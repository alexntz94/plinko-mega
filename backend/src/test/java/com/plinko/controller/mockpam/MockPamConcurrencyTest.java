package com.plinko.controller.mockpam;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.plinko.TestFixtures;
import com.plinko.common.Crypto;
import com.plinko.repo.MockWalletRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Review N14.7: two copies of a new transaction id sent to the mock wallet at the same moment get the same answer and move money once (MySQL). */
@Testcontainers(disabledWithoutDocker = true)
class MockPamConcurrencyTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");

    private static HikariDataSource app;
    private static JdbcClient jdbc;
    private static MockWalletRepository wallets;
    private static MockPamController pam;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ExecutorService THREADS = Executors.newFixedThreadPool(4);

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection root = root(); Statement s = root.createStatement()) {
            s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
        }
        com.plinko.migration.Migrator.configure(MYSQL.getJdbcUrl(), "owner", "owner-password-123", "plinko", "app")
                .load().migrate();
        app = new HikariDataSource();
        app.setJdbcUrl(MYSQL.getJdbcUrl());
        app.setUsername("app");
        app.setPassword("app-password-123");
        app.setMaximumPoolSize(6);
        jdbc = JdbcClient.create(app);
        wallets = new MockWalletRepository(jdbc);
        pam = new MockPamController(wallets, JSON, TestFixtures.properties(), new DataSourceTransactionManager(app));
    }

    @AfterAll
    static void close() {
        THREADS.shutdownNow();
        if (app != null) {
            app.close();
        }
    }

    private static Connection root() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static String wallet() {
        String player = "p-" + UUID.randomUUID();
        wallets.create(player, player.substring(0, 20), "EUR", new BigDecimal("100.00"), UUID.randomUUID().toString(), Instant.now());
        return player;
    }

    private static ResponseEntity<String> call(String endpoint, Map<String, Object> body) {
        String text = JSON.writeValueAsString(body);
        return pam.handle(endpoint, "key", Crypto.hmacSha256Hex(TestFixtures.properties().pam().secret(), text), text);
    }

    private static Map<String, Object> move(String player, String id, String round, String amount) {
        return Map.of("playerId", player, "transactionId", id, "roundId", round, "amount", new BigDecimal(amount), "currency", "EUR");
    }

    private static BigDecimal balance(String player) {
        return jdbc.sql("SELECT balance FROM mock_wallet WHERE player_id = ?").param(player).query(BigDecimal.class).single();
    }

    private static long recorded(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM mock_transaction WHERE transaction_id = ?").param(id).query(Long.class).single();
    }

    /** The application's statements now waiting to lock a wallet (innodb_trx is not read here: it lags inside a transaction). */
    private static long lockWaits(Connection root) throws SQLException {
        try (Statement s = root.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE user = 'app' AND command = 'Query' AND info LIKE '%FROM mock_wallet%FOR UPDATE%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The request for this endpoint; a refund gets its bet placed first. */
    private static Map<String, Object> request(String endpoint, String player, String id) {
        String round = "r-" + id;
        return switch (endpoint) {
            case "debit" -> move(player, id, round, "7.00");
            case "credit" -> move(player, id, round, "5.00");
            case "rollback" -> {
                assertThat(call("debit", move(player, "d-" + id, round, "7.00")).getStatusCode().value()).isEqualTo(200);
                yield Map.of("playerId", player, "transactionId", id, "originalTransactionId", "d-" + id, "roundId", round);
            }
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    private static BigDecimal expectedAfter(String endpoint) {
        return switch (endpoint) {
            case "debit" -> new BigDecimal("93.00");
            case "credit" -> new BigDecimal("105.00");
            default -> new BigDecimal("100.00");      // the bet taken and refunded once
        };
    }

    private static void assertOneMoveAndTheSameAnswer(String endpoint, String player, String id, List<ResponseEntity<String>> answers) {
        assertThat(answers).allSatisfy(a -> assertThat(a.getStatusCode().value()).as("%s", a.getBody()).isEqualTo(200));
        JsonNode first = JSON.readTree(answers.get(0).getBody());
        JsonNode second = JSON.readTree(answers.get(1).getBody());
        assertThat(first.path("transactionId").asString()).isEqualTo(id).isEqualTo(second.path("transactionId").asString());
        assertThat(first.path("reference").asString()).isEqualTo(second.path("reference").asString());
        assertThat(first.path("balance").decimalValue()).isEqualByComparingTo(second.path("balance").decimalValue())
                .isEqualByComparingTo(expectedAfter(endpoint));
        assertThat(first.path("repeated").asBoolean(false) ^ second.path("repeated").asBoolean(false))
                .as("one applied it, the other was told it is a repeat").isTrue();
        assertThat(recorded(id)).as("recorded once").isEqualTo(1);
        assertThat(balance(player)).as("money moved once").isEqualByComparingTo(expectedAfter(endpoint));
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit", "credit", "rollback"})
    void twoCopiesWaitingOnTheWalletTogetherGetOneAnswerAndMoveMoneyOnce(String endpoint) throws Exception {
        String player = wallet();
        String id = UUID.randomUUID().toString();
        Map<String, Object> body = request(endpoint, player, id);
        List<Future<ResponseEntity<String>>> copies = new ArrayList<>();
        try (Connection root = root()) {
            // another transaction holds the wallet, so both copies arrive and wait for it at the same moment
            root.setAutoCommit(false);
            try (Statement s = root.createStatement()) {
                s.executeQuery("SELECT * FROM plinko.mock_wallet WHERE player_id = '" + player + "' FOR UPDATE").close();
            }
            for (int i = 0; i < 2; i++) {
                copies.add(THREADS.submit(() -> call(endpoint, body)));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (lockWaits(root) < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(lockWaits(root)).as("both copies wait on the wallet").isEqualTo(2);
            root.commit();
        }
        List<ResponseEntity<String>> answers = new ArrayList<>();
        for (Future<ResponseEntity<String>> copy : copies) {
            answers.add(copy.get(20, TimeUnit.SECONDS));
        }
        assertOneMoveAndTheSameAnswer(endpoint, player, id, answers);
    }

    @RepeatedTest(10)
    void twoCopiesSentAtOnceGetOneAnswerAndMoveMoneyOnce() throws Exception {
        String player = wallet();
        String id = UUID.randomUUID().toString();
        Map<String, Object> body = move(player, id, "r-" + id, "7.00");
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<ResponseEntity<String>> send = () -> {
            together.await(5, TimeUnit.SECONDS);
            return call("debit", body);
        };
        Future<ResponseEntity<String>> one = THREADS.submit(send);
        Future<ResponseEntity<String>> two = THREADS.submit(send);
        assertOneMoveAndTheSameAnswer("debit", player, id, List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)));
    }
}
