## ⚠️ VPS রিবুট মহড়া — সার্ভার রিবুট করা হয়েছিল

**রিবুট:** 2026-10-20 09:58:04 UTC · নতুন boot: হ্যাঁ (আগে চালু ছিল 2026-10-20 05:27:55 থেকে)

| মাপ | সময় (রিবুট কমান্ড থেকে) |
|---|---|
| সাইট বন্ধ হলো | 11.7 s |
| SSH (পোর্ট 22) ফিরল | 30.4 s |
| **https /api/health সবুজ (সরাসরি VPS)** | **49.4 s** |
| Cloudflare দিয়ে (lifolo.com/api/health) | ok 49.9 |
| Spring চালুর আগে nginx-এর উত্তর | JSON (502) |

বুট: 4.293s (kernel) + 8.814s (userspace) = 13.107s  · অ্যাপ: Started LifoloApplicationKt in 13.66 seconds · health (ভেতর থেকে): ok, PostgreSQL: ok, Redis: PONG, swap: /swapfile , ফায়ারওয়াল: Status: active

| সার্ভিস | আগে (active/enabled) | রিবুটের পরে | নিজে চালু? |
|---|---|---|---|
| lifolo-api | active/enabled | active/enabled | ✅ |
| nginx | active/enabled | active/enabled | ✅ |
| postgresql | active/enabled | active/enabled | ✅ |
| postgresql-16-main | active/enabled-runtime | active/enabled-runtime | ✅ |
| redis-server | active/enabled | active/enabled | ✅ |
| ssh | active/disabled | active/disabled | ✅ |
| fail2ban | active/enabled | active/enabled | ✅ |
| ufw | active/enabled | active/enabled | ✅ |
| unattended-upgrades | active/enabled | active/enabled | ✅ |
| cron | active/enabled | active/enabled | ✅ |
| lifolo-backup-timer | active/enabled | active/enabled | ✅ |
| certbot-timer | active/enabled | active/enabled | ✅ |
| logrotate-timer | active/enabled | active/enabled | ✅ |

ব্যর্থ unit (রিবুটের পরে): cloud-init.service (আগেও ব্যর্থ ছিল, Lifolo-র অংশ নয়)
cloud-init: `status: error extended_status: error - done errors: 	- ('bootcmd', ProcessExecutionError("Unexpected error while running command.\nCommand: ['/bin/sh', '/run/cloud-init/tmp/tmpx8by75gc.sh']\nExit code: 1\nReason: -\nStdout: -\nStderr: -")) `
রিবুট দরকার (আপডেটের পর): no · unattended-upgrades নিজে রিবুট করে: না (ডিফল্ট false)

| সেটিং | রিবুটের আগে | পরে |
|---|---|---|
| api_cmdline | /usr/bin/java -Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/var/log/lifolo/gc.log:time,uptime,level,tags:filecount=5,filesize=10m -jar <jar>  | /usr/bin/java -Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/var/log/lifolo/gc.log:time,uptime,level,tags:filecount=5,filesize=10m -jar <jar>  |
| pg | effective_cache_size=6GB max_connections=100 shared_buffers=2GB work_mem=8MB | effective_cache_size=6GB max_connections=100 shared_buffers=2GB work_mem=8MB |
| redis | 536870912 volatile-lru | 536870912 volatile-lru |
| nginx_worker_connections | 4096 | 4096 |
| nginx_nofile | 16384 | 16384 |
| ufw | active | active |
| ufw_cloudflare_rules | 22 | 22 |
| ufw_open_80_443 | 0 | 0 |

**ফল:** ✅ সব সার্ভিস নিজে নিজে ফিরেছে

<details><summary>বুটের বিস্তারিত</summary>

```
2026-10-20T09:58:52+00:00 vmi3678618 java[1050]: 2026-10-20T09:58:52.523Z  INFO 1050 --- [lifolo-backend] [           main] com.lifolo.LifoloApplicationKt           : Started LifoloApplicationKt in 13.66 seconds (process running for 15.259)

2.730s postgresql@16-main.service
1.844s systemd-networkd-wait-online.service
1.066s cloud-init-local.service
 761ms dev-sda1.device
 718ms cloud-init.service
 678ms snapd.seeded.service
 613ms cloud-config.service
 589ms snapd.service

NEXT                                 LEFT LAST                        PASSED UNIT                           ACTIVATES
Tue 2026-10-20 10:03:28 UTC      3min 13s -                                - update-notifier-download.timer update-notifier-download.service
Tue 2026-10-20 10:10:00 UTC          9min Tue 2026-10-20 10:00:11 UTC 3s ago sysstat-collect.timer          sysstat-collect.service
Tue 2026-10-20 10:13:24 UTC         13min -                                - systemd-tmpfiles-clean.timer   systemd-tmpfiles-clean.service
Tue 2026-10-20 10:14:46 UTC         14min Tue 2026-10-20 09:16:26 UTC      - fwupd-refresh.timer            fwupd-refresh.service
Tue 2026-10-20 10:15:00 UTC         14min Tue 2026-10-20 10:00:11 UTC 3s ago lifolo-origin-guard.timer      lifolo-origin-guard.service
Tue 2026-10-20 11:22:20 UTC      1h 22min Tue 2026-10-20 00:20:16 UTC      - apt-daily.timer                apt-daily.service
Tue 2026-10-20 15:03:15 UTC       5h 2min Tue 2026-10-20 03:20:26 UTC      - motd-news.timer                motd-news.service
Tue 2026-10-20 17:34:00 UTC            7h Tue 2026-10-20 00:58:15 UTC      - certbot.timer                  certbot.service
Tue 2026-10-20 21:32:01 UTC           11h Mon 2026-10-19 21:32:32 UTC      - lifolo-backup.timer            lifolo-backup.service
Wed 2026-10-21 00:00:00 UTC           13h Tue 2026-10-20 00:00:22 UTC      - dpkg-db-backup.timer           dpkg-db-backup.service
Wed 2026-10-21 00:00:00 UTC           13h Tue 2026-10-20 00:00:22 UTC      - logrotate.timer                logrotate.service
Wed 2026-10-21 00:07:00 UTC           14h -                                - sysstat-summary.timer          sysstat-summary.service
Wed 2026-10-21 05:19:01 UTC           19h Tue 2026-10-20 00:32:28 UTC      - man-db.timer                   man-db.service
```
</details>

_run 39404492328 · গাইড: deploy/OPS-DRILLS.md_
