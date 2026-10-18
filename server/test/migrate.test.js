// scripts/migrate-firestore-to-pg.js: নকল Firestore (firebase-admin-এর মতো listCollections/listDocuments/getAll/count)
// থেকে আসল PostgreSQL-এ — সাব-কালেকশন, তথ্যহীন ডকের নিচের সাব-কালেকশন, Timestamp/GeoPoint/Reference/Bytes,
// দুবার চালালেও একই, আর --verify মেলায় ও ভুল ধরে। TEST_DATABASE_URL না থাকলে বাদ।
const test = require('node:test');
const assert = require('node:assert');

if (!process.env.TEST_DATABASE_URL) {
  test('migrate (TEST_DATABASE_URL নেই — বাদ)', { skip: 'TEST_DATABASE_URL not set' }, () => {});
} else {
  run();
}

function run() {
  const { Timestamp, GeoPoint } = require('@google-cloud/firestore');
  const PG = require('../lib/pgstore');
  const { freshDb } = require('./support/pgmode');
  const { migrate, verify } = require('../scripts/migrate-firestore-to-pg');
  const { db, sync, drop } = freshDb();
  test.after(async () => { await db.close(); drop(); });

  // ---- নকল Firestore (firebase-admin-এর যতটুকু স্ক্রিপ্ট ব্যবহার করে)
  const data = new Map(); // path -> data
  class DocumentReference {
    constructor(p) { this.path = p; this.id = p.split('/').pop(); }
    async listCollections() {
      const ids = new Set();
      for (const k of data.keys()) if (k.startsWith(`${this.path}/`)) ids.add(k.slice(this.path.length + 1).split('/')[0]);
      return [...ids].sort().map((id) => new CollectionReference(`${this.path}/${id}`));
    }
  }
  class CollectionReference {
    constructor(p) { this.path = p; this.id = p.split('/').pop(); }
    async listDocuments() {
      const ids = new Set();
      const depth = this.path.split('/').length;
      for (const k of data.keys()) if (k.startsWith(`${this.path}/`)) ids.add(k.split('/').slice(0, depth + 1).join('/'));
      return [...ids].sort().map((p) => new DocumentReference(p));
    }
    // firebase-admin-এর মতো: orderBy(documentId()).limit(n).startAfter(snap).select().get() — প্রতি ফেরত ডকে ১ পড়া
    orderBy(f) {
      assert.strictEqual(f, DOC_ID);
      return new FakeQuery(this.path, { limit: Infinity, after: null, idsOnly: false });
    }
  }
  const DOC_ID = { documentId: true };
  class FakeQuery {
    constructor(p, o) { this.path = p; this.o = o; }
    limit(n) { return new FakeQuery(this.path, { ...this.o, limit: n }); }
    select() { return new FakeQuery(this.path, { ...this.o, idsOnly: true }); }
    startAfter(snap) { return new FakeQuery(this.path, { ...this.o, after: snap.id }); }
    async get() {
      const depth = this.path.split('/').length + 1;
      const docs = [...data.keys()].filter((k) => k.startsWith(`${this.path}/`) && k.split('/').length === depth).sort()
        .filter((k) => this.o.after === null || k.split('/').pop() > this.o.after).slice(0, this.o.limit)
        .map((k) => ({ id: k.split('/').pop(), ref: new DocumentReference(k), exists: true, data: () => (this.o.idsOnly ? {} : data.get(k)), createTime: Timestamp.fromMillis(1600000000000), updateTime: Timestamp.fromMillis(1700000000000) }));
      reads += Math.max(1, docs.length);
      return { docs, size: docs.length };
    }
  }
  let reads = 0;
  const source = {
    doc: (p) => new DocumentReference(p),
    async listCollections() {
      return [...new Set([...data.keys()].map((k) => k.split('/')[0]))].sort().map((id) => new CollectionReference(id));
    },
    async getAll(...refs) {
      return refs.map((ref) => {
        reads++;
        const d = data.get(ref.path);
        return { ref, exists: d !== undefined, data: () => d, createTime: Timestamp.fromMillis(1600000000000), updateTime: Timestamp.fromMillis(1700000000000) };
      });
    },
  };

  // requests আর responses-এর নিচে সাব-কালেকশন আছে, ghost-এর নিচেও (তথ্যহীন ডক)
  const OPTS = { parents: ['requests', 'responses', 'ghost'] };

  data.set('users/u1', { name: 'রহিম', createdAt: new Timestamp(1700000000, 5), geo: new GeoPoint(24.75, 90.4), n: 1 });
  data.set('users/u2', { name: "O'Brien", tags: ['a', 'b'], nested: { $odd: 1, deep: { x: null } } });
  data.set('requests/r1', { status: 'open', owner: new DocumentReference('users/u1'), blob: Buffer.from('hi') });
  data.set('requests/r1/responses/u2', { donorUid: 'u2', at: 5 });
  data.set('requests/r1/responses/u2/notes/n1', { text: 'গভীর' });
  // তথ্যহীন ডক (ghost/g1) — নিচে সাব-কালেকশন আছে
  for (let i = 0; i < 7; i++) data.set(`ghost/g1/items/i${i}`, { i });

  test('সব কালেকশন আর সাব-কালেকশন কপি হয়, রূপ বদলে; দুবার চালালেও একই', async () => {
    const lines = [];
    const counts = await migrate({ source, pgPool: db.pool, opts: { batch: 3, ...OPTS }, log: (l) => lines.push(l) });
    assert.deepStrictEqual(counts, { ghost: 7, requests: 3, users: 2 });
    assert.ok(lines.some((l) => l.startsWith('ghost\t7')));
    const u1 = (await db.doc('users/u1').get()).data();
    assert.ok(u1.createdAt instanceof PG.Timestamp && u1.createdAt.seconds === 1700000000 && u1.createdAt.nanoseconds === 5);
    assert.ok(u1.geo instanceof PG.GeoPoint && u1.geo.latitude === 24.75);
    const r1 = (await db.doc('requests/r1').get()).data();
    assert.strictEqual(r1.owner.path, 'users/u1');
    assert.strictEqual(r1.blob.toString(), 'hi');
    assert.deepStrictEqual((await db.doc('users/u2').get()).data(), { name: "O'Brien", tags: ['a', 'b'], nested: { $odd: 1, deep: { x: null } } });
    assert.strictEqual((await db.doc('requests/r1/responses/u2/notes/n1').get()).get('text'), 'গভীর');
    assert.strictEqual((await db.doc('ghost/g1').get()).exists, false);
    assert.strictEqual((await db.collection('ghost/g1/items').count().get()).data().count, 7);
    assert.strictEqual((await db.collectionGroup('responses').where('donorUid', '==', 'u2').get()).size, 1);
    // Firestore-এর createTime/updateTime
    const row = sync.query("SELECT extract(epoch FROM created_at)::bigint AS c, extract(epoch FROM updated_at)::bigint AS u FROM docs WHERE path = 'users/u1'").rows[0];
    assert.deepStrictEqual([Number(row.c), Number(row.u)], [1600000000, 1700000000]);
    // আবার চালালে একই সংখ্যা (upsert)
    const before = Number(sync.query('SELECT count(*)::int AS n FROM docs').rows[0].n);
    data.set('users/u1', { ...data.get('users/u1'), n: 2 });
    await migrate({ source, pgPool: db.pool, opts: OPTS, log: () => {} });
    assert.strictEqual(Number(sync.query('SELECT count(*)::int AS n FROM docs').rows[0].n), before);
    assert.strictEqual((await db.doc('users/u1').get()).get('n'), 2);
    assert.ok(reads > 0);
  });

  test('--verify: মিললে ok; PostgreSQL-এ বাড়তি/কম/বদলানো ডক ধরা পড়ে', async () => {
    const quiet = () => {};
    const V = { sample: 50, ...OPTS };
    assert.strictEqual((await verify({ source, pgPool: db.pool, opts: V, log: quiet })).ok, true);
    sync.query("UPDATE docs SET data = jsonb_set(data, '{name}', '\"বদল\"') WHERE path = 'users/u2'");
    sync.query("DELETE FROM docs WHERE path = 'ghost/g1/items/i3'");
    sync.query(`INSERT INTO docs (path, coll, grp, id, data) VALUES ('requests/r1/extra/x', 'requests/r1/extra', 'extra', 'x', '{}')`);
    const r = await verify({ source, pgPool: db.pool, opts: V, log: quiet });
    assert.strictEqual(r.ok, false);
    assert.ok(r.problems.includes('doc users/u2: data differs'), r.problems.join('\n'));
    assert.ok(r.problems.includes('missing ghost/g1/items: 1 not in postgres (i3)'), r.problems.join('\n'));
    assert.ok(r.problems.includes('extra requests/r1/extra: 1 not in firestore (x)'), r.problems.join('\n'));
    // আবার কপি করলে ঠিক হয় (বাড়তিটা হাতে মুছতে হয় — কপি কিছু মোছে না)
    await migrate({ source, pgPool: db.pool, opts: OPTS, log: quiet });
    sync.query("DELETE FROM docs WHERE path = 'requests/r1/extra/x'");
    assert.strictEqual((await verify({ source, pgPool: db.pool, opts: V, log: quiet })).ok, true);
  });

  test('--verify হুবহু id মেলায়: সংখ্যা এক হলেও একটা ডক বদলে অন্যটা থাকলে ধরা পড়ে (নমুনা ছাড়া)', async () => {
    const V = { sample: 0, ...OPTS };
    sync.query("DELETE FROM docs WHERE path = 'ghost/g1/items/i5'");
    sync.query(`INSERT INTO docs (path, coll, grp, id, data) VALUES ('ghost/g1/items/zz', 'ghost/g1/items', 'items', 'zz', '{}')`);
    const r = await verify({ source, pgPool: db.pool, opts: V, log: () => {} });
    assert.deepStrictEqual(r.problems.sort(), ['extra ghost/g1/items: 1 not in firestore (zz)', 'missing ghost/g1/items: 1 not in postgres (i5)']);
    sync.query("DELETE FROM docs WHERE path = 'ghost/g1/items/zz'");
    await migrate({ source, pgPool: db.pool, opts: OPTS, log: () => {} });
    assert.strictEqual((await verify({ source, pgPool: db.pool, opts: V, log: () => {} })).ok, true);
  });

  test('পড়ার খরচ: সাধারণ কালেকশন প্রতি ডকে ১ পড়া; সাব-কালেকশন শুধু জানা parent-এর নিচে খোঁজা (--deep-এ সবখানে)', async () => {
    sync.query('DELETE FROM docs');
    for (let i = 0; i < 10; i++) data.set(`tips/t${i}`, { i });
    data.set('tips/t3/hidden/h1', { secret: 1 });
    let lists = 0;
    const orig = DocumentReference.prototype.listCollections;
    DocumentReference.prototype.listCollections = function listCollections() { lists++; return orig.call(this); };
    try {
      reads = 0;
      const counts = await migrate({ source, pgPool: db.pool, opts: { batch: 4, only: ['tips'], documentId: DOC_ID }, log: () => {} });
      assert.deepStrictEqual(counts, { tips: 10 });
      // ১০টা ডক, পাতা ৪+৪+২: ঠিক ১০ পড়া; tips parent নয় বলে listCollections একবারও নয়, নিচের hidden কপি হয় না
      assert.strictEqual(reads, 10);
      assert.strictEqual(lists, 0);
      assert.strictEqual((await db.doc('tips/t3/hidden/h1').get()).exists, false);
      // --deep: সব ডকের নিচে খোঁজে
      await migrate({ source, pgPool: db.pool, opts: { only: ['tips'], deep: true, documentId: DOC_ID }, log: () => {} });
      assert.strictEqual((await db.doc('tips/t3/hidden/h1').get()).get('secret'), 1);
      assert.strictEqual(lists, 11); // ১০টা tips + নিচের hidden/h1
    } finally {
      DocumentReference.prototype.listCollections = orig;
      for (const k of [...data.keys()]) if (k.startsWith('tips/')) data.delete(k);
    }
  });

  test('U+0000 আর অর্ধেক ইমোজি: কপি থামে না, লগে কতটা ঘর বদলাল', async () => {
    data.set('notes/n1', { text: 'a\u0000b', bad: 'x\ud83d', ok: 'সাধারণ' });
    try {
      const lines = [];
      await migrate({ source, pgPool: db.pool, opts: { only: ['notes'] }, log: (l) => lines.push(l) });
      const d = (await db.doc('notes/n1').get()).data();
      assert.deepStrictEqual(d, { text: 'a\u0000b', bad: 'x\ufffd', ok: 'সাধারণ' });
      assert.ok(lines.some((l) => /U\+0000 escape 1টা ঘরে.*U\+FFFD 1টা ঘরে/.test(l)), lines.join('\n'));
      // verify মেলায় রাখার রূপে (একই encode) — তাই মেলে
      assert.strictEqual((await verify({ source, pgPool: db.pool, opts: { only: ['notes'] }, log: () => {} })).ok, true);
    } finally {
      data.delete('notes/n1');
    }
  });
}
