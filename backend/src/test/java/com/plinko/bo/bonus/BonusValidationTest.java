package com.plinko.bo.bonus;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.plinko.model.BonusSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Review 3.3: settings outside their limits are refused when proposed, and read as "all off" if they were stored anyway. */
class BonusValidationTest {

    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build());
    private static final BonusSettings MEGA = BonusSettings.MEGA;

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static BonusSettings meter(int charges, String minMultiplier, String potShare) {
        return new BonusSettings(new BonusSettings.Meter(true, charges, d(minMultiplier), d(potShare)), MEGA.gates(), MEGA.wheel(), MEGA.megaWheel());
    }

    private static BonusSettings gates(String boost) {
        return new BonusSettings(MEGA.meter(), new BonusSettings.Gates(true, d(boost)), MEGA.wheel(), MEGA.megaWheel());
    }

    private static BonusSettings wheel(BonusSettings.Segment... segments) {
        return new BonusSettings(MEGA.meter(), MEGA.gates(), new BonusSettings.Wheel(true, segments == null ? null : List.of(segments)),
                MEGA.megaWheel());
    }

    private static BonusSettings.Segment segment(String name, String multiplier, String weight) {
        return new BonusSettings.Segment(name, d(multiplier), d(weight));
    }

    /** Four segments that add up to 100%, the last one as given. */
    private static BonusSettings.Segment[] withLast(BonusSettings.Segment last) {
        return new BonusSettings.Segment[] {segment(null, "1", "40"), segment(null, "2", "30"), segment(null, "5", "20"), last};
    }

    static Stream<Arguments> refused() {
        List<BonusSettings.Segment> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(segment(null, "2", i < 16 ? "6" : "4"));
        }
        return Stream.of(
                Arguments.of(meter(2, "2", "30"), "between 3 and 100 charges"),
                Arguments.of(meter(101, "2", "30"), "between 3 and 100 charges"),
                Arguments.of(meter(10, null, "30"), "prize to charge it"),
                Arguments.of(meter(10, "1", "30"), "prize to charge it"),
                Arguments.of(meter(10, "1001", "30"), "prize to charge it"),
                Arguments.of(meter(10, "2.123", "30"), "prize to charge it"),
                Arguments.of(meter(10, "2", null), "share of the bet"),
                Arguments.of(meter(10, "2", "0"), "share of the bet"),
                Arguments.of(meter(10, "2", "101"), "share of the bet"),
                Arguments.of(meter(10, "2", "30.123"), "share of the bet"),
                Arguments.of(gates(null), "gate boost"),
                Arguments.of(gates("1"), "gate boost"),
                Arguments.of(gates("11"), "gate boost"),
                Arguments.of(gates("1.555"), "gate boost"),
                Arguments.of(wheel((BonusSettings.Segment[]) null), "4 to 16 segments"),
                Arguments.of(wheel(segment(null, "2", "50"), segment(null, "3", "50")), "4 to 16 segments"),
                Arguments.of(wheel(seventeen.toArray(BonusSettings.Segment[]::new)), "4 to 16 segments"),
                Arguments.of(wheel(withLast(segment(null, null, "10"))), "a multiplier and a weight"),
                Arguments.of(wheel(withLast(segment(null, "0", "10"))), "above 0x and at most 1000x"),
                Arguments.of(wheel(withLast(segment(null, "1001", "10"))), "above 0x and at most 1000x"),
                Arguments.of(wheel(withLast(segment("GRAND", "100", "10"))), "MINI, MAJOR or MEGA"),
                Arguments.of(wheel(withLast(segment(null, "3", "11"))), "add up to 100%"),
                Arguments.of(wheel(withLast(segment(null, "3", "0"))), "weights must be above 0"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void settingsOutsideTheirLimitsAreRefusedAndReadAsAllOff(BonusSettings settings, String error) {
        assertThat(bonus.validate(settings)).anyMatch(e -> e.contains(error));
        assertThat(bonus.parse(bonus.toJson(settings))).as("stored anyway: read as all off").isEqualTo(BonusSettings.OFF);
    }

    @Test
    void theSettingsWithinTheirLimitsAreKept() {
        assertThat(bonus.validate(MEGA)).isEmpty();
        assertThat(bonus.validate(BonusSettings.OFF)).isEmpty();
        assertThat(bonus.validate(null)).containsExactly("Bonus settings are incomplete");
        assertThat(bonus.parse(bonus.toJson(MEGA))).isEqualTo(MEGA);
    }

    @Test
    void unreadableSettingsAreAllOffAndMissingOnesAreTheStartingPoint() {
        // never saved: the game's own starting point (gates, wheel and meter on)
        assertThat(bonus.parse(null)).isEqualTo(MEGA);
        assertThat(bonus.parse("{not json")).isEqualTo(BonusSettings.OFF);
        // saved with only the meter: the gates and the wheel are off
        BonusSettings meterOnly = bonus.parse(bonus.toJson(new BonusSettings(MEGA.meter(), null, null, null)));
        assertThat(meterOnly.meterOn()).isTrue();
        assertThat(meterOnly.gatesOn()).isFalse();
        assertThat(meterOnly.wheelOn()).isFalse();
        assertThat(meterOnly.megaWheel()).isEqualTo(BonusSettings.OFF.megaWheel());
        assertThat(BonusService.withDefaults(null)).isNull();
    }
}
