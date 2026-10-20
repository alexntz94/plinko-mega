package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Review 7.4: the market's responsible gambling options; operators may only make them stricter. */
class GameFeaturesTest {

    private static SettingsService.GameFeatures f(boolean hold, boolean clock, int check, boolean quiet, String min) {
        return new SettingsService.GameFeatures(hold, clock, check, quiet, new BigDecimal(min));
    }

    @Test
    void stricterIsAlwaysAllowedLooserOnlyForAdmins() {
        SettingsService.GameFeatures now = f(true, false, 60, false, "1");
        assertThat(now.loosenedBy(f(false, true, 30, true, "2.5"))).isFalse();   // every change protects players more
        assertThat(now.loosenedBy(f(true, false, 0, false, "1"))).isTrue();      // reality check switched off
        assertThat(now.loosenedBy(f(true, false, 90, false, "1"))).isTrue();     // less often
        assertThat(now.loosenedBy(f(true, false, 60, false, "0.5"))).isTrue();   // faster rounds
        assertThat(f(false, true, 0, true, "0").loosenedBy(f(true, true, 0, true, "0"))).isTrue();    // Hold & Drop on again
        assertThat(f(false, true, 0, true, "0").loosenedBy(f(false, false, 0, true, "0"))).isTrue();  // clock off
        assertThat(f(false, true, 0, true, "0").loosenedBy(f(false, true, 0, false, "0"))).isTrue();  // small wins celebrated
        assertThat(SettingsService.GameFeatures.DEFAULT.loosenedBy(f(true, false, 45, false, "0"))).isFalse();
    }

    @Test
    void requiringProofOfTheRealityCheckIsStricterAndDroppingItLooser() {
        SettingsService.GameFeatures without = f(true, false, 30, false, "0");
        SettingsService.GameFeatures with = new SettingsService.GameFeatures(true, false, 30, false, BigDecimal.ZERO, true);
        assertThat(without.requireRealityCheckProof()).as("off unless set").isFalse();
        assertThat(SettingsService.GameFeatures.DEFAULT.requireRealityCheckProof()).isFalse();
        assertThat(without.loosenedBy(with)).isFalse();
        assertThat(with.loosenedBy(without)).as("needs an admin and a second admin's approval").isTrue();
    }

    @Test
    void valuesOutsideTheirRangeAreRefused() {
        assertThat(f(true, false, 5, false, "0").problems()).hasSize(1);
        assertThat(f(true, false, 300, false, "0").problems()).hasSize(1);
        assertThat(f(true, false, 0, false, "11").problems()).hasSize(1);
        assertThat(f(true, false, 0, false, "1.25").problems()).hasSize(1);
        assertThat(f(true, false, 60, false, "2.5").problems()).isEmpty();
    }
}
