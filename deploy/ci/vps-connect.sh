#!/usr/bin/env bash
# GitHub Actions থেকে Contabo VPS-এ (Ubuntu 24.04) root হিসেবে SSH সংযোগ প্রস্তুত করা। শেষে `ssh vps <কমান্ড>` কাজ করে।
#
# পরিবেশ (GitHub secrets থেকে):
#   VPS_HOST            VPS-এর IP (বা হোস্টনেম)
#   VPS_ROOT_PASSWORD   root-এর পাসওয়ার্ড (Contabo-তে VPS কেনার সময় দেওয়া)
#   BOOTSTRAP           yes হলে প্রথমবারের সংযোগ (পাসওয়ার্ড দিয়ে) আর SSH শক্ত করা — শুধু Setup VPS workflow
#
# কীভাবে কাজ করে (কোনো চাবি GitHub-এ রাখতে হয় না):
#   ১) root পাসওয়ার্ড থেকে derive-keys.js দুটো চাবি বানায় — প্রতিবার একই:
#      VPS-এর host key (এটা দিয়ে GitHub সার্ভারকে চেনে, নকল সার্ভার ধরা পড়ে) আর root-এর লগইন চাবি।
#   ২) প্রথমবার: ssh-keyscan দিয়ে সার্ভারের আসল host key নেওয়া হয় (ফিঙ্গারপ্রিন্ট লগে), পাসওয়ার্ডে ঢুকে
#      ওপরের চাবিগুলো বসানো হয়, তারপর SSH-এ পাসওয়ার্ড লগইন বন্ধ (Contabo-র VNC কনসোল জরুরি পথ হিসেবে থাকে)।
#   ৩) পরে প্রতিবার: শুধু ওই চাবি আর পিন করা host key দিয়ে ঢোকা।
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
log() { echo "==> $*"; }

HOST="${VPS_HOST:-}"
HOST="${HOST//[[:space:]]/}"
PW="${VPS_ROOT_PASSWORD:-}"
PW="${PW%$'\n'}"; PW="${PW%$'\r'}"
BOOTSTRAP="${BOOTSTRAP:-no}"
D="$HOME/.ssh"
GH_SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

[ -n "$HOST" ] || die "VPS_HOST সিক্রেট নেই। GitHub → Settings → Secrets and variables → Actions-এ VPS-এর IP দিন।"
[[ "$HOST" =~ ^[A-Za-z0-9.:-]+$ ]] || die "VPS_HOST-এ শুধু IP দিন (যেমন 203.0.113.10), অন্য কিছু নয়।"
[ -n "$PW" ] || die "VPS_ROOT_PASSWORD সিক্রেট নেই। Settings → Secrets and variables → Actions-এ Contabo-র root পাসওয়ার্ড দিন।"

install -d -m 0700 "$D"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIFOLO_MASTER="$PW" node "$SCRIPT_DIR/derive-keys.js" "$D/lifolo"

# পিন করা host key: শুধু আমাদের বানানো চাবি গ্রহণযোগ্য
printf '%s %s\n' "$HOST" "$(cut -d' ' -f1,2 "$D/lifolo/host_ed25519.pub")" > "$D/lifolo/known_hosts"
cat > "$D/config" <<EOF
Host vps
  HostName $HOST
  User root
  IdentityFile $D/lifolo/root_ed25519
  IdentitiesOnly yes
  UserKnownHostsFile $D/lifolo/known_hosts
  StrictHostKeyChecking yes
  HostKeyAlgorithms ssh-ed25519
  BatchMode yes
  ConnectTimeout 20
  ServerAliveInterval 30
  ServerAliveCountMax 10
  LogLevel ERROR
  # একটাই TCP সংযোগ সব ধাপে (দ্রুত, আর ফায়ারওয়াল/fail2ban-এর চোখে বারবার নতুন সংযোগ নয়)
  ControlMaster auto
  ControlPath $D/lifolo/cm-%C
  ControlPersist 60m
EOF
chmod 0600 "$D/config"

# নতুন সংযোগে যাচাই (আগের খোলা সংযোগ থাকলে বন্ধ করে) — সফল হলে সেটাই পরের সব ধাপের সংযোগ
try_vps() {
  ssh -O exit vps >/dev/null 2>&1 || true
  ssh vps true 2>/dev/null
}

host_fp() { ssh-keygen -lf "$D/lifolo/host_ed25519.pub" | awk '{print $2}'; }

if try_vps; then
  log "VPS-এ সংযোগ হয়েছে (root), host key পিন করা: $(host_fp)"
  exit 0
fi

if [ "$BOOTSTRAP" != yes ]; then
  die "VPS-এ ঢোকা যাচ্ছে না। আগে Actions → 'Setup VPS' চালান। (সেটা হয়ে থাকলে: VPS চালু আছে কি না, আর VPS_HOST / VPS_ROOT_PASSWORD বদলেছে কি না দেখুন।)"
fi

# ---------- প্রথম সংযোগ ----------
log "প্রথম সংযোগ: সার্ভারের host key নেওয়া (trust on first use)"
FIRST="$D/lifolo/known_hosts_first"
: > "$FIRST"
for _ in 1 2 3 4 5 6; do
  ssh-keyscan -T 15 -t ed25519 "$HOST" 2>/dev/null | grep -v '^#' > "$FIRST" || true
  [ -s "$FIRST" ] && break
  sleep 10
done
[ -s "$FIRST" ] || die "VPS-এর SSH (পোর্ট 22) সাড়া দিচ্ছে না। VPS চালু আছে কি না আর VPS_HOST-এ ঠিক IP আছে কি না দেখুন। নতুন কেনা হলে ৫ মিনিট পর আবার চালান।"
FIRST_FP="$(ssh-keygen -lf "$FIRST" | awk '{print $2}' | head -1)"
log "সার্ভারের আসল host key (প্রথম দেখা): $FIRST_FP"
echo "- প্রথম সংযোগে সার্ভারের host key: \`$FIRST_FP\`" >> "$GH_SUMMARY"

first_ssh() {
  SSHPASS="$PW" sshpass -e ssh -o UserKnownHostsFile="$FIRST" -o StrictHostKeyChecking=yes -o ConnectTimeout=20 \
    -o LogLevel=ERROR -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=1 "root@$HOST" "$@"
}

if ! command -v sshpass >/dev/null; then
  sudo apt-get install -y -q sshpass >/dev/null
fi

if ! first_ssh true; then
  die "root পাসওয়ার্ড দিয়ে ঢোকা গেল না। VPS_ROOT_PASSWORD-এ Contabo-তে দেওয়া পাসওয়ার্ডটাই আছে কি না দেখুন। (আগে একবার Setup সফল হয়ে থাকলে পাসওয়ার্ড লগইন বন্ধ — তখন পাসওয়ার্ড সিক্রেট বদলাবেন না।)"
fi

# সার্ভারে যা বসবে: পিন করা host key আর root-এর চাবি। পাসওয়ার্ড লগইন এখনো বন্ধ নয় — নতুন চাবি কাজ করার পর।
boot_script() {
  local phase="$1"
  cat <<EOF
set -eu
umask 077
EOF
  if [ "$phase" = 1 ]; then
    cat <<EOF
cat > /etc/ssh/lifolo_host_ed25519_key <<'LIFOLO_KEY'
$(cat "$D/lifolo/host_ed25519")
LIFOLO_KEY
cat > /etc/ssh/lifolo_host_ed25519_key.pub <<'LIFOLO_KEY'
$(cat "$D/lifolo/host_ed25519.pub")
LIFOLO_KEY
chmod 0600 /etc/ssh/lifolo_host_ed25519_key
chmod 0644 /etc/ssh/lifolo_host_ed25519_key.pub
install -d -m 0700 /root/.ssh
touch /root/.ssh/authorized_keys
chmod 0600 /root/.ssh/authorized_keys
grep -v 'lifolo-github-root\$' /root/.ssh/authorized_keys > /root/.ssh/authorized_keys.new || true
echo '$(cat "$D/lifolo/root_ed25519.pub")' >> /root/.ssh/authorized_keys.new
mv /root/.ssh/authorized_keys.new /root/.ssh/authorized_keys
EOF
  fi
  # sshd-তে প্রথম পাওয়া মানটাই চলে, আর sshd_config.d/*.conf মূল ফাইলের শুরুতে পড়া হয় — তাই 00- নাম
  cat <<'EOF'
cat > /etc/ssh/sshd_config.d/00-lifolo.conf.new <<'LIFOLO_CONF'
# Lifolo (GitHub Actions-এর Setup VPS) — হাতে বদলাবেন না; আবার Setup চালালে আবার লেখা হয়
HostKey /etc/ssh/lifolo_host_ed25519_key
LIFOLO_CONF
EOF
  if [ "$phase" = 2 ]; then
    cat <<'EOF'
cat >> /etc/ssh/sshd_config.d/00-lifolo.conf.new <<'LIFOLO_CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PermitEmptyPasswords no
LIFOLO_CONF
EOF
  fi
  cat <<'EOF'
chmod 0644 /etc/ssh/sshd_config.d/00-lifolo.conf.new
mv /etc/ssh/sshd_config.d/00-lifolo.conf.new /etc/ssh/sshd_config.d/00-lifolo.conf
install -d -m 0755 /run/sshd
if ! sshd -t; then
  rm -f /etc/ssh/sshd_config.d/00-lifolo.conf
  echo "sshd config invalid; reverted" >&2
  exit 1
fi
systemctl reload ssh 2>/dev/null || systemctl restart ssh
EOF
}

log "সার্ভারে GitHub-এর চাবি আর পিন করা host key বসানো"
boot_script 1 | first_ssh 'bash -s' >/dev/null \
  || die "VPS-এ root হিসেবে চাবি বসানো গেল না (ওপরের লেখা দেখুন)। কিছু বদলানো হয়নি।"
sleep 2
for _ in 1 2 3 4 5; do try_vps && break; sleep 3; done
try_vps || die "নতুন চাবি দিয়ে ঢোকা গেল না (পাসওয়ার্ড লগইন এখনো চালু আছে, কিছু বন্ধ হয়নি)। Setup VPS আবার চালান; বারবার হলে স্ক্রিনশট পাঠান।"

log "SSH শক্ত করা: পাসওয়ার্ড লগইন বন্ধ, root শুধু চাবি দিয়ে"
boot_script 2 | ssh vps 'bash -s' >/dev/null || die "SSH শক্ত করা যায়নি (ওপরের লেখা দেখুন)।"
sleep 2
for _ in 1 2 3 4 5; do try_vps && break; sleep 3; done
try_vps || die "SSH শক্ত করার পর ঢোকা যাচ্ছে না। Contabo প্যানেলের VNC কনসোল থেকে /etc/ssh/sshd_config.d/00-lifolo.conf মুছে দিন, তারপর আবার চালান।"

if SSHPASS="$PW" sshpass -e ssh -o UserKnownHostsFile="$D/lifolo/known_hosts" -o StrictHostKeyChecking=yes \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=1 -o ConnectTimeout=20 -o LogLevel=QUIET "root@$HOST" true 2>/dev/null; then
  die "পাসওয়ার্ড লগইন এখনো চালু আছে — বন্ধ করা যায়নি।"
fi
log "পাসওয়ার্ড দিয়ে SSH আর হয় না (ঠিক আছে)"
log "VPS-এ সংযোগ প্রস্তুত, পিন করা host key: $(host_fp)"
echo "- এখন থেকে পিন করা host key: \`$(host_fp)\`" >> "$GH_SUMMARY"
