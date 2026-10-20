package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.plinko.TestFixtures;
import com.plinko.model.OddsTable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OddsTest {

    private final OddsService odds = new OddsService(null, TestFixtures.properties(), new com.plinko.common.Integrity(TestFixtures.properties()), null, null, null);

    @Test
    void slotsMapToSymmetricPrizePositions() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            int prizes = PlinkoBoard.prizeCount(rows);
            for (int prize = 0; prize < prizes; prize++) {
                int left = PlinkoBoard.leftSlot(rows, prize);
                int right = PlinkoBoard.rightSlot(rows, prize);
                assertThat(left).isBetween(0, rows);
                assertThat(right).isBetween(0, rows);
                assertThat(left + right).isEqualTo(rows);
                assertThat(PlinkoBoard.prizeIndexOfSlot(rows, left)).isEqualTo(prize);
                assertThat(PlinkoBoard.prizeIndexOfSlot(rows, right)).isEqualTo(prize);
            }
            assertThat(PlinkoBoard.prizeIndexOfSlot(rows, 0)).isEqualTo(prizes - 1);
        }
    }

    /**
     * Review N15.5: the same layout the game client is tested against (game/test/rules.test.ts reads this file too), for
     * every board the server offers.
     */
    @Test
    void slotLayoutMatchesTheGameClient() throws IOException {
        JsonNode layout;
        try (InputStream in = OddsTest.class.getResourceAsStream("/fixtures/slot-layout.json")) {
            assertThat(in).as("fixtures/slot-layout.json").isNotNull();
            layout = JsonMapper.builder().build().readTree(in).get("rows");
        }
        assertThat(layout.size()).isEqualTo(PlinkoBoard.MAX_ROWS - PlinkoBoard.MIN_ROWS + 1);
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            JsonNode client = layout.get(String.valueOf(rows));
            assertThat(client).as("%d rows", rows).isNotNull();
            assertThat(client.size()).isEqualTo(rows + 1);
            for (int slot = 0; slot <= rows; slot++) {
                assertThat(PlinkoBoard.prizeIndexOfSlot(rows, slot)).as("%d rows, slot %d", rows, slot).isEqualTo(client.get(slot).asInt());
            }
        }
    }

    @Test
    void naturalProbabilitiesSumToOne() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            double sum = 0;
            for (double p : PlinkoBoard.naturalProbabilities(rows)) {
                sum += p;
            }
            assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void everyDefaultTablePassesTheHardLimits() {
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.defaultTable(rows, risk);
                assertThat(odds.validate(table)).as("%d rows risk %d", rows, risk).isEmpty();
                assertThat(table.rtp().doubleValue()).isCloseTo(96.0, org.assertj.core.data.Offset.offset(0.0001));
            }
        }
    }

    @Test
    void targetRtpIsHitExactlyWithReadableRisingTables() {
        for (String target : List.of("88", "92", "94", "95.5", "96", "97", "98", "99.5")) {
            for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
                for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                    OddsTable table = odds.forTargetRtp(rows, risk, new BigDecimal(target));
                    String where = target + "% " + rows + " rows risk " + risk;
                    assertThat(table.rtp().doubleValue()).as(where).isCloseTo(Double.parseDouble(target), org.assertj.core.data.Offset.offset(0.0001));
                    assertThat(odds.validate(table)).as(where).isEmpty();
                    for (int i = 1; i < table.prizes().size(); i++) {
                        assertThat(table.prizes().get(i).multiplier()).as(where).isGreaterThanOrEqualTo(table.prizes().get(i - 1).multiplier());
                    }
                    double[] natural = PlinkoBoard.naturalProbabilities(rows);
                    for (OddsTable.Prize prize : table.prizes()) {
                        // the fine-tuning keeps every probability within 3 points of the physical board
                        assertThat(Math.abs(prize.probability().doubleValue() - natural[prize.index()] * 100)).as(where).isLessThan(3.0);
                    }
                }
            }
        }
    }

    @Test
    void lowerRtpMeansSmallerTopPrizes() {
        OddsTable high = odds.forTargetRtp(14, 2, new BigDecimal("99"));
        OddsTable low = odds.forTargetRtp(14, 2, new BigDecimal("94"));
        assertThat(low.maxMultiplier()).isLessThan(high.maxMultiplier());
    }

    @Test
    void rejectsOddsThatWouldCreateFakeWinners() {
        OddsTable fair = odds.defaultTable(8, 2);
        List<OddsTable.Prize> rigged = new ArrayList<>(fair.prizes());
        OddsTable.Prize edge = rigged.get(rigged.size() - 1);
        // Put 50% on the 29x edges.
        rigged.set(rigged.size() - 1, new OddsTable.Prize(edge.index(), edge.multiplier(), new BigDecimal("50")));
        rigged.set(0, new OddsTable.Prize(0, rigged.get(0).multiplier(), rigged.get(0).probability().subtract(new BigDecimal("50")).max(BigDecimal.ZERO)));
        assertThat(odds.validate(new OddsTable(8, 2, rigged))).isNotEmpty();

        List<OddsTable.Prize> huge = new ArrayList<>(fair.prizes());
        huge.set(huge.size() - 1, new OddsTable.Prize(edge.index(), new BigDecimal("5000"), edge.probability()));
        assertThat(odds.validate(new OddsTable(8, 2, huge))).anyMatch(e -> e.contains("hard limit"));

        List<OddsTable.Prize> negative = new ArrayList<>(fair.prizes());
        negative.set(0, new OddsTable.Prize(0, new BigDecimal("-1"), negative.get(0).probability()));
        assertThat(odds.validate(new OddsTable(8, 2, negative))).isNotEmpty();

        assertThat(odds.validate(new OddsTable(8, 2, fair.prizes().subList(0, 3)))).isNotEmpty();
    }

    @Test
    void pickedSlotsFollowTheConfiguredProbabilities() {
        OddsTable table = odds.defaultTable(12, 2);
        int draws = 400_000;
        int[] counts = new int[table.prizes().size()];
        double win = 0;
        for (int i = 0; i < draws; i++) {
            int slot = odds.pickSlot(table);
            int prize = PlinkoBoard.prizeIndexOfSlot(12, slot);
            counts[prize]++;
            win += table.prizes().get(prize).multiplier().doubleValue();
        }
        for (OddsTable.Prize prize : table.prizes()) {
            double expected = prize.probability().doubleValue() / 100;
            double observed = (double) counts[prize.index()] / draws;
            double sigma = Math.sqrt(expected * (1 - expected) / draws);
            assertThat(Math.abs(observed - expected)).as("prize %d", prize.index()).isLessThan(5 * sigma + 1e-9);
        }
        // Empirical RTP within a few percent of theory (the 170x edge makes it noisy).
        assertThat(win / draws * 100).isBetween(91.0, 101.0);
    }
}
