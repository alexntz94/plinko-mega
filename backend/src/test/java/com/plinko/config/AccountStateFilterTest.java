package com.plinko.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.security.AdminUserService;
import com.plinko.model.AdminUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Review 5.4: a signed-in admin's session ends at once when the account changes, and background refreshes don't keep it alive. */
class AccountStateFilterTest {

    private final AdminUserService users = mock(AdminUserService.class);
    private final AccountStateFilter filter = new AccountStateFilter(users);
    private final MockHttpSession session = new MockHttpSession();
    private AdminUser alice = user(true, "stamp-1");

    private static AdminUser user(boolean enabled, String stamp) {
        return new AdminUser(1, "alice", "hash", "ADMIN", enabled, "v1:secret", 0, 0, null, null, Instant.now(), false, null, null,
                stamp, "integrity");
    }

    @BeforeEach
    void setUp() {
        when(users.find("alice")).thenAnswer(i -> Optional.ofNullable(alice));
        when(users.trusted(any())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        AccountStateFilter.remember(session, alice);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Sends one request through the filter; returns where it was sent, or null if it went on to the page. */
    private String request(boolean liveRefresh) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/rounds");
        request.setSession(session);
        if (liveRefresh) {
            request.addHeader("X-Live-Refresh", "1");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest() == null).as("stopped exactly when redirected").isEqualTo(response.getRedirectedUrl() != null);
        return response.getRedirectedUrl();
    }

    private void signedOut(String redirect) {
        assertThat(redirect).isEqualTo("/admin/login?expired");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anUnchangedAccountGoesOn() throws Exception {
        assertThat(request(false)).isNull();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aChangedSecurityStampEndsTheSession() throws Exception {
        // role, password or 2FA changed since sign-in
        alice = user(true, "stamp-2");
        signedOut(request(false));
    }

    @Test
    void aSessionWithoutARecordedStampEnds() throws Exception {
        session.removeAttribute(AccountStateFilter.STAMP);
        signedOut(request(false));
    }

    @Test
    void aDisabledAccountEndsTheSession() throws Exception {
        alice = user(false, "stamp-1");
        signedOut(request(false));
    }

    @Test
    void aDeletedAccountEndsTheSession() throws Exception {
        alice = null;
        signedOut(request(false));
    }

    @Test
    void anAccountChangedOutsideTheAdminPanelEndsTheSession() throws Exception {
        when(users.trusted(any())).thenReturn(false);
        signedOut(request(false));
    }

    @Test
    void liveRefreshesDoNotResetTheIdleTimer() throws Exception {
        long twentyMinutesAgo = System.currentTimeMillis() - 20 * 60 * 1000L;
        session.setAttribute(AccountStateFilter.LAST_ACTIVITY, twentyMinutesAgo);
        assertThat(request(true)).isNull();
        assertThat(session.getAttribute(AccountStateFilter.LAST_ACTIVITY)).as("a live refresh is not activity").isEqualTo(twentyMinutesAgo);
        // a real request is
        assertThat(request(false)).isNull();
        assertThat((Long) session.getAttribute(AccountStateFilter.LAST_ACTIVITY)).isGreaterThan(twentyMinutesAgo);
    }

    @Test
    void aLiveRefreshAfterThirtyIdleMinutesSignsOut() throws Exception {
        session.setAttribute(AccountStateFilter.LAST_ACTIVITY, System.currentTimeMillis() - 31 * 60 * 1000L);
        signedOut(request(true));
    }

    @Test
    void anonymousRequestsAreLeftToTheRoleChecks() throws Exception {
        SecurityContextHolder.clearContext();
        alice = user(false, "other");
        assertThat(request(false)).isNull();
    }
}
