#!/usr/bin/env bash
# PostgreSQL-এর "lifolo" ডাটাবেসের ব্যাকআপ (pg_dump custom format), ১৪ দিনের পুরোনো মোছা।
# setup-vps.sh এটা /usr/local/sbin/lifolo-backup-এ কপি করে; postgres ব্যবহারকারী হিসেবে চলে (lifolo-backup.timer)।
# সফল হলে systemd lifolo-backup-upload.service চালায় — Firebase-এর Cloud Storage-এ কপি (server/standalone/backup-upload.js)।
# হাতে চালাতে:  sudo systemctl start lifolo-backup
set -euo pipefail
DIR=${BACKUP_DIR:-/var/backups/lifolo}
DB=${BACKUP_DB:-lifolo}
KEEP_DAYS=${KEEP_DAYS:-14}
umask 027  # lifolo গ্রুপ (আপলোডার) পড়তে পারে; ফোল্ডার setgid
ts=$(date -u +%Y%m%d-%H%M%S)
tmp="$DIR/.lifolo-$ts.dump.partial"
trap 'rm -f "$tmp"' EXIT
pg_dump --format=custom --compress=6 --dbname="$DB" --file="$tmp"
mv "$tmp" "$DIR/lifolo-$ts.dump"
trap - EXIT
# ১৪ দিনের বেশি পুরোনো (আর আধা লেখা) ফাইল মোছা
find "$DIR" -maxdepth 1 -type f -name 'lifolo-*.dump' -mtime +"$((KEEP_DAYS - 1))" -delete
find "$DIR" -maxdepth 1 -type f -name '.lifolo-*.partial' -mmin +120 -delete
echo "backup ok: $DIR/lifolo-$ts.dump ($(du -h "$DIR/lifolo-$ts.dump" | cut -f1))"
