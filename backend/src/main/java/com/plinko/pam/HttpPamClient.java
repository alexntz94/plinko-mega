package com.plinko.pam;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generic JSON-over-HTTPS PAM adapter, see docs/PAM_INTEGRATION.md.
 * Requests and responses are signed with HMAC-SHA256 over the raw body; unsigned or wrongly signed
 * responses are rejected so a spoofed PAM response cannot report a fake balance or confirmation.
 */
@Component
public class HttpPamClient implements PamClient {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private static final Logger log = LoggerFactory.getLogger(HttpPamClient.class);

    private final PlinkoProperties.Pam config;
    private final boolean mockPam;
    private final ObjectMapper json;
    private volatile HttpClient http;

    public HttpPamClient(PlinkoProperties properties, ObjectMapper json) {
        this.config = properties.pam();
        this.mockPam = properties.mockPam().enabled();
        this.json = json;
        this.http = newClient();
    }

    private HttpClient newClient() {
        // its own work runs on virtual threads: a burst of wallet calls never needs new platform threads
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    }

    HttpClient client() {
        return http;
    }

    /**
     * True when wallet calls can be sent. A client that stopped for good (e.g. its selector thread died when the
     * machine ran out of threads) is replaced here, so the game recovers by itself instead of failing every wallet
     * call until a restart.
     */
    public boolean usable() {
        HttpClient current = http;
        if (!current.isTerminated()) {
            return true;
        }
        synchronized (this) {
            if (http == current) {
                log.error("PAM HTTP client stopped; replacing it");
                try {
                    http = newClient();
                } catch (RuntimeException | Error e) {
                    log.error("PAM HTTP client could not be replaced: {}", e.toString());
                    return false;
                }
            }
        }
        return true;
    }

    @PostConstruct
    void checkConfig() {
        if (isBlank(config.baseUrl()) || isBlank(config.apiKey()) || isBlank(config.secret())) {
            throw new IllegalStateException("PAM_BASE_URL, PAM_API_KEY and PAM_SECRET must be set");
        }
        if (config.secret().length() < 32) {
            throw new IllegalStateException("PAM_SECRET must be at least 32 characters");
        }
        // Over plain http anyone on the network path could read tokens and replay signed PAM answers.
        if (!config.baseUrl().startsWith("https://") && !config.allowHttp() && !mockPam) {
            throw new IllegalStateException("PAM_BASE_URL must start with https:// (set PAM_ALLOW_HTTP=true only for a PAM on the same private network)");
        }
    }

    @Override
    public PamPlayer authenticate(String token) {
        JsonNode body = call("authenticate", Map.of("token", token));
        return new PamPlayer(text(body, "playerId"), body.path("username").asString(null), text(body, "currency"), decimal(body, "balance"));
    }

    @Override
    public BigDecimal balance(String playerId, String token) {
        return decimal(call("balance", Map.of("playerId", playerId, "token", token)), "balance");
    }

    @Override
    public PamResult debit(WalletRequest request) {
        return result(call("debit", walletBody(request)), request.transactionId());
    }

    @Override
    public PamResult credit(WalletRequest request) {
        return result(call("credit", walletBody(request)), request.transactionId());
    }

    @Override
    public PamResult rollback(RollbackRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerId", request.playerId());
        body.put("token", request.token());
        body.put("transactionId", request.transactionId());
        body.put("originalTransactionId", request.originalTransactionId());
        body.put("roundId", request.roundId());
        return result(call("rollback", body), request.transactionId());
    }

    @Override
    public TransactionPage transactions(java.time.Instant from, java.time.Instant to, String after) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gameId", "plinko");
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("after", after);
        body.put("limit", 500);
        JsonNode answer = call("transactions", body);
        JsonNode list = answer.get("transactions");
        if (list == null || !list.isArray()) {
            throw new PamException("INVALID_RESPONSE", "PAM response is missing transactions", true);
        }
        java.util.List<PamTransaction> out = new java.util.ArrayList<>();
        for (JsonNode t : list) {
            try {
                out.add(new PamTransaction(t.path("transactionId").asString(), t.path("type").asString(), t.path("roundId").asString(null),
                        t.path("playerId").asString(), decimal(t, "amount"), java.time.Instant.parse(t.path("time").asString())));
            } catch (java.time.format.DateTimeParseException e) {
                throw new PamException("INVALID_RESPONSE", "PAM transaction has an invalid time", true);
            }
        }
        JsonNode next = answer.get("next");
        return new TransactionPage(out, next == null || next.isNull() ? null : next.asString());
    }

    public String baseUrl() {
        return config.baseUrl();
    }

    private Map<String, Object> walletBody(WalletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerId", request.playerId());
        body.put("token", request.token());
        body.put("transactionId", request.transactionId());
        body.put("roundId", request.roundId());
        body.put("amount", request.amount());
        body.put("currency", request.currency());
        body.put("gameId", "plinko");
        return body;
    }

    /** Every wallet call timed, per endpoint and result (review 7.10): "ok", the PAM's error code, or why it failed. */
    private JsonNode call(String endpoint, Map<String, Object> payload) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry);
        String outcome = "ERROR";
        try {
            JsonNode node = send(endpoint, payload);
            outcome = "ok";
            return node;
        } catch (PamException e) {
            outcome = e.code();
            throw e;
        } finally {
            sample.stop(io.micrometer.core.instrument.Metrics.timer("plinko.pam", "endpoint", endpoint, "outcome", outcome));
        }
    }

    private JsonNode send(String endpoint, Map<String, Object> payload) {
        String requestBody = json.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(config.baseUrl()) + "/" + endpoint))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .header(API_KEY_HEADER, config.apiKey())
                .header(SIGNATURE_HEADER, Crypto.hmacSha256Hex(config.secret(), requestBody))
                // the game request this call belongs to, so the PAM's logs can be matched with the game's (not signed)
                .header("X-Request-Id", java.util.Objects.requireNonNullElse(org.slf4j.MDC.get("requestId"), "background"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        if (!usable()) {
            throw new PamException("CONNECTION_ERROR", "PAM client unavailable", true);
        }
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("PAM {} failed: {}", endpoint, e.toString());
            usable();
            throw new PamException("CONNECTION_ERROR", "PAM unreachable: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PamException("CONNECTION_ERROR", "Interrupted", true);
        }

        String responseBody = response.body();
        String signature = response.headers().firstValue(SIGNATURE_HEADER).orElse(null);
        if (!Crypto.safeEquals(signature, Crypto.hmacSha256Hex(config.secret(), responseBody))) {
            log.error("PAM {} returned HTTP {} with a missing or invalid signature", endpoint, response.statusCode());
            throw new PamException("INVALID_SIGNATURE", "PAM response signature invalid", true);
        }

        JsonNode node;
        try {
            node = json.readTree(responseBody);
        } catch (JacksonException e) {
            throw new PamException("INVALID_RESPONSE", "PAM response is not JSON", true);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return node;
        }
        String code = node.path("code").asString("HTTP_" + status);
        String message = node.path("message").asString(code);
        // 4xx is a definite answer, anything else leaves the outcome unknown; only a listed refusal (not with 409) proves
        // that nothing moved (PamException.refusal, review R10)
        throw new PamException(code, message, status < 400 || status >= 500, status);
    }

    /**
     * The signed answer must name the transaction it confirms. Otherwise a recorded "OK" answer for one
     * transaction could be replayed as the answer to another (e.g. a debit that never happened).
     */
    private static PamResult result(JsonNode body, String transactionId) {
        if (!transactionId.equals(body.path("transactionId").asString(null))) {
            throw new PamException("INVALID_RESPONSE", "PAM response is for a different transaction", true);
        }
        if (body.path("repeated").asBoolean(false)) {
            // the PAM recognised a transaction it already applied and returned the original answer (contract R1.1)
            log.info("PAM reports transaction {} as a repeat; original answer used", transactionId);
        }
        return new PamResult(decimal(body, "balance"), body.path("reference").asString(null));
    }

    private static String text(JsonNode body, String field) {
        String value = body.path(field).asString(null);
        if (isBlank(value)) {
            throw new PamException("INVALID_RESPONSE", "PAM response is missing " + field, true);
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !(value.isNumber() || value.isString())) {
            throw new PamException("INVALID_RESPONSE", "PAM response is missing " + field, true);
        }
        try {
            return new BigDecimal(value.isNumber() ? value.decimalValue().toPlainString() : value.asString());
        } catch (NumberFormatException e) {
            throw new PamException("INVALID_RESPONSE", "PAM response has an invalid " + field, true);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
