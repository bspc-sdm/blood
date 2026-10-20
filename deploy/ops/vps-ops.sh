#!/usr/bin/env bash
# Lifolo — অপারেশনের মহড়া/পরীক্ষার VPS-অংশ (root হিসেবে)। GitHub Actions-এর "Ops: …" workflow-গুলো ssh দিয়ে চালায়;
# হাতেও চলে:  bash vps-ops.sh <কাজ> [আর্গুমেন্ট]
#
#   config                 টিউনিং-এর জন্য দরকারি সেটিং (JVM, Hikari, PostgreSQL, nginx, ulimit) — key=value
#   services               সার্ভিস/টাইমার চালু কি না, boot-এর সময় — key=value
#   sample-start DIR SEC   পেছনে মাপা শুরু (CPU, RAM, Java, DB সংযোগ, TCP) → DIR/metrics.csv
#   sample-stop DIR        মাপা বন্ধ
#   snapshot DIR           লোড টেস্টের আগে: ডেমো অ্যাকাউন্টের কোটা/অবস্থা (পরে ফেরত আনতে) আর t0
#   cleanup DIR            লোড টেস্টের পরে: টেস্টের বানানো সারি মোছা, কোটা/অবস্থা আগের মতো — শুধু demo-… uid
#   cleanup-since T0 R:N,… snapshot হারালে: T0-র পরের LOADTEST সারি মোছা, ডেমো রোলের আজকের কোটা N কমানো
#   window DIR             লোড টেস্টের সময়ের nginx/অ্যাপের ত্রুটি গোনা (snapshot-এর পর থেকে)
#   disk                   ডিস্ক, RAM, লগ ঘোরানো (journald, nginx, অ্যাপ) — Markdown
#   ssl                    সার্টিফিকেট, certbot renew --dry-run, nginx কোন নামে কোন সার্টিফিকেট — Markdown
#   cfports                Cloudflare-এর ঠিকানা থেকে খোলা সংযোগ: 443 না 80 (Flexible ধরা)
#   timers                 systemd timer আর JobRunr-এর কাজ গত ২৪ ঘণ্টায় চলেছে কি না — Markdown
#
# কখনো আসল (non-demo) অ্যাকাউন্টের কিছু বদলায় না: সব লেখা `uid LIKE 'demo-%'` দিয়ে বাঁধা।
# গোপন কিছু ছাপে না (পাসওয়ার্ড, চাবি, টোকেন)।
# রিপোর্টের কাজগুলো একটা লাইন ব্যর্থ হলেও বাকিটা দেখায় (set -e নেই); snapshot/cleanup নিজে ভুল ধরে থামে।
set -u

CMD="${1:-}"
shift || true
DB=lifolo
UNIT=lifolo-api.service

psqlq() { sudo -u postgres psql -X -q -At -d "$DB" "$@"; }
kv() { printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\n\r' | cut -c1-300)"; }
api_pid() { systemctl show -p MainPID --value "$UNIT" 2>/dev/null || echo 0; }

case "$CMD" in
# ------------------------------------------------------------------------------------------------ config
config)
  pid="$(api_pid)"
  kv execstart "$(systemctl show -p ExecStart --value "$UNIT" | sed -nE 's/.*argv\[\]=([^;]*);.*/\1/p' | sed -E 's#-jar [^ ]+#-jar <jar>#')"
  kv java_version "$(java -version 2>&1 | head -1)"
  if [ "${pid:-0}" != 0 ] && [ -r "/proc/$pid/cmdline" ]; then
    kv api_cmdline "$(tr '\0' ' ' < "/proc/$pid/cmdline" | sed -E 's#-jar [^ ]+#-jar <jar>#')"
    kv api_rss_mb "$(awk '/VmRSS/{printf "%d", $2/1024}' "/proc/$pid/status")"
    kv api_threads "$(awk '/Threads/{print $2}' "/proc/$pid/status")"
    kv api_nofile_limit "$(awk '/Max open files/{print $4}' "/proc/$pid/limits")"
    kv api_started "$(systemctl show -p ActiveEnterTimestamp --value "$UNIT")"
  fi
  kv api_restarts "$(systemctl show -p NRestarts --value "$UNIT")"
  # ঠিক কোন env আছে (মান নয় — শুধু নাম, আর পুলের সংখ্যা)
  pool="$(sed -nE 's/^DB_POOL_SIZE=([0-9]+)$/\1/p' /etc/lifolo/env /etc/lifolo/app.env 2>/dev/null | tail -1)"
  kv db_pool_size "${pool:-20 (ডিফল্ট, application.yml)}"
  kv virtual_threads "true (application.yml)"
  kv guest_rate_per_minute "$(sed -nE 's/^GUEST_RATE_PER_MINUTE=([0-9]+)$/\1/p' /etc/lifolo/app.env 2>/dev/null | tail -1)"
  for s in max_connections shared_buffers effective_cache_size work_mem maintenance_work_mem max_wal_size random_page_cost; do
    kv "pg_$s" "$(psqlq -c "SHOW $s" 2>/dev/null || echo '?')"
  done
  kv pg_version "$(psqlq -c 'SHOW server_version' 2>/dev/null || echo '?')"
  kv pg_db_size "$(psqlq -c "SELECT pg_size_pretty(pg_database_size('$DB'))" 2>/dev/null || echo '?')"
  kv nginx_worker_processes "$(nginx -T 2>/dev/null | sed -nE 's/^\s*worker_processes\s+([^;]+);.*/\1/p' | head -1)"
  kv nginx_worker_connections "$(nginx -T 2>/dev/null | sed -nE 's/^\s*worker_connections\s+([^;]+);.*/\1/p' | head -1)"
  kv nginx_upstream_keepalive "$(nginx -T 2>/dev/null | grep -cE '^\s*keepalive\s+[0-9]+;' || true)"
  kv nginx_limit_req "$(nginx -T 2>/dev/null | grep -cE '^\s*limit_req(_zone)?\s' || true)"
  kv nginx_nofile "$(awk '/Max open files/{print $4}' "/proc/$(pgrep -o nginx)/limits" 2>/dev/null || echo '?')"
  kv redis_maxmemory "$(REDISCLI_AUTH="$(sed -nE 's/^LIFOLO_REDIS_PASSWORD=(.*)$/\1/p' /etc/lifolo/env | tail -1)" redis-cli -h 127.0.0.1 config get maxmemory 2>/dev/null | tail -1)"
  kv cpus "$(nproc)"
  kv mem_total_mb "$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)"
  kv swap_total_mb "$(awk '/SwapTotal/{printf "%d", $2/1024}' /proc/meminfo)"
  kv somaxconn "$(sysctl -n net.core.somaxconn)"
  ;;

# ------------------------------------------------------------------------------------------------ services
services)
  kv boot_id "$(cat /proc/sys/kernel/random/boot_id)"
  kv booted_at "$(uptime -s)"
  kv uptime_s "$(awk '{printf "%d", $1}' /proc/uptime)"
  kv boot_time "$(systemd-analyze 2>/dev/null | head -1 | sed 's/^Startup finished in //')"
  for u in lifolo-api nginx postgresql postgresql@16-main redis-server ssh fail2ban ufw unattended-upgrades cron \
           lifolo-backup.timer certbot.timer logrotate.timer; do
    a="$(systemctl is-active "$u" 2>/dev/null || true)"
    e="$(systemctl is-enabled "$u" 2>/dev/null || true)"
    kv "unit_${u//[^a-z0-9]/_}" "$a/$e"
  done
  kv failed_units "$(systemctl --failed --no-legend --plain 2>/dev/null | awk '{print $1}' | tr '\n' ' ')"
  kv api_pid "$(api_pid)"
  kv api_restarts "$(systemctl show -p NRestarts --value "$UNIT")"
  kv api_active_since "$(systemctl show -p ActiveEnterTimestamp --value "$UNIT")"
  # বুটের কত পরে অ্যাপ চালু (Spring-এর "Started … in N seconds")
  kv api_spring_started "$(journalctl -b -u "$UNIT" --no-pager -o cat 2>/dev/null | grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' | tail -1)"
  kv api_health_local "$(curl -fsS -m 5 http://127.0.0.1:8080/api/health 2>/dev/null | grep -q '"ok":true' && echo ok || echo down)"
  kv pg_ready "$(pg_isready -q && echo ok || echo down)"
  kv redis_ping "$(REDISCLI_AUTH="$(sed -nE 's/^LIFOLO_REDIS_PASSWORD=(.*)$/\1/p' /etc/lifolo/env | tail -1)" redis-cli -h 127.0.0.1 ping 2>/dev/null || echo down)"
  kv swap_on "$(swapon --show --noheadings | awk '{print $1}' | tr '\n' ' ')"
  kv ufw "$(ufw status 2>/dev/null | head -1)"
  kv reboot_required "$([ -f /var/run/reboot-required ] && echo yes || echo no)"
  kv auto_reboot "$(apt-config dump 2>/dev/null | sed -nE 's/^Unattended-Upgrade::Automatic-Reboot "([^"]*)";/\1/p' | tail -1)"
  ;;

# ------------------------------------------------------------------------------------------------ sampler
sample-start)
  dir="$1"; sec="${2:-2}"
  install -d -m 0700 "$dir"
  cat > "$dir/sampler.sh" <<'EOS'
#!/usr/bin/env bash
# প্রতি SEC সেকেন্ডে এক লাইন: ts,cpu%,load1,mem_used_mb,mem_avail_mb,swap_used_mb,java_rss_mb,java_threads,java_cpu%,
#   pg_active,pg_idle,pg_idle_tx,pg_lock_wait,pg_total,tcp_443,tcp_8080
out="$1"; sec="$2"
ncpu=$(nproc); hz=$(getconf CLK_TCK)
echo "ts,cpu,load1,mem_used_mb,mem_avail_mb,swap_used_mb,java_rss_mb,java_threads,java_cpu,pg_active,pg_idle,pg_idle_tx,pg_lock_wait,pg_total,tcp_443,tcp_8080" > "$out"
read -r _ u n s i w q sq st _ < /proc/stat; pb=$((u+n+s+q+sq+st)); pt=$((pb+i+w))
pj=0
while :; do
  sleep "$sec"
  read -r _ u n s i w q sq st _ < /proc/stat; b=$((u+n+s+q+sq+st)); t=$((b+i+w))
  cpu=$(( (t-pt) > 0 ? 100*(b-pb)/(t-pt) : 0 )); pb=$b; pt=$t
  load1=$(cut -d' ' -f1 /proc/loadavg)
  read -r mt ma swt swf < <(awk '/MemTotal/{a=$2}/MemAvailable/{b=$2}/SwapTotal/{c=$2}/SwapFree/{d=$2}END{print a,b,c,d}' /proc/meminfo)
  pid=$(systemctl show -p MainPID --value lifolo-api 2>/dev/null || echo 0)
  rss=0; thr=0; jc=0
  if [ "${pid:-0}" != 0 ] && [ -r "/proc/$pid/stat" ]; then
    rss=$(awk '/VmRSS/{printf "%d", $2/1024}' "/proc/$pid/status"); thr=$(awk '/Threads/{print $2}' "/proc/$pid/status")
    j=$(awk '{print $14+$15}' "/proc/$pid/stat")
    if [ "$pj" -gt 0 ] && [ "$j" -ge "$pj" ]; then jc=$(( 100*(j-pj) / (hz*sec*ncpu) )); fi
    pj=$j
  fi
  pg=$(sudo -u postgres psql -X -At -F, -d lifolo -c "SELECT count(*) FILTER (WHERE state='active'), count(*) FILTER (WHERE state='idle'), count(*) FILTER (WHERE state LIKE 'idle in%'), count(*) FILTER (WHERE wait_event_type='Lock'), count(*) FROM pg_stat_activity WHERE datname='lifolo' AND pid <> pg_backend_pid()" 2>/dev/null || echo ",,,,")
  c443=$(ss -Htn state established '( sport = :443 )' 2>/dev/null | wc -l)
  c8080=$(ss -Htn state established '( dport = :8080 )' 2>/dev/null | wc -l)
  echo "$(date +%s),$cpu,$load1,$(( (mt-ma)/1024 )),$((ma/1024)),$(( (swt-swf)/1024 )),$rss,$thr,$jc,$pg,$c443,$c8080" >> "$out"
done
EOS
  chmod 0700 "$dir/sampler.sh"
  nohup setsid bash "$dir/sampler.sh" "$dir/metrics.csv" "$sec" > /dev/null 2>&1 < /dev/null &
  echo $! > "$dir/sampler.pid"
  kv sampler started
  ;;
sample-stop)
  dir="$1"
  if [ -f "$dir/sampler.pid" ]; then pkill -P "$(cat "$dir/sampler.pid")" 2>/dev/null || true; kill "$(cat "$dir/sampler.pid")" 2>/dev/null || true; fi
  pkill -f "$dir/sampler.sh" 2>/dev/null || true
  kv sampler stopped
  ;;

# ------------------------------------------------------------------------------------------------ snapshot / cleanup
snapshot)
  dir="$1"
  install -d -m 0755 "$dir"
  fail() { kv snapshot "failed: $1"; exit 1; }
  psqlq -c "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')" > "$dir/t0" || fail t0
  psqlq -c "COPY (SELECT uid, kind, day, count FROM user_quotas WHERE uid LIKE 'demo-%') TO STDOUT WITH CSV" > "$dir/quotas.csv" || fail quotas
  psqlq -c "COPY (SELECT uid, inbox_seen_at FROM user_state WHERE uid LIKE 'demo-%') TO STDOUT WITH CSV" > "$dir/user_state.csv" || fail user_state
  psqlq -c "COPY (SELECT uid, active_request_id, at FROM ambreq_patient_state WHERE uid LIKE 'demo-%') TO STDOUT WITH CSV" > "$dir/ambstate.csv" || fail ambstate
  chmod 0644 "$dir"/*.csv "$dir/t0"
  wc -l < /var/log/nginx/access.log > "$dir/access.lines" 2>/dev/null || echo 0 > "$dir/access.lines"
  wc -l < /var/log/nginx/error.log > "$dir/error.lines" 2>/dev/null || echo 0 > "$dir/error.lines"
  date '+%Y-%m-%d %H:%M:%S' > "$dir/journal.since"
  kv t0 "$(cat "$dir/t0")"
  kv api_pid "$(api_pid)"
  kv api_restarts "$(systemctl show -p NRestarts --value "$UNIT")"
  kv demo_active "$(psqlq -c "SELECT count(*) FROM demo_accounts" 2>/dev/null || echo '?')"
  kv quotas_rows "$(wc -l < "$dir/quotas.csv")"
  kv snapshot ok
  ;;
cleanup|cleanup-since)
  # cleanup DIR — snapshot থেকে ফেরত আনা (স্বাভাবিক পথ)
  # cleanup-since T0 ROLE:N,… — snapshot হারালে: T0-র পরের LOADTEST সারি মোছা, আর ডেমো রোলের আজকের রক্ত-অনুরোধের কোটা N কমানো
  if [ "$CMD" = cleanup ]; then
    dir="$1"
    [ -s "$dir/t0" ] || { kv cleanup "no snapshot"; exit 1; }
    t0="$(cat "$dir/t0")"
    fix=""
  else
    t0="$1"; fix="${2:-}"; dir=""
    [[ "$fix" =~ ^([a-z]+:[0-9]+,?)*$ ]] || { kv cleanup "bad role list"; exit 1; }
  fi
  [[ "$t0" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$ ]] || { kv cleanup "bad t0"; exit 1; }
  sql="$(mktemp)"
  cat > "$sql" <<EOF
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE ops_amb AS
  SELECT id FROM ambulance_requests WHERE uid LIKE 'demo-%' AND created_at >= '$t0' AND note LIKE 'LOADTEST%';
CREATE TEMP TABLE ops_counts (what text, n bigint);
WITH u AS (UPDATE ambreq_patient_state SET active_request_id = NULL
             WHERE uid LIKE 'demo-%' AND active_request_id IN (SELECT id FROM ops_amb) RETURNING 1)
  INSERT INTO ops_counts SELECT 'ambreq_state_cleared', count(*) FROM u;
WITH d AS (DELETE FROM inbox_items WHERE uid LIKE 'demo-%' AND created_at >= '$t0'
             AND (data->>'reqId' IN (SELECT id FROM ops_amb) OR data->>'requestId' IN (SELECT id FROM ops_amb)) RETURNING 1)
  INSERT INTO ops_counts SELECT 'inbox_items', count(*) FROM d;
WITH d AS (DELETE FROM ambulance_requests WHERE id IN (SELECT id FROM ops_amb) RETURNING 1)
  INSERT INTO ops_counts SELECT 'ambulance_requests', count(*) FROM d;
WITH d AS (DELETE FROM ambreq_create_log WHERE uid LIKE 'demo-%' AND created_at >= '$t0' RETURNING 1)
  INSERT INTO ops_counts SELECT 'ambreq_create_log', count(*) FROM d;
WITH d AS (DELETE FROM blood_requests WHERE requester_uid LIKE 'demo-%' AND created_at >= '$t0' AND hospital LIKE 'LOADTEST%' RETURNING 1)
  INSERT INTO ops_counts SELECT 'blood_requests', count(*) FROM d;
EOF
  if [ "$CMD" = cleanup-since ]; then
    for rc in ${fix//,/ }; do
      cat >> "$sql" <<EOF
WITH u AS (UPDATE user_quotas SET count = greatest(count - ${rc#*:}, 0)
             WHERE uid = 'demo-${rc%%:*}' AND kind = 'create' AND day = (now() AT TIME ZONE 'Asia/Dhaka')::date RETURNING 1)
  INSERT INTO ops_counts SELECT 'quota_${rc%%:*}', count(*) FROM u;
EOF
    done
  else
    cat >> "$sql" <<EOF
CREATE TEMP TABLE snap_q (uid text, kind text, day date, count int);
\copy snap_q FROM '$dir/quotas.csv' WITH CSV
WITH d AS (DELETE FROM user_quotas q WHERE q.uid LIKE 'demo-%'
             AND NOT EXISTS (SELECT 1 FROM snap_q s WHERE s.uid = q.uid AND s.kind = q.kind) RETURNING 1)
  INSERT INTO ops_counts SELECT 'user_quotas_removed', count(*) FROM d;
WITH u AS (UPDATE user_quotas q SET day = s.day, count = s.count FROM snap_q s
             WHERE q.uid = s.uid AND q.kind = s.kind AND (q.day, q.count) IS DISTINCT FROM (s.day, s.count) RETURNING 1)
  INSERT INTO ops_counts SELECT 'user_quotas_restored', count(*) FROM u;

CREATE TEMP TABLE snap_s (uid text, inbox_seen_at timestamptz);
\copy snap_s FROM '$dir/user_state.csv' WITH CSV
WITH u AS (UPDATE user_state t SET inbox_seen_at = s.inbox_seen_at FROM snap_s s
             WHERE t.uid = s.uid AND t.inbox_seen_at IS DISTINCT FROM s.inbox_seen_at RETURNING 1)
  INSERT INTO ops_counts SELECT 'user_state_restored', count(*) FROM u;
-- টেস্টে নতুন হওয়া user_state সারি (আগে ছিল না): শুধু inbox_seen_at মোছা — অন্য কলাম থাকলে থাকুক
WITH u AS (UPDATE user_state t SET inbox_seen_at = NULL
             WHERE t.uid LIKE 'demo-%' AND t.inbox_seen_at >= '$t0' AND NOT EXISTS (SELECT 1 FROM snap_s s WHERE s.uid = t.uid) RETURNING 1)
  INSERT INTO ops_counts SELECT 'user_state_cleared', count(*) FROM u;

CREATE TEMP TABLE snap_a (uid text, active_request_id text, at timestamptz);
\copy snap_a FROM '$dir/ambstate.csv' WITH CSV
WITH u AS (UPDATE ambreq_patient_state t SET active_request_id = s.active_request_id, at = s.at FROM snap_a s
             WHERE t.uid = s.uid AND (t.active_request_id, t.at) IS DISTINCT FROM (s.active_request_id, s.at) RETURNING 1)
  INSERT INTO ops_counts SELECT 'ambreq_state_restored', count(*) FROM u;
WITH d AS (DELETE FROM ambreq_patient_state t WHERE t.uid LIKE 'demo-%' AND t.at >= '$t0'
             AND NOT EXISTS (SELECT 1 FROM snap_a s WHERE s.uid = t.uid) RETURNING 1)
  INSERT INTO ops_counts SELECT 'ambreq_state_removed', count(*) FROM d;
EOF
  fi
  cat >> "$sql" <<'EOF'
SELECT what || '=' || n FROM ops_counts;
COMMIT;
EOF
  chmod 0644 "$sql"
  if out="$(sudo -u postgres psql -X -q -At -d "$DB" -f "$sql" 2>&1)"; then
    printf '%s\n' "$out" | sed -n 's/^\([a-z_]*=[0-9]*\)$/clean_\1/p'
    kv cleanup ok
  else
    kv cleanup "failed: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
  fi
  rm -f "$sql"
  # যা থেকে গেল (০ হওয়া উচিত)
  kv left_blood "$(psqlq -c "SELECT count(*) FROM blood_requests WHERE hospital LIKE 'LOADTEST%'")"
  kv left_amb "$(psqlq -c "SELECT count(*) FROM ambulance_requests WHERE note LIKE 'LOADTEST%'")"
  ;;
window)
  dir="$1"
  a0="$(cat "$dir/access.lines" 2>/dev/null || echo 0)"; e0="$(cat "$dir/error.lines" 2>/dev/null || echo 0)"
  since="$(cat "$dir/journal.since" 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')"
  # access.log ঘুরে গেলে (logrotate) গোনা কম আসতে পারে — সেটা লেখা হয়
  a1="$(wc -l < /var/log/nginx/access.log)"
  if [ "$a1" -ge "$a0" ]; then
    tail -n "+$((a0 + 1))" /var/log/nginx/access.log > "$dir/access.window"
  else
    cp /var/log/nginx/access.log "$dir/access.window"; kv access_rotated yes
  fi
  kv nginx_requests "$(wc -l < "$dir/access.window")"
  for c in 499 500 502 503 504 429; do kv "nginx_$c" "$(awk -v c="$c" '$9==c' "$dir/access.window" | wc -l)"; done
  kv nginx_5xx "$(awk '$9 ~ /^5/' "$dir/access.window" | wc -l)"
  e1="$(wc -l < /var/log/nginx/error.log)"
  if [ "$e1" -ge "$e0" ]; then tail -n "+$((e0 + 1))" /var/log/nginx/error.log > "$dir/error.window"; else cp /var/log/nginx/error.log "$dir/error.window"; fi
  kv nginx_errors "$(wc -l < "$dir/error.window")"
  kv nginx_upstream_timeouts "$(grep -c 'upstream timed out' "$dir/error.window" || true)"
  kv nginx_conn_refused "$(grep -c 'Connection refused' "$dir/error.window" || true)"
  kv nginx_worker_conns_short "$(grep -c 'worker_connections are not enough' "$dir/error.window" || true)"
  journalctl -u "$UNIT" --since "$since" --no-pager -o cat 2>/dev/null > "$dir/app.window" || true
  kv app_log_lines "$(wc -l < "$dir/app.window")"
  kv app_errors "$(grep -cE '\b(ERROR|Exception)\b' "$dir/app.window" || true)"
  kv app_hikari_timeouts "$(grep -c 'Connection is not available, request timed out' "$dir/app.window" || true)"
  kv app_oom "$(grep -c 'OutOfMemoryError' "$dir/app.window" || true)"
  # সবচেয়ে বেশি আসা ত্রুটির লাইন (ইমেইল/টোকেন ঢাকা)
  grep -E '\b(ERROR|WARN)\b' "$dir/app.window" | sed -E 's/^[0-9T:. -]+//; s/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/<email>/g; s/eyJ[A-Za-z0-9._-]+/<token>/g; s/[0-9a-f]{20,}/<id>/g' \
    | cut -c1-160 | sort | uniq -c | sort -rn | head -8 > "$dir/app.top" || true
  kv api_pid "$(api_pid)"
  kv api_restarts "$(systemctl show -p NRestarts --value "$UNIT")"
  ;;

# ------------------------------------------------------------------------------------------------ disk
disk)
  echo "### ডিস্ক"
  echo '```'
  df -h -x tmpfs -x devtmpfs -x squashfs -x overlay 2>/dev/null
  echo
  df -i / | tail -1 | awk '{print "inode: " $5 " ব্যবহৃত (" $3 " / " $2 ")"}'
  echo '```'
  echo "### RAM"
  echo '```'
  free -m
  echo
  echo "সবচেয়ে বেশি RAM (RSS, MB):"
  ps -eo rss,comm --sort=-rss | head -8 | awk 'NR==1{next}{printf "  %6d  %s\n", $1/1024, $2}'
  echo '```'
  echo "### কোথায় কত জায়গা"
  echo '```'
  for d in /var/log /var/log/journal /var/log/nginx /var/log/postgresql /var/log/redis /var/lib/postgresql /var/lib/postgresql/16/main/pg_wal \
           /var/backups/lifolo /opt/lifolo /var/www /var/lib/redis /root/lifolo-setup /root/lifolo-ops /var/cache/apt /tmp /swapfile; do
    [ -e "$d" ] && du -sh "$d" 2>/dev/null
  done
  echo
  echo "ডাটাবেস: $(psqlq -c "SELECT pg_size_pretty(pg_database_size('$DB'))" 2>/dev/null)"
  psqlq -F ' ' -c "SELECT pg_size_pretty(pg_total_relation_size(c.oid)), c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY pg_total_relation_size(c.oid) DESC LIMIT 6" 2>/dev/null | sed 's/^/  /'
  echo
  echo "ব্যাকআপ: $(find /var/backups/lifolo -maxdepth 1 -name 'lifolo-*.dump' 2>/dev/null | wc -l)টা ফাইল, সবচেয়ে নতুন: $(find /var/backups/lifolo -maxdepth 1 -name 'lifolo-*.dump' -printf '%TY-%Tm-%Td %TH:%TM  %s\n' 2>/dev/null | sort | tail -1 | awk '{printf "%s %s (%.1f MB)", $1, $2, $3/1048576}')"
  echo "jar: $(find /opt/lifolo/api -maxdepth 1 -name 'app.jar*' -printf '%f ' 2>/dev/null)"
  echo "ওয়েবের release: $(find /var/www -path '*/releases/*' -mindepth 3 -maxdepth 3 -type d 2>/dev/null | wc -l)টা"
  echo '```'
  echo "### লগ ঘোরানো (rotation)"
  echo '```'
  echo "journald: $(journalctl --disk-usage 2>/dev/null | sed 's/^Archived and active journals take up //')"
  jc="$(systemd-analyze cat-config systemd/journald.conf 2>/dev/null | grep -E '^\s*(SystemMaxUse|SystemKeepFree|MaxRetentionSec|Storage|SystemMaxFileSize)=' | tr '\n' ' ')"
  echo "journald সেটিং: ${jc:-ডিফল্ট (SystemMaxUse = ফাইলসিস্টেমের ১০%, সর্বোচ্চ ৪ GB)}"
  echo "journald-এর আসল সীমা: $(journalctl -b -u systemd-journald --no-pager -o cat 2>/dev/null | grep -oE 'System Journal .*max [0-9.]+[KMGT]?, [0-9.]+[KMGT]? free' | tail -1)"
  echo
  echo "logrotate.timer: $(systemctl is-active logrotate.timer 2>/dev/null)/$(systemctl is-enabled logrotate.timer 2>/dev/null); শেষ চলা: $(systemctl show logrotate.service -p ExecMainExitTimestamp --value 2>/dev/null); ফল: $(systemctl show logrotate.service -p Result --value 2>/dev/null)"
  for f in nginx postgresql-common redis-server fail2ban rsyslog; do
    [ -f "/etc/logrotate.d/$f" ] || { echo "/etc/logrotate.d/$f: নেই"; continue; }
    echo "/etc/logrotate.d/$f: $(grep -hoE '^\s*(daily|weekly|monthly|rotate [0-9]+|compress|maxsize [0-9A-Za-z]+|size [0-9A-Za-z]+)' "/etc/logrotate.d/$f" | tr -s ' \n' ' ')"
  done
  dbg="$(logrotate -d /etc/logrotate.conf 2>&1 || true)"
  echo "logrotate -d (শুকনো চালানো): ত্রুটি $(printf '%s\n' "$dbg" | grep -ciE '^error' || true)টা"
  printf '%s\n' "$dbg" | grep -iE '^error' | head -3 | sed 's/^/  /'
  echo
  pid="$(api_pid)"
  files=""
  if [ "${pid:-0}" != 0 ]; then files="$(find "/proc/$pid/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null | grep -E '\.log' | sort -u | tr '\n' ' ')"; fi
  echo "অ্যাপের লগ: stdout → journald (lifolo-api.service)। খোলা .log ফাইল: ${files:-নেই}"
  echo "অ্যাপের লগ, আজ: $(journalctl -u "$UNIT" --since today --no-pager -o cat 2>/dev/null | wc -l) লাইন; গত ২৪ ঘণ্টায় ERROR: $(journalctl -u "$UNIT" --since '-24h' --no-pager -o cat 2>/dev/null | grep -cE '\bERROR\b' || true)"
  echo "nginx access.log: $(du -h /var/log/nginx/access.log 2>/dev/null | cut -f1), ঘোরানো ফাইল: $(find /var/log/nginx -name 'access.log.*' 2>/dev/null | wc -l)টা"
  echo "postgres লগ: $(du -sh /var/log/postgresql 2>/dev/null | cut -f1)"
  echo "বড় ফাইল (>100 MB, /var /opt /root /home):"
  find /var /opt /root /home -xdev -type f -size +100M -printf '  %s %p\n' 2>/dev/null | sort -rn | head -8 | awk '{printf "  %.0f MB  %s\n", $1/1048576, $2}'
  echo '```'
  ;;

# ------------------------------------------------------------------------------------------------ ssl
ssl)
  echo "### সার্টিফিকেট (VPS-এ, Let's Encrypt)"
  echo '| নাম | মেয়াদ শেষ | বাকি দিন | কে দিয়েছে |'
  echo '|---|---|---|---|'
  now=$(date +%s)
  for d in /etc/letsencrypt/live/*/; do
    f="$d/fullchain.pem"; [ -f "$f" ] || continue
    end="$(openssl x509 -enddate -noout -in "$f" | cut -d= -f2)"
    left=$(( ( $(date -d "$end" +%s) - now ) / 86400 ))
    names="$(openssl x509 -noout -ext subjectAltName -in "$f" 2>/dev/null | grep -oE 'DNS:[^,]+' | sed 's/DNS://' | tr '\n' ' ')"
    iss="$(openssl x509 -noout -issuer -in "$f" | sed -E 's/.*O ?= ?([^,]+), CN ?= ?([^,]+).*/\1 \2/')"
    echo "| $names | $(date -u -d "$end" '+%Y-%m-%d') | $left | $iss |"
  done
  echo
  echo "### nginx যা দেখায় (SNI ধরে, 127.0.0.1:443)"
  echo '| নাম | মেয়াদ শেষ | যাচাই (নাম + chain) |'
  echo '|---|---|---|'
  for n in lifolo.com www.lifolo.com api.lifolo.com "${SSLIP:-}"; do
    [ -n "$n" ] || continue
    out="$(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -servername "$n" -verify_hostname "$n" -verify_return_error -CApath /etc/ssl/certs 2>&1 || true)"
    end="$(printf '%s' "$out" | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    vr="$(printf '%s' "$out" | sed -nE 's/^\s*Verify return code: ([0-9]+) \((.*)\)/\1 \2/p' | tail -1)"
    echo "| $n | ${end:+$(date -u -d "$end" '+%Y-%m-%d')} | ${vr:-সংযোগ হয়নি} |"
  done
  echo
  for sni in unknown.invalid ""; do
    if [ -n "$sni" ]; then o="$(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -servername "$sni" 2>&1)"; else o="$(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -noservername 2>&1)"; fi
    subj="$(printf '%s' "$o" | openssl x509 -noout -subject 2>/dev/null | sed 's/^subject=//')"
    echo "$([ -n "$sni" ] && echo 'ভুল নাম (SNI unknown.invalid)' || echo 'SNI ছাড়া (IP দিয়ে স্ক্যান)'): $([ -n "$subj" ] && echo "সার্টিফিকেট দেখায় — $subj ⚠️ (IP থেকে নাম চেনা যায়)" || echo 'handshake বাতিল (ভালো)')"
  done
  echo "default_server (443): $(nginx -T 2>/dev/null | grep -cE 'listen\s+(\[::\]:)?443\s.*default_server') · ssl_reject_handshake: $(nginx -T 2>/dev/null | grep -cE '^\s*ssl_reject_handshake\s+on')"
  echo "TLS 1.1: $(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -servername api.lifolo.com -tls1_1 2>&1 | grep -qE '^\s*Protocol\s*:\s*TLSv1\.1' && echo 'চলে (বন্ধ করা উচিত)' || echo 'চলে না (ভালো)') · TLS 1.3: $(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -servername api.lifolo.com -tls1_3 2>&1 | grep -qE 'TLSv1\.3' && echo চলে || echo 'চলে না')"
  echo
  echo "### certbot"
  echo '```'
  echo "certbot.timer: $(systemctl is-active certbot.timer)/$(systemctl is-enabled certbot.timer 2>/dev/null); শেষ চলা: $(systemctl show certbot.service -p ExecMainExitTimestamp --value 2>/dev/null); ফল: $(systemctl show certbot.service -p Result --value 2>/dev/null)"
  echo "পরের চলা: $(systemctl show certbot.timer -p NextElapseUSecRealtime --value 2>/dev/null)"
  for r in /etc/letsencrypt/renewal/*.conf; do
    [ -f "$r" ] || continue
    echo "$(basename "$r" .conf): authenticator=$(sed -nE 's/^authenticator = (.*)/\1/p' "$r") webroot=$(sed -nE 's/^webroot_path = (.*)/\1/p' "$r" | tr -d ',') deploy-hook=$(sed -nE 's/^renew_hook = (.*)/\1/p' "$r")"
  done
  echo
  t0=$(date +%s)
  if dr="$(timeout 600 certbot renew --dry-run --no-random-sleep-on-renew 2>&1)"; then rc=0; else rc=$?; fi
  echo "certbot renew --dry-run: exit $rc ($(( $(date +%s) - t0 )) সেকেন্ড)"
  printf '%s\n' "$dr" | grep -E 'Congratulations|simulated renewals|fail|Failed|error|Error|/etc/letsencrypt/live/.*\((success|failure)\)|Challenge failed|Detail:' | head -12 | sed 's/^/  /'
  echo '```'
  ;;

# ------------------------------------------------------------------------------------------------ cfports
# Cloudflare-এর ঠিকানা থেকে এখন খোলা সংযোগ — কোন পোর্টে (443 = Full/Full (strict), 80 = Flexible)
cfports)
  python3 - <<'PY'
import ipaddress, re, subprocess
nets = []
for l in open("/etc/nginx/conf.d/lifolo-cloudflare-realip.conf"):
    m = re.match(r"set_real_ip_from ([0-9a-f.:/]+);", l.strip())
    if m:
        nets.append(ipaddress.ip_network(m.group(1)))
out = subprocess.run(["ss", "-Htn", "state", "established"], capture_output=True, text=True).stdout
c = {"443": 0, "80": 0}
for line in out.splitlines():
    p = line.split()
    if len(p) < 4:
        continue
    lport = p[2].rsplit(":", 1)[1]
    ip = p[3].rsplit(":", 1)[0].strip("[]")
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        continue
    if getattr(a, "ipv4_mapped", None):
        a = a.ipv4_mapped
    if lport in c and any(a in n for n in nets):
        c[lport] += 1
print("cf_conns_443=%d" % c["443"])
print("cf_conns_80=%d" % c["80"])
PY
  # origin-এর 80: শুধু https-এ পাঠায় (301) হলে Flexible-এ Cloudflare দিয়ে 200 আসা সম্ভব নয় → 200 মানে Full/Full (strict)
  kv http80_api "$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Host: api.lifolo.com' http://127.0.0.1/api/health)"
  kv http80_web "$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Host: lifolo.com' http://127.0.0.1/api/health)"
  ;;

# ------------------------------------------------------------------------------------------------ timers
timers)
  echo "### systemd timer"
  echo '```'
  systemctl list-timers --all --no-pager 2>/dev/null | sed -E 's/ +/ /g' | head -30
  echo '```'
  echo
  echo '| কাজ | শেষ চলা | ফল | গত ২৪ ঘণ্টায়? |'
  echo '|---|---|---|---|'
  now=$(date +%s)
  for s in lifolo-backup certbot logrotate apt-daily apt-daily-upgrade man-db fstrim e2scrub_all dpkg-db-backup sysstat-collect; do
    systemctl cat "$s.service" >/dev/null 2>&1 || continue
    last="$(systemctl show "$s.service" -p ExecMainStartTimestamp --value 2>/dev/null)"
    res="$(systemctl show "$s.service" -p Result --value 2>/dev/null)"
    code="$(systemctl show "$s.service" -p ExecMainStatus --value 2>/dev/null)"
    if [ -z "$last" ] || [ "$last" = n/a ]; then
      # এই boot-এ চলেনি — journal থেকে শেষবার
      last="$(journalctl -u "$s.service" --no-pager -o short-iso -n 1 --grep 'Finished|Deactivated|Succeeded' 2>/dev/null | awk '/^[0-9]{4}-/{print $1}' | tail -1)"
    fi
    ok="?"
    [[ "$last" =~ [0-9]{4}-[0-9]{2}-[0-9]{2} ]] || last=""
    if [ -n "$last" ] && t=$(date -d "$last" +%s 2>/dev/null); then ok=$([ $((now - t)) -le 90000 ] && echo হ্যাঁ || echo "না ($(( (now - t) / 3600 )) ঘণ্টা আগে)"); fi
    echo "| $s | ${last:-চলেনি} | $res (exit ${code:-?}) | $ok |"
  done
  echo
  echo "### দৈনিক ব্যাকআপ"
  echo '```'
  find /var/backups/lifolo -maxdepth 1 -name 'lifolo-*.dump' -printf '%TY-%Tm-%Td %TH:%TM UTC  %s  %f\n' 2>/dev/null | sort | tail -4 | awk '{printf "%s %s %s  %.1f MB  %s\n", $1, $2, $3, $4/1048576, $5}'
  newest="$(find /var/backups/lifolo -maxdepth 1 -name 'lifolo-*.dump' -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1)"
  echo "সবচেয়ে নতুন ব্যাকআপ: $([ -n "$newest" ] && echo "$(( (now - newest) / 3600 )) ঘণ্টা আগে" || echo 'নেই')"
  journalctl -u lifolo-backup.service --since '-48h' --no-pager -o cat 2>/dev/null | grep -E 'backup ok|error|failed' | tail -3 | sed -E 's#/var/backups/lifolo/##'
  echo '```'
  echo
  echo "### cron"
  echo '```'
  (crontab -l 2>/dev/null | grep -v '^#' | grep . || echo "root crontab: খালি")
  ls /etc/cron.d 2>/dev/null | tr '\n' ' '; echo
  echo '```'
  echo
  echo "### JobRunr (অ্যাপের ভেতরের নির্ধারিত কাজ)"
  tables="$(psqlq -c "SELECT string_agg(table_name, ' ' ORDER BY table_name) FROM information_schema.tables WHERE table_name LIKE 'jobrunr%'")"
  if [ -z "$tables" ]; then
    echo "JobRunr-এর টেবিল নেই"
  else
    echo '| recurring কাজ | cron | প্রথম সফল (UTC, যা আছে) | শেষ সফল (UTC) | গত ২৪ ঘণ্টায় সফল | গত ২৪ ঘণ্টায় ব্যর্থ |'
    echo '|---|---|---|---|---|---|'
    psqlq -F ' | ' -c "
      SELECT '| ' || r.id,
             coalesce(substring(r.jobasjson from '\"scheduleExpression\":\"([^\"]+)\"'), '?'),
             coalesce(to_char(min(j.updatedat) FILTER (WHERE j.state = 'SUCCEEDED'), 'YYYY-MM-DD HH24:MI'), '—'),
             coalesce(to_char(max(j.updatedat) FILTER (WHERE j.state = 'SUCCEEDED'), 'YYYY-MM-DD HH24:MI'), '—'),
             count(*) FILTER (WHERE j.state = 'SUCCEEDED' AND j.updatedat > (now() AT TIME ZONE 'UTC') - interval '24 hours'),
             count(*) FILTER (WHERE j.state = 'FAILED' AND j.updatedat > (now() AT TIME ZONE 'UTC') - interval '24 hours') || ' |'
      FROM jobrunr_recurring_jobs r LEFT JOIN jobrunr_jobs j ON j.recurringjobid = r.id
      GROUP BY r.id, r.jobasjson ORDER BY r.id" 2>&1
    echo
    echo "সব কাজের অবস্থা (গত ২৪ ঘণ্টা): $(psqlq -c "SELECT string_agg(state || '=' || n, ', ') FROM (SELECT state, count(*) n FROM jobrunr_jobs WHERE updatedat > (now() AT TIME ZONE 'UTC') - interval '24 hours' GROUP BY state ORDER BY state) x" 2>/dev/null)"
    echo "চালু JobRunr সার্ভার: $(psqlq -c "SELECT count(*) || ' (শেষ সাড়া ' || to_char(max(lastheartbeat), 'HH24:MI:SS') || ' UTC)' FROM jobrunr_backgroundjobservers" 2>/dev/null)"
    echo
    echo "দৈনিক কাজের (daily-maintenance) লগ, গত ২৬ ঘণ্টা:"
    echo '```'
    journalctl -u "$UNIT" --since '-26h' --no-pager -o short-iso 2>/dev/null | grep -E 'daily task|Daily maintenance|daily-maintenance' | tail -6 | cut -c1-200 || true
    echo "(ত্রুটির লাইন: $(journalctl -u "$UNIT" --since '-26h' --no-pager -o cat 2>/dev/null | grep -c 'daily task .* failed' || true)টা)"
    echo '```'
  fi
  ;;
*)
  echo "ব্যবহার: vps-ops.sh config|services|sample-start DIR SEC|sample-stop DIR|snapshot DIR|cleanup DIR|window DIR|disk|ssl|timers" >&2
  exit 2
  ;;
esac
