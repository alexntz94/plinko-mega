package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.plinko.TestFixtures;
import com.plinko.bo.bonus.BonusService;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review 5.3 #1-#2: the real draw (OddsService.pickSlot, BonusService.rollGolden / rollHot, SecureRandom) against the
 * approved odds, on every board. The measured return must match the table's RTP (with golden balls and the hot slot:
 * the approved total RTP) within a wide statistical margin, and the landing slots must follow the approved
 * probabilities (chi-square). Margins are set so a fair game fails less than once in a million runs.
 */
class FairnessTest {

    private static final int BALLS = 1_000_000;
    /** About 5 standard deviations: a fair game is outside this less than once in a million. */
    private static final double SIGMAS = 5.0;

    private static final BonusSettings GOLDEN_AND_HOT = new BonusSettings(
            new BonusSettings.Golden(true, new BigDecimal("8"), BonusSettings.OFF.golden().boosts()),
            new BonusSettings.Hot(true, new BigDecimal("20"), new BigDecimal("3")));

    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build());

    @Test
    void everyBoardPaysItsApprovedRtpAndLandsWhereTheOddsSay() {
        check(BonusSettings.OFF);
    }

    @Test
    void withGoldenBallsAndTheHotSlotEveryBoardPaysTheApprovedTotalRtp() {
        check(GOLDEN_AND_HOT);
    }

    private void check(BonusSettings settings) {
        OddsService odds = TestOdds.with(settings, TestFixtures.properties());
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.get(rows, risk);
                String board = rows + " rows / " + PlinkoBoard.RISK_NAMES[risk];
                long[] landed = new long[PlinkoBoard.slotCount(rows)];
                double sum = 0;
                double squares = 0;
                for (int ball = 0; ball < BALLS; ball++) {
                    int hot = bonus.rollHot(settings, rows);          // each ball as its own one-ball round
                    int slot = odds.pickSlot(table);
                    landed[slot]++;
                    double paid = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(rows, slot)).multiplier().doubleValue()
                            * bonus.rollGolden(settings).doubleValue() * (slot == hot ? settings.hot().boost().doubleValue() : 1);
                    sum += paid;
                    squares += paid * paid;
                }
                double mean = sum / BALLS;
                double spread = Math.sqrt(Math.max(0, squares / BALLS - mean * mean) / BALLS);
                double expected = BonusService.totalRtp(table, settings).doubleValue() / 100;
                assertThat(Math.abs(mean - expected)).as("%s: measured return %.5f, approved %.5f", board, mean, expected)
                        .isLessThanOrEqualTo(SIGMAS * spread + 1e-9);
                assertThat(chiSquare(table, landed)).as("%s: landing slots against the approved probabilities", board)
                        .isLessThan(critical(landed.length - 1));
            }
        }
    }

    /** Chi-square of the landing counts against the table: a prize's probability is shared equally by its slots. */
    private static double chiSquare(OddsTable table, long[] landed) {
        int rows = table.rows();
        int[] slotsOfPrize = new int[table.prizes().size()];
        for (int slot = 0; slot < landed.length; slot++) {
            slotsOfPrize[PlinkoBoard.prizeIndexOfSlot(rows, slot)]++;
        }
        double chi = 0;
        for (int slot = 0; slot < landed.length; slot++) {
            int prize = PlinkoBoard.prizeIndexOfSlot(rows, slot);
            double expected = BALLS * table.prizes().get(prize).probability().doubleValue() / 100 / slotsOfPrize[prize];
            if (expected > 0) {
                chi += Math.pow(landed[slot] - expected, 2) / expected;
            } else {
                assertThat(landed[slot]).as("slot %d has probability 0", slot).isZero();
            }
        }
        return chi;
    }

    /** The chi-square value a fair draw exceeds with probability about 1 in a million (Wilson-Hilferty). */
    private static double critical(int degrees) {
        double z = 4.753;
        double k = degrees;
        return k * Math.pow(1 - 2 / (9 * k) + z * Math.sqrt(2 / (9 * k)), 3);
    }

    @Test
    void theCriticalValuesAreSane() {
        // the chi-square 1-in-a-million value for 8 degrees is about 41.5, for 14 about 55.0
        assertThat(List.of(critical(8), critical(14))).allSatisfy(v -> assertThat(v).isBetween(35.0, 60.0));
    }
}
