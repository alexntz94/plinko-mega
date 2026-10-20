package com.plinko.bo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.plinko.TestFixtures;
import com.plinko.bo.audit.AuditService;
import com.plinko.model.AdminUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Review 5.4: nobody approves their own change, or one by an account linked to theirs, or without the right standing. */
class ApprovalPolicyTest {

    private final AdminUserService users = mock(AdminUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private ApprovalPolicy policy;

    private static AdminUser user(String name, String role, boolean enabled, boolean enrolled, Instant lockedUntil) {
        return new AdminUser(1, name, "hash", role, enabled, enrolled ? "v1:secret" : null, 0, 0, lockedUntil, null, Instant.now(), false,
                null, null, "stamp", "integrity");
    }

    @BeforeEach
    void setUp() {
        Map<String, AdminUser> all = Map.of(
                "alice", user("alice", "ADMIN", true, true, null),
                "bob", user("bob", "ADMIN", true, true, null),
                "carol", user("carol", "ADMIN", true, true, null),
                "op", user("op", "OPERATOR", true, true, null),
                "viewer", user("viewer", "VIEWER", true, true, null),
                "gone", user("gone", "ADMIN", false, true, null),
                "new", user("new", "ADMIN", true, false, null),
                "locked", user("locked", "ADMIN", true, true, Instant.now().plusSeconds(600)));
        when(users.find(anyString())).thenAnswer(i -> Optional.ofNullable(all.get((String) i.getArgument(0))));
        when(users.all()).thenReturn(List.copyOf(all.values()));
        when(users.trusted(any())).thenAnswer(i -> !"tampered".equals(((AdminUser) i.getArgument(0)).username()));
        // carol created bob's account: linked
        when(users.related(anyString(), anyString())).thenAnswer(i -> {
            String a = i.getArgument(0);
            String b = i.getArgument(1);
            return (a.equals("bob") && b.equals("carol")) || (a.equals("carol") && b.equals("bob"));
        });
        when(users.activeAdminCount()).thenReturn(2);
        policy = new ApprovalPolicy(users, audit, TestFixtures.properties());
    }

    @Test
    void anotherUnlinkedAdminMayApprove() {
        assertThat(policy.approvalProblem("op", "alice")).isEmpty();
        assertThat(policy.approvalProblem("bob", "alice")).isEmpty();
    }

    @Test
    void nobodyApprovesTheirOwnChange() {
        assertThat(policy.approvalProblem("alice", "alice")).get().asString().contains("different admin");
    }

    @Test
    void linkedAccountsCantApproveEachOther() {
        assertThat(policy.approvalProblem("bob", "carol")).get().asString().contains("linked");
        assertThat(policy.approvers("bob", null)).extracting(AdminUser::username).doesNotContain("bob", "carol").contains("alice");
    }

    @Test
    void aSettlementAlwaysNeedsAnUnlinkedSecondAdminEvenWithTheSwitchOff() {
        // R9 #2: with REQUIRE_SECOND_APPROVER=false the other changes skip the checks, a settlement never does
        com.plinko.config.PlinkoProperties p = TestFixtures.properties();
        ApprovalPolicy relaxed = new ApprovalPolicy(users, audit, new com.plinko.config.PlinkoProperties(p.admin(), false, p.pam(), p.mockPam(),
                p.roundSigningKey(), p.limits(), p.login(), p.gameUrl(), p.sessionTtlMinutes(), p.retry()));
        assertThat(relaxed.approvalProblem("bob", "carol")).as("an ordinary change").isEmpty();
        assertThat(relaxed.settlementApprovalProblem("bob", "carol")).get().asString().contains("linked");
        assertThat(relaxed.settlementApprovalProblem("alice", "alice")).get().asString().contains("different admin");
        assertThat(relaxed.settlementApprovalProblem("viewer", "alice")).get().asString().contains("can no longer propose");
        assertThat(relaxed.settlementApprovalProblem("bob", "alice")).isEmpty();
    }

    @Test
    void onlyAnEnabledEnrolledUnlockedAdminApproves() {
        for (String approver : List.of("op", "viewer", "gone", "new", "locked", "nobody")) {
            assertThat(policy.approvalProblem("alice", approver)).as(approver).get().asString().contains("Only an enabled ADMIN");
        }
    }

    @Test
    void aChangeByAnAccountThatLostItsStandingCantBeApproved() {
        assertThat(policy.approvalProblem("gone", "alice")).get().asString().contains("can no longer propose");
        assertThat(policy.approvalProblem("viewer", "alice")).get().asString().contains("can no longer propose");
    }

    @Test
    void fourEyesSwitchesOnWithTheSecondAdminAndStaysOn() {
        assertThat(policy.active()).isTrue();
        org.mockito.Mockito.verify(audit).log("system", ApprovalPolicy.ACTIVATED,
                "Two enrolled admins: account changes, odds and limits now always need a second admin");
        // fewer admins later never switches it off: there is no fallback to a single admin
        when(users.activeAdminCount()).thenReturn(1);
        assertThat(policy.active()).isTrue();
        assertThat(policy.approvers("alice", null)).extracting(AdminUser::username).doesNotContain("alice");
    }

    @Test
    void accountsLinkedThroughAChainOfSponsorsCantApproveEachOther() {
        // ann set up ben, ben set up cat: ann and cat may be one person, although neither set up the other directly
        com.plinko.repo.AdminUserRepository repository = mock(com.plinko.repo.AdminUserRepository.class);
        Map<String, AdminUser> all = Map.of(
                "ann", sponsored("ann", null), "ben", sponsored("ben", "ann"), "cat", sponsored("cat", "ben"), "dan", sponsored("dan", null));
        when(repository.find(anyString())).thenAnswer(i -> Optional.ofNullable(all.get((String) i.getArgument(0))));
        when(repository.all()).thenReturn(List.copyOf(all.values()));
        AdminUserService real = org.mockito.Mockito.spy(new AdminUserService(repository, null,
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), TestFixtures.properties(), audit, null, null, null));
        org.mockito.Mockito.doReturn(true).when(real).trusted(any());
        org.mockito.Mockito.doReturn(4).when(real).activeAdminCount();
        ApprovalPolicy chained = new ApprovalPolicy(real, audit, TestFixtures.properties());

        assertThat(real.sponsors("cat")).containsExactly("ben", "ann");
        for (String[] pair : new String[][] {{"cat", "ann"}, {"ann", "cat"}, {"cat", "ben"}, {"ben", "ann"}}) {
            assertThat(chained.approvalProblem(pair[0], pair[1])).as("%s proposes, %s approves", pair[0], pair[1])
                    .get().asString().contains("linked");
        }
        assertThat(chained.approvalProblem("cat", "dan")).isEmpty();
        assertThat(chained.approvers("cat", null)).extracting(AdminUser::username).containsExactly("dan");
    }

    @Test
    void aSponsorLoopDoesNotHangTheCheck() {
        com.plinko.repo.AdminUserRepository repository = mock(com.plinko.repo.AdminUserRepository.class);
        Map<String, AdminUser> all = Map.of("x", sponsored("x", "y"), "y", sponsored("y", "x"));
        when(repository.find(anyString())).thenAnswer(i -> Optional.ofNullable(all.get((String) i.getArgument(0))));
        AdminUserService real = new AdminUserService(repository, null, mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                TestFixtures.properties(), audit, null, null, null);
        assertThat(real.sponsors("x")).containsExactly("y", "x");
        assertThat(real.related("x", "y")).isTrue();
    }

    private static AdminUser sponsored(String name, String sponsor) {
        return new AdminUser(name.hashCode(), name, "hash", "ADMIN", true, "v1:secret", 0, 0, null, null, Instant.now(), false,
                null, sponsor, "stamp", "integrity");
    }
}
