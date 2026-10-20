"""Wallet (PAM) certification: checks a casino's wallet against the game's PAM contract (README "PAM API contract":
the signing, the endpoints and rules R1.1-R1.7) before the game is connected to it. For the casino's wallet team, on
their test environment; Python standard library only.

Usage:
  PAM_API_KEY=... PAM_SECRET=... python3 tests/pam_certify.py --url https://wallet.test.casino/plinko --token <launch token>
      [--token2 <second launch token of the same player>] [--expired-token <token issued over 24 hours ago>]
      [--save run.json] [--repeat-from <run.json of an earlier run>]

  --token          a fresh launch token of a test player with at least 1.00 on the account (it is used up: R1.7)
  --token2         another fresh one of the same player, for the "used only once" check when --token is spent
  --expired-token  a token issued more than 24 hours ago (R1.7); without it that check is skipped
  --save           keeps this run's first bet so a later run can check it is still remembered (R1.6)
  --repeat-from    an earlier run's --save file: its bet sent again must still be recognised as a repeat (R1.6)

Safe on a test account: every amount is 0.01, every transaction and round id is new and starts with "certify-", and
the run gives back what it takes (bets are paid back or refunded), so the balance ends where it started. It prints
PASS or FAIL per check and exits 1 if any check failed. The game's own mock wallet passes it:
  PAM_API_KEY=... PAM_SECRET=... python3 tests/pam_certify.py --url http://127.0.0.1:8081/mock-pam --token <token>
(a test stack from scripts/test-env.sh; key and secret from its test.env; a token from Admin -> Mock PAM)."""
import argparse, hashlib, hmac, json, os, sys, time, urllib.error, urllib.request, uuid
from decimal import Decimal

parser = argparse.ArgumentParser(description="Check a wallet against the Plinko PAM contract (R1.1-R1.7 and signing).")
parser.add_argument("--url", required=True, help="the wallet's base URL (PAM_BASE_URL)")
parser.add_argument("--token", required=True, help="a fresh launch token of a test player")
parser.add_argument("--token2", help="another fresh launch token of the same player")
parser.add_argument("--expired-token", help="a launch token issued more than 24 hours ago")
parser.add_argument("--save", help="file to keep this run's first bet in (for --repeat-from later)")
parser.add_argument("--repeat-from", help="an earlier run's --save file (R1.6)")
parser.add_argument("--timeout", type=float, default=10, help="seconds per call (the game's default PAM timeout is 10)")
args = parser.parse_args()
API_KEY = os.environ.get("PAM_API_KEY") or sys.exit("set PAM_API_KEY (the key the game sends as X-Api-Key)")
SECRET = os.environ.get("PAM_SECRET") or sys.exit("set PAM_SECRET (the shared signing secret)")
BASE = args.url.rstrip("/")
if not BASE.startswith("https://"):
    print(f"note: {BASE} is not https; the game refuses that unless PAM_ALLOW_HTTP=true (a wallet on the same private network)")

RUN = "certify-" + uuid.uuid4().hex[:12]
AMOUNT = Decimal("0.01")
results = []


def check(rule, name, ok, detail=""):
    results.append(bool(ok))
    print(f"{'PASS' if ok else 'FAIL'} {rule:6} {name}" + (f"  [{detail}]" if detail and not ok else ""))


def sign(body):
    return hmac.new(SECRET.encode(), body, hashlib.sha256).hexdigest()


def call(endpoint, payload, signature=None, api_key=None):
    """(HTTP status, answer as a dict, answer signed correctly?) for one wallet call, signed as the game signs it."""
    body = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json", "X-Api-Key": API_KEY if api_key is None else api_key,
               "X-Signature": sign(body) if signature is None else signature, "X-Request-Id": RUN}
    request = urllib.request.Request(f"{BASE}/{endpoint}", data=body, method="POST", headers=headers)
    try:
        resp = urllib.request.urlopen(request, timeout=args.timeout)
        status, raw, sig = resp.status, resp.read(), resp.headers.get("X-Signature")
    except urllib.error.HTTPError as e:
        status, raw, sig = e.code, e.read(), e.headers.get("X-Signature")
    except OSError as e:
        sys.exit(f"cannot reach {BASE}/{endpoint}: {e}")
    try:
        answer = json.loads(raw or b"{}")
    except ValueError:
        answer = {"unreadable": raw[:200].decode(errors="replace")}
    return status, answer, sig is not None and hmac.compare_digest(sig, sign(raw))


def ident(kind):
    return f"{RUN}-{kind}"


def money(answer, field="balance"):
    try:
        return Decimal(str(answer[field]))
    except (KeyError, ArithmeticError, TypeError):
        return None


print(f"wallet {BASE}, run {RUN}")

# ---------------------------------------------------------------- authenticate, balance, signing
status, player, signed = call("authenticate", {"token": args.token})
check("auth", "authenticate: 200 with playerId, currency and balance", status == 200 and player.get("playerId") and player.get("currency")
      and money(player) is not None, (status, player))
if status != 200:
    sys.exit("cannot continue without a working launch token (--token)")
check("sign", "authenticate's answer is signed (X-Signature over the body)", signed)
PID, CUR, TOKEN = player["playerId"], player["currency"], args.token
start = money(player)
if start < Decimal("1.00"):
    sys.exit(f"the test player needs at least 1.00 {CUR}, has {start}")


def balance():
    s, a, sig = call("balance", {"playerId": PID, "token": TOKEN})
    return money(a) if s == 200 else None, s, a, sig


now, s, a, sig = balance()
check("api", "balance: 200 with balance and currency, the same as authenticate's", s == 200 and now == start and a.get("currency") == CUR, (s, a))
check("sign", "balance's answer is signed", sig)

s, a, _ = call("balance", {"playerId": PID, "token": TOKEN}, signature="00" * 32)
check("sign", "a request with a wrong X-Signature is refused (4xx)", 400 <= s < 500, s)
s, a, _ = call("balance", {"playerId": PID, "token": TOKEN}, api_key="wrong-" + RUN)
check("sign", "a request with a wrong X-Api-Key is refused (4xx)", 400 <= s < 500, s)


def bet(kind, round_id, amount=AMOUNT, tx=None, endpoint="debit", **change):
    payload = {"playerId": PID, "token": TOKEN, "transactionId": tx or ident(kind), "roundId": round_id, "amount": amount,
               "currency": CUR, "gameId": "plinko"}
    payload.update(change)
    payload["amount"] = float(payload["amount"]) if isinstance(payload["amount"], Decimal) else payload["amount"]
    return payload, call(endpoint, payload)


def rollback(tx, original, round_id):
    return call("rollback", {"playerId": PID, "token": TOKEN, "transactionId": tx, "originalTransactionId": original, "roundId": round_id})


before = balance()[0]
payload = {"playerId": PID, "token": TOKEN, "transactionId": ident("forged"), "roundId": ident("r-forged"), "amount": 0.01,
           "currency": CUR, "gameId": "plinko"}
s, _, _ = call("debit", payload, signature="00" * 32)
check("sign", "a debit with a wrong signature is refused and takes nothing", 400 <= s < 500 and balance()[0] == before, s)

# ---------------------------------------------------------------- R1.1 repeats: the original answer, money moved once
r1 = ident("r1")
first_bet, (s, a, sig) = bet("d1", r1)
after_bet = balance()[0]
check("api", "debit 0.01: 200, signed, echoes the transactionId, balance down by 0.01",
      s == 200 and sig and a.get("transactionId") == first_bet["transactionId"] and money(a) == start - AMOUNT and after_bet == start - AMOUNT,
      (s, a, after_bet))
s, a, sig = call("debit", first_bet)
check("R1.1", "the same debit again: 200 with the original answer, nothing taken twice",
      s == 200 and sig and a.get("transactionId") == first_bet["transactionId"] and money(a) == start - AMOUNT and balance()[0] == after_bet,
      (s, a))

# ---------------------------------------------------------------- R1.2 the same id with other contents
for what, change in (("amount", {"amount": 0.02}), ("round", {"roundId": ident("r1-other")}), ("type (as a credit)", {"_endpoint": "credit"})):
    endpoint = change.pop("_endpoint", "debit")
    _, (s, a, _) = bet("d1", r1, endpoint=endpoint, **change)
    check("R1.2", f"the bet's id reused with another {what}: 409 TRANSACTION_MISMATCH, no money moved",
          s == 409 and a.get("code") == "TRANSACTION_MISMATCH" and balance()[0] == after_bet, (s, a))

# the round is paid: a credit of 0.01 (the bet back), and repeated
credit, (s, a, sig) = bet("c1", r1, endpoint="credit")
paid = balance()[0]
check("api", "credit 0.01: 200, signed, echoes the transactionId, balance up by 0.01",
      s == 200 and sig and a.get("transactionId") == credit["transactionId"] and paid == start, (s, a, paid))
s, a, _ = call("credit", credit)
check("R1.1", "the same credit again: 200 with the original answer, paid once", s == 200 and money(a) == start and balance()[0] == paid, (s, a))

# ---------------------------------------------------------------- R1.5 a paid round's bet can't be refunded
s, a, _ = rollback(ident("rb1"), first_bet["transactionId"], r1)
check("R1.5", "a rollback of a paid round's bet: 409 ROUND_SETTLED, nothing refunded",
      s == 409 and a.get("code") == "ROUND_SETTLED" and balance()[0] == paid, (s, a))

# ---------------------------------------------------------------- R1.3 / R1.4 a rollback before its debit
r2 = ident("r2")
s, a, sig = rollback(ident("rb2"), ident("d2"), r2)
check("R1.3", "a rollback of a debit the wallet never saw: 200, signed, echoes the id, nothing refunded",
      s == 200 and sig and a.get("transactionId") == ident("rb2") and balance()[0] == paid, (s, a))
_, (s, a, _) = bet("d2", r2)
check("R1.4", "that debit arriving after its rollback: 409 TRANSACTION_ROLLED_BACK, nothing taken",
      s == 409 and a.get("code") == "TRANSACTION_ROLLED_BACK" and balance()[0] == paid, (s, a))

# ---------------------------------------------------------------- R1.5 a refund happens once
r3 = ident("r3")
bet("d3", r3)
taken = balance()[0]
s, a, sig = rollback(ident("rb3"), ident("d3"), r3)
check("R1.5", "a rollback of an unpaid round's bet: 200, signed, the 0.01 refunded",
      s == 200 and sig and a.get("transactionId") == ident("rb3") and taken == paid - AMOUNT and balance()[0] == paid, (s, a, taken))
s, a, _ = rollback(ident("rb3"), ident("d3"), r3)
check("R1.5", "the same rollback again: 200 with the original answer, refunded once", s == 200 and balance()[0] == paid, (s, a))
s, a, _ = rollback(ident("rb3b"), ident("d3"), r3)
check("R1.5", "another rollback of the same bet (new id): never a second refund", balance()[0] == paid, (s, a))
s, a, _ = rollback(ident("rb3"), ident("d1"), r3)
check("R1.2", "the rollback's id reused for another bet: 409 TRANSACTION_MISMATCH, no money moved",
      s == 409 and a.get("code") == "TRANSACTION_MISMATCH" and balance()[0] == paid, (s, a))

# ---------------------------------------------------------------- refunds only of this player's bet in this round
r4 = ident("r4")
bet("d4", r4)
s, a, _ = rollback(ident("rb4"), ident("d4"), ident("r4-other"))
check("refund", "a rollback naming another round than its bet's: nothing refunded", balance()[0] == paid - AMOUNT, (s, a))
bet("c4", r4, endpoint="credit")    # the round paid, so the balance is back where it was

# ---------------------------------------------------------------- errors the game shows to the player
_, (s, a, sig) = bet("big", ident("r5"), amount=Decimal(start) + Decimal("1000000"))
check("errors", "a bet over the balance: 402 or another 4xx with code INSUFFICIENT_FUNDS, signed, nothing taken",
      400 <= s < 500 and a.get("code") == "INSUFFICIENT_FUNDS" and sig and balance()[0] == paid, (s, a))
s, a, sig = call("authenticate", {"token": "certify-unknown-" + uuid.uuid4().hex})
check("errors", "an unknown launch token: 4xx with code INVALID_TOKEN, signed", 400 <= s < 500 and a.get("code") == "INVALID_TOKEN" and sig, (s, a))

# ---------------------------------------------------------------- R1.6 ids remembered
if args.repeat_from:
    earlier = json.load(open(args.repeat_from))
    old = {"playerId": earlier["debit"]["playerId"], "token": earlier["debit"]["token"]}    # the player of that run
    held = call("balance", old)[1].get("balance")
    s, a, _ = call("debit", earlier["debit"])
    now = call("balance", old)[1].get("balance")
    check("R1.6", f"a bet from the run of {earlier['time']} sent again: still a repeat (200, nothing taken)",
          s == 200 and a.get("transactionId") == earlier["debit"]["transactionId"] and (held is None or now == held), (s, a, held, now))
else:
    print("SKIP R1.6   ids remembered long enough: run again later with --repeat-from <file of --save> (e.g. after 31 days)")
if args.save:
    with open(args.save, "w") as f:
        json.dump({"time": time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), "debit": first_bet}, f)

# ---------------------------------------------------------------- transaction list (optional)
s, a, sig = call("transactions", {"gameId": "plinko", "from": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 3600)),
                                  "to": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 60)), "after": None, "limit": 500})
if s == 404:
    print("SKIP list   POST /transactions is not offered (optional; Finance -> Check against the wallet needs it or an export)")
else:
    ids = set()
    while s == 200 and isinstance(a.get("transactions"), list):
        ids |= {t.get("transactionId") for t in a["transactions"]}
        if not a.get("next"):
            break
        s, a, sig = call("transactions", {"gameId": "plinko", "from": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 3600)),
                                          "to": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 60)), "after": a["next"], "limit": 500})
    check("list", "transactions: signed pages that list this run's bets, payment and refunds", sig and {ident("d1"), ident("c1"), ident("rb3")} <= ids,
          (s, sorted(i for i in ids if i and i.startswith(RUN))))

# ---------------------------------------------------------------- R1.7 launch tokens
s, a, _ = call("authenticate", {"token": args.token})
# the game also refuses a launch token used before, but only for the 31 days it keeps them: after that, only the wallet does
check("R1.7", "a used launch token is refused (4xx INVALID_TOKEN)", 400 <= s < 500 and a.get("code") == "INVALID_TOKEN", (s, a))
if args.token2:
    s, a, _ = call("authenticate", {"token": args.token2})
    check("R1.7", "a second, unused launch token of the player still works", s == 200 and a.get("playerId") == PID, (s, a))
if args.expired_token:
    s, a, _ = call("authenticate", {"token": args.expired_token})
    check("R1.7", "a launch token issued over 24 hours ago is refused (4xx INVALID_TOKEN)", 400 <= s < 500 and a.get("code") == "INVALID_TOKEN", (s, a))
else:
    print("SKIP R1.7   expiry after 24 hours: pass --expired-token <a token issued over 24 hours ago>")

end = balance()[0]
check("money", f"the balance ends where it started ({start} {CUR})", end == start, end)
failed = results.count(False)
print(f"\n{len(results) - failed}/{len(results)} checks passed" + ("" if not failed else f", {failed} FAILED") + f" (transaction ids start with {RUN})")
sys.exit(1 if failed else 0)
