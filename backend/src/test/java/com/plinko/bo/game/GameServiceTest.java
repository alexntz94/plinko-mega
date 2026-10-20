package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import com.plinko.common.ApiException;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Review 5.1 #9, 5.2 and 5.3 #5, #6, #8: whole rounds through the real game service over in-memory tables and wallet:
 * the live credit check blocking an edited round, the round lock across a slow wallet, a player or game switched off
 * mid-session, session expiry and extension, a platform currency change with sessions open, and two rounds racing on the
 * bonus meter.
 */
class GameServiceTest {

    private static final BonusSettings METER = new BonusSettings(new BonusSettings.Meter(true, 3, new BigDecimal("2"), new BigDecimal("30")));

    private final TestGame game = new TestGame(BonusSettings.OFF);

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected the game to refuse");
    }

    // ---------------------------------------------------------------- 5.2: the live path blocks an edited round

    /** Edits the round in the database between its outcome being saved and its payment (when the credit row is written). */
    private void editBeforeThePayment(TestGame on, UnaryOperator<GameRepository.Round> edit) {
        on.repository.before("insertTransaction", args -> {
            GameRepository.Transaction tx = (GameRepository.Transaction) args[0];
            if ("CREDIT".equals(tx.type())) {
                on.repository.edit(tx.roundId(), edit);
            }
        });
    }

    private void assertBlockedLive(TestGame on) {
        BigDecimal before = on.balance();
        ApiException refused = refusal(() -> on.play("1.00", 3));
        assertThat(refused.code()).isEqualTo("ROUND_UNDER_REVIEW");
        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        GameRepository.Round round = on.repository.rounds().values().iterator().next();
        assertThat(round.status()).isEqualTo(RoundStatus.TAMPERED);
        assertThat(on.wallet.count("credit")).as("never paid").isZero();
        assertThat(on.balance()).as("only the bet moved").isEqualByComparingTo(before.subtract(new BigDecimal("3.00")));
        verify(on.audit).log(eq("system"), eq("ROUND_TAMPERED"), contains(round.id()));
        assertThat(on.repository.lockHeld(on.player.id())).as("the player isn't stuck").isFalse();
        // the background retry never pays a blocked round either
        on.recoverInTheBackground();
        assertThat(on.wallet.count("credit")).isZero();
        assertThat(on.repository.round(round.id()).status()).isEqualTo(RoundStatus.TAMPERED);
    }

    @Test
    void aWinEditedBeforeItsLivePaymentIsBlockedNeverPaidAndAudited() {
        editBeforeThePayment(game, r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin().add(new BigDecimal("500")), r.currency(), r.status(), r.slots(), r.multipliers(), r.error(), r.signature(),
                r.createdAt(), r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
        assertBlockedLive(game);
    }

    @Test
    void aMultiplierEditedBeforeItsLivePaymentIsBlockedNeverPaidAndAudited() {
        editBeforeThePayment(game, r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin(), r.currency(), r.status(), r.slots(), "1000," + r.multipliers().substring(r.multipliers().indexOf(',') + 1),
                r.error(), r.signature(), r.createdAt(), r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
        assertBlockedLive(game);
    }

    @Test
    void aRoundReadsTheSettingsOnceAsOneSnapshot() {
        // N14.10: the switch, the currency, the limits, the features and the bonus of a round all come from one read
        org.mockito.Mockito.clearInvocations(game.settings);
        game.play("1.00", 3);
        org.mockito.Mockito.verify(game.settings, org.mockito.Mockito.times(1)).snapshot();
        org.mockito.Mockito.verify(game.settings, org.mockito.Mockito.never()).version();
        org.mockito.Mockito.verify(game.settings, org.mockito.Mockito.never()).rawValues();
    }

    @Test
    void normalRoundsStillPayAfterABlockedOne() {
        editBeforeThePayment(game, r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(),
                r.totalBet(), r.totalWin().add(BigDecimal.ONE), r.currency(), r.status(), r.slots(), r.multipliers(), r.error(), r.signature(),
                r.createdAt(), r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
        refusal(() -> game.play("1.00", 3));
        game.repository.before("insertTransaction", args -> { });
        GameService.PlayResponse next = game.play("1.00", 3);
        assertThat(game.repository.round(next.roundId()).status()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(game.wallet.count("credit")).isEqualTo(1);
        assertThat(next.balance()).isEqualByComparingTo(game.balance());
    }

    // ---------------------------------------------------------------- 5.1 #9: the round lock holds across a slow wallet

    @Test
    void aSecondPlayWhileTheWalletIsSlowIsRefusedAndOnlyOneBetIsTaken() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        game.wallet.next("debit", new MemoryWallet.Hold(arrived, release));
        Thread first = Thread.ofVirtual().start(() -> game.play("1.00", 3));
        assertThat(arrived.await(5, TimeUnit.SECONDS)).as("the first bet reached the wallet").isTrue();

        ApiException second = refusal(() -> game.play("1.00", 3));
        assertThat(second.code()).isEqualTo("ROUND_IN_PROGRESS");
        assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);

        release.countDown();
        first.join(5000);
        assertThat(game.wallet.count("debit")).as("one bet only").isEqualTo(1);
        assertThat(game.repository.rounds()).hasSize(1);
        assertThat(game.repository.rounds().values().iterator().next().status()).isEqualTo(RoundStatus.COMPLETED);
        // once it is over the next round plays
        assertThat(game.play("1.00", 1).roundId()).isNotNull();
    }

    // ---------------------------------------------------------------- 5.3 #5: switched off during a session

    @Test
    void aPlayerBlockedMidSessionCantPlayOrResumeAndNoBetIsTaken() {
        game.play("1.00", 1);
        game.repository.setBlocked(game.player.id(), true);
        List<String> calls = List.copyOf(game.wallet.calls);
        assertThat(refusal(() -> game.play("1.00", 1)).code()).isEqualTo("PLAYER_BLOCKED");
        assertThat(refusal(() -> game.game.resumeSession("s")).code()).isEqualTo("PLAYER_BLOCKED");
        assertThat(game.wallet.calls).as("no money moved").isEqualTo(calls);
        assertThat(game.repository.rounds()).hasSize(1);
    }

    @Test
    void theGameSwitchedOffMidSessionRefusesPlayAndResumeUntilItIsBackOn() {
        game.play("1.00", 1);
        game.enabled.set(false);
        List<String> calls = List.copyOf(game.wallet.calls);
        ApiException play = refusal(() -> game.play("1.00", 1));
        assertThat(play.code()).isEqualTo("GAME_DISABLED");
        assertThat(play.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusal(() -> game.game.resumeSession("s")).code()).isEqualTo("GAME_DISABLED");
        assertThat(refusal(() -> game.game.openSession("new-launch", null)).code()).isEqualTo("GAME_DISABLED");
        assertThat(game.wallet.calls).as("no money moved, not even a balance read").isEqualTo(calls);
        assertThat(game.repository.rounds()).hasSize(1);

        game.enabled.set(true);
        assertThat(game.game.resumeSession("s").balance()).isEqualByComparingTo(game.balance());
        assertThat(game.play("1.00", 1).roundId()).isNotNull();
    }

    // ---------------------------------------------------------------- 5.3 #6: session expiry and extension

    private Instant expiry() {
        return game.repository.findSession("s").orElseThrow().expiresAt();
    }

    @Test
    void playingOrResumingExtendsTheSessionByItsConfiguredLength() {
        Duration ttl = Duration.ofMinutes(game.properties.sessionTtlMinutes());
        game.repository.addSession(new GameRepository.Session("s", game.player.id(), "tok", Instant.now().plusSeconds(30), "loteria-muerta"));
        Instant before = Instant.now();
        game.play("1.00", 1);
        assertThat(expiry()).isBetween(before.plus(ttl).minusSeconds(1), Instant.now().plus(ttl).plusSeconds(1));

        game.repository.addSession(new GameRepository.Session("s", game.player.id(), "tok", Instant.now().plusSeconds(30), "loteria-muerta"));
        before = Instant.now();
        game.game.resumeSession("s");
        assertThat(expiry()).isBetween(before.plus(ttl).minusSeconds(1), Instant.now().plus(ttl).plusSeconds(1));
    }

    @Test
    void anExpiredSessionCantPlayResumeOrReadItsHistory() {
        game.play("1.00", 1);
        game.repository.addSession(new GameRepository.Session("s", game.player.id(), "tok", Instant.now().minusSeconds(1), "loteria-muerta"));
        List<String> calls = List.copyOf(game.wallet.calls);
        for (Runnable call : List.<Runnable>of(() -> game.play("1.00", 1), () -> game.game.resumeSession("s"),
                () -> game.game.history("s", null, 10))) {
            ApiException refused = refusal(call);
            assertThat(refused.code()).isEqualTo("SESSION_EXPIRED");
            assertThat(refused.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(game.wallet.calls).isEqualTo(calls);
        // an expired session is not brought back by trying
        assertThat(expiry()).isBefore(Instant.now());
    }

    // ---------------------------------------------------------------- 5.3 #8: the platform currency changed with sessions open

    @Test
    void aCurrencyChangeWithSessionsOpenIsRefusedBeforeAnyMoneyMoves() {
        game.play("1.00", 1);
        game.currency.set("USD");
        List<String> calls = List.copyOf(game.wallet.calls);
        ApiException play = refusal(() -> game.play("1.00", 1));
        assertThat(play.code()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(play.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal(() -> game.game.resumeSession("s")).code()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        // a new launch of a EUR wallet too, before its launch link is used up
        assertThat(refusal(() -> game.game.openSession("eur-wallet", null)).code()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(game.wallet.calls).as("no bet, no balance read").isEqualTo(calls);
        assertThat(game.repository.rounds()).hasSize(1);
    }

    // ---------------------------------------------------------------- new: two rounds racing on the bonus meter

    @Test
    void aRoundThatLosesTheRaceOnTheBonusMeterIsRefundedAndLeavesTheMeterAlone() {
        TestGame meter = new TestGame(METER);
        meter.play("1.00", 1);
        String pointer = meter.repository.meterRound(meter.player.id()).orElseThrow();
        // another round (e.g. on an instance whose round lock had run out) continues from the same meter state first
        meter.repository.before("saveOutcome", args -> {
            if (pointer.equals(args[1])) {
                meter.repository.continueMeter(pointer, "other-round");
            }
        });
        BigDecimal before = meter.balance();
        ApiException refused = refusal(() -> meter.play("1.00", 2));
        assertThat(refused.code()).isEqualTo("ROUND_IN_PROGRESS");
        GameRepository.Round lost = meter.repository.rounds().values().stream().reduce((a, b) -> b).orElseThrow();
        assertThat(lost.status()).isEqualTo(RoundStatus.CANCELLED);
        assertThat(lost.signature()).as("its outcome was never kept").isNull();
        assertThat(meter.balance()).as("its bet came back").isEqualByComparingTo(before);
        assertThat(meter.wallet.count("rollback")).isEqualTo(1);
        assertThat(meter.repository.meterRound(meter.player.id())).as("the meter pointer was not moved by the lost round").contains(pointer);
        assertThat(meter.repository.lockHeld(meter.player.id())).isFalse();
    }
}
