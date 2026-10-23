## প্রসেস-ক্র্যাশ মহড়া (kill -9 lifolo-api)

**মারা হলো:** 2026-10-20 05:38:09 UTC · systemd-এর restart গোনা: 0 → 1

| মাপ | সময় (kill থেকে) |
|---|---|
| systemd নতুন প্রসেস চালাল | 6.0 s |
| **https /api/health সবুজ** | **18.9 s** |
| মাঝের ব্যর্থ health (প্রতি ০.৫ s) | 11টা — HTTP: 200×1 502×11  |

এখন: অ্যাপ ok, চালু Tue 2026-10-20 05:38:15 UTC থেকে; Started LifoloApplicationKt in 11.278 seconds

JobRunr (প্রতি-মিনিটের কাজ restart-এর পরে চলেছে কি না):
```
storage-trash_last_ok=05:39:43
ambulance-timers_last_ok=05:39:44
processing_now=0
now_utc=05:40:01
```
systemd-এর লগ:
```
2026-10-20T05:36:19+00:00 vmi3678618 systemd[1]: Stopped lifolo-api.service - Lifolo API (Spring Boot).
2026-10-20T05:36:31+00:00 vmi3678618 java[4521]: 2026-10-20T05:36:31.206Z  INFO 4521 --- [lifolo-backend] [           main] com.lifolo.LifoloApplicationKt           : Started LifoloApplicationKt in 11
2026-10-20T05:38:10+00:00 vmi3678618 systemd[1]: lifolo-api.service: Main process exited, code=killed, status=9/KILL
2026-10-20T05:38:10+00:00 vmi3678618 systemd[1]: lifolo-api.service: Failed with result 'signal'.
2026-10-20T05:38:15+00:00 vmi3678618 systemd[1]: lifolo-api.service: Scheduled restart job, restart counter is at 1.
2026-10-20T05:38:27+00:00 vmi3678618 java[5552]: 2026-10-20T05:38:27.339Z  INFO 5552 --- [lifolo-backend] [           main] com.lifolo.LifoloApplicationKt           : Started LifoloApplicationKt in 11
```

**ফল:** ✅ systemd নিজে অ্যাপ আবার চালিয়েছে, health সবুজ।

_run 39375927286 · গাইড: deploy/OPS-DRILLS.md_
