"""5.1 #10: the backend killed mid-round. The bet is taken and the outcome signed and saved, the credit is on its way: the
backend dies. After the restart the round is found and its signed win paid exactly once, with the same transaction id.
The kill waits for the saved outcome, so the round can only end paid: a refund (CANCELLED) here would be a failure."""
import subprocess, threading, time
from decimal import Decimal

from conftest import SMALL, api, fault, last_round, mock_balance, pam_rows, restart_backend, round_status, sql


def test_backend_killed_mid_round(check, faulty):
    restart_backend({})
    ksid = faulty.new_session()
    before_kill = mock_balance(faulty.pid)
    previous, _ = last_round()
    fault("credit", "slow")     # the credit waits 80% of the timeout inside the wallet before anything is applied
    killed = {}

    def doomed_play():
        try:
            killed["answer"] = api("/api/game/play", SMALL, ksid)
        except Exception as e:      # the connection dies with the backend
            killed["answer"] = (None, repr(e))
    t = threading.Thread(target=doomed_play); t.start()
    rid_k, st_k = previous, None
    for _ in range(200):
        rid_k, st_k = last_round()
        if rid_k != previous and st_k == "DEBITED":
            break
        time.sleep(0.05)
    subprocess.run(["docker", "kill", "plinkotest-backend-1"], capture_output=True)
    t.join()
    saved = sql(f"SELECT signature IS NOT NULL AND total_win IS NOT NULL FROM game_round WHERE id='{rid_k}'")[1]
    check("5.1 #10 the backend was killed after the outcome was saved, before the credit was applied",
          rid_k != previous and st_k == "DEBITED" and saved == "1" and pam_rows(rid_k, "CREDIT") == 0
          and mock_balance(faulty.pid) == before_kill - Decimal("1"), (rid_k, st_k, saved, pam_rows(rid_k, "CREDIT")))
    restart_backend({})
    # an interrupted round is taken up once its player's round lock has run out (5 minutes, plus one: so a call still
    # running on another instance is never overtaken)
    for _ in range(110):
        if round_status(rid_k) in ("COMPLETED", "CANCELLED", "TAMPERED"):
            break
        time.sleep(5)
    win_k = Decimal(sql(f"SELECT IFNULL(total_win, 0) FROM game_round WHERE id='{rid_k}'")[1])
    st_after = round_status(rid_k)
    credit_id = sql(f"SELECT id FROM wallet_transaction WHERE round_id='{rid_k}' AND type='CREDIT'")[1]
    check("5.1 #10 ... after the restart its signed win is paid exactly once, with the round's own credit id",
          st_after == "COMPLETED" and pam_rows(rid_k, "CREDIT") == 1 and pam_rows(rid_k, "ROLLBACK") == 0
          and sql(f"SELECT transaction_id FROM mock_transaction WHERE round_id='{rid_k}' AND type='CREDIT'")[1] == credit_id
          and mock_balance(faulty.pid) == before_kill - Decimal("1") + win_k,
          (st_k, st_after, pam_rows(rid_k, "CREDIT"), pam_rows(rid_k, "ROLLBACK"), mock_balance(faulty.pid) - before_kill, win_k))


def test_backend_killed_after_the_first_transaction(check, faulty):
    """5.1 #17 (a): killed after the round and its bet were recorded, while the bet is still on its way to the wallet
    (the mock wallet dies with the backend, so the bet is never applied): after the restart the bet is refunded once
    (a refund of a bet the wallet never saw moves no money, R1.3) and the round is cancelled."""
    restart_backend({})
    ksid = faulty.new_session()
    before = mock_balance(faulty.pid)
    previous, _ = last_round()
    fault("debit", "slow")      # the bet waits inside the wallet before anything is applied
    t = threading.Thread(target=lambda: _quietly(lambda: api("/api/game/play", SMALL, ksid)))
    t.start()
    rid, st = previous, None
    for _ in range(200):
        rid, st = last_round()
        if rid != previous and st == "PENDING":
            break
        time.sleep(0.05)
    subprocess.run(["docker", "kill", "plinkotest-backend-1"], capture_output=True)
    t.join()
    check("5.1 #17 (a) the backend was killed after the first transaction, the bet not applied",
          rid != previous and st == "PENDING" and pam_rows(rid, "DEBIT") == 0 and mock_balance(faulty.pid) == before, (rid, st))
    restart_backend({})
    for _ in range(110):
        if round_status(rid) in ("CANCELLED", "COMPLETED", "TAMPERED", "REJECTED"):
            break
        time.sleep(5)
    check("5.1 #17 (a) ... after the restart the round is cancelled, nothing taken, nothing paid",
          round_status(rid) == "CANCELLED" and pam_rows(rid, "CREDIT") == 0 and mock_balance(faulty.pid) == before,
          (round_status(rid), pam_rows(rid, "CREDIT"), mock_balance(faulty.pid) - before))


def test_backend_killed_after_the_last_transaction(check, faulty):
    """5.1 #17 (c): killed right after a round's last transaction (the win paid and recorded): after the restart and a
    full pass of the background retry, nothing is paid or refunded again."""
    restart_backend({})
    ksid = faulty.new_session()
    before = mock_balance(faulty.pid)
    status, played = api("/api/game/play", SMALL, ksid)
    rid, st = last_round()
    subprocess.run(["docker", "kill", "plinkotest-backend-1"], capture_output=True)
    win = Decimal(sql(f"SELECT IFNULL(total_win, 0) FROM game_round WHERE id='{rid}'")[1])
    check("5.1 #17 (c) the backend was killed after the round's last transaction", status == 200 and st == "COMPLETED", (status, st))
    restart_backend({})
    time.sleep(75)      # the background retry runs every minute
    check("5.1 #17 (c) ... after the restart nothing is paid or refunded again",
          round_status(rid) == "COMPLETED" and pam_rows(rid, "CREDIT") == 1 and pam_rows(rid, "ROLLBACK") == 0
          and mock_balance(faulty.pid) == before - Decimal("1") + win,
          (round_status(rid), pam_rows(rid, "CREDIT"), pam_rows(rid, "ROLLBACK"), mock_balance(faulty.pid) - before, win))


def _quietly(call):
    try:
        call()
    except Exception:       # the connection dies with the backend
        pass
