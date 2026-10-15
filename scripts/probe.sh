#!/bin/bash
# সরকারি ওষুধ নিবন্ধনের তালিকার API খোঁজা
for u in \
  "https://api.tr.ocl.dghs.gov.bd/orgs/?limit=50" \
  "https://tr.ocl.dghs.gov.bd/api/orgs/?limit=50" \
  "https://api.tr.ocl.dghs.gov.bd/sources/?limit=50" \
  "https://tr-api.ocl.dghs.gov.bd/orgs/?limit=50" \
  "https://api.ocl.dghs.gov.bd/orgs/?limit=50" \
  "https://tr.ocl.dghs.gov.bd/" ; do
  echo "=== $u"
  curl -sS -m 20 -H "Accept: application/json" "$u" | head -c 3000
  echo
done
# ওয়েব অ্যাপের কনফিগ থেকে API ঠিকানা
curl -sS -m 20 https://tr.ocl.dghs.gov.bd/ | grep -oE 'src="[^"]+\.js"' | head -5 | while read -r line; do
  f=$(echo "$line" | sed 's/src="//; s/"$//')
  echo "=== JS $f"
  curl -sS -m 30 "https://tr.ocl.dghs.gov.bd$f" | grep -oE 'https?://[a-zA-Z0-9.:/_-]*(api|ocl)[a-zA-Z0-9.:/_-]*' | sort -u | head -20
done
curl -sS -m 20 https://tr.ocl.dghs.gov.bd/env-config.js 2>/dev/null | head -c 1500
