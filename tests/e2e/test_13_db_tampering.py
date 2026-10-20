"""Tampering in the database, mostly with the application's own login: paid rounds, wins, wallet transactions, forged
rounds and credits, odds edited or rolled back, a pending change edited, the audit log; and what that login may not do."""
import re, time

from conftest import FAIR, api, app, fresh_token, mock_balance, sql, wait_session


def won_round(tester):
    """A paid round with a win (tester plays small rounds until one wins, if none exists yet)."""
    for _ in range(40):
        rid = sql("SELECT id FROM game_round WHERE status='COMPLETED' AND total_win > 0 ORDER BY created_at LIMIT 1")[1]
        if rid:
            return rid
        time.sleep(0.3)
        api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 3}, tester.sid)
    return ""


def test_paid_rounds_frozen(check, admin, tester):
    pid = tester.pid
    rid = won_round(tester)
    code, out = app(f"UPDATE game_round SET status='CREDIT_PENDING' WHERE id='{rid}'")
    check("database refuses to reopen a paid round (no credit replay)", code != 0 and "cannot be reopened" in out, out)
    code, out = app(f"UPDATE game_round SET total_win = total_win + 5000 WHERE id='{rid}'")
    check("database refuses to change a decided win", code != 0 and "outcome cannot be changed" in out, out)
    code, out = app(f"UPDATE wallet_transaction SET amount = amount + 5000 WHERE round_id='{rid}' AND type='CREDIT'")
    check("database refuses to change a wallet transaction", code != 0 and "only the status" in out, out)
    code, out = app(f"UPDATE wallet_transaction SET status='FAILED' WHERE round_id='{rid}' AND type='CREDIT'")
    check("database refuses to reopen a confirmed payment", code != 0 and "cannot be reopened" in out, out)
    code, out = app(f"UPDATE player SET external_id='accomplice' WHERE external_id='{pid}'")
    check("database refuses to redirect a player's payouts", code != 0 and "cannot be changed" in out, out)

    # a round cannot be inserted as already won
    player_row = sql(f"SELECT id FROM player WHERE external_id='{pid}'")[1]
    code, out = app(f"""INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, total_win, currency, status, slots, multipliers, signature, created_at, updated_at)
            VALUES (UUID(), {player_row}, 'x', 8, 2, 100, 1, 100, 5000, 'EUR', 'COMPLETED', '0', '50', REPEAT('a', 64), NOW(3), NOW(3))""")
    check("database refuses a round inserted as already settled", code != 0 and "must start as PENDING" in out, out)
    app(f"""INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at)
            VALUES (UUID(), {player_row}, 'x', 8, 2, 100, 1, 100, 'EUR', 'PENDING', NOW(3), NOW(3))""")
    newest = sql("SELECT id FROM game_round ORDER BY created_at DESC LIMIT 1")[1]
    code, out = app(f"UPDATE game_round SET status='COMPLETED', total_win=5000 WHERE id='{newest}'")
    check("database refuses to settle a round without a signed outcome", code != 0, out)

    # a forged pending round with a big win and a forged credit, built through the allowed steps: never paid
    fake = "fa4e0000-0000-4000-8000-000000000001"
    app(f"""INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at)
            VALUES ('{fake}', {player_row}, 'x', 8, 2, 100, 1, 100, 'EUR', 'PENDING', NOW(3), NOW(3))""")
    app(f"UPDATE game_round SET slots='0', multipliers='50', total_win=5000, signature=REPEAT('a', 64), status='CREDIT_PENDING' WHERE id='{fake}'")
    app(f"""INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, signature, attempts, created_at, updated_at)
            VALUES (UUID(), '{fake}', {player_row}, 'CREDIT', 5000, 'PENDING', REPEAT('b', 64), 0, NOW(3), NOW(3))""")
    app(f"UPDATE wallet_transaction SET status='FAILED' WHERE round_id='{fake}'")
    before = mock_balance(pid)
    admin.post_form(f"/admin/rounds/{fake}/retry", {}, f"/admin/rounds/{fake}")
    check("forged round with a forged credit is never paid", mock_balance(pid) == before, (before, mock_balance(pid)))
    check("forged round flagged TAMPERED and audited", sql(f"SELECT status FROM game_round WHERE id='{fake}'")[1] == "TAMPERED"
          and sql("SELECT COUNT(*) FROM audit_log WHERE action='ROUND_TAMPERED'")[1] != "0")
    _, _, html = admin.get("/admin")
    check("dashboard reports the tampering", re.search(r"failing signature check</span><strong>[1-9]", html) is not None)


def odds_snapshot():
    rows = sql("SELECT prize_index, multiplier, probability FROM odds WHERE rows_count=8 AND risk=2 ORDER BY prize_index")[1].splitlines()
    return rows, sql("SELECT signature FROM odds_signature WHERE rows_count=8 AND risk=2")[1]


def odds_restore(snapshot):
    rows, signature = snapshot
    app("DELETE FROM odds WHERE rows_count=8 AND risk=2")
    for line in rows:
        i, m, pr = line.split()
        app(f"INSERT INTO odds (rows_count, risk, prize_index, multiplier, probability) VALUES (8, 2, {i}, {m}, {pr})")
    app(f"UPDATE odds_signature SET signature='{signature}' WHERE rows_count=8 AND risk=2")


def test_edited_odds_stop_the_game(check, admin, second_admin, tester):
    # odds edited directly in the database: the game stops, the panel raises the alarm, an approved fix restores it
    pid = tester.pid
    old_odds = odds_snapshot()
    app("DELETE FROM odds WHERE rows_count=8 AND risk=2 AND prize_index=4")
    app("INSERT INTO odds (rows_count, risk, prize_index, multiplier, probability) VALUES (8, 2, 4, 1000, 0.78125)")
    status, body = wait_session(pid, 503)
    check("odds edited in the database stop the game within a minute", status == 503 and body.get("code") == "GAME_DISABLED", (status, body))
    _, _, html = admin.get("/admin")
    check("admin panel raises an integrity alarm for the odds", "Integrity alarm" in html and "8 rows / High" in html)
    admin.post_form("/admin/odds/propose", FAIR, "/admin/odds?rows=8")
    fid = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{fid}/approve", {}, "/admin/changes")
    status, _ = api("/api/game/session", {"token": fresh_token(pid)})
    check("an approved fix restores the game", status == 200 and sql(f"SELECT status FROM change_request WHERE id={fid}")[1] == "APPROVED", status)

    # putting back an older, genuinely signed and once-approved table is detected too
    current_odds = odds_snapshot()
    odds_restore(old_odds)
    status, body = wait_session(pid, 503)
    check("an older approved odds table put back in the database stops the game", status == 503, (status, body))
    _, _, html = admin.get("/admin")
    check("alarm says an older version was put back", "older approved version was put back" in html)
    odds_restore(current_odds)
    status, _ = wait_session(pid, 200)
    check("the current table put back restores the game", status == 200, status)

    # a pending change whose stored payload is swapped in the database cannot be approved
    admin.post_form("/admin/odds/propose", dict(FAIR, multiplier=["0.2", "0.3", "1.5", "4", "27.5"]), "/admin/odds?rows=8")
    cid = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS' AND status='PENDING'")[1]
    app(f"UPDATE change_request SET payload = REPLACE(payload, '27.5', '1000') WHERE id={cid}")
    second_admin.post_form(f"/admin/changes/{cid}/approve", {}, "/admin/changes")
    check("a change edited in the database cannot be approved", sql(f"SELECT status FROM change_request WHERE id={cid}")[1] == "PENDING"
          and sql("SELECT MAX(multiplier) FROM odds WHERE rows_count=8 AND risk=2")[1] == "28.00")
    admin.post_form(f"/admin/changes/{cid}/reject", {}, "/admin/changes")


def test_audit_log_and_db_grants(check, admin):
    aid = sql("SELECT MIN(id) FROM audit_log")[1]
    _, _, html = admin.get("/admin/audit")
    check("audit chain intact before tampering", "Hash chain intact" in html)
    sql(f"UPDATE audit_log SET details='nothing happened' WHERE id={aid}")
    _, _, html = admin.get("/admin/audit")
    check("audit log edit detected", "BROKEN" in html)

    # least-privilege DB user
    rid = sql("SELECT id FROM game_round ORDER BY created_at LIMIT 1")[1] or "none"
    code, out = app(f"DELETE FROM game_round WHERE id='{rid}'")
    check("app DB user cannot delete rounds", code != 0 and "denied" in out, out)
    code, out = app("UPDATE audit_log SET details='x'")
    check("app DB user cannot edit audit log", code != 0 and "denied" in out, out)
    code, out = app("DELETE FROM audit_log")
    check("app DB user cannot delete audit log", code != 0 and "denied" in out, out)
    code, out = app("DROP TABLE odds")
    check("app DB user cannot drop tables", code != 0, out)
