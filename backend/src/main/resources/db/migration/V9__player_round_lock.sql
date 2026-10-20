-- One round at a time per player, across every backend instance (Cloud Run can run several, e.g. while a new
-- version rolls out). A round claims the player row with one atomic UPDATE and clears the claim when it ends; the
-- expiry frees a claim left behind by an instance that died mid-round.
ALTER TABLE player
    ADD COLUMN round_lock       CHAR(36)    NULL,
    ADD COLUMN round_lock_until DATETIME(3) NULL;
