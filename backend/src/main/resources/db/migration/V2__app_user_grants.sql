-- Least privilege for the runtime database user (migrations run as a separate owner account).
-- The application can never delete rounds or transactions, and can only append to the audit log.
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.admin_user         TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.setting            TO '${appUser}'@'%';
GRANT SELECT, INSERT, DELETE ON `${dbName}`.odds               TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.player             TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.game_session       TO '${appUser}'@'%';
GRANT SELECT, INSERT         ON `${dbName}`.used_launch_token  TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.game_round         TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.wallet_transaction TO '${appUser}'@'%';
GRANT SELECT, INSERT         ON `${dbName}`.audit_log          TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.change_request     TO '${appUser}'@'%';
GRANT SELECT, INSERT, UPDATE ON `${dbName}`.mock_wallet        TO '${appUser}'@'%';
GRANT SELECT, INSERT         ON `${dbName}`.mock_transaction   TO '${appUser}'@'%';
