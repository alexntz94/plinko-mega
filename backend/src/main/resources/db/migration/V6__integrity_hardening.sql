-- Room for encrypted values: 2FA secrets and PAM session tokens are stored AES-GCM encrypted, so even
-- someone who can read the database cannot generate 2FA codes or play with a player's PAM session.
ALTER TABLE admin_user MODIFY totp_secret VARCHAR(255) NULL;
ALTER TABLE game_session MODIFY pam_token VARCHAR(1024) NOT NULL;

-- PAM player ids are compared exactly: "Bob" and "bob" are different players.
ALTER TABLE player MODIFY external_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

DROP TRIGGER game_round_guard;
DROP TRIGGER wallet_transaction_guard;

DELIMITER //

-- New rounds and wallet transactions always start unsettled; their outcome can only be reached through
-- the allowed status steps below.
CREATE TRIGGER game_round_insert_guard BEFORE INSERT ON game_round FOR EACH ROW
BEGIN
    IF NOT (NEW.status <=> 'PENDING' AND NEW.signature IS NULL AND NEW.total_win IS NULL AND NEW.slots IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: a new round must start as PENDING without an outcome';
    END IF;
END//

CREATE TRIGGER wallet_transaction_insert_guard BEFORE INSERT ON wallet_transaction FOR EACH ROW
BEGIN
    IF NOT (NEW.status <=> 'PENDING') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wallet_transaction: a new transaction must start as PENDING';
    END IF;
END//

-- A round's bet never changes; once its outcome is signed the outcome never changes; and its status only moves
-- forward along the allowed steps (a refund is only possible for a round without an outcome).
CREATE TRIGGER game_round_guard BEFORE UPDATE ON game_round FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.player_id <=> OLD.player_id AND NEW.session_id <=> OLD.session_id
            AND NEW.rows_count <=> OLD.rows_count AND NEW.risk <=> OLD.risk AND NEW.bet <=> OLD.bet
            AND NEW.balls <=> OLD.balls AND NEW.total_bet <=> OLD.total_bet AND NEW.currency <=> OLD.currency
            AND NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_round: the bet of a round cannot be changed';
    END IF;
    IF OLD.signature IS NOT NULL AND NOT (NEW.signature <=> OLD.signature AND NEW.slots <=> OLD.slots
            AND NEW.multipliers <=> OLD.multipliers AND NEW.total_win <=> OLD.total_win) THEN
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

-- A wallet transaction's id, round, player, type, amount and signature never change; a confirmed one stays
-- confirmed, and a failed one never goes back to pending.
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
END//

DELIMITER ;
