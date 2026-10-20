"""7.2 key rotation: new keys per use, newest first. Everything made before still verifies and decrypts (2FA, odds,
sessions, a round waiting for its credit); new data names its new key. The lists are Docker secrets (files in
SECRETS_DIR, empty until now): written in place, the recreated backend reads them. The rotated keys stay for the rest of
the run."""
import re, time
from decimal import Decimal

from conftest import ENV, SMALL, api, fault, fresh_token, last_round, mock_balance, pam_rows, restart_backend, retry, sql

KEYS_ENV = {"DEMO_MODE": "false"}
INTEGRITY_FAILURES = "SELECT COUNT(*) FROM audit_log WHERE action IN ('ROUNDS_INTEGRITY_FAILED', 'ROUND_TAMPERED', 'CHANGE_INTEGRITY_FAILED')"


def test_key_rotation(check, admin, tester, faulty):
    status, before_rotation = api("/api/game/session", {"token": fresh_token(tester.pid)})
    old_sid = before_rotation.get("sessionId")

    # N1 #3: a round waiting for its credit when the keys are rotated is paid after the rotation, once, with the credit
    # id it was given before (made with the original key)
    faulty.new_session()
    pending_before = mock_balance(faulty.pid)
    fault("credit", "error")
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    pending_rid, st = last_round()
    credit_id = sql(f"SELECT id FROM wallet_transaction WHERE round_id='{pending_rid}' AND type='CREDIT'")[1]
    check("N1 #3 before the rotation: a failed credit leaves the round waiting as CREDIT_PENDING, its credit id made",
          status == 200 and st == "CREDIT_PENDING" and credit_id != "" and pam_rows(pending_rid, "CREDIT") == 0, (status, st, credit_id))

    for key_name, key_list in {"ROUND_KEYS": "r1=" + "R" * 48, "RECORD_KEYS": "d1=" + "D" * 48, "ENCRYPTION_KEYS": "e1=" + "E" * 48}.items():
        with open(ENV["SECRETS_DIR"] + "/" + key_name, "w") as f:
            f.write(key_list)
    failures_before = sql(INTEGRITY_FAILURES)[1]
    restart_backend(KEYS_ENV)
    time.sleep(5)
    b, _, location = admin.login()
    check("7.2 after a key rotation: 2FA set up before it still works", location.endswith("/admin"), location)
    admin.session = b
    status, ks = api("/api/game/session", {"token": fresh_token(tester.pid)})
    check("7.2 the odds signed before the rotation still load", status == 200 and ks.get("config", {}).get("rows"), status)
    time.sleep(1.2)
    status, kr = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, ks.get("sessionId"))
    sig, st = sql(f"SELECT signature, status FROM game_round WHERE id='{kr.get('roundId')}'")[1].split("\t") if status == 200 else ("", "")
    check("7.2 a new round is signed with the new round key and paid", sig.startswith("r1.") and st == "COMPLETED", (status, sig[:4], st))
    token_stored = sql(f"SELECT pam_token FROM game_session WHERE id='{ks.get('sessionId')}'")[1] if ks.get("sessionId") else ""
    check("7.2 new secrets are encrypted with the new encryption key", token_stored.startswith("v2:e1:"), token_stored[:6])
    time.sleep(1.2)
    status, _ = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, old_sid)
    check("7.2 a session opened before the rotation keeps playing", status == 200, status)

    st = retry(pending_rid, by=b)
    win = Decimal(sql(f"SELECT IFNULL(total_win, 0) FROM game_round WHERE id='{pending_rid}'")[1])
    paid_id = sql(f"SELECT transaction_id FROM mock_transaction WHERE round_id='{pending_rid}' AND type='CREDIT'")[1]
    check("N1 #3 after the rotation the retry pays the round: COMPLETED, paid exactly once, with the credit id made before it",
          st == "COMPLETED" and pam_rows(pending_rid, "CREDIT") == 1 and paid_id == credit_id
          and sql(f"SELECT id FROM wallet_transaction WHERE round_id='{pending_rid}' AND type='CREDIT'")[1] == credit_id
          and mock_balance(faulty.pid) == pending_before - 1 + win,
          (st, pam_rows(pending_rid, "CREDIT"), credit_id, paid_id, pending_before, win, mock_balance(faulty.pid)))

    check("7.2 nothing made before the rotation is reported as changed", sql(INTEGRITY_FAILURES)[1] == failures_before, failures_before)
    _, _, html = b.get("/admin")
    check("7.2 the dashboard shows no new alarm after the rotation", re.search(r"failing signature check</span><strong>0", html) is not None
          or failures_before != "0", "dashboard")
