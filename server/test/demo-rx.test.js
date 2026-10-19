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
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur').bn;
const { db } = F.firebase;
// নকল Firebase-এ নেই এমন দুটো জিনিস (account.js-এর deleteNow লাগে): recursiveDelete আর ref.parent
if (!db.recursiveDelete) {
  db.recursiveDelete = async (ref) => { [...F.store.keys()].filter((k) => k === ref.path || k.startsWith(`${ref.path}/`)).forEach((k) => F.store.delete(k)); };
}
const origDoc = db.doc;
db.doc = (p) => {
  const r = origDoc(p);
  if (!r.parent) {
    const parts = String(p).split('/');
    r.parent = { path: parts.slice(0, -1).join('/'), parent: parts.length > 2 ? db.doc(parts.slice(0, -2).join('/')) : null };
  }
  return r;
};

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
  assert.ok(!patAppts.some((a) => a.date === today), 'আজ ডেমো ডাক্তারের কাছে রোগীর কিছু নেই — নিজে সিরিয়াল নেবেন');
  assert.ok(patAppts.some((a) => a.status === 'confirmed' && a.serial === 2 && a.otherPhone === '01700000213'), 'নিশ্চিত হলে ডাক্তারের ফোন');

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
  assert.ok(home.missed.some((m) => /^Thyrox/.test(m.name)));
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

  // =================== রোগীর পুরো যাত্রা (journey.json) — আসল কাজ দিয়ে ===================
  // J: রোগী আজ (চেম্বারের সময় শেষ হলে কাল) ডেমো ডাক্তারের সিরিয়াল চান
  const ch1 = hasan.chambers[0];
  let b = await raw(PT, 'book', { providerUid: DOC, chamberIndex: 0, date: today, note: 'সুগার বেশি, মাথা ঘোরে', chamberKey: ch1.hospitalId });
  if (b.status !== 200) {
    assert.match(b.body.error, /সময় শেষ/);
    b = await raw(PT, 'book', { providerUid: DOC, chamberIndex: 0, date: today + DAY, note: 'সুগার বেশি, মাথা ঘোরে', chamberKey: ch1.hospitalId });
  }
  assert.strictEqual(b.status, 200, JSON.stringify(b.body));
  const apptId = b.body.id;
  const dup = await raw(PT, 'book', { providerUid: DOC, chamberIndex: 0, date: (await db.doc(`appointments/${apptId}`).get()).get('date'), note: 'আবার' });
  assert.strictEqual(dup.status, 409, 'একই দিনে দুবার নয়');
  // J: ডাক্তার নিশ্চিত করেন → সিরিয়াল; সহকারীর রোগীর তালিকায় আসে; ফোন নম্বর দুজনেই দেখেন
  assert.strictEqual((await raw(AS, 'appointment_update', { id: apptId, op: 'confirm' })).status, 400, 'সহকারী বুকিং নিশ্চিত করতে পারেন না');
  await call(DOC, 'appointment_update', { id: apptId, op: 'confirm', providerNote: 'সিরিয়াল এলে ফোন করা হবে' });
  const myA = (await call(PT, 'my_appointments')).items.find((a) => a.id === apptId);
  assert.strictEqual(myA.status, 'confirmed');
  assert.ok(myA.serial >= 1 && myA.otherPhone);
  const docA = (await call(DOC, 'my_appointments')).items.find((a) => a.id === apptId && a.role === 'provider');
  assert.strictEqual(docA.otherPhone, (await db.doc(`users/${PT}`).get()).get('phone'));
  assert.ok((await call(AS, 'rx_patients', { doctorUid: DOC })).appointments.some((a) => a.id === apptId && a.serial === myA.serial));
  // J: ডাক্তার ওষুধ আর পরীক্ষা দিয়ে প্রেসক্রিপশন লেখেন
  const made = await call(DOC, 'rx_create', {
    appointmentId: apptId, chamberIndex: 0, complaints: 'সুগার বেশি, মাথা ঘোরে', findings: { bp: '140/90' }, diagnosis: 'Type 2 DM',
    medicines: [
      { name: 'Comet', form: 'Tablet', strength: '500 mg', dose: '1+0+1', days: 30, timing: 'after' },
      { name: 'Napa', form: 'Tablet', strength: '500 mg', dose: '1+1+1', days: 3, timing: 'after' },
    ],
    tests: ['CBC', 'HbA1c'], advice: 'মিষ্টি বাদ', followUpDays: 15,
  });
  // J: রোগী প্রেসক্রিপশন দেখেন
  const got = (await call(PT, 'rx_mine')).items.find((r) => r.id === made.id);
  assert.deepStrictEqual(got.tests, ['CBC', 'HbA1c']);
  // J: রোগী ডেমো ফার্মেসিতে অনলাইনে অর্ডার দেন (স্টকে আছে দেখায়) → ফার্মেসি নেয়, পাঠায়, ওষুধ লেখে
  const onl = (await call(PT, 'rx_pharmacy_options', { rxId: made.id, district: DIST, upazilaId: 320, mode: 'online' })).items.find((o) => o.id === PH);
  assert.ok(onl && onl.online && onl.have === 2, JSON.stringify(onl));
  const ord = await call(PT, 'rx_send_pharmacy', { rxId: made.id, pharmacyId: PH, mode: 'online', address: 'ডেমো বাসা ৫, শিববাড়ি', phone: '01700000101' });
  await call(PH, 'rx_order_update', { id: ord.id, status: 'accepted', price: '৪২০ টাকা', eta: 'এক ঘণ্টায়' });
  await call(PH, 'rx_order_update', { id: ord.id, status: 'out' });
  await call(PH, 'rx_dispense', { code: made.code, items: [{ i: 0, qty: 60 }, { i: 1, qty: 9 }] });
  const a1 = await call(PT, 'rx_activity', { rxId: made.id });
  assert.strictEqual(a1.orders[0].status, 'done');
  assert.ok(a1.medicines.every((m) => m.done));
  // J: রোগী ডেমো প্যাথলজিতে পরীক্ষা পাঠান → প্যাথলজি নমুনা নেয়, রিপোর্ট দেয়
  await call(PT, 'rx_send_lab', { rxId: made.id, labId: LAB, tests: ['CBC', 'HbA1c'] });
  assert.strictEqual((await raw(PT, 'rx_send_lab', { rxId: made.id, labId: LAB })).status, 409, 'একই প্যাথলজিতে আবার নয়');
  const lo = (await call(LAB, 'lab_orders')).items.find((o) => o.rxId === made.id);
  await call(LAB, 'lab_order_status', { id: lo.id, status: 'received' });
  const forms = (await call(LAB, 'lab_form', { id: lo.id })).forms;
  assert.deepStrictEqual(forms.map((f) => f.code), ['cbc', 'hba1c']);
  const vals = { hb: '13.2', wbc: '7.5', plt: '250', hba1c: '7.9' };
  const items = forms.flatMap((f) => f.params.filter((p) => vals[p.key]).map((p) => ({ test: p.name, result: vals[p.key], unit: p.unit, group: f.name, code: f.code, key: p.key, low: p.low, high: p.high })));
  await call(LAB, 'lab_report', { id: lo.id, items, note: 'ডেমো রিপোর্ট' });
  // J: রোগী রিপোর্ট দেখেন (আগের HbA1c পাশে)
  const pr = await call(PT, 'lab_order_get', { id: lo.id });
  assert.strictEqual(pr.item.report.items.find((i) => i.key === 'hba1c').flag, 'high');
  assert.strictEqual(pr.history['hba1c.hba1c'][0].result, '8.4');
  // J: ডাক্তার রিপোর্ট দেখেন — অনলাইনে রিপোর্ট দেখার ফি: রোগী TrxID দেন → সহকারী মেলান → ডাক্তার পড়েন ও জানান
  assert.strictEqual((await raw(DOC, 'lab_order_get', { id: lo.id })).status, 402, 'টাকা মেলার আগে ডাক্তার রিপোর্ট দেখেন না');
  await call(PT, 'review_pay', { id: lo.id, trxId: 'DEMO9X8Y7Z', method: 'bkash' });
  assert.ok((await call(AS, 'reviews_doctor', { doctorUid: DOC })).items.some((r) => r.id === lo.id && r.status === 'claimed' && r.trxId === 'DEMO9X8Y7Z'));
  await call(AS, 'review_confirm', { doctorUid: DOC, id: lo.id, ok: true });
  const dr = await call(DOC, 'lab_order_get', { id: lo.id });
  assert.ok(dr.item.report.items.length >= 4);
  assert.strictEqual(dr.history['hba1c.hba1c'][0].result, '8.4', 'ডাক্তারও আগের ফল দেখেন');
  await call(DOC, 'review_done', { id: lo.id, comment: 'HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।' });
  const fin = await call(PT, 'rx_activity', { rxId: made.id });
  assert.strictEqual(fin.reviews.find((r) => r.labOrderId === lo.id).comment, 'HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।');
  // ডাক্তারের "রোগী" ইতিহাসে আগের প্রেসক্রিপশন, আর পাশে পার্টনার ল্যাবের পরীক্ষা — ফল শুধু টাকা মিললে (J-18)
  const h = await call(DOC, 'rx_patient_history', { appointmentId: apptId });
  const hist = h.items;
  assert.ok(hist.some((r) => r.id === made.id) && hist.some((r) => r.code === RXD.CODES.B));
  const hl = Object.fromEntries(h.labOrders.map((o) => [o.id, o]));
  assert.ok(hl[lo.id].open && hl[lo.id].review === 'done' && hl[lo.id].report.items.length >= 4, 'আজকের রিপোর্ট (টাকা মিলেছে) — ফলসহ');
  assert.ok(hl['demo-rx-lab-me-old'].open && hl['demo-rx-lab-me-old'].comment, '৭৫ দিন আগের রিপোর্ট — দেখা হয়েছে, নিজের মন্তব্যসহ');
  assert.ok(!hl['demo-rx-lab-me-lipid'].open && hl['demo-rx-lab-me-lipid'].review === 'claimed' && hl['demo-rx-lab-me-lipid'].report === null, 'টাকা মেলানো বাকি — ফল নয়');
  assert.strictEqual(hl['demo-rx-lab-me-cbc'].status, 'received');
  const kh = await call(DOC, 'rx_patient_history', { appointmentId: todays.find((a) => a.serial === 2).id });
  assert.ok(kh.labOrders.some((o) => o.id === 'demo-rx-lab-karim' && o.review === 'unpaid' && o.report === null), 'করিম টাকা দেননি — ফল নয়');

  // =================== আরও কয়েকটা কাজ ===================
  const habib = todays.find((a) => a.status === 'pending');
  await call(DOC, 'appointment_update', { id: habib.id, op: 'confirm', providerNote: 'সন্ধ্যা ৭টায় আসবেন' });
  assert.ok((await db.doc(`appointments/${habib.id}`).get()).get('serial') >= 5, 'আজকের আগের সিরিয়ালের পরে');
  // দোকানে গিয়ে (আগের প্রেসক্রিপশন)
  await call(PT, 'rx_send_pharmacy', { rxId: rxB.id, pharmacyId: PH, mode: 'shop' });
  assert.ok((await call(PH, 'rx_orders_pharmacy')).items.some((o) => o.rxId === rxB.id && o.status === 'sent'));
  const disp = await call(PH, 'rx_dispense', { code: RXD.CODES.B, items: [{ i: 0, qty: 30 }, { i: 2, qty: 30 }] });
  assert.ok(disp.item.medicines[0].done && disp.item.medicines[2].done);
  assert.strictEqual((await raw(PH, 'rx_dispense', { code: RXD.CODES.C, items: [{ i: 0, qty: 1 }] })).status, 409, 'বাতিল প্রেসক্রিপশনে ওষুধ নয়');
  await call(PH, 'rx_order_update', { id: 'demo-rx-order-karim-online', status: 'accepted', price: '৮৫০ টাকা', eta: 'আজ রাত ৮টা' });
  // আগে থেকে থাকা রিপোর্ট দেখার অনুরোধ
  await call(AS, 'review_confirm', { doctorUid: DOC, id: 'demo-rx-lab-me-lipid', ok: true });
  await call(DOC, 'review_done', { id: 'demo-rx-lab-me-lipid', comment: 'কোলেস্টেরল কমছে, ওষুধ চলবে।' });
  await call(DOC, 'review_done', { id: 'demo-rx-lab-salma-tsh', comment: 'TSH বেশি — Thyrox 75 চলবে।' });
  await call(LAB, 'lab_order_status', { id: 'demo-rx-lab-salma-new', status: 'received' });
  await call(LAB, 'lab_report', { id: 'demo-rx-lab-me-cbc', items: [{ test: 'Haemoglobin', result: '13.8', unit: 'g/dL', group: 'CBC (Complete Blood Count)', code: 'cbc', key: 'hb', low: 13, high: 17 }] });
  await call(PT, 'review_pay', { id: 'demo-rx-lab-me-cbc', trxId: 'TEST12345', method: 'bkash' });

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
  const after = (await call(PT, 'providers', { type: 'doctor', district: DIST, homeVisit: false })).items;
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
  const mentions = [...F.store.entries()].filter(([, v]) => [...demoUids].some((u) => JSON.stringify(v).includes(`"${u}"`) || JSON.stringify(v).includes(u)))
    .map(([k]) => k);
  const serialLeft = [...F.store.keys()].filter((k) => k.startsWith(`serials/${DOC}_`));
  const gaps = [...new Set([...mentions, ...serialLeft])].map((k) => k.split('/')[0]).sort();
  // ডাক্তারের লেখা প্রেসক্রিপশন আর এন্ট্রি করা রোগীও এখন মোছে; বাকি থাকে শুধু নতুন দিনের সিরিয়ালের গোনা আর কাজের লগ
  for (const g of gaps) assert.ok(['serials', 'adminLog', 'adminLogs', 'auditLog'].includes(g), `অপ্রত্যাশিত বাকি: ${g}`);
});
