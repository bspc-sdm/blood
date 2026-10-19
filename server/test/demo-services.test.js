// ডেমো: সেবা (ফার্মেসি, ওষুধ খোঁজা, অনলাইন অর্ডার, প্যাথলজি, অ্যাম্বুলেন্স, বাসায় সেবা, রিপোর্ট, অনুমোদন)
// বানানো → আসল পড়ার কাজে দেখা যায় → পরীক্ষকের কয়েকটা কাজ → মোছার পর কিছু থাকে না। (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
// অন্য ভাগগুলো (blood, rx, health) এখনো তৈরি হচ্ছে — সেগুলোর ভুলে এই ভাগের পরীক্ষা যেন না আটকায়
for (const part of ['blood', 'rx', 'health']) {
  const file = path.join(SERVER, `lib/demo/${part}.js`);
  const real = require(file);
  require.cache[file].exports = {
    async seed(ctx) {
      try {
        await real.seed(ctx);
      } catch (e) {
        console.error(`(demo ${part} seed failed — ignored in services test)`, e.message);
      }
    },
  };
}
const api = require(path.join(SERVER, 'api/community.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const verify = require(path.join(SERVER, 'lib/verify'));
const { db } = F.firebase;
const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

// নকল ডাটাবেসে count() নেই (রিপোর্টের গণনা) — এই পরীক্ষার জন্য যোগ করা
const origCollection = db.collection;
function withCount(q) {
  return new Proxy(q, {
    get(t, k) {
      if (k === 'count') return () => ({ get: async () => { const s = await t.get(); return { data: () => ({ count: s.size }) }; } });
      const v = t[k];
      if (['where', 'limit', 'orderBy', 'startAt', 'endAt'].includes(k)) return (...a) => withCount(v(...a));
      return v;
    },
  });
}
db.collection = (c) => withCount(origCollection(c));
// নকল ডাটাবেসে recursiveDelete আর ref.parent নেই (অন্য ভাগের রিকোয়েস্ট/প্রশ্ন মুছতে অ্যাকাউন্ট মোছা এগুলো ডাকে)
if (!db.recursiveDelete) {
  db.recursiveDelete = async (ref) => {
    for (const k of [...F.store.keys()]) if (k === ref.path || k.startsWith(`${ref.path}/`)) F.store.delete(k);
  };
}
const origDoc = db.doc;
db.doc = (p) => {
  const r = origDoc(p);
  const parts = p.split('/');
  if (!r.parent && parts.length > 2) r.parent = { path: parts.slice(0, -1).join('/'), parent: db.doc(parts.slice(0, -2).join('/')) };
  return r;
};

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

const U = (k) => demo.uidOf(k);
const X = (k) => demo.uidOf(`x-${k}`);
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur').bn;
const names = (items) => items.map((x) => x.name);

test('সেবার ডেমো: তালিকা, অর্ডার, খোঁজ, অনুমোদন — সব আসল কাজে দেখা যায়, পরীক্ষার কাজ চলে, মোছার পর কিছু থাকে না', async () => {
  const seeded = await call(OWNER, 'admin_demo_seed');
  assert.strictEqual(seeded.status, 200, JSON.stringify(seeded.body));
  const P = U('patient');
  const PH = U('pharmacy');
  const LAB = U('lab');
  const AMB = U('ambulance');
  const NURSE = U('homecare');

  // ---------- অতিথি (লগইন ছাড়া) ----------
  const gPh = await ok(null, 'pharmacies', { district: DISTRICT });
  assert.ok(names(gPh.items).includes('ডেমো লাইফ ফার্মেসি'));
  assert.ok(names(gPh.items).includes('ডেমো ২৪ ঘণ্টা ফার্মেসি'));
  assert.ok(!names(gPh.items).includes('ডেমো আরোগ্য ফার্মেসি'), 'অপেক্ষার ফার্মেসি তালিকায় নয়');
  const seba = gPh.items.find((p) => p.name === 'ডেমো সেবা ড্রাগ হাউস');
  assert.ok(seba && seba.openNow === false && seba.closedUntil > Date.now(), 'সাময়িক বন্ধ দোকান "বন্ধ"');
  assert.ok(gPh.items.find((p) => p.name === 'ডেমো ২৪ ঘণ্টা ফার্মেসি').open24);
  const life = gPh.items.find((p) => p.id === PH);
  assert.strictEqual(life.phone, '01700000150');
  assert.strictEqual(life.delivery, true);
  assert.strictEqual(life.discount, 10);
  const gOpen = await ok(null, 'pharmacies', { district: DISTRICT, openNow: true });
  assert.ok(!names(gOpen.items).includes('ডেমো সেবা ড্রাগ হাউস'), '"এখন খোলা" ফিল্টারে বন্ধ দোকান নেই');
  assert.ok(names(gOpen.items).includes('ডেমো ২৪ ঘণ্টা ফার্মেসি'));
  // "২৪ ঘণ্টা খোলা" (S-04): ২৪ ঘণ্টা ফার্মেসি আর ডেমো লাইফ ফার্মেসি (রাতে ডেমো চালাতে ২৪ ঘণ্টা, ২.২৬.১) — বাকিরা নয়
  const g24 = await ok(null, 'pharmacies', { district: DISTRICT, open24: true });
  assert.deepStrictEqual(names(g24.items).sort(), ['ডেমো ২৪ ঘণ্টা ফার্মেসি', 'ডেমো লাইফ ফার্মেসি'].sort());
  assert.ok(gPh.items.length > g24.items.length + 2, 'ছাঁকনিতে তালিকা চোখে পড়ার মতো ছোট হয়');
  const gMukta = await ok(null, 'pharmacies', { district: DISTRICT, upazilaId: 318 });
  assert.ok(names(gMukta.items).includes('ডেমো কালিয়াকৈর মেডিসিন কর্নার'));
  assert.ok(!names(gMukta.items).includes('ডেমো লাইফ ফার্মেসি'));

  const gLabs = await ok(null, 'labs', { district: DISTRICT });
  assert.ok(names(gLabs.items).includes('ডেমো সিটি ডায়াগনস্টিক সেন্টার'));
  assert.ok(names(gLabs.items).includes('ডেমো পপুলার প্যাথলজি'));
  assert.ok(!names(gLabs.items).includes('ডেমো নিউ লাইফ ল্যাব'));
  assert.ok(gLabs.items.find((l) => l.id === LAB).homeCollection);

  const gAmb = await ok(null, 'ambulances', { district: DISTRICT });
  assert.ok(names(gAmb.items).includes('ডেমো জীবন অ্যাম্বুলেন্স (এসি)'));
  assert.ok(gAmb.items.find((a) => a.name === 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)').available);
  assert.strictEqual(gAmb.items.find((a) => a.name === 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস').available, false, '১২ ঘণ্টার পুরোনো → ফোনে জেনে নিন');
  assert.ok(names(gAmb.busy).includes('ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)'), 'ট্রিপে থাকা গাড়ি আলাদা তালিকায়');
  assert.ok(!names([...gAmb.items, ...gAmb.busy]).includes('ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স'), 'অপেক্ষার গাড়ি তালিকায় নয়');
  const gIcu = await ok(null, 'ambulances', { district: DISTRICT, type: 'icu' });
  assert.deepStrictEqual(names(gIcu.items), ['ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস']);

  const gNurse = await ok(null, 'home_providers', { type: 'nurse', district: DISTRICT });
  assert.ok(names(gNurse.items).includes('ডেমো নার্স নাসরিন'));
  assert.ok(names(gNurse.items).includes('ডেমো নার্স মৌসুমী'));
  assert.ok(!names(gNurse.items).includes('ডেমো নার্স তানিয়া'), 'ব্যাজ অপেক্ষায় — তালিকায় নয়');
  const gFemale = await ok(null, 'home_providers', { type: 'nurse', district: DISTRICT, female: true });
  assert.ok(!names(gFemale.items).includes('ডেমো নার্স শাহীন'));
  const gSaline = await ok(null, 'home_providers', { type: 'nurse', district: DISTRICT, service: 'newborn_care' });
  assert.ok(!names(gSaline.items).includes('ডেমো নার্স শাহীন'));
  const gDoc = await ok(null, 'home_providers', { type: 'doctor', district: DISTRICT });
  assert.ok(names(gDoc.items).includes('ডা. ডেমো রফিক'));
  assert.ok((await ok(null, 'drug_search', { q: 'Monte' })).items.some((d) => d.name === 'Montene'));
  assert.strictEqual((await call(null, 'med_find', { i: 1, district: DISTRICT })).status, 401, 'ওষুধ খুঁজতে লগইন লাগে');

  // ---------- ফার্মেসি অ্যাকাউন্ট ----------
  const me = (await ok(PH, 'pharmacy_me')).pharmacy;
  assert.strictEqual(me.status, 'approved');
  assert.strictEqual(me.licenseNo, 'DEMO-DL-1501');
  assert.ok(me.hasLocation);
  const home = await ok(PH, 'rx_pharmacy_home');
  assert.strictEqual(home.shopCode, 'DEMQKP');
  assert.strictEqual(home.online.enabled, true);
  assert.strictEqual(home.missed.length, 3);
  assert.strictEqual(home.missed[0].name, 'Mixtard 30');
  assert.ok((await ok(PH, 'stock_get')).items.includes('Montene'));
  const orders = (await ok(PH, 'rx_orders_pharmacy')).items;
  const byId = Object.fromEntries(orders.map((o) => [o.id, o]));
  assert.strictEqual(byId['demo-svc-ord-a'].status, 'sent');
  assert.strictEqual(byId['demo-svc-ord-a'].mode, 'online');
  assert.ok(byId['demo-svc-ord-a'].address.includes('শিববাড়ি'), 'চালু অনলাইন অর্ডারে ঠিকানা');
  assert.strictEqual(byId['demo-svc-ord-b'].status, 'out');
  assert.strictEqual(byId['demo-svc-ord-c'].status, 'done');
  assert.strictEqual(byId['demo-svc-ord-c'].address, undefined, 'শেষ হওয়া অর্ডারে ঠিকানা লুকানো');
  const reqs = (await ok(PH, 'med_find_requests')).items;
  assert.deepStrictEqual(reqs.map((r) => r.id).sort(), ['demo-svc-find-1', 'demo-svc-find-3']);
  const st = await ok(PH, 'pharmacy_stats');
  assert.ok(st.calls[30] > 0);
  const opened = (await ok(PH, 'rx_pharmacy_open', { code: 'DEMSVCRXA2' })).item;
  assert.strictEqual(opened.medicines.length, 3);

  // ---------- রোগী ----------
  const mine = (await ok(P, 'rx_mine')).items.map((x) => x.id);
  for (const id of ['demo-svc-rx-a', 'demo-svc-rx-b', 'demo-svc-rx-c', 'demo-svc-rx-d']) assert.ok(mine.includes(id), id);
  const actB = await ok(P, 'rx_activity', { rxId: 'demo-svc-rx-b' });
  assert.deepStrictEqual(actB.orders.map((o) => o.status).sort(), ['cancelled', 'out']);
  assert.deepStrictEqual(actB.orders.find((o) => o.status === 'cancelled').missed, ['Montene']);
  const actC = await ok(P, 'rx_activity', { rxId: 'demo-svc-rx-c' });
  assert.ok(actC.medicines[0].done, 'আগের অর্ডারের ওষুধ কেনা হয়ে গেছে');
  const opts = (await ok(P, 'rx_pharmacy_options', { rxId: 'demo-svc-rx-d', district: DISTRICT, mode: 'online' })).items;
  const lifeOpt = opts.find((o) => o.id === PH);
  assert.ok(lifeOpt && lifeOpt.online && lifeOpt.have === 3 && lifeOpt.total === 3, JSON.stringify(opts));
  const finds = (await ok(P, 'med_finds_mine')).items;
  const f1 = finds.find((f) => f.id === 'demo-svc-find-1');
  assert.ok(f1.open);
  assert.deepStrictEqual(f1.have.map((h) => h.name), ['ডেমো ২৪ ঘণ্টা ফার্মেসি']);
  assert.strictEqual(finds.find((f) => f.id === 'demo-svc-find-2').open, false);
  const pAmb = await ok(P, 'ambulances', { district: DISTRICT });
  assert.ok(pAmb.items.find((a) => a.name === 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস').canRate, 'ফোনের ১ ঘণ্টা পর রেটিং');
  // ডেমো লাইফ ফার্মেসি থেকে গতকাল অনলাইন অর্ডারে ওষুধ নিয়েছেন → ফোন ছাড়াই রেটিং, তিন দিকেই;
  // নিউ মডেলে শুধু ফোন (২ দিন আগে) → শুধু ব্যবহার; ২৪ ঘণ্টা ফার্মেসিতে ফোনও নেই, কেনাও নেই → নয়
  const pPh = (await ok(P, 'pharmacies', { district: DISTRICT })).items;
  const lifePh = pPh.find((p) => p.id === PH);
  assert.ok(lifePh.canRate, 'সম্পন্ন অর্ডার → ফার্মেসিতে রেটিং');
  assert.deepStrictEqual(lifePh.rateAspects, ['behaviour', 'price', 'delivery']);
  assert.deepStrictEqual(lifePh.aspects, { behaviour: { rating: 4.7, count: 12 }, price: { rating: 4.4, count: 10 }, delivery: { rating: 4.1, count: 7 } });
  assert.strictEqual(lifePh.rating, 4.5);
  assert.deepStrictEqual(pPh.find((p) => p.id === X('svc-ph-model')).rateAspects, ['behaviour'], 'শুধু ফোন → শুধু ব্যবহার');
  assert.strictEqual(pPh.find((p) => p.id === X('svc-ph-24h')).canRate, false, 'ফোন বা কেনা ছাড়া নয়');
  assert.strictEqual((await ok(PH, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === PH).canRate, false, 'নিজের দোকানে নয়');
  const pNurse = await ok(P, 'home_providers', { type: 'nurse', district: DISTRICT });
  assert.ok(pNurse.items.find((n) => n.uid === NURSE).canRate);

  // ---------- প্যাথলজি, অ্যাম্বুলেন্স, নার্স ----------
  const labMe = (await ok(LAB, 'lab_me')).lab;
  assert.strictEqual(labMe.status, 'approved');
  assert.strictEqual((await ok(LAB, 'lab_orders')).items.length >= 0, true, 'অনুমোদিত প্যাথলজি — পরীক্ষার তালিকা খোলে');
  const myAmb = (await ok(AMB, 'ambulance_mine')).items;
  assert.deepStrictEqual(myAmb.map((a) => a.status).sort(), ['approved', 'approved', 'approved', 'pending']);
  const ambStats = (await ok(AMB, 'ambulance_stats')).items;
  const acStats = ambStats.find((a) => a.id === 'demo-svc-amb-ac');
  assert.ok(acStats.calls[30] > 0 && acStats.comments.length === 2 && acStats.rating > 4);
  const hs = await ok(NURSE, 'home_stats');
  assert.ok(hs.enabled && hs.comments.length === 2 && hs.ratingCount === 6);
  const prov = (await ok(NURSE, 'provider_me')).provider;
  assert.strictEqual(prov.homeVisit.phone, '01700000180');

  // ---------- মালিক (অ্যাডমিন) ----------
  const aPh = (await ok(OWNER, 'admin_pharmacies')).items;
  const pend = aPh.find((p) => p.name === 'ডেমো আরোগ্য ফার্মেসি');
  assert.ok(pend && pend.verify.docs.filter((d) => d.required).every((d) => d.has), 'দরকারি ছবি আছে');
  assert.ok((await ok(OWNER, 'admin_labs')).items.some((l) => l.name === 'ডেমো নিউ লাইফ ল্যাব'));
  assert.ok((await ok(OWNER, 'admin_ambulances')).items.some((a) => a.name === 'ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স' && a.ownerUid === AMB));
  assert.ok((await ok(OWNER, 'admin_badges')).items.some((b) => b.name === 'ডেমো নার্স তানিয়া' && b.type === 'nurse'));
  const reps = (await ok(OWNER, 'admin_reports', { panel: true })).items;
  for (const [kind, p] of [['pharmacy', `pharmacies/${X('svc-ph-model')}`], ['ambulance', 'ambulances/demo-svc-amb-momota'], ['homecare', `providers/${X('svc-nurse-shahin')}`]]) {
    assert.ok(reps.some((r) => r.kind === kind && r.targetPath === p), kind);
  }
  const photos = await ok(OWNER, 'admin_verify_docs', { kind: 'pharmacy', id: X('svc-ph-new') });
  assert.ok(photos.photos.filter((p) => p.data).length >= 2);
  const ov = await ok(OWNER, 'admin_overview');
  assert.ok(ov.pharmacies.items.length && ov.labs.items.length && ov.ambulances.items.length && ov.reports.items.length);

  // ---------- পরীক্ষকের কাজ ----------
  // ফার্মেসি: নতুন অনলাইন অর্ডার নেওয়া → পাঠানো; পথে থাকা অর্ডারের ওষুধ দেওয়া (সম্পন্ন)
  await ok(PH, 'rx_order_update', { id: 'demo-svc-ord-a', status: 'accepted', price: '৩৫০ টাকা', eta: '১ ঘণ্টা' });
  await ok(PH, 'rx_order_update', { id: 'demo-svc-ord-a', status: 'out' });
  await ok(PH, 'rx_dispense', { code: 'DEMSVCRXB3', items: [{ i: 0, qty: 30 }, { i: 1, qty: 10 }] });
  assert.strictEqual((await db.doc('rxOrders/demo-svc-ord-b').get()).get('status'), 'done');
  const actA = await ok(P, 'rx_activity', { rxId: 'demo-svc-rx-a' });
  assert.strictEqual(actA.orders[0].status, 'out');
  assert.strictEqual(actA.orders[0].price, '৩৫০ টাকা');
  await ok(PH, 'med_find_answer', { id: 'demo-svc-find-3', have: true });
  await ok(PH, 'stock_set', { add: ['Thyrox 50'] });
  await ok(PH, 'pharmacy_save', {
    name: 'ডেমো লাইফ ফার্মেসি', licenseNo: 'DEMO-DL-1501', phone: '01700000150', district: DISTRICT, upazilaId: 320,
    address: 'শিববাড়ি মোড়, হাসপাতাল রোড, গাজীপুর', open24: true, delivery: true, deliveryNote: 'ফ্রি', discount: 12, closedDays: [], termsAccepted: true,
  });
  assert.strictEqual((await ok(PH, 'pharmacy_me')).pharmacy.status, 'approved', 'সময়/ছাড় বদলালে আবার যাচাই লাগে না');
  // লাইসেন্স বদল → যাচাই; মালিক বাতিল করলে আগের অনুমোদিত তথ্য ফেরে (গাইডে বলা আছে)
  await ok(PH, 'pharmacy_save', {
    name: 'ডেমো লাইফ ফার্মেসি', licenseNo: 'DEMO-DL-9999', phone: '01700000150', district: DISTRICT, upazilaId: 320,
    address: 'শিববাড়ি মোড়, হাসপাতাল রোড, গাজীপুর', open24: true, delivery: true, discount: 12, termsAccepted: true,
  });
  assert.strictEqual((await ok(PH, 'pharmacy_me')).pharmacy.status, 'pending');
  await ok(OWNER, 'admin_pharmacy_resolve', { id: PH, status: 'rejected', reason: 'ডেমো' });
  const back = (await ok(PH, 'pharmacy_me')).pharmacy;
  assert.strictEqual(back.status, 'approved');
  assert.strictEqual(back.licenseNo, 'DEMO-DL-1501');
  // রোগী: নতুন অনলাইন অর্ডার, ওষুধ খোঁজা, রেটিং, রিপোর্ট, ফোন
  const sent = await ok(P, 'rx_send_pharmacy', { rxId: 'demo-svc-rx-d', pharmacyId: PH, mode: 'online', address: 'বাসা ১২, শিববাড়ি', phone: '01700000159' });
  assert.ok((await ok(PH, 'rx_orders_pharmacy')).items.some((o) => o.id === sent.id && o.status === 'sent'));
  const montene = (await ok(P, 'drug_search', { q: 'Montene' })).items[0];
  const mf = await ok(P, 'med_find', { i: montene.i, district: DISTRICT, upazilaId: 320 });
  assert.ok(mf.ok && mf.notified >= 1, JSON.stringify(mf));
  await ok(P, 'ambulance_rate', { id: 'demo-svc-amb-icu', rating: 4, comment: 'ভালো সার্ভিস' });
  await ok(P, 'home_rate', { uid: NURSE, rating: 5, comment: 'খুব যত্নশীল' });
  await ok(P, 'pharmacy_rate', { id: PH, aspects: { behaviour: 5, price: 5, delivery: 5 }, comment: 'রাতেও ডেলিভারি দিয়েছে' });
  assert.strictEqual((await db.doc(`pharmacies/${PH}`).get()).get('ratingCount'), 13);
  const lifeAfter = (await ok(P, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === PH);
  assert.strictEqual(lifeAfter.rating, 4.5);
  assert.deepStrictEqual(lifeAfter.aspects, { behaviour: { rating: 4.7, count: 13 }, price: { rating: 4.5, count: 11 }, delivery: { rating: 4.3, count: 8 } });
  assert.strictEqual((await ok(PH, 'pharmacy_stats')).comments[0].comment, 'রাতেও ডেলিভারি দিয়েছে');
  // গাইডের S-30: বদলে ব্যবহার ৩, দাম ৩, ডেলিভারি খালি → ★ ৪.৪ (১৩), আগের ডেলিভারির ৫ সরে যায়
  await ok(P, 'pharmacy_rate', { id: PH, aspects: { behaviour: 3, price: 3 } });
  const lifeRe = (await ok(P, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === PH);
  assert.deepStrictEqual([lifeRe.rating, lifeRe.ratingCount], [4.4, 13]);
  assert.deepStrictEqual(lifeRe.aspects, { behaviour: { rating: 4.5, count: 13 }, price: { rating: 4.3, count: 11 }, delivery: { rating: 4.1, count: 7 } });
  assert.deepStrictEqual((await ok(P, 'pharmacies', { district: DISTRICT })).items.find((p) => p.id === X('svc-ph-model')).aspects,
    { behaviour: { rating: 3.7, count: 6 }, price: { rating: 3.3, count: 4 } });
  await ok(P, 'pharmacy_called', { id: X('svc-ph-24h') });
  await ok(P, 'ambulance_called', { id: 'demo-svc-amb-ac' });
  await ok(P, 'home_called', { uid: NURSE });
  await ok(P, 'report', { kind: 'pharmacy', id: X('svc-ph-24h'), reason: 'ডেমো রিপোর্ট' });
  // অ্যাম্বুলেন্স: খালি/ট্রিপে; প্যাথলজি: নিজের মাত্রা
  await ok(AMB, 'ambulance_available', { id: 'demo-svc-amb-nonac', available: true });
  await ok(LAB, 'lab_ranges_set', { ranges: [{ code: 'rbs', key: 'glu', low: 4, high: 7.8 }] });
  // মালিক: ছবি দেখে সব টিক দিয়ে অনুমোদন; রিপোর্ট "রেখে দিন"
  const checks = verify.CHECKS.pharmacy().map((c) => c.id);
  assert.strictEqual((await call(OWNER, 'admin_pharmacy_resolve', { id: X('svc-ph-new'), approve: true })).status, 400, 'টিক ছাড়া অনুমোদন নয়');
  await ok(OWNER, 'admin_pharmacy_resolve', { id: X('svc-ph-new'), status: 'approved', checks });
  await ok(OWNER, 'admin_lab_resolve', { id: X('svc-lab-new'), status: 'approved', checks: verify.CHECKS.lab().map((c) => c.id) });
  await ok(OWNER, 'admin_ambulance_resolve', { id: 'demo-svc-amb-icu-new', status: 'approved', checks: verify.CHECKS.ambulance().map((c) => c.id) });
  await ok(OWNER, 'admin_badge_resolve', { uid: X('svc-nurse-tania'), approve: true, checks: verify.CHECKS.badge({ type: 'nurse' }).map((c) => c.id) });
  await ok(OWNER, 'admin_report_resolve', { targetPath: `pharmacies/${X('svc-ph-model')}`, kind: 'pharmacy', remove: false });
  require(path.join(SERVER, 'lib/memcache')).clear('');
  assert.ok(names((await ok(P, 'pharmacies', { district: DISTRICT })).items).includes('ডেমো আরোগ্য ফার্মেসি'), 'অনুমোদনের পর তালিকায়');
  assert.ok((await ok(AMB, 'ambulance_mine')).items.every((a) => a.status === 'approved'));

  // ---------- মোছা ----------
  const w = await call(OWNER, 'admin_demo_wipe');
  assert.strictEqual(w.status, 200, JSON.stringify(w.body));
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  // পরীক্ষার সময় ডেমো অ্যাকাউন্টের কাজে বানানো জিনিসও (ফোন, রেটিং, খোঁজ, অর্ডার, রিপোর্ট) নেই
  // (মালিকের নিজের কাজের ইতিহাস adminLog থাকে — সেটা মালিকের)
  const MINE = ['pharmacies', 'pharmacyStock', 'pharmacyStats', 'pharmacyMissed', 'pharmacyCalls', 'pharmacyRatings', 'medFinds', 'rxOrders', 'prescriptions',
    'labs', 'labPrices', 'ambulances', 'ambulanceStats', 'ambulanceCalls', 'providers', 'badges', 'homeStats', 'homeCalls', 'modReports', 'verifyDocs',
    'sanctions', 'users', 'serverState', 'inbox'];
  const traces = [...F.store.entries()]
    .filter(([k]) => MINE.includes(k.split('/')[0]))
    .filter(([k, v]) => k.includes('demo-') || JSON.stringify(v).includes('"demo-') || JSON.stringify(v).includes('ডেমো'))
    .map(([k]) => k);
  assert.deepStrictEqual(traces, []);
  assert.ok(![...F.users.keys()].some((x) => demo.isDemoUid(x)));
});
