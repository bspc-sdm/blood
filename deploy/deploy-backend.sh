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
# যা করে:
#   1) jar → /opt/lifolo/api/app.jar (আগেরটা app.jar.prev), sha256 মিলিয়ে
#   2) /etc/lifolo/app.env (0640 root:lifolo) — অ্যাপের সেটিং: Redis-এর ঠিকানা (/etc/lifolo/env-এর পাসওয়ার্ডে), প্রথমবার
#      বানানো স্থায়ী চাবি (CRON_SECRET, WEB_SESSION_SECRET, EMAIL_CODE_SECRET), আর --provided-এর মান। ডাটাবেস
#      (SPRING_DATASOURCE_*) /etc/lifolo/env থেকেই আসে। Firebase না থাকলে PUSH_MODE=log (পুশ বন্ধ)।
#   3) systemd drop-in (lifolo-api.service.d/10-deploy.conf) → app.env-ও পড়ে; restart; Flyway অ্যাপ চালুর সময় নিজে চলে
#   4) /api/health না এলে আগের jar-এ ফিরে যাওয়া (থাকলে)
#   5) nginx: /api/, /r/, /rx/, /shop/, /.well-known/ → 127.0.0.1:8080 (/healthz যেমন ছিল); JobRunr dashboard বন্ধ
#   6) পরীক্ষা (এই মেশিন থেকেই, nginx-এর https দিয়ে) — ফল /var/lib/lifolo/deploy-status-এ (গোপন কিছু নয়)
# কোনো গোপন মান ছাপা হয় না।
set -euo pipefail

ENV_FILE=/etc/lifolo/env
APP_ENV=/etc/lifolo/app.env
API_DIR=/opt/lifolo/api
API_JAR=$API_DIR/app.jar
API_PORT=8080
APP_USER=lifolo
STATUS=/var/lib/lifolo/deploy-status
SNIPPET=/etc/nginx/snippets/lifolo-api.conf
DROPIN_DIR=/etc/systemd/system/lifolo-api.service.d

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
[ -f /etc/systemd/system/lifolo-api.service ] || die "lifolo-api.service নেই — আগে 'Setup VPS (base)' চালান"
command -v java >/dev/null || die "java নেই — আগে 'Setup VPS (base)' চালান"
[ -f "$JAR" ] || die "jar পৌঁছায়নি ($JAR)"
[ -f "$PROVIDED" ] || die "provided env পৌঁছায়নি"

env_get() { sed -nE "s/^$1=(.*)$/\1/p" "$2" 2>/dev/null | tail -1; }

# ---------------------------------------------------------------- 1) jar
say "jar যাচাই আর বসানো"
got="$(sha256sum "$JAR" | awk '{print $1}')"
[ "$got" = "$SHA" ] || die "jar-এর sha256 মেলেনি (পথে ভেঙেছে?)"
install -d -o root -g "$APP_USER" -m 0750 "$API_DIR"
had_prev=no
if [ -f "$API_JAR" ]; then cp -p "$API_JAR" "$API_JAR.prev"; had_prev=yes; fi
install -o root -g "$APP_USER" -m 0640 "$JAR" "$API_JAR.new"
mv "$API_JAR.new" "$API_JAR"
rm -f "$JAR"
st jar_sha "${SHA:0:12}"

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
# শেয়ার লিংক/QR-এর ঠিকানা: সিক্রেট PUBLIC_BASE_URL > ওয়েবসাইট (DNS এই VPS-এ এলে) > sslip.io
dns_here() {
  local ip ips my
  my=" $(hostname -I 2>/dev/null || true) "
  ips="$(getent ahostsv4 "$1" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
  for ip in $ips; do case "$my" in *" $ip "*) return 0 ;; esac; done
  return 1
}
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
      R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET SENTRY_DSN SMS_URL_TEMPLATE; do
    v="$(p "$k")"
    [ -n "$v" ] && printf '%s=%s\n' "$k" "$v"
  done
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
st public_base_url "$BASE_URL"
st demo_keep_firebase_users "$KEEP_DEMO_USERS"

# ---------------------------------------------------------------- 3) systemd
say "systemd: lifolo-api (app.env-ও পড়ে) — restart"
install -d -m 0755 "$DROPIN_DIR"
cat > "$DROPIN_DIR/10-deploy.conf" <<EOF
# Lifolo — deploy-backend.sh লেখে। setup-base.sh-এর /etc/lifolo/env-এর পরে অ্যাপের নিজের সেটিং।
[Service]
EnvironmentFile=$APP_ENV
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
EOF
chmod 0644 "$DROPIN_DIR/10-deploy.conf"
systemctl daemon-reload
systemctl enable lifolo-api.service >/dev/null 2>&1
started_at="$(date '+%Y-%m-%d %H:%M:%S')"
systemctl restart lifolo-api.service

health_ok() { curl -fsS -m 4 "http://127.0.0.1:$API_PORT/api/health" 2>/dev/null | grep -q '"ok":true'; }
wait_health() {
  local i
  for i in $(seq 1 "$1"); do
    if health_ok; then return 0; fi
    if ! systemctl is-active --quiet lifolo-api.service && [ "$i" -gt 20 ]; then sleep 2; fi
    sleep 2
  done
  return 1
}
# প্রথমবার Flyway সব migration চালায় — ৪ মিনিট পর্যন্ত অপেক্ষা
if wait_health 120; then
  st app up
  # ইমেইল: অ্যাপ চালুর সময় SES client বানায় (কিছু পাঠায় না) আর লগে লেখে — "Mail: Amazon SES client ready" / "logging only"
  mail_line="$(journalctl -u lifolo-api.service --since "$started_at" --no-pager 2>/dev/null | grep -o 'Mail: .*' | tail -1 || true)"
  case "$mail_line" in
    *"SES client ready"*) st ses_client ok ;;
    *"logging only"*) st ses_client off ;;
    *"failed to initialise"*) st ses_client "failed: ${mail_line#*initialise: }" ;;
    *) st ses_client "unknown" ;;
  esac
  st flyway "$(sudo -u postgres psql -d lifolo -tAc "SELECT count(*) || ' migrations, latest V' || (SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1) FROM flyway_schema_history WHERE success" 2>/dev/null | head -1 || echo '?')"
else
  st app down
  echo "---- lifolo-api log (শেষ ৮০ লাইন) ----"
  journalctl -u lifolo-api.service --since "$started_at" --no-pager 2>/dev/null | grep -v 'Subject:' | tail -80 || true
  if [ "$had_prev" = yes ]; then
    say "নতুন jar চালু হয়নি — আগের jar-এ ফেরা"
    cp -p "$API_JAR.prev" "$API_JAR"
    systemctl restart lifolo-api.service
    if wait_health 90; then st rollback ok; else st rollback failed; fi
  else
    st rollback none
  fi
  die "অ্যাপ চালু হয়নি (/api/health সাড়া দেয়নি)"
fi

# ---------------------------------------------------------------- 5) nginx
say "nginx: /api/, /r/, /rx/, /shop/, /.well-known/ → 127.0.0.1:$API_PORT"
loc() {
  cat <<EOF
location $1 {
    proxy_pass http://127.0.0.1:$API_PORT;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout ${2:-60s};
    client_max_body_size 10m;${3:-}
}
EOF
}
cp -p "$SNIPPET" "$SNIPPET.bak" 2>/dev/null || true
{
  echo "# Lifolo — managed by deploy-backend.sh (Deploy backend workflow). setup-base.sh এই ফাইল আর বদলায় না।"
  echo "# অ্যাপের পথগুলো → Spring Boot (127.0.0.1:$API_PORT); /healthz আর বাকি সব lifolo-common.conf-এ।"
  echo "# X-Forwarded-For = শুধু আসল IP (অতিথি আর লগইন-কোডের সীমা এটা দেখে; বাইরের দেওয়া মান নেওয়া হয় না)।"
  loc /api/
  # SSE (নোটিফিকেশনের সরাসরি ধারা): buffering বন্ধ, লম্বা সময় খোলা
  loc /api/events 1h "
    proxy_buffering off;
    proxy_cache off;"
  loc /r/
  loc /rx/
  loc /shop/
  loc /.well-known/
} > "$SNIPPET.new"
chmod 0644 "$SNIPPET.new"
mv "$SNIPPET.new" "$SNIPPET"
if nginx -t 2>/dev/null; then
  systemctl reload nginx
  st nginx ok
else
  nginx -t || true
  [ -f "$SNIPPET.bak" ] && mv "$SNIPPET.bak" "$SNIPPET" && systemctl reload nginx || true
  st nginx failed
fi

# ---------------------------------------------------------------- 5b) আলাদা নাম (api.lifolo.com)
API_SITE=/etc/nginx/sites-available/lifolo-api-domain
if [ -n "$API_DOMAIN" ]; then
  say "আলাদা নাম: $API_DOMAIN"
  if ! [[ "$API_DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]]; then
    st api_domain "invalid name"
  else
    MY_IPS=" $(hostname -I 2>/dev/null || true) "
    DNS_IPS="$(getent ahostsv4 "$API_DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
    matched=no
    for ip in $DNS_IPS; do case "$MY_IPS" in *" $ip "*) matched=yes ;; esac; done
    CERT="/etc/letsencrypt/live/$API_DOMAIN/fullchain.pem"
    if [ "$matched" != yes ]; then
      st api_domain "dns_not_ready (${DNS_IPS:-no A record})"
    else
      # port 80-এর default_server-এ acme-challenge আগে থেকেই আছে (setup-base.sh), তাই webroot-এ সরাসরি
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
if [ "$(env_get api_domain "$STATUS")" = ok ]; then
  check api_domain_health "curl -sS -m 15 --resolve '$API_DOMAIN:443:127.0.0.1' 'https://$API_DOMAIN/api/health' | grep -q '\"ok\":true'"
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
