package com.plinko.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.flywaydb.core.Flyway;

/**
 * Applies the database migrations as the schema owner, then exits. Runs in its own short-lived container
 * before the application starts, so the running application never holds the owner's credentials.
 * <pre>java -Dloader.main=com.plinko.migration.Migrator -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher</pre>
 * Settings come from the environment, or from files with the same names: /run/secrets/NAME (Docker secrets) or
 * /secrets/NAME/NAME (Cloud Run, which mounts every secret in a folder of its own).
 * <p>
 * Review R3: with the argument {@code repair} it runs Flyway's repair instead (README, Rolling back): it removes the
 * record of a migration that failed half-way (a non-transactional one, such as a batched backfill, can't roll back on
 * its own), so the next run starts it again; nothing else in the database is touched.
 */
public final class Migrator {

    private Migrator() {
    }

    /**
     * N11: migrations run while the previous version is serving players; under READ COMMITTED a data backfill
     * (INSERT ... SELECT, UPDATE with a subquery) reads the rounds without locking them.
     */
    public static final String SESSION = "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED";

    /** How every migration run is set up: here, and in the database tests, so they test exactly this. */
    public static org.flywaydb.core.api.configuration.FluentConfiguration configure(String url, String owner, String password, String dbName,
                                                                                  String appUser) {
        return Flyway.configure()
                .dataSource(url, owner, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("dbName", dbName, "appUser", appUser))
                .initSql(SESSION)
                .resolvers(new SqlRecordedVersions())
                .callbacks(new SqlVersionsRewritten());
    }

    /** The same set-up for Spring Boot's own Flyway (when the backend migrates by itself, e.g. a local run without Compose). */
    @org.springframework.stereotype.Component
    static final class SpringFlyway implements org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer {
        @Override
        public void customize(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
            configuration.resolvers(new SqlRecordedVersions());
        }
    }

    public static void main(String[] args) {
        Flyway flyway = configure(required("DB_URL"), required("DB_OWNER_USERNAME"), required("DB_OWNER_PASSWORD"), required("DB_NAME"),
                required("DB_USERNAME")).load();
        if (args.length > 0 && "repair".equals(args[0])) {
            var repaired = flyway.repair();
            System.out.println("Database migrations repaired: " + repaired.migrationsRemoved.size() + " failed record(s) removed, "
                    + repaired.migrationsAligned.size() + " aligned; run the migrations again");
            return;
        }
        if (args.length > 0) {
            throw new IllegalArgumentException("Unknown argument " + args[0] + ": none (migrate) or repair");
        }
        int applied = flyway.migrate().migrationsExecuted;
        System.out.println("Database migrations applied: " + applied + "; schema version "
                + flyway.info().current().getVersion());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Path file = Path.of("/run/secrets", name);
        if (!Files.isReadable(file)) {
            file = Path.of("/secrets", name, name);
        }
        if ((value == null || value.isBlank()) && Files.isReadable(file)) {
            try {
                value = Files.readString(file).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read secret " + name, e);
            }
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
