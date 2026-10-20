package com.plinko.pam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.plinko.TestFixtures;
import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The game's side of the wallet contract (review 5.1 #7-#8, 5.4), against a small local wallet server: only answers
 * signed with the PAM secret and naming the transaction they confirm are trusted; a 4xx is a definite refusal; a
 * timeout, a 5xx, an unsigned, mis-signed or malformed answer leaves the outcome unknown (the game then refunds a bet
 * or retries a payment).
 */
class HttpPamClientContractTest {

    private static final String SECRET = "s".repeat(40);

    private HttpServer server;
    /** What the fake wallet answers next: status, body, and how to sign it. */
    private final AtomicReference<Answer> next = new AtomicReference<>();
    private final AtomicReference<String> lastRequestSignature = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private HttpPamClient pam;

    private record Answer(int status, String body, String signing, long delayMs) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        pam = client("http://127.0.0.1:" + server.getAddress().getPort() + "/wallet", true, SECRET);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpPamClient client(String baseUrl, boolean allowHttp, String secret) {
        PlinkoProperties base = TestFixtures.properties();
        PlinkoProperties properties = new PlinkoProperties(base.admin(), base.requireSecondApprover(),
                new PlinkoProperties.Pam(baseUrl, "key", secret, 800, allowHttp), base.mockPam(), base.roundSigningKey(),
                base.limits(), base.login(), base.gameUrl(), base.sessionTtlMinutes(), base.retry());
        return new HttpPamClient(properties, JsonMapper.builder().build());
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastRequestSignature.set(exchange.getRequestHeaders().getFirst(HttpPamClient.SIGNATURE_HEADER));
        lastApiKey.set(exchange.getRequestHeaders().getFirst(HttpPamClient.API_KEY_HEADER));
        Answer answer = next.get();
        if (answer.delayMs() > 0) {
            try {
                Thread.sleep(answer.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        switch (answer.signing()) {
            case "good" -> exchange.getResponseHeaders().add(HttpPamClient.SIGNATURE_HEADER, Crypto.hmacSha256Hex(SECRET, answer.body()));
            case "wrong" -> exchange.getResponseHeaders().add(HttpPamClient.SIGNATURE_HEADER, Crypto.hmacSha256Hex("x".repeat(40), answer.body()));
            // a genuine signature, but of another answer (e.g. copied from an earlier response)
            case "swapped" -> exchange.getResponseHeaders().add(HttpPamClient.SIGNATURE_HEADER, Crypto.hmacSha256Hex(SECRET, answer.body() + " "));
            default -> { }
        }
        exchange.sendResponseHeaders(answer.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void answer(int status, String body, String signing) {
        next.set(new Answer(status, body, signing, 0));
    }

    private PamClient.PamResult debit() {
        return pam.debit(new PamClient.WalletRequest("p1", "tok", "tx-1", "r1", new BigDecimal("2.00"), "USD"));
    }

    private PamException failure(Runnable call) {
        try {
            call.run();
        } catch (PamException e) {
            return e;
        }
        throw new AssertionError("expected the wallet call to fail");
    }

    @Test
    void aSignedAnswerForThisTransactionIsTrusted() {
        answer(200, "{\"transactionId\":\"tx-1\",\"balance\":98.00,\"reference\":\"ref-1\"}", "good");
        PamClient.PamResult result = debit();
        assertThat(result.balance()).isEqualByComparingTo("98.00");
        assertThat(result.reference()).isEqualTo("ref-1");
        // the request itself is signed with the secret and carries the API key
        assertThat(lastRequestSignature.get()).isEqualTo(Crypto.hmacSha256Hex(SECRET, lastRequestBody.get()));
        assertThat(lastApiKey.get()).isEqualTo("key");
        assertThat(lastRequestBody.get()).contains("\"transactionId\":\"tx-1\"", "\"amount\":2.00", "\"gameId\":\"plinko\"");
    }

    @Test
    void unsignedOrWronglySignedAnswersAreNeverTrusted() {
        for (String signing : new String[] {"none", "wrong"}) {
            answer(200, "{\"transactionId\":\"tx-1\",\"balance\":1000000,\"reference\":\"x\"}", signing);
            PamException e = failure(this::debit);
            assertThat(e.code()).as(signing).isEqualTo("INVALID_SIGNATURE");
            assertThat(e.outcomeUnknown()).as(signing).isTrue();
        }
    }

    /** Review 5.1 #7: a missing, wrong or swapped signature is refused on every wallet endpoint, as an unknown outcome. */
    @Test
    void everyEndpointRefusesAMissingWrongOrSwappedSignature() {
        java.util.Map<String, Runnable> calls = new java.util.LinkedHashMap<>();
        calls.put("authenticate", () -> pam.authenticate("tok"));
        calls.put("balance", () -> pam.balance("p1", "tok"));
        calls.put("debit", this::debit);
        calls.put("credit", () -> pam.credit(new PamClient.WalletRequest("p1", "tok", "tx-1", "r1", new BigDecimal("2.00"), "USD")));
        calls.put("rollback", () -> pam.rollback(new PamClient.RollbackRequest("p1", "tok", "tx-1", "tx-0", "r1")));
        String body = "{\"transactionId\":\"tx-1\",\"playerId\":\"p1\",\"username\":\"a\",\"currency\":\"USD\",\"balance\":1000000,"
                + "\"reference\":\"x\"}";
        for (var call : calls.entrySet()) {
            // the same answer, correctly signed, is accepted: only the signature makes the difference below
            answer(200, body, "good");
            call.getValue().run();
            for (String signing : new String[] {"none", "wrong", "swapped"}) {
                answer(200, body, signing);
                PamException e = failure(call.getValue());
                assertThat(e.code()).as("%s, %s signature", call.getKey(), signing).isEqualTo("INVALID_SIGNATURE");
                assertThat(e.outcomeUnknown()).as("%s, %s signature", call.getKey(), signing).isTrue();
            }
            // a refusal is only definite when it is signed: an unsigned 4xx could come from anyone, so the outcome stays unknown
            answer(409, "{\"code\":\"ROUND_SETTLED\"}", "none");
            assertThat(failure(call.getValue()).outcomeUnknown()).as("%s, unsigned refusal", call.getKey()).isTrue();
        }
    }

    /** Review 5.1 #8: a signed answer naming another transaction is refused for payments and refunds too, not only bets. */
    @Test
    void aCreditOrRollbackAnswerForAnotherTransactionIsNotAccepted() {
        answer(200, "{\"transactionId\":\"tx-OTHER\",\"balance\":98.00,\"reference\":\"ref\"}", "good");
        PamException credit = failure(() -> pam.credit(new PamClient.WalletRequest("p1", "tok", "tx-1", "r1", new BigDecimal("2.00"), "USD")));
        assertThat(credit.code()).isEqualTo("INVALID_RESPONSE");
        assertThat(credit.outcomeUnknown()).isTrue();
        PamException rollback = failure(() -> pam.rollback(new PamClient.RollbackRequest("p1", "tok", "rb-1", "tx-1", "r1")));
        assertThat(rollback.code()).isEqualTo("INVALID_RESPONSE");
        assertThat(rollback.outcomeUnknown()).isTrue();
    }

    @Test
    void aSignedAnswerForAnotherTransactionIsNotAcceptedAsThisOne() {
        answer(200, "{\"transactionId\":\"tx-OLD\",\"balance\":98.00,\"reference\":\"ref\"}", "good");
        PamException e = failure(this::debit);
        assertThat(e.code()).isEqualTo("INVALID_RESPONSE");
        assertThat(e.outcomeUnknown()).isTrue();
    }

    @Test
    void aRefusalIsDefiniteAndKeepsItsCode() {
        answer(402, "{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"no money\"}", "good");
        PamException e = failure(this::debit);
        assertThat(e.code()).isEqualTo(PamException.INSUFFICIENT_FUNDS);
        assertThat(e.outcomeUnknown()).isFalse();
        assertThat(e.refusal()).isTrue();
        // R10: the same code with 409, or a code not on the list, may mean the wallet holds the transaction
        answer(409, "{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"no money\"}", "good");
        assertThat(failure(this::debit).refusal()).isFalse();
        answer(400, "{\"code\":\"DUPLICATE_TRANSACTION\",\"message\":\"seen it\"}", "good");
        assertThat(failure(this::debit).refusal()).isFalse();
        answer(409, "{\"code\":\"ROUND_SETTLED\",\"message\":\"paid\"}", "good");
        assertThat(failure(() -> pam.rollback(new PamClient.RollbackRequest("p1", "tok", "rb-1", "tx-1", "r1"))).code())
                .isEqualTo("ROUND_SETTLED");
    }

    @Test
    void serverErrorsLeaveTheOutcomeUnknown() {
        answer(503, "{\"code\":\"DOWN\"}", "good");
        PamException e = failure(this::debit);
        assertThat(e.outcomeUnknown()).isTrue();
        // a 5xx without even a signature: still unknown, never a refusal
        answer(500, "oops", "none");
        assertThat(failure(this::debit).outcomeUnknown()).isTrue();
    }

    @Test
    void malformedAnswersAreNotTrusted() {
        answer(200, "not json", "good");
        assertThat(failure(this::debit).code()).isEqualTo("INVALID_RESPONSE");
        answer(200, "{\"transactionId\":\"tx-1\",\"reference\":\"r\"}", "good");
        assertThat(failure(this::debit).code()).isEqualTo("INVALID_RESPONSE");
        answer(200, "{\"transactionId\":\"tx-1\",\"balance\":\"lots\",\"reference\":\"r\"}", "good");
        assertThat(failure(this::debit).code()).isEqualTo("INVALID_RESPONSE");
        answer(200, "{\"username\":\"a\",\"currency\":\"USD\",\"balance\":5}", "good");
        assertThat(failure(() -> pam.authenticate("tok")).code()).isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void aWalletThatDoesNotAnswerInTimeLeavesTheOutcomeUnknown() {
        next.set(new Answer(200, "{\"transactionId\":\"tx-1\",\"balance\":98,\"reference\":\"r\"}", "good", 2000));
        PamException e = failure(this::debit);
        assertThat(e.code()).isEqualTo("CONNECTION_ERROR");
        assertThat(e.outcomeUnknown()).isTrue();
    }

    @Test
    void anUnreachableWalletLeavesTheOutcomeUnknown() {
        server.stop(0);
        PamException e = failure(this::debit);
        assertThat(e.code()).isEqualTo("CONNECTION_ERROR");
        assertThat(e.outcomeUnknown()).isTrue();
    }

    @Test
    void authenticateAndBalanceReadTheSignedAnswer() {
        answer(200, "{\"playerId\":\"p1\",\"username\":\"alice\",\"currency\":\"EUR\",\"balance\":\"12.50\"}", "good");
        PamClient.PamPlayer player = pam.authenticate("tok");
        assertThat(player.playerId()).isEqualTo("p1");
        assertThat(player.currency()).isEqualTo("EUR");
        assertThat(player.balance()).isEqualByComparingTo("12.50");
        answer(200, "{\"balance\":7.25,\"currency\":\"EUR\"}", "good");
        assertThat(pam.balance("p1", "tok")).isEqualByComparingTo("7.25");
    }

    @Test
    void aRepeatTheWalletReportsIsAcceptedWithItsOriginalAnswer() {
        answer(200, "{\"transactionId\":\"tx-1\",\"balance\":98.00,\"reference\":\"ref-1\",\"repeated\":true}", "good");
        assertThat(debit().balance()).isEqualByComparingTo("98.00");
    }

    @Test
    void unsafeSettingsAreRefusedAtStartUp() {
        assertThatThrownBy(() -> client("http://pam.example.com", false, SECRET).checkConfig())
                .hasMessageContaining("https://");
        assertThatThrownBy(() -> client("https://pam.example.com", false, "short").checkConfig())
                .hasMessageContaining("at least 32");
        client("https://pam.example.com", false, SECRET).checkConfig();
    }

    @Test
    void theTransactionListIsReadFromASignedAnswer() {
        answer(200, "{\"transactions\":[{\"transactionId\":\"t1\",\"type\":\"DEBIT\",\"roundId\":\"r1\",\"playerId\":\"p1\","
                + "\"amount\":1.50,\"time\":\"2026-10-10T03:00:00Z\"}],\"next\":\"t1\"}", "good");
        PamClient.TransactionPage page = pam.transactions(java.time.Instant.parse("2026-10-10T00:00:00Z"), java.time.Instant.parse("2026-10-11T00:00:00Z"), null);
        assertThat(page.transactions()).singleElement().satisfies(t -> {
            assertThat(t.transactionId()).isEqualTo("t1");
            assertThat(t.amount()).isEqualByComparingTo("1.50");
        });
        assertThat(page.next()).isEqualTo("t1");
        assertThat(lastRequestBody.get()).contains("\"gameId\":\"plinko\"", "\"from\":\"2026-10-10T00:00:00Z\"");
        answer(200, "{\"transactions\":[]}", "none");
        assertThat(failure(() -> pam.transactions(java.time.Instant.EPOCH, java.time.Instant.now(), null)).code()).isEqualTo("INVALID_SIGNATURE");
        answer(200, "{\"nothing\":1}", "good");
        assertThat(failure(() -> pam.transactions(java.time.Instant.EPOCH, java.time.Instant.now(), null)).code()).isEqualTo("INVALID_RESPONSE");
    }

    /** Review 7.1: a transaction list with an unreadable time, or not a list, is not trusted; the last page has no next. */
    @Test
    void aTransactionListWithAnUnreadableTimeIsNotTrusted() {
        answer(200, "{\"transactions\":[{\"transactionId\":\"t1\",\"type\":\"DEBIT\",\"playerId\":\"p1\",\"amount\":1,\"time\":\"yesterday\"}]}", "good");
        PamException e = failure(() -> pam.transactions(java.time.Instant.EPOCH, java.time.Instant.now(), null));
        assertThat(e.code()).isEqualTo("INVALID_RESPONSE");
        assertThat(e.outcomeUnknown()).isTrue();
        answer(200, "{\"transactions\":{\"t1\":1}}", "good");
        assertThat(failure(() -> pam.transactions(java.time.Instant.EPOCH, java.time.Instant.now(), null)).code()).isEqualTo("INVALID_RESPONSE");
        answer(200, "{\"transactions\":[],\"next\":null}", "good");
        assertThat(pam.transactions(java.time.Instant.EPOCH, java.time.Instant.now(), "t0").next()).isNull();
        assertThat(lastRequestBody.get()).contains("\"after\":\"t0\"");
    }

    @Test
    void aBaseUrlEndingInASlashAndAnUnsignedRedirectAreHandled() {
        pam = client("http://127.0.0.1:" + server.getAddress().getPort() + "/wallet/", true, SECRET);
        answer(200, "{\"balance\":7.25}", "good");
        assertThat(pam.balance("p1", "tok")).isEqualByComparingTo("7.25");
        assertThat(pam.baseUrl()).endsWith("/wallet/");
        // anything that is neither a success nor a refusal leaves the outcome unknown
        answer(302, "{\"code\":\"MOVED\"}", "good");
        assertThat(failure(this::debit).outcomeUnknown()).isTrue();
    }

    @Test
    void anInterruptedCallLeavesTheOutcomeUnknown() {
        answer(200, "{\"transactionId\":\"tx-1\",\"balance\":98,\"reference\":\"r\"}", "good");
        Thread.currentThread().interrupt();
        try {
            PamException e = failure(this::debit);
            assertThat(e.code()).isEqualTo("CONNECTION_ERROR");
            assertThat(e.outcomeUnknown()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingSettingsAreRefusedAtStartUp() {
        assertThatThrownBy(() -> client("", false, SECRET).checkConfig()).hasMessageContaining("must be set");
        assertThatThrownBy(() -> client("https://pam.example.com", false, " ").checkConfig()).hasMessageContaining("must be set");
    }

    /**
     * Review N2 / R4: only a signed refusal proves the money did not move (stored as the transaction's refused flag, not
     * in its error text); a reused id or a settled round is not a refusal.
     */
    @Test
    void storedErrorsTellARefusalFromAnAnswerThatHoldsTheId() {
        PamException refused = new PamException(PamException.INSUFFICIENT_FUNDS, "no money", false);
        assertThat(refused.refusal()).isTrue();
        assertThat(refused.stored()).isEqualTo("INSUFFICIENT_FUNDS: no money");
        assertThat(new PamException("ROUND_SETTLED", "paid", false).refusal()).isFalse();
        assertThat(new PamException("TRANSACTION_MISMATCH", "other", false).refusal()).isFalse();
        assertThat(new PamException("CONNECTION_ERROR", "timeout", true).refusal()).isFalse();
        assertThat(PamException.notSent("blocked")).isEqualTo("NOT_SENT: blocked");
    }

    /** Review R10: a refusal is a code on the list, and never an answer with HTTP 409; every other 4xx may hold the id. */
    @Test
    void onlyTheListedCodesAreRefusals() {
        for (String code : PamException.REFUSALS) {
            assertThat(new PamException(code, "no", false, 403).refusal()).as(code).isTrue();
            assertThat(new PamException(code, "no", false, 409).refusal()).as(code + " with 409").isFalse();
            assertThat(new PamException(code, "no", true, 503).refusal()).as(code + " with 503").isFalse();
        }
        assertThat(PamException.REFUSALS).containsExactlyInAnyOrder("INSUFFICIENT_FUNDS", "INVALID_TOKEN", "LIMIT_REACHED", "PLAYER_EXCLUDED",
                "PLAYER_BLOCKED");
        for (String code : java.util.List.of("DUPLICATE_TRANSACTION", "TRANSACTION_MISMATCH", "ROUND_SETTLED", "PLAYER_NOT_FOUND", "INVALID_AMOUNT",
                "HTTP_400", "SOMETHING_NEW")) {
            assertThat(new PamException(code, "no", false, 400).refusal()).as(code).isFalse();
        }
    }
}
