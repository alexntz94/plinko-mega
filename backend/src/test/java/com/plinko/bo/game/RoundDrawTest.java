package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.plinko.TestFixtures;
import com.plinko.bo.odds.MegaBoard;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.odds.TestOdds;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;

/** Review 7.8: the round's draw on its own, with fixed dice; the stored text written and read by one class. */
class RoundDrawTest {

    /** Every feature on, and a meter that fills after 3 charges (so MEGA SPINs come often). */
    private static final BonusSettings ALL = new BonusSettings(new BonusSettings.Meter(true, 3, new BigDecimal("2"), new BigDecimal("30")),
            BonusSettings.MEGA.gates(), BonusSettings.MEGA.wheel(), BonusSettings.MEGA.megaWheel(), BonusSettings.MEGA.megaBall());
    /** Only the gates (no meter, no wheel). */
    private static final BonusSettings GATES = new BonusSettings(BonusSettings.OFF.meter(), BonusSettings.MEGA.gates(), BonusSettings.OFF.wheel(),
            BonusSettings.OFF.megaWheel());
    private final OddsService odds = TestOdds.with(BonusSettings.MEGA, TestFixtures.properties());

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

    /** Dice that land the balls in these slots, each on the path that goes right first, and stop wheels on segment 0. */
    private static RoundDraw.Dice fixed(List<Integer> slots) {
        Deque<Integer> s = new ArrayDeque<>(slots);
        return new RoundDraw.Dice() {
            public int slot(OddsTable table) {
                return s.removeFirst();
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
        BigDecimal boost = GATES.gates().boost();
        RoundDraw.Result r = RoundDraw.draw(table, GATES, new BigDecimal("1.00"), 2, null, new BigDecimal("1000.00"), fixed(List.of(3, 0)));
        BigDecimal prize3 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 3)).multiplier().multiply(boost.pow(MegaBoard.gatesPassed(8, 7)));
        BigDecimal prize0 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 0)).multiplier().multiply(boost.pow(MegaBoard.gatesPassed(8, 0)));
        assertThat(r.slotText()).isEqualTo("3,0");
        assertThat(r.multipliers()).containsExactly(prize3.stripTrailingZeros(), prize0.stripTrailingZeros());
        assertThat(r.bonusText()).isEqualTo("path:7,0;gates:" + boost.toPlainString());
        assertThat(r.totalWin()).isEqualByComparingTo(RoundWin.ball(BigDecimal.ONE, prize3).add(RoundWin.ball(BigDecimal.ONE, prize0)));
        // the same round over a low win limit: cut to it, the uncut win recorded last
        RoundDraw.Result capped = RoundDraw.draw(table, GATES, new BigDecimal("1.00"), 2, null, new BigDecimal("0.10"), fixed(List.of(3, 0)));
        assertThat(capped.totalWin()).isEqualByComparingTo("0.10");
        assertThat(capped.bonusText()).isEqualTo("path:7,0;gates:" + boost.toPlainString() + ";capped:" + r.totalWin().toPlainString());
        // no feature on: only the paths
        assertThat(RoundDraw.draw(table, BonusSettings.OFF, BigDecimal.ONE, 1, null, BigDecimal.TEN, fixed(List.of(4))).bonusText())
                .isEqualTo("path:15");
    }

    @Test
    void aMegaBallMultipliesItsBallsWinAndIsStored() {
        OddsTable table = odds.get(8, 0);
        BonusSettings megaOnly = new BonusSettings(BonusSettings.OFF.meter(), BonusSettings.OFF.gates(), BonusSettings.OFF.wheel(),
                BonusSettings.OFF.megaWheel(), BonusSettings.MEGA.megaBall());
        Deque<Integer> slots = new ArrayDeque<>(List.of(0, 4));
        Deque<Integer> megas = new ArrayDeque<>(List.of(-1, 3));    // the second ball is a MEGA BALL, on its 4th multiplier
        RoundDraw.Result r = RoundDraw.draw(table, megaOnly, new BigDecimal("1.00"), 2, null, new BigDecimal("1000.00"), new RoundDraw.Dice() {
            public int slot(OddsTable t) {
                return slots.removeFirst();
            }

            public int path(int rows, int slot) {
                return (1 << slot) - 1;
            }

            public int segment(BonusSettings.Wheel wheel) {
                return 0;
            }

            public int megaBall(BonusSettings.MegaBall megaBall) {
                return megas.removeFirst();
            }
        });
        BigDecimal times = BonusSettings.MEGA.megaBall().multipliers().get(3).multiplier();
        BigDecimal centre = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 4)).multiplier();
        assertThat(r.megaBalls()).containsExactly(new RoundDraw.MegaBall(1, times.stripTrailingZeros()));
        assertThat(r.multipliers().get(1)).isEqualByComparingTo(centre.multiply(times));
        assertThat(r.bonusText()).isEqualTo("path:0,15;megaball:1x" + times.stripTrailingZeros().toPlainString());
        RoundWin.Outcome read = RoundWin.parse(r.slotText(), r.multiplierText(), r.bonusText());
        assertThat(read.megaBallTimes(1)).isEqualByComparingTo(times);
        assertThat(read.megaBallTimes(0)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(read.raw(BigDecimal.ONE)).isEqualByComparingTo(r.totalWin());
    }

    @Test
    void everyStoredTextReadsBackToTheSameRoundAndIsWrittenTheSameWay() {
        java.util.Random random = new java.util.Random(7);
        RoundDraw.MeterBefore meter = new RoundDraw.MeterBefore(0, BigDecimal.ZERO, "start-7");
        int spins = 0, megas = 0;
        for (int round = 0; round < 3000; round++) {
            int rows = PlinkoBoard.MIN_ROWS + random.nextInt(PlinkoBoard.MAX_ROWS - PlinkoBoard.MIN_ROWS + 1);
            OddsTable table = odds.get(rows, random.nextInt(3));
            int balls = 1 + random.nextInt(10);
            RoundDraw.Result r = RoundDraw.draw(table, ALL, new BigDecimal("0.20"), balls, meter, new BigDecimal("40.00"), new RoundDraw.Dice() {
                public int slot(OddsTable t) {
                    return random.nextInt(t.rows() + 1);
                }

                public int path(int rows, int slot) {
                    return pathTo(random, rows, slot);
                }

                public int segment(BonusSettings.Wheel wheel) {
                    return random.nextInt(wheel.segments().size());
                }

                public int megaBall(BonusSettings.MegaBall megaBall) {
                    return random.nextInt(4) == 0 ? random.nextInt(megaBall.multipliers().size()) : -1;
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
            read.paths().forEach(again::path);
            if (read.gateBoost() != null) {
                again.gates(read.gateBoost());
            }
            read.megaBalls().forEach(x -> again.megaBall(x.ball(), x.multiplier()));
            assertThat(read.megaBalls()).hasSize(r.megaBalls().size());
            read.spins().forEach(x -> again.spin(x.ball(), x.segment(), x.multiplier()));
            read.megaSpins().forEach(x -> again.mega(x.stake(), x.segment(), x.multiplier()));
            again.meter(read.meter().charges(), read.meter().of(), read.meter().pot(), read.meter().prev());
            if (read.capped() != null) {
                again.capped(read.capped());
            }
            assertThat(again.text()).isEqualTo(r.bonusText());
            spins += r.spins().size();
            megas += r.megaSpins().size();
            meter = new RoundDraw.MeterBefore(r.chargesAfter(), r.potAfter(), "round-" + round);
        }
        assertThat(spins).as("wheel spins written and read").isPositive();
        assertThat(megas).as("MEGA SPINs written and read").isPositive();
    }

    @Test
    void gatesMultiplyThePrizeAndSpinSlotsSpinTheWheel() {
        OddsTable table = odds.get(8, 0);
        BonusSettings mega = new BonusSettings(null, BonusSettings.MEGA.gates(), BonusSettings.MEGA.wheel(), BonusSettings.MEGA.megaWheel());
        BigDecimal boost = mega.gates().boost();
        int spinSlot = PlinkoBoard.leftSlot(8, MegaBoard.spinPrizeIndex(8));
        // the fixed dice send a ball right first, then left: slot 2's path is 0b11, through the gates it crosses
        int gates = MegaBoard.gatesPassed(8, 3);
        RoundDraw.Result r = RoundDraw.draw(table, mega, new BigDecimal("1.00"), 2, null, new BigDecimal("100000.00"), fixed(List.of(2, spinSlot)));
        BigDecimal prize2 = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(8, 2)).multiplier();
        assertThat(r.gates().get(0)).isEqualTo(gates);
        assertThat(r.multipliers().get(0)).isEqualByComparingTo(prize2.multiply(boost.pow(gates)));
        assertThat(r.spins()).hasSize(1);
        assertThat(r.spins().get(0).ball()).isEqualTo(1);
        BigDecimal firstSegment = mega.wheel().segments().get(0).multiplier();
        assertThat(r.bonusText()).isEqualTo("path:3," + ((1 << spinSlot) - 1) + ";gates:" + boost.toPlainString() + ";spin:1@0x"
                + firstSegment.toPlainString());
        // the spin pays the SPIN slot ball's own win again times the segment
        assertThat(r.spins().get(0).win()).isEqualByComparingTo(RoundWin.ball(BigDecimal.ONE, r.multipliers().get(1).multiply(firstSegment)));
        assertThat(r.totalWin()).isEqualByComparingTo(r.wins().get(0).add(r.wins().get(1)).add(r.spins().get(0).win()));
    }
}
