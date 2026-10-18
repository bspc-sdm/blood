#!/usr/bin/env bash
# Lifolo — নতুন Ubuntu 24.04 VPS (Contabo) প্রস্তুত করা। root হিসেবে চালান; আবার চালালেও ক্ষতি নেই (idempotent)।
#
#   bash setup-vps.sh <domain> <email> [branch]
#   উদাহরণ:  bash setup-vps.sh api.lifolo.com.bd you@gmail.com
#
# যা করে: lifolo ব্যবহারকারী, Node 22, PostgreSQL, nginx, certbot, ufw (22/80/443), unattended-upgrades, fail2ban,
# ডাটাবেস + র‍্যান্ডম পাসওয়ার্ড (/etc/lifolo/env), কোড /opt/lifolo-এ, systemd সার্ভিস + দৈনিক ক্রন + দৈনিক ব্যাকআপ,
# nginx সাইট, Let's Encrypt সার্টিফিকেট, আর GitHub থেকে ডেপ্লয়ের SSH চাবি।
set -euo pipefail
cd /  # postgres ইত্যাদি ব্যবহারকারী /root পড়তে পারে না

DOMAIN="${1:-}"
EMAIL="${2:-}"
BRANCH="${3:-main}"
REPO_URL="${REPO_URL:-https://github.com/bspc-sdm/blood.git}"
APP_DIR=/opt/lifolo
ENV_DIR=/etc/lifolo
ENV_FILE=$ENV_DIR/env
BACKUP_DIR=/var/backups/lifolo
APP_USER=lifolo
DB_NAME=lifolo
DB_USER=lifolo
NODE_MAJOR=22

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31mXX %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান (sudo bash setup-vps.sh ...)"
[ -n "$DOMAIN" ] && [ -n "$EMAIL" ] || die "ব্যবহার: bash setup-vps.sh <domain> <email> [branch]"
# nginx/sed-এ বসানো হয়, তাই কড়া যাচাই
[[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || die "ডোমেইন ঠিক নেই (ছোট হাতের অক্ষরে, যেমন api.example.com): $DOMAIN"
[[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "ইমেইল ঠিক নেই: $EMAIL"
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || die "branch ঠিক নেই: $BRANCH"
. /etc/os-release
[ "${ID:-}" = ubuntu ] && [ "${VERSION_ID:-}" = "24.04" ] || warn "এটা Ubuntu 24.04-এর জন্য লেখা; এখানে ${PRETTY_NAME:-অজানা} — তবু চালানো হচ্ছে"

export DEBIAN_FRONTEND=noninteractive

say "প্যাকেজ (nginx, PostgreSQL, certbot, ufw, fail2ban ...)"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg git openssl ufw fail2ban unattended-upgrades \
  nginx certbot postgresql postgresql-contrib

say "Node.js $NODE_MAJOR (NodeSource, সই যাচাইসহ)"
if ! command -v node >/dev/null || ! node -v | grep -q "^v$NODE_MAJOR\."; then
  install -d -m 0755 /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_$NODE_MAJOR.x nodistro main" > /etc/apt/sources.list.d/nodesource.list
  apt-get update -q
  apt-get install -y -q nodejs
fi
node -v

say "ব্যবহারকারী $APP_USER"
if ! id "$APP_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$APP_USER"
fi
passwd -l "$APP_USER" >/dev/null  # পাসওয়ার্ডে লগইন নয়, শুধু SSH চাবি
# লগ পড়া (journalctl -u lifolo) sudo ছাড়াই
usermod -aG systemd-journal "$APP_USER"

say "ফায়ারওয়াল (ufw): শুধু 22, 80, 443"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
ufw status verbose | head -20

say "নিরাপত্তা আপডেট নিজে নিজে (unattended-upgrades)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable --now unattended-upgrades >/dev/null

say "fail2ban (SSH-এ বারবার ভুল পাসওয়ার্ড দিলে IP বন্ধ)"
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
systemctl enable fail2ban >/dev/null
systemctl restart fail2ban

say "কোড: $REPO_URL ($BRANCH) → $APP_DIR"
if [ -d "$APP_DIR/.git" ]; then
  sudo -u "$APP_USER" -H git -C "$APP_DIR" fetch --quiet origin
  sudo -u "$APP_USER" -H git -C "$APP_DIR" checkout --quiet "$BRANCH"
  sudo -u "$APP_USER" -H git -C "$APP_DIR" pull --quiet --ff-only origin "$BRANCH"
else
  install -d -o "$APP_USER" -g "$APP_USER" -m 0755 "$APP_DIR"
  sudo -u "$APP_USER" -H git clone --quiet --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi
[ -f "$APP_DIR/server/standalone/server.js" ] || die "$BRANCH শাখায় server/standalone/server.js নেই"
sudo -u "$APP_USER" -H bash -c "cd '$APP_DIR/server' && npm ci --omit=dev --no-audit --no-fund"

say "গোপন সেটিং: $ENV_FILE"
install -d -o root -g "$APP_USER" -m 0750 "$ENV_DIR"
if [ ! -f "$ENV_FILE" ]; then
  install -o root -g "$APP_USER" -m 0640 "$APP_DIR/deploy/env.example" "$ENV_FILE"
fi
chown root:"$APP_USER" "$ENV_FILE"
chmod 0640 "$ENV_FILE"
# KEY= খালি থাকলে মান বসানো, না থাকলে যোগ করা; আগে থেকে মান থাকলে বদলায় না
set_if_empty() {
  local key="$1" value="$2"
  if grep -qE "^${key}=.+" "$ENV_FILE"; then return 0; fi
  if grep -qE "^${key}=$" "$ENV_FILE"; then
    sed -i "s|^${key}=\$|${key}=${value}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}
set_if_empty CRON_SECRET "$(openssl rand -hex 32)"
set_if_empty WEB_SESSION_SECRET "$(openssl rand -hex 32)"

say "PostgreSQL: ডাটাবেস $DB_NAME, ব্যবহারকারী $DB_USER"
systemctl enable --now postgresql >/dev/null
# আগের পাসওয়ার্ড (# দেওয়া বা চালু DATABASE_URL লাইন থেকে) — না থাকলে নতুন
DB_PASS="$(sed -nE "s|^#?DATABASE_URL=postgresql://${DB_USER}:([A-Za-z0-9]+)@.*|\1|p" "$ENV_FILE" | head -1)"
if [ -z "$DB_PASS" ]; then
  DB_PASS="$(openssl rand -hex 24)"
  DB_URL="postgresql://${DB_USER}:${DB_PASS}@127.0.0.1:5432/${DB_NAME}"
  if grep -qE '^#?DATABASE_URL=$' "$ENV_FILE"; then
    sed -i -E "s|^#?DATABASE_URL=\$|#DATABASE_URL=${DB_URL}|" "$ENV_FILE"
  else
    printf '#DATABASE_URL=%s\n' "$DB_URL" >> "$ENV_FILE"
  fi
fi
# পাসওয়ার্ড কমান্ড লাইনে নয় (ps-এ দেখা যায়), stdin দিয়ে
sudo -u postgres psql -q -v ON_ERROR_STOP=1 -v pw="$DB_PASS" -v usr="$DB_USER" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'usr') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'usr') \gexec
ALTER ROLE :"usr" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw';
SQL
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
  sudo -u postgres createdb --owner="$DB_USER" "$DB_NAME"
fi
sudo -u postgres psql -q -v ON_ERROR_STOP=1 -c "REVOKE ALL ON DATABASE ${DB_NAME} FROM PUBLIC; GRANT CONNECT, TEMPORARY ON DATABASE ${DB_NAME} TO ${DB_USER};"
unset DB_PASS

say "ব্যাকআপ: প্রতিদিন pg_dump → $BACKUP_DIR (১৪ দিন)"
install -d -o postgres -g postgres -m 0700 "$BACKUP_DIR"
# root-এর ফাইল (lifolo ব্যবহারকারী বদলাতে পারে না — postgres হিসেবে চলে)
install -o root -g root -m 0755 "$APP_DIR/deploy/backup.sh" /usr/local/sbin/lifolo-backup

say "systemd: সার্ভার, দৈনিক ক্রন, দৈনিক ব্যাকআপ"
for u in lifolo.service lifolo-cron.service lifolo-cron.timer lifolo-backup.service lifolo-backup.timer; do
  install -o root -g root -m 0644 "$APP_DIR/deploy/systemd/$u" "/etc/systemd/system/$u"
done
systemctl daemon-reload
systemctl enable lifolo.service lifolo-cron.timer lifolo-backup.timer >/dev/null
systemctl restart lifolo.service
systemctl start lifolo-cron.timer lifolo-backup.timer

say "GitHub থেকে ডেপ্লয়: sudo-তে শুধু 'systemctl restart lifolo'"
cat > /etc/sudoers.d/lifolo-deploy <<EOF
# GitHub Actions (deploy-vps.yml) lifolo হিসেবে SSH করে শুধু এটুকু করতে পারে
$APP_USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart lifolo, /usr/bin/systemctl is-active lifolo
EOF
chmod 0440 /etc/sudoers.d/lifolo-deploy
visudo -cf /etc/sudoers.d/lifolo-deploy >/dev/null || { rm -f /etc/sudoers.d/lifolo-deploy; die "sudoers ফাইল ঠিক হয়নি"; }

DEPLOY_KEY=/root/lifolo-deploy-key
SSH_DIR=/home/$APP_USER/.ssh
install -d -o "$APP_USER" -g "$APP_USER" -m 0700 "$SSH_DIR"
touch "$SSH_DIR/authorized_keys"
if ! grep -q 'lifolo-github-deploy' "$SSH_DIR/authorized_keys"; then
  [ -f "$DEPLOY_KEY" ] || ssh-keygen -q -t ed25519 -N '' -C lifolo-github-deploy -f "$DEPLOY_KEY"
  # restrict: পোর্ট ফরওয়ার্ড, এজেন্ট, pty বন্ধ — শুধু কমান্ড চালানো
  printf 'restrict %s\n' "$(cat "$DEPLOY_KEY.pub")" >> "$SSH_DIR/authorized_keys"
fi
chown "$APP_USER:$APP_USER" "$SSH_DIR/authorized_keys"
chmod 0600 "$SSH_DIR/authorized_keys"
[ -f "$DEPLOY_KEY" ] && chmod 0600 "$DEPLOY_KEY"

say "nginx সাইট: $DOMAIN"
install -d -m 0755 /var/www/letsencrypt
install -o root -g root -m 0644 "$APP_DIR/deploy/nginx/lifolo-common.conf" /etc/nginx/snippets/lifolo-common.conf
cat > /etc/nginx/conf.d/lifolo-hardening.conf <<'EOF'
server_tokens off;
EOF
rm -f /etc/nginx/sites-enabled/default
CERT=/etc/letsencrypt/live/$DOMAIN/fullchain.pem
write_site() {
  sed "s|__DOMAIN__|$DOMAIN|g" "$APP_DIR/deploy/nginx/$1" > /etc/nginx/sites-available/lifolo
  ln -sf /etc/nginx/sites-available/lifolo /etc/nginx/sites-enabled/lifolo
  nginx -t
  systemctl enable nginx >/dev/null
  systemctl reload-or-restart nginx
}
if [ ! -f "$CERT" ]; then
  write_site lifolo-http.conf
  say "TLS সার্টিফিকেট (Let's Encrypt)"
  MY_IPS="$(hostname -I)"
  DNS_IPS="$(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u | tr '\n' ' ')"
  matched=no
  for ip in $DNS_IPS; do case " $MY_IPS " in *" $ip "*) matched=yes ;; esac; done
  [ "$matched" = yes ] || warn "$DOMAIN এখনো এই VPS-এর IP-তে যায় না (DNS: ${DNS_IPS:-কিছু না}; এই VPS: $MY_IPS)। A রেকর্ড ঠিক করে কিছুক্ষণ পর আবার চালান।"
  if certbot certonly --webroot -w /var/www/letsencrypt -d "$DOMAIN" -m "$EMAIL" \
      --agree-tos --no-eff-email --non-interactive --keep-until-expiring \
      --deploy-hook "systemctl reload nginx"; then
    :
  else
    warn "সার্টিফিকেট পাওয়া যায়নি — এখন শুধু http চলছে। DNS ঠিক হলে আবার চালান: bash $APP_DIR/deploy/setup-vps.sh $DOMAIN $EMAIL $BRANCH"
  fi
fi
if [ -f "$CERT" ]; then
  write_site lifolo-https.conf
fi
systemctl enable --now certbot.timer >/dev/null 2>&1 || true

say "পরীক্ষা"
ok=no
for _ in $(seq 1 20); do
  if curl -fsS -m 3 http://127.0.0.1:3000/healthz >/dev/null 2>&1; then ok=yes; break; fi
  sleep 1
done
if [ "$ok" = yes ]; then echo "সার্ভার চালু: http://127.0.0.1:3000/healthz"; else warn "সার্ভার সাড়া দিচ্ছে না — দেখুন: journalctl -u lifolo -n 50"; fi
if ! grep -qE '^FIREBASE_SERVICE_ACCOUNT=.+' "$ENV_FILE"; then
  warn "এখনো FIREBASE_SERVICE_ACCOUNT ইত্যাদি বসানো হয়নি: nano $ENV_FILE (তারপর systemctl restart lifolo)"
fi

PUBLIC_IP="$(hostname -I | awk '{print $1}')"
cat <<EOF

================ শেষ ================
ডোমেইন:         https://$DOMAIN
গোপন সেটিং:     $ENV_FILE   (nano দিয়ে খুলে Vercel-এর মানগুলো বসান, তারপর: systemctl restart lifolo)
ব্যাকআপ:        $BACKUP_DIR  (প্রতিদিন 03:30 ঢাকা সময়)

GitHub → Settings → Secrets and variables → Actions-এ যোগ করুন:
  VPS_HOST         $PUBLIC_IP
  VPS_USER         $APP_USER
  VPS_SSH_KEY      নিচের কমান্ডের পুরো লেখা (BEGIN থেকে END পর্যন্ত):  cat $DEPLOY_KEY
  VPS_KNOWN_HOSTS  $PUBLIC_IP $(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)
কপি করার পর চাবিটা এখান থেকে মুছে দিন:  rm $DEPLOY_KEY
EOF
