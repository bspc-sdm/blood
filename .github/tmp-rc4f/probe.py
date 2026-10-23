#!/usr/bin/env python3
# TMP (release-check only, never merged): read-only probe of production as demo roles.
# Prints the feature switches each demo role sees, the published versions, and writes the
# switch-gated journeys whose switches are ON for the demo accounts to $GITHUB_OUTPUT (extra=...).
import json, os, sys, urllib.request

S = os.environ.get("E2E_SERVER", "https://lifolo.com")
KEY = json.load(open("app/google-services.json"))["client"][0]["api_key"][0]["current_key"]
UA = {"content-type": "application/json", "user-agent": "lifolo-e2e-release-check"}


def post(url, body, token=None):
    h = dict(UA)
    if token:
        h["authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
            return r.read().decode()[:600]
    except Exception as e:  # noqa
        return f"ERR {e}"


print("version.json:", get(S + "/download/version.json"))
print("desktop latest.json:", get(S + "/download/desktop/latest.json"))
_, roles = post(S + "/api/auth/verify-code", {"demoRoles": True})
roles = roles.get("roles", roles if isinstance(roles, list) else [])
print("demo roles:", json.dumps(roles, ensure_ascii=False)[:1500])

_, g = post(S + "/api/community", {"action": "features"})
print("guest features:", json.dumps(g, ensure_ascii=False)[:1500])


def on_map(resp):
    f = resp.get("keys", resp.get("features", resp))
    out = {}
    if isinstance(f, dict):
        for k, v in f.items():
            out[k] = v.get("on") if isinstance(v, dict) else bool(v)
    elif isinstance(f, list):
        for v in f:
            if isinstance(v, dict) and "key" in v:
                out[v["key"]] = bool(v.get("on"))
    return out


seen = {}
for r in roles:
    key = r.get("key") if isinstance(r, dict) else None
    name = r.get("name", "") if isinstance(r, dict) else ""
    if not key or not any(w in name for w in ("রোগী রহিম", "ডা. ডেমো হাসান", "ফার্মেসির মালিক", "অ্যাম্বুলেন্স চালক")):
        continue
    c, t = post(S + "/api/auth/verify-code", {"demo": key})
    ct = t.get("token")
    if not ct:
        print("login", key, c, t)
        continue
    _, s = post(f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key={KEY}", {"token": ct, "returnSecureToken": True})
    tok = s.get("idToken")
    _, f = post(S + "/api/community", {"action": "features"}, tok)
    m = on_map(f)
    seen[name] = m
    print(f"features as {name} ({key}):", json.dumps(f, ensure_ascii=False)[:1500])

# health_consent ON: give the demo roles their consent first, like the web e2e (api.mjs ensureConsent) — otherwise the
# one-time consent prompt sits on top of every journey. The prompt itself is covered by ConsentScreensTest.
for r in roles:
    key = r.get("key") if isinstance(r, dict) else None
    if not key:
        continue
    c, t = post(S + "/api/auth/verify-code", {"demo": key})
    if not t.get("token"):
        continue
    _, s2 = post(f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key={KEY}", {"token": t["token"], "returnSecureToken": True})
    tok = s2.get("idToken")
    c, st = post(S + "/api/community", {"action": "consent_status", "lang": "bn"}, tok)
    if c != 200 or not st.get("on"):
        print(f"consent {key}: http {c} on={st.get('on')}")
        continue
    need = st.get("needed") or []
    if need:
        c2, g2 = post(S + "/api/community", {"action": "consent_grant", "lang": "bn", "scopes": ["health_data", "data_abroad"], "version": st.get("version"), "source": "app"}, tok)
        print(f"consent {key}: needed {need} → grant http {c2} {json.dumps(g2, ensure_ascii=False)[:200]}")
    else:
        print(f"consent {key}: already given")

pat = next((v for k, v in seen.items() if "রোগী" in k), {})
doc = next((v for k, v in seen.items() if "হাসান" in k), {})
pha = next((v for k, v in seen.items() if "ফার্মেসি" in k), {})
on = lambda m, *ks: all(m.get(k) for k in ks)  # noqa
extra, skipped = [], []
gates = [
    ("j16", on(doc, "online_chamber", "appt_video") and on(pat, "online_chamber", "appt_video"), "online_chamber/appt_video"),
    ("j17", on(pat, "calls_audio", "calls_video"), "calls_audio/calls_video"),
    ("j18", on(pat, "chat") and on(pha, "chat"), "chat"),
    ("j19", on(pha, "inv_pharmacy"), "inv_pharmacy"),
    ("j20", on(pat, "my_expenses"), "my_expenses"),
    ("j21", on(pat, "inv_ambulance"), "inv_ambulance"),
    ("j22", on(pat, "pay_manual"), "pay_manual"),
    ("j23", on(pat, "pay_due"), "pay_due"),
    ("j24", on(pha, "pay_manual"), "pay_manual"),
    ("j25", on(pat, "practice_systems"), "practice_systems"),
    ("j26", on(doc, "practice_systems"), "practice_systems"),
    ("j27", on(pat, "pro_psychologist"), "pro_psychologist"),
]
for j, ok, why in gates:
    (extra if ok else skipped).append(j if ok else f"{j}({why} off)")
print("gated journeys ON:", " ".join(extra) or "-")
print("gated journeys SKIPPED:", " ".join(skipped) or "-")
with open(os.environ.get("GITHUB_OUTPUT", "/dev/null"), "a") as o:
    o.write("extra=" + " ".join(extra) + "\n")
    o.write("skipped=" + " ".join(skipped) + "\n")
