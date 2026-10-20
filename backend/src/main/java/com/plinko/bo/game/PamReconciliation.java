package com.plinko.bo.game;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import com.plinko.repo.GameRepository;
import org.springframework.stereotype.Service;

/**
 * Review 7.1: checks the casino wallet's record of this game's money movements against the game's own, for a time range.
 * After a backup is restored this finds what the restore lost (the wallet moved money for rounds the game no longer
 * has); at any time it finds wallet calls the two sides disagree about. It only reads and reports: what to do with each
 * difference is decided by people (a lost round is settled with the casino; a payment the game still owes is retried).
 */
@Service
public class PamReconciliation {

    /** At most a week at a time: a restore point is days old at most (point-in-time recovery keeps 7 days). */
    public static final Duration MAX_RANGE = Duration.ofDays(7);
    static final int MAX_ROWS = 200_000;
    /** The two sides stamp a call a moment apart: the game's rows are read with this margin on both sides. */
    static final Duration MARGIN = Duration.ofMinutes(5);
    static final int SHOWN = 200;

    public record Difference(String kind, String transactionId, String type, String roundId, String playerId, BigDecimal gameAmount,
                             BigDecimal walletAmount, String gameStatus, Instant time) {
    }

    /**
     * walletOnly: money the wallet moved that the game has no record of (rounds lost by a restore); gameOnly: calls the
     * game recorded as done that the wallet doesn't have; different: the two disagree on amount or type; notConfirmed:
     * calls the game still waits for that the wallet did make (its retry will settle them). Each list shows the first 200.
     */
    public record Report(Instant from, Instant to, int walletCount, int gameCount, int matched, Map<String, Integer> counts,
                         List<Difference> differences, boolean complete) {
        public boolean clean() {
            return counts.values().stream().allMatch(c -> c == 0) && complete;
        }
    }

    private final PamClient pam;
    private final GameRepository game;

    public PamReconciliation(PamClient pam, GameRepository game) {
        this.pam = pam;
        this.game = game;
    }

    /** The wallet's name for a game transaction type (settlements move money with the wallet's credit and rollback). */
    static String walletType(String gameType) {
        return switch (gameType) {
            case "MANUAL_PAY" -> "CREDIT";
            case "MANUAL_REF" -> "ROLLBACK";
            default -> gameType;
        };
    }

    public Report compare(Instant from, Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("Choose a range of up to " + MAX_RANGE.toDays() + " days");
        }
        Map<String, PamClient.PamTransaction> wallet = new HashMap<>();
        boolean complete = true;
        String after = null;
        do {
            PamClient.TransactionPage page = pam.transactions(from, to, after);
            page.transactions().forEach(t -> wallet.put(t.transactionId(), t));
            String next = page.next();
            if (next != null && next.equals(after)) {
                throw new PamException("INVALID_RESPONSE", "The wallet returned the same page twice", true);
            }
            after = next;
            if (wallet.size() > MAX_ROWS) {
                complete = false;
                break;
            }
        } while (after != null);

        List<GameRepository.Transaction> ours = game.transactionsBetween(from.minus(MARGIN), to.plus(MARGIN), MAX_ROWS + 1);
        if (ours.size() > MAX_ROWS) {
            complete = false;
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String kind : List.of("walletOnly", "gameOnly", "different", "notConfirmed")) {
            counts.put(kind, 0);
        }
        List<Difference> differences = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int matched = 0;
        int counted = 0;
        for (GameRepository.Transaction tx : ours) {
            boolean inRange = tx.createdAt() == null || (!tx.createdAt().isBefore(from) && tx.createdAt().isBefore(to));
            PamClient.PamTransaction theirs = wallet.get(tx.id());
            if (theirs == null) {
                // a refused or never-answered call leaves nothing at the wallet: only one the game counts as done is missing
                if (inRange && "OK".equals(tx.status())) {
                    add(counts, differences, new Difference("gameOnly", tx.id(), tx.type(), tx.roundId(), null, tx.amount(), null, tx.status(),
                            tx.createdAt()));
                }
                continue;
            }
            seen.add(tx.id());
            counted += inRange ? 1 : 0;
            boolean sameType = walletType(tx.type()).equals(theirs.type());
            // a refund's amount is the wallet's to report (nothing, if the bet was already refunded): only bets and wins compare
            boolean sameAmount = "ROLLBACK".equals(theirs.type()) || tx.amount().compareTo(theirs.amount()) == 0;
            if (!sameType || !sameAmount) {
                add(counts, differences, new Difference("different", tx.id(), tx.type(), tx.roundId(), theirs.playerId(), tx.amount(),
                        theirs.amount(), tx.status(), theirs.time()));
            } else if (!"OK".equals(tx.status())) {
                add(counts, differences, new Difference("notConfirmed", tx.id(), tx.type(), tx.roundId(), theirs.playerId(), tx.amount(),
                        theirs.amount(), tx.status(), theirs.time()));
            } else {
                matched++;
            }
        }
        for (PamClient.PamTransaction theirs : wallet.values()) {
            if (!seen.contains(theirs.transactionId())) {
                add(counts, differences, new Difference("walletOnly", theirs.transactionId(), theirs.type(), theirs.roundId(), theirs.playerId(),
                        null, theirs.amount(), null, theirs.time()));
            }
        }
        differences.sort(java.util.Comparator.comparing(Difference::kind).thenComparing(d -> d.time() == null ? Instant.EPOCH : d.time()));
        return new Report(from, to, wallet.size(), counted, matched, counts, differences, complete);
    }

    private static void add(Map<String, Integer> counts, List<Difference> differences, Difference d) {
        int n = counts.merge(d.kind(), 1, Integer::sum);
        if (n <= SHOWN) {
            differences.add(d);
        }
    }
}
