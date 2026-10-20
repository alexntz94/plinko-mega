package com.plinko.bo.odds;

/**
 * Board geometry shared with the game client.
 *
 * <p>A board with {@code rows} rows of pins has {@code rows + 1} landing slots, numbered 0 (left edge)
 * to {@code rows} (right edge). Prizes are symmetric: prize index 0 is the centre, the highest
 * index the two edge slots. Boards with an even row count have a single centre slot.
 */
public final class PlinkoBoard {

    public static final int MIN_ROWS = 8;
    public static final int MAX_ROWS = 14;
    public static final int RISK_LEVELS = 3;
    public static final String[] RISK_NAMES = {"Low", "Mid", "High"};

    private PlinkoBoard() {
    }

    public static int slotCount(int rows) {
        return rows + 1;
    }

    public static int prizeCount(int rows) {
        return rows / 2 + 1;
    }

    public static int prizeIndexOfSlot(int rows, int slot) {
        if (rows % 2 == 0) {
            return Math.abs(slot - rows / 2);
        }
        int rightStart = (rows + 1) / 2;
        return slot < rightStart ? rightStart - 1 - slot : slot - rightStart;
    }

    /** Left slot of a prize position, or the centre slot. */
    public static int leftSlot(int rows, int prizeIndex) {
        return rows % 2 == 0 ? rows / 2 - prizeIndex : (rows - 1) / 2 - prizeIndex;
    }

    /** Right slot of a prize position, or the centre slot. */
    public static int rightSlot(int rows, int prizeIndex) {
        return rows % 2 == 0 ? rows / 2 + prizeIndex : (rows + 1) / 2 + prizeIndex;
    }

    /**
     * Probability (0..1) of each prize position for a physical board where every pin is a 50/50
     * bounce, i.e. a binomial distribution over the slots.
     */
    public static double[] naturalProbabilities(int rows) {
        double[] probabilities = new double[prizeCount(rows)];
        for (int slot = 0; slot <= rows; slot++) {
            probabilities[prizeIndexOfSlot(rows, slot)] += binomial(rows, slot) / Math.pow(2, rows);
        }
        return probabilities;
    }

    private static double binomial(int n, int k) {
        double result = 1;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }
}
