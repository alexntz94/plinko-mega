package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code game_event} table (review 7.4): what the game client reports having shown the player. Insert-only. */
@Repository
public class GameEventRepository {

    /** One recorded event: its type and the server's time it arrived. */
    public record Event(String type, Instant at) {
    }

    /** A player's events of one type: how many, and the newest. */
    public record Summary(String type, long count, Instant last) {
    }

    private final JdbcClient jdbc;

    public GameEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String sessionId, long playerId, String type, Instant clientAt, Instant at) {
        jdbc.sql("INSERT INTO game_event (session_id, player_id, type, client_at, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(sessionId, playerId, type, clientAt == null ? null : Timestamp.from(clientAt), Timestamp.from(at))
                .update();
    }

    /** The session's events, newest first, at most {@code limit}. */
    public List<Event> latest(String sessionId, int limit) {
        return jdbc.sql("SELECT type, created_at FROM game_event WHERE session_id = ? ORDER BY id DESC LIMIT ?")
                .params(sessionId, limit)
                .query((rs, n) -> new Event(rs.getString("type"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** How many events the session has recorded, counted only up to {@code cap}. */
    public long countInSession(String sessionId, int cap) {
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT 1 FROM game_event WHERE session_id = ? LIMIT ?) counted")
                .params(sessionId, cap).query(Long.class).single();
    }

    /** When the game session was opened (empty once it is gone). */
    public java.util.Optional<Instant> sessionStart(String sessionId) {
        return jdbc.sql("SELECT created_at FROM game_session WHERE id = ?").param(sessionId)
                .query((rs, n) -> rs.getTimestamp("created_at").toInstant()).optional();
    }

    /** Per type, how many events the player has and the newest (the player's admin page). */
    public List<Summary> summary(long playerId) {
        return jdbc.sql("SELECT type, COUNT(*) AS n, MAX(created_at) AS last FROM game_event WHERE player_id = ? GROUP BY type ORDER BY type")
                .param(playerId)
                .query((rs, n) -> new Summary(rs.getString("type"), rs.getLong("n"), rs.getTimestamp("last").toInstant()))
                .list();
    }
}
