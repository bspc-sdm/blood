// ফার্মেসিতে আনুমানিক খরচ (পারভেজ, ১৮ অক্টোবর ২০২৬): দোকানের হিসাব-খাতার বিক্রি দাম pharmacyStock/{uid}.prices-এ,
// rx_pharmacy_options-এ প্রতিটা দোকানে estimate { total, priced, of }; দোকান/প্যাথলজির নিজের পাতা থেকে পাঠাতে placeId;
// ডেমো আর আসল আলাদা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const B = require(path.join(SERVER, 'lib/shopbooks.js'));
const RX = require(path.join(SERVER, 'lib/rxnet.js'));
const DISTRICTS = require(path.join(SERVER, 'lib/districts'));
const DIST = DISTRICTS.find((d) => d.en === 'Mymensingh').bn;
const OTHER = DISTRICTS.find((d) => d.en !== 'Mymensingh').bn;
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
  F.store.set(`users/${id}`, { name: `ব্যবহারকারী ${id}`, lang: 'bn' });
}
function shop(id, extra = {}) {
  user(id);
  F.store.set(`pharmacies/${id}`, {
    name: `দোকান ${id}`, ownerUid: id, status: 'approved', district: DIST, upazilaId: 1, lat: 24.75, lng: 90.40, open24: true,
    phone: '01711111111', address: 'চরপাড়া', ...extra,
  });
}
const stock = async (id) => (await db.doc(`pharmacyStock/${id}`).get()).data() || {};
const rxDoc = (id, patientUid, medicines, tests = []) => F.store.set(`prescriptions/${id}`, {
  code: `C${id}`.toUpperCase().slice(0, 8), patientUid, doctorUid: 'docX', status: 'active', medicines, tests, patient: { name: 'রোগী' }, createdAt: 1,
});

test('আনুমানিক খরচের হিসাব: ছাড়, আংশিক দাম, পরিমাণ না জানা, কোনো দাম নেই', () => {
  const W = (name, left, countable = true) => ({ name, k: B.key(name), left, countable });
  const keys = new Set(['comet 500', 'napa', 'tusca', 'seclo 20']);
  // ছাড় বাদে দাম: ৮ টাকা − ১০% = ৭.২
  assert.strictEqual(B.patientPriceOf({ price: 8, disc: { type: 'pct', v: 10 }, unit: 'piece' }), 7.2);
  assert.strictEqual(B.patientPriceOf({ price: 40, disc: { type: 'amt', v: 2 }, unit: '' }), 38);
  assert.strictEqual(B.patientPriceOf({ price: 120, unit: 'strip' }), null, 'পাতার দাম — পাতায় কয়টা জানা নেই');
  assert.strictEqual(B.patientPriceOf({ price: null }), null);
  assert.strictEqual(B.patientPriceOf({ price: 5, disc: { type: 'pct', v: 100 } }), null, 'বিনা দামে = দাম জানা নয়');
  const prices = { 'comet 500': 7.2, napa: 1.2, tusca: 85.5 };
  // Comet ১০টা × ৭.২ = ৭২; Napa ট্যাবলেট কয়টা জানা নেই — দাম ধরা নয়; সিরাপ ১টা = ৮৫.৫; Seclo স্টকে আছে দাম নেই; Zimax স্টকে নেই
  const wanted = [W('Comet 500', 10), W('Napa', null), W('Tusca', null, false), W('Seclo 20', 14), W('Zimax', 3)];
  assert.deepStrictEqual(RX.pharmacyEstimate(wanted, keys, prices), { total: 158, priced: 2, of: 5 }, '৭২ + ৮৫.৫ = ১৫৭.৫ → ১৫৮');
  // সব দাম জানা
  assert.deepStrictEqual(RX.pharmacyEstimate([W('Comet 500', 3), W('Tusca', null, false)], keys, prices), { total: 107, priced: 2, of: 2 }, '২১.৬ + ৮৫.৫ = ১০৭.১');
  // দাম স্টকে না থাকা ওষুধের হলে গোনা নয়
  assert.strictEqual(RX.pharmacyEstimate([W('Zimax', 3)], keys, { zimax: 30 }), null);
  assert.strictEqual(RX.pharmacyEstimate(wanted, keys, {}), null, 'কোনো দাম নেই');
  assert.strictEqual(RX.pharmacyEstimate(wanted, keys, null), null);
  // প্রোটোটাইপের নাম চাবি হলেও ভুল দাম নয়
  assert.strictEqual(RX.pharmacyEstimate([W('constructor', 2), W('toString', 1)], new Set(['constructor', 'tostring']), {}), null);
});

test('দামের ভাগ সীমার মধ্যে: বেশি হলে স্টকের পণ্য আগে, Firestore-এর বিশেষ নাম বাদ', () => {
  const big = {};
  for (let i = 0; i < 4000; i++) big[`medicine number ${String(i).padStart(4, '0')} with a long name`] = 10 + i;
  big.__x__ = 5;
  const inStock = new Set(['medicine number 3999 with a long name']);
  const out = B.capPrices(big, inStock);
  const size = Object.keys(out).reduce((a, k) => a + Buffer.byteLength(k) + 9, 0);
  assert.ok(size <= B.PRICE_BYTES, `ভাগের আকার ${size}`);
  assert.ok(Object.keys(out).length > 1000 && Object.keys(out).length <= 3000);
  assert.ok(out['medicine number 3999 with a long name'], 'স্টকে থাকা পণ্য বাদ পড়ে না');
  assert.ok(!('__x__' in out));
});

test('পণ্য যোগ/বদল/মোছায়, কেনায়, নষ্টে আর স্টকের তালিকা বদলে দামের ভাগ ঠিক থাকে', async () => {
  const A = 'estShopA01';
  shop(A);
  // যোগ: ছাড়সহ দাম; পরিমাণ ০ হলেও দাম থাকে (স্টকে নেই, তাই হিসাবে আসে না)
  const c = (await ok(A, 'books_item_save', { name: 'Comet 500', price: 8, disc: { type: 'pct', v: 10 }, unit: 'piece', qty: 50 })).item;
  await ok(A, 'books_item_save', { name: 'Napa', qty: 0, price: 1.2 });
  await ok(A, 'books_item_save', { name: 'Seclo 20' }); // দাম নেই
  let st = await stock(A);
  assert.deepStrictEqual(st.prices, { 'comet 500': 7.2, napa: 1.2 });
  assert.deepStrictEqual(st.keys, ['comet 500', 'seclo 20']);

  // বদল: নতুন দাম সাথে সাথে (পুরোনো দাম থাকে না)
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 500', price: 9, disc: { type: 'amt', v: 0.5 }, unit: 'piece' });
  assert.strictEqual((await stock(A)).prices['comet 500'], 8.5);
  // পাতার দাম হলে সরে
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 500', price: 90, unit: 'strip' });
  assert.ok(!('comet 500' in (await stock(A)).prices));
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 500', price: 9, unit: 'piece' });
  // নাম বদলালে পুরোনো চাবির দাম সরে
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 850', price: 9, unit: 'piece' });
  st = await stock(A);
  assert.deepStrictEqual(st.prices, { 'comet 850': 9, napa: 1.2 });
  assert.ok(st.keys.includes('comet 850') && !st.keys.includes('comet 500'));
  // দাম মুছলে সরে
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 850', price: '', unit: 'piece' });
  assert.ok(!('comet 850' in (await stock(A)).prices));
  await ok(A, 'books_item_save', { id: c.id, name: 'Comet 850', price: 9, unit: 'piece' });

  // কেনায় নতুন বিক্রি দাম; কেনা মুছলে আগের দাম ফেরে
  const buy = await ok(A, 'books_purchase_add', { lines: [{ itemId: c.id, qty: 10, unitCost: 6, price: 10 }, { name: 'Tusca', qty: 5, price: 85 }] });
  st = await stock(A);
  assert.strictEqual(st.prices['comet 850'], 10);
  assert.strictEqual(st.prices.tusca, 85);
  await ok(A, 'books_purchase_delete', { id: buy.id });
  st = await stock(A);
  assert.strictEqual(st.prices['comet 850'], 9);
  assert.ok(!('tusca' in st.prices), 'কেনায় তৈরি পণ্যের দাম আগে ছিল না');

  // নষ্ট (write-off) আর বিক্রি: নাম সরলেও দাম থাকে
  await ok(A, 'books_adjust', { kind: 'writeoff', itemId: c.id, qty: 1000, reason: 'মেয়াদ শেষ' });
  st = await stock(A);
  assert.ok(!st.keys.includes('comet 850'), 'সব নষ্ট — স্টকে নেই');
  assert.strictEqual(st.prices['comet 850'], 9, 'দাম থাকে (আবার এলে কাজে লাগে)');
  // হাতে স্টকের তালিকা বদলালেও (পুরোনো স্টকের পাতা) দাম থাকে
  await ok(A, 'stock_set', { add: ['Zimax'], remove: [] });
  st = await stock(A);
  assert.ok(st.keys.includes('zimax'));
  assert.strictEqual(st.prices.napa, 1.2);
  // মোছা
  await ok(A, 'books_item_delete', { id: (await ok(A, 'books_items')).items.find((x) => x.name === 'Napa').id });
  assert.ok(!('napa' in (await stock(A)).prices));
});

test('পুরোনো দোকানে একবার দাম বসানো (ক্রন) — শেষে আর চলে না, পরে বসানো দাম মুছে দেয় না', async () => {
  const O = 'estShopOld1';
  shop(O);
  F.store.set(`pharmacyStock/${O}`, { names: ['Ace', 'Fexo'], keys: ['ace', 'fexo'], updatedAt: 1 });
  F.store.set(`shopItems/${O}/items/${B.idOf('Ace')}`, { name: 'Ace', key: 'ace', price: 1.5, disc: null, unit: 'piece' });
  F.store.set(`shopItems/${O}/items/${B.idOf('Fexo')}`, { name: 'Fexo', key: 'fexo', price: 10, disc: { type: 'pct', v: 10 }, unit: '' });
  F.store.set(`shopItems/${O}/items/${B.idOf('Box')}`, { name: 'Box', key: 'box', price: 100, unit: 'box' });
  const r = await B.backfillStockPrices(Date.now());
  assert.strictEqual(r.done, true);
  assert.ok(r.filled >= 1);
  const st = await stock(O);
  assert.deepStrictEqual(st.prices, { ace: 1.5, fexo: 9 });
  assert.strictEqual(st.pricesAll, true);
  assert.deepStrictEqual(st.names, ['Ace', 'Fexo'], 'নামের তালিকা যেমন ছিল');
  assert.deepStrictEqual(await B.backfillStockPrices(Date.now()), { done: true, filled: 0 });
  // পরের বদলেও pricesAll থাকে
  await ok(O, 'books_item_save', { id: B.idOf('Ace'), name: 'Ace', price: 2, unit: 'piece' });
  assert.deepStrictEqual([(await stock(O)).prices.ace, (await stock(O)).pricesAll], [2, true]);
  F.store.delete('maintenance/stockPrices');
});

test('রোগী: প্রতিটা দোকানে আনুমানিক খরচ; placeId-এ শুধু সেই দোকান; ডেমো আর আসল আলাদা', async () => {
  const S1 = 'estShopP1'; const S2 = 'estShopP2'; const FAR = 'estShopFar'; const ELSE = 'estShopElse'; const DS = 'demo-estshop';
  shop(S1);
  shop(S2, { lat: 24.751, lng: 90.401 });
  shop(FAR, { upazilaId: 9, lat: 25.2, lng: 90.9 }); // অন্য উপজেলা, অনেক দূরে
  shop(ELSE, { district: OTHER });
  shop(DS);
  const P = 'estPatient1'; const DP = 'demo-estpatient';
  user(P); user(DP);
  const meds = [
    { name: 'Comet 500', form: 'Tablet', dose: '1+0+1', days: 5 }, // ১০টা
    { name: 'Napa', form: 'Tablet' }, // কয়টা জানা নেই
    { name: 'Tusca', form: 'Syrup' }, // ১ বোতল
    { name: 'Zimax', form: 'Tablet', dose: '1+0+0', days: 3 },
  ];
  rxDoc('estRx1', P, meds);
  rxDoc('demo-estRx1', DP, meds);
  for (const id of [S1, FAR, DS]) {
    await ok(id, 'books_item_save', { name: 'Comet 500', price: 8, disc: { type: 'pct', v: 10 }, unit: 'piece' });
    await ok(id, 'books_item_save', { name: 'Napa', price: 1.2, unit: 'piece' });
    await ok(id, 'books_item_save', { name: 'Tusca', price: 85.5, unit: 'bottle' });
  }
  // S2: স্টকের তালিকা আছে, দাম নেই
  await ok(S2, 'stock_set', { add: ['Comet 500', 'Zimax'] });
  // ELSE-এ দাম আছে, কিন্তু অন্য জেলা
  await ok(ELSE, 'books_item_save', { name: 'Comet 500', price: 8, unit: 'piece' });

  const body = { rxId: 'estRx1', district: DIST, upazilaId: 1, lat: 24.75, lng: 90.40, mode: 'shop' };
  const items = (await ok(P, 'rx_pharmacy_options', body)).items;
  const by = (id) => items.find((x) => x.id === id);
  assert.deepStrictEqual(by(S1).estimate, { total: 158, priced: 2, of: 4 }, '১০ × ৭.২ + ৮৫.৫ = ১৫৭.৫ → ১৫৮');
  assert.deepStrictEqual([by(S1).have, by(S1).total, by(S1).missing], [3, 4, ['Zimax']]);
  assert.strictEqual(by(S2).estimate, null, 'দাম নেই');
  assert.strictEqual(by(S2).have, 2);
  assert.ok(!by(FAR), 'দূরের অন্য উপজেলার দোকান সাধারণ তালিকায় নেই');
  assert.ok(!by(ELSE) && !by(DS), 'অন্য জেলা আর ডেমো নেই');
  assert.ok(!JSON.stringify(items).includes('85.5'), 'আলাদা ওষুধের দাম রোগী পান না');

  // placeId: দূরের দোকানও আসে, শুধু সেটা
  let one = (await ok(P, 'rx_pharmacy_options', { ...body, placeId: FAR })).items;
  assert.deepStrictEqual(one.map((x) => x.id), [FAR]);
  assert.deepStrictEqual(one[0].estimate, { total: 158, priced: 2, of: 4 });
  one = (await ok(P, 'rx_pharmacy_options', { ...body, mode: 'online', placeId: S2 })).items;
  assert.deepStrictEqual(one.map((x) => [x.id, x.estimate, x.keepsStock]), [[S2, null, true]], 'অনলাইন না নিলেও নিজের পাতা থেকে সংখ্যা দেখা যায়');
  assert.deepStrictEqual((await ok(P, 'rx_pharmacy_options', { ...body, placeId: ELSE })).items, [], 'অন্য জেলা নয়');
  assert.deepStrictEqual((await ok(P, 'rx_pharmacy_options', { ...body, placeId: DS })).items, [], 'আসল রোগী ডেমো দোকান নয়');
  assert.deepStrictEqual((await ok(P, 'rx_pharmacy_options', { ...body, placeId: 'nope000' })).items, []);

  // ডেমো রোগী: শুধু ডেমো দোকান (দামসহ), আসল দোকান placeId দিয়েও নয়
  const dem = (await ok(DP, 'rx_pharmacy_options', { ...body, rxId: 'demo-estRx1' })).items;
  assert.ok(dem.length && dem.every((x) => x.id.startsWith('demo-')));
  assert.deepStrictEqual(dem.find((x) => x.id === DS).estimate, { total: 158, priced: 2, of: 4 });
  assert.deepStrictEqual((await ok(DP, 'rx_pharmacy_options', { ...body, rxId: 'demo-estRx1', placeId: S1 })).items, []);

  // কিছু কেনা হলে বাকিটার দাম: Comet ৬টা দেওয়া → ৪ × ৭.২ = ২৮.৮ + ৮৫.৫ = ১১৪.৩ → ১১৪
  F.store.set('prescriptions/estRx1', { ...F.store.get('prescriptions/estRx1'), dispensed: [{ by: S2, at: 1, items: [{ i: 0, qty: 6 }] }] });
  one = (await ok(P, 'rx_pharmacy_options', { ...body, placeId: S1 })).items;
  assert.deepStrictEqual(one[0].estimate, { total: 114, priced: 2, of: 4 });
  // দাম বদলালে পরের বার নতুন দাম (পুরোনো দাম থাকে না)
  await ok(S1, 'books_item_save', { id: B.idOf('Tusca'), name: 'Tusca', price: 100, unit: 'bottle' });
  one = (await ok(P, 'rx_pharmacy_options', { ...body, placeId: S1 })).items;
  assert.deepStrictEqual(one[0].estimate, { total: 129, priced: 2, of: 4 });
});

test('প্যাথলজি: placeId-এ শুধু সেই প্যাথলজি (দাম সহ), অন্য জেলা/ডেমো নয়, জেলার তালিকা আগের মতো', async () => {
  const L1 = 'estLab1'; const L2 = 'estLab2'; const LO = 'estLabOther'; const LD = 'demo-estlab';
  const lab = (id, extra = {}) => { user(id); F.store.set(`labs/${id}`, { name: `ল্যাব ${id}`, status: 'approved', district: DIST, address: 'চরপাড়া', phone: '01711000098', licenseNo: 'L', ...extra }); };
  lab(L1); lab(L2); lab(LO, { district: OTHER }); lab(LD);
  for (const id of [L1, LD]) await ok(id, 'lab_prices_set', { items: [{ code: 'cbc', price: 400 }, { code: 'hba1c', price: 1000 }], discountPct: 10 });
  const P = 'estPatient2'; const DP = 'demo-estpatient2';
  user(P); user(DP);
  rxDoc('estRxL', P, [], ['CBC', 'HbA1c', 'Bone scan']);
  rxDoc('demo-estRxL', DP, [], ['CBC', 'HbA1c', 'Bone scan']);

  const all = (await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST })).items;
  assert.ok(all.some((l) => l.id === L1) && all.some((l) => l.id === L2) && !all.some((l) => l.id === LD || l.id === LO));
  let one = (await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST, placeId: L1 })).items;
  assert.deepStrictEqual(one.map((l) => [l.id, l.priced, l.have, l.total, l.missing, l.subtotal, l.discountPct, l.estimate]),
    [[L1, true, 2, 3, ['Bone scan'], 1400, 10, 1260]]);
  one = (await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST, placeId: L2 })).items;
  assert.deepStrictEqual(one.map((l) => [l.id, l.priced, l.estimate]), [[L2, false, null]], 'দাম জানা নেই');
  assert.deepStrictEqual((await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST, placeId: LO })).items, []);
  assert.deepStrictEqual((await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST, placeId: LD })).items, []);
  // placeId-এর পরেও জেলার তালিকার দাম ঠিক (জেলার ক্যাশে একটা প্যাথলজির দাম বসে না)
  const again = (await ok(P, 'rx_lab_options', { rxId: 'estRxL', district: DIST })).items;
  assert.strictEqual(again.find((l) => l.id === L1).estimate, 1260);
  const dem = (await ok(DP, 'rx_lab_options', { rxId: 'demo-estRxL', district: DIST, placeId: LD })).items;
  assert.deepStrictEqual(dem.map((l) => [l.id, l.estimate]), [[LD, 1260]]);
  assert.deepStrictEqual((await ok(DP, 'rx_lab_options', { rxId: 'demo-estRxL', district: DIST, placeId: L1 })).items, []);
});
