-- Every win is checked against the stored round before it is paid (WinCheck). For that, a round records two things
-- at the moment its bet is taken, both covered by the round signature and frozen with the bet:
--   odds_ref: the exact odds version it was played on (an entry of odds_history), so each ball's multiplier can be
--             checked against the table it was drawn from, even after the odds were changed;
--   max_win:  the hard limit on the win per round in force then, so lowering the limit later never blocks a genuine
--             win, and a win above the limit of its own time is never paid.
-- odds_history keeps every odds version ever used, signed with the integrity key. The application's user can only
-- add entries, never change or remove them.
CREATE TABLE odds_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rows_count  INT          NOT NULL,
    risk        INT          NOT NULL,
    multipliers VARCHAR(500) NOT NULL,
    signature   CHAR(64)     NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_odds_history (rows_count, risk, multipliers)
);

GRANT SELECT, INSERT ON `${dbName}`.odds_history TO '${appUser}'@'%';

ALTER TABLE game_round
    ADD COLUMN odds_ref BIGINT        NULL AFTER risk,
    ADD COLUMN max_win  DECIMAL(19,2) NULL AFTER total_bet;

DROP TRIGGER game_round_guard;

DELIMITER //

-- As before (V10), with the odds version and the win limit frozen together with the bet.
CREATE TRIGGER game_round_guard BEFORE UPDATE ON game_round FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.player_id <=> OLD.player_id AND NEW.session_id <=> OLD.session_id
            AND NEW.rows_count <=> OLD.rows_count AND NEW.risk <=> OLD.risk AND NEW.bet <=> OLD.bet
            AND NEW.balls <=> OLD.balls AND NEW.total_bet <=> OLD.total_bet AND NEW.currency <=> OLD.currency
            AND NEW.created_at <=> OLD.created_at AND NEW.odds_ref <=> OLD.odds_ref AND NEW.max_win <=> OLD.max_win) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: the bet of a round cannot be changed';
    END IF;
    IF OLD.signature IS NOT NULL AND NOT (NEW.signature <=> OLD.signature AND NEW.slots <=> OLD.slots
            AND NEW.multipliers <=> OLD.multipliers AND NEW.total_win <=> OLD.total_win AND NEW.bonus <=> OLD.bonus
            AND NEW.meter_prev <=> OLD.meter_prev) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: a decided outcome cannot be changed';
    END IF;
    IF OLD.status IN ('COMPLETED', 'CANCELLED', 'REJECTED', 'TAMPERED') AND NOT (NEW.status <=> OLD.status) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: a finished round cannot be reopened';
    END IF;
    IF NOT (NEW.status <=> OLD.status
            OR (OLD.status = 'PENDING' AND NEW.status IN ('DEBITED', 'REJECTED', 'CANCELLED', 'TAMPERED'))
            OR (OLD.status = 'PENDING' AND NEW.status = 'ROLLBACK_PENDING' AND NEW.signature IS NULL)
            OR (OLD.status = 'PENDING' AND NEW.status = 'CREDIT_PENDING' AND NEW.signature IS NOT NULL)
            OR (OLD.status = 'DEBITED' AND NEW.status IN ('COMPLETED', 'CREDIT_PENDING', 'TAMPERED'))
            OR (OLD.status = 'DEBITED' AND NEW.status = 'ROLLBACK_PENDING' AND NEW.signature IS NULL)
            OR (OLD.status = 'CREDIT_PENDING' AND NEW.status IN ('COMPLETED', 'TAMPERED'))
            OR (OLD.status = 'ROLLBACK_PENDING' AND NEW.status IN ('CANCELLED', 'TAMPERED'))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: status change not allowed';
    END IF;
    IF NEW.status IN ('DEBITED', 'CREDIT_PENDING', 'COMPLETED') AND NEW.status <> OLD.status
            AND (NEW.signature IS NULL OR NEW.total_win IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: a round can only be settled with a signed outcome';
    END IF;
END//

DELIMITER ;
