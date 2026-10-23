#!/usr/bin/env python3
"""
ফিচার সুইচের পরীক্ষা ইন্টারনেট থেকে ("Ops: feature switches" workflow): API (https://api.lifolo.com)-এর health,
অতিথি আর ডেমো রোগী/ডাক্তার হিসেবে `features`, আর কয়েকটা সুইচ-বাঁধা শুধু-পড়া action (chat_threads → chat,
my_expenses → my_expenses, consent_status → health_consent)। কিছু লেখে না।
সুইচ চালু হলে action 200 দেবে, বন্ধ হলে 404 `feature_off` — দুটোই "ঠিক"; উল্টোটা হলে ভুল।

env: API (https://…), KEY (Firebase web/Android API key — app/google-services.json-এর, গোপন নয়),
     EXPECT = on | off | "" (on: ডেমো রোগীর সব key চালু থাকা চাই; off: সব বন্ধ; খালি: শুধু মিল দেখা)
ডেমো লগইন ব্যর্থ হলে (সুইচ বদলের পর DemoRefresh ডেমো আবার বানাচ্ছে — ~১০ মিনিট) কিছুক্ষণ পরপর আবার চেষ্টা।
শেষ লাইন: "ok (…)" বা "failed: …"।
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = os.environ["API"].rstrip("/")
KEY = os.environ["KEY"]
EXPECT = os.environ.get("EXPECT", "")
UA = "lifolo-ci-features-smoke/1.0 (+https://lifolo.com)"


def req(url, body=None, token=None):
    r = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    r.add_header("content-type", "application/json")
    r.add_header("user-agent", UA)  # Cloudflare blocks the "Python-urllib" agent
    if token:
        r.add_header("authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}


def action(name, token=None, **extra):
    return req(API + "/api/community", {"action": name, "lang": "bn", **extra}, token)


def login(role):
    st, d = req(API + "/api/auth/verify-code", {"demo": role})
    if st != 200 or not d.get("token"):
        raise RuntimeError(f"{role} লগইন: HTTP {st} {str(d.get('error', ''))[:120]}")
    st, d = req("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=" + KEY,
                {"token": d["token"], "returnSecureToken": True})
    if st != 200 or not d.get("idToken"):
        raise RuntimeError(f"{role}: Firebase signInWithCustomToken HTTP {st}")
    return d["idToken"]


def login_retry(role, tries=12, wait=45):
    for i in range(tries):
        try:
            return login(role)
        except RuntimeError as e:
            if i == tries - 1:
                raise
            print(f"wait {role}: {e} — আবার চেষ্টা {wait} s পরে (ডেমো আবার বানানো হচ্ছে হয়তো)")
            time.sleep(wait)


def main():
    done = []
    st, d = req(API + "/api/health")
    if st != 200 or d.get("ok") is not True:
        raise RuntimeError(f"health: HTTP {st}")
    print("ok health")
    done.append("health ok")

    st, g = action("features")
    if st != 200 or "keys" not in g:
        raise RuntimeError(f"guest features: HTTP {st}")
    print("guest keys: " + " ".join(f"{k}={'on' if v else 'off'}" for k, v in g["keys"].items()))
    done.append(f"অতিথি v={g.get('v')}: {sum(g['keys'].values())}/{len(g['keys'])} চালু")

    tok = login_retry("patient")
    st, f = action("features", tok)
    if st != 200:
        raise RuntimeError(f"patient features: HTTP {st}")
    keys = f["keys"]
    print("demo patient keys: " + " ".join(f"{k}={'on' if v else 'off'}" for k, v in keys.items()))
    if EXPECT == "on" and not all(keys.values()):
        raise RuntimeError("সব চালু হওয়ার কথা, কিন্তু বন্ধ: " + ", ".join(k for k, v in keys.items() if not v))
    if EXPECT == "off" and any(keys.values()):
        raise RuntimeError("সব বন্ধ হওয়ার কথা, কিন্তু চালু: " + ", ".join(k for k, v in keys.items() if v))
    done.append(f"ডেমো রোগী লগইন, {sum(keys.values())}/{len(keys)} চালু")

    probes = [("chat_threads", "chat", {}), ("my_expenses", "my_expenses", {})]
    for name, key, extra in probes:
        st, d = action(name, tok, **extra)
        on = keys.get(key)
        if on and st == 200 and "error" not in d:
            print(f"ok {name} ({key} on): 200 {', '.join(list(d.keys())[:5])}")
        elif not on and st == 404 and d.get("code") == "feature_off":
            print(f"ok {name} ({key} off): 404 feature_off")
        else:
            raise RuntimeError(f"{name} ({key} {'on' if on else 'off'}): HTTP {st} {d.get('code', '')} {str(d.get('error', ''))[:120]}")
    st, d = action("consent_status", tok)
    if st != 200 or bool(d.get("on")) != bool(keys.get("health_consent")):
        raise RuntimeError(f"consent_status: HTTP {st} on={d.get('on')}")
    print(f"ok consent_status: on={d.get('on')} ask={d.get('ask')} version={d.get('version')}")
    done.append("chat_threads, my_expenses, consent_status ঠিক")

    dtok = login_retry("doctor", tries=4, wait=30)
    st, f2 = action("features", dtok)
    st2, pm = action("provider_me", dtok)
    if st != 200 or st2 != 200:
        raise RuntimeError(f"doctor: features HTTP {st}, provider_me HTTP {st2}")
    print("ok doctor features + provider_me")
    done.append(f"ডেমো ডাক্তার লগইন, {sum(f2['keys'].values())}/{len(f2['keys'])} চালু")
    print("ok (" + ", ".join(done) + ")")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(f"failed: {e}")
        sys.exit(1)
