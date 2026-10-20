-- Review 3.4: each player points at their current bonus-meter round, so a round reads it by its id instead of
-- searching the player's history with LIKE '%meter:%'. Set in the same transaction that saves a round's outcome. The
-- chain's safety is unchanged: only one round can ever continue from a meter state (uk_game_round_meter_prev), so a
-- pointer edited in the database can't pay a pot twice; that round is refused and its bet refunded.
ALTER TABLE player ADD COLUMN meter_round VARCHAR(64) NULL;

UPDATE player p SET p.meter_round = (
    SELECT r.id FROM game_round r
    WHERE r.player_id = p.id AND r.bonus LIKE '%meter:%'
      AND NOT EXISTS (SELECT 1 FROM game_round n WHERE n.meter_prev = r.id)
    ORDER BY r.created_at DESC, r.id DESC LIMIT 1);

-- Review 3.5: indexes for the admin lists, Finance and the background jobs (each list is filtered or sorted on these).
CREATE INDEX idx_round_status_created ON game_round (status, created_at);
CREATE INDEX idx_round_status_updated ON game_round (status, updated_at);
CREATE INDEX idx_tx_created ON wallet_transaction (created_at);
CREATE INDEX idx_tx_status_created ON wallet_transaction (status, created_at);
CREATE INDEX idx_tx_player_created ON wallet_transaction (player_id, created_at);
CREATE INDEX idx_player_last_seen ON player (last_seen_at);
CREATE INDEX idx_audit_action ON audit_log (action, id);
CREATE INDEX idx_audit_username ON audit_log (username, id);
CREATE INDEX idx_session_expires ON game_session (expires_at);
CREATE INDEX idx_token_used ON used_launch_token (used_at);
