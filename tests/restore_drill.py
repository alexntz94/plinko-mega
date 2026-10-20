"""Restore drill (review 7.1): on a fresh test stack, take a backup, keep playing, put the backup back, and record what
the game reports. Run on a stack nothing else uses:
    docker compose -p plinkotest --env-file <test.env> up -d   then   python3 tests/restore_drill.py <test.env>
The mock wallet's tables are left out of the backup and the restore, as a real casino wallet is a separate system."""
import os, subprocess, sys, time, urllib.request

# the end-to-end suite's helpers (browser, admin login with 2FA, sql, api) without running its checks
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "e2e"))
from conftest import ENV, GAME, api, enroll, fresh_token, login, sql  # noqa: E402

ENV.update(dict(l.split("=", 1) for l in open(sys.argv[1]).read().splitlines() if "=" in l and not l.startswith("#")))
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))

DB = "plinkotest-db-1"
WALLET_TABLES = ["mock_wallet", "mock_transaction"]


def mysql_cmd(tool, extra):
    return ["docker", "exec", "-i", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], DB, tool, "-uroot", *extra]


def compose(*args):
    return subprocess.run(["docker", "compose", "-p", "plinkotest", "--env-file", sys.argv[1], *args], capture_output=True, text=True, timeout=300)


def wait_healthy():
    for _ in range(60):
        if subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", "plinkotest-backend-1"], capture_output=True,
                          text=True).stdout.strip() == "healthy":
            break
        time.sleep(3)
    subprocess.run(["docker", "restart", "plinkotest-web-1"], capture_output=True)
    for _ in range(30):
        try:
            if urllib.request.urlopen(GAME + "/healthz", timeout=5).status == 200:
                return
        except Exception:
            pass
        time.sleep(2)


def play(session, n):
    ids = []
    for _ in range(n):
        time.sleep(1.2)
        status, r = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, session)
        if status == 200:
            ids.append(r["roundId"])
    return ids


# before the backup: an admin with 2FA, a player, a few rounds
secret, step, _ = enroll("admin", ENV["ADMIN_PASSWORD"])
admin, _, location, step = login("admin", ENV["ADMIN_PASSWORD"], secret, step)
check("drill: admin signs in", location.endswith("/admin"), location)
sql("INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at) "
    "VALUES ('drill-1', 'drill', 'USD', 100, 'drill-token-1', UTC_TIMESTAMP(3))")
status, s = api("/api/game/session", {"token": "drill-token-1"})
before = play(s.get("sessionId"), 3)
check("drill: 3 rounds before the backup", len(before) == 3, before)

# the backup: everything but the wallet's tables
backup = subprocess.run(mysql_cmd("mysqldump", ["--single-transaction", "--routines", "--triggers", "--set-gtid-purged=OFF",
                                                *[f"--ignore-table=plinko.{t}" for t in WALLET_TABLES], "plinko"]), capture_output=True)
check("drill: backup taken", backup.returncode == 0 and len(backup.stdout) > 10_000, backup.stderr[-200:])
time.sleep(2)
restore_point = time.strftime("%Y-%m-%dT%H:%M", time.gmtime(time.time() - 60))

# after the backup: more rounds, the game stopped and started again (a settings change), a new operator account
after = play(s.get("sessionId"), 3)
admin.post_form("/admin/settings/game-enabled", {"enabled": "false", "reason": "drill"}, "/admin/settings")
admin.post_form("/admin/settings/game-enabled", {"enabled": "true", "reason": "drill"}, "/admin/settings")
admin.post_form("/admin/users", {"username": "drill-ops", "password": "Drill-Operator-Pass-1", "role": "OPERATOR"}, "/admin/users")
wallet_after = sql("SELECT balance FROM mock_wallet WHERE player_id='drill-1'")[1]
check("drill: 3 more rounds after the backup", len(after) == 3, after)

# the restore: backend stopped, the backup put back, backend started
compose("stop", "backend")
restored = subprocess.run(mysql_cmd("mysql", ["plinko"]), input=backup.stdout, capture_output=True)
check("drill: backup restored", restored.returncode == 0, restored.stderr[-300:])
compose("start", "backend")
wait_healthy()
time.sleep(20)

logs = compose("logs", "--no-color", "--since", "2m", "backend").stdout
alarms = [l for l in logs.splitlines() if "INTEGRITY" in l or "TAMPERED" in l or "ALERT" in l]
print("\n--- what the server reported after the restore:")
print("\n".join(alarms[-20:]) or "(no integrity alarms or alerts)")
print("---\n")
check("drill: the game starts after the restore", urllib.request.urlopen(GAME + "/healthz", timeout=5).status == 200)
admin, _, location, step = login("admin", ENV["ADMIN_PASSWORD"], secret, step)
check("drill: the admin signs in with the same 2FA", location.endswith("/admin"), location)
_, _, html = admin.get("/admin")
check("drill: the dashboard shows no integrity alarm", "failing signature check</span><strong>0" in html and "Intact" in html, "dashboard")
check("drill: the rounds after the backup are gone from the game",
      sql("SELECT COUNT(*) FROM game_round WHERE id IN ('" + "','".join(after) + "')")[1] == "0")
check("drill: the operator account made after the backup is gone", sql("SELECT COUNT(*) FROM admin_user WHERE username='drill-ops'")[1] == "0")
check("drill: the wallet kept its money (a separate system)", sql("SELECT balance FROM mock_wallet WHERE player_id='drill-1'")[1] == wallet_after)
status, _, html = admin.get(f"/admin/finance/wallet-check?run=1&from={restore_point}&to=" + time.strftime("%Y-%m-%dT%H:%M", time.gmtime(time.time() + 120)))
found = sum(html.count(r) for r in after)
check("drill: the wallet check lists the lost rounds' bets and wins", status == 200 and "Differences found" in html and found >= 3, found)
status, s2 = api("/api/game/session", {"token": fresh_token("drill-1")})
check("drill: the player plays on after the restore", status == 200 and len(play(s2.get("sessionId"), 1)) == 1, status)
_, _, html = admin.get("/admin")
check("drill: still no integrity alarm after new rounds", "failing signature check</span><strong>0" in html, "dashboard")
passed = sum(1 for _, ok in results if ok)
print(f"\n{passed}/{len(results)} drill checks passed")
sys.exit(0 if passed == len(results) else 1)
