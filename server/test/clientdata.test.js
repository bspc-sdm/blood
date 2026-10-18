// অ্যাপ/ওয়েব আর সরাসরি Firestore-এ যায় না (ধাপ ৩): firestore.rules-এর প্রতিটা নিয়ম সার্ভারে একই রকম কি না।
// মালিক বনাম অন্য ব্যবহারকারী বনাম অতিথি, নিজে বদলানো যায় এমন ঘরের তালিকা, ডেমো/আসল আলাদা, সীমা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const geo = require(path.join(SERVER, 'lib/geo'));
const { db } = F.firebase;
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const DAY = 86400000;

async function call(uid, action, body = {}, ip = '5.5.5.5') {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': ip };
  await api({ method: 'POST', headers, body: { lang: 'en', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
async function user(uid, extra) {
  F.users.set(uid, { uid, email: `${uid}@example.com` });
  if (extra) await db.doc(`users/${uid}`).set({ name: `Name ${uid}`, lang: 'bn', ...extra });
}
const data = async (p) => (await db.doc(p).get()).data();
const LAT = 24.75, LNG = 90.40;
function request(id, by, f = {}) {
  const lat = f.lat ?? LAT, lng = f.lng ?? LNG;
  const open = (f.status || 'open') === 'open';
  return db.doc(`requests/${id}`).set({
    requesterUid: by, requesterName: `Requester ${by}`, patientGroup: f.group || 'O+', bags: 2, hospital: 'MCH', placeName: DIST,
    lat, lng, geohash: geo.hash(lat, lng), ...(open ? { geoOpen: geo.hash(lat, lng) } : {}),
    neededByMillis: f.neededBy ?? Date.now() + DAY, status: f.status || 'open', urgent: f.urgent === true, note: 'secret note',
    createdAtMillis: f.createdAt ?? Date.now(), acceptedCount: 1, ...(f.extra || {}),
  });
}

// ---------------------------------------------------------------------------
test('প্রোফাইল: শুধু নিজেরটা পড়া; অতিথি নয়', async () => {
  await user('pA', { phone: '01711111111', bloodGroup: 'O+', family: [{ name: 'x' }], fcmTokens: ['t'], respondedTo: { r: 1 }, photo: 'AAAA' });
  await user('pB', { phone: '01822222222' });
  const a = await ok('pA', 'profile_get');
  assert.strictEqual(a.profile.phone, '01711111111');
  assert.strictEqual(a.profile.hasPhotoThumb, false);
  // সার্ভারের ঘর (পরিবার, টোকেন, সাড়ার হিসাব) যায় না
  for (const k of ['family', 'fcmTokens', 'respondedTo', 'donorGeo']) assert.ok(!(k in a.profile), k);
  // অন্যের uid দিলেও নিজেরটাই
  const x = await ok('pA', 'profile_get', { uid: 'pB' });
  assert.strictEqual(x.profile.phone, '01711111111');
  // কেয়ারগিভার রোগীর প্রোফাইল পড়তে/বদলাতে পারেন না
  assert.strictEqual((await call('pA', 'profile_get', { asUid: 'pB' })).status, 403);
  assert.strictEqual((await call('pA', 'profile_update', { asUid: 'pB', set: { name: 'হ্যাক' } })).status, 403);
  // প্রোফাইল নেই
  await user('pNone');
  assert.strictEqual((await ok('pNone', 'profile_get')).profile, null);
  // অতিথি
  for (const action of ['profile_get', 'profile_update']) assert.strictEqual((await call(null, action, { set: { name: 'x' } })).status, 401, action);
});

test('প্রোফাইল বদল: শুধু অ্যাপের নিজের ঘর; ব্যাজ/অনুমোদন/সার্ভারের ঘর নয়', async () => {
  await user('pC', { phone: '017', bloodGroup: 'A+' });
  for (const bad of [{ badge: 'doctor' }, { approved: true }, { status: 'approved' }, { family: [] }, { fcmTokens: ['x'] }, { donorGeo: 'O+|x' },
    { geohash: 'wh2' }, { respondedTo: {} }, { emergencyComing: {} }, { commitments: { r: 1 } }, { disabled: false }, { updatedAt: 1 }]) {
    const r = await call('pC', 'profile_update', { set: bad });
    assert.strictEqual(r.status, 403, JSON.stringify(bad));
  }
  // ভুল মান
  for (const bad of [{ bloodGroup: 'Z+' }, { name: 5 }, { name: 'x'.repeat(101) }, { maxKm: 7 }, { notify: 'yes' }, { notifyGroups: ['Q'] },
    { lat: 24.7 }, { lat: 'a', lng: 90 }, { lang: 'fr' }, { photo: '<script>' }, { weightKg: -1 }]) {
    const r = await call('pC', 'profile_update', { set: bad });
    assert.strictEqual(r.status, 400, JSON.stringify(bad));
  }
  const before = await data('users/pC');
  assert.strictEqual(before.phone, '017', 'কিছুই বদলায়নি');

  await ok('pC', 'profile_update', { set: { name: 'নতুন', phone: '01733333333', bloodGroup: 'B+', birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60, notify: true } });
  let d = await data('users/pC');
  assert.strictEqual(d.name, 'নতুন');
  assert.ok(d.updatedAt, 'updatedAt');
  assert.ok(!('donorGeo' in d), 'লোকেশন নেই, তাই ডোনার খোঁজার ঘর নেই');
  // লোকেশন: রাউন্ড, geohash আর donorGeo সার্ভার বসায়
  await ok('pC', 'profile_update', { set: { lat: 24.75432, lng: 90.40789, placeName: DIST, locationSource: 'gps' } });
  d = await data('users/pC');
  assert.strictEqual(d.lat, 24.75);
  assert.strictEqual(d.lng, 90.41);
  assert.strictEqual(d.geohash, geo.hash(24.75, 90.41));
  assert.strictEqual(d.donorGeo, `B+|${geo.hash(24.75, 90.41)}`);
  assert.ok(d.locationUpdatedAtMillis > 0);
  // গ্রুপ খালি → donorGeo সরে
  await ok('pC', 'profile_update', { set: { bloodGroup: '' } });
  assert.ok(!('donorGeo' in (await data('users/pC'))));
  // সেটিংস: সীমা; null = মোছা
  await ok('pC', 'profile_update', { set: { maxPushPerDay: 99, notifyGroups: ['O+', 'O+'], viewGroups: null, maxKm: 25, fareAfterKm: 5, lang: 'en' } });
  d = await data('users/pC');
  assert.strictEqual(d.maxPushPerDay, 10);
  assert.deepStrictEqual(d.notifyGroups, ['O+']);
  assert.strictEqual(d.fareAfterKm, 5);
  await ok('pC', 'profile_update', { set: { fareAfterKm: null, photo: 'QUJD', photoThumb: 'QQ==' } });
  d = await data('users/pC');
  assert.ok(!('fareAfterKm' in d));
  assert.strictEqual((await ok('pC', 'profile_get')).profile.hasPhotoThumb, true);
  await ok('pC', 'profile_update', { set: { photo: null, photoThumb: null } });
  d = await data('users/pC');
  assert.ok(!('photo' in d) && !('photoThumb' in d));
  // প্রসেসিং তালিকা
  await ok('pC', 'profile_update', { commitAdd: 'rq1' });
  await ok('pC', 'profile_update', { commitAdd: 'rq2' });
  assert.deepStrictEqual(Object.keys((await data('users/pC')).commitments).sort(), ['rq1', 'rq2']);
  await ok('pC', 'profile_update', { commitRemove: ['rq1', 'nope'] });
  assert.deepStrictEqual(Object.keys((await data('users/pC')).commitments), ['rq2']);
  assert.strictEqual((await call('pC', 'profile_update', { commitAdd: 'bad/id' })).status, 400);
  assert.strictEqual((await call('pC', 'profile_update', { commitRemove: Array.from({ length: 101 }, (_, i) => `r${i}`) })).status, 400);
});

test('শেষ রক্তদানের তারিখ শুধু সামনে এগোয় (পেছানো বা মোছা নয়)', async () => {
  await user('pD', { name: 'ডি' });
  const t0 = Date.UTC(2026, 0, 1);
  await ok('pD', 'profile_update', { set: { lastDonationMillis: t0 } }); // প্রথমবার
  assert.strictEqual((await call('pD', 'profile_update', { set: { lastDonationMillis: t0 - DAY } })).status, 403);
  assert.strictEqual((await call('pD', 'profile_update', { set: { lastDonationMillis: null } })).status, 403);
  assert.strictEqual((await data('users/pD')).lastDonationMillis, t0);
  await ok('pD', 'profile_update', { set: { lastDonationMillis: t0 } }); // একই — চলে
  await ok('pD', 'profile_update', { set: { lastDonationMillis: t0 + 10 * DAY } });
  assert.strictEqual((await data('users/pD')).lastDonationMillis, t0 + 10 * DAY);
  // তারিখ না পাঠালে অন্য ঘর বদলায়, তারিখ থাকে
  await ok('pD', 'profile_update', { set: { name: 'ডি২' } });
  assert.strictEqual((await data('users/pD')).lastDonationMillis, t0 + 10 * DAY);
  // প্রোফাইল নেই — প্রথম লেখায় যেকোনো তারিখ (নিয়মের create-এর মতো)
  await user('pE');
  await ok('pE', 'profile_update', { set: { lastDonationMillis: t0 } });
  assert.strictEqual((await data('users/pE')).name, undefined);
});

test('রিকোয়েস্ট পড়া: লগইন করা সবাই; ডেমো আর আসল আলাদা; অতিথি নয়', async () => {
  await user('rA', {});
  await user('rB', {});
  await user('demo-patient', {});
  await request('rq-real', 'rA', { extra: { createdAt: 1700000000000, notifiedAt: 1700000001000, closedAt: 0 } });
  await request('rq-demo', 'demo-patient');
  const g = await ok('rB', 'blood_request_get', { requestId: 'rq-real' });
  assert.strictEqual(g.request.requesterUid, 'rA');
  assert.strictEqual(g.request.note, 'secret note');
  assert.strictEqual(g.request.createdAt, 1700000000000);
  assert.strictEqual(g.request.notifiedAt, 1700000001000);
  assert.strictEqual((await ok('rB', 'blood_request_get', { requestId: 'nope' })).request, null);
  // ডেমো/আসল
  assert.strictEqual((await ok('rB', 'blood_request_get', { requestId: 'rq-demo' })).request, null);
  assert.strictEqual((await ok('demo-patient', 'blood_request_get', { requestId: 'rq-real' })).request, null);
  assert.strictEqual((await ok('demo-patient', 'blood_request_get', { requestId: 'rq-demo' })).request.id, 'rq-demo');
  assert.strictEqual((await call('rB', 'blood_request_get', { requestId: '../x' })).status, 404);
  // নিজের তালিকা: শুধু নিজের, নতুনটা আগে
  await request('rq-real2', 'rA', { createdAt: Date.now() + 1000, status: 'closed' });
  const mine = await ok('rA', 'blood_requests_mine');
  assert.deepStrictEqual(mine.items.map((x) => x.id), ['rq-real2', 'rq-real']);
  assert.deepStrictEqual((await ok('rB', 'blood_requests_mine')).items, []);
  for (const action of ['blood_request_get', 'blood_requests_mine', 'blood_requests_near', 'blood_response_get', 'blood_responses_list', 'blood_contact_get', 'report_request']) {
    assert.strictEqual((await call(null, action, { requestId: 'rq-real', lat: LAT, lng: LNG })).status, 401, action);
  }
});

test('আশেপাশের রিকোয়েস্ট: চালু, নিজেরগুলো বাদ, গ্রুপ, দূরত্ব, ডেমো/আসল; জরুরি আগে', async () => {
  for (const k of [...F.store.keys()]) if (k.startsWith('requests/')) F.store.delete(k);
  await user('nMe', {});
  await user('nOther', {});
  await user('demo-donor', {});
  const north = (km) => LAT + km / 111;
  await request('n-near', 'nOther', { lat: north(1) });
  await request('n-urgent', 'nOther', { lat: north(3), urgent: true });
  await request('n-mine', 'nMe');
  await request('n-closed', 'nOther', { status: 'closed' });
  await request('n-expired', 'nOther', { neededBy: Date.now() - 3 * DAY });
  await request('n-group', 'nOther', { group: 'AB-' });
  await request('n-far', 'nOther', { lat: north(30) });
  await request('n-60', 'nOther', { lat: north(60) });
  await request('n-demo', 'demo-patient', { lat: north(2) });
  const r = await ok('nMe', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 10, groups: ['O+'] });
  assert.deepStrictEqual(r.items.map((x) => x.id), ['n-urgent', 'n-near']);
  assert.ok(r.items[1].km > 0.9 && r.items[1].km < 1.2);
  // সব গ্রুপ, ২৫ কিমি
  const all = await ok('nMe', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 25, groups: null });
  assert.deepStrictEqual(all.items.map((x) => x.id).sort(), ['n-group', 'n-near', 'n-urgent']);
  // সর্বোচ্চ ৫০ কিমি (বেশি চাইলেও)
  const big = await ok('nMe', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 1000 });
  assert.ok(big.items.some((x) => x.id === 'n-far') && !big.items.some((x) => x.id === 'n-60'));
  // ডেমো ব্যবহারকারী শুধু ডেমো রিকোয়েস্ট
  const demo = await ok('demo-donor', 'blood_requests_near', { lat: LAT, lng: LNG, maxKm: 10 });
  assert.deepStrictEqual(demo.items.map((x) => x.id), ['n-demo']);
  assert.strictEqual((await call('nMe', 'blood_requests_near', { lat: 'x', lng: LNG })).status, 400);
  // অতিথির তালিকা আগের মতো — নাম, uid, নোট ছাড়া
  const gr = await call(null, 'guest_requests', { lat: LAT, lng: LNG, maxKm: 10 }, '7.7.7.7');
  assert.strictEqual(gr.status, 200);
  assert.ok(gr.body.items.length >= 2);
  for (const it of gr.body.items) for (const k of ['requesterName', 'requesterUid', 'note', 'lat', 'lng']) assert.ok(!(k in it), k);
});

test('সাড়া আর রক্তপ্রার্থীর ফোন: কে দেখতে পারে', async () => {
  for (const u of ['cReq', 'cAcc', 'cDon', 'cCan', 'cStr']) await user(u, {});
  await request('c1', 'cReq');
  await db.doc('requests/c1/private/contact').set({ name: 'রক্তপ্রার্থী', phone: '01799999999' });
  const resp = (uid, status, at) => db.doc(`requests/c1/responses/${uid}`).set({ donorUid: uid, donorName: `D ${uid}`, donorPhone: '0180000', donorGroup: 'O+', status, createdAtMillis: at });
  await resp('cAcc', 'accepted', 2);
  await resp('cDon', 'donated', 1);
  await resp('cCan', 'cancelled', 3);

  // নিজের সাড়া — শুধু নিজেরটা
  assert.strictEqual((await ok('cAcc', 'blood_response_get', { requestId: 'c1' })).response.status, 'accepted');
  assert.strictEqual((await ok('cStr', 'blood_response_get', { requestId: 'c1', donorUid: 'cAcc' })).response, null);
  assert.strictEqual((await ok('cReq', 'blood_response_get', { requestId: 'c1' })).response, null);

  // তালিকা: শুধু রক্তপ্রার্থী, শুধু রাজি/রক্ত দেওয়া
  const list = await ok('cReq', 'blood_responses_list', { requestId: 'c1' });
  assert.deepStrictEqual(list.items.map((x) => x.donorUid), ['cDon', 'cAcc']);
  for (const u of ['cAcc', 'cStr', 'cCan']) assert.strictEqual((await call(u, 'blood_responses_list', { requestId: 'c1' })).status, 403, u);
  assert.strictEqual((await call('cReq', 'blood_responses_list', { requestId: 'none' })).status, 403);

  // ফোন: রক্তপ্রার্থী নিজে, রাজি আর রক্ত দেওয়া ডোনার (খোলা রিকোয়েস্টে)
  for (const u of ['cReq', 'cAcc', 'cDon']) {
    assert.strictEqual((await ok(u, 'blood_contact_get', { requestId: 'c1' })).contact.phone, '01799999999', u);
  }
  for (const u of ['cCan', 'cStr']) assert.strictEqual((await call(u, 'blood_contact_get', { requestId: 'c1' })).status, 403, u);
  assert.strictEqual((await call('cReq', 'blood_contact_get', { requestId: 'none' })).status, 403);
  // বন্ধ হলে ডোনার আর দেখেন না, রক্তপ্রার্থী দেখেন
  await db.doc('requests/c1').update({ status: 'closed' });
  for (const u of ['cAcc', 'cDon']) assert.strictEqual((await call(u, 'blood_contact_get', { requestId: 'c1' })).status, 403, u);
  assert.strictEqual((await ok('cReq', 'blood_contact_get', { requestId: 'c1' })).contact.name, 'রক্তপ্রার্থী');
});

test('রিপোর্ট: নিজের নামে একবারই, শুধু তিনটা ঘর', async () => {
  await user('rpA', {});
  await user('rpB', {});
  await ok('rpA', 'report_request', { requestId: 'rq-x', reporterUid: 'rpB', extra: 'x' });
  const d = await data('reports/rq-x_rpA');
  assert.deepStrictEqual(Object.keys(d).sort(), ['createdAtMillis', 'reporterUid', 'requestId']);
  assert.strictEqual(d.reporterUid, 'rpA');
  assert.ok(!(await db.doc('reports/rq-x_rpB').get()).exists, 'অন্যের নামে নয়');
  assert.strictEqual((await call('rpA', 'report_request', { requestId: 'rq-x' })).status, 409);
  await ok('rpB', 'report_request', { requestId: 'rq-x' });
  assert.strictEqual((await call('rpA', 'report_request', { requestId: '' })).status, 404);
});
