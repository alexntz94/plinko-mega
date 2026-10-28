package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Each theme sells its own power-ups, and Reactor Drop's and Lagoon Drop's rules pay what they say. */
class ThemePowersTest {

    @Test
    void eachThemeSellsOnlyItsOwnPowerUps() {
        assertThat(BonusService.themeSells("bombs-away", "tank")).isTrue();
        assertThat(BonusService.themeSells("bombs-away", "chain")).isFalse();
        assertThat(BonusService.themeSells("reactor-drop", "meltdown")).isTrue();
        assertThat(BonusService.themeSells("reactor-drop", "heavy")).as("Overcharge").isTrue();
        assertThat(BonusService.themeSells("reactor-drop", "cluster")).isFalse();
        assertThat(BonusService.themeSells("lagoon-drop", "pair")).isTrue();
        assertThat(BonusService.themeSells("lagoon-drop", "heavy")).as("Sunburst Surge").isTrue();
        assertThat(BonusService.themeSells("lagoon-drop", "magnet-left")).as("Crosscurrent").isTrue();
        assertThat(BonusService.themeSells("lagoon-drop", "meltdown")).as("Volcano Vault").isTrue();
        assertThat(BonusService.themeSells("lagoon-drop", "chain")).isFalse();
        assertThat(BonusService.themeSells("reactor-drop", "pair")).isFalse();
        assertThat(BonusService.themeSells("pharaoh-drop", "pair")).as("Twin Serpents").isTrue();
        assertThat(BonusService.themeSells("pharaoh-drop", "meltdown")).as("Tomb Raid").isTrue();
        assertThat(BonusService.themeSells("pharaoh-drop", "tank")).isFalse();
        assertThat(BonusService.themeSells("zombie-war", "meltdown")).as("Locker Raid").isTrue();
        assertThat(BonusService.themeSells("zombie-war", "tank")).isFalse();
        assertThat(BonusService.themeSells("neon-nights", "meltdown")).as("Lucky Draw").isTrue();
        assertThat(BonusService.themeSells("neon-nights", "chain")).isFalse();
        assertThat(BonusService.themeSells("candy-factory", "meltdown")).as("Sugar Rush").isTrue();
        assertThat(BonusService.themeSells("candy-factory", "pair")).as("Double Dip").isTrue();
        assertThat(BonusService.themeSells("candy-factory", "tank")).isFalse();
        assertThat(BonusService.themeSells("shurda-plinko", "meltdown")).as("Laundry Day").isTrue();
        assertThat(BonusService.themeSells("shurda-plinko", "pair")).as("Pair of Socks").isTrue();
        assertThat(BonusService.themeSells("hell-of-a-deal", "meltdown")).as("Closing Sale").isTrue();
        assertThat(BonusService.themeSells("hell-of-a-deal", "pair")).as("Double Dealing").isTrue();
        assertThat(BonusService.themeSells("hell-of-a-deal", "tank")).isFalse();
        assertThat(BonusService.themeSells("disco-is-dead", "meltdown")).as("Last Dance").isTrue();
        assertThat(BonusService.themeSells("disco-is-dead", "pair")).as("Duet").isTrue();
        assertThat(BonusService.themeSells("disco-is-dead", "tank")).isFalse();
        assertThat(BonusService.themeSells("plinko-exe", "meltdown")).as("System Crash").isTrue();
        assertThat(BonusService.themeSells("plinko-exe", "pair")).as("Fork Bomb").isTrue();
        assertThat(BonusService.themeSells("plinko-exe", "tank")).isFalse();
        assertThat(BonusService.themeSells("death-and-taxes", "meltdown")).as("Death Duty").isTrue();
        assertThat(BonusService.themeSells("death-and-taxes", "magnet-left")).as("Loophole").isTrue();
        assertThat(BonusService.themeSells("death-and-taxes", "tank")).isFalse();
        assertThat(BonusService.themeSells("old-theme", "heavy")).isFalse();
        for (List<String> powers : BonusService.THEME_POWERS.values()) {
            assertThat(BonusService.POWERS).containsAll(powers);
        }
    }

    @Test
    void aPearlPairLandsTwiceAndAClusterThreeTimes() {
        assertThat(BonusService.landings("pair")).isEqualTo(2);
        assertThat(BonusService.landings("cluster")).isEqualTo(3);
        assertThat(BonusService.landings("heavy")).isEqualTo(1);
        assertThat(BonusService.landings(null)).isEqualTo(1);
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
