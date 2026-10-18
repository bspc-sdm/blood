#!/usr/bin/env bash
# Firestore → PostgreSQL-এ চলে যাওয়া (আর দরকার হলে ফেরা)। GitHub-এর "Cutover to PostgreSQL" workflow
# (.github/workflows/cutover.yml) এটা VPS-এ root হিসেবে চালায় (stdin দিয়ে পাঠিয়ে)। pg-migration main-এ আসার পরেই।
#
#   bash cutover.sh cutover [keep_demo] [only]   কপি → মেলানো (--verify) → DATABASE_URL চালু → পরীক্ষা
#   bash cutover.sh rollback                     DATABASE_URL বন্ধ → আবার Firestore
#   bash cutover.sh status                       এখন কোনটা চলছে আর PostgreSQL-এ কত ডক
#
# keep_demo: "true" হলে ডেমো অ্যাকাউন্ট/ডেটাও থাকে; না হলে কপির পর PostgreSQL থেকে মোছা হয় (অ্যাপের নিজের
#            "সব ডেমো মুছুন" কোড দিয়ে — পরে অ্যাডমিন প্যানেল থেকে আবার বানানো যায়)।
# only:      কমা দিয়ে কিছু top-level কালেকশন (খালি = সব)।
# পরীক্ষা ব্যর্থ হলে নিজে থেকেই rollback। Firestore-এ কিছু লেখা বা মোছা হয় না।
set -euo pipefail
MODE="${1:-status}" KEEP_DEMO="${2:-false}" ONLY="${3:-}"
ENV_FILE=/etc/lifolo/env APP=/opt/lifolo/server PORT=3000 DB=lifolo

die() { echo "::error::$*" >&2; exit 1; }
say() { printf '\n==> %s\n' "$*"; }
[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"
[ -f "$ENV_FILE" ] || die "$ENV_FILE নেই — আগে Setup VPS চালান"
[[ "$ONLY" =~ ^[A-Za-z0-9_,]*$ ]] || die "only-তে শুধু কালেকশনের নাম, কমা দিয়ে"

as_lifolo() {
  # DATABASE_URL # দিয়ে বন্ধ থাকলেও এখানে চালু (কপি/মেলানোর জন্য) — মান কমান্ড লাইনে আসে না
  sudo -u lifolo -H bash -c '
    set -a; . /etc/lifolo/env; set +a
    DATABASE_URL="$(sed -nE "s/^#?DATABASE_URL=//p" /etc/lifolo/env | head -1)"
    DATABASE_URL="${DATABASE_URL%\"}"; DATABASE_URL="${DATABASE_URL#\"}"
    export DATABASE_URL
    cd /opt/lifolo/server && exec "$@"' _ "$@"
}

enabled() { grep -qE '^DATABASE_URL=.+' "$ENV_FILE"; }
enable_pg() { sed -i -E 's|^#DATABASE_URL=(.+)$|DATABASE_URL=\1|' "$ENV_FILE"; }
disable_pg() { sed -i -E 's|^DATABASE_URL=(.+)$|#DATABASE_URL=\1|' "$ENV_FILE"; }

health() {
  local i
  for i in $(seq 1 40); do
    curl -fsS -m 3 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break
    [ "$i" = 40 ] && return 1
    sleep 1
  done
  curl -fsS -m 20 "http://127.0.0.1:$PORT/api/health"
  echo
}

counts() {
  echo "PostgreSQL-এ ডকের সংখ্যা (top-level কালেকশন ধরে):"
  sudo -u postgres psql -d "$DB" -P pager=off -c \
    "SELECT split_part(coll, '/', 1) AS collection, count(*) AS docs FROM docs GROUP BY 1 ORDER BY 1" 2>/dev/null \
    || echo "(docs টেবিল এখনো নেই)"
  sudo -u postgres psql -d "$DB" -tAc "SELECT 'মোট: ' || count(*) FROM docs" 2>/dev/null || true
}

rollback() {
  disable_pg
  systemctl restart lifolo
  health || true
}

case "$MODE" in
  status)
    if enabled; then echo "এখন চলছে: PostgreSQL"; else echo "এখন চলছে: Firestore (DATABASE_URL বন্ধ)"; fi
    counts
    ;;
  rollback)
    say "ফেরা: DATABASE_URL বন্ধ → Firestore"
    rollback
    echo "এখন সার্ভার Firestore ব্যবহার করছে। PostgreSQL-এর ডেটা মোছা হয়নি।"
    ;;
  cutover)
    grep -qE '^#?DATABASE_URL=postgres(ql)?://' "$ENV_FILE" || die "$ENV_FILE-এ DATABASE_URL নেই — Setup VPS আবার চালান"
    say "সবচেয়ে নতুন কোড (main)"
    sudo -u lifolo -H /usr/local/sbin/lifolo-deploy || die "কোড আপডেট হয়নি"
    { [ -f "$APP/scripts/migrate-firestore-to-pg.js" ] && [ -f "$APP/lib/pgstore.js" ]; } \
      || die "PostgreSQL-এর কোড (pg-migration) এখনো এই শাখায় নেই — আগে main-এ merge হতে হবে"

    say "আগে PostgreSQL-এর এখনকার অবস্থার ব্যাকআপ"
    systemctl start lifolo-backup || echo "(ব্যাকআপ হয়নি — ডাটাবেস খালি হলে স্বাভাবিক)"

    if enabled; then
      say "আগে থেকে PostgreSQL চালু ছিল — কপির সময় Firestore-এ ফেরানো"
      disable_pg
    fi
    say "সার্ভার থামানো (অল্প সময়ের জন্য বন্ধ)"
    systemctl stop lifolo lifolo-cron.timer
    trap 'echo "ব্যর্থ — Firestore-এ ফেরা"; disable_pg; systemctl start lifolo lifolo-cron.timer' EXIT

    args=()
    [ -n "$ONLY" ] && args+=(--only "$ONLY")
    say "Firestore → PostgreSQL কপি"
    as_lifolo node scripts/migrate-firestore-to-pg.js "${args[@]}"
    say "মেলানো (--verify)"
    as_lifolo node scripts/migrate-firestore-to-pg.js --verify "${args[@]}" || die "Firestore আর PostgreSQL মেলেনি (ওপরে ✗ দেখুন) — কিছু চালু করা হয়নি"

    if [ "$KEEP_DEMO" != true ]; then
      say "ডেমো অ্যাকাউন্ট আর ডামি ডেটা PostgreSQL থেকে মোছা (পরে অ্যাডমিন প্যানেল থেকে আবার বানানো যায়)"
      as_lifolo node -e '
        const { wipe } = require("./lib/demo");
        wipe({ all: true }).then((r) => { console.log("demo removed:", JSON.stringify(r)); process.exit(0); },
          (e) => { console.error(e); process.exit(1); });'
    fi

    say "DATABASE_URL চালু → সার্ভার PostgreSQL ব্যবহার করবে"
    enable_pg
    systemctl start lifolo lifolo-cron.timer
    say "পরীক্ষা (smoke test)"
    out="$(health)" || die "সার্ভার চালু হয়নি"
    echo "$out"
    case "$out" in *'"ok":true'*) ;; *) die "/api/health ঠিক নেই: $out" ;; esac
    curl -fsS -m 15 -o /dev/null "http://127.0.0.1:$PORT/" || die "ওয়েব পাতা খুলছে না"
    trap - EXIT
    counts
    say "শেষ: সার্ভার এখন PostgreSQL-এ। ফিরতে চাইলে workflow-টা mode = rollback দিয়ে চালান।"
    ;;
  *) die "mode হবে cutover, rollback বা status" ;;
esac
