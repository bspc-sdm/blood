#!/usr/bin/env bash
# Lifolo — অ্যাপের ভেতরের কল (LiveKit, self-hosted)। root হিসেবে চালান; আবার চালালেও ক্ষতি নেই (idempotent)।
# সাধারণত GitHub Actions-এর "Setup calls (LiveKit)" workflow (.github/workflows/setup-call-server.yml) চালায়।
# বাংলা গাইড: deploy/CALLS-SERVER.md
#
# দুই রকম:
#   --mode main        এখন (পরীক্ষার পর্ব): মূল VPS-এই, API-র পাশে। signaling wss://lifolo.com/rtc (Cloudflare → nginx →
#                      127.0.0.1:7880), মিডিয়া সরাসরি VPS-এর IP-তে (7881/tcp, 3478/udp TURN, 50000-60000/udp)। 80/443 যেমন
#                      ছিল (শুধু Cloudflare) তেমনই। TURN/TLS নেই। LiveKit-এর CPU/RAM-এ সীমা (API যেন ভুগে না), ঘরে সর্বোচ্চ ২ জন।
#                      অ্যাপের জন্য /etc/livekit/backend.env (LIVEKIT_URL/KEY/SECRET) — Deploy backend সেখান থেকে app.env-এ নেয়।
#   --mode dedicated   পরে (Singapore-এ সরার সময়): আলাদা ছোট VPS, নিজের নাম call.lifolo.com (DNS only)। এখানে নিজেই
#                      unattended-upgrades, fail2ban, ufw, nginx + Let's Encrypt; --turn-domain দিলে (যেমন turn.lifolo.com)
#                      TURN/TLS 443-এ (nginx stream, SNI দিয়ে ভাগ) — কড়া ফায়ারওয়ালের নেটওয়ার্কেও কল চলে।
#
# আর্গুমেন্ট:
#   --keys FILE         LiveKit-এর key_file ("APIxxxxxxxxxxxx: <secret>"; deploy/ci/livekit-keys.js --keyfile) — দরকারি
#   --public-url URL    (main) অ্যাপ যে ঠিকানা পাবে, যেমন wss://lifolo.com (ক্লায়েন্ট নিজে /rtc যোগ করে)
#   --node-ip IP        VPS-এর public IPv4 (না দিলে বাইরের route-এর src)
#   --domain D          (dedicated) যেমন call.lifolo.com
#   --turn-domain T     (dedicated, ঐচ্ছিক) যেমন turn.lifolo.com
#   --email E           (dedicated) Let's Encrypt-এর ইমেইল (ঐচ্ছিক)
#   --test-cert DIR     (শুধু CI) Let's Encrypt-এর বদলে DIR/fullchain.pem + DIR/privkey.pem
#   --skip-upgrade      (শুধু CI) apt upgrade বাদ
#   --ci                GitHub Actions: গোপন মান লগে লুকানো
# ফল: /var/lib/lifolo/call-status (key=value, গোপন কিছু নয়)। কোনো গোপন মান ছাপা হয় না।
set -euo pipefail

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*" >&2; WARNINGS+=("$*"); }
die() { printf '\033[1;31mXX %s\033[0m\n' "$*" >&2; exit 1; }
WARNINGS=()

# LiveKit — পিন করা সংস্করণ আর GitHub release-এর checksums.txt থেকে sha256 (বদলালে দুটোই একসাথে)
LK_VERSION=1.13.10
LK_SHA256_amd64=c25a3e45fb922e9a93c6443d10c0ed041ba64342097e38632331736630283165
LK_SHA256_arm64=ff60607e1931ff078576882fa11c087aa5821d635ac0c47f56ff77e203e32006

LK_USER=livekit
LK_ETC=/etc/livekit
LK_CONF=/etc/livekit/livekit.yaml
LK_KEYS=/etc/livekit/keys.yaml
LK_TLS=/etc/livekit/tls
LK_UNIT=/etc/systemd/system/livekit-server.service
BACKEND_ENV=/etc/livekit/backend.env
STATUS_DIR=/var/lib/lifolo
STATUS=/var/lib/lifolo/call-status
SIGNAL_PORT=7880
# LiveKit webhook → API (main: same machine; dedicated: through the public API). LIFOLO_WEBHOOK_URL overrides.
API_PORT=8080
RTC_TCP=7881
TURN_UDP=3478
TURN_TLS_INTERNAL=5349
UDP_START=50000
UDP_END=60000
HTTPS_INTERNAL=8443
CALLS_SNIPPET=/etc/nginx/snippets/lifolo-calls.conf
CALLS_MAP=/etc/nginx/conf.d/lifolo-calls-map.conf
API_SNIPPET=/etc/nginx/snippets/lifolo-api.conf
STREAM_CONF=/etc/nginx/lifolo-stream.conf
ACME=/var/www/letsencrypt
DOMAIN_RE='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$'
IP_RE='^[0-9]{1,3}(\.[0-9]{1,3}){3}$'

MODE="" KEYS_IN="" PUBLIC_URL="" NODE_IP="" DOMAIN="" TURN_DOMAIN="" EMAIL="" TEST_CERT="" SKIP_UPGRADE=no CI_MODE=no
declare -A ST=()
st() { ST[$1]="$2"; }

write_status() {
  install -d -m 0755 "$STATUS_DIR"
  {
    for k in "${!ST[@]}"; do printf '%s=%s\n' "$k" "${ST[$k]}"; done | sort
    printf 'warnings=%s\n' "${#WARNINGS[@]}"
  } > "$STATUS.new"
  mv "$STATUS.new" "$STATUS"
}

# ---------------------------------------------------------------- আর্গুমেন্ট
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    --keys) KEYS_IN="${2:-}"; shift 2 ;;
    --public-url) PUBLIC_URL="${2:-}"; shift 2 ;;
    --node-ip) NODE_IP="${2:-}"; shift 2 ;;
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --turn-domain) TURN_DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --test-cert) TEST_CERT="${2:-}"; shift 2 ;;
    --skip-upgrade) SKIP_UPGRADE=yes; shift ;;
    --ci) CI_MODE=yes; shift ;;
    *) die "অচেনা আর্গুমেন্ট: $1 (দেখুন এই ফাইলের শুরুর লেখা)" ;;
  esac
done

cd /
[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"
case "$MODE" in main|dedicated) ;; *) die "--mode main বা --mode dedicated দিন" ;; esac
if [ "$MODE" = main ]; then WEBHOOK_URL="${LIFOLO_WEBHOOK_URL:-http://127.0.0.1:$API_PORT/api/livekit/webhook}"
else WEBHOOK_URL="${LIFOLO_WEBHOOK_URL:-https://lifolo.com/api/livekit/webhook}"; fi
[ -f "$KEYS_IN" ] || die "--keys ফাইল নেই"
KEY_LINE="$(tr -d '\r' < "$KEYS_IN" | grep -E '^API[0-9A-Za-z]{12}: [0-9A-Za-z]{43}$' | head -1 || true)"
[ -n "$KEY_LINE" ] || die "--keys ফাইলের গঠন ঠিক নেই (livekit-keys.js --keyfile দিয়ে বানান)"
API_KEY="${KEY_LINE%%:*}"
API_SECRET="${KEY_LINE#*: }"
if [ "$CI_MODE" = yes ]; then printf '::add-mask::%s\n::add-mask::%s\n' "$API_KEY" "$API_SECRET"; fi

if [ -z "$NODE_IP" ]; then
  NODE_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | sed -nE 's/.* src ([0-9.]+).*/\1/p' | head -1)"
fi
[[ "$NODE_IP" =~ $IP_RE ]] || die "VPS-এর IPv4 পাওয়া গেল না (--node-ip দিন)"
if [ "$CI_MODE" = yes ]; then printf '::add-mask::%s\n' "$NODE_IP"; fi
if ! ip -4 -o addr show 2>/dev/null | grep -q " $NODE_IP/"; then
  warn "node IP এই মেশিনের কোনো interface-এ নেই (NAT?) — বাইরের ঠিকানা হিসেবেই ঘোষণা করা হবে"
fi

if [ "$MODE" = main ]; then
  PUBLIC_URL="${PUBLIC_URL:-wss://lifolo.com}"
  [[ "$PUBLIC_URL" =~ ^wss://[a-z0-9.-]+$ ]] || die "--public-url যেমন wss://lifolo.com (শেষে / বা পথ ছাড়া)"
  SIGNAL_HOST="${PUBLIC_URL#wss://}"
  command -v nginx >/dev/null || die "nginx নেই — এটা মূল VPS নয়? আগে 'Setup VPS (base)' আর 'Deploy backend'"
  [ -f "$API_SNIPPET" ] || die "$API_SNIPPET নেই — আগে 'Deploy backend' চালান"
else
  DOMAIN="$(printf '%s' "$DOMAIN" | tr '[:upper:]' '[:lower:]')"
  TURN_DOMAIN="$(printf '%s' "$TURN_DOMAIN" | tr '[:upper:]' '[:lower:]')"
  [[ "$DOMAIN" =~ $DOMAIN_RE ]] || die "--domain ঠিক নেই (যেমন call.lifolo.com)"
  [ -z "$TURN_DOMAIN" ] || [[ "$TURN_DOMAIN" =~ $DOMAIN_RE ]] || die "--turn-domain ঠিক নেই"
  [ "$TURN_DOMAIN" != "$DOMAIN" ] || die "--turn-domain আলাদা নাম হতে হবে (443-এ নাম দেখে ভাগ হয়)"
  [ -z "$EMAIL" ] || [[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "ইমেইল ঠিক নেই"
  PUBLIC_URL="wss://$DOMAIN"
  SIGNAL_HOST="$DOMAIN"
fi
if [ -n "$TEST_CERT" ]; then
  [ -f "$TEST_CERT/fullchain.pem" ] && [ -f "$TEST_CERT/privkey.pem" ] || die "--test-cert-এ fullchain.pem/privkey.pem নেই"
fi
st mode "$MODE"
st public_url "$PUBLIC_URL"

export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1
APT=(apt-get -y -q -o DPkg::Lock::Timeout=600 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

# ---------------------------------------------------------------- ১) আলাদা VPS-এর ভিত্তি
if [ "$MODE" = dedicated ]; then
  say "apt: আপডেট আর প্যাকেজ (ufw, fail2ban, unattended-upgrades, nginx + stream, certbot)"
  "${APT[@]}" update
  [ "$SKIP_UPGRADE" = yes ] || "${APT[@]}" upgrade
  # বসানোর সময় nginx নিজে চালু নয় — নিচে নিজেদের সেটিং বসিয়ে আমরাই চালু করি
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 0755 /usr/sbin/policy-rc.d
  trap 'rm -f /usr/sbin/policy-rc.d' EXIT
  "${APT[@]}" install ca-certificates curl openssl iproute2 python3 ufw fail2ban unattended-upgrades nginx libnginx-mod-stream certbot \
    || die "প্যাকেজ বসানো যায়নি"
  rm -f /usr/sbin/policy-rc.d
  timedatectl set-timezone UTC 2>/dev/null || true

  say "নিরাপত্তা আপডেট নিজে নিজে (unattended-upgrades)"
  cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
  systemctl enable --now unattended-upgrades >/dev/null 2>&1 || warn "unattended-upgrades চালু করা গেল না"

  say "fail2ban (SSH-এ বারবার ভুল চেষ্টা করলে IP বন্ধ)"
  cat > /etc/fail2ban/jail.d/lifolo.local <<'EOF'
[sshd]
enabled = true
backend = systemd
maxretry = 5
findtime = 10m
bantime = 1h

[recidive]
enabled = true
backend = auto
logpath = /var/log/fail2ban.log
bantime = 1w
findtime = 1d
maxretry = 3
EOF
  touch /var/log/fail2ban.log
  systemctl enable fail2ban >/dev/null 2>&1 || true
  systemctl restart fail2ban || warn "fail2ban চালু হয়নি"

  say "journald: সর্বোচ্চ 500 MB, ১৪ দিন (কলের লগে uid আর IP থাকে)"
  install -d -m 0755 /etc/systemd/journald.conf.d
  printf '[Journal]\nSystemMaxUse=500M\nMaxFileSec=1day\nMaxRetentionSec=14day\n' > /etc/systemd/journald.conf.d/lifolo.conf
  systemctl restart systemd-journald 2>/dev/null || true
fi

# ---------------------------------------------------------------- ২) LiveKit
say "LiveKit $LK_VERSION (GitHub release, sha256 যাচাইসহ)"
ARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
case "$ARCH" in amd64|x86_64) ARCH=amd64; SHA="$LK_SHA256_amd64" ;; arm64|aarch64) ARCH=arm64; SHA="$LK_SHA256_arm64" ;; *) die "অচেনা CPU: $ARCH" ;; esac
LK_DIR="/opt/livekit/$LK_VERSION"
if [ ! -x "$LK_DIR/livekit-server" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/lk.tgz" "https://github.com/livekit/livekit/releases/download/v$LK_VERSION/livekit_${LK_VERSION}_linux_$ARCH.tar.gz" \
    || die "LiveKit নামানো গেল না"
  echo "$SHA  $tmp/lk.tgz" | sha256sum -c --quiet - || die "LiveKit-এর sha256 মেলেনি — নামানো ফাইল বাতিল"
  install -d -m 0755 "$LK_DIR"
  tar -xzf "$tmp/lk.tgz" -C "$tmp"
  install -m 0755 "$tmp/livekit-server" "$LK_DIR/livekit-server"
  rm -rf "$tmp"
fi
ln -sfn "$LK_DIR/livekit-server" /usr/local/bin/livekit-server
st livekit "$("$LK_DIR/livekit-server" --version 2>/dev/null | awk '{print $NF}')"

id "$LK_USER" >/dev/null 2>&1 || useradd --system --home-dir /var/lib/livekit --create-home --shell /usr/sbin/nologin "$LK_USER"
install -d -o root -g "$LK_USER" -m 0750 "$LK_ETC"
( umask 027; printf '%s\n' "$KEY_LINE" > "$LK_KEYS.new" )
chown root:"$LK_USER" "$LK_KEYS.new"; chmod 0640 "$LK_KEYS.new"; mv "$LK_KEYS.new" "$LK_KEYS"

say "UDP-র বাফার (LiveKit-এর সুপারিশ)"
cat > /etc/sysctl.d/60-lifolo-livekit.conf <<'EOF'
# Lifolo — setup-call-server.sh: WebRTC-র UDP বাফার
net.core.rmem_max = 5000000
net.core.wmem_max = 5000000
EOF
sysctl -q -p /etc/sysctl.d/60-lifolo-livekit.conf 2>/dev/null || warn "sysctl বসানো গেল না"

# ---------------------------------------------------------------- ৩) সার্টিফিকেট (শুধু dedicated)
MY_IPS=" $(hostname -I 2>/dev/null || true) "
dns_here() { # নামটা এই VPS-এ আসে কি না (Cloudflare-এর কমলা মেঘ হলে আসে না — কলের নাম DNS only হতে হবে)
  local ip ips
  ips="$(getent ahostsv4 "$1" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
  for ip in $ips; do case "$MY_IPS" in *" $ip "*) return 0 ;; esac; [ "$ip" = "$NODE_IP" ] && return 0; done
  return 1
}
CERT_FILE="" KEY_FILE="" TURN_TLS=off
if [ "$MODE" = dedicated ]; then
  install -d -m 0755 "$ACME"
  install -d -o root -g "$LK_USER" -m 0750 "$LK_TLS"
  if [ -n "$TEST_CERT" ]; then
    install -o root -g "$LK_USER" -m 0640 "$TEST_CERT/fullchain.pem" "$LK_TLS/fullchain.pem"
    install -o root -g "$LK_USER" -m 0640 "$TEST_CERT/privkey.pem" "$LK_TLS/privkey.pem"
    CERT_FILE="$LK_TLS/fullchain.pem"; KEY_FILE="$LK_TLS/privkey.pem"
    st cert test
    [ -z "$TURN_DOMAIN" ] || TURN_TLS=on
  else
    names=()
    if dns_here "$DOMAIN"; then names+=(-d "$DOMAIN"); else warn "$DOMAIN এই VPS-এ আসে না (DNS A রেকর্ড, ধূসর মেঘ?) — সার্টিফিকেট পরে"; fi
    if [ -n "$TURN_DOMAIN" ]; then
      if dns_here "$TURN_DOMAIN"; then names+=(-d "$TURN_DOMAIN"); else warn "$TURN_DOMAIN এই VPS-এ আসে না — TURN/TLS আপাতত বন্ধ"; fi
    fi
    LE=/etc/letsencrypt/live/lifolo-call
    need=no
    if [ "${#names[@]}" -gt 0 ] && [ "${names[1]}" = "$DOMAIN" ]; then
      need=yes
      if [ -f "$LE/fullchain.pem" ]; then
        need=no
        for ((i = 1; i < ${#names[@]}; i += 2)); do
          openssl x509 -in "$LE/fullchain.pem" -noout -checkhost "${names[$i]}" 2>/dev/null | grep -q 'does match' || need=yes
        done
      fi
    fi
    if [ "$need" = yes ]; then
      # প্রথমবার: ACME-র জন্য শুধু http (80); পরে আমাদের নিজের সেটিংয়েই 80-এ /.well-known আছে
      if [ ! -f /etc/nginx/sites-available/lifolo-call ]; then
        cat > /etc/nginx/sites-available/lifolo-call <<EOF
# Lifolo — setup-call-server.sh (dedicated): সাময়িক, সার্টিফিকেটের আগে
server {
    listen 80 default_server;
    server_name _;
    location ^~ /.well-known/acme-challenge/ { root $ACME; }
    location / { return 404; }
}
EOF
        ln -sfn /etc/nginx/sites-available/lifolo-call /etc/nginx/sites-enabled/lifolo-call
        rm -f /etc/nginx/sites-enabled/default
        [ -f "$STREAM_CONF" ] || : > "$STREAM_CONF"
        grep -qxF "include $STREAM_CONF;" /etc/nginx/nginx.conf || printf '\ninclude %s;\n' "$STREAM_CONF" >> /etc/nginx/nginx.conf
        nginx -t 2>/dev/null && { systemctl enable nginx >/dev/null 2>&1; systemctl restart nginx; }
      fi
      ufw allow 80/tcp >/dev/null 2>&1 || true
      email_args=(--register-unsafely-without-email)
      [ -z "$EMAIL" ] || email_args=(-m "$EMAIL" --no-eff-email)
      if certbot certonly --webroot -w "$ACME" --cert-name lifolo-call "${names[@]}" "${email_args[@]}" --agree-tos --non-interactive \
          --keep-until-expiring --expand --deploy-hook "systemctl reload nginx"; then
        :
      else
        warn "Let's Encrypt সার্টিফিকেট পাওয়া যায়নি (পোর্ট 80 বাইরে থেকে খোলা? DNS ঠিক?) — ১৫ মিনিট পর আবার চালান"
      fi
    fi
    if [ -f "$LE/fullchain.pem" ]; then
      install -o root -g "$LK_USER" -m 0640 "$LE/fullchain.pem" "$LK_TLS/fullchain.pem"
      install -o root -g "$LK_USER" -m 0640 "$LE/privkey.pem" "$LK_TLS/privkey.pem"
      CERT_FILE="$LE/fullchain.pem"; KEY_FILE="$LE/privkey.pem"
      st cert ok
      if [ -n "$TURN_DOMAIN" ] && openssl x509 -in "$LE/fullchain.pem" -noout -checkhost "$TURN_DOMAIN" 2>/dev/null | grep -q 'does match'; then
        TURN_TLS=on
      fi
    else
      st cert "$([ "${#names[@]}" -gt 0 ] && echo failed || echo no-dns)"
    fi
    systemctl enable --now certbot.timer >/dev/null 2>&1 || true
    # LiveKit নিজের কপি পড়ে (livekit ব্যবহারকারী /etc/letsencrypt পড়তে পারে না): নতুন সার্টিফিকেট এলে রাতে (ঢাকা ~৩:৩০) কপি + restart
    cat > /usr/local/sbin/lifolo-livekit-cert <<EOF
#!/bin/sh
# Lifolo — setup-call-server.sh: Let's Encrypt-এর নতুন সার্টিফিকেট LiveKit-এর TURN/TLS-এ (দিনে একবার, রাতে)
set -e
LE=$LE
[ -f "\$LE/fullchain.pem" ] || exit 0
cmp -s "\$LE/fullchain.pem" $LK_TLS/fullchain.pem && exit 0
install -o root -g $LK_USER -m 0640 "\$LE/fullchain.pem" $LK_TLS/fullchain.pem
install -o root -g $LK_USER -m 0640 "\$LE/privkey.pem" $LK_TLS/privkey.pem
systemctl try-restart livekit-server
EOF
    chmod 0755 /usr/local/sbin/lifolo-livekit-cert
    cat > /etc/systemd/system/lifolo-livekit-cert.service <<'EOF'
[Unit]
Description=Lifolo: copy renewed certificate to LiveKit (TURN/TLS)
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/lifolo-livekit-cert
EOF
    cat > /etc/systemd/system/lifolo-livekit-cert.timer <<'EOF'
[Unit]
Description=Lifolo: nightly LiveKit certificate sync (03:30 Asia/Dhaka)
[Timer]
OnCalendar=*-*-* 21:30:00 UTC
Persistent=true
[Install]
WantedBy=timers.target
EOF
    systemctl daemon-reload
    systemctl enable --now lifolo-livekit-cert.timer >/dev/null 2>&1 || true
  fi
else
  st cert "n/a (Cloudflare → lifolo.com)"
fi
st turn_tls "$TURN_TLS"

# ---------------------------------------------------------------- ৪) LiveKit-এর সেটিং + systemd
say "LiveKit-এর সেটিং: $LK_CONF"
{
  echo "# Lifolo — setup-call-server.sh লেখে ($MODE)। হাতে বদলালে পরের বার মুছে যাবে। গাইড: deploy/CALLS-SERVER.md"
  echo "port: $SIGNAL_PORT"
  echo "# signaling শুধু nginx দিয়ে (TLS সেখানে); মিডিয়ার পোর্ট সবার জন্য"
  echo "bind_addresses:"
  echo "  - 127.0.0.1"
  echo "key_file: $LK_KEYS"
  echo "rtc:"
  echo "  tcp_port: $RTC_TCP"
  echo "  port_range_start: $UDP_START"
  echo "  port_range_end: $UDP_END"
  echo "  # VPS-এর public IP সরাসরি (STUN দিয়ে খোঁজা নয়)"
  echo "  use_external_ip: false"
  echo "  node_ip: $NODE_IP"
  echo "turn:"
  echo "  enabled: true"
  echo "  udp_port: $TURN_UDP"
  echo "  # relay-র পোর্ট শুধু এই মেশিনের ভেতরে (TURN → SFU), ফায়ারওয়ালে খোলা লাগে না"
  echo "  relay_range_start: 30000"
  echo "  relay_range_end: 40000"
  if [[ "$NODE_IP" =~ ^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.) ]]; then
    # node IP private (NAT-এর পেছনে / CI): LiveKit private ঠিকানায় relay করে না — শুধু নিজের ঠিকানাটা ছাড়
    # (embedded TURN এমনিতেও শুধু এই মেশিনের মিডিয়া-পোর্টে relay করে)
    echo "  allow_restricted_peer_cidrs:"
    echo "    - $NODE_IP/32"
  fi
  if [ "$TURN_TLS" = on ]; then
    echo "  # TURN/TLS: বাইরে turns:$TURN_DOMAIN:443 (nginx stream, SNI) → এখানে $TURN_TLS_INTERNAL"
    echo "  domain: $TURN_DOMAIN"
    echo "  tls_port: $TURN_TLS_INTERNAL"
    echo "  cert_file: $LK_TLS/fullchain.pem"
    echo "  key_file: $LK_TLS/privkey.pem"
  fi
  echo "room:"
  echo "  # ১:১ কল — ঘরে সর্বোচ্চ ২ জন"
  echo "  max_participants: 2"
  echo "  empty_timeout: 60"
  echo "  departure_timeout: 20"
  # ঘর বা কেউ বেরিয়ে গেলে API-কে জানায় (আটকে থাকা কল শেষ করে) — POST /api/livekit/webhook, API key দিয়ে সই করা
  echo "webhook:"
  echo "  api_key: $API_KEY"
  echo "  urls:"
  echo "    - $WEBHOOK_URL"
  echo "logging:"
  echo "  level: info"
} > "$LK_CONF.new"
chown root:"$LK_USER" "$LK_CONF.new"; chmod 0640 "$LK_CONF.new"; mv "$LK_CONF.new" "$LK_CONF"

LIMITS=""
if [ "$MODE" = main ]; then
  # API-র পাশে: CPU-র এক-চতুর্থাংশ (কমপক্ষে ৫০%, বেশি হলে ২০০%), RAM 512 MB; ভিড়ে API আগে পায় (CPUWeight)
  cores="$(nproc 2>/dev/null || echo 2)"
  q=$((cores * 25)); [ "$q" -ge 50 ] || q=50; [ "$q" -le 200 ] || q=200
  LIMITS="CPUQuota=${q}%
CPUWeight=50
MemoryHigh=448M
MemoryMax=512M
Environment=GOMEMLIMIT=400MiB
TasksMax=512
IOWeight=50"
  st cpu_quota "${q}% of ${cores} cores"
  st memory_max 512M
else
  st cpu_quota none
  st memory_max none
fi
cat > "$LK_UNIT.new" <<EOF
# Lifolo — setup-call-server.sh লেখে ($MODE)
[Unit]
Description=LiveKit server (Lifolo in-app calls)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$LK_USER
Group=$LK_USER
ExecStart=/usr/local/bin/livekit-server --config $LK_CONF
Restart=on-failure
RestartSec=5
TimeoutStopSec=20
LimitNOFILE=65536
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
ReadWritePaths=/var/lib/livekit
$LIMITS

[Install]
WantedBy=multi-user.target
EOF
chmod 0644 "$LK_UNIT.new"; mv "$LK_UNIT.new" "$LK_UNIT"
systemctl daemon-reload
systemctl enable livekit-server >/dev/null 2>&1
systemctl restart livekit-server

# ---------------------------------------------------------------- ৫) ফায়ারওয়াল
say "ফায়ারওয়াল (ufw): মিডিয়ার পোর্ট সবার জন্য — $RTC_TCP/tcp, $TURN_UDP/udp, $UDP_START-$UDP_END/udp"
if [ "$MODE" = dedicated ]; then
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
fi
# main: 80/443 ছোঁয়া হয় না (tune-vps.sh-এর Cloudflare-নিয়ম যেমন আছে); tune-vps.sh শুধু 80/443-এর নিয়ম মোছে
ufw allow "$RTC_TCP/tcp" comment 'lifolo-livekit' >/dev/null
ufw allow "$TURN_UDP/udp" comment 'lifolo-livekit' >/dev/null
ufw allow "$UDP_START:$UDP_END/udp" comment 'lifolo-livekit' >/dev/null
if [ "$MODE" = dedicated ]; then
  ufw --force enable >/dev/null
fi
if ufw status 2>/dev/null | head -1 | grep -q 'Status: active'; then
  st firewall "active; livekit rules $(ufw show added 2>/dev/null | grep -c "lifolo-livekit" || true)"
else
  st firewall inactive
  warn "ufw চালু নেই (মূল VPS-এ 'Setup VPS (base)' চালু করে)"
fi

# ---------------------------------------------------------------- ৬) nginx (signaling)
say "nginx: $PUBLIC_URL/rtc → 127.0.0.1:$SIGNAL_PORT (WebSocket, লম্বা সময়)"
cat > "$CALLS_MAP.new" <<'EOF'
# Lifolo — setup-call-server.sh: WebSocket upgrade (LiveKit-এর /rtc)
map $http_upgrade $lifolo_calls_connection {
    default upgrade;
    ''      close;
}
EOF
rtc_loc() {
  echo "location $1 {"
  echo "    proxy_pass http://127.0.0.1:$SIGNAL_PORT;"
  echo "    proxy_http_version 1.1;"
  echo "    proxy_set_header Upgrade \$http_upgrade;"
  echo "    proxy_set_header Connection \$lifolo_calls_connection;"
  echo "    proxy_set_header Host \$host;"
  echo "    proxy_set_header X-Real-IP \$remote_addr;"
  echo "    proxy_set_header X-Forwarded-For \$remote_addr;"
  echo "    proxy_set_header X-Forwarded-Proto \$scheme;"
  echo "    proxy_connect_timeout 5s;"
  echo "    proxy_read_timeout 1h;"
  echo "    proxy_send_timeout 1h;"
  echo "    proxy_buffering off;"
  echo "    # ঠিকানায় access_token (JWT) থাকে — লগে নয়"
  echo "    access_log off;"
  echo "}"
}
{
  echo "# Lifolo — setup-call-server.sh লেখে: LiveKit-এর signaling (ক্লায়েন্ট <LIVEKIT_URL>/rtc, /rtc/v1, /rtc/validate খোলে)।"
  echo "# deploy-backend.sh-এর lifolo-api.conf এটা include করে (lifolo-calls*.conf) — lifolo.com আর api.lifolo.com দুটোতেই।"
  rtc_loc "= /rtc"
  rtc_loc "^~ /rtc/"
} > "$CALLS_SNIPPET.new"
chmod 0644 "$CALLS_MAP.new" "$CALLS_SNIPPET.new"
for f in "$CALLS_MAP" "$CALLS_SNIPPET" "$STREAM_CONF" /etc/nginx/sites-available/lifolo-call "$API_SNIPPET"; do
  [ ! -f "$f" ] || cp -p "$f" "$f.bak-calls"
done
mv "$CALLS_MAP.new" "$CALLS_MAP"
mv "$CALLS_SNIPPET.new" "$CALLS_SNIPPET"

if [ "$MODE" = main ]; then
  # deploy-backend.sh (নতুনটা) নিজেই এই include লেখে; পুরোনো deploy-এর snippet-এ না থাকলে এখনই যোগ
  if ! grep -qF 'lifolo-calls' "$API_SNIPPET"; then
    printf '\n# setup-call-server.sh যোগ করেছে (পরের Deploy backend নিজেই লেখে)\ninclude /etc/nginx/snippets/lifolo-calls*.conf;\n' >> "$API_SNIPPET"
  fi
else
  ssl_lines() {
    echo "    ssl_certificate $1;"
    echo "    ssl_certificate_key $2;"
    echo "    ssl_protocols TLSv1.2 TLSv1.3;"
    echo "    ssl_prefer_server_ciphers off;"
    echo "    ssl_session_cache shared:lifolo_call_ssl:10m;"
    echo "    ssl_session_timeout 1d;"
    echo "    ssl_session_tickets off;"
  }
  {
    echo "# Lifolo — setup-call-server.sh লেখে (dedicated): $DOMAIN"
    echo "server {"
    echo "    listen 80 default_server;"
    echo "    server_name _;"
    echo "    location ^~ /.well-known/acme-challenge/ { root $ACME; }"
    if [ -n "$CERT_FILE" ]; then
      echo "    location / { return 301 https://$DOMAIN\$request_uri; }"
    else
      echo "    location = /healthz { proxy_pass http://127.0.0.1:$SIGNAL_PORT/; access_log off; }"
      echo "    location / { return 404; }"
    fi
    echo "}"
    if [ -n "$CERT_FILE" ]; then
      echo "server {"
      if [ "$TURN_TLS" = on ]; then
        echo "    # 443-এ nginx stream (SNI দেখে): $TURN_DOMAIN → LiveKit TURN/TLS, বাকি সব → এখানে"
        echo "    listen 127.0.0.1:$HTTPS_INTERNAL ssl http2;"
      else
        echo "    listen 443 ssl http2 default_server;"
      fi
      echo "    server_name $DOMAIN;"
      ssl_lines "$CERT_FILE" "$KEY_FILE"
      echo "    add_header Strict-Transport-Security \"max-age=31536000\" always;"
      echo "    location = /healthz { proxy_pass http://127.0.0.1:$SIGNAL_PORT/; access_log off; }"
      echo "    include $CALLS_SNIPPET;"
      echo "    location / { return 404; }"
      echo "}"
    fi
  } > /etc/nginx/sites-available/lifolo-call
  ln -sfn /etc/nginx/sites-available/lifolo-call /etc/nginx/sites-enabled/lifolo-call
  rm -f /etc/nginx/sites-enabled/default
  echo 'server_tokens off;' > /etc/nginx/conf.d/lifolo-hardening.conf
  if [ "$TURN_TLS" = on ]; then
    cat > "$STREAM_CONF" <<EOF
# Lifolo — setup-call-server.sh: 443-এ নাম (SNI) দেখে ভাগ — TLS এখানে খোলা হয় না, যার যার কাছে যায়
stream {
    map \$ssl_preread_server_name \$lifolo_443_upstream {
        $TURN_DOMAIN 127.0.0.1:$TURN_TLS_INTERNAL;
        default 127.0.0.1:$HTTPS_INTERNAL;
    }
    server {
        listen 443;
        ssl_preread on;
        proxy_pass \$lifolo_443_upstream;
        proxy_connect_timeout 5s;
        proxy_timeout 1h;
    }
}
EOF
  else
    echo "# Lifolo — setup-call-server.sh: TURN/TLS বন্ধ (stream নেই)" > "$STREAM_CONF"
  fi
  chmod 0644 "$STREAM_CONF"
  grep -qxF "include $STREAM_CONF;" /etc/nginx/nginx.conf || printf '\ninclude %s;\n' "$STREAM_CONF" >> /etc/nginx/nginx.conf
  systemctl enable nginx >/dev/null 2>&1 || true
fi
if nginx -t 2>/tmp/lifolo-calls-nginx.log; then
  if [ "$MODE" = dedicated ]; then systemctl restart nginx; else systemctl reload nginx; fi
  st nginx ok
  rm -f "$CALLS_MAP.bak-calls" "$CALLS_SNIPPET.bak-calls" "$STREAM_CONF.bak-calls" /etc/nginx/sites-available/lifolo-call.bak-calls "$API_SNIPPET.bak-calls"
else
  cat /tmp/lifolo-calls-nginx.log >&2
  for f in "$CALLS_MAP" "$CALLS_SNIPPET" "$STREAM_CONF" /etc/nginx/sites-available/lifolo-call "$API_SNIPPET"; do
    if [ -f "$f.bak-calls" ]; then mv "$f.bak-calls" "$f"; elif [ "$f" = "$CALLS_MAP" ] || [ "$f" = "$CALLS_SNIPPET" ]; then rm -f "$f"; fi
  done
  nginx -t 2>/dev/null && systemctl reload nginx || true
  st nginx "failed (reverted)"
  warn "nginx-এর নতুন সেটিং ভুল — আগেরটা ফেরানো হলো (ওপরের লেখা দেখুন)"
fi

# ---------------------------------------------------------------- ৭) অ্যাপের জন্য (main)
if [ "$MODE" = main ]; then
  say "অ্যাপের জন্য: $BACKEND_ENV (Deploy backend এখান থেকে app.env-এ LIVEKIT_* নেয়)"
  ( umask 077
    { echo "# Lifolo — setup-call-server.sh: অ্যাপের LiveKit সেটিং (deploy-backend.sh পড়ে)"
      echo "LIVEKIT_URL=$PUBLIC_URL"
      echo "LIVEKIT_API_KEY=$API_KEY"
      echo "LIVEKIT_API_SECRET=$API_SECRET"
    } > "$BACKEND_ENV.new" )
  chown root:root "$BACKEND_ENV.new"; chmod 0600 "$BACKEND_ENV.new"; mv "$BACKEND_ENV.new" "$BACKEND_ENV"
  if grep -q '^LIVEKIT_URL=' /etc/lifolo/app.env 2>/dev/null; then st backend_env "in app.env"; else st backend_env "next Deploy backend"; fi
fi

# ---------------------------------------------------------------- ৮) পরীক্ষা
say "পরীক্ষা"
up=no
for _ in $(seq 1 20); do
  if [ "$(curl -fsS -m 3 "http://127.0.0.1:$SIGNAL_PORT/" 2>/dev/null)" = OK ]; then up=yes; break; fi
  sleep 1
done
st service "$(systemctl is-active livekit-server 2>/dev/null || true)"
st local_http "$up"
if [ "$up" != yes ]; then
  journalctl -u livekit-server -n 30 --no-pager >&2 || true
  warn "LiveKit চালু হয়নি (ওপরের লগ দেখুন)"
fi
# nginx দিয়ে LiveKit-এ পৌঁছায় কি না: টোকেন ছাড়া /rtc/validate-এ LiveKit-এর নিজের 401 লেখা আসার কথা
sig=failed
if [ "$MODE" = main ] || [ -n "$CERT_FILE" ]; then
  body="$(curl -sk -m 5 --resolve "$SIGNAL_HOST:443:127.0.0.1" "https://$SIGNAL_HOST/rtc/validate" 2>/dev/null || true)"
  [[ "$body" == *"no permissions to access the room"* ]] && sig=ok
else
  sig="no cert yet"
fi
st signal_nginx "$sig"
[ "$sig" != failed ] || warn "nginx দিয়ে /rtc LiveKit-এ পৌঁছাচ্ছে না"
st turn_udp "$(ss -Hlun "sport = :$TURN_UDP" 2>/dev/null | grep -q . && echo listening || echo closed)"
st rtc_tcp "$(ss -Hltn "sport = :$RTC_TCP" 2>/dev/null | grep -q . && echo listening || echo closed)"
if [ "$TURN_TLS" = on ]; then
  if echo | timeout 8 openssl s_client -connect 127.0.0.1:443 -servername "$TURN_DOMAIN" 2>/dev/null | grep -q 'BEGIN CERTIFICATE\|Server certificate'; then
    st turn_tls "on (443 SNI $TURN_DOMAIN)"
  else
    st turn_tls "failed"
    warn "TURN/TLS (443, $TURN_DOMAIN) হ্যান্ডশেক হলো না"
  fi
fi
if [ -n "$CERT_FILE" ]; then
  end="$(openssl x509 -in "$CERT_FILE" -noout -enddate 2>/dev/null | cut -d= -f2)"
  [ -z "$end" ] || st cert_days "$(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))"
fi
st node_ip_set yes
st reboot "$([ -f /var/run/reboot-required ] && echo yes || echo no)"
write_status
echo
cat "$STATUS"
if [ "${#WARNINGS[@]}" -gt 0 ]; then
  echo
  printf '!! %s\n' "${WARNINGS[@]}"
fi
[ "$up" = yes ] || exit 1
