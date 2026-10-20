package com.plinko.bo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.common.ClientIp;
import com.plinko.model.AdminUser;
import com.plinko.repo.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Review 5.4: the admin sign-in steps, the code screen's expiry, the per-account lockout and the per-address limit. */
class AdminAuthenticationProviderTest {

    private static final String PASSWORD = "right-password-123";
    private final String secret = Totp.newSecret();
    private final AdminUserService users = mock(AdminUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ClientIp clientIp = mock(ClientIp.class);
    private final AdminAuthenticationProvider provider = new AdminAuthenticationProvider(users, audit);
    private MockHttpSession session = new MockHttpSession();
    private AdminUser alice = user("alice", secret, null);

    private static AdminUser user(String name, String totpSecret, Instant lockedUntil) {
        return new AdminUser(1, name, "hash", "ADMIN", true, totpSecret, 0, 0, lockedUntil, null, Instant.now(), false, null, null,
                "stamp", "integrity");
    }

    @BeforeEach
    void setUp() {
        when(clientIp.of(any())).thenAnswer(i -> ((MockHttpServletRequest) i.getArgument(0)).getRemoteAddr());
        when(users.find("alice")).thenAnswer(i -> Optional.of(alice));
        when(users.find("nobody")).thenReturn(Optional.empty());
        when(users.passwordMatches(any(), anyString())).thenAnswer(i -> PASSWORD.equals(i.getArgument(1)));
        when(users.totpSecret(any())).thenReturn(secret);
        when(users.trusted(any())).thenReturn(true);
        when(users.recordSuccess(any(), anyLong())).thenReturn(true);
    }

    private String code() {
        return Totp.code(Totp.unbase32(secret), Instant.now().getEpochSecond() / 30);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private Authentication signIn(String username, String password, String code, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/login");
        request.setRemoteAddr(ip);
        request.setSession(session);
        if (code != null) {
            request.setParameter("code", code);
        }
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        token.setDetails(new AdminAuthenticationProvider.Details(request, clientIp));
        return provider.authenticate(token);
    }

    private Authentication signIn(String password, String code) {
        return signIn("alice", password, code, "10.0.0.1");
    }

    private void passStepOne() {
        assertThatThrownBy(() -> signIn(PASSWORD, null)).isInstanceOf(AdminAuthenticationProvider.CodeRequiredException.class);
        assertThat(session.getAttribute(AdminAuthenticationProvider.PENDING)).isNotNull();
    }

    @Test
    void theRightPasswordThenTheRightCodeSignsIn() {
        passStepOne();
        Authentication signedIn = signIn("", code());
        assertThat(signedIn.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
        assertThat(session.getAttribute(AdminAuthenticationProvider.PENDING)).as("the step-1 result is used up").isNull();
    }

    @Test
    void theCodeScreenExpiresFiveMinutesAfterThePassword() {
        session.setAttribute(AdminAuthenticationProvider.PENDING,
                new AdminAuthenticationProvider.PendingLogin("alice", Instant.now().minus(Duration.ofMinutes(5)).minusSeconds(1), 0));
        // too late for the code alone: it counts as a sign-in without the password
        assertThatThrownBy(() -> signIn("", code())).isInstanceOf(BadCredentialsException.class);
        verify(users).recordFailure(alice);
        // still within the 5 minutes, the code alone completes the sign-in
        session = new MockHttpSession();
        session.setAttribute(AdminAuthenticationProvider.PENDING,
                new AdminAuthenticationProvider.PendingLogin("alice", Instant.now().minus(Duration.ofMinutes(4)), 0));
        assertThat(signIn("", code()).isAuthenticated()).isTrue();
    }

    @Test
    void aCodeStepFromAnotherUsersPasswordIsRefused() {
        session.setAttribute(AdminAuthenticationProvider.PENDING, new AdminAuthenticationProvider.PendingLogin("bob", Instant.now(), 0));
        assertThatThrownBy(() -> signIn("", code())).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void threeWrongCodesStartTheSignInOverAndEachCountsOnce() {
        passStepOne();
        String right = code();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThatThrownBy(() -> signIn("", wrong(right))).isInstanceOf(BadCredentialsException.class);
            verify(users, times(attempt)).recordFailure(alice);
        }
        assertThat(session.getAttribute(AdminAuthenticationProvider.PENDING)).as("back to the password step").isNull();
        // the right code now is not enough: the password must be given again
        assertThatThrownBy(() -> signIn("", right)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void aWrongPasswordCountsOneFailure() {
        assertThatThrownBy(() -> signIn("wrong-password-123", null)).isInstanceOf(BadCredentialsException.class);
        verify(users, times(1)).recordFailure(alice);
        verify(audit).log(eq("alice"), eq("LOGIN_FAILED"), anyString());
    }

    @Test
    void aLockedAccountIsRefusedEvenWithTheRightPasswordAndCode() {
        alice = user("alice", secret, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> signIn(PASSWORD, code())).isInstanceOf(LockedException.class);
        verify(users, never()).passwordMatches(any(), anyString());
        verify(users, never()).recordSuccess(any(), anyLong());
    }

    @Test
    void theLockoutEndsWhenItsWindowHasPassed() {
        alice = user("alice", secret, Instant.now().minusSeconds(1));
        assertThat(signIn(PASSWORD, code()).isAuthenticated()).isTrue();
    }

    @Test
    void theFifthFailureLocksTheAccountForTheConfiguredTime() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        AdminUserService real = new AdminUserService(repository, null, mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                TestFixtures.properties(), audit, null, null, null);
        AdminUser fourFailures = new AdminUser(9, "carol", "hash", "ADMIN", true, null, 0, 4, null, null, Instant.now(), false, null, null,
                "stamp", "integrity");
        AdminUser threeFailures = new AdminUser(9, "carol", "hash", "ADMIN", true, null, 0, 3, null, null, Instant.now(), false, null, null,
                "stamp", "integrity");
        real.recordFailure(threeFailures);
        verify(repository).setFailures(9, 4, null);
        Instant before = Instant.now();
        real.recordFailure(fourFailures);
        org.mockito.ArgumentCaptor<Instant> until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).setFailures(eq(9L), eq(0), until.capture());
        // 15 minutes in the fixtures
        assertThat(until.getValue()).isBetween(before.plus(Duration.ofMinutes(15)), Instant.now().plus(Duration.ofMinutes(15)));
        verify(audit).log("carol", "LOGIN_LOCKED", "Locked after 5 failed attempts");
    }

    @Test
    void twentyFailuresFromOneAddressBlockItButNotOthers() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> signIn("nobody", "guess", null, "10.0.0.66")).isInstanceOf(BadCredentialsException.class);
        }
        // the 21st try from there is refused before any check, even with the right password
        assertThatThrownBy(() -> signIn("alice", PASSWORD, null, "10.0.0.66")).isInstanceOf(LockedException.class)
                .hasMessageContaining("this address");
        verify(users, never()).passwordMatches(any(), anyString());
        // another address is not affected
        assertThatThrownBy(() -> signIn("alice", PASSWORD, null, "10.0.0.67"))
                .isInstanceOf(AdminAuthenticationProvider.CodeRequiredException.class);
    }

    @Test
    void theAddressLimitEndsAfterItsFifteenMinutes() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> signIn("nobody", "guess", null, "10.0.0.66")).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> signIn("alice", PASSWORD, null, "10.0.0.66")).isInstanceOf(LockedException.class);
        // move the address's window back past 15 minutes (the provider has no clock to turn)
        java.lang.reflect.Field field = AdminAuthenticationProvider.class.getDeclaredField("ipFailures");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> failures = (Map<String, Object>) field.get(provider);
        Class<?> type = Class.forName(AdminAuthenticationProvider.class.getName() + "$IpFailures");
        java.lang.reflect.Constructor<?> make = type.getDeclaredConstructor(int.class, Instant.class);
        make.setAccessible(true);
        failures.put("10.0.0.66", make.newInstance(20, Instant.now().minus(Duration.ofMinutes(15)).minusSeconds(1)));
        assertThatThrownBy(() -> signIn("alice", PASSWORD, null, "10.0.0.66"))
                .isInstanceOf(AdminAuthenticationProvider.CodeRequiredException.class);
    }

    @Test
    void aSuccessfulSignInClearsTheAddressCount() {
        for (int i = 0; i < 19; i++) {
            assertThatThrownBy(() -> signIn("nobody", "guess", null, "10.0.0.5")).isInstanceOf(BadCredentialsException.class);
        }
        passStepOneFrom("10.0.0.5");
        assertThat(signIn("alice", "", code(), "10.0.0.5").isAuthenticated()).isTrue();
        // counted from zero again: 19 more failures still leave the address open
        for (int i = 0; i < 19; i++) {
            assertThatThrownBy(() -> signIn("nobody", "guess", null, "10.0.0.5")).isInstanceOf(BadCredentialsException.class);
        }
        assertThatThrownBy(() -> signIn("nobody", "guess", null, "10.0.0.5")).isInstanceOf(BadCredentialsException.class);
    }

    private void passStepOneFrom(String ip) {
        assertThatThrownBy(() -> signIn("alice", PASSWORD, null, ip)).isInstanceOf(AdminAuthenticationProvider.CodeRequiredException.class);
    }
}
