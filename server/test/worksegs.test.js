// "আমার কাজ"-এর ভাগ (২.২৭.০): ফার্মেসির অর্ডার নতুন · চলছে · শেষ, প্যাথলজির অনুরোধ নতুন · চলছে · রিপোর্ট দেওয়া · শেষ।
// rx_orders_pharmacy আর lab_orders প্রতিটা অর্ডারে seg আর পুরো গোনা (counts) দেয়; তালিকার সীমা আগের মতো,
// কিন্তু চালু অর্ডার আগে জায়গা পায়। ডেমোতে প্রতিটা ভাগে অন্তত একটা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
require('./support/clock').setDhaka('2026-10-19 11:30');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const RN = require(path.join(SERVER, 'lib/rxnet'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur').bn; // ডেমো গাজীপুরে
const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  assert.strictEqual(res.statusCode, 200, `${action}: ${JSON.stringify(out)}`);
  return out;
}

test('ভাগ: অবস্থা থেকে নতুন · চলছে · (রিপোর্ট) · শেষ', () => {
  assert.deepStrictEqual(['sent', 'ready', 'accepted', 'out', 'done', 'cancelled', 'withdrawn'].map(RN.pharmSeg),
    ['new', 'active', 'active', 'active', 'closed', 'closed', 'closed']);
  const L = (status, home) => RN.labSeg({ status, ...(home ? { home } : {}) });
  assert.strictEqual(L('sent'), 'new');
  assert.strictEqual(L('sent', { status: 'requested' }), 'new', 'বাসার সময় জানানো বাকি — নতুন');
  assert.strictEqual(L('sent', { status: 'declined' }), 'new', 'বাসায় যাবে না — রোগী আসবেন, তখনো নতুন');
  assert.strictEqual(L('sent', { status: 'confirmed' }), 'active', 'বাসায় যাওয়ার সময় নিশ্চিত — চলছে');
  assert.strictEqual(L('received'), 'active');
  assert.strictEqual(L('reported'), 'reported');
  assert.strictEqual(L('cancelled'), 'closed');
  assert.deepStrictEqual(RN.segCounts(['new', 'active', 'closed'], ['new', 'closed', 'closed']), { new: 1, active: 0, closed: 2 });
});

test('তালিকার সীমা একই, কিন্তু পুরোনো চালু অর্ডার শেষ হওয়াদের ভিড়ে হারায় না', () => {
  const list = [{ id: 'old-open', createdAt: 1, open: true }, ...Array.from({ length: 30 }, (_, i) => ({ id: `c${i}`, createdAt: 100 + i, open: false }))];
  const out = RN.recentFirstOpen(list, (x) => x.open, 10);
  assert.strictEqual(out.length, 10);
  assert.ok(out.some((x) => x.id === 'old-open'));
  assert.strictEqual(out[0].id, 'c29', 'নতুন আগে');
  assert.strictEqual(out[out.length - 1].id, 'old-open');
});

test('ডেমো ফার্মেসি আর প্যাথলজি: প্রতিটা ভাগে অর্ডার, গোনা তালিকার সাথে মেলে; ১০০-র বেশি হলেও নতুন অর্ডার থাকে', async () => {
  for (let step = 0; step !== null;) step = (await call(OWNER, 'admin_demo_seed', { step })).next;

  const ph = await call('demo-pharmacy', 'rx_orders_pharmacy');
  for (const s of ['new', 'active', 'closed']) {
    assert.ok(ph.counts[s] > 0, `ফার্মেসির ডেমোতে "${s}" ভাগে অর্ডার আছে`);
    assert.strictEqual(ph.items.filter((o) => o.seg === s).length, ph.counts[s], `ফার্মেসি ${s}: গোনা = তালিকা`);
  }
  assert.ok(ph.items.every((o) => o.seg === RN.pharmSeg(o.status)));
  assert.ok(ph.items.some((o) => o.seg === 'new' && o.mode === 'online' && o.address && o.phone), 'নতুন অনলাইন অর্ডারে ঠিকানা-ফোন');
  assert.ok(ph.items.some((o) => o.seg === 'new' && o.mode === 'shop'), 'নতুন দোকানে আসা অর্ডার');

  const lab = await call('demo-lab', 'lab_orders');
  for (const s of ['new', 'active', 'reported', 'closed']) {
    assert.ok(lab.counts[s] > 0, `প্যাথলজির ডেমোতে "${s}" ভাগে অনুরোধ আছে`);
    assert.strictEqual(lab.items.filter((o) => o.seg === s).length, lab.counts[s], `প্যাথলজি ${s}: গোনা = তালিকা`);
  }
  assert.strictEqual(lab.items.find((o) => o.id === 'demo-rx-lab-fatema-home').seg, 'new', 'বাসা থেকে নমুনা, সময় জানানো বাকি — নতুন');

  // ১২০টা শেষ হওয়া অর্ডার (নতুন তারিখের) + ডেমোর অর্ডার: তালিকা ১০০-তেই থামে, চালুগুলো সব থাকে, গোনা সবার
  const pid = 'demo-pharmacy';
  const now = Date.now();
  for (let i = 0; i < 120; i++) {
    F.store.set(`rxOrders/bulk-${i}`, { pharmacyId: pid, rxId: 'demo-svc-rx-c', code: 'DEMSVCRXC4', patientName: `রোগী ${i}`, status: 'done', mode: 'shop', createdAt: now + i });
  }
  const many = await call(pid, 'rx_orders_pharmacy');
  assert.strictEqual(many.items.length, 100);
  assert.strictEqual(many.counts.closed, ph.counts.closed + 120);
  assert.strictEqual(many.counts.new, ph.counts.new);
  assert.strictEqual(many.items.filter((o) => o.seg === 'new').length, ph.counts.new, 'নতুন অর্ডার তালিকায় থেকে গেল');
  assert.strictEqual(many.items.filter((o) => o.seg === 'active').length, ph.counts.active);
  for (let i = 1; i < many.items.length; i++) assert.ok(many.items[i - 1].createdAt >= many.items[i].createdAt, 'নতুন আগে');

  // অবস্থা বদলালে ভাগ বদলায়: নতুন → চলছে
  const fresh = ph.items.find((o) => o.seg === 'new' && o.mode === 'online');
  await call(pid, 'rx_order_update', { id: fresh.id, status: 'accepted', price: '১০০ টাকা' });
  const after = await call(pid, 'rx_orders_pharmacy');
  assert.strictEqual(after.items.find((o) => o.id === fresh.id).seg, 'active');
  assert.strictEqual(after.counts.new, many.counts.new - 1);
  assert.strictEqual(after.counts.active, many.counts.active + 1);
  void DISTRICT;
});
