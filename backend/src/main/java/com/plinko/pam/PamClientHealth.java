package com.plinko.pam;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * DOWN when wallet calls cannot be sent even after replacing the HTTP client, so the container is restarted. Only
 * the local client is checked, never the PAM itself: a casino's wallet outage must not restart the game.
 */
@Component("pamClient")
public class PamClientHealth implements HealthIndicator {

    private final HttpPamClient pam;

    public PamClientHealth(HttpPamClient pam) {
        this.pam = pam;
    }

    @Override
    public Health health() {
        return pam.usable() ? Health.up().build() : Health.down().build();
    }
}
