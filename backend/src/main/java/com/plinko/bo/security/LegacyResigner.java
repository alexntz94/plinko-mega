package com.plinko.bo.security;

import java.util.List;

import com.plinko.bo.game.RoundSigner;
import com.plinko.common.Integrity;
import com.plinko.common.KeyRing;
import com.plinko.repo.GameRepository;
import com.plinko.repo.JobState;
import com.plinko.repo.ResignedSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Review N8 #2: signs again, with the current keys, every frozen record still signed with the original ROUND_SIGNING_KEY
 * (rounds, confirmed wallet transactions, odds versions), so the original key can then be removed. A record is signed
 * again only if it verifies now; money still to move (a pending or failed transaction) never is. It runs only while
 * the original key is not yet retired and the key lists are set, on one instance at a time, in batches, remembering
 * where it got to.
 * <p>
 * Review R7: a record that fails its check (e.g. a round kept TAMPERED as evidence), or one made after the suspected leak
 * of the original key (LEGACY_KEY_SUSPECTED_LEAK, possibly forged), is never signed again: it is kept as evidence
 * (legacy_evidence) and no longer counts as left, so it can't keep the key from being removed. Each table is swept from
 * the start again whenever a sweep ends, so a transaction confirmed after the sweep passed it is signed again on the next
 * one; once nothing is left the job stops for good. LEGACY_RESIGN=false switches it off (the leak runbook: stop it first).
 * Review R8: how much is left is counted by the job at the end of a sweep and kept in job_state with its time, for the
 * Settings page, which never counts the tables itself.
 */
@Service
public class LegacyResigner {

    private static final Logger log = LoggerFactory.getLogger(LegacyResigner.class);
    static final int BATCH = 1000;
    private static final int BATCHES_PER_RUN = 50;
    static final String LEFT = "legacy_resign.left";
    static final String DONE = "legacy_resign.done";
    private static final List<String> TABLES = List.of(ResignedSignatures.ROUNDS, ResignedSignatures.TRANSACTIONS, ResignedSignatures.ODDS);

    private final JdbcClient jdbc;
    private final GameRepository game;
    private final RoundSigner rounds;
    private final Integrity integrity;
    private final KeyRing keys;
    private final ResignedSignatures resigned;
    private final JobState state;
    /** R7 #3: nothing made at or after this time is signed again (null: no leak suspected). */
    private final java.time.Instant suspectedLeak;
    private final boolean enabled;

    public LegacyResigner(JdbcClient jdbc, GameRepository game, RoundSigner rounds, Integrity integrity, KeyRing keys, ResignedSignatures resigned,
                          JobState state) {
        this(jdbc, game, rounds, integrity, keys, resigned, state, "", true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LegacyResigner(JdbcClient jdbc, GameRepository game, RoundSigner rounds, Integrity integrity, KeyRing keys, ResignedSignatures resigned,
                          JobState state, @org.springframework.beans.factory.annotation.Value("${plinko.keys.suspected-leak:${LEGACY_KEY_SUSPECTED_LEAK:}}")
                          String suspectedLeak, @org.springframework.beans.factory.annotation.Value("${plinko.keys.resign:${LEGACY_RESIGN:true}}") boolean enabled) {
        this.jdbc = jdbc;
        this.game = game;
        this.rounds = rounds;
        this.integrity = integrity;
        this.keys = keys;
        this.resigned = resigned;
        this.state = state;
        this.enabled = enabled;
        try {
            this.suspectedLeak = suspectedLeak == null || suspectedLeak.isBlank() ? null : java.time.Instant.parse(suspectedLeak.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("LEGACY_KEY_SUSPECTED_LEAK must be a UTC time like 2026-11-01T00:00:00Z, or empty");
        }
    }

    /** What is still signed only with the original key, per table, as the job last counted it, and when (R8); empty before its first count. */
    public record Left(java.util.Map<String, Long> tables, java.time.Instant countedAt, boolean done) {
    }

    public java.util.Optional<Left> left() {
        return state.get(LEFT).map(LegacyResigner::parse);
    }

    /** Records kept as evidence, never signed again (R7), per table and reason. */
    public java.util.Map<String, java.util.Map<String, Long>> evidence() {
        java.util.Map<String, java.util.Map<String, Long>> out = new java.util.LinkedHashMap<>();
        TABLES.forEach(table -> out.put(table, resigned.evidence(table)));
        return out;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void run() {
        if (!enabled || keys.legacyRemoved() || keys.legacyRetired() || integrity.signsWithLegacy("credit") || integrity.signsWithLegacy("odds-history")) {
            return;     // switched off, nothing to do, too late (after the cutoff nothing is signed again on the original key's word), or no new keys yet
        }
        if (state.get(DONE).isPresent()) {
            return;     // R7 #4: nothing was left at the end of the last sweep, and nothing new is ever signed with the original key
        }
        state.runAlone("legacy_resign", () -> {
            boolean[] ended = new boolean[1];
            int done = rounds(ended) + transactions(ended) + odds();
            if (done > 0) {
                log.info("Signed {} record(s) again with the current keys", done);
            }
            if (ended[0] || state.get(LEFT).isEmpty()) {
                count();
            }
        });
    }

    /** R8: counts what is left (whole tables: only here, at the end of a sweep) and keeps it with its time; none left: done. */
    void count() {
        java.util.Map<String, Long> left = new java.util.LinkedHashMap<>();
        TABLES.forEach(table -> left.put(table, resigned.left(table)));
        boolean none = left.values().stream().allMatch(n -> n == 0);
        state.put(LEFT, java.time.Instant.now() + "|" + none + "|" + left.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(",")));
        if (none) {
            state.put(DONE, java.time.Instant.now().toString());
            log.info("Nothing is signed only with the original key any more (evidence apart): the re-signing job stops; ROUND_SIGNING_KEY "
                    + "can be removed once LEGACY_KEY_UNTIL has passed");
        } else {
            log.info("Still signed only with the original key: {}", left);
        }
    }

    private static Left parse(String stored) {
        String[] parts = stored.split("\\|", 3);
        java.util.Map<String, Long> tables = new java.util.LinkedHashMap<>();
        for (String entry : parts[2].split(",")) {
            String[] kv = entry.split("=");
            tables.put(kv[0], Long.parseLong(kv[1]));
        }
        return new Left(tables, java.time.Instant.parse(parts[0]), Boolean.parseBoolean(parts[1]));
    }

    /** R7 #3: made at or after the suspected leak (may be forged with the leaked key). */
    private boolean afterLeak(java.time.Instant madeAt) {
        return suspectedLeak != null && (madeAt == null || !madeAt.isBefore(suspectedLeak));
    }

    /** Where a table's sweep got to; at the end it starts again from the beginning next run (R7 #2). */
    private String cursor(String name) {
        return state.get(name).orElse("");
    }

    private int rounds(boolean[] ended) {
        String after = cursor("legacy_resign.rounds");
        int done = 0;
        for (int batch = 0; batch < BATCHES_PER_RUN; batch++) {
            List<GameRepository.Round> page = game.decidedRoundsAfter(after, BATCH);
            for (GameRepository.Round round : page) {
                if (!KeyRing.isLegacy(round.signature()) || resigned.of(ResignedSignatures.ROUNDS, round.id()).isPresent()) {
                    continue;
                }
                if (afterLeak(round.createdAt())) {
                    resigned.evidence(ResignedSignatures.ROUNDS, round.id(), ResignedSignatures.AFTER_LEAK);
                } else if (rounds.verify(round)) {
                    resigned.add(ResignedSignatures.ROUNDS, round.id(), rounds.signAgain(round));
                    done++;
                } else {
                    resigned.evidence(ResignedSignatures.ROUNDS, round.id(), ResignedSignatures.FAILS_CHECK);
                }
            }
            if (page.size() < BATCH) {
                state.put("legacy_resign.rounds", "");     // swept to the end: from the start again next time
                ended[0] = true;
                break;
            }
            after = page.get(page.size() - 1).id();
            state.put("legacy_resign.rounds", after);
        }
        return done;
    }

    private int transactions(boolean[] ended) {
        String after = cursor("legacy_resign.transactions");
        int done = 0;
        for (int batch = 0; batch < BATCHES_PER_RUN; batch++) {
            List<Object[]> page = jdbc.sql("""
                    SELECT t.id, t.type, t.round_id, p.external_id, t.amount, r.currency, t.original_tx_id, t.signature, t.status, t.created_at
                    FROM wallet_transaction t JOIN game_round r ON r.id = t.round_id JOIN player p ON p.id = t.player_id
                    WHERE t.id > ? AND t.type IN ('DEBIT', 'CREDIT', 'ROLLBACK') ORDER BY t.id LIMIT ?""")
                    .params(after, BATCH)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10).toInstant()})
                    .list();
            for (Object[] tx : page) {
                String signature = (String) tx[7];
                // money still to move is never signed again; a later sweep finds it once it is confirmed (R7 #2)
                if (!KeyRing.isLegacy(signature) || !"OK".equals(tx[8]) || resigned.of(ResignedSignatures.TRANSACTIONS, (String) tx[0]).isPresent()) {
                    continue;
                }
                String purpose = ((String) tx[1]).toLowerCase();
                Object[] fields = "ROLLBACK".equals(tx[1]) ? new Object[] {tx[0], tx[2], tx[3], tx[6]} : new Object[] {tx[0], tx[2], tx[3], tx[4], tx[5]};
                if (afterLeak((java.time.Instant) tx[9])) {
                    resigned.evidence(ResignedSignatures.TRANSACTIONS, (String) tx[0], ResignedSignatures.AFTER_LEAK);
                } else if (integrity.verify(signature, purpose, fields)) {
                    resigned.add(ResignedSignatures.TRANSACTIONS, (String) tx[0], integrity.sign(purpose, fields));
                    done++;
                } else {
                    resigned.evidence(ResignedSignatures.TRANSACTIONS, (String) tx[0], ResignedSignatures.FAILS_CHECK);
                }
            }
            if (page.size() < BATCH) {
                state.put("legacy_resign.transactions", "");
                ended[0] = true;
                break;
            }
            after = (String) page.get(page.size() - 1)[0];
            state.put("legacy_resign.transactions", after);
        }
        return done;
    }

    private int odds() {
        int done = 0;
        for (Object[] version : jdbc.sql("SELECT id, rows_count, risk, multipliers, signature, created_at FROM odds_history ORDER BY id")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant()}).list()) {
            String signature = (String) version[4];
            String id = version[0].toString();
            if (!KeyRing.isLegacy(signature) || resigned.of(ResignedSignatures.ODDS, id).isPresent()) {
                continue;
            }
            if (afterLeak((java.time.Instant) version[5])) {
                resigned.evidence(ResignedSignatures.ODDS, id, ResignedSignatures.AFTER_LEAK);
            } else if (integrity.verify(signature, "odds-history", version[1], version[2], version[3])) {
                resigned.add(ResignedSignatures.ODDS, id, integrity.sign("odds-history", version[1], version[2], version[3]));
                done++;
            } else {
                resigned.evidence(ResignedSignatures.ODDS, id, ResignedSignatures.FAILS_CHECK);
            }
        }
        return done;
    }
}
