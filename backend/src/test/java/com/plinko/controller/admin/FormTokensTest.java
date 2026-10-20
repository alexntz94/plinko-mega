package com.plinko.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.bo.changes.ChangeRequestService;
import com.plinko.bo.security.AdminUserService;
import com.plinko.bo.security.ApprovalPolicy;
import com.plinko.bo.settings.SettingsService;
import com.plinko.model.AdminUser;
import com.plinko.repo.AdminListRepository;
import com.plinko.repo.MockWalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Review N14.15: the password reset and new test wallet forms carry a one-time token, used up on the server. */
class FormTokensTest {

    private final FormTokens tokens = new FormTokens();
    private final MockHttpSession session = new MockHttpSession();
    private final Principal alice = () -> "alice";

    @Test
    void aTokenWorksOnceAndOnlyInTheSessionThatGotIt() {
        String token = tokens.issue(session);
        assertThat(tokens.use(new MockHttpSession(), token)).as("another session").isFalse();
        assertThat(tokens.use(session, token)).isTrue();
        assertThat(tokens.use(session, token)).as("sent again").isFalse();
        assertThat(tokens.use(session, null)).isFalse();
        assertThat(tokens.use(session, " ")).isFalse();
        assertThat(tokens.use(session, "made-up")).isFalse();
        assertThat(tokens.use(null, tokens.issue(session))).isFalse();
    }

    @Test
    void eachPageGetsItsOwnTokenAndOnlyTheNewestAreKept() {
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < FormTokens.KEPT + 5; i++) {
            issued.add(tokens.issue(session));
        }
        assertThat(issued).doesNotHaveDuplicates();
        // the oldest five were dropped; the rest each work once, in any order
        for (String old : issued.subList(0, 5)) {
            assertThat(tokens.use(session, old)).isFalse();
        }
        for (String kept : issued.subList(5, issued.size()).reversed()) {
            assertThat(tokens.use(session, kept)).isTrue();
        }
    }

    // ---------------------------------------------------------------- the forms that use it

    private static AdminUser enrolled(String username) {
        return new AdminUser(2, username, "hash", "OPERATOR", true, "secret", 0, 0, null, null, Instant.now(), false, null, null, "stamp", "sig");
    }

    @Test
    void aResetPasswordSentTwiceIsDoneOnceAndOneWithoutATokenNotAtAll() {
        AdminUserService users = mock(AdminUserService.class);
        AuditService audit = mock(AuditService.class);
        ApprovalPolicy policy = mock(ApprovalPolicy.class);
        when(users.find("bob")).thenReturn(Optional.of(enrolled("bob")));
        when(users.resetPassword("bob", false, "alice")).thenReturn("temporary-1");
        when(policy.active()).thenReturn(true);
        UserAdminController controller = new UserAdminController(users, mock(ChangeRequestService.class), policy, audit, mock(AdminListRepository.class));

        RedirectAttributesModelMap none = new RedirectAttributesModelMap();
        controller.action("bob", "reset-password", null, null, session, alice, none);
        assertThat(none.getFlashAttributes().get("error")).isEqualTo("This form was already sent; reload the page");
        verify(users, never()).resetPassword(anyString(), eq(false), anyString());

        ExtendedModelMap page = new ExtendedModelMap();
        assertThat(controller.users(page, session)).isEqualTo("admin/users");
        String token = (String) page.getAttribute("formToken");

        RedirectAttributesModelMap first = new RedirectAttributesModelMap();
        controller.action("bob", "reset-password", null, token, session, alice, first);
        assertThat(first.getFlashAttributes().get("tempPassword")).isEqualTo("temporary-1");

        RedirectAttributesModelMap second = new RedirectAttributesModelMap();
        assertThat(controller.action("bob", "reset-password", null, token, session, alice, second)).isEqualTo("redirect:/admin/users");
        assertThat(second.getFlashAttributes().get("error")).isEqualTo("This form was already sent; reload the page");
        assertThat(second.getFlashAttributes()).doesNotContainKey("tempPassword");
        verify(users, times(1)).resetPassword(anyString(), eq(false), anyString());
        verify(audit, times(1)).log(eq("alice"), eq("ADMIN_PASSWORD_RESET"), anyString());

        // the page shown again gives a new token: a deliberate second reset works
        controller.users(page, session);
        controller.action("bob", "reset-password", null, (String) page.getAttribute("formToken"), session, alice, new RedirectAttributesModelMap());
        verify(users, times(2)).resetPassword("bob", false, "alice");
    }

    @Test
    void aTestWalletFormSentTwiceCreatesOneWallet() {
        MockWalletRepository wallets = mock(MockWalletRepository.class);
        AuditService audit = mock(AuditService.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.currency()).thenReturn("EUR");
        MockPamAdminController controller = new MockPamAdminController(wallets, audit, TestFixtures.properties(), settings, "");
        ExtendedModelMap page = new ExtendedModelMap();
        assertThat(controller.wallets(page, session)).isEqualTo("admin/mock-pam");
        String token = (String) page.getAttribute("formToken");

        RedirectAttributesModelMap first = new RedirectAttributesModelMap();
        controller.create("tester", null, "50", token, session, alice, first);
        assertThat(first.getFlashAttributes().get("message")).isEqualTo("Test wallet created");

        RedirectAttributesModelMap second = new RedirectAttributesModelMap();
        controller.create("tester", null, "50", token, session, alice, second);
        assertThat(second.getFlashAttributes().get("error")).isEqualTo("This form was already sent; reload the page");
        RedirectAttributesModelMap without = new RedirectAttributesModelMap();
        controller.create("tester-2", null, "50", null, session, alice, without);
        assertThat(without.getFlashAttributes().get("error")).isEqualTo("This form was already sent; reload the page");
        verify(wallets, times(1)).create(anyString(), eq("tester"), eq("EUR"), eq(new BigDecimal("50.00")), anyString(), any());
        verify(audit, times(1)).log(eq("alice"), eq("MOCK_WALLET_CREATED"), anyString());

        // an invalid form is refused before its token is used: corrected, it goes through with the same token
        controller.wallets(page, session);
        String next = (String) page.getAttribute("formToken");
        controller.create("bad name!", null, "50", next, session, alice, new RedirectAttributesModelMap());
        controller.create("tester-3", null, "lots", next, session, alice, new RedirectAttributesModelMap());
        controller.create("tester-3", "eur", "1", next, session, alice, new RedirectAttributesModelMap());
        verify(wallets, never()).create(anyString(), eq("bad name!"), anyString(), any(), anyString(), any());
        verify(wallets, times(2)).create(anyString(), anyString(), anyString(), any(), anyString(), any());
    }
}
