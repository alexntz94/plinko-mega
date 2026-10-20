package com.plinko.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.plinko.TestFixtures;
import com.plinko.common.ReportingZone;
import com.plinko.common.SecretBox;
import com.plinko.model.RoundStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review 5.5: the database rules on the production MySQL version: every migration applied to a database that already
 * holds the previous release's data (with its backfills checked), one test per integrity trigger, the Finance figures
 * over hand-built rounds with refunds, open rounds and free balls, the round lock shared by two connection pools, and the
 * meter pointer giving the same answer as the old LIKE query on generated histories. Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RepositoryRulesTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");

    private static DataSource app;
    private static JdbcClient jdbc;
    private static GameRepository game;

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
            s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE DATABASE plinko_prev");
            s.execute("GRANT ALL PRIVILEGES ON plinko_prev.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
        }
        flyway("plinko", null).migrate();
        app = appDataSource(MYSQL.getJdbcUrl());
        jdbc = JdbcClient.create(app);
        game = new GameRepository(jdbc, new SecretBox(TestFixtures.properties()), app);
    }

    /** The type and script each of V15 and V16 is recorded with in a schema's history. */
    private static Map<String, String> recordedAs(String schema) throws SQLException {
        Map<String, String> recorded = new java.util.TreeMap<>();
        try (Connection c = DriverManager.getConnection(url(schema), "owner", "owner-password-123"); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT version, type, script, checksum FROM flyway_schema_history WHERE version IN ('15', '16')")) {
            while (rs.next()) {
                recorded.put(rs.getString(1), rs.getString(2) + " " + rs.getString(3) + " " + rs.getInt(4));
            }
        }
        return recorded;
    }

    private static final Map<String, String> AS_SQL_FILES = Map.of(
            "15", "SQL V15__daily_totals.sql " + com.plinko.migration.DailyTotalsBackfill.SQL_CHECKSUM,
            "16", "SQL V16__meter_pointer_and_indexes.sql " + com.plinko.migration.MeterPointerBackfill.SQL_CHECKSUM);

    /** The previous release's view (v1.0.0): the SQL files of V15 and V16, no resolver or callback of this release. */
    private static Flyway previousRelease(String schema) {
        return Flyway.configure().dataSource(url(schema), "owner", "owner-password-123").locations("classpath:db/migration", "classpath:db/original")
                .placeholders(Map.of("dbName", schema, "appUser", "app")).load();
    }

    private static void createSchema(String schema) throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
            s.execute("CREATE DATABASE " + schema);
            s.execute("GRANT ALL PRIVILEGES ON " + schema + ".* TO 'owner'@'%' WITH GRANT OPTION");
        }
    }

    @Test
    void anInstallThatRanTheSqlVersionsOfV15AndV16StillValidatesAndMovesOn() throws SQLException {
        // N11 / R3: an install of v1.0.0 ran the SQL files; this release validates against the same record and keeps it
        createSchema("plinko_sqlv");
        flyway("plinko_sqlv", "14").migrate();
        Flyway.configure().dataSource(url("plinko_sqlv"), "owner", "owner-password-123").locations("classpath:db/original")
                .placeholders(Map.of("dbName", "plinko_sqlv", "appUser", "app")).target("16").validateOnMigrate(false).load().migrate();
        assertThat(recordedAs("plinko_sqlv")).isEqualTo(AS_SQL_FILES);
        assertThat(flyway("plinko_sqlv", null).migrate().success).isTrue();
        assertThat(recordedAs("plinko_sqlv")).as("still the SQL files, as v1.0.0 expects").isEqualTo(AS_SQL_FILES);
        previousRelease("plinko_sqlv").validate();
    }

    @Test
    void aFreshInstallRecordsV15AndV16AsTheSqlFilesThePreviousReleaseShips() throws SQLException {
        // R3: what runs is the batched backfill, what is recorded is the SQL file (type, name and checksum)
        assertThat(recordedAs("plinko")).isEqualTo(AS_SQL_FILES);
        previousRelease("plinko").validate();
    }

    @Test
    void anInstallThatRecordedThemAsJavaMigrationsHasThemPutBackAsTheSqlFiles() throws SQLException {
        // R3: 6d80fc8 to f01819f recorded V15/V16 as Java migrations, which v1.0.0's migration step refuses
        createSchema("plinko_jdbc");
        flyway("plinko_jdbc", null).migrate();
        try (Connection c = DriverManager.getConnection(url("plinko_jdbc"), "owner", "owner-password-123"); Statement st = c.createStatement()) {
            st.execute("UPDATE flyway_schema_history SET type = 'JDBC', script = 'db.migration.V15__daily_totals' WHERE version = '15'");
            st.execute("UPDATE flyway_schema_history SET type = 'JDBC', script = 'db.migration.V16__meter_pointer_and_indexes' WHERE version = '16'");
        }
        assertThatThrownBy(() -> previousRelease("plinko_jdbc").validate()).hasMessageContaining("not resolved locally");
        assertThat(flyway("plinko_jdbc", null).migrate().success).isTrue();
        assertThat(recordedAs("plinko_jdbc")).isEqualTo(AS_SQL_FILES);
        previousRelease("plinko_jdbc").validate();
    }

    @Test
    void aMigrationThatFailedHalfWayIsRepairedAndRunsAgain() throws SQLException {
        // R3: a batched backfill can't roll back on its own; Migrator repair removes its failed record
        createSchema("plinko_repair");
        flyway("plinko_repair", "14").migrate();
        try (Connection c = DriverManager.getConnection(url("plinko_repair"), "owner", "owner-password-123"); Statement st = c.createStatement()) {
            st.execute("INSERT INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at) "
                    + "VALUES ('repair-1', 'r', 'EUR', 0, FALSE, NOW(3), NOW(3))");
            st.execute("INSERT INTO game_session (id, player_id, pam_token, created_at, expires_at) "
                    + "SELECT 'repair-session', id, 'x', NOW(3), NOW(3) + INTERVAL 1 HOUR FROM player WHERE external_id = 'repair-1'");
            st.execute("CREATE TABLE round_daily_totals (broken INT)");    // V15 then fails writing its first batch
        }
        long player = JdbcClient.create(new DriverManagerDataSource(url("plinko_repair"), "owner", "owner-password-123"))
                .sql("SELECT id FROM player WHERE external_id = 'repair-1'").query(Long.class).single();
        decide("plinko_repair", pending("plinko_repair", player, "repair-session", 8, 0, "1.00", "EUR", Instant.now()), "0.50", null, null);
        assertThatThrownBy(() -> flyway("plinko_repair", null).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThatThrownBy(() -> flyway("plinko_repair", null).migrate()).hasMessageContaining("failed");
        try (Connection c = DriverManager.getConnection(url("plinko_repair"), "owner", "owner-password-123"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE round_daily_totals");    // the cause fixed by hand
        }
        flyway("plinko_repair", null).repair();
        assertThat(flyway("plinko_repair", null).migrate().success).isTrue();
        assertThat(recordedAs("plinko_repair")).isEqualTo(AS_SQL_FILES);
    }

    /** One wallet transaction as the database holds it: status, refused flag, in flight since, attempts. */
    private static String txState(String id) {
        return jdbc.sql("SELECT CONCAT(status, ' refused=', refused, ' inFlight=', in_flight_since IS NOT NULL, ' attempts=', attempts) "
                + "FROM wallet_transaction WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void aRefusalIsOnlyEverTheAnswerToASendOrForOneNeverSent() {
        // Review R4: the refused flag, guarded by wallet_transaction_guard (V29)
        GameRepository.Player p = player("refusals", "EUR");
        String s = session(p.id());
        String round = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        String credit = UUID.randomUUID().toString();
        game.insertTransaction(new GameRepository.Transaction(credit, round, p.id(), "CREDIT", new BigDecimal("1.00"), "PENDING", null, null, null, 0,
                null, null, "s".repeat(64)));

        // never sent (blocked or waiting before the wallet call): recorded as refused
        game.updateTransaction(credit, "FAILED", null, "NOT_SENT: blocked before sending", true);
        assertThat(txState(credit)).isEqualTo("FAILED refused=1 inFlight=0 attempts=1");
        // sent again: the mark clears the refusal before the wallet is called
        game.markSending(credit);
        assertThat(txState(credit)).isEqualTo("FAILED refused=0 inFlight=1 attempts=1");
        assertThatThrownBy(() -> owner("UPDATE wallet_transaction SET refused = 1 WHERE id = '" + credit + "'"))
                .hasMessageContaining("a send in flight cannot be refused");
        assertThatThrownBy(() -> owner("UPDATE wallet_transaction SET in_flight_since = NULL WHERE id = '" + credit + "'"))
                .hasMessageContaining("a send in flight ends only with its answer");
        // the wallet's answer: a refusal again
        game.updateTransaction(credit, "FAILED", null, "INVALID_TOKEN: refused", true);
        assertThat(txState(credit)).isEqualTo("FAILED refused=1 inFlight=0 attempts=2");

        // a failed row with an unknown outcome can't be turned into a refusal by hand
        game.markSending(credit);
        game.updateTransaction(credit, "FAILED", null, "CONNECTION_ERROR: timed out", false);
        assertThatThrownBy(() -> owner("UPDATE wallet_transaction SET refused = 1 WHERE id = '" + credit + "'"))
                .hasMessageContaining("a refusal is only the answer to a send, or for one never sent");
        // recorded as an answer without a send marked in flight: kept, but never as a refusal
        owner("UPDATE wallet_transaction SET status = 'FAILED', refused = 1, attempts = attempts + 1 WHERE id = '" + credit + "'");
        assertThat(txState(credit)).isEqualTo("FAILED refused=0 inFlight=0 attempts=4");

        // an older release (no in-flight mark) recording an answer on a refused row: no longer counted as refused
        game.markSending(credit);
        game.updateTransaction(credit, "FAILED", null, "INVALID_TOKEN: refused", true);
        owner("UPDATE wallet_transaction SET status = 'FAILED', error = '[refused] INVALID_TOKEN: x', attempts = attempts + 1 WHERE id = '" + credit + "'");
        assertThat(txState(credit)).isEqualTo("FAILED refused=0 inFlight=0 attempts=6");
        // only a failed transaction is a refusal: the flag goes with a confirmation
        game.markSending(credit);
        game.updateTransaction(credit, "FAILED", null, "INVALID_TOKEN: refused", true);
        owner("UPDATE wallet_transaction SET status = 'OK', attempts = attempts + 1 WHERE id = '" + credit + "'");
        assertThat(txState(credit)).isEqualTo("OK refused=0 inFlight=0 attempts=8");
        game.markSending(credit);
        assertThat(txState(credit)).as("a confirmed transaction is never sent again").isEqualTo("OK refused=0 inFlight=0 attempts=8");
    }

    @Test
    void theMigrationKeepsTheRefusalsTheErrorTextRecorded() throws SQLException {
        // R4: rows from before V29 carried the refusal as a "[refused] " prefix in their error
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement st = root.createStatement()) {
            st.execute("CREATE DATABASE plinko_r4");
            st.execute("GRANT ALL PRIVILEGES ON plinko_r4.* TO 'owner'@'%' WITH GRANT OPTION");
        }
        flyway("plinko_r4", "28").migrate();
        GameRepository before = new GameRepository(JdbcClient.create(appDataSource(url("plinko_r4"))), new SecretBox(TestFixtures.properties()),
                appDataSource(url("plinko_r4")));
        GameRepository.Player p = before.upsertPlayer("rules-r4", "r4", "EUR", new BigDecimal("100.00"));
        String s = UUID.randomUUID().toString();
        before.insertSession(new GameRepository.Session(s, p.id(), "token", Instant.now().plusSeconds(3600), null));
        List<String> ids = new ArrayList<>();
        for (String error : List.of("[refused] INVALID_TOKEN: refused", "CONNECTION_ERROR: timed out", "TRANSACTION_MISMATCH: other")) {
            String round = pending("plinko_r4", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
            String id = UUID.randomUUID().toString();
            ids.add(id);
            ownerOn("plinko_r4", "INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) VALUES ('"
                    + id + "', '" + round + "', " + p.id() + ", 'CREDIT', 1.00, 'PENDING', 0, NOW(3), NOW(3))",
                    "UPDATE wallet_transaction SET status = 'FAILED', error = '" + error + "', attempts = 1 WHERE id = '" + id + "'");
        }
        flyway("plinko_r4", null).migrate();
        List<Integer> refused = new ArrayList<>();
        for (String id : ids) {
            refused.add(JdbcClient.create(appDataSource(url("plinko_r4"))).sql("SELECT refused FROM wallet_transaction WHERE id = ?").param(id)
                    .query(Integer.class).single());
        }
        assertThat(refused).containsExactly(1, 0, 0);
    }

    /** A blocked round with an approved PAY settlement still to send, its money in a wallet transaction; returns the round. */
    private static String settlementToSend(String schema, long playerId, String session, Instant at, String txStatus, String error, int attempts,
                                           boolean refused) {
        String round = pending(schema, playerId, session, 8, 0, "1.00", "EUR", at);
        status(schema, round, RoundStatus.TAMPERED);
        String tx = UUID.randomUUID().toString();
        ownerOn(schema, "INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) VALUES ('"
                        + tx + "', '" + round + "', " + playerId + ", 'MANUAL_PAY', 1.00, 'PENDING', 0, NOW(3), NOW(3))",
                "INSERT INTO round_settlement (round_id, action, amount, tx_id, change_id, proposed_by, approved_by, reason, status, signature, "
                        + "created_at, updated_at) VALUES ('" + round + "', 'PAY', 1.00, '" + tx + "', 1, 'a', 'b', 'reason here', 'PENDING', "
                        + "REPEAT('s', 64), '" + ts(at) + "', '" + ts(at) + "')");
        if (txStatus != null) {
            ownerOn(schema, "UPDATE wallet_transaction SET status = '" + txStatus + "', error = '" + error + "', attempts = " + attempts
                    + (refused ? ", refused = 1" : "") + " WHERE id = '" + tx + "'");
        }
        return round;
    }

    @Test
    void stoppedSettlementsNeverKeepANewerOneFromBeingSent() {
        // Review R11: more stopped settlements than one batch of the background retry (50), all older than a new one
        GameRepository.Player p = player("stopped-settlements", "EUR");
        String s = session(p.id());
        SettlementRepository settlements = new SettlementRepository(jdbc);
        Instant old = Instant.now().minus(Duration.ofDays(2));
        List<String> stopped = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            String round = settlementToSend("plinko", p.id(), s, old.plusSeconds(i), null, null, 0, false);
            assertThat(settlements.markStopped(round)).isTrue();
            stopped.add(round);
        }
        // stopped_at goes with updated_at: make them the oldest again, as stopped settlements sit for days
        owner("UPDATE round_settlement SET updated_at = created_at WHERE round_id IN ('" + String.join("', '", stopped) + "')");
        String fresh = settlementToSend("plinko", p.id(), s, Instant.now().minusSeconds(120), null, null, 0, false);

        List<String> batch = settlements.pending(Instant.now().minusSeconds(60), 50).stream().map(SettlementRepository.Settlement::roundId).toList();
        assertThat(batch).contains(fresh).doesNotContainAnyElementsOf(stopped);
        assertThat(settlements.stopped(1000)).extracting(SettlementRepository.Settlement::roundId).containsAll(stopped).doesNotContain(fresh);
        assertThat(settlements.markStopped(stopped.get(0))).as("once").isFalse();
        // a person sends one again: it is retried in the background again
        settlements.resume(stopped.get(0));
        assertThat(settlements.pending(Instant.now().minusSeconds(60), 1000)).extracting(SettlementRepository.Settlement::roundId).contains(stopped.get(0));
    }

    @Test
    void theMigrationMarksTheSettlementsAlreadyStopped() throws SQLException {
        // R11: before V30 a settlement was stopped by its transaction's state; those must not be sent again by themselves
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement st = root.createStatement()) {
            st.execute("CREATE DATABASE plinko_r11");
            st.execute("GRANT ALL PRIVILEGES ON plinko_r11.* TO 'owner'@'%' WITH GRANT OPTION");
        }
        flyway("plinko_r11", "29").migrate();
        GameRepository before = new GameRepository(JdbcClient.create(appDataSource(url("plinko_r11"))), new SecretBox(TestFixtures.properties()),
                appDataSource(url("plinko_r11")));
        GameRepository.Player p = before.upsertPlayer("rules-r11", "r11", "EUR", new BigDecimal("100.00"));
        String s = UUID.randomUUID().toString();
        before.insertSession(new GameRepository.Session(s, p.id(), "token", Instant.now().plusSeconds(3600), null));
        Instant at = Instant.now().minus(Duration.ofHours(3));
        Map<String, String> rounds = new LinkedHashMap<>();
        rounds.put(settlementToSend("plinko_r11", p.id(), s, at, "FAILED", "INVALID_TOKEN: refused", 1, true), "refused");
        rounds.put(settlementToSend("plinko_r11", p.id(), s, at, "FAILED", "TRANSACTION_MISMATCH: other", 1, false), "mismatch");
        rounds.put(settlementToSend("plinko_r11", p.id(), s, at, "FAILED", "TIMEOUT: no answer", 10, false), "attempts used up");
        rounds.put(settlementToSend("plinko_r11", p.id(), s, at, "FAILED", "TIMEOUT: no answer", 3, false), "still retried");
        rounds.put(settlementToSend("plinko_r11", p.id(), s, at, null, null, 0, false), "not sent yet");
        flyway("plinko_r11", null).migrate();
        Map<String, Boolean> marked = new LinkedHashMap<>();
        rounds.forEach((round, what) -> marked.put(what, JdbcClient.create(appDataSource(url("plinko_r11")))
                .sql("SELECT stopped_at IS NOT NULL FROM round_settlement WHERE round_id = ?").param(round).query(Boolean.class).single()));
        assertThat(marked).containsExactly(Map.entry("refused", true), Map.entry("mismatch", true), Map.entry("attempts used up", true),
                Map.entry("still retried", false), Map.entry("not sent yet", false));
    }

    /** A round on {@code schema} played at {@code at}, decided and signed with {@code signer} (or a forged signature), then COMPLETED. */
    private static String signedRound(String schema, GameRepository on, com.plinko.bo.game.RoundSigner signer, long playerId, String session,
                                      Instant at, boolean forged) {
        String id = pending(schema, playerId, session, 8, 0, "1.00", "EUR", at);
        BigDecimal one = new BigDecimal("1.00");
        BigDecimal win = new BigDecimal("1.10");
        String signature = forged ? signer.sign("other", playerId, 8, 0, one, 1, one, win, "EUR", "4", "1.1") : signer.sign(id, playerId, 8, 0, one, 1,
                one, win, "EUR", "4", "1.1");
        on.saveOutcome(id, "4", "1.1", null, null, win, signature, RoundStatus.DEBITED);
        on.updateRoundStatus(id, forged ? RoundStatus.TAMPERED : RoundStatus.COMPLETED, null);
        return id;
    }

    @Test
    void theReSigningJobKeepsEvidenceAsideRescansForLateConfirmationsAndStopsWhenNothingIsLeft() throws SQLException {
        // Review R7 and R8, on a schema of its own (so "nothing left" is this test's own state)
        createSchema("plinko_r7");
        flyway("plinko_r7", null).migrate();
        DataSource ds = appDataSource(url("plinko_r7"));
        JdbcClient on = JdbcClient.create(ds);
        GameRepository g = new GameRepository(on, new SecretBox(TestFixtures.properties()), ds);
        com.plinko.bo.game.RoundSigner original = new com.plinko.bo.game.RoundSigner(TestFixtures.properties());
        com.plinko.common.Integrity originalIntegrity = new com.plinko.common.Integrity(TestFixtures.properties());
        GameRepository.Player p = g.upsertPlayer("rules-r7", "r7", "EUR", new BigDecimal("100.00"));
        String s = UUID.randomUUID().toString();
        g.insertSession(new GameRepository.Session(s, p.id(), "token", Instant.now().plusSeconds(3600), null));
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        // the suspected leak: everything this test writes now is before it, except the round played after it
        Instant leak = Instant.now().plus(Duration.ofMinutes(30));

        String genuine = signedRound("plinko_r7", g, original, p.id(), s, twoDaysAgo, false);
        String evidence = signedRound("plinko_r7", g, original, p.id(), s, twoDaysAgo, true);        // fails its check, kept TAMPERED
        String afterLeak = signedRound("plinko_r7", g, original, p.id(), s, leak.plus(Duration.ofMinutes(30)), false);   // may be forged
        // a round whose credit is still owed when the job passes it, and confirmed only later
        String owed = pending("plinko_r7", p.id(), s, 8, 0, "1.00", "EUR", twoDaysAgo);
        BigDecimal one = new BigDecimal("1.00");
        BigDecimal win = new BigDecimal("1.10");
        g.saveOutcome(owed, "4", "1.1", null, null, win, original.sign(owed, p.id(), 8, 0, one, 1, one, win, "EUR", "4", "1.1"), RoundStatus.DEBITED);
        g.updateRoundStatus(owed, RoundStatus.CREDIT_PENDING, null);
        String credit = new com.plinko.bo.game.WalletSigner(originalIntegrity).creditId(owed);
        g.insertTransaction(new GameRepository.Transaction(credit, owed, p.id(), "CREDIT", win, "PENDING", null, null, null, 0, null, null,
                originalIntegrity.sign("credit", credit, owed, p.externalId(), win, "EUR")));

        String lists = "r1=" + "r".repeat(40);
        com.plinko.common.KeyRing rotated = new com.plinko.common.KeyRing(TestFixtures.properties(), lists, "d1=" + "d".repeat(40), "e1=" + "e".repeat(40));
        ResignedSignatures resigned = new ResignedSignatures(on);
        JobState state = new JobState(on, g);
        com.plinko.bo.security.LegacyResigner job = new com.plinko.bo.security.LegacyResigner(on, g, new com.plinko.bo.game.RoundSigner(rotated),
                new com.plinko.common.Integrity(rotated), rotated, resigned, state, leak.toString(), true);
        assertThat(job.left()).as("R8: nothing counted before the job ran").isEmpty();
        // switched off (LEGACY_RESIGN=false, the leak runbook's first step): it signs nothing
        new com.plinko.bo.security.LegacyResigner(on, g, new com.plinko.bo.game.RoundSigner(rotated), new com.plinko.common.Integrity(rotated), rotated,
                resigned, state, "", false).run();
        assertThat(resigned.of(ResignedSignatures.ROUNDS, genuine)).as("LEGACY_RESIGN=false").isEmpty();
        job.run();

        // #1: the round that fails its check and the one made after the suspected leak are evidence, never signed again
        assertThat(resigned.of(ResignedSignatures.ROUNDS, genuine)).get().asString().startsWith("r1.");
        assertThat(resigned.of(ResignedSignatures.ROUNDS, owed)).isPresent();
        assertThat(resigned.of(ResignedSignatures.ROUNDS, evidence)).isEmpty();
        assertThat(resigned.of(ResignedSignatures.ROUNDS, afterLeak)).as("#3: made after the suspected leak").isEmpty();
        assertThat(resigned.evidence(ResignedSignatures.ROUNDS)).containsEntry(ResignedSignatures.FAILS_CHECK, 1L)
                .containsEntry(ResignedSignatures.AFTER_LEAK, 1L);
        assertThat(resigned.left(ResignedSignatures.ROUNDS)).as("evidence doesn't count as left").isZero();
        assertThat(resigned.of(ResignedSignatures.TRANSACTIONS, credit)).as("money still to move is never signed again").isEmpty();
        // R8: counted by the job at the end of its sweep, kept with its time
        com.plinko.bo.security.LegacyResigner.Left counted = job.left().orElseThrow();
        assertThat(counted.tables()).containsEntry(ResignedSignatures.ROUNDS, 0L).containsEntry(ResignedSignatures.TRANSACTIONS, 1L);
        assertThat(counted.done()).isFalse();
        assertThat(counted.countedAt()).isAfter(Instant.now().minusSeconds(60));

        // #2: the credit is confirmed after the sweep passed it; the next sweep starts again from the beginning and finds it
        g.updateTransaction(credit, "OK", "ref", null);
        g.updateRoundStatus(owed, RoundStatus.COMPLETED, null);
        job.run();
        assertThat(resigned.of(ResignedSignatures.TRANSACTIONS, credit)).get().asString().startsWith("r1.");
        // #4: nothing left: counted as none, and the job stops for good
        assertThat(job.left().orElseThrow().done()).isTrue();
        assertThat(job.left().orElseThrow().tables().values()).containsOnly(0L);
        String later = signedRound("plinko_r7", g, original, p.id(), s, twoDaysAgo, false);
        job.run();
        assertThat(resigned.of(ResignedSignatures.ROUNDS, later)).as("the job has stopped").isEmpty();

    }

    @Test
    void aCutoffIsRefusedWhileMoneyStillToMoveIsSignedWithTheOriginalKey() throws SQLException {
        // Review R6: LEGACY_KEY_UNTIL an hour ahead, set in the same deploy as the key lists, during a wallet outage
        createSchema("plinko_r6");
        flyway("plinko_r6", null).migrate();
        DataSource ds = appDataSource(url("plinko_r6"));
        JdbcClient on = JdbcClient.create(ds);
        GameRepository g = new GameRepository(on, new SecretBox(TestFixtures.properties()), ds);
        com.plinko.bo.game.RoundSigner original = new com.plinko.bo.game.RoundSigner(TestFixtures.properties());
        com.plinko.common.Integrity originalIntegrity = new com.plinko.common.Integrity(TestFixtures.properties());
        GameRepository.Player p = g.upsertPlayer("rules-r6", "r6", "EUR", new BigDecimal("100.00"));
        String s = UUID.randomUUID().toString();
        g.insertSession(new GameRepository.Session(s, p.id(), "token", Instant.now().plusSeconds(3600), null));
        String owed = pending("plinko_r6", p.id(), s, 8, 0, "1.00", "EUR", Instant.now().minus(Duration.ofHours(2)));
        BigDecimal one = new BigDecimal("1.00");
        BigDecimal win = new BigDecimal("1.10");
        g.saveOutcome(owed, "4", "1.1", null, null, win, original.sign(owed, p.id(), 8, 0, one, 1, one, win, "EUR", "4", "1.1"), RoundStatus.DEBITED);
        g.updateRoundStatus(owed, RoundStatus.CREDIT_PENDING, null);
        String credit = new com.plinko.bo.game.WalletSigner(originalIntegrity).creditId(owed);
        g.insertTransaction(new GameRepository.Transaction(credit, owed, p.id(), "CREDIT", win, "PENDING", null, null, null, 0, null, null,
                originalIntegrity.sign("credit", credit, owed, p.externalId(), win, "EUR")));
        g.updateTransaction(credit, "FAILED", null, "CONNECTION_ERROR: timed out");

        com.plinko.common.KeyRing listsAndCutoff = new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        com.plinko.repo.IntegrityLedger ledger = org.mockito.Mockito.mock(com.plinko.repo.IntegrityLedger.class);
        com.plinko.bo.security.IntegrityBootstrap bootstrap = new com.plinko.bo.security.IntegrityBootstrap(null, null, null,
                org.mockito.Mockito.mock(com.plinko.bo.audit.AuditService.class), ledger, listsAndCutoff, on);
        assertThat(bootstrap.moneyOnOriginalKey()).containsEntry("credits and refunds still owed", 1L).containsEntry("settlements waiting on the wallet", 0L);
        assertThatThrownBy(bootstrap::checkLegacyRetired).as("refused although the cutoff is still ahead")
                .hasMessageContaining("money still to move depends on ROUND_SIGNING_KEY").hasMessageContaining("1 credits and refunds still owed");
        // paid: the cutoff can be set
        g.updateTransaction(credit, "OK", "ref", null);
        g.updateRoundStatus(owed, RoundStatus.COMPLETED, null);
        assertThat(bootstrap.moneyOnOriginalKey().values()).containsOnly(0L);
        bootstrap.checkLegacyRetired();
        // without a cutoff nothing is checked
        new com.plinko.bo.security.IntegrityBootstrap(null, null, null, null, ledger,
                new com.plinko.common.KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40), "e1=" + "e".repeat(40)), on)
                .checkLegacyRetired();
    }

    private static Flyway flyway(String schema, String target) {
        // as the release runs them (Migrator)
        var config = com.plinko.migration.Migrator.configure(url(schema), "owner", "owner-password-123", schema, "app");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static String url(String schema) {
        return MYSQL.getJdbcUrl().replaceFirst("/plinko(\\?|$)", "/" + schema + "$1");
    }

    private static DataSource appDataSource(String url) {
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "connectionTimeZone=UTC", "app", "app-password-123");
    }

    /** Runs statements as the schema owner (every privilege: only the triggers stand in the way). */
    private static void ownerOn(String schema, String... sql) {
        try (Connection c = DriverManager.getConnection(url(schema) + (url(schema).contains("?") ? "&" : "?") + "connectionTimeZone=UTC",
                "owner", "owner-password-123"); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void owner(String... sql) {
        ownerOn("plinko", sql);
    }

    private static GameRepository.Player player(String name, String currency) {
        return game.upsertPlayer("rules-" + name + "-" + UUID.randomUUID(), name, currency, new BigDecimal("100.00"));
    }

    private static String session(long playerId) {
        String id = UUID.randomUUID().toString();
        game.insertSession(new GameRepository.Session(id, playerId, "token", Instant.now().plusSeconds(3600), null));
        return id;
    }

    /** Inserts a PENDING round played at {@code at}, as the owner (so its time can be in the past). */
    private static String pending(String schema, long playerId, String session, int rows, int risk, String bet, String currency, Instant at) {
        String id = UUID.randomUUID().toString();
        ownerOn(schema, "INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at) "
                + "VALUES ('" + id + "', " + playerId + ", '" + session + "', " + rows + ", " + risk + ", " + bet + ", 1, " + bet + ", '" + currency
                + "', 'PENDING', '" + ts(at) + "', '" + ts(at) + "')");
        return id;
    }

    /** Gives a round its signed outcome (DEBITED). */
    private static void decide(String schema, String id, String win, String bonus, String meterPrev) {
        ownerOn(schema, "UPDATE game_round SET slots = '4', multipliers = '1.1', total_win = " + win + ", signature = REPEAT('s', 64), status = 'DEBITED'"
                + (bonus == null ? "" : ", bonus = '" + bonus + "'") + (meterPrev == null ? "" : ", meter_prev = '" + meterPrev + "'")
                + " WHERE id = '" + id + "'");
    }

    private static void status(String schema, String id, String status) {
        ownerOn(schema, "UPDATE game_round SET status = '" + status + "' WHERE id = '" + id + "'");
    }

    private static String ts(Instant at) {
        return Timestamp.from(at).toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ');
    }

    // ---------------------------------------------------------------- one test per trigger

    /** A statement the trigger must refuse with its message, or allow. */
    private record Case(String sql, String refusedWith) {
        static Case refused(String sql, String message) {
            return new Case(sql, message);
        }

        static Case allowed(String sql) {
            return new Case(sql, null);
        }
    }

    /**
     * Each integrity trigger in the database, refusing what it guards and allowing what the game does. A trigger with no
     * cases here fails, so a new trigger can't go untested.
     */
    @TestFactory
    Stream<DynamicTest> everyIntegrityTriggerRefusesWhatItGuardsAndAllowsTheGamesOwnWrites() {
        GameRepository.Player p = player("triggers", "EUR");
        String s = session(p.id());
        String pending = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        String decided = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        decide("plinko", decided, "0.50", "meter:1/10", "trig-prev-" + decided);
        String done = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        decide("plinko", done, "0.50", null, null);
        status("plinko", done, RoundStatus.COMPLETED);
        String tampered = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        status("plinko", tampered, RoundStatus.TAMPERED);
        String tx = UUID.randomUUID().toString();
        String okTx = UUID.randomUUID().toString();
        owner("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at, signature) VALUES "
                        + "('" + tx + "', '" + decided + "', " + p.id() + ", 'CREDIT', 0.50, 'PENDING', 0, NOW(3), NOW(3), REPEAT('s', 64)), "
                        + "('" + okTx + "', '" + done + "', " + p.id() + ", 'DEBIT', 1.00, 'PENDING', 0, NOW(3), NOW(3), NULL)",
                "UPDATE wallet_transaction SET status = 'OK' WHERE id = '" + okTx + "'");
        String expired = UUID.randomUUID().toString();
        owner("INSERT INTO game_session (id, player_id, pam_token, created_at, expires_at) VALUES ('" + expired + "', " + p.id() + ", 'x', "
                + "UTC_TIMESTAMP(3) - INTERVAL 40 DAY, UTC_TIMESTAMP(3) - INTERVAL 31 DAY)");
        String young = "b".repeat(63) + "1";
        String old = "b".repeat(63) + "2";
        owner("INSERT INTO used_launch_token (token_hash, used_at) VALUES ('" + young + "', UTC_TIMESTAMP(3)), ('" + old + "', UTC_TIMESTAMP(3) - INTERVAL 31 DAY)");
        String settled = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        status("plinko", settled, RoundStatus.TAMPERED);
        String toSettle = pending("plinko", p.id(), s, 8, 0, "1.00", "EUR", Instant.now());
        status("plinko", toSettle, RoundStatus.TAMPERED);
        String settlement = "INSERT INTO round_settlement (round_id, action, amount, change_id, proposed_by, approved_by, reason, status, signature, "
                + "created_at, updated_at) VALUES ('%s', '%s', %s, 1, 'a', 'b', 'reason here', 'PENDING', REPEAT('s', 64), NOW(3), NOW(3))";

        owner(settlement.formatted(settled, "PAY", "5"));
        Map<String, List<Case>> cases = new LinkedHashMap<>();
        cases.put("game_round_insert_guard", List.of(
                Case.refused("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, total_win, "
                        + "created_at, updated_at) VALUES (UUID(), " + p.id() + ", '" + s + "', 8, 0, 1, 1, 1, 'EUR', 'COMPLETED', 9, NOW(3), NOW(3))",
                        "must start as PENDING"),
                Case.refused("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, meter_prev, "
                        + "created_at, updated_at) VALUES (UUID(), " + p.id() + ", '" + s + "', 8, 0, 1, 1, 1, 'EUR', 'PENDING', 'x', NOW(3), NOW(3))",
                        "must start as PENDING"),
                Case.allowed("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, "
                        + "created_at, updated_at) VALUES (UUID(), " + p.id() + ", '" + s + "', 8, 0, 1, 1, 1, 'EUR', 'PENDING', NOW(3), NOW(3))")));
        cases.put("game_round_guard", List.of(
                Case.refused("UPDATE game_round SET bet = 5 WHERE id = '" + pending + "'", "the bet of a round cannot be changed"),
                Case.refused("UPDATE game_round SET max_win = 99999 WHERE id = '" + pending + "'", "the bet of a round cannot be changed"),
                Case.refused("UPDATE game_round SET odds_ref = 99 WHERE id = '" + pending + "'", "the bet of a round cannot be changed"),
                Case.refused("UPDATE game_round SET created_at = created_at - INTERVAL 1 DAY WHERE id = '" + pending + "'",
                        "the bet of a round cannot be changed"),
                Case.refused("UPDATE game_round SET total_win = 99 WHERE id = '" + decided + "'", "a decided outcome cannot be changed"),
                Case.refused("UPDATE game_round SET bonus = 'meter:9/10' WHERE id = '" + decided + "'", "a decided outcome cannot be changed"),
                Case.refused("UPDATE game_round SET meter_prev = NULL WHERE id = '" + decided + "'", "a decided outcome cannot be changed"),
                Case.refused("UPDATE game_round SET status = 'CREDIT_PENDING' WHERE id = '" + done + "'", "a finished round cannot be reopened"),
                Case.refused("UPDATE game_round SET status = 'ROLLBACK_PENDING' WHERE id = '" + decided + "'", "status change not allowed"),
                Case.refused("UPDATE game_round SET status = 'COMPLETED' WHERE id = '" + pending + "'", "status change not allowed"),
                Case.refused("UPDATE game_round SET status = 'DEBITED' WHERE id = '" + pending + "'", "only be settled with a signed outcome"),
                Case.allowed("UPDATE game_round SET error = 'retrying' WHERE id = '" + done + "'"),
                Case.allowed("UPDATE game_round SET status = 'CREDIT_PENDING' WHERE id = '" + decided + "'"),
                Case.allowed("UPDATE game_round SET status = 'COMPLETED' WHERE id = '" + decided + "'")));
        cases.put("wallet_transaction_insert_guard", List.of(
                Case.refused("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) "
                        + "VALUES (UUID(), '" + pending + "', " + p.id() + ", 'DEBIT', 1, 'OK', 0, NOW(3), NOW(3))", "must start as PENDING"),
                Case.allowed("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) "
                        + "VALUES (UUID(), '" + pending + "', " + p.id() + ", 'DEBIT', 1, 'PENDING', 0, NOW(3), NOW(3))")));
        cases.put("wallet_transaction_guard", List.of(
                Case.refused("UPDATE wallet_transaction SET amount = 500 WHERE id = '" + tx + "'", "only the status of a transaction can change"),
                Case.refused("UPDATE wallet_transaction SET type = 'DEBIT' WHERE id = '" + tx + "'", "only the status of a transaction can change"),
                Case.refused("UPDATE wallet_transaction SET signature = REPEAT('f', 64) WHERE id = '" + tx + "'",
                        "only the status of a transaction can change"),
                Case.refused("UPDATE wallet_transaction SET status = 'FAILED' WHERE id = '" + okTx + "'", "a confirmed transaction cannot be reopened"),
                Case.refused("UPDATE wallet_transaction SET status = 'DONE' WHERE id = '" + tx + "'", "status change not allowed"),
                Case.allowed("UPDATE wallet_transaction SET status = 'FAILED', attempts = attempts + 1, next_retry_at = NOW(3) WHERE id = '" + tx + "'"),
                Case.refused("UPDATE wallet_transaction SET status = 'PENDING' WHERE id = '" + tx + "'", "status change not allowed"),
                Case.allowed("UPDATE wallet_transaction SET status = 'OK', pam_reference = 'ref' WHERE id = '" + tx + "'")));
        cases.put("player_guard", List.of(
                Case.refused("UPDATE player SET external_id = 'someone-else' WHERE id = " + p.id(), "the PAM player id cannot be changed"),
                Case.allowed("UPDATE player SET last_balance = 7, blocked = TRUE, meter_round = 'x' WHERE id = " + p.id())));
        cases.put("game_session_guard", List.of(
                Case.refused("UPDATE game_session SET pam_token = 'stolen' WHERE id = '" + s + "'", "only the expiry of a session can change"),
                Case.refused("UPDATE game_session SET player_id = player_id + 1 WHERE id = '" + s + "'", "only the expiry of a session can change"),
                Case.allowed("UPDATE game_session SET expires_at = NOW(3) + INTERVAL 1 HOUR WHERE id = '" + s + "'")));
        cases.put("round_settlement_insert_guard", List.of(
                Case.refused(settlement.formatted(done, "PAY", "5"), "only a blocked round can be settled"),
                Case.refused(settlement.formatted(tampered, "STEAL", "5"), "only a blocked round can be settled"),
                Case.refused(settlement.formatted(tampered, "PAY", "-5"), "only a blocked round can be settled"),
                Case.allowed(settlement.formatted(toSettle, "REFUND", "1"))));
        cases.put("round_settlement_guard", List.of(
                Case.refused("UPDATE round_settlement SET amount = 500 WHERE round_id = '" + settled + "'", "only a pending settlement can be marked done"),
                Case.refused("UPDATE round_settlement SET status = 'LOST' WHERE round_id = '" + settled + "'", "only a pending settlement can be marked done"),
                Case.allowed("UPDATE round_settlement SET status = 'DONE' WHERE round_id = '" + settled + "'"),
                Case.refused("UPDATE round_settlement SET status = 'PENDING' WHERE round_id = '" + settled + "'",
                        "only a pending settlement can be marked done")));
        cases.put("used_launch_token_cleanup_guard", List.of(
                Case.refused("DELETE FROM used_launch_token WHERE token_hash = '" + young + "'", "only tokens used more than 30 days ago"),
                Case.allowed("DELETE FROM used_launch_token WHERE token_hash = '" + old + "'")));
        cases.put("game_session_cleanup_guard", List.of(
                Case.refused("DELETE FROM game_session WHERE id = '" + s + "'", "only sessions expired more than 30 days ago"),
                Case.allowed("DELETE FROM game_session WHERE id = '" + expired + "'")));

        // read as the owner: the application's user can't see the triggers (no TRIGGER privilege)
        List<String> triggers = JdbcClient.create(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "owner", "owner-password-123"))
                .sql("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = 'plinko' ORDER BY TRIGGER_NAME")
                .query(String.class).list();
        assertThat(triggers).as("the triggers this test knows").containsExactlyInAnyOrderElementsOf(cases.keySet());
        return triggers.stream().map(trigger -> DynamicTest.dynamicTest(trigger, () -> {
            for (Case c : cases.get(trigger)) {
                if (c.refusedWith() == null) {
                    owner(c.sql());
                } else {
                    assertThatThrownBy(() -> owner(c.sql())).as(c.sql()).hasMessageContaining(c.refusedWith());
                }
            }
        }));
    }

    // ---------------------------------------------------------------- Finance over hand-built rounds

    @Test
    void financeFiguresCountDecidedRoundsWithTheirFreeBallsAndLeaveOutRefundsAndBlockedRounds() {
        // in its own currency, so other tests' rounds don't mix in
        String cur = "SEK";
        GameRepository.Player p1 = player("fin1", cur);
        GameRepository.Player p2 = player("fin2", cur);
        String s1 = session(p1.id());
        String s2 = session(p2.id());
        Instant past = Instant.now().minus(Duration.ofDays(2));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate pastDay = today.minusDays(2);
        Map<String, String> paid = new HashMap<>();     // completed rounds: their credit amount

        // two days ago: a plain round, a round whose win includes free balls, a refunded round and a blocked one
        String plain = pending("plinko", p1.id(), s1, 8, 0, "1.00", cur, past);
        decide("plinko", plain, "0.50", null, null);
        status("plinko", plain, RoundStatus.COMPLETED);
        paid.put(plain, "0.50");
        String free = pending("plinko", p1.id(), s1, 12, 2, "2.00", cur, past);
        decide("plinko", free, "30.40", "free:1.50|4|2,1.50|5|1.1;meter:0/10,0.000000,start", null);
        status("plinko", free, RoundStatus.COMPLETED);
        paid.put(free, "30.40");
        String refunded = pending("plinko", p2.id(), s2, 8, 0, "5.00", cur, past);
        status("plinko", refunded, RoundStatus.ROLLBACK_PENDING);
        status("plinko", refunded, RoundStatus.CANCELLED);
        String blocked = pending("plinko", p2.id(), s2, 8, 0, "1.00", cur, past);
        decide("plinko", blocked, "900.00", null, null);
        status("plinko", blocked, RoundStatus.TAMPERED);
        // today: a win still owed, a decided round not yet paid (paid below), and a refund still pending
        String owed = pending("plinko", p2.id(), s2, 8, 1, "1.00", cur, Instant.now());
        decide("plinko", owed, "2.00", null, null);
        status("plinko", owed, RoundStatus.CREDIT_PENDING);
        String debited = pending("plinko", p2.id(), s2, 8, 0, "0.40", cur, Instant.now());
        decide("plinko", debited, "0.00", null, null);
        String refunding = pending("plinko", p1.id(), s1, 8, 0, "3.00", cur, Instant.now());
        status("plinko", refunding, RoundStatus.ROLLBACK_PENDING);
        // the wallet's side of the completed rounds: one credit wrongly confirmed for more than the round's win
        String wrong = pending("plinko", p1.id(), s1, 8, 0, "1.00", cur, past);
        decide("plinko", wrong, "1.00", null, null);
        status("plinko", wrong, RoundStatus.COMPLETED);
        paid.put(wrong, "1.50");
        paid.forEach((round, credit) -> {
            String bet = jdbc.sql("SELECT total_bet FROM game_round WHERE id = ?").param(round).query(String.class).single();
            owner("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) VALUES "
                            + "(UUID(), '" + round + "', " + p1.id() + ", 'DEBIT', " + bet + ", 'PENDING', 0, NOW(3), NOW(3)), "
                            + "(UUID(), '" + round + "', " + p1.id() + ", 'CREDIT', " + credit + ", 'PENDING', 0, NOW(3), NOW(3))",
                    "UPDATE wallet_transaction SET status = 'OK' WHERE round_id = '" + round + "'");
        });

        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        // Finance reads only the daily totals: a closed day rebuilt, and today's at start-up (or every hour)
        new TransactionTemplate(new DataSourceTransactionManager(app)).executeWithoutResult(x -> {
            stats.rebuildDay(pastDay);
            stats.rebuildDay(today);
        });
        FinanceRepository finance = new FinanceRepository(jdbc, ReportingZone.utc());
        // R2: the round between its bet and its payout (DEBITED) is not in a rebuild; it is counted by its increment, in the
        // same transaction as its payout's answer, and the minute job adds it
        assertThat(only(cur, finance.totals(pastDay, today))).singleElement().satisfies(l -> assertLine(l, 4, 2, "5.00", "33.90"));
        TransactionTemplate settle = new TransactionTemplate(new DataSourceTransactionManager(app));
        settle.executeWithoutResult(x -> {
            game.updateRoundStatus(debited, RoundStatus.COMPLETED, null);
            stats.addDelta(game.findRound(debited).orElseThrow(), 1);
        });
        settle.execute(x -> stats.foldDeltas(100_000));

        // the wrongly paid round is part of the figures (it is COMPLETED): 5 rounds, bets 5.40, wins 33.90
        assertThat(only(cur, finance.totals(pastDay, today))).singleElement().satisfies(l -> assertLine(l, 5, 2, "5.40", "33.90"));
        List<FinanceRepository.Line> daily = only(cur, finance.daily(pastDay, today));
        assertThat(daily).extracting(FinanceRepository.Line::label).containsExactly(pastDay.toString(), today.toString());
        assertLine(daily.get(0), 3, 1, "4.00", "31.90");
        assertLine(daily.get(1), 2, 1, "1.40", "2.00");
        Map<String, FinanceRepository.Line> byRisk = byLabel(only(cur, finance.byRisk(pastDay, today)));
        assertLine(byRisk.get("Low"), 3, 0, "2.40", "1.50");
        assertLine(byRisk.get("Mid"), 1, 0, "1.00", "2.00");
        assertLine(byRisk.get("High"), 1, 0, "2.00", "30.40");
        Map<String, FinanceRepository.Line> byRows = byLabel(only(cur, finance.byRows(pastDay, today)));
        assertLine(byRows.get("8 rows"), 4, 0, "3.40", "3.50");
        assertLine(byRows.get("12 rows"), 1, 0, "2.00", "30.40");
        List<FinanceRepository.Line> winners = only(cur, finance.players(pastDay, today, true, 1000));
        assertThat(winners).extracting(FinanceRepository.Line::label).containsExactly(p1.externalId() + " (fin1)", p2.externalId() + " (fin2)");
        assertLine(winners.get(0), 3, 1, "4.00", "31.90");
        assertLine(winners.get(1), 2, 1, "1.40", "2.00");
        Map<String, FinanceRepository.Line> open = byLabel(only(cur, finance.open(pastDay, today)));
        assertThat(open.keySet()).containsExactlyInAnyOrder(RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING, RoundStatus.TAMPERED);
        assertLine(open.get(RoundStatus.CREDIT_PENDING), 1, 1, "1.00", "2.00");
        assertLine(open.get(RoundStatus.ROLLBACK_PENDING), 1, 1, "3.00", "0.00");
        assertLine(open.get(RoundStatus.TAMPERED), 1, 1, "1.00", "900.00");

        // the wallet check: only the completed round whose confirmed credit differs from its win
        List<FinanceRepository.Reconciled> checked = finance.reconcileAfter(Instant.EPOCH, "", Instant.now().plusSeconds(60), 10_000).stream()
                .filter(r -> paid.containsKey(r.id())).toList();
        assertThat(checked).extracting(FinanceRepository.Reconciled::id).containsExactlyInAnyOrderElementsOf(paid.keySet());
        assertThat(checked).filteredOn(FinanceRepository.Reconciled::differs).extracting(FinanceRepository.Reconciled::id).containsExactly(wrong);
    }

    private static List<FinanceRepository.Line> only(String currency, List<FinanceRepository.Line> lines) {
        return lines.stream().filter(l -> currency.equals(l.currency())).toList();
    }

    private static Map<String, FinanceRepository.Line> byLabel(List<FinanceRepository.Line> lines) {
        Map<String, FinanceRepository.Line> map = new HashMap<>();
        lines.forEach(l -> map.put(l.label(), l));
        return map;
    }

    private static void assertLine(FinanceRepository.Line line, long rounds, long players, String bet, String win) {
        assertThat(line).as("line").isNotNull();
        assertThat(line.rounds()).as("%s rounds", line.label()).isEqualTo(rounds);
        if (players > 0) {
            assertThat(line.players()).as("%s players", line.label()).isEqualTo(players);
        }
        assertThat(line.bet()).as("%s bets", line.label()).isEqualByComparingTo(bet);
        assertThat(line.win()).as("%s wins", line.label()).isEqualByComparingTo(win);
    }

    // ---------------------------------------------------------------- the round lock with two connection pools

    @Test
    void theRoundLockHoldsAcrossTwoConnectionPools() throws Exception {
        GameRepository.Player p = player("pools", "EUR");
        try (HikariDataSource first = pool("first"); HikariDataSource second = pool("second")) {
            GameRepository a = new GameRepository(JdbcClient.create(first), new SecretBox(TestFixtures.properties()), first);
            GameRepository b = new GameRepository(JdbcClient.create(second), new SecretBox(TestFixtures.properties()), second);
            // a claim taken through one pool is seen by the other, and only its owner's id releases it, through either
            assertThat(a.claimRound(p.id(), "claim-a", 30)).isTrue();
            assertThat(b.claimRound(p.id(), "claim-b", 30)).isFalse();
            b.releaseRound(p.id(), "claim-b");
            assertThat(a.claimRound(p.id(), "claim-c", 30)).isFalse();
            b.releaseRound(p.id(), "claim-a");
            assertThat(b.claimRound(p.id(), "claim-b", 30)).isTrue();
            b.settlePlayer(p.id(), new BigDecimal("50.00"), "claim-b");

            // instances racing: never two rounds of one player at once, and both pools get turns
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger most = new AtomicInteger();
            int[] won = new int[2];
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 12; t++) {
                int side = t % 2;
                GameRepository repository = side == 0 ? a : b;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 25; i++) {
                        String claim = UUID.randomUUID().toString();
                        if (repository.claimRound(p.id(), claim, 30)) {
                            most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            synchronized (won) {
                                won[side]++;
                            }
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            inside.decrementAndGet();
                            repository.releaseRound(p.id(), claim);
                        }
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join(60_000);
            }
            assertThat(most.get()).as("rounds of one player at the same time").isEqualTo(1);
            assertThat(won[0]).as("rounds through the first pool").isPositive();
            assertThat(won[1]).as("rounds through the second pool").isPositive();
        }
    }

    private static HikariDataSource pool(String name) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(name);
        pool.setJdbcUrl(MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC");
        pool.setUsername("app");
        pool.setPassword("app-password-123");
        pool.setMaximumPoolSize(4);
        return pool;
    }

    // ---------------------------------------------------------------- migrations onto the previous release's data, and the meter pointer

    /** The meter lookup before review 3.4 (GameRepository.latestMeterRound, removed in 8ad306f). */
    private static Optional<String> oldMeterQuery(JdbcClient on, long playerId) {
        return on.sql("SELECT r.id FROM game_round r WHERE r.player_id = ? AND r.bonus LIKE '%meter:%' "
                        + "AND NOT EXISTS (SELECT 1 FROM game_round n WHERE n.meter_prev = r.id) ORDER BY r.created_at DESC, r.id DESC LIMIT 1")
                .param(playerId).query(String.class).optional();
    }

    /** The lookup the game does now (GameService.meterState): the player's pointer, then along the chain by its unique index. */
    private static Optional<String> pointerLookup(GameRepository on, long playerId) {
        String pointer = on.meterRound(playerId).orElse(null);
        Optional<GameRepository.Round> latest = pointer == null ? on.roundContinuing("start-" + playerId) : on.findRound(pointer);
        for (int step = 0; latest.isPresent() && step < 10_000; step++) {
            Optional<GameRepository.Round> next = on.roundContinuing(latest.get().id());
            if (next.isEmpty()) {
                break;
            }
            latest = next;
        }
        return latest.map(GameRepository.Round::id);
    }

    @Test
    void theMigrationsApplyToTheLastReleasesDataAndTheMeterPointerAgreesWithTheOldQuery() {
        // the database as the release before the daily totals and the meter pointer left it (V14), with players' histories
        flyway("plinko_prev", "14").migrate();
        DataSource prevApp = appDataSource(url("plinko_prev"));
        Random random = new Random(5);
        List<Long> players = new ArrayList<>();
        Map<Long, String> sessions = new HashMap<>();
        Map<Long, String> chainEnd = new HashMap<>();
        Instant start = Instant.now().minus(Duration.ofDays(3));
        int minute = 0;
        for (int n = 0; n < 30; n++) {
            ownerOn("plinko_prev", "INSERT INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at) VALUES ('prev-" + n
                    + "', 'p" + n + "', 'EUR', 10, FALSE, NOW(3), NOW(3))");
            long id = JdbcClient.create(prevApp).sql("SELECT id FROM player WHERE external_id = ?").param("prev-" + n).query(Long.class).single();
            players.add(id);
            String s = "prev-session-" + n;
            ownerOn("plinko_prev", "INSERT INTO game_session (id, player_id, pam_token, created_at, expires_at) VALUES ('" + s + "', " + id
                    + ", 'x', NOW(3), NOW(3) + INTERVAL 1 HOUR)");
            sessions.put(id, s);
            // rounds before the meter chain (V10): meter rounds without a link; the newest is the current one
            if (n % 3 == 0) {
                for (int i = 0; i < 2; i++) {
                    String r = pending("plinko_prev", id, s, 8, 0, "1.00", "EUR", start.plusSeconds(60L * minute++));
                    decide("plinko_prev", r, "0.20", "meter:" + i + "/10,0.1", null);
                    status("plinko_prev", r, RoundStatus.COMPLETED);
                    chainEnd.put(id, r);    // after V10 the chain went on from the newest of them, as the old query found it
                }
            }
            for (int i = 0; i < 6 + random.nextInt(8); i++) {
                play("plinko_prev", null, id, s, chainEnd, random, start.plusSeconds(60L * minute++), true);
            }
        }
        // a status mix the daily totals must count right: refunds, open and blocked rounds on several days
        for (long id : players.subList(0, 6)) {
            String r = pending("plinko_prev", id, sessions.get(id), 9, 1, "2.00", "EUR", start.plus(Duration.ofDays(1)));
            status("plinko_prev", r, RoundStatus.CANCELLED);
            String t = pending("plinko_prev", id, sessions.get(id), 9, 1, "3.00", "EUR", start.plus(Duration.ofDays(2)));
            decide("plinko_prev", t, "4.00", null, null);
            status("plinko_prev", t, id % 2 == 0 ? RoundStatus.TAMPERED : RoundStatus.CREDIT_PENDING);
        }
        Map<Long, Optional<String>> before = new HashMap<>();
        JdbcClient prev = JdbcClient.create(prevApp);
        players.forEach(id -> before.put(id, oldMeterQuery(prev, id)));

        // this release's migrations, onto that data
        int applied = flyway("plinko_prev", null).migrate().migrationsExecuted;
        // the SQL files, and V15/V16 recorded as their SQL files but run as batched backfills (R3)
        long files = new java.io.File("src/main/resources/db/migration").listFiles((d, n) -> n.matches("V\\d+__.*\\.sql")).length
                + com.plinko.migration.SqlRecordedVersions.VERSIONS.size();
        assertThat(applied).isEqualTo((int) files - 14);

        // V15: the daily totals equal the rounds they add up, day by day
        List<String> raw = prev.sql("SELECT CONCAT(d, currency, rows_count, risk, ':', n, ':', bet, ':', win) FROM (SELECT DATE(created_at) AS d, "
                + "currency, rows_count, risk, COUNT(*) AS n, SUM(total_bet) AS bet, COALESCE(SUM(total_win), 0) AS win FROM game_round "
                + "WHERE status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED') GROUP BY DATE(created_at), currency, rows_count, risk) x")
                .query(String.class).list();
        List<String> totals = prev.sql("SELECT CONCAT(day, currency, rows_count, risk, ':', rounds, ':', total_bet, ':', total_win) FROM round_daily_totals")
                .query(String.class).list();
        assertThat(totals).isNotEmpty().containsExactlyInAnyOrderElementsOf(raw);
        // V24: the players' new columns start at 0 on old days, and a rebuild of such a day fills them exactly
        StatsRepository stats = new StatsRepository(prev, ReportingZone.utc());
        LocalDate day = start.atZone(ZoneOffset.UTC).toLocalDate();
        new TransactionTemplate(new DataSourceTransactionManager(prevApp)).executeWithoutResult(x -> stats.rebuildDay(day));
        assertThat(prev.sql("SELECT COUNT(*) FROM round_daily_players WHERE day = ? AND rounds > 0").param(java.sql.Date.valueOf(day))
                .query(Long.class).single()).isPositive();
        assertThat(prev.sql("SELECT CONCAT(player_id, ':', rounds, ':', total_bet) FROM round_daily_players WHERE day = ?")
                .param(java.sql.Date.valueOf(day)).query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(prev.sql("SELECT CONCAT(player_id, ':', COUNT(*), ':', SUM(total_bet)) FROM game_round "
                        + "WHERE DATE(created_at) = ? AND status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED') GROUP BY player_id")
                        .param(java.sql.Date.valueOf(day)).query(String.class).list());

        // V16: every player's pointer is what the old query found, and the game's lookup agrees
        GameRepository prevGame = new GameRepository(prev, new SecretBox(TestFixtures.properties()), prevApp);
        for (long id : players) {
            assertThat(prevGame.meterRound(id)).as("pointer of player %d", id).isEqualTo(before.get(id));
            assertThat(pointerLookup(prevGame, id)).as("lookup of player %d", id).isEqualTo(before.get(id));
        }
        // the old rounds still read through this release's code
        assertThat(prevGame.history(players.get(0), Instant.EPOCH, Instant.now().plusSeconds(60), "\uffff", 100)).isNotEmpty();

        // play on with this release (pointer moved), and with the previous release still running beside it (pointer not moved)
        for (int i = 0; i < 300; i++) {
            long id = players.get(random.nextInt(players.size()));
            play("plinko_prev", prevGame, id, sessions.get(id), chainEnd, random, Instant.now(), random.nextInt(4) != 0);
        }
        for (long id : players) {
            assertThat(pointerLookup(prevGame, id)).as("lookup of player %d after more rounds", id).isEqualTo(oldMeterQuery(prev, id));
        }
    }

    /**
     * One generated round of a player's history: a meter round continuing the chain, a round without the meter, or a
     * refunded one. With {@code game} set it is written through this release's repository, which moves the pointer
     * unless {@code movePointer} is false (a previous-release instance still serving during a rolling deploy).
     */
    private static void play(String schema, GameRepository game, long playerId, String session, Map<Long, String> chainEnd, Random random, Instant at,
                             boolean movePointer) {
        int kind = random.nextInt(10);
        String id;
        if (game == null) {
            id = pending(schema, playerId, session, 8, 0, "1.00", "EUR", at);
        } else {
            id = UUID.randomUUID().toString();
            game.insertRound(new GameRepository.Round(id, playerId, session, 8, 0, new BigDecimal("1.00"), 1, new BigDecimal("1.00"), null, "EUR",
                    RoundStatus.PENDING, null, null, null, null, null, null, null, 1L, new BigDecimal("1000.00")));
        }
        if (kind < 6) {
            // the meter: continues from the end of the player's chain (or its start)
            String prev = chainEnd.getOrDefault(playerId, "start-" + playerId);
            if (game == null) {
                decide(schema, id, "0.30", "meter:" + kind + "/10,0.2", prev);
            } else {
                game.saveOutcome(id, "4", "1.1", "meter:" + kind + "/10,0.2", prev, new BigDecimal("0.30"), "s".repeat(64), RoundStatus.DEBITED);
                if (movePointer) {
                    game.setMeterRound(playerId, id);
                }
            }
            chainEnd.put(playerId, id);
        } else if (kind < 9) {
            // the meter switched off: no meter state, not part of the chain
            if (game == null) {
                decide(schema, id, "0.10", "golden:0=2", null);
            } else {
                game.saveOutcome(id, "4", "1.1", "golden:0=2", null, new BigDecimal("0.10"), "s".repeat(64), RoundStatus.DEBITED);
            }
        } else {
            status(schema, id, RoundStatus.CANCELLED);
            return;
        }
        status(schema, id, RoundStatus.COMPLETED);
    }
}
