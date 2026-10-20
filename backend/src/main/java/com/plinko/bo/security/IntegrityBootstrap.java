package com.plinko.bo.security;

import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.odds.OddsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Signs the admin accounts and odds tables that existed before integrity protection was added, exactly once.
 * The "done" marker is an audit-log entry, which the application's database user can add but never remove,
 * so deleting signatures later does not make the application sign tampered data: unsigned data stays blocked.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IntegrityBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrityBootstrap.class);
    private static final String DONE = "INTEGRITY_INITIALISED";
    private static final String LEDGER_DONE = "INTEGRITY_LEDGER_INITIALISED";
    private static final String V2_DONE = "ADMIN_SIGNATURES_V2";
    private static final String SETTINGS_DONE = "SETTINGS_LEDGER_INITIALISED";

    private final AdminUserService users;
    private final OddsService odds;
    private final com.plinko.bo.settings.SettingsService settings;
    private final AuditService audit;
    private final com.plinko.repo.IntegrityLedger ledger;
    private final com.plinko.common.KeyRing keys;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public IntegrityBootstrap(AdminUserService users, OddsService odds, com.plinko.bo.settings.SettingsService settings,
                              AuditService audit, com.plinko.repo.IntegrityLedger ledger, com.plinko.common.KeyRing keys,
                              org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.users = users;
        this.odds = odds;
        this.settings = settings;
        this.audit = audit;
        this.ledger = ledger;
        this.keys = keys;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!done(DONE)) {
            // Fresh install or upgrade from before signatures: signing also records each row in the ledger.
            users.signAll();
            odds.signAllStored();
            audit.log("system", DONE, "Signed the existing admin accounts and odds tables");
            audit.log("system", LEDGER_DONE, "Recorded them in the version ledger");
            audit.log("system", V2_DONE, "Admin accounts signed with 2FA secrets encrypted");
            log.info("Integrity protection initialised: existing admin accounts and odds tables signed");
        } else if (!done(LEDGER_DONE)) {
            // Upgrade from signatures without a ledger: record the correctly signed rows as their current version.
            users.recordAllInLedger();
            odds.recordAllInLedger();
            audit.log("system", LEDGER_DONE, "Recorded the signed admin accounts and odds tables in the version ledger");
            log.info("Integrity version ledger initialised");
        }
        if (!done(SETTINGS_DONE)) {
            settings.recordInitial();
            audit.log("system", SETTINGS_DONE, "Recorded the current bet limits and game switch in the version ledger");
        }
        settings.keepSnapshot();
        if (!done(V2_DONE)) {
            List<String> upgraded = users.upgradeSignatures();
            audit.log("system", V2_DONE, "2FA secrets encrypted and accounts re-signed: " + upgraded
                    + " (accounts not listed failed their check and stay blocked)");
        }
        moveToCurrentKeys();
        checkLegacyRetired();
    }

    /**
     * N8: once key lists are set, what still verifies under the original key and can be signed again (admin accounts,
     * odds tables, the version ledger) is moved to the current keys, so the original key can then be retired. History
     * (rounds, wallet transactions, odds versions) is never signed again: it verifies by its date (LEGACY_KEY_UNTIL).
     */
    private void moveToCurrentKeys() {
        List<String> accounts = users.moveToCurrentKeys();
        int tables = odds.moveToCurrentKey();
        List<String> entities = ledger.moveToCurrentKey();
        if (!accounts.isEmpty() || tables > 0 || !entities.isEmpty()) {
            audit.log("system", "KEYS_MOVED", "Moved from the original key to the current keys: accounts " + accounts + ", " + tables
                    + " odds table(s), " + entities.size() + " ledger record(s)");
            log.info("Moved from the original key to the current keys: accounts {}, {} odds table(s), {} ledger record(s)",
                    accounts, tables, entities.size());
        }
    }

    /**
     * Review R6: money still to move that only the original key authorises: credits and refunds still owed, and
     * settlements still waiting on the wallet. Once LEGACY_KEY_UNTIL passes none of it would be paid automatically (its
     * signature no longer counts), so it must be settled before a cutoff is set (Settings shows the counts). Read on the
     * status indexes: only open money, never the history.
     */
    public java.util.Map<String, Long> moneyOnOriginalKey() {
        java.util.Map<String, Long> money = new java.util.LinkedHashMap<>();
        money.put("credits and refunds still owed", jdbc.sql("SELECT COUNT(*) FROM wallet_transaction t JOIN game_round r ON r.id = t.round_id "
                + "WHERE t.type IN ('CREDIT', 'ROLLBACK') AND t.status IN ('PENDING', 'FAILED') AND r.status IN ('CREDIT_PENDING', 'ROLLBACK_PENDING') "
                + "AND SUBSTRING(t.signature, 3, 1) <> '.'").query(Long.class).single());
        money.put("settlements waiting on the wallet", jdbc.sql("SELECT COUNT(*) FROM round_settlement WHERE status = 'PENDING' "
                + "AND SUBSTRING(signature, 3, 1) <> '.'").query(Long.class).single());
        return money;
    }

    /**
     * N8: with the original key retired (LEGACY_KEY_UNTIL), nothing still in use may depend on it; if something does,
     * the instance refuses to start (the previous version keeps serving) and says what to settle first. Review R6: money
     * still to move on the original key refuses the start as soon as a cutoff is set, even one still ahead: when the
     * time passes at runtime that money would be blocked, never paid.
     */
    public void checkLegacyRetired() {
        if (keys.legacyUntil().isEmpty()) {
            return;
        }
        List<String> money = new java.util.ArrayList<>();
        moneyOnOriginalKey().forEach((what, n) -> {
            if (n > 0) {
                money.add(n + " " + what + ", signed with the original key");
            }
        });
        if (!money.isEmpty()) {
            throw new IllegalStateException("LEGACY_KEY_UNTIL is set but money still to move depends on ROUND_SIGNING_KEY: " + String.join("; ", money)
                    + ". After the cutoff it would never be paid automatically: unset LEGACY_KEY_UNTIL, pay or settle it first (Settings shows "
                    + "the counts), then set it again.");
        }
        List<String> left = new java.util.ArrayList<>();
        count(left, "admin account(s) not moved (they fail their check: fix or remove them)",
                "SELECT COUNT(*) FROM admin_user WHERE integrity IS NOT NULL AND SUBSTRING(integrity, 3, 1) <> '.'");
        count(left, "admin 2FA secret(s) still encrypted with the original key",
                "SELECT COUNT(*) FROM admin_user WHERE totp_secret LIKE 'v1:%'");
        count(left, "odds table(s) not moved (they fail their check)",
                "SELECT COUNT(*) FROM odds_signature WHERE SUBSTRING(signature, 3, 1) <> '.'");
        count(left, "pending change request(s) signed with the original key (approve or reject them first)",
                "SELECT COUNT(*) FROM change_request WHERE status = 'PENDING' AND signature IS NOT NULL AND SUBSTRING(signature, 3, 1) <> '.'");
        // N8 #2: with the original key removed, every frozen record must have been signed again first; R7: the ones kept as
        // evidence (failing their check, or made after the suspected leak) are not waited for
        if (keys.legacyRemoved() && signatures != null) {
            resigned.forEach(table -> {
                long n = signatures.left(table);
                if (n > 0) {
                    left.add(n + " " + table + " record(s) still signed only with the removed ROUND_SIGNING_KEY (put it back until the "
                            + "re-signing job has signed them again)");
                }
            });
        }
        List<String> entities = ledger.onLegacyKey();
        if (!entities.isEmpty()) {
            left.add(entities.size() + " ledger record(s) still on the original key, e.g. " + entities.subList(0, Math.min(5, entities.size())));
        }
        if (!left.isEmpty() && !keys.legacyRetired() && !keys.legacyRemoved()) {
            // the cutoff is still ahead: say what must be settled by then, but run
            log.warn("LEGACY_KEY_UNTIL {} is ahead; by then settle what still depends on ROUND_SIGNING_KEY: {}", keys.legacyUntil().get(),
                    String.join("; ", left));
            return;
        }
        if (!left.isEmpty()) {
            throw new IllegalStateException("LEGACY_KEY_UNTIL is set but these still depend on ROUND_SIGNING_KEY: " + String.join("; ", left)
                    + ". Unset LEGACY_KEY_UNTIL (or set it later), settle them, then set it again.");
        }
        log.info("The original key is retired since {}: it verifies only history made before then", keys.legacyUntil().get());
    }

    /** The frozen tables whose old records are signed again (N8 #2). */
    private final List<String> resigned = List.of("game_round", "wallet_transaction", "odds_history");

    private com.plinko.repo.ResignedSignatures signatures;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSignatures(com.plinko.repo.ResignedSignatures signatures) {
        this.signatures = signatures;
    }

    private void count(List<String> left, String what, String sql) {
        long n = jdbc.sql(sql).query(Long.class).single();
        if (n > 0) {
            left.add(n + " " + what);
        }
    }

    private boolean done(String marker) {
        return audit.happened(marker);
    }
}
