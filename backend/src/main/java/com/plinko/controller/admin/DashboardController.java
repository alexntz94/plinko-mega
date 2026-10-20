package com.plinko.controller.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.model.RoundStatus;
import com.plinko.repo.AdminListRepository;
import com.plinko.repo.StatsRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final StatsRepository stats;
    private final AdminListRepository lists;
    private final SettingsService settings;
    private final AuditService audit;
    private final com.plinko.bo.game.RoundIntegrityScanner scanner;
    private final com.plinko.bo.game.RoundSettlementService settlements;

    public DashboardController(StatsRepository stats, AdminListRepository lists, SettingsService settings, AuditService audit,
                               com.plinko.bo.game.RoundIntegrityScanner scanner, com.plinko.bo.game.RoundSettlementService settlements) {
        this.settlements = settlements;
        this.scanner = scanner;
        this.stats = stats;
        this.lists = lists;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * What the dashboard shows, computed at most every 20 seconds and shared by every admin who has it open (review 3.2).
     * invalidSignatures: rounds the integrity scan found failing (blocked ones are counted apart); checkedUntil: rounds
     * played before it were checked by the scan's last pass (review 3.10d: the recent list is no longer verified row by row).
     */
    private record View(List<StatsRepository.CurrencyTotals> totals, List<AdminListRepository.RoundSummary> recent, long invalidSignatures,
                        Instant checkedUntil, AuditService.ChainStatus auditChain, long tamperedAt, Instant at) {
    }

    private static final Duration FRESH = Duration.ofSeconds(20);
    private volatile View view;

    @GetMapping("/admin")
    public String dashboard(Model model) {
        // the alarms are always read live (two counts on the status index); the rest is shared for 20 seconds, or until
        // a round is blocked
        long pendingWallet = stats.countByStatus(List.of(RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING));
        long tampered = stats.countByStatus(List.of(RoundStatus.TAMPERED));
        View now = view;
        if (now == null || now.tamperedAt() != tampered || now.at().plus(FRESH).isBefore(Instant.now())) {
            now = compute(tampered);
            view = now;
        }
        model.addAttribute("totals", now.totals());
        model.addAttribute("recentRounds", now.recent());
        model.addAttribute("pendingWallet", pendingWallet);
        model.addAttribute("tampered", tampered + now.invalidSignatures());
        // review 7.3: blocked rounds still to be settled with the player, the longest waiting first (live, a small list)
        model.addAttribute("openBlocked", tampered == 0 ? List.of() : settlements.open(20));
        model.addAttribute("checkedUntil", now.checkedUntil());
        model.addAttribute("auditChain", now.auditChain());
        model.addAttribute("gameEnabled", settings.gameEnabled());
        return "admin/dashboard";
    }

    /** Review 3.2.5: the last 24 hours (distinct players over a day of rounds) are worked out every 5 minutes, not every 20 seconds. */
    private static final Duration DAY_FRESH = Duration.ofMinutes(5);
    private final java.util.Map<String, StatsRepository.Totals> lastDay = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Instant lastDayAt = Instant.EPOCH;

    private StatsRepository.Totals lastDay(String currency) {
        if (lastDayAt.plus(DAY_FRESH).isBefore(Instant.now())) {
            lastDay.clear();
            lastDayAt = Instant.now();
        }
        return lastDay.computeIfAbsent(currency, c -> stats.totals("Last 24 hours", c, Duration.ofDays(1)));
    }

    /**
     * The last 24 hours come from the rounds themselves (a range on their created_at index), at most every 5 minutes;
     * longer periods add up whole days from the daily totals, today included.
     */
    private View compute(long tampered) {
        java.time.LocalDate today = stats.today();
        List<StatsRepository.CurrencyTotals> totals = new ArrayList<>();
        for (String currency : stats.currencies()) {
            totals.add(new StatsRepository.CurrencyTotals(currency, List.of(
                    lastDay(currency),
                    stats.totalsSince("Last 7 days", currency, today.minusDays(6)),
                    stats.totalsSince("Last 30 days", currency, today.minusDays(29)),
                    stats.totalsSince("All time", currency, null))));
        }
        // the scan's findings (rounds since blocked are counted with the blocked ones, not twice)
        long invalid = scanner.invalidRounds().size();
        return new View(totals, lists.latestRounds(null, 15), invalid, scanner.checkedUntil().orElse(null), audit.chainStatus(), tampered,
                Instant.now());
    }
}
