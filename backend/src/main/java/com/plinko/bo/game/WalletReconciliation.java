package com.plinko.bo.game;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.plinko.repo.FinanceRepository;
import com.plinko.repo.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks every completed round against its wallet transactions once, in the background (review 3.3): the confirmed
 * debit must equal the round's bet and the confirmed credit its win. A completed round and its confirmed transactions
 * can no longer change (database triggers), so each is checked once, in the order rounds finish; the Finance page
 * shows the counts per day. Covers the last {@link #WINDOW} days from the first run on.
 * <p>
 * N10: one instance at a time runs it, and its progress and counts are kept in the database (with each batch, in one
 * transaction), so a new instance continues instead of re-checking a month of rounds, and every instance shows the same
 * counts. Rounds are taken only once they finished at least {@link #LAG} ago: a round whose last write committed a
 * little after a later one was read is not skipped.
 */
@Service
public class WalletReconciliation {

    private static final Logger log = LoggerFactory.getLogger(WalletReconciliation.class);
    static final Duration WINDOW = Duration.ofDays(31);
    static final Duration LAG = Duration.ofMinutes(2);
    private static final int BATCH = 2000;
    private static final int MAX_BATCHES_PER_RUN = 250;
    private static final String CHECKPOINT = "wallet_check.checkpoint";
    private static final String FROM = "wallet_check.from";
    private static final String CAUGHT_UP = "wallet_check.caught_up";

    private final FinanceRepository finance;
    private final JobState state;
    private final TransactionTemplate transaction;

    public WalletReconciliation(FinanceRepository finance, JobState state, PlatformTransactionManager transactions) {
        this.finance = finance;
        this.state = state;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 15_000)
    public void run() {
        try {
            state.runAlone("wallet_check", this::check);
        } catch (RuntimeException e) {
            log.warn("Wallet reconciliation paused: {}", e.toString());
        }
    }

    private void check() {
        String[] checkpoint = state.get(CHECKPOINT).orElseGet(() -> {
            Instant from = Instant.now().minus(WINDOW);
            state.put(FROM, String.valueOf(from.toEpochMilli()));
            return from.toEpochMilli() + "|";
        }).split("\\|", 2);
        Instant finishedAfter = Instant.ofEpochMilli(Long.parseLong(checkpoint[0]));
        String afterId = checkpoint[1];
        Instant until = Instant.now().minus(LAG);
        boolean caughtUp = false;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<FinanceRepository.Reconciled> rounds = finance.reconcileAfter(finishedAfter, afterId, until, BATCH);
            if (rounds.isEmpty()) {
                caughtUp = true;
                break;
            }
            Map<LocalDate, long[]> counts = new TreeMap<>();
            for (FinanceRepository.Reconciled round : rounds) {
                long[] day = counts.computeIfAbsent(round.day(), d -> new long[2]);
                day[0]++;
                if (round.differs()) {
                    day[1]++;
                    log.error("WALLET MISMATCH: round {} ({}) differs from its confirmed wallet debit or credit", round.id(), round.day());
                }
            }
            FinanceRepository.Reconciled last = rounds.get(rounds.size() - 1);
            finishedAfter = last.finished();
            afterId = last.id();
            String next = finishedAfter.toEpochMilli() + "|" + afterId;
            // the counts and the checkpoint together: a crash between batches never counts a round twice
            transaction.executeWithoutResult(status -> {
                counts.forEach((day, c) -> finance.addWalletCheck(day, c[0], c[1]));
                state.put(CHECKPOINT, next);
            });
            if (rounds.size() < BATCH) {
                caughtUp = true;
                break;
            }
        }
        state.put(CAUGHT_UP, String.valueOf(caughtUp));
    }

    /** Rounds played between the two days (inclusive) whose wallet transactions differ from them, as checked so far. */
    public long differing(LocalDate first, LocalDate last) {
        return finance.walletCheckDiffering(first, last);
    }

    /** The first day the check covers (rounds finished before its first run minus the window are not checked). */
    public LocalDate coveredFrom() {
        Instant from = state.get(FROM).map(at -> Instant.ofEpochMilli(Long.parseLong(at))).orElse(Instant.now().minus(WINDOW));
        return finance.zoneDay(from);
    }

    /** False until the check has gone through every round finished so far. */
    public boolean caughtUp() {
        return state.get(CAUGHT_UP).map(Boolean::parseBoolean).orElse(false);
    }
}
