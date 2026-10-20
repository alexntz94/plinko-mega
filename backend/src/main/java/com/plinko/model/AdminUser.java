package com.plinko.model;

import java.time.Instant;

/** An admin panel account; {@code integrity} signs its security-relevant fields. */
public record AdminUser(long id, String username, String passwordHash, String role, boolean enabled, String totpSecret,
                        long totpLastStep, int failedAttempts, Instant lockedUntil, Instant lastLoginAt, Instant createdAt,
                        boolean mustChangePassword, Instant passwordExpiresAt, String sponsor, String securityStamp,
                        String integrity) {
    public boolean passwordExpired() {
        return passwordExpiresAt != null && passwordExpiresAt.isBefore(Instant.now());
    }

    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean enrolled() {
        return totpSecret != null;
    }
}
