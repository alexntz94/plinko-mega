-- Review 7.3: a blocked (TAMPERED) round stays blocked for good, as evidence, but can be settled with the player once
-- two admins agree: pay an amount, refund the bet, or close it with nothing owed. The primary key allows one settlement
-- per round, ever; its money moves in a wallet_transaction with an id derived from the round, so it is paid at most once
-- even if a request is sent twice. Only the status of a settlement can change, and only from PENDING to DONE.
CREATE TABLE round_settlement (
    round_id      CHAR(36)      PRIMARY KEY,
    action        VARCHAR(10)   NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,
    tx_id         CHAR(36)      NULL,
    change_id     BIGINT        NOT NULL,
    proposed_by   VARCHAR(64)   NOT NULL,
    approved_by   VARCHAR(64)   NOT NULL,
    reason        VARCHAR(500)  NOT NULL,
    status        VARCHAR(10)   NOT NULL,
    signature     CHAR(64)      NOT NULL,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    CONSTRAINT fk_settlement_round FOREIGN KEY (round_id) REFERENCES game_round (id),
    INDEX idx_settlement_status (status)
);

DELIMITER //

CREATE TRIGGER round_settlement_insert_guard BEFORE INSERT ON round_settlement FOR EACH ROW
BEGIN
    IF NOT (NEW.action IN ('PAY', 'REFUND', 'CLOSE') AND NEW.amount >= 0 AND NEW.status IN ('PENDING', 'DONE')
            AND (SELECT status FROM game_round WHERE id = NEW.round_id) <=> 'TAMPERED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'round_settlement: only a blocked round can be settled';
    END IF;
END//

CREATE TRIGGER round_settlement_guard BEFORE UPDATE ON round_settlement FOR EACH ROW
BEGIN
    IF NOT (NEW.round_id <=> OLD.round_id AND NEW.action <=> OLD.action AND NEW.amount <=> OLD.amount AND NEW.tx_id <=> OLD.tx_id
            AND NEW.change_id <=> OLD.change_id AND NEW.proposed_by <=> OLD.proposed_by AND NEW.approved_by <=> OLD.approved_by
            AND NEW.reason <=> OLD.reason AND NEW.signature <=> OLD.signature AND NEW.created_at <=> OLD.created_at)
       OR (OLD.status = 'DONE' AND NOT (NEW.status <=> 'DONE')) OR NEW.status NOT IN ('PENDING', 'DONE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'round_settlement: only a pending settlement can be marked done';
    END IF;
END//

DELIMITER ;

GRANT SELECT, INSERT, UPDATE ON `${dbName}`.round_settlement TO '${appUser}'@'%';
