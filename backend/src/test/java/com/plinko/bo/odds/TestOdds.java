package com.plinko.bo.odds;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.plinko.bo.bonus.BonusService;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.OddsRepository;
import tools.jackson.databind.json.JsonMapper;

/** A working odds service for tests outside this package: every table at 96% with these bonuses, odds history in memory. */
public final class TestOdds {

    private TestOdds() {
    }

    public static OddsService with(BonusSettings settings, PlinkoProperties properties) {
        return with(settings, properties, false);
    }

    /** edgeOnly: every ball lands on the board's top prize (the right edge slot), to test the largest possible win. */
    public static OddsService with(BonusSettings settings, PlinkoProperties properties, boolean edgeOnly) {
        Integrity integrity = new Integrity(properties);
        String stored = "test-bonus";
        BonusService bonus = new BonusService(null, JsonMapper.builder().build()) {
            @Override
            public String stored() {
                return stored;
            }

            @Override
            public BonusSettings parse(String text) {
                return settings;
            }

            @Override
            public BonusSettings current() {
                return settings;
            }
        };
        OddsRepository repository = mock(OddsRepository.class);
        Map<Long, OddsRepository.HistoryEntry> history = new HashMap<>();
        when(repository.historyId(anyInt(), anyInt(), anyString(), anyString())).thenAnswer(i -> {
            long id = history.size() + 1;
            history.put(id, new OddsRepository.HistoryEntry(id, i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), java.time.Instant.now()));
            return id;
        });
        when(repository.history(anyLong())).thenAnswer(i -> Optional.ofNullable(history.get((long) i.getArgument(0))));
        IntegrityLedger ledger = mock(IntegrityLedger.class);
        when(ledger.isLatest(any(), any())).thenReturn(true);
        Map<String, OddsTable> tables = new TreeMap<>();
        OddsService odds = new OddsService(repository, properties, integrity, ledger, mock(com.plinko.bo.audit.AuditService.class), bonus) {
            @Override
            Map<String, OddsTable> readStored() {
                return tables;
            }

            @Override
            public int pickSlot(OddsTable table) {
                return edgeOnly ? PlinkoBoard.rightSlot(table.rows(), table.prizes().size() - 1) : super.pickSlot(table);
            }

            @Override
            Map<String, String> storedSignatures() {
                Map<String, String> signatures = new TreeMap<>();
                tables.forEach((key, table) -> signatures.put(key, integrity.sign("odds", OddsService.fingerprint(table))));
                return signatures;
            }
        };
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                tables.put(String.format("%02d:%d", rows, risk), odds.forTargetRtp(rows, risk, new BigDecimal("96"), settings));
            }
        }
        odds.check();
        return odds;
    }
}
