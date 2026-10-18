#!/usr/bin/env bash
# PostgreSQL ব্যাকআপ ফেরত আনা। setup-vps.sh এটা /usr/local/sbin/lifolo-restore-এ রাখে; root হিসেবে চলে।
# GitHub-এর "Restore backup" workflow (.github/workflows/restore-backup.yml) এটা চালায়।
#
#   lifolo-restore cloud latest                        Cloud Storage-এর সবচেয়ে নতুনটা
#   lifolo-restore cloud lifolo-20261020-213000.dump   Cloud Storage-এর নির্দিষ্টটা (নতুন VPS-এও চলে)
#   lifolo-restore local latest | <নাম>                 এই VPS-এর /var/backups/lifolo থেকে
#   lifolo-restore cloud list                          শুধু তালিকা
#
# ধাপ: আগে এখনকার অবস্থার একটা ব্যাকআপ (ভুল হলে ফেরার পথ) → সার্ভার থামানো → pg_restore --clean → চালু → পরীক্ষা।
set -euo pipefail
SRC="${1:-}" NAME="${2:-latest}"
DIR=/var/backups/lifolo DB=lifolo PORT=3000
NAME_RE='^lifolo-[0-9]{8}-[0-9]{6}\.dump$'

die() { echo "XX $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"
[ "$SRC" = cloud ] || [ "$SRC" = local ] || die "ব্যবহার: lifolo-restore cloud|local latest|list|<নাম>"
[ "$NAME" = latest ] || [ "$NAME" = list ] || [[ "$NAME" =~ $NAME_RE ]] || die "ব্যাকআপের নাম ঠিক নেই: $NAME"

as_lifolo() {
  # /etc/lifolo/env (lifolo গ্রুপ পড়তে পারে) — systemd আর bash দুটোই একই নিয়মে পড়ে
  sudo -u lifolo -H bash -c 'set -a; . /etc/lifolo/env; set +a; cd /opt/lifolo/server && exec "$@"' _ "$@"
}

if [ "$NAME" = list ]; then
  if [ "$SRC" = cloud ]; then as_lifolo node standalone/backup-upload.js list; else ls -l "$DIR"; fi
  exit 0
fi

WORK="$(mktemp -d /tmp/lifolo-restore.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
FILE=
if [ "$SRC" = cloud ]; then
  chown lifolo:lifolo "$WORK"
  as_lifolo node standalone/backup-upload.js download "$NAME" "$WORK/restore.dump"
  FILE="$WORK/restore.dump"
else
  if [ "$NAME" = latest ]; then
    NAME="$(find "$DIR" -maxdepth 1 -type f -name 'lifolo-*.dump' -printf '%f\n' | sort | tail -1)"
    [ -n "$NAME" ] || die "$DIR-এ কোনো ব্যাকআপ নেই"
  fi
  FILE="$DIR/$NAME"
  [ -f "$FILE" ] || die "$FILE নেই"
fi
# ফাইলটা আসলেই pg_dump-এর কি না (ভুল ফাইলে ডাটাবেস মোছা নয়)
pg_restore --list "$FILE" >/dev/null || die "ব্যাকআপ ফাইল পড়া যাচ্ছে না: $FILE"
echo "ফেরত আনা হবে: $FILE ($(du -h "$FILE" | cut -f1))"

echo "==> আগে এখনকার অবস্থার ব্যাকআপ"
systemctl start lifolo-backup

echo "==> সার্ভার থামানো, ডাটাবেস ফেরত আনা"
systemctl stop lifolo lifolo-cron.timer
restart() { systemctl start lifolo lifolo-cron.timer; }
trap 'restart; rm -rf "$WORK"' EXIT
# shellcheck disable=SC2024  # ইচ্ছা করে: ফাইল root খোলে (postgres ফোল্ডারটা পড়তে পারে না), pg_restore stdin থেকে পড়ে
sudo -u postgres pg_restore --clean --if-exists --no-owner --role=lifolo --exit-on-error -d "$DB" < "$FILE"
restart
trap 'rm -rf "$WORK"' EXIT

for _ in $(seq 1 30); do
  curl -fsS -m 3 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS -m 15 "http://127.0.0.1:$PORT/api/health" || die "ফেরত আনার পর /api/health কাজ করছে না"
echo
echo "ফেরত আনা হয়েছে। ডকের সংখ্যা:"
sudo -u postgres psql -d "$DB" -tAc "SELECT split_part(coll, '/', 1) AS c, count(*) FROM docs GROUP BY 1 ORDER BY 1" 2>/dev/null || true
