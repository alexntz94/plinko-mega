package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a round's win is worked out, in one place: {@link GameService} pays with it, {@link WinCheck} recomputes stored
 * rounds with it and the player's history replays them with it, so they can never drift apart. The stored outcome is
 * written by {@link Text} and read by {@link #parse}, side by side (review 7.8): the text is part of what a round's
 * signature covers, so its format never changes for rounds already played.
 * <p>
 * A stored round keeps its outcome as text (written in {@code GameService.playRound}): {@code slots} and
 * {@code multipliers} hold one entry per paid ball, and {@code bonus} the bonus parts, separated by ';':
 * <ul>
 * <li>{@code hot:<slot>x<boost>}: the round's hot slot;</li>
 * <li>{@code gold:<ball>x<boost>,...}: the paid balls that were golden;</li>
 * <li>{@code free:<stake>@<slot>x<multiplier>[g<boost>],...}: free balls paid by a full bonus meter;</li>
 * <li>{@code meter:...}: the meter state after the round (not part of the win);</li>
 * <li>{@code capped:<amount>}: the win before it was cut to the hard limit per round.</li>
 * </ul>
 */
public final class RoundWin {

    private RoundWin() {
    }

    /** What one ball pays: its stake times its multiplier, rounded down to the cent. */
    public static BigDecimal ball(BigDecimal stake, BigDecimal multiplier) {
        return stake.multiply(multiplier).setScale(2, RoundingMode.DOWN);
    }

    public record FreeBall(BigDecimal stake, int slot, BigDecimal multiplier, BigDecimal boost) {
    }

    /** What a round pays: {@code total}, and {@code capped}, what its balls added up to before the cut (null if not cut). */
    public record Paid(BigDecimal total, BigDecimal capped) {
    }

    /**
     * Review 7.8b: the balls' sum cut to the most a round may pay, bonuses included; in one place for the round that is
     * played and the check before it is paid.
     */
    public static Paid paid(BigDecimal sum, BigDecimal limit) {
        return sum.compareTo(limit) > 0 ? new Paid(limit, sum) : new Paid(sum, null);
    }

    /** The bonus meter after a round: charges of how many, the pot's value, and the state it continued from. */
    public record Meter(int charges, int of, BigDecimal pot, String prev) {
    }

    /**
     * Writes a round's bonus parts in the stored format, always in the same order (hot, gold, free, meter, capped),
     * whatever order they are added in; null when the round had none.
     */
    public static final class Text {
        private String hot;
        private final List<String> gold = new ArrayList<>();
        private final List<String> free = new ArrayList<>();
        private String meter;
        private String capped;

        public Text hot(int slot, BigDecimal boost) {
            hot = "hot:" + slot + "x" + boost.stripTrailingZeros().toPlainString();
            return this;
        }

        public Text gold(int ball, BigDecimal boost) {
            gold.add(ball + "x" + boost.stripTrailingZeros().toPlainString());
            return this;
        }

        public Text free(BigDecimal stake, int slot, BigDecimal multiplier, BigDecimal boost) {
            free.add(stake.toPlainString() + "@" + slot + "x" + multiplier.stripTrailingZeros().toPlainString()
                    + (boost.compareTo(BigDecimal.ONE) > 0 ? "g" + boost.stripTrailingZeros().toPlainString() : ""));
            return this;
        }

        /** The pot is stored with 6 decimals, rounded down. */
        public Text meter(int charges, int of, BigDecimal pot, String prev) {
            meter = "meter:" + charges + "/" + of + ":" + pot.setScale(6, RoundingMode.DOWN).toPlainString() + ":" + prev;
            return this;
        }

        public Text capped(BigDecimal before) {
            capped = "capped:" + before.toPlainString();
            return this;
        }

        public String text() {
            List<String> parts = new ArrayList<>();
            if (hot != null) {
                parts.add(hot);
            }
            if (!gold.isEmpty()) {
                parts.add("gold:" + String.join(",", gold));
            }
            if (!free.isEmpty()) {
                parts.add("free:" + String.join(",", free));
            }
            if (meter != null) {
                parts.add(meter);
            }
            if (capped != null) {
                parts.add(capped);
            }
            return parts.isEmpty() ? null : String.join(";", parts);
        }
    }

    /**
     * A stored outcome read back. golden: paid ball index to its boost; meter: the meter after the round (or null);
     * capped: the win before the cap (or null).
     */
    public record Outcome(List<Integer> slots, List<BigDecimal> multipliers, Integer hotSlot, BigDecimal hotBoost,
                          Map<Integer, BigDecimal> golden, List<FreeBall> free, Meter meter, BigDecimal capped) {

        /** The win before any cap: every paid ball at the bet, every free ball at its own stake. */
        public BigDecimal raw(BigDecimal bet) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal multiplier : multipliers) {
                sum = sum.add(ball(bet, multiplier));
            }
            for (FreeBall free : free) {
                sum = sum.add(ball(free.stake(), free.multiplier()));
            }
            return sum;
        }
    }

    /**
     * Reads a stored outcome. Throws IllegalArgumentException on anything that is not exactly in the format the game
     * writes (an unknown bonus part, a malformed number, slots and multipliers of different lengths).
     */
    public static Outcome parse(String slotText, String multiplierText, String bonus) {
        if (slotText == null || multiplierText == null || slotText.isEmpty() || multiplierText.isEmpty()) {
            throw new IllegalArgumentException("no outcome");
        }
        List<Integer> slots = new ArrayList<>();
        for (String slot : slotText.split(",", -1)) {
            slots.add(Integer.parseInt(slot));
        }
        List<BigDecimal> multipliers = new ArrayList<>();
        for (String multiplier : multiplierText.split(",", -1)) {
            multipliers.add(new BigDecimal(multiplier));
        }
        if (slots.size() != multipliers.size()) {
            throw new IllegalArgumentException(slots.size() + " slots but " + multipliers.size() + " multipliers");
        }
        Integer hotSlot = null;
        BigDecimal hotBoost = null;
        Map<Integer, BigDecimal> golden = new HashMap<>();
        List<FreeBall> free = new ArrayList<>();
        BigDecimal capped = null;
        Meter meter = null;
        if (bonus != null) {
            for (String part : bonus.split(";", -1)) {
                int colon = part.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException("bonus part without a name: " + part);
                }
                String value = part.substring(colon + 1);
                switch (part.substring(0, colon)) {
                    case "hot" -> {
                        String[] hot = value.split("x", -1);
                        if (hot.length != 2) {
                            throw new IllegalArgumentException("hot: " + value);
                        }
                        hotSlot = Integer.parseInt(hot[0]);
                        hotBoost = new BigDecimal(hot[1]);
                    }
                    case "gold" -> {
                        for (String entry : value.split(",", -1)) {
                            String[] gold = entry.split("x", -1);
                            if (gold.length != 2 || golden.put(Integer.parseInt(gold[0]), new BigDecimal(gold[1])) != null) {
                                throw new IllegalArgumentException("gold: " + entry);
                            }
                        }
                    }
                    case "free" -> {
                        for (String entry : value.split(",", -1)) {
                            String[] stakeAndRest = entry.split("@", -1);
                            String[] slotAndRest = stakeAndRest.length == 2 ? stakeAndRest[1].split("x", -1) : new String[0];
                            String[] multiplierAndBoost = slotAndRest.length == 2 ? slotAndRest[1].split("g", -1) : new String[0];
                            if (multiplierAndBoost.length < 1 || multiplierAndBoost.length > 2) {
                                throw new IllegalArgumentException("free: " + entry);
                            }
                            free.add(new FreeBall(new BigDecimal(stakeAndRest[0]), Integer.parseInt(slotAndRest[0]),
                                    new BigDecimal(multiplierAndBoost[0]),
                                    multiplierAndBoost.length == 2 ? new BigDecimal(multiplierAndBoost[1]) : BigDecimal.ONE));
                        }
                    }
                    case "meter" -> {
                        // the meter state after the round (not part of the win): <charges>/<of>:<pot>:<previous state>
                        // (rounds from before the meter chain have no previous state)
                        String[] fields = value.split(":", 3);
                        String[] count = fields[0].split("/", -1);
                        if (fields.length < 2 || count.length != 2) {
                            throw new IllegalArgumentException("meter: " + value);
                        }
                        meter = new Meter(Integer.parseInt(count[0]), Integer.parseInt(count[1]), new BigDecimal(fields[1]),
                                fields.length == 3 ? fields[2] : null);
                    }
                    case "capped" -> capped = new BigDecimal(value);
                    default -> throw new IllegalArgumentException("unknown bonus part: " + part);
                }
            }
        }
        return new Outcome(List.copyOf(slots), List.copyOf(multipliers), hotSlot, hotBoost, Map.copyOf(golden), List.copyOf(free), meter, capped);
    }
}
