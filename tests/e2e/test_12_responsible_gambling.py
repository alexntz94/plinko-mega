"""The market's responsible gambling options (review 7.4): in the game's config, enforced by the server where they must
be; protecting players less needs a second admin (N14.9)."""
import time

from conftest import SMALL, api, fault, fresh_token, sql

FEATURES = {"holdToDrop": "true", "sessionClock": "true", "realityCheckMinutes": "30", "quietSmallWins": "true", "minRoundSeconds": "3"}


def test_responsible_gambling_options(check, admin, second_admin, faulty):
    admin.post_form("/admin/settings/features", FEATURES, "/admin/settings")
    time.sleep(65)   # every instance picks up a settings change within a minute
    status, fs = api("/api/game/session", {"token": fresh_token(faulty.pid)})
    cfg = fs.get("config", {})
    check("7.4 the options reach the game", cfg.get("sessionClock") is True and cfg.get("realityCheckMinutes") == 30
          and cfg.get("quietSmallWins") is True and float(cfg.get("minRoundSeconds", 0)) == 3, {k: cfg.get(k) for k in FEATURES})
    time.sleep(1.3)
    st_a, _ = api("/api/game/play", SMALL, fs.get("sessionId"))
    time.sleep(1.3)
    st_b, rb = api("/api/game/play", SMALL, fs.get("sessionId"))
    check("7.4 the minimum time per round is enforced by the server", st_a == 200 and st_b == 429 and rb.get("code") == "TOO_FAST", (st_a, st_b, rb))
    time.sleep(3.2)
    fault("debit", "limit")
    st_c, rc = api("/api/game/play", SMALL, fs.get("sessionId"))
    check("7.4 the casino's limit refuses the bet and is shown as such, no money moved", st_c == 403 and rc.get("code") == "LIMIT_REACHED", (st_c, rc))
    admin.post_form("/admin/settings/features", {"holdToDrop": "true", "realityCheckMinutes": "0", "minRoundSeconds": "0"}, "/admin/settings")
    loosen = sql("SELECT MAX(id) FROM change_request WHERE type='FEATURES' AND status='PENDING'")[1]
    check("N14.9 protecting players less is only proposed: nothing changes until a second admin approves",
          loosen.isdigit() and sql("SELECT value FROM setting WHERE name='game.minRoundSeconds'")[1] == "3", loosen)
    admin.post_form(f"/admin/changes/{loosen}/approve", {}, "/admin/changes")
    check("N14.9 ... the proposer can't approve it", sql(f"SELECT status FROM change_request WHERE id={loosen}")[1] == "PENDING")
    second_admin.post_form(f"/admin/changes/{loosen}/approve", {}, "/admin/changes")
    time.sleep(65)
    check("7.4 switched back off by an admin, approved by a second", sql("SELECT value FROM setting WHERE name='game.minRoundSeconds'")[1] == "0")
