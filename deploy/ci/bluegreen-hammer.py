#!/usr/bin/env python3
"""
blue/green deploy-এর পরীক্ষা (.github/workflows/deploy-bluegreen-test.yml): deploy চলার পুরো সময় nginx-এর https দিয়ে
/api/health (GET) আর একটা আসল API action (POST /api/community, অতিথির "features" — ডাটাবেস পড়ে) বারবার, কয়েকটা থ্রেডে,
keep-alive সংযোগে — stop-ফাইল না আসা পর্যন্ত। তারপর ফল JSON-এ।

  bluegreen-hammer.py <host> <out.json> <stop-file>

ব্রাউজার/OkHttp-এর মতোই: nginx reload-এ পুরোনো worker অলস keep-alive সংযোগ বন্ধ করে — আগের সংযোগে পাঠাতে গিয়ে ভাঙলে
নতুন সংযোগে একবার আবার (reused_retry হিসেবে গোনা)। নতুন সংযোগেও ব্যর্থ হলে বা কোনো 5xx এলে — সেটাই ব্যর্থতা।
"""
import http.client
import json
import os
import ssl
import sys
import threading
import time

HOST, OUT, STOP = sys.argv[1], sys.argv[2], sys.argv[3]
CTX = ssl.create_default_context()
lock = threading.Lock()
counts = {}
bad = []
reused_retry = [0]
first_ts = [None]
last_ts = [None]


def once(conn, kind):
    if kind == "health":
        conn.request("GET", "/api/health")
    else:
        conn.request(
            "POST",
            "/api/community",
            body=json.dumps({"action": "features", "lang": "bn"}),
            headers={"content-type": "application/json"},
        )
    r = conn.getresponse()
    body = r.read()
    return r.status, body, (r.getheader("connection") or "").lower() == "close"


def worker(kind):
    conn = None
    while not os.path.exists(STOP):
        reused = conn is not None
        if conn is None:
            conn = http.client.HTTPSConnection(HOST, 443, timeout=30, context=CTX)
        try:
            code, body, close = once(conn, kind)
        except Exception as e:  # noqa: BLE001 — any network failure counts
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass
            conn = None
            if reused:
                with lock:
                    reused_retry[0] += 1
                conn = http.client.HTTPSConnection(HOST, 443, timeout=30, context=CTX)
                try:
                    code, body, close = once(conn, kind)
                except Exception as e2:  # noqa: BLE001
                    code, body, close = "ERR:" + type(e2).__name__, str(e2).encode()[:160], True
            else:
                code, body, close = "ERR:" + type(e).__name__, str(e).encode()[:160], True
        if close and conn is not None:
            conn.close()
            conn = None
        now = time.time()
        with lock:
            key = f"{kind} {code}"
            counts[key] = counts.get(key, 0) + 1
            first_ts[0] = first_ts[0] or now
            last_ts[0] = now
            if not (isinstance(code, int) and 200 <= code < 300) and len(bad) < 400:
                bad.append(f"{time.strftime('%H:%M:%S', time.gmtime(now))} {kind} {code} {body[:200]!r}")
        time.sleep(0.02)


threads = [threading.Thread(target=worker, args=(k,), daemon=True) for k in ("health", "health", "health", "action", "action")]
for t in threads:
    t.start()
for t in threads:
    t.join()

total = sum(counts.values())
n5xx = sum(n for k, n in counts.items() if k.split(" ", 1)[1].startswith("5"))
nerr = sum(n for k, n in counts.items() if k.split(" ", 1)[1].startswith("ERR"))
n2xx = {kind: sum(n for k, n in counts.items() if k.startswith(kind + " 2")) for kind in ("health", "action")}
res = {
    "total": total,
    "2xx_health": n2xx["health"],
    "2xx_action": n2xx["action"],
    "5xx": n5xx,
    "errors": nerr,
    "reused_retry": reused_retry[0],
    "seconds": round((last_ts[0] or 0) - (first_ts[0] or 0), 1),
    "counts": counts,
    "bad": bad,
}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(res, f, ensure_ascii=False, indent=1)
print(json.dumps({k: v for k, v in res.items() if k != "bad"}, ensure_ascii=False))
