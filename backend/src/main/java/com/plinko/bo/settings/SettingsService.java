package com.plinko.bo.settings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.plinko.common.Crypto;
import com.plinko.config.PlinkoProperties;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.SettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bet limits and the game on/off switch, stored in the {@code setting} table. Every change made through the
 * application is recorded in the integrity ledger; settings edited directly in the database stop the game, and no
 * change is saved on top of them: that would record the edit as genuine.
 * <p>
 * The game reads them from memory (with their integrity check), re-reading them when the settings version changed
 * (every change through the application raises it: on every instance at once) and every minute in any case (so a
 * direct edit of the database stops the game within a minute). Admin pages and changes always read the database.
 */
@Service
public class SettingsService {

    public record GameLimits(BigDecimal minBet, BigDecimal maxBet, BigDecimal defaultBet, int maxBalls) {
    }

    private static final BigDecimal MIN_BET_FLOOR = new BigDecimal("0.01");
    private static final GameLimits DEFAULT_LIMITS = new GameLimits(new BigDecimal("0.10"), new BigDecimal("100.00"), new BigDecimal("1.00"), 100);

    private static final String LEDGER_ENTITY = "settings";

    /** One setting that differs from the last recorded version; {@code null} when the row is missing. */
    public record Difference(String name, String stored, String recorded) {
    }

    /** Thrown when a change is attempted while the stored settings differ from the last recorded version. */
    public static class TamperedException extends IllegalStateException {
        public TamperedException() {
            super("The settings were changed outside the admin panel; restore them before changing them");
        }
    }

    private final SettingsRepository repository;
    private final PlinkoProperties.Limits hardLimits;
    private final IntegrityLedger ledger;

    /** The settings as last read, the version they were read at, and whether they passed the integrity check. */
    private record Cached(long version, Map<String, String> values, boolean trusted) {
    }

    private volatile Cached cached;
    /** N14.10: the version is read at most once a second per instance, not by every settings read of every round. */
    private static final long VERSION_EVERY_NANOS = 1_000_000_000L;
    private volatile long versionReadAt;
    private volatile long versionRead;
    /** One reload at a time: requests arriving while it runs use its result instead of reloading too. */
    private final java.util.concurrent.locks.ReentrantLock loading = new java.util.concurrent.locks.ReentrantLock();
    /** What the integrity alarm last said, so a tampered state is logged once, not every minute. */
    private volatile boolean reportedTrusted = true;

    public SettingsService(SettingsRepository repository, PlinkoProperties properties, IntegrityLedger ledger) {
        this.repository = repository;
        this.hardLimits = properties.limits();
        this.ledger = ledger;
    }

    /** False if the stored settings differ from the last version saved through the application. */
    public boolean trusted() {
        return trusted(values());
    }

    /**
     * The settings version (every change through the application raises it, odds approvals included), as read at most a
     * second ago.
     */
    public long version() {
        long now = System.nanoTime();
        if (versionReadAt == 0 || now - versionReadAt > VERSION_EVERY_NANOS) {
            versionRead = repository.version();
            versionReadAt = now;
        }
        return versionRead;
    }

    /** The settings the game uses: from memory, read again if the version moved on. */
    private Cached current() {
        if (Boolean.TRUE.equals(changedHere.get())) {
            // this transaction changed the settings: it sees its own change, read fresh and never cached (other requests
            // keep the committed settings until it commits)
            Map<String, String> values = Map.copyOf(repository.values());
            return new Cached(repository.version(), values, trusted(values));
        }
        long version = version();
        Cached now = cached;
        if (now != null && now.version() == version) {
            return now;
        }
        loading.lock();
        try {
            now = cached;
            if (now != null && now.version() == version) {
                return now;     // another request reloaded them meanwhile
            }
            // the version is read before the values: values newer than their version only cause one more re-read
            Map<String, String> values = Map.copyOf(repository.values());
            now = new Cached(version, values, trusted(values));
            cached = now;
            if (now.trusted() != reportedTrusted) {
                reportedTrusted = now.trusted();
                if (!now.trusted()) {
                    org.slf4j.LoggerFactory.getLogger(SettingsService.class).error(
                            "SETTINGS INTEGRITY FAILURE, game stopped: the settings differ from the last version saved through the admin panel");
                } else {
                    org.slf4j.LoggerFactory.getLogger(SettingsService.class).info("Settings trusted again: the game can run");
                }
            }
            return now;
        } finally {
            loading.unlock();
        }
    }

    /**
     * Announces a change made outside the settings (an approved odds table) to every instance, in the same transaction:
     * they reload at their next round instead of within a minute (N14.10).
     */
    public void announceChange() {
        repository.raiseVersion();
        forgetVersionAfterCommit();
    }

    /** This instance reads the version again right after the change commits, instead of up to a second later. */
    private void forgetVersionAfterCommit() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            if (!Boolean.TRUE.equals(changedHere.get())) {
                changedHere.set(true);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                changedHere.remove();
                                versionReadAt = 0;
                            }
                        });
            }
        } else {
            versionReadAt = 0;
        }
    }

    /** True in a transaction that changed the settings, until it ends (its own reads must see the change). */
    private final ThreadLocal<Boolean> changedHere = new ThreadLocal<>();

    /** Re-reads the settings every minute, whatever the version says: a direct database edit stops the game within a minute. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        cached = null;
        versionReadAt = 0;
    }

    /** Records the settings as they are now as the first version; does nothing once a version exists. */
    @Transactional
    public void recordInitial() {
        Map<String, String> values = lockedValues();
        if (!ledger.exists(LEDGER_ENTITY)) {
            record(values);
        }
    }

    /** Keeps a copy of the settings if they are trusted and none is kept yet (installs from before the copies). */
    @Transactional
    public void keepSnapshot() {
        Map<String, String> values = lockedValues();
        if (trusted(values) && lastTrusted().isEmpty()) {
            snapshot(values);
        }
    }

    /** The newest recorded fingerprint of the settings. */
    public Optional<String> recordedFingerprint() {
        return ledger.latestContent(LEDGER_ENTITY);
    }

    /** The fingerprint of the settings as they are stored now. */
    public String storedFingerprint() {
        return fingerprint(values());
    }

    /** The settings that differ from the last recorded version (empty when trusted, or when no copy is kept). */
    public List<Difference> differences() {
        Map<String, String> stored = values();
        return lastTrusted().map(recorded -> {
            TreeMap<String, Difference> out = new TreeMap<>();
            recorded.forEach((name, value) -> {
                if (!value.equals(stored.get(name))) {
                    out.put(name, new Difference(name, stored.get(name), value));
                }
            });
            stored.forEach((name, value) -> {
                if (!recorded.containsKey(name)) {
                    out.put(name, new Difference(name, value, null));
                }
            });
            return List.copyOf(out.values());
        }).orElse(List.of());
    }

    /**
     * Puts back exactly the settings recorded as {@code fingerprint}, the newest version (an approved "Restore
     * settings"): rows added in the database are removed, edited ones set back. Nothing new is recorded, so nothing
     * edited in the database can become genuine this way.
     */
    @Transactional
    public void restore(String fingerprint) {
        Map<String, String> stored = lockedValues();
        if (!recordedFingerprint().map(fingerprint::equals).orElse(false)) {
            throw new IllegalArgumentException("the settings were saved again since this restore was proposed");
        }
        Map<String, String> recorded = lastTrusted()
                .orElseThrow(() -> new IllegalArgumentException("no copy of the recorded settings is kept"));
        stored.keySet().stream().filter(name -> !recorded.containsKey(name))
                .forEach(repository::delete);
        recorded.forEach((name, value) -> {
            if (!value.equals(stored.get(name))) {
                set(name, value);
            }
        });
        if (!trusted(values())) {
            throw new IllegalArgumentException("the restored settings do not match the recorded version");
        }
        repository.raiseVersion();
        forgetVersionAfterCommit();
    }

    /** The settings as last recorded through the application, if a copy of them is kept. */
    public Optional<Map<String, String>> lastTrusted() {
        return ledger.latestContent(LEDGER_ENTITY).flatMap(fingerprint -> repository.snapshots(fingerprint).stream()
                .filter(content -> Crypto.sha256Hex(content).equals(fingerprint))
                .findFirst()
                .map(SettingsService::parse));
    }

    private boolean trusted(Map<String, String> values) {
        return ledger.isLatest(LEDGER_ENTITY, fingerprint(values));
    }

    private static String fingerprint(Map<String, String> values) {
        return Crypto.sha256Hex(canonical(values));
    }

    /** The settings as one string, every name and value prefixed with its length: {@code 9:game.bonus=4:{..};}. */
    static String canonical(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(values).forEach((name, value) -> out.append(name.length()).append(':').append(name)
                .append('=').append(value.length()).append(':').append(value).append(';'));
        return out.toString();
    }

    static Map<String, String> parse(String canonical) {
        Map<String, String> values = new TreeMap<>();
        int at = 0;
        while (at < canonical.length()) {
            int colon = canonical.indexOf(':', at);
            int nameEnd = colon + 1 + Integer.parseInt(canonical.substring(at, colon));
            String name = canonical.substring(colon + 1, nameEnd);
            at = nameEnd + 1;                                   // skip '='
            colon = canonical.indexOf(':', at);
            int valueEnd = colon + 1 + Integer.parseInt(canonical.substring(at, colon));
            values.put(name, canonical.substring(colon + 1, valueEnd));
            at = valueEnd + 1;                                  // skip ';'
        }
        return values;
    }

    private void record(Map<String, String> values) {
        ledger.record(LEDGER_ENTITY, fingerprint(values));
        snapshot(values);
    }

    private void snapshot(Map<String, String> values) {
        String content = canonical(values);
        repository.insertSnapshot(Crypto.sha256Hex(content), content);
    }

    /**
     * The stored limits, forced back inside the hard limits: even a value edited directly in the database
     * can never allow a bet of 0 or less, a bet above the hard maximum, or more balls than the hard maximum.
     */
    public GameLimits limits() {
        return limitsOf(current().values());
    }

    /**
     * N14.10: the settings as one read, for one round: its checks (switched on, currency, limits, features, bonus) all
     * see the same version, and the version is read once for them.
     */
    public record Snapshot(long version, Map<String, String> values, boolean trusted, GameLimits limits, String currency, boolean gameEnabled,
                           GameFeatures features) {
        public String raw(String name) {
            return values.get(name);
        }
    }

    public Snapshot snapshot() {
        Cached now = current();
        return new Snapshot(now.version(), now.values(), now.trusted(), limitsOf(now.values()), currencyOf(now.values()),
                now.trusted() && Boolean.parseBoolean(now.values().getOrDefault("game.enabled", "true")), featuresOf(now.values()));
    }

    private GameLimits limitsOf(Map<String, String> values) {
        BigDecimal maxBet = decimal(values, "game.maxBet", DEFAULT_LIMITS.maxBet()).min(hardLimits.maxBet()).max(MIN_BET_FLOOR);
        BigDecimal minBet = decimal(values, "game.minBet", DEFAULT_LIMITS.minBet()).max(MIN_BET_FLOOR).min(maxBet);
        BigDecimal defaultBet = decimal(values, "game.defaultBet", DEFAULT_LIMITS.defaultBet()).max(minBet).min(maxBet);
        int maxBalls;
        try {
            maxBalls = Integer.parseInt(values.getOrDefault("game.maxBalls", Integer.toString(DEFAULT_LIMITS.maxBalls())));
        } catch (NumberFormatException e) {
            maxBalls = 1;
        }
        return new GameLimits(minBet, maxBet, defaultBet, Math.max(1, Math.min(maxBalls, hardLimits.maxBalls())));
    }

    /** The one currency the platform plays in (default USD); players' PAM wallets must be in it. */
    public String currency() {
        return currencyOf(current().values());
    }

    private static String currencyOf(Map<String, String> values) {
        String code = values.get("game.currency");
        return code != null && com.plinko.common.Currencies.OFFERED.contains(code) ? code : "USD";
    }

    /** Only called when an approved change request is applied. */
    @Transactional
    public void setCurrency(String code) {
        if (!com.plinko.common.Currencies.OFFERED.contains(code)) {
            throw new IllegalArgumentException("Unsupported currency " + code);
        }
        change(Map.of("game.currency", code));
    }

    /** The game runs only if it is switched on and the settings were not changed outside the application. */
    public boolean gameEnabled() {
        Cached settings = current();
        return settings.trusted() && Boolean.parseBoolean(settings.values().getOrDefault("game.enabled", "true"));
    }

    public List<String> validate(GameLimits limits) {
        List<String> errors = new ArrayList<>();
        if (limits.minBet() == null || limits.maxBet() == null || limits.defaultBet() == null) {
            errors.add("All bet amounts are required");
            return errors;
        }
        if (limits.minBet().signum() <= 0) {
            errors.add("Minimum bet must be above 0");
        }
        for (BigDecimal amount : List.of(limits.minBet(), limits.maxBet(), limits.defaultBet())) {
            if (amount.stripTrailingZeros().scale() > 2) {
                errors.add("Bet amounts can have at most 2 decimals");
                break;
            }
        }
        if (limits.maxBet().compareTo(limits.minBet()) < 0) {
            errors.add("Maximum bet must be at least the minimum bet");
        }
        if (limits.maxBet().compareTo(hardLimits.maxBet()) > 0) {
            errors.add("Maximum bet is above the hard limit of " + hardLimits.maxBet());
        }
        if (limits.defaultBet().compareTo(limits.minBet()) < 0 || limits.defaultBet().compareTo(limits.maxBet()) > 0) {
            errors.add("Default bet must be between the minimum and maximum bet");
        }
        if (limits.maxBalls() < 1 || limits.maxBalls() > hardLimits.maxBalls()) {
            errors.add("Maximum balls must be between 1 and " + hardLimits.maxBalls());
        }
        return errors;
    }

    /** Only called when an approved change request is applied. */
    @Transactional
    public void applyLimits(GameLimits limits) {
        List<String> errors = validate(limits);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        change(Map.of("game.minBet", limits.minBet().toPlainString(), "game.maxBet", limits.maxBet().toPlainString(),
                "game.defaultBet", limits.defaultBet().toPlainString(), "game.maxBalls", Integer.toString(limits.maxBalls())));
    }

    /** Themes switched on (comma separated) and the default; recorded in the ledger like every setting. */
    @Transactional
    public void setThemes(String enabledThemes, String defaultTheme, String knownThemes) {
        change(Map.of("game.themes", enabledThemes, "game.theme.default", defaultTheme, "game.themes.known", knownThemes));
    }

    /** The paying bonuses (JSON, see BonusService). Only called when an approved change request is applied. */
    @Transactional
    public void setBonus(String bonusJson) {
        change(Map.of("game.bonus", bonusJson));
    }

    /** All stored settings as they are (used by ThemeService and BonusService). */
    public Map<String, String> rawValues() {
        return current().values();
    }

    /**
     * Player-facing options, set per market (review 7.4; which ones a market needs is for compliance, decision D11):
     * <ul>
     *   <li>holdToDrop: the player may hold the drop button to play round after round (some markets ban autoplay); on by default;</li>
     *   <li>sessionClock: the game shows how long the player has played and their net result this session;</li>
     *   <li>realityCheckMinutes: every so many minutes the game pauses with the time played and net result (0: never);</li>
     *   <li>quietSmallWins: a round that pays back less than its bet is not celebrated as a win (losses disguised as wins);</li>
     *   <li>minRoundSeconds: the shortest time between two rounds of a player, enforced by the server (0: none);</li>
     *   <li>requireRealityCheckProof: the server refuses a round once the reality check is due and the game has not reported
     *   showing it and the player continuing (where a market requires proof; off by default).</li>
     * </ul>
     */
    public record GameFeatures(boolean holdToDrop, boolean sessionClock, int realityCheckMinutes, boolean quietSmallWins,
                               BigDecimal minRoundSeconds, boolean requireRealityCheckProof) {
        public static final GameFeatures DEFAULT = new GameFeatures(true, false, 0, false, BigDecimal.ZERO, false);

        /** Without the reality check proof (off). */
        public GameFeatures(boolean holdToDrop, boolean sessionClock, int realityCheckMinutes, boolean quietSmallWins, BigDecimal minRoundSeconds) {
            this(holdToDrop, sessionClock, realityCheckMinutes, quietSmallWins, minRoundSeconds, false);
        }

        /** True if {@code next} protects players less than this in any way (only admins may do that). */
        public boolean loosenedBy(GameFeatures next) {
            return (next.holdToDrop && !holdToDrop) || (!next.sessionClock && sessionClock) || (!next.quietSmallWins && quietSmallWins)
                    || (realityCheckMinutes > 0 && (next.realityCheckMinutes == 0 || next.realityCheckMinutes > realityCheckMinutes))
                    || next.minRoundSeconds.compareTo(minRoundSeconds) < 0
                    || (!next.requireRealityCheckProof && requireRealityCheckProof);
        }

        public List<String> problems() {
            List<String> problems = new java.util.ArrayList<>();
            if (realityCheckMinutes != 0 && (realityCheckMinutes < 10 || realityCheckMinutes > 240)) {
                problems.add("Reality check: every 10 to 240 minutes, or 0 for none");
            }
            if (minRoundSeconds.signum() < 0 || minRoundSeconds.compareTo(BigDecimal.TEN) > 0 || minRoundSeconds.scale() > 1) {
                problems.add("Minimum time per round: 0 to 10 seconds, at most one decimal");
            }
            return problems;
        }
    }

    public GameFeatures features() {
        return featuresOf(current().values());
    }

    private static GameFeatures featuresOf(Map<String, String> values) {
        try {
            return new GameFeatures(Boolean.parseBoolean(values.getOrDefault("game.holdToDrop", "true")),
                    Boolean.parseBoolean(values.getOrDefault("game.sessionClock", "false")),
                    Integer.parseInt(values.getOrDefault("game.realityCheckMinutes", "0")),
                    Boolean.parseBoolean(values.getOrDefault("game.quietSmallWins", "false")),
                    new BigDecimal(values.getOrDefault("game.minRoundSeconds", "0")),
                    Boolean.parseBoolean(values.getOrDefault("game.requireRealityCheckProof", "false")));
        } catch (NumberFormatException e) {
            return GameFeatures.DEFAULT;
        }
    }

    @Transactional
    public void setFeatures(GameFeatures features) {
        change(Map.of("game.holdToDrop", Boolean.toString(features.holdToDrop()), "game.sessionClock", Boolean.toString(features.sessionClock()),
                "game.realityCheckMinutes", Integer.toString(features.realityCheckMinutes()),
                "game.quietSmallWins", Boolean.toString(features.quietSmallWins()),
                "game.minRoundSeconds", features.minRoundSeconds().stripTrailingZeros().toPlainString(),
                "game.requireRealityCheckProof", Boolean.toString(features.requireRealityCheckProof())));
    }

    @Transactional
    public void setGameEnabled(boolean enabled) {
        change(Map.of("game.enabled", Boolean.toString(enabled)));
    }

    /** N3 #3: the most a settlement of a blocked round may pay, per currency ({@code CUR=amount,...}). */
    public static final String SETTLEMENT_MAX = "settlement.maxAmounts";
    /** N3 #3: the most a settlement may pay that is not the round's own recomputed win, per currency. */
    public static final String SETTLEMENT_OTHER = "settlement.otherAmounts";

    /** Both settlement limit lists, written {@code EUR=1000,GBP=900} (sorted by currency). */
    public record SettlementLimits(String maxAmounts, String otherAmounts) {
    }

    /**
     * The stored settlement limit {@code name} (SETTLEMENT_MAX or SETTLEMENT_OTHER); empty while it is not stored yet.
     * Refused (TamperedException) while the settings fail their check: a limit edited in the database is never used.
     */
    public Optional<String> settlementLimit(String name) {
        Cached settings = current();
        if (!settings.trusted()) {
            throw new TamperedException();
        }
        return Optional.ofNullable(settings.values().get(name));
    }

    /**
     * Reads a settlement limit list: {@code CUR=amount} entries separated by commas, each currency an ISO code listed
     * once, each amount above 0 with at most 2 decimals. Throws IllegalArgumentException saying what is wrong.
     */
    public static Map<String, BigDecimal> parseSettlementLimits(String list) {
        Map<String, BigDecimal> limits = new TreeMap<>();
        if (list == null || list.isBlank()) {
            throw new IllegalArgumentException("at least one currency is required, written CUR=amount,CUR=amount (e.g. EUR=1000,GBP=900)");
        }
        for (String entry : list.split(",", -1)) {
            String[] parts = entry.trim().split("=", -1);
            if (parts.length != 2 || !parts[0].trim().matches("[A-Z]{3}") || !com.plinko.common.Currencies.isValid(parts[0].trim())) {
                throw new IllegalArgumentException("write CUR=amount,CUR=amount (e.g. EUR=1000,GBP=900); got '" + entry.trim() + "'");
            }
            String currency = parts[0].trim();
            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("the amount for " + currency + " is not a number");
            }
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("the amount for " + currency + " must be above 0, with at most 2 decimals");
            }
            if (limits.put(currency, amount) != null) {
                throw new IllegalArgumentException(currency + " is listed twice");
            }
        }
        return limits;
    }

    /** A limit list in its one stored form: sorted by currency, amounts without trailing zeros. */
    public static String normalSettlementLimits(String list) {
        StringBuilder out = new StringBuilder();
        parseSettlementLimits(list).forEach((currency, amount) -> out.append(out.isEmpty() ? "" : ",").append(currency).append('=')
                .append(amount.stripTrailingZeros().toPlainString()));
        return out.toString();
    }

    /** What is wrong with these settlement limits (empty when they can be saved). */
    public static List<String> settlementLimitProblems(String maxAmounts, String otherAmounts) {
        List<String> problems = new ArrayList<>();
        Map<String, BigDecimal> max = null;
        Map<String, BigDecimal> other = null;
        try {
            max = parseSettlementLimits(maxAmounts);
        } catch (IllegalArgumentException e) {
            problems.add("Highest settlement: " + e.getMessage());
        }
        try {
            other = parseSettlementLimits(otherAmounts);
        } catch (IllegalArgumentException e) {
            problems.add("Other amounts: " + e.getMessage());
        }
        if (max != null && other != null) {
            Map<String, BigDecimal> highest = max;
            other.forEach((currency, amount) -> {
                if (highest.containsKey(currency) && amount.compareTo(highest.get(currency)) > 0) {
                    problems.add("Other amounts: " + currency + " " + amount.toPlainString() + " is above the highest settlement of "
                            + highest.get(currency).toPlainString());
                }
            });
        }
        return problems;
    }

    /** Only called when an approved change request is applied (a second admin agreed). */
    @Transactional
    public void setSettlementLimits(String maxAmounts, String otherAmounts) {
        List<String> problems = settlementLimitProblems(maxAmounts, otherAmounts);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        change(Map.of(SETTLEMENT_MAX, normalSettlementLimits(maxAmounts), SETTLEMENT_OTHER, normalSettlementLimits(otherAmounts)));
    }

    /**
     * Stores the settlement limits from the environment as their first value, recorded in the ledger like any change,
     * if they are not stored yet (an install from before they were settings). Does nothing once they are stored, and
     * while the settings fail their check. True if they were stored now.
     */
    @Transactional
    public boolean seedSettlementLimits(String maxAmounts, String otherAmounts) {
        Map<String, String> current = lockedValues();
        if (current.containsKey(SETTLEMENT_MAX) && current.containsKey(SETTLEMENT_OTHER)) {
            return false;
        }
        if (!trusted(current)) {
            return false;
        }
        Map<String, String> changes = new TreeMap<>();
        if (!current.containsKey(SETTLEMENT_MAX)) {
            changes.put(SETTLEMENT_MAX, normalSettlementLimits(maxAmounts));
        }
        if (!current.containsKey(SETTLEMENT_OTHER)) {
            changes.put(SETTLEMENT_OTHER, normalSettlementLimits(otherAmounts));
        }
        change(changes);
        return true;
    }

    /**
     * Saves {@code changes} and records the result. The rows stay locked from the check to the commit, and the new
     * version is the checked settings plus the changes (never a fresh read), so an edit made in the database, before
     * or during the change, is never recorded as genuine.
     */
    private void change(Map<String, String> changes) {
        Map<String, String> current = lockedValues();
        if (!trusted(current)) {
            throw new TamperedException();
        }
        Map<String, String> next = new TreeMap<>(current);
        next.putAll(changes);
        changes.forEach(this::set);
        record(next);
        repository.raiseVersion();
        forgetVersionAfterCommit();
    }

    private void set(String name, String value) {
        repository.set(name, value);
    }

    private Map<String, String> values() {
        return repository.values();
    }

    private Map<String, String> lockedValues() {
        return repository.lockedValues();
    }

    private static BigDecimal decimal(Map<String, String> values, String name, BigDecimal fallback) {
        String value = values.get(name);
        try {
            return value == null ? fallback : new BigDecimal(value).setScale(2, java.math.RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
