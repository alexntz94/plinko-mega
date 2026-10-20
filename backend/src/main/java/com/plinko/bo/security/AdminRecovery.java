package com.plinko.bo.security;

import java.util.List;

import com.plinko.bo.audit.AuditService;
import com.plinko.model.AdminUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Break-glass recovery for when no admin can sign in (e.g. the only admin lost their phone). Needs access
 * to the server, so it is as trusted as the server itself:
 * <pre>docker compose run --rm --no-deps backend --recover-admin=USERNAME --server.port=0</pre>
 * Re-enables and unlocks the account, resets its password to a printed temporary one and clears its 2FA,
 * writes the audit log, then exits.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminRecovery implements ApplicationRunner {

    private final AdminUserService users;
    private final AuditService audit;
    private final ApplicationContext context;

    public AdminRecovery(AdminUserService users, AuditService audit, ApplicationContext context) {
        this.users = users;
        this.audit = audit;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> values = args.getOptionValues("recover-admin");
        if (values == null) {
            return;
        }
        int exitCode = recover(values.isEmpty() ? "" : values.get(0).trim());
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int recover(String username) {
        if (users.find(username).isEmpty()) {
            System.out.println("\nNo admin account named '" + username + "'. Existing accounts: "
                    + users.all().stream().map(AdminUser::username).toList() + "\n");
            return 1;
        }
        // Trusted path: also restores an account that failed its integrity check.
        users.recoverEnable(username);
        String temporary = users.resetPassword(username, true, "server-console");
        audit.log("server-console", "ADMIN_RECOVERED", username + ": enabled, unlocked, password and 2FA reset from the server console");
        System.out.println("""

                ==================================================================
                 Account recovered: %s
                 Temporary password (valid 24 hours): %s
                 Sign in with it, set up 2FA again, then choose a new password.
                 This recovery is recorded in the audit log.
                ==================================================================
                """.formatted(username, temporary));
        return 0;
    }
}
