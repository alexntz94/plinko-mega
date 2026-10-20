-- PAM contract R1.7 in the mock wallet (the reference implementation): a launch token is valid 24 hours after it was
-- issued. Tokens issued before this column existed have no time and keep working.
ALTER TABLE mock_wallet ADD COLUMN token_issued_at DATETIME(3) NULL;
