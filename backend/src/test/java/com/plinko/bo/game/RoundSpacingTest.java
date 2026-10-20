package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.plinko.bo.settings.SettingsService;
import com.plinko.common.ApiException;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Review N14.8 (and 7.4): the minimum time between rounds is checked again under the round lock, so of parallel plays only one passes. */
class RoundSpacingTest {

    private final TestGame game = new TestGame(BonusSettings.OFF);

    @BeforeEach
    void fiveSecondsApart() {
        game.features.set(new SettingsService.GameFeatures(true, false, 0, false, new BigDecimal("5")));
    }

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected the game to refuse");
    }

    @Test
    void aRoundSoonerThanTheMinimumIsRefusedAndOneLaterPlays() {
        game.play("1.00", 1);
        ApiException tooFast = refusal(() -> game.play("1.00", 1));
        assertThat(tooFast.code()).isEqualTo("TOO_FAST");
        assertThat(tooFast.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(game.wallet.count("debit")).isEqualTo(1);

        game.repository.age(Duration.ofSeconds(6));
        assertThat(game.play("1.00", 1).roundId()).isNotNull();
        assertThat(game.wallet.count("debit")).isEqualTo(2);
    }

    @Test
    void withoutAMinimumRoundsFollowEachOtherAtOnce() {
        game.features.set(SettingsService.GameFeatures.DEFAULT);
        game.play("1.00", 1);
        game.play("1.00", 1);
        assertThat(game.wallet.count("debit")).isEqualTo(2);
    }

    @Test
    void aRoundThatFinishedWhileThisOneWaitedForTheLockIsSeenUnderTheLock() {
        // the early check passed (no round yet); just before this request takes the round lock, a parallel one finishes its round
        game.repository.before("claimRound", args -> {
            game.repository.before("claimRound", ignored -> { });
            game.repository.insertRound(new GameRepository.Round("parallel", game.player.id(), "s", 8, 1, BigDecimal.ONE, 1, BigDecimal.ONE,
                    BigDecimal.ZERO, "EUR", RoundStatus.COMPLETED, null, null, null, null, Instant.now(), Instant.now(), null, 1L,
                    new BigDecimal("1000")), null);
        });
        ApiException refused = refusal(() -> game.play("1.00", 1));
        assertThat(refused.code()).isEqualTo("TOO_FAST");
        assertThat(game.wallet.calls).as("no bet taken").isEmpty();
        assertThat(game.repository.rounds().values()).as("no round of its own left behind")
                .noneMatch(r -> RoundStatus.PENDING.equals(r.status()));
        assertThat(game.repository.lockHeld(game.player.id())).as("its claim on the round lock was rolled back").isFalse();
    }

    @RepeatedTest(5)
    void ofManyParallelPlaysOnlyOnePasses() throws Exception {
        game.repository.serialTransactions();
        int players = 12;
        CyclicBarrier start = new CyclicBarrier(players);
        AtomicInteger played = new AtomicInteger();
        Map<String, AtomicInteger> refused = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    game.play("1.00", 1);
                    played.incrementAndGet();
                } catch (ApiException e) {
                    refused.computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    refused.computeIfAbsent(e.toString(), k -> new AtomicInteger()).incrementAndGet();
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(played.get()).as("one play passes (refused: %s)", refused).isEqualTo(1);
        assertThat(refused.keySet()).isSubsetOf("TOO_FAST", "ROUND_IN_PROGRESS");
        assertThat(game.wallet.count("debit")).as("one bet").isEqualTo(1);
        assertThat(game.repository.rounds()).hasSize(1);
        assertThat(game.repository.lockHeld(game.player.id())).isFalse();
        assertThat(game.balance()).isEqualByComparingTo(new BigDecimal("99.00").add(game.repository.rounds().values().iterator().next().totalWin()));
    }
}
