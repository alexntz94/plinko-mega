package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.repo.GameRepository;
import com.plinko.repo.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Review 7.3: settling a blocked round pays once, within the limits, and only what the two admins approved. */
class RoundSettlementServiceTest {

    private static final String ROUND = "11111111-2222-3333-4444-555555555555";
    private static final GameRepository.Player PLAYER = new GameRepository.Player(7, "pam-7", "alice", "EUR", null, false, Instant.now(), Instant.now());

    private final GameRepository game = mock(GameRepository.class);
    private final SettlementRepository repo = mock(SettlementRepository.class);
    private final PamClient pam = mock(PamClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final WinCheck winCheck = mock(WinCheck.class);
    private final Integrity integrity = new Integrity(TestFixtures.properties());
    private final List<GameRepository.Transaction> transactions = new ArrayList<>();
    private final AtomicReference<SettlementRepository.Settlement> stored = new AtomicReference<>();
    private RoundSettlementService service;
    private String status = RoundStatus.TAMPERED;
    private Instant blockedAt = Instant.now().minus(Duration.ofHours(2));
    private String win = "900.00";

    /** The stored settings: none stored yet unless a test says so (then the environment's values apply). */
    private final com.plinko.bo.settings.SettingsService settings = mock(com.plinko.bo.settings.SettingsService.class);

    private RoundSettlementService service(String maxAmounts) {
        return new RoundSettlementService(game, repo, pam, integrity, audit, winCheck, settings, new BigDecimal("500"), maxAmounts,
                new BigDecimal("100"), "", 90, 24, 3);
    }

    /** The limits as stored (approved) settings, per currency. */
    private void storedLimits(String max, String other) {
        when(settings.settlementLimit(com.plinko.bo.settings.SettingsService.SETTLEMENT_MAX)).thenReturn(Optional.ofNullable(max));
        when(settings.settlementLimit(com.plinko.bo.settings.SettingsService.SETTLEMENT_OTHER)).thenReturn(Optional.ofNullable(other));
    }

    @BeforeEach
    void setUp() {
        service = service("");
        storedLimits("EUR=500", "EUR=100");
        when(game.findRound(ROUND)).thenAnswer(i -> Optional.of(new GameRepository.Round(ROUND, 7, "s", 8, 0, new BigDecimal("2.00"), 1,
                new BigDecimal("2.00"), new BigDecimal(win), "EUR", status, "0", "450", null, "f".repeat(64), blockedAt, blockedAt,
                null, null, new BigDecimal("800.00"))));
        when(game.findPlayer(7)).thenReturn(Optional.of(PLAYER));
        when(game.findSession("s")).thenReturn(Optional.empty());
        transactions.add(new GameRepository.Transaction("debit-1", ROUND, 7, "DEBIT", new BigDecimal("2.00"), "OK", null, "r", null, 1, null, null, null));
        when(game.transactionsOfRound(ROUND)).thenAnswer(i -> List.copyOf(transactions));
        doAnswer(i -> transactions.add(i.getArgument(0))).when(game).insertTransaction(any());
        doAnswer(i -> update(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), false))
                .when(game).updateTransaction(anyString(), anyString(), any(), any());
        doAnswer(i -> update(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4)))
                .when(game).updateTransaction(anyString(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        when(repo.find(ROUND)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        }).when(repo).insert(any());
        doAnswer(i -> {
            SettlementRepository.Settlement s = stored.get();
            stored.set(new SettlementRepository.Settlement(s.roundId(), s.action(), s.amount(), s.txId(), s.changeId(), s.proposedBy(),
                    s.approvedBy(), s.reason(), "DONE", s.signature(), s.createdAt(), s.updatedAt()));
            return null;
        }).when(repo).markDone(ROUND);
        // R11: the stopped mark, and the queries that leave stopped settlements out (as the SQL does)
        when(repo.markStopped(ROUND)).thenAnswer(i -> {
            SettlementRepository.Settlement s = stored.get();
            if (s == null || !"PENDING".equals(s.status()) || s.stoppedAt() != null) {
                return false;
            }
            stored.set(stopped(s, Instant.now()));
            return true;
        });
        doAnswer(i -> {
            stored.set(stopped(stored.get(), null));
            return null;
        }).when(repo).resume(ROUND);
        when(repo.pending(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> java.util.stream.Stream.ofNullable(stored.get())
                .filter(s -> "PENDING".equals(s.status()) && s.stoppedAt() == null).toList());
        when(repo.stopped(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> java.util.stream.Stream.ofNullable(stored.get())
                .filter(s -> "PENDING".equals(s.status()) && s.stoppedAt() != null).toList());
        when(pam.credit(any())).thenReturn(new PamClient.PamResult(new BigDecimal("150.00"), "ref"));
        when(pam.rollback(any())).thenReturn(new PamClient.PamResult(new BigDecimal("52.00"), "ref"));
    }

    private static SettlementRepository.Settlement stopped(SettlementRepository.Settlement s, Instant at) {
        return new SettlementRepository.Settlement(s.roundId(), s.action(), s.amount(), s.txId(), s.changeId(), s.proposedBy(), s.approvedBy(),
                s.reason(), s.status(), s.signature(), s.createdAt(), s.updatedAt(), at);
    }

    /** The wallet's answer stored on a transaction, as the database's guard keeps it (a refusal only on a FAILED row). */
    private Void update(String id, String status, String reference, String error, boolean refused) {
        transactions.replaceAll(tx -> tx.id().equals(id) ? new GameRepository.Transaction(tx.id(), tx.roundId(), tx.playerId(), tx.type(),
                tx.amount(), status, tx.originalTxId(), reference, error, tx.attempts() + 1, null, null, tx.signature(),
                refused && "FAILED".equals(status), null) : tx);
        return null;
    }

    private static RoundSettlementService.Proposal pay(String amount) {
        return new RoundSettlementService.Proposal(ROUND, "PAY", new BigDecimal(amount), "Player ticket 42: genuine round blocked by a bug");
    }

    @Test
    void theLimitsAreChecked() {
        assertThat(service.problem(pay("100.00"))).isEmpty();
        assertThat(service.problem(pay("600.00"))).get().asString().contains("settlement limit");
        assertThat(service.problem(pay("0"))).get().asString().contains("amount");
        assertThat(service.problem(pay("1.005"))).get().asString().contains("2 decimals");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("5.00"), "the bet only, nothing else")))
                .get().asString().contains("exactly the round's total bet");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("2.00"), "the bet only, nothing else"))).isEmpty();
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "CLOSE", BigDecimal.ZERO, "confirmed edited in the database"))).isEmpty();
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "GIVE", BigDecimal.ONE, "free balance change please"))).isPresent();
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "PAY", BigDecimal.ONE, "short"))).isPresent();
        blockedAt = Instant.now().minus(Duration.ofDays(91));
        assertThat(service.problem(pay("10.00"))).get().asString().contains("90 days");
        blockedAt = Instant.now();
        status = RoundStatus.COMPLETED;
        assertThat(service.problem(pay("10.00"))).get().asString().contains("Only a blocked");
    }

    @Test
    void anApprovedPaymentIsPaidOnceWithItsOwnDerivedId() {
        service.apply(pay("100.00"), 9, "alice", "bob");
        GameRepository.Transaction tx = transactions.get(1);
        assertThat(tx.type()).isEqualTo("MANUAL_PAY");
        assertThat(tx.id()).isEqualTo(integrity.derivedId("manual-pay", ROUND)).isNotEqualTo(integrity.derivedId("credit", ROUND));
        assertThat(service.send(ROUND)).contains("sent");
        assertThat(service.send(ROUND)).isEqualTo("Nothing to send");
        verify(pam, times(1)).credit(any());
        assertThat(stored.get().status()).isEqualTo("DONE");
        // settled: a second settlement of the same round is refused
        assertThat(service.problem(pay("1.00"))).get().asString().contains("already been settled");
        assertThatThrownBy(() -> service.apply(pay("1.00"), 10, "alice", "bob")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefundSendsARollbackOfTheBet() {
        service.apply(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("2.00"), "refund agreed with the casino"), 9, "alice", "bob");
        service.send(ROUND);
        verify(pam).rollback(new PamClient.RollbackRequest(PLAYER.externalId(), "", integrity.derivedId("manual-refund", ROUND),
                "debit-1", ROUND));
    }

    @Test
    void aSettlementsChangeRequestTargetFitsItsColumn() {
        // change_request.target is VARCHAR(32)
        assertThat(com.plinko.bo.changes.ChangeRequestService.settlementTarget(ROUND)).hasSize(32).doesNotContain("-");
    }

    @Test
    void closingMovesNoMoney() {
        service.apply(new RoundSettlementService.Proposal(ROUND, "CLOSE", BigDecimal.ZERO, "confirmed edited in the database"), 9, "alice", "bob");
        assertThat(stored.get().status()).isEqualTo("DONE");
        assertThat(transactions).hasSize(1);
        verify(pam, never()).credit(any());
    }

    @Test
    void aSettlementChangedAfterApprovalIsNeverSent() {
        service.apply(pay("100.00"), 9, "alice", "bob");
        SettlementRepository.Settlement s = stored.get();
        // someone with database access raises the amount on both rows
        stored.set(new SettlementRepository.Settlement(s.roundId(), s.action(), new BigDecimal("499.00"), s.txId(), s.changeId(), s.proposedBy(),
                s.approvedBy(), s.reason(), s.status(), s.signature(), null, null));
        GameRepository.Transaction tx = transactions.get(1);
        transactions.set(1, new GameRepository.Transaction(tx.id(), tx.roundId(), tx.playerId(), tx.type(), new BigDecimal("499.00"), tx.status(),
                null, null, null, 0, null, null, tx.signature()));
        assertThat(service.send(ROUND)).contains("changed after approval");
        verify(pam, never()).credit(any());
    }

    // ---------------------------------------------------------------- N2: never pay what the wallet may already have paid

    private void credit(String status, String error) {
        credit(status, error, false);
    }

    /** The round's credit as stored: its status, error text and whether the wallet definitely refused it (R4). */
    private void credit(String status, String error, boolean refused) {
        transactions.add(new GameRepository.Transaction(integrity.derivedId("credit", ROUND), ROUND, 7, "CREDIT", new BigDecimal(win), status,
                null, null, error, 1, null, null, "sig", refused, null));
    }

    @Test
    void noPaymentWhileTheRoundsCreditMayHaveBeenApplied() {
        credit("FAILED", new com.plinko.pam.PamException("TIMEOUT", "no answer", true).stored());
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("2.00"), "refund agreed with the casino")))
                .get().asString().contains("may already have applied");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "CLOSE", BigDecimal.ZERO, "confirmed edited in the database"))).isEmpty();
    }

    @Test
    void noPaymentWhileTheRoundsCreditIsPendingOrFailedBeforeOutcomesWereRecorded() {
        credit("PENDING", null);
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied");
        transactions.remove(1);
        credit("FAILED", "TIMEOUT: written before refusals were marked");
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied");
    }

    @Test
    void aReusedIdAnswerIsNotProofThatNothingWasPaid() {
        // the wallet holds a transaction with this id already (409 TRANSACTION_MISMATCH): it may have been applied
        credit("FAILED", new com.plinko.pam.PamException("TRANSACTION_MISMATCH", "other contents", false).stored());
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied");
    }

    @Test
    void aCreditTheGameNeverSentDoesNotStopASettlement() {
        // blocked before the wallet call: the round can still be paid or refunded
        credit("FAILED", com.plinko.pam.PamException.notSent("blocked before sending: C5"), true);
        assertThat(service.problem(pay("100.00"))).isEmpty();
    }

    @Test
    void aPaymentIsAllowedAfterTheWalletDefinitelyRefusedTheCredit() {
        credit("FAILED", new com.plinko.pam.PamException("INVALID_TOKEN", "refused", false).stored(), true);
        assertThat(service.problem(pay("100.00"))).isEmpty();
    }

    @Test
    void theErrorTextAloneNeverProvesARefusal() {
        // R4: written by an older release, or left from an earlier answer: only the refused flag counts
        credit("FAILED", "[refused] INVALID_TOKEN: refused", false);
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied");
    }

    @Test
    void aCreditSentAgainWithNoAnswerRecordedMayHaveBeenApplied() {
        // R4: the resend's "sending" mark cleared the earlier refusal; the process died before the answer was stored
        transactions.add(new GameRepository.Transaction(integrity.derivedId("credit", ROUND), ROUND, 7, "CREDIT", new BigDecimal(win), "FAILED",
                null, null, "INVALID_TOKEN: refused", 1, null, null, "sig", false, Instant.now()));
        assertThat(service.problem(pay("100.00"))).get().asString().contains("may already have applied").contains("no answer recorded");
    }

    // ---------------------------------------------------------------- N3: the amount is the round's own

    @Test
    void aPaymentIsTheRoundsOwnRecomputedWin() {
        win = "450.00";
        assertThat(service.problem(pay("450.00"))).isEmpty();
        assertThat(service.problem(pay("100.00"))).isEmpty();
        assertThat(service.problem(pay("100.01"))).get().asString().contains("recomputed").contains("450.00").contains("100");
        assertThat(service.payNote(ROUND, new BigDecimal("450.00"))).contains("the round's own win");
        assertThat(service.payNote(ROUND, new BigDecimal("90.00"))).contains("NOT the round's win, which is 450.00");
    }

    @Test
    void aRoundWhoseOutcomeCantBeTrustedIsPaidAtMostTheLowerLimit() {
        win = "450.00";
        when(winCheck.outcomeProblem(any())).thenReturn("C1: signature");
        assertThat(service.problem(pay("450.00"))).get().asString().contains("can't be trusted");
        assertThat(service.problem(pay("100.00"))).isEmpty();
        assertThat(service.payNote(ROUND, new BigDecimal("100.00"))).contains("can't be trusted");
    }

    @Test
    void theLimitIsPerCurrency() {
        win = "450.00";
        storedLimits("EUR=300,SEK=5000", "EUR=100,SEK=1000");
        assertThat(service.problem(pay("450.00"))).get().asString().contains("settlement limit of 300 EUR");
        storedLimits("EUR=500,SEK=5000", "EUR=100,SEK=1000");
        assertThat(service.problem(pay("450.00"))).isEmpty();
        assertThatThrownBy(() -> service("EUR:300")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noMoneyIsSettledInACurrencyWithoutAStoredLimit() {
        // R9 #1: no fallback to the environment's one amount for every currency; closing stays possible
        win = "450.00";
        storedLimits("GBP=400", "GBP=40");
        assertThat(service("EUR=1000").maxAmount("EUR")).as("the environment's values are not a limit").isEmpty();
        assertThat(service.problem(pay("450.00"))).get().asString().contains("No settlement limit is stored for EUR");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("2.00"), "refund agreed with the casino")))
                .get().asString().contains("No settlement limit is stored for EUR");
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "CLOSE", BigDecimal.ZERO, "confirmed edited in the database"))).isEmpty();
        // a highest limit but none for other amounts: only the round's own recomputed win can be paid
        storedLimits("EUR=500", "GBP=40");
        assertThat(service.problem(pay("450.00"))).isEmpty();
        assertThat(service.problem(pay("10.00"))).get().asString().contains("No limit for amounts other than the round's own win is stored for EUR");
        // nothing stored at all yet
        storedLimits(null, null);
        assertThat(service.problem(pay("450.00"))).get().asString().contains("No settlement limit is stored for EUR");
    }

    @Test
    void anUnsignedDebitIsAcceptedOnlyForARoundPlayedBeforeDebitsWereSigned() {
        // R9 #3: the round was played 2 hours ago (blockedAt); this install signs every debit from the stored time
        WalletSigner walletSigner = new WalletSigner(integrity);
        com.plinko.repo.JobState state = mock(com.plinko.repo.JobState.class);
        walletSigner.setJobState(state);
        service.setWalletSigner(walletSigner);
        when(state.putIfAbsent(org.mockito.ArgumentMatchers.eq("debits.signed_from"), anyString())).thenReturn(Instant.now().minus(Duration.ofDays(1)).toString());
        assertThat(service.problem(pay("100.00"))).get().asString().contains("has no signature");
        WalletSigner later = new WalletSigner(integrity);
        later.setJobState(state);
        service.setWalletSigner(later);
        when(state.putIfAbsent(org.mockito.ArgumentMatchers.eq("debits.signed_from"), anyString())).thenReturn(Instant.now().toString());
        assertThat(service.problem(pay("100.00"))).isEmpty();
    }

    @Test
    void aRefundIsExactlyTheRoundsTotalBet() {
        transactions.set(0, new GameRepository.Transaction("debit-1", ROUND, 7, "DEBIT", new BigDecimal("3.00"), "OK", null, "r", null, 1, null, null, null));
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal("3.00"), "refund agreed with the casino")))
                .get().asString().contains("not the round's total bet");
    }

    @Test
    void theProposerNeverApprovesTheirOwnSettlement() {
        assertThatThrownBy(() -> service.apply(pay("100.00"), 9, "alice", "alice")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different admin");
        assertThat(stored.get()).isNull();
        verify(pam, never()).credit(any());
    }

    // ---------------------------------------------------------------- N13: a refused settlement is not retried forever

    @Test
    void aSettlementTheWalletRefusesStopsWithAnAlarm() {
        when(pam.credit(any())).thenThrow(new com.plinko.pam.PamException("TRANSACTION_MISMATCH", "refused", false));
        service.apply(pay("100.00"), 9, "alice", "bob");
        assertThat(service.send(ROUND)).contains("refused").contains("retries stopped");
        verify(audit).log(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("SETTLEMENT_RETRIES_STOPPED"), anyString());
        when(game.whileLocked(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        service.retryPending();
        verify(pam, times(1)).credit(any());
        assertThat(service.stoppedSettlements()).containsExactly(ROUND);
        // a person can still send it again; refused again, it stops again (and says so again)
        assertThat(service.sendAgain(ROUND)).contains("refused");
        verify(pam, times(2)).credit(any());
        verify(audit, times(2)).log(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("SETTLEMENT_RETRIES_STOPPED"), anyString());
        service.retryPending();
        verify(pam, times(2)).credit(any());
    }

    @Test
    void aSettlementSentAgainByAPersonIsRetriedInTheBackgroundUntilItStopsAgain() {
        // R11: "Send to the wallet again" lifts the stop; a send without an answer is then retried every few minutes
        when(pam.credit(any())).thenThrow(new com.plinko.pam.PamException("TRANSACTION_MISMATCH", "refused", false))
                .thenThrow(new com.plinko.pam.PamException("TIMEOUT", "no answer", true))
                .thenReturn(new PamClient.PamResult(new BigDecimal("150.00"), "ref"));
        when(game.whileLocked(anyString(), org.mockito.ArgumentMatchers.anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        service.apply(pay("100.00"), 9, "alice", "bob");
        service.send(ROUND);
        assertThat(service.stoppedSettlements()).containsExactly(ROUND);
        assertThat(service.sendAgain(ROUND)).contains("did not confirm it yet");
        assertThat(service.stoppedSettlements()).isEmpty();
        service.retryPending();
        assertThat(stored.get().status()).isEqualTo("DONE");
    }

    // ---------------------------------------------------------------- what can't be settled, and why

    private static RoundSettlementService.Proposal refund(String amount) {
        return new RoundSettlementService.Proposal(ROUND, "REFUND", new BigDecimal(amount), "refund agreed with the casino");
    }

    private static RoundSettlementService.Proposal close(String amount) {
        return new RoundSettlementService.Proposal(ROUND, "CLOSE", new BigDecimal(amount), "confirmed edited in the database");
    }

    private void walletTransaction(String type, String status) {
        transactions.add(new GameRepository.Transaction(type.toLowerCase() + "-x", ROUND, 7, type, BigDecimal.ONE, status, null, "r", null, 1, null,
                null, "sig"));
    }

    @Test
    void anUnknownRoundCantBeSettled() {
        assertThat(service.problem(new RoundSettlementService.Proposal("not-a-round", "PAY", BigDecimal.ONE, "reason long enough"))).contains("Unknown round");
        assertThat(service.problem(new RoundSettlementService.Proposal(null, "PAY", BigDecimal.ONE, "reason long enough"))).contains("Unknown round");
        String other = "99999999-2222-3333-4444-555555555555";
        when(game.findRound(other)).thenReturn(Optional.empty());
        assertThat(service.problem(new RoundSettlementService.Proposal(other, "PAY", BigDecimal.ONE, "reason long enough"))).contains("Unknown round");
    }

    @Test
    void aRoundWhoseBetTheWalletNeverTookCanOnlyBeClosed() {
        transactions.clear();
        assertThat(service.problem(pay("10.00"))).get().asString().contains("never took this round's bet");
        assertThat(service.problem(refund("2.00"))).get().asString().contains("never took this round's bet");
        assertThat(service.problem(close("0"))).isEmpty();
        assertThat(service.problem(close("1.00"))).get().asString().contains("Closing pays nothing");
    }

    @Test
    void aRoundTheWalletAlreadyPaidCantBePaidOrRefundedAgain() {
        walletTransaction("CREDIT", "OK");
        assertThat(service.problem(pay("10.00"))).get().asString().contains("already paid this round");
        assertThat(service.problem(refund("2.00"))).get().asString().contains("can't also be refunded");
    }

    @Test
    void aRoundTheWalletAlreadyRefundedCanOnlyBeClosed() {
        walletTransaction("ROLLBACK", "OK");
        assertThat(service.problem(pay("10.00"))).get().asString().contains("already refunded");
        assertThat(service.problem(refund("2.00"))).get().asString().contains("already refunded");
        assertThat(service.problem(close("0"))).isEmpty();
    }

    @Test
    void aPaymentAboveWhatTheRoundCouldEverWinIsRefused() {
        storedLimits("EUR=5000", "EUR=1000");
        assertThat(service.problem(pay("801.00"))).get().asString().contains("could ever win (800.00)");
    }

    @Test
    void theStateAProposalWasMadeAgainstNamesTheRoundItsSettlementAndItsTransactions() {
        String before = service.state(ROUND);
        assertThat(before).isEqualTo("TAMPERED|debit-1:OK|");
        service.apply(pay("100.00"), 9, "alice", "bob");
        assertThat(service.state(ROUND)).isNotEqualTo(before).contains("settled|");
        assertThat(service.find(ROUND)).isPresent();
    }

    @Test
    void theMoneyIsSentOnlyOnceTheApprovalCommits() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        List<org.springframework.transaction.support.TransactionSynchronization> registered;
        try {
            service.apply(pay("100.00"), 9, "alice", "bob");
            verify(pam, never()).credit(any());
            registered = List.copyOf(org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        registered.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        verify(pam, times(1)).credit(any());
        assertThat(stored.get().status()).isEqualTo("DONE");
    }

    @Test
    void blockedRoundsWaitingTooLongAreCountedForTheAlert() {
        SettlementRepository.OpenRound old = new SettlementRepository.OpenRound(ROUND, "pam-7", BigDecimal.ONE, "EUR",
                Instant.now().minus(Duration.ofDays(3)), null);
        SettlementRepository.OpenRound fresh = new SettlementRepository.OpenRound("r2", "pam-7", BigDecimal.ONE, "EUR", Instant.now(), null);
        when(repo.open(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(old, fresh));
        service.alertOverdue();
        assertThat(service.open(10)).containsExactly(old, fresh);
        when(repo.open(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(fresh));
        service.alertOverdue();
        verify(repo, times(3)).open(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aStoppedSettlementIsListedForTheAdminAlarm() {
        when(pam.credit(any())).thenThrow(new com.plinko.pam.PamException("INVALID_TOKEN", "refused", false));
        service.apply(pay("100.00"), 9, "alice", "bob");
        assertThat(service.stoppedSettlements()).isEmpty();      // not sent yet: just waiting
        service.send(ROUND);
        assertThat(service.stoppedSettlements()).containsExactly(ROUND);
    }

    // ---------------------------------------------------------------- N3 #3: the limits are stored settings

    @Test
    void theStoredLimitsApplyInsteadOfTheEnvironmentsOnes() {
        RoundSettlementService limited = service("EUR=300");
        storedLimits("EUR=50,GBP=40", "EUR=20");
        assertThat(limited.maxAmount("EUR").orElseThrow()).isEqualByComparingTo("50");
        assertThat(limited.maxAmount("SEK")).as("a currency not listed has no limit (R9 #1)").isEmpty();
        assertThat(limited.otherAmount("EUR").orElseThrow()).isEqualByComparingTo("20");
        assertThat(limited.problem(pay("100.00"))).get().asString().contains("settlement limit of 50 EUR");
        assertThat(limited.problem(pay("30.00"))).get().asString().contains("limited to 20 EUR");
        assertThat(limited.problem(pay("20.00"))).isEmpty();
        when(settings.currency()).thenReturn("EUR");
        assertThat(limited.currentLimits()).isEqualTo(new com.plinko.bo.settings.SettingsService.SettlementLimits("EUR=50,GBP=40", "EUR=20"));
    }

    @Test
    void noPaymentIsAcceptedWhileTheSettingsFailTheirCheck() {
        when(settings.settlementLimit(anyString())).thenThrow(new com.plinko.bo.settings.SettingsService.TamperedException());
        assertThat(service.problem(pay("10.00"))).get().asString().contains("changed outside the admin panel");
        // closing moves no money and stays possible
        assertThat(service.problem(new RoundSettlementService.Proposal(ROUND, "CLOSE", BigDecimal.ZERO, "confirmed edited in the database"))).isEmpty();
    }

    @Test
    void theEnvironmentsLimitsAreStoredOnceAsTheFirstValue() {
        when(settings.currency()).thenReturn("USD");
        when(settings.seedSettlementLimits(anyString(), anyString())).thenReturn(true, false);
        RoundSettlementService fromEnvironment = service("EUR=300,SEK=5000");
        fromEnvironment.storeInitialLimits();
        // the platform currency gets the default when the environment doesn't list it
        verify(settings).seedSettlementLimits("EUR=300,SEK=5000,USD=500", "USD=100");
        verify(audit).log(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("SETTLEMENT_LIMITS_INITIALISED"), anyString());
        fromEnvironment.storeInitialLimits();
        verify(audit, times(1)).log(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("SETTLEMENT_LIMITS_INITIALISED"), anyString());
        // limits that can't be stored (other above the highest) are left to the environment, with an error in the log
        new RoundSettlementService(game, repo, pam, integrity, audit, winCheck, settings, new BigDecimal("50"), "", new BigDecimal("100"), "",
                90, 24, 3).storeInitialLimits();
        verify(settings, times(2)).seedSettlementLimits(anyString(), anyString());
    }

    @Test
    void aSettlementThatNeverGetsAnAnswerStopsAfterTheLastAttempt() {
        when(pam.credit(any())).thenThrow(new com.plinko.pam.PamException("TIMEOUT", "no answer", true));
        service.apply(pay("100.00"), 9, "alice", "bob");
        assertThat(service.send(ROUND)).contains("retried");
        assertThat(service.send(ROUND)).contains("retried");
        assertThat(service.send(ROUND)).contains("retries stopped");
        verify(audit, times(1)).log(org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.eq("SETTLEMENT_RETRIES_STOPPED"), anyString());
    }
}
