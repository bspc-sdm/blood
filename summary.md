## ✅ Firestore বন্ধ (deny-all), export VPS-এ যাচাই করা

প্রকল্প `blood-3eb28` · তারিখ (UTC) 2026-10-20 · commit `c3476f8` (firestore-off)

### ১) export

| কী | ফল |
|---|---|
| export (firebase-admin) | success — মোট **1552** নথি, 47টা top-level collection, 133736 bytes gz |
| VPS-এ কপি + খুলে মেলানো (সংখ্যা, sha256) | success ✅ |
| ফাইল | `/var/backups/lifolo/firestore-final-2026-10-20.json.gz` (root, 600) · sha256 `ec91ffa1baa35c0f…` |
| ডাটাবেস (প্রকল্পে) | (default) — export শুধু (default) |

| collection (subcollection: `বাবা/*/নাম`) | নথি (VPS-এ গোনা) |
|---|---|
| `adminLog` | 42 |
| `ambulanceCalls` | 3 |
| `ambulanceStats` | 6 |
| `ambulances` | 7 |
| `appointments` | 21 |
| `badges` | 14 |
| `bloodBankCalls` | 1 |
| `bloodBankStats` | 3 |
| `bloodBanks` | 4 |
| `careLinks` | 2 |
| `config` | 2 |
| `correctionRequests` | 2 |
| `emailCodes` | 6 |
| `emergencies` | 2 |
| `emergencies/*/donors` | 6 |
| `homeCalls` | 2 |
| `homeStats` | 4 |
| `hospitalRequests` | 1 |
| `hospitals` | 702 |
| `inbox` | 32 |
| `labOrders` | 8 |
| `labPrices` | 3 |
| `labs` | 5 |
| `maintenance` | 3 |
| `medFinds` | 3 |
| `modReports` | 7 |
| `pharmacies` | 6 |
| `pharmacyCalls` | 1 |
| `pharmacyMissed` | 1 |
| `pharmacyStats` | 5 |
| `pharmacyStock` | 3 |
| `prescriptions` | 15 |
| `providers` | 10 |
| `questions` | 7 |
| `questions/*/answers` | 6 |
| `queues` | 1 |
| `requests` | 12 |
| `requests/*/private` | 12 |
| `requests/*/responses` | 7 |
| `rxOrders` | 10 |
| `rxPatients` | 3 |
| `rxReviews` | 4 |
| `sanctions` | 5 |
| `serials` | 11 |
| `serverState` | 32 |
| `shopBooks/*/adjust` | 3 |
| `shopBooks/*/days` | 35 |
| `shopBooks/*/purchases` | 5 |
| `shopBooks/*/sales` | 301 |
| `shopItems/*/items` | 26 |
| `tickets` | 4 |
| `tips` | 5 |
| `users` | 73 |
| `verifyDocs` | 58 |

### ২) Firestore rules

| কী | ফল |
|---|---|
| আগের rules → VPS | ✅ `/var/backups/lifolo/firestore-rules-before-2026-10-20.rules` (sha256 `00e9db4df52e`; রিপোর `firestore.rules`-এর সাথে হুবহু এক: no; আগেই deny-all: False) |
| deny-all প্রকাশ + আবার পড়ে মেলানো | ✅ `1c983356-dc2b-4ec4-a104-5940875960d3` (Tue, 20 Oct 2026 02:51:33 GMT) |

### ৩) Firebase Storage (শুধু দেখা, কিছু বদলানো হয়নি)

| বাকেট | ফাইল | Storage rules |
|---|---|---|
| `blood-3eb28.firebasestorage.app` | বাকেট নেই | নেই |
| `blood-3eb28.appspot.com` | বাকেট নেই | নেই |

কোনো নথি বা ডাটাবেস মোছা হয়নি। Firebase Auth, Cloud Messaging, অ্যাপের নিবন্ধন, authorized domains, service account — অছোঁয়া।
ফেরত আনা ও পরের ধাপ: deploy/CUTOVER.md ("Firestore বন্ধ")।
