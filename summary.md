# Android e2e — Node (Vercel) vs Spring

- **node**: server: https://blood-server-ashy.vercel.app · health: {"ok":true,"firebase":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}
- **spring**: server: https://lifolo.com · health: {"ok":true,"firebase":true,"db":true} · demoRoles: {"active":true,"roles":[{"key":"patient","name":"ডেমো রোগী রহিম","role":"সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"},{"key":"donor","name":"ডেমো ডোনার করিম","role":"রক্তদাতা"},{"key":"doctor","name":"ডা. ডেমো হাসান","role":"ডাক্তার"},{"key":"assistant","name":"ডেমো সহকারী সুমন","role":"ডাক্তারের সহকারী"},{"key":"pharmacy","name":"ডেমো ফার্মেসির মালিক","role":"ফার্মেসি"},{"key":"lab","name":"ডেমো প্যাথলজির মালিক","role":"প্যাথলজি / ল্যাব"},{"key":"ambulance","name":"ডেমো অ্যাম্বুলেন্স চালক","role":"অ্যাম্বুলেন্স"},{"key":"bloodbank","name":"ডেমো ব্লাড ব্যাংক","role":"ব্লাড ব্যাংক"},{"key":"homecare","name":"ডেমো নার্স নাসরিন","role":"বাসায় সেবা (নার্স)"},{"key":"caregiver","name":"ডেমো কেয়ারগিভার রুমা","role":"কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"}]}

| journey | node rc | spring rc | node steps | spring steps | parity |
|---|---|---|---|---|---|
| j1-patient | 1 (94s) | 0 (124s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:84 SKIPPED:2 WARNED:1 | rc differs |
| j2-services | 1 (94s) | 0 (113s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:90 SKIPPED:3 | rc differs |
| j3-pharmacy | 1 (123s) | 0 (156s) | COMPLETED:46 FAILED:1 SKIPPED:3 WARNED:2 | COMPLETED:75 SKIPPED:5 WARNED:3 | rc differs |
| j4-lab | 1 (97s) | 0 (55s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:44 SKIPPED:3 | rc differs |
| j5-doctor | 1 (93s) | 0 (77s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:38 SKIPPED:3 WARNED:1 | rc differs |
| j6-ambulance | 1 (93s) | 0 (57s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:37 SKIPPED:3 WARNED:2 | rc differs |
| j7-blood | 1 (92s) | 0 (46s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:39 SKIPPED:3 | rc differs |
| j8-profile | 1 (92s) | 0 (71s) | COMPLETED:17 FAILED:2 SKIPPED:2 | COMPLETED:54 SKIPPED:3 | rc differs |

**Verdict:** PARITY — Spring fails no journey that passes on Node

rc 0 = journey passed (optional steps may still be WARNED/SKIPPED; those are listed below when they differ).

## Steps whose status differs

### j1-patient
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 66 later step(s) not reached on node (other side: {'COMPLETED': 65, 'WARNED': 1})

### j2-services
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 72 later step(s) not reached on node (other side: {'COMPLETED': 71, 'SKIPPED': 1})

### j3-pharmacy
  - #51 node `FAILED` assertCondition {"visible": {"textRegex": ".*ওষুধ দিন.*", "optional": false}} | spring `COMPLETED` assertCondition {"visible": {"textRegex": ".*ওষুধ দিন.*", "optional": false}}
  - 31 later step(s) not reached on node (other side: {'COMPLETED': 28, 'WARNED': 1, 'SKIPPED': 2})

### j4-lab
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 26 later step(s) not reached on node (other side: {'COMPLETED': 25, 'SKIPPED': 1})

### j5-doctor
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 21 later step(s) not reached on node (other side: {'COMPLETED': 19, 'SKIPPED': 1, 'WARNED': 1})

### j6-ambulance
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 21 later step(s) not reached on node (other side: {'COMPLETED': 18, 'SKIPPED': 1, 'WARNED': 2})

### j7-blood
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 21 later step(s) not reached on node (other side: {'COMPLETED': 20, 'SKIPPED': 1})

### j8-profile
  - #3 node `FAILED` runFlow "../common/start.yaml" | spring `COMPLETED` runFlow "../common/start.yaml"
  - #20 node `FAILED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false} | spring `COMPLETED` scrollUntilVisible {"textRegex": ".*${ROLE}.*", "optional": false}
  - 36 later step(s) not reached on node (other side: {'COMPLETED': 35, 'SKIPPED': 1})

