package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Review 5.1 #10 and #17: a round's writes come in three transactions, (a) the round and its bet, (b) the bet taken
 * and the signed outcome, (c) the payment's answer; a database error inside each one, and the process dying right
 * after each one commits, must leave a state the background recovery finishes correctly: the signed win paid once,
 * or the bet refunded once, and never both.
 */
class RoundCrashRecoveryTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);
    private final BigDecimal start = game.balance();

    /** The only round played so far. */
    private GameRepository.Round round() {
        assertThat(game.repository.rounds()).hasSize(1);
        return game.repository.rounds().values().iterator().next();
    }

    private void assertWonOnce(GameRepository.Round round) {
        assertThat(round.status()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(game.balance()).as("bet taken once, win paid once").isEqualByComparingTo(start.subtract(round.totalBet()).add(round.totalWin()));
        assertThat(game.repository.transactionsOf(round.id())).extracting(GameRepository.Transaction::type).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(game.repository.transactionsOf(round.id())).allMatch(tx -> "OK".equals(tx.status()));
    }

    private void assertRefundedOnce(GameRepository.Round round) {
        assertThat(round.status()).isEqualTo(RoundStatus.CANCELLED);
        assertThat(round.signature()).as("no outcome was kept").isNull();
        assertThat(game.balance()).as("the bet came back, nothing else moved").isEqualByComparingTo(start);
        assertThat(game.repository.transactionsOf(round.id())).extracting(GameRepository.Transaction::type).containsExactlyInAnyOrder("DEBIT", "ROLLBACK");
        assertThat(game.wallet.count("credit")).isZero();
    }

    /** Recovery run twice more changes nothing: no second payment or refund. */
    private void assertRecoveryIsDone() {
        List<String> calls = List.copyOf(game.wallet.calls);
        BigDecimal balance = game.balance();
        game.recoverInTheBackground();
        game.recoverInTheBackground();
        assertThat(game.wallet.calls).isEqualTo(calls);
        assertThat(game.balance()).isEqualByComparingTo(balance);
        assertThat(game.repository.lockHeld(game.player.id())).as("the player can play again").isFalse();
        assertThat(game.play("1.00", 1).roundId()).isNotNull();
    }

    // ---------------------------------------------------------------- a database error inside each transaction

    @Test
    void aFailureInTheFirstTransactionLeavesNothingBehindAndSendsNoBet() {
        game.repository.failNext("insertTransaction");
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(DataAccessException.class);
        assertThat(game.repository.rounds()).as("the round was rolled back with its bet").isEmpty();
        assertThat(game.wallet.calls).isEmpty();
        assertThat(game.repository.lockHeld(game.player.id())).as("the round lock was rolled back too").isFalse();
        game.recoverInTheBackground();
        assertThat(game.wallet.calls).isEmpty();
        assertThat(game.balance()).isEqualByComparingTo(start);
        assertRecoveryIsDone();
    }

    @Test
    void aFailureInTheSecondTransactionRefundsTheBetItTook() {
        game.repository.failNext("saveOutcome");
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(DataAccessException.class);
        assertThat(round().status()).as("the outcome was rolled back").isEqualTo(RoundStatus.PENDING);
        assertThat(game.balance()).as("the wallet took the bet").isEqualByComparingTo(start.subtract(new BigDecimal("3.00")));
        game.recoverInTheBackground();
        assertRefundedOnce(round());
        assertRecoveryIsDone();
    }

    @Test
    void aFailureInTheThirdTransactionAfterThePaymentIsNotPaidTwice() {
        game.repository.failNext("settlePlayer");
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(DataAccessException.class);
        GameRepository.Round left = round();
        assertThat(left.status()).as("the payment's answer was rolled back").isEqualTo(RoundStatus.DEBITED);
        BigDecimal paidOnce = start.subtract(left.totalBet()).add(left.totalWin());
        assertThat(game.balance()).as("the wallet paid it").isEqualByComparingTo(paidOnce);
        game.recoverInTheBackground();
        assertWonOnce(round());
        // sent again with its own id, which the wallet recognised as the same payment
        assertThat(game.wallet.calls).filteredOn(c -> c.startsWith("credit ")).hasSize(2).containsOnly(game.wallet.calls.stream()
                .filter(c -> c.startsWith("credit ")).findFirst().orElseThrow());
        assertRecoveryIsDone();
    }

    // ---------------------------------------------------------------- the process dies right after each commit

    @Test
    void dyingAfterTheFirstTransactionRefundsABetTheWalletNeverSaw() {
        game.repository.dieAfterCommit(1);
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(MemoryGameRepository.ProcessDied.class);
        game.repository.restart();
        assertThat(round().status()).isEqualTo(RoundStatus.PENDING);
        assertThat(game.wallet.calls).as("the bet was never sent").isEmpty();
        game.recoverInTheBackground();
        assertRefundedOnce(round());
        // the debit can't arrive late and be kept: the rollback came first (R1.3/R1.4)
        String debitId = game.repository.transactionsOf(round().id()).stream().filter(t -> "DEBIT".equals(t.type())).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> game.wallet.debit(new com.plinko.pam.PamClient.WalletRequest("pam-7", "tok", debitId, round().id(),
                new BigDecimal("3.00"), "EUR"))).isInstanceOf(com.plinko.pam.PamException.class);
        assertThat(game.balance()).isEqualByComparingTo(start);
        assertRecoveryIsDone();
    }

    @Test
    void dyingAfterTheOutcomeIsSavedPaysTheSignedWinOnce() {
        // review 5.1 #10: killed after the outcome was saved, before the payment was sent
        game.repository.dieAfterCommit(2);
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(MemoryGameRepository.ProcessDied.class);
        game.repository.restart();
        GameRepository.Round left = round();
        assertThat(left.status()).isEqualTo(RoundStatus.DEBITED);
        assertThat(left.signature()).isNotNull();
        assertThat(game.wallet.count("credit")).isZero();
        game.recoverInTheBackground();
        GameRepository.Round done = round();
        assertWonOnce(done);
        assertThat(done.totalWin()).as("exactly the win that was signed before the crash").isEqualByComparingTo(left.totalWin());
        assertThat(game.wallet.count("credit")).isEqualTo(1);
        assertThat(game.wallet.count("rollback")).isZero();
        assertRecoveryIsDone();
    }

    @Test
    void dyingWhileThePaymentIsInFlightPaysItOnce() {
        // the wallet applied the payment, but the process died before its answer was written
        game.wallet.next("credit", new MemoryWallet.AppliedThen(() -> {
            game.repository.die();
            throw new MemoryGameRepository.ProcessDied("killed with the payment in flight");
        }));
        assertThatThrownBy(() -> game.play("1.00", 3)).isInstanceOf(MemoryGameRepository.ProcessDied.class);
        game.repository.restart();
        GameRepository.Round left = round();
        assertThat(left.status()).isEqualTo(RoundStatus.DEBITED);
        assertThat(game.balance()).as("the wallet paid it").isEqualByComparingTo(start.subtract(left.totalBet()).add(left.totalWin()));
        game.recoverInTheBackground();
        assertWonOnce(round());
        assertThat(game.wallet.count("credit")).as("sent again with the same id").isEqualTo(2);
        assertRecoveryIsDone();
    }

    @Test
    void aLateCreditNeitherMarksItsDayNorCountsTheRoundAgain() {
        // R17.12: CREDIT_PENDING -> COMPLETED is counted either way (one increment, written with CREDIT_PENDING): the day's
        // totals don't change, so the day is not marked for a rebuild and no second increment is written
        DailyTotals days = org.mockito.Mockito.mock(DailyTotals.class);
        com.plinko.repo.StatsRepository stats = org.mockito.Mockito.mock(com.plinko.repo.StatsRepository.class);
        game.recovery.setDailyTotals(days);
        game.recovery.setStats(stats);
        game.wallet.next("credit", new MemoryWallet.LostBefore());
        game.play("1.00", 3);
        assertThat(round().status()).isEqualTo(RoundStatus.CREDIT_PENDING);
        game.recoverInTheBackground();
        assertWonOnce(round());
        org.mockito.Mockito.verify(days, org.mockito.Mockito.never()).roundSettled(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(stats, org.mockito.Mockito.never()).addDelta(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void anAnswerLostAfterThePaymentWasAppliedIsRetriedWithTheSameIdAndPaidOnce() {
        game.wallet.next("credit", new MemoryWallet.LostAfter());
        GameService.PlayResponse response = game.play("1.00", 3);
        assertThat(round().status()).isEqualTo(RoundStatus.CREDIT_PENDING);
        game.recoverInTheBackground();
        assertWonOnce(round());
        assertThat(response.totalWin()).isEqualByComparingTo(round().totalWin());
        assertThat(game.wallet.count("credit")).isEqualTo(2);
        assertRecoveryIsDone();
    }
}
