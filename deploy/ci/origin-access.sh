#!/usr/bin/env bash
# GitHub-এর মেশিন থেকে VPS-এর 80/443-এ পৌঁছানো। origin শুধু Cloudflare-এর জন্য খোলা (deploy/tune-vps.sh), তাই সরাসরি-VPS
# পরীক্ষাগুলো (--resolve <নাম>:443:<IP>, /etc/hosts) এভাবে চলে। আগে deploy/ci/vps-connect.sh (`ssh vps`) চালাতে হবে।
#
#   origin-access.sh tunnel <ip> [<নাম>]
#       SSH tunnel: এই মেশিন থেকে <ip>:443 আর <ip>:80-এ যাওয়া সব সংযোগ ssh দিয়ে VPS-এর 127.0.0.1:443/80-এ (iptables DNAT) —
#       --resolve, /etc/hosts, k6 যেমন ছিল তেমনই চলে, সত্যিকারের নাম আর সার্টিফিকেট দিয়েই। VPS-এর nginx দেখে 127.0.0.1
#       (IP-সীমার বাইরে)। <নাম> (ডিফল্ট <ip-ড্যাশ>.sslip.io) দিয়ে /healthz খুলে যাচাই।
#   origin-access.sh open <মিনিট>
#       সাময়িক ছিদ্র: এই মেশিনের ঠিকানা → VPS-এর ufw-এ 80/443 খোলা আর nginx-এর IP-সীমার বাইরে (lifolo-tune ci-open);
#       <মিনিট> পরে VPS নিজেই বন্ধ করে (lifolo-origin-guard.timer) — workflow মাঝপথে মরলেও। শুধু যেখানে tunnel চলে না:
#       লোড টেস্ট (ssh-ই বোতলের গলা হয়ে মাপ নষ্ট করত) আর রিবুট মহড়া (রিবুটে ssh ছিঁড়ে যায়)।
#   origin-access.sh close
#       ছিদ্র এখনই বন্ধ (workflow-এর always() ধাপে)।
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
STATE="${RUNNER_TEMP:-/tmp}/lifolo-origin-ip"

case "${1:-}" in
tunnel)
  ip="${2:-}"
  [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || die "tunnel: IP নেই"
  name="${3:-${ip//./-}.sslip.io}"
  ssh -O check vps >/dev/null 2>&1 || ssh vps true
  ssh -O forward -L 127.0.0.1:18443:127.0.0.1:443 -L 127.0.0.1:18080:127.0.0.1:80 vps
  # নিজের মেশিনের ভেতরে 127.0.0.1-এ পাঠানো (route_localnet ছাড়া কার্নেল এমন প্যাকেট ফেলে দেয়)
  sudo sysctl -qw net.ipv4.conf.all.route_localnet=1
  for p in 443:18443 80:18080; do
    sudo iptables -t nat -C OUTPUT -p tcp -d "$ip" --dport "${p%%:*}" -j DNAT --to-destination "127.0.0.1:${p##*:}" 2>/dev/null \
      || sudo iptables -t nat -A OUTPUT -p tcp -d "$ip" --dport "${p%%:*}" -j DNAT --to-destination "127.0.0.1:${p##*:}"
  done
  for _ in 1 2 3 4 5; do
    if curl -fsS -m 10 --resolve "$name:443:$ip" "https://$name/healthz" 2>/dev/null | grep -q '"ok":true'; then
      echo "origin tunnel ready (443/80 → ssh → VPS 127.0.0.1)"
      exit 0
    fi
    sleep 2
  done
  die "origin tunnel: https://<vps>/healthz সাড়া দিচ্ছে না"
  ;;
open)
  min="${2:-60}"
  [[ "$min" =~ ^[0-9]+$ ]] || die "open: মিনিট সংখ্যায়"
  # VPS যে ঠিকানা থেকে এই ssh দেখে = এই মেশিনের বাইরের ঠিকানা
  # shellcheck disable=SC2016  # $SSH_CONNECTION VPS-এ খোলে
  me="$(ssh vps 'set -- $SSH_CONNECTION; echo "$1"')"
  [[ "$me" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || die "এই মেশিনের ঠিকানা পাওয়া গেল না"
  echo "::add-mask::$me"
  printf '%s\n' "$me" > "$STATE"
  # shellcheck disable=SC2029  # মানগুলো ওপরে যাচাই করা
  ssh vps "if [ -x /usr/local/sbin/lifolo-tune ]; then /usr/local/sbin/lifolo-tune ci-open '$me' '$min'; else echo 'lifolo-tune নেই — origin এখনো সবার জন্য খোলা'; fi"
  ;;
close)
  [ -f "$STATE" ] || { echo "খোলা ছিদ্র নেই"; exit 0; }
  me="$(cat "$STATE")"
  # shellcheck disable=SC2029
  ssh vps "[ ! -x /usr/local/sbin/lifolo-tune ] || /usr/local/sbin/lifolo-tune ci-close '$me'" || echo "::warning::ছিদ্র বন্ধ করা গেল না — VPS নিজে মেয়াদ শেষে বন্ধ করবে"
  rm -f "$STATE"
  ;;
*)
  die "ব্যবহার: origin-access.sh tunnel <ip> [<নাম>] | open <মিনিট> | close"
  ;;
esac
