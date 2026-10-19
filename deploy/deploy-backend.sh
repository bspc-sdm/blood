#!/usr/bin/env bash
# Lifolo — Spring Boot API বসানো/আপডেট (VPS-এ root হিসেবে)। সাধারণত GitHub Actions-এর "Deploy backend"
# (.github/workflows/deploy-backend.yml) চালায়; আগে "Setup VPS (base)" (setup-base.sh) হয়ে থাকতে হবে।
#
#   bash deploy-backend.sh --domain 169-58-58-176.sslip.io --jar /root/lifolo-setup/app.jar.new --sha256 <hash> \
#        --provided /root/lifolo-setup/provided.env [--demo-smoke <owner-uid> <owner-email>] [--ci]
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

DOMAIN="" JAR="" SHA="" PROVIDED="" OWNER_UID="" OWNER_EMAIL="" CI_MODE=no
while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --sha256) SHA="$2"; shift 2 ;;
    --provided) PROVIDED="$2"; shift 2 ;;
    --demo-smoke) OWNER_UID="$2"; OWNER_EMAIL="$3"; shift 3 ;;
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
  mask "$v"
  printf '%s' "$v"
}
CRON_SECRET="$(keep_or_make CRON_SECRET)"
WEB_SESSION_SECRET="$(keep_or_make WEB_SESSION_SECRET)"
EMAIL_CODE_SECRET="$(keep_or_make EMAIL_CODE_SECRET)"
p() { env_get "$1" "$PROVIDED"; }
FIREBASE_B64="$(p FIREBASE_SERVICE_ACCOUNT)"
tmp="$(mktemp /etc/lifolo/.app.env.XXXXXX)"
{
  echo "# Lifolo API — deploy-backend.sh লেখে (প্রতি deploy-এ আবার)। স্থায়ী চাবিগুলো থেকে যায়। হাতে বদলাবেন না।"
  echo "REDIS_URL=redis://:${REDIS_PASS}@127.0.0.1:6379"
  echo "CRON_SECRET=$CRON_SECRET"
  echo "WEB_SESSION_SECRET=$WEB_SESSION_SECRET"
  echo "EMAIL_CODE_SECRET=$EMAIL_CODE_SECRET"
  echo "JOBRUNR_DASHBOARD_ENABLED=false"
  echo "SENTRY_ENVIRONMENT=production"
  echo "PUBLIC_BASE_URL=$(p PUBLIC_BASE_URL | grep -E '^https?://[A-Za-z0-9.:/-]+$' || echo "https://$DOMAIN")"
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
check actuator_hidden "! H '$B/actuator/health' | grep -q '\"status\"'"
check api_guest "H -X POST -H 'content-type: application/json' --data '{\"action\":\"__nope__\",\"lang\":\"bn\"}' '$B/api/community' | grep -q 'error'"

# ডেমো বানানো আর মোছা — API দিয়ে, মালিকের অ্যাডমিন-প্যানেল সেশন (কুকি) এখানেই WEB_SESSION_SECRET দিয়ে সই করে
if [ -n "$OWNER_UID" ]; then
  say "ডেমো: বানানো → চালু কি না → মোছা"
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
  if [ "$(printf '%s' "$before" | jget active)" = true ]; then
    st demo_seed skipped_active
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
  fi
  rm -f "$HDR"
  unset COOKIE
fi
unset CRON_SECRET WEB_SESSION_SECRET EMAIL_CODE_SECRET
st finished yes
echo
cat "$STATUS"
