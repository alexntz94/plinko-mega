package com.plinko.pam;

/**
 * A failed PAM call. {@code outcomeUnknown} is true when we cannot tell whether the PAM applied the
 * transaction (timeout, 5xx, unreadable or unsigned response); such debits must be rolled back.
 */
public class PamException extends RuntimeException {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";
    /** The casino refuses the bet because one of the player's limits is reached (loss, deposit, time; review 7.4). */
    public static final String LIMIT_REACHED = "LIMIT_REACHED";
    /** The casino refuses the bet because the player is excluded or blocked on its side. */
    public static final String PLAYER_EXCLUDED = "PLAYER_EXCLUDED";
    /** The same, under the name some wallets use. */
    public static final String PLAYER_BLOCKED = "PLAYER_BLOCKED";

    /**
     * Review R10: the only answers that prove the wallet did not apply a transaction (README, PAM API contract). Every
     * other 4xx, and any answer with HTTP 409, may mean the wallet holds the transaction id, so it may have been applied.
     */
    public static final java.util.Set<String> REFUSALS = java.util.Set.of(INSUFFICIENT_FUNDS, INVALID_TOKEN, LIMIT_REACHED, PLAYER_EXCLUDED,
            PLAYER_BLOCKED);

    private final String code;
    private final boolean outcomeUnknown;
    /** The HTTP status of the wallet's answer, or 0 when there was none. */
    private final int httpStatus;

    public PamException(String code, String message, boolean outcomeUnknown) {
        this(code, message, outcomeUnknown, 0);
    }

    public PamException(String code, String message, boolean outcomeUnknown, int httpStatus) {
        super(message);
        this.code = code;
        this.outcomeUnknown = outcomeUnknown;
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }

    /**
     * The error text stored on a failed wallet transaction (for people; whether it was a refusal is stored apart, see
     * {@link #refusal()}).
     */
    public String stored() {
        return code + ": " + getMessage();
    }

    /**
     * True if the PAM definitely refused the transaction, so it did not apply it (review R4: stored in the transaction's
     * own {@code refused} column, never read from the error text). Only a code on the list of refusals ({@link #REFUSALS},
     * R10) is: not an unknown outcome, not any other 4xx, and never an answer with HTTP 409.
     */
    public boolean refusal() {
        return !outcomeUnknown && httpStatus != 409 && REFUSALS.contains(code);
    }

    /** The stored error of a transaction the game never sent to the wallet (stored as refused: certainly not applied). */
    public static String notSent(String why) {
        return "NOT_SENT: " + why;
    }
}
