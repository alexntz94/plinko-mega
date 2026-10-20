package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.plinko.bo.odds.OddsService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.RoundStatus;
import com.plinko.repo.StatsRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The state of the game for monitoring (review 7.10), refreshed every 30 seconds (a few small queries on indexes):
 * rounds waiting on the wallet and how long the oldest has waited, blocked rounds, rounds whose retries stopped,
 * integrity alarms, and the RTP actually paid over the last hour and today. Two conditions nothing else reports are
 * written to the log as "ALERT ..." lines, once each, for the log-based alerts: a payment waiting more than 10 minutes,
 * and today's measured RTP outside the hard limits (with a margin, once there are enough rounds to judge).
 */
@Component
public class GameMetrics {

    private static final Logger log = LoggerFactory.getLogger(GameMetrics.class);
    static final Duration PENDING_ALERT = Duration.ofMinutes(10);
    static final long RTP_MIN_ROUNDS = 50_000;
    static final BigDecimal RTP_MARGIN = new BigDecimal("5");

    private record State(double pending, double oldestPendingSeconds, double tampered, double retriesStopped, double integrityAlarms,
                         double rtpHour, double rtpToday, double roundsToday) {
    }

    private final StatsRepository stats;
    private final RoundRecoveryService recovery;
    private final RoundIntegrityScanner scanner;
    private final OddsService odds;
    private final SettingsService settings;
    private final PlinkoProperties.Limits limits;
    private final AtomicReference<State> state = new AtomicReference<>(new State(0, 0, 0, 0, 0, Double.NaN, Double.NaN, 0));
    private String alertedPending;
    private String alertedRtp;

    public GameMetrics(StatsRepository stats, RoundRecoveryService recovery, RoundIntegrityScanner scanner, OddsService odds,
                       SettingsService settings, PlinkoProperties properties, MeterRegistry registry) {
        this.stats = stats;
        this.recovery = recovery;
        this.scanner = scanner;
        this.odds = odds;
        this.settings = settings;
        this.limits = properties.limits();
        gauge(registry, "plinko.rounds.pending", "Rounds waiting for a credit or a refund", s -> s.pending());
        gauge(registry, "plinko.rounds.pending.oldest.seconds", "How long the oldest of them has waited", s -> s.oldestPendingSeconds());
        gauge(registry, "plinko.rounds.tampered", "Rounds blocked by an integrity check (never paid)", s -> s.tampered());
        gauge(registry, "plinko.rounds.retries.stopped", "Rounds still owed a payment whose automatic retries stopped", s -> s.retriesStopped());
        gauge(registry, "plinko.integrity.alarms", "Integrity alarms shown in the admin panel", s -> s.integrityAlarms());
        Gauge.builder("plinko.rtp.measured", state, r -> r.get().rtpHour()).tag("window", "1h")
                .description("Wins / bets of the rounds played in the last hour, in percent").register(registry);
        Gauge.builder("plinko.rtp.measured", state, r -> r.get().rtpToday()).tag("window", "today")
                .description("Wins / bets of today's rounds (reporting time zone), in percent").register(registry);
        gauge(registry, "plinko.rounds.today", "Rounds played today (reporting time zone)", s -> s.roundsToday());
    }

    private void gauge(MeterRegistry registry, String name, String description, java.util.function.ToDoubleFunction<State> value) {
        Gauge.builder(name, state, r -> value.applyAsDouble(r.get())).description(description).register(registry);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void refresh() {
        try {
            Instant now = Instant.now();
            Instant oldest = stats.oldestPending().orElse(null);
            List<String> stopped = recovery.stoppedRounds();
            int alarms = odds.problems().size() + scanner.invalidRounds().size() + (settings.trusted() ? 0 : 1) + (stopped.isEmpty() ? 0 : 1);
            StatsRepository.Totals hour = stats.since(now.minus(Duration.ofHours(1)));
            StatsRepository.Totals today = stats.day(stats.today());
            state.set(new State(stats.countByStatus(List.of(RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING)),
                    oldest == null ? 0 : Duration.between(oldest, now).toSeconds(),
                    stats.countByStatus(List.of(RoundStatus.TAMPERED)), stopped.size(), alarms,
                    rtp(hour), rtp(today), today.rounds()));
            alertIfPendingTooLong(oldest, now);
            alertIfRtpOutOfRange(today);
        } catch (RuntimeException e) {
            log.warn("Could not refresh the game metrics: {}", e.toString());
        }
    }

    private static double rtp(StatsRepository.Totals totals) {
        return totals.bet().signum() == 0 ? Double.NaN
                : totals.win().multiply(BigDecimal.valueOf(100)).divide(totals.bet(), MathContext.DECIMAL64).doubleValue();
    }

    private void alertIfPendingTooLong(Instant oldest, Instant now) {
        if (oldest == null || Duration.between(oldest, now).compareTo(PENDING_ALERT) < 0) {
            alertedPending = null;
            return;
        }
        String key = oldest.toString();
        if (!key.equals(alertedPending)) {
            alertedPending = key;
            log.error("ALERT PENDING_PAYMENT_OLD: a round has waited {} minutes for its credit or refund; check the wallet and Rounds",
                    Duration.between(oldest, now).toMinutes());
        }
    }

    private void alertIfRtpOutOfRange(StatsRepository.Totals today) {
        if (today.rounds() < RTP_MIN_ROUNDS || today.bet().signum() == 0) {
            return;
        }
        BigDecimal measured = today.win().multiply(BigDecimal.valueOf(100)).divide(today.bet(), MathContext.DECIMAL64);
        String direction = measured.compareTo(limits.maxRtp().add(RTP_MARGIN)) > 0 ? "above"
                : measured.compareTo(limits.minRtp().subtract(RTP_MARGIN)) < 0 ? "below" : null;
        String key = direction == null ? null : stats.today() + direction;
        if (key != null && !key.equals(alertedRtp)) {
            log.error("ALERT RTP_OUT_OF_RANGE: today's {} rounds paid {}% , {} the hard limits {}%-{}% (by more than {} points)",
                    today.rounds(), measured.setScale(2, java.math.RoundingMode.HALF_UP), direction, limits.minRtp(), limits.maxRtp(), RTP_MARGIN);
        }
        alertedRtp = key;
    }
}
