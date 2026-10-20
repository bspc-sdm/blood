#!/usr/bin/env bash
# APK → VPS-এর https://lifolo.com/download/ (GitHub Actions-এর "Build APK" আর "Build APK (next)" চালায়)।
# আগে `bash deploy/ci/vps-connect.sh` (তাতে `ssh vps` তৈরি)। nginx-এর /download/ অংশ deploy/deploy-web.sh লেখে।
#
#   publish-download.sh release <apk> <versionName> <versionCode>
#       → lifolo-<ভার্সন>-<কোড>.apk, lifolo.apk, তারপর সবশেষে version.json (ফোন নতুন ভার্সনের খবর পায় APK তৈরি থাকার পরেই);
#         ভার্সনের নামের APK শেষ ৩টা থাকে।
#   publish-download.sh next <apk>
#       → lifolo-next.apk (version.json ছোঁয়া হয় না)
#
# প্রতিটা ফাইল আগে .part নামে যায়, sha256 মিলিয়ে তারপর mv (অর্ধেক-লেখা ফাইল কেউ কখনো পায় না)।
# ফল (stdout-এ key=value): dl_url, dl_sha256, dl_files
set -euo pipefail

DIR=/var/www/lifolo-dl/download
BASE=https://lifolo.com/download
mode="${1:-}"; apk="${2:-}"
[ -f "$apk" ] || { echo "::error::APK নেই: $apk" >&2; exit 1; }
sha="$(sha256sum "$apk" | awk '{print $1}')"

# $1 = ফাইলের নাম (DIR-এর ভেতরে); stdin = বিষয়বস্তু; $2 = প্রত্যাশিত sha256
put() {
  local name="$1" want="$2"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "::error::নাম ঠিক নেই: $name" >&2; exit 1; }
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::sha256 ঠিক নেই" >&2; exit 1; }
  # shellcheck disable=SC2029  # নাম আর hash ওপরে কড়া যাচাই করা
  ssh vps "set -e; install -d -m 0755 '$DIR'; umask 022; cat > '$DIR/.$name.part'; \
    [ \"\$(sha256sum '$DIR/.$name.part' | cut -d' ' -f1)\" = '$want' ] || { rm -f '$DIR/.$name.part'; echo 'sha256 mismatch: $name' >&2; exit 1; }; \
    chmod 0644 '$DIR/.$name.part'; mv -f '$DIR/.$name.part' '$DIR/$name'"
}

case "$mode" in
  release)
    name="${3:-}"; code="${4:-}"
    [[ "$name" =~ ^[0-9A-Za-z.+-]+$ ]] && [[ "$code" =~ ^[0-9]+$ ]] || { echo "::error::ভার্সন ঠিক নেই: $name ($code)" >&2; exit 1; }
    vapk="lifolo-${name}-${code}.apk"
    put "$vapk" "$sha" < "$apk"
    put lifolo.apk "$sha" < "$apk"
    tmp="$(mktemp)"
    printf '{"versionCode": %s, "versionName": "%s", "url": "%s/%s"}\n' "$code" "$name" "$BASE" "$vapk" > "$tmp"
    put version.json "$(sha256sum "$tmp" | awk '{print $1}')" < "$tmp"
    rm -f "$tmp"
    # ভার্সনের নামের APK শেষ ৩টা রাখা (কোড ধরে সাজানো) — পুরোনো ফোনে আপডেটের লিংক version.json-এর নতুনটাই
    # shellcheck disable=SC2016  # $DIR-এর মান এখানে বসানো, বাকিটা VPS-এ চলে
    ssh vps "cd '$DIR' && ls -1 | grep -E '^lifolo-[0-9][A-Za-z0-9.+-]*-[0-9]+\.apk\$' | awk -F- '{c=\$NF; sub(/\.apk\$/, \"\", c); print c\"\t\"\$0}' | sort -n | head -n -3 | cut -f2 | xargs -r rm -f --"
    echo "dl_url=$BASE/$vapk"
    ;;
  next)
    put lifolo-next.apk "$sha" < "$apk"
    echo "dl_url=$BASE/lifolo-next.apk"
    ;;
  *) echo "usage: publish-download.sh release <apk> <versionName> <versionCode> | next <apk>" >&2; exit 2 ;;
esac
echo "dl_sha256=$sha"
echo "dl_files=$(ssh vps "ls -1 '$DIR' | grep -v '^\\.' | tr '\n' ' '")"
