package com.plinko.bo.changes;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.bonus.BonusService;
import com.plinko.bo.odds.OddsService;
import com.plinko.bo.odds.PlinkoBoard;
import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.security.ApprovalPolicy;
import com.plinko.bo.settings.SettingsService;
import com.plinko.common.Crypto;
import com.plinko.common.Integrity;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.AdminUser;
import com.plinko.model.BonusSettings;
import com.plinko.model.ChangeRequest;
import com.plinko.model.OddsTable;
import com.plinko.repo.ChangeRequestRepository;
import com.plinko.repo.IntegrityLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Four-eyes control: odds and bet limits are never changed directly. One admin proposes a change,
 * a different admin approves it, and only then is it validated again and applied.
 * <p>
 * Each proposal is signed (so its stored content cannot be swapped before approval), expires after
 * {@link #VALID_FOR}, and remembers the state of what it changes: if that state changed in the meantime
 * (e.g. another change to the same table was approved first) approval is refused and it must be proposed again.
 */
@Service
public class ChangeRequestService {

    public static final String ODDS = "ODDS";
    public static final String LIMITS = "LIMITS";
    /** Several odds tables changed together, e.g. "set every table to 96% RTP". */
    public static final String ODDS_BULK = "ODDS_BULK";
    /** Admin-account changes that could let one person control a second ADMIN account. */
    public static final String ADMIN_CREATE = "ADMIN_CREATE";
    public static final String ADMIN_ROLE = "ADMIN_ROLE";
    public static final String ADMIN_2FA_RESET = "ADMIN_2FA_RESET";
    public static final String ADMIN_DISABLE = "ADMIN_DISABLE";
    public static final String ADMIN_ENABLE = "ADMIN_ENABLE";
    /** The platform currency (players' wallets must be in it). */
    public static final String CURRENCY = "CURRENCY";
    /** The paying bonuses, together with every odds table rebuilt so each table's total RTP stays the same. */
    public static final String BONUS = "BONUS";
    /** Every setting put back as last recorded, after they were changed in the database. */
    public static final String SETTINGS_RESTORE = "SETTINGS_RESTORE";
    /** Settling a blocked round with the player: pay, refund or close (review 7.3). */
    public static final String ROUND_SETTLE = "ROUND_SETTLE";
    /** Player protections made weaker (N14.9): Hold & Drop on, a reality check later or off, a shorter round time... */
    public static final String FEATURES = "FEATURES";
    /**
     * N3 #3: the settlement limits per currency (always approved by an admin other than the proposer). Stored as
     * SETTLE_LIMITS: the type column holds 16 characters.
     */
    public static final String SETTLEMENT_LIMITS = "SETTLE_LIMITS";

    public record SettingsRestore(String fingerprint) {
    }

    public record BonusChange(BonusSettings settings, List<OddsTable> tables) {
    }

    public record CurrencyChange(String code) {
    }

    static final Duration VALID_FOR = Duration.ofDays(7);

    public record AdminCreate(String username, String role, String passwordHash) {
    }

    public record AdminRole(String username, String role) {
    }

    public record AdminTarget(String username) {
    }

    /** Thrown with a message that can be shown to the admin. */
    public static class ChangeException extends RuntimeException {
        public ChangeException(String message) {
            super(message);
        }
    }

    private final ChangeRequestRepository repository;
    private final ObjectMapper json;
    private final OddsService odds;
    private final SettingsService settings;
    private final AuditService audit;
    private final AdminUserService users;
    private final ApprovalPolicy policy;
    private final Integrity integrity;
    private final IntegrityLedger ledger;
    private final BonusService bonus;
    private final com.plinko.bo.game.RoundSettlementService settlement;
    private final boolean requireSecondApprover;

    public ChangeRequestService(ChangeRequestRepository repository, ObjectMapper json, OddsService odds, SettingsService settings,
                                AuditService audit, AdminUserService users, ApprovalPolicy policy, Integrity integrity,
                                IntegrityLedger ledger, PlinkoProperties properties, BonusService bonus,
                                com.plinko.bo.game.RoundSettlementService settlement) {
        this.repository = repository;
        this.settlement = settlement;
        this.bonus = bonus;
        this.json = json;
        this.odds = odds;
        this.settings = settings;
        this.audit = audit;
        this.users = users;
        this.policy = policy;
        this.integrity = integrity;
        this.ledger = ledger;
        this.requireSecondApprover = properties.requireSecondApprover();
    }

    @Transactional
    public long proposeOdds(OddsTable table, String username) {
        List<String> errors = odds.validate(table);
        if (!errors.isEmpty()) {
            throw new ChangeException(String.join("; ", errors));
        }
        OddsTable current = odds.get(table.rows(), table.risk());
        // current is null when the stored table failed its integrity check; approving this proposal restores it.
        String summary = String.format("%d rows / %s risk: RTP %s%% -> %s%%; multipliers %s -> %s; probabilities %s -> %s",
                table.rows(), PlinkoBoard.RISK_NAMES[table.risk()],
                current == null ? "BLOCKED " : current.rtp().setScale(2, RoundingMode.HALF_UP), table.rtp().setScale(2, RoundingMode.HALF_UP),
                current == null ? "[blocked]" : multipliers(current), multipliers(table),
                current == null ? "[blocked]" : probabilities(current), probabilities(table));
        return propose(ODDS, table.rows() + ":" + table.risk(), table, summary, username);
    }

    /** One proposal for several tables (all applied together on approval). */
    @Transactional
    public long proposeOddsBulk(List<OddsTable> tables, String description, String username) {
        if (tables.isEmpty()) {
            throw new ChangeException("No tables to change");
        }
        for (OddsTable table : tables) {
            List<String> errors = odds.validate(table);
            if (!errors.isEmpty()) {
                throw new ChangeException(table.rows() + " rows / " + PlinkoBoard.RISK_NAMES[table.risk()] + ": " + String.join("; ", errors));
            }
        }
        StringBuilder summary = new StringBuilder(description).append(':');
        for (OddsTable table : tables) {
            OddsTable current = odds.get(table.rows(), table.risk());
            summary.append(String.format("%n%d rows / %s: RTP %s%% -> %s%%, top prize %sx -> %sx",
                    table.rows(), PlinkoBoard.RISK_NAMES[table.risk()],
                    current == null ? "BLOCKED " : current.rtp().setScale(2, RoundingMode.HALF_UP), table.rtp().setScale(2, RoundingMode.HALF_UP),
                    current == null ? "?" : current.maxMultiplier().stripTrailingZeros().toPlainString(), table.maxMultiplier().stripTrailingZeros().toPlainString()));
        }
        return propose(ODDS_BULK, "bulk", tables, summary.toString(), username);
    }

    @Transactional
    public long proposeAdminCreate(String username, String password, String role, String proposer) {
        List<String> problems = users.validateNew(username, password, role);
        if (!problems.isEmpty()) {
            throw new ChangeException(String.join("; ", problems));
        }
        if (pendingFor(ADMIN_CREATE, username).isPresent()) {
            throw new ChangeException("Creating " + username + " is already waiting for approval");
        }
        return propose(ADMIN_CREATE, username, new AdminCreate(username, role, users.hash(password)),
                "Create admin account " + username + " with role " + role, proposer);
    }

    @Transactional
    public long proposeAdminRole(String username, String role, String proposer) {
        requireReadyForPromotion(username, role);
        return propose(ADMIN_ROLE, username, new AdminRole(username, role), "Change role of " + username + " to " + role, proposer);
    }

    @Transactional
    public long proposeAdmin2faReset(String username, String proposer) {
        return propose(ADMIN_2FA_RESET, username, new AdminTarget(username),
                "Reset two-factor authentication of " + username + " (they enrol a new phone at next login)", proposer);
    }

    @Transactional
    public long proposeAdminDisable(String username, String proposer) {
        return propose(ADMIN_DISABLE, username, new AdminTarget(username), "Disable admin account " + username, proposer);
    }

    @Transactional
    public long proposeAdminEnable(String username, String proposer) {
        return propose(ADMIN_ENABLE, username, new AdminTarget(username), "Enable account " + username + " again", proposer);
    }

    /**
     * Only an account its owner has fully taken over (own 2FA, own password) can be given the right to propose
     * or approve; otherwise whoever knows its temporary password could be the one using it.
     */
    public void requireReadyForPromotion(String username, String role) {
        if ("VIEWER".equals(role)) {
            return;
        }
        AdminUser user = users.find(username).orElseThrow(() -> new ChangeException("Unknown admin"));
        boolean promotion = AdminUserService.ROLES.indexOf(role) > AdminUserService.ROLES.indexOf(user.role());
        if (promotion && (!user.enrolled() || user.mustChangePassword())) {
            throw new ChangeException(username + " must first sign in, set up 2FA and choose their own password before getting the " + role + " role");
        }
    }

    /**
     * N14.9: game features that protect players less than now take effect only after a second admin approves; making
     * them stricter stays instant (the controller applies it directly).
     */
    @Transactional
    public long proposeFeatures(SettingsService.GameFeatures next, String username) {
        if (!next.problems().isEmpty()) {
            throw new ChangeException(String.join("; ", next.problems()));
        }
        SettingsService.GameFeatures now = settings.features();
        return propose(FEATURES, "features", next, String.format("Player protections: Hold & Drop %s -> %s, session clock %s -> %s, "
                        + "reality check %d -> %d min, small wins quiet %s -> %s, minimum round time %s -> %s s, reality check proof required %s -> %s",
                onOff(now.holdToDrop()), onOff(next.holdToDrop()), onOff(now.sessionClock()), onOff(next.sessionClock()),
                now.realityCheckMinutes(), next.realityCheckMinutes(), onOff(now.quietSmallWins()), onOff(next.quietSmallWins()),
                now.minRoundSeconds().toPlainString(), next.minRoundSeconds().toPlainString(),
                onOff(now.requireRealityCheckProof()), onOff(next.requireRealityCheckProof())), username);
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    @Transactional
    public long proposeCurrency(String code, String username) {
        if (!com.plinko.common.Currencies.OFFERED.contains(code)) {
            throw new ChangeException("Unsupported currency");
        }
        if (code.equals(settings.currency())) {
            throw new ChangeException("The platform already uses " + code);
        }
        return propose(CURRENCY, "currency", new CurrencyChange(code),
                "Platform currency: " + settings.currency() + " -> " + code + ". Players whose wallet is in another currency can no longer play.",
                username);
    }

    /**
     * New bonus settings. Every odds table is rebuilt for the new bonuses at the total RTP it has now, so switching
     * a bonus on or off moves money between the base game and the bonus, never the total; both are approved together.
     */
    @Transactional
    public long proposeBonus(BonusSettings next, String username) {
        List<String> errors = bonus.validate(next);
        if (!errors.isEmpty()) {
            throw new ChangeException(String.join("; ", errors));
        }
        BonusSettings now = bonus.current();
        if (now.equals(next)) {
            throw new ChangeException("These are the bonus settings already in use");
        }
        List<OddsTable> tables = new java.util.ArrayList<>();
        for (int rows = PlinkoBoard.MIN_ROWS; rows <= PlinkoBoard.MAX_ROWS; rows++) {
            for (int risk = 0; risk < PlinkoBoard.RISK_LEVELS; risk++) {
                OddsTable current = odds.get(rows, risk);
                if (current == null) {
                    throw new ChangeException("Fix the odds tables first: " + rows + " rows / " + PlinkoBoard.RISK_NAMES[risk] + " is blocked");
                }
                java.math.BigDecimal total = BonusService.totalRtp(current, now).setScale(2, RoundingMode.HALF_UP);
                OddsTable rebuilt = odds.forTargetRtp(rows, risk, total, next);
                List<String> problems = new java.util.ArrayList<>(odds.validate(rebuilt, next));
                if (BonusService.factor(next, rebuilt).compareTo(BonusService.MAX_FACTOR) > 0) {
                    problems.add("bonuses would add " + BonusService.factor(next, rebuilt).subtract(java.math.BigDecimal.ONE).movePointRight(2)
                            .setScale(2, RoundingMode.HALF_UP) + "% on top of this table, more than the "
                            + BonusService.MAX_FACTOR.subtract(java.math.BigDecimal.ONE).movePointRight(2).stripTrailingZeros().toPlainString() + "% allowed");
                }
                if (!problems.isEmpty()) {
                    throw new ChangeException(rows + " rows / " + PlinkoBoard.RISK_NAMES[risk] + ": " + String.join("; ", problems));
                }
                tables.add(rebuilt);
            }
        }
        String summary = "Bonuses: " + describe(now) + " -> " + describe(next) + ". All 21 odds tables rebuilt so each keeps its total RTP"
                + " (bonuses add " + BonusService.shareText(next, tables) + " on top of the base game).";
        return propose(BONUS, "bonus", new BonusChange(next, tables), summary, username);
    }

    private static String describe(BonusSettings settings) {
        String golden = !settings.goldenOn() ? "golden ball off"
                : "golden ball " + settings.golden().chance().stripTrailingZeros().toPlainString() + "% per ball, boosts "
                + settings.golden().boosts().stream()
                .map(b -> b.multiplier().stripTrailingZeros().toPlainString() + "x " + b.weight().stripTrailingZeros().toPlainString() + "%")
                .collect(Collectors.joining(", "));
        String hot = !settings.hotOn() ? "hot slot off"
                : "hot slot in " + settings.hot().chance().stripTrailingZeros().toPlainString() + "% of rounds, x"
                + settings.hot().boost().stripTrailingZeros().toPlainString();
        String meter = !settings.meterOn() ? "meter off"
                : "meter: " + settings.meter().charges() + " charges from " + settings.meter().minMultiplier().stripTrailingZeros().toPlainString()
                + "x prizes, " + settings.meter().potShare().stripTrailingZeros().toPlainString() + "% of the bet to the pot, "
                + settings.meter().freeBalls() + " free balls";
        return golden + "; " + hot + "; " + meter;
    }

    @Transactional
    public long proposeLimits(SettingsService.GameLimits limits, String username) {
        List<String> errors = settings.validate(limits);
        if (!errors.isEmpty()) {
            throw new ChangeException(String.join("; ", errors));
        }
        SettingsService.GameLimits current = settings.limits();
        String summary = String.format("Bet limits: min %s -> %s, max %s -> %s, default %s -> %s, max balls %d -> %d",
                current.minBet(), limits.minBet(), current.maxBet(), limits.maxBet(), current.defaultBet(), limits.defaultBet(),
                current.maxBalls(), limits.maxBalls());
        return propose(LIMITS, "limits", limits, summary, username);
    }

    /**
     * N3 #3: new settlement limits per currency ({@code CUR=amount,...}): the most a settlement may pay, and the most a
     * payment that is not the round's own recomputed win may pay. They take effect when another admin approves.
     */
    @Transactional
    public long proposeSettlementLimits(String maxAmounts, String otherAmounts, String username) {
        List<String> problems = SettingsService.settlementLimitProblems(maxAmounts, otherAmounts);
        if (!problems.isEmpty()) {
            throw new ChangeException(String.join("; ", problems));
        }
        SettingsService.SettlementLimits next = new SettingsService.SettlementLimits(SettingsService.normalSettlementLimits(maxAmounts),
                SettingsService.normalSettlementLimits(otherAmounts));
        SettingsService.SettlementLimits now;
        try {
            now = settlement.currentLimits();
        } catch (SettingsService.TamperedException e) {
            throw new ChangeException(e.getMessage());
        }
        if (now.equals(next)) {
            throw new ChangeException("These are the settlement limits already in use");
        }
        return propose(SETTLEMENT_LIMITS, "settlement-limits", next, "Settlement limits: highest payment " + now.maxAmounts() + " -> "
                + next.maxAmounts() + "; any amount other than the round's own win " + now.otherAmounts() + " -> " + next.otherAmounts(), username);
    }

    /**
     * Proposes settling a blocked round (review 7.3): pay an amount, refund the bet or close it with nothing owed. A
     * second admin approves; the limits are checked now and again on approval.
     */
    @Transactional
    public long proposeSettlement(com.plinko.bo.game.RoundSettlementService.Proposal proposal, String username) {
        settlement.problem(proposal).ifPresent(problem -> {
            throw new ChangeException(problem);
        });
        java.math.BigDecimal amount = proposal.amount().setScale(2, RoundingMode.UNNECESSARY);
        com.plinko.bo.game.RoundSettlementService.Proposal clean = new com.plinko.bo.game.RoundSettlementService.Proposal(
                proposal.roundId(), proposal.action(), amount, proposal.reason().trim());
        String summary = switch (proposal.action()) {
            case com.plinko.bo.game.RoundSettlementService.PAY -> "Settle blocked round " + proposal.roundId() + ": pay " + amount.toPlainString()
                    + settlement.payNote(proposal.roundId(), amount);
            case com.plinko.bo.game.RoundSettlementService.REFUND -> "Settle blocked round " + proposal.roundId() + ": refund the bet of "
                    + amount.toPlainString();
            default -> "Settle blocked round " + proposal.roundId() + ": close, nothing owed";
        } + ". Reason: " + shorten(clean.reason());
        return propose(ROUND_SETTLE, settlementTarget(proposal.roundId()), clean, summary, username);
    }

    /** The change request's target for a round's settlement: its id without dashes (32 characters, the column's size). */
    public static String settlementTarget(String roundId) {
        return roundId.replace("-", "");
    }

    /** Proposes putting back the last recorded settings; only while the stored ones fail their check. */
    @Transactional
    public long proposeSettingsRestore(String username) {
        if (settings.trusted()) {
            throw new ChangeException("The settings match the recorded version; there is nothing to restore");
        }
        List<SettingsService.Difference> differences = settings.differences();
        String fingerprint = settings.recordedFingerprint().orElse(null);
        if (fingerprint == null || differences.isEmpty()) {
            throw new ChangeException("No copy of the recorded settings is kept (they were changed before this version "
                    + "of the server); put them back in the database by hand");
        }
        String summary = "Restore settings: " + differences.stream()
                .map(d -> d.name() + " " + shorten(d.stored()) + " -> " + shorten(d.recorded()))
                .collect(Collectors.joining("; "));
        return propose(SETTINGS_RESTORE, "settings", new SettingsRestore(fingerprint), summary, username);
    }

    private static String shorten(String value) {
        if (value == null) {
            return "(none)";
        }
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    @Transactional
    public void approve(long id, String username, String note) {
        ChangeRequest request = pending(id);
        if (!integrity.verify(request.signature(), "change", signedFields(request))
                || !ledger.isLatest("change:" + id, decisionState("PENDING", request.signature()))) {
            audit.log("system", "CHANGE_INTEGRITY_FAILED", "#" + id + " was modified outside the admin panel; approval refused");
            throw new ChangeException("This change was modified outside the admin panel and cannot be approved; reject it");
        }
        if (request.expiresAt() == null || request.expiresAt().isBefore(Instant.now())) {
            throw new ChangeException("This change has expired; reject it and propose it again");
        }
        // R9 #2: a settlement and its limits always need a second person, linked accounts excluded, whatever the switch says
        boolean money = ROUND_SETTLE.equals(request.type()) || SETTLEMENT_LIMITS.equals(request.type());
        (money ? policy.settlementApprovalProblem(request.requestedBy(), username) : policy.approvalProblem(request.requestedBy(), username))
                .ifPresent(problem -> {
                    throw new ChangeException(problem);
                });
        // Agreeing to one's own removal or demotion is fine; approving a reset of one's own 2FA is not.
        if (ADMIN_2FA_RESET.equals(request.type()) && request.target().equals(username)) {
            throw new ChangeException("You cannot approve a reset of your own 2FA");
        }
        // like a settlement itself: whatever REQUIRE_SECOND_APPROVER says, the limits on paying blocked rounds need two people
        if (SETTLEMENT_LIMITS.equals(request.type()) && request.requestedBy().equals(username)) {
            throw new ChangeException("A change to the settlement limits must be approved by an admin other than the one who proposed it");
        }
        if (!baseHash(request.type(), request.target(), request.payload()).equals(request.baseHash())) {
            throw new ChangeException("What this change modifies has changed since it was proposed; reject it and propose it again");
        }
        try {
            switch (request.type()) {
                case ODDS -> odds.apply(json.readValue(request.payload(), OddsTable.class));
                case ODDS_BULK -> json.readValue(request.payload(), new TypeReference<List<OddsTable>>() { }).forEach(odds::apply);
                case ADMIN_CREATE -> {
                    AdminCreate create = json.readValue(request.payload(), AdminCreate.class);
                    if (users.find(create.username()).isPresent()) {
                        throw new ChangeException("Username already exists");
                    }
                    users.createWithHash(create.username(), create.passwordHash(), create.role(), request.requestedBy());
                }
                case ADMIN_ROLE -> {
                    AdminRole role = json.readValue(request.payload(), AdminRole.class);
                    requireReadyForPromotion(role.username(), role.role());
                    users.setRole(role.username(), role.role());
                }
                case ADMIN_ENABLE -> users.setEnabled(json.readValue(request.payload(), AdminTarget.class).username(), true);
                case ADMIN_2FA_RESET -> users.resetTotp(json.readValue(request.payload(), AdminTarget.class).username(), request.requestedBy());
                case ADMIN_DISABLE -> users.setEnabled(json.readValue(request.payload(), AdminTarget.class).username(), false);
                case LIMITS -> settings.applyLimits(json.readValue(request.payload(), SettingsService.GameLimits.class));
                case CURRENCY -> settings.setCurrency(json.readValue(request.payload(), CurrencyChange.class).code());
                case FEATURES -> {
                    SettingsService.GameFeatures features = json.readValue(request.payload(), SettingsService.GameFeatures.class);
                    if (!features.problems().isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", features.problems()));
                    }
                    settings.setFeatures(features);
                }
                case SETTINGS_RESTORE -> settings.restore(json.readValue(request.payload(), SettingsRestore.class).fingerprint());
                case SETTLEMENT_LIMITS -> {
                    SettingsService.SettlementLimits limits = json.readValue(request.payload(), SettingsService.SettlementLimits.class);
                    settings.setSettlementLimits(limits.maxAmounts(), limits.otherAmounts());
                }
                case ROUND_SETTLE -> settlement.apply(json.readValue(request.payload(), com.plinko.bo.game.RoundSettlementService.Proposal.class),
                        request.id(), request.requestedBy(), username);
                case BONUS -> {
                    BonusChange change = json.readValue(request.payload(), BonusChange.class);
                    List<String> problems = bonus.validate(change.settings());
                    if (!problems.isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", problems));
                    }
                    if (change.tables() == null || change.tables().size() != PlinkoBoard.RISK_LEVELS * (PlinkoBoard.MAX_ROWS - PlinkoBoard.MIN_ROWS + 1)) {
                        throw new IllegalArgumentException("the change must contain all odds tables");
                    }
                    // settings first: the tables are then checked against the hard limits with the new bonuses
                    settings.setBonus(bonus.toJson(change.settings()));
                    change.tables().forEach(odds::apply);
                }
                default -> throw new ChangeException("Unknown change type");
            }
        } catch (JacksonException e) {
            throw new ChangeException("Stored change is unreadable");
        } catch (IllegalArgumentException e) {
            throw new ChangeException("Change no longer passes validation: " + e.getMessage());
        } catch (AdminUserService.IntegrityException | SettingsService.TamperedException e) {
            throw new ChangeException(e.getMessage());
        }
        decide(request, "APPROVED", username, note);
        audit.log(username, "CHANGE_APPROVED", "#" + id + " (proposed by " + request.requestedBy() + "): " + request.summary());
        policy.activateIfReady();
    }

    @Transactional
    public void reject(long id, String username, String note) {
        ChangeRequest request = pending(id);
        String status = request.requestedBy().equals(username) ? "CANCELLED" : "REJECTED";
        decide(request, status, username, note);
        audit.log(username, "CHANGE_" + status, "#" + id + ": " + request.summary());
    }

    public List<ChangeRequest> pending() {
        return repository.pending();
    }

    public List<ChangeRequest> recent(int limit) {
        return repository.recent(limit);
    }

    public Optional<ChangeRequest> pendingFor(String type, String target) {
        return repository.pendingFor(type, target);
    }

    public boolean requireSecondApprover() {
        return requireSecondApprover;
    }

    private long propose(String type, String target, Object payload, String summary, String username) {
        if (pendingFor(type, target).isPresent()) {
            throw new ChangeException("There is already a pending change for this; approve, reject or cancel it first");
        }
        String payloadJson = json.writeValueAsString(payload);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expires = now.plus(VALID_FOR);
        String base = baseHash(type, target, payloadJson);
        ChangeRequest draft = new ChangeRequest(0, type, target, payloadJson, summary, "PENDING", username, now, null, null, null,
                base, expires, null);
        long id = repository.insert(type, target, payloadJson, summary, username, now, base, expires,
                integrity.sign("change", signedFields(draft)));
        // The ledger pins this request's state: a decided request set back to PENDING, or a copied row, is refused.
        ledger.record("change:" + id, decisionState("PENDING", integrity.sign("change", signedFields(draft))));
        audit.log(username, "CHANGE_PROPOSED", "#" + id + ": " + summary);
        return id;
    }

    private ChangeRequest pending(long id) {
        ChangeRequest request = repository.lock(id)
                .orElseThrow(() -> new ChangeException("Change request not found"));
        if (!"PENDING".equals(request.status())) {
            throw new ChangeException("Change request was already " + request.status().toLowerCase());
        }
        return request;
    }

    private static String decisionState(String status, String signature) {
        return Crypto.sha256Hex(status + "|" + signature);
    }

    private void decide(ChangeRequest request, String status, String username, String note) {
        ledger.record("change:" + request.id(), decisionState(status, request.signature()));
        repository.decide(request.id(), status, username, Instant.now(), note == null || note.length() <= 500 ? note : note.substring(0, 500));
    }

    private static Object[] signedFields(ChangeRequest request) {
        return new Object[] {request.type(), request.target(), request.payload(), request.summary(), request.requestedBy(),
                request.requestedAt().toEpochMilli(), request.baseHash(), request.expiresAt() == null ? null : request.expiresAt().toEpochMilli()};
    }

    /** Hash of the current state of whatever the change modifies. */
    private String baseHash(String type, String target, String payload) {
        StringBuilder state = new StringBuilder(type).append('|');
        try {
            switch (type) {
                case ODDS -> {
                    OddsTable table = json.readValue(payload, OddsTable.class);
                    state.append(OddsService.fingerprint(odds.get(table.rows(), table.risk())));
                }
                case ODDS_BULK -> {
                    for (OddsTable table : json.readValue(payload, new TypeReference<List<OddsTable>>() { })) {
                        state.append(OddsService.fingerprint(odds.get(table.rows(), table.risk()))).append('|');
                    }
                }
                case LIMITS -> state.append(settings.limits());
                case CURRENCY -> state.append(settings.currency());
                case FEATURES -> state.append(settings.features());
                case SETTLEMENT_LIMITS -> {
                    try {
                        state.append(settlement.currentLimits());
                    } catch (SettingsService.TamperedException e) {
                        state.append("tampered");
                    }
                }
                // what the approver saw: any further edit in the database makes the proposal stale
                case SETTINGS_RESTORE -> state.append(settings.storedFingerprint());
                case ROUND_SETTLE -> state.append(settlement.state(json.readValue(payload, com.plinko.bo.game.RoundSettlementService.Proposal.class).roundId()));
                case BONUS -> {
                    state.append(bonus.toJson(bonus.current())).append('|');
                    for (OddsTable table : odds.all().stream().sorted(java.util.Comparator.comparingInt(OddsTable::rows).thenComparingInt(OddsTable::risk)).toList()) {
                        state.append(OddsService.fingerprint(table)).append('|');
                    }
                }
                default -> state.append(users.find(target)
                        .map(u -> u.role() + "/" + u.enabled() + "/" + u.enrolled() + "/" + u.securityStamp())
                        .orElse("absent"));
            }
        } catch (JacksonException e) {
            state.append("unreadable");
        }
        return Crypto.sha256Hex(state.toString());
    }

    private static String multipliers(OddsTable table) {
        return table.prizes().stream().map(p -> p.multiplier().stripTrailingZeros().toPlainString()).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String probabilities(OddsTable table) {
        return table.prizes().stream().map(p -> p.probability().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
