// সিরিয়াল চলছে (queue.js): শুরু/পরের রোগী/বাতিল বাদ/চেপে বদল/বিরতি, গড় আর বিকল্প, রোগীর আনুমানিক সময়,
// সহকারী পারেন, অন্যরা পারেন না, কেয়ারগিভার (asUid), দেরির অনুরোধ → ঠিক আছে / পরে আসুন, আগে ২ জনে একবার নোটিফিকেশন,
// ডেমোর আজকের সিরিয়াল, অ্যাকাউন্ট/ডেমো মুছলে সারি থাকে না।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const Q = require(path.join(SERVER, 'lib/queue'));
const R = require(path.join(SERVER, 'lib/rules'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const { db } = F.firebase;
// account.js-এর deleteNow লাগে: recursiveDelete আর ref.parent (demo-rx.test.js-এর মতো)
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

const MIN = 60 * 1000;
const realNow = Date.now;
const TODAY = R.todayDhakaUtcMidnight(realNow());
const T0 = TODAY + 4 * 3600 * 1000; // ঢাকার সকাল ১০টা
let T = T0;
const at = (m) => { T = T0 + m * MIN; };
const hhmm = (ms) => { const d = new Date(ms + 6 * 3600 * 1000); return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`; };

async function raw(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: uid ? { authorization: `Bearer ${uid}` } : {}, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function call(uid, action, body) {
  const r = await raw(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const pushesTo = (uid, re) => F.pushes.filter((p) => p.uid === uid && (!re || re.test(p.title)));

const DOC = 'qDoctor';
const AS = 'qAssistant';
const CG = 'qCaregiver';
const X = 'qStranger';
const P = ['qP1', 'qP2', 'qP3', 'qP4', 'qP5', 'qP6'];
const CH = {
  hospitalId: 'dghs_392', hospitalName: 'Test Medical College Hospital', hospitalNameBn: 'টেস্ট মেডিকেল কলেজ হাসপাতাল', district: DIST,
  upazilaId: null, unionId: null, room: '৩১২', days: [0, 1, 2, 3, 4, 5, 6], start: '09:00', end: '23:00', schedule: '', noFee: false, fee: '',
  appointments: true, serialPhone: '', patientMin: 12,
};

function seed() {
  const s = F.store;
  const u = (uid, email, name) => {
    F.users.set(uid, { uid, email, displayName: name });
    s.set(`users/${uid}`, { name, lang: 'bn', fcmTokens: [`tok_${uid}`] });
  };
  u(DOC, 'doc@q.test', 'ডা. সিরিয়াল');
  u(AS, 'asst@q.test', 'সহকারী');
  u(CG, 'cg@q.test', 'কেয়ারগিভার');
  u(X, 'x@q.test', 'অন্য কেউ');
  P.forEach((p, i) => u(p, `${p}@q.test`, `রোগী ${i + 1}`));
  s.set(`badges/${DOC}`, { type: 'doctor', status: 'approved', name: 'ডা. সিরিয়াল' });
  s.set(`providers/${DOC}`, { type: 'doctor', name: 'ডা. সিরিয়াল', listed: true, appointments: true, specialties: ['medicine'], chambers: [CH], assistantEmails: ['asst@q.test'] });
  s.set(`careLinks/${P[5]}_${CG}`, { patientUid: P[5], caretakerUid: CG, status: 'active' });
}

test('পাতলা হিসাব: গড়, বিকল্প, আনুমানিক সময়', () => {
  const { addGap, avgFor, etaFor, numbers, order, placeOf } = Q._q;
  const now = 1_000_000_000_000;
  // চলমান গড়
  let st = addGap(null, 8, now);
  assert.deepStrictEqual([st.avg, st.n], [8, 1]);
  st = addGap(st, 12, now);
  assert.deepStrictEqual([st.avg, st.n], [10, 2]);
  // ৩০ দিন পুরোনো হলে নতুন করে
  assert.deepStrictEqual(addGap({ avg: 30, n: 50, at: now - 31 * 86400000 }, 6, now).n, 1);
  // যথেষ্ট হিসাব না থাকলে চেম্বারের সেটিং, তাও না থাকলে ১০
  assert.deepStrictEqual(avgFor({ queueStats: { k: { avg: 7.6, n: 5, at: now } } }, 'k', { patientMin: 12 }, now), { avgMin: 8, avgFrom: 'history' });
  assert.deepStrictEqual(avgFor({ queueStats: { k: { avg: 7.6, n: 2, at: now } } }, 'k', { patientMin: 12 }, now), { avgMin: 12, avgFrom: 'setting' });
  assert.deepStrictEqual(avgFor({}, 'k', {}, now), { avgMin: 10, avgFrom: 'default' });
  // নম্বরের ফাঁক (ফোনে নেওয়া রোগী) বাদ যায় না, বাতিল আর "পরে আসুন" বাদ
  const nums = numbers([
    { serial: 1, status: 'confirmed' }, { serial: 3, status: 'cancelled' }, { serial: 4, status: 'confirmed', lateStatus: 'later' }, { serial: 6, status: 'confirmed' },
  ], 6);
  assert.deepStrictEqual(order({ started: false }, nums), [1, 2, 5, 6, 4]);
  assert.deepStrictEqual(placeOf({ started: true, current: 2, pos: 2, done: [1] }, nums, 6), { state: 'waiting', ahead: 2 }, 'এখন ২, তারপর ৫');
  assert.deepStrictEqual(placeOf({ started: true, current: 2, pos: 2, done: [1] }, nums, 4), { state: 'waiting', ahead: 3 }, 'শেষে রাখা ৪');
  // উদাহরণ: এখন ৫, আপনার ৯ → আগে ৪ জন; গড় ৮ → আনুমানিক = বর্তমানের শেষ + ৩×৮
  const q = { started: true, current: 5, lastAt: now, paused: false };
  const e = etaFor(q, { state: 'waiting', ahead: 4 }, 8, null, now);
  assert.strictEqual(e.eta, Math.ceil((now + 32 * MIN) / (5 * MIN)) * 5 * MIN);
  assert.strictEqual(e.arriveBy, Math.floor((e.eta - 20 * MIN) / (5 * MIN)) * 5 * MIN);
});

test('সিরিয়াল চলছে: ডাক্তার/সহকারী, রোগী, কেয়ারগিভার, দেরি', async () => {
  seed();
  Date.now = () => T;
  try {
    at(0);
    // ছয়জন রোগী আজ বুক করেন, ডাক্তার নিশ্চিত করেন → সিরিয়াল ১–৬; ৩ নম্বর পরে বাতিল
    const ids = [];
    for (const p of P) {
      const { id } = await call(p, 'book', { providerUid: DOC, date: TODAY, chamberIndex: 0, note: 'জ্বর' });
      await call(DOC, 'appointment_update', { id, op: 'confirm' });
      ids.push(id);
    }
    assert.deepStrictEqual(ids.map((id) => F.store.get(`appointments/${id}`).serial), [1, 2, 3, 4, 5, 6]);
    await call(P[2], 'appointment_update', { id: ids[2], op: 'cancel' });

    // ---------- শুরুর আগে ----------
    let v = await call(P[4], 'appointment_queue', { id: ids[4] });
    assert.strictEqual(v.live, true);
    assert.strictEqual(v.started, false);
    assert.strictEqual(v.ahead, 3, '১, ২, ৪ (৩ বাতিল)');
    assert.deepStrictEqual([v.avgMin, v.avgFrom], [12, 'setting']);
    // চেম্বার ৯টায় শুরু, এখন ১০টা → এখন থেকে ৩×১২ = ১০:৩৬ → ১০:৪০; আসবেন ২৪ মিনিট আগে → ১০:১৫
    assert.strictEqual(hhmm(v.eta), '10:40');
    assert.strictEqual(hhmm(v.arriveBy), '10:15');
    // অন্যের অ্যাপয়েন্টমেন্ট দেখা যায় না
    assert.strictEqual((await raw(X, 'appointment_queue', { id: ids[4] })).status, 404);

    // ---------- কে পারেন ----------
    assert.strictEqual((await raw(X, 'queue_today', { doctorUid: DOC })).status, 403, 'অন্য কেউ নন');
    assert.strictEqual((await raw(P[0], 'queue_today')).status, 403, 'রোগী নিজে ডাক্তার নন');
    assert.strictEqual((await raw(P[0], 'queue_control', { chamberKey: CH.hospitalId, op: 'start' })).status, 403);
    assert.strictEqual((await raw(X, 'queue_control', { doctorUid: DOC, chamberKey: CH.hospitalId, op: 'start' })).status, 403);
    // কেয়ারগিভার রোগীর হয়ে সারি চালাতে পারেন না
    assert.strictEqual((await raw(CG, 'queue_control', { asUid: P[5], doctorUid: DOC, chamberKey: CH.hospitalId, op: 'start' })).status, 403);
    const tdA = await call(AS, 'queue_today', { doctorUid: DOC });
    assert.strictEqual(tdA.role, 'assistant');
    assert.strictEqual(tdA.chambers.length, 1);
    const chamberKey = tdA.chambers[0].chamberKey;
    assert.strictEqual(chamberKey, CH.hospitalId);
    assert.deepStrictEqual(tdA.chambers[0].rows.map((r) => [r.serial, r.state]), [[1, 'waiting'], [2, 'waiting'], [3, 'cancelled'], [4, 'waiting'], [5, 'waiting'], [6, 'waiting']]);

    // ---------- শুরু (ডাক্তার) ----------
    let c = (await call(DOC, 'queue_control', { chamberKey, op: 'start' })).chamber;
    assert.deepStrictEqual([c.queue.started, c.queue.current, c.queue.next], [true, 1, 2]);
    // দুবার চাপলে কিছু বদলায় না
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'start' })).chamber;
    assert.strictEqual(c.queue.current, 1);
    // আগে ১–২ জন: ২ নম্বর (আগে ১) আর ৪ নম্বর (আগে ২) নোটিফিকেশন পান
    assert.strictEqual(pushesTo(P[1], /আগে ১ জন/).length, 1);
    assert.strictEqual(pushesTo(P[3], /আগে ২ জন — চেম্বারে চলে আসুন/).length, 1);
    assert.strictEqual(pushesTo(P[4], /আগে/).length, 0);

    // ---------- দেরি: ৪ নম্বর ২০ মিনিট ----------
    assert.strictEqual((await raw(P[3], 'appointment_late', { id: ids[3], minutes: 15 })).status, 400, 'শুধু ১০/২০/৩০/৬০');
    assert.strictEqual((await raw(X, 'appointment_late', { id: ids[3], minutes: 20 })).status, 404, 'অন্যের নয়');
    await call(P[3], 'appointment_late', { id: ids[3], minutes: 20, note: 'জ্যামে আছি' });
    assert.ok(pushesTo(DOC, /২০ মিনিট দেরিতে/).length === 1, 'ডাক্তার জানেন');
    let row4 = (await call(AS, 'queue_today', { doctorUid: DOC })).chambers[0].rows.find((r) => r.serial === 4);
    assert.deepStrictEqual([row4.late.min, row4.late.status, row4.late.note], [20, 'asked', 'জ্যামে আছি']);
    // আবার বদলানো যায় (৩০ মিনিট)
    await call(P[3], 'appointment_late', { id: ids[3], minutes: 30 });
    // অন্য কেউ উত্তর দিতে পারেন না; সহকারী "পরে আসুন" চাপেন
    assert.strictEqual((await raw(X, 'appointment_late_reply', { id: ids[3], op: 'later' })).status, 403);
    const before5 = pushesTo(P[4], /আগে/).length;
    c = (await call(AS, 'appointment_late_reply', { id: ids[3], op: 'later' })).chamber;
    assert.strictEqual(F.store.get(`appointments/${ids[3]}`).lateStatus, 'later');
    assert.ok(pushesTo(P[3], /পরে আসুন/).length === 1, 'রোগী উত্তর পান');
    assert.deepStrictEqual(c.rows.find((r) => r.serial === 4).state, 'later');
    // ক্রম: ২, ৫, ৬, তারপর ৪ (অন্যদের নম্বর বদলায় না)
    assert.deepStrictEqual(Q._q.order(F.store.get(`queues/${DOC}_${TODAY}_${Q.keyOf(chamberKey)}`), Q._q.numbers(Object.values(Object.fromEntries([...F.store].filter(([k]) => k.startsWith('appointments/')))), 6)), [2, 5, 6, 4]);
    // ৫ নম্বর এখন আগে ২ জন → নোটিফিকেশন
    assert.strictEqual(pushesTo(P[4], /আগে ২ জন/).length, before5 + 1);
    // শেষে রাখার পর আর সময় চাওয়া যায় না
    assert.strictEqual((await raw(P[3], 'appointment_late', { id: ids[3], minutes: 10 })).status, 409);
    v = await call(P[3], 'appointment_queue', { id: ids[3] });
    assert.deepStrictEqual([v.later, v.canLate, v.ahead], [true, false, 4]);

    // ---------- কেয়ারগিভার: ৬ নম্বরের হয়ে দেখা আর সময় চাওয়া; ডাক্তার "ঠিক আছে" ----------
    v = await call(CG, 'appointment_queue', { asUid: P[5], id: ids[5] });
    assert.deepStrictEqual([v.serial, v.current, v.ahead], [6, 1, 3]);
    await call(CG, 'appointment_late', { asUid: P[5], id: ids[5], minutes: 10 });
    assert.strictEqual(F.store.get(`appointments/${ids[5]}`).lateStatus, 'asked');
    await call(DOC, 'appointment_late_reply', { id: ids[5], op: 'ok' });
    assert.strictEqual(F.store.get(`appointments/${ids[5]}`).lateStatus, 'ok');
    assert.ok(pushesTo(P[5], /ঠিক আছে/).length === 1);
    assert.ok(F.pushes.some((p) => p.uid === CG && /ঠিক আছে/.test(p.title)), 'কেয়ারগিভারও পান');

    // ---------- পরের রোগী (সহকারী), ৮ মিনিট পর ----------
    at(8);
    c = (await call(AS, 'queue_control', { doctorUid: DOC, chamberKey, op: 'next' })).chamber;
    assert.strictEqual(c.queue.current, 2);
    let stats = F.store.get(`providers/${DOC}`).queueStats[Q.keyOf(chamberKey)];
    assert.deepStrictEqual([stats.avg, stats.n], [8, 1]);
    // ৬ নম্বর এখন আগে ২ জন (২, ৫) → নোটিফিকেশন (রোগী আর কেয়ারগিভার)
    assert.strictEqual(pushesTo(P[5], /আগে ২ জন/).length, 1);
    // আনুমানিক: গড় এখনো সেটিং (১২) — বর্তমান ২ নম্বর ১০:২০ পর্যন্ত, ৫ নম্বর তখন; ৬ নম্বর ১০:৩২ → ১০:৩৫
    v = await call(P[4], 'appointment_queue', { id: ids[4] });
    assert.deepStrictEqual([v.current, v.ahead, v.avgMin], [2, 1, 12]);
    assert.strictEqual(hhmm(v.eta), '10:20');
    assert.strictEqual(hhmm(v.arriveBy), '09:55');
    v = await call(P[5], 'appointment_queue', { id: ids[5] });
    assert.strictEqual(hhmm(v.eta), '10:35');
    assert.strictEqual(hhmm(v.arriveBy), '10:10');

    // ---------- বিরতি ----------
    at(10);
    await call(DOC, 'queue_control', { chamberKey, op: 'pause' });
    v = await call(P[4], 'appointment_queue', { id: ids[4] });
    assert.strictEqual(v.paused, true);
    at(40);
    await call(DOC, 'queue_control', { chamberKey, op: 'resume' });
    // ৫০ মিনিটে পরের রোগী: ফাঁক ৪২ মিনিট − ৩০ মিনিট বিরতি = ১২ → গড় ১০
    at(50);
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'next' })).chamber;
    assert.strictEqual(c.queue.current, 5, '৩ বাতিল, ৪ শেষে');
    stats = F.store.get(`providers/${DOC}`).queueStats[Q.keyOf(chamberKey)];
    assert.deepStrictEqual([stats.avg, stats.n], [10, 2]);
    // নিজের পালা চলে এলে আর সময় চাওয়া যায় না
    assert.strictEqual((await raw(P[4], 'appointment_late', { id: ids[4], minutes: 10 })).status, 409);
    v = await call(P[4], 'appointment_queue', { id: ids[4] });
    assert.strictEqual(v.state, 'now');

    at(60);
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'next' })).chamber;
    assert.strictEqual(c.queue.current, 6);
    v = await call(P[3], 'appointment_queue', { id: ids[3] });
    assert.deepStrictEqual([v.ahead, v.avgMin, v.avgFrom], [1, 10, 'history'], 'তিনটা ফাঁকের পর আগের হিসাব');
    // শেষে রাখা ৪ নম্বরের আবার নোটিফিকেশন (আগে ১ জন) — একবারই
    assert.strictEqual(pushesTo(P[3], /আগে/).length, 2);

    at(70);
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'next' })).chamber;
    assert.strictEqual(c.queue.current, 4, 'সবার পরে শেষে রাখা রোগী');
    at(75);
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'next' })).chamber;
    assert.deepStrictEqual([c.queue.current, c.queue.finished, c.queue.next], [0, true, null]);
    v = await call(P[3], 'appointment_queue', { id: ids[3] });
    assert.strictEqual(v.state, 'done');
    // প্রত্যেকে "আগে ২ জন"-এর নোটিফিকেশন একবারের বেশি নয় (শেষে সরালে আবার একবার)
    for (const p of [P[1], P[4], P[5]]) assert.ok(pushesTo(p, /আগে/).length <= 1, p);

    // ---------- চেপে বদল: ২ নম্বরকে আবার; ৬০ মিনিটের বেশি ফাঁক গড়ে ধরে না ----------
    at(200);
    c = (await call(AS, 'queue_control', { doctorUid: DOC, chamberKey, op: 'set', appointmentId: ids[1] })).chamber;
    assert.strictEqual(c.queue.current, 2);
    assert.strictEqual(F.store.get(`providers/${DOC}`).queueStats[Q.keyOf(chamberKey)].n, 4, 'শুধু আগের চারটা ফাঁক (৮, ১২, ১০, ১০)');
    assert.strictEqual((await raw(DOC, 'queue_control', { chamberKey, op: 'set', appointmentId: ids[2] })).status, 409, 'বাতিলটা নয়');
    // ফোনে নেওয়া রোগীর নম্বর (অ্যাপে নেই) চেপেও বসানো যায়
    c = (await call(DOC, 'queue_control', { chamberKey, op: 'set', serial: 9 })).chamber;
    assert.strictEqual(c.queue.current, 9);
  } finally {
    Date.now = realNow;
  }

  // ---------- অ্যাকাউন্ট মুছলে ডাক্তারের সারি থাকে না ----------
  assert.ok([...F.store.keys()].some((k) => k.startsWith(`queues/${DOC}_`)));
  await require(path.join(SERVER, 'lib/account')).deletion.deleteNow(DOC, 'doc@q.test');
  assert.deepStrictEqual([...F.store.keys()].filter((k) => k.startsWith('queues/')), []);
});

test('ডেমো: আজকের সিরিয়াল চলছে, এক রোগী সময় চেয়েছেন; মালিকের নতুন সিরিয়ালে সারির তথ্য; মোছা', async () => {
  F.store.clear();
  F.pushes.length = 0;
  const OWNER = 'owner-queue-test';
  F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
  const demo = require(path.join(SERVER, 'lib/demo'));
  await call(OWNER, 'admin_demo_seed');
  const DD = demo.uidOf('doctor');
  const AA = demo.uidOf('assistant');
  const PT = demo.uidOf('patient');
  const today = R.todayDhakaUtcMidnight();

  const td = await call(AA, 'queue_today', { doctorUid: DD });
  const ch = td.chambers.find((x) => x.queue.started);
  assert.ok(ch, 'আজ সিরিয়াল চলছে');
  assert.strictEqual(ch.queue.current, 2);
  assert.deepStrictEqual([ch.avgMin, ch.avgFrom], [8, 'history']);
  assert.strictEqual(ch.lateAsked, 1);
  const late = ch.rows.find((r) => r.late);
  assert.deepStrictEqual([late.serial, late.late.min, late.late.status], [4, 20, 'asked']);

  // J-01..J-03: মালিক (ডেমো রোগী) আজ সিরিয়াল নেন, ডাক্তার নিশ্চিত করেন → ৫ নম্বর, আগে ৩ জন (২, ৩, ৪)
  const prov = (await call(PT, 'providers', { type: 'doctor', district: DIST, homeVisit: false })).items.find((p) => p.uid === DD);
  const ci = prov.chambers.findIndex((c) => (c.hospitalId || c.hospitalName) === ch.chamberKey);
  const { id } = await call(PT, 'book', { providerUid: DD, date: today, chamberIndex: ci, note: 'মাথা ঘোরে' });
  assert.strictEqual((await call(PT, 'appointment_queue', { id })).live, false, 'নিশ্চিত হওয়ার আগে নয়');
  await call(DD, 'appointment_update', { id, op: 'confirm' });
  const v = await call(PT, 'appointment_queue', { id });
  assert.deepStrictEqual([v.live, v.serial, v.current, v.ahead, v.avgMin, v.canLate], [true, 5, 2, 3, 8, true]);
  assert.ok(v.eta > Date.now() && v.arriveBy < v.eta);
  // ডাক্তার পরের রোগী চাপলে মালিকের আগে ২ জন → নোটিফিকেশন
  await call(DD, 'queue_control', { chamberKey: ch.chamberKey, op: 'next' });
  assert.ok(F.pushes.some((p) => p.uid === PT && /আগে ২ জন/.test(p.title)));
  // ডাক্তার ৪ নম্বরের অনুরোধে "ঠিক আছে"
  await call(DD, 'appointment_late_reply', { id: late.id, op: 'ok' });

  await call(OWNER, 'admin_demo_wipe');
  assert.deepStrictEqual([...F.store.keys()].filter((k) => k.startsWith('queues/')), [], 'ডেমো মুছলে সারি নেই');
});

// ---------------------------------------------------------------
// রিভিউয়ের ভুলগুলো আর হয় না
// ---------------------------------------------------------------
const DAYMS = 24 * 3600 * 1000;
function person(uid) {
  F.users.set(uid, { uid, email: `${uid}@q.test`, displayName: uid });
  F.store.set(`users/${uid}`, { name: uid, lang: 'bn', fcmTokens: [`tok_${uid}`] });
}
/** প্রোফাইলের ডক ছাড়া ডাক্তার (শুধু ব্যাজ) আর সরাসরি লেখা নিশ্চিত সিরিয়াল */
function seedDoctor(uid, { profile = false } = {}) {
  person(uid);
  F.store.set(`badges/${uid}`, { type: 'doctor', status: 'approved', name: uid });
  if (profile) F.store.set(`providers/${uid}`, { type: 'doctor', name: uid, chambers: [] });
}
function seedAppt(id, doc, patient, date, serial, chamberKey) {
  person(patient);
  F.store.set(`appointments/${id}`, {
    providerUid: doc, patientUid: patient, date, serial, status: 'confirmed', chamberKey, chamber: 'Night Clinic', chamberBn: 'রাতের চেম্বার',
    providerName: 'ডা. রাত', patientName: patient, start: '20:00', createdAt: serial,
  });
}

test('দুজন একসাথে "পরের রোগী": পুরোনো নম্বর দেখে চাপলে কিছু হয় না (stale); প্রোফাইল ডক না থাকলেও গড় লেখা যায়', async () => {
  const D = 'qStaleDoc';
  seedDoctor(D); // providers/{uid} নেই
  const KEY = 'dghs_901';
  for (let s = 1; s <= 4; s++) seedAppt(`qStale${s}`, D, `qStaleP${s}`, TODAY, s, KEY);
  Date.now = () => T;
  try {
    at(0);
    let r = await call(D, 'queue_control', { chamberKey: KEY, op: 'start' });
    assert.strictEqual(r.chamber.queue.current, 1);
    at(6);
    // ডাক্তার আর সহকারী দুজনেই ১ নম্বর দেখে "পরের রোগী" — প্রথমটায় ২, দ্বিতীয়টায় কিছু নয় (৩ নয়)
    r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 1 });
    assert.deepStrictEqual([r.chamber.queue.current, r.stale], [2, undefined]);
    r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 1 });
    assert.deepStrictEqual([r.stale, r.chamber.queue.current, r.chamber.queue.next], [true, 2, 3], 'কেউ বাদ পড়েননি');
    const q = F.store.get(`queues/${D}_${TODAY}_${Q.keyOf(KEY)}`);
    assert.deepStrictEqual([q.current, q.done], [2, [1]]);
    // "এখন দেখছি"-তেও একই
    r = await call(D, 'queue_control', { chamberKey: KEY, op: 'set', serial: 4, expect: 1 });
    assert.deepStrictEqual([r.stale, r.chamber.queue.current], [true, 2]);
    // প্রোফাইলের ডক ছিল না — "পরের রোগী" আটকায়নি, গড় merge করে লেখা
    assert.deepStrictEqual(F.store.get(`providers/${D}`).queueStats[Q.keyOf(KEY)].n, 1);
    // queue_control-এ অ্যাপয়েন্টমেন্ট একবারই পড়া হয় (আগে দুবার load())
    let reads = 0;
    const orig = db.collection;
    db.collection = (c) => { if (c === 'appointments') reads++; return orig(c); };
    try {
      at(12);
      r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 2 });
    } finally {
      db.collection = orig;
    }
    assert.deepStrictEqual([r.chamber.queue.current, reads], [3, 1]);
    assert.strictEqual(F.store.get(`providers/${D}`).queueStats[Q.keyOf(KEY)].n, 2, 'ফেরত দেখায়ও নতুন গড়');
  } finally {
    Date.now = realNow;
  }
});

test('"আগে ২ জন" নোটিফিকেশন একসাথে দুটো অনুরোধেও একবার', async () => {
  const D = 'qNearDoc';
  seedDoctor(D, { profile: true });
  const KEY = 'dghs_902';
  for (let s = 1; s <= 3; s++) seedAppt(`qNear${s}`, D, `qNearP${s}`, TODAY, s, KEY);
  F.store.set(`queues/${D}_${TODAY}_${Q.keyOf(KEY)}`, { providerUid: D, date: TODAY, chamberKey: KEY, key: Q.keyOf(KEY), started: true, current: 1, pos: 1, done: [] });
  const L1 = await Q._q.load(D, TODAY, KEY);
  const L2 = await Q._q.load(D, TODAY, KEY);
  const sent = await Promise.all([Q._q.notifyNear(L1), Q._q.notifyNear(L2)]);
  assert.strictEqual(sent[0] + sent[1], 2, 'দুজন রোগী, প্রত্যেকে একবার');
  assert.strictEqual(pushesTo('qNearP2', /আগে/).length, 1);
  assert.strictEqual(pushesTo('qNearP3', /আগে/).length, 1);
});

test('রোগীর খোঁজ ১৫ সেকেন্ড মেমোরিতে; সারি/অ্যাপয়েন্টমেন্ট বদলালে সাথে সাথে নতুন', async () => {
  const D = 'qCacheDoc';
  seedDoctor(D, { profile: true });
  const KEY = 'dghs_903';
  for (let s = 1; s <= 4; s++) seedAppt(`qCache${s}`, D, `qCacheP${s}`, TODAY, s, KEY);
  Date.now = () => T;
  try {
    at(0);
    let v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
    assert.strictEqual(v.ahead, 3);
    let reads = 0;
    const orig = db.collection;
    db.collection = (c) => { if (c === 'appointments') reads++; return orig(c); };
    try {
      // মেমোরি থেকে: ডাটাবেসে সরাসরি বদল (যেমন অন্য সার্ভার) এখনই দেখায় না, অ্যাপয়েন্টমেন্টের তালিকাও আবার পড়া হয় না
      F.store.set('appointments/qCache1', { ...F.store.get('appointments/qCache1'), status: 'cancelled' });
      v = await call('qCacheP3', 'appointment_queue', { id: 'qCache3' });
      assert.strictEqual(v.ahead, 2);
      v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
      assert.strictEqual(v.ahead, 3);
      assert.strictEqual(reads, 0);
      // ১৫ সেকেন্ড পর নতুন করে
      T += 16 * 1000;
      v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
      assert.strictEqual(v.ahead, 2);
      assert.strictEqual(reads, 1);
    } finally {
      db.collection = orig;
    }
    // অ্যাপ দিয়ে বাতিল → সাথে সাথে
    await call('qCacheP2', 'appointment_update', { id: 'qCache2', op: 'cancel' });
    v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
    assert.strictEqual(v.ahead, 1);
    // সারি চালালে → সাথে সাথে
    await call(D, 'queue_control', { chamberKey: KEY, op: 'start' });
    v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
    assert.deepStrictEqual([v.started, v.current, v.ahead], [true, 3, 1]);
    await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 3 });
    v = await call('qCacheP4', 'appointment_queue', { id: 'qCache4' });
    assert.strictEqual(v.state, 'now');
  } finally {
    Date.now = realNow;
  }
});

test('মাঝরাত পেরোনো চেম্বার: ভোর ৬টা পর্যন্ত গতকালের চলমান সারি — ডাক্তার, রোগী, দেরি; শেষ হলে আজকেরটা নিজে থেকে শুরু হয় না', async () => {
  const D = 'qNightDoc';
  seedDoctor(D, { profile: true });
  const KEY = 'dghs_904';
  const YEST = TODAY - DAYMS;
  for (let s = 1; s <= 3; s++) seedAppt(`qNight${s}`, D, `qNightP${s}`, YEST, s, KEY);
  seedAppt('qNightToday', D, 'qNightP9', TODAY, 1, KEY); // একই চেম্বারে আজকের সিরিয়াল
  let now = YEST + 16 * 3600 * 1000; // গতকাল রাত ১০টা (ঢাকা)
  Date.now = () => now;
  try {
    assert.strictEqual(Q._q.earlyHours(now), false);
    await call(D, 'queue_control', { chamberKey: KEY, op: 'start' });
    now += 10 * MIN;
    await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 1 });
    // রাত ১২:৩০ (আজকের তারিখ)
    now = TODAY - 6 * 3600 * 1000 + 30 * MIN;
    assert.strictEqual(R.todayDhakaUtcMidnight(now), TODAY);
    assert.strictEqual(Q._q.earlyHours(now), true);
    let td = await call(D, 'queue_today');
    assert.deepStrictEqual(td.chambers.map((c) => [c.date, c.queue.current, c.chamberKey]), [[YEST, 2, KEY]], 'গতকালেরটাই, আজকেরটা পরে');
    // রোগী: গতকালের অ্যাপয়েন্টমেন্টেও সারি দেখেন আর সময় চাইতে পারেন
    let v = await call('qNightP3', 'appointment_queue', { id: 'qNight3' });
    assert.deepStrictEqual([v.live, v.current, v.ahead], [true, 2, 1]);
    await call('qNightP3', 'appointment_late', { id: 'qNight3', minutes: 10 });
    await call(D, 'appointment_late_reply', { id: 'qNight3', op: 'ok' });
    // পুরোনো অ্যাপ (date ছাড়া) আর নতুন অ্যাপ (date সহ) — দুটোই গতকালেরটা চালায়
    let r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 2 });
    assert.strictEqual(r.chamber.queue.current, 3);
    assert.strictEqual(r.chamber.date, YEST);
    r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 3, date: YEST });
    assert.strictEqual(r.chamber.queue.finished, true);
    // শেষের পর আবার চাপলেও আজকের সারি শুরু হয় না
    r = await call(D, 'queue_control', { chamberKey: KEY, op: 'next', expect: 0, date: YEST });
    assert.deepStrictEqual([r.chamber.date, r.chamber.queue.finished], [YEST, true]);
    assert.strictEqual(F.store.get(`queues/${D}_${TODAY}_${Q.keyOf(KEY)}`), undefined);
    td = await call(D, 'queue_today');
    assert.deepStrictEqual(td.chambers.map((c) => [c.date, c.queue.started]), [[TODAY, false]], 'গতকালেরটা শেষ — এখন আজকেরটা');
    v = await call('qNightP3', 'appointment_queue', { id: 'qNight3' });
    assert.strictEqual(v.state, 'done');
    // সকাল ৭টায় গতকালের কিছু নয়
    now = TODAY + 3600 * 1000;
    v = await call('qNightP3', 'appointment_queue', { id: 'qNight3' });
    assert.strictEqual(v.live, false);
  } finally {
    Date.now = realNow;
  }
});

test('অ্যাকাউন্ট মুছলে ১০০০-এর বেশি দিনের সারিও মোছে', async () => {
  const D = 'qManyDoc';
  for (let i = 0; i < 1100; i++) F.store.set(`queues/${D}_${i}_k`, { providerUid: D, date: i, started: true });
  F.store.set('queues/other_1_k', { providerUid: 'other', date: 1 });
  assert.strictEqual(await Q.deleteFor(D), 1100);
  assert.deepStrictEqual([...F.store.keys()].filter((k) => k.startsWith(`queues/${D}_`)), []);
  assert.ok(F.store.has('queues/other_1_k'));
});
