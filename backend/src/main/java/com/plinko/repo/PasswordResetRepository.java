package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.plinko.model.ResetRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code password_reset_request} table ("forgot password"). */
@Repository
public class PasswordResetRepository {

    private final JdbcClient jdbc;

    public PasswordResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasOpen(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM password_reset_request WHERE username = ? AND status = 'PENDING'")
                .param(username).query(Long.class).single() > 0;
    }

    public void insert(String username, String ip, Instant requestedAt) {
        jdbc.sql("INSERT INTO password_reset_request (username, ip, status, requested_at) VALUES (?, ?, 'PENDING', ?)")
                .params(username, ip, Timestamp.from(requestedAt)).update();
    }

    public List<ResetRequest> pending() {
        return jdbc.sql("SELECT * FROM password_reset_request WHERE status = 'PENDING' ORDER BY requested_at")
                .query((rs, n) -> new ResetRequest(rs.getLong("id"), rs.getString("username"), rs.getString("ip"),
                        rs.getTimestamp("requested_at").toInstant()))
                .list();
    }

    /** Marks the account's open requests done (its password was reset). */
    public void done(String username, String handledBy, Instant at) {
        jdbc.sql("UPDATE password_reset_request SET status = 'DONE', handled_by = ?, handled_at = ? WHERE username = ? AND status = 'PENDING'")
                .params(handledBy, Timestamp.from(at), username).update();
    }

    public void dismiss(long id, String handledBy, Instant at) {
        jdbc.sql("UPDATE password_reset_request SET status = 'DISMISSED', handled_by = ?, handled_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(handledBy, Timestamp.from(at), id).update();
    }
}
