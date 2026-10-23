package com.plinko.bo.bonus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.plinko.bo.odds.MegaBoard;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.settings.SettingsService;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Plinko Mega's paying features: their approved settings, what they add to the return to player, and the server-side
 * draws.
 * <p>
 * The maths: a ball through g gates pays its prize times boost^g; every path to a slot is equally likely, so the gates
 * multiply a slot's prize by a known average (see {@link MegaBoard#gateFactor}) and the table's return by the
 * probability-weighted mean of those. A ball in a SPIN slot also spins the wheel, which pays that ball's win again times
 * the segment: it adds {@code P(SPIN slot) * prize * gates * MEGA BALL factor * E[wheel]} (the wheel is drawn apart
 * from the ball, so the averages multiply). The bonus meter takes a share s of the bet of every ball that lands on a prize of
 * at least the minimum (probability f on that table) into a pot of money, and a full meter pays it as one MEGA SPIN of
 * the gold wheel, worth pot * segment: every ball adds {@code f * s * E[gold wheel]}, whatever the table it is later
 * paid on. (A pot a player never fills is never paid, so over time players get very slightly less than the total,
 * never more.) A share c of balls drop as a MEGA BALL, which multiplies that ball's prize by a drawn m: the balls'
 * prizes are worth {@code 1 + c * (E[m] - 1)} times as much. Total RTP = table RTP * gate factor * MEGA BALL factor +
 * what the wheels add; the hard RTP limits apply to the total, and base tables are rebuilt so the total stays on
 * target.
 */
@Service
public class BonusService {

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
            return BonusSettings.MEGA;
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
        return new BonusSettings(bonus.meter() == null ? BonusSettings.OFF.meter() : bonus.meter(),
                bonus.gates() == null ? BonusSettings.OFF.gates() : bonus.gates(),
                bonus.wheel() == null ? BonusSettings.OFF.wheel() : bonus.wheel(),
                bonus.megaWheel() == null ? BonusSettings.OFF.megaWheel() : bonus.megaWheel(),
                bonus.megaBall() == null ? BonusSettings.OFF.megaBall() : bonus.megaBall(),
                bonus.arsenal() == null ? BonusSettings.OFF.arsenal() : bonus.arsenal());
    }

    public String toJson(BonusSettings bonus) {
        return json.writeValueAsString(bonus);
    }

    public List<String> validate(BonusSettings bonus) {
        List<String> errors = new ArrayList<>();
        if (bonus == null) {
            errors.add("Bonus settings are incomplete");
            return errors;
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
        }
        BonusSettings.Gates gates = bonus.gates();
        if (gates != null && (gates.boost() == null || gates.boost().compareTo(BigDecimal.ONE) <= 0
                || gates.boost().compareTo(BigDecimal.TEN) > 0 || gates.boost().stripTrailingZeros().scale() > 2)) {
            errors.add("The gate boost must be above 1x and at most 10x, with at most 2 decimals");
        }
        if (bonus.wheel() != null) {
            wheelErrors("The wheel", bonus.wheel(), errors);
        }
        if (bonus.megaWheel() != null) {
            wheelErrors("The gold wheel", bonus.megaWheel(), errors);
        }
        BonusSettings.MegaBall megaBall = bonus.megaBall();
        if (megaBall != null) {
            if (megaBall.chance() == null || megaBall.chance().signum() <= 0 || megaBall.chance().compareTo(BigDecimal.valueOf(50)) > 0
                    || megaBall.chance().stripTrailingZeros().scale() > 2) {
                errors.add("The share of MEGA BALLs must be above 0% and at most 50%, with at most 2 decimals");
            }
            megaBallErrors(megaBall, errors);
        }
        return errors;
    }

    private static void megaBallErrors(BonusSettings.MegaBall megaBall, List<String> errors) {
        List<BonusSettings.Segment> multipliers = megaBall.multipliers();
        if (multipliers == null || multipliers.isEmpty() || multipliers.size() > 10) {
            errors.add("MEGA BALLs need 1 to 10 multipliers");
            return;
        }
        BigDecimal weights = BigDecimal.ZERO;
        for (BonusSettings.Segment m : multipliers) {
            if (m == null || m.multiplier() == null || m.weight() == null) {
                errors.add("MEGA BALLs: every multiplier needs a value and a weight");
                return;
            }
            if (m.multiplier().compareTo(BigDecimal.ONE) <= 0 || m.multiplier().compareTo(BigDecimal.valueOf(100)) > 0
                    || m.multiplier().stripTrailingZeros().scale() > 2) {
                errors.add("MEGA BALLs multiply by above 1x and at most 100x, with at most 2 decimals");
            }
            if (m.weight().signum() <= 0 || m.weight().stripTrailingZeros().scale() > 2) {
                errors.add("MEGA BALLs: weights must be above 0, with at most 2 decimals");
            }
            weights = weights.add(m.weight());
        }
        if (weights.compareTo(OddsTable.HUNDRED) != 0) {
            errors.add("MEGA BALLs: weights must add up to 100% (now " + weights.stripTrailingZeros().toPlainString() + "%)");
        }
    }

    private static final java.util.Set<String> JACKPOTS = java.util.Set.of("MINI", "MAJOR", "MEGA");

    private static void wheelErrors(String what, BonusSettings.Wheel wheel, List<String> errors) {
        List<BonusSettings.Segment> segments = wheel.segments();
        if (segments == null || segments.size() < 4 || segments.size() > 16) {
            errors.add(what + " needs 4 to 16 segments");
            return;
        }
        BigDecimal weights = BigDecimal.ZERO;
        for (BonusSettings.Segment segment : segments) {
            if (segment == null || segment.multiplier() == null || segment.weight() == null) {
                errors.add(what + ": every segment needs a multiplier and a weight");
                return;
            }
            if (segment.multiplier().signum() <= 0 || segment.multiplier().compareTo(BigDecimal.valueOf(1000)) > 0
                    || segment.multiplier().stripTrailingZeros().scale() > 2) {
                errors.add(what + ": segments pay above 0x and at most 1000x, with at most 2 decimals");
            }
            if (segment.weight().signum() <= 0 || segment.weight().stripTrailingZeros().scale() > 2) {
                errors.add(what + ": segment weights must be above 0, with at most 2 decimals");
            }
            if (segment.name() != null && !JACKPOTS.contains(segment.name())) {
                errors.add(what + ": a jackpot segment is MINI, MAJOR or MEGA");
            }
            weights = weights.add(segment.weight());
        }
        if (weights.compareTo(OddsTable.HUNDRED) != 0) {
            errors.add(what + ": segment weights must add up to 100% (now " + weights.stripTrailingZeros().toPlainString() + "%)");
        }
    }

    /** What a spin of this wheel pays on average, times its stake. */
    public static BigDecimal expected(BonusSettings.Wheel wheel) {
        return wheel.segments().stream().map(s -> s.multiplier().multiply(s.weight())).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
    }

    /** What a MEGA BALL multiplies by on average. */
    public static BigDecimal expected(BonusSettings.MegaBall megaBall) {
        return megaBall.multipliers().stream().map(s -> s.multiplier().multiply(s.weight())).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
    }

    /** How much MEGA BALLs multiply what the balls' prizes are worth: 1 + share * (E[m] - 1) (1 when off). */
    public static BigDecimal megaBallFactor(BonusSettings bonus) {
        if (!bonus.megaBallOn()) {
            return BigDecimal.ONE;
        }
        BigDecimal share = bonus.megaBall().chance().divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
        return BigDecimal.ONE.add(share.multiply(expected(bonus.megaBall()).subtract(BigDecimal.ONE))).round(MathContext.DECIMAL64);
    }

    /**
     * How much the gates and MEGA BALLs multiply this table's own return: each slot's prize times the average gate
     * boost of a ball landing there, weighted by how often balls land there, times the MEGA BALL factor (1 when both
     * are off).
     */
    public static BigDecimal gateFactor(BonusSettings bonus, OddsTable table) {
        return gatesOnly(bonus, table).multiply(megaBallFactor(bonus), MathContext.DECIMAL64);
    }

    private static BigDecimal gatesOnly(BonusSettings bonus, OddsTable table) {
        if (!bonus.gatesOn()) {
            return BigDecimal.ONE;
        }
        double boost = bonus.gates().boost().doubleValue();
        double plain = 0, gated = 0;
        for (int slot = 0; slot <= table.rows(); slot++) {
            int index = PlinkoBoard.prizeIndexOfSlot(table.rows(), slot);
            OddsTable.Prize prize = table.prizes().get(index);
            int slotsOfPrize = PlinkoBoard.leftSlot(table.rows(), index) == PlinkoBoard.rightSlot(table.rows(), index) ? 1 : 2;
            double value = prize.probability().doubleValue() / slotsOfPrize * prize.multiplier().doubleValue();
            plain += value;
            gated += value * MegaBoard.gateFactor(table.rows(), slot, boost);
        }
        return plain == 0 ? BigDecimal.ONE : new BigDecimal(gated / plain, MathContext.DECIMAL64);
    }

    /** What the wheel and the gold wheel add on this table, per unit of bet (0.05 = 5% RTP), on top of the balls' prizes. */
    public static BigDecimal extra(BonusSettings bonus, OddsTable table) {
        BigDecimal extra = BigDecimal.ZERO;
        if (bonus.wheelOn()) {
            int rows = table.rows();
            int index = MegaBoard.spinPrizeIndex(rows);
            OddsTable.Prize prize = table.prizes().get(index);
            BigDecimal chance = prize.probability().divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
            // what a ball in a SPIN slot wins on average: its prize, times its gates (both SPIN slots alike), times MEGA BALLs
            double gates = bonus.gatesOn() ? (MegaBoard.gateFactor(rows, PlinkoBoard.leftSlot(rows, index), bonus.gates().boost().doubleValue())
                    + MegaBoard.gateFactor(rows, PlinkoBoard.rightSlot(rows, index), bonus.gates().boost().doubleValue())) / 2 : 1;
            BigDecimal ballWin = prize.multiplier().multiply(BigDecimal.valueOf(gates)).multiply(megaBallFactor(bonus));
            extra = extra.add(chance.multiply(ballWin).multiply(expected(bonus.wheel())));
        }
        if (bonus.meterOn()) {
            BigDecimal share = bonus.meter().potShare().divide(OddsTable.HUNDRED, MathContext.DECIMAL64);
            extra = extra.add(meterFill(bonus, table).multiply(share).multiply(expected(bonus.megaWheel())));
        }
        return extra.round(MathContext.DECIMAL64);
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

    /** What one charging paid ball with this bet adds to the pot (money, 6 decimals, rounded down). */
    public static BigDecimal potCharge(BonusSettings bonus, BigDecimal bet) {
        return bet.multiply(bonus.meter().potShare()).divide(OddsTable.HUNDRED, 6, RoundingMode.DOWN);
    }

    /**
     * What one full meter's MEGA SPIN is played for, when the player has {@code charges} charges (at least one full
     * meter): its own share of the pot, in whole cents rounded down (the fractions stay in the pot).
     */
    public static BigDecimal megaStake(BonusSettings bonus, BigDecimal pot, int charges) {
        return pot.multiply(BigDecimal.valueOf(bonus.meter().charges())).divide(BigDecimal.valueOf(Math.max(charges, 1)), 2, RoundingMode.DOWN);
    }

    /** Total RTP of a table as a multiple of its own RTP: everything the bonuses and Plinko Mega's features add. */
    public static BigDecimal totalFactor(BonusSettings bonus, OddsTable table) {
        if (table.rtp().signum() == 0) {
            return BigDecimal.ONE;
        }
        return totalRtp(table, bonus).divide(table.rtp(), MathContext.DECIMAL64);
    }

    /** "+x%" for these tables: the range from the table where the features add least to the one where they add most. */
    public static String shareText(BonusSettings bonus, java.util.Collection<OddsTable> tables) {
        if (tables.isEmpty()) {
            return "0%";
        }
        List<BigDecimal> shares = tables.stream()
                .map(t -> totalFactor(bonus, t).subtract(BigDecimal.ONE).movePointRight(2).setScale(2, RoundingMode.HALF_UP)).sorted().toList();
        BigDecimal low = shares.get(0);
        BigDecimal high = shares.get(shares.size() - 1);
        return low.compareTo(high) == 0 ? high.toPlainString() + "%" : low.toPlainString() + "% to " + high.toPlainString() + "% depending on the table";
    }

    /** Total return to player of a table with these bonuses, in percent. */
    public static BigDecimal totalRtp(OddsTable table, BonusSettings bonus) {
        return table.rtp().multiply(gateFactor(bonus, table))
                .add(extra(bonus, table).multiply(OddsTable.HUNDRED)).round(MathContext.DECIMAL64);
    }

    public BigDecimal totalRtp(OddsTable table) {
        return totalRtp(table, current());
    }

    /**
     * The path of a ball that lands in this slot: one of the paths to it, every one equally likely (the slot's own
     * chance comes from the odds table; only the way there is drawn here).
     */
    public int rollPath(int rows, int slot) {
        int path = 0;
        int rightsLeft = slot;
        for (int step = 0; step < rows; step++) {
            // right with the chance that a uniformly drawn arrangement of the remaining steps starts with a right
            if (random.nextInt(rows - step) < rightsLeft) {
                path |= 1 << step;
                rightsLeft--;
            }
        }
        return path;
    }

    /**
     * A path to this slot that falls straight through every hole (one right and one left there), every such path
     * equally likely; when no path to the slot can do that, any path (the peg is back for this ball).
     */
    public int rollPath(int rows, int slot, java.util.Set<Integer> holes) {
        // ways[row][rights]: paths from that peg to the slot that respect the holes
        double[][] ways = new double[rows + 1][rows + 2];
        ways[rows][slot] = 1;
        for (int row = rows - 1; row >= 0; row--) {
            for (int k = 0; k <= row; k++) {
                ways[row][k] = holes.contains(MegaBoard.node(row, k))
                        ? (row + 2 <= rows ? 2 * ways[row + 2][k + 1] : 0)
                        : ways[row + 1][k] + ways[row + 1][k + 1];
            }
        }
        if (ways[0][0] == 0) {
            return rollPath(rows, slot);
        }
        int path = 0, k = 0;
        for (int row = 0; row < rows; ) {
            if (holes.contains(MegaBoard.node(row, k))) {
                // straight down through the hole: right then left, or left then right
                path |= random.nextBoolean() ? 1 << row : 1 << (row + 1);
                k++;
                row += 2;
            } else {
                boolean right = random.nextDouble() * ways[row][k] < ways[row + 1][k + 1];
                if (right) {
                    path |= 1 << row;
                    k++;
                }
                row++;
            }
        }
        return path;
    }

    /** One of `count` gate layouts, every one equally likely. */
    public int rollLayout(int count) {
        return random.nextInt(count);
    }

    /** The power-ups a round can be bought with. */
    public static final java.util.List<String> POWERS = java.util.List.of("heavy", "cluster", "strike-left", "strike-centre", "strike-right",
            "crates", "missile", "night", "tank", "chain", "magnet-left", "magnet-right", "meltdown", "pair");

    /**
     * The power-ups each theme sells (Reactor Drop's Overcharge and Lagoon Drop's Sunburst Surge are heavy under their
     * own names; Lagoon Drop's Crosscurrent is the magnet, its Volcano Vault the meltdown; Pharaoh's Drop's Scarab
     * Fortune, Twin Serpents, Sirocco Draft and Tomb Raid, and Zombie War's Mega Charge, Split Fuse, Horde Rush and Locker
     * Raid, and Neon Nights' Golden Gacha, Twin Pop, Side Magnet and Lucky Draw, and Candy Factory's Golden Glaze, Double
     * Dip, Candy Current and Sugar Rush, are heavy, pair, the magnet and the meltdown).
     */
    public static final java.util.Map<String, java.util.List<String>> THEME_POWERS = java.util.Map.of(
            "bombs-away", java.util.List.of("heavy", "cluster", "strike-left", "strike-centre", "strike-right", "crates", "missile", "night", "tank"),
            "reactor-drop", java.util.List.of("heavy", "chain", "magnet-left", "magnet-right", "meltdown"),
            "lagoon-drop", java.util.List.of("heavy", "pair", "magnet-left", "magnet-right", "meltdown"),
            "pharaoh-drop", java.util.List.of("heavy", "pair", "magnet-left", "magnet-right", "meltdown"),
            "zombie-war", java.util.List.of("heavy", "pair", "magnet-left", "magnet-right", "meltdown"),
            "neon-nights", java.util.List.of("heavy", "pair", "magnet-left", "magnet-right", "meltdown"),
            "candy-factory", java.util.List.of("heavy", "pair", "magnet-left", "magnet-right", "meltdown"));

    /** How many times a ball with this power-up lands, keeping its best landing: cluster 3, pair 2, else 1. */
    public static int landings(String power) {
        return "cluster".equals(power) ? 3 : "pair".equals(power) ? 2 : 1;
    }

    /** Whether a theme sells this power-up (a theme not listed sells none). */
    public static boolean themeSells(String theme, String power) {
        return THEME_POWERS.getOrDefault(theme, java.util.List.of()).contains(power);
    }

    /** Magnetic field: the share of balls pulled to the chosen half, and what that half's slots pay. */
    public static final double MAGNET_PULL = 0.7;
    public static final BigDecimal MAGNET_BOOST = new BigDecimal("1.5");

    /** The chance a ball falls to the left (0.5, or the magnet's pull). */
    public static double leftChance(String power) {
        return "magnet-left".equals(power) ? MAGNET_PULL : "magnet-right".equals(power) ? 1 - MAGNET_PULL : 0.5;
    }

    /** Core meltdown: its one payout after the balls, x the round's plain bet (weights add up to 100). */
    public static final java.util.List<BonusSettings.Segment> MELTDOWN = java.util.List.of(
            new BonusSettings.Segment(null, new BigDecimal("0.5"), new BigDecimal("30")),
            new BonusSettings.Segment(null, new BigDecimal("1"), new BigDecimal("25")),
            new BonusSettings.Segment(null, new BigDecimal("2"), new BigDecimal("20")),
            new BonusSettings.Segment(null, new BigDecimal("3"), new BigDecimal("12")),
            new BonusSettings.Segment(null, new BigDecimal("5"), new BigDecimal("8")),
            new BonusSettings.Segment(null, new BigDecimal("10"), new BigDecimal("4")),
            new BonusSettings.Segment(null, new BigDecimal("20"), new BigDecimal("1")));

    /** The payouts a power-up adds after the balls: how many, and from which table (none: 0). */
    public static int targetCount(String power) {
        return "tank".equals(power) ? TANK_TARGETS : "meltdown".equals(power) ? 1 : 0;
    }

    public static java.util.List<BonusSettings.Segment> targetTable(String power) {
        return "meltdown".equals(power) ? MELTDOWN : TANK;
    }

    /**
     * What a ball landing in this slot is paid before its boosts: the slot's prize; with chain lightning also half of
     * each neighbouring slot's prize (an edge slot has one neighbour). prizes: by prize position.
     */
    public static BigDecimal landingPrize(String power, int rows, java.util.List<BigDecimal> prizes, int slot) {
        BigDecimal prize = prizes.get(PlinkoBoard.prizeIndexOfSlot(rows, slot));
        if (!"chain".equals(power)) {
            return prize;
        }
        BigDecimal half = new BigDecimal("0.5");
        if (slot > 0) {
            prize = prize.add(prizes.get(PlinkoBoard.prizeIndexOfSlot(rows, slot - 1)).multiply(half));
        }
        if (slot < rows) {
            prize = prize.add(prizes.get(PlinkoBoard.prizeIndexOfSlot(rows, slot + 1)).multiply(half));
        }
        return prize;
    }

    /** Tank assault: how many targets the tank fires at, and what each pays (x the round's plain bet; weights add up to 100). */
    public static final int TANK_TARGETS = 5;
    public static final java.util.List<BonusSettings.Segment> TANK = java.util.List.of(
            new BonusSettings.Segment(null, new BigDecimal("0.1"), new BigDecimal("30")),
            new BonusSettings.Segment(null, new BigDecimal("0.2"), new BigDecimal("25")),
            new BonusSettings.Segment(null, new BigDecimal("0.5"), new BigDecimal("20")),
            new BonusSettings.Segment(null, new BigDecimal("1"), new BigDecimal("14")),
            new BonusSettings.Segment(null, new BigDecimal("2"), new BigDecimal("7")),
            new BonusSettings.Segment(null, new BigDecimal("5"), new BigDecimal("3")),
            new BonusSettings.Segment(null, new BigDecimal("10"), new BigDecimal("1")));

    /** One payout after the balls (a tank target, the meltdown): the index of what it pays in its table. */
    public int rollTarget(java.util.List<BonusSettings.Segment> table) {
        double pick = random.nextDouble() * 100, cumulative = 0;
        for (int i = 0; i < table.size(); i++) {
            cumulative += table.get(i).weight().doubleValue();
            if (pick < cumulative) {
                return i;
            }
        }
        return 0;
    }

    /** Chain reaction: the share of balls that hit an ammo crate, and what a crate multiplies by (weights add up to 100). */
    public static final BigDecimal CRATE_CHANCE = new BigDecimal("20");
    public static final java.util.List<BonusSettings.Segment> CRATES = java.util.List.of(
            new BonusSettings.Segment(null, new BigDecimal("2"), new BigDecimal("60")),
            new BonusSettings.Segment(null, new BigDecimal("3"), new BigDecimal("30")),
            new BonusSettings.Segment(null, new BigDecimal("5"), new BigDecimal("10")));

    /**
     * What a power-up multiplies the prize of a ball landing in this slot by (1 for the others and for the power-ups
     * that don't work by slot). strike-left/centre/right: the slots of that third of the board x2; missile: the two
     * outermost prize positions x3; night: the positions whose standard prize is 2x or more x3, under 1x x0.6.
     */
    public static BigDecimal slotFactor(String power, int rows, int risk, int slot) {
        if (power == null) {
            return BigDecimal.ONE;
        }
        int third = Math.round((rows + 1) / 3f);
        int index = PlinkoBoard.prizeIndexOfSlot(rows, slot);
        return switch (power) {
            case "strike-left" -> slot < third ? BigDecimal.TWO : BigDecimal.ONE;
            case "strike-right" -> slot > rows - third ? BigDecimal.TWO : BigDecimal.ONE;
            case "strike-centre" -> slot >= third && slot <= rows - third ? BigDecimal.TWO : BigDecimal.ONE;
            case "missile" -> index >= PlinkoBoard.prizeCount(rows) - 2 ? BigDecimal.valueOf(3) : BigDecimal.ONE;
            case "magnet-left" -> slot < rows / 2.0 ? MAGNET_BOOST : BigDecimal.ONE;
            case "magnet-right" -> slot > rows / 2.0 ? MAGNET_BOOST : BigDecimal.ONE;
            case "night" -> {
                double[][] shape = com.plinko.bo.odds.OddsDefaults.MULTIPLIERS.get(rows);
                double standard = shape == null ? 1 : shape[index][risk];
                yield standard >= 2 ? BigDecimal.valueOf(3) : standard < 1 ? new BigDecimal("0.6") : BigDecimal.ONE;
            }
            default -> BigDecimal.ONE;
        };
    }

    /** The biggest slot factor or crate a power-up can give a ball (for the win limit). */
    public static BigDecimal maxFactor(String power, BonusSettings bonus) {
        if (power == null) {
            return BigDecimal.ONE;
        }
        return switch (power) {
            case "heavy" -> bonus.megaBall().multipliers().stream().map(BonusSettings.Segment::multiplier).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            case "crates" -> BigDecimal.valueOf(5);
            case "missile", "night" -> BigDecimal.valueOf(3);
            case "strike-left", "strike-centre", "strike-right", "chain" -> BigDecimal.TWO;
            case "magnet-left", "magnet-right" -> MAGNET_BOOST;
            default -> BigDecimal.ONE;
        };
    }

    /** What a crate multiplies by on average. */
    static double crateExpected() {
        double sum = 0;
        for (BonusSettings.Segment s : CRATES) {
            sum += s.multiplier().doubleValue() * s.weight().doubleValue() / 100;
        }
        return sum;
    }

    /**
     * What each prize position of this table is worth to one ball per unit of bet, all features included (its prize
     * times the average gate boost of its two slots times the MEGA BALL factor, again times the wheel when it is a SPIN
     * position, plus what it puts towards a MEGA SPIN): the table's total RTP is the sum of these times their chances.
     * megaBall: the MEGA BALL factor to use (1 + share * (E - 1) normally; E for a ball that is always one).
     */
    static double[] positionValues(BonusSettings bonus, OddsTable table, double megaBall) {
        double[] none = new double[table.prizes().size()];
        java.util.Arrays.fill(none, 1);
        return positionValues(bonus, table, megaBall, none);
    }

    /** The same, each position's prize (and its wheel spin) also multiplied by factor[i]. */
    static double[] positionValues(BonusSettings bonus, OddsTable table, double megaBall, double[] factor) {
        int rows = table.rows(), n = table.prizes().size();
        double boost = bonus.gatesOn() ? bonus.gates().boost().doubleValue() : 1;
        double wheel = bonus.wheelOn() ? expected(bonus.wheel()).doubleValue() : 0;
        double gold = bonus.meterOn() ? bonus.meter().potShare().doubleValue() / 100 * expected(bonus.megaWheel()).doubleValue() : 0;
        double[] value = new double[n];
        for (int i = 0; i < n; i++) {
            int left = PlinkoBoard.leftSlot(rows, i), right = PlinkoBoard.rightSlot(rows, i);
            double gates = bonus.gatesOn() ? (MegaBoard.gateFactor(rows, left, boost) + MegaBoard.gateFactor(rows, right, boost)) / 2 : 1;
            value[i] = table.prizes().get(i).multiplier().doubleValue() * gates * megaBall * factor[i]
                    * (1 + (bonus.wheelOn() && i == MegaBoard.spinPrizeIndex(rows) ? wheel : 0))
                    + (chargesMeter(bonus, rows, table.risk(), i) ? gold : 0);
        }
        return value;
    }

    /**
     * What a ball with this power-up costs, as a multiple of the bet, on this table: what the power-up's ball is worth
     * over what a plain ball is worth, rounded up to the cent (so a bought round returns at most the target RTP). heavy:
     * the MEGA BALL is certain; cluster (pair): the ball lands three (two) times and is paid on the landing with the best
     * prize (the prize positions ordered by multiplier, then by position), so its position is the best of the draws.
     */
    public static BigDecimal powerPrice(BonusSettings bonus, OddsTable table, String power) {
        int n = table.prizes().size();
        double plainBall = bonus.megaBallOn() ? megaBallFactor(bonus).doubleValue() : 1;
        double[] plain = positionValues(bonus, table, plainBall);
        double[] p = new double[n];
        double normal = 0;
        for (int i = 0; i < n; i++) {
            p[i] = table.prizes().get(i).probability().doubleValue() / 100;
            normal += p[i] * plain[i];
        }
        double powered = 0;
        if ("heavy".equals(power)) {
            double[] heavy = positionValues(bonus, table, expected(bonus.megaBall()).doubleValue());
            for (int i = 0; i < n; i++) {
                powered += p[i] * heavy[i];
            }
        } else if (landings(power) > 1) {
            Integer[] order = clusterOrder(table);
            int draws = landings(power);
            double below = 0;
            for (int k = 0; k < n; k++) {
                int i = order[k];
                double upTo = below + p[i];
                powered += (Math.pow(upTo, draws) - Math.pow(below, draws)) * plain[i];
                below = upTo;
            }
        } else if (targetCount(power) > 0) {
            // the balls as usual, and on top the payouts after them (tank targets, the meltdown): each pays x the
            // round's plain bet, so per ball x1
            double target = 0;
            for (BonusSettings.Segment s : targetTable(power)) {
                target += s.multiplier().doubleValue() * s.weight().doubleValue() / 100;
            }
            powered = normal + targetCount(power) * target;
        } else if (POWERS.contains(power)) {
            // by slot (airstrike, missile, night, magnet: each position's two slots by the chance of falling to that
            // side); chain: the slot's prize and half its neighbours'; crates: every ball alike
            int rows = table.rows();
            double left = leftChance(power);
            java.util.List<BigDecimal> prizes = table.prizes().stream().map(OddsTable.Prize::multiplier).toList();
            double[] factor = new double[n];
            for (int i = 0; i < n; i++) {
                int l = PlinkoBoard.leftSlot(rows, i), r = PlinkoBoard.rightSlot(rows, i);
                double prize = prizes.get(i).doubleValue();
                factor[i] = "crates".equals(power) ? 1 + CRATE_CHANCE.doubleValue() / 100 * (crateExpected() - 1)
                        : "chain".equals(power) ? (landingPrize(power, rows, prizes, l).doubleValue() + landingPrize(power, rows, prizes, r).doubleValue()) / 2 / prize
                        : left * slotFactor(power, rows, table.risk(), l).doubleValue() + (1 - left) * slotFactor(power, rows, table.risk(), r).doubleValue();
            }
            double[] boosted = positionValues(bonus, table, plainBall, factor);
            for (int i = 0; i < n; i++) {
                powered += p[i] * boosted[i];
            }
        } else {
            throw new IllegalArgumentException("unknown power-up " + power);
        }
        return BigDecimal.valueOf(powered / normal).setScale(2, RoundingMode.CEILING);
    }

    /** The prize positions from the worst to the best landing for a cluster shell (or pair): by prize, then by position. */
    public static Integer[] clusterOrder(OddsTable table) {
        Integer[] order = new Integer[table.prizes().size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.<Integer, BigDecimal>comparing(i -> table.prizes().get(i).multiplier())
                .thenComparing(i -> i));
        return order;
    }

    /** What a round with this power-up costs: bet x balls x the power's price, rounded up to the cent. */
    public static BigDecimal powerCost(BigDecimal bet, int balls, BigDecimal price) {
        return bet.multiply(BigDecimal.valueOf(balls)).multiply(price).setScale(2, RoundingMode.CEILING);
    }

    /** Whether a ball hits an ammo crate (chain reaction): the index of its multiplier in CRATES, or -1. */
    public int rollCrate() {
        if (random.nextDouble() * 100 >= CRATE_CHANCE.doubleValue()) {
            return -1;
        }
        double pick = random.nextDouble() * 100, cumulative = 0;
        for (int i = 0; i < CRATES.size(); i++) {
            cumulative += CRATES.get(i).weight().doubleValue();
            if (pick < cumulative) {
                return i;
            }
        }
        return 0;
    }

    /** Whether a ball drops as a MEGA BALL, and its multiplier's index if so (-1: a plain ball). */
    public int rollMegaBall(BonusSettings.MegaBall megaBall) {
        if (random.nextDouble() * 100 >= megaBall.chance().doubleValue()) {
            return -1;
        }
        double pick = random.nextDouble() * 100;
        double cumulative = 0;
        List<BonusSettings.Segment> multipliers = megaBall.multipliers();
        for (int i = 0; i < multipliers.size(); i++) {
            cumulative += multipliers.get(i).weight().doubleValue();
            if (pick < cumulative) {
                return i;
            }
        }
        return 0;
    }

    /** Draws a segment of a wheel by its weights; returns its index. */
    public int rollSegment(BonusSettings.Wheel wheel) {
        double pick = random.nextDouble() * 100;
        double cumulative = 0;
        List<BonusSettings.Segment> segments = wheel.segments();
        for (int i = 0; i < segments.size(); i++) {
            cumulative += segments.get(i).weight().doubleValue();
            if (pick < cumulative) {
                return i;
            }
        }
        // rounding at the very end of the range: the segment that pays least, never a jackpot
        int least = 0;
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).multiplier().compareTo(segments.get(least).multiplier()) < 0) {
                least = i;
            }
        }
        return least;
    }
}
