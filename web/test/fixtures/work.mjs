// "আমার কাজ"-এর নকল উত্তর: ডেমো ব্যবহারকারীর ৩টা অ্যাম্বুলেন্স (একটা যাচাইয়ে), ১টা ফার্মেসি, ১টা ব্লাড ব্যাংক, ১টা প্যাথলজি,
// আর অনুমোদিত ডাক্তার ব্যাজ (চেম্বার কার্ড)। 00-common-এর খালি তালিকার উপর বসে (ফাইল বর্ণানুক্রমে আসে)।
import { ME } from './00-common.mjs';

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
    area: 'মেডিকেল কলেজ, চরপাড়া, ঢাকা রুট', fare: 'শহরের মধ্যে ৮০০ টাকা, ঢাকা ৫৫০০ টাকা', available: true, availableAt: now - 2 * HOUR,
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
};

let bloodBanks = [
  {
    id: 'bb1', name: 'সন্ধানী ব্লাড ব্যাংক, ময়মনসিংহ মেডিকেল', kind: 'org', phone: '01911000222', district: MYM, upazilaId: SADAR,
    address: 'ময়মনসিংহ মেডিকেল কলেজ, ছাত্রাবাস রোড', open24: false, openAt: '09:00', closeAt: '21:00', openNow: true,
    services: ['whole_blood', 'screening', 'donate_here'], closedDays: [], licenseNo: '', status: 'approved', statusReason: '',
  },
];

let lab = {
  id: 'u-work', name: 'পপুলার ডায়াগনস্টিক সেন্টার', phone: '01611223399', address: 'নতুন বাজার, সি কে ঘোষ রোড', district: MYM, upazilaId: SADAR,
  facilityId: null, homeCollection: true, hours: 'সকাল ৮টা – রাত ১০টা', licenseNo: 'HSM/DIAG/2021/889', status: 'pending', statusReason: '',
  docsMissing: false, docsRequested: true, docsRequestNote: 'লাইসেন্সের পুরো পাতার পরিষ্কার ছবি দিন — মেয়াদের তারিখ পড়া যাচ্ছে না।',
};

let findOff = false;
let finds = [
  { id: 'mf1', drug: { name: 'Napa Extra', generic: 'Paracetamol + Caffeine', form: 'Tablet', company: 'Beximco' }, area: 'চরপাড়া', km: 0.8, answer: null, createdAt: now - 12 * 60 * 1000 },
  { id: 'mf2', drug: { name: 'Insulin Mixtard 30', generic: 'Human insulin', form: 'Injection', company: 'Novo Nordisk' }, area: 'গাঙ্গিনাপাড়', km: 2.1, answer: 'yes', createdAt: now - 50 * 60 * 1000 },
  { id: 'mf3', drug: { name: 'Seclo 20', generic: 'Omeprazole', form: 'Capsule', company: 'Square' }, area: 'ত্রিশাল', km: 18.4, answer: 'no', createdAt: now - 3 * HOUR },
];

const ambDaily = { amb1: daily(1, 6), amb2: daily(3, 3), amb3: daily(0, 0).map(() => 0) };

export default {
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
    ambulances = ambulances.map((a) => (a.id === b.id ? { ...a, available: !!b.available, availableAt: b.available ? Date.now() : 0 } : a));
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
    finds = finds.map((f) => (f.id === b.id ? { ...f, answer: b.have ? 'yes' : 'no' } : f));
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
