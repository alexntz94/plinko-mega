package com.plinko.bo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1, T = 59 s: 94287082 (last 6 digits 287082). */
    @Test
    void matchesRfcTestVector() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(key, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo("081804");
    }

    @Test
    void base32RoundTrip() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.base32(Totp.unbase32(secret))).isEqualTo(secret);
    }

    @Test
    void codeIsSingleUse() {
        String secret = Totp.newSecret();
        long step = System.currentTimeMillis() / 1000 / 30;
        String code = Totp.code(Totp.unbase32(secret), step);
        long used = Totp.verify(secret, code, 0);
        assertThat(used).isEqualTo(step);
        assertThat(Totp.verify(secret, code, used)).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedCodes() {
        String secret = Totp.newSecret();
        assertThat(Totp.verify(secret, null, 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "12345", 0)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "abcdef", 0)).isEqualTo(-1);
    }

    @Test
    void temporaryPasswordsAreStrongAndUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String password = AdminUserService.temporaryPassword();
            assertThat(password).hasSize(20);
            assertThat(AdminUserService.passwordProblems("someone", password)).isEmpty();
            assertThat(seen.add(password)).isTrue();
        }
    }

    @Test
    void passwordPolicy() {
        assertThat(AdminUserService.passwordProblems("admin", "short")).isNotEmpty();
        assertThat(AdminUserService.passwordProblems("admin", "alllowercaseletters")).isNotEmpty();
        assertThat(AdminUserService.passwordProblems("admin", "Correct-Horse-9")).isEmpty();
    }
}
