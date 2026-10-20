package com.plinko.repo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Money figures for the Finance page. A round counts once its bet was taken and its outcome decided
 * (COMPLETED, or DEBITED / CREDIT_PENDING whose win is still owed to the player).
 */
@Repository
public class FinanceRepository {

    static final String COUNTED = "status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED')";

    /** One line of figures: a day, a risk level, a rows count or a player. */
    public record Line(String label, String currency, long rounds, long players, BigDecimal bet, BigDecimal win) {
        public BigDecimal ggr() {
            return bet.subtract(win);
        }

        public BigDecimal rtp() {
            return bet.signum() == 0 ? null : win.multiply(BigDecimal.valueOf(100)).divide(bet, 2, RoundingMode.HALF_UP);
        }

        /** Share of the bets the platform kept, in percent. */
        public BigDecimal margin() {
            return bet.signum() == 0 ? null : ggr().multiply(BigDecimal.valueOf(100)).divide(bet, 2, RoundingMode.HALF_UP);
        }
    }

    private final JdbcClient jdbc;

    private final com.plinko.common.ReportingZone zone;

    public FinanceRepository(JdbcClient jdbc, com.plinko.common.ReportingZone zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    /*
     * Review 3.3: every figure, today's included, adds up the daily totals (round_daily_totals): past days are rebuilt
     * once closed, and today is kept up to date by each round's increment (folded in every minute, N4 #2), so the page
     * never reads the rounds themselves. Players are counted the same way, from round_daily_players.
     */

    public LocalDate today() {
        return zone.today();
    }

    public LocalDate zoneDay(java.time.Instant time) {
        return zone.dayOf(time);
    }

    public String zoneName() {
        return zone.name();
    }

    public List<Line> totals(LocalDate from, LocalDate to) {
        return withPlayers(summary("'Total'", "'Total'", from, to), from, to, false);
    }

    public List<Line> daily(LocalDate from, LocalDate to) {
        return withPlayers(summary("DATE_FORMAT(day, '%Y-%m-%d')", "day", from, to), from, to, true);
    }

    public List<Line> byRisk(LocalDate from, LocalDate to) {
        return summary("ELT(risk + 1, 'Low', 'Mid', 'High')", "risk", from, to);
    }

    public List<Line> byRows(LocalDate from, LocalDate to) {
        return summary("CONCAT(rows_count, ' rows')", "rows_count", from, to);
    }

    /**
     * One line per label and currency from the daily totals, today included (its increments folded in every minute, so
     * today is at most a minute behind): Finance never reads the rounds themselves (review 3.3; under load a day of rounds read on every view
     * held up play). Sorted by currency, then key.
     */
    private List<Line> summary(String label, String key, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT %s AS label, currency, SUM(rounds) AS rounds, 0 AS players, SUM(total_bet) AS bet, SUM(total_win) AS win
                FROM round_daily_totals WHERE day >= ? AND day <= ?
                GROUP BY currency, %s, label ORDER BY currency, %s""".formatted(label, key, key))
                .params(java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))
                .query(this::line).list();
    }

    /**
     * The lines with their number of different players (over the whole range, or per day), from the daily player totals:
     * the range of days read by the primary key (day first), not the whole currency index.
     */
    private List<Line> withPlayers(List<Line> lines, LocalDate from, LocalDate to, boolean perDay) {
        java.util.Map<String, Long> players = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT %s AS label, currency, COUNT(DISTINCT player_id) AS players FROM round_daily_players FORCE INDEX (PRIMARY)
                WHERE day >= ? AND day <= ? GROUP BY label, currency""".formatted(perDay ? "DATE_FORMAT(day, '%Y-%m-%d')" : "'Total'"))
                .params(java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))
                .query(rs -> {
                    players.put(rs.getString("label") + "|" + rs.getString("currency"), rs.getLong("players"));
                });
        return lines.stream().map(l -> new Line(l.label(), l.currency(), l.rounds(), players.getOrDefault(l.label() + "|" + l.currency(), 0L),
                l.bet(), l.win())).toList();
    }

    /**
     * Players by net result for the platform; {@code biggestWinners} = players who took the most from the platform. From
     * each player's daily totals, today included (review 3.3).
     */
    public List<Line> players(LocalDate from, LocalDate to, boolean biggestWinners, int limit) {
        return jdbc.sql("""
                SELECT CONCAT(p.external_id, IFNULL(CONCAT(' (', p.username, ')'), '')) AS label, x.currency,
                       SUM(x.rounds) AS rounds, 1 AS players, SUM(x.total_bet) AS bet, SUM(x.total_win) AS win
                FROM round_daily_players x JOIN player p ON p.id = x.player_id
                WHERE x.day >= ? AND x.day <= ?
                GROUP BY x.player_id, p.external_id, p.username, x.currency
                ORDER BY SUM(x.total_win) - SUM(x.total_bet) %s
                LIMIT ?""".formatted(biggestWinners ? "DESC" : "ASC"))
                .params(java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), limit)
                .query(this::line).list();
    }

    /** A finished round checked against its wallet transactions: when it was played, when it finished, and whether they differ. */
    public record Reconciled(String id, LocalDate day, java.time.Instant finished, boolean differs) {
    }

    /**
     * The next completed rounds after (finishedAfter, afterId), in the order they finished, each with whether its
     * confirmed wallet debit or credit differs from its bet or win (review 3.3: checked once each, in the background).
     */
    public List<Reconciled> reconcileAfter(java.time.Instant finishedAfter, String afterId, java.time.Instant finishedBefore, int limit) {
        Timestamp after = Timestamp.from(finishedAfter);
        return jdbc.sql("""
                SELECT r.id, r.created_at, r.updated_at,
                  (r.total_bet <> (SELECT IFNULL(SUM(t.amount), 0) FROM wallet_transaction t WHERE t.round_id = r.id AND t.type = 'DEBIT' AND t.status = 'OK')
                   OR r.total_win <> (SELECT IFNULL(SUM(t.amount), 0) FROM wallet_transaction t WHERE t.round_id = r.id AND t.type = 'CREDIT' AND t.status = 'OK')) AS differs
                FROM game_round r
                WHERE r.status = 'COMPLETED' AND (r.updated_at > ? OR (r.updated_at = ? AND r.id > ?)) AND r.updated_at < ?
                ORDER BY r.updated_at, r.id LIMIT ?""")
                .params(after, after, afterId, Timestamp.from(finishedBefore), limit)
                .query((rs, n) -> new Reconciled(rs.getString("id"), zone.dayOf(rs.getTimestamp("created_at").toInstant()), rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("differs")))
                .list();
    }

    /** Adds a batch of the wallet check's results to its counts for the day the rounds were played. */
    public void addWalletCheck(LocalDate day, long checked, long differing) {
        jdbc.sql("INSERT INTO wallet_check_day (day, checked, differing) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE checked = checked + VALUES(checked), differing = differing + VALUES(differing)")
                .params(java.sql.Date.valueOf(day), checked, differing)
                .update();
    }

    /** Rounds played between the two days (inclusive) the wallet check found differing from their wallet transactions. */
    public long walletCheckDiffering(LocalDate first, LocalDate last) {
        return jdbc.sql("SELECT COALESCE(SUM(differing), 0) FROM wallet_check_day WHERE day >= ? AND day <= ?")
                .params(java.sql.Date.valueOf(first), java.sql.Date.valueOf(last))
                .query(Long.class).single();
    }

    /** Rounds whose win is still owed (credit failing) or whose bet is being refunded. */
    public List<Line> open(LocalDate from, LocalDate to) {
        // each status read by its (status, created_at) index; only the few open rows are grouped (3.5)
        String branch = "SELECT status, currency, player_id, total_bet, total_win FROM game_round WHERE status = ? AND created_at >= ? AND created_at < ?";
        return jdbc.sql("""
                SELECT status AS label, currency, COUNT(*) AS rounds, COUNT(DISTINCT player_id) AS players,
                       SUM(total_bet) AS bet, IFNULL(SUM(total_win), 0) AS win
                FROM (%s UNION ALL %s UNION ALL %s) x
                GROUP BY status, currency""".formatted(branch, branch, branch))
                .params("CREDIT_PENDING", start(from), end(to), "ROLLBACK_PENDING", start(from), end(to), "TAMPERED", start(from), end(to))
                .query(this::line).list();
    }

    private Line line(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Line(rs.getString("label"), rs.getString("currency"), rs.getLong("rounds"), rs.getLong("players"),
                rs.getBigDecimal("bet"), rs.getBigDecimal("win"));
    }

    private Timestamp start(LocalDate day) {
        return Timestamp.from(zone.start(day));
    }

    /** Exclusive end: the start of the day after {@code day}. */
    private Timestamp end(LocalDate day) {
        return Timestamp.from(zone.end(day));
    }
}
