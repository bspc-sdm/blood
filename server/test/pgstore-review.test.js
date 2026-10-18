// Independent review of lib/pgstore.js + scripts/migrate-firestore-to-pg.js (branch pg-review).
// Each test pins a confirmed difference from Firestore / firebase-admin and FAILS on the current code.
// Needs a real PostgreSQL: TEST_DATABASE_URL=postgres://... node --test test/pgstore-review.test.js
const test = require('node:test');
const assert = require('node:assert');

if (!process.env.TEST_DATABASE_URL) {
  test('pgstore review (TEST_DATABASE_URL not set — skipped)', { skip: 'TEST_DATABASE_URL not set' }, () => {});
} else {
  run();
}

function run() {
  const PG = require('../lib/pgstore');
  const { freshDb } = require('./support/pgmode');
  const { migrate } = require('../scripts/migrate-firestore-to-pg');
  const { Timestamp } = PG;
  const { db, sync, drop } = freshDb({ poolMax: 6 });
  test.after(async () => { await db.close(); drop(); });

  const ids = (s) => s.docs.map((d) => d.id);
  const clear = () => sync.query('DELETE FROM docs');

  // ---- 1. U+0000 in a string or key: Firestore stores it, jsonb rejects it (22P05) -> 500 on any write
  // carrying user text (e.g. requestcreate.js `note`, community cleanText keeps NUL), and the migration
  // aborts on the first Firestore doc that contains one.
  test('review: a string or map key containing U+0000 can be written, read back and queried', async () => {
    clear();
    await db.doc('nul/1').set({ note: 'a\u0000b', m: { 'k\u0000': 1 } });
    const d = (await db.doc('nul/1').get()).data();
    assert.strictEqual(d.note, 'a\u0000b');
    assert.deepStrictEqual(d.m, { 'k\u0000': 1 });
    assert.deepStrictEqual(ids(await db.collection('nul').where('note', '==', 'a\u0000b').get()), ['1']);
  });

  test('review: migrate copies a Firestore doc containing U+0000 instead of aborting the whole run', async () => {
    clear();
    const data = new Map([['users/a', { name: 'ok' }], ['users/b', { bio: 'x\u0000y' }], ['users/c', { name: 'after' }]]);
    class DocRef {
      constructor(p) { this.path = p; this.id = p.split('/').pop(); }
      async listCollections() { return []; }
    }
    class CollRef {
      constructor(p) { this.path = p; this.id = p; }
      async listDocuments() { return [...data.keys()].filter((k) => k.startsWith(`${this.path}/`)).sort().map((k) => new DocRef(k)); }
    }
    const source = {
      async listCollections() { return [new CollRef('users')]; },
      async getAll(...refs) {
        return refs.map((ref) => ({ ref, exists: data.has(ref.path), data: () => data.get(ref.path), createTime: null, updateTime: null }));
      },
    };
    const counts = await migrate({ source, pgPool: db.pool, opts: { batch: 400 }, log: () => {} });
    assert.deepStrictEqual(counts, { users: 3 });
    assert.strictEqual((await db.doc('users/b').get()).get('bio'), 'x\u0000y');
  });

  // ---- 2. Transaction read after write: firebase-admin throws ("Firestore transactions require all reads to
  // be executed before all writes"); pgstore silently returns the pre-write state.
  test('review: transaction read after a buffered write throws instead of returning stale data', async () => {
    clear();
    const ref = db.doc('tx/raw');
    await assert.rejects(db.runTransaction(async (t) => {
      t.set(ref, { v: 1 });
      const s = await t.get(ref); // pgstore: exists === false although this tx just wrote it
      return s.exists;
    }), /read/i);
  });

  // ---- 3. Preconditions other than `exists` are silently dropped (should apply or throw loudly).
  test('review: delete() with a stale lastUpdateTime precondition does not delete the document', async () => {
    clear();
    const ref = db.doc('pre/1');
    await ref.set({ v: 1 });
    await assert.rejects(ref.delete({ lastUpdateTime: Timestamp.fromMillis(0) }));
    assert.strictEqual((await ref.get()).exists, true, 'document was deleted despite a failing precondition');
  });

  // ---- 4. Implicit order of inequality fields: firebase-admin / backend use the direction of the LAST explicit
  // orderBy (node_modules/@google-cloud/firestore/build/src/reference/query.js createImplicitOrderBy);
  // pgstore._orderKeys always appends them 'asc' (and then __name__ inherits 'asc').
  test('review: where(a > 0).orderBy(b, desc) orders ties on b by a DESC, then id DESC', async () => {
    clear();
    for (const [id, a] of [['x', 1], ['y', 3], ['z', 2], ['w', 3]]) await db.doc(`ord/${id}`).set({ a, b: 5 });
    const got = ids(await db.collection('ord').where('a', '>', 0).orderBy('b', 'desc').get());
    assert.deepStrictEqual(got, ['y', 'w', 'z', 'x']);
  });

  // ---- 5. not-in whose list contains null matches nothing in Firestore.
  test('review: not-in with null in the list returns no documents', async () => {
    clear();
    await db.doc('ni/1').set({ a: 1 });
    await db.doc('ni/2').set({ a: 2 });
    assert.deepStrictEqual(ids(await db.collection('ni').where('a', 'not-in', [null, 1]).get()), []);
  });

  // ---- 6. ±Infinity are ordinary numbers in Firestore; pgstore stores them as {$num} with fs_num() = NULL,
  // so range filters and orderBy drop / misplace them (PG.md only mentions NaN).
  test('review: range filters and orderBy treat ±Infinity as numbers', async () => {
    clear();
    await db.doc('inf/lo').set({ a: -Infinity });
    await db.doc('inf/mid').set({ a: 5 });
    await db.doc('inf/hi').set({ a: Infinity });
    assert.deepStrictEqual(ids(await db.collection('inf').where('a', '>', 100).get()), ['hi']);
    assert.deepStrictEqual(ids(await db.collection('inf').where('a', '<', Infinity).orderBy('a').get()), ['lo', 'mid']);
    assert.deepStrictEqual(ids(await db.collection('inf').orderBy('a').get()), ['lo', 'mid', 'hi']);
  });
}
