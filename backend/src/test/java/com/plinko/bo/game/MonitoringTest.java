package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.plinko.NoTransactions;
import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import com.plinko.repo.StatsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Review 7.10 and 3.2: the monitoring gauges, the daily totals job and the hourly round scan. */
class MonitoringTest {

    private final StatsRepository stats = mock(StatsRepository.class);
    private final GameRepository game = mock(GameRepository.class);
    private final RoundRecoveryService recovery = mock(RoundRecoveryService.class);
    private final RoundIntegrityScanner scanner = mock(RoundIntegrityScanner.class);
    private final OddsService odds = mock(OddsService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private GameMetrics metrics;

    private static StatsRepository.Totals totals(long rounds, String bet, String win) {
        return new StatsRepository.Totals("x", rounds, 0, new BigDecimal(bet), new BigDecimal(win));
    }

    @BeforeEach
    void setUp() {
        when(stats.countByStatus(List.of(RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING))).thenReturn(3L);
        when(stats.countByStatus(List.of(RoundStatus.TAMPERED))).thenReturn(1L);
        when(stats.oldestPending()).thenReturn(Optional.of(Instant.now().minus(Duration.ofMinutes(2))));
        when(stats.since(any())).thenReturn(totals(1000, "1000.00", "961.50"));
        when(stats.day(any())).thenReturn(totals(20_000, "20000.00", "19100.00"));
        when(recovery.stoppedRounds()).thenReturn(List.of());
        when(scanner.invalidRounds()).thenReturn(List.of());
        when(odds.problems()).thenReturn(List.of());
        when(settings.trusted()).thenReturn(true);
        metrics = new GameMetrics(stats, recovery, scanner, odds, settings, TestFixtures.properties(), registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void theGaugesShowTheStateOfTheGame() {
        metrics.refresh();
        assertThat(gauge("plinko.rounds.pending")).isEqualTo(3);
        assertThat(gauge("plinko.rounds.pending.oldest.seconds")).isBetween(110.0, 130.0);
        assertThat(gauge("plinko.rounds.tampered")).isEqualTo(1);
        assertThat(gauge("plinko.integrity.alarms")).isZero();
        assertThat(registry.get("plinko.rtp.measured").tag("window", "1h").gauge().value()).isEqualTo(96.15);
        assertThat(registry.get("plinko.rtp.measured").tag("window", "today").gauge().value()).isEqualTo(95.5);
        assertThat(gauge("plinko.rounds.today")).isEqualTo(20_000);
    }

    @Test
    void integrityProblemsAreCounted() {
        when(odds.problems()).thenReturn(List.of("8 rows / High: changed"));
        when(scanner.invalidRounds()).thenReturn(List.of("r1", "r2"));
        when(settings.trusted()).thenReturn(false);
        when(recovery.stoppedRounds()).thenReturn(List.of("r3"));
        metrics.refresh();
        assertThat(gauge("plinko.integrity.alarms")).isEqualTo(5);
        assertThat(gauge("plinko.rounds.retries.stopped")).isEqualTo(1);
    }

    @Test
    void noBetsMeansNoMeasuredRtpRatherThanZero() {
        when(stats.since(any())).thenReturn(totals(0, "0", "0"));
        metrics.refresh();
        assertThat(registry.get("plinko.rtp.measured").tag("window", "1h").gauge().value()).isNaN();
    }

    @Test
    void aFailedRefreshKeepsTheLastValues() {
        metrics.refresh();
        when(stats.countByStatus(any())).thenThrow(new IllegalStateException("database down"));
        metrics.refresh();
        assertThat(gauge("plinko.rounds.pending")).isEqualTo(3);
    }

    @Test
    void theAlertConditionsAreRecognised() {
        // waiting 15 minutes, and today's RTP far above the hard limit with enough rounds: both alert (once each)
        when(stats.oldestPending()).thenReturn(Optional.of(Instant.now().minus(Duration.ofMinutes(15))));
        when(stats.day(any())).thenReturn(totals(60_000, "60000.00", "66000.00"));
        metrics.refresh();
        metrics.refresh();
        assertThat(gauge("plinko.rounds.pending.oldest.seconds")).isGreaterThan(GameMetrics.PENDING_ALERT.toSeconds());
        assertThat(registry.get("plinko.rtp.measured").tag("window", "today").gauge().value()).isEqualTo(110.0);
        // too few rounds to judge: no alert however far off
        when(stats.day(any())).thenReturn(totals(100, "100.00", "300.00"));
        when(stats.oldestPending()).thenReturn(Optional.empty());
        metrics.refresh();
        assertThat(gauge("plinko.rounds.pending.oldest.seconds")).isZero();
    }

    // ---------------------------------------------------------------- daily totals (3.2)

    @Test
    void theDailyTotalsRebuildTodayAndYesterdayUnderTheirLock() {
        when(game.whileLocked(eq("plinko_daily_totals"), anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        DailyTotals daily = new DailyTotals(stats, game, new NoTransactions());
        daily.refreshRecent();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        verify(stats).rebuildDay(today);
        verify(stats).rebuildDay(today.minusDays(1));
        // N4 #2: after that, today's increments are added each minute; today is rebuilt again only as the hourly repair,
        // yesterday only in the first 15 minutes of the day
        daily.refreshRecent();
        boolean justAfterMidnight = java.time.Instant.now().isBefore(today.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(900));
        verify(stats, times(1)).rebuildDay(today);
        verify(stats, times(justAfterMidnight ? 2 : 1)).rebuildDay(today.minusDays(1));
        verify(stats, times(2)).foldDeltas(anyInt());
    }

    @Test
    void aRoundSettledLateOnlyMarksItsDayAndTheMinuteJobRebuildsIt() {
        when(game.whileLocked(eq("plinko_daily_totals"), anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        DailyTotals daily = new DailyTotals(stats, game, new NoTransactions());
        LocalDate old = LocalDate.of(2026, 10, 1);
        daily.roundSettled(old.atTime(23, 30).toInstant(ZoneOffset.UTC));
        daily.roundSettled(null);
        verify(stats).markDayChanged(old);
        verify(stats, never()).rebuildDay(any());
        verify(game, never()).whileLocked(anyString(), anyInt(), any());

        java.time.Instant marked = java.time.Instant.now();
        when(stats.changedDays(anyInt())).thenReturn(java.util.List.of(java.util.Map.entry(old, marked)));
        daily.refreshRecent();
        verify(stats).rebuildDay(old);
        verify(stats).clearDayChanged(old, marked);
    }

    @Test
    void aChangedDayThatFailsToRebuildStaysMarked() {
        when(game.whileLocked(eq("plinko_daily_totals"), anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        LocalDate old = LocalDate.of(2026, 10, 1);
        when(stats.changedDays(anyInt())).thenReturn(java.util.List.of(java.util.Map.entry(old, java.time.Instant.now())));
        org.mockito.Mockito.doThrow(new IllegalStateException("deadlock")).when(stats).rebuildDay(old);
        new DailyTotals(stats, game, new NoTransactions()).refreshRecent();
        verify(stats, never()).clearDayChanged(any(), any());
    }

    @Test
    void aFailedDayRebuildDoesNotStopTheOthers() {
        when(game.whileLocked(eq("plinko_daily_totals"), anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        org.mockito.Mockito.doThrow(new IllegalStateException("deadlock")).when(stats).rebuildDay(today.minusDays(1));
        new DailyTotals(stats, game, new NoTransactions()).refreshRecent();
        verify(stats).rebuildDay(today);
    }

    @Test
    void aNewReportingTimeZoneRebuildsEveryDayOnce() {
        when(game.whileLocked(eq("plinko_daily_totals"), anyInt(), any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        com.plinko.common.ReportingZone malta = new com.plinko.common.ReportingZone("Europe/Malta");
        when(stats.totalsZone()).thenReturn(null);   // built before review 7.9: UTC days
        when(stats.firstRoundDay()).thenReturn(Optional.of(malta.today().minusDays(3)));
        DailyTotals daily = new DailyTotals(stats, game, new NoTransactions(), malta);
        daily.refreshRecent();
        verify(stats).clearTotals();
        verify(stats).rebuildDay(malta.today().minusDays(4));
        verify(stats, times(2)).rebuildDay(malta.today());   // once in the full rebuild, once as today
        verify(stats).setTotalsZone("Europe/Malta");
        daily.refreshRecent();
        verify(stats, times(1)).clearTotals();
    }

    // ---------------------------------------------------------------- the hourly scan

    @Test
    void theHourlyScanFlagsRoundsThatFailTheirCheckOnceInTheAuditLog() {
        RoundSigner signer = new RoundSigner(TestFixtures.properties());
        WinCheck check = new WinCheck(signer, new WalletSigner(new Integrity(TestFixtures.properties())), odds, TestFixtures.properties());
        AuditService audit = mock(AuditService.class);
        BigDecimal win = new BigDecimal("1.50");
        String signature = signer.sign("a", 7, 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, win, "EUR", "0", "1.5");
        GameRepository.Round good = new GameRepository.Round("a", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, win, "EUR",
                RoundStatus.COMPLETED, "0", "1.5", null, signature, null, null);
        GameRepository.Round forged = new GameRepository.Round("b", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, new BigDecimal("900"),
                "EUR", RoundStatus.COMPLETED, "0", "900", null, "f".repeat(64), null, null);
        GameRepository.Round blocked = new GameRepository.Round("c", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, new BigDecimal("900"),
                "EUR", RoundStatus.TAMPERED, "0", "900", null, "f".repeat(64), null, null);
        when(game.decidedRoundsAfter("", 1000)).thenReturn(List.of(good, forged, blocked));
        when(game.decidedRoundsAfter("c", 1000)).thenReturn(List.of());
        com.plinko.MemoryJobState state = new com.plinko.MemoryJobState();
        RoundIntegrityScanner scan = new RoundIntegrityScanner(game, check, audit, state);
        scan.scan();
        assertThat(scan.invalidRounds()).containsExactly("b");
        scan.scan();
        verify(audit, times(1)).log(eq("system"), eq("ROUNDS_INTEGRITY_FAILED"), contains("1 round(s)"));
        verify(audit, never()).log(anyString(), eq("ROUND_TAMPERED"), anyString());

        // N10: another instance shows the same result without scanning, and doesn't scan again at start-up
        RoundIntegrityScanner other = new RoundIntegrityScanner(game, check, audit, state);
        assertThat(other.invalidRounds()).containsExactly("b");
        other.scanAll();
        verify(game, times(2)).decidedRoundsAfter("", 1000);

        // N14.3: once the round is blocked, the next hourly pass drops it from the alarm
        when(game.findRound("b")).thenReturn(java.util.Optional.of(new GameRepository.Round("b", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE,
                new BigDecimal("900"), "EUR", RoundStatus.TAMPERED, "0", "900", null, "f".repeat(64), null, null)));
        when(game.decidedRoundsPlayed(any(), anyString(), any(), anyInt())).thenReturn(List.of());
        other.scanNew();
        assertThat(other.invalidRounds()).isEmpty();
        assertThat(new RoundIntegrityScanner(game, check, audit, state).invalidRounds()).isEmpty();
    }

    @Test
    void aRoundWhoseOddsVersionCantBeCheckedIsASetupAlarmNotAnIntegrityAlarm() {
        // N14.5: missing odds version (or a key this server doesn't hold): listed apart, not as data changed
        WinCheck check = mock(WinCheck.class);
        GameRepository.Round round = new GameRepository.Round("u", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, "EUR",
                RoundStatus.CREDIT_PENDING, "0", "1", null, "f".repeat(64), null, null);
        when(check.outcomeProblem(round)).thenReturn("C5: odds version 4 is missing");
        when(game.decidedRoundsAfter("", 1000)).thenReturn(List.of(round));
        when(game.decidedRoundsAfter("u", 1000)).thenReturn(List.of());
        AuditService audit = mock(AuditService.class);
        RoundIntegrityScanner scan = new RoundIntegrityScanner(game, check, audit, new com.plinko.MemoryJobState());
        scan.scan();
        assertThat(scan.invalidRounds()).isEmpty();
        assertThat(scan.unreadableRounds()).containsExactly("u");
        verify(audit, never()).log(anyString(), eq("ROUNDS_INTEGRITY_FAILED"), anyString());
    }

    /** Review 3.10d: the admin lists show the scan's verdict per round instead of verifying each listed row. */
    @Test
    void theListsSignatureColumnIsTheScansVerdict() {
        WinCheck check = mock(WinCheck.class);
        GameRepository.Round forged = new GameRepository.Round("b", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, new BigDecimal("900"),
                "EUR", RoundStatus.COMPLETED, "0", "900", null, "f".repeat(64), null, null);
        GameRepository.Round unreadable = new GameRepository.Round("u", 7, "s", 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE,
                "EUR", RoundStatus.COMPLETED, "0", "1", null, "f".repeat(64), null, null);
        when(check.outcomeProblem(forged)).thenReturn("C1: signature");
        when(check.outcomeProblem(unreadable)).thenReturn("C5: odds version 4 is missing");
        when(game.decidedRoundsAfter("", 1000)).thenReturn(List.of(forged, unreadable));
        when(game.decidedRoundsAfter("u", 1000)).thenReturn(List.of());
        com.plinko.MemoryJobState state = new com.plinko.MemoryJobState();
        RoundIntegrityScanner scan = new RoundIntegrityScanner(game, check, mock(AuditService.class), state);
        RoundIntegrityScanner.Verdicts before = scan.verdicts();
        assertThat(scan.checkedUntil()).as("no pass yet").isEmpty();
        assertThat(before.of("a", RoundStatus.COMPLETED, true, Instant.now().minus(Duration.ofDays(1)))).isEqualTo("not yet checked");

        Instant started = Instant.now();
        scan.scan();
        Instant until = scan.checkedUntil().orElseThrow();
        assertThat(until).as("10 minutes before the pass").isBetween(started.minus(Duration.ofMinutes(11)), started.minus(Duration.ofMinutes(9)));
        RoundIntegrityScanner.Verdicts verdicts = scan.verdicts();
        assertThat(verdicts.of("b", RoundStatus.COMPLETED, true, until.minusSeconds(60))).isEqualTo("INVALID");
        assertThat(verdicts.of("c", RoundStatus.TAMPERED, true, until.minusSeconds(60))).isEqualTo("blocked");
        assertThat(verdicts.of("u", RoundStatus.COMPLETED, true, until.minusSeconds(60))).isEqualTo("odds version unreadable");
        assertThat(verdicts.of("a", RoundStatus.COMPLETED, true, until.minusSeconds(60))).isEqualTo("checked");
        assertThat(verdicts.of("n", RoundStatus.COMPLETED, true, until.plusSeconds(1))).isEqualTo("not yet checked");
        assertThat(verdicts.of("p", RoundStatus.PENDING, false, until.minusSeconds(60))).as("no outcome to check").isEmpty();
        assertThat(verdicts.of(new com.plinko.repo.AdminListRepository.RoundSummary("b", 7, 8, 2, BigDecimal.ONE, 1, BigDecimal.ONE,
                new BigDecimal("900"), "EUR", RoundStatus.COMPLETED, true, until.minusSeconds(60)))).isEqualTo("INVALID");
        assertThat(verdicts.bad("INVALID")).isTrue();
        assertThat(verdicts.bad("blocked")).isTrue();
        assertThat(verdicts.bad("checked")).isFalse();
        // another instance reads the same checkpoint
        assertThat(new RoundIntegrityScanner(game, check, mock(AuditService.class), state).checkedUntil()).contains(until);
        // a checkpoint it can't read counts as no pass
        state.put("round_scan.checkpoint", "garbage|");
        assertThat(new RoundIntegrityScanner(game, check, mock(AuditService.class), state).checkedUntil()).isEmpty();
    }

    @Test
    void aScanAnotherInstanceIsRunningIsNotRunTwice() {
        com.plinko.MemoryJobState state = new com.plinko.MemoryJobState();
        state.free = false;
        new RoundIntegrityScanner(game, mock(WinCheck.class), mock(AuditService.class), state).scanAll();
        verify(game, never()).decidedRoundsAfter(anyString(), anyInt());
    }

    /**
     * Review 5.2: the scan doesn't stop at the signature: a correctly signed round whose win doesn't add up from its
     * balls, or is above the limit recorded with it, is flagged too, by the hourly pass as well as the full one.
     */
    @Test
    void theScanRecomputesTheWinOfCorrectlySignedRounds() {
        RoundSigner signer = new RoundSigner(TestFixtures.properties());
        WinCheck check = new WinCheck(signer, new WalletSigner(new Integrity(TestFixtures.properties())), odds, TestFixtures.properties());
        AuditService audit = mock(AuditService.class);
        java.util.function.Function<Object[], GameRepository.Round> signed = f -> {
            String id = (String) f[0];
            BigDecimal win = new BigDecimal((String) f[1]);
            String multipliers = (String) f[2];
            BigDecimal limit = f[3] == null ? null : new BigDecimal((String) f[3]);
            return new GameRepository.Round(id, 7, "s", 8, 2, BigDecimal.ONE, 2, new BigDecimal("2"), win, "EUR", RoundStatus.COMPLETED, "0,8",
                    multipliers, null, signer.sign(id, 7, 8, 2, BigDecimal.ONE, 2, new BigDecimal("2"), win, "EUR", "0,8", multipliers, null, null, limit),
                    Instant.now().minus(Duration.ofHours(1)), null, null, null, limit);
        };
        GameRepository.Round good = signed.apply(new Object[] {"a", "3.50", "1.5,2", "1000"});
        GameRepository.Round winDoesNotAddUp = signed.apply(new Object[] {"b", "350.00", "1.5,2", "1000"});
        GameRepository.Round aboveItsLimit = signed.apply(new Object[] {"c", "3.50", "1.5,2", "3"});
        assertThat(signer.verify(winDoesNotAddUp)).as("signed with the game's key").isTrue();
        assertThat(signer.verify(aboveItsLimit)).isTrue();
        List<GameRepository.Round> all = List.of(good, winDoesNotAddUp, aboveItsLimit);

        when(game.decidedRoundsAfter("", 1000)).thenReturn(all);
        when(game.decidedRoundsAfter("c", 1000)).thenReturn(List.of());
        com.plinko.MemoryJobState state = new com.plinko.MemoryJobState();
        RoundIntegrityScanner scan = new RoundIntegrityScanner(game, check, audit, state);
        scan.scan();
        assertThat(scan.invalidRounds()).containsExactly("b", "c");
        verify(audit).log(eq("system"), eq("ROUNDS_INTEGRITY_FAILED"), contains("2 round(s)"));

        // the hourly pass finds the same in rounds played since the last one
        com.plinko.MemoryJobState hourly = new com.plinko.MemoryJobState();
        hourly.put("round_scan.checkpoint", Instant.now().minus(Duration.ofDays(1)).toEpochMilli() + "|");
        when(game.decidedRoundsPlayed(any(), eq(""), any(), anyInt())).thenReturn(all);
        when(game.decidedRoundsPlayed(any(), eq("c"), any(), anyInt())).thenReturn(List.of());
        RoundIntegrityScanner pass = new RoundIntegrityScanner(game, check, audit, hourly);
        pass.scanNew();
        assertThat(pass.invalidRounds()).containsExactly("b", "c");
    }
}
