package com.plinko.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.plinko.config.PlinkoProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The application's secret keys, by use (review 7.2), so a leak of one doesn't expose the others:
 * <ul>
 *   <li>{@link Use#ROUNDS}: round signatures and the money they may move (credits, refunds, settlements);</li>
 *   <li>{@link Use#RECORDS}: odds, settings, admin accounts, change requests and the integrity ledger;</li>
 *   <li>{@link Use#ENCRYPTION}: the AES key for stored secrets (2FA secrets, PAM tokens).</li>
 * </ul>
 * Each use has a list of keys, {@code ROUND_KEYS}, {@code RECORD_KEYS}, {@code ENCRYPTION_KEYS}: {@code "r2=<secret>,r1=<secret>"},
 * newest first. New data is signed or encrypted with the first and carries its id; older keys stay for verifying and
 * decrypting what was made with them. Rotating = putting a new key in front. Data made before key ids existed (no id)
 * belongs to the original {@code ROUND_SIGNING_KEY}, which is used for everything while a list is empty.
 */
@Component
public class KeyRing {

    public enum Use { ROUNDS, RECORDS, ENCRYPTION }

    /** A key with its id: two lowercase letters or digits, written in front of what it signed. */
    public record Key(String id, String secret) {
        @Override
        public String toString() {
            return "Key[" + id + "]";   // never the secret
        }
    }

    private final String legacy;
    private final Map<Use, List<Key>> keys = new LinkedHashMap<>();
    /**
     * N8: from this time on the original ROUND_SIGNING_KEY is retired: old-format signatures verify only on records made
     * before it (rounds, wallet transactions, odds versions), and nothing else is accepted or decrypted with it. Null:
     * not retired yet.
     */
    private final java.time.Instant legacyUntil;

    /** Only the original key: what tests and installs without key lists use. */
    public KeyRing(PlinkoProperties properties) {
        this(properties, "", "", "");
    }

    public KeyRing(PlinkoProperties properties, String rounds, String records, String encryption) {
        this(properties, rounds, records, encryption, "");
    }

    @Autowired
    public KeyRing(PlinkoProperties properties, @Value("${plinko.keys.rounds:${ROUND_KEYS:}}") String rounds,
                   @Value("${plinko.keys.records:${RECORD_KEYS:}}") String records,
                   @Value("${plinko.keys.encryption:${ENCRYPTION_KEYS:}}") String encryption,
                   @Value("${plinko.keys.legacy-until:${LEGACY_KEY_UNTIL:}}") String legacyUntil) {
        String original = properties.roundSigningKey();
        // N8 #2: once retired (LEGACY_KEY_UNTIL) and everything is signed again, the original key can be removed entirely
        boolean removed = (original == null || original.isBlank()) && legacyUntil != null && !legacyUntil.isBlank();
        this.legacy = removed ? null : original;
        if (!removed && (legacy == null || legacy.length() < 32)) {
            throw new IllegalStateException("ROUND_SIGNING_KEY must be set to at least 32 characters");
        }
        keys.put(Use.ROUNDS, parse("ROUND_KEYS", rounds));
        keys.put(Use.RECORDS, parse("RECORD_KEYS", records));
        keys.put(Use.ENCRYPTION, parse("ENCRYPTION_KEYS", encryption));
        if (legacyUntil == null || legacyUntil.isBlank()) {
            this.legacyUntil = null;
        } else {
            try {
                this.legacyUntil = java.time.Instant.parse(legacyUntil.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalStateException("LEGACY_KEY_UNTIL must be a UTC time like 2026-11-01T00:00:00Z");
            }
            if (keys.values().stream().anyMatch(List::isEmpty)) {
                throw new IllegalStateException("LEGACY_KEY_UNTIL retires ROUND_SIGNING_KEY: set ROUND_KEYS, RECORD_KEYS and ENCRYPTION_KEYS first");
            }
        }
        org.slf4j.LoggerFactory.getLogger(KeyRing.class).info("Keys in use (ids only): {}", currentIds());
    }

    static List<Key> parse(String name, String text) {
        List<Key> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return Collections.unmodifiableList(list);
        }
        for (String part : text.trim().split("\\s*,\\s*")) {
            int eq = part.indexOf('=');
            String id = eq < 0 ? "" : part.substring(0, eq).trim();
            String secret = eq < 0 ? "" : part.substring(eq + 1).trim();
            if (!id.matches("[a-z0-9]{2}")) {
                throw new IllegalStateException(name + ": each key is id=secret, the id two lowercase letters or digits");
            }
            if (secret.length() < 32) {
                throw new IllegalStateException(name + ": key " + id + " must be at least 32 characters");
            }
            if (list.stream().anyMatch(k -> k.id().equals(id))) {
                throw new IllegalStateException(name + ": key id " + id + " is listed twice");
            }
            list.add(new Key(id, secret));
        }
        return Collections.unmodifiableList(list);
    }

    /** The key new data of this use is made with; empty while only the original key is in use. */
    public Optional<Key> current(Use use) {
        List<Key> list = keys.get(use);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Key> byId(Use use, String id) {
        return keys.get(use).stream().filter(k -> k.id().equals(id)).findFirst();
    }

    public List<Key> all(Use use) {
        return keys.get(use);
    }

    /**
     * The original ROUND_SIGNING_KEY: verifies everything made before key ids, and makes everything while no list is set.
     * Null once it was removed (N8 #2).
     */
    public String legacy() {
        return legacy;
    }

    /** True once the original key was removed from the configuration (retired, and every record signed again). */
    public boolean legacyRemoved() {
        return legacy == null;
    }

    /**
     * An HMAC-SHA256 signature of {@code data} with the current key of this use. With a key id it is written
     * {@code "<id>.<first 61 hex digits>"} (244 bits, still 64 characters, so it fits every existing signature column);
     * with the original key it is the full 64 hex digits, as before key ids existed.
     */
    public String sign(Use use, String data) {
        Optional<Key> key = current(use);
        return key.isEmpty() ? Crypto.hmacSha256Hex(legacy, data) : key.get().id() + "." + Crypto.hmacSha256Hex(key.get().secret(), data).substring(0, 61);
    }

    /** When the original key was retired (LEGACY_KEY_UNTIL), if it was. */
    public Optional<java.time.Instant> legacyUntil() {
        return Optional.ofNullable(legacyUntil);
    }

    /** True if the original key may still be used for something made at {@code madeAt} (null: no time known). */
    public boolean legacyAccepted(java.time.Instant madeAt) {
        return !legacyRetired() || (madeAt != null && madeAt.isBefore(legacyUntil));
    }

    /**
     * True once LEGACY_KEY_UNTIL has passed. Before that time the original key still works for everything, so the key
     * lists and a cutoff in the future can be set in one deploy: start-up moves what it can to the new keys first.
     */
    public boolean legacyRetired() {
        return legacyUntil != null && !java.time.Instant.now().isBefore(legacyUntil);
    }

    /**
     * True if {@code signature} is this data's, made with the key it names (or, without an id, the original key, while
     * it isn't retired).
     */
    public boolean verify(Use use, String data, String signature) {
        return verify(use, data, signature, null);
    }

    /**
     * As {@link #verify(Use, String, String)}, for a record made at {@code madeAt}: once the original key is retired, an
     * old-format signature still verifies on a record made before that (history that can't be signed again).
     */
    public boolean verify(Use use, String data, String signature, java.time.Instant madeAt) {
        if (signature == null || signature.length() != 64) {
            return false;
        }
        if (signature.charAt(2) == '.') {
            Optional<Key> key = byId(use, signature.substring(0, 2));
            return key.isPresent() && Crypto.safeEquals(signature,
                    key.get().id() + "." + Crypto.hmacSha256Hex(key.get().secret(), data).substring(0, 61));
        }
        return legacy != null && legacyAccepted(madeAt) && Crypto.safeEquals(signature, Crypto.hmacSha256Hex(legacy, data));
    }

    /** True if a stored signature was made with the original key (no key id). */
    public static boolean isLegacy(String signature) {
        return signature != null && signature.length() == 64 && signature.charAt(2) != '.';
    }

    /** The key id a stored signature names ("original" for one made before key ids). */
    public static String keyIdOf(String signature) {
        return signature != null && signature.length() == 64 && signature.charAt(2) == '.' ? signature.substring(0, 2) : "original";
    }

    /** For the admin panel and the logs: which key id each use signs with now (never the secrets). */
    public Map<Use, String> currentIds() {
        Map<Use, String> ids = new LinkedHashMap<>();
        for (Use use : Use.values()) {
            ids.put(use, current(use).map(Key::id).orElse("original"));
        }
        return ids;
    }
}
