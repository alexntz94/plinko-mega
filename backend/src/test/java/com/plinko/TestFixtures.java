package com.plinko;

import java.math.BigDecimal;

import com.plinko.config.PlinkoProperties;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static PlinkoProperties properties() {
        return new PlinkoProperties(
                new PlinkoProperties.Admin("admin", "", true),
                true,
                new PlinkoProperties.Pam("https://pam.test", "key", "s".repeat(40), 1000, false),
                new PlinkoProperties.MockPam(false),
                "k".repeat(40),
                new PlinkoProperties.Limits(new BigDecimal("99.7"), new BigDecimal("85"), new BigDecimal("1000"),
                        new BigDecimal("1000"), 100, new BigDecimal("100000")),
                new PlinkoProperties.Login(5, 15),
                "http://localhost/",
                60,
                new PlinkoProperties.Retry(60000, 10));
    }

    /** The same, with ROUND_SIGNING_KEY removed (review N8 #2: possible once retired and every record signed again). */
    public static PlinkoProperties propertiesWithoutOriginalKey() {
        PlinkoProperties p = properties();
        return new PlinkoProperties(p.admin(), p.requireSecondApprover(), p.pam(), p.mockPam(), "", p.limits(), p.login(), p.gameUrl(),
                p.sessionTtlMinutes(), p.retry());
    }

    /**
     * A mocked SettingsService's snapshot (N14.10), made from what the test stubbed on it (limits, currency, switch,
     * features) and the stored bonus text, each time it is asked for.
     */
    public static void snapshotFromStubs(com.plinko.bo.settings.SettingsService settings, java.util.function.Supplier<String> bonusStored) {
        org.mockito.Mockito.when(settings.snapshot()).thenAnswer(i -> {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            String bonus = bonusStored.get();
            if (bonus != null) {
                values.put("game.bonus", bonus);
            }
            return new com.plinko.bo.settings.SettingsService.Snapshot(0, values, true, settings.limits(), settings.currency(), settings.gameEnabled(),
                    settings.features());
        });
    }
}
