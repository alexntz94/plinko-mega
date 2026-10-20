package com.plinko.bo.security;

import java.nio.ByteBuffer;
import java.time.Instant;

import com.plinko.common.Crypto;

/** RFC 6238 time-based one-time passwords (SHA-1, 6 digits, 30 s), as used by authenticator apps. */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;

    private Totp() {
    }

    public static String newSecret() {
        return base32(Crypto.randomBytes(20));
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + issuer + ":" + account + "?secret=" + secret + "&issuer=" + issuer + "&digits=6&period=30";
    }

    /**
     * Returns the time step the code matched (current step +/- 1 for clock drift), or -1.
     * Callers store the step and reject codes for steps at or before it, so a code works only once.
     */
    public static long verify(String secret, String code, long lastUsedStep) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long current = Instant.now().getEpochSecond() / STEP_SECONDS;
        byte[] key = unbase32(secret);
        for (long step = current - 1; step <= current + 1; step++) {
            if (step > lastUsedStep && Crypto.safeEquals(code(key, step), code)) {
                return step;
            }
        }
        return -1;
    }

    static String code(byte[] key, long step) {
        byte[] hash = Crypto.hmac("HmacSHA1", key, ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace(" ", "").replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
