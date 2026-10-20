"""The server's recovery command for a locked-out admin, and the lockout after repeated wrong passwords."""
import re

from conftest import compose, login, sql


def test_recovery_command(check, third_admin):
    p = compose("run", "--rm", "--no-deps", "backend", "--recover-admin=admin3", "--server.port=0")
    m = re.search(r"Temporary password \(valid 24 hours\): (\S+)", p.stdout)
    check("recovery command prints a temporary password", m is not None, p.stdout[-300:] + p.stderr[-300:])
    check("recovery resets 2FA and is audited",
          sql("SELECT totp_secret IS NULL AND must_change_password FROM admin_user WHERE username='admin3'")[1] == "1"
          and sql("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_RECOVERED'")[1] == "1")


def test_brute_force_lockout(check, viewer):
    for i in range(6):
        login("viewer1", "wrong-password-%d" % i)
    sql("UPDATE admin_user SET password_expires_at = NULL, must_change_password = FALSE WHERE username='viewer1'")
    b, status, location = viewer.login()
    check("account locked after repeated failures", "error" in location, location)
