package com.plinko.bo.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.plinko.bo.audit.AuditService;
import com.plinko.common.ClientIp;
import com.plinko.model.AdminUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Admin login in two steps: (1) username + password, then (2) the authenticator code on its own screen.
 * After step 1 the session only remembers "password verified for this user" for a few minutes; nobody is
 * signed in until the code is right. Per-account lockout and a per-IP throttle apply to both steps.
 * Admins without an enrolled authenticator only get ROLE_ENROLL, which reaches the enrolment page and nothing else.
 * (Password and code together in one request are also accepted.)
 */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    public static final String ROLE_ENROLL = "ROLE_ENROLL";
    /** Signed in with a temporary password: only the "choose a new password" page is reachable. */
    public static final String ROLE_PASSWORD_CHANGE = "ROLE_PASSWORD_CHANGE";

    private static final int MAX_FAILURES_PER_IP = 20;
    private static final Duration IP_WINDOW = Duration.ofMinutes(15);
    /** How long step 2 may follow step 1, and how many wrong codes before starting over. */
    private static final Duration PENDING_VALID = Duration.ofMinutes(5);
    private static final int MAX_CODE_ATTEMPTS = 3;
    public static final String PENDING = "plinko.pendingLogin";

    /** Step 1 passed: the password of this user was verified at this time, in this browser session. */
    public record PendingLogin(String username, Instant verifiedAt, int codeAttempts) implements java.io.Serializable {
        boolean fresh() {
            return verifiedAt.plus(PENDING_VALID).isAfter(Instant.now());
        }
    }

    /** Step 1 succeeded for an account with 2FA: the sign-in continues on the code screen. */
    public static class CodeRequiredException extends AuthenticationException {
        public CodeRequiredException() {
            super("Enter the code from your authenticator app");
        }
    }

    /** Login form details: visitor address, the authenticator code, and the session (for the step-1 result). */
    public static class Details extends WebAuthenticationDetails {
        private final String code;
        private final String ip;
        private final transient jakarta.servlet.http.HttpSession session;

        public Details(HttpServletRequest request, ClientIp clientIp) {
            super(request);
            this.code = request.getParameter("code");
            this.ip = clientIp.of(request);
            this.session = request.getSession(true);
        }

        public String ip() {
            return ip;
        }

        public String code() {
            return code;
        }

        PendingLogin pending() {
            return session.getAttribute(PENDING) instanceof PendingLogin p ? p : null;
        }

        void pending(PendingLogin value) {
            if (value == null) {
                session.removeAttribute(PENDING);
            } else {
                session.setAttribute(PENDING, value);
            }
        }
    }

    private record IpFailures(int count, Instant windowStart) {
    }

    private final AdminUserService users;
    private final AuditService audit;
    private final Map<String, IpFailures> ipFailures = new ConcurrentHashMap<>();

    public AdminAuthenticationProvider(AdminUserService users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        Details details = authentication.getDetails() instanceof Details d ? d : null;
        String ip = details == null ? "unknown" : details.ip();

        if (ipBlocked(ip)) {
            throw new LockedException("Too many failed attempts from this address. Try again later.");
        }

        AdminUser user = users.find(username).orElse(null);
        if (user == null) {
            users.dummyPasswordCheck(password);
            ipFailed(ip);
            throw new BadCredentialsException("Invalid username, password or code");
        }
        if (user.locked()) {
            throw new LockedException("Account locked after too many failed attempts. Try again later.");
        }
        String code = details == null ? null : details.code();
        boolean hasCode = code != null && !code.isBlank();
        PendingLogin pending = details == null ? null : details.pending();
        // Step 2 (code only): allowed only right after this user passed step 1 in this same session.
        boolean secondStep = password.isEmpty() && hasCode && pending != null && pending.username().equals(username) && pending.fresh();
        if (!secondStep && !users.passwordMatches(user, password)) {
            fail(user, ip, "wrong password");
        }
        if (secondStep && !user.enrolled()) {
            details.pending(null);
            fail(user, ip, "code step for an account without 2FA");
        }
        if (!users.trusted(user)) {
            audit.log(username, "ADMIN_INTEGRITY_FAILED", "login refused: account was modified outside the admin panel, from " + ip);
            throw new DisabledException("This account is blocked because it was changed outside the admin panel. Use the server recovery command.");
        }
        if (!user.enabled()) {
            throw new DisabledException("Account disabled");
        }
        if (user.passwordExpired()) {
            audit.log(username, "LOGIN_FAILED", "temporary password expired, from " + ip);
            throw new BadCredentialsException("Your temporary password has expired. Use \"Forgot password\" to request a new one.");
        }

        long step = 0;
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (user.enrolled()) {
            if (!hasCode) {
                // Step 1 done: remember it for this session and ask for the code on its own screen.
                details.pending(new PendingLogin(username, Instant.now(), 0));
                throw new CodeRequiredException();
            }
            step = Totp.verify(users.totpSecret(user), code, user.totpLastStep());
            if (step < 0) {
                if (secondStep) {
                    int attempts = pending.codeAttempts() + 1;
                    details.pending(attempts >= MAX_CODE_ATTEMPTS ? null : new PendingLogin(username, pending.verifiedAt(), attempts));
                }
                fail(user, ip, "wrong or reused authenticator code");
            }
            if (details != null) {
                details.pending(null);
            }
            if (user.mustChangePassword()) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PASSWORD_CHANGE));
            } else {
                for (String role : impliedRoles(user.role())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
        } else {
            authorities.add(new SimpleGrantedAuthority(ROLE_ENROLL));
        }

        if (!users.recordSuccess(user, step)) {
            fail(user, ip, "authenticator code already used");
        }
        ipFailures.remove(ip);
        audit.log(username, "LOGIN", user.enrolled() ? "from " + ip : "from " + ip + " (2FA enrolment pending)");
        return UsernamePasswordAuthenticationToken.authenticated(username, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    public static List<String> impliedRoles(String role) {
        return switch (role) {
            case "ADMIN" -> List.of("ADMIN", "OPERATOR", "VIEWER");
            case "OPERATOR" -> List.of("OPERATOR", "VIEWER");
            default -> List.of("VIEWER");
        };
    }

    private void fail(AdminUser user, String ip, String reason) {
        users.recordFailure(user);
        ipFailed(ip);
        audit.log(user.username(), "LOGIN_FAILED", reason + " from " + ip);
        throw new BadCredentialsException("Invalid username, password or code");
    }

    private boolean ipBlocked(String ip) {
        IpFailures failures = ipFailures.get(ip);
        if (failures == null) {
            return false;
        }
        if (failures.windowStart().plus(IP_WINDOW).isBefore(Instant.now())) {
            ipFailures.remove(ip);
            return false;
        }
        return failures.count() >= MAX_FAILURES_PER_IP;
    }

    private void ipFailed(String ip) {
        ipFailures.compute(ip, (key, current) -> current == null || current.windowStart().plus(IP_WINDOW).isBefore(Instant.now())
                ? new IpFailures(1, Instant.now())
                : new IpFailures(current.count() + 1, current.windowStart()));
    }
}
