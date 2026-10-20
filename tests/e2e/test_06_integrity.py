"""Round integrity (review 5.2): a round waiting for its credit whose odds version, win or multipliers were changed in
the database is blocked and never paid; normal rounds keep paying; the database guards stay in place."""
from conftest import SMALL, api, app, fault, last_round, mock_balance, pam_rows, retry, sql, without_round_guard


def test_edited_odds_version(check, faulty, blocked_round):
    # 5.2 end to end: the odds version a pending round was played on is changed in the database: never paid
    # (the credit is refused outright, a definite answer: so the round can be settled later, N2)
    b = blocked_round
    check("every new round records its odds version and win limit", b["ref"].isdigit() and b["max_win_saved"] == "1", b["ref"])
    check("5.2 a pending round whose odds version was edited is blocked, never paid",
          b["status"] == "TAMPERED" and b["credits"] == 0 and b["after"] == b["before"] - 1, (b["status"], b["credits"]))
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    rid, st = last_round()
    check("5.2 ... normal rounds still pay", status == 200 and st == "COMPLETED", (status, st))
    code, out = app(f"UPDATE odds_history SET multipliers='1' WHERE id={b['ref']}")
    check("the application's database user can't change the odds history", code != 0, out)


def test_edited_win_or_multiplier(check, faulty, admin):
    # 5.2 end to end: the win or a multiplier of a round waiting for its credit is changed in the database by someone who
    # can get past the database's own guard (root drops the trigger for a moment): the game's own check must still catch it
    rid = None
    for what, change in (("win", "total_win = total_win + 50"), ("multiplier", "multipliers = CONCAT('9', multipliers)")):
        before = mock_balance(faulty.pid)
        fault("credit", "error")
        status, resp = api("/api/game/play", SMALL, faulty.sid)
        rid, st = last_round()
        code, out = without_round_guard(f"UPDATE game_round SET {change} WHERE id='{rid}'")
        edited = code == 0 and st == "CREDIT_PENDING"
        st = retry(rid)
        check(f"5.2 a pending round whose {what} was edited in the database is blocked, never paid, audited",
              edited and st == "TAMPERED" and pam_rows(rid, "CREDIT") == 0 and mock_balance(faulty.pid) == before - 1
              and sql(f"SELECT COUNT(*) FROM audit_log WHERE action='ROUND_TAMPERED' AND details LIKE '{rid}%'")[1] == "1",
              (code, out, st, pam_rows(rid, "CREDIT"), before, mock_balance(faulty.pid)))
        _, _, html = admin.get("/admin")
        check(f"5.2 ... the dashboard shows it among the blocked rounds ({what})", "Blocked rounds to settle" in html and rid[:8] in html, rid)
    code, out = app(f"UPDATE game_round SET total_win = total_win + 1 WHERE id='{rid}'")
    check("5.2 ... and the database guard is back in place afterwards", code != 0 and "outcome cannot be changed" in out, out)
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    rid, st = last_round()
    check("5.2 ... normal rounds still pay after it", status == 200 and st == "COMPLETED", (status, st))
