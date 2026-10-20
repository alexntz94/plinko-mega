package com.plinko.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The paying bonus features, as approved by two admins. Stored as JSON in the {@code setting} table (covered by the
 * settings ledger), so an edit made directly in the database stops the game.
 *
 * @param golden a ball can turn golden; its prize is then multiplied by a boost
 * @param hot    in some rounds one slot lights up and pays its prize times a boost for every ball that lands in it
 *               (null in settings saved before hot slots existed: off)
 * @param meter  balls landing on a big prize charge a meter that pays its pot as a MEGA SPIN of the gold wheel when
 *               full (null: off)
 * @param gates  Plinko Mega: a ball's prize is multiplied by the boost for every gate its path goes through (null: off)
 * @param wheel  Plinko Mega: a ball landing in a SPIN slot also spins the wheel, which pays its bet times the segment
 *               (null: off)
 * @param megaWheel the gold wheel a full meter spins: it pays the pot times the segment
 */
public record BonusSettings(Golden golden, Hot hot, Meter meter, Gates gates, Wheel wheel, Wheel megaWheel) {

    public BonusSettings(Golden golden, Hot hot) {
        this(golden, hot, null);
    }

    /** Without Plinko Mega's gates and wheel; a full meter spins the standard gold wheel. */
    public BonusSettings(Golden golden, Hot hot, Meter meter) {
        this(golden, hot, meter, null, null, GOLD_WHEEL);
    }

    /** One possible golden boost and how often it is drawn (weights are percentages and add up to 100). */
    public record Boost(BigDecimal multiplier, BigDecimal weight) {
    }

    /** chance: percent of balls that turn golden. */
    public record Golden(boolean enabled, BigDecimal chance, List<Boost> boosts) {
    }

    /** chance: percent of rounds with a hot slot; boost: what the hot slot's prize is multiplied by. */
    public record Hot(boolean enabled, BigDecimal chance, BigDecimal boost) {
    }

    /**
     * charges: how many charges fill the meter; minMultiplier: a paid ball landing on a prize of at least this
     * charges it; potShare: percent of that ball's bet put into the pot; freeBalls: how many free balls a full meter
     * pays, sharing the pot as their stake.
     */
    public record Meter(boolean enabled, int charges, BigDecimal minMultiplier, BigDecimal potShare, int freeBalls) {
    }

    /** boost: what every gate a ball goes through multiplies its prize by. */
    public record Gates(boolean enabled, BigDecimal boost) {
    }

    /**
     * One segment of a wheel: what it pays (times the bet, or the pot on the gold wheel) and how often it comes up
     * (weights are percentages and add up to 100); name: MINI, MAJOR or MEGA on a jackpot segment, else null.
     */
    public record Segment(String name, BigDecimal multiplier, BigDecimal weight) {
    }

    public record Wheel(boolean enabled, List<Segment> segments) {
    }

    /** The gold wheel a full meter spins (its own switch is the meter's). */
    private static final Wheel GOLD_WHEEL = new Wheel(false, wheel(
            "1", "25", "2", "25", "3", "20", "5", "15", "10", "9", "MAJOR:25", "5", "MEGA:100", "1"));

    /** Everything off; the values are the suggested starting point when an admin switches a feature on. */
    public static final BonusSettings OFF = new BonusSettings(new Golden(false, new BigDecimal("1"), List.of(
            new Boost(new BigDecimal("2"), new BigDecimal("60")),
            new Boost(new BigDecimal("3"), new BigDecimal("25")),
            new Boost(new BigDecimal("5"), new BigDecimal("12")),
            new Boost(new BigDecimal("10"), new BigDecimal("3")))),
            new Hot(false, new BigDecimal("20"), new BigDecimal("3")),
            new Meter(false, 10, new BigDecimal("2"), new BigDecimal("30"), 10),
            new Gates(false, new BigDecimal("2")),
            new Wheel(false, wheel(
                    "1", "28", "2", "26", "3", "18", "5", "13", "10", "8", "25", "4", "MINI:50", "2", "MAJOR:250", "0.75", "MEGA:1000", "0.25")),
            GOLD_WHEEL);

    /** Plinko Mega's starting point before an admin has saved anything: gates, the wheel and the meter's MEGA SPIN on. */
    public static final BonusSettings MEGA = new BonusSettings(OFF.golden(), OFF.hot(),
            new Meter(true, 10, new BigDecimal("2"), new BigDecimal("10"), 1),
            new Gates(true, OFF.gates().boost()), new Wheel(true, OFF.wheel().segments()), new Wheel(true, OFF.megaWheel().segments()));

    /** Segments from pairs of ("[NAME:]multiplier", "weight"). */
    private static List<Segment> wheel(String... pairs) {
        List<Segment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String[] named = pairs[i].split(":");
            segments.add(new Segment(named.length == 2 ? named[0] : null, new BigDecimal(named[named.length - 1]), new BigDecimal(pairs[i + 1])));
        }
        return List.copyOf(segments);
    }

    public boolean goldenOn() {
        return golden != null && golden.enabled();
    }

    public boolean hotOn() {
        return hot != null && hot.enabled();
    }

    public boolean meterOn() {
        return meter != null && meter.enabled();
    }

    public boolean gatesOn() {
        return gates != null && gates.enabled();
    }

    public boolean wheelOn() {
        return wheel != null && wheel.enabled();
    }

    public boolean anyOn() {
        return goldenOn() || hotOn() || meterOn() || gatesOn() || wheelOn();
    }
}
