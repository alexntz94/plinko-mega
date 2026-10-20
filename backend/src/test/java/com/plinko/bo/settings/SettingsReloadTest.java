package com.plinko.bo.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.plinko.TestFixtures;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.SettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Review N14.10: one reload however many rounds ask at once, a raised version reloads, and the tampered-settings alarm is logged once per change of state. */
class SettingsReloadTest {

    private final SettingsRepository repository = mock(SettingsRepository.class);
    private final IntegrityLedger ledger = mock(IntegrityLedger.class);
    private final SettingsService settings = new SettingsService(repository, TestFixtures.properties(), ledger);
    private final AtomicInteger valueReads = new AtomicInteger();
    private final AtomicLong version = new AtomicLong(4);
    private final AtomicBoolean trusted = new AtomicBoolean(true);

    private final Logger logger = (Logger) LoggerFactory.getLogger(SettingsService.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(repository.version()).thenAnswer(i -> version.get());
        when(repository.values()).thenAnswer(i -> {
            valueReads.incrementAndGet();
            Thread.sleep(50);       // a database read: long enough for the other requests to arrive while it runs
            return Map.of("game.enabled", "true", "game.currency", "EUR", "game.minRoundSeconds", "1.5");
        });
        when(ledger.isLatest(anyString(), anyString())).thenAnswer(i -> trusted.get());
        when(ledger.latestContent(anyString())).thenReturn(Optional.empty());
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    private List<ILoggingEvent> logs(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @RepeatedTest(3)
    void manyRoundsAskingAtOnceAfterTheMinutesReReadCauseOneReload() throws Exception {
        settings.features();
        settings.refresh();
        int before = valueReads.get();
        int rounds = 32;
        CyclicBarrier start = new CyclicBarrier(rounds);
        List<Thread> threads = new ArrayList<>();
        AtomicInteger answers = new AtomicInteger();
        for (int i = 0; i < rounds; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    if (settings.gameEnabled() && settings.features().minRoundSeconds().compareTo(new java.math.BigDecimal("1.5")) == 0) {
                        answers.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(answers.get()).as("every round got the settings").isEqualTo(rounds);
        assertThat(valueReads.get() - before).as("read from the database once").isEqualTo(1);
    }

    @Test
    void aRaisedVersionIsReadWithinASecondAndReloadsTheSettings() throws Exception {
        settings.features();
        assertThat(valueReads.get()).isEqualTo(1);
        version.incrementAndGet();      // a change on another instance
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (valueReads.get() == 1 && System.nanoTime() < deadline) {
            settings.features();
            Thread.sleep(20);
        }
        assertThat(valueReads.get()).as("reloaded once the new version was seen").isEqualTo(2);
        assertThat(settings.version()).isEqualTo(5);
    }

    @Test
    void aChangeAnnouncedHereIsSeenAtOnce() {
        settings.features();
        version.incrementAndGet();
        org.mockito.Mockito.doAnswer(i -> null).when(repository).raiseVersion();
        settings.announceChange();      // no transaction: the version is read again at the next round
        settings.features();
        assertThat(valueReads.get()).isEqualTo(2);
        org.mockito.Mockito.verify(repository).raiseVersion();
    }

    @Test
    void theTamperedAlarmIsLoggedOncePerChangeOfState() {
        settings.features();
        assertThat(logged.list).as("trusted from the start: nothing to say").isEmpty();

        trusted.set(false);
        for (int minute = 0; minute < 3; minute++) {
            settings.refresh();
            assertThat(settings.gameEnabled()).isFalse();
        }
        assertThat(logs(Level.ERROR)).hasSize(1);
        assertThat(logs(Level.ERROR).get(0).getFormattedMessage()).contains("SETTINGS INTEGRITY FAILURE");

        trusted.set(true);
        for (int minute = 0; minute < 3; minute++) {
            settings.refresh();
            assertThat(settings.gameEnabled()).isTrue();
        }
        assertThat(logs(Level.INFO)).hasSize(1);
        assertThat(logs(Level.INFO).get(0).getFormattedMessage()).contains("trusted again");

        trusted.set(false);
        settings.refresh();
        settings.gameEnabled();
        settings.refresh();
        settings.gameEnabled();
        assertThat(logs(Level.ERROR)).as("tampered again: said once more").hasSize(2);
    }
}
