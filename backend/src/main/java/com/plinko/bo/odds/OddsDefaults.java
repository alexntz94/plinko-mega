package com.plinko.bo.odds;

import java.util.Map;

/**
 * The standard SHAPE of each table: multipliers per rows count, indexed [prizeIndex][risk] with prize
 * index 0 at the centre (about 99% RTP as written). Live tables are built from this shape at a target
 * RTP by {@link OddsService#forTargetRtp}.
 */
public final class OddsDefaults {

    public static final Map<Integer, double[][]> MULTIPLIERS = Map.of(
            8, new double[][]{{0.5, 0.4, 0.2}, {1, 0.7, 0.3}, {1.1, 1.3, 1.5}, {2.1, 3, 4}, {5.6, 13, 29}},
            9, new double[][]{{0.7, 0.5, 0.2}, {1, 0.9, 0.6}, {1.6, 1.7, 2}, {2, 4, 7}, {5.6, 18, 43}},
            10, new double[][]{{0.5, 0.4, 0.2}, {1, 0.6, 0.3}, {1.1, 1.4, 0.9}, {1.4, 2, 3}, {3, 5, 10}, {8.9, 22, 76}},
            11, new double[][]{{0.7, 0.5, 0.2}, {1, 0.7, 0.4}, {1.3, 1.8, 1.4}, {1.9, 3, 5.2}, {3, 6, 14}, {8.4, 24, 120}},
            12, new double[][]{{0.5, 0.3, 0.2}, {1, 0.6, 0.2}, {1.1, 1.1, 0.7}, {1.4, 2, 2}, {1.6, 4, 8.1}, {3, 11, 24}, {10, 33, 170}},
            13, new double[][]{{0.7, 0.4, 0.2}, {0.9, 0.7, 0.2}, {1.2, 1.3, 1}, {1.9, 3, 4}, {3, 6, 11}, {4, 13, 37}, {8.1, 43, 260}},
            14, new double[][]{{0.5, 0.2, 0.2}, {1, 0.5, 0.2}, {1.1, 1, 0.3}, {1.3, 1.9, 1.9}, {1.4, 4, 5}, {1.9, 7, 18}, {4, 15, 56}, {7.1, 58, 420}}
    );

    private OddsDefaults() {
    }
}
