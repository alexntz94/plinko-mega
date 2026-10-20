package com.plinko.bo.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.plinko.model.AuditEntry;
import com.plinko.repo.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Review 5.4: the audit log's hash chain finds every kind of edit, and the incremental check agrees with the full one. */
class AuditServiceTest {

    /** The audit table in memory: entries in id order, edited freely by the tests (as someone with database access could). */
    static final class Table extends AuditRepository {
        final List<AuditEntry> rows = new ArrayList<>();
        long nextId = 1;

        Table() {
            super(null, null);
        }

        @Override
        public void appendChained(String username, String action, String details, Instant createdAt, String genesis, UnaryOperator<String> hashAfter) {
            String prev = rows.isEmpty() ? genesis : rows.get(rows.size() - 1).hash();
            rows.add(new AuditEntry(nextId++, username, action, details, createdAt, prev, hashAfter.apply(prev)));
        }

        @Override
        public void forEachAfter(long afterId, Consumer<AuditEntry> each) {
            rows.stream().filter(e -> e.id() > afterId).forEach(each);
        }

        @Override
        public void forEachInOrder(Consumer<AuditEntry> each) {
            rows.forEach(each);
        }

        @Override
        public boolean hasAction(String action) {
            return rows.stream().anyMatch(e -> e.action().equals(action));
        }
    }

    private Table table;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        table = new Table();
        audit = new AuditService(table);
        for (int i = 0; i < 10; i++) {
            audit.log("alice", "ACTION_" + i, "details " + i);
        }
    }

    private AuditEntry row(int index) {
        return table.rows.get(index);
    }

    @Test
    void anUntouchedChainIsIntact() {
        assertThat(audit.verifyChain().intact()).isTrue();
        assertThat(audit.verifyChain().entries()).isEqualTo(10);
        assertThat(audit.happened("ACTION_3")).isTrue();
    }

    @Test
    void anEditedEntryIsFound() {
        AuditEntry e = row(4);
        table.rows.set(4, new AuditEntry(e.id(), e.username(), e.action(), "details changed", e.createdAt(), e.prevHash(), e.hash()));
        assertThat(audit.verifyChain().brokenAtId()).isEqualTo(e.id());
    }

    @Test
    void aDeletedEntryIsFound() {
        long next = row(5).id();
        table.rows.remove(4);
        assertThat(audit.verifyChain().brokenAtId()).isEqualTo(next);
    }

    @Test
    void reorderedEntriesAreFound() {
        AuditEntry a = row(3);
        AuditEntry b = row(6);
        table.rows.set(3, new AuditEntry(a.id(), b.username(), b.action(), b.details(), b.createdAt(), b.prevHash(), b.hash()));
        table.rows.set(6, new AuditEntry(b.id(), a.username(), a.action(), a.details(), a.createdAt(), a.prevHash(), a.hash()));
        assertThat(audit.verifyChain().intact()).isFalse();
    }

    @Test
    void anInsertedEntryIsFoundEvenWithAFreshHash() {
        // someone computes a valid-looking hash for their own row: the next entry no longer links to it
        AuditEntry before = row(4);
        table.rows.add(5, new AuditEntry(100, "mallory", "CHANGE_APPROVED", "#1", Instant.now(), before.hash(),
                com.plinko.common.Crypto.sha256Hex("anything")));
        assertThat(audit.verifyChain().intact()).isFalse();
    }

    @Test
    void theIncrementalCheckAgreesWithTheFullOne() {
        audit.verifyAll();
        for (int i = 0; i < 5; i++) {
            audit.log("bob", "MORE", "entry " + i);
        }
        audit.verifyNew();
        assertThat(audit.chainStatus()).isEqualTo(audit.verifyChain());
        // a new entry edited after it was written: the incremental check finds it too
        audit.log("bob", "LAST", "x");
        AuditEntry last = table.rows.get(table.rows.size() - 1);
        table.rows.set(table.rows.size() - 1, new AuditEntry(last.id(), last.username(), last.action(), "y", last.createdAt(), last.prevHash(), last.hash()));
        audit.verifyNew();
        assertThat(audit.chainStatus().brokenAtId()).isEqualTo(last.id());
    }
}
