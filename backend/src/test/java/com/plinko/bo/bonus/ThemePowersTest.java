package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Each theme sells its own power-ups, and Reactor Drop's rules pay what they say. */
class ThemePowersTest {

    @Test
    void eachThemeSellsOnlyItsOwnPowerUps() {
        assertThat(BonusService.themeSells("bombs-away", "tank")).isTrue();
        assertThat(BonusService.themeSells("bombs-away", "chain")).isFalse();
        assertThat(BonusService.themeSells("reactor-drop", "meltdown")).isTrue();
        assertThat(BonusService.themeSells("reactor-drop", "heavy")).as("Overcharge").isTrue();
        assertThat(BonusService.themeSells("reactor-drop", "cluster")).isFalse();
        assertThat(BonusService.themeSells("old-theme", "heavy")).isFalse();
        for (List<String> powers : BonusService.THEME_POWERS.values()) {
            assertThat(BonusService.POWERS).containsAll(powers);
        }
    }

    @Test
    void chainLightningPaysTheSlotAndHalfOfEachNeighbour() {
        // 8 rows: prize positions from the centre out
        List<BigDecimal> prizes = List.of(new BigDecimal("0.3"), new BigDecimal("0.7"), new BigDecimal("0.7"), new BigDecimal("1.4"),
                new BigDecimal("5.6"));
        // the centre slot (4): 0.3 + 0.35 + 0.35
        assertThat(BonusService.landingPrize("chain", 8, prizes, 4)).isEqualByComparingTo("1.0");
        // an edge slot has one neighbour: 5.6 + 0.7
        assertThat(BonusService.landingPrize("chain", 8, prizes, 0)).isEqualByComparingTo("6.3");
        assertThat(BonusService.landingPrize("chain", 8, prizes, 8)).isEqualByComparingTo("6.3");
        assertThat(BonusService.landingPrize(null, 8, prizes, 4)).isEqualByComparingTo("0.3");
    }

    @Test
    void aMagneticFieldPullsToItsHalfAndBoostsIt() {
        assertThat(BonusService.leftChance("magnet-left")).isEqualTo(0.7);
        assertThat(BonusService.leftChance("magnet-right")).isEqualTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(BonusService.leftChance("heavy")).isEqualTo(0.5);
        // 8 rows: slots 0-3 left, 4 the centre (no boost), 5-8 right
        assertThat(BonusService.slotFactor("magnet-left", 8, 0, 3)).isEqualByComparingTo("1.5");
        assertThat(BonusService.slotFactor("magnet-left", 8, 0, 4)).isEqualByComparingTo("1");
        assertThat(BonusService.slotFactor("magnet-right", 8, 0, 5)).isEqualByComparingTo("1.5");
        assertThat(BonusService.slotFactor("magnet-right", 9, 0, 4)).isEqualByComparingTo("1");
        assertThat(BonusService.slotFactor("magnet-left", 9, 0, 4)).isEqualByComparingTo("1.5");
    }
}
