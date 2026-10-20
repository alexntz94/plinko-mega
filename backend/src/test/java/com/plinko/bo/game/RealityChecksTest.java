package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.settings.SettingsService;
import com.plinko.common.ApiException;
import com.plinko.model.BonusSettings;
import com.plinko.repo.GameEventRepository;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Review 7.4: the game's "shown" reports are recorded per session, and with proof required a due reality check stops play. */
class RealityChecksTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final GameRepository.Session SESSION = new GameRepository.Session("s", 7, "tok", NOW.plusSeconds(3600), null);

    private final GameRepository game = mock(GameRepository.class);
    private final GameEventRepository events = mock(GameEventRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final RealityChecks checks = new RealityChecks(game, events, settings, Clock.fixed(NOW, ZoneOffset.UTC));
    /** The session's events, newest first, as the repository answers. */
    private final List<GameEventRepository.Event> recorded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(game.findSession("s")).thenReturn(Optional.of(SESSION));
        when(events.latest(anyString(), anyInt())).thenAnswer(i -> List.copyOf(recorded));
        when(events.sessionStart("s")).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(45))));
        when(settings.features()).thenReturn(proof(true, 30));
    }

    private static SettingsService.GameFeatures proof(boolean required, int minutes) {
        return new SettingsService.GameFeatures(true, false, minutes, false, BigDecimal.ZERO, required);
    }

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    /** An event, minutes ago; added newest first like the repository's answer. */
    private void happened(String type, int minutesAgo) {
        recorded.add(0, new GameEventRepository.Event(type, NOW.minus(Duration.ofMinutes(minutesAgo))));
    }

    @Test
    void theThreeKindsOfEventAreRecordedForTheBearersSessionWithTheServersTime() {
        checks.record("s", "REALITY_CHECK_SHOWN", NOW.minusSeconds(2).toEpochMilli());
        checks.record("s", "REALITY_CHECK_CONTINUED", null);
        checks.record("s", "SESSION_CLOCK_SHOWN", NOW.plusSeconds(5).toEpochMilli());
        verify(events).insert("s", 7, "REALITY_CHECK_SHOWN", NOW.minusSeconds(2), NOW);
        verify(events).insert("s", 7, "REALITY_CHECK_CONTINUED", null, NOW);
        verify(events).insert("s", 7, "SESSION_CLOCK_SHOWN", NOW.plusSeconds(5), NOW);
    }

    @Test
    void anythingElseIsRefusedAndNothingIsRecorded() {
        for (String type : new String[] {null, "", "reality_check_shown", "ROUND_PLAYED", "REALITY_CHECK_SHOWN "}) {
            ApiException e = refusal(() -> checks.record("s", type, null));
            assertThat(e.status()).as(type).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(refusal(() -> checks.record("s", "REALITY_CHECK_SHOWN", NOW.minus(Duration.ofDays(2)).toEpochMilli())).code())
                .as("a client time far from now").isEqualTo("INVALID_REQUEST");
        // only for a live session
        when(game.findSession("old")).thenReturn(Optional.of(new GameRepository.Session("old", 7, "tok", NOW.minusSeconds(1), null)));
        for (String session : new String[] {null, "old", "unknown", "x".repeat(65)}) {
            ApiException e = refusal(() -> checks.record(session, "REALITY_CHECK_SHOWN", null));
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("SESSION_EXPIRED");
        }
        // a session can't fill the table: past its share, refused
        when(events.countInSession("s", RealityChecks.MAX_PER_SESSION)).thenReturn((long) RealityChecks.MAX_PER_SESSION);
        ApiException tooMany = refusal(() -> checks.record("s", "SESSION_CLOCK_SHOWN", null));
        assertThat(tooMany.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(tooMany.code()).isEqualTo("TOO_MANY_EVENTS");
        verify(events, never()).insert(anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void withoutProofRequiredOrWithoutARealityCheckRoundsAreNeverHeld() {
        when(settings.features()).thenReturn(proof(false, 30));
        checks.requireShown(SESSION);
        when(settings.features()).thenReturn(proof(true, 0));
        checks.requireShown(SESSION);
        verify(events, never()).latest(anyString(), anyInt());
    }

    @Test
    void aDueRealityCheckWithoutShownAndContinuedStopsPlay() {
        // 45 minutes into the session, a check every 30: due since 15 minutes
        ApiException due = refusal(() -> checks.requireShown(SESSION));
        assertThat(due.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(due.code()).isEqualTo("REALITY_CHECK_DUE");
        // the session clock or a "continued" the player was never shown don't count
        happened("SESSION_CLOCK_SHOWN", 40);
        happened("REALITY_CHECK_CONTINUED", 10);
        assertThat(refusal(() -> checks.requireShown(SESSION)).code()).isEqualTo("REALITY_CHECK_DUE");
        // shown, then continued: play goes on for the next 30 minutes
        happened("REALITY_CHECK_SHOWN", 2);
        happened("SESSION_CLOCK_SHOWN", 2);
        happened("REALITY_CHECK_CONTINUED", 1);
        checks.requireShown(SESSION);
    }

    @Test
    void theNextCheckIsDueAnIntervalAfterThePlayerLastContinued() {
        happened("REALITY_CHECK_SHOWN", 31);
        happened("REALITY_CHECK_CONTINUED", 30);
        assertThat(refusal(() -> checks.requireShown(SESSION)).code()).as("exactly 30 minutes later").isEqualTo("REALITY_CHECK_DUE");
        // shown again but not answered yet: still due
        happened("REALITY_CHECK_SHOWN", 0);
        assertThat(refusal(() -> checks.requireShown(SESSION)).code()).isEqualTo("REALITY_CHECK_DUE");
        happened("REALITY_CHECK_CONTINUED", 0);
        checks.requireShown(SESSION);
    }

    @Test
    void aNewSessionPlaysUntilItsFirstCheckIsDue() {
        when(events.sessionStart("s")).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(29))));
        checks.requireShown(SESSION);
        // a session already gone is left to the round itself to refuse
        when(events.sessionStart("s")).thenReturn(Optional.empty());
        checks.requireShown(SESSION);
    }

    @Test
    void theGameRefusesARoundWhileTheCheckIsDueAndTakesNoBet() {
        TestGame play = new TestGame(BonusSettings.OFF);
        SettingsService features = mock(SettingsService.class);
        when(features.features()).thenReturn(proof(true, 30));
        GameEventRepository sessionEvents = mock(GameEventRepository.class);
        when(sessionEvents.sessionStart("s")).thenReturn(Optional.of(Instant.now().minus(Duration.ofMinutes(31))));
        when(sessionEvents.latest(anyString(), anyInt())).thenReturn(List.of());
        ReflectionTestUtils.setField(play.game, "realityChecks", new RealityChecks(play.repository, sessionEvents, features));

        ApiException due = refusal(() -> play.play("1.00", 1));
        assertThat(due.code()).isEqualTo("REALITY_CHECK_DUE");
        assertThat(play.wallet.count("debit")).isZero();
        assertThat(play.balance()).isEqualByComparingTo("100.00");

        when(sessionEvents.latest(anyString(), anyInt())).thenReturn(List.of(
                new GameEventRepository.Event("REALITY_CHECK_CONTINUED", Instant.now()),
                new GameEventRepository.Event("REALITY_CHECK_SHOWN", Instant.now().minusSeconds(5))));
        assertThat(play.play("1.00", 1).roundId()).isNotNull();
        assertThat(play.wallet.count("debit")).isEqualTo(1);
    }
}
