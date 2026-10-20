package com.plinko.bo.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.audit.AuditService;
import com.plinko.common.Integrity;
import com.plinko.model.RoundStatus;
import com.plinko.pam.PamClient;
import com.plinko.pam.PamException;
import com.plinko.repo.GameRepository;
import com.plinko.repo.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Settling a blocked (TAMPERED) round with the player (review 7.3). The round itself stays blocked, as evidence; what
 * two admins agreed (a change request: one proposes, another approves) is recorded once per round and its money sent
 * with a transaction id derived from the round, so it is paid at most once. Three outcomes only, never a free balance
 * change: PAY an amount (within the limits), REFUND the bet the wallet took, or CLOSE with nothing owed.
 * <p>
 * Money moves only when the wallet certainly did not already move it (a credit or refund of the round whose outcome is
 * unknown may have been applied), and a PAY is the round's own win as recomputed from its signed outcome; any other
 * amount (or a round whose outcome can't be trusted) is held to a lower limit. Limits are per currency: stored settings
 * that change only when a second admin approves (N3 #3).
 */
@Service
public class RoundSettlementService {

    private static final Logger log = LoggerFactory.getLogger(RoundSettlementService.class);

    public static final String PAY = "PAY";
    public static final String REFUND = "REFUND";
    public static final String CLOSE = "CLOSE";
    static final String PAY_TX = "MANUAL_PAY";
    static final String REFUND_TX = "MANUAL_REF";

    /** What an admin proposes; stored, signed, in the change request until a second admin approves it. */
    public record Proposal(String roundId, String action, BigDecimal amount, String reason) {
    }

    private final GameRepository game;
    private final SettlementRepository settlements;
    private final PamClient pam;
    private final Integrity integrity;
    private final AuditService audit;
    private final WinCheck winCheck;
    /** The environment's settlement limits: only the first value of the stored settings (never a limit by themselves, R9 #1). */
    private final Limits initialMax;
    private final Limits initialOther;
    private final com.plinko.bo.settings.SettingsService settings;
    private final Duration maxAge;
    private final Duration alertAfter;
    private final int maxAttempts;

    public RoundSettlementService(GameRepository game, SettlementRepository settlements, PamClient pam, Integrity integrity, AuditService audit,
                                  WinCheck winCheck, com.plinko.bo.settings.SettingsService settings,
                                  @Value("${plinko.settlement.max-amount:1000}") BigDecimal maxAmount,
                                  @Value("${plinko.settlement.max-amounts:${SETTLEMENT_MAX_AMOUNTS:}}") String maxAmounts,
                                  @Value("${plinko.settlement.other-amount:100}") BigDecimal otherAmount,
                                  @Value("${plinko.settlement.other-amounts:${SETTLEMENT_OTHER_AMOUNTS:}}") String otherAmounts,
                                  @Value("${plinko.settlement.max-age-days:90}") int maxAgeDays,
                                  @Value("${plinko.settlement.alert-hours:24}") int alertHours,
                                  @Value("${plinko.retry.max-attempts:10}") int maxAttempts) {
        this.alertAfter = Duration.ofHours(alertHours);
        this.game = game;
        this.settlements = settlements;
        this.pam = pam;
        this.integrity = integrity;
        this.audit = audit;
        this.winCheck = winCheck;
        this.settings = settings;
        this.initialMax = new Limits(maxAmount, maxAmounts);
        this.initialOther = new Limits(otherAmount, otherAmounts);
        this.maxAge = Duration.ofDays(maxAgeDays);
        this.maxAttempts = maxAttempts;
    }

    /**
     * Settlement limits per currency, e.g. {@code EUR=1000,SEK=10000}. The default ({@code plinko.settlement.max-amount} /
     * {@code other-amount}) is only what the platform currency starts with when the limits are first stored; a currency
     * not listed has no limit, and no money is settled in it (R9 #1).
     */
    record Limits(BigDecimal fallback, java.util.Map<String, BigDecimal> perCurrency) {
        Limits(BigDecimal fallback, String list) {
            this(fallback, parse(list));
        }

        Optional<BigDecimal> of(String currency) {
            return Optional.ofNullable(perCurrency.get(currency));
        }

        /** The list as it is stored: the listed currencies, plus the platform currency at the default if it is not listed. */
        String text(String platformCurrency) {
            java.util.Map<String, BigDecimal> all = new java.util.TreeMap<>(perCurrency);
            all.putIfAbsent(platformCurrency, fallback);
            StringBuilder out = new StringBuilder();
            all.forEach((currency, amount) -> out.append(out.isEmpty() ? "" : ",").append(currency).append('=')
                    .append(amount.stripTrailingZeros().toPlainString()));
            return out.toString();
        }

        private static java.util.Map<String, BigDecimal> parse(String list) {
            java.util.Map<String, BigDecimal> limits = new java.util.HashMap<>();
            for (String entry : list == null ? new String[0] : list.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                String[] parts = entry.trim().split("=");
                if (parts.length != 2 || !parts[0].trim().matches("[A-Z]{3}")) {
                    throw new IllegalArgumentException("Settlement limits are written CUR=amount,CUR=amount; got '" + entry.trim() + "'");
                }
                limits.put(parts[0].trim(), new BigDecimal(parts[1].trim()));
            }
            return java.util.Map.copyOf(limits);
        }
    }

    /**
     * N3 #3: the limits are settings that change only when a second admin approves (Settings page); the environment's
     * values are only their first value, stored once (and recorded in the ledger) by the first instance that finds them
     * missing.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void storeInitialLimits() {
        try {
            String platform = settings.currency();
            String max = initialMax.text(platform);
            String other = initialOther.text(platform);
            List<String> problems = com.plinko.bo.settings.SettingsService.settlementLimitProblems(max, other);
            if (!problems.isEmpty()) {
                log.error("Settlement limits from SETTLEMENT_MAX_AMOUNTS / SETTLEMENT_OTHER_AMOUNTS not stored: {}", String.join("; ", problems));
                return;
            }
            if (settings.seedSettlementLimits(max, other)) {
                audit.log("system", "SETTLEMENT_LIMITS_INITIALISED", "Settlement limits stored from the environment: highest " + max
                        + "; other amounts " + other);
            }
        } catch (RuntimeException e) {
            log.warn("Settlement limits not stored yet: {}", e.toString());
        }
    }

    /** The limits in force: the stored ones, or the environment's until they are stored. Refused while the settings are tampered. */
    private Limits limits(String setting, Limits initial) {
        return settings.settlementLimit(setting).map(list -> new Limits(initial.fallback(), list)).orElse(initial);
    }

    /** Both limit lists in force, as the Settings page shows them (TamperedException while the settings are tampered). */
    public com.plinko.bo.settings.SettingsService.SettlementLimits currentLimits() {
        String platform = settings.currency();
        return new com.plinko.bo.settings.SettingsService.SettlementLimits(
                limits(com.plinko.bo.settings.SettingsService.SETTLEMENT_MAX, initialMax).text(platform),
                limits(com.plinko.bo.settings.SettingsService.SETTLEMENT_OTHER, initialOther).text(platform));
    }

    /**
     * The most a settlement of a round in {@code currency} may pay, as stored and approved: empty when no limit is stored
     * for that currency (TamperedException while the settings are tampered).
     */
    public Optional<BigDecimal> maxAmount(String currency) {
        return stored(com.plinko.bo.settings.SettingsService.SETTLEMENT_MAX).flatMap(limits -> limits.of(currency));
    }

    /** The most a PAY that is not the round's own recomputed win may pay; empty when none is stored for the currency. */
    public Optional<BigDecimal> otherAmount(String currency) {
        return stored(com.plinko.bo.settings.SettingsService.SETTLEMENT_OTHER).flatMap(limits -> limits.of(currency));
    }

    /** R9 #1: only the stored limits count (approved by a second admin, or the first value stored and recorded at start-up). */
    private Optional<Limits> stored(String setting) {
        return settings.settlementLimit(setting).map(list -> new Limits(null, list));
    }

    private static String noLimit(String which, String currency) {
        return "No " + which + " is stored for " + currency + ": set one on the Settings page (another admin approves it) before settling in "
                + currency;
    }

    /**
     * The round's win, recomputed from its stored balls, when its outcome is signed and passes every check (C1, C4, C5,
     * C8); empty when it can't be trusted (then only a smaller, explained amount can be paid).
     */
    public Optional<BigDecimal> recomputedWin(GameRepository.Round round) {
        return winCheck.outcomeProblem(round) == null ? Optional.of(round.totalWin()) : Optional.empty();
    }

    /** For the approver: whether a PAY is the round's own recomputed win, and if not, what that win is. */
    public String payNote(String roundId, BigDecimal amount) {
        Optional<BigDecimal> win = game.findRound(roundId).flatMap(this::recomputedWin);
        if (win.isEmpty()) {
            return " (NOT the round's win: its outcome can't be trusted)";
        }
        return win.get().compareTo(amount) == 0 ? " (the round's own win)" : " (NOT the round's win, which is " + win.get().toPlainString() + ")";
    }

    /**
     * Why money of this type might already have moved for the round: a credit or refund that is pending, or failed
     * without the wallet clearly refusing it (a timeout or 5xx may still have been applied). Empty if none.
     */
    private static Optional<String> maybeApplied(List<GameRepository.Transaction> transactions) {
        return transactions.stream()
                .filter(t -> List.of("CREDIT", "ROLLBACK").contains(t.type()) && !"OK".equals(t.status()))
                // R4: only the refused column (set by the wallet's answer, guarded by the database) proves nothing moved
                .filter(t -> !("FAILED".equals(t.status()) && t.refused() && t.inFlightSince() == null))
                .findFirst()
                .map(t -> "The wallet may already have applied this round's " + t.type().toLowerCase() + " " + t.id()
                        + " (its outcome is unknown: " + (t.inFlightSince() != null ? "sent at " + t.inFlightSince() + ", no answer recorded"
                                : t.error() == null ? t.status().toLowerCase() : t.error())
                        + "). Ask the casino to look it up; until the wallet answers it definitely, only closing is possible");
    }

    /** Judges a debit without a signature by when the round was played (R9 #3; optional: absent in some unit tests). */
    private WalletSigner walletSigner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWalletSigner(WalletSigner walletSigner) {
        this.walletSigner = walletSigner;
    }

    /** Why this proposal can't be carried out now, or empty when it can (checked on proposing and again on approval). */
    public Optional<String> problem(Proposal p) {
        if (p.roundId() == null || !p.roundId().matches("[0-9a-f-]{36}")) {
            return Optional.of("Unknown round");
        }
        Optional<GameRepository.Round> found = game.findRound(p.roundId());
        if (found.isEmpty()) {
            return Optional.of("Unknown round");
        }
        GameRepository.Round round = found.get();
        if (!RoundStatus.TAMPERED.equals(round.status())) {
            return Optional.of("Only a blocked (TAMPERED) round can be settled; this one is " + round.status());
        }
        if (settlements.find(round.id()).isPresent()) {
            return Optional.of("This round has already been settled");
        }
        Instant blocked = round.updatedAt() != null ? round.updatedAt() : round.createdAt();
        if (blocked != null && blocked.isBefore(Instant.now().minus(maxAge))) {
            return Optional.of("The round was blocked more than " + maxAge.toDays() + " days ago; settle it with the casino directly");
        }
        if (p.reason() == null || p.reason().trim().length() < 10 || p.reason().length() > 500) {
            return Optional.of("Write what was found and why (10 to 500 characters)");
        }
        List<GameRepository.Transaction> transactions = game.transactionsOfRound(round.id());
        Optional<GameRepository.Transaction> debit = ok(transactions, "DEBIT");
        // N3: a signed debit must be the round's own; one without a signature only for a round played before debits were
        // signed (R9 #3)
        if (debit.isPresent() && debit.get().signature() == null && walletSigner != null && !walletSigner.unsignedDebitAccepted(round)) {
            return Optional.of("The bet's record has no signature, but the round was played after debits were signed; settle it with the casino directly");
        }
        if (debit.isPresent() && debit.get().signature() != null && !game.findPlayer(round.playerId())
                .map(owner -> integrity.verify(debit.get().signature(), "debit", debit.get().id(), round.id(), owner.externalId(), debit.get().amount(),
                        round.currency())).orElse(false)) {
            return Optional.of("The bet's record is not the round's own signed debit; settle it with the casino directly");
        }
        boolean paid = ok(transactions, "CREDIT").isPresent();
        BigDecimal amount;
        try {
            amount = p.amount() == null ? null : p.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            return Optional.of("Amounts have at most 2 decimals");
        }
        if (ok(transactions, "ROLLBACK").isPresent() && !CLOSE.equals(p.action())) {
            return Optional.of("The wallet already refunded this round's bet; only closing it is left");
        }
        if (!CLOSE.equals(p.action())) {
            Optional<String> unknown = maybeApplied(transactions);
            if (unknown.isPresent()) {
                return unknown;
            }
        }
        switch (p.action() == null ? "" : p.action()) {
            case PAY -> {
                Optional<BigDecimal> stored;
                try {
                    stored = maxAmount(round.currency());
                } catch (com.plinko.bo.settings.SettingsService.TamperedException e) {
                    return Optional.of("The settings (the settlement limits among them) were changed outside the admin panel; restore them first");
                }
                if (stored.isEmpty()) {
                    return Optional.of(noLimit("settlement limit", round.currency()));
                }
                if (debit.isEmpty()) {
                    return Optional.of("The wallet never took this round's bet; there is nothing to pay");
                }
                if (paid) {
                    return Optional.of("The wallet already paid this round");
                }
                if (amount == null || amount.signum() <= 0) {
                    return Optional.of("Enter the amount to pay");
                }
                BigDecimal limit = stored.get();
                if (amount.compareTo(limit) > 0) {
                    return Optional.of("Above the settlement limit of " + limit.toPlainString() + " " + round.currency());
                }
                if (round.maxWin() != null && amount.compareTo(round.maxWin()) > 0) {
                    return Optional.of("Above the most this round could ever win (" + round.maxWin().toPlainString() + ")");
                }
                Optional<BigDecimal> win = recomputedWin(round);
                boolean ownWin = win.isPresent() && amount.compareTo(win.get()) == 0;
                Optional<BigDecimal> storedOther = otherAmount(round.currency());
                if (!ownWin && storedOther.isEmpty()) {
                    return Optional.of(noLimit("limit for amounts other than the round's own win", round.currency()));
                }
                BigDecimal other = storedOther.orElse(BigDecimal.ZERO);
                if (!ownWin && amount.compareTo(other) > 0) {
                    return Optional.of(win.map(w -> "The round's own win, recomputed from its signed outcome, is " + w.toPlainString()
                                    + "; a different amount is limited to " + other.toPlainString() + " " + round.currency())
                            .orElse("This round's outcome can't be trusted, so its win can't be recomputed; a payment is limited to "
                                    + other.toPlainString() + " " + round.currency()));
                }
            }
            case REFUND -> {
                try {
                    if (maxAmount(round.currency()).isEmpty()) {
                        return Optional.of(noLimit("settlement limit", round.currency()));
                    }
                } catch (com.plinko.bo.settings.SettingsService.TamperedException e) {
                    return Optional.of("The settings (the settlement limits among them) were changed outside the admin panel; restore them first");
                }
                if (debit.isEmpty()) {
                    return Optional.of("The wallet never took this round's bet; there is nothing to refund");
                }
                if (paid) {
                    return Optional.of("The wallet already paid this round; it can't also be refunded");
                }
                if (debit.get().amount().compareTo(round.totalBet()) != 0) {
                    return Optional.of("The bet the wallet took (" + debit.get().amount().toPlainString() + ") is not the round's total bet ("
                            + round.totalBet().toPlainString() + "); settle it with the casino directly");
                }
                if (amount == null || amount.compareTo(round.totalBet()) != 0) {
                    return Optional.of("A refund is exactly the round's total bet: " + round.totalBet().toPlainString());
                }
            }
            case CLOSE -> {
                if (amount == null || amount.signum() != 0) {
                    return Optional.of("Closing pays nothing: the amount is 0");
                }
            }
            default -> {
                return Optional.of("Choose pay, refund or close");
            }
        }
        return Optional.empty();
    }

    /** The state a proposal was made against: if any of it changes before approval, the proposal is stale. */
    public String state(String roundId) {
        StringBuilder state = new StringBuilder();
        game.findRound(roundId).ifPresent(r -> state.append(r.status()).append('|'));
        settlements.find(roundId).ifPresent(s -> state.append("settled|"));
        game.transactionsOfRound(roundId).forEach(t -> state.append(t.id()).append(':').append(t.status()).append('|'));
        return state.toString();
    }

    /**
     * Carries out an approved proposal, inside the approval's transaction: records the settlement and its transaction.
     * The money is sent once the approval is committed (and retried in the background until the wallet confirms it).
     */
    public void apply(Proposal p, long changeId, String proposedBy, String approvedBy) {
        if (proposedBy == null || proposedBy.equals(approvedBy)) {
            // whatever REQUIRE_SECOND_APPROVER says: money for a blocked round always needs two people
            throw new IllegalArgumentException("A settlement must be approved by a different admin than the one who proposed it");
        }
        problem(p).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        GameRepository.Round round = game.findRound(p.roundId()).orElseThrow();
        GameRepository.Player player = game.findPlayer(round.playerId()).orElseThrow();
        BigDecimal amount = p.amount().setScale(2, RoundingMode.UNNECESSARY);
        String txId = switch (p.action()) {
            case PAY -> integrity.derivedId("manual-pay", round.id());
            case REFUND -> integrity.derivedId("manual-refund", round.id());
            default -> null;
        };
        String status = txId == null ? "DONE" : "PENDING";
        String signature = sign(round.id(), p.action(), amount, txId, changeId, player.externalId(), round.currency());
        settlements.insert(new SettlementRepository.Settlement(round.id(), p.action(), amount, txId, changeId, proposedBy, approvedBy,
                p.reason().trim(), status, signature, null, null));
        if (txId != null) {
            String debitId = ok(game.transactionsOfRound(round.id()), "DEBIT").map(GameRepository.Transaction::id).orElse(null);
            game.insertTransaction(new GameRepository.Transaction(txId, round.id(), round.playerId(), PAY.equals(p.action()) ? PAY_TX : REFUND_TX,
                    amount, "PENDING", REFUND.equals(p.action()) ? debitId : null, null, null, 0, null, null, signature));
        }
        audit.log(approvedBy, "ROUND_SETTLEMENT", round.id() + ": " + p.action() + " " + amount.toPlainString() + " " + round.currency()
                + " (change #" + changeId + ", proposed by " + proposedBy + ")");
        if (txId != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(round.id());
                }
            });
        }
    }

    /** Sends a pending settlement's money (again); the wallet sees the same transaction id every time. */
    public String send(String roundId) {
        String logged = RoundRecoveryService.logContext(roundId);
        try {
            SettlementRepository.Settlement s = settlements.find(roundId).orElse(null);
            if (s == null || !"PENDING".equals(s.status())) {
                return "Nothing to send";
            }
            GameRepository.Round round = game.findRound(roundId).orElseThrow();
            GameRepository.Player player = game.findPlayer(round.playerId()).orElseThrow();
            GameRepository.Transaction tx = game.transactionsOfRound(roundId).stream().filter(t -> t.id().equals(s.txId())).findFirst().orElse(null);
            // only what the two admins approved, unchanged: the settlement and its transaction both carry that signature
            if (tx == null || !integrity.verify(s.signature(), "settlement", fields(roundId, s.action(), s.amount(), s.txId(), s.changeId(),
                    player.externalId(), round.currency())) || !s.signature().equals(tx.signature()) || tx.amount().compareTo(s.amount()) != 0
                    || tx.playerId() != round.playerId()) {
                if (com.plinko.common.KeyRing.isLegacy(s.signature()) && integrity.legacyRetired()) {
                    // R6: approved and signed before the cutoff: genuine, but the retired key no longer authorises it
                    log.error("ALERT SETTLEMENT_SIGNED_WITH_RETIRED_KEY: round {}: the settlement was signed with the original key, retired since {}; "
                            + "not sent: settle it with the casino directly", roundId, integrity.legacyUntil().orElse(null));
                    audit.log("system", "SETTLEMENT_SIGNED_WITH_RETIRED_KEY", roundId + ": signed with the original key before its cutoff; not sent");
                    return "Signed with the retired original key; not sent: settle it with the casino directly";
                }
                log.error("SETTLEMENT INTEGRITY FAILURE: round {} settlement does not match what was approved; not sent", roundId);
                audit.log("system", "SETTLEMENT_INTEGRITY_FAILED", roundId + ": the settlement was changed after approval; not sent");
                return "The settlement was changed after approval; not sent";
            }
            if (!"OK".equals(tx.status())) {
                game.markSending(tx.id());    // R4: committed on its own connection, also when called right after the approval commits
                String token = game.findSession(round.sessionId()).map(GameRepository.Session::pamToken).orElse("");
                PamClient.PamResult result = PAY.equals(s.action())
                        ? pam.credit(new PamClient.WalletRequest(player.externalId(), token, tx.id(), roundId, tx.amount(), round.currency()))
                        : pam.rollback(new PamClient.RollbackRequest(player.externalId(), token, tx.id(), tx.originalTxId(), roundId));
                game.updateTransaction(tx.id(), "OK", result.reference(), null);
                game.updatePlayerBalance(player.id(), result.balance());
            }
            settlements.markDone(roundId);
            audit.log("system", "ROUND_SETTLEMENT_SENT", roundId + ": " + s.action() + " " + s.amount().toPlainString() + " " + round.currency());
            return "Settlement sent to the wallet";
        } catch (PamException e) {
            return failed(roundId, e);
        } catch (RuntimeException e) {
            log.error("Settlement of round {} could not be sent", roundId, e);
            return "Could not be sent; it is retried every few minutes";
        } finally {
            RoundRecoveryService.endLogContext(logged);
        }
    }

    /**
     * Records a failed send on the settlement's transaction. A definite answer (e.g. TRANSACTION_MISMATCH) or the last
     * allowed attempt stops the background retry (the settlement is marked stopped, R11), loudly and once: from then on
     * only a person sends it again.
     */
    private String failed(String roundId, PamException e) {
        SettlementRepository.Settlement s = settlements.find(roundId).orElseThrow();
        GameRepository.Transaction tx = game.transactionsOfRound(roundId).stream().filter(t -> t.id().equals(s.txId())).findFirst().orElseThrow();
        game.updateTransaction(tx.id(), "FAILED", null, e.stored(), e.refusal());
        if (s.stoppedAt() != null || !e.outcomeUnknown() || tx.attempts() + 1 >= maxAttempts) {
            if (settlements.markStopped(roundId)) {
                String why = e.outcomeUnknown() ? "failed " + (tx.attempts() + 1) + " times" : "was refused by the wallet (" + e.code() + ")";
                log.error("ALERT SETTLEMENT_STOPPED: round {}: the settlement {}; automatic retries stopped, needs a person", roundId, why);
                audit.log("system", "SETTLEMENT_RETRIES_STOPPED", roundId + ": the " + s.action() + " of " + s.amount().toPlainString() + " " + why
                        + "; automatic retries stopped. Last error: " + e.code());
            }
            return "The wallet " + (e.outcomeUnknown() ? "did not confirm it (" + e.code() + ")" : "refused it (" + e.code() + ")")
                    + "; automatic retries stopped, check with the casino before sending it again";
        }
        log.warn("Settlement of round {} not confirmed by the wallet yet: {}", roundId, e.code());
        return "The wallet did not confirm it yet (" + e.code() + "); it is retried every few minutes";
    }

    /** N13: rounds whose approved settlement the wallet refused or that used up its attempts (shown in the admin alarm). */
    public List<String> stoppedSettlements() {
        return settlements.stopped(1000).stream().map(SettlementRepository.Settlement::roundId).toList();
    }

    /** A person's "Send to the wallet again": automatic sends go on again (R11), and it is sent now. */
    public String sendAgain(String roundId) {
        settlements.resume(roundId);
        return send(roundId);
    }

    /**
     * Pending settlements are sent again every five minutes until the wallet confirms them or retries stop; the stopped
     * ones are left out by the query, so they never fill the batch (R11).
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void retryPending() {
        game.whileLocked("plinko_settlements", 0, () -> settlements.pending(Instant.now().minusSeconds(60), 50).forEach(s -> send(s.roundId())));
    }

    /** Review 7.3: a blocked round should not wait long for its settlement; an alert names how many are overdue. */
    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    public void alertOverdue() {
        Instant due = Instant.now().minus(alertAfter);
        long overdue = settlements.open(1000).stream().filter(o -> o.blockedAt().isBefore(due)).count();
        if (overdue > 0) {
            log.error("ALERT BLOCKED_ROUND_OLD: {} blocked round(s) waiting more than {} hours for a settlement (Admin -> Dashboard)",
                    overdue, alertAfter.toHours());
        }
    }

    public List<SettlementRepository.OpenRound> open(int limit) {
        return settlements.open(limit);
    }

    public Optional<SettlementRepository.Settlement> find(String roundId) {
        return settlements.find(roundId);
    }

    private String sign(String roundId, String action, BigDecimal amount, String txId, long changeId, String player, String currency) {
        return integrity.sign("settlement", fields(roundId, action, amount, txId, changeId, player, currency));
    }

    private static Object[] fields(String roundId, String action, BigDecimal amount, String txId, long changeId, String player, String currency) {
        return new Object[] {roundId, action, amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), txId, changeId, player, currency};
    }

    private static Optional<GameRepository.Transaction> ok(List<GameRepository.Transaction> transactions, String type) {
        return transactions.stream().filter(t -> type.equals(t.type()) && "OK".equals(t.status())).findFirst();
    }
}
