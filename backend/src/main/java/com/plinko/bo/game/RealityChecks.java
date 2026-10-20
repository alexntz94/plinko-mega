package com.plinko.bo.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.settings.SettingsService;
import com.plinko.common.ApiException;
import com.plinko.repo.GameEventRepository;
import com.plinko.repo.GameRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Review 7.4: the reality check and the session clock are shown by the game client; a scripted client could skip them.
 * The client reports showing them (and the player choosing to continue), and the server records it per session, so a
 * market that requires proof has it. With {@code game.requireRealityCheckProof} on (Settings, off by default), a round is
 * refused (409 REALITY_CHECK_DUE) once the session's reality check is due and no "shown, then continued" was reported
 * since the session started or since the last one.
 */
@Service
public class RealityChecks {

    public static final String SHOWN = "REALITY_CHECK_SHOWN";
    public static final String CONTINUED = "REALITY_CHECK_CONTINUED";
    public static final String CLOCK_SHOWN = "SESSION_CLOCK_SHOWN";
    /** The only events recorded. */
    public static final List<String> TYPES = List.of(SHOWN, CONTINUED, CLOCK_SHOWN);
    /** Events kept per session at most: a reality check every 10 minutes for a day is under 300. */
    static final int MAX_PER_SESSION = 500;
    /** How far the client's own time may be from the server's (a phone's clock is often off a little). */
    static final Duration CLIENT_CLOCK = Duration.ofDays(1);
    /** The newest events looked at for the last "shown, then continued". */
    private static final int LOOK_BACK = 20;

    private final GameRepository game;
    private final GameEventRepository events;
    private final SettingsService settings;
    private final Clock clock;

    @Autowired
    public RealityChecks(GameRepository game, GameEventRepository events, SettingsService settings) {
        this(game, events, settings, Clock.systemUTC());
    }

    RealityChecks(GameRepository game, GameEventRepository events, SettingsService settings, Clock clock) {
        this.game = game;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Records what the client of this session reports having shown. {@code at}: the client's own time (milliseconds
     * since 1970), kept as it said it; the server's time is what counts.
     */
    public void record(String sessionId, String type, Long at) {
        GameRepository.Session session = requireSession(sessionId);
        if (type == null || !TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "type must be one of " + String.join(", ", TYPES));
        }
        Instant now = clock.instant();
        Instant clientAt = at == null ? null : Instant.ofEpochMilli(at);
        if (clientAt != null && Duration.between(clientAt, now).abs().compareTo(CLIENT_CLOCK) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "at must be the client's current time in milliseconds");
        }
        if (events.countInSession(session.id(), MAX_PER_SESSION) >= MAX_PER_SESSION) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_EVENTS", "This session has reported too many events");
        }
        events.insert(session.id(), session.playerId(), type, clientAt, now);
    }

    /**
     * Called at the start of every round: nothing unless proof is required and a reality check is set; then refuses the
     * round once the check is due and the player was not shown it and chose to continue.
     */
    public void requireShown(GameRepository.Session session) {
        SettingsService.GameFeatures features = settings.features();
        if (!features.requireRealityCheckProof() || features.realityCheckMinutes() <= 0) {
            return;
        }
        Optional<Instant> since = lastContinued(session.id()).or(() -> events.sessionStart(session.id()));
        if (since.isEmpty()) {
            return;     // the session is gone: the round itself refuses it
        }
        if (!clock.instant().isBefore(since.get().plus(Duration.ofMinutes(features.realityCheckMinutes())))) {
            throw new ApiException(HttpStatus.CONFLICT, "REALITY_CHECK_DUE",
                    "A reality check is due: show the player their time and result and let them choose to continue");
        }
    }

    /** When the player last continued after being shown the reality check: a CONTINUED right after a SHOWN. */
    private Optional<Instant> lastContinued(String sessionId) {
        List<GameEventRepository.Event> latest = events.latest(sessionId, LOOK_BACK).stream()
                .filter(e -> SHOWN.equals(e.type()) || CONTINUED.equals(e.type()))
                .toList();
        for (int i = 0; i + 1 < latest.size(); i++) {
            if (CONTINUED.equals(latest.get(i).type()) && SHOWN.equals(latest.get(i + 1).type())) {
                return Optional.of(latest.get(i).at());
            }
        }
        return Optional.empty();
    }

    private GameRepository.Session requireSession(String sessionId) {
        if (sessionId == null || sessionId.length() > 64) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
        }
        GameRepository.Session session = game.findSession(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired"));
        if (session.expiresAt().isBefore(clock.instant())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
        }
        return session;
    }
}
