// গোপনীয়তার ফাঁক বন্ধ (১৮ অক্টোবর ২০২৬):
//  ১) প্যাথলজি রিপোর্টের "আগের ফল" — শুধু নিজের আগের অর্ডারের; রোগী আর (অনুমতি পাওয়া) ডাক্তার সব ল্যাবের
//  ২) অনলাইন অর্ডারের ঠিকানা-ফোন — দোকান সরালে / মালিকের অ্যাকাউন্ট মুছলে এখনই, বন্ধ অর্ডারে ৭ দিন পর (দৈনিক ক্রন)
//  ৩) "বাসায় যেতে পারব না" — রোগীর ঠিকানা-ফোন সাথে সাথে মোছে, দিন/সময়/অবস্থা থাকে
//  ৪) দোকানের হিসাব-খাতার বিক্রিতে রোগীর নাম রাখা হয় না; পুরোনোগুলো দেখানো হয় না, দৈনিক ক্রন একবারে মুছে দেয়
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
process.env.CRON_SECRET = 'privacy-gaps-secret';
const api = require(path.join(SERVER, 'api/community.js'));
const account = require(path.join(SERVER, 'lib/account.js'));
const RX = require(path.join(SERVER, 'lib/rxnet.js'));
const B = require(path.join(SERVER, 'lib/shopbooks.js'));
const cron = require(path.join(SERVER, 'api/cron/daily.js'));
const { db } = F.firebase;
const DAY = 24 * 3600 * 1000;

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
async function user(uid, extra = {}) {
  F.users.set(uid, { uid, email: `${uid}@example.com` });
  await db.doc(`users/${uid}`).set({ name: `নাম ${uid}`, lang: 'bn', ...extra });
}
const data = async (p) => (await db.doc(p).get()).data();
const med = (name) => ({ name, form: 'Tablet', strength: '', dose: '1+0+1', timing: 'after', days: 30, note: '' });

// ---------------------------------------------------------------------------
test('১) ল্যাব শুধু নিজের আগের ফল পায়; রোগী আর ডাক্তার সব ল্যাবের', async () => {
  const P = 'pgPatient01', L1 = 'pgLabOne001', L2 = 'pgLabTwo001', L3 = 'pgLabNew001', D = 'pgDoctor001';
  for (const u of [P, L1, L2, L3, D]) await user(u);
  for (const l of [L1, L2, L3]) await db.doc(`labs/${l}`).set({ name: `ল্যাব ${l}`, status: 'approved' });
  const now = Date.now();
  const order = (id, labId, at, result) => db.doc(`labOrders/${id}`).set({
    rxId: 'pgRx1', code: 'PGRXQQ5555', labId, labName: `ল্যাব ${labId}`, patientUid: P, patientName: 'রোগী', doctorUid: D, doctorName: 'ডা.',
    tests: ['CBC'], status: 'reported', createdAt: at, reportedAt: at,
    report: { items: [{ test: 'Hb', code: 'cbc', key: 'hb', result, unit: 'g/dL', flag: '' }], note: '', photos: [], reportedAt: at },
  });
  await order('pgOld1', L1, now - 30 * DAY, '10.1'); // এই ল্যাবের নিজের আগের
  await order('pgOld2', L2, now - 20 * DAY, '10.9'); // অন্য ল্যাবের আগের
  await order('pgCur1', L1, now - DAY, '11.5');
  await order('pgCur3', L3, now - DAY / 2, '12.0'); // এই ল্যাবে রোগীর প্রথম অর্ডার
  await db.doc('rxReviews/pgCur1').set({ doctorUid: D, patientUid: P, status: 'paid' });

  const lab = await ok(L1, 'lab_order_get', { id: 'pgCur1' });
  assert.deepStrictEqual(lab.history['cbc.hb'].map((h) => h.result), ['10.1'], 'ল্যাব শুধু নিজের আগের ফল');
  assert.ok(!JSON.stringify(lab).includes('10.9') && !JSON.stringify(lab).includes(`ল্যাব ${L2}`), 'অন্য ল্যাবের ফল/নাম নয়');

  const fresh = await ok(L3, 'lab_order_get', { id: 'pgCur3' });
  assert.deepStrictEqual(fresh.history, {}, 'নিজের আগের অর্ডার না থাকলে খালি (ক্লায়েন্ট {} সামলায়)');
  assert.strictEqual(fresh.item.report.items[0].result, '12.0', 'রিপোর্ট নিজে আগের মতো');

  const pat = await ok(P, 'lab_order_get', { id: 'pgCur1' });
  assert.deepStrictEqual(pat.history['cbc.hb'].map((h) => h.result), ['10.9', '10.1'], 'রোগী সব ল্যাবের, নতুন আগে');
  const doc = await ok(D, 'lab_order_get', { id: 'pgCur1' });
  assert.deepStrictEqual(doc.history['cbc.hb'].map((h) => h.result), ['10.9', '10.1'], 'ডাক্তার (টাকা মিললে) সব ল্যাবের');
});

// ---------------------------------------------------------------------------
test('২) অনলাইন অর্ডার: বন্ধ হলে ৭ দিন পর ঠিকানা-ফোন মোছে; রোগীর ইতিহাস আগের মতো', async () => {
  const P = 'pgPatient02', S = 'pgShop00002';
  await user(P);
  await user(S);
  await db.doc(`pharmacies/${S}`).set({ name: 'দোকান', status: 'approved', district: 'ময়মনসিংহ', phone: '01711111111' });
  await db.doc('prescriptions/pgRx2').set({ code: 'PGRXQQ2222', status: 'active', doctorUid: 'docX', patientUid: P, patient: { name: 'রোগী দুই' }, medicines: [med('Seclo')], dispensed: [] });
  const online = (id, extra) => db.doc(`rxOrders/${id}`).set({
    rxId: 'pgRx2', code: 'PGRXQQ2222', pharmacyId: S, pharmacyName: 'দোকান', patientUid: P, patientName: 'রোগী দুই',
    mode: 'online', address: 'বাসা ১২, চরপাড়া', phone: '01711000222', status: 'sent', createdAt: Date.now() - DAY, ...extra,
  });
  await online('pgOrdCancel');
  await online('pgOrdWithdraw');
  await online('pgOrdDone', { status: 'out' });
  await online('pgOrdOpen', { rxId: 'pgRx2b' });

  await ok(S, 'rx_order_update', { id: 'pgOrdCancel', status: 'cancelled', reason: 'স্টক নেই' });
  await ok(P, 'rx_order_withdraw', { id: 'pgOrdWithdraw' });
  await ok(S, 'rx_dispense', { code: 'PGRXQQ2222', items: [{ i: 0, qty: 10 }] });
  for (const [id, st] of [['pgOrdCancel', 'cancelled'], ['pgOrdWithdraw', 'withdrawn'], ['pgOrdDone', 'done']]) {
    const x = await data(`rxOrders/${id}`);
    assert.strictEqual(x.status, st);
    assert.ok(x.closedAt > 0, `${id}: closedAt`);
    assert.ok(Math.abs(x.purgeContactAt - (x.closedAt + 7 * DAY)) < 5, `${id}: ৭ দিন পর মোছার দিন`);
    assert.strictEqual(x.address, 'বাসা ১২, চরপাড়া', 'এখনই মোছে না');
  }

  // ৭ দিন হয়নি: কিছু মোছে না
  assert.strictEqual(await RX.purgeClosedContacts(Date.now() + 6 * DAY), 0);
  // ৭ দিন পেরোলে দৈনিক ক্রন (আসল হ্যান্ডলার) মোছে
  const realNow = Date.now;
  Date.now = () => realNow() + 7 * DAY + 60000;
  let out;
  try {
    const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
    await cron({ method: 'GET', headers: { authorization: `Bearer ${process.env.CRON_SECRET}` }, query: {} }, res);
    assert.strictEqual(res.statusCode, 200, JSON.stringify(out));
  } finally {
    Date.now = realNow;
  }
  assert.ok(out.privacy.rxOrderContactsPurged >= 3, JSON.stringify(out.privacy));
  for (const id of ['pgOrdCancel', 'pgOrdWithdraw', 'pgOrdDone']) {
    const x = await data(`rxOrders/${id}`);
    assert.deepStrictEqual([x.address, x.phone, x.purgeContactAt], [undefined, undefined, undefined], `${id}: মুছেছে`);
    assert.ok(x.closedAt > 0 && x.status, 'অবস্থা/সময় থাকে');
  }
  const open = await data('rxOrders/pgOrdOpen');
  assert.deepStrictEqual([open.address, open.phone], ['বাসা ১২, চরপাড়া', '01711000222'], 'চালু অর্ডার অক্ষত');
  assert.strictEqual(await RX.purgeClosedContacts(Date.now() + 30 * DAY), 0, 'আবার চালালে কিছু পড়ে না (চিহ্ন সরে গেছে)');

  // রোগীর নিজের ইতিহাস আগের মতো
  const act = await ok(P, 'rx_activity', { rxId: 'pgRx2' });
  assert.deepStrictEqual(act.orders.map((o) => o.status).sort(), ['cancelled', 'done', 'withdrawn']);
});

test('২খ) নিয়মের আগে বন্ধ হওয়া অনলাইন অর্ডার: একবারের পরিষ্কার', async () => {
  const S = 'pgShop0002b';
  const now = Date.now();
  const base = { rxId: 'pgRxL', pharmacyId: S, patientUid: 'pgPatL', mode: 'online', address: 'পুরোনো ঠিকানা', phone: '01711000333', createdAt: now - 40 * DAY };
  await db.doc('rxOrders/pgLegacyOld').set({ ...base, status: 'done', doneAt: now - 10 * DAY });
  await db.doc('rxOrders/pgLegacyNew').set({ ...base, status: 'cancelled', updatedAt: now - 2 * DAY });
  await db.doc('rxOrders/pgLegacyOpen').set({ ...base, status: 'accepted' });
  await db.doc('rxOrders/pgLegacyShop').set({ ...base, mode: 'shop', address: undefined, phone: undefined, status: 'done', doneAt: now - 10 * DAY });
  await db.doc('maintenance/rxOrderContacts').delete();

  const r = await RX.backfillClosedContacts(now);
  assert.strictEqual(r.done, true);
  const old = await data('rxOrders/pgLegacyOld');
  assert.deepStrictEqual([old.address, old.phone], [undefined, undefined], '৭ দিন পেরোনো: এখনই মোছে');
  const nw = await data('rxOrders/pgLegacyNew');
  assert.strictEqual(nw.address, 'পুরোনো ঠিকানা', '৭ দিন হয়নি: থাকে');
  assert.strictEqual(nw.purgeContactAt, now - 2 * DAY + 7 * DAY, 'মোছার দিন বসে');
  assert.strictEqual((await data('rxOrders/pgLegacyOpen')).address, 'পুরোনো ঠিকানা', 'চালু অর্ডার অক্ষত');
  assert.deepStrictEqual(await RX.backfillClosedContacts(now), { done: true, stripped: 0, marked: 0 }, 'শেষ হলে আর চলে না');
  await RX.purgeClosedContacts(now + 6 * DAY);
  assert.strictEqual((await data('rxOrders/pgLegacyNew')).address, undefined, 'পরে নিয়মিত ক্রনে মোছে');
});

test('২গ) ফার্মেসি সরালে আর মালিকের অ্যাকাউন্ট মুছলে: সব অর্ডারের ঠিকানা-ফোন এখনই', async () => {
  for (const S of ['pgShopDel01', 'pgShopAcc01']) {
    await user(S);
    await db.doc(`pharmacies/${S}`).set({ name: 'দোকান', status: 'approved', district: 'ময়মনসিংহ' });
    // এক পাতার (৩০০) বেশি — পাতায় পাতায় শেষ পর্যন্ত
    for (let i = 0; i < 305; i++) {
      await db.doc(`rxOrders/${S}_o${i}`).set({
        rxId: `r${i}`, pharmacyId: S, patientUid: 'pgSomeone', mode: 'online', address: `ঠিকানা ${i}`, phone: '01711000444',
        status: i % 3 === 0 ? 'sent' : 'done', createdAt: i,
      });
    }
  }
  await db.doc('rxOrders/pgOtherShop').set({ rxId: 'rz', pharmacyId: 'pgOtherShop1', mode: 'online', address: 'অন্য দোকানের', phone: '01711000555', status: 'done' });

  await ok('pgShopDel01', 'pharmacy_delete');
  await account.deletion.deleteNow('pgShopAcc01', 'pgShopAcc01@example.com');
  for (const S of ['pgShopDel01', 'pgShopAcc01']) {
    const left = [...F.store.entries()].filter(([k, v]) => k.startsWith('rxOrders/') && v.pharmacyId === S && (v.address || v.phone));
    assert.strictEqual(left.length, 0, `${S}: কোনো অর্ডারে ঠিকানা-ফোন নেই`);
    assert.strictEqual((await data(`rxOrders/${S}_o0`)).status, 'sent', 'অর্ডার নিজে থাকে');
  }
  assert.strictEqual((await data('rxOrders/pgOtherShop')).address, 'অন্য দোকানের', 'অন্য দোকানের অর্ডার অক্ষত');
});

// ---------------------------------------------------------------------------
test('৩) "বাসায় যেতে পারব না": ঠিকানা-ফোন সাথে সাথে মোছে, দিন/সময়/অবস্থা থাকে', async () => {
  const P = 'pgPatient03', L = 'pgLabHome01';
  await user(P);
  await user(L);
  await db.doc(`labs/${L}`).set({ name: 'বাসার ল্যাব', status: 'approved' });
  await db.doc('labOrders/pgHome1').set({
    rxId: 'pgRx3', code: 'PGRXQQ3333', labId: L, labName: 'বাসার ল্যাব', patientUid: P, patientName: 'রোগী তিন', doctorUid: 'docX',
    tests: ['CBC'], status: 'sent', createdAt: Date.now(),
    home: { address: 'বাসা ৭, গাঙ্গিনারপাড়', phone: '01711000666', date: Date.now() + DAY, slot: 'morning', note: '', status: 'requested', fee: 100 },
  });
  await ok(L, 'lab_home_decline', { id: 'pgHome1', note: 'লোক নেই' });
  const h = (await data('labOrders/pgHome1')).home;
  assert.deepStrictEqual([h.address, h.phone], [undefined, undefined]);
  assert.strictEqual(h.status, 'declined');
  assert.strictEqual(h.slot, 'morning');
  assert.ok(h.date > 0 && h.declinedAt > 0 && h.declineNote === 'লোক নেই');
  const pv = (await ok(P, 'lab_order_get', { id: 'pgHome1' })).item.home;
  assert.strictEqual(pv.status, 'declined', 'রোগী অবস্থা দেখেন');
  const lv = (await ok(L, 'lab_orders')).items.find((o) => o.id === 'pgHome1');
  assert.ok(!JSON.stringify(lv).includes('গাঙ্গিনারপাড়') && !JSON.stringify(lv).includes('01711000666'));
});

// ---------------------------------------------------------------------------
test('৪) হিসাব-খাতা: বিক্রিতে রোগীর নাম নয়, শুধু প্রেসক্রিপশনের কোড; পুরোনোগুলো ক্রনে মোছে', async () => {
  const S = 'pgShopBook1';
  await user(S);
  await db.doc(`pharmacies/${S}`).set({ name: 'দোকান', status: 'approved', district: 'ময়মনসিংহ' });
  await db.doc('prescriptions/pgRx4').set({ code: 'PGRXQQ4444', status: 'active', doctorUid: 'docX', patientUid: null, patient: { name: 'গোপন রোগী' }, medicines: [med('Seclo')], dispensed: [] });
  await ok(S, 'rx_dispense', { code: 'PGRXQQ4444', items: [{ i: 0, qty: 5 }] });
  const pv = await ok(S, 'books_rx_preview', { code: 'PGRXQQ4444' });
  assert.strictEqual(pv.patient, undefined, 'প্রিভিউতে নাম নয়');
  const sale = (await ok(S, 'books_sale_add', { source: 'rx', rx: { code: 'PGRXQQ4444', at: pv.at } })).sale;
  assert.deepStrictEqual(Object.keys(sale.rx).sort(), ['at', 'code', 'rxId']);
  const stored = [...F.store.entries()].filter(([k]) => k.startsWith(`shopBooks/${S}/`));
  assert.ok(stored.length && !JSON.stringify(stored).includes('গোপন রোগী'), 'হিসাব-খাতার কোথাও নাম নেই');
  assert.strictEqual((await call(S, 'books_sale_add', { source: 'rx', rx: { code: 'PGRXQQ4444', at: pv.at } })).status, 409, 'একই ভাগ দুবার নয় (কোড/id দিয়ে)');

  // আগে তোলা বিক্রি (নামসহ): দেখানো হয় না, ক্রন একবারে মোছে
  const old = `shopBooks/${S}/sales/rx_pgOld_1`;
  await db.doc(old).set({ at: Date.now(), day: B.dayKey(Date.now()), source: 'rx', lines: [], totals: { net: 0 }, rx: { rxId: 'pgOld', code: 'PGRXQQ6666', at: 1, patient: 'পুরোনো রোগী' } });
  await db.doc(`shopBooks/${S}/sales/counter1`).set({ at: Date.now(), source: 'counter', lines: [], totals: { net: 0 }, customer: 'ক্রেতা' });
  const list = await ok(S, 'books_sales', { period: 'today' });
  assert.ok(!JSON.stringify(list).includes('পুরোনো রোগী'), 'তালিকায় নাম নয়');
  assert.strictEqual(list.items.find((x) => x.id === 'rx_pgOld_1').rx.code, 'PGRXQQ6666', 'কোড থাকে (ওয়েব কোড দেখায়)');

  await db.doc('maintenance/salePatientNames').delete();
  const r = await B.stripSalePatientNames(Date.now());
  assert.strictEqual(r.done, true);
  assert.ok(r.cleaned >= 1);
  const x = await data(old);
  assert.deepStrictEqual(x.rx, { rxId: 'pgOld', code: 'PGRXQQ6666', at: 1 }, 'নাম মুছেছে, কোড/id থাকে');
  assert.strictEqual((await data(`shopBooks/${S}/sales/counter1`)).customer, 'ক্রেতা', 'কাউন্টারের বিক্রি অক্ষত');
  assert.deepStrictEqual(await B.stripSalePatientNames(Date.now()), { done: true, cleaned: 0 }, 'শেষ হলে আর চলে না');
});
