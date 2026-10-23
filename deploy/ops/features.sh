#!/usr/bin/env bash
# Lifolo — ফিচার সুইচ বদলানো, VPS-এর ভেতর থেকে (root হিসেবে)। "Ops: feature switches" workflow (ops-features.yml)
# ssh দিয়ে চালায়; হাতেও চলে:  bash features.sh <কাজ> [snapshot-এর নাম]
#
#   show            প্রতিটা key-র mode (off / pilot / on), পাইলট সংখ্যা, ডেমো, না মেটা নির্ভরতা
#   all_on          আগে snapshot, তারপর সব key "on" (পাইলট তালিকা আর ডেমো-চিহ্ন যেমন ছিল থাকে)
#   all_off         আগে snapshot, তারপর সব key "off" (পাইলট তালিকা আর ডেমো-চিহ্ন যেমন ছিল থাকে)
#   restore [নাম]   সর্বশেষ all_on/all_off-এর আগের snapshot (বা নাম দেওয়া snapshot) হুবহু ফেরত — mode, পাইলট, ডেমো;
#                   ফেরানোর আগেও একটা snapshot (…-before-restore.json) রাখা হয়
#   list            রাখা snapshot-গুলো
#
# কীভাবে: অ্যাপের নিজের পথ — POST /api/cron/features?do=show|set (features/FeatureOps.kt), CRON_SECRET দিয়ে,
# 127.0.0.1:8088 (nginx → যেটা live)। অ্যাডমিন পাতার মতোই লেখে: feature_flags, feature_changes (ইতিহাস), admin_log
# (actor "ops-features"), cache মোছা, features_changed push/SSE। ডাটাবেসে সরাসরি কিছু লেখা হয় না।
# Snapshot: /var/lib/lifolo/feature-snapshots/<UTC সময়>-before-<কাজ>.json (0600, root)।
# গোপন কিছু ছাপে না: CRON_SECRET শুধু একটা অস্থায়ী header ফাইলে (কমান্ড লাইনে নয়); BKASH_* শুধু আছে কি নেই।
# শেষ লাইন: RESULT ok=<0|1> action=… changed=<n> unmet=<n> snapshot=<নাম>
set -uo pipefail

ACTION="${1:-show}"
NAME="${2:-}"
# (FEATURES_* only for a local test of this script)
APP_ENV="${FEATURES_APP_ENV:-/etc/lifolo/app.env}"
SNAP_DIR="${FEATURES_SNAP_DIR:-/var/lib/lifolo/feature-snapshots}"
BASE="${FEATURES_BASE:-$(/usr/local/sbin/lifolo-api local 2>/dev/null || echo http://127.0.0.1:8080)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

env_get() { sed -nE "s/^$1=(.*)$/\1/p" "$2" 2>/dev/null | tail -1; }
fail() { echo "**ত্রুটি:** $*"; echo "RESULT ok=0 action=$ACTION changed=0 unmet=0 snapshot=${SNAP_NAME:-}"; exit 1; }

secret="$(env_get CRON_SECRET "$APP_ENV")"
[ -n "$secret" ] || fail "CRON_SECRET নেই ($APP_ENV)"
printf 'Authorization: Bearer %s\n' "$secret" > "$WORK/h"
printf 'Content-Type: application/json\n' >> "$WORK/h"
chmod 600 "$WORK/h"
unset secret

# api <do> <out> [body-file] — HTTP কোড ফেরত দেয়
api() {
  local data=()
  [ -n "${3:-}" ] && data=(--data-binary "@$3")
  curl -sS -m 60 -o "$2" -w '%{http_code}' -X POST -H "@$WORK/h" "${data[@]}" "$BASE/api/cron/features?do=$1" 2>"$WORK/curl.err" || echo 000
}

show_json() {
  local code
  code="$(api show "$1")"
  [ "$code" = 200 ] || fail "show: HTTP $code $(head -c 200 "$1" 2>/dev/null) $(head -c 200 "$WORK/curl.err")"
}

snapshot() {
  install -d -m 0700 "$SNAP_DIR"
  SNAP_NAME="$(date -u +%Y%m%dT%H%M%SZ)-before-$1.json"
  cp "$WORK/before.json" "$SNAP_DIR/$SNAP_NAME"
  chmod 600 "$SNAP_DIR/$SNAP_NAME"
}

SNAP_NAME=""
case "$ACTION" in
  list)
    echo "### রাখা snapshot ($SNAP_DIR)"
    ls -1 "$SNAP_DIR" 2>/dev/null | sed 's/^/- /' || echo "- (নেই)"
    echo "RESULT ok=1 action=list changed=0 unmet=0 snapshot="
    exit 0 ;;
  show)
    show_json "$WORK/after.json" ;;
  all_on|all_off|restore)
    show_json "$WORK/before.json"
    if [ "$ACTION" = restore ]; then
      if [ -n "$NAME" ]; then
        [[ "$NAME" =~ ^[0-9TZ]+-before-[a-z_]+\.json$ ]] || fail "snapshot-এর নাম ঠিক নেই: $NAME"
        SRC="$SNAP_DIR/$NAME"
      else
        SRC="$(ls -1 "$SNAP_DIR"/*-before-all_on.json "$SNAP_DIR"/*-before-all_off.json 2>/dev/null | sort | tail -1)"
      fi
      [ -n "$SRC" ] && [ -f "$SRC" ] || fail "ফেরানোর মতো snapshot নেই (আগে all_on বা all_off চালাতে হয়)"
      RESTORED_FROM="$(basename "$SRC")"
      snapshot restore
      python3 - "$SRC" > "$WORK/body.json" <<'PY' || fail "snapshot পড়া গেল না"
import json, sys
snap = json.load(open(sys.argv[1]))
items = [{"key": i["key"], "mode": i["mode"], "pilotUids": i.get("pilotUids", []), "demo": bool(i.get("demo", False))}
         for i in snap["items"]]
print(json.dumps({"items": items}))
PY
      # snapshot-এর পরে যোগ হওয়া key (তখন ছিল না = বন্ধ) → off
      python3 - "$WORK/body.json" "$WORK/before.json" <<'PY' || fail "snapshot মেলানো গেল না"
import json, sys
body = json.load(open(sys.argv[1])); now = json.load(open(sys.argv[2]))
have = {i["key"] for i in body["items"]}
known = [i["key"] for i in now["items"]]
body["items"] = [i for i in body["items"] if i["key"] in known] + [{"key": k, "mode": "off"} for k in known if k not in have]
json.dump(body, open(sys.argv[1], "w"))
PY
    else
      snapshot "$ACTION"
      mode="${ACTION#all_}"
      python3 - "$WORK/before.json" "$mode" > "$WORK/body.json" <<'PY' || fail "তালিকা বানানো গেল না"
import json, sys
s = json.load(open(sys.argv[1]))
print(json.dumps({"items": [{"key": i["key"], "mode": sys.argv[2]} for i in s["items"]]}))
PY
    fi
    code="$(api set "$WORK/set.json" "$WORK/body.json")"
    [ "$code" = 200 ] || fail "set: HTTP $code $(head -c 300 "$WORK/set.json" 2>/dev/null) $(head -c 200 "$WORK/curl.err")"
    show_json "$WORK/after.json" ;;
  *)
    fail "কাজ: show | all_on | all_off | restore [নাম] | list" ;;
esac

# bKash PGW-এর চাবি আছে কি না (মান নয়, শুধু নাম গোনা)
bkash_set="$(grep -cE '^BKASH_[A-Z_]+=.+' "$APP_ENV" 2>/dev/null || true)"

python3 - "$ACTION" "$WORK/after.json" "$WORK/set.json" "$SNAP_NAME" "${RESTORED_FROM:-}" "${bkash_set:-0}" <<'PY'
import json, os, sys
action, after_f, set_f, snap, restored, bkash = sys.argv[1:7]
after = json.load(open(after_f))
res = json.load(open(set_f)) if os.path.exists(set_f) else None
names = {"show": "দেখা", "all_on": "সব চালু", "all_off": "সব বন্ধ", "restore": "আগের অবস্থায় ফেরত"}
mode_bn = {"on": "চালু (সবার জন্য)", "pilot": "পাইলট", "off": "বন্ধ"}
print(f"## ফিচার সুইচ — {names.get(action, action)} (`{action}`)")
print()
if snap:
    print(f"- আগের অবস্থার snapshot: `{snap}` (VPS: /var/lib/lifolo/feature-snapshots/)")
if restored:
    print(f"- যেখান থেকে ফেরানো হলো: `{restored}`")
if res is not None:
    ch = res.get("changed", [])
    print(f"- বদলেছে: **{len(ch)}টা** key, আগের মতোই ছিল: {res.get('unchanged', 0)}টা · সংস্করণ v={res.get('v')}")
    print("- অডিট: প্রতিটা বদলে `feature_changes`-এ একটা সারি আর অ্যাডমিন লগে একটা (`admin_feature_set`, কে: `ops-features`)")
counts = {}
for i in after["items"]:
    counts[i["mode"]] = counts.get(i["mode"], 0) + 1
print(f"- এখন: " + " · ".join(f"{mode_bn.get(m, m)} {n}" for m, n in sorted(counts.items())))
unmet = after.get("unmet", [])
if unmet:
    print("- **নির্ভরতা মেটেনি:** " + ", ".join(f"`{u['key']}` (লাগে: {', '.join(u['needs'])})" for u in unmet))
else:
    print("- নির্ভরতা: সব মেটানো (FEATURES.md → Features.NEEDS)")
pgw = next((i for i in after["items"] if i["key"] == "pay_bkash_pgw"), None)
if pgw and pgw["mode"] != "off" and int(bkash or 0) == 0:
    print("- `pay_bkash_pgw` চালু, কিন্তু সার্ভারে BKASH_* চাবি নেই: Lifolo-র নিজের সাবস্ক্রিপশনের অনলাইন পেমেন্ট "
          "503 `pgw_unavailable` (\"অনলাইন পেমেন্ট এখন চালু নেই\") দেবে — জেনেশুনে রাখা; অন্য কিছু ভাঙে না।")
if after.get("allOn"):
    print("- সতর্কতা: সার্ভারে FEATURES_ALL_ON চালু — সুইচ যা-ই থাকুক সব চালু দেখাবে।")
print()
print("| key | mode | পাইলট | ডেমো | লাগে |")
print("|---|---|---|---|---|")
for i in after["items"]:
    print(f"| `{i['key']}` | {i['mode']} | {len(i.get('pilotUids', []))} | {'হ্যাঁ' if i.get('demo') else '—'} | {', '.join(i.get('needs', [])) or '—'} |")
print()
print("```")
for i in after["items"]:
    print(f"{i['key']}={i['mode']}")
print("```")
print(f"RESULT ok=1 action={action} changed={len(res.get('changed', [])) if res else 0} unmet={len(unmet)} snapshot={snap}")
PY
