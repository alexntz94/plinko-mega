package com.plinko.controller.admin;

import java.security.Principal;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.bo.settings.ThemeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Which game themes are switched on and which is the default. Themes are purely visual (no money or odds),
 * so operators change them directly; every change is in the audit log.
 */
@Controller
public class ThemeAdminController {

    private final ThemeService themes;
    private final SettingsService settings;
    private final AuditService audit;

    public ThemeAdminController(ThemeService themes, SettingsService settings, AuditService audit) {
        this.themes = themes;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/admin/themes")
    public String themes(Model model) {
        model.addAttribute("known", ThemeService.KNOWN);
        model.addAttribute("current", themes.current());
        return "admin/themes";
    }

    @PostMapping("/admin/themes")
    @PreAuthorize("hasRole('OPERATOR')")
    public String save(@RequestParam(name = "enabled", required = false) List<String> enabled, @RequestParam String defaultTheme,
                       Principal principal, RedirectAttributes redirect) {
        List<String> problems = themes.validate(enabled, defaultTheme);
        if (!problems.isEmpty()) {
            redirect.addFlashAttribute("error", String.join("; ", problems));
            return "redirect:/admin/themes";
        }
        if (!settings.trusted()) {
            redirect.addFlashAttribute("error", "The settings were changed outside the admin panel; restore them first (see the alarm).");
            return "redirect:/admin/themes";
        }
        ThemeService.Themes before = themes.current();
        try {
            themes.save(enabled, defaultTheme);
        } catch (SettingsService.TamperedException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/themes";
        }
        audit.log(principal.getName(), "THEMES_CHANGED", "switched on " + before.enabled() + " -> " + enabled
                + ", default " + before.defaultTheme() + " -> " + defaultTheme);
        redirect.addFlashAttribute("message", "Themes saved. New game sessions use them straight away.");
        return "redirect:/admin/themes";
    }
}
