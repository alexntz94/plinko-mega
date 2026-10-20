"""Shared setup of the end-to-end and attack suite: the running test stack, its secrets, helpers, and the accounts,
wallets and rounds the areas need.

The stack: scripts/test-env.sh <dir>, then docker compose -p plinkotest --env-file <dir>/test.env up -d --build.
Run:   python3 -m pytest tests/e2e --env-file <dir>/test.env          (or with TEST_ENV=<dir>/test.env set)
       python3 -m pytest tests/e2e --env-file <dir>/test.env -k settlements     (one area, on a fresh stack)

Every area (one module, test_NN_<area>.py) asks for what it needs through the fixtures below (admin, second_admin,
tester, faulty, blocked_round, viewer, ...): whatever an earlier area already made is reused, anything missing is made
on the spot. The whole suite runs in file order; the areas that restart the backend, rotate keys or delete the admin
accounts come last. An area run alone expects a fresh stack (the admin's 2FA is enrolled by the suite itself).

Each check of the old script is a check(name, ok, detail) call: a failed one is recorded and the test goes on; at the
end of the test every failed check is reported together, by name."""
import base64, hashlib, hmac, http.cookiejar, json, os, re, struct, subprocess, time, urllib.error, urllib.parse, urllib.request
from decimal import Decimal
from pathlib import Path

import pytest

GAME = "http://localhost:8080"
ADMIN = "http://127.0.0.1:8081"
REPO = Path(__file__).resolve().parents[2]
ENV = {}            # the test stack's settings and secrets (filled from the env file when the run starts)
ENV_FILE = None
STATE = {}          # what the areas made so far: accounts, wallets, rounds (shared by the whole run)
RESULTS = []        # every check, for the summary line


# ---------------------------------------------------------------- pytest plumbing
def pytest_addoption(parser):
    parser.addoption("--env-file", default=os.environ.get("TEST_ENV"),
                     help="the test stack's env file from scripts/test-env.sh (default: $TEST_ENV)")


def pytest_configure(config):
    global ENV_FILE
    if config.option.collectonly or config.option.help:
        return
    ENV_FILE = config.getoption("--env-file")
    if not ENV_FILE:
        raise pytest.UsageError("give the test stack's env file: --env-file <dir>/test.env (or set TEST_ENV)")
    ENV.update(dict(l.split("=", 1) for l in open(ENV_FILE).read().splitlines() if "=" in l and not l.startswith("#")))


class Checks:
    """Soft asserts: every check is recorded; the failed ones fail the test at its end, all of them named."""

    def __init__(self):
        self.failures = []

    def __call__(self, name, ok, detail=""):
        RESULTS.append((name, bool(ok)))
        print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
        if not ok:
            self.failures.append(name + (f"  [{detail}]" if detail else ""))
        return bool(ok)


@pytest.fixture
def check():
    return Checks()


@pytest.hookimpl(wrapper=True)
def pytest_runtest_call(item):
    result = yield
    checks = item.funcargs.get("check")
    if checks is not None and checks.failures:
        raise AssertionError(f"{len(checks.failures)} check(s) failed:\n  " + "\n  ".join(checks.failures))
    return result


def pytest_terminal_summary(terminalreporter):
    if RESULTS:
        passed = sum(1 for _, ok in RESULTS if ok)
        terminalreporter.write_line(f"{passed}/{len(RESULTS)} checks passed")


# ---------------------------------------------------------------- admin panel (browser sessions, 2FA)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Browser:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)

    def req(self, method, url, data=None, headers=None):
        body = urllib.parse.urlencode(data, doseq=True).encode() if isinstance(data, dict) else data
        r = urllib.request.Request(url, data=body, method=method, headers=headers or {})
        try:
            resp = self.opener.open(r, timeout=30)
            return resp.status, resp.headers, resp.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read().decode()

    def csrf(self, html):
        m = re.search(r'name="_csrf" value="([^"]+)"', html)
        return m.group(1) if m else None

    def get(self, path):
        return self.req("GET", ADMIN + path)

    def form_token(self, html):
        m = re.search(r'name="formToken" value="([^"]+)"', html)
        return m.group(1) if m else None

    def post_form(self, path, data, page="/admin"):
        _, _, html = self.get(page)
        data = dict(data)
        data["_csrf"] = self.csrf(html)
        # N14.15: a guarded form's one-time token, from the page that shows it (sent as a browser would)
        token = self.form_token(html)
        if token and "formToken" not in data:
            data["formToken"] = token
        return self.req("POST", ADMIN + path, data)


def totp(secret, step):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def next_step_code(secret, after_step):
    while True:
        step = int(time.time()) // 30
        if step > after_step:
            return totp(secret, step), step
        time.sleep(1)


def login(username, password, secret=None, last_step=0):
    b = Browser()
    _, _, html = b.get("/admin/login")
    data = {"username": username, "password": password, "_csrf": b.csrf(html)}
    step = last_step
    if secret:
        data["code"], step = next_step_code(secret, last_step)
    status, headers, _ = b.req("POST", ADMIN + "/admin/login", data)
    return b, status, headers.get("Location", ""), step


def enroll(username, password):
    b, status, location, _ = login(username, password)
    assert location.endswith("/admin/2fa/setup"), location
    _, _, html = b.get("/admin/2fa/setup")
    secret = re.search(r'class="secret">([^<]+)<', html).group(1).replace(" ", "")
    step = int(time.time()) // 30
    status, headers, _ = b.req("POST", ADMIN + "/admin/2fa/setup", {"code": totp(secret, step), "_csrf": b.csrf(html)})
    return secret, step, headers.get("Location", "")


def full_login(username, password, secret, step):
    """Login with TOTP; completes a forced password change if the account has a temporary password."""
    b, status, location, step = login(username, password, secret, step)
    if location.endswith("/admin/password/change"):
        new = password + "-Nw1"
        _, _, html = b.get("/admin/password/change")
        b.req("POST", ADMIN + "/admin/password/change",
              {"currentPassword": password, "newPassword": new, "confirmPassword": new, "_csrf": b.csrf(html)})
        b, status, location, step = login(username, new, secret, step)
        password = new
    return b, location, step, password


class Account:
    """An admin account the suite knows the password and 2FA secret of, with its signed-in session (get/post_form/req
    act through that session). step is the last TOTP step used: a code is never sent twice."""

    def __init__(self, username, password, secret, step, session=None):
        self.username, self.password, self.secret, self.step, self.session = username, password, secret, step, session

    def get(self, path):
        return self.session.get(path)

    def post_form(self, path, data, page="/admin"):
        return self.session.post_form(path, data, page)

    def req(self, *a, **k):
        return self.session.req(*a, **k)

    def login(self, password=None):
        """A new sign-in (password + the next TOTP code): (browser, status, location)."""
        b, status, location, self.step = login(self.username, password or self.password, self.secret, self.step)
        return b, status, location

    def signed_in(self):
        """The session, signed in again if it ended (e.g. the backend was restarted)."""
        if self.session is None or self.session.get("/admin")[0] != 200:
            self.session, location, self.step, self.password = full_login(self.username, self.password, self.secret, self.step)
            assert location.endswith("/admin"), f"{self.username} can't sign in: {location}"
        return self


def new_account(creator, username, password, role):
    """An account created by `creator` (directly: before four-eyes, or for a viewer), enrolled in 2FA and signed in."""
    creator.post_form("/admin/users", {"username": username, "password": password, "role": role}, "/admin/users")
    secret, step, _ = enroll(username, password)
    session, location, step, password = full_login(username, password, secret, step)
    account = Account(username, password, secret, step, session)
    account.first_location = location
    return account


# ---------------------------------------------------------------- game API, database, wallet
def api(path, body, session=None, raw=None):
    headers = {"Content-Type": "application/json"}
    if session:
        headers["Authorization"] = "Bearer " + session
    r = urllib.request.Request(GAME + path, data=raw if raw is not None else json.dumps(body).encode(), method="POST", headers=headers)
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        text = e.read()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text[:100]}


def sql(query, user="root", password=None):
    password = password or ENV["MYSQL_ROOT_PASSWORD"]
    p = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=" + password, "plinkotest-db-1", "mysql", "-u" + user, "-N", "-B", "plinko", "-e", query],
                       capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def app(query):
    """A statement with the application's own database login (least privilege, triggers apply)."""
    return sql(query, "plinko_app", ENV["DB_PASSWORD"])


def sql_script(script):
    p = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], "plinkotest-db-1", "mysql", "-uroot", "plinko"],
                       input=script, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def without_round_guard(query):
    """Runs a statement with the game_round guard trigger dropped for a moment (as someone past the database's own
    guard could), then puts the trigger back exactly."""
    body = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], "plinkotest-db-1", "mysql", "-uroot", "-N", "-B",
                           "--raw", "plinko", "-e", "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS "
                           "WHERE TRIGGER_SCHEMA='plinko' AND TRIGGER_NAME='game_round_guard'"], capture_output=True, text=True).stdout.strip()
    assert body.startswith("BEGIN"), body[:80]
    sql("DROP TRIGGER game_round_guard")
    try:
        return sql(query)
    finally:
        sql_script("DELIMITER //\nCREATE TRIGGER game_round_guard BEFORE UPDATE ON game_round FOR EACH ROW " + body + "//\nDELIMITER ;\n")


def fresh_token(player_id):
    token = "t" + hashlib.sha256(str(time.time()).encode()).hexdigest()[:30]
    sql(f"UPDATE mock_wallet SET token='{token}', token_issued_at=NULL WHERE player_id='{player_id}'")
    return token


def mock_balance(player_id):
    return Decimal(sql(f"SELECT balance FROM mock_wallet WHERE player_id='{player_id}'")[1])


def pam_answer(endpoint, payload):
    """A signed call to the mock PAM as the game makes it: (HTTP status, answer body)."""
    data = json.dumps(payload).encode()
    r = urllib.request.Request(ADMIN + "/mock-pam/" + endpoint, data=data, method="POST",
                               headers={"Content-Type": "application/json", "X-Api-Key": ENV["PAM_API_KEY"],
                                        "X-Signature": hmac.new(ENV["PAM_SECRET"].encode(), data, hashlib.sha256).hexdigest()})
    try:
        resp = urllib.request.urlopen(r, timeout=10)
        return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        text = e.read()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text[:100]}


def pam_call(endpoint, payload):
    return pam_answer(endpoint, payload)[0]


SMALL = {"rows": 8, "risk": 0, "bet": 1, "balls": 1}


def fault(endpoint, kind, times=1, player=None):
    """The mock PAM misbehaves on request (POST /mock-pam/_fault, signed), by default for the faulty player."""
    pam_call("_fault", {"playerId": player or STATE["faulty"].pid, "endpoint": endpoint, "fault": kind, "times": times})


def last_round(player=None):
    player = player or STATE["faulty"].pid
    row = sql(f"SELECT r.id, r.status FROM game_round r JOIN player p ON p.id = r.player_id WHERE p.external_id='{player}' "
              "ORDER BY r.created_at DESC, r.id DESC LIMIT 1")[1]
    return row.split("\t") if row else (None, None)


def round_status(rid):
    return sql(f"SELECT status FROM game_round WHERE id='{rid}'")[1]


def pam_rows(rid, kind):
    return int(sql(f"SELECT COUNT(*) FROM mock_transaction WHERE round_id='{rid}' AND type='{kind}'")[1])


def retry(rid, by=None):
    """The admin's Retry button on a round; its status afterwards."""
    (by or STATE["admin"]).post_form(f"/admin/rounds/{rid}/retry", {}, f"/admin/rounds/{rid}")
    return round_status(rid)


def wait_session(player_id, expected):
    """Opens sessions until one answers `expected` (settings and odds are re-checked every minute): the last answer."""
    deadline = time.time() + 90
    while time.time() < deadline and api("/api/game/session", {"token": fresh_token(player_id)})[0] != expected:
        time.sleep(5)
    return api("/api/game/session", {"token": fresh_token(player_id)})


# ---------------------------------------------------------------- raw HTTP, the stack itself
no_redirect = urllib.request.build_opener(NoRedirect)


def get_raw(url, headers=None):
    try:
        resp = no_redirect.open(urllib.request.Request(url, headers=headers or {}), timeout=30)
        return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()
    except (urllib.error.URLError, OSError):
        return 0, None, b""     # not answering (e.g. while nginx restarts)


def post_raw(url, form):
    # the demo's Play button: a form POST (review 2.5)
    try:
        resp = no_redirect.open(urllib.request.Request(url, data=urllib.parse.urlencode(form).encode(), method="POST",
                                                       headers={"Content-Type": "application/x-www-form-urlencoded"}), timeout=30)
        return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()
    except (urllib.error.URLError, OSError):
        return 0, None, b""


def head(path, gzip=False):
    r = urllib.request.Request(GAME + path, headers={"Accept-Encoding": "gzip"} if gzip else {})
    try:
        resp = urllib.request.urlopen(r, timeout=10)
        return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, b""


def compose(*args, env=None):
    return subprocess.run(["docker", "compose", "-p", "plinkotest", "--env-file", ENV_FILE, *args],
                          capture_output=True, text=True, timeout=300, env={**os.environ, **(env or {})})


def restart_backend(env):
    compose("up", "-d", "--no-deps", "--force-recreate", "backend", env=env)
    for _ in range(60):
        if subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", "plinkotest-backend-1"],
                          capture_output=True, text=True).stdout.strip() == "healthy":
            break
        time.sleep(3)
    # nginx looks the backend up once; the new container may have a new address
    subprocess.run(["docker", "restart", "plinkotest-web-1"], capture_output=True)
    for _ in range(30):
        if get_raw(GAME + "/healthz")[0] == 200:
            break
        time.sleep(2)


# ---------------------------------------------------------------- odds forms used by several areas
FAIR = {"rows": "8", "risk": "2", "multiplier": ["0.2", "0.3", "1.5", "4", "28"],
        "probability": ["27.34375", "43.75", "21.875", "6.25", "0.78125"]}


def bonus_form(enabled, chance, boosts, hot=None, meter=None):
    form = {"goldenChance": chance, "boostMultiplier": [m for m, _ in boosts] + [""] * (6 - len(boosts)),
            "boostWeight": [w for _, w in boosts] + [""] * (6 - len(boosts)),
            "hotChance": hot[0] if hot else "20", "hotBoost": hot[1] if hot else "3",
            "meterCharges": meter[0] if meter else "10", "meterMin": meter[1] if meter else "2",
            "meterShare": meter[2] if meter else "30", "meterFree": meter[3] if meter else "10"}
    if enabled:
        form["goldenEnabled"] = "true"
    if hot:
        form["hotEnabled"] = "true"
    if meter:
        form["meterEnabled"] = "true"
    return form


# ---------------------------------------------------------------- what the areas need, made on demand
def admin_account():
    """The first admin ("admin", the ADMIN_PASSWORD secret), enrolled in 2FA and signed in."""
    if "admin" not in STATE:
        if sql("SELECT totp_secret IS NOT NULL FROM admin_user WHERE username='admin'")[1] == "1":
            pytest.fail("the admin's 2FA was enrolled before this run (its secret is unknown here): start a fresh stack")
        secret, step, _ = enroll("admin", ENV["ADMIN_PASSWORD"])
        STATE["admin"] = Account("admin", ENV["ADMIN_PASSWORD"], secret, step)
    return STATE["admin"].signed_in()


@pytest.fixture
def admin():
    return admin_account()


def second_admin_account():
    """admin2: created by the first admin while it is the only one (so directly), enrolled; from then on four-eyes is
    on. What the creation showed is kept for the area that checks it (early_ops: an operator created before it)."""
    if "admin2" not in STATE:
        admin = admin_account()
        early = {"username": "early-ops", "role": "OPERATOR", "password": "Early-Operator-Pass-1"}  # gitleaks:allow (made-up test password)
        admin.post_form("/admin/users", early, "/admin/users")
        early_ops = sql("SELECT COUNT(*) FROM admin_user WHERE username='early-ops'")[1]
        account = new_account(admin, "admin2", "Second-Admin-Pass-1", "ADMIN")
        account.early_ops = early_ops
        STATE["admin2"] = account
    return STATE["admin2"].signed_in()


@pytest.fixture
def second_admin(admin):
    return second_admin_account()


def third_admin_account():
    """admin3: proposed by admin2, approved by the first admin (four-eyes is on by then); not enrolled yet."""
    if "admin3" not in STATE:
        admin, admin2 = admin_account(), second_admin_account()
        if sql("SELECT COUNT(*) FROM admin_user WHERE username='admin3'")[1] == "0":
            admin2.post_form("/admin/users", {"username": "admin3", "password": "Third-Admin-Pass-1", "role": "ADMIN"}, "/admin/users")
            cid = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1]
            admin.post_form(f"/admin/changes/{cid}/approve", {}, "/admin/changes")
        STATE["admin3"] = Account("admin3", "Third-Admin-Pass-1", None, 0)
    return STATE["admin3"]


@pytest.fixture
def third_admin(admin):
    return third_admin_account()


def viewer_account():
    """viewer1: a viewer created by the first admin once four-eyes is on, enrolled and signed in."""
    if "viewer1" not in STATE:
        second_admin_account()
        STATE["viewer1"] = new_account(admin_account(), "viewer1", "Viewer-Only-Pass-1", "VIEWER")
    return STATE["viewer1"]


@pytest.fixture
def viewer(admin):
    return viewer_account()


def operator_account():
    """ops1: an operator proposed by the first admin and approved by admin2, enrolled and signed in."""
    if "ops1" not in STATE:
        admin, admin2 = admin_account(), second_admin_account()
        if sql("SELECT COUNT(*) FROM admin_user WHERE username='ops1'")[1] == "0":
            admin.post_form("/admin/users", {"username": "ops1", "password": "Operator-One-Pass-1", "role": "OPERATOR"}, "/admin/users")
            oid = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1]
            admin2.post_form(f"/admin/changes/{oid}/approve", {}, "/admin/changes")
        secret, step, _ = enroll("ops1", "Operator-One-Pass-1")
        session, location, step, password = full_login("ops1", "Operator-One-Pass-1", secret, step)
        STATE["ops1"] = Account("ops1", password, secret, step, session)
    return STATE["ops1"]


@pytest.fixture
def operator(admin):
    return operator_account()


class Player:
    """A mock wallet the suite plays with: pid (the wallet's player id) and sid (its current game session)."""

    def __init__(self, pid, sid=None):
        self.pid, self.sid = pid, sid

    def new_session(self):
        status, body = api("/api/game/session", {"token": fresh_token(self.pid)})
        self.sid = body.get("sessionId")
        return self.sid


def make_wallet(username, balance):
    admin_account().post_form("/admin/mock-pam", {"username": username, "balance": balance}, "/admin/mock-pam")
    return sql(f"SELECT player_id FROM mock_wallet WHERE username='{username}' ORDER BY created_at DESC LIMIT 1")[1]


@pytest.fixture
def tester(admin):
    """The "tester" wallet (500 in the platform currency) with an open game session."""
    if "tester" not in STATE:
        player = Player(make_wallet("tester", "500"))
        player.new_session()
        STATE["tester"] = player
    return STATE["tester"]


@pytest.fixture
def faulty(admin):
    """The "faulty" wallet (1000), the one the mock PAM is told to misbehave for, with an open game session."""
    if "faulty" not in STATE:
        player = Player(make_wallet("faulty", "1000"))
        player.new_session()
        STATE["faulty"] = player
    return STATE["faulty"]


@pytest.fixture
def blocked_round(faulty, admin):
    """A round blocked for review (5.2): its credit refused outright by the wallet (so it may be settled later, N2),
    then the odds version it was played on edited in the database; the retry blocks it, never paid. What happened is
    kept for the integrity area (status, credits, balance); the settlement area (7.3) settles it."""
    if "blocked" not in STATE:
        before = mock_balance(faulty.pid)
        fault("credit", "limit")
        status, resp = api("/api/game/play", SMALL, faulty.sid)
        rid, st = last_round()
        ref = sql(f"SELECT odds_ref FROM game_round WHERE id='{rid}'")[1]
        original = sql(f"SELECT multipliers FROM odds_history WHERE id={ref}")[1]
        max_win_saved = sql(f"SELECT max_win IS NOT NULL FROM game_round WHERE id='{rid}'")[1]
        sql(f"UPDATE odds_history SET multipliers=CONCAT('9', multipliers) WHERE id={ref}")
        st = retry(rid)
        STATE["blocked"] = dict(rid=rid, ref=ref, max_win_saved=max_win_saved, status=st, credits=pam_rows(rid, "CREDIT"),
                                before=before, after=mock_balance(faulty.pid))
        sql(f"UPDATE odds_history SET multipliers='{original}' WHERE id={ref}")
    return STATE["blocked"]
