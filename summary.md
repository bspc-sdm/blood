# Android e2e — Node (Vercel) vs Spring

- **spring**: server: https://lifolo.com · health: {"ok":true,"firebase":true,"db":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}

| journey | node rc | spring rc | node steps | spring steps | parity |
|---|---|---|---|---|---|
| j1-patient | - () | 1 (79s) |  | COMPLETED:47 FAILED:1 SKIPPED:3 | rc differs |
| j11-ambulance-driver | - () | 0 (102s) |  | COMPLETED:70 SKIPPED:7 | rc differs |
| j12-appointments | - () | 0 (110s) |  | COMPLETED:69 SKIPPED:8 | rc differs |
| j16-online-chamber | - () | 1 (130s) |  | COMPLETED:53 FAILED:1 SKIPPED:8 | rc differs |
| j17-calls | - () | 0 (148s) |  | COMPLETED:103 SKIPPED:7 | rc differs |
| j18-chat | - () | 1 (118s) |  | COMPLETED:66 FAILED:1 SKIPPED:4 | rc differs |
| j19-invoice-pharmacy | - () | 0 (95s) |  | COMPLETED:57 SKIPPED:8 | rc differs |
| j21-invoice-driver | - () | 1 (109s) |  | COMPLETED:41 FAILED:1 SKIPPED:3 | rc differs |
| j24-pay-business | - () | 0 (73s) |  | COMPLETED:42 SKIPPED:4 | rc differs |
| j26-practice-rx | - () | 0 (102s) |  | COMPLETED:65 SKIPPED:9 | rc differs |
| j3-pharmacy | - () | 0 (173s) |  | COMPLETED:84 SKIPPED:5 WARNED:4 | rc differs |
| j4-lab | - () | 0 (64s) |  | COMPLETED:47 SKIPPED:3 | rc differs |
| j5-doctor | - () | 0 (99s) |  | COMPLETED:53 SKIPPED:3 WARNED:1 | rc differs |
| j6-ambulance | - () | 0 (63s) |  | COMPLETED:37 SKIPPED:3 WARNED:2 | rc differs |

**Verdict:** PARITY — Spring fails no journey that passes on Node

rc 0 = journey passed (optional steps may still be WARNED/SKIPPED; those are listed below when they differ).

## Steps whose status differs

### j1-patient
  - 51 later step(s) not reached on node (other side: {'COMPLETED': 47, 'SKIPPED': 3, 'FAILED': 1})

### j11-ambulance-driver
  - 77 later step(s) not reached on node (other side: {'COMPLETED': 70, 'SKIPPED': 7})

### j12-appointments
  - 77 later step(s) not reached on node (other side: {'COMPLETED': 69, 'SKIPPED': 8})

### j16-online-chamber
  - 62 later step(s) not reached on node (other side: {'COMPLETED': 53, 'SKIPPED': 8, 'FAILED': 1})

### j17-calls
  - 110 later step(s) not reached on node (other side: {'COMPLETED': 103, 'SKIPPED': 7})

### j18-chat
  - 71 later step(s) not reached on node (other side: {'COMPLETED': 66, 'SKIPPED': 4, 'FAILED': 1})

### j19-invoice-pharmacy
  - 65 later step(s) not reached on node (other side: {'COMPLETED': 57, 'SKIPPED': 8})

### j21-invoice-driver
  - 45 later step(s) not reached on node (other side: {'COMPLETED': 41, 'SKIPPED': 3, 'FAILED': 1})

### j24-pay-business
  - 46 later step(s) not reached on node (other side: {'COMPLETED': 42, 'SKIPPED': 4})

### j26-practice-rx
  - 74 later step(s) not reached on node (other side: {'COMPLETED': 65, 'SKIPPED': 9})

### j3-pharmacy
  - 93 later step(s) not reached on node (other side: {'COMPLETED': 84, 'SKIPPED': 5, 'WARNED': 4})

### j4-lab
  - 50 later step(s) not reached on node (other side: {'COMPLETED': 47, 'SKIPPED': 3})

### j5-doctor
  - 57 later step(s) not reached on node (other side: {'COMPLETED': 53, 'SKIPPED': 3, 'WARNED': 1})

### j6-ambulance
  - 42 later step(s) not reached on node (other side: {'COMPLETED': 37, 'SKIPPED': 3, 'WARNED': 2})

