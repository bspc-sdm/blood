#!/bin/bash
# নোটিফিকেশন চাপলে অ্যাপ ঠিক পাতা খোলে কি না — Android-এর দিক (ওয়েব/সার্ভারের দিক: e2e/web/journeys/11-notifications.mjs)।
# পুশ চাপলে অ্যাপ যা পায় ঠিক তাই: MainActivity-তে একই intent extras (type, requestId, questionId, kind, rxId, asUid, reqId — Notifier.show)
# আর একই flag (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP = 0x14000000; MainActivity singleTop → চালু অ্যাপে onNewIntent)।
# একটা ঘটনা ঠান্ডা অবস্থা থেকে (force-stop → onCreate-এর পথ)। প্রতিটা ঘটনার পরে Maestro (notify/check.yaml) দেখে ঠিক পাতা।
# ডেমোর স্থির id (DemoSeeder): demo-rq-patient-open (রোগী রহিমের খোলা রিকোয়েস্ট, ডেমো জয়দেবপুর জেনারেল হাসপাতাল),
# demo-q-diabetes (রহিমের প্রশ্ন), demo-rx-b (রহিমের প্রেসক্রিপশন, Dyslipidaemia), demo-patient (রহিমের uid)।
# run.sh থেকে চলে (এমুলেটর, অ্যাপ বসানো)। ফল: $OUT/notify.txt (PASS/FAIL প্রতিটা), ছবি $OUT/shots/n-*.png
set -u
W=${GITHUB_WORKSPACE:-$(pwd)}
OUT=${OUT:-$W/e2e-out}
N=$W/e2e/android/notify
PKG=com.bspc.blood
mkdir -p "$OUT/shots" "$OUT/debug"
RES="$OUT/notify.txt"
: > "$RES"
Y='(?:য়|য়)' # "য়" দুই রকম ইউনিকোডে লেখা যায়

login() { # role-name
  timeout 600 maestro test -e J=n -e ROLE="$1" --debug-output "$OUT/debug/n-login" "$N/login.yaml" > "$OUT/debug/n-login-$2.log" 2>&1
}

# ঘটনা: নাম, যা দেখার কথা (regex), দ্বিতীয় যা দেখার কথা ("" = নেই), তারপর am start-এর extras
case_() {
  local name=$1 want=$2 want2=$3
  shift 3
  adb shell am start -W -n "$PKG/.MainActivity" -f 0x14000000 "$@" > "$OUT/debug/$name-am.txt" 2>&1
  if timeout 300 maestro test -e WANT="$want" -e WANT2="$want2" -e SHOT="$name" --debug-output "$OUT/debug/$name" "$N/check.yaml" > "$OUT/debug/$name.log" 2>&1; then
    echo "PASS $name $*" | tee -a "$RES"
  else
    echo "FAIL $name $* — expected: $want ${want2:+/ $want2}" | tee -a "$RES"
    tail -15 "$OUT/debug/$name.log"
  fi
  # পরের ঘটনার আগে হোমে (পাতার স্তূপ ছোট রাখতে): ফিরে ফিরে
  for _ in 1 2 3 4; do adb shell input keyevent KEYCODE_BACK; sleep 0.4; done
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1
}

cd "$OUT/shots"

# ---- রোগী (ডেমো রোগী রহিম) ----
login "ডেমো রোগী রহিম" patient
case_ n-response ".*আমার রিকো${Y}েস্ট.*" ".*জ${Y}দেবপুর জেনারেল হাসপাতাল.*" --es type response --es requestId demo-rq-patient-open
case_ n-answer "প্রশ্ন" ".*টাইপ-২ ডা${Y}াবেটিস.*" --es type answer --es questionId demo-q-diabetes
case_ n-appointment ".*আমার অ্যাপ${Y}েন্টমেন্ট.*" "" --es type appointment --es role patient
case_ n-homecare ".*বাসা${Y} (?:নার্স|ডাক্তার).*" "" --es type homecare
case_ n-support "সাহায্য ও আপিল" "" --es type support
case_ n-amb_req_mine "অ্যাম্বুলেন্স ডাকা" "" --es type amb_req_mine --es reqId e2e-no-such-request
case_ n-rx ".*Dyslipidaemia.*" "" --es type rx --es rxId demo-rx-b
case_ n-rx-list "আমার প্রেসক্রিপশন" "" --es type rx
case_ n-med_find_mine ".*ওষুধের নাম.*" "" --es type med_find_mine
case_ n-emergency ".*আশেপাশের রিকো${Y}েস্ট.*" "" --es type emergency
# ঠান্ডা অবস্থা: অ্যাপ বন্ধ, নোটিফিকেশন চাপলে নতুন করে খোলে (onCreate-এর পথ)
adb shell am force-stop "$PKG"
case_ n-rx-cold ".*Dyslipidaemia.*" "" --es type rx --es rxId demo-rx-b
adb shell am force-stop "$PKG"
case_ n-response-cold ".*আমার রিকো${Y}েস্ট.*" ".*জ${Y}দেবপুর জেনারেল হাসপাতাল.*" --es type response --es requestId demo-rq-patient-open

# ---- ডোনার ----
login "ডেমো ডোনার করিম" donor
case_ n-request ".*রক্তের রিকো${Y}েস্ট.*" ".*জ${Y}দেবপুর জেনারেল হাসপাতাল.*" --es type request --es requestId demo-rq-patient-open

# ---- ডাক্তার ----
login "ডা. ডেমো হাসান" doctor
case_ n-appointment-provider "আমার চেম্বার" ".*বুকিং.*" --es type appointment --es role provider
case_ n-rx_review "রিপোর্ট" "" --es type rx_review

# ---- ফার্মেসি ----
login "ডেমো ফার্মেসির মালিক" pharmacy
case_ n-rx_pharmacy "আমার ফার্মেসি" "" --es type rx_pharmacy
case_ n-med_find "আমার ফার্মেসি" ".*ওষুধ খোঁজা.*" --es type med_find
case_ n-services-pharmacy "আমার ফার্মেসি" "" --es type services --es kind pharmacy

# ---- প্যাথলজি ----
login "ডেমো প্যাথলজির মালিক" lab
case_ n-rx_lab "আমার প্যাথলজি" "" --es type rx_lab

# ---- কেয়ারগিভার ----
login "ডেমো কে(?:য়|য়)ারগিভার রুমা" caregiver
case_ n-care ".*কে${Y}ারগিভার ও পরিবার.*" "" --es type care
case_ n-care-rx ".*Dyslipidaemia.*" ".*ডেমো রোগী রহিম.*" --es type care --es asUid demo-patient --es rxId demo-rx-b

# ---- অ্যাম্বুলেন্স চালক ----
login "ডেমো অ্যাম্বুলেন্স চালক" ambulance
case_ n-services-ambulance "আমার অ্যাম্বুলেন্স" "" --es type services --es kind ambulance --es reqId e2e-no-such-request
case_ n-services "আমার কাজ" "" --es type services

echo "notify: $(grep -c '^PASS' "$RES") pass, $(grep -c '^FAIL' "$RES") fail" | tee -a "$OUT/results.txt"
