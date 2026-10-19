// ডেমো: ফার্মেসির পুরো পথ — প্যানেলের মতো ধাপে ধাপে ডেমো বানিয়ে, রোগী আর দোকান দুই দিক থেকে আসল কাজ দিয়ে।
// (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
// ঘড়ি ঢাকার রাতে (দোকানের খোলা-বন্ধ সময়ের ওপর নির্ভর করে — যখনই চালান একই ফল; রাতের ভুলটা: demo-night.test.js)
require('./support/clock').setDhaka('2026-10-19 03:30');
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
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.7.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}

const PH = 'demo-pharmacy';
const P = 'demo-patient';
const CG = 'demo-caregiver';
const LOG = process.env.PHARMACY_LOG ? (...a) => console.log('  ✓', ...a) : () => {};

test('ফার্মেসি: দোকান, রোগী, কেয়ারগিভার আর মালিক — আসল কাজ দিয়ে', async () => {
  // প্যানেলের মতো ধাপে ধাপে
  for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
  LOG('ডেমো বানানো (৫ ধাপ)');

  // ---- অতিথি ----
  const list = (await ok(null, 'pharmacies', { district: DISTRICT })).items;
  const names = list.map((x) => x.name);
  assert.ok(list.some((x) => x.id === PH), 'ডেমো ফার্মেসি তালিকায়');
  assert.ok(!names.some((n) => /আরোগ্য/.test(n)), 'অনুমোদনের অপেক্ষার দোকান তালিকায় নেই');
  LOG(`অতিথির তালিকা: ${list.length}টা ফার্মেসি —`, names.join(', '));

  // ---- দোকানের মালিক: নিজের দোকান ----
  const me = (await ok(PH, 'pharmacy_me')).pharmacy;
  assert.strictEqual(me.status, 'approved');
  const home = await ok(PH, 'rx_pharmacy_home');
  LOG(`দোকান: ${me.name}, অবস্থা ${me.status}, এখন খোলা ${me.openNow}, দোকানের কোড ${home.shopCode}, অনলাইন ${home.online.enabled} (${home.online.radiusKm} কিমি, ${home.online.fee})`);
  const orders0 = (await ok(PH, 'rx_orders_pharmacy')).items;
  LOG(`অর্ডারের তালিকা: ${orders0.length}টা —`, orders0.map((o) => `${o.mode}/${o.status}`).join(', '));
  assert.ok(orders0.some((o) => o.status === 'sent'), 'নতুন অর্ডার অপেক্ষায়');
  const stock = await ok(PH, 'stock_get');
  LOG(`স্টক ${stock.items.length}টা, চেয়েও পাননি ${home.missed.length}টা`);
  const stats = await ok(PH, 'pharmacy_stats');
  LOG('ফোনের হিসাব:', JSON.stringify(stats).slice(0, 120));

  // ---- রোগী: অনলাইন অর্ডার (প্রেসক্রিপশন D) ----
  const mine = (await ok(P, 'rx_mine')).items;
  LOG(`রোগীর প্রেসক্রিপশন: ${mine.length}টা`);
  const opts = (await ok(P, 'rx_pharmacy_options', { rxId: 'demo-svc-rx-d', district: DISTRICT, mode: 'online' })).items;
  assert.ok(opts.some((o) => o.id === PH), 'অনলাইনে ডেমো ফার্মেসি বাছাই করা যায়');
  const est = opts.find((o) => o.id === PH).estimate;
  LOG('ডেমো ফার্মেসিতে আনুমানিক খরচ', JSON.stringify(est));
  assert.ok(est && est.total > 0 && est.priced > 0, 'ডেমো ফার্মেসিতে আনুমানিক খরচ দেখা যায় (হিসাব-খাতার দাম)');
  assert.ok(opts.every((o) => o.id.startsWith('demo-')), 'ডেমো রোগী শুধু ডেমো দোকান দেখেন? (না হলে আসল দোকানও আসে)');
  const sent = await ok(P, 'rx_send_pharmacy', { rxId: 'demo-svc-rx-d', pharmacyId: PH, mode: 'online', address: 'বাসা ১২, শিববাড়ি', phone: '01700000159' });
  LOG('রোগী অনলাইন অর্ডার পাঠালেন');
  // দোকান: নেওয়া → পাঠানো → কত দিলেন
  let o = (await ok(PH, 'rx_orders_pharmacy')).items.find((x) => x.id === sent.id);
  assert.strictEqual(o.status, 'sent');
  await ok(PH, 'rx_order_update', { id: sent.id, status: 'accepted', price: '৩৫০ টাকা', eta: '১ ঘণ্টা' });
  await ok(PH, 'rx_order_update', { id: sent.id, status: 'out' });
  const rxD = (await ok(P, 'rx_mine')).items.find((x) => x.id === 'demo-svc-rx-d');
  const disp = await ok(PH, 'rx_dispense', { code: rxD.code, items: [{ i: 0, qty: 10 }] });
  LOG('দোকান: নিলেন → পাঠালেন → ওষুধ দেওয়া লিখলেন', JSON.stringify(disp).slice(0, 80));
  o = (await ok(P, 'rx_activity', { rxId: 'demo-svc-rx-d' }));
  LOG('রোগী অবস্থা দেখলেন:', JSON.stringify(o).slice(0, 160));

  // ---- দোকানে দাঁড়িয়ে (QR/কোড) ----
  const shop = await ok(P, 'rx_shop_find', { code: home.shopCode });
  assert.strictEqual(shop.item.id, PH);
  LOG(`দোকানের কোড ${home.shopCode} দিয়ে দোকান পাওয়া গেল`);

  // ---- দোকান: ফেরত দেওয়া ("পারব না") ----
  const waiting = (await ok(PH, 'rx_orders_pharmacy')).items.filter((x) => x.status === 'sent');
  if (waiting.length) {
    await ok(PH, 'rx_order_update', { id: waiting[0].id, status: 'cancelled', reason: 'not_available' });
    LOG('দোকান একটা অর্ডার "পারব না" দিলেন');
  }

  // ---- স্টক ----
  await ok(PH, 'stock_set', { add: ['Thyrox 50'] });
  assert.ok(JSON.stringify(await ok(PH, 'stock_get')).includes('Thyrox 50'));
  LOG('স্টকে ওষুধ যোগ হলো');

  // ---- ওষুধ খোঁজা: রোগী খোঁজেন → ডেমো দোকান উত্তর দেন ----
  const drugs = (await ok(P, 'drug_search', { q: 'Montene' })).items;
  const mf = await ok(P, 'med_find', { i: drugs[0].i, district: DISTRICT, upazilaId: 320 });
  assert.ok(mf.ok, JSON.stringify(mf));
  const reqs = (await ok(PH, 'med_find_requests')).items;
  const req = reqs.find((x) => x.id === mf.id);
  assert.ok(req, 'ডেমো দোকান খোঁজটা পেলেন');
  await ok(PH, 'med_find_answer', { id: mf.id, have: true });
  const myFinds = (await ok(P, 'med_finds_mine')).items;
  assert.ok(JSON.stringify(myFinds.find((x) => x.id === mf.id)).includes(PH) || myFinds.length);
  LOG(`ওষুধ খোঁজা: ${mf.notified}টা দোকানে গেল, ডেমো দোকান "আছে" বললেন`);
  // নোটিফিকেশন শুধু ডেমো দোকানে
  const notified = (await db.doc(`medFinds/${mf.id}`).get()).get('notified');
  assert.ok(notified.every((u) => u.startsWith('demo-')), 'আসল দোকানে যায়নি');

  // ---- দোকানের তথ্য বদলানো আর কয়েক দিন বন্ধ ----
  const paused = await call(PH, 'pharmacy_pause', { days: 2 });
  LOG('কয়েক দিন বন্ধ:', paused.status, JSON.stringify(paused.body).slice(0, 80));
  if (paused.status === 200) await call(PH, 'pharmacy_pause', { days: 0 });

  // ---- কেয়ারগিভার: রোগীর হয়ে দোকানে পাঠানো ----
  const cg = await call(CG, 'rx_pharmacy_options', { asUid: P, rxId: 'demo-svc-rx-d', district: DISTRICT, mode: 'shop' });
  LOG('কেয়ারগিভার রোগীর হয়ে দোকান বাছাই:', cg.status);
  assert.strictEqual(cg.status, 200);

  // ---- মালিক: অপেক্ষার দোকান অনুমোদন ----
  const pend = (await ok(OWNER, 'admin_pharmacies', { status: 'pending' })).items;
  LOG('মালিকের অপেক্ষার তালিকা:', pend.map((x) => x.name + ' / ' + x.status).join(', '));
  const newShop = pend.find((x) => /আরোগ্য/.test(x.name));
  assert.ok(newShop, 'মালিক অপেক্ষার দোকান দেখেন');
  assert.strictEqual((await call(OWNER, 'admin_pharmacy_resolve', { id: newShop.id, approve: true })).status, 400, 'টিক ছাড়া নয়');
  await ok(OWNER, 'admin_pharmacy_resolve', { id: newShop.id, status: 'approved', checks: verify.CHECKS.pharmacy().map((c) => c.id) });
  // তালিকা সার্ভারে ১ মিনিট মনে রাখা হয় — অনুমোদনের পর সর্বোচ্চ ১ মিনিটে আসে (এখানে সেই মিনিট পেরোনো ধরে নেওয়া)
  require(path.join(SERVER, 'lib/memcache')).clear();
  assert.ok((await ok(P, 'pharmacies', { district: DISTRICT })).items.some((x) => x.id === newShop.id));
  LOG('মালিক কাগজ দেখে টিক দিয়ে অনুমোদন দিলেন → তালিকায় এলো');

  // ---- আসল ব্যবহারকারী ডেমো দোকানে ওষুধ খুঁজলে ডেমো দোকান নোটিফিকেশন পায় না ----
  const REAL = 'real-user-0001';
  F.users.set(REAL, { uid: REAL, email: 'real@example.com' });
  await db.doc(`users/${REAL}`).set({ name: 'আসল', phone: '01811000000' });
  const rf = await call(REAL, 'med_find', { i: drugs[0].i, district: DISTRICT, upazilaId: 320 });
  LOG('আসল মানুষের খোঁজ (শুধু ডেমো দোকান থাকায়):', rf.status, JSON.stringify(rf.body));
  if (rf.status === 200 && rf.body.id) {
    const n2 = (await db.doc(`medFinds/${rf.body.id}`).get()).get('notified') || [];
    assert.ok(!n2.some((u) => u.startsWith('demo-')), 'আসল খোঁজ ডেমো দোকানে নয়');
  }

  // আসল মানুষ ডেমো দোকানে প্রেসক্রিপশন পাঠাতে পারেন না (কোড দিয়েও না)
  assert.strictEqual((await call(REAL, 'rx_shop_find', { code: home.shopCode })).status, 404);
  LOG('আসল মানুষ ডেমো দোকানের কোড দিলে "এই কোডের কোনো ফার্মেসি নেই"');

  // ---- মোছা ----
  for (let i = 0; i < 5; i++) if (!(await ok(OWNER, 'admin_demo_wipe')).more) break;
  const left = [...F.store.entries()].filter(([k, v]) => (v && v.demo === true) || /pharmac|rxOrders|medFinds|stock/i.test(k) && JSON.stringify(v).includes('demo-pharmacy')).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  LOG('সব ডেমো মুছে গেল, ফার্মেসির কিছু বাকি নেই');
});
