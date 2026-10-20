package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.security.Principal;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.config.PlinkoProperties;
import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Bet limits (changed through approval), the game on/off switch and a read-only view of the PAM
 * connection, which is configured in the environment only.
 */
@Controller
public class SettingsAdminController {

    private final SettingsService settings;
    private final ChangeRequestService changes;
    private final PamClient pam;
    private final AuditService audit;
    private final PlinkoProperties properties;
    private final com.plinko.bo.game.RoundSettlementService settlements;

    public SettingsAdminController(SettingsService settings, ChangeRequestService changes, PamClient pam, AuditService audit,
                                   PlinkoProperties properties, com.plinko.bo.game.RoundSettlementService settlements) {
        this.settlements = settlements;
        this.settings = settings;
        this.changes = changes;
        this.pam = pam;
        this.audit = audit;
        this.properties = properties;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        model.addAttribute("limits", settings.limits());
        model.addAttribute("gameEnabled", settings.gameEnabled());
        model.addAttribute("features", settings.features());
        model.addAttribute("hardLimits", properties.limits());
        model.addAttribute("pendingLimits", changes.pendingFor(ChangeRequestService.LIMITS, "limits").orElse(null));
        model.addAttribute("pamUrl", properties.pam().baseUrl());
        model.addAttribute("pamKey", mask(properties.pam().apiKey()));
        model.addAttribute("pamTimeout", properties.pam().timeoutMs());
        model.addAttribute("requireSecondApprover", properties.requireSecondApprover());
        // N8 #2: which keys sign now, and how much is still signed only with the original key
        if (keys != null) {
            model.addAttribute("keyIds", keys.currentIds());
            model.addAttribute("originalKey", keys.legacyRemoved() ? "removed" : keys.legacyRetired() ? "retired" : "in use");
        }
        if (resigner != null) {
            // R8: as the re-signing job last counted it, with its time; the page never counts the tables itself
            model.addAttribute("legacyLeft", resigner.left().orElse(null));
            model.addAttribute("legacyCounted", resigner.left().isPresent());
            model.addAttribute("legacyEvidence", resigner.evidence());
        }
        if (bootstrap != null) {
            // R6: money still to move on the original key, which a cutoff would leave unpaid (read on the status indexes)
            java.util.Map<String, Long> money = bootstrap.moneyOnOriginalKey();
            model.addAttribute("moneyOnOriginalKey", money);
            model.addAttribute("moneyOnOriginalKeyOpen", money.values().stream().anyMatch(n -> n > 0));
        }
        model.addAttribute("currency", settings.currency());
        model.addAttribute("currencyLabel", com.plinko.common.Currencies.label(settings.currency()));
        model.addAttribute("currencies", com.plinko.common.Currencies.OFFERED.stream()
                .collect(java.util.stream.Collectors.toMap(c -> c, com.plinko.common.Currencies::label, (a, b) -> a, java.util.LinkedHashMap::new)));
        model.addAttribute("pendingCurrency", changes.pendingFor(ChangeRequestService.CURRENCY, "currency").orElse(null));
        boolean trusted = settings.trusted();
        model.addAttribute("settingsTrusted", trusted);
        model.addAttribute("settingsDifferences", trusted ? java.util.List.of() : settings.differences());
        model.addAttribute("pendingRestore", changes.pendingFor(ChangeRequestService.SETTINGS_RESTORE, "settings").orElse(null));
        SettingsService.SettlementLimits settlementLimits = null;
        try {
            settlementLimits = settlements.currentLimits();
        } catch (SettingsService.TamperedException e) {
            // shown as unavailable until the settings are restored
        }
        model.addAttribute("settlementLimits", settlementLimits);
        model.addAttribute("pendingSettlementLimits", changes.pendingFor(ChangeRequestService.SETTLEMENT_LIMITS, "settlement-limits").orElse(null));
        return "admin/settings";
    }

    /** N3 #3: new settlement limits per currency; they apply after another admin approves (never the proposer). */
    @PostMapping("/admin/settings/settlement-limits")
    @PreAuthorize("hasRole('ADMIN')")
    public String proposeSettlementLimits(@RequestParam String maxAmounts, @RequestParam String otherAmounts, Principal principal,
                                          RedirectAttributes redirect) {
        try {
            long id = changes.proposeSettlementLimits(maxAmounts.trim().toUpperCase(), otherAmounts.trim().toUpperCase(), principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. The settlement limits change after another admin approves it.");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/restore")
    @PreAuthorize("hasRole('OPERATOR')")
    public String proposeRestore(Principal principal, RedirectAttributes redirect) {
        try {
            long id = changes.proposeSettingsRestore(principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. The settings are put back after another admin approves it.");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/currency")
    @PreAuthorize("hasRole('OPERATOR')")
    public String proposeCurrency(@RequestParam String currency, Principal principal, RedirectAttributes redirect) {
        try {
            long id = changes.proposeCurrency(currency.trim().toUpperCase(), principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. The currency changes after another admin approves it.");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    private com.plinko.common.KeyRing keys;
    private com.plinko.bo.security.LegacyResigner resigner;
    private com.plinko.bo.security.IntegrityBootstrap bootstrap;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBootstrap(com.plinko.bo.security.IntegrityBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKeys(com.plinko.common.KeyRing keys, com.plinko.bo.security.LegacyResigner resigner) {
        this.keys = keys;
        this.resigner = resigner;
    }

    @PostMapping("/admin/settings/limits")
    @PreAuthorize("hasRole('OPERATOR')")
    public String proposeLimits(@RequestParam String minBet, @RequestParam String maxBet, @RequestParam String defaultBet,
                                @RequestParam String maxBalls, Principal principal, RedirectAttributes redirect) {
        try {
            SettingsService.GameLimits limits = new SettingsService.GameLimits(new BigDecimal(minBet.trim()), new BigDecimal(maxBet.trim()),
                    new BigDecimal(defaultBet.trim()), Integer.parseInt(maxBalls.trim()));
            long id = changes.proposeLimits(limits, principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. It takes effect after another admin approves it.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "All limits must be numbers");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    /** Operators may stop the game at once (the safe direction); only admins may start it again. */
    @PostMapping("/admin/settings/game-enabled")
    @PreAuthorize("hasRole('OPERATOR')")
    public String gameEnabled(@RequestParam boolean enabled, @RequestParam String reason, Principal principal, Authentication authentication,
                              RedirectAttributes redirect) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (enabled && !admin) {
            redirect.addFlashAttribute("error", "Only admins can start the game again");
            return "redirect:/admin/settings";
        }
        if (reason == null || reason.isBlank()) {
            redirect.addFlashAttribute("error", "A reason is required");
            return "redirect:/admin/settings";
        }
        if (!settings.trusted()) {
            redirect.addFlashAttribute("error", "The settings were changed outside the admin panel; restore them first (see the alarm).");
            return "redirect:/admin/settings";
        }
        try {
            settings.setGameEnabled(enabled);
        } catch (SettingsService.TamperedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/settings";
        }
        audit.log(principal.getName(), enabled ? "GAME_ENABLED" : "GAME_DISABLED", reason.trim());
        if (!enabled) {
            // review 7.10: an alert goes out (deploy.sh alerts), so nobody is surprised by a stopped game
            org.slf4j.LoggerFactory.getLogger(SettingsAdminController.class).warn("ALERT GAME_STOPPED by {}: {}", principal.getName(), reason.trim());
        }
        redirect.addFlashAttribute("message", enabled ? "Game started" : "Game stopped: new sessions and rounds are refused");
        return "redirect:/admin/settings";
    }

    /**
     * Player features: Hold & Drop and the responsible gambling options (review 7.4). Protecting players more is the safe
     * direction (operators may); protecting them less in any way is for admins, like starting the game.
     */
    @PostMapping("/admin/settings/features")
    @PreAuthorize("hasRole('OPERATOR')")
    public String features(@RequestParam(defaultValue = "false") boolean holdToDrop, @RequestParam(defaultValue = "false") boolean sessionClock,
                           @RequestParam(defaultValue = "0") String realityCheckMinutes, @RequestParam(defaultValue = "false") boolean quietSmallWins,
                           @RequestParam(defaultValue = "0") String minRoundSeconds,
                           @RequestParam(defaultValue = "false") boolean requireRealityCheckProof, Principal principal, Authentication authentication,
                           RedirectAttributes redirect) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        SettingsService.GameFeatures before = settings.features();
        SettingsService.GameFeatures next;
        try {
            next = new SettingsService.GameFeatures(holdToDrop, sessionClock, Integer.parseInt(realityCheckMinutes.trim()), quietSmallWins,
                    new java.math.BigDecimal(minRoundSeconds.trim()).stripTrailingZeros(), requireRealityCheckProof);
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Reality check and minimum time per round must be numbers");
            return "redirect:/admin/settings";
        }
        if (next.minRoundSeconds().scale() < 0) {
            next = new SettingsService.GameFeatures(next.holdToDrop(), next.sessionClock(), next.realityCheckMinutes(), next.quietSmallWins(),
                    next.minRoundSeconds().setScale(0), next.requireRealityCheckProof());
        }
        if (!next.problems().isEmpty()) {
            redirect.addFlashAttribute("error", String.join("; ", next.problems()));
            return "redirect:/admin/settings";
        }
        if (before.loosenedBy(next) && !admin) {
            redirect.addFlashAttribute("error", "Only admins can switch Hold & Drop on or protect players less; you can make the options stricter");
            return "redirect:/admin/settings";
        }
        if (before.loosenedBy(next)) {
            // N14.9: protecting players less needs a second admin; stricter options below take effect at once
            try {
                long id = changes.proposeFeatures(next, principal.getName());
                redirect.addFlashAttribute("message", "Change #" + id + " proposed: it protects players less, so it takes effect after another admin approves it.");
            } catch (ChangeRequestService.ChangeException e) {
                redirect.addFlashAttribute("error", e.getMessage());
            }
            return "redirect:/admin/settings";
        }
        if (!settings.trusted()) {
            redirect.addFlashAttribute("error", "The settings were changed outside the admin panel; restore them first (see the alarm).");
            return "redirect:/admin/settings";
        }
        try {
            settings.setFeatures(next);
        } catch (SettingsService.TamperedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/settings";
        }
        audit.log(principal.getName(), "GAME_FEATURES", "Hold & Drop " + (holdToDrop ? "on" : "off") + ", session clock " + (sessionClock ? "on" : "off")
                + ", reality check " + (next.realityCheckMinutes() == 0 ? "off" : "every " + next.realityCheckMinutes() + " min")
                + ", small wins " + (quietSmallWins ? "not celebrated" : "celebrated") + ", minimum round time " + next.minRoundSeconds().toPlainString() + " s"
                + ", reality check proof " + (requireRealityCheckProof ? "required" : "not required"));
        redirect.addFlashAttribute("message", "Game features saved");
        return "redirect:/admin/settings";
    }

    /** Sends a signed authenticate call with a dummy token; a signed INVALID_TOKEN reply proves the connection works. */
    @PostMapping("/admin/settings/test-pam")
    @PreAuthorize("hasRole('ADMIN')")
    public String testPam(Principal principal, RedirectAttributes redirect) {
        try {
            pam.authenticate("connection-test");
            redirect.addFlashAttribute("message", "PAM reachable (it accepted the test token)");
        } catch (PamException e) {
            if (e.outcomeUnknown()) {
                redirect.addFlashAttribute("error", "PAM connection failed: " + e.code() + " " + e.getMessage());
            } else {
                redirect.addFlashAttribute("message", "PAM reachable and signatures valid (answered " + e.code() + ")");
            }
        }
        audit.log(principal.getName(), "PAM_TEST", null);
        return "redirect:/admin/settings";
    }

    private static String mask(String value) {
        if (value == null || value.length() < 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 2);
    }
}
