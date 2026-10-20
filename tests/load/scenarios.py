"""Load scenarios beyond the fixed-load run (review 5.6 #1, #3, #4, #5 and N4 #3), on a running test stack with the mock
wallet (scripts/test-env.sh; docker compose -p <project> ... up). Python standard library only; k6 runs in Docker.

    python3 tests/load/scenarios.py <test.env> <scenario> [<scenario> ...]

  ramp        more and more rounds a second (k6 ramping-arrival-rate, RAMP_RATES held RAMP_STEP each) until errors or
              play's 99th percentile break; reports each step and the highest rate held (rounds/s), with the database
              (SHOW GLOBAL STATUS deltas: commits, row lock waits and time, threads running; the database container's
              CPU) and the backend's connection pool (hikaricp_* from /actuator/prometheus on the admin port) per step
  outage      the wallet refuses every credit for OUTAGE_SECONDS (default 120; 600 is the review's 10 minutes) while
              players keep playing, then answers again; every round must be settled (COMPLETED or CANCELLED) within
              SETTLE_MINUTES of the recovery, and no round paid twice (checked in the mock wallet's own records)
  db-restart  the MySQL container restarted while players play; errors only while it is down (plus RESTART_GRACE
              seconds), except 409 ROUND_IN_PROGRESS for a player whose round the restart cut off, until its round lock
              runs out (RESTART_LOCKOUT_SECONDS, default 420; reported); every round settled afterwards, nothing paid twice
  history     a large history seeded with SQL (HISTORY_ROUNDS rounds over HISTORY_DAYS days, with their wallet
              transactions and daily totals), a run on it, then TODAY_ROUNDS more rounds played "today" so the
              minute's daily-totals rebuild reads a big day (N4), and the same run again: play's percentiles with and
              without the big rebuild
  after-run   the checks after scripts/loadtest.sh's fixed load (it runs this itself): every round settled, nothing
              paid twice, the wallets equal the game's totals, the daily totals equal the rounds (R2)

Settings (environment): PROJECT (compose project, default plinkotest), ADMIN_HOST_URL (the admin port from this machine,
default http://127.0.0.1:8081), OUT (default ./loadtest-out), K6_IMAGE, GAME_URL / ADMIN_URL (as k6 sees them, default
the backend on the stack's network), PLAY_P99_MS (default 1500), and the per-scenario ones above (defaults below).
Each scenario writes <OUT>/<scenario>.json; the run exits non-zero when one fails. Never point it at a real wallet.
"""
import gzip, hashlib, hmac, json, os, re, statistics, subprocess, sys, threading, time, urllib.error, urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ENV_FILE = sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__)
ENV = dict(l.split("=", 1) for l in open(ENV_FILE).read().splitlines() if "=" in l and not l.startswith("#"))
PROJECT = os.environ.get("PROJECT", "plinkotest")
DB = f"{PROJECT}-db-1"
ADMIN_HOST = os.environ.get("ADMIN_HOST_URL", "http://127.0.0.1:8081")
OUT = os.path.abspath(os.environ.get("OUT", "loadtest-out"))
# grafana/k6 v1.3.0, pinned by digest (as scripts/loadtest.sh)
K6_IMAGE = os.environ.get("K6_IMAGE", "grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d")
PLAY_P99_MS = float(os.environ.get("PLAY_P99_MS", "1500"))
START_BALANCE = 1000000    # prepare.py's wallets


def setting(name, default):
    return type(default)(os.environ.get(name, default))


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------------------------------------------------- database

def sql(query, check=True):
    """Rows of a query run as root inside the database container (tab-separated, no header)."""
    p = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], DB, "mysql", "-uroot", "-N", "-B", "plinko"],
                       input=query, capture_output=True, text=True)
    if p.returncode != 0:
        if check:
            raise RuntimeError("database: " + p.stderr.strip()[-500:])
        return None
    return [line.split("\t") for line in p.stdout.splitlines()]


def one(query):
    rows = sql(query)
    return rows[0][0] if rows else None


STATUS_VARS = ("Com_commit", "Com_insert", "Com_update", "Com_select", "Innodb_row_lock_waits", "Innodb_row_lock_time",
               "Innodb_rows_read", "Questions", "Threads_running", "Threads_connected")
POOL = ("hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_idle", "hikaricp_connections_max",
        "hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_count", "hikaricp_connections_timeout_total",
        "plinko_rounds_pending")


class Sampler(threading.Thread):
    """Every few seconds: the database's counters, its container's CPU, and the backend's pool from its metrics."""

    def __init__(self, every=5):
        super().__init__(daemon=True)
        self.every = every
        self.samples = []
        self.done = threading.Event()

    def sample(self):
        s = {"t": time.time()}
        rows = sql("SHOW GLOBAL STATUS WHERE Variable_name IN (%s)" % ",".join(f"'{v}'" for v in STATUS_VARS), check=False)
        if rows:
            s["db"] = {k: float(v) for k, v in rows}
        p = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", DB], capture_output=True, text=True)
        if p.returncode == 0 and p.stdout.strip().endswith("%"):
            s["dbCpuPercent"] = float(p.stdout.strip().rstrip("%"))
        try:
            text = urllib.request.urlopen(ADMIN_HOST + "/actuator/prometheus", timeout=5).read().decode()
            pool = {}
            for line in text.splitlines():
                name = line.split("{", 1)[0].split(" ", 1)[0]
                if name in POOL:
                    pool[name] = pool.get(name, 0.0) + float(line.rsplit(" ", 1)[1])
            s["pool"] = pool
        except (OSError, ValueError):
            pass
        return s

    def run(self):
        while not self.done.is_set():
            self.samples.append(self.sample())
            self.done.wait(self.every)

    def stop(self):
        self.done.set()
        self.join(30)
        self.samples.append(self.sample())

    def window(self, t0, t1):
        """The database and the pool between two times: rates of the counters, peaks of the gauges."""
        inside = [s for s in self.samples if t0 <= s["t"] <= t1]
        db = [s for s in self.samples if "db" in s]
        first = next((s for s in db if s["t"] >= t0), None)
        last = next((s for s in reversed(db) if s["t"] <= t1), None)
        out = {}
        if first and last and last["t"] > first["t"]:
            span = last["t"] - first["t"]
            d = {k: last["db"].get(k, 0) - first["db"].get(k, 0) for k in STATUS_VARS}
            out["db"] = {"commitsPerSecond": round(d["Com_commit"] / span, 1), "insertsPerSecond": round(d["Com_insert"] / span, 1),
                         "updatesPerSecond": round(d["Com_update"] / span, 1), "selectsPerSecond": round(d["Com_select"] / span, 1),
                         "queriesPerSecond": round(d["Questions"] / span, 1), "rowsReadPerSecond": round(d["Innodb_rows_read"] / span),
                         "rowLockWaits": int(d["Innodb_row_lock_waits"]), "rowLockTimeMs": int(d["Innodb_row_lock_time"]),
                         "threadsRunningMax": max(s["db"]["Threads_running"] for s in inside if "db" in s) if any("db" in s for s in inside) else None,
                         "threadsConnectedMax": max(s["db"]["Threads_connected"] for s in inside if "db" in s) if any("db" in s for s in inside) else None}
        cpu = [s["dbCpuPercent"] for s in inside if "dbCpuPercent" in s]
        if cpu:
            out["dbCpuPercent"] = {"avg": round(statistics.mean(cpu), 1), "max": round(max(cpu), 1)}
        pools = [s for s in self.samples if "pool" in s]
        pfirst = next((s for s in pools if s["t"] >= t0), None)
        plast = next((s for s in reversed(pools) if s["t"] <= t1), None)
        inside_pool = [s["pool"] for s in inside if "pool" in s]
        if inside_pool:
            out["pool"] = {"activeMax": max(p.get("hikaricp_connections_active", 0) for p in inside_pool),
                           "pendingMax": max(p.get("hikaricp_connections_pending", 0) for p in inside_pool),
                           "size": max(p.get("hikaricp_connections_max", 0) for p in inside_pool),
                           "roundsPendingMax": max(p.get("plinko_rounds_pending", 0) for p in inside_pool)}
            if pfirst and plast and plast is not pfirst:
                n = plast["pool"].get("hikaricp_connections_acquire_seconds_count", 0) - pfirst["pool"].get("hikaricp_connections_acquire_seconds_count", 0)
                secs = plast["pool"].get("hikaricp_connections_acquire_seconds_sum", 0) - pfirst["pool"].get("hikaricp_connections_acquire_seconds_sum", 0)
                out["pool"]["acquireAvgMs"] = round(secs * 1000 / n, 2) if n > 0 else 0
                out["pool"]["timeouts"] = int(plast["pool"].get("hikaricp_connections_timeout_total", 0) - pfirst["pool"].get("hikaricp_connections_timeout_total", 0))
        return out


# ------------------------------------------------------------------------------------------------------- k6 and wallets

def prepare(count, admin=False):
    """count fresh mock wallets (tests/load/prepare.py); returns the run's player id prefix."""
    env = dict(os.environ, PREPARE_ADMIN="1" if admin else "0", ADMIN_HOST_URL=ADMIN_HOST)
    subprocess.run([sys.executable, os.path.join(HERE, "prepare.py"), ENV_FILE, str(count), OUT, PROJECT], check=True, env=env)
    data = json.load(open(os.path.join(OUT, "wallets.json")))
    return data["wallets"][0]["playerId"].rsplit("-", 1)[0] + "-", [w["playerId"] for w in data["wallets"]]


def k6(name, settings, points=False):
    """Starts k6 (Docker) with these settings; returns the process. Its summary goes to <OUT>/<name>-k6.json and, with
    points, every measurement to <OUT>/<name>-points.json.gz (read by points())."""
    # the wallets file of this run, kept under the run's name (the next prepare.py overwrites wallets.json)
    os.replace(os.path.join(OUT, "wallets.json"), os.path.join(OUT, f"{name}-wallets.json"))
    env = {"WALLETS": f"/out/{name}-wallets.json", "OUT": "/out", "SUMMARY": f"{name}-k6.json",
           "GAME_URL": os.environ.get("GAME_URL", "http://backend:8080"), "ADMIN_URL": os.environ.get("ADMIN_URL", "http://backend:8081"),
           "PLAY_P99_MS": str(int(PLAY_P99_MS)), "PAM_API_KEY": ENV["PAM_API_KEY"], "PAM_SECRET": ENV["PAM_SECRET"]}
    env.update({k: str(v) for k, v in settings.items()})
    cmd = ["docker", "run", "--rm", "--network", f"{PROJECT}_edge", "--user", f"{os.getuid()}:{os.getgid()}",
           "-v", f"{HERE}:/scripts:ro", "-v", f"{OUT}:/out"]
    for k in env:
        cmd += ["-e", k]    # values by name from this process's environment, never on the command line
    cmd += [K6_IMAGE, "run", "--quiet", "--no-color"]
    if points:
        cmd += ["--out", f"json=/out/{name}-points.json.gz"]
    cmd += ["/scripts/play.js"]
    return subprocess.Popen(cmd, env=dict(os.environ, **env))


def finish(proc, name):
    code = proc.wait()
    path = os.path.join(OUT, f"{name}-k6.json")
    summary = json.load(open(path)) if os.path.exists(path) else {}
    summary["k6ExitCode"] = code
    return summary


def stamp(text):
    # k6 writes nanoseconds and an offset: 2026-10-19T10:00:00.123456789+02:00
    text = re.sub(r"(\.\d{6})\d+", r"\1", text).replace("Z", "+00:00")
    return datetime.fromisoformat(text).timestamp()


def points(name, metrics):
    """(time, metric, value) of the given metrics, from k6's json output."""
    out = []
    path = os.path.join(OUT, f"{name}-points.json.gz")
    with gzip.open(path, "rt") as f:
        for line in f:
            if '"Point"' not in line:
                continue
            p = json.loads(line)
            if p["metric"] in metrics:
                out.append((stamp(p["data"]["time"]), p["metric"], p["data"]["value"]))
    out.sort()
    return out


def pct(values, q):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(q * len(values)))], 1)


def sign(body):
    return hmac.new(ENV["PAM_SECRET"].encode(), body.encode(), hashlib.sha256).hexdigest()


def fault(player, endpoint, kind, times=1):
    body = json.dumps({"playerId": player, "endpoint": endpoint, "fault": kind, "times": times})
    req = urllib.request.Request(ADMIN_HOST + "/mock-pam/_fault", data=body.encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Api-Key": ENV["PAM_API_KEY"], "X-Signature": sign(body)})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status == 200


def wait_healthy(container, timeout=300):
    end = time.time() + timeout
    while time.time() < end:
        p = subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", container], capture_output=True, text=True)
        if p.stdout.strip() == "healthy":
            return True
        time.sleep(1)
    return False


# ------------------------------------------------------------------------------------------------------- money checks

SETTLED = ("COMPLETED", "CANCELLED")


def statuses(prefix):
    rows = sql(f"""SELECT r.status, COUNT(*) FROM game_round r JOIN player p ON p.id = r.player_id
                   WHERE p.external_id LIKE '{prefix}%' GROUP BY r.status""")
    return {s: int(n) for s, n in rows}


def wait_settled(prefix, minutes, since):
    """Waits until every round of the run is COMPLETED or CANCELLED; the seconds it took after `since`, or None."""
    end = since + minutes * 60
    while True:
        open_rounds = {s: n for s, n in statuses(prefix).items() if s not in SETTLED}
        if not open_rounds:
            return round(time.time() - since)
        if time.time() > end:
            log("still open:", open_rounds)
            return None
        time.sleep(10)


def money(prefix):
    """No round paid twice, every wallet's balance equal to the game's own totals, every credit equal to its round's win.
    Read from the mock wallet's own records (mock_transaction), not from what the game believes."""
    like = f"'{prefix}%'"
    checks = {
        # a round credited, debited or refunded under two different transaction ids
        "roundsPaidTwice": f"SELECT COUNT(*) FROM (SELECT round_id FROM mock_transaction WHERE player_id LIKE {like} AND type = 'CREDIT' "
                           f"GROUP BY round_id HAVING COUNT(*) > 1) x",
        "roundsDebitedTwice": f"SELECT COUNT(*) FROM (SELECT round_id FROM mock_transaction WHERE player_id LIKE {like} AND type = 'DEBIT' "
                              f"GROUP BY round_id HAVING COUNT(*) > 1) x",
        "roundsRefundedTwice": f"SELECT COUNT(*) FROM (SELECT round_id FROM mock_transaction WHERE player_id LIKE {like} AND type = 'ROLLBACK' "
                               f"AND amount > 0 GROUP BY round_id HAVING COUNT(*) > 1) x",
        # a round both paid a win and refunded its bet
        "roundsPaidAndRefunded": f"SELECT COUNT(DISTINCT c.round_id) FROM mock_transaction c JOIN mock_transaction r ON r.round_id = c.round_id "
                                 f"AND r.type = 'ROLLBACK' AND r.amount > 0 WHERE c.player_id LIKE {like} AND c.type = 'CREDIT' AND c.amount > 0",
        # a finished round whose wallet credits differ from its win, or a cancelled one whose bet stayed taken
        "completedRoundsCreditDiffers": f"""SELECT COUNT(*) FROM game_round g JOIN player p ON p.id = g.player_id
            LEFT JOIN (SELECT round_id, SUM(amount) paid FROM mock_transaction WHERE player_id LIKE {like} AND type = 'CREDIT' GROUP BY round_id) c
              ON c.round_id = g.id
            WHERE p.external_id LIKE {like} AND g.status = 'COMPLETED' AND COALESCE(c.paid, 0) <> COALESCE(g.total_win, 0)""",
        "cancelledRoundsBetKept": f"""SELECT COUNT(*) FROM game_round g JOIN player p ON p.id = g.player_id
            LEFT JOIN (SELECT round_id, SUM(CASE type WHEN 'DEBIT' THEN amount WHEN 'ROLLBACK' THEN -amount ELSE 0 END) kept
                       FROM mock_transaction WHERE player_id LIKE {like} GROUP BY round_id) t ON t.round_id = g.id
            WHERE p.external_id LIKE {like} AND g.status = 'CANCELLED' AND COALESCE(t.kept, 0) <> 0""",
        # every wallet: its balance now against its start and the game's finished rounds (win - bet)
        "walletsDifferFromGame": f"""SELECT COUNT(*) FROM mock_wallet w
            LEFT JOIN (SELECT p.external_id, SUM(COALESCE(g.total_win, 0) - g.total_bet) net FROM game_round g JOIN player p ON p.id = g.player_id
                       WHERE p.external_id LIKE {like} AND g.status = 'COMPLETED' GROUP BY p.external_id) n ON n.external_id = w.player_id
            WHERE w.player_id LIKE {like} AND w.balance <> {START_BALANCE} + COALESCE(n.net, 0)""",
    }
    result = {name: int(one(q)) for name, q in checks.items()}
    result.update(daily_totals())
    result["ok"] = all(v == 0 for v in result.values())
    return result


def daily_totals():
    """Review R2: once every round is settled, the daily totals (with the increments the minute job hasn't added yet) equal a
    direct count of the rounds they add up, every day and every player: no round counted twice or left out. 1 = differs."""
    counted = "status IN ('COMPLETED', 'CREDIT_PENDING')"
    figures = "CONCAT(COALESCE(SUM(rounds), 0), '/', COALESCE(SUM(total_bet), 0), '/', COALESCE(SUM(total_win), 0))"
    rounds = one(f"SELECT CONCAT(COUNT(*), '/', COALESCE(SUM(total_bet), 0), '/', COALESCE(SUM(total_win), 0)) FROM game_round WHERE {counted}")
    kept = one(f"""SELECT {figures} FROM (SELECT rounds, total_bet, total_win FROM round_daily_totals
                   UNION ALL SELECT rounds, total_bet, total_win FROM round_daily_delta) x""")
    players = one(f"""SELECT {figures} FROM (SELECT rounds, total_bet, total_win FROM round_daily_players
                      UNION ALL SELECT rounds, total_bet, total_win FROM round_daily_delta) x""")
    if rounds != kept or rounds != players:
        log(f"daily totals differ from the rounds: rounds {rounds}, daily totals {kept}, per-player totals {players}")
    return {"dailyTotalsDifferFromRounds": int(rounds != kept), "dailyPlayerTotalsDifferFromRounds": int(rounds != players)}


def write(name, report):
    with open(os.path.join(OUT, f"{name}.json"), "w") as f:
        json.dump(report, f, indent=2)
        f.write("\n")
    log(f"{name}: {'PASSED' if report['passed'] else 'FAILED'} ({os.path.join(OUT, name + '.json')})")
    return report["passed"]


def duration_seconds(text):
    m = re.fullmatch(r"(\d+)(s|m)", text)
    return int(m.group(1)) * (60 if m.group(2) == "m" else 1)


# ------------------------------------------------------------------------------------------------------------ scenarios

def ramp():
    rates = [int(r) for r in setting("RAMP_RATES", "5,10,20,40,60,80,100,130,160,200,250,300").split(",")]
    step = setting("RAMP_STEP", "45s")
    max_vus = setting("RAMP_MAX_VUS", 300)
    max_errors = setting("RAMP_MAX_ERRORS", 0.01)
    held_share = setting("RAMP_HELD_SHARE", 0.95)
    _, _ = prepare(max_vus)
    sampler = Sampler()
    sampler.start()
    log(f"ramp: {rates} rounds/s, {step} each, up to {max_vus} players")
    summary = finish(k6("ramp", {"MODE": "ramp", "RAMP_RATES": ",".join(map(str, rates)), "RAMP_STEP": step, "RAMP_MAX_VUS": max_vus}, points=True), "ramp")
    sampler.stop()
    pts = points("ramp", {"play_duration", "player_errors", "dropped_iterations", "vus_max", "vus", "rounds"})
    t0 = summary["startedAt"] / 1000 if summary.get("startedAt") else (pts[0][0] if pts else time.time())
    hold = duration_seconds(step)
    steps = []
    for i, rate in enumerate(rates):
        # the step's own window: after the 5 s to reach it and 5 s to settle, to its end
        a, b = t0 + i * (5 + hold) + 10, t0 + (i + 1) * (5 + hold)
        play = [v for t, m, v in pts if m == "play_duration" and a <= t < b]
        errors = [v for t, m, v in pts if m == "player_errors" and a <= t < b]
        if not play and not errors:
            break    # the run stopped before this step
        done = sum(v for t, m, v in pts if m == "rounds" and a <= t < b)
        dropped = sum(v for t, m, v in pts if m == "dropped_iterations" and a <= t < b)
        s = {"targetRoundsPerSecond": rate, "roundsPerSecond": round(done / (b - a), 1), "playMs": {"med": pct(play, 0.5), "p95": pct(play, 0.95),
             "p99": pct(play, 0.99), "max": pct(play, 1.0)}, "errorRate": round(sum(errors) / len(errors), 4) if errors else None,
             "droppedArrivals": int(dropped), "players": int(max([v for t, m, v in pts if m == "vus" and a <= t < b] or [0]))}
        s.update(sampler.window(a, b))
        s["held"] = (s["roundsPerSecond"] >= held_share * rate and s["playMs"]["p99"] is not None and s["playMs"]["p99"] <= PLAY_P99_MS
                     and (s["errorRate"] or 0) <= max_errors)
        steps.append(s)
        log(f"  {rate:>4} rounds/s asked: {s['roundsPerSecond']} done, play p99 {s['playMs']['p99']} ms, errors {s['errorRate']}, "
            f"commits/s {s.get('db', {}).get('commitsPerSecond')}, lock waits {s.get('db', {}).get('rowLockWaits')}, "
            f"pool pending max {s.get('pool', {}).get('pendingMax')}: {'held' if s['held'] else 'NOT held'}")
    held = [s for s in steps if s["held"]]
    # the highest step held, and the first step above it that wasn't (where it broke); a low step missed on the way up
    # (a cold start: the first sessions, the JIT) is listed, not taken as the limit
    top = max(range(len(steps)), key=lambda i: steps[i]["roundsPerSecond"] if steps[i]["held"] else -1, default=None)
    sustained = steps[top]["roundsPerSecond"] if top is not None and steps[top]["held"] else None
    broke = next((s for s in steps[(top + 1 if sustained else 0):] if not s["held"]), None)
    missed_below = [s["targetRoundsPerSecond"] for s in steps[:top] if not s["held"]] if sustained else []
    report = {"passed": bool(held) and summary.get("k6ExitCode") in (0, 99, 108),
              "maxSustainedRoundsPerSecond": sustained, "stepsMissedBelowIt": missed_below,
              "brokeAt": broke and {"targetRoundsPerSecond": broke["targetRoundsPerSecond"], "why":
                                    [w for w, bad in (("rate not reached", broke["roundsPerSecond"] < held_share * broke["targetRoundsPerSecond"]),
                                                      ("play p99 over target", (broke["playMs"]["p99"] or 0) > PLAY_P99_MS),
                                                      ("errors", (broke["errorRate"] or 0) > max_errors)) if bad]},
              "criteria": {"playP99Ms": PLAY_P99_MS, "maxErrorRate": max_errors, "heldShareOfTarget": held_share},
              "steps": steps, "wholeRun": sampler.window(t0, t0 + 10 ** 6), "k6": summary}
    log(f"ramp: highest rate held {sustained} rounds/s" + (f"; broke at {broke['targetRoundsPerSecond']} ({report['brokeAt']['why']})" if broke else "; never broke"))
    return write("ramp", report)


def steady(name, players, seconds, extra=None, admin=False):
    """A run of `players` players for `seconds` with points; returns (prefix, summary, process start, process)."""
    prefix, ids = prepare(players + (2 if admin else 0), admin=admin)
    settings = {"PLAYERS": players, "SLOW_PLAYERS": 0, "ADMINS": 2 if admin else 0, "RAMP": "10s", "DURATION": f"{seconds}s",
                "THINK_MIN": setting("THINK_MIN", 1), "THINK_MAX": setting("THINK_MAX", 3)}
    settings.update(extra or {})
    return prefix, ids, k6(name, settings, points=True)


def errors_by_time(name):
    return [(t, v) for t, m, v in points(name, {"player_errors"}) if v]


def answers_by_time(name):
    """(time, request name, HTTP status) of every session and play request (k6's http_reqs points)."""
    out = []
    with gzip.open(os.path.join(OUT, f"{name}-points.json.gz"), "rt") as f:
        for line in f:
            if '"http_reqs"' not in line:
                continue
            p = json.loads(line)
            tags = p["data"].get("tags", {})
            if p["metric"] == "http_reqs" and tags.get("name") in ("play", "session"):
                out.append((stamp(p["data"]["time"]), tags["name"], tags.get("status", "?")))
    return sorted(out)


def outage():
    players = setting("OUTAGE_PLAYERS", 30)
    before, length, after = setting("OUTAGE_BEFORE", 30), setting("OUTAGE_SECONDS", 120), setting("OUTAGE_AFTER", 30)
    settle = setting("SETTLE_MINUTES", 10 + 2 * length // 60)
    sampler = Sampler()
    sampler.start()
    prefix, ids, proc = steady("outage", players, before + length + after, {"NO_THRESHOLDS": 1})
    time.sleep(10 + before)
    log(f"outage: the wallet refuses every credit of {len(ids)} players for {length} s")
    down = time.time()
    # the mock wallet's faults are per wallet and used one call at a time: enough for every round and retry of the outage
    for p in ids:
        fault(p, "credit", "error", times=length * 2 + 100)
    time.sleep(max(0, length - (time.time() - down)))
    for p in ids:
        fault(p, "credit", "none")
    up = time.time()
    open_at_recovery = {s: n for s, n in statuses(prefix).items() if s not in SETTLED}
    log(f"outage: wallet back; rounds waiting at that moment: {open_at_recovery}")
    summary = finish(proc, "outage")
    took = wait_settled(prefix, settle, up)
    sampler.stop()
    errs = errors_by_time("outage")
    m = money(prefix)
    report = {"passed": took is not None and m["ok"],
              "outageSeconds": length, "settleMinutesAllowed": settle, "settledSecondsAfterRecovery": took,
              "openRoundsAtRecovery": open_at_recovery, "roundsByStatus": statuses(prefix), "money": m,
              "playerErrors": {"total": len(errs), "duringOutage": sum(1 for t, _ in errs if down <= t <= up)},
              "database": {"duringOutage": sampler.window(down, up), "afterRecovery": sampler.window(up, time.time())},
              "k6": summary}
    log(f"outage: settled {took} s after recovery (allowed {settle} min); money {m}")
    return write("outage", report)


def db_restart():
    players = setting("RESTART_PLAYERS", 30)
    before, after, grace = setting("RESTART_BEFORE", 30), setting("RESTART_AFTER", 60), setting("RESTART_GRACE", 30)
    settle = setting("SETTLE_MINUTES", 12)
    sampler = Sampler()
    sampler.start()
    prefix, _, proc = steady("db-restart", players, before + 60 + after, {"NO_THRESHOLDS": 1})
    time.sleep(10 + before)
    log("db-restart: restarting the database container")
    down = time.time()
    subprocess.run(["docker", "restart", "-t", "10", DB], check=True, capture_output=True)
    healthy = wait_healthy(DB)
    up = time.time()
    log(f"db-restart: database back after {round(up - down)} s")
    summary = finish(proc, "db-restart")
    took = wait_settled(prefix, settle, up)
    sampler.stop()
    # what players got outside the restart (plus a grace for the pool to reconnect): only 200, except 409
    # ROUND_IN_PROGRESS for a player whose round the restart cut off, until its round lock runs out and the minute's
    # recovery settles it (GameService: the lock is at least 300 s; RoundRecoveryService: 60 s later, then the next pass)
    lockout = setting("RESTART_LOCKOUT_SECONDS", 420)
    answers = answers_by_time("db-restart")
    outside = [(t, n, st) for t, n, st in answers if st != "200" and not (down - 1 <= t <= up + grace)]
    locked = [(t, n, st) for t, n, st in outside if n == "play" and st == "409" and t <= up + lockout]
    other = [x for x in outside if x not in locked]
    by_status = {}
    for _, n, st in outside:
        by_status[f"{n} {st}"] = by_status.get(f"{n} {st}", 0) + 1
    m = money(prefix)
    report = {"passed": healthy and not other and took is not None and m["ok"],
              "databaseDownSeconds": round(up - down), "graceSeconds": grace,
              "answers": {"total": len(answers), "failedWhileDown": sum(1 for t, n, st in answers if st != "200" and down - 1 <= t <= up + grace),
                          "failedOutsideTheRestart": by_status,
                          "roundInProgressLockout": {"answers": len(locked), "lastSecondsAfterRestart": round(max(t for t, _, _ in locked) - up) if locked else None,
                                                     "allowedSeconds": lockout},
                          "otherFailuresOutside": len(other)},
              "settleMinutesAllowed": settle, "settledSecondsAfterRestart": took, "roundsByStatus": statuses(prefix), "money": m,
              "database": {"afterRestart": sampler.window(up, time.time())}, "k6": summary}
    log(f"db-restart: failures outside the restart window {by_status} ({len(locked)} of them 409 for players whose round was cut off); "
        f"settled {took} s after; money {m}")
    return write("db-restart", report)


SEED_SEQ = "load_seed_seq"


def seed(rounds, first_day_offset, days, label, until_sql=None):
    """`rounds` finished rounds with their debit and credit, spread evenly over `days` days ending `first_day_offset` days
    before today (UTC), or over today up to `until_sql`; inserted in batches as the game writes them (PENDING, then
    decided and signed, then COMPLETED), so the integrity triggers accept them. The signatures are not real ones: these
    rounds exist for their size, and the round scan (hourly, from its checkpoint) never reaches back to them."""
    players = max(100, rounds // 100)
    batch = setting("SEED_BATCH", 100000)
    sql(f"""SET SESSION cte_max_recursion_depth = 20000;
            CREATE TABLE IF NOT EXISTS {SEED_SEQ} (n INT PRIMARY KEY);
            INSERT IGNORE INTO {SEED_SEQ} WITH RECURSIVE s (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM s WHERE i < 9999) SELECT i FROM s;
            INSERT IGNORE INTO player (external_id, username, currency, last_balance, blocked, created_at, last_seen_at)
              SELECT CONCAT('seed-', a.n * 10000 + b.n), 'seed', 'USD', 0, FALSE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
              FROM {SEED_SEQ} a JOIN {SEED_SEQ} b WHERE a.n <= {(players - 1) // 10000} AND a.n * 10000 + b.n < {players};""")
    if until_sql:
        start, span = "UTC_DATE()", f"GREATEST(60, TIMESTAMPDIFF(SECOND, UTC_DATE(), {until_sql}))"
    else:
        start, span = f"UTC_DATE() - INTERVAL {first_day_offset + days - 1} DAY", str(days * 86400 - 1)
    t = time.time()
    for k0 in range(0, rounds, batch):
        k1 = min(rounds, k0 + batch)
        sql(f"""SET SESSION transaction_isolation = 'READ-COMMITTED';
        INSERT INTO game_round (id, player_id, session_id, rows_count, risk, odds_ref, bet, balls, total_bet, max_win, currency, status,
                                created_at, updated_at, request_id)
          SELECT UUID(), p.id, '{label}', 8 + MOD(k, 7), MOD(k, 3), (SELECT MIN(id) FROM odds_history),
                 ELT(1 + MOD(k, 4), 0.10, 0.20, 0.50, 1.00), ELT(1 + MOD(k DIV 4, 7), 1, 1, 5, 10, 20, 50, 100),
                 ELT(1 + MOD(k, 4), 0.10, 0.20, 0.50, 1.00) * ELT(1 + MOD(k DIV 4, 7), 1, 1, 5, 10, 20, 50, 100), 100000, 'USD', 'PENDING',
                 {start} + INTERVAL (k * {span} DIV {rounds}) SECOND, {start} + INTERVAL (k * {span} DIV {rounds}) SECOND, UUID()
          FROM (SELECT a.n * 10000 + b.n AS k FROM {SEED_SEQ} a JOIN {SEED_SEQ} b WHERE a.n BETWEEN {k0 // 10000} AND {(k1 - 1) // 10000}
                AND a.n * 10000 + b.n >= {k0} AND a.n * 10000 + b.n < {k1}) x
          JOIN player p ON p.external_id = CONCAT('seed-', MOD(x.k, {players}));
        UPDATE game_round SET slots = LEFT(REPEAT('4,', balls), balls * 2 - 1), multipliers = LEFT(REPEAT('1.1,', balls), balls * 4 - 1),
               total_win = ROUND(total_bet * (0.46 + MOD(CRC32(id), 101) / 100), 2), signature = SHA2(id, 256), status = 'DEBITED',
               updated_at = created_at + INTERVAL 1 SECOND
          WHERE status = 'PENDING' AND session_id = '{label}';
        INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at)
          SELECT UUID(), id, player_id, 'DEBIT', total_bet, 'PENDING', 1, created_at, created_at FROM game_round
          WHERE status = 'DEBITED' AND session_id = '{label}';
        INSERT INTO wallet_transaction (id, round_id, player_id, type, amount, status, attempts, created_at, updated_at, signature)
          SELECT UUID(), id, player_id, 'CREDIT', total_win, 'PENDING', 1, updated_at, updated_at, SHA2(CONCAT('c', id), 256) FROM game_round
          WHERE status = 'DEBITED' AND session_id = '{label}';
        UPDATE wallet_transaction t JOIN game_round g ON g.id = t.round_id SET t.status = 'OK', t.pam_reference = CONCAT('seed-', t.type)
          WHERE g.status = 'DEBITED' AND g.session_id = '{label}' AND t.status = 'PENDING';
        UPDATE game_round SET status = 'COMPLETED' WHERE status = 'DEBITED' AND session_id = '{label}';""")
        log(f"  seeded {k1:,} / {rounds:,} rounds ({round(time.time() - t)} s)")
    # their days' totals (today's too), as the game would have counted them: by the daily job for a closed day, and by
    # each round's increment for today (the rebuild of today runs only at start-up and every hour, R2)
    sql(f"""INSERT INTO round_daily_totals (day, currency, rows_count, risk, rounds, total_bet, total_win)
              SELECT DATE(created_at), currency, rows_count, risk, COUNT(*), SUM(total_bet), SUM(total_win) FROM game_round
              WHERE session_id = '{label}' GROUP BY DATE(created_at), currency, rows_count, risk
            ON DUPLICATE KEY UPDATE rounds = rounds + VALUES(rounds), total_bet = total_bet + VALUES(total_bet), total_win = total_win + VALUES(total_win);
            INSERT INTO round_daily_players (day, currency, player_id, rounds, total_bet, total_win)
              SELECT DATE(created_at), currency, player_id, COUNT(*), SUM(total_bet), SUM(total_win) FROM game_round
              WHERE session_id = '{label}' GROUP BY DATE(created_at), currency, player_id
            ON DUPLICATE KEY UPDATE rounds = rounds + VALUES(rounds), total_bet = total_bet + VALUES(total_bet), total_win = total_win + VALUES(total_win);""")
    sql(f"ANALYZE TABLE game_round, wallet_transaction, player, round_daily_totals, round_daily_players")
    return round(time.time() - t)


def rebuild_read_seconds():
    """How long the daily job's read of today takes now (the same two plain SELECTs as StatsRepository.rebuildDay, in one
    REPEATABLE READ snapshot; it counts only rounds whose increment is written, R2)."""
    t = time.time()
    sql("""SET SESSION transaction_isolation = 'REPEATABLE-READ';
           START TRANSACTION WITH CONSISTENT SNAPSHOT;
           SELECT currency, rows_count, risk, COUNT(*), SUM(total_bet), COALESCE(SUM(total_win), 0) FROM game_round
             WHERE created_at >= UTC_DATE() AND created_at < UTC_DATE() + INTERVAL 1 DAY AND status IN ('COMPLETED', 'CREDIT_PENDING')
             GROUP BY currency, rows_count, risk;
           SELECT currency, player_id, COUNT(*), SUM(total_bet), COALESCE(SUM(total_win), 0) FROM game_round
             WHERE created_at >= UTC_DATE() AND created_at < UTC_DATE() + INTERVAL 1 DAY AND status IN ('COMPLETED', 'CREDIT_PENDING')
             GROUP BY currency, player_id;
           COMMIT;""")
    return round(time.time() - t, 2)


def history():
    rounds, days = setting("HISTORY_ROUNDS", 200000), setting("HISTORY_DAYS", 60)
    today = setting("TODAY_ROUNDS", max(10000, rounds // 4))
    players, seconds = setting("HISTORY_PLAYERS", 50), setting("HISTORY_SECONDS", 120)
    log(f"history: seeding {rounds:,} rounds over {days} days")
    took_history = seed(rounds, 1, days, "seed")
    sizes = {"gameRound": int(one("SELECT COUNT(*) FROM game_round")), "walletTransaction": int(one("SELECT COUNT(*) FROM wallet_transaction"))}
    runs = {}
    for name, label in (("history-small-today", "without"), ("history-big-today", "with")):
        if label == "with":
            log(f"history: seeding {today:,} rounds played today (the daily job rebuilds today every minute)")
            took_today = seed(today, 0, 1, "seed-today", until_sql="UTC_TIMESTAMP() - INTERVAL 20 MINUTE")
        read = rebuild_read_seconds()
        rows_today = int(one("SELECT COUNT(*) FROM game_round WHERE created_at >= UTC_DATE()"))
        log(f"history: run {label} a big day to rebuild ({rows_today:,} rounds today; the rebuild's read takes {read} s)")
        sampler = Sampler()
        sampler.start()
        prefix, _, proc = steady(name, players, seconds, admin=True)
        summary = finish(proc, name)
        sampler.stop()
        play = [v for t, m, v in points(name, {"play_duration"})]
        runs[label] = {"roundsToday": rows_today, "rebuildReadSeconds": read, "playMs": {"med": pct(play, 0.5), "p95": pct(play, 0.95), "p99": pct(play, 0.99),
                       "max": pct(play, 1.0)}, "rounds": summary.get("rounds"), "playerErrorRate": summary.get("playerErrorRate"),
                       "adminPageMs": summary.get("adminPageMs"), "thresholds": summary.get("thresholds"), "k6Passed": summary.get("passed"),
                       "database": sampler.window(sampler.samples[0]["t"], sampler.samples[-1]["t"]), "prefix": prefix}
    for label in runs:
        runs[label]["settledSeconds"] = wait_settled(runs[label]["prefix"], setting("SETTLE_MINUTES", 5), time.time())
        runs[label]["money"] = money(runs[label].pop("prefix"))
    a, b = runs["without"]["playMs"]["p99"] or 0, runs["with"]["playMs"]["p99"] or 0
    ratio = setting("REBUILD_P99_RATIO", 2.0)
    jump = b > max(a * ratio, a + 100)
    report = {"passed": all(r["k6Passed"] and r["money"]["ok"] and r["settledSeconds"] is not None for r in runs.values()) and not jump,
              "seeded": {"historyRounds": rounds, "historyDays": days, "todayRounds": today, "historySeconds": took_history,
                         "todaySeconds": took_today, "tableRowsAfterHistory": sizes},
              "playP99Ms": {"withoutBigRebuild": a, "withBigRebuild": b, "allowed": f"at most {ratio} x, or +100 ms"}, "p99Jumped": jump, "runs": runs}
    log(f"history: play p99 {a} ms without the big rebuild, {b} ms with it")
    return write("history", report)


def after_run():
    """The fixed-load run's own checks (scripts/loadtest.sh runs this after k6, with the same OUT): every round of the run
    settles (COMPLETED or CANCELLED) within SETTLE_MINUTES, nothing is paid twice, the wallets equal the game's totals and
    the daily totals equal the rounds (R2)."""
    data = json.load(open(os.path.join(OUT, "wallets.json")))
    prefix = data["wallets"][0]["playerId"].rsplit("-", 1)[0] + "-"
    took = wait_settled(prefix, setting("SETTLE_MINUTES", 10), time.time())
    m = money(prefix)
    report = {"passed": took is not None and m["ok"], "settledSeconds": took, "roundsByStatus": statuses(prefix), "money": m}
    log(f"after-run: rounds {report['roundsByStatus']}, settled after {took} s; money {m}")
    return write("after-run", report)


SCENARIOS = {"ramp": ramp, "outage": outage, "db-restart": db_restart, "history": history, "after-run": after_run}

if __name__ == "__main__":
    chosen = sys.argv[2:] or sys.exit(__doc__)
    unknown = [s for s in chosen if s not in SCENARIOS]
    if unknown:
        sys.exit(f"unknown scenario {unknown}; one of {list(SCENARIOS)}")
    os.makedirs(OUT, exist_ok=True)
    results = {s: SCENARIOS[s]() for s in chosen}
    print(json.dumps(results))
    sys.exit(0 if all(results.values()) else 1)
