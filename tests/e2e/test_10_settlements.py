"""Settling a blocked round with the player (review 7.3, N2): one admin proposes, another approves, the wallet is paid
once and within the limits; a round whose credit may already have been applied can't be paid again."""
import time
from decimal import Decimal

from conftest import SMALL, api, app, fault, last_round, mock_balance, pam_rows, retry, round_status, sql

REASON = "e2e: genuine round, odds history fixed after the incident"


def settle_requests():
    return int(sql("SELECT COUNT(*) FROM change_request WHERE type='ROUND_SETTLE'")[1])


def test_settle_blocked_round(check, admin, second_admin, faulty, blocked_round):
    rid = blocked_round["rid"]
    _, _, html = admin.get("/admin")
    check("7.3 the dashboard lists blocked rounds still to settle", "Blocked rounds to settle" in html and rid[:8] in html, "list")
    # N2: the round's credit was refused outright (5.2), so a payment may be proposed; one whose outcome is unknown is
    # refused (test_unknown_outcome_not_paid)
    requests_before = settle_requests()
    admin.post_form(f"/admin/rounds/{rid}/settle", {"action": "PAY", "amount": "999999", "reason": REASON}, f"/admin/rounds/{rid}")
    check("7.3 a settlement above the limit is refused", settle_requests() == requests_before)
    admin.post_form(f"/admin/rounds/{rid}/settle", {"action": "PAY", "amount": "1.50", "reason": REASON}, f"/admin/rounds/{rid}")
    change = sql("SELECT MAX(id) FROM change_request WHERE type='ROUND_SETTLE' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{change}/approve", {}, "/admin/changes")
    check("7.3 the proposer cannot approve their own settlement", sql(f"SELECT status FROM change_request WHERE id={change}")[1] == "PENDING"
          and pam_rows(rid, "CREDIT") == 0, change)
    paid_before = mock_balance(faulty.pid)
    second_admin.post_form(f"/admin/changes/{change}/approve", {}, "/admin/changes")
    time.sleep(1)
    check("7.3 approved by a second admin: paid once through the wallet, the round stays blocked",
          sql(f"SELECT status FROM change_request WHERE id={change}")[1] == "APPROVED"
          and sql(f"SELECT CONCAT(status, '/', action, '/', amount) FROM round_settlement WHERE round_id='{rid}'")[1] == "DONE/PAY/1.50"
          and pam_rows(rid, "CREDIT") == 1 and mock_balance(faulty.pid) == paid_before + Decimal("1.50")
          and round_status(rid) == "TAMPERED", (sql(f"SELECT * FROM round_settlement WHERE round_id='{rid}'")[1], mock_balance(faulty.pid) - paid_before))
    second_admin.post_form(f"/admin/changes/{change}/approve", {}, "/admin/changes")
    second_admin.post_form(f"/admin/rounds/{rid}/settle/send", {}, f"/admin/rounds/{rid}")
    admin.post_form(f"/admin/rounds/{rid}/settle", {"action": "PAY", "amount": "1.00", "reason": REASON}, f"/admin/rounds/{rid}")
    check("7.3 approving again, resending or a second settlement pays nothing more", pam_rows(rid, "CREDIT") == 1
          and mock_balance(faulty.pid) == paid_before + Decimal("1.50")
          and settle_requests() == requests_before + 1, pam_rows(rid, "CREDIT"))
    code, out = app(f"UPDATE round_settlement SET amount=500 WHERE round_id='{rid}'")
    check("7.3 a settlement can't be edited in the database", code != 0 and "only a pending settlement" in out, out[-120:])
    _, _, html = admin.get("/admin")
    check("7.3 a settled round leaves the dashboard list", rid[:8] not in html.split("Latest rounds")[0], "list")


def test_unknown_outcome_not_paid(check, admin, faulty):
    # N2: a blocked round whose credit failed (the wallet may have paid it) can't be paid or refunded again, only closed
    fault("credit", "error")
    api("/api/game/play", SMALL, faulty.sid)
    unknown_rid, _ = last_round()
    uref = sql(f"SELECT odds_ref FROM game_round WHERE id='{unknown_rid}'")[1]
    uoriginal = sql(f"SELECT multipliers FROM odds_history WHERE id={uref}")[1]
    sql(f"UPDATE odds_history SET multipliers=CONCAT('9', multipliers) WHERE id={uref}")
    retry(unknown_rid)
    sql(f"UPDATE odds_history SET multipliers='{uoriginal}' WHERE id={uref}")
    settles = settle_requests()
    admin.post_form(f"/admin/rounds/{unknown_rid}/settle", {"action": "PAY", "amount": "0.50", "reason": REASON}, f"/admin/rounds/{unknown_rid}")
    check("N2 a payment is refused while the round's credit may already have been applied",
          round_status(unknown_rid) == "TAMPERED" and settle_requests() == settles, round_status(unknown_rid))
