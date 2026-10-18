// ফার্মেসির রেটিং (পারভেজ, ১৮ অক্টোবর ২০২৬): অ্যাম্বুলেন্স/বাসায় সেবার মতো — ফোন বা কেনার পরেই, একজন একবার (বদলানো যায়),
// মালিক নিজের দোকানে নয়, ডেমো ↔ আসল নয়, অ্যাকাউন্ট মুছলে রেটিং গড় থেকে বাদ।
// রেটিং দোকানের সেবার (ওষুধ কেমন কাজ করল তার নয়): ব্যবহার (সবাই), ঠিক ওষুধ ও ন্যায্য দাম (কিনলে), ডেলিভারির দ্রুততা (অনলাইন অর্ডার পৌঁছালে)।
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
/** দিকভিত্তিক যোগফল/সংখ্যা: { behaviour: [sum, count], ... } (সংখ্যা ০ হলে বাদ) */
const aspAgg = async (id) => {
  const a = (await db.doc(`pharmacies/${id}`).get()).get('aspects') || {};
  return Object.fromEntries(Object.entries(a).filter(([, v]) => v.count).map(([k, v]) => [k, [v.sum, v.count]]));
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
  let r = await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 5 } });
  assert.strictEqual(r.status, 403);
  assert.strictEqual((await listItem(U, A)).canRate, false);
  assert.deepStrictEqual((await listItem(U, A)).rateAspects, []);
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 6 } })).status, 400, '১-৫ তারা');
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, aspects: { price: 4 } })).status, 400, 'ব্যবহার লাগবেই');
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 4, medicine: 5 } })).status, 400, 'অজানা দিক নয়');

  // এইমাত্র ফোন: ১ ঘণ্টা অপেক্ষা
  await ok(U, 'pharmacy_called', { id: A });
  const callDoc = await db.doc(`pharmacyCalls/${U}_${A}`).get();
  assert.ok(callDoc.get('firstAt') > 0, 'প্রথম ফোনের সময় লেখা থাকে');
  r = await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 5 } });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /১ ঘণ্টা/);
  assert.strictEqual((await listItem(U, A)).canRate, false);

  // ২ ঘণ্টা আগের প্রথম ফোন
  await db.doc(`pharmacyCalls/${U}_${A}`).update({ firstAt: Date.now() - 2 * HOUR });
  const it = await listItem(U, A);
  assert.strictEqual(it.canRate, true);
  // শুধু ফোন করেছেন: দাম বা ডেলিভারি জানেন না — শুধু ব্যবহার
  assert.deepStrictEqual(it.rateAspects, ['behaviour']);
  r = await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 4, price: 2 } });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /ওষুধ নেওয়ার পর/);
  assert.deepStrictEqual(await agg(A), [8, 2]);
  await ok(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 4 }, comment: '  ভালো ব্যবহার  ' });
  assert.deepStrictEqual(await agg(A), [12, 3]);
  assert.deepStrictEqual(await aspAgg(A), { behaviour: [4, 1] });
  const mine = await db.doc(`pharmacyRatings/${U}_${A}`).get();
  assert.strictEqual(mine.get('rating'), 4);
  assert.deepStrictEqual(mine.get('aspects'), { behaviour: 4 });
  assert.strictEqual(mine.get('comment'), 'ভালো ব্যবহার');
  // কার্ডে নতুন গড় সাথে সাথে (মেমোরির তালিকা মোছা হয়)
  const card = (await ok(U, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === A);
  assert.strictEqual(card.ratingCount, 3);
  assert.strictEqual(card.rating, 4);
  assert.deepStrictEqual(card.aspects, { behaviour: { rating: 4, count: 1 } });

  // বদলানো: সংখ্যা একই, যোগফল বদলায়; মালিকের মন্তব্যে একটাই (পুরোনো অ্যাপের "rating" = ব্যবহার)
  await ok(U, 'pharmacy_rate', { id: A, rating: 2, comment: 'দাম বেশি' });
  assert.deepStrictEqual(await agg(A), [10, 3]);
  assert.deepStrictEqual(await aspAgg(A), { behaviour: [2, 1] });
  const st = await ok(A, 'pharmacy_stats');
  assert.strictEqual(st.ratingCount, 3);
  assert.deepStrictEqual(st.comments.map((c) => [c.rating, c.comment]), [[2, 'দাম বেশি']]);
  assert.ok(st.comments.every((c) => c.who === undefined), 'কে দিয়েছেন তা নয়');
  assert.ok(st.calls && typeof st.calls[30] === 'number', 'আগের ফোনের হিসাবও আছে');
  assert.deepStrictEqual(st.aspects, { behaviour: { rating: 2, count: 1 } });

  // শেষ ফোন ৭ দিনের বেশি আগে: আর নয়
  await db.doc(`pharmacyCalls/${U}_${A}`).update({ at: Date.now() - 8 * DAY, firstAt: Date.now() - 8 * DAY });
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: A, aspects: { behaviour: 5 } })).status, 403);
  assert.strictEqual((await listItem(U, A)).canRate, false);
  assert.deepStrictEqual(await agg(A), [10, 3]);
});

test('কেনার পর: অর্ডার "দেওয়া হয়েছে" বা প্রেসক্রিপশনে ওষুধ দিয়েছে — ৭ দিনের মধ্যে; চলমান/পুরোনো অর্ডারে নয়; দাম কিনলে, ডেলিভারি অনলাইনে', async () => {
  const B = 'phshopB0001';
  const C2 = 'phshopC0001';
  const D = 'phshopD0001';
  const F2 = 'phshopF0001';
  const U = 'phbuyer0001';
  await shop(B);
  await shop(C2);
  await shop(D);
  await shop(F2);
  user(U);
  const now = Date.now();
  // দোকানে গিয়ে নেওয়া অর্ডার (mode 'shop'; পুরোনো অর্ডারে mode নেই — তাও দোকানে)
  await db.doc('rxOrders/o1').set({ rxId: 'rx1', pharmacyId: B, patientUid: U, status: 'done', doneAt: now - 2 * DAY, createdAt: now - 3 * DAY });
  await db.doc('rxOrders/o2').set({ rxId: 'rx1', pharmacyId: C2, patientUid: U, status: 'out', mode: 'online', createdAt: now - DAY });
  // ৭ দিনের বেশি আগে (আগে ৩০ দিন ছিল — এখন সেবার রেটিং, ফোনের মতো ৭ দিন)
  await db.doc('rxOrders/o3').set({ rxId: 'rx0', pharmacyId: D, patientUid: U, status: 'done', mode: 'online', doneAt: now - 8 * DAY, createdAt: now - 9 * DAY });
  // অনলাইন অর্ডার পৌঁছেছে
  await db.doc('rxOrders/o4').set({ rxId: 'rx3', pharmacyId: F2, patientUid: U, status: 'done', mode: 'online', doneAt: now - DAY, createdAt: now - 1.2 * DAY });

  M.clear('');
  const items = (await ok(U, 'pharmacies', { district: DISTRICT })).items;
  const can = Object.fromEntries(items.map((p) => [p.id, p.canRate]));
  const asp = Object.fromEntries(items.map((p) => [p.id, p.rateAspects]));
  assert.strictEqual(can[B], true, 'সম্পন্ন অর্ডার — ফোন ছাড়াই');
  assert.deepStrictEqual(asp[B], ['behaviour', 'price'], 'দোকান থেকে কেনা: ব্যবহার আর দাম, ডেলিভারি নয়');
  assert.strictEqual(can[C2], false, 'পথে থাকা অর্ডারে এখনো নয়');
  assert.strictEqual(can[D], false, '৭ দিনের বেশি পুরোনো');
  assert.deepStrictEqual(asp[D], []);
  assert.deepStrictEqual(asp[F2], ['behaviour', 'price', 'delivery'], 'অনলাইন অর্ডার পৌঁছেছে: তিন দিকই');

  // দোকানে কেনা: ডেলিভারি দেওয়া যায় না
  let r = await call(U, 'pharmacy_rate', { id: B, aspects: { behaviour: 5, price: 4, delivery: 5 } });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /অনলাইন অর্ডার/);
  // মিলিত = দেওয়া দিকের গড় (৪.৫ → ৫)
  r = await ok(U, 'pharmacy_rate', { id: B, aspects: { behaviour: 5, price: 4 } });
  assert.strictEqual(r.rating, 5);
  assert.deepStrictEqual(await agg(B), [5, 1]);
  assert.deepStrictEqual(await aspAgg(B), { behaviour: [5, 1], price: [4, 1] });
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: C2, aspects: { behaviour: 5 } })).status, 403);
  assert.strictEqual((await call(U, 'pharmacy_rate', { id: D, aspects: { behaviour: 5 } })).status, 403);

  // অনলাইন: তিন দিক; বদলালে আগের দিকগুলো ঠিকমতো সরে (ডেলিভারি বাদ দিলে তার সংখ্যাও কমে)
  r = await ok(U, 'pharmacy_rate', { id: F2, aspects: { behaviour: 5, price: 4, delivery: 2 }, comment: 'দেরিতে এসেছে' });
  assert.strictEqual(r.rating, 4);
  assert.deepStrictEqual(await agg(F2), [4, 1]);
  assert.deepStrictEqual(await aspAgg(F2), { behaviour: [5, 1], price: [4, 1], delivery: [2, 1] });
  const card = (await listItem(U, F2));
  assert.deepStrictEqual(card.aspects, { behaviour: { rating: 5, count: 1 }, price: { rating: 4, count: 1 }, delivery: { rating: 2, count: 1 } });
  await ok(U, 'pharmacy_rate', { id: F2, aspects: { behaviour: 3, price: 2 } });
  assert.deepStrictEqual(await agg(F2), [3, 1]);
  assert.deepStrictEqual(await aspAgg(F2), { behaviour: [3, 1], price: [2, 1] });
  const doc = await db.doc(`pharmacyRatings/${U}_${F2}`).get();
  assert.deepStrictEqual(doc.get('aspects'), { behaviour: 3, price: 2 }, 'আগের ডেলিভারি রেটিং পড়ে থাকে না');
  assert.ok(doc.get('firstRatedAt') > 0);
  assert.deepStrictEqual((await listItem(U, F2)).aspects, { behaviour: { rating: 3, count: 1 }, price: { rating: 2, count: 1 } });

  // দোকানে দাঁড়িয়ে প্রেসক্রিপশনের কোড দিয়ে ওষুধ নেওয়া (অর্ডার ছাড়া) — ব্যবহার আর দাম
  await db.doc('prescriptions/rx2').set({ patientUid: U, status: 'active', dispensed: [{ by: C2, name: 'দোকান', at: now - 5 * DAY, items: [{ i: 0, qty: 10 }] }] });
  assert.deepStrictEqual((await listItem(U, C2)).rateAspects, ['behaviour', 'price']);
  await ok(U, 'pharmacy_rate', { id: C2, aspects: { behaviour: 3, price: 3 } });
  assert.deepStrictEqual(await agg(C2), [3, 1]);
  // ৮ দিন আগে দেওয়া হলে আর নয়
  await db.doc('prescriptions/rx2').update({ dispensed: [{ by: C2, name: 'দোকান', at: now - 8 * DAY, items: [{ i: 0, qty: 10 }] }] });
  assert.strictEqual((await listItem(U, C2)).canRate, false);
});

test('মালিক নিজের দোকানে নয়; ডেমো ↔ আসল নয়', async () => {
  const O = 'phowner0001';
  await shop(O);
  // নিজের দোকানে "অর্ডার সম্পন্ন" থাকলেও
  await db.doc('rxOrders/own1').set({ rxId: 'rxo', pharmacyId: O, patientUid: O, status: 'done', doneAt: Date.now() - DAY });
  const r = await call(O, 'pharmacy_rate', { id: O, aspects: { behaviour: 5 } });
  assert.strictEqual(r.status, 403);
  assert.match(r.body.error, /নিজের/);
  assert.strictEqual((await listItem(O, O)).canRate, false);
  assert.deepStrictEqual(await agg(O), [0, 0]);

  // ডেমো রোগী আসল দোকানে (ফোনের রেকর্ড থাকলেও)
  const DP = 'demo-patient';
  user(DP);
  await db.doc(`pharmacyCalls/${DP}_${O}`).set({ uid: DP, pharmacyId: O, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  assert.strictEqual((await call(DP, 'pharmacy_rate', { id: O, aspects: { behaviour: 1 } })).status, 404);
  assert.strictEqual((await listItem(DP, O)).canRate, false);

  // আসল মানুষ ডেমো দোকানে
  const DPH = 'demo-pharmacy';
  await shop(DPH);
  const RU = 'phreal00001';
  user(RU);
  await db.doc(`pharmacyCalls/${RU}_${DPH}`).set({ uid: RU, pharmacyId: DPH, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  assert.strictEqual((await call(RU, 'pharmacy_rate', { id: DPH, aspects: { behaviour: 1 } })).status, 404);
  assert.strictEqual((await listItem(RU, DPH)).canRate, false);
  assert.deepStrictEqual(await agg(DPH), [0, 0]);
  // একই জগতে চলে
  await db.doc(`pharmacyCalls/${DP}_${DPH}`).set({ uid: DP, pharmacyId: DPH, at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  await ok(DP, 'pharmacy_rate', { id: DPH, aspects: { behaviour: 4 } });
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
  // যিনি চলে যাবেন তিনি অনলাইনে কিনেছিলেন — তিন দিকেই দিয়েছেন
  await db.doc('rxOrders/oE').set({ rxId: 'rxE', pharmacyId: E, patientUid: U, status: 'done', mode: 'online', doneAt: Date.now() - DAY });
  await ok(U, 'pharmacy_rate', { id: E, aspects: { behaviour: 1, price: 2, delivery: 1 }, comment: 'খারাপ' });
  await ok(K2, 'pharmacy_rate', { id: E, aspects: { behaviour: 5 }, comment: 'ভালো' });
  assert.deepStrictEqual(await agg(E), [15, 4]);
  assert.deepStrictEqual(await aspAgg(E), { behaviour: [6, 2], price: [2, 1], delivery: [1, 1] });

  await account.deletion.deleteNow(U, `${U}@example.com`);
  assert.deepStrictEqual(await agg(E), [14, 3]);
  assert.deepStrictEqual(await aspAgg(E), { behaviour: [5, 1] }, 'দিকের যোগফল থেকেও বাদ');
  assert.ok(!(await db.doc(`pharmacyRatings/${U}_${E}`).get()).exists);
  assert.ok(!(await db.doc(`pharmacyCalls/${U}_${E}`).get()).exists);
  const st = await ok(E, 'pharmacy_stats');
  assert.deepStrictEqual(st.comments.map((c) => c.comment), ['ভালো']);
  assert.strictEqual((await listItem(K2, E)).ratingCount, 3);

  // দোকান মুছে আবার যোগ করলে আগের রেটিং পড়ে থেকে গড় ভাঙে না
  await ok(E, 'pharmacy_delete');
  assert.ok(!(await db.doc(`pharmacyRatings/${K2}_${E}`).get()).exists);
  await shop(E);
  await ok(K2, 'pharmacy_rate', { id: E, aspects: { behaviour: 3 } });
  assert.deepStrictEqual(await agg(E), [3, 1]);
  assert.deepStrictEqual(await aspAgg(E), { behaviour: [3, 1] });

  // মালিকের অ্যাকাউন্ট মুছলে তার দোকানের রেটিংও
  await account.deletion.deleteNow(E, `${E}@example.com`);
  assert.ok(!(await db.doc(`pharmacyRatings/${K2}_${E}`).get()).exists);
});
