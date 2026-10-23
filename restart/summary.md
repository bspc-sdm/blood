## ⚠️ VPS রিবুট মহড়া — সার্ভার রিবুট করা হয়েছিল

**রিবুট:** 2026-10-20 05:27:36 UTC · নতুন boot: হ্যাঁ (আগে চালু ছিল 2026-10-19 10:54:40 থেকে)

| মাপ | সময় (রিবুট কমান্ড থেকে) |
|---|---|
| সাইট বন্ধ হলো | 11.4 s |
| SSH (পোর্ট 22) ফিরল | 12.5 s |
| **https /api/health সবুজ (সরাসরি VPS)** | **46.0 s** |
| Cloudflare দিয়ে (lifolo.com/api/health) | ok 46.4 |

বুট: 4.115s (kernel) + 8.626s (userspace) = 12.741s  · অ্যাপ: Started LifoloApplicationKt in 11.931 seconds · health (ভেতর থেকে): ok, PostgreSQL: ok, Redis: PONG, swap: /swapfile , ফায়ারওয়াল: Status: active

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

ব্যর্থ unit: cloud-init.service · রিবুট দরকার (আপডেটের পর): no · unattended-upgrades নিজে রিবুট করে: না (ডিফল্ট false)

**ফল:** ❌ কিছু ফেরেনি — ওপরের সারণি দেখুন

<details><summary>বুটের বিস্তারিত</summary>

```
2026-10-20T05:28:21+00:00 vmi3678618 java[1047]: 2026-10-20T05:28:21.157Z  INFO 1047 --- [lifolo-backend] [           main] com.lifolo.LifoloApplicationKt           : Started LifoloApplicationKt in 11.931 seconds (process running for 13.114)

2.572s postgresql@16-main.service
1.776s systemd-networkd-wait-online.service
1.239s cloud-init-local.service
 744ms cloud-init.service
 727ms dev-sda1.device
 564ms snapd.seeded.service
 553ms snapd.service
 540ms apparmor.service

NEXT                                 LEFT LAST                        PASSED UNIT                           ACTIVATES
Tue 2026-10-20 05:30:00 UTC           17s -                                - sysstat-collect.timer          sysstat-collect.service
Tue 2026-10-20 05:32:59 UTC      3min 17s -                                - update-notifier-download.timer update-notifier-download.service
Tue 2026-10-20 05:42:55 UTC         13min -                                - systemd-tmpfiles-clean.timer   systemd-tmpfiles-clean.service
Tue 2026-10-20 06:14:56 UTC         45min Mon 2026-10-19 10:54:51 UTC      - apt-daily-upgrade.timer        apt-daily-upgrade.service
Tue 2026-10-20 06:47:12 UTC      1h 17min Tue 2026-10-20 05:21:56 UTC      - fwupd-refresh.timer            fwupd-refresh.service
Tue 2026-10-20 10:24:47 UTC      4h 55min Tue 2026-10-20 00:20:16 UTC      - apt-daily.timer                apt-daily.service
Tue 2026-10-20 13:38:35 UTC            8h Tue 2026-10-20 03:20:26 UTC      - motd-news.timer                motd-news.service
Tue 2026-10-20 21:32:49 UTC           16h Mon 2026-10-19 21:32:32 UTC      - lifolo-backup.timer            lifolo-backup.service
Tue 2026-10-20 22:21:12 UTC           16h Tue 2026-10-20 00:58:15 UTC      - certbot.timer                  certbot.service
Wed 2026-10-21 00:00:00 UTC           18h Tue 2026-10-20 00:00:22 UTC      - dpkg-db-backup.timer           dpkg-db-backup.service
Wed 2026-10-21 00:00:00 UTC           18h Tue 2026-10-20 00:00:22 UTC      - logrotate.timer                logrotate.service
Wed 2026-10-21 00:07:00 UTC           18h -                                - sysstat-summary.timer          sysstat-summary.service
Wed 2026-10-21 02:20:20 UTC           20h Tue 2026-10-20 00:32:28 UTC      - man-db.timer                   man-db.service
```
</details>

_run 39374988172 · গাইড: deploy/OPS-DRILLS.md_
