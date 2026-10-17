// সেবা অংশের নকল উত্তর (অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি, ওষুধ খোঁজা) — সব নাম/নম্বর বানানো
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const geo = require('../../public/data/geo.json');
const CATALOG = require('../../../server/lib/catalog-defaults.json');
const DRUGS = require('../../../server/lib/drugs.json');

const D = geo.u.find((u) => u[0] === 466)[1]; // ময়মনসিংহ
const SADAR = 466;
const TRISHAL = 463;
const MUKTAGACHA = 465;
const H = 3600 * 1000;
const now = () => Date.now();

const empty = (b) => b.district !== D;

function ambulances(b, { authed }) {
  if (empty(b)) return { items: [], busyCount: 0, busy: [] };
  const t = now();
  let items = [
    { id: 'amb1', name: 'জননী অ্যাম্বুলেন্স সার্ভিস', phone: '01711000101', types: ['ac', 'icu'], district: D, upazilaId: SADAR, area: 'চরপাড়া মোড়', fare: 'শহরে ৮০০ টাকা, ঢাকা ৫৫০০', available: true, availableAt: t - 40 * 60 * 1000, rating: 4.6, ratingCount: 23, canRate: authed },
    { id: 'amb2', name: 'মমতা অ্যাম্বুলেন্স', phone: '01811000202', types: ['general'], district: D, upazilaId: TRISHAL, area: 'ত্রিশাল বাসস্ট্যান্ড', fare: 'আলোচনা সাপেক্ষে', available: true, availableAt: t - 3 * H, rating: 4.1, ratingCount: 9, canRate: false },
    { id: 'amb3', name: 'সেবা ফ্রিজার ভ্যান', phone: '01911000303', types: ['freezer'], district: D, upazilaId: SADAR, area: 'মেডিকেল কলেজ গেট', fare: '', available: false, availableAt: t - 20 * H, rating: 0, ratingCount: 0, canRate: false },
  ];
  if (b.type) items = items.filter((a) => a.types.includes(b.type));
  const busy = [
    { id: 'amb4', name: 'রহমত অ্যাম্বুলেন্স', phone: '01611000404', types: ['general', 'ac'], district: D, upazilaId: MUKTAGACHA, area: 'মুক্তাগাছা বাজার', fare: '৭০০ টাকা থেকে', available: false, availableAt: 0, rating: 3.8, ratingCount: 5, canRate: false, busy: true, busySince: t - 2 * H },
  ].filter((a) => !b.type || a.types.includes(b.type));
  return { items, busyCount: busy.length, busy };
}

function pharmacies(b) {
  if (empty(b)) return { items: [] };
  const gps = typeof b.lat === 'number';
  let items = [
    { id: 'ph1', name: 'লাইফ কেয়ার ফার্মেসি', phone: '01711000501', address: 'হাসপাতাল রোড, চরপাড়া', district: D, upazilaId: SADAR, openAt: '', closeAt: '', open24: true, openNow: true, closedUntil: 0, closedDays: [], delivery: true, deliveryNote: 'শহরের ভেতরে ৩০ টাকা', discount: 10, rating: 4.7, ratingCount: 31, km: gps ? 0.6 : null },
    { id: 'ph2', name: 'নিউ মডেল ড্রাগ হাউস', phone: '01811000602', address: 'গাঙ্গিনাপাড়, ছোটবাজার', district: D, upazilaId: SADAR, openAt: '09:00', closeAt: '23:00', open24: false, openNow: true, closedUntil: 0, closedDays: [5], delivery: false, deliveryNote: '', discount: 5, rating: 4.2, ratingCount: 12, km: gps ? 1.8 : null },
    { id: 'ph3', name: 'ত্রিশাল মেডিসিন কর্নার', phone: '01911000703', address: 'দরিরামপুর, ত্রিশাল', district: D, upazilaId: TRISHAL, openAt: '08:00', closeAt: '22:00', open24: false, openNow: false, closedUntil: now() + 3 * 24 * H, closedDays: [], delivery: true, deliveryNote: '', discount: 0, rating: 0, ratingCount: 0, km: gps ? 19.4 : null },
  ];
  if (b.openNow) items = items.filter((p) => p.openNow);
  return { items };
}

function bloodbanks(b) {
  if (empty(b)) return { items: [] };
  let items = [
    { id: 'bb1', name: 'জেলা সদর হাসপাতাল ব্লাড ব্যাংক', kind: 'govt', phone: '0912000001', district: D, upazilaId: SADAR, address: 'চরপাড়া, হাসপাতালের নিচতলা', open24: true, openAt: '', closeAt: '', openNow: true, services: ['whole_blood', 'components', 'platelets', 'screening'], closedDays: [] },
    { id: 'bb2', name: 'সন্ধানী ডোনার ক্লাব', kind: 'org', phone: '01711000808', district: D, upazilaId: SADAR, address: 'মেডিকেল কলেজ ক্যাম্পাস', open24: false, openAt: '09:00', closeAt: '17:00', openNow: true, services: ['whole_blood', 'donate_here'], closedDays: [5] },
    { id: 'bb3', name: 'প্রিমিয়ার ব্লাড সেন্টার', kind: 'private', phone: '01811000909', district: D, upazilaId: SADAR, address: 'সি.কে. ঘোষ রোড', open24: false, openAt: '08:00', closeAt: '20:00', openNow: false, services: ['whole_blood', 'screening'], closedDays: [] },
  ];
  if (b.open24) items = items.filter((x) => x.open24);
  return { items };
}

function labs(b) {
  if (empty(b)) return { items: [] };
  return {
    items: [
      { id: 'lab1', name: 'নিরাময় ডায়াগনস্টিক সেন্টার', phone: '01711001001', address: 'নওমহল, ময়মনসিংহ', district: D, upazilaId: SADAR, facilityId: null, homeCollection: true, hours: 'সকাল ৭টা – রাত ১০টা' },
      { id: 'lab2', name: 'ত্রিশাল প্যাথলজি সেন্টার', phone: '01811001102', address: 'ত্রিশাল পৌরসভা', district: D, upazilaId: TRISHAL, facilityId: null, homeCollection: false, hours: 'সকাল ৮টা – সন্ধ্যা ৭টা, শুক্রবার বন্ধ' },
    ],
  };
}

function drugSearch(b) {
  const q = String(b.q || '').trim().toLowerCase();
  if (q.length < 2) return { items: [] };
  const out = [];
  for (let i = 0; i < DRUGS.d.length && out.length < 15; i++) {
    const r = DRUGS.d[i];
    if (r[0].toLowerCase().startsWith(q)) out.push({ i, name: r[0], generic: r[1], form: DRUGS.f[r[2]] || '', company: DRUGS.c[r[3]] || '' });
  }
  return { items: out };
}

function medFindsMine(b, { authed }) {
  if (!authed) return { __status: 401, body: { error: 'login' } };
  const t = now();
  return {
    items: [
      {
        id: 'mf1', drug: { name: 'Napa Extend', generic: 'Paracetamol 665 mg', form: 'Tablet', company: 'Beximco Pharmaceuticals Ltd.' },
        area: `ময়মনসিংহ সদর, ${D}`, open: true, notified: 8, answered: 3, createdAt: t - 25 * 60 * 1000,
        have: [
          { id: 'ph1', name: 'লাইফ কেয়ার ফার্মেসি', phone: '01711000501', address: 'হাসপাতাল রোড, চরপাড়া', openNow: true, open24: true, km: 0.6, at: t - 20 * 60 * 1000 },
          { id: 'ph2', name: 'নিউ মডেল ড্রাগ হাউস', phone: '01811000602', address: 'গাঙ্গিনাপাড়, ছোটবাজার', openNow: true, open24: false, km: 1.8, at: t - 10 * 60 * 1000 },
        ],
      },
      { id: 'mf2', drug: { name: 'Seclo', generic: 'Omeprazole 20 mg', form: 'Capsule', company: 'Square Pharmaceuticals PLC' }, area: D, open: false, notified: 5, answered: 2, createdAt: t - 9 * H, have: [] },
    ],
  };
}

export default {
  catalog_get: () => ({ lists: CATALOG, updatedAt: 0 }),
  ambulances,
  pharmacies,
  bloodbanks,
  labs,
  drug_search: drugSearch,
  med_find: () => ({ ok: true, id: 'mf3', notified: 6 }),
  med_finds_mine: medFindsMine,
  med_find_close: () => ({ ok: true }),
  ambulance_called: () => ({ ok: true }),
  pharmacy_called: () => ({ ok: true }),
  bloodbank_called: () => ({ ok: true }),
  ambulance_rate: () => ({ ok: true }),
  report: () => ({ ok: true }),
};
