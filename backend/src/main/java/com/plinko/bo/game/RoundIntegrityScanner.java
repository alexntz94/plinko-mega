package com.plinko.bo.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.audit.AuditService;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import com.plinko.repo.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-checks rounds that have an outcome: their signature, and their win worked out again from the stored balls within
 * their limit ({@link WinCheck#outcomeProblem}). A "winner" added to the database by hand (which never moved money, but
 * would show in reports) fails and raises the integrity alarm in the admin panel and the audit log.
 * <p>
 * Review 3.6: every hour only the rounds played since the last pass (up to 10 minutes ago, so rounds still being played
 * are left for the next one); every round once a day at a quiet hour, and at start-up only if no full scan finished in the
 * last day. The database already freezes a decided round, so the full scan is the second line of defence.
 * <p>
 * N10: one instance at a time runs a pass (a database lock), and where it got to and what it found are kept in the
 * database, so instances that start with the load neither repeat the full scan nor show different alarms.
 */
@Component
public class RoundIntegrityScanner {

    private static final Logger log = LoggerFactory.getLogger(RoundIntegrityScanner.class);
    private static final int PAGE = 1000;
    private static final String CHECKPOINT = "round_scan.checkpoint";
    private static final String FOUND = "round_scan.invalid";
    /** N14.5: rounds whose odds version can't be checked (missing, or a key this server doesn't hold): a setup problem. */
    private static final String UNREADABLE = "round_scan.unreadable";
    private static final String FULL = "round_scan.full";
    private static final java.time.Duration SETTLE = java.time.Duration.ofMinutes(10);
    private static final java.time.Duration FULL_EVERY = java.time.Duration.ofHours(20);
    private static final java.time.Duration REREAD = java.time.Duration.ofMinutes(1);

    private final GameRepository repository;
    private final WinCheck winCheck;
    private final AuditService audit;
    private final JobState state;
    private volatile List<String> invalid = List.of();
    private volatile java.time.Instant readAt = java.time.Instant.EPOCH;

    public RoundIntegrityScanner(GameRepository repository, WinCheck winCheck, AuditService audit, JobState state) {
        this.repository = repository;
        this.winCheck = winCheck;
        this.audit = audit;
        this.state = state;
    }

    /** Ids of rounds with an outcome that fails its signature or win check, as of the last scan (by any instance). */
    public List<String> invalidRounds() {
        if (readAt.isBefore(java.time.Instant.now().minus(REREAD))) {
            try {
                invalid = ids(state.get(FOUND).orElse(""));
                unreadable = ids(state.get(UNREADABLE).orElse(""));
                checkedUntil = checkpointTime(state.get(CHECKPOINT));
                readAt = java.time.Instant.now();
            } catch (RuntimeException e) {
                log.warn("Could not read the round scan's results: {}", e.toString());
            }
        }
        return invalid;
    }

    /** Rounds played before this were checked by the last pass (by any instance); empty before the first pass. */
    public Optional<java.time.Instant> checkedUntil() {
        invalidRounds();
        return Optional.ofNullable(checkedUntil);
    }

    private volatile java.time.Instant checkedUntil;

    /** Reads where the passes got to: the checkpoint is "played-at millis|round id". */
    private static java.time.Instant checkpointTime(Optional<String> checkpoint) {
        try {
            return checkpoint.map(c -> java.time.Instant.ofEpochMilli(Long.parseLong(c.split("\\|", 2)[0]))).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Review 3.10d: the admin lists' signature column, from the scan's results instead of verifying every listed row
     * (which would need its long balls and multipliers text). One snapshot per page.
     */
    public Verdicts verdicts() {
        List<String> found = invalidRounds();
        return new Verdicts(java.util.Set.copyOf(found), java.util.Set.copyOf(unreadable), checkedUntil);
    }

    /** The scan's verdict per round, as of its last pass. */
    public record Verdicts(java.util.Set<String> invalid, java.util.Set<String> unreadable, java.time.Instant checkedUntil) {

        public static final String INVALID = "INVALID";
        public static final String BLOCKED = "blocked";
        public static final String CHECKED = "checked";
        public static final String NOT_YET = "not yet checked";
        public static final String UNREADABLE = "odds version unreadable";

        /**
         * INVALID: the scan found its outcome fails its signature or win check; blocked: TAMPERED (it failed a check and is
         * never paid); checked: it passed the last pass; not yet checked: played after it; "" for a round with no outcome.
         */
        public String of(String roundId, String status, boolean hasOutcome, java.time.Instant playedAt) {
            if (RoundStatus.TAMPERED.equals(status)) {
                return BLOCKED;
            }
            if (invalid.contains(roundId)) {
                return INVALID;
            }
            if (!hasOutcome) {
                return "";
            }
            if (unreadable.contains(roundId)) {
                return UNREADABLE;
            }
            return checkedUntil != null && playedAt != null && playedAt.isBefore(checkedUntil) ? CHECKED : NOT_YET;
        }

        public String of(com.plinko.repo.AdminListRepository.RoundSummary round) {
            return of(round.id(), round.status(), round.hasOutcome(), round.createdAt());
        }

        /** True for a verdict the lists show as a problem. */
        public boolean bad(String verdict) {
            return INVALID.equals(verdict) || BLOCKED.equals(verdict);
        }
    }

    /** Rounds whose odds version can't be checked, as of the last scan: a setup problem (keys), not data changed. */
    public List<String> unreadableRounds() {
        invalidRounds();
        return unreadable;
    }

    private volatile List<String> unreadable = List.of();

    /** Sorts a round into the found (changed) or unreadable (setup) list; true if it was changed. */
    private boolean check(GameRepository.Round round, List<String> found, List<String> unreadableNow) {
        if (RoundStatus.TAMPERED.equals(round.status()) || found.contains(round.id()) || unreadableNow.contains(round.id())) {
            return false;
        }
        String problem = winCheck.outcomeProblem(round);
        if (WinCheck.oddsUnreadable(problem)) {
            unreadableNow.add(round.id());
            return false;
        }
        if (problem != null) {
            found.add(round.id());
            return true;
        }
        return false;
    }

    /** The rounds played since the last pass; a full scan first if there has never been one. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void scanNew() {
        state.runAlone("round_scan", () -> {
            Optional<String> checkpoint = state.get(CHECKPOINT);
            if (checkpoint.isEmpty()) {
                fullScan();
                return;
            }
            java.time.Instant scannedPlayed = java.time.Instant.ofEpochMilli(Long.parseLong(checkpoint.get().split("\\|", 2)[0]));
            String scannedId = checkpoint.get().split("\\|", 2)[1];
            java.time.Instant until = java.time.Instant.now().minus(SETTLE);
            List<String> before = ids(state.get(FOUND).orElse(""));
            // N14.3: rounds blocked since the last pass are no longer an open alarm
            List<String> found = new ArrayList<>(before.stream()
                    .filter(id -> repository.findRound(id).map(r -> !RoundStatus.TAMPERED.equals(r.status())).orElse(false)).toList());
            int kept = found.size();
            // unreadable ones are checked again each pass: they clear once the keys are right
            List<String> unreadableNow = new ArrayList<>();
            for (String id : ids(state.get(UNREADABLE).orElse(""))) {
                repository.findRound(id).ifPresent(r -> check(r, found, unreadableNow));
            }
            while (true) {
                List<GameRepository.Round> page = repository.decidedRoundsPlayed(scannedPlayed, scannedId, until, PAGE);
                for (GameRepository.Round round : page) {
                    check(round, found, unreadableNow);
                }
                if (page.isEmpty()) {
                    break;
                }
                GameRepository.Round last = page.get(page.size() - 1);
                scannedPlayed = last.createdAt();
                scannedId = last.id();
            }
            state.put(CHECKPOINT, scannedPlayed.toEpochMilli() + "|" + scannedId);
            reportUnreadable(unreadableNow);
            report(found, found.size() > kept);
        });
    }

    /** Every round, every night at 03:30 UTC (once, whichever instance gets there first). */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void scanAll() {
        state.runAlone("round_scan", () -> {
            if (!fullScanDue()) {
                return;
            }
            fullScan();
        });
    }

    /** At start-up only if no instance finished a full scan in the last day (instances start and stop with the load). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void scanAtStart() {
        Thread.ofVirtual().name("round-scan").start(this::scanAll);
    }

    /** A full scan now, unless another instance is running one (the admin's button, tests). */
    public void scan() {
        state.runAlone("round_scan", this::fullScan);
    }

    private boolean fullScanDue() {
        return state.get(FULL).map(at -> java.time.Instant.ofEpochMilli(Long.parseLong(at)).isBefore(java.time.Instant.now().minus(FULL_EVERY)))
                .orElse(true);
    }

    private void fullScan() {
        java.time.Instant until = java.time.Instant.now().minus(SETTLE);
        List<String> found = new ArrayList<>();
        List<String> unreadableNow = new ArrayList<>();
        String after = "";
        while (true) {
            List<GameRepository.Round> page = repository.decidedRoundsAfter(after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (GameRepository.Round round : page) {
                check(round, found, unreadableNow);
            }
            after = page.get(page.size() - 1).id();
        }
        List<String> before = ids(state.get(FOUND).orElse(""));
        // the hourly passes continue from here (rounds still being played are left for them)
        state.put(CHECKPOINT, until.toEpochMilli() + "|");
        state.put(FULL, String.valueOf(java.time.Instant.now().toEpochMilli()));
        reportUnreadable(unreadableNow);
        report(found, !found.isEmpty() && !before.containsAll(found));
    }

    private void reportUnreadable(List<String> now) {
        List<String> before = ids(state.get(UNREADABLE).orElse(""));
        state.put(UNREADABLE, String.join(",", now));
        unreadable = List.copyOf(now);
        if (!now.isEmpty() && !before.containsAll(now)) {
            log.error("ALERT ODDS_VERSION_UNREADABLE: {} round(s) whose odds version can't be checked (missing, or signed with a key this "
                    + "server doesn't hold): check the key lists", now.size());
        }
    }

    private void report(List<String> found, boolean newProblems) {
        state.put(FOUND, String.join(",", found));
        invalid = List.copyOf(found);
        checkedUntil = checkpointTime(state.get(CHECKPOINT));
        readAt = java.time.Instant.now();
        if (newProblems) {
            log.error("ROUND INTEGRITY: {} round(s) whose outcome fails its signature or win check", found.size());
            audit.log("system", "ROUNDS_INTEGRITY_FAILED", found.size() + " round(s) whose outcome fails its signature or win check, e.g. "
                    + found.subList(0, Math.min(5, found.size())));
        }
    }

    private static List<String> ids(String joined) {
        return joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }
}
