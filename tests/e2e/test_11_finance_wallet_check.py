"""Finance: checking the wallet's record against the game's (review 7.1). (The Finance figures against the rounds are in
test_03_wallet_rounds, where every round is known.)"""
from conftest import sql


def test_wallet_check(check, admin):
    # 7.1 a bet the wallet has but the game lost (as after a restore)
    sql("INSERT INTO mock_transaction (transaction_id, player_id, type, amount, balance_after, round_id, created_at) "
        "VALUES ('e2e-lost-by-restore', 'nobody', 'DEBIT', 3.00, 0, 'e2e-lost-round', UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE)")
    status, _, html = admin.get("/admin/finance/wallet-check?run=1")
    check("7.1 the wallet check finds money the wallet moved for a round the game doesn't have", status == 200
          and "e2e-lost-by-restore" in html and "Differences found" in html, status)
    status, _, html = admin.get("/admin/finance/wallet-check?run=1&from=2026-01-01T00:00&to=2026-03-01T00:00")
    check("7.1 the wallet check refuses ranges over 7 days", "up to 7 days" in html, status)
    check("7.1 every wallet check is in the audit log", sql("SELECT COUNT(*) FROM audit_log WHERE action='WALLET_CHECK'")[1] != "0")
