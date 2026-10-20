"""Odds: hard RTP limits, four-eyes approval (the second admin is created here, as on a new install), and the quick
Set RTP tool (one bulk proposal, exact result)."""
from conftest import FAIR, api, fresh_token, second_admin_account, sql


def test_odds_four_eyes(check, admin, tester):
    pid = tester.pid
    rigged = {"rows": "8", "risk": "2", "multiplier": ["0.2", "0.3", "1.5", "4", "29"], "probability": ["0", "0", "0", "0", "100"]}
    requests_before = sql("SELECT COUNT(*) FROM change_request")[1]
    admin.post_form("/admin/odds/propose", rigged, "/admin/odds?rows=8")
    code, out = sql("SELECT COUNT(*) FROM change_request")
    check("rigged odds (2900% RTP) cannot even be proposed", out == requests_before, out)
    top_before = api("/api/game/session", {"token": fresh_token(pid)})[1]["config"]["rows"][0]["prizes"][4][2]
    admin.post_form("/admin/odds/propose", FAIR, "/admin/odds?rows=8")
    cid = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS' AND status='PENDING'")[1]
    check("valid odds change is proposed", cid.isdigit(), cid)
    admin.post_form(f"/admin/changes/{cid}/approve", {"note": "self"}, "/admin/changes")
    check("proposer cannot approve own change", sql(f"SELECT status FROM change_request WHERE id={cid}")[1] == "PENDING")
    status, s2 = api("/api/game/session", {"token": fresh_token(pid)})
    check("odds unchanged before approval", s2["config"]["rows"][0]["prizes"][4][2] == top_before, (top_before, s2["config"]["rows"][0]["prizes"][4]))

    # second admin approves; before that, the first admin can create nothing but the second admin
    admin2 = second_admin_account()
    check("before two-person approval is on, only the second admin can be created", admin2.early_ops == "0")
    check("new admin must replace the creator's password, then gets in", admin2.first_location.endswith("/admin"), admin2.first_location)
    check("2FA secrets are stored encrypted", sql("SELECT COUNT(*) FROM admin_user WHERE totp_secret IS NOT NULL AND totp_secret NOT LIKE 'v1:%'")[1] == "0"
          and sql("SELECT COUNT(*) FROM admin_user WHERE totp_secret LIKE 'v1:%'")[1] == "2")
    check("PAM tokens are stored encrypted", sql("SELECT COUNT(*) FROM game_session WHERE pam_token NOT LIKE 'v1:%'")[1] == "0")
    admin2.post_form(f"/admin/changes/{cid}/approve", {"note": "ok"}, "/admin/changes")
    check("different admin approves; change applied", sql(f"SELECT status FROM change_request WHERE id={cid}")[1] == "APPROVED")
    status, s3 = api("/api/game/session", {"token": fresh_token(pid)})
    check("game config reflects approved odds", s3["config"]["rows"][0]["prizes"][4][2] == 28, s3["config"]["rows"][0]["prizes"][4])


def test_set_rtp(check, admin, second_admin):
    # quick RTP tool: preview, one bulk proposal, four-eyes, exact result
    _, _, html = admin.get("/admin/odds?rows=8&targetRtp=95&scope=all")
    check("Set RTP preview lists all 21 tables", html.count("95.00%") >= 21, html.count("95.00%"))
    _, _, html = admin.get("/admin/odds?rows=8&targetRtp=150&scope=all")
    check("Set RTP refuses targets outside the hard limits", "must be between" in html)
    admin.post_form("/admin/odds/target-rtp", {"targetRtp": "95", "scope": "all", "rows": "8"}, "/admin/odds?rows=8")
    bulk = sql("SELECT MAX(id) FROM change_request WHERE type='ODDS_BULK' AND status='PENDING'")[1]
    check("Set RTP creates one bulk proposal", bulk.isdigit(), bulk)
    admin.post_form(f"/admin/changes/{bulk}/approve", {}, "/admin/changes")
    check("proposer cannot approve own RTP change", sql(f"SELECT status FROM change_request WHERE id={bulk}")[1] == "PENDING")
    second_admin.post_form(f"/admin/changes/{bulk}/approve", {}, "/admin/changes")
    check("second admin approves RTP change", sql(f"SELECT status FROM change_request WHERE id={bulk}")[1] == "APPROVED")
    rtps = sql("SELECT ROUND(SUM(multiplier*probability), 4) FROM odds GROUP BY rows_count, risk")[1].split()
    check("all 21 tables now exactly 95% RTP", len(rtps) == 21 and all(r == "95.0000" for r in rtps), rtps)
