package com.plinko.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.plinko.config.PlinkoProperties;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for secrets the application must store but nobody reading the database should see
 * (admins' 2FA secrets, players' PAM session tokens). The key is derived from the environment key, never
 * stored in the database. Each value is bound to its purpose, so a ciphertext copied into another column or
 * row type does not decrypt.
 */
@Component
public class SecretBox {

    /** Encrypted with the key derived from the original ROUND_SIGNING_KEY. */
    private static final String PREFIX = "v1:";
    /** Encrypted with an ENCRYPTION_KEYS key, named after the prefix: "v2:e1:..." (review 7.2). */
    private static final String KEYED = "v2:";

    private final KeyRing keys;
    private final SecretKeySpec legacy;

    public SecretBox(PlinkoProperties properties) {
        this(new KeyRing(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecretBox(KeyRing keys) {
        this.keys = keys;
        this.legacy = keys.legacy() == null ? null : aesKey(keys.legacy());
    }

    private static SecretKeySpec aesKey(String secret) {
        return new SecretKeySpec(Crypto.hmac("HmacSHA256", secret.getBytes(StandardCharsets.UTF_8),
                "plinko secret encryption".getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public String encrypt(String purpose, String plain) {
        try {
            byte[] iv = Crypto.randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            java.util.Optional<KeyRing.Key> current = keys.current(KeyRing.Use.ENCRYPTION);
            cipher.init(Cipher.ENCRYPT_MODE, current.map(k -> aesKey(k.secret())).orElse(legacy), new GCMParameterSpec(128, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return current.map(k -> KEYED + k.id() + ":").orElse(PREFIX) + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** The plain value, or null if the value is missing, not encrypted by us, or was modified. */
    public String decrypt(String purpose, String stored) {
        if (!isEncrypted(stored)) {
            return null;
        }
        try {
            SecretKeySpec key;
            String body;
            if (stored.startsWith(PREFIX)) {
                if (keys.legacyRetired() || legacy == null) {
                    return null;     // the original key is retired (or removed): nothing is decrypted with it any more
                }
                key = legacy;
                body = stored.substring(PREFIX.length());
            } else {
                java.util.Optional<KeyRing.Key> named = keys.byId(KeyRing.Use.ENCRYPTION, stored.substring(KEYED.length(), KEYED.length() + 2));
                if (named.isEmpty() || stored.charAt(KEYED.length() + 2) != ':') {
                    return null;     // made with a key that is no longer configured
                }
                key = aesKey(named.get().secret());
                body = stored.substring(KEYED.length() + 3);
            }
            byte[] data = Base64.getDecoder().decode(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the value was encrypted with the original key ("v1:"). */
    public static boolean isLegacy(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && (stored.startsWith(PREFIX) || (stored.startsWith(KEYED) && stored.length() > KEYED.length() + 3));
    }
}
