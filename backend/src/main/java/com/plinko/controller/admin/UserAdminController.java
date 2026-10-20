package com.plinko.controller.admin;

import java.security.Principal;
import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.security.ApprovalPolicy;
import com.plinko.config.AccountStateFilter;
import com.plinko.model.AdminUser;
import com.plinko.model.AuditEntry;
import com.plinko.repo.AdminListRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin accounts. Anything that could hand one person a second account able to propose or approve changes
 * (creating an OPERATOR or ADMIN, granting either role, resetting their 2FA) or that removes an approver
 * (disabling or demoting an ADMIN) needs a second admin's approval once four-eyes is active. Without an
 * available approver these actions are refused; only the server-console recovery command remains.
 */
@Controller
public class UserAdminController {

    private static final String NO_APPROVER = "No other admin can approve this (approvers must be enabled ADMINs with 2FA, "
            + "not you, not the account itself, and not linked to you by creating or resetting each other's access). "
            + "If admins are locked out, use the server recovery command (see README).";

    private final AdminUserService users;
    private final ChangeRequestService changes;
    private final ApprovalPolicy policy;
    private final AuditService audit;
    private final AdminListRepository lists;

    /** The password reset form carries a one-time token: sent twice, it is done once (N14.15). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FormTokens formTokens = new FormTokens();

    public UserAdminController(AdminUserService users, ChangeRequestService changes, ApprovalPolicy policy, AuditService audit,
                               AdminListRepository lists) {
        this.lists = lists;
        this.users = users;
        this.changes = changes;
        this.policy = policy;
        this.audit = audit;
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String users(Model model, HttpSession session) {
        model.addAttribute("formToken", formTokens.issue(session));
        model.addAttribute("users", users.all());
        model.addAttribute("roles", AdminUserService.ROLES);
        model.addAttribute("resetRequests", users.pendingResetRequests());
        model.addAttribute("fourEyesActive", policy.active());
        model.addAttribute("untrusted", users.all().stream().filter(u -> !users.trusted(u)).map(AdminUser::username).toList());
        return "admin/users";
    }

    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String username, @RequestParam String password, @RequestParam String role, Principal principal,
                         RedirectAttributes redirect) {
        String name = username.trim();
        String me = principal.getName();
        try {
            // Before two-person approval is on, the first admin may only create the second admin: nobody can
            // prepare extra accounts to use as "independent" approvers later.
            if (policy.required() && !policy.active() && (users.all().size() > 1 || !"ADMIN".equals(role))) {
                throw new ChangeRequestService.ChangeException("Until a second admin has set up 2FA, the only account you can create is "
                        + "that second ADMIN. Once they have, two-person approval switches on and you can add other accounts.");
            }
            if (!"VIEWER".equals(role) && policy.active()) {
                requireApprover(me, name);
                long id = changes.proposeAdminCreate(name, password, role, me);
                redirect.addFlashAttribute("message", "Creating " + name + " as " + role + " needs a second admin's approval: change #" + id + ".");
                return "redirect:/admin/users";
            }
            List<String> problems = users.create(name, password, role, policy.active() ? me : null);
            if (!problems.isEmpty()) {
                throw new ChangeRequestService.ChangeException(String.join("; ", problems));
            }
        } catch (ChangeRequestService.ChangeException | AdminUserService.IntegrityException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users";
        }
        audit.log(me, "ADMIN_CREATED", name + " as " + role);
        redirect.addFlashAttribute("message", "Account " + name + " created. They set up 2FA and choose their own password at first login.");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{username}/{action}")
    @PreAuthorize("hasRole('ADMIN')")
    public String action(@PathVariable String username, @PathVariable String action, @RequestParam(required = false) String role,
                         @RequestParam(name = FormTokens.FIELD, required = false) String formToken, HttpSession session,
                         Principal principal, RedirectAttributes redirect) {
        String me = principal.getName();
        if (username.equals(me)) {
            redirect.addFlashAttribute("error", "You cannot change your own account here");
            return "redirect:/admin/users";
        }
        AdminUser target = users.find(username).orElse(null);
        if (target == null) {
            redirect.addFlashAttribute("error", "Unknown admin");
            return "redirect:/admin/users";
        }
        boolean active = policy.active();
        boolean privileged = !"VIEWER".equals(target.role());
        try {
            switch (action) {
                case "disable" -> {
                    if (active && "ADMIN".equals(target.role())) {
                        // the admin being removed may approve it themselves
                        return proposed(redirect, "Disabling admin " + username, changes.proposeAdminDisable(username, requireApprover(me, null)));
                    }
                    users.setEnabled(username, false);
                }
                case "enable" -> {
                    // Bringing back an account that can propose or approve needs a second admin, like removing it.
                    if (active && privileged) {
                        return proposed(redirect, "Enabling " + username, changes.proposeAdminEnable(username, requireApprover(me, username)));
                    }
                    users.setEnabled(username, true);
                }
                case "unlock" -> users.unlock(username);
                case "reset-password" -> {
                    // Safe to do alone only while the account's 2FA (stored encrypted) stays on its owner's phone.
                    if (active && !target.enrolled()) {
                        throw new ChangeRequestService.ChangeException(username + " has no 2FA set up, so a password reset would hand you the account. "
                                + "They can sign in with their current password to set up 2FA; if they lost both, use the server recovery command.");
                    }
                    if (!formTokens.use(session, formToken)) {
                        throw new ChangeRequestService.ChangeException(FormTokens.REFUSED);
                    }
                    String temporary = users.resetPassword(username, false, me);
                    audit.log(me, "ADMIN_PASSWORD_RESET", username + " (temporary password, 2FA kept)");
                    redirect.addFlashAttribute("tempUser", username);
                    redirect.addFlashAttribute("tempPassword", temporary);
                    return "redirect:/admin/users";
                }
                case "reset-2fa" -> {
                    if (active && privileged) {
                        return proposed(redirect, "Resetting " + username + "'s 2FA", changes.proposeAdmin2faReset(username, requireApprover(me, username)));
                    }
                    users.resetTotp(username, active ? me : null);
                }
                case "role" -> {
                    if (role == null || !AdminUserService.ROLES.contains(role)) {
                        throw new ChangeRequestService.ChangeException("Unknown role");
                    }
                    changes.requireReadyForPromotion(username, role);
                    // Granting the right to propose or approve, or removing an approver, needs a second admin.
                    if (active && (!"VIEWER".equals(role) || "ADMIN".equals(target.role())) && !role.equals(target.role())) {
                        // a demoted admin may agree to it themselves; a promotion needs someone else
                        String approverTarget = "ADMIN".equals(target.role()) ? null : username;
                        return proposed(redirect, "Changing " + username + " to " + role, changes.proposeAdminRole(username, role, requireApprover(me, approverTarget)));
                    }
                    users.setRole(username, role);
                }
                default -> throw new ChangeRequestService.ChangeException("Unknown action");
            }
        } catch (ChangeRequestService.ChangeException | AdminUserService.IntegrityException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users";
        }
        audit.log(me, "ADMIN_" + action.toUpperCase().replace('-', '_'), username + (role != null ? " -> " + role : ""));
        redirect.addFlashAttribute("message", "Done: " + action + " " + username);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/reset-requests/{id}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    public String dismiss(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        users.dismissResetRequest(id, principal.getName());
        audit.log(principal.getName(), "PASSWORD_RESET_DISMISSED", "request #" + id);
        redirect.addFlashAttribute("message", "Request dismissed");
        return "redirect:/admin/users";
    }

    /** Returns the actor if someone else could approve their proposal about {@code target}; refuses otherwise. */
    private String requireApprover(String actor, String target) {
        if (policy.approvers(actor, target).isEmpty()) {
            throw new ChangeRequestService.ChangeException(NO_APPROVER);
        }
        return actor;
    }

    private static String proposed(RedirectAttributes redirect, String what, long id) {
        redirect.addFlashAttribute("message", what + " needs a second admin's approval: change #" + id + ".");
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/account")
    public String account() {
        return "admin/account";
    }

    @PostMapping("/admin/account/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword, Principal principal,
                                 HttpSession session, RedirectAttributes redirect) {
        List<String> problems = users.changePassword(principal.getName(), currentPassword, newPassword);
        if (problems.isEmpty()) {
            // Other sessions of this account are signed out; this one stays signed in.
            AccountStateFilter.remember(session, users.find(principal.getName()).orElseThrow());
            audit.log(principal.getName(), "PASSWORD_CHANGED", null);
            redirect.addFlashAttribute("message", "Password changed");
        } else {
            redirect.addFlashAttribute("error", String.join("; ", problems));
        }
        return "redirect:/admin/account";
    }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public String audit(@RequestParam(required = false) String q, @RequestParam(required = false) String user,
                        @RequestParam(required = false) String action, @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
                        @RequestParam(required = false) Integer size, @RequestParam(required = false) String dir,
                        jakarta.servlet.http.HttpServletRequest request, Model model) {
        com.plinko.common.ListQuery query = auditQuery(page, size, dir);
        AdminListRepository.AuditFilter filter = auditFilter(q, user, action, from, to);
        model.addAttribute("result", lists.audit(filter, query));
        model.addAttribute("links", ListSupport.Links.of(request, query));
        model.addAttribute("filter", filter);
        model.addAttribute("actions", lists.auditActions());
        model.addAttribute("auditUsers", lists.auditUsers());
        model.addAttribute("chain", audit.verifyChain());
        return "admin/audit";
    }

    @GetMapping("/admin/audit.csv")
    @PreAuthorize("hasRole('ADMIN')")
    public void auditCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String user,
                         @RequestParam(required = false) String action, @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to, @RequestParam(required = false) String dir,
                         Principal principal, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        List<AuditEntry> entries = lists.auditForExport(auditFilter(q, user, action, from, to), auditQuery(1, 50, dir));
        audit.log(principal.getName(), "EXPORT_AUDIT", entries.size() + " audit entries");
        ListSupport.csv(response, "audit-log.csv", List.of("#", "Time (UTC)", "Admin", "Action", "Details", "Hash"),
                entries.stream().map(e -> List.<Object>of(e.id(), ListSupport.time(e.createdAt()), e.username(), e.action(),
                        e.details() == null ? "" : e.details(), e.hash())).toList());
    }

    private static AdminListRepository.AuditFilter auditFilter(String q, String user, String action, String from, String to) {
        return new AdminListRepository.AuditFilter(q, user, action, ListSupport.date(from), ListSupport.date(to));
    }

    private static com.plinko.common.ListQuery auditQuery(Integer page, Integer size, String dir) {
        return com.plinko.common.ListQuery.of(page, size, "time", dir, AdminListRepository.AUDIT_SORTS, "time", false);
    }
}
