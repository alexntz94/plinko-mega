package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.plinko.model.BonusSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Review 3.3: bonus settings outside their limits are refused when proposed, and read as "all off" if they were stored anyway. */
class BonusValidationTest {

    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build());

    private static final BonusSettings.Golden GOLDEN = new BonusSettings.Golden(true, new BigDecimal("1"), BonusSettings.OFF.golden().boosts());
    private static final BonusSettings.Hot HOT = new BonusSettings.Hot(true, new BigDecimal("20"), new BigDecimal("3"));
    private static final BonusSettings.Meter METER = new BonusSettings.Meter(true, 10, new BigDecimal("2"), new BigDecimal("30"), 10);

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static BonusSettings hot(String chance, String boost) {
        return new BonusSettings(GOLDEN, new BonusSettings.Hot(true, d(chance), d(boost)), METER);
    }

    private static BonusSettings meter(int charges, String minMultiplier, String potShare, int freeBalls) {
        return new BonusSettings(GOLDEN, HOT, new BonusSettings.Meter(true, charges, d(minMultiplier), d(potShare), freeBalls));
    }

    private static BonusSettings golden(String chance, BonusSettings.Boost... boosts) {
        return new BonusSettings(new BonusSettings.Golden(true, d(chance), boosts == null ? null : Arrays.asList(boosts)), HOT, METER);
    }

    private static BonusSettings.Boost boost(String multiplier, String weight) {
        return new BonusSettings.Boost(d(multiplier), d(weight));
    }

    static Stream<Arguments> refused() {
        List<BonusSettings.Boost> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(boost("2", i < 6 ? "14" : "16"));
        }
        return Stream.of(
                Arguments.of(hot(null, "3"), "Hot slot chance"),
                Arguments.of(hot("-1", "3"), "Hot slot chance"),
                Arguments.of(hot("20.123", "3"), "Hot slot chance"),
                Arguments.of(hot("20", null), "Hot slot boost"),
                Arguments.of(hot("20", "1"), "Hot slot boost"),
                Arguments.of(hot("20", "3.123"), "Hot slot boost"),
                Arguments.of(meter(2, "2", "30", 10), "between 3 and 100 charges"),
                Arguments.of(meter(101, "2", "30", 10), "between 3 and 100 charges"),
                Arguments.of(meter(10, null, "30", 10), "prize to charge it"),
                Arguments.of(meter(10, "1", "30", 10), "prize to charge it"),
                Arguments.of(meter(10, "1001", "30", 10), "prize to charge it"),
                Arguments.of(meter(10, "2.123", "30", 10), "prize to charge it"),
                Arguments.of(meter(10, "2", null, 10), "share of the bet"),
                Arguments.of(meter(10, "2", "0", 10), "share of the bet"),
                Arguments.of(meter(10, "2", "101", 10), "share of the bet"),
                Arguments.of(meter(10, "2", "30.123", 10), "share of the bet"),
                Arguments.of(meter(10, "2", "30", 0), "between 1 and 50 free balls"),
                Arguments.of(meter(10, "2", "30", 51), "between 1 and 50 free balls"),
                Arguments.of(golden(null, boost("2", "100")), "between 0% and 10%"),
                Arguments.of(golden("-1", boost("2", "100")), "between 0% and 10%"),
                Arguments.of(golden("1.123", boost("2", "100")), "at most 2 decimals"),
                Arguments.of(golden("1", (BonusSettings.Boost[]) null), "1 to 6 boosts"),
                Arguments.of(golden("1"), "1 to 6 boosts"),
                Arguments.of(golden("1", seven.toArray(BonusSettings.Boost[]::new)), "1 to 6 boosts"),
                Arguments.of(golden("1", boost(null, "100")), "a multiplier and a weight"),
                Arguments.of(golden("1", boost("2", null)), "a multiplier and a weight"),
                Arguments.of(golden("1", new BonusSettings.Boost[] {null}), "a multiplier and a weight"),
                Arguments.of(golden("1", boost("2.123", "100")), "Boosts must be above 1x"),
                Arguments.of(golden("1", boost("2", "50.123"), boost("3", "49.877")), "Boost weights must be above 0"),
                Arguments.of(golden("1", boost("2", "0"), boost("3", "100")), "Boost weights must be above 0"),
                Arguments.of(new BonusSettings(GOLDEN, null, METER), "incomplete"),
                Arguments.of(new BonusSettings(null, HOT, METER), "incomplete"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void settingsOutsideTheirLimitsAreRefusedAndReadAsAllOff(BonusSettings settings, String error) {
        assertThat(bonus.validate(settings)).anyMatch(e -> e.contains(error));
        if (settings.golden() != null && settings.hot() != null) {
            assertThat(bonus.parse(bonus.toJson(settings))).as("stored anyway: read as all off").isEqualTo(BonusSettings.OFF);
        }
    }

    @Test
    void theSettingsWithinTheirLimitsAreKept() {
        BonusSettings all = new BonusSettings(GOLDEN, HOT, METER);
        assertThat(bonus.validate(all)).isEmpty();
        assertThat(bonus.validate(null)).containsExactly("Bonus settings are incomplete");
        assertThat(bonus.parse(bonus.toJson(all))).isEqualTo(all);
    }

    @Test
    void unreadableOrMissingSettingsAreAllOffAndFeaturesAddedLaterStartOff() {
        assertThat(bonus.parse(null)).isEqualTo(BonusSettings.OFF);
        assertThat(bonus.parse("{not json")).isEqualTo(BonusSettings.OFF);
        // saved before hot slots and the meter existed
        BonusSettings old = bonus.parse("{\"golden\":{\"enabled\":true,\"chance\":1,\"boosts\":[{\"multiplier\":2,\"weight\":100}]}}");
        assertThat(old.goldenOn()).isTrue();
        assertThat(old.hot()).isEqualTo(BonusSettings.OFF.hot());
        assertThat(old.meter()).isEqualTo(BonusSettings.OFF.meter());
        assertThat(BonusService.withDefaults(null)).isNull();
    }

    @Test
    void withGoldenBallsOffNoBallIsGolden() {
        assertThat(BonusService.maxGoldenBoost(BonusSettings.OFF)).isEqualByComparingTo("1");
        BonusSettings never = golden("0", boost("2", "100"));
        for (int ball = 0; ball < 100; ball++) {
            assertThat(bonus.rollGolden(never)).isEqualByComparingTo("1");
            assertThat(bonus.rollGolden(BonusSettings.OFF)).isEqualByComparingTo("1");
            assertThat(bonus.rollHot(BonusSettings.OFF, 8)).isEqualTo(-1);
        }
    }
}
