# PostgreSQL-এ ডাটাবেস (ফেজ ১)

Firestore-এর বদলে Contabo VPS-এর PostgreSQL। সার্ভারের কোড আগের মতোই `db.collection(...).doc(...)` লেখে —
`lib/pgstore.js` সেগুলো SQL-এ বদলায়। লগইন (Firebase Auth) আর পুশ নোটিফিকেশন (FCM) Firebase-এই থাকে।

এই ব্রাঞ্চ (`pg-migration`) এখনো main-এ নেই। Vercel + Firestore আগের মতো চলছে।

## Environment variable

| নাম | কী |
|---|---|
| `DATABASE_URL` | `postgres://user:pass@host:5432/lifolo`। **থাকলে** ডাটাবেস PostgreSQL, **না থাকলে** আগের মতো Firestore। |
| `FIREBASE_SERVICE_ACCOUNT` | আগের মতোই লাগে (লগইন যাচাই, পুশ)। |
| `PG_POOL_MAX` | একসাথে কয়টা সংযোগ (ডিফল্ট ১০)। |
| `PG_AUTO_SCHEMA` | `0` দিলে চালু হওয়ার সময় টেবিল/ফাংশন বানানো হয় না (তখন হাতে বানাতে হবে)। |
| `PG_LOCK_TIMEOUT_MS` | লকের জন্য সর্বোচ্চ অপেক্ষা (ডিফল্ট ১০০০০)। পেরোলে transaction আবার চেষ্টা করে। |
| `TEST_DATABASE_URL` | শুধু টেস্টের জন্য (নিচে দেখুন)। টেস্ট কখনো `DATABASE_URL` ব্যবহার করে না। |

## Schema (টেবিল বানানো)

সব আছে `scripts/pg-schema.sql`-এ: একটা টেবিল `docs(path, coll, grp, id, data jsonb, created_at, updated_at)`,
তুলনার ফাংশন (`fs_rank`, `fs_num`, `fs_str`), index, আর `fs_index()`।

- নিজে থেকে: সার্ভার প্রথম কোয়েরির আগে একবার ফাইলটা চালায় (বারবার চালানো নিরাপদ)।
- হাতে: `psql "$DATABASE_URL" -f scripts/pg-schema.sql` (তখন `PG_AUTO_SCHEMA=0`)।
- ডাটাবেসের collation যা-ই হোক সমস্যা নেই — লেখার তুলনা সবসময় বাইট ধরে (`COLLATE "C"`), Firestore-এর মতো।

নতুন কোনো ঘর দিয়ে ঘন ঘন সাজানো / range খোঁজা হলে index:

```sql
SELECT fs_index('createdAt', 'num');   -- সংখ্যা বা সময়
SELECT fs_index('geohash', 'str');     -- লেখা (geohash)
```

এখনই বানানো থাকে: `donorGeo`, `geoOpen`, `geohash` (str) আর `createdAt`, `at`, `remindAt` (num)।
`==` আর `array-contains` খোঁজায় GIN index (`data jsonb_path_ops`) কাজে লাগে।

## Firestore থেকে তথ্য আনা

```bash
FIREBASE_SERVICE_ACCOUNT='...' DATABASE_URL=postgres://... node scripts/migrate-firestore-to-pg.js
FIREBASE_SERVICE_ACCOUNT='...' DATABASE_URL=postgres://... node scripts/migrate-firestore-to-pg.js --verify
```

- সব কালেকশন আর সাব-কালেকশন (যেকোনো গভীরতায়) কপি হয়; প্রতিটা top-level কালেকশনের ডকের সংখ্যা দেখায়।
- বারবার চালানো নিরাপদ (upsert)। মাঝপথে থামলে আবার চালান। `--only users,tips` দিয়ে কয়েকটা।
- `--verify`: প্রতিটা কালেকশনের সংখ্যা মেলায়, আর কিছু এলোমেলো ডক হুবহু মেলায় (`--sample 20`)। না মিললে exit code 1।
- Firestore-এর প্রতিটা ডক একবার পড়া হয় — বিনামূল্যের প্ল্যানে দিনে ৫০,০০০ পড়ার সীমা; বেশি হলে `--only` দিয়ে ভাগ করুন।
- Firestore-এ কিছু লেখা বা মোছা হয় না। কপি PostgreSQL থেকে কিছু মোছে না।

## টেস্ট

```bash
cd server
npm test                                    # আগের মতো: নকল (মেমোরির) Firestore
TEST_DATABASE_URL=postgres://user:pass@localhost:5432/lifolo_test npm run test:pg
```

`test:pg` (`TEST_DB=pg`) একই সব টেস্ট আসল PostgreSQL-এ চালায়: প্রতিটা টেস্ট ফাইল নিজের নতুন schema পায়,
শেষে মুছে যায়। লগইন আর পুশের নকল আগের মতো। `test/pgstore.test.js` (কোয়েরির নিয়ম, transaction, batch,
collectionGroup, recursiveDelete) আর `test/migrate.test.js` শুধু `TEST_DATABASE_URL` থাকলে চলে।

টেস্টের ডাটাবেসের ব্যবহারকারীর `CREATE SCHEMA` অনুমতি লাগে।

## Firestore-এর সাথে যা আলাদা (জানা)

- **মানের রূপ:** Timestamp, GeoPoint, Reference, Bytes jsonb-তে বিশেষ রূপে থাকে (`{"$ts": ...}` ইত্যাদি —
  `scripts/pg-schema.sql`-এর শুরুতে)। পড়লে আগের মতোই Timestamp ইত্যাদি ফেরত আসে। `serverTimestamp()` = Timestamp
  (সার্ভারের ঘড়ি, ডাটাবেসের নয়)। ডকের ভেতরের যে চাবি `$` দিয়ে শুরু, সেটা ভেতরে `$$` হয়ে থাকে।
- **undefined:** Firestore ভুল দেয়; এখানে ঘরটা বাদ পড়ে (অ্যারের ভেতরে `null`)।
- **transaction:** আসল PostgreSQL transaction। পড়া ডকে লক (না থাকা ডকেও), তাই একসাথে দুটো transaction একই ডক
  বদলালে একটা অপেক্ষা করে নতুন মান দেখে। deadlock হলে আবার চেষ্টা (সর্বোচ্চ ৫ বার)। transaction-এর ভেতরে
  কোয়েরি (`t.get(query)`) পুরো কালেকশনে লক নেয় — অন্যরা সেই কালেকশনে লেখার আগে অপেক্ষা করে।
  transaction-এর ভেতরে transaction ছাড়া লেখা (`ref.set`) একই ডকে করলে নিজেই আটকে যায় (লক অপেক্ষার সীমা পেরিয়ে ভুল)।
- **সাজানো:** collectionGroup-এ টাই ভাঙে পুরো ঠিকানার লেখা ধরে (Firestore ধরে অংশ ধরে) — খুব অদ্ভুত id না হলে একই।
  NaN-এর ক্রম, অ্যারে/ম্যাপ দিয়ে সাজানো Firestore-এর মতো নিখুঁত নয় (কোডে কোথাও নেই)।
- **`!=` / `not-in`:** ঘর না থাকলে বা `null` হলে বাদ।
- **নেই:** `onSnapshot` (লাইভ শোনা), `withConverter`, `Filter.or/and`, bulkWriter। সার্ভারের কোড এগুলো ব্যবহার করে না।
- **index লাগে না:** Firestore-এর মতো composite index না থাকলে ভুল হয় না — শুধু ধীর হতে পারে।
- **সীমা নেই:** batch-এ ৫০০ লেখা, ডক ১ MB — এসব সীমা এখানে নেই।
- **অ্যাপ আর ওয়েব:** এই পরিবর্তন শুধু সার্ভারে। অ্যাপ বা ওয়েব যদি সরাসরি Firestore পড়ে/লেখে
  (`firestore.rules` আছে), PostgreSQL-এ সরালে তারা আর নতুন তথ্য দেখবে না — ফেজ ৪-এর আগে সেগুলো সার্ভারের API-তে আনতে হবে।
