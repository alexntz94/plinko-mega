package com.plinko.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The built-in test PAM's wallets and transactions ({@code mock_wallet}, {@code mock_transaction}); never used with a real PAM. */
@Repository
public class MockWalletRepository {

    public record Wallet(String playerId, String username, String currency, BigDecimal balance) {
    }

    private final JdbcClient jdbc;

    public MockWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Wallet> byToken(String token) {
        // R1.7: a launch token is valid 24 hours after it was issued
        return jdbc.sql("SELECT * FROM mock_wallet WHERE token = ? AND (token_issued_at IS NULL OR token_issued_at > ?)")
                .params(token, Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(24))))
                .query(this::wallet).optional();
    }

    /** The wallet, locked until the transaction ends. */
    public Optional<Wallet> lock(String playerId) {
        return jdbc.sql("SELECT * FROM mock_wallet WHERE player_id = ? FOR UPDATE").param(playerId).query(this::wallet).optional();
    }

    /** The balance a transaction left, if that transaction id was already used. */
    /** True if a rollback of this transaction was recorded (whether or not the transaction itself arrived). */
    /** A transaction as the mock PAM first recorded it (to tell a true repeat from a reused id). */
    public record Stored(String playerId, String type, BigDecimal amount, BigDecimal balanceAfter, String roundId, String originalId) {
    }

    public Optional<Stored> stored(String transactionId) {
        return jdbc.sql("SELECT player_id, type, amount, balance_after, round_id, original_tx_id FROM mock_transaction WHERE transaction_id = ?")
                .param(transactionId)
                .query((rs, n) -> new Stored(rs.getString("player_id"), rs.getString("type"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"), rs.getString("round_id"), rs.getString("original_tx_id")))
                .optional();
    }

    public boolean rolledBack(String transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_transaction WHERE original_tx_id = ?")
                .param(transactionId).query(Long.class).single() > 0;
    }

    public boolean roundPaid(String roundId) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_transaction WHERE round_id = ? AND type = 'CREDIT'")
                .param(roundId).query(Long.class).single() > 0;
    }

    /** The amount of a debit of this player and round. */
    public Optional<BigDecimal> debit(String transactionId, String playerId, String roundId) {
        return jdbc.sql("SELECT amount FROM mock_transaction WHERE transaction_id = ? AND type = 'DEBIT' AND player_id = ? AND round_id = ?")
                .params(transactionId, playerId, roundId)
                .query(BigDecimal.class).optional();
    }

    /** Sets the wallet's balance and records the transaction that led to it. */
    public void record(String transactionId, String playerId, String type, BigDecimal amount, BigDecimal balance, String roundId, String originalId) {
        jdbc.sql("UPDATE mock_wallet SET balance = ? WHERE player_id = ?").params(balance, playerId).update();
        jdbc.sql("INSERT INTO mock_transaction (transaction_id, player_id, type, amount, balance_after, round_id, original_tx_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(transactionId, playerId, type, amount, balance, roundId, originalId, Timestamp.from(Instant.now()))
                .update();
    }

    /** The mock wallet's side of the contract's transaction listing (review 7.1), by transaction id. */
    public List<Map<String, Object>> transactions(Instant from, Instant to, String after, int limit) {
        return jdbc.sql("""
                SELECT transaction_id, type, round_id, player_id, amount, created_at FROM mock_transaction
                WHERE created_at >= ? AND created_at < ? AND transaction_id > ? ORDER BY transaction_id LIMIT ?""")
                .params(java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), after == null ? "" : after, limit)
                .query((rs, i) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("transactionId", rs.getString(1));
                    row.put("type", rs.getString(2));
                    row.put("roundId", rs.getString(3));
                    row.put("playerId", rs.getString(4));
                    row.put("amount", rs.getBigDecimal(5));
                    row.put("time", rs.getTimestamp(6).toInstant().toString());
                    return row;
                }).list();
    }

    public List<Map<String, Object>> all() {
        return jdbc.sql("SELECT player_id, username, currency, balance, token FROM mock_wallet ORDER BY created_at DESC")
                .query().listOfRows();
    }

    public void create(String playerId, String username, String currency, BigDecimal balance, String token, Instant createdAt) {
        jdbc.sql("INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at, token_issued_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(playerId, username, currency, balance, token, Timestamp.from(createdAt), Timestamp.from(createdAt))
                .update();
    }

    /** R1.7: a launch token works once: once used it is expired (a fresh one comes from "Open game" or setToken). */
    public void useToken(String playerId, String token) {
        jdbc.sql("UPDATE mock_wallet SET token_issued_at = ? WHERE player_id = ? AND token = ?")
                .params(Timestamp.from(Instant.EPOCH.plusSeconds(86_400)), playerId, token).update();
    }

    /** Gives the wallet a new launch token; false if there is no such wallet. */
    public boolean setToken(String playerId, String token) {
        return jdbc.sql("UPDATE mock_wallet SET token = ?, token_issued_at = ? WHERE player_id = ?")
                .params(token, Timestamp.from(Instant.now()), playerId).update() == 1;
    }

    /** Demo wallets created after {@code since}. */
    public int demoWalletsSince(Instant since) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM mock_wallet WHERE player_id LIKE 'demo-%' AND created_at > ?")
                .param(Timestamp.from(since)).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private Wallet wallet(ResultSet rs, int n) throws SQLException {
        return new Wallet(rs.getString("player_id"), rs.getString("username"), rs.getString("currency"), rs.getBigDecimal("balance"));
    }
}
