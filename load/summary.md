## Lifolo — লোড টেস্ট (সরাসরি VPS-এ, Cloudflare ছাড়া)

**নিরাপদ ক্ষমতা:** ~**1198 অনুরোধ/সেকেন্ড** (লক্ষ্য 1200 লগইন-করা rps + অতিথি/লগইন/health) — p95 365 ms, ত্রুটি 0.00%।
**ভাঙনের বিন্দু:** দেওয়া সর্বোচ্চ ধাপ পর্যন্ত পৌঁছায়নি (p95 ≤ 1 s, ত্রুটি ≤ 1%)।

### ধাপ অনুযায়ী
| ধাপ | লক্ষ্য rps | পাওয়া rps | p95 সব (ms) | p95 পড়া | p95 লেখা | p95 অতিথি | p95 health | ত্রুটি | dropped | CPU গড়/সর্বোচ্চ | Java RSS (MB) | DB সংযোগ active/মোট |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 300 | 303.6 | 189 | 201 | 110 | 369 | 101 | 0.00% | 0 | 39% / 69% | 1,431 | 1/20 |
| 1 | 500 | 503.7 | 261 | 279 | 144 | 367 | 123 | 0.00% | 0 | 47% / 60% | 1,460 | 3/20 |
| 2 | 650 | 653.6 | 365 | 366 | 158 | 375 | 137 | 0.00% | 0 | 54% / 63% | 1,472 | 3/20 |
| 3 | 800 | 803.7 | 340 | 356 | 154 | 368 | 127 | 0.00% | 0 | 57% / 71% | 1,474 | 4/20 |
| 4 | 1000 | 1001.6 | 350 | 356 | 128 | 369 | 113 | 0.00% | 121 | 61% / 74% | 1,484 | 1/20 |
| 5 | 1200 | 1197.8 | 365 | 365 | 205 | 364 | 158 | 0.00% | 349 | 62% / 81% | 1,512 | 5/20 |

**লক্ষ্য rps** = লগইন-করা ডেমো ব্যবহারকারীর অনুরোধ (৯৫% পড়া, ৫% লেখা); তার সাথে প্রতি ধাপে স্থির: অতিথি ২/সে (সার্ভারের সীমা প্রতি IP-তে মিনিটে ২৪০), লগইন-কোডের পথ ০.৫/সে, health ১/সে। প্রতি ধাপ 60 সেকেন্ড। **dropped** = k6 সময়মতো পাঠাতে পারেনি (সার্ভার ধীর হলে বাড়ে)।

### VPS (ধাপের সর্বোচ্চ)
| ধাপ | load1 | RAM ব্যবহৃত (MB) | খালি RAM কম-সে-কম (MB) | swap (MB) | Java CPU % (সব কোর) | Java থ্রেড | nginx→অ্যাপ সংযোগ | :443 সংযোগ | DB lock-অপেক্ষা |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 2.41 | 2,152 | 9,808 | 0 | 58 | 69 | 43 | 312 | 0 |
| 1 | 4.21 | 2,214 | 9,745 | 0 | 44 | 72 | 120 | 513 | 1 |
| 2 | 5.71 | 2,248 | 9,712 | 0 | 45 | 71 | 413 | 663 | 0 |
| 3 | 7.29 | 2,259 | 9,700 | 0 | 49 | 70 | 224 | 814 | 1 |
| 4 | 6.23 | 2,278 | 9,681 | 0 | 48 | 68 | 243 | 820 | 0 |
| 5 | 7.21 | 2,298 | 9,662 | 0 | 52 | 68 | 386 | 818 | 0 |

### ধরন অনুযায়ী (মাঝামাঝি / p95, ms)
| ধাপ | পড়া | লেখা | অতিথি | লগইন-কোড | health |
|---|---|---|---|---|---|
| 0 | 97 / 201 (0.00%) | 95 / 110 (0.00%) | 94 / 369 (0.00%) | 94 / 103 (0.00%) | 93 / 101 (0.00%) |
| 1 | 98 / 279 (0.00%) | 96 / 144 (0.00%) | 97 / 367 (0.00%) | 96 / 123 (0.00%) | 93 / 123 (0.00%) |
| 2 | 98 / 366 (0.00%) | 96 / 158 (0.00%) | 97 / 375 (0.00%) | 95 / 127 (0.00%) | 94 / 137 (0.00%) |
| 3 | 98 / 356 (0.00%) | 96 / 154 (0.00%) | 94 / 368 (0.00%) | 95 / 156 (0.00%) | 94 / 127 (0.00%) |
| 4 | 98 / 356 (0.00%) | 96 / 128 (0.00%) | 96 / 369 (0.00%) | 96 / 121 (0.00%) | 93 / 113 (0.00%) |
| 5 | 97 / 365 (0.00%) | 95 / 205 (0.00%) | 94 / 364 (0.00%) | 94 / 181 (0.00%) | 93 / 158 (0.00%) |

### সার্ভারের দিক (পুরো টেস্টের সময়)
- nginx: 268353 অনুরোধ; 5xx 0 (502: 0, 504: 0), 499 (ক্লায়েন্ট ছেড়ে দিয়েছে) 0, 429 60; error.log-এ নতুন 0 লাইন (upstream timeout 0, worker_connections কম 0)
- অ্যাপ: লগে ERROR/Exception 4টা, Hikari পুল-টাইমআউট 0, OutOfMemory 0; টেস্টের মধ্যে অ্যাপ restart: না
- অ্যাপের লগে সবচেয়ে বেশি আসা সতর্কতা/ত্রুটি:
```
      2 unhandled error on /api/events
      2 Resolved [org.springframework.web.context.request.async.AsyncRequestTimeoutException]
      2 Ignoring exception, response committed already: org.springframework.web.context.request.async.AsyncRequestTimeoutException
      2 Failure in @ExceptionHandler com.lifolo.common.ApiErrorHandler#other(Exception, HttpServletRequest)
      2 org.springframework.web.context.request.async.AsyncRequestTimeoutException
      2 org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'
```
- লেখা: রক্তের অনুরোধ বানানো→মোছা 6 বার, অ্যাম্বুলেন্স ডাকা→বাতিল 2 বার
- অতিথির সীমা (একই IP থেকে দ্রুত 300টা): 200 = 240, 429 = 60, অন্য = 0 → সীমা কাজ করে ✅

### পরিষ্কার (শুধু demo-… অ্যাকাউন্ট)
- ফল: **ok** — ambreq_state_cleared 1, inbox_items 6, ambulance_requests 2, ambreq_create_log 2, blood_requests 0, user_quotas_removed 6, user_quotas_restored 0, user_state_restored 1, user_state_cleared 1, ambreq_state_restored 0, ambreq_state_removed 1; বাকি থাকা LOADTEST সারি: রক্ত 0, অ্যাম্বুলেন্স 0

### এখনকার সেটিং
- JVM: `/usr/bin/java $LIFOLO_JAVA_OPTS -Dserver.port=8080 -Dserver.address=127.0.0.1 -jar <jar>` · openjdk version "21.0.12.1" 2026-08-18 LTS · অ্যাপের RSS এখন 1384 MB · open files 65536
- Hikari পুল: 20 (ডিফল্ট, application.yml) · virtual threads: true (application.yml)
- PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1): max_connections 100, shared_buffers 2GB, effective_cache_size 6GB, work_mem 8MB, ডাটাবেস 41 MB
- nginx: worker_processes auto, worker_connections 4096, upstream keepalive আছে, limit_req আছে
- মেশিন: 6 vCPU, RAM 11960 MB, swap 2047 MB · Redis maxmemory 536870912

_run 39932571774 · 2026-10-23 09:41 UTC · গাইড: deploy/OPS-DRILLS.md_
