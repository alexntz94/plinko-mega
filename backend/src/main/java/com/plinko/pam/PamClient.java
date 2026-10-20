package com.plinko.pam;

import java.math.BigDecimal;

/**
 * Seamless-wallet connection to the operator's Player Account Management system.
 * Every money movement carries our transaction id; the PAM must treat a repeated id as the same
 * transaction (idempotency) so retries never move money twice.
 */
public interface PamClient {

    PamPlayer authenticate(String token);

    BigDecimal balance(String playerId, String token);

    PamResult debit(WalletRequest request);

    PamResult credit(WalletRequest request);

    PamResult rollback(RollbackRequest request);

    /**
     * Review 7.1: the wallet's own record of this game's money movements in [from, to), a page at a time ({@code after}:
     * the last transaction id of the previous page), to check them against the game's after a backup is restored. An
     * optional part of the contract: a wallet without it answers 404.
     */
    default TransactionPage transactions(java.time.Instant from, java.time.Instant to, String after) {
        throw new PamException("NOT_SUPPORTED", "This wallet does not list transactions", false);
    }

    record PamTransaction(String transactionId, String type, String roundId, String playerId, BigDecimal amount, java.time.Instant time) {
    }

    record TransactionPage(java.util.List<PamTransaction> transactions, String next) {
    }

    record PamPlayer(String playerId, String username, String currency, BigDecimal balance) {
    }

    record PamResult(BigDecimal balance, String reference) {
    }

    record WalletRequest(String playerId, String token, String transactionId, String roundId, BigDecimal amount, String currency) {
    }

    record RollbackRequest(String playerId, String token, String transactionId, String originalTransactionId, String roundId) {
    }
}
