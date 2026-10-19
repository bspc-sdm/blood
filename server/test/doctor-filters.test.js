// ডাক্তারের তালিকার ছাঁকনি (২.৩৩.০, পারভেজ ১৯ অক্টোবর ২০২৬): আজ বসছেন / আগামীকাল / এখন চেম্বারে (ঢাকার সময়ে, বুকিং আর
// সারির নিয়মের মতো), বিশেষজ্ঞতা (তালিকায় যা আছে — facets), ফি (টাকা পর্যন্ত), হাসপাতালের নাম, লিঙ্গ।
// ঘড়ি ঢাকার নির্দিষ্ট সময়ে বসানো (support/clock) — যখনই চালান একই ফল। ১৯ অক্টোবর ২০২৬ = সোমবার।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const clock = require('./support/clock');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const K = require(path.join(SERVER, 'lib/care'));
const R = require(path.join(SERVER, 'lib/rules'));
const M = require(path.join(SERVER, 'lib/memcache'));
const demo = require(path.join(SERVER, 'lib/demo'));
const { db } = F.firebase;
const GZP = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Gazipur').bn;
const OWNER = 'owner-doc-filters';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

async function raw(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: uid ? { authorization: `Bearer ${uid}` } : {}, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await raw(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
// অতিথির উত্তর এক মিনিট মেমোরিতে থাকে (guest: — একই body), তাই ঘড়ি ঘোরানো পরীক্ষায় লগইন করা দর্শক
const VIEWER = 'realViewerF';
F.users.set(VIEWER, { uid: VIEWER, email: 'realviewerf@example.com' });
const list = (uid, extra = {}) => ok(uid === null ? VIEWER : uid, 'providers', { type: 'doctor', district: GZP, homeVisit: false, ...extra });
const ids = (r) => r.items.map((p) => p.uid).sort();

const ch = (o) => ({
  hospitalId: null, hospitalName: 'Test Hospital', hospitalNameBn: '', district: GZP, upazilaId: 320, unionId: null, room: '', schedule: '',
  fee: '', noFee: false, appointments: true, serialPhone: '', days: [], start: '', end: '', ...o,
});
// আসল (ডেমো নয়) ডাক্তার, সবাই গাজীপুরে
const A = 'realDocA'; // মেডিসিন, পুরুষ — সোম/বুধ ১৭–২১, ৫০০ টাকা, টঙ্গী
const B = 'realDocB'; // হৃদরোগ, নারী — প্রতিদিন ০৯–১২, ৳1,200
const C = 'realDocC'; // শিশু, লিঙ্গ লেখা নেই — শনি ২০:০০–০২:০০ (রাত পেরোনো), ফি নেই
const D = 'realDocD'; // মেডিসিন, পুরুষ — রবি ১০–১৩ (১০০০) একটা হাসপাতালে, মঙ্গল ১৮–২০ (৩০০) আরেকটায়
const DOCS = {
  [A]: { name: 'ডা. আসল আলম', specialties: ['medicine'], gender: 'male', chambers: [ch({ hospitalId: 'dghs_t1', hospitalName: 'Tongi General Hospital', hospitalNameBn: 'টঙ্গী সাধারণ হাসপাতাল', days: [1, 3], start: '17:00', end: '21:00', fee: '৫০০ টাকা' })] },
  [B]: { name: 'ডা. আসল বিথি', specialties: ['cardiology'], gender: 'female', chambers: [ch({ hospitalName: 'Heart Care Center', days: [0, 1, 2, 3, 4, 5, 6], start: '09:00', end: '12:00', fee: '৳1,200' })] },
  [C]: { name: 'ডা. আসল চন্দন', specialties: ['pediatrics'], gender: '', chambers: [ch({ hospitalName: 'Night Child Clinic', days: [6], start: '20:00', end: '02:00', noFee: true })] },
  [D]: {
    name: 'ডা. আসল দীপ', specialties: ['medicine', 'chest'], gender: 'male',
    chambers: [
      ch({ hospitalName: 'Sunday Hospital', days: [0], start: '10:00', end: '13:00', fee: '১০০০ টাকা', upazilaId: 318 }),
      ch({ hospitalName: 'Tuesday Clinic', days: [2], start: '18:00', end: '20:00', fee: '৩০০ টাকা' }),
    ],
  },
};

async function seedReal() {
  F.store.clear();
  M.clear('');
  for (const [uid, x] of Object.entries(DOCS)) {
    F.users.set(uid, { uid, email: `${uid}@example.com` });
    await db.doc(`users/${uid}`).set({ name: x.name });
    await db.doc(`badges/${uid}`).set({ type: 'doctor', status: 'approved', name: x.name });
    await db.doc(`providers/${uid}`).set({ type: 'doctor', listed: true, appointments: true, active: true, degrees: 'MBBS', ...x });
  }
}

test('সময়ের হিসাব: ফি পড়া, এখন চেম্বারে (রাত পেরোনো ভাগসহ), আজ (শেষ হলে নয়), আগামীকাল', () => {
  try {
    assert.strictEqual(new Date(clock.setDhaka('2026-10-19 12:00') + 6 * 3600 * 1000).getUTCDay(), 1, '১৯ অক্টোবর ২০২৬ সোমবার');
    assert.strictEqual(K.feeAmount({ fee: '৫০০ টাকা' }), 500);
    assert.strictEqual(K.feeAmount({ fee: '৳1,200' }), 1200);
    assert.strictEqual(K.feeAmount({ fee: '৩০০–৫০০ টাকা (সেবা অনুযায়ী)' }), 300);
    assert.strictEqual(K.feeAmount({ fee: 'ফি পরে জানাব', noFee: true }), 0);
    assert.strictEqual(K.feeAmount({ fee: 'আলোচনা সাপেক্ষে' }), null);

    const night = DOCS[C].chambers[0]; // শনি ২০:০০–০২:০০
    let t = clock.setDhaka('2026-10-17 23:00'); // শনি
    assert.ok(K.chamberNowOpen(night, t) && K.chamberToday(night, t));
    t = clock.setDhaka('2026-10-18 01:30'); // রবি ভোর — শনির ভাগ এখনো চলছে
    assert.ok(K.chamberNowOpen(night, t), 'মাঝরাতের পরে আগের দিনের ভাগ');
    assert.ok(K.chamberToday(night, t), 'এখন চেম্বারে = আজও');
    assert.ok(!K.chamberTomorrow(night, t));
    t = clock.setDhaka('2026-10-18 02:00');
    assert.ok(!K.chamberNowOpen(night, t) && !K.chamberToday(night, t), 'শেষের মিনিটে বন্ধ');

    const a = DOCS[A].chambers[0]; // সোম/বুধ ১৭–২১
    t = clock.setDhaka('2026-10-19 10:00');
    assert.ok(K.chamberToday(a, t) && !K.chamberNowOpen(a, t), 'সকালে: আজ বসবেন, এখন নয়');
    t = clock.setDhaka('2026-10-19 20:59');
    assert.ok(K.chamberNowOpen(a, t));
    t = clock.setDhaka('2026-10-19 21:00');
    assert.ok(!K.chamberToday(a, t), 'আজকের সময় শেষ — book()-এর নিয়মের মতো');
    assert.ok(!K.chamberTomorrow(a, t), 'মঙ্গলবার বসেন না');
    t = clock.setDhaka('2026-10-20 23:59');
    assert.ok(K.chamberTomorrow(a, t), 'মঙ্গলবার রাতে: আগামীকাল বুধ');
  } finally {
    clock.reset();
  }
});

test('তালিকা: আজ / আগামীকাল / এখন চেম্বারে — ঢাকার কয়েকটা সময়ে', async () => {
  try {
    clock.setDhaka('2026-10-19 18:00'); // সোম সন্ধ্যা
    await seedReal();
    assert.deepStrictEqual(ids(await list(null)), [A, B, C, D].sort());
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), [A], 'B-র ১২টায় শেষ, C শনি, D রবি/মঙ্গল');
    assert.deepStrictEqual(ids(await list(null, { openNow: true })), [A]);
    assert.deepStrictEqual(ids(await list(null, { day: 'tomorrow' })), [B, D].sort());
    // D-র কোন চেম্বার মিলল: মঙ্গলবারেরটা (ক্রম ১)
    assert.deepStrictEqual((await list(null, { day: 'tomorrow' })).items.find((p) => p.uid === D).matched, [1]);

    clock.setDhaka('2026-10-19 10:00'); // সোম সকাল
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), [A, B].sort());
    assert.deepStrictEqual(ids(await list(null, { openNow: true })), [B]);

    clock.setDhaka('2026-10-18 01:00'); // রবি রাত ১টা — শনির রাতের চেম্বার চলছে
    assert.deepStrictEqual(ids(await list(null, { openNow: true })), [C]);
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), [B, C, D].sort());

    clock.setDhaka('2026-10-17 23:00'); // শনি রাত
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), [C]);
    assert.deepStrictEqual(ids(await list(null, { day: 'tomorrow' })), [B, D].sort());
  } finally {
    clock.reset();
  }
});

test('তালিকা: বিশেষজ্ঞতা, ফি, হাসপাতালের নাম, লিঙ্গ; একই চেম্বারে সব শর্ত; facets', async () => {
  try {
    clock.setDhaka('2026-10-19 18:00');
    await seedReal();
    const all = await list(null);
    // শুধু তালিকায় যেগুলো আছে (বেশি আগে), সংখ্যাসহ; লিঙ্গ লেখা আছে
    assert.deepStrictEqual(all.facets.specialties, [{ code: 'medicine', n: 2 }, { code: 'cardiology', n: 1 }, { code: 'chest', n: 1 }, { code: 'pediatrics', n: 1 }]);
    assert.strictEqual(all.facets.gender, true);
    // বিশেষজ্ঞতা দিয়ে ছাঁকলেও facets পুরো এলাকার (চিপগুলো হারায় না)
    const med = await list(null, { specialty: 'medicine' });
    assert.deepStrictEqual(ids(med), [A, D].sort());
    assert.strictEqual(med.facets.specialties.length, 4);

    assert.deepStrictEqual(ids(await list(null, { maxFee: 300 })), [C, D].sort(), 'ফি নেই = ০; D-র মঙ্গলবারের ৩০০');
    assert.deepStrictEqual(ids(await list(null, { maxFee: 500 })), [A, C, D].sort());
    assert.deepStrictEqual(ids(await list(null, { maxFee: 1000 })), [A, C, D].sort(), '৳1,200 বাদ');
    // একই চেম্বারে: সোমবার সন্ধ্যায় "আগামীকাল" (মঙ্গল) + ৳১০০০ পর্যন্ত → D-র মঙ্গলবারের চেম্বার; B (১২০০) বাদ
    assert.deepStrictEqual(ids(await list(null, { day: 'tomorrow', maxFee: 1000 })), [D]);
    // শনিবার দুপুরে "আগামীকাল" (রবি) + ৩০০ পর্যন্ত: D-র রবিবারের চেম্বার ১০০০ — অন্য চেম্বারের ৩০০ দিয়ে মেলে না
    clock.setDhaka('2026-10-17 12:00');
    assert.deepStrictEqual(ids(await list(null, { day: 'tomorrow', maxFee: 300 })), []);
    const sun = await list(null, { day: 'tomorrow', maxFee: 1000 });
    assert.deepStrictEqual(ids(sun), [D]);
    assert.deepStrictEqual(sun.items[0].matched, [0]);

    assert.deepStrictEqual(ids(await list(null, { hospitalQ: 'tongi' })), [A]);
    assert.deepStrictEqual(ids(await list(null, { hospitalQ: 'টঙ্গী' })), [A], 'বাংলা নামেও');
    assert.deepStrictEqual(ids(await list(null, { hospitalQ: '  Tuesday   CLINIC ' })), [D]);
    assert.deepStrictEqual(ids(await list(null, { gender: 'female' })), [B]);
    assert.deepStrictEqual(ids(await list(null, { gender: 'male', specialty: 'medicine' })), [A, D].sort());
    // এলাকা + নতুন ছাঁকনি: কালিয়াকৈরে (৩১৮) শুধু D-র রবিবারের চেম্বার
    assert.deepStrictEqual(ids(await list(null, { upazilaId: 318, maxFee: 300 })), []);
    assert.deepStrictEqual(ids(await list(null, { upazilaId: 318, maxFee: 1000 })), [D]);
  } finally {
    clock.reset();
  }
});

test('ভুল বা পুরোনো মান ছাঁকে না; ক্যাশে ছাঁকা ফল ঢোকে না', async () => {
  try {
    clock.setDhaka('2026-10-19 18:00');
    await seedReal();
    const before = await list(null);
    for (const extra of [{ day: 'yesterday' }, { day: true }, { openNow: 'true' }, { maxFee: '500' }, { maxFee: -1 }, { maxFee: 2.5 }, { gender: 'other' }, { hospitalQ: 'x' }, { hospitalQ: 5 }]) {
      assert.strictEqual((await list(null, extra)).items.length, 4, JSON.stringify(extra));
    }
    // পুরোনো অ্যাপ (homeVisit পাঠায় না) — আগের মতো, নতুন ঘরগুলো বাড়তি
    const old = await ok(null, 'providers', { type: 'doctor', district: GZP });
    assert.deepStrictEqual(ids(old), [A, B, C, D].sort());

    // ছাঁকা ফলের পরে ছাঁকনি ছাড়া আবার পুরোটা, আগের মতোই (একই মেমোরির তালিকা থেকে)
    await list(null, { day: 'today', maxFee: 300, specialty: 'medicine', gender: 'male', openNow: true });
    const after = await list(null);
    assert.deepStrictEqual(after, before);
    const cachedNow = await M.cached('providers:doctor', 60 * 1000, async () => { throw new Error('should be cached'); });
    assert.strictEqual(cachedNow.length, 4, 'মেমোরিতে পুরো তালিকা');
    assert.ok(cachedNow.every(({ x }) => x.matched === undefined), 'matched মেমোরির ডকে লেখা হয়নি');
    // ঘড়ি এগোলে ক্যাশ থাকলেও "আজ" নতুন সময়ে হিসাব হয়
    clock.setDhaka('2026-10-19 18:01');
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), [A]);
    clock.setDhaka('2026-10-19 21:00');
    assert.deepStrictEqual(ids(await list(null, { day: 'today' })), []);
  } finally {
    clock.reset();
  }
});

// ডেমো (গাজীপুর): সপ্তাহের প্রতিটা দিনে দিনের কয়েকটা সময়ে "আজ", "আগামীকাল" আর বিশেষজ্ঞতা তালিকা ছোট করে, কিন্তু ফাঁকা নয়;
// "এখন চেম্বারে" আর "৳৩০০ পর্যন্ত"-ও কিছু দেখায়। ডেমো একবার বানিয়ে ঘড়ি ঘোরানো (চেম্বারের সময় বীজের সময়ের ওপর নির্ভর করে না)।
const DEMO_TIMES = ['00:00', '00:30', '01:00', '03:30', '06:00', '09:59', '10:00', '12:30', '13:00', '14:30', '15:59', '16:00', '18:00', '21:30', '23:00', '23:59'];

test('ডেমো: আজ/আগামীকাল/বিশেষজ্ঞতা যেকোনো সময়ে তালিকা ছোট করে, আর কিছু দেখায়; রাত ১টা পর্যন্ত চেম্বার', async () => {
  try {
    clock.setDhaka('2026-10-19 11:00');
    F.store.clear();
    M.clear('');
    for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
    const PT = demo.uidOf('patient');
    const DD = demo.uidOf('doctor');
    for (let day = 17; day <= 23; day++) {
      for (const hm of DEMO_TIMES) {
        const when = `2026-10-${day} ${hm}`;
        clock.setDhaka(when);
        const all = await list(PT);
        const n = all.items.length;
        assert.ok(n >= 6, `${when}: ডেমো ডাক্তার ${n}`);
        const today = await list(PT, { day: 'today' });
        const tomorrow = await list(PT, { day: 'tomorrow' });
        assert.ok(today.items.length > 0 && today.items.length < n, `${when}: আজ ${today.items.length}/${n}`);
        assert.ok(tomorrow.items.length > 0 && tomorrow.items.length < n, `${when}: আগামীকাল ${tomorrow.items.length}/${n}`);
        // ডা. হাসানের চেম্বার রাত ১টা পর্যন্ত — "আজ" সবসময় তিনি (মাঝরাতের পরে নতুন দিনের সকালও সামনে)
        assert.ok(today.items.some((p) => p.uid === DD), `${when}: ডা. হাসান আজ`);
        const now = await list(PT, { openNow: true });
        assert.ok(now.items.length > 0, `${when}: এখন চেম্বারে কেউ`);
        assert.ok(now.items.length < n, `${when}: এখন চেম্বারে সবাই নয়`);
        const sp = all.facets.specialties.map((x) => x.code);
        assert.ok(sp.length >= 5, `${when}: বিশেষজ্ঞতার চিপ ${sp}`);
        for (const code of ['cardiology', 'gynecology', 'pediatrics']) {
          const r = await list(PT, { specialty: code });
          assert.ok(r.items.length > 0 && r.items.length < n, `${when}: ${code}`);
        }
        const cheap = await list(PT, { maxFee: 300 });
        const mid = await list(PT, { maxFee: 500 });
        assert.ok(cheap.items.length > 0 && cheap.items.length < mid.items.length && mid.items.length < n, `${when}: ফি ${cheap.items.length}/${mid.items.length}/${n}`);
      }
    }
    // রাত ১২:৩০-এ ডা. হাসান এখনো চেম্বারে (বিকালের ভাগ রাত ১টা পর্যন্ত), ১টায় নয়
    clock.setDhaka('2026-10-20 00:30');
    assert.ok((await list(PT, { openNow: true })).items.some((p) => p.uid === DD));
    clock.setDhaka('2026-10-20 01:00');
    assert.ok(!(await list(PT, { openNow: true })).items.some((p) => p.uid === DD));
    // হাসপাতালের নাম: কালিয়াকৈর
    const kal = await list(PT, { hospitalQ: 'কালিয়াকৈর' });
    assert.ok(kal.items.length > 0 && kal.items.every((p) => p.chambers.some((c, i) => p.matched.includes(i) && c.hospitalNameBn.normalize('NFC').includes('কালিয়াকৈর'.normalize('NFC')))));
    assert.ok(kal.items.length < (await list(PT)).items.length);
    assert.strictEqual((await list(PT)).facets.gender, true);
  } finally {
    clock.reset();
  }
});

test('ডেমো ↔ আসল: ছাঁকনিতে তালিকা সবার এক (আগের মতো), কিন্তু অন্য জগতের ডাক্তারকে বুকিং নয়', async () => {
  try {
    clock.setDhaka('2026-10-19 11:00');
    F.store.clear();
    M.clear('');
    for (let step = 0; step !== null;) step = (await ok(OWNER, 'admin_demo_seed', { step })).next;
    // আসল ডাক্তারও গাজীপুরে (ক্যাশ মুছে নতুন করে পড়া)
    for (const [uid, x] of Object.entries(DOCS)) {
      F.users.set(uid, { uid, email: `${uid}@example.com` });
      await db.doc(`badges/${uid}`).set({ type: 'doctor', status: 'approved', name: x.name });
      await db.doc(`providers/${uid}`).set({ type: 'doctor', listed: true, appointments: true, active: true, degrees: 'MBBS', ...x });
    }
    M.clear('');
    const REAL = 'realPatientF';
    F.users.set(REAL, { uid: REAL, email: 'realpatientf@example.com' });
    await db.doc(`users/${REAL}`).set({ name: 'আসল রোগী' });
    const PT = demo.uidOf('patient');
    const f = { day: 'today', maxFee: 500 };
    const asDemo = await list(PT, f);
    const asReal = await list(REAL, f);
    const asGuest = await ok(null, 'providers', { type: 'doctor', district: GZP, homeVisit: false, ...f });
    assert.deepStrictEqual(ids(asDemo), ids(asReal));
    assert.deepStrictEqual(ids(asDemo), ids(asGuest));
    assert.ok(asReal.items.some((p) => demo.isDemoUid(p.uid)) && asReal.items.some((p) => !demo.isDemoUid(p.uid)), 'দুই জগতের ডাক্তারই তালিকায়');
    // আজ সোমবার ১১টা: আসল A (সোম ১৭–২১, ৫০০) — ডেমো রোগী ছাঁকনিতে পেলেও বুক করতে পারেন না
    const today = R.todayDhakaUtcMidnight();
    const x1 = await raw(PT, 'book', { providerUid: A, chamberIndex: 0, date: today, note: 'ডেমো থেকে আসলে' });
    assert.strictEqual(x1.status, 404);
    const dd = asReal.items.find((p) => p.uid === demo.uidOf('doctor'));
    const x2 = await raw(REAL, 'book', { providerUid: dd.uid, chamberIndex: dd.matched[0], date: today, note: 'আসল থেকে ডেমোতে' });
    assert.strictEqual(x2.status, 404);
    // নিজের জগতে চলে
    const x3 = await raw(PT, 'book', { providerUid: dd.uid, chamberIndex: dd.matched[0], date: today, note: 'ডেমো থেকে ডেমোতে' });
    assert.strictEqual(x3.status, 200, JSON.stringify(x3.body));
  } finally {
    clock.reset();
  }
});
