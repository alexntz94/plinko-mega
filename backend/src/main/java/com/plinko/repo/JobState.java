package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Progress and results of the background checks, shared by every backend instance (N10): a job runs on one instance at
 * a time (a database lock) and keeps where it got to here, so instances that start and stop with the load neither repeat
 * it nor show only their own part of it.
 */
@Repository
public class JobState {

    private final JdbcClient jdbc;
    private final GameRepository game;

    public JobState(JdbcClient jdbc, GameRepository game) {
        this.jdbc = jdbc;
        this.game = game;
    }

    public Optional<String> get(String name) {
        return jdbc.sql("SELECT value FROM job_state WHERE name = ?").param(name).query(String.class).optional();
    }

    public void put(String name, String value) {
        jdbc.sql("INSERT INTO job_state (name, value, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)")
                .params(name, value, Timestamp.from(Instant.now()))
                .update();
    }

    /** Stores the value unless one is stored already (the first instance to get there wins); returns the stored value. */
    public String putIfAbsent(String name, String value) {
        jdbc.sql("INSERT INTO job_state (name, value, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE name = name")
                .params(name, value, Timestamp.from(Instant.now()))
                .update();
        return get(name).orElse(value);
    }

    /** Runs the job if no other instance is running it now; false if one is. */
    public boolean runAlone(String job, Runnable work) {
        return game.whileLocked("plinko_job_" + job, 0, work);
    }
}
