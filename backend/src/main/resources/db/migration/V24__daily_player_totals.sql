-- Review 3.3: each player's rounds, bets and wins per day, so Finance's biggest winners and losers add up these small
-- rows (and today's rounds) instead of grouping up to a year of rounds by player. Days built before these columns are
-- rebuilt in the background by the daily totals job (not here: a backfill never runs inside a migration).
ALTER TABLE round_daily_players
    ADD COLUMN rounds    BIGINT        NOT NULL DEFAULT 0,
    ADD COLUMN total_bet DECIMAL(23,2) NOT NULL DEFAULT 0,
    ADD COLUMN total_win DECIMAL(23,2) NOT NULL DEFAULT 0;
