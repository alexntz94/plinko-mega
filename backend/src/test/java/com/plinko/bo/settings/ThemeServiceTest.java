package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Which themes are on and which is the default (Plinko Mega ships Bombs Away, the default, Reactor Drop, Lagoon
 * Drop, Pharaoh's Drop, Zombie War, Neon Nights, Candy Factory and Shurda Plinko). */
class ThemeServiceTest {

    private static ThemeService with(Map<String, String> values) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.rawValues()).thenReturn(values);
        return new ThemeService(settings);
    }

    @Test
    void aFreshInstallHasEveryThemeOnAndPlaysBombsAway() {
        ThemeService.Themes themes = with(Map.of()).current();
        assertThat(themes.enabled()).containsExactly("bombs-away", "reactor-drop", "lagoon-drop", "pharaoh-drop", "zombie-war", "neon-nights", "candy-factory", "shurda-plinko", "hell-of-a-deal", "disco-is-dead", "plinko-exe", "death-and-taxes");
        assertThat(themes.defaultTheme()).isEqualTo("bombs-away");
        assertThat(with(Map.of()).resolve("old-theme")).isEqualTo("bombs-away");
    }

    @Test
    void namesThatAreNotThemesAreIgnored() {
        ThemeService.Themes themes = with(Map.of("game.themes", "old-theme,bombs-away", "game.theme.default", "old-theme")).current();
        // the themes newer than that choice come on too
        assertThat(themes.enabled()).containsExactly("bombs-away", "reactor-drop", "lagoon-drop", "pharaoh-drop", "zombie-war", "neon-nights", "candy-factory", "shurda-plinko", "hell-of-a-deal", "disco-is-dead", "plinko-exe", "death-and-taxes");
        assertThat(themes.defaultTheme()).isEqualTo("bombs-away");
    }

    @Test
    void aChoiceMustBeKnownThemesWithTheDefaultOn() {
        ThemeService themes = with(Map.of());
        assertThat(themes.validate(List.of(), "bombs-away")).isNotEmpty();
        assertThat(themes.validate(List.of("old-theme"), "old-theme")).containsExactly("Unknown theme");
        assertThat(themes.validate(List.of("bombs-away"), "bombs-away")).isEmpty();
        assertThat(themes.validate(List.of("bombs-away", "reactor-drop"), "reactor-drop")).isEmpty();
    }
}
