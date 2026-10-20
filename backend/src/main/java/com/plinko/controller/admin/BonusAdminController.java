package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Paying bonuses. Saving creates a change request (settings plus rebuilt odds); nothing changes until another admin approves it. */
@Controller
public class BonusAdminController {

    /** Base and total RTP of one board size, per risk. */
    public record RtpRow(int rows, List<BigDecimal> base, List<BigDecimal> total) {
    }

    private final BonusService bonus;
    private final OddsService odds;
    private final ChangeRequestService changes;

    public BonusAdminController(BonusService bonus, OddsService odds, ChangeRequestService changes) {
        this.bonus = bonus;
        this.odds = odds;
        this.changes = changes;
    }

    @GetMapping("/admin/bonus")
    public String page(Model model) {
        BonusSettings current = bonus.current();
        // the form shows the current values, or the suggested ones while the feature is off
        BonusSettings.Golden form = current.goldenOn() ? current.golden() : BonusSettings.OFF.golden();
        BonusSettings.Hot hotForm = current.hotOn() ? current.hot() : BonusSettings.OFF.hot();
        BonusSettings.Meter meterForm = current.meterOn() ? current.meter() : BonusSettings.OFF.meter();
        List<RtpRow> rows = new ArrayList<>();
        for (int r = PlinkoBoard.MIN_ROWS; r <= PlinkoBoard.MAX_ROWS; r++) {
            List<BigDecimal> base = new ArrayList<>();
            List<BigDecimal> total = new ArrayList<>();
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable table = odds.get(r, risk);
                base.add(table == null ? null : table.rtp().setScale(2, RoundingMode.HALF_UP));
                total.add(table == null ? null : BonusService.totalRtp(table, current).setScale(2, RoundingMode.HALF_UP));
            }
            rows.add(new RtpRow(r, base, total));
        }
        model.addAttribute("bonus", current);
        model.addAttribute("form", form);
        model.addAttribute("hotForm", hotForm);
        model.addAttribute("meterForm", meterForm);
        model.addAttribute("share", BonusService.shareText(current, odds.all()));
        model.addAttribute("meterShare", BonusService.shareText(new BonusSettings(BonusSettings.OFF.golden(), BonusSettings.OFF.hot(),
                new BonusSettings.Meter(true, meterForm.charges(), meterForm.minMultiplier(), meterForm.potShare(), meterForm.freeBalls())), odds.all()));
        model.addAttribute("goldenShare", BonusService.shareText(new BonusSettings(new BonusSettings.Golden(true, form.chance(), form.boosts()), BonusSettings.OFF.hot())));
        model.addAttribute("hotShare", BonusService.shareText(new BonusSettings(BonusSettings.OFF.golden(), new BonusSettings.Hot(true, hotForm.chance(), hotForm.boost()))));
        // Plinko Mega: the gates, the wheel and the gold wheel (shown with the values in force, or the suggested ones)
        BonusSettings.Gates gatesForm = current.gates() != null ? current.gates() : BonusSettings.MEGA.gates();
        BonusSettings.Wheel wheelForm = current.wheel() != null ? current.wheel() : BonusSettings.MEGA.wheel();
        BonusSettings.Wheel goldForm = current.megaWheel() != null ? current.megaWheel() : BonusSettings.MEGA.megaWheel();
        model.addAttribute("gatesForm", gatesForm);
        model.addAttribute("wheelForm", wheelForm.segments());
        model.addAttribute("goldForm", goldForm.segments());
        model.addAttribute("jackpots", List.of("MINI", "MAJOR", "MEGA"));
        model.addAttribute("wheelPays", BonusService.expected(wheelForm).setScale(2, RoundingMode.HALF_UP).toPlainString());
        model.addAttribute("goldPays", BonusService.expected(goldForm).setScale(2, RoundingMode.HALF_UP).toPlainString());
        model.addAttribute("maxShare", BonusService.MAX_FACTOR.subtract(BigDecimal.ONE).movePointRight(2).stripTrailingZeros().toPlainString());
        model.addAttribute("rtpRows", rows);
        model.addAttribute("riskNames", PlinkoBoard.RISK_NAMES);
        model.addAttribute("pending", changes.pendingFor(ChangeRequestService.BONUS, "bonus").orElse(null));
        return "admin/bonus";
    }

    @PostMapping("/admin/bonus/propose")
    @PreAuthorize("hasRole('OPERATOR')")
    public String propose(@RequestParam(defaultValue = "false") boolean goldenEnabled, @RequestParam String goldenChance,
                          @RequestParam List<String> boostMultiplier, @RequestParam List<String> boostWeight,
                          @RequestParam(defaultValue = "false") boolean hotEnabled, @RequestParam String hotChance, @RequestParam String hotBoost,
                          @RequestParam(defaultValue = "false") boolean meterEnabled, @RequestParam String meterCharges,
                          @RequestParam String meterMin, @RequestParam String meterShare, @RequestParam String meterFree,
                          @RequestParam(required = false) Boolean gatesEnabled, @RequestParam(required = false) String gateBoost,
                          @RequestParam(required = false) Boolean wheelEnabled, @RequestParam(required = false) List<String> wheelName,
                          @RequestParam(required = false) List<String> wheelMultiplier, @RequestParam(required = false) List<String> wheelWeight,
                          @RequestParam(required = false) List<String> goldName, @RequestParam(required = false) List<String> goldMultiplier,
                          @RequestParam(required = false) List<String> goldWeight,
                          Principal principal, RedirectAttributes redirect) {
        try {
            if (boostMultiplier.size() != boostWeight.size()) {
                throw new IllegalArgumentException("Invalid form");
            }
            List<BonusSettings.Boost> boosts = new ArrayList<>();
            for (int i = 0; i < boostMultiplier.size(); i++) {
                String m = boostMultiplier.get(i).trim();
                String w = boostWeight.get(i).trim();
                if (m.isEmpty() && w.isEmpty()) {
                    continue;   // unused line
                }
                boosts.add(new BonusSettings.Boost(new BigDecimal(m), new BigDecimal(w)));
            }
            // a form without Plinko Mega's fields (gateBoost absent) keeps the gates and wheels in force
            BonusSettings current = bonus.current();
            BonusSettings.Gates gates = gateBoost == null ? current.gates()
                    : new BonusSettings.Gates(Boolean.TRUE.equals(gatesEnabled), new BigDecimal(gateBoost.trim()));
            BonusSettings.Wheel wheel = wheelMultiplier == null ? current.wheel()
                    : new BonusSettings.Wheel(Boolean.TRUE.equals(wheelEnabled), segments(wheelName, wheelMultiplier, wheelWeight));
            BonusSettings.Wheel gold = goldMultiplier == null ? current.megaWheel()
                    : new BonusSettings.Wheel(false, segments(goldName, goldMultiplier, goldWeight));
            BonusSettings next = new BonusSettings(new BonusSettings.Golden(goldenEnabled, new BigDecimal(goldenChance.trim()), boosts),
                    new BonusSettings.Hot(hotEnabled, new BigDecimal(hotChance.trim()), new BigDecimal(hotBoost.trim())),
                    new BonusSettings.Meter(meterEnabled, Integer.parseInt(meterCharges.trim()), new BigDecimal(meterMin.trim()),
                            new BigDecimal(meterShare.trim()), Integer.parseInt(meterFree.trim())), gates, wheel, gold);
            long id = changes.proposeBonus(next, principal.getName());
            redirect.addFlashAttribute("message", "Change #" + id + " proposed. It takes effect after another admin approves it.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Chance, boosts and weights must be numbers");
        } catch (IllegalArgumentException | ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/bonus";
    }

    /** A wheel's segments from the form's lines; empty lines are skipped, a name is MINI, MAJOR or MEGA or none. */
    private static List<BonusSettings.Segment> segments(List<String> names, List<String> multipliers, List<String> weights) {
        if (weights == null || multipliers.size() != weights.size() || (names != null && names.size() != multipliers.size())) {
            throw new IllegalArgumentException("Invalid form");
        }
        List<BonusSettings.Segment> segments = new ArrayList<>();
        for (int i = 0; i < multipliers.size(); i++) {
            String m = multipliers.get(i).trim();
            String w = weights.get(i).trim();
            if (m.isEmpty() && w.isEmpty()) {
                continue;   // unused line
            }
            String name = names == null ? "" : names.get(i).trim();
            segments.add(new BonusSettings.Segment(name.isEmpty() ? null : name, new BigDecimal(m), new BigDecimal(w)));
        }
        return segments;
    }
}
