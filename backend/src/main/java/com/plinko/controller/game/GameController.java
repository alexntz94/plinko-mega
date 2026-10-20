package com.plinko.controller.game;

import java.util.LinkedHashMap;
import java.util.Map;

import com.plinko.bo.game.GameService;
import com.plinko.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    private final GameService game;
    private final com.plinko.bo.game.RealityChecks realityChecks;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public GameController(GameService game, com.plinko.bo.game.RealityChecks realityChecks, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.realityChecks = realityChecks;
        this.game = game;
        this.metrics = metrics;
    }

    /** Times a game call, tagged with its result: "ok" or the error code the player got (review 7.10). */
    private <T> T timed(String name, java.util.function.Supplier<T> call) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(metrics);
        String outcome = "SERVER_ERROR";
        try {
            T result = call.get();
            outcome = "ok";
            return result;
        } catch (ApiException e) {
            outcome = e.code();
            throw e;
        } finally {
            sample.stop(metrics.timer(name, "outcome", outcome));
        }
    }

    /** theme is optional: the casino's choice of look, used only if switched on in the admin. */
    public record SessionRequest(String token, String theme) {
    }

    @PostMapping("/session")
    public GameService.SessionResponse session(@RequestBody SessionRequest request) {
        return timed("plinko.session", () -> game.openSession(request.token(), request.theme()));
    }

    @PostMapping("/resume")
    public GameService.SessionResponse resume(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return game.resumeSession(bearer(authorization));
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }

    @PostMapping("/play")
    public GameService.PlayResponse play(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody GameService.PlayRequest request) {
        return timed("plinko.play", () -> game.play(bearer(authorization), request));
    }

    /** Review 7.5: the player's own earlier rounds, a page at a time (cursor: where the last page ended). */
    public record HistoryRequest(String cursor, Integer limit) {
    }

    @PostMapping("/history")
    public GameService.HistoryPage history(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody HistoryRequest request) {
        return timed("plinko.history", () -> game.history(bearer(authorization), request.cursor(), request.limit()));
    }

    /**
     * Review 7.4: what the game showed the player: REALITY_CHECK_SHOWN, REALITY_CHECK_CONTINUED or SESSION_CLOCK_SHOWN;
     * at is the client's own time in milliseconds (optional).
     */
    public record EventRequest(String type, Long at) {
    }

    @PostMapping("/event")
    public Map<String, Object> event(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody EventRequest request) {
        return timed("plinko.event", () -> {
            realityChecks.record(bearer(authorization), request.type(), request.at());
            return Map.of("recorded", true);
        });
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> apiError(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        if (e.balance() != null) {
            body.put("balance", e.balance());
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Malformed request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected game API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", "SERVER_ERROR", "message", "Server error"));
    }
}
