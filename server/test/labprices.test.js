// প্যাথলজির পরীক্ষা ও দাম, আর বাসা থেকে নমুনা (১৮ অক্টোবর ২০২৬, labprices.js)।
// দামের তালিকা (শুধু অনুমোদিত প্যাথলজি, যাচাই), সবার দেখা তালিকা, প্রেসক্রিপশনের হিসাব আর সাজানো, পাঠানোর সময়ের দাম রেখে দেওয়া,
// বাসা থেকে নমুনা (যাচাই, প্যাথলজি না নিলে নয়, সময় নিশ্চিত → রোগী জানেন, যেতে পারব না), ঠিকানার গোপনীয়তা,
// কেয়ারগিভার (asUid), ডেমো আর আসল আলাদা, অ্যাকাউন্ট মোছা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const account = require(path.join(SERVER, 'lib/account'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const SADAR = 466;
const TRISHAL = 463;
const DAY = 24 * 60 * 60 * 1000;
const today = require(path.join(SERVER, 'lib/prescriptions')).dhakaToday();

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.9.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  if (res.statusCode !== 200) { const e = new Error(`${action} → ${res.statusCode}: ${out && out.error}`); e.status = res.statusCode; e.body = out; throw e; }
  return out;
}
async function fails(uid, action, body, status) {
  try { await call(uid, action, body); } catch (e) { assert.strictEqual(e.status, status, e.message); return e.body; }
  throw new Error(`${action} should fail`);
}
const pushesTo = (uid) => F.pushes.filter((p) => p.uid === uid);
const lastPush = (uid) => pushesTo(uid).slice(-1)[0];
const clearCache = () => require(path.join(SERVER, 'lib/memcache')).clear();
const leaks = (obj, text) => JSON.stringify(obj).includes(text);

const U = {
  doc: { uid: 'lpDoctor', email: 'doc@lp.test', name: 'ডা. দাম' },
  pat: { uid: 'lpPatient', email: 'pat@lp.test', name: 'রোগী রহিম', phone: '01711000401' },
  cg: { uid: 'lpCaregiver', email: 'cg@lp.test', name: 'ছেলে সাকিব' },
  other: { uid: 'lpOther', email: 'x@lp.test', name: 'অন্য কেউ' },
  l1: { uid: 'lpLabCity', email: 'l1@lp.test', name: 'সিটি ল্যাব' },
  l2: { uid: 'lpLabPopular', email: 'l2@lp.test', name: 'পপুলার ল্যাব' },
  l3: { uid: 'lpLabNoList', email: 'l3@lp.test', name: 'তালিকাহীন ল্যাব' },
  l4: { uid: 'lpLabPending', email: 'l4@lp.test', name: 'অপেক্ষার ল্যাব' },
  l5: { uid: 'lpLabTrishal', email: 'l5@lp.test', name: 'ত্রিশাল ল্যাব' },
  l6: { uid: 'lpLabTshOnly', email: 'l6@lp.test', name: 'শুধু টিএসএইচ ল্যাব' },
};
const P = U.pat.uid, D = U.doc.uid, CG = U.cg.uid, X = U.other.uid;
const L1 = U.l1.uid, L2 = U.l2.uid, L3 = U.l3.uid, L4 = U.l4.uid, L5 = U.l5.uid, L6 = U.l6.uid;
const HOME = 'বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ';

function seed() {
  const s = F.store;
  for (const u of Object.values(U)) {
    F.users.set(u.uid, { uid: u.uid, email: u.email, displayName: u.name });
    s.set(`users/${u.uid}`, { name: u.name, phone: u.phone || '', lang: 'bn', fcmTokens: [] });
  }
  s.set(`badges/${D}`, { type: 'doctor', status: 'approved', regNo: 'A-777', name: U.doc.name });
  s.set(`providers/${D}`, { type: 'doctor', name: U.doc.name, degrees: 'MBBS', specialties: ['medicine'], listed: true, chambers: [] });
  const lab = (u, x = {}) => s.set(`labs/${u.uid}`, { name: u.name, status: 'approved', district: DIST, upazilaId: SADAR, address: 'চরপাড়া', phone: '01711000498', licenseNo: 'DGHS-L', ownerUid: u.uid, ...x });
  lab(U.l1, { homeCollection: true });
  lab(U.l2);
  lab(U.l3, { homeCollection: true });
  lab(U.l4, { status: 'pending' });
  lab(U.l5, { upazilaId: TRISHAL });
  lab(U.l6);
  s.set(`careLinks/${P}_${CG}`, { patientUid: P, caretakerUid: CG, status: 'active' });
}

test('পরীক্ষা ও দাম, বাসা থেকে নমুনা — পুরো পথ', async () => {
  seed();

  // ---------- ১. দামের তালিকা: শুধু অনুমোদিত প্যাথলজি, যাচাই ----------
  const good = [
    { code: 'cbc', price: 400, tat: 'একই দিন' },
    { code: 'hba1c', price: '১,০০০' },
    { name: 'Lipid profile', price: 1200, tat: 'একই দিন' }, // নিজে লেখা, কিন্তু ছকের নাম — কোড নিজে থেকে
    { name: 'Serum Vitamin B12', price: 1800, tat: '৪৮ ঘণ্টা' }, // ছকে নেই
    { code: 'ecg', price: 400, on: false }, // এখন হয় না
  ];
  await fails(P, 'lab_prices_set', { items: good }, 403);
  await fails(L4, 'lab_prices_set', { items: good }, 403);
  await fails(P, 'lab_prices_get', {}, 403);
  await fails(L1, 'lab_prices_set', { items: [{ code: 'cbc' }] }, 400); // দাম নেই
  await fails(L1, 'lab_prices_set', { items: [{ code: 'cbc', price: 0 }] }, 400);
  await fails(L1, 'lab_prices_set', { items: [{ code: 'cbc', price: 'চারশো' }] }, 400);
  await fails(L1, 'lab_prices_set', { items: [{ code: 'cbc', price: 400 }, { name: 'CBC', price: 300 }] }, 400); // দুবার
  await fails(L1, 'lab_prices_set', { items: [{ code: 'no_such', price: 400 }] }, 400);
  await fails(L1, 'lab_prices_set', { items: [{ name: '', price: 400 }] }, 400);
  await fails(L1, 'lab_prices_set', { items: good, discountPct: 95 }, 400);
  await fails(L1, 'lab_prices_set', { items: good, homeFee: -5 }, 400);
  await fails(L1, 'lab_prices_set', { items: Array.from({ length: 201 }, (_, i) => ({ name: `T${i}`, price: 10 })) }, 400);
  await fails(L1, 'lab_prices_set', {}, 400);

  const saved = await call(L1, 'lab_prices_set', { items: good, discountPct: 10, homeFee: 150 });
  assert.strictEqual(saved.items.length, 5);
  const lip = saved.items.find((i) => i.name === 'Lipid Profile');
  assert.ok(lip && lip.code === 'lipid', 'নিজে লেখা নাম ছকের সাথে মিলে কোড পেল');
  assert.strictEqual(saved.items.find((i) => i.code === 'hba1c').price, 1000, 'বাংলা অঙ্ক আর কমা চলে');
  assert.strictEqual(saved.items.find((i) => i.name === 'Serum Vitamin B12').code, null);
  assert.strictEqual(saved.items.find((i) => i.code === 'ecg').on, false);
  assert.deepStrictEqual([saved.discountPct, saved.homeFee, saved.homeCollection], [10, 150, true]);
  const got = await call(L1, 'lab_prices_get');
  assert.deepStrictEqual(got.items, saved.items);
  // বাসা থেকে নমুনা না নিলে বাসার চার্জ থাকে না
  const s2 = await call(L2, 'lab_prices_set', { items: [{ code: 'cbc', price: 350, tat: 'একই দিন' }, { code: 'hba1c', price: 900, tat: '২৪ ঘণ্টা' }], homeFee: 100 });
  assert.strictEqual(s2.homeFee, null);
  await call(L5, 'lab_prices_set', { items: [{ code: 'cbc', price: 350 }, { code: 'hba1c', price: 900 }] }); // L2-এর সমান দাম, অন্য উপজেলা
  await call(L6, 'lab_prices_set', { items: [{ code: 'tsh', price: 800 }] });
  // সব খালি করলে তালিকা মুছে যায়
  await call(L3, 'lab_prices_set', { items: [{ code: 'cbc', price: 100 }] });
  assert.ok(F.store.has(`labPrices/${L3}`));
  await call(L3, 'lab_prices_set', { items: [] });
  assert.ok(!F.store.has(`labPrices/${L3}`));

  // ---------- ২. সবার দেখা তালিকা (অতিথিও): এলাকার তালিকায় সারাংশ, বিস্তারিতে পুরো তালিকা ----------
  const pub = (await call(null, 'labs', { district: DIST })).items;
  const s1 = pub.find((l) => l.id === L1).prices;
  assert.deepStrictEqual(s1, { count: 4, from: 400, discountPct: 10, homeFee: 150, updatedAt: s1.updatedAt }, 'তালিকায় শুধু সারাংশ (বন্ধ ECG গোনা নয়)');
  const p1 = (await call(null, 'lab_prices_public', { labId: L1 })).prices;
  assert.deepStrictEqual(p1.items.map((i) => [i.name, i.price]), [['CBC (Complete Blood Count)', 400], ['HbA1c', 1000], ['Lipid Profile', 1200], ['Serum Vitamin B12', 1800]], 'বন্ধ পরীক্ষা (ECG) দেখায় না');
  assert.deepStrictEqual([p1.discountPct, p1.homeFee], [10, 150]);
  assert.deepStrictEqual((await call(X, 'lab_prices_public', { labId: L1 })).prices, p1, 'লগইন করা হলেও একই');
  assert.strictEqual(pub.find((l) => l.id === L2).prices.homeFee, null);
  assert.strictEqual(pub.find((l) => l.id === L3).prices, null, 'তালিকা নেই');
  assert.strictEqual((await call(null, 'lab_prices_public', { labId: L3 })).prices, null);
  await fails(null, 'lab_prices_public', { labId: L4 }, 404); // অপেক্ষার প্যাথলজি নয়
  await fails(null, 'lab_prices_public', { labId: '../x' }, 404);
  assert.ok(!pub.some((l) => l.id === L4), 'অপেক্ষার প্যাথলজি নেই');

  // ---------- ৩. প্রেসক্রিপশন: কোথায় কয়টা হয়, কত — সাজানো ----------
  const pt = (await call(D, 'rx_patient_add', { name: 'রোগী রহিম', age: 52, gender: 'male', phone: '01711000401' })).patient;
  const rx = await call(D, 'rx_create', { rxPatientId: pt.id, tests: ['CBC', 'HbA1c', 'Lipid profile', 'ECG'], advice: 'পরীক্ষা করে আসবেন' });
  await call(P, 'rx_claim', { code: rx.code, phone: '01711000401' });

  let opt = await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST, upazilaId: SADAR });
  assert.strictEqual(opt.lastHome, null);
  const o1 = opt.items.find((l) => l.id === L1);
  assert.deepStrictEqual([o1.priced, o1.have, o1.total, o1.missing], [true, 3, 4, ['ECG']], 'ECG বন্ধ — নেই');
  assert.deepStrictEqual([o1.subtotal, o1.discountPct, o1.estimate, o1.homeFee, o1.homeCollection], [2600, 10, 2340, 150, true]);
  assert.strictEqual(o1.tat, 'একই দিন');
  assert.deepStrictEqual(o1.items.map((i) => [i.test, i.price]), [['CBC', 400], ['HbA1c', 1000], ['Lipid profile', 1200]]);
  const o2 = opt.items.find((l) => l.id === L2);
  assert.deepStrictEqual([o2.have, o2.estimate, o2.tat], [2, 1250, 'একই দিন / ২৪ ঘণ্টা']);
  const o3 = opt.items.find((l) => l.id === L3);
  assert.deepStrictEqual([o3.priced, o3.have, o3.estimate], [false, null, null], 'দাম জানা নেই');
  assert.ok(!opt.items.some((l) => l.id === L4));
  // বেশি পরীক্ষা আগে; একই হলে কম দাম; তারপর কাছের (একই উপজেলা); তালিকা নেই — একটাও না হওয়ার আগে
  assert.deepStrictEqual(opt.items.map((l) => l.id), [L1, L2, L5, L3, L6]);
  // শুধু CBC আর HbA1c: সিটি ১২৬০ (ছাড়সহ), পপুলার ১২৫০ → পপুলার আগে
  opt = await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST, upazilaId: SADAR, tests: ['CBC', 'HbA1c'] });
  assert.deepStrictEqual(opt.items.slice(0, 3).map((l) => [l.id, l.estimate]), [[L2, 1250], [L5, 1250], [L1, 1260]]);
  // ত্রিশালে থাকলে ত্রিশালেরটা আগে (একই দাম)
  opt = await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST, upazilaId: TRISHAL, tests: ['CBC', 'HbA1c'] });
  assert.deepStrictEqual(opt.items.slice(0, 2).map((l) => l.id), [L5, L2]);
  // অন্যের প্রেসক্রিপশন নয়; কেয়ারগিভার রোগীর হয়ে পারেন, নিজের হয়ে নয়
  await fails(X, 'rx_lab_options', { rxId: rx.id, district: DIST }, 404);
  await fails(CG, 'rx_lab_options', { rxId: rx.id, district: DIST }, 404);
  await fails(D, 'rx_lab_options', { rxId: rx.id, district: DIST }, 404);
  const cgOpt = await call(CG, 'rx_lab_options', { asUid: P, rxId: rx.id, district: DIST, upazilaId: SADAR });
  assert.deepStrictEqual(cgOpt.items.map((l) => l.id), [L1, L2, L5, L3, L6]);
  await fails(P, 'rx_lab_options', { rxId: rx.id, district: 'কোথাও না' }, 400);

  // ---------- ৪. বাসা থেকে নমুনা: যাচাই ----------
  const home = { address: HOME, phone: '01711000401', day: 1, slot: 'morning', note: 'খালি পেটে থাকব' };
  await fails(P, 'rx_send_lab', { rxId: rx.id, labId: L2, home }, 409); // পপুলার বাসা থেকে নেয় না
  await fails(P, 'rx_send_lab', { rxId: rx.id, labId: L1, home: { ...home, address: '' } }, 400);
  await fails(P, 'rx_send_lab', { rxId: rx.id, labId: L1, home: { ...home, phone: '123' } }, 400);
  await fails(P, 'rx_send_lab', { rxId: rx.id, labId: L1, home: { ...home, day: 5 } }, 400);
  await fails(P, 'rx_send_lab', { rxId: rx.id, labId: L1, home: { ...home, slot: 'night' } }, 400);
  assert.ok(![...F.store.keys()].some((k) => k.startsWith('labOrders/')), 'ভুল অনুরোধে কিছু তৈরি হয়নি');

  // ---------- ৫. পাঠানো: দাম রেখে দেওয়া, বাসার তথ্য ----------
  const sent = await call(P, 'rx_send_lab', { rxId: rx.id, labId: L1, tests: ['CBC', 'HbA1c', 'Lipid profile', 'ECG'], home });
  assert.match(lastPush(L1).title, /বাসা থেকে নমুনা/);
  let lo = (await call(L1, 'lab_orders')).items.find((o) => o.id === sent.id);
  assert.deepStrictEqual([lo.prices.estimate, lo.prices.subtotal, lo.prices.discountPct, lo.prices.missing], [2340, 2600, 10, ['ECG']]);
  assert.deepStrictEqual([lo.home.status, lo.home.slot, lo.home.date, lo.home.fee, lo.home.address, lo.home.phone, lo.home.note],
    ['requested', 'morning', today + DAY, 150, HOME, '01711000401', 'খালি পেটে থাকব']);
  // পরে দাম বদলালেও এই অর্ডারে আগের হিসাব; নতুন হিসাবে নতুন দাম
  await call(L1, 'lab_prices_set', { items: [{ ...good[0], price: 500 }, ...good.slice(1)], discountPct: 10, homeFee: 150 });
  lo = (await call(L1, 'lab_orders')).items.find((o) => o.id === sent.id);
  assert.strictEqual(lo.prices.estimate, 2340);
  assert.strictEqual((await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST })).items.find((l) => l.id === L1).estimate, 2430);
  // রোগী একই হিসাব আর নিজের ঠিকানা দেখেন
  let act = await call(P, 'rx_activity', { rxId: rx.id });
  assert.deepStrictEqual([act.labOrders[0].prices.estimate, act.labOrders[0].home.address], [2340, HOME]);
  assert.strictEqual((await call(P, 'lab_order_get', { id: sent.id })).item.home.phone, '01711000401');
  // পরের বার ঠিকানা আগে থেকে বসে
  assert.deepStrictEqual((await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST })).lastHome, { address: HOME, phone: '01711000401' });

  // ---------- ৬. গোপনীয়তা: তালিকায় বা ডাক্তারের কাছে ঠিকানা-ফোন নেই ----------
  clearCache();
  assert.ok(!leaks(await call(null, 'labs', { district: DIST }), HOME) && !leaks(await call(X, 'labs', { district: DIST }), '01711000401'));
  assert.ok(!leaks(await call(L2, 'lab_orders'), HOME), 'অন্য প্যাথলজি দেখে না');
  await fails(L2, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '08:00' }, 404);
  await fails(X, 'lab_order_get', { id: sent.id }, 404);
  const hist = await call(D, 'rx_patient_history', { rxPatientId: pt.id });
  assert.ok(!leaks(hist, HOME) && !leaks(hist, '01711000401'), 'ডাক্তারের ইতিহাসে ঠিকানা-ফোন নেই');
  const dAct = await call(D, 'rx_activity', { rxId: rx.id });
  assert.ok(!leaks(dAct, HOME));

  // ---------- ৭. প্যাথলজি সময় নিশ্চিত করে → রোগী আর কেয়ারগিভার জানেন ----------
  await fails(P, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '08:00' }, 403);
  await fails(L1, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '8 am' }, 400);
  await fails(L1, 'lab_home_confirm', { id: sent.id, date: today + 9 * DAY, time: '08:00' }, 400);
  await fails(L1, 'lab_home_confirm', { id: sent.id, date: today - DAY, time: '08:00' }, 400);
  await fails(L1, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '23:30' }, 400);
  await call(L1, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '08:00', note: 'রাতে ১০টার পর কিছু খাবেন না' });
  assert.strictEqual(lastPush(P).title, 'সিটি ল্যাব কাল সকাল ৮টায় আসবে');
  assert.strictEqual(lastPush(P).body, 'রাতে ১০টার পর কিছু খাবেন না');
  assert.strictEqual(lastPush(P).data.id, rx.id);
  assert.strictEqual(lastPush(CG).title, 'রোগী রহিম: সিটি ল্যাব কাল সকাল ৮টায় আসবে', 'কেয়ারগিভারও জানেন');
  act = await call(P, 'rx_activity', { rxId: rx.id });
  assert.deepStrictEqual([act.labOrders[0].home.status, act.labOrders[0].home.time, act.labOrders[0].home.visitDate, act.labOrders[0].home.confirmNote],
    ['confirmed', '08:00', today + DAY, 'রাতে ১০টার পর কিছু খাবেন না']);
  // নমুনা নেওয়ার আগে সময় বদলানো যায়
  await call(L1, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '08:30' });
  assert.strictEqual(lastPush(P).title, 'সিটি ল্যাব কাল সকাল ৮টা ৩০ মিনিটে আসবে');
  // নমুনা নেওয়া হলে আর বদলানো/বাতিল নয়; রিপোর্টের পর প্যাথলজি আর ঠিকানা দেখে না
  await call(L1, 'lab_order_status', { id: sent.id, status: 'received' });
  await fails(L1, 'lab_home_confirm', { id: sent.id, date: today + DAY, time: '09:00' }, 409);
  await fails(L1, 'lab_home_decline', { id: sent.id }, 409);
  assert.strictEqual((await call(L1, 'lab_orders')).items.find((o) => o.id === sent.id).home.address, HOME);
  await call(L1, 'lab_report', { id: sent.id, items: [{ test: 'Haemoglobin', result: '12.5', unit: 'g/dL', range: '13 – 17', flag: 'low' }] });
  const done = (await call(L1, 'lab_orders')).items.find((o) => o.id === sent.id);
  assert.strictEqual(done.home.address, undefined, 'রিপোর্টের পর প্যাথলজি ঠিকানা দেখে না');
  assert.strictEqual(done.home.status, 'confirmed');
  assert.strictEqual((await call(P, 'lab_order_get', { id: sent.id })).item.home.address, HOME, 'রোগী দেখেন');

  // ---------- ৮. কেয়ারগিভার রোগীর হয়ে বাসা থেকে চান; প্যাথলজি "বাসায় যেতে পারব না" ----------
  const rx2 = await call(D, 'rx_create', { rxPatientId: pt.id, tests: ['CBC'] });
  const s3 = await call(CG, 'rx_send_lab', { asUid: P, rxId: rx2.id, labId: L1, home: { ...home, slot: 'afternoon', day: 2 } });
  const o3doc = F.store.get(`labOrders/${s3.id}`);
  assert.deepStrictEqual([o3doc.patientUid, o3doc.byCaretaker, o3doc.home.date, o3doc.prices.estimate], [P, CG, today + 2 * DAY, 450]);
  assert.strictEqual((await call(CG, 'lab_order_get', { asUid: P, id: s3.id })).item.home.address, HOME, 'কেয়ারগিভার রোগীর হয়ে দেখেন');
  await fails(CG, 'lab_order_get', { id: s3.id }, 404);
  await call(L1, 'lab_home_decline', { id: s3.id, note: 'ওই দিন লোক নেই' });
  assert.strictEqual(lastPush(P).title, 'সিটি ল্যাব বাসায় আসতে পারবে না — প্যাথলজিতে গিয়ে নমুনা দিন');
  assert.strictEqual(lastPush(P).body, 'ওই দিন লোক নেই');
  const dec = (await call(L1, 'lab_orders')).items.find((o) => o.id === s3.id);
  assert.deepStrictEqual([dec.status, dec.home.status, dec.home.declineNote, dec.home.address], ['sent', 'declined', 'ওই দিন লোক নেই', undefined], 'অর্ডার থাকে, সাধারণ পরীক্ষা হিসেবে');
  await fails(L1, 'lab_home_confirm', { id: s3.id, date: today + DAY, time: '08:00' }, 409);
  await fails(L1, 'lab_home_decline', { id: s3.id }, 409);
  await call(L1, 'lab_order_status', { id: s3.id, status: 'received' }); // রোগী এসে নমুনা দিলেন
  // বাসা থেকে না চাইলে home নেই; বাসা না চাওয়া অর্ডারে নিশ্চিত/বাতিল নয়
  const rx3 = await call(D, 'rx_create', { rxPatientId: pt.id, tests: ['Thyroid profile'] });
  const s4 = await call(P, 'rx_send_lab', { rxId: rx3.id, labId: L3 });
  const o4 = (await call(L3, 'lab_orders')).items.find((o) => o.id === s4.id);
  assert.deepStrictEqual([o4.home, o4.prices], [null, null], 'দামের তালিকা নেই — দামও নেই');
  await fails(L3, 'lab_home_confirm', { id: s4.id, date: today + DAY, time: '08:00' }, 404);

  // ---------- ৯. কাগজের প্রেসক্রিপশন (কোড দিয়ে খোলা): নিজের তালিকা থেকে হিসাব ----------
  const rx4 = await call(D, 'rx_create', { rxPatientId: pt.id, tests: ['HbA1c', 'Serum Vitamin B12'] });
  const walk = (await call(L1, 'lab_open_code', { code: rx4.code })).item;
  assert.deepStrictEqual([walk.prices.estimate, walk.prices.missing], [Math.round(2800 * 0.9), []]);

  // ---------- ১০. ডেমো আর আসল আলাদা ----------
  const DL = 'demo-lp-lab';
  const DP = 'demo-lp-patient';
  F.users.set(DP, { uid: DP, email: 'dp@lifolo-demo.test' });
  F.store.set(`users/${DP}`, { name: 'ডেমো রোগী', phone: '01700000999', lang: 'bn' });
  F.store.set(`labs/${DL}`, { name: 'ডেমো ল্যাব', status: 'approved', district: DIST, upazilaId: SADAR, address: 'ডেমো', phone: '01700000998', licenseNo: 'D', homeCollection: true, demo: true });
  F.store.set(`labPrices/${DL}`, { items: [{ code: 'cbc', name: 'CBC (Complete Blood Count)', price: 10, tat: '', on: true }], discountPct: null, homeFee: 0, updatedAt: 1 });
  F.store.set('prescriptions/demo-lp-rx', { code: 'DEMXLPRX22', patientUid: DP, doctorUid: 'demo-doctor', status: 'active', tests: ['CBC'], medicines: [], patient: { name: 'ডেমো রোগী' } });
  clearCache();
  const real = await call(P, 'rx_lab_options', { rxId: rx.id, district: DIST });
  assert.ok(!real.items.some((l) => l.id === DL), 'আসল রোগী ডেমো প্যাথলজি দেখেন না');
  const dem = await call(DP, 'rx_lab_options', { rxId: 'demo-lp-rx', district: DIST });
  assert.deepStrictEqual(dem.items.map((l) => [l.id, l.estimate, l.homeFee]), [[DL, 10, 0]], 'ডেমো রোগী শুধু ডেমো প্যাথলজি');
  await fails(P, 'rx_send_lab', { rxId: rx3.id, labId: DL, home }, 404);
  await fails(DP, 'rx_send_lab', { rxId: 'demo-lp-rx', labId: L1 }, 404);

  // ---------- ১১. অ্যাকাউন্ট মোছা: প্যাথলজির দামের তালিকা আর অর্ডারে রোগীর ঠিকানা; রোগীর অর্ডার পুরোটা ----------
  await account.deletion.deleteNow(L1, U.l1.email);
  assert.ok(!F.store.has(`labPrices/${L1}`), 'দামের তালিকা মুছেছে');
  const left = F.store.get(`labOrders/${sent.id}`);
  assert.ok(left && left.home && left.home.address === undefined && left.home.phone === undefined && left.home.status === 'confirmed', 'অর্ডার রোগীর কাছে থাকে, ঠিকানা-ফোন নেই');
  await account.deletion.deleteNow(P, U.pat.email);
  assert.ok(![...F.store.entries()].some(([k, v]) => k.startsWith('labOrders/') && v.patientUid === P), 'রোগীর সব অর্ডার মুছেছে');
  assert.ok(![...F.store.values()].some((v) => JSON.stringify(v).includes(HOME)), 'ঠিকানা কোথাও নেই');
});

// পর্যালোচনার পর ঠিক করা (প্রতিটার জন্য আলাদা পরীক্ষা): বাংলা নাম, বাসার কথা নোটিফিকেশনে, সময় পেরোনো, রোগীর বদল/বাতিল,
// একটাও না হলে দাম নয়, অবস্থার নিয়ম, অর্ডার ধরে ঠিকানা, লুকানো পরীক্ষা, সারাংশ/সীমা/শেষ ঠিকানা, প্যাথলজি সরানো, ডাক্তারের জায়গা।
test('পর্যালোচনার পর: বাংলা নাম, বাসার অনুরোধ, অবস্থা, লুকানো পরীক্ষা, মোছা', async () => {
  const LT = require(path.join(SERVER, 'lib/labtests'));
  const RL = require(path.join(SERVER, 'lib/rules'));
  const LIM = require(path.join(SERVER, 'lib/community')).LIMITS;
  const V = {
    doc: { uid: 'lqDoctor', email: 'doc@lq.test', name: 'ডা. পর্যালোচনা' },
    pat: { uid: 'lqPatient', email: 'pat@lq.test', name: 'রোগী করিম', phone: '01711000501' },
    cg: { uid: 'lqCaregiver', email: 'cg@lq.test', name: 'মেয়ে রিমা' },
    other: { uid: 'lqOther', email: 'o@lq.test', name: 'অন্য' },
    a: { uid: 'lqLabA', email: 'a@lq.test', name: 'আলফা ল্যাব' },
    b: { uid: 'lqLabB', email: 'b@lq.test', name: 'বিটা ল্যাব' },
    c: { uid: 'lqLabC', email: 'c@lq.test', name: 'ডাক্তারের বলা ল্যাব' },
  };
  const s = F.store;
  for (const u of Object.values(V)) {
    F.users.set(u.uid, { uid: u.uid, email: u.email, displayName: u.name });
    s.set(`users/${u.uid}`, { name: u.name, phone: u.phone || '', lang: 'bn', fcmTokens: [] });
  }
  s.set(`badges/${V.doc.uid}`, { type: 'doctor', status: 'approved', regNo: 'A-778', name: V.doc.name });
  s.set(`providers/${V.doc.uid}`, { type: 'doctor', name: V.doc.name, degrees: 'MBBS', specialties: ['medicine'], listed: true, chambers: [] });
  const lab = (u, x = {}) => s.set(`labs/${u.uid}`, { name: u.name, status: 'approved', district: DIST, upazilaId: SADAR, address: 'চরপাড়া', phone: '01711000497', licenseNo: 'DGHS-Q', ownerUid: u.uid, ...x });
  lab(V.a, { homeCollection: true });
  lab(V.b);
  lab(V.c, { facilityId: 'fac-lq-1' });
  s.set(`careLinks/${V.pat.uid}_${V.cg.uid}`, { patientUid: V.pat.uid, caretakerUid: V.cg.uid, status: 'active' });
  const PT = V.pat.uid, DR = V.doc.uid, A = V.a.uid, B = V.b.uid, CGq = V.cg.uid, XO = V.other.uid;
  clearCache();

  // ---------- ১. বাংলা নিজে লেখা নাম: দুটো আলাদা বাংলা পরীক্ষা "দুবার" নয়; যতিচিহ্নে আলাদা হলেও একই; খালি নাম নয় ----------
  await call(A, 'lab_prices_set', { items: [{ name: 'রক্তের গ্রুপ', price: 150 }, { name: 'প্রস্রাবের পরীক্ষা', price: 200 }, { code: 'ecg', price: 300 }], homeFee: 100 });
  await fails(A, 'lab_prices_set', { items: [{ name: 'রক্তের গ্রুপ', price: 150 }, { name: 'রক্তের-গ্রুপ!', price: 100 }] }, 400);
  await fails(A, 'lab_prices_set', { items: [{ name: '— ?', price: 10 }] }, 400);
  await call(B, 'lab_prices_set', { items: [{ code: 'cbc', price: 350 }, { code: 'tsh', price: 800 }] });

  const ptd = (await call(DR, 'rx_patient_add', { name: V.pat.name, age: 40, gender: 'male', phone: V.pat.phone })).patient;
  const mk = async (tests, extra = {}) => {
    const r = await call(DR, 'rx_create', { rxPatientId: ptd.id, tests, ...extra });
    await call(PT, 'rx_claim', { code: r.code, phone: V.pat.phone });
    return r;
  };
  const rxBn = await mk(['রক্তের গ্রুপ', 'ডেঙ্গু পরীক্ষা']);
  let opt = await call(PT, 'rx_lab_options', { rxId: rxBn.id, district: DIST });
  const oa = opt.items.find((l) => l.id === A);
  assert.deepStrictEqual([oa.have, oa.total, oa.estimate, oa.missing], [1, 2, 150, ['ডেঙ্গু পরীক্ষা']], 'অন্য বাংলা নামের পরীক্ষার সাথে মেলে না');
  assert.deepStrictEqual(oa.items.map((i) => i.name), ['রক্তের গ্রুপ']);

  // ---------- ৩. একটাও না হলে দাম নয় (৳০ নয়), অর্ডারে কিছু রাখা হয় না; কিছু না হলে অর্ডারে কোনটা নেই ----------
  const ob = opt.items.find((l) => l.id === B);
  assert.deepStrictEqual([ob.priced, ob.have, ob.estimate, ob.subtotal, ob.missing], [true, 0, null, null, ['রক্তের গ্রুপ', 'ডেঙ্গু পরীক্ষা']]);
  const toB = await call(PT, 'rx_send_lab', { rxId: rxBn.id, labId: B });
  assert.ok(!('prices' in F.store.get(`labOrders/${toB.id}`)), 'একটাও না হলে অর্ডারে দাম নেই');
  const toA = await call(PT, 'rx_send_lab', { rxId: rxBn.id, labId: A });
  assert.deepStrictEqual([F.store.get(`labOrders/${toA.id}`).prices.estimate, F.store.get(`labOrders/${toA.id}`).prices.missing], [150, ['ডেঙ্গু পরীক্ষা']]);

  // ---------- ৮. একই ছকের পরীক্ষা দুবার নয়; ডাক্তারের বলা প্যাথলজি সবার আগে ----------
  const rxDup = await mk(['CBC', 'CBC with ESR', 'TSH'], { testPlaceId: 'fac-lq-1' });
  opt = await call(PT, 'rx_lab_options', { rxId: rxDup.id, district: DIST, upazilaId: SADAR });
  const od = opt.items.find((l) => l.id === B);
  assert.deepStrictEqual([od.have, od.total, od.estimate, od.missing], [2, 2, 1150, []], 'CBC আর CBC with ESR একই — একবার');
  assert.strictEqual(opt.items[0].id, V.c.uid, 'ডাক্তারের বলা জায়গা (দামের তালিকা না থাকলেও) আগে');
  assert.strictEqual(opt.items[0].suggested, true);
  assert.strictEqual(opt.items[0].total, 2);

  // ---------- ৪. অবস্থার নিয়ম: বাতিল আবার খোলা যায় না ----------
  await fails(B, 'lab_order_status', { id: toB.id, status: 'sent' }, 404);
  await call(B, 'lab_order_status', { id: toB.id, status: 'cancelled' });
  await fails(B, 'lab_order_status', { id: toB.id, status: 'received' }, 409);
  await fails(B, 'lab_order_status', { id: toB.id, status: 'cancelled' }, 409);
  await call(A, 'lab_order_status', { id: toA.id, status: 'received' });
  await fails(A, 'lab_order_status', { id: toA.id, status: 'received' }, 409);
  await call(A, 'lab_order_status', { id: toA.id, status: 'cancelled' });
  await fails(A, 'lab_order_status', { id: toA.id, status: 'received' }, 409);

  // ---------- ২. বাসা থেকে: নোটিফিকেশনে দরকারি কথা (ফোন নয়), শেষ ঠিকানা serverState-এ ----------
  const ADDR = 'বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ';
  const home = { address: ADDR, phone: V.pat.phone, day: 1, slot: 'morning', note: '' };
  const rxH = await mk(['রক্তের গ্রুপ']);
  const h1 = await call(PT, 'rx_send_lab', { rxId: rxH.id, labId: A, home });
  const pb = lastPush(A);
  assert.strictEqual(pb.body, `বাসা থেকে নমুনা · কাল সকাল ৯–১২ · চরপাড়া… · ${V.pat.name} · রক্তের গ্রুপ`, 'পুরোনো অ্যাপের প্যাথলজিও নোটিফিকেশনে দিন-সময়-এলাকা পান');
  assert.ok(!pb.body.includes('01711000501') && !pb.body.includes('রোড ২'), 'ফোন আর পুরো ঠিকানা নোটিফিকেশনে নয়');
  assert.deepStrictEqual(F.store.get(`serverState/${PT}`).labHome.address, ADDR);
  // শেষ ঠিকানা অর্ডার খুঁজে নয়, রাখা জায়গা থেকে
  F.store.set(`serverState/${PT}`, { ...F.store.get(`serverState/${PT}`), labHome: { address: 'নতুন ঠিকানা', phone: '01711000502', at: 1 } });
  assert.deepStrictEqual((await call(PT, 'rx_lab_options', { rxId: rxH.id, district: DIST })).lastHome, { address: 'নতুন ঠিকানা', phone: '01711000502' });

  // ---------- ২. সময় পেরিয়ে গেলে দুই দিকেই "expired"; রোগী সময় বদলান / বাতিল করেন ----------
  const o1 = F.store.get(`labOrders/${h1.id}`);
  F.store.set(`labOrders/${h1.id}`, { ...o1, home: { ...o1.home, date: today - DAY } });
  assert.strictEqual((await call(A, 'lab_orders')).items.find((o) => o.id === h1.id).home.status, 'expired');
  assert.strictEqual((await call(PT, 'rx_activity', { rxId: rxH.id })).labOrders[0].home.status, 'expired');
  assert.strictEqual((await call(PT, 'lab_order_get', { id: h1.id })).item.home.status, 'expired');
  await fails(XO, 'lab_home_change', { id: h1.id, home }, 404);
  await fails(A, 'lab_home_change', { id: h1.id, home }, 404);
  await fails(PT, 'lab_home_change', { id: h1.id, home: { ...home, slot: 'night' } }, 400);
  await call(PT, 'lab_home_change', { id: h1.id, home: { ...home, day: 2, slot: 'afternoon', address: 'বাসা ৯, কাঁচিঝুলি' } });
  let cur = (await call(A, 'lab_orders')).items.find((o) => o.id === h1.id).home;
  assert.deepStrictEqual([cur.status, cur.date, cur.slot, cur.address, cur.fee], ['requested', today + 2 * DAY, 'afternoon', 'বাসা ৯, কাঁচিঝুলি', 100]);
  assert.strictEqual(lastPush(A).title, 'রোগী বাসা থেকে নমুনার সময় বদলেছেন');
  assert.match(lastPush(A).body, /^রোগী করিম · বাসা থেকে নমুনা · পরশু বিকেল ৩–৬ · কাঁচিঝুলি…$/);
  // কেয়ারগিভার রোগীর হয়ে বাতিল করেন → সাধারণ পরীক্ষা (অর্ডার থাকে), ঠিকানা মোছে, প্যাথলজি জানে
  await fails(CGq, 'lab_home_cancel', { id: h1.id }, 404);
  await call(CGq, 'lab_home_cancel', { asUid: PT, id: h1.id });
  const after = F.store.get(`labOrders/${h1.id}`);
  assert.deepStrictEqual([after.status, after.home], ['sent', undefined]);
  assert.strictEqual(lastPush(A).title, 'বাসায় যেতে হবে না — রোগী প্যাথলজিতে এসে নমুনা দেবেন');
  await fails(PT, 'lab_home_cancel', { id: h1.id }, 404);
  // প্যাথলজি সময় জানালে রোগী আর বদলাতে/বাতিল করতে পারেন না (ফোন করে)
  const rxH2 = await mk(['প্রস্রাবের পরীক্ষা']);
  const h2 = await call(PT, 'rx_send_lab', { rxId: rxH2.id, labId: A, home });
  await call(A, 'lab_home_confirm', { id: h2.id, date: today + DAY, time: '09:30' });
  await fails(PT, 'lab_home_change', { id: h2.id, home }, 409);
  await fails(PT, 'lab_home_cancel', { id: h2.id }, 409);

  // ---------- ৪. rx_activity: ঠিকানা অর্ডার ধরে (অর্ডারের রোগী), প্রেসক্রিপশনের মালিক দেখে নয় ----------
  F.store.set('labOrders/lq-foreign', { ...F.store.get(`labOrders/${h2.id}`), patientUid: 'lqSomeoneElse', home: { ...F.store.get(`labOrders/${h2.id}`).home, address: 'অন্যের বাসা', phone: '01711000599' } });
  const act = await call(PT, 'rx_activity', { rxId: rxH2.id });
  assert.strictEqual(act.labOrders.find((o) => o.id === h2.id).home.address, ADDR);
  const foreign = act.labOrders.find((o) => o.id === 'lq-foreign');
  assert.ok(foreign && foreign.home.address === undefined && !leaks(act, 'অন্যের বাসা'), 'অন্যের পাঠানো অর্ডারের ঠিকানা নয়');
  F.store.delete('labOrders/lq-foreign');

  // ---------- ৫. ছকে লুকানো পরীক্ষা: সবার দেখায় নেই, সেভ আটকায় না (নিজে লেখা হিসেবে থাকে) ----------
  assert.ok((await call(null, 'lab_prices_public', { labId: A })).prices.items.some((i) => i.code === 'ecg'));
  const ecg = require(path.join(SERVER, 'lib/labtests-defaults.json')).find((t) => t.code === 'ecg');
  F.store.set('config/labTests', { tests: [{ ...ecg, hidden: true }] });
  await LT.load(Date.now() + 60 * 60 * 1000);
  clearCache();
  const hidPub = (await call(null, 'lab_prices_public', { labId: A })).prices;
  assert.ok(!hidPub.items.some((i) => i.code === 'ecg'), 'লুকানো কোডের সারি সবার দেখায় নেই');
  assert.strictEqual((await call(null, 'labs', { district: DIST })).items.find((l) => l.id === A).prices.count, 2);
  assert.strictEqual((await call(A, 'lab_prices_get')).items.find((i) => i.code === 'ecg').hidden, true, 'মালিক দেখেন — লুকানো');
  const kept = await call(A, 'lab_prices_set', { items: (await call(A, 'lab_prices_get')).items, homeFee: 100 });
  assert.deepStrictEqual(kept.kept, ['ECG'], 'সেভ আটকায়নি, নাম দিয়ে রাখা');
  assert.deepStrictEqual(kept.items.find((i) => i.name === 'ECG'), { code: null, name: 'ECG', price: 300, tat: '', on: true });
  assert.ok((await call(null, 'lab_prices_public', { labId: A })).prices.items.some((i) => i.name === 'ECG' && i.code === null), 'নিজে লেখা হিসেবে রাখলে দেখায়');
  F.store.delete('config/labTests');
  await LT.load(Date.now() + 2 * 60 * 60 * 1000);
  clearCache();

  // ---------- ৬. সীমা: দিনের সীমা শেষ হলে rx_lab_options নয় ----------
  const st = F.store.get(`serverState/${PT}`) || {};
  F.store.set(`serverState/${PT}`, { ...st, rxPharmDay: RL.dhakaDayKey(), rxPharmCount: LIM.rxPharm });
  await fails(PT, 'rx_lab_options', { rxId: rxH.id, district: DIST }, 429);
  F.store.set(`serverState/${PT}`, st);

  // ---------- ৭. প্যাথলজি সরানো: দামের তালিকা আর চালু অর্ডারের ঠিকানা মোছে ----------
  assert.ok(F.store.has(`labPrices/${A}`) && F.store.get(`labOrders/${h2.id}`).home.address);
  await call(A, 'lab_delete');
  assert.ok(!F.store.has(`labPrices/${A}`), 'দামের তালিকা মুছেছে');
  const h2doc = F.store.get(`labOrders/${h2.id}`);
  assert.ok(h2doc.home.address === undefined && h2doc.home.phone === undefined && h2doc.home.status === 'confirmed', 'চালু অর্ডারে ঠিকানা-ফোন নেই');
  await fails(null, 'lab_prices_public', { labId: A }, 404);

  // ---------- ৭. অ্যাকাউন্ট মোছা: অনেক অর্ডার (এক পাতার বেশি) — সবগুলোর ঠিকানা মোছে, চালুগুলো আগে ----------
  for (let i = 0; i < 700; i++) {
    F.store.set(`labOrders/lq-bulk-${String(i).padStart(4, '0')}`, {
      labId: B, patientUid: PT, rxId: rxDup.id, status: i % 50 === 0 ? 'sent' : 'reported', tests: ['CBC'], createdAt: i,
      home: { address: `বাসা ${i}, চরপাড়া`, phone: '01711000501', status: 'requested', date: today, slot: 'early' },
    });
  }
  await account.deletion.deleteNow(B, V.b.email);
  const bulk = [...F.store.entries()].filter(([k]) => k.startsWith('labOrders/lq-bulk-'));
  assert.strictEqual(bulk.length, 700);
  assert.ok(bulk.every(([, v]) => v.home.address === undefined && v.home.phone === undefined), 'এক পাতার বেশি অর্ডারেও ঠিকানা নেই');
  bulk.forEach(([k]) => F.store.delete(k));
});
