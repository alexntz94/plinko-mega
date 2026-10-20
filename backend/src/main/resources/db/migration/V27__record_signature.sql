-- Review N8 #2: records signed with the original ROUND_SIGNING_KEY are signed again with the current keys, so the
-- original key can be removed. Rounds, wallet transactions and odds versions are frozen by triggers (their signature
-- column can't change), so their new signature is kept here, one per record: written once, never changed or removed.
CREATE TABLE record_signature (
    table_name VARCHAR(32) NOT NULL,
    record_id  VARCHAR(64) NOT NULL,
    signature  CHAR(64)    NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (table_name, record_id)
);

GRANT SELECT, INSERT ON `${dbName}`.record_signature TO '${appUser}'@'%';
