-- Review R11: a settlement whose automatic sends have stopped (the wallet refused it, or it used up its attempts) is
-- marked here, so the background retry leaves it out in the query itself: stopped ones can no longer fill its batch and
-- keep newer settlements from being sent. A person's "Send to the wallet again" clears the mark.
ALTER TABLE round_settlement ADD COLUMN stopped_at DATETIME(3) NULL;
CREATE INDEX idx_settlement_retry ON round_settlement (status, stopped_at, updated_at);

-- the settlements already stopped by the rule until now: failed with a definite answer, or the default 10 attempts used
UPDATE round_settlement s JOIN wallet_transaction t ON t.id = s.tx_id -- small: pending settlements only (a handful), by primary key
SET s.stopped_at = t.updated_at
WHERE s.status = 'PENDING' AND t.status = 'FAILED'
  AND (t.refused = 1 OR t.error LIKE 'TRANSACTION_MISMATCH:%' OR t.error LIKE 'ROUND_SETTLED:%' OR t.attempts >= 10);
