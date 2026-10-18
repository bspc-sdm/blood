// ডেমো — ভাগ: blood (রক্তদান)। মালিক ডেমো বানান → আসল পড়ার কাজগুলোতে ডামি দেখা যায় →
// পরীক্ষকের সাধারণ কাজ (সাড়া, নিশ্চিত, বন্ধ, জরুরি আহ্বান, ব্লাড ব্যাংক, অ্যাডমিন অনুমোদন) → ডেমো মুছলে কিছু থাকে না।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const { db } = F.firebase;

// ---- নকল Firebase-এ যা নেই (শুধু এই টেস্টে): টোকেন যাচাই (আলাদা API ফাইল), recursiveDelete, লেনদেনে getAll ----
F.firebase.verifyUser = async (req) => {
  const h = req.headers.authorization || '';
  const u = F.users.get(h.startsWith('Bearer ') ? h.slice(7) : '');
  if (!u) throw new F.firebase.HttpError(401, 'লগইন দরকার');
  return u.uid;
};
if (!db.recursiveDelete) {
  db.recursiveDelete = async (ref) => {
    for (const k of [...F.store.keys()]) if (k === ref.path || k.startsWith(`${ref.path}/`)) F.store.delete(k);
  };
}
const txOrig = db.runTransaction;
db.runTransaction = (fn) => txOrig((t) => fn({ ...t, getAll: async (...refs) => Promise.all(refs.map((r) => t.get(r))) }));

const api = require(path.join(SERVER, 'api/community.js'));
const respondApi = require(path.join(SERVER, 'api/respond.js'));
const closeApi = require(path.join(SERVER, 'api/close-request.js'));
const editApi = require(path.join(SERVER, 'api/edit-request.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const R = require(path.join(SERVER, 'lib/rules'));
const Geo = require(path.join(SERVER, 'lib/geo'));
const V = require(path.join(SERVER, 'lib/verify'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;

const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
const P = demo.uidOf('patient');
const D = demo.uidOf('donor');
const CG = demo.uidOf('caregiver');
const BB = demo.uidOf('bloodbank');
const x = (k) => demo.uidOf(`x-${k}`);

function resObj() {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  return { res, out: () => out };
}
async function call(uid, action, body = {}) {
  const { res, out } = resObj();
  await api({ method: 'POST', headers: uid ? { authorization: `Bearer ${uid}` } : {}, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out() };
}
async function post(handler, uid, body) {
  const { res, out } = resObj();
  await handler({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body }, res);
  return { status: res.statusCode, body: out() };
}
const get = async (p) => (await db.doc(p).get()).data();
const docsUnder = (prefix) => [...F.store.keys()].filter((k) => k.startsWith(prefix));

/** অ্যাপ/ওয়েবের RequestRepository.nearbyOpen-এর মতো (সরাসরি Firestore পড়া): geoOpen দিয়ে কাছের, দেখার গ্রুপ, নিজেরগুলো বাদ */
async function nearbyOpen(uid, maxKm) {
  const u = await get(`users/${uid}`);
  const groups = R.BLOOD_GROUPS.filter((g) => R.canDonate(u.bloodGroup, g));
  const view = groups.length ? groups : R.BLOOD_GROUPS;
  const found = await Geo.near(db.collection('requests'), 'geoOpen', u.lat, u.lng, maxKm);
  return found.map((f) => ({ id: f.doc.id, ...f.doc.data(), km: f.km }))
    .filter((r) => r.status === 'open' && R.isActive(r) && r.requesterUid !== uid && view.includes(r.patientGroup));
}

test('বানানো: প্রোফাইল, ডোনার, যোগ্যতা — অ্যাপের মতো ঘর', async () => {
  const r = await call(OWNER, 'admin_demo_seed');
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));

  const donor = await get(`users/${D}`);
  assert.strictEqual(donor.bloodGroup, 'O+');
  assert.ok(R.isEligibleDonor(donor), 'ডেমো ডোনার এখন রক্ত দিতে পারেন');
  assert.strictEqual(donor.donorGeo, Geo.donorKeyOf(donor), 'donorGeo = গ্রুপ|geohash');
  assert.strictEqual(donor.geohash, Geo.hash(donor.lat, donor.lng));
  assert.strictEqual(donor.placeName, DISTRICT);
  assert.strictEqual(donor.notify, true);
  assert.ok(Object.keys(donor.commitments || {}).includes('demo-rq-donor-confirmed'), 'প্রসেসিং তালিকা');

  const cg = await get(`users/${CG}`);
  assert.ok(!R.isEligibleDonor(cg), 'কেয়ারগিভার সম্প্রতি রক্ত দিয়েছেন');
  assert.ok(R.isEligible(cg.lastDonationMillis, cg.lastDonationMillis + 120 * R.DAY_MS));

  const patient = await get(`users/${P}`);
  assert.ok(patient.name && patient.phone, 'নাম ও ফোন আছে (প্রোফাইল পূর্ণ)');
  assert.ok(!patient.bloodGroup, 'রোগী এখনো রক্তদাতা নন');
  // ব্যবসার ডেমো অ্যাকাউন্ট: "প্রোফাইল পূরণ করুন" এ আটকে নেই
  for (const k of ['doctor', 'assistant', 'pharmacy', 'lab', 'ambulance', 'bloodbank', 'homecare', 'caregiver']) {
    const u = await get(`users/${demo.uidOf(k)}`);
    assert.ok(u && u.name && /^01[3-9]\d{8}$/.test(u.phone) && typeof u.lat === 'number', k);
  }

  // পেছনের ডোনার: ১২ জন, কেউ যোগ্য কেউ নন
  const bg = docsUnder('users/demo-x-').map((p) => F.store.get(p)).filter((u) => u.bloodGroup);
  assert.ok(bg.length >= 8 && bg.length <= 15, `${bg.length}`);
  assert.ok(bg.filter((u) => R.isEligibleDonor(u)).length >= 5);
  assert.ok(bg.filter((u) => !R.isEligibleDonor(u)).length >= 3);
  assert.ok(new Set(bg.map((u) => u.bloodGroup)).size >= 7, 'বিভিন্ন গ্রুপ');
  bg.forEach((u) => assert.strictEqual(u.donorGeo, Geo.donorKeyOf(u)));
  // নিকটের ডোনার খোঁজা (রিকোয়েস্টের নোটিফিকেশন / জরুরি আহ্বান যেভাবে খোঁজে)
  const near = await Geo.nearDonors(db, R.DONORS_FOR['A+'], donor.lat, donor.lng, 10);
  assert.ok(near.length >= 5, `${near.length}`);
});

test('অতিথি: আশেপাশের রিকোয়েস্ট (নাম-ফোন ছাড়া), জরুরি আহ্বান, ব্লাড ব্যাংক, হাসপাতাল', async () => {
  const c = require(path.join(SERVER, 'lib/districts')).find((d) => d.bn === DISTRICT);
  const g = await call(null, 'guest_requests', { lat: c.lat, lng: c.lng, maxKm: 10 });
  assert.strictEqual(g.status, 200, JSON.stringify(g.body));
  const ids = g.body.items.map((i) => i.id);
  for (const id of ['demo-rq-patient-open', 'demo-rq-urgent', 'demo-rq-fare', 'demo-rq-oplus', 'demo-rq-thal', 'demo-rq-oneg', 'demo-rq-donor-confirmed']) {
    assert.ok(ids.includes(id), id);
  }
  assert.ok(!ids.includes('demo-rq-far'), '১০ কিমির বাইরে');
  assert.ok(!ids.includes('demo-rq-patient-done'), 'বন্ধ রিকোয়েস্ট নয়');
  assert.strictEqual(ids[0], 'demo-rq-urgent', 'জরুরি সবার আগে');
  assert.ok(g.body.items.every((i) => !('requesterName' in i) && !JSON.stringify(i).includes('0170000')), 'অতিথি নাম/ফোন দেখেন না');
  assert.ok(g.body.items.find((i) => i.id === 'demo-rq-fare').travelFare);

  const em = await call(null, 'emergencies_active', { district: DISTRICT });
  // ডেমো আহ্বান অতিথি (আর আসল ব্যবহারকারী) দেখেন না — শুধু ডেমো অ্যাকাউন্ট
  assert.deepStrictEqual(em.body.items.map((i) => i.id), []);

  const banks = await call(null, 'bloodbanks', { district: DISTRICT });
  const names = banks.body.items.map((b) => b.id);
  assert.ok(['demo-bb-mine', 'demo-bb-govt', 'demo-bb-club'].every((id) => names.includes(id)), names.join());
  assert.ok(!names.includes('demo-bb-pending'), 'অপেক্ষায় থাকা তালিকায় নয়');

  const h = await call(null, 'hospitals', { district: DISTRICT });
  assert.ok(h.body.items.some((x) => x.id === 'demo-hosp-charpara'));
});

test('ডোনার: আশেপাশে মানানসই রিকোয়েস্ট, দূরত্বের সেটিংস, নিশ্চিত রিকোয়েস্টে নম্বর; রিকোয়েস্টে কারো ফোন নেই', async () => {
  const ids = (await nearbyOpen(D, 10)).map((r) => r.id).sort();
  assert.deepStrictEqual(ids, ['demo-rq-donor-confirmed', 'demo-rq-fare', 'demo-rq-oplus', 'demo-rq-patient-open', 'demo-rq-thal', 'demo-rq-urgent']);
  assert.ok((await nearbyOpen(D, 25)).some((r) => r.id === 'demo-rq-far'), '২৫ কিমিতে দূরেরটাও');
  // রোগী (রক্তদাতা নন): সব গ্রুপ দেখেন, নিজেরটা বাদ
  const pIds = (await nearbyOpen(P, 10)).map((r) => r.id);
  assert.ok(pIds.includes('demo-rq-oneg') && !pIds.includes('demo-rq-patient-open'));

  // ফোন শুধু আলাদা গোপন নথিতে (Firestore নিয়ম: রক্তপ্রার্থী আর রাজি ডোনার)
  for (const p of docsUnder('requests/').filter((k) => k.split('/').length === 2)) {
    assert.ok(!Object.keys(F.store.get(p)).some((k) => /phone/i.test(k)), p);
  }
  const resp = await get(`requests/demo-rq-donor-confirmed/responses/${D}`);
  assert.strictEqual(resp.status, 'accepted');
  assert.strictEqual(resp.confirmed, true);
  const contact = await get('requests/demo-rq-donor-confirmed/private/contact');
  assert.ok(/^01700001\d{3}$/.test(contact.phone) && contact.name.includes('ডেমো'));
  // রোগীর রিকোয়েস্টে এখনো ডেমো ডোনারের সাড়া নেই — তাই নম্বর দেখার নিয়ম মেলে না
  assert.strictEqual(await get(`requests/demo-rq-patient-open/responses/${D}`), undefined);
});

test('জরুরি আহ্বান: ডোনার "আসছি", শেষ হওয়াটায় "রক্ত দিয়েছেন?" → না', async () => {
  const r = await call(D, 'emergencies_active');
  assert.strictEqual(r.status, 200);
  const e = r.body.items.find((i) => i.id === 'demo-em-live');
  assert.ok(e && e.canCome && e.myGroup === 'O+');
  assert.deepStrictEqual(r.body.ask.map((a) => a.id), ['demo-em-ended']);
  assert.strictEqual((await call(D, 'emergency_respond', { id: 'demo-em-live', op: 'coming' })).status, 200);
  assert.strictEqual((await get('emergencies/demo-em-live')).groups['O+'].coming, 2);
  const before = (await get(`users/${D}`)).lastDonationMillis;
  assert.strictEqual((await call(D, 'emergency_donated', { id: 'demo-em-ended', donated: false })).status, 200);
  assert.strictEqual((await get(`users/${D}`)).lastDonationMillis, before, '"না, দিইনি" — তারিখ বদলায় না');
  // কেয়ারগিভার (B+) — এই আহ্বানে তাঁর গ্রুপ নেই
  const cg = await call(CG, 'emergencies_active');
  assert.strictEqual(cg.body.items[0].canCome, false);
});

test('সাড়া → রক্তপ্রার্থী ডোনার দেখেন → নিশ্চিত → "রক্ত পেয়েছি"; অযোগ্যদের সাড়া আটকায়', async () => {
  // যোগ্য নন / মানানসই নয় / রক্তদাতার তথ্য নেই
  assert.strictEqual((await post(respondApi, D, { requestId: 'demo-rq-oneg' })).status, 400);
  const cgr = await post(respondApi, CG, { requestId: 'demo-rq-fare' });
  assert.strictEqual(cgr.status, 400);
  assert.match(cgr.body.error, /যোগ্য নন/);
  assert.match((await post(respondApi, P, { requestId: 'demo-rq-urgent' })).body.error, /প্রোফাইল/);

  // ডোনার রাজি: রক্তপ্রার্থী নাম-ফোন দেখেন, ডোনার রক্তপ্রার্থীর নম্বর দেখেন
  const ok = await post(respondApi, D, { requestId: 'demo-rq-patient-open' });
  assert.strictEqual(ok.status, 200, JSON.stringify(ok.body));
  const resp = await get(`requests/demo-rq-patient-open/responses/${D}`);
  assert.strictEqual(resp.donorPhone, '01700001102');
  assert.strictEqual(resp.status, 'accepted');
  const req = await get('requests/demo-rq-patient-open');
  assert.strictEqual(req.acceptedCount, 3);
  assert.strictEqual((await get('requests/demo-rq-patient-open/private/contact')).phone, '01700001101');
  assert.ok(F.pushes.some((p) => p.uid === P && /রাজি/.test(p.title)), 'রক্তপ্রার্থী নোটিফিকেশন পান');

  // ভাড়ার রিকোয়েস্টে রাজি হয়ে বাতিল → নম্বর আর দেখা যায় না (সাড়া "cancelled")
  assert.strictEqual((await post(respondApi, D, { requestId: 'demo-rq-fare' })).status, 200);
  assert.strictEqual((await call(D, 'response_update', { requestId: 'demo-rq-fare', op: 'cancel' })).status, 200);
  assert.strictEqual((await get(`requests/demo-rq-fare/responses/${D}`)).status, 'cancelled');

  // রক্তপ্রার্থী: এডিট, নাদিয়াকে বাদ দেওয়া, ডেমো ডোনারকে নিশ্চিত
  assert.strictEqual((await post(editApi, P, { requestId: 'demo-rq-patient-open', note: 'ডেমো: সকাল ১০টার মধ্যে আসুন' })).status, 200);
  assert.strictEqual((await call(P, 'response_update', { requestId: 'demo-rq-patient-open', op: 'remove', donorUid: x('nadia') })).status, 200);
  const cf = await call(P, 'confirm_donor', { requestId: 'demo-rq-patient-open', donorUid: D });
  assert.strictEqual(cf.status, 200, JSON.stringify(cf.body));
  const after = await get('requests/demo-rq-patient-open');
  assert.strictEqual(after.confirmedCount, 2);
  assert.strictEqual(after.fulfilled, true);

  // "রক্ত পেয়েছি, বন্ধ করুন": দুজন রক্ত দিয়েছেন
  const cl = await post(closeApi, P, { requestId: 'demo-rq-patient-open', donatedUids: [D, x('sakib')], bagsReceived: 2 });
  assert.strictEqual(cl.status, 200, JSON.stringify(cl.body));
  const closed = await get('requests/demo-rq-patient-open');
  assert.strictEqual(closed.status, 'closed');
  assert.strictEqual(closed.geoOpen, undefined, 'আশেপাশের তালিকা থেকে সরে যায়');
  const donor = await get(`users/${D}`);
  assert.strictEqual(donor.lastDonationMillis, R.todayDhakaUtcMidnight());
  assert.ok(!R.isEligibleDonor(donor), 'এখন ১২০ দিন অপেক্ষা');
  // অন্য রিকোয়েস্টের "রাজি" মুক্ত হয়ে যায়
  assert.strictEqual((await get(`requests/demo-rq-donor-confirmed/responses/${D}`)).status, 'cancelled');

  // শেষ রক্তদানের তারিখ বদল (সার্ভার): পেছালে ঝুঁকি জেনে নিশ্চিত করতে হয়
  const back = await call(CG, 'donation_date_set', { millis: R.todayDhakaUtcMidnight() - 200 * R.DAY_MS });
  assert.strictEqual(back.status, 409);
  assert.strictEqual((await call(CG, 'donation_date_set', { millis: R.todayDhakaUtcMidnight() - 200 * R.DAY_MS, confirmed: true })).status, 200);
  assert.ok(R.isEligibleDonor(await get(`users/${CG}`)));

  // রোগী নতুন রিকোয়েস্ট দেন (পরীক্ষার সময় বানানো — মুছলে যেতে হবে)
  const c = await call(P, 'request_create', {
    requesterName: 'ডেমো রোগী রহিম', requesterPhone: '01700001101', patientGroup: 'O+', bags: 1, hospital: 'ডেমো চরপাড়া জেনারেল হাসপাতাল',
    // নোট ৩০০ এককে কাটা হয় — ঠিক সীমায় একটা ইমোজি: অর্ধেকটা থাকলে PostgreSQL লেখা ফেরত দিত (22P02)
    placeName: DISTRICT, lat: 24.75, lng: 90.41, neededByMillis: R.todayDhakaUtcMidnight() + R.DAY_MS, note: `ডেমো${'.'.repeat(295)}😀 শেষ`,
  });
  assert.strictEqual(c.status, 200, JSON.stringify(c.body));
  const note = (await get(`requests/${c.body.id}`)).note;
  assert.strictEqual(note, `ডেমো${'.'.repeat(295)}`, 'ইমোজি অর্ধেক না রেখে পুরো বাদ');
  assert.ok(note.isWellFormed());
  assert.ok((await nearbyOpen(x('rafiq'), 10)).some((r) => r.id === c.body.id), 'নতুন রিকোয়েস্ট আশেপাশে দেখা যায়');
});

test('ব্লাড ব্যাংক: নিজের তালিকা, সময় বদল, ফোন আর রিপোর্ট; মালিক (অ্যাডমিন) অনুমোদন দেন', async () => {
  const mine = await call(BB, 'bloodbank_mine');
  assert.strictEqual(mine.status, 200);
  const st = Object.fromEntries(mine.body.items.map((b) => [b.id, b.status]));
  assert.deepStrictEqual(st, { 'demo-bb-mine': 'approved', 'demo-bb-pending': 'pending' });
  assert.ok(mine.body.items.find((b) => b.id === 'demo-bb-pending').docs.some((d) => d.type === 'front' && d.has));
  assert.ok((await get('bloodBankStats/demo-bb-mine')).total > 0, 'ফোনের হিসাব');

  // সময় বদল (২৪ ঘণ্টা → সকাল ৮টা–রাত ১০টা): যাচাই ছাড়াই, তালিকায় থাকে
  const b = mine.body.items.find((i) => i.id === 'demo-bb-mine');
  const save = await call(BB, 'bloodbank_save', { ...b, open24: false, openAt: '08:00', closeAt: '22:00', termsAccepted: true });
  assert.strictEqual(save.status, 200, JSON.stringify(save.body));
  assert.strictEqual(save.body.pending, false);
  assert.strictEqual((await get('bloodBanks/demo-bb-mine')).status, 'approved');

  // রোগী ফোন করে রিপোর্ট
  assert.strictEqual((await call(P, 'bloodbank_called', { id: 'demo-bb-govt' })).status, 200);
  assert.strictEqual((await call(P, 'report', { kind: 'bloodbank', id: 'demo-bb-govt', reason: 'ডেমো: ফোন ধরেনি' })).status, 200);

  // অ্যাডমিন: অপেক্ষার তালিকা → সব বিষয় টিক দিয়ে অনুমোদন
  const pend = await call(OWNER, 'admin_bloodbanks', { status: 'pending' });
  const item = pend.body.items.find((i) => i.id === 'demo-bb-pending');
  assert.ok(item && item.verify.docs.find((d) => d.type === 'front').has);
  const photos = await call(OWNER, 'admin_verify_docs', { kind: 'bloodbank', id: 'demo-bb-pending' });
  assert.ok(photos.body.photos.find((p) => p.type === 'front').data.startsWith('/9j/'));
  const checks = V.CHECKS.bloodbank(item).map((c) => c.id);
  const ap = await call(OWNER, 'admin_bloodbank_resolve', { id: 'demo-bb-pending', status: 'approved', checks });
  assert.strictEqual(ap.status, 200, JSON.stringify(ap.body));
  const list = await call(P, 'bloodbanks', { district: DISTRICT });
  assert.ok(list.body.items.some((i) => i.id === 'demo-bb-pending'), 'অনুমোদনের পর তালিকায়');

  // রিপোর্ট, হাসপাতালের অনুরোধ, তারিখ সংশোধন, জরুরি আহ্বান — অ্যাডমিনের তালিকায়
  const reps = await call(OWNER, 'admin_reports');
  assert.ok(reps.body.items.some((i) => i.targetPath === 'bloodBanks/demo-bb-govt'));
  const hr = await call(OWNER, 'admin_hospital_requests');
  assert.ok(hr.body.items.some((i) => i.id === 'demo-hreq-kachijhuli'));
  assert.strictEqual((await call(OWNER, 'admin_hospital_resolve', { id: 'demo-hreq-kachijhuli', op: 'reject' })).status, 200);
  const corr = await require(path.join(SERVER, 'lib/corrections')).pendingCorrections();
  assert.ok(corr.some((c) => c.id === 'demo-corr-farhana'));
  const ems = await call(OWNER, 'admin_emergencies');
  assert.deepStrictEqual(ems.body.items.filter((i) => i.id.startsWith('demo-')).map((i) => [i.id, i.live]).sort(), [['demo-em-ended', false], ['demo-em-live', true]]);
});

test('মোছা: ডেমোর সব (পরীক্ষার সময় বানানো রিকোয়েস্ট, সাড়া, ফোনের রেকর্ড, রিপোর্ট সহ) চলে যায়', async () => {
  await db.doc('requests/realreq0001').set({ requesterUid: 'realuser0002', status: 'open' });
  const r = await call(OWNER, 'admin_demo_wipe');
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  const mentions = (k) => /demo-/.test(k) || /demo-/.test(JSON.stringify(F.store.get(k)));
  for (const c of ['requests/', 'emergencies/', 'bloodBanks/', 'bloodBankStats/', 'bloodBankCalls/', 'modReports/', 'hospitals/',
    'hospitalRequests/', 'correctionRequests/', 'inbox/', 'serverState/demo-', 'users/demo-', 'verifyDocs/bloodbank_', 'sanctions/']) {
    assert.deepStrictEqual(docsUnder(c).filter(mentions), [], c);
  }
  assert.deepStrictEqual(docsUnder('requests/').filter((k) => !k.startsWith('requests/realreq0001')), [], 'ডেমোর সব রিকোয়েস্ট (সাব-কালেকশন সহ)');
  assert.ok((await db.doc('requests/realreq0001').get()).exists, 'আসলটা থাকে');
  assert.ok(![...F.users.keys()].some((u) => demo.isDemoUid(u)));
});
