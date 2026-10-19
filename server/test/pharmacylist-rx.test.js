// ফার্মেসির তালিকা (পারভেজ, ১৯ অক্টোবর ২০২৬): আগে "দোকানে গিয়ে কিনব" / "অনলাইনে অর্ডার" (mode), আর প্রেসক্রিপশন বাছলে (rxId)
// প্রতিটা কার্ডে কয়টা ওষুধ আছে, আনুমানিক দাম, মোট ছাড়, ছাড়ের শর্ত — এক ডাকে। প্রেসক্রিপশন নিজের (বা কেয়ারগিভারের রোগীর) না হলে ৪০৩;
// প্রেসক্রিপশনের অংশ অন্যের তালিকায় যায় না (মেমোরিতে শুধু জেলার দোকান); ডেমো আর আসল আলাদা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const { db } = F.firebase;

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
function user(id) {
  F.users.set(id, { uid: id, email: `${id}@example.com` });
  F.store.set(`users/${id}`, { name: `ব্যবহারকারী ${id}`, lang: 'bn', phone: '01712345678' });
}
function shop(id, x = {}) {
  user(id);
  F.store.set(`pharmacies/${id}`, {
    name: `দোকান ${id}`, ownerUid: id, status: 'approved', district: DISTRICT, upazilaId: 466, lat: 24.75, lng: 90.40, open24: true,
    phone: '01711111111', address: 'চরপাড়া', licenseNo: 'DL-1', ...x,
  });
}
const rxDoc = (id, patientUid, medicines) => F.store.set(`prescriptions/${id}`, {
  code: `C${id}`.toUpperCase().slice(0, 8), patientUid, doctorUid: 'docX', status: 'active', medicines, tests: [], patient: { name: 'রোগী' }, createdAt: 1,
});
const ids = (r) => r.items.map((p) => p.id);
const by = (r, id) => r.items.find((p) => p.id === id);

const A = 'rxlA0001'; // কাছে, অনলাইন (৩ কিমি), দাম আর MRP জানা
const B = 'rxlB0001'; // ~১.১ কিমি, অনলাইন কিন্তু ১ কিমি পর্যন্ত; স্টকের তালিকা আছে, দাম নেই
const C2 = 'rxlC0001'; // অনলাইন নয়; ৳৫০০-এর বেশি কিনলে ছাড়
const D = 'rxlD0001'; // ~২.২ কিমি, অনলাইন (৫ কিমি); স্টকের তালিকা নেই
const FAR = 'rxlFar001'; // অন্য উপজেলা, অনেক দূরে
const DS = 'demo-rxlshop'; // ডেমো দোকান
const P = 'rxlPatient1';
const Q = 'rxlPatient2';
const DP = 'demo-rxlpatient';
const meds = [
  { name: 'Comet 500', form: 'Tablet', dose: '1+0+1', days: 5 }, // ১০টা
  { name: 'Napa', form: 'Tablet' }, // কয়টা জানা নেই
  { name: 'Tusca', form: 'Syrup' }, // ১ বোতল
  { name: 'Zimax', form: 'Tablet', dose: '1+0+0', days: 3 },
];
const HERE = { district: DISTRICT, upazilaId: 466, lat: 24.75, lng: 90.40 };

let seeded = false;
async function seed() {
  if (seeded) return;
  seeded = true;
  F.store.clear();
  M.clear('');
  shop(A, { lat: 24.751, lng: 90.401, discount: 10, freeDeliveryOver: 1000, discountNote: 'শুধু নগদে', online: { enabled: true, radiusKm: 3, fee: '৩০ টাকা' }, ratingSum: 9, ratingCount: 2, aspects: { behaviour: { sum: 9, count: 2 } } });
  shop(B, { lat: 24.76, lng: 90.40, discount: 5, online: { enabled: true, radiusKm: 1, fee: '২০ টাকা' } });
  shop(C2, { discount: 12, discountMin: 500 });
  shop(D, { lat: 24.77, lng: 90.40, online: { enabled: true, radiusKm: 5, fee: '' } });
  shop(FAR, { upazilaId: 9, lat: 25.2, lng: 90.9, online: { enabled: true, radiusKm: 30, fee: '' } });
  shop(DS, { online: { enabled: true, radiusKm: 5, fee: '' } });
  for (const id of [A, DS]) {
    await ok(id, 'books_item_save', { name: 'Comet 500', price: 8, disc: { type: 'pct', v: 10 }, unit: 'piece' });
    await ok(id, 'books_item_save', { name: 'Napa', price: 1.2, unit: 'piece' });
    await ok(id, 'books_item_save', { name: 'Tusca', price: 85.5, unit: 'bottle' });
  }
  await ok(B, 'stock_set', { add: ['Comet 500', 'Zimax'] });
  await ok(C2, 'books_item_save', { name: 'Comet 500', price: 8, disc: { type: 'pct', v: 12 }, unit: 'piece' });
  user(P); user(Q); user(DP);
  rxDoc('rxlRx1', P, meds);
  rxDoc('rxlRxQ', Q, [{ name: 'Napa', form: 'Tablet' }]);
  rxDoc('demo-rxlRx1', DP, meds);
}

test('প্রেসক্রিপশন বাছলে: কয়টা ওষুধ আছে, আনুমানিক দাম, মোট ছাড়, শর্ত — আর সাজানো (বেশি ওষুধ → কম দাম → খোলা/কাছে)', async () => {
  await seed();
  const r = await ok(P, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1' });
  assert.deepStrictEqual(ids(r), [A, B, C2, D], 'ডেমো আর দূরের দোকান নেই; বেশি ওষুধ আছে আগে, স্টকের তালিকা নেই শেষে');
  // A: ৩/৪টা; ১০ × ৭.২ + ৮৫.৫ = ১৫৭.৫ → ১৫৮; MRP ১০ × ৮ + ৮৫.৫ = ১৬৫.৫ → ১৬৬; ছাড় ৮
  assert.deepStrictEqual(by(r, A).rx, { keepsStock: true, have: 3, total: 4, missing: ['Zimax'], priced: 2, payable: 158, mrp: 166, discount: 8, belowMin: false });
  // শর্ত আর রেটিং কার্ডেই
  assert.deepStrictEqual([by(r, A).discount, by(r, A).discountMin, by(r, A).freeDeliveryOver, by(r, A).discountNote], [10, 0, 1000, 'শুধু নগদে']);
  assert.deepStrictEqual([by(r, A).rating, by(r, A).ratingCount, by(r, A).aspects.behaviour], [4.5, 2, { rating: 4.5, count: 2 }]);
  assert.deepStrictEqual([by(r, A).online, by(r, A).deliveryFee], [true, '৩০ টাকা']);
  // B: স্টকের তালিকা আছে, দাম নেই — "MRP থেকে ৫% ছাড়"-এ ফেরা
  assert.deepStrictEqual(by(r, B).rx, { keepsStock: true, have: 2, total: 4, missing: ['Napa', 'Tusca'], priced: 0, payable: null, mrp: null, discount: null, belowMin: false });
  // C: ৳৫০০-এর বেশি কিনলে ছাড় — এই প্রেসক্রিপশনে MRP ৳৮০, তাই ছাড় নয়, দাম MRP-তে
  assert.deepStrictEqual(by(r, C2).rx, { keepsStock: true, have: 1, total: 4, missing: ['Napa', 'Tusca', 'Zimax'], priced: 1, payable: 80, mrp: 80, discount: 0, belowMin: true });
  assert.strictEqual(by(r, D).rx.keepsStock, false, 'স্টকের তালিকা নেই');
  assert.strictEqual(r.rxId, 'rxlRx1');
  assert.strictEqual(r.lastHome, null);
  // আলাদা ওষুধের দাম যায় না
  assert.ok(!JSON.stringify(r).includes('85.5') && !JSON.stringify(r).includes('7.2'));
  // প্রেসক্রিপশন ছাড়া: আগের মতো (খোলা আগে, কাছে আগে) আর rx নেই
  const plain = await ok(P, 'pharmacies', { ...HERE, mode: 'shop' });
  assert.ok(plain.items.every((p) => !('rx' in p)) && !('lastHome' in plain));
  assert.deepStrictEqual(ids(plain), [C2, A, B, D]);
});

test('অনলাইনে অর্ডার: শুধু অনলাইনে নেওয়া দোকান, ডেলিভারির দূরত্বে; জায়গা না জানলে একই উপজেলা', async () => {
  await seed();
  const on = await ok(P, 'pharmacies', { ...HERE, mode: 'online' });
  assert.deepStrictEqual(ids(on), [A, D], 'B ১ কিমির বাইরে, C অনলাইনে নয়, দূরেরটা নয়, ডেমো নয়');
  assert.ok(on.items.every((p) => p.online && !('radiusKm' in p)));
  // প্রেসক্রিপশনসহ একই ছাঁকা
  assert.deepStrictEqual(ids(await ok(P, 'pharmacies', { ...HERE, mode: 'online', rxId: 'rxlRx1' })), [A, D]);
  // জায়গা না জানলে: একই উপজেলার অনলাইন দোকান (B-ও)
  const noLoc = await ok(P, 'pharmacies', { district: DISTRICT, upazilaId: 466, mode: 'online' });
  assert.deepStrictEqual(ids(noLoc).sort(), [A, B, D].sort());
  // উপজেলাও না দিলে জেলার সব অনলাইন দোকান
  assert.deepStrictEqual(ids(await ok(P, 'pharmacies', { district: DISTRICT, mode: 'online' })).sort(), [A, B, D, FAR].sort());
  // দোকানে গিয়ে: সব (আগের মতো, এলাকা আর ফিল্টারসহ)
  assert.deepStrictEqual(ids(await ok(P, 'pharmacies', { ...HERE, mode: 'shop' })).sort(), [A, B, C2, D].sort());
});

test('অন্যের প্রেসক্রিপশন, অতিথি বা ভুল আইডি: ৪০৩, তালিকার কিছুই নয়; কেয়ারগিভার রোগীর হয়ে পারেন', async () => {
  await seed();
  for (const [uid, rxId] of [[Q, 'rxlRx1'], [null, 'rxlRx1'], [P, 'nope0000'], [P, 'rxlRxQ'], [P, '../x']]) {
    const r = await call(uid, 'pharmacies', { ...HERE, mode: 'shop', rxId });
    assert.strictEqual(r.status, 403, `${uid} ${rxId}`);
    assert.ok(!r.body.items);
  }
  // কেয়ারগিভার: রোগীর হয়ে (asUid) — রোগীর প্রেসক্রিপশন চলে; সংযোগ না থাকলে নয়
  const CG = 'rxlCarer01';
  user(CG);
  F.store.set(`careLinks/${P}_${CG}`, { patientUid: P, caretakerUid: CG, status: 'active' });
  const viaCare = await ok(CG, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1', asUid: P });
  assert.strictEqual(by(viaCare, A).rx.payable, 158);
  assert.strictEqual((await call(Q, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1', asUid: P })).status, 403, 'কেয়ারগিভার নন');
  // নিজের হয়ে কেয়ারগিভার রোগীর প্রেসক্রিপশন নয়
  assert.strictEqual((await call(CG, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1' })).status, 403);
});

test('প্রেসক্রিপশনের অংশ অন্যের তালিকায় যায় না (মেমোরির তালিকা সবার একই)', async () => {
  await seed();
  const mine = await ok(P, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1' });
  assert.strictEqual(by(mine, A).rx.have, 3);
  // একই জেলা-উপজেলা (একই মেমোরির চাবি) — অন্যজন প্রেসক্রিপশন ছাড়া
  const other = await ok(Q, 'pharmacies', { ...HERE, mode: 'shop' });
  assert.ok(other.items.every((p) => !('rx' in p)) && !('lastHome' in other) && !('rxId' in other));
  const guest = await ok(null, 'pharmacies', { ...HERE });
  assert.ok(guest.items.every((p) => !('rx' in p)));
  // অন্যজনের নিজের প্রেসক্রিপশন: তাঁর হিসাব (Napa — কয়টা জানা নেই, দাম ধরা নয়)
  const q = await ok(Q, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRxQ' });
  assert.deepStrictEqual(by(q, A).rx, { keepsStock: true, have: 1, total: 1, missing: [], priced: 0, payable: null, mrp: null, discount: null, belowMin: false });
  // আবার প্রথমজন — আগের মতো
  assert.deepStrictEqual(by(await ok(P, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'rxlRx1' }), A).rx, by(mine, A).rx);
});

test('ডেমো ↔ আসল: বাছাই দিলে নিজের জগতের দোকানই; অতিথি আর পুরোনো অ্যাপ আগের মতো', async () => {
  await seed();
  const dem = await ok(DP, 'pharmacies', { ...HERE, mode: 'shop', rxId: 'demo-rxlRx1' });
  assert.deepStrictEqual(ids(dem), [DS]);
  assert.deepStrictEqual(dem.items[0].rx.payable, 158);
  assert.deepStrictEqual(ids(await ok(DP, 'pharmacies', { ...HERE, mode: 'online' })), [DS]);
  // ডেমো রোগী আসল রোগীর প্রেসক্রিপশন নয়, আসল রোগী ডেমোর নয়
  assert.strictEqual((await call(DP, 'pharmacies', { ...HERE, rxId: 'rxlRx1' })).status, 403);
  assert.strictEqual((await call(P, 'pharmacies', { ...HERE, rxId: 'demo-rxlRx1' })).status, 403);
  // অতিথি আর পুরোনো অ্যাপ (mode নেই): আগের মতো সব
  assert.ok(ids(await ok(null, 'pharmacies', HERE)).includes(DS));
  assert.ok(ids(await ok(P, 'pharmacies', HERE)).includes(DS));
});

test('অনলাইন অর্ডারের পর পরের বার ঠিকানা-ফোন আগে থেকে (শুধু নিজের তালিকায়)', async () => {
  await seed();
  await ok(P, 'rx_send_pharmacy', { rxId: 'rxlRx1', pharmacyId: A, mode: 'online', address: 'বাসা ৫, চরপাড়া', phone: '01712345678' });
  const r = await ok(P, 'pharmacies', { ...HERE, mode: 'online', rxId: 'rxlRx1' });
  assert.deepStrictEqual(r.lastHome, { address: 'বাসা ৫, চরপাড়া', phone: '1712345678'.padStart(11, '0') });
  assert.ok(!('lastHome' in (await ok(Q, 'pharmacies', { ...HERE, mode: 'online' }))));
  assert.strictEqual((await ok(Q, 'pharmacies', { ...HERE, mode: 'online', rxId: 'rxlRxQ' })).lastHome, null);
});

test('দোকানের ছাড়ের শর্ত: সেভ, পুরোনো অ্যাপে আগের মান থাকে, সীমা', async () => {
  await seed();
  const base = { name: `দোকান ${A}`, licenseNo: 'DL-1', phone: '01711111111', district: DISTRICT, upazilaId: 466, address: 'চরপাড়া', open24: true, termsAccepted: true };
  await ok(A, 'pharmacy_save', { ...base, discount: 10, discountMin: 500, freeDeliveryOver: 1000, discountNote: '  শুধু\nনগদে  ' + 'ক'.repeat(100) });
  let me = (await ok(A, 'pharmacy_me')).pharmacy;
  assert.deepStrictEqual([me.discountMin, me.freeDeliveryOver], [500, 1000]);
  assert.ok(me.discountNote.startsWith('শুধু নগদে') && me.discountNote.length === 80, me.discountNote);
  // পুরোনো অ্যাপ: ঘর পাঠায় না → আগের মান
  await ok(A, 'pharmacy_save', { ...base, discount: 10 });
  me = (await ok(A, 'pharmacy_me')).pharmacy;
  assert.deepStrictEqual([me.discountMin, me.freeDeliveryOver], [500, 1000]);
  // ভুল মান → ০, খালি নোট
  await ok(A, 'pharmacy_save', { ...base, discount: 10, discountMin: -5, freeDeliveryOver: 'x', discountNote: '' });
  me = (await ok(A, 'pharmacy_me')).pharmacy;
  assert.deepStrictEqual([me.discountMin, me.freeDeliveryOver, me.discountNote], [0, 0, '']);
  assert.strictEqual((await db.doc(`pharmacies/${A}`).get()).get('status'), 'approved', 'শর্ত বদলালে আবার যাচাই লাগে না');
  // তালিকায় সাথে সাথে (মেমোরি ১ মিনিট — সেভের পর পরীক্ষায় মুছে)
  M.clear('');
  await ok(A, 'pharmacy_save', { ...base, discount: 10, discountMin: 300, freeDeliveryOver: 0, discountNote: 'বিকাশে নয়' });
  M.clear('');
  const p = by(await ok(P, 'pharmacies', { ...HERE, mode: 'shop' }), A);
  assert.deepStrictEqual([p.discountMin, p.freeDeliveryOver, p.discountNote], [300, 0, 'বিকাশে নয়']);
});
