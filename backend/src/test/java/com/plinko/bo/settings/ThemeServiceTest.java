package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Which themes are on and which is the default (Plinko Mega ships one theme, Lotería Muerta). */
class ThemeServiceTest {

    private static ThemeService with(Map<String, String> values) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.rawValues()).thenReturn(values);
        return new ThemeService(settings);
    }

    @Test
    void aFreshInstallPlaysLoteriaMuerta() {
        ThemeService.Themes themes = with(Map.of()).current();
        assertThat(themes.enabled()).containsExactly("loteria-muerta");
        assertThat(themes.defaultTheme()).isEqualTo("loteria-muerta");
        assertThat(with(Map.of()).resolve("old-theme")).isEqualTo("loteria-muerta");
    }

    @Test
    void namesThatAreNotThemesAreIgnored() {
        ThemeService.Themes themes = with(Map.of("game.themes", "old-theme,loteria-muerta", "game.theme.default", "old-theme")).current();
        assertThat(themes.enabled()).containsExactly("loteria-muerta");
        assertThat(themes.defaultTheme()).isEqualTo("loteria-muerta");
    }

    @Test
    void aChoiceMustBeKnownThemesWithTheDefaultOn() {
        ThemeService themes = with(Map.of());
        assertThat(themes.validate(List.of(), "loteria-muerta")).isNotEmpty();
        assertThat(themes.validate(List.of("old-theme"), "old-theme")).containsExactly("Unknown theme");
        assertThat(themes.validate(List.of("loteria-muerta"), "loteria-muerta")).isEmpty();
    }
}
