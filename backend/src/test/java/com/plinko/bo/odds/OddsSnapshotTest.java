package com.plinko.bo.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.plinko.TestFixtures;
import com.plinko.bo.bonus.BonusService;
import com.plinko.common.Integrity;
import com.plinko.model.BonusSettings;
import com.plinko.model.OddsTable;
import com.plinko.repo.IntegrityLedger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A bonus change approved on another server: rounds must never pair the old tables with the new bonuses. */
class OddsSnapshotTest {

    private static final BonusSettings GOLDEN = new BonusSettings(new BonusSettings.Golden(true, new BigDecimal("8"),
            List.of(new BonusSettings.Boost(new BigDecimal("2"), new BigDecimal("100")))), BonusSettings.OFF.hot());

    /** What is in the database: the bonus text and the tables, changed together like an approval does. */
    private String storedBonus;
    private Map<String, OddsTable> storedTables;
    private Runnable duringRead = () -> { };

    private final Integrity integrity = new Integrity(TestFixtures.properties());
    private final BonusService bonus = new BonusService(null, JsonMapper.builder().build()) {
        @Override
        public String stored() {
            return storedBonus;
        }
    };
    private final OddsService odds = new OddsService(mock(com.plinko.repo.OddsRepository.class), TestFixtures.properties(), integrity,
            ledger(), null, bonus) {
        @Override
        Map<String, OddsTable> readStored() {
            Map<String, OddsTable> read = storedTables;
            duringRead.run();
            duringRead = () -> { };
            return read;
        }

        @Override
        Map<String, String> storedSignatures() {
            Map<String, String> signatures = new TreeMap<>();
            storedTables.forEach((key, table) -> signatures.put(key, integrity.sign("odds", OddsService.fingerprint(table))));
            return signatures;
        }
    };

    private static IntegrityLedger ledger() {
        IntegrityLedger ledger = mock(IntegrityLedger.class);
        when(ledger.isLatest(any(), any())).thenReturn(true);
        return ledger;
    }

    private Map<String, OddsTable> tables(BonusSettings with) {
        Map<String, OddsTable> out = new TreeMap<>();
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                out.put(String.format("%02d:%d", rows, risk), odds.forTargetRtp(rows, risk, new BigDecimal("96"), with));
            }
        }
        return out;
    }

    private void approveGoldenElsewhere() {
        storedBonus = bonus.toJson(GOLDEN);
        storedTables = tables(GOLDEN);
    }

    @Test
    void roundAfterABonusApprovedElsewhereUsesTheNewTablesWithIt() {
        storedBonus = null;
        storedTables = tables(BonusSettings.OFF);
        odds.check();
        assertThat(odds.forRound().bonus().anyOn()).isFalse();

        approveGoldenElsewhere();                       // no reload on this server yet
        OddsService.Snapshot round = odds.forRound();
        assertThat(round.bonus().goldenOn()).isTrue();
        assertThat(round.get(8, 0)).isEqualTo(storedTables.get("08:0"));
        assertThat(BonusService.totalRtp(round.get(8, 0), round.bonus())).isLessThan(new BigDecimal("96.01"));
        assertThat(odds.healthy()).isTrue();
    }

    @Test
    void anApprovalCommittedDuringAReloadIsReadAgain() {
        storedBonus = null;
        storedTables = tables(BonusSettings.OFF);
        duringRead = this::approveGoldenElsewhere;      // the old tables are read, then the approval commits
        odds.check();
        OddsService.Snapshot loaded = odds.forRound();
        assertThat(loaded.bonus().goldenOn()).isTrue();
        assertThat(loaded.get(8, 0)).isEqualTo(storedTables.get("08:0"));
    }
}
