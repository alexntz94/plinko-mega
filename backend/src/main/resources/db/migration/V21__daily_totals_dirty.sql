-- N5: days whose totals changed after the fact (a round blocked or settled days after it was played). Marking a day is
-- one small write; the minute job rebuilds the marked days, so no day is skipped for good and no player request waits on
-- a rebuild.
CREATE TABLE daily_totals_dirty (
    day       DATE        PRIMARY KEY,
    marked_at DATETIME(3) NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON `${dbName}`.daily_totals_dirty TO '${appUser}'@'%';
