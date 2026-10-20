package com.plinko.controller.admin;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.game.RoundRecoveryService;
import com.plinko.bo.game.RoundSigner;
import com.plinko.common.ListQuery;
import com.plinko.model.RoundStatus;
import com.plinko.repo.AdminListRepository;
import com.plinko.repo.GameRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Rounds and wallet transactions are read-only; the only action is retrying a failed credit or rollback. */
@Controller
public class RoundAdminController {

    public static final List<String> ROUND_STATUSES = List.of(RoundStatus.COMPLETED, RoundStatus.CREDIT_PENDING, RoundStatus.ROLLBACK_PENDING,
            RoundStatus.DEBITED, RoundStatus.PENDING, RoundStatus.CANCELLED, RoundStatus.REJECTED, RoundStatus.TAMPERED);

    private final GameRepository game;
    private final AdminListRepository lists;
    private final RoundSigner signer;
    private final RoundRecoveryService recovery;
    private final AuditService audit;

    private final com.plinko.bo.game.RoundSettlementService settlements;
    private final com.plinko.bo.changes.ChangeRequestService changes;

    /** The lists' signature column: the scan's verdict, not a check of every listed row (review 3.10d). */
    private final com.plinko.bo.game.RoundIntegrityScanner scanner;

    public RoundAdminController(GameRepository game, AdminListRepository lists, RoundSigner signer, RoundRecoveryService recovery,
                                AuditService audit, com.plinko.bo.game.RoundSettlementService settlements,
                                com.plinko.bo.changes.ChangeRequestService changes, com.plinko.bo.game.RoundIntegrityScanner scanner) {
        this.scanner = scanner;
        this.settlements = settlements;
        this.changes = changes;
        this.game = game;
        this.lists = lists;
        this.signer = signer;
        this.recovery = recovery;
        this.audit = audit;
    }

    // rounds

    private static AdminListRepository.RoundFilter roundFilter(String q, String status, String from, String to, String rows, String risk,
                                                               String currency, String player) {
        Integer playerId = ListSupport.integer(player);
        return new AdminListRepository.RoundFilter(q, status, ListSupport.date(from), ListSupport.date(to), ListSupport.integer(rows),
                ListSupport.integer(risk), currency, playerId == null ? null : playerId.longValue());
    }

    private static ListQuery roundQuery(Integer page, Integer size, String sort, String dir) {
        return ListQuery.of(page, size, sort, dir, AdminListRepository.ROUND_SORTS, "time", false);
    }

    @GetMapping("/admin/rounds")
    public String rounds(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                         @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         @RequestParam(required = false) String rows, @RequestParam(required = false) String risk,
                         @RequestParam(required = false) String currency, @RequestParam(required = false) String player,
                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                         @RequestParam(required = false) String sort, @RequestParam(required = false) String dir,
                         HttpServletRequest request, Model model) {
        ListQuery query = roundQuery(page, size, sort, dir);
        AdminListRepository.RoundFilter filter = roundFilter(q, status, from, to, rows, risk, currency, player);
        model.addAttribute("result", lists.rounds(filter, query));
        model.addAttribute("links", ListSupport.Links.of(request, query));
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", ROUND_STATUSES);
        model.addAttribute("rowOptions", java.util.stream.IntStream.rangeClosed(8, 14).boxed().toList());
        model.addAttribute("currencies", lists.playerCurrencies());
        model.addAttribute("playerFilter", filter.playerId() == null ? null : game.findPlayer(filter.playerId()).orElse(null));
        model.addAttribute("verdicts", scanner.verdicts());
        return "admin/rounds";
    }

    @GetMapping("/admin/rounds.csv")
    public void roundsCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                          @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                          @RequestParam(required = false) String rows, @RequestParam(required = false) String risk,
                          @RequestParam(required = false) String currency, @RequestParam(required = false) String player,
                          @RequestParam(required = false) String sort, @RequestParam(required = false) String dir,
                          Principal principal, HttpServletResponse response) throws IOException {
        List<AdminListRepository.RoundRow> rounds = lists.roundsForExport(roundFilter(q, status, from, to, rows, risk, currency, player),
                roundQuery(1, 50, sort, dir));
        audit.log(principal.getName(), "EXPORT_ROUNDS", rounds.size() + " rounds");
        String[] risks = com.plinko.bo.odds.PlinkoBoard.RISK_NAMES;
        com.plinko.bo.game.RoundIntegrityScanner.Verdicts verdicts = scanner.verdicts();
        ListSupport.csv(response, "rounds.csv", List.of("Time (UTC)", "Round", "PAM player id", "Player", "Rows", "Risk", "Balls", "Bet per ball",
                        "Total bet", "Total win", "Currency", "Status", "Signature"),
                rounds.stream().map(row -> {
                    AdminListRepository.RoundSummary r = row.round();
                    return List.<Object>of(ListSupport.time(r.createdAt()), r.id(), row.playerExternalId(), row.playerName() == null ? "" : row.playerName(),
                            r.rows(), risks[r.risk()], r.balls(), r.bet().toPlainString(), r.totalBet().toPlainString(),
                            r.totalWin() == null ? "" : r.totalWin().toPlainString(), r.currency(), r.status(), verdicts.of(r));
                }).toList());
    }

    @GetMapping("/admin/rounds/{id}")
    public String round(@PathVariable String id, Model model) {
        GameRepository.Round round = game.findRound(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("round", round);
        model.addAttribute("player", game.findPlayer(round.playerId()).orElse(null));
        model.addAttribute("transactions", game.transactionsOfRound(id));
        model.addAttribute("signatureValid", round.signature() == null ? null : signer.verify(round));
        model.addAttribute("settlement", settlements.find(id).orElse(null));
        model.addAttribute("settlementPending", changes.pendingFor(com.plinko.bo.changes.ChangeRequestService.ROUND_SETTLE,
                com.plinko.bo.changes.ChangeRequestService.settlementTarget(id)).orElse(null));
        try {
            // R9 #1: a currency without a stored limit can only be closed
            String none = "(no limit stored for " + round.currency() + ": set it on the Settings page first)";
            model.addAttribute("settlementLimit", settlements.maxAmount(round.currency()).map(BigDecimal::toPlainString).orElse(none));
            model.addAttribute("settlementOther", settlements.otherAmount(round.currency()).map(BigDecimal::toPlainString).orElse(none));
        } catch (com.plinko.bo.settings.SettingsService.TamperedException e) {
            // the settings fail their check: no limit is shown (and a proposal is refused) until they are restored
            model.addAttribute("settlementLimit", "(unavailable: restore the settings)");
            model.addAttribute("settlementOther", "(unavailable: restore the settings)");
        }
        model.addAttribute("recomputedWin", round.status().equals(com.plinko.model.RoundStatus.TAMPERED)
                ? settlements.recomputedWin(round).orElse(null) : null);
        return "admin/round";
    }

    /** Review 7.3: proposes settling a blocked round; another admin approves it on the Changes page. */
    @PostMapping("/admin/rounds/{id}/settle")
    @PreAuthorize("hasRole('ADMIN')")
    public String settle(@PathVariable String id, @RequestParam String action, @RequestParam(required = false) String amount,
                         @RequestParam String reason, Principal principal, RedirectAttributes redirect) {
        String back = "redirect:/admin/rounds/" + id;
        java.math.BigDecimal value;
        try {
            value = "CLOSE".equals(action) ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(amount == null ? "" : amount.trim());
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "The amount must be a number");
            return back;
        }
        try {
            long change = changes.proposeSettlement(new com.plinko.bo.game.RoundSettlementService.Proposal(id, action, value, reason),
                    principal.getName());
            redirect.addFlashAttribute("message", "Settlement proposed as change #" + change + ". It is carried out after another admin approves it.");
        } catch (com.plinko.bo.changes.ChangeRequestService.ChangeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return back;
    }

    /** Review 7.3: sends an approved settlement's money again now (the same transaction id; also retried every 5 minutes). */
    @PostMapping("/admin/rounds/{id}/settle/send")
    @PreAuthorize("hasRole('ADMIN')")
    public String sendSettlement(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        String result = settlements.sendAgain(id);
        audit.log(principal.getName(), "ROUND_SETTLEMENT_RETRY", id + ": " + result);
        redirect.addFlashAttribute("message", result);
        return "redirect:/admin/rounds/" + id;
    }

    @PostMapping("/admin/rounds/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public String retry(@PathVariable String id, Principal principal, RedirectAttributes redirect) {
        GameRepository.Round round = game.findRound(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String result = recovery.retryExclusive(round);
        audit.log(principal.getName(), "ROUND_RETRY", id + ": " + result);
        redirect.addFlashAttribute("message", result);
        return "redirect:/admin/rounds/" + id;
    }

    // wallet transactions

    private static AdminListRepository.TransactionFilter txFilter(String q, String type, String status, String from, String to) {
        return new AdminListRepository.TransactionFilter(q, type, status, ListSupport.date(from), ListSupport.date(to));
    }

    private static ListQuery txQuery(Integer page, Integer size, String sort, String dir) {
        return ListQuery.of(page, size, sort, dir, AdminListRepository.TX_SORTS, "time", false);
    }

    @GetMapping("/admin/transactions")
    public String transactions(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                               @RequestParam(required = false) String status, @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort,
                               @RequestParam(required = false) String dir, HttpServletRequest request, Model model) {
        ListQuery query = txQuery(page, size, sort, dir);
        AdminListRepository.TransactionFilter filter = txFilter(q, type, status, from, to);
        model.addAttribute("result", lists.transactions(filter, query));
        model.addAttribute("links", ListSupport.Links.of(request, query));
        model.addAttribute("filter", filter);
        return "admin/transactions";
    }

    @GetMapping("/admin/transactions.csv")
    public void transactionsCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                                @RequestParam(required = false) String status, @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to, @RequestParam(required = false) String sort,
                                @RequestParam(required = false) String dir, Principal principal, HttpServletResponse response) throws IOException {
        List<AdminListRepository.TransactionRow> rows = lists.transactionsForExport(txFilter(q, type, status, from, to), txQuery(1, 50, sort, dir));
        audit.log(principal.getName(), "EXPORT_TRANSACTIONS", rows.size() + " wallet transactions");
        ListSupport.csv(response, "wallet-transactions.csv", List.of("Time (UTC)", "Transaction", "Round", "PAM player id", "Type", "Amount",
                        "Status", "PAM reference", "Attempts", "Error"),
                rows.stream().map(row -> {
                    GameRepository.Transaction t = row.tx();
                    return List.<Object>of(ListSupport.time(t.createdAt()), t.id(), t.roundId(), row.playerExternalId(), t.type(),
                            t.amount().toPlainString(), t.status(), t.pamReference() == null ? "" : t.pamReference(), t.attempts(),
                            t.error() == null ? "" : t.error());
                }).toList());
    }
}
