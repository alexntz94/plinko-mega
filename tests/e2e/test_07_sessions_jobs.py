"""What a game session may still do (5.3: blocked player, expired session, one launch link opened 5 times at once), and
the background jobs: daily totals (3.2), used launch tokens kept 30 days (3.7), retry backoff (3.8)."""
import threading, time

from conftest import SMALL, api, app, fault, fresh_token, last_round, retry, round_status, sql


def test_session_limits(check, faulty, admin):
    # 5.3 #5-#7: what a session may still do
    fpid = faulty.pid
    fsid = faulty.new_session()
    internal = sql(f"SELECT id FROM player WHERE external_id='{fpid}'")[1]
    admin.post_form(f"/admin/players/{internal}/block", {"blocked": "true", "reason": "e2e: blocked mid-session"}, "/admin/players")
    debits = int(sql(f"SELECT COUNT(*) FROM mock_transaction WHERE player_id='{fpid}' AND type='DEBIT'")[1])
    status, resp = api("/api/game/play", SMALL, fsid)
    check("5.3 #5 a player blocked mid-session can't play the next round; no bet is taken", status == 403
          and int(sql(f"SELECT COUNT(*) FROM mock_transaction WHERE player_id='{fpid}' AND type='DEBIT'")[1]) == debits, (status, resp))
    admin.post_form(f"/admin/players/{internal}/block", {"blocked": "false", "reason": "e2e: done"}, "/admin/players")
    status, _ = api("/api/game/play", SMALL, fsid)
    check("5.3 #5 ... and can play again once unblocked", status == 200, status)
    sql(f"UPDATE game_session SET expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id='{fsid}'")
    status, resp = api("/api/game/play", SMALL, fsid)
    check("5.3 #6 an expired session is refused", status == 401 and resp.get("code") == "SESSION_EXPIRED", (status, resp))
    same = fresh_token(fpid)
    opened = []
    racers = [threading.Thread(target=lambda: opened.append(api("/api/game/session", {"token": same})[0])) for _ in range(5)]
    [t.start() for t in racers]; [t.join() for t in racers]
    check("5.3 #7 the same launch link sent 5 times at once opens exactly one session", opened.count(200) == 1, opened)


def test_daily_totals(check, faulty):
    # review 3.2: the dashboard's daily totals are a summary of the rounds and must always equal them (rebuilt every minute)
    raw = sql("SELECT COUNT(*), COALESCE(SUM(total_bet),0), COALESCE(SUM(total_win),0) FROM game_round "
              "WHERE status IN ('COMPLETED','CREDIT_PENDING','DEBITED')")[1]
    summed = None
    for _ in range(16):
        summed = sql("SELECT COALESCE(SUM(rounds),0), COALESCE(SUM(total_bet),0), COALESCE(SUM(total_win),0) FROM round_daily_totals")[1]
        if summed == raw:
            break
        time.sleep(5)
    check("3.2 the daily totals equal the rounds they summarise", summed == raw, (summed, raw))
    players = sql("SELECT COUNT(DISTINCT player_id) FROM game_round WHERE status IN ('COMPLETED','CREDIT_PENDING','DEBITED')")[1]
    check("3.2 ... and so do the players", sql("SELECT COUNT(DISTINCT player_id) FROM round_daily_players")[1] == players)


def test_used_launch_tokens_kept(check, faulty):
    # review 3.7: used launch tokens keep links single-use: only ones older than 30 days may be deleted, whoever asks
    code, out = app("DELETE FROM used_launch_token ORDER BY used_at DESC LIMIT 1")
    check("3.7 a recent used launch token can't be deleted, even with the app's database login", code != 0 and "30 days" in out, out)
    sql("INSERT INTO used_launch_token (token_hash, used_at) VALUES (SHA2('e2e-old-token', 256), UTC_TIMESTAMP(3) - INTERVAL 40 DAY)")
    code, out = app("DELETE FROM used_launch_token WHERE token_hash = SHA2('e2e-old-token', 256)")
    check("3.7 ... one used more than 30 days ago can", code == 0, out)


def test_retry_backoff(check, faulty):
    # review 3.8: a credit the wallet refuses again waits before its next try
    fsid = faulty.new_session()
    fault("credit", "error", times=2)
    api("/api/game/play", SMALL, fsid)
    rid, st = last_round()
    retry(rid)
    wait = sql(f"SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), next_retry_at) FROM wallet_transaction WHERE round_id='{rid}' AND type='CREDIT'")[1]
    check("3.8 a refused retry is scheduled for later (backoff)", wait.lstrip('-').isdigit() and 20 <= int(wait) <= 70, wait)
    retry(rid)
    check("3.8 ... and the next try pays it once the wallet works", round_status(rid) == "COMPLETED", round_status(rid))
