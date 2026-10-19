// অ্যাম্বুলেন্স এক চাপে ডাকুন (২.২৮.০, পারভেজ "বানা", ১৯ অক্টোবর ২০২৬):
// কাছের খালি গাড়িতে একসাথে অনুরোধ, আগে রাজি হওয়া চালক পায়, ৬০ সেকেন্ডে কেউ না নিলে পরিধি একবার বাড়ে আর কাছের ৩টায়
// ফোনের বাটন, ৩ মিনিটে সময় শেষ; রোগী/চালক বাতিল, পৌঁছেছি, শেষ → রেটিং; গোপনীয়তা, সীমা, ডেমো আলাদা।
// সাথে ফোন আর অনুরোধ একসাথে (পারভেজের প্রশ্ন): ফোন করা গাড়ি অনুরোধে ওঠে, পরিধি বাড়ানোয় বাদ, byPhone বন্ধ, রাজি চালক বদল।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const clock = require('./support/clock');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const AR = require(path.join(SERVER, 'lib/ambreq.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const { db } = F.firebase;

const DISTRICTS = require(path.join(SERVER, 'lib/districts'));
const MYM = DISTRICTS.find((d) => d.en === 'Mymensingh');
const DISTRICT = MYM.bn;
const SADAR = 466;
const HOUR = 3600 * 1000;
const SEC = 1000;

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.8.0.${++ip % 250}` };
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
  await db.doc(`users/${uid}`).set({ name: `নাম ${uid}`, lang: 'bn', phone: '01811000000', ...x });
};
/** জেলার কেন্দ্র থেকে dx কিমি পূর্বে, dy কিমি উত্তরে */
const at = (dx, dy) => ({
  lat: Math.round((MYM.lat + dy / 111) * 1e5) / 1e5,
  lng: Math.round((MYM.lng + dx / (111 * Math.cos((MYM.lat * Math.PI) / 180))) * 1e5) / 1e5,
});
async function amb(id, owner, x = {}) {
  await user(owner);
  const base = x.base ? { lat: Math.round(x.base.lat * 100) / 100, lng: Math.round(x.base.lng * 100) / 100 } : {};
  await db.doc(`ambulances/${id}`).set({
    name: x.name || `গাড়ি ${id}`, phone: x.phone || '01711000000', vehicleNo: `V-${id}`, types: x.types || ['general'], district: x.district || DISTRICT,
    upazilaId: x.upazilaId || SADAR, area: '', fare: '', ownerUid: owner, status: x.status || 'approved',
    available: x.available !== false, availableAt: x.availableAt ?? Date.now() - HOUR, ratingSum: 0, ratingCount: 0,
    ...(x.busyAt ? { busyAt: x.busyAt } : {}), ...base, createdAt: Date.now() - 30 * 24 * HOUR,
  });
}
const clearAll = () => {
  for (const k of [...F.store.keys()]) if (/^(ambulances|users|ambulanceCalls|ambulanceStats|ambulanceRequests|ambReqLimits|inbox)\//.test(k)) F.store.delete(k);
  F.pushes.length = 0;
  M.clear('');
};
const P = 'req-patient';
const gps = (dx, dy) => ({ ...at(dx, dy), gps: true });
const ask = (uid, body = {}) => call(uid, 'ambreq_create', { district: DISTRICT, upazilaId: SADAR, ...gps(0, 0), pickup: 'চরপাড়া মোড়, মেডিকেল গেট', note: 'রোগী হাঁটতে পারেন না', phone: '01811000000', ...body });
const reqDoc = async (id) => (await db.doc(`ambulanceRequests/${id}`).get()).data();
const pushedTo = () => F.pushes.map((p) => p.uid);
const driverList = async (uid) => (await ok(uid, 'ambreq_driver')).items;

/** ১ থেকে ৮ কিমি দূরে ৮টা গাড়ি (d1…d8), আলাদা মালিক; সাথে নানা বাদ পড়ার গাড়ি */
async function seed() {
  clearAll();
  clock.reset();
  await user(P);
  for (let i = 1; i <= 8; i++) await amb(`a${i}`, `d${i}`, { base: at(i, 0), name: `গাড়ি ${i}` });
  // বাদ: ট্রিপে (০.৫ কিমি), শুধু ফ্রিজার (০.৩ কিমি), অনুমোদন ছাড়া, অন্য জেলা, ডেমো, রোগীর নিজের
  await amb('xBusy', 'dBusy', { base: at(0.5, 0), available: false, busyAt: Date.now() - HOUR });
  await amb('xFreezer', 'dFreezer', { base: at(0.3, 0), types: ['freezer'] });
  await amb('xPending', 'dPending', { base: at(0.2, 0), status: 'pending' });
  await amb('xOther', 'dOther', { base: at(0.1, 0), district: DISTRICTS.find((d) => d.en === 'Dhaka').bn });
  await amb('demo-svc-x', 'demo-x-drv', { base: at(0.1, 0.1) });
  await amb('xMine', P, { base: at(0.1, 0.2) });
  // একই মালিকের দ্বিতীয় গাড়ি (২.৫ কিমি) — মালিক একবারই
  await amb('a2b', 'd2', { base: at(2.5, 0) });
}

test('বাছাই: কাছের ৫ জন মালিক, ট্রিপে/ফ্রিজার/অনুমোদন ছাড়া/অন্য জেলা/ডেমো/নিজের বাদ, একই মালিক একবার', async () => {
  await seed();
  const r = await ask(P);
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  const x = await reqDoc(r.body.id);
  assert.deepStrictEqual(Object.keys(x.sentTo).sort(), ['d1', 'd2', 'd3', 'd4', 'd5']);
  assert.strictEqual(x.sentTo.d2, 'a2', 'মালিকের কাছের গাড়ি');
  assert.deepStrictEqual(x.km, { d1: 1, d2: 2, d3: 3, d4: 4, d5: 5 });
  assert.deepStrictEqual(pushedTo().sort(), ['d1', 'd2', 'd3', 'd4', 'd5']);
  assert.ok(F.pushes.every((p) => !/চরপাড়া|হাঁটতে/.test(p.title + p.body)), 'পুশে ঠিকানা/নোট নয় (নোটিফিকেশনের তালিকায় থাকে)');
  assert.strictEqual(r.body.request.status, 'open');
  assert.strictEqual(r.body.request.notified, 5);
});

test('বাছাই: ধরন দিলে শুধু সেই ধরন; ফ্রিজার শুধু বেছে নিলে; ১৩০ কিমির বাইরে নয়; শিটের গাড়ি আগে', async () => {
  await seed();
  await amb('xIcu', 'dIcu', { base: at(7.5, 0), types: ['icu', 'ac'] });
  const icu = await ask(P, { type: 'icu' });
  assert.deepStrictEqual(Object.keys((await reqDoc(icu.body.id)).sentTo), ['dIcu']);
  await ok(P, 'ambreq_cancel', { id: icu.body.id, reason: 'wrong' });
  const fr = await ask(P, { type: 'freezer' });
  assert.deepStrictEqual(Object.keys((await reqDoc(fr.body.id)).sentTo), ['dFreezer']);
  await ok(P, 'ambreq_cancel', { id: fr.body.id });
  // শিট থেকে ৮ কিমির গাড়ি: সেটা আগে, বাকি কাছের ৪টা
  const one = await ask(P, { ambulanceId: 'a8' });
  const x = await reqDoc(one.body.id);
  assert.deepStrictEqual(Object.keys(x.sentTo), ['d8', 'd1', 'd2', 'd3', 'd4']);
  assert.strictEqual(x.chosenId, 'a8');
  await ok(P, 'ambreq_cancel', { id: one.body.id });
  // ১৩০ কিমির বাইরে (জেলার ভেতরে তালিকাভুক্ত, কিন্তু জায়গা অনেক দূরে) — বাদ
  await seed();
  for (let i = 1; i <= 8; i++) await db.doc(`ambulances/a${i}`).update({ lat: Math.round((MYM.lat + 1.3) * 100) / 100 });
  await db.doc('ambulances/a2b').update({ lat: Math.round((MYM.lat + 1.3) * 100) / 100 });
  const far = await ask(P);
  assert.strictEqual(far.status, 409);
  assert.strictEqual(far.body.code, 'no_ambulance');
});

test('আগে যিনি "আসছি" চাপেন তিনিই পান (একসাথে চাপলেও একজন); বাকিদের তালিকা থেকে সরে; রোগী পুশ ও তথ্য পান', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  F.pushes.length = 0;
  const res = await Promise.all(['d3', 'd1', 'd2'].map((u) => call(u, 'ambreq_accept', { id })));
  assert.deepStrictEqual(res.map((r) => r.status).sort(), [200, 409, 409]);
  const winner = ['d3', 'd1', 'd2'][res.findIndex((r) => r.status === 200)];
  const x = await reqDoc(id);
  assert.strictEqual(x.status, 'accepted');
  assert.strictEqual(x.acceptedBy, winner);
  assert.deepStrictEqual(x.activeOwners, [winner]);
  assert.match(res.find((r) => r.status === 409).body.error, /অন্য একজন চালক/);
  for (const u of ['d1', 'd2', 'd3', 'd4', 'd5'].filter((u) => u !== winner)) assert.deepStrictEqual(await driverList(u), [], `${u}-এর তালিকা থেকে সরে`);
  // গাড়ি তালিকায় "ট্রিপে"
  const amb = (await db.doc(`ambulances/${x.ambulanceId}`).get()).data();
  assert.strictEqual(amb.available, false);
  assert.strictEqual(amb.reqBusy, id);
  // রোগী: গাড়ির নাম, চালকের ফোন, দূরত্ব, "আসছে" — গাড়ির নম্বর নয়
  assert.deepStrictEqual(pushedTo(), [P]);
  const v = (await ok(P, 'ambreq_status', { id })).request;
  assert.strictEqual(v.status, 'accepted');
  assert.strictEqual(v.ambulance.phone, '01711000000');
  assert.strictEqual(v.ambulance.km, Number(winner.slice(1)));
  assert.ok(!JSON.stringify(v).includes('V-a'), 'গাড়ির নম্বর রোগীকে নয়');
});

test('৬০ সেকেন্ডে কেউ না নিলে একবারই পরিধি বাড়ে (পরের ৫ জন) আর কাছের ৩টায় ফোনের বাটন; ৩ মিনিটে সময় শেষ', async () => {
  await seed();
  for (let i = 9; i <= 11; i++) await amb(`a${i}`, `d${i}`, { base: at(i, 0) });
  const { id } = (await ask(P)).body;
  F.pushes.length = 0;
  clock.advance(30 * SEC);
  let v = (await ok(P, 'ambreq_status', { id })).request;
  assert.strictEqual(v.wave, 1);
  assert.deepStrictEqual(v.fallback, [], '৬০ সেকেন্ডের আগে ফোনের বাটন নয়');
  clock.advance(31 * SEC);
  // রোগীর পড়া আর চালকের তালিকা একসাথে — তবু একবারই
  const [a, b] = await Promise.all([ok(P, 'ambreq_status', { id }), ok('d1', 'ambreq_driver'), ok(P, 'ambreq_status', { id })]);
  v = a.request;
  assert.strictEqual(v.wave, 2);
  assert.strictEqual(b.items.length, 1);
  const x = await reqDoc(id);
  assert.deepStrictEqual(Object.keys(x.sentTo).sort(), ['d1', 'd10', 'd2', 'd3', 'd4', 'd5', 'd6', 'd7', 'd8', 'd9']);
  assert.deepStrictEqual(pushedTo().sort(), ['d10', 'd6', 'd7', 'd8', 'd9'], 'নতুন ৫ জন একবারই');
  assert.deepStrictEqual(v.fallback.map((f) => f.id), ['a1', 'a2', 'a3']);
  assert.ok(v.fallback.every((f) => f.phone && Number.isInteger(f.km)));
  F.pushes.length = 0;
  await ok(P, 'ambreq_status', { id });
  await ok('d7', 'ambreq_driver');
  assert.deepStrictEqual(F.pushes, [], 'আবার পড়লে আর পাঠানো নয়');
  // ৩ মিনিটে সময় শেষ: রোগীকে পুশ, ফোনের বাটন থাকে, চালকদের তালিকা খালি
  clock.advance(2 * 60 * SEC);
  v = (await ok('d7', 'ambreq_driver')).items;
  assert.deepStrictEqual(v, []);
  v = (await ok(P, 'ambreq_status', { id })).request;
  assert.strictEqual(v.status, 'expired');
  assert.deepStrictEqual(v.fallback.map((f) => f.id), ['a1', 'a2', 'a3']);
  assert.deepStrictEqual(pushedTo(), [P]);
  assert.strictEqual((await call('d1', 'ambreq_accept', { id })).status, 409);
  // id ছাড়া অবস্থা: ১০ মিনিট পর আর "চালু" নয়
  clock.advance(11 * 60 * SEC);
  assert.strictEqual((await ok(P, 'ambreq_status', {})).request, null);
  clock.reset();
});

test('সবাই "পারব না" বললে ৬০ সেকেন্ডের আগেই পরিধি বাড়ে; "পারব না" বলা চালকের তালিকা থেকে সরে', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  await ok('d1', 'ambreq_decline', { id });
  assert.deepStrictEqual(await driverList('d1'), []);
  assert.strictEqual((await driverList('d2')).length, 1);
  for (const u of ['d2', 'd3', 'd4', 'd5']) await ok(u, 'ambreq_decline', { id });
  const x = await reqDoc(id);
  assert.strictEqual(x.wave, 2);
  assert.deepStrictEqual(x.activeOwners.sort(), ['d6', 'd7', 'd8']);
});

test('রোগী বাতিল (খোলা/রাজির পর, কারণসহ) — চালক জানেন, গাড়ি আবার খালি; চালক: পৌঁছেছি → শেষ → রেটিং সাথে সাথে; চালকের বাতিল', async () => {
  await seed();
  // খোলা অবস্থায় বাতিল
  let id = (await ask(P)).body.id;
  await ok(P, 'ambreq_cancel', { id, reason: 'not_needed' });
  let x = await reqDoc(id);
  assert.strictEqual(x.status, 'cancelled');
  assert.strictEqual(x.cancelReason, 'not_needed');
  assert.deepStrictEqual(await driverList('d1'), []);
  // রাজির পরে বাতিল
  id = (await ask(P)).body.id;
  await ok('d1', 'ambreq_accept', { id });
  F.pushes.length = 0;
  await ok(P, 'ambreq_cancel', { id, reason: 'found_other' });
  assert.deepStrictEqual(pushedTo(), ['d1']);
  let a1 = (await db.doc('ambulances/a1').get()).data();
  assert.strictEqual(a1.available, true, 'বাতিলে গাড়ি আবার খালি');
  assert.ok(a1.reqBusy === undefined && a1.busyAt === undefined);
  // রাজি → পৌঁছেছি → শেষ
  id = (await ask(P)).body.id;
  await ok('d1', 'ambreq_accept', { id });
  F.pushes.length = 0;
  await ok('d1', 'ambreq_step', { id, step: 'arrived' });
  assert.strictEqual((await ok(P, 'ambreq_status', { id })).request.status, 'arrived');
  // রেটিং: শেষের আগে নয়
  assert.strictEqual((await call(P, 'ambulance_rate', { id: 'a1', rating: 5 })).status, 403);
  await ok('d1', 'ambreq_step', { id, step: 'done' });
  assert.deepStrictEqual(pushedTo(), [P, P]);
  const v = (await ok(P, 'ambreq_status', { id })).request;
  assert.strictEqual(v.status, 'done');
  assert.strictEqual(v.canRate, true);
  a1 = (await db.doc('ambulances/a1').get()).data();
  assert.strictEqual(a1.available, true);
  assert.ok(a1.remindAt > Date.now(), '"এখনো খালি আছেন?" আবার বসে');
  // শেষ হওয়া অনুরোধ = ফোনের মতো, ১ ঘণ্টা অপেক্ষা ছাড়াই রেটিং
  const list = await ok(P, 'ambulances', { district: DISTRICT });
  assert.strictEqual(list.items.find((a) => a.id === 'a1').canRate, true);
  assert.strictEqual(list.items.find((a) => a.id === 'a2').canRate, false);
  await ok(P, 'ambulance_rate', { id: 'a1', rating: 5, comment: 'সময়মতো এসেছে' });
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('ratingCount'), 1);
  // আবার শেষ/বাতিল চাপা যায় না
  assert.strictEqual((await call('d1', 'ambreq_step', { id, step: 'cancel' })).status, 409);
  // চালকের বাতিল
  id = (await ask(P)).body.id;
  await ok('d2', 'ambreq_accept', { id });
  F.pushes.length = 0;
  await ok('d2', 'ambreq_step', { id, step: 'cancel', reason: 'vehicle_problem' });
  x = await reqDoc(id);
  assert.strictEqual(x.cancelledBy, 'driver');
  assert.strictEqual(x.cancelReason, 'vehicle_problem');
  assert.deepStrictEqual(pushedTo(), [P]);
  assert.strictEqual((await db.doc('ambulances/a2').get()).get('available'), true);
  // অন্যের অনুরোধে চালকের কাজ চলে না
  assert.strictEqual((await call('d9', 'ambreq_accept', { id })).status, 404);
  assert.strictEqual((await call('d1', 'ambreq_status', { id })).status, 404);
});

test('গোপনীয়তা: রাজির আগে চালক শুধু দূরত্ব, ঠিকানা, নোট দেখেন; রাজি চালক নাম, ফোন, ঠিক জায়গা; ২৪ ঘণ্টা পর মোছা', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  const before = (await driverList('d2'))[0];
  assert.strictEqual(before.km, 2);
  assert.strictEqual(before.pickup, 'চরপাড়া মোড়, মেডিকেল গেট');
  assert.strictEqual(before.note, 'রোগী হাঁটতে পারেন না');
  for (const k of ['lat', 'lng', 'phone', 'patientName']) assert.strictEqual(before[k], undefined, `রাজির আগে ${k} নয়`);
  await ok('d2', 'ambreq_accept', { id });
  const mine = (await driverList('d2'))[0];
  assert.strictEqual(mine.status, 'accepted');
  assert.strictEqual(mine.phone, '01811000000');
  assert.strictEqual(mine.patientName, `নাম ${P}`);
  assert.deepStrictEqual([mine.lat, mine.lng], [at(0, 0).lat, at(0, 0).lng], 'ঠিক জায়গা (ম্যাপে খুলতে)');
  // অন্য চালকের কাছে কিছুই নয়
  assert.deepStrictEqual(await driverList('d1'), []);
  assert.strictEqual((await call('d1', 'ambreq_step', { id, step: 'done' })).status, 409);
  await ok('d2', 'ambreq_step', { id, step: 'done' });
  // ২৪ ঘণ্টার আগে থাকে, পরে মোছে
  await AR.dailyCleanup(Date.now() + 23 * HOUR);
  assert.strictEqual((await reqDoc(id)).phone, '01811000000');
  const r = await AR.dailyCleanup(Date.now() + 25 * HOUR);
  assert.strictEqual(r.purged, 1);
  const x = await reqDoc(id);
  for (const k of ['lat', 'lng', 'phone', 'pickup', 'note', 'patientName', 'purgeAt']) assert.strictEqual(x[k], undefined, `${k} মোছা`);
  assert.strictEqual(x.status, 'done');
  assert.strictEqual(x.ambulanceId, 'a2', 'ইতিহাস থাকে (রেটিং)');
  // GPS ছাড়া (এলাকা ধরে): কোনো স্থানাঙ্ক রাখা হয় না, দূরত্ব নয়
  const noGps = (await ask(P, { gps: false })).body.id;
  const y = await reqDoc(noGps);
  assert.ok(y.lat === undefined && y.gps === false);
  assert.strictEqual((await driverList('d1'))[0].km, null);
});

test('সীমা: একসাথে একটাই চালু, ঘণ্টায় ৫টা; অতিথি পারেন না (ফোন তালিকা আগের মতো)', async () => {
  await seed();
  const first = (await ask(P)).body.id;
  const again = await ask(P);
  assert.strictEqual(again.status, 409);
  assert.strictEqual(again.body.code, 'amb_req_active');
  // একসাথে দুই চাপ — একটাই হয়
  await ok(P, 'ambreq_cancel', { id: first });
  const two = await Promise.all([ask(P), ask(P)]);
  assert.deepStrictEqual(two.map((r) => r.status).sort(), [200, 409]);
  await ok(P, 'ambreq_cancel', { id: two.find((r) => r.status === 200).body.id });
  // ৩ নম্বর ঘণ্টার মধ্যে; আরও ২টা ঠিক, ৬ নম্বর না
  for (let i = 0; i < 2; i++) await ok(P, 'ambreq_cancel', { id: (await ask(P)).body.id });
  assert.strictEqual((await ask(P)).status, 200);
  const sixth = await ask(P);
  assert.ok([409, 429].includes(sixth.status));
  await ok(P, 'ambreq_cancel', { id: (await ok(P, 'ambreq_status', {})).request.id });
  assert.strictEqual((await ask(P)).status, 429);
  // এক ঘণ্টা পর আবার
  clock.advance(HOUR + SEC);
  assert.strictEqual((await ask(P)).status, 200);
  clock.reset();
  // সময় পেরোনো খোলা অনুরোধ নতুনটা আটকায় না
  await seed();
  await ask(P);
  clock.advance(3 * 60 * SEC + SEC);
  assert.strictEqual((await ask(P)).status, 200);
  clock.reset();
  // অতিথি
  const g = await call(null, 'ambreq_create', { district: DISTRICT, pickup: 'x', phone: '01811000000' });
  assert.strictEqual(g.status, 401);
  assert.strictEqual((await call(null, 'ambulances', { district: DISTRICT })).status, 200);
  // ভুল তথ্য
  assert.strictEqual((await ask(P, { phone: '123' })).status, 400);
  assert.strictEqual((await ask(P, { pickup: '  ' })).status, 400);
});

test('ডেমো আর আসল আলাদা: ডেমো রোগী শুধু ডেমো গাড়িতে, আসল রোগী ডেমোতে নয়', async () => {
  await seed();
  const DP = 'demo-patient-req';
  await user(DP);
  const d = await ask(DP);
  assert.strictEqual(d.status, 200);
  const x = await reqDoc(d.body.id);
  assert.deepStrictEqual(Object.keys(x.sentTo), ['demo-x-drv']);
  assert.strictEqual(x.demo, true);
  await ok('demo-x-drv', 'ambreq_accept', { id: d.body.id });
  assert.strictEqual((await ok(DP, 'ambreq_status', { id: d.body.id })).request.status, 'accepted');
  const r = await reqDoc((await ask(P)).body.id);
  assert.ok(!Object.keys(r.sentTo).includes('demo-x-drv'));
  // একমাত্র খালি ডেমো গাড়ি এখন ট্রিপে — আসল গাড়িতে যায় না
  await user('demo-patient-req2');
  const d2 = await ask('demo-patient-req2');
  assert.strictEqual(d2.status, 409, 'আসল গাড়িতে নয়');
  assert.strictEqual(d2.body.code, 'no_ambulance');
});

// ---------- ফোন আর অনুরোধ একসাথে ----------

test('অপেক্ষার সময় ফোন: অনুরোধে ওঠে, ফোন করা চালক চিহ্নসহ তালিকায় পান ("আসছি" চাপলে সাধারণ রাজি); পরিধি বাড়ানোয় ফোন করা গাড়ি বাদ', async () => {
  await seed();
  for (let i = 9; i <= 12; i++) await amb(`a${i}`, `d${i}`, { base: at(i, 0) });
  const { id } = (await ask(P)).body;
  F.pushes.length = 0;
  // প্রথম ধাপে না থাকা গাড়ি ৬ আর ৭-এ ফোন
  await ok(P, 'ambulance_called', { id: 'a6', reqId: id });
  await ok(P, 'ambulance_called', { id: 'a7', reqId: id });
  await ok(P, 'ambulance_called', { id: 'a6', reqId: id });
  assert.deepStrictEqual(pushedTo(), ['d6', 'd7'], 'প্রথম ফোনে একবার পুশ');
  let x = await reqDoc(id);
  assert.deepStrictEqual(x.calledIds, ['a6', 'a7']);
  assert.strictEqual(x.km.d6, 6);
  const d6 = (await driverList('d6'))[0];
  assert.strictEqual(d6.called, true, '"রোগী আপনাকে ফোন করেছেন"');
  assert.strictEqual((await driverList('d1'))[0].called, false);
  // ফোনের রেকর্ডও আগের মতো (রেটিং)
  assert.ok((await db.doc(`ambulanceCalls/${P}_a6`).get()).exists);
  // পরিধি বাড়ানো: ফোন করা ৬, ৭ বাদ → ৮, ৯, ১০, ১১, ১২
  F.pushes.length = 0;
  clock.advance(61 * SEC);
  await ok(P, 'ambreq_status', { id });
  x = await reqDoc(id);
  assert.deepStrictEqual(pushedTo().sort(), ['d10', 'd11', 'd12', 'd8', 'd9']);
  assert.strictEqual(Object.keys(x.sentTo).length, 12);
  // ফোন করা চালক অ্যাপে "আসছি" — সাধারণ রাজির মতোই
  await ok('d7', 'ambreq_accept', { id });
  x = await reqDoc(id);
  assert.strictEqual(x.status, 'accepted');
  assert.strictEqual(x.ambulanceId, 'a7');
  assert.strictEqual((await call('d6', 'ambreq_accept', { id })).status, 409);
  // অন্য রোগীর অনুরোধে ফোন জোড়া যায় না
  await user('other-p');
  await ok('other-p', 'ambulance_called', { id: 'a1', reqId: id });
  assert.deepStrictEqual((await reqDoc(id)).calledIds, ['a6', 'a7']);
  clock.reset();
});

test('ফোনে গাড়ি পেয়েছেন → byPhone: বাকি সব চালকের তালিকা থেকে সরে, পুশ "আর দরকার নেই"; এরপর "আসছি" চাপলে পরিষ্কার বার্তা; রেটিংয়ের রেকর্ড', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  await ok(P, 'ambulance_called', { id: 'a6', reqId: id });
  // ফোন না করা গাড়ি বাছা যায় না
  assert.strictEqual((await call(P, 'ambreq_byphone', { id, ambulanceId: 'a1' })).status, 400);
  F.pushes.length = 0;
  const v = (await ok(P, 'ambreq_byphone', { id, ambulanceId: 'a6' })).request;
  assert.strictEqual(v.status, 'byPhone');
  assert.strictEqual(v.ambulance.id, 'a6');
  assert.deepStrictEqual(pushedTo().sort(), ['d1', 'd2', 'd3', 'd4', 'd5'], 'বুক করা চালক (d6) বাদে সবাই');
  assert.ok(F.pushes.every((p) => p.title === 'রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই'));
  for (const u of ['d1', 'd6']) assert.deepStrictEqual(await driverList(u), []);
  const late = await call('d1', 'ambreq_accept', { id });
  assert.strictEqual(late.status, 409);
  assert.strictEqual(late.body.error, 'রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন — আর দরকার নেই');
  assert.strictEqual((await call('d6', 'ambreq_accept', { id })).status, 409);
  const c = (await db.doc(`ambulanceCalls/${P}_a6`).get()).data();
  assert.strictEqual(c.byPhoneReq, id);
  // নতুন অনুরোধ খোলা যায় (আগেরটা বন্ধ)
  assert.strictEqual((await ask(P)).status, 200);
});

test('রাজি চালক বদল: "ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল" — byPhone বা আবার খোলা; এক সময়ে একটাই রাজি', async () => {
  await seed();
  // (ক) অন্য গাড়ি বেছে byPhone
  let id = (await ask(P)).body.id;
  await ok(P, 'ambulance_called', { id: 'a7', reqId: id });
  await ok('d1', 'ambreq_accept', { id });
  let v = (await ok(P, 'ambreq_status', { id })).request;
  assert.strictEqual(v.status, 'accepted');
  assert.deepStrictEqual(v.called.map((c) => c.id), ['a7'], 'রাজি অবস্থায় ফোন করা গাড়ির তালিকা');
  assert.strictEqual((await call(P, 'ambreq_byphone', { id, ambulanceId: 'a7' })).status, 409, 'রাজি থাকলে replace দিয়ে');
  F.pushes.length = 0;
  v = (await ok(P, 'ambreq_replace', { id, ambulanceId: 'a7' })).request;
  assert.strictEqual(v.status, 'byPhone');
  assert.strictEqual(v.ambulance.id, 'a7');
  assert.deepStrictEqual(pushedTo(), ['d1']);
  assert.match(F.pushes[0].title, /দুঃখিত — রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন/);
  let x = await reqDoc(id);
  assert.strictEqual(x.acceptedBy, undefined);
  assert.strictEqual(x.replaced[0].reason, 'patient_found_other');
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('available'), true, 'বাতিল চালকের গাড়ি আবার খালি');
  assert.deepStrictEqual(await driverList('d1'), []);

  // (খ) গাড়ি না বেছে: আবার খোলা, সময় নতুন করে, বাতিল চালক বাদ, বাকিদের আবার পুশ
  id = (await ask(P)).body.id;
  await ok(P, 'ambulance_called', { id: 'a7', reqId: id });
  await ok('d2', 'ambreq_accept', { id });
  clock.advance(2 * 60 * SEC + 50 * SEC);
  F.pushes.length = 0;
  v = (await ok(P, 'ambreq_replace', { id })).request;
  assert.strictEqual(v.status, 'open');
  assert.ok(v.expiresAt > Date.now() + 2 * 60 * SEC, 'সময় নতুন করে');
  assert.strictEqual(pushedTo()[0], 'd2', 'বাতিল চালক আগে ভদ্র বার্তা');
  assert.deepStrictEqual(pushedTo().slice(1).sort(), ['d1', 'd3', 'd4', 'd5', 'd7']);
  assert.deepStrictEqual(await driverList('d2'), []);
  assert.strictEqual((await call('d2', 'ambreq_accept', { id })).status, 404, 'বাতিল চালক আর নয়');
  assert.strictEqual((await driverList('d3')).length, 1);
  // এক সময়ে একটাই রাজি: একসাথে দুই চালকের "আসছি" আর রোগীর byPhone
  const res = await Promise.all([call('d3', 'ambreq_accept', { id }), call('d4', 'ambreq_accept', { id }), call(P, 'ambreq_byphone', { id, ambulanceId: 'a7' })]);
  assert.strictEqual(res.filter((r) => r.status === 200).length, 1, JSON.stringify(res.map((r) => r.status)));
  x = await reqDoc(id);
  assert.ok(['accepted', 'byPhone'].includes(x.status));
  const busy = (await db.collection('ambulances').where('reqBusy', '==', id).get()).size;
  assert.strictEqual(busy, x.status === 'accepted' ? 1 : 0, 'ট্রিপে শুধু একটা গাড়ি');
  clock.reset();
});

test('অ্যাকাউন্ট মোছা: রোগীর অনুরোধ বাতিল ও মোছা, চালক জানেন', async () => {
  await seed();
  const { id } = (await ask(P)).body;
  await ok('d1', 'ambreq_accept', { id });
  F.pushes.length = 0;
  await AR.closeForUser(P, { full: true });
  assert.strictEqual(await reqDoc(id), undefined);
  assert.deepStrictEqual(pushedTo(), ['d1']);
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('available'), true);
});

test('ডেমো: রোগী ডাকেন → ডেমো অ্যাম্বুলেন্স চালক "আসছি" → রোগী দেখেন "আসছে" → শেষ', async () => {
  clearAll();
  const demo = require(path.join(SERVER, 'lib/demo'));
  await demo.seed();
  const DP = 'demo-patient';
  const DA = 'demo-ambulance';
  const { lat, lng } = demo.CENTER;
  const r = await call(DP, 'ambreq_create', { district: DISTRICT, upazilaId: SADAR, lat, lng, gps: true, pickup: 'ডেমো: চরপাড়া মোড়', phone: '01700000101' });
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  const x = await reqDoc(r.body.id);
  assert.ok(Object.keys(x.sentTo).includes(DA), 'ডেমো চালকের কাছে যায়');
  assert.ok(Object.keys(x.sentTo).every((u) => u.startsWith('demo-')), 'শুধু ডেমো');
  const list = await driverList(DA);
  assert.strictEqual(list.length, 1);
  await ok(DA, 'ambreq_accept', { id: r.body.id });
  const v = (await ok(DP, 'ambreq_status', {})).request;
  assert.strictEqual(v.status, 'accepted');
  assert.match(v.ambulance.name, /ডেমো/);
  await ok(DA, 'ambreq_step', { id: r.body.id, step: 'done' });
  assert.strictEqual((await ok(DP, 'ambreq_status', { id: r.body.id })).request.canRate, true);
  await demo.wipe({ all: true });
});

test('রিভিউ: সময় পেরোনোর পরে "আসছি" নয়; ক্রনে অনেক পরে বন্ধ হলে রোগীকে পুশ নয়; চালক নিজে খালি/ব্যস্ত বদলালে ট্রিপ শেষে তা-ই থাকে', async () => {
  await seed();
  let id = (await ask(P)).body.id;
  clock.advance(3 * 60 * SEC + SEC);
  const late = await call('d1', 'ambreq_accept', { id });
  assert.strictEqual(late.status, 409);
  assert.strictEqual((await reqDoc(id)).status, 'expired');
  clock.reset();
  // ক্রন: এক দিন পরে বন্ধ — পুশ নয়
  id = (await ask(P)).body.id;
  F.pushes.length = 0;
  await AR.dailyCleanup(Date.now() + 24 * HOUR);
  assert.strictEqual((await reqDoc(id)).status, 'expired');
  assert.deepStrictEqual(F.pushes, []);
  // চালক ট্রিপের মাঝে নিজে "ব্যস্ত" রাখলেন → শেষেও ব্যস্ত
  id = (await ask(P)).body.id;
  await ok('d1', 'ambreq_accept', { id });
  await ok('d1', 'ambulance_available', { id: 'a1', available: false });
  await ok('d1', 'ambreq_step', { id, step: 'done' });
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('available'), false);
});
