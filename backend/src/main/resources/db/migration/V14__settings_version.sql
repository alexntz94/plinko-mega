-- Review 3.1: the settings are kept in memory on every instance. Each round reads only this number (one row, by its
-- primary key); every change made through the application raises it in the same transaction, so an approved change
-- applies at once on every instance. A direct edit of the settings doesn't raise it, but the full re-read every minute
-- still finds the edit and stops the game, as before. Raising it by hand only makes every instance read the settings again.
CREATE TABLE settings_version (
    id      TINYINT PRIMARY KEY,
    version BIGINT  NOT NULL
);

INSERT INTO settings_version (id, version) VALUES (1, 0);

GRANT SELECT, UPDATE ON `${dbName}`.settings_version TO '${appUser}'@'%';
