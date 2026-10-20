package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Review N8 #2: the new signatures of frozen records (rounds, wallet transactions, odds versions) first signed with the
 * original key, made with the current keys by the re-signing job; the checks accept them, so the original key can go.
 */
@Repository
public class ResignedSignatures {

    public static final String ROUNDS = "game_round";
    public static final String TRANSACTIONS = "wallet_transaction";
    public static final String ODDS = "odds_history";

    private final JdbcClient jdbc;

    public ResignedSignatures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> of(String table, String recordId) {
        return jdbc.sql("SELECT signature FROM record_signature WHERE table_name = ? AND record_id = ?").params(table, recordId)
                .query(String.class).optional();
    }

    /** Keeps a record's new signature (once: a second one for the same record is ignored). */
    public void add(String table, String recordId, String signature) {
        jdbc.sql("INSERT IGNORE INTO record_signature (table_name, record_id, signature, created_at) VALUES (?, ?, ?, ?)")
                .params(table, recordId, signature, Timestamp.from(Instant.now())).update();
    }

    /** R7: a record that will never be signed again, kept as evidence (once), with why. */
    public void evidence(String table, String recordId, String reason) {
        jdbc.sql("INSERT IGNORE INTO legacy_evidence (table_name, record_id, reason, created_at) VALUES (?, ?, ?, ?)")
                .params(table, recordId, reason, Timestamp.from(Instant.now())).update();
    }

    public static final String FAILS_CHECK = "fails its check";
    public static final String AFTER_LEAK = "made after the suspected leak";

    /**
     * How many records of {@code table} are signed with the original key, not yet signed again, and not kept as evidence
     * (R7: they would verify, so they can still be signed again). Reads the whole table: run by the re-signing job and at
     * start-up only, never on a page view (R8).
     */
    public long left(String table) {
        String id = ODDS.equals(table) ? "CAST(x.id AS CHAR)" : "x.id";
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " x WHERE x.signature IS NOT NULL AND SUBSTRING(x.signature, 3, 1) <> '.' "
                        + "AND NOT EXISTS (SELECT 1 FROM record_signature s WHERE s.table_name = ? AND s.record_id = " + id + ") "
                        + "AND NOT EXISTS (SELECT 1 FROM legacy_evidence e WHERE e.table_name = ? AND e.record_id = " + id + ")")
                .params(table, table).query(Long.class).single();
    }

    /** How many records of {@code table} are kept as evidence, per reason. */
    public java.util.Map<String, Long> evidence(String table) {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        jdbc.sql("SELECT reason, COUNT(*) FROM legacy_evidence WHERE table_name = ? GROUP BY reason").param(table)
                .query((rs, n) -> out.put(rs.getString(1), rs.getLong(2))).list();
        return out;
    }
}
