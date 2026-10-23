#!/usr/bin/env bash
# Lifolo — Spring Boot API বসানো/আপডেট (VPS-এ root হিসেবে)। সাধারণত GitHub Actions-এর "Deploy backend"
# (.github/workflows/deploy-backend.yml) চালায়; আগে "Setup VPS (base)" (setup-base.sh) হয়ে থাকতে হবে।
#
#   bash deploy-backend.sh --domain 169-58-58-176.sslip.io --jar /root/lifolo-setup/app.jar.new --sha256 <hash> \
#        --provided /root/lifolo-setup/provided.env [--api-domain api.lifolo.com] [--web-domain lifolo.com]
#        [--demo keep|smoke <owner-uid> <owner-email>] [--ci]
#
# --web-domain: ওয়েবসাইটের নাম (deploy-web.sh বসায়) — DNS-এ এই VPS-এ গেলে PUBLIC_BASE_URL (শেয়ার লিংক, QR) = https://<নাম>।
# --demo keep: ডেমো না থাকলে বানানো আর রেখে দেওয়া (থাকলে হাত দেওয়া হয় না); smoke: বানানো → মোছা (পুরোনো পরীক্ষা)।
#   DEMO_KEEP_FIREBASE_USERS (ডিফল্ট true, provided-এ false দিলে বন্ধ): Vercel চালু থাকা পর্যন্ত Firebase-এর ডেমো অ্যাকাউন্ট
#   (Node-এর ডেমোও এগুলো দিয়েই লগইন করে) এই অ্যাপ কখনো মোছে/বদলায় না — "ডেমো মুছুন" শুধু এখানকার ডাটাবেস মোছে।
#
# --api-domain: আরেকটা নাম (যেমন api.lifolo.com) — DNS-এ এই VPS-এর IP-তে গেলে তবেই Let's Encrypt সার্টিফিকেট আর
#   nginx-এর আলাদা সাইট (একই পথগুলো + /healthz); না গেলে কিছু বদলায় না (sslip.io-র ঠিকানা সবসময় চলে)।
#
# --provided: GitHub secret থেকে আসা মান (KEY=value, এক লাইনে একটা; FIREBASE_SERVICE_ACCOUNT base64-এ)।
#   চেনা নাম: FIREBASE_SERVICE_ACCOUNT ADMIN_EMAILS SES_REGION SES_ACCESS_KEY_ID SES_SECRET_ACCESS_KEY SES_FROM
#   R2_ACCOUNT_ID R2_ENDPOINT R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET SENTRY_DSN PUBLIC_BASE_URL SMS_URL_TEMPLATE
#
# যা করে (blue/green, কোনো বিরতি ছাড়া — বাংলা গাইড: deploy/ZERO-DOWNTIME.md):
#   1) jar → /opt/lifolo/api/releases/app-<sha12>.jar (version-সহ, শেষ ৫টা থাকে), sha256 মিলিয়ে
#   2) /etc/lifolo/app.env (0640 root:lifolo) — অ্যাপের সেটিং: Redis-এর ঠিকানা (/etc/lifolo/env-এর পাসওয়ার্ডে), প্রথমবার
#      বানানো স্থায়ী চাবি (CRON_SECRET, WEB_SESSION_SECRET, EMAIL_CODE_SECRET), আর --provided-এর মান। ডাটাবেস
#      (SPRING_DATASOURCE_*) /etc/lifolo/env থেকেই আসে। Firebase না থাকলে PUSH_MODE=log (পুশ বন্ধ)।
#   3) systemd: দুটো instance lifolo-api@8080 / lifolo-api@8081 (template lifolo-api@.service + drop-in → app.env-ও পড়ে)।
#      যেটা চলছে না সেখানে নতুন release চালু (আগেরটা চলতেই থাকে; নতুনটার JobRunr "hold"-এ) → Flyway চালুর সময় চলে →
#      /api/health-এর অপেক্ষা। না এলে: নতুনটা বন্ধ, আগেরটা যেমন ছিল তেমনই চলে (nginx-এ কিছু বদলায় না) → deploy ব্যর্থ।
#   4) switch: nginx-এর upstream lifolo_api → নতুন পোর্ট (graceful reload) → JobRunr আগেরটায় থামিয়ে নতুনটায় চালু →
#      আগেরটা ২০ s চলমান অনুরোধ শেষ করে (drain) → বন্ধ। /etc/lifolo/api-port = এখন কোনটা; রিবুটে শুধু ওটাই চালু।
#      switch-এর সময় এই মেশিন থেকে /api/health আর একটা আসল API action বারবার — কয়টা 5xx (switch_probe, লক্ষ্য ০)।
#      nginx: /api/, /r/, /rx/, /shop/, /.well-known/ → upstream lifolo_api (/healthz যেমন ছিল); 127.0.0.1:8088 = ভেতরের
#      স্থির ঠিকানা (cron, Sentry test, R2, LiveKit webhook); JobRunr dashboard বন্ধ
#   6) পরীক্ষা (এই মেশিন থেকেই, nginx-এর https দিয়ে) — ফল /var/lib/lifolo/deploy-status-এ (গোপন কিছু নয়)
# কোনো গোপন মান ছাপা হয় না।
set -euo pipefail

ENV_FILE=/etc/lifolo/env
APP_ENV=/etc/lifolo/app.env
API_DIR=/opt/lifolo/api
# app.jar = live release-এ symlink (পুরোনো স্ক্রিপ্ট/হাতের কাজের জন্য); instance-গুলো চালায় app-<port>.jar
API_JAR=$API_DIR/app.jar
RELEASES=$API_DIR/releases
APP_USER=lifolo
STATUS=/var/lib/lifolo/deploy-status
SNIPPET=/etc/nginx/snippets/lifolo-api.conf
UNIT_TPL=/etc/systemd/system/lifolo-api@.service
DROPIN_DIR=/etc/systemd/system/lifolo-api@.service.d
# blue/green-এর আগের এক-instance সার্ভিস (port 8080) — প্রথম blue/green deploy এটা থেকে সরে এসে সরিয়ে রাখে
LEGACY_UNIT=lifolo-api.service
LEGACY_FILE=/etc/systemd/system/lifolo-api.service
LEGACY_DROPINS=/etc/systemd/system/lifolo-api.service.d
ACTIVE_FILE=/etc/lifolo/api-port
RUN_DIR=/run/lifolo
LOCAL_PORT=8088
DRAIN_SECONDS="${LIFOLO_DRAIN_SECONDS:-20}"
MIN_FREE_MB="${LIFOLO_MIN_FREE_MB:-1600}"
[[ "$DRAIN_SECONDS" =~ ^[0-9]{1,3}$ ]] || DRAIN_SECONDS=20
[[ "$MIN_FREE_MB" =~ ^[0-9]{1,6}$ ]] || MIN_FREE_MB=1600

DOMAIN="" JAR="" SHA="" PROVIDED="" OWNER_UID="" OWNER_EMAIL="" CI_MODE=no API_DOMAIN="" WEB_DOMAIN="" DEMO_MODE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --sha256) SHA="$2"; shift 2 ;;
    --provided) PROVIDED="$2"; shift 2 ;;
    --demo-smoke) DEMO_MODE=smoke; OWNER_UID="$2"; OWNER_EMAIL="$3"; shift 3 ;;
    --demo) DEMO_MODE="$2"; OWNER_UID="$3"; OWNER_EMAIL="$4"; shift 4 ;;
    --web-domain) WEB_DOMAIN="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"; shift 2 ;;
    --api-domain) API_DOMAIN="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"; shift 2 ;;
    --ci) CI_MODE=yes; shift ;;
    *) echo "অচেনা আর্গুমেন্ট: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'XX %s\n' "$*" >&2; st fatal "$*"; exit 1; }
install -d -m 0755 /var/lib/lifolo
: > "$STATUS"
chmod 0644 "$STATUS"
# ফলাফল: key=value (workflow পড়ে বাংলা সারাংশ বানায়)
st() { printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\n\r' | cut -c1-300)" >> "$STATUS"; }
mask() { if [ "$CI_MODE" = yes ] && [ -n "${1:-}" ]; then printf '::add-mask::%s\n' "$1"; fi; }

[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"
[[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || die "ডোমেইন ঠিক নেই: $DOMAIN"
[ -f "$ENV_FILE" ] || die "$ENV_FILE নেই — আগে Actions → 'Setup VPS (base)' চালান"
id "$APP_USER" >/dev/null 2>&1 || die "$APP_USER ব্যবহারকারী নেই — আগে 'Setup VPS (base)' চালান"
command -v java >/dev/null || die "java নেই — আগে 'Setup VPS (base)' চালান"
[ -f "$JAR" ] || die "jar পৌঁছায়নি ($JAR)"
[ -f "$PROVIDED" ] || die "provided env পৌঁছায়নি"

env_get() { sed -nE "s/^$1=(.*)$/\1/p" "$2" 2>/dev/null | tail -1; }

# ---------------------------------------------------------------- blue/green-এর সহায়ক
unit_of() { printf 'lifolo-api@%s.service' "$1"; }

# দুটো instance-এর template (setup-base.sh-ও একই লেখা লেখে — দুটো মিলিয়ে রাখুন)। instance = পোর্ট; jar =
# /opt/lifolo/api/app-<port>.jar (releases/-এর একটা version-এ symlink)। JVM-এর সেটিং LIFOLO_JAVA_OPTS-এ
# (tune-vps.sh-এর drop-in 20-tuning.conf বদলায়)। -Dserver.port /etc/lifolo/env-এর SERVER_PORT-এর ওপরে জেতে।
unit_template() {
  cat <<EOF
# Lifolo API (Kotlin + Spring Boot) — blue/green: lifolo-api@8080 আর lifolo-api@8081, একসময়ে একটাই live
# (nginx-এর upstream lifolo_api, /etc/lifolo/api-port)। deploy-backend.sh / setup-base.sh লেখে — হাতে বদলাবেন না।
# এখন কোনটা: lifolo-api unit · লগ: journalctl -u 'lifolo-api@*'
[Unit]
Description=Lifolo API (Spring Boot) on 127.0.0.1:%i
After=network-online.target postgresql.service redis-server.service
Wants=network-online.target
ConditionPathExists=$API_DIR/app-%i.jar

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$API_DIR
EnvironmentFile=$ENV_FILE
Environment="LIFOLO_JAVA_OPTS=-Xmx1g -XX:+ExitOnOutOfMemoryError"
ExecStart=/usr/bin/java \$LIFOLO_JAVA_OPTS -Dserver.port=%i -Dserver.address=127.0.0.1 -jar $API_DIR/app-%i.jar
Restart=always
RestartSec=5
SuccessExitStatus=143
# Spring-এর graceful shutdown ২০ s (application.yml) + JobRunr থামা — তার ওপরে
TimeoutStopSec=60
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectHome=yes
ProtectKernelTunables=yes
ProtectControlGroups=yes

[Install]
WantedBy=multi-user.target
EOF
}

# /usr/local/sbin/lifolo-api — ops স্ক্রিপ্ট আর workflow-র জন্য: কোন instance চালু
helper_script() {
  cat <<'EOF'
#!/bin/sh
# Lifolo — deploy-backend.sh বসায়। blue/green-এর এখনকার live instance:
#   lifolo-api unit    lifolo-api@8080.service | lifolo-api@8081.service (পুরোনো এক-instance হলে lifolo-api.service)
#   lifolo-api port    8080 | 8081
#   lifolo-api url     http://127.0.0.1:<port> (সরাসরি অ্যাপ)
#   lifolo-api local   http://127.0.0.1:8088 (nginx → যেটা live; /api/cron/* সহ — cron, Sentry test, R2 এখান দিয়ে)
f=/etc/lifolo/api-port
p="$(cat "$f" 2>/dev/null)"
case "$p" in 8080|8081) ;; *) p=8080 ;; esac
case "${1:-unit}" in
  unit) if [ -f "$f" ]; then echo "lifolo-api@$p.service"; else echo lifolo-api.service; fi ;;
  port) echo "$p" ;;
  url) echo "http://127.0.0.1:$p" ;;
  local) if [ -f /etc/nginx/conf.d/lifolo-api-local.conf ]; then echo http://127.0.0.1:8088; else echo "http://127.0.0.1:$p"; fi ;;
  *) echo "lifolo-api unit|port|url|local" >&2; exit 2 ;;
esac
EOF
}

health_ok() { curl -fsS -m 4 "http://127.0.0.1:$1/api/health" 2>/dev/null | grep -q '"ok":true'; }
# wait_health <port> <unit> <চেষ্টা> — ২ s পরপর; systemd ৩ বার restart করলে (crash-loop) আগেই থামা
wait_health() {
  local i n0 n
  n0="$(systemctl show -p NRestarts --value "$2" 2>/dev/null || echo 0)"
  for i in $(seq 1 "$3"); do
    if health_ok "$1"; then return 0; fi
    n="$(systemctl show -p NRestarts --value "$2" 2>/dev/null || echo 0)"
    if [ $(( ${n:-0} - ${n0:-0} )) -ge 3 ]; then return 1; fi
    if [ "$i" -gt 20 ] && [ "$(systemctl show -p ActiveState --value "$2" 2>/dev/null)" = failed ]; then return 1; fi
    sleep 2
  done
  return 1
}
# অ্যাপ না চললে লগের প্রথম কারণ (Exception-এর লাইন; @-যুক্ত অংশ ঢাকা) — status-এ, যাতে লগ না খুলেই বোঝা যায়
app_error() {
  journalctl -u "$1" --since "$started_at" --no-pager -o cat 2>/dev/null \
    | grep -E '(Exception|Error)(:| )' | grep -vE '^\s+at ' | head -3 \
    | sed -E 's#[A-Za-z0-9._%+:/-]+@[A-Za-z0-9._:/-]+#<…@…>#g' | tr '\n' ' ' | cut -c1-400
}

json_field() { python3 -c "import json,sys
try: print(json.load(sys.stdin).get('$1') or '')
except Exception: print('')" 2>/dev/null; }
# JobRunr-এর হাতবদল (jobs/JobsGate.kt): POST /api/cron/jobs?do=status|start|stop, CRON_SECRET দিয়ে (চাবি কমান্ড লাইনে নয়)
jobs_call() {
  local hdr out
  hdr="$(mktemp)"
  printf 'Authorization: Bearer %s\n' "$(env_get CRON_SECRET "$APP_ENV")" > "$hdr"
  out="$(curl -sS -m 40 -X POST -H "@$hdr" "http://127.0.0.1:$1/api/cron/jobs?do=$2" 2>/dev/null || true)"
  rm -f "$hdr"
  printf '%s' "$out"
}
jobs_start_new() {
  local i
  for i in 1 2 3 4 5; do
    [ "$(jobs_call "$NEW_PORT" start | json_field server)" = running ] && return 0
    sleep 2
  done
  return 1
}

# switch-এর সময়টুকু ব্যবহারকারীর মতো অনুরোধ (nginx-এর https দিয়ে, এই মেশিন থেকে — IP-সীমার বাইরে): /api/health আর একটা
# আসল API action (অতিথির "features", ডাটাবেস পড়ে)। ফল: কয়টা 5xx (লক্ষ্য ০)।
PROBE_DIR="" PROBE_PID=""
probe_start() {
  PROBE_DIR="$(mktemp -d)"
  (
    end=$((SECONDS + 900))
    while [ ! -f "$PROBE_DIR/stop" ] && [ "$SECONDS" -lt "$end" ]; do
      # সংযোগ না হলে curl ব্যর্থ ফেরে কিন্তু -w তবুও "000" লেখে — set -e-তে লুপ যেন না থামে
      curl -sk -o /dev/null -m 15 -w '%{http_code}\n' --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/api/health" >> "$PROBE_DIR/codes" 2>/dev/null || true
      curl -sk -o /dev/null -m 15 -w '%{http_code}\n' --resolve "$DOMAIN:443:127.0.0.1" -X POST -H 'content-type: application/json' \
        --data '{"action":"features","lang":"bn"}' "https://$DOMAIN/api/community" >> "$PROBE_DIR/codes" 2>/dev/null || true
      sleep 0.4
    done
  ) &
  PROBE_PID=$!
}
probe_stop() {
  [ -n "$PROBE_DIR" ] || return 0
  touch "$PROBE_DIR/stop"
  wait "$PROBE_PID" 2>/dev/null || true
  local total n2 n5 n0 other
  total="$(grep -c . "$PROBE_DIR/codes" 2>/dev/null || true)"
  n2="$(grep -c '^2' "$PROBE_DIR/codes" 2>/dev/null || true)"
  n5="$(grep -c '^5' "$PROBE_DIR/codes" 2>/dev/null || true)"
  n0="$(grep -cx '000' "$PROBE_DIR/codes" 2>/dev/null || true)"
  other="$(grep -vE '^(2|5|000)' "$PROBE_DIR/codes" 2>/dev/null | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}' || true)"
  st switch_probe "requests=${total:-0} 2xx=${n2:-0} 5xx=${n5:-0} conn_errors=${n0:-0}${other:+ other: $other}"
  st switch_5xx "${n5:-0}"
  rm -rf "$PROBE_DIR"
  PROBE_DIR=""
}

# ---------------------------------------------------------------- nginx (switch-এর অংশ)
# http-স্তরের অংশ: upstream (অ্যাপের সাথে খোলা সংযোগ রেখে দেওয়া — প্রতি অনুরোধে নতুন TCP নয়) আর /api/-এ IP-প্রতি সীমা।
# Tomcat ২০-৬০ s অলস সংযোগ বন্ধ করে, তাই nginx-এর দিকে 15 s (দুজন একসাথে বন্ধ করলে POST-এ 502 হতে পারত)।
# upstream: live পোর্ট + অন্যটা "backup" — শুধু live-এ সংযোগই না হলে (connect error/timeout, 502) অনুরোধ অন্যটায় যায়
# (drain-এর ২০ s আগেরটা তখনো চলে; পরে ওটা বন্ধ, তখন backup-এ গেলেও সাথে সাথে "refused" → JSON 503)। max_fails=0: একটা
# ধীর অনুরোধের timeout-এ live পোর্টকে "অচল" ধরা হয় না (নইলে ১০ s সব অনুরোধ backup-এ যেত)। POST একবার পাঠানো হয়ে গেলে
# nginx আর দ্বিতীয়বার পাঠায় না (non_idempotent নেই) — দুবার লেখা হয় না।
# সীমার IP = আসল দর্শক: Cloudflare-এর পেছনে CF-Connecting-IP (tune-vps.sh-এর realip); এই মেশিন (127.0.0.1) আর
# CI-র সাময়িক ঠিকানা (lifolo-tune ci-open → limit-exempt.conf) সীমার বাইরে।
UPSTREAM_CONF=/etc/nginx/conf.d/lifolo-api-upstream.conf
LOCAL_CONF=/etc/nginx/conf.d/lifolo-api-local.conf
EXEMPT=/etc/nginx/lifolo/limit-exempt.conf
REALIP=/etc/nginx/conf.d/lifolo-cloudflare-realip.conf
# অ্যাপ বন্ধ/চালু হওয়ার সময় (crash-এর পরে restart, বা দুটো instance-ই অচল) nginx-এর নিজের 502/503/504 আর সীমার 429: HTML
# নয়, ছোট JSON + Retry-After — অ্যাপ "error" লেখাটাই দেখায় ("আবার চেষ্টা করুন"; ServerApi.kt / web-vue api.ts)। অ্যাপের নিজের
# উত্তর (যেমন কোড-সীমার 429) যেমন আছে তেমনই যায়।
MSG_DOWN='সার্ভার এই মুহূর্তে চালু হচ্ছে — কয়েক সেকেন্ড পর আবার চেষ্টা করুন। (Server is restarting — please try again in a few seconds.)'
MSG_LIMIT='অনেক বেশি অনুরোধ — একটু থেমে আবার চেষ্টা করুন। (Too many requests — please wait a moment.)'
loc() {
  echo "location $1 {"
  echo "    proxy_pass http://lifolo_api;"
  echo "    proxy_http_version 1.1;"
  echo "    proxy_set_header Connection \"\";"
  echo "    proxy_set_header Host \$host;"
  echo "    proxy_set_header X-Real-IP \$remote_addr;"
  echo "    proxy_set_header X-Forwarded-For \$remote_addr;"
  echo "    proxy_set_header X-Forwarded-Proto \$scheme;"
  echo "    proxy_connect_timeout 5s;"
  echo "    proxy_read_timeout ${2:-60s};"
  echo "    proxy_next_upstream error timeout http_502;"
  echo "    proxy_next_upstream_tries 2;"
  echo "    proxy_next_upstream_timeout 10s;"
  echo "    client_max_body_size 10m;"
  [ -z "${3:-}" ] || printf '%s\n' "$3"
  echo "}"
}
json_loc() { # $1 নাম, $2 HTTP কোড, $3 Retry-After, $4 লেখা, $5 code
  echo "location $1 {"
  echo "    default_type \"application/json; charset=utf-8\";"
  echo "    add_header Retry-After $3 always;"
  echo "    add_header Cache-Control \"no-store\" always;"
  echo "    return $2 '{\"error\":\"$4\",\"code\":\"$5\",\"retry\":true}\n';"
  echo "}"
}
# nginx_write <live port> <backup port> — সব ফাইল লিখে nginx -t; ঠিক না হলে আগের ফাইল ফিরিয়ে 1
nginx_write() {
  local live="$1" backup="$2" f
  install -d -m 0755 "$(dirname "$EXEMPT")"
  [ -f "$EXEMPT" ] || { echo "# Lifolo — CI-র সাময়িক ঠিকানা (lifolo-tune ci-open লেখে, guard মোছে)। nginx-এর geo-তে include।" > "$EXEMPT"; chmod 0644 "$EXEMPT"; }
  # realip না থাকলে Cloudflare-এর অল্প কয়েকটা ঠিকানাই "দর্শক" — তখন IP-সীমা সবাইকে একসাথে আটকাত, তাই বন্ধ
  RATE_LIMIT=no
  grep -q '^real_ip_header CF-Connecting-IP;' "$REALIP" 2>/dev/null && RATE_LIMIT=yes
  for f in "$UPSTREAM_CONF" "$SNIPPET" "$LOCAL_CONF"; do
    if [ -f "$f" ]; then cp -p "$f" "$f.bak"; else rm -f "$f.bak"; fi
  done
  {
    echo "# Lifolo — managed by deploy-backend.sh: অ্যাপের upstream আর /api/-এর IP-প্রতি সীমা (snippets/lifolo-api.conf ব্যবহার করে)"
    echo "# blue/green: live = 127.0.0.1:$live ($(unit_of "$live")), backup = 127.0.0.1:$backup (শুধু live-এ সংযোগ না হলে)"
    echo "upstream lifolo_api {"
    echo "    server 127.0.0.1:$live max_fails=0;"
    echo "    server 127.0.0.1:$backup backup max_fails=0;"
    echo "    keepalive 64;"
    echo "    keepalive_requests 10000;"
    echo "    keepalive_timeout 15s;"
    echo "}"
    echo "geo \$lifolo_limit_exempt {"
    echo "    default 0;"
    echo "    127.0.0.1/32 1;"
    echo "    ::1/128 1;"
    echo "    include $EXEMPT;"
    echo "}"
    echo "map \$lifolo_limit_exempt \$lifolo_limit_key {"
    echo "    default \$binary_remote_addr;"
    echo "    1 \"\";"
    echo "}"
    echo "# IP-প্রতি সেকেন্ডে ২০টা, একসাথে ৪০টা বাড়তি (অ্যাপ খোলার সময় কয়েকটা একসাথে যায়); বেশি হলে 429 + JSON"
    echo "limit_req_zone \$lifolo_limit_key zone=lifolo_api:10m rate=20r/s;"
  } > "$UPSTREAM_CONF.new"
  local API_EXTRA="    error_page 502 503 504 @lifolo_api_down;"
  if [ "$RATE_LIMIT" = yes ]; then
    API_EXTRA="$API_EXTRA
    limit_req zone=lifolo_api burst=40 nodelay;
    limit_req_status 429;
    error_page 429 @lifolo_api_limited;"
  fi
  {
    echo "# Lifolo — managed by deploy-backend.sh (Deploy backend workflow). setup-base.sh এই ফাইল আর বদলায় না।"
    echo "# অ্যাপের পথগুলো → Spring Boot (upstream lifolo_api: $UPSTREAM_CONF); /healthz আর বাকি সব lifolo-common.conf-এ।"
    echo "# X-Forwarded-For = শুধু আসল IP (অতিথি আর লগইন-কোডের সীমা এটা দেখে; বাইরের দেওয়া মান নেওয়া হয় না)।"
    loc /api/ 60s "$API_EXTRA"
    # SSE (নোটিফিকেশনের সরাসরি ধারা): buffering বন্ধ, লম্বা সময় খোলা; সংযোগ একটাই, তাই সীমা নেই। deploy-এ আগের instance
    # বন্ধ হলে ধারা শেষ হয়, অ্যাপ নিজেই আবার সংযোগ করে (তখন নতুনটায়)।
    loc /api/events 1h "    proxy_buffering off;
    proxy_cache off;
    error_page 502 503 504 @lifolo_api_down;"
    # /api/cron/* (CRON_SECRET-এর পথ: দৈনিক কাজ, R2, Sentry পরীক্ষা, JobRunr হাতবদল) বাইরে থেকে নয় — VPS-এর ভেতর থেকে
    # 127.0.0.1:$LOCAL_PORT (নিচের lifolo-api-local.conf) বা সরাসরি অ্যাপের পোর্ট দিয়েই চলে
    echo "location /api/cron/ { return 404; }"
    # অ্যাপের কল: setup-call-server.sh-এর /rtc (LiveKit) — ফাইল না থাকলে কিছু হয় না (glob)
    echo "include /etc/nginx/snippets/lifolo-calls*.conf;"
    loc /r/
    loc /rx/
    loc /shop/
    loc /.well-known/
    json_loc @lifolo_api_down 503 15 "$MSG_DOWN" server_unavailable
    json_loc @lifolo_api_limited 429 2 "$MSG_LIMIT" rate_limited
  } > "$SNIPPET.new"
  # VPS-এর ভেতরের স্থির ঠিকানা: 127.0.0.1:$LOCAL_PORT → যেটা live (cron, Sentry test, R2, LiveKit-এর webhook; পোর্ট বদলালেও একই)
  {
    echo "# Lifolo — managed by deploy-backend.sh: VPS-এর ভেতরের স্থির ঠিকানা (শুধু 127.0.0.1) → upstream lifolo_api (যেটা live)।"
    echo "# /api/cron/* সহ সব পথ (বাইরের সাইটে ওটা 404)। হাতে: curl http://127.0.0.1:$LOCAL_PORT/api/health"
    echo "server {"
    echo "    listen 127.0.0.1:$LOCAL_PORT;"
    echo "    server_name lifolo-local;"
    echo "    access_log off;"
    echo "    location / {"
    echo "        proxy_pass http://lifolo_api;"
    echo "        proxy_http_version 1.1;"
    echo "        proxy_set_header Connection \"\";"
    echo "        proxy_set_header Host \$host;"
    echo "        proxy_set_header X-Real-IP \$remote_addr;"
    echo "        proxy_set_header X-Forwarded-For \$remote_addr;"
    echo "        proxy_set_header X-Forwarded-Proto http;"
    echo "        proxy_connect_timeout 5s;"
    echo "        proxy_read_timeout 900s;"
    echo "        proxy_buffering off;"
    echo "        client_max_body_size 64m;"
    echo "        proxy_next_upstream error timeout;"
    echo "        proxy_next_upstream_tries 2;"
    echo "        proxy_next_upstream_timeout 10s;"
    echo "    }"
    echo "}"
  } > "$LOCAL_CONF.new"
  chmod 0644 "$UPSTREAM_CONF.new" "$SNIPPET.new" "$LOCAL_CONF.new"
  mv "$UPSTREAM_CONF.new" "$UPSTREAM_CONF"
  mv "$SNIPPET.new" "$SNIPPET"
  mv "$LOCAL_CONF.new" "$LOCAL_CONF"
  if nginx -t 2>/dev/null; then
    # graceful: নতুন worker নতুন সেটিং নেয়, পুরোনো worker হাতের অনুরোধ শেষ করে তবেই বেরোয়
    systemctl reload nginx
    st nginx ok
    st rate_limit "$([ "$RATE_LIMIT" = yes ] && echo '20r/s burst 40 per client IP' || echo 'off (no Cloudflare realip yet)')"
    return 0
  fi
  nginx -t || true
  for f in "$UPSTREAM_CONF" "$SNIPPET" "$LOCAL_CONF"; do
    if [ -f "$f.bak" ]; then mv "$f.bak" "$f"; else rm -f "$f"; fi
  done
  systemctl reload nginx || true
  st nginx failed
  return 1
}
# go_live <port>: nginx → এই পোর্ট, /etc/lifolo/api-port, রিবুটে এটাই চালু, app.jar → এর release
go_live() {
  local port="$1" other=8080
  [ "$port" != 8080 ] || other=8081
  nginx_write "$port" "$other" || return 1
  printf '%s\n' "$port" > "$ACTIVE_FILE.new"
  chmod 0644 "$ACTIVE_FILE.new"
  mv "$ACTIVE_FILE.new" "$ACTIVE_FILE"
  systemctl enable "$(unit_of "$port")" >/dev/null 2>&1 || true
  ln -sfn "$(readlink "$API_DIR/app-$port.jar")" "$API_DIR/app.jar.new"
  mv -T "$API_DIR/app.jar.new" "$API_JAR"
  return 0
}

# LiveKit এই VPS-এ (setup-call-server.sh --mode main) হলে তার webhook আগে 127.0.0.1:8080-এ যেত — blue/green-এ পোর্ট বদলায়,
# তাই স্থির 127.0.0.1:$LOCAL_PORT। LiveKit নতুন সেটিং নেয় শুধু restart-এ — চলমান কল কাটে না: কোনো কল চালু না থাকলে
# তবেই restart, নইলে পরের deploy-এ আবার দেখা (ততক্ষণ কলের ১৫ s-এর sweep আটকে থাকা কল শেষ করে)।
livekit_webhook() {
  local y=/etc/livekit/livekit.yaml pending=/var/lib/lifolo/livekit-restart-pending live
  [ -f "$y" ] || return 0
  if grep -q 'http://127\.0\.0\.1:8080/api/livekit/webhook' "$y"; then
    sed -i "s#http://127\.0\.0\.1:8080/api/livekit/webhook#http://127.0.0.1:$LOCAL_PORT/api/livekit/webhook#" "$y"
    touch "$pending"
  fi
  if [ ! -f "$pending" ]; then st livekit_webhook "ok"; return 0; fi
  live="$(sudo -u postgres psql -d lifolo -tAc "SELECT count(*) FROM call_sessions WHERE state IN ('ringing','accepted')" 2>/dev/null | head -1 || true)"
  if [ "$live" = 0 ] && systemctl try-restart livekit-server; then
    rm -f "$pending"
    st livekit_webhook "moved to 127.0.0.1:$LOCAL_PORT (LiveKit restarted, no live calls)"
  else
    st livekit_webhook "moved to 127.0.0.1:$LOCAL_PORT in livekit.yaml; LiveKit restart waits (live calls: ${live:-?}) — next deploy"
  fi
}

# ---------------------------------------------------------------- 1) jar (version-সহ)
say "jar যাচাই আর বসানো: $RELEASES"
got="$(sha256sum "$JAR" | awk '{print $1}')"
[ "$got" = "$SHA" ] || die "jar-এর sha256 মেলেনি (পথে ভেঙেছে?)"
install -d -o root -g "$APP_USER" -m 0750 "$API_DIR" "$RELEASES"
REL_NAME="app-${SHA:0:12}.jar"
PREV_REL=""
if [ -L "$API_JAR" ]; then
  PREV_REL="$(basename "$(readlink "$API_JAR")")"
elif [ -f "$API_JAR" ]; then
  # এক-instance-এর সময়ের jar (আসল ফাইল) — releases/-এ রেখে দেওয়া (ফেরার জন্য)
  PREV_REL="app-$(sha256sum "$API_JAR" | cut -c1-12).jar"
  [ -f "$RELEASES/$PREV_REL" ] || install -o root -g "$APP_USER" -m 0640 "$API_JAR" "$RELEASES/$PREV_REL"
fi
install -o root -g "$APP_USER" -m 0640 "$JAR" "$RELEASES/$REL_NAME.new"
mv "$RELEASES/$REL_NAME.new" "$RELEASES/$REL_NAME"
rm -f "$JAR"
st jar_sha "${SHA:0:12}"
st release "$REL_NAME${PREV_REL:+ (previous $PREV_REL)}"

# ---------------------------------------------------------------- 2) app.env
say "অ্যাপের সেটিং: $APP_ENV"
REDIS_PASS="$(env_get LIFOLO_REDIS_PASSWORD "$ENV_FILE")"
[ -n "$REDIS_PASS" ] || die "$ENV_FILE-এ LIFOLO_REDIS_PASSWORD নেই — 'Setup VPS (base)' আবার চালান"
mask "$REDIS_PASS"
keep_or_make() {
  local v
  v="$(env_get "$1" "$APP_ENV")"
  [[ "$v" =~ ^[0-9a-f]{32,}$ ]] || v="$(openssl rand -hex 32)"
  printf '%s' "$v"
}
# mask এখানে, $(…)-এর বাইরে — ভেতরে থাকলে "::add-mask::…" লেখাটাও মানের অংশ হয়ে যেত (প্রতি deploy-এ নতুন চাবি, অ্যাডমিন কুকি অচল)
CRON_SECRET="$(keep_or_make CRON_SECRET)"
WEB_SESSION_SECRET="$(keep_or_make WEB_SESSION_SECRET)"
EMAIL_CODE_SECRET="$(keep_or_make EMAIL_CODE_SECRET)"
mask "$CRON_SECRET"
mask "$WEB_SESSION_SECRET"
mask "$EMAIL_CODE_SECRET"
p() { env_get "$1" "$PROVIDED"; }
FIREBASE_B64="$(p FIREBASE_SERVICE_ACCOUNT)"
# DNS: নামটা এই VPS-এ আসে কি না। "yes" = সরাসরি (ধূসর মেঘ), "cloudflare" = সব ঠিকানা Cloudflare-এর (কমলা মেঘ, proxy —
# Cloudflare আমাদের এখানেই পাঠায়), নইলে "no (<ঠিকানা>)"। Cloudflare-এর ঠিকানার তালিকা deploy-web.sh-এর realip ফাইল থেকে
# (না থাকলে জানা তালিকা)।
CF_REALIP=/etc/nginx/conf.d/lifolo-cloudflare-realip.conf
cf_nets() {
  local n
  n="$(sed -n 's/^set_real_ip_from \([0-9.]*\/[0-9]*\);$/\1/p' "$CF_REALIP" 2>/dev/null || true)"
  [ "$(printf '%s\n' "$n" | grep -c .)" -ge 5 ] || n="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22"
  printf '%s\n' $n
}
dns_state() {
  local ip ips my
  my=" $(hostname -I 2>/dev/null || true) "
  ips="$(getent ahostsv4 "$1" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
  for ip in $ips; do case "$my" in *" $ip "*) echo yes; return 0 ;; esac; done
  # shellcheck disable=SC2086  # ঠিকানাগুলো আলাদা আর্গুমেন্ট হিসেবে
  if [ -n "$ips" ] && python3 -c 'import ipaddress,sys; nets=[ipaddress.ip_network(n) for n in sys.stdin.read().split()]; sys.exit(0 if all(any(ipaddress.ip_address(a) in n for n in nets) for a in sys.argv[1:]) else 1)' $ips <<<"$(cf_nets)"; then
    echo cloudflare; return 0
  fi
  echo "no (${ips:-no A record})"
}
dns_here() { case "$(dns_state "$1")" in yes|cloudflare) return 0 ;; *) return 1 ;; esac; }
# শেয়ার লিংক/QR-এর ঠিকানা: সিক্রেট PUBLIC_BASE_URL > ওয়েবসাইট (DNS এই VPS-এ বা Cloudflare-এ এলে) > sslip.io
BASE_URL="$(p PUBLIC_BASE_URL | grep -E '^https?://[A-Za-z0-9.:/-]+$' || true)"
if [ -z "$BASE_URL" ]; then
  if [ -n "$WEB_DOMAIN" ] && [[ "$WEB_DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] && dns_here "$WEB_DOMAIN"; then
    BASE_URL="https://$WEB_DOMAIN"
  else
    BASE_URL="https://$DOMAIN"
  fi
fi
KEEP_DEMO_USERS="$(p DEMO_KEEP_FIREBASE_USERS | tr '[:upper:]' '[:lower:]')"
[ "$KEEP_DEMO_USERS" = false ] || KEEP_DEMO_USERS=true
tmp="$(mktemp /etc/lifolo/.app.env.XXXXXX)"
{
  echo "# Lifolo API — deploy-backend.sh লেখে (প্রতি deploy-এ আবার)। স্থায়ী চাবিগুলো থেকে যায়। হাতে বদলাবেন না।"
  echo "REDIS_URL=redis://:${REDIS_PASS}@127.0.0.1:6379"
  echo "CRON_SECRET=$CRON_SECRET"
  echo "WEB_SESSION_SECRET=$WEB_SESSION_SECRET"
  echo "EMAIL_CODE_SECRET=$EMAIL_CODE_SECRET"
  echo "JOBRUNR_DASHBOARD_ENABLED=false"
  # OpenAPI (/api/docs, /api/docs/ui) বাইরে খোলা নয়
  echo "SPRINGDOC_API_DOCS_ENABLED=false"
  echo "SPRINGDOC_SWAGGER_UI_ENABLED=false"
  echo "SENTRY_ENVIRONMENT=production"
  echo "PUBLIC_BASE_URL=$BASE_URL"
  # Vercel (Node) চালু থাকা পর্যন্ত: Firebase-এর ডেমো অ্যাকাউন্ট দুই সার্ভারের — এখানে কখনো মোছা/বদলানো নয়
  echo "DEMO_KEEP_FIREBASE_USERS=$KEEP_DEMO_USERS"
  if [ -n "$FIREBASE_B64" ]; then
    echo "FIREBASE_SERVICE_ACCOUNT=$FIREBASE_B64"
    echo "PUSH_MODE=fcm"
  else
    echo "PUSH_MODE=log"
  fi
  for k in ADMIN_EMAILS SES_REGION SES_ACCESS_KEY_ID SES_SECRET_ACCESS_KEY SES_FROM R2_ACCOUNT_ID R2_ENDPOINT \
      R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET SENTRY_DSN SENTRY_RELEASE SMS_URL_TEMPLATE; do
    v="$(p "$k")"
    [ -n "$v" ] && printf '%s=%s\n' "$k" "$v"
  done
  # অ্যাপের কল (LiveKit, deploy/CALLS-SERVER.md): workflow দিলে (আলাদা কল-সার্ভার), নইলে এই VPS-এ বসানো থাকলে তার backend.env
  LK_SRC="$PROVIDED"
  [ -n "$(p LIVEKIT_URL)" ] || LK_SRC=/etc/livekit/backend.env
  if [ -f "$LK_SRC" ]; then
    for k in LIVEKIT_URL LIVEKIT_API_KEY LIVEKIT_API_SECRET; do
      v="$(env_get "$k" "$LK_SRC")"
      [ -n "$v" ] && printf '%s=%s\n' "$k" "$v"
    done
  fi
} > "$tmp"
chown root:"$APP_USER" "$tmp"
chmod 0640 "$tmp"
mv "$tmp" "$APP_ENV"
shred -u "$PROVIDED" 2>/dev/null || rm -f "$PROVIDED"
unset REDIS_PASS FIREBASE_B64
st firebase "$([ -n "$(env_get FIREBASE_SERVICE_ACCOUNT "$APP_ENV")" ] && echo on || echo off)"
st ses "$([ -n "$(env_get SES_REGION "$APP_ENV")" ] && [ -n "$(env_get SES_FROM "$APP_ENV")" ] && echo on || echo off)"
st r2 "$([ -n "$(env_get R2_BUCKET "$APP_ENV")" ] && echo on || echo off)"
st sentry "$([ -n "$(env_get SENTRY_DSN "$APP_ENV")" ] && echo on || echo off)"
st livekit "$(env_get LIVEKIT_URL "$APP_ENV" || true)"
st public_base_url "$BASE_URL"
st demo_keep_firebase_users "$KEEP_DEMO_USERS"

# ---------------------------------------------------------------- 3) systemd: দুটো instance (blue/green)
say "systemd: lifolo-api@8080 / lifolo-api@8081 (blue/green; app.env-ও পড়ে)"
install -d -m 0755 "$DROPIN_DIR"
install -d -o root -g "$APP_USER" -m 0755 "$RUN_DIR"
unit_template > "$UNIT_TPL.new"
chmod 0644 "$UNIT_TPL.new"
mv "$UNIT_TPL.new" "$UNIT_TPL"
cat > "$DROPIN_DIR/10-deploy.conf" <<EOF
# Lifolo — deploy-backend.sh লেখে। setup-base.sh-এর /etc/lifolo/env-এর পরে অ্যাপের নিজের সেটিং, শেষে এই চালুর সাময়িক
# সেটিং ($RUN_DIR/api-<port>.env: LIFOLO_JOBS_HOLD=true — deploy নিজেই মোছে; রিবুটে /run খালি হয়)।
[Service]
EnvironmentFile=$APP_ENV
EnvironmentFile=-$RUN_DIR/api-%i.env
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
EOF
chmod 0644 "$DROPIN_DIR/10-deploy.conf"
systemctl daemon-reload
# /usr/local/sbin/lifolo-api: কোন instance চালু (ops স্ক্রিপ্ট, drill, Sentry test এটা দিয়ে খোঁজে)
helper_script > /usr/local/sbin/lifolo-api.new
chmod 0755 /usr/local/sbin/lifolo-api.new
mv /usr/local/sbin/lifolo-api.new /usr/local/sbin/lifolo-api

# এখন কোনটা চলছে (OLD) আর নতুনটা কোথায় (NEW)
OLD_UNIT="" OLD_PORT="" OLD_KIND=""
p="$(cat "$ACTIVE_FILE" 2>/dev/null || true)"
if [[ "$p" =~ ^(8080|8081)$ ]] && systemctl is-active --quiet "$(unit_of "$p")"; then
  OLD_PORT="$p"; OLD_UNIT="$(unit_of "$p")"; OLD_KIND=bluegreen
elif systemctl is-active --quiet "$LEGACY_UNIT"; then
  OLD_PORT=8080; OLD_UNIT="$LEGACY_UNIT"; OLD_KIND=legacy
else
  for p in 8080 8081; do
    if systemctl is-active --quiet "$(unit_of "$p")"; then OLD_PORT="$p"; OLD_UNIT="$(unit_of "$p")"; OLD_KIND=bluegreen; break; fi
  done
fi
if [ "$OLD_PORT" = 8080 ]; then NEW_PORT=8081; else NEW_PORT=8080; fi
NEW_UNIT="$(unit_of "$NEW_PORT")"
OLD_HEALTHY=no
[ -z "$OLD_PORT" ] || ! health_ok "$OLD_PORT" || OLD_HEALTHY=yes
st switch_from "${OLD_UNIT:-none}${OLD_PORT:+ (:$OLD_PORT, health $OLD_HEALTHY)}"
st switch_to "$NEW_UNIT"
# যে instance live নয় তা সরানো: আগের ব্যর্থ deploy-এর বাকি, বা OLD নয় এমন পুরোনো এক-instance-এর সার্ভিস
systemctl stop "$NEW_UNIT" 2>/dev/null || true
systemctl reset-failed "$NEW_UNIT" 2>/dev/null || true
if [ "$OLD_KIND" != legacy ] && [ -f "$LEGACY_FILE" ]; then
  systemctl disable --now "$LEGACY_UNIT" >/dev/null 2>&1 || true
fi
if ss -ltnH "( sport = :$NEW_PORT )" 2>/dev/null | grep -q .; then
  die "পোর্ট $NEW_PORT অন্য কিছু ধরে আছে — নতুন instance চালু করা যাচ্ছে না"
fi

# মেমরি: দুটো JVM অল্প সময় একসাথে (নতুনটা -Xms1g দিয়ে শুরু)। কম থাকলে আগেরটা আগে বন্ধ (পুরোনো ধাঁচ, ~১ মিনিট 503 JSON)।
avail_mb="$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
st mem_available_mb "$avail_mb"
SWITCH=bluegreen
if [ -z "$OLD_UNIT" ]; then
  SWITCH=fresh
elif [ "$OLD_HEALTHY" != yes ]; then
  SWITCH=replace-unhealthy
elif [ "${avail_mb:-0}" -lt "$MIN_FREE_MB" ]; then
  SWITCH="restart (low memory: ${avail_mb} MB available)"
fi

ln -sfn "releases/$REL_NAME" "$API_DIR/app-$NEW_PORT.jar"
# পাশাপাশি চলার সময় JobRunr-এর কাজ শুধু আগেরটায় — নতুনটা "hold"-এ (switch-এর পরে deploy চালু করে)
if [ "$SWITCH" = bluegreen ]; then
  printf '# deploy-backend.sh: switch-এর আগে পর্যন্ত JobRunr বন্ধ (deploy নিজেই মোছে)\nLIFOLO_JOBS_HOLD=true\n' > "$RUN_DIR/api-$NEW_PORT.env"
  chmod 0644 "$RUN_DIR/api-$NEW_PORT.env"
else
  rm -f "$RUN_DIR/api-$NEW_PORT.env"
fi
case "$SWITCH" in
  bluegreen|fresh) ;;
  *) say "আগেরটা আগে বন্ধ: $SWITCH"; systemctl stop "$OLD_UNIT" || true ;;
esac

started_at="$(date '+%Y-%m-%d %H:%M:%S')"
# Uptime-এর সতর্কতা: এই deploy-এর ১৫ মিনিটের restart (ভাঙা jar-এর crash-loop আর ফেরা সহ) "অপ্রত্যাশিত" নয় — deploy নিজেই জানায়
{ tail -n 49 /var/lib/lifolo/ops-expected 2>/dev/null || true; date +%s; } > /var/lib/lifolo/ops-expected.new && mv /var/lib/lifolo/ops-expected.new /var/lib/lifolo/ops-expected
say "নতুন instance চালু: $NEW_UNIT ($REL_NAME) — আগেরটা (${OLD_UNIT:-নেই}) চলতেই থাকে"
systemctl start "$NEW_UNIT" || true

# প্রথমবার Flyway সব migration চালায় — ৪ মিনিট পর্যন্ত অপেক্ষা। migration নতুন instance চালুর সময়, health-এর আগে চলে;
# আগের release তখনো চলে, তাই migration আগের release-এর সাথেও চলার মতো (backward compatible) হতে হবে — deploy/ZERO-DOWNTIME.md
app_ok=no
if wait_health "$NEW_PORT" "$NEW_UNIT" 120; then
  app_ok=yes
elif [ -n "$(env_get SENTRY_DSN "$APP_ENV")" ]; then
  # Sentry-র DSN-এ সমস্যা হলে অ্যাপ চালুই হয় না — Sentry ছাড়া আরেকবার
  st app_error "$(app_error "$NEW_UNIT")"
  say "অ্যাপ চালু হয়নি — Sentry ছাড়া আবার চেষ্টা"
  sed -i '/^SENTRY_DSN=/d' "$APP_ENV"
  started_at="$(date '+%Y-%m-%d %H:%M:%S')"
  systemctl restart "$NEW_UNIT" || true
  if wait_health "$NEW_PORT" "$NEW_UNIT" 120; then app_ok=yes; st sentry "off: the app did not start with SENTRY_DSN (see app_error)"; fi
fi

if [ "$app_ok" != yes ]; then
  st app down
  [ -n "$(env_get app_error "$STATUS")" ] || st app_error "$(app_error "$NEW_UNIT")"
  echo "---- $NEW_UNIT log (শেষ ৮০ লাইন) ----"
  journalctl -u "$NEW_UNIT" --since "$started_at" --no-pager 2>/dev/null | grep -v 'Subject:' | tail -80 || true
  systemctl stop "$NEW_UNIT" || true
  systemctl disable "$NEW_UNIT" >/dev/null 2>&1 || true
  rm -f "$RUN_DIR/api-$NEW_PORT.env"
  if [ "$SWITCH" = bluegreen ]; then
    # আজকের rollback-এর মতোই ফল, কিন্তু কোনো বিরতি ছাড়া: nginx আর JobRunr আগেরটাতেই, কিছুই বদলায়নি
    st rollback "ok (the previous release kept serving on :$OLD_PORT — no switch, no downtime)"
    die "নতুন release চালু হয়নি (/api/health সাড়া দেয়নি) — আগেরটা (:$OLD_PORT) চলছে"
  fi
  # আগে কিছু চলছিল না (বা আগেই বন্ধ করতে হয়েছিল): আগের release-এ ফেরা
  rolled=no
  if [ -n "$OLD_UNIT" ]; then
    say "আগের instance আবার চালু: $OLD_UNIT"
    systemctl start "$OLD_UNIT" || true
    if wait_health "$OLD_PORT" "$OLD_UNIT" 90; then rolled=yes; st rollback "ok (restarted $OLD_UNIT)"; fi
  elif [ -n "$PREV_REL" ] && [ "$PREV_REL" != "$REL_NAME" ] && [ -f "$RELEASES/$PREV_REL" ]; then
    say "নতুন jar চালু হয়নি — আগের release-এ ফেরা ($PREV_REL)"
    ln -sfn "releases/$PREV_REL" "$API_DIR/app-$NEW_PORT.jar"
    started_at="$(date '+%Y-%m-%d %H:%M:%S')"
    systemctl reset-failed "$NEW_UNIT" 2>/dev/null || true
    systemctl start "$NEW_UNIT" || true
    if wait_health "$NEW_PORT" "$NEW_UNIT" 90; then rolled=yes; st rollback "ok ($PREV_REL)"; go_live "$NEW_PORT"; fi
  fi
  # আগের jar-ও না চললে: JVM-এর নতুন সেটিং (tune-vps.sh-এর drop-in) সরিয়ে setup-base.sh-এর ভিত্তিতে আরেকবার
  TUNING_DROPIN="$DROPIN_DIR/20-tuning.conf"
  if [ "$rolled" != yes ] && [ -f "$TUNING_DROPIN" ]; then
    say "অ্যাপ চালু হচ্ছে না — JVM-এর tuning drop-in ছাড়া আরেকবার"
    mv "$TUNING_DROPIN" "$TUNING_DROPIN.disabled"
    systemctl daemon-reload
    systemctl reset-failed "$NEW_UNIT" 2>/dev/null || true
    systemctl start "$NEW_UNIT" || true
    if wait_health "$NEW_PORT" "$NEW_UNIT" 90; then
      rolled=yes; st rollback "ok (without $TUNING_DROPIN — see $TUNING_DROPIN.disabled)"; go_live "$NEW_PORT"
    fi
  fi
  [ "$rolled" = yes ] || st rollback "${OLD_UNIT:+failed}${OLD_UNIT:-none}"
  die "অ্যাপ চালু হয়নি (/api/health সাড়া দেয়নি)"
fi

st app up
# ইমেইল: অ্যাপ চালুর সময় SES client বানায় (কিছু পাঠায় না) আর লগে লেখে — "Mail: Amazon SES client ready" / "logging only"
mail_line="$(journalctl -u "$NEW_UNIT" --since "$started_at" --no-pager 2>/dev/null | grep -o 'Mail: .*' | tail -1 || true)"
case "$mail_line" in
  *"SES client ready"*) st ses_client ok ;;
  *"logging only"*) st ses_client off ;;
  *"failed to initialise"*) st ses_client "failed: ${mail_line#*initialise: }" ;;
  *) st ses_client "unknown" ;;
esac
st flyway "$(sudo -u postgres psql -d lifolo -tAc "SELECT count(*) || ' migrations, latest V' || (SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1) FROM flyway_schema_history WHERE success" 2>/dev/null | head -1 || echo '?')"

# ---------------------------------------------------------------- 4) switch: nginx → নতুন, JobRunr হাতবদল, আগেরটা drain করে বন্ধ
say "switch: nginx upstream lifolo_api → 127.0.0.1:$NEW_PORT (graceful reload)"
probe_start
go_live "$NEW_PORT" || {
  probe_stop
  systemctl stop "$NEW_UNIT" || true
  systemctl disable "$NEW_UNIT" >/dev/null 2>&1 || true
  rm -f "$RUN_DIR/api-$NEW_PORT.env"
  [ "$SWITCH" != bluegreen ] || st rollback "ok (nginx config failed — the previous release kept serving on :$OLD_PORT)"
  die "nginx-এর নতুন সেটিং চলেনি (nginx -t) — switch হয়নি"
}
# JobRunr: আগেরটার কাজ থামানো → নতুনটায় চালু (একসাথে কখনো দুটো নয়)। আগেরটা এই endpoint ছাড়া পুরোনো release হলে
# (প্রথমবার, এক-instance থেকে আসার সময়) নতুনটার কাজ আগেরটা বন্ধ হওয়ার পরে।
jobs_note=""
if [ "$SWITCH" = bluegreen ]; then
  if [ "$OLD_KIND" = bluegreen ] && [ "$(jobs_call "$OLD_PORT" stop | json_field server)" = handed_over ]; then
    jobs_note="old stopped at switch"
    jobs_start_new || true
  else
    jobs_note="old has no hand-over endpoint (${OLD_KIND}) — new starts after the old one stopped"
  fi
fi
if [ "$SWITCH" = bluegreen ]; then
  say "drain: আগেরটা ($OLD_UNIT) আরও ${DRAIN_SECONDS} s চলমান অনুরোধ শেষ করে, তারপর বন্ধ"
  sleep "$DRAIN_SECONDS"
fi
if [ -n "$OLD_UNIT" ]; then
  systemctl stop "$OLD_UNIT" || true
  systemctl disable "$OLD_UNIT" >/dev/null 2>&1 || true
fi
sleep 2
probe_stop
if [ "$(jobs_call "$NEW_PORT" status | json_field server)" != running ]; then
  jobs_start_new || true
fi
rm -f "$RUN_DIR/api-$NEW_PORT.env"
jobs_now="$(jobs_call "$NEW_PORT" status | json_field server)"
st jobs "${jobs_now:-unknown} on :$NEW_PORT${jobs_note:+ ($jobs_note)}"
[ "$jobs_now" = running ] || st jobs_error "JobRunr not running on the new instance — POST /api/cron/jobs?do=start on 127.0.0.1:$NEW_PORT"
st switch "$SWITCH: ${OLD_PORT:-none} → $NEW_PORT"
# এক-instance থেকে প্রথমবার এলে: পুরোনো lifolo-api.service সরিয়ে রাখা (setup-base.sh আর বানায় না)
if [ -f "$LEGACY_FILE" ]; then
  systemctl disable --now "$LEGACY_UNIT" >/dev/null 2>&1 || true
  install -d -m 0700 /etc/lifolo/legacy
  mv "$LEGACY_FILE" /etc/lifolo/legacy/lifolo-api.service
  if [ -d "$LEGACY_DROPINS" ]; then rm -rf /etc/lifolo/legacy/lifolo-api.service.d; mv "$LEGACY_DROPINS" /etc/lifolo/legacy/; fi
  systemctl daemon-reload
  systemctl reset-failed "$LEGACY_UNIT" 2>/dev/null || true
  st legacy_unit "moved to /etc/lifolo/legacy/"
fi
# পুরোনো release-গুলো: শেষ ৫টা (আর যেগুলো কোনো instance-এর symlink) রাখা — হাতে ফেরার জন্য
keep=" "
for p in 8080 8081; do
  if [ -L "$API_DIR/app-$p.jar" ]; then keep="$keep$(basename "$(readlink "$API_DIR/app-$p.jar")") "; fi
done
# shellcheck disable=SC2012  # নাম আমাদের বানানো (app-<hex>.jar)
ls -1t "$RELEASES"/app-*.jar 2>/dev/null | tail -n +6 | while read -r f; do
  case "$keep" in *" $(basename "$f") "*) ;; *) rm -f "$f" ;; esac
done
st releases "$(ls -1 "$RELEASES" 2>/dev/null | wc -l) kept in $RELEASES"
# LiveKit (এই VPS-এ, main mode): webhook আগে 127.0.0.1:8080-এ যেত — এখন স্থির ঠিকানা 127.0.0.1:$LOCAL_PORT (nginx → যেটা চালু)
livekit_webhook

# ---------------------------------------------------------------- 5b) আলাদা নাম (api.lifolo.com)
API_SITE=/etc/nginx/sites-available/lifolo-api-domain
if [ -n "$API_DOMAIN" ]; then
  say "আলাদা নাম: $API_DOMAIN"
  if ! [[ "$API_DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]]; then
    st api_domain "invalid name"
  else
    api_dns="$(dns_state "$API_DOMAIN")"
    st api_dns "$api_dns"
    CERT="/etc/letsencrypt/live/$API_DOMAIN/fullchain.pem"
    if ! dns_here "$API_DOMAIN"; then
      st api_domain "dns_not_ready (${api_dns#no })"
    else
      # port 80-এর default_server-এ acme-challenge আগে থেকেই আছে (setup-base.sh), তাই webroot-এ সরাসরি।
      # Cloudflare-এর proxy চালু থাকলেও HTTP-01 চলে (Cloudflare /.well-known/acme-challenge/ এখানে পাঠায়); থাকা সার্টিফিকেট রাখা হয়।
      if [ ! -f "$CERT" ]; then
        certbot certonly --webroot -w /var/www/letsencrypt -d "$API_DOMAIN" --register-unsafely-without-email \
          --agree-tos --non-interactive --keep-until-expiring --deploy-hook "systemctl reload nginx" >/dev/null 2>&1 || true
      fi
      if [ ! -f "$CERT" ]; then
        st api_domain cert_failed
      else
        {
          echo "# Lifolo — deploy-backend.sh লেখে: $API_DOMAIN → একই অ্যাপ (setup-base.sh এই ফাইল ছোঁয় না)"
          echo "server {"
          echo "    listen 80;"
          echo "    listen [::]:80;"
          echo "    server_name $API_DOMAIN;"
          echo "    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }"
          echo "    location / { return 301 https://$API_DOMAIN\$request_uri; }"
          echo "}"
          echo "server {"
          echo "    listen 443 ssl http2;"
          echo "    listen [::]:443 ssl http2;"
          echo "    server_name $API_DOMAIN;"
          echo "    ssl_certificate $CERT;"
          echo "    ssl_certificate_key /etc/letsencrypt/live/$API_DOMAIN/privkey.pem;"
          echo "    ssl_protocols TLSv1.2 TLSv1.3;"
          echo "    ssl_prefer_server_ciphers off;"
          echo "    ssl_session_cache shared:lifolo_ssl:10m;"
          echo "    ssl_session_timeout 1d;"
          echo "    ssl_session_tickets off;"
          echo "    add_header Strict-Transport-Security \"max-age=31536000\" always;"
          echo "    include $SNIPPET;"
          echo "    include /etc/nginx/snippets/lifolo-common.conf;"
          echo "}"
        } > "$API_SITE.new"
        [ -e /proc/net/if_inet6 ] || sed -i '/listen \[::\]/d' "$API_SITE.new"
        mv "$API_SITE.new" "$API_SITE"
        chmod 0644 "$API_SITE"
        ln -sf "$API_SITE" /etc/nginx/sites-enabled/lifolo-api-domain
        if nginx -t 2>/dev/null; then
          systemctl reload nginx
          st api_domain ok
        else
          rm -f /etc/nginx/sites-enabled/lifolo-api-domain
          systemctl reload nginx || true
          st api_domain nginx_failed
        fi
      fi
    fi
  fi
fi

# ---------------------------------------------------------------- 6) পরীক্ষা
say "পরীক্ষা (এই মেশিন থেকে, https দিয়ে)"
H() { curl -sS -m 15 --resolve "$DOMAIN:443:127.0.0.1" "$@"; }
B="https://$DOMAIN"
check() { if eval "$2"; then st "$1" ok; echo "ok   $1"; else st "$1" failed; echo "FAIL $1"; fi; }
check health_https "H '$B/api/health' | grep -q '\"ok\":true'"
check healthz "H '$B/healthz' | grep -q '\"stage\":\"base\"'"
check assetlinks "H -o /dev/null -w '%{http_code} %{content_type}' '$B/.well-known/assetlinks.json' | grep -q '^200 application/json'"
# /r/<id>: অ্যাপের শেয়ার পাতা (nginx-এর "setting up" পাতা নয়)
check share_route "! H '$B/r/deploy-smoke' | grep -q 'Lifolo — setting up'"
# JobRunr dashboard: বন্ধ (কোনো পোর্টে শুনছে না) আর বাইরে থেকেও নেই
check jobrunr_off "! ss -ltnH | awk '{print \$4}' | grep -qE ':8000\$' && ! H '$B/dashboard' | grep -qi jobrunr"
# actuator বাইরে খোলা নয় (nginx শুধু অ্যাপের পথগুলো পাঠায়)
check apidocs_hidden "! H '$B/api/docs' | grep -q openapi"
check actuator_hidden "! H '$B/actuator/health' | grep -q '\"status\"'"
check cron_hidden "H -o /dev/null -w '%{http_code}' '$B/api/cron/daily' | grep -qx 404"
if [ "$(env_get api_domain "$STATUS")" = ok ]; then
  check api_domain_health "curl -sS -m 15 --resolve '$API_DOMAIN:443:127.0.0.1' 'https://$API_DOMAIN/api/health' | grep -q '\"ok\":true'"
fi
check nginx_keepalive "nginx -T 2>/dev/null | grep 'proxy_pass http://lifolo_api;' >/dev/null && nginx -T 2>/dev/null | grep -E '^\s*keepalive 64;' >/dev/null"
# blue/green: ভেতরের স্থির ঠিকানা চলে, আর এখন একটাই instance চালু
check local_port "curl -fsS -m 5 'http://127.0.0.1:$LOCAL_PORT/api/health' | grep -q '\"ok\":true'"
check one_instance "[ \"\$(systemctl list-units --state=active --no-legend --plain 'lifolo-api*' 2>/dev/null | grep -c '^lifolo-api')\" = 1 ]"
# IP-সীমা: এই মেশিনের নিজের সর্বজনীন ঠিকানা থেকে (127.0.0.1 সীমার বাইরে) একসাথে ৮০টা → কিছু 429 (JSON), বাকি 200
if [ "$(env_get rate_limit "$STATUS" | cut -c1-2)" = 20 ]; then
  MYIP="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)"
  if [ -n "$MYIP" ]; then
    probe="$(mktemp)"
    seq 1 80 | xargs -P 40 -I{} curl -sS -m 10 -w '\n%{http_code}\n' --resolve "$DOMAIN:443:$MYIP" "$B/api/health" > "$probe" 2>/dev/null || true
    n200="$(grep -cx 200 "$probe" || true)"; n429="$(grep -cx 429 "$probe" || true)"
    st rate_limit_probe "200=$n200 429=$n429"
    if [ "${n429:-0}" -gt 0 ] && [ "${n200:-0}" -ge 30 ] && grep -q '"code":"rate_limited"' "$probe"; then
      st rate_limit_json ok; echo "ok   rate_limit_json (200=$n200 429=$n429)"
    else
      st rate_limit_json failed; echo "FAIL rate_limit_json (200=$n200 429=$n429)"
    fi
    rm -f "$probe"
  fi
fi
check api_guest "H -X POST -H 'content-type: application/json' --data '{\"action\":\"__nope__\",\"lang\":\"bn\"}' '$B/api/community' | grep -q 'error'"

# ডেমো বানানো আর মোছা — API দিয়ে, মালিকের অ্যাডমিন-প্যানেল সেশন (কুকি) এখানেই WEB_SESSION_SECRET দিয়ে সই করে
if [ -n "$OWNER_UID" ] && [ -n "$DEMO_MODE" ]; then
  say "ডেমো ($DEMO_MODE): বানানো → চালু কি না$([ "$DEMO_MODE" = smoke ] && echo ' → মোছা')"
  COOKIE="$(WSS="$WEB_SESSION_SECRET" UID_="$OWNER_UID" EMAIL_="$OWNER_EMAIL" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time
key = hashlib.sha256(("lifolo-admin-web-v1\n" + os.environ["WSS"]).encode()).digest()
now = int(time.time() * 1000)
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
payload = b64(json.dumps({"u": os.environ["UID_"], "e": os.environ["EMAIL_"], "i": now, "s": now}, separators=(",", ":")).encode())
print(payload + "." + b64(hmac.new(key, payload.encode(), hashlib.sha256).digest()))
PY
)"
  mask "$COOKIE"
  # কুকি কমান্ড লাইনে নয় (ps-এ দেখা যায়) — 0600 ফাইল থেকে হেডার
  HDR="$(mktemp)"
  printf 'Cookie: __Host-lf_admin=%s\n' "$COOKIE" > "$HDR"
  trap 'rm -f "$HDR"' EXIT
  A() { H -X POST -H 'content-type: application/json' -H 'x-lifolo-admin: 1' -H "@$HDR" --data "$1" "$B/api/community"; }
  jget() { python3 -c "import json,sys; d=json.load(sys.stdin); v=d.get('$1'); print('' if v is None else (str(v).lower() if isinstance(v,bool) else v))" 2>/dev/null; }
  before="$(A '{"action":"admin_demo_status","lang":"bn"}')"
  if [ "$(printf '%s' "$before" | jget active)" = true ] && [ "$(printf '%s' "$before" | jget partial)" != true ]; then
    st demo_seed "kept (আগে থেকেই চালু)"
    st demo_wipe skipped_active
    echo "ডেমো আগে থেকেই চালু — বানানো/মোছা হলো না"
  elif [ -n "$(printf '%s' "$before" | jget error)" ]; then
    st demo_seed "failed: $(printf '%s' "$before" | jget error)"
    st demo_wipe skipped
  else
    step=0 n=0 seed_err=""
    while [ -n "$step" ] && [ "$n" -lt 80 ]; do
      r="$(A "{\"action\":\"admin_demo_seed\",\"step\":$step,\"lang\":\"bn\"}")"
      seed_err="$(printf '%s' "$r" | jget error)"
      [ -z "$seed_err" ] || break
      step="$(printf '%s' "$r" | jget next)"
      n=$((n + 1))
    done
    roles="$(H -X POST -H 'content-type: application/json' --data '{"demoRoles":true}' "$B/api/auth/verify-code" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('roles') or []) if d.get('active') else 0)" 2>/dev/null || echo 0)"
    login="$(H -X POST -H 'content-type: application/json' --data '{"demo":"patient"}' "$B/api/auth/verify-code" | jget token)"
    if [ -z "$seed_err" ] && [ -z "$step" ] && [ "${roles:-0}" -gt 0 ] && [ -n "$login" ]; then
      st demo_seed "ok ($n ধাপ, $roles রোল, ডেমো লগইন চলে)"
    else
      st demo_seed "failed: ${seed_err:-step=$step roles=$roles login=$([ -n "$login" ] && echo yes || echo no)}"
    fi
    if [ "$DEMO_MODE" = smoke ]; then
    wipe_err="" n=0
    while [ "$n" -lt 40 ]; do
      r="$(A '{"action":"admin_demo_wipe","lang":"bn"}')"
      wipe_err="$(printf '%s' "$r" | jget error)"
      [ -z "$wipe_err" ] || break
      [ "$(printf '%s' "$r" | jget more)" = true ] || break
      n=$((n + 1))
    done
    after="$(A '{"action":"admin_demo_status","lang":"bn"}' | jget active)"
    if [ -z "$wipe_err" ] && [ "$after" = false ]; then st demo_wipe ok; else st demo_wipe "failed: ${wipe_err:-still active}"; fi
    else
      st demo_wipe "skipped (keep)"
    fi
  fi
  rm -f "$HDR"
  unset COOKIE
fi
unset CRON_SECRET WEB_SESSION_SECRET EMAIL_CODE_SECRET
st finished yes
echo
cat "$STATUS"
