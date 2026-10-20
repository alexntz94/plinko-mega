package com.plinko.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("plinko")
public record PlinkoProperties(
        Admin admin,
        boolean requireSecondApprover,
        Pam pam,
        MockPam mockPam,
        String roundSigningKey,
        Limits limits,
        Login login,
        String gameUrl,
        int sessionTtlMinutes,
        Retry retry) {

    /** bootstrap: create the first admin when there is none (off on the public game service in Google Cloud). */
    public record Admin(String username, String password, boolean bootstrap) {
    }

    /** allowHttp: permit a plain http:// PAM address (only for a PAM on the same private network). */
    public record Pam(String baseUrl, String apiKey, String secret, int timeoutMs, boolean allowHttp) {
    }

    public record MockPam(boolean enabled) {
    }

    /** Hard limits from the environment; nothing in the admin panel can go past them. */
    public record Limits(BigDecimal maxRtp, BigDecimal minRtp, BigDecimal maxMultiplier, BigDecimal maxBet,
                         int maxBalls, BigDecimal maxWinPerRound) {
    }

    public record Login(int maxAttempts, int lockMinutes) {
    }

    public record Retry(long intervalMs, int maxAttempts) {
    }
}
