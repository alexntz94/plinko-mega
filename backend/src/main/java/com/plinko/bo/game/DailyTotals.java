package com.plinko.bo.game;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.plinko.repo.GameRepository;
import com.plinko.repo.StatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the daily totals (round_daily_totals, round_daily_players) up to date, so the dashboard and Finance never add
 * up the whole round history (review 3.2). Today's totals are updated incrementally (N4 #2): each round writes its
 * increment in its own last transaction (insert-only), and this job adds them every minute. The full rebuild stays for
 * closed days and repairs: yesterday just after midnight, today at start-up, 15 minutes later and every hour, and every day marked as
 * changed (a round blocked or refunded after the fact; N5), so no day is skipped for good and no player request waits on
 * a rebuild. One instance at a time does it, reading the rounds without locking them (N4).
 */
@Service
public class DailyTotals {

    private static final Logger log = LoggerFactory.getLogger(DailyTotals.class);
    private static final String LOCK = "plinko_daily_totals";

    private final StatsRepository stats;
    private final GameRepository game;
    private final TransactionTemplate transaction;

    private final com.plinko.common.ReportingZone zone;
    private volatile boolean zoneChecked;
    private int minutes;

    public DailyTotals(StatsRepository stats, GameRepository game, PlatformTransactionManager transactions) {
        this(stats, game, transactions, com.plinko.common.ReportingZone.utc());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DailyTotals(StatsRepository stats, GameRepository game, PlatformTransactionManager transactions, com.plinko.common.ReportingZone zone) {
        this.zone = zone;
        this.stats = stats;
        this.game = game;
        this.transaction = new TransactionTemplate(transactions);
        // the rebuild reads the rounds and the day's increments in one snapshot (REPEATABLE READ), with plain SELECTs, which
        // take no locks on the rounds while live rounds are written (N4)
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refreshRecent() {
        LocalDate today = zone.today();
        game.whileLocked(LOCK, 0, () -> {
            if (!zoneChecked) {
                rebuildAllIfZoneChanged();
                fillPlayerTotalsOnce();
                zoneChecked = true;
            }
            // N4 #2: today's totals are updated incrementally: every round's increment is added here, each minute
            fold();
            // the full rebuild stays for closed days and repairs: yesterday at start-up and in the first 15 minutes of the
            // day (while its last rounds finish), today at start-up and every hour, and every day marked as changed
            boolean justAfterMidnight = java.time.Instant.now().isBefore(zone.start(today).plus(java.time.Duration.ofMinutes(15)));
            if (minutes == 0 || justAfterMidnight) {
                rebuild(today.minusDays(1));
            }
            // R2: and once more 15 minutes after start-up, when a rolling deploy is over: until then the previous release
            // (before N4 #2) rebuilt today every minute ignoring the increments, which this release then added again
            if (minutes % 60 == 0 || minutes == AFTER_ROLLOUT) {
                rebuild(today);
            }
            minutes++;
            rebuildChangedDays();
        });
    }

    /** Minutes after start-up when today is rebuilt again, once the previous release has stopped (R2). */
    static final int AFTER_ROLLOUT = 15;

    /** Adds every waiting increment to the daily totals, a batch per transaction. */
    private void fold() {
        try {
            int added;
            do {
                added = transaction.execute(status -> stats.foldDeltas(FOLD_BATCH));
            } while (added == FOLD_BATCH);
        } catch (RuntimeException e) {
            log.error("Could not add the rounds' increments to the daily totals; they are added next minute", e);
        }
    }

    private static final int FOLD_BATCH = 5000;

    /** Rebuilds the days marked as changed; a day that fails stays marked and is tried again next minute. */
    private void rebuildChangedDays() {
        for (java.util.Map.Entry<LocalDate, Instant> changed : stats.changedDays(20)) {
            if (rebuild(changed.getKey())) {
                stats.clearDayChanged(changed.getKey(), changed.getValue());
            }
        }
    }

    /**
     * Marks the day a round was played on as changed (a round blocked or refunded after the fact, possibly days later):
     * one small write, never a rebuild in the caller's request. Never fails the caller.
     */
    public void roundSettled(Instant playedAt) {
        if (playedAt != null) {
            try {
                stats.markDayChanged(zone.dayOf(playedAt));
            } catch (RuntimeException e) {
                log.error("Could not mark the daily totals of {} as changed; they are corrected when the day is rebuilt", zone.dayOf(playedAt), e);
            }
        }
    }

    /**
     * Review 3.3: days built before each player's daily totals existed are marked once, so the minute job rebuilds them
     * (20 a minute) in the background; until then those days count no player totals in Finance's winners and losers.
     */
    private void fillPlayerTotalsOnce() {
        if (state == null || state.get(PLAYER_TOTALS).isPresent()) {
            return;
        }
        stats.firstRoundDay().ifPresent(first -> stats.markDaysChanged(first.minusDays(1), zone.today().minusDays(1)));
        state.put(PLAYER_TOTALS, "marked");
    }

    private static final String PLAYER_TOTALS = "daily_totals.player_totals";
    private com.plinko.repo.JobState state;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJobState(com.plinko.repo.JobState state) {
        this.state = state;
    }

    /**
     * Review 7.9: the daily totals were built with another time zone's days (UTC, before the reporting zone was set):
     * every day is rebuilt once, oldest first, and the zone recorded. Until then Finance shows the old days.
     */
    private void rebuildAllIfZoneChanged() {
        String built = stats.totalsZone();
        if (zone.name().equals(built == null ? "UTC" : built)) {
            if (built == null) {
                stats.setTotalsZone(zone.name());
            }
            return;
        }
        LocalDate first = stats.firstRoundDay().orElse(null);
        log.warn("Daily totals were built for {}; rebuilding every day for {} (from {})", built == null ? "UTC" : built, zone.name(), first);
        if (first != null) {
            transaction.executeWithoutResult(status -> stats.clearTotals());
            for (LocalDate day = first.minusDays(1); !day.isAfter(zone.today()); day = day.plusDays(1)) {
                rebuild(day);
            }
        }
        stats.setTotalsZone(zone.name());
    }

    private boolean rebuild(LocalDate day) {
        try {
            transaction.executeWithoutResult(status -> stats.rebuildDay(day));
            return true;
        } catch (RuntimeException e) {
            log.error("Could not rebuild the daily totals of {}", day, e);
            return false;
        }
    }
}
