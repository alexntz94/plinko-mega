package com.plinko.bo.game;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.plinko.repo.GameRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The game's tables in memory, for unit tests of whole rounds: the same rules as the database where the game relies on
 * them (the round lock, the unique request id and meter_prev, status transitions), transactions that really roll back
 * ({@link #transactions()}), and switches to make a write fail or the process "die" right after a commit.
 */
class MemoryGameRepository extends GameRepository {

    /** Everything the database holds; copied whole when a transaction begins, put back when it rolls back. */
    private static final class State {
        final Map<Long, Player> players = new LinkedHashMap<>();
        final Map<Long, String[]> locks = new HashMap<>();          // player -> {claim, until (epoch ms)}
        final Map<Long, String> meterRounds = new HashMap<>();
        final Map<String, Session> sessions = new HashMap<>();
        final Set<String> launchTokens = new HashSet<>();
        final Map<String, Round> rounds = new LinkedHashMap<>();
        final Map<String, String> byRequest = new HashMap<>();      // "player:request" -> round
        final Map<String, String> continuedBy = new HashMap<>();    // meter_prev -> round
        final Map<String, Transaction> transactions = new LinkedHashMap<>();
        final Map<String, Instant> nextRetry = new HashMap<>();

        State copy() {
            State c = new State();
            c.players.putAll(players);
            locks.forEach((k, v) -> c.locks.put(k, v.clone()));
            c.meterRounds.putAll(meterRounds);
            c.sessions.putAll(sessions);
            c.launchTokens.addAll(launchTokens);
            c.rounds.putAll(rounds);
            c.byRequest.putAll(byRequest);
            c.continuedBy.putAll(continuedBy);
            c.transactions.putAll(transactions);
            c.nextRetry.putAll(nextRetry);
            return c;
        }
    }

    private State state = new State();
    private long nextPlayer = 7;
    /** A write that fails the next time it is called (the database refuses it), by method name. */
    private final Set<String> failing = new HashSet<>();
    /** Run just before every call of the named write (until replaced), e.g. to let another round in or edit a row. */
    private final Map<String, Consumer<Object[]>> before = new HashMap<>();
    /** Set once the process "died": its writes from then on never reach the database. */
    private volatile boolean dead;

    MemoryGameRepository() {
        super(null, null, null);
    }

    // ------------------------------------------------------------------ test switches

    /** The next call of this write fails with a database error (inside its transaction, which then rolls back). */
    void failNext(String method) {
        failing.add(method);
    }

    void before(String method, Consumer<Object[]> hook) {
        before.put(method, hook);
    }

    /** The process stops: nothing it writes afterwards is kept (until {@link #restart()}). */
    void die() {
        dead = true;
    }

    void restart() {
        dead = false;
    }

    /** Moves every round's start and every round lock's end back in time, as if that much time had passed. */
    synchronized void age(Duration by) {
        state.rounds.replaceAll((id, r) -> withCreated(r, r.createdAt().minus(by)));
        state.locks.values().forEach(lock -> lock[1] = String.valueOf(Long.parseLong(lock[1]) - by.toMillis()));
    }

    Player addPlayer(String externalId, String currency, BigDecimal balance) {
        Player player = new Player(nextPlayer++, externalId, externalId, currency, balance, false, Instant.now(), Instant.now());
        state.players.put(player.id(), player);
        return player;
    }

    void addSession(Session session) {
        state.sessions.put(session.id(), session);
    }

    void setBlocked(long playerId, boolean blocked) {
        Player p = state.players.get(playerId);
        state.players.put(playerId, new Player(p.id(), p.externalId(), p.username(), p.currency(), p.lastBalance(), blocked, p.createdAt(),
                p.lastSeenAt()));
    }

    Map<String, Round> rounds() {
        return state.rounds;
    }

    Round round(String id) {
        return state.rounds.get(id);
    }

    List<Transaction> transactionsOf(String roundId) {
        return transactionsOfRound(roundId);
    }

    List<Transaction> allTransactions() {
        return List.copyOf(state.transactions.values());
    }

    boolean lockHeld(long playerId) {
        String[] lock = state.locks.get(playerId);
        return lock != null && lock[0] != null && Long.parseLong(lock[1]) > System.currentTimeMillis();
    }

    /** Records that this round continued the bonus meter from that state (as another round's outcome would). */
    synchronized void continueMeter(String meterState, String roundId) {
        state.continuedBy.put(meterState, roundId);
    }

    /** Replaces a stored round directly, as someone with database access would. */
    void edit(String roundId, java.util.function.UnaryOperator<Round> change) {
        state.rounds.put(roundId, change.apply(state.rounds.get(roundId)));
    }

    /**
     * Transactions run one at a time from now on, as rows locked by the database would make them: for tests with rounds
     * of several threads, whose rollbacks would otherwise put back a copy taken before another thread's commit.
     */
    void serialTransactions() {
        serial = new java.util.concurrent.locks.ReentrantLock();
    }

    private volatile java.util.concurrent.locks.ReentrantLock serial;

    /** Transactions over this store: a rollback puts back what was there when the transaction began. */
    PlatformTransactionManager transactions() {
        return new PlatformTransactionManager() {
            private final ThreadLocal<State> saved = new ThreadLocal<>();

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                if (serial != null) {
                    serial.lock();
                }
                synchronized (MemoryGameRepository.this) {
                    saved.set(state.copy());
                }
                return new SimpleTransactionStatus();
            }

            private void end() {
                if (serial != null && serial.isHeldByCurrentThread()) {
                    serial.unlock();
                }
            }

            @Override
            public void commit(TransactionStatus status) {
                saved.remove();
                end();
                commits++;
                if (crashAfterCommit == commits) {
                    die();
                    throw new ProcessDied("the process died right after transaction " + commits + " committed");
                }
            }

            @Override
            public void rollback(TransactionStatus status) {
                synchronized (MemoryGameRepository.this) {
                    state = saved.get();
                }
                end();
            }
        };
    }

    private int commits;
    private int crashAfterCommit = -1;

    /** The process dies right after the n-th transaction (counting from now, 1-based) has committed. */
    void dieAfterCommit(int n) {
        crashAfterCommit = commits + n;
    }

    /** The process stopped: what it was doing is lost, what it committed is kept. */
    static final class ProcessDied extends RuntimeException {
        ProcessDied(String message) {
            super(message);
        }
    }

    private synchronized void write(String method, Object... args) {
        Consumer<Object[]> hook = before.get(method);
        if (hook != null) {
            hook.accept(args);
        }
        if (failing.remove(method)) {
            throw new DataAccessResourceFailureException("database refused " + method);
        }
    }

    // ------------------------------------------------------------------ the repository

    @Override
    public boolean whileLocked(String name, int waitSeconds, Runnable work) {
        work.run();
        return true;
    }

    @Override
    public synchronized Player upsertPlayer(String externalId, String username, String currency, BigDecimal balance) {
        Optional<Player> existing = state.players.values().stream().filter(p -> p.externalId().equals(externalId)).findFirst();
        Player player = existing.map(p -> new Player(p.id(), externalId, username, currency, balance, p.blocked(), p.createdAt(), Instant.now()))
                .orElseGet(() -> new Player(nextPlayer++, externalId, username, currency, balance, false, Instant.now(), Instant.now()));
        if (!dead) {
            state.players.put(player.id(), player);
        }
        return player;
    }

    @Override
    public synchronized Optional<Player> findPlayer(long id) {
        return Optional.ofNullable(state.players.get(id));
    }

    @Override
    public synchronized void updatePlayerBalance(long id, BigDecimal balance) {
        if (dead) {
            return;
        }
        write("updatePlayerBalance", id, balance);
        Player p = state.players.get(id);
        state.players.put(id, new Player(p.id(), p.externalId(), p.username(), p.currency(), balance, p.blocked(), p.createdAt(), Instant.now()));
    }

    @Override
    public synchronized boolean claimLaunchToken(String tokenHash) {
        return dead || state.launchTokens.add(tokenHash);
    }

    @Override
    public synchronized void insertSession(Session session) {
        if (!dead) {
            state.sessions.put(session.id(), session);
        }
    }

    @Override
    public synchronized Optional<Session> findSession(String id) {
        return Optional.ofNullable(state.sessions.get(id));
    }

    @Override
    public synchronized void extendSession(String id, Instant expiresAt) {
        if (dead) {
            return;
        }
        write("extendSession", id, expiresAt);
        Session s = state.sessions.get(id);
        state.sessions.put(id, new Session(s.id(), s.playerId(), s.pamToken(), expiresAt, s.theme()));
    }

    @Override
    public synchronized boolean claimRound(long playerId, String claim, long seconds) {
        if (dead) {
            return false;
        }
        write("claimRound", playerId, claim);
        if (lockHeld(playerId)) {
            return false;
        }
        state.locks.put(playerId, new String[] {claim, String.valueOf(System.currentTimeMillis() + seconds * 1000)});
        return true;
    }

    @Override
    public synchronized void settlePlayer(long playerId, BigDecimal balance, String claim, String meterRound) {
        if (dead) {
            return;
        }
        write("settlePlayer", playerId, balance, claim);
        if (meterRound != null) {
            state.meterRounds.put(playerId, meterRound);
        }
        Player p = state.players.get(playerId);
        state.players.put(playerId, new Player(p.id(), p.externalId(), p.username(), p.currency(), balance, p.blocked(), p.createdAt(), Instant.now()));
        releaseRound(playerId, claim);
    }

    @Override
    public synchronized void releaseRound(long playerId, String claim) {
        if (dead) {
            return;
        }
        String[] lock = state.locks.get(playerId);
        if (lock != null && claim.equals(lock[0])) {
            state.locks.remove(playerId);
        }
    }

    @Override
    public synchronized List<Round> dueForRetry(int maxAttempts, int limit) {
        Instant now = Instant.now();
        return state.rounds.values().stream()
                .filter(r -> List.of("CREDIT_PENDING", "ROLLBACK_PENDING").contains(r.status()))
                .filter(r -> state.transactions.values().stream().anyMatch(t -> t.roundId().equals(r.id())
                        && t.type().equals("CREDIT_PENDING".equals(r.status()) ? "CREDIT" : "ROLLBACK") && t.attempts() < maxAttempts
                        && (state.nextRetry.get(t.id()) == null || !state.nextRetry.get(t.id()).isAfter(now))))
                .limit(limit).toList();
    }

    @Override
    public synchronized void scheduleRetry(String transactionId, Instant at) {
        if (!dead) {
            state.nextRetry.put(transactionId, at);
        }
    }

    @Override
    public synchronized Optional<String> meterRound(long playerId) {
        return Optional.ofNullable(state.meterRounds.get(playerId));
    }

    @Override
    public synchronized void setMeterRound(long playerId, String roundId) {
        if (dead) {
            return;
        }
        write("setMeterRound", playerId, roundId);
        state.meterRounds.put(playerId, roundId);
    }

    @Override
    public synchronized Optional<Round> roundContinuing(String meterState) {
        return Optional.ofNullable(state.continuedBy.get(meterState)).map(state.rounds::get);
    }

    @Override
    public synchronized void insertRound(Round round, String requestId) {
        if (dead) {
            return;
        }
        write("insertRound", round, requestId);
        if (requestId != null && state.byRequest.putIfAbsent(round.playerId() + ":" + requestId, round.id()) != null) {
            throw new DuplicateKeyException("uk_round_request");
        }
        state.rounds.put(round.id(), withCreated(round, Instant.now()));
    }

    @Override
    public synchronized List<Round> history(long playerId, Instant since, Instant beforePlayed, String beforeId, int limit) {
        return state.rounds.values().stream().filter(r -> r.playerId() == playerId && r.signature() != null)
                .sorted(Comparator.comparing(Round::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized Optional<Round> roundByRequest(long playerId, String requestId) {
        return Optional.ofNullable(state.byRequest.get(playerId + ":" + requestId)).map(state.rounds::get);
    }

    @Override
    public synchronized void saveOutcome(String roundId, String slots, String multipliers, String bonus, String meterPrev, BigDecimal totalWin,
                                         String signature, String status) {
        if (dead) {
            return;
        }
        write("saveOutcome", roundId, meterPrev);
        if (meterPrev != null && state.continuedBy.containsKey(meterPrev)) {
            throw new DuplicateKeyException("uk_round_meter_prev");
        }
        if (meterPrev != null) {
            state.continuedBy.put(meterPrev, roundId);
        }
        Round r = state.rounds.get(roundId);
        state.rounds.put(roundId, new Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(), totalWin,
                r.currency(), status, slots, multipliers, r.error(), signature, r.createdAt(), Instant.now(), bonus, r.oddsRef(), r.maxWin()));
    }

    @Override
    public synchronized boolean transitionRound(String roundId, String from, String to, String error) {
        if (dead) {
            return false;
        }
        Round r = state.rounds.get(roundId);
        if (r == null || !r.status().equals(from)) {
            return false;
        }
        state.rounds.put(roundId, withStatus(r, to, error));
        return true;
    }

    @Override
    public synchronized void updateRoundStatus(String roundId, String status, String error) {
        if (dead) {
            return;
        }
        write("updateRoundStatus", roundId, status, error);
        state.rounds.put(roundId, withStatus(state.rounds.get(roundId), status, error));
    }

    @Override
    public synchronized Optional<Round> findRound(String id) {
        return Optional.ofNullable(state.rounds.get(id));
    }

    @Override
    public synchronized List<Round> staleRounds(List<String> statuses, Instant olderThan) {
        return state.rounds.values().stream().filter(r -> statuses.contains(r.status()) && r.createdAt().isBefore(olderThan)).toList();
    }

    @Override
    public synchronized void insertTransaction(Transaction tx) {
        if (dead) {
            return;
        }
        write("insertTransaction", tx);
        if (state.transactions.containsKey(tx.id())) {
            throw new DuplicateKeyException("wallet_transaction.PRIMARY");
        }
        state.transactions.put(tx.id(), new Transaction(tx.id(), tx.roundId(), tx.playerId(), tx.type(), tx.amount(), tx.status(), tx.originalTxId(),
                null, null, 0, Instant.now(), Instant.now(), tx.signature()));
    }

    @Override
    public synchronized Optional<Instant> lastRoundAt(long playerId) {
        return state.rounds.values().stream().filter(r -> r.playerId() == playerId).map(Round::createdAt).max(Comparator.naturalOrder());
    }

    @Override
    public synchronized void updateTransaction(String id, String status, String pamReference, String error) {
        updateTransaction(id, status, pamReference, error, false);
    }

    @Override
    public synchronized void updateTransaction(String id, String status, String pamReference, String error, boolean refused) {
        if (dead) {
            return;
        }
        write("updateTransaction", id, status);
        Transaction t = state.transactions.get(id);
        // as the database's guard (V29): only a failed transaction is ever a refusal, and the answer ends a send in flight
        state.transactions.put(id, new Transaction(t.id(), t.roundId(), t.playerId(), t.type(), t.amount(), status, t.originalTxId(), pamReference,
                error, t.attempts() + 1, t.createdAt(), Instant.now(), t.signature(), refused && "FAILED".equals(status), null));
    }

    /** Committed at once (its own connection in the real repository; never called inside a transaction). */
    @Override
    public synchronized void markSending(String id) {
        if (dead) {
            return;
        }
        write("markSending", id);
        Transaction t = state.transactions.get(id);
        if (t != null && !"OK".equals(t.status())) {
            Transaction marked = new Transaction(t.id(), t.roundId(), t.playerId(), t.type(), t.amount(), t.status(), t.originalTxId(),
                    t.pamReference(), t.error(), t.attempts(), t.createdAt(), Instant.now(), t.signature(), false, Instant.now());
            state.transactions.put(id, marked);
        }
    }

    @Override
    public synchronized List<String> stoppedRounds(int maxAttempts) {
        List<String> out = new ArrayList<>();
        for (Round r : state.rounds.values()) {
            if (List.of("CREDIT_PENDING", "ROLLBACK_PENDING").contains(r.status())) {
                String type = "CREDIT_PENDING".equals(r.status()) ? "CREDIT" : "ROLLBACK";
                if (state.transactions.values().stream().anyMatch(t -> t.roundId().equals(r.id()) && t.type().equals(type) && t.attempts() >= maxAttempts)) {
                    out.add(r.id());
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<Transaction> transactionsOfRound(String roundId) {
        return state.transactions.values().stream().filter(t -> t.roundId().equals(roundId)).toList();
    }

    private static Round withStatus(Round r, String status, String error) {
        return new Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(), r.totalWin(), r.currency(), status,
                r.slots(), r.multipliers(), error, r.signature(), r.createdAt(), Instant.now(), r.bonus(), r.oddsRef(), r.maxWin());
    }

    private static Round withCreated(Round r, Instant created) {
        return new Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(), r.totalWin(), r.currency(),
                r.status(), r.slots(), r.multipliers(), r.error(), r.signature(), created, r.updatedAt(), r.bonus(), r.oddsRef(), r.maxWin());
    }
}
