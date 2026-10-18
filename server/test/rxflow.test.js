// ডেমো অ্যাকাউন্ট দিয়ে পুরো পথ (১৭ অক্টোবর ২০২৬): আসল সার্ভারের কোড (api/community.js), মেমোরিতে নকল Firebase।
// ডাক্তার → রোগী → কেয়ারগিভার → দোকানে/অনলাইনে ফার্মেসি → প্যাথলজির ছক → পরিবারের সদস্য → অ্যাডমিনের ছক।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
process.env.ADMIN_EMAILS = 'admin@demo.test';
process.env.CRON_SECRET = 'demo-secret';
const api = require(path.join(SERVER, 'api/community.js'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;

const log = [];
let step = 0;
function ok(text) { log.push(`✔ ${++step}. ${text}`); }

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  if (res.statusCode !== 200) { const e = new Error(`${action} → ${res.statusCode}: ${out && out.error}`); e.status = res.statusCode; e.body = out; throw e; }
  return out;
}
async function fails(uid, action, body, status) {
  try { await call(uid, action, body); } catch (e) { assert.strictEqual(e.status, status, e.message); return e.body; }
  throw new Error(`${action} should fail`);
}
const lastPush = (uid) => F.pushes.filter((p) => p.uid === uid).slice(-1)[0];

// ---------------- ডেমো অ্যাকাউন্ট ----------------
const U = {
  doc: { uid: 'demoDoctor', email: 'doctor@demo.test', name: 'ডা. ফারহানা ইসলাম' },
  pat: { uid: 'demoPatient', email: 'rahima@demo.test', name: 'রহিমা বেগম', phone: '01711000001' },
  care: { uid: 'demoCaregiver', email: 'son@demo.test', name: 'সাকিব (ছেলে)' },
  other: { uid: 'demoStranger', email: 'x@demo.test', name: 'অন্য কেউ' },
  ph1: { uid: 'demoShop1', email: 'shop1@demo.test', name: 'মা ফার্মেসি' },
  ph2: { uid: 'demoShop2', email: 'shop2@demo.test', name: 'সেবা ফার্মেসি (অনলাইন)' },
  lab: { uid: 'demoLab', email: 'lab@demo.test', name: 'পপুলার ডায়াগনস্টিক' },
  admin: { uid: 'demoAdmin', email: 'admin@demo.test', name: 'অ্যাডমিন' },
};
function seed() {
  const s = F.store;
  for (const u of Object.values(U)) {
    F.users.set(u.uid, { uid: u.uid, email: u.email, displayName: u.name });
    s.set(`users/${u.uid}`, { name: u.name, phone: u.phone || '', lang: 'bn', fcmTokens: [`tok_${u.uid}`] });
  }
  s.set(`badges/${U.doc.uid}`, { type: 'doctor', status: 'approved', regNo: 'A-12345', name: U.doc.name });
  s.set(`providers/${U.doc.uid}`, { type: 'doctor', name: U.doc.name, degrees: 'MBBS, FCPS (Medicine)', specialties: ['medicine'], listed: true, chambers: [] });
  const shop = (name, lat, lng, extra = {}) => ({ name, status: 'approved', district: DIST, upazilaId: null, lat, lng, open24: true, phone: '01711000099', address: 'চরপাড়া মোড়', licenseNo: 'DL-1', ...extra });
  s.set(`pharmacies/${U.ph1.uid}`, shop(U.ph1.name, 24.751, 90.408));
  s.set(`pharmacies/${U.ph2.uid}`, shop(U.ph2.name, 24.760, 90.400));
  s.set(`labs/${U.lab.uid}`, { name: U.lab.name, status: 'approved', district: DIST, address: 'চরপাড়া', phone: '01711000098', licenseNo: 'DGHS-1' });
}

test('ডেমো অ্যাকাউন্টে প্রেসক্রিপশন থেকে ফার্মেসি, প্যাথলজি, কেয়ারগিভার — পুরো পথ', async () => {
  seed();
  const P = U.pat.uid, D = U.doc.uid, CG = U.care.uid;

  // ---------- ১. ডাক্তার: রোগী এন্ট্রি, প্রেসক্রিপশন ----------
  const pat = (await call(D, 'rx_patient_add', { name: 'রহিমা বেগম', age: 52, gender: 'female', phone: '01711-000001' })).patient;
  const rx = await call(D, 'rx_create', {
    rxPatientId: pat.id, diagnosis: 'Type 2 DM, Dyslipidaemia', complaints: 'দুর্বলতা',
    medicines: [
      { name: 'Comet 500', form: 'Tablet', dose: '1+0+1', days: 30, timing: 'after' },
      { name: 'Rosuva 10', form: 'Tablet', dose: '0+0+1', days: 30 },
      { name: 'Napa 500', form: 'Tablet', dose: '1+1+1', days: 3 },
    ],
    tests: ['CBC', 'Lipid profile', 'HbA1c', 'Bone scan'], followUpDays: 30,
  });
  ok(`ডাক্তার রোগী এন্ট্রি করে প্রেসক্রিপশন দিলেন (কোড ${rx.code})`);

  // ---------- ২. রোগী: কোড + ফোনে যোগ ----------
  const claimed = await call(P, 'rx_claim', { code: rx.code, phone: '01711000001' });
  assert.strictEqual(claimed.added, 1);
  ok('রোগী কোড আর ফোন নম্বর দিয়ে প্রেসক্রিপশন নিজের অ্যাকাউন্টে যোগ করলেন');

  // ---------- ৩. কেয়ারগিভার ----------
  await fails(P, 'care_invite', { email: 'nobody@demo.test' }, 404);
  await call(P, 'care_invite', { email: U.care.email });
  assert.strictEqual(lastPush(CG).data.type, 'care');
  ok('রোগী ছেলেকে কেয়ারগিভার হিসেবে যোগ করলেন — ছেলে নোটিফিকেশন পেলেন (Lifolo-তে অ্যাকাউন্ট নেই এমন ইমেইল ঠিকভাবে আটকাল)');
  await fails(CG, 'rx_mine', { asUid: P }, 403);
  ok('রাজি হওয়ার আগে ছেলে রোগীর প্রেসক্রিপশন দেখতে পারেন না');
  await call(CG, 'care_respond', { patientUid: P, accept: true });
  const careList = await call(CG, 'care_list');
  assert.strictEqual(careList.caringFor[0].status, 'active');
  const viaCare = await call(CG, 'rx_mine', { asUid: P });
  assert.strictEqual(viaCare.items.length, 1);
  ok('ছেলে রাজি হলেন — এখন নিজের ফোন থেকে মায়ের প্রেসক্রিপশন দেখছেন');
  await fails(CG, 'delete_account', { asUid: P }, 403);
  await fails(CG, 'care_invite', { asUid: P, email: U.other.email }, 403);
  await fails(U.other.uid, 'rx_mine', { asUid: P }, 403);
  ok('কেয়ারগিভার রোগীর অ্যাকাউন্ট মুছতে বা নতুন কেয়ারগিভার যোগ করতে পারেন না; অপরিচিত কেউ রোগী সেজে কিছু পান না');

  // ---------- ৪. দোকানে গিয়ে (কেয়ারগিভার) ----------
  const home1 = await call(U.ph1.uid, 'rx_pharmacy_home');
  assert.match(home1.shopCode, /^[A-HJ-NP-Z2-9]{6}$/);
  const found = await call(CG, 'rx_shop_find', { asUid: P, code: home1.shopCode.toLowerCase() });
  assert.strictEqual(found.item.id, U.ph1.uid);
  await call(CG, 'rx_send_pharmacy', { asUid: P, rxId: rx.id, pharmacyId: U.ph1.uid, mode: 'shop' });
  assert.ok(lastPush(U.ph1.uid).title.includes('প্রেসক্রিপশন'));
  ok(`ছেলে মা ফার্মেসিতে দাঁড়িয়ে দোকানের কোড (${home1.shopCode}) দিয়ে প্রেসক্রিপশন পাঠালেন — দোকান নোটিফিকেশন পেল`);
  const inbox1 = (await call(U.ph1.uid, 'rx_orders_pharmacy')).items;
  assert.strictEqual(inbox1[0].mode, 'shop');
  assert.strictEqual(inbox1[0].address, undefined);
  const disp = await call(U.ph1.uid, 'rx_dispense', { code: inbox1[0].code, items: [{ i: 0, qty: 60 }], missing: [1] });
  assert.strictEqual(disp.item.medicines[0].done, true);
  ok('দোকান Comet 500 দিল ৬০টা, Rosuva 10-এ "নেই" চিহ্ন দিল (Napa কিছু লেখেনি)');
  await fails(U.ph1.uid, 'rx_dispense', { code: inbox1[0].code, items: [{ i: 0, qty: 1 }] }, 409);
  ok('একই ওষুধ প্রেসক্রিপশনের বেশি দিতে চাইলে আটকায়');
  const pPush = F.pushes.filter((p) => p.uid === P).slice(-1)[0];
  const cPush = F.pushes.filter((p) => p.uid === CG).slice(-1)[0];
  assert.ok(pPush.title.includes('ওষুধ দেওয়া হয়েছে') && cPush.title.startsWith('রহিমা বেগম:'));
  ok('রোগী আর ছেলে দুজনেই নোটিফিকেশন পেলেন ("রহিমা বেগম: প্রেসক্রিপশনের ওষুধ দেওয়া হয়েছে")');
  const miss1 = (await call(U.ph1.uid, 'rx_pharmacy_home')).missed;
  assert.deepStrictEqual(miss1.map((m) => [m.name, m.count]), [['Rosuva 10', 1]]);
  ok('মা ফার্মেসির "চেয়েও পাননি" তালিকায় Rosuva 10 (১ বার) জমা হলো');

  // ---------- ৫. ফিরিয়ে নেওয়া ----------
  const act0 = await call(P, 'rx_activity', { rxId: rx.id });
  assert.strictEqual(act0.orders[0].status, 'done');
  await call(P, 'rx_send_pharmacy', { rxId: rx.id, pharmacyId: U.ph1.uid, mode: 'shop' });
  const o2 = (await call(P, 'rx_activity', { rxId: rx.id })).orders.find((o) => o.status === 'sent');
  await call(P, 'rx_order_withdraw', { id: o2.id, reason: 'not_available' });
  const miss2 = (await call(U.ph1.uid, 'rx_pharmacy_home')).missed;
  assert.deepStrictEqual(miss2.map((m) => [m.name, m.count]).sort(), [['Napa 500', 1], ['Rosuva 10', 2]]);
  assert.ok(lastPush(U.ph1.uid).title.includes('ফিরিয়ে'));
  ok('রোগী আবার পাঠিয়ে "ওষুধ নেই" বলে ফিরিয়ে নিলেন — দোকানের তালিকায় Rosuva 10 (২ বার), Napa 500 (১ বার)');
  await fails(P, 'rx_order_withdraw', { id: o2.id }, 409);
  ok('একবার ফিরিয়ে নেওয়া অর্ডার আবার ফেরানো যায় না');

  // ---------- ৬. অনলাইনে ----------
  let opts = await call(P, 'rx_pharmacy_options', { rxId: rx.id, district: DIST, lat: 24.752, lng: 90.407, mode: 'online' });
  assert.strictEqual(opts.items.length, 0);
  await fails(P, 'rx_send_pharmacy', { rxId: rx.id, pharmacyId: U.ph2.uid, mode: 'online', address: 'বাসা', phone: '01711000001' }, 409);
  ok('কোনো দোকান অনলাইন চালু না করলে অনলাইনের তালিকা খালি, অর্ডারও আটকায়');
  await call(U.ph2.uid, 'rx_pharmacy_online_set', { enabled: true, radiusKm: 3, fee: '৩০ টাকা', note: 'সকাল ১০টা–রাত ৯টা' });
  await call(U.ph2.uid, 'stock_set', { add: ['Rosuva 10', 'Napa 500'] });
  opts = await call(P, 'rx_pharmacy_options', { rxId: rx.id, district: DIST, lat: 24.752, lng: 90.407, mode: 'online' });
  assert.strictEqual(opts.items.length, 1);
  assert.deepStrictEqual([opts.items[0].id, opts.items[0].have, opts.items[0].total, opts.items[0].deliveryFee], [U.ph2.uid, 2, 2, '৩০ টাকা']);
  const shopOpts = await call(P, 'rx_pharmacy_options', { rxId: rx.id, district: DIST, lat: 24.752, lng: 90.407, mode: 'shop' });
  assert.strictEqual(shopOpts.items[0].id, U.ph1.uid);
  ok('সেবা ফার্মেসি অনলাইন চালু করল (৩ কিমি, ৩০ টাকা) — রোগী দেখলেন "২টার ২টা ওষুধ আছে"; "দোকানে আছি"-তে কাছের মা ফার্মেসি ওপরে');
  const byName = await call(P, 'rx_pharmacy_options', { rxId: rx.id, district: DIST, lat: 24.9, lng: 90.6, mode: 'shop', q: 'মা ফার' });
  assert.deepStrictEqual(byName.items.map((x) => x.id), [U.ph1.uid]);
  ok('দোকানের নাম লিখে ("মা ফার") খুঁজলে দূরে থাকলেও সেই দোকান আসে');
  const far = await call(P, 'rx_pharmacy_options', { rxId: rx.id, district: DIST, lat: 24.9, lng: 90.6, mode: 'online' });
  assert.strictEqual(far.items.length, 0);
  ok('ডেলিভারির দূরত্বের বাইরে থাকলে অনলাইনের তালিকায় আসে না');
  await fails(P, 'rx_send_pharmacy', { rxId: rx.id, pharmacyId: U.ph2.uid, mode: 'online', address: '', phone: '01711000001' }, 400);
  await call(P, 'rx_send_pharmacy', { rxId: rx.id, pharmacyId: U.ph2.uid, mode: 'online', address: 'বাসা ১২, রোড ৪, চরপাড়া', phone: '01711000001' });
  const on = (await call(U.ph2.uid, 'rx_orders_pharmacy')).items[0];
  assert.deepStrictEqual([on.mode, on.address, on.phone], ['online', 'বাসা ১২, রোড ৪, চরপাড়া', '01711000001']);
  ok('রোগী ঠিকানা দিয়ে অনলাইনে অর্ডার দিলেন — দোকান ঠিকানা আর ফোন দেখল');
  await fails(U.ph2.uid, 'rx_order_update', { id: on.id, status: 'ready' }, 409);
  await call(U.ph2.uid, 'rx_order_update', { id: on.id, status: 'accepted', price: '৪২০ টাকা', eta: 'আজ সন্ধ্যা ৭টার মধ্যে' });
  assert.ok(lastPush(P).body.includes('৪২০ টাকা'));
  await call(U.ph2.uid, 'rx_order_update', { id: on.id, status: 'out' });
  await fails(P, 'rx_order_withdraw', { id: on.id }, 409);
  ok('দোকান অর্ডার নিল (৪২০ টাকা, সন্ধ্যা ৭টা) → পথে; পথে থাকা অর্ডার রোগী আর ফেরাতে পারেন না');
  await call(U.ph2.uid, 'rx_dispense', { code: rx.code, items: [{ i: 1, qty: 30 }, { i: 2, qty: 9 }] });
  const act1 = await call(P, 'rx_activity', { rxId: rx.id });
  assert.ok(act1.medicines.every((m) => m.done));
  assert.strictEqual(act1.orders.find((o) => o.id === on.id).status, 'done');
  const closedAddr = (await call(U.ph2.uid, 'rx_orders_pharmacy')).items[0];
  assert.strictEqual(closedAddr.address, undefined);
  ok('পৌঁছে দিয়ে বাকি ওষুধ লিখল — প্রেসক্রিপশনের সব ওষুধ কেনা শেষ; অর্ডার শেষ হলে দোকান আর রোগীর ঠিকানা দেখে না');
  await fails(P, 'rx_send_pharmacy', { rxId: rx.id, pharmacyId: U.ph1.uid, mode: 'shop' }, 409);
  ok('সব কেনা হয়ে গেলে আর কোনো দোকানে পাঠানো যায় না');

  // ---------- ৭. প্যাথলজি: ছক ----------
  await call(CG, 'rx_send_lab', { asUid: P, rxId: rx.id, labId: U.lab.uid, tests: ['CBC', 'Lipid profile', 'HbA1c', 'Bone scan'] });
  const lo = (await call(U.lab.uid, 'lab_orders')).items[0];
  const forms = (await call(U.lab.uid, 'lab_form', { id: lo.id })).forms;
  const cbc = forms.find((f) => f.code === 'cbc');
  const hb = cbc.params.find((p) => p.key === 'hb');
  assert.deepStrictEqual([hb.low, hb.high, hb.unit], [12, 15, 'g/dL']);
  assert.strictEqual(forms.find((f) => f.test === 'Bone scan').code, null);
  ok(`ছেলে পরীক্ষা পাঠালেন; ল্যাব ফর্ম খুলে পেল CBC (${cbc.params.length}টা ঘর), Lipid, HbA1c ভরা — রোগী নারী, তাই Hb মাত্রা 12–15; "Bone scan"-এর ছক নেই, খালি সারি`);
  await call(U.lab.uid, 'lab_ranges_set', { ranges: [{ code: 'hba1c', key: 'hba1c', low: 4.2, high: 5.8 }] });
  const forms2 = (await call(U.lab.uid, 'lab_form', { id: lo.id })).forms;
  assert.strictEqual(forms2.find((f) => f.code === 'hba1c').params[0].high, 5.8);
  ok('ল্যাব নিজের মেশিনের HbA1c মাত্রা (4.2–5.8) রাখল — পরের ফর্মে সেটাই আসে');
  const row = (f, key, result) => { const p = f.params.find((x) => x.key === key); return { test: p.name, result, unit: p.unit, range: p.range, group: f.name, code: f.code, key, low: p.low, high: p.high, flag: '' }; };
  const lip = forms2.find((f) => f.code === 'lipid');
  const a1c = forms2.find((f) => f.code === 'hba1c');
  await call(U.lab.uid, 'lab_report', { id: lo.id, items: [row(cbc, 'hb', '10.4'), row(cbc, 'wbc', '7.8'), row(lip, 'chol', '232'), row(lip, 'hdl', '38'), row(a1c, 'hba1c', '8.6'), { test: 'Bone scan', result: 'Normal study', flag: '' }], note: '' });
  const rep = await call(P, 'lab_order_get', { id: lo.id });
  const flags = Object.fromEntries(rep.item.report.items.map((i) => [i.test, i.flag]));
  assert.deepStrictEqual(flags, { Haemoglobin: 'low', 'Total WBC count': '', 'Total cholesterol': 'high', 'HDL cholesterol': 'low', HbA1c: 'high', 'Bone scan': '' });
  ok('ল্যাব শুধু ফল লিখল (বেশি/কম চিহ্ন দেয়নি) — সার্ভার নিজে বসাল: Hb 10.4 কম, কোলেস্টেরল 232 বেশি, HDL 38 কম, HbA1c 8.6 বেশি, WBC স্বাভাবিক');
  assert.ok(lastPush(P).title.includes('রিপোর্ট') && lastPush(CG).title.includes('রহিমা বেগম'));
  ok('রিপোর্টের খবর রোগী আর ছেলে দুজনেই পেলেন');

  // দ্বিতীয় রিপোর্ট (৩ মাস পর) — আগের ফল পাশে
  const rx2 = await call(D, 'rx_create', { rxPatientId: pat.id, medicines: [{ name: 'Comet 500', form: 'Tablet', dose: '1+0+1', days: 30 }], tests: ['HbA1c'] });
  await call(P, 'rx_send_lab', { rxId: rx2.id, labId: U.lab.uid });
  const lo2 = (await call(U.lab.uid, 'lab_orders')).items.find((o) => o.rxId === rx2.id);
  const f3 = (await call(U.lab.uid, 'lab_form', { id: lo2.id })).forms[0];
  await call(U.lab.uid, 'lab_report', { id: lo2.id, items: [row(f3, 'hba1c', '7.4')] });
  const rep2 = await call(P, 'lab_order_get', { id: lo2.id });
  assert.deepStrictEqual(rep2.history['hba1c.hba1c'].map((h) => [h.result, h.flag]), [['8.6', 'high']]);
  ok('পরের প্রেসক্রিপশনে আবার HbA1c — নতুন রিপোর্টে আগের ফল পাশে: "আগে: 8.6↑"');
  const hist = await call(D, 'rx_patient_history', { rxPatientId: pat.id });
  assert.deepStrictEqual(hist.items.map((r) => r.id).sort(), [rx.id, rx2.id].sort());
  await fails(P, 'rx_patient_history', { rxPatientId: pat.id }, 403);
  ok('ডাক্তার রোগীর নাম চাপলে তাঁকে দেওয়া আগের ২টা প্রেসক্রিপশন দেখলেন; রোগী নিজে এই তালিকা চাইতে পারেন না');

  // ---------- ৮. পরিবারের সদস্য ----------
  await call(P, 'family_set', { family: [{ name: 'আব্দুল করিম', relation: 'স্বামী', age: 60, gender: 'male', phone: '01811000002' }] });
  await fails(CG, 'family_set', { asUid: P, family: [] }, 403);
  const p2 = (await call(D, 'rx_patient_add', { name: 'আব্দুল করিম', age: 60, gender: 'male', phone: '01811000002' })).patient;
  const rx3 = await call(D, 'rx_create', { rxPatientId: p2.id, medicines: [{ name: 'Amlo 5', form: 'Tablet', dose: '1+0+0', days: 30 }] });
  await fails(U.other.uid, 'rx_claim', { code: rx3.code, phone: '01811000002' }, 400);
  await call(P, 'rx_claim', { code: rx3.code, phone: '01811000002' });
  const mine = (await call(P, 'rx_mine')).items.map((r) => r.patient.name);
  assert.deepStrictEqual([...new Set(mine)].sort(), ['আব্দুল করিম', 'রহিমা বেগম'].sort());
  ok('রোগী স্বামীকে (নিজের ফোন নেই) পরিবারের সদস্য করলেন; স্বামীর প্রেসক্রিপশন তাঁর নম্বর দিয়ে নিজের অ্যাকাউন্টে আনলেন — অন্য কেউ সেই নম্বরে আনতে পারে না');

  // ---------- ৯. কেয়ারগিভার সরানো ----------
  await call(P, 'care_remove', { caretakerUid: CG });
  const revoked = await fails(CG, 'rx_mine', { asUid: P }, 403);
  assert.strictEqual(revoked.code, 'care_revoked');
  const before = F.pushes.length;
  await call(U.lab.uid, 'lab_order_status', { id: lo2.id, status: 'received' }).catch(() => {});
  await call(D, 'rx_cancel', { id: rx3.id }).catch(() => {});
  assert.ok(!F.pushes.slice(before).some((p) => p.uid === CG));
  ok('রোগী ছেলেকে সরিয়ে দিলেন — সাথে সাথে ছেলের সুযোগ বন্ধ, নোটিফিকেশনও আর যায় না');

  // ---------- ১০. অ্যাডমিন: নতুন ছক ----------
  await fails(D, 'admin_labtests_set', { test: { name: 'Serum Magnesium', params: [{ name: 'Magnesium', unit: 'mg/dL', r: [1.7, 2.2] }] } }, 403);
  await call(U.admin.uid, 'admin_labtests_set', { test: { name: 'Serum Magnesium', aliases: ['s magnesium', 'magnesium'], params: [{ name: 'Magnesium', unit: 'mg/dL', r: [1.7, 2.2] }] } });
  await fails(U.admin.uid, 'admin_labtests_set', { test: { name: 'Sugar test', aliases: ['rbs'], params: [{ name: 'x' }] } }, 409);
  const rx4 = await call(D, 'rx_create', { rxPatientId: pat.id, tests: ['S. Magnesium'] });
  await call(P, 'rx_send_lab', { rxId: rx4.id, labId: U.lab.uid });
  const lo4 = (await call(U.lab.uid, 'lab_orders')).items.find((o) => o.rxId === rx4.id);
  const f4 = (await call(U.lab.uid, 'lab_form', { id: lo4.id })).forms[0];
  assert.deepStrictEqual([f4.name, f4.params[0].range], ['Serum Magnesium', '1.7 – 2.2']);
  ok('অ্যাডমিন প্যানেল থেকে নতুন পরীক্ষা "Serum Magnesium" যোগ করলেন — ডাক্তার "S. Magnesium" লিখলে ল্যাব সেই ছক পায়; অন্য পরীক্ষার নামের সাথে মিলে গেলে আটকায়; ডাক্তার অ্যাডমিন কাজ করতে পারেন না');

  // প্রেসক্রিপশনের সব নোটিফিকেশনে rxId — চাপলে অ্যাপ সেই প্রেসক্রিপশনই খোলে (কেয়ারগিভারেরটাতেও)
  const rxPushes = F.pushes.filter((p) => ['rx', 'rx_pharmacy', 'rx_lab', 'rx_review'].includes(p.data?.type));
  for (const p of rxPushes) {
    assert.ok(typeof p.data.rxId === 'string' && p.data.rxId, `${p.data.type} নোটিফিকেশনে rxId নেই: ${p.title}`);
    if (p.data.type === 'rx') assert.strictEqual(p.data.rxId, p.data.id);
  }
  assert.deepStrictEqual([...new Set(rxPushes.map((p) => p.data.type))].sort(), ['rx', 'rx_lab', 'rx_pharmacy']);
  const carePushes = F.pushes.filter((p) => p.data?.type === 'care' && p.data.asUid);
  assert.ok(carePushes.length >= 3 && carePushes.every((p) => p.data.asUid === P && p.data.rxId));
  ok(`প্রেসক্রিপশনের ${rxPushes.length}টা নোটিফিকেশন আর ছেলের ${carePushes.length}টা — সবগুলোতে rxId`);

  assert.ok(step >= 29);
});
