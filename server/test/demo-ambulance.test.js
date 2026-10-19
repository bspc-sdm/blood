// ডেমো: অ্যাম্বুলেন্সের পুরো পথ — প্যানেলের মতো ধাপে ধাপে ডেমো বানিয়ে, রোগী, চালক আর মালিক — আসল কাজ দিয়ে।
// (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const verify = require(path.join(SERVER, 'lib/verify'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur').bn;
const { db } = F.firebase;
const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.6.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const clearCache = () => require(path.join(SERVER, 'lib/memcache')).clear();

const AMB = 'demo-ambulance';
const P = 'demo-patient';
const LOG = process.env.AMB_LOG ? (...a) => console.log('  ✓', ...a) : () => {};

test('অ্যাম্বুলেন্স: খোঁজা, ফোন, রেটিং, চালকের কাজ, নতুন গাড়ি, মালিকের অনুমোদন — আসল কাজ দিয়ে', async () => {
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  LOG('ডেমো বানানো (ধাপে ধাপে)');

  // ---- অতিথি: তালিকা আর ধরন দিয়ে ছাঁকা ----
  const L = async (uid, body) => { const r = await ok(uid, 'ambulances', body); return [...r.items, ...(r.busy || [])]; };
  const all = await L(null, { district: DISTRICT });
  const names = (xs) => xs.map((x) => `${x.name}${x.busy ? ' (ট্রিপে)' : x.available ? ' (খালি)' : ''}`).join(', ');
  LOG(`অতিথির তালিকা: ${all.length}টা — ${names(all)}`);
  assert.ok(all.some((x) => x.name.includes('ডেমো জীবন অ্যাম্বুলেন্স (এসি)') && x.available), 'এসি অ্যাম্বুলেন্স খালি');
  assert.ok(all.some((x) => x.name.includes('নন-এসি') && x.busy), 'নন-এসি ট্রিপে');
  assert.ok(!all.some((x) => x.id === 'demo-svc-amb-icu-new'), 'অনুমোদনের অপেক্ষার আইসিইউ তালিকায় নেই');
  assert.ok(all.every((x) => !x.canRate), 'অতিথি রেটিং দিতে পারেন না');
  const icu = await L(null, { district: DISTRICT, type: 'icu' });
  assert.ok(icu.length >= 1 && icu.every((x) => (x.types || []).includes('icu')));
  LOG(`"আইসিইউ" দিয়ে ছাঁকলে ${icu.length}টা — ${names(icu)}`);

  // ---- রোগী: ফোন আর রেটিং ----
  const pl = await L(P, { district: DISTRICT });
  const rateable = pl.filter((x) => x.canRate);
  LOG(`রোগী লগইন করে দেখেন: রেটিং দেওয়া যায় ${rateable.length}টায় (৩ ঘণ্টা আগে ফোন করা) — ${names(rateable)}`);
  assert.ok(rateable.some((x) => x.id === 'demo-svc-amb-icu'));
  const fresh = pl.find((x) => !x.canRate && !x.busy);
  await ok(P, 'ambulance_called', { id: fresh.id });
  assert.strictEqual((await call(P, 'ambulance_rate', { id: fresh.id, rating: 5 })).status, 403, 'ফোনের ১ ঘণ্টার আগে রেটিং নয়');
  LOG(`রোগী "${fresh.name}"-এ প্রথমবার ফোন করলেন → সাথে সাথে রেটিং দেওয়া যায় না ("প্রথম ফোনের ১ ঘণ্টা পর")`);
  const before = (await L(P, { district: DISTRICT })).find((x) => x.id === 'demo-svc-amb-icu');
  await ok(P, 'ambulance_rate', { id: 'demo-svc-amb-icu', rating: 4, comment: 'সময়মতো এসেছে' });
  clearCache();
  const after = (await L(P, { district: DISTRICT })).find((x) => x.id === 'demo-svc-amb-icu');
  LOG(`৩ ঘণ্টা আগে ফোন করা আইসিইউকে ৪ তারা দিলেন: রেটিং ${before.rating ?? '-'} (${before.ratingCount ?? 0}) → ${after.rating ?? '-'} (${after.ratingCount ?? 0})`);
  assert.ok((after.ratingCount || 0) >= (before.ratingCount || 0));

  // ---- চালক (অ্যাম্বুলেন্সের মালিক): নিজের গাড়ি, খালি/ট্রিপে, হিসাব ----
  const mine = (await ok(AMB, 'ambulance_mine')).items;
  LOG(`চালকের গাড়ি ${mine.length}টা — ${mine.map((x) => `${x.name} / ${x.status}`).join(', ')}`);
  assert.ok(mine.length >= 3);
  await ok(AMB, 'ambulance_available', { id: 'demo-svc-amb-nonac', available: true });
  await ok(AMB, 'ambulance_available', { id: 'demo-svc-amb-ac', available: false });
  clearCache();
  const l2 = await L(null, { district: DISTRICT });
  assert.ok(l2.find((x) => x.id === 'demo-svc-amb-nonac').available);
  assert.ok(l2.find((x) => x.id === 'demo-svc-amb-ac').busy);
  LOG('চালক নন-এসি "খালি" আর এসি "ট্রিপে" করলেন → তালিকায় সাথে সাথে বদলায়');
  const st = await ok(AMB, 'ambulance_stats');
  LOG('চালকের হিসাব:', JSON.stringify(st).slice(0, 160));

  // ---- চালক: তথ্য বদলানো (ভাড়া → সরাসরি; ফোন বদলালে আবার যাচাই) ----
  const ac = mine.find((x) => x.id === 'demo-svc-amb-ac');
  const base = { id: ac.id, name: ac.name, phone: ac.phone, vehicleNo: ac.vehicleNo, types: ac.types, district: ac.district, upazilaId: ac.upazilaId, area: ac.area, termsAccepted: true };
  const f1 = await ok(AMB, 'ambulance_save', { ...base, fare: 'ঢাকা ৭০০০ টাকা' });
  assert.strictEqual(f1.pending, false, 'ভাড়া বদলালে যাচাই লাগে না');
  const f2 = await ok(AMB, 'ambulance_save', { ...base, phone: '01700000188' });
  LOG(`চালক ভাড়া বদলালেন → সরাসরি; ফোন বদলালেন → ${f2.pending ? 'আবার যাচাইয়ের অপেক্ষায়' : 'সরাসরি'}`);

  // ---- চালক নতুন গাড়ি যোগ → মালিকের অনুমোদন ----
  const nw = await call(AMB, 'ambulance_save', { name: 'ডেমো জীবন ফ্রিজার ভ্যান', phone: '01700000189', vehicleNo: 'গাজীপুর-ছ ১১-২২৩৩', types: ['freezer'], district: DISTRICT, upazilaId: 320, area: 'শিববাড়ি', fare: 'ফোনে জেনে নিন', termsAccepted: true });
  assert.strictEqual(nw.body.code, 'docs_required', 'কাগজের ছবি ছাড়া নতুন গাড়ি নয়');
  LOG('চালক কাগজের ছবি ছাড়া নতুন গাড়ি যোগ করতে চাইলে বলে:', nw.body.error);
  const pend = (await ok(OWNER, 'admin_ambulances', { status: 'pending' })).items;
  LOG(`মালিকের অপেক্ষার তালিকা: ${pend.map((x) => x.name).join(', ')}`);
  const icuNew = pend.find((x) => x.id === 'demo-svc-amb-icu-new');
  assert.ok(icuNew);
  assert.strictEqual((await call(OWNER, 'admin_ambulance_resolve', { id: icuNew.id, approve: true })).status, 400, 'টিক ছাড়া নয়');
  await ok(OWNER, 'admin_ambulance_resolve', { id: icuNew.id, status: 'approved', checks: verify.CHECKS.ambulance().map((c) => c.id) });
  clearCache();
  assert.ok((await L(P, { district: DISTRICT })).some((x) => x.id === icuNew.id));
  LOG('মালিক কাগজ দেখে টিক দিয়ে আইসিইউ অনুমোদন দিলেন → তালিকায় এলো');

  // ---- রিপোর্ট ----
  const rep = await call(P, 'report', { kind: 'ambulance', id: 'demo-svc-amb-icu', reason: 'ভাড়া বেশি চেয়েছে' });
  LOG('রোগী আইসিইউর বিরুদ্ধে রিপোর্ট দিলেন:', rep.status);
  assert.strictEqual(rep.status, 200);

  // ---- ডেমো আর আসল আলাদা ----
  const REAL = 'real-user-0003';
  F.users.set(REAL, { uid: REAL, email: 'real3@example.com' });
  await db.doc(`users/${REAL}`).set({ name: 'আসল', phone: '01811000002' });
  await ok(REAL, 'ambulance_called', { id: 'demo-svc-amb-ac' });
  assert.ok(!(await db.doc(`ambulanceCalls/${REAL}_demo-svc-amb-ac`).get()).exists, 'আসল মানুষের ফোন ডেমো গাড়িতে গোনা হয় না');
  await db.doc(`ambulanceCalls/${REAL}_demo-svc-amb-icu`).set({ uid: REAL, ambulanceId: 'demo-svc-amb-icu', at: Date.now() - 3 * 3600e3, firstAt: Date.now() - 3 * 3600e3 });
  assert.strictEqual((await call(REAL, 'ambulance_rate', { id: 'demo-svc-amb-icu', rating: 1 })).status, 404, 'আসল মানুষ ডেমো গাড়িতে রেটিং দিতে পারেন না');
  await db.doc(`ambulanceCalls/${REAL}_demo-svc-amb-icu`).delete();
  LOG('আসল মানুষ ডেমো গাড়িতে ফোন করলে গোনা হয় না, রেটিংও দিতে পারেন না');

  // ---- মোছা ----
  for (let i = 0; i < 5; i++) if (!(await ok(OWNER, 'admin_demo_wipe')).more) break;
  const left = [...F.store.entries()].filter(([k, v]) => (v && v.demo === true) || (/^ambulance/.test(k) && JSON.stringify(v).includes('demo-'))).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  LOG('সব ডেমো মুছে গেল, অ্যাম্বুলেন্সের কিছু বাকি নেই');
});

test('ডেমো এখন গাজীপুরে (পারভেজ, ১৯ অক্টোবর): গাজীপুরে ডেমো অ্যাম্বুলেন্স, ফার্মেসি, প্যাথলজি আছে — ময়মনসিংহে নেই', async () => {
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  clearCache();
  const D = require(path.join(SERVER, 'lib/districts'));
  const GZP = D.find((d) => d.en === 'Gazipur').bn;
  const MYM = D.find((d) => d.en === 'Mymensingh').bn;
  const demo = require(path.join(SERVER, 'lib/demo'));
  // কেন্দ্র জয়দেবপুরে (≈২৩.৯৯ উ, ৯০.৪২ পূ)
  assert.ok(Math.abs(demo.CENTER.lat - 23.99) < 0.05 && Math.abs(demo.CENTER.lng - 90.42) < 0.05, JSON.stringify(demo.CENTER));
  const gAmb = await ok(P, 'ambulances', { district: GZP });
  assert.ok(gAmb.items.filter((a) => a.id.startsWith('demo-svc-amb')).length >= 4, 'গাজীপুরে ডেমো অ্যাম্বুলেন্স');
  assert.ok(gAmb.items.filter((a) => a.upazilaId === 320).length >= 3, 'গাজীপুর সদরে');
  assert.ok(gAmb.items.some((a) => a.upazilaId === 321), 'শ্রীপুরে একটা');
  const mAmb = await ok(P, 'ambulances', { district: MYM });
  assert.strictEqual([...mAmb.items, ...(mAmb.busy || [])].filter((a) => a.id.startsWith('demo-')).length, 0, 'ময়মনসিংহে ডেমো অ্যাম্বুলেন্স নেই');
  const gPh = await ok(P, 'pharmacies', { district: GZP });
  assert.ok(gPh.items.some((x) => x.name === 'ডেমো লাইফ ফার্মেসি' && x.open24), 'লাইফ ফার্মেসি ২৪ ঘণ্টা, গাজীপুরে');
  assert.strictEqual((await ok(P, 'pharmacies', { district: MYM })).items.filter((x) => /ডেমো/.test(x.name)).length, 0);
  const gLab = await ok(P, 'labs', { district: GZP });
  assert.ok(gLab.items.some((x) => x.name === 'ডেমো সিটি ডায়াগনস্টিক সেন্টার' && x.homeCollection));
  assert.strictEqual((await ok(P, 'labs', { district: MYM })).items.filter((x) => /ডেমো/.test(x.name)).length, 0);
  // ডেমো ডাক্তারের চেম্বার গাজীপুরের হাসপাতালে
  const prov = (await db.doc('providers/demo-doctor').get()).data();
  assert.ok(prov.chambers.every((c) => c.district === GZP), JSON.stringify(prov.chambers.map((c) => c.district)));
  assert.strictEqual(prov.chambers[0].hospitalId, 'dghs_22839', 'শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল');
  // ডেমো রোগী আর রক্তের রিকোয়েস্ট গাজীপুরে
  assert.strictEqual((await db.doc('users/demo-patient').get()).get('placeName'), GZP);
  assert.strictEqual((await db.doc('requests/demo-rq-patient-open').get()).get('placeName'), GZP);
  for (let i = 0; i < 5; i++) if (!(await ok(OWNER, 'admin_demo_wipe')).more) break;
});
