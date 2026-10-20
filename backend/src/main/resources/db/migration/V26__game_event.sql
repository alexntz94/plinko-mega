-- Review 7.4: what the game client reports having shown the player (the reality check shown and continued, the session
-- clock shown), recorded on the server for markets that require proof. created_at is the server's time; client_at the
-- time the client gave, kept as it said it. The application only adds and reads rows: they are never changed or removed.
-- No foreign key to game_session: expired sessions are deleted, their events stay as the proof.
CREATE TABLE game_event (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    player_id   BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    client_at   DATETIME(3),
    created_at  DATETIME(3)  NOT NULL,
    CONSTRAINT fk_event_player FOREIGN KEY (player_id) REFERENCES player (id),
    INDEX idx_event_session (session_id, id),
    INDEX idx_event_player (player_id, type, created_at)
);

GRANT SELECT, INSERT ON `${dbName}`.game_event TO '${appUser}'@'%';
