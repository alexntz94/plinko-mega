package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.plinko.TestFixtures;
import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.odds.TestOdds;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review 5.3 #1: whole rounds played by the real round calculation ({@link RoundDraw}, with the server's own draws), on
 * every board and once per bonus (none, golden balls, hot slot, both): the measured return must be the approved total
 * RTP. Plinko Mega's gates, wheel and MEGA SPIN are measured in MegaRtpTest.
 * <p>
 * The tolerance is tight (about 0.1 point at the default size, on every board) because the noise of the draws is taken
 * out: where the balls landed, which were golden and which slot was hot are controls whose exact mean is known, weighted
 * by the round's own arithmetic, so what is left to measure is what the round calculation and the meter make of them. A
 * board or bonus paying 0.2 point too much or too little fails. The draws themselves are checked separately:
 * {@link #theGoldenAndHotDrawsFollowTheirApprovedOdds} here, and the landing slots by the chi-square in FairnessTest.
 * <p>
 * Rounds per board and bonus: {@value #DEFAULT_ROUNDS} by default (about 7 seconds); a nightly run can go deeper with
 * {@code -DMONTE_CARLO_ROUNDS=50000} or the environment variable {@code MONTE_CARLO_ROUNDS}.
 */
class MonteCarloRtpTest {

    static final int DEFAULT_ROUNDS = 4000;
    private static final int BALLS = 20;
    private static final BigDecimal BET = new BigDecimal("10.00");
    /** About 1 in 3 million for a correct game, per board and bonus (105 of them). */
    private static final double SIGMAS = 5.0;
    /** Rounds are measured in this many batches (the meter carries over between rounds). */
    private static final int BATCHES = 100;

    private static final BonusSettings.Golden GOLDEN = new BonusSettings.Golden(true, new BigDecimal("8"), List.of(
            new BonusSettings.Boost(new BigDecimal("2"), new BigDecimal("60")), new BonusSettings.Boost(new BigDecimal("3"), new BigDecimal("25")),
            new BonusSettings.Boost(new BigDecimal("5"), new BigDecimal("12")), new BonusSettings.Boost(new BigDecimal("10"), new BigDecimal("3"))));
    private static final BonusSettings.Hot HOT = new BonusSettings.Hot(true, new BigDecimal("20"), new BigDecimal("3"));

    private static final Map<String, BonusSettings> BONUSES = new LinkedHashMap<>();

    static {
        BONUSES.put("no bonus", BonusSettings.OFF);
        BONUSES.put("golden balls", new BonusSettings(GOLDEN, BonusSettings.OFF.hot()));
        BONUSES.put("hot slot", new BonusSettings(BonusSettings.OFF.golden(), HOT));
        BONUSES.put("all", new BonusSettings(GOLDEN, HOT));
    }

    static int rounds() {
        String value = System.getProperty("MONTE_CARLO_ROUNDS", System.getenv("MONTE_CARLO_ROUNDS"));
        return value == null || value.isBlank() ? DEFAULT_ROUNDS : Integer.parseInt(value.trim());
    }

    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build());

    /** The measured return of one board with one bonus setting, and its standard error. */
    record Measured(double rtp, double error, int freeBalls) {
    }

    @Test
    void everyBoardPaysItsApprovedTotalRtpWithEachBonusAndTheMeter() {
        int rounds = rounds();
        List<String> report = new ArrayList<>();
        int freeBalls = 0;
        double widest = 0;
        for (Map.Entry<String, BonusSettings> setting : BONUSES.entrySet()) {
            OddsService odds = TestOdds.with(setting.getValue(), TestFixtures.properties());
            for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
                for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                    OddsTable table = odds.get(rows, risk);
                    Measured measured = play(odds, table, setting.getValue(), rounds);
                    double approved = BonusService.totalRtp(table, setting.getValue()).doubleValue() / 100;
                    String board = setting.getKey() + ", " + rows + " rows / " + PlinkoBoard.RISK_NAMES[risk];
                    report.add(String.format("%-32s measured %.4f approved %.4f (+/- %.4f)", board, measured.rtp(), approved, SIGMAS * measured.error()));
                    assertThat(Math.abs(measured.rtp() - approved)).as("%s: measured %.5f, approved %.5f", board, measured.rtp(), approved)
                            .isLessThanOrEqualTo(SIGMAS * measured.error() + 1e-4);
                    freeBalls += measured.freeBalls();
                    widest = Math.max(widest, SIGMAS * measured.error());
                }
            }
        }
        System.out.println("Monte Carlo RTP, " + rounds + " rounds of " + BALLS + " balls per board and bonus:\n" + String.join("\n", report));
        if (rounds >= DEFAULT_ROUNDS) {
            assertThat(widest).as("the widest tolerance, in RTP (1 point = 0.01)").isLessThan(0.002);
        }
    }

    /**
     * The controls above take the golden and hot draws' approved odds as given, so the draws are checked here, tightly:
     * the average golden boost and each boost's share, the share of rounds with a hot slot, and every slot alike.
     */
    @Test
    void theGoldenAndHotDrawsFollowTheirApprovedOdds() {
        BonusSettings settings = BONUSES.get("all");
        // at most 200 million draws (about a minute), however deep the nightly run
        int draws = (int) Math.min(200_000_000L, Math.max(2_000_000L, rounds() * 500L));
        double sum = 0;
        double squares = 0;
        Map<BigDecimal, Integer> boosts = new java.util.TreeMap<>();
        for (int i = 0; i < draws; i++) {
            BigDecimal boost = bonus.rollGolden(settings);
            boosts.merge(boost, 1, Integer::sum);
            sum += boost.doubleValue();
            squares += boost.doubleValue() * boost.doubleValue();
        }
        double mean = sum / draws;
        double error = Math.sqrt((squares / draws - mean * mean) / draws);
        assertThat(Math.abs(mean - expectedGolden(settings))).as("average golden boost %.5f", mean).isLessThanOrEqualTo(SIGMAS * error);
        for (BonusSettings.Boost boost : GOLDEN.boosts()) {
            double p = GOLDEN.chance().doubleValue() / 100 * boost.weight().doubleValue() / 100;
            double seen = boosts.getOrDefault(boost.multiplier(), 0) / (double) draws;
            assertThat(Math.abs(seen - p)).as("share of x%s balls", boost.multiplier()).isLessThanOrEqualTo(SIGMAS * Math.sqrt(p * (1 - p) / draws));
        }
        for (int rows : new int[] {PlinkoBoard.MIN_ROWS, PlinkoBoard.MAX_ROWS}) {
            long[] hot = new long[PlinkoBoard.slotCount(rows)];
            int none = 0;
            int roundsDrawn = draws / 4;
            for (int i = 0; i < roundsDrawn; i++) {
                int slot = bonus.rollHot(settings, rows);
                if (slot < 0) {
                    none++;
                } else {
                    hot[slot]++;
                }
            }
            double p = HOT.chance().doubleValue() / 100;
            double seen = 1 - none / (double) roundsDrawn;
            assertThat(Math.abs(seen - p)).as("share of rounds with a hot slot, %d rows", rows)
                    .isLessThanOrEqualTo(SIGMAS * Math.sqrt(p * (1 - p) / roundsDrawn));
            double expected = (roundsDrawn - none) / (double) hot.length;
            double chi = 0;
            for (long count : hot) {
                chi += (count - expected) * (count - expected) / expected;
            }
            // the chi-square value a fair draw exceeds about once in a million, for up to 14 degrees of freedom, is about 55
            assertThat(chi).as("every slot as often hot, %d rows", rows).isLessThan(56);
        }
    }

    /**
     * Plays rounds on one board, the meter carried from round to round, and estimates the return per unit bet with
     * control variates of exactly known mean zero, their weights fixed by the round's arithmetic (not fitted): for paid and
     * free balls alike, the landed prize against the table's RTP, the golden boost and the hot slot's boost against their
     * approved averages (and the two together), and the paid balls that charged the meter against the table's approved
     * share. What is left is what the round calculation and the meter make of those draws.
     */
    Measured play(OddsService odds, OddsTable table, BonusSettings settings, int rounds) {
        RoundDraw.Dice dice = new RoundDraw.Dice() {
            @Override
            public int hotSlot(BonusSettings b, int rows) {
                return bonus.rollHot(b, rows);
            }

            @Override
            public int slot(OddsTable t) {
                return odds.pickSlot(t);
            }

            @Override
            public BigDecimal golden(BonusSettings b) {
                return bonus.rollGolden(b);
            }

            @Override
            public int path(int rows, int slot) {
                return bonus.rollPath(rows, slot);
            }

            @Override
            public int segment(BonusSettings.Wheel wheel) {
                return bonus.rollSegment(wheel);
            }
        };
        double base = table.rtp().doubleValue() / 100;
        double goldenMean = expectedGolden(settings);
        double hotBoost = settings.hotOn() ? settings.hot().boost().doubleValue() : 1;
        double hotMean = settings.hotOn()
                ? 1 + settings.hot().chance().doubleValue() / 100 * (hotBoost - 1) / PlinkoBoard.slotCount(table.rows()) : 1;
        double fill = BonusService.meterFill(settings, table).doubleValue();
        double totalBet = BET.doubleValue() * BALLS;
        RoundDraw.MeterBefore meter = settings.meterOn() ? new RoundDraw.MeterBefore(0, BigDecimal.ZERO, "start") : null;
        // the meter carries over from round to round, so rounds are taken in batches (batch means), and the batches'
        // spread gives the error; only the batch sums are kept, so a nightly run of millions of rounds needs no memory
        int batches = Math.min(BATCHES, rounds / 10);
        int perBatch = rounds / batches;
        int played = batches * perBatch;
        double[] by = new double[batches];
        double[][] bx = new double[batches][9];
        int freeBalls = 0;
        for (int round = 0; round < played; round++) {
            RoundDraw.Result result = RoundDraw.draw(table, settings, BET, BALLS, meter, new BigDecimal("1000000"), dice);
            int batch = round / perBatch;
            by[batch] += result.totalWin().doubleValue() / totalBet / perBatch;
            int hot = result.hotSlot() == null ? -1 : result.hotSlot();
            double[] c = new double[9];
            for (int ball = 0; ball < result.slots().size(); ball++) {
                int slot = result.slots().get(ball);
                double prize = prize(table, slot);
                c[0] += BET.doubleValue() * (prize - base);
                c[1] += BET.doubleValue() * prize * (result.boosts().get(ball).doubleValue() - goldenMean);
                c[2] += BET.doubleValue() * prize * ((slot == hot ? hotBoost : 1) - hotMean);
                c[7] += BET.doubleValue() * prize * (result.boosts().get(ball).doubleValue() - goldenMean) * ((slot == hot ? hotBoost : 1) - hotMean);
                if (BonusService.chargesMeter(settings, table.rows(), table.risk(), PlinkoBoard.prizeIndexOfSlot(table.rows(), slot))) {
                    c[6] += BET.doubleValue();
                }
            }
            c[6] -= BET.doubleValue() * BALLS * fill;
            for (int ball = 0; ball < result.freeSlots().size(); ball++) {
                int slot = result.freeSlots().get(ball);
                double stake = result.freeBets().get(ball).doubleValue();
                double prize = prize(table, slot);
                c[3] += stake * (prize - base);
                c[4] += stake * prize * (result.freeBoosts().get(ball).doubleValue() - goldenMean);
                c[5] += stake * prize * ((slot == hot ? hotBoost : 1) - hotMean);
                c[8] += stake * prize * (result.freeBoosts().get(ball).doubleValue() - goldenMean) * ((slot == hot ? hotBoost : 1) - hotMean);
            }
            for (int k = 0; k < c.length; k++) {
                bx[batch][k] += c[k] / totalBet / perBatch;
            }
            freeBalls += result.freeSlots().size();
            if (meter != null) {
                meter = new RoundDraw.MeterBefore(result.chargesAfter(), result.potAfter(), "r" + round);
            }
        }
        // what the pot still holds at the end is owed to the player: its value, at the table's average return per stake
        double owed = meter == null ? 0
                : meter.pot().doubleValue() * BonusService.totalRtp(table, settings).doubleValue() / 100 / (totalBet * played);
        // the coefficients follow from the round's own arithmetic (a ball pays stake x prize x golden x hot), so nothing is
        // fitted: a round calculation that pays differently moves the result instead of being fitted away
        double both = goldenMean * hotMean;
        double share = settings.meterOn() ? settings.meter().potShare().doubleValue() / 100 : 0;
        double[] beta = {both, hotMean, goldenMean, both, hotMean, goldenMean, both * base * share, 1, 1};
        double sum = 0;
        double squares = 0;
        for (int batch = 0; batch < batches; batch++) {
            double adjusted = by[batch];
            for (int k = 0; k < beta.length; k++) {
                adjusted -= beta[k] * bx[batch][k];
            }
            sum += adjusted;
            squares += adjusted * adjusted;
        }
        double mean = sum / batches;
        double sd = Math.sqrt(Math.max(0, (squares - batches * mean * mean) / (batches - 1)));
        return new Measured(mean + owed, sd / Math.sqrt(batches), freeBalls);
    }

    /** The approved average golden boost of a ball (1 when golden balls are off). */
    static double expectedGolden(BonusSettings settings) {
        if (!settings.goldenOn()) {
            return 1;
        }
        double boost = settings.golden().boosts().stream().mapToDouble(b -> b.multiplier().doubleValue() * b.weight().doubleValue() / 100).sum();
        return 1 + settings.golden().chance().doubleValue() / 100 * (boost - 1);
    }

    private static double prize(OddsTable table, int slot) {
        return table.prizes().get(PlinkoBoard.prizeIndexOfSlot(table.rows(), slot)).multiplier().doubleValue();
    }
}
