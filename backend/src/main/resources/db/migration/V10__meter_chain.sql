-- The bonus meter as a chain: every round that records a meter state also records the round whose state it continued
-- from (meter_prev; "start-<player>" for a player's first one). The unique index lets only one round continue from any
-- state, so two rounds played at the same time (e.g. after someone shortened the round lock in the database) can never
-- both pay out the same pot: the second one is refused and its bet refunded. The current state is the round no other
-- round continued from, which does not depend on any server's clock. Also covered by the round signature (bonus text)
-- and frozen with the outcome.
-- bonus becomes TEXT: many free balls with golden boosts can need more than 2000 characters.
ALTER TABLE game_round
    MODIFY bonus TEXT NULL,
    ADD COLUMN meter_prev VARCHAR(64) NULL AFTER bonus,
    ADD UNIQUE KEY uk_game_round_meter_prev (meter_prev);

DROP TRIGGER game_round_insert_guard;
DROP TRIGGER game_round_guard;

DELIMITER //

CREATE TRIGGER game_round_insert_guard BEFORE INSERT ON game_round FOR EACH ROW
BEGIN
    IF NOT (NEW.status <=> 'PENDING' AND NEW.signature IS NULL AND NEW.total_win IS NULL AND NEW.slots IS NULL
            AND NEW.bonus IS NULL AND NEW.meter_prev IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: a new round must start as PENDING without an outcome';
    END IF;
END//

-- As before (V8), with the meter link frozen together with the rest of the decided outcome.
CREATE TRIGGER game_round_guard BEFORE UPDATE ON game_round FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.player_id <=> OLD.player_id AND NEW.session_id <=> OLD.session_id
            AND NEW.rows_count <=> OLD.rows_count AND NEW.risk <=> OLD.risk AND NEW.bet <=> OLD.bet
            AND NEW.balls <=> OLD.balls AND NEW.total_bet <=> OLD.total_bet AND NEW.currency <=> OLD.currency
            AND NEW.created_at <=> OLD.created_at) THEN
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
