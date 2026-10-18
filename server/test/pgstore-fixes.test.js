// pg-review-এর ভুলগুলো সারানোর পরের টেস্ট (test/pgstore-review.test.js-এর পাশে, আরও খুঁটিনাটি):
// U+0000 আর অর্ধেক ইমোজি, transaction-এ লেখার পর পড়া, lastUpdateTime precondition, অসমতার নিজে থেকে সাজানোর দিক,
// not-in-এ null, ±Infinity, transaction-এর কোয়েরির লক (পুরো কালেকশন নয়), লকের ক্রম, pool আর schema।
// clip() (lib/text.js) সবসময় চলে; বাকিগুলো শুধু TEST_DATABASE_URL থাকলে।
const test = require('node:test');
const assert = require('node:assert');
const { clip } = require('../lib/text');

test('clip: slice-এর মতো সীমা, কিন্তু ইমোজি অর্ধেক নয়', () => {
  assert.strictEqual(clip('ab😀', 3), 'ab');
  assert.strictEqual(clip('ab😀', 4), 'ab😀');
  assert.strictEqual(clip('ab😀c', 4), 'ab😀');
  assert.strictEqual(clip('রক্তদান', 3), 'রক্ত'.slice(0, 3));
  assert.strictEqual(clip('short', 300), 'short');
  assert.strictEqual(clip('😀', 1), '');
  assert.strictEqual(clip(12345, 2), '12');
  for (let n = 0; n < 12; n++) assert.ok(clip('a😀😀b😀', n).isWellFormed(), `n=${n}`);
});

if (!process.env.TEST_DATABASE_URL) {
  test('pgstore fixes (TEST_DATABASE_URL নেই — বাদ)', { skip: 'TEST_DATABASE_URL not set' }, () => {});
} else {
  run();
}

function run() {
  const PG = require('../lib/pgstore');
  const { freshDb, testDbUrl } = require('./support/pgmode');
  const { FieldPath, Timestamp } = PG;
  const { db, sync, schema, drop } = freshDb({ poolMax: 8 });
  test.after(async () => { await db.close(); drop(); });

  const ids = (s) => s.docs.map((d) => d.id);
  const clear = () => sync.query('DELETE FROM docs');
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---- ১. U+0000, U+0001, অর্ধেক ইমোজি
  test('U+0000/U+0001: মান আর চাবিতে ফেরতযোগ্য, বাইটের ক্রম ঠিক; অর্ধেক ইমোজি U+FFFD, লেখা কখনো ভুল নয়', async () => {
    clear();
    const vals = { e: '', z: '\u0000', za: '\u0000a', o: '\u0001', oo: '\u0001\u0001', a: 'a', an: 'a\u0000', b: 'b' };
    for (const [id, s] of Object.entries(vals)) await db.doc(`s/${id}`).set({ s });
    // Firestore-এর ক্রম = UTF-8 বাইটের ক্রম
    const want = Object.keys(vals).sort((x, y) => Buffer.compare(Buffer.from(vals[x]), Buffer.from(vals[y])));
    assert.deepStrictEqual(ids(await db.collection('s').orderBy('s').get()), want);
    assert.deepStrictEqual(ids(await db.collection('s').where('s', '>', '\u0000').where('s', '<', 'a').orderBy('s').get()), ['za', 'o', 'oo']);
    assert.deepStrictEqual(ids(await db.collection('s').where('s', 'in', ['\u0001', 'a\u0000']).get()).sort(), ['an', 'o']);
    for (const [id, s] of Object.entries(vals)) assert.strictEqual((await db.doc(`s/${id}`).get()).get('s'), s);

    // চাবিতে: set, update (FieldPath), merge, mergeFields, select, where
    const ref = db.doc('k/1');
    await ref.set({ 'a\u0000b': 1, '\u0001': { '$x\u0000': 2 } });
    await ref.update(new FieldPath('a\u0000b'), 5);
    await ref.set({ m: { 'q\u0000': 'r\u0000' } }, { merge: true });
    await ref.set({ n: { '\u0000': 1, keep: 2 } }, { mergeFields: [new FieldPath('n', '\u0000')] });
    assert.deepStrictEqual((await ref.get()).data(), { 'a\u0000b': 5, '\u0001': { '$x\u0000': 2 }, m: { 'q\u0000': 'r\u0000' }, n: { '\u0000': 1 } });
    assert.strictEqual((await ref.get()).get(new FieldPath('m', 'q\u0000')), 'r\u0000');
    assert.deepStrictEqual(ids(await db.collection('k').where(new FieldPath('a\u0000b'), '==', 5).get()), ['1']);
    const sel = await db.collection('k').select(new FieldPath('\u0001', '$x\u0000')).get();
    assert.deepStrictEqual(sel.docs[0].data(), { '\u0001': { '$x\u0000': 2 } });

    // একা surrogate: U+FFFD (Firestore-ও UTF-8-এ তাই করে), মানে আর চাবিতে
    await db.doc('k/2').set({ t: 'x\ud83d', [`y\udc00`]: 1, ok: 'ab😀' });
    assert.deepStrictEqual((await db.doc('k/2').get()).data(), { t: 'x�', 'y�': 1, ok: 'ab😀' });
    await db.runTransaction(async (t) => { t.update(db.doc('k/2'), { t: '\u0000\ud800' }); });
    assert.strictEqual((await db.doc('k/2').get()).get('t'), '\u0000�');
  });

  // ---- ২. transaction-এ লেখার পর পড়া
  test('transaction: লেখা জমার পর get/getAll/কোয়েরি — ভুল, কিছুই লেখা হয় না', async () => {
    clear();
    for (const read of [(t) => t.get(db.doc('x/1')), (t) => t.getAll(db.doc('x/1')), (t) => t.get(db.collection('x'))]) {
      await assert.rejects(db.runTransaction(async (t) => {
        t.set(db.doc('x/2'), { v: 1 });
        await read(t);
      }), /all reads to be executed before all writes/);
    }
    assert.strictEqual((await db.doc('x/2').get()).exists, false);
    // আগে পড়া, পরে লেখা চলে
    await db.runTransaction(async (t) => { await t.get(db.doc('x/1')); t.set(db.doc('x/2'), { v: 1 }); });
    assert.strictEqual((await db.doc('x/2').get()).get('v'), 1);
  });

  // ---- ৩. lastUpdateTime precondition
  test('lastUpdateTime: মিললে delete/update হয়, না মিললে FAILED_PRECONDITION; writeTime = updateTime; অজানা precondition ভুল', async () => {
    clear();
    const ref = db.doc('pre/1');
    const w = await ref.set({ v: 1 });
    const s1 = await ref.get();
    assert.ok(s1.updateTime.isEqual(w.writeTime), 'writeTime ডকের updateTime-এর সমান');
    assert.ok(s1.createTime.isEqual(s1.updateTime));
    // update: মিললে চলে
    const w2 = await ref.update({ v: 2 }, { lastUpdateTime: s1.updateTime });
    const s2 = await ref.get();
    assert.strictEqual(s2.get('v'), 2);
    assert.ok(s2.updateTime.isEqual(w2.writeTime));
    assert.ok(s2.createTime.isEqual(s1.createTime), 'createTime বদলায় না');
    // পুরোনো সময়: update আর delete দুটোই আটকায়, ডক থাকে
    await assert.rejects(ref.update({ v: 3 }, { lastUpdateTime: s1.updateTime }), (e) => e.code === 9);
    await assert.rejects(ref.update('v', 3, { lastUpdateTime: s1.updateTime }), (e) => e.code === 9);
    await assert.rejects(ref.delete({ lastUpdateTime: s1.updateTime }), (e) => e.code === 9);
    await assert.rejects(db.batch().delete(ref, { lastUpdateTime: s1.updateTime }).commit(), (e) => e.code === 9);
    await assert.rejects(db.runTransaction(async (t) => { await t.get(ref); t.delete(ref, { lastUpdateTime: s1.updateTime }); }), (e) => e.code === 9);
    assert.strictEqual((await ref.get()).get('v'), 2);
    // কোয়েরির snapshot-এর updateTime দিয়েও
    const q = (await db.collection('pre').get()).docs[0];
    await ref.delete({ lastUpdateTime: q.updateTime });
    assert.strictEqual((await ref.get()).exists, false);
    // না থাকা ডকে lastUpdateTime: delete আটকায়; exists: true আগের মতো NOT_FOUND
    await assert.rejects(ref.delete({ lastUpdateTime: s2.updateTime }), (e) => e.code === 9);
    await assert.rejects(ref.delete({ exists: true }), (e) => e.code === 5);
    await ref.delete(); // precondition ছাড়া: কিছু না থাকলেও ঠিক আছে
    // যা সমর্থিত নয় তা চুপচাপ বাদ নয়
    await assert.rejects(ref.delete({ updateTime: s2.updateTime }), /not supported/);
    assert.throws(() => db.batch().update(ref, { v: 1 }, { lastUpdateTime: 5 }), /Timestamp/);
    await assert.rejects(ref.delete({ exists: true, lastUpdateTime: s2.updateTime }), /only one/);
    assert.throws(() => db.batch().delete(ref, { updateTime: s2.updateTime }), /not supported/);
  });

  // ---- ৪. অসমতা-ঘরের নিজে থেকে সাজানো: শেষ orderBy-র দিকে
  test('অসমতার বাড়তি সাজানো ঘর (নামের ক্রমে) আর id শেষ orderBy-র দিকে; orderBy না থাকলে asc', async () => {
    clear();
    const rows = [['p', 1, 1], ['q', 1, 2], ['r', 2, 1], ['s', 2, 2], ['t', 2, 2]];
    for (const [id, a, c] of rows) await db.doc(`o/${id}`).set({ a, c, b: 5 });
    assert.deepStrictEqual(ids(await db.collection('o').where('c', '>', 0).where('a', '>', 0).orderBy('b', 'desc').get()), ['t', 's', 'r', 'q', 'p']);
    assert.deepStrictEqual(ids(await db.collection('o').where('c', '>', 0).where('a', '>', 0).orderBy('b').get()), ['p', 'q', 'r', 's', 't']);
    assert.deepStrictEqual(ids(await db.collection('o').where('a', '!=', 9).get()), ['p', 'q', 'r', 's', 't']);
    assert.deepStrictEqual(ids(await db.collection('o').where('a', '>', 0).orderBy('b', 'desc').limit(2).get()), ['t', 's']);
    // cursor-ও একই সাজানোয়
    assert.deepStrictEqual(ids(await db.collection('o').where('a', '>', 0).orderBy('b', 'desc').startAfter(5, 2, 't').get()), ['s', 'r', 'q', 'p']);
  });

  // ---- ৫. not-in null
  test('not-in: তালিকায় null থাকলে কিছুই নয়; না থাকলে আগের মতো', async () => {
    clear();
    await db.doc('n/1').set({ a: 1 });
    await db.doc('n/2').set({ a: 2 });
    await db.doc('n/3').set({ a: null });
    assert.deepStrictEqual(ids(await db.collection('n').where('a', 'not-in', [null]).get()), []);
    assert.deepStrictEqual(ids(await db.collection('n').where('a', 'not-in', [2, null]).get()), []);
    assert.deepStrictEqual(ids(await db.collection('n').where('a', 'not-in', [2]).get()), ['1']);
    assert.deepStrictEqual(ids(await db.collection('n').where('a', '!=', null).get()), ['1', '2']);
  });

  // ---- ৬. ±Infinity
  test('±Infinity সংখ্যার মতো: range, desc, cursor, ==; NaN কোনো range-এ নয়', async () => {
    clear();
    for (const [id, a] of [['lo', -Infinity], ['m', -5], ['z', 0], ['p', 1e308], ['hi', Infinity], ['nan', NaN], ['s', 'str']]) await db.doc(`i/${id}`).set({ a });
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '>=', -Infinity).orderBy('a').get()), ['lo', 'm', 'z', 'p', 'hi']);
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '<=', Infinity).orderBy('a', 'desc').get()), ['hi', 'p', 'z', 'm', 'lo']);
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '>', 1e308).get()), ['hi']);
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '<', -1e308).get()), ['lo']);
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '==', Infinity).get()), ['hi']);
    assert.deepStrictEqual(ids(await db.collection('i').orderBy('a').startAfter(1).endAt(Infinity).get()), ['p', 'hi']);
    assert.deepStrictEqual(ids(await db.collection('i').where('a', '>', -Infinity).where('a', '<', Infinity).orderBy('a').get()), ['m', 'z', 'p']);
    assert.ok((await db.collection('i').where('a', '==', NaN).get()).size === 1);
  });

  // ---- ৭খ. transaction-এর কোয়েরি: শুধু মেলা শর্তে লক
  test('transaction-এর == কোয়েরি অন্য মানের লেখা আটকায় না; কোয়েরিতে "ঢোকা" লেখা commit পর্যন্ত অপেক্ষা করে', async () => {
    clear();
    await db.doc('rq/old1').set({ requesterUid: 'u1', status: 'closed' });
    await db.doc('rq/other').set({ requesterUid: 'u2', status: 'open' });
    const events = [];
    let release;
    const hold = new Promise((r) => { release = r; });
    let started;
    const ready = new Promise((r) => { started = r; });
    const tx = db.runTransaction(async (t) => {
      const mine = await t.get(db.collection('rq').where('requesterUid', '==', 'u1').where('status', '==', 'open'));
      events.push(`tx saw ${mine.size}`);
      started();
      await hold;
      t.set(db.doc('rq/new1'), { requesterUid: 'u1', status: 'open' });
    }).then(() => events.push('tx commit'));
    await ready;
    // অন্য ব্যবহারকারী: সাথে সাথে
    await db.doc('rq/u2b').set({ requesterUid: 'u2', status: 'open' });
    await db.doc('rq/other').update({ status: 'closed', note: 'x' });
    events.push('u2 writes done');
    // u1-এর নতুন খোলা রিকোয়েস্ট, আর বন্ধটা আবার খোলা — দুটোই tx শেষ হওয়া পর্যন্ত অপেক্ষা
    const w1 = db.doc('rq/new2').set({ requesterUid: 'u1', status: 'open' }).then(() => events.push('u1 insert'));
    const w2 = db.doc('rq/old1').update({ status: 'open' }).then(() => events.push('u1 reopen'));
    await sleep(150);
    events.push('release');
    release();
    await Promise.all([tx, w1, w2]);
    assert.deepStrictEqual(events.slice(0, 4), ['tx saw 0', 'u2 writes done', 'release', 'tx commit'], events.join(' | '));
    assert.deepStrictEqual(events.slice(4).sort(), ['u1 insert', 'u1 reopen']);
  });

  test('transaction-এর কোয়েরি: একসাথে অনেকে "সর্বোচ্চ ৩টা খোলা" — কখনো বেশি নয় (phantom নেই)', async () => {
    clear();
    const create = (i) => db.runTransaction(async (t) => {
      const mine = await t.get(db.collection('lim').where('uid', '==', 'u').where('status', '==', 'open'));
      if (mine.size >= 3) return false;
      t.set(db.doc(`lim/r${i}`), { uid: 'u', status: 'open', n: i });
      return true;
    });
    const ok = await Promise.all(Array.from({ length: 10 }, (_, i) => create(i)));
    assert.strictEqual(ok.filter(Boolean).length, 3);
    assert.strictEqual((await db.collection('lim').get()).size, 3);
  });

  test('transaction-এর কোয়েরি: ছাঁকনি ছাড়া বা range হলে আগের মতো পুরো কালেকশনে লক; বড় batch-ও শর্ত-লক মানে', async () => {
    clear();
    let release;
    const hold = new Promise((r) => { release = r; });
    let started;
    const ready = new Promise((r) => { started = r; });
    const events = [];
    const tx = db.runTransaction(async (t) => {
      await t.get(db.collection('all').where('n', '>', 0));
      started();
      await hold;
    }).then(() => events.push('tx'));
    await ready;
    const w = db.doc('all/x').set({ n: -1 }).then(() => events.push('write'));
    await sleep(100);
    release();
    await Promise.all([tx, w]);
    assert.deepStrictEqual(events, ['tx', 'write']);

    // বড় batch (এক কালেকশনে অনেক ঘর-মান): একটা 'b:' লক — == কোয়েরির সাথে তবুও মেলে
    let rel2;
    const hold2 = new Promise((r) => { rel2 = r; });
    let st2;
    const ready2 = new Promise((r) => { st2 = r; });
    const ev = [];
    const tx2 = db.runTransaction(async (t) => {
      await t.get(db.collection('big').where('g', '==', 'A'));
      st2();
      await hold2;
    }).then(() => ev.push('tx'));
    await ready2;
    const b = db.batch();
    for (let i = 0; i < 100; i++) b.set(db.doc(`big/d${i}`), { g: i === 50 ? 'A' : `x${i}` });
    const wb = b.commit().then(() => ev.push('batch'));
    await sleep(100);
    rel2();
    await Promise.all([tx2, wb]);
    assert.deepStrictEqual(ev, ['tx', 'batch']);
  });

  // ---- ৭ক. লকের ক্রম: বিক্রি (পণ্য → স্টক) আর ক্রন (পণ্যের কোয়েরি → স্টক) একসাথে — deadlock নয়
  test('লকের ক্রম: পণ্য-আগে-স্টক বিক্রি আর ক্রন একসাথে চললে deadlock/আবার চেষ্টা হয় না', async () => {
    clear();
    for (let i = 0; i < 5; i++) await db.doc(`shopItems/s1/items/i${i}`).set({ qty: 10, price: i });
    await db.doc('pharmacyStock/s1').set({ prices: {} });
    let tries = 0;
    const sale = () => db.runTransaction(async (t) => {
      tries++;
      const items = await t.getAll(...[0, 1, 2].map((i) => db.doc(`shopItems/s1/items/i${i}`)));
      await sleep(20);
      const st = await t.get(db.doc('pharmacyStock/s1'));
      items.forEach((s) => t.update(s.ref, { qty: s.get('qty') - 1 }));
      t.update(st.ref, { n: (st.get('n') || 0) + 1 });
    });
    const cron = () => db.runTransaction(async (t) => {
      tries++;
      const items = await t.get(db.collection('shopItems/s1/items').limit(100));
      await sleep(20);
      const st = await t.get(db.doc('pharmacyStock/s1'));
      t.update(st.ref, { prices: Object.fromEntries(items.docs.map((d) => [d.id, d.get('price')])) });
    });
    const t0 = Date.now();
    await Promise.all([sale(), cron(), sale(), cron(), sale()]);
    assert.strictEqual(tries, 5, 'কেউ আবার চেষ্টা করেনি');
    assert.ok(Date.now() - t0 < 1000, 'deadlock ধরার ~১ সেকেন্ড অপেক্ষা হয়নি');
    assert.strictEqual((await db.doc('pharmacyStock/s1').get()).get('n'), 3);
    assert.strictEqual((await db.doc('shopItems/s1/items/i0').get()).get('qty'), 7);
  });

  // ---- ১০. pool, schema
  test('pool: statement_timeout আর idle_in_transaction_session_timeout বসানো; খোলা transaction-এ বসে থাকলে সংযোগ কাটে', async () => {
    const r = await db.pool.query("SELECT current_setting('statement_timeout') AS st, current_setting('idle_in_transaction_session_timeout') AS idle");
    assert.deepStrictEqual(r.rows[0], { st: '1min', idle: '30s' });
    process.env.PG_IDLE_TX_TIMEOUT_MS = '200';
    const s = new PG.PgFirestore({ connectionString: testDbUrl(), schema, autoSchema: false, poolMax: 2 });
    try {
      await assert.rejects(s.runTransaction(async (t) => {
        await t.get(s.doc('idle/1'));
        await sleep(500);
        t.set(s.doc('idle/1'), { v: 1 });
      }));
      assert.strictEqual((await s.doc('idle/1').get()).exists, false);
    } finally {
      delete process.env.PG_IDLE_TX_TIMEOUT_MS;
      await s.close();
    }
  });

  test('pool: সংযোগ পেতে দেরি (সব ব্যস্ত) হলে কয়েকবার অপেক্ষা করে আবার চেষ্টা', async () => {
    const s = new PG.PgFirestore({ connectionString: testDbUrl(), schema, autoSchema: false, poolMax: 1, connectionTimeoutMillis: 100 });
    try {
      const busy = await s.pool.connect();
      setTimeout(() => busy.release(), 250);
      await s.doc('cr/1').set({ v: 1 }); // pool.connect ১০০ms-এ সময় পেরোয় — আবার চেষ্টায় পায়
      assert.strictEqual((await s.doc('cr/1').get()).get('v'), 1);
      const busy2 = await s.pool.connect();
      setTimeout(() => busy2.release(), 250);
      assert.strictEqual((await s.collection('cr').get()).size, 1); // pool.query-ও
    } finally {
      await s.close();
    }
  });

  test('schema: সংস্করণ মিললে DDL চলে না — চলমান লেখার লকে আটকায় না; না মিললে একবার চালায়', async () => {
    const SyncPgMod = require('./support/pgsync');
    const other = new SyncPgMod.SyncPg({ connectionString: testDbUrl(), options: `-c search_path=${schema}` });
    // চলমান লেখা (ROW EXCLUSIVE) — CREATE INDEX (SHARE) এর সাথে আটকাত
    other.query('BEGIN');
    other.query('LOCK TABLE docs IN ROW EXCLUSIVE MODE');
    const s = new PG.PgFirestore({ connectionString: testDbUrl(), schema, autoSchema: true, poolMax: 2 });
    try {
      const t0 = Date.now();
      await s.ensureSchema();
      assert.ok(Date.now() - t0 < 2000);
      other.query('ROLLBACK');
      // পুরোনো সংস্করণ: আবার চালায় আর নতুনটা বসায়
      sync.query("UPDATE fs_meta SET value = '0' WHERE key = 'schema_version'");
      const s2 = new PG.PgFirestore({ connectionString: testDbUrl(), schema, autoSchema: true, poolMax: 2 });
      await s2.ensureSchema();
      await s2.close();
      assert.notStrictEqual(sync.query("SELECT value FROM fs_meta WHERE key = 'schema_version'").rows[0].value, '0');
    } finally {
      try { other.query('ROLLBACK'); } catch { /* আগেই */ }
      other.close();
      await s.close();
    }
  });
}
