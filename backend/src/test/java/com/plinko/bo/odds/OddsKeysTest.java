package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.plinko.TestFixtures;
import com.plinko.common.Integrity;
import com.plinko.common.KeyRing;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.OddsRepository;
import org.junit.jupiter.api.Test;

/** N8 and N14.5: odds tables move to the current key, and an odds version that can't be checked is told apart from an edited one. */
class OddsKeysTest {

    private final Integrity original = new Integrity(TestFixtures.properties());
    private final Integrity rotated = new Integrity(new KeyRing(TestFixtures.properties(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40), "e1=" + "e".repeat(40)));
    private final OddsRepository repository = mock(OddsRepository.class);
    private final IntegrityLedger ledger = mock(IntegrityLedger.class);

    private OddsService service(Integrity integrity, Map<String, OddsTable> tables, Map<String, String> signatures) {
        return new OddsService(repository, TestFixtures.properties(), integrity, ledger, mock(com.plinko.bo.audit.AuditService.class), null) {
            @Override
            Map<String, OddsTable> readStored() {
                return tables;
            }

            @Override
            Map<String, String> storedSignatures() {
                return signatures;
            }
        };
    }

    @Test
    void anOddsVersionThatCantBeCheckedIsToldApartFromAnEditedOne() {
        OddsService odds = service(original, Map.of(), Map.of());
        when(repository.history(1L)).thenReturn(Optional.of(new OddsRepository.HistoryEntry(1, 8, 0, "1,2", original.sign("odds-history", 8, 0, "1,2"),
                Instant.now())));
        when(repository.history(2L)).thenReturn(Optional.of(new OddsRepository.HistoryEntry(2, 8, 0, "1,2", rotated.sign("odds-history", 8, 0, "1,2"),
                Instant.now())));
        when(repository.history(3L)).thenReturn(Optional.empty());
        // signed with a key this server holds: if it fails its check, it was changed
        assertThat(odds.unreadable(1L)).isEmpty();
        // signed with a key this server doesn't hold (d1), or missing: a setup problem
        assertThat(odds.unreadable(2L)).get().asString().contains("key d1");
        assertThat(odds.unreadable(3L)).contains("is missing");
    }

    @Test
    void tablesSignedWithTheOriginalKeyMoveToTheCurrentOne() {
        OddsService builder = service(original, Map.of(), Map.of());
        Map<String, OddsTable> tables = new TreeMap<>();
        Map<String, String> signatures = new TreeMap<>();
        for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
            OddsTable table = builder.forTargetRtp(8, risk, new BigDecimal("96"), BonusSettings.OFF);
            tables.put("08:" + risk, table);
            signatures.put("08:" + risk, original.sign("odds", OddsService.fingerprint(table)));
        }
        when(ledger.isLatest(anyString(), anyString())).thenReturn(true);
        // still signing with the original key: nothing to move
        assertThat(service(original, tables, signatures).moveToCurrentKey()).isZero();
        assertThat(service(rotated, tables, signatures).moveToCurrentKey()).isEqualTo(PlinkoBoard.RISK_LEVELS);
        verify(repository, times(PlinkoBoard.RISK_LEVELS)).saveSignature(anyInt(), anyInt(), org.mockito.ArgumentMatchers.startsWith("d1."));
    }

    @Test
    void aTableThatFailsItsCheckIsNeverSignedAgain() {
        OddsService builder = service(original, Map.of(), Map.of());
        OddsTable table = builder.forTargetRtp(8, 0, new BigDecimal("96"), BonusSettings.OFF);
        Map<String, OddsTable> tables = Map.of("08:0", table);
        when(ledger.isLatest(anyString(), anyString())).thenReturn(true);
        // edited in the database: its signature no longer matches; re-signing would make the edit genuine
        assertThat(service(rotated, tables, Map.of("08:0", "f".repeat(64))).moveToCurrentKey()).isZero();
        verify(repository, never()).saveSignature(anyInt(), anyInt(), any());
    }

    @Test
    void anOddsVersionSignedAgainStillCountsOnceTheOriginalKeyIsRemoved() {
        // N8 #2: an odds version signed with the original key, signed again with d1 by the re-signing job
        KeyRing removed = new KeyRing(TestFixtures.propertiesWithoutOriginalKey(), "r1=" + "r".repeat(40), "d1=" + "d".repeat(40),
                "e1=" + "e".repeat(40), "2026-01-01T00:00:00Z");
        Integrity now = new Integrity(removed);
        OddsService odds = service(now, Map.of(), Map.of());
        String multipliers = "0.5,1,1.1,2,5.6";
        when(repository.history(7L)).thenReturn(Optional.of(new OddsRepository.HistoryEntry(7, 8, 0, multipliers,
                original.sign("odds-history", 8, 0, multipliers), Instant.parse("2025-06-01T00:00:00Z"))));
        assertThat(odds.playedOn(7L, 8, 0)).as("before it is signed again").isEmpty();
        assertThat(odds.unreadable(7L)).as("its only signature names the removed key").isPresent();

        com.plinko.repo.ResignedSignatures resigned = mock(com.plinko.repo.ResignedSignatures.class);
        when(resigned.of(com.plinko.repo.ResignedSignatures.ODDS, "7")).thenReturn(Optional.of(now.sign("odds-history", 8, 0, multipliers)));
        odds.setResigned(resigned);
        assertThat(odds.playedOn(7L, 8, 0)).get().asList().hasSize(5);
        assertThat(odds.unreadable(7L)).isEmpty();

        // a "new signature" that is itself in the old format, or doesn't match, counts for nothing
        when(resigned.of(com.plinko.repo.ResignedSignatures.ODDS, "7")).thenReturn(Optional.of(original.sign("odds-history", 8, 0, multipliers)));
        assertThat(odds.playedOn(7L, 8, 0)).isEmpty();
        when(resigned.of(com.plinko.repo.ResignedSignatures.ODDS, "7")).thenReturn(Optional.of(now.sign("odds-history", 8, 0, "9,9,9,9,9")));
        assertThat(odds.playedOn(7L, 8, 0)).isEmpty();
    }
}
