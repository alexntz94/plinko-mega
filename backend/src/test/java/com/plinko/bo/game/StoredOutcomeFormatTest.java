package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Review 3.2 and N14.12: a stored outcome not exactly in the format the game writes is refused when read, so it is never replayed or paid. */
class StoredOutcomeFormatTest {

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "null | 1     | path:15",
            "4    | null  | path:15",
            "''   | 1     | path:15",
            "4,5  | 1     | path:15",
            "4    | 1     | null",
            "4    | 1     | path:15,7",
            "4    | 1     | path:x",
            "4    | 1     | path:15;hot:3x2",
            "4    | 1     | path:15;gold:0x2",
            "4    | 1     | path:15;free:1.00@3x1",
            "4    | 1     | path:15;meter:2:0.4",
            "4    | 1     | path:15;meter:2/3:0.4",
            "4    | 1     | path:15;spin:0@1",
            "4    | 1     | path:15;mega:1.00@2",
            "4    | 1     | path:15;megaball:0",
            "4    | 1     | path:15;power:heavy",
            "4    | 1     | path:15;decoys:0@3",
            "4    | 1     | path:15;decoys:1@3/4",
            "4    | 1     | path:15;megaball:0x2;megaball:0x3",
            "4    | 1     | path:15;jackpot:1",
            "x    | 1     | path:15",
            "4    | one   | path:15"})
    void anOutcomeNotInTheGamesFormatIsRefused(String slots, String multipliers, String bonus) {
        assertThatThrownBy(() -> RoundWin.parse(slots, multipliers, bonus)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyBonusPartTheGameWritesIsRead() {
        RoundWin.Outcome outcome = RoundWin.parse("4,0", "1.1,26",
                "path:15,0;gates:2;megaball:0x2;spin:1@3x10;mega:0.40@6x25;meter:0/3:0:m-1;capped:5.00");
        assertThat(outcome.megaBalls()).containsExactly(new RoundWin.MegaBall(0, new BigDecimal("2")));
        RoundWin.Outcome bought = RoundWin.parse("4", "1.1", "path:15;power:clusterx2.05;decoys:0@3/5");
        assertThat(bought.power()).isEqualTo("cluster");
        assertThat(bought.powerPrice()).isEqualByComparingTo("2.05");
        assertThat(bought.decoys()).containsExactly(java.util.List.of(3, 5));
        assertThat(outcome.slots()).containsExactly(4, 0);
        assertThat(outcome.paths()).containsExactly(15, 0);
        assertThat(outcome.gateBoost()).isEqualByComparingTo("2");
        assertThat(outcome.spins()).containsExactly(new RoundWin.Spin(1, 3, new BigDecimal("10")));
        assertThat(outcome.megaSpins()).containsExactly(new RoundWin.MegaSpin(new BigDecimal("0.40"), 6, new BigDecimal("25")));
        assertThat(outcome.meter().charges()).isZero();
        assertThat(outcome.meter().prev()).isEqualTo("m-1");
        assertThat(outcome.capped()).isEqualByComparingTo("5.00");
        // balls; ball 1's wheel spin pays its 26x win again times 10; MEGA SPINs at their stake
        assertThat(outcome.raw(BigDecimal.ONE)).isEqualByComparingTo(new BigDecimal("1.10").add(new BigDecimal("26")).add(new BigDecimal("260"))
                .add(new BigDecimal("10.00")));
    }
}
