package com.plinko.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.plinko.model.AdminUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code admin_user} table. Signing and the integrity checks stay in AdminUserService. */
@Repository
public class AdminUserRepository {

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM admin_user").query(Long.class).single();
    }

    public Optional<AdminUser> find(String username) {
        return jdbc.sql("SELECT * FROM admin_user WHERE username = ?").param(username).query(this::map).optional();
    }

    public AdminUser get(long id) {
        return jdbc.sql("SELECT * FROM admin_user WHERE id = ?").param(id).query(this::map).single();
    }

    public List<AdminUser> all() {
        return jdbc.sql("SELECT * FROM admin_user ORDER BY username").query(this::map).list();
    }

    /** The account, locked until the transaction ends. */
    public Optional<AdminUser> lock(String username) {
        return jdbc.sql("SELECT * FROM admin_user WHERE username = ? FOR UPDATE").param(username).query(this::map).optional();
    }

    public void insert(String username, String passwordHash, String role, boolean mustChangePassword, String sponsor,
                       String securityStamp, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO admin_user (username, password_hash, role, enabled, must_change_password, sponsor, security_stamp, created_at)
                VALUES (?, ?, ?, TRUE, ?, ?, ?, ?)""")
                .params(username, passwordHash, role, mustChangePassword, sponsor, securityStamp, Timestamp.from(createdAt))
                .update();
    }

    /**
     * Sets signed fields of one account. {@code assignments} is one of AdminUserService's fixed column lists
     * ("role = ?", ...), never user input; the values are bound as parameters.
     */
    public void update(long id, String assignments, List<Object> values) {
        List<Object> params = new ArrayList<>(values);
        params.add(id);
        jdbc.sql("UPDATE admin_user SET " + assignments + " WHERE id = ?").params(params).update();
    }

    public void setIntegrity(long id, String signature) {
        jdbc.sql("UPDATE admin_user SET integrity = ? WHERE id = ?").params(signature, id).update();
    }

    public void setTotpSecret(long id, String storedSecret) {
        jdbc.sql("UPDATE admin_user SET totp_secret = ? WHERE id = ?").params(storedSecret, id).update();
    }

    public void setSecurityStampIfEmpty(long id, String stamp) {
        jdbc.sql("UPDATE admin_user SET security_stamp = ? WHERE id = ? AND security_stamp = ''").params(stamp, id).update();
    }

    public int activeAdminCount() {
        return jdbc.sql("SELECT COUNT(*) FROM admin_user WHERE role = 'ADMIN' AND enabled = TRUE AND totp_secret IS NOT NULL")
                .query(Integer.class).single();
    }

    public void clearLockout(String username) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL WHERE username = ?").param(username).update();
    }

    public void setFailures(long id, int failedAttempts, Instant lockedUntil) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = ?, locked_until = ? WHERE id = ?")
                .params(failedAttempts, lockedUntil == null ? null : Timestamp.from(lockedUntil), id)
                .update();
    }

    public void recordLogin(long id, Instant at) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")
                .params(Timestamp.from(at), id).update();
    }

    /**
     * Records a login with a 2FA code of time step {@code totpStep}, only if that step is newer than the last one used,
     * in a single statement (two logins racing with the same code cannot both succeed). False if it was not newer.
     */
    public boolean recordLogin(long id, Instant at, long totpStep) {
        return jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = ?, totp_last_step = ? WHERE id = ? AND totp_last_step < ?")
                .params(Timestamp.from(at), totpStep, id, totpStep)
                .update() == 1;
    }

    private AdminUser map(ResultSet rs, int n) throws SQLException {
        Timestamp locked = rs.getTimestamp("locked_until");
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        Timestamp expires = rs.getTimestamp("password_expires_at");
        return new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("role"),
                rs.getBoolean("enabled"), rs.getString("totp_secret"), rs.getLong("totp_last_step"), rs.getInt("failed_attempts"),
                locked == null ? null : locked.toInstant(), lastLogin == null ? null : lastLogin.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getBoolean("must_change_password"),
                expires == null ? null : expires.toInstant(), rs.getString("sponsor"), rs.getString("security_stamp"),
                rs.getString("integrity"));
    }
}
