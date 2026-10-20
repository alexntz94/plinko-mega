"""Fresh mock-wallet launch tokens for the browser checks on the test stack (CI; review 5.7): one per theme for
tests/game_ui.mjs and one for tests/browser_check.mjs, each wallet with enough credit for a round of 100 balls.
Usage: python3 tests/launch_tokens.py <test.env>   — prints TOKENS=theme=token,... and BROWSER_TOKEN=token
(lines for $GITHUB_ENV). Needs the stack from scripts/test-env.sh running as project plinkotest
(or the project named in $PROJECT)."""
import os, subprocess, sys, uuid

ENV = dict(l.split("=", 1) for l in open(sys.argv[1]).read().splitlines() if "=" in l and not l.startswith("#"))
THEMES = sorted(d for d in os.listdir(os.path.join(os.path.dirname(__file__), "..", "game", "public", "themes"))
                if not d.startswith("."))


def wallet():
    token = "ui" + uuid.uuid4().hex[:24]
    p = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD=" + ENV["MYSQL_ROOT_PASSWORD"], os.environ.get("PROJECT", "plinkotest") + "-db-1", "mysql", "-uroot",
                        "plinko", "-e", "INSERT INTO mock_wallet (player_id, username, currency, balance, token, created_at) "
                        f"VALUES ('ui-{token}', 'ui', 'USD', 100000, '{token}', UTC_TIMESTAMP(3))"], capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit("could not make a test wallet: " + p.stderr.strip())
    return token


if not THEMES:
    sys.exit("no themes in game/public/themes")
print("TOKENS=" + ",".join(f"{theme}={wallet()}" for theme in THEMES))
print("BROWSER_TOKEN=" + wallet())
