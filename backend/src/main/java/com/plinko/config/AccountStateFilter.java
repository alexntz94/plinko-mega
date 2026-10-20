package com.plinko.config;

import java.io.IOException;

import com.plinko.bo.security.AdminUserService;
import com.plinko.model.AdminUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-checks the signed-in admin on every request. A session ends as soon as its account is disabled,
 * its role, password or 2FA changes (the account's security stamp no longer matches the one recorded at
 * sign-in) or the account fails its integrity check, instead of living on until it times out.
 * Not a bean on purpose: it is added to the admin security chain only.
 */
public class AccountStateFilter extends OncePerRequestFilter {

    static final String STAMP = "plinko.securityStamp";
    static final String LAST_ACTIVITY = "plinko.lastActivity";
    /** Background refreshes of live pages are not user activity: after this long without a real request, sign out. */
    private static final long IDLE_MILLIS = 30 * 60 * 1000L;

    private final AdminUserService users;

    public AccountStateFilter(AdminUserService users) {
        this.users = users;
    }

    /** Records the account state this session was granted (called at sign-in and after changing one's own password). */
    public static void remember(HttpSession session, AdminUser user) {
        session.setAttribute(STAMP, user.securityStamp());
        session.setAttribute(LAST_ACTIVITY, System.currentTimeMillis());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (session != null && auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object stamp = session.getAttribute(STAMP);
            AdminUser user = users.find(auth.getName()).orElse(null);
            boolean liveRefresh = "1".equals(request.getHeader("X-Live-Refresh"));
            Object last = session.getAttribute(LAST_ACTIVITY);
            boolean idle = !(last instanceof Long time) || System.currentTimeMillis() - time > IDLE_MILLIS;
            if (!liveRefresh) {
                session.setAttribute(LAST_ACTIVITY, System.currentTimeMillis());
            }
            if (user == null || !user.enabled() || stamp == null || !stamp.equals(user.securityStamp()) || !users.trusted(user)
                    || (liveRefresh && idle)) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                response.sendRedirect(request.getContextPath() + "/admin/login?expired");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
