package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.plinko.common.ApiException;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Review N14.6 (and 2.3): a repeated request id gets its round again, and 409 ROUND_STILL_RUNNING while that round is still being played. */
class RepeatedRequestTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);
    private final String requestId = UUID.randomUUID().toString();

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected the game to refuse");
    }

    private GameRepository.Round only() {
        assertThat(game.repository.rounds()).hasSize(1);
        return game.repository.rounds().values().iterator().next();
    }

    private void setStatus(String status) {
        game.repository.edit(only().id(), r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(),
                r.balls(), r.totalBet(), r.totalWin(), r.currency(), status, r.slots(), r.multipliers(), r.error(), r.signature(), r.createdAt(),
                r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin()));
    }

    /** Plays the request in the background with this wallet call held; returns once the round is waiting on it. */
    private Thread heldOn(String call, CountDownLatch release, AtomicReference<GameService.PlayResponse> answer) throws InterruptedException {
        CountDownLatch arrived = new CountDownLatch(1);
        game.wallet.next(call, new MemoryWallet.Hold(arrived, release));
        Thread first = Thread.ofVirtual().start(() -> answer.set(game.play("1.00", 3, requestId)));
        assertThat(arrived.await(5, TimeUnit.SECONDS)).as("the first copy reached the wallet's " + call).isTrue();
        return first;
    }

    private void assertStillRunning(String status) {
        assertThat(only().status()).isEqualTo(status);
        List<String> calls = List.copyOf(game.wallet.calls);
        ApiException repeat = refusal(() -> game.play("1.00", 3, requestId));
        assertThat(repeat.code()).isEqualTo("ROUND_STILL_RUNNING");
        assertThat(repeat.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(game.wallet.calls).as("the repeat moved no money").isEqualTo(calls);
        assertThat(game.repository.rounds()).as("and played no second round").hasSize(1);
    }

    private void assertTheSameRoundAfterwards(Thread first, AtomicReference<GameService.PlayResponse> answer) throws InterruptedException {
        first.join(5000);
        assertThat(answer.get()).as("the first copy finished").isNotNull();
        GameService.PlayResponse again = game.play("1.00", 3, requestId);
        assertThat(again.roundId()).isEqualTo(answer.get().roundId());
        assertThat(again.slots()).isEqualTo(answer.get().slots());
        assertThat(again.totalWin()).isEqualByComparingTo(answer.get().totalWin());
        assertThat(game.wallet.count("debit")).as("one bet only").isEqualTo(1);
        assertThat(game.wallet.count("credit")).isEqualTo(1);
    }

    @Test
    void aRepeatWhileTheBetIsStillAtTheWalletIsToldTheRoundIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<GameService.PlayResponse> answer = new AtomicReference<>();
        Thread first = heldOn("debit", release, answer);
        try {
            assertStillRunning(RoundStatus.PENDING);
        } finally {
            release.countDown();
        }
        assertTheSameRoundAfterwards(first, answer);
    }

    @Test
    void aRepeatWhileTheWinIsStillAtTheWalletIsToldTheRoundIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<GameService.PlayResponse> answer = new AtomicReference<>();
        Thread first = heldOn("credit", release, answer);
        try {
            assertStillRunning(RoundStatus.DEBITED);
        } finally {
            release.countDown();
        }
        assertTheSameRoundAfterwards(first, answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {RoundStatus.PENDING, RoundStatus.DEBITED})
    void aRepeatOfARoundLeftUnfinishedIsToldItIsStillRunning(String status) {
        game.play("1.00", 3, requestId);
        setStatus(status);
        assertStillRunning(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {RoundStatus.COMPLETED, RoundStatus.CREDIT_PENDING})
    void aRepeatOfAPlayedRoundReturnsItAgainWithoutANewBet(String status) {
        GameService.PlayResponse first = game.play("1.00", 3, requestId);
        setStatus(status);
        BigDecimal balance = game.balance();
        GameService.PlayResponse again = game.play("1.00", 3, requestId);
        assertThat(again.roundId()).isEqualTo(first.roundId());
        assertThat(again.slots()).isEqualTo(first.slots());
        assertThat(again.multipliers()).isEqualTo(first.multipliers());
        assertThat(again.wins()).isEqualTo(first.wins());
        assertThat(again.balance()).isEqualByComparingTo(balance);
        assertThat(game.wallet.count("debit")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {RoundStatus.REJECTED, RoundStatus.CANCELLED, RoundStatus.ROLLBACK_PENDING})
    void aRepeatOfARoundThatWasNotPlayedSaysSoAndTakesNoBet(String status) {
        game.play("1.00", 3, requestId);
        setStatus(status);
        ApiException repeat = refusal(() -> game.play("1.00", 3, requestId));
        assertThat(repeat.code()).isEqualTo("REQUEST_NOT_PLAYED");
        assertThat(repeat.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(game.wallet.count("debit")).isEqualTo(1);
    }

    @Test
    void aRepeatOfABlockedRoundIsUnderReview() {
        game.play("1.00", 3, requestId);
        setStatus(RoundStatus.TAMPERED);
        ApiException repeat = refusal(() -> game.play("1.00", 3, requestId));
        assertThat(repeat.code()).isEqualTo("ROUND_UNDER_REVIEW");
        assertThat(repeat.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void theSameRequestIdForADifferentBetIsRefused() {
        game.play("1.00", 3, requestId);
        for (Runnable other : List.<Runnable>of(() -> game.play("2.00", 3, requestId), () -> game.play("1.00", 2, requestId),
                () -> game.game.play("s", new GameService.PlayRequest(9, 1, new BigDecimal("1.00"), 3, requestId)),
                () -> game.game.play("s", new GameService.PlayRequest(8, 2, new BigDecimal("1.00"), 3, requestId)))) {
            ApiException refused = refusal(other);
            assertThat(refused.code()).isEqualTo("REQUEST_MISMATCH");
            assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(game.wallet.count("debit")).isEqualTo(1);
    }

    @Test
    void aRequestIdThatIsNotAUuidIsRefused() {
        ApiException refused = refusal(() -> game.play("1.00", 3, "not-a-uuid"));
        assertThat(refused.code()).isEqualTo("INVALID_REQUEST");
        assertThat(game.wallet.calls).isEmpty();
    }
}
