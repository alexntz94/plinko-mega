"""More approvals (a rejected change revived in the database, accounts without their own 2FA, re-enabling an operator,
the platform currency) and the admin lists: filters, sorting, pages, CSV export, injection attempts."""
from conftest import FAIR, api, app, fresh_token, sql


def test_approvals(check, admin, second_admin, operator):
    # a rejected change set back to PENDING in the database cannot be approved
    admin.post_form("/admin/odds/propose", dict(FAIR, multiplier=["0.2", "0.3", "1.5", "4", "27"],
                    probability=["27.34375", "43.75", "21.875", "6.25", "0.78125"]), "/admin/odds?rows=8")
    rej = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{rej}/reject", {}, "/admin/changes")
    app(f"UPDATE change_request SET status='PENDING', decided_by=NULL, decided_at=NULL WHERE id={rej}")
    second_admin.post_form(f"/admin/changes/{rej}/approve", {}, "/admin/changes")
    check("a rejected change revived in the database cannot be approved", sql("SELECT MAX(multiplier) FROM odds WHERE rows_count=8 AND risk=2")[1] != "27.00")
    app(f"UPDATE change_request SET status='REJECTED' WHERE id={rej}")

    # accounts without their own 2FA and password cannot be reset alone or promoted
    admin.post_form("/admin/users", {"username": "viewer2", "password": "Viewer-Two-Pass-12", "role": "VIEWER"}, "/admin/users")
    admin.post_form("/admin/users/viewer2/reset-password", {}, "/admin/users")
    check("password reset refused for an account without 2FA", sql("SELECT must_change_password FROM admin_user WHERE username='viewer2'")[1] == "1"
          and sql("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_PASSWORD_RESET' AND details LIKE 'viewer2%'")[1] == "0")
    admin.post_form("/admin/users/viewer2/role", {"role": "OPERATOR"}, "/admin/users")
    check("promotion refused before the owner set up their own 2FA and password",
          sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_ROLE' AND target='viewer2'")[1] == "0")
    admin.post_form("/admin/users/ops1/disable", {}, "/admin/users")
    admin.post_form("/admin/users/ops1/enable", {}, "/admin/users")
    check("re-enabling an operator needs approval", sql("SELECT enabled FROM admin_user WHERE username='ops1'")[1] == "0"
          and sql("SELECT COUNT(*) FROM change_request WHERE type='ADMIN_ENABLE' AND status='PENDING'")[1] == "1")


def test_admin_lists(check, admin):
    status, _, html = admin.get("/admin/rounds?risk=2&sort=win&dir=desc&size=25")
    check("rounds list filters, sorts and pages", status == 200 and "Clear filters" in html and 'class="pager"' in html and "of " in html, status)
    status, hd, csv = admin.get("/admin/rounds.csv?risk=2")
    check("rounds CSV export of the filtered list", status == 200 and csv.lstrip("﻿").startswith('"Time (UTC)"') and "text/csv" in hd.get("Content-Type", ""), status)
    check("CSV exports are audited", sql("SELECT COUNT(*) FROM audit_log WHERE action='EXPORT_ROUNDS'")[1] != "0")
    status, _, html = admin.get("/admin/rounds?q=%27%20OR%201%3D1%20--")
    check("list search input can't change the query (no SQL injection)", status == 200 and "No rounds match" in html, status)
    status, _, html = admin.get("/admin/rounds?sort=id;DROP%20TABLE%20x&dir=asc")
    check("unknown sort keys fall back to the default", status == 200, status)
    status, _, html = admin.get("/admin/changes?status=APPROVED&size=25")
    check("approvals history has filters, pages and expandable rows", status == 200 and "expand-row" in html and 'class="pager"' in html, status)
    for page in ["/admin/players", "/admin/transactions", "/admin/audit?action=LOGIN", "/admin/finance", "/admin/odds", "/admin/settings", "/admin/themes", "/admin/users"]:
        status, _, html = admin.get(page)
        check(f"page {page} renders in the new design", status == 200 and 'class="sidebar"' in html, status)


def test_currency_needs_approval(check, admin, second_admin, tester):
    # the platform currency changes only through approval
    admin.post_form("/admin/settings/currency", {"currency": "EUR"}, "/admin/settings")
    check("changing the platform currency needs a second admin", sql("SELECT COUNT(*) FROM change_request WHERE type='CURRENCY' AND status='PENDING'")[1] == "1"
          and api("/api/game/session", {"token": fresh_token(tester.pid)})[0] == 200)
    cur = sql("SELECT MAX(id) FROM change_request WHERE type='CURRENCY' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{cur}/reject", {}, "/admin/changes")
