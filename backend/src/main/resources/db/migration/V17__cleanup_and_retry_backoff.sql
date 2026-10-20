-- Review 3.7: expired game sessions and used launch tokens are deleted after 30 days (a nightly job). The used tokens
-- are what make a launch link single-use, so the application may delete only rows at least 30 days old: these guards
-- refuse anything newer, even to someone holding the application's database login. (30 days: until compliance sets
-- the retention periods, decision D6. A PAM token must not stay valid longer than that.)
DELIMITER //

CREATE TRIGGER used_launch_token_cleanup_guard BEFORE DELETE ON used_launch_token FOR EACH ROW
BEGIN
    IF OLD.used_at > UTC_TIMESTAMP(3) - INTERVAL 30 DAY THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'used_launch_token: only tokens used more than 30 days ago can be removed';
    END IF;
END//

CREATE TRIGGER game_session_cleanup_guard BEFORE DELETE ON game_session FOR EACH ROW
BEGIN
    IF OLD.expires_at > UTC_TIMESTAMP(3) - INTERVAL 30 DAY THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'game_session: only sessions expired more than 30 days ago can be removed';
    END IF;
END//

DELIMITER ;

GRANT DELETE ON `${dbName}`.used_launch_token TO '${appUser}'@'%';
GRANT DELETE ON `${dbName}`.game_session TO '${appUser}'@'%';

-- Review 3.8: a credit or refund the wallet refused waits before its next try (30 s, 1, 2, 4 ... up to 15 minutes), so a
-- wallet that is down is not hammered, and the background retry takes the ones that are due first, in batches.
ALTER TABLE wallet_transaction ADD COLUMN next_retry_at DATETIME(3) NULL;
CREATE INDEX idx_tx_retry ON wallet_transaction (status, next_retry_at);
