package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.plinko.TestFixtures;
import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.MegaBoard;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.odds.TestOdds;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plinko Mega's gates, wheel and MEGA SPIN: the board geometry, the path draw, and whole rounds played by the real round
 * calculation with the server's own draws, which must return the approved total RTP on every board.
 */
class MegaRtpTest {

    private static final BonusSettings MEGA = BonusSettings.MEGA;
    private static final BigDecimal TARGET = new BigDecimal("98");
    private static final BigDecimal BET = new BigDecimal("1.00");
    private static final int BALLS = 20;
    private static final double SIGMAS = 5.0;

    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build());

    @Test
    void gatesStandInsideTheBoardAndSpinSlotsAreAPairOffTheCentre() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            List<MegaBoard.Gate> gates = MegaBoard.gates(rows);
            Set<Integer> steps = new HashSet<>();
            for (MegaBoard.Gate gate : gates) {
                assertThat(gate.step()).isBetween(1, rows - 1);
                assertThat(gate.from()).isBetween(0, gate.to());
                assertThat(gate.to()).isLessThanOrEqualTo(gate.step());
                // three pegs wide, fewer where the board's edge cuts it short
                assertThat(gate.to() - gate.from()).as("%d rows: a bar up to three pegs wide", rows).isBetween(1, 2);
                steps.add(gate.step());
            }
            assertThat(steps).as("%d rows: three gate rows", rows).hasSize(3);
            int spin = MegaBoard.spinPrizeIndex(rows);
            assertThat(spin).isPositive();
            assertThat(PlinkoBoard.leftSlot(rows, spin)).isNotEqualTo(PlinkoBoard.rightSlot(rows, spin));
        }
    }

    @Test
    void theGateFactorIsTheAverageOverEveryPathToTheSlot() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            double[] sum = new double[rows + 1];
            int[] count = new int[rows + 1];
            for (int path = 0; path < 1 << rows; path++) {
                int slot = MegaBoard.slotOf(path);
                sum[slot] += Math.pow(2, MegaBoard.gatesPassed(rows, path));
                count[slot]++;
            }
            for (int slot = 0; slot <= rows; slot++) {
                assertThat(MegaBoard.gateFactor(rows, slot, 2)).as("%d rows slot %d", rows, slot).isCloseTo(sum[slot] / count[slot], within(1e-9));
            }
        }
    }

    @Test
    void everyPathToASlotIsDrawnAlike() {
        int rows = 8, slot = 4, draws = 700_000;
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < draws; i++) {
            int path = bonus.rollPath(rows, slot);
            assertThat(MegaBoard.slotOf(path)).isEqualTo(slot);
            assertThat(MegaBoard.isPath(rows, path)).isTrue();
            seen.merge(path, 1, Integer::sum);
        }
        // 70 paths lead to the middle of an 8-row board
        assertThat(seen).hasSize(70);
        double expected = draws / 70.0;
        double chi = seen.values().stream().mapToDouble(c -> (c - expected) * (c - expected) / expected).sum();
        // 69 degrees of freedom: a fair draw exceeds 130 about once in a million
        assertThat(chi).isLessThan(130);
    }

    @Test
    void tablesBuiltForPlinkoMegaLandOnTheTargetWithinTheHardLimits() {
        OddsService odds = TestOdds.with(MEGA, TestFixtures.properties());
        List<String> report = new ArrayList<>();
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.forTargetRtp(rows, risk, TARGET, MEGA);
                assertThat(BonusService.totalRtp(table, MEGA).doubleValue()).as("%d rows risk %d", rows, risk).isCloseTo(98.0, within(0.05));
                assertThat(odds.validate(table, MEGA)).as("%d rows risk %d", rows, risk).isEmpty();
                report.add(String.format("%2d rows %-4s base %.2f%% gates x%.3f wheels +%.2f%%  prizes %s", rows, PlinkoBoard.RISK_NAMES[risk],
                        table.rtp().doubleValue(), BonusService.gateFactor(MEGA, table).doubleValue(),
                        BonusService.extra(MEGA, table).doubleValue() * 100,
                        table.prizes().stream().map(p -> p.multiplier().stripTrailingZeros().toPlainString()).toList()));
            }
        }
        System.out.println("Plinko Mega tables at " + TARGET + "%:\n" + String.join("\n", report));
    }

    @Test
    void wholeRoundsReturnTheApprovedTotalRtp() {
        OddsService odds = TestOdds.with(MEGA, TestFixtures.properties());
        RoundDraw.Dice dice = new RoundDraw.Dice() {
            public int slot(OddsTable t) {
                return odds.pickSlot(t);
            }

            public int path(int rows, int slot) {
                return bonus.rollPath(rows, slot);
            }

            public int segment(BonusSettings.Wheel wheel) {
                return bonus.rollSegment(wheel);
            }

            public int megaBall(BonusSettings.MegaBall megaBall) {
                return bonus.rollMegaBall(megaBall);
            }
        };
        int rounds = 40_000, batches = 100, perBatch = rounds / batches;
        int spins = 0, megas = 0, megaBalls = 0, roundsWithSpin = 0, played = 0;
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.forTargetRtp(rows, risk, TARGET, MEGA);
                RoundDraw.MeterBefore meter = new RoundDraw.MeterBefore(0, BigDecimal.ZERO, "start");
                double[] batch = new double[batches];
                for (int round = 0; round < rounds; round++) {
                    RoundDraw.Result r = RoundDraw.draw(table, MEGA, BET, BALLS, meter, new BigDecimal("100000000"), dice);
                    batch[round / perBatch] += r.totalWin().doubleValue() / (BALLS * BET.doubleValue()) / perBatch;
                    spins += r.spins().size();
                    megas += r.megaSpins().size();
                    megaBalls += r.megaBalls().size();
                    roundsWithSpin += r.spins().isEmpty() ? 0 : 1;
                    played++;
                    meter = new RoundDraw.MeterBefore(r.chargesAfter(), r.potAfter(), "r" + round);
                }
                // the pot left at the end is owed to the player: what its MEGA SPIN will pay on average
                double owed = meter.pot().doubleValue() * BonusService.expected(MEGA.megaWheel()).doubleValue() / (rounds * BALLS * BET.doubleValue());
                double mean = 0, squares = 0;
                for (double b : batch) {
                    mean += b / batches;
                    squares += b * b / batches;
                }
                double error = Math.sqrt(Math.max(squares - mean * mean, 0) / batches);
                double approved = BonusService.totalRtp(table, MEGA).doubleValue() / 100;
                assertThat(Math.abs(mean + owed - approved)).as("%d rows risk %d: measured %.4f approved %.4f", rows, risk, mean + owed, approved)
                        .isLessThanOrEqualTo(SIGMAS * error + 1e-3);
            }
        }
        assertThat(spins).as("wheel spins").isPositive();
        assertThat(megas).as("MEGA SPINs").isPositive();
        // about 1 ball in 15 is a MEGA BALL
        assertThat((double) megaBalls / ((double) played * BALLS)).as("MEGA BALL share").isBetween(0.06, 0.073);
        System.out.printf("rounds with a wheel spin: %.1f%%, MEGA BALLs per round: %.2f%n", 100.0 * roundsWithSpin / played,
                (double) megaBalls / played);
    }
}
