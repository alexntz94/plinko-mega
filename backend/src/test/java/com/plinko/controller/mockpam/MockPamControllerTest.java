package com.plinko.controller.mockpam;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.plinko.NoTransactions;
import com.plinko.TestFixtures;
import com.plinko.common.Crypto;
import com.plinko.repo.MockWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review 5.2 (R1.2): the mock wallet, the reference for the casino's, refuses a transaction id reused for a different
 * transaction with 409 TRANSACTION_MISMATCH and moves no money, for refunds as for bets and payments; a true repeat gets
 * the first answer again.
 */
class MockPamControllerTest {

    /** The mock wallet's tables in memory. */
    private static final class Wallets extends MockWalletRepository {
        final Map<String, BigDecimal> balances = new LinkedHashMap<>();
        final Map<String, Stored> transactions = new LinkedHashMap<>();

        Wallets() {
            super(null);
        }

        @Override
        public Optional<Wallet> lock(String playerId) {
            return Optional.ofNullable(balances.get(playerId)).map(b -> new Wallet(playerId, playerId, "EUR", b));
        }

        @Override
        public Optional<Stored> stored(String transactionId) {
            return Optional.ofNullable(transactions.get(transactionId));
        }

        @Override
        public boolean rolledBack(String transactionId) {
            return transactions.values().stream().anyMatch(t -> transactionId.equals(t.originalId()));
        }

        @Override
        public boolean roundPaid(String roundId) {
            return transactions.values().stream().anyMatch(t -> "CREDIT".equals(t.type()) && roundId.equals(t.roundId()));
        }

        @Override
        public Optional<BigDecimal> debit(String transactionId, String playerId, String roundId) {
            return Optional.ofNullable(transactions.get(transactionId))
                    .filter(t -> "DEBIT".equals(t.type()) && t.playerId().equals(playerId) && t.roundId().equals(roundId)).map(Stored::amount);
        }

        @Override
        public void record(String transactionId, String playerId, String type, BigDecimal amount, BigDecimal balance, String roundId, String originalId) {
            balances.put(playerId, balance);
            transactions.put(transactionId, new Stored(playerId, type, amount, balance, roundId, originalId));
        }
    }

    private final Wallets wallets = new Wallets();
    private final JsonMapper json = JsonMapper.builder().build();
    private final MockPamController pam = new MockPamController(wallets, json, TestFixtures.properties(), new NoTransactions());

    @BeforeEach
    void setUp() {
        wallets.balances.put("p1", new BigDecimal("100.00"));
        wallets.balances.put("p2", new BigDecimal("100.00"));
    }

    private ResponseEntity<String> call(String endpoint, Map<String, Object> body) {
        String text = json.writeValueAsString(body);
        return pam.handle(endpoint, "key", Crypto.hmacSha256Hex(TestFixtures.properties().pam().secret(), text), text);
    }

    private ResponseEntity<String> debit(String id, String round, String amount) {
        return call("debit", Map.of("playerId", "p1", "transactionId", id, "roundId", round, "amount", new BigDecimal(amount), "currency", "EUR"));
    }

    private ResponseEntity<String> rollback(String id, String original, String round, String player) {
        return call("rollback", Map.of("playerId", player, "transactionId", id, "originalTransactionId", original, "roundId", round));
    }

    private static String code(ResponseEntity<String> answer) {
        return JsonMapper.builder().build().readTree(answer.getBody()).path("code").asString("");
    }

    @Test
    void aRollbackIdReusedForAnotherBetRoundOrPlayerIsRefusedAndMovesNothing() {
        debit("d-1", "r1", "5.00");
        debit("d-2", "r2", "7.00");
        assertThat(rollback("rb-1", "d-1", "r1", "p1").getStatusCode().value()).isEqualTo(200);
        assertThat(wallets.balances.get("p1")).isEqualByComparingTo("93.00");
        Map<String, BigDecimal> before = Map.copyOf(wallets.balances);
        int recorded = wallets.transactions.size();

        for (ResponseEntity<String> reused : java.util.List.of(rollback("rb-1", "d-2", "r2", "p1"), rollback("rb-1", "d-1", "r2", "p1"),
                rollback("rb-1", "d-1", "r1", "p2"))) {
            assertThat(reused.getStatusCode().value()).isEqualTo(409);
            assertThat(code(reused)).isEqualTo("TRANSACTION_MISMATCH");
        }
        assertThat(wallets.balances).as("no money moved").isEqualTo(before);
        assertThat(wallets.transactions).hasSize(recorded);

        // the same refund again is a repeat: the first answer, nothing moved
        ResponseEntity<String> again = rollback("rb-1", "d-1", "r1", "p1");
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody()).contains("\"repeated\":true");
        assertThat(wallets.balances).isEqualTo(before);
    }

    @Test
    void aBetOrPaymentIdReusedForAnotherAmountRoundOrKindIsRefused() {
        debit("d-1", "r1", "5.00");
        call("credit", Map.of("playerId", "p1", "transactionId", "c-1", "roundId", "r1", "amount", new BigDecimal("2.00"), "currency", "EUR"));
        Map<String, BigDecimal> before = Map.copyOf(wallets.balances);
        for (ResponseEntity<String> reused : java.util.List.of(debit("d-1", "r1", "6.00"), debit("d-1", "r9", "5.00"), debit("c-1", "r1", "2.00"),
                call("credit", Map.of("playerId", "p1", "transactionId", "c-1", "roundId", "r1", "amount", new BigDecimal("200.00"), "currency", "EUR")))) {
            assertThat(reused.getStatusCode().value()).isEqualTo(409);
            assertThat(code(reused)).isEqualTo("TRANSACTION_MISMATCH");
        }
        assertThat(wallets.balances).isEqualTo(before);
    }

    @Test
    void aRefundOfAPaidRoundIsRefusedAsSettledAndALateBetAfterItsRollbackIsRefused() {
        debit("d-1", "r1", "5.00");
        call("credit", Map.of("playerId", "p1", "transactionId", "c-1", "roundId", "r1", "amount", new BigDecimal("2.00"), "currency", "EUR"));
        ResponseEntity<String> settled = rollback("rb-1", "d-1", "r1", "p1");
        assertThat(settled.getStatusCode().value()).isEqualTo(409);
        assertThat(code(settled)).isEqualTo("ROUND_SETTLED");
        // R1.3/R1.4: a rollback of a bet that never arrived is accepted and refunds nothing; the bet arriving late is refused
        assertThat(rollback("rb-2", "d-2", "r2", "p1").getStatusCode().value()).isEqualTo(200);
        BigDecimal balance = wallets.balances.get("p1");
        ResponseEntity<String> late = debit("d-2", "r2", "5.00");
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("TRANSACTION_ROLLED_BACK");
        assertThat(wallets.balances.get("p1")).isEqualByComparingTo(balance);
    }
}
