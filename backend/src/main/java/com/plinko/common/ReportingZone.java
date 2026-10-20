package com.plinko.common;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The time zone the casino's days are counted in (review 7.9): Finance, the dashboard's day totals and the daily totals
 * table use its days, so the figures match the casino's own books. Times are stored in UTC as always; only where one day
 * ends and the next begins moves. {@code REPORTING_TIME_ZONE}, e.g. {@code Europe/Malta}; UTC when unset.
 */
@Component
public class ReportingZone {

    private final ZoneId zone;

    public ReportingZone(@Value("${plinko.reporting.time-zone:${REPORTING_TIME_ZONE:UTC}}") String zone) {
        try {
            this.zone = ZoneId.of(zone == null || zone.isBlank() ? "UTC" : zone.trim());
        } catch (DateTimeException e) {
            throw new IllegalStateException("REPORTING_TIME_ZONE is not a time zone (e.g. UTC, Europe/Malta): " + zone);
        }
    }

    public static ReportingZone utc() {
        return new ReportingZone("UTC");
    }

    public ZoneId zone() {
        return zone;
    }

    public String name() {
        return zone.getId();
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalDate dayOf(Instant time) {
        return time.atZone(zone).toLocalDate();
    }

    /** The first moment of {@code day} in this zone. */
    public Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    /** The first moment of the day after {@code day} (exclusive end). */
    public Instant end(LocalDate day) {
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
