#!/usr/bin/env bash
# Lifolo — নতুন Ubuntu 24.04 VPS-এর ভিত্তি (stack-neutral base)। root হিসেবে চালান; আবার চালালেও ক্ষতি নেই (idempotent)।
#
# সাধারণত GitHub Actions-এর "Setup VPS (base)" workflow (.github/workflows/setup-vps-base.yml) এটা চালায়। হাতে:
#   sudo bash setup-base.sh [--domain 203-0-113-10.sslip.io] [--email you@example.com] [--ci]
#   --domain না দিলে এই VPS-এর প্রথম IPv4 থেকে <IP-ড্যাশ-দিয়ে>.sslip.io
#   --ci     GitHub Actions-এর জন্য: গোপন মানগুলো ::add-mask:: দিয়ে লগে লুকানো
#
# যা বসায়/করে:
#   apt update/upgrade, unattended-upgrades, fail2ban, ufw (শুধু 22/80/443), সময় UTC (hostname যেমন আছে),
#   2 GB swap, lifolo ব্যবহারকারী, JDK 21 (Eclipse Temurin; না হলে Ubuntu-র openjdk-21),
#   PostgreSQL 16 (শুধু localhost; ডাটাবেস+ব্যবহারকারী lifolo), Redis (শুধু localhost, পাসওয়ার্ডসহ),
#   গোপন মান /etc/lifolo/env (0600 root:lifolo), nginx + "setting up" পাতা + /healthz, Let's Encrypt (certbot),
#   lifolo-api.service (java -jar /opt/lifolo/api/app.jar; jar না থাকলে বন্ধ), nginx-এ /api → 127.0.0.1:8080
#   (jar থাকলে চালু, না হলে মন্তব্য করা), প্রতিদিন pg_dump → /var/backups/lifolo (১৪ দিন)।
# কোনো গোপন মান ছাপা হয় না।
set -euo pipefail

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*" >&2; WARNINGS+=("$*"); }
die() { printf '\033[1;31mXX %s\033[0m\n' "$*" >&2; exit 1; }
WARNINGS=()

APP_USER=lifolo
DB_NAME=lifolo
DB_USER=lifolo
PG_MAJOR=16
ENV_DIR=/etc/lifolo
ENV_FILE=/etc/lifolo/env
API_DIR=/opt/lifolo/api
API_JAR=/opt/lifolo/api/app.jar
API_PORT=8080
WEB_ROOT=/var/www/lifolo
BACKUP_DIR=/var/backups/lifolo
SWAP_FILE=/swapfile
CI_MODE=no

ci_mask() { if [ "$CI_MODE" = yes ] && [ -n "${1:-}" ]; then printf '::add-mask::%s\n' "$1"; fi; }

# env ফাইল থেকে একটা মান পড়া (KEY=value, উদ্ধৃতি ছাড়া)
env_get() { sed -nE "s/^$1=(.*)$/\1/p" "$ENV_FILE" | tail -1; }
# env ফাইলে KEY=value বসানো (থাকলে বদলানো, না থাকলে যোগ) — মান শুধু [A-Za-z0-9._:/@-]
env_set() {
  local key="$1" value="$2"
  [[ "$value" =~ ^[A-Za-z0-9._:/@-]*$ ]] || die "env_set: অপ্রত্যাশিত অক্ষর ($key)"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i -E "s|^${key}=.*$|${key}=${value}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}
# গোপন মান: আগে থাকলে সেটাই, না হলে নতুন র‍্যান্ডম (hex)
secret_get_or_make() {
  local key="$1" v
  v="$(env_get "$key")"
  if ! [[ "$v" =~ ^[0-9a-f]{32,}$ ]]; then
    v="$(openssl rand -hex 24)"
    env_set "$key" "$v"
  fi
  printf '%s' "$v"
}

main() {
  local DOMAIN="" EMAIL=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --domain) DOMAIN="${2:-}"; shift 2 ;;
      --email) EMAIL="${2:-}"; shift 2 ;;
      --ci) CI_MODE=yes; shift ;;
      *) die "অচেনা আর্গুমেন্ট: $1 (ব্যবহার: setup-base.sh [--domain D] [--email E] [--ci])" ;;
    esac
  done

  cd /
  [ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান (sudo bash setup-base.sh)"
  # shellcheck disable=SC1091
  . /etc/os-release
  { [ "${ID:-}" = ubuntu ] && [ "${VERSION_ID:-}" = "24.04" ]; } || warn "এটা Ubuntu 24.04-এর জন্য লেখা; এখানে ${PRETTY_NAME:-অজানা} — তবু চালানো হচ্ছে"
  local CODENAME="${VERSION_CODENAME:-noble}"

  if [ -z "$DOMAIN" ]; then
    local ip
    ip="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)"
    [ -n "$ip" ] || die "IPv4 পাওয়া গেল না; --domain দিন"
    DOMAIN="${ip//./-}.sslip.io"
  fi
  DOMAIN="$(printf '%s' "$DOMAIN" | tr '[:upper:]' '[:lower:]')"
  # nginx/sed-এ বসানো হয়, তাই কড়া যাচাই
  [[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || die "ডোমেইন ঠিক নেই: $DOMAIN"
  if [ -n "$EMAIL" ]; then
    [[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "ইমেইল ঠিক নেই: $EMAIL"
  fi
  echo "ঠিকানা: $DOMAIN"

  export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1
  # নতুন VPS-এ প্রথম কয়েক মিনিট unattended-upgrades apt আটকে রাখে — ১০ মিনিট পর্যন্ত অপেক্ষা
  local APT=(apt-get -y -q -o DPkg::Lock::Timeout=600 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

  # ---------------------------------------------------------------- প্যাকেজ
  say "apt update + upgrade"
  "${APT[@]}" update
  "${APT[@]}" upgrade

  say "প্যাকেজ: ufw, fail2ban, unattended-upgrades, nginx, certbot, PostgreSQL $PG_MAJOR, Redis"
  # বসানোর সময় সার্ভিস নিজে চালু নয় (policy-rc.d) — nginx-এর default সাইট IPv6 ছাড়া মেশিনে চালু হতে না পেরে
  # dpkg ভেঙে দেয়; নিচে নিজেদের সেটিং বসিয়ে আমরাই চালু করি
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 0755 /usr/sbin/policy-rc.d
  trap 'rm -f /usr/sbin/policy-rc.d' EXIT
  local PKGS=(ca-certificates curl gnupg openssl tzdata iproute2 sudo ufw fail2ban unattended-upgrades
    nginx certbot "postgresql-$PG_MAJOR" "postgresql-client-$PG_MAJOR" redis-server)
  "${APT[@]}" install "${PKGS[@]}" || die "প্যাকেজ বসানো যায়নি (ওপরের লেখা দেখুন)"

  say "Java 21 (Eclipse Temurin, Adoptium-এর apt রিপো, সই যাচাইসহ)"
  local JAVA_SRC=temurin
  if ! dpkg -s temurin-21-jdk >/dev/null 2>&1; then
    install -d -m 0755 /etc/apt/keyrings
    if curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg \
      && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $CODENAME main" > /etc/apt/sources.list.d/adoptium.list \
      && "${APT[@]}" update && "${APT[@]}" install temurin-21-jdk; then
      :
    else
      warn "Temurin বসানো গেল না — Ubuntu-র openjdk-21 বসানো হচ্ছে"
      rm -f /etc/apt/sources.list.d/adoptium.list
      "${APT[@]}" update
      "${APT[@]}" install openjdk-21-jdk-headless || die "Java 21 বসানো যায়নি"
      JAVA_SRC=openjdk
    fi
  fi
  rm -f /usr/sbin/policy-rc.d
  trap - EXIT
  command -v java >/dev/null || die "java পাওয়া যাচ্ছে না"
  java -version 2>&1 | head -1

  # ---------------------------------------------------------------- সিস্টেম
  say "সময় UTC (hostname যেমন আছে: $(hostname))"
  if ! timedatectl set-timezone UTC 2>/dev/null; then
    ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime
    echo Etc/UTC > /etc/timezone
  fi

  say "swap: 2 GB ($SWAP_FILE)"
  if swapon --show=NAME --noheadings 2>/dev/null | grep -qx "$SWAP_FILE"; then
    echo "আগে থেকেই চালু"
  elif [ -n "$(swapon --show --noheadings 2>/dev/null)" ]; then
    echo "অন্য swap আগে থেকেই চালু — নতুন বানানো হলো না"
  else
    if [ ! -f "$SWAP_FILE" ]; then
      fallocate -l 2G "$SWAP_FILE" 2>/dev/null || dd if=/dev/zero of="$SWAP_FILE" bs=1M count=2048 status=none
      chmod 0600 "$SWAP_FILE"
      mkswap "$SWAP_FILE" >/dev/null
    fi
    chmod 0600 "$SWAP_FILE"
    swapon "$SWAP_FILE" || warn "swap চালু করা গেল না (এই মেশিনে swap সমর্থিত নয়?)"
  fi
  if [ -f "$SWAP_FILE" ] && ! grep -qE "^${SWAP_FILE}[[:space:]]" /etc/fstab; then
    echo "$SWAP_FILE none swap sw 0 0" >> /etc/fstab
  fi
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-lifolo.conf
  sysctl -q -p /etc/sysctl.d/99-lifolo.conf 2>/dev/null || true

  say "ব্যবহারকারী $APP_USER (root নয়, পাসওয়ার্ডে লগইন নয়)"
  if ! id "$APP_USER" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "$APP_USER"
  fi
  passwd -l "$APP_USER" >/dev/null

  say "ফায়ারওয়াল (ufw): শুধু 22, 80, 443"
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  # "limit" নয়: GitHub Actions অল্প সময়ে কয়েকবার ঢোকে। পাসওয়ার্ড লগইন বন্ধ আর fail2ban আছে।
  ufw delete limit 22/tcp >/dev/null 2>&1 || true
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  # SSH অন্য পোর্টে চললে সেটাও খোলা — নিজেকে আটকে না ফেলতে
  local p
  for p in $(sshd -T 2>/dev/null | awk '$1 == "port" {print $2}' | sort -u); do
    if [ "$p" != 22 ]; then
      warn "SSH পোর্ট $p-এ চলছে — সেটাও খোলা রাখা হলো"
      ufw allow "$p/tcp" >/dev/null
    fi
  done
  ufw --force enable >/dev/null

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
  systemctl enable fail2ban >/dev/null
  systemctl restart fail2ban

  # ---------------------------------------------------------------- গোপন মান
  say "গোপন সেটিং: $ENV_FILE (0600 root:$APP_USER)"
  install -d -o root -g "$APP_USER" -m 0750 "$ENV_DIR"
  if [ ! -f "$ENV_FILE" ]; then
    ( umask 077; printf '# Lifolo — গোপন সেটিং (setup-base.sh বানিয়েছে)। systemd (lifolo-api.service) পড়ে।\n' > "$ENV_FILE" )
  fi
  chown root:"$APP_USER" "$ENV_FILE"
  chmod 0600 "$ENV_FILE"
  local DB_PASS REDIS_PASS
  DB_PASS="$(secret_get_or_make LIFOLO_DB_PASSWORD)"
  REDIS_PASS="$(secret_get_or_make LIFOLO_REDIS_PASSWORD)"
  ci_mask "$DB_PASS"
  ci_mask "$REDIS_PASS"
  # Spring Boot নিজেই এই নামগুলো চেনে (application.yml-এ কিছু লাগে না)
  env_set SERVER_ADDRESS 127.0.0.1
  env_set SERVER_PORT "$API_PORT"
  env_set SPRING_DATASOURCE_URL "jdbc:postgresql://127.0.0.1:5432/$DB_NAME"
  env_set SPRING_DATASOURCE_USERNAME "$DB_USER"
  env_set SPRING_DATASOURCE_PASSWORD "$DB_PASS"
  env_set SPRING_DATA_REDIS_HOST 127.0.0.1
  env_set SPRING_DATA_REDIS_PORT 6379
  env_set SPRING_DATA_REDIS_PASSWORD "$REDIS_PASS"
  chmod 0600 "$ENV_FILE"

  # ---------------------------------------------------------------- PostgreSQL
  say "PostgreSQL $PG_MAJOR: শুধু localhost, ডাটাবেস $DB_NAME, ব্যবহারকারী $DB_USER"
  local PG_CONF_DIR="/etc/postgresql/$PG_MAJOR/main"
  [ -d "$PG_CONF_DIR" ] || pg_createcluster "$PG_MAJOR" main
  install -d -o postgres -g postgres -m 0755 "$PG_CONF_DIR/conf.d"
  cat > "$PG_CONF_DIR/conf.d/lifolo.conf" <<'EOF'
# Lifolo (setup-base.sh) — বাইরে থেকে নয়, শুধু এই মেশিন
listen_addresses = 'localhost'
password_encryption = 'scram-sha-256'
EOF
  chown postgres:postgres "$PG_CONF_DIR/conf.d/lifolo.conf"
  systemctl enable postgresql >/dev/null
  systemctl restart postgresql
  local _
  for _ in $(seq 1 30); do pg_isready -q -h /var/run/postgresql && break; sleep 1; done
  pg_isready -q -h /var/run/postgresql || die "PostgreSQL চালু হয়নি (journalctl -u postgresql@$PG_MAJOR-main দেখুন)"
  # পাসওয়ার্ড (শুধু hex) কমান্ড লাইনে নয় (ps-এ দেখা যায়) — stdin দিয়ে; env ফাইলই আসল মান
  sudo -u postgres psql -q -v ON_ERROR_STOP=1 >/dev/null <<SQL
SELECT 'CREATE ROLE ${DB_USER} LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${DB_USER}') \gexec
ALTER ROLE ${DB_USER} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${DB_PASS}';
SELECT 'CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}') \gexec
REVOKE ALL ON DATABASE ${DB_NAME} FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE ${DB_NAME} TO ${DB_USER};
SQL
  # lifolo পাসওয়ার্ড দিয়ে TCP-তে ঢুকতে পারে কি না (পাসওয়ার্ড env দিয়ে, কমান্ড লাইনে নয়)
  PGPASSWORD="$DB_PASS" psql -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -tAc 'SELECT 1' >/dev/null \
    || die "lifolo ব্যবহারকারী PostgreSQL-এ ঢুকতে পারছে না"

  # ---------------------------------------------------------------- Redis
  say "Redis: শুধু localhost, পাসওয়ার্ডসহ"
  ( umask 027; cat > /etc/redis/lifolo.conf.new <<EOF
# Lifolo (setup-base.sh) — হাতে বদলাবেন না; আবার চালালে আবার লেখা হয়
bind 127.0.0.1 -::1
protected-mode yes
port 6379
requirepass ${REDIS_PASS}
EOF
  )
  chown root:redis /etc/redis/lifolo.conf.new
  chmod 0640 /etc/redis/lifolo.conf.new
  mv /etc/redis/lifolo.conf.new /etc/redis/lifolo.conf
  # শেষে include — পরের মানই চলে, তাই আমাদের সেটিং জেতে
  grep -qxF 'include /etc/redis/lifolo.conf' /etc/redis/redis.conf || printf '\ninclude /etc/redis/lifolo.conf\n' >> /etc/redis/redis.conf
  systemctl enable redis-server >/dev/null
  systemctl restart redis-server
  local pong=""
  for _ in $(seq 1 20); do
    pong="$(REDISCLI_AUTH="$REDIS_PASS" redis-cli -h 127.0.0.1 ping 2>/dev/null || true)"
    [ "$pong" = PONG ] && break
    sleep 1
  done
  [ "$pong" = PONG ] || die "Redis পাসওয়ার্ড দিয়ে সাড়া দিচ্ছে না"
  if [ "$(redis-cli -h 127.0.0.1 ping 2>&1 || true)" = PONG ]; then die "Redis পাসওয়ার্ড ছাড়াই খোলা!"; fi
  unset DB_PASS REDIS_PASS

  # ---------------------------------------------------------------- API (Spring Boot) — jar এলে চালু
  say "systemd: lifolo-api.service (java -jar $API_JAR)"
  install -d -o root -g "$APP_USER" -m 0755 /opt/lifolo
  install -d -o root -g "$APP_USER" -m 0750 "$API_DIR"
  cat > /etc/systemd/system/lifolo-api.service <<EOF
# Lifolo API (Kotlin + Spring Boot) — setup-base.sh বানিয়েছে; আবার চালালে আবার লেখা হয়।
# jar রাখুন $API_JAR-এ (root:$APP_USER, 0640), তারপর: systemctl enable --now lifolo-api
[Unit]
Description=Lifolo API (Spring Boot)
After=network-online.target postgresql.service redis-server.service
Wants=network-online.target
ConditionPathExists=$API_JAR

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$API_DIR
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java -Xmx1g -XX:+ExitOnOutOfMemoryError -jar $API_JAR
Restart=always
RestartSec=5
SuccessExitStatus=143
TimeoutStopSec=30
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectHome=yes
ProtectKernelTunables=yes
ProtectControlGroups=yes

[Install]
WantedBy=multi-user.target
EOF
  chmod 0644 /etc/systemd/system/lifolo-api.service
  systemctl daemon-reload
  local API_STATE
  if [ -f "$API_JAR" ]; then
    systemctl enable lifolo-api.service >/dev/null
    systemctl restart lifolo-api.service
    API_STATE="jar আছে — চালু"
  else
    systemctl disable --now lifolo-api.service >/dev/null 2>&1 || true
    API_STATE="jar নেই — বন্ধ (ঠিক আছে)"
  fi
  echo "$API_STATE"

  # ---------------------------------------------------------------- ব্যাকআপ
  say "ব্যাকআপ: প্রতিদিন pg_dump → $BACKUP_DIR (১৪ দিন রাখা)"
  install -d -o postgres -g postgres -m 0750 "$BACKUP_DIR"
  cat > /usr/local/sbin/lifolo-backup <<'EOF'
#!/usr/bin/env bash
# Lifolo — PostgreSQL "lifolo" ডাটাবেসের ব্যাকআপ (pg_dump custom format), ১৪ দিনের পুরোনো মোছা।
# setup-base.sh বসায়; postgres ব্যবহারকারী হিসেবে চলে (lifolo-backup.timer)। হাতে: systemctl start lifolo-backup
# ফেরত আনা: pg_restore --clean --if-exists -d lifolo /var/backups/lifolo/lifolo-<সময়>.dump (postgres হিসেবে)
set -euo pipefail
DIR=${BACKUP_DIR:-/var/backups/lifolo}
DB=${BACKUP_DB:-lifolo}
KEEP_DAYS=${KEEP_DAYS:-14}
umask 077
ts=$(date -u +%Y%m%d-%H%M%S)
tmp="$DIR/.lifolo-$ts.dump.partial"
trap 'rm -f "$tmp"' EXIT
pg_dump --format=custom --compress=6 --dbname="$DB" --file="$tmp"
mv "$tmp" "$DIR/lifolo-$ts.dump"
trap - EXIT
find "$DIR" -maxdepth 1 -type f -name 'lifolo-*.dump' -mtime +"$((KEEP_DAYS - 1))" -delete
find "$DIR" -maxdepth 1 -type f -name '.lifolo-*.partial' -mmin +120 -delete
echo "backup ok: $DIR/lifolo-$ts.dump ($(du -h "$DIR/lifolo-$ts.dump" | cut -f1))"
EOF
  chmod 0755 /usr/local/sbin/lifolo-backup
  cat > /etc/systemd/system/lifolo-backup.service <<EOF
[Unit]
Description=Lifolo PostgreSQL backup (pg_dump, keep 14 days)
After=postgresql.service
Requires=postgresql.service

[Service]
Type=oneshot
User=postgres
Group=postgres
Environment=BACKUP_DIR=$BACKUP_DIR BACKUP_DB=$DB_NAME KEEP_DAYS=14
ExecStart=/usr/local/sbin/lifolo-backup
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ReadWritePaths=$BACKUP_DIR
EOF
  cat > /etc/systemd/system/lifolo-backup.timer <<'EOF'
[Unit]
Description=Daily Lifolo PostgreSQL backup at 21:30 UTC (03:30 Dhaka)

[Timer]
OnCalendar=*-*-* 21:30:00 UTC
Persistent=true
RandomizedDelaySec=5min

[Install]
WantedBy=timers.target
EOF
  chmod 0644 /etc/systemd/system/lifolo-backup.service /etc/systemd/system/lifolo-backup.timer
  systemctl daemon-reload
  systemctl enable --now lifolo-backup.timer >/dev/null
  local BACKUP_STATE=ok
  systemctl start lifolo-backup.service || { BACKUP_STATE=failed; warn "পরীক্ষামূলক ব্যাকআপ ব্যর্থ (journalctl -u lifolo-backup দেখুন)"; }

  # ---------------------------------------------------------------- nginx + TLS
  say "nginx: $DOMAIN (\"Lifolo — setting up\" পাতা, /healthz)"
  install -d -m 0755 "$WEB_ROOT" /var/www/letsencrypt
  if [ ! -f "$WEB_ROOT/index.html" ]; then
    cat > "$WEB_ROOT/index.html" <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Lifolo — setting up</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: system-ui, sans-serif; background: #fafafa; color: #222; }
  @media (prefers-color-scheme: dark) { body { background: #111; color: #eee; } }
  main { text-align: center; padding: 16px; }
  h1 { font-size: 1.6rem; margin: 0 0 .5rem; }
  p { margin: 0; opacity: .7; }
</style>
</head>
<body><main><h1>Lifolo — setting up</h1><p>The new server is being prepared.</p></main></body>
</html>
EOF
    chmod 0644 "$WEB_ROOT/index.html"
  fi
  echo 'server_tokens off;' > /etc/nginx/conf.d/lifolo-hardening.conf
  write_api_snippet
  write_common_snippet
  rm -f /etc/nginx/sites-enabled/default

  local CERT="/etc/letsencrypt/live/$DOMAIN/fullchain.pem" CERT_STATE=ok
  if [ ! -f "$CERT" ]; then
    write_site "$DOMAIN" http
    say "TLS সার্টিফিকেট (Let's Encrypt, certbot)"
    local MY_IPS DNS_IPS matched=no ip
    MY_IPS="$(hostname -I 2>/dev/null || true)"
    DNS_IPS="$(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
    for ip in $DNS_IPS; do case " $MY_IPS " in *" $ip "*) matched=yes ;; esac; done
    [ "$matched" = yes ] || warn "$DOMAIN এই VPS-এর IP-তে যায় না (DNS: ${DNS_IPS:-কিছু না}; এই VPS: $MY_IPS)"
    local email_args=(--register-unsafely-without-email)
    [ -z "$EMAIL" ] || email_args=(-m "$EMAIL" --no-eff-email)
    if ! certbot certonly --webroot -w /var/www/letsencrypt -d "$DOMAIN" "${email_args[@]}" \
        --agree-tos --non-interactive --keep-until-expiring --deploy-hook "systemctl reload nginx"; then
      CERT_STATE=failed
      warn "সার্টিফিকেট পাওয়া যায়নি — সাইট আপাতত শুধু http-তে। পোর্ট 80 বাইরে থেকে খোলা কি না দেখে পরে আবার চালান।"
    fi
  fi
  if [ -f "$CERT" ]; then
    write_site "$DOMAIN" https
  else
    CERT_STATE=failed
  fi
  systemctl enable --now certbot.timer >/dev/null 2>&1 || true

  # ---------------------------------------------------------------- পরীক্ষা ও সারাংশ
  say "পরীক্ষা"
  local HTTP_HEALTH=failed HTTPS_HEALTH=skipped
  curl -fsS -m 5 --resolve "$DOMAIN:80:127.0.0.1" "http://$DOMAIN/healthz" | grep -q '"ok":true' && HTTP_HEALTH=ok
  if [ "$CERT_STATE" = ok ]; then
    HTTPS_HEALTH=failed
    curl -fsS -m 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/healthz" | grep -q '"ok":true' && HTTPS_HEALTH=ok
  fi
  [ "$HTTP_HEALTH" = ok ] || warn "http://$DOMAIN/healthz এই মেশিনেই সাড়া দিচ্ছে না"
  [ "$HTTPS_HEALTH" != failed ] || warn "https://$DOMAIN/healthz এই মেশিনেই সাড়া দিচ্ছে না"

  local JAVA_V PG_V REDIS_V NGINX_V CERTBOT_V
  JAVA_V="$(java -version 2>&1 | head -1) [$JAVA_SRC]"
  PG_V="$(sudo -u postgres psql -tAc 'SHOW server_version' 2>/dev/null | awk '{print $1}')"
  REDIS_V="$(redis-server --version | sed -nE 's/.*v=([0-9.]+).*/\1/p')"
  NGINX_V="$(nginx -v 2>&1 | sed -nE 's|.*nginx/([0-9.]+).*|\1|p')"
  CERTBOT_V="$(certbot --version 2>&1 | awk '{print $2}')"

  cat <<EOF

================ সারাংশ (Lifolo base) ================
OS:          ${PRETTY_NAME:-?} ($(uname -r))
Hostname:    $(hostname)  (বদলানো হয়নি)
Timezone:    $(readlink /etc/localtime | sed 's|.*/zoneinfo/||')
Swap:        $(swapon --show=NAME,SIZE --noheadings 2>/dev/null | tr '\n' ' ' || true)
Java:        $JAVA_V
PostgreSQL:  $PG_V (শুধু localhost)
Redis:       $REDIS_V (শুধু localhost, পাসওয়ার্ডসহ)
nginx:       $NGINX_V
certbot:     $CERTBOT_V
TLS cert:    $CERT_STATE ($DOMAIN)
/healthz:    http=$HTTP_HEALTH https=$HTTPS_HEALTH
API:         $API_STATE
Backup:      $BACKUP_STATE → $BACKUP_DIR (প্রতিদিন 21:30 UTC, ১৪ দিন)
Secrets:     $ENV_FILE ($(stat -c '%a %U:%G' "$ENV_FILE"))
Reboot:      $([ -f /var/run/reboot-required ] && echo "দরকার (কার্নেল আপডেট) — সুবিধামতো রিবুট করুন" || echo "দরকার নেই")

শোনা পোর্ট (TCP):
$(ss -ltnH 2>/dev/null | awk '{print "  " $4}' | sort -u)

ufw:
$(ufw status verbose 2>&1 | sed 's/^/  /')
EOF
  if [ "${#WARNINGS[@]}" -gt 0 ]; then
    echo
    echo "সতর্কতা:"
    local w
    for w in "${WARNINGS[@]}"; do echo "  - $w"; done
  fi
  # workflow এগুলো পড়ে (গোপন কিছু নয়): /var/lib/lifolo/setup-status
  install -d -m 0755 /var/lib/lifolo
  {
    echo "cert=$CERT_STATE"
    echo "http_health=$HTTP_HEALTH"
    echo "https_health=$HTTPS_HEALTH"
    echo "backup=$BACKUP_STATE"
    echo "java=$(printf '%s' "$JAVA_V" | tr -d '[:cntrl:]"`$')"
    echo "postgres=$PG_V"
    echo "redis=$REDIS_V"
    echo "nginx=$NGINX_V"
    echo "certbot=$CERTBOT_V"
    echo "reboot=$([ -f /var/run/reboot-required ] && echo yes || echo no)"
  } > /var/lib/lifolo/setup-status
  chmod 0644 /var/lib/lifolo/setup-status
}

# /api → Spring Boot: jar থাকলে চালু, না হলে মন্তব্য করা (আবার চালালে jar দেখে চালু হয়)। SSE-র সেটিং পরে।
write_api_snippet() {
  local c=""
  [ -f "$API_JAR" ] || c="# "
  cat > /etc/nginx/snippets/lifolo-api.conf <<EOF
# Lifolo — /api → Spring Boot (127.0.0.1:$API_PORT)। setup-base.sh লেখে: $API_JAR থাকলে চালু, না হলে মন্তব্য।
${c}location /api/ {
${c}    proxy_pass http://127.0.0.1:$API_PORT;
${c}    proxy_http_version 1.1;
${c}    proxy_set_header Connection "";
${c}    proxy_set_header Host \$host;
${c}    proxy_set_header X-Real-IP \$remote_addr;
${c}    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
${c}    proxy_set_header X-Forwarded-Proto \$scheme;
${c}    proxy_connect_timeout 5s;
${c}    proxy_read_timeout 60s;
${c}    client_max_body_size 10m;
${c}}
EOF
  chmod 0644 /etc/nginx/snippets/lifolo-api.conf
}

write_common_snippet() {
  cat > /etc/nginx/snippets/lifolo-common.conf <<EOF
# Lifolo — সাইটের সাধারণ অংশ (setup-base.sh লেখে)
root $WEB_ROOT;
index index.html;
charset utf-8;

add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header X-Frame-Options "DENY" always;

location = /healthz {
    access_log off;
    default_type application/json;
    return 200 '{"ok":true,"service":"lifolo","stage":"base"}\n';
}

location / {
    try_files \$uri \$uri/ /index.html;
}
EOF
  chmod 0644 /etc/nginx/snippets/lifolo-common.conf
}

# http: সার্টিফিকেটের আগে পাতা + /healthz + Let's Encrypt-এর যাচাই। https: 80 → 443 (যাচাই আর /healthz বাদে)।
write_site() {
  local domain="$1" mode="$2" f=/etc/nginx/sites-available/lifolo
  {
    cat <<EOF
# Lifolo — setup-base.sh লেখে ($mode)। হাতে বদলালে আবার চালানোর সময় মুছে যাবে।
# অচেনা Host (IP দিয়ে স্ক্যান ইত্যাদি) — উত্তর ছাড়াই বন্ধ
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
    location / { return 444; }
}
EOF
    if [ "$mode" = http ]; then
      cat <<EOF

server {
    listen 80;
    listen [::]:80;
    server_name $domain;
    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
    include /etc/nginx/snippets/lifolo-common.conf;
}
EOF
    else
      cat <<EOF
server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;
    ssl_reject_handshake on;
}

server {
    listen 80;
    listen [::]:80;
    server_name $domain;
    location /.well-known/acme-challenge/ { root /var/www/letsencrypt; }
    location = /healthz {
        access_log off;
        default_type application/json;
        return 200 '{"ok":true,"service":"lifolo","stage":"base"}\n';
    }
    location / { return 301 https://$domain\$request_uri; }
}

server {
    # Ubuntu 24.04-এর nginx 1.24-এ আলাদা "http2 on;" নেই — listen-এর ভেতরে
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $domain;

    ssl_certificate /etc/letsencrypt/live/$domain/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$domain/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:lifolo_ssl:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;
    add_header Strict-Transport-Security "max-age=31536000" always;

    # /api শুধু HTTPS-এ (লগইন টোকেন খোলা http-তে নয়)
    include /etc/nginx/snippets/lifolo-api.conf;
    include /etc/nginx/snippets/lifolo-common.conf;
}
EOF
    fi
  } > "$f.new"
  # IPv6 বন্ধ করা কার্নেল: [::] শুনতে গেলে nginx চালুই হয় না
  [ -e /proc/net/if_inet6 ] || sed -i '/listen \[::\]/d' "$f.new"
  mv "$f.new" "$f"
  chmod 0644 "$f"
  ln -sf "$f" /etc/nginx/sites-enabled/lifolo
  nginx -t
  systemctl enable nginx >/dev/null
  systemctl reload-or-restart nginx
}

# পুরো স্ক্রিপ্ট আগে পড়া হয়, তারপর চলে — চলার মাঝে ফাইল বদলালেও সমস্যা নেই
main "$@"
exit 0
