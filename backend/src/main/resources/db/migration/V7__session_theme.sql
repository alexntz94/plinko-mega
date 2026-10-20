-- The look (theme) a game session was opened with, so a page reload keeps it.
ALTER TABLE game_session ADD COLUMN theme VARCHAR(32) NULL;
