package com.plinko.bo.security;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.plinko.bo.audit.AuditService;
import com.plinko.common.Crypto;
import com.plinko.common.Integrity;
import com.plinko.common.SecretBox;
import com.plinko.config.PlinkoProperties;
import com.plinko.model.AdminUser;
import com.plinko.model.ResetRequest;
import com.plinko.repo.AdminUserRepository;
import com.plinko.repo.IntegrityLedger;
import com.plinko.repo.PasswordResetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin accounts. The fields that decide who can sign in and what they may do (password hash, role,
 * enabled, 2FA secret, sponsor, security stamp) are signed with the integrity key; an account edited
 * directly in the database fails its check and can no longer sign in or approve anything.
 */
@Service
public class AdminUserService implements ApplicationRunner {

    /** The account was changed outside the application (its integrity signature does not match). */
    public static class IntegrityException extends RuntimeException {
        public IntegrityException(String username) {
            super("Account " + username + " failed its integrity check (changed outside the admin panel) and is blocked. "
                    + "Use the server recovery command to restore it.");
        }
    }

    public static final List<String> ROLES = List.of("VIEWER", "OPERATOR", "ADMIN");

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);


    private final AdminUserRepository users;
    private final PasswordResetRepository resets;
    private final PasswordEncoder passwordEncoder;
    private final PlinkoProperties properties;
    private final AuditService audit;
    private final Integrity integrity;
    private final IntegrityLedger ledger;
    private final SecretBox secrets;
    private final String dummyHash;

    public AdminUserService(AdminUserRepository users, PasswordResetRepository resets, PasswordEncoder passwordEncoder, PlinkoProperties properties, AuditService audit,
                            Integrity integrity, IntegrityLedger ledger, SecretBox secrets) {
        this.users = users;
        this.resets = resets;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
        this.integrity = integrity;
        this.ledger = ledger;
        this.secrets = secrets;
        this.dummyHash = passwordEncoder.encode(com.plinko.common.Crypto.randomToken(16));
    }

    /** The first admin's temporary password when ADMIN_PASSWORD is not set. */
    static final String DEFAULT_PASSWORD = "admin";

    /** Creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD (or the default) when the table is empty. */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.admin().bootstrap() || users.count() > 0) {
            return;
        }
        String username = properties.admin().username();
        String password = properties.admin().password();
        if (password == null || password.isEmpty()) {
            // Fresh install without ADMIN_PASSWORD: the password is "admin", handled like a reset's temporary password.
            // Signing in with it opens only 2FA setup and "choose a new password", and it stops working after 24 hours.
            insert(username, passwordEncoder.encode(DEFAULT_PASSWORD), "ADMIN", true, null);
            mutate(username, false, "password_expires_at = ?", Timestamp.from(Instant.now().plus(Duration.ofHours(24))));
            audit.log("system", "ADMIN_CREATED", "Initial admin " + username + " with the default temporary password");
            log.warn("Created initial admin '{}' with the password '{}'. Sign in within 24 hours: you must set up 2FA and choose "
                    + "a new password before anything else.", username, DEFAULT_PASSWORD);
            return;
        }
        List<String> problems = passwordProblems(username, password);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("No admin exists yet: set ADMIN_USERNAME and ADMIN_PASSWORD (" + String.join("; ", problems) + ")");
        }
        // The first admin chose this password in the environment, so no forced change.
        insert(username, passwordEncoder.encode(password), "ADMIN", false, null);
        audit.log("system", "ADMIN_CREATED", "Initial admin " + username);
        log.info("Created initial admin '{}'. Two-factor enrolment is required on first login.", username);
    }

    public Optional<AdminUser> find(String username) {
        return users.find(username);
    }

    public List<AdminUser> all() {
        return users.all();
    }

    /** {@code sponsor}: the admin creating the account (they know its first password), or null during initial setup. */
    @Transactional
    public List<String> create(String username, String password, String role, String sponsor) {
        List<String> problems = passwordProblems(username, password);
        if (username == null || !username.matches("[a-zA-Z0-9._-]{3,64}")) {
            problems.add("Username must be 3-64 letters, digits, dots, dashes or underscores");
        }
        if (!ROLES.contains(role)) {
            problems.add("Unknown role");
        }
        if (problems.isEmpty() && find(username).isPresent()) {
            problems.add("Username already exists");
        }
        if (problems.isEmpty()) {
            // The creator knows this password: the new admin must replace it at first login.
            insert(username, passwordEncoder.encode(password), role, true, sponsor);
        }
        return problems;
    }

    /** Validates a new account without creating it (used when creation needs a second admin's approval). */
    public List<String> validateNew(String username, String password, String role) {
        List<String> problems = passwordProblems(username, password);
        if (username == null || !username.matches("[a-zA-Z0-9._-]{3,64}")) {
            problems.add("Username must be 3-64 letters, digits, dots, dashes or underscores");
        }
        if (!ROLES.contains(role)) {
            problems.add("Unknown role");
        }
        if (problems.isEmpty() && find(username).isPresent()) {
            problems.add("Username already exists");
        }
        return problems;
    }

    /** Also completes a forced change after a reset: clears the temporary-password flag and expiry. */
    @Transactional
    public List<String> changePassword(String username, String currentPassword, String newPassword) {
        AdminUser user = find(username).orElseThrow();
        List<String> problems = new ArrayList<>();
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            problems.add("Current password is wrong");
            return problems;
        }
        problems.addAll(passwordProblems(username, newPassword));
        if (newPassword != null && newPassword.equals(currentPassword)) {
            problems.add("The new password must be different from the current one");
        }
        if (problems.isEmpty()) {
            mutate(username, false, "password_hash = ?, must_change_password = FALSE, password_expires_at = NULL", passwordEncoder.encode(newPassword));
        }
        return problems;
    }

    /**
     * Gives the account a random temporary password (returned once, valid 24 hours, must be changed at login),
     * unlocks it, and closes its open reset requests. 2FA is kept unless {@code resetTotp}; then {@code handledBy}
     * becomes the account's sponsor, since whoever holds the temporary password could enrol the new 2FA.
     */
    @Transactional
    public String resetPassword(String username, boolean resetTotp, String handledBy) {
        String temporary = temporaryPassword();
        mutate(username, false, "password_hash = ?, must_change_password = TRUE, password_expires_at = ?, failed_attempts = 0, locked_until = NULL",
                passwordEncoder.encode(temporary), Timestamp.from(Instant.now().plus(Duration.ofHours(24))));
        if (resetTotp) {
            mutate(username, false, "totp_secret = NULL, totp_last_step = 0, sponsor = ?", handledBy);
        }
        resets.done(username, handledBy, Instant.now());
        return temporary;
    }

    /** Enabled ADMIN accounts with 2FA set up, i.e. admins able to approve changes. */
    public int activeAdminCount() {
        return users.activeAdminCount();
    }

    /** Creates an account from a stored hash, when an approved change request creates an admin; the proposer is its sponsor. */
    @Transactional
    public void createWithHash(String username, String passwordHash, String role, String sponsor) {
        insert(username, passwordHash, role, true, sponsor);
    }

    public String hash(String password) {
        return passwordEncoder.encode(password);
    }

    // forgot-password requests

    /** Records a request for an existing, enabled account (one open request each). Never reveals whether the account exists. */
    public void requestReset(String username, String ip) {
        Optional<AdminUser> user = find(username);
        if (user.isEmpty() || !user.get().enabled()) {
            return;
        }
        if (!resets.hasOpen(username)) {
            resets.insert(username, ip, Instant.now());
            audit.log(username, "PASSWORD_RESET_REQUESTED", "from " + ip);
        }
    }

    public List<ResetRequest> pendingResetRequests() {
        return resets.pending();
    }

    public void dismissResetRequest(long id, String handledBy) {
        resets.dismiss(id, handledBy, Instant.now());
    }

    /** 20 random characters that always meet the password policy. */
    static String temporaryPassword() {
        String lower = "abcdefghijkmnopqrstuvwxyz", upper = "ABCDEFGHJKLMNPQRSTUVWXYZ", digits = "23456789", symbols = "!@#%*-_+";
        String all = lower + upper + digits + symbols;
        SecureRandom random = new SecureRandom();
        List<Character> chars = new ArrayList<>(List.of(
                lower.charAt(random.nextInt(lower.length())), upper.charAt(random.nextInt(upper.length())),
                digits.charAt(random.nextInt(digits.length())), symbols.charAt(random.nextInt(symbols.length()))));
        while (chars.size() < 20) {
            chars.add(all.charAt(random.nextInt(all.length())));
        }
        Collections.shuffle(chars, random);
        StringBuilder out = new StringBuilder();
        chars.forEach(out::append);
        return out.toString();
    }

    @Transactional
    public void setEnabled(String username, boolean enabled) {
        mutate(username, false, "enabled = ?", enabled);
    }

    /** Server-console recovery only: re-enables the account and re-signs it even if it failed its integrity check. */
    @Transactional
    public void recoverEnable(String username) {
        mutate(username, true, "enabled = TRUE");
    }

    @Transactional
    public void setRole(String username, String role) {
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("Unknown role");
        }
        mutate(username, false, "role = ?", role);
    }

    /**
     * Forces the admin to enrol a new authenticator on next login. {@code sponsor} (the admin who asked for the
     * reset, null during initial setup) is recorded, as they could try to enrol it first.
     */
    @Transactional
    public void resetTotp(String username, String sponsor) {
        if (sponsor == null) {
            mutate(username, false, "totp_secret = NULL, totp_last_step = 0");
        } else {
            mutate(username, false, "totp_secret = NULL, totp_last_step = 0, sponsor = ?", sponsor);
        }
    }

    /** Stored encrypted: someone who can read the database still cannot generate this admin's codes. */
    @Transactional
    public void enrollTotp(String username, String secret, long step) {
        AdminUser user = lock(username);
        if (user.totpSecret() == null) {
            mutate(username, false, "totp_secret = ?, totp_last_step = ?", secrets.encrypt("totp:" + user.id(), secret), step);
        }
    }

    /** The decrypted 2FA secret, or null if none is set or it cannot be decrypted. */
    public String totpSecret(AdminUser user) {
        return secrets.decrypt("totp:" + user.id(), user.totpSecret());
    }

    /**
     * One-time upgrade: accounts that pass the previous signature check get their 2FA secret encrypted and are
     * signed again with the current field set (which also covers the temporary-password fields).
     */
    @Transactional
    public List<String> upgradeSignatures() {
        List<String> upgraded = new ArrayList<>();
        for (AdminUser listed : all()) {
            AdminUser user = lock(listed.username());
            boolean valid = user.integrity() != null && integrity.verify(user.integrity(), "admin", fieldsV1(user))
                    && ledger.isLatest("admin:" + user.id(), user.integrity());
            if (!valid) {
                continue;
            }
            if (user.totpSecret() != null && !SecretBox.isEncrypted(user.totpSecret())) {
                users.setTotpSecret(user.id(), secrets.encrypt("totp:" + user.id(), user.totpSecret()));
            }
            resign(user.id());
            upgraded.add(user.username());
        }
        return upgraded;
    }

    /**
     * N8: accounts that pass their check and still use the original key (signature, or 2FA secret encrypted with it)
     * are encrypted and signed again with the current keys, so the original key can be retired. Returns them.
     */
    @Transactional
    public List<String> moveToCurrentKeys() {
        List<String> moved = new ArrayList<>();
        if (integrity.signsWithLegacy("admin")) {
            return moved;
        }
        for (AdminUser listed : all()) {
            AdminUser user = lock(listed.username());
            boolean legacySecret = SecretBox.isLegacy(user.totpSecret());
            if (!trusted(user) || !(com.plinko.common.KeyRing.isLegacy(user.integrity()) || legacySecret)) {
                continue;
            }
            if (legacySecret) {
                String plain = secrets.decrypt("totp:" + user.id(), user.totpSecret());
                String again = plain == null ? null : secrets.encrypt("totp:" + user.id(), plain);
                if (again != null && !SecretBox.isLegacy(again)) {
                    users.setTotpSecret(user.id(), again);
                }
            }
            resign(user.id());
            moved.add(user.username());
        }
        return moved;
    }

    /**
     * True if the stored account matches its integrity signature and that signature is the newest one in the
     * ledger, i.e. the account was only changed through the application and was not rolled back to an old copy.
     */
    public boolean trusted(AdminUser user) {
        return user.integrity() != null && integrity.verify(user.integrity(), "admin", fields(user))
                && ledger.isLatest("admin:" + user.id(), user.integrity());
    }

    /** Records the current signature of every correctly signed account in the ledger (once, when the ledger is added). */
    @Transactional
    public void recordAllInLedger() {
        for (AdminUser user : all()) {
            if (user.integrity() != null && (integrity.verify(user.integrity(), "admin", fields(user))
                    || integrity.verify(user.integrity(), "admin", fieldsV1(user)))) {
                ledger.record("admin:" + user.id(), user.integrity());
            }
        }
    }

    /** Signs every account as it is now. Only used once, when integrity protection is first switched on. */
    @Transactional
    public void signAll() {
        for (AdminUser user : all()) {
            users.setSecurityStampIfEmpty(user.id(), newStamp());
            resign(user.id());
        }
    }

    /**
     * The admins who set up this account, directly or through accounts they set up themselves. Such accounts
     * may be controlled by the same person, so they never approve each other's changes.
     */
    public java.util.Set<String> sponsors(String username) {
        java.util.Set<String> chain = new java.util.LinkedHashSet<>();
        String current = find(username).map(AdminUser::sponsor).orElse(null);
        while (current != null && chain.add(current) && chain.size() < 50) {
            current = find(current).map(AdminUser::sponsor).orElse(null);
        }
        return chain;
    }

    public boolean related(String a, String b) {
        return a.equals(b) || sponsors(a).contains(b) || sponsors(b).contains(a);
    }

    public void unlock(String username) {
        users.clearLockout(username);
    }

    boolean passwordMatches(AdminUser user, String password) {
        return passwordEncoder.matches(password, user.passwordHash());
    }

    /** Spends the same time as a real check when the username does not exist. */
    void dummyPasswordCheck(String password) {
        passwordEncoder.matches(password, dummyHash);
    }

    void recordFailure(AdminUser user) {
        int attempts = user.failedAttempts() + 1;
        Instant lockedUntil = attempts >= properties.login().maxAttempts()
                ? Instant.now().plus(Duration.ofMinutes(properties.login().lockMinutes())) : null;
        users.setFailures(user.id(), lockedUntil == null ? attempts : 0, lockedUntil);
        if (lockedUntil != null) {
            audit.log(user.username(), "LOGIN_LOCKED", "Locked after " + attempts + " failed attempts");
        }
    }

    /**
     * Records a successful login. With 2FA the code's time step is stored only if it is newer than the last
     * one used, in a single statement, so two logins racing with the same code cannot both succeed.
     */
    boolean recordSuccess(AdminUser user, long totpStep) {
        if (totpStep <= 0) {
            users.recordLogin(user.id(), Instant.now());
            return true;
        }
        return users.recordLogin(user.id(), Instant.now(), totpStep);
    }

    public static List<String> passwordProblems(String username, String password) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < 12) {
            problems.add("Password must be at least 12 characters");
        } else {
            if (password.equalsIgnoreCase(username)) {
                problems.add("Password must differ from the username");
            }
            int classes = (password.matches(".*[a-z].*") ? 1 : 0) + (password.matches(".*[A-Z].*") ? 1 : 0)
                    + (password.matches(".*\\d.*") ? 1 : 0) + (password.matches(".*[^a-zA-Z0-9].*") ? 1 : 0);
            if (classes < 3) {
                problems.add("Password must mix at least 3 of: lowercase, uppercase, digits, symbols");
            }
        }
        return problems;
    }

    private void insert(String username, String passwordHash, String role, boolean mustChangePassword, String sponsor) {
        users.insert(username, passwordHash, role, mustChangePassword, sponsor, newStamp(), Instant.now());
        resign(find(username).orElseThrow().id());
    }

    /**
     * Changes signed fields of one account: locks the row, refuses if it already fails its integrity check,
     * applies the change with a new security stamp (signing out its existing sessions) and signs it again.
     * Must run inside a transaction.
     */
    private void mutate(String username, boolean force, String set, Object... params) {
        AdminUser before = lock(username);
        if (!force && !trusted(before)) {
            audit.log("system", "ADMIN_INTEGRITY_FAILED", username + ": change refused, account was modified outside the admin panel");
            throw new IntegrityException(username);
        }
        List<Object> values = new ArrayList<>(List.of(params));
        values.add(newStamp());
        users.update(before.id(), set + ", security_stamp = ?", values);
        resign(before.id());
    }

    private AdminUser lock(String username) {
        return users.lock(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown admin " + username));
    }

    private void resign(long id) {
        AdminUser user = users.get(id);
        String signature = integrity.sign("admin", fields(user));
        users.setIntegrity(id, signature);
        ledger.record("admin:" + id, signature);
    }

    private static Object[] fields(AdminUser user) {
        return new Object[] {"v2", user.id(), user.username(), user.passwordHash(), user.role(), user.enabled(), user.totpSecret(),
                user.sponsor(), user.securityStamp(), user.mustChangePassword(),
                user.passwordExpiresAt() == null ? null : user.passwordExpiresAt().toEpochMilli()};
    }

    /** Field set of the first signature version, only used by {@link #upgradeSignatures()}. */
    private static Object[] fieldsV1(AdminUser user) {
        return new Object[] {user.id(), user.username(), user.passwordHash(), user.role(), user.enabled(), user.totpSecret(),
                user.sponsor(), user.securityStamp()};
    }

    private static String newStamp() {
        return Crypto.randomToken(24);
    }
}
