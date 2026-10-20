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
            new BonusSettings.Meter(true, 3, new BigDecimal("2"), new BigDecimal("30")),
            BonusSettings.MEGA.gates(), BonusSettings.MEGA.wheel(), BonusSettings.MEGA.megaWheel());

    /** A random path to this slot. */
    static int pathTo(java.util.Random random, int rows, int slot) {
        int path = 0;
        int left = slot;
        for (int step = 0; step < rows; step++) {
            if (random.nextInt(rows - step) < left) {
                path |= 1 << step;
                left--;
            }
        }
        return path;
    }
    // tables built without the gates and wheel (these test settings are far too generous for a valid table with them)
    private final OddsService odds = TestOdds.with(new BonusSettings(BONUSES.golden(), BONUSES.hot(), BONUSES.meter()), TestFixtures.properties());

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

            public int path(int rows, int slot) {
                return (1 << slot) - 1;
            }

            public int segment(BonusSettings.Wheel wheel) {
                return 0;
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
        assertThat(r.bonusText()).isEqualTo("hot:3x3;gold:1x10;path:7,0");
        assertThat(r.totalWin()).isEqualByComparingTo(RoundWin.ball(BigDecimal.ONE, prize3.multiply(new BigDecimal("3")))
                .add(RoundWin.ball(BigDecimal.ONE, prize0.multiply(BigDecimal.TEN))));
        // the same round over a low win limit: cut to it, the uncut win recorded last
        RoundDraw.Result capped = RoundDraw.draw(table, noMeter, new BigDecimal("1.00"), 2, null, new BigDecimal("0.50"),
                fixed(3, List.of(3, 0), List.of("1", "10")));
        assertThat(capped.totalWin()).isEqualByComparingTo("0.50");
        assertThat(capped.bonusText()).isEqualTo("hot:3x3;gold:1x10;path:7,0;capped:" + r.totalWin().toPlainString());
        // no bonus at all: only the path
        assertThat(RoundDraw.draw(table, BonusSettings.OFF, BigDecimal.ONE, 1, null, BigDecimal.TEN, fixed(-1, List.of(4), List.of("1"))).bonusText())
                .isEqualTo("path:15");
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

                public int path(int rows, int slot) {
                    return pathTo(random, rows, slot);
                }

                public int segment(BonusSettings.Wheel wheel) {
                    return random.nextInt(wheel.segments().size());
                }
            });
            RoundWin.Outcome read = RoundWin.parse(r.slotText(), r.multiplierText(), r.bonusText());
            assertThat(read.slots()).isEqualTo(r.slots());
            assertThat(read.paths()).isEqualTo(r.paths());
            assertThat(read.spins()).hasSize(r.spins().size());
            assertThat(read.megaSpins()).hasSize(r.megaSpins().size());
            assertThat(read.meter().charges()).isEqualTo(r.chargesAfter());
            BigDecimal raw = read.raw(new BigDecimal("0.20"));
            assertThat(read.capped() == null ? raw : new BigDecimal("40.00")).isEqualByComparingTo(r.totalWin());
            // written again from what was read: byte for byte the same text (what the signature covers)
            RoundWin.Text again = new RoundWin.Text();
            if (read.hotSlot() != null) {
                again.hot(read.hotSlot(), read.hotBoost());
            }
            new TreeMap<>(read.golden()).forEach(again::gold);
            read.paths().forEach(again::path);
            if (read.gateBoost() != null) {
                again.gates(read.gateBoost());
            }
            read.spins().forEach(x -> again.spin(x.ball(), x.segment(), x.multiplier()));
            read.megaSpins().forEach(x -> again.mega(x.stake(), x.segment(), x.multiplier()));
            again.meter(read.meter().charges(), read.meter().of(), read.meter().pot(), read.meter().prev());
            if (read.capped() != null) {
                again.capped(read.capped());
            }
            assertThat(again.text()).isEqualTo(r.bonusText());
            meter = new RoundDraw.MeterBefore(r.chargesAfter(), r.potAfter(), "round-" + round);
        }
    }

    @Test
    void gatesMultiplyThePrizeAndSpinSlotsSpinTheWheel() {
        OddsTable table = odds.get(8, 0);
        BonusSettings mega = new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(), null,
                BonusSettings.MEGA.gates(), BonusSettings.MEGA.wheel(), BonusSettings.MEGA.megaWheel());
        int spinSlot = com.plinko.bo.odds.PlinkoBoard.leftSlot(8, com.plinko.bo.odds.MegaBoard.spinPrizeIndex(8));
        // the fixed dice send a ball right first, then left: slot 2's path is 0b11, through the gates it passes
        int gates = com.plinko.bo.odds.MegaBoard.gatesPassed(8, 3);
        RoundDraw.Result r = RoundDraw.draw(table, mega, new BigDecimal("1.00"), 2, null, new BigDecimal("100000.00"),
                fixed(-1, List.of(2, spinSlot), List.of("1", "1")));
        BigDecimal prize2 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 2)).multiplier();
        assertThat(r.gates().get(0)).isEqualTo(gates);
        assertThat(r.multipliers().get(0)).isEqualByComparingTo(prize2.multiply(new BigDecimal("2").pow(gates)));
        assertThat(r.spins()).hasSize(1);
        assertThat(r.spins().get(0).ball()).isEqualTo(1);
        BigDecimal firstSegment = mega.wheel().segments().get(0).multiplier();
        assertThat(r.bonusText()).isEqualTo("path:3," + ((1 << spinSlot) - 1) + ";gates:2;spin:1@0x" + firstSegment.toPlainString());
        assertThat(r.totalWin()).isEqualByComparingTo(r.wins().get(0).add(r.wins().get(1)).add(RoundWin.ball(BigDecimal.ONE, firstSegment)));
    }
}
