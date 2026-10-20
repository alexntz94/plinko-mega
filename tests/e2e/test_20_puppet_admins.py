"""No puppet admins: a new admin, a promotion to admin and an admin's 2FA reset all need an independent second admin."""
from conftest import STATE, Account, enroll, full_login, sql


def test_no_puppet_admins(check, admin, second_admin, viewer):
    second_admin.post_form("/admin/users", {"username": "admin3", "password": "Third-Admin-Pass-1", "role": "ADMIN"}, "/admin/users")
    check("creating an admin with another admin around needs approval",
          sql("SELECT COUNT(*) FROM admin_user WHERE username='admin3'")[1] == "0"
          and sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1] == "1")
    cid = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_CREATE' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{cid}/approve", {}, "/admin/changes")
    check("creator cannot approve their own new admin", sql("SELECT COUNT(*) FROM admin_user WHERE username='admin3'")[1] == "0")
    admin.post_form(f"/admin/changes/{cid}/approve", {}, "/admin/changes")
    check("second admin's approval creates it", sql("SELECT COUNT(*) FROM admin_user WHERE username='admin3'")[1] == "1")
    admin.post_form("/admin/users/viewer1/role", {"role": "ADMIN"}, "/admin/users")
    check("promoting to admin needs approval", sql("SELECT role FROM admin_user WHERE username='viewer1'")[1] == "VIEWER")
    pid_role = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_ROLE' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{pid_role}/reject", {}, "/admin/changes")
    admin.post_form("/admin/users/admin2/reset-2fa", {}, "/admin/users")
    check("resetting an admin's 2FA with no independent approver is refused",
          sql("SELECT totp_secret IS NOT NULL FROM admin_user WHERE username='admin2'")[1] == "1"
          and sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_2FA_RESET' AND target='admin2'")[1] == "0")
    secret4, step4, _ = enroll("admin3", "Third-Admin-Pass-1")
    session, location, step4, password = full_login("admin3", "Third-Admin-Pass-1", secret4, step4)
    STATE["admin3"] = admin3 = Account("admin3", password, secret4, step4, session)
    admin.post_form("/admin/users/admin2/reset-2fa", {}, "/admin/users")
    rid = sql("SELECT MAX(id) FROM change_request WHERE type='ADMIN_2FA_RESET' AND status='PENDING'")[1]
    check("admin 2FA reset becomes a proposal once an approver exists", rid.isdigit(), rid)
    second_admin.post_form(f"/admin/changes/{rid}/approve", {}, "/admin/changes")
    check("an admin cannot approve the reset of their own 2FA", sql(f"SELECT status FROM change_request WHERE id={rid}")[1] == "PENDING")
    admin3.post_form(f"/admin/changes/{rid}/approve", {}, "/admin/changes")
    check("independent admin approves the 2FA reset", sql("SELECT totp_secret IS NULL FROM admin_user WHERE username='admin2'")[1] == "1")
