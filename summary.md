# Android e2e — Node (Vercel) vs Spring

- **node**: server: https://blood-server-ashy.vercel.app · health: {"ok":true,"firebase":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}
- **spring**: server: https://api.lifolo.com · health: {"ok":true,"firebase":true,"db":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}

| journey | node rc | spring rc | node steps | spring steps | parity |
|---|---|---|---|---|---|
| j1-patient | 1 (110s) | 1 (107s) | COMPLETED:61 FAILED:1 SKIPPED:2 | COMPLETED:61 FAILED:1 SKIPPED:2 | same |
| j2-services | 1 (128s) | 1 (130s) | COMPLETED:61 FAILED:1 SKIPPED:3 | COMPLETED:61 FAILED:1 SKIPPED:3 | same |
| j3-pharmacy | 0 (168s) | 0 (147s) | COMPLETED:75 SKIPPED:5 WARNED:3 | COMPLETED:77 SKIPPED:5 WARNED:1 | 2 step(s) differ |
| j4-lab | 0 (61s) | 0 (59s) | COMPLETED:44 SKIPPED:3 | COMPLETED:44 SKIPPED:3 | same |
| j5-doctor | 0 (80s) | 0 (79s) | COMPLETED:38 SKIPPED:3 WARNED:1 | COMPLETED:38 SKIPPED:3 WARNED:1 | same |
| j6-ambulance | 0 (62s) | 0 (62s) | COMPLETED:37 SKIPPED:3 WARNED:2 | COMPLETED:37 SKIPPED:3 WARNED:2 | same |
| j7-blood | 1 (78s) | 1 (79s) | COMPLETED:31 FAILED:1 SKIPPED:3 | COMPLETED:31 FAILED:1 SKIPPED:3 | same |
| j8-profile | 0 (81s) | 0 (80s) | COMPLETED:54 SKIPPED:3 | COMPLETED:54 SKIPPED:3 | same |

**Verdict:** PARITY — Spring fails no journey that passes on Node

rc 0 = journey passed (optional steps may still be WARNED/SKIPPED; those are listed below when they differ).

## Steps whose status differs

### j3-pharmacy
  - #32 node `WARNED` scrollUntilVisible {"textRegex": "দোকানে এসেছেন", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": "দোকানে এসেছেন", "optional": false}
  - #34 node `WARNED` tapOnElement {"textRegex": "তৈরি", "below": {"textRegex": "দোকানে এসেছেন", "optional": false}, "optiona | spring `COMPLETED` tapOnElement {"textRegex": "তৈরি", "below": {"textRegex": "দোকানে এসেছেন", "optional": false}, "optiona

