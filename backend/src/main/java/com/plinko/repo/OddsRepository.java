package com.plinko.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.plinko.model.OddsTable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code odds} tables and their signatures ({@code odds_signature}). */
@Repository
public class OddsRepository {

    public record StoredSignature(int rows, int risk, String signature) {
    }

    private final JdbcClient jdbc;

    public OddsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM odds").query(Long.class).single();
    }

    /** Replaces every prize of the table's rows/risk. */
    public void replace(OddsTable table) {
        jdbc.sql("DELETE FROM odds WHERE rows_count = ? AND risk = ?").params(table.rows(), table.risk()).update();
        for (OddsTable.Prize prize : table.prizes()) {
            jdbc.sql("INSERT INTO odds (rows_count, risk, prize_index, multiplier, probability) VALUES (?, ?, ?, ?, ?)")
                    .params(table.rows(), table.risk(), prize.index(), prize.multiplier(), prize.probability())
                    .update();
        }
    }

    public void saveSignature(int rows, int risk, String signature) {
        jdbc.sql("INSERT INTO odds_signature (rows_count, risk, signature) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE signature = VALUES(signature)")
                .params(rows, risk, signature)
                .update();
    }

    public List<StoredSignature> signatures() {
        return jdbc.sql("SELECT rows_count, risk, signature FROM odds_signature")
                .query((rs, n) -> new StoredSignature(rs.getInt("rows_count"), rs.getInt("risk"), rs.getString("signature")))
                .list();
    }

    /** One odds version as it was used by rounds: the multipliers by prize index, and its integrity signature. */
    public record HistoryEntry(long id, int rows, int risk, String multipliers, String signature, java.time.Instant createdAt) {
    }

    /**
     * The id of this odds version in odds_history, adding it the first time it is used. Several instances may add the
     * same version at once: the unique key keeps one, and everyone reads that one back.
     */
    public long historyId(int rows, int risk, String multipliers, String signature) {
        jdbc.sql("INSERT IGNORE INTO odds_history (rows_count, risk, multipliers, signature, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(rows, risk, multipliers, signature, java.sql.Timestamp.from(java.time.Instant.now()))
                .update();
        return jdbc.sql("SELECT id FROM odds_history WHERE rows_count = ? AND risk = ? AND multipliers = ?")
                .params(rows, risk, multipliers).query(Long.class).single();
    }

    public java.util.Optional<HistoryEntry> history(long id) {
        return jdbc.sql("SELECT id, rows_count, risk, multipliers, signature, created_at FROM odds_history WHERE id = ?").param(id)
                .query((rs, n) -> new HistoryEntry(rs.getLong("id"), rs.getInt("rows_count"), rs.getInt("risk"),
                        rs.getString("multipliers"), rs.getString("signature"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** Every stored table, its prizes in order, whatever their content (the caller checks them). */
    public List<OddsTable> tables() {
        Map<List<Integer>, List<OddsTable.Prize>> prizes = new TreeMap<>((a, b) -> a.get(0).equals(b.get(0))
                ? Integer.compare(a.get(1), b.get(1)) : Integer.compare(a.get(0), b.get(0)));
        jdbc.sql("SELECT rows_count, risk, prize_index, multiplier, probability FROM odds ORDER BY rows_count, risk, prize_index")
                .query((rs, n) -> {
                    prizes.computeIfAbsent(List.of(rs.getInt("rows_count"), rs.getInt("risk")), k -> new ArrayList<>())
                            .add(new OddsTable.Prize(rs.getInt("prize_index"), rs.getBigDecimal("multiplier"), rs.getBigDecimal("probability")));
                    return null;
                })
                .list();
        List<OddsTable> tables = new ArrayList<>();
        prizes.forEach((key, list) -> tables.add(new OddsTable(key.get(0), key.get(1), List.copyOf(list))));
        return tables;
    }
}
