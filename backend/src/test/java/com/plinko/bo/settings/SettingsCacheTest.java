package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.plinko.TestFixtures;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.SettingsRepository;
import org.junit.jupiter.api.Test;

/** N14.10: a round's settings reads cost one version read a second at most, and a reload is done once. */
class SettingsCacheTest {

    private final SettingsRepository repository = mock(SettingsRepository.class);
    private final IntegrityLedger ledger = mock(IntegrityLedger.class);
    private final SettingsService settings = new SettingsService(repository, TestFixtures.properties(), ledger);

    @Test
    void aRoundsSettingsReadsReadTheVersionOnce() {
        when(repository.version()).thenReturn(4L);
        when(repository.values()).thenReturn(Map.of("game.enabled", "true", "game.currency", "EUR"));
        when(ledger.isLatest(anyString(), anyString())).thenReturn(true);
        when(ledger.latestContent(anyString())).thenReturn(java.util.Optional.empty());
        settings.features();
        settings.currency();
        settings.limits();
        settings.features();
        settings.rawValues();
        verify(repository, times(1)).version();
        verify(repository, times(1)).values();
        // the minute's re-read reads both again
        settings.refresh();
        settings.features();
        verify(repository, times(2)).version();
        assertThat(settings.version()).isEqualTo(4L);
    }
}
