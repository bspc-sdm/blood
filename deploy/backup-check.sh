#!/usr/bin/env bash
# Lifolo — ব্যাকআপ যাচাই (VPS-এ root হিসেবে চলে; সাধারণত Actions-এর "Backup check" workflow চালায়)। বাংলা গাইড: deploy/BACKUP.md
#
#   sudo bash backup-check.sh
#
# যা করে:
#   ১) চালু ডাটাবেস (lifolo) থেকে একটা টাটকা pg_dump → /var/backups/lifolo/lifolo-<সময়>-check.dump (দৈনিকেরটা ছাড়াও;
#      একই ১৪ দিনের নিয়মে মোছা হয়)। dump আর গোনা একই snapshot থেকে, তাই অ্যাপ লিখতে থাকলেও সংখ্যা হুবহু মেলে।
#   ২) সেই dump একটা আলাদা অস্থায়ী ডাটাবেসে (lifolo_restore_test) ফেরত আনা → Flyway-এর ইতিহাস, টেবিল আর index-এর সংখ্যা,
#      প্রতিটা টেবিলের সারির সংখ্যা চালু ডাটাবেসের সাথে মেলানো → অস্থায়ী ডাটাবেস মুছে ফেলা।
#   ৩) /var/backups/lifolo-এর সব dump পড়া যায় কি না (pg_restore --list), দৈনিক ব্যাকআপ সময়মতো হচ্ছে কি না, ডিস্কে কত জায়গা।
#
# চালু ডাটাবেসে শুধু পড়া হয় (READ ONLY transaction + pg_dump) — কিছু লেখা, বদলানো বা মোছা হয় না।
# ফলাফল (গোপন কিছু নয়, কোনো সারির তথ্য নয়): /var/lib/lifolo/backup-check-status (key=value)।
# সব ঠিক থাকলে exit 0, কোনো যাচাই ব্যর্থ হলে exit 1।
set -euo pipefail

DB=lifolo
TEST_DB=lifolo_restore_test
BACKUP_DIR=/var/backups/lifolo
STATUS_FILE=/var/lib/lifolo/backup-check-status
KEY_TABLES=(accounts user_profiles blood_requests blood_responses flyway_schema_history)
MIN_FREE_GB=2
MIN_FREE_PCT=10

say() { printf '\n==> %s\n' "$*"; }
STATUS=()
st() { STATUS+=("$1=$(printf '%s' "$2" | tr -d '\r\n')"); }
FAILS=()
fail() { FAILS+=("$1"); echo "XX $1" >&2; }

# নিরাপত্তা: অস্থায়ী ডাটাবেসের নাম কখনো চালু ডাটাবেস নয় (এর ওপরেই শুধু DROP চলে)
[ "$TEST_DB" != "$DB" ] || { echo "TEST_DB == DB" >&2; exit 2; }
[[ "$TEST_DB" =~ ^lifolo_restore_test$ ]] || { echo "unexpected TEST_DB" >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || { echo "root হিসেবে চালান" >&2; exit 2; }
cd /
rm -f "$STATUS_FILE"   # মাঝপথে থেমে গেলে পুরোনো ফলাফল যেন "ঠিক আছে" না দেখায়

pgq() { sudo -u postgres psql -XAtq -v ON_ERROR_STOP=1 "$@"; }
drop_test_db() { pgq -d postgres -c "DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)" >/dev/null 2>&1 || true; }

WORK="$(mktemp -d /root/.lifolo-bkcheck.XXXXXX)"
LIVE_PID=""
cleanup() {
  [ -z "$LIVE_PID" ] || kill "$LIVE_PID" 2>/dev/null || true
  drop_test_db
  rm -rf "$WORK"
  rm -f "$BACKUP_DIR"/.lifolo-*-check.dump.partial
}
trap cleanup EXIT

write_status() {
  install -d -m 0755 /var/lib/lifolo
  {
    printf '%s\n' "${STATUS[@]}"
    echo "fails=${#FAILS[@]}"
    local i=0 f
    for f in "${FAILS[@]}"; do i=$((i + 1)); echo "fail_$i=$f"; done
    echo "overall=$([ "${#FAILS[@]}" -eq 0 ] && echo ok || echo failed)"
  } > "$STATUS_FILE.new"
  chmod 0644 "$STATUS_FILE.new"
  mv "$STATUS_FILE.new" "$STATUS_FILE"
}

# প্রতিটা টেবিলের সারি, Flyway, index — "ধরন|নাম|মান" লাইন (শুধু সংখ্যা, কোনো সারির তথ্য নয়)
cat > "$WORK/inventory.sql" <<'SQL'
SELECT 'row|' || table_schema || '.' || table_name || '|' ||
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text
  FROM information_schema.tables
 WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
 ORDER BY 1;
SELECT 'index|-|' || count(*) FROM pg_indexes WHERE schemaname NOT IN ('pg_catalog', 'information_schema');
SELECT 'flyway|count|' || CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'none' ELSE
       (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.flyway_schema_history', false, true, '')))[1]::text END;
SELECT 'flyway|latest|' || CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'none' ELSE
       COALESCE((xpath('/row/v/text()', query_to_xml('SELECT version AS v FROM public.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1', false, true, '')))[1]::text, 'none') END;
SQL

inv_get() { awk -F'|' -v k="$2" -v n="$3" '$1 == k && $2 == n {print $3}' "$1"; }
inv_tables() { grep -c '^row|' "$1" || true; }

# ------------------------------------------------------------------ প্রস্তুতি
say "PostgreSQL আর ডিস্ক"
pg_isready -q -h /var/run/postgresql || { fail "PostgreSQL সাড়া দিচ্ছে না"; write_status; exit 1; }
[ "$(pgq -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$DB'")" = 1 ] \
  || { fail "ডাটাবেস $DB নেই"; write_status; exit 1; }
[ -d "$BACKUP_DIR" ] || { fail "$BACKUP_DIR নেই (setup-base.sh চলেছে?)"; write_status; exit 1; }
st pg_version "$(pgq -d postgres -c 'SHOW server_version' | awk '{print $1}')"
DB_BYTES="$(pgq -d postgres -c "SELECT pg_database_size('$DB')")"
st db_size "$(numfmt --to=iec --suffix=B "$DB_BYTES")"

disk_line() { df -B1 --output=avail,size "$BACKUP_DIR" | tail -1; }
read -r AVAIL SIZE < <(disk_line)
FREE_PCT=$(( AVAIL * 100 / SIZE ))
st disk_free "$(numfmt --to=iec --suffix=B "$AVAIL")"
st disk_size "$(numfmt --to=iec --suffix=B "$SIZE")"
st disk_free_pct "$FREE_PCT"
st backup_dir_size "$(du -sh "$BACKUP_DIR" | cut -f1)B"
if [ "$AVAIL" -lt $((MIN_FREE_GB * 1024 * 1024 * 1024)) ] || [ "$FREE_PCT" -lt "$MIN_FREE_PCT" ]; then
  st disk ok_low; fail "ডিস্কে জায়গা কম: $(numfmt --to=iec "$AVAIL") ($FREE_PCT%) খালি"
else
  st disk ok
fi

# ------------------------------------------------------------------ টাটকা dump (একই snapshot-এ গোনা)
say "টাটকা pg_dump (চালু ডাটাবেস শুধু পড়া)"
TS="$(date -u +%Y%m%d-%H%M%S)"
FRESH="lifolo-$TS-check.dump"
TMP_DUMP="$BACKUP_DIR/.lifolo-$TS-check.dump.partial"
# READ ONLY transaction খোলা রেখে snapshot export; সেই snapshot-এই pg_dump আর গোনা
coproc LIVE { exec sudo -u postgres env PGOPTIONS='-c default_transaction_read_only=on' psql -XAtq -v ON_ERROR_STOP=1 -d "$DB" 2>&1; }
exec {LIVE_IN}>&"${LIVE[1]}" {LIVE_OUT}<&"${LIVE[0]}"
printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;" "SELECT pg_export_snapshot();" >&"$LIVE_IN"
SNAP=""
read -r -t 60 SNAP <&"$LIVE_OUT" || true
if ! [[ "$SNAP" =~ ^[0-9A-Fa-f-]+$ ]]; then
  fail "চালু ডাটাবেসে snapshot নেওয়া গেল না"
  write_status; exit 1
fi
t0=$(date +%s)
DUMP_OK=yes
# shellcheck disable=SC2016  # $1..$3 ভেতরের sh-এর আর্গুমেন্ট
if ! nice -n 10 sudo -u postgres sh -c 'umask 077; exec pg_dump --snapshot="$1" --format=custom --compress=6 --dbname="$2" --file="$3"' \
    sh "$SNAP" "$DB" "$TMP_DUMP" 2> "$WORK/dump.err"; then
  DUMP_OK=no
  fail "pg_dump ব্যর্থ: $(grep -m1 'error' "$WORK/dump.err" | cut -c1-200)"
fi
st dump_seconds "$(( $(date +%s) - t0 ))"
# একই snapshot-এ চালু ডাটাবেসের হিসাব
{ cat "$WORK/inventory.sql"; echo "SELECT '@@END@@';"; } >&"$LIVE_IN"
: > "$WORK/live.inv"
while IFS= read -r -t 600 line <&"$LIVE_OUT"; do
  [ "$line" = "@@END@@" ] && break
  printf '%s\n' "$line" >> "$WORK/live.inv"
done
printf '%s\n' "COMMIT;" '\q' >&"$LIVE_IN"
exec {LIVE_IN}>&-
wait "$LIVE_PID" 2>/dev/null || true
LIVE_PID=""
if [ ! -s "$WORK/live.inv" ] || grep -qv -E '^(row|index|flyway)\|' "$WORK/live.inv"; then
  fail "চালু ডাটাবেসের হিসাব পড়া যায়নি: $(grep -m1 -v -E '^(row|index|flyway)\|' "$WORK/live.inv" | cut -c1-200)"
  DUMP_OK=no
fi
if [ "$DUMP_OK" = yes ]; then
  mv "$TMP_DUMP" "$BACKUP_DIR/$FRESH"
  chown postgres:postgres "$BACKUP_DIR/$FRESH"
  chmod 0600 "$BACKUP_DIR/$FRESH"
  st fresh_dump "$FRESH"
  st fresh_dump_size "$(du -h "$BACKUP_DIR/$FRESH" | cut -f1)B"
  st fresh_dump_bytes "$(stat -c %s "$BACKUP_DIR/$FRESH")"
  st fresh_dump_sha256 "$(sha256sum "$BACKUP_DIR/$FRESH" | awk '{print $1}')"
else
  rm -f "$TMP_DUMP"
  st fresh_dump none
fi

# ------------------------------------------------------------------ অস্থায়ী ডাটাবেসে ফেরত আনা
say "সবচেয়ে নতুন dump → $TEST_DB (অস্থায়ী)"
NEWEST="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'lifolo-*.dump' -printf '%T@ %f\n' | sort -n | tail -1 | cut -d' ' -f2)"
st restore_dump "${NEWEST:-none}"
RESTORE=failed
if [ -z "$NEWEST" ]; then
  fail "ফেরত আনার মতো কোনো dump নেই"
elif [ "$NEWEST" != "$FRESH" ] || [ "$DUMP_OK" != yes ]; then
  fail "সবচেয়ে নতুন dump টাটকাটা নয় ($NEWEST) — মেলানো বাদ"
else
  read -r AVAIL _ < <(disk_line)
  NEED=$(( DB_BYTES * 2 + 512 * 1024 * 1024 ))
  if [ "$AVAIL" -lt "$NEED" ]; then
    fail "ফেরত আনার জায়গা নেই (দরকার ~$(numfmt --to=iec "$NEED"), খালি $(numfmt --to=iec "$AVAIL"))"
  else
    drop_test_db
    pgq -d postgres -c "CREATE DATABASE $TEST_DB OWNER postgres" >/dev/null
    pgq -d postgres -c "REVOKE ALL ON DATABASE $TEST_DB FROM PUBLIC" >/dev/null
    t0=$(date +%s)
    if nice -n 10 sudo -u postgres pg_restore --exit-on-error --single-transaction -d "$TEST_DB" "$BACKUP_DIR/$NEWEST" 2> "$WORK/restore.err"; then
      RESTORE=ok
    else
      # শুধু ত্রুটির প্রথম লাইন (সারির তথ্য লগে যেন না আসে)
      fail "pg_restore ব্যর্থ: $(grep -m1 '^pg_restore: error' "$WORK/restore.err" | cut -c1-200)"
    fi
    st restore_seconds "$(( $(date +%s) - t0 ))"
  fi
fi
st restore "$RESTORE"

if [ "$RESTORE" = ok ]; then
  say "মেলানো: চালু ডাটাবেস (একই snapshot) বনাম ফেরত আনা"
  if ! pgq -d "$TEST_DB" < "$WORK/inventory.sql" > "$WORK/restored.inv" 2> "$WORK/restored.err"; then
    fail "ফেরত আনা ডাটাবেস গোনা গেল না"
  fi
  st flyway_live "$(inv_get "$WORK/live.inv" flyway count)"
  st flyway_restored "$(inv_get "$WORK/restored.inv" flyway count)"
  st flyway_latest "$(inv_get "$WORK/live.inv" flyway latest)"
  st tables_live "$(inv_tables "$WORK/live.inv")"
  st tables_restored "$(inv_tables "$WORK/restored.inv")"
  st indexes_live "$(inv_get "$WORK/live.inv" index -)"
  st indexes_restored "$(inv_get "$WORK/restored.inv" index -)"
  [ "$(inv_get "$WORK/live.inv" flyway count)" != none ] || fail "চালু ডাটাবেসে Flyway-এর ইতিহাস নেই (অ্যাপ deploy হয়নি?)"
  [ "$(inv_get "$WORK/live.inv" flyway count)" = "$(inv_get "$WORK/restored.inv" flyway count)" ] || fail "Flyway-এর ইতিহাসের সংখ্যা মেলেনি"
  [ "$(inv_tables "$WORK/live.inv")" = "$(inv_tables "$WORK/restored.inv")" ] || fail "টেবিলের সংখ্যা মেলেনি"
  [ "$(inv_get "$WORK/live.inv" index -)" = "$(inv_get "$WORK/restored.inv" index -)" ] || fail "index-এর সংখ্যা মেলেনি"
  # প্রতিটা টেবিলের সারি
  MISMATCH="$(diff <(grep '^row|' "$WORK/live.inv" | sort) <(grep '^row|' "$WORK/restored.inv" | sort) | grep '^[<>]' | cut -d'|' -f2 | sort -u | tr '\n' ' ' || true)"
  MISMATCH="${MISMATCH% }"
  st rows_compared "$(inv_tables "$WORK/live.inv")"
  st rows_mismatch "${MISMATCH:-none}"
  [ -z "$MISMATCH" ] || fail "সারির সংখ্যা মেলেনি: $MISMATCH"
  for t in "${KEY_TABLES[@]}"; do
    l="$(inv_get "$WORK/live.inv" row "public.$t")"; r="$(inv_get "$WORK/restored.inv" row "public.$t")"
    if [ -z "$l" ]; then st "key_$t" missing; fail "চালু ডাটাবেসে টেবিল $t নেই"
    elif [ "$l" = "$r" ]; then st "key_$t" match
    else st "key_$t" differ
    fi
  done
fi
say "অস্থায়ী ডাটাবেস মোছা"
drop_test_db
if [ "$(pgq -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$TEST_DB'")" = 0 ]; then
  st test_db_dropped yes
else
  st test_db_dropped no; fail "$TEST_DB মোছা যায়নি"
fi

# ------------------------------------------------------------------ পুরোনো ব্যাকআপ
say "ব্যাকআপ ফাইল ($BACKUP_DIR)"
ALL=0; BAD=0; BAD_LIST=""
while IFS= read -r f; do
  ALL=$((ALL + 1))
  if ! sudo -u postgres pg_restore --list "$BACKUP_DIR/$f" >/dev/null 2>&1; then BAD=$((BAD + 1)); BAD_LIST+="$f "; fi
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'lifolo-*.dump' -printf '%f\n' | sort)
st dumps_total "$ALL"
st dumps_unreadable "$BAD"
[ "$BAD" -eq 0 ] || fail "পড়া যায় না এমন dump: ${BAD_LIST% }"

# দৈনিক (timer-এর) dump: lifolo-YYYYmmdd-HHMMSS.dump
mapfile -t DAILY < <(find "$BACKUP_DIR" -maxdepth 1 -type f -regextype posix-extended -regex '.*/lifolo-[0-9]{8}-[0-9]{6}\.dump' -printf '%f\n' | sort)
st daily_count "${#DAILY[@]}"
NOW=$(date -u +%s)
if [ "${#DAILY[@]}" -eq 0 ]; then
  st daily_newest none
  fail "কোনো দৈনিক ব্যাকআপ নেই"
else
  newest="${DAILY[-1]}"; oldest="${DAILY[0]}"
  ndate="$(sed -E 's/^lifolo-([0-9]{4})([0-9]{2})([0-9]{2})-([0-9]{2})([0-9]{2})([0-9]{2})\.dump$/\1-\2-\3 \4:\5:\6/' <<< "$newest")"
  odate="$(sed -E 's/^lifolo-([0-9]{4})([0-9]{2})([0-9]{2})-.*/\1-\2-\3/' <<< "$oldest")"
  AGE_H=$(( (NOW - $(date -u -d "$ndate" +%s)) / 3600 ))
  st daily_newest "$ndate UTC"
  st daily_newest_age_h "$AGE_H"
  st daily_oldest "$odate"
  [ "$AGE_H" -le 26 ] || fail "শেষ দৈনিক ব্যাকআপ $AGE_H ঘণ্টা আগের (২৬ ঘণ্টার বেশি)"
  # গত ৭ দিনের (বা প্রথম ব্যাকআপের পর থেকে) কোন দিন বাদ গেছে — আজকের 21:30 UTC-র আগে আজকেরটা গোনা হয় না
  MISSING=""; CHECKED=0
  for i in 0 1 2 3 4 5 6 7; do
    d="$(date -u -d "@$((NOW - i * 86400))" +%Y-%m-%d)"
    [ "$i" -ne 0 ] || [ "$(date -u +%H%M)" -ge 2140 ] || continue
    [[ "$d" < "$odate" ]] && continue
    [ "$CHECKED" -lt 7 ] || break
    CHECKED=$((CHECKED + 1))
    if [ -z "$(find "$BACKUP_DIR" -maxdepth 1 -type f -regextype posix-extended -regex ".*/lifolo-${d//-/}-[0-9]{6}\.dump" -print -quit)" ]; then
      MISSING+="$d "
    fi
  done
  st daily_days_checked "$CHECKED"
  st daily_days_missing "${MISSING% }"
  [ -z "$MISSING" ] || fail "এই দিনগুলোর দৈনিক ব্যাকআপ নেই: ${MISSING% }"
fi
TIMER="$(systemctl is-active lifolo-backup.timer 2>/dev/null || true)"
st timer "$TIMER"
[ "$TIMER" = active ] || fail "lifolo-backup.timer চালু নেই"
st timer_last_result "$(systemctl show lifolo-backup.service -p Result --value 2>/dev/null || echo '?')"
st timer_next "$(systemctl show lifolo-backup.timer -p NextElapseUSecRealtime --value 2>/dev/null || echo '?')"
[ "$(systemctl show lifolo-backup.service -p Result --value 2>/dev/null)" = success ] || fail "শেষবারের দৈনিক ব্যাকআপ সফল হয়নি (journalctl -u lifolo-backup)"

# ------------------------------------------------------------------ শেষ
write_status
echo
cat "$STATUS_FILE"
[ "${#FAILS[@]}" -eq 0 ]
