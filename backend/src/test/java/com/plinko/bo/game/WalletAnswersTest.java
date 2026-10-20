package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.plinko.common.ApiException;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamException;
import com.plinko.repo.GameRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

/**
 * Review 5.3 and 7.4: what the player is told for each answer of the casino's wallet (a refusal, the casino's own limits
 * and exclusions, no answer), opening a session from a launch link once, and the bet refunded whenever its outcome is unknown.
 */
class WalletAnswersTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);

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

    @ParameterizedTest
    @CsvSource({
            PamException.INSUFFICIENT_FUNDS + ", INSUFFICIENT_FUNDS, PAYMENT_REQUIRED",
            PamException.INVALID_TOKEN + ", SESSION_EXPIRED, UNAUTHORIZED",
            PamException.LIMIT_REACHED + ", LIMIT_REACHED, FORBIDDEN",
            PamException.PLAYER_EXCLUDED + ", PLAYER_BLOCKED, FORBIDDEN",
            PamException.PLAYER_BLOCKED + ", PLAYER_BLOCKED, FORBIDDEN"})
    void aBetTheWalletRefusesIsShownForWhatItIsAndNothingIsRefunded(String walletCode, String code, HttpStatus status) {
        game.wallet.next("debit", new MemoryWallet.Refuse(walletCode));
        ApiException refused = refusal(() -> game.play("1.00", 2));
        assertThat(refused.code()).isEqualTo(code);
        assertThat(refused.status()).isEqualTo(status);
        assertThat(only().status()).isEqualTo(RoundStatus.REJECTED);
        assertThat(only().error()).isEqualTo(walletCode);
        assertThat(game.wallet.count("rollback")).as("a definite refusal needs no refund").isZero();
        assertThat(game.balance()).isEqualByComparingTo("100.00");
        assertThat(game.repository.lockHeld(game.player.id())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"SOMETHING_ELSE, 400", "DUPLICATE_TRANSACTION, 409", "INSUFFICIENT_FUNDS, 409"})
    void aBetAnswerThatIsNotAListedRefusalIsRefunded(String walletCode, int httpStatus) {
        // R10: any other 4xx, and any 409, may mean the wallet holds the debit: the bet is rolled back (a rollback of a
        // debit the wallet never applied refunds nothing, R1.3)
        game.wallet.next("debit", new MemoryWallet.Refuse(walletCode, httpStatus));
        ApiException refused = refusal(() -> game.play("1.00", 2));
        assertThat(refused.code()).isEqualTo("WALLET_UNAVAILABLE");
        assertThat(only().status()).isEqualTo(RoundStatus.CANCELLED);
        assertThat(game.wallet.count("rollback")).isEqualTo(1);
        assertThat(game.balance()).isEqualByComparingTo("100.00");
        assertThat(game.repository.lockHeld(game.player.id())).isFalse();
    }

    @Test
    void aBetWithoutAnAnswerIsRefundedAndTheRoundCancelled() {
        game.wallet.next("debit", new MemoryWallet.LostAfter());
        ApiException refused = refusal(() -> game.play("1.00", 2));
        assertThat(refused.code()).isEqualTo("WALLET_UNAVAILABLE");
        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(only().status()).isEqualTo(RoundStatus.CANCELLED);
        assertThat(game.wallet.count("rollback")).isEqualTo(1);
        assertThat(game.balance()).as("the bet the wallet took came back").isEqualByComparingTo("100.00");
    }

    @Test
    void aRefundThatFailsTooIsLeftForTheBackgroundRetry() {
        game.wallet.next("debit", new MemoryWallet.LostAfter());
        game.wallet.next("rollback", new MemoryWallet.LostBefore());
        assertThat(refusal(() -> game.play("1.00", 2)).code()).isEqualTo("WALLET_UNAVAILABLE");
        assertThat(only().status()).isEqualTo(RoundStatus.ROLLBACK_PENDING);
        assertThat(game.balance()).as("still taken, until the retry").isEqualByComparingTo("98.00");
        game.recoverInTheBackground();
        assertThat(only().status()).isEqualTo(RoundStatus.CANCELLED);
        assertThat(game.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void aWinTheWalletDidNotConfirmIsPaidOnceWhenAnAdminRetriesIt() {
        game.wallet.next("credit", new MemoryWallet.LostBefore());
        GameService.PlayResponse played = game.play("1.00", 2);
        GameRepository.Round owed = only();
        assertThat(owed.status()).isEqualTo(RoundStatus.CREDIT_PENDING);
        BigDecimal expected = new BigDecimal("98.00").add(owed.totalWin());
        assertThat(played.balance()).isEqualByComparingTo(expected);

        assertThat(game.recovery.retryExclusive(owed)).isNotBlank();
        assertThat(only().status()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(game.balance()).isEqualByComparingTo(expected);
        // retried again (a second click): nothing more is paid
        game.recovery.retryExclusive(only());
        assertThat(game.balance()).isEqualByComparingTo(expected);
        assertThat(only().status()).isEqualTo(RoundStatus.COMPLETED);
    }

    @Test
    void aLaunchLinkOpensOneSession() {
        GameService.SessionResponse opened = game.game.openSession("launch-1", null);
        assertThat(opened.sessionId()).isNotBlank();
        assertThat(opened.currency()).isEqualTo("EUR");
        assertThat(opened.balance()).isEqualByComparingTo("100.00");
        assertThat(opened.meter().enabled()).isFalse();
        assertThat(opened.config().rows()).isNotEmpty();
        ApiException again = refusal(() -> game.game.openSession("launch-1", null));
        assertThat(again.code()).isEqualTo("INVALID_TOKEN");
        assertThat(again.getMessage()).contains("already used");
        // the new session plays
        assertThat(game.game.play(opened.sessionId(), new GameService.PlayRequest(8, 1, new BigDecimal("1.00"), 1)).roundId()).isNotNull();
    }

    @Test
    void anEmptyOrOverlongLaunchTokenOrSessionIdIsRefusedBeforeTheWalletIsAsked() {
        for (String token : new String[] {null, " ", "x".repeat(513)}) {
            assertThat(refusal(() -> game.game.openSession(token, null)).code()).isEqualTo("INVALID_TOKEN");
        }
        assertThat(refusal(() -> game.game.resumeSession("x".repeat(65))).code()).isEqualTo("SESSION_EXPIRED");
        assertThat(refusal(() -> game.game.resumeSession(null)).code()).isEqualTo("SESSION_EXPIRED");
        assertThat(refusal(() -> game.game.resumeSession("unknown")).code()).isEqualTo("SESSION_EXPIRED");
        assertThat(game.wallet.calls).isEmpty();
    }

    @Test
    void aPlayerBlockedBeforeTheyOpenTheGameCantOpenIt() {
        long id = game.repository.upsertPlayer("pam-launch-9", "pam-launch-9", "EUR", BigDecimal.TEN).id();
        game.repository.setBlocked(id, true);
        ApiException blocked = refusal(() -> game.game.openSession("launch-9", null));
        assertThat(blocked.code()).isEqualTo("PLAYER_BLOCKED");
        assertThat(blocked.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aBalanceTheWalletWontGiveEndsOrPausesTheSession() {
        game.wallet.next("balance", new MemoryWallet.Refuse(PamException.INVALID_TOKEN));
        ApiException expired = refusal(() -> game.game.resumeSession("s"));
        assertThat(expired.code()).isEqualTo("SESSION_EXPIRED");
        game.wallet.next("balance", new MemoryWallet.LostBefore());
        ApiException unavailable = refusal(() -> game.game.resumeSession("s"));
        assertThat(unavailable.code()).isEqualTo("WALLET_UNAVAILABLE");
        assertThat(unavailable.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(game.game.resumeSession("s").balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void aHistoryCursorThisServerDidNotGiveIsRefused() {
        game.play("1.00", 1);
        for (String cursor : List.of("nonsense", "12:bad id!", "x:abc")) {
            assertThat(refusal(() -> game.game.history("s", cursor, 10)).code()).as(cursor).isEqualTo("INVALID_REQUEST");
        }
        assertThat(game.game.history("s", "", 500).rounds()).hasSize(1);
    }

    @Test
    void eachRoundsDatabaseTimeIsMeasuredPerStep() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        game.game.setMeters(meters);
        game.play("1.00", 1);
        for (String step : List.of("open", "record", "settle")) {
            assertThat(meters.get("plinko.round.db").tag("step", step).timer().count()).as(step).isEqualTo(1);
        }
    }
}
