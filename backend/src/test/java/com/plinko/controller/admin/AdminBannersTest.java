package com.plinko.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.plinko.TestFixtures;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.game.RoundIntegrityScanner;
import com.plinko.bo.game.RoundRecoveryService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.settings.SettingsService;
import org.junit.jupiter.api.Test;

/** Review N14.2: rounds the wallet keeps refusing get their own banner, not the integrity alarm about data changed outside the admin panel. */
class AdminBannersTest {

    private final OddsService odds = mock(OddsService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final RoundIntegrityScanner scanner = mock(RoundIntegrityScanner.class);
    private final RoundRecoveryService recovery = mock(RoundRecoveryService.class);
    private final AdminUserService users = mock(AdminUserService.class);
    private final AdminModelAdvice advice = new AdminModelAdvice(TestFixtures.properties(), mock(ChangeRequestService.class), users, odds, settings,
            scanner, recovery);

    @Test
    void roundsTheWalletKeepsRefusingAreAWalletAlarmNotAnIntegrityAlarm() {
        when(odds.problems()).thenReturn(List.of());
        when(scanner.invalidRounds()).thenReturn(List.of());
        when(settings.trusted()).thenReturn(true);
        when(users.all()).thenReturn(List.of());
        when(recovery.stoppedRounds()).thenReturn(List.of("r1", "r2", "r3", "r4"));

        assertThat(advice.integrityAlarms()).isEmpty();
        assertThat(advice.walletAlarms()).singleElement().asString()
                .startsWith("4 round(s) are still owed").contains("wallet refused every retry").contains("[r1, r2, r3]").doesNotContain("r4");

        when(recovery.stoppedRounds()).thenReturn(List.of());
        assertThat(advice.walletAlarms()).isEmpty();
    }

    @Test
    void roundsWhoseOutcomeFailsItsCheckStayAnIntegrityAlarm() {
        when(odds.problems()).thenReturn(List.of());
        when(scanner.invalidRounds()).thenReturn(List.of("x1"));
        when(settings.trusted()).thenReturn(true);
        when(users.all()).thenReturn(List.of());
        when(recovery.stoppedRounds()).thenReturn(List.of());
        assertThat(advice.integrityAlarms()).singleElement().asString().contains("fails its signature or win check");
        assertThat(advice.walletAlarms()).isEmpty();
    }
}
