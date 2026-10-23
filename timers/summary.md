## নির্ধারিত কাজ (timer, cron, JobRunr) — গত ২৪ ঘণ্টা

### ঘড়ি
```
clock_ntp_synced=yes
clock_ntp_service=active inactive 
clock_offset_s=0
```
### systemd timer
```
NEXT LEFT LAST PASSED UNIT ACTIVATES
Tue 2026-10-20 05:32:59 UTC 21s - - update-notifier-download.timer update-notifier-download.service
Tue 2026-10-20 05:40:00 UTC 7min Tue 2026-10-20 05:30:09 UTC 2min 29s ago sysstat-collect.timer sysstat-collect.service
Tue 2026-10-20 05:42:55 UTC 10min - - systemd-tmpfiles-clean.timer systemd-tmpfiles-clean.service
Tue 2026-10-20 06:14:56 UTC 42min Mon 2026-10-19 10:54:51 UTC - apt-daily-upgrade.timer apt-daily-upgrade.service
Tue 2026-10-20 06:47:12 UTC 1h 14min Tue 2026-10-20 05:21:56 UTC - fwupd-refresh.timer fwupd-refresh.service
Tue 2026-10-20 10:24:47 UTC 4h 52min Tue 2026-10-20 00:20:16 UTC - apt-daily.timer apt-daily.service
Tue 2026-10-20 13:38:35 UTC 8h Tue 2026-10-20 03:20:26 UTC - motd-news.timer motd-news.service
Tue 2026-10-20 21:32:49 UTC 16h Mon 2026-10-19 21:32:32 UTC - lifolo-backup.timer lifolo-backup.service
Tue 2026-10-20 22:21:12 UTC 16h Tue 2026-10-20 00:58:15 UTC - certbot.timer certbot.service
Wed 2026-10-21 00:00:00 UTC 18h Tue 2026-10-20 00:00:22 UTC - dpkg-db-backup.timer dpkg-db-backup.service
Wed 2026-10-21 00:00:00 UTC 18h Tue 2026-10-20 00:00:22 UTC - logrotate.timer logrotate.service
Wed 2026-10-21 00:07:00 UTC 18h - - sysstat-summary.timer sysstat-summary.service
Wed 2026-10-21 02:20:20 UTC 20h Tue 2026-10-20 00:32:28 UTC - man-db.timer man-db.service
Sun 2026-10-25 03:10:16 UTC 4 days Mon 2026-10-19 10:54:51 UTC - e2scrub_all.timer e2scrub_all.service
Mon 2026-10-26 00:33:30 UTC 5 days Mon 2026-10-19 10:54:51 UTC - fstrim.timer fstrim.service
Tue 2026-10-27 15:45:28 UTC 1 week 0 days Mon 2026-10-19 10:54:51 UTC - update-notifier-motd.timer update-notifier-motd.service
- - - - apport-autoreport.timer apport-autoreport.service
- - - - snapd.snap-repair.timer snapd.snap-repair.service
- - - - ua-timer.timer ua-timer.service

19 timers listed.
```

| কাজ | শেষ চলা | ফল | গত ২৪ ঘণ্টায়? |
|---|---|---|---|
| lifolo-backup | 2026-10-19T21:32:32+00:00 | success (exit 0) | হ্যাঁ |
| certbot | 2026-10-20T00:58:15+00:00 | success (exit 0) | হ্যাঁ |
| logrotate | 2026-10-20T00:00:22+00:00 | success (exit 0) | হ্যাঁ |
| apt-daily | 2026-10-20T00:20:58+00:00 | success (exit 0) | হ্যাঁ |
| apt-daily-upgrade | চলেনি | success (exit 0) | ? |
| man-db | 2026-10-20T00:32:29+00:00 | success (exit 0) | হ্যাঁ |
| fstrim | চলেনি | success (exit 0) | ? |
| e2scrub_all | চলেনি | success (exit 0) | ? |
| dpkg-db-backup | 2026-10-20T00:00:22+00:00 | success (exit 0) | হ্যাঁ |
| sysstat-collect | Tue 2026-10-20 05:30:09 UTC | success (exit 0) | হ্যাঁ |

### দৈনিক ব্যাকআপ
```
2026-10-19 11:38 UTC  0.0 MB  lifolo-20261019-113815.dump
2026-10-19 21:32 UTC  0.3 MB  lifolo-20261019-213232.dump
2026-10-20 01:53 UTC  0.4 MB  lifolo-20261020-015329-check.dump
2026-10-20 03:55 UTC  0.4 MB  lifolo-20261020-035521-check.dump
সবচেয়ে নতুন ব্যাকআপ: 1 ঘণ্টা আগে
backup ok: lifolo-20261019-113815.dump (4.0K)
backup ok: lifolo-20261019-213232.dump (280K)
```

### cron
```
root crontab: খালি
certbot e2scrub_all staticroute sysstat 
```

### JobRunr (অ্যাপের ভেতরের নির্ধারিত কাজ)
| recurring কাজ | cron | প্রথম সফল (UTC, যা আছে) | শেষ সফল (UTC) | গত ২৪ ঘণ্টায় সফল | গত ২৪ ঘণ্টায় ব্যর্থ |
|---|---|---|---|---|---|
| ambulance-timers | * * * * * | 2026-10-19 16:22 | 2026-10-20 05:32 | 777 | 0 |
| daily-maintenance | 0 3 * * * | 2026-10-20 03:00 | 2026-10-20 03:00 | 1 | 0 |
| storage-trash | * * * * * | 2026-10-20 04:23 | 2026-10-20 05:32 | 69 | 0 |

সব কাজের অবস্থা (গত ২৪ ঘণ্টা): SCHEDULED=3, SUCCEEDED=847
চালু JobRunr সার্ভার: 1 (শেষ সাড়া 05:32:36 UTC)

দৈনিক কাজের (daily-maintenance) লগ, গত ২৬ ঘণ্টা:
```
(ত্রুটির লাইন: 0টা)
```

### GitHub-এর নির্ধারিত workflow (শেষ চলা)
| workflow | শেষ চলা (UTC) | ফল |
|---|---|---|
| uptime.yml | 2026-10-20T00:44:14Z | success |
| backup-check.yml | null | null |

_run 39375441696 · গাইড: deploy/OPS-DRILLS.md_
