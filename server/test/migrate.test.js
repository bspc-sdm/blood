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
    count() {
      return { get: async () => ({ data: () => ({ count: [...data.keys()].filter((k) => k.startsWith(`${this.path}/`) && k.split('/').length === this.path.split('/').length + 1).length }) }) };
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

  data.set('users/u1', { name: 'রহিম', createdAt: new Timestamp(1700000000, 5), geo: new GeoPoint(24.75, 90.4), n: 1 });
  data.set('users/u2', { name: "O'Brien", tags: ['a', 'b'], nested: { $odd: 1, deep: { x: null } } });
  data.set('requests/r1', { status: 'open', owner: new DocumentReference('users/u1'), blob: Buffer.from('hi') });
  data.set('requests/r1/responses/u2', { donorUid: 'u2', at: 5 });
  data.set('requests/r1/responses/u2/notes/n1', { text: 'গভীর' });
  // তথ্যহীন ডক (ghost/g1) — নিচে সাব-কালেকশন আছে
  for (let i = 0; i < 7; i++) data.set(`ghost/g1/items/i${i}`, { i });

  test('সব কালেকশন আর সাব-কালেকশন কপি হয়, রূপ বদলে; দুবার চালালেও একই', async () => {
    const lines = [];
    const counts = await migrate({ source, pgPool: db.pool, opts: { batch: 3 }, log: (l) => lines.push(l) });
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
    await migrate({ source, pgPool: db.pool, opts: {}, log: () => {} });
    assert.strictEqual(Number(sync.query('SELECT count(*)::int AS n FROM docs').rows[0].n), before);
    assert.strictEqual((await db.doc('users/u1').get()).get('n'), 2);
    assert.ok(reads > 0);
  });

  test('--verify: মিললে ok; PostgreSQL-এ বাড়তি/কম/বদলানো ডক ধরা পড়ে', async () => {
    const quiet = () => {};
    assert.strictEqual((await verify({ source, pgPool: db.pool, opts: { sample: 50 }, log: quiet })).ok, true);
    sync.query("UPDATE docs SET data = jsonb_set(data, '{name}', '\"বদল\"') WHERE path = 'users/u2'");
    sync.query("DELETE FROM docs WHERE path = 'ghost/g1/items/i3'");
    sync.query(`INSERT INTO docs (path, coll, grp, id, data) VALUES ('requests/r1/extra/x', 'requests/r1/extra', 'extra', 'x', '{}')`);
    const r = await verify({ source, pgPool: db.pool, opts: { sample: 50 }, log: quiet });
    assert.strictEqual(r.ok, false);
    assert.ok(r.problems.includes('doc users/u2: data differs'), r.problems.join('\n'));
    assert.ok(r.problems.includes('count ghost/g1/items: firestore=7 postgres=6'), r.problems.join('\n'));
    assert.ok(r.problems.includes('count requests/r1/extra: firestore=0 postgres=1'), r.problems.join('\n'));
    // আবার কপি করলে ঠিক হয় (বাড়তিটা হাতে মুছতে হয় — কপি কিছু মোছে না)
    await migrate({ source, pgPool: db.pool, opts: {}, log: quiet });
    sync.query("DELETE FROM docs WHERE path = 'requests/r1/extra/x'");
    assert.strictEqual((await verify({ source, pgPool: db.pool, opts: { sample: 50 }, log: quiet })).ok, true);
  });
}
