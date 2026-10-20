package com.plinko.bo.security;

import java.util.List;
import java.util.Optional;

import com.plinko.bo.audit.AuditService;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.AdminUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Who may approve what, so that no single person can change odds, limits or admin access alone.
 * <ul>
 * <li>Four-eyes switches on for good the moment a second ADMIN has enrolled 2FA (recorded in the append-only
 *     audit log). From then on, removing or disabling admins cannot switch it off again: when no other admin
 *     can approve, sensitive actions are refused and only the server-console recovery command remains.</li>
 * <li>An approver must be a different, enabled, enrolled ADMIN whose account passes its integrity check, and
 *     must not be connected to the proposer by sponsorship (one created or reset the other's access, directly
 *     or through other accounts), because such accounts may be held by the same person.</li>
 * </ul>
 */
@Service
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ApprovalPolicy implements ApplicationRunner {

    /** A lock rather than synchronized, so a virtual thread that waits for I/O inside it does not hold up its carrier thread. */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    static final String ACTIVATED = "FOUR_EYES_ACTIVATED";

    private final AdminUserService users;
    private final AuditService audit;
    private final boolean required;
    private volatile boolean active;

    public ApprovalPolicy(AdminUserService users, AuditService audit, PlinkoProperties properties) {
        this.users = users;
        this.audit = audit;
        this.required = properties.requireSecondApprover();
    }

    @Override
    public void run(ApplicationArguments args) {
        activateIfReady();
    }

    /** False only in local development with REQUIRE_SECOND_APPROVER=false. */
    public boolean required() {
        return required;
    }

    /** True once a second admin has enrolled; never false again afterwards. */
    public boolean active() {
        if (!required) {
            return false;
        }
        if (!active) {
            active = audit.happened(ACTIVATED);
        }
        if (!active) {
            activateIfReady();
        }
        return active;
    }

    public void activateIfReady() {
        lock.lock();
        try {
            if (required && !active && users.activeAdminCount() >= 2) {
                audit.log("system", ACTIVATED, "Two enrolled admins: account changes, odds and limits now always need a second admin");
                active = true;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Admins who could approve a change proposed by {@code actor} about {@code target} (target may be null). */
    public List<AdminUser> approvers(String actor, String target) {
        return users.all().stream()
                .filter(u -> !u.username().equals(actor) && !u.username().equals(target))
                .filter(u -> canApprove(u) && !users.related(u.username(), actor))
                .toList();
    }

    /** Why {@code approver} may not approve a change proposed by {@code proposer}, or empty if they may. */
    public Optional<String> approvalProblem(String proposer, String approver) {
        return approvalProblem(proposer, approver, required);
    }

    /**
     * Review R9 #2: money for a blocked round (a settlement, and the limits on it) always needs a second person: every
     * rule applies, linked accounts included, whatever REQUIRE_SECOND_APPROVER says.
     */
    public Optional<String> settlementApprovalProblem(String proposer, String approver) {
        return approvalProblem(proposer, approver, true);
    }

    private Optional<String> approvalProblem(String proposer, String approver, boolean fourEyes) {
        AdminUser user = users.find(approver).orElse(null);
        if (user == null || !canApprove(user)) {
            return Optional.of("Only an enabled ADMIN with 2FA, whose account passes its integrity check, can approve");
        }
        if (!fourEyes) {
            return Optional.empty();
        }
        if (proposer.equals(approver)) {
            return Optional.of("A change must be approved by a different admin than the one who proposed it");
        }
        AdminUser author = users.find(proposer).orElse(null);
        if (author == null || !author.enabled() || "VIEWER".equals(author.role()) || !users.trusted(author)) {
            return Optional.of("The admin who proposed this change can no longer propose changes; reject it");
        }
        if (users.related(proposer, approver)) {
            return Optional.of("You and " + proposer + " are linked: one of you created or reset the other's account access "
                    + "(directly or through other accounts), so a third admin must approve");
        }
        return Optional.empty();
    }

    private boolean canApprove(AdminUser user) {
        return "ADMIN".equals(user.role()) && user.enabled() && user.enrolled() && !user.locked() && users.trusted(user);
    }
}
