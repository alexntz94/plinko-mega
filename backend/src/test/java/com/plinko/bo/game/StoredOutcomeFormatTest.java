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
            "null | 1     | null",
            "4    | null  | null",
            "''   | 1     | null",
            "4,5  | 1     | null",
            "4    | 1     | hot",
            "4    | 1     | hot:3",
            "4    | 1     | gold:0x2,0x3",
            "4    | 1     | gold:0",
            "4    | 1     | free:1.00",
            "4    | 1     | free:1.00@3x1g2g3",
            "4    | 1     | meter:2:0.4",
            "4    | 1     | meter:2/3",
            "4    | 1     | jackpot:1",
            "x    | 1     | null",
            "4    | one   | null"})
    void anOutcomeNotInTheGamesFormatIsRefused(String slots, String multipliers, String bonus) {
        assertThatThrownBy(() -> RoundWin.parse(slots, multipliers, bonus)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyBonusPartTheGameWritesIsRead() {
        RoundWin.Outcome outcome = RoundWin.parse("4,0", "1.1,26", "hot:4x2;gold:1x3;free:0.40@8x26g2;meter:0/3:0:m-1;capped:5.00");
        assertThat(outcome.slots()).containsExactly(4, 0);
        assertThat(outcome.hotSlot()).isEqualTo(4);
        assertThat(outcome.hotBoost()).isEqualByComparingTo("2");
        assertThat(outcome.golden()).containsEntry(1, new BigDecimal("3"));
        assertThat(outcome.free()).singleElement().satisfies(f -> {
            assertThat(f.stake()).isEqualByComparingTo("0.40");
            assertThat(f.slot()).isEqualTo(8);
            assertThat(f.multiplier()).isEqualByComparingTo("26");
            assertThat(f.boost()).isEqualByComparingTo("2");
        });
        assertThat(outcome.meter().charges()).isZero();
        assertThat(outcome.capped()).isEqualByComparingTo("5.00");
        // a free ball without a golden boost counts once
        assertThat(RoundWin.parse("4", "1", "free:0.40@8x26").free().get(0).boost()).isEqualByComparingTo("1");
    }
}
