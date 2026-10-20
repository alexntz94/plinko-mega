package com.plinko.bo.odds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.bonus.BonusService;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.OddsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds the odds tables (multiplier and probability per prize position) and decides where balls land.
 * Tables are cached in memory and reloaded on every save.
 * <p>
 * Every stored table carries an HMAC signature made when an approved change is applied. A table that was
 * edited directly in the database fails its signature (or the hard limits) when loaded, and the game stops
 * until it is fixed: nobody can raise the odds without going through two-person approval.
 */
@Service
public class OddsService implements ApplicationRunner {

    /** A lock rather than synchronized, so a virtual thread that waits for I/O inside it does not hold up its carrier thread. */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    private static final BigDecimal PROBABILITY_TOLERANCE = new BigDecimal("0.0001");
    public static final BigDecimal DEFAULT_RTP = new BigDecimal("96");

    private static final Logger log = LoggerFactory.getLogger(OddsService.class);

    private final OddsRepository repository;
    private final PlinkoProperties.Limits limits;
    private final Integrity integrity;
    private final IntegrityLedger ledger;
    private final AuditService audit;
    private final BonusService bonus;
    private final SecureRandom random = new SecureRandom();
    /**
     * The tables together with the bonus settings they were approved with (a bonus change rebuilds every table), read
     * as one: a round never combines tables of one version with bonuses of another.
     */
    /** refs: per table, its version in odds_history, which a round records so its win can be checked against it later. */
    public record Snapshot(Map<String, OddsTable> tables, BonusSettings bonus, String bonusSource, Map<String, Long> refs) {
        public Snapshot(Map<String, OddsTable> tables, BonusSettings bonus, String bonusSource) {
            this(tables, bonus, bonusSource, Map.of());
        }

        public OddsTable get(int rows, int risk) {
            return tables.get(key(rows, risk));
        }

        public Long ref(int rows, int risk) {
            return refs.get(key(rows, risk));
        }
    }

    /** History ids already known, by table and its multipliers, so a reload only writes when a table changed. */
    private final Map<String, Long> knownRefs = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Snapshot snapshot = new Snapshot(Map.of(), BonusSettings.OFF, null);
    /** Why the stored odds cannot be used; empty when every table is present, signed and within the hard limits. */
    private volatile List<String> problems = List.of("Odds not loaded yet");

    public OddsService(OddsRepository repository, PlinkoProperties properties, Integrity integrity, IntegrityLedger ledger, AuditService audit,
                       BonusService bonus) {
        this.repository = repository;
        this.bonus = bonus;
        this.limits = properties.limits();
        this.integrity = integrity;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = repository.count();
        if (count == 0) {
            for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
                for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                    save(defaultTable(rows, risk));
                }
            }
        }
        reload();
    }

    /**
     * Builds a table with exactly the target RTP (in percent) from the standard shape for this rows/risk:
     * <ol>
     * <li>losing multipliers (1x or less) stay as they are; the winning part of every other multiplier
     *     (m - 1) is scaled by one factor, so big prizes move most;</li>
     * <li>values are rounded to readable steps (0.1 below 10x, 0.5 below 100x, whole numbers above) and
     *     kept rising from the centre to the edges;</li>
     * <li>the rounding error is removed by moving a sliver of probability between the centre and the
     *     next position, so the RTP lands on the target.</li>
     * </ol>
     */
    public OddsTable forTargetRtp(int rows, int risk, BigDecimal targetPercent) {
        return forTargetRtp(rows, risk, targetPercent, currentBonus());
    }

    /**
     * Same, where the target is the total RTP including these bonuses: the table itself is built for
     * target / bonus factor, so table and bonuses together return exactly the target.
     */
    public OddsTable forTargetRtp(int rows, int risk, BigDecimal targetPercent, BonusSettings bonusSettings) {
        // The meter, the gates and the wheel add a share that depends on the table (how often it lands where they pay),
        // so build, measure what the built table adds, and rebuild until the two agree (a few passes; stops when stable).
        double target = targetPercent.doubleValue() / 100;
        // positions that also pay through the wheel or the meter keep their natural chance (a sliver moved onto them
        // would move what the wheels add as well, and the passes would never settle)
        java.util.function.IntPredicate fixed = prize -> (bonusSettings.wheelOn() && prize == MegaBoard.spinPrizeIndex(rows))
                || BonusService.chargesMeter(bonusSettings, rows, risk, prize);
        OddsTable table = buildForBase(rows, risk, target / BonusService.factor(bonusSettings, rows).doubleValue(), fixed);
        boolean dependsOnTable = bonusSettings.meterOn() || bonusSettings.gatesOn() || bonusSettings.wheelOn();
        for (int pass = 0; pass < 12 && dependsOnTable; pass++) {
            double base = (target - BonusService.extra(bonusSettings, table).doubleValue())
                    / (BonusService.factor(bonusSettings, table).doubleValue() * BonusService.gateFactor(bonusSettings, table).doubleValue());
            OddsTable next = buildForBase(rows, risk, base, fixed);
            if (fingerprint(next).equals(fingerprint(table))) {
                break;
            }
            table = next;
        }
        return table;
    }

    /** The table with exactly this RTP of its own (a fraction, e.g. 0.96), from the standard shape. */
    private OddsTable buildForBase(int rows, int risk, double target, java.util.function.IntPredicate fixed) {
        double[] p = PlinkoBoard.naturalProbabilities(rows);
        double[] base = defaultMultipliers(rows, risk).stream().mapToDouble(BigDecimal::doubleValue).toArray();

        double losing = 0, winningProbability = 0, winningProfit = 0;
        for (int i = 0; i < base.length; i++) {
            if (base[i] <= 1) {
                losing += base[i] * p[i];
            } else {
                winningProbability += p[i];
                winningProfit += (base[i] - 1) * p[i];
            }
        }
        double factor = winningProfit > 0 ? (target - losing - winningProbability) / winningProfit : -1;
        double[] m = new double[base.length];
        for (int i = 0; i < base.length; i++) {
            if (factor > 0) {
                m[i] = base[i] <= 1 ? base[i] : 1 + (base[i] - 1) * factor;
            } else {
                // target below what the losing slots alone return: scale everything down instead
                m[i] = base[i] * target / rtpOf(base, p);
            }
        }
        for (int i = 0; i < m.length; i++) {
            m[i] = readable(m[i]);
            if (i > 0 && m[i] < m[i - 1]) {
                m[i] = m[i - 1];
            }
        }

        // Remove the rounding error with the smallest possible probability move between the centre and one
        // inner position; the edge (jackpot) position keeps its natural frequency.
        double residual = target - rtpOf(m, p);
        int bestJ = -1;
        double bestShift = 0;
        for (int j = 1; j < m.length - 1; j++) {
            if (m[j] == m[0] || fixed.test(j)) {
                continue;
            }
            double shift = residual / (m[j] - m[0]);
            if (p[0] - shift >= 0 && p[j] + shift >= 0 && (bestJ < 0 || Math.abs(shift) < Math.abs(bestShift))) {
                bestJ = j;
                bestShift = shift;
            }
        }
        if (bestJ > 0) {
            p[0] -= bestShift;
            p[bestJ] += bestShift;
        }

        List<OddsTable.Prize> prizes = new ArrayList<>();
        BigDecimal others = BigDecimal.ZERO;
        for (int i = m.length - 1; i >= 0; i--) {
            BigDecimal probability = i == 0
                    ? OddsTable.HUNDRED.subtract(others)
                    : BigDecimal.valueOf(p[i] * 100).setScale(8, RoundingMode.HALF_EVEN);
            others = others.add(probability);
            prizes.add(0, new OddsTable.Prize(i, BigDecimal.valueOf(m[i]).setScale(2, RoundingMode.HALF_EVEN), probability));
        }
        return new OddsTable(rows, risk, prizes);
    }

    private static double rtpOf(double[] multipliers, double[] probabilities) {
        double sum = 0;
        for (int i = 0; i < multipliers.length; i++) {
            sum += multipliers[i] * probabilities[i];
        }
        return sum;
    }

    private static double readable(double multiplier) {
        double rounded = multiplier >= 100 ? Math.round(multiplier)
                : multiplier >= 10 ? Math.round(multiplier * 2) / 2.0
                : Math.round(multiplier * 10) / 10.0;
        return Math.max(0.1, rounded);
    }

    public OddsTable get(int rows, int risk) {
        return snapshot.get(rows, risk);
    }

    /**
     * The tables and bonuses for a round. If the stored bonuses differ from the loaded ones (a bonus change approved
     * on another server since the last reload), everything is reloaded first.
     */
    public Snapshot forRound() {
        return forRound(settings == null ? -1 : settings.version(), bonus == null ? null : bonus.stored());
    }

    /**
     * N14.10: the same, for the settings a round already read (its settings version and stored bonus text), so the
     * round reads the settings once.
     */
    public Snapshot forRound(long settingsVersion, String bonusStored) {
        Snapshot current = snapshot;
        // an odds change approved on another instance raises the settings version: reload at the next round
        boolean announced = settings != null && settingsVersion != loadedVersion;
        if (announced || (bonus != null && !java.util.Objects.equals(current.bonusSource(), bonusStored))) {
            reload();
            current = snapshot;
        }
        return current;
    }

    /** Announces approved odds changes to every instance (optional: absent in unit tests). */
    private com.plinko.bo.settings.SettingsService settings;
    /** The settings version the loaded tables were read at. */
    private volatile long loadedVersion = -1;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(com.plinko.bo.settings.SettingsService settings) {
        this.settings = settings;
    }

    /** True when every table is present, correctly signed and within the hard limits; otherwise the game is stopped. */
    public boolean healthy() {
        return problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    /** Re-reads the stored odds every minute, so a direct database edit stops the game within a minute. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void check() {
        reload();
    }

    /** Signs every stored table as it is now. Only used once, when integrity protection is first switched on. */
    public void signAllStored() {
        Map<String, OddsTable> stored = readStored();
        stored.values().forEach(this::saveSignature);
    }

    /**
     * N8: tables that pass their check and are still signed with the original key are signed again with the current
     * one, so the original key can be retired. Returns how many.
     */
    public int moveToCurrentKey() {
        if (integrity.signsWithLegacy("odds")) {
            return 0;
        }
        Map<String, String> signatures = storedSignatures();
        int[] moved = {0};
        readStored().forEach((key, table) -> {
            String signature = signatures.get(key);
            if (com.plinko.common.KeyRing.isLegacy(signature) && integrity.verify(signature, "odds", fingerprint(table))
                    && ledger.isLatest(ledgerEntity(table.rows(), table.risk()), signature)) {
                saveSignature(table);
                moved[0]++;
            }
        });
        return moved[0];
    }

    /** Records the signature of every correctly signed table in the ledger (once, when the ledger is added). */
    public void recordAllInLedger() {
        Map<String, String> signatures = storedSignatures();
        readStored().forEach((key, table) -> {
            if (integrity.verify(signatures.get(key), "odds", fingerprint(table))) {
                ledger.record(ledgerEntity(table.rows(), table.risk()), signatures.get(key));
            }
        });
    }

    /** Fingerprint of a table, used to detect that it changed between a proposal and its approval. */
    public static String fingerprint(OddsTable table) {
        if (table == null) {
            return "absent";
        }
        StringBuilder out = new StringBuilder().append(table.rows()).append('/').append(table.risk());
        for (OddsTable.Prize prize : table.prizes()) {
            out.append(';').append(prize.index()).append(':').append(prize.multiplier().stripTrailingZeros().toPlainString())
                    .append(':').append(prize.probability().stripTrailingZeros().toPlainString());
        }
        return out.toString();
    }

    public List<OddsTable> all() {
        return new ArrayList<>(snapshot.tables().values());
    }

    /**
     * Stores a table. Only called when an approved change request is applied; the table is
     * validated again against the hard limits at that moment.
     */
    @Transactional
    public void apply(OddsTable table) {
        lock.lock();
        try {
            List<String> errors = validate(table);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", errors));
            }
            save(table);
            if (settings != null) {
                settings.announceChange();
            }
            // Use the new table only once the approval is committed; if it rolls back, memory keeps the old table.
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                reload();
                            }
                        });
            } else {
                reload();
            }
        } finally {
            lock.unlock();
        }
    }

    public List<String> validate(OddsTable table) {
        return validate(table, currentBonus());
    }

    /** The approved bonus settings (none in unit tests without a bonus service). */
    public BonusSettings currentBonus() {
        return bonus == null ? BonusSettings.OFF : bonus.current();
    }

    /** Checks a table against the hard limits; the RTP limits apply to the total, table plus these bonuses. */
    public List<String> validate(OddsTable table, BonusSettings bonusSettings) {
        List<String> errors = new ArrayList<>();
        if (table.prizes().size() != PlinkoBoard.prizeCount(table.rows())) {
            errors.add("Expected " + PlinkoBoard.prizeCount(table.rows()) + " prize positions for " + table.rows() + " rows");
        }
        for (OddsTable.Prize prize : table.prizes()) {
            if (prize.multiplier() == null || prize.multiplier().signum() < 0) {
                errors.add("Prize " + prize.index() + ": multiplier must be 0 or more");
            } else if (prize.multiplier().compareTo(limits.maxMultiplier()) > 0) {
                errors.add("Prize " + prize.index() + ": multiplier is above the hard limit of " + limits.maxMultiplier() + "x");
            } else if (prize.multiplier().scale() > 2 && prize.multiplier().stripTrailingZeros().scale() > 2) {
                errors.add("Prize " + prize.index() + ": multiplier can have at most 2 decimals");
            }
            if (prize.probability() == null || prize.probability().signum() < 0) {
                errors.add("Prize " + prize.index() + ": probability must be 0 or more");
            }
        }
        if (errors.isEmpty() && table.totalProbability().subtract(OddsTable.HUNDRED).abs().compareTo(PROBABILITY_TOLERANCE) > 0) {
            errors.add("Probabilities must add up to 100% (currently " + table.totalProbability().stripTrailingZeros().toPlainString() + "%)");
        }
        if (errors.isEmpty()) {
            BigDecimal rtp = BonusService.totalRtp(table, bonusSettings);
            String what = bonusSettings.anyOn() ? "Total RTP (with bonuses) " : "RTP ";
            if (rtp.compareTo(limits.maxRtp()) > 0) {
                errors.add(what + rtp.setScale(2, RoundingMode.HALF_UP) + "% is above the hard limit of " + limits.maxRtp() + "%");
            }
            if (rtp.compareTo(limits.minRtp()) < 0) {
                errors.add(what + rtp.setScale(2, RoundingMode.HALF_UP) + "% is below the hard limit of " + limits.minRtp() + "%");
            }
        }
        return errors;
    }

    /** The table a fresh installation starts with: the standard shape at {@link #DEFAULT_RTP}. */
    public OddsTable defaultTable(int rows, int risk) {
        return forTargetRtp(rows, risk, DEFAULT_RTP);
    }

    public List<BigDecimal> defaultMultipliers(int rows, int risk) {
        List<BigDecimal> multipliers = new ArrayList<>();
        for (double[] perRisk : OddsDefaults.MULTIPLIERS.get(rows)) {
            multipliers.add(BigDecimal.valueOf(perRisk[risk]));
        }
        return multipliers;
    }

    /** Builds a table with the given multipliers and the probabilities of a physical 50/50 board. */
    public OddsTable withNaturalProbabilities(int rows, int risk, List<BigDecimal> multipliers) {
        double[] natural = PlinkoBoard.naturalProbabilities(rows);
        List<OddsTable.Prize> prizes = new ArrayList<>();
        for (int i = 0; i < natural.length; i++) {
            BigDecimal probability = BigDecimal.valueOf(natural[i] * 100).setScale(8, RoundingMode.HALF_EVEN);
            prizes.add(new OddsTable.Prize(i, multipliers.get(i), probability));
        }
        return new OddsTable(rows, risk, prizes);
    }

    /** Picks the landing slot of one ball. */
    public int pickSlot(OddsTable table) {
        double target = random.nextDouble() * table.totalProbability().doubleValue();
        // Fallback for floating-point rounding at the very end of the range: the centre, never the jackpot.
        int prizeIndex = 0;
        double cumulative = 0;
        for (OddsTable.Prize prize : table.prizes()) {
            cumulative += prize.probability().doubleValue();
            if (target < cumulative) {
                prizeIndex = prize.index();
                break;
            }
        }
        return random.nextBoolean()
                ? PlinkoBoard.leftSlot(table.rows(), prizeIndex)
                : PlinkoBoard.rightSlot(table.rows(), prizeIndex);
    }

    private void save(OddsTable table) {
        repository.replace(table);
        saveSignature(table);
    }

    private void saveSignature(OddsTable table) {
        String signature = signature(table);
        repository.saveSignature(table.rows(), table.risk(), signature);
        ledger.record(ledgerEntity(table.rows(), table.risk()), signature);
    }

    private static String ledgerEntity(int rows, int risk) {
        return "odds:" + rows + ":" + risk;
    }

    Map<String, String> storedSignatures() {
        Map<String, String> signatures = new TreeMap<>();
        repository.signatures().forEach(row -> signatures.put(key(row.rows(), row.risk()), row.signature()));
        return signatures;
    }

    private String signature(OddsTable table) {
        return integrity.sign("odds", fingerprint(table));
    }

    /** Loads the stored tables; only complete, correctly signed tables within the hard limits are used. */
    private void reload() {
        lock.lock();
        try {
            // read before the tables: a change committed meanwhile only causes one more reload
            loadedVersion = settings == null ? -1 : settings.version();
            // A bonus change saves the bonus and every table in one transaction: if the bonus is the same before and after
            // reading the tables, the tables belong to it. Otherwise a change was committed meanwhile: read again.
            String bonusSource;
            Map<String, OddsTable> stored;
            Map<String, String> signatures;
            String after = bonus == null ? null : bonus.stored();
            do {
                bonusSource = after;
                stored = readStored();
                signatures = storedSignatures();
                after = bonus == null ? null : bonus.stored();
            } while (!java.util.Objects.equals(bonusSource, after));
            BonusSettings bonusSettings = bonus == null ? BonusSettings.OFF : bonus.parse(bonusSource);

            List<String> found = new ArrayList<>();
            Map<String, OddsTable> loaded = new TreeMap<>();
            for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
                for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                    String key = key(rows, risk);
                    String name = rows + " rows / " + PlinkoBoard.RISK_NAMES[risk];
                    OddsTable table = stored.get(key);
                    if (table == null) {
                        found.add(name + ": missing");
                    } else if (!integrity.verify(signatures.get(key), "odds", fingerprint(table))) {
                        found.add(name + ": changed outside the admin panel (signature does not match)");
                    } else if (!ledger.isLatest(ledgerEntity(rows, risk), signatures.get(key))) {
                        found.add(name + ": an older approved version was put back outside the admin panel");
                    } else if (!validate(table, bonusSettings).isEmpty()) {
                        found.add(name + ": " + String.join("; ", validate(table, bonusSettings)));
                    } else {
                        loaded.put(key, table);
                    }
                }
            }
            if (stored.size() > loaded.size() + found.size()) {
                found.add("Unexpected odds rows for rows/risk combinations the game does not have");
            }
            boolean newlyBroken = !found.isEmpty() && !found.equals(problems);
            Map<String, Long> refs = new TreeMap<>();
            loaded.forEach((key, table) -> refs.put(key, historyRef(table)));
            snapshot = new Snapshot(java.util.Collections.unmodifiableMap(loaded), bonusSettings, bonusSource,
                    java.util.Collections.unmodifiableMap(refs));
            problems = List.copyOf(found);
            if (newlyBroken) {
                log.error("ODDS INTEGRITY FAILURE, game stopped: {}", found);
                audit.log("system", "ODDS_INTEGRITY_FAILED", "Game stopped: " + String.join(" | ", found));
            }
        } finally {
            lock.unlock();
        }
    }

    /** The odds_history id of this table's multipliers, adding them the first time they are used. */
    private Long historyRef(OddsTable table) {
        String multipliers = historyMultipliers(table);
        return knownRefs.computeIfAbsent(key(table.rows(), table.risk()) + "=" + multipliers, k -> repository.historyId(table.rows(),
                table.risk(), multipliers, integrity.sign("odds-history", table.rows(), table.risk(), multipliers)));
    }

    /** The multipliers of a table by prize index, as odds_history keeps them (e.g. "0.5,1,1.1,2,5.6"). */
    public static String historyMultipliers(OddsTable table) {
        StringBuilder out = new StringBuilder();
        for (OddsTable.Prize prize : table.prizes()) {
            out.append(out.isEmpty() ? "" : ",").append(prize.multiplier().stripTrailingZeros().toPlainString());
        }
        return out.toString();
    }

    /**
     * The multipliers (by prize index) of the odds version a round was played on, if that version exists and its
     * signature verifies for these rows and risk; empty otherwise.
     */
    public java.util.Optional<List<BigDecimal>> playedOn(long ref, int rows, int risk) {
        return repository.history(ref)
                .filter(e -> e.rows() == rows && e.risk() == risk)
                .filter(e -> integrity.verifyMadeAt(e.createdAt(), e.signature(), "odds-history", e.rows(), e.risk(), e.multipliers())
                        || resignedOk(e))
                .map(e -> java.util.Arrays.stream(e.multipliers().split(",")).map(BigDecimal::new).toList());
    }

    /** N8 #2: an odds version first signed with the original key, signed again with a current key by the re-signing job. */
    private boolean resignedOk(OddsRepository.HistoryEntry e) {
        return resigned != null && com.plinko.common.KeyRing.isLegacy(e.signature())
                && resigned.of(com.plinko.repo.ResignedSignatures.ODDS, Long.toString(e.id()))
                        .filter(again -> !com.plinko.common.KeyRing.isLegacy(again))
                        .map(again -> integrity.verify(again, "odds-history", e.rows(), e.risk(), e.multipliers()))
                        .orElse(false);
    }

    private com.plinko.repo.ResignedSignatures resigned;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResigned(com.plinko.repo.ResignedSignatures resigned) {
        this.resigned = resigned;
    }

    /**
     * N14.5: why an odds version can't be checked although nobody need have changed it: it is missing (the application
     * can't delete versions), or its signature names a key this server doesn't hold (a key mistake). Empty when it can be
     * checked, i.e. when {@link #playedOn} failing means it was changed.
     */
    public java.util.Optional<String> unreadable(long ref) {
        java.util.Optional<OddsRepository.HistoryEntry> entry = repository.history(ref);
        if (entry.isEmpty()) {
            return java.util.Optional.of("is missing");
        }
        boolean resignedWithAHeldKey = resigned != null && resigned.of(com.plinko.repo.ResignedSignatures.ODDS, Long.toString(ref))
                .map(again -> integrity.hasKeyFor(again, "odds-history", null)).orElse(false);
        if (!resignedWithAHeldKey && !integrity.hasKeyFor(entry.get().signature(), "odds-history", entry.get().createdAt())) {
            return java.util.Optional.of("is signed with key " + com.plinko.common.KeyRing.keyIdOf(entry.get().signature()) + ", which this server doesn't hold");
        }
        return java.util.Optional.empty();
    }

    Map<String, OddsTable> readStored() {
        Map<String, OddsTable> stored = new TreeMap<>();
        repository.tables().forEach(table -> stored.put(key(table.rows(), table.risk()), table));
        return stored;
    }

    private static String key(int rows, int risk) {
        return String.format("%02d:%d", rows, risk);
    }
}
