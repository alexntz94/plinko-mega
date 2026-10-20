package com.plinko.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import com.plinko.TestFixtures;
import com.plinko.common.ListQuery;
import com.plinko.common.ReportingZone;
import com.plinko.common.SecretBox;
import com.plinko.model.RoundStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review 5.5 #6 and 3.5: the repositories' own hot queries (rounds, retries, the scans, the daily totals rebuild, Finance,
 * the wallet check, the admin lists), run through the real repository code against a seeded database, each statement
 * checked with EXPLAIN as it is sent: no full table or index scan, and no filesort on a table (sorting the few grouped
 * rows of a derived table is fine).
 * <p>
 * Rounds seeded: {@value #DEFAULT_ROWS} by default (a few seconds); a nightly run can seed more with
 * {@code -DQUERY_PLAN_ROWS=2000000} or the environment variable {@code QUERY_PLAN_ROWS}, up to 10,000,000 (review 5.5:
 * production size). The rows are made by the database itself (INSERT ... SELECT, nothing held in the test's memory), a
 * batch of {@value #SEED_BATCH} players (about 200,000 rounds) per statement, each statement reading only its batch's rows
 * by index, so neither the test's memory nor the database's undo and lock memory grow with the row count. The time does:
 * the inserts are disk-bound once the tables outgrow the buffer pool (1,500,000 rounds took 31 minutes on a laptop's
 * Docker; plan for several hours at 10,000,000, e.g. a weekly job). Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueryPlanTest {

    static final int DEFAULT_ROWS = 20_000;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");

    /** One step of a statement's plan, as EXPLAIN shows it. */
    record Step(String table, String type, String key, String extra) {
        @Override
        public String toString() {
            return table + ": " + type + " on " + key + (extra == null ? "" : " (" + extra + ")");
        }
    }

    /** A statement the repositories sent, and its plan. */
    record Plan(String sql, List<Step> steps) {
    }

    private static final List<Plan> PLANS = new ArrayList<>();
    private static JdbcClient jdbc;
    private static long player;
    private static String round;

    static int rows() {
        String value = System.getProperty("QUERY_PLAN_ROWS", System.getenv("QUERY_PLAN_ROWS"));
        return value == null || value.isBlank() ? DEFAULT_ROWS : Integer.parseInt(value.trim());
    }

    /** Numbers 0 to SEQ - 1, the seeding's counter. */
    private static final int SEQ = 10_000;
    /** Players seeded per batch: about 100 rounds each, so every statement writes some 200,000 rows at most. */
    private static final int SEED_BATCH = 2_000;

    /**
     * The rounds of players {@code first} to {@code last} with their wallet transactions, every statement limited to those
     * players' rows (by the player index), so no statement grows with the whole table: the database's undo and lock memory
     * stay the same at 10 million rounds as at 20,000.
     */
    private static void seedRounds(Statement s, int rounds, int players, int first, int last) throws SQLException {
        // the batch's players drive every join (STRAIGHT_JOIN), so each statement reads their rows by index, never the whole table
        String batchPlayers = "seed_player sp STRAIGHT_JOIN ";
        String range = " AND sp.n BETWEEN " + first + " AND " + last;
        String r = batchPlayers + "game_round r ON r.player_id = sp.id" + range;
        // rounds of the last 60 days, newest first, spread over the players; each with a session id of its own player
        s.execute("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, odds_ref, bet, balls, total_bet, max_win, currency, status, "
                + "created_at, updated_at, request_id) "
                + "SELECT UUID(), sp.id, CONCAT('s-', sp.id), 8 + MOD(k, 7), MOD(k, 3), 1, 1.00, 1, 1.00, 1000, 'EUR', 'PENDING', "
                + "UTC_TIMESTAMP(3) - INTERVAL (k * 5184000 DIV " + rounds + ") SECOND - INTERVAL 15 MINUTE, UTC_TIMESTAMP(3), UUID() "
                + "FROM (SELECT sp.n + " + players + " * j.n AS k, sp.id FROM seed_player sp JOIN seq j "
                + "WHERE sp.n BETWEEN " + first + " AND " + last + " AND sp.n + " + players + " * j.n < " + rounds + ") sp");
        // most rounds decided and paid; a few refunded, owed, being refunded, blocked or interrupted
        s.execute("UPDATE " + r + " SET r.slots = '4', r.multipliers = '1.1', r.total_win = 1.10, r.signature = REPEAT('s', 64), "
                + "r.status = 'DEBITED', r.meter_prev = IF(MOD(CRC32(r.id), 10) = 0, CONCAT('m-', r.id), NULL), "
                + "r.bonus = IF(MOD(CRC32(r.id), 10) = 0, 'meter:1/10,0.1', NULL), r.updated_at = r.created_at + INTERVAL 1 SECOND "
                + "WHERE MOD(CRC32(r.id), 1000) >= 30");
        s.execute("UPDATE " + r + " SET r.status = 'COMPLETED' WHERE r.status = 'DEBITED' AND MOD(CRC32(r.id), 1000) >= 60");
        s.execute("UPDATE " + r + " SET r.status = 'CREDIT_PENDING' WHERE r.status = 'DEBITED' AND MOD(CRC32(r.id), 1000) BETWEEN 30 AND 49");
        s.execute("UPDATE " + r + " SET r.status = 'TAMPERED' WHERE r.status = 'DEBITED' AND MOD(CRC32(r.id), 1000) BETWEEN 50 AND 54");
        s.execute("UPDATE " + r + " SET r.status = 'ROLLBACK_PENDING' WHERE r.status = 'PENDING' AND MOD(CRC32(r.id), 1000) < 10");
        s.execute("UPDATE " + r + " SET r.status = 'CANCELLED' WHERE r.status IN ('PENDING', 'ROLLBACK_PENDING') "
                + "AND MOD(CRC32(r.id), 1000) < 25 AND MOD(CRC32(r.id), 1000) >= 5");
        // their wallet transactions: every bet, the credits of decided rounds and the refunds
        s.execute("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at) "
                + "SELECT UUID(), r.id, r.player_id, 'DEBIT', r.total_bet, 'PENDING', 1, r.created_at, r.created_at FROM " + r);
        s.execute("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at, signature) "
                + "SELECT UUID(), r.id, r.player_id, 'CREDIT', r.total_win, 'PENDING', 1, r.created_at, r.created_at, REPEAT('s', 64) "
                + "FROM " + r + " WHERE r.total_win IS NOT NULL");
        s.execute("INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at, original_tx_id) "
                + "SELECT UUID(), r.id, r.player_id, 'ROLLBACK', 0, 'PENDING', 1, r.created_at, r.created_at, t.id FROM " + r + " "
                + "JOIN wallet_transaction t ON t.round_id = r.id AND t.type = 'DEBIT' WHERE r.status IN ('CANCELLED', 'ROLLBACK_PENDING')");
        String t = batchPlayers + "wallet_transaction t ON t.player_id = sp.id" + range + " JOIN game_round r ON r.id = t.round_id";
        s.execute("UPDATE " + t + " SET t.status = 'OK' "
                + "WHERE t.type = 'DEBIT' OR r.status IN ('COMPLETED', 'CANCELLED')");
        s.execute("UPDATE " + t + " SET t.status = 'FAILED', "
                + "t.attempts = 1 + MOD(CRC32(t.id), 12), t.next_retry_at = UTC_TIMESTAMP(3) + INTERVAL (CAST(MOD(CRC32(t.id), 600) AS SIGNED) - 300) SECOND "
                + "WHERE t.status = 'PENDING' AND r.status IN ('CREDIT_PENDING', 'ROLLBACK_PENDING')");
    }

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
            s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
        }
        com.plinko.migration.Migrator.configure(MYSQL.getJdbcUrl(), "owner", "owner-password-123", "plinko", "app")
                .load().migrate();
        int rounds = rows();
        int players = Math.max(200, rounds / 100);
        // each player n plays rounds k = n, n + players, n + 2 * players, ... (j below); the rounds per player stay under SEQ
        int perPlayer = (rounds + players - 1) / players;
        if (perPlayer > SEQ || players > SEQ * SEQ) {
            throw new IllegalArgumentException("QUERY_PLAN_ROWS " + rounds + " is more than this seeding makes");
        }
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC";
        try (Connection c = DriverManager.getConnection(url, "owner", "owner-password-123"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE seq (n INT PRIMARY KEY)");
            s.execute("SET SESSION cte_max_recursion_depth = 20000");
            s.execute("INSERT INTO seq WITH RECURSIVE n (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < " + (SEQ - 1) + ") SELECT i FROM n");
            s.execute("INSERT INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at) "
                    + "SELECT CONCAT('plan-', n), CONCAT('p', n), 'EUR', 10, FALSE, NOW(3), NOW(3) - INTERVAL n MINUTE "
                    + "FROM (SELECT a.n * " + SEQ + " + b.n AS n FROM seq a JOIN seq b WHERE a.n <= " + (players / SEQ) + " AND a.n * " + SEQ
                    + " + b.n < " + players + ") x");
            // the seeded players by number, to seed their rounds a batch of players at a time
            s.execute("CREATE TABLE seed_player (n INT PRIMARY KEY, id BIGINT NOT NULL, INDEX (id))");
            s.execute("INSERT INTO seed_player SELECT CAST(SUBSTRING(external_id, 6) AS UNSIGNED), id FROM player WHERE external_id LIKE 'plan-%'");
            for (int from = 0; from < players; from += SEED_BATCH) {
                seedRounds(s, rounds, players, from, Math.min(players, from + SEED_BATCH) - 1);
            }
            s.execute("ANALYZE TABLE game_round, wallet_transaction, player");
            try (ResultSet rs = s.executeQuery("SELECT player_id, id FROM game_round WHERE signature IS NOT NULL LIMIT 1")) {
                rs.next();
                player = rs.getLong(1);
                round = rs.getString(2);
            }
        }
        jdbc = JdbcClient.create(explaining(new DriverManagerDataSource(url, "app", "app-password-123")));
    }

    // ---------------------------------------------------------------- the queries, through the repositories

    private static GameRepository game() {
        return new GameRepository(jdbc, new SecretBox(TestFixtures.properties()), null);
    }

    private final Instant now = Instant.now();
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @Test
    void theRetryJobsSelectionUsesItsIndexes() {
        assertPlans(() -> game().dueForRetry(10, 200));
    }

    @Test
    void theStoppedRoundsAlarmUsesItsIndexes() {
        assertPlans(() -> game().stoppedRounds(10));
    }

    @Test
    void theInterruptedRoundsSearchUsesItsIndexes() {
        assertPlans(() -> game().staleRounds(List.of(RoundStatus.PENDING, RoundStatus.DEBITED), now.minus(Duration.ofMinutes(6))));
    }

    /**
     * During a long wallet outage (N5: about 20,000 rounds owed after 30 minutes) the retry job, the stopped-rounds alarm,
     * the interrupted-round search and Finance's open rounds must not sort every open round: each status is read in order
     * from its index (one branch per status), and only the few rows of a page are merged.
     */
    @Test
    void theOpenRoundQueriesSortWithoutAFilesort() {
        FinanceRepository finance = new FinanceRepository(jdbc, ReportingZone.utc());
        assertPlans(() -> {
            game().dueForRetry(10, 200);
            game().stoppedRounds(10);
            game().staleRounds(List.of(RoundStatus.PENDING, RoundStatus.DEBITED), now.minus(Duration.ofMinutes(6)));
            finance.open(today.minusDays(30), today);
        });
    }

    @Test
    void theHourlyAndFullRoundScansUseTheirIndexes() {
        assertPlans(() -> {
            game().decidedRoundsPlayed(now.minus(Duration.ofDays(1)), "", now.minus(Duration.ofMinutes(10)), 1000);
            game().decidedRoundsAfter("", 1000);
        });
    }

    @Test
    void thePlayersOwnQueriesUseTheirIndexes() {
        assertPlans(() -> {
            game().history(player, now.minus(Duration.ofDays(90)), now.plusSeconds(60), "\uffff", 21);
            game().roundByRequest(player, "00000000-0000-0000-0000-000000000000");
            game().lastRoundAt(player);
            game().meterRound(player);
            game().roundContinuing(round);
            game().findRound(round);
            game().transactionsOfRound(round);
        }, "WHERE round_id = ?");    // sorts a round's own two or three transactions

    }

    @Test
    void theWalletListingForReconciliationUsesItsIndex() {
        assertPlans(() -> game().transactionsBetween(now.minus(Duration.ofDays(1)), now, 500));
    }

    @Test
    void theDailyTotalsRebuildReadsOneDayByItsIndex() {
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        assertPlans(() -> {
            stats.rebuildDay(today.minusDays(3));
            stats.rebuildDay(today);
        });
    }

    @Test
    void theDashboardFiguresUseTheirIndexes() {
        StatsRepository stats = new StatsRepository(jdbc, ReportingZone.utc());
        assertPlans(() -> {
            stats.since(now.minus(Duration.ofHours(24)));
            stats.oldestPending();
            stats.countByStatus(List.of(RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING));
            stats.firstRoundDay();
            stats.totalsSince("month", "EUR", today.minusDays(30));
        });
    }

    @Test
    void theWalletCheckUsesItsIndexes() {
        FinanceRepository finance = new FinanceRepository(jdbc, ReportingZone.utc());
        assertPlans(() -> finance.reconcileAfter(now.minus(Duration.ofDays(2)), "", now.minus(Duration.ofMinutes(5)), 500));
    }

    @Test
    void theFinancePageUsesItsIndexes() {
        FinanceRepository finance = new FinanceRepository(jdbc, ReportingZone.utc());
        LocalDate from = today.minusDays(30);
        assertPlans(() -> {
            finance.totals(from, today);
            finance.daily(from, today);
            finance.byRisk(from, today);
            finance.byRows(from, today);
            finance.players(from, today, true, 20);
            finance.open(from, today);
        }, "FROM round_daily_totals", "FROM round_daily_players");    // the daily summaries are grouped (a sort of a range of their rows)
    }

    @Test
    void theAdminRoundAndTransactionListsUseTheirIndexes() {
        AdminListRepository lists = new AdminListRepository(jdbc);
        ListQuery rounds = ListQuery.of(1, 50, null, null, AdminListRepository.ROUND_SORTS, "time", false);
        ListQuery transactions = ListQuery.of(1, 50, null, null, AdminListRepository.TX_SORTS, "time", false);
        assertPlans(() -> {
            lists.rounds(new AdminListRepository.RoundFilter(null, null, null, null, null, null, null, null), rounds);
            lists.rounds(new AdminListRepository.RoundFilter(null, RoundStatus.CREDIT_PENDING, today.minusDays(7), today, null, null, null, null), rounds);
            lists.rounds(new AdminListRepository.RoundFilter(null, null, null, null, null, null, null, player), rounds);
            // the dashboard's and the player page's latest rounds
            lists.latestRounds(null, 15);
            lists.latestRounds(player, 100);
            lists.transactions(new AdminListRepository.TransactionFilter(null, null, null, null, null), transactions);
            lists.transactions(new AdminListRepository.TransactionFilter(null, null, "FAILED", today.minusDays(7), today), transactions);
        });
    }

    // ---------------------------------------------------------------- checking the plans

    /**
     * Runs the repository calls and checks every statement they sent: never a full table scan; a full index scan only in
     * a statement with a LIMIT (reading an index in order and stopping early is the best plan); and no filesort on a
     * table, except in statements containing one of {@code sortsAllowed} (sorts known to be bounded, each named where used).
     */
    private static void assertPlans(Runnable calls, String... sortsAllowed) {
        List<Plan> sent;
        synchronized (PLANS) {
            PLANS.clear();
            calls.run();
            sent = List.copyOf(PLANS);
        }
        assertThat(sent).as("statements sent").isNotEmpty();
        for (Plan plan : sent) {
            for (Step step : plan.steps()) {
                if (step.table() == null || step.table().startsWith("<")) {
                    continue;      // nothing read, or a derived table of rows already grouped
                }
                String sql = oneLine(plan.sql());
                assertThat(step.type()).as("%s%n  %s", sql, plan.steps()).isNotEqualTo("ALL");
                if (!sql.contains(" LIMIT ")) {
                    assertThat(step.type()).as("%s%n  %s", sql, plan.steps()).isNotEqualTo("index");
                }
                if (java.util.Arrays.stream(sortsAllowed).noneMatch(sql::contains)) {
                    assertThat(step.extra() == null ? "" : step.extra()).as("%s%n  %s", sql, plan.steps()).doesNotContain("Using filesort");
                }
            }
        }
    }

    private static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    // ---------------------------------------------------------------- EXPLAIN of every statement as it is sent

    /** A data source whose statements are explained (with the same parameters) just before they run. */
    private static DataSource explaining(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(QueryPlanTest.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            Object result = invoke(real, method, args);
            if (result instanceof Connection connection) {
                return explaining(connection);
            }
            return result;
        });
    }

    private static Connection explaining(Connection real) {
        return (Connection) Proxy.newProxyInstance(QueryPlanTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            Object result = invoke(real, method, args);
            if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                return explaining(real, statement, (String) args[0]);
            }
            if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                return explaining(real, statement);
            }
            return result;
        });
    }

    private static PreparedStatement explaining(Connection connection, PreparedStatement real, String sql) {
        Map<Integer, Object> params = new TreeMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(QueryPlanTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                        params.put(index, name.equals("setNull") ? null : args[1]);
                    }
                    if (name.startsWith("execute") && (args == null || args.length == 0)) {
                        explain(connection, sql, params);
                    }
                    return invoke(real, method, args);
                });
    }

    private static Statement explaining(Connection connection, Statement real) {
        return (Statement) Proxy.newProxyInstance(QueryPlanTest.class.getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("execute") && args != null && args.length >= 1 && args[0] instanceof String sql) {
                explain(connection, sql, Map.of());
            }
            return invoke(real, method, args);
        });
    }

    private static void explain(Connection connection, String sql, Map<Integer, Object> params) throws SQLException {
        String head = sql.stripLeading().toUpperCase();
        if (!(head.startsWith("SELECT") || head.startsWith("UPDATE") || head.startsWith("DELETE") || head.startsWith("WITH"))
                || head.contains("GET_LOCK") || head.contains("RELEASE_LOCK")) {
            return;     // inserts of given values read nothing
        }
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
            for (Map.Entry<Integer, Object> param : params.entrySet()) {
                explain.setObject(param.getKey(), param.getValue());
            }
            try (ResultSet rs = explain.executeQuery()) {
                while (rs.next()) {
                    steps.add(new Step(rs.getString("table"), rs.getString("type"), rs.getString("key"), rs.getString("Extra")));
                }
            }
        }
        synchronized (PLANS) {
            PLANS.add(new Plan(sql, steps));
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
