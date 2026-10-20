package com.plinko.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Review 3.4: each player points at their current bonus-meter round, so a round reads it by its id instead of
 * searching the player's history with LIKE '%meter:%'. The chain's safety is unchanged: only one round can ever
 * continue from a meter state (uk_game_round_meter_prev), so a pointer edited in the database can't pay a pot twice.
 * Review 3.5: indexes for the admin lists, Finance and the background jobs.
 * <p>
 * Review N11: the pointer is backfilled in batches by player id range ({@value #BATCH} players per statement, each
 * committed on its own) under READ COMMITTED, so a batch holds only its own players' rows, briefly, while the previous
 * version is serving players. It can run again after a failure.
 * <p>
 * Review R3: it runs as V16, recorded exactly as V16__meter_pointer_and_indexes.sql (type SQL, that file's checksum;
 * see {@link SqlRecordedVersions}), so the previous release (v1.0.0, which ships the SQL file) accepts the database.
 */
// batched: players by id range, BATCH per statement, each committed on its own (scripts/check-migrations.sh)
public final class MeterPointerBackfill {

    static final int BATCH = 1000;
    /** The checksum Flyway recorded for V16__meter_pointer_and_indexes.sql (RepositoryRulesTest checks it). */
    public static final int SQL_CHECKSUM = -366358722;

    private static final String[] INDEXES = {
        "idx_round_status_created ON game_round (status, created_at)",
        "idx_round_status_updated ON game_round (status, updated_at)",
        "idx_tx_created ON wallet_transaction (created_at)",
        "idx_tx_status_created ON wallet_transaction (status, created_at)",
        "idx_tx_player_created ON wallet_transaction (player_id, created_at)",
        "idx_player_last_seen ON player (last_seen_at)",
        "idx_audit_action ON audit_log (action, id)",
        "idx_audit_username ON audit_log (username, id)",
        "idx_session_expires ON game_session (expires_at)",
        "idx_token_used ON used_launch_token (used_at)",
    };

    private MeterPointerBackfill() {
    }

    /** Each batch commits on its own (never inside a transaction): a lock is never held longer than one batch. */
    static void migrate(Connection connection, Map<String, String> placeholders) throws Exception {
        connection.setAutoCommit(true);
        try (Statement s = connection.createStatement()) {
            s.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
            if (!exists(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                    + "AND table_name = 'player' AND column_name = 'meter_round'")) {
                s.execute("ALTER TABLE player ADD COLUMN meter_round VARCHAR(64) NULL");
            }
        }
        long last = 0;
        long max;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM player")) {
            rs.next();
            max = rs.getLong(1);
        }
        try (PreparedStatement batch = connection.prepareStatement("""
                UPDATE player p SET p.meter_round = (
                    SELECT r.id FROM game_round r
                    WHERE r.player_id = p.id AND r.bonus LIKE '%meter:%'
                      AND NOT EXISTS (SELECT 1 FROM game_round n WHERE n.meter_prev = r.id)
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1)
                WHERE p.id > ? AND p.id <= ?""")) {
            while (last < max) {
                batch.setLong(1, last);
                batch.setLong(2, last + BATCH);
                batch.executeUpdate();
                last += BATCH;
            }
        }
        for (String index : INDEXES) {
            String name = index.substring(0, index.indexOf(' '));
            String table = index.substring(index.indexOf(" ON ") + 4, index.indexOf(' ', index.indexOf(" ON ") + 4));
            if (!exists(connection, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = '"
                    + table + "' AND index_name = '" + name + "'")) {
                try (Statement s = connection.createStatement()) {
                    s.execute("CREATE INDEX " + index);
                }
            }
        }
    }

    private static boolean exists(Connection connection, String countQuery) throws Exception {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(countQuery)) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }
}
