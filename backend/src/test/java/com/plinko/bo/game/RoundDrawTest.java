package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;

import com.plinko.TestFixtures;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.odds.TestOdds;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;

/** Review 7.8: the round's draw on its own, with fixed dice; the stored text written and read by one class. */
class RoundDrawTest {

    private static final BonusSettings BONUSES = new BonusSettings(
            new BonusSettings.Golden(true, new BigDecimal("25"), BonusSettings.OFF.golden().boosts()),
            new BonusSettings.Hot(true, new BigDecimal("40"), new BigDecimal("3")),
            new BonusSettings.Meter(true, 3, new BigDecimal("2"), new BigDecimal("30"), 4));
    private final OddsService odds = TestOdds.with(BONUSES, TestFixtures.properties());

    /** Dice that give exactly these values, in order. */
    private static RoundDraw.Dice fixed(int hot, List<Integer> slots, List<String> golden) {
        Deque<Integer> s = new ArrayDeque<>(slots);
        Deque<String> g = new ArrayDeque<>(golden);
        return new RoundDraw.Dice() {
            public int hotSlot(BonusSettings bonus, int rows) {
                return hot;
            }

            public int slot(OddsTable table) {
                return s.removeFirst();
            }

            public BigDecimal golden(BonusSettings bonus) {
                return new BigDecimal(g.removeFirst());
            }
        };
    }

    @Test
    void fixedDiceGiveAnExactRound() {
        OddsTable table = odds.get(8, 0);
        BonusSettings noMeter = new BonusSettings(BONUSES.golden(), BONUSES.hot());
        RoundDraw.Result r = RoundDraw.draw(table, noMeter, new BigDecimal("1.00"), 2, null, new BigDecimal("1000.00"),
                fixed(3, List.of(3, 0), List.of("1", "10")));
        BigDecimal prize3 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 3)).multiplier();
        BigDecimal prize0 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 0)).multiplier();
        assertThat(r.slotText()).isEqualTo("3,0");
        assertThat(r.multipliers()).containsExactly(prize3.multiply(new BigDecimal("3")).stripTrailingZeros(),
                prize0.multiply(BigDecimal.TEN).stripTrailingZeros());
        assertThat(r.bonusText()).isEqualTo("hot:3x3;gold:1x10");
        assertThat(r.totalWin()).isEqualByComparingTo(RoundWin.ball(BigDecimal.ONE, prize3.multiply(new BigDecimal("3")))
                .add(RoundWin.ball(BigDecimal.ONE, prize0.multiply(BigDecimal.TEN))));
        // the same round over a low win limit: cut to it, the uncut win recorded last
        RoundDraw.Result capped = RoundDraw.draw(table, noMeter, new BigDecimal("1.00"), 2, null, new BigDecimal("0.50"),
                fixed(3, List.of(3, 0), List.of("1", "10")));
        assertThat(capped.totalWin()).isEqualByComparingTo("0.50");
        assertThat(capped.bonusText()).isEqualTo("hot:3x3;gold:1x10;capped:" + r.totalWin().toPlainString());
        // no bonus at all: no bonus text
        assertThat(RoundDraw.draw(table, BonusSettings.OFF, BigDecimal.ONE, 1, null, BigDecimal.TEN, fixed(-1, List.of(4), List.of("1"))).bonusText())
                .isNull();
    }

    @Test
    void everyStoredTextReadsBackToTheSameRoundAndIsWrittenTheSameWay() {
        java.util.Random random = new java.util.Random(7);
        RoundDraw.MeterBefore meter = new RoundDraw.MeterBefore(0, BigDecimal.ZERO, "start-7");
        for (int round = 0; round < 3000; round++) {
            int rows = PlinkoBoard.MIN_ROWS + random.nextInt(PlinkoBoard.MAX_ROWS - PlinkoBoard.MIN_ROWS + 1);
            OddsTable table = odds.get(rows, random.nextInt(3));
            int balls = 1 + random.nextInt(10);
            RoundDraw.Result r = RoundDraw.draw(table, BONUSES, new BigDecimal("0.20"), balls, meter, new BigDecimal("40.00"), new RoundDraw.Dice() {
                public int hotSlot(BonusSettings bonus, int rows) {
                    return random.nextInt(4) == 0 ? random.nextInt(rows + 1) : -1;
                }

                public int slot(OddsTable t) {
                    return random.nextInt(t.rows() + 1);
                }

                public BigDecimal golden(BonusSettings bonus) {
                    return random.nextInt(5) == 0 ? new BigDecimal(List.of("2", "3", "5", "10").get(random.nextInt(4))) : BigDecimal.ONE;
                }
            });
            RoundWin.Outcome read = RoundWin.parse(r.slotText(), r.multiplierText(), r.bonusText());
            assertThat(read.slots()).isEqualTo(r.slots());
            assertThat(read.free()).hasSize(r.freeSlots().size());
            assertThat(read.meter().charges()).isEqualTo(r.chargesAfter());
            BigDecimal raw = read.raw(new BigDecimal("0.20"));
            assertThat(read.capped() == null ? raw : new BigDecimal("40.00")).isEqualByComparingTo(r.totalWin());
            // written again from what was read: byte for byte the same text (what the signature covers)
            RoundWin.Text again = new RoundWin.Text();
            if (read.hotSlot() != null) {
                again.hot(read.hotSlot(), read.hotBoost());
            }
            new TreeMap<>(read.golden()).forEach(again::gold);
            read.free().forEach(f -> again.free(f.stake(), f.slot(), f.multiplier(), f.boost()));
            again.meter(read.meter().charges(), read.meter().of(), read.meter().pot(), read.meter().prev());
            if (read.capped() != null) {
                again.capped(read.capped());
            }
            assertThat(again.text()).isEqualTo(r.bonusText());
            meter = new RoundDraw.MeterBefore(r.chargesAfter(), r.potAfter(), "round-" + round);
        }
    }

    @Test
    void oldMeterTextWithoutAPreviousStateStillReads() {
        RoundWin.Outcome old = RoundWin.parse("1", "0.5", "meter:2/3:0.400000");
        assertThat(old.meter()).isEqualTo(new RoundWin.Meter(2, 3, new BigDecimal("0.400000"), null));
    }
}
