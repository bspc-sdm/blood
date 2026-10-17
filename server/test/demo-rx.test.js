// ডেমো (rx ভাগ): ডাক্তার, সিরিয়াল, প্রেসক্রিপশন, সহকারী, ফার্মেসি/প্যাথলজির সাথে কাজ — আসল কাজ (action) দিয়ে পড়া,
// পরীক্ষকের মতো কয়েকটা কাজ করা, তারপর মোছা। (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const RXD = require(path.join(SERVER, 'lib/demo/rx'));
const K = require(path.join(SERVER, 'lib/care'));
const P = require(path.join(SERVER, 'lib/prescriptions'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const { db } = F.firebase;

const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
const DAY = 86400000;

async function raw(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : {};
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function call(uid, action, body) {
  const r = await raw(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}

const U = (k) => demo.uidOf(k);
const X = (k) => demo.uidOf(`x-${k}`);

test('rx ডেমো: ডাক্তার, সিরিয়াল, প্রেসক্রিপশন, সহকারী, ফার্মেসি, প্যাথলজি — দেখা, কাজ করা, মোছা', async () => {
  const s = await call(OWNER, 'admin_demo_seed');
  assert.ok(s.docs > 50);
  const DOC = U('doctor'); const AS = U('assistant'); const PT = U('patient'); const PH = U('pharmacy'); const LAB = U('lab');
  const today = P.dhakaToday();

  // ---------- ডাক্তারের তালিকা (অতিথিও দেখেন) ----------
  const list = (await call(null, 'providers', { type: 'doctor', district: DIST, homeVisit: false })).items;
  const names = list.map((p) => p.name);
  for (const n of ['ডা. ডেমো হাসান', 'ডা. ডেমো নাসরিন আক্তার', 'ডা. ডেমো কামরুল ইসলাম', 'ডা. ডেমো মাহবুব আলম', 'ডা. ডেমো ফারজানা হক']) assert.ok(names.includes(n), n);
  assert.ok(!names.includes('ডা. ডেমো তানভীর রহমান'), 'যাচাইয়ের অপেক্ষার ডাক্তার তালিকায় নেই');
  const hasan = list.find((p) => p.uid === DOC);
  assert.strictEqual(hasan.chambers.length, 2);
  assert.ok(hasan.appointments && hasan.chambers.every((c) => c.appointments));
  assert.strictEqual(list.find((p) => p.name === 'ডা. ডেমো ফারজানা হক').appointments, false);
  const cardio = (await call(null, 'providers', { type: 'doctor', district: DIST, specialty: 'cardiology', homeVisit: false })).items;
  assert.deepStrictEqual(cardio.map((p) => p.name), ['ডা. ডেমো মাহবুব আলম']);

  // ---------- ডাক্তার নিজে ----------
  const me = await call(DOC, 'me');
  assert.strictEqual(me.badge.type, 'doctor');
  assert.strictEqual(me.badge.status, 'approved');
  assert.match(me.badge.regNo, /^DEMO-/);
  const rxMe = await call(DOC, 'rx_me');
  assert.strictEqual(rxMe.chambers.length, 2);

  // আজকের সিরিয়াল (ডাক্তার)
  const docAppts = (await call(DOC, 'my_appointments')).items.filter((a) => a.role === 'provider');
  const todays = docAppts.filter((a) => a.date === today);
  assert.deepStrictEqual(todays.filter((a) => a.status === 'confirmed').map((a) => a.serial).sort(), [1, 2, 3, 4]);
  assert.strictEqual(todays.filter((a) => a.status === 'pending').length, 1);
  assert.strictEqual(todays.filter((a) => a.status === 'cancelled').length, 1);
  assert.ok(todays.find((a) => a.serial === 3).otherPhone, 'নিশ্চিত হলে রোগীর ফোন দেখা যায়');
  assert.ok(docAppts.some((a) => a.date > today && a.status === 'pending'));
  assert.ok(docAppts.some((a) => a.status === 'completed'));
  assert.ok(docAppts.some((a) => a.status === 'declined'));

  // রোগী: সব অবস্থার অ্যাপয়েন্টমেন্ট
  const patAppts = (await call(PT, 'my_appointments')).items.filter((a) => a.role === 'patient');
  const st = new Set(patAppts.map((a) => a.status));
  for (const x of ['confirmed', 'pending', 'completed', 'expired', 'declined', 'cancelled']) assert.ok(st.has(x), x);
  const mine3 = patAppts.find((a) => a.date === today);
  assert.strictEqual(mine3.serial, 3);
  assert.strictEqual(mine3.otherPhone, '01700000201');

  // ---------- সহকারী ----------
  const assisting = (await call(AS, 'rx_assisting')).items;
  assert.deepStrictEqual(assisting.map((d) => d.doctorUid), [DOC]);
  const rp = await call(AS, 'rx_patients', { doctorUid: DOC });
  assert.strictEqual(rp.role, 'assistant');
  assert.ok(rp.appointments.filter((a) => a.date === today && a.serial).length >= 4);
  assert.ok(rp.patients.some((p) => p.name === 'ডেমো রোগী জমিলা খাতুন'));
  assert.ok(rp.appPatients.some((p) => p.name === 'ডেমো রোগী রহিম'));
  assert.strictEqual((await raw(AS, 'rx_create', { doctorUid: DOC, appointmentId: 'demo-rx-appt-03', advice: 'x' })).status, 403, 'সহকারী লিখতে পারেন না');
  assert.strictEqual((await raw(X('rx-pat-salma'), 'rx_patients', { doctorUid: DOC })).status, 403, 'অন্য কেউ সহকারী নন');

  // ---------- প্রেসক্রিপশন ----------
  const docList = (await call(DOC, 'rx_doctor_list')).items;
  assert.ok(docList.length >= 9);
  assert.ok(docList.some((r) => r.code === RXD.CODES.C && r.status === 'cancelled'));
  const mineRx = (await call(PT, 'rx_mine')).items;
  const codes = mineRx.map((r) => r.code);
  for (const c of [RXD.CODES.A, RXD.CODES.B, RXD.CODES.C]) assert.ok(codes.includes(c), c);
  assert.ok(!codes.includes(RXD.CODES.PAPER), 'কাগজেরটা কোড দিয়ে যোগের আগে নেই');
  const rxB = mineRx.find((r) => r.code === RXD.CODES.B);
  assert.strictEqual(rxB.doctor.regNo, 'DEMO-A-10001');
  assert.ok(rxB.followUpDate > today);
  const act = await call(PT, 'rx_activity', { rxId: rxB.id });
  assert.deepStrictEqual(act.medicines.map((m) => [m.name, m.got, m.need]), [['Comet', 30, 60], ['Rosuva', 30, 30], ['Bislol', 0, 30], ['Napa', 0, 9]]);
  assert.deepStrictEqual(act.orders.map((o) => o.status), ['done']);
  assert.deepStrictEqual(act.labOrders.map((o) => o.status).sort(), ['received', 'reported']);
  assert.strictEqual(act.reviews.find((r) => r.status === 'claimed').fee, '৩০০ টাকা');
  assert.ok(act.nextOpen >= today);
  const rep = await call(PT, 'lab_order_get', { id: 'demo-rx-lab-me-lipid' });
  assert.ok(rep.item.report.items.some((i) => i.code === 'lipid' && i.key === 'ldl' && i.flag === 'high'));
  assert.strictEqual(rep.history['lipid.ldl'][0].result, '152', 'আগের রিপোর্টের ফল পাশে');

  // ---------- ফার্মেসি ----------
  const orders = (await call(PH, 'rx_orders_pharmacy')).items;
  const shopIn = orders.find((o) => o.id === 'demo-rx-order-nasima');
  assert.strictEqual(shopIn.status, 'sent');
  assert.strictEqual(shopIn.mode, 'shop');
  const online = orders.find((o) => o.id === 'demo-rx-order-karim-online');
  assert.strictEqual(online.mode, 'online');
  assert.ok(online.address && online.phone);
  const home = await call(PH, 'rx_pharmacy_home');
  assert.ok(home.online.enabled);
  assert.ok(home.missed.some((m) => m.name === 'Thyrox'));
  const opened = (await call(PH, 'rx_pharmacy_open', { code: RXD.CODES.C })).item;
  assert.strictEqual(opened.status, 'cancelled');
  const opts = (await call(PT, 'rx_pharmacy_options', { rxId: rxB.id, district: DIST, mode: 'shop' })).items;
  const shopOpt = opts.find((o) => o.id === PH);
  assert.ok(shopOpt, 'ডেমো ফার্মেসি রোগীর তালিকায়');

  // ---------- প্যাথলজি ----------
  const labOrders = (await call(LAB, 'lab_orders')).items;
  const lst = new Set(labOrders.map((o) => o.status));
  for (const x of ['sent', 'received', 'reported', 'cancelled']) assert.ok(lst.has(x), x);
  assert.ok((await call(null, 'labs', { district: DIST })).items.some((l) => l.id === LAB));

  // ---------- রিপোর্ট দেখানো (ডাক্তার) ----------
  const revs = (await call(DOC, 'reviews_doctor')).items;
  const rst = new Set(revs.map((r) => r.status));
  for (const x of ['done', 'claimed', 'paid', 'unpaid']) assert.ok(rst.has(x), x);

  // ---------- মালিক: যাচাইয়ের অপেক্ষা ----------
  const pend = (await call(OWNER, 'admin_badges', { status: 'pending', panel: true })).items;
  assert.ok(pend.some((b) => b.name === 'ডা. ডেমো তানভীর রহমান' && b.status === 'pending' && b.type === 'doctor'));
  assert.ok(pend.some((b) => b.name === 'ডা. ডেমো কামরুল ইসলাম' && b.change && b.change.regNo === 'DEMO-A-55555'));

  // =================== পরীক্ষকের মতো কাজ ===================
  // রোগী সিরিয়াল চান → ডাক্তার নিশ্চিত করেন
  const ch1 = hasan.chambers[0];
  let date = today + DAY;
  while (!K.chamberOpenOn(ch1, date)) date += DAY;
  const dup = await raw(PT, 'book', { providerUid: DOC, chamberIndex: 0, date: today, note: 'আবার' });
  assert.ok([400, 409].includes(dup.status), 'আজকের সিরিয়াল আগেই আছে / সময় শেষ');
  const booked = await call(PT, 'book', { providerUid: DOC, chamberIndex: 0, date, note: 'ডেমো পরীক্ষা: সুগার মাপাতে চাই', chamberKey: ch1.hospitalId });
  await call(DOC, 'appointment_update', { id: booked.id, op: 'confirm', providerNote: '' });
  const habib = todays.find((a) => a.status === 'pending');
  await call(DOC, 'appointment_update', { id: habib.id, op: 'confirm', providerNote: 'সন্ধ্যা ৭টায় আসবেন' });
  assert.strictEqual((await db.doc(`appointments/${habib.id}`).get()).get('serial'), 5, 'আজকের পরের সিরিয়াল ৫');

  // ডাক্তার আজকের রোগীর জন্য প্রেসক্রিপশন লেখেন → রোগী সাথে সাথে পান
  const made = await call(DOC, 'rx_create', {
    appointmentId: 'demo-rx-appt-03', chamberIndex: 0, diagnosis: 'Type 2 DM (controlled)',
    medicines: [{ name: 'Comet', form: 'Tablet', strength: '500 mg', dose: '1+0+1', days: 30, timing: 'after' }], tests: ['FBS', 'HbA1c'], followUpDays: 30,
  });
  assert.ok((await call(PT, 'rx_mine')).items.some((r) => r.id === made.id));

  // রোগী দোকানে পাঠান → ফার্মেসি ওষুধ দেয়
  await call(PT, 'rx_send_pharmacy', { rxId: rxB.id, pharmacyId: PH, mode: 'shop' });
  assert.ok((await call(PH, 'rx_orders_pharmacy')).items.some((o) => o.rxId === rxB.id && o.status === 'sent'));
  const disp = await call(PH, 'rx_dispense', { code: RXD.CODES.B, items: [{ i: 0, qty: 30 }, { i: 2, qty: 30 }] });
  assert.ok(disp.item.medicines[0].done && disp.item.medicines[2].done);
  assert.strictEqual((await raw(PH, 'rx_dispense', { code: RXD.CODES.C, items: [{ i: 0, qty: 1 }] })).status, 409, 'বাতিল প্রেসক্রিপশনে ওষুধ নয়');
  // অনলাইন অর্ডার নেওয়া
  await call(PH, 'rx_order_update', { id: 'demo-rx-order-karim-online', status: 'accepted', price: '৮৫০ টাকা', eta: 'আজ রাত ৮টা' });

  // সহকারী টাকা মেলান → ডাক্তার রিপোর্ট দেখে জানান
  await call(AS, 'review_confirm', { doctorUid: DOC, id: 'demo-rx-lab-me-lipid', ok: true });
  await call(DOC, 'review_done', { id: 'demo-rx-lab-me-lipid', comment: 'কোলেস্টেরল কমছে, ওষুধ চলবে।' });
  await call(DOC, 'review_done', { id: 'demo-rx-lab-salma-tsh', comment: 'TSH বেশি — Thyrox 75 চলবে।' });

  // প্যাথলজি: নমুনা নেয়, রিপোর্ট দেয় → রোগী টাকা পাঠান
  await call(LAB, 'lab_order_status', { id: 'demo-rx-lab-salma-new', status: 'received' });
  const form = (await call(LAB, 'lab_form', { id: 'demo-rx-lab-me-cbc' })).forms[0];
  assert.strictEqual(form.code, 'cbc');
  await call(LAB, 'lab_report', { id: 'demo-rx-lab-me-cbc', items: [{ test: 'Haemoglobin', result: '13.8', unit: 'g/dL', group: form.name, code: 'cbc', key: 'hb', low: 13, high: 17 }] });
  const unpaid = (await call(PT, 'rx_activity', { rxId: rxB.id })).reviews.find((r) => r.labOrderId === 'demo-rx-lab-me-cbc');
  assert.strictEqual(unpaid.status, 'unpaid');
  await call(PT, 'review_pay', { id: 'demo-rx-lab-me-cbc', trxId: 'TEST12345', method: 'bkash' });
  // রোগী নিজে পরীক্ষা পাঠান (নতুন প্রেসক্রিপশন থেকে)
  await call(PT, 'rx_send_lab', { rxId: made.id, labId: LAB, tests: ['FBS', 'HbA1c'] });
  // একই প্যাথলজিতে চালু থাকা অবস্থায় আবার পাঠানো যায় না
  assert.strictEqual((await raw(PT, 'rx_send_lab', { rxId: made.id, labId: LAB })).status, 409);

  // কাগজের প্রেসক্রিপশন কোড দিয়ে যোগ
  const phone = (await db.doc(`users/${PT}`).get()).get('phone');
  assert.strictEqual((await raw(PT, 'rx_claim', { code: RXD.CODES.PAPER, phone: '01799999999' })).status, 400);
  assert.strictEqual((await call(PT, 'rx_claim', { code: RXD.CODES.PAPER, phone })).added, 1);
  assert.ok((await call(PT, 'rx_mine')).items.some((r) => r.code === RXD.CODES.PAPER));

  // সহকারী নতুন রোগী এন্ট্রি করেন
  await call(AS, 'rx_patient_add', { doctorUid: DOC, name: 'ডেমো নতুন রোগী', age: 30, gender: 'male', phone: '01700000399' });

  // টিম: সহকারী সরানো → আর ঢুকতে পারেন না → আবার যোগ
  await call(DOC, 'rx_team_set', { emails: [] });
  assert.deepStrictEqual((await call(AS, 'rx_assisting')).items, []);
  assert.strictEqual((await raw(AS, 'rx_patients', { doctorUid: DOC })).status, 403);
  await call(DOC, 'rx_team_set', { emails: ['assistant@lifolo-demo.test'] });
  assert.strictEqual((await call(AS, 'rx_assisting')).items.length, 1);

  // মালিক যাচাই করে অনুমোদন দেন → তালিকায় আসেন
  const tanvir = pend.find((b) => b.name === 'ডা. ডেমো তানভীর রহমান');
  assert.strictEqual((await raw(OWNER, 'admin_badge_resolve', { uid: tanvir.uid, approve: true, checks: [] })).status, 400, 'সব টিক ছাড়া অনুমোদন নয়');
  await call(OWNER, 'admin_badge_resolve', { uid: tanvir.uid, approve: true, checks: tanvir.verify.checks.map((c) => c.id) });
  const after = (await call(null, 'providers', { type: 'doctor', district: DIST, homeVisit: false })).items;
  assert.ok(after.some((p) => p.name === 'ডা. ডেমো তানভীর রহমান'));

  // =================== মোছা ===================
  const demoUids = new Set([...F.users.keys()].filter((u) => demo.isDemoUid(u)));
  const w = await call(OWNER, 'admin_demo_wipe');
  assert.ok(w.users >= 10);
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, [], 'demo: true কিছু নেই');
  assert.ok(![...F.users.keys()].some((u) => demo.isDemoUid(u)));
  // পরীক্ষার সময় বানানো জিনিস: অ্যাপয়েন্টমেন্ট, ফার্মেসি/প্যাথলজির অনুরোধ, রিপোর্ট দেখার অনুরোধ — সব গেছে
  for (const c of ['appointments', 'rxOrders', 'labOrders', 'rxReviews']) {
    const rest = [...F.store.keys()].filter((p) => p.startsWith(`${c}/`));
    assert.deepStrictEqual(rest, [], c);
  }
  // যা থেকে যায় (account.js-এর deleteNow ধরে না — রিপোর্টে লেখা): ডাক্তারের লেখা প্রেসক্রিপশন, সহকারীর এন্ট্রি করা রোগী,
  // নতুন দিনের সিরিয়ালের গোনা, মালিকের কাজের লগ
  const mentions = [...F.store.entries()].filter(([, v]) => [...demoUids].some((u) => JSON.stringify(v).includes(`"${u}"`) || JSON.stringify(v).includes(u)))
    .map(([k]) => k);
  const serialLeft = [...F.store.keys()].filter((k) => k.startsWith(`serials/${DOC}_`));
  const gaps = [...new Set([...mentions, ...serialLeft])].map((k) => k.split('/')[0]).sort();
  for (const g of gaps) assert.ok(['prescriptions', 'rxPatients', 'serials', 'adminLog', 'adminLogs', 'auditLog'].includes(g), `অপ্রত্যাশিত বাকি: ${g}`);
  assert.ok(gaps.includes('prescriptions') && gaps.includes('rxPatients') && gaps.includes('serials'));
});
