package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.settings.SettingsService;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.OddsTable;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.OddsRepository;
import com.plinko.repo.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Review N14.10: an approved odds change raises the settings version, so every other instance reloads its odds at its next round; and the odds' own integrity checks. */
class OddsChangeTest {

    private final PlinkoProperties properties = TestFixtures.properties();
    private final Integrity integrity = new Integrity(properties);

    // ---------------------------------------------------------------- the database the instances share, in memory

    private final Map<String, OddsTable> tables = new ConcurrentHashMap<>();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();
    private final Map<String, String> ledgerLatest = new ConcurrentHashMap<>();
    private final Map<Long, OddsRepository.HistoryEntry> history = new ConcurrentHashMap<>();
    private final AtomicLong settingsVersion = new AtomicLong(1);

    private final OddsRepository oddsRepository = oddsRepository();
    private final IntegrityLedger ledger = ledger();
    private final SettingsRepository settingsRepository = settingsRepository();

    private static String key(int rows, int risk) {
        return rows + ":" + risk;
    }

    private OddsRepository oddsRepository() {
        OddsRepository repository = mock(OddsRepository.class);
        when(repository.count()).thenAnswer(i -> (long) tables.size());
        doAnswer(i -> {
            OddsTable table = i.getArgument(0);
            tables.put(key(table.rows(), table.risk()), table);
            return null;
        }).when(repository).replace(any());
        doAnswer(i -> {
            signatures.put(key(i.getArgument(0), i.getArgument(1)), i.getArgument(2));
            return null;
        }).when(repository).saveSignature(anyInt(), anyInt(), anyString());
        when(repository.tables()).thenAnswer(i -> List.copyOf(tables.values()));
        when(repository.signatures()).thenAnswer(i -> signatures.entrySet().stream().map(e -> {
            String[] k = e.getKey().split(":");
            return new OddsRepository.StoredSignature(Integer.parseInt(k[0]), Integer.parseInt(k[1]), e.getValue());
        }).toList());
        when(repository.historyId(anyInt(), anyInt(), anyString(), anyString())).thenAnswer(i -> {
            synchronized (history) {
                for (OddsRepository.HistoryEntry e : history.values()) {
                    if (e.rows() == (int) i.getArgument(0) && e.risk() == (int) i.getArgument(1) && e.multipliers().equals(i.getArgument(2))) {
                        return e.id();
                    }
                }
                long id = history.size() + 1;
                history.put(id, new OddsRepository.HistoryEntry(id, i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), Instant.now()));
                return id;
            }
        });
        when(repository.history(anyLong())).thenAnswer(i -> Optional.ofNullable(history.get((long) i.getArgument(0))));
        return repository;
    }

    private IntegrityLedger ledger() {
        IntegrityLedger l = mock(IntegrityLedger.class);
        doAnswer(i -> ledgerLatest.put(i.getArgument(0), i.getArgument(1))).when(l).record(anyString(), anyString());
        when(l.isLatest(anyString(), any())).thenAnswer(i -> i.getArgument(1) != null && i.getArgument(1).equals(ledgerLatest.get(i.getArgument(0))));
        when(l.latestContent(anyString())).thenAnswer(i -> Optional.ofNullable(ledgerLatest.get(i.getArgument(0))));
        when(l.exists(anyString())).thenAnswer(i -> ledgerLatest.containsKey(i.getArgument(0)));
        return l;
    }

    private SettingsRepository settingsRepository() {
        SettingsRepository repository = mock(SettingsRepository.class);
        when(repository.version()).thenAnswer(i -> settingsVersion.get());
        when(repository.values()).thenReturn(Map.of());
        doAnswer(i -> settingsVersion.incrementAndGet()).when(repository).raiseVersion();
        return repository;
    }

    /** One backend instance: its own odds and settings in memory, over the shared database. */
    private record Instance(OddsService odds, SettingsService settings, AuditService audit) {
    }

    private Instance instance() {
        return instance(integrity);
    }

    private Instance instance(Integrity keys) {
        AuditService audit = mock(AuditService.class);
        SettingsService settings = new SettingsService(settingsRepository, properties, ledger);
        OddsService odds = new OddsService(oddsRepository, properties, keys, ledger, audit, null);
        odds.setSettings(settings);
        return new Instance(odds, settings, audit);
    }

    /** A fresh installation: the first instance stores and signs the default tables. */
    private Instance installed() {
        Instance first = instance();
        first.odds().run(null);
        assertThat(first.odds().healthy()).as("%s", first.odds().problems()).isTrue();
        return first;
    }

    private OddsTable at95(Instance on) {
        return on.odds().forTargetRtp(8, 1, new BigDecimal("95"));
    }

    // ---------------------------------------------------------------- N14.10

    @Test
    void anApprovedChangeRaisesTheSettingsVersionAndAnotherInstanceUsesItAtItsNextRound() throws Exception {
        Instance a = installed();
        Instance b = instance();
        b.odds().check();
        OddsTable before = b.odds().forRound().get(8, 1);
        long version = settingsVersion.get();

        OddsTable changed = at95(a);
        assertThat(OddsService.fingerprint(changed)).isNotEqualTo(OddsService.fingerprint(before));
        a.odds().apply(changed);
        assertThat(settingsVersion.get()).as("the approval announced the change").isEqualTo(version + 1);
        assertThat(OddsService.fingerprint(a.odds().forRound().get(8, 1))).as("used at once where it was approved")
                .isEqualTo(OddsService.fingerprint(changed));

        // the other instance has not re-read its odds (no minute's check): its next round sees the new version
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        OddsTable seen = b.odds().forRound().get(8, 1);
        while (!OddsService.fingerprint(seen).equals(OddsService.fingerprint(changed)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            seen = b.odds().forRound().get(8, 1);
        }
        assertThat(OddsService.fingerprint(seen)).as("reloaded within a second of the change").isEqualTo(OddsService.fingerprint(changed));
        assertThat(b.odds().forRound().ref(8, 1)).as("and records the new odds version with its rounds").isNotNull()
                .isEqualTo(a.odds().forRound().ref(8, 1));
        assertThat(b.odds().healthy()).isTrue();
    }

    @Test
    void withoutANewVersionARoundDoesNotReloadTheOdds() {
        Instance a = installed();
        a.odds().forRound();
        org.mockito.Mockito.clearInvocations(oddsRepository);
        for (int round = 0; round < 20; round++) {
            a.odds().forRound();
        }
        verify(oddsRepository, never()).tables();
    }

    @Test
    void theNewTableIsUsedOnlyOnceTheApprovalCommits() {
        Instance a = installed();
        OddsTable before = a.odds().get(8, 1);
        OddsTable changed = at95(a);
        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> registered;
        try {
            a.odds().apply(changed);
            assertThat(a.odds().get(8, 1)).as("not before the commit").isEqualTo(before);
            registered = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(registered).hasSize(2);
        registered.forEach(TransactionSynchronization::afterCommit);
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertThat(a.odds().get(8, 1)).isEqualTo(changed);
        assertThat(a.settings().version()).isEqualTo(settingsVersion.get());
    }

    @Test
    void aTableOutsideTheHardLimitsIsNotApprovedAndNothingIsAnnounced() {
        Instance a = installed();
        long version = settingsVersion.get();
        OddsTable generous = a.odds().forTargetRtp(8, 1, new BigDecimal("99.9"));
        assertThatThrownBy(() -> a.odds().apply(generous)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("above the hard limit");
        OddsTable mean = a.odds().forTargetRtp(8, 1, new BigDecimal("80"));
        assertThatThrownBy(() -> a.odds().apply(mean)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below the hard limit");
        assertThat(settingsVersion.get()).isEqualTo(version);
        assertThat(OddsService.fingerprint(tables.get(key(8, 1)))).isEqualTo(OddsService.fingerprint(a.odds().get(8, 1)));
    }

    // ---------------------------------------------------------------- the stored odds' own checks

    private void assertStoppedWith(Instance a, String problem) {
        a.odds().check();
        assertThat(a.odds().healthy()).isFalse();
        assertThat(a.odds().problems()).anyMatch(p -> p.contains(problem));
        verify(a.audit()).log(eq("system"), eq("ODDS_INTEGRITY_FAILED"), startsWith("Game stopped: "));
        // said once, not every minute
        a.odds().check();
        verify(a.audit(), times(1)).log(eq("system"), eq("ODDS_INTEGRITY_FAILED"), anyString());
    }

    @Test
    void aTableEditedInTheDatabaseStopsTheGame() {
        Instance a = installed();
        OddsTable table = tables.get(key(8, 1));
        List<OddsTable.Prize> prizes = new ArrayList<>(table.prizes());
        OddsTable.Prize top = prizes.get(prizes.size() - 1);
        prizes.set(prizes.size() - 1, new OddsTable.Prize(top.index(), top.multiplier().add(BigDecimal.TEN), top.probability()));
        tables.put(key(8, 1), new OddsTable(8, 1, prizes));
        assertStoppedWith(a, "8 rows / " + PlinkoBoard.RISK_NAMES[1] + ": changed outside the admin panel");
        assertThat(a.odds().get(8, 1)).as("the edited table is not used").isNull();
    }

    @Test
    void aMissingTableStopsTheGame() {
        Instance a = installed();
        tables.remove(key(9, 2));
        assertStoppedWith(a, ": missing");
    }

    @Test
    void anOlderApprovedVersionPutBackStopsTheGame() {
        Instance a = installed();
        OddsTable original = tables.get(key(8, 1));
        String originalSignature = signatures.get(key(8, 1));
        a.odds().apply(at95(a));
        tables.put(key(8, 1), original);
        signatures.put(key(8, 1), originalSignature);
        assertStoppedWith(a, "an older approved version was put back");
    }

    @Test
    void aSignedTableOutsideTheHardLimitsStopsTheGame() {
        Instance a = installed();
        OddsTable generous = a.odds().forTargetRtp(8, 1, new BigDecimal("99.9"));
        tables.put(key(8, 1), generous);
        String signature = integrity.sign("odds", OddsService.fingerprint(generous));
        signatures.put(key(8, 1), signature);
        ledgerLatest.put("odds:8:1", signature);
        assertStoppedWith(a, "above the hard limit");
    }

    @Test
    void rowsForABoardTheGameDoesNotHaveStopTheGame() {
        Instance a = installed();
        tables.put(key(40, 0), new OddsTable(40, 0, a.odds().get(8, 0).prizes()));
        assertStoppedWith(a, "Unexpected odds rows");
    }

    @Test
    void anInstallationThatAlreadyHasOddsKeepsThem() {
        Instance a = installed();
        Map<String, OddsTable> stored = new TreeMap<>(tables);
        Instance b = instance();
        b.odds().run(null);
        assertThat(tables).isEqualTo(stored);
        assertThat(b.odds().healthy()).isTrue();
        assertThat(b.odds().all()).hasSize(stored.size());
        verify(a.audit(), never()).log(anyString(), anyString(), anyString());
    }

    @Test
    void signingAndRecordingEveryStoredTableMakesThemTrustedAgain() {
        Instance a = installed();
        // as when integrity protection is first switched on: tables without valid signatures or ledger entries
        signatures.replaceAll((k, v) -> "unsigned");
        ledgerLatest.clear();
        a.odds().check();
        assertThat(a.odds().healthy()).isFalse();
        a.odds().signAllStored();
        a.odds().check();
        assertThat(a.odds().healthy()).isTrue();

        // and recording only what verifies in the ledger
        ledgerLatest.clear();
        signatures.put(key(8, 0), "forged");
        a.odds().recordAllInLedger();
        assertThat(ledgerLatest).doesNotContainKey("odds:8:0").containsKey("odds:8:1");
    }

    @Test
    void tablesSignedWithTheOriginalKeyAreSignedAgainWithTheCurrentOne() {
        Instance a = installed();
        assertThat(a.odds().moveToCurrentKey()).as("no key list: nothing to move to").isZero();
        Integrity rotated = new Integrity(new com.plinko.common.KeyRing(properties, "", "d1=" + "d".repeat(40), ""));
        Instance b = instance(rotated);
        int all = tables.size();
        signatures.put(key(8, 0), "forged");
        assertThat(b.odds().moveToCurrentKey()).as("every table that verifies").isEqualTo(all - 1);
        assertThat(signatures.get(key(8, 1))).startsWith("d1.");
        assertThat(signatures.get(key(8, 0))).isEqualTo("forged");
        assertThat(b.odds().moveToCurrentKey()).as("done once").isZero();
    }

    @Test
    void theOddsVersionOfARoundIsReadableOnlyWhileItsSignatureHolds() {
        Instance a = installed();
        long ref = a.odds().forRound().ref(8, 1);
        assertThat(a.odds().playedOn(ref, 8, 1)).isPresent();
        assertThat(a.odds().playedOn(ref, 8, 2)).as("another board's version").isEmpty();
        assertThat(a.odds().playedOn(9999, 8, 1)).isEmpty();
        assertThat(a.odds().unreadable(ref)).isEmpty();
        assertThat(a.odds().unreadable(9999)).contains("is missing");
        OddsRepository.HistoryEntry e = history.get(ref);
        history.put(ref, new OddsRepository.HistoryEntry(e.id(), e.rows(), e.risk(), e.multipliers().replaceFirst("^[^,]+", "9"), e.signature(),
                e.createdAt()));
        assertThat(a.odds().playedOn(ref, 8, 1)).as("an edited version").isEmpty();
        assertThat(a.odds().unreadable(ref)).as("edited, not unreadable: it can be checked and fails").isEmpty();
        // signed with a key this server doesn't hold: a setup mistake, not an edit
        history.put(ref, new OddsRepository.HistoryEntry(e.id(), e.rows(), e.risk(), e.multipliers(), "k9." + "0".repeat(61), e.createdAt()));
        assertThat(a.odds().unreadable(ref)).hasValueSatisfying(why -> assertThat(why).contains("k9"));
    }

    @Test
    void tablesAreCheckedForEveryKindOfMistake() {
        OddsService odds = instance().odds();
        OddsTable natural = odds.withNaturalProbabilities(8, 0, odds.defaultMultipliers(8, 0));
        assertThat(natural.prizes()).hasSize(PlinkoBoard.prizeCount(8));
        assertThat(natural.totalProbability()).isCloseTo(OddsTable.HUNDRED, org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));

        List<OddsTable.Prize> prizes = new ArrayList<>(natural.prizes());
        prizes.set(1, new OddsTable.Prize(1, new BigDecimal("1.005"), prizes.get(1).probability()));
        prizes.set(2, new OddsTable.Prize(2, prizes.get(2).multiplier(), new BigDecimal("-1")));
        prizes.set(3, new OddsTable.Prize(3, new BigDecimal("-2"), prizes.get(3).probability()));
        assertThat(odds.validate(new OddsTable(8, 0, prizes))).anyMatch(p -> p.contains("at most 2 decimals"))
                .anyMatch(p -> p.contains("probability must be 0 or more")).anyMatch(p -> p.contains("multiplier must be 0 or more"));
        assertThat(odds.validate(new OddsTable(8, 0, natural.prizes().subList(0, 3)))).anyMatch(p -> p.startsWith("Expected"));
        assertThat(OddsService.fingerprint(null)).isEqualTo("absent");
    }
}
