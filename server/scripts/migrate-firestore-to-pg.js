#!/usr/bin/env node
// Firestore → PostgreSQL (docs টেবিল): সব কালেকশন আর সাব-কালেকশন (যেকোনো গভীরতায়) পড়ে ব্যাচে লেখা।
// বারবার চালানো নিরাপদ (upsert) — মাঝপথে থামলে আবার চালালেই হয়।
//
//   FIREBASE_SERVICE_ACCOUNT='{...}' DATABASE_URL=postgres://... node scripts/migrate-firestore-to-pg.js
//   ... --verify            শুধু মেলানো: প্রতিটা কালেকশনের ডকের সংখ্যা, আর কিছু ডক হুবহু (--sample 20)
//   ... --only users,tips   শুধু এই top-level কালেকশনগুলো
//   ... --batch 400         এক INSERT-এ কতগুলো ডক
//
// সাবধান: Firestore-এর প্রতিটা ডক একবার পড়া হয় (বিনামূল্যের প্ল্যানে দিনে ৫০,০০০ পড়া) — বেশি ডক থাকলে
// --only দিয়ে কয়েক দিনে ভাগ করে চালান। লেখা হয় শুধু PostgreSQL-এ; Firestore-এ কিছু বদলায় না।
//
// মানের রূপ pgstore-এর মতোই (lib/pgstore.js-এর encode): Timestamp → {"$ts":..}, GeoPoint → {"$geo":..},
// DocumentReference → {"$ref": "ঠিকানা"}, Bytes → {"$bytes": base64}। createTime/updateTime → created_at/updated_at।
const path = require('path');
const PG = require(path.join(__dirname, '..', 'lib', 'pgstore'));

const { encode, docParts } = PG._internal;

function parseArgs(argv) {
  const o = { verify: false, only: null, batch: 400, sample: 20, concurrency: 8 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--verify') o.verify = true;
    else if (a === '--only') o.only = argv[++i].split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '--batch') o.batch = Math.max(1, Number(argv[++i]) || 400);
    else if (a === '--sample') o.sample = Math.max(0, Number(argv[++i]) || 0);
    else if (a === '--concurrency') o.concurrency = Math.max(1, Number(argv[++i]) || 8);
    else throw new Error(`Unknown argument: ${a}`);
  }
  return o;
}

/** একটার পর একটা নয়, কয়েকটা একসাথে (সীমিত) */
async function pool(items, n, fn) {
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) {
      const k = i++;
      await fn(items[k], k);
    }
  }));
}

const tsToDate = (t) => (t && typeof t.toDate === 'function' ? t.toDate() : null);

/**
 * কালেকশন আর তার নিচের সবকিছু ঘুরে দেখা। onDocs(snaps) — ডেটাসহ ডক (একবারে কয়েকটা); onCollection(collRef)
 * listDocuments() "missing" ডকও দেয় (নিজের তথ্য নেই কিন্তু নিচে সাব-কালেকশন আছে) — তাদের নিচেও ঘোরা হয়।
 */
async function walk(source, coll, opts, { onDocs, onCollection }) {
  if (onCollection) await onCollection(coll);
  const refs = await coll.listDocuments();
  for (let i = 0; i < refs.length; i += opts.batch) {
    const part = refs.slice(i, i + opts.batch);
    if (onDocs) {
      const snaps = await source.getAll(...part);
      await onDocs(snaps.filter((s) => s.exists));
    }
    await pool(part, opts.concurrency, async (ref) => {
      const subs = await ref.listCollections();
      for (const sub of subs) await walk(source, sub, opts, { onDocs, onCollection });
    });
  }
}

async function upsert(pgPool, snaps) {
  if (!snaps.length) return;
  const cols = { path: [], coll: [], grp: [], id: [], data: [], created: [], updated: [] };
  for (const s of snaps) {
    const d = docParts(s.ref.path);
    cols.path.push(d.path);
    cols.coll.push(d.coll);
    cols.grp.push(d.grp);
    cols.id.push(d.id);
    cols.data.push(JSON.stringify(encode(s.data(), 'document')));
    cols.created.push(tsToDate(s.createTime));
    cols.updated.push(tsToDate(s.updateTime));
  }
  await pgPool.query(
    `INSERT INTO docs (path, coll, grp, id, data, created_at, updated_at)
     SELECT p, c, g, i, d, coalesce(ca, clock_timestamp()), coalesce(ua, clock_timestamp())
     FROM unnest($1::text[], $2::text[], $3::text[], $4::text[], $5::text[]::jsonb[], $6::timestamptz[], $7::timestamptz[]) AS u(p, c, g, i, d, ca, ua)
     ON CONFLICT (path) DO UPDATE SET data = EXCLUDED.data, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at`,
    [cols.path, cols.coll, cols.grp, cols.id, cols.data, cols.created, cols.updated],
  );
}

async function topCollections(source, only) {
  const all = await source.listCollections();
  return only ? all.filter((c) => only.includes(c.id)) : all;
}

/** কপি করা; ফেরত { [top-level কালেকশন]: ডকের সংখ্যা } */
async function migrate({ source, pgPool, opts = {}, log = console.log }) {
  const o = { batch: 400, concurrency: 8, only: null, ...opts };
  const counts = {};
  for (const top of await topCollections(source, o.only)) {
    counts[top.id] = 0;
    await walk(source, top, o, {
      onDocs: async (snaps) => {
        await upsert(pgPool, snaps);
        counts[top.id] += snaps.length;
      },
    });
    log(`${top.id}\t${counts[top.id]}`);
  }
  const total = Object.values(counts).reduce((a, b) => a + b, 0);
  log(`মোট\t${total}`);
  return counts;
}

function sameJson(a, b) {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || !a || !b) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && sameJson(a[k], b[k]));
}

/**
 * মেলানো: প্রতিটা কালেকশনের (সাব-কালেকশনসহ) ডকের সংখ্যা Firestore-এর count() আর PostgreSQL-এর সাথে,
 * আর প্রতিটা top-level কালেকশন থেকে PostgreSQL-এর কয়েকটা এলোমেলো ডক Firestore-এর ডকের সাথে হুবহু।
 * ফেরত { ok, problems: [...] }
 */
async function verify({ source, pgPool, opts = {}, log = console.log }) {
  const o = { batch: 400, concurrency: 8, only: null, sample: 20, ...opts };
  const problems = [];
  for (const top of await topCollections(source, o.only)) {
    const colls = [];
    await walk(source, top, o, { onCollection: async (c) => { colls.push(c); } });
    let fsTotal = 0;
    let pgTotal = 0;
    const pgCounts = new Map((await pgPool.query(
      'SELECT coll, count(*)::int AS n FROM docs WHERE coll = $1 OR (coll >= $2 AND coll < $3) GROUP BY coll',
      [top.id, `${top.id}/`, `${top.id}0`],
    )).rows.map((r) => [r.coll, r.n]));
    const seen = new Set();
    for (const c of colls) {
      if (seen.has(c.path)) continue;
      seen.add(c.path);
      const n = (await c.count().get()).data().count;
      const m = pgCounts.get(c.path) || 0;
      fsTotal += n;
      pgTotal += m;
      if (n !== m) problems.push(`count ${c.path}: firestore=${n} postgres=${m}`);
    }
    for (const [cp, m] of pgCounts) {
      if (!seen.has(cp)) {
        pgTotal += m;
        problems.push(`count ${cp}: firestore=0 postgres=${m}`);
      }
    }
    const sample = o.sample ? (await pgPool.query(
      'SELECT path, data FROM docs WHERE path >= $1 AND path < $2 ORDER BY random() LIMIT $3',
      [`${top.id}/`, `${top.id}0`, o.sample],
    )).rows : [];
    if (sample.length) {
      const snaps = await source.getAll(...sample.map((r) => source.doc(r.path)));
      snaps.forEach((s, i) => {
        if (!s.exists) problems.push(`doc ${sample[i].path}: missing in firestore`);
        else if (!sameJson(JSON.parse(JSON.stringify(encode(s.data(), 'document'))), sample[i].data)) problems.push(`doc ${sample[i].path}: data differs`);
      });
    }
    log(`${top.id}\tfirestore=${fsTotal}\tpostgres=${pgTotal}\tsample=${sample.length}${fsTotal === pgTotal ? '' : '\tMISMATCH'}`);
  }
  problems.forEach((p) => log(`  ✗ ${p}`));
  log(problems.length ? `মেলেনি: ${problems.length}টা` : 'সব মিলেছে');
  return { ok: !problems.length, problems };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL সেট করা নেই');
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error('FIREBASE_SERVICE_ACCOUNT সেট করা নেই');
  const admin = require('firebase-admin');
  const sa = JSON.parse(raw.trim().startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8'));
  admin.initializeApp({ credential: admin.credential.cert(sa) });
  const source = admin.firestore();
  const store = new PG.PgFirestore({});
  await store.ensureSchema();
  try {
    if (opts.verify) {
      const r = await verify({ source, pgPool: store.pool, opts });
      process.exitCode = r.ok ? 0 : 1;
    } else {
      await migrate({ source, pgPool: store.pool, opts });
    }
  } finally {
    await store.close();
  }
}

module.exports = { migrate, verify, parseArgs };

if (require.main === module) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
