"""What is exposed where: CSRF and security headers on the admin panel, the public port, the deployment itself."""
import subprocess, urllib.error, urllib.request

from conftest import ADMIN, ENV, GAME, Browser


def test_csrf_and_headers(check, admin):
    status, _, _ = admin.req("POST", ADMIN + "/admin/settings/game-enabled", {"enabled": "false", "reason": "x"})
    check("admin POST without CSRF token refused", status == 403, status)
    _, headers, _ = admin.get("/admin")
    csp = headers.get("Content-Security-Policy", "")
    check("admin CSP blocks inline scripts and framing", "script-src 'self'" in csp and "frame-ancestors 'none'" in csp, csp)
    check("admin X-Frame-Options DENY", headers.get("X-Frame-Options") == "DENY")
    check("admin pages not cached", "no-store" in headers.get("Cache-Control", ""))
    status, headers, _ = Browser().get("/admin/odds")
    check("anonymous admin access redirects to login", status == 302 and "/admin/login" in headers.get("Location", ""))


def test_public_surface(check):
    for path in ["/admin", "/admin/login", "/mock-pam/debit", "/actuator/health", "/api/game/other", "/.env", "/../backend/pom.xml"]:
        r = urllib.request.Request(GAME + path, method="GET")
        try:
            code = urllib.request.urlopen(r, timeout=10).status
        except urllib.error.HTTPError as e:
            code = e.code
        check(f"public port hides {path}", code in (400, 403, 404, 405), code)
    resp = urllib.request.urlopen(GAME + "/", timeout=10)
    check("game page served with CSP", "script-src 'self'" in resp.headers.get("Content-Security-Policy", ""))
    check("nginx version hidden", "nginx/" not in (resp.headers.get("Server") or ""))


def test_deployment(check):
    inspect = subprocess.run(["docker", "inspect", "plinkotest-backend-1", "plinkotest-migrate-1"], capture_output=True, text=True).stdout
    check("no secret value visible in container settings (docker inspect)",
          all(ENV[k] not in inspect for k in ("DB_PASSWORD", "DB_OWNER_PASSWORD", "ROUND_SIGNING_KEY", "PAM_SECRET", "PAM_API_KEY", "ADMIN_PASSWORD")))
    backend_env = subprocess.run(["docker", "inspect", "-f", "{{json .Config.Env}}", "plinkotest-backend-1"], capture_output=True, text=True).stdout
    check("running backend does not hold the database owner's credentials", "DB_OWNER" not in backend_env, backend_env)
    check("migrations ran in their own container and it exited cleanly",
          subprocess.run(["docker", "inspect", "-f", "{{.State.ExitCode}}", "plinkotest-migrate-1"], capture_output=True, text=True).stdout.strip() == "0")
    code = Browser().req("POST", ADMIN + "/api/game/session", b"{}", {"Content-Type": "application/json"})[0]
    check("game API not served on the admin port", code == 404, code)
    code = subprocess.run(["docker", "exec", "plinkotest-web-1", "wget", "-q", "-O", "/dev/null", "-S", "http://backend:8080/admin/login"],
                          capture_output=True, text=True).stderr
    check("admin panel not served on the backend's game port", "404" in code, code[-200:])
