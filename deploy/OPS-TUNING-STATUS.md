# Ops tuning — status (2026-10-20)

Merged to main (fast-forward) — see `deploy/OPS-DRILLS.md` ("টিউনিং" section) for the full Bangla notes.

## Applied on the VPS (by every Deploy backend / Setup VPS; idempotent)
- JVM `-Xms1g -Xmx3g` G1 + GC log + LimitNOFILE 65536; PostgreSQL shared_buffers 2GB / effective_cache_size 6GB /
  work_mem 8MB / max_connections 100; Redis maxmemory 512mb volatile-lru.
- nginx worker_connections 4096, worker_rlimit_nofile 16384 (+ systemd LimitNOFILE); upstream keepalive 64;
  /api/ limit 20 r/s burst 40 per real client IP (429 JSON); JSON bodies for nginx 502/503/504.
- ufw: 80/443 only from Cloudflare (v4+v6), 22 open; realip from Cloudflare; certbot hooks open :80 only while
  renewing (dry-run OK for all 3 certs); `lifolo-origin-guard.timer`.

## Results
- Load test: 300→1200 logged-in rps, 0% errors, p95 179→429 ms (650: 245 ms vs 581 before; 800: 370 ms vs
  2,241 ms / 28% errors before). Breaking point not reached (runner-limited above 1000).
- Restart drill: https health green 49 s after reboot; JSON 502 while Spring starts; all settings persisted.
- Uptime (every 3 h) now includes VPS health alerts — green on the branch.
- Backup check: R2 off-site copies older than 90 days are deleted weekly (newest 4 always kept); first run
  listed 2 copies, deleted 0 (nothing old yet — the delete call itself is not exercised until a copy turns 90 days).

## Remaining
- Guest-limit re-check after the backend fix on spring-p10 lands (load test showed 300×200 once).
- Other branches' old deploy-backend.sh don't write the deploy marker → their crash-loops alert until rebased.
- Optional: load test at 1200–1800 rps to find the real limit.
