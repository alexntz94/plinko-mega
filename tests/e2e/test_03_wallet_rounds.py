"""Test wallets and game sessions (currency, single-use launch tokens), then normal rounds: the wallet moves exactly by
bets and wins, and the Finance page and CSV show the same revenue as the database."""
import re, time
from decimal import Decimal

from conftest import ADMIN, STATE, Player, api, mock_balance, sql


def test_wallet_and_session(check, admin):
    admin.post_form("/admin/mock-pam", {"username": "tester", "balance": "500"}, "/admin/mock-pam")
    check("test wallets are created in the platform currency (USD)", sql("SELECT currency FROM mock_wallet WHERE username='tester'")[1] == "USD")
    _, _, html = admin.get("/admin/mock-pam")
    browser = admin.session
    sent_twice = {"username": "sent-twice", "balance": "5", "_csrf": browser.csrf(html), "formToken": browser.form_token(html)}
    admin.req("POST", ADMIN + "/admin/mock-pam", sent_twice)
    admin.req("POST", ADMIN + "/admin/mock-pam", sent_twice)
    admin.req("POST", ADMIN + "/admin/mock-pam", {"username": "no-form-token", "balance": "5", "_csrf": browser.csrf(html)})
    check("N14.15 a form sent twice with its one-time token is done once; one without a token not at all",
          sql("SELECT COUNT(*) FROM mock_wallet WHERE username='sent-twice'")[1] == "1"
          and sql("SELECT COUNT(*) FROM mock_wallet WHERE username='no-form-token'")[1] == "0")
    admin.post_form("/admin/mock-pam", {"username": "euro-player", "currency": "EUR", "balance": "500"}, "/admin/mock-pam")
    eur_token = sql("SELECT token FROM mock_wallet WHERE username='euro-player'")[1]
    before_eur = sql("SELECT balance FROM mock_wallet WHERE username='euro-player'")[1]
    status, body = api("/api/game/session", {"token": eur_token})
    check("a wallet in another currency is refused", status == 403 and body.get("code") == "CURRENCY_NOT_SUPPORTED", (status, body))
    check("no money moved for the refused wallet", sql("SELECT balance FROM mock_wallet WHERE username='euro-player'")[1] == before_eur)
    pid, token = sql("SELECT player_id, token FROM mock_wallet WHERE username='tester' ORDER BY created_at DESC LIMIT 1")[1].split("\t")
    STATE["tester"] = tester = Player(pid)
    status, body = api("/api/game/session", {"token": "not-a-real-token"})
    check("unknown launch token refused", status == 401 and body.get("code") == "INVALID_TOKEN", body)
    status, session = api("/api/game/session", {"token": token})
    check("session opens with PAM token", status == 200 and session.get("balance") is not None, session)
    tester.sid = sid = session["sessionId"]
    status, body = api("/api/game/session", {"token": token})
    check("launch token is single-use (replay refused)", status == 401, body)
    check("R1.7 the mock wallet itself no longer accepts a used launch token",
          sql(f"SELECT token_issued_at < UTC_TIMESTAMP() - INTERVAL 1 DAY FROM mock_wallet WHERE player_id='{pid}'")[1] == "1")
    # the game's own check, for a wallet that would accept the token again: still refused
    sql(f"UPDATE mock_wallet SET token_issued_at=NULL WHERE player_id='{pid}'")
    status, body = api("/api/game/session", {"token": token})
    check("launch token is single-use in the game too, whatever the wallet says", status == 401, body)
    check("session id is long and random", len(sid) >= 40)
    check("config carries server odds for 7 row counts", len(session["config"]["rows"]) == 7)
    locs = [admin.get(f"/admin/mock-pam/{pid}/open")[1].get("Location", "") for _ in range(2)]
    toks = [l.split("token=")[-1] for l in locs]
    check("'Open game' issues a new launch token every time", all("token=" in l for l in locs) and toks[0] != toks[1], locs)
    check("the newly issued launch token opens the game", api("/api/game/session", {"token": toks[1]})[0] == 200)


def test_rounds_and_finance(check, admin, tester):
    pid, sid = tester.pid, tester.sid
    start = mock_balance(pid)
    total_bet = total_win = Decimal(0)
    for i in range(25):
        time.sleep(0.15)  # a real client plays one round per animation
        status, round_ = api("/api/game/play", {"rows": 8 + i % 7, "risk": i % 3, "bet": 1.5, "balls": 3}, sid)
        if status != 200:
            check("round plays", False, round_)
            break
        total_bet += Decimal(str(round_["totalBet"]))
        total_win += Decimal(str(round_["totalWin"]))
        rows = 8 + i % 7
        if any(s < 0 or s > rows for s in round_["slots"]):
            check("slots within the board", False, round_)
    end = mock_balance(pid)
    check("wallet moved exactly bets - wins over 25 rounds", end == start - total_bet + total_win, f"{start} {end} {total_bet} {total_win}")
    check("API balance equals wallet", Decimal(str(round_["balance"])) == end, f"{round_['balance']} {end}")
    code, out = sql("SELECT COUNT(*) FROM game_round WHERE status <> 'COMPLETED'")
    check("all rounds completed", out == "0", out)
    bets, wins = sql("SELECT SUM(total_bet), SUM(total_win) FROM game_round WHERE status='COMPLETED'")[1].split("\t")
    ggr = Decimal(bets) - Decimal(wins)
    # Finance reads the daily totals (today's rebuilt every minute): read the page and the CSV until they caught up
    page_ggr = csv_ggr = shown = None
    for _ in range(19):
        _, _, html = admin.get("/admin/finance?refresh=1")   # the page keeps its figures a minute; this reads them now
        shown = re.search(r"Platform revenue \(GGR\)</span>\s*<strong>([^<]+)</strong>", html)
        page_ggr = Decimal(shown.group(1).split()[0].replace(",", "")) if shown else None
        _, _, csv_text = admin.get("/admin/finance.csv")
        csv_ggr = sum(Decimal(l.split(",")[6]) for l in csv_text.strip().split("\n")[1:])
        if page_ggr == ggr and csv_ggr == ggr:
            break
        time.sleep(5)
    check("Finance page GGR equals bets - wins in the database", page_ggr == ggr, (shown and shown.group(1), ggr))
    check("Finance CSV adds up to the same GGR", csv_ggr == ggr, (csv_ggr, ggr))
    check("Finance reconciliation finds no wallet mismatches", "differs from the round</span><strong>0</strong>" in html)
