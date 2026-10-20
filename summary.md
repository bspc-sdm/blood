# Android e2e — Node (Vercel) vs Spring

- **spring**: server: https://lifolo.com · health: {"ok":true,"firebase":true,"db":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}

| journey | node rc | spring rc | node steps | spring steps | parity |
|---|---|---|---|---|---|
| j1-patient | - () | 0 (134s) |  | COMPLETED:84 SKIPPED:2 WARNED:1 | rc differs |
| j10-blood | - () | 0 (107s) |  | COMPLETED:76 SKIPPED:8 | rc differs |
| j11-ambulance-driver | - () | 0 (93s) |  | COMPLETED:65 SKIPPED:7 | rc differs |
| j12-appointments | - () | 0 (108s) |  | COMPLETED:69 SKIPPED:8 | rc differs |
| j2-services | - () | 0 (122s) |  | COMPLETED:90 SKIPPED:3 | rc differs |
| j3-pharmacy | - () | 0 (143s) |  | COMPLETED:77 SKIPPED:5 WARNED:1 | rc differs |
| j4-lab | - () | 0 (61s) |  | COMPLETED:44 SKIPPED:3 | rc differs |
| j5-doctor | - () | 0 (82s) |  | COMPLETED:38 SKIPPED:3 WARNED:1 | rc differs |
| j6-ambulance | - () | 0 (134s) |  | COMPLETED:37 SKIPPED:3 WARNED:2 | rc differs |
| j7-blood | - () | 0 (56s) |  | COMPLETED:39 SKIPPED:3 | rc differs |
| j8-profile | - () | 0 (90s) |  | COMPLETED:60 SKIPPED:3 | rc differs |
| j9-rx-online | - () | 0 (124s) |  | COMPLETED:58 SKIPPED:5 WARNED:3 | rc differs |

**Verdict:** PARITY — Spring fails no journey that passes on Node

rc 0 = journey passed (optional steps may still be WARNED/SKIPPED; those are listed below when they differ).

## Steps whose status differs

### j1-patient
  - 87 later step(s) not reached on node (other side: {'COMPLETED': 84, 'SKIPPED': 2, 'WARNED': 1})

### j10-blood
  - 84 later step(s) not reached on node (other side: {'COMPLETED': 76, 'SKIPPED': 8})

### j11-ambulance-driver
  - 72 later step(s) not reached on node (other side: {'COMPLETED': 65, 'SKIPPED': 7})

### j12-appointments
  - 77 later step(s) not reached on node (other side: {'COMPLETED': 69, 'SKIPPED': 8})

### j2-services
  - 93 later step(s) not reached on node (other side: {'COMPLETED': 90, 'SKIPPED': 3})

### j3-pharmacy
  - 83 later step(s) not reached on node (other side: {'COMPLETED': 77, 'SKIPPED': 5, 'WARNED': 1})

### j4-lab
  - 47 later step(s) not reached on node (other side: {'COMPLETED': 44, 'SKIPPED': 3})

### j5-doctor
  - 42 later step(s) not reached on node (other side: {'COMPLETED': 38, 'SKIPPED': 3, 'WARNED': 1})

### j6-ambulance
  - 42 later step(s) not reached on node (other side: {'COMPLETED': 37, 'SKIPPED': 3, 'WARNED': 2})

### j7-blood
  - 42 later step(s) not reached on node (other side: {'COMPLETED': 39, 'SKIPPED': 3})

### j8-profile
  - 63 later step(s) not reached on node (other side: {'COMPLETED': 60, 'SKIPPED': 3})

### j9-rx-online
  - 66 later step(s) not reached on node (other side: {'COMPLETED': 58, 'SKIPPED': 5, 'WARNED': 3})

