"""First admin on a fresh install: admin / admin. With no ADMIN_PASSWORD (scripts/generate-secrets.sh leaves its secret
file empty) the first admin's password is "admin", a temporary one: 2FA first, then a new password. Last area of the
run: it deletes every admin account (and restarts the backend; rotated keys stay, so what was signed with them must
keep verifying)."""
from conftest import ADMIN, ENV, GAME, STATE, enroll, get_raw, login, restart_backend, sql

KEYS_ENV = {"DEMO_MODE": "false"}


def test_first_admin_on_fresh_install(check):
    admin_secret = ENV["SECRETS_DIR"] + "/ADMIN_PASSWORD"
    original_admin_secret = open(admin_secret).read()
    open(admin_secret, "w").close()          # emptied in place: the container sees the same file
    try:
        sql("SET FOREIGN_KEY_CHECKS=0; DELETE FROM admin_user; SET FOREIGN_KEY_CHECKS=1")
        for name in ("admin", "admin2", "admin3", "viewer1", "ops1"):
            STATE.pop(name, None)
        restart_backend(KEYS_ENV)
        check("fresh install: admin / admin is created as a temporary password (must change, expires in 24h)",
              sql("SELECT must_change_password AND password_expires_at BETWEEN UTC_TIMESTAMP() + INTERVAL 23 HOUR AND UTC_TIMESTAMP() + INTERVAL 25 HOUR "
                  "FROM admin_user WHERE username='admin'")[1] == "1")
        b, status, location, _ = login("admin", "admin")
        check("admin / admin opens only 2FA setup first", location.endswith("/admin/2fa/setup"), location)
        code = b.get("/admin/odds")[0]
        check("nothing else is reachable before 2FA", code in (302, 403), code)
        secret0, step0, _ = enroll("admin", "admin")
        b, status, location, step0 = login("admin", "admin", secret0, step0)
        check("after 2FA, admin / admin opens only \"choose a new password\"", location.endswith("/admin/password/change"), location)
        code = b.get("/admin/odds")[0]
        check("nothing else is reachable before the new password", code in (302, 403), code)
        _, _, html = b.get("/admin/password/change")
        b.req("POST", ADMIN + "/admin/password/change", {"currentPassword": "admin", "newPassword": "admin", "confirmPassword": "admin", "_csrf": b.csrf(html)})
        check("the new password can't stay admin", sql("SELECT must_change_password FROM admin_user WHERE username='admin'")[1] == "1")
        _, _, html = b.get("/admin/password/change")
        b.req("POST", ADMIN + "/admin/password/change",
              {"currentPassword": "admin", "newPassword": "Fresh-Admin-Pass-9", "confirmPassword": "Fresh-Admin-Pass-9", "_csrf": b.csrf(html)})
        b, status, location, step0 = login("admin", "admin", secret0, step0)
        check("admin / admin no longer works once changed", "error" in location, location)
        b, status, location, step0 = login("admin", "Fresh-Admin-Pass-9", secret0, step0)
        check("the new password and 2FA open the admin panel", location.endswith("/admin"), location)
    finally:
        with open(admin_secret, "w") as f:
            f.write(original_admin_secret)
    status, _, _ = get_raw(GAME + "/demo")
    check("demo gone again when DEMO_MODE is off", status == 404, status)
