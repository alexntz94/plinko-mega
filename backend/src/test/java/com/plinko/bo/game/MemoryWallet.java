package com.plinko.bo.game;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;

/**
 * A casino wallet in memory that keeps the contract the game relies on (R1.1-R1.4): a repeated transaction id is the
 * same transaction and moves no money again; a rollback refunds its debit once, and a rollback of a debit that never
 * arrived refunds nothing and makes that debit refused if it arrives late. Faults can be queued per call.
 */
class MemoryWallet implements PamClient {

    /** What the next call of one kind does instead of answering normally. */
    sealed interface Fault permits Refuse, LostBefore, LostAfter, Hold, AppliedThen {
    }

    /** The wallet applied it, then {@code then} runs on the caller's side instead of the answer (e.g. the game's process dies). */
    record AppliedThen(Runnable then) implements Fault {
    }

    /** A definite answer with this code (and HTTP status, 0 for "not said"): nothing moved. */
    record Refuse(String code, int httpStatus) implements Fault {
        Refuse(String code) {
            this(code, 0);
        }
    }

    /** The call never reached the wallet (e.g. a timeout on the way): nothing moved, the outcome is unknown to the game. */
    record LostBefore() implements Fault {
    }

    /** The wallet applied it, but the answer was lost (e.g. a timeout on the way back): the outcome is unknown to the game. */
    record LostAfter() implements Fault {
    }

    /** The wallet answers only once {@code release} opens (a slow wallet); {@code arrived} opens when the call is waiting. */
    record Hold(CountDownLatch arrived, CountDownLatch release) implements Fault {
    }

    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, PamResult> applied = new HashMap<>();
    private final Map<String, BigDecimal> debits = new HashMap<>();
    private final Set<String> refunded = new HashSet<>();
    private final Set<String> cancelledBeforeArrival = new HashSet<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    /** Every call that reached the wallet, as "kind transactionId amount". */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private int references;

    synchronized void open(String playerId, BigDecimal balance) {
        balances.put(playerId, balance);
    }

    synchronized BigDecimal balanceOf(String playerId) {
        return balances.get(playerId);
    }

    synchronized void next(String kind, Fault fault) {
        faults.computeIfAbsent(kind, k -> new ArrayDeque<>()).add(fault);
    }

    long count(String kind) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.startsWith(kind + " ")).count();
        }
    }

    @Override
    public synchronized PamPlayer authenticate(String token) {
        String id = "pam-" + token;
        balances.putIfAbsent(id, new BigDecimal("100.00"));
        return new PamPlayer(id, id, "EUR", balances.get(id));
    }

    @Override
    public synchronized BigDecimal balance(String playerId, String token) {
        Fault fault = take("balance");
        if (fault instanceof Refuse refuse) {
            throw new PamException(refuse.code(), "refused", false, refuse.httpStatus());
        }
        if (fault != null) {
            throw new PamException("CONNECTION_ERROR", "no answer", true);
        }
        return balances.get(playerId);
    }

    @Override
    public PamResult debit(WalletRequest request) {
        return move("debit", request.transactionId(), request.playerId(), request.amount(), () -> {
            if (cancelledBeforeArrival.contains(request.transactionId())) {
                // R1.4: its rollback came first, so the late debit is refused
                throw new PamException("TRANSACTION_CANCELLED", "rolled back before it arrived", false);
            }
            if (balances.get(request.playerId()).compareTo(request.amount()) < 0) {
                throw new PamException(PamException.INSUFFICIENT_FUNDS, "no money", false);
            }
            debits.put(request.transactionId(), request.amount());
            return request.amount().negate();
        });
    }

    @Override
    public PamResult credit(WalletRequest request) {
        return move("credit", request.transactionId(), request.playerId(), request.amount(), request::amount);
    }

    @Override
    public PamResult rollback(RollbackRequest request) {
        String debit = request.originalTransactionId();
        return move("rollback", request.transactionId(), request.playerId(), null, () -> {
            if (!debits.containsKey(debit)) {
                cancelledBeforeArrival.add(debit);      // R1.3: accepted, nothing to refund
                return BigDecimal.ZERO;
            }
            return refunded.add(debit) ? debits.get(debit) : BigDecimal.ZERO;
        });
    }

    /** amount: what the call names (for the call log); change: works out the balance change, once, the first time the id arrives. */
    private PamResult move(String kind, String transactionId, String playerId, BigDecimal amount, java.util.function.Supplier<BigDecimal> change) {
        Fault fault;
        synchronized (this) {
            fault = take(kind);
        }
        if (fault instanceof Hold hold) {
            hold.arrived().countDown();
            try {
                if (!hold.release().await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held wallet call was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PamException("CONNECTION_ERROR", "interrupted", true);
            }
        }
        synchronized (this) {
            calls.add(kind + " " + transactionId + (amount == null ? "" : " " + amount.toPlainString()));
            if (fault instanceof Refuse refuse) {
                throw new PamException(refuse.code(), "refused", false, refuse.httpStatus());
            }
            if (fault instanceof LostBefore) {
                throw new PamException("CONNECTION_ERROR", "timed out", true);
            }
            PamResult earlier = applied.get(transactionId);
            if (earlier == null) {
                balances.merge(playerId, change.get(), BigDecimal::add);
                earlier = new PamResult(balances.get(playerId), "ref-" + (++references));
                applied.put(transactionId, earlier);
            }
            if (fault instanceof LostAfter) {
                throw new PamException("CONNECTION_ERROR", "timed out after it was applied", true);
            }
            if (fault instanceof AppliedThen then) {
                then.then().run();
            }
            return earlier;
        }
    }

    private Fault take(String kind) {
        Deque<Fault> queue = faults.get(kind);
        return queue == null ? null : queue.poll();
    }
}
