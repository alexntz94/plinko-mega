"""The money path under wallet failures (review 5.1): the mock PAM misbehaves on request (timeouts, errors, bad or
missing signatures, lost answers, slow answers) and every case must end with the wallet exactly right."""
import threading, time
from decimal import Decimal

from conftest import SMALL, api, fault, fresh_token, last_round, mock_balance, pam_answer, pam_call, pam_rows, retry, sql


def test_debit_failures(check, faulty):
    for kind, case in (("timeout", "1"), ("error", "2")):
        before = mock_balance(faulty.pid)
        fault("debit", kind)
        status, resp = api("/api/game/play", SMALL, faulty.sid)
        rid, st = last_round()
        check(f"5.1 #{case} debit {kind}: WALLET_UNAVAILABLE, bet rolled back, balance unchanged",
              status == 503 and resp.get("code") == "WALLET_UNAVAILABLE" and st == "CANCELLED" and mock_balance(faulty.pid) == before,
              (status, resp, st, before, mock_balance(faulty.pid)))
        time.sleep(1)

    before = mock_balance(faulty.pid)
    status, resp = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 100, "balls": 100}, faulty.sid)
    rid, st = last_round()
    check("5.1 #3 insufficient funds: refused, no rollback, no outcome drawn",
          status == 402 and st == "REJECTED" and pam_rows(rid, "ROLLBACK") == 0
          and sql(f"SELECT signature IS NULL AND slots IS NULL FROM game_round WHERE id='{rid}'")[1] == "1" and mock_balance(faulty.pid) == before,
          (status, st))


def test_credit_failures(check, faulty, admin):
    fpid, fsid = faulty.pid, faulty.sid
    before = mock_balance(fpid)
    fault("credit", "timeout")
    status, resp = api("/api/game/play", SMALL, fsid)
    rid, st = last_round()
    check("5.1 #4 credit times out: the round waits as CREDIT_PENDING", status == 200 and st == "CREDIT_PENDING", (status, st))
    st = retry(rid)
    win = Decimal(sql(f"SELECT total_win FROM game_round WHERE id='{rid}'")[1])
    check("5.1 #4 the retry pays the win exactly once, with the same id", st == "COMPLETED" and pam_rows(rid, "CREDIT") == 1
          and mock_balance(fpid) == before - 1 + win, (st, pam_rows(rid, "CREDIT"), before, win, mock_balance(fpid)))
    status, resumed = api("/api/game/resume", {}, fsid)
    check("5.1 #4 the balance after resume is the wallet's", status == 200 and Decimal(str(resumed.get("balance"))) == mock_balance(fpid),
          (status, resumed.get("balance")))

    before = mock_balance(fpid)
    fault("credit", "error", times=10)
    status, resp = api("/api/game/play", SMALL, fsid)
    rid, st = last_round()
    for _ in range(9):
        retry(rid)
    attempts = int(sql(f"SELECT attempts FROM wallet_transaction WHERE round_id='{rid}' AND type='CREDIT'")[1])
    _, _, page = admin.get("/admin/rounds")
    check("5.1 #5 a credit that keeps failing: retries stop at the limit with an alarm and an audit entry",
          attempts == 10 and "automatic retries have stopped" in page
          and sql(f"SELECT COUNT(*) FROM audit_log WHERE action='ROUND_RETRIES_STOPPED' AND details LIKE '{rid}%'")[1] == "1", attempts)
    check("5.1 #5 ... and nothing was paid meanwhile", pam_rows(rid, "CREDIT") == 0 and mock_balance(fpid) == before - 1)
    st = retry(rid)
    win = Decimal(sql(f"SELECT total_win FROM game_round WHERE id='{rid}'")[1])
    _, _, page = admin.get("/admin/rounds")
    check("5.1 #5 once the wallet works, Retry pays it once and the alarm clears",
          st == "COMPLETED" and pam_rows(rid, "CREDIT") == 1 and mock_balance(fpid) == before - 1 + win
          and "automatic retries have stopped" not in page, (st, mock_balance(fpid)))


def test_refund_failure(check, faulty):
    before = mock_balance(faulty.pid)
    fault("debit", "timeout")
    fault("rollback", "error")
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    rid, st = last_round()
    check("5.1 #6 a refund that fails waits as ROLLBACK_PENDING", status == 503 and st == "ROLLBACK_PENDING", (status, st))
    st = retry(rid)
    check("5.1 #6 ... and the retry refunds it", st == "CANCELLED" and mock_balance(faulty.pid) == before, (st, before, mock_balance(faulty.pid)))


def test_untrusted_answers(check, faulty):
    fpid, fsid = faulty.pid, faulty.sid
    for kind in ("bad-signature", "no-signature", "other-transaction"):
        before = mock_balance(fpid)
        fault("debit", kind)
        status, resp = api("/api/game/play", SMALL, fsid)
        rid, st = last_round()
        check(f"5.1 #7/#8 debit answered with {kind}: not trusted, the bet is refunded",
              status == 503 and st == "CANCELLED" and mock_balance(fpid) == before, (status, st, before, mock_balance(fpid)))
        before = mock_balance(fpid)
        fault("credit", kind)
        status, resp = api("/api/game/play", SMALL, fsid)
        rid, st = last_round()
        st = retry(rid) if st == "CREDIT_PENDING" else st
        win = Decimal(sql(f"SELECT total_win FROM game_round WHERE id='{rid}'")[1] or "0")
        check(f"5.1 #7/#8 credit answered with {kind}: waits, then paid exactly once",
              st == "COMPLETED" and pam_rows(rid, "CREDIT") == 1 and mock_balance(fpid) == before - 1 + win, (st, before, win, mock_balance(fpid)))
    fault("balance", "bad-signature")
    status, resp = api("/api/game/resume", {}, fsid)
    check("5.1 #7 a balance answer with a bad signature is refused", status == 503, (status, resp))
    # the refund answered badly: the debit was applied (its answer lost), the wallet applies the refund too but its answer
    # can't be trusted; the round waits as ROLLBACK_PENDING and the retry, a repeat of the same refund, refunds nothing more
    for kind in ("bad-signature", "no-signature", "other-transaction"):
        before = mock_balance(fpid)
        fault("debit", "lost-answer")
        fault("rollback", kind)
        status, resp = api("/api/game/play", SMALL, fsid)
        rid, st = last_round()
        check(f"5.1 #7/#8 rollback answered with {kind}: not trusted, the round waits as ROLLBACK_PENDING",
              status == 503 and resp.get("code") == "WALLET_UNAVAILABLE" and st == "ROLLBACK_PENDING", (status, resp, st))
        st = retry(rid)
        check(f"5.1 #7/#8 ... the retry closes it, refunded exactly once", st == "CANCELLED" and pam_rows(rid, "ROLLBACK") == 1
              and mock_balance(fpid) == before, (st, pam_rows(rid, "ROLLBACK"), before, mock_balance(fpid)))


def test_slow_wallet(check, faulty):
    fpid, fsid = faulty.pid, faulty.sid
    debits = int(sql(f"SELECT COUNT(*) FROM mock_transaction WHERE player_id='{fpid}' AND type='DEBIT'")[1])
    previous, _ = last_round()
    fault("debit", "slow")
    slow = {}
    first = threading.Thread(target=lambda: slow.update(answer=api("/api/game/play", SMALL, fsid)))
    first.start()
    for _ in range(100):     # the slow round holds the player's round lock once its row exists
        if last_round()[0] != previous:
            break
        time.sleep(0.05)
    second = api("/api/game/play", SMALL, fsid)
    first.join()
    status, resp = slow.get("answer", (None, None))
    rid, st = last_round()
    check("5.1 #9 a second play while the wallet is slow is refused: the round lock holds (409 ROUND_IN_PROGRESS)",
          second[0] == 409 and second[1].get("code") == "ROUND_IN_PROGRESS", second)
    check("5.1 #9 a slow wallet (just under the timeout): one bet, round completes", status == 200 and st == "COMPLETED"
          and pam_rows(rid, "DEBIT") == 1
          and int(sql(f"SELECT COUNT(*) FROM mock_transaction WHERE player_id='{fpid}' AND type='DEBIT'")[1]) == debits + 1, (status, st))
    status, _ = api("/api/game/play", SMALL, fsid)
    check("5.1 #9 ... and the player's round lock was released", status == 200, status)


def test_negative_balance_and_expired_token(check, faulty):
    fpid = faulty.pid
    sql(f"UPDATE mock_wallet SET balance=-5 WHERE player_id='{fpid}'")
    status, resp = api("/api/game/session", {"token": fresh_token(fpid)})
    check("5.1 #11 a wallet reporting a negative balance is refused before any money moves", status == 502, (status, resp))
    sql(f"UPDATE mock_wallet SET balance=1000 WHERE player_id='{fpid}'")
    faulty.new_session()

    before = mock_balance(fpid)
    fault("credit", "error")
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    rid, st = last_round()
    fresh_token(fpid)      # the player's PAM token changes (expired); the credit still goes by player id
    st = retry(rid)
    win = Decimal(sql(f"SELECT total_win FROM game_round WHERE id='{rid}'")[1])
    check("5.1 #12 a credit after the token expired is paid by player id", st == "COMPLETED" and mock_balance(fpid) == before - 1 + win, st)
    faulty.new_session()


def test_lost_and_late_debits(check, faulty):
    fpid, fsid = faulty.pid, faulty.sid
    before = mock_balance(fpid)
    fault("debit", "lost-answer")
    status, resp = api("/api/game/play", SMALL, fsid)
    rid, st = last_round()
    debit_id = sql(f"SELECT id FROM wallet_transaction WHERE round_id='{rid}' AND type='DEBIT'")[1]
    check("5.1 #13 a debit applied but its answer lost: rolled back, refunded once", status == 503 and st == "CANCELLED"
          and mock_balance(fpid) == before, (status, st, before, mock_balance(fpid)))
    code, answer = pam_answer("debit", {"playerId": fpid, "transactionId": debit_id, "roundId": rid, "amount": 1, "currency": "USD"})
    # this debit was applied before its rollback, so its late repeat is a repeat (R1.1: 200 with the original answer, marked
    # "repeated", no money moved), not a debit arriving after its rollback (R1.4, 409: case #14 below)
    check("5.1 #13 ... its late repeat never takes the bet again: answered as a repeat (R1.1), no money moved",
          code == 200 and answer.get("repeated") is True and answer.get("transactionId") == debit_id and mock_balance(fpid) == before,
          (code, answer, mock_balance(fpid)))
    check("5.1 #13 ... and the wallet holds exactly one debit and one refund for the round",
          pam_rows(rid, "DEBIT") == 1 and pam_rows(rid, "ROLLBACK") == 1, (pam_rows(rid, "DEBIT"), pam_rows(rid, "ROLLBACK")))

    before = mock_balance(fpid)
    fault("debit", "timeout")
    status, resp = api("/api/game/play", SMALL, fsid)
    rid, st = last_round()
    debit_id = sql(f"SELECT id FROM wallet_transaction WHERE round_id='{rid}' AND type='DEBIT'")[1]
    check("5.1 #14 a debit that never arrived: rollback accepted, nothing refunded", st == "CANCELLED" and mock_balance(fpid) == before
          and Decimal(sql(f"SELECT amount FROM mock_transaction WHERE round_id='{rid}' AND type='ROLLBACK'")[1]) == 0, st)
    code, answer = pam_answer("debit", {"playerId": fpid, "transactionId": debit_id, "roundId": rid, "amount": 1, "currency": "USD"})
    check("5.1 #14 ... and if it arrives late after all, it is refused (R1.4: 409 TRANSACTION_ROLLED_BACK)",
          code == 409 and answer.get("code") == "TRANSACTION_ROLLED_BACK" and mock_balance(fpid) == before, (code, answer))


def test_reused_transaction_ids(check, faulty):
    fpid = faulty.pid
    before = mock_balance(fpid)
    pam_call("credit", {"playerId": fpid, "transactionId": "mismatch-1", "roundId": "mm", "amount": 2, "currency": "USD"})
    code, answer = pam_answer("credit", {"playerId": fpid, "transactionId": "mismatch-1", "roundId": "mm", "amount": 200, "currency": "USD"})
    same = pam_call("credit", {"playerId": fpid, "transactionId": "mismatch-1", "roundId": "mm", "amount": 2, "currency": "USD"})
    check("5.1 #15 a reused transaction id with another amount is refused (R1.2: 409 TRANSACTION_MISMATCH); a true repeat still answers 200",
          code == 409 and answer.get("code") == "TRANSACTION_MISMATCH" and same == 200 and mock_balance(fpid) == before + 2,
          (code, answer, same, mock_balance(fpid)))

    # 5.2 / R1.2 for refunds: the same rollback id for another bet or another round is refused and moves nothing
    before = mock_balance(fpid)
    pam_call("debit", {"playerId": fpid, "transactionId": "rb-mm-debit", "roundId": "rb-mm", "amount": 3, "currency": "USD"})
    pam_call("debit", {"playerId": fpid, "transactionId": "rb-mm-debit-2", "roundId": "rb-mm", "amount": 4, "currency": "USD"})
    first_rb = pam_call("rollback", {"playerId": fpid, "transactionId": "rb-mm-1", "originalTransactionId": "rb-mm-debit", "roundId": "rb-mm"})
    other_bet = pam_answer("rollback", {"playerId": fpid, "transactionId": "rb-mm-1", "originalTransactionId": "rb-mm-debit-2", "roundId": "rb-mm"})
    other_round = pam_answer("rollback", {"playerId": fpid, "transactionId": "rb-mm-1", "originalTransactionId": "rb-mm-debit", "roundId": "rb-other"})
    repeat_rb = pam_answer("rollback", {"playerId": fpid, "transactionId": "rb-mm-1", "originalTransactionId": "rb-mm-debit", "roundId": "rb-mm"})
    check("5.2 R1.2 a rollback id reused for another bet is refused: 409 TRANSACTION_MISMATCH",
          first_rb == 200 and other_bet[0] == 409 and other_bet[1].get("code") == "TRANSACTION_MISMATCH", (first_rb, other_bet))
    check("5.2 R1.2 ... and for another round too", other_round[0] == 409 and other_round[1].get("code") == "TRANSACTION_MISMATCH", other_round)
    check("5.2 R1.2 ... no money moved: only the first refund counts; a true repeat answers 200 as a repeat",
          repeat_rb[0] == 200 and repeat_rb[1].get("repeated") is True and mock_balance(fpid) == before - 4
          and sql("SELECT COUNT(*) FROM mock_transaction WHERE transaction_id='rb-mm-1'")[1] == "1", (repeat_rb, before, mock_balance(fpid)))


def test_refund_already_settled(check, faulty, admin):
    fault("debit", "timeout")
    fault("rollback", "error")
    status, resp = api("/api/game/play", SMALL, faulty.sid)
    rid, st = last_round()
    pam_call("credit", {"playerId": faulty.pid, "transactionId": "settle-" + rid[:8], "roundId": rid, "amount": 0, "currency": "USD"})
    st = retry(rid)
    check("5.1 #16 a refund the wallet calls already paid (ROUND_SETTLED): stops, goes to review",
          st == "TAMPERED" and sql(f"SELECT COUNT(*) FROM audit_log WHERE action='ROUND_TAMPERED' AND details LIKE '{rid}%ROUND_SETTLED%'")[1] == "1", st)
    _, _, html = admin.get("/admin")
    check("5.1 #16 ... the dashboard raises it among the blocked rounds to settle", "Blocked rounds to settle" in html and rid[:8] in html, rid)
    _, _, html = admin.get(f"/admin/rounds/{rid}")
    check("5.1 #16 ... and the round's page says why", "ROUND_SETTLED" in html and "Settle with the player" in html, rid)
