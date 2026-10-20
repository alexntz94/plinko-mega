package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import com.plinko.common.Integrity;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import com.plinko.repo.SettlementRepository;
import org.junit.jupiter.api.Test;

/**
 * Review R4: a credit the wallet once refused is sent again by the background retry; the wallet pays it, and the
 * process dies before the answer is stored. The earlier refusal must not survive that send: once the round is blocked,
 * a settlement may not pay or refund it (the wallet may have paid), only close it.
 */
class StaleRefusalTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);
    private final BigDecimal start = game.balance();
    private final RoundSettlementService settlements = new RoundSettlementService(game.repository, mock(SettlementRepository.class), game.wallet,
            new Integrity(game.properties), game.audit, game.winCheck, game.settings, new BigDecimal("1000"), "", new BigDecimal("100"), "", 90, 24, 10);

    StaleRefusalTest() {
        // the settlement limits as stored settings (R9 #1)
        org.mockito.Mockito.when(game.settings.settlementLimit(com.plinko.bo.settings.SettingsService.SETTLEMENT_MAX)).thenReturn(java.util.Optional.of("EUR=1000"));
        org.mockito.Mockito.when(game.settings.settlementLimit(com.plinko.bo.settings.SettingsService.SETTLEMENT_OTHER)).thenReturn(java.util.Optional.of("EUR=100"));
    }

    private GameRepository.Round round() {
        return game.repository.rounds().values().iterator().next();
    }

    private GameRepository.Transaction credit() {
        return game.repository.transactionsOf(round().id()).stream().filter(tx -> "CREDIT".equals(tx.type())).findFirst().orElseThrow();
    }

    private RoundSettlementService.Proposal proposal(String action, BigDecimal amount) {
        return new RoundSettlementService.Proposal(round().id(), action, amount, "Checked with the casino's wallet team, ticket 42");
    }

    /** A round whose credit the wallet definitely refused: unpaid, waiting for the background retry. */
    private void playARefusedCredit() {
        game.wallet.next("credit", new MemoryWallet.Refuse("INVALID_TOKEN"));
        game.play("1.00", 3);
        assertThat(round().status()).isEqualTo(RoundStatus.CREDIT_PENDING);
        assertThat(credit().refused()).as("the wallet's refusal is recorded").isTrue();
    }

    @Test
    void aRefusalThatStillStandsLetsABlockedRoundBeSettled() {
        playARefusedCredit();
        game.recovery.block(round(), "credit blocked: C6 test");
        assertThat(settlements.problem(proposal("REFUND", round().totalBet()))).isEmpty();
        assertThat(settlements.problem(proposal("PAY", new BigDecimal("1.00")))).isEmpty();
    }

    @Test
    void aResendWhoseAnswerWasNeverStoredIsNoLongerARefusal() {
        playARefusedCredit();
        GameRepository.Round refused = round();
        // the retry sends it again: the wallet pays, then the process dies before the answer is written
        game.wallet.next("credit", new MemoryWallet.AppliedThen(() -> {
            game.repository.die();
            throw new MemoryGameRepository.ProcessDied("killed with the resent credit in flight");
        }));
        game.recoverInTheBackground();
        game.repository.restart();
        assertThat(game.balance()).as("the wallet paid the win").isEqualByComparingTo(start.subtract(refused.totalBet()).add(refused.totalWin()));
        assertThat(credit().status()).isEqualTo("FAILED");
        assertThat(credit().refused()).as("the resend cleared the old refusal before it was sent").isFalse();
        assertThat(credit().inFlightSince()).as("sent, no answer recorded").isNotNull();

        // the round is blocked before the next retry gets to it
        game.recovery.block(round(), "credit blocked: C6 test");
        assertThat(round().status()).isEqualTo(RoundStatus.TAMPERED);
        assertThat(settlements.problem(proposal("PAY", new BigDecimal("1.00")))).get().asString().contains("may already have applied");
        assertThat(settlements.problem(proposal("REFUND", round().totalBet()))).get().asString().contains("may already have applied");
        assertThat(settlements.problem(proposal("CLOSE", BigDecimal.ZERO))).isEmpty();
    }

    @Test
    void dyingWhileTheLiveCreditIsInFlightLeavesItUnknown() {
        game.wallet.next("credit", new MemoryWallet.AppliedThen(() -> {
            game.repository.die();
            throw new MemoryGameRepository.ProcessDied("killed with the payment in flight");
        }));
        try {
            game.play("1.00", 3);
        } catch (MemoryGameRepository.ProcessDied expected) {
            // the process is gone
        }
        game.repository.restart();
        assertThat(credit().refused()).isFalse();
        assertThat(credit().inFlightSince()).as("marked as sending before the wallet was called").isNotNull();
        game.recovery.block(round(), "credit blocked: C6 test");
        assertThat(settlements.problem(proposal("PAY", new BigDecimal("1.00")))).get().asString().contains("may already have applied");
    }
}
