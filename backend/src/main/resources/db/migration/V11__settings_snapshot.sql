-- A copy of the settings at every version recorded in the integrity ledger, so settings edited in the database can be
-- put back exactly ("Restore settings"). A copy needs no signature of its own: it is used only if its SHA-256 is the
-- newest signed ledger version, so a copy added by someone without the key is never used. The application's user can
-- only add copies, never change or remove them.
CREATE TABLE settings_snapshot (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint CHAR(64)    NOT NULL,
    content     MEDIUMTEXT  NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    INDEX idx_settings_snapshot_fingerprint (fingerprint)
);

GRANT SELECT, INSERT ON `${dbName}`.settings_snapshot TO '${appUser}'@'%';
-- Restoring removes settings rows added in the database; the application's user could already change every value.
GRANT DELETE ON `${dbName}`.setting TO '${appUser}'@'%';
