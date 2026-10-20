package com.plinko.bo.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.plinko.common.Crypto;
import com.plinko.repo.AuditRepository;
import org.springframework.stereotype.Service;

/**
 * Append-only audit log. Each entry's hash covers the previous entry's hash, so changing or
 * deleting any row breaks the chain and {@link #verifyChain()} reports where.
 */
@Service
public class AuditService {

    /** A lock rather than synchronized, so a virtual thread that waits for I/O inside it does not hold up its carrier thread. */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private static final String GENESIS = "0".repeat(64);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /** Result of a chain check; brokenAtId is null when the chain is intact. */
    public record ChainStatus(long entries, Long brokenAtId) {
        public boolean intact() {
            return brokenAtId == null;
        }
    }

    /** Writes an entry at once (it is kept even if the caller's transaction rolls back). */
    public void log(String username, String action, String details) {
        lock.lock();
        try {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            repository.appendChained(username, action, details, now, GENESIS, prevHash -> hash(prevHash, username, action, details, now));
        } finally {
            lock.unlock();
        }
    }

    /** True if an entry with this action was ever written (one-time setup steps are recorded this way). */
    public boolean happened(String action) {
        return repository.hasAction(action);
    }

    /** Where the background check got to: the last entry checked, its hash, how many were checked, and any break found. */
    private record Checked(long lastId, String lastHash, long entries, Long brokenAtId) {
    }

    private volatile Checked checked;
    private volatile java.time.Instant checkedReadAt = java.time.Instant.EPOCH;
    private static final String CHAIN = "audit_chain.checked";
    private static final String CHAIN_FULL = "audit_chain.full";

    /**
     * N10: where the check got to is kept in the database and the check runs on one instance at a time, so instances
     * that start with the load don't each re-read the whole log (optional: absent in unit tests, which check locally).
     */
    private com.plinko.repo.JobState state;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJobState(com.plinko.repo.JobState state) {
        this.state = state;
    }

    /** What any instance last found, from the database (at most once a minute); null if never checked. */
    private Checked shared() {
        if (state == null) {
            return checked;
        }
        if (checked == null || checkedReadAt.isBefore(java.time.Instant.now().minusSeconds(60))) {
            state.get(CHAIN).map(AuditService::parse).ifPresent(c -> checked = c);
            checkedReadAt = java.time.Instant.now();
        }
        return checked;
    }

    private void store(Checked now) {
        checked = now;
        if (state != null) {
            state.put(CHAIN, now.lastId() + "|" + now.lastHash() + "|" + now.entries() + "|" + (now.brokenAtId() == null ? "" : now.brokenAtId()));
            checkedReadAt = java.time.Instant.now();
        }
    }

    private static Checked parse(String value) {
        String[] parts = value.split("\\|", -1);
        return new Checked(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]), parts[3].isEmpty() ? null : Long.valueOf(parts[3]));
    }

    /** Runs a check here, unless another instance is running one (then its result is read from the database). */
    private void alone(Runnable check) {
        if (state == null) {
            check.run();
        } else {
            state.runAlone("audit_chain", check);
        }
    }

    /**
     * The chain as the background check last found it (review 3.2: the dashboard shows this instead of re-reading the
     * whole log on every refresh). Checked fully on first use and once a day, and the new entries every 10 minutes.
     */
    public ChainStatus chainStatus() {
        Checked now = shared();
        if (now == null) {
            verifyAll();
            now = shared();
            if (now == null) {
                // another instance is checking it right now
                return new ChainStatus(0, null);
            }
        }
        return new ChainStatus(now.entries(), now.brokenAtId());
    }

    /** Checks only the entries added since the last check, continuing the chain from the last checked hash. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void verifyNew() {
        alone(this::checkNew);
    }

    private void checkNew() {
        checkedReadAt = java.time.Instant.EPOCH;
        Checked from = shared();
        if (from == null || from.brokenAtId() != null) {
            if (from == null) {
                checkAll();
            }
            return;
        }
        String[] expectedPrev = {from.lastHash()};
        long[] last = {from.lastId()};
        long[] count = {from.entries()};
        Long[] broken = {null};
        repository.forEachAfter(from.lastId(), entry -> {
            if (broken[0] != null) {
                return;
            }
            count[0]++;
            last[0] = entry.id();
            String recomputed = hash(entry.prevHash(), entry.username(), entry.action(), entry.details(), entry.createdAt());
            if (!entry.prevHash().equals(expectedPrev[0]) || !Crypto.safeEquals(entry.hash(), recomputed)) {
                broken[0] = entry.id();
            }
            expectedPrev[0] = entry.hash();
        });
        store(new Checked(last[0], expectedPrev[0], count[0], broken[0]));
    }

    /** The whole chain, once a day (an edit of an old entry is found within a day; the audit page checks it fully on every view). */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 86_400_000, initialDelay = 86_400_000)
    public void verifyAll() {
        alone(() -> {
            boolean recent = state != null && state.get(CHAIN_FULL)
                    .map(at -> java.time.Instant.ofEpochMilli(Long.parseLong(at)).isAfter(java.time.Instant.now().minus(java.time.Duration.ofHours(20))))
                    .orElse(false);
            if (!recent || shared() == null) {
                checkAll();
            }
        });
    }

    private void checkAll() {
        String[] expectedPrev = {GENESIS};
        long[] last = {0};
        long[] count = {0};
        Long[] broken = {null};
        repository.forEachInOrder(entry -> {
            if (broken[0] != null) {
                return;
            }
            count[0]++;
            last[0] = entry.id();
            String recomputed = hash(entry.prevHash(), entry.username(), entry.action(), entry.details(), entry.createdAt());
            if (!entry.prevHash().equals(expectedPrev[0]) || !Crypto.safeEquals(entry.hash(), recomputed)) {
                broken[0] = entry.id();
            }
            expectedPrev[0] = entry.hash();
        });
        store(new Checked(last[0], expectedPrev[0], count[0], broken[0]));
        if (state != null) {
            state.put(CHAIN_FULL, String.valueOf(java.time.Instant.now().toEpochMilli()));
        }
    }

    /** The whole chain checked now (the audit page, opened on purpose); also refreshes what the dashboard shows. */
    public ChainStatus verifyChain() {
        checkAll();
        Checked now = checked;
        return new ChainStatus(now.entries(), now.brokenAtId());
    }

    private static String hash(String prevHash, String username, String action, String details, Instant createdAt) {
        return Crypto.sha256Hex(String.join("|", prevHash, username, action, details == null ? "" : details,
                Long.toString(createdAt.toEpochMilli())));
    }
}
