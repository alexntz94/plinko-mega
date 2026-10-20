package com.plinko.controller.admin;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.plinko.bo.audit.AuditService;
import com.plinko.common.Crypto;
import com.plinko.common.Currencies;
import com.plinko.config.PlinkoProperties;
import com.plinko.repo.MockWalletRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Test wallets for the built-in mock PAM. Does not exist unless MOCK_PAM_ENABLED=true. */
@Controller
@ConditionalOnProperty(name = "plinko.mock-pam.enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN')")
public class MockPamAdminController {

    private final MockWalletRepository wallets;
    private final AuditService audit;
    private final String gameUrl;
    private final String phoneGameUrl;
    private final com.plinko.bo.settings.SettingsService settings;

    /** The new wallet form carries a one-time token: sent twice, it is done once (N14.15). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FormTokens formTokens = new FormTokens();

    public MockPamAdminController(MockWalletRepository wallets, AuditService audit, PlinkoProperties properties, com.plinko.bo.settings.SettingsService settings,
                                  @org.springframework.beans.factory.annotation.Value("${plinko.mock-pam.phone-game-url:}") String phoneGameUrl) {
        this.wallets = wallets;
        this.audit = audit;
        this.gameUrl = properties.gameUrl();
        this.phoneGameUrl = phoneGameUrl;
        this.settings = settings;
    }

    @GetMapping("/admin/mock-pam")
    public String wallets(Model model, jakarta.servlet.http.HttpSession session) {
        model.addAttribute("formToken", formTokens.issue(session));
        List<Map<String, Object>> wallets = this.wallets.all();
        model.addAttribute("wallets", wallets);
        model.addAttribute("gameUrl", gameUrl);
        model.addAttribute("platformCurrency", settings.currency());
        model.addAttribute("themes", com.plinko.bo.settings.ThemeService.KNOWN);
        return "admin/mock-pam";
    }

    @PostMapping("/admin/mock-pam")
    public String create(@RequestParam String username, @RequestParam(required = false) String currency, @RequestParam String balance,
                         @RequestParam(name = FormTokens.FIELD, required = false) String formToken, jakarta.servlet.http.HttpSession session,
                         Principal principal, RedirectAttributes redirect) {
        // new test wallets use the platform currency unless another one is given
        String code = currency == null || currency.isBlank() ? settings.currency() : currency.trim().toUpperCase();
        BigDecimal amount;
        try {
            amount = new BigDecimal(balance.trim()).setScale(2, java.math.RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Balance must be a number");
            return "redirect:/admin/mock-pam";
        }
        if (!username.trim().matches("[a-zA-Z0-9._-]{1,64}") || !Currencies.isValid(code) || amount.signum() < 0) {
            redirect.addFlashAttribute("error", "Invalid username, currency or balance");
            return "redirect:/admin/mock-pam";
        }
        if (!formTokens.use(session, formToken)) {
            redirect.addFlashAttribute("error", FormTokens.REFUSED);
            return "redirect:/admin/mock-pam";
        }
        String playerId = "mock-" + Crypto.randomToken(6);
        wallets.create(playerId, username.trim(), code, amount, Crypto.randomToken(24), Instant.now());
        audit.log(principal.getName(), "MOCK_WALLET_CREATED", playerId + " " + amount + " " + code);
        redirect.addFlashAttribute("message", "Test wallet created");
        return "redirect:/admin/mock-pam";
    }

    /**
     * Issues a fresh single-use launch token and opens the game with it, like a casino lobby does on every launch.
     * A plain link (not a form), so the browser may follow the redirect to the game's address; the admin
     * session cookie is SameSite=Strict, so other sites cannot trigger it.
     */
    @GetMapping("/admin/mock-pam/{playerId}/open")
    public String open(@PathVariable String playerId, @RequestParam(required = false) String theme, Principal principal,
                       RedirectAttributes redirect) {
        String token = Crypto.randomToken(24);
        if (!wallets.setToken(playerId, token)) {
            redirect.addFlashAttribute("error", "Unknown test wallet");
            return "redirect:/admin/mock-pam";
        }
        audit.log(principal.getName(), "MOCK_GAME_OPENED", playerId);
        return "redirect:" + launchUrl(gameUrl, token, theme);
    }

    /**
     * Same as "Open game", for a phone on the same network: issues a fresh single-use token and shows the launch
     * link as a QR code to scan. Uses MOCK_PHONE_GAME_URL (the game's local-network address).
     */
    @GetMapping("/admin/mock-pam/{playerId}/qr")
    public String qr(@PathVariable String playerId, @RequestParam(required = false) String theme, Principal principal,
                     Model model, RedirectAttributes redirect) {
        if (phoneGameUrl == null || phoneGameUrl.isBlank()) {
            redirect.addFlashAttribute("error", "Set MOCK_PHONE_GAME_URL in .env to the game's local-network address "
                    + "(e.g. http://192.168.1.4:8080/) and restart, then the QR code works.");
            return "redirect:/admin/mock-pam";
        }
        String token = Crypto.randomToken(24);
        if (!wallets.setToken(playerId, token)) {
            redirect.addFlashAttribute("error", "Unknown test wallet");
            return "redirect:/admin/mock-pam";
        }
        audit.log(principal.getName(), "MOCK_GAME_QR", playerId);
        String url = launchUrl(phoneGameUrl, token, theme);
        model.addAttribute("playerId", playerId);
        model.addAttribute("theme", theme);
        model.addAttribute("url", url);
        model.addAttribute("qrCode", com.plinko.bo.security.QrCode.svgDataUri(url));
        model.addAttribute("themes", com.plinko.bo.settings.ThemeService.KNOWN);
        return "admin/mock-pam-qr";
    }

    /** like a casino asking for a look; the server still only allows themes that are switched on */
    private static String launchUrl(String base, String token, String theme) {
        String themeParam = theme != null && com.plinko.bo.settings.ThemeService.KNOWN.containsKey(theme) ? "&theme=" + theme : "";
        return base + "?token=" + token + themeParam;
    }
}
