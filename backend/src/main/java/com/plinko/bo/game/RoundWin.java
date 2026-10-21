package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * How a round's win is worked out, in one place: {@link GameService} pays with it, {@link WinCheck} recomputes stored
 * rounds with it and the player's history replays them with it, so they can never drift apart. The stored outcome is
 * written by {@link Text} and read by {@link #parse}, side by side (review 7.8): the text is part of what a round's
 * signature covers, so its format never changes for rounds already played.
 * <p>
 * A stored round keeps its outcome as text (written in {@code GameService.playRound}): {@code slots} and
 * {@code multipliers} hold one entry per paid ball, and {@code bonus} the bonus parts, separated by ';':
 * <ul>
 * <li>{@code path:<path>,...}: every ball's path (see {@code MegaBoard}), one per ball;</li>
 * <li>{@code gates:<boost>}: what each gate a path goes through multiplies the prize by;</li>
 * <li>{@code layout:<index>}: which of the board's gate layouts the round used (see {@code MegaBoard.layouts});</li>
 * <li>{@code megaball:<ball>x<multiplier>,...}: balls that dropped as a MEGA BALL and what it multiplied their win by
 *     (already in the ball's multiplier);</li>
 * <li>{@code spin:<ball>@<segment>x<multiplier>,...}: balls in a SPIN slot and what their wheel spin multiplied that
 *     ball's win by (paid on top of the ball's own win);</li>
 * <li>{@code mega:<stake>@<segment>x<multiplier>,...}: MEGA SPINs of the gold wheel paid by a full meter (each for its
 *     own stake from the pot);</li>
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

    /** What a wheel spin pays: the ball's own win (its stake times its multiplier) times the segment, rounded down. */
    public static BigDecimal spin(BigDecimal stake, BigDecimal ballMultiplier, BigDecimal segment) {
        return ball(stake, ballMultiplier.multiply(segment));
    }

    /** A wheel spin: the paid ball whose SPIN slot started it, the segment it stopped on and what that multiplies. */
    public record Spin(int ball, int segment, BigDecimal multiplier) {
    }

    /** A MEGA BALL: the paid ball it was and what it multiplied that ball's win by. */
    public record MegaBall(int ball, BigDecimal multiplier) {
    }

    /** A MEGA SPIN of the gold wheel: its stake (from the pot), the segment it stopped on and what that pays. */
    public record MegaSpin(BigDecimal stake, int segment, BigDecimal multiplier) {
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
     * Writes a round's bonus parts in the stored format, always in the same order (path, gates, layout, megaball, spin,
     * mega, meter, capped),
     * whatever order they are added in; null when the round had none.
     */
    public static final class Text {
        private final List<String> paths = new ArrayList<>();
        private String gates;
        private String layout;
        private final List<String> megaBalls = new ArrayList<>();
        private final List<String> spins = new ArrayList<>();
        private final List<String> megas = new ArrayList<>();
        private String meter;
        private String capped;

        public Text path(int path) {
            paths.add(String.valueOf(path));
            return this;
        }

        public Text gates(BigDecimal boost) {
            gates = "gates:" + boost.stripTrailingZeros().toPlainString();
            return this;
        }

        public Text layout(int index) {
            layout = "layout:" + index;
            return this;
        }

        public Text megaBall(int ball, BigDecimal multiplier) {
            megaBalls.add(ball + "x" + multiplier.stripTrailingZeros().toPlainString());
            return this;
        }

        public Text spin(int ball, int segment, BigDecimal multiplier) {
            spins.add(ball + "@" + segment + "x" + multiplier.stripTrailingZeros().toPlainString());
            return this;
        }

        public Text mega(BigDecimal stake, int segment, BigDecimal multiplier) {
            megas.add(stake.toPlainString() + "@" + segment + "x" + multiplier.stripTrailingZeros().toPlainString());
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
            if (!paths.isEmpty()) {
                parts.add("path:" + String.join(",", paths));
            }
            if (gates != null) {
                parts.add(gates);
            }
            if (layout != null) {
                parts.add(layout);
            }
            if (!megaBalls.isEmpty()) {
                parts.add("megaball:" + String.join(",", megaBalls));
            }
            if (!spins.isEmpty()) {
                parts.add("spin:" + String.join(",", spins));
            }
            if (!megas.isEmpty()) {
                parts.add("mega:" + String.join(",", megas));
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
     * A stored outcome read back. meter: the meter after the round (or null);
     * capped: the win before the cap (or null); paths: every ball's path; gateBoost:
     * what a gate multiplied by (null: no gates); spins and megaSpins: the wheel spins the round paid; megaBalls: the
     * balls that dropped as a MEGA BALL.
     */
    public record Outcome(List<Integer> slots, List<BigDecimal> multipliers, Meter meter, BigDecimal capped,
                          List<Integer> paths, BigDecimal gateBoost, List<Spin> spins, List<MegaSpin> megaSpins,
                          List<MegaBall> megaBalls, int layout) {

        /** What ball {@code ball}'s MEGA BALL multiplied its win by (1: a plain ball). */
        public BigDecimal megaBallTimes(int ball) {
            return megaBalls.stream().filter(m -> m.ball() == ball).map(MegaBall::multiplier).findFirst().orElse(BigDecimal.ONE);
        }

        /** The win before any cap: every ball at the bet, every wheel spin its ball's win again times the segment, every MEGA SPIN at its own stake. */
        public BigDecimal raw(BigDecimal bet) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal multiplier : multipliers) {
                sum = sum.add(ball(bet, multiplier));
            }
            for (Spin spin : spins) {
                sum = sum.add(spin(bet, multipliers.get(spin.ball()), spin.multiplier()));
            }
            for (MegaSpin mega : megaSpins) {
                sum = sum.add(ball(mega.stake(), mega.multiplier()));
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
        BigDecimal capped = null;
        Meter meter = null;
        List<Integer> paths = null;
        BigDecimal gateBoost = null;
        List<Spin> spins = new ArrayList<>();
        List<MegaSpin> megaSpins = new ArrayList<>();
        List<MegaBall> megaBalls = new ArrayList<>();
        int layout = 0;
        if (bonus != null) {
            for (String part : bonus.split(";", -1)) {
                int colon = part.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException("bonus part without a name: " + part);
                }
                String value = part.substring(colon + 1);
                switch (part.substring(0, colon)) {
                    case "path" -> {
                        if (paths != null) {
                            throw new IllegalArgumentException("two path parts");
                        }
                        paths = new ArrayList<>();
                        for (String entry : value.split(",", -1)) {
                            paths.add(Integer.parseInt(entry));
                        }
                    }
                    case "gates" -> gateBoost = new BigDecimal(value);
                    case "layout" -> layout = Integer.parseInt(value);
                    case "megaball" -> {
                        if (!megaBalls.isEmpty()) {
                            throw new IllegalArgumentException("two megaball parts");
                        }
                        for (String entry : value.split(",", -1)) {
                            String[] ballAndTimes = entry.split("x", -1);
                            if (ballAndTimes.length != 2) {
                                throw new IllegalArgumentException("megaball: " + entry);
                            }
                            megaBalls.add(new MegaBall(Integer.parseInt(ballAndTimes[0]), new BigDecimal(ballAndTimes[1])));
                        }
                    }
                    case "spin" -> {
                        for (String entry : value.split(",", -1)) {
                            String[] ballAndRest = entry.split("@", -1);
                            String[] segmentAndMultiplier = ballAndRest.length == 2 ? ballAndRest[1].split("x", -1) : new String[0];
                            if (segmentAndMultiplier.length != 2) {
                                throw new IllegalArgumentException("spin: " + entry);
                            }
                            spins.add(new Spin(Integer.parseInt(ballAndRest[0]), Integer.parseInt(segmentAndMultiplier[0]),
                                    new BigDecimal(segmentAndMultiplier[1])));
                        }
                    }
                    case "mega" -> {
                        for (String entry : value.split(",", -1)) {
                            String[] stakeAndRest = entry.split("@", -1);
                            String[] segmentAndMultiplier = stakeAndRest.length == 2 ? stakeAndRest[1].split("x", -1) : new String[0];
                            if (segmentAndMultiplier.length != 2) {
                                throw new IllegalArgumentException("mega: " + entry);
                            }
                            megaSpins.add(new MegaSpin(new BigDecimal(stakeAndRest[0]), Integer.parseInt(segmentAndMultiplier[0]),
                                    new BigDecimal(segmentAndMultiplier[1])));
                        }
                    }
                    case "meter" -> {
                        // the meter state after the round (not part of the win): <charges>/<of>:<pot>:<previous state>
                        String[] fields = value.split(":", 3);
                        String[] count = fields[0].split("/", -1);
                        if (fields.length != 3 || count.length != 2) {
                            throw new IllegalArgumentException("meter: " + value);
                        }
                        meter = new Meter(Integer.parseInt(count[0]), Integer.parseInt(count[1]), new BigDecimal(fields[1]), fields[2]);
                    }
                    case "capped" -> capped = new BigDecimal(value);
                    default -> throw new IllegalArgumentException("unknown bonus part: " + part);
                }
            }
        }
        if (paths == null || paths.size() != slots.size()) {
            throw new IllegalArgumentException("every ball needs its path");
        }
        for (Spin spin : spins) {
            if (spin.ball() < 0 || spin.ball() >= slots.size()) {
                throw new IllegalArgumentException("wheel spin of ball " + spin.ball() + ", which was not played");
            }
        }
        return new Outcome(List.copyOf(slots), List.copyOf(multipliers), meter, capped,
                List.copyOf(paths), gateBoost, List.copyOf(spins), List.copyOf(megaSpins), List.copyOf(megaBalls), layout);
    }
}
