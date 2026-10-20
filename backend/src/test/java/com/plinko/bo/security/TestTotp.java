package com.plinko.bo.security;

/** For tests outside this package that sign in for real: the authenticator code of a secret right now. */
public final class TestTotp {

    private TestTotp() {
    }

    public static String now(String secret) {
        return Totp.code(Totp.unbase32(secret), java.time.Instant.now().getEpochSecond() / 30);
    }
}
