package com.plinko.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * Review R3: V15 and V16 are recorded as the SQL files the previous release ships ({@link SqlRecordedVersions}). The
 * releases from 6d80fc8 to f01819f recorded them as Java migrations (type JDBC, the class as script), which v1.0.0's
 * migration step refuses ("applied migration not resolved locally"). Before validating, those exact rows (same
 * version, type JDBC, the SQL file's checksum, a successful run, nothing else) are put back as the SQL migrations, so
 * both this release and the previous one see the migration they know.
 */
@org.springframework.stereotype.Component
public final class SqlVersionsRewritten implements Callback {

    /** version, checksum, the Java class recorded as its script by 6d80fc8 to f01819f, the SQL file it is again. */
    private static final Object[][] REWRITTEN = {
        {"15", DailyTotalsBackfill.SQL_CHECKSUM, "db.migration.V15__daily_totals", "V15__daily_totals.sql"},
        {"16", MeterPointerBackfill.SQL_CHECKSUM, "db.migration.V16__meter_pointer_and_indexes", "V16__meter_pointer_and_indexes.sql"},
    };

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE || event == Event.BEFORE_INFO;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        String table = context.getConfiguration().getTable();
        try (PreparedStatement exists = context.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            exists.setString(1, table);
            try (ResultSet rs = exists.executeQuery()) {
                rs.next();
                if (rs.getLong(1) == 0) {
                    return;
                }
            }
            for (Object[] rewritten : REWRITTEN) {
                try (PreparedStatement update = context.getConnection().prepareStatement("UPDATE `" + table + "` SET type = 'SQL', script = ? "
                        + "WHERE version = ? AND type = 'JDBC' AND script = ? AND checksum = ? AND success = 1")) {
                    update.setString(1, (String) rewritten[3]);
                    update.setString(2, (String) rewritten[0]);
                    update.setString(3, (String) rewritten[2]);
                    update.setInt(4, (Integer) rewritten[1]);
                    update.executeUpdate();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not record V15/V16 as their SQL versions again", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "sql-versions-rewritten";
    }
}
