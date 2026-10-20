package com.plinko.bo.game;

import com.plinko.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Review 3.7: every night, used launch tokens and expired sessions older than 31 days are deleted, in small chunks so
 * the tables are never locked for long (the database itself refuses anything younger than 30 days). Rounds and wallet
 * transactions are never deleted here: how long they are kept, and archiving them, waits for compliance (D6).
 */
@Service
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);
    private static final int CHUNK = 5000;

    private final GameRepository repository;

    public Housekeeping(GameRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "UTC")
    public void cleanUp() {
        repository.whileLocked("plinko_housekeeping", 0, () -> {
            int tokens = drain(repository::deleteOldTokens);
            int sessions = drain(repository::deleteOldSessions);
            if (tokens + sessions > 0) {
                log.info("Housekeeping: removed {} used launch token(s) and {} expired session(s) older than 31 days", tokens, sessions);
            }
        });
    }

    private static int drain(java.util.function.IntUnaryOperator delete) {
        int total = 0;
        for (int round = 0; round < 1000; round++) {
            int removed = delete.applyAsInt(CHUNK);
            total += removed;
            if (removed < CHUNK) {
                break;
            }
        }
        return total;
    }
}
