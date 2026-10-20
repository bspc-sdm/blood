#!/usr/bin/env python3
"""
ডেমো পরীক্ষা ইন্টারনেট থেকে (Deploy backend workflow): API (যেমন https://api.lifolo.com)-এ ডেমো রোলের তালিকা,
রোগী আর ফার্মেসি হিসেবে এক-চাপে লগইন (custom token → Firebase ID token), তারপর কয়েকটা শুধু-পড়া action।
কিছু লেখে না। শেষ লাইন: "ok (…)" বা "failed: …" — workflow সেটাই সারাংশে দেখায়।

env: API (https://…), KEY (Firebase web/Android API key — app/google-services.json-এর, গোপন নয়)
"""
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ["API"].rstrip("/")
KEY = os.environ["KEY"]


def post(url, body, token=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST")
    req.add_header("content-type", "application/json")
    # Cloudflare-এর proxy "Python-urllib" নামের ব্রাউজারকে ৪০৩ দেয় (Browser Integrity Check) — নিজের নাম দেওয়া
    req.add_header("user-agent", "lifolo-ci-demo-smoke/1.0 (+https://lifolo.com)")
    if token:
        req.add_header("authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}


def login(role):
    st, d = post(API + "/api/auth/verify-code", {"demo": role})
    if st != 200 or not d.get("token"):
        raise RuntimeError(f"{role} লগইন: HTTP {st} {d.get('error', '')}")
    st, d = post(
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + KEY,
        {"token": d["token"], "returnSecureToken": True},
    )
    if st != 200 or not d.get("idToken"):
        raise RuntimeError(f"{role}: Firebase signInWithCustomToken HTTP {st}")
    return d["idToken"]


def main():
    done = []
    st, d = post(API + "/api/auth/verify-code", {"demoRoles": True})
    roles = [r.get("key") for r in (d.get("roles") or [])] if d.get("active") else []
    if not roles:
        raise RuntimeError(f"ডেমো চালু নেই (HTTP {st})")
    done.append(f"{len(roles)} রোল")
    reads = {
        "patient": [("profile_get", {}), ("blood_requests_mine", {}), ("my_appointments", {}), ("rx_mine", {})],
        "pharmacy": [("pharmacy_me", {}), ("books_today", {})],
        "doctor": [("provider_me", {})],
    }
    for role, actions in reads.items():
        tok = login(role)
        for action, extra in actions:
            st, d = post(API + "/api/community", {"action": action, "lang": "bn", **extra}, tok)
            if st != 200 or "error" in d:
                raise RuntimeError(f"{role} {action}: HTTP {st} {str(d.get('error', ''))[:120]}")
            print(f"ok {role} {action}: {', '.join(list(d.keys())[:6])}")
        done.append(f"{role} লগইন + {len(actions)} পড়া")
    print("ok (" + ", ".join(done) + ")")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(f"failed: {e}")
        sys.exit(1)
