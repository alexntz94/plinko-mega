package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.plinko.pam.PamClient;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

/** Review 7.1: the wallet's record of the game's money against the game's own. */
class PamReconciliationTest {

    private static final Instant FROM = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant TO = FROM.plus(Duration.ofDays(1));
    private static final Instant AT = FROM.plus(Duration.ofHours(3));

    private final PamClient pam = mock(PamClient.class);
    private final GameRepository game = mock(GameRepository.class);
    private final PamReconciliation check = new PamReconciliation(pam, game);

    private static GameRepository.Transaction ours(String id, String type, String amount, String status) {
        return new GameRepository.Transaction(id, "round-" + id, 7, type, new BigDecimal(amount), status, null, null, null, 1, AT, AT, "s");
    }

    private static PamClient.PamTransaction theirs(String id, String type, String amount) {
        return new PamClient.PamTransaction(id, type, "round-" + id, "pam-7", new BigDecimal(amount), AT);
    }

    @Test
    void everyKindOfDifferenceIsFoundAcrossPages() {
        when(pam.transactions(eq(FROM), eq(TO), isNull())).thenReturn(new PamClient.TransactionPage(List.of(
                theirs("a", "DEBIT", "1.00"), theirs("b", "CREDIT", "2.00"), theirs("lost", "DEBIT", "5.00")), "lost"));
        when(pam.transactions(FROM, TO, "lost")).thenReturn(new PamClient.TransactionPage(List.of(
                theirs("c", "CREDIT", "3.00"), theirs("m", "CREDIT", "4.00"), theirs("p", "CREDIT", "9.00")), null));
        when(game.transactionsBetween(any(), any(), anyInt())).thenReturn(List.of(
                ours("a", "DEBIT", "1.00", "OK"),            // matches
                ours("b", "CREDIT", "2.50", "OK"),           // amount differs
                ours("c", "CREDIT", "3.00", "PENDING"),      // done at the wallet, still pending here
                ours("m", "MANUAL_PAY", "4.00", "OK"),       // a settlement: the wallet calls it a credit
                ours("p", "CREDIT", "9.00", "OK"),           // matches
                ours("x", "CREDIT", "7.00", "OK"),           // the wallet never had it
                ours("refused", "DEBIT", "1.00", "FAILED"))); // refused: nothing at the wallet, as expected
        PamReconciliation.Report report = check.compare(FROM, TO);
        assertThat(report.counts()).containsEntry("walletOnly", 1).containsEntry("gameOnly", 1).containsEntry("different", 1)
                .containsEntry("notConfirmed", 1);
        assertThat(report.matched()).isEqualTo(3);
        assertThat(report.walletCount()).isEqualTo(6);
        assertThat(report.clean()).isFalse();
        assertThat(report.differences()).extracting(PamReconciliation.Difference::transactionId).containsExactlyInAnyOrder("lost", "x", "b", "c");
    }

    @Test
    void twoSidesThatAgreeAreClean() {
        when(pam.transactions(eq(FROM), eq(TO), isNull())).thenReturn(new PamClient.TransactionPage(List.of(theirs("a", "DEBIT", "1.00"),
                theirs("r", "ROLLBACK", "0.00")), null));
        // a refund's amount is the wallet's (nothing when the bet was already refunded)
        when(game.transactionsBetween(any(), any(), anyInt())).thenReturn(List.of(ours("a", "DEBIT", "1.00", "OK"), ours("r", "ROLLBACK", "1.00", "OK")));
        assertThat(check.compare(FROM, TO).clean()).isTrue();
    }

    @Test
    void theRangeIsLimitedAndAWalletRepeatingAPageIsCaught() {
        assertThatThrownBy(() -> check.compare(FROM, FROM.plus(Duration.ofDays(8)))).hasMessageContaining("7 days");
        assertThatThrownBy(() -> check.compare(TO, FROM)).isInstanceOf(IllegalArgumentException.class);
        when(pam.transactions(eq(FROM), eq(TO), isNull())).thenReturn(new PamClient.TransactionPage(List.of(), "same"));
        when(pam.transactions(FROM, TO, "same")).thenReturn(new PamClient.TransactionPage(List.of(), "same"));
        assertThatThrownBy(() -> check.compare(FROM, TO)).hasMessageContaining("same page");
    }
}
