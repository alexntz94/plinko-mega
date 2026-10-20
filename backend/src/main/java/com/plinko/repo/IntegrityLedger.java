package com.plinko.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.common.Integrity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Append-only record of the current signature of each signed record ("admin:7", "odds:8:2"). A row is
 * accepted only if its signature is the content of the newest validly signed ledger version for it, so an
 * old signed copy put back into the database no longer passes. Entries without a valid signature (written
 * by someone without the key) are ignored; the database user cannot update or delete entries.
 */
@Component
public class IntegrityLedger {

    private record Entry(long version, String content, String signature) {
    }

    private final JdbcClient jdbc;
    private final Integrity integrity;

    public IntegrityLedger(JdbcClient jdbc, Integrity integrity) {
        this.jdbc = jdbc;
        this.integrity = integrity;
    }

    /** Records {@code content} as the newest version of {@code entity}. Callers serialise writes per entity. */
    public void record(String entity, String content) {
        long version = latest(entity).map(Entry::version).orElse(0L) + 1;
        jdbc.sql("INSERT INTO integrity_ledger (entity, version, content, signature, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(entity, version, content, integrity.sign("ledger", entity, version, content), Timestamp.from(Instant.now()))
                .update();
    }

    /** True if {@code content} is the newest validly signed version recorded for {@code entity}. */
    public boolean isLatest(String entity, String content) {
        return content != null && latest(entity).map(e -> e.content().equals(content)).orElse(false);
    }

    /** True if {@code entity} has at least one validly signed version. */
    public boolean exists(String entity) {
        return latest(entity).isPresent();
    }

    /** The content of the newest validly signed version of {@code entity}. */
    public Optional<String> latestContent(String entity) {
        return latest(entity).map(Entry::content);
    }

    /**
     * N8: records the newest valid version of every entity again, signed with the current key, where it was signed
     * with the original one (only what verifies now is carried over). Returns the entities moved.
     */
    public List<String> moveToCurrentKey() {
        if (integrity.signsWithLegacy("ledger")) {
            return List.of();
        }
        List<String> moved = new java.util.ArrayList<>();
        for (String entity : jdbc.sql("SELECT DISTINCT entity FROM integrity_ledger").query(String.class).list()) {
            latest(entity).filter(e -> com.plinko.common.KeyRing.isLegacy(e.signature())).ifPresent(e -> {
                record(entity, e.content());
                moved.add(entity);
            });
        }
        return moved;
    }

    /** Entities whose newest valid version is still signed with the original key. */
    public List<String> onLegacyKey() {
        return jdbc.sql("SELECT DISTINCT entity FROM integrity_ledger").query(String.class).list().stream()
                .filter(entity -> latest(entity).map(e -> com.plinko.common.KeyRing.isLegacy(e.signature())).orElse(false))
                .toList();
    }

    /** Review 3.7: the newest 10 versions first (one of them is normally valid); all of them only if none is. */
    private Optional<Entry> latest(String entity) {
        Optional<Entry> found = newestValid(entity, " LIMIT 10");
        return found.isPresent() ? found : newestValid(entity, "");
    }

    private Optional<Entry> newestValid(String entity, String limit) {
        List<Entry> entries = jdbc.sql("SELECT version, content, signature FROM integrity_ledger WHERE entity = ? ORDER BY version DESC, id DESC" + limit)
                .param(entity)
                .query((rs, n) -> new Entry(rs.getLong("version"), rs.getString("content"), rs.getString("signature")))
                .list();
        return entries.stream()
                .filter(e -> integrity.verify(e.signature(), "ledger", entity, e.version(), e.content()))
                .findFirst();
    }
}
