package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import com.plinko.repo.AdminListRepository;
import com.plinko.repo.GameRepository;
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

/** Player info comes from the PAM; the panel can view it and block a player, never change a balance. */
@Controller
public class PlayerAdminController {

    private static final int PAGE_SIZE = 50;

    private final GameRepository game;
    private final AdminListRepository lists;
    private final PamClient pam;
    private final com.plinko.bo.game.RoundIntegrityScanner scanner;
    private final com.plinko.repo.GameEventRepository events;
    private final AuditService audit;

    public PlayerAdminController(GameRepository game, AdminListRepository lists, PamClient pam, com.plinko.bo.game.RoundIntegrityScanner scanner,
                                 AuditService audit, com.plinko.repo.GameEventRepository events) {
        this.events = events;
        this.game = game;
        this.lists = lists;
        this.pam = pam;
        this.scanner = scanner;
        this.audit = audit;
    }

    private static AdminListRepository.PlayerFilter filter(String q, String status, String currency, String active) {
        return new AdminListRepository.PlayerFilter(q, status, currency, "1".equals(active));
    }

    private static com.plinko.common.ListQuery query(Integer page, Integer size, String sort, String dir) {
        return com.plinko.common.ListQuery.of(page, size, sort, dir, AdminListRepository.PLAYER_SORTS, "lastSeen", false);
    }

    @GetMapping("/admin/players")
    public String players(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                          @RequestParam(required = false) String currency, @RequestParam(required = false) String active,
                          @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                          @RequestParam(required = false) String sort, @RequestParam(required = false) String dir,
                          jakarta.servlet.http.HttpServletRequest request, Model model) {
        com.plinko.common.ListQuery query = query(page, size, sort, dir);
        var result = lists.players(filter(q, status, currency, active), query);
        model.addAttribute("result", result);
        model.addAttribute("links", ListSupport.Links.of(request, query));
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("currency", currency);
        model.addAttribute("active", "1".equals(active));
        model.addAttribute("currencies", lists.playerCurrencies());
        model.addAttribute("activeNow", result.items().stream().filter(PlayerAdminController::activeNow).map(GameRepository.Player::id).toList());
        return "admin/players";
    }

    @GetMapping("/admin/players.csv")
    public void playersCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String currency, @RequestParam(required = false) String active,
                           @RequestParam(required = false) String sort, @RequestParam(required = false) String dir,
                           Principal principal, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        List<GameRepository.Player> players = lists.playersForExport(filter(q, status, currency, active), query(1, 50, sort, dir));
        audit.log(principal.getName(), "EXPORT_PLAYERS", players.size() + " players");
        ListSupport.csv(response, "players.csv", List.of("PAM player id", "Username", "Currency", "Balance (from PAM)", "Balance as of (UTC)",
                        "First seen (UTC)", "Status"),
                players.stream().map(p -> List.<Object>of(p.externalId(), p.username() == null ? "" : p.username(), p.currency(),
                        p.lastBalance() == null ? "" : p.lastBalance().toPlainString(), ListSupport.time(p.lastSeenAt()),
                        ListSupport.time(p.createdAt()), p.blocked() ? "Blocked" : "Allowed")).toList());
    }

    @GetMapping("/admin/players/{id}")
    public String player(@PathVariable long id, Model model) {
        GameRepository.Player player = game.findPlayer(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("player", player);
        model.addAttribute("activeNow", activeNow(player));
        model.addAttribute("rounds", lists.latestRounds(id, 100));
        model.addAttribute("verdicts", scanner.verdicts());
        model.addAttribute("gameEvents", events.summary(id));
        return "admin/player";
    }

    /** Played (or had their balance read) in the last 2 minutes. */
    private static boolean activeNow(GameRepository.Player player) {
        return player.lastSeenAt() != null && player.lastSeenAt().isAfter(java.time.Instant.now().minusSeconds(120));
    }

    @PostMapping("/admin/players/{id}/refresh-balance")
    @PreAuthorize("hasRole('OPERATOR')")
    public String refreshBalance(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        GameRepository.Player player = game.findPlayer(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String token = game.rounds(id, null, 1, 0).stream().findFirst()
                .flatMap(r -> game.findSession(r.sessionId())).map(GameRepository.Session::pamToken).orElse("");
        try {
            BigDecimal balance = pam.balance(player.externalId(), token);
            game.updatePlayerBalance(id, balance);
            audit.log(principal.getName(), "PLAYER_BALANCE_REFRESHED", player.externalId() + ": " + balance.toPlainString() + " " + player.currency());
            redirect.addFlashAttribute("message", "Balance from PAM: " + balance.toPlainString() + " " + player.currency());
        } catch (PamException e) {
            redirect.addFlashAttribute("error", "PAM error: " + e.code() + " " + e.getMessage());
        }
        return "redirect:/admin/players/" + id;
    }

    @PostMapping("/admin/players/{id}/block")
    @PreAuthorize("hasRole('OPERATOR')")
    public String block(@PathVariable long id, @RequestParam boolean blocked, @RequestParam String reason, Principal principal,
                        RedirectAttributes redirect) {
        GameRepository.Player player = game.findPlayer(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (reason == null || reason.isBlank()) {
            redirect.addFlashAttribute("error", "A reason is required");
            return "redirect:/admin/players/" + id;
        }
        game.setPlayerBlocked(id, blocked);
        audit.log(principal.getName(), blocked ? "PLAYER_BLOCKED" : "PLAYER_UNBLOCKED",
                player.externalId() + " (" + player.username() + "): " + reason.trim());
        redirect.addFlashAttribute("message", blocked ? "Player blocked" : "Player unblocked");
        return "redirect:/admin/players/" + id;
    }
}
