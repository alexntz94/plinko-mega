package com.plinko.migration;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.executor.MigrationExecutor;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.extensibility.MigrationType;

/**
 * Review R3: V15 and V16 are the SQL files the previous release (v1.0.0) ships, and the database must record them as
 * that release expects, or its migration step refuses the database and a rollback can't start. So they are resolved
 * here as those SQL migrations (type SQL, the file's name and its checksum), while what runs is the batched backfill
 * of review N11 ({@link DailyTotalsBackfill}, {@link MeterPointerBackfill}): a long statement over a whole table never
 * runs while the previous release is serving players. An install that already recorded them as Java migrations
 * (6d80fc8 to f01819f) has those rows put back as SQL first ({@link SqlVersionsRewritten}).
 */
public final class SqlRecordedVersions implements MigrationResolver {

    public interface Backfill {
        void migrate(Connection connection, Map<String, String> placeholders) throws Exception;
    }

    /** One version recorded as its SQL file: its number, description and file name as Flyway derives them, its checksum. */
    public record Version(String version, String description, String script, int checksum, Backfill run) {
    }

    public static final List<Version> VERSIONS = List.of(
            new Version("15", "daily totals", "V15__daily_totals.sql", DailyTotalsBackfill.SQL_CHECKSUM, DailyTotalsBackfill::migrate),
            new Version("16", "meter pointer and indexes", "V16__meter_pointer_and_indexes.sql", MeterPointerBackfill.SQL_CHECKSUM,
                    MeterPointerBackfill::migrate));

    @Override
    public Collection<ResolvedMigration> resolveMigrations(MigrationResolver.Context context) {
        return VERSIONS.stream().<ResolvedMigration>map(Resolved::new).toList();
    }

    private record Resolved(Version v) implements ResolvedMigration {

        @Override
        public MigrationVersion getVersion() {
            return MigrationVersion.fromVersion(v.version());
        }

        @Override
        public String getDescription() {
            return v.description();
        }

        @Override
        public String getScript() {
            return v.script();
        }

        @Override
        public Integer getChecksum() {
            return v.checksum();
        }

        @Override
        public MigrationType getType() {
            return CoreMigrationType.SQL;
        }

        @Override
        public String getPhysicalLocation() {
            return "classpath:db/migration/" + v.script() + " (run as a batched backfill, " + SqlRecordedVersions.class.getName() + ")";
        }

        @Override
        public boolean checksumMatches(Integer checksum) {
            return Objects.equals(checksum, v.checksum());
        }

        @Override
        public boolean checksumMatchesWithoutBeingIdentical(Integer checksum) {
            return false;
        }

        @Override
        public MigrationExecutor getExecutor() {
            return new MigrationExecutor() {
                @Override
                public List<org.flywaydb.core.internal.jdbc.Results> execute(org.flywaydb.core.api.executor.Context context) throws java.sql.SQLException {
                    try {
                        v.run().migrate(context.getConnection(), context.getConfiguration().getPlaceholders());
                    } catch (java.sql.SQLException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new java.sql.SQLException("V" + v.version() + " failed: " + e.getMessage(), e);
                    }
                    return List.of();
                }

                /** Each batch commits on its own: a lock is never held longer than one batch (N11). */
                @Override
                public boolean canExecuteInTransaction() {
                    return false;
                }

                @Override
                public boolean shouldExecute() {
                    return true;
                }
            };
        }
    }
}
