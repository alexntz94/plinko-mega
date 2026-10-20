package com.plinko.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Review 3.2: the dashboard and Finance read pre-added daily totals instead of adding up the whole round history on
 * every refresh. Totals per UTC day, currency and board (rows and risk) count the rounds that took a bet and were
 * decided (DEBITED, CREDIT_PENDING, COMPLETED); the players table gives the number of different players over any range
 * of days. Both are only a summary of game_round, never a source of money.
 * <p>
 * Review N11: the backfill runs in batches by id range (rounds read in id order, {@value #BATCH} at a time, each batch
 * added to the totals and committed on its own) under READ COMMITTED, so it reads live rounds without locking them
 * while the previous version is serving players. It can run again after a failure: it starts from empty tables.
 * <p>
 * Review R3: it runs as V15, recorded exactly as V15__daily_totals.sql (type SQL, that file's checksum; see
 * {@link SqlRecordedVersions}), so the previous release (v1.0.0, which ships the SQL file) accepts the database.
 */
// batched: rounds read by id, BATCH per batch, each batch committed on its own (scripts/check-migrations.sh)
public final class DailyTotalsBackfill {

    static final int BATCH = 5000;
    /** The checksum Flyway recorded for V15__daily_totals.sql (RepositoryRulesTest checks it). */
    public static final int SQL_CHECKSUM = -1521078428;

    private DailyTotalsBackfill() {
    }

    /** Each batch commits on its own (never inside a transaction): a lock is never held longer than one batch. */
    static void migrate(Connection connection, Map<String, String> placeholders) throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS round_daily_totals (
                        day        DATE          NOT NULL,
                        currency   CHAR(3)       NOT NULL,
                        rows_count INT           NOT NULL,
                        risk       INT           NOT NULL,
                        rounds     BIGINT        NOT NULL,
                        total_bet  DECIMAL(23,2) NOT NULL,
                        total_win  DECIMAL(23,2) NOT NULL,
                        PRIMARY KEY (day, currency, rows_count, risk),
                        INDEX idx_daily_totals_currency (currency, day)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS round_daily_players (
                        day       DATE    NOT NULL,
                        currency  CHAR(3) NOT NULL,
                        player_id BIGINT  NOT NULL,
                        PRIMARY KEY (day, currency, player_id),
                        INDEX idx_daily_players_currency (currency, day)
                    )""");
            // after a failed run: start again from nothing (the tables are this migration's own)
            s.execute("DELETE FROM round_daily_totals");
            s.execute("DELETE FROM round_daily_players");
        }
        connection.setAutoCommit(true);
        String after = "";
        while (true) {
            Map<String, Object[]> totals = new HashMap<>();
            Map<String, Object[]> players = new HashMap<>();
            String last = null;
            int read = 0;
            try (PreparedStatement batch = connection.prepareStatement("SELECT id, created_at, currency, rows_count, risk, player_id, total_bet, "
                    + "total_win, status FROM game_round WHERE id > ? ORDER BY id LIMIT " + BATCH)) {
                batch.setString(1, after);
                try (ResultSet rs = batch.executeQuery()) {
                    while (rs.next()) {
                        read++;
                        last = rs.getString("id");
                        if (!java.util.List.of("COMPLETED", "CREDIT_PENDING", "DEBITED").contains(rs.getString("status"))) {
                            continue;
                        }
                        Date day = Date.valueOf(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate());
                        String currency = rs.getString("currency");
                        int rows = rs.getInt("rows_count");
                        int risk = rs.getInt("risk");
                        BigDecimal bet = rs.getBigDecimal("total_bet");
                        BigDecimal win = rs.getBigDecimal("total_win") == null ? BigDecimal.ZERO : rs.getBigDecimal("total_win");
                        Object[] sum = totals.computeIfAbsent(day + "|" + currency + "|" + rows + "|" + risk,
                                k -> new Object[] {day, currency, rows, risk, 0L, BigDecimal.ZERO, BigDecimal.ZERO});
                        sum[4] = (long) sum[4] + 1;
                        sum[5] = ((BigDecimal) sum[5]).add(bet);
                        sum[6] = ((BigDecimal) sum[6]).add(win);
                        long player = rs.getLong("player_id");
                        players.putIfAbsent(day + "|" + currency + "|" + player, new Object[] {day, currency, player});
                    }
                }
            }
            add(connection, totals, players);
            if (read < BATCH) {
                break;
            }
            after = last;
        }
        try (Statement s = connection.createStatement()) {
            s.execute("GRANT SELECT, INSERT, DELETE ON `" + placeholders.get("dbName") + "`.round_daily_totals TO '" + placeholders.get("appUser") + "'@'%'");
            s.execute("GRANT SELECT, INSERT, DELETE ON `" + placeholders.get("dbName") + "`.round_daily_players TO '" + placeholders.get("appUser") + "'@'%'");
        }
    }

    /** One batch of rounds added to the totals (its own commit). */
    private static void add(Connection connection, Map<String, Object[]> totals, Map<String, Object[]> players) throws Exception {
        try (PreparedStatement sum = connection.prepareStatement("INSERT INTO round_daily_totals (day, currency, rows_count, risk, rounds, total_bet, "
                + "total_win) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE rounds = rounds + VALUES(rounds), "
                + "total_bet = total_bet + VALUES(total_bet), total_win = total_win + VALUES(total_win)");
             PreparedStatement player = connection.prepareStatement("INSERT IGNORE INTO round_daily_players (day, currency, player_id) VALUES (?, ?, ?)")) {
            for (Object[] row : totals.values()) {
                for (int i = 0; i < row.length; i++) {
                    sum.setObject(i + 1, row[i]);
                }
                sum.addBatch();
            }
            for (Object[] row : players.values()) {
                for (int i = 0; i < row.length; i++) {
                    player.setObject(i + 1, row[i]);
                }
                player.addBatch();
            }
            sum.executeBatch();
            player.executeBatch();
        }
    }
}
