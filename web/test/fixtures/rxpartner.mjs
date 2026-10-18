// ফার্মেসির কাউন্টার আর প্যাথলজির ডেস্কের নকল উত্তর (/work/pharmacy/orders, /work/lab/orders) — সার্ভারের আসল আকারে
// (server/lib/rxnet.js), সব নাম/নম্বর/কোড বানানো। drug_search (services.mjs) আর lab_order_get (health.mjs) অন্যদের।
// work.mjs-এর ডেমো প্যাথলজি "অনুমোদনের অপেক্ষায়" — শুধু এই অংশের ছবি তোলার সময় (BUILD_DIR=.build-rxpartner) সেটাকে অনুমোদিত করা হয়।
import { createRequire } from 'node:module';
import work from './work.mjs';

const require = createRequire(import.meta.url);
const TESTS = require('../../../server/lib/labtests-defaults.json');

if (process.env.BUILD_DIR === '.build-rxpartner') work.lab_save({ status: 'approved', docsRequested: false, docsMissing: false, docsRequestNote: '' });

const M = 60 * 1000;
const H = 60 * M;
const D = 24 * H;
const now = Date.now();
const DOC1 = { name: 'ডা. তানভীর আহমেদ', degrees: 'MBBS, FCPS (মেডিসিন)', regNo: 'A-38214' };
const DOC2 = { name: 'ডা. ফারহানা ইয়াসমিন', degrees: 'MBBS, DGO', regNo: 'A-51907' };
const SHOP = 'নিউ লাইফ ফার্মেসি';
const err = (status, error) => ({ __status: status, body: { error } });

// ---------- ওষুধের হিসাব (rxnet.js-এর perDay/countable/needQty/medStatus) ----------
function perDay(dose) {
  const parts = String(dose || '').replace(/\s/g, '').replace(/½/g, '0.5').split('+');
  if (parts.length < 2 || parts.length > 4) return 0;
  let sum = 0;
  for (const p of parts) {
    const n = Number(p);
    if (!Number.isFinite(n) || n < 0) return 0;
    sum += n;
  }
  return sum;
}
const countable = (m) => {
  const f = String(m.form || '').toLowerCase();
  return !f || f.includes('tablet') || f.includes('capsule');
};
const needQty = (m) => (countable(m) && perDay(m.dose) > 0 && m.days > 0 ? Math.ceil(perDay(m.dose) * m.days) : null);
function view(rx) {
  const got = new Map();
  for (const d of rx.dispensed) for (const it of d.items) got.set(it.i, (got.get(it.i) || 0) + it.qty);
  return {
    id: rx.id, code: rx.code, status: rx.status, createdAt: rx.createdAt, doctor: rx.doctor, patient: rx.patient,
    medicines: rx.medicines.map((m, i) => {
      const need = needQty(m);
      const g = got.get(i) || 0;
      return {
        i, name: m.name, form: m.form || '', strength: m.strength || '', dose: m.dose || '', days: m.days || 0, need, got: g,
        left: need == null ? null : Math.max(0, need - g), done: need == null ? g > 0 && m.days > 0 : g >= need, timing: m.timing || '', note: m.note || '',
      };
    }),
    dispensed: rx.dispensed.map((d) => ({ name: d.name, at: d.at, items: d.items })),
  };
}

const RX = {
  H4QW7ZK2MP: {
    id: 'prx1', code: 'H4QW7ZK2MP', status: 'active', createdAt: now - 3 * D, doctor: DOC1, patient: { name: 'রহিমা খাতুন', age: 58 },
    medicines: [
      { name: 'Amdocal', form: 'Tablet', strength: '5 mg', dose: '0+0+1', days: 30, timing: 'খাওয়ার পরে' },
      { name: 'Comet', form: 'Tablet', strength: '500 mg', dose: '1+0+1', days: 30, timing: 'খাওয়ার সাথে' },
      { name: 'Seclo', form: 'Capsule', strength: '20 mg', dose: '1+0+1', days: 14, timing: 'খাওয়ার আগে' },
      { name: 'Napa', form: 'Tablet', strength: '500 mg', dose: '1+1+1', days: 0, note: 'জ্বর থাকলে' },
      { name: 'Avolac', form: 'Oral Solution', strength: '3.35 gm/5 ml', dose: '0+0+2', days: 10, note: '২ চামচ, রাতে' },
    ],
    dispensed: [{ name: 'লাইফ কেয়ার ফার্মেসি', at: now - 3 * D + 2 * H, items: [{ i: 0, qty: 10 }, { i: 2, qty: 28 }] }],
  },
  M2KD9VW4YA: {
    id: 'prx2', code: 'M2KD9VW4YA', status: 'active', createdAt: now - 2 * H, doctor: DOC2, patient: { name: 'সুমাইয়া ইসলাম', age: 27 },
    medicines: [
      { name: 'Folison', form: 'Tablet', strength: '5 mg', dose: '0+0+1', days: 90 },
      { name: 'Calbo-D', form: 'Tablet', strength: '500 mg + 200 IU', dose: '1+0+1', days: 60, timing: 'খাওয়ার পরে' },
      { name: 'Emistat', form: 'Tablet', strength: '8 mg', dose: '1+0+1', days: 5, note: 'বমি হলে' },
    ],
    dispensed: [],
  },
  B8NX3RT6CE: {
    id: 'prx3', code: 'B8NX3RT6CE', status: 'active', createdAt: now - 5 * H, doctor: DOC1, patient: { name: 'আব্দুল করিম', age: 42 },
    medicines: [
      { name: 'Azithrocin', form: 'Tablet', strength: '500 mg', dose: '1+0+0', days: 5, timing: 'খাওয়ার ১ ঘণ্টা আগে' },
      { name: 'Fexo', form: 'Tablet', strength: '120 mg', dose: '0+0+1', days: 10 },
      { name: 'Tusca Plus', form: 'Syrup', strength: '100 ml', dose: '2+2+2', days: 7, note: '২ চামচ করে' },
    ],
    dispensed: [],
  },
  T7PJ3LQ8XN: {
    id: 'prx4', code: 'T7PJ3LQ8XN', status: 'cancelled', createdAt: now - 9 * D, doctor: DOC2, patient: { name: 'নাসিমা বেগম', age: 49 },
    medicines: [{ name: 'Ciprocin', form: 'Tablet', strength: '500 mg', dose: '1+0+1', days: 7 }],
    dispensed: [],
  },
};

let orders = [
  { id: 'o1', rxId: 'prx2', code: 'M2KD9VW4YA', patientName: 'সুমাইয়া ইসলাম', note: 'বিকেলে এসে নেব', status: 'sent', createdAt: now - 8 * M, mode: 'shop', price: '', eta: '', deliveryFee: '', missed: [] },
  { id: 'o2', rxId: 'prx1', code: 'H4QW7ZK2MP', patientName: 'রহিমা খাতুন', note: '', status: 'ready', createdAt: now - 40 * M, mode: 'shop', price: '', eta: '', deliveryFee: '', missed: [] },
  {
    id: 'o3', rxId: 'prx3', code: 'B8NX3RT6CE', patientName: 'আব্দুল করিম', note: 'গেটে দারোয়ানকে দিলেই হবে', status: 'sent', createdAt: now - 15 * M, mode: 'online',
    price: '', eta: '', deliveryFee: '৩০ টাকা', missed: [], address: 'বাসা ১২, রোড ৩, আকুয়া মোড়বাড়ি, ময়মনসিংহ', phone: '01712000345',
  },
  {
    id: 'o4', rxId: 'prx5', code: 'R3TW8KQ5HA', patientName: 'শাহনাজ পারভীন', note: '', status: 'accepted', createdAt: now - 2 * H, mode: 'online',
    price: '৮৫০ টাকা', eta: 'সন্ধ্যা ৭টার মধ্যে', deliveryFee: '৩০ টাকা', missed: [], address: 'চরপাড়া, মেডিকেল কলেজ রোড, ময়মনসিংহ', phone: '01815000672',
  },
  {
    id: 'o5', rxId: 'prx6', code: 'C6YV2MN9PD', patientName: 'ফারুক হোসেন', note: 'ফোন করে আসবেন', status: 'out', createdAt: now - 5 * H, mode: 'online',
    price: '১২২০ টাকা', eta: 'দুপুর ২টা', deliveryFee: 'ফ্রি', missed: ['Rosuva 10'], address: 'গাঙ্গিনাপাড়, স্বদেশী বাজারের পাশে', phone: '01911000418',
  },
  { id: 'o6', rxId: 'prx7', code: 'E2HQ7WD4NR', patientName: 'মাহফুজা রহমান', note: '', status: 'done', createdAt: now - 1 * D, mode: 'online', price: '৪২০ টাকা', eta: '', deliveryFee: '৩০ টাকা', missed: [] },
  { id: 'o7', rxId: 'prx8', code: 'K8PV3XA6TG', patientName: 'রাশেদ খান', note: '', status: 'cancelled', createdAt: now - 2 * D, mode: 'online', price: '', eta: '', deliveryFee: '৩০ টাকা', missed: ['Insulin Mixtard 30'] },
  { id: 'o8', rxId: 'prx9', code: 'P4ZT9HC2LW', patientName: 'জাকির হোসেন', note: '', status: 'done', createdAt: now - 3 * D, mode: 'shop', price: '', eta: '', deliveryFee: '', missed: [] },
];

let home = {
  shopCode: 'K4M8PX',
  online: { enabled: true, radiusKm: 3, fee: '৩০ টাকা, ৫০০ টাকার ওপরে ফ্রি', note: 'সকাল ১০টা–রাত ১০টা ডেলিভারি' },
  hasLocation: true,
  missed: [
    { k: 'k_1f3a', name: 'Insulin Mixtard 30', count: 5, lastAt: now - 2 * D },
    { k: 'k_2b7c', name: 'Rosuva 10', count: 4, lastAt: now - 5 * H },
    { k: 'k_3d1e', name: 'Montair 10', count: 3, lastAt: now - 4 * D },
    { k: 'k_4a9f', name: 'Nexum MUPS 20', count: 2, lastAt: now - 6 * D },
    { k: 'k_5c2d', name: 'Bisocor 2.5', count: 2, lastAt: now - 9 * D },
    { k: 'k_6e8b', name: 'Thyrox 50', count: 1, lastAt: now - 12 * D },
    { k: 'k_7f4a', name: 'Ecosprin 75', count: 1, lastAt: now - 15 * D },
  ],
};

let stock = {
  items: [
    'Ace Plus', 'Alatrol', 'Amdocal 5', 'Antacid Plus', 'Azithrocin 500', 'Bizoran 5/20', 'Calbo-D', 'Camlodin 5', 'Cef-3 200', 'Ciprocin 500', 'Comet 500',
    'Emistat 8', 'Fexo 120', 'Flagyl 400', 'Folison', 'Glucomin 500', 'Losectil 20', 'Maxpro 20', 'Monas 10', 'Napa', 'Napa Extra', 'Orsaline-N',
    'Osartil 50', 'Seclo 20', 'Sergel 20', 'Tusca Plus', 'Zimax 500',
  ],
  updatedAt: now - 26 * H,
};
const skey = (n) => String(n).trim().toLowerCase().replace(/\s+/g, ' ');

// ---------- প্যাথলজি ----------
const LAB = 'পপুলার ডায়াগনস্টিক সেন্টার';
const lo = (o) => ({ rxId: 'lrx-' + o.id, labId: 'u-work', labName: LAB, doctorName: DOC1.name, report: null, ...o });
let labOrders = [
  lo({ id: 'lo1', code: 'H9KD3PW7MA', patientName: 'মো. জাহিদ হাসান', patientAge: 36, patientGender: 'male', tests: ['CBC', 'RBS', 'S. Creatinine'], status: 'sent', createdAt: now - 20 * M }),
  // বাসা থেকে নমুনা — সময় জানানো বাকি
  lo({
    id: 'lh1', code: 'V6TR4MW8KA', patientName: 'আব্দুল করিম', patientAge: 58, patientGender: 'male', tests: ['Lipid Profile', 'ECG'], status: 'sent', createdAt: now - 12 * M,
    prices: { items: [{ test: 'Lipid Profile', name: 'Lipid Profile', price: 1200, tat: 'একই দিন' }, { test: 'ECG', name: 'ECG', price: 400, tat: '৩০ মিনিট' }], missing: [], subtotal: 1600, discountPct: 10, estimate: 1440 },
    home: {
      status: 'requested', date: Date.UTC(new Date(now + 6 * H).getUTCFullYear(), new Date(now + 6 * H).getUTCMonth(), new Date(now + 6 * H).getUTCDate()) + D, slot: 'early',
      note: 'খালি পেটে থাকব', fee: 150, visitDate: 0, time: '', at: 0, confirmNote: '', declineNote: '', address: 'বাসা ১২, রোড ৩, কাচিঝুলি, ময়মনসিংহ', phone: '01712000345',
    },
  }),
  lo({ id: 'lo2', code: 'H4QW7ZK2MP', patientName: 'রহিমা খাতুন', patientAge: 58, patientGender: 'female', tests: ['Lipid Profile', 'HbA1c', 'Urine R/E'], status: 'received', createdAt: now - 3 * H }),
  lo({ id: 'lo3', code: 'Q7RM2XE5VB', patientName: 'তাসলিমা আক্তার', patientAge: 24, patientGender: 'female', tests: ['Dengue NS1', 'CBC'], status: 'sent', createdAt: now - 45 * M, doctorName: DOC2.name }),
  lo({ id: 'lo4', code: 'N5WC8KT3JH', patientName: 'আরিফুল ইসলাম', patientAge: 31, patientGender: 'male', tests: ['TSH', 'X-ray Chest P/A view', 'Vit B12 level'], status: 'received', createdAt: now - 1 * D }),
  lo({
    id: 'l1', code: 'F3HJ6YP9QK', patientName: 'কামরুন নাহার', patientAge: 45, patientGender: 'female', tests: ['CBC', 'Lipid Profile'], status: 'reported', createdAt: now - 1 * D,
    report: { items: [], note: '', reportedAt: now - 6 * H, photoCount: 0 },
  }),
  lo({ id: 'lo6', code: 'G8LB4ZN7WE', patientName: 'সাকিব হাসান', patientAge: 19, patientGender: 'male', tests: ['HBsAg'], status: 'cancelled', createdAt: now - 3 * D }),
  lo({
    id: 'lo7', code: 'J2VX9PD6MC', patientName: 'মনোয়ারা বেগম', patientAge: 67, patientGender: 'female', tests: ['Serum Electrolytes', 'S. Creatinine'], status: 'reported', createdAt: now - 4 * D,
    report: { items: [], note: '', reportedAt: now - 3 * D, photoCount: 1 },
  }),
];
let ranges = {};
// পরীক্ষা ও দাম (lab_prices_get / lab_prices_set)
let prices = {
  items: [
    { code: 'cbc', name: 'CBC (Complete Blood Count)', price: 400, tat: 'একই দিন', on: true },
    { code: 'rbs', name: 'Random Blood Sugar (RBS)', price: 100, tat: '১ ঘণ্টা', on: true },
    { code: 'hba1c', name: 'HbA1c', price: 1000, tat: 'একই দিন', on: true },
    { code: 'lipid', name: 'Lipid Profile', price: 1200, tat: 'একই দিন', on: true },
    { code: 'tsh', name: 'TSH', price: 900, tat: '২৪ ঘণ্টা', on: true },
    { code: 'ecg', name: 'ECG', price: 400, tat: '৩০ মিনিট', on: true },
    { code: null, name: 'Serum Vitamin B12', price: 1800, tat: '৪৮ ঘণ্টা', on: true },
    { code: 'usg_abd', name: 'USG of Whole Abdomen', price: 1500, tat: 'একই দিন', on: false },
  ],
  discountPct: 10, homeFee: 150, updatedAt: now - 5 * D, homeCollection: true,
};

const norm = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
function match(name) {
  const n = norm(name);
  if (!n) return null;
  const tries = [n, n.replace(/^(s|serum|blood|plasma)\s+/, ''), n.replace(/\s+(test|level|levels)$/, '')];
  return TESTS.find((t) => !t.hidden && tries.some((x) => [t.code, t.name, ...(t.aliases || [])].map(norm).includes(x))) || null;
}
const rtext = ([a, b]) => (a != null && b != null ? `${a} – ${b}` : b != null ? `< ${b}` : a != null ? `> ${a}` : '');
function formFor(testName, gender) {
  const t = match(testName);
  if (!t) return { test: testName, code: null, name: testName, params: [] };
  return {
    test: testName, code: t.code, name: t.name,
    params: t.params.map((p) => {
      const own = ranges[`${t.code}.${p.key}`];
      const r = (gender === 'female' && p.f) || (gender === 'male' && p.m) || p.r || null;
      const base = r ? { low: r[0] ?? null, high: r[1] ?? null, range: rtext(r) }
        : p.m && p.f ? { low: null, high: null, range: `M: ${rtext(p.m)}, F: ${rtext(p.f)}` } : { low: null, high: null, range: p.text || '' };
      const rg = own ? { low: own.low, high: own.high, range: rtext([own.low, own.high]) } : base;
      return { key: p.key, name: p.name, unit: p.unit || '', ...rg, text: p.text || '', options: p.options || [], long: p.long === true };
    }),
  };
}

export default {
  // ---------- ফার্মেসি ----------
  rx_pharmacy_home: () => home,
  rx_pharmacy_open: (b) => {
    const c = String(b.code || '').toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{10}$/.test(c)) return err(400, 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
    const rx = RX[c];
    return rx ? { item: view(rx) } : err(404, 'এই কোডের কোনো প্রেসক্রিপশন নেই');
  },
  rx_dispense: (b) => {
    const rx = RX[String(b.code || '').toUpperCase()];
    if (!rx) return err(404, 'এই কোডের কোনো প্রেসক্রিপশন নেই');
    if (rx.status !== 'active') return err(409, 'এই প্রেসক্রিপশন বাতিল। ওষুধ দেবেন না।');
    const items = (b.items || []).filter((it) => Number.isInteger(it.i) && it.qty > 0);
    if (!items.length && !(b.missing || []).length) return err(400, 'কয়টা দিলেন লিখুন');
    if (items.length) rx.dispensed.push({ name: SHOP, at: Date.now(), items });
    const names = (b.missing || []).map((i) => rx.medicines[i]?.name).filter(Boolean);
    for (const n of names) {
      const ex = home.missed.find((m) => m.name === n);
      if (ex) { ex.count += 1; ex.lastAt = Date.now(); } else home.missed.unshift({ k: 'k_' + Math.random().toString(16).slice(2, 8), name: n, count: 1, lastAt: Date.now() });
    }
    if (items.length) orders = orders.map((o) => (o.code === rx.code && ['sent', 'ready', 'accepted', 'out'].includes(o.status) ? { ...o, status: 'done' } : o));
    return { item: view(rx) };
  },
  rx_missed_clear: (b) => {
    home = { ...home, missed: b.k ? home.missed.filter((m) => m.k !== b.k) : [] };
    return { ok: true };
  },
  rx_orders_pharmacy: () => ({ items: orders.map((o) => (['sent', 'ready', 'accepted', 'out'].includes(o.status) ? o : { ...o, address: undefined, phone: undefined })) }),
  rx_order_update: (b) => {
    const o = orders.find((x) => x.id === b.id);
    if (!o) return err(404, 'পাওয়া যায়নি');
    orders = orders.map((x) => (x.id === b.id ? { ...x, status: b.status, ...(b.status === 'accepted' ? { price: b.price || '', eta: b.eta || '' } : {}) } : x));
    return { ok: true };
  },
  rx_pharmacy_online_set: (b) => {
    const online = { enabled: b.enabled === true, radiusKm: b.radiusKm || 3, fee: b.fee || '', note: b.note || '' };
    home = { ...home, online };
    return { online };
  },
  stock_get: () => stock,
  stock_set: (b) => {
    const map = new Map(stock.items.map((n) => [skey(n), n]));
    for (const n of b.add || []) if (!map.has(skey(n))) map.set(skey(n), n);
    for (const n of b.remove || []) map.delete(skey(n));
    stock = { items: [...map.values()].sort((a, c) => a.localeCompare(c)), updatedAt: Date.now() };
    return { items: stock.items };
  },

  // ---------- প্যাথলজি ----------
  lab_orders: () => ({ items: labOrders }),
  lab_open_code: (b) => {
    const c = String(b.code || '').toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{10}$/.test(c)) return err(400, 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
    const mine = labOrders.find((o) => o.code === c && o.status !== 'cancelled');
    if (mine) return { item: mine };
    if (c !== 'W5NE8RJ3KT') return err(404, 'এই কোডের কোনো প্রেসক্রিপশন নেই');
    const item = lo({ id: 'lo' + (labOrders.length + 10), code: c, patientName: 'মাহমুদা সুলতানা', patientAge: 33, patientGender: 'female', tests: ['FBS', 'TSH'], status: 'received', createdAt: Date.now() });
    labOrders = [item, ...labOrders];
    return { item };
  },
  lab_order_status: (b) => {
    labOrders = labOrders.map((o) => (o.id === b.id ? { ...o, status: b.status } : o));
    return { ok: true };
  },
  lab_report: (b) => {
    const items = (b.items || []).filter((r) => r.test && r.result);
    if (!items.length && !(b.photos || []).length) return err(400, 'অন্তত একটা ফল বা রিপোর্টের ছবি দিন');
    labOrders = labOrders.map((o) => (o.id === b.id ? { ...o, status: 'reported', report: { items, note: b.note || '', reportedAt: Date.now(), photoCount: (b.photos || []).length } } : o));
    return { ok: true };
  },
  lab_form: (b) => {
    const o = labOrders.find((x) => x.id === b.id);
    if (!o) return err(404, 'পাওয়া যায়নি');
    return { forms: o.tests.map((t) => formFor(t, o.patientGender)) };
  },
  lab_ranges_set: (b) => {
    for (const r of b.ranges || []) {
      const k = `${r.code}.${r.key}`;
      if (r.low == null && r.high == null) delete ranges[k];
      else ranges[k] = { low: r.low ?? null, high: r.high ?? null };
    }
    return { ok: true, count: Object.keys(ranges).length };
  },
  labtests_get: () => ({ tests: TESTS }),
  lab_prices_get: () => prices,
  lab_prices_set: (b) => {
    const items = (b.items || []).map((i) => {
      const t = i.code ? TESTS.find((x) => x.code === i.code) : match(i.name);
      return { code: t ? t.code : null, name: t ? t.name : i.name, price: i.price, tat: i.tat || '', on: i.on !== false };
    });
    prices = { ...prices, items, discountPct: b.discountPct || null, homeFee: b.homeFee ?? null, updatedAt: Date.now() };
    return prices;
  },
  lab_home_confirm: (b) => {
    if (!/^\d\d:\d\d$/.test(String(b.time || ''))) return err(400, 'সময় বেছে নিন');
    labOrders = labOrders.map((o) => (o.id === b.id && o.home ? { ...o, home: { ...o.home, status: 'confirmed', visitDate: b.date, time: b.time, confirmNote: b.note || '' } } : o));
    return { ok: true };
  },
  lab_home_decline: (b) => {
    labOrders = labOrders.map((o) => (o.id === b.id && o.home ? { ...o, home: { ...o.home, status: 'declined', declineNote: b.note || '', address: undefined, phone: undefined } } : o));
    return { ok: true };
  },
};
