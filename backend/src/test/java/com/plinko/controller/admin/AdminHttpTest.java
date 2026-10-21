package com.plinko.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.security.TestTotp;
import com.plinko.bo.security.Totp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review 5.4: the whole application on the production MySQL version, called over real HTTP. Every admin endpoint of the
 * role matrix (RoleMatrixTest.EXPECTED, generated from the controllers) as anonymous, VIEWER, OPERATOR and ADMIN, each
 * signed in for real with password and 2FA; and the admin panel kept off the game port and the game API off the
 * admin port, also for /%61dmin, /admin;x, //admin and /ADMIN. Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminHttpTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");
    private static final int ADMIN_PORT = freePort();
    private static final String PASSWORD = "Final-password-123";

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Started and migrated once, before the application (which runs with the restricted user and no migrations). */
    private static synchronized String database() {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
            try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
                s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
                s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
                s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            com.plinko.migration.Migrator.configure(MYSQL.getJdbcUrl(), "owner", "owner-password-123", "plinko", "app")
                    .load().migrate();
        }
        return MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=UTC";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        registry.add("spring.datasource.url", AdminHttpTest::database);
        registry.add("spring.datasource.username", () -> "app");
        registry.add("spring.datasource.password", () -> "app-password-123");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("plinko.admin-port", () -> Integer.toString(ADMIN_PORT));
        registry.add("plinko.admin.bootstrap", () -> "false");
        registry.add("plinko.round-signing-key", () -> "k".repeat(40));
        registry.add("plinko.pam.base-url", () -> "https://pam.invalid");
        registry.add("plinko.pam.api-key", () -> "key");
        registry.add("plinko.pam.secret", () -> "s".repeat(40));
        registry.add("plinko.pam.timeout-ms", () -> "1000");
        // the mock wallet's admin pages are in the matrix too
        registry.add("plinko.mock-pam.enabled", () -> "true");
    }

    @Autowired
    private Environment environment;
    @Autowired
    private AdminUserService users;

    private int gamePort;
    private final Map<String, Browser> browsers = new LinkedHashMap<>();

    @BeforeAll
    void signIn() throws Exception {
        gamePort = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        browsers.put("anonymous", new Browser());
        for (String role : List.of("VIEWER", "OPERATOR", "ADMIN")) {
            String username = "http-" + role.toLowerCase();
            String secret = Totp.newSecret();
            assertThat(users.create(username, "Temporary-password-1", role, null)).isEmpty();
            users.enrollTotp(username, secret, 0);
            assertThat(users.changePassword(username, "Temporary-password-1", PASSWORD)).isEmpty();
            Browser browser = new Browser();
            browser.signIn(username, secret);
            browsers.put(role, browser);
        }
    }

    /** One visitor: their own cookies (so their own session) and the CSRF token of the last page they loaded. */
    private final class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        String csrf;

        Browser() throws Exception {
            csrf = token(get(ADMIN_PORT, "/admin/login").body());
        }

        void signIn(String username, String secret) throws Exception {
            HttpResponse<String> response = post(ADMIN_PORT, "/admin/login", Map.of("username", username, "password", PASSWORD,
                    "code", TestTotp.now(secret)));
            assertThat(response.headers().firstValue("Location")).as(username + " signed in").hasValueSatisfying(l -> assertThat(l).endsWith("/admin"));
            // a new session after sign-in: its own CSRF token, from a page with a form
            csrf = token(get(ADMIN_PORT, "/admin").body());
        }

        HttpResponse<String> get(int port, String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(int port, String path, Map<String, String> form) throws Exception {
            Map<String, String> all = new LinkedHashMap<>(form);
            all.put("_csrf", csrf);
            StringBuilder body = new StringBuilder();
            all.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private static String token(String page) {
        Matcher m = CSRF.matcher(page);
        assertThat(m.find()).as("a CSRF token on the page").isTrue();
        return m.group(1);
    }

    /** What a caller of this role should get: true = allowed through the role checks. */
    private static boolean allowed(String rule, String caller) {
        return switch (rule) {
            case "PUBLIC (sign-in step)" -> true;
            case "ENROLL (sign-in step)", "PASSWORD_CHANGE (sign-in step)" -> false;
            case "VIEWER", "VIEWER (own account)" -> !caller.equals("anonymous");
            case "OPERATOR" -> caller.equals("OPERATOR") || caller.equals("ADMIN");
            case "ADMIN" -> caller.equals("ADMIN");
            default -> throw new IllegalArgumentException(rule);
        };
    }

    /**
     * Every parameter an admin action takes, with harmless values: Spring binds the parameters before the role check runs,
     * so an action called without them answers 400 to everyone and its role would not be tested.
     */
    private static final Map<String, String> FORM = Map.ofEntries(
            Map.entry("blocked", "false"), Map.entry("enabled", "true"), Map.entry("rows", "8"), Map.entry("risk", "0"),
            Map.entry("boostMultiplier", "2"), Map.entry("boostWeight", "100"), Map.entry("multiplier", "1"), Map.entry("probability", "1"),
            Map.entry("action", "close"), Map.entry("balance", "10"), Map.entry("code", "000000"), Map.entry("confirmPassword", "x"),
            Map.entry("currency", "EUR"), Map.entry("currentPassword", "not-the-password"), Map.entry("defaultBet", "1"),
            Map.entry("defaultTheme", "bombs-away"), Map.entry("goldenChance", "0"), Map.entry("hotBoost", "1"), Map.entry("hotChance", "0"),
            Map.entry("maxBalls", "10"), Map.entry("maxBet", "10"), Map.entry("minBet", "0.10"), Map.entry("meterCharges", "3"),
            Map.entry("meterFree", "1"), Map.entry("meterMin", "2"), Map.entry("meterShare", "1"), Map.entry("newPassword", "x"),
            Map.entry("password", "x"), Map.entry("reason", "role matrix test"), Map.entry("role", "VIEWER"), Map.entry("targetRtp", "96"),
            Map.entry("username", "nobody-matrix"), Map.entry("amount", "0"),
            // refused as written wrong: nothing is proposed
            Map.entry("maxAmounts", "x"), Map.entry("otherAmounts", "x"));

    private static String concrete(String path) {
        return path.replace("{id}", "1").replace("{playerId}", "nobody").replace("{username}", "nobody").replace("{action}", "noop");
    }

    @Test
    void everyAdminEndpointAnswersEachRoleAsTheMatrixSays() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> endpoint : RoleMatrixTest.EXPECTED.entrySet()) {
            String verb = endpoint.getKey().substring(0, endpoint.getKey().indexOf(' '));
            String path = concrete(endpoint.getKey().substring(verb.length() + 1));
            for (Map.Entry<String, Browser> caller : browsers.entrySet()) {
                Browser browser = caller.getValue();
                HttpResponse<String> response = "GET".equals(verb) ? browser.get(ADMIN_PORT, path) : browser.post(ADMIN_PORT, path, FORM);
                int status = response.statusCode();
                String location = response.headers().firstValue("Location").orElse("");
                boolean toLogin = status == 302 && location.contains("/admin/login") && !location.contains("/admin/login/code");
                // a sign-in step may send a visitor back to the sign-in itself (the code screen without a password first)
                boolean refused = status == 403 || (toLogin && !endpoint.getValue().startsWith("PUBLIC"));
                boolean expected = allowed(endpoint.getValue(), caller.getKey());
                if (expected == refused) {
                    wrong.add(endpoint.getKey() + " as " + caller.getKey() + " (" + endpoint.getValue() + "): " + status + " " + location);
                }
                if (refused && !expected) {
                    // refused the right way: anonymous to the sign-in, a signed-in admin with "denied"
                    if (caller.getKey().equals("anonymous") ? !toLogin : status != 403) {
                        wrong.add(endpoint.getKey() + " as " + caller.getKey() + ": refused with " + status + " " + location);
                    }
                }
            }
        }
        assertThat(wrong).as("endpoints answering a role differently from the matrix").isEmpty();
        // the sign-ins above are still valid: no request ended a session by mistake
        assertThat(browsers.get("VIEWER").get(ADMIN_PORT, "/admin").statusCode()).isEqualTo(200);
    }

    @Autowired
    private com.plinko.repo.MockWalletRepository wallets;

    private long walletsNamed(String username) {
        return wallets.all().stream().filter(w -> username.equals(w.get("username"))).count();
    }

    @Test
    void aGuardedFormIsDoneOnceWithItsPagesOneTimeToken() throws Exception {
        Browser admin = browsers.get("ADMIN");
        Matcher token = Pattern.compile("name=\"formToken\" value=\"([^\"]+)\"").matcher(admin.get(ADMIN_PORT, "/admin/mock-pam").body());
        assertThat(token.find()).as("the page's one-time token").isTrue();
        Map<String, String> form = Map.of("username", "http-once", "balance", "5", "formToken", token.group(1));
        assertThat(admin.post(ADMIN_PORT, "/admin/mock-pam", form).statusCode()).isEqualTo(302);
        assertThat(admin.get(ADMIN_PORT, "/admin/mock-pam").body()).contains("Test wallet created");
        // the same form again (a double click, a resend): refused
        admin.post(ADMIN_PORT, "/admin/mock-pam", form);
        assertThat(admin.get(ADMIN_PORT, "/admin/mock-pam").body()).contains("This form was already sent; reload the page");
        // and one without a token
        admin.post(ADMIN_PORT, "/admin/mock-pam", Map.of("username", "http-none", "balance", "5"));
        assertThat(admin.get(ADMIN_PORT, "/admin/mock-pam").body()).contains("This form was already sent; reload the page");
        assertThat(walletsNamed("http-once")).isEqualTo(1);
        assertThat(walletsNamed("http-none")).isZero();
    }

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** Review 3.10d: the round lists read only the columns they show, with the scan's verdict as the signature column. */
    @Test
    void theRoundListsShowTheScansVerdict() throws Exception {
        jdbc.sql("INSERT INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at) "
                + "VALUES ('http-lists', 'lister', 'USD', 10, FALSE, NOW(3), NOW(3))").update();
        long player = jdbc.sql("SELECT id FROM player WHERE external_id = 'http-lists'").query(Long.class).single();
        jdbc.sql("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at) "
                + "VALUES ('11570000-0000-4000-8000-000000000001', ?, 's', 8, 1, 1, 2, 2, 'USD', 'PENDING', NOW(3), NOW(3))").param(player).update();
        Browser viewer = browsers.get("VIEWER");
        HttpResponse<String> list = viewer.get(ADMIN_PORT, "/admin/rounds?player=" + player);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("11570000").contains("<th>Signature</th>");
        HttpResponse<String> csv = viewer.get(ADMIN_PORT, "/admin/rounds.csv?player=" + player);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body()).contains("\"Signature\"").contains("11570000-0000-4000-8000-000000000001");
        HttpResponse<String> page = viewer.get(ADMIN_PORT, "/admin/players/" + player);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("11570000");
        HttpResponse<String> dashboard = viewer.get(ADMIN_PORT, "/admin");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        // (its latest rounds may be the ones of up to 20 seconds ago: the view is shared that long)
        assertThat(dashboard.body()).contains("failing signature check").containsAnyOf("no scan finished yet", "UTC checked");
    }

    @Autowired
    private com.plinko.repo.GameRepository game;

    private HttpResponse<String> gameEvent(String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gamePort + "/api/game/event"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Authorization", "Bearer " + session);
        }
        return browsers.get("anonymous").http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Review 7.4: the game reports what it showed on the game port; the player's admin page shows it. */
    @Test
    void theGamesShownEventsAreRecordedForTheSessionAndShownToAdmins() throws Exception {
        com.plinko.repo.GameRepository.Player player = game.upsertPlayer("http-events", "eventful", "USD", new java.math.BigDecimal("10.00"));
        String session = "http-events-" + System.nanoTime();
        game.insertSession(new com.plinko.repo.GameRepository.Session(session, player.id(), "token", java.time.Instant.now().plusSeconds(600), null));

        HttpResponse<String> shown = gameEvent(session, "{\"type\":\"REALITY_CHECK_SHOWN\",\"at\":" + System.currentTimeMillis() + "}");
        assertThat(shown.statusCode()).isEqualTo(200);
        assertThat(shown.body()).contains("\"recorded\":true");
        assertThat(gameEvent(session, "{\"type\":\"REALITY_CHECK_CONTINUED\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> unknown = gameEvent(session, "{\"type\":\"JACKPOT\"}");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("INVALID_REQUEST");
        assertThat(gameEvent(session, "{\"type\":\"REALITY_CHECK_SHOWN\",\"at\":\"soon\"}").statusCode()).isEqualTo(400);
        assertThat(gameEvent(null, "{\"type\":\"REALITY_CHECK_SHOWN\"}").body()).contains("SESSION_EXPIRED");
        // not on the admin port
        HttpResponse<String> onAdminPort = browsers.get("anonymous").http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + ADMIN_PORT
                + "/api/game/event")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(onAdminPort.statusCode()).isEqualTo(404);

        String page = browsers.get("VIEWER").get(ADMIN_PORT, "/admin/players/" + player.id()).body();
        assertThat(page).contains("Responsible gambling shown").contains("Reality check shown").contains("Reality check, player continued")
                .contains("1 times, last");
    }

    @Test
    void anActionWithoutTheCsrfTokenIsRefusedEvenForAnAdmin() throws Exception {
        Browser admin = browsers.get("ADMIN");
        String token = admin.csrf;
        admin.csrf = "forged";
        try {
            assertThat(admin.post(ADMIN_PORT, "/admin/settings/game-enabled", Map.of("enabled", "false")).statusCode()).isEqualTo(403);
        } finally {
            admin.csrf = token;
        }
    }

    @Test
    void theAdminPanelIsNeverServedOnTheGamePort() throws Exception {
        Browser anonymous = browsers.get("anonymous");
        Browser admin = browsers.get("ADMIN");
        for (String path : List.of("/admin", "/admin/", "/admin/login", "/%61dmin", "/%61dmin/login", "/admin;x", "/admin;x/login", "//admin",
                "//admin/login", "/ADMIN", "/ADMIN/login", "/admin-assets/admin.css", "/actuator/prometheus", "/%2Fadmin/login")) {
            for (Browser browser : List.of(anonymous, admin)) {
                HttpResponse<String> response = browser.get(gamePort, path);
                assertThat(response.statusCode()).as("%s on the game port", path).isIn(302, 400, 403, 404);
                assertThat(response.body()).as("%s on the game port", path).doesNotContain("name=\"password\"").doesNotContain("_csrf");
                if (response.statusCode() == 302) {
                    // only ever to the sign-in page, which the game port doesn't serve either
                    String location = response.headers().firstValue("Location").orElseThrow();
                    assertThat(browser.get(gamePort, URI.create(location).getRawPath()).statusCode()).as("%s -> %s", path, location).isEqualTo(404);
                }
            }
        }
    }

    @Test
    void theAdminPortServesTheAdminPanelAndNotTheGame() throws Exception {
        Browser anonymous = browsers.get("anonymous");
        // the container decodes and normalises the path before the port check: these all reach the admin panel
        for (String path : List.of("/admin/login", "/%61dmin/login", "/admin;x/login", "//admin/login")) {
            HttpResponse<String> response = anonymous.get(ADMIN_PORT, path);
            assertThat(response.statusCode()).as(path).isIn(200, 400);
        }
        assertThat(anonymous.get(ADMIN_PORT, "/admin/login").body()).contains("name=\"password\"");
        assertThat(anonymous.get(ADMIN_PORT, "/actuator/prometheus").statusCode()).isEqualTo(200);
        // the game API and anything else that is not the admin panel is not on the admin port
        for (String path : List.of("/api/game/session", "/ADMIN/login", "/", "/index.html")) {
            assertThat(anonymous.get(ADMIN_PORT, path).statusCode()).as(path).isEqualTo(404);
        }
        HttpResponse<String> gameApi = anonymous.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + ADMIN_PORT + "/api/game/session"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(gameApi.statusCode()).isEqualTo(404);
        // while the game port does answer the game API
        HttpResponse<String> onGamePort = anonymous.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + gamePort + "/api/game/session"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(onGamePort.statusCode()).isNotEqualTo(404);
    }
}
