#!/usr/bin/env node
// Firestore → PostgreSQL (docs টেবিল): সব কালেকশন আর জানা সাব-কালেকশন (যেকোনো গভীরতায়) পড়ে ব্যাচে লেখা।
// বারবার চালানো নিরাপদ (upsert) — মাঝপথে থামলে আবার চালালেই হয়।
//
//   FIREBASE_SERVICE_ACCOUNT='{...}' DATABASE_URL=postgres://... node scripts/migrate-firestore-to-pg.js
//   ... --verify            শুধু মেলানো: প্রতিটা কালেকশনের ডকের id-র পুরো তালিকা দুই দিকে, আর কিছু ডক হুবহু (--sample 20)
//   ... --only users,tips   শুধু এই top-level কালেকশনগুলো
//   ... --batch 400         এক INSERT-এ (আর Firestore-এর এক পাতায়) কতগুলো ডক
//   ... --parents a,b       কোন কালেকশনের ডকের নিচে সাব-কালেকশন খোঁজা হবে (ডিফল্ট: PARENTS, নিচে)
//   ... --deep              সব ডকের নিচে সাব-কালেকশন খোঁজা (ধীর, খরচ বেশি — অজানা সাব-কালেকশন আছে কিনা দেখতে)
//
// Firestore-এর পড়ার খরচ (বিনামূল্যের প্ল্যানে দিনে ৫০,০০০):
//  - সাধারণ কালেকশন: documentId ধরে পাতায় পাতায় কোয়েরি — প্রতি ডকে ১ পড়া।
//  - PARENTS-এর কালেকশন (নিচে সাব-কালেকশন থাকতে পারে): listDocuments (তথ্যহীন "missing" ডকসহ, প্রতি ডকে ১) +
//    getAll (প্রতি ডকে ১) + প্রতিটা ডকে listCollections (১) — প্রতি ডকে প্রায় ৩।
//  - --verify: প্রতি ডকে ১ (id-র তালিকা, select()) + PARENTS-এ listDocuments/listCollections (প্রতি ডকে ২) + নমুনা।
// বেশি ডক থাকলে --only দিয়ে কয়েক দিনে ভাগ করে চালান। লেখা হয় শুধু PostgreSQL-এ; Firestore-এ কিছু বদলায় না।
//
// মানের রূপ pgstore-এর মতোই (lib/pgstore.js-এর encode): Timestamp → {"$ts":..}, GeoPoint → {"$geo":..},
// DocumentReference → {"$ref": "ঠিকানা"}, Bytes → {"$bytes": base64}। createTime/updateTime → created_at/updated_at।
// লেখায় U+0000 থাকলে ফেরতযোগ্য escape হয় (pgstore পড়লে আসলটা ফেরে); একা surrogate (অর্ধেক ইমোজি) U+FFFD হয় —
// দুটোর সংখ্যাই শেষে লগে আসে।
const path = require('path');
const PG = require(path.join(__dirname, '..', 'lib', 'pgstore'));

const { encode, docParts, textStats } = PG._internal;

// সার্ভারের কোডে যেসব কালেকশনের ডকের নিচে সাব-কালেকশন আছে (requests/{id}/responses|private|meta,
// emergencies/{id}/donors, questions/{id}/answers, shopBooks/{uid}/sales|purchases|days|adjust, shopItems/{uid}/items)।
// নতুন সাব-কালেকশন যোগ হলে এখানেও যোগ করুন — নইলে কপি হবে না (--deep দিয়ে ধরা পড়ে)।
const PARENTS = ['requests', 'emergencies', 'questions', 'shopBooks', 'shopItems'];

function parseArgs(argv) {
  const o = { verify: false, only: null, batch: 400, sample: 20, concurrency: 8, parents: PARENTS, deep: false };
  const list = (v) => String(v || '').split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--verify') o.verify = true;
    else if (a === '--only') o.only = list(argv[++i]);
    else if (a === '--batch') o.batch = Math.max(1, Number(argv[++i]) || 400);
    else if (a === '--sample') o.sample = Math.max(0, Number(argv[++i]) || 0);
    else if (a === '--concurrency') o.concurrency = Math.max(1, Number(argv[++i]) || 8);
    else if (a === '--parents') o.parents = list(argv[++i]);
    else if (a === '--deep') o.deep = true;
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
const isParent = (coll, o) => o.deep || (o.parents || PARENTS).includes(coll.id);

/**
 * একটা কালেকশনের থাকা ডকগুলো, পাতায় পাতায়। o.documentId (FieldPath.documentId()) থাকলে id-র ক্রমে কোয়েরি
 * (প্রতি ডকে ১ পড়া; idsOnly হলে select() — শুধু ঠিকানা); নইলে listDocuments + getAll।
 */
async function* pages(source, coll, o, idsOnly = false) {
  if (o.documentId && typeof coll.orderBy === 'function') {
    let q = coll.orderBy(o.documentId).limit(o.batch);
    if (idsOnly) q = q.select();
    let last = null;
    for (;;) {
      const s = await (last ? q.startAfter(last) : q).get();
      if (s.docs.length) yield s.docs;
      if (s.docs.length < o.batch) return;
      last = s.docs[s.docs.length - 1];
    }
  }
  const refs = await coll.listDocuments();
  for (let i = 0; i < refs.length; i += o.batch) {
    const snaps = await source.getAll(...refs.slice(i, i + o.batch));
    const docs = snaps.filter((s) => s.exists);
    if (docs.length) yield docs;
  }
}

/**
 * কালেকশন আর তার নিচের সবকিছু ঘুরে দেখা। onDocs(snaps) — ডেটাসহ ডক (একবারে কয়েকটা); onCollection(collRef)।
 * সাব-কালেকশন খোঁজা হয় শুধু PARENTS-এর (বা --deep) ডকের নিচে: সেখানে listDocuments() "missing" ডকও দেয়
 * (নিজের তথ্য নেই কিন্তু নিচে সাব-কালেকশন আছে) — তাদের নিচেও ঘোরা হয়।
 */
async function walk(source, coll, opts, { onDocs, onCollection }) {
  if (onCollection) await onCollection(coll);
  if (!isParent(coll, opts)) {
    if (onDocs) for await (const docs of pages(source, coll, opts)) await onDocs(docs);
    return;
  }
  const refs = await coll.listDocuments();
  for (let i = 0; i < refs.length; i += opts.batch) {
    const part = refs.slice(i, i + opts.batch);
    if (onDocs) {
      const snaps = await source.getAll(...part);
      const docs = snaps.filter((s) => s.exists);
      if (docs.length) await onDocs(docs);
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
  const o = { batch: 400, concurrency: 8, only: null, parents: PARENTS, deep: false, ...opts };
  const counts = {};
  const before = { ...textStats };
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
  const nul = textStats.nul - before.nul;
  const sur = textStats.surrogate - before.surrogate;
  if (nul || sur) log(`লেখা বদল: U+0000 escape ${nul}টা ঘরে (ফেরতযোগ্য), একা surrogate → U+FFFD ${sur}টা ঘরে`);
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

/** a-তে আছে, b-তে নেই — সাজানো */
const minus = (a, b) => [...a].filter((x) => !b.has(x)).sort();
const preview = (list) => `${list.slice(0, 5).join(', ')}${list.length > 5 ? ', …' : ''}`;

/**
 * মেলানো: প্রতিটা কালেকশনের (সাব-কালেকশনসহ) ডকের id-র পুরো তালিকা Firestore আর PostgreSQL-এ হুবহু,
 * আর প্রতিটা top-level কালেকশন থেকে PostgreSQL-এর কয়েকটা এলোমেলো ডক Firestore-এর ডকের সাথে হুবহু।
 * ফেরত { ok, problems: [...] }
 */
async function verify({ source, pgPool, opts = {}, log = console.log }) {
  const o = { batch: 400, concurrency: 8, only: null, sample: 20, parents: PARENTS, deep: false, ...opts };
  const problems = [];
  for (const top of await topCollections(source, o.only)) {
    const startProblems = problems.length;
    const colls = [];
    await walk(source, top, o, { onCollection: async (c) => { colls.push(c); } });
    let fsTotal = 0;
    let pgTotal = 0;
    // PostgreSQL-এ এই top-level-এর নিচের সব কালেকশন আর তাদের id
    const pgIds = new Map();
    for (const r of (await pgPool.query(
      'SELECT coll, id FROM docs WHERE coll = $1 OR (coll >= $2 AND coll < $3)',
      [top.id, `${top.id}/`, `${top.id}0`],
    )).rows) {
      if (!pgIds.has(r.coll)) pgIds.set(r.coll, new Set());
      pgIds.get(r.coll).add(r.id);
    }
    const seen = new Set();
    for (const c of colls) {
      if (seen.has(c.path)) continue;
      seen.add(c.path);
      const fsSet = new Set();
      for await (const docs of pages(source, c, o, true)) docs.forEach((d) => fsSet.add(d.ref.id));
      const pgSet = pgIds.get(c.path) || new Set();
      fsTotal += fsSet.size;
      pgTotal += pgSet.size;
      const missing = minus(fsSet, pgSet);
      const extra = minus(pgSet, fsSet);
      if (missing.length) problems.push(`missing ${c.path}: ${missing.length} not in postgres (${preview(missing)})`);
      if (extra.length) problems.push(`extra ${c.path}: ${extra.length} not in firestore (${preview(extra)})`);
    }
    for (const [cp, set] of pgIds) {
      if (!seen.has(cp)) {
        pgTotal += set.size;
        problems.push(`extra ${cp}: ${set.size} not in firestore (${preview([...set].sort())})`);
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
    const bad = problems.length > startProblems;
    log(`${top.id}\tfirestore=${fsTotal}\tpostgres=${pgTotal}\tsample=${sample.length}${bad ? '\tMISMATCH' : ''}`);
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
  // documentId ধরে পাতায় পাতায় কোয়েরি (প্রতি ডকে ১ পড়া)
  opts.documentId = admin.firestore.FieldPath.documentId();
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

module.exports = { migrate, verify, parseArgs, PARENTS };

if (require.main === module) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
