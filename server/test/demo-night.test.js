// রাতের ভুল (২.২৬.১): ডেমোর চেম্বার রাত ১০:৩০-এ শেষ হত, ডেমো ফার্মেসি রাত ২–৬টা বন্ধ থাকত — তাই ঢাকার রাতে ডেমো
// রোগী আজকের সিরিয়াল নিতে পারতেন না, আর "ওষুধ খুঁজুন" ডেমো দোকানে পৌঁছাত না (queue.test, demo-pharmacy.test রাতে ভাঙত)।
// এখানে ঘড়ি ঢাকার কয়েকটা রাতের সময়ে বসিয়ে প্রতিবার ডেমো নতুন করে বানানো হয়, আর দুটো যাত্রাই চলে কি না দেখা হয়।
// (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const clock = require('./support/clock');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const R = require(path.join(SERVER, 'lib/rules'));
const K = require(path.join(SERVER, 'lib/care'));
const demo = require(path.join(SERVER, 'lib/demo'));
const DISTRICT = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;
const OWNER = 'owner-demo-night';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}

// আগে ভাঙত: ২২:৪৫, ২৩:৫৯ (চেম্বার), ০৩:৩০, ০৫:৫৫ (দোকান); মাঝরাত আর দিনের একটা সময়ও
const TIMES = ['2026-10-19 22:45', '2026-10-19 23:59', '2026-10-20 00:00', '2026-10-20 03:30', '2026-10-20 05:55', '2026-10-20 11:00'];

test('ডেমো ঢাকার রাতেও চলে: ডেমো রোগী আজকের সিরিয়াল নেন, "ওষুধ খুঁজুন" ডেমো দোকানে পৌঁছায়', async () => {
  try {
    for (const t of TIMES) {
      clock.setDhaka(t);
      F.store.clear();
      for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
      const DD = demo.uidOf('doctor');
      const PT = demo.uidOf('patient');
      const PH = demo.uidOf('pharmacy');

      // চেম্বার: আজ খোলা, আর আজকের সময় শেষ নয় → ডেমো রোগী আজকের সিরিয়াল নেন
      const today = R.todayDhakaUtcMidnight();
      const prov = (await ok(PT, 'providers', { type: 'doctor', district: DISTRICT, homeVisit: false })).items.find((p) => p.uid === DD);
      const ci = prov.chambers.findIndex((c) => K.chamberOpenOn(c, today));
      assert.ok(ci >= 0, `${t}: ডেমো ডাক্তারের আজ চেম্বার আছে`);
      const booked = await call(PT, 'book', { providerUid: DD, date: today, chamberIndex: ci, note: 'রাতের পরীক্ষা' });
      assert.strictEqual(booked.status, 200, `${t}: আজকের সিরিয়াল — ${JSON.stringify(booked.body)}`);

      // দোকান: এখন খোলা, ডেমো রোগীর খোঁজ ডেমো দোকানে আসে
      const me = (await ok(PH, 'pharmacy_me')).pharmacy;
      assert.strictEqual(me.openNow, true, `${t}: ডেমো ফার্মেসি এখন খোলা`);
      const drugs = (await ok(PT, 'drug_search', { q: 'Montene' })).items;
      const mf = await ok(PT, 'med_find', { i: drugs[0].i, district: DISTRICT, upazilaId: 466 });
      assert.ok(mf.ok, `${t}: ${JSON.stringify(mf)}`);
      const reqs = (await ok(PH, 'med_find_requests')).items;
      assert.ok(reqs.some((x) => x.id === mf.id), `${t}: ডেমো দোকান খোঁজটা পেলেন`);
    }
  } finally {
    clock.reset();
  }
});
