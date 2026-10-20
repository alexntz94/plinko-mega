package com.plinko.repo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

import com.plinko.common.SecretBox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GameRepository {

    public record Player(long id, String externalId, String username, String currency, BigDecimal lastBalance,
                         boolean blocked, Instant createdAt, Instant lastSeenAt) {
    }

    public record Session(String id, long playerId, String pamToken, Instant expiresAt, String theme) {
    }

    /**
     * oddsRef: the odds version (odds_history) the round was played on; maxWin: the hard limit on the win per round
     * in force when its bet was taken. Both are null for rounds played before they were recorded.
     */
    public record Round(String id, long playerId, String sessionId, int rows, int risk, BigDecimal bet, int balls,
                        BigDecimal totalBet, BigDecimal totalWin, String currency, String status, String slots,
                        String multipliers, String error, String signature, Instant createdAt, Instant updatedAt, String bonus,
                        Long oddsRef, BigDecimal maxWin) {

        /** A round without the odds version and win limit (rounds played before they were recorded). */
        public Round(String id, long playerId, String sessionId, int rows, int risk, BigDecimal bet, int balls,
                     BigDecimal totalBet, BigDecimal totalWin, String currency, String status, String slots,
                     String multipliers, String error, String signature, Instant createdAt, Instant updatedAt, String bonus) {
            this(id, playerId, sessionId, rows, risk, bet, balls, totalBet, totalWin, currency, status, slots, multipliers, error,
                    signature, createdAt, updatedAt, bonus, null, null);
        }

        /** A round without bonus information (rounds before bonuses existed, and new rounds before their outcome). */
        public Round(String id, long playerId, String sessionId, int rows, int risk, BigDecimal bet, int balls,
                     BigDecimal totalBet, BigDecimal totalWin, String currency, String status, String slots,
                     String multipliers, String error, String signature, Instant createdAt, Instant updatedAt) {
            this(id, playerId, sessionId, rows, risk, bet, balls, totalBet, totalWin, currency, status, slots, multipliers, error,
                    signature, createdAt, updatedAt, null, null, null);
        }
    }

    /**
     * A wallet transaction. {@code refused}: the wallet definitely refused it, so it moved no money (review R4; only ever
     * on a FAILED row). {@code inFlightSince}: a send started then and its answer was never recorded (outcome unknown).
     */
    public record Transaction(String id, String roundId, long playerId, String type, BigDecimal amount, String status,
                              String originalTxId, String pamReference, String error, int attempts, Instant createdAt, Instant updatedAt,
                              String signature, boolean refused, Instant inFlightSince) {

        public Transaction(String id, String roundId, long playerId, String type, BigDecimal amount, String status, String originalTxId,
                           String pamReference, String error, int attempts, Instant createdAt, Instant updatedAt, String signature) {
            this(id, roundId, playerId, type, amount, status, originalTxId, pamReference, error, attempts, createdAt, updatedAt, signature, false, null);
        }
    }

    private final JdbcClient jdbc;
    private final SecretBox secrets;
    private final DataSource dataSource;

    public GameRepository(JdbcClient jdbc, SecretBox secrets, DataSource dataSource) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.dataSource = dataSource;
    }

    /**
     * Runs the work holding a MySQL named lock, so only one backend instance does it at a time. The lock lives on
     * one connection and is freed by MySQL if the instance dies. False (work not run) if it was not free in time.
     */
    public boolean whileLocked(String name, int waitSeconds, Runnable work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                lock.setString(1, name);
                lock.setInt(2, waitSeconds);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 1) {
                        return false;
                    }
                }
            }
            try {
                work.run();
                return true;
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, name);
                    release.executeQuery().close();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not use the database lock " + name, e);
        }
    }

    // players

    public Player upsertPlayer(String externalId, String username, String currency, BigDecimal balance) {
        Timestamp now = now();
        jdbc.sql("""
                INSERT INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, FALSE, ?, ?)
                ON DUPLICATE KEY UPDATE username = VALUES(username), currency = VALUES(currency),
                    last_balance = VALUES(last_balance), last_seen_at = VALUES(last_seen_at)""")
                .params(externalId, username, currency, balance, now, now)
                .update();
        Player player = jdbc.sql("SELECT * FROM player WHERE external_id = ?").param(externalId).query(this::player).single();
        if (!player.externalId().equals(externalId)) {
            throw new IllegalStateException("Player id collision for " + externalId);
        }
        return player;
    }

    public Optional<Player> findPlayer(long id) {
        return jdbc.sql("SELECT * FROM player WHERE id = ?").param(id).query(this::player).optional();
    }

    public void updatePlayerBalance(long id, BigDecimal balance) {
        jdbc.sql("UPDATE player SET last_balance = ?, last_seen_at = ? WHERE id = ?").params(balance, now(), id).update();
    }

    public void setPlayerBlocked(long id, boolean blocked) {
        jdbc.sql("UPDATE player SET blocked = ? WHERE id = ?").params(blocked, id).update();
    }

    public List<Player> searchPlayers(String query, int limit, int offset) {
        String like = "%" + (query == null ? "" : query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.sql("SELECT * FROM player WHERE external_id LIKE ? OR username LIKE ? ORDER BY last_seen_at DESC LIMIT ? OFFSET ?")
                .params(like, like, limit, offset)
                .query(this::player).list();
    }

    // sessions

    /** Records a launch token as used. Returns false if it was used before (primary key clash). */
    public boolean claimLaunchToken(String tokenHash) {
        try {
            jdbc.sql("INSERT INTO used_launch_token (token_hash, used_at) VALUES (?, ?)").params(tokenHash, now()).update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** The PAM token is stored encrypted and bound to this session and player, so a copied value is useless. */
    public void insertSession(Session session) {
        jdbc.sql("INSERT INTO game_session (id, player_id, pam_token, created_at, expires_at, theme) VALUES (?, ?, ?, ?, ?, ?)")
                .params(session.id(), session.playerId(), secrets.encrypt(tokenPurpose(session.id(), session.playerId()), session.pamToken()),
                        now(), Timestamp.from(session.expiresAt()), session.theme())
                .update();
    }

    /** Empty if the session does not exist or its token was not stored by the application. */
    public Optional<Session> findSession(String id) {
        return jdbc.sql("SELECT * FROM game_session WHERE id = ?").param(id)
                .query((rs, n) -> {
                    long playerId = rs.getLong("player_id");
                    String token = secrets.decrypt(tokenPurpose(rs.getString("id"), playerId), rs.getString("pam_token"));
                    return token == null ? null : new Session(rs.getString("id"), playerId, token, rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("theme"));
                })
                .list().stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private static String tokenPurpose(String sessionId, long playerId) {
        return "pam_token:" + sessionId + ":" + playerId;
    }

    public void extendSession(String id, Instant expiresAt) {
        jdbc.sql("UPDATE game_session SET expires_at = ? WHERE id = ?").params(Timestamp.from(expiresAt), id).update();
    }

    /**
     * Claims the player for one round, on every backend instance at once: true if no other round holds the player
     * (or its claim has expired). Uses the database clock, so instances with different clocks agree.
     */
    public boolean claimRound(long playerId, String claim, long seconds) {
        return jdbc.sql("UPDATE player SET round_lock = ?, round_lock_until = UTC_TIMESTAMP(3) + INTERVAL ? SECOND "
                        + "WHERE id = ? AND (round_lock IS NULL OR round_lock_until < UTC_TIMESTAMP(3))")
                .params(claim, seconds, playerId).update() == 1;
    }

    /**
     * The end of a round in one update: the player's balance as the wallet last reported it, and this round's claim
     * released (a claim another round took over after expiry is left alone).
     */
    public void settlePlayer(long playerId, BigDecimal balance, String claim) {
        settlePlayer(playerId, balance, claim, null);
    }

    /**
     * As {@link #settlePlayer(long, BigDecimal, String)}, also pointing the player's bonus meter at this round when it
     * moved the meter (review 3.1.3: one update of the player at the end instead of a second one with the outcome). The
     * pointer is only a shortcut: if this update never happens, the meter is still found by following its chain.
     */
    public void settlePlayer(long playerId, BigDecimal balance, String claim, String meterRound) {
        jdbc.sql("UPDATE player SET last_balance = ?, last_seen_at = ?, round_lock_until = IF(round_lock <=> ?, NULL, round_lock_until), "
                        + "round_lock = IF(round_lock <=> ?, NULL, round_lock), meter_round = COALESCE(?, meter_round) WHERE id = ?")
                .params(balance, now(), claim, claim, meterRound, playerId).update();
    }

    /**
     * Rounds owed a credit or refund whose transaction is due for another try (fewer than {@code maxAttempts} so far,
     * and its wait over), the most overdue first (review 3.8).
     */
    public List<Round> dueForRetry(int maxAttempts, int limit) {
        // one branch per kind of transaction (credit, refund), status and "never scheduled / due": each read in order from
        // idx_tx_type_status_retry, so a pass never sorts every open round (3.5); never-scheduled ones first, as before
        List<String> branches = new java.util.ArrayList<>();
        List<Object> params = new java.util.ArrayList<>();
        for (String[] kind : new String[][] {{"CREDIT", "CREDIT_PENDING"}, {"ROLLBACK", "ROLLBACK_PENDING"}}) {
            for (String status : List.of("PENDING", "FAILED")) {
                for (boolean scheduled : new boolean[] {false, true}) {
                    branches.add("(SELECT r.*, t.next_retry_at AS retry_at FROM wallet_transaction t JOIN game_round r ON r.id = t.round_id "
                            + "WHERE t.type = ? AND t.status = ? AND " + (scheduled ? "t.next_retry_at <= UTC_TIMESTAMP(3)" : "t.next_retry_at IS NULL")
                            + " AND t.attempts < ? AND r.status = ?" + (scheduled ? " ORDER BY t.next_retry_at" : "") + " LIMIT ?)");
                    params.addAll(List.of(kind[0], status, maxAttempts, kind[1], limit));
                }
            }
        }
        params.add(limit);
        return jdbc.sql("SELECT * FROM (" + String.join(" UNION ALL ", branches) + ") x ORDER BY retry_at IS NOT NULL, retry_at LIMIT ?")
                .params(params).query(this::round).list();
    }

    /** When a refused credit or refund is tried again. */
    public void scheduleRetry(String transactionId, Instant at) {
        jdbc.sql("UPDATE wallet_transaction SET next_retry_at = ? WHERE id = ?").params(Timestamp.from(at), transactionId).update();
    }

    /** Review 3.7: removes up to {@code limit} used launch tokens and expired sessions older than 31 days; returns how many. */
    public int deleteOldTokens(int limit) {
        return jdbc.sql("DELETE FROM used_launch_token WHERE used_at < UTC_TIMESTAMP(3) - INTERVAL 31 DAY LIMIT ?").param(limit).update();
    }

    public int deleteOldSessions(int limit) {
        return jdbc.sql("DELETE FROM game_session WHERE expires_at < UTC_TIMESTAMP(3) - INTERVAL 31 DAY LIMIT ?").param(limit).update();
    }

    /** The player's current bonus-meter round as last recorded (review 3.4; may lag behind: follow the chain from it). */
    public Optional<String> meterRound(long playerId) {
        return jdbc.sql("SELECT meter_round FROM player WHERE id = ?").param(playerId).query(String.class).optional()
                .filter(java.util.Objects::nonNull);
    }

    public void setMeterRound(long playerId, String roundId) {
        jdbc.sql("UPDATE player SET meter_round = ? WHERE id = ?").params(roundId, playerId).update();
    }

    /** The round that continued the bonus meter from this state (a round id, or "start-<player>"), by its unique index. */
    public Optional<Round> roundContinuing(String meterState) {
        return jdbc.sql("SELECT * FROM game_round WHERE meter_prev = ?").param(meterState).query(this::round).optional();
    }

    /** Ends this round's claim; a claim another round took over after expiry is left alone. */
    public void releaseRound(long playerId, String claim) {
        jdbc.sql("UPDATE player SET round_lock = NULL, round_lock_until = NULL WHERE id = ? AND round_lock = ?")
                .params(playerId, claim).update();
    }

    // rounds

    public void insertRound(Round round) {
        insertRound(round, null);
    }

    /** requestId: the game's id for this tap (review 2.3); a second round with the same id fails (DuplicateKeyException). */
    public void insertRound(Round round, String requestId) {
        jdbc.sql("""
                INSERT INTO game_round (id, player_id, session_id, rows_count, risk, odds_ref, bet, balls, total_bet, max_win, currency,
                                        status, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(round.id(), round.playerId(), round.sessionId(), round.rows(), round.risk(), round.oddsRef(), round.bet(),
                        round.balls(), round.totalBet(), round.maxWin(), round.currency(), round.status(), now(), now(), requestId)
                .update();
    }

    /**
     * Review 7.5: a player's decided rounds played since {@code since}, newest first, before (beforePlayed, beforeId): one
     * page of their history, on the (player_id, created_at) index.
     */
    public List<Round> history(long playerId, Instant since, Instant beforePlayed, String beforeId, int limit) {
        Timestamp before = Timestamp.from(beforePlayed);
        return jdbc.sql("""
                SELECT * FROM game_round WHERE player_id = ? AND signature IS NOT NULL AND created_at >= ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC LIMIT ?""")
                .params(playerId, Timestamp.from(since), before, before, beforeId, limit)
                .query(this::round).list();
    }

    /** The round a player's request id already played (review 2.3), by the unique index. */
    public Optional<Round> roundByRequest(long playerId, String requestId) {
        return jdbc.sql("SELECT * FROM game_round WHERE player_id = ? AND request_id = ?").params(playerId, requestId)
                .query(this::round).optional();
    }

    /**
     * meterPrev: the round whose meter state this one continued from (null without the meter). Another round that
     * already continued from it makes this fail with a DuplicateKeyException (unique index), and nothing is saved.
     */
    public void saveOutcome(String roundId, String slots, String multipliers, String bonus, String meterPrev, BigDecimal totalWin,
                            String signature, String status) {
        jdbc.sql("UPDATE game_round SET slots = ?, multipliers = ?, bonus = ?, meter_prev = ?, total_win = ?, signature = ?, status = ?, "
                        + "updated_at = ? WHERE id = ?")
                .params(slots, multipliers, bonus, meterPrev, totalWin, signature, status, now(), roundId)
                .update();
    }

    /** Moves a round from {@code from} to {@code to} only if it is still in {@code from}; false if something else moved it. */
    public boolean transitionRound(String roundId, String from, String to, String error) {
        return jdbc.sql("UPDATE game_round SET status = ?, error = ?, updated_at = ? WHERE id = ? AND status = ?")
                .params(to, truncate(error), now(), roundId, from)
                .update() == 1;
    }

    public void updateRoundStatus(String roundId, String status, String error) {
        jdbc.sql("UPDATE game_round SET status = ?, error = ?, updated_at = ? WHERE id = ?")
                .params(status, truncate(error), now(), roundId)
                .update();
    }

    public Optional<Round> findRound(String id) {
        return jdbc.sql("SELECT * FROM game_round WHERE id = ?").param(id).query(this::round).optional();
    }

    public List<Round> rounds(Long playerId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM game_round WHERE 1 = 1");
        List<Object> params = new java.util.ArrayList<>();
        if (playerId != null) {
            sql.append(" AND player_id = ?");
            params.add(playerId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.sql(sql.toString()).params(params).query(this::round).list();
    }


    public List<Round> roundsByStatus(List<String> statuses) {
        return jdbc.sql("SELECT * FROM game_round WHERE status IN (:statuses) ORDER BY created_at")
                .param("statuses", statuses)
                .query(this::round).list();
    }

    /** One page (ordered by id) of rounds that have, or claim to have, an outcome; for the hourly signature scan. */
    /**
     * Decided rounds played after (playedAfter, afterId) and before {@code until}, in the order they were played (the
     * created_at index): the hourly scan's next page (review 3.6).
     */
    /** The columns a {@link Round} is read from (the full scans read only these, not the whole row). */
    private static final String ROUND_COLUMNS = "id, player_id, session_id, rows_count, risk, bet, balls, total_bet, total_win, currency, status, "
            + "slots, multipliers, error, signature, created_at, updated_at, bonus, odds_ref, max_win";

    public List<Round> decidedRoundsPlayed(Instant playedAfter, String afterId, Instant until, int limit) {
        Timestamp after = Timestamp.from(playedAfter);
        return jdbc.sql("SELECT " + ROUND_COLUMNS + """
                 FROM game_round WHERE (created_at > ? OR (created_at = ? AND id > ?)) AND created_at < ?
                  AND (status IN ('DEBITED', 'CREDIT_PENDING', 'COMPLETED') OR total_win IS NOT NULL)
                ORDER BY created_at, id LIMIT ?""")
                .params(after, after, afterId, Timestamp.from(until), limit)
                .query(this::round).list();
    }

    public List<Round> decidedRoundsAfter(String afterId, int limit) {
        return jdbc.sql("SELECT " + ROUND_COLUMNS + """
                 FROM game_round WHERE id > ? AND (status IN ('DEBITED', 'CREDIT_PENDING', 'COMPLETED') OR total_win IS NOT NULL)
                ORDER BY id LIMIT ?""")
                .params(afterId, limit)
                .query(this::round).list();
    }

    /**
     * Rounds still in these statuses although they were created before {@code olderThan}, e.g. after a crash in the
     * middle of a round. Uses created_at, which the database trigger keeps unchangeable.
     */
    public List<Round> staleRounds(List<String> statuses, Instant olderThan) {
        // one branch per status, each read in time order from (status, created_at): no sort of every open round (3.5)
        String branches = String.join(" UNION ALL ", java.util.Collections.nCopies(statuses.size(),
                "(SELECT * FROM game_round WHERE status = ? AND created_at < ? ORDER BY created_at LIMIT 500)"));
        List<Object> params = new java.util.ArrayList<>();
        statuses.forEach(status -> {
            params.add(status);
            params.add(Timestamp.from(olderThan));
        });
        return jdbc.sql("SELECT * FROM (" + branches + ") x ORDER BY created_at LIMIT 500").params(params).query(this::round).list();
    }

    // transactions

    public void insertTransaction(Transaction tx) {
        jdbc.sql("""
                INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, original_tx_id, signature, attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""")
                .params(tx.id(), tx.roundId(), tx.playerId(), tx.type(), tx.amount(), tx.status(), tx.originalTxId(), tx.signature(), now(), now())
                .update();
    }

    /** When the player's latest round began (review 7.4, minimum time per round), on the (player_id, created_at) index. */
    public Optional<Instant> lastRoundAt(long playerId) {
        return jdbc.sql("SELECT created_at FROM game_round WHERE player_id = ? ORDER BY created_at DESC LIMIT 1").param(playerId)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
    }

    /** Review 7.1: the game's wallet transactions created in [from, to), for checking them against the wallet's own record. */
    public List<Transaction> transactionsBetween(Instant from, Instant to, int limit) {
        return jdbc.sql("SELECT * FROM wallet_transaction WHERE created_at >= ? AND created_at < ? ORDER BY created_at LIMIT ?")
                .params(Timestamp.from(from), Timestamp.from(to), limit).query(this::transaction).list();
    }

    public void updateTransaction(String id, String status, String pamReference, String error) {
        updateTransaction(id, status, pamReference, error, false);
    }

    /**
     * Records the wallet's answer (or that the game never sent it): ends a send in flight, and {@code refused} says the
     * wallet definitely refused it (only for FAILED; the database refuses a refusal that isn't such an answer, R4).
     */
    public void updateTransaction(String id, String status, String pamReference, String error, boolean refused) {
        jdbc.sql("UPDATE wallet_transaction SET status = ?, pam_reference = ?, error = ?, refused = ?, in_flight_since = NULL, "
                        + "attempts = attempts + 1, updated_at = ? WHERE id = ?")
                .params(status, pamReference, truncate(error), refused, now(), id)
                .update();
    }

    /**
     * Review R4: marks a credit or rollback as being sent, in its own committed write on its own connection (even when
     * called inside a transaction), before the wallet is called. Until its answer is recorded its outcome is unknown, so
     * a crash after the send never leaves an earlier refusal standing. Never call it inside a transaction that holds
     * this row's lock.
     */
    public void markSending(String id) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement mark = connection.prepareStatement(
                    "UPDATE wallet_transaction SET refused = 0, in_flight_since = ?, updated_at = ? WHERE id = ? AND status <> 'OK'")) {
                mark.setTimestamp(1, now());
                mark.setTimestamp(2, now());
                mark.setString(3, id);
                mark.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark transaction " + id + " as being sent", e);
        }
    }

    /**
     * Rounds still owed a credit or refund whose wallet transaction has used up its retries, oldest first: the
     * background retry no longer tries them, so a person must look.
     */
    public List<String> stoppedRounds(int maxAttempts) {
        // one branch per status, each read in time order from (status, created_at) (3.5)
        return jdbc.sql("""
                SELECT id FROM (
                  (SELECT r.id, r.created_at FROM game_round r JOIN wallet_transaction t ON t.round_id = r.id AND t.type = 'CREDIT'
                   WHERE r.status = 'CREDIT_PENDING' AND t.attempts >= ? ORDER BY r.created_at LIMIT 1000)
                  UNION ALL
                  (SELECT r.id, r.created_at FROM game_round r JOIN wallet_transaction t ON t.round_id = r.id AND t.type = 'ROLLBACK'
                   WHERE r.status = 'ROLLBACK_PENDING' AND t.attempts >= ? ORDER BY r.created_at LIMIT 1000)
                ) x ORDER BY created_at LIMIT 1000""")
                .params(maxAttempts, maxAttempts).query(String.class).list();
    }

    public List<Transaction> transactionsOfRound(String roundId) {
        return jdbc.sql("SELECT * FROM wallet_transaction WHERE round_id = ? ORDER BY created_at").param(roundId).query(this::transaction).list();
    }

    public List<Transaction> transactions(String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("SELECT * FROM wallet_transaction ORDER BY created_at DESC LIMIT ? OFFSET ?")
                    .params(limit, offset).query(this::transaction).list();
        }
        return jdbc.sql("SELECT * FROM wallet_transaction WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?")
                .params(status, limit, offset).query(this::transaction).list();
    }

    private Player player(ResultSet rs, int n) throws SQLException {
        return new Player(rs.getLong("id"), rs.getString("external_id"), rs.getString("username"), rs.getString("currency"),
                rs.getBigDecimal("last_balance"), rs.getBoolean("blocked"), instant(rs, "created_at"), instant(rs, "last_seen_at"));
    }

    private Round round(ResultSet rs, int n) throws SQLException {
        return new Round(rs.getString("id"), rs.getLong("player_id"), rs.getString("session_id"), rs.getInt("rows_count"),
                rs.getInt("risk"), rs.getBigDecimal("bet"), rs.getInt("balls"), rs.getBigDecimal("total_bet"),
                rs.getBigDecimal("total_win"), rs.getString("currency"), rs.getString("status"), rs.getString("slots"),
                rs.getString("multipliers"), rs.getString("error"), rs.getString("signature"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("bonus"), rs.getObject("odds_ref", Long.class), rs.getBigDecimal("max_win"));
    }

    private Transaction transaction(ResultSet rs, int n) throws SQLException {
        return new Transaction(rs.getString("id"), rs.getString("round_id"), rs.getLong("player_id"), rs.getString("type"),
                rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("original_tx_id"), rs.getString("pam_reference"),
                rs.getString("error"), rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("signature"),
                rs.getBoolean("refused"), instant(rs, "in_flight_since"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
