package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Which themes are on: every theme is, unless an admin switched it off; themes added later start switched on. */
class ThemeServiceTest {

    private static final List<String> ALL = List.copyOf(ThemeService.KNOWN.keySet());

    private static ThemeService with(Map<String, String> values) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.rawValues()).thenReturn(values);
        return new ThemeService(settings);
    }

    private static String allBut(String... off) {
        return ALL.stream().filter(id -> !List.of(off).contains(id)).collect(Collectors.joining(","));
    }

    @Test
    void aFreshInstallHasEveryThemeOn() {
        assertThat(with(Map.of()).current().enabled()).isEqualTo(ALL);
    }

    @Test
    void aListSavedBeforeTheKnownListCountsUnnamedThemesAsNew() {
        // saved before "inked-damned" existed: it is new, so on
        String saved = allBut("inked-damned");
        assertThat(with(Map.of("game.themes", saved)).current().enabled()).isEqualTo(ALL);
    }

    @Test
    void aThemeSwitchedOffStaysOff() {
        String known = String.join(",", ALL);
        assertThat(with(Map.of("game.themes", allBut("rat-race"), "game.themes.known", known)).current().enabled())
                .doesNotContain("rat-race").hasSize(ALL.size() - 1);
    }

    @Test
    void aThemeAddedAfterTheLastSaveStartsOn() {
        // the admin switched rat-race off when inked-damned didn't exist yet
        String known = allBut("inked-damned");
        Map<String, String> values = Map.of("game.themes", allBut("rat-race", "inked-damned"), "game.themes.known", known);
        assertThat(with(values).current().enabled()).contains("inked-damned").doesNotContain("rat-race");
    }
}
