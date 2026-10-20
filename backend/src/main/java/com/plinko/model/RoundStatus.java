package com.plinko.model;

public final class RoundStatus {

    /** Round created, debit not confirmed yet. */
    public static final String PENDING = "PENDING";
    /** PAM refused the debit (no money moved). */
    public static final String REJECTED = "REJECTED";
    /** Debit confirmed, outcome decided, credit not confirmed yet. */
    public static final String DEBITED = "DEBITED";
    /** Debit and credit confirmed. */
    public static final String COMPLETED = "COMPLETED";
    /** Credit failed; retried in the background with the same transaction id. */
    public static final String CREDIT_PENDING = "CREDIT_PENDING";
    /** Debit outcome unknown and the rollback failed; retried in the background. */
    public static final String ROLLBACK_PENDING = "ROLLBACK_PENDING";
    /** Debit rolled back. */
    public static final String CANCELLED = "CANCELLED";
    /** Signature check failed: round data was changed outside the application. Never paid. */
    public static final String TAMPERED = "TAMPERED";

    private RoundStatus() {
    }
}
