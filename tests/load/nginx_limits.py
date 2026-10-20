"""nginx's limits, tested from outside as configured in deploy/nginx/nginx.conf (review 5.6 #8). Python standard library
only. Run against a test stack's public port (nothing else should be talking to it from this address meanwhile):

    python3 tests/load/nginx_limits.py [http://127.0.0.1:8080]    (or WEB_URL)

  body size    a play request over client_max_body_size (16k) gets 413 at once; one just under it does not
  slow body    a few connections send their headers and then the body a byte at a time, slower than
               client_body_timeout (10 s): nginx drops them after about 10 s (closed, or 408)
  slow headers a few connections never finish their headers: dropped after client_header_timeout (10 s)
  connections  more than limit_conn perip (50) requests in progress at once from one address: the extra ones get 429;
               the held requests are slow-body ones sent under the request rate, so only the connection limit refuses
  rate         a burst of play requests from one address: limit_req (10 a second, burst 10, nodelay) lets about 11
               through at once and answers the rest 429

Writes a JSON report to stdout (and to $OUT/nginx-limits.json when OUT is set); exits non-zero when a limit is missing.
The limits count per visitor address (client-ip.conf): many players behind one mobile carrier address share them; the
report states what that means for them.
"""
import json, os, socket, sys, threading, time, urllib.parse

URL = urllib.parse.urlsplit(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("WEB_URL", "http://127.0.0.1:8080"))
HOST, PORT = URL.hostname, URL.port or 80
BODY_LIMIT = 16 * 1024
BODY_TIMEOUT = 10
CONN_LIMIT = 50
RATE, BURST = 10, 10


def connect(timeout=30):
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return s


def head(path, length, extra=""):
    return (f"POST {path} HTTP/1.1\r\nHost: {HOST}\r\nContent-Type: application/json\r\nContent-Length: {length}\r\n{extra}\r\n").encode()


def status_of(s):
    """The status of the answer on this connection, 0 if it was closed without one."""
    data = b""
    try:
        while b"\r\n" not in data:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
    except (ConnectionResetError, socket.timeout):
        pass
    parts = data.split(b" ", 2)
    return int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0


def request(path, body):
    s = connect()
    try:
        s.sendall(head(path, len(body), "Connection: close\r\n") + body)
        return status_of(s)
    finally:
        s.close()


def body_size():
    over = request("/api/game/play", b"{" + b" " * (BODY_LIMIT + 1024) + b"}")
    time.sleep(1.2)
    under = request("/api/game/play", b"{" + b" " * (BODY_LIMIT - 2048) + b"}")
    return {"ok": over == 413 and under != 413, "overTheLimit": over, "justUnder": under, "expected": "413 over 16k, anything else under"}


def dropped_after(send_first, keep_sending=None):
    """Seconds until nginx drops a connection that sent send_first (and keep_sending bytes every 12 s), and its answer."""
    s = connect(timeout=60)
    t = time.time()
    s.sendall(send_first)
    s.settimeout(12)
    status = None
    while True:
        try:
            data = s.recv(4096)
            parts = data.split(b" ", 2)
            status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
            break
        except socket.timeout:
            if time.time() - t > 60:
                status = "still open after 60 s"
                break
            if keep_sending:
                try:
                    s.sendall(keep_sending)
                except OSError:
                    status = 0
                    break
        except ConnectionResetError:
            status = 0
            break
    s.close()
    return round(time.time() - t, 1), status


def slow(kind, connections=3):
    if kind == "body":
        first, more = head("/api/game/session", 200) + b'{"token":', b"x"
    else:
        first, more = f"GET / HTTP/1.1\r\nHost: {HOST}\r\nUser-Agent: slow".encode(), None
    results = [None] * connections
    threads = [threading.Thread(target=lambda i=i: results.__setitem__(i, dropped_after(first, more))) for i in range(connections)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    ok = all(isinstance(r[1], int) and BODY_TIMEOUT - 1 <= r[0] <= BODY_TIMEOUT + 6 for r in results)
    return {"ok": ok, "connections": [{"droppedAfterSeconds": r[0], "answer": r[1] or "closed without an answer"} for r in results],
            "expected": f"dropped after about {BODY_TIMEOUT} s (408 or closed)"}


def connections(extra=6):
    """CONN_LIMIT + extra slow-body requests, opened under the request rate and kept alive a byte at a time."""
    held, answers, stop = [], {}, threading.Event()
    lock = threading.Lock()

    def keep_alive():
        while not stop.wait(3):
            with lock:
                for s in list(held):
                    try:
                        s.sendall(b" ")
                    except OSError:
                        held.remove(s)
    feeder = threading.Thread(target=keep_alive, daemon=True)
    feeder.start()
    for i in range(CONN_LIMIT + extra):
        s = connect(timeout=0.5)
        s.sendall(head("/api/game/session", 10000) + b"{")
        # a refused one is answered at once; a held one has nothing to say yet
        answers[i] = status_of(s)
        if answers[i]:
            s.close()
        else:
            answers[i] = "held"
            s.settimeout(30)
            with lock:
                held.append(s)
        time.sleep(1.0 / (RATE * 0.6))    # under the request rate, so only the connection limit can refuse
    stop.set()
    refused = [i for i, a in answers.items() if a == 429]
    with lock:
        for s in held:
            s.close()
    kept = sum(1 for a in answers.values() if a == "held")
    return {"ok": kept <= CONN_LIMIT and len(refused) >= extra - 1 and (not refused or min(refused) >= CONN_LIMIT - 1),
            "opened": CONN_LIMIT + extra, "heldAtOnce": kept, "refused429": len(refused), "firstRefused": min(refused) + 1 if refused else None,
            "expected": f"at most {CONN_LIMIT} requests in progress per address; the rest 429"}


def rate(count=40):
    results = []
    lock = threading.Lock()

    def one():
        st = request("/api/game/play", b"{}")
        with lock:
            results.append(st)
    t = time.time()
    threads = [threading.Thread(target=one) for _ in range(count)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    took = time.time() - t
    through = sum(1 for r in results if r != 429)
    allowed = BURST + 1 + int(RATE * took) + 1
    return {"ok": results.count(429) > 0 and through <= allowed, "sent": count, "seconds": round(took, 2), "through": through,
            "refused429": results.count(429), "allowedThrough": allowed, "answers": {str(k): results.count(k) for k in sorted(set(results))},
            "expected": f"about {BURST + 1} through at once, then {RATE} a second; the rest 429"}


def main():
    report = {"target": f"{HOST}:{PORT}"}
    report["bodySize"] = body_size()
    time.sleep(1.5)
    slow_results = {}
    threads = [threading.Thread(target=lambda k=k: slow_results.__setitem__(k, slow(k))) for k in ("body", "headers")]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    report["slowBody"], report["slowHeaders"] = slow_results["body"], slow_results["headers"]
    time.sleep(2)
    report["connections"] = connections()
    time.sleep(3)
    report["rate"] = rate()
    report["passed"] = all(v["ok"] for v in report.values() if isinstance(v, dict))
    report["note"] = (f"The limits are per visitor address. Players behind one mobile carrier address (CGNAT) share {RATE} game "
                      f"requests a second (burst {BURST}) and {CONN_LIMIT} requests in progress: at one round every 3 to 5 seconds "
                      f"per player that is about {RATE * 3}-{RATE * 5} players at once on one address before some get 429. Idle "
                      f"keep-alive connections don't count against the {CONN_LIMIT}.")
    text = json.dumps(report, indent=2)
    print(text)
    if os.environ.get("OUT"):
        os.makedirs(os.environ["OUT"], exist_ok=True)
        with open(os.path.join(os.environ["OUT"], "nginx-limits.json"), "w") as f:
            f.write(text + "\n")
    print("nginx limits:", "PASSED" if report["passed"] else "FAILED", file=sys.stderr)
    sys.exit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
