package com.plinko.controller.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.plinko.bo.settings.SettingsService;
import com.plinko.bo.settings.ThemeService;
import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import com.plinko.repo.MockWalletRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The public demo. /demo (nginx forwards it here) lists the themes that are switched on; /demo/play makes a fresh
 * wallet of fake credits in the built-in mock PAM and opens the game in the chosen theme. Exists only with DEMO_MODE=true, and that refuses to start unless the wallet is the
 * built-in mock PAM, so a server with a real PAM can never hand out demo wallets. nginx limits how often one visitor
 * may call it; this caps how many wallets are made a day in total.
 */
@Controller
@ConditionalOnProperty(name = "plinko.demo.enabled", havingValue = "true")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    /** The built-in mock PAM on this same backend; anything else is a real wallet. */
    private static final String MOCK_PAM_URL = "^http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?/mock-pam/?$";

    private final MockWalletRepository wallets;
    private final PlinkoProperties properties;
    private final SettingsService settings;
    private final ThemeService themes;
    private final BigDecimal credits;
    private final int perDay;
    /** Each theme's preview fingerprint, so a new picture gets a new address (Cloudflare keeps images for hours). */
    private final Map<String, String> previewVersions = new ConcurrentHashMap<>();

    public DemoController(MockWalletRepository wallets, PlinkoProperties properties, SettingsService settings, ThemeService themes,
                          @Value("${plinko.demo.credits}") BigDecimal credits, @Value("${plinko.demo.per-day}") int perDay) {
        this.wallets = wallets;
        this.properties = properties;
        this.settings = settings;
        this.themes = themes;
        this.credits = credits.setScale(2, RoundingMode.DOWN);
        this.perDay = perDay;
    }

    @PostConstruct
    void checkConfig() {
        if (!properties.mockPam().enabled() || properties.pam().baseUrl() == null || !properties.pam().baseUrl().matches(MOCK_PAM_URL)) {
            throw new IllegalStateException("DEMO_MODE=true needs MOCK_PAM_ENABLED=true and PAM_BASE_URL set to the built-in mock PAM "
                    + "(e.g. http://localhost:8080/mock-pam). Demo mode is never allowed with a real wallet.");
        }
        if (credits.signum() <= 0 || credits.compareTo(BigDecimal.valueOf(1_000_000)) > 0 || perDay < 1) {
            throw new IllegalStateException("DEMO_CREDITS must be above 0 and at most 1000000, DEMO_WALLETS_PER_DAY at least 1");
        }
        log.warn("DEMO MODE: /demo gives every visitor {} fake credits (at most {} wallets a day). No real money.", credits, perDay);
    }

    /** The lobby: one card per theme that is switched on. Looking costs nothing; a wallet is made only on play. */
    @GetMapping("/api/demo")
    public String lobby(Model model) {
        Map<String, String> on = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (String id : themes.current().enabled()) {
            on.put(id, ThemeService.KNOWN.get(id));
            versions.put(id, previewVersions.computeIfAbsent(id, DemoController::previewVersion));
        }
        model.addAttribute("themes", on);
        model.addAttribute("versions", versions);
        model.addAttribute("credits", String.format(java.util.Locale.ROOT, "%,d", credits.longValue()));
        // link previews need full addresses: the public address from GAME_URL
        String site = properties.gameUrl() == null ? "/" : properties.gameUrl();
        model.addAttribute("site", site.endsWith("/") ? site : site + "/");
        return "demo/lobby";
    }

    /**
     * The first 12 hex characters of the preview's SHA-256. The game serves /themes/<id>/preview.png; the backend has
     * the same picture for the admin's Themes page, so it can fingerprint it without asking the game.
     */
    private static String previewVersion(String id) {
        try (var in = new ClassPathResource("static/admin-assets/themes/" + id + ".png").getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())).substring(0, 12);
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * An old /demo/play link, or a link previewer fetching it: no wallet, back to the lobby (review 2.5: only the Play
     * button, a POST that previewers never send, makes a wallet).
     */
    @GetMapping("/api/demo/play")
    @ResponseBody
    public ResponseEntity<String> playLink() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/demo")).cacheControl(CacheControl.noStore()).build();
    }

    /** The Play button: makes a demo wallet and sends the browser to the game with its single-use launch token. */
    @org.springframework.web.bind.annotation.PostMapping("/api/demo/play")
    @ResponseBody
    public ResponseEntity<String> play(@RequestParam(required = false) String theme) {
        Instant now = Instant.now();
        if (wallets.demoWalletsSince(now.minus(Duration.ofDays(1))) >= perDay) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
                    .cacheControl(CacheControl.noStore()).body("The demo is busy right now. Please try again later.");
        }
        String token = Crypto.randomToken(24);
        wallets.create("demo-" + Crypto.randomToken(9), "Demo player", settings.currency(), credits, token, now);
        // a relative address: the game is on the same host as /demo, whatever the public address is
        // only a theme that is switched on (the game server checks again when it opens the session)
        String themeParam = theme != null && themes.current().enabled().contains(theme) ? "&theme=" + theme : "";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/?token=" + token + themeParam))
                .cacheControl(CacheControl.noStore()).build();
    }
}
