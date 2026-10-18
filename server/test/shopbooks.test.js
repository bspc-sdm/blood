// ফার্মেসির হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬): পণ্য (নাম ছাড়া সব ঐচ্ছিক), স্টকের নামের তালিকার সাথে মিল,
// মাল কেনা (গড় কেনা দাম), কাউন্টারে বিক্রি (% আর টাকায় ছাড়), প্রেসক্রিপশনের ওষুধ দেওয়া থেকে বিক্রি, নষ্ট/গণনা,
// লাভ-ক্ষতির হিসাব, শুধু মালিক, অ্যাকাউন্ট মুছলে সব মোছা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const account = require(path.join(SERVER, 'lib/account.js'));
const B = require(path.join(SERVER, 'lib/shopbooks.js'));
const { db } = F.firebase;
const DAY = 24 * 3600 * 1000;

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
async function shop(id, names) {
  F.users.set(id, { uid: id, email: `${id}@example.com` });
  await db.doc(`users/${id}`).set({ name: `মালিক ${id}` });
  await db.doc(`pharmacies/${id}`).set({ name: `দোকান ${id}`, ownerUid: id, status: 'approved', district: 'ময়মনসিংহ', phone: '01711111111' });
  if (names) await db.doc(`pharmacyStock/${id}`).set({ names, keys: names.map(B.key), updatedAt: 1 });
}
const stockNames = async (id) => ((await db.doc(`pharmacyStock/${id}`).get()).get('names') || []);
const item = async (id, name) => (await ok(id, 'books_items')).items.find((x) => x.name === name);
// দিনের যোগফল (increment-এর দশমিকের গরমিল বাদ দিয়ে)
const round = (o) => JSON.parse(JSON.stringify(o, (k, v) => (typeof v === 'number' ? Math.round(v * 100) / 100 : v)));
const dayDoc = async (id) => round((await db.doc(`shopBooks/${id}/days/${B.dayKey(Date.now())}`).get()).data() || {});
const monthAhead = (days) => new Date(Date.now() + days * DAY + 6 * 3600 * 1000).toISOString().slice(0, 7);

test('পণ্য: পুরোনো স্টকের নাম পণ্য হয়, নাম ছাড়া সব ঐচ্ছিক, স্টকের তালিকার সাথে মিল থাকে', async () => {
  const A = 'shopItemsA1';
  await shop(A, ['Napa', 'Seclo']);
  let list = (await ok(A, 'books_items')).items;
  assert.deepStrictEqual(list.map((x) => [x.name, x.qty, x.inStock]), [['Napa', null, true], ['Seclo', null, true]], 'আগের নাম পণ্য হয়ে গেছে');
  assert.ok((await db.doc(`shopItems/${A}/items/${B.idOf('Napa')}`).get()).exists);
  list = (await ok(A, 'books_items')).items;
  assert.strictEqual(list.length, 2, 'দ্বিতীয়বার নতুন কিছু হয় না');

  // শুধু নাম — আগের মতো "স্টকে আছে"
  await ok(A, 'books_item_save', { name: '  Fexo   120 ', generic: 'Fexofenadine', form: 'Tablet' });
  assert.ok((await stockNames(A)).includes('Fexo 120'));
  // পরিমাণ ০ — স্টকে নেই; পরে ১০ — স্টকে আসে, গণনার হিসাব থাকে
  const c = (await ok(A, 'books_item_save', { name: 'Comet 500', qty: 0, cost: 5, price: 8, disc: { type: 'pct', v: 10 }, lowAt: 5, unit: 'piece' })).item;
  assert.strictEqual(c.inStock, false);
  assert.strictEqual(c.salePrice, 7.2);
  assert.ok(!(await stockNames(A)).includes('Comet 500'));
  const c2 = (await ok(A, 'books_item_save', { id: c.id, name: 'Comet 500', qty: 10, cost: 5, price: 8, disc: { type: 'pct', v: 10 }, lowAt: 5, unit: 'piece' })).item;
  assert.strictEqual(c2.inStock, true);
  assert.strictEqual(c2.qty, 10);
  assert.ok((await stockNames(A)).includes('Comet 500'));
  const adj = (await ok(A, 'books_adjusts')).items;
  assert.deepStrictEqual(adj.map((a) => [a.kind, a.name, a.before, a.after]), [['count', 'Comet 500', 0, 10]]);

  // একই নাম দুবার নয়; ভুল দাম/ছাড়/মেয়াদ নয়
  assert.strictEqual((await call(A, 'books_item_save', { name: 'comet  500' })).status, 409);
  assert.strictEqual((await call(A, 'books_item_save', { name: 'X', price: -1 })).status, 400);
  assert.strictEqual((await call(A, 'books_item_save', { name: 'X', disc: { type: 'pct', v: 120 } })).status, 400);
  assert.strictEqual((await call(A, 'books_item_save', { name: 'X', price: 5, disc: { type: 'amt', v: 6 } })).status, 400);
  assert.strictEqual((await call(A, 'books_item_save', { name: 'X', expiry: '2027-13' })).status, 400);
  assert.strictEqual((await call(A, 'books_item_save', { name: '' })).status, 400);

  // হাতে "স্টকে নেই/আছে"
  const fexo = await item(A, 'Fexo 120');
  await ok(A, 'books_item_stock', { id: fexo.id, on: false });
  assert.ok(!(await stockNames(A)).includes('Fexo 120'));
  assert.strictEqual((await item(A, 'Fexo 120')).inStock, false);
  await ok(A, 'books_item_stock', { id: fexo.id, on: true });
  assert.ok((await stockNames(A)).includes('Fexo 120'));

  // নাম বদলালে তালিকায়ও; আগের stock_set দিয়ে সরানো নাম পণ্যেও "নেই"
  const napa = await item(A, 'Napa');
  await ok(A, 'books_item_save', { id: napa.id, name: 'Napa 500', price: 1.2 });
  assert.deepStrictEqual((await stockNames(A)).filter((n) => n.startsWith('Napa')), ['Napa 500']);
  await ok(A, 'stock_set', { remove: ['Seclo'] });
  assert.strictEqual((await item(A, 'Seclo')).inStock, false);
  await ok(A, 'stock_set', { add: ['Ace'] });
  assert.ok(await item(A, 'Ace'), 'পুরোনো তালিকায় যোগ করা নামও পণ্য হয়');

  // মোছা: স্টকের তালিকা থেকেও
  await ok(A, 'books_item_delete', { id: (await item(A, 'Napa 500')).id });
  assert.ok(!(await stockNames(A)).includes('Napa 500'));
  assert.strictEqual(await item(A, 'Napa 500'), undefined);
  // রোগীর প্রেসক্রিপশন মেলানো চাবি একই
  const st = await db.doc(`pharmacyStock/${A}`).get();
  assert.deepStrictEqual(st.get('keys'), st.get('names').map((n) => n.toLowerCase()));
});

test('মাল কেনা: পরিমাণ বাড়ে, কেনা দাম গড় হয়, মেয়াদ কাছেরটা, নতুন নাম পণ্য হয়; মুছলে পরিমাণ ফেরে', async () => {
  const A = 'shopBuyA001';
  await shop(A, ['Seclo']);
  await ok(A, 'books_items');
  const seclo = await item(A, 'Seclo');
  await ok(A, 'books_purchase_add', { supplier: 'রেনাটা ডিপো', lines: [{ itemId: seclo.id, qty: 100, unitCost: 4, expiry: '2027-06' }] });
  let s = await item(A, 'Seclo');
  assert.deepStrictEqual([s.qty, s.cost, s.expiry], [100, 4, '2027-06']);
  const p2 = await ok(A, 'books_purchase_add', { lines: [{ itemId: seclo.id, qty: 50, unitCost: 5.5, expiry: '2027-03', price: 7 }, { name: 'Ace', qty: 20, unitCost: 1.2, price: 2 }] });
  s = await item(A, 'Seclo');
  assert.deepStrictEqual([s.qty, s.cost, s.expiry, s.price], [150, 4.5, '2027-03', 7], '(১০০×৪ + ৫০×৫.৫) / ১৫০ = ৪.৫');
  const ace = await item(A, 'Ace');
  assert.deepStrictEqual([ace.qty, ace.cost, ace.price, ace.inStock], [20, 1.2, 2, true]);
  assert.ok((await stockNames(A)).includes('Ace'));
  assert.strictEqual(p2.purchase.total, 299);
  let d = await dayDoc(A);
  assert.deepStrictEqual([d.purchases, d.bought], [2, 699]);
  const list = (await ok(A, 'books_purchases')).items;
  assert.strictEqual(list.length, 2);
  assert.strictEqual(list.find((p) => p.supplier === 'রেনাটা ডিপো').total, 400);

  assert.strictEqual((await call(A, 'books_purchase_add', { lines: [{ itemId: seclo.id, qty: 0 }] })).status, 400);
  assert.strictEqual((await call(A, 'books_purchase_add', { at: Date.now() + 5 * DAY, lines: [{ itemId: seclo.id, qty: 1 }] })).status, 400, 'ভবিষ্যতের তারিখ নয়');

  await ok(A, 'books_purchase_delete', { id: p2.id });
  s = await item(A, 'Seclo');
  assert.strictEqual(s.qty, 100);
  assert.strictEqual((await item(A, 'Ace')).qty, 0);
  assert.ok(!(await stockNames(A)).includes('Ace'), 'পরিমাণ ০ হলে স্টকের তালিকা থেকে সরে');
  d = await dayDoc(A);
  assert.deepStrictEqual([d.purchases, d.bought], [1, 400]);
});

test('কাউন্টারে বিক্রি: পণ্যের ছাড় (% / টাকা), পুরো বিলে ছাড়, লাভ, কেনা দামের নিচে, দাম ছাড়া; মুছলে সব ফেরে', async () => {
  const A = 'shopSaleA01';
  await shop(A);
  const comet = (await ok(A, 'books_item_save', { name: 'Comet 500', qty: 10, cost: 5, price: 8, disc: { type: 'pct', v: 10 } })).item;
  const seclo = (await ok(A, 'books_item_save', { name: 'Seclo 20', qty: 100, cost: 4.5, price: 7, disc: { type: 'amt', v: 0.5 } })).item;
  const ace = (await ok(A, 'books_item_save', { name: 'Ace', qty: 20, cost: 1.2, price: 2 })).item;
  const fexo = (await ok(A, 'books_item_save', { name: 'Fexo 120' })).item;

  // দাম পণ্য থেকে: Comet ৪ × (৮ − ১০%), Seclo ১০ × (৭ − ০.৫); বিলে ৫%
  const a = (await ok(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 4 }, { itemId: seclo.id, qty: 10 }], billDisc: { type: 'pct', v: 5 } })).sale;
  assert.deepStrictEqual(a.totals, { gross: 102, discount: 12.89, billDiscount: 4.69, net: 89.11, cost: 65, profit: 24.11, netCosted: 89.11, unpriced: 0 });
  assert.deepStrictEqual(a.lines.map((l) => [l.name, l.qty, l.unitPrice, l.unitDisc, l.net, l.cost, l.profit]), [
    ['Comet 500', 4, 8, 0.8, 27.36, 20, 7.36], ['Seclo 20', 10, 7, 0.5, 61.75, 45, 16.75],
  ]);
  assert.strictEqual(a.source, 'counter');
  // হাতে দাম বদলে, বিলে ১ টাকা ছাড়
  const b = (await ok(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 2, unitPrice: 8, unitDisc: 0 }], billDisc: { type: 'amt', v: 1 } })).sale;
  assert.deepStrictEqual([b.totals.gross, b.totals.discount, b.totals.net, b.totals.cost, b.totals.profit], [16, 1, 15, 10, 5]);
  // কেনা দামের নিচে
  const c = (await ok(A, 'books_sale_add', { lines: [{ itemId: ace.id, qty: 5, unitPrice: 1 }] })).sale;
  assert.strictEqual(c.totals.profit, -1);
  // দাম ছাড়া (শুধু পরিমাণ) — টাকার হিসাবে নয়
  const u = (await ok(A, 'books_sale_add', { lines: [{ itemId: fexo.id, qty: 1 }] })).sale;
  assert.deepStrictEqual([u.totals.net, u.totals.unpriced, u.lines[0].profit], [0, 1, null]);

  assert.strictEqual((await item(A, 'Comet 500')).qty, 4);
  assert.strictEqual((await item(A, 'Seclo 20')).qty, 90);
  assert.ok((await item(A, 'Comet 500')).lastSaleAt > 0);

  const d = await dayDoc(A);
  assert.deepStrictEqual([d.sales, d.net, d.discount, d.cost, d.profit], [4, 109.11, 13.89, 81, 28.11]);
  assert.deepStrictEqual(d.src.counter, { n: 4, net: 109.11 });
  assert.strictEqual(d.items[ace.id].neg, -1);

  // বেশি বিক্রি হলে ০-তে থামে, স্টকের তালিকা থেকে সরে
  await ok(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 9 }] });
  assert.strictEqual((await item(A, 'Comet 500')).qty, 0);
  assert.ok(!(await stockNames(A)).includes('Comet 500'));
  const last = (await ok(A, 'books_sales', { period: 'today' })).items[0];
  assert.strictEqual(last.lines[0].qty, 9);
  await ok(A, 'books_sale_delete', { id: last.id });
  assert.strictEqual((await item(A, 'Comet 500')).qty, 9, 'মুছলে পরিমাণ ফেরে');
  assert.ok((await stockNames(A)).includes('Comet 500'));
  const d2 = await dayDoc(A);
  assert.deepStrictEqual([d2.sales, d2.net, d2.profit], [4, 109.11, 28.11]);

  assert.strictEqual((await call(A, 'books_sale_add', { lines: [] })).status, 400);
  assert.strictEqual((await call(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: -2 }] })).status, 400);
  assert.strictEqual((await call(A, 'books_sale_add', { lines: [{ itemId: 'nope000001', qty: 1 }] })).status, 404);
  assert.strictEqual((await call(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 1 }], billDisc: { type: 'x', v: 1 } })).status, 400);
});

test('প্রেসক্রিপশন: rx_dispense আগের মতোই; পরে "বিক্রির হিসাবে তুলুন" — পরিমাণ দেওয়া থেকে, একবারই; অনলাইন অর্ডার আলাদা', async () => {
  const A = 'shopRxA0001';
  await shop(A);
  await ok(A, 'books_item_save', { name: 'Comet 500', qty: 4, cost: 5, price: 8, disc: { type: 'pct', v: 10 } });
  await ok(A, 'books_item_save', { name: 'Seclo', qty: 30, cost: 4.5, price: 7 });
  const med = (name) => ({ name, form: 'Tablet', strength: '', dose: '1+0+1', timing: 'after', days: 30, note: '' });
  await db.doc('prescriptions/rxBooks01').set({ code: 'H4QW7ZK2MP', status: 'active', doctorUid: 'docX', patientUid: null, patient: { name: 'রহিমা' }, medicines: [med('Comet'), med('Seclo'), med('Napa')], dispensed: [] });

  const before = (await ok(A, 'books_sales', { period: 'today' })).items.length;
  const disp = await ok(A, 'rx_dispense', { code: 'H4QW7ZK2MP', items: [{ i: 0, qty: 6 }, { i: 1, qty: 10 }, { i: 2, qty: 2 }] });
  assert.strictEqual(disp.item.dispensed.length, 1, 'ওষুধ দেওয়া আগের মতো');
  assert.strictEqual((await ok(A, 'books_sales', { period: 'today' })).items.length, before, 'নিজে থেকে বিক্রি লেখা হয় না');
  assert.strictEqual((await item(A, 'Seclo')).qty, 30, 'তুলে না রাখলে পরিমাণ বদলায় না');

  const pv = await ok(A, 'books_rx_preview', { code: 'H4QW7ZK2MP' });
  assert.strictEqual(pv.recorded, false);
  assert.strictEqual(pv.source, 'rx');
  assert.deepStrictEqual(pv.lines.map((l) => [l.name, l.qty, l.itemName, l.unitPrice, l.unitDisc, l.unitCost]), [
    ['Comet', 6, 'Comet 500', 8, 0.8, 5], ['Seclo', 10, 'Seclo', 7, 0, 4.5], ['Napa', 2, '', null, 0, null],
  ]);
  // Seclo-র দাম কাউন্টারে বদলানো, Napa দোকানের তালিকায় নেই (দাম ছাড়া, নতুন পণ্য হয়)
  const s = (await ok(A, 'books_sale_add', { source: 'rx', rx: { code: 'H4QW7ZK2MP', at: pv.at }, lines: [{ i: 0, itemId: pv.lines[0].itemId }, { i: 1, unitPrice: 6.5, unitDisc: 0 }, { i: 2 }] })).sale;
  assert.strictEqual(s.source, 'rx');
  assert.deepStrictEqual([s.totals.gross, s.totals.discount, s.totals.net, s.totals.cost, s.totals.profit, s.totals.unpriced], [113, 4.8, 108.2, 75, 33.2, 1]);
  assert.deepStrictEqual(s.lines.map((l) => [l.name, l.qty]), [['Comet 500', 6], ['Seclo', 10], ['Napa', 2]]);
  assert.strictEqual(s.rx.code, 'H4QW7ZK2MP');
  assert.strictEqual((await item(A, 'Comet 500')).qty, 0, '৪টা ছিল, ৬টা দিলে ০');
  assert.strictEqual((await item(A, 'Seclo')).qty, 20);
  assert.ok(await item(A, 'Napa'));
  assert.strictEqual((await call(A, 'books_sale_add', { source: 'rx', rx: { code: 'H4QW7ZK2MP', at: pv.at }, lines: [] })).status, 409, 'একই ভাগ দুবার নয়');
  assert.strictEqual((await ok(A, 'books_rx_preview', { code: 'H4QW7ZK2MP' })).recorded, true);
  // পরিমাণ দোকানের লেখা থেকে — বেশি পাঠালেও নয়
  assert.strictEqual(s.lines.reduce((a, l) => a + l.qty, 0), 18);

  // অনলাইন অর্ডার → উৎস "অনলাইন"
  await db.doc('prescriptions/rxBooks02').set({ code: 'M2KD9VW4YA', status: 'active', doctorUid: 'docX', patientUid: null, patient: { name: 'সুমাইয়া' }, medicines: [med('Seclo')], dispensed: [] });
  await db.doc('rxOrders/ordBooks2').set({ rxId: 'rxBooks02', code: 'M2KD9VW4YA', pharmacyId: A, status: 'out', mode: 'online', createdAt: Date.now() - 3600000 });
  await ok(A, 'rx_dispense', { code: 'M2KD9VW4YA', items: [{ i: 0, qty: 4 }] });
  const pv2 = await ok(A, 'books_rx_preview', { code: 'M2KD9VW4YA' });
  assert.strictEqual(pv2.source, 'online');
  const o = (await ok(A, 'books_sale_add', { source: 'rx', rx: { code: 'M2KD9VW4YA', at: pv2.at } })).sale;
  assert.deepStrictEqual([o.source, o.totals.net], ['online', 28]);
  const d = await dayDoc(A);
  assert.deepStrictEqual([d.src.rx.n, d.src.online.n, d.src.online.net], [1, 1, 28]);

  // অন্য দোকান এই ভাগ তুলতে পারে না
  const X = 'shopRxOther';
  await shop(X);
  assert.strictEqual((await call(X, 'books_rx_preview', { code: 'H4QW7ZK2MP' })).status, 404);
  assert.strictEqual((await call(X, 'books_sale_add', { source: 'rx', rx: { code: 'H4QW7ZK2MP', at: pv.at } })).status, 404);
});

test('নষ্ট/মেয়াদ শেষ = কেনা দামে ক্ষতি; গণনা ঠিক করা লাভ-ক্ষতিতে নয়', async () => {
  const A = 'shopAdjA001';
  await shop(A);
  const s = (await ok(A, 'books_item_save', { name: 'Seclo', qty: 30, cost: 4.5, price: 7 })).item;
  const w = (await ok(A, 'books_adjust', { itemId: s.id, kind: 'writeoff', qty: 5, reason: 'মেয়াদ শেষ' })).adjust;
  assert.deepStrictEqual([w.before, w.after, w.loss], [30, 25, 22.5]);
  const c = (await ok(A, 'books_adjust', { itemId: s.id, kind: 'count', qty: 22 })).adjust;
  assert.deepStrictEqual([c.before, c.after, c.delta, c.loss], [25, 22, -3, 0]);
  assert.strictEqual((await item(A, 'Seclo')).qty, 22);
  const d = await dayDoc(A);
  assert.deepStrictEqual([d.writeOff, d.woq, d.adjusts], [22.5, 5, 2]);
  assert.strictEqual((await call(A, 'books_adjust', { itemId: s.id, kind: 'writeoff', qty: 0 })).status, 400);
  assert.strictEqual((await call(A, 'books_adjust', { itemId: s.id, kind: 'eat', qty: 1 })).status, 404);
  // সব নষ্ট হলে স্টকের তালিকা থেকে সরে
  await ok(A, 'books_adjust', { itemId: s.id, kind: 'writeoff', qty: 22 });
  assert.ok(!(await stockNames(A)).includes('Seclo'));
});

test('হিসাব: জানা ডেটা থেকে বিক্রি, ছাড়, কেনা দাম, লাভ, মার্জিন, ক্ষতি, স্টকের মূল্য, কম স্টক, মেয়াদ, বিক্রি নেই', async () => {
  const A = 'shopRepA001';
  await shop(A);
  const now = Date.now();
  const comet = (await ok(A, 'books_item_save', { name: 'Comet 500', qty: 10, cost: 5, price: 8, disc: { type: 'pct', v: 10 }, lowAt: 5 })).item;
  const seclo = (await ok(A, 'books_item_save', { name: 'Seclo 20', qty: 100, cost: 4.5, price: 7, disc: { type: 'amt', v: 0.5 }, expiry: monthAhead(20) })).item;
  const ace = (await ok(A, 'books_item_save', { name: 'Ace', qty: 20, cost: 1.2, price: 2 })).item;
  const fexo = (await ok(A, 'books_item_save', { name: 'Fexo 120', qty: 6 })).item;
  const old = (await ok(A, 'books_item_save', { name: 'Tofen', qty: 8, cost: 3, price: 4 })).item;
  await db.doc(`shopItems/${A}/items/${old.id}`).update({ createdAt: now - 60 * DAY });

  await ok(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 4 }, { itemId: seclo.id, qty: 10 }], billDisc: { type: 'pct', v: 5 } }); // নিট ৮৯.১১, লাভ ২৪.১১
  await ok(A, 'books_sale_add', { lines: [{ itemId: comet.id, qty: 2, unitPrice: 8, unitDisc: 0 }], billDisc: { type: 'amt', v: 1 } }); // ১৫, লাভ ৫
  await ok(A, 'books_sale_add', { lines: [{ itemId: ace.id, qty: 5, unitPrice: 1 }] }); // ৫, লাভ −১
  await ok(A, 'books_sale_add', { lines: [{ itemId: fexo.id, qty: 2, unitPrice: 10 }] }); // ২০, কেনা দাম নেই
  await ok(A, 'books_adjust', { itemId: seclo.id, kind: 'writeoff', qty: 2 }); // ক্ষতি ৯
  // ৩ দিন আগের বিক্রি — ৭ দিনে আছে, আজ নয়
  await ok(A, 'books_sale_add', { at: now - 3 * DAY, lines: [{ itemId: seclo.id, qty: 4, unitPrice: 7, unitDisc: 0 }] }); // ২৮, লাভ ১০

  const t = await ok(A, 'books_report', { period: 'today' });
  assert.deepStrictEqual(t.totals, {
    sales: 4, gross: 143, discount: 13.89, net: 129.11, cost: 81, profit: 28.11, margin: 25.8, uncosted: 20,
    writeOff: 9, netProfit: 19.11, bought: 0, purchases: 0,
  });
  assert.strictEqual(t.daily.length, 1);
  assert.deepStrictEqual(t.topSales.map((x) => [x.name, x.net]), [['Seclo 20', 61.75], ['Comet 500', 42.36], ['Fexo 120', 20], ['Ace', 5]]);
  assert.deepStrictEqual(t.topProfit.map((x) => [x.name, x.profit]), [['Seclo 20', 16.75], ['Comet 500', 12.36]]);
  assert.deepStrictEqual(t.belowCost.map((x) => [x.name, x.loss, x.lossQty]), [['Ace', 1, 5]]);
  assert.deepStrictEqual([t.bySource.counter.n, t.bySource.counter.net, t.bySource.rx.n], [4, 129.11, 0]);
  // স্টক: Comet ৪, Seclo ৮৪ (১০০−১০−২−৪), Ace ১৫, Fexo ৪ (দাম নেই), Tofen ৮
  assert.deepStrictEqual(t.stock, { items: 5, counted: 5, atCost: 440, atPrice: 682, noCost: 1, noPrice: 1 });
  assert.deepStrictEqual(t.low.map((x) => [x.name, x.qty]), [['Comet 500', 4]]);
  assert.deepStrictEqual(t.expiring.map((x) => x.name), ['Seclo 20']);
  assert.ok(t.expiring[0].expDays >= 19 && t.expiring[0].expDays <= 60);
  assert.deepStrictEqual(t.noSale.map((x) => x.name), ['Tofen'], '৩০ দিনে বিক্রি নেই (নতুনগুলো নয়)');

  const w = await ok(A, 'books_report', { period: '7d' });
  assert.strictEqual(w.daily.length, 7);
  assert.deepStrictEqual([w.totals.sales, w.totals.net, w.totals.profit, w.totals.netProfit], [5, 157.11, 38.11, 29.11]);
  assert.deepStrictEqual(w.daily[3], { day: B.dayKey(now - 3 * DAY), net: 28, profit: 10, sales: 1 });
  assert.strictEqual((await ok(A, 'books_report', { period: '30d' })).daily.length, 30);
  const m = await ok(A, 'books_report', { period: 'month' });
  assert.strictEqual(m.daily.length, Number(B.dayKey(now).slice(6)));
  assert.strictEqual(m.to, B.dayKey(now));

  // সব কিছু দেখার খরচ: দিনের ডক (≤৩১) আর পণ্য
  const pd = B.periodOf('month', Date.parse('2026-10-31T23:30:00+06:00'));
  assert.strictEqual(pd.days.length, 31);
  assert.strictEqual(pd.days[0], '20261001');
  assert.strictEqual(pd.days[30], '20261031');
});

test('শুধু দোকানের মালিক: অন্য কেউ, অতিথি, দোকান না থাকলে নয়; অন্য দোকানের কিছু দেখা/বদলানো যায় না', async () => {
  const A = 'shopOwnA001';
  await shop(A);
  const c = (await ok(A, 'books_item_save', { name: 'Comet 500', qty: 10, cost: 5, price: 8 })).item;
  const sale = await ok(A, 'books_sale_add', { lines: [{ itemId: c.id, qty: 1 }] });
  const U = 'plainUser01';
  F.users.set(U, { uid: U, email: 'u@example.com' });
  for (const [action, body] of [['books_items', {}], ['books_item_save', { name: 'X' }], ['books_sale_add', { lines: [{ itemId: c.id, qty: 1 }] }], ['books_report', {}], ['books_purchase_add', { lines: [{ name: 'X', qty: 1 }] }], ['books_adjust', { itemId: c.id, kind: 'writeoff', qty: 1 }]]) {
    assert.strictEqual((await call(U, action, body)).status, 403, action);
  }
  assert.strictEqual((await call(null, 'books_items')).status, 401, 'লগইন ছাড়া নয়');
  // কেয়ারগিভার হয়েও নয়
  assert.strictEqual((await call(U, 'books_items', { asUid: A })).status, 403);
  // অন্য দোকান: নিজের খালি খাতা, অন্যেরটা বদলায় না
  const X = 'shopOwnX001';
  await shop(X);
  assert.deepStrictEqual((await ok(X, 'books_items')).items, []);
  assert.strictEqual((await call(X, 'books_sale_delete', { id: sale.id })).status, 404);
  assert.strictEqual((await call(X, 'books_adjust', { itemId: c.id, kind: 'writeoff', qty: 1 })).status, 404);
  assert.strictEqual((await call(X, 'books_item_delete', { id: c.id })).status, 200);
  assert.ok(await item(A, 'Comet 500'), 'অন্য দোকানের পণ্য মোছে না');
  assert.strictEqual((await ok(X, 'books_report', { period: 'today' })).totals.sales, 0);
});

test('অ্যাকাউন্ট মুছলে পণ্য আর পুরো হিসাব-খাতা মোছে', async () => {
  const A = 'shopDelA001';
  await shop(A, ['Napa']);
  const c = (await ok(A, 'books_item_save', { name: 'Comet 500', qty: 10, cost: 5, price: 8 })).item;
  await ok(A, 'books_purchase_add', { lines: [{ itemId: c.id, qty: 5, unitCost: 5 }] });
  await ok(A, 'books_sale_add', { lines: [{ itemId: c.id, qty: 2 }] });
  await ok(A, 'books_adjust', { itemId: c.id, kind: 'writeoff', qty: 1 });
  await ok(A, 'books_items');
  const mine = () => [...F.store.keys()].filter((k) => k.startsWith(`shopItems/${A}/`) || k.startsWith(`shopBooks/${A}/`) || k === `pharmacyStock/${A}`);
  assert.ok(mine().length >= 6);
  await account.deletion.deleteNow(A, `${A}@example.com`);
  assert.deepStrictEqual(mine(), []);
});
