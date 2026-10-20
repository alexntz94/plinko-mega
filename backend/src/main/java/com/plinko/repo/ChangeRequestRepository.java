package com.plinko.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.model.ChangeRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code change_request} table (four-eyes proposals). */
@Repository
public class ChangeRequestRepository {

    private final JdbcClient jdbc;

    public ChangeRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChangeRequest> pending() {
        return jdbc.sql("SELECT * FROM change_request WHERE status = 'PENDING' ORDER BY requested_at").query(this::map).list();
    }

    public List<ChangeRequest> recent(int limit) {
        return jdbc.sql("SELECT * FROM change_request WHERE status <> 'PENDING' ORDER BY decided_at DESC LIMIT ?").param(limit).query(this::map).list();
    }

    public Optional<ChangeRequest> pendingFor(String type, String target) {
        return jdbc.sql("SELECT * FROM change_request WHERE status = 'PENDING' AND type = ? AND target = ?")
                .params(type, target).query(this::map).optional();
    }

    /** Stores a new PENDING request and returns its id. */
    public long insert(String type, String target, String payload, String summary, String requestedBy, Instant requestedAt,
                       String baseHash, Instant expiresAt, String signature) {
        jdbc.sql("""
                INSERT INTO change_request (type, target, payload, summary, status, requested_by, requested_at, base_hash, expires_at, signature)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)""")
                .params(type, target, payload, summary, requestedBy, Timestamp.from(requestedAt), baseHash, Timestamp.from(expiresAt), signature)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /** The request, locked until the transaction ends. */
    public Optional<ChangeRequest> lock(long id) {
        return jdbc.sql("SELECT * FROM change_request WHERE id = ? FOR UPDATE").param(id).query(this::map).optional();
    }

    public void decide(long id, String status, String decidedBy, Instant decidedAt, String note) {
        jdbc.sql("UPDATE change_request SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ?")
                .params(status, decidedBy, Timestamp.from(decidedAt), note, id)
                .update();
    }

    private ChangeRequest map(ResultSet rs, int n) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        Timestamp expires = rs.getTimestamp("expires_at");
        return new ChangeRequest(rs.getLong("id"), rs.getString("type"), rs.getString("target"), rs.getString("payload"),
                rs.getString("summary"), rs.getString("status"), rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(),
                rs.getString("decided_by"), decided == null ? null : decided.toInstant(), rs.getString("decision_note"),
                rs.getString("base_hash"), expires == null ? null : expires.toInstant(), rs.getString("signature"));
    }
}
