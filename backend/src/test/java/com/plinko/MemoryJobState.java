package com.plinko;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.plinko.repo.JobState;

/** The background jobs' shared state, in memory: what several instances would share through the database. */
public class MemoryJobState extends JobState {

    public final Map<String, String> values = new ConcurrentHashMap<>();
    /** Set to false to act as if another instance were running the job. */
    public volatile boolean free = true;

    public MemoryJobState() {
        super(null, null);
    }

    @Override
    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    @Override
    public void put(String name, String value) {
        values.put(name, value);
    }

    @Override
    public boolean runAlone(String job, Runnable work) {
        if (!free) {
            return false;
        }
        work.run();
        return true;
    }
}
