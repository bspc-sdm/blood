# Ops tuning — status (2026-10-20, paused)

Branch `ops-tuning` (not merged to main yet). Details and Bangla notes: `deploy/OPS-DRILLS.md` ("টিউনিং" section).

## Applied on the VPS (via Deploy backend dispatched on `ops-tuning`, last run 07:16 UTC, green, idempotent re-run = no changes)
- JVM `-Xms1g -Xmx3g` G1 + GC log + LimitNOFILE 65536 (drop-in `lifolo-api.service.d/20-tuning.conf`).
- PostgreSQL shared_buffers 2GB, effective_cache_size 6GB, work_mem 8MB, max_connections 100 (restarted once).
- Redis maxmemory 512mb, volatile-lru (live + file).
- nginx worker_connections 4096, worker_rlimit_nofile 16384, systemd LimitNOFILE 16384; upstream keepalive 64;
  /api/ limit 20 r/s burst 40 per real client IP (429 JSON); JSON bodies for nginx 502/503/504.
- ufw: 80/443 only from Cloudflare (22 v4+v6 rules), 22 open; realip from Cloudflare; certbot pre/post hooks open :80
  only during renewal (`certbot renew --dry-run`: all 3 certs OK); `lifolo-origin-guard.timer` (CI holes expire,
  Cloudflare ranges weekly). Cloudflare → origin verified from the VPS (HTTP 200). Last public health check
  (Uptime run 07:51 UTC): lifolo.com/api/health OK via Cloudflare. No VPS change in flight.

## Applied in the repo (ops-tuning)
- `deploy/tune-vps.sh` (new), `deploy/ci/origin-access.sh` (new: SSH tunnel / temporary hole), deploy-backend.sh,
  deploy-web.sh, setup-base.sh, vps-ops.sh (`health` mode, exception causes), k6 script (one client IP per VU).
- Workflows: deploy-backend, setup-vps-base, uptime (VPS health alerts every 3 h), ops load/restart/crash/ssl,
  build, download-check.

## Results
- Load test (07:41 UTC, after tuning + backend commit 6ce3da9): 300→1200 logged-in rps, 0% errors, p95 179→429 ms
  (650 rps: 245 ms vs 581 ms before; 800: 370 ms vs 2,241 ms/28% errors before). Breaking point not reached
  (k6 runner started dropping at ≥1000 rps). CPU ≤ 82%, 9.8 GB RAM free.
- Uptime health check works; it currently fails on restarts/reboot from before the markers (05:25–05:38 UTC),
  which age out of the 3 h window by ~08:40 UTC.

## Remaining
- Restart drill with the new config (not run: Android e2e runs against production were continuously in progress).
- Merge: CI green for the final commit → rebase → fast-forward main (main deploy re-applies, no changes expected).
- Load test above 1200 rps (`rates=1200 1500 1800`) if a true limit is wanted.
- Guest-limit check returned 300×200 in the last load test (earlier 240/60 on the same backend) — re-check.
- Other branches' deploy-backend.sh (e.g. spring-p9) don't write the deploy marker → their crash-loops will alert
  until rebased.

## Next steps
1. When no e2e is hitting production: run "Ops: restart drill" (confirm=REBOOT, log_branch=ci-log-ops-tuning).
2. Run "Uptime check" on ops-tuning after 08:40 UTC → expect green.
3. Rebase on main, fast-forward main, watch Deploy backend (ci-log-deploy).
