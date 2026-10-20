package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoundRecoveryServiceTest {

    private final RoundSigner roundSigner = new RoundSigner(TestFixtures.properties());
    private final WalletSigner walletSigner = new WalletSigner(new Integrity(TestFixtures.properties()));
    private final GameRepository repository = mock(GameRepository.class);
    private final PamClient pam = mock(PamClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final RoundRecoveryService recovery = new RoundRecoveryService(repository, pam, roundSigner, walletSigner,
            audit, new WinCheck(roundSigner, walletSigner, mock(com.plinko.bo.odds.OddsService.class),
            TestFixtures.properties()), TestFixtures.properties());

    private final GameRepository.Player player = new GameRepository.Player(7, "pam-7", "alice", "EUR", null, false, Instant.now(), Instant.now());
    private final List<GameRepository.Transaction> transactions = new ArrayList<>();
    private final BigDecimal win = new BigDecimal("29.50");

    @BeforeEach
    void setUp() {
        when(repository.findPlayer(7)).thenReturn(Optional.of(player));
        when(repository.findSession(anyString())).thenReturn(Optional.empty());
        when(repository.transactionsOfRound(anyString())).thenAnswer(i -> List.copyOf(transactions));
        when(pam.credit(any())).thenReturn(new PamClient.PamResult(new BigDecimal("100"), "ref"));
        when(pam.rollback(any())).thenReturn(new PamClient.PamResult(new BigDecimal("100"), "ref"));
        when(repository.transitionRound(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private GameRepository.Round round(String status, boolean decided) {
        String signature = decided ? roundSigner.sign("r1", 7, 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"), win, "EUR", "0,1,2",
                "0.2,29,0.3", "path:0,1,3") : null;
        GameRepository.Round round = new GameRepository.Round("r1", 7, "s", 8, 2, new BigDecimal("1.00"), 3, new BigDecimal("3.00"),
                decided ? win : null, "EUR", status, decided ? "0,1,2" : null, decided ? "0.2,29,0.3" : null, null, signature, null, null,
                decided ? "path:0,1,3" : null, null, null);
        when(repository.findRound("r1")).thenReturn(Optional.of(round));
        return round;
    }

    private void debit() {
        transactions.add(new GameRepository.Transaction("debit-1", "r1", 7, "DEBIT", new BigDecimal("3.00"), "OK", null, null, null, 1, null, null, null));
    }

    @Test
    void aCreditBegunBeforeAKeyRotationIsPaidByTheRetryUnderItsOwnId() {
        // N1: the credit row was made under the original key; the keys are then rotated (r1, d1, e1) and the retry runs
        pendingCredit(1);
        org.mockito.Mockito.doReturn(new PamClient.PamResult(new BigDecimal("100"), "ref")).when(pam).credit(any());
        String originalId = walletSigner.creditId("r1");
        com.plinko.common.KeyRing rotated = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40));
        RoundSigner rotatedRounds = new RoundSigner(rotated);
        WalletSigner rotatedWallet = new WalletSigner(new Integrity(rotated));
        RoundRecoveryService afterRotation = new RoundRecoveryService(repository, pam, rotatedRounds, rotatedWallet, audit,
                new WinCheck(rotatedRounds, rotatedWallet, mock(com.plinko.bo.odds.OddsService.class), TestFixtures.properties()),
                TestFixtures.properties());
        assertThat(rotatedWallet.creditId("r1")).isNotEqualTo(originalId);

        assertThat(afterRotation.retry(round(RoundStatus.CREDIT_PENDING, true))).isEqualTo("Credit paid");
        ArgumentCaptor<PamClient.WalletRequest> sent = ArgumentCaptor.forClass(PamClient.WalletRequest.class);
        verify(pam).credit(sent.capture());
        assertThat(sent.getValue().transactionId()).isEqualTo(originalId);    // the wallet sees the same id: never paid twice
        verify(repository).updateRoundStatus("r1", RoundStatus.COMPLETED, null);
        verify(repository, never()).updateRoundStatus(anyString(), eq(RoundStatus.TAMPERED), any());
    }

    @Test
    void interruptedRoundWithOutcomePaysTheSignedWinOnce() {
        debit();
        recovery.resolveInterrupted(round(RoundStatus.DEBITED, true));
        ArgumentCaptor<GameRepository.Transaction> inserted = ArgumentCaptor.forClass(GameRepository.Transaction.class);
        verify(repository).insertTransaction(inserted.capture());
        assertThat(inserted.getValue().id()).isEqualTo(walletSigner.creditId("r1"));
        assertThat(inserted.getValue().amount()).isEqualByComparingTo(win);
        verify(repository).transitionRound("r1", RoundStatus.DEBITED, RoundStatus.CREDIT_PENDING, "Interrupted round: paying the win");
    }

    @Test
    void interruptedRoundWithoutOutcomeRefundsTheBet() {
        debit();
        recovery.resolveInterrupted(round(RoundStatus.PENDING, false));
        ArgumentCaptor<GameRepository.Transaction> inserted = ArgumentCaptor.forClass(GameRepository.Transaction.class);
        verify(repository).insertTransaction(inserted.capture());
        assertThat(inserted.getValue().type()).isEqualTo("ROLLBACK");
        assertThat(inserted.getValue().originalTxId()).isEqualTo("debit-1");
        verify(repository).transitionRound("r1", RoundStatus.PENDING, RoundStatus.ROLLBACK_PENDING, "Interrupted round: refunding the bet");
    }

    @Test
    void decidedRoundSetBackToPendingIsPaidNotRefunded() {
        // someone sets a decided round back to PENDING hoping for a refund on top of the win
        debit();
        recovery.resolveInterrupted(round(RoundStatus.PENDING, true));
        ArgumentCaptor<GameRepository.Transaction> inserted = ArgumentCaptor.forClass(GameRepository.Transaction.class);
        verify(repository).insertTransaction(inserted.capture());
        assertThat(inserted.getValue().type()).isEqualTo("CREDIT");
    }

    @Test
    void creditWithoutTheGamesSignatureIsNeverPaid() {
        debit();
        transactions.add(new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", win, "FAILED", null, null, null, 1, null, null, "f".repeat(64)));
        String result = recovery.retry(round(RoundStatus.CREDIT_PENDING, true));
        assertThat(result).startsWith("Blocked");
        verify(pam, never()).credit(any());
        verify(repository).updateRoundStatus(eq("r1"), eq(RoundStatus.TAMPERED), anyString());
    }

    @Test
    void signedCreditIsPaidWithItsRoundDerivedId() {
        debit();
        transactions.add(new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", win, "FAILED", null, null, null, 1, null, null,
                walletSigner.signCredit("r1", "pam-7", win, "EUR")));
        assertThat(recovery.retry(round(RoundStatus.CREDIT_PENDING, true))).isEqualTo("Credit paid");
        ArgumentCaptor<PamClient.WalletRequest> sent = ArgumentCaptor.forClass(PamClient.WalletRequest.class);
        verify(pam).credit(sent.capture());
        assertThat(sent.getValue().transactionId()).isEqualTo(walletSigner.creditId("r1"));
        assertThat(sent.getValue().playerId()).isEqualTo("pam-7");
    }

    @Test
    void rollbackRefusedForARoundThatAlreadyHasACredit() {
        debit();
        transactions.add(new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", BigDecimal.ZERO, "OK", null, null, null, 1, null, null, null));
        transactions.add(new GameRepository.Transaction(walletSigner.rollbackId("r1"), "r1", 7, "ROLLBACK", BigDecimal.ZERO, "FAILED", "debit-1", null, null, 1, null, null,
                walletSigner.signRollback("r1", "pam-7", "debit-1")));
        assertThat(recovery.retry(round(RoundStatus.ROLLBACK_PENDING, false))).startsWith("Blocked");
        verify(pam, never()).rollback(any());
    }

    // ---------------------------------------------------------------- review item 2.2

    private void pendingCredit(int attempts) {
        debit();
        transactions.add(new GameRepository.Transaction(walletSigner.creditId("r1"), "r1", 7, "CREDIT", win, "FAILED", null, null, null, attempts,
                null, null, walletSigner.signCredit("r1", "pam-7", win, "EUR")));
        when(pam.credit(any())).thenThrow(new com.plinko.pam.PamException("HTTP_503", "wallet down", true));
    }

    @Test
    void theLastFailedRetryRaisesTheAlarmOnce() {
        // max attempts is 10 in the fixtures: the 10th failure stops the automatic retries and says so
        pendingCredit(9);
        recovery.retry(round(RoundStatus.CREDIT_PENDING, true));
        verify(audit).log(eq("system"), eq("ROUND_RETRIES_STOPPED"), org.mockito.ArgumentMatchers.contains("win of 29.50 EUR failed 10 times"));
    }

    @Test
    void aRoundStoppedWithoutAFailureHereIsReportedOnce() {
        // N14.1: max-attempts lowered below a round's attempts: no retry fails, the background run still reports it, once
        when(repository.stoppedRounds(anyInt())).thenReturn(List.of("r1"));
        recovery.alarmStopped();
        recovery.alarmStopped();
        verify(audit, times(1)).log(eq("system"), eq("ROUND_RETRIES_STOPPED"), org.mockito.ArgumentMatchers.startsWith("r1:"));
        // and a further failed manual retry of it doesn't report it again
        pendingCredit(12);
        recovery.retry(round(RoundStatus.CREDIT_PENDING, true));
        verify(audit, times(1)).log(eq("system"), eq("ROUND_RETRIES_STOPPED"), anyString());
    }

    @Test
    void anUnreadableOddsVersionLeavesTheCreditWaitingWithItsOwnAlarm() {
        pendingCredit(1);
        GameRepository.Round round = round(RoundStatus.CREDIT_PENDING, true);
        assertThat(recovery.oddsUnreadable(round, "C5: odds version 4 is missing")).startsWith("Waiting");
        recovery.oddsUnreadable(round, "C5: odds version 4 is missing");
        verify(audit, times(1)).log(eq("system"), eq("ODDS_VERSION_UNREADABLE"), anyString());
        verify(repository, never()).updateRoundStatus(anyString(), eq(RoundStatus.TAMPERED), any());
    }

    @Test
    void anEarlierFailedRetryDoesNotRaiseTheAlarm() {
        pendingCredit(3);
        recovery.retry(round(RoundStatus.CREDIT_PENDING, true));
        verify(audit, never()).log(anyString(), eq("ROUND_RETRIES_STOPPED"), anyString());
    }

    @Test
    void stoppedRoundsAreNotRetriedInTheBackground() {
        pendingCredit(10);
        GameRepository.Round round = round(RoundStatus.CREDIT_PENDING, true);
        when(repository.dueForRetry(anyInt(), anyInt())).thenReturn(List.of(round));
        when(repository.staleRounds(any(), any())).thenReturn(List.of());
        recovery.retryPendingNow();
        verify(pam, never()).credit(any());
    }

    @Test
    void aPassAfterAWalletOutageTakesBatchAfterBatchButEachRoundOnce() {
        // 3.8: 450 rounds owed: one pass works through them in three batches; a round handed back twice is retried once
        List<GameRepository.Round> owed = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            owed.add(new GameRepository.Round("o" + i, 7, "s", 8, 2, new BigDecimal("1.00"), 1, new BigDecimal("1.00"), BigDecimal.ONE, "EUR",
                    RoundStatus.CREDIT_PENDING, "0", "1", null, null, null, null));
        }
        when(repository.staleRounds(any(), any())).thenReturn(List.of());
        when(repository.findRound(anyString())).thenReturn(Optional.empty());
        when(repository.dueForRetry(anyInt(), anyInt())).thenReturn(owed.subList(0, 200), owed.subList(200, 400), owed.subList(350, 450),
                List.of());
        recovery.retryPendingNow();
        verify(repository, times(3)).dueForRetry(anyInt(), anyInt());
        for (String id : List.of("o0", "o199", "o200", "o399", "o449")) {
            verify(repository, times(1)).findRound(id);
        }
        verify(repository, times(1)).findRound("o360");
    }

    @Test
    void aRefundTheWalletCallsSettledStopsAndGoesToReview() {
        debit();
        transactions.add(new GameRepository.Transaction(walletSigner.rollbackId("r1"), "r1", 7, "ROLLBACK", BigDecimal.ZERO, "FAILED", "debit-1",
                null, null, 1, null, null, walletSigner.signRollback("r1", "pam-7", "debit-1")));
        when(pam.rollback(any())).thenThrow(new com.plinko.pam.PamException("ROUND_SETTLED", "The round was already paid", false));
        assertThat(recovery.retry(round(RoundStatus.ROLLBACK_PENDING, false))).startsWith("Blocked: refund refused by the wallet");
        verify(repository).updateRoundStatus(eq("r1"), eq(RoundStatus.TAMPERED), anyString());
        verify(audit).log(eq("system"), eq("ROUND_TAMPERED"), org.mockito.ArgumentMatchers.contains("ROUND_SETTLED"));
    }

    @Test
    void aCreditSignedWithTheRetiredKeyRaisesItsOwnAlarmNotTheTamperingOne() {
        // R6: blocked (only a person can settle it now), but reported as what it is
        String reason = "credit blocked: " + WinCheck.RETIRED_KEY + " (LEGACY_KEY_UNTIL 2026-11-01T00:00:00Z): settle it with the casino";
        assertThat(recovery.block(round(RoundStatus.CREDIT_PENDING, true), reason)).startsWith("Blocked: credit blocked: C6: the credit is signed with a retired key");
        verify(repository).updateRoundStatus(eq("r1"), eq(RoundStatus.TAMPERED), anyString());
        verify(audit).log(eq("system"), eq("ROUND_SIGNED_WITH_RETIRED_KEY"), anyString());
        verify(audit, never()).log(eq("system"), eq("ROUND_TAMPERED"), anyString());
    }

    // ---------------------------------------------------------------- review 3.8

    @Test
    void aRefusedRetryWaitsLongerEachTimeUpTo15Minutes() {
        assertThat(RoundRecoveryService.backoff(1)).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(RoundRecoveryService.backoff(2)).isEqualTo(java.time.Duration.ofMinutes(1));
        assertThat(RoundRecoveryService.backoff(4)).isEqualTo(java.time.Duration.ofMinutes(4));
        assertThat(RoundRecoveryService.backoff(9)).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(RoundRecoveryService.backoff(60)).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    void aFailedRetryIsScheduledForLater() {
        pendingCredit(3);
        java.time.Instant before = java.time.Instant.now();
        recovery.retry(round(RoundStatus.CREDIT_PENDING, true));
        ArgumentCaptor<java.time.Instant> at = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(repository).scheduleRetry(eq(walletSigner.creditId("r1")), at.capture());
        // the 4th attempt failed: the next waits 4 minutes
        assertThat(at.getValue()).isBetween(before.plusSeconds(239), before.plusSeconds(242));
    }

    @Test
    void dueRoundsAreRetriedInTheBackground() {
        pendingCredit(2);
        org.mockito.Mockito.doReturn(new PamClient.PamResult(new BigDecimal("100"), "ref")).when(pam).credit(any());
        GameRepository.Round round = round(RoundStatus.CREDIT_PENDING, true);
        when(repository.dueForRetry(anyInt(), anyInt())).thenReturn(List.of(round));
        when(repository.staleRounds(any(), any())).thenReturn(List.of());
        recovery.retryPendingNow();
        verify(pam).credit(any());
        verify(repository).dueForRetry(10, RoundRecoveryService.BATCH);
    }
}
