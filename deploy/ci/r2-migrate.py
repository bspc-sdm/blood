#!/usr/bin/env python3
"""
R2-এ পুরোনো base64 ছবি সরানো (একবারের কাজ) — VPS-এ root হিসেবে চলে ("R2 migrate" workflow: ssh vps python3 - < এই ফাইল)।

  ১) GET  /api/cron/storage            → কত বাকি (R2 চালু না থাকলে থামে)
  ২) GET  /api/cron/storage/probe      → প্রতি ধরনের একটা সারি (যেটায় এখনো base64): API যে লেখা ফেরত দেয় তার sha256
  ৩) POST /api/cron/storage/migrate?limit=200 বারবার — বাকি ০ হওয়া পর্যন্ত (বা আর কিছু না সরলে)
  ৪) একই সারিগুলো আবার probe: এবার R2 থেকে পড়া, sha256 আগের মতোই কি না
CRON_SECRET আসে /etc/lifolo/app.env থেকে। আউটপুট শুধু key=value — সংখ্যা আর ok/mismatch; কোনো ছবি, id, ইমেইল নয়।
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api/cron/storage"
SOURCES = ["profilePhoto", "profileThumb", "verifyDocs", "labReportPhotos"]
LIMIT = 200
MAX_ROUNDS = 1000


def secret():
    with open("/etc/lifolo/app.env", encoding="utf-8") as f:
        for line in f:
            if line.startswith("CRON_SECRET="):
                return line.split("=", 1)[1].strip()
    return ""


S = secret()


def call(method, path, timeout=60):
    req = urllib.request.Request(BASE + path, method=method, data=b"" if method == "POST" else None)
    req.add_header("Authorization", "Bearer " + S)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}


def out(k, v):
    print(f"{k}={v}", flush=True)


def remaining(d):
    return {s: int((d.get("remaining") or {}).get(s, 0)) for s in SOURCES}


def main():
    if not S:
        out("error", "CRON_SECRET not found in /etc/lifolo/app.env")
        return 1
    st, d = call("GET", "")
    if st != 200:
        out("error", f"status HTTP {st}")
        return 1
    out("configured", str(bool(d.get("configured"))).lower())
    before = remaining(d)
    for s in SOURCES:
        out(f"before_{s}", before[s])
    out("before_stored", d.get("stored", 0))
    if not d.get("configured"):
        out("error", "R2 is not configured on the server (deploy with the R2_* secrets first)")
        return 1

    # ২) নমুনা — pk শুধু এই প্রক্রিয়ার মেমোরিতে থাকে, কোথাও লেখা হয় না
    st, sample = call("GET", "/probe")
    if st != 200:
        out("error", f"probe HTTP {st}")
        return 1
    samples = {s: v for s, v in sample.items() if s in SOURCES and v and v.get("sha256")}

    # ৩) সরানো
    tm = tf = tc = rounds = busy = 0
    rem = before
    while rounds < MAX_ROUNDS:
        st, r = call("POST", f"/migrate?limit={LIMIT}", timeout=900)
        if st == 409:
            busy += 1
            if busy > 60:
                out("error", "another migration kept running (409)")
                break
            time.sleep(10)
            continue
        if st != 200:
            out("error", f"migrate HTTP {st}")
            break
        rounds += 1
        m, f, c = int(r.get("migrated", 0)), int(r.get("failed", 0)), int(r.get("changed", 0))
        tm, tf, tc = tm + m, tf + f, tc + c
        rem = remaining(r)
        out(f"round_{rounds}", f"migrated {m}, failed {f}, changed {c}, left {sum(rem.values())}")
        if sum(rem.values()) == 0 or (m == 0 and c == 0):
            break
    out("rounds", rounds)
    out("migrated", tm)
    out("failed", tf)
    out("changed", tc)

    st, d = call("GET", "")
    after = remaining(d) if st == 200 else rem
    for s in SOURCES:
        out(f"after_{s}", after[s])
    out("after_stored", d.get("stored", "?") if st == 200 else "?")
    out("after_trash", d.get("trash", "?") if st == 200 else "?")

    # ৪) একই সারি আবার, এবার R2 থেকে
    bad = 0
    for s in SOURCES:
        v = samples.get(s)
        if not v:
            out(f"check_{s}", "no sample (no base64 rows of this kind)")
            continue
        q = urllib.parse.urlencode([("source", s)] + [("pk", p) for p in v["pk"]])
        st, p = call("GET", "/probe?" + q)
        if st != 200:
            out(f"check_{s}", f"HTTP {st}")
            bad += 1
        elif p.get("sha256") == v["sha256"] and p.get("from") == "r2":
            out(f"check_{s}", "ok (db -> r2, sha256 same)")
        elif p.get("sha256") == v["sha256"]:
            out(f"check_{s}", f"same sha256 but still from {p.get('from')}")
            bad += 1
        else:
            out(f"check_{s}", f"MISMATCH (from {p.get('from')})")
            bad += 1
    out("check_failed", bad)
    return 0 if (tf == 0 and sum(after.values()) == 0 and bad == 0) else 1


if __name__ == "__main__":
    sys.exit(main())
