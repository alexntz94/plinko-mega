-- Review N4 #2: today's totals are updated incrementally. The last transaction of each round (and a round blocked or
-- paid by a retry later) writes one row here, insert-only, so live rounds never wait on each other's totals; the minute
-- job adds the rows to the daily totals and removes them. A full rebuild stays for closed days and repairs.
CREATE TABLE round_daily_delta (
    id         BIGINT        AUTO_INCREMENT PRIMARY KEY,
    day        DATE          NOT NULL,
    currency   CHAR(3)       NOT NULL,
    rows_count INT           NOT NULL,
    risk       INT           NOT NULL,
    player_id  BIGINT        NOT NULL,
    rounds     INT           NOT NULL,
    total_bet  DECIMAL(23,2) NOT NULL,
    total_win  DECIMAL(23,2) NOT NULL,
    INDEX idx_daily_delta_day (day, id)
);

GRANT SELECT, INSERT, DELETE ON `${dbName}`.round_daily_delta TO '${appUser}'@'%';
GRANT UPDATE ON `${dbName}`.round_daily_totals TO '${appUser}'@'%';
GRANT UPDATE ON `${dbName}`.round_daily_players TO '${appUser}'@'%';
