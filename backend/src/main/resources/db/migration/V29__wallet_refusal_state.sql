-- Review R4: whether the wallet definitely refused a transaction (so no money moved) is kept in its own column, which
-- the trigger below guards, instead of a "[refused]" prefix in the free-text error. Before every send of a credit or a
-- rollback the game commits a "sending" mark (in_flight_since set, refused cleared), so a crash after the send leaves
-- "outcome unknown", never a stale "refused" that would let a settlement pay the round a second time.
ALTER TABLE wallet_transaction
    ADD COLUMN refused         TINYINT(1)  NOT NULL DEFAULT 0,
    ADD COLUMN in_flight_since DATETIME(3) NULL;

-- what the error text said until now (only failed rows can carry the mark: idx_tx_status)
UPDATE wallet_transaction SET refused = 1 -- small: only FAILED rows, read from idx_tx_status; a handful
WHERE status = 'FAILED' AND error LIKE '[refused] %';

DROP TRIGGER wallet_transaction_guard;

DELIMITER //

-- As before (V6): a wallet transaction's id, round, player, type, amount and signature never change; a confirmed one
-- stays confirmed, and a failed one never goes back to pending. And (R4): a refusal is recorded only on a failed
-- transaction, either as the wallet's answer to a send marked as in flight or for a transaction never sent; an in-flight
-- mark is removed only by recording an answer. An answer recorded on a failed row without an in-flight mark (a release
-- from before this column) is never taken as a refusal.
CREATE TRIGGER wallet_transaction_guard BEFORE UPDATE ON wallet_transaction FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.round_id <=> OLD.round_id AND NEW.player_id <=> OLD.player_id
            AND NEW.type <=> OLD.type AND NEW.amount <=> OLD.amount AND NEW.original_tx_id <=> OLD.original_tx_id
            AND NEW.signature <=> OLD.signature AND NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: only the status of a transaction can change';
    END IF;
    IF OLD.status = 'OK' AND NOT (NEW.status <=> 'OK') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: a confirmed transaction cannot be reopened';
    END IF;
    IF NOT (NEW.status IN ('PENDING', 'OK', 'FAILED')) OR (OLD.status <> 'PENDING' AND NEW.status = 'PENDING') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: status change not allowed';
    END IF;
    IF OLD.in_flight_since IS NOT NULL AND NEW.in_flight_since IS NULL AND NOT (NEW.attempts <=> OLD.attempts + 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: a send in flight ends only with its answer';
    END IF;
    -- a confirmed or pending transaction is never a refusal
    IF NOT (NEW.status <=> 'FAILED') THEN
        SET NEW.refused = 0;
    END IF;
    -- an older release recording an answer on a failed row (it never marks a send): not proof that nothing moved
    IF OLD.status = 'FAILED' AND OLD.in_flight_since IS NULL AND NEW.in_flight_since IS NULL AND NEW.attempts <> OLD.attempts THEN
        SET NEW.refused = 0;
    END IF;
    IF NEW.refused <> 0 AND NEW.in_flight_since IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: a send in flight cannot be refused';
    END IF;
    IF NEW.refused <> 0 AND OLD.refused = 0 AND NOT (
            (OLD.in_flight_since IS NOT NULL AND NEW.in_flight_since IS NULL)
            OR (OLD.status = 'PENDING' AND OLD.in_flight_since IS NULL AND OLD.attempts = 0)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: a refusal is only the answer to a send, or for one never sent';
    END IF;
END//

DELIMITER ;
