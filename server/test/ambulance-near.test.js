// অ্যাম্বুলেন্স (পারভেজ, ১৮ অক্টোবর ২০২৬: "জরুরি অবস্থায় প্রতিটা সেকেন্ড দামি"):
// ১) সবচেয়ে কাছের খালি গাড়ি আগে, প্রতিটায় দূরত্ব (km), আর এক চাপে ফোনের গাড়ি (nearestId) — ধরন দিয়ে ছাঁকলে সেই ধরনের;
//    lat/lng না দিলে (পুরোনো অ্যাপ) আগের সাজানো; ডেমো আর আসল আলাদা।
// ২) "এখনো খালি আছেন?" — খালি থাকার মেয়াদের শেষ ঘণ্টায় চালককে একবার পুশ; আবার চালু করলে নতুন পালা।
// রিভিউয়ের পর (১৮ অক্টোবর): চালকের জায়গা ২ দশমিকে কাটা (নানা জায়গা থেকে চেয়ে ত্রিভুজে বের করা যায় না), km পুরো কিমিতে,
// GPS ছাড়া স্থানাঙ্কে নিজের উপজেলা আগে, তালিকা মনে করানোর জন্য অপেক্ষা করে না, remindAt দিয়ে এক ঘরের কোয়েরি।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const AMB = require(path.join(SERVER, 'lib/ambulance.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const geo = require(path.join(SERVER, 'lib/geo'));
const { db } = F.firebase;

const MYM = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh');
const DISTRICT = MYM.bn;
const SADAR = 466;
const TRISHAL = 463;
const HOUR = 3600 * 1000;
const MIN = 60 * 1000;

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
  F.users.set(uid, { uid, email: `${uid}@example.com` });
  await db.doc(`users/${uid}`).set({ name: `নাম ${uid}`, lang: 'bn', ...x });
};
/** জেলার কেন্দ্র থেকে dx কিমি পূর্বে, dy কিমি উত্তরে */
const at = (dx, dy) => ({
  lat: Math.round((MYM.lat + dy / 111) * 1000) / 1000,
  lng: Math.round((MYM.lng + dx / (111 * Math.cos((MYM.lat * Math.PI) / 180))) * 1000) / 1000,
});
async function amb(id, owner, x) {
  await user(owner);
  await db.doc(`ambulances/${id}`).set({
    name: x.name || `গাড়ি ${id}`, phone: '01711000000', vehicleNo: `V-${id}`, types: x.types || ['general'], district: DISTRICT,
    upazilaId: x.upazilaId || SADAR, area: '', fare: '', ownerUid: owner, status: x.status || 'approved',
    available: x.available !== false, availableAt: x.availableAt ?? Date.now() - HOUR, ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
    ...(x.busyAt ? { busyAt: x.busyAt } : {}), ...(x.base ? x.base : {}), createdAt: Date.now() - 30 * 24 * HOUR,
    // আসলে "খালি আছি" চাপলে বসে; এখানে সব গাড়িতে (ট্রিপে/অনুমোদন ছাড়াও) — মনে করানো যেন নিজেই ছেঁকে নেয়
    ...(x.remindAt === null ? {} : { remindAt: x.remindAt ?? (x.availableAt ?? Date.now() - HOUR) + AMB.REMIND_AT_MS }),
  });
}
const list = async (uid, body = {}) => {
  M.clear('');
  const r = await ok(uid, 'ambulances', { district: DISTRICT, ...body });
  await AMB.remindIdle();
  return r;
};
/** ফোনের আসল GPS (gps: true) */
const gps = (dx, dy) => ({ ...at(dx, dy), gps: true });
const ids = (r) => r.items.map((a) => a.id);
const clearAll = () => {
  for (const k of [...F.store.keys()]) if (/^(ambulances|users|ambulanceCalls|ambulanceStats)\//.test(k)) F.store.delete(k);
  F.pushes.length = 0;
  M.clear('');
};

const REAL = 'near-patient';
const DEMO_P = 'demo-patient';

async function seedNear() {
  clearAll();
  await user(REAL);
  await user(DEMO_P);
  // A: ৪ কিমি উত্তরে, রেটিং সবচেয়ে বেশি
  await amb('nA', 'drvA', { name: 'আলফা', base: at(0, 4), ratingSum: 25, ratingCount: 5 });
  // B: ১ কিমি পূর্বে, এসি
  await amb('nB', 'drvB', { name: 'ব্রাভো', types: ['ac', 'general'], base: at(1, 0), ratingSum: 9, ratingCount: 3 });
  // শুধু ফ্রিজার ভ্যান, ৩০০ মিটার — রোগীর জরুরি ফোনে নয়
  await amb('nF', 'drvF', { name: 'ফ্রিজার', types: ['freezer'], base: at(0.3, 0) });
  // D: আইসিইউ, জায়গা দেওয়া নেই → জেলার কেন্দ্র ধরে "প্রায়"
  await amb('nD', 'drvD', { name: 'ডেল্টা আইসিইউ', types: ['icu', 'ac'], upazilaId: TRISHAL, ratingSum: 16, ratingCount: 4 });
  // E: ২০০ মিটার, কিন্তু ২০ ঘণ্টা আগে "খালি" জানানো → "ফোনে জেনে নিন" (খালিগুলোর পরে)
  await amb('nE', 'drvE', { name: 'ইকো', base: at(0, 0.2), availableAt: Date.now() - 20 * HOUR });
  // ট্রিপে
  await amb('nT', 'drvT', { name: 'ট্রিপ', base: at(0, 0.1), available: false, availableAt: Date.now() - HOUR, busyAt: Date.now() - HOUR });
  // ডেমো গাড়ি, ১০০ মিটার
  await amb('demo-svc-x-near', 'demo-x-drv', { name: 'ডেমো কাছের', base: at(0.1, 0) });
}

test('lat/lng ছাড়া (পুরোনো অ্যাপ): আগের সাজানো — খালি আগে, রেটিং বেশি আগে; km নেই', async () => {
  await seedNear();
  const r = await list(REAL);
  assert.deepStrictEqual(ids(r), ['nA', 'nD', 'nB', 'nF', 'demo-svc-x-near', 'nE']);
  assert.ok(r.items.every((a) => a.km === undefined && a.kmApprox === undefined));
  assert.deepStrictEqual(r.busy.map((a) => a.id), ['nT'], 'ট্রিপের তালিকা আগের মতো');
  assert.strictEqual(r.nearestId, 'nA', 'দূরত্ব না জানলে প্রথম খালি গাড়ি');
  // বাংলাদেশের বাইরের স্থানাঙ্ক উপেক্ষা
  const bad = await list(REAL, { lat: 51.5, lng: -0.12 });
  assert.deepStrictEqual(ids(bad), ids(r));
});

test('GPS দিলে: খালিগুলো কাছেরটা আগে, প্রতিটায় km (পুরো কিমি, ১-এর কম = ০), জায়গা না দেওয়া গাড়ি "প্রায়"', async () => {
  await seedNear();
  const r = await list(REAL, { ...gps(0, 0), upazilaId: SADAR });
  // ডেমো আর ফ্রিজার একই ~১ কিমির ঘরে (০ কিমি) — দুটোর ক্রম যেকোনো
  assert.deepStrictEqual(ids(r).slice(0, 2).sort(), ['demo-svc-x-near', 'nF']);
  assert.deepStrictEqual(ids(r).slice(2), ['nB', 'nA', 'nD', 'nE']);
  const by = Object.fromEntries(r.items.map((a) => [a.id, a]));
  assert.strictEqual(by.nB.km, 1);
  // আলফা আসলে ৪ কিমি — ঘরে কাটার পর ৩ (গোপনীয়তার দাম: ~১ কিমি এদিক-ওদিক)
  assert.strictEqual(by.nA.km, 3);
  assert.strictEqual(by.nF.km, 0, '১ কিমির কম');
  assert.ok(r.items.every((a) => Number.isInteger(a.km)), 'পুরো কিমি');
  assert.strictEqual(by.nD.kmApprox, true, 'জায়গা নেই → জেলার কেন্দ্র');
  assert.strictEqual(by.nD.km, 0);
  assert.ok(!by.nB.kmApprox);
  assert.deepStrictEqual(r.busy.map((a) => a.id), ['nT']);
  assert.strictEqual(r.busy[0].km, undefined, 'ট্রিপের তালিকা বদলায়নি');
  // এক চাপে ফোন: আসল মানুষকে ডেমো গাড়ি নয়, শুধু ফ্রিজার ভ্যান নয় → ব্রাভো
  assert.strictEqual(r.nearestId, 'nB');
  // অন্য জায়গা থেকে (আলফার পাশে) — আলফা আগে
  const north = await list(REAL, gps(0, 3.8));
  assert.strictEqual(north.items[0].id, 'nA');
  assert.strictEqual(north.items[0].km, 0);
  assert.strictEqual(north.nearestId, 'nA');
});

test('ধরন দিয়ে ছাঁকলে কাছের সেই ধরনের গাড়ি; ফ্রিজার দিয়ে ছাঁকলে ফ্রিজার', async () => {
  await seedNear();
  const icu = await list(REAL, { ...gps(0, 0), type: 'icu' });
  assert.deepStrictEqual(ids(icu), ['nD']);
  assert.strictEqual(icu.nearestId, 'nD');
  assert.strictEqual(icu.items[0].kmApprox, true);
  const ac = await list(REAL, { ...gps(0, 0), type: 'ac' });
  assert.deepStrictEqual(ids(ac), ['nB', 'nD'], 'নিশ্চিত ১ কিমি আগে, "প্রায়" পরে');
  assert.strictEqual(ac.nearestId, 'nB');
  const fr = await list(REAL, { ...gps(0, 0), type: 'freezer' });
  assert.strictEqual(fr.nearestId, 'nF');
});

test('ডেমো আর আসল: ডেমো রোগী ডেমো গাড়ি পান, অতিথি/আসল মানুষ নয়; নিজের গাড়ি নিজের বাটনে নয়; ফোন গোনা আলাদা', async () => {
  await seedNear();
  assert.strictEqual((await list(DEMO_P, gps(0, 0))).nearestId, 'demo-svc-x-near');
  assert.strictEqual((await list(null, gps(0, 0))).nearestId, 'nB', 'অতিথি = আসল জগৎ');
  assert.strictEqual((await list('drvB', gps(0, 0))).nearestId, 'nA', 'চালক নিজের গাড়ি নয় — পরের কাছেরটা');
  // বাটনের ফোনও সাধারণ ফোনের মতো লেখা হয় (ambulance_called) — আসল মানুষের ফোন ডেমো গাড়িতে গোনা হয় না
  await ok(REAL, 'ambulance_called', { id: 'nB' });
  assert.ok((await db.doc(`ambulanceCalls/${REAL}_nB`).get()).exists);
  await ok(REAL, 'ambulance_called', { id: 'demo-svc-x-near' });
  assert.ok(!(await db.doc(`ambulanceCalls/${REAL}_demo-svc-x-near`).get()).exists);
  await ok(DEMO_P, 'ambulance_called', { id: 'demo-svc-x-near' });
  assert.ok((await db.doc(`ambulanceCalls/${DEMO_P}_demo-svc-x-near`).get()).exists);
});

test('চালক গাড়ির জায়গা দেন (GPS): ঘরে কেটে রাখা হয়, বদল ছাড়া থাকে, জেলা বদলালে মোছে, জেলার বাইরে হলে শুধু জায়গাটা বাদ', async () => {
  clearAll();
  const D = 'base-driver';
  await user(D);
  const base = { name: 'জায়গা টেস্ট', phone: '01711000099', vehicleNo: 'V-base', types: ['general'], district: DISTRICT, upazilaId: SADAR, termsAccepted: true };
  await db.doc('ambulances/nBase').set({ ...base, ownerUid: D, status: 'approved', available: true, availableAt: Date.now(), createdAt: Date.now() });
  await ok(D, 'ambulance_save', { ...base, id: 'nBase', ...at(2, 0) });
  let s = (await db.doc('ambulances/nBase').get()).data();
  const r2 = (v) => Math.round(v * 100) / 100;
  assert.deepStrictEqual([s.lat, s.lng], [r2(at(2, 0).lat), r2(at(2, 0).lng)], '২ দশমিকে (≈১ কিমি), আসল GPS নয়');
  assert.strictEqual(s.status, 'approved', 'জায়গা দিলে আবার যাচাই নয়');
  assert.strictEqual((await ok(D, 'ambulance_mine')).items[0].hasBase, true);
  await ok(D, 'ambulance_save', { ...base, id: 'nBase', fare: '৫০০ টাকা' });
  assert.strictEqual((await db.doc('ambulances/nBase').get()).get('lat'), r2(at(2, 0).lat), 'নতুন জায়গা না দিলে আগেরটা থাকে');
  // চট্টগ্রামের জায়গা ময়মনসিংহের গাড়িতে নয় — কিন্তু ফর্ম আটকায় না: বাকিটা সেভ, জায়গা আগেরটাই
  const far = await ok(D, 'ambulance_save', { ...base, id: 'nBase', fare: '৬০০ টাকা', lat: 22.33, lng: 91.83 });
  assert.strictEqual(far.baseIgnored, true);
  s = (await db.doc('ambulances/nBase').get()).data();
  assert.strictEqual(s.fare, '৬০০ টাকা');
  assert.strictEqual(s.lat, r2(at(2, 0).lat));
  const other = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Jamalpur').bn;
  await ok(D, 'ambulance_save', { ...base, id: 'nBase', district: other, upazilaId: null });
  s = (await db.doc('ambulances/nBase').get()).data();
  assert.strictEqual(s.lat, undefined, 'জেলা বদলালে পুরোনো জায়গা মোছে');
});

// ---------------- "এখনো খালি আছেন?" ----------------

test('মনে করানো: শেষ ঘণ্টায় একবারই; জানালার বাইরে, ট্রিপে, মেয়াদ শেষ বা অনুমোদন ছাড়া নয়', async () => {
  clearAll();
  const now = Date.now();
  await amb('rDue', 'rOwn1', { name: 'জীবন মিনি', availableAt: now - 11.5 * HOUR });
  await amb('rEarly', 'rOwn2', { availableAt: now - 10 * HOUR });
  await amb('rGone', 'rOwn3', { availableAt: now - 12.5 * HOUR });
  await amb('rBusy', 'rOwn4', { available: false, availableAt: now - 11.5 * HOUR, busyAt: now - 11.5 * HOUR });
  await amb('rPend', 'rOwn5', { status: 'pending', availableAt: now - 11.5 * HOUR });
  assert.strictEqual(await AMB.remindAvailable(now), 1);
  assert.strictEqual(F.pushes.length, 1);
  const p = F.pushes[0];
  assert.strictEqual(p.uid, 'rOwn1');
  assert.strictEqual(p.title, 'জীবন মিনি — এখনো খালি আছেন?');
  assert.deepStrictEqual(p.data, { type: 'services', kind: 'ambulance', ambulanceId: 'rDue' });
  assert.strictEqual((await db.doc('ambulances/rDue').get()).get('reminderSentAt'), now);
  // পড়া প্রতিটা ডকের remindAt মোছে (পাঠানো হোক বা না হোক) — পরের খোঁজে আর পড়া হয় না; সময় না হওয়াটা থাকে
  for (const id of ['rDue', 'rGone', 'rBusy', 'rPend']) assert.strictEqual((await db.doc(`ambulances/${id}`).get()).get('remindAt'), undefined, id);
  assert.strictEqual((await db.doc('ambulances/rEarly').get()).get('remindAt'), now - 10 * HOUR + 11 * HOUR);
  // একবারই (একই পালায়)
  assert.strictEqual(await AMB.remindAvailable(now + 20 * MIN), 0);
  assert.strictEqual(F.pushes.length, 1);
  // চালকের কার্ডে কতক্ষণ বাকি
  const mine = (await ok('rOwn1', 'ambulance_mine')).items[0];
  assert.ok(mine.available);
  assert.ok(Math.abs(mine.availableUntil - (now + 30 * MIN)) < 1000, 'আর ৩০ মিনিট');
});

test('আবার চালু রাখলে নতুন পালা: মেয়াদ নতুন করে ১২ ঘণ্টা, পরের শেষ ঘণ্টায় আবার একবার', async () => {
  clearAll();
  // মনে করানো এক সেকেন্ড আগে — নবায়নের availableAt যেন একই মিলিসেকেন্ডে না পড়ে
  const now = Date.now() - 1000;
  await amb('rRenew', 'rOwnR', { name: 'নবায়ন', availableAt: now - 11.5 * HOUR });
  assert.strictEqual(await AMB.remindAvailable(now), 1);
  await ok('rOwnR', 'ambulance_available', { id: 'rRenew', available: true });
  const s = (await db.doc('ambulances/rRenew').get()).data();
  assert.ok(s.availableAt >= now && s.available === true);
  assert.strictEqual(s.remindAt, s.availableAt + 11 * HOUR, 'নতুন পালার remindAt');
  const mine = (await ok('rOwnR', 'ambulance_mine')).items[0];
  assert.ok(mine.availableUntil - Date.now() > 11.9 * HOUR, 'আবার পুরো ১২ ঘণ্টা');
  // নতুন পালার শুরুতে নয়; পরের শেষ ঘণ্টায় আবার
  assert.strictEqual(await AMB.remindAvailable(Date.now() + HOUR), 0);
  assert.strictEqual(await AMB.remindAvailable(s.availableAt + 11.5 * HOUR), 1);
  assert.strictEqual(F.pushes.length, 2);
  // "খালি নেই" করলে মনে করানো নয়
  await ok('rOwnR', 'ambulance_available', { id: 'rRenew', available: false });
  const s2 = (await db.doc('ambulances/rRenew').get()).data();
  assert.strictEqual(s2.remindAt, undefined, 'বন্ধ করলে remindAt মোছে');
  assert.strictEqual(await AMB.remindAvailable(s2.availableAt + 11.5 * HOUR), 0);
});

test('তালিকা পড়ার সাথে খোঁজ: এক সার্ভারে ১০ মিনিটে একবার; ডেমো গাড়ির খবর শুধু ডেমো চালকের কাছে', async () => {
  clearAll();
  const now = Date.now();
  await user(REAL);
  await amb('lDue', 'lOwn', { availableAt: now - 11.2 * HOUR });
  await ok(REAL, 'ambulances', { district: DISTRICT });
  await AMB.remindIdle();
  assert.deepStrictEqual(F.pushes.map((p) => p.data.ambulanceId), ['lDue'], 'তালিকা খুললেই খোঁজ');
  // ১০ মিনিটের মধ্যে আরেকটা গাড়ি শেষ ঘণ্টায় এলেও এই সার্ভার আবার খোঁজে না
  await amb('demo-svc-x-due', 'demo-x-drv2', { name: 'ডেমো মনে করানো', availableAt: now - 11.6 * HOUR });
  await ok(REAL, 'ambulances', { district: DISTRICT, upazilaId: SADAR });
  await AMB.remindIdle();
  assert.strictEqual(F.pushes.length, 1);
  // সময় পেরোলে (এখানে memcache মুছে) আবার — ডেমো গাড়ির খবর ডেমো চালকের কাছেই
  M.clear('');
  await ok(REAL, 'ambulances', { district: DISTRICT });
  await AMB.remindIdle();
  assert.strictEqual(F.pushes.length, 2);
  assert.strictEqual(F.pushes[1].uid, 'demo-x-drv2');
  assert.strictEqual(F.pushes[1].title, 'ডেমো মনে করানো — এখনো খালি আছেন?');
  // অতিথির তালিকাতেও খোঁজ চলে (একবারের বেশি পাঠায় না)
  M.clear('');
  await ok(null, 'ambulances', { district: DISTRICT });
  await AMB.remindIdle();
  assert.strictEqual(F.pushes.length, 2);
});

test('ডেমো: ডেমো গাড়ির জায়গা আলাদা দূরত্বে, চালকের একটা গাড়ি সাড়ে ১১ ঘণ্টা আগে খালি → মনে করানো আর "আর ৩০ মিনিট"', async () => {
  clearAll();
  const OWNER = 'owner-demo-test';
  F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  F.pushes.length = 0;
  M.clear('');
  // ডেমো রোগী, GPS-এ গাজীপুর জেলার কেন্দ্রে (ডেমো এখন গাজীপুরে — পারভেজ, ১৯ অক্টোবর)
  const GZ = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur');
  const GZP = GZ.bn;
  assert.strictEqual((await ok('demo-patient', 'ambulances', { district: DISTRICT, lat: MYM.lat, lng: MYM.lng, gps: true })).items.filter((a) => a.id.startsWith('demo-')).length, 0, 'ময়মনসিংহে ডেমো গাড়ি নেই');
  const r = await ok('demo-patient', 'ambulances', { district: GZP, lat: GZ.lat, lng: GZ.lng, gps: true });
  await AMB.remindIdle();
  const km = r.items.filter((a) => a.available).map((a) => a.km);
  assert.ok(new Set(km).size >= 4, `আলাদা দূরত্ব: ${km}`);
  const exact = r.items.filter((a) => a.available && !a.kmApprox).map((a) => a.km);
  assert.deepStrictEqual(exact, [...exact].sort((a, b) => a - b), 'কাছেরটা আগে');
  const near = r.items.find((a) => a.id === r.nearestId);
  assert.ok(near && near.available && !near.types.every((t) => t === 'freezer'));
  assert.ok(r.items.find((a) => a.id === 'demo-svc-amb-freezer').kmApprox, 'ফ্রিজার ভ্যানের জায়গা দেওয়া নেই');
  // তালিকা খোলায় ডেমো চালক মনে করানো পেলেন (সাড়ে ১১ ঘণ্টা আগের মিনি)
  assert.deepStrictEqual(F.pushes.map((p) => [p.uid, p.data.ambulanceId]), [['demo-ambulance', 'demo-svc-amb-mini']]);
  const mini = (await ok('demo-ambulance', 'ambulance_mine')).items.find((a) => a.id === 'demo-svc-amb-mini');
  const left = Math.round((mini.availableUntil - Date.now()) / MIN);
  assert.ok(left > 25 && left <= 30, `আর ${left} মিনিট`);
  for (let i = 0; i < 5; i++) if (!(await ok(OWNER, 'admin_demo_wipe')).more) break;
});

test('দূরত্বের হিসাব geo.distanceKm-এর সাথে মেলে', () => {
  const a = at(0, 0);
  const b = at(1, 0);
  assert.strictEqual(Math.round(geo.distanceKm(a.lat, a.lng, b.lat, b.lng) * 10) / 10, 1);
});

// ---------------- রিভিউয়ের পরের ঠিক করা (১৮ অক্টোবর) ----------------

test('গোপনীয়তা: চালকের আসল GPS রাখা হয় না; একই ~১ কিমির ঘরের নানা জায়গা থেকে চাইলে হুবহু একই উত্তর (ত্রিভুজে কিছু মেলে না)', async () => {
  clearAll();
  await user(REAL);
  const D = 'priv-driver';
  await user(D);
  const form = { name: 'গোপন জায়গা', phone: '01711000098', vehicleNo: 'V-priv', types: ['general'], district: DISTRICT, upazilaId: SADAR, termsAccepted: true };
  await db.doc('ambulances/pv').set({ ...form, ownerUid: D, status: 'approved', available: true, availableAt: Date.now(), createdAt: Date.now() });
  await ok(D, 'ambulance_save', { ...form, id: 'pv', lat: 24.75341, lng: 90.41893 });
  const s = (await db.doc('ambulances/pv').get()).data();
  assert.deepStrictEqual([s.lat, s.lng], [24.75, 90.42]);
  // পুরোনো (৩ দশমিকে রাখা) জায়গাও পড়ার সময় ঘরে কাটা
  await amb('pOld', 'pOldDrv', { base: { lat: 24.783, lng: 90.407 } });
  // একই ঘরের (২৪.৭৪৫–২৪.৭৫৫, ৯০.৪০৫–৯০.৪১৫) পাঁচ জায়গা থেকে
  const probes = [[24.7451, 90.4051], [24.7549, 90.4149], [24.75, 90.41], [24.7462, 90.4138], [24.7537, 90.4066]];
  const seen = [];
  for (const [lat, lng] of probes) {
    const r = await list(REAL, { lat, lng, gps: true });
    seen.push(JSON.stringify(r.items.map((a) => [a.id, a.km])));
  }
  assert.strictEqual(new Set(seen).size, 1, 'ঘরের ভেতরে কোথা থেকে চাওয়া হলো তাতে কিছু বদলায় না');
  const by = Object.fromEntries(JSON.parse(seen[0]));
  assert.strictEqual(by.pv, 1);
  assert.strictEqual(by.pOld, 3);
  // মালিকের তালিকাতেও স্থানাঙ্ক নেই, শুধু আছে কি না
  const mine = (await ok(D, 'ambulance_mine')).items[0];
  assert.strictEqual(mine.hasBase, true);
  assert.strictEqual(mine.lat, undefined);
});

test('"জায়গা মুছুন": clearBase দিলে জায়গা মোছে (নতুন lat/lng দিলেও নয়), জেলার কেন্দ্র ধরা হয়; ১৩০ কিমির ভেতর চলে', async () => {
  clearAll();
  const D = 'clr-driver';
  await user(D);
  const form = { name: 'মুছুন টেস্ট', phone: '01711000097', vehicleNo: 'V-clr', types: ['general'], district: DISTRICT, upazilaId: SADAR, termsAccepted: true };
  await db.doc('ambulances/cl').set({ ...form, ownerUid: D, status: 'approved', available: true, availableAt: Date.now(), createdAt: Date.now() });
  // জেলার কেন্দ্র থেকে ১১০ কিমি (আগের ৮০ কিমির নিয়মে বাদ পড়ত)
  const r = await ok(D, 'ambulance_save', { ...form, id: 'cl', ...at(0, -110) });
  assert.ok(!r.baseIgnored);
  assert.strictEqual(typeof (await db.doc('ambulances/cl').get()).get('lat'), 'number');
  // ১৪০ কিমি — বাদ, কিন্তু সেভ হয়
  assert.strictEqual((await ok(D, 'ambulance_save', { ...form, id: 'cl', ...at(0, -140) })).baseIgnored, true);
  await ok(D, 'ambulance_save', { ...form, id: 'cl', clearBase: true, ...at(1, 1) });
  const s = (await db.doc('ambulances/cl').get()).data();
  assert.strictEqual(s.lat, undefined);
  assert.strictEqual(s.lng, undefined);
  assert.strictEqual(s.status, 'approved');
  assert.strictEqual((await ok(D, 'ambulance_mine')).items[0].hasBase, false);
  M.clear('');
  const l = await ok(REAL, 'ambulances', { district: DISTRICT, ...gps(0, 0) });
  assert.strictEqual(l.items.find((a) => a.id === 'cl').kmApprox, true);
});

test('GPS ছাড়া স্থানাঙ্ক (gps: true নেই): আনুমানিক — নিজের উপজেলা আগে, km নেই; GPS হলে কাছেরটা আগে', async () => {
  await seedNear();
  // ত্রিশালের মানুষ, স্থানাঙ্ক জেলার কেন্দ্রের (আনুমানিক) — ত্রিশালের গাড়ি (ডেল্টা) খালিগুলোর মধ্যে আগে
  const approx = await list(REAL, { ...at(0, 0), upazilaId: TRISHAL });
  assert.strictEqual(approx.items[0].id, 'nD');
  assert.ok(approx.items.every((a) => a.km === undefined && a.kmApprox === undefined), 'আনুমানিক জায়গায় দূরত্ব দেখানো অর্থহীন');
  // বাকিরা দূরত্ব ধরে
  assert.deepStrictEqual(ids(approx).slice(3), ['nB', 'nA', 'nE']);
  // আসল GPS হলে দূরত্বই আগে (ডেল্টার জায়গা জানা নেই → পেছনে)
  const real = await list(REAL, { ...gps(0, 0), upazilaId: TRISHAL });
  assert.notStrictEqual(real.items[0].id, 'nD');
  assert.ok(ids(real).indexOf('nD') > ids(real).indexOf('nA'));
  // উপজেলা না দিলে আনুমানিক স্থানাঙ্কেও দূরত্বে সাজানো (km ছাড়া)
  const noUpa = await list(REAL, at(0, 0));
  assert.deepStrictEqual(ids(noUpa).slice(2), ['nB', 'nA', 'nD', 'nE']);
});

test('তালিকা মনে করানোর জন্য অপেক্ষা করে না: খোঁজ আটকে থাকলেও তালিকা আসে, পরে পুশ যায়', async () => {
  clearAll();
  await user(REAL);
  await amb('slow', 'slowOwn', { availableAt: Date.now() - 11.5 * HOUR });
  const orig = db.runTransaction;
  let release;
  const gate = new Promise((r) => { release = r; });
  db.runTransaction = async (fn) => { await gate; return orig.call(db, fn); };
  try {
    M.clear('');
    const r = await Promise.race([
      ok(REAL, 'ambulances', { district: DISTRICT }),
      new Promise((_, rej) => setTimeout(() => rej(new Error('তালিকা মনে করানোর জন্য আটকে আছে')), 1000)),
    ]);
    assert.deepStrictEqual(ids(r), ['slow']);
    assert.strictEqual(F.pushes.length, 0, 'খোঁজ তখনো চলছে');
  } finally {
    release();
    db.runTransaction = orig;
  }
  await AMB.remindIdle();
  assert.deepStrictEqual(F.pushes.map((p) => p.data.ambulanceId), ['slow']);
});

test('মনে করানোর কোয়েরি: remindAt <= এখন, পাতায় পাতায় (২৩০টা) — সবাই একবার, remindAt মোছে; বন্ধ/যাচাইয়ে গেলে remindAt নেই', async () => {
  clearAll();
  const now = Date.now();
  for (let i = 0; i < 230; i++) {
    const id = `pg${String(i).padStart(3, '0')}`;
    await db.doc(`ambulances/${id}`).set({
      name: `পাতা ${i}`, phone: '01711000000', vehicleNo: `V-${id}`, types: ['general'], district: DISTRICT, upazilaId: SADAR, ownerUid: 'pgOwn',
      status: 'approved', available: true, availableAt: now - 11.5 * HOUR, remindAt: now - 0.5 * HOUR, createdAt: now - 30 * 24 * HOUR,
    });
  }
  await user('pgOwn');
  assert.strictEqual(await AMB.remindAvailable(now), 230);
  assert.strictEqual(F.pushes.length, 230);
  assert.ok([...F.store.keys()].filter((k) => k.startsWith('ambulances/pg')).every((k) => F.store.get(k).remindAt === undefined));
  assert.strictEqual(await AMB.remindAvailable(now + MIN), 0);

  // অনুমোদন ছাড়া গাড়ি "খালি আছি" চাপলে remindAt বসে না; অনুমোদিত হলে বসে; যাচাইয়ে গেলে মোছে
  const D = 'ra-driver';
  await user(D);
  const form = { name: 'রিমাইন্ড টেস্ট', phone: '01711000096', vehicleNo: 'V-ra', types: ['general'], district: DISTRICT, upazilaId: SADAR, termsAccepted: true };
  await db.doc('ambulances/ra').set({ ...form, ownerUid: D, status: 'pending', available: false, createdAt: now });
  await ok(D, 'ambulance_available', { id: 'ra', available: true });
  assert.strictEqual((await db.doc('ambulances/ra').get()).get('remindAt'), undefined);
  await db.doc('ambulances/ra').update({ status: 'approved' });
  await ok(D, 'ambulance_available', { id: 'ra', available: true });
  const x = (await db.doc('ambulances/ra').get()).data();
  assert.strictEqual(x.remindAt, x.availableAt + 11 * HOUR);
  await ok(D, 'ambulance_save', { ...form, id: 'ra', vehicleNo: 'V-ra-2' });
  const y = (await db.doc('ambulances/ra').get()).data();
  assert.strictEqual(y.status, 'pending');
  assert.strictEqual(y.remindAt, undefined, 'যাচাইয়ে গেলে মনে করানো নয়');
});

test('পুরোনো খালি গাড়ি (remindAt নেই): দৈনিক ক্রনের backfill-এ বসে আর শেষ ঘণ্টায় হলে তখনই পুশ', async () => {
  clearAll();
  const now = Date.now();
  await amb('old1', 'oldOwn1', { name: 'পুরোনো', availableAt: now - 11.5 * HOUR, remindAt: null });
  await amb('old2', 'oldOwn2', { availableAt: now - 3 * HOUR, remindAt: null });
  await amb('oldOff', 'oldOwn3', { available: false, availableAt: now - 3 * HOUR, busyAt: now - 3 * HOUR, remindAt: null });
  assert.strictEqual(await AMB.remindAvailable(now), 0, 'remindAt ছাড়া সাধারণ খোঁজে পড়েই না');
  assert.strictEqual(await AMB.remindAvailable(now, { backfill: true }), 1);
  assert.deepStrictEqual(F.pushes.map((p) => p.data.ambulanceId), ['old1']);
  assert.strictEqual((await db.doc('ambulances/old2').get()).get('remindAt'), now - 3 * HOUR + 11 * HOUR);
  assert.strictEqual((await db.doc('ambulances/oldOff').get()).get('remindAt'), undefined);
  // আবার backfill — একবার পাঠানোটায় আর বসে না
  assert.strictEqual(await AMB.remindAvailable(now + MIN, { backfill: true }), 0);
  assert.strictEqual((await db.doc('ambulances/old1').get()).get('remindAt'), undefined);
});

test('অ্যাডমিনের "কী বদলেছে": জায়গা আর মনে করানোর ঘর (lat/lng/remindAt/reminderSentAt) দেখায় না', () => {
  const LR = require(path.join(SERVER, 'lib/listingreview'));
  const x = {
    name: 'ক', phone: '01711000000', lat: 24.75, lng: 90.42, remindAt: 5, reminderSentAt: 4,
    lastApproved: { name: 'ক', phone: '01711000000', lat: 24.783, lng: 90.407 },
  };
  assert.deepStrictEqual(LR.changedFields(x), []);
  assert.deepStrictEqual(LR.changedFields({ ...x, phone: '01811000000' }).map((c) => c.field), ['phone']);
  assert.deepStrictEqual(Object.keys(LR.snapshot(x)).sort(), ['name', 'phone']);
});
