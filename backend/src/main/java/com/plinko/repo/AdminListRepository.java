package com.plinko.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.plinko.common.ListQuery.Where;
import com.plinko.common.ListQuery;
import com.plinko.model.AuditEntry;
import com.plinko.model.ChangeRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Filtered, sorted, paged lists for the admin panel (players, rounds, wallet transactions, audit log).
 * Filters are bind parameters; sort keys map to whitelisted columns only.
 */
@Repository
public class AdminListRepository {

    /** Rows exported to CSV at most, so one click can't make the server read the whole history. */
    public static final int EXPORT_LIMIT = 50_000;

    public static final Map<String, String> PLAYER_SORTS = Map.of("lastSeen", "p.last_seen_at", "firstSeen", "p.created_at",
            "balance", "p.last_balance", "username", "p.username", "player", "p.external_id");
    public static final Map<String, String> ROUND_SORTS = Map.of("time", "r.created_at", "bet", "r.total_bet", "win", "r.total_win",
            "balls", "r.balls", "rows", "r.rows_count", "player", "p.external_id");
    public static final Map<String, String> TX_SORTS = Map.of("time", "t.created_at", "amount", "t.amount", "type", "t.type",
            "status", "t.status");
    public static final Map<String, String> AUDIT_SORTS = Map.of("time", "a.id");
    public static final Map<String, String> CHANGE_SORTS = Map.of("decided", "c.decided_at", "id", "c.id", "proposed", "c.requested_at");

    public record PlayerFilter(String q, String status, String currency, boolean activeNow) {
    }

    public record RoundFilter(String q, String status, LocalDate from, LocalDate to, Integer rows, Integer risk, String currency,
                              Long playerId) {
    }

    public record TransactionFilter(String q, String type, String status, LocalDate from, LocalDate to) {
    }

    public record AuditFilter(String q, String username, String action, LocalDate from, LocalDate to) {
    }

    public record ChangeFilter(String q, String type, String status, String proposer, String decider, LocalDate from, LocalDate to) {
    }

    /**
     * A round as the admin lists show it (review 3.10d): only the columns they display, never the long balls,
     * multipliers, bonus or error text. hasOutcome: the round has, or claims, a decided outcome (signed or with a win),
     * so the signature scan checks it. The round's own page reads and verifies the whole round.
     */
    public record RoundSummary(String id, long playerId, int rows, int risk, java.math.BigDecimal bet, int balls,
                               java.math.BigDecimal totalBet, java.math.BigDecimal totalWin, String currency, String status,
                               boolean hasOutcome, Instant createdAt) {
    }

    public record RoundRow(RoundSummary round, String playerExternalId, String playerName) {
    }

    /** The columns of a {@link RoundSummary}, of game_round as {@code r}. */
    private static final String ROUND_LIST_COLUMNS = "r.id, r.player_id, r.rows_count, r.risk, r.bet, r.balls, r.total_bet, r.total_win, "
            + "r.currency, r.status, r.created_at, (r.signature IS NOT NULL OR r.total_win IS NOT NULL) AS has_outcome";

    public record TransactionRow(GameRepository.Transaction tx, String playerExternalId, String playerName) {
    }

    private final JdbcClient jdbc;

    public AdminListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // players

    public ListQuery.Page<GameRepository.Player> players(PlayerFilter f, ListQuery query) {
        Where where = playerWhere(f);
        long total = count("SELECT COUNT(*) FROM player p" + where.sql(), where);
        return new ListQuery.Page<>(players(where, query, query.size(), query.offset()), total, query);
    }

    public List<GameRepository.Player> playersForExport(PlayerFilter f, ListQuery query) {
        return players(playerWhere(f), query, EXPORT_LIMIT, 0);
    }

    private List<GameRepository.Player> players(Where where, ListQuery query, int limit, int offset) {
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT p.* FROM player p" + where.sql() + query.orderBy("p.id") + " LIMIT ? OFFSET ?")
                .params(params).query(this::player).list();
    }

    private static Where playerWhere(PlayerFilter f) {
        return new Where()
                .add(Where.has(f.q()), "(p.external_id LIKE ? OR p.username LIKE ?)", Where.prefix(nz(f.q())), Where.like(nz(f.q())))
                .add("blocked".equals(f.status()), "p.blocked = TRUE")
                .add("allowed".equals(f.status()), "p.blocked = FALSE")
                .add(Where.has(f.currency()), "p.currency = ?", nz(f.currency()).toUpperCase())
                .add(f.activeNow(), "p.last_seen_at >= ?", Timestamp.from(Instant.now().minusSeconds(120)));
    }

    public List<String> playerCurrencies() {
        return jdbc.sql("SELECT DISTINCT currency FROM player ORDER BY currency").query(String.class).list();
    }

    // rounds

    public ListQuery.Page<RoundRow> rounds(RoundFilter f, ListQuery query) {
        Where where = roundWhere(f);
        long total = count("SELECT COUNT(*) FROM game_round r JOIN player p ON p.id = r.player_id" + where.sql(), where);
        return new ListQuery.Page<>(rounds(where, query, query.size(), query.offset()), total, query);
    }

    public List<RoundRow> roundsForExport(RoundFilter f, ListQuery query) {
        return rounds(roundWhere(f), query, EXPORT_LIMIT, 0);
    }

    private List<RoundRow> rounds(Where where, ListQuery query, int limit, int offset) {
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT " + ROUND_LIST_COLUMNS + ", p.external_id AS p_external_id, p.username AS p_username FROM game_round r "
                        + "JOIN player p ON p.id = r.player_id" + where.sql() + query.orderBy("r.id") + " LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new RoundRow(roundSummary(rs), rs.getString("p_external_id"), rs.getString("p_username")))
                .list();
    }

    /** The newest rounds, of one player or of everyone (the dashboard and the player's page). */
    public List<RoundSummary> latestRounds(Long playerId, int limit) {
        return playerId == null
                ? jdbc.sql("SELECT " + ROUND_LIST_COLUMNS + " FROM game_round r ORDER BY r.created_at DESC LIMIT ?")
                        .param(limit).query((rs, n) -> roundSummary(rs)).list()
                : jdbc.sql("SELECT " + ROUND_LIST_COLUMNS + " FROM game_round r WHERE r.player_id = ? ORDER BY r.created_at DESC LIMIT ?")
                        .params(playerId, limit).query((rs, n) -> roundSummary(rs)).list();
    }

    private static Where roundWhere(RoundFilter f) {
        return new Where()
                .add(Where.has(f.q()), "(r.id LIKE ? OR p.external_id LIKE ? OR p.username LIKE ?)",
                        Where.prefix(nz(f.q())), Where.prefix(nz(f.q())), Where.like(nz(f.q())))
                .add(Where.has(f.status()), "r.status = ?", f.status())
                .add(f.from() != null, "r.created_at >= ?", start(f.from()))
                .add(f.to() != null, "r.created_at < ?", end(f.to()))
                .add(f.rows() != null, "r.rows_count = ?", f.rows())
                .add(f.risk() != null, "r.risk = ?", f.risk())
                .add(Where.has(f.currency()), "r.currency = ?", nz(f.currency()).toUpperCase())
                .add(f.playerId() != null, "r.player_id = ?", f.playerId());
    }

    // wallet transactions

    public ListQuery.Page<TransactionRow> transactions(TransactionFilter f, ListQuery query) {
        Where where = transactionWhere(f);
        long total = count("SELECT COUNT(*) FROM wallet_transaction t JOIN player p ON p.id = t.player_id" + where.sql(), where);
        return new ListQuery.Page<>(transactions(where, query, query.size(), query.offset()), total, query);
    }

    public List<TransactionRow> transactionsForExport(TransactionFilter f, ListQuery query) {
        return transactions(transactionWhere(f), query, EXPORT_LIMIT, 0);
    }

    private List<TransactionRow> transactions(Where where, ListQuery query, int limit, int offset) {
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT t.*, p.external_id AS p_external_id, p.username AS p_username FROM wallet_transaction t JOIN player p ON p.id = t.player_id"
                        + where.sql() + query.orderBy("t.id") + " LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new TransactionRow(transaction(rs), rs.getString("p_external_id"), rs.getString("p_username")))
                .list();
    }

    private static Where transactionWhere(TransactionFilter f) {
        return new Where()
                .add(Where.has(f.q()), "(t.id LIKE ? OR t.round_id LIKE ? OR p.external_id LIKE ? OR p.username LIKE ?)",
                        Where.prefix(nz(f.q())), Where.prefix(nz(f.q())), Where.prefix(nz(f.q())), Where.like(nz(f.q())))
                .add(Where.has(f.type()), "t.type = ?", f.type())
                .add(Where.has(f.status()), "t.status = ?", f.status())
                .add(f.from() != null, "t.created_at >= ?", start(f.from()))
                .add(f.to() != null, "t.created_at < ?", end(f.to()));
    }

    // audit log

    public ListQuery.Page<AuditEntry> audit(AuditFilter f, ListQuery query) {
        Where where = auditWhere(f);
        long total = count("SELECT COUNT(*) FROM audit_log a" + where.sql(), where);
        return new ListQuery.Page<>(audit(where, query, query.size(), query.offset()), total, query);
    }

    public List<AuditEntry> auditForExport(AuditFilter f, ListQuery query) {
        return audit(auditWhere(f), query, EXPORT_LIMIT, 0);
    }

    private List<AuditEntry> audit(Where where, ListQuery query, int limit, int offset) {
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT a.* FROM audit_log a" + where.sql() + query.orderBy(null) + " LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new AuditEntry(rs.getLong("id"), rs.getString("username"), rs.getString("action"),
                        rs.getString("details"), rs.getTimestamp("created_at").toInstant(), rs.getString("prev_hash"), rs.getString("hash")))
                .list();
    }

    private static Where auditWhere(AuditFilter f) {
        return new Where()
                .add(Where.has(f.q()), "a.details LIKE ?", Where.like(nz(f.q())))
                .add(Where.has(f.username()), "a.username = ?", nz(f.username()).trim())
                .add(Where.has(f.action()), "a.action = ?", f.action())
                .add(f.from() != null, "a.created_at >= ?", start(f.from()))
                .add(f.to() != null, "a.created_at < ?", end(f.to()));
    }

    // decided change requests (approvals history)

    public ListQuery.Page<ChangeRequest> changes(ChangeFilter f, ListQuery query) {
        Where where = changeWhere(f);
        long total = count("SELECT COUNT(*) FROM change_request c" + where.sql(), where);
        return new ListQuery.Page<>(changes(where, query, query.size(), query.offset()), total, query);
    }

    public List<ChangeRequest> changesForExport(ChangeFilter f, ListQuery query) {
        return changes(changeWhere(f), query, EXPORT_LIMIT, 0);
    }

    private List<ChangeRequest> changes(Where where, ListQuery query, int limit, int offset) {
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT c.* FROM change_request c" + where.sql() + query.orderBy("c.id") + " LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new ChangeRequest(rs.getLong("id"), rs.getString("type"),
                        rs.getString("target"), rs.getString("payload"), rs.getString("summary"), rs.getString("status"),
                        rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("decided_by"), instant(rs, "decided_at"),
                        rs.getString("decision_note"), rs.getString("base_hash"), instant(rs, "expires_at"), rs.getString("signature")))
                .list();
    }

    private static Where changeWhere(ChangeFilter f) {
        return new Where()
                .add(true, "c.status <> 'PENDING'")
                .add(Where.has(f.q()), "(c.summary LIKE ? OR c.decision_note LIKE ? OR c.target LIKE ?)",
                        Where.like(nz(f.q())), Where.like(nz(f.q())), Where.like(nz(f.q())))
                .add(Where.has(f.type()), "c.type = ?", f.type())
                .add(Where.has(f.status()), "c.status = ?", f.status())
                .add(Where.has(f.proposer()), "c.requested_by = ?", nz(f.proposer()).trim())
                .add(Where.has(f.decider()), "c.decided_by = ?", nz(f.decider()).trim())
                .add(f.from() != null, "c.decided_at >= ?", start(f.from()))
                .add(f.to() != null, "c.decided_at < ?", end(f.to()));
    }

    public List<String> changeTypes() {
        return jdbc.sql("SELECT DISTINCT type FROM change_request ORDER BY type").query(String.class).list();
    }

    /** Every admin account, plus anyone in the history (e.g. an account renamed or no longer present). */
    public List<String> changePeople() {
        return jdbc.sql("""
                SELECT u FROM (SELECT username AS u FROM admin_user
                               UNION SELECT requested_by FROM change_request
                               UNION SELECT decided_by FROM change_request WHERE decided_by IS NOT NULL) x ORDER BY u""")
                .query(String.class).list();
    }

    public List<String> auditActions() {
        return jdbc.sql("SELECT DISTINCT action FROM audit_log ORDER BY action").query(String.class).list();
    }

    public List<String> auditUsers() {
        return jdbc.sql("SELECT u FROM (SELECT username AS u FROM admin_user UNION SELECT username FROM audit_log) x ORDER BY u")
                .query(String.class).list();
    }

    // helpers

    /** The number of matches, counted only up to just past the cap (review 3.10): the rest is "10,000+". */
    private long count(String sql, Where where) {
        String rows = sql.replaceFirst("^SELECT COUNT\\(\\*\\)", "SELECT 1");
        return jdbc.sql("SELECT COUNT(*) FROM (" + rows + " LIMIT " + (ListQuery.COUNT_CAP + 1) + ") counted")
                .params(where.params()).query(Long.class).single();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** null when no date is set (the filter is then not applied; Where.add still evaluates its arguments). */
    private static Timestamp start(LocalDate day) {
        return day == null ? null : Timestamp.from(day.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static Timestamp end(LocalDate day) {
        return day == null ? null : Timestamp.from(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private GameRepository.Player player(ResultSet rs, int n) throws SQLException {
        return new GameRepository.Player(rs.getLong("id"), rs.getString("external_id"), rs.getString("username"), rs.getString("currency"),
                rs.getBigDecimal("last_balance"), rs.getBoolean("blocked"), instant(rs, "created_at"), instant(rs, "last_seen_at"));
    }

    private static RoundSummary roundSummary(ResultSet rs) throws SQLException {
        return new RoundSummary(rs.getString("id"), rs.getLong("player_id"), rs.getInt("rows_count"), rs.getInt("risk"), rs.getBigDecimal("bet"),
                rs.getInt("balls"), rs.getBigDecimal("total_bet"), rs.getBigDecimal("total_win"), rs.getString("currency"), rs.getString("status"),
                rs.getBoolean("has_outcome"), instant(rs, "created_at"));
    }

    private GameRepository.Transaction transaction(ResultSet rs) throws SQLException {
        return new GameRepository.Transaction(rs.getString("id"), rs.getString("round_id"), rs.getLong("player_id"), rs.getString("type"),
                rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("original_tx_id"), rs.getString("pam_reference"),
                rs.getString("error"), rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("signature"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
