package com.plinko.controller.admin;

import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.config.PlinkoProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every admin page layout needs. */
@ControllerAdvice(basePackages = "com.plinko.controller.admin")
public class AdminModelAdvice {

    private final PlinkoProperties properties;
    private final ChangeRequestService changes;
    private final com.plinko.bo.security.AdminUserService users;
    private final com.plinko.bo.odds.OddsService odds;
    private final com.plinko.bo.settings.SettingsService settings;
    private final com.plinko.bo.game.RoundIntegrityScanner rounds;
    private final com.plinko.bo.game.RoundRecoveryService recovery;

    public AdminModelAdvice(PlinkoProperties properties, ChangeRequestService changes, com.plinko.bo.security.AdminUserService users,
                            com.plinko.bo.odds.OddsService odds, com.plinko.bo.settings.SettingsService settings,
                            com.plinko.bo.game.RoundIntegrityScanner rounds, com.plinko.bo.game.RoundRecoveryService recovery) {
        this.recovery = recovery;
        this.properties = properties;
        this.changes = changes;
        this.users = users;
        this.odds = odds;
        this.settings = settings;
        this.rounds = rounds;
    }

    @ModelAttribute("pageSizes")
    public java.util.List<Integer> pageSizes() {
        return com.plinko.common.ListQuery.SIZES;
    }

    /** Path of the current page, for highlighting it in the sidebar. */
    @ModelAttribute("currentPath")
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("oddsHealthy")
    public boolean oddsHealthy() {
        return odds.healthy();
    }

    /** Odds tables or admin accounts that fail their integrity check; shown in red on every page. */
    @ModelAttribute("integrityAlarms")
    public java.util.List<String> integrityAlarms() {
        java.util.List<String> alarms = new java.util.ArrayList<>(odds.problems().stream().map(p -> "Odds " + p).toList());
        if (!rounds.invalidRounds().isEmpty()) {
            alarms.add(rounds.invalidRounds().size() + " round(s) have an outcome that fails its signature or win check (added or edited in the database), e.g. "
                    + rounds.invalidRounds().subList(0, Math.min(3, rounds.invalidRounds().size())));
        }
        if (!settings.trusted()) {
            alarms.add("Bet limits / game switch were changed outside the admin panel; the game is stopped until an approved "
                    + "\"Restore settings\" puts them back (Settings)");
        }
        users.all().stream().filter(u -> !users.trusted(u))
                .forEach(u -> alarms.add("Admin account " + u.username() + " was changed outside the admin panel and is blocked"));
        return alarms;
    }

    /** N14.2: rounds the wallet keeps refusing are a wallet problem, not tampering: their own banner. */
    @ModelAttribute("walletAlarms")
    public java.util.List<String> walletAlarms() {
        java.util.List<String> alarms = new java.util.ArrayList<>();
        java.util.List<String> stopped = recovery.stoppedRounds();
        if (!stopped.isEmpty()) {
            alarms.add(stopped.size() + " round(s) are still owed a win or a refund, and the wallet refused every retry: automatic "
                    + "retries have stopped. Check the wallet, then press Retry on the round (Rounds, status Credit pending / Rollback "
                    + "pending), e.g. " + stopped.subList(0, Math.min(3, stopped.size())));
        }
        // N13: an approved settlement the wallet refused, or that used up its attempts
        java.util.List<String> settlementsStopped = settlement == null ? java.util.List.of() : settlement.stoppedSettlements();
        if (!settlementsStopped.isEmpty()) {
            alarms.add(settlementsStopped.size() + " approved settlement(s) of blocked rounds were refused by the wallet or used up their "
                    + "attempts: automatic sends have stopped. Check with the casino, then use \"Send to the wallet again\" on the round, e.g. "
                    + settlementsStopped.subList(0, Math.min(3, settlementsStopped.size())));
        }
        return alarms;
    }

    private com.plinko.bo.game.RoundSettlementService settlement;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettlement(com.plinko.bo.game.RoundSettlementService settlement) {
        this.settlement = settlement;
    }

    /** N14.5: odds versions that can't be checked point at the keys (setup), not at data changed: their own banner. */
    @ModelAttribute("setupAlarms")
    public java.util.List<String> setupAlarms() {
        java.util.List<String> unreadable = rounds.unreadableRounds();
        if (unreadable.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(unreadable.size() + " round(s) were played on an odds version this server can't check (missing, or signed "
                + "with a key it doesn't hold): their payments wait. Check ROUND_KEYS / RECORD_KEYS and LEGACY_KEY_UNTIL, e.g. "
                + unreadable.subList(0, Math.min(3, unreadable.size())));
    }

    @ModelAttribute("resetRequestCount")
    public int resetRequestCount() {
        return users.pendingResetRequests().size();
    }

    @ModelAttribute("mockPamEnabled")
    public boolean mockPamEnabled() {
        return properties.mockPam().enabled();
    }

    @ModelAttribute("riskNames")
    public String[] riskNames() {
        return com.plinko.bo.odds.PlinkoBoard.RISK_NAMES;
    }

    @ModelAttribute("pendingChangeCount")
    public int pendingChangeCount() {
        return changes.pending().size();
    }
}
