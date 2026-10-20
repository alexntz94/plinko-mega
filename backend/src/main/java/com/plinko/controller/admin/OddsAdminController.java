package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.model.ChangeRequest;
import com.plinko.model.OddsTable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Odds editor. Saving creates a change request; nothing changes until another admin approves it. */
@Controller
public class OddsAdminController {

    /** One risk level of the editor, with the reference values the page's buttons can load. */
    public record RiskView(int risk, String name, OddsTable table, String defaultMultipliers,
                           String naturalProbabilities, ChangeRequest pending) {
    }

    private final OddsService odds;
    private final ChangeRequestService changes;
    private final PlinkoProperties.Limits limits;
    private final BonusService bonus;

    public OddsAdminController(OddsService odds, ChangeRequestService changes, PlinkoProperties properties, BonusService bonus) {
        this.odds = odds;
        this.bonus = bonus;
        this.changes = changes;
        this.limits = properties.limits();
    }

    /** One line of the "Set RTP" preview. */
    public record PreviewRow(int rows, String risk, BigDecimal currentRtp, BigDecimal newRtp, BigDecimal currentTop, BigDecimal newTop,
                             String newMultipliers) {
    }

    @GetMapping("/admin/odds")
    public String odds(@RequestParam(defaultValue = "8") int rows, @RequestParam(required = false) String targetRtp,
                       @RequestParam(defaultValue = "all") String scope, Model model) {
        int selected = Math.max(PlinkoBoard.MIN_ROWS, Math.min(PlinkoBoard.MAX_ROWS, rows));
        model.addAttribute("scope", "rows".equals(scope) ? "rows" : "all");
        model.addAttribute("targetRtp", targetRtp == null ? OddsService.DEFAULT_RTP.toPlainString() : targetRtp);
        model.addAttribute("bulkPending", changes.pendingFor(ChangeRequestService.ODDS_BULK, "bulk").orElse(null));
        // with bonuses on, every RTP on this page is the total (table plus bonuses), which the hard limits apply to
        BonusSettings bonusSettings = bonus.current();
        model.addAttribute("bonusShare", bonusSettings.anyOn() ? BonusService.shareText(bonusSettings, odds.all()) : null);
        if (targetRtp != null) {
            try {
                List<OddsTable> tables = tablesForTarget(new BigDecimal(targetRtp.trim()), scope, selected);
                model.addAttribute("preview", tables.stream().map(t -> {
                    OddsTable current = odds.get(t.rows(), t.risk());
                    return new PreviewRow(t.rows(), PlinkoBoard.RISK_NAMES[t.risk()],
                            current == null ? null : BonusService.totalRtp(current, bonusSettings).setScale(2, RoundingMode.HALF_UP),
                            BonusService.totalRtp(t, bonusSettings).setScale(2, RoundingMode.HALF_UP), current == null ? null : current.maxMultiplier().stripTrailingZeros(),
                            t.maxMultiplier().stripTrailingZeros(),
                            t.prizes().stream().map(p -> p.multiplier().stripTrailingZeros().toPlainString() + "x")
                                    .collect(java.util.stream.Collectors.joining(" · ")));
                }).toList());
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
            }
        }

        Map<Integer, List<BigDecimal>> overview = new LinkedHashMap<>();
        for (int r = PlinkoBoard.MIN_ROWS; r <= PlinkoBoard.MAX_ROWS; r++) {
            List<BigDecimal> rtps = new ArrayList<>();
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.get(r, risk);
                rtps.add(table == null ? null : BonusService.totalRtp(table, bonusSettings).setScale(2, RoundingMode.HALF_UP));
            }
            overview.put(r, rtps);
        }

        List<RiskView> risks = new ArrayList<>();
        double[] natural = PlinkoBoard.naturalProbabilities(selected);
        String naturalPercent = java.util.Arrays.stream(natural)
                .mapToObj(p -> BigDecimal.valueOf(p * 100).setScale(8, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString())
                .collect(java.util.stream.Collectors.joining(","));
        for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
            // A table blocked by its integrity check is shown as the standard 96% table, ready to be proposed as the fix.
            OddsTable stored = odds.get(selected, risk);
            risks.add(new RiskView(risk, PlinkoBoard.RISK_NAMES[risk], stored == null ? odds.defaultTable(selected, risk) : stored,
                    odds.defaultTable(selected, risk).prizes().stream().map(p -> p.multiplier().stripTrailingZeros().toPlainString()).collect(java.util.stream.Collectors.joining(",")),
                    naturalPercent, changes.pendingFor(ChangeRequestService.ODDS, selected + ":" + risk).orElse(null)));
        }

        model.addAttribute("rows", selected);
        model.addAttribute("rowOptions", java.util.stream.IntStream.rangeClosed(PlinkoBoard.MIN_ROWS, PlinkoBoard.MAX_ROWS).boxed().toList());
        model.addAttribute("overview", overview);
        model.addAttribute("riskNames", PlinkoBoard.RISK_NAMES);
        model.addAttribute("risks", risks);
        model.addAttribute("limits", limits);
        return "admin/odds";
    }

    /** Recomputes the tables on the server from the target (never trusts posted tables) and proposes them as one change. */
    @PostMapping("/admin/odds/target-rtp")
    @PreAuthorize("hasRole('OPERATOR')")
    public String proposeTarget(@RequestParam String targetRtp, @RequestParam(defaultValue = "all") String scope, @RequestParam int rows,
                                Principal principal, RedirectAttributes redirect) {
        try {
            BigDecimal target = new BigDecimal(targetRtp.trim());
            List<OddsTable> tables = tablesForTarget(target, scope, rows);
            String description = "Set RTP to " + target.stripTrailingZeros().toPlainString() + "% for "
                    + ("rows".equals(scope) ? rows + " rows (all risks)" : "all tables");
            long id = changes.proposeOddsBulk(tables, description, principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed (" + tables.size() + " tables). It takes effect after another admin approves it.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Target RTP must be a number, e.g. 96");
        } catch (IllegalArgumentException | ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/odds?rows=" + rows;
    }

    private List<OddsTable> tablesForTarget(BigDecimal target, String scope, int rows) {
        if (target.compareTo(limits.minRtp()) < 0 || target.compareTo(limits.maxRtp()) > 0) {
            throw new IllegalArgumentException("Target RTP must be between " + limits.minRtp() + "% and " + limits.maxRtp() + "%");
        }
        if (target.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Target RTP can have at most 2 decimals");
        }
        List<OddsTable> tables = new ArrayList<>();
        int from = "rows".equals(scope) ? rows : PlinkoBoard.MIN_ROWS;
        int to = "rows".equals(scope) ? rows : PlinkoBoard.MAX_ROWS;
        for (int r = from; r <= to; r++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                tables.add(odds.forTargetRtp(r, risk, target));
            }
        }
        return tables;
    }

    @PostMapping("/admin/odds/propose")
    @PreAuthorize("hasRole('OPERATOR')")
    public String propose(@RequestParam int rows, @RequestParam int risk, @RequestParam List<String> multiplier,
                          @RequestParam List<String> probability, Principal principal, RedirectAttributes redirect) {
        String back = "redirect:/admin/odds?rows=" + rows;
        if (rows < PlinkoBoard.MIN_ROWS || rows > PlinkoBoard.MAX_ROWS || risk < 0 || risk >= PlinkoBoard.RISK_LEVELS
                || multiplier.size() != probability.size()) {
            redirect.addFlashAttribute("error", "Invalid odds form");
            return back;
        }
        List<OddsTable.Prize> prizes = new ArrayList<>();
        try {
            for (int i = 0; i < multiplier.size(); i++) {
                prizes.add(new OddsTable.Prize(i, new BigDecimal(multiplier.get(i).trim()), new BigDecimal(probability.get(i).trim())));
            }
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "All multipliers and probabilities must be numbers");
            return back;
        }
        try {
            long id = changes.proposeOdds(new OddsTable(rows, risk, prizes), principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. It takes effect after another admin approves it.");
        } catch (ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return back;
    }
}
