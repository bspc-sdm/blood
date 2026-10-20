#!/bin/bash
# প্রোডাকশন সার্ভারে ডেমো রোল হিসেবে কয়েকটা action সরাসরি ডাকা (অ্যাপের ছবিতে কিছু অদ্ভুত দেখালে কারণ বোঝার জন্য)। শুধু পড়া।
# ব্যবহার: probe.sh <role> <action> [json-body-extra]
set -u
S=${E2E_SERVER:-https://blood-server-ashy.vercel.app}
KEY=$(python3 -c "import json;print(json.load(open('app/google-services.json'))['client'][0]['api_key'][0]['current_key'])")
tok() {
  local ct; ct=$(curl -s -X POST "$S/api/auth/verify-code" -H 'Content-Type: application/json' -d "{\"demo\":\"$1\"}" | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))")
  curl -s -X POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=$KEY" -H 'Content-Type: application/json' -d "{\"token\":\"$ct\",\"returnSecureToken\":true}" | python3 -c "import sys,json;print(json.load(sys.stdin).get('idToken',''))"
}
call() { # role action extra
  local t; t=$(tok "$1")
  echo "### $1 $2 $3"
  local out; out=$(curl -s -w '\n[http %{http_code}, %{time_total}s]' -X POST "$S/api/community" -H 'Content-Type: application/json' -H "Authorization: Bearer $t" -d "{\"action\":\"$2\"${3:+,$3}}")
  echo "${out:0:2500}"; echo "$out" | tail -1
}
# হোমের "আশেপাশের রিকোয়েস্ট" (অ্যাপে মাঝে মাঝে "রিকোয়েস্ট লোড হয়নি") — ডেমো এলাকার মাঝখান, অ্যাপের মতোই body
for i in 1 2 3; do
  call donor blood_requests_near '"lat":23.999,"lng":90.42,"maxKm":50,"groups":null'
  call patient blood_requests_near '"lat":23.999,"lng":90.42,"maxKm":50,"groups":null'
done
