"""A few rounds played and paid through the public API, and the integrity checks still clean (scripts/compat-check.sh).
Usage: python3 tests/compat_smoke.py <test.env> <label> [client_requests.json]
  — uses only what every release has: session, play, the DB. With a requests file (the PREVIOUS release's
    tests/client_requests.json, review N9) it first replays every request that release's game client sends, and each
    must be accepted: players keep the old client in their browsers while the new backend serves them."""
import json, subprocess, sys, time, urllib.error, urllib.request, uuid

ENV = dict(l.split("=", 1) for l in open(sys.argv[1]).read().splitlines() if "=" in l and not l.startswith("#"))
LABEL = sys.argv[2]
REQUESTS = sys.argv[3] if len(sys.argv) > 3 else None
GAME = "http://localhost:8080"


def sql(query):
    p = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], "plinkotest-db-1", "mysql", "-uroot", "-N", "-B",
                        "plinko", "-e", query], capture_output=True, text=True)
    return p.stdout.strip()


def api(path, body, session=None):
    headers = {"Content-Type": "application/json", **({"Authorization": "Bearer " + session} if session else {})}
    request = urllib.request.Request(GAME + path, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def new_token():
    token = "compat" + uuid.uuid4().hex[:20]
    sql(f"INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at) "
        f"VALUES ('compat-{token}', 'compat', 'USD', 100, '{token}', UTC_TIMESTAMP(3))")
    return token


def fill(value, cursor):
    """The recorded body with its placeholders filled: a fresh launch token, a new request id, the history cursor."""
    if isinstance(value, dict):
        return {k: fill(v, cursor) for k, v in value.items()}
    if value == "$TOKEN":
        return new_token()
    if value == "$UUID":
        return str(uuid.uuid4())
    if value == "$NEXT":
        return cursor
    if value == "$NOW":
        return int(time.time() * 1000)
    return value


replay_ok = True
if REQUESTS:
    replay_session, cursor = None, None
    for req in json.load(open(REQUESTS))["requests"]:
        if "$NEXT" in json.dumps(req["body"]) and not cursor:
            continue    # no second page of history to ask for
        if req["path"].endswith("/play"):
            time.sleep(1.3)
        status, answer = api(req["path"], fill(req["body"], cursor), replay_session)
        replay_session = answer.get("sessionId", replay_session)
        cursor = answer.get("next", cursor)
        if status in (403, 404) and req.get("goesOnWithout"):
            # a report the game doesn't wait for (review 7.4): a backend without its endpoint doesn't stop play. A release
            # from before it answers 404 through its own nginx, or 403 through this one (its backend denies any path it
            # doesn't know)
            print(f"NOTE {LABEL}: {req['path']} is not served here ({status}); the game goes on without it")
        elif status != 200:
            replay_ok = False
            print(f"FAIL {LABEL}: the previous client's {req['path']} with {sorted(req['body'])} answered {status}")
    print(("PASS " if replay_ok else "FAIL ") + f"{LABEL}: the previous release's game client requests accepted")

status, session = api("/api/game/session", {"token": new_token()})
ok = status == 200
played = []
for _ in range(3):
    time.sleep(1.3)
    status, r = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 2}, session.get("sessionId"))
    ok = ok and status == 200
    played.append(r.get("roundId"))
statuses = sql("SELECT status FROM game_round WHERE id IN ('" + "','".join(filter(None, played)) + "')").split()
blocked = sql("SELECT COUNT(*) FROM game_round WHERE status = 'TAMPERED'")
ok = ok and statuses == ["COMPLETED"] * 3 and blocked == "0" and replay_ok
print(("PASS " if ok else "FAIL ") + f"{LABEL}: rounds played and paid, none blocked ({statuses}, blocked {blocked})")
sys.exit(0 if ok else 1)
