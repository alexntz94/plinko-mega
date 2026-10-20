-- Review 7.9: Finance and the daily totals count days in the casino's reporting time zone (REPORTING_TIME_ZONE, UTC by
-- default). This remembers which zone the daily totals were built in, so a change of zone rebuilds them once.
CREATE TABLE daily_totals_meta (
    name  VARCHAR(32) PRIMARY KEY,
    value VARCHAR(64) NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON `${dbName}`.daily_totals_meta TO '${appUser}'@'%';
