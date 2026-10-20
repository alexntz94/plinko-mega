package com.plinko.model;

import java.time.Instant;

/** A "forgot password" request waiting for an admin. */
public record ResetRequest(long id, String username, String ip, Instant requestedAt) {
}
