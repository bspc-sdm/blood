// অ্যাম্বুলেন্সের ভাড়া (২.৩০.০, পারভেজ, ১৯ অক্টোবর ২০২৬):
// মালিকের ভাড়ার তালিকা → গন্তব্য থেকে আনুমানিক ভাড়া (নিজের উপজেলা/গন্তব্য/বাড়তি/রাত); রোগীর বাজেটের ছাঁকনি আর গোনা;
// ভাড়া না জানা গাড়ি বাদ (চাইলে সহ); চালকের দর বাজেটের বেশি নয়; কয়েকটা দর, রোগী "রাজি" চাপলে একটাই (transaction);
// দরের মেয়াদ; বাজেট বাড়ালে নতুন গাড়িতে পাঠানো; ফোনের নিয়ম আগের মতো; ডেমো আলাদা; "বেশি ভাড়া নিয়েছে"।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const clock = require('./support/clock');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const AR = require(path.join(SERVER, 'lib/ambreq.js'));
const FARE = require(path.join(SERVER, 'lib/ambfare.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const { db } = F.firebase;

const DISTRICTS = require(path.join(SERVER, 'lib/districts'));
const MYM = DISTRICTS.find((d) => d.en === 'Mymensingh');
const DISTRICT = MYM.bn;
const DHAKA = DISTRICTS.find((d) => d.en === 'Dhaka').bn;
const SADAR = 466;
const TRISHAL = 463;
const SAVAR = 365; // ঢাকা জেলার উপজেলা
const SADAR_LABEL = `${require(path.join(SERVER, 'lib/hospital-match')).UPAZILA.get(SADAR).bn}, ${DISTRICT}`;
const HOSP = 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল';
const HOUR = 3600 * 1000;
const SEC = 1000;

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.9.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const user = async (uid, x = {}) => {
  F.users.set(uid, { uid, email: x.email || `${uid}@example.com` });
  await db.doc(`users/${uid}`).set({ name: `নাম ${uid}`, lang: 'bn', phone: '01811000000', ...x });
};
const at = (dx, dy) => ({
  lat: Math.round((MYM.lat + dy / 111) * 1e5) / 1e5,
  lng: Math.round((MYM.lng + dx / (111 * Math.cos((MYM.lat * Math.PI) / 180))) * 1e5) / 1e5,
});
async function amb(id, owner, x = {}) {
  await user(owner);
  const base = x.base ? { lat: Math.round(x.base.lat * 100) / 100, lng: Math.round(x.base.lng * 100) / 100 } : {};
  await db.doc(`ambulances/${id}`).set({
    name: x.name || `গাড়ি ${id}`, phone: '01711000000', vehicleNo: `V-${id}`, types: x.types || ['general'], district: DISTRICT,
    upazilaId: SADAR, area: '', fare: 'আলোচনা সাপেক্ষে', ownerUid: owner, status: 'approved',
    available: true, availableAt: Date.now() - HOUR, ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
    ...(x.fareChart ? { fareChart: x.fareChart } : {}), ...base, createdAt: Date.now() - 30 * 24 * HOUR,
  });
}
const chart = (local, rows = [], extras = {}) => ({ local, rows, oxygen: null, icu: null, night: null, ...extras });
const toDhaka = (amount) => ({ kind: 'district', district: DHAKA, amount });
const clearAll = () => {
  for (const k of [...F.store.keys()]) if (/^(ambulances|users|ambulanceCalls|ambulanceStats|ambulanceRequests|ambReqLimits|inbox)\//.test(k)) F.store.delete(k);
  F.pushes.length = 0;
  M.clear('');
};
const P = 'fare-patient';
const LOCAL = { kind: 'upazila', upazilaId: SADAR };
const base = { district: DISTRICT, upazilaId: SADAR, ...at(0, 0), gps: true };
const ask = (uid, body = {}) => call(uid, 'ambreq_create', { ...base, pickup: 'চরপাড়া মোড়', note: '', phone: '01811000000', dest: LOCAL, maxFare: 1000, ...body });
const count = (uid, body = {}) => { M.clear(''); return ok(uid, 'ambreq_count', { ...base, dest: LOCAL, maxFare: 1000, ...body }); };
const reqDoc = async (id) => (await db.doc(`ambulanceRequests/${id}`).get()).data();
const pushedTo = () => F.pushes.map((p) => p.uid);
const driverList = async (uid) => (await ok(uid, 'ambreq_driver')).items;
const status = async (id) => (await ok(P, 'ambreq_status', { id })).request;

/**
 * ১–৬ কিমি দূরে ৬টা গাড়ি:
 * a1 ৮০০ (ত্রিশাল ১২০০, ঢাকা ৬০০০, মেডিকেল ৭০০; অক্সিজেন ৩০০, রাত ৫০০), a2 ১০০০ (ঢাকা ৫০০০), a3 ১৫০০ (ঢাকা ৪৫০০),
 * a4 তালিকা নেই, a5 ২৫০০ (ঢাকা ৯০০০), a6 আইসিইউ: নিজের জেলা যেকোনো জায়গা ১৮০০, ঢাকা ৮০০০, আইসিইউ যন্ত্র ৩০০০
 */
async function seed() {
  clearAll();
  clock.setDhaka('2026-10-19 12:00');
  await user(P);
  await amb('a1', 'd1', { base: at(1, 0), ratingSum: 9, ratingCount: 2,
    fareChart: chart(800, [{ kind: 'upazila', upazilaId: TRISHAL, amount: 1200 }, toDhaka(6000), { kind: 'hospital', name: HOSP, amount: 700 }], { oxygen: 300, night: 500 }) });
  await amb('a2', 'd2', { base: at(2, 0), types: ['general', 'ac'], fareChart: chart(1000, [toDhaka(5000)]) });
  await amb('a3', 'd3', { base: at(3, 0), fareChart: chart(1500, [toDhaka(4500)]) });
  await amb('a4', 'd4', { base: at(4, 0) });
  await amb('a5', 'd5', { base: at(5, 0), fareChart: chart(2500, [toDhaka(9000)]) });
  await amb('a6', 'd6', { base: at(6, 0), types: ['icu', 'ac'], fareChart: chart(null, [{ kind: 'district', district: DISTRICT, amount: 1800 }, toDhaka(8000)], { icu: 3000 }) });
}

test('আনুমানিক ভাড়া: নিজের উপজেলা, অন্য উপজেলা, জেলা (উপজেলা থেকে জেলায় নামা), হাসপাতালের নাম, জানা নেই; বাড়তি আর রাত', () => {
  const veh = { district: DISTRICT, upazilaId: SADAR };
  const c = FARE.normalizeChart({
    local: 800, rows: [{ kind: 'upazila', upazilaId: TRISHAL, amount: 1200 }, toDhaka(6000), { kind: 'hospital', name: HOSP, amount: 700 }],
    oxygen: 300, icu: 2000, night: '৫০০',
  }, veh).chart;
  assert.strictEqual(c.night, 500, 'বাংলা অঙ্কও');
  const est = (dest, o) => FARE.estimate(c, veh, dest, o);
  assert.strictEqual(est(LOCAL), 800, 'নিজের উপজেলা = ভেতরের ভাড়া');
  assert.strictEqual(est({ kind: 'upazila', upazilaId: TRISHAL }), 1200);
  assert.strictEqual(est({ kind: 'district', district: DHAKA }), 6000);
  assert.strictEqual(est({ kind: 'upazila', upazilaId: SAVAR }), 6000, 'ঢাকার উপজেলা → ঢাকার ভাড়া');
  assert.strictEqual(est({ kind: 'hospital', name: 'ময়মনসিংহ মেডিকেল কলেজ' }), 700, 'নাম মেলানো (হাসপাতাল শব্দ ছাড়া, অংশ)');
  assert.strictEqual(est({ kind: 'hospital', name: 'এভারকেয়ার' }), null);
  assert.strictEqual(est({ kind: 'upazila', upazilaId: 470 }), null, 'তালিকায় নেই = জানা নেই');
  assert.strictEqual(est({ kind: 'district', district: 'গাজীপুর' }), null);
  // বাড়তি: আইসিইউ ধরন, অক্সিজেন, রাত
  assert.strictEqual(est(LOCAL, { type: 'icu' }), 2800);
  assert.strictEqual(est(LOCAL, { type: 'ac', oxygen: true }), 1100);
  assert.strictEqual(est({ kind: 'district', district: DHAKA }, { type: 'icu', oxygen: true, night: true }), 6000 + 2000 + 300 + 500);
  // বাড়তি তালিকায় না থাকলে ০ (ভাড়ার মধ্যেই)
  assert.strictEqual(FARE.estimate(FARE.normalizeChart({ local: 900 }, veh).chart, veh, LOCAL, { type: 'icu', oxygen: true, night: true }), 900);
  // নিজের জেলার ঘর = জেলার ভেতরে যেকোনো জায়গা (উপজেলার ঘর না থাকলে)
  const d = FARE.normalizeChart({ rows: [{ kind: 'district', district: DISTRICT, amount: 1800 }] }, veh).chart;
  assert.strictEqual(FARE.estimate(d, veh, { kind: 'upazila', upazilaId: TRISHAL }), 1800);
  assert.strictEqual(FARE.estimate(null, veh, LOCAL), null, 'তালিকা নেই');
  // রাত: ঢাকার সময় রাত ১০টা–সকাল ৬টা
  for (const [t, night] of [['2026-10-19 21:59', false], ['2026-10-19 22:00', true], ['2026-10-20 03:00', true], ['2026-10-20 05:59', true], ['2026-10-20 06:00', false], ['2026-10-19 12:00', false]]) {
    assert.strictEqual(FARE.isNight(clock.setDhaka(t)), night, t);
  }
  clock.reset();
});

test('ভাড়ার তালিকা যাচাই: পুরো টাকা, সীমা, সর্বোচ্চ ১৫টা, একই গন্তব্য দুবার নয়, নিজের জেলার উপজেলা; মালিক সেভ করেন, পুরোনো অ্যাপে আগেরটা থাকে', async () => {
  const veh = { district: DISTRICT, upazilaId: SADAR };
  const e = (x) => FARE.normalizeChart(x, veh).error?.[1];
  assert.match(e({ local: 800.5 }), /পুরো টাকায়/);
  assert.match(e({ local: 50 }), /১০০ থেকে/);
  assert.match(e({ local: 800, rows: [toDhaka(200000)] }), /১০০ থেকে/);
  assert.match(e({ local: 800, oxygen: 10 }), /বাড়তি/);
  assert.match(e({ rows: Array.from({ length: 16 }, (_, i) => ({ kind: 'hospital', name: `হাসপাতাল নম্বর ${i}`, amount: 1000 })) }), /১৫টা/);
  assert.match(e({ rows: [toDhaka(5000), toDhaka(6000)] }), /দুবার/);
  assert.match(e({ rows: [{ kind: 'hospital', name: 'সিটি হাসপাতাল', amount: 900 }, { kind: 'hospital', name: 'সিটি  Hospital', amount: 900 }] }), /দুবার/, 'একই হাসপাতাল, আলাদা লেখা');
  assert.match(e({ rows: [{ kind: 'upazila', upazilaId: SAVAR, amount: 5000 }] }), /নিজের জেলার/);
  assert.match(e({ rows: [{ kind: 'upazila', upazilaId: SADAR, amount: 5000 }] }), /নিজের উপজেলার ভেতরে/);
  assert.match(e({ rows: [{ kind: 'district', district: DHAKA }] }), /ভাড়া লিখুন/);
  assert.match(e({ oxygen: 300 }), /অন্তত একটা/);
  assert.deepStrictEqual(FARE.normalizeChart({ local: null, rows: [] }, veh), { chart: null });
  assert.strictEqual(FARE.normalizeChart({ rows: Array.from({ length: 15 }, (_, i) => ({ kind: 'hospital', name: `হাসপাতাল ${'ক'.repeat(i + 1)}`, amount: 1000 })) }, veh).chart.rows.length, 15);

  await seed();
  const save = (fc) => call('d4', 'ambulance_save', {
    id: 'a4', name: 'গাড়ি a4', phone: '01711000000', vehicleNo: 'V-a4', types: ['general'], district: DISTRICT, upazilaId: SADAR,
    fare: 'আলোচনা সাপেক্ষে', termsAccepted: true, ...(fc !== undefined ? { fareChart: fc } : {}),
  });
  assert.strictEqual((await save({ local: 900.5 })).status, 400);
  assert.strictEqual((await save(chart(900, [toDhaka(5500)], { night: 400 }))).status, 200);
  let a = (await db.doc('ambulances/a4').get()).data();
  assert.deepStrictEqual(a.fareChart, { local: 900, rows: [{ kind: 'district', district: DHAKA, amount: 5500 }], oxygen: null, icu: null, night: 400 });
  assert.strictEqual(a.fare, 'আলোচনা সাপেক্ষে', 'আগের লেখা ভাড়া নোট হিসেবে থাকে');
  assert.strictEqual(a.status, 'approved', 'ভাড়া বদলে আবার যাচাই নয়');
  // পুরোনো অ্যাপ (fareChart পাঠায় না) → আগেরটা থাকে; null → মোছে
  await save(undefined);
  assert.strictEqual((await db.doc('ambulances/a4').get()).get('fareChart').local, 900);
  const mine = (await ok('d4', 'ambulance_mine')).items[0];
  assert.strictEqual(mine.fareChart.local, 900);
  await save(null);
  a = (await db.doc('ambulances/a4').get()).data();
  assert.ok(a.fareChart == null);
  clock.reset();
});

test('বাজেটের ছাঁকনি আর গোনা: চিপে কয়টা, ভাড়া না জানা বাদ (চাইলে সহ), ০ হলে "৳X পর্যন্ত বাড়ান"; পুশ শুধু বাজেটের ভেতরে', async () => {
  await seed();
  let c = await count(P);
  assert.strictEqual(c.count, 2, '৮০০ আর ১০০০');
  assert.strictEqual(c.unknown, 1, 'a4');
  assert.deepStrictEqual(c.levels, { 1000: 2, 2000: 4, 5000: 5, 10000: 5 });
  assert.strictEqual(c.raiseTo, null);
  assert.deepStrictEqual(c.hospitals, [HOSP], 'কাছের গাড়ির তালিকার হাসপাতাল');
  assert.strictEqual((await count(P, { includeUnknown: true })).count, 3);
  assert.strictEqual((await count(P, { maxFare: null })).count, 5, 'যেকোনো — তবু ভাড়া না জানা বাদ');
  assert.strictEqual((await count(P, { type: 'icu', maxFare: 2000 })).count, 0, 'আইসিইউ যন্ত্রসহ ১৮০০ + ৩০০০');
  assert.strictEqual((await count(P, { type: 'icu', maxFare: 5000 })).count, 1, 'ধরনও একই নিয়মে');
  // ঢাকায় ১০০০-এ কেউ নেই → ৫০০০ পর্যন্ত (সবচেয়ে কম ৪৫০০)
  c = await count(P, { dest: { kind: 'district', district: DHAKA } });
  assert.strictEqual(c.count, 0);
  assert.strictEqual(c.raiseTo, 5000);
  // চিপের ওপরে: আইসিইউ ঢাকা ৮০০০ + ৩০০০ = ১১০০০ → ১০০০০-এ নয়, ১১০০০
  c = await count(P, { dest: { kind: 'district', district: DHAKA }, type: 'icu', maxFare: 10000 });
  assert.strictEqual(c.count, 0);
  assert.strictEqual(c.raiseTo, 11000);
  // হাসপাতাল: নাম মেলে শুধু a1-এর তালিকায়
  assert.strictEqual((await count(P, { dest: { kind: 'hospital', name: 'ময়মনসিংহ মেডিকেল কলেজ' }, maxFare: null })).count, 1);
  // ভুল গন্তব্য/বাজেট
  assert.strictEqual((await call(P, 'ambreq_count', { ...base, dest: { kind: 'upazila', upazilaId: 99999 } })).status, 400);
  assert.strictEqual((await call(P, 'ambreq_count', { ...base, dest: LOCAL, maxFare: 50 })).status, 400);
  assert.strictEqual((await call(null, 'ambreq_count', { ...base, dest: LOCAL })).status, 401, 'লগইন লাগে');

  // ডাকা: শুধু বাজেটের ভেতরের গাড়িতে পুশ, আনুমানিক ভাড়া অনুরোধে
  F.pushes.length = 0;
  const r = await ask(P);
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  let x = await reqDoc(r.body.id);
  assert.deepStrictEqual(Object.keys(x.sentTo).sort(), ['d1', 'd2']);
  assert.deepStrictEqual(x.est, { d1: 800, d2: 1000 });
  assert.deepStrictEqual(pushedTo().sort(), ['d1', 'd2']);
  assert.match(F.pushes[0].body, /ভাড়াসহ "আসছি"/);
  assert.ok(F.pushes.every((p) => !/মেডিকেল|চরপাড়া/.test(p.title + p.body)), 'পুশে গন্তব্য/ঠিকানা নয়');
  assert.strictEqual(r.body.request.maxFare, 1000);
  assert.strictEqual(r.body.request.destLabel, SADAR_LABEL);
  await ok(P, 'ambreq_cancel', { id: r.body.id });
  // ভাড়া না জানা সহ: a4-ও পায় (আনুমানিক ভাড়া নেই)
  x = await reqDoc((await ask(P, { includeUnknown: true })).body.id);
  assert.deepStrictEqual(Object.keys(x.sentTo).sort(), ['d1', 'd2', 'd4']);
  assert.strictEqual(x.est.d4, undefined);
  await ok(P, 'ambreq_cancel', { id: x && (await ok(P, 'ambreq_status', {})).request.id });
  // ০টা মিললে ডাকা যায় না (পরিষ্কার বার্তা)
  const none = await ask(P, { dest: { kind: 'district', district: DHAKA } });
  assert.strictEqual(none.status, 409);
  assert.strictEqual(none.body.code, 'no_fare_match');
  assert.match(none.body.error, /ভাড়া বাড়ান/);
  // গন্তব্য ভুল → ৪০০
  assert.strictEqual((await ask(P, { dest: { kind: 'hospital', name: 'ক' } })).status, 400);
  // রাতে অক্সিজেনসহ: a1 = ৮০০ + ৩০০ + ৫০০ = ১৬০০ → ১০০০-এ বাদ, ২০০০-এ আছে
  clock.setDhaka('2026-10-19 23:30');
  c = await count(P, { oxygen: true, maxFare: 2000 });
  assert.strictEqual(c.night, true);
  const y = await reqDoc((await ask(P, { oxygen: true, maxFare: 2000 })).body.id);
  assert.strictEqual(y.est.d1, 1600);
  assert.strictEqual(y.night, true);
  assert.strictEqual(y.oxygen, true);
  clock.reset();
});

test('দর: আগে বসানো ভাড়া, বাজেটের বেশি নয়, ভাড়া না জানা গাড়িকে লিখতে হয়; কয়েকটা দর — কম ভাড়া আগে, সমান হলে কাছেরটা; রাজির আগে চালক ফোন/GPS পান না', async () => {
  await seed();
  const { id } = (await ask(P, { maxFare: 2000, includeUnknown: true })).body;
  const x0 = await reqDoc(id);
  assert.deepStrictEqual(Object.keys(x0.sentTo).sort(), ['d1', 'd2', 'd3', 'd4', 'd6']);
  // চালকের কার্ড: গন্তব্য, ধরন, আনুমানিক ভাড়া, বাজেট — ফোন/GPS/নাম নয়
  const card = (await driverList('d3'))[0];
  assert.strictEqual(card.suggestedFare, 1500);
  assert.strictEqual(card.maxFare, 2000);
  assert.strictEqual(card.destLabel, SADAR_LABEL);
  assert.strictEqual(card.km, 3);
  assert.strictEqual(card.myQuote, null);
  for (const k of ['lat', 'lng', 'phone', 'patientName']) assert.strictEqual(card[k], undefined, `রাজির আগে ${k} নয়`);
  F.pushes.length = 0;
  // বাজেটের বেশি নয়
  const over = await call('d3', 'ambreq_accept', { id, fare: 2100 });
  assert.strictEqual(over.status, 409);
  assert.strictEqual(over.body.code, 'over_budget');
  assert.match(over.body.error, /৳২০০০/);
  assert.strictEqual((await call('d3', 'ambreq_accept', { id, fare: 1500.5 })).status, 400);
  // ভাড়া না জানা গাড়ি: লিখতে হয়
  const blank = await call('d4', 'ambreq_accept', { id });
  assert.strictEqual(blank.status, 400);
  assert.strictEqual(blank.body.code, 'fare_needed');
  // দর: fare না দিলে আনুমানিক ভাড়া (পুরোনো অ্যাপ), দিলে সেটা
  const q3 = await ok('d3', 'ambreq_accept', { id });
  assert.strictEqual(q3.request.myQuote.fare, 1500);
  assert.strictEqual(q3.request.status, 'open', 'এখনো নিশ্চিত নয়');
  await ok('d1', 'ambreq_accept', { id, fare: 1500 });
  await ok('d4', 'ambreq_accept', { id, fare: '১২০০' });
  assert.strictEqual((await reqDoc(id)).status, 'open');
  assert.deepStrictEqual(pushedTo(), [P, P, P], 'প্রতিটা দরে রোগীকে পুশ');
  assert.match(F.pushes[0].title, /৳১৫০০-তে আসতে রাজি/);
  let v = await status(id);
  assert.deepStrictEqual(v.quotes.map((q) => [q.id, q.fare, q.km]), [['a4', 1200, 4], ['a1', 1500, 1], ['a3', 1500, 3]], 'কম ভাড়া আগে, সমান হলে কাছেরটা');
  assert.strictEqual(v.quotes[1].rating, 4.5);
  assert.ok(v.quotes.every((q) => !('phone' in q) && !('owner' in q)), 'রাজির আগে দরে ফোন নয়');
  // দর বদলানো (কমানো)
  await ok('d1', 'ambreq_accept', { id, fare: 1100 });
  v = await status(id);
  assert.deepStrictEqual(v.quotes.map((q) => q.id), ['a1', 'a4', 'a3']);
  // দর দেওয়া চালক: "রোগীর উত্তরের অপেক্ষা" — তবু ফোন/GPS নয়
  const wait = (await driverList('d1'))[0];
  assert.strictEqual(wait.myQuote.fare, 1100);
  assert.strictEqual(wait.phone, undefined);
  clock.reset();
});

test('"রাজি": একসাথে দুই দরে চাপলেও একটাই নিশ্চিত; বাকিরা ছাড়া পান ("রোগী অন্য গাড়ি নিয়েছেন"); ঠিক হওয়া ভাড়া রাখা; চালক এখন ফোন/GPS পান', async () => {
  await seed();
  const { id } = (await ask(P, { maxFare: 2000 })).body;
  await ok('d1', 'ambreq_accept', { id });
  await ok('d2', 'ambreq_accept', { id, fare: 900 });
  await ok('d3', 'ambreq_accept', { id });
  // দর ছাড়া রাজি নয়; দর না দেওয়া গাড়িতে নয়
  assert.strictEqual((await call(P, 'ambreq_agree', { id, ambulanceId: 'a6' })).status, 409);
  F.pushes.length = 0;
  const res = await Promise.all([call(P, 'ambreq_agree', { id, ambulanceId: 'a2' }), call(P, 'ambreq_agree', { id, ambulanceId: 'a3' })]);
  assert.deepStrictEqual(res.map((r) => r.status).sort(), [200, 409]);
  const x = await reqDoc(id);
  assert.strictEqual(x.status, 'accepted');
  const won = x.ambulanceId;
  assert.strictEqual(x.agreedFare, won === 'a2' ? 900 : 1500);
  assert.deepStrictEqual(x.activeOwners, [x.acceptedBy]);
  // একটাই গাড়ি ট্রিপে
  assert.strictEqual((await db.collection('ambulances').where('reqBusy', '==', id).get()).size, 1);
  const winner = x.acceptedBy;
  const released = F.pushes.filter((p) => p.title === 'রোগী অন্য গাড়ি নিয়েছেন').map((p) => p.uid).sort();
  assert.deepStrictEqual(released, ['d1', 'd2', 'd3', 'd6'].filter((u) => u !== winner), 'দর দেওয়া আর শুধু ডাক পাওয়া — সবাই');
  assert.ok(F.pushes.some((p) => p.uid === winner && /রোগী রাজি — ৳/.test(p.title)));
  for (const u of ['d1', 'd2', 'd3', 'd6'].filter((o) => o !== winner)) assert.deepStrictEqual(await driverList(u), [], `${u} ছাড়া`);
  const mine = (await driverList(winner))[0];
  assert.strictEqual(mine.status, 'accepted');
  assert.strictEqual(mine.agreedFare, x.agreedFare);
  assert.strictEqual(mine.phone, '01811000000');
  assert.ok(typeof mine.lat === 'number');
  const v = await status(id);
  assert.strictEqual(v.status, 'accepted');
  assert.strictEqual(v.agreedFare, x.agreedFare);
  assert.deepStrictEqual(v.quotes, [], 'নিশ্চিতের পর দরের তালিকা নয়');
  // হারানো চালক আর দর দিতে পারেন না
  assert.strictEqual((await call('d1', 'ambreq_accept', { id, fare: 800 })).status, 409);
  // শেষ → ট্রিপের রেকর্ডে ঠিক হওয়া ভাড়া → রেটিংয়ে "বেশি নিয়েছে" → অ্যাডমিনের হিসাব (দুবার গোনা নয়)
  await ok(winner, 'ambreq_step', { id, step: 'done' });
  assert.strictEqual((await db.doc(`ambulanceCalls/${P}_${won}`).get()).get('agreedFare'), x.agreedFare);
  await ok(P, 'ambulance_rate', { id: won, rating: 2, overcharged: true });
  await ok(P, 'ambulance_rate', { id: won, rating: 2, overcharged: true });
  assert.strictEqual((await db.doc(`ambulances/${won}`).get()).get('overchargeCount'), 1);
  await user('fare-admin', { email: 'ekapkbd@gmail.com' });
  const adm = (await ok('fare-admin', 'admin_ambulances', { status: 'approved' })).items.find((a) => a.id === won);
  assert.strictEqual(adm.overchargeCount, 1);
  await ok(P, 'ambulance_rate', { id: won, rating: 3, overcharged: false });
  assert.strictEqual((await db.doc(`ambulances/${won}`).get()).get('overchargeCount'), 0);
  // অ্যাপে ভাড়া ঠিক না হওয়া (শুধু ফোন) গাড়িতে "বেশি নিয়েছে" গোনা হয় না
  await db.doc(`ambulanceCalls/${P}_a5`).set({ uid: P, ambulanceId: 'a5', at: Date.now() - 2 * HOUR, firstAt: Date.now() - 2 * HOUR });
  await ok(P, 'ambulance_rate', { id: 'a5', rating: 1, overcharged: true });
  assert.ok(!(await db.doc('ambulances/a5').get()).get('overchargeCount'));
  // ২৪ ঘণ্টা পর গন্তব্যও মোছে; ঠিক হওয়া ভাড়া থাকে
  await AR.dailyCleanup(Date.now() + 25 * HOUR);
  const purged = await reqDoc(id);
  assert.strictEqual(purged.dest, undefined);
  assert.strictEqual(purged.agreedFare, x.agreedFare);
  clock.reset();
});

test('দরের মেয়াদ ২ মিনিট: উত্তর না পেলে চালক ছাড়া (পুশ); দর ঝুলে থাকলে ৩ মিনিটের মেয়াদ বাড়ে; ৩ মিনিটের পর নতুন দর নয়', async () => {
  await seed();
  const { id } = (await ask(P, { maxFare: 2000 })).body;
  clock.advance(10 * SEC);
  await ok('d1', 'ambreq_accept', { id });
  F.pushes.length = 0;
  clock.advance(2 * 60 * SEC + SEC);
  let v = await status(id);
  assert.strictEqual(v.status, 'open');
  assert.deepStrictEqual(v.quotes, []);
  assert.ok(F.pushes.some((p) => p.uid === 'd1' && /উত্তর দেননি/.test(p.title)), 'চালককে পুশ');
  assert.deepStrictEqual(await driverList('d1'), [], 'চালক ছাড়া');
  assert.strictEqual((await call(P, 'ambreq_agree', { id, ambulanceId: 'a1' })).status, 409);
  assert.strictEqual((await call('d1', 'ambreq_accept', { id, fare: 900 })).status, 404, 'ছাড়া চালক আবার নয়');
  // ২:৫০-এ নতুন দর → ৩ মিনিট পেরিয়েও খোলা, মেয়াদ ৪:৫০
  clock.advance(39 * SEC); // ২:৫০
  await ok('d2', 'ambreq_accept', { id });
  clock.advance(20 * SEC); // ৩:১০
  v = await status(id);
  assert.strictEqual(v.status, 'open', 'দর ঝুলে আছে');
  assert.strictEqual(v.quotes.length, 1);
  assert.ok(v.expiresAt > Date.now() + 90 * SEC);
  const late = await call('d3', 'ambreq_accept', { id });
  assert.strictEqual(late.status, 409, '৩ মিনিটের পর নতুন দর নয়');
  // ৪:৫১-এ শেষ
  clock.advance(101 * SEC);
  v = await status(id);
  assert.strictEqual(v.status, 'expired');
  assert.ok(F.pushes.some((p) => p.uid === P && p.title === 'ভাড়ার প্রস্তাবের সময় শেষ'));
  clock.reset();
});

test('বাজেট বাড়ানো: ৬০ সেকেন্ডে "৳X পর্যন্ত বাড়ান" (raiseTo), বাড়ালে নতুন মেলা গাড়িতে পাঠানো, কমানো যায় না, ভাড়া না জানাও সহ', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  clock.advance(61 * SEC);
  let v = await status(id);
  assert.strictEqual(v.wave, 2);
  assert.strictEqual(v.notified, 2, 'বাজেটে আর কেউ নেই');
  assert.strictEqual(v.raiseTo, 2000);
  assert.strictEqual(v.unknownFree, 1);
  assert.ok(v.fallback.length === 3 && v.fallback[0].id === 'a1', 'ফোনের বাটন — বাজেটের ভেতরের আগে');
  F.pushes.length = 0;
  assert.strictEqual((await call(P, 'ambreq_budget', { id, maxFare: 900 })).status, 400, 'কমানো নয়');
  const up = await ok(P, 'ambreq_budget', { id, maxFare: 2000 });
  assert.strictEqual(up.added, 2);
  assert.deepStrictEqual(pushedTo().sort(), ['d3', 'd6']);
  let x = await reqDoc(id);
  assert.deepStrictEqual(x.est, { d1: 800, d2: 1000, d3: 1500, d6: 1800 });
  assert.strictEqual(x.maxFare, 2000);
  assert.ok(up.request.expiresAt >= Date.now() + 3 * 60 * SEC - SEC, 'মেয়াদ নতুন করে');
  assert.strictEqual(up.request.raiseTo, 5000, 'বাকি a5 (২৫০০) → পরের চিপ');
  // নতুন গাড়ি এখন দর দিতে পারে; পুরোনো গাড়িও নতুন বাজেট পর্যন্ত
  await ok('d6', 'ambreq_accept', { id });
  await ok('d2', 'ambreq_accept', { id, fare: 1900 });
  // ভাড়া না জানা সহ
  F.pushes.length = 0;
  const unk = await ok(P, 'ambreq_budget', { id, maxFare: 2000, includeUnknown: true });
  assert.strictEqual(unk.added, 1);
  assert.deepStrictEqual(pushedTo(), ['d4']);
  // যেকোনো ভাড়া
  F.pushes.length = 0;
  await ok(P, 'ambreq_budget', { id, maxFare: null });
  assert.deepStrictEqual(pushedTo(), ['d5']);
  x = await reqDoc(id);
  assert.strictEqual(x.maxFare, null);
  assert.strictEqual((await call(P, 'ambreq_budget', { id, maxFare: 5000 })).status, 400, '"যেকোনো" থেকে কমানো নয়');
  // পুরোনো (ভাড়া ছাড়া) অনুরোধে নয়
  await ok(P, 'ambreq_cancel', { id });
  const legacy = (await call(P, 'ambreq_create', { ...base, pickup: 'চরপাড়া', phone: '01811000000' })).body.id;
  assert.strictEqual((await call(P, 'ambreq_budget', { id: legacy, maxFare: 5000 })).status, 409);
  clock.reset();
});

test('ফোনের নিয়ম আগের মতো: ফোন করা গাড়ি দর দিতে পারে (বাজেটের নিয়মে), byPhone দর থাকলেও বন্ধ করে, রাজি চালক বদলালে দর মোছে; এক সময়ে একটাই রাজি', async () => {
  await seed();
  let id = (await ask(P)).body.id;
  // বাজেটের বাইরের a5-এ ফোন → তালিকায় আসে, আনুমানিক ২৫০০, বাজেট ১০০০
  await ok(P, 'ambulance_called', { id: 'a5', reqId: id });
  const card = (await driverList('d5'))[0];
  assert.strictEqual(card.called, true);
  assert.strictEqual(card.suggestedFare, 2500);
  assert.strictEqual((await call('d5', 'ambreq_accept', { id })).body.code, 'over_budget');
  await ok('d5', 'ambreq_accept', { id, fare: 1000 });
  await ok('d1', 'ambreq_accept', { id });
  // ফোনে পেয়েছেন → byPhone: দর দেওয়া d1 সহ বাকিরা "আর দরকার নেই"; এরপর রাজি/দর নয়
  F.pushes.length = 0;
  await ok(P, 'ambreq_byphone', { id, ambulanceId: 'a5' });
  assert.deepStrictEqual(pushedTo().sort(), ['d1', 'd2']);
  assert.strictEqual((await call(P, 'ambreq_agree', { id, ambulanceId: 'a1' })).status, 409);
  assert.strictEqual((await call('d2', 'ambreq_accept', { id, fare: 900 })).status, 409);

  // রাজির পর "ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল" (গাড়ি না বেছে) → আবার খোলা, দর আর ঠিক হওয়া ভাড়া মোছে
  id = (await ask(P, { maxFare: 2000 })).body.id;
  await ok('d1', 'ambreq_accept', { id });
  await ok('d2', 'ambreq_accept', { id });
  await ok(P, 'ambreq_agree', { id, ambulanceId: 'a1' });
  const v = (await ok(P, 'ambreq_replace', { id })).request;
  assert.strictEqual(v.status, 'open');
  assert.deepStrictEqual(v.quotes, [], 'আগের দর বাদ — আবার দিতে হবে');
  assert.strictEqual(v.agreedFare, null);
  assert.strictEqual((await call(P, 'ambreq_agree', { id, ambulanceId: 'a2' })).status, 409);
  assert.strictEqual((await call('d1', 'ambreq_accept', { id })).status, 404, 'বাতিল চালক আর নয়');
  // এক সময়ে একটাই রাজি: দুই দরে রাজি + byPhone একসাথে
  await ok(P, 'ambulance_called', { id: 'a5', reqId: id });
  await ok('d2', 'ambreq_accept', { id });
  await ok('d3', 'ambreq_accept', { id });
  const res = await Promise.all([
    call(P, 'ambreq_agree', { id, ambulanceId: 'a2' }), call(P, 'ambreq_agree', { id, ambulanceId: 'a3' }), call(P, 'ambreq_byphone', { id, ambulanceId: 'a5' }),
  ]);
  assert.strictEqual(res.filter((r) => r.status === 200).length, 1, JSON.stringify(res.map((r) => r.status)));
  const x = await reqDoc(id);
  const busy = (await db.collection('ambulances').where('reqBusy', '==', id).get()).size;
  assert.strictEqual(busy, x.status === 'accepted' ? 1 : 0);

  // নিশ্চিত হওয়া চালকের অন্য রোগীর দর তুলে নেওয়া (গাড়ি এখন ট্রিপে)
  await seed();
  await user('fare-patient-2');
  const a = (await ask(P, { maxFare: 2000 })).body.id;
  const b = (await ask('fare-patient-2', { maxFare: 2000 })).body.id;
  await ok('d1', 'ambreq_accept', { id: a });
  await ok('d1', 'ambreq_accept', { id: b });
  await ok(P, 'ambreq_agree', { id: a, ambulanceId: 'a1' });
  assert.deepStrictEqual((await ok('fare-patient-2', 'ambreq_status', { id: b })).request.quotes, []);
  assert.strictEqual((await call('fare-patient-2', 'ambreq_agree', { id: b, ambulanceId: 'a1' })).status, 409);
  clock.reset();
});

test('ডেমো আলাদা: ডেমো রোগীর গোনা আর ডাক শুধু ডেমো গাড়িতে, আসল রোগীর গোনায় ডেমো গাড়ি নয়', async () => {
  await seed();
  await amb('demo-svc-fx', 'demo-fx-drv', { base: at(0.5, 0), fareChart: chart(700) });
  const DP = 'demo-patient-fare';
  await user(DP);
  const dc = await count(DP);
  assert.strictEqual(dc.count, 1);
  assert.strictEqual(dc.unknown, 0);
  const pc = await count(P);
  assert.strictEqual(pc.count, 2, 'আসল রোগী: ডেমো গাড়ি গোনা হয় না');
  const r = await ask(DP);
  assert.deepStrictEqual(Object.keys((await reqDoc(r.body.id)).sentTo), ['demo-fx-drv']);
  await ok('demo-fx-drv', 'ambreq_accept', { id: r.body.id });
  await ok(DP, 'ambreq_agree', { id: r.body.id, ambulanceId: 'demo-svc-fx' });
  assert.strictEqual((await reqDoc(r.body.id)).agreedFare, 700);
  // আসল চালক ডেমো অনুরোধে দর দিতে পারেন না (পাঠানোই হয়নি)
  assert.strictEqual((await call('d1', 'ambreq_accept', { id: r.body.id })).status, 404);
  clock.reset();
});
