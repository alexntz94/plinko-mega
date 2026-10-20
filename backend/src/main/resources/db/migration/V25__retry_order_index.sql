-- Review 3.5 / 5.5: the retry job reads the credits and refunds still owed in the order they are due, straight from an
-- index, instead of sorting every open round on each pass (they pile up during a wallet outage). An index only: built
-- online, no rows rewritten.
CREATE INDEX idx_tx_type_status_retry ON wallet_transaction (type, status, next_retry_at);
