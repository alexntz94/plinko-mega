package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.plinko.repo.FinanceRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** How much the platform makes: GGR by period, day, risk, rows and player, plus a reconciliation check. */
@Controller
public class FinanceController {

    /** One bar of the daily GGR chart, already laid out (the page has no scripts). */
    public record Bar(String day, BigDecimal ggr, double x, double y, double width, double height, boolean positive) {
    }

    /** Daily GGR chart for one currency: bars plus the y of the zero line and the largest absolute value. */
    public record Chart(List<Bar> bars, double zeroY, BigDecimal max) {
    }

    private static final int MAX_DAYS = 366;

    private final FinanceRepository finance;
    private final com.plinko.bo.game.WalletReconciliation reconciliation;
    private final com.plinko.bo.game.PamReconciliation walletCheck;
    private final com.plinko.bo.audit.AuditService audit;

    public FinanceController(FinanceRepository finance, com.plinko.bo.game.WalletReconciliation reconciliation,
                             com.plinko.bo.game.PamReconciliation walletCheck, com.plinko.bo.audit.AuditService audit) {
        this.finance = finance;
        this.reconciliation = reconciliation;
        this.walletCheck = walletCheck;
        this.audit = audit;
    }

    @GetMapping("/admin/finance")
    public String finance(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                          @RequestParam(required = false) String refresh, Model model) {
        LocalDate[] range = range(from, to, model);
        LocalDate start = range[0], end = range[1];
        if (refresh != null) {
            views.remove(start + "|" + end);
        }
        View view = view(start, end);
        model.addAttribute("asOf", view.at());
        List<FinanceRepository.Line> totals = view.totals();
        List<FinanceRepository.Line> daily = view.daily();

        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("totals", totals);
        model.addAttribute("daily", daily);
        model.addAttribute("byRisk", view.byRisk());
        model.addAttribute("byRows", view.byRows());
        model.addAttribute("winners", view.winners());
        model.addAttribute("losers", view.losers());
        model.addAttribute("open", view.open());
        // checked once per round in the background (review 3.3), not on every refresh
        model.addAttribute("mismatches", reconciliation.differing(start, end));
        model.addAttribute("reconciledFrom", reconciliation.coveredFrom());
        model.addAttribute("reconciledAll", reconciliation.caughtUp());
        model.addAttribute("currencies", totals.stream().map(FinanceRepository.Line::currency).toList());
        model.addAttribute("charts", totals.stream().collect(Collectors.toMap(FinanceRepository.Line::currency,
                t -> bars(daily.stream().filter(d -> d.currency().equals(t.currency())).toList(), start, end))));
        return "admin/finance";
    }

    /** Everything the Finance page adds up for a range; kept a minute, so refreshes and several admins don't each re-read today's rounds. */
    private record View(java.time.Instant at, List<FinanceRepository.Line> totals, List<FinanceRepository.Line> daily,
                        List<FinanceRepository.Line> byRisk, List<FinanceRepository.Line> byRows, List<FinanceRepository.Line> winners,
                        List<FinanceRepository.Line> losers, List<FinanceRepository.Line> open) {
    }

    private static final java.time.Duration VIEW_FOR = java.time.Duration.ofSeconds(60);
    private final java.util.Map<String, View> views = new java.util.concurrent.ConcurrentHashMap<>();

    private View view(LocalDate start, LocalDate end) {
        java.time.Instant now = java.time.Instant.now();
        views.values().removeIf(v -> v.at().isBefore(now.minus(VIEW_FOR)));
        return views.computeIfAbsent(start + "|" + end, key -> new View(now, finance.totals(start, end), finance.daily(start, end),
                finance.byRisk(start, end), finance.byRows(start, end), finance.players(start, end, true, 10),
                finance.players(start, end, false, 10), finance.open(start, end)));
    }

    private static final java.util.Map<String, String> CHECK_LABELS = java.util.Map.of("walletOnly", "Only at the wallet", "gameOnly", "Only in the game",
            "different", "Amount or type differs", "notConfirmed", "Pending in the game");

    /** Review 7.1: the casino wallet's record of this game's money against the game's own (after a restore, or a dispute). */
    @GetMapping("/admin/finance/wallet-check")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public String walletCheck(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                              @RequestParam(required = false) String run, java.security.Principal principal, Model model) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withSecond(0).withNano(0);
        java.time.LocalDateTime start, end;
        try {
            start = from == null || from.isBlank() ? now.minusDays(1) : java.time.LocalDateTime.parse(from);
            end = to == null || to.isBlank() ? now : java.time.LocalDateTime.parse(to);
        } catch (java.time.format.DateTimeParseException e) {
            start = now.minusDays(1);
            end = now;
            model.addAttribute("error", "Enter both times as date and time");
            run = null;
        }
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("labels", CHECK_LABELS);
        if (run != null) {
            try {
                com.plinko.bo.game.PamReconciliation.Report report = walletCheck.compare(start.toInstant(java.time.ZoneOffset.UTC),
                        end.toInstant(java.time.ZoneOffset.UTC));
                model.addAttribute("report", report);
                audit.log(principal.getName(), "WALLET_CHECK", start + " to " + end + " UTC: " + (report.clean() ? "both sides agree" : report.counts()));
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
            } catch (com.plinko.pam.PamException e) {
                model.addAttribute("error", "NOT_SUPPORTED".equals(e.code())
                        ? "The casino's wallet doesn't offer the transaction list (PAM contract, optional /transactions); ask the casino for an export instead"
                        : "The wallet did not answer (" + e.code() + "); try again");
            }
        }
        return "admin/wallet-check";
    }

    /** Daily figures as CSV, to compare with the PAM's own statement. */
    @GetMapping("/admin/finance.csv")
    public void csv(@RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpServletResponse response)
            throws java.io.IOException {
        LocalDate[] range = range(from, to, null);
        response.setContentType("text/csv; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"plinko-finance-" + range[0] + "-to-" + range[1] + ".csv\"");
        var out = response.getWriter();
        out.println("day_utc,currency,rounds,players,bets,wins,ggr,rtp_percent");
        for (FinanceRepository.Line d : finance.daily(range[0], range[1])) {
            out.println(String.join(",", d.label(), d.currency(), Long.toString(d.rounds()), Long.toString(d.players()),
                    d.bet().toPlainString(), d.win().toPlainString(), d.ggr().toPlainString(), d.rtp() == null ? "" : d.rtp().toPlainString()));
        }
    }

    private LocalDate[] range(String from, String to, Model model) {
        LocalDate today = finance.today();
        if (model != null) {
            model.addAttribute("zone", finance.zoneName());
        }
        LocalDate end = today, start = today.minusDays(29);
        try {
            if (to != null && !to.isBlank()) {
                end = LocalDate.parse(to);
            }
            if (from != null && !from.isBlank()) {
                start = LocalDate.parse(from);
            }
        } catch (DateTimeParseException e) {
            if (model != null) {
                model.addAttribute("error", "Dates must look like 2026-10-31");
            }
        }
        if (start.isAfter(end)) {
            start = end;
        }
        if (start.isBefore(end.minusDays(MAX_DAYS))) {
            start = end.minusDays(MAX_DAYS);
        }
        return new LocalDate[]{start, end};
    }

    /** Lays out one slot per calendar day from start to end (empty days stay blank) around a zero line, in a 1200 x 240 chart. */
    private static Chart bars(List<FinanceRepository.Line> days, LocalDate start, LocalDate end) {
        List<Bar> bars = new ArrayList<>();
        if (days.isEmpty()) {
            return new Chart(bars, 200, BigDecimal.ZERO);
        }
        double max = days.stream().mapToDouble(d -> Math.abs(d.ggr().doubleValue())).max().orElse(1);
        boolean anyNegative = days.stream().anyMatch(d -> d.ggr().signum() < 0);
        double top = 24, bottom = 210;
        double zero = anyNegative ? (top + bottom) / 2 : bottom;
        double scale = (zero - top) / (max == 0 ? 1 : max);
        long slots = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        double band = 1130.0 / slots, width = Math.max(2, band * 0.7);
        for (FinanceRepository.Line d : days) {
            long i = java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.parse(d.label()));
            double h = Math.abs(d.ggr().doubleValue()) * scale;
            boolean positive = d.ggr().signum() >= 0;
            bars.add(new Bar(d.label(), d.ggr(), 60 + i * band + (band - width) / 2, positive ? zero - h : zero, width, Math.max(h, 1), positive));
        }
        return new Chart(bars, zero, BigDecimal.valueOf(max).setScale(2, java.math.RoundingMode.HALF_UP));
    }
}
