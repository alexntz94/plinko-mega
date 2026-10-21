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
 * The bars are three pegs wide, starting next to the centre, at about a third, three fifths and five sixths of the way
 * down, on the left, the right and the left again. The two SPIN slots are the pair whose natural chance is closest to
 * 4% (with 10 balls, a wheel spin in about one round in three).
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

    private static final double[][] GATE_ROWS = {{.35, -1}, {.6, 1}, {.85, -1}};
    private static final double SPIN_CHANCE = .04;

    private MegaBoard() {
    }

    /** The three gates of a board with this many rows, top to bottom. */
    public static List<Gate> gates(int rows) {
        List<Gate> gates = new ArrayList<>();
        for (double[] row : GATE_ROWS) {
            int step = Math.max(2, Math.min(rows - 1, (int) Math.round(rows * row[0])));
            // the peg next to the centre on this gate's side, and the next two outwards
            if (row[1] < 0) {
                int to = (step - 1) / 2;
                gates.add(new Gate(step, Math.max(0, to - BAR + 1), to));
            } else {
                int from = step / 2 + 1;
                gates.add(new Gate(step, from, Math.min(step, from + BAR - 1)));
            }
        }
        return List.copyOf(gates);
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

    /** How many gates this path goes through. */
    public static int gatesPassed(int rows, int path) {
        int passed = 0;
        for (Gate gate : gates(rows)) {
            int mask = (1 << gate.step()) - 1;
            if (gate.covers(Integer.bitCount(path & mask))) {
                passed++;
            }
        }
        return passed;
    }

    /**
     * What the gates multiply a ball's prize by on average, for a ball that lands in this slot: every path to the slot
     * is equally likely (the server draws them so), and a path through g gates pays boost^g.
     */
    public static double gateFactor(int rows, int slot, double boost) {
        List<Gate> gates = gates(rows);
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
