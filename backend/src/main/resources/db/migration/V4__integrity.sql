-- Integrity protection against changes made directly in the database (e.g. with the application's
-- database login): signed admin accounts, odds tables, change requests and wallet transactions, plus
-- triggers that make settled money records unchangeable. Triggers belong to the schema owner, and the
-- application's database user cannot create, change or drop them.

-- sponsor: the admin who created this account or reset its 2FA, i.e. who could have known its credentials.
-- An admin and the accounts they sponsored (directly or through others) never approve each other's changes.
-- security_stamp changes whenever the account's access changes; older sessions are then signed out.
ALTER TABLE admin_user
    ADD COLUMN sponsor        VARCHAR(64) NULL,
    ADD COLUMN security_stamp CHAR(32)    NOT NULL DEFAULT '',
    ADD COLUMN integrity      CHAR(64)    NULL;

CREATE TABLE odds_signature (
    rows_count INT      NOT NULL,
    risk       TINYINT  NOT NULL,
    signature  CHAR(64) NOT NULL,
    PRIMARY KEY (rows_count, risk)
);

-- base_hash: state of what the change modifies when it was proposed; approval is refused if that changed since.
ALTER TABLE change_request
    ADD COLUMN base_hash  CHAR(64)    NULL,
    ADD COLUMN expires_at DATETIME(3) NULL,
    ADD COLUMN signature  CHAR(64)    NULL;

-- One debit, one credit and one rollback at most per round.
ALTER TABLE wallet_transaction
    ADD COLUMN signature CHAR(64) NULL,
    ADD UNIQUE KEY uq_tx_round_type (round_id, type);

GRANT SELECT, INSERT, UPDATE ON `${dbName}`.odds_signature TO '${appUser}'@'%';

DELIMITER //

-- A round's bet never changes; once its outcome is signed the outcome never changes; a finished round is never reopened.
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
END//

-- A wallet transaction's id, round, player, type, amount and signature never change; a confirmed one stays confirmed.
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
END//

-- Payouts go to player.external_id, so it never changes.
CREATE TRIGGER player_guard BEFORE UPDATE ON player FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.external_id <=> OLD.external_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'player: the PAM player id cannot be changed';
    END IF;
END//

CREATE TRIGGER game_session_guard BEFORE UPDATE ON game_session FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.player_id <=> OLD.player_id AND NEW.pam_token <=> OLD.pam_token) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_session: only the expiry of a session can change';
    END IF;
END//

DELIMITER ;
