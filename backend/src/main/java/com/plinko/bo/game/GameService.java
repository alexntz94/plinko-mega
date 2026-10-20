package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.settings.SettingsService;
import com.plinko.common.ApiException;
import com.plinko.common.Crypto;
import com.plinko.common.Currencies;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import com.plinko.repo.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Opens player sessions and plays rounds. The outcome of every ball is decided here with
 * SecureRandom and the configured odds; the client only chooses rows, risk, bet and ball count.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /** demo: the public demo server (fake credits); the game says so on screen. */
    public record SessionResponse(String sessionId, PlayerInfo player, String currency, String currencySymbol,
                                  BigDecimal balance, GameConfig config, String theme, MeterView meter, boolean demo) {
    }

    /**
     * The player's bonus meter: charges so far of how many, and per board ("rows:risk") the prize positions that
     * charge it.
     */
    public record MeterView(boolean enabled, int charges, int of, Map<String, List<Integer>> positions) {
    }

    /** round: the round that holds this state, which the next meter round continues from (null: none yet). */
    private record MeterState(int charges, BigDecimal pot, String round) {
    }

    public record PlayerInfo(String username) {
    }

    /**
     * holdToDrop: the player may hold the drop button to keep playing; sessionClock, realityCheckMinutes, quietSmallWins and
     * minRoundSeconds: the market's responsible gambling options (review 7.4). All switched in the admin, Settings.
     */
    public record GameConfig(BigDecimal minBet, BigDecimal maxBet, BigDecimal defaultBet, int maxBalls, List<RowConfig> rows,
                             boolean holdToDrop, boolean sessionClock, int realityCheckMinutes, boolean quietSmallWins,
                             BigDecimal minRoundSeconds) {
    }

    /** prizes[prizeIndex] = multipliers for [low, mid, high] risk. */
    public record RowConfig(int rows, List<List<BigDecimal>> prizes) {
    }

    /** requestId: the game's id for this tap (optional, a UUID): a repeat returns the round already played (review 2.3). */
    public record PlayRequest(Integer rows, Integer risk, BigDecimal bet, Integer balls, String requestId) {
        public PlayRequest(Integer rows, Integer risk, BigDecimal bet, Integer balls) {
            this(rows, risk, bet, balls, null);
        }
    }

    private static final java.util.regex.Pattern REQUEST_ID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * multipliers are what each ball paid (prize x golden boost x hot boost); boosts says which balls were golden
     * (1 = normal ball); hotSlot is the slot lit up for this round (null when none) and hotBoost what it multiplies by.
     * free* describe the free balls a full bonus meter paid in this round (dropped after the paid balls; each with
     * its own stake from the pot); meter is the player's meter after the round.
     */
    public record PlayResponse(String roundId, List<Integer> slots, List<BigDecimal> multipliers, BigDecimal totalBet,
                               BigDecimal totalWin, BigDecimal balance, List<BigDecimal> boosts, Integer hotSlot, BigDecimal hotBoost,
                               List<Integer> freeSlots, List<BigDecimal> freeMultipliers, List<BigDecimal> freeBoosts,
                               List<BigDecimal> freeBets, MeterView meter, List<BigDecimal> wins, List<BigDecimal> freeWins) {
    }

    private final GameRepository repository;
    private final PamClient pam;
    private final OddsService odds;
    private final SettingsService settings;
    private final RoundSigner signer;
    private final WalletSigner walletSigner;
    private final PlinkoProperties properties;
    private final com.plinko.bo.settings.ThemeService themes;
    private final BonusService bonus;
    private final WinCheck winCheck;
    private final RoundRecoveryService recovery;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final boolean demo;
    /** Where a round's randomness comes from: the odds service draws slots, the bonus service hot slots and golden balls. */
    private final RoundDraw.Dice dice = new RoundDraw.Dice() {
        @Override
        public int hotSlot(BonusSettings settings, int rows) {
            return bonus.rollHot(settings, rows);
        }

        @Override
        public int slot(OddsTable table) {
            return odds.pickSlot(table);
        }

        @Override
        public BigDecimal golden(BonusSettings settings) {
            return bonus.rollGolden(settings);
        }
    };

    public GameService(GameRepository repository, PamClient pam, OddsService odds, SettingsService settings,
                       RoundSigner signer, WalletSigner walletSigner, PlinkoProperties properties,
                       com.plinko.bo.settings.ThemeService themes, BonusService bonus, WinCheck winCheck, RoundRecoveryService recovery,
                       org.springframework.transaction.PlatformTransactionManager transactions,
                       @org.springframework.beans.factory.annotation.Value("${plinko.demo.enabled:false}") boolean demo) {
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.winCheck = winCheck;
        this.recovery = recovery;
        this.demo = demo;
        this.themes = themes;
        this.bonus = bonus;
        this.repository = repository;
        this.pam = pam;
        this.odds = odds;
        this.settings = settings;
        this.signer = signer;
        this.walletSigner = walletSigner;
        this.properties = properties;
    }

    public SessionResponse openSession(String token, String requestedTheme) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid token");
        }
        requireGameEnabled();

        PamClient.PamPlayer pamPlayer;
        try {
            pamPlayer = pam.authenticate(token);
        } catch (PamException e) {
            if (!e.outcomeUnknown()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid token");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_UNAVAILABLE", "Wallet unavailable");
        }
        if (!Currencies.isValid(pamPlayer.currency())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WALLET_ERROR", "Unsupported currency");
        }
        if (pamPlayer.balance() == null || pamPlayer.balance().signum() < 0) {
            // a wallet answer the game can't trust: refused before any money moves
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WALLET_ERROR", "The wallet reported an invalid balance");
        }
        requirePlatformCurrency(pamPlayer.currency());

        GameRepository.Player player = repository.upsertPlayer(pamPlayer.playerId(), pamPlayer.username(), pamPlayer.currency(), pamPlayer.balance());
        if (player.blocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAYER_BLOCKED", "Player blocked");
        }
        // A launch URL opens one session only, so a leaked link cannot be replayed.
        if (!repository.claimLaunchToken(Crypto.sha256Hex(token))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Launch link already used");
        }

        String sessionId = Crypto.randomToken(32);
        String theme = themes.resolve(requestedTheme);
        repository.insertSession(new GameRepository.Session(sessionId, player.id(), token, sessionExpiry(), theme));
        return new SessionResponse(sessionId, new PlayerInfo(pamPlayer.username()), pamPlayer.currency(),
                Currencies.symbol(pamPlayer.currency()), pamPlayer.balance(), config(), theme, meterView(player.id(), bonus.current()), demo);
    }

    /**
     * Re-opens an existing, unexpired session after a page reload (the launch token is single-use).
     * The balance is read fresh from the PAM.
     */
    public SessionResponse resumeSession(String sessionId) {
        GameRepository.Session session = requireSession(sessionId);
        GameRepository.Player player = repository.findPlayer(session.playerId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired"));
        if (player.blocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAYER_BLOCKED", "Player blocked");
        }
        requireGameEnabled();
        requirePlatformCurrency(player.currency());
        BigDecimal balance;
        try {
            balance = pam.balance(player.externalId(), session.pamToken());
        } catch (PamException e) {
            if (!e.outcomeUnknown()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_UNAVAILABLE", "Wallet unavailable");
        }
        repository.updatePlayerBalance(player.id(), balance);
        repository.extendSession(session.id(), sessionExpiry());
        // keeps the session's theme unless it was switched off since
        return new SessionResponse(session.id(), new PlayerInfo(player.username()), player.currency(),
                Currencies.symbol(player.currency()), balance, config(), themes.resolve(session.theme()), meterView(player.id(), bonus.current()), demo);
    }

    public GameConfig config() {
        SettingsService.Snapshot now = settings.snapshot();
        SettingsService.GameLimits limits = now.limits();
        List<RowConfig> rows = new ArrayList<>();
        for (int count = PlinkoBoard.MIN_ROWS; count <= PlinkoBoard.MAX_ROWS; count++) {
            List<List<BigDecimal>> prizes = new ArrayList<>();
            for (int prize = 0; prize < PlinkoBoard.prizeCount(count); prize++) {
                List<BigDecimal> perRisk = new ArrayList<>();
                for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                    perRisk.add(odds.get(count, risk).prizes().get(prize).multiplier().stripTrailingZeros());
                }
                prizes.add(perRisk);
            }
            rows.add(new RowConfig(count, prizes));
        }
        SettingsService.GameFeatures features = now.features();
        return new GameConfig(limits.minBet(), limits.maxBet(), limits.defaultBet(), limits.maxBalls(), rows,
                features.holdToDrop(), features.sessionClock(), features.realityCheckMinutes(), features.quietSmallWins(),
                features.minRoundSeconds());
    }

    /** Review 7.4: a round is refused while a required reality check is due (RealityChecks). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RealityChecks realityChecks;

    public PlayResponse play(String sessionId, PlayRequest request) {
        GameRepository.Session session = requireSession(sessionId);
        if (realityChecks != null) realityChecks.requireShown(session);
        GameRepository.Player player = repository.findPlayer(session.playerId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired"));
        if (player.blocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAYER_BLOCKED", "Player blocked");
        }
        // N14.10: the settings are read once for the whole round, and every check of it uses that one read
        SettingsService.Snapshot now = settings.snapshot();
        requireGameEnabled(now);
        requirePlatformCurrency(now, player.currency());
        validate(request, now.limits());
        if (request.requestId() != null) {
            Optional<GameRepository.Round> earlier = repository.roundByRequest(player.id(), request.requestId());
            if (earlier.isPresent()) {
                return repeated(earlier.get(), request, player);
            }
        }

        requireRoundSpacing(player, now.features());    // early, before any work; checked again under the round lock

        // one round at a time per player, held in the database so it covers every backend instance (claimed with
        // the round's first writes, released with its last one)
        RoundClaim claim = new RoundClaim(player.id(), UUID.randomUUID().toString());
        try {
            return playRound(session, player, request, claim, now);
        } finally {
            if (claim.held) {
                repository.releaseRound(player.id(), claim.id);
            }
            org.slf4j.MDC.remove("roundId");
        }
    }

    /** The player's round lock for one round; held from the round's first transaction until its last one releases it. */
    private static final class RoundClaim {
        final long playerId;
        final String id;
        boolean held;
        /** The round that moved the player's bonus meter, pointed at when the round ends. */
        String meterRound;
        /** The round as it is counted in the daily totals, added in the round's last transaction (N4 #2). */
        GameRepository.Round counted;

        RoundClaim(long playerId, String id) {
            this.playerId = playerId;
            this.id = id;
        }
    }

    /**
     * How long a claim lasts if its instance dies mid-round. A round makes at most a few PAM calls, each cut off
     * after the PAM timeout, so this is far longer than any live round.
     */
    private long roundClaimSeconds() {
        return roundClaimSeconds(properties);
    }

    static long roundClaimSeconds(PlinkoProperties properties) {
        return Math.max(300, properties.pam().timeoutMs() * 10L / 1000);
    }

    /**
     * The round's database writes come in three transactions, split at the wallet calls so a crash leaves the round in
     * one of the states the recovery knows (review 3.1): (a) before the bet, (b) between the bet and the payment,
     * (c) after the payment.
     */
    /** The odds and limits a round is played on, checked before anything is written. */
    private record Setup(OddsTable table, Long oddsRef, BonusSettings bonus, BigDecimal bet, BigDecimal totalBet, BigDecimal maxWin) {
    }

    /**
     * One round, step by step (review 7.8): {@link #prepare} the odds and limits, {@link #open} the round, {@link #takeBet},
     * {@link RoundDraw#draw decide the balls} (pure), {@link #record} the signed outcome, and {@link #pay}.
     */
    private PlayResponse playRound(GameRepository.Session session, GameRepository.Player player, PlayRequest request, RoundClaim claim,
                                   SettingsService.Snapshot now) {
        Setup setup = prepare(request, now);
        String roundId = UUID.randomUUID().toString();
        org.slf4j.MDC.put("roundId", roundId);      // on every log line of this round (review 7.10)
        String debitId = UUID.randomUUID().toString();
        open(session, player, request, claim, setup, roundId, debitId, now.features());
        PamClient.PamResult debit = takeBet(session, player, roundId, debitId, setup.totalBet());

        // decide every ball, after the bet is taken: where it lands, and whether it is golden (independent draws)
        BonusSettings.Meter meterSettings = setup.bonus().meterOn() ? setup.bonus().meter() : null;
        RoundDraw.MeterBefore meterBefore = null;
        if (meterSettings != null) {
            MeterState state = meterState(player.id());
            meterBefore = new RoundDraw.MeterBefore(state.charges(), state.pot(), state.round() != null ? state.round() : "start-" + player.id());
        }
        RoundDraw.Result drawn = RoundDraw.draw(setup.table(), setup.bonus(), setup.bet(), request.balls(), meterBefore, setup.maxWin(), dice);

        String signature = signer.sign(roundId, player.id(), request.rows(), request.risk(), setup.bet(), request.balls(), setup.totalBet(),
                drawn.totalWin(), player.currency(), drawn.slotText(), drawn.multiplierText(), drawn.bonusText(), setup.oddsRef(), setup.maxWin());
        String creditId = record(session, player, claim, roundId, debitId, debit, drawn, signature);
        BigDecimal balance = pay(session, player, claim, roundId, creditId, debit.balance().add(drawn.totalWin()));

        MeterView meterAfter = meterSettings == null ? new MeterView(false, 0, 0, Map.of())
                : new MeterView(true, drawn.chargesAfter(), meterSettings.charges(), BonusService.meterPositions(setup.bonus()));
        return new PlayResponse(roundId, drawn.slots(), drawn.multipliers(), setup.totalBet(), drawn.totalWin(), balance, drawn.boosts(),
                drawn.hotSlot(), drawn.hotBoost() == null ? null : drawn.hotBoost().stripTrailingZeros(),
                drawn.freeSlots(), drawn.freeMultipliers(), drawn.freeBoosts(), drawn.freeBets(), meterAfter, drawn.wins(), drawn.freeWins());
    }

    /** The odds table and bonuses in force, the round's cost, and the hard win limit (a bet that could exceed it is refused). */
    private Setup prepare(PlayRequest request, SettingsService.Snapshot now) {
        OddsService.Snapshot current = odds.forRound(now.version(), bonus.stored(now));
        OddsTable table = current.get(request.rows(), request.risk());
        // the odds version this round is played on, recorded with it so its win can be checked against it later
        Long oddsRef = current.ref(request.rows(), request.risk());
        if (table == null || oddsRef == null || !odds.healthy()) {
            // the reload for a new bonus found a table that is not valid with it
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_DISABLED", "The game is temporarily unavailable");
        }
        BigDecimal bet = request.bet().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalBet = bet.multiply(BigDecimal.valueOf(request.balls()));
        // the hard limit in force now: recorded with the round, so a later change never blocks or raises this round's win
        BigDecimal maxWin = properties.limits().maxWinPerRound().setScale(2, RoundingMode.DOWN);
        if (totalBet.multiply(table.maxMultiplier()).compareTo(maxWin) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BET_TOO_HIGH", "Bet exceeds the maximum win per round");
        }
        return new Setup(table, oddsRef, current.bonus(), bet, totalBet, maxWin);
    }

    /** The player's round lock, the session kept alive, the round and its bet: one transaction. */
    private void open(GameRepository.Session session, GameRepository.Player player, PlayRequest request, RoundClaim claim, Setup setup,
                      String roundId, String debitId, SettingsService.GameFeatures features) {
        try {
            timed("open", () -> transaction.executeWithoutResult(status -> {
                if (!repository.claimRound(player.id(), claim.id, roundClaimSeconds())) {
                    throw new ApiException(HttpStatus.CONFLICT, "ROUND_IN_PROGRESS", "A round is already in progress");
                }
                // N14.8: checked again holding the round lock, so parallel requests can't all pass it (the claim is rolled back)
                requireRoundSpacing(player, features);
                repository.extendSession(session.id(), sessionExpiry());
                repository.insertRound(new GameRepository.Round(roundId, player.id(), session.id(), request.rows(), request.risk(), setup.bet(),
                        request.balls(), setup.totalBet(), null, player.currency(), RoundStatus.PENDING, null, null, null, null, null, null, null,
                        setup.oddsRef(), setup.maxWin()), request.requestId());
                repository.insertTransaction(new GameRepository.Transaction(debitId, roundId, player.id(), "DEBIT", setup.totalBet(), "PENDING",
                        null, null, null, 0, null, null, walletSigner.signDebit(debitId, roundId, player.externalId(), setup.totalBet(),
                        player.currency())));
            }));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // the same request arrived twice at the same moment: the other one plays it (nothing was written here)
            throw new ApiException(HttpStatus.CONFLICT, "ROUND_IN_PROGRESS", "A round is already in progress");
        }
        claim.held = true;
    }

    /** Takes the bet from the wallet; a refusal or an unknown outcome ends the round (the bet is refunded if it may have been taken). */
    private PamClient.PamResult takeBet(GameRepository.Session session, GameRepository.Player player, String roundId, String debitId,
                                        BigDecimal totalBet) {
        try {
            return pam.debit(new PamClient.WalletRequest(player.externalId(), session.pamToken(), debitId, roundId, totalBet, player.currency()));
        } catch (PamException e) {
            repository.updateTransaction(debitId, "FAILED", null, e.stored(), e.refusal());
            throw debitFailed(session, player, roundId, debitId, e);
        }
    }

    /**
     * The bet taken, the decided and signed outcome, and the credit to send: one transaction. The credit's id is derived
     * from the round and the credit is signed, so it can only ever be paid once and only to this player.
     */
    private String record(GameRepository.Session session, GameRepository.Player player, RoundClaim claim, String roundId, String debitId,
                          PamClient.PamResult debit, RoundDraw.Result drawn, String signature) {
        String creditId = walletSigner.creditId(roundId);
        BigDecimal win = drawn.totalWin();
        try {
            timed("record", () -> transaction.executeWithoutResult(status -> {
                repository.updateTransaction(debitId, "OK", debit.reference(), null);
                repository.saveOutcome(roundId, drawn.slotText(), drawn.multiplierText(), drawn.bonusText(), drawn.meterPrev(), win, signature,
                        RoundStatus.DEBITED);
                if (drawn.meterPrev() != null) {
                    claim.meterRound = roundId;    // the player's meter is now this round (review 3.4), pointed at in (c) (3.1.3)
                }
                repository.insertTransaction(new GameRepository.Transaction(creditId, roundId, player.id(), "CREDIT", win, "PENDING",
                        null, null, null, 0, null, null, walletSigner.signCredit(roundId, player.externalId(), win, player.currency())));
            }));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // another round of this player continued from the same meter state first: this one never happened
            log.warn("Round {} of player {} raced another round on the bonus meter; bet refunded", roundId, player.id());
            repository.updateTransaction(debitId, "OK", debit.reference(), null);
            refundBet(session, player, roundId, debitId, "Raced another round on the bonus meter");
            throw new ApiException(HttpStatus.CONFLICT, "ROUND_IN_PROGRESS", "A round is already in progress");
        }
        return creditId;
    }

    /**
     * Pays the win (a zero credit closes the round on the PAM side). What is paid is what the database holds, checked once
     * more: read back, the win worked out again from the stored balls. Returns the player's balance after it.
     */
    private BigDecimal pay(GameRepository.Session session, GameRepository.Player player, RoundClaim claim, String roundId, String creditId,
                           BigDecimal expectedBalance) {
        GameRepository.Round saved = repository.findRound(roundId).orElseThrow();
        claim.counted = saved;
        List<GameRepository.Transaction> recorded = repository.transactionsOfRound(roundId);
        String problem = winCheck.problem(saved, recorded, player);
        if (WinCheck.oddsUnreadable(problem)) {
            // N14.5: a setup problem, not this round's: the win waits for the background retry, the round is not blocked
            recovery.oddsUnreadable(saved, problem);
            settle(player, claim, creditId, "FAILED", null, PamException.notSent("ODDS_UNREADABLE: " + problem), true, RoundStatus.CREDIT_PENDING,
                    "Credit waiting: " + problem, roundId, expectedBalance);
            return expectedBalance;
        }
        if (problem != null) {
            recovery.block(saved, "credit blocked: " + problem);
            // never sent (it was written in this request): so the round can still be paid or refunded by a settlement (N2)
            repository.updateTransaction(creditId, "FAILED", null, PamException.notSent("blocked before sending: " + problem), true);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUND_UNDER_REVIEW", "This round is being checked");
        }
        GameRepository.Transaction toPay = recorded.stream().filter(tx -> "CREDIT".equals(tx.type())).findFirst().orElseThrow();
        repository.markSending(toPay.id());    // R4: from here its outcome is unknown until the answer is stored
        try {
            PamClient.PamResult credit = pam.credit(new PamClient.WalletRequest(player.externalId(), session.pamToken(), toPay.id(),
                    saved.id(), toPay.amount(), saved.currency()));
            settle(player, claim, creditId, "OK", credit.reference(), null, false, RoundStatus.COMPLETED, null, roundId, credit.balance());
            return credit.balance();
        } catch (PamException e) {
            log.warn("Credit failed for round {}: {}", roundId, e.getMessage());
            settle(player, claim, creditId, "FAILED", null, e.stored(), e.refusal(), RoundStatus.CREDIT_PENDING,
                    "Credit failed: " + e.getMessage(), roundId, expectedBalance);
            return expectedBalance;
        }
    }

    /** Review 7.5: one round of the player's history, with everything needed to replay it. */
    public record HistoryEntry(String roundId, String time, String status, int rows, int risk, BigDecimal bet, int balls, List<BigDecimal> prizes,
                               PlayResponse round) {
    }

    public record HistoryPage(List<HistoryEntry> rounds, String next) {
    }

    static final java.time.Duration HISTORY_DAYS = java.time.Duration.ofDays(90);
    /** The status the history shows for a round whose stored outcome fails its check. */
    static final String UNDER_REVIEW = "UNDER_REVIEW";

    /**
     * The player's own rounds of the last 90 days, newest first, 20 a page (cursor: where the last page ended). Each
     * comes with its stored balls and the prizes of the odds it was played on, so the game can replay it exactly.
     */
    public HistoryPage history(String sessionId, String cursor, Integer limit) {
        GameRepository.Session session = requireSession(sessionId);
        int size = limit == null ? 20 : Math.max(1, Math.min(50, limit));
        Instant before = Instant.now().plusSeconds(60);
        String beforeId = "￿";
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", 2);
            try {
                before = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                beforeId = parts[1];
            } catch (RuntimeException e) {
                throw invalid("cursor is not one this server gave");
            }
            // any id this server can hand out (it is a bound parameter, never part of the SQL)
            if (!beforeId.matches("[0-9A-Za-z-]{1,64}")) {
                throw invalid("cursor is not one this server gave");
            }
        }
        List<GameRepository.Round> rounds = repository.history(session.playerId(), Instant.now().minus(HISTORY_DAYS), before, beforeId, size + 1);
        boolean more = rounds.size() > size;
        List<HistoryEntry> entries = new ArrayList<>();
        for (GameRepository.Round round : rounds.subList(0, Math.min(size, rounds.size()))) {
            // N14.12: a round whose stored outcome fails its signature or win check (edited in the database) is shown as
            // under review, without its balls or win
            if (winCheck.outcomeProblem(round) != null) {
                entries.add(new HistoryEntry(round.id(), round.createdAt().toString(), UNDER_REVIEW, round.rows(), round.risk(), round.bet(),
                        round.balls(), null, null));
                continue;
            }
            List<BigDecimal> prizes = round.oddsRef() == null ? null
                    : odds.playedOn(round.oddsRef(), round.rows(), round.risk()).orElse(null);
            PlayResponse replay;
            try {
                replay = rebuilt(round, null, null);
            } catch (RuntimeException e) {
                replay = null;     // a malformed stored outcome: listed, not replayed
            }
            entries.add(new HistoryEntry(round.id(), round.createdAt().toString(), round.status(), round.rows(), round.risk(), round.bet(), round.balls(),
                    prizes, replay));
        }
        GameRepository.Round last = more ? rounds.get(size - 1) : null;
        return new HistoryPage(entries, last == null ? null : last.createdAt().toEpochMilli() + ":" + last.id());
    }

    /**
     * A repeat of a request already played (review 2.3): the same round again, rebuilt from what was stored, without a
     * new bet. The same id with a different bet is refused; a round still being played or not played says so.
     */
    private PlayResponse repeated(GameRepository.Round round, PlayRequest request, GameRepository.Player player) {
        if (round.rows() != request.rows() || round.risk() != request.risk() || round.balls() != request.balls()
                || round.bet().compareTo(request.bet()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_MISMATCH", "This request id was already used for a different bet");
        }
        switch (round.status()) {
            case RoundStatus.COMPLETED, RoundStatus.CREDIT_PENDING -> {
            }
            // N14.6: the first copy of this request is still being played (a slow wallet): the client asks again shortly
            case RoundStatus.PENDING, RoundStatus.DEBITED ->
                    throw new ApiException(HttpStatus.CONFLICT, "ROUND_STILL_RUNNING", "This round is still being played; ask again shortly");
            case RoundStatus.TAMPERED ->
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUND_UNDER_REVIEW", "This round is being checked");
            default -> throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_PLAYED", "This round was not played; no bet was kept");
        }
        log.info("Request {} repeated: round {} returned again, no new bet", request.requestId(), round.id());
        BigDecimal balance = repository.findPlayer(player.id()).map(GameRepository.Player::lastBalance).orElse(BigDecimal.ZERO);
        return rebuilt(round, balance, meterView(player.id(), bonus.current()));
    }

    /** A stored round as the game shows it: every ball, its boosts and what it paid, from what was stored and signed. */
    private PlayResponse rebuilt(GameRepository.Round round, BigDecimal balance, MeterView meter) {
        RoundWin.Outcome outcome = RoundWin.parse(round.slots(), round.multipliers(), round.bonus());
        List<BigDecimal> boosts = new ArrayList<>();
        List<BigDecimal> wins = new ArrayList<>();
        for (int ball = 0; ball < outcome.slots().size(); ball++) {
            boosts.add(outcome.golden().getOrDefault(ball, BigDecimal.ONE).stripTrailingZeros());
            wins.add(RoundWin.ball(round.bet(), outcome.multipliers().get(ball)));
        }
        List<Integer> freeSlots = outcome.free().stream().map(RoundWin.FreeBall::slot).toList();
        List<BigDecimal> freeMultipliers = outcome.free().stream().map(f -> f.multiplier().stripTrailingZeros()).toList();
        List<BigDecimal> freeBoosts = outcome.free().stream().map(f -> f.boost().stripTrailingZeros()).toList();
        List<BigDecimal> freeBets = outcome.free().stream().map(RoundWin.FreeBall::stake).toList();
        List<BigDecimal> freeWins = outcome.free().stream().map(f -> RoundWin.ball(f.stake(), f.multiplier())).toList();
        return new PlayResponse(round.id(), outcome.slots(), outcome.multipliers().stream().map(BigDecimal::stripTrailingZeros).toList(),
                round.totalBet(), round.totalWin(), balance, boosts, outcome.hotSlot(),
                outcome.hotBoost() == null ? null : outcome.hotBoost().stripTrailingZeros(), freeSlots, freeMultipliers, freeBoosts, freeBets,
                meter, wins, freeWins);
    }

    /** (c) the credit's answer, the round's status, the player's balance and the round lock released: one transaction. */
    private void settle(GameRepository.Player player, RoundClaim claim, String creditId, String creditStatus, String reference,
                        String error, boolean refused, String roundStatus, String roundError, String roundId, BigDecimal balance) {
        timed("settle", () -> transaction.executeWithoutResult(status -> {
            repository.updateTransaction(creditId, creditStatus, reference, error, refused);
            repository.updateRoundStatus(roundId, roundStatus, roundError);
            repository.settlePlayer(player.id(), balance, claim.id, claim.meterRound);
            // N4 #2: today's totals are updated incrementally, in this transaction (an insert-only row, folded every minute)
            if (stats != null && claim.counted != null) {
                stats.addDelta(claim.counted, 1);
            }
        }));
        claim.held = false;
    }

    /** The daily totals' increments (optional: absent in unit tests). */
    private com.plinko.repo.StatsRepository stats;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStats(com.plinko.repo.StatsRepository stats) {
        this.stats = stats;
    }

    /** Review 3.10e: database time per round, per step (open, record, settle), as plinko.round.db (optional: absent in unit tests). */
    private io.micrometer.core.instrument.MeterRegistry meters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    private void timed(String step, Runnable work) {
        if (meters == null) {
            work.run();
            return;
        }
        io.micrometer.core.instrument.Timer.builder("plinko.round.db").description("Database time of one step of a round")
                .tag("step", step).publishPercentiles(0.5, 0.95, 0.99).register(meters).record(work);
    }

    /**
     * The player's meter, read from their latest round that recorded one. Rounds are signed and the database keeps a
     * decided round from changing, so the meter can't be filled or rolled back by hand; a latest meter round whose
     * signature fails is treated as an empty meter (and the round is flagged by the integrity scan).
     */
    private MeterState meterState(long playerId) {
        // review 3.4: from the player's pointer (or the start of their chain), following the chain to its end by its
        // unique index; a round saved by a version that did not move the pointer (during a rolling release) is found too
        String pointer = repository.meterRound(playerId).orElse(null);
        Optional<GameRepository.Round> latest = pointer == null ? repository.roundContinuing("start-" + playerId) : repository.findRound(pointer);
        for (int step = 0; latest.isPresent() && step < 10_000; step++) {
            Optional<GameRepository.Round> next = repository.roundContinuing(latest.get().id());
            if (next.isEmpty()) {
                break;
            }
            latest = next;
        }
        if (latest.isEmpty()) {
            return new MeterState(0, BigDecimal.ZERO, null);
        }
        GameRepository.Round round = latest.get();
        if (round.playerId() != playerId || !signer.verify(round) || RoundStatus.TAMPERED.equals(round.status())) {
            log.error("Bonus meter of player {}: latest meter round {} fails its signature; meter counted as empty", playerId, round.id());
            return new MeterState(0, BigDecimal.ZERO, round.id());
        }
        try {
            RoundWin.Meter meter = RoundWin.parse(round.slots(), round.multipliers(), round.bonus()).meter();
            if (meter != null) {
                return new MeterState(meter.charges(), meter.pot(), round.id());
            }
        } catch (RuntimeException e) {
            log.error("Bonus meter of player {}: round {} has an unreadable outcome; meter counted as empty", playerId, round.id());
        }
        return new MeterState(0, BigDecimal.ZERO, round.id());
    }

    private MeterView meterView(long playerId, BonusSettings settings) {
        if (!settings.meterOn()) {
            return new MeterView(false, 0, 0, Map.of());
        }
        return new MeterView(true, meterState(playerId).charges(), settings.meter().charges(), BonusService.meterPositions(settings));
    }

    /** Review 7.4: where the market sets a minimum time per round, a round that comes sooner after the last one is refused. */
    private void requireRoundSpacing(GameRepository.Player player, SettingsService.GameFeatures features) {
        BigDecimal min = features.minRoundSeconds();
        if (min.signum() > 0) {
            Optional<Instant> last = repository.lastRoundAt(player.id());
            long minMillis = min.movePointRight(3).longValue();
            if (last.isPresent() && last.get().plusMillis(minMillis).isAfter(Instant.now())) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_FAST", "Rounds are at least " + min.toPlainString() + " seconds apart");
            }
        }
    }

    private ApiException debitFailed(GameRepository.Session session, GameRepository.Player player, String roundId, String debitId, PamException e) {
        // R10: only a listed refusal proves the bet was not taken; any other answer may mean the wallet holds the debit
        if (e.refusal()) {
            repository.updateRoundStatus(roundId, RoundStatus.REJECTED, e.code());
            return switch (e.code()) {
                case PamException.INSUFFICIENT_FUNDS -> new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_FUNDS", "Insufficient funds");
                case PamException.INVALID_TOKEN -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
                // review 7.4: the casino's own limits and exclusions, shown to the player as such
                case PamException.LIMIT_REACHED -> new ApiException(HttpStatus.FORBIDDEN, "LIMIT_REACHED", "A limit set on the account is reached");
                case PamException.PLAYER_EXCLUDED, PamException.PLAYER_BLOCKED ->
                        new ApiException(HttpStatus.FORBIDDEN, "PLAYER_BLOCKED", "Player blocked by the casino");
                default -> new ApiException(HttpStatus.BAD_GATEWAY, "WALLET_ERROR", "Wallet refused the bet");
            };
        }
        // We cannot know whether the PAM took the money (no answer, or an answer that isn't a refusal): roll it back.
        refundBet(session, player, roundId, debitId, "Debit outcome unknown");
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_UNAVAILABLE", "Wallet unavailable");
    }

    /** Gives the bet back with the round's own signed rollback; a failed rollback is retried in the background. */
    private void refundBet(GameRepository.Session session, GameRepository.Player player, String roundId, String debitId, String reason) {
        String rollbackId = walletSigner.rollbackId(roundId);
        repository.insertTransaction(new GameRepository.Transaction(rollbackId, roundId, player.id(), "ROLLBACK", BigDecimal.ZERO,
                "PENDING", debitId, null, null, 0, null, null, walletSigner.signRollback(roundId, player.externalId(), debitId)));
        repository.markSending(rollbackId);
        try {
            PamClient.PamResult result = pam.rollback(new PamClient.RollbackRequest(player.externalId(), session.pamToken(), rollbackId, debitId, roundId));
            repository.updateTransaction(rollbackId, "OK", result.reference(), null);
            repository.updateRoundStatus(roundId, RoundStatus.CANCELLED, reason + ", rolled back");
        } catch (PamException rollbackError) {
            repository.updateTransaction(rollbackId, "FAILED", null, rollbackError.stored(), rollbackError.refusal());
            repository.updateRoundStatus(roundId, RoundStatus.ROLLBACK_PENDING, reason + ", rollback failed");
        }
    }

    private void validate(PlayRequest request, SettingsService.GameLimits limits) {
        if (request == null || request.rows() == null || request.risk() == null || request.bet() == null || request.balls() == null) {
            throw invalid("rows, risk, bet and balls are required");
        }
        if (request.rows() < PlinkoBoard.MIN_ROWS || request.rows() > PlinkoBoard.MAX_ROWS) {
            throw invalid("rows must be between " + PlinkoBoard.MIN_ROWS + " and " + PlinkoBoard.MAX_ROWS);
        }
        if (request.risk() < 0 || request.risk() >= PlinkoBoard.RISK_LEVELS) {
            throw invalid("risk must be 0, 1 or 2");
        }
        if (request.balls() < 1 || request.balls() > limits.maxBalls()) {
            throw invalid("balls must be between 1 and " + limits.maxBalls());
        }
        if (request.bet().signum() <= 0) {
            throw invalid("bet must be above 0");
        }
        if (request.bet().stripTrailingZeros().scale() > 2) {
            throw invalid("bet can have at most 2 decimals");
        }
        if (request.requestId() != null && !REQUEST_ID.matcher(request.requestId()).matches()) {
            throw invalid("requestId must be a UUID");
        }
        if (request.bet().scale() < 0) {
            // written with an exponent (1e2): amounts are plain decimals only, as in the PAM contract
            throw invalid("bet must be a plain decimal number");
        }
        if (request.bet().compareTo(limits.minBet()) < 0 || request.bet().compareTo(limits.maxBet()) > 0) {
            throw invalid("bet must be between " + limits.minBet() + " and " + limits.maxBet());
        }
    }

    private GameRepository.Session requireSession(String sessionId) {
        if (sessionId == null || sessionId.length() > 64) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
        }
        GameRepository.Session session = repository.findSession(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired"));
        if (session.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired");
        }
        return session;
    }

    /** The platform plays in one currency (Settings); a wallet in another currency is refused before any money moves. */
    private void requirePlatformCurrency(String walletCurrency) {
        requirePlatformCurrency(settings.snapshot(), walletCurrency);
    }

    private void requirePlatformCurrency(SettingsService.Snapshot now, String walletCurrency) {
        if (!now.currency().equals(walletCurrency)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENCY_NOT_SUPPORTED", "This game is not available in your account's currency");
        }
    }

    private void requireGameEnabled() {
        requireGameEnabled(settings.snapshot());
    }

    private void requireGameEnabled(SettingsService.Snapshot now) {
        // Odds that fail their signature or the hard limits stop the game entirely.
        if (!now.gameEnabled() || !odds.healthy()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_DISABLED", "The game is temporarily unavailable");
        }
    }

    private Instant sessionExpiry() {
        return Instant.now().plus(Duration.ofMinutes(properties.sessionTtlMinutes()));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
