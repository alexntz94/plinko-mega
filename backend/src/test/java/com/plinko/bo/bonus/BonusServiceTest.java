package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.plinko.TestFixtures;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;

/** The meter, the gates and the wheels: what they add to the return, and their draws. */
class BonusServiceTest {

    private final BonusService bonus = new BonusService(null, null);
    private final OddsService odds = new OddsService(null, TestFixtures.properties(), new com.plinko.common.Integrity(TestFixtures.properties()), null, null, null);
    private static final BonusSettings.Meter METER = new BonusSettings.Meter(true, 10, new BigDecimal("2"), new BigDecimal("30"));
    private static final BonusSettings METER_ONLY = new BonusSettings(METER);

    @Test
    void theMeterAddsFillTimesShareTimesTheGoldWheel() {
        OddsTable table = odds.forTargetRtp(8, 0, new BigDecimal("96"), BonusSettings.OFF);
        // 8 rows / low: standard shape 0.5, 1, 1.1, 2.1, 5.6 -> the two outer positions charge the meter
        double fill = (table.prizes().get(3).probability().doubleValue() + table.prizes().get(4).probability().doubleValue()) / 100;
        assertThat(BonusService.meterPositions(METER_ONLY).get("8:0")).containsExactly(3, 4);
        assertThat(BonusService.meterFill(METER_ONLY, table).doubleValue()).isCloseTo(fill, within(1e-12));
        double gold = BonusService.expected(METER_ONLY.megaWheel()).doubleValue();
        assertThat(BonusService.extra(METER_ONLY, table).doubleValue()).isCloseTo(fill * 0.3 * gold, within(1e-12));
        assertThat(BonusService.gateFactor(METER_ONLY, table)).isEqualByComparingTo("1");
        assertThat(bonus.validate(METER_ONLY)).isEmpty();
    }

    @Test
    void tablesBuiltWithEveryFeatureLandOnTheTotal() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.forTargetRtp(rows, risk, new BigDecimal("96"), BonusSettings.MEGA);
                assertThat(BonusService.totalRtp(table, BonusSettings.MEGA).doubleValue()).as("%d rows risk %d", rows, risk).isCloseTo(96.0, within(0.05));
            }
        }
    }

    /** Plays one player's rounds with the meter as the game server does, choosing boards with the given strategy. */
    private double playWithMeter(boolean switchBeforeFull, long seed) {
        java.util.Random pick = new java.util.Random(seed);
        OddsTable[][] tables = new OddsTable[PlinkoBoard.MAX_ROWS + 1][PlinkoBoard.RISK_LEVELS];
        OddsTable cheapest = null;
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                tables[rows][risk] = odds.forTargetRtp(rows, risk, new BigDecimal("96"), METER_ONLY);
                if (cheapest == null || BonusService.meterFill(METER_ONLY, tables[rows][risk]).compareTo(BonusService.meterFill(METER_ONLY, cheapest)) < 0) {
                    cheapest = tables[rows][risk];
                }
            }
        }
        BigDecimal bet = new BigDecimal("1.00");
        double paid = 0, won = 0;
        int charges = 0;
        BigDecimal pot = BigDecimal.ZERO;
        for (int round = 0; round < 40_000; round++) {
            // the attacker plays the board where meter charges are cheapest whenever the meter is about to fill
            OddsTable table = switchBeforeFull && charges >= METER.charges() - 3 ? cheapest
                    : tables[PlinkoBoard.MIN_ROWS + pick.nextInt(7)][pick.nextInt(3)];
            for (int ball = 0; ball < 20; ball++) {
                int slot = odds.pickSlot(table);
                double m = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(table.rows(), slot)).multiplier().doubleValue();
                if (BonusService.chargesMeter(METER_ONLY, table.rows(), table.risk(), PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                    charges++;
                    pot = pot.add(BonusService.potCharge(METER_ONLY, bet));
                }
                paid += 1;
                won += Math.floor(m * 100) / 100;
            }
            for (int award = 0; charges >= METER.charges() && award < 3; award++) {
                BigDecimal stake = BonusService.megaStake(METER_ONLY, pot, charges);
                charges -= METER.charges();
                pot = pot.subtract(stake);
                double m = METER_ONLY.megaWheel().segments().get(bonus.rollSegment(METER_ONLY.megaWheel())).multiplier().doubleValue();
                won += Math.floor(stake.doubleValue() * m * 100) / 100;
            }
        }
        return won / paid;
    }

    @Test
    void aRoundThatFillsTheMeterTwicePaysBothFullMetersAlike() {
        BigDecimal pot = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) {
            pot = pot.add(BonusService.potCharge(METER_ONLY, new BigDecimal("1.00")));
        }
        int charges = 20;
        BigDecimal first = BonusService.megaStake(METER_ONLY, pot, charges);
        pot = pot.subtract(first);
        charges -= 10;
        BigDecimal second = BonusService.megaStake(METER_ONLY, pot, charges);
        assertThat(first).isPositive();
        // the same, give or take the cent carried over from the first payout's rounding
        assertThat(second).isBetween(first, first.add(new BigDecimal("0.01")));
    }

    @Test
    void theMeterPaysItsShareAndSwitchingBoardGainsNothing() {
        // 800,000 balls each; high-variance boards, so a wide but honest tolerance (about 4 standard deviations)
        double honest = playWithMeter(false, 11);
        double attacker = playWithMeter(true, 12);
        assertThat(honest).isCloseTo(0.96, within(0.02));
        assertThat(attacker).isCloseTo(0.96, within(0.02));
    }

    @Test
    void wheelSpinsFollowTheirWeights() {
        BonusSettings.Wheel wheel = BonusSettings.MEGA.wheel();
        int draws = 1_000_000;
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < draws; i++) {
            counts.merge(bonus.rollSegment(wheel), 1, Integer::sum);
        }
        for (int i = 0; i < wheel.segments().size(); i++) {
            double p = wheel.segments().get(i).weight().doubleValue() / 100;
            double seen = counts.getOrDefault(i, 0) / (double) draws;
            assertThat(Math.abs(seen - p)).as("segment %d", i).isLessThanOrEqualTo(5 * Math.sqrt(p * (1 - p) / draws));
        }
    }

    @Test
    void hardLimitsApplyToTheTotal() {
        // a 99% table is fine alone, but with the wheels on top it is above the 99.7% hard limit
        OddsTable table = odds.forTargetRtp(8, 1, new BigDecimal("99"), BonusSettings.OFF);
        assertThat(odds.validate(table, BonusSettings.OFF)).isEmpty();
        assertThat(odds.validate(table, BonusSettings.MEGA)).anyMatch(e -> e.contains("Total RTP") && e.contains("above the hard limit"));
    }
}
