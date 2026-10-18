// ফার্মেসির রেটিং (পারভেজ, ১৮ অক্টোবর ২০২৬): অ্যাম্বুলেন্স/বাসায় সেবার মতো — ফোন বা কেনার পরেই, একজন একবার (বদলানো যায়),
// মালিক নিজের দোকানে নয়, ডেমো ↔ আসল নয়, অ্যাকাউন্ট মুছলে রেটিং গড় থেকে বাদ।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const account = require(path.join(SERVER, 'lib/account.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const { db } = F.firebase;

const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const user = (uid) => F.users.set(uid, { uid, email: `${uid}@example.com` });
async function shop(id, x = {}) {
  user(id);
  await db.doc(`users/${id}`).set({ name: `মালিক ${id}` });
  await db.doc(`pharmacies/${id}`).set({
    name: x.name || `দোকান ${id}`, ownerUid: id, status: 'approved', district: DISTRICT, upazilaId: 466, phone: '01711111111',
    address: 'চরপাড়া', lat: 24.75, lng: 90.4, openAt: '08:00', closeAt: '22:00', ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
  });
  M.clear('');
}
const agg = async (id) => {
  const p = await db.doc(`pharmacies/${id}`).get();
  return [p.get('ratingSum'), p.get('ratingCount')];
};
const listItem = async (uid, id) => {
  M.clear('');
  return (await ok(uid, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === id);
};

test('ফোনের পর: ১ ঘণ্টা পর থেকে ৭ দিন পর্যন্ত; একজনের একটাই রেটিং, বদলালে গড় ঠিক থাকে', async () => {
  const A = 'phshopA0001';
  const U = 'phpatient001';
  await shop(A, { ratingSum: 8, ratingCount: 2 });
  user(U);

  // ফোন বা কেনা ছাড়া নয়
  let r = await call(U, 'pharmacy_rate', { id: A, rating: 5 });
  assert.strictEqual(r.status, 403);
  assert.strictEqual((await listItem(U, A)).canRate, false);
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, rating: 6 })).status, 400, '১-৫ তারা');

  // এইমাত্র ফোন: ১ ঘণ্টা অপেক্ষা
  await ok(U, 'pharmacy_called', { id: A });
  const callDoc = await db.doc(`pharmacyCalls/${U}_${A}`).get();
  assert.ok(callDoc.get('firstAt') > 0, 'প্রথম ফোনের সময় লেখা থাকে');
  r = await call(U, 'pharmacy_rate', { id: A, rating: 5 });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /১ ঘণ্টা/);
  assert.strictEqual((await listItem(U, A)).canRate, false);

  // ২ ঘণ্টা আগের প্রথম ফোন
  await db.doc(`pharmacyCalls/${U}_${A}`).update({ firstAt: Date.now() - 2 * HOUR });
  assert.strictEqual((await listItem(U, A)).canRate, true);
  await ok(U, 'pharmacy_rate', { id: A, rating: 4, comment: '  ভালো ব্যবহার  ' });
  assert.deepStrictEqual(await agg(A), [12, 3]);
  const mine = await db.doc(`pharmacyRatings/${U}_${A}`).get();
  assert.strictEqual(mine.get('rating'), 4);
  assert.strictEqual(mine.get('comment'), 'ভালো ব্যবহার');
  // কার্ডে নতুন গড় সাথে সাথে (মেমোরির তালিকা মোছা হয়)
  const card = (await ok(U, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === A);
  assert.strictEqual(card.ratingCount, 3);
  assert.strictEqual(card.rating, 4);

  // বদলানো: সংখ্যা একই, যোগফল বদলায়; মালিকের মন্তব্যে একটাই
  await ok(U, 'pharmacy_rate', { id: A, rating: 2, comment: 'দাম বেশি' });
  assert.deepStrictEqual(await agg(A), [10, 3]);
  const st = await ok(A, 'pharmacy_stats');
  assert.strictEqual(st.ratingCount, 3);
  assert.deepStrictEqual(st.comments.map((c) => [c.rating, c.comment]), [[2, 'দাম বেশি']]);
  assert.ok(st.comments.every((c) => c.who === undefined), 'কে দিয়েছেন তা নয়');
  assert.ok(st.calls && typeof st.calls[30] === 'number', 'আগের ফোনের হিসাবও আছে');

  // শেষ ফোন ৭ দিনের বেশি আগে: আর নয়
  await db.doc(`pharmacyCalls/${U}_${A}`).update({ at: Date.now() - 8 * DAY, firstAt: Date.now() - 8 * DAY });
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, rating: 5 })).status, 403);
  assert.strictEqual((await listItem(U, A)).canRate, false);
  assert.deepStrictEqual(await agg(A), [10, 3]);
});

test('কেনার পর: অর্ডার "দেওয়া হয়েছে" বা প্রেসক্রিপশনে ওষুধ দিয়েছে — ৩০ দিনের মধ্যে; চলমান/পুরোনো অর্ডারে নয়', async () => {
  const B = 'phshopB0001';
  const C2 = 'phshopC0001';
  const D = 'phshopD0001';
  const U = 'phbuyer0001';
  await shop(B);
  await shop(C2);
  await shop(D);
  user(U);
  const now = Date.now();
  await db.doc('rxOrders/o1').set({ rxId: 'rx1', pharmacyId: B, patientUid: U, status: 'done', doneAt: now - 2 * DAY, createdAt: now - 3 * DAY });
  await db.doc('rxOrders/o2').set({ rxId: 'rx1', pharmacyId: C2, patientUid: U, status: 'out', createdAt: now - DAY });
  await db.doc('rxOrders/o3').set({ rxId: 'rx0', pharmacyId: D, patientUid: U, status: 'done', doneAt: now - 40 * DAY, createdAt: now - 41 * DAY });

  M.clear('');
  const items = (await ok(U, 'pharmacies', { district: DISTRICT })).items;
  const can = Object.fromEntries(items.map((p) => [p.id, p.canRate]));
  assert.strictEqual(can[B], true, 'সম্পন্ন অর্ডার — ফোন ছাড়াই');
  assert.strictEqual(can[C2], false, 'পথে থাকা অর্ডারে এখনো নয়');
  assert.strictEqual(can[D], false, '৩০ দিনের বেশি পুরোনো');

  await ok(U, 'pharmacy_rate', { id: B, rating: 5 });
  assert.deepStrictEqual(await agg(B), [5, 1]);
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: C2, rating: 5 })).status, 403);
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: D, rating: 5 })).status, 403);

  // দোকানে দাঁড়িয়ে প্রেসক্রিপশনের কোড দিয়ে ওষুধ নেওয়া (অর্ডার ছাড়া)
  await db.doc('prescriptions/rx2').set({ patientUid: U, status: 'active', dispensed: [{ by: C2, name: 'দোকান', at: now - 5 * DAY, items: [{ i: 0, qty: 10 }] }] });
  await ok(U, 'pharmacy_rate', { id: C2, rating: 3 });
  assert.deepStrictEqual(await agg(C2), [3, 1]);
});

test('মালিক নিজের দোকানে নয়; ডেমো ↔ আসল নয়', async () => {
  const O = 'phowner0001';
  await shop(O);
  // নিজের দোকানে "অর্ডার সম্পন্ন" থাকলেও
  await db.doc('rxOrders/own1').set({ rxId: 'rxo', pharmacyId: O, patientUid: O, status: 'done', doneAt: Date.now() - DAY });
  const r = await call(O, 'pharmacy_rate', { id: O, rating: 5 });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /নিজের/);
  assert.strictEqual((await listItem(O, O)).canRate, false);
  assert.deepStrictEqual(await agg(O), [0, 0]);

  // ডেমো রোগী আসল দোকানে (ফোনের রেকর্ড থাকলেও)
  const DP = 'demo-patient';
  user(DP);
  await db.doc(`pharmacyCalls/${DP}_${O}`).set({ uid: DP, pharmacyId: O, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  assert.strictEqual((await call(DP, 'pharmacy_rate', { id: O, rating: 1 })).status, 404);
  assert.strictEqual((await listItem(DP, O)).canRate, false);

  // আসল মানুষ ডেমো দোকানে
  const DPH = 'demo-pharmacy';
  await shop(DPH);
  const RU = 'phreal00001';
  user(RU);
  await db.doc(`pharmacyCalls/${RU}_${DPH}`).set({ uid: RU, pharmacyId: DPH, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  assert.strictEqual((await call(RU, 'pharmacy_rate', { id: DPH, rating: 1 })).status, 404);
  assert.strictEqual((await listItem(RU, DPH)).canRate, false);
  assert.deepStrictEqual(await agg(DPH), [0, 0]);
  // একই জগতে চলে
  await db.doc(`pharmacyCalls/${DP}_${DPH}`).set({ uid: DP, pharmacyId: DPH, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  await ok(DP, 'pharmacy_rate', { id: DPH, rating: 4 });
  assert.deepStrictEqual(await agg(DPH), [4, 1]);
});

test('অ্যাকাউন্ট মুছলে: রেটিং গড় থেকে বাদ, মালিকের মন্তব্য থেকে সরে, রেকর্ড মোছে; দোকান মুছলে তার রেটিংও', async () => {
  const E = 'phshopE0001';
  const U = 'phleaver001';
  const K2 = 'phstayer001';
  await shop(E, { ratingSum: 9, ratingCount: 2 });
  for (const u of [U, K2]) {
    user(u);
    await db.doc(`users/${u}`).set({ name: 'ক্রেতা' });
    await db.doc(`pharmacyCalls/${u}_${E}`).set({ uid: u, pharmacyId: E, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  }
  await ok(U, 'pharmacy_rate', { id: E, rating: 1, comment: 'খারাপ' });
  await ok(K2, 'pharmacy_rate', { id: E, rating: 5, comment: 'ভালো' });
  assert.deepStrictEqual(await agg(E), [15, 4]);

  await account.deletion.deleteNow(U, `${U}@example.com`);
  assert.deepStrictEqual(await agg(E), [14, 3]);
  assert.ok(!(await db.doc(`pharmacyRatings/${U}_${E}`).get()).exists);
  assert.ok(!(await db.doc(`pharmacyCalls/${U}_${E}`).get()).exists);
  const st = await ok(E, 'pharmacy_stats');
  assert.deepStrictEqual(st.comments.map((c) => c.comment), ['ভালো']);
  assert.strictEqual((await listItem(K2, E)).ratingCount, 3);

  // দোকান মুছে আবার যোগ করলে আগের রেটিং পড়ে থেকে গড় ভাঙে না
  await ok(E, 'pharmacy_delete');
  assert.ok(!(await db.doc(`pharmacyRatings/${K2}_${E}`).get()).exists);
  await shop(E);
  await ok(K2, 'pharmacy_rate', { id: E, rating: 3 });
  assert.deepStrictEqual(await agg(E), [3, 1]);

  // মালিকের অ্যাকাউন্ট মুছলে তার দোকানের রেটিংও
  await account.deletion.deleteNow(E, `${E}@example.com`);
  assert.ok(!(await db.doc(`pharmacyRatings/${K2}_${E}`).get()).exists);
});
