package com.plinko.controller.admin;

import java.security.Principal;

import com.plinko.bo.audit.AuditService;
import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.security.QrCode;
import com.plinko.bo.security.Totp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    private static final String PENDING_SECRET = "pendingTotpSecret";

    private final AdminUserService users;
    private final AuditService audit;
    private final com.plinko.bo.security.ApprovalPolicy approvalPolicy;
    private final com.plinko.common.ClientIp clientIp;

    public LoginController(AdminUserService users, AuditService audit, com.plinko.bo.security.ApprovalPolicy approvalPolicy,
                           com.plinko.common.ClientIp clientIp) {
        this.clientIp = clientIp;
        this.users = users;
        this.audit = audit;
        this.approvalPolicy = approvalPolicy;
    }

    @GetMapping("/admin/login")
    public String login(HttpSession session, Model model) {
        // Back on step 1: a half-finished sign-in (password checked, code not yet) is dropped.
        session.removeAttribute(com.plinko.bo.security.AdminAuthenticationProvider.PENDING);
        Object error = session.getAttribute("loginError");
        if (error != null) {
            model.addAttribute("error", error);
            session.removeAttribute("loginError");
        }
        return "admin/login";
    }

    /** Step 2 of signing in: only reachable right after the password was accepted in this session. */
    @GetMapping("/admin/login/code")
    public String loginCode(HttpSession session, Model model) {
        Object pending = session.getAttribute(com.plinko.bo.security.AdminAuthenticationProvider.PENDING);
        if (!(pending instanceof com.plinko.bo.security.AdminAuthenticationProvider.PendingLogin login)) {
            return "redirect:/admin/login";
        }
        Object error = session.getAttribute("loginError");
        if (error != null) {
            model.addAttribute("error", error);
            session.removeAttribute("loginError");
        }
        model.addAttribute("username", login.username());
        return "admin/login-code";
    }

    /** Any method: Spring Security forwards the original request here, e.g. a POST with a bad CSRF token. */
    @RequestMapping("/admin/denied")
    public String denied() {
        return "admin/denied";
    }

    @GetMapping("/admin/forgot")
    public String forgot() {
        return "admin/forgot";
    }

    /** Same answer whether or not the account exists; at most 5 requests per address per hour. */
    @PostMapping("/admin/forgot")
    public String requestReset(@RequestParam String username, HttpServletRequest request, Model model) {
        String ip = clientIp.of(request);
        java.time.Instant now = java.time.Instant.now();
        java.util.List<java.time.Instant> recent = forgotRequests.computeIfAbsent(ip, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        recent.removeIf(t -> t.isBefore(now.minusSeconds(3600)));
        if (forgotRequests.size() > 10_000) {
            forgotRequests.entrySet().removeIf(e -> e.getValue().stream().allMatch(t -> t.isBefore(now.minusSeconds(3600))));
        }
        if (recent.size() < 5 && username != null && username.length() <= 64) {
            recent.add(now);
            users.requestReset(username.trim(), ip);
        }
        model.addAttribute("sent", true);
        return "admin/forgot";
    }

    @GetMapping("/admin/password/change")
    public String passwordChange() {
        return "admin/password-change";
    }

    @PostMapping("/admin/password/change")
    public String changeTemporaryPassword(Principal principal, @RequestParam String currentPassword, @RequestParam String newPassword,
                                          @RequestParam String confirmPassword, HttpServletRequest request, Model model) throws Exception {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (!newPassword.equals(confirmPassword)) {
            problems.add("The two new passwords are not the same");
        } else {
            problems.addAll(users.changePassword(principal.getName(), currentPassword, newPassword));
        }
        if (!problems.isEmpty()) {
            model.addAttribute("error", String.join("; ", problems));
            return "admin/password-change";
        }
        audit.log(principal.getName(), "PASSWORD_CHANGED", "replaced temporary password");
        request.logout();
        return "redirect:/admin/login?passwordChanged";
    }

    private final java.util.Map<String, java.util.List<java.time.Instant>> forgotRequests = new java.util.concurrent.ConcurrentHashMap<>();

    @GetMapping("/admin/2fa/setup")
    public String setup(Principal principal, HttpSession session, Model model) {
        String secret = (String) session.getAttribute(PENDING_SECRET);
        if (secret == null) {
            secret = Totp.newSecret();
            session.setAttribute(PENDING_SECRET, secret);
        }
        model.addAttribute("secret", secret.replaceAll("(.{4})", "$1 ").trim());
        String otpauthUri = Totp.otpauthUri("Plinko", principal.getName(), secret);
        model.addAttribute("otpauthUri", otpauthUri);
        model.addAttribute("qrCode", QrCode.svgDataUri(otpauthUri));
        return "admin/2fa-setup";
    }

    @PostMapping("/admin/2fa/setup")
    public String confirm(Principal principal, HttpSession session, @RequestParam String code, HttpServletRequest request, Model model)
            throws Exception {
        String secret = (String) session.getAttribute(PENDING_SECRET);
        long step = secret == null ? -1 : Totp.verify(secret, code.trim(), 0);
        if (step < 0) {
            model.addAttribute("error", "That code is not valid. Check the time on your phone and try again.");
            return setup(principal, session, model);
        }
        users.enrollTotp(principal.getName(), secret, step);
        audit.log(principal.getName(), "2FA_ENROLLED", null);
        approvalPolicy.activateIfReady();
        // Log in again with password and code to receive the account's real permissions.
        request.logout();
        return "redirect:/admin/login?enrolled";
    }
}
