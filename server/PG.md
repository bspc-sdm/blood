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
| `PG_STATEMENT_TIMEOUT_MS` | একটা কোয়েরি সর্বোচ্চ কতক্ষণ (ডিফল্ট ৬০০০০)। schema বানানোয় প্রযোজ্য নয়। |
| `PG_IDLE_TX_TIMEOUT_MS` | খোলা transaction-এ কিছু না করে বসে থাকলে PostgreSQL সংযোগ কেটে লক ছেড়ে দেয় (ডিফল্ট ৩০০০০)। |
| `TEST_DATABASE_URL` | শুধু টেস্টের জন্য (নিচে দেখুন)। টেস্ট কখনো `DATABASE_URL` ব্যবহার করে না। |

## Schema (টেবিল বানানো)

সব আছে `scripts/pg-schema.sql`-এ: একটা টেবিল `docs(path, coll, grp, id, data jsonb, created_at, updated_at)`,
তুলনার ফাংশন (`fs_rank`, `fs_num`, `fs_str`), index, আর `fs_index()`।

- নিজে থেকে: সার্ভার প্রথম কোয়েরির আগে `fs_meta`-র `schema_version` দেখে। ফাইলের শেষের সংস্করণের সাথে মিললে
  কোনো DDL চলে না (`CREATE INDEX IF NOT EXISTS`-ও টেবিলে SHARE লক নেয় — প্রতি চালুতে চললে চলমান লেখা আটকাত)।
  না মিললে (প্রথমবার, বা ফাইল বদলের পর) একটা প্রসেসই advisory lock নিয়ে পুরো ফাইল একবার চালায়।
- ফাইল বদলালে শেষের `schema_version` বাড়াতে হবে। বড় টেবিলে নতুন index লাগলে হাতে `CREATE INDEX CONCURRENTLY`
  দিয়ে আগে বানিয়ে নিন (ফাইলের index বানানো transaction-এর ভেতরে, তাই লেখা আটকায়)। `fs_num` বদলালে তার
  expression index-গুলো `REINDEX INDEX CONCURRENTLY` করুন (সংস্করণ ২-এ ±Infinity যোগ হয়েছে — আগে Infinity-ওয়ালা ডক
  থাকলে লাগবে; এখনো চালু ডাটাবেস নেই বলে সাধারণত নয়)।
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

- সব top-level কালেকশন কপি হয়। সাব-কালেকশন খোঁজা হয় শুধু জানা parent কালেকশনের ডকের নিচে
  (`requests`, `emergencies`, `questions`, `shopBooks`, `shopItems` — স্ক্রিপ্টের `PARENTS`); সেখানে যেকোনো গভীরতায়।
  `--parents a,b` দিয়ে বদলানো যায়; `--deep` সব ডকের নিচে খোঁজে (অজানা সাব-কালেকশন আছে কিনা দেখতে)।
  কোডে নতুন সাব-কালেকশন এলে `PARENTS`-এ যোগ করুন।
- বারবার চালানো নিরাপদ (upsert)। মাঝপথে থামলে আবার চালান। `--only users,tips` দিয়ে কয়েকটা।
- `--verify`: প্রতিটা কালেকশনের (সাব-কালেকশনসহ) ডকের id-র পুরো তালিকা দুই দিকে হুবহু মেলায় — কোনটা PostgreSQL-এ
  নেই (`missing`) আর কোনটা বাড়তি (`extra`), নাম ধরে; সাথে কিছু এলোমেলো ডকের তথ্য হুবহু (`--sample 20`)।
  না মিললে exit code 1।
- **পড়ার খরচ** (বিনামূল্যের প্ল্যানে দিনে ৫০,০০০):
  - সাধারণ কালেকশন: documentId ধরে পাতায় পাতায় কোয়েরি — **প্রতি ডকে ১ পড়া**।
  - parent কালেকশন: `listDocuments` (তথ্যহীন ডকসহ, প্রতি ডকে ১) + `getAll` (প্রতি ডকে ১) + প্রতি ডকে
    `listCollections` (১) — **প্রতি ডকে প্রায় ৩**। সাব-কালেকশনের ডক নিজের কালেকশনের নিয়মে।
  - `--verify`: প্রতি ডকে ১ (id-র তালিকা) + parent-এ প্রতি ডকে আরও ২ + নমুনা (`--sample`)।
  - আগের লেখা "প্রতিটা ডক একবার পড়া" ভুল ছিল: আগে প্রতিটা ডকে listDocuments + getAll + listCollections — প্রায় ৩।
  - মোট বেশি হলে `--only` দিয়ে কয়েক দিনে ভাগ করুন।
- লেখায় U+0000 থাকলে ফেরতযোগ্য escape হয়, অর্ধেক ইমোজি U+FFFD — কোনো ডকে থামে না; শেষে লগে কতটা ঘরে।
- Firestore-এ কিছু লেখা বা মোছা হয় না। কপি PostgreSQL থেকে কিছু মোছে না।

## টেস্ট

```bash
cd server
npm test                                    # আগের মতো: নকল (মেমোরির) Firestore
TEST_DATABASE_URL=postgres://user:pass@localhost:5432/lifolo_test npm run test:pg
```

`test:pg` (`TEST_DB=pg`) একই সব টেস্ট আসল PostgreSQL-এ চালায়: প্রতিটা টেস্ট ফাইল নিজের নতুন schema পায়,
শেষে মুছে যায়। লগইন আর পুশের নকল আগের মতো। `test/pgstore.test.js` (কোয়েরির নিয়ম, transaction, batch,
collectionGroup, recursiveDelete), `test/pgstore-review.test.js` (স্বাধীন রিভিউয়ের ধরা ভুল),
`test/pgstore-fixes.test.js` (সেগুলো সারানোর খুঁটিনাটি, লক, pool, schema) আর `test/migrate.test.js`
শুধু `TEST_DATABASE_URL` থাকলে চলে।

টেস্টের ডাটাবেসের ব্যবহারকারীর `CREATE SCHEMA` অনুমতি লাগে।

## Firestore-এর সাথে যা আলাদা (জানা)

- **মানের রূপ:** Timestamp, GeoPoint, Reference, Bytes jsonb-তে বিশেষ রূপে থাকে (`{"$ts": ...}` ইত্যাদি —
  `scripts/pg-schema.sql`-এর শুরুতে)। পড়লে আগের মতোই Timestamp ইত্যাদি ফেরত আসে। `serverTimestamp()` = Timestamp
  (সার্ভারের ঘড়ি, ডাটাবেসের নয়)। ডকের ভেতরের যে চাবি `$` দিয়ে শুরু, সেটা ভেতরে `$$` হয়ে থাকে।
- **U+0000 আর অর্ধেক ইমোজি:** jsonb U+0000 রাখতে পারে না, তাই লেখায় (মান আর map-এর চাবি) ফেরতযোগ্য escape:
  U+0000 → U+0001 U+0001, U+0001 → U+0001 U+0002। বাইটের ক্রম একই থাকে, তাই `==`, `<`, `orderBy`, cursor আগের
  মতো; পড়লে আসলটা ফেরে (মুছে ফেলা হয় না — রিভিউ টেস্ট হুবহু ফেরত চায়)। SQL দিয়ে সরাসরি পড়লে escape-করা রূপ
  দেখা যায়। একা surrogate (অর্ধেক ইমোজি) UTF-8-এ হয় না — U+FFFD বসে (Firestore-ও তাই করে); লেখা কখনো ভুল দেয় না।
  সার্ভারের কোড ব্যবহারকারীর লেখা কাটে `lib/text.js`-এর `clip()` দিয়ে (অক্ষরের সীমায়), `slice(0, n)` দিয়ে নয়।
  ডকের ঠিকানায় (id) U+0000 চলে না।
- **undefined:** Firestore ভুল দেয়; এখানে ঘরটা বাদ পড়ে (অ্যারের ভেতরে `null`)।
- **precondition:** `update(..., { lastUpdateTime })` আর `delete({ lastUpdateTime })` ডকের `updated_at`-এর সাথে
  মাইক্রোসেকেন্ড পর্যন্ত মেলায় (না মিললে `9 FAILED_PRECONDITION`); `snapshot.updateTime` আর `writeTime` সেই মাপেই।
  `exists` আগের মতো। অন্য কোনো precondition দিলে ভুল (চুপচাপ বাদ নয়)।
- **transaction:** আসল PostgreSQL transaction। পড়া ডকে লক (না থাকা ডকেও), তাই একসাথে দুটো transaction একই ডক
  বদলালে একটা অপেক্ষা করে নতুন মান দেখে। deadlock হলে আবার চেষ্টা (সর্বোচ্চ ৫ বার)। লেখা জমা হওয়ার পর পড়লে
  (`t.set` তারপর `t.get`) ভুল — Firestore-এর মতো। transaction-এর ভেতরে transaction ছাড়া লেখা (`ref.set`) একই ডকে
  করলে নিজেই আটকে যায় (লক অপেক্ষার সীমা পেরিয়ে ভুল)।
- **transaction-এর কোয়েরি (`t.get(query)`):** phantom নেই — কোয়েরি পড়া থেকে commit পর্যন্ত নতুন কোনো ডক কোয়েরিতে
  "ঢুকতে" পারে না। কিন্তু পুরো কালেকশন আটকে নয়:
  - কোয়েরিতে উপরের-স্তরের ঘরে `==` (না থাকলে `in`) থাকলে প্রথমটা "নোঙর": (কালেকশন, ঘর, মান)-এ exclusive advisory
    লক। প্রতিটা লেখা, লেখার পর ডকের উপরের-স্তরের প্রতিটা ঘর-মানে shared লক নেয় — তাই শুধু যে লেখার পর ডকে
    নোঙর-মান থাকে (যেমন একই `requesterUid`-এর রিকোয়েস্ট) সে অপেক্ষা করে; অন্য ব্যবহারকারীর লেখা চলে।
    আগে থেকেই মেলা ডকগুলো `FOR UPDATE`-এ লক (বদলানো/মোছা অপেক্ষা করে)। কোয়েরিতে নতুন ঢোকা যেকোনো ডকের লেখার
    পরের রূপে নোঙর-মান থাকেই, তাই একটা নোঙরই যথেষ্ট। কোডে সবচেয়ে বাছাইকারী `==` আগে লিখুন
    (`requesterUid == uid`, তারপর `status == 'open'`) — `status` নোঙর হলে সব খোলা রিকোয়েস্টের লেখা অপেক্ষা করত।
  - এক লেখায় এক কালেকশনে ৬৪টার বেশি ঘর-মান (বড় batch) হলে সেগুলোর বদলে একটা কালেকশন-লক (`b:`), যা নোঙরওয়ালা
    কোয়েরির সাথে মেলে — lock table ভরে যায় না।
  - নোঙর না থাকলে (শুধু range/`array-contains`, ছাঁকনি নেই, collectionGroup, map-এর ভেতরের ঘর): আগের মতো পুরো
    কালেকশনে exclusive লক।
  - কেন SERIALIZABLE নয়: PostgreSQL-এর predicate lock শুধু SERIALIZABLE transaction-এর মধ্যে কাজ করে — সব লেখাকে
    SERIALIZABLE করতে হতো, আর GIN index-এ পুরো index-এ লক পড়ে অন্য ব্যবহারকারীর লেখাতেও ভুল আর আবার চেষ্টা হতো।
- **লকের ক্রম:** transaction-এ ডক পড়ার সময়েই তার কালেকশনে shared লক (লেখার সময় নয়), তাই একই ডকগুলো একই ক্রমে
  পড়লে deadlock হয় না। দোকানের পণ্য আর স্টক একসাথে পড়ার কাজ সবসময় আগে পণ্য, তারপর স্টক
  (`shopbooks.js`-এর বিক্রি আর দাম ভরার ক্রন)।
- **সংযোগ:** `statement_timeout` (৬০ সে), `idle_in_transaction_session_timeout` (৩০ সে), সংযোগ পেতে সময় পেরোলে
  কয়েকবার (বাড়তে থাকা অপেক্ষায়) আবার চেষ্টা। হাতে থাকা সংযোগ সার্ভার কেটে দিলে প্রসেস পড়ে না; সংযোগটা ফেলে দেওয়া হয়।
- **সাজানো:** collectionGroup-এ টাই ভাঙে পুরো ঠিকানার লেখা ধরে (Firestore ধরে অংশ ধরে) — খুব অদ্ভুত id না হলে একই।
  অসমতার ঘর orderBy-তে না থাকলে নামের ক্রমে নিজে থেকে যোগ হয়, শেষ orderBy-র দিকে (তারপর id-ও সেই দিকে) — Firestore-এর মতো।
  ±Infinity সাধারণ সংখ্যার মতো (range, orderBy, cursor)। NaN কোনো range-এ পড়ে না, কিন্তু orderBy-তে NaN সব
  সংখ্যার পরে আসে (Firestore-এ আগে); অ্যারে/ম্যাপ দিয়ে সাজানো নিখুঁত নয় (কোডে কোথাও নেই)।
- **`!=` / `not-in`:** ঘর না থাকলে বা `null` হলে বাদ। `not-in`-এর তালিকায় `null` থাকলে কিছুই ফেরে না (Firestore-এর মতো)।
- **নেই:** `onSnapshot` (লাইভ শোনা), `withConverter`, `Filter.or/and`, bulkWriter। সার্ভারের কোড এগুলো ব্যবহার করে না।
- **index লাগে না:** Firestore-এর মতো composite index না থাকলে ভুল হয় না — শুধু ধীর হতে পারে।
- **সীমা নেই:** batch-এ ৫০০ লেখা, ডক ১ MB — এসব সীমা এখানে নেই।
- **অ্যাপ আর ওয়েব:** এই পরিবর্তন শুধু সার্ভারে। অ্যাপ বা ওয়েব যদি সরাসরি Firestore পড়ে/লেখে
  (`firestore.rules` আছে), PostgreSQL-এ সরালে তারা আর নতুন তথ্য দেখবে না — ফেজ ৪-এর আগে সেগুলো সার্ভারের API-তে আনতে হবে।
