"""End-to-end and attack tests against a running test stack: docker compose -p plinkotest --env-file <test.env> up -d
Usage: python3 tests/security_e2e.py <test.env> [pytest options, e.g. -k settlements]
(the env file must have MOCK_PAM_ENABLED=true, COOKIE_SECURE=false; scripts/test-env.sh makes one)

The suite itself is tests/e2e (pytest, one module per area); this only runs it, so older commands keep working:
    python3 -m pytest tests/e2e --env-file <test.env> -q -rA"""
import os, subprocess, sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
tests = os.path.join(os.path.dirname(os.path.abspath(__file__)), "e2e")
sys.exit(subprocess.call([sys.executable, "-m", "pytest", tests, "--env-file", sys.argv[1], "-q", "-rA", *sys.argv[2:]]))
