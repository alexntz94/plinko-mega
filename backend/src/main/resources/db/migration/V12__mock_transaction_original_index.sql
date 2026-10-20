-- The mock PAM looks up rollbacks by the transaction they cancel (a bet arriving after its rollback is refused);
-- without an index every bet scanned the whole table.
CREATE INDEX idx_mock_transaction_original ON mock_transaction (original_tx_id);
