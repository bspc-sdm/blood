#!/usr/bin/env bash
# Lifolo — সার্ভারের লগ কতদিন থাকে (VPS-এ root হিসেবে)। গোপনীয়তা নীতিতে লেখা আছে সার্ভারের লগ কয়েক সপ্তাহ পরে
# নিজে মুছে যায় — এই স্ক্রিপ্ট সেটা সত্যি করে। বারবার চালালেও একই ফল (idempotent)।
# সাধারণত GitHub Actions-এর "Ops: log retention" চালায়; হাতে:  bash log-retention.sh
#
#   journald (অ্যাপের লগ — lifolo-api stdout, আর সব সার্ভিস): সর্বোচ্চ ২১ দিন (MaxRetentionSec=21day), ফাইল রোজ নতুন
#     (MaxFileSec=1day — নইলে চালু ফাইলটা এক মাস পর্যন্ত থাকতে পারত), মোট ১ GB-এর বেশি নয় (SystemMaxUse=1G)
#   nginx (access.log-এ দর্শকের IP): রোজ ঘোরে, ১৩টা পুরোনো ফাইল + আজকেরটা = ≤১৪ দিন, maxage 14
#   syslog/auth.log (rsyslog — journald-এর কপি, তাই অ্যাপের লগও এখানে আসে), PostgreSQL, Redis, fail2ban, ufw,
#     btmp/wtmp (মাসিক → সাপ্তাহিক): সাপ্তাহিক, ২টা পুরোনো ফাইল + চলতি = ≤২১ দিন, maxage 21
#   এখনই: journal থেকে ২১ দিনের পুরোনো মোছা, ওই ফোল্ডারগুলোর ২১ দিনের পুরোনো ঘোরানো ফাইল মোছা।
# প্যাকেজের /etc/logrotate.d ফাইলে শুধু rotate/maxage লাইন বদলায় (postrotate ইত্যাদি যেমন ছিল); unattended-upgrades
# প্যাকেজ আপডেটে বদলানো conffile রেখে দেয় (--force-confold)। কিছু ভুল হলে আগের ফাইল ফেরত।
# শেষে key=value (workflow সারাংশ বানায়)।
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "root হিসেবে চালান" >&2; exit 1; }
kv() { printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\n\r' | cut -c1-300)"; }

JOURNAL_DAYS=21
WEB_DAYS=14
OTHER_DAYS=21

# ---------------------------------------------------------------- journald
conf=/etc/systemd/journald.conf.d/90-lifolo-retention.conf
install -d -m 0755 /etc/systemd/journald.conf.d
want="# Lifolo — deploy/log-retention.sh লেখে (গোপনীয়তা নীতি: সার্ভারের লগ কয়েক সপ্তাহ পরে মোছে)। হাতে বদলাবেন না।
[Journal]
Storage=persistent
SystemMaxUse=1G
SystemKeepFree=2G
MaxFileSec=1day
MaxRetentionSec=${JOURNAL_DAYS}day"
if [ "$(cat "$conf" 2>/dev/null || true)" != "$want" ]; then
  printf '%s\n' "$want" > "$conf"
  chmod 0644 "$conf"
  systemctl restart systemd-journald
  kv journald_changed yes
else
  kv journald_changed no
fi
journalctl --vacuum-time="${JOURNAL_DAYS}d" >/dev/null 2>&1 || true
kv journald_settings "$(systemd-analyze cat-config systemd/journald.conf 2>/dev/null | grep -E '^(SystemMaxUse|SystemKeepFree|MaxFileSec|MaxRetentionSec|Storage)=' | tr '\n' ' ')"
kv journald_usage "$(journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]?B?' | head -1)"
kv journald_oldest "$( (journalctl --no-pager -q -o short-iso 2>/dev/null || true) | awk 'NR==1{print $1; exit}')"

# ---------------------------------------------------------------- logrotate
# $1 = ফাইল, $2 = rotate সংখ্যা, $3 = maxage দিন, $4 = weekly দিলে monthly → weekly
set_rotate() {
  local f="/etc/logrotate.d/$1" n="$2" d="$3" freq="${4:-}"
  [ -f "$f" ] || { kv "logrotate_$1" "নেই"; return 0; }
  local bak
  bak="$(mktemp)"
  cp -p "$f" "$bak"
  if [ "$freq" = weekly ]; then sed -i -E 's/^([[:space:]]*)monthly[[:space:]]*$/\1weekly/' "$f"; fi
  sed -i -E "s/^([[:space:]]*)rotate[[:space:]]+[0-9]+/\1rotate $n/" "$f"
  if grep -qE '^[[:space:]]*maxage[[:space:]]+[0-9]+' "$f"; then
    sed -i -E "s/^([[:space:]]*)maxage[[:space:]]+[0-9]+/\1maxage $d/" "$f"
  else
    # প্রতিটা rotate লাইনের পরে একই ঢঙে maxage
    sed -i -E "s/^([[:space:]]*)rotate $n$/&\n\1maxage $d/" "$f"
  fi
  if ! grep -qE "^[[:space:]]*rotate $n\$" "$f"; then
    cp -p "$bak" "$f"; rm -f "$bak"; kv "logrotate_$1" "failed: rotate লাইন নেই"; return 0
  fi
  # পুরো কনফিগ দিয়ে যাচাই (কিছু ফাইল একা পড়লে ব্যর্থ হয় — যেমন rsyslog-এর su/create — তাই একা নয়)
  if ! logrotate -d /etc/logrotate.conf >/dev/null 2>&1; then
    cp -p "$bak" "$f"; rm -f "$bak"; kv "logrotate_$1" "failed: logrotate -d, আগের ফাইল ফেরত"; return 0
  fi
  if cmp -s "$bak" "$f"; then ch=no; else ch=yes; fi
  rm -f "$bak"
  kv "logrotate_$1" "$(grep -hoE '^[[:space:]]*(daily|weekly|monthly|rotate [0-9]+|maxage [0-9]+)' "$f" | tr -s ' \t\n' ' ')(বদলেছে: $ch)"
}
# nginx রোজ ঘোরে: আজকের + ১৩টা = ১৪ দিন
set_rotate nginx $((WEB_DAYS - 1)) "$WEB_DAYS"
# সাপ্তাহিক: চলতি + ২টা = ৩ সপ্তাহ (২১ দিন)
for f in rsyslog postgresql-common redis-server fail2ban ufw; do set_rotate "$f" 2 "$OTHER_DAYS"; done
# btmp (ভুল SSH-লগইনের IP) আর wtmp: Ubuntu-তে মাসিক — সাপ্তাহিক করে একই সীমা
for f in btmp wtmp; do set_rotate "$f" 2 "$OTHER_DAYS" weekly; done
# সব ঠিক আছে কি না (পুরো কনফিগ)
if logrotate -d /etc/logrotate.conf >/dev/null 2>&1; then kv logrotate_check ok; else kv logrotate_check "errors (logrotate -d /etc/logrotate.conf দেখুন)"; fi
kv logrotate_timer "$(systemctl is-active logrotate.timer 2>/dev/null)/$(systemctl is-enabled logrotate.timer 2>/dev/null)"

# ---------------------------------------------------------------- এখনই পুরোনো ঘোরানো ফাইল মোছা
removed=0
for d in /var/log/nginx /var/log/postgresql /var/log/redis; do
  [ -d "$d" ] || continue
  n="$(find "$d" -maxdepth 1 -type f \( -name '*.gz' -o -name '*.[0-9]' \) -mtime +"$OTHER_DAYS" -print -delete 2>/dev/null | wc -l || true)"
  removed=$((removed + n))
done
n="$(find /var/log/nginx -maxdepth 1 -type f \( -name '*.gz' -o -name '*.[0-9]' \) -mtime +"$WEB_DAYS" -print -delete 2>/dev/null | wc -l || true)"
removed=$((removed + n))
n="$(find /var/log -maxdepth 1 -type f \( -name 'syslog.*' -o -name 'auth.log.*' -o -name 'kern.log.*' -o -name 'fail2ban.log.*' -o -name 'ufw.log.*' \) -mtime +"$OTHER_DAYS" -print -delete 2>/dev/null | wc -l || true)"
removed=$((removed + n))
kv old_rotated_removed "$removed"

# ---------------------------------------------------------------- অ্যাপের নিজের লগ ফাইল?
pid="$(systemctl show -p MainPID --value lifolo-api.service 2>/dev/null || echo 0)"
files=""
if [ "${pid:-0}" != 0 ]; then files="$(find "/proc/$pid/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null | grep -E '\.log' | sort -u | tr '\n' ' ' || true)"; fi
kv app_log_files "${files:-none (stdout → journald, ${JOURNAL_DAYS} দিন)}"
kv rsyslog_forwarding "$(systemctl is-active rsyslog 2>/dev/null) (journald-এর কপি /var/log/syslog-এ — ওপরের rsyslog সীমা)"
kv finished yes
