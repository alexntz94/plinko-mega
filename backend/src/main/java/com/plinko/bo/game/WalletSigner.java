package com.plinko.bo.game;

import java.math.BigDecimal;

import com.plinko.common.Integrity;
import com.plinko.repo.GameRepository;
import org.springframework.stereotype.Component;

/**
 * Authorises the money a round may move. When the game decides to pay a win or refund a bet it signs that
 * decision (round, transaction id, PAM player id, amount); the background retry only sends signed credits
 * and rollbacks. Credit and rollback ids are derived from the round, so a round can be paid or refunded at
 * most once even if its rows are copied or reset in the database: the PAM sees the same transaction id again.
 */
@Component
public class WalletSigner {

    private final Integrity integrity;

    public WalletSigner(Integrity integrity) {
        this.integrity = integrity;
    }

    public String creditId(String roundId) {
        return integrity.derivedId("credit", roundId);
    }

    public String rollbackId(String roundId) {
        return integrity.derivedId("rollback", roundId);
    }

    public String signCredit(String roundId, String pamPlayerId, BigDecimal amount, String currency) {
        return integrity.sign("credit", creditId(roundId), roundId, pamPlayerId, amount, currency);
    }

    public String signRollback(String roundId, String pamPlayerId, String debitId) {
        return integrity.sign("rollback", rollbackId(roundId), roundId, pamPlayerId, debitId);
    }

    /** The bet the game asks the wallet to take for a round (N3: the debit row is signed like the credit). */
    public String signDebit(String debitId, String roundId, String pamPlayerId, BigDecimal amount, String currency) {
        return integrity.sign("debit", debitId, roundId, pamPlayerId, amount, currency);
    }

    /**
     * The debit is the round's own, for this player and exactly this amount, as the game signed it. A debit without a
     * signature (recorded before debits were signed) is not judged here: see {@link #debitProblem}.
     */
    public boolean verifyDebit(GameRepository.Transaction tx, GameRepository.Round round, GameRepository.Player player) {
        return "DEBIT".equals(tx.type()) && tx.roundId().equals(round.id()) && tx.playerId() == round.playerId() && player.id() == round.playerId()
                && signed(tx, "debit", tx.id(), round.id(), player.externalId(), tx.amount(), round.currency());
    }

    /**
     * Why a round's debit can't be trusted, or null: a signed debit must verify; an unsigned one is accepted only for a
     * round played before this install signed debits (R9 #3).
     */
    public String debitProblem(GameRepository.Transaction tx, GameRepository.Round round, GameRepository.Player player) {
        if (tx.signature() == null) {
            return unsignedDebitAccepted(round) ? null
                    : "the debit has no signature, and the round was played after debits were signed (from " + debitsSignedFrom() + ")";
        }
        return verifyDebit(tx, round, player) ? null : "the debit is not the round's own signed debit";
    }

    /**
     * Review R9 #3: from when this install signs every debit. The first start of a release that signs debits records it
     * (job_state, once, the earliest instance wins) an hour ahead, for the previous release's instances still taking bets
     * during a rolling deploy; an unsigned debit of a round played after it is not one the game wrote.
     */
    static final String DEBITS_SIGNED_FROM = "debits.signed_from";
    static final java.time.Duration ROLLOUT_GRACE = java.time.Duration.ofHours(1);

    private com.plinko.repo.JobState state;
    private volatile java.time.Instant signedFrom;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJobState(com.plinko.repo.JobState state) {
        this.state = state;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recordDebitSigning() {
        try {
            debitsSignedFrom();
        } catch (RuntimeException e) {
            // read again (and recorded if still missing) when a round's debit is checked
            org.slf4j.LoggerFactory.getLogger(WalletSigner.class).warn("Could not record from when debits are signed yet: {}", e.toString());
        }
    }

    /** From when every debit is signed on this install (null without the job state, i.e. in unit tests). */
    java.time.Instant debitsSignedFrom() {
        if (signedFrom == null && state != null) {
            signedFrom = java.time.Instant.parse(state.putIfAbsent(DEBITS_SIGNED_FROM, java.time.Instant.now().plus(ROLLOUT_GRACE).toString()));
        }
        return signedFrom;
    }

    /** An unsigned debit is accepted only for a round played before debits were signed. */
    public boolean unsignedDebitAccepted(GameRepository.Round round) {
        java.time.Instant from = debitsSignedFrom();
        return from == null || (round.createdAt() != null && round.createdAt().isBefore(from));
    }

    /** The credit is the round's own, for this player, for exactly this amount, and was signed by the game. */
    public boolean verifyCredit(GameRepository.Transaction tx, GameRepository.Round round, GameRepository.Player player) {
        return "CREDIT".equals(tx.type()) && ownId(tx, "credit", round.id()) && tx.roundId().equals(round.id())
                && tx.playerId() == round.playerId() && player.id() == round.playerId()
                // money still to move is never history: once the original key is retired (N8) it never authorises a payment,
                // whatever date the row carries (created_at is not signed, so a backdated row proves nothing)
                && signed(tx, "credit", tx.id(), round.id(), player.externalId(), tx.amount(), round.currency());
    }

    /**
     * Review R6: true if this money is signed with the original key and the key is retired: it may be genuine, but
     * since the cutoff the original key no longer authorises a payment, so it waits for a person.
     */
    public boolean signedWithRetiredKey(GameRepository.Transaction tx) {
        return com.plinko.common.KeyRing.isLegacy(tx.signature()) && integrity.legacyRetired()
                && !(resigned != null && "OK".equals(tx.status()) && resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, tx.id()).isPresent());
    }

    /** When the original key was retired, for messages (empty if no cutoff is set). */
    public java.util.Optional<java.time.Instant> legacyUntil() {
        return integrity.legacyUntil();
    }

    public boolean verifyRollback(GameRepository.Transaction tx, GameRepository.Round round, GameRepository.Player player) {
        return "ROLLBACK".equals(tx.type()) && ownId(tx, "rollback", round.id()) && tx.roundId().equals(round.id())
                && tx.playerId() == round.playerId() && player.id() == round.playerId()
                && signed(tx, "rollback", tx.id(), round.id(), player.externalId(), tx.originalTxId());
    }

    /**
     * The transaction's id is the round's own derived one, under any key held; or (N8 #2) it is a confirmed transaction
     * signed again with a current key (that signature covers the id) whose id was derived with the removed original key.
     */
    private boolean ownId(GameRepository.Transaction tx, String purpose, String roundId) {
        return integrity.isDerivedId(tx.id(), purpose, roundId)
                || (resigned != null && "OK".equals(tx.status()) && resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, tx.id()).isPresent());
    }

    /**
     * The transaction's signature verifies, or (N8 #2) a confirmed one first signed with the original key was signed again
     * with a current key by the re-signing job. Money still to move is never re-signed: only confirmed history is.
     */
    private boolean signed(GameRepository.Transaction tx, String purpose, Object... fields) {
        if (integrity.verify(tx.signature(), purpose, fields)) {
            return true;
        }
        return resigned != null && "OK".equals(tx.status()) && com.plinko.common.KeyRing.isLegacy(tx.signature())
                && resigned.of(com.plinko.repo.ResignedSignatures.TRANSACTIONS, tx.id())
                        .filter(again -> !com.plinko.common.KeyRing.isLegacy(again))
                        .map(again -> integrity.verify(again, purpose, fields))
                        .orElse(false);
    }

    /** N8 #2: the transaction's signature made again with the current key (only for one that verifies now). */
    public String signAgain(String purpose, Object... fields) {
        return integrity.sign(purpose, fields);
    }

    private com.plinko.repo.ResignedSignatures resigned;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResigned(com.plinko.repo.ResignedSignatures resigned) {
        this.resigned = resigned;
    }
}
