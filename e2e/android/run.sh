#!/bin/bash
# এমুলেটরে Maestro যাত্রাগুলো চালানো (GitHub Actions-এর emulator-runner থেকে)। একটা যাত্রা ব্যর্থ হলেও বাকিগুলো চলে।
set -u
W=${GITHUB_WORKSPACE:-$(pwd)}
OUT=$W/e2e-out
FLOWS=$W/e2e/android/flows
mkdir -p "$OUT/shots" "$OUT/debug"
export PATH="$PATH:$HOME/.maestro/bin"
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

adb wait-for-device
boot_wait() { for i in $(seq 1 120); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0; sleep 2; done; }
# ফোনের ভাষা বাংলা (অ্যাপ ফোনের ভাষা ধরে — বাংলাদেশের ফোনের মতো)
adb root >/dev/null 2>&1 || true; sleep 3; adb wait-for-device
adb shell setprop persist.sys.locale bn-BD
adb shell setprop ctl.restart zygote
sleep 10; adb wait-for-device; boot_wait
echo "locale: $(adb shell getprop persist.sys.locale)"
# ধীর এমুলেটরে লঞ্চারের "সাড়া দিচ্ছে না" ডায়ালগ যেন না আসে
adb shell settings put global hide_error_dialogs 1 || true
sleep 30
# তিন বাটনের নিচের বার — বাটন কাটা পড়ছে কি না সবচেয়ে ভালো বোঝা যায়
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
# E2E_APK: অন্য APK (যেমন রিলিজের হুবহু releaseSmoke) — না দিলে debug
adb install -r "${E2E_APK:-$W/app/build/outputs/apk/debug/app-debug.apk}"
adb shell getprop ro.build.version.release

ONLY=${E2E_ONLY:-}
# ক্রম: আগে ফার্মেসি (আগের যাত্রায় রোগীর পাঠানো খোলা প্রেসক্রিপশন বন্ধ করে), তারপর রোগী আবার পাঠায় — বারবার চালানো যায়
ORDER="j3-pharmacy j1-patient j2-services j4-lab j5-doctor j6-ambulance j7-blood j8-profile j9-rx-online j10-blood j11-ambulance-driver j12-appointments j13-notify-settings j14-account-delete j15-account-pause j16-online-chamber j17-calls j19-invoice-pharmacy j20-invoice-patient j21-invoice-driver"
cd "$OUT/shots"
for f in $ORDER; do
  # j16 (অনলাইন চেম্বার) শুধু চাইলে: প্রোডাকশনে ডেমো ডাক্তারের অনলাইন চেম্বার বন্ধ (DEMO_ONLINE_CHAMBERS) — স্টেজিংয়ে চালান
  if [ -z "$ONLY" ] && [ "$f" = j16-online-chamber ]; then continue; fi
  # j19–j21 (রসিদ) শুধু চাইলে: inv_pharmacy/inv_lab/inv_chamber/inv_ambulance/my_expenses ডেমোতে চালু থাকলে তবেই (ফিচার সুইচ, শুরুতে বন্ধ)
  case "$f" in j19-*|j20-*|j21-*) [ -z "$ONLY" ] && continue ;; esac
  # j17 (অ্যাপে কল) শুধু চাইলে: ফিচার সুইচ (calls_audio, calls_video) প্রোডাকশনে বন্ধ — চালু থাকা সার্ভারে চালান
  if [ -z "$ONLY" ] && [ "$f" = j17-calls ]; then continue; fi
  if [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " ${f%%-*} "; then continue; fi
  adb logcat -c || true
  start=$(date +%s)
  timeout 900 maestro test --format junit --output "$OUT/debug/$f.xml" --debug-output "$OUT/debug/$f" "$FLOWS/$f.yaml" > "$OUT/debug/$f.log" 2>&1
  rc=$?
  echo "$f rc=$rc $(( $(date +%s) - start ))s" | tee -a "$OUT/results.txt"
  tail -25 "$OUT/debug/$f.log"
  adb logcat -d -b crash > "$OUT/debug/crash-$f.txt" 2>/dev/null || true
  adb logcat -d -t 3000 > "$OUT/debug/logcat-$f.txt" 2>/dev/null || true
done
# নোটিফিকেশন চাপলে ঠিক পাতা (notify.sh) — "n" (বা সব)
if [ -z "$ONLY" ] || echo " $ONLY " | grep -q " n "; then
  start=$(date +%s)
  OUT="$OUT" bash "$W/e2e/android/notify.sh"
  echo "n-notify $(( $(date +%s) - start ))s" | tee -a "$OUT/results.txt"
  cd "$OUT/shots"
fi
# কোথাও অন্য ফোল্ডারে ছবি পড়ে থাকলে
find "$OUT/debug" "$W/e2e" "$HOME/.maestro" -name 'j[0-9]-*.png' -newer "$W/e2e/android/run.sh" -exec cp -n {} "$OUT/shots/" \; 2>/dev/null || true
ls "$OUT/shots" | wc -l
exit 0
