package com.plinko.bo.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.TestOdds;
import com.plinko.bo.settings.SettingsService;
import com.plinko.bo.settings.ThemeService;
import com.plinko.common.ApiException;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

/**
 * Thousands of rounds played by the real round code, with the gates, the wheel and a meter that fills often and a low
 * win limit (so caps happen too), on every board: every one of them must pass the credit check and be
 * paid exactly its win. If the game and the check ever disagree, genuine wins would be blocked.
 */
class RealRoundsPassWinCheckTest {

    private static final BonusSettings BONUSES = new BonusSettings(new BonusSettings.Meter(true, 3, new BigDecimal("2"), new BigDecimal("30")),
            BonusSettings.MEGA.gates(), BonusSettings.MEGA.wheel(), BonusSettings.MEGA.megaWheel());

    private final PlinkoProperties base = TestFixtures.properties();
    /** A low win limit per round (40), so caps happen; maxRtp as given (the forced-cap run needs bonuses no real odds allow). */
    private PlinkoProperties properties(String maxRtp) {
        return new PlinkoProperties(base.admin(), base.requireSecondApprover(), base.pam(), base.mockPam(),
                base.roundSigningKey(), new PlinkoProperties.Limits(new BigDecimal(maxRtp), base.limits().minRtp(), base.limits().maxMultiplier(),
                base.limits().maxBet(), base.limits().maxBalls(), new BigDecimal("40")), base.login(), base.gameUrl(), base.sessionTtlMinutes(),
                base.retry());
    }

    private PlinkoProperties properties = properties("99.7");

    private final Map<String, GameRepository.Round> rounds = new LinkedHashMap<>();
    private final List<GameRepository.Transaction> transactions = new ArrayList<>();
    private final List<String> paid = new ArrayList<>();
    private final Map<String, String> continuedBy = new java.util.HashMap<>();
    private final Map<String, String> byRequest = new java.util.HashMap<>();
    private final String[] meterPointer = {null};
    private final GameRepository.Player player = new GameRepository.Player(7, "pam-7", "alice", "EUR", null, false, Instant.now(), Instant.now());

    /** Gates of x10: a ball through them pays 10, 100 or 1000 times its prize, so many rounds go over the limit and are capped. */
    private static final BonusSettings BIG_GATES = new BonusSettings(BonusSettings.OFF.meter(), new BonusSettings.Gates(true, BigDecimal.TEN),
            BonusSettings.OFF.wheel(), BonusSettings.OFF.megaWheel());

    @Test
    void plinkoMegaRoundsWithGatesWheelAndMegaSpinPassTheCheck() {
        int[] stats = play(BonusSettings.MEGA, 4000, 20);
        assertThat(stats[0]).isGreaterThan(3000);
        assertThat(stats[2]).as("some rounds paid a MEGA SPIN").isPositive();
    }

    @Test
    void everyRealRoundPassesTheCheckAndIsPaidItsWin() {
        int[] stats = play(BONUSES, 4000, 20);
        assertThat(stats[0]).isGreaterThan(3000);
        assertThat(stats[2]).as("some rounds paid a MEGA SPIN").isPositive();
    }

    @Test
    void cappedRealRoundsPassTheCheckToo() {
        properties = properties("10000");
        int[] stats = play(BIG_GATES, 1000, 4000);
        assertThat(stats[1]).as("rounds capped at the limit").isGreaterThan(20);
    }

    /** Review 5.3 #3: every ball on the top prize at the biggest bet allowed stays within the limit; a cent more is refused. */
    @Test
    void theBiggestBetOnTheTopPrizeStaysWithinTheLimit() {
        properties = properties("10000");
        for (int balls : new int[] {10, 100}) {
            GameService game = game(BonusSettings.OFF, true);
            // the biggest bet the game takes on this board: its top prize times the bet stays within the limit
            BigDecimal top = com.plinko.bo.odds.TestOdds.with(BonusSettings.OFF, properties).get(8, 2).maxMultiplier();
            BigDecimal bet = new BigDecimal("40").divide(top.multiply(BigDecimal.valueOf(balls)), 2, java.math.RoundingMode.DOWN);
            assertThat(bet).as("a bet the game takes").isGreaterThanOrEqualTo(new BigDecimal("0.01"));
            GameService.PlayResponse response = game.play("s", new GameService.PlayRequest(8, 2, bet, balls));
            GameRepository.Round round = rounds.get(response.roundId());
            BigDecimal perBall = RoundWin.ball(bet, top);
            assertThat(response.slots()).hasSize(balls);
            assertThat(response.wins()).allSatisfy(w -> assertThat(w).isEqualByComparingTo(perBall));
            assertThat(response.totalWin()).isEqualByComparingTo(perBall.multiply(BigDecimal.valueOf(balls))).isLessThanOrEqualTo(new BigDecimal("40.00"));
            assertThat(round.status()).isEqualTo(RoundStatus.COMPLETED);
            BigDecimal oneCentMore = bet.add(new BigDecimal("0.01"));
            assertThatThrownBy(() -> game.play("s", new GameService.PlayRequest(8, 2, oneCentMore, balls)))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BET_TOO_HIGH");
        }
    }

    /** Review 5.3 #4: requests the game must refuse before anything happens. */
    @Test
    void invalidRequestsAreRefused() {
        GameService game = game(BonusSettings.OFF, false);
        List<GameService.PlayRequest> bad = List.of(
                new GameService.PlayRequest(null, 0, BigDecimal.ONE, 1), new GameService.PlayRequest(8, null, BigDecimal.ONE, 1),
                new GameService.PlayRequest(8, 0, null, 1), new GameService.PlayRequest(8, 0, BigDecimal.ONE, null),
                new GameService.PlayRequest(7, 0, BigDecimal.ONE, 1), new GameService.PlayRequest(15, 0, BigDecimal.ONE, 1),
                new GameService.PlayRequest(8, -1, BigDecimal.ONE, 1), new GameService.PlayRequest(8, 3, BigDecimal.ONE, 1),
                new GameService.PlayRequest(8, 0, BigDecimal.ONE, 0), new GameService.PlayRequest(8, 0, BigDecimal.ONE, 101),
                new GameService.PlayRequest(8, 0, BigDecimal.ZERO, 1), new GameService.PlayRequest(8, 0, new BigDecimal("-1"), 1),
                new GameService.PlayRequest(8, 0, new BigDecimal("0.001"), 1), new GameService.PlayRequest(8, 0, new BigDecimal("0.00"), 1),
                new GameService.PlayRequest(8, 0, new BigDecimal("100.01"), 1), new GameService.PlayRequest(8, 0, new BigDecimal("1e30"), 1),
                new GameService.PlayRequest(8, 0, new BigDecimal("1e2"), 1),
                new GameService.PlayRequest(Integer.MAX_VALUE, Integer.MIN_VALUE, new BigDecimal("99999999999999999999.99"), Integer.MAX_VALUE));
        for (GameService.PlayRequest request : bad) {
            assertThatThrownBy(() -> game.play("s", request)).as("%s", request)
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_REQUEST");
        }
        assertThat(rounds).as("nothing was played").isEmpty();
        assertThat(game.play("s", new GameService.PlayRequest(8, 0, new BigDecimal("0.10"), 1)).roundId()).isNotNull();
    }

    /** Plays rounds and checks each; returns {played, capped, with a MEGA SPIN}. */
    private int[] play(BonusSettings bonuses, int count, int maxBetCents) {
        GameService game = game(bonuses, false);
        Random random = new Random(42);
        int played = 0;
        int capped = 0;
        int withFreeBalls = 0;
        for (int i = 0; i < count; i++) {
            int rows = com.plinko.bo.odds.PlinkoBoard.MIN_ROWS + random.nextInt(com.plinko.bo.odds.PlinkoBoard.MAX_ROWS - com.plinko.bo.odds.PlinkoBoard.MIN_ROWS + 1);
            int risk = random.nextInt(3);
            int balls = 1 + random.nextInt(random.nextInt(10) == 0 ? 100 : 5);
            BigDecimal bet = BigDecimal.valueOf(1 + random.nextInt(maxBetCents), 2);
            try {
                GameService.PlayResponse response = game.play("s", new GameService.PlayRequest(rows, risk, bet, balls));
                GameRepository.Round round = rounds.get(response.roundId());
                assertThat(round.status()).as("round %s: %s", round.id(), round.error()).isEqualTo(RoundStatus.COMPLETED);
                assertThat(paid).last().isEqualTo(round.id() + "=" + round.totalWin().toPlainString());
                played++;
                capped += round.bonus() != null && round.bonus().contains("capped:") ? 1 : 0;
                withFreeBalls += round.bonus() != null && round.bonus().contains("mega:") ? 1 : 0;
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("BET_TOO_HIGH");
            }
        }
        return new int[] {played, capped, withFreeBalls};
    }

    private final java.util.concurrent.atomic.AtomicReference<SettingsService.GameFeatures> lastSettings =
            new java.util.concurrent.atomic.AtomicReference<>(SettingsService.GameFeatures.DEFAULT);
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastRoundAt = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<PamClient> lastPam = new java.util.concurrent.atomic.AtomicReference<>();
    private static final Instant HISTORY_TIME = Instant.parse("2026-10-17T10:00:00Z");

    private GameService game(BonusSettings bonuses, boolean topPrizeOnly) {
        // stub only: 5,000 rounds of calls are never verified, and recording them all runs a small heap out of memory
        GameRepository repository = mock(GameRepository.class, org.mockito.Mockito.withSettings().stubOnly());
        PamClient pam = mock(PamClient.class);
        lastPam.set(pam);
        when(repository.lastRoundAt(anyLong())).thenAnswer(i -> Optional.ofNullable(lastRoundAt.get()));
        when(repository.findSession(anyString())).thenReturn(Optional.of(new GameRepository.Session("s", 7, "tok",
                Instant.now().plusSeconds(3600), "loteria-muerta")));
        when(repository.findPlayer(7)).thenReturn(Optional.of(player));
        when(repository.claimRound(anyLong(), anyString(), anyLong())).thenReturn(true);
        doAnswer(i -> {
            GameRepository.Round r = i.getArgument(0);
            String requestId = i.getArgument(1);
            if (requestId != null && byRequest.putIfAbsent(requestId, r.id()) != null) {
                throw new org.springframework.dao.DuplicateKeyException("uk_round_request");
            }
            return rounds.put(r.id(), r);
        }).when(repository).insertRound(any(), any());
        when(repository.roundByRequest(anyLong(), anyString())).thenAnswer(i -> Optional.ofNullable(byRequest.get((String) i.getArgument(1)))
                .map(rounds::get));
        doAnswer(i -> {
            GameRepository.Round r = rounds.get((String) i.getArgument(0));
            rounds.put(r.id(), new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(),
                    i.getArgument(5), r.currency(), i.getArgument(7), i.getArgument(1), i.getArgument(2), null, i.getArgument(6), null, null,
                    i.getArgument(3), r.oddsRef(), r.maxWin()));
            if (i.getArgument(4) != null && continuedBy.putIfAbsent(i.getArgument(4), r.id()) != null) {
                throw new org.springframework.dao.DuplicateKeyException("meter_prev");
            }
            return null;
        }).when(repository).saveOutcome(anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyString());
        doAnswer(i -> transactions.add(i.getArgument(0))).when(repository).insertTransaction(any());
        doAnswer(i -> {
            for (int t = 0; t < transactions.size(); t++) {
                GameRepository.Transaction tx = transactions.get(t);
                if (tx.id().equals(i.getArgument(0))) {
                    transactions.set(t, new GameRepository.Transaction(tx.id(), tx.roundId(), tx.playerId(), tx.type(), tx.amount(),
                            i.getArgument(1), tx.originalTxId(), i.getArgument(2), i.getArgument(3), tx.attempts() + 1, null, null, tx.signature()));
                }
            }
            return null;
        }).when(repository).updateTransaction(anyString(), anyString(), any(), any());
        doAnswer(i -> {
            GameRepository.Round r = rounds.get((String) i.getArgument(0));
            rounds.put(r.id(), new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(),
                    r.totalWin(), r.currency(), i.getArgument(1), r.slots(), r.multipliers(), i.getArgument(2), r.signature(), null, null,
                    r.bonus(), r.oddsRef(), r.maxWin()));
            return null;
        }).when(repository).updateRoundStatus(anyString(), anyString(), any());
        when(repository.findRound(anyString())).thenAnswer(i -> Optional.ofNullable(rounds.get((String) i.getArgument(0))));
        // the player's history (7.5): decided rounds, all at one moment here, newest (highest id) first, keyset by id
        when(repository.history(anyLong(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> rounds.values().stream()
                .filter(r -> r.signature() != null && r.id().compareTo(i.getArgument(3)) < 0)
                .sorted(java.util.Comparator.comparing(GameRepository.Round::id).reversed()).limit((int) i.getArgument(4))
                .map(r -> new GameRepository.Round(r.id(), r.playerId(), r.sessionId(), r.rows(), r.risk(), r.bet(), r.balls(), r.totalBet(),
                        r.totalWin(), r.currency(), r.status(), r.slots(), r.multipliers(), r.error(), r.signature(), HISTORY_TIME, null,
                        r.bonus(), r.oddsRef(), r.maxWin()))
                .toList());
        when(repository.transactionsOfRound(anyString())).thenAnswer(i -> transactions.stream()
                .filter(tx -> tx.roundId().equals(i.getArgument(0))).toList());
        // the meter chain: the player's pointer, and which round continued from which state (the unique meter_prev)
        when(repository.meterRound(anyLong())).thenAnswer(i -> Optional.ofNullable(meterPointer[0]));
        doAnswer(i -> meterPointer[0] = i.getArgument(1)).when(repository).setMeterRound(anyLong(), anyString());
        when(repository.roundContinuing(anyString())).thenAnswer(i -> Optional.ofNullable(continuedBy.get((String) i.getArgument(0)))
                .map(rounds::get));
        when(pam.debit(any())).thenReturn(new PamClient.PamResult(new BigDecimal("1000"), "d"));
        when(pam.credit(any())).thenAnswer(i -> {
            PamClient.WalletRequest request = i.getArgument(0);
            paid.add(request.roundId() + "=" + request.amount().toPlainString());
            return new PamClient.PamResult(new BigDecimal("1000"), "c");
        });

        SettingsService settings = mock(SettingsService.class);
        when(settings.limits()).thenReturn(new SettingsService.GameLimits(new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("1"), 100));
        when(settings.currency()).thenReturn("EUR");
        when(settings.gameEnabled()).thenReturn(true);
        when(settings.features()).thenAnswer(i -> lastSettings.get());
        OddsService odds = TestOdds.with(bonuses, properties, topPrizeOnly);
        RoundSigner signer = new RoundSigner(properties);
        WalletSigner walletSigner = new WalletSigner(new Integrity(properties));
        WinCheck winCheck = new WinCheck(signer, walletSigner, odds, properties);
        RoundRecoveryService recovery = new RoundRecoveryService(repository, pam, signer, walletSigner, mock(AuditService.class), winCheck, properties);
        BonusService bonus = new BonusService(null, tools.jackson.databind.json.JsonMapper.builder().build()) {
            @Override
            public BonusSettings current() {
                return bonuses;
            }

        };
        com.plinko.TestFixtures.snapshotFromStubs(settings, () -> "test-bonus");    // what TestOdds holds as the stored bonus
        return new GameService(repository, pam, odds, settings, signer, walletSigner, properties, mock(ThemeService.class), bonus,
                winCheck, recovery, new com.plinko.NoTransactions(), false);
    }

    /** Review 2.3: a repeated request returns the round already played, and takes no second bet. */
    @Test
    void aRepeatedRequestReturnsTheSameRoundWithoutASecondBet() {
        GameService game = game(BONUSES, false);
        String id = java.util.UUID.randomUUID().toString();
        GameService.PlayResponse first = game.play("s", new GameService.PlayRequest(10, 1, new BigDecimal("0.50"), 5, id));
        GameService.PlayResponse again = game.play("s", new GameService.PlayRequest(10, 1, new BigDecimal("0.50"), 5, id));
        assertThat(again.roundId()).isEqualTo(first.roundId());
        assertThat(again.slots()).isEqualTo(first.slots());
        assertThat(again.totalWin()).isEqualByComparingTo(first.totalWin());
        assertThat(again.wins()).isEqualTo(first.wins());
        assertThat(again.paths()).isEqualTo(first.paths());
        assertThat(again.spins()).isEqualTo(first.spins());
        assertThat(transactions.stream().filter(t -> "DEBIT".equals(t.type())).count()).isEqualTo(1);
        assertThat(paid).hasSize(1);
        // the same id for a different bet is refused; an id that is not a UUID too
        assertThatThrownBy(() -> game.play("s", new GameService.PlayRequest(10, 1, new BigDecimal("1.00"), 5, id)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_MISMATCH");
        assertThatThrownBy(() -> game.play("s", new GameService.PlayRequest(10, 1, new BigDecimal("0.50"), 5, "not-a-uuid")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_REQUEST");
        // a new id plays a new round
        assertThat(game.play("s", new GameService.PlayRequest(10, 1, new BigDecimal("0.50"), 5, java.util.UUID.randomUUID().toString()))
                .roundId()).isNotEqualTo(first.roundId());
    }

    @Test
    void theHistoryPagesThroughThePlayersRoundsAndReplaysEachExactly() {
        GameService game = game(BONUSES, false);
        java.util.Set<String> played = new java.util.HashSet<>();
        java.util.Map<String, GameService.PlayResponse> byId = new java.util.HashMap<>();
        for (int n = 0; n < 3; n++) {
            GameService.PlayResponse r = game.play("s", new GameService.PlayRequest(12, 2, new BigDecimal("0.05"), 3, null));
            played.add(r.roundId());
            byId.put(r.roundId(), r);
        }
        GameService.HistoryPage first = game.history("s", null, 2);
        assertThat(first.rounds()).hasSize(2);
        assertThat(first.next()).isNotNull();
        GameService.HistoryPage second = game.history("s", first.next(), 2);
        assertThat(second.rounds()).hasSize(1);
        assertThat(second.next()).isNull();
        java.util.List<GameService.HistoryEntry> all = new java.util.ArrayList<>(first.rounds());
        all.addAll(second.rounds());
        assertThat(all).extracting(e -> e.round().roundId()).containsExactlyInAnyOrderElementsOf(played);
        for (GameService.HistoryEntry entry : all) {
            GameService.PlayResponse original = byId.get(entry.round().roundId());
            assertThat(entry.rows()).isEqualTo(12);
            assertThat(entry.risk()).isEqualTo(2);
            assertThat(entry.round().slots()).isEqualTo(original.slots());
            assertThat(entry.round().wins()).isEqualTo(original.wins());
            assertThat(entry.round().totalWin()).isEqualByComparingTo(original.totalWin());
            assertThat(entry.prizes()).hasSize(7);   // by prize position: centre to edge, 12 rows
            // the history never shows a balance or the meter: it is not a way to read the wallet
            assertThat(entry.round().balance()).isNull();
        }
        assertThatThrownBy(() -> game.history("s", "x:' OR 1=1", 2)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void theMarketsMinimumTimePerRoundIsEnforcedByTheServer() {
        GameService game = game(BONUSES, false);
        game.play("s", new GameService.PlayRequest(8, 0, new BigDecimal("1.00"), 1, null));
        // the setting is only read when it is on; switched on, a round right after the last one is refused
        GameService strict = game(BONUSES, false);
        lastSettings.set(new com.plinko.bo.settings.SettingsService.GameFeatures(true, false, 0, false, new BigDecimal("3")));
        lastRoundAt.set(java.time.Instant.now().minusMillis(500));
        assertThatThrownBy(() -> strict.play("s", new GameService.PlayRequest(8, 0, new BigDecimal("1.00"), 1, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TOO_FAST");
        lastRoundAt.set(java.time.Instant.now().minusSeconds(4));
        assertThat(strict.play("s", new GameService.PlayRequest(8, 0, new BigDecimal("1.00"), 1, null)).roundId()).isNotNull();
    }

    @Test
    void theCasinosLimitIsShownToThePlayerAsSuch() {
        GameService game = game(BONUSES, false);
        when(lastPam.get().debit(any())).thenThrow(new com.plinko.pam.PamException(com.plinko.pam.PamException.LIMIT_REACHED, "loss limit", false));
        assertThatThrownBy(() -> game.play("s", new GameService.PlayRequest(8, 0, new BigDecimal("1.00"), 1, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("LIMIT_REACHED");
    }
}
