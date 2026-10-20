"""Forgot password: the same answer for real and unknown users, the reset by an admin (a strong temporary password shown
once, then a forced change with the account's own 2FA), and temporary passwords that expire."""
import re, time

from conftest import ADMIN, Browser, sql


def test_forgot_password(check, admin, viewer):
    anon = Browser()
    _, _, html = anon.get("/admin/forgot")
    s1, _, real = anon.req("POST", ADMIN + "/admin/forgot", {"username": "viewer1", "_csrf": anon.csrf(html)})
    anon2 = Browser(); _, _, html = anon2.get("/admin/forgot")
    s2, _, fake = anon2.req("POST", ADMIN + "/admin/forgot", {"username": "nobody-here", "_csrf": anon2.csrf(html)})
    strip = lambda h: re.sub(r'name="_csrf" value="[^"]+"', "", h)
    check("forgot page gives the same answer for real and unknown users", s1 == s2 == 200 and strip(real) == strip(fake))
    check("reset request recorded only for the real account",
          sql("SELECT GROUP_CONCAT(username) FROM password_reset_request WHERE status='PENDING'")[1] == "viewer1")
    _, _, html = admin.get("/admin/users")
    check("admins see the pending request", "Forgot-password requests" in html and "viewer1" in html)

    admin.post_form("/admin/users/viewer1/reset-password", {}, "/admin/users")
    _, _, html = admin.get("/admin/users")
    m = re.search(r'class="secret">([^<]+)<', html)
    temp = m.group(1) if m else ""
    check("reset shows a strong temporary password once", len(temp) == 20, temp and "(hidden)")
    _, _, html = admin.get("/admin/users")
    check("temporary password not shown again", 'class="secret"' not in html)
    check("request closed by the reset", sql("SELECT status FROM password_reset_request WHERE username='viewer1'")[1] == "DONE")
    b, _, location = viewer.login()
    check("old password no longer works", "error" in location, location)
    b, _, location = viewer.login(temp)
    check("temporary password + own TOTP leads only to the change page", location.endswith("/admin/password/change"), location)
    status, _, _ = b.get("/admin")
    check("nothing else reachable with a temporary password", status == 403, status)
    _, _, html = b.get("/admin/password/change")
    b.req("POST", ADMIN + "/admin/password/change", {"currentPassword": temp, "newPassword": temp, "confirmPassword": temp, "_csrf": b.csrf(html)})
    check("cannot keep the temporary password", sql("SELECT must_change_password FROM admin_user WHERE username='viewer1'")[1] == "1")
    viewer.password = "Viewer-Fresh-Pass-2"
    _, _, html = b.get("/admin/password/change")
    b.req("POST", ADMIN + "/admin/password/change",
          {"currentPassword": temp, "newPassword": viewer.password, "confirmPassword": viewer.password, "_csrf": b.csrf(html)})
    b, _, location = viewer.login()
    check("new own password works after the change", location.endswith("/admin"), location)
    viewer.session = b

    time.sleep(10)   # a second reset of the same account within 10 s is taken as a double submit (N14.15)
    admin.post_form("/admin/users/viewer1/reset-password", {}, "/admin/users")
    _, _, html = admin.get("/admin/users")
    temp = re.search(r'class="secret">([^<]+)<', html).group(1)
    viewer.password = temp      # (the lockout area signs in with it, its expiry lifted)
    sql("UPDATE admin_user SET password_expires_at = NOW(3) - INTERVAL 1 MINUTE WHERE username='viewer1'")
    b, _, location = viewer.login(temp)
    check("expired temporary password refused", "error" in location, location)
