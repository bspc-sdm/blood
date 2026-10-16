# আলাদা করে সরানো যায় এমন ফিচার

কিছু ফিচার "মডিউল" হিসেবে বানানো, যাতে দরকার হলে পুরোটা মুছে ফেলা যায় আর বাকি অ্যাপে (রক্ত, অ্যাম্বুলেন্স, ফার্মেসি, ডাক্তার…) কোনো প্রভাব না পড়ে।

নিয়ম:
- ফিচারের নিজের সব কোড নিজের ফাইলে থাকে।
- অন্য ফাইলে যেখানে জোড়া লাগে, সেখানে `FEATURE:<নাম>` চিহ্ন থাকে।
  - এক লাইনের জোড়ার শেষে `// FEATURE:jobs` থাকে।
  - কয়েক লাইনের অংশ `// FEATURE:jobs START` থেকে `// FEATURE:jobs END` পর্যন্ত থাকে।
- কোনো ফিচার মুছতে প্রথমে `grep -rn "FEATURE:jobs"` চালিয়ে সব জোড়ার জায়গা বের করা হয়। তারপর নিচের তালিকা ধরে কাজ করা হয়।

---

## চাকরি ও সিভি (`jobs`)

### পুরো ফাইল মুছুন
| জায়গা | ফাইল |
|---|---|
| সার্ভার | `server/lib/jobs.js` |
| সার্ভার টেস্ট | `server/test/jobs.test.js` (আর `server/package.json`-এর `test` লাইন থেকে নামটা) |
| অ্যাপ | `app/src/main/java/com/bspc/blood/` এর `Jobs.kt`, `JobDetail.kt`, `JobEmployer.kt`, `JobAlarms.kt` (সিভি `Cv.kt` এখন মূল প্রোফাইলের অংশ — মুছবেন না) |
| অ্যাপ টেস্ট | `app/src/test/java/com/bspc/blood/JobsScreensTest.kt` |
| আইকন | `app/src/main/res/drawable/ic_nav_work.xml`, `ic_nav_work_fill.xml` (অন্য কোথাও ব্যবহার না হলে) |

### জোড়ার জায়গা (চিহ্ন দেওয়া লাইন বা অংশ সরান)

**অ্যাপ (Kotlin)**
- `MainActivity.kt` থেকে নিচের অংশগুলো সরান। এরপর `Tabs.PROFILE` আবার `4` করে দিন:
  - `Tabs.JOBS`
  - `Screen.Jobs`
  - নোটিফিকেশন থেকে খোলার অংশ (START/END)
  - `onOpenJobs`
  - `is Screen.Jobs`
  - `+ JobsNav.TYPES`
  - `EXTRA_JOB_ID` পড়া
- `MainScreen.kt` থেকে সরান:
  - `onOpenJobs` প্যারামিটার
  - নিচের বারের "চাকরি" বাটন
  - `Tabs.JOBS -> JobsTab`
- `Notifications.kt` থেকে সরান:
  - `EXTRA_JOB_ID`
  - `jobId` প্যারামিটার
  - ট্যাগে `jobId`
- `Inbox.kt`: চাকরির আইকনের লাইন সরান।
- `MainScreen.kt`: `JobAlarms.sync` লাইন সরান।
- `AndroidManifest.xml`: `JobAlarmReceiver` (চিহ্ন দেওয়া মন্তব্যসহ) সরান।
- `Verify.kt`: `VerifyDocs.employer()` সরান।
- `Services.kt`: সার্ভিসের তালিকায় "চাকরি" টাইল, `ServiceScreen`-এর `"jobs"` শাখা আর `onOpenJobs` প্যারামিটার সরান।
- `MainScreen.kt`/`Profile.kt`: প্রোফাইলের "আমার চাকরির আবেদন" (`onOpenMyApplications`) সরান।
- `Ongoing.kt`: চাকরির আইকনের লাইন সরান।

**সার্ভার**
- `.github/workflows/screens.yml` সব `*ScreensTest.kt` চালায়, তাই সেখানে কিছু বদলাতে হবে না।
- `api/community.js`:
  - অ্যাকশনের তালিকা থেকে `require('../lib/jobs').actions` সরান।
  - `admin_overview` থেকে `jobs`, `employers` সরান।
- `api/cron/daily.js`: `jobsClosed` সরান।
- `api/share.js`: শুধু `employerVerified` লাইন (`FEATURE:jobs`) সরান। `cvPage` আর `/cv/:uid` মূল প্রোফাইলের শেয়ার লিংক — রাখুন।
- `lib/account.js`: `jobs.deleteForUser` সরান।
- `lib/verify.js`: নিচের অংশগুলো সরান:
  - `DOCS.employer`, `DOCS.job`
  - `CHECKS.employer`, `CHECKS.job`
  - `COLL`-এর `employer`, `job`
  - মালিক বের করার `employer`/`job` শর্ত
  - `verifyStatus: 'pending'` লাইন
  - লেবেল আর নোটিফিকেশনের `job_mine`
- `lib/moderation.js`: `job` রিপোর্টের অংশ সরান। জায়গাগুলো:
  - `KINDS`
  - `PATH_RE`
  - `targetPath`
  - `removeTarget`
  - `report`-এর শর্ত
  - `admin_reports`-এর লেখা
- `lib/useractivity.js`: `jobs` অংশ সরান (`SECTIONS`, `LOADERS`, ফাংশন)।
- `lib/adminweb.js`: `LOGGED`-এর `admin_job_resolve`, `admin_employer_resolve`, `admin_employer_warn`, `admin_employer_block`, `admin_job_words_save` সরান।
- `lib/moderation.js`-এর `countReport` লাইনও সরান।
- `lib/community.js`: `LIMITS`-এর `job`, `apply` সরান।
- `lib/ongoing.js`: হোমের "চলমান"-এর চাকরির অংশ (`require('./jobs').ongoing`) সরান।
- `lib/cv.js`: `rolesOf`-এর `employers/{uid}` লাইন সরান (সিভি নিজে থাকবে, ওটা মূল প্রোফাইল)।

**অ্যাডমিন প্যানেল (`server/public/admin/app.js`)**
- নিচের অংশগুলো সরান:
  - `L.jobCat`, `jobType`, `edu`, `empKind`, `app`
  - মেনুর "চাকরির বিজ্ঞাপন" আর "নিয়োগকর্তা"
  - পাতার রুট
  - `LISTINGS.jobs`/`employers` (START/END)
  - `QUEUES`
  - ওভারভিউ
  - যাচাই পাতার `jobs`/`employers`
  - ব্যবহারকারীর "চাকরি" ট্যাব আর টাইমলাইন
  - রিপোর্টের `job` লেবেল
  - "চাকরির নিষিদ্ধ শব্দ" মেনু আর `pageJobWords` (START/END)
  - `jobSalary`, `empHistory` (START/END)
  - `afterChange`-এর `jobs:open`
  - `style.css`-এর `textarea.words`
- `scripts/build-admin-icons.js`: `work` আইকন সরান। তারপর `icons.js` আবার বানান।

### ডাটাবেস (Firestore)
কোড মুছলে নিচের কালেকশনগুলো আর কেউ পড়ে না। এগুলোর তথ্য আলাদা করে মোছা যায়:
- `employers`
- `jobs`
- `applications`
- `verifyDocs`-এর `employer_*` ছবি
- `jobSaves` (সেভ করা চাকরি)
- `jobAlerts` (চাকরির অ্যালার্ট)
- `config/jobRules` (নিষিদ্ধ শব্দ)

### যাচাই
- `npm test`
- অ্যাপ বিল্ড (CI)
- `grep -rn "FEATURE:jobs"` চালালে কিছুই না আসা
