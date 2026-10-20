package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import com.plinko.TestFixtures;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

class WalletSignerTest {

    private final Integrity integrity = new Integrity(TestFixtures.properties());
    private final WalletSigner signer = new WalletSigner(integrity);

    private final GameRepository.Round round = new GameRepository.Round("round-1", 7, "s", 8, 2, new BigDecimal("1.00"), 3,
            new BigDecimal("3.00"), new BigDecimal("29.50"), "EUR", RoundStatus.CREDIT_PENDING, "0,1,2", "0.2,29,0.3", null, "sig", null, null);
    private final GameRepository.Player player = new GameRepository.Player(7, "pam-player-7", "alice", "EUR", null, false, Instant.now(), Instant.now());

    private GameRepository.Transaction credit(String id, long playerId, BigDecimal amount, String signature) {
        return new GameRepository.Transaction(id, "round-1", playerId, "CREDIT", amount, "FAILED", null, null, null, 1, null, null, signature);
    }

    @Test
    void creditAndRollbackIdsAreFixedPerRoundAndDistinct() {
        assertThat(signer.creditId("round-1")).isEqualTo(signer.creditId("round-1")).hasSize(36);
        assertThat(signer.creditId("round-1")).isNotEqualTo(signer.creditId("round-2"));
        assertThat(signer.creditId("round-1")).isNotEqualTo(signer.rollbackId("round-1"));
    }

    @Test
    void acceptsOnlyTheSignedCreditForThisRoundPlayerAndAmount() {
        String id = signer.creditId("round-1");
        String signature = signer.signCredit("round-1", "pam-player-7", new BigDecimal("29.50"), "EUR");
        assertThat(signer.verifyCredit(credit(id, 7, new BigDecimal("29.50"), signature), round, player)).isTrue();

        // a copied credit with a fresh id, a higher amount, no signature, or a redirected PAM player is refused
        assertThat(signer.verifyCredit(credit("11111111-1111-8111-8111-111111111111", 7, new BigDecimal("29.50"), signature), round, player)).isFalse();
        assertThat(signer.verifyCredit(credit(id, 7, new BigDecimal("2950.00"), signature), round, player)).isFalse();
        assertThat(signer.verifyCredit(credit(id, 7, new BigDecimal("29.50"), null), round, player)).isFalse();
        GameRepository.Player accomplice = new GameRepository.Player(7, "pam-accomplice", "bob", "EUR", null, false, Instant.now(), Instant.now());
        assertThat(signer.verifyCredit(credit(id, 7, new BigDecimal("29.50"), signature), round, accomplice)).isFalse();
    }

    @Test
    void signaturesHaveTheirOwnPurposeAndUnambiguousFields() {
        // the same values signed for another purpose, or split differently, never match
        assertThat(integrity.sign("credit", "a|b", "c")).isNotEqualTo(integrity.sign("credit", "a", "b|c"));
        assertThat(integrity.sign("credit", "x")).isNotEqualTo(integrity.sign("rollback", "x"));
        assertThat(integrity.verify(integrity.sign("odds", "t"), "odds", "t")).isTrue();
    }

    @Test
    void onceTheOriginalKeyIsRetiredItNeverAuthorisesAPaymentWhateverTheRowsDate() {
        // N8: a credit signed with the leaked original key and dated before the cutoff (created_at is not signed)
        String id = signer.creditId("round-1");
        String signature = signer.signCredit("round-1", "pam-player-7", new BigDecimal("29.50"), "EUR");
        GameRepository.Transaction backdated = new GameRepository.Transaction(id, "round-1", 7, "CREDIT", new BigDecimal("29.50"), "PENDING", null,
                null, null, 0, Instant.parse("2025-06-01T00:00:00Z"), null, signature);
        com.plinko.common.KeyRing retired = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), "2026-01-01T00:00:00Z");
        assertThat(new WalletSigner(new Integrity(retired)).verifyCredit(backdated, round, player)).isFalse();
        // before the cutoff it is still paid (a credit begun before a rotation)
        com.plinko.common.KeyRing ahead = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), Instant.now().plusSeconds(3600).toString());
        assertThat(new WalletSigner(new Integrity(ahead)).verifyCredit(backdated, round, player)).isTrue();
    }

    @Test
    void aConfirmedTransactionSignedAgainStillVerifiesOnceTheOriginalKeyIsRemoved() {
        // N8 #2: a confirmed credit, refund and bet signed with the original key, signed again with r1 by the re-signing job
        com.plinko.common.KeyRing removed = new com.plinko.common.KeyRing(TestFixtures.propertiesWithoutOriginalKey(), "r1=" + "r".repeat(40),
                "d1=" + "d".repeat(40), "e1=" + "e".repeat(40), "2026-01-01T00:00:00Z");
        Integrity now = new Integrity(removed);
        WalletSigner after = new WalletSigner(now);
        BigDecimal win = new BigDecimal("29.50");
        String creditId = signer.creditId("round-1");
        GameRepository.Transaction credit = new GameRepository.Transaction(creditId, "round-1", 7, "CREDIT", win, "OK", null, null, null, 1, null,
                null, signer.signCredit("round-1", "pam-player-7", win, "EUR"));
        String rollbackId = signer.rollbackId("round-1");
        GameRepository.Transaction rollback = new GameRepository.Transaction(rollbackId, "round-1", 7, "ROLLBACK", BigDecimal.ZERO, "OK", "debit-1",
                null, null, 1, null, null, signer.signRollback("round-1", "pam-player-7", "debit-1"));
        GameRepository.Transaction debit = new GameRepository.Transaction("debit-1", "round-1", 7, "DEBIT", new BigDecimal("3.00"), "OK", null, null,
                null, 1, null, null, signer.signDebit("debit-1", "round-1", "pam-player-7", new BigDecimal("3.00"), "EUR"));
        assertThat(after.verifyCredit(credit, round, player)).as("not signed again yet").isFalse();

        com.plinko.repo.ResignedSignatures resigned = org.mockito.Mockito.mock(com.plinko.repo.ResignedSignatures.class);
        org.mockito.Mockito.when(resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, creditId))
                .thenReturn(java.util.Optional.of(now.sign("credit", creditId, "round-1", "pam-player-7", win, "EUR")));
        org.mockito.Mockito.when(resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, rollbackId))
                .thenReturn(java.util.Optional.of(now.sign("rollback", rollbackId, "round-1", "pam-player-7", "debit-1")));
        org.mockito.Mockito.when(resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, "debit-1"))
                .thenReturn(java.util.Optional.of(now.sign("debit", "debit-1", "round-1", "pam-player-7", new BigDecimal("3.00"), "EUR")));
        after.setResigned(resigned);
        assertThat(after.verifyCredit(credit, round, player)).isTrue();
        assertThat(after.verifyRollback(rollback, round, player)).isTrue();
        assertThat(after.verifyDebit(debit, round, player)).isTrue();
        // money still to move is never authorised this way, nor an amount changed after it was signed again
        GameRepository.Transaction pending = new GameRepository.Transaction(creditId, "round-1", 7, "CREDIT", win, "PENDING", null, null, null, 0,
                null, null, credit.signature());
        assertThat(after.verifyCredit(pending, round, player)).isFalse();
        GameRepository.Transaction raised = new GameRepository.Transaction(creditId, "round-1", 7, "CREDIT", new BigDecimal("2950.00"), "OK", null,
                null, null, 1, null, null, credit.signature());
        assertThat(after.verifyCredit(raised, round, player)).isFalse();
        assertThat(after.signAgain("credit", creditId, "round-1", "pam-player-7", win, "EUR")).startsWith("r1.");
    }
}
