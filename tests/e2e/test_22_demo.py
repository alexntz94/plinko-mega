"""The admin panel through the public port (review 5.4), and the public demo (DEMO_MODE): lobby, single-use demo wallets,
rate limits and the daily cap. Restarts the backend in demo mode (the areas after this one restart it again)."""
import re, time, urllib.parse
from decimal import Decimal

from conftest import ADMIN, GAME, REPO, api, compose, get_raw, post_raw, restart_backend, sql

# the themes the backend knows, read from its own list (a copy here went stale with every new theme)
KNOWN_THEMES = re.findall(r'KNOWN\.put\("([a-z0-9-]+)"',
                          (REPO / "backend/src/main/java/com/plinko/bo/settings/ThemeService.java").read_text())


def test_admin_not_reachable_through_public_port(check):
    # 5.4 tricks to reach the admin panel through the public port: encoded, with parameters, doubled slashes, other case
    for trick in ["/%61dmin", "/admin;x=1", "//admin", "/ADMIN", "/admin/../admin", "/%2e/admin", "/admin%2f", "/admin/%2e%2e/admin"]:
        status, _, body = get_raw(GAME + trick)
        check(f"5.4 the admin panel is not reachable on the public port as {trick}", status in (400, 403, 404) and b"Plinko Admin" not in body, status)
    status, _, _ = get_raw(ADMIN + "/api/game/session")
    check("5.4 the game API is not served on the admin port", status == 404, status)


def test_public_demo(check):
    codes = [get_raw(GAME + path)[0] for path in ["/demo", "/demo/play", "/demo/play?theme=rat-race"]] + [post_raw(GAME + "/demo/play", {})[0]]
    check("no demo on a normal server (/demo and /demo/play are 404)", codes == [404, 404, 404, 404], codes)
    p = compose("run", "--rm", "--no-deps", "-e", "DEMO_MODE=true", "-e", "PAM_BASE_URL=https://pam.example.com/wallet", "backend")
    check("demo mode refuses to start with a real PAM", p.returncode != 0 and "never allowed with a real wallet" in p.stdout + p.stderr,
          (p.stdout + p.stderr)[-300:])

    restart_backend({"DEMO_MODE": "true", "DEMO_CREDITS": "1000", "DEMO_WALLETS_PER_DAY": "2000"})
    stored = sql("SELECT value FROM setting WHERE name='game.themes'")[1]
    # a theme is on unless switched off: on in the saved list, or added after the list was saved (not in "known")
    known = sql("SELECT value FROM setting WHERE name='game.themes.known'")[1]
    on = [t.strip() for t in stored.split(",")] if stored else KNOWN_THEMES
    seen = [t.strip() for t in known.split(",")] if known else on
    enabled = [t for t in KNOWN_THEMES if t in on or t not in seen]
    status, headers, page = get_raw(GAME + "/demo")
    headers_lobby = headers
    text = page.decode("utf-8", "replace")
    listed = re.findall(r'name="theme" value="([a-z0-9-]+)"', text)
    check("demo lobby lists exactly the themes that are switched on", status == 200 and listed == enabled, (status, listed, enabled))
    check("demo lobby has no scripts and a strict CSP", "<script" not in text.lower()
          and "default-src 'none'" in (headers.get("Content-Security-Policy", "") if headers else ""), headers.get("Content-Security-Policy") if headers else status)
    card = re.search(r'property="og:image" content="([^"]+)"', text)
    check("shared demo links show a preview card", card is not None and card.group(1).endswith("/demo-card.jpg")
          and get_raw(GAME + "/demo-card.jpg")[0] == 200, card.group(1) if card else None)
    check("looking at the lobby makes no wallet", sql("SELECT COUNT(*) FROM mock_wallet WHERE player_id LIKE 'demo-%'")[1] == "0")
    status, headers, _ = get_raw(GAME + f"/demo/play?theme={enabled[-1]}")
    check("2.5 a link previewer fetching /demo/play makes no wallet and is sent to the lobby",
          status == 302 and (headers.get("Location", "") if headers else "") == "/demo"
          and sql("SELECT COUNT(*) FROM mock_wallet WHERE player_id LIKE 'demo-%'")[1] == "0", status)
    # every CSP header counts (the browser applies them all): exactly one, and it lets the form go to /demo/play
    csps = (headers_lobby.get_all("Content-Security-Policy") or []) if headers_lobby else []
    check("2.5 the lobby's Play is a form POST its CSP allows", "<form method=\"post\" action=\"/demo/play\">" in text
          and len(csps) == 1 and "form-action 'self'" in csps[0], csps)
    time.sleep(11)
    previews = re.findall(r'src="(/themes/[a-z0-9-]+/preview\.png[^"]*)"', text)
    status, _, _ = get_raw(GAME + previews[0].replace("&amp;", "&")) if previews else (None, None, None)
    check("theme previews load for the lobby", status == 200, status)
    # Cloudflare keeps images for hours: each preview address carries its picture's fingerprint, so a new one shows at once
    check("lobby previews carry a version tag", len(previews) == len(enabled)
          and all(re.search(r"\?v=[0-9a-f]{12}$", src) for src in previews), previews)
    status, headers, _ = post_raw(GAME + "/demo/play", {"theme": enabled[-1]})
    location = headers.get("Location", "") if headers else ""
    check("play sends the visitor to the game with a launch token and the chosen theme",
          status == 302 and location.startswith("/?token=") and location.endswith("&theme=" + enabled[-1]), (status, location))
    demo_token = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("token", [""])[0]
    status, demo_session = api("/api/game/session", {"token": demo_token, "theme": enabled[-1]})
    check("demo session: fake credits, marked as demo, in the chosen theme", status == 200 and demo_session.get("demo") is True
          and Decimal(str(demo_session.get("balance"))) == Decimal("1000") and demo_session.get("theme") == enabled[-1], demo_session)
    status, _ = api("/api/game/session", {"token": demo_token})
    check("demo launch token is single-use", status == 401, status)
    status, rd = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 1}, demo_session.get("sessionId"))
    check("a demo round plays, decided and signed by the server", status == 200
          and sql(f"SELECT signature IS NOT NULL FROM game_round WHERE id='{rd.get('roundId')}'")[1] == "1", rd)
    time.sleep(11)
    off = [t for t in KNOWN_THEMES if t not in enabled]
    for asked in ["evil%26token%3Dx"] + off[:1]:
        status, headers, _ = post_raw(GAME + "/demo/play", {"theme": urllib.parse.unquote(asked)})
        where = headers.get("Location", "") if headers else ""
        check(f"play ignores a theme that is not switched on ({asked})", status == 302 and "theme=" not in where, (status, where))
        time.sleep(11)
    codes = [post_raw(GAME + "/demo/play", {})[0] for _ in range(6)]
    check("making demo wallets is rate-limited per visitor", 429 in codes, codes)
    codes = [get_raw(GAME + "/demo")[0] for _ in range(5)]
    check("the lobby itself stays open", codes == [200] * 5, codes)
    status, _, _ = get_raw(ADMIN + "/api/demo/play")
    check("demo is not served on the admin port", status == 404, status)
    sql("INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at) "
        "SELECT CONCAT('demo-cap-', seq), 'Demo player', 'USD', 1000, CONCAT('cap-', seq), UTC_TIMESTAMP(3) FROM "
        "(SELECT 1 seq UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8) s")
    restart_backend({"DEMO_MODE": "true", "DEMO_WALLETS_PER_DAY": "8"})
    status, _, body = post_raw(GAME + "/demo/play", {})
    check("daily cap on new demo wallets", status == 503 and b"busy" in body, (status, body[:80]))
