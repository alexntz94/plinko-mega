-- Review R7: records the re-signing job will never sign again with the current keys, kept as evidence: one that fails
-- its check with the original key (e.g. a round kept TAMPERED), or one made after the suspected leak of that key
-- (LEGACY_KEY_SUSPECTED_LEAK: it may be forged). They are left out of what is "still signed only with the original key",
-- so they never stop the key from being removed; once it is, they no longer verify, as they should not. Insert-only.
CREATE TABLE legacy_evidence (
    table_name VARCHAR(32) NOT NULL,
    record_id  VARCHAR(64) NOT NULL,
    reason     VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (table_name, record_id)
);

GRANT SELECT, INSERT ON `${dbName}`.legacy_evidence TO '${appUser}'@'%';
