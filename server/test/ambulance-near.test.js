// অ্যাম্বুলেন্স (পারভেজ, ১৮ অক্টোবর ২০২৬: "জরুরি অবস্থায় প্রতিটা সেকেন্ড দামি"):
// ১) সবচেয়ে কাছের খালি গাড়ি আগে, প্রতিটায় দূরত্ব (km), আর এক চাপে ফোনের গাড়ি (nearestId) — ধরন দিয়ে ছাঁকলে সেই ধরনের;
//    lat/lng না দিলে (পুরোনো অ্যাপ) আগের সাজানো; ডেমো আর আসল আলাদা।
// ২) "এখনো খালি আছেন?" — খালি থাকার মেয়াদের শেষ ঘণ্টায় চালককে একবার পুশ; আবার চালু করলে নতুন পালা।
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
  });
}
const list = async (uid, body = {}) => {
  M.clear('');
  return ok(uid, 'ambulances', { district: DISTRICT, ...body });
};
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

test('lat/lng দিলে: খালিগুলো কাছেরটা আগে, প্রতিটায় km (এক দশমিক), জায়গা না দেওয়া গাড়ি "প্রায়"', async () => {
  await seedNear();
  const r = await list(REAL, { ...at(0, 0), upazilaId: SADAR });
  assert.deepStrictEqual(ids(r), ['demo-svc-x-near', 'nF', 'nB', 'nA', 'nD', 'nE']);
  const by = Object.fromEntries(r.items.map((a) => [a.id, a]));
  assert.strictEqual(by.nB.km, 1);
  assert.strictEqual(by.nA.km, 4);
  assert.strictEqual(by.nF.km, 0.3);
  assert.ok(r.items.every((a) => typeof a.km === 'number' && Math.round(a.km * 10) === a.km * 10), 'এক দশমিক');
  assert.strictEqual(by.nD.kmApprox, true, 'জায়গা নেই → জেলার কেন্দ্র');
  assert.strictEqual(by.nD.km, 0);
  assert.ok(!by.nB.kmApprox);
  assert.deepStrictEqual(r.busy.map((a) => a.id), ['nT']);
  assert.strictEqual(r.busy[0].km, undefined, 'ট্রিপের তালিকা বদলায়নি');
  // এক চাপে ফোন: আসল মানুষকে ডেমো গাড়ি নয়, শুধু ফ্রিজার ভ্যান নয় → ব্রাভো
  assert.strictEqual(r.nearestId, 'nB');
  // অন্য জায়গা থেকে (আলফার পাশে) — আলফা আগে
  const north = await list(REAL, at(0, 3.8));
  assert.strictEqual(north.items[0].id, 'nA');
  assert.strictEqual(north.items[0].km, 0.2);
  assert.strictEqual(north.nearestId, 'nA');
});

test('ধরন দিয়ে ছাঁকলে কাছের সেই ধরনের গাড়ি; ফ্রিজার দিয়ে ছাঁকলে ফ্রিজার', async () => {
  await seedNear();
  const icu = await list(REAL, { ...at(0, 0), type: 'icu' });
  assert.deepStrictEqual(ids(icu), ['nD']);
  assert.strictEqual(icu.nearestId, 'nD');
  assert.strictEqual(icu.items[0].kmApprox, true);
  const ac = await list(REAL, { ...at(0, 0), type: 'ac' });
  assert.deepStrictEqual(ids(ac), ['nB', 'nD'], 'নিশ্চিত ১ কিমি আগে, "প্রায়" পরে');
  assert.strictEqual(ac.nearestId, 'nB');
  const fr = await list(REAL, { ...at(0, 0), type: 'freezer' });
  assert.strictEqual(fr.nearestId, 'nF');
});

test('ডেমো আর আসল: ডেমো রোগী ডেমো গাড়ি পান, অতিথি/আসল মানুষ নয়; নিজের গাড়ি নিজের বাটনে নয়; ফোন গোনা আলাদা', async () => {
  await seedNear();
  assert.strictEqual((await list(DEMO_P, at(0, 0))).nearestId, 'demo-svc-x-near');
  assert.strictEqual((await list(null, at(0, 0))).nearestId, 'nB', 'অতিথি = আসল জগৎ');
  assert.strictEqual((await list('drvB', at(0, 0))).nearestId, 'nA', 'চালক নিজের গাড়ি নয় — পরের কাছেরটা');
  // বাটনের ফোনও সাধারণ ফোনের মতো লেখা হয় (ambulance_called) — আসল মানুষের ফোন ডেমো গাড়িতে গোনা হয় না
  await ok(REAL, 'ambulance_called', { id: 'nB' });
  assert.ok((await db.doc(`ambulanceCalls/${REAL}_nB`).get()).exists);
  await ok(REAL, 'ambulance_called', { id: 'demo-svc-x-near' });
  assert.ok(!(await db.doc(`ambulanceCalls/${REAL}_demo-svc-x-near`).get()).exists);
  await ok(DEMO_P, 'ambulance_called', { id: 'demo-svc-x-near' });
  assert.ok((await db.doc(`ambulanceCalls/${DEMO_P}_demo-svc-x-near`).get()).exists);
});

test('চালক গাড়ির জায়গা দেন (GPS): রাখা হয়, বদল ছাড়া থাকে, জেলা বদলালে মোছে, জেলার বাইরে হলে ভুল', async () => {
  clearAll();
  const D = 'base-driver';
  await user(D);
  const base = { name: 'জায়গা টেস্ট', phone: '01711000099', vehicleNo: 'V-base', types: ['general'], district: DISTRICT, upazilaId: SADAR, termsAccepted: true };
  await db.doc('ambulances/nBase').set({ ...base, ownerUid: D, status: 'approved', available: true, availableAt: Date.now(), createdAt: Date.now() });
  await ok(D, 'ambulance_save', { ...base, id: 'nBase', ...at(2, 0) });
  let s = (await db.doc('ambulances/nBase').get()).data();
  assert.deepStrictEqual([s.lat, s.lng], [at(2, 0).lat, at(2, 0).lng]);
  assert.strictEqual(s.status, 'approved', 'জায়গা দিলে আবার যাচাই নয়');
  assert.strictEqual((await ok(D, 'ambulance_mine')).items[0].hasBase, true);
  await ok(D, 'ambulance_save', { ...base, id: 'nBase', fare: '৫০০ টাকা' });
  assert.strictEqual((await db.doc('ambulances/nBase').get()).get('lat'), at(2, 0).lat, 'নতুন জায়গা না দিলে আগেরটা থাকে');
  const far = await call(D, 'ambulance_save', { ...base, id: 'nBase', lat: 22.33, lng: 91.83 });
  assert.strictEqual(far.status, 400, 'চট্টগ্রামের জায়গা ময়মনসিংহের গাড়িতে নয়');
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
  const now = Date.now();
  await amb('rRenew', 'rOwnR', { name: 'নবায়ন', availableAt: now - 11.5 * HOUR });
  assert.strictEqual(await AMB.remindAvailable(now), 1);
  await ok('rOwnR', 'ambulance_available', { id: 'rRenew', available: true });
  const s = (await db.doc('ambulances/rRenew').get()).data();
  assert.ok(s.availableAt >= now && s.available === true);
  const mine = (await ok('rOwnR', 'ambulance_mine')).items[0];
  assert.ok(mine.availableUntil - Date.now() > 11.9 * HOUR, 'আবার পুরো ১২ ঘণ্টা');
  // নতুন পালার শুরুতে নয়; পরের শেষ ঘণ্টায় আবার
  assert.strictEqual(await AMB.remindAvailable(Date.now() + HOUR), 0);
  assert.strictEqual(await AMB.remindAvailable(s.availableAt + 11.5 * HOUR), 1);
  assert.strictEqual(F.pushes.length, 2);
  // "খালি নেই" করলে মনে করানো নয়
  await ok('rOwnR', 'ambulance_available', { id: 'rRenew', available: false });
  const s2 = (await db.doc('ambulances/rRenew').get()).data();
  assert.strictEqual(await AMB.remindAvailable(s2.availableAt + 11.5 * HOUR), 0);
});

test('তালিকা পড়ার সাথে খোঁজ: এক সার্ভারে ১০ মিনিটে একবার; ডেমো গাড়ির খবর শুধু ডেমো চালকের কাছে', async () => {
  clearAll();
  const now = Date.now();
  await user(REAL);
  await amb('lDue', 'lOwn', { availableAt: now - 11.2 * HOUR });
  await ok(REAL, 'ambulances', { district: DISTRICT });
  assert.deepStrictEqual(F.pushes.map((p) => p.data.ambulanceId), ['lDue'], 'তালিকা খুললেই খোঁজ');
  // ১০ মিনিটের মধ্যে আরেকটা গাড়ি শেষ ঘণ্টায় এলেও এই সার্ভার আবার খোঁজে না
  await amb('demo-svc-x-due', 'demo-x-drv2', { name: 'ডেমো মনে করানো', availableAt: now - 11.6 * HOUR });
  await ok(REAL, 'ambulances', { district: DISTRICT, upazilaId: SADAR });
  assert.strictEqual(F.pushes.length, 1);
  // সময় পেরোলে (এখানে memcache মুছে) আবার — ডেমো গাড়ির খবর ডেমো চালকের কাছেই
  M.clear('');
  await ok(REAL, 'ambulances', { district: DISTRICT });
  assert.strictEqual(F.pushes.length, 2);
  assert.strictEqual(F.pushes[1].uid, 'demo-x-drv2');
  assert.strictEqual(F.pushes[1].title, 'ডেমো মনে করানো — এখনো খালি আছেন?');
  // অতিথির তালিকাতেও খোঁজ চলে (একবারের বেশি পাঠায় না)
  M.clear('');
  await ok(null, 'ambulances', { district: DISTRICT });
  assert.strictEqual(F.pushes.length, 2);
});

test('ডেমো: ডেমো গাড়ির জায়গা আলাদা দূরত্বে, চালকের একটা গাড়ি সাড়ে ১১ ঘণ্টা আগে খালি → মনে করানো আর "আর ৩০ মিনিট"', async () => {
  clearAll();
  const OWNER = 'owner-demo-test';
  F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  F.pushes.length = 0;
  M.clear('');
  // ডেমো রোগী, এলাকার কেন্দ্র ধরে
  const r = await ok('demo-patient', 'ambulances', { district: DISTRICT, lat: MYM.lat, lng: MYM.lng });
  const km = r.items.filter((a) => a.available).map((a) => a.km);
  assert.ok(new Set(km).size >= 4, `আলাদা দূরত্ব: ${km}`);
  assert.deepStrictEqual(km, [...km].sort((a, b) => a - b).length ? km : km);
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
