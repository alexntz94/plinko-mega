package com.plinko.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

import com.plinko.model.AuditEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The append-only {@code audit_log} table. */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public AuditRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * Appends an entry chained to the newest one: {@code hashAfter} turns the previous entry's hash ({@code genesis}
     * for the first) into this entry's hash. Runs on a dedicated autocommit connection: the named lock, the read of
     * the previous hash, the insert and the release all happen on one connection, and the entry is committed before
     * the lock is released. The entry is kept even if the caller's own transaction later rolls back.
     */
    public void appendChained(String username, String action, String details, Instant createdAt, String genesis,
                              UnaryOperator<String> hashAfter) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (scalarInt(connection, "SELECT GET_LOCK('plinko_audit_log', 10)") != 1) {
                throw new IllegalStateException("Could not lock the audit log");
            }
            try {
                String prevHash = genesis;
                try (PreparedStatement select = connection.prepareStatement("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1");
                     ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        prevHash = rs.getString(1);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO audit_log (username, action, details, created_at, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, username);
                    insert.setString(2, action);
                    insert.setString(3, details);
                    insert.setTimestamp(4, Timestamp.from(createdAt));
                    insert.setString(5, prevHash);
                    insert.setString(6, hashAfter.apply(prevHash));
                    insert.executeUpdate();
                }
            } finally {
                scalarInt(connection, "SELECT RELEASE_LOCK('plinko_audit_log')");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write the audit log", e);
        }
    }

    /** The entries after {@code afterId}, oldest first, streamed to {@code each}. */
    public void forEachAfter(long afterId, Consumer<AuditEntry> each) {
        jdbc.sql("SELECT * FROM audit_log WHERE id > ? ORDER BY id").param(afterId).query(rs -> {
            each.accept(new AuditEntry(rs.getLong("id"), rs.getString("username"), rs.getString("action"), rs.getString("details"),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("prev_hash"), rs.getString("hash")));
        });
    }

    /** Every entry, oldest first, streamed to {@code each}. */
    public void forEachInOrder(Consumer<AuditEntry> each) {
        jdbc.sql("SELECT * FROM audit_log ORDER BY id").query(rs -> {
            each.accept(new AuditEntry(rs.getLong("id"), rs.getString("username"), rs.getString("action"), rs.getString("details"),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("prev_hash"), rs.getString("hash")));
        });
    }

    public boolean hasAction(String action) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = ?").param(action).query(Long.class).single() > 0;
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
