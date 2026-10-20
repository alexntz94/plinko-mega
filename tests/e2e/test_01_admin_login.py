"""Admin sign-in: password + 2FA in two steps, code replay, lockout counting, the pages an admin sees."""
from conftest import ADMIN, ENV, STATE, Account, Browser, enroll, login, next_step_code, sql, totp


def test_two_step_sign_in(check):
    admin_pw = ENV["ADMIN_PASSWORD"]
    b, status, location, _ = login("admin", "wrong-password")
    check("wrong password rejected", "error" in location)
    secret, step, location = enroll("admin", admin_pw)
    check("2FA enrolment completes and forces re-login", "enrolled" in location, location)
    STATE["admin"] = account = Account("admin", admin_pw, secret, step)
    b, status, location, _ = login("admin", admin_pw)
    check("a correct password alone only leads to the code screen (two-step sign-in)", location.endswith("/admin/login/code"), location)
    status, hd, _ = b.get("/admin")
    check("nothing is reachable before the code is entered", status == 302 and "/admin/login" in hd.get("Location", ""), (status, hd.get("Location")))
    _, _, html = b.get("/admin/login/code")
    code, account.step = next_step_code(secret, account.step)
    status, hd, _ = b.req("POST", ADMIN + "/admin/login", {"username": "admin", "code": code, "_csrf": b.csrf(html)})
    check("step 2 with the code signs in", hd.get("Location", "").endswith("/admin"), hd.get("Location"))
    # three wrong codes send you back to step 1; each counts once toward the lockout
    b3, _, location, _ = login("admin", admin_pw)
    for i in range(3):
        _, _, html = b3.get("/admin/login/code")
        _, hd, _ = b3.req("POST", ADMIN + "/admin/login", {"username": "admin", "code": "000000", "_csrf": b3.csrf(html)})
    check("three wrong codes send you back to step 1", "/admin/login?error" in hd.get("Location", ""), hd.get("Location"))
    check("each wrong code counts once toward the lockout", sql("SELECT failed_attempts FROM admin_user WHERE username='admin'")[1] == "3",
          sql("SELECT failed_attempts FROM admin_user WHERE username='admin'")[1])
    status, hd, _ = Browser().get("/admin/login/code")
    check("the code screen can't be opened without step 1", status == 302, status)
    account.session, status, location = account.login()
    check("login with password + TOTP works", location.endswith("/admin"), location)
    code_used = totp(secret, account.step)
    b2 = Browser(); _, _, h = b2.get("/admin/login")
    s, hd, _ = b2.req("POST", ADMIN + "/admin/login", {"username": "admin", "password": admin_pw, "code": code_used, "_csrf": b2.csrf(h)})
    check("TOTP code replay refused", "error" in hd.get("Location", ""))


def test_admin_pages(check, admin):
    status, _, html = admin.get("/admin")
    check("dashboard renders", status == 200 and "Dashboard" in html, status)
    check("live pages are marked for auto-refresh", 'data-live="dashboard"' in html)
    status, _, html = admin.req("GET", ADMIN + "/admin", headers={"X-Live-Refresh": "1"})
    check("a live refresh is served while the admin is active", status == 200 and 'data-live="dashboard"' in html, status)
    for page in ["/admin/players", "/admin/rounds", "/admin/transactions", "/admin/odds?rows=13", "/admin/changes", "/admin/settings",
                 "/admin/users", "/admin/audit", "/admin/account", "/admin/mock-pam", "/admin/finance"]:
        status, _, html = admin.get(page)
        check(f"page {page} renders", status == 200 and "Whitelabel" not in html, status)
