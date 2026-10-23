#!/usr/bin/env bash
# ডেস্কটপ অ্যাপ (Windows) → VPS-এর https://lifolo.com/download/desktop/ ("Desktop app" workflow-এর release ধাপ চালায়)।
# APK-র publish-download.sh-এর একই ধরন: আগে `bash deploy/ci/vps-connect.sh` (তাতে `ssh vps`), প্রতিটা ফাইল .part নামে গিয়ে
# sha256 মিলিয়ে তারপর mv (অর্ধেক-লেখা ফাইল কেউ পায় না)।
#
#   publish-desktop.sh <setup.exe> <setup.exe.sig> <version>
#     → lifolo-desktop-<ভার্সন>.exe (কখনো বদলায় না), lifolo-desktop.exe (সর্বশেষ, ডাউনলোডের বোতাম),
#       desktop.json {version, url, sha256, size} (ডাউনলোডের পাতা), তারপর সবশেষে latest.json (Tauri updater —
#       বসানো অ্যাপ নতুন ভার্সনের খবর পায় ফাইল তৈরি থাকার পরেই)। ভার্সনের নামের exe শেষ ৩টা থাকে।
# ফল (stdout-এ key=value): dl_url, dl_sha256, dl_files; আর $RUNNER_TEMP/latest.json (GitHub রিলিজের জন্য)
set -euo pipefail

DIR=/var/www/lifolo-dl/download/desktop
BASE=https://lifolo.com/download/desktop
exe="${1:-}"; sig="${2:-}"; ver="${3:-}"
[ -f "$exe" ] || { echo "::error::installer নেই: $exe" >&2; exit 1; }
[ -f "$sig" ] || { echo "::error::সই (.sig) নেই: $sig" >&2; exit 1; }
[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "::error::ভার্সন ঠিক নেই: $ver" >&2; exit 1; }
sha="$(sha256sum "$exe" | awk '{print $1}')"
size="$(stat -c %s "$exe")"

put() {
  local name="$1" want="$2"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "::error::নাম ঠিক নেই: $name" >&2; exit 1; }
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::sha256 ঠিক নেই" >&2; exit 1; }
  # shellcheck disable=SC2029  # নাম আর hash ওপরে কড়া যাচাই করা
  ssh vps "set -e; install -d -m 0755 '$DIR'; umask 022; cat > '$DIR/.$name.part'; \
    [ \"\$(sha256sum '$DIR/.$name.part' | cut -d' ' -f1)\" = '$want' ] || { rm -f '$DIR/.$name.part'; echo 'sha256 mismatch: $name' >&2; exit 1; }; \
    chmod 0644 '$DIR/.$name.part'; mv -f '$DIR/.$name.part' '$DIR/$name'"
}
put_text() { # $1 = নাম, $2 = ফাইল
  put "$1" "$(sha256sum "$2" | awk '{print $1}')" < "$2"
}

vexe="lifolo-desktop-${ver}.exe"
put "$vexe" "$sha" < "$exe"
put lifolo-desktop.exe "$sha" < "$exe"

tmp="$(mktemp -d)"
node -e '
  const [ver, url, sha, size, sigFile, out] = process.argv.slice(1);
  const fs = require("fs");
  const signature = fs.readFileSync(sigFile, "utf8").trim();
  fs.writeFileSync(out + "/desktop.json", JSON.stringify({ version: ver, url, sha256: sha, size: Number(size) }) + "\n");
  const plat = { signature, url };
  fs.writeFileSync(out + "/latest.json", JSON.stringify({
    version: ver,
    notes: "Lifolo " + ver,
    pub_date: new Date().toISOString(),
    platforms: { "windows-x86_64": plat, "windows-x86_64-nsis": plat },
  }, null, 2) + "\n");
' "$ver" "$BASE/$vexe" "$sha" "$size" "$sig" "$tmp"
put_text desktop.json "$tmp/desktop.json"
put_text latest.json "$tmp/latest.json"
cp "$tmp/latest.json" "${RUNNER_TEMP:-/tmp}/latest.json"
rm -rf "$tmp"

# ভার্সনের নামের exe শেষ ৩টা (ভার্সন ধরে সাজানো)
# shellcheck disable=SC2016
ssh vps "cd '$DIR' && ls -1 | grep -E '^lifolo-desktop-[0-9]+\.[0-9]+\.[0-9]+\.exe\$' | sort -V | head -n -3 | xargs -r rm -f --"
echo "dl_url=$BASE/$vexe"
echo "dl_sha256=$sha"
echo "dl_files=$(ssh vps "ls -1 '$DIR' | grep -v '^\\.' | tr '\n' ' '")"
