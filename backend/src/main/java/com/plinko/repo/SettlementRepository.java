package com.plinko.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Settlements of blocked rounds (review 7.3): one per round, written once, only its status moves from PENDING to DONE. */
@Repository
public class SettlementRepository {

    /** {@code stoppedAt}: when its automatic sends stopped (R11), null while they go on. */
    public record Settlement(String roundId, String action, BigDecimal amount, String txId, long changeId, String proposedBy,
                             String approvedBy, String reason, String status, String signature, Instant createdAt, Instant updatedAt,
                             Instant stoppedAt) {

        public Settlement(String roundId, String action, BigDecimal amount, String txId, long changeId, String proposedBy, String approvedBy,
                          String reason, String status, String signature, Instant createdAt, Instant updatedAt) {
            this(roundId, action, amount, txId, changeId, proposedBy, approvedBy, reason, status, signature, createdAt, updatedAt, null);
        }
    }

    /** A blocked round that is not settled yet, for the dashboard: how long it has waited, and a settlement under way. */
    public record OpenRound(String roundId, String playerExternalId, BigDecimal totalBet, String currency, Instant blockedAt,
                            String settlementStatus) {
    }

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Settlement> find(String roundId) {
        return jdbc.sql("SELECT * FROM round_settlement WHERE round_id = ?").param(roundId).query(this::map).optional();
    }

    public void insert(Settlement s) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO round_settlement (round_id, action, amount, tx_id, change_id, proposed_by, approved_by, reason, status,
                  signature, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(s.roundId(), s.action(), s.amount(), s.txId(), s.changeId(), s.proposedBy(), s.approvedBy(), s.reason(),
                        s.status(), s.signature(), now, now)
                .update();
    }

    public void markDone(String roundId) {
        jdbc.sql("UPDATE round_settlement SET status = 'DONE', updated_at = ? WHERE round_id = ? AND status = 'PENDING'")
                .params(Timestamp.from(Instant.now()), roundId).update();
    }

    /**
     * Settlements whose money has not been confirmed by the wallet yet and whose automatic sends go on, oldest first:
     * stopped ones are left out here, so however many there are they never take the place of newer ones (R11).
     */
    public List<Settlement> pending(Instant before, int limit) {
        return jdbc.sql("SELECT * FROM round_settlement WHERE status = 'PENDING' AND stopped_at IS NULL AND updated_at < ? ORDER BY updated_at LIMIT ?")
                .params(Timestamp.from(before), limit).query(this::map).list();
    }

    /** Pending settlements whose automatic sends have stopped (a person must look), oldest stop first. */
    public List<Settlement> stopped(int limit) {
        return jdbc.sql("SELECT * FROM round_settlement WHERE status = 'PENDING' AND stopped_at IS NOT NULL ORDER BY stopped_at LIMIT ?")
                .param(limit).query(this::map).list();
    }

    /** Automatic sends stop for this settlement (the wallet refused it, or it used up its attempts); true if it wasn't stopped yet. */
    public boolean markStopped(String roundId) {
        return jdbc.sql("UPDATE round_settlement SET stopped_at = ?, updated_at = ? WHERE round_id = ? AND status = 'PENDING' AND stopped_at IS NULL")
                .params(Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), roundId).update() == 1;
    }

    /** A person sends it again: automatic sends go on until the wallet answers or they stop again. */
    public void resume(String roundId) {
        jdbc.sql("UPDATE round_settlement SET stopped_at = NULL WHERE round_id = ? AND status = 'PENDING'").param(roundId).update();
    }

    /** Blocked rounds without a finished settlement, the longest waiting first. */
    public List<OpenRound> open(int limit) {
        return jdbc.sql("""
                SELECT r.id, p.external_id, r.total_bet, r.currency, r.updated_at, s.status
                FROM game_round r JOIN player p ON p.id = r.player_id LEFT JOIN round_settlement s ON s.round_id = r.id
                WHERE r.status = 'TAMPERED' AND (s.status IS NULL OR s.status <> 'DONE')
                ORDER BY r.updated_at LIMIT ?""")
                .param(limit)
                .query((rs, i) -> new OpenRound(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(6)))
                .list();
    }

    private Settlement map(ResultSet rs, int row) throws SQLException {
        return new Settlement(rs.getString("round_id"), rs.getString("action"), rs.getBigDecimal("amount"), rs.getString("tx_id"),
                rs.getLong("change_id"), rs.getString("proposed_by"), rs.getString("approved_by"), rs.getString("reason"),
                rs.getString("status"), rs.getString("signature"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("stopped_at") == null ? null : rs.getTimestamp("stopped_at").toInstant());
    }
}
