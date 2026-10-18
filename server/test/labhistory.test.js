// ডাক্তারের রোগীর ইতিহাসে পার্টনার ল্যাবের রিপোর্ট (১৮ অক্টোবর ২০২৬, "ডাক্তারকে রিপোর্ট দেখানো")।
// নিয়ম: অবস্থা (পাঠানো/নমুনা নেওয়া/বাতিল/রিপোর্ট এসেছে) সবসময়; ফল শুধু lab_order_get-এর একই শর্তে —
// রিপোর্ট দেখার অনুরোধ 'paid'/'done' (টাকা মিলেছে, বা "ফি চাই না")। অন্য ডাক্তার কিছু পান না; সহকারী ফল পান না।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
process.env.CRON_SECRET = 'labhistory-secret';
const api = require(path.join(SERVER, 'api/community.js'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;

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

const U = {
  doc: { uid: 'lhDoctor', email: 'doc@lh.test', name: 'ডা. প্রথম' },
  doc2: { uid: 'lhDoctor2', email: 'doc2@lh.test', name: 'ডা. দ্বিতীয়' },
  asst: { uid: 'lhAssistant', email: 'asst@lh.test', name: 'সহকারী' },
  pat: { uid: 'lhPatient', email: 'pat@lh.test', name: 'রোগী রহিম', phone: '01711000301' },
  lab: { uid: 'lhLab', email: 'lab@lh.test', name: 'পরীক্ষা ল্যাব' },
};
const D = U.doc.uid, D2 = U.doc2.uid, AS = U.asst.uid, P = U.pat.uid, L = U.lab.uid;

function seed() {
  const s = F.store;
  for (const u of Object.values(U)) {
    F.users.set(u.uid, { uid: u.uid, email: u.email, displayName: u.name });
    s.set(`users/${u.uid}`, { name: u.name, phone: u.phone || '', lang: 'bn', fcmTokens: [] });
  }
  for (const d of [U.doc, U.doc2]) {
    s.set(`badges/${d.uid}`, { type: 'doctor', status: 'approved', regNo: `A-${d.uid.length}`, name: d.name });
    s.set(`providers/${d.uid}`, {
      type: 'doctor', name: d.name, degrees: 'MBBS', specialties: ['medicine'], listed: true, chambers: [],
      ...(d.uid === D ? { assistantEmails: [U.asst.email] } : {}),
    });
  }
  s.set(`labs/${L}`, { name: U.lab.name, status: 'approved', district: DIST, address: 'চরপাড়া', phone: '01711000398', licenseNo: 'DGHS-9' });
}

/** ডাক্তার রোগীকে পরীক্ষা দিলেন, রোগী ল্যাবে পাঠালেন — ল্যাব অর্ডারের id */
async function rxWithLab(doctor, rxPatientId, tests) {
  const rx = await call(doctor, 'rx_create', { rxPatientId, tests, advice: 'রিপোর্ট নিয়ে আসবেন' });
  await call(P, 'rx_send_lab', { rxId: rx.id, labId: L });
  const lo = (await call(L, 'lab_orders')).items.find((o) => o.rxId === rx.id);
  return { rx, lo };
}
const report = (id, value) => call(L, 'lab_report', { id, items: [{ test: 'Haemoglobin', result: value, unit: 'g/dL', range: '13 – 17', flag: '' }], note: 'নোট-' + value });
const labsOf = async (doctor, rxPatientId) => (await call(doctor, 'rx_patient_history', { rxPatientId })).labOrders;
/** উত্তরের কোথাও ফলের মান/নোট আছে কি না */
const leaks = (obj, value) => JSON.stringify(obj).includes(value);

test('ডাক্তারের ইতিহাসে ল্যাবের রিপোর্ট — টাকার একই শর্তে', async () => {
  seed();
  // রোগী এন্ট্রি, প্রথম প্রেসক্রিপশন, রোগী কোড দিয়ে নিজের অ্যাকাউন্টে আনলেন
  const pat = (await call(D, 'rx_patient_add', { name: 'রোগী রহিম', age: 50, gender: 'male', phone: '01711000301' })).patient;
  const first = await call(D, 'rx_create', { rxPatientId: pat.id, advice: 'বিশ্রাম' });
  await call(P, 'rx_claim', { code: first.code, phone: '01711000301' });

  // ---------- ১. "ফি চাই না": রিপোর্ট আসার সাথে সাথে ডাক্তার ফল দেখেন ----------
  await call(D, 'review_settings', { set: true, enabled: true, noFee: true });
  const a = await rxWithLab(D, pat.id, ['CBC']);
  let labs = await labsOf(D, pat.id);
  assert.deepStrictEqual(labs.map((o) => [o.id, o.status, o.open, o.report]), [[a.lo.id, 'sent', false, null]], 'পাঠানো পরীক্ষা অবস্থাসহ দেখা যায়');
  await report(a.lo.id, '11.1');
  labs = await labsOf(D, pat.id);
  const fa = labs.find((o) => o.id === a.lo.id);
  assert.strictEqual(fa.status, 'reported');
  assert.strictEqual(fa.review, 'paid');
  assert.strictEqual(fa.open, true);
  assert.strictEqual(fa.report.items[0].result, '11.1');
  assert.strictEqual(fa.report.note, 'নোট-11.1');
  assert.strictEqual(fa.report.photos, undefined, 'ইতিহাসে ছবি নয় (উত্তর ছোট)');
  assert.strictEqual(fa.rxId, a.rx.id);
  const got = await call(D, 'rx_get', { id: a.rx.id });
  assert.strictEqual(got.labOrders.length, 1);
  assert.strictEqual(got.labOrders[0].report.items[0].result, '11.1', 'প্রেসক্রিপশন খুললেও একই');
  assert.strictEqual((await call(D, 'lab_order_get', { id: a.lo.id })).item.report.items[0].result, '11.1', 'পুরো রিপোর্ট (ছবি, আগের ফল) খোলে');

  // ---------- ২. ফি আছে, টাকা দেননি: অবস্থা দেখা যায়, মান নয় ----------
  await call(D, 'review_settings', { set: true, enabled: true, noFee: false, fee: '৩০০ টাকা', payNumber: '01711000355', payMethods: ['bkash'] });
  const b = await rxWithLab(D, pat.id, ['CBC']);
  await call(L, 'lab_order_status', { id: b.lo.id, status: 'received' });
  labs = await labsOf(D, pat.id);
  assert.deepStrictEqual(labs.filter((o) => o.id === b.lo.id).map((o) => [o.status, o.open, o.review]), [['received', false, null]], 'নমুনা নেওয়া হয়েছে');
  await report(b.lo.id, '9.7');
  const hist = await call(D, 'rx_patient_history', { rxPatientId: pat.id });
  const fb = hist.labOrders.find((o) => o.id === b.lo.id);
  assert.deepStrictEqual([fb.status, fb.review, fb.open, fb.report, fb.comment], ['reported', 'unpaid', false, null, '']);
  assert.ok(fb.reportedAt > 0);
  assert.ok(!leaks(hist, '9.7') && !leaks(hist, 'নোট-9.7'), 'টাকা না দিলে ইতিহাসে মান বা নোট নেই');
  const gb = await call(D, 'rx_get', { id: b.rx.id });
  assert.ok(!leaks(gb, '9.7') && gb.labOrders[0].open === false, 'প্রেসক্রিপশন খুলেও নয়');
  await fails(D, 'lab_order_get', { id: b.lo.id }, 402);
  // নতুনটা আগে
  assert.deepStrictEqual(hist.labOrders.map((o) => o.id), [b.lo.id, a.lo.id]);

  // ---------- ৩. রোগী টাকা পাঠালেন (মেলানোর আগে নয়), সহকারী মিলিয়ে নিলেন → ডাক্তার দেখেন ----------
  await call(P, 'review_pay', { id: b.lo.id, method: 'bkash', trxId: 'LH12345678' });
  labs = await labsOf(D, pat.id);
  assert.deepStrictEqual(labs.filter((o) => o.id === b.lo.id).map((o) => [o.review, o.open, o.report]), [['claimed', false, null]]);
  assert.ok(!leaks(labs, 'LH12345678'), 'ইতিহাসে TrxID নয়');
  await call(AS, 'review_confirm', { doctorUid: D, id: b.lo.id, ok: true });
  labs = await labsOf(D, pat.id);
  const fb2 = labs.find((o) => o.id === b.lo.id);
  assert.deepStrictEqual([fb2.review, fb2.open, fb2.report.items[0].result], ['paid', true, '9.7']);
  await call(D, 'review_done', { id: b.lo.id, comment: 'রক্তস্বল্পতা — আয়রন খান' });
  labs = await labsOf(D, pat.id);
  assert.strictEqual(labs.find((o) => o.id === b.lo.id).comment, 'রক্তস্বল্পতা — আয়রন খান', 'নিজের মন্তব্যও ইতিহাসে');

  // ---------- ৪. অনলাইনে রিপোর্ট দেখা বন্ধ: কখনো মান নয়; ল্যাব বাতিল করলে অবস্থা ----------
  await call(D, 'review_settings', { set: true, enabled: false });
  const c = await rxWithLab(D, pat.id, ['RBS']);
  await report(c.lo.id, '6.6');
  const dd = await rxWithLab(D, pat.id, ['ECG']);
  await call(L, 'lab_order_status', { id: dd.lo.id, status: 'cancelled' });
  const h4 = await call(D, 'rx_patient_history', { rxPatientId: pat.id });
  assert.deepStrictEqual(h4.labOrders.filter((o) => o.id === c.lo.id).map((o) => [o.review, o.open, o.report]), [['off', false, null]]);
  assert.ok(!leaks(h4, '6.6'));
  assert.deepStrictEqual(h4.labOrders.filter((o) => o.id === dd.lo.id).map((o) => [o.status, o.open]), [['cancelled', false]]);

  // ---------- ৫. অন্য ডাক্তার: এই রোগী/প্রেসক্রিপশন/রিপোর্ট কিছুই নয়; নিজের রোগীর ইতিহাসে শুধু নিজেরটা ----------
  await fails(D2, 'rx_patient_history', { rxPatientId: pat.id }, 404);
  await fails(D2, 'rx_get', { id: a.rx.id }, 404);
  await fails(D2, 'lab_order_get', { id: a.lo.id }, 404);
  await call(D2, 'review_settings', { set: true, enabled: true, noFee: true });
  const pat2 = (await call(D2, 'rx_patient_add', { name: 'রোগী রহিম', age: 50, gender: 'male', phone: '01711000301' })).patient;
  const f2 = await call(D2, 'rx_create', { rxPatientId: pat2.id, advice: 'x' });
  await call(P, 'rx_claim', { code: f2.code, phone: '01711000301' });
  const e = await rxWithLab(D2, pat2.id, ['CBC']);
  await report(e.lo.id, '12.4');
  const mine2 = await call(D2, 'rx_patient_history', { rxPatientId: pat2.id });
  assert.deepStrictEqual(mine2.labOrders.map((o) => [o.id, o.open]), [[e.lo.id, true]], 'দ্বিতীয় ডাক্তার শুধু নিজের প্রেসক্রিপশনের রিপোর্ট');
  assert.ok(!leaks(mine2, '11.1') && !leaks(mine2, '9.7'), 'প্রথম ডাক্তারের ফল নয়');
  const h5 = await call(D, 'rx_patient_history', { rxPatientId: pat.id });
  assert.ok(!h5.labOrders.some((o) => o.id === e.lo.id) && !leaks(h5, '12.4'), 'প্রথম ডাক্তারও দ্বিতীয়জনেরটা পান না (রোগী একই হলেও)');

  // ---------- ৬. সহকারী: ইতিহাস/প্রেসক্রিপশন/রিপোর্ট নয় (আগের নিয়ম); "রিপোর্ট দেখা"-য় শুধু টাকার তথ্য ----------
  await fails(AS, 'rx_patient_history', { doctorUid: D, rxPatientId: pat.id }, 403);
  await fails(AS, 'rx_get', { id: a.rx.id }, 404);
  await fails(AS, 'lab_order_get', { id: a.lo.id }, 404);
  const asList = await call(AS, 'reviews_doctor', { doctorUid: D });
  assert.ok(asList.items.length >= 2);
  assert.ok(!leaks(asList, '11.1') && !leaks(asList, '9.7') && asList.items.every((r) => r.report === undefined), 'সহকারীর তালিকায় ফল নেই');

  // ---------- ৭. রোগী নিজে: আগের মতো সব রিপোর্ট পান, ইতিহাস চাইতে পারেন না ----------
  await fails(P, 'rx_patient_history', { rxPatientId: pat.id }, 403);
  const own = await call(P, 'rx_get', { id: b.rx.id });
  assert.strictEqual(own.labOrders, undefined, 'রোগীর rx_get আগের মতোই (রিপোর্ট rx_activity দিয়ে)');
  assert.strictEqual((await call(P, 'lab_order_get', { id: c.lo.id })).item.report.items[0].result, '6.6');
});
