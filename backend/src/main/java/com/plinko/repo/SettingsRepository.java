package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code setting} table and the kept copies of recorded settings ({@code settings_snapshot}). */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> values() {
        return read("SELECT name, value FROM setting");
    }

    /** The settings, with the rows (and the gaps between them) locked until the transaction ends. */
    public Map<String, String> lockedValues() {
        return read("SELECT name, value FROM setting FOR UPDATE");
    }

    /** The settings version: raised by every change made through the application (see V14). */
    public long version() {
        return jdbc.sql("SELECT version FROM settings_version WHERE id = 1").query(Long.class).optional().orElse(0L);
    }

    /** Raises the settings version; called in the same transaction as the change it announces. */
    public void raiseVersion() {
        jdbc.sql("UPDATE settings_version SET version = version + 1 WHERE id = 1").update();
    }

    public void set(String name, String value) {
        jdbc.sql("INSERT INTO setting (name, value, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)")
                .params(name, value, Timestamp.from(Instant.now()))
                .update();
    }

    public void delete(String name) {
        jdbc.sql("DELETE FROM setting WHERE name = ?").param(name).update();
    }

    /** Kept copies with this fingerprint, newest first (the caller checks each against it). */
    public List<String> snapshots(String fingerprint) {
        return jdbc.sql("SELECT content FROM settings_snapshot WHERE fingerprint = ? ORDER BY id DESC")
                .param(fingerprint)
                .query(String.class).list();
    }

    public void insertSnapshot(String fingerprint, String content) {
        jdbc.sql("INSERT INTO settings_snapshot (fingerprint, content, created_at) VALUES (?, ?, ?)")
                .params(fingerprint, content, Timestamp.from(Instant.now()))
                .update();
    }

    private Map<String, String> read(String query) {
        return jdbc.sql(query)
                .query((rs, n) -> Map.entry(rs.getString("name"), rs.getString("value")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
