"""A play request sent twice plays one round (review 2.3), and the player's own history, a page at a time, with each
round's balls for the replay (review 7.5)."""
import time, uuid
from decimal import Decimal

from conftest import api, mock_balance, sql


def test_repeated_request_and_history(check, tester):
    pid, sid = tester.pid, tester.sid
    # at least two pages of history (an earlier area usually played many more)
    played = int(sql(f"SELECT COUNT(*) FROM game_round r JOIN player p ON p.id = r.player_id WHERE p.external_id='{pid}' "
                     "AND r.status='COMPLETED'")[1])
    for _ in range(max(0, 10 - played)):
        time.sleep(0.3)
        api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, sid)

    # 2.3: a request sent twice (the answer was lost) plays one round; the same id for another bet is refused
    time.sleep(1.2)
    req_id = str(uuid.uuid4())
    wallet_before = mock_balance(pid)
    st1, r1 = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1, "requestId": req_id}, sid)
    time.sleep(1.2)
    st2, r2 = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1, "requestId": req_id}, sid)
    check("2.3 a repeated request returns the same round, with one bet taken", st1 == 200 and st2 == 200 and r1.get("roundId") == r2.get("roundId")
          and sql(f"SELECT COUNT(*) FROM game_round WHERE request_id='{req_id}'")[1] == "1"
          and mock_balance(pid) == wallet_before - Decimal("1") + Decimal(str(r1.get("totalWin"))), (st1, st2, r1.get("roundId"), r2.get("roundId")))
    time.sleep(1.2)
    st3, r3 = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 2, "balls": 1, "requestId": req_id}, sid)
    check("2.3 the same request id for a different bet is refused", st3 == 409 and r3.get("code") == "REQUEST_MISMATCH", (st3, r3))

    # 7.5: the player's own history, a page at a time, with each round's balls for the replay
    st, page1 = api("/api/game/history", {"limit": 5}, sid)
    # every row has its round id (N15.4); a round whose stored outcome fails its check shows under review, without a
    # replay (N14.12), e.g. one forged in the database by an earlier area
    ids = [e.get("roundId") for e in page1.get("rounds", []) if e.get("roundId")]
    owners = sql("SELECT COUNT(DISTINCT p.external_id) FROM game_round r JOIN player p ON p.id = r.player_id WHERE r.id IN ('"
                 + "','".join(ids) + "')")[1] if ids else "0"
    check("7.5 history: the player's own rounds, newest first, five a page", st == 200 and len(ids) == 5 and owners == "1"
          and page1["rounds"][0]["round"]["roundId"] == r1.get("roundId") and page1.get("next"), (st, len(ids), owners))
    st, page2 = api("/api/game/history", {"cursor": page1.get("next"), "limit": 5}, sid)
    ids2 = [e.get("roundId") for e in page2.get("rounds", []) if e.get("roundId")]
    check("7.5 history: the next page carries on without repeats", st == 200 and ids2 and not set(ids) & set(ids2), (st, ids2[:2]))
    stored = sql(f"SELECT slots FROM game_round WHERE id='{r1.get('roundId')}'")[1]
    check("7.5 history: the replay has exactly the stored balls, and no balance", page1["rounds"][0]["round"]["slots"] == r1.get("slots")
          and page1["rounds"][0]["round"].get("balance") is None and stored.split(",")[0] == str(r1["slots"][0]), stored)
    st, bad = api("/api/game/history", {"cursor": "1:' OR 1=1 --"}, sid)
    check("7.5 history: a made-up cursor is refused", st == 400, (st, bad))
    st, _ = api("/api/game/history", {}, "not-a-session")
    check("7.5 history needs a game session", st in (401, 403), st)
