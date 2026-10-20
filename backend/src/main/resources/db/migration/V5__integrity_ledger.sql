-- Version ledger for signed records (admin accounts, odds tables). Every change appends the record's new
-- signature with the next version number, signed with the integrity key. Only the newest valid version is
-- accepted, so restoring an older, genuinely signed copy of a row (e.g. a previous role or 2FA secret, or a
-- replaced odds table) is detected. The application's user can only add entries, never change or remove them.
CREATE TABLE integrity_ledger (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity     VARCHAR(64) NOT NULL,
    version    BIGINT      NOT NULL,
    content    CHAR(64)    NOT NULL,
    signature  CHAR(64)    NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_ledger_entity (entity, version)
);

GRANT SELECT, INSERT ON `${dbName}`.integrity_ledger TO '${appUser}'@'%';
