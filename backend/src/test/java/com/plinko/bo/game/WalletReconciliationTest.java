package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.plinko.MemoryJobState;
import com.plinko.NoTransactions;
import com.plinko.repo.FinanceRepository;
import org.junit.jupiter.api.Test;

/** N10: the wallet check runs on one instance, keeps its progress and counts in the database, and never skips a late round. */
class WalletReconciliationTest {

    private final FinanceRepository finance = mock(FinanceRepository.class);
    private final MemoryJobState state = new MemoryJobState();
    private final LocalDate day = LocalDate.of(2026, 10, 18);

    @Test
    void aNewInstanceContinuesWhereTheLastOneStopped() {
        Instant finished = Instant.now().minusSeconds(600);
        when(finance.reconcileAfter(any(), eq(""), any(), anyInt())).thenReturn(List.of(
                new FinanceRepository.Reconciled("a", day, finished, false), new FinanceRepository.Reconciled("b", day, finished, true)));
        new WalletReconciliation(finance, state, new NoTransactions()).run();
        verify(finance).addWalletCheck(day, 2, 1);
        assertThat(state.values.get("wallet_check.checkpoint")).isEqualTo(finished.toEpochMilli() + "|b");
        assertThat(state.values.get("wallet_check.caught_up")).isEqualTo("true");

        // the next instance starts from round b, not from a month ago
        WalletReconciliation next = new WalletReconciliation(finance, state, new NoTransactions());
        next.run();
        verify(finance).reconcileAfter(eq(Instant.ofEpochMilli(finished.toEpochMilli())), eq("b"), any(), anyInt());
        assertThat(next.caughtUp()).isTrue();
        when(finance.walletCheckDiffering(day, day)).thenReturn(1L);
        assertThat(next.differing(day, day)).isEqualTo(1);
    }

    @Test
    void roundsThatFinishedJustNowAreLeftForTheNextRun() {
        new WalletReconciliation(finance, state, new NoTransactions()).run();
        org.mockito.ArgumentCaptor<Instant> until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(finance).reconcileAfter(any(), anyString(), until.capture(), anyInt());
        assertThat(until.getValue()).isBefore(Instant.now().minus(WalletReconciliation.LAG).plusSeconds(1));
    }

    @Test
    void onlyOneInstanceRunsIt() {
        state.free = false;
        new WalletReconciliation(finance, state, new NoTransactions()).run();
        verify(finance, never()).reconcileAfter(any(), anyString(), any(), anyInt());
        verify(finance, never()).addWalletCheck(any(), anyLong(), anyLong());
    }
}
