#!/usr/bin/env bash
# GitHub Actions-এর ডেপ্লয় (deploy-vps.yml)। setup-vps.sh এটা root-এর ফাইল হিসেবে /usr/local/sbin/lifolo-deploy-এ রাখে,
# আর ডেপ্লয়ের SSH চাবিতে command="..." দিয়ে বেঁধে দেয় — চাবি ফাঁস হলেও শুধু এটুকুই চালানো যায়
# (পাঠানো কমান্ড উপেক্ষা হয়)। lifolo ব্যবহারকারী হিসেবে চলে।
# কাজ: git pull → (lock বদলালে) npm ci --omit=dev → systemctl restart lifolo → /healthz দেখা।
set -euo pipefail
APP_DIR=/opt/lifolo
PORT="${PORT:-3000}"

# একসাথে দুটো ডেপ্লয় নয়
exec 9>"$HOME/.lifolo-deploy.lock"
flock -w 600 9

cd "$APP_DIR"
lock_before=$(git rev-parse HEAD:server/package-lock.json)
git pull --ff-only --quiet
echo "commit: $(git log -1 --format='%h %s')"
cd server
# npm ci node_modules মুছে আবার বসায় — চলন্ত সার্ভারের পরে লোড হওয়া মডিউল যাতে না হারায়, শুধু lock বদলালে
if [ "$lock_before" != "$(git rev-parse HEAD:server/package-lock.json)" ] || [ ! -d node_modules ]; then
  npm ci --omit=dev --no-audit --no-fund
else
  echo "package-lock.json unchanged, skipping npm ci"
fi
sudo -n /usr/bin/systemctl restart lifolo

for i in $(seq 1 30); do
  if curl -fsS -m 3 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
    echo "healthz ok"
    break
  fi
  if [ "$i" = 30 ]; then
    echo "server did not become healthy" >&2
    sudo -n /usr/bin/systemctl is-active lifolo || true
    sudo -n /usr/bin/journalctl -u lifolo -n 40 --no-pager || true
    exit 1
  fi
  sleep 1
done
# Firebase/ডাটাবেসসহ পুরো পরীক্ষা — ব্যর্থ হলে শুধু সতর্কবার্তা (সেটিং বাকি থাকতে পারে)
if curl -fsS -m 15 "http://127.0.0.1:$PORT/api/health"; then echo; else echo "WARNING: /api/health failed - check /etc/lifolo/env"; fi
