"""Wallets and an admin sign-in for the load test (review 5.6), on a test stack started with scripts/test-env.sh.
Usage: python3 tests/load/prepare.py <test.env> <wallets> <out dir> [compose project, default plinkotest]
Writes <out dir>/wallets.json: one mock wallet per virtual user ({playerId, token}, a fresh launch token each, credit
for the whole run) and the admin's session cookie for the dashboard and Finance page loads. Run by scripts/loadtest.sh.

The admin signs in with 2FA. On a fresh stack the admin is enrolled here and the authenticator secret is kept in
<out dir>/admin-totp for later runs; on a stack whose admin was enrolled by something else (e.g. tests/security_e2e.py)
the admin page loads are skipped (the cookie is left empty) with a note. PREPARE_ADMIN=0 skips the admin sign-in (runs
without admins: tests/load/scenarios.py)."""
import base64, hashlib, hmac, http.cookiejar, json, os, re, struct, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

ENV = dict(l.split("=", 1) for l in open(sys.argv[1]).read().splitlines() if "=" in l and not l.startswith("#"))
COUNT = int(sys.argv[2])
OUT = sys.argv[3]
PROJECT = sys.argv[4] if len(sys.argv) > 4 else "plinkotest"
ADMIN = os.environ.get("ADMIN_HOST_URL", "http://127.0.0.1:8081")


def sql(query):
    p = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], f"{PROJECT}-db-1", "mysql", "-uroot", "-N", "-B",
                        "plinko", "-e", query], capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit("database: " + p.stderr.strip())
    return p.stdout.strip()


def wallets(count):
    run = uuid.uuid4().hex[:8]
    made = [{"playerId": f"load-{run}-{i}", "token": "lt" + uuid.uuid4().hex} for i in range(count)]
    for start in range(0, count, 500):
        values = ",".join(f"('{w['playerId']}', 'load', 'USD', 1000000, '{w['token']}', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))"
                          for w in made[start:start + 500])
        sql("INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at, token_issued_at) VALUES " + values)
    return made


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Browser:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)

    def req(self, method, path, data=None):
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        try:
            resp = self.opener.open(urllib.request.Request(ADMIN + path, data=body, method=method), timeout=30)
            return resp.status, resp.headers.get("Location", ""), resp.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Location", ""), e.read().decode()

    def csrf(self, path):
        m = re.search(r'name="_csrf" value="([^"]+)"', self.req("GET", path)[2])
        return m.group(1) if m else ""

    def cookie(self):
        return "; ".join(f"{c.name}={c.value}" for c in self.jar)


def totp(secret, step):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def admin_cookie():
    """A signed-in admin session, or "" when the admin can't be signed in here."""
    saved = os.path.join(OUT, "admin-totp")
    b = Browser()
    _, location, _ = b.req("POST", "/admin/login", {"username": ENV.get("ADMIN_USERNAME", "admin"), "password": ENV["ADMIN_PASSWORD"],
                                                    "_csrf": b.csrf("/admin/login")})
    if location.endswith("/admin/2fa/setup"):
        _, _, page = b.req("GET", "/admin/2fa/setup")
        secret = re.search(r'class="secret">([^<]+)<', page).group(1).replace(" ", "")
        step = int(time.time()) // 30
        b.req("POST", "/admin/2fa/setup", {"code": totp(secret, step), "_csrf": re.search(r'name="_csrf" value="([^"]+)"', page).group(1)})
        with open(saved, "w") as f:
            f.write(f"{secret} {step}\n")
    if not os.path.exists(saved):
        print("note: the admin already has 2FA from another run; admin page loads skipped (use a fresh stack)", file=sys.stderr)
        return ""
    secret, last = open(saved).read().split()
    while int(time.time()) // 30 <= int(last):    # each code is accepted once: wait for the next one
        time.sleep(1)
    step = int(time.time()) // 30
    b = Browser()
    _, location, _ = b.req("POST", "/admin/login", {"username": ENV.get("ADMIN_USERNAME", "admin"), "password": ENV["ADMIN_PASSWORD"],
                                                    "code": totp(secret, step), "_csrf": b.csrf("/admin/login")})
    with open(saved, "w") as f:
        f.write(f"{secret} {step}\n")
    if not location.endswith("/admin"):
        print(f"note: admin sign-in failed ({location}); admin page loads skipped", file=sys.stderr)
        return ""
    return b.cookie()


os.makedirs(OUT, exist_ok=True)
result = {"wallets": wallets(COUNT), "adminCookie": admin_cookie() if os.environ.get("PREPARE_ADMIN", "1") != "0" else ""}
with open(os.path.join(OUT, "wallets.json"), "w") as f:
    json.dump(result, f)
print(f"{COUNT} wallets, admin {'signed in' if result['adminCookie'] else 'skipped'}: {os.path.join(OUT, 'wallets.json')}")
