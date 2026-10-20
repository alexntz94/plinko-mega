package com.plinko.repo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StatsRepository {

    /** Totals over rounds whose bet was taken and outcome decided. */
    public record Totals(String label, long rounds, long players, BigDecimal bet, BigDecimal win) {
        public BigDecimal ggr() {
            return bet.subtract(win);
        }

        /** Actual return to player in percent, null without bets. */
        public BigDecimal rtp() {
            return bet.signum() == 0 ? null : win.multiply(BigDecimal.valueOf(100)).divide(bet, 2, RoundingMode.HALF_UP);
        }
    }

    public record CurrencyTotals(String currency, List<Totals> periods) {
    }

    private final JdbcClient jdbc;

    private final com.plinko.common.ReportingZone zone;

    public StatsRepository(JdbcClient jdbc, com.plinko.common.ReportingZone zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    /** Today in the reporting time zone (review 7.9). */
    public java.time.LocalDate today() {
        return zone.today();
    }

    public com.plinko.common.ReportingZone zone() {
        return zone;
    }

    /** The day of the first round, in the reporting time zone (none yet: empty). */
    public java.util.Optional<java.time.LocalDate> firstRoundDay() {
        return jdbc.sql("SELECT MIN(created_at) FROM game_round").query((rs, n) -> rs.getTimestamp(1)).optional()
                .filter(java.util.Objects::nonNull).map(t -> zone.dayOf(t.toInstant()));
    }

    /** Empties the daily totals, before they are rebuilt for another time zone. */
    public void clearTotals() {
        jdbc.sql("DELETE FROM round_daily_totals").update();
        jdbc.sql("DELETE FROM round_daily_players").update();
    }

    /** The time zone the daily totals were last built in (null: before review 7.9, i.e. UTC). */
    public String totalsZone() {
        return jdbc.sql("SELECT value FROM daily_totals_meta WHERE name = 'zone'").query(String.class).optional().orElse(null);
    }

    public void setTotalsZone(String name) {
        jdbc.sql("INSERT INTO daily_totals_meta (name, value) VALUES ('zone', ?) ON DUPLICATE KEY UPDATE value = VALUES(value)").param(name).update();
    }

    /** The currencies rounds were played in (from the daily totals: a small table, not the round history). */
    public List<String> currencies() {
        return jdbc.sql("SELECT DISTINCT currency FROM round_daily_totals ORDER BY currency").query(String.class).list();
    }

    /** Rounds counted when the rounds are read directly (a bet taken and an outcome decided). */
    private static final String COUNTED = "status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED')";

    /**
     * Review R2: the rounds a rebuild counts, those whose +1 increment is already written (in the same transaction as the
     * status change). A DEBITED round is between its transactions (b) and (c): its increment comes with (c), so it is
     * counted by that increment, never by a rebuild as well.
     */
    static final String REBUILT = "status IN ('COMPLETED', 'CREDIT_PENDING')";

    /**
     * Rebuilds one day (in the reporting time zone) of the daily totals from the rounds of that day (read through the created_at index), for
     * a closed day or a repair. Run in a REPEATABLE READ transaction: the day's unadded increments and its rounds are read in one snapshot
     * with plain SELECTs (consistent reads that take no locks; never INSERT ... SELECT, which would lock every round of the day, N4), and
     * exactly those increments are removed. Only rounds whose increment is already written are counted (R2): every such increment is
     * either in the snapshot (and removed) or written later only by a round that the snapshot did not count, so each round is counted
     * once, by the rebuild or by its increment. Only the small summary tables are written.
     */
    public void rebuildDay(java.time.LocalDate day) {
        Timestamp from = Timestamp.from(zone.start(day));
        Timestamp to = Timestamp.from(zone.end(day));
        java.sql.Date date = java.sql.Date.valueOf(day);
        List<Long> included = jdbc.sql("SELECT id FROM round_daily_delta WHERE day = ?").param(date).query(Long.class).list();
        List<Object[]> totals = jdbc.sql("SELECT currency, rows_count, risk, COUNT(*), SUM(total_bet), COALESCE(SUM(total_win), 0) FROM game_round "
                        + "WHERE created_at >= ? AND created_at < ? AND " + REBUILT + " GROUP BY currency, rows_count, risk")
                .params(from, to)
                .query((rs, n) -> new Object[] {date, rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getBigDecimal(5), rs.getBigDecimal(6)})
                .list();
        List<Object[]> players = jdbc.sql("SELECT currency, player_id, COUNT(*), SUM(total_bet), COALESCE(SUM(total_win), 0) FROM game_round "
                        + "WHERE created_at >= ? AND created_at < ? AND " + REBUILT + " GROUP BY currency, player_id")
                .params(from, to)
                .query((rs, n) -> new Object[] {date, rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4), rs.getBigDecimal(5)})
                .list();
        jdbc.sql("DELETE FROM round_daily_totals WHERE day = ?").param(date).update();
        insertRows("INSERT INTO round_daily_totals (day, currency, rows_count, risk, rounds, total_bet, total_win) VALUES ", "(?, ?, ?, ?, ?, ?, ?)", totals);
        jdbc.sql("DELETE FROM round_daily_players WHERE day = ?").param(date).update();
        insertRows("INSERT INTO round_daily_players (day, currency, player_id, rounds, total_bet, total_win) VALUES ", "(?, ?, ?, ?, ?, ?)", players);
        deleteDeltas(included);
    }

    /**
     * N4 #2: one round's increment to its day's totals, written in the round's own last transaction (+1), or when a counted round
     * is blocked later (-1). Insert-only: live rounds never wait on each other's totals.
     */
    public void addDelta(GameRepository.Round round, int sign) {
        BigDecimal factor = BigDecimal.valueOf(sign);
        jdbc.sql("INSERT INTO round_daily_delta (day, currency, rows_count, risk, player_id, rounds, total_bet, total_win) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(java.sql.Date.valueOf(zone.dayOf(round.createdAt())), round.currency(), round.rows(), round.risk(), round.playerId(), sign,
                        round.totalBet().multiply(factor), (round.totalWin() == null ? BigDecimal.ZERO : round.totalWin()).multiply(factor))
                .update();
    }

    /**
     * N4 #2: adds up to {@code limit} increments to the daily totals and removes exactly those (run in one transaction). Returns
     * how many were added.
     */
    public int foldDeltas(int limit) {
        List<Object[]> rows = jdbc.sql("SELECT id, day, currency, rows_count, risk, player_id, rounds, total_bet, total_win FROM round_daily_delta "
                        + "ORDER BY id LIMIT ?")
                .param(limit)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getDate(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getLong(6), rs.getInt(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9)})
                .list();
        java.util.Map<String, Object[]> totals = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object[]> players = new java.util.LinkedHashMap<>();
        for (Object[] r : rows) {
            add(totals.computeIfAbsent(r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4], k -> new Object[] {r[1], r[2], r[3], r[4], 0L, BigDecimal.ZERO,
                    BigDecimal.ZERO}), 4, r);
            add(players.computeIfAbsent(r[1] + "|" + r[2] + "|" + r[5], k -> new Object[] {r[1], r[2], r[5], 0L, BigDecimal.ZERO, BigDecimal.ZERO}), 3, r);
        }
        for (Object[] t : totals.values()) {
            jdbc.sql("INSERT INTO round_daily_totals (day, currency, rows_count, risk, rounds, total_bet, total_win) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE rounds = rounds + VALUES(rounds), total_bet = total_bet + VALUES(total_bet), "
                    + "total_win = total_win + VALUES(total_win)").params(java.util.Arrays.asList(t)).update();
        }
        for (Object[] p : players.values()) {
            jdbc.sql("INSERT INTO round_daily_players (day, currency, player_id, rounds, total_bet, total_win) VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE rounds = rounds + VALUES(rounds), total_bet = total_bet + VALUES(total_bet), "
                    + "total_win = total_win + VALUES(total_win)").params(java.util.Arrays.asList(p)).update();
        }
        deleteDeltas(rows.stream().map(r -> (Long) r[0]).toList());
        return rows.size();
    }

    /** Adds one increment's rounds, bet and win into a sum whose counts start at {@code at}. */
    private static void add(Object[] sum, int at, Object[] delta) {
        sum[at] = (long) sum[at] + (int) delta[6];
        sum[at + 1] = ((BigDecimal) sum[at + 1]).add((BigDecimal) delta[7]);
        sum[at + 2] = ((BigDecimal) sum[at + 2]).add((BigDecimal) delta[8]);
    }

    private void deleteDeltas(List<Long> ids) {
        for (int from = 0; from < ids.size(); from += 1000) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 1000));
            jdbc.sql("DELETE FROM round_daily_delta WHERE id IN (" + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ")")
                    .params(chunk.stream().map(Object.class::cast).toList()).update();
        }
    }

    /** Inserts rows a thousand at a time, one multi-row INSERT each. */
    private void insertRows(String insert, String row, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += 1000) {
            List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + 1000));
            jdbc.sql(insert + String.join(", ", java.util.Collections.nCopies(chunk.size(), row)))
                    .params(chunk.stream().flatMap(java.util.Arrays::stream).toList())
                    .update();
        }
    }

    /** Marks every day from {@code first} to {@code last} as changed, for the minute job to rebuild (a new column to fill). */
    public void markDaysChanged(java.time.LocalDate first, java.time.LocalDate last) {
        for (java.time.LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            markDayChanged(day);
        }
    }

    /** Marks a day whose totals changed after the fact (a round blocked days later): the minute job rebuilds it (N5). */
    public void markDayChanged(java.time.LocalDate day) {
        jdbc.sql("INSERT INTO daily_totals_dirty (day, marked_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE marked_at = VALUES(marked_at)")
                .params(java.sql.Date.valueOf(day), Timestamp.from(Instant.now()))
                .update();
    }

    /** Days marked as changed, oldest mark first, with when they were marked. */
    public List<java.util.Map.Entry<java.time.LocalDate, Instant>> changedDays(int limit) {
        return jdbc.sql("SELECT day, marked_at FROM daily_totals_dirty ORDER BY marked_at LIMIT ?")
                .param(limit)
                .query((rs, n) -> java.util.Map.entry(rs.getDate(1).toLocalDate(), rs.getTimestamp(2).toInstant()))
                .list();
    }

    /** Clears a day's mark, unless it was marked again since {@code markedAt} (that newer change is rebuilt next time). */
    public void clearDayChanged(java.time.LocalDate day, Instant markedAt) {
        jdbc.sql("DELETE FROM daily_totals_dirty WHERE day = ? AND marked_at <= ?")
                .params(java.sql.Date.valueOf(day), Timestamp.from(markedAt))
                .update();
    }

    /** Totals of whole days (reporting time zone) from {@code fromDay} (null: all of them) to today, from the daily totals. */
    public Totals totalsSince(String label, String currency, java.time.LocalDate fromDay) {
        java.sql.Date from = java.sql.Date.valueOf(fromDay == null ? java.time.LocalDate.of(1970, 1, 1) : fromDay);
        long players = jdbc.sql("SELECT COUNT(DISTINCT player_id) FROM round_daily_players WHERE currency = ? AND day >= ?")
                .params(currency, from).query(Long.class).single();
        return jdbc.sql("SELECT COALESCE(SUM(rounds), 0) AS rounds, COALESCE(SUM(total_bet), 0) AS bet, COALESCE(SUM(total_win), 0) AS win "
                        + "FROM round_daily_totals WHERE currency = ? AND day >= ?")
                .params(currency, from)
                .query((rs, n) -> new Totals(label, rs.getLong("rounds"), players, rs.getBigDecimal("bet"), rs.getBigDecimal("win")))
                .single();
    }

    public Totals totals(String label, String currency, Duration period) {
        Instant from = period == null ? Instant.EPOCH : Instant.now().minus(period);
        return jdbc.sql("""
                SELECT COUNT(*) AS rounds, COUNT(DISTINCT player_id) AS players,
                       COALESCE(SUM(total_bet), 0) AS bet, COALESCE(SUM(total_win), 0) AS win
                FROM game_round
                WHERE currency = ? AND created_at >= ? AND status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED')""")
                .params(currency, Timestamp.from(from))
                .query((rs, n) -> new Totals(label, rs.getLong("rounds"), rs.getLong("players"), rs.getBigDecimal("bet"), rs.getBigDecimal("win")))
                .single();
    }

    /** When the oldest round still owed a credit or a refund was played (empty when none is). */
    public java.util.Optional<Instant> oldestPending() {
        Timestamp at = jdbc.sql("SELECT MIN(created_at) FROM game_round WHERE status IN ('CREDIT_PENDING', 'ROLLBACK_PENDING')")
                .query(Timestamp.class).optional().orElse(null);
        return java.util.Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    /** Rounds, bets and wins of the rounds played since {@code from} (a range on the created_at index). */
    public Totals since(Instant from) {
        return jdbc.sql("SELECT COUNT(*) AS rounds, COALESCE(SUM(total_bet), 0) AS bet, COALESCE(SUM(total_win), 0) AS win FROM game_round "
                        + "WHERE created_at >= ? AND " + COUNTED)
                .param(Timestamp.from(from))
                .query((rs, n) -> new Totals("since", rs.getLong("rounds"), 0, rs.getBigDecimal("bet"), rs.getBigDecimal("win")))
                .single();
    }

    /** Rounds, bets and wins of one UTC day, from the daily totals (every currency together: the platform plays in one). */
    public Totals day(java.time.LocalDate day) {
        return jdbc.sql("SELECT COALESCE(SUM(rounds), 0) AS rounds, COALESCE(SUM(total_bet), 0) AS bet, COALESCE(SUM(total_win), 0) AS win "
                        + "FROM round_daily_totals WHERE day = ?")
                .param(java.sql.Date.valueOf(day))
                .query((rs, n) -> new Totals("day", rs.getLong("rounds"), 0, rs.getBigDecimal("bet"), rs.getBigDecimal("win")))
                .single();
    }

    public long countByStatus(List<String> statuses) {
        return jdbc.sql("SELECT COUNT(*) FROM game_round WHERE status IN (:statuses)").param("statuses", statuses).query(Long.class).single();
    }
}
