-- N10: the background checks (round signature scan, wallet check, audit chain) run on one instance at a time and keep
-- their progress and results here, so a new instance continues where the last one stopped instead of starting over,
-- and every instance shows the same results.
CREATE TABLE job_state (
    name       VARCHAR(64)  PRIMARY KEY,
    value      MEDIUMTEXT   NOT NULL,
    updated_at DATETIME(3)  NOT NULL
);

-- The wallet check's counts per day the rounds were played (reporting time zone).
CREATE TABLE wallet_check_day (
    day       DATE   PRIMARY KEY,
    checked   BIGINT NOT NULL,
    differing BIGINT NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON `${dbName}`.job_state TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.wallet_check_day TO '${appUser}'@'%';
