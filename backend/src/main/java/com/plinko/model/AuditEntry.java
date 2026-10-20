package com.plinko.model;

import java.time.Instant;

/** One audit log row; {@code hash} covers {@code prevHash} and the entry, chaining every entry to the one before. */
public record AuditEntry(long id, String username, String action, String details, Instant createdAt, String prevHash, String hash) {
}
