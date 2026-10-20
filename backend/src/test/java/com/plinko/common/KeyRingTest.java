package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.plinko.TestFixtures;
import com.plinko.bo.game.RoundSigner;
import com.plinko.config.PlinkoProperties;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

/** Review 7.2: key ids, separate keys per use, rotation without breaking what was signed or encrypted before. */
class KeyRingTest {

    private static final String R1 = "r".repeat(40);
    private static final String R2 = "q".repeat(40);
    private static final String D1 = "d".repeat(40);
    private static final String E1 = "e".repeat(40);
    private final PlinkoProperties properties = TestFixtures.properties();

    private KeyRing ring(String rounds, String records, String encryption) {
        return new KeyRing(properties, rounds, records, encryption);
    }

    @Test
    void withoutKeyListsEverythingIsSignedExactlyAsBefore() {
        KeyRing plain = new KeyRing(properties);
        assertThat(plain.sign(KeyRing.Use.ROUNDS, "data")).isEqualTo(Crypto.hmacSha256Hex(properties.roundSigningKey(), "data"));
        assertThat(new SecretBox(properties).encrypt("totp", "x")).startsWith("v1:");
        assertThat(plain.currentIds()).containsValues("original");
    }

    @Test
    void newSignaturesNameTheirKeyAndOldOnesStillVerify() {
        String old = new KeyRing(properties).sign(KeyRing.Use.ROUNDS, "round");
        KeyRing rotated = ring("r1=" + R1, "", "");
        String fresh = rotated.sign(KeyRing.Use.ROUNDS, "round");
        assertThat(fresh).hasSize(64).startsWith("r1.");
        assertThat(KeyRing.keyIdOf(fresh)).isEqualTo("r1");
        assertThat(rotated.verify(KeyRing.Use.ROUNDS, "round", old)).isTrue();
        assertThat(rotated.verify(KeyRing.Use.ROUNDS, "round", fresh)).isTrue();
        assertThat(rotated.verify(KeyRing.Use.ROUNDS, "other", fresh)).isFalse();
        // the next rotation: r2 signs, r1 still verifies; once r1 is removed its signatures no longer do
        KeyRing next = ring("r2=" + R2 + ",r1=" + R1, "", "");
        assertThat(next.sign(KeyRing.Use.ROUNDS, "round")).startsWith("r2.");
        assertThat(next.verify(KeyRing.Use.ROUNDS, "round", fresh)).isTrue();
        assertThat(ring("r2=" + R2, "", "").verify(KeyRing.Use.ROUNDS, "round", fresh)).isFalse();
    }

    @Test
    void aKeyOfOneUseNeverVerifiesAnother() {
        KeyRing keys = ring("k1=" + R1, "k1=" + D1, "");
        String money = keys.sign(KeyRing.Use.ROUNDS, "x");
        assertThat(keys.verify(KeyRing.Use.ROUNDS, "x", money)).isTrue();
        assertThat(keys.verify(KeyRing.Use.RECORDS, "x", money)).isFalse();
        Integrity integrity = new Integrity(keys);
        assertThat(integrity.sign("credit", "a")).isEqualTo(keys.sign(KeyRing.Use.ROUNDS, "credit|1:a"));
        assertThat(integrity.sign("odds", "a")).isEqualTo(keys.sign(KeyRing.Use.RECORDS, "odds|1:a"));
    }

    @Test
    void roundsSignedBeforeARotationStillVerify() {
        BigDecimal one = BigDecimal.ONE;
        String before = new RoundSigner(properties).sign("a", 7, 8, 2, one, 1, one, one, "EUR", "0", "1");
        GameRepository.Round round = new GameRepository.Round("a", 7, "s", 8, 2, one, 1, one, one, "EUR", "COMPLETED", "0", "1", null,
                before, null, null, null, null, null);
        RoundSigner rotated = new RoundSigner(ring("r1=" + R1, "", ""));
        assertThat(rotated.verify(round)).isTrue();
        String after = rotated.sign("a", 7, 8, 2, one, 1, one, one, "EUR", "0", "1");
        assertThat(after).startsWith("r1.");
        assertThat(new RoundSigner(properties).verify(new GameRepository.Round("a", 7, "s", 8, 2, one, 1, one, one, "EUR", "COMPLETED", "0",
                "1", null, after, null, null, null, null, null))).isFalse();
    }

    @Test
    void paymentsBegunBeforeARotationKeepTheirId() {
        String credit = new Integrity(properties).derivedId("credit", "round-1");
        Integrity rotated = new Integrity(ring("r1=" + R1, "", ""));
        assertThat(rotated.derivedId("credit", "round-1")).isNotEqualTo(credit);
        assertThat(rotated.isDerivedId(credit, "credit", "round-1")).isTrue();
        assertThat(rotated.isDerivedId(rotated.derivedId("credit", "round-1"), "credit", "round-1")).isTrue();
        assertThat(rotated.isDerivedId(credit, "rollback", "round-1")).isFalse();
    }

    @Test
    void encryptedValuesSurviveARotation() {
        String old = new SecretBox(properties).encrypt("totp", "SECRET");
        SecretBox rotated = new SecretBox(ring("", "", "e1=" + E1));
        String fresh = rotated.encrypt("totp", "SECRET");
        assertThat(fresh).startsWith("v2:e1:");
        assertThat(SecretBox.isEncrypted(fresh)).isTrue();
        assertThat(rotated.decrypt("totp", old)).isEqualTo("SECRET");
        assertThat(rotated.decrypt("totp", fresh)).isEqualTo("SECRET");
        assertThat(rotated.decrypt("pam", fresh)).isNull();
        assertThat(new SecretBox(properties).decrypt("totp", fresh)).isNull();
    }

    @Test
    void badKeyListsStopTheStart() {
        assertThatThrownBy(() -> ring("r1=short", "", "")).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> ring("R1=" + R1, "", "")).hasMessageContaining("two lowercase");
        assertThatThrownBy(() -> ring("r1=" + R1 + ",r1=" + R2, "", "")).hasMessageContaining("twice");
        assertThat(ring("r1=" + R1, "", "").toString()).doesNotContain(R1);
        assertThat(ring("r1=" + R1, "", "").current(KeyRing.Use.ROUNDS).orElseThrow().toString()).doesNotContain(R1);
    }

    // ---------------------------------------------------------------- N8: retiring the original key

    @Test
    void retiringTheOriginalKeyNeedsTheKeyListsAndAValidTime() {
        assertThatThrownBy(() -> new KeyRing(properties, "r1=" + R1, "", "", "2026-11-01T00:00:00Z")).hasMessageContaining("RECORD_KEYS");
        assertThatThrownBy(() -> new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, "next week")).hasMessageContaining("UTC time");
        assertThat(new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, "2026-11-01T00:00:00Z").legacyUntil()).isPresent();
    }

    @Test
    void onceRetiredTheOriginalKeyVerifiesOnlyHistoryMadeBeforeThen() {
        java.time.Instant cutoff = java.time.Instant.parse("2026-01-01T00:00:00Z");
        String old = new KeyRing(properties).sign(KeyRing.Use.ROUNDS, "round");
        KeyRing retired = new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, cutoff.toString());
        assertThat(retired.verify(KeyRing.Use.ROUNDS, "round", old, cutoff.minusSeconds(1))).isTrue();
        // a forged record dated after the cutoff, or one without a date (settings, accounts, ledger), is refused
        assertThat(retired.verify(KeyRing.Use.ROUNDS, "round", old, cutoff)).isFalse();
        assertThat(retired.verify(KeyRing.Use.ROUNDS, "round", old)).isFalse();
        assertThat(retired.verify(KeyRing.Use.RECORDS, "x", new KeyRing(properties).sign(KeyRing.Use.RECORDS, "x"))).isFalse();
        // what the current keys sign still verifies whatever its date
        assertThat(retired.verify(KeyRing.Use.ROUNDS, "round", retired.sign(KeyRing.Use.ROUNDS, "round"))).isTrue();
        assertThat(KeyRing.isLegacy(old)).isTrue();
        assertThat(KeyRing.isLegacy(retired.sign(KeyRing.Use.ROUNDS, "round"))).isFalse();
    }

    @Test
    void roundsPlayedBeforeTheCutoffStillVerify() {
        java.time.Instant cutoff = java.time.Instant.parse("2026-01-01T00:00:00Z");
        BigDecimal one = BigDecimal.ONE;
        String before = new RoundSigner(properties).sign("a", 7, 8, 2, one, 1, one, one, "EUR", "0", "1");
        RoundSigner retired = new RoundSigner(new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, cutoff.toString()));
        assertThat(retired.verify(new GameRepository.Round("a", 7, "s", 8, 2, one, 1, one, one, "EUR", "COMPLETED", "0", "1", null,
                before, cutoff.minusSeconds(60), null, null, null, null))).isTrue();
        assertThat(retired.verify(new GameRepository.Round("a", 7, "s", 8, 2, one, 1, one, one, "EUR", "COMPLETED", "0", "1", null,
                before, cutoff.plusSeconds(60), null, null, null, null))).isFalse();
    }

    @Test
    void onceRetiredNothingIsDecryptedWithTheOriginalKey() {
        String old = new SecretBox(properties).encrypt("totp", "SECRET");
        assertThat(SecretBox.isLegacy(old)).isTrue();
        assertThat(new SecretBox(new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, "")).decrypt("totp", old)).isEqualTo("SECRET");
        assertThat(new SecretBox(new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, "2026-01-01T00:00:00Z")).decrypt("totp", old)).isNull();
        // a cutoff still ahead isn't in force yet: set together with the key lists, start-up moves things first
        String future = java.time.Instant.now().plusSeconds(86_400).toString();
        assertThat(new SecretBox(new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, future)).decrypt("totp", old)).isEqualTo("SECRET");
        KeyRing ahead = new KeyRing(properties, "r1=" + R1, "d1=" + D1, "e1=" + E1, future);
        assertThat(ahead.legacyRetired()).isFalse();
        assertThat(ahead.verify(KeyRing.Use.RECORDS, "x", new KeyRing(properties).sign(KeyRing.Use.RECORDS, "x"))).isTrue();
    }
}
