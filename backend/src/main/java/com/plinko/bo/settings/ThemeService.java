package com.plinko.bo.settings;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Game themes (looks). Purely visual: images and layout under game/public/themes/&lt;id&gt;/; odds and outcomes are the
 * same in every theme. The admin chooses which themes are switched on and the default; a casino may ask for a
 * theme in its launch link, and anything not switched on falls back to the default.
 */
@Service
public class ThemeService {

    /** Every theme that exists in game/public/themes, with its display name. */
    public static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("bombs-away", "Bombs Away");
    }

    public record Themes(List<String> enabled, String defaultTheme) {
    }

    private final SettingsService settings;

    public ThemeService(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * Switched-on themes and the default. A theme is on unless an admin switched it off: the Themes page saves the
     * themes that are on and every theme that existed then ("game.themes.known"), so a theme added later starts
     * switched on. A list saved before the known list existed counts every theme it doesn't name as new (all on).
     */
    public Themes current() {
        Map<String, String> values = settings.rawValues();
        String stored = values.get("game.themes");
        List<String> enabled;
        if (stored == null) {
            enabled = List.copyOf(KNOWN.keySet());
        } else {
            List<String> on = names(stored);
            String known = values.get("game.themes.known");
            List<String> seen = known == null ? on : names(known);
            enabled = KNOWN.keySet().stream().filter(id -> on.contains(id) || !seen.contains(id)).toList();
        }
        if (enabled.isEmpty()) {
            enabled = List.of("bombs-away");
        }
        String defaultTheme = values.getOrDefault("game.theme.default", "bombs-away");
        if (!enabled.contains(defaultTheme)) {
            defaultTheme = enabled.get(0);
        }
        return new Themes(enabled, defaultTheme);
    }

    /** The theme to use: the requested one if it is switched on, otherwise the default. */
    public String resolve(String requested) {
        Themes themes = current();
        return requested != null && themes.enabled().contains(requested) ? requested : themes.defaultTheme();
    }

    /** Problems with a new choice, or empty if it can be saved. */
    public List<String> validate(List<String> enabled, String defaultTheme) {
        if (enabled == null || enabled.isEmpty()) {
            return List.of("Switch on at least one theme");
        }
        if (!enabled.stream().allMatch(KNOWN::containsKey)) {
            return List.of("Unknown theme");
        }
        if (!enabled.contains(defaultTheme)) {
            return List.of("The default theme must be switched on");
        }
        return List.of();
    }

    private static List<String> names(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(KNOWN::containsKey).distinct().toList();
    }

    public void save(List<String> enabled, String defaultTheme) {
        List<String> problems = validate(enabled, defaultTheme);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        settings.setThemes(String.join(",", enabled.stream().distinct().toList()), defaultTheme, String.join(",", KNOWN.keySet()));
    }
}
