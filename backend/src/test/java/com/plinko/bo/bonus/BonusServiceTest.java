package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.plinko.TestFixtures;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;

class BonusServiceTest {

    private final BonusService bonus = new BonusService(null, null);
    private final OddsService odds = new OddsService(null, TestFixtures.properties(), new com.plinko.common.Integrity(TestFixtures.properties()), null, null, null);

    private static BonusSettings golden(String chance, String... boosts) {
        List<BonusSettings.Boost> list = new java.util.ArrayList<>();
        for (int i = 0; i < boosts.length; i += 2) {
            list.add(new BonusSettings.Boost(new BigDecimal(boosts[i]), new BigDecimal(boosts[i + 1])));
        }
        return new BonusSettings(new BonusSettings.Golden(true, new BigDecimal(chance), list), BonusSettings.OFF.hot());
    }

    private static final BonusSettings DEFAULT_ON = golden("1", "2", "60", "3", "25", "5", "12", "10", "3");
    private static final BonusSettings HOT_ONLY = new BonusSettings(BonusSettings.OFF.golden(), new BonusSettings.Hot(true, new BigDecimal("20"), new BigDecimal("3")));
    private static final BonusSettings BOTH = new BonusSettings(DEFAULT_ON.golden(), HOT_ONLY.hot());
    private static final BonusSettings.Meter METER = new BonusSettings.Meter(true, 10, new BigDecimal("2"), new BigDecimal("30"));
    private static final BonusSettings ALL = new BonusSettings(DEFAULT_ON.golden(), HOT_ONLY.hot(), METER);

    @Test
    void goldenBallAddsChanceTimesExtraBoost() {
        // E[boost] = 2*.6 + 3*.25 + 5*.12 + 10*.03 = 2.85; 1% of balls pay 1.85x more
        assertThat(BonusService.factor(DEFAULT_ON, 8)).isEqualByComparingTo("1.0185");
        assertThat(BonusService.factor(DEFAULT_ON, 14)).isEqualByComparingTo("1.0185");
        assertThat(BonusService.share(DEFAULT_ON, 8)).isEqualByComparingTo("1.85");
        assertThat(BonusService.factor(BonusSettings.OFF, 8)).isEqualByComparingTo("1");
        assertThat(BonusService.maxGoldenBoost(DEFAULT_ON)).isEqualByComparingTo("10");
    }

    @Test
    void hotSlotAddsChanceTimesExtraBoostOverTheSlotCount() {
        // 20% of rounds one of rows+1 slots pays 3x: 8 rows 1 + 0.2 * 2 / 9, 14 rows 1 + 0.2 * 2 / 15
        assertThat(BonusService.factor(HOT_ONLY, 8).doubleValue()).isCloseTo(1 + 0.4 / 9, within(1e-12));
        assertThat(BonusService.factor(HOT_ONLY, 14).doubleValue()).isCloseTo(1 + 0.4 / 15, within(1e-12));
        // independent draws: the factors multiply
        assertThat(BonusService.factor(BOTH, 8).doubleValue()).isCloseTo(1.0185 * (1 + 0.4 / 9), within(1e-12));
        assertThat(BonusService.shareText(HOT_ONLY)).isEqualTo("2.67% (14 rows) to 4.44% (8 rows)");
        assertThat(BonusService.shareText(DEFAULT_ON)).isEqualTo("1.85%");
    }

    @Test
    void settingsFromBeforeHotSlotsKeepTheGoldenBall() {
        BonusSettings old = new BonusSettings(DEFAULT_ON.golden(), null);
        BonusSettings read = BonusService.withDefaults(old);
        assertThat(read.goldenOn()).isTrue();
        assertThat(read.hotOn()).isFalse();
        assertThat(bonus.validate(read)).isEmpty();
    }

    @Test
    void hotSlotsAreDrawnEvenlyInTheRightShareOfRounds() {
        int rounds = 300_000;
        int[] counts = new int[9];
        int none = 0;
        for (int i = 0; i < rounds; i++) {
            int slot = bonus.rollHot(HOT_ONLY, 8);
            if (slot < 0) {
                none++;
            } else {
                counts[slot]++;
            }
        }
        assertThat((rounds - none) / (double) rounds).isCloseTo(0.20, within(0.004));
        for (int count : counts) {
            assertThat(count / (double) (rounds - none)).isCloseTo(1 / 9.0, within(0.006));
        }
        assertThat(bonus.rollHot(BonusSettings.OFF, 8)).isEqualTo(-1);
    }

    @Test
    void meterAddsFillTimesShareOfTheTable() {
        OddsTable table = odds.forTargetRtp(8, 0, new BigDecimal("96"), BonusSettings.OFF);
        // 8 rows / low: standard shape 0.5, 1, 1.1, 2.1, 5.6 -> the two outer positions charge the meter
        double fill = (table.prizes().get(3).probability().doubleValue() + table.prizes().get(4).probability().doubleValue()) / 100;
        assertThat(BonusService.meterPositions(new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(), METER)).get("8:0")).containsExactly(3, 4);
        BonusSettings meterOnly = new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(), METER);
        assertThat(BonusService.meterFill(meterOnly, table).doubleValue()).isCloseTo(fill, within(1e-12));
        // Plinko Mega: the pot is money, paid as a MEGA SPIN of the gold wheel: it adds fill x share x E[gold wheel]
        double gold = BonusService.expected(meterOnly.megaWheel()).doubleValue();
        assertThat(BonusService.extra(meterOnly, table).doubleValue()).isCloseTo(fill * 0.3 * gold, within(1e-12));
        assertThat(BonusService.factor(meterOnly, table).doubleValue()).isEqualTo(1.0);
        assertThat(BonusService.factor(ALL, table).doubleValue()).isCloseTo(1.0185 * (1 + 0.4 / 9), within(1e-12));
        assertThat(bonus.validate(ALL)).isEmpty();
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(),
                new BonusSettings.Meter(true, 2, new BigDecimal("2"), new BigDecimal("50"))))).anyMatch(e -> e.contains("between 3 and 100 charges"));
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(),
                new BonusSettings.Meter(true, 10, new BigDecimal("2"), new BigDecimal("0"))))).anyMatch(e -> e.contains("above 0%"));
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(),
                new BonusSettings.Meter(true, 10, new BigDecimal("1"), new BigDecimal("50"))))).anyMatch(e -> e.contains("above 1x"));
    }

    @Test
    void tablesBuiltWithTheMeterLandOnTheTotal() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.forTargetRtp(rows, risk, new BigDecimal("96"), ALL);
                assertThat(BonusService.totalRtp(table, ALL).doubleValue()).as("%d rows risk %d", rows, risk).isCloseTo(96.0, within(0.05));
                assertThat(BonusService.factor(ALL, table)).isLessThanOrEqualTo(BonusService.MAX_FACTOR);
            }
        }
    }

    /**
     * Plays one player's rounds with all three bonuses exactly as the game server does (hot slot per round; per paid
     * ball slot, golden boost, meter charge; full meter pays a MEGA SPIN), choosing boards with the given strategy.
     */
    private double playWithMeter(boolean switchBeforeFull, long seed) {
        java.util.Random pick = new java.util.Random(seed);
        OddsTable[][] tables = new OddsTable[PlinkoBoard.MAX_ROWS + 1][PlinkoBoard.RISK_LEVELS];
        OddsTable cheapest = null;
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                tables[rows][risk] = odds.forTargetRtp(rows, risk, new BigDecimal("96"), ALL);
                if (cheapest == null || BonusService.meterFill(ALL, tables[rows][risk]).compareTo(BonusService.meterFill(ALL, cheapest)) < 0) {
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
            int hot = bonus.rollHot(ALL, table.rows());
            for (int ball = 0; ball < 20; ball++) {
                int slot = odds.pickSlot(table);
                BigDecimal prize = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(table.rows(), slot)).multiplier();
                double m = prize.multiply(bonus.rollGolden(ALL)).doubleValue() * (slot == hot ? 3 : 1);
                if (BonusService.chargesMeter(ALL, table.rows(), table.risk(), PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                    charges++;
                    pot = pot.add(BonusService.potCharge(ALL, bet));
                }
                paid += 1;
                won += Math.floor(m * 100) / 100;
            }
            for (int award = 0; charges >= METER.charges() && award < 3; award++) {
                BigDecimal stake = BonusService.megaStake(ALL, pot, charges);
                charges -= METER.charges();
                pot = pot.subtract(stake);
                double m = ALL.megaWheel().segments().get(bonus.rollSegment(ALL.megaWheel())).multiplier().doubleValue();
                won += Math.floor(stake.doubleValue() * m * 100) / 100;
            }
        }
        return won / paid;
    }

    @Test
    void aRoundThatFillsTheMeterTwicePaysBothFullMetersAlike() {
        OddsTable table = odds.forTargetRtp(8, 0, new BigDecimal("96"), ALL);
        BigDecimal pot = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) {
            pot = pot.add(BonusService.potCharge(ALL, new BigDecimal("1.00")));
        }
        int charges = 20;
        BigDecimal first = BonusService.megaStake(ALL, pot, charges);
        pot = pot.subtract(first);
        charges -= 10;
        BigDecimal second = BonusService.megaStake(ALL, pot, charges);
        assertThat(first).isPositive();
        // the same, give or take the cent carried over from the first payout's rounding
        assertThat(second).isBetween(first, first.add(new BigDecimal("0.01")));
    }

    @Test
    void theMeterPaysItsShareAndSwitchingBoardGainsNothing() {
        // 800,000 paid balls each; high-variance boards, so a wide but honest tolerance (about 4 standard deviations)
        double honest = playWithMeter(false, 11);
        double attacker = playWithMeter(true, 12);
        assertThat(honest).isCloseTo(0.96, within(0.02));
        assertThat(attacker).isCloseTo(0.96, within(0.02));
    }

    @Test
    void validSettingsPassAndBrokenOnesAreRefused() {
        assertThat(bonus.validate(DEFAULT_ON)).isEmpty();
        assertThat(bonus.validate(BonusSettings.OFF)).isEmpty();
        assertThat(bonus.validate(golden("1", "2", "60", "3", "30"))).anyMatch(e -> e.contains("add up to 100"));
        assertThat(bonus.validate(golden("11", "2", "100"))).anyMatch(e -> e.contains("between 0% and 10%"));
        assertThat(bonus.validate(golden("1", "1", "100"))).anyMatch(e -> e.contains("above 1x"));
        assertThat(bonus.validate(golden("1", "101", "100"))).anyMatch(e -> e.contains("at most 100x"));
        assertThat(bonus.validate(golden("1", "2", "-10", "3", "110"))).isNotEmpty();
        assertThat(bonus.validate(new BonusSettings(null, null))).isNotEmpty();
        assertThat(bonus.validate(HOT_ONLY)).isEmpty();
        assertThat(bonus.validate(BOTH)).isEmpty();
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), new BonusSettings.Hot(true, new BigDecimal("101"), new BigDecimal("3")))))
                .anyMatch(e -> e.contains("between 0% and 100%"));
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), new BonusSettings.Hot(true, new BigDecimal("20"), new BigDecimal("11")))))
                .anyMatch(e -> e.contains("at most 10x"));
        // every round hot at 10x on 8 rows: +100%
        assertThat(bonus.validate(new BonusSettings(BonusSettings.OFF.golden(), new BonusSettings.Hot(true, new BigDecimal("100"), new BigDecimal("10")))))
                .anyMatch(e -> e.contains("at most 15%"));
        // 10% of balls x50: +490% would hollow out the base game
        assertThat(bonus.validate(golden("10", "50", "100"))).anyMatch(e -> e.contains("at most 15%"));
    }

    @Test
    void drawsFollowTheSettings() {
        BonusSettings often = golden("10", "2", "60", "3", "25", "5", "12", "10", "3");
        int draws = 400_000;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < draws; i++) {
            counts.merge(bonus.rollGolden(often).stripTrailingZeros().toPlainString(), 1, Integer::sum);
        }
        int goldenBalls = draws - counts.getOrDefault("1", 0);
        assertThat(goldenBalls / (double) draws).isCloseTo(0.10, within(0.003));
        assertThat(counts.getOrDefault("2", 0) / (double) goldenBalls).isCloseTo(0.60, within(0.02));
        assertThat(counts.getOrDefault("10", 0) / (double) goldenBalls).isCloseTo(0.03, within(0.01));
        assertThat(bonus.rollGolden(BonusSettings.OFF)).isEqualByComparingTo("1");
    }

    @Test
    void tablesBuiltForATotalLandOnItWithTheBonus() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.forTargetRtp(rows, risk, new BigDecimal("96"), BOTH);
                assertThat(BonusService.totalRtp(table, BOTH).doubleValue()).as("%d rows risk %d", rows, risk).isCloseTo(96.0, within(0.01));
                assertThat(table.rtp().doubleValue()).isCloseTo(96.0 / BonusService.factor(BOTH, rows).doubleValue(), within(0.01));
                assertThat(odds.validate(table, BOTH)).isEmpty();
            }
        }
    }

    @Test
    void playedBallsReturnTheTargetWithBothBonuses() {
        // the server's own draws (hot slot per round; slot and golden boost per ball) on tables built for a 96% total:
        // 2 million balls
        java.util.Random pick = new java.util.Random(7);
        double bet = 0, win = 0, variance = 0;
        for (int round = 0; round < 20_000; round++) {
            int rows = PlinkoBoard.MIN_ROWS + pick.nextInt(PlinkoBoard.MAX_ROWS - PlinkoBoard.MIN_ROWS + 1);
            int risk = pick.nextInt(PlinkoBoard.RISK_LEVELS);
            OddsTable table = odds.forTargetRtp(rows, risk, new BigDecimal("96"), BOTH);
            int hot = bonus.rollHot(BOTH, rows);
            for (int ball = 0; ball < 100; ball++) {
                int slot = odds.pickSlot(table);
                double paid = table.prizes().get(PlinkoBoard.prizeIndexOfSlot(rows, slot)).multiplier()
                        .multiply(bonus.rollGolden(BOTH)).doubleValue() * (slot == hot ? 3 : 1);
                bet += 1;
                win += paid;
                variance += paid * paid;
            }
        }
        double rtp = win / bet;
        double sd = Math.sqrt((variance / bet - rtp * rtp) / bet);
        // within 4 standard deviations of 96% (a correct implementation fails this about once in 16,000 runs)
        assertThat(rtp).isCloseTo(0.96, within(4 * sd));
    }

    @Test
    void hardLimitsApplyToTheTotal() {
        // a 98% table is fine alone, but with +1.85% of bonuses it is 99.81%: above the 99.7% hard limit
        OddsTable table = odds.forTargetRtp(8, 1, new BigDecimal("98"), BonusSettings.OFF);
        assertThat(odds.validate(table, BonusSettings.OFF)).isEmpty();
        assertThat(odds.validate(table, DEFAULT_ON)).anyMatch(e -> e.contains("Total RTP") && e.contains("above the hard limit"));
    }
}
