package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Review 5.4: an admin list only ever sorts by its own allowed columns and pages within the limits. */
class ListQueryTest {

    private static final Map<String, String> SORTS = Map.of("time", "r.created_at", "bet", "r.total_bet");

    @Test
    void onlyAllowedColumnsReachTheSql() {
        assertThat(ListQuery.of(1, 50, "bet", "asc", SORTS, "time", false).orderBy("r.id")).isEqualTo(" ORDER BY r.total_bet ASC, r.id ASC");
        for (String attack : new String[] {"r.total_bet; DROP TABLE game_round", "(SELECT 1)", "time DESC, (SELECT SLEEP(5))", "TIME", null}) {
            ListQuery q = ListQuery.of(1, 50, attack, "asc", SORTS, "time", false);
            assertThat(q.sortColumn()).as(String.valueOf(attack)).isEqualTo("r.created_at");
            assertThat(q.ascending()).isFalse();
        }
        assertThat(ListQuery.of(1, 50, "bet", "'; --", SORTS, "time", false).orderBy(null)).isEqualTo(" ORDER BY r.total_bet DESC");
    }

    @Test
    void pageSizeAndDepthStayWithinTheLimits() {
        assertThat(ListQuery.of(null, 1_000_000, null, null, SORTS, "time", false).size()).isEqualTo(50);
        assertThat(ListQuery.of(-5, 25, null, null, SORTS, "time", false).page()).isEqualTo(1);
        // no deeper than 10,000 rows
        assertThat(ListQuery.of(999_999, 100, null, null, SORTS, "time", false).page()).isEqualTo(100);
        assertThat(ListQuery.of(999_999, 100, null, null, SORTS, "time", false).offset()).isEqualTo(9_900);
    }
}
