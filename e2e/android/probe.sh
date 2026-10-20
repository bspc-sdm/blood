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
  curl -s -X POST "$S/api/community" -H 'Content-Type: application/json' -H "Authorization: Bearer $t" -d "{\"action\":\"$2\"${3:+,$3}}" | head -c 3000; echo
}
call pharmacy books_today ""
call pharmacy books_rx_preview '"code":"DEMXRXB333"'
call pharmacy books_items ""
