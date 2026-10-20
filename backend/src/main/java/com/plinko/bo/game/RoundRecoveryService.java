package com.plinko.bo.game;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.audit.AuditService;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import com.plinko.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Finishes rounds whose credit or rollback failed, and rounds left half-way by a crash.
 * <ul>
 * <li>A credit is paid only if the round passes {@link WinCheck}: its signature verifies, its win worked out again
 *     from the stored balls matches, its bet was taken, and the credit itself was signed by the game for this round,
 *     this player and exactly the round's win. Its id is derived from the round, so the PAM applies it at most once.</li>
 * <li>A rollback (refund of the bet) is sent only for a round that never got an outcome and has no credit,
 *     and only if the game signed it.</li>
 * <li>Anything else is marked TAMPERED, never paid, and written to the audit log.</li>
 * </ul>
 */
@Service
public class RoundRecoveryService {

    /**
     * Locks per round (striped), so different rounds can be retried at once (review 3.8) but one round never twice at
     * the same time. Locks rather than synchronized, so a virtual thread waiting for I/O doesn't hold up its carrier.
     */
    private final java.util.concurrent.locks.ReentrantLock[] locks = java.util.stream.Stream.generate(java.util.concurrent.locks.ReentrantLock::new)
            .limit(64).toArray(java.util.concurrent.locks.ReentrantLock[]::new);

    private java.util.concurrent.locks.ReentrantLock lockFor(String roundId) {
        return locks[Math.floorMod(roundId.hashCode(), locks.length)];
    }

    /** At most this many rounds retried in one pass, and this many at the same time: a recovering wallet is not swamped. */
    static final int BATCH = 200;
    static final int PARALLEL = 8;
    /** How long one pass may keep taking batches (the pass runs every minute; one instance at a time). */
    static final java.time.Duration PASS_TIME = java.time.Duration.ofSeconds(45);

    /** How long a refused credit or refund waits before its next try: 30 s, then doubling, at most 15 minutes. */
    static java.time.Duration backoff(int attempts) {
        long seconds = 30L << Math.min(Math.max(attempts - 1, 0), 10);
        return java.time.Duration.ofSeconds(Math.min(seconds, 15 * 60));
    }

    private static final Logger log = LoggerFactory.getLogger(RoundRecoveryService.class);
    /**
     * A round still PENDING or DEBITED after this long was interrupted (normal rounds take seconds). Never sooner than
     * its player's round lock runs out, so a slow but live wallet call is never overtaken by a refund.
     */
    private final Duration stuckAfter;

    private final GameRepository repository;
    private final PamClient pam;
    private final RoundSigner signer;
    private final WalletSigner walletSigner;
    private final AuditService audit;
    private final WinCheck winCheck;
    private final int maxAttempts;

    public RoundRecoveryService(GameRepository repository, PamClient pam, RoundSigner signer, WalletSigner walletSigner,
                                AuditService audit, WinCheck winCheck, PlinkoProperties properties) {
        this.winCheck = winCheck;
        this.repository = repository;
        this.pam = pam;
        this.signer = signer;
        this.walletSigner = walletSigner;
        this.audit = audit;
        this.maxAttempts = properties.retry().maxAttempts();
        this.stuckAfter = Duration.ofSeconds(GameService.roundClaimSeconds(properties) + 60);
    }

    /** The daily totals' increments (optional: absent in unit tests). */
    private com.plinko.repo.StatsRepository stats;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStats(com.plinko.repo.StatsRepository stats) {
        this.stats = stats;
    }

    /** Marks a day's totals as changed when a round of that day changes counted status late (optional: absent in unit tests). */
    private DailyTotals dailyTotals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDailyTotals(DailyTotals dailyTotals) {
        this.dailyTotals = dailyTotals;
    }

    /** Transactions for a status change and its daily-totals increment together (optional: absent in unit tests, then run as is). */
    private org.springframework.transaction.support.TransactionTemplate transaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactions(org.springframework.transaction.PlatformTransactionManager transactions) {
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    /**
     * Review R2: a round's status change and its increment to the daily totals are one transaction, so a rebuild's snapshot
     * sees both or neither (a round is counted once: by the rebuild, or by its increment).
     */
    private <T> T atomically(java.util.function.Supplier<T> work) {
        return transaction == null ? work.get() : transaction.execute(status -> work.get());
    }

    private void daySettled(GameRepository.Round round) {
        if (dailyTotals != null) {
            dailyTotals.roundSettled(round.createdAt());
        }
    }

    /** Only one backend instance retries at a time (several run while a new version rolls out). */
    private static final String LOCK = "plinko_round_recovery";

    @Scheduled(fixedDelayString = "${plinko.retry.interval-ms}", initialDelayString = "${plinko.retry.interval-ms}")
    public void retryPending() {
        // a busy lock means another instance is on it right now
        repository.whileLocked(LOCK, 0, this::retryPendingNow);
    }

    /** The admin's retry button: waits briefly for a background run on another instance to finish. */
    public String retryExclusive(GameRepository.Round round) {
        String[] result = {"Busy: the background retry is running; try again in a minute"};
        repository.whileLocked(LOCK, 10, () -> result[0] = retry(round));
        return result[0];
    }

    void retryPendingNow() {
        for (GameRepository.Round round : repository.staleRounds(List.of(RoundStatus.PENDING, RoundStatus.DEBITED), Instant.now().minus(stuckAfter))) {
            try {
                resolveInterrupted(round);
            } catch (RuntimeException e) {
                log.error("Could not resolve interrupted round {}", round.id(), e);
            }
        }
        // the most overdue first, in batches, a few at a time (review 3.8); after a wallet outage thousands are owed, so a
        // pass keeps taking batches while more are due, for up to PASS_TIME, never retrying a round twice in one pass
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(PARALLEL);
        java.util.Set<String> tried = new java.util.HashSet<>();
        Instant until = Instant.now().plus(PASS_TIME);
        while (true) {
            List<GameRepository.Round> batch = repository.dueForRetry(maxAttempts, BATCH);
            List<GameRepository.Round> fresh = batch.stream().filter(round -> tried.add(round.id())).toList();
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (GameRepository.Round round : fresh) {
                    if (!ownTransaction(round).map(tx -> tx.attempts() < maxAttempts).orElse(true)) {
                        continue;
                    }
                    slots.acquireUninterruptibly();
                    workers.submit(() -> {
                        try {
                            retry(round);
                        } catch (RuntimeException e) {
                            log.error("Could not retry round {}", round.id(), e);
                        } finally {
                            slots.release();
                        }
                    });
                }
            }
            if (batch.size() < BATCH || fresh.isEmpty() || Instant.now().isAfter(until)) {
                break;
            }
        }
        alarmStopped();
    }

    /**
     * A round that stopped half-way (crash, database error): if its outcome was decided and signed, the win is
     * paid; if not, the bet is refunded. Either way with the round's own derived, signed transaction.
     */
    public String resolveInterrupted(GameRepository.Round round) {
        java.util.concurrent.locks.ReentrantLock lock = lockFor(round.id());
        lock.lock();
        String logged = logContext(round.id());
        try {
            GameRepository.Round current = repository.findRound(round.id()).orElseThrow();
            if (!List.of(RoundStatus.PENDING, RoundStatus.DEBITED).contains(current.status())) {
                return "Nothing to resolve";
            }
            GameRepository.Player player = repository.findPlayer(current.playerId()).orElseThrow();
            List<GameRepository.Transaction> transactions = repository.transactionsOfRound(current.id());

            // The status moves first, and only if nobody else moved the round meanwhile; a round that is still being
            // played then fails its own next step (the database refuses ROLLBACK_PENDING -> DEBITED) instead of
            // being both refunded and paid.
            if (current.signature() != null) {
                if (!signer.verify(current)) {
                    return tampered(current, "interrupted round failed its signature check");
                }
                boolean moved = atomically(() -> {
                    if (!repository.transitionRound(current.id(), current.status(), RoundStatus.CREDIT_PENDING, "Interrupted round: paying the win")) {
                        return false;
                    }
                    if (stats != null) {
                        stats.addDelta(current, 1);     // N4 #2: counted from now on (its last transaction never ran)
                    }
                    return true;
                });
                if (!moved) {
                    return "Round moved on by itself";
                }
                if (find(transactions, "CREDIT").isEmpty()) {
                    repository.insertTransaction(new GameRepository.Transaction(walletSigner.creditId(current.id()), current.id(), player.id(),
                            "CREDIT", current.totalWin(), "PENDING", null, null, null, 0, null, null,
                            walletSigner.signCredit(current.id(), player.externalId(), current.totalWin(), current.currency())));
                }
            } else {
                Optional<GameRepository.Transaction> debit = find(transactions, "DEBIT");
                if (debit.isEmpty()) {
                    repository.transitionRound(current.id(), current.status(), RoundStatus.CANCELLED, "Interrupted before the bet was sent");
                    return "Cancelled (no bet was taken)";
                }
                if (!repository.transitionRound(current.id(), current.status(), RoundStatus.ROLLBACK_PENDING, "Interrupted round: refunding the bet")) {
                    return "Round moved on by itself";
                }
                if (find(transactions, "ROLLBACK").isEmpty()) {
                    repository.insertTransaction(new GameRepository.Transaction(walletSigner.rollbackId(current.id()), current.id(), player.id(),
                            "ROLLBACK", java.math.BigDecimal.ZERO, "PENDING", debit.get().id(), null, null, 0, null, null,
                            walletSigner.signRollback(current.id(), player.externalId(), debit.get().id())));
                }
            }
            // DEBITED is counted in the daily totals and ROLLBACK_PENDING is not; PENDING is not and CREDIT_PENDING is
            daySettled(current);
            log.warn("Round {} was interrupted; resolving it", current.id());
            return retry(current);
        } finally {
            stoppedCache = null;    // this round may have stopped or been paid: the admin alarm reads it again
            endLogContext(logged);
            lock.unlock();
        }
    }

    /** Retries one round now; also used by the admin "retry" button. Returns a short result text. */
    public String retry(GameRepository.Round round) {
        java.util.concurrent.locks.ReentrantLock lock = lockFor(round.id());
        lock.lock();
        String logged = logContext(round.id());
        try {
            GameRepository.Round current = repository.findRound(round.id()).orElseThrow();
            GameRepository.Player player = repository.findPlayer(current.playerId()).orElseThrow();
            List<GameRepository.Transaction> transactions = repository.transactionsOfRound(current.id());
            String token = repository.findSession(current.sessionId()).map(GameRepository.Session::pamToken).orElse("");

            if (RoundStatus.CREDIT_PENDING.equals(current.status())) {
                String problem = winCheck.problem(current, transactions, player);
                if (WinCheck.oddsUnreadable(problem)) {
                    // tried again later, not every minute: many such rounds must not crowd out the other retries
                    find(transactions, "CREDIT").ifPresent(tx -> repository.scheduleRetry(tx.id(), java.time.Instant.now().plus(java.time.Duration.ofMinutes(15))));
                    return oddsUnreadable(current, problem);
                }
                if (problem != null) {
                    return tampered(current, "credit blocked: " + problem);
                }
                GameRepository.Transaction tx = find(transactions, "CREDIT").orElseThrow();
                if ("OK".equals(tx.status())) {
                    repository.updateRoundStatus(current.id(), RoundStatus.COMPLETED, null);
                    return "Credit was already paid";
                }
                repository.markSending(tx.id());    // R4: an earlier refusal no longer stands once it is sent again
                try {
                    PamClient.PamResult result = pam.credit(new PamClient.WalletRequest(player.externalId(), token, tx.id(), current.id(),
                            tx.amount(), current.currency()));
                    repository.updateTransaction(tx.id(), "OK", result.reference(), null);
                    repository.updateRoundStatus(current.id(), RoundStatus.COMPLETED, null);
                    repository.updatePlayerBalance(player.id(), result.balance());
                    return "Credit paid";
                } catch (PamException e) {
                    return failed(current, tx, e);
                }
            }

            if (RoundStatus.ROLLBACK_PENDING.equals(current.status())) {
                GameRepository.Transaction tx = find(transactions, "ROLLBACK").orElse(null);
                Optional<GameRepository.Transaction> debit = find(transactions, "DEBIT");
                // A refund is only ever right for a round whose outcome was never decided and that paid nothing.
                if (tx == null || current.signature() != null || current.totalWin() != null || find(transactions, "CREDIT").isPresent()
                        || debit.isEmpty() || !debit.get().id().equals(tx.originalTxId()) || !walletSigner.verifyRollback(tx, current, player)) {
                    return tampered(current, "rollback blocked: the round has an outcome or a credit, or the rollback is not signed");
                }
                if ("OK".equals(tx.status())) {
                    repository.updateRoundStatus(current.id(), RoundStatus.CANCELLED, "Rolled back");
                    return "Rollback was already done";
                }
                repository.markSending(tx.id());
                try {
                    PamClient.PamResult result = pam.rollback(new PamClient.RollbackRequest(player.externalId(), token, tx.id(),
                            tx.originalTxId(), current.id()));
                    repository.updateTransaction(tx.id(), "OK", result.reference(), null);
                    repository.updateRoundStatus(current.id(), RoundStatus.CANCELLED, "Rolled back on retry");
                    repository.updatePlayerBalance(player.id(), result.balance());
                    return "Rolled back";
                } catch (PamException e) {
                    if ("ROUND_SETTLED".equals(e.code())) {
                        // the wallet says this round was paid, but the game never sent it a credit: someone must look
                        repository.updateTransaction(tx.id(), "FAILED", null, e.stored(), e.refusal());
                        return tampered(current, "refund refused by the wallet: it says the round was already paid (ROUND_SETTLED), "
                                + "but the game has no credit for it; review with the wallet's records");
                    }
                    return failed(current, tx, e);
                }
            }
            return "Nothing to retry";
        } finally {
            stoppedCache = null;    // this round may have stopped or been paid: the admin alarm reads it again
            endLogContext(logged);
            lock.unlock();
        }
    }

    private String failed(GameRepository.Round round, GameRepository.Transaction tx, PamException e) {
        repository.updateTransaction(tx.id(), "FAILED", null, e.stored(), e.refusal());
        repository.scheduleRetry(tx.id(), java.time.Instant.now().plus(backoff(tx.attempts() + 1)));
        log.warn("Retry of round {} failed: {}", round.id(), e.getMessage());
        if (tx.attempts() + 1 >= maxAttempts) {
            // the background retry stops here: say so loudly, once per round (the admin alarm then shows it until it is settled)
            String what = "CREDIT".equals(tx.type()) ? "win of " + round.totalWin() + " " + round.currency() : "refund of the bet";
            alarmOnce(round.id(), "the " + what + " failed " + (tx.attempts() + 1) + " times", e.code());
        }
        return "Failed again: " + e.getMessage();
    }

    /**
     * N14.13: the round on every log line of a retry, and, for a background retry (no request of its own), an id of its
     * own, which the wallet call also sends as X-Request-Id. Returns the request id this set, to remove afterwards.
     */
    static String logContext(String roundId) {
        org.slf4j.MDC.put("roundId", roundId);
        if (org.slf4j.MDC.get("requestId") != null) {
            return null;
        }
        String id = "retry-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        org.slf4j.MDC.put("requestId", id);
        return id;
    }

    static void endLogContext(String setRequestId) {
        org.slf4j.MDC.remove("roundId");
        if (setRequestId != null) {
            org.slf4j.MDC.remove("requestId");
        }
    }

    /** Rounds already reported as stopped (N14.1), kept in the database when it is available so every instance agrees. */
    private final java.util.Set<String> alarmed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String ALARMED = "retries_stopped.alarmed";
    private com.plinko.repo.JobState state;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJobState(com.plinko.repo.JobState state) {
        this.state = state;
    }

    private void alarmOnce(String roundId, String what, String lastError) {
        java.util.Set<String> known = alarmedRounds();
        if (!known.add(roundId)) {
            return;
        }
        log.error("Round {}: {}; automatic retries stopped, needs a person (last error: {})", roundId, what, lastError);
        audit.log("system", "ROUND_RETRIES_STOPPED", roundId + ": " + what + "; automatic retries stopped. Last error: " + lastError);
        storeAlarmed(known);
    }

    private java.util.Set<String> alarmedRounds() {
        if (state != null) {
            state.get(ALARMED).ifPresent(v -> alarmed.addAll(v.isEmpty() ? List.of() : List.of(v.split(","))));
        }
        return alarmed;
    }

    private void storeAlarmed(java.util.Set<String> known) {
        if (state != null) {
            state.put(ALARMED, String.join(",", known));
        }
    }

    /**
     * N14.1: every round whose retries have stopped is reported once, also when it got there without a failure here
     * (max-attempts lowered below its attempts); rounds no longer stopped are forgotten.
     */
    void alarmStopped() {
        List<String> stopped = repository.stoppedRounds(maxAttempts);
        java.util.Set<String> known = alarmedRounds();
        known.retainAll(stopped);
        for (String id : stopped) {
            if (!known.contains(id)) {
                alarmOnce(id, "its credit or refund has used up its " + maxAttempts + " attempts", "see the round's transactions");
            }
        }
        storeAlarmed(known);
        stoppedCache = new Cached(Instant.now(), List.copyOf(stopped));
    }

    private record Cached(Instant at, List<String> rounds) {
    }

    private volatile Cached stoppedCache;

    /**
     * Rounds still owed a credit or refund whose automatic retries have stopped (shown as an alarm in the admin);
     * read at most every 30 seconds, not on every admin page (N14.2).
     */
    public List<String> stoppedRounds() {
        Cached cached = stoppedCache;
        if (cached == null || cached.at().isBefore(Instant.now().minusSeconds(30))) {
            cached = new Cached(Instant.now(), repository.stoppedRounds(maxAttempts));
            stoppedCache = cached;
        }
        return cached.rounds();
    }

    private final java.util.Set<String> oddsAlarmed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * N14.5: the round's odds version can't be read or verified (a configuration problem, e.g. a key mistake): the credit
     * waits, unpaid and not blocked, and the alarm says so once per round; it is paid once the odds version verifies.
     */
    String oddsUnreadable(GameRepository.Round round, String problem) {
        if (oddsAlarmed.add(round.id())) {
            log.error("ALERT ODDS_VERSION_UNREADABLE: round {}: {}; the credit waits until the odds version verifies (check the keys)", round.id(), problem);
            audit.log("system", "ODDS_VERSION_UNREADABLE", round.id() + ": " + problem + "; the credit waits, not blocked");
        }
        return "Waiting: " + problem;
    }

    /** Blocks a round that failed a check: never paid, flagged TAMPERED, written to the audit log and the error log. */
    public String block(GameRepository.Round round, String reason) {
        return tampered(round, reason);
    }

    private String tampered(GameRepository.Round round, String reason) {
        atomically(() -> {
            repository.updateRoundStatus(round.id(), RoundStatus.TAMPERED, "Integrity check failed: " + reason);
            if (stats != null && List.of(RoundStatus.CREDIT_PENDING, RoundStatus.COMPLETED).contains(round.status())) {
                stats.addDelta(round, -1);      // N4 #2: no longer counted (it was added when it reached this status)
            }
            return null;
        });
        daySettled(round);
        if (reason.contains(WinCheck.RETIRED_KEY)) {
            // R6: its own alarm, not the generic tampering one: money signed before the cutoff, which only a person can settle now
            log.error("ALERT SIGNED_WITH_RETIRED_KEY: round {} blocked: {}", round.id(), reason);
            audit.log("system", "ROUND_SIGNED_WITH_RETIRED_KEY", round.id() + ": " + reason + "; nothing was paid");
            return "Blocked: " + reason;
        }
        log.error("Round {} TAMPERED: {}", round.id(), reason);
        audit.log("system", "ROUND_TAMPERED", round.id() + ": " + reason + "; nothing was paid");
        return "Blocked: " + reason;
    }

    /** The transaction a pending round is waiting on. */
    private Optional<GameRepository.Transaction> ownTransaction(GameRepository.Round round) {
        String type = RoundStatus.CREDIT_PENDING.equals(round.status()) ? "CREDIT" : "ROLLBACK";
        return find(repository.transactionsOfRound(round.id()), type);
    }

    private static Optional<GameRepository.Transaction> find(List<GameRepository.Transaction> transactions, String type) {
        return transactions.stream().filter(tx -> tx.type().equals(type)).findFirst();
    }
}
