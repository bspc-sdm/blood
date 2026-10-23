#!/usr/bin/env bash
# Lifolo — ওয়েবসাইট (lifolo.com) VPS-এ: Vue ওয়েব (/w/), Vue অ্যাডমিন (/admin/), বাকি সব Vercel-এর মতোই।
# সাধারণত GitHub Actions-এর "Deploy backend" (.github/workflows/deploy-backend.yml) চালায়, deploy-backend.sh-এর পরে
# (/api, /r, /rx, /shop, /.well-known → Spring-এর nginx অংশ ওটাই লেখে: /etc/nginx/snippets/lifolo-api.conf)।
#
#   bash deploy-web.sh --site /root/lifolo-setup/site.tar.gz --sha256 <hash> --web-domain lifolo.com [--ci]
#
# Vercel-এর server/vercel.json হুবহু:
#   /r/:id /rx/:code /shop/:code /.well-known/assetlinks.json → অ্যাপ (Spring)   /api/… → অ্যাপ (একই ঠিকানা, তাই ওয়েব বদলাতে হয় না)
#   /admin → /admin/index.html   /favicon.ico → /w/icon.svg   বাকি সব (api/ admin w/ r/ rx/ shop/ .well-known/ ছাড়া) → /w/index.html
#   হেডার: /admin… কড়া CSP + no-cache + noindex; বাকি পাতা ওয়েবের CSP + no-cache; /w/assets/ এক বছর immutable
#   (নাম hash-যুক্ত — Cloudflare পরে এগুলো জমা রাখতে পারবে)। /admin/assets/-ও hash-যুক্ত, তাই immutable (CSP সহ)।
# www.lifolo.com → https://lifolo.com (301)। সার্টিফিকেট (Let's Encrypt) শুধু যে নাম DNS-এ এই VPS-এ আসে তার জন্য;
# না এলে কিছু বদলায় না (status-এ dns_not_ready)। Cloudflare-এর proxy চালু হলে আসল IP: CF-Connecting-IP (শুধু Cloudflare-এর IP থেকে)।
#
# ফাইল: /var/www/lifolo-site/releases/<সময়>/ (w/, admin/), /var/www/lifolo-site/current → সর্বশেষ (শেষ ৫টা থাকে)।
# ফেরা: ln -sfn /var/www/lifolo-site/releases/<আগেরটা> /var/www/lifolo-site/current  (nginx reload লাগে না)
# ফল: /var/lib/lifolo/deploy-web-status (key=value, গোপন কিছু নয়)।
set -euo pipefail

SITE_DIR=/var/www/lifolo-site
SNIPPET_API=/etc/nginx/snippets/lifolo-api.conf
SNIPPET_WEB=/etc/nginx/snippets/lifolo-web.conf
SITE_CONF=/etc/nginx/sites-available/lifolo-web
REALIP_CONF=/etc/nginx/conf.d/lifolo-cloudflare-realip.conf
STATUS=/var/lib/lifolo/deploy-web-status
ACME=/var/www/letsencrypt
DL_DIR=/var/www/lifolo-dl

TARBALL="" SHA="" WEB_DOMAIN="" NO_CERT=no SENTRY_HOST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --site) TARBALL="$2"; shift 2 ;;
    --sha256) SHA="$2"; shift 2 ;;
    --web-domain) WEB_DOMAIN="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"; shift 2 ;;
    --ci) shift ;;
    --no-cert) NO_CERT=yes; shift ;;
    --sentry-host) SENTRY_HOST="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"; shift 2 ;;
    *) echo "অচেনা আর্গুমেন্ট: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }
install -d -m 0755 /var/lib/lifolo
: > "$STATUS"
chmod 0644 "$STATUS"
st() { printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\n\r' | cut -c1-500)" >> "$STATUS"; }
die() { printf 'XX %s\n' "$*" >&2; st fatal "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"
[[ "$WEB_DOMAIN" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || die "ডোমেইন ঠিক নেই: $WEB_DOMAIN"
# Sentry (ওয়েবের ভুলের খবর): শুধু ওয়েব অ্যাপের CSP-তে ওই একটা ঠিকানা (অ্যাডমিন পাঠায় না, তার CSP বদলায় না)
[ -z "$SENTRY_HOST" ] || [[ "$SENTRY_HOST" =~ ^[a-z0-9.-]+\.sentry\.io$ ]] || die "Sentry-র ঠিকানা ঠিক নেই: $SENTRY_HOST"
[ -f "$TARBALL" ] || die "ওয়েবের ফাইল পৌঁছায়নি ($TARBALL)"
grep -q 'managed by deploy-backend.sh' "$SNIPPET_API" 2>/dev/null || die "$SNIPPET_API নেই — আগে Deploy backend-এর অ্যাপ অংশ সফল হতে হবে"
WWW="www.$WEB_DOMAIN"

# ---------------------------------------------------------------- 1) ফাইল
say "ওয়েবের ফাইল যাচাই আর বসানো"
got="$(sha256sum "$TARBALL" | awk '{print $1}')"
[ "$got" = "$SHA" ] || die "ওয়েবের ফাইলের sha256 মেলেনি"
stamp="$(date -u +%Y%m%d-%H%M%S)-${SHA:0:8}"
rel="$SITE_DIR/releases/$stamp"
install -d -m 0755 "$SITE_DIR/releases" "$rel"
tar -xzf "$TARBALL" -C "$rel" --no-same-owner --no-same-permissions
rm -f "$TARBALL"
[ -f "$rel/w/index.html" ] && [ -f "$rel/admin/index.html" ] || die "ওয়েবের ফাইলে w/index.html বা admin/index.html নেই"
find "$rel" -type d -exec chmod 0755 {} + && find "$rel" -type f -exec chmod 0644 {} +
prev=""
[ -L "$SITE_DIR/current" ] && prev="$(readlink -f "$SITE_DIR/current")"
ln -sfn "$rel" "$SITE_DIR/current.new" && mv -T "$SITE_DIR/current.new" "$SITE_DIR/current"
st release "$stamp"
[ -z "$prev" ] || st previous "$(basename "$prev")"
# শেষ ৫টা রাখা (ফেরার জন্য)
find "$SITE_DIR/releases" -mindepth 1 -maxdepth 1 -type d | sort | head -n -5 | while read -r old; do
  [ "$old" = "$rel" ] || rm -rf "$old"
done

# ---------------------------------------------------------------- 2) Cloudflare-এর পেছনে আসল IP
say "Cloudflare: আসল IP (CF-Connecting-IP, শুধু Cloudflare-এর ঠিকানা থেকে)"
cf_v4="$(curl -fsS -m 10 https://www.cloudflare.com/ips-v4 2>/dev/null | grep -E '^[0-9./]+$' || true)"
cf_v6="$(curl -fsS -m 10 https://www.cloudflare.com/ips-v6 2>/dev/null | grep -E '^[0-9a-f:./]+$' || true)"
if [ "$(printf '%s\n' "$cf_v4" | grep -c .)" -lt 5 ]; then
  cf_v4="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18 190.93.240.0/20
188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22"
  cf_v6="2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32"
fi
if grep -q 'managed by tune-vps.sh' "$REALIP_CONF" 2>/dev/null; then
  # tune-vps.sh (Deploy backend-এর আগের ধাপ) এই ফাইল আর ufw-এর Cloudflare-তালিকা একসাথে রাখে — এখানে আর লেখা নয়
  st realip "ok (tune-vps.sh)"
else
  {
    echo "# Lifolo — deploy-web.sh লেখে: Cloudflare-এর proxy চালু হলে \$remote_addr = আসল দর্শক (CF-Connecting-IP)।"
    echo "# অন্য কোনো ঠিকানা থেকে এই হেডার এলে মানা হয় না। proxy বন্ধ থাকলে কোনো প্রভাব নেই।"
    for n in $cf_v4 $cf_v6; do echo "set_real_ip_from $n;"; done
    echo "real_ip_header CF-Connecting-IP;"
  } > "$REALIP_CONF.new"
  cp -p "$REALIP_CONF" "$REALIP_CONF.bak" 2>/dev/null || true
  mv "$REALIP_CONF.new" "$REALIP_CONF"
  chmod 0644 "$REALIP_CONF"
  if nginx -t 2>/dev/null; then
    st realip ok
  else
    if [ -f "$REALIP_CONF.bak" ]; then mv "$REALIP_CONF.bak" "$REALIP_CONF"; else rm -f "$REALIP_CONF"; fi
    st realip failed
  fi
fi

# ---------------------------------------------------------------- 3) nginx: সাইটের পথ আর হেডার
say "nginx: $WEB_DOMAIN-এর পথ (Vercel-এর vercel.json-এর মতো)"
HSTS='add_header Strict-Transport-Security "max-age=31536000" always;'
# vercel.json-এর হেডার — হুবহু
CSP_WEB="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data: blob:; connect-src 'self' wss://lifolo.com wss://call.lifolo.com https://call.lifolo.com https://firestore.googleapis.com https://identitytoolkit.googleapis.com https://securetoken.googleapis.com${SENTRY_HOST:+ https://$SENTRY_HOST}; manifest-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
CSP_ADMIN="default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
web_headers() { # $1 = Cache-Control
  cat <<EOF
    $HSTS
    add_header Content-Security-Policy "$CSP_WEB" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy "camera=(self), microphone=(self), payment=(), geolocation=(self)" always;
    add_header X-Frame-Options "DENY" always;
    add_header Cache-Control "$1" always;
EOF
}
admin_headers() { # $1 = Cache-Control
  cat <<EOF
    $HSTS
    add_header Content-Security-Policy "$CSP_ADMIN" always;
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;
    add_header Cross-Origin-Opener-Policy "same-origin" always;
    add_header Cache-Control "$1" always;
    add_header X-Robots-Tag "noindex, nofollow" always;
EOF
}
dl_headers() { # $1 = Cache-Control, $2 = attachment (APK) বা খালি
  echo "        $HSTS"
  echo "        add_header X-Content-Type-Options \"nosniff\" always;"
  echo "        add_header Cache-Control \"$1\" always;"
  [ "${2:-}" != attachment ] || echo "        add_header Content-Disposition \"attachment\" always;"
}
install -d -m 0755 "$DL_DIR" "$DL_DIR/download"
{
  echo "# Lifolo — deploy-web.sh লেখে (Deploy backend workflow)। হাতে বদলালে পরের deploy-এ মুছে যাবে।"
  echo "# server/vercel.json-এর পথ আর হেডার হুবহু; /api /r /rx /shop /.well-known → $SNIPPET_API (Spring)।"
  echo "root $SITE_DIR/current;"
  echo "charset utf-8;"
  echo "gzip on;"
  echo "gzip_vary on;"
  echo "gzip_min_length 512;"
  echo "gzip_types text/css application/javascript text/javascript application/json application/manifest+json image/svg+xml text/plain font/ttf application/vnd.ms-fontobject;"
  echo "$HSTS"
  echo
  echo "location ^~ /.well-known/acme-challenge/ { root $ACME; }"
  echo "location = /healthz {"
  echo "    access_log off;"
  echo "    default_type application/json;"
  echo "    return 200 '{\"ok\":true,\"service\":\"lifolo\",\"stage\":\"web\"}\n';"
  echo "}"
  echo
  echo "include $SNIPPET_API;"
  echo
  echo "# /w/assets/: নামে hash — এক বছর, কখনো বদলায় না"
  echo "location ^~ /w/assets/ {"
  echo "    $HSTS"
  echo "    add_header Cache-Control \"public, max-age=31536000, immutable\" always;"
  echo "    add_header X-Content-Type-Options \"nosniff\" always;"
  echo "    try_files \$uri =404;"
  echo "}"
  echo "# manifest: nginx-এর mime তালিকায় .webmanifest নেই"
  echo "location = /w/manifest.webmanifest {"
  echo "    default_type application/manifest+json;"
  web_headers "no-cache"
  echo "    try_files \$uri =404;"
  echo "}"
  echo "# /w/… (index.html, icon, manifest, data/): ওয়েবের CSP, no-cache। নেই এমন ফাইল → 404 (Vercel-এর মতো)"
  echo "location ^~ /w/ {"
  web_headers "no-cache"
  echo "    try_files \$uri \$uri/index.html =404;"
  echo "}"
  echo "# অ্যাডমিন: /admin → /admin/index.html; সব জায়গায় কড়া CSP"
  echo "location = /admin {"
  admin_headers "no-cache"
  echo "    try_files /admin/index.html =404;"
  echo "}"
  echo "location ^~ /admin/assets/ {"
  admin_headers "public, max-age=31536000, immutable"
  echo "    try_files \$uri =404;"
  echo "}"
  echo "location ^~ /admin {"
  admin_headers "no-cache"
  echo "    try_files \$uri \$uri/index.html =404;"
  echo "}"
  echo "# প্রাইভেসি পলিসি আর ব্যবহারের শর্ত: PRIVACY.md / TERMS.md থেকে বিল্ডের সময় বানানো পাতা (deploy/ci/render-legal.py)"
  echo "location ~ ^/(privacy|terms)/?\$ {"
  web_headers "no-cache"
  echo "    try_files /\$1/index.html =404;"
  echo "}"
  echo "# অ্যাপের ডাউনলোড: Build APK workflow $DL_DIR/download/-এ রাখে (ওয়েবের রিলিজ বদলালেও থাকে)।"
  echo "# version.json কখনো cache নয় (Cloudflare/ফোন সবসময় নতুনটা); lifolo.apk / lifolo-next.apk-এর নাম একই থাকে → no-cache;"
  echo "# lifolo-<ভার্সন>-<কোড>.apk কখনো বদলায় না → এক বছর।"
  echo "location ^~ /download/ {"
  echo "    root $DL_DIR;"
  echo "    types { application/vnd.android.package-archive apk; application/json json; }"
  echo "    default_type application/octet-stream;"
  echo "    autoindex off;"
  echo "    location = /download/version.json {"
  dl_headers "no-store, max-age=0"
  echo "        try_files \$uri =404;"
  echo "    }"
  echo "    location ~ ^/download/lifolo-[0-9][A-Za-z0-9._-]*\\.apk\$ {"
  dl_headers "public, max-age=31536000, immutable" attachment
  echo "        try_files \$uri =404;"
  echo "    }"
  echo "    location ~ \\.apk\$ {"
  dl_headers "no-cache" attachment
  echo "        try_files \$uri =404;"
  echo "    }"
  echo "    # ডেস্কটপ অ্যাপ (Desktop app workflow): latest.json (Tauri updater) আর desktop.json কখনো cache নয়;"
  echo "    # lifolo-desktop-<ভার্সন>.exe কখনো বদলায় না → এক বছর; lifolo-desktop.exe (সর্বশেষ) → no-cache"
  echo "    location ~ ^/download/desktop/(latest|desktop)\\.json\$ {"
  dl_headers "no-store, max-age=0"
  echo "        try_files \$uri =404;"
  echo "    }"
  echo "    location ~ ^/download/desktop/lifolo-desktop-[0-9][0-9.]*\\.exe\$ {"
  dl_headers "public, max-age=31536000, immutable" attachment
  echo "        try_files \$uri =404;"
  echo "    }"
  echo "    location ~ \\.exe\$ {"
  dl_headers "no-cache" attachment
  echo "        try_files \$uri =404;"
  echo "    }"
  dl_headers "no-cache"
  echo "    try_files \$uri =404;"
  echo "}"
  echo "location = /favicon.ico {"
  web_headers "no-cache"
  echo "    try_files /w/icon.svg =404;"
  echo "}"
  echo "# বাকি সব: থাকা ফাইল, নইলে ওয়েব অ্যাপ (/w/index.html) — /, /services, /appointment …"
  echo "location / {"
  web_headers "no-cache"
  echo "    try_files \$uri /w/index.html;"
  echo "}"
} > "$SNIPPET_WEB.new"
chmod 0644 "$SNIPPET_WEB.new"
cp -p "$SNIPPET_WEB" "$SNIPPET_WEB.bak" 2>/dev/null || true
mv "$SNIPPET_WEB.new" "$SNIPPET_WEB"

# ---------------------------------------------------------------- 4) DNS → সার্টিফিকেট → সাইট
MY_IPS=" $(hostname -I 2>/dev/null || true) "
# "yes" = সরাসরি এই VPS (ধূসর মেঘ), "cloudflare" = সব ঠিকানা Cloudflare-এর (কমলা মেঘ — proxy এখানেই পাঠায়), নইলে "no (…)"
cf_nets() { printf '%s\n' $cf_v4; }
dns_here() {
  local ip ips
  ips="$(getent ahostsv4 "$1" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ' || true)"
  for ip in $ips; do case "$MY_IPS" in *" $ip "*) echo yes; return 0 ;; esac; done
  # shellcheck disable=SC2086  # ঠিকানাগুলো আলাদা আর্গুমেন্ট হিসেবে
  if [ -n "$ips" ] && python3 -c 'import ipaddress,sys; nets=[ipaddress.ip_network(n) for n in sys.stdin.read().split()]; sys.exit(0 if all(any(ipaddress.ip_address(a) in n for n in nets) for a in sys.argv[1:]) else 1)' $ips <<<"$(cf_nets)"; then
    echo cloudflare; return 0
  fi
  echo "no (${ips:-no A record})"
}
ok_dns() { case "$1" in yes|cloudflare) return 0 ;; *) return 1 ;; esac; }
apex_dns="$(dns_here "$WEB_DOMAIN")"
www_dns="$(dns_here "$WWW")"
st dns_apex "$apex_dns"
st dns_www "$www_dns"
CERT="/etc/letsencrypt/live/$WEB_DOMAIN/fullchain.pem"
names=()
ok_dns "$apex_dns" && names+=("$WEB_DOMAIN")
ok_dns "$www_dns" && names+=("$WWW")
cert_has() { [ -f "$CERT" ] && openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | grep -q "DNS:$1\(,\|$\)"; }
# Cloudflare-এর পেছনে: Let's Encrypt-এর HTTP-01 (নবায়নও) Cloudflare দিয়েই আসে — এই VPS থেকে Cloudflare হয়ে
# /.well-known/acme-challenge/-এ একটা ফাইল পড়া যায় কি না (সার্টিফিকেট নবায়ন চলবে কি না তার ইঙ্গিত)
if [ "$apex_dns" = cloudflare ]; then
  tok="lifolo-probe-$(date +%s)-$$"
  install -d -m 0755 "$ACME/.well-known/acme-challenge"
  echo "$tok" > "$ACME/.well-known/acme-challenge/$tok"
  got="$(curl -sSL -m 20 "http://$WEB_DOMAIN/.well-known/acme-challenge/$tok" 2>/dev/null | head -c 200 || true)"
  rm -f "$ACME/.well-known/acme-challenge/$tok"
  if [ "$got" = "$tok" ]; then st acme_via_cloudflare ok; else st acme_via_cloudflare "failed (${got:0:60})"; fi
  [ ! -f "$CERT" ] || st cert_expires "$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)"
fi
if ! ok_dns "$apex_dns"; then
  st site "dns_not_ready ($apex_dns)"
else
  need=no
  for n in "${names[@]}"; do cert_has "$n" || need=yes; done
  get_cert() {
    local dargs=() n
    for n in "$@"; do dargs+=(-d "$n"); done
    say "সার্টিফিকেট: $*"
    if certbot certonly --webroot -w "$ACME" --cert-name "$WEB_DOMAIN" "${dargs[@]}" --expand \
        --register-unsafely-without-email --agree-tos --non-interactive --keep-until-expiring \
        --deploy-hook "systemctl reload nginx" >/tmp/lifolo-certbot.log 2>&1; then
      return 0
    fi
    tail -25 /tmp/lifolo-certbot.log
    # কারণ সারাংশে (Let's Encrypt-এর লেখা; গোপন কিছু নয়)
    st cert_error "$*: $(grep -hE 'Detail:|Type:|Error:|error:|too many' /tmp/lifolo-certbot.log | sed 's/^[[:space:]]*//' | head -3 | tr '\n' ' ' | cut -c1-250)"
    return 1
  }
  # DNS কেমন দেখায় বাইরের কয়েকটা resolver থেকে (Let's Encrypt-এর "DNS problem" বোঝার জন্য; DNSSEC ভুল হলে SERVFAIL)
  dns_diag() {
    command -v dig >/dev/null || { echo "dig নেই"; return 0; }
    local r out
    for r in 1.1.1.1 8.8.8.8 9.9.9.9; do
      out="$(dig +time=3 +tries=1 A "$WEB_DOMAIN" @"$r" 2>&1 | grep -oE 'status: [A-Z]+' | head -1)"
      printf '%s A %s %s; ' "$r" "${out#status: }" "$(dig +short +time=3 +tries=1 A "$WEB_DOMAIN" @"$r" 2>/dev/null | tr '\n' ',')"
    done
    printf 'DS: %s; ' "$(dig +short +time=3 +tries=1 DS "$WEB_DOMAIN" @1.1.1.1 2>/dev/null | head -2 | cut -c1-40 | tr '\n' ',')"
    printf 'NS: %s; ' "$(dig +short +time=3 +tries=1 NS "$WEB_DOMAIN" @1.1.1.1 2>/dev/null | tr '\n' ',')"
    # .com-এর সার্ভার কোন NS দেখায় (রেজিস্ট্রারে পুরোনো NS থেকে গেলে Let's Encrypt-এর কিছু জায়গা থেকে খোঁজা ব্যর্থ হয়)
    printf 'parent NS: %s; ' "$(dig +norec +time=3 +tries=1 NS "$WEB_DOMAIN" @a.gtld-servers.net 2>/dev/null | awk '$4=="NS"{print $5}' | sort -u | tr '\n' ',')"
    printf 'CAA: %s; ' "$(dig +short +time=3 +tries=1 CAA "$WEB_DOMAIN" @1.1.1.1 2>/dev/null | tr '\n' ',')"
    printf 'AAAA: %s' "$(dig +short +time=3 +tries=1 AAAA "$WEB_DOMAIN" @1.1.1.1 2>/dev/null | tr '\n' ',')"
  }
  # Let's Encrypt: এক ঘণ্টায় একই নামে ৫টা ব্যর্থ চেষ্টার পর আটকায় — তাই এক ঘণ্টায় ৩টার বেশি চেষ্টা নয়
  FAILS_FILE="/var/lib/lifolo/cert-fails-$WEB_DOMAIN"
  recent_fails() { [ -f "$FAILS_FILE" ] && awk -v t="$(( $(date +%s) - 3600 ))" '$1 > t' "$FAILS_FILE" | wc -l || echo 0; }
  if [ "$need" = yes ]; then
    st dns_diag "$(dns_diag)"
    if [ "$NO_CERT" = yes ]; then
      st cert_error "এবার সার্টিফিকেট চাওয়া হয়নি (--no-cert)"
    elif [ "$(recent_fails)" -ge 3 ]; then
      st cert_error "গত এক ঘণ্টায় ৩বার ব্যর্থ — Let's Encrypt-এর সীমা এড়াতে এবার চেষ্টা করা হলো না; এক ঘণ্টা পর আবার চালান"
    else
      # দুটো নাম একসাথে না হলে (শুধু www-এর সমস্যায়) মূল নাম একা
      if ! get_cert "${names[@]}"; then
        date +%s >> "$FAILS_FILE"
        if [ "${#names[@]}" -gt 1 ] && ! grep -q "for $WEB_DOMAIN\b\|looking up [A-Z]* for $WEB_DOMAIN;" /tmp/lifolo-certbot.log; then
          get_cert "$WEB_DOMAIN" || date +%s >> "$FAILS_FILE"
        fi
      fi
    fi
  fi
  if ! cert_has "$WEB_DOMAIN"; then
    st site cert_failed
  else
    st cert "$(openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | grep -o 'DNS:[^,]*' | tr '\n' ' ')"
    www_ok=no
    cert_has "$WWW" && www_ok=yes
    {
      echo "# Lifolo — deploy-web.sh লেখে: $WEB_DOMAIN (ওয়েব + /api), $WWW → $WEB_DOMAIN"
      echo "server {"
      echo "    listen 80;"
      echo "    listen [::]:80;"
      echo "    server_name $WEB_DOMAIN $WWW;"
      echo "    location ^~ /.well-known/acme-challenge/ { root $ACME; }"
      echo "    location / { return 301 https://$WEB_DOMAIN\$request_uri; }"
      echo "}"
      if [ "$www_ok" = yes ]; then
        echo "server {"
        echo "    listen 443 ssl http2;"
        echo "    listen [::]:443 ssl http2;"
        echo "    server_name $WWW;"
        echo "    ssl_certificate $CERT;"
        echo "    ssl_certificate_key /etc/letsencrypt/live/$WEB_DOMAIN/privkey.pem;"
        echo "    ssl_protocols TLSv1.2 TLSv1.3;"
        echo "    ssl_prefer_server_ciphers off;"
        echo "    ssl_session_cache shared:lifolo_ssl:10m;"
        echo "    ssl_session_timeout 1d;"
        echo "    ssl_session_tickets off;"
        echo "    $HSTS"
        echo "    location ^~ /.well-known/acme-challenge/ { root $ACME; }"
        echo "    location / { return 301 https://$WEB_DOMAIN\$request_uri; }"
        echo "}"
      fi
      echo "server {"
      echo "    listen 443 ssl http2;"
      echo "    listen [::]:443 ssl http2;"
      echo "    server_name $WEB_DOMAIN;"
      echo "    ssl_certificate $CERT;"
      echo "    ssl_certificate_key /etc/letsencrypt/live/$WEB_DOMAIN/privkey.pem;"
      echo "    ssl_protocols TLSv1.2 TLSv1.3;"
      echo "    ssl_prefer_server_ciphers off;"
      echo "    ssl_session_cache shared:lifolo_ssl:10m;"
      echo "    ssl_session_timeout 1d;"
      echo "    ssl_session_tickets off;"
      echo "    include $SNIPPET_WEB;"
      echo "}"
    } > "$SITE_CONF.new"
    [ -e /proc/net/if_inet6 ] || sed -i '/listen \[::\]/d' "$SITE_CONF.new"
    cp -p "$SITE_CONF" "$SITE_CONF.bak" 2>/dev/null || true
    mv "$SITE_CONF.new" "$SITE_CONF"
    chmod 0644 "$SITE_CONF"
    ln -sf "$SITE_CONF" /etc/nginx/sites-enabled/lifolo-web
    if nginx -t 2>/tmp/lifolo-nginx-t.log; then
      systemctl reload nginx
      st site ok
      st www "$([ "$www_ok" = yes ] && echo ok || echo "skipped ($www_dns)")"
    else
      cat /tmp/lifolo-nginx-t.log || true
      if [ -f "$SITE_CONF.bak" ]; then mv "$SITE_CONF.bak" "$SITE_CONF"; else rm -f /etc/nginx/sites-enabled/lifolo-web "$SITE_CONF"; fi
      [ -f "$SNIPPET_WEB.bak" ] && cp -p "$SNIPPET_WEB.bak" "$SNIPPET_WEB"
      systemctl reload nginx || true
      st site nginx_failed
    fi
  fi
fi

# ---------------------------------------------------------------- 5) পরীক্ষা (এই মেশিন থেকে, https দিয়ে)
if [ "$(sed -n 's/^site=//p' "$STATUS")" = ok ]; then
  say "পরীক্ষা"
  # grep ছাড়া -q: পুরো লেখা পড়ে (pipefail-এ -q আগেভাগে থামলে curl SIGPIPE পেয়ে "ব্যর্থ" দেখাত)
  H() { curl -sS -m 15 --resolve "$WEB_DOMAIN:443:127.0.0.1" --resolve "$WWW:443:127.0.0.1" "$@"; }
  B="https://$WEB_DOMAIN"
  check() { if eval "$2"; then st "$1" ok; echo "ok   $1"; else st "$1" failed; echo "FAIL $1"; fi; }
  hdr() { H -o /dev/null -D - "$@" | tr -d '\r'; }
  asset="$(grep -oE '/w/assets/[A-Za-z0-9._-]+\.js' "$rel/w/index.html" | head -1)"
  admin_asset="$(grep -oE '/admin/assets/[A-Za-z0-9._-]+\.js' "$rel/admin/index.html" | head -1)"
  check t_root "H '$B/' | grep '/w/assets/' >/dev/null"
  check t_spa_route "H '$B/services/pharmacy' | grep '/w/assets/' >/dev/null"
  check t_root_csp "hdr '$B/' | grep -i \"^content-security-policy: default-src 'self'\" >/dev/null"
  check t_root_nocache "hdr '$B/' | grep -i '^cache-control: no-cache' >/dev/null"
  check t_asset_cache "[ -n '$asset' ] && hdr '$B$asset' | grep -i '^cache-control: public, max-age=31536000, immutable' >/dev/null"
  check t_w_missing "[ \"\$(H -o /dev/null -w '%{http_code}' '$B/w/nope-404.js')\" = 404 ]"
  check t_admin "H '$B/admin' | grep '/admin/assets/' >/dev/null && H '$B/admin/' | grep '/admin/assets/' >/dev/null"
  check t_admin_csp "hdr '$B/admin/' | grep -i \"^content-security-policy: default-src 'none'\" >/dev/null && hdr '$B/admin/' | grep -i '^x-robots-tag: noindex' >/dev/null"
  check t_admin_asset "[ -n '$admin_asset' ] && [ \"\$(H -o /dev/null -w '%{http_code}' '$B$admin_asset')\" = 200 ]"
  check t_favicon "hdr '$B/favicon.ico' | grep -i '^content-type: image/svg+xml' >/dev/null"
  check t_api "H '$B/api/health' | grep '\"ok\":true' >/dev/null"
  check t_assetlinks "H -o /dev/null -w '%{http_code} %{content_type}' '$B/.well-known/assetlinks.json' | grep '^200 application/json' >/dev/null"
  check t_share "! H '$B/r/deploy-smoke' | grep '/w/assets/' >/dev/null"
  check t_hsts "hdr '$B/' | grep -i '^strict-transport-security' >/dev/null"
  check t_gzip "[ -n '$asset' ] && H -H 'Accept-Encoding: gzip' -o /dev/null -D - '$B$asset' | tr -d '\r' | grep -i '^content-encoding: gzip' >/dev/null"
  check t_privacy "H '$B/privacy' | grep 'Privacy Policy' >/dev/null && hdr '$B/privacy' | grep -i '^content-type: text/html' >/dev/null"
  check t_terms "H '$B/terms/' | grep 'Terms of Use' >/dev/null"
  if [ -f "$DL_DIR/download/version.json" ]; then
    check t_dl_version "hdr '$B/download/version.json' | grep -i '^cache-control: no-store' >/dev/null && H '$B/download/version.json' | grep '\"versionCode\"' >/dev/null"
  fi
  if [ -f "$DL_DIR/download/lifolo.apk" ]; then
    check t_dl_apk "hdr '$B/download/lifolo.apk' | grep -i '^content-type: application/vnd.android.package-archive' >/dev/null"
  fi
  check t_dl_missing "[ \"\$(H -o /dev/null -w '%{http_code}' '$B/download/nope.apk')\" = 404 ]"
  check t_http_redirect "curl -sS -m 10 --resolve '$WEB_DOMAIN:80:127.0.0.1' -o /dev/null -w '%{http_code} %{redirect_url}' 'http://$WEB_DOMAIN/x?y=1' | grep '^301 https://$WEB_DOMAIN/x?y=1' >/dev/null"
  if [ "$(sed -n 's/^www=//p' "$STATUS")" = ok ]; then
    check t_www_redirect "H -o /dev/null -w '%{http_code} %{redirect_url}' 'https://$WWW/services?a=1' | grep '^301 https://$WEB_DOMAIN/services?a=1' >/dev/null"
  fi
fi
st finished yes
echo
cat "$STATUS"
