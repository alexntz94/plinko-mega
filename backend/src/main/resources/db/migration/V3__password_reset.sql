-- Temporary passwords: must be changed at the next login and expire after 24 hours.
ALTER TABLE admin_user
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN password_expires_at  DATETIME(3) NULL;

-- "Forgot password" requests waiting for an admin.
CREATE TABLE password_reset_request (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64) NOT NULL,
    ip           VARCHAR(64),
    status       VARCHAR(16) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    handled_by   VARCHAR(64),
    handled_at   DATETIME(3),
    INDEX idx_reset_status (status)
);

GRANT SELECT, INSERT, UPDATE ON `${dbName}`.password_reset_request TO '${appUser}'@'%';
