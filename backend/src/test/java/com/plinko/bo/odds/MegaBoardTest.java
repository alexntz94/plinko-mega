package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.plinko.bo.bonus.BonusService;
import org.junit.jupiter.api.Test;

/** The gates move every round (one of a fixed list of layouts), and a MEGA BALL's holes are fallen straight through. */
class MegaBoardTest {

    @Test
    void everyBoardHasSeveralLayoutsOfThreeBarsOnTheBoard() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            List<List<MegaBoard.Gate>> layouts = MegaBoard.layouts(rows);
            assertThat(layouts).as("%d rows", rows).hasSizeGreaterThanOrEqualTo(8).doesNotHaveDuplicates();
            assertThat(layouts.get(0)).isEqualTo(MegaBoard.gates(rows));
            for (List<MegaBoard.Gate> layout : layouts) {
                assertThat(layout).hasSize(3);
                assertThat(layout.stream().map(MegaBoard.Gate::step).distinct()).hasSize(3);
                for (MegaBoard.Gate gate : layout) {
                    assertThat(gate.step()).isBetween(2, rows - 1);
                    assertThat(gate.from()).isBetween(0, gate.to());
                    assertThat(gate.to()).isLessThanOrEqualTo(gate.step());
                }
            }
            // the same list every time: a stored round names its layout by its place in it
            assertThat(MegaBoard.layouts(rows)).isSameAs(layouts);
        }
    }

    @Test
    void ballsAfterAMegaBallFallStraightThroughItsHoles() {
        BonusService bonus = new BonusService(null, null);
        java.util.Random random = new java.util.Random(3);
        for (int r = PlinkoBoard.MIN_ROWS; r <= PlinkoBoard.MAX_ROWS; r++) {
            final int rows = r;
            assertThat(MegaBoard.smashRows(rows)).hasSize(3).allSatisfy(row -> assertThat(row).isBetween(1, rows - 2));
            for (int i = 0; i < 300; i++) {
                int mega = bonus.rollPath(rows, random.nextInt(rows + 1));
                Set<Integer> holes = MegaBoard.smashed(rows, mega);
                int slot = random.nextInt(rows + 1);
                int path = bonus.rollPath(rows, slot, holes);
                assertThat(MegaBoard.slotOf(path)).isEqualTo(slot);
                assertThat(MegaBoard.isPath(rows, path)).isTrue();
                // through the holes when any path to the slot can be; otherwise the peg is back for this ball
                if (MegaBoard.throughHoles(rows, path, holes)) {
                    continue;
                }
                assertThat(java.util.stream.IntStream.range(0, 1 << rows)
                        .filter(p -> MegaBoard.slotOf(p) == slot && MegaBoard.throughHoles(rows, p, holes)).count()).isZero();
            }
        }
    }
}
