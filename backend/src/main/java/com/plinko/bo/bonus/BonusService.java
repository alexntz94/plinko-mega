package com.plinko.bo.bonus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.settings.SettingsService;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The paying bonuses: their approved settings, what they add to the return to player, and the server-side draws.
 * <p>
 * The maths: a golden ball multiplies its prize by a boost B with probability c, so on average every ball pays
 * {@code 1 + c * (E[B] - 1)} times what the table alone pays. A hot slot, lit in a share q of rounds on one of the
 * {@code rows + 1} slots picked uniformly, pays H times: every ball then pays {@code 1 + q * (H - 1) / (rows + 1)} times
 * as much on average, whatever the table. Both are drawn independently of each other and of the landing slot, so the
 * factors multiply. The bonus meter takes a share s of the bet of every paid ball that lands on a prize of at least
 * the minimum (probability f on that table) into a pot, and later plays the pot as free balls on the same kind of
 * board: on average every paid ball also funds {@code f * s} of a free ball, so the meter multiplies by
 * {@code 1 + f * s}. The total RTP of a table is its own RTP times the factor; the hard RTP limits apply to the total,
 * and base tables are rebuilt so the total stays on target. (A pot a player never fills is never paid, so over time
 * players get very slightly less than the total, never more.)
 */
@Service
public class BonusService {

    /** Bonuses may add at most this much on top of the base game (factor 1.15 = +15%), so the base game keeps its value. */
    public static final BigDecimal MAX_FACTOR = new BigDecimal("1.15");
    private static final String SETTING = "game.bonus";

    private final SettingsService settings;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public BonusService(SettingsService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** The approved settings; anything unreadable counts as "all off" (and a database edit already stops the game). */
    public BonusSettings current() {
        return parse(stored());
    }

    /** The stored bonus settings as text, exactly as saved ({@code null} when never set). */
    public String stored() {
        return settings.rawValues().get(SETTING);
    }

    /** The stored bonus settings as text in a settings snapshot (one read for a whole round, N14.10). */
    public String stored(com.plinko.bo.settings.SettingsService.Snapshot snapshot) {
        return snapshot.raw(SETTING);
    }

    /** Reads stored bonus settings; anything unreadable or invalid counts as "all off". */
    public BonusSettings parse(String stored) {
        if (stored == null) {
            return BonusSettings.OFF;
        }
        try {
            BonusSettings parsed = withDefaults(json.readValue(stored, BonusSettings.class));
            return validate(parsed).isEmpty() ? parsed : BonusSettings.OFF;
        } catch (JacksonException e) {
            return BonusSettings.OFF;
        }
    }

    /** Settings saved before a feature existed have no entry for it: it is off. */
    public static BonusSettings withDefaults(BonusSettings bonus) {
        if (bonus == null) {
            return null;
        }
        return new BonusSettings(bonus.golden() == null ? BonusSettings.OFF.golden() : bonus.golden(),
                bonus.hot() == null ? BonusSettings.OFF.hot() : bonus.hot(),
                bonus.meter() == null ? BonusSettings.OFF.meter() : bonus.meter());
    }

    public String toJson(BonusSettings bonus) {
        return json.writeValueAsString(bonus);
    }

    public List<String> validate(BonusSettings bonus) {
        List<String> errors = new ArrayList<>();
        if (bonus == null || bonus.golden() == null || bonus.hot() == null) {
            errors.add("Bonus settings are incomplete");
            return errors;
        }
        BonusSettings.Hot hot = bonus.hot();
        if (hot.chance() == null || hot.chance().signum() < 0 || hot.chance().compareTo(OddsTable.HUNDRED) > 0
                || hot.chance().stripTrailingZeros().scale() > 2) {
            errors.add("Hot slot chance must be between 0% and 100%, with at most 2 decimals");
        }
        if (hot.boost() == null || hot.boost().compareTo(BigDecimal.ONE) <= 0 || hot.boost().compareTo(BigDecimal.TEN) > 0
                || hot.boost().stripTrailingZeros().scale() > 2) {
            errors.add("Hot slot boost must be above 1x and at most 10x, with at most 2 decimals");
        }
        BonusSettings.Meter meter = bonus.meter();
        if (meter != null) {
            if (meter.charges() < 3 || meter.charges() > 100) {
                errors.add("The meter needs between 3 and 100 charges");
            }
            if (meter.minMultiplier() == null || meter.minMultiplier().compareTo(BigDecimal.ONE) <= 0
                    || meter.minMultiplier().compareTo(BigDecimal.valueOf(1000)) > 0 || meter.minMultiplier().stripTrailingZeros().scale() > 2) {
                errors.add("The meter's prize to charge it must be above 1x and at most 1000x, with at most 2 decimals");
            }
            if (meter.potShare() == null || meter.potShare().signum() <= 0 || meter.potShare().compareTo(OddsTable.HUNDRED) > 0
                    || meter.potShare().stripTrailingZeros().scale() > 2) {
                errors.add("The share of the bet put into the pot must be above 0% and at most 100%, with at most 2 decimals");
            }
            if (meter.freeBalls() < 1 || meter.freeBalls() > 50) {
                errors.add("A full meter pays between 1 and 50 free balls");
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        BonusSettings.Golden golden = bonus.golden();
        if (golden.chance() == null || golden.chance().signum() < 0 || golden.chance().compareTo(BigDecimal.TEN) > 0) {
            errors.add("Golden ball chance must be between 0% and 10%");
        } else if (golden.chance().stripTrailingZeros().scale() > 2) {
            errors.add("Golden ball chance can have at most 2 decimals");
        }
        List<BonusSettings.Boost> boosts = golden.boosts();
        if (boosts == null || boosts.isEmpty() || boosts.size() > 6) {
            errors.add("Golden ball needs 1 to 6 boosts");
        } else {
            BigDecimal weights = BigDecimal.ZERO;
            for (BonusSettings.Boost boost : boosts) {
                if (boost == null || boost.multiplier() == null || boost.weight() == null) {
                    errors.add("Every boost needs a multiplier and a weight");
                    return errors;
                }
                if (boost.multiplier().compareTo(BigDecimal.ONE) <= 0 || boost.multiplier().compareTo(BigDecimal.valueOf(100)) > 0
                        || boost.multiplier().stripTrailingZeros().scale() > 2) {
                    errors.add("Boosts must be above 1x and at most 100x, with at most 2 decimals");
                }
                if (boost.weight().signum() <= 0 || boost.weight().stripTrailingZeros().scale() > 2) {
                    errors.add("Boost weights must be above 0, with at most 2 decimals");
                }
                weights = weights.add(boost.weight());
            }
            if (weights.compareTo(OddsTable.HUNDRED) != 0) {
                errors.add("Boost weights must add up to 100% (now " + weights.stripTrailingZeros().toPlainString() + "%)");
            }
        }
        // the smallest board has the fewest slots, so a hot slot adds the most there
        if (errors.isEmpty() && factor(bonus, PlinkoBoard.MIN_ROWS).compareTo(MAX_FACTOR) > 0) {
            errors.add("Bonuses can add at most " + MAX_FACTOR.subtract(BigDecimal.ONE).movePointRight(2).stripTrailingZeros().toPlainString()
                    + "% on top of the base game (these settings add " + share(bonus, PlinkoBoard.MIN_ROWS) + "% on " + PlinkoBoard.MIN_ROWS + " rows)");
        }
        return errors;
    }

    /** How much more every ball pays on average with these bonuses on a board with this many rows (1 = no bonus). */
    public static BigDecimal factor(BonusSettings bonus, int rows) {
        BigDecimal factor = BigDecimal.ONE;
        if (bonus.goldenOn()) {
            BigDecimal expectedBoost = bonus.golden().boosts().stream()
                    .map(b -> b.multiplier().multiply(b.weight()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
            BigDecimal chance = bonus.golden().chance().divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
            factor = factor.add(chance.multiply(expectedBoost.subtract(BigDecimal.ONE)));
        }
        if (bonus.hotOn()) {
            BigDecimal chance = bonus.hot().chance().divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
            BigDecimal perBall = chance.multiply(bonus.hot().boost().subtract(BigDecimal.ONE))
                    .divide(BigDecimal.valueOf(PlinkoBoard.slotCount(rows)), MathContext.DECIMAL64);
            factor = factor.multiply(BigDecimal.ONE.add(perBall), MathContext.DECIMAL64);
        }
        return factor;
    }

    /**
     * Whether a ball landing on this prize position charges the meter: the positions that pay at least the meter's
     * minimum on the standard table shape. Fixed per board and risk, so rebuilding a table for an RTP (which can move
     * a 2x prize to 1.9x) never moves a position in or out of the meter; the game marks these slots.
     */
    public static boolean chargesMeter(BonusSettings bonus, int rows, int risk, int prizeIndex) {
        if (!bonus.meterOn()) {
            return false;
        }
        double[][] shape = com.plinko.bo.odds.OddsDefaults.MULTIPLIERS.get(rows);
        return shape != null && prizeIndex >= 0 && prizeIndex < shape.length
                && BigDecimal.valueOf(shape[prizeIndex][risk]).compareTo(bonus.meter().minMultiplier()) >= 0;
    }

    /** The prize positions that charge the meter, per board ("rows:risk"), for the game to mark. */
    public static java.util.Map<String, List<Integer>> meterPositions(BonusSettings bonus) {
        java.util.Map<String, List<Integer>> positions = new java.util.TreeMap<>();
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                List<Integer> list = new ArrayList<>();
                for (int prize = 0; prize < PlinkoBoard.prizeCount(rows); prize++) {
                    if (chargesMeter(bonus, rows, risk, prize)) {
                        list.add(prize);
                    }
                }
                positions.put(rows + ":" + risk, list);
            }
        }
        return positions;
    }

    /** Share (0..1) of balls on this table that land on a position that charges the meter. */
    public static BigDecimal meterFill(BonusSettings bonus, OddsTable table) {
        if (!bonus.meterOn()) {
            return BigDecimal.ZERO;
        }
        return table.prizes().stream()
                .filter(p -> chargesMeter(bonus, table.rows(), table.risk(), p.index()))
                .map(OddsTable.Prize::probability)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
    }

    /**
     * The meter's pot holds value, not money: a charge from a ball on this table adds bet * share / conversion, and a
     * payout on this table turns value back into stake with * conversion, where conversion = 1 + fill * share. A pot
     * then pays the same on average whichever board it is filled or paid on, so switching board just before the meter
     * fills gains nothing, and every table's total RTP stays exact.
     */
    public static BigDecimal potConversion(BonusSettings bonus, OddsTable table) {
        if (!bonus.meterOn()) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(meterFill(bonus, table).multiply(bonus.meter().potShare().divide(OddsTable.HUNDRED, MathContext.DECIMAL64)));
    }

    /** What one charging paid ball with this bet adds to the pot (in value). */
    public static BigDecimal potCharge(BonusSettings bonus, OddsTable table, BigDecimal bet) {
        return bet.multiply(bonus.meter().potShare()).divide(OddsTable.HUNDRED.multiply(potConversion(bonus, table)), 6, RoundingMode.DOWN);
    }

    /** The stake of each free ball when a pot of this value pays out on this table (whole cents, rounded down). */
    public static BigDecimal freeStake(BonusSettings bonus, OddsTable table, BigDecimal pot) {
        return pot.multiply(potConversion(bonus, table)).divide(BigDecimal.valueOf(bonus.meter().freeBalls()), 2, RoundingMode.DOWN);
    }

    /**
     * The stake of each free ball for one full meter, when the player has {@code charges} charges (at least one full
     * meter): a full meter pays its own share of the pot, so a round that fills the meter twice pays both alike.
     */
    public static BigDecimal awardStake(BonusSettings bonus, OddsTable table, BigDecimal pot, int charges) {
        BigDecimal share = pot.multiply(BigDecimal.valueOf(bonus.meter().charges())).divide(BigDecimal.valueOf(Math.max(charges, 1)), 6, RoundingMode.DOWN);
        return freeStake(bonus, table, share);
    }

    /** The pot left after paying free balls with this stake: the fractions of a cent stay in it. */
    public static BigDecimal potAfterPayout(BonusSettings bonus, OddsTable table, BigDecimal pot, BigDecimal stake) {
        BigDecimal used = stake.multiply(BigDecimal.valueOf(bonus.meter().freeBalls())).divide(potConversion(bonus, table), 6, RoundingMode.UP);
        return pot.subtract(used).max(BigDecimal.ZERO);
    }

    /** Everything the bonuses add on this table: golden ball and hot slot (by board size) and the meter (by table). */
    public static BigDecimal factor(BonusSettings bonus, OddsTable table) {
        return factor(bonus, table.rows()).multiply(potConversion(bonus, table), MathContext.DECIMAL64);
    }

    /** "+x%" for these tables: the range from the table where the bonuses add least to the one where they add most. */
    public static String shareText(BonusSettings bonus, java.util.Collection<OddsTable> tables) {
        if (!bonus.meterOn() || tables.isEmpty()) {
            return shareText(bonus);
        }
        List<BigDecimal> shares = tables.stream()
                .map(t -> factor(bonus, t).subtract(BigDecimal.ONE).movePointRight(2).setScale(2, RoundingMode.HALF_UP)).sorted().toList();
        BigDecimal low = shares.get(0);
        BigDecimal high = shares.get(shares.size() - 1);
        return low.compareTo(high) == 0 ? high.toPlainString() + "%" : low.toPlainString() + "% to " + high.toPlainString() + "% depending on the table";
    }

    /** What the bonuses add on a board with this many rows, in percent of the table's own RTP (e.g. 1.85). */
    public static BigDecimal share(BonusSettings bonus, int rows) {
        return factor(bonus, rows).subtract(BigDecimal.ONE).movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }

    /** "1.85%" when it is the same on every board, else the range from the biggest board to the smallest. */
    public static String shareText(BonusSettings bonus) {
        BigDecimal small = share(bonus, PlinkoBoard.MAX_ROWS);
        BigDecimal big = share(bonus, PlinkoBoard.MIN_ROWS);
        return small.compareTo(big) == 0 ? big.toPlainString() + "%" : small.toPlainString() + "% (14 rows) to " + big.toPlainString() + "% (8 rows)";
    }

    /** Total return to player of a table with these bonuses, in percent. */
    public static BigDecimal totalRtp(OddsTable table, BonusSettings bonus) {
        return table.rtp().multiply(factor(bonus, table)).round(MathContext.DECIMAL64);
    }

    public BigDecimal totalRtp(OddsTable table) {
        return totalRtp(table, current());
    }

    /** The biggest golden boost a ball can get (1 when golden balls are off). */
    public static BigDecimal maxGoldenBoost(BonusSettings bonus) {
        return bonus.goldenOn()
                ? bonus.golden().boosts().stream().map(BonusSettings.Boost::multiplier).max(BigDecimal::compareTo).orElse(BigDecimal.ONE)
                : BigDecimal.ONE;
    }

    /** The slot (0..rows) that is hot in a new round, or -1 when this round has none. */
    public int rollHot(BonusSettings bonus, int rows) {
        if (!bonus.hotOn() || random.nextDouble() * 100 >= bonus.hot().chance().doubleValue()) {
            return -1;
        }
        return random.nextInt(PlinkoBoard.slotCount(rows));
    }

    /** Draws whether one ball is golden, and its boost: 1 for a normal ball. */
    public BigDecimal rollGolden(BonusSettings bonus) {
        if (!bonus.goldenOn() || bonus.golden().chance().signum() == 0) {
            return BigDecimal.ONE;
        }
        if (random.nextDouble() * 100 >= bonus.golden().chance().doubleValue()) {
            return BigDecimal.ONE;
        }
        double pick = random.nextDouble() * 100;
        double cumulative = 0;
        List<BonusSettings.Boost> boosts = bonus.golden().boosts();
        for (BonusSettings.Boost boost : boosts) {
            cumulative += boost.weight().doubleValue();
            if (pick < cumulative) {
                return boost.multiplier();
            }
        }
        // rounding at the very end of the range: the smallest boost, never the biggest
        return boosts.stream().map(BonusSettings.Boost::multiplier).min(BigDecimal::compareTo).orElse(BigDecimal.ONE);
    }
}
