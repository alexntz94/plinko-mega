-- Review 2.3: the game sends an id with every tap (requestId). A repeat of the same request (the answer was lost on the
-- network and the game sends it again) returns the round already played instead of taking a second bet; the unique key
-- makes that hold even when the two arrive at the same moment on different instances. Older games send no id (NULL).
ALTER TABLE game_round ADD COLUMN request_id CHAR(36) NULL;
CREATE UNIQUE INDEX uk_round_request ON game_round (player_id, request_id);
