// ফার্মেসি খোঁজায় "২৪ ঘণ্টা খোলা" ছাঁকনি (পারভেজ, ১৯ অক্টোবর ২০২৬; পরীক্ষার গাইড S-04): রাতে রোগী যেন খোলা দোকান পান।
// ২৪ ঘণ্টা জানা যায় দোকানের নিজের ঘর open24 থেকে (প্রোফাইলে "২৪ ঘণ্টা খোলা" — তখন সময়ের ঘর থাকে না)।
// ঘড়ি ঢাকার রাত ৩টায় বসানো — যখনই চালান একই ফল।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const clock = require('./support/clock');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const K = require(path.join(SERVER, 'lib/care'));
const M = require(path.join(SERVER, 'lib/memcache'));
const { db } = F.firebase;

const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: uid ? { authorization: `Bearer ${uid}` } : {}, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const user = (uid) => F.users.set(uid, { uid, email: `${uid}@example.com` });
/** x-এ যা দেওয়া হয় তা-ই দোকানে (open24 না দিলে ঘরটাই থাকে না — পুরোনো দোকানের মতো) */
async function shop(id, x = {}) {
  user(id);
  await db.doc(`users/${id}`).set({ name: `মালিক ${id}` });
  await db.doc(`pharmacies/${id}`).set({
    name: `দোকান ${id}`, ownerUid: id, status: 'approved', district: DISTRICT, upazilaId: 466, phone: '01711111111',
    address: 'চরপাড়া', lat: 24.75, lng: 90.4, closedDays: [], ...x,
  });
}
const ids = (r) => r.items.map((p) => p.id).sort();

const H24 = 'ph24real0001'; // আসল, ২৪ ঘণ্টা
const DAYSHOP = 'phdayreal001'; // আসল, সকাল ৯টা – রাত ১০টা
const OLD = 'pholdreal001'; // আসল, পুরোনো দোকান: open24 ঘরই নেই, সকাল ৮টা – রাত ১১টা
const PAUSED = 'ph24paused01'; // আসল, ২৪ ঘণ্টা কিন্তু মালিক সাময়িক বন্ধ রেখেছেন
const DEMO24 = 'demo-test-ph24'; // ডেমো, ২৪ ঘণ্টা

async function seedAll() {
  F.store.clear();
  M.clear('');
  await shop(H24, { open24: true, openAt: '', closeAt: '' });
  await shop(DAYSHOP, { open24: false, openAt: '09:00', closeAt: '22:00' });
  await shop(OLD, { openAt: '08:00', closeAt: '23:00' });
  await shop(PAUSED, { open24: true, openAt: '', closeAt: '', closedUntil: Date.now() + 3 * 24 * 3600 * 1000 });
  await shop(DEMO24, { open24: true, openAt: '', closeAt: '' });
}

test('২৪ ঘণ্টার দোকান রাত ৩টায় (ঢাকা) "এখন খোলা"; সময়ের দোকান নয়', () => {
  try {
    const t = clock.setDhaka('2026-10-20 03:00');
    assert.strictEqual(K.pharmacyOpenNow({ open24: true, openAt: '', closeAt: '' }, t), true);
    // সময়ের ঘরে পুরোনো কিছু রয়ে গেলেও ২৪ ঘণ্টা জিতে যায়
    assert.strictEqual(K.pharmacyOpenNow({ open24: true, openAt: '09:00', closeAt: '22:00' }, t), true);
    assert.strictEqual(K.pharmacyOpenNow({ open24: false, openAt: '09:00', closeAt: '22:00' }, t), false);
    assert.strictEqual(K.pharmacyOpenNow({ openAt: '08:00', closeAt: '23:00' }, t), false, 'ঘর নেই = ২৪ ঘণ্টা নয়');
    // মালিক সাময়িক বন্ধ রাখলে ২৪ ঘণ্টারও বন্ধ
    assert.strictEqual(K.pharmacyOpenNow({ open24: true, closedUntil: t + 3600 * 1000 }, t), false);
  } finally {
    clock.reset();
  }
});

test('তালিকা: open24 দিলে শুধু ২৪ ঘণ্টার দোকান; ঘর আর openNow ঠিক; অন্য ফিল্টার আর সাজানো আগের মতো', async () => {
  try {
    clock.setDhaka('2026-10-20 03:00');
    await seedAll();
    const all = await ok(null, 'pharmacies', { district: DISTRICT });
    assert.deepStrictEqual(ids(all), [DEMO24, H24, PAUSED, DAYSHOP, OLD].sort(), 'ফিল্টার ছাড়া সব (অতিথির তালিকায় ডেমো দোকানও দেখায়, আগের মতো)');
    const by = Object.fromEntries(all.items.map((p) => [p.id, p]));
    assert.strictEqual(by[H24].open24, true);
    assert.strictEqual(by[H24].openNow, true, 'রাত ৩টায় ২৪ ঘণ্টার দোকান খোলা');
    assert.strictEqual(by[DAYSHOP].open24, false);
    assert.strictEqual(by[DAYSHOP].openNow, false);
    assert.strictEqual(by[OLD].open24, false, 'পুরোনো দোকান (ঘর নেই) → false');
    assert.strictEqual(by[PAUSED].openNow, false);
    // খোলা আগে (সাজানো আগের মতো)
    const firstClosed = all.items.findIndex((p) => !p.openNow);
    assert.ok(all.items.slice(firstClosed).every((p) => !p.openNow), 'খোলা দোকান আগে');

    // মেমোরির তালিকা আগের ডাকেই ভরা — তবু ছাঁকা ফল আলাদা (ক্যাশে ফিল্টার মেশে না)
    const only24 = await ok(null, 'pharmacies', { district: DISTRICT, open24: true });
    assert.deepStrictEqual(ids(only24), [DEMO24, H24, PAUSED].sort());
    assert.ok(only24.items.every((p) => p.open24));
    // সাময়িক বন্ধ ২৪ ঘণ্টার দোকান তালিকায় থাকে, কিন্তু "বন্ধ"
    assert.strictEqual(only24.items.find((p) => p.id === PAUSED).openNow, false);

    // দুটো একসাথে: ২৪ ঘণ্টা আর এখন খোলা
    const both = await ok(null, 'pharmacies', { district: DISTRICT, open24: true, openNow: true });
    assert.deepStrictEqual(ids(both), [DEMO24, H24].sort());

    // ফিল্টার তুলে নিলে আবার সব (ক্যাশ থেকে, ছাঁকা ফল আটকে থাকে না)
    assert.strictEqual((await ok(null, 'pharmacies', { district: DISTRICT })).items.length, 5);
    // শুধু "এখন খোলা": রাত ৩টায় সময়ের দোকান বাদ
    assert.deepStrictEqual(ids(await ok(null, 'pharmacies', { district: DISTRICT, openNow: true })), [DEMO24, H24].sort());
  } finally {
    clock.reset();
  }
});

test('পুরোনো অ্যাপ (open24 পাঠায় না) বা ভুল মান: ছাঁকা হয় না', async () => {
  try {
    clock.setDhaka('2026-10-20 03:00');
    await seedAll();
    for (const v of [undefined, false, 'true', 1, null]) {
      const body = { district: DISTRICT };
      if (v !== undefined) body.open24 = v;
      assert.strictEqual((await ok(null, 'pharmacies', body)).items.length, 5, `open24=${JSON.stringify(v)}`);
    }
    // উপজেলা ফিল্টারের সাথেও চলে
    assert.deepStrictEqual(ids(await ok(null, 'pharmacies', { district: DISTRICT, upazilaId: 466, open24: true })), [DEMO24, H24, PAUSED].sort());
  } finally {
    clock.reset();
  }
});

test('ডেমো ↔ আসল আলাদা: রাত ৩টায় "ওষুধ খুঁজুন" নিজের জগতের ২৪ ঘণ্টার দোকানেই যায়', async () => {
  try {
    clock.setDhaka('2026-10-20 03:00');
    await seedAll();
    const REAL = 'patientreal24';
    const DEMO = 'demo-test-patient24';
    user(REAL);
    user(DEMO);
    const drug = (await ok(REAL, 'drug_search', { q: 'Napa' })).items[0];
    const real = await ok(REAL, 'med_find', { i: drug.i, district: DISTRICT, upazilaId: 466 });
    assert.ok(real.ok, JSON.stringify(real));
    assert.deepStrictEqual((await db.doc(`medFinds/${real.id}`).get()).get('notified'), [H24], 'আসল রোগী: শুধু আসল খোলা (২৪ ঘণ্টার) দোকান');
    const dem = await ok(DEMO, 'med_find', { i: drug.i, district: DISTRICT, upazilaId: 466 });
    assert.ok(dem.ok, JSON.stringify(dem));
    assert.deepStrictEqual((await db.doc(`medFinds/${dem.id}`).get()).get('notified'), [DEMO24], 'ডেমো রোগী: শুধু ডেমো দোকান');
    // ডেমো রোগী আসল দোকানে রেটিং দিতে পারেন না (তালিকায় দেখা গেলেও)
    const seen = (await ok(DEMO, 'pharmacies', { district: DISTRICT, open24: true })).items.find((p) => p.id === H24);
    assert.strictEqual(seen.canRate, false);
  } finally {
    clock.reset();
  }
});
