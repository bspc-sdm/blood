// ওয়েবের পুরো যাত্রা (e2e/web) চালাতে গিয়ে পাওয়া সার্ভারের ভুলগুলোর পরীক্ষা
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const R = require(path.join(SERVER, 'lib/rules'));
const { db } = F.firebase;
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

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}

test('bnNumbers: আলাদা সংখ্যা বাংলায়, নামের ভেতরের অঙ্ক নয়', () => {
  assert.strictEqual(R.bnNumbers('ডা. হাসান · 19/10/2026 · #5'), 'ডা. হাসান · ১৯/১০/২০২৬ · #৫');
  assert.strictEqual(R.bnNumbers('HbA1c 7.9 · B12 · Napa 500 mg'), 'HbA1c ৭.৯ · B12 · Napa ৫০০ mg');
});

test('অ্যাপয়েন্টমেন্ট নিশ্চিতের নোটিফিকেশনে তারিখ আর সিরিয়াল বাংলা অঙ্কে (বাংলার ব্যবহারকারী), ইংরেজিতে আগের মতো', async () => {
  assert.strictEqual((await call(OWNER, 'admin_demo_seed')).status, 200);
  const PT = demo.uidOf('patient');
  const DOC = demo.uidOf('doctor');
  const prov = (await db.doc(`providers/${DOC}`).get()).data();
  const ch = prov.chambers[0];
  // কাল থেকে চেম্বার খোলা প্রথম দিন
  const K = require(path.join(SERVER, 'lib/care'));
  let date = R.todayDhakaUtcMidnight() + DAY;
  while (!K.chamberOpenOn(ch, date)) date += DAY;
  const b = await call(PT, 'book', { providerUid: DOC, chamberIndex: 0, date, note: 'পরীক্ষা', chamberKey: ch.hospitalId });
  assert.strictEqual(b.status, 200, JSON.stringify(b.body));
  F.pushes.length = 0;
  const c = await call(DOC, 'appointment_update', { id: b.body.id, op: 'confirm' });
  assert.strictEqual(c.status, 200, JSON.stringify(c.body));
  const p = F.pushes.find((x) => x.uid === PT);
  assert.ok(p, 'রোগী নোটিফিকেশন পান');
  assert.doesNotMatch(p.body, /\d/, p.body);
  assert.match(p.body, /#[০-৯]+/);

  // ইংরেজির ব্যবহারকারী: আগের মতো
  await db.doc(`users/${PT}`).set({ lang: 'en' }, { merge: true });
  const b2 = await call(PT, 'book', { providerUid: DOC, chamberIndex: 0, date: date + 7 * DAY, note: 'পরীক্ষা', chamberKey: ch.hospitalId });
  if (b2.status === 200) {
    F.pushes.length = 0;
    await call(DOC, 'appointment_update', { id: b2.body.id, op: 'confirm' });
    const p2 = F.pushes.find((x) => x.uid === PT);
    assert.match(p2.body, /\d+\/\d+\/\d{4}/);
  }
});

test('ফার্মেসির রেটিংয়ের হিসাব, অনলাইন সেটিং আর ডেমো চিহ্ন অ্যাডমিনের "কী বদলেছে"-তে আসে না, বদল বাতিলে হারায় না', async () => {
  const LR = require(path.join(SERVER, 'lib/listingreview'));
  const base = { name: 'দোকান', phone: '01711000000', licenseNo: 'DL-1', status: 'approved' };
  const x = {
    ...base, lastApproved: LR.snapshot(base),
    aspects: { behaviour: { sum: 9, count: 2 } }, online: { enabled: true, fee: 30 }, demo: true,
  };
  // আগে: aspects/online/demo "বদল" হিসেবে আসত — প্যানেলে "আগে — → এখন [object Object]"
  assert.deepStrictEqual(LR.changedFields(x), []);
  assert.deepStrictEqual(LR.changedFields({ ...x, phone: '01811000000' }).map((c) => c.field), ['phone']);

  // অপেক্ষায় থাকা বদল বাতিল: নাম আগের মতো ফেরে, কিন্তু রেটিংয়ের হিসাব আর অনলাইন সেটিং থাকে
  const id = 'e2e-pharmacy-review';
  await db.doc(`pharmacies/${id}`).set({ ...x, name: 'নতুন নাম', status: 'pending', ownerUid: id });
  const user = { uid: OWNER };
  const r = await LR.resolveListing({ body: { status: 'rejected', reason: 'মিলছে না' }, headers: {} }, user, 'pharmacy', 'pharmacies', id);
  assert.ok(r.restored);
  const after = (await db.doc(`pharmacies/${id}`).get()).data();
  assert.strictEqual(after.name, 'দোকান');
  assert.strictEqual(after.status, 'approved');
  assert.deepStrictEqual(after.aspects, { behaviour: { sum: 9, count: 2 } });
  assert.deepStrictEqual(after.online, { enabled: true, fee: 30 });
  assert.strictEqual(after.demo, true);
});
