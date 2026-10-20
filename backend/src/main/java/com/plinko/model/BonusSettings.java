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
 * @param meter  balls landing on a big prize charge a meter that pays out free balls when full (null: off)
 */
public record BonusSettings(Golden golden, Hot hot, Meter meter) {

    public BonusSettings(Golden golden, Hot hot) {
        this(golden, hot, null);
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

    /** Everything off; the values are the suggested starting point when an admin switches a feature on. */
    public static final BonusSettings OFF = new BonusSettings(new Golden(false, new BigDecimal("1"), List.of(
            new Boost(new BigDecimal("2"), new BigDecimal("60")),
            new Boost(new BigDecimal("3"), new BigDecimal("25")),
            new Boost(new BigDecimal("5"), new BigDecimal("12")),
            new Boost(new BigDecimal("10"), new BigDecimal("3")))),
            new Hot(false, new BigDecimal("20"), new BigDecimal("3")),
            new Meter(false, 10, new BigDecimal("2"), new BigDecimal("30"), 10));

    public boolean goldenOn() {
        return golden != null && golden.enabled();
    }

    public boolean hotOn() {
        return hot != null && hot.enabled();
    }

    public boolean meterOn() {
        return meter != null && meter.enabled();
    }

    public boolean anyOn() {
        return goldenOn() || hotOn() || meterOn();
    }
}
