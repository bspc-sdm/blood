// ডেমো: প্যাথলজির পুরো পথ — প্যানেলের মতো ধাপে ধাপে ডেমো বানিয়ে, রোগী, প্যাথলজি, ডাক্তার, কেয়ারগিভার আর মালিক — আসল কাজ দিয়ে।
// (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const verify = require(path.join(SERVER, 'lib/verify'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const { db } = F.firebase;
const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.8.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}

const LAB = 'demo-lab';
const P = 'demo-patient';
const DOC = 'demo-doctor';
const AS = 'demo-assistant';
const CG = 'demo-caregiver';
const LOG = process.env.LAB_LOG ? (...a) => console.log('  ✓', ...a) : () => {};

test('প্যাথলজি: তালিকা, প্যাথলজির কাজ, রোগী, ডাক্তার, কেয়ারগিভার, মালিক — আসল কাজ দিয়ে', async () => {
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  LOG('ডেমো বানানো (ধাপে ধাপে)');

  // ---- অতিথি: তালিকা ----
  const list = (await ok(null, 'labs', { district: DISTRICT })).items;
  assert.ok(list.some((x) => x.id === LAB), 'ডেমো প্যাথলজি তালিকায়');
  assert.ok(!list.some((x) => /নিউ লাইফ/.test(x.name)), 'অপেক্ষার প্যাথলজি তালিকায় নেই');
  LOG(`অতিথির তালিকা: ${list.length}টা প্যাথলজি —`, list.map((x) => x.name).join(', '));

  // ---- প্যাথলজির মালিক: নিজের প্যাথলজি আর অর্ডার ----
  const me = (await ok(LAB, 'lab_me')).lab;
  assert.strictEqual(me.status, 'approved');
  LOG(`প্যাথলজি: ${me.name}, অবস্থা ${me.status}, বাসা থেকে নমুনা ${me.homeCollection ?? me.home ?? '-'}`);
  const orders0 = (await ok(LAB, 'lab_orders')).items;
  const byStatus = orders0.reduce((m, o) => ({ ...m, [o.status]: (m[o.status] || 0) + 1 }), {});
  LOG(`অর্ডার ${orders0.length}টা —`, JSON.stringify(byStatus));
  assert.ok(orders0.length >= 3);
  const cat = await ok(LAB, 'labtests_get');
  LOG(`পরীক্ষার ছক: ${(cat.items || cat.tests || []).length}টা পরীক্ষা`);

  // ---- ডাক্তার পরীক্ষাসহ প্রেসক্রিপশন লেখেন (অ্যাপের রোগী: ডেমো রোগীর অ্যাপয়েন্টমেন্ট ধরে) ----
  const pts = await ok(DOC, 'rx_patients');
  let apId = null;
  for (const a of pts.appPatients) if ((await db.doc(`appointments/${a.appointmentId}`).get()).get('patientUid') === P) { apId = a.appointmentId; break; }
  assert.ok(apId, 'ডেমো রোগী ডাক্তারের অ্যাপের রোগীর তালিকায়');
  const made = await ok(DOC, 'rx_create', {
    appointmentId: apId, diagnosis: 'রক্তস্বল্পতা?',
    medicines: [{ name: 'Napa', form: 'Tablet', strength: '500 mg', dose: '1+1+1', days: 3 }],
    tests: ['CBC', 'HbA1c'], advice: 'পরীক্ষা করে দেখাবেন',
  });
  LOG(`ডাক্তার প্রেসক্রিপশন লিখলেন (কোড ${made.code}), পরীক্ষা: CBC, HbA1c`);
  const sender = P;
  const pat = { id: null };

  // ---- রোগী প্যাথলজিতে পাঠান ----
  const labsP = (await ok(P, 'labs', { district: DISTRICT })).items;
  assert.ok(labsP.some((x) => x.id === LAB));
  await ok(P, 'rx_send_lab', { rxId: made.id, labId: LAB, tests: ['CBC', 'HbA1c'] });
  assert.strictEqual((await call(P, 'rx_send_lab', { rxId: made.id, labId: LAB })).status, 409, 'একই প্যাথলজিতে দুবার নয়');
  let lo = (await ok(LAB, 'lab_orders')).items.find((o) => o.rxId === made.id);
  assert.strictEqual(lo.status, 'sent');
  LOG('রোগী অ্যাপ থেকে প্যাথলজিতে পাঠালেন → প্যাথলজির তালিকায় "নতুন"');

  // ---- অ্যাপ নেই এমন রোগী: কাগজের প্রেসক্রিপশনের কোড দিয়ে প্যাথলজি নিজে খোলে ----
  const walkPt = (pts.patients || []).find((x) => !x.linkedUid) || (pts.patients || [])[0];
  const paper = await ok(DOC, 'rx_create', { rxPatientId: walkPt.id, tests: ['RBS'] });
  const walk = (await ok(LAB, 'lab_open_code', { code: paper.code })).item;
  assert.strictEqual(walk.status, 'received');
  LOG(`কাউন্টারে আসা রোগী: প্যাথলজি কোড ${paper.code} দিয়ে খুললেন → সরাসরি "নমুনা নেওয়া"`);

  // ---- প্যাথলজি: নমুনা নেওয়া → রিপোর্ট লেখা ----
  if (lo.status === 'sent') await ok(LAB, 'lab_order_status', { id: lo.id, status: 'received' });
  const forms = (await ok(LAB, 'lab_form', { id: lo.id })).forms;
  assert.deepStrictEqual(forms.map((f) => f.code), ['cbc', 'hba1c']);
  LOG(`নমুনা নেওয়া হলো; রিপোর্টের ছক: ${forms.map((f) => `${f.name} (${f.params.length}টা ঘর)`).join(', ')}`);
  const vals = { hb: '10.1', wbc: '7.5', plt: '250', hba1c: '7.9' };
  const items = forms.flatMap((f) => f.params.filter((p) => vals[p.key]).map((p) => ({ test: p.name, result: vals[p.key], unit: p.unit, group: f.name, code: f.code, key: p.key, low: p.low, high: p.high })));
  await ok(LAB, 'lab_report', { id: lo.id, items, note: 'ডেমো রিপোর্ট' });
  assert.strictEqual((await call(LAB, 'lab_order_status', { id: lo.id, status: 'cancelled' })).status, 404, 'রিপোর্টের পর বাতিল নয়');
  LOG('প্যাথলজি রিপোর্ট দিলেন (Hb কম, HbA1c বেশি)');

  // ---- রোগী রিপোর্ট দেখেন ----
  if (sender) {
    const pr = await ok(P, 'lab_order_get', { id: lo.id });
    const flags = Object.fromEntries(pr.item.report.items.map((i) => [i.key, i.flag]));
    assert.strictEqual(flags.hb, 'low');
    assert.strictEqual(flags.hba1c, 'high');
    LOG('রোগী রিপোর্ট দেখলেন — কম/বেশি চিহ্নসহ', JSON.stringify(flags), pr.history ? '(আগের ফল পাশে)' : '');
  }

  // ---- ডাক্তার: টাকা মেলার আগে ফল নয়, পরে দেখেন; ইতিহাসেও ----
  assert.strictEqual((await call(DOC, 'lab_order_get', { id: lo.id })).status, 402, 'টাকার আগে ডাক্তার ফল দেখেন না');
  if (sender) {
    await ok(P, 'review_pay', { id: lo.id, trxId: 'LABTEST123', method: 'bkash' });
    await ok(AS, 'review_confirm', { doctorUid: DOC, id: lo.id, ok: true });
    const dr = await ok(DOC, 'lab_order_get', { id: lo.id });
    assert.ok(dr.item.report.items.length >= 4);
    await ok(DOC, 'review_done', { id: lo.id, comment: 'আয়রন খাবেন, ১৫ দিন পর আসবেন' });
    const fin = await ok(P, 'rx_activity', { rxId: made.id });
    assert.ok(fin.reviews.some((r) => r.comment === 'আয়রন খাবেন, ১৫ দিন পর আসবেন'));
    LOG('রোগী টাকা দিলেন → সহকারী মেলালেন → ডাক্তার রিপোর্ট পড়ে মন্তব্য দিলেন → রোগী মন্তব্য পেলেন');
  }

  // ---- পরীক্ষা ও দাম (ডেমো প্যাথলজির পুরো তালিকা; পেছনের প্যাথলজির আধা তালিকা, একটার নেই) ----
  const pub = (await ok(null, 'labs', { district: DISTRICT })).items;
  const byName = (n) => pub.find((x) => x.name === n);
  const city = byName('ডেমো সিটি ডায়াগনস্টিক সেন্টার').prices;
  assert.strictEqual(city.items.length, 17, '১৬টা ছকের পরীক্ষা + ১টা নিজে লেখা (USG বন্ধ)');
  assert.deepStrictEqual([city.discountPct, city.homeFee], [10, 150]);
  assert.ok(city.items.some((i) => i.name === 'Serum Vitamin B12' && i.code === null));
  assert.ok(byName('ডেমো পপুলার প্যাথলজি').prices.items.length < 17);
  assert.strictEqual(byName('ডেমো রয়েল ল্যাব').prices, null, 'তালিকা নেই — "দাম জানা নেই"');
  const own = await ok(LAB, 'lab_prices_get');
  assert.strictEqual(own.items.length, 18, 'মালিক বন্ধগুলোও দেখেন');
  LOG(`পরীক্ষা ও দাম: সিটি ${city.items.length}টা (ছাড় ${city.discountPct}%, বাসা থেকে ৳${city.homeFee}), পপুলার ${byName('ডেমো পপুলার প্যাথলজি').prices.items.length}টা, রয়েল — তালিকা নেই`);
  const rx3 = await ok(DOC, 'rx_create', { appointmentId: apId, tests: ['CBC', 'HbA1c', 'TSH'] });
  const opt = (await ok(P, 'rx_lab_options', { rxId: rx3.id, district: DISTRICT })).items;
  LOG('রোগীর বাছাই (CBC, HbA1c, TSH):', opt.map((x) => `${x.name} ${x.priced ? `${x.have}/${x.total} ৳${x.estimate}` : 'দাম জানা নেই'}`).join(' · '));
  assert.strictEqual(opt[0].have, 3);
  assert.strictEqual(opt[opt.length - 1].priced === false || opt[opt.length - 1].have < 3, true);
  assert.ok(!opt.some((x) => !x.id.startsWith('demo-')), 'শুধু ডেমো প্যাথলজি');
  // বাসা থেকে নমুনার অনুরোধ (পেছনের রোগী করিম) — সময় নিশ্চিত
  const ho = (await ok(LAB, 'lab_orders')).items.find((o) => o.id === 'demo-rx-lab-fatema-home');
  assert.deepStrictEqual([ho.status, ho.home.status, ho.home.slot, ho.home.fee], ['sent', 'requested', 'early', 150]);
  assert.ok(ho.home.address && ho.prices && ho.prices.estimate === Math.round(2300 * 0.9));
  const tomorrow = require(path.join(SERVER, 'lib/prescriptions')).dhakaToday() + 86400000;
  await ok(LAB, 'lab_home_confirm', { id: ho.id, date: tomorrow, time: '07:30', note: 'রাত ১০টার পর কিছু খাবেন না' });
  assert.ok(F.pushes.slice(-1)[0].title.includes('কাল সকাল ৭টা ৩০ মিনিটে আসবে'));
  LOG(`বাসা থেকে নমুনা: ফাতেমা (কাল সকাল ৭–৯, ৳${ho.home.fee}) → প্যাথলজি "কাল সকাল ৭:৩০" নিশ্চিত করলেন, আনুমানিক ৳${ho.prices.estimate}`);
  // ডেমো রোগী বাসা থেকে চান
  const rxH = await ok(DOC, 'rx_create', { appointmentId: apId, tests: ['RBS'] });
  const hs = await ok(P, 'rx_send_lab', { rxId: rxH.id, labId: LAB, home: { address: 'ডেমো বাসা', phone: '01700000101', day: 1, slot: 'morning' } });
  await ok(LAB, 'lab_home_decline', { id: hs.id });
  LOG('ডেমো রোগী বাসা থেকে চাইলেন → প্যাথলজি "বাসায় যেতে পারব না"');

  // ---- প্যাথলজি: অর্ডার "করতে পারবে না" ----
  const waiting = (await ok(LAB, 'lab_orders')).items.find((o) => o.status === 'sent');
  if (waiting) {
    await ok(LAB, 'lab_order_status', { id: waiting.id, status: 'cancelled' });
    LOG('প্যাথলজি একটা অর্ডার "করতে পারবে না" দিলেন');
  }

  // ---- প্যাথলজি: নিজের স্বাভাবিক মাত্রা ----
  await ok(LAB, 'lab_ranges_set', { ranges: [{ code: 'rbs', key: 'glu', low: 4, high: 7.8 }] });
  LOG('প্যাথলজি নিজের স্বাভাবিক মাত্রা বসালেন');

  // ---- কেয়ারগিভার রোগীর হয়ে প্যাথলজিতে পাঠান ----
  const made2 = await ok(DOC, 'rx_create', { appointmentId: apId, tests: ['Lipid profile'] });
  if (sender) {
    const cg = await call(CG, 'rx_send_lab', { asUid: P, rxId: made2.id, labId: LAB, tests: ['Lipid profile'] });
    assert.strictEqual(cg.status, 200, JSON.stringify(cg.body));
    LOG('কেয়ারগিভার রোগীর হয়ে প্যাথলজিতে পাঠালেন');
  }

  // ---- মালিক: অপেক্ষার প্যাথলজি অনুমোদন ----
  const pend = (await ok(OWNER, 'admin_labs', { status: 'pending' })).items;
  const nl = pend.find((x) => /নিউ লাইফ/.test(x.name));
  assert.ok(nl, 'অপেক্ষার প্যাথলজি মালিকের তালিকায়');
  assert.strictEqual((await call(OWNER, 'admin_lab_resolve', { id: nl.id, approve: true })).status, 400, 'টিক ছাড়া নয়');
  await ok(OWNER, 'admin_lab_resolve', { id: nl.id, status: 'approved', checks: verify.CHECKS.lab().map((c) => c.id) });
  require(path.join(SERVER, 'lib/memcache')).clear();
  assert.ok((await ok(P, 'labs', { district: DISTRICT })).items.some((x) => x.id === nl.id));
  LOG('মালিক কাগজ দেখে টিক দিয়ে অনুমোদন দিলেন → তালিকায় এলো');

  // ---- ডেমো আর আসল আলাদা ----
  const REAL = 'real-user-0002';
  F.users.set(REAL, { uid: REAL, email: 'real2@example.com' });
  await db.doc(`users/${REAL}`).set({ name: 'আসল', phone: '01811000001' });
  assert.strictEqual((await call(LAB, 'lab_open_code', { code: 'ZZZZZZZZZZ' })).status === 404, true);
  // আসল ডাক্তারের প্রেসক্রিপশন ডেমো প্যাথলজি কোড দিয়ে খুলতে পারে না
  await db.doc('prescriptions/real-rx-1').set({ code: 'RE2LRX3456', doctorUid: 'real-doc-1', status: 'active', tests: ['CBC'], patient: { name: 'আসল রোগী' } });
  assert.strictEqual((await call(LAB, 'lab_open_code', { code: 'RE2LRX3456' })).status, 404);
  LOG('ডেমো প্যাথলজি আসল প্রেসক্রিপশনের কোড দিলে "এই কোডের কোনো প্রেসক্রিপশন নেই"');

  // ---- মোছা ----
  for (let i = 0; i < 5; i++) if (!(await ok(OWNER, 'admin_demo_wipe')).more) break;
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  assert.deepStrictEqual([...F.store.keys()].filter((k) => k.startsWith('labOrders/') || k.startsWith('rxReviews/')), []);
  LOG('সব ডেমো মুছে গেল, প্যাথলজির অর্ডার/রিপোর্ট কিছু বাকি নেই');
});
