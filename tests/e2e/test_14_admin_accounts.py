"""Admin accounts and approvals: viewer limits, accounts edited in the database, sessions ended by account changes,
four-eyes for operators and admins, no puppet approvals, Hold & Drop by role."""
from conftest import FAIR, STATE, Account, api, app, enroll, fresh_token, full_login, sql


def test_viewer_permissions(check, viewer):
    status, _, _ = viewer.post_form("/admin/odds/propose", FAIR, "/admin/odds?rows=8")
    check("viewer cannot propose odds", status == 403 or "denied" in str(status), status)
    status, _, _ = viewer.get("/admin/users")
    check("viewer cannot open admin management", status == 403, status)
    status, _, _ = viewer.post_form("/admin/settings/game-enabled", {"enabled": "false", "reason": "x"}, "/admin/settings")
    check("viewer cannot stop the game", status == 403, status)


def test_accounts_edited_in_database(check, admin, viewer):
    app("UPDATE admin_user SET role='ADMIN' WHERE username='viewer1'")
    _, _, location = viewer.login()
    check("an account promoted in the database cannot sign in", "error" in location, location)
    _, _, html = admin.get("/admin")
    check("admin panel raises an alarm for the edited account", "Admin account viewer1 was changed outside" in html)
    app("UPDATE admin_user SET role='VIEWER' WHERE username='viewer1'")

    # a genuine old copy of the account put back after the account changed is refused
    cols = "password_hash, role, enabled, IFNULL(totp_secret,''), IFNULL(sponsor,''), security_stamp, integrity"
    old_row = sql(f"SELECT {cols} FROM admin_user WHERE username='viewer1'")[1].split("\t")
    admin.post_form("/admin/users/viewer1/disable", {}, "/admin/users")
    admin.post_form("/admin/users/viewer1/enable", {}, "/admin/users")
    new_row = sql(f"SELECT {cols} FROM admin_user WHERE username='viewer1'")[1].split("\t")

    def put_row(r):
        app(f"UPDATE admin_user SET password_hash='{r[0]}', role='{r[1]}', enabled={r[2]}, totp_secret=NULLIF('{r[3]}',''), "
            f"sponsor=NULLIF('{r[4]}',''), security_stamp='{r[5]}', integrity='{r[6]}' WHERE username='viewer1'")
    put_row(old_row)
    _, _, location = viewer.login()
    check("an old signed copy of an account put back is refused", "error" in location, location)
    put_row(new_row)

    # sessions end as soon as the account changes
    viewer.session, _, viewer.step, _ = full_login("viewer1", viewer.password, viewer.secret, viewer.step)
    status, _, _ = viewer.get("/admin")
    check("viewer session works", status == 200, status)
    admin.post_form("/admin/users/viewer1/disable", {}, "/admin/users")
    status, headers, _ = viewer.get("/admin")
    check("disabling an account ends its open session", status == 302 and "expired" in headers.get("Location", ""), (status, headers.get("Location")))
    admin.post_form("/admin/users/viewer1/enable", {}, "/admin/users")


def test_operators_need_approval(check, admin, second_admin, tester):
    # with two enrolled admins four-eyes is on for good
    check("four-eyes switched on by the second enrolled admin", sql("SELECT COUNT(*) FROM audit_log WHERE action='FOUR_EYES_ACTIVATED'")[1] == "1")
    admin.post_form("/admin/users", {"username": "ops1", "password": "Operator-One-Pass-1", "role": "OPERATOR"}, "/admin/users")
    check("creating an operator needs approval", sql("SELECT COUNT(*) FROM admin_user WHERE username='ops1'")[1] == "0"
          and sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1] == "1")
    oid = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{oid}/approve", {}, "/admin/changes")
    check("operator created after a second admin approves", sql("SELECT sponsor FROM admin_user WHERE username='ops1'")[1] == "admin")
    # the creator signs in as the new operator (they know its first password) and proposes odds...
    secret, step, _ = enroll("ops1", "Operator-One-Pass-1")
    session, location, step, password = full_login("ops1", "Operator-One-Pass-1", secret, step)
    STATE["ops1"] = ops = Account("ops1", password, secret, step, session)
    ops.post_form("/admin/odds/propose", dict(FAIR, multiplier=["0.2", "0.3", "1.5", "4", "29.5"],
                  probability=["27.4", "43.75", "21.875", "6.19375", "0.78125"]), "/admin/odds?rows=8")
    pup = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS' AND status='PENDING' AND requested_by='ops1'")[1]
    admin.post_form(f"/admin/changes/{pup}/approve", {}, "/admin/changes")
    check("an admin cannot approve changes of an account they created (no puppet approvals)",
          pup.isdigit() and sql(f"SELECT status FROM change_request WHERE id={pup}")[1] == "PENDING", pup)
    admin.post_form(f"/admin/changes/{pup}/reject", {}, "/admin/changes")


def test_hold_and_drop_by_role(check, admin, second_admin, viewer, operator, tester):
    # Hold & Drop (autoplay by holding the drop button): on by default; operators may switch it off, only admins on
    pid = tester.pid
    hold = lambda: api("/api/game/session", {"token": fresh_token(pid)})[1].get("config", {}).get("holdToDrop")
    check("Hold & Drop is on by default and the game is told", hold() is True)
    status, _, _ = viewer.post_form("/admin/settings/features", {}, "/admin/settings")
    check("viewer cannot change Hold & Drop", status == 403 and hold() is True, status)
    operator.post_form("/admin/settings/features", {}, "/admin/settings")
    check("operator switches Hold & Drop off; the game is told", hold() is False
          and sql("SELECT COUNT(*) FROM audit_log WHERE action='GAME_FEATURES' AND username='ops1'")[1] == "1")
    operator.post_form("/admin/settings/features", {"holdToDrop": "true"}, "/admin/settings")
    check("only an admin can switch Hold & Drop back on", hold() is False)
    admin.post_form("/admin/settings/features", {"holdToDrop": "true"}, "/admin/settings")
    hold_change = sql("SELECT MAX(id) FROM change_request WHERE type='FEATURES' AND status='PENDING'")[1]
    check("N14.9 an admin switching Hold & Drop on only proposes it", hold_change.isdigit() and hold() is False, hold_change)
    second_admin.post_form(f"/admin/changes/{hold_change}/approve", {}, "/admin/changes")
    check("admin switches Hold & Drop on again, approved by a second admin", hold() is True)
    status, _ = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 0.1, "balls": 1}, api("/api/game/session", {"token": fresh_token(pid)})[1].get("sessionId"))
    check("the settings stay trusted after these changes (rounds play)", status == 200, status)


def test_admin_changes_need_approval(check, admin, second_admin, operator):
    admin.post_form("/admin/users/admin2/disable", {}, "/admin/users")
    check("disabling the other admin needs approval", sql("SELECT enabled FROM admin_user WHERE username='admin2'")[1] == "1"
          and sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_DISABLE' AND status='PENDING'")[1] == "1")
    did = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_DISABLE' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{did}/reject", {}, "/admin/changes")
    admin.post_form("/admin/users/admin2/role", {"role": "OPERATOR"}, "/admin/users")
    check("demoting the other admin needs approval", sql("SELECT role FROM admin_user WHERE username='admin2'")[1] == "ADMIN")
    dem = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_ROLE' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{dem}/reject", {}, "/admin/changes")
    admin.post_form("/admin/users/ops1/reset-2fa", {}, "/admin/users")
    check("resetting an operator's 2FA needs approval", sql("SELECT totp_secret IS NOT NULL FROM admin_user WHERE username='ops1'")[1] == "1")
    r2 = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_2FA_RESET' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{r2}/reject", {}, "/admin/changes")
