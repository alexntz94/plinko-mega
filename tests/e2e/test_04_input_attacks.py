"""Attacks on the game API: bad and forged requests, insufficient funds, parallel rounds, the round lock across backend
instances, the rate limit, and forged or replayed calls to the wallet (PAM)."""
import hashlib, hmac, json, threading, time, urllib.error, urllib.request
from decimal import Decimal

from conftest import ADMIN, ENV, api, mock_balance, pam_call, sql

BAD_REQUESTS = {
    "bet below minimum": {"rows": 8, "risk": 0, "bet": 0.01, "balls": 1},
    "bet above maximum": {"rows": 8, "risk": 0, "bet": 1000000, "balls": 1},
    "negative bet": {"rows": 8, "risk": 0, "bet": -5, "balls": 1},
    "3-decimal bet": {"rows": 8, "risk": 0, "bet": 1.005, "balls": 1},
    "zero balls": {"rows": 8, "risk": 0, "bet": 1, "balls": 0},
    "too many balls": {"rows": 8, "risk": 0, "bet": 1, "balls": 101},
    "rows 7": {"rows": 7, "risk": 0, "bet": 1, "balls": 1},
    "rows 15": {"rows": 15, "risk": 0, "bet": 1, "balls": 1},
    "risk 3": {"rows": 8, "risk": 3, "bet": 1, "balls": 1},
    "client-chosen slots": {"rows": 8, "risk": 0, "bet": 1, "balls": 1, "slots": [0]},
    "client-chosen win": {"rows": 8, "risk": 0, "bet": 1, "balls": 1, "totalWin": 999},
    "missing field": {"rows": 8, "risk": 0, "bet": 1},
}


def test_bad_and_forged_requests(check, tester):
    pid, sid = tester.pid, tester.sid
    before = mock_balance(pid)
    for name, body in BAD_REQUESTS.items():
        time.sleep(0.15)
        status, resp = api("/api/game/play", body, sid)
        check(f"rejects {name}", status == 400, f"{status} {resp}")
    status, resp = api("/api/game/play", None, sid, raw=b"{not json")
    check("rejects malformed JSON", status == 400, status)
    status, resp = api("/api/game/resume", {}, "forged-session-id")
    check("resume refuses a forged session", status == 401, status)
    status, resp = api("/api/game/resume", {}, sid)
    check("resume returns the live PAM balance for a real session", status == 200 and Decimal(str(resp["balance"])) == mock_balance(pid), resp)
    status, resp = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, "forged-session-id")
    check("rejects forged session", status == 401, status)
    status, resp = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1})
    check("rejects missing session", status == 401, status)
    check("no money moved by rejected requests", mock_balance(pid) == before)

    # insufficient funds
    status, resp = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 100, "balls": 100}, sid)
    check("insufficient funds refused by PAM", status in (400, 402), resp)
    check("balance unchanged after refused bet", mock_balance(pid) == before)


def test_parallel_rounds_and_round_lock(check, tester):
    pid, sid = tester.pid, tester.sid
    # concurrency: 10 parallel rounds must not double spend or break consistency
    before = mock_balance(pid)
    outcomes = []

    def play():
        outcomes.append(api("/api/game/play", {"rows": 10, "risk": 1, "bet": 1, "balls": 2}, sid))
    threads = [threading.Thread(target=play) for _ in range(10)]
    [t.start() for t in threads]; [t.join() for t in threads]
    ok = [r for s, r in outcomes if s == 200]
    expected = before - sum(Decimal(str(r["totalBet"])) for r in ok) + sum(Decimal(str(r["totalWin"])) for r in ok)
    check("parallel rounds serialised (others get 409 or 429)", all(s in (200, 409, 429) for s, _ in outcomes) and len(ok) >= 1, [s for s, _ in outcomes])
    check("wallet consistent after parallel rounds", mock_balance(pid) == expected, f"{mock_balance(pid)} vs {expected}")

    # the per-player lock lives in the database, so it holds across backend instances (Cloud Run can run several)
    small = {"rows": 8, "risk": 0, "bet": 0.1, "balls": 1}
    sql(f"UPDATE player SET round_lock='other-instance', round_lock_until=UTC_TIMESTAMP(3) + INTERVAL 60 SECOND WHERE external_id='{pid}'")
    time.sleep(1.2)
    status, resp = api("/api/game/play", small, sid)
    check("a round held by another backend instance blocks this one (409)", status == 409, f"{status} {resp}")
    sql(f"UPDATE player SET round_lock_until=UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE external_id='{pid}'")
    status, resp = api("/api/game/play", small, sid)
    check("a lock left by a crashed instance expires", status == 200, f"{status} {resp}")
    held = sql(f"SELECT COALESCE(round_lock, 'none') FROM player WHERE external_id='{pid}'")[1]
    check("the round's lock is released when it ends", held == "none", held)


def test_rate_limit(check, tester):
    # rate limit on the public API
    time.sleep(2)
    codes = [api("/api/game/play", {"rows": 8, "risk": 0, "bet": 0.01, "balls": 1}, tester.sid)[0] for _ in range(40)]
    check("public API rate-limits floods with 429", 429 in codes, codes)
    time.sleep(2)


def test_forged_pam_traffic(check, tester):
    pid = tester.pid
    body = json.dumps({"playerId": pid, "transactionId": "evil-1", "roundId": "x", "amount": 1000, "currency": "USD"})
    r = urllib.request.Request(ADMIN + "/mock-pam/credit", data=body.encode(), method="POST",
                               headers={"Content-Type": "application/json", "X-Api-Key": ENV["PAM_API_KEY"], "X-Signature": "00" * 32})
    try:
        code = urllib.request.urlopen(r, timeout=10).status
    except urllib.error.HTTPError as e:
        code = e.code
    check("PAM rejects unsigned/forged credit", code == 401, code)
    sig = hmac.new(ENV["PAM_SECRET"].encode(), body.encode(), hashlib.sha256).hexdigest()
    before = mock_balance(pid)
    for _ in range(2):
        r = urllib.request.Request(ADMIN + "/mock-pam/credit", data=body.encode(), method="POST",
                                   headers={"Content-Type": "application/json", "X-Api-Key": ENV["PAM_API_KEY"], "X-Signature": sig})
        urllib.request.urlopen(r, timeout=10).read()
    check("PAM idempotency: repeated transaction id credited once", mock_balance(pid) == before + 1000)

    # a bet delayed on the way arrives after the game already rolled it back (seen under load): it must not be taken
    before = mock_balance(pid)
    pam_call("rollback", {"playerId": pid, "transactionId": "late-rb", "originalTransactionId": "late-debit", "roundId": "late-round",
                          "amount": 5, "currency": "USD"})
    code = pam_call("debit", {"playerId": pid, "transactionId": "late-debit", "roundId": "late-round", "amount": 5, "currency": "USD"})
    check("PAM refuses a bet that arrives after its own rollback", code == 409 and mock_balance(pid) == before, (code, before, mock_balance(pid)))
