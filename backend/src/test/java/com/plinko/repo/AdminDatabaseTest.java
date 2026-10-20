package com.plinko.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.security.ApprovalPolicy;
import com.plinko.bo.settings.SettingsService;
import com.plinko.common.Crypto;
import com.plinko.common.Integrity;
import com.plinko.model.ChangeRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review 5.4: the admin-side rules that rest on the database, on the production MySQL version: the integrity ledger,
 * change requests approved in parallel, after a rejection, twice or after 7 days, and the settings cache shared by two
 * instances. Skipped where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminDatabaseTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("plinko").withUsername("owner")
            .withPassword("owner-password-123").withCommand("--character-set-server=utf8mb4");

    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private static final Integrity INTEGRITY = new Integrity(TestFixtures.properties());
    private static IntegrityLedger ledger;

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); Statement s = root.createStatement()) {
            s.execute("GRANT ALL PRIVILEGES ON plinko.* TO 'owner'@'%' WITH GRANT OPTION");
            s.execute("CREATE USER 'app'@'%' IDENTIFIED BY 'app-password-123'");
            s.execute("SET GLOBAL log_bin_trust_function_creators = 1");
        }
        com.plinko.migration.Migrator.configure(MYSQL.getJdbcUrl(), "owner", "owner-password-123", "plinko", "app")
                .load().migrate();
        DataSource app = new DriverManagerDataSource(MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC", "app", "app-password-123");
        jdbc = JdbcClient.create(app);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(app));
        ledger = new IntegrityLedger(jdbc, INTEGRITY);
    }

    // ---------------------------------------------------------------- IntegrityLedger

    private static String content(String text) {
        return Crypto.sha256Hex(text);
    }

    @Test
    void theNewestRecordedVersionIsTheOnlyOneAccepted() {
        String entity = "test:" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(ledger.exists(entity)).isFalse();
        assertThat(ledger.isLatest(entity, content("v1"))).isFalse();
        ledger.record(entity, content("v1"));
        assertThat(ledger.isLatest(entity, content("v1"))).isTrue();
        ledger.record(entity, content("v2"));
        // an old, genuinely signed copy put back into the record no longer passes
        assertThat(ledger.isLatest(entity, content("v1"))).isFalse();
        assertThat(ledger.isLatest(entity, content("v2"))).isTrue();
        assertThat(ledger.latestContent(entity)).contains(content("v2"));
        assertThat(ledger.isLatest(entity, null)).isFalse();
    }

    @Test
    void anEntryWrittenWithoutTheKeyIsIgnored() {
        String entity = "test:" + UUID.randomUUID().toString().substring(0, 8);
        ledger.record(entity, content("genuine"));
        // someone with database access appends a newer version pointing at their own edit
        forged(entity, 2, content("edited"));
        assertThat(ledger.isLatest(entity, content("edited"))).isFalse();
        assertThat(ledger.isLatest(entity, content("genuine"))).isTrue();
        // and the next genuine version counts from the forged one's number without being hidden by it
        ledger.record(entity, content("genuine-2"));
        assertThat(ledger.isLatest(entity, content("genuine-2"))).isTrue();
    }

    @Test
    void theShortReadOfTheNewestTenGivesTheSameAnswerAsReadingEverything() {
        String entity = "test:" + UUID.randomUUID().toString().substring(0, 8);
        ledger.record(entity, content("old"));
        ledger.record(entity, content("current"));
        // more than 10 forged entries on top: the newest 10 hold no valid one, so every entry is read
        for (int v = 3; v < 16; v++) {
            forged(entity, v, content("forged-" + v));
        }
        assertThat(ledger.latestContent(entity)).contains(content("current")).isEqualTo(newestValidOfAll(entity));
        // with a valid entry among the newest 10, the short read finds the same one as the full read
        ledger.record(entity, content("newest"));
        assertThat(ledger.latestContent(entity)).contains(content("newest")).isEqualTo(newestValidOfAll(entity));
    }

    private static void forged(String entity, long version, String content) {
        jdbc.sql("INSERT INTO integrity_ledger (entity, version, content, signature, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(entity, version, content, "f".repeat(64), Timestamp.from(Instant.now())).update();
    }

    /** What the ledger answers, worked out here over every entry. */
    private static java.util.Optional<String> newestValidOfAll(String entity) {
        return jdbc.sql("SELECT version, content, signature FROM integrity_ledger WHERE entity = ? ORDER BY version DESC, id DESC")
                .param(entity).query((rs, n) -> new String[] {rs.getString("version"), rs.getString("content"), rs.getString("signature")})
                .list().stream()
                .filter(e -> INTEGRITY.verify(e[2], "ledger", entity, Long.parseLong(e[0]), e[1]))
                .map(e -> e[1]).findFirst();
    }

    // ---------------------------------------------------------------- ChangeRequestService

    private final SettingsService settings = mock(SettingsService.class);
    private final ApprovalPolicy policy = mock(ApprovalPolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final ChangeRequestRepository changes = new ChangeRequestRepository(jdbc);
    private final ChangeRequestService service = new ChangeRequestService(changes, tools.jackson.databind.json.JsonMapper.builder().build(),
            mock(com.plinko.bo.odds.OddsService.class), settings, audit, mock(com.plinko.bo.security.AdminUserService.class), policy,
            INTEGRITY, ledger, TestFixtures.properties(), mock(com.plinko.bo.bonus.BonusService.class),
            mock(com.plinko.bo.game.RoundSettlementService.class));

    /** A limits change proposed by alice; each test its own limits (one pending change per kind at a time). */
    private long proposeLimits() {
        when(settings.limits()).thenReturn(new SettingsService.GameLimits(new BigDecimal("0.10"), new BigDecimal("100.00"), BigDecimal.ONE, 100));
        rejectPendingLimits();
        return transaction.execute(s -> service.proposeLimits(
                new SettingsService.GameLimits(new BigDecimal("0.20"), new BigDecimal("50.00"), BigDecimal.ONE, 50), "alice"));
    }

    private void rejectPendingLimits() {
        changes.pendingFor(ChangeRequestService.LIMITS, "limits").ifPresent(c -> changes.decide(c.id(), "CANCELLED", "test", Instant.now(), null));
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM change_request WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void twoAdminsApprovingAtTheSameMomentApplyTheChangeOnce() throws Exception {
        long id = proposeLimits();
        AtomicInteger applied = new AtomicInteger();
        CountDownLatch applying = new CountDownLatch(1);
        doAnswer(i -> {
            applied.incrementAndGet();
            applying.countDown();
            Thread.sleep(500);      // the first approval holds the request's row lock a while
            return null;
        }).when(settings).applyLimits(any());
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable bob = () -> outcomes.add(approve(id, "bob"));
        Thread first = Thread.ofVirtual().start(bob);
        assertThat(applying.await(10, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofVirtual().start(() -> outcomes.add(approve(id, "carol")));
        first.join(15_000);
        second.join(15_000);
        assertThat(applied).hasValue(1);
        assertThat(outcomes).containsExactlyInAnyOrder("approved", "Change request was already approved");
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT decided_by FROM change_request WHERE id = ?").param(id).query(String.class).single()).isEqualTo("bob");
    }

    private String approve(long id, String approver) {
        try {
            transaction.executeWithoutResult(s -> service.approve(id, approver, null));
            return "approved";
        } catch (ChangeRequestService.ChangeException e) {
            return e.getMessage();
        }
    }

    @Test
    void aChangeIsApprovedOnlyOnce() {
        long id = proposeLimits();
        assertThat(approve(id, "bob")).isEqualTo("approved");
        assertThat(approve(id, "carol")).isEqualTo("Change request was already approved");
        verify(settings, times(1)).applyLimits(any());
    }

    @Test
    void aRejectedChangeCantBeApprovedAfterwards() {
        long id = proposeLimits();
        transaction.executeWithoutResult(s -> service.reject(id, "carol", "no"));
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(approve(id, "bob")).isEqualTo("Change request was already rejected");
        verify(settings, times(0)).applyLimits(any());
    }

    @Test
    void aRejectedChangeSetBackToPendingInTheDatabaseIsRefused() {
        long id = proposeLimits();
        transaction.executeWithoutResult(s -> service.reject(id, "carol", "no"));
        jdbc.sql("UPDATE change_request SET status = 'PENDING', decided_by = NULL, decided_at = NULL WHERE id = ?").param(id).update();
        assertThat(approve(id, "bob")).contains("modified outside the admin panel");
        verify(settings, times(0)).applyLimits(any());
    }

    @Test
    void aChangeExpiresSevenDaysAfterItWasProposed() {
        long id = proposeLimits();
        ChangeRequest proposed = changes.lock(id).orElseThrow();
        assertThat(Duration.between(proposed.requestedAt(), proposed.expiresAt())).isEqualTo(Duration.ofDays(7));
        rejectPendingLimits();

        // a genuinely signed proposal made 7 days and a minute ago
        Instant at = Instant.now().minus(Duration.ofDays(7)).minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MILLIS);
        long old = signedRequest(at, at.plus(Duration.ofDays(7)));
        assertThat(approve(old, "bob")).isEqualTo("This change has expired; reject it and propose it again");
        // one made 6 days ago is still valid (it then fails only on what it changes, which this test does not set up)
        Instant recent = Instant.now().minus(Duration.ofDays(6)).truncatedTo(ChronoUnit.MILLIS);
        long fresh = signedRequest(recent, recent.plus(Duration.ofDays(7)));
        assertThat(approve(fresh, "bob")).doesNotContain("expired");
    }

    /** A LIMITS request signed and recorded as the service does it, at the given times. */
    private long signedRequest(Instant requestedAt, Instant expiresAt) {
        rejectPendingLimits();
        String payload = "{\"minBet\":0.3,\"maxBet\":40,\"defaultBet\":1,\"maxBalls\":40}";
        String summary = "Bet limits (test)";
        String base = "b".repeat(64);
        String signature = INTEGRITY.sign("change", ChangeRequestService.LIMITS, "limits", payload, summary, "alice", requestedAt.toEpochMilli(),
                base, expiresAt.toEpochMilli());
        // in one transaction, as the service runs it: the new id is read on the connection that inserted it
        return transaction.execute(s -> {
            long id = changes.insert(ChangeRequestService.LIMITS, "limits", payload, summary, "alice", requestedAt, base, expiresAt, signature);
            ledger.record("change:" + id, Crypto.sha256Hex("PENDING|" + signature));
            return id;
        });
    }

    // ---------------------------------------------------------------- N3 #3: settlement limits, changed by two admins

    @Test
    void theSettlementLimitsChangeOnlyWhenASecondAdminApproves() {
        SettingsService stored = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        transaction.executeWithoutResult(s -> stored.recordInitial());
        // a blocked round whose own win (1,800) is above the environment's limit
        String roundId = "5e771e00-0000-4000-8000-000000000001";
        GameRepository game = mock(GameRepository.class);
        Instant blocked = Instant.now().minus(Duration.ofHours(1));
        when(game.findRound(roundId)).thenReturn(java.util.Optional.of(new GameRepository.Round(roundId, 7, "s", 8, 0, new BigDecimal("2.00"), 1,
                new BigDecimal("2.00"), new BigDecimal("1800.00"), "EUR", "TAMPERED", "0", "900", null, "f".repeat(64), blocked, blocked)));
        when(game.transactionsOfRound(roundId)).thenReturn(List.of(new GameRepository.Transaction("debit-1", roundId, 7, "DEBIT",
                new BigDecimal("2.00"), "OK", null, "r", null, 1, null, null, null)));
        com.plinko.bo.game.RoundSettlementService settlement = new com.plinko.bo.game.RoundSettlementService(game, mock(SettlementRepository.class),
                mock(com.plinko.pam.PamClient.class), INTEGRITY, audit, mock(com.plinko.bo.game.WinCheck.class), stored,
                new BigDecimal("1000"), "EUR=1000", new BigDecimal("100"), "", 90, 24, 3);
        ChangeRequestService withStored = new ChangeRequestService(changes, tools.jackson.databind.json.JsonMapper.builder().build(),
                mock(com.plinko.bo.odds.OddsService.class), stored, audit, mock(com.plinko.bo.security.AdminUserService.class), policy,
                INTEGRITY, ledger, TestFixtures.properties(), mock(com.plinko.bo.bonus.BonusService.class), settlement);
        com.plinko.bo.game.RoundSettlementService.Proposal pay = new com.plinko.bo.game.RoundSettlementService.Proposal(roundId, "PAY",
                new BigDecimal("1800.00"), "Ticket 7: the round was genuine, blocked by a bug");

        // the environment's values are stored once as the first value, recorded in the ledger (the settings stay trusted)
        transaction.executeWithoutResult(s -> settlement.storeInitialLimits());
        assertThat(stored.settlementLimit(SettingsService.SETTLEMENT_MAX)).contains("EUR=1000,USD=1000");
        assertThat(stored.settlementLimit(SettingsService.SETTLEMENT_OTHER)).contains("USD=100");
        assertThat(stored.trusted()).isTrue();
        assertThat(settlement.problem(pay)).get().asString().contains("settlement limit of 1000 EUR");

        // written wrong: refused before anything is proposed
        for (String[] wrong : List.of(new String[] {"EUR:2000", "EUR=100"}, new String[] {"EUR=2000", ""}, new String[] {"ABC=2000", "EUR=100"},
                new String[] {"EUR=2000.001", "EUR=100"}, new String[] {"EUR=100", "EUR=200"})) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(s -> withStored.proposeSettlementLimits(wrong[0], wrong[1], "alice")))
                    .as(String.join(" / ", wrong)).isInstanceOf(ChangeRequestService.ChangeException.class);
        }
        assertThat(changes.pendingFor(ChangeRequestService.SETTLEMENT_LIMITS, "settlement-limits")).isEmpty();

        long id = transaction.execute(s -> withStored.proposeSettlementLimits("USD=1500, EUR=2000", "EUR=150,USD=100", "alice"));
        assertThat(changes.lock(id).orElseThrow().summary())
                .isEqualTo("Settlement limits: highest payment EUR=1000,USD=1000 -> EUR=2000,USD=1500; any amount other than the round's own win "
                        + "USD=100 -> EUR=150,USD=100");
        assertThat(settlement.maxAmount("EUR").orElseThrow()).as("nothing changes before the approval").isEqualByComparingTo("1000");

        // the proposer can't approve it, even where a second approver is not required
        assertThat(approveWith(withStored, id, "alice")).contains("other than the one who proposed it");
        assertThat(status(id)).isEqualTo("PENDING");

        assertThat(approveWith(withStored, id, "bob")).isEqualTo("approved");
        assertThat(stored.settlementLimit(SettingsService.SETTLEMENT_MAX)).contains("EUR=2000,USD=1500");
        assertThat(stored.trusted()).as("recorded in the ledger").isTrue();
        assertThat(settlement.maxAmount("EUR").orElseThrow()).isEqualByComparingTo("2000");
        assertThat(settlement.otherAmount("EUR").orElseThrow()).isEqualByComparingTo("150");
        assertThat(settlement.problem(pay)).as("the settlement uses the new limit").isEmpty();

        // the environment's values are not stored again over the approved ones
        transaction.executeWithoutResult(s -> settlement.storeInitialLimits());
        assertThat(stored.settlementLimit(SettingsService.SETTLEMENT_MAX)).contains("EUR=2000,USD=1500");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> withStored.proposeSettlementLimits("EUR=2000,USD=1500", "EUR=150,USD=100", "bob")))
                .hasMessageContaining("already in use");
    }

    private String approveWith(ChangeRequestService with, long id, String approver) {
        try {
            transaction.executeWithoutResult(s -> with.approve(id, approver, null));
            return "approved";
        } catch (ChangeRequestService.ChangeException e) {
            return e.getMessage();
        }
    }

    // ---------------------------------------------------------------- the settings cache on two instances

    @Test
    void anApprovedSettingsChangeOnOneInstanceReachesTheOtherAtItsNextVersionRead() throws Exception {
        SettingsService one = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        SettingsService two = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        transaction.executeWithoutResult(s -> one.recordInitial());
        transaction.executeWithoutResult(s -> one.setGameEnabled(true));
        assertThat(two.gameEnabled()).isTrue();

        transaction.executeWithoutResult(s -> one.setGameEnabled(false));
        assertThat(one.gameEnabled()).as("the instance that made the change, at once").isFalse();
        // the other reads the version at most once a second: it stops the game within that, not within the minute
        long start = System.nanoTime();
        while (two.gameEnabled() && System.nanoTime() - start < 5_000_000_000L) {
            Thread.sleep(50);
        }
        assertThat(two.gameEnabled()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        transaction.executeWithoutResult(s -> one.setGameEnabled(true));
    }

    @Test
    void aTransactionThatChangesTheSettingsSeesItsOwnChangeAndNobodyElseDoesUntilItCommits() throws Exception {
        // the bonus approval: the new bonus is saved, then the odds tables are checked against it in the same transaction
        SettingsService one = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        transaction.executeWithoutResult(s -> one.recordInitial());
        transaction.executeWithoutResult(s -> one.setBonus("{\"before\":true}"));
        assertThat(one.rawValues().get("game.bonus")).isEqualTo("{\"before\":true}");    // cached
        java.util.concurrent.CountDownLatch changed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch looked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> seenInside = new java.util.concurrent.atomic.AtomicReference<>();
        Thread approval = new Thread(() -> transaction.executeWithoutResult(s -> {
            one.setBonus("{\"after\":true}");
            seenInside.set(one.rawValues().get("game.bonus"));
            changed.countDown();
            try {
                looked.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        approval.start();
        assertThat(changed.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(seenInside.get()).as("inside the transaction").isEqualTo("{\"after\":true}");
        Thread.sleep(1100);    // past the version read's second
        assertThat(one.rawValues().get("game.bonus")).as("another request, before the commit").isEqualTo("{\"before\":true}");
        looked.countDown();
        approval.join();
        assertThat(one.rawValues().get("game.bonus")).as("after the commit").isEqualTo("{\"after\":true}");
    }

    @Test
    void aDirectDatabaseEditStopsTheGameOnEveryInstanceAtTheMinutesReRead() throws Exception {
        SettingsService one = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        SettingsService two = new SettingsService(new SettingsRepository(jdbc), TestFixtures.properties(), ledger);
        transaction.executeWithoutResult(s -> one.recordInitial());
        transaction.executeWithoutResult(s -> one.setGameEnabled(true));
        assertThat(one.gameEnabled()).isTrue();
        assertThat(two.gameEnabled()).isTrue();
        String before = jdbc.sql("SELECT value FROM setting WHERE name = 'game.maxBet'").query(String.class).optional().orElse(null);
        try {
            // an edit made in the database does not raise the version: the instances notice it at their minute's re-read
            jdbc.sql("INSERT INTO setting (name, value, updated_at) VALUES ('game.maxBet', '999.00', NOW()) "
                    + "ON DUPLICATE KEY UPDATE value = VALUES(value)").update();
            one.refresh();
            two.refresh();
            assertThat(one.gameEnabled()).isFalse();
            assertThat(two.gameEnabled()).isFalse();
            assertThat(one.trusted()).isFalse();
        } finally {
            if (before == null) {
                jdbc.sql("DELETE FROM setting WHERE name = 'game.maxBet'").update();
            } else {
                jdbc.sql("UPDATE setting SET value = ? WHERE name = 'game.maxBet'").param(before).update();
            }
        }
        one.refresh();
        assertThat(one.gameEnabled()).as("put back as recorded, trusted again").isTrue();
        // and that re-read runs at least once a minute
        org.springframework.scheduling.annotation.Scheduled every = SettingsService.class.getMethod("refresh")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(every.fixedDelay()).isPositive().isLessThanOrEqualTo(60_000);
    }
}
