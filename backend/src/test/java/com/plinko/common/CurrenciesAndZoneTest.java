package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;

import org.junit.jupiter.api.Test;

/** Review 7.9: only 2-decimal currencies; days counted in the reporting time zone. */
class CurrenciesAndZoneTest {

    @Test
    void onlyCurrenciesWithTwoDecimalsAreOfferedOrAccepted() {
        assertThat(Currencies.OFFERED).allSatisfy(code -> assertThat(Currency.getInstance(code).getDefaultFractionDigits()).isEqualTo(2));
        assertThat(Currencies.isValid("EUR")).isTrue();
        assertThat(Currencies.isValid("JPY")).isFalse();   // 0 decimals
        assertThat(Currencies.isValid("KWD")).isFalse();   // 3 decimals
        assertThat(Currencies.isValid("XYZ")).isFalse();
        assertThat(Currencies.isValid(null)).isFalse();
    }

    @Test
    void daysFollowTheReportingTimeZone() {
        ReportingZone malta = new ReportingZone("Europe/Malta");
        Instant lateEvening = Instant.parse("2026-07-01T22:30:00Z");   // 00:30 on 2 July in Malta (UTC+2 in summer)
        assertThat(malta.dayOf(lateEvening)).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(ReportingZone.utc().dayOf(lateEvening)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(malta.start(LocalDate.of(2026, 7, 2))).isEqualTo(Instant.parse("2026-07-01T22:00:00Z"));
        assertThat(malta.end(LocalDate.of(2026, 7, 2))).isEqualTo(Instant.parse("2026-07-02T22:00:00Z"));
        // a day with a clock change is 23 hours long
        assertThat(java.time.Duration.between(malta.start(LocalDate.of(2026, 3, 29)), malta.end(LocalDate.of(2026, 3, 29))).toHours()).isEqualTo(23);
        assertThat(new ReportingZone("").name()).isEqualTo("UTC");
        assertThatThrownBy(() -> new ReportingZone("Mars/Base")).hasMessageContaining("REPORTING_TIME_ZONE");
    }
}
