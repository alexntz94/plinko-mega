package com.plinko.controller.mockpam;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import com.plinko.pam.HttpPamClient;
import com.plinko.repo.MockWalletRepository.Wallet;
import com.plinko.repo.MockWalletRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only PAM implementing the same signed HTTP API a real PAM must provide.
 * The bean does not exist unless MOCK_PAM_ENABLED=true.
 * <p>
 * For the money-path tests it can also misbehave on purpose: {@code POST /mock-pam/_fault} (signed like every call)
 * with {@code {playerId, endpoint, fault, times}} makes that wallet's next calls to {@code endpoint} (debit, credit,
 * rollback, balance) fail in one way:
 * <ul>
 * <li>{@code timeout}: no answer within the game's PAM timeout, nothing applied;</li>
 * <li>{@code error}: HTTP 500, nothing applied;</li>
 * <li>{@code slow}: applied and answered, but late (80% of the timeout);</li>
 * <li>{@code lost-answer}: applied, but the answer comes after the timeout (the game never hears it);</li>
 * <li>{@code bad-signature}, {@code no-signature}: applied, answered with a wrong or missing signature;</li>
 * <li>{@code other-transaction}: applied, answered (correctly signed) for a different transaction id.</li>
 * </ul>
 * {@code fault: "none"} clears the wallet's faults.
 */
@RestController
@RequestMapping("/mock-pam")
@ConditionalOnProperty(name = "plinko.mock-pam.enabled", havingValue = "true")
public class MockPamController {

    private static final java.util.Set<String> FAULTS = java.util.Set.of("timeout", "error", "slow", "limit", "lost-answer", "bad-signature",
            "no-signature", "other-transaction");

    private record Fault(String endpoint, String kind) {
    }

    private final MockWalletRepository wallets;
    private final ObjectMapper json;
    private final PlinkoProperties.Pam config;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    /** Faults waiting per wallet, used up one call at a time. */
    private final Map<String, java.util.Deque<Fault>> faults = new java.util.concurrent.ConcurrentHashMap<>();

    public MockPamController(MockWalletRepository wallets, ObjectMapper json, PlinkoProperties properties,
                             org.springframework.transaction.PlatformTransactionManager transactions) {
        this.wallets = wallets;
        this.json = json;
        this.config = properties.pam();
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    @PostMapping("/{endpoint}")
    public ResponseEntity<String> handle(@PathVariable String endpoint,
                                         @RequestHeader(value = HttpPamClient.API_KEY_HEADER, required = false) String apiKey,
                                         @RequestHeader(value = HttpPamClient.SIGNATURE_HEADER, required = false) String signature,
                                         @RequestBody String body) {
        if (!Crypto.safeEquals(apiKey, config.apiKey()) || !Crypto.safeEquals(signature, Crypto.hmacSha256Hex(config.secret(), body))) {
            return reply(HttpStatus.UNAUTHORIZED, Map.of("code", "INVALID_SIGNATURE"));
        }
        JsonNode request = json.readTree(body);
        if ("_fault".equals(endpoint)) {
            return addFault(request);
        }
        Fault fault = nextFault(request.path("playerId").asString(""), endpoint);
        if (fault != null) {
            switch (fault.kind()) {
                case "timeout" -> {
                    pause(config.timeoutMs() + 1500L);
                    return reply(HttpStatus.GATEWAY_TIMEOUT, Map.of("code", "TIMEOUT"));
                }
                case "error" -> {
                    return reply(HttpStatus.INTERNAL_SERVER_ERROR, Map.of("code", "HTTP_500", "message", "Wallet error (injected)"));
                }
                case "slow" -> pause(config.timeoutMs() * 8L / 10);
                // the casino's own limits (review 7.4): a definite, signed refusal of the bet
                case "limit" -> {
                    return reply(HttpStatus.FORBIDDEN, Map.of("code", "LIMIT_REACHED", "message", "Loss limit reached"));
                }
                default -> {
                    // the others are applied first, then answered badly
                }
            }
        }
        ResponseEntity<String> answer = transaction.execute(status -> process(endpoint, request));
        if (fault == null || answer == null) {
            return answer;
        }
        return switch (fault.kind()) {
            case "lost-answer" -> {
                pause(config.timeoutMs() + 1500L);
                yield answer;
            }
            case "bad-signature" -> ResponseEntity.status(answer.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpPamClient.SIGNATURE_HEADER, "00".repeat(32)).body(answer.getBody());
            case "no-signature" -> ResponseEntity.status(answer.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(answer.getBody());
            case "other-transaction" -> {
                Map<String, Object> other = json.readValue(answer.getBody(), new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
                other.put("transactionId", "other-" + other.get("transactionId"));
                yield reply(HttpStatus.valueOf(answer.getStatusCode().value()), other);
            }
            default -> answer;
        };
    }

    private ResponseEntity<String> process(String endpoint, JsonNode request) {
        return switch (endpoint) {
            case "authenticate" -> authenticate(request.path("token").asString());
            case "balance" -> balance(request.path("playerId").asString());
            case "debit" -> move(request, "DEBIT");
            case "credit" -> move(request, "CREDIT");
            case "rollback" -> rollback(request);
            case "transactions" -> transactions(request);
            default -> reply(HttpStatus.NOT_FOUND, Map.of("code", "UNKNOWN_ENDPOINT"));
        };
    }

    private ResponseEntity<String> addFault(JsonNode request) {
        String playerId = request.path("playerId").asString("");
        String kind = request.path("fault").asString("");
        if ("none".equals(kind)) {
            faults.remove(playerId);
            return reply(HttpStatus.OK, Map.of("cleared", playerId));
        }
        if (!FAULTS.contains(kind) || playerId.isEmpty()) {
            return reply(HttpStatus.BAD_REQUEST, Map.of("code", "UNKNOWN_FAULT", "message", "fault must be one of " + FAULTS));
        }
        java.util.Deque<Fault> queue = faults.computeIfAbsent(playerId, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        for (int i = 0; i < Math.max(1, request.path("times").asInt(1)); i++) {
            queue.add(new Fault(request.path("endpoint").asString(""), kind));
        }
        return reply(HttpStatus.OK, Map.of("playerId", playerId, "queued", queue.size()));
    }

    /** The first waiting fault of this wallet for this endpoint, used up. */
    private Fault nextFault(String playerId, String endpoint) {
        java.util.Deque<Fault> queue = faults.get(playerId);
        if (queue == null) {
            return null;
        }
        for (Fault fault : queue) {
            if (fault.endpoint().equals(endpoint) && queue.remove(fault)) {
                return fault;
            }
        }
        return null;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ResponseEntity<String> authenticate(String token) {
        Optional<Wallet> wallet = wallets.byToken(token);
        if (wallet.isEmpty()) {
            return reply(HttpStatus.UNAUTHORIZED, Map.of("code", "INVALID_TOKEN", "message", "Unknown token"));
        }
        Wallet w = wallet.get();
        wallets.useToken(w.playerId(), token);     // R1.7: a launch token works once
        return reply(HttpStatus.OK, Map.of("playerId", w.playerId(), "username", w.username(), "currency", w.currency(), "balance", w.balance()));
    }

    private ResponseEntity<String> balance(String playerId) {
        Optional<Wallet> wallet = lockWallet(playerId);
        if (wallet.isEmpty()) {
            return reply(HttpStatus.NOT_FOUND, Map.of("code", "PLAYER_NOT_FOUND"));
        }
        return reply(HttpStatus.OK, Map.of("balance", wallet.get().balance(), "currency", wallet.get().currency()));
    }

    private ResponseEntity<String> move(JsonNode request, String type) {
        String transactionId = request.path("transactionId").asString();
        // N14.7: the wallet is locked before the repeat check, so two copies of a new transaction arriving at the same
        // moment are taken one after the other: the second sees the first and gets its answer (R1.1)
        Optional<Wallet> wallet = lockWallet(request.path("playerId").asString());
        Optional<ResponseEntity<String>> repeated = repeated(transactionId, request, type);
        if (repeated.isPresent()) {
            return repeated.get();
        }
        if (wallet.isEmpty()) {
            return reply(HttpStatus.NOT_FOUND, Map.of("code", "PLAYER_NOT_FOUND"));
        }
        BigDecimal amount = request.path("amount").decimalValue();
        if (amount.signum() < 0) {
            return reply(HttpStatus.BAD_REQUEST, Map.of("code", "INVALID_AMOUNT"));
        }
        if (!wallet.get().currency().equals(request.path("currency").asString())) {
            return reply(HttpStatus.BAD_REQUEST, Map.of("code", "CURRENCY_MISMATCH"));
        }
        // As the PAM contract requires: a bet that arrives after its own rollback (it was delayed on the way) is
        // refused, otherwise the player loses a bet the game already cancelled.
        if ("DEBIT".equals(type) && wallets.rolledBack(transactionId)) {
            return reply(HttpStatus.CONFLICT, Map.of("code", "TRANSACTION_ROLLED_BACK", "message", "This bet was already cancelled"));
        }
        BigDecimal balance = wallet.get().balance();
        if ("DEBIT".equals(type)) {
            if (balance.compareTo(amount) < 0) {
                return reply(HttpStatus.PAYMENT_REQUIRED, Map.of("code", "INSUFFICIENT_FUNDS", "message", "Insufficient funds", "balance", balance));
            }
            balance = balance.subtract(amount);
        } else {
            balance = balance.add(amount);
        }
        record(transactionId, wallet.get().playerId(), type, amount, balance, request.path("roundId").asString(), null);
        return reply(HttpStatus.OK, Map.of("transactionId", transactionId, "balance", balance, "reference", transactionId));
    }

    private ResponseEntity<String> transactions(JsonNode request) {
        try {
            Instant from = Instant.parse(request.path("from").asString());
            Instant to = Instant.parse(request.path("to").asString());
            int limit = Math.max(1, Math.min(1000, request.path("limit").asInt(500)));
            List<Map<String, Object>> rows = wallets.transactions(from, to, request.path("after").asString(""), limit);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("transactions", rows);
            answer.put("next", rows.size() == limit ? rows.get(rows.size() - 1).get("transactionId") : null);
            return reply(HttpStatus.OK, answer);
        } catch (java.time.format.DateTimeParseException e) {
            return reply(HttpStatus.BAD_REQUEST, Map.of("code", "INVALID_REQUEST"));
        }
    }

    private ResponseEntity<String> rollback(JsonNode request) {
        String transactionId = request.path("transactionId").asString();
        Optional<Wallet> wallet = lockWallet(request.path("playerId").asString());    // before the repeat check (N14.7)
        Optional<ResponseEntity<String>> repeated = repeated(transactionId, request, "ROLLBACK");
        if (repeated.isPresent()) {
            return repeated.get();
        }
        if (wallet.isEmpty()) {
            return reply(HttpStatus.NOT_FOUND, Map.of("code", "PLAYER_NOT_FOUND"));
        }
        String originalId = request.path("originalTransactionId").asString();
        // As the PAM contract requires: a round that was already paid can never have its bet refunded.
        boolean settled = wallets.roundPaid(request.path("roundId").asString());
        if (settled) {
            return reply(HttpStatus.CONFLICT, Map.of("code", "ROUND_SETTLED", "message", "The round was already paid"));
        }
        // Only a debit of this same player and round can be refunded.
        BigDecimal refund = wallets.debit(originalId, wallet.get().playerId(), request.path("roundId").asString()).orElse(BigDecimal.ZERO);
        boolean alreadyRolledBack = wallets.rolledBack(originalId);
        BigDecimal balance = alreadyRolledBack ? wallet.get().balance() : wallet.get().balance().add(refund);
        record(transactionId, wallet.get().playerId(), "ROLLBACK", alreadyRolledBack ? BigDecimal.ZERO : refund, balance,
                request.path("roundId").asString(), originalId);
        return reply(HttpStatus.OK, Map.of("transactionId", transactionId, "balance", balance, "reference", transactionId));
    }

    /**
     * Idempotency, as the PAM contract requires: a repeated transaction id returns the original result without moving
     * money again (R1.1), marked "repeated"; the same id with different contents (player, type, round, amount, currency,
     * or the bet a rollback cancels) is refused and moves nothing (R1.2), so a reused id can't push another payment through.
     */
    private Optional<ResponseEntity<String>> repeated(String transactionId, JsonNode request, String type) {
        Optional<MockWalletRepository.Stored> stored = wallets.stored(transactionId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        MockWalletRepository.Stored first = stored.get();
        boolean same = first.type().equals(type) && first.playerId().equals(request.path("playerId").asString())
                && java.util.Objects.equals(first.roundId(), request.path("roundId").asString());
        if ("ROLLBACK".equals(type)) {
            same &= java.util.Objects.equals(first.originalId(), request.path("originalTransactionId").asString());
        } else {
            same &= request.path("amount").isNumber() && first.amount().compareTo(request.path("amount").decimalValue()) == 0
                    && wallets.lock(first.playerId()).map(w -> w.currency().equals(request.path("currency").asString())).orElse(false);
        }
        if (!same) {
            return Optional.of(reply(HttpStatus.CONFLICT, Map.of("code", "TRANSACTION_MISMATCH",
                    "message", "This transaction id was already used for a different transaction")));
        }
        return Optional.of(reply(HttpStatus.OK, Map.of("transactionId", transactionId, "balance", first.balanceAfter(), "reference", transactionId,
                "repeated", true)));
    }

    private void record(String transactionId, String playerId, String type, BigDecimal amount, BigDecimal balance, String roundId, String originalId) {
        wallets.record(transactionId, playerId, type, amount, balance, roundId, originalId);
    }

    private Optional<Wallet> lockWallet(String playerId) {
        return wallets.lock(playerId);
    }

    private ResponseEntity<String> reply(HttpStatus status, Map<String, ?> body) {
        String text = json.writeValueAsString(new LinkedHashMap<>(body));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpPamClient.SIGNATURE_HEADER, Crypto.hmacSha256Hex(config.secret(), text))
                .body(text);
    }
}
