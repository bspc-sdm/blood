// "আমার কাজ"-এর নকল উত্তর: ডেমো ব্যবহারকারীর ৩টা অ্যাম্বুলেন্স (একটা যাচাইয়ে), ১টা ফার্মেসি, ১টা ব্লাড ব্যাংক, ১টা প্যাথলজি,
// আর অনুমোদিত ডাক্তার ব্যাজ (চেম্বার কার্ড)। 00-common-এর খালি তালিকার উপর বসে (ফাইল বর্ণানুক্রমে আসে)।
import { ME } from './00-common.mjs';
import { closedToday, pausedUntil } from './appointment.mjs';

const MYM = 'ময়মনসিংহ'; // জেলার নাম ডাটাবেসের মতো (য় = U+09DF)
const SADAR = 466; // ময়মনসিংহ সদর
const TRISHAL = 463;
const HOUR = 3600 * 1000;
const now = Date.now();

const daily = (seed, peak) => Array.from({ length: 30 }, (_, i) => Math.max(0, Math.round(((Math.sin(i * 0.9 + seed) + 1) / 2) * peak + (i > 24 ? 2 : 0) - (i % 7 === 5 ? 2 : 0))));
const summary = (d) => {
  const sum = (n) => d.slice(30 - n).reduce((a, b) => a + b, 0);
  return { calls: { 7: sum(7), 30: sum(30), 90: sum(30) + 41, total: sum(30) + 120 }, callers: Math.round(sum(30) * 0.7), daily: d };
};

let ambulances = [
  {
    id: 'amb1', name: 'জননী অ্যাম্বুলেন্স সার্ভিস', phone: '01711223344', types: ['ac', 'general'], district: MYM, upazilaId: SADAR,
    // সোয়া ১১ ঘণ্টা আগে "খালি" — মেয়াদের শেষ ঘণ্টা: "খালি আছে — আর ৪৫ মিনিট"
    area: 'মেডিকেল কলেজ, চরপাড়া, ঢাকা রুট', fare: 'শহরের মধ্যে ৮০০ টাকা, ঢাকা ৫৫০০ টাকা', available: true, availableAt: now - 11.25 * HOUR,
    availableUntil: now + 0.75 * HOUR, hasBase: true,
    rating: 4.6, ratingCount: 23, vehicleNo: 'ময়মনসিংহ-ছ ১১-০০৪৫', status: 'approved', statusReason: '',
  },
  {
    id: 'amb2', name: 'জননী অ্যাম্বুলেন্স সার্ভিস', phone: '01711223344', types: ['icu'], district: MYM, upazilaId: SADAR,
    area: 'সারা দেশ', fare: 'আইসিইউ — ঢাকা ১৮০০০ টাকা', available: false, availableAt: 0,
    rating: 4.8, ratingCount: 9, vehicleNo: 'ঢাকা মেট্রো-ছ ৭১-২২৩৩', status: 'approved', statusReason: '',
  },
  {
    id: 'amb3', name: 'জননী অ্যাম্বুলেন্স সার্ভিস', phone: '01711223344', types: ['freezer'], district: MYM, upazilaId: TRISHAL,
    area: '', fare: '', available: false, availableAt: now - 30 * HOUR, rating: 0, ratingCount: 0,
    vehicleNo: 'ময়মনসিংহ-ছ ১১-০৯৮৭', status: 'pending', statusReason: '',
    docs: [], docsMissing: true, docsRequested: false, docsRequestNote: '',
  },
];

let pharmacy = {
  id: 'u-work', name: 'নিউ লাইফ ফার্মেসি', phone: '01811556677', address: 'চরপাড়া মোড়, মেডিকেল কলেজ গেটের বিপরীতে', district: MYM, upazilaId: SADAR,
  openAt: '08:00', closeAt: '23:30', open24: false, openNow: true, closedUntil: 0, closedDays: [5], delivery: true, deliveryNote: 'চরপাড়া ও গাঙ্গিনাপাড়ে ফ্রি',
  discount: 10, rating: 4.4, ratingCount: 31, licenseNo: 'DL-MYM-2019-04412', status: 'approved', statusReason: '', hasLocation: true,
  // তালিকার একই ঘর (backend/docs/LISTINGS.md "Provider side")
  ratingSummary: { avg: 4.4, count: 31 }, is24h: false, opensAt: '08:00', closesAt: '23:30', openOverride: null, verified: true,
};

let bloodBanks = [
  {
    id: 'bb1', name: 'সন্ধানী ব্লাড ব্যাংক, ময়মনসিংহ মেডিকেল', kind: 'org', phone: '01911000222', district: MYM, upazilaId: SADAR,
    address: 'ময়মনসিংহ মেডিকেল কলেজ, ছাত্রাবাস রোড', open24: false, openAt: '09:00', closeAt: '21:00', openNow: true,
    services: ['whole_blood', 'screening', 'donate_here'], closedDays: [], licenseNo: '', status: 'approved', statusReason: '',
    ratingSummary: null, verified: true,
    stock: { 'A+': 'have', 'A-': 'none', 'B+': 'low', 'B-': 'none', 'O+': 'have', 'O-': null, 'AB+': 'low', 'AB-': null }, stockUpdatedAt: now - 5 * HOUR, stockStale: false,
  },
];

let lab = {
  id: 'u-work', name: 'পপুলার ডায়াগনস্টিক সেন্টার', phone: '01611223399', address: 'নতুন বাজার, সি কে ঘোষ রোড', district: MYM, upazilaId: SADAR,
  facilityId: null, homeCollection: true, hours: 'সকাল ৮টা – রাত ১০টা', licenseNo: 'HSM/DIAG/2021/889', status: 'pending', statusReason: '',
  docsMissing: false, docsRequested: true, docsRequestNote: 'লাইসেন্সের পুরো পাতার পরিষ্কার ছবি দিন — মেয়াদের তারিখ পড়া যাচ্ছে না।',
};

let findOff = false;
let finds = [
  // qty/unit: কতটা লাগবে (MEDFIND.md); haveQty/haveUnit: দোকান কতটা আছে বলেছে (না থাকলে উত্তর দেয় না)
  { id: 'mf1', drug: { name: 'Napa Extra', generic: 'Paracetamol + Caffeine', form: 'Tablet', company: 'Beximco' }, area: 'চরপাড়া', km: 0.8, answer: null, createdAt: now - 12 * 60 * 1000, qty: 3, unit: 'strip', haveQty: null, haveUnit: null },
  { id: 'mf2', drug: { name: 'Insulin Mixtard 30', generic: 'Human insulin', form: 'Injection', company: 'Novo Nordisk' }, area: 'গাঙ্গিনাপাড়', km: 2.1, answer: 'yes', createdAt: now - 50 * 60 * 1000, qty: 2, unit: 'vial', haveQty: 1, haveUnit: 'vial' },
  { id: 'mf3', drug: { name: 'Seclo 20', generic: 'Omeprazole', form: 'Capsule', company: 'Square' }, area: 'ত্রিশাল', km: 18.4, answer: null, createdAt: now - 3 * HOUR, qty: 14, unit: 'pcs', haveQty: null, haveUnit: null },
];

const ambDaily = { amb1: daily(1, 6), amb2: daily(3, 3), amb3: daily(0, 0).map(() => 0) };

// "আজকের কাজ" (work_today — backend/docs/MYWORK.md, mywork CONTRACT "## Changes"): এই ব্যবহারকারীর সব ব্যবসা — অবস্থা, আজকের সংখ্যা,
// এখনই দেখুন, শুরু করতে যা বাকি। ডাক্তারের চেম্বার আর অ্যাম্বুলেন্সের গাড়ি এক ব্যবসার ভেতরে।
let labOpen = { openNow: true, openOverride: null };
const MIN = 60 * 1000;
const ovStatus = (o) => (o.openOverride && o.openOverride.open === false && o.openOverride.pause && o.openOverride.until > Date.now() ? 'paused' : o.openNow === false ? 'closed' : 'open');
const task = (kind, bizId, type, id, title, sub, at, action, link) => ({ kind, bizId, type, id, title, sub, at, action, link });
function workToday() {
  const t = Date.now();
  const ch = (i, name, nameBn) => {
    const paused = pausedUntil.get(i) > t;
    return { chamberIndex: i, chamberKey: 'c' + i, name, nameBn, online: false, sitsToday: true, closedToday: closedToday.has(i) && !paused, sessionNow: i === 0,
      status: paused ? 'paused' : closedToday.has(i) ? 'closed' : 'open', pausedUntil: paused ? pausedUntil.get(i) : null };
  };
  const chambers = [ch(0, 'Labib Hospital', 'লাবিব হাসপাতাল'), ch(1, 'Mymensingh Medical College Hospital', 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল')];
  const phSt = ovStatus(pharmacy);
  const items = [
    {
      kind: 'doctor', id: 'u-work', name: 'ডা. আরিফুল হক', role: 'owner', proType: 'doctor', listingStatus: 'approved',
      status: chambers.some((c) => c.status === 'open') ? 'open' : chambers.some((c) => c.status === 'paused') ? 'paused' : 'closed', pausedUntil: null,
      counts: { today: 5, waiting: 2, requests: 3 }, chambers, setup: [{ key: 'photo', done: true }, { key: 'chamber', done: true }, { key: 'fee', done: true }],
      tasks: [
        task('doctor', 'u-work', 'queue_next', 'c0', 'জাহানারা বেগম', 'সিরিয়াল ৪ · লাবিব হাসপাতাল', t - 5 * MIN, 'next', '/work/pro?v=active'),
        task('doctor', 'u-work', 'appt_request', 'q4', 'নাসরিন সুলতানা', 'অ্যাপয়েন্টমেন্টের অনুরোধ · আজ', t - 30 * MIN, 'accept', '/work/pro?v=new'),
      ],
    },
    {
      kind: 'pharmacy', id: pharmacy.id, name: pharmacy.name, role: 'owner', listingStatus: pharmacy.status, status: phSt,
      pausedUntil: phSt === 'paused' ? pharmacy.openOverride.until : null,
      counts: { new: 2, active: 3, salesToday: 6, salesTodayTk: 1663.72, ...(findOff ? {} : { finds: finds.filter((f) => !f.answer).length }) },
      tasks: [
        task('pharmacy', pharmacy.id, 'order_new', 'o1', 'সুমাইয়া ইসলামের প্রেসক্রিপশন', 'M2KD9VW4YA · দোকানে এসে নেবেন', t - 8 * MIN, 'ready', '/work/pharmacy/orders/rx/M2KD9VW4YA'),
        task('pharmacy', pharmacy.id, 'order_new', 'o3', 'আব্দুল করিমের প্রেসক্রিপশন', 'B8NX3RT6CE · বাসায় ডেলিভারি', t - 15 * MIN, 'accept', '/work/pharmacy/orders/rx/B8NX3RT6CE'),
        ...(findOff ? [] : [task('pharmacy', pharmacy.id, 'find', 'mf1', 'Napa Extra — কেউ খুঁজছেন', 'চরপাড়া · ৩ পাতা', t - 12 * MIN, 'answer', '/work/pharmacy/finds')]),
      ],
      setup: [],
    },
    lab.status === 'approved'
      ? { kind: 'lab', id: lab.id, name: lab.name, role: 'owner', listingStatus: 'approved', status: ovStatus(labOpen), pausedUntil: null, counts: { new: 4, active: 2, reported: 2 }, tasks: [], setup: [] }
      : { kind: 'lab', id: lab.id, name: lab.name, role: 'owner', listingStatus: lab.status, status: null, pausedUntil: null, counts: {}, tasks: [],
        setup: [{ key: 'approved', done: false }, { key: 'hours', done: true }, { key: 'address', done: true }, { key: 'prices', done: false }] },
    ...bloodBanks.map((b) => ({
      kind: 'bloodbank', id: b.id, name: b.name, role: 'owner', listingStatus: b.status, status: null, pausedUntil: null, counts: {}, setup: [],
      stockUpdatedAt: b.stockUpdatedAt ?? null, stockStale: !!b.stockStale,
      tasks: b.stockStale || !b.stock ? [task('bloodbank', b.id, 'stock', b.id, 'রক্তের মজুদ হালনাগাদ করুন', b.name, 0, 'stock', '/work/bloodbank')] : [],
    })),
    {
      kind: 'ambulance', id: 'u-work', name: 'জননী অ্যাম্বুলেন্স সার্ভিস', role: 'owner', listingStatus: 'approved',
      status: ambulances.some((a) => a.status === 'approved' && a.available) ? 'open' : 'closed', pausedUntil: null,
      vehicles: ambulances.map((a) => ({ id: a.id, name: a.name, vehicleNo: a.vehicleNo, types: a.types, listingStatus: a.status, available: a.available, availableUntil: a.availableUntil ?? (a.available && a.availableAt > 1 ? a.availableAt + 12 * HOUR : null) })),
      counts: { calls: 1, trips: 0 }, setup: [{ key: 'approved', done: false }, { key: 'base', done: true }],
      tasks: [task('ambulance', 'u-work', 'call', 'c1', 'ডাক · চরপাড়া মোড়', 'রোগী: আব্দুল হাই', t - 3 * MIN, 'accept', '/work/ambulance')],
    },
  ];
  const all = items.flatMap((b) => b.tasks).sort((a, b) => b.at - a.at);
  return { now: t, date: Date.UTC(new Date(t + 6 * 3600e3).getUTCFullYear(), new Date(t + 6 * 3600e3).getUTCMonth(), new Date(t + 6 * 3600e3).getUTCDate()),
    roles: ['doctor', 'pharmacy', 'lab', 'bloodbank', 'ambulance'], items, tasks: all.slice(0, 5), taskCount: all.length };
}

export default {
  work_today: () => workToday(),
  me: () => ({ ...ME, badge: { type: 'doctor', status: 'approved', regNo: 'A-45012', workplace: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল' } }),
  ambulance_mine: () => ({ items: ambulances }),
  pharmacy_me: () => ({ pharmacy }),
  bloodbank_mine: () => ({ items: bloodBanks }),
  lab_me: () => ({ lab }),

  ambulance_stats: () => ({
    items: ambulances.map((a) => ({
      id: a.id, name: a.name, status: a.status, ...summary(ambDaily[a.id] || daily(2, 2)), rating: a.rating, ratingCount: a.ratingCount,
      comments: a.id === 'amb1' ? [
        { rating: 5, comment: 'রাত ২টায় ফোন করে ১৫ মিনিটে পেয়েছি, ধন্যবাদ', at: now - 26 * HOUR },
        { rating: 4, comment: 'ভাড়া একটু বেশি, তবে চালক ভালো', at: now - 4 * 24 * HOUR },
      ] : [],
      reports: 0,
    })),
  }),
  pharmacy_stats: () => summary(daily(5, 5)),
  bloodbank_stats: () => ({ items: bloodBanks.map((b) => ({ id: b.id, name: b.name, status: b.status, ...summary(daily(4, 3)), reports: 0 })) }),

  ambulance_save: (b) => {
    if (b.id) {
      const old = ambulances.find((a) => a.id === b.id);
      const pending = !!old && (old.phone !== b.phone || old.vehicleNo !== b.vehicleNo || old.district !== b.district);
      ambulances = ambulances.map((a) => (a.id === b.id ? { ...a, ...b, status: pending ? 'pending' : a.status } : a));
      return { ok: true, pending: pending || old?.status === 'pending' };
    }
    const id = 'amb' + (ambulances.length + 10);
    ambulances = [...ambulances, { ...b, id, available: false, availableAt: 0, rating: 0, ratingCount: 0, status: 'pending', statusReason: '' }];
    return { ok: true, pending: true, id };
  },
  ambulance_available: (b) => {
    ambulances = ambulances.map((a) => (a.id === b.id ? { ...a, available: !!b.available, availableAt: b.available ? Date.now() : 0, availableUntil: b.available ? Date.now() + 12 * HOUR : 0 } : a));
    return { ok: true };
  },
  ambulance_delete: (b) => {
    ambulances = ambulances.filter((a) => a.id !== b.id);
    return { ok: true };
  },

  pharmacy_save: (b) => {
    const pending = b.licenseNo !== pharmacy.licenseNo || b.phone !== pharmacy.phone;
    pharmacy = { ...pharmacy, ...b, status: pending ? 'pending' : pharmacy.status };
    return { ok: true, pending };
  },
  pharmacy_pause: (b) => {
    pharmacy = { ...pharmacy, closedUntil: b.until || 0, openNow: !b.until };
    return { ok: true };
  },
  pharmacy_delete: () => ({ ok: true }),
  pharmacy_find_setting: (b) => {
    findOff = b.off === true;
    return { ok: true };
  },
  med_find_requests: () => ({ items: findOff ? [] : finds, findOff }),
  med_find_answer: (b) => {
    // কতটা আছে (haveQty ১–৯৯৯৯) বা "বাদ দিন" (dismiss — নিজের তালিকা থেকে সরে)
    if (b.dismiss === true) {
      finds = finds.filter((f) => f.id !== b.id);
      return { ok: true };
    }
    if (!(Number.isInteger(b.haveQty) && b.haveQty >= 1 && b.haveQty <= 9999)) return { __status: 400, body: { error: 'কতটা আছে লিখুন (১ থেকে ৯৯৯৯)' } };
    finds = finds.map((f) => (f.id === b.id ? { ...f, answer: 'yes', haveQty: b.haveQty, haveUnit: f.unit } : f));
    return { ok: true };
  },

  bloodbank_save: (b) => {
    if (b.id) {
      bloodBanks = bloodBanks.map((x) => (x.id === b.id ? { ...x, ...b } : x));
      return { ok: true, pending: false };
    }
    bloodBanks = [...bloodBanks, { ...b, id: 'bb' + (bloodBanks.length + 10), openNow: true, status: 'pending', statusReason: '' }];
    return { ok: true, pending: true };
  },
  bloodbank_stock_set: (b) => {
    const t = Date.now();
    bloodBanks = bloodBanks.map((x) => (x.id === b.id ? { ...x, stock: b.groups ?? x.stock, stockUpdatedAt: t, stockStale: false } : x));
    const x = bloodBanks.find((y) => y.id === b.id);
    return { ok: true, id: b.id, stock: x?.stock ?? null, stockUpdatedAt: t, stockStale: false };
  },
  pharmacy_open_override: (b) => {
    // বিরতি ("আজকের কাজ"): open:false + until — সময় হলে নিজে খোলে
    const until = b.until || Date.now() + 3 * HOUR;
    pharmacy = { ...pharmacy, openNow: b.open == null ? true : !!b.open, openOverride: b.open == null ? null : { open: !!b.open, until, ...(b.until ? { pause: true } : {}) } };
    return { ok: true, openNow: pharmacy.openNow, openOverride: pharmacy.openOverride };
  },
  pharmacy_delivery_set: (b) => {
    pharmacy = { ...pharmacy, delivery: !!b.delivery };
    return { ok: true, delivery: pharmacy.delivery };
  },
  lab_open_override: (b) => {
    labOpen = { openNow: b.open !== false, openOverride: b.open == null ? null : { open: !!b.open, until: b.until || Date.now() + 3 * HOUR, ...(b.until ? { pause: true } : {}) } };
    return { ok: true, ...labOpen };
  },
  lab_home_collection_set: (b) => ({ ok: true, homeCollection: !!b.homeCollection }),
  bloodbank_delete: (b) => {
    bloodBanks = bloodBanks.filter((x) => x.id !== b.id);
    return { ok: true };
  },

  lab_save: (b) => {
    lab = { ...lab, ...b };
    return { ok: true, pending: true };
  },
  lab_delete: () => ({ ok: true }),
  verify_docs_upload: () => ({ ok: true, saved: 1, docsMissing: false, docsRequested: false }),
};
