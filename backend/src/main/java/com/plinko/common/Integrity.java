package com.plinko.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.plinko.config.PlinkoProperties;
import org.springframework.stereotype.Component;

/**
 * HMAC signatures for data that must not be changed outside the application: odds tables, admin accounts,
 * change requests and wallet transactions. The key lives only in the environment (ROUND_SIGNING_KEY), so
 * someone with database access alone cannot produce a valid signature.
 * <p>
 * Every signature starts with a purpose, so a signature made for one kind of record never verifies as
 * another. Fields are length-prefixed, so different field values can never produce the same signed text.
 */
@Component
public class Integrity {

    private final KeyRing keys;

    public Integrity(PlinkoProperties properties) {
        this(new KeyRing(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Integrity(KeyRing keys) {
        this.keys = keys;
    }

    /** Money signatures (credits, refunds, settlements) use the round keys; every other record the record keys (review 7.2). */
    static KeyRing.Use useOf(String purpose) {
        return switch (purpose) {
            case "credit", "rollback", "settlement", "debit" -> KeyRing.Use.ROUNDS;
            default -> KeyRing.Use.RECORDS;
        };
    }

    /**
     * True if this server holds the key a stored signature names (for a record made at {@code madeAt}); false points at
     * the setup (a key list missing a key, the original key retired) rather than at an edit of the record.
     */
    public boolean hasKeyFor(String signature, String purpose, java.time.Instant madeAt) {
        if (signature == null || signature.length() != 64) {
            return false;
        }
        String id = KeyRing.keyIdOf(signature);
        return "original".equals(id) ? keys.legacy() != null && keys.legacyAccepted(madeAt) : keys.byId(useOf(purpose), id).isPresent();
    }

    /** True while signatures of this purpose are still made with the original key (no key list for its use is set). */
    /** True once the original key is retired (LEGACY_KEY_UNTIL has passed): an old-format signature then authorises no money. */
    public boolean legacyRetired() {
        return keys.legacyRetired();
    }

    /** When the original key was (or will be) retired, if a cutoff is set. */
    public java.util.Optional<java.time.Instant> legacyUntil() {
        return keys.legacyUntil();
    }

    public boolean signsWithLegacy(String purpose) {
        return keys.current(useOf(purpose)).isEmpty();
    }

    public String sign(String purpose, Object... fields) {
        return keys.sign(useOf(purpose), encode(purpose, fields));
    }

    /** Verifies with the key the signature names, so records signed before a key rotation still verify. */
    public boolean verify(String signature, String purpose, Object... fields) {
        return keys.verify(useOf(purpose), encode(purpose, fields), signature);
    }

    /** As {@link #verify}, for a record made at {@code madeAt} (history signed before the original key was retired). */
    public boolean verifyMadeAt(java.time.Instant madeAt, String signature, String purpose, Object... fields) {
        return keys.verify(useOf(purpose), encode(purpose, fields), signature, madeAt);
    }

    /**
     * A transaction id derived from the round: every attempt to pay (or refund) a round uses the same id,
     * so the PAM's idempotency turns any repeat into a no-op instead of a second payment.
     */
    public String derivedId(String purpose, String roundId) {
        return derivedId(keys.current(KeyRing.Use.ROUNDS).map(KeyRing.Key::secret).orElse(keys.legacy()), purpose, roundId);
    }

    /** True if {@code id} is this round's derived id under any round key, current or older (a payment begun before a rotation). */
    public boolean isDerivedId(String id, String purpose, String roundId) {
        if (id == null) {
            return false;
        }
        if (keys.legacy() != null && id.equals(derivedId(keys.legacy(), purpose, roundId))) {
            return true;
        }
        return keys.all(KeyRing.Use.ROUNDS).stream().anyMatch(k -> id.equals(derivedId(k.secret(), purpose, roundId)));
    }

    private static String derivedId(String key, String purpose, String roundId) {
        byte[] hash = Crypto.hmac("HmacSHA256", key.getBytes(StandardCharsets.UTF_8),
                encode("id:" + purpose, new Object[] {roundId}).getBytes(StandardCharsets.UTF_8));
        long most = 0, least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (hash[i] & 0xff);
            least = (least << 8) | (hash[i + 8] & 0xff);
        }
        most = (most & ~0xF000L) | 0x8000L;               // version 8 (custom)
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // IETF variant
        return new UUID(most, least).toString();
    }

    private static String encode(String purpose, Object[] fields) {
        StringBuilder out = new StringBuilder(purpose);
        for (Object field : fields) {
            String text = field == null ? "\u0000null" : field instanceof java.math.BigDecimal d ? d.stripTrailingZeros().toPlainString() : field.toString();
            out.append('|').append(text.length()).append(':').append(text);
        }
        return out.toString();
    }
}
