package com.plinko.bo.game;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.TestOdds;
import com.plinko.bo.settings.SettingsService;
import com.plinko.bo.settings.ThemeService;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.repo.GameRepository;

/**
 * The real game and recovery services over the in-memory tables and wallet, for whole-round tests: one player
 * ("pam-7", 100.00 EUR) with an open session "s"; the game's switch, currency and player options can be changed mid-session.
 */
final class TestGame {

    final MemoryGameRepository repository = new MemoryGameRepository();
    final MemoryWallet wallet = new MemoryWallet();
    final AuditService audit = mock(AuditService.class);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicReference<String> currency = new AtomicReference<>("EUR");
    /** The market's player options (Settings), changeable mid-session. */
    final AtomicReference<SettingsService.GameFeatures> features = new AtomicReference<>(SettingsService.GameFeatures.DEFAULT);
    final PlinkoProperties properties;
    /** The game's settings (a mock answering from the fields above). */
    final SettingsService settings;
    final RoundSigner signer;
    final WalletSigner walletSigner;
    final WinCheck winCheck;
    final RoundRecoveryService recovery;
    final GameService game;
    final GameRepository.Player player;

    TestGame(BonusSettings bonuses) {
        this(bonuses, TestFixtures.properties());
    }

    TestGame(BonusSettings bonuses, PlinkoProperties properties) {
        this.properties = properties;
        player = repository.addPlayer("pam-7", "EUR", new BigDecimal("100.00"));
        wallet.open("pam-7", new BigDecimal("100.00"));
        repository.addSession(new GameRepository.Session("s", player.id(), "tok", Instant.now().plusSeconds(3600), "bombs-away"));

        settings = mock(SettingsService.class);
        when(settings.limits()).thenReturn(new SettingsService.GameLimits(new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("1"), 100));
        when(settings.currency()).thenAnswer(i -> currency.get());
        when(settings.gameEnabled()).thenAnswer(i -> enabled.get());
        when(settings.features()).thenAnswer(i -> features.get());
        OddsService odds = TestOdds.with(bonuses, properties);
        signer = new RoundSigner(properties);
        walletSigner = new WalletSigner(new Integrity(properties));
        winCheck = new WinCheck(signer, walletSigner, odds, properties);
        recovery = new RoundRecoveryService(repository, wallet, signer, walletSigner, audit, winCheck, properties);
        BonusService bonus = new BonusService(null, tools.jackson.databind.json.JsonMapper.builder().build()) {
            @Override
            public BonusSettings current() {
                return bonuses;
            }
        };
        com.plinko.TestFixtures.snapshotFromStubs(settings, () -> "test-bonus");    // what TestOdds holds as the stored bonus
        game = new GameService(repository, wallet, odds, settings, signer, walletSigner, properties, mock(ThemeService.class), bonus,
                winCheck, recovery, repository.transactions(), false);
    }

    GameService.PlayResponse play(String bet, int balls) {
        return game.play("s", new GameService.PlayRequest(8, 1, new BigDecimal(bet), balls));
    }

    /** A play carrying the game's id for the tap (review 2.3). */
    GameService.PlayResponse play(String bet, int balls, String requestId) {
        return game.play("s", new GameService.PlayRequest(8, 1, new BigDecimal(bet), balls, requestId));
    }

    BigDecimal balance() {
        return wallet.balanceOf("pam-7");
    }

    /** The background recovery, run as if the interrupted rounds had been waiting long enough. */
    void recoverInTheBackground() {
        repository.age(java.time.Duration.ofHours(1));
        recovery.retryPendingNow();
    }
}
