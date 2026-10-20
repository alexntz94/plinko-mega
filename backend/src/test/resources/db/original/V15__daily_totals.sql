-- Review 3.2: the dashboard and Finance read pre-added daily totals instead of adding up the whole round history on
-- every refresh. Totals per UTC day, currency and board (rows and risk) count the rounds that took a bet and were
-- decided (DEBITED, CREDIT_PENDING, COMPLETED), as the live totals always did; the players table gives the number of
-- different players over any range of days. Both are only a summary of game_round: rebuilt from it day by day (every
-- minute for today and yesterday, and for the day of a round a retry settles later), never a source of money.
CREATE TABLE round_daily_totals (
    day        DATE          NOT NULL,
    currency   CHAR(3)       NOT NULL,
    rows_count INT           NOT NULL,
    risk       INT           NOT NULL,
    rounds     BIGINT        NOT NULL,
    total_bet  DECIMAL(23,2) NOT NULL,
    total_win  DECIMAL(23,2) NOT NULL,
    PRIMARY KEY (day, currency, rows_count, risk),
    INDEX idx_daily_totals_currency (currency, day)
);

CREATE TABLE round_daily_players (
    day       DATE    NOT NULL,
    currency  CHAR(3) NOT NULL,
    player_id BIGINT  NOT NULL,
    PRIMARY KEY (day, currency, player_id),
    INDEX idx_daily_players_currency (currency, day)
);

INSERT INTO round_daily_totals (day, currency, rows_count, risk, rounds, total_bet, total_win)
SELECT DATE(created_at), currency, rows_count, risk, COUNT(*), SUM(total_bet), COALESCE(SUM(total_win), 0)
FROM game_round WHERE status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED')
GROUP BY DATE(created_at), currency, rows_count, risk;

INSERT INTO round_daily_players (day, currency, player_id)
SELECT DISTINCT DATE(created_at), currency, player_id
FROM game_round WHERE status IN ('COMPLETED', 'CREDIT_PENDING', 'DEBITED');

GRANT SELECT, INSERT, DELETE ON `${dbName}`.round_daily_totals TO '${appUser}'@'%';
GRANT SELECT, INSERT, DELETE ON `${dbName}`.round_daily_players TO '${appUser}'@'%';
