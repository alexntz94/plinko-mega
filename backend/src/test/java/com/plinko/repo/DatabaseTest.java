package com.plinko.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.plinko.TestFixtures;
import com.plinko.common.ReportingZone;
import com.plinko.common.SecretBox;
import com.plinko.model.RoundStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review 5.5: the database as it really runs (the production MySQL version), not mocks: every migration applies, the
 * application's own database user is held to its grants and the integrity triggers, the daily totals equal the rounds
 * they add up, the round lock behaves, and the hot queries use their indexes. Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");

    private static final java.util.Set<String> ISOLATIONS = new java.util.concurrent.ConcurrentSkipListSet<>();
    private static DataSource app;
    private static JdbcClient jdbc;
    private static GameRepository game;
    private static int migrations;

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
            // as deploy/mysql/01-users.sh: the schema owner runs the migrations, the application gets only what V2+ grant it
            s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
        }
        // exactly as the release runs them (Migrator), noting the isolation each migration ran under
        Flyway flyway = com.plinko.migration.Migrator.configure(MYSQL.getJdbcUrl(), "owner", "owner-password-123", "plinko", "app")
                .callbacks(new org.flywaydb.core.api.callback.Callback() {
                    @Override
                    public boolean supports(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
                        return event == org.flywaydb.core.api.callback.Event.BEFORE_EACH_MIGRATE;
                    }

                    @Override
                    public boolean canHandleInTransaction(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
                        return true;
                    }

                    @Override
                    public void handle(org.flywaydb.core.api.callback.Event event, org.flywaydb.core.api.callback.Context context) {
                        try (Statement s = context.getConnection().createStatement();
                             java.sql.ResultSet rs = s.executeQuery("SELECT @@transaction_isolation")) {
                            rs.next();
                            ISOLATIONS.add(rs.getString(1));
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }

                    @Override
                    public String getCallbackName() {
                        return "isolation";
                    }
                }).load();
        migrations = flyway.migrate().migrationsExecuted;
        app = new DriverManagerDataSource(MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC", "app", "app-password-123");
        jdbc = JdbcClient.create(app);
        game = new GameRepository(jdbc, new SecretBox(TestFixtures.properties()), app);
    }

    private static void asApp(String sql) {
        jdbc.sql(sql).update();
    }

    private static GameRepository.Player player(String name) {
        return game.upsertPlayer("db-" + name + "-" + UUID.randomUUID(), name, "EUR", new BigDecimal("100.00"));
    }

    private static String session(GameRepository.Player player) {
        String id = UUID.randomUUID().toString();
        game.insertSession(new GameRepository.Session(id, player.id(), "token", Instant.now().plusSeconds(3600), null));
        return id;
    }

    /** A round as the game writes one: PENDING with its bet, then its signed outcome, then paid. */
    private static String round(GameRepository.Player player, String session, String bet, String win, boolean paid) {
        String id = UUID.randomUUID().toString();
        game.insertRound(new GameRepository.Round(id, player.id(), session, 8, 0, new BigDecimal(bet), 1, new BigDecimal(bet), null, "EUR",
                RoundStatus.PENDING, null, null, null, null, null, null, null, 1L, new BigDecimal("1000.00")));
        game.saveOutcome(id, "4", "1.1", null, null, new BigDecimal(win), "s".repeat(64), RoundStatus.DEBITED);
        if (paid) {
            game.updateRoundStatus(id, RoundStatus.COMPLETED, null);
        }
        return id;
    }

    @Test
    void liveRoundsAreWrittenWhileTheDailyTotalsAreRebuilt() throws Exception {
        // N4: the rebuild reads today's rounds; even held open under REPEATABLE READ (MySQL's default) it must not lock
        // them, or saveOutcome / updateRoundStatus of live rounds wait on it
        GameRepository.Player p = player("rebuild-lock");
        String s = session(p);
        String live = round(p, s, "1.00", "2.00", false);
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread rebuild = new Thread(() -> {
            org.springframework.transaction.support.TransactionTemplate held = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(app));
            held.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            held.executeWithoutResult(x -> {
                stats.rebuildDay(LocalDate.now(ZoneOffset.UTC));
                read.countDown();
                try {
                    done.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        rebuild.start();
        try {
            assertThat(read.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (Connection c = app.getConnection(); Statement st = c.createStatement()) {
                st.execute("SET SESSION innodb_lock_wait_timeout = 2");
                assertThat(st.executeUpdate("UPDATE game_round SET status = 'COMPLETED', updated_at = NOW(3) WHERE id = '" + live + "'")).isEqualTo(1);
            }
        } finally {
            done.countDown();
            rebuild.join();
        }
    }

    @Test
    void aChangedDayStaysMarkedUntilItsLatestMarkIsRebuilt() {
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        LocalDate day = LocalDate.of(2026, 1, 15);
        stats.markDayChanged(day);
        var marked = stats.changedDays(100).stream().filter(e -> e.getKey().equals(day)).findFirst().orElseThrow();
        stats.clearDayChanged(day, marked.getValue().minusMillis(1));    // marked again after the rebuild read it: kept
        assertThat(stats.changedDays(100)).anyMatch(e -> e.getKey().equals(day));
        stats.clearDayChanged(day, marked.getValue());
        assertThat(stats.changedDays(100)).noneMatch(e -> e.getKey().equals(day));
    }

    @Test
    void oldRecordsAreSignedAgainSoTheOriginalKeyCanBeRemoved() {
        // N8 #2: a round, its confirmed credit and an odds version, all signed with the original ROUND_SIGNING_KEY
        com.plinko.bo.game.RoundSigner original = new com.plinko.bo.game.RoundSigner(TestFixtures.properties());
        com.plinko.common.Integrity originalIntegrity = new com.plinko.common.Integrity(TestFixtures.properties());
        GameRepository.Player p = player("resign");
        String s = session(p);
        String id = UUID.randomUUID().toString();
        BigDecimal bet = new BigDecimal("1.00");
        BigDecimal win = new BigDecimal("1.10");
        game.insertRound(new GameRepository.Round(id, p.id(), s, 8, 0, bet, 1, bet, null, "EUR", RoundStatus.PENDING, null, null, null, null, null,
                null, null, 1L, new BigDecimal("1000.00")));
        game.saveOutcome(id, "4", "1.1", null, null, win, original.sign(id, p.id(), 8, 0, bet, 1, bet, win, "EUR", "4", "1.1", null, 1L,
                new BigDecimal("1000.00")), RoundStatus.DEBITED);
        String credit = new com.plinko.bo.game.WalletSigner(originalIntegrity).creditId(id);
        game.insertTransaction(new GameRepository.Transaction(credit, id, p.id(), "CREDIT", win, "PENDING", null, null, null, 0, null, null,
                originalIntegrity.sign("credit", credit, id, p.externalId(), win, "EUR")));
        game.updateTransaction(credit, "OK", "ref", null);
        game.updateRoundStatus(id, RoundStatus.COMPLETED, null);
        String multipliers = "9," + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO odds_history (rows_count, risk, multipliers, signature, created_at) VALUES (8, 0, ?, ?, NOW(3))")
                .params(multipliers, originalIntegrity.sign("odds-history", 8, 0, multipliers)).update();
        long odds = jdbc.sql("SELECT id FROM odds_history WHERE multipliers = ?").param(multipliers).query(Long.class).single();

        // the key lists are set: the job signs them again (while the original key is not yet retired)
        String lists = "r1=" + "r".repeat(40);
        com.plinko.common.KeyRing rotated = new com.plinko.common.KeyRing(TestFixtures.properties(), lists, "d1=" + "d".repeat(40), "e1=" + "e".repeat(40));
        ResignedSignatures resigned = new ResignedSignatures(jdbc);
        new com.plinko.bo.security.LegacyResigner(jdbc, game, new com.plinko.bo.game.RoundSigner(rotated), new com.plinko.common.Integrity(rotated),
                rotated, resigned, new JobState(jdbc, game)).run();
        assertThat(resigned.of(ResignedSignatures.ROUNDS, id)).get().asString().startsWith("r1.");
        assertThat(resigned.of(ResignedSignatures.TRANSACTIONS, credit)).get().asString().startsWith("r1.");
        assertThat(resigned.of(ResignedSignatures.ODDS, Long.toString(odds))).get().asString().startsWith("d1.");

        // then the original key is removed: the records still verify, by their new signatures
        com.plinko.common.KeyRing removed = new com.plinko.common.KeyRing(TestFixtures.propertiesWithoutOriginalKey(), lists, "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), "2026-01-01T00:00:00Z");
        assertThat(removed.legacyRemoved()).isTrue();
        com.plinko.bo.game.RoundSigner rounds = new com.plinko.bo.game.RoundSigner(removed);
        com.plinko.bo.game.WalletSigner wallet = new com.plinko.bo.game.WalletSigner(new com.plinko.common.Integrity(removed));
        GameRepository.Round round = game.findRound(id).orElseThrow();
        GameRepository.Transaction tx = game.transactionsOfRound(id).stream().filter(t -> t.id().equals(credit)).findFirst().orElseThrow();
        assertThat(rounds.verify(round)).as("without the new signatures").isFalse();
        rounds.setResigned(resigned);
        wallet.setResigned(resigned);
        assertThat(rounds.verify(round)).isTrue();
        assertThat(wallet.verifyCredit(tx, round, p)).isTrue();
        assertThat(new com.plinko.common.Integrity(removed).verify(resigned.of(ResignedSignatures.ODDS, Long.toString(odds)).orElseThrow(),
                "odds-history", 8, 0, multipliers)).isTrue();
        // a record nobody signed again (or one added later with the old key) no longer verifies
        GameRepository.Round forged = new GameRepository.Round(UUID.randomUUID().toString(), p.id(), s, 8, 0, bet, 1, bet, win, "EUR",
                RoundStatus.COMPLETED, "4", "1.1", null, original.sign("x", p.id(), 8, 0, bet, 1, bet, win, "EUR", "4", "1.1"), null, null);
        assertThat(rounds.verify(forged)).isFalse();
    }

    @Test
    void todaysTotalsAddEachRoundsIncrementAndARebuildNeverCountsOneTwice() {
        // N4 #2 (other tests' rounds of today are in the figures too: only the differences are checked)
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(app));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(x -> stats.rebuildDay(day));
        tx.execute(x -> stats.foldDeltas(100_000));
        java.util.function.Supplier<String> totals = () -> jdbc.sql("SELECT CONCAT(COALESCE(SUM(rounds), 0), '/', COALESCE(SUM(total_bet), 0), '/', "
                + "COALESCE(SUM(total_win), 0)) FROM round_daily_totals WHERE day = ?").param(java.sql.Date.valueOf(day)).query(String.class).single();
        String before = totals.get();
        GameRepository.Player p = player("delta");
        String s = session(p);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = round(p, s, "1.00", "0.50", true);
            ids.add(id);
            stats.addDelta(game.findRound(id).orElseThrow(), 1);     // as the round's last transaction does
        }
        tx.execute(x -> stats.foldDeltas(100_000));
        String folded = totals.get();
        // a repair rebuild reads the rounds and the waiting increments in one snapshot: nothing is counted twice
        String another = round(p, s, "2.00", "0.00", true);
        stats.addDelta(game.findRound(another).orElseThrow(), 1);
        tx.executeWithoutResult(x -> stats.rebuildDay(day));
        tx.execute(x -> stats.foldDeltas(100_000));
        String rebuilt = totals.get();
        tx.executeWithoutResult(x -> stats.rebuildDay(day));
        assertThat(totals.get()).isEqualTo(rebuilt);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM round_daily_delta WHERE day = ?").param(java.sql.Date.valueOf(day)).query(Long.class).single()).isZero();
        String[] b = before.split("/"), f = folded.split("/"), r = rebuilt.split("/");
        assertThat(Long.parseLong(f[0]) - Long.parseLong(b[0])).isEqualTo(3);
        assertThat(new BigDecimal(f[1]).subtract(new BigDecimal(b[1]))).isEqualByComparingTo("3.00");
        assertThat(Long.parseLong(r[0]) - Long.parseLong(f[0])).isEqualTo(1);
        // a counted round blocked later takes itself out again
        stats.addDelta(game.findRound(ids.get(0)).orElseThrow(), -1);
        tx.execute(x -> stats.foldDeltas(100_000));
        assertThat(Long.parseLong(totals.get().split("/")[0])).isEqualTo(Long.parseLong(r[0]) - 1);
    }

    @Test
    void everyMigrationRunsUnderReadCommitted() {
        // N11: a backfill reads live rounds without locking them while the previous version serves players
        assertThat(ISOLATIONS).containsExactly("READ-COMMITTED");
    }

    @Test
    void everyMigrationAppliesToAnEmptyDatabase() {
        // the SQL files, and V15/V16 recorded as their SQL files but run as batched backfills (R3)
        long files = new java.io.File("src/main/resources/db/migration").listFiles((d, n) -> n.matches("V\\d+__.*\\.sql")).length
                + com.plinko.migration.SqlRecordedVersions.VERSIONS.size();
        assertThat(migrations).isEqualTo((int) files);
    }

    @Test
    void theApplicationUserCantDeleteDropOrAlter() {
        for (String forbidden : List.of("DELETE FROM game_round", "DELETE FROM wallet_transaction", "DELETE FROM audit_log",
                "UPDATE audit_log SET details = 'x'", "DROP TABLE game_round", "ALTER TABLE game_round ADD COLUMN x INT",
                "TRUNCATE TABLE integrity_ledger", "CREATE TABLE evil (id INT)", "DELETE FROM odds_history", "DELETE FROM round_settlement")) {
            assertThatThrownBy(() -> asApp(forbidden)).as(forbidden).rootCause().hasMessageContaining("denied");
        }
    }

    /** Review 7.4: what the game reported showing is kept as the application wrote it: added and read, never changed or removed. */
    @Test
    void theGamesShownEventsAreAddedAndReadButNeverChanged() {
        GameRepository.Player p = player("events");
        String s = session(p);
        GameEventRepository events = new GameEventRepository(jdbc);
        Instant start = events.sessionStart(s).orElseThrow();
        assertThat(start).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
        assertThat(events.sessionStart("no-such-session")).isEmpty();
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        events.insert(s, p.id(), "SESSION_CLOCK_SHOWN", null, at);
        events.insert(s, p.id(), "REALITY_CHECK_SHOWN", at.minusSeconds(3), at.plusSeconds(1));
        events.insert(s, p.id(), "REALITY_CHECK_CONTINUED", null, at.plusSeconds(2));
        events.insert(s, p.id(), "REALITY_CHECK_SHOWN", null, at.plusSeconds(3));
        assertThat(events.latest(s, 2)).containsExactly(new GameEventRepository.Event("REALITY_CHECK_SHOWN", at.plusSeconds(3)),
                new GameEventRepository.Event("REALITY_CHECK_CONTINUED", at.plusSeconds(2)));
        assertThat(events.countInSession(s, 10)).isEqualTo(4);
        assertThat(events.countInSession(s, 3)).as("counted only up to the cap").isEqualTo(3);
        assertThat(events.summary(p.id())).containsExactly(new GameEventRepository.Summary("REALITY_CHECK_CONTINUED", 1, at.plusSeconds(2)),
                new GameEventRepository.Summary("REALITY_CHECK_SHOWN", 2, at.plusSeconds(3)),
                new GameEventRepository.Summary("SESSION_CLOCK_SHOWN", 1, at));
        for (String forbidden : List.of("DELETE FROM game_event", "UPDATE game_event SET type = 'REALITY_CHECK_CONTINUED'")) {
            assertThatThrownBy(() -> asApp(forbidden)).as(forbidden).rootCause().hasMessageContaining("denied");
        }
        // the session's events and a player's summary are read by their indexes
        for (String query : List.of("SELECT type, created_at FROM game_event WHERE session_id = 'x' ORDER BY id DESC LIMIT 20",
                "SELECT type, COUNT(*), MAX(created_at) FROM game_event WHERE player_id = 1 GROUP BY type")) {
            var plan = jdbc.sql("EXPLAIN " + query).query((rs, n) -> rs.getString("type") + "/" + rs.getString("key")).list();
            assertThat(plan).as(query).allSatisfy(step -> assertThat(step).doesNotStartWith("ALL/").doesNotEndWith("/null"));
        }
    }

    @Test
    void theIntegrityTriggersHoldForTheApplicationUserToo() {
        GameRepository.Player p = player("triggers");
        String s = session(p);
        String done = round(p, s, "1.00", "0.50", true);
        // a new round must start as PENDING without an outcome
        assertThatThrownBy(() -> asApp("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, "
                + "total_win, created_at, updated_at) VALUES (UUID(), " + p.id() + ", '" + s + "', 8, 0, 1, 1, 1, 'EUR', 'COMPLETED', 99, NOW(3), NOW(3))"))
                .rootCause().hasMessageContaining("must start as PENDING");
        // a signed outcome never changes; a finished round never reopens; the bet never changes
        assertThatThrownBy(() -> asApp("UPDATE game_round SET total_win = 999 WHERE id = '" + done + "'")).rootCause().hasMessageContaining("game_round");
        assertThatThrownBy(() -> asApp("UPDATE game_round SET status = 'CREDIT_PENDING' WHERE id = '" + done + "'")).rootCause().hasMessageContaining("game_round");
        assertThatThrownBy(() -> asApp("UPDATE game_round SET bet = 50 WHERE id = '" + done + "'")).rootCause().hasMessageContaining("game_round");
        // a wallet transaction: only its status moves, and a confirmed one stays confirmed
        String tx = UUID.randomUUID().toString();
        game.insertTransaction(new GameRepository.Transaction(tx, done, p.id(), "CREDIT", new BigDecimal("0.50"), "PENDING", null, null, null, 0, null,
                null, "s".repeat(64)));
        assertThatThrownBy(() -> asApp("UPDATE wallet_transaction SET amount = 500 WHERE id = '" + tx + "'")).rootCause().hasMessageContaining("wallet_transaction");
        game.updateTransaction(tx, "OK", "ref", null);
        assertThatThrownBy(() -> asApp("UPDATE wallet_transaction SET status = 'PENDING' WHERE id = '" + tx + "'"))
                .rootCause().hasMessageContaining("wallet_transaction");
        assertThatThrownBy(() -> asApp("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) "
                + "VALUES (UUID(), '" + done + "', " + p.id() + ", 'CREDIT', 9, 'OK', 0, NOW(3), NOW(3))")).rootCause().hasMessageContaining("must start as PENDING");
        // a settlement only for a blocked round (7.3)
        assertThatThrownBy(() -> asApp("INSERT INTO round_settlement (round_id, action, amount, change_id, proposed_by, approved_by, reason, status, "
                + "signature, created_at, updated_at) VALUES ('" + done + "', 'PAY', 5, 1, 'a', 'b', 'reason here', 'DONE', '" + "s".repeat(64)
                + "', NOW(3), NOW(3))")).rootCause().hasMessageContaining("only a blocked round");
        // young launch tokens and sessions can't be deleted (3.7)
        asApp("INSERT INTO used_launch_token (token_hash, used_at) VALUES ('" + "a".repeat(64) + "', NOW(3))");
        assertThatThrownBy(() -> asApp("DELETE FROM used_launch_token WHERE token_hash = '" + "a".repeat(64) + "'")).rootCause().hasMessageContaining("30 days");
    }

    @Test
    void aRoundInProgressWhileTodayIsRebuiltIsCountedOnce() {
        // Review R2: the rebuild reads a round between its transactions (b) and (c) (DEBITED); its +1 increment is written
        // only later, in (c), with its final status. It must be counted once: by that increment, not by the rebuild as well.
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(app));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        java.util.function.Supplier<String> totals = () -> jdbc.sql("SELECT CONCAT(COALESCE(SUM(rounds), 0), '/', COALESCE(SUM(total_bet), 0), '/', "
                + "COALESCE(SUM(total_win), 0)) FROM round_daily_totals WHERE day = ?").param(java.sql.Date.valueOf(day)).query(String.class).single();
        java.util.function.Supplier<String> players = () -> jdbc.sql("SELECT CONCAT(COALESCE(SUM(rounds), 0), '/', COALESCE(SUM(total_bet), 0)) "
                + "FROM round_daily_players WHERE day = ?").param(java.sql.Date.valueOf(day)).query(String.class).single();
        tx.executeWithoutResult(x -> stats.rebuildDay(day));
        tx.execute(x -> stats.foldDeltas(100_000));
        String before = totals.get();
        String playersBefore = players.get();

        GameRepository.Player p = player("in-progress");
        String inProgress = round(p, session(p), "7.00", "3.50", false);     // DEBITED: bet taken, outcome signed, not paid yet
        tx.executeWithoutResult(x -> stats.rebuildDay(day));              // the hourly or start-up rebuild of today, right now
        // (c): the payout's answer, the round's status and its increment, in one transaction
        tx.executeWithoutResult(x -> {
            game.updateRoundStatus(inProgress, RoundStatus.COMPLETED, null);
            stats.addDelta(game.findRound(inProgress).orElseThrow(), 1);
        });
        tx.execute(x -> stats.foldDeltas(100_000));                        // the minute job

        String[] b = before.split("/"), a = totals.get().split("/");
        assertThat(Long.parseLong(a[0]) - Long.parseLong(b[0])).as("rounds").isEqualTo(1);
        assertThat(new BigDecimal(a[1]).subtract(new BigDecimal(b[1]))).as("bets").isEqualByComparingTo("7.00");
        assertThat(new BigDecimal(a[2]).subtract(new BigDecimal(b[2]))).as("wins").isEqualByComparingTo("3.50");
        String[] pb = playersBefore.split("/"), pa = players.get().split("/");
        assertThat(Long.parseLong(pa[0]) - Long.parseLong(pb[0])).as("per-player rounds").isEqualTo(1);
        assertThat(new BigDecimal(pa[1]).subtract(new BigDecimal(pb[1]))).as("per-player bets").isEqualByComparingTo("7.00");
        // and the next rebuild agrees
        String folded = totals.get();
        tx.executeWithoutResult(x -> stats.rebuildDay(day));
        assertThat(totals.get()).isEqualTo(folded);
    }

    @Test
    void theDailyTotalsEqualTheRoundsTheyAddUp() {
        GameRepository.Player p = player("totals");
        GameRepository.Player q = player("totals2");
        String s = session(p);
        String t = session(q);
        round(p, s, "1.00", "0.50", true);
        round(p, s, "2.00", "5.40", true);
        round(q, t, "0.20", "0.00", true);
        game.updateRoundStatus(round(q, t, "3.00", "1.20", false), RoundStatus.CREDIT_PENDING, null);    // credit still pending: counted
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(app))
                .executeWithoutResult(x -> stats.rebuildDay(today));
        var raw = jdbc.sql("SELECT COUNT(*), SUM(total_bet), SUM(total_win), COUNT(DISTINCT player_id) FROM game_round "
                + "WHERE created_at >= ? AND status IN ('COMPLETED', 'CREDIT_PENDING')")    // R2: a DEBITED round comes with its increment
                .param(java.sql.Timestamp.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .query((rs, n) -> List.of(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getLong(4))).single();
        StatsRepository.Totals summed = stats.totalsSince("today", "EUR", today);
        assertThat(summed.rounds()).isEqualTo(raw.get(0));
        assertThat(summed.bet()).isEqualByComparingTo((BigDecimal) raw.get(1));
        assertThat(summed.win()).isEqualByComparingTo((BigDecimal) raw.get(2));
        assertThat(summed.players()).isEqualTo(raw.get(3));
    }

    @Test
    void theRoundLockExpiresAndIsReleasedOnlyByItsOwner() throws InterruptedException {
        GameRepository.Player p = player("lock");
        assertThat(game.claimRound(p.id(), "first", 2)).isTrue();
        assertThat(game.claimRound(p.id(), "second", 2)).isFalse();
        game.releaseRound(p.id(), "second");            // not its owner: nothing happens
        assertThat(game.claimRound(p.id(), "third", 2)).isFalse();
        Thread.sleep(2200);                              // the first holder crashed: its claim runs out
        assertThat(game.claimRound(p.id(), "third", 2)).isTrue();
        game.releaseRound(p.id(), "third");
        assertThat(game.claimRound(p.id(), "fourth", 2)).isTrue();
    }

    @Test
    void theHotQueriesUseTheirIndexes() {
        GameRepository.Player p = player("plans");
        String s = session(p);
        for (int i = 0; i < 50; i++) {
            round(p, s, "1.00", "0.50", i % 3 != 0);
        }
        // the table as it really looks: this player's rounds are a small part of many players' rounds
        for (int other = 0; other < 20; other++) {
            GameRepository.Player o = player("other" + other);
            String os = session(o);
            jdbc.sql("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at) "
                    + "WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 400) "
                    + "SELECT UUID(), ?, ?, 8, 0, 1, 1, 1, 'EUR', 'PENDING', UTC_TIMESTAMP(3) - INTERVAL i MINUTE, UTC_TIMESTAMP(3) FROM n")
                    .params(o.id(), os).update();
        }
        jdbc.sql("ANALYZE TABLE game_round, wallet_transaction").query().listOfRows();
        String ts = "'2026-01-01 00:00:00'";
        for (String query : List.of(
                "SELECT * FROM game_round WHERE player_id = " + p.id() + " AND signature IS NOT NULL AND created_at >= " + ts
                        + " ORDER BY created_at DESC, id DESC LIMIT 21",
                "SELECT created_at FROM game_round WHERE player_id = " + p.id() + " ORDER BY created_at DESC LIMIT 1",
                "SELECT * FROM game_round WHERE player_id = " + p.id() + " AND request_id = 'x'",
                "SELECT COUNT(*) FROM game_round WHERE status IN ('CREDIT_PENDING', 'ROLLBACK_PENDING')",
                "SELECT * FROM wallet_transaction WHERE created_at >= " + ts + " AND created_at < '2026-02-01' ORDER BY created_at LIMIT 10",
                "SELECT * FROM wallet_transaction WHERE round_id = 'x' ORDER BY created_at")) {
            var plan = jdbc.sql("EXPLAIN " + query).query((rs, n) -> rs.getString("type") + "/" + rs.getString("key")).list();
            // never a full scan; "null/null" is MySQL answering from a unique index before reading any row
            assertThat(plan).as(query).allSatisfy(step -> assertThat(step).satisfiesAnyOf(
                    x -> assertThat(x).isEqualTo("null/null"), x -> assertThat(x).doesNotStartWith("ALL/").doesNotEndWith("/null")));
        }
    }
}
