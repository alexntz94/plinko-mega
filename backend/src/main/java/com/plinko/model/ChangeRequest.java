package com.plinko.model;

import java.time.Instant;

/** A proposed change waiting for (or decided by) a second admin; signed when proposed. */
public record ChangeRequest(long id, String type, String target, String payload, String summary, String status,
                            String requestedBy, Instant requestedAt, String decidedBy, Instant decidedAt, String decisionNote,
                            String baseHash, Instant expiresAt, String signature) {
}
