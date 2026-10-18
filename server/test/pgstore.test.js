// lib/pgstore.js (Firestore-এর মতো ডাটাবেস, PostgreSQL-এ): কোয়েরির নিয়ম, transaction, batch, collectionGroup, recursiveDelete।
// আসল PostgreSQL লাগে: TEST_DATABASE_URL=postgres://... (না থাকলে বাদ যায়)। প্রতিবার নতুন schema, শেষে মুছে যায়।
const test = require('node:test');
const assert = require('node:assert');

// শুধু TEST_DATABASE_URL — ভুল করে আসল ডাটাবেসে (DATABASE_URL) টেস্ট না চলে
const URL_SET = !!process.env.TEST_DATABASE_URL;
if (!URL_SET) {
  test('pgstore (TEST_DATABASE_URL নেই — বাদ)', { skip: 'TEST_DATABASE_URL not set' }, () => {});
} else {
  run();
}

function run() {
  const PG = require('../lib/pgstore');
  const { freshDb } = require('./support/pgmode');
  const { FieldValue, FieldPath, Timestamp, GeoPoint } = PG;
  const { db, sync, drop } = freshDb({ poolMax: 12 });
  test.after(async () => { await db.close(); drop(); });

  const ids = (s) => s.docs.map((d) => d.id);
  const clear = () => sync.query('DELETE FROM docs');

  test('মান যেমন লেখা তেমন পড়া: Timestamp, GeoPoint, reference, bytes, NaN, $-চাবি, নেস্টেড', async () => {
    clear();
    const ts = new Timestamp(1700000000, 123456789);
    await db.doc('v/1').set({
      ts, date: new Date(1700000000123), geo: new GeoPoint(23.8, 90.4), ref: db.doc('users/u1'), bytes: Buffer.from([0, 1, 255]),
      nan: NaN, inf: -Infinity, $weird: { $ts: 'not a timestamp', $$x: 1 }, arr: [{ a: 1 }, 'x', null, 2.5], empty: {}, nul: null,
      quote: "it's \"quoted\" -- '; DROP TABLE docs; --", bn: 'বাংলা',
    });
    const d = (await db.doc('v/1').get()).data();
    assert.ok(d.ts instanceof Timestamp && d.ts.isEqual(ts));
    assert.strictEqual(d.date.toMillis(), 1700000000123);
    assert.ok(d.geo instanceof GeoPoint && d.geo.latitude === 23.8 && d.geo.longitude === 90.4);
    assert.strictEqual(d.ref.path, 'users/u1');
    assert.deepStrictEqual([...d.bytes], [0, 1, 255]);
    assert.ok(Number.isNaN(d.nan));
    assert.strictEqual(d.inf, -Infinity);
    assert.deepStrictEqual(d.$weird, { $ts: 'not a timestamp', $$x: 1 });
    assert.deepStrictEqual(d.arr, [{ a: 1 }, 'x', null, 2.5]);
    assert.deepStrictEqual([d.empty, d.nul, d.bn], [{}, null, 'বাংলা']);
    assert.strictEqual(d.quote, "it's \"quoted\" -- '; DROP TABLE docs; --");
    // JSON-এ firebase-admin-এর Timestamp-এর মতোই রূপ
    assert.strictEqual(JSON.stringify(ts), '{"_seconds":1700000000,"_nanoseconds":123456789}');
    // $-চাবির ভেতরের ঘরও খোঁজা যায়
    assert.deepStrictEqual(ids(await db.collection('v').where('$weird.$ts', '==', 'not a timestamp').get()), ['1']);
    // অদ্ভুত ঘরের নাম (উদ্ধৃতি চিহ্ন) parameter হিসেবে যায়
    await db.doc('v/2').set({ "a'b": { 'c"d': 5 } });
    assert.deepStrictEqual(ids(await db.collection('v').where("a'b.c\"d", '==', 5).get()), ['2']);
    assert.deepStrictEqual(ids(await db.collection('v').where('quote', '==', "it's \"quoted\" -- '; DROP TABLE docs; --").get()), ['1']);
  });

  test('set merge, update (ডট-ঠিকানা), FieldValue — Firestore-এর নিয়মে', async () => {
    clear();
    const ref = db.doc('m/1');
    await ref.set({ a: { x: 1, y: 2 }, n: 1, tags: ['a'], objs: [{ k: 1 }], gone: 1 });
    await ref.set({ a: { y: 3, z: 4 }, n: FieldValue.increment(2), gone: FieldValue.delete(), t: FieldValue.serverTimestamp() }, { merge: true });
    let d = (await ref.get()).data();
    assert.deepStrictEqual(d.a, { x: 1, y: 3, z: 4 });
    assert.strictEqual(d.n, 3);
    assert.ok(!('gone' in d));
    assert.ok(d.t instanceof Timestamp && Math.abs(d.t.toMillis() - Date.now()) < 5000);
    await ref.update({ 'a.x': FieldValue.delete(), 'a.deep.er': 1, tags: FieldValue.arrayUnion('a', 'b'), objs: FieldValue.arrayRemove({ k: 1 }) });
    d = (await ref.get()).data();
    assert.deepStrictEqual(d.a, { y: 3, z: 4, deep: { er: 1 } });
    assert.deepStrictEqual(d.tags, ['a', 'b']);
    assert.deepStrictEqual(d.objs, []);
    // update: পুরো map বদলায় (মেশে না)
    await ref.update({ a: { only: true } });
    assert.deepStrictEqual((await ref.get()).get('a'), { only: true });
    // update / create-এর শর্ত
    await assert.rejects(db.doc('m/none').update({ x: 1 }), (e) => e.code === 5);
    await assert.rejects(ref.create({ x: 1 }), (e) => e.code === 6);
    // set (merge ছাড়া) পুরো বদল; increment না থাকা ঘরে = সংখ্যা
    await ref.set({ c: FieldValue.increment(5), u: FieldValue.arrayUnion(1, 1, 2) });
    assert.deepStrictEqual((await ref.get()).data(), { c: 5, u: [1, 2] });
    // add() নতুন ২০ অক্ষরের id
    const added = await db.collection('m').add({ z: 1 });
    assert.match(added.id, /^[A-Za-z0-9]{20}$/);
    assert.strictEqual(added.parent.id, 'm');
  });

  test('where: ধরন মিলিয়ে তুলনা, ঘর না থাকলে বাদ, সংখ্যা সংখ্যার মতো, লেখা বাইটের মতো', async () => {
    clear();
    const vals = { n2: 2, n10: 10, n15: 1.5, s10: '10', s2: '2', t: true, f: false, nul: null, ts: Timestamp.fromMillis(5), arr: [1], map: { a: 1 } };
    for (const [id, v] of Object.entries(vals)) await db.doc(`w/${id}`).set({ v, k: id });
    await db.doc('w/missing').set({ k: 'missing' });
    const q = (op, v) => db.collection('w').where('v', op, v).get().then(ids);
    assert.deepStrictEqual(await q('<', 10), ['n15', 'n2']);
    assert.deepStrictEqual(await q('>=', 2), ['n2', 'n10']); // অসমতার ঘরে নিজে থেকে সাজানো
    assert.deepStrictEqual(await q('>', '1'), ['s10', 's2']);
    assert.deepStrictEqual(await q('<', '2'), ['s10']); // '10' < '2' লেখা হিসেবে
    assert.deepStrictEqual(await q('==', 10), ['n10']);
    assert.deepStrictEqual(await q('==', 10.0), ['n10']);
    assert.deepStrictEqual(await q('==', '10'), ['s10']);
    assert.deepStrictEqual(await q('==', null), ['nul']);
    assert.deepStrictEqual(await q('==', false), ['f']);
    assert.deepStrictEqual(await q('==', { a: 1 }), ['map']);
    assert.deepStrictEqual(await q('<', Timestamp.fromMillis(10)), ['ts']);
    assert.deepStrictEqual(await q('in', [2, '2', null]), ['n2', 'nul', 's2']);
    assert.deepStrictEqual(await q('!=', 2), ['f', 't', 'n15', 'n10', 'ts', 's10', 's2', 'arr', 'map']);
    assert.deepStrictEqual(await q('not-in', [2, 10, '2']), ['f', 't', 'n15', 'ts', 's10', 'arr', 'map']);
    // count, limit সহ
    assert.strictEqual((await db.collection('w').count().get()).data().count, 12);
    assert.strictEqual((await db.collection('w').where('v', '>', 0).limit(2).count().get()).data().count, 2);
  });

  test('array-contains (হুবহু মিল), array-contains-any, নেস্টেড ঘর', async () => {
    clear();
    await db.doc('a/1').set({ tags: ['x', 'y'], objs: [{ a: 1, b: 2 }], p: { q: { r: 'deep' } } });
    await db.doc('a/2').set({ tags: ['y'], objs: [{ a: 1 }] });
    await db.doc('a/3').set({ tags: 'x' });
    const q = (f, op, v) => db.collection('a').where(f, op, v).get().then(ids);
    assert.deepStrictEqual(await q('tags', 'array-contains', 'x'), ['1']);
    assert.deepStrictEqual(await q('tags', 'array-contains', 'y'), ['1', '2']);
    // {a:1} শুধু হুবহু {a:1}-এ মেলে, {a:1,b:2}-তে নয়
    assert.deepStrictEqual(await q('objs', 'array-contains', { a: 1 }), ['2']);
    assert.deepStrictEqual(await q('tags', 'array-contains-any', ['x', 'z']), ['1']);
    assert.deepStrictEqual(await q('p.q.r', '==', 'deep'), ['1']);
    assert.deepStrictEqual(await q(new FieldPath('p', 'q', 'r'), '==', 'deep'), ['1']);
  });

  test('orderBy: ধরনের ক্রম, ঘর না থাকলে বাদ, সমান হলে id, desc, orderBy ছাড়া id-র ক্রম', async () => {
    clear();
    const vals = { b: 'b', B: 'B', a: 'a', tilde: '~', n10: 10, n9: 9, nul: null, t: true, ts: Timestamp.fromMillis(1), arr: [0], map: { z: 1 }, dupA: 5, dupB: 5 };
    for (const [id, v] of Object.entries(vals)) await db.doc(`o/${id}`).set({ v });
    await db.doc('o/zzz').set({ other: 1 });
    const asc = ids(await db.collection('o').orderBy('v').get());
    assert.deepStrictEqual(asc, ['nul', 't', 'dupA', 'dupB', 'n9', 'n10', 'ts', 'B', 'a', 'b', 'tilde', 'arr', 'map']);
    const desc = ids(await db.collection('o').orderBy('v', 'desc').get());
    assert.deepStrictEqual(desc, [...asc].reverse());
    assert.deepStrictEqual(ids(await db.collection('o').get()), [...Object.keys(vals), 'zzz'].sort());
    assert.deepStrictEqual(ids(await db.collection('o').orderBy('v', 'desc').limit(3).get()), ['map', 'arr', 'tilde']);
    assert.deepStrictEqual(ids(await db.collection('o').orderBy('v').limitToLast(2).get()), ['arr', 'map']);
    // অসমতার ঘরে নিজে থেকেই সাজানো
    assert.deepStrictEqual(ids(await db.collection('o').where('v', '>', 'A').get()), ['B', 'a', 'b', 'tilde']);
    // documentId দিয়ে range (inbox-এর গোনা)
    const id = FieldPath.documentId();
    assert.deepStrictEqual(ids(await db.collection('o').where(id, '>=', 'd').where(id, '<', 'n9').get()), ['dupA', 'dupB', 'map', 'n10']);
    assert.strictEqual((await db.collection('o').where(id, '>=', 'n').where(id, '<', 'o').count().get()).data().count, 3);
  });

  test('cursor: startAt/endAt (geohash), startAfter(ডক) দিয়ে পাতা, endBefore, offset, select', async () => {
    clear();
    const hashes = ['wh0', 'wh2kc', 'wh2kc1', 'wh2kd', 'wh2k~', 'wh3', 'WH2kc'];
    for (const [i, h] of hashes.entries()) await db.doc(`g/d${i}`).set({ geohash: h, n: i });
    const inBox = ids(await db.collection('g').orderBy('geohash').startAt('wh2k').endAt('wh2k~').get());
    assert.deepStrictEqual(inBox, ['d1', 'd2', 'd3', 'd4']);
    assert.deepStrictEqual(ids(await db.collection('g').orderBy('geohash').startAfter('wh2kc').endBefore('wh2k~').get()), ['d2', 'd3']);
    assert.deepStrictEqual(ids(await db.collection('g').orderBy('geohash', 'desc').startAt('wh2kd').endAt('wh2kc1').get()), ['d3', 'd2']);
    // সমান মানের মাঝে পাতা ভাগ: startAfter(snapshot) id দিয়ে টাই ভাঙে
    clear();
    for (let i = 0; i < 7; i++) await db.doc(`p/x${i}`).set({ at: i < 4 ? 100 : 200 });
    const seen = [];
    let last = null;
    for (;;) {
      let q = db.collection('p').orderBy('at', 'desc').limit(3);
      if (last) q = q.startAfter(last);
      const s = await q.get();
      seen.push(...ids(s));
      if (s.size < 3) break;
      last = s.docs[s.docs.length - 1];
    }
    assert.deepStrictEqual(seen, ['x6', 'x5', 'x4', 'x3', 'x2', 'x1', 'x0']);
    assert.deepStrictEqual(ids(await db.collection('p').orderBy('at').offset(2).limit(2).get()), ['x2', 'x3']);
    // startAt(মান, id)
    assert.deepStrictEqual(ids(await db.collection('p').orderBy('at').startAt(100, 'x2').limit(3).get()), ['x2', 'x3', 'x4']);
    // select(): শুধু বলা ঘর; select() খালি: ডক আছে, তথ্য নেই
    await db.doc('p/x0').set({ at: 100, big: 'x'.repeat(1000), keep: { a: 1, b: 2 } });
    const sel = await db.collection('p').where('at', '==', 100).select('keep.a').limit(1).get();
    assert.deepStrictEqual(sel.docs[0].data(), { keep: { a: 1 } });
    const none = await db.collection('p').select().get();
    assert.strictEqual(none.size, 7);
    assert.deepStrictEqual(none.docs[0].data(), {});
  });

  test('গরম কোয়েরিতে expression index কাজে লাগে (geohash, at desc)', async () => {
    const plan = (q) => {
      const { text, values } = q._sql('rows', false);
      sync.query('BEGIN');
      try {
        sync.query('SET LOCAL enable_seqscan = off');
        sync.query('SET LOCAL enable_bitmapscan = off');
        return sync.query(`EXPLAIN ${text}`, values).rows.map((r) => r['QUERY PLAN']).join('\n');
      } finally {
        sync.query('ROLLBACK');
      }
    };
    assert.match(plan(db.collection('users').orderBy('donorGeo').startAt('O+|wh2').endAt('O+|wh2~').limit(50)), /docs_str_donorGeo/);
    assert.match(plan(db.collection('requests').orderBy('geoOpen').startAt('wh2').endAt('wh2~').limit(50)), /docs_str_geoOpen/);
    assert.match(plan(db.collection('shopBooks/s1/sales').where('at', '<', 5).orderBy('at', 'desc').limit(50)), /docs_num_at/);
    assert.match(plan(db.collection('questions').orderBy('createdAt', 'desc').limit(20)), /docs_num_createdAt/);
  });

  test('collectionGroup আর ref.parent', async () => {
    clear();
    await db.doc('requests/r1/responses/u1').set({ donorUid: 'u1', status: 'accepted' });
    await db.doc('requests/r2/responses/u1').set({ donorUid: 'u1', status: 'donated' });
    await db.doc('requests/r2/responses/u2').set({ donorUid: 'u2' });
    await db.doc('emergencies/e1/responses/u1').set({ donorUid: 'u1' });
    await db.doc('responses/top').set({ donorUid: 'u1' });
    await db.doc('requests/r1/other/u1').set({ donorUid: 'u1' });
    const s = await db.collectionGroup('responses').where('donorUid', '==', 'u1').get();
    assert.deepStrictEqual(s.docs.map((d) => d.ref.path), ['emergencies/e1/responses/u1', 'requests/r1/responses/u1', 'requests/r2/responses/u1', 'responses/top']);
    assert.deepStrictEqual(s.docs.map((d) => d.ref.parent.parent && d.ref.parent.parent.id), ['e1', 'r1', 'r2', null]);
    const parents = await db.getAll(...s.docs.map((d) => d.ref.parent.parent).filter(Boolean));
    assert.deepStrictEqual(parents.map((d) => [d.id, d.exists]), [['e1', false], ['r1', false], ['r2', false]]);
    assert.strictEqual((await db.collectionGroup('responses').where('status', 'in', ['accepted', 'donated']).count().get()).data().count, 2);
    assert.deepStrictEqual((await db.doc('requests/r1').listCollections()).map((c) => c.id), ['other', 'responses']);
  });

  test('recursiveDelete: ডক আর তার নিচের সব, একই শুরুর অন্য ডক থাকে', async () => {
    clear();
    for (const p of ['q/a', 'q/a/answers/1', 'q/a/answers/1/likes/x', 'q/a/meta/m', 'q/a_1', 'q/a2', 'q/a2/answers/1', 'q/ab/answers/9', 'qq/a']) {
      await db.doc(p).set({ p });
    }
    await db.recursiveDelete(db.doc('q/a'));
    const left = sync.query('SELECT path FROM docs ORDER BY path').rows.map((r) => r.path);
    assert.deepStrictEqual(left, ['q/a2', 'q/a2/answers/1', 'q/a_1', 'q/ab/answers/9', 'qq/a']);
    await db.recursiveDelete(db.collection('q'));
    assert.deepStrictEqual(sync.query('SELECT path FROM docs').rows.map((r) => r.path), ['qq/a']);
  });

  test('batch: সব বা কিছুই না', async () => {
    clear();
    await db.doc('b/keep').set({ v: 1 });
    const b = db.batch();
    b.set(db.doc('b/new'), { v: 1 });
    b.update(db.doc('b/keep'), { v: 2 });
    b.update(db.doc('b/missing'), { v: 1 }); // নেই → পুরো batch বাতিল
    await assert.rejects(b.commit(), (e) => e.code === 5);
    assert.strictEqual((await db.doc('b/new').get()).exists, false);
    assert.strictEqual((await db.doc('b/keep').get()).get('v'), 1);
    // একই ডকে কয়েকটা লেখা ক্রমে
    const b2 = db.batch();
    b2.set(db.doc('b/x'), { n: 1 }).update(db.doc('b/x'), { n: FieldValue.increment(1) }).delete(db.doc('b/keep'));
    await b2.commit();
    assert.strictEqual((await db.doc('b/x').get()).get('n'), 2);
    assert.strictEqual((await db.doc('b/keep').get()).exists, false);
  });

  test('transaction: একসাথে ২০টা increment সব জমা হয় (না থাকা ডকেও)', async () => {
    clear();
    const ref = db.doc('c/counter');
    await Promise.all(Array.from({ length: 20 }, () => db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const n = s.exists ? s.get('n') : 0;
      await new Promise((r) => setTimeout(r, 2));
      t.set(ref, { n: n + 1 });
    })));
    assert.strictEqual((await ref.get()).get('n'), 20);
    // transaction ছাড়া FieldValue.increment-ও হারায় না
    await Promise.all(Array.from({ length: 30 }, () => db.doc('c/fv').set({ n: FieldValue.increment(1) }, { merge: true })));
    assert.strictEqual((await db.doc('c/fv').get()).get('n'), 30);
  });

  test('transaction: "আশা করা" অবস্থা — একজনই পায়, বাকিরা নতুন অবস্থা দেখে থামে; কোয়েরি পড়াও লক হয়', async () => {
    clear();
    const slot = db.doc('c/slot');
    await slot.set({ status: 'free' });
    const results = await Promise.allSettled(Array.from({ length: 8 }, (_, i) => db.runTransaction(async (t) => {
      const s = await t.get(slot);
      if (s.get('status') !== 'free') throw new Error('taken');
      t.update(slot, { status: 'taken', by: i });
      return i;
    })));
    assert.strictEqual(results.filter((r) => r.status === 'fulfilled').length, 1);
    assert.ok(results.filter((r) => r.status === 'rejected').every((r) => r.reason.message === 'taken'));
    // ভুল হলে কিছুই লেখা হয় না
    await assert.rejects(db.runTransaction(async (t) => { t.set(db.doc('c/never'), { a: 1 }); throw new Error('stop'); }), /stop/);
    assert.strictEqual((await db.doc('c/never').get()).exists, false);
    // কোয়েরি দিয়ে পড়া (t.get(query)) — সর্বোচ্চ ২ জন "accepted"
    await db.doc('r/1').set({ cap: 2 });
    const tryAccept = (uid) => db.runTransaction(async (t) => {
      const all = await t.get(db.collection('r/1/responses').where('status', '==', 'accepted'));
      const lock = await t.get(db.doc('r/1'));
      if (all.size >= lock.get('cap')) return false;
      t.set(db.doc(`r/1/responses/${uid}`), { status: 'accepted' });
      return true;
    });
    const ok = await Promise.all(['u1', 'u2', 'u3', 'u4', 'u5'].map(tryAccept));
    assert.strictEqual(ok.filter(Boolean).length, 2);
  });

  test('transaction: উল্টো ক্রমে লক (deadlock) হলে আবার চেষ্টা করে দুটোই শেষ হয়', async () => {
    clear();
    const A = db.doc('d/a');
    const B = db.doc('d/b');
    await A.set({ n: 0 });
    await B.set({ n: 0 });
    let tries = 0;
    const swap = (x, y) => db.runTransaction(async (t) => {
      tries++;
      const sx = await t.get(x);
      await new Promise((r) => setTimeout(r, 50));
      const sy = await t.get(y);
      t.update(x, { n: sx.get('n') + 1 });
      t.update(y, { n: sy.get('n') + 1 });
    });
    await Promise.all([swap(A, B), swap(B, A)]);
    assert.strictEqual((await A.get()).get('n'), 2);
    assert.strictEqual((await B.get()).get('n'), 2);
    assert.ok(tries >= 3, `one transaction retried (tries=${tries})`);
  });

  test('ভুল ঠিকানা বা অপারেটর ধরা পড়ে', () => {
    assert.throws(() => db.doc('onlycollection'));
    assert.throws(() => db.collection('a/b'));
    assert.throws(() => db.doc('a//b'));
    assert.throws(() => db.collection('a').where('x', 'like', 1));
    assert.throws(() => db.collection('a').where('x', 'in', []));
  });
}
