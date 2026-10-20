#!/usr/bin/env bash
# Lifolo — VPS-এর ক্ষমতার সেটিং আর origin-এর পাহারা (root হিসেবে)। বারবার চালালেও একই ফল (idempotent): যা আগে থেকেই
# ঠিক আছে তা ছোঁয়া হয় না, সার্ভিস restart শুধু সেটিং সত্যিই বদলালে।
#
# কে চালায়: "Deploy backend" (deploy-backend.sh-এর আগে, --no-app-restart — অ্যাপ deploy নিজেই restart করে) আর
# "Setup VPS (base)" (setup-base.sh-এর পরে)। নিজেকে /usr/local/sbin/lifolo-tune-এ বসায় — timer, certbot আর CI ওটাই ডাকে।
#
#   tune-vps.sh [apply] [--no-app-restart]   নিচের ১-৫ সব
#   lifolo-tune firewall          Cloudflare-এর ঠিকানা আনা → nginx realip (আসল দর্শকের IP) + ufw: 80/443 শুধু Cloudflare থেকে
#   lifolo-tune guard             (timer, ১৫ মিনিট পরপর) মেয়াদ-পেরোনো CI-ছিদ্র বন্ধ; Cloudflare-এর তালিকা ৭ দিনের পুরোনো হলে firewall
#   lifolo-tune ci-open IP MIN    CI-র সাময়িক ছিদ্র: IP → 80/443 আর nginx-এর IP-সীমার বাইরে; MIN মিনিট পরে guard নিজে বন্ধ করে
#   lifolo-tune ci-close IP       সেই ছিদ্র এখনই বন্ধ
#   lifolo-tune acme-open|acme-close   certbot-এর pre/post hook: নবায়নের সময়টুকু পোর্ট 80 সবার জন্য খোলা
#                                 (sslip.io-র নাম Cloudflare-এর পেছনে নয় — Let's Encrypt সরাসরি আসে)
#   lifolo-tune status            এখনকার অবস্থা (key=value)
#
# apply:
#   1) JVM — lifolo-api.service.d/20-tuning.conf: -Xms1g -Xmx3g, G1 (pause লক্ষ্য 200 ms), OOM-এ বেরিয়ে যাওয়া (systemd আবার
#      চালায়), GC লগ /var/log/lifolo/gc.log (৫ × ১০ MB), LimitNOFILE=65536
#   2) PostgreSQL — conf.d/lifolo-tuning.conf: shared_buffers 2GB, effective_cache_size 6GB, work_mem 8MB, max_connections 100
#      (shared_buffers বদলালে একবার restart — কয়েক সেকেন্ড; Hikari নিজে আবার সংযোগ নেয়)
#   3) Redis — maxmemory 512mb, maxmemory-policy volatile-lru (ফাইলে + চালু অবস্থায় CONFIG SET; restart নয়)
#   4) nginx — worker_connections 4096, worker_rlimit_nofile 16384, systemd LimitNOFILE=16384
#   5) firewall (ওপরে) + certbot-এর hook + guard timer
# জরুরি দরজা: /etc/lifolo/origin-open ফাইল থাকলে (touch) firewall 80/443 সবার জন্য খুলে দেয় (Cloudflare ছাড়া চালাতে হলে)।
# ফল: /var/lib/lifolo/tune-status (key=value, গোপন কিছু নয়)।
set -euo pipefail

SELF=/usr/local/sbin/lifolo-tune
STATUS=/var/lib/lifolo/tune-status
API_UNIT=lifolo-api.service
API_DROPIN=/etc/systemd/system/lifolo-api.service.d/20-tuning.conf
API_JAR=/opt/lifolo/api/app.jar
PG_MAJOR=16
PG_TUNING=/etc/postgresql/$PG_MAJOR/main/conf.d/lifolo-tuning.conf
REDIS_TUNING=/etc/redis/lifolo-tuning.conf
NGINX_CONF=/etc/nginx/nginx.conf
NGINX_DROPIN=/etc/systemd/system/nginx.service.d/lifolo-limits.conf
REALIP_CONF=/etc/nginx/conf.d/lifolo-cloudflare-realip.conf
EXEMPT=/etc/nginx/lifolo/limit-exempt.conf
CF_LIST=/etc/lifolo/cloudflare-ips.txt
OPEN_FLAG=/etc/lifolo/origin-open
ACME_MARK=/var/lib/lifolo/acme-open
CF_COMMENT=lifolo-cloudflare

JAVA_OPTS="-Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/var/log/lifolo/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"

# Cloudflare-এর তালিকা আনা না গেলে (https://www.cloudflare.com/ips/, ২০২৬-১০ দেখা)
PINNED_V4="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22"
PINNED_V6="2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32"

say() { printf '\n==> %s\n' "$*"; }
st() { install -d -m 0755 "$(dirname "$STATUS")"; printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\n\r' | cut -c1-300)" >> "$STATUS"; }
die() { printf 'XX %s\n' "$*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "root হিসেবে চালান"

# ফাইলের বিষয়বস্তু আলাদা হলে তবেই লেখা (stdin থেকে)। বদলালে 0, একই থাকলে 1।
write_if_changed() {
  local path="$1" mode="$2" owner="${3:-root:root}" tmp
  tmp="$(mktemp)"
  cat > "$tmp"
  if [ -f "$path" ] && cmp -s "$tmp" "$path"; then rm -f "$tmp"; return 1; fi
  install -d -m 0755 "$(dirname "$path")"
  install -m "$mode" -o "${owner%%:*}" -g "${owner##*:}" "$tmp" "$path"
  rm -f "$tmp"
  return 0
}

nginx_reload() { if nginx -t 2>/dev/null; then systemctl reload nginx 2>/dev/null || true; return 0; fi; nginx -t || true; return 1; }

redis_pass() { sed -nE 's/^LIFOLO_REDIS_PASSWORD=(.*)$/\1/p' /etc/lifolo/env 2>/dev/null | tail -1; }
rcli() { REDISCLI_AUTH="$(redis_pass)" redis-cli -h 127.0.0.1 "$@"; }
psqlq() { sudo -u postgres psql -X -q -At "$@"; }

ufw_v6() { grep -qiE '^IPV6=yes' /etc/default/ufw 2>/dev/null; }
# পুরো লেখা আগে ধরে তারপর খোঁজা (grep -q আগেভাগে থামলে pipefail-এ ভুল "না" হতো)
ufw_rules() { local r; r="$(ufw show added 2>/dev/null || true)"; printf '%s\n' "$r" | sed -n 's/^ufw //p'; }
ufw_active() { local s; s="$(ufw status 2>/dev/null || true)"; [[ "$s" == "Status: active"* ]]; }
has_rule() { local r; r="$(ufw_rules)"; grep -q "$@" <<<"$r"; }

# ------------------------------------------------------------------------------------------------ Cloudflare-এর ঠিকানা
valid_nets() { # stdin: এক লাইনে একটা CIDR → শুধু বৈধগুলো (v4 বা v6, $1)
  python3 -c '
import ipaddress, sys
want = sys.argv[1]
for l in sys.stdin.read().split():
    try:
        n = ipaddress.ip_network(l.strip(), strict=True)
    except ValueError:
        continue
    if (want == "4" and n.version == 4 and n.prefixlen >= 8) or (want == "6" and n.version == 6 and n.prefixlen >= 16):
        print(n)
' "$1"
}

cf_fetch() {
  local v4 v6 src=fetched
  v4="$(curl -fsS -m 10 https://www.cloudflare.com/ips-v4 2>/dev/null | valid_nets 4 || true)"
  v6="$(curl -fsS -m 10 https://www.cloudflare.com/ips-v6 2>/dev/null | valid_nets 6 || true)"
  if [ "$(printf '%s\n' "$v4" | grep -c .)" -lt 10 ] || [ "$(printf '%s\n' "$v6" | grep -c .)" -lt 3 ]; then
    # shellcheck disable=SC2086  # ফাঁকা দিয়ে আলাদা তালিকা
    v4="$(printf '%s\n' $PINNED_V4)"; v6="$(printf '%s\n' $PINNED_V6)"; src=pinned
  fi
  {
    echo "# Lifolo — lifolo-tune লেখে: Cloudflare-এর ঠিকানা ($src, $(date -u '+%Y-%m-%d %H:%M UTC'))"
    printf '%s\n' "$v4" "$v6" | grep .
  } | write_if_changed "$CF_LIST" 0644 || true
  touch "$CF_LIST"
  echo "$src"
}
cf_nets() { grep -vE '^\s*(#|$)' "$CF_LIST" 2>/dev/null || true; }

# ------------------------------------------------------------------------------------------------ firewall
do_firewall() {
  local src nets n rules spec changed=no v6=no count=0
  say "Cloudflare-এর ঠিকানা → nginx realip + ufw"
  src="$(cf_fetch)"
  nets="$(cf_nets)"
  [ "$(printf '%s\n' "$nets" | grep -c .)" -ge 13 ] || die "Cloudflare-এর তালিকা ছোট ($CF_LIST) — কিছু বদলানো হলো না"
  st cf_ranges "$src ($(printf '%s\n' "$nets" | grep -c .) nets)"

  # nginx: আসল দর্শকের IP (CF-Connecting-IP — শুধু Cloudflare-এর ঠিকানা থেকে এলে মানা হয়)
  if {
    echo "# Lifolo — managed by tune-vps.sh (lifolo-tune firewall): Cloudflare-এর proxy দিয়ে এলে \$remote_addr = আসল দর্শক।"
    echo "# অন্য কোনো ঠিকানা থেকে CF-Connecting-IP এলে মানা হয় না। deploy-web.sh এই ফাইল আর লেখে না।"
    for n in $nets; do echo "set_real_ip_from $n;"; done
    echo "real_ip_header CF-Connecting-IP;"
  } | write_if_changed "$REALIP_CONF" 0644; then
    nginx_reload || die "nginx realip সেটিং ভুল"
  fi
  st realip ok

  # ufw
  if ! ufw_active; then
    st firewall "ufw inactive — lockdown not applied"
    echo "ufw চালু নেই — কিছু বদলানো হলো না"
    return 0
  fi
  ufw_v6 && v6=yes
  # SSH সবসময় খোলা (নিজেকে আটকে না ফেলতে)
  has_rule -xF 'allow 22/tcp' || ufw allow 22/tcp >/dev/null
  rules="$(ufw_rules)"
  if [ -f "$OPEN_FLAG" ]; then
    # জরুরি দরজা: সবার জন্য খোলা
    for spec in 80/tcp 443/tcp; do grep -qxF "allow $spec" <<<"$rules" || ufw allow "$spec" >/dev/null; done
    st firewall "open to everyone ($OPEN_FLAG)"
    echo "$OPEN_FLAG আছে — 80/443 সবার জন্য খোলা"
    return 0
  fi
  # ১) Cloudflare-এর প্রতিটা ঠিকানা (আগে যোগ — খোলা নিয়ম মোছার আগে, যাতে মাঝে সাইট বন্ধ না হয়)
  for n in $nets; do
    case "$n" in *:*) [ "$v6" = yes ] || continue ;; esac
    count=$((count + 1))
    grep -qxF "allow from $n to any port 80,443 proto tcp comment '$CF_COMMENT'" <<<"$rules" && continue
    case "$n" in
      # IPv6-এ সমস্যা হলে (কার্নেলে ip6tables নেই) v4 তবু চলে
      *:*) ufw allow proto tcp from "$n" to any port 80,443 comment "$CF_COMMENT" >/dev/null 2>&1 || { v6=failed; count=$((count - 1)); continue; } ;;
      *) ufw allow proto tcp from "$n" to any port 80,443 comment "$CF_COMMENT" >/dev/null ;;
    esac
    changed=yes
  done
  # ২) পুরোনো (তালিকায় আর নেই) Cloudflare-নিয়ম মোছা
  rules="$(ufw_rules)"
  while read -r n; do
    [ -n "$n" ] || continue
    grep -qxF "$n" <<<"$nets" && continue
    ufw delete allow proto tcp from "$n" to any port 80,443 >/dev/null && changed=yes
  done < <(printf '%s\n' "$rules" | sed -nE "s/^allow from ([0-9a-f.:/]+) to any port 80,443 proto tcp comment '$CF_COMMENT'$/\1/p")
  # ৩) সবার-জন্য-খোলা 80/443 বন্ধ (comment-ছাড়া; certbot-এর সাময়িক 'lifolo-acme-temp' থাকে — acme-close মোছে)
  for spec in 80/tcp 443/tcp 80 443 80,443/tcp "'Nginx Full'" "'Nginx HTTP'" "'Nginx HTTPS'"; do
    if grep -qxF "allow $spec" <<<"$rules"; then
      eval "ufw delete allow $spec" >/dev/null && changed=yes
    fi
  done
  st firewall "cloudflare-only ($count rules, v6=$v6; changed=$changed)"
  echo "80/443 শুধু Cloudflare থেকে ($count নিয়ম), 22 সবার জন্য; বদল: $changed"
}

# ------------------------------------------------------------------------------------------------ CI-র সাময়িক ছিদ্র
exempt_init() {
  install -d -m 0755 "$(dirname "$EXEMPT")"
  [ -f "$EXEMPT" ] || { echo "# Lifolo — CI-র সাময়িক ঠিকানা (lifolo-tune ci-open লেখে, guard মোছে)। nginx-এর geo-তে include।" > "$EXEMPT"; chmod 0644 "$EXEMPT"; }
}
valid_ip() { python3 -c 'import ipaddress,sys; a=ipaddress.ip_address(sys.argv[1]); sys.exit(0 if a.is_global else 1)' "$1" 2>/dev/null; }

do_ci_open() {
  local ip="${1:-}" min="${2:-60}" until
  valid_ip "$ip" || die "ঠিকানা ঠিক নেই: $ip"
  [[ "$min" =~ ^[0-9]+$ ]] && [ "$min" -ge 1 ] && [ "$min" -le 240 ] || die "মিনিট 1-240"
  until=$(( $(date +%s) + min * 60 ))
  do_ci_close "$ip" quiet
  if [ ! -f "$OPEN_FLAG" ] && ufw_active; then
    ufw allow proto tcp from "$ip" to any port 80,443 comment "lifolo-ci-until-$until" >/dev/null
  fi
  exempt_init
  printf '%s 1; # lifolo-ci-until-%s\n' "$ip" "$until" >> "$EXEMPT"
  nginx_reload >/dev/null || true
  echo "ci_open=ok until $(date -u -d "@$until" '+%H:%M UTC')"
}

do_ci_close() {
  local ip="${1:-}" quiet="${2:-}"
  valid_ip "$ip" || die "ঠিকানা ঠিক নেই: $ip"
  if has_rule -E "^allow from ${ip//./\\.} to any port 80,443 proto tcp comment 'lifolo-ci-until-"; then
    ufw delete allow proto tcp from "$ip" to any port 80,443 >/dev/null || true
  fi
  if [ -f "$EXEMPT" ] && grep -qE "^${ip//./\\.} 1;" "$EXEMPT"; then
    sed -i -E "/^${ip//./\\.} 1;/d" "$EXEMPT"
    nginx_reload >/dev/null || true
  fi
  [ -n "$quiet" ] || echo "ci_close=ok"
}

do_guard() {
  local now ip until reload=no
  now=$(date +%s)
  # মেয়াদ-পেরোনো CI-ছিদ্র
  while read -r ip until; do
    [ -n "$ip" ] || continue
    [ "$until" -le "$now" ] || continue
    ufw delete allow proto tcp from "$ip" to any port 80,443 >/dev/null 2>&1 || true
    echo "expired ufw: $ip"
  done < <(ufw_rules | sed -nE "s/^allow from ([0-9a-f.:]+) to any port 80,443 proto tcp comment 'lifolo-ci-until-([0-9]+)'$/\1 \2/p")
  if [ -f "$EXEMPT" ]; then
    while read -r ip until; do
      [ -n "$ip" ] || continue
      [ "$until" -le "$now" ] || continue
      sed -i -E "/^${ip//./\\.} 1; # lifolo-ci-until-${until}\$/d" "$EXEMPT"
      reload=yes
      echo "expired exempt: $ip"
    done < <(sed -nE 's/^([0-9a-f.:]+) 1; # lifolo-ci-until-([0-9]+)$/\1 \2/p' "$EXEMPT")
  fi
  [ "$reload" = no ] || nginx_reload >/dev/null || true
  # certbot-এর সাময়িক 80 দুই ঘণ্টার বেশি খোলা থাকলে (post-hook চলেনি) বন্ধ
  if [ -f "$ACME_MARK" ] && [ $(( now - $(stat -c %Y "$ACME_MARK") )) -gt 7200 ]; then do_acme_close; fi
  # load test-এর সাময়িক drop-in (অ্যাপের অ্যাকাউন্ট-সীমা তোলা) ২ ঘণ্টার বেশি থাকলে মোছা আর অ্যাপ restart
  local lt=/etc/systemd/system/lifolo-api.service.d/95-loadtest.conf
  if [ -f "$lt" ] && [ $(( now - $(stat -c %Y "$lt") )) -gt 7200 ]; then
    rm -f "$lt"
    systemctl daemon-reload
    date +%s >> /var/lib/lifolo/ops-expected
    systemctl restart "$API_UNIT" || true
    echo "removed stale $lt"
  fi
  # Cloudflare-এর তালিকা সপ্তাহে একবার নতুন করে
  if [ ! -f "$CF_LIST" ] || [ $(( now - $(stat -c %Y "$CF_LIST") )) -gt 604800 ]; then : > "$STATUS.guard"; STATUS="$STATUS.guard" do_firewall; fi
}

do_acme_open() {
  [ ! -f "$OPEN_FLAG" ] || return 0
  ufw_active || return 0
  has_rule -xF 'allow 80/tcp' && return 0
  install -d -m 0755 "$(dirname "$ACME_MARK")"
  touch "$ACME_MARK"
  ufw allow 80/tcp comment 'lifolo-acme-temp' >/dev/null
}
do_acme_close() {
  [ -f "$ACME_MARK" ] || return 0
  if has_rule -xF "allow 80/tcp comment 'lifolo-acme-temp'"; then ufw delete allow 80/tcp >/dev/null || true; fi
  rm -f "$ACME_MARK"
}

# ------------------------------------------------------------------------------------------------ apply
do_apply() {
  local restart_app=yes a
  for a in "$@"; do
    case "$a" in
      --no-app-restart) restart_app=no ;;
      --ci) ;;
      *) die "অচেনা আর্গুমেন্ট: $a" ;;
    esac
  done
  install -d -m 0755 /var/lib/lifolo
  : > "$STATUS"
  chmod 0644 "$STATUS"
  # নিজেকে বসানো (timer, certbot hook আর CI এটাই ডাকে)
  if [ "$(readlink -f "$0")" != "$SELF" ]; then install -m 0755 "$0" "$SELF"; fi

  # ---- 1) JVM
  say "JVM: $JAVA_OPTS"
  # GC লগের ফোল্ডার lifolo-র লেখার মতো না হলে -Xlog বাদ (নইলে JVM চালুই হয় না)
  local opts="$JAVA_OPTS" jvm_changed=no
  if id lifolo >/dev/null 2>&1 && install -d -o lifolo -g lifolo -m 0750 /var/log/lifolo; then :; else opts="${JAVA_OPTS%% -Xlog:*}"; fi
  if {
    echo "# Lifolo — tune-vps.sh লেখে (Deploy backend / Setup VPS প্রতিবার)। setup-base.sh-এর ExecStart-এর বদলে এটা চলে।"
    echo "[Service]"
    echo "LimitNOFILE=65536"
    echo "ExecStart="
    echo "ExecStart=/usr/bin/java $opts -jar $API_JAR"
  } | write_if_changed "$API_DROPIN" 0644; then
    jvm_changed=yes
    systemctl daemon-reload
  fi
  if [ "$jvm_changed" = yes ] && [ "$restart_app" = yes ] && systemctl is-active --quiet "$API_UNIT"; then
    systemctl restart "$API_UNIT"
    for _ in $(seq 1 60); do curl -fsS -m 3 http://127.0.0.1:8080/api/health 2>/dev/null | grep -q '"ok":true' && break; sleep 2; done
  fi
  st jvm "$([ "$jvm_changed" = yes ] && echo changed || echo same)$([ "$jvm_changed" = yes ] && [ "$restart_app" = no ] && echo ' (deploy restarts the app)')"

  # ---- 2) PostgreSQL
  say "PostgreSQL: shared_buffers 2GB, effective_cache_size 6GB, work_mem 8MB, max_connections 100"
  if [ -d "/etc/postgresql/$PG_MAJOR/main/conf.d" ]; then
    if {
      echo "# Lifolo — tune-vps.sh লেখে। ১২ GB RAM-এর VPS; ডাটাবেস ছোট, তাই এখন বেশিরভাগই পরের বৃদ্ধির জন্য।"
      echo "shared_buffers = 2GB"
      echo "effective_cache_size = 6GB"
      echo "work_mem = 8MB"
      echo "max_connections = 100"
    } | write_if_changed "$PG_TUNING" 0644 postgres:postgres; then
      systemctl reload postgresql 2>/dev/null || true
    fi
    sleep 1
    if [ "$(psqlq -c "SELECT count(*) FROM pg_settings WHERE pending_restart" 2>/dev/null || echo 0)" != 0 ]; then
      say "PostgreSQL restart (shared_buffers)"
      systemctl restart postgresql || true
      for _ in $(seq 1 30); do pg_isready -q && break; sleep 1; done
      if pg_isready -q; then
        st postgres restarted
      else
        # নতুন সেটিং-এ চালু হলো না (যেমন RAM কম) — সরিয়ে আগের মতো
        mv "$PG_TUNING" "$PG_TUNING.failed"
        systemctl restart postgresql || true
        for _ in $(seq 1 30); do pg_isready -q && break; sleep 1; done
        st postgres "failed to start with the new settings — reverted ($PG_TUNING.failed)"
      fi
    else
      st postgres ok
    fi
    st pg_settings "$(psqlq -c "SELECT string_agg(name || '=' || current_setting(name), ' ' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers','effective_cache_size','work_mem','max_connections')" 2>/dev/null || echo '?')"
  else
    st postgres "skipped (no cluster)"
  fi

  # ---- 3) Redis
  say "Redis: maxmemory 512mb, volatile-lru"
  if [ -f /etc/redis/redis.conf ]; then
    {
      echo "# Lifolo — tune-vps.sh লেখে (redis.conf-এর শেষে include)"
      echo "maxmemory 512mb"
      echo "maxmemory-policy volatile-lru"
    } | write_if_changed "$REDIS_TUNING" 0640 root:redis || true
    grep -qxF "include $REDIS_TUNING" /etc/redis/redis.conf || printf '\ninclude %s\n' "$REDIS_TUNING" >> /etc/redis/redis.conf
    if [ -n "$(redis_pass)" ] && rcli ping >/dev/null 2>&1; then
      rcli config set maxmemory 512mb >/dev/null
      rcli config set maxmemory-policy volatile-lru >/dev/null
      st redis "$(rcli config get maxmemory | tail -1) $(rcli config get maxmemory-policy | tail -1)"
    else
      st redis "file only (redis not answering)"
    fi
  else
    st redis "skipped (not installed)"
  fi

  # ---- 4) nginx
  say "nginx: worker_connections 4096, worker_rlimit_nofile 16384, LimitNOFILE 16384"
  if [ -f "$NGINX_CONF" ]; then
    cp -p "$NGINX_CONF" "$NGINX_CONF.lifolo-bak"
    sed -i -E 's/^(\s*)worker_connections\s+[0-9]+;/\1worker_connections 4096;/' "$NGINX_CONF"
    if grep -qE '^\s*worker_rlimit_nofile\s' "$NGINX_CONF"; then
      sed -i -E 's/^(\s*)worker_rlimit_nofile\s+[0-9]+;/\1worker_rlimit_nofile 16384;/' "$NGINX_CONF"
    elif grep -qE '^worker_processes\s' "$NGINX_CONF"; then
      sed -i -E '0,/^worker_processes\s.*$/s//&\nworker_rlimit_nofile 16384;/' "$NGINX_CONF"
    else
      sed -i '1i worker_rlimit_nofile 16384;' "$NGINX_CONF"
    fi
    local ngx_conf_changed=no ngx_unit_changed=no
    cmp -s "$NGINX_CONF" "$NGINX_CONF.lifolo-bak" || ngx_conf_changed=yes
    if printf '[Service]\nLimitNOFILE=16384\n' | write_if_changed "$NGINX_DROPIN" 0644; then
      ngx_unit_changed=yes
      systemctl daemon-reload
    fi
    exempt_init
    if ! nginx -t 2>/dev/null; then
      nginx -t || true
      cp -p "$NGINX_CONF.lifolo-bak" "$NGINX_CONF"
      st nginx "failed (nginx.conf restored)"
    else
      # LimitNOFILE শুধু নতুন প্রসেসে — একবার restart (এক সেকেন্ডের কম); নইলে শুধু reload
      if [ "$ngx_unit_changed" = yes ] && systemctl is-active --quiet nginx; then
        systemctl restart nginx
      elif [ "$ngx_conf_changed" = yes ]; then
        systemctl reload nginx
      fi
      st nginx "ok (conf=$ngx_conf_changed unit=$ngx_unit_changed)"
    fi
    rm -f "$NGINX_CONF.lifolo-bak"
  fi

  # ---- 5) firewall + certbot hook + guard timer
  do_firewall
  install -d -m 0755 /etc/letsencrypt/renewal-hooks/pre /etc/letsencrypt/renewal-hooks/post
  printf '#!/bin/sh\n# Lifolo (tune-vps.sh): নবায়নের সময়টুকু পোর্ট 80 সবার জন্য (Let'"'"'s Encrypt-এর HTTP-01)\nexec %s acme-open\n' "$SELF" \
    | write_if_changed /etc/letsencrypt/renewal-hooks/pre/lifolo-acme-open 0755 || true
  printf '#!/bin/sh\n# Lifolo (tune-vps.sh): নবায়নের পরে পোর্ট 80 আবার শুধু Cloudflare\nexec %s acme-close\n' "$SELF" \
    | write_if_changed /etc/letsencrypt/renewal-hooks/post/lifolo-acme-close 0755 || true
  local t_changed=no
  printf '[Unit]\nDescription=Lifolo origin guard (expire CI firewall holes, refresh Cloudflare ranges weekly)\n\n[Service]\nType=oneshot\nExecStart=%s guard\n' "$SELF" \
    | write_if_changed /etc/systemd/system/lifolo-origin-guard.service 0644 && t_changed=yes
  printf '[Unit]\nDescription=Lifolo origin guard every 15 minutes\n\n[Timer]\nOnCalendar=*:0/15\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n' \
    | write_if_changed /etc/systemd/system/lifolo-origin-guard.timer 0644 && t_changed=yes
  [ "$t_changed" = no ] || systemctl daemon-reload
  systemctl enable --now lifolo-origin-guard.timer >/dev/null 2>&1 || true
  st guard_timer "$(systemctl is-active lifolo-origin-guard.timer 2>/dev/null || true)"
  st finished yes
  echo
  cat "$STATUS"
}

do_status() {
  local pid
  pid="$(systemctl show -p MainPID --value "$API_UNIT" 2>/dev/null || echo 0)"
  echo "jvm_dropin=$([ -f "$API_DROPIN" ] && echo yes || echo no)"
  [ "${pid:-0}" = 0 ] || echo "api_cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | sed -E 's#-jar [^ ]+#-jar <jar>#' | cut -c1-300)"
  echo "pg=$(psqlq -c "SELECT string_agg(name || '=' || current_setting(name), ' ' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers','effective_cache_size','work_mem','max_connections')" 2>/dev/null || echo '?')"
  echo "redis=$(rcli config get maxmemory 2>/dev/null | tail -1) $(rcli config get maxmemory-policy 2>/dev/null | tail -1)"
  echo "nginx_worker_connections=$(nginx -T 2>/dev/null | sed -nE 's/^\s*worker_connections\s+([0-9]+);.*/\1/p' | head -1)"
  echo "nginx_nofile=$(awk '/Max open files/{print $4}' "/proc/$(pgrep -o nginx)/limits" 2>/dev/null || echo '?')"
  echo "ufw=$(ufw status 2>/dev/null | head -1 | sed 's/^Status: //')"
  echo "ufw_cloudflare_rules=$(ufw_rules | grep -c "comment '$CF_COMMENT'" || true)"
  echo "ufw_open_80_443=$(ufw_rules | grep -cxE "allow (80|443)(/tcp)?|allow 80,443/tcp|allow 'Nginx (Full|HTTP|HTTPS)'" || true)"
  echo "ufw_ci_holes=$(ufw_rules | grep -c "comment 'lifolo-ci-until-" || true)"
  echo "origin_open_flag=$([ -f "$OPEN_FLAG" ] && echo yes || echo no)"
  echo "cf_list_age_h=$([ -f "$CF_LIST" ] && echo $(( ( $(date +%s) - $(stat -c %Y "$CF_LIST") ) / 3600 )) || echo none)"
}

CMD="${1:-apply}"
case "$CMD" in
  apply) shift || true; do_apply "$@" ;;
  --no-app-restart|--ci) do_apply "$@" ;;
  firewall) : > "$STATUS.firewall"; STATUS="$STATUS.firewall" do_firewall ;;
  guard) do_guard ;;
  ci-open) do_ci_open "${2:-}" "${3:-60}" ;;
  ci-close) do_ci_close "${2:-}" ;;
  acme-open) do_acme_open ;;
  acme-close) do_acme_close ;;
  status) do_status ;;
  *) die "ব্যবহার: tune-vps.sh [apply [--no-app-restart]] | firewall | guard | ci-open IP MIN | ci-close IP | acme-open | acme-close | status" ;;
esac
