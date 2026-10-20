#!/usr/bin/env python3
"""
লোড টেস্টের জন্য ডেমো অ্যাকাউন্টগুলোর Firebase ID token (এক ঘণ্টা চলে) — {role: idToken} JSON ফাইলে।
ডেমো লগইন (POST /api/auth/verify-code {"demo": role}) → custom token → Firebase signInWithCustomToken।
শুধু ডেমো (uid "demo-…"): সার্ভার নিজেই অন্য কারো টোকেন দেয় না। মালিকের অ্যাকাউন্ট ছোঁয়া হয় না।
টোকেন কখনো ছাপা হয় না।

env: API (https://api.lifolo.com), KEY (app/google-services.json-এর Firebase API key — গোপন নয়)
args: <out.json>
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ["API"].rstrip("/")
KEY = os.environ["KEY"]
UA = "lifolo-ops-loadtest/1.0 (+https://lifolo.com)"


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("content-type", "application/json")
    req.add_header("user-agent", UA)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}


def uid_of(id_token):
    p = id_token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))["sub"]


def main():
    out = sys.argv[1]
    st, d = post(API + "/api/auth/verify-code", {"demoRoles": True})
    roles = [r.get("key") for r in (d.get("roles") or [])] if d.get("active") else []
    if not roles:
        print(f"failed: ডেমো চালু নেই (HTTP {st})")
        sys.exit(1)
    tokens, bad = {}, []
    for role in roles:
        st, d = post(API + "/api/auth/verify-code", {"demo": role})
        if st != 200 or not d.get("token"):
            bad.append(f"{role}:HTTP{st}")
            continue
        st, d = post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + KEY,
            {"token": d["token"], "returnSecureToken": True},
        )
        if st != 200 or not d.get("idToken"):
            bad.append(f"{role}:firebase{st}")
            continue
        uid = uid_of(d["idToken"])
        if not uid.startswith("demo-"):
            # কখনো হওয়া উচিত নয় — নিরাপত্তার জন্য থামা
            print(f"failed: {role} ডেমো uid নয় — থামানো হলো")
            sys.exit(1)
        tokens[role] = d["idToken"]
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(tokens, f)
    print(f"ok {len(tokens)}/{len(roles)} রোল" + (f" (বাদ: {', '.join(bad)})" if bad else ""))
    if "patient" not in tokens:
        sys.exit(1)


if __name__ == "__main__":
    main()
