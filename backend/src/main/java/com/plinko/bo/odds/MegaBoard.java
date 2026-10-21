package com.plinko.bo.odds;

import java.util.ArrayList;
import java.util.List;

/**
 * Plinko Mega's board features, shared with the game client (sent in the session's config): where the gates stand,
 * which slots spin the wheel, and the path a ball takes.
 * <p>
 * A ball's path is {@code rows} steps, left or right; it is kept as a number whose bit {@code i} is set when step
 * {@code i + 1} goes right, so the slot it ends in is the number of bits set. After {@code step} steps a ball has gone
 * right {@code rights} times. A gate is a bar across one row of pegs, from {@code from} to {@code to} rights: a ball
 * whose path crosses it there has its prize multiplied by the gate boost (once per gate, so up to three times).
 * <p>
 * The bars are three pegs wide. Every round the server picks one of a fixed list of layouts (see {@link #layouts}):
 * three bars at different heights, on the left or right of the centre, next to it or one peg out. The first layout
 * (at about a third, three fifths and five sixths of the way down, left, right, left, next to the centre) is the one
 * shown between rounds. The two SPIN slots are the pair whose natural chance is closest to 4% (with 10 balls, a wheel
 * spin in about one round in three).
 * <p>
 * A MEGA BALL smashes the pegs it hits on a few rows ({@link #smashRows}); the later balls of the round fall straight
 * through those holes (their path goes one right and one left there). Where a ball lands still comes from the odds
 * table: the holes change the way balls travel, not what a round pays.
 */
public final class MegaBoard {

    /** A gate: a bar across the pegs a ball reaches after {@code step} steps (1..rows-1) with {@code from}..{@code to} rights. */
    public record Gate(int step, int from, int to) {
        public boolean covers(int rights) {
            return rights >= from && rights <= to;
        }
    }

    /** How many pegs a gate's bar spans. */
    private static final int BAR = 3;

    /** Heights of the three bars (fractions of the way down), and which side each is on (-1 left, 1 right). */
    private static final double[][] HEIGHTS = {{.35, .6, .85}, {.3, .55, .8}, {.4, .65, .85}, {.3, .5, .75}, {.45, .65, .9}};
    private static final int[][] SIDES = {{-1, 1, -1}, {1, -1, 1}, {-1, -1, 1}, {1, 1, -1}};
    private static final double SPIN_CHANCE = .04;

    private MegaBoard() {
    }

    /** The gates shown between rounds: the first layout. */
    public static List<Gate> gates(int rows) {
        return layouts(rows).get(0);
    }

    private static final java.util.Map<Integer, List<List<Gate>>> LAYOUTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every gate layout a board with this many rows can have, the same list every time (a stored round names its
     * layout by its place here): each of the heights with each choice of sides, the bars next to the centre or one peg
     * out; layouts that would put two bars on one row, or a bar off the board, are left out.
     */
    public static List<List<Gate>> layouts(int rows) {
        return LAYOUTS.computeIfAbsent(rows, MegaBoard::makeLayouts);
    }

    private static List<List<Gate>> makeLayouts(int rows) {
        List<List<Gate>> layouts = new ArrayList<>();
        for (int out = 0; out <= 1; out++) {
            for (double[] heights : HEIGHTS) {
                for (int[] sides : SIDES) {
                    List<Gate> layout = new ArrayList<>();
                    java.util.Set<Integer> steps = new java.util.HashSet<>();
                    for (int g = 0; g < 3; g++) {
                        int step = Math.max(2, Math.min(rows - 1, (int) Math.round(rows * heights[g])));
                        Gate gate = bar(step, sides[g], out);
                        if (gate == null || !steps.add(step)) {
                            layout = null;
                            break;
                        }
                        layout.add(gate);
                    }
                    if (layout != null && !layouts.contains(layout)) {
                        layouts.add(List.copyOf(layout));
                    }
                }
            }
        }
        return List.copyOf(layouts);
    }

    /** A bar three pegs wide on this row, on one side of the centre, starting next to it or `out` pegs further. */
    private static Gate bar(int step, int side, int out) {
        if (side < 0) {
            int to = (step - 1) / 2 - out;
            return to < 0 ? null : new Gate(step, Math.max(0, to - BAR + 1), to);
        }
        int from = step / 2 + 1 + out;
        return from > step ? null : new Gate(step, from, Math.min(step, from + BAR - 1));
    }

    /**
     * The rows (the peg a ball reaches after that many steps) where a MEGA BALL smashes the peg it hits: three, about
     * a third, half and two thirds of the way down, never the top peg nor the last row (a ball falling straight
     * through a hole there would land between two slots).
     */
    public static List<Integer> smashRows(int rows) {
        java.util.TreeSet<Integer> picked = new java.util.TreeSet<>();
        for (double at : new double[] {.3, .5, .7}) {
            picked.add(Math.max(1, Math.min(rows - 2, (int) Math.round(rows * at))));
        }
        return List.copyOf(picked);
    }

    /** The pegs a MEGA BALL on this path smashes, as node keys (see {@link #node}). */
    public static java.util.Set<Integer> smashed(int rows, int path) {
        java.util.Set<Integer> holes = new java.util.HashSet<>();
        for (int row : smashRows(rows)) {
            holes.add(node(row, Integer.bitCount(path & ((1 << row) - 1))));
        }
        return holes;
    }

    /** A peg by its row (steps taken to reach it) and how many of those went right. */
    public static int node(int row, int rights) {
        return row * 64 + rights;
    }

    /**
     * Whether a path falls straight through every hole it reaches: one right and one left there, which takes it past
     * the next row's peg without touching it, onto the peg two rows down.
     */
    public static boolean throughHoles(int rows, int path, java.util.Set<Integer> holes) {
        int rights = 0;
        for (int row = 0; row < rows; ) {
            if (holes.contains(node(row, rights))) {
                if (row + 2 > rows || ((path >> row) & 1) == ((path >> (row + 1)) & 1)) {
                    return false;
                }
                rights++;
                row += 2;
            } else {
                rights += (path >> row) & 1;
                row++;
            }
        }
        return true;
    }

    /** The prize position whose two slots spin the wheel. */
    public static int spinPrizeIndex(int rows) {
        double[] natural = PlinkoBoard.naturalProbabilities(rows);
        int best = 1;
        for (int prize = 1; prize < natural.length; prize++) {
            if (Math.abs(natural[prize] - SPIN_CHANCE) < Math.abs(natural[best] - SPIN_CHANCE)) {
                best = prize;
            }
        }
        return best;
    }

    public static boolean isSpinSlot(int rows, int slot) {
        return PlinkoBoard.prizeIndexOfSlot(rows, slot) == spinPrizeIndex(rows);
    }

    /** The slot a path ends in. */
    public static int slotOf(int path) {
        return Integer.bitCount(path);
    }

    /** Whether a number is a path on this board (no step past the last row). */
    public static boolean isPath(int rows, int path) {
        return path >= 0 && path < (1 << rows);
    }

    /** How many gates of the first layout this path goes through. */
    public static int gatesPassed(int rows, int path) {
        return gatesPassed(gates(rows), path);
    }

    /** How many gates of this layout this path goes through. */
    public static int gatesPassed(List<Gate> layout, int path) {
        int passed = 0;
        for (Gate gate : layout) {
            int mask = (1 << gate.step()) - 1;
            if (gate.covers(Integer.bitCount(path & mask))) {
                passed++;
            }
        }
        return passed;
    }

    /**
     * What the gates multiply a ball's prize by on average, for a ball that lands in this slot: every layout is equally
     * likely, every path to the slot is equally likely (the server draws them so), and a path through g gates pays
     * boost^g.
     */
    public static double gateFactor(int rows, int slot, double boost) {
        double sum = 0;
        List<List<Gate>> layouts = layouts(rows);
        for (List<Gate> layout : layouts) {
            sum += gateFactor(rows, layout, slot, boost);
        }
        return sum / layouts.size();
    }

    private static double gateFactor(int rows, List<Gate> gates, int slot, double boost) {
        // count[k] and weight[k]: paths with k rights so far, and the sum of boost^gates over them
        double[] count = new double[rows + 1];
        double[] weight = new double[rows + 1];
        count[0] = 1;
        weight[0] = 1;
        for (int step = 1; step <= rows; step++) {
            double[] nextCount = new double[rows + 1];
            double[] nextWeight = new double[rows + 1];
            for (int k = 0; k < step; k++) {
                for (int to = k; to <= k + 1; to++) {
                    nextCount[to] += count[k];
                    nextWeight[to] += weight[k];
                }
            }
            for (Gate gate : gates) {
                if (gate.step() == step) {
                    for (int k = gate.from(); k <= gate.to(); k++) {
                        nextWeight[k] *= boost;
                    }
                }
            }
            count = nextCount;
            weight = nextWeight;
        }
        return weight[slot] / count[slot];
    }
}
