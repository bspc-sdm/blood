## সার্ভারের লগ কতদিন থাকে

| লগ | নিয়ম এখন | সীমা |
|---|---|---|
| journald (অ্যাপ lifolo-api আর সব সার্ভিস) | `Storage=persistent SystemMaxUse=1G SystemKeepFree=2G MaxFileSec=1day MaxRetentionSec=21day ` · ব্যবহার 60.5M, সবচেয়ে পুরোনো 2026-10-19T10:54:43+00:00 | ≤২১ দিন, ≤১ GB |
| nginx (access/error, দর্শকের IP) |  daily rotate 13 maxage 14 (বদলেছে: no) | ≤১৪ দিন |
| syslog, auth.log … (rsyslog; journald-এর কপি) |  rotate 2 maxage 21 weekly (বদলেছে: no) | ≤২১ দিন |
| PostgreSQL |  weekly rotate 2 maxage 21 (বদলেছে: no) | ≤২১ দিন |
| Redis |  weekly rotate 2 maxage 21 (বদলেছে: no) | ≤২১ দিন |
| fail2ban (IP) |  weekly rotate 2 maxage 21 (বদলেছে: no) | ≤২১ দিন |
| ufw (ফায়ারওয়ালের IP) |  rotate 2 maxage 21 weekly (বদলেছে: yes) | ≤২১ দিন |
| btmp / wtmp (SSH লগইন) |  weekly rotate 2 maxage 21 (বদলেছে: yes) /  weekly rotate 2 maxage 21 (বদলেছে: yes) | ≤২১ দিন |

- অ্যাপের নিজের লগ ফাইল: none (stdout → journald, 21 দিন)
- logrotate: যাচাই ok, timer active/enabled · এখনই মোছা পুরোনো ঘোরানো ফাইল: 0টা · journald বদলেছে: no

_run 39376403981 · গাইড: deploy/OPS-DRILLS.md_
