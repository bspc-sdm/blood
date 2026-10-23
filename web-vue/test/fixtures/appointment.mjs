// অ্যাপয়েন্টমেন্টের নকল উত্তর: ময়মনসিংহের ডেমো ডাক্তার/পুষ্টিবিদ/ট্রেইনার (চেম্বার, বার, ফি), বাসায় সেবার ডাক্তার/নার্স,
// রোগীর নিজের অ্যাপয়েন্টমেন্ট, আর পেশাদারের কাজের জায়গার জন্য (provider_me, সিরিয়ালের সারি, home_stats)।
// সব নাম/নম্বর বানানো। hospitals / catalog_get / family_get / report অন্য ফাইলে (blood, services, account)।
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const geo = require('../../public/data/geo.json');
const D = geo.u.find((u) => u[0] === 466)[1]; // ময়মনসিংহ (ডাটাবেসের বানানে)
const SADAR = 466;
const TRISHAL = 463;
const MUKTAGACHA = 465;
const DAY = 24 * 3600 * 1000;
const H = 3600 * 1000;
const today = () => {
  const d = new Date(Date.now() + 6 * H);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};

const ch = (o) => ({
  hospitalId: null, hospitalName: '', hospitalNameBn: '', district: D, upazilaId: SADAR, unionId: null, room: '', schedule: '',
  fee: '', noFee: false, appointments: true, serialPhone: '', days: [], start: '', end: '', ...o,
});
const MMCH = { hospitalId: 'dghs_1', hospitalName: 'Mymensingh Medical College Hospital', hospitalNameBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল' };
const CBMCH = { hospitalId: 'dghs_2', hospitalName: 'Community Based Medical College Hospital', hospitalNameBn: '' };
const LABIB = { hospitalId: 'dghs_3', hospitalName: 'Labib Hospital', hospitalNameBn: 'লাবিব হাসপাতাল' };

const PROVIDERS = [
  {
    uid: 'p_mahmuda', type: 'doctor', name: 'ডা. মাহমুদা আক্তার', specialties: ['medicine', 'endocrinology'], degrees: 'MBBS, FCPS (Medicine)', gender: 'female',
    listed: true, appointments: true, rating: 4.7, ratingCount: 38, photo: '',
    chambers: [
      ch({ ...LABIB, room: '২০৪', days: [6, 1, 3], start: '17:00', end: '21:00', fee: '৭০০ টাকা', serialPhone: '01711000222', schedule: 'সরকারি ছুটির দিন বন্ধ' }),
      ch({ ...MMCH, room: 'বহির্বিভাগ ৩১২', days: [0, 2, 4], start: '09:00', end: '13:00', fee: '৫০০ টাকা' }),
    ],
    homeVisit: { enabled: true, radiusKm: 5, upazilaIds: [MUKTAGACHA], services: ['checkup', 'follow_up', 'elderly_care'], schedule: 'এক ঘণ্টা আগে ফোন দিন', fee: '১০০০ টাকা + যাতায়াত', noFee: false, phone: '01711000333', times: [{ days: [5], start: '09:00', end: '12:00' }] },
  },
  {
    uid: 'p_rafiq', type: 'doctor', name: 'ডা. রফিকুল ইসলাম', specialties: ['cardiology'], degrees: 'MBBS, MD (Cardiology)', gender: 'male',
    listed: true, appointments: true, rating: 4.9, ratingCount: 52, photo: '',
    chambers: [ch({ ...MMCH, room: 'হৃদরোগ বিভাগ, ৫ম তলা', days: [6, 0, 1, 2, 3], start: '15:00', end: '19:00', fee: '১০০০ টাকা', serialPhone: '01811000444' })],
    homeVisit: null,
  },
  {
    uid: 'p_tanvir', type: 'doctor', name: 'ডা. তানভীর হাসান', specialties: ['pediatrics'], degrees: 'MBBS, DCH (Child Health)', gender: 'male',
    listed: true, appointments: true, rating: 4.5, ratingCount: 21, photo: '',
    chambers: [
      ch({
        ...CBMCH, room: '১১৫', days: [6, 1, 3, 5], start: '16:00', end: '20:00', fee: '৬০০ টাকা',
        times: [{ days: [6, 1, 3], start: '16:00', end: '20:00' }, { days: [5], start: '10:00', end: '13:00' }],
      }),
    ],
    homeVisit: null,
  },
  {
    uid: 'p_shahana', type: 'doctor', name: 'ডা. শাহানা পারভীন', specialties: ['gynecology'], degrees: 'MBBS, FCPS (Gynae & Obs)', gender: 'female',
    listed: true, appointments: false, rating: 0, ratingCount: 0, photo: '',
    chambers: [ch({ ...LABIB, room: '৩০১', days: [0, 2], start: '18:00', end: '21:00', fee: '৮০০ টাকা', appointments: false, serialPhone: '01911000555' })],
    homeVisit: null,
  },
  {
    uid: 'p_nusrat', type: 'nutritionist', name: 'নুসরাত জাহান', specialties: ['clinical_nutrition', 'diabetes_diet'], degrees: 'BSc, MSc (Food & Nutrition)', gender: 'female',
    listed: true, appointments: true, rating: 4.8, ratingCount: 14, photo: '',
    chambers: [ch({ hospitalName: 'নিউট্রি কেয়ার সেন্টার', hospitalNameBn: 'নিউট্রি কেয়ার সেন্টার', custom: true, address: '৩২ সি কে ঘোষ রোড', days: [6, 0, 1, 2, 3], start: '10:00', end: '14:00', fee: '৫০০ টাকা' })],
    homeVisit: null,
  },
  {
    uid: 'p_faisal', type: 'trainer', name: 'ফয়সাল আহমেদ', specialties: ['weight_loss', 'strength'], degrees: 'সার্টিফায়েড পার্সোনাল ট্রেইনার', gender: 'male',
    listed: true, appointments: true, rating: 4.4, ratingCount: 9, photo: '',
    chambers: [ch({ hospitalName: 'ফিট লাইফ জিম', hospitalNameBn: 'ফিট লাইফ জিম', custom: true, address: 'চরপাড়া মোড়, মেডিকেল রোড', days: [6, 0, 1, 2, 3, 4], start: '06:00', end: '09:00', fee: 'মাসে ১৫০০ টাকা' })],
    homeVisit: null,
  },
];

// সেবার এক ছাঁচের বাড়তি ঘর (backend/docs/LISTINGS.md): ratingSummary, feeAmount, nextSlot, verified
const SLOT = { p_mahmuda: [0, 'আজ', true, 0], p_rafiq: [1, 'কাল', true, 0], p_tanvir: [3, null, true, 0], p_nusrat: [0, 'আজ', false, 0], p_faisal: [1, 'কাল', true, 0] };
const FEE = { p_mahmuda: 700, p_rafiq: 1000, p_tanvir: 600, p_shahana: 800, p_nusrat: 500, p_faisal: 1500 };
function listingFields(p) {
  const s = SLOT[p.uid];
  return {
    ...p,
    ratingSummary: p.ratingCount > 0 ? { avg: p.rating, count: p.ratingCount } : null,
    feeAmount: FEE[p.uid] ?? null,
    verified: p.uid === 'p_mahmuda' || p.uid === 'p_rafiq',
    nextSlot: s ? { date: today() + s[0] * DAY, label: s[1] ?? new Date(today() + s[0] * DAY).getUTCDate() + ' অক্টোবর', available: s[2], chamberIndex: s[3] } : null,
    distanceKm: null,
  };
}

function providers(b) {
  return {
    items: PROVIDERS.map(listingFields).filter((p) => p.type === b.type)
      .filter((p) => !b.specialty || p.specialties.includes(b.specialty))
      .filter((p) => !b.homeVisit || (p.homeVisit && p.homeVisit.enabled))
      .filter((p) => (b.hospitalId ? p.chambers.some((c) => c.hospitalId === b.hospitalId) : !b.district || p.chambers.some((c) => c.district === b.district && (!b.upazilaId || c.upazilaId === b.upazilaId)))),
  };
}

const HOME = [
  {
    uid: 'p_mahmuda', type: 'doctor', name: 'ডা. মাহমুদা আক্তার', specialties: ['medicine', 'endocrinology'], degrees: 'MBBS, FCPS (Medicine)', gender: 'female',
    services: ['checkup', 'follow_up', 'elderly_care'], radiusKm: 5, upazilaIds: [MUKTAGACHA], schedule: 'এক ঘণ্টা আগে ফোন দিন',
    times: [{ days: [5], start: '09:00', end: '12:00' }], fee: '১০০০ টাকা + যাতায়াত', noFee: false, phone: '01711000333', km: 3,
    rating: 4.7, ratingCount: 38, called: false, myRating: 0, canRate: false, photo: '',
  },
  {
    uid: 'p_mizan', type: 'doctor', name: 'ডা. মিজানুর রহমান', specialties: ['general'], degrees: 'MBBS', gender: 'male',
    services: ['checkup', 'bedridden', 'palliative'], radiusKm: 10, upazilaIds: [TRISHAL], schedule: '',
    times: [{ days: [6, 0, 1, 2, 3], start: '19:00', end: '22:00' }], fee: '৮০০ টাকা', noFee: false, phone: '01811000666', km: 7,
    rating: 4.2, ratingCount: 6, called: true, myRating: 4, canRate: true, photo: '',
  },
  {
    uid: 'n_rumana', type: 'nurse', name: 'রুমানা বেগম', specialties: ['general_nursing', 'home_care'], degrees: 'ডিপ্লোমা ইন নার্সিং', gender: 'female',
    services: ['injection', 'saline', 'dressing', 'bp_sugar', 'elderly_care'], radiusKm: 5, upazilaIds: [], schedule: '',
    times: [{ days: [6, 0, 1, 2, 3, 4], start: '08:00', end: '20:00' }], fee: '৩০০ টাকা', noFee: false, phone: '01911000777', km: 2,
    rating: 4.9, ratingCount: 27, called: true, myRating: 0, canRate: true, photo: '',
  },
  {
    uid: 'n_zakir', type: 'nurse', name: 'জাকির হোসেন', specialties: ['icu'], degrees: 'বিএসসি ইন নার্সিং', gender: 'male',
    services: ['cannula', 'catheter', 'post_op', 'nebulizer'], radiusKm: 10, upazilaIds: [TRISHAL, MUKTAGACHA], schedule: 'রাতে জরুরি হলে ফোন দিন',
    times: [{ days: [5, 6], start: '09:00', end: '21:00' }], fee: '', noFee: true, phone: '01611000888', km: 6,
    rating: 0, ratingCount: 0, called: false, myRating: 0, canRate: false, photo: '',
  },
];

function homeProviders(b) {
  return {
    items: HOME.filter((p) => p.type === b.type)
      .filter((p) => !b.service || p.services.includes(b.service))
      .filter((p) => !b.female || p.gender === 'female')
      .filter(() => !b.district || b.district === D || typeof b.lat === 'number'),
  };
}

// রোগী হিসেবে নিজের, আর ডাক্তার হিসেবে নিজের চেম্বারে আসা (পেশাদারের কাজের জায়গা)
function appointments() {
  const t = today();
  const P = (o) => ({ role: 'patient', type: 'doctor', room: '', start: '', serial: 0, schedule: '', note: '', providerNote: '', otherPhone: '', otherKey: '', createdAt: Date.now() - 2 * DAY, ...o });
  const Q = (o) => ({ role: 'provider', type: 'doctor', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', room: '২০৪', start: '17:00', serial: 0, schedule: '', providerNote: '', otherPhone: '', createdAt: Date.now() - DAY, ...o });
  return {
    items: [
      P({ id: 'a0', otherName: 'ডা. রফিকুল ইসলাম', chamber: 'Mymensingh Medical College Hospital', chamberBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', room: 'হৃদরোগ বিভাগ, ৫ম তলা', start: '15:00', serial: 9, date: t, note: 'বুকে চাপ লাগে, ইসিজি দেখাতে', status: 'confirmed', otherPhone: '01811000444' }),
      P({ id: 'a1', otherName: 'ডা. মাহমুদা আক্তার', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', room: '২০৪', start: '17:00', serial: 7, date: t + DAY, note: 'ডায়াবেটিসের ফলো-আপ, সুগার বেশি থাকছে', status: 'confirmed', providerNote: 'সন্ধ্যা ৬টার মধ্যে আসবেন', otherPhone: '01711000222' }),
      P({ id: 'a2', otherName: 'ডা. তানভীর হাসান', chamber: 'Community Based Medical College Hospital', chamberBn: '', room: '১১৫', start: '16:00', date: t + 3 * DAY, note: 'ছেলের তিন দিন ধরে জ্বর আর কাশি', status: 'pending' }),
      P({ id: 'a3', otherName: 'ডা. রফিকুল ইসলাম', chamber: 'Mymensingh Medical College Hospital', chamberBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', start: '15:00', serial: 3, date: t - 12 * DAY, note: 'বুকে ব্যথা, ইসিজি রিপোর্ট দেখাতে', status: 'completed' }),
      P({ id: 'a4', otherName: 'ডা. শাহানা পারভীন', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', start: '18:00', date: t - 20 * DAY, note: 'নিয়মিত চেকআপ', status: 'cancelled', providerNote: 'ডাক্তার চেম্বার বা বার বদলেছেন' }),

      Q({ id: 'q1', otherName: 'আব্দুল করিম', otherKey: 'k1', serial: 1, date: t, note: 'উচ্চ রক্তচাপ, ওষুধ বদলাতে চাই', status: 'confirmed', otherPhone: '01711100001' }),
      Q({ id: 'q2', otherName: 'সালমা খাতুন', otherKey: 'k2', serial: 2, date: t, note: 'থাইরয়েড রিপোর্ট দেখাতে', status: 'confirmed', otherPhone: '01811100002', providerNote: 'রিপোর্ট সাথে আনবেন' }),
      Q({ id: 'q3', otherName: 'মো. হাবিবুর রহমান', otherKey: 'k3', serial: 3, date: t, note: 'মাথা ঘোরা, দুর্বল লাগে', status: 'completed', otherPhone: '01911100003' }),
      Q({ id: 'q4', otherName: 'নাসরিন সুলতানা', otherKey: 'k4', date: t, note: 'পেটে ব্যথা আর গ্যাস', status: 'pending', createdAt: Date.now() - 3 * H }),
      Q({ id: 'q5', otherName: 'রবিউল আলম', otherKey: 'k5', chamber: 'Mymensingh Medical College Hospital', chamberBn: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', room: 'বহির্বিভাগ ৩১২', start: '09:00', date: t, note: 'ডায়াবেটিস, ইনসুলিনের মাত্রা', status: 'pending', createdAt: Date.now() - H }),
      Q({ id: 'q6', otherName: 'আব্দুল করিম', otherKey: 'k1', serial: 1, date: t + 2 * DAY, note: 'আবার দেখাতে', status: 'confirmed', otherPhone: '01711100001' }),
      Q({ id: 'q7', otherName: 'ফাতেমা বেগম', otherKey: 'k6', date: t + 2 * DAY, note: 'হাঁটুতে ব্যথা, সিঁড়ি ভাঙতে কষ্ট', status: 'pending' }),
      Q({ id: 'q8', otherName: 'সালমা খাতুন', otherKey: 'k2', serial: 5, date: t - 7 * DAY, note: 'থাইরয়েড', status: 'completed' }),
      Q({ id: 'q9', otherName: 'কামরুল হাসান', otherKey: 'k7', date: t - 3 * DAY, note: 'জ্বর', status: 'declined' }),
      Q({ id: 'q10', otherName: 'শিরিন আক্তার', otherKey: 'k8', date: t - 5 * DAY, note: 'চেকআপ', status: 'cancelled' }),
    ],
  };
}

// সিরিয়াল চলছে (server/lib/queue.js): রোগীর আজকের a0, আর ডাক্তার/সহকারীর আজকের সারি
const M = 60 * 1000;
const r5 = (ms, up) => (up ? Math.ceil(ms / (5 * M)) : Math.floor(ms / (5 * M))) * 5 * M;
function appointmentQueue(b) {
  if (b.id !== 'a0') return { live: false };
  const eta = r5(Date.now() + 32 * M, true);
  return {
    live: true, serial: 9, started: true, paused: false, current: 5, state: 'waiting', ahead: 4, later: false, avgMin: 8, avgFrom: 'history',
    eta, arriveBy: r5(eta - 20 * M, false), late: { min: 20, note: 'জ্যামে আছি', at: Date.now() - 4 * M, status: 'asked' }, canLate: true,
  };
}
function queueToday() {
  const row = (id, serial, name, state, late = null) => ({ id, serial, name, status: state === 'cancelled' ? 'cancelled' : 'confirmed', state, ahead: 0, late });
  return {
    role: 'doctor',
    date: today(),
    chambers: [{
      date: today(), chamberKey: 'dghs_3', key: 'k3', name: 'Labib Hospital', nameBn: 'লাবিব হাসপাতাল', start: '17:00', avgMin: 8, avgFrom: 'history', lateAsked: 1,
      queue: { started: true, current: 2, paused: false, finished: false, next: 4, left: 2, max: 5, startedAt: Date.now() - 16 * M, lastAt: Date.now() - 8 * M },
      rows: [
        row('q1', 1, 'আব্দুল করিম', 'done'),
        row('q2', 2, 'সালমা খাতুন', 'now'),
        row('q3', 3, 'মো. হাবিবুর রহমান', 'later', { min: 30, note: '', at: Date.now() - 20 * M, status: 'later' }),
        row('q11', 4, 'জাহানারা বেগম', 'waiting', { min: 20, note: 'জ্যামে আটকে আছি', at: Date.now() - 5 * M, status: 'asked' }),
        row('q12', 5, 'মো. কামাল উদ্দিন', 'waiting'),
      ],
    }],
  };
}

// পেশাদারের নিজের প্রোফাইল (ডেমো ডাক্তার — work.mjs-এর অনুমোদিত ডাক্তার ব্যাজের সাথে মিলিয়ে)
const ME_PROVIDER = {
  uid: 'u1', type: 'doctor', name: 'ডা. আরিফুল হক', specialties: ['medicine', 'endocrinology'], degrees: 'MBBS, FCPS (Medicine)', gender: 'male',
  listed: true, appointments: true, rating: 4.6, ratingCount: 17, photo: '',
  chambers: [
    ch({ ...LABIB, room: '২০৪', days: [6, 1, 3], start: '17:00', end: '21:00', fee: '৭০০ টাকা', serialPhone: '01711000999', schedule: 'সরকারি ছুটির দিন বন্ধ' }),
    ch({ ...MMCH, room: 'বহির্বিভাগ ৩১২', days: [0, 2, 4], start: '09:00', end: '13:00', fee: '৫০০ টাকা' }),
  ],
  homeVisit: { enabled: true, radiusKm: 5, upazilaIds: [MUKTAGACHA], services: ['checkup', 'elderly_care'], schedule: '', fee: '১০০০ টাকা', noFee: false, phone: '01711000999', times: [{ days: [5], start: '09:00', end: '12:00' }] },
};

// "আমার চেম্বার"-এর "রোগীরা আপনাকে যেভাবে দেখেন" আর "আজ সিরিয়াল চলছে / আজ বন্ধ" (chamber_days, chamber_day_set)
// দুটো চেম্বারই আজ বসে ধরা (নকল) — বন্ধ করলে nextSlot কালকে যায়
const closedToday = new Set();
function chamberDays() {
  const items = ME_PROVIDER.chambers.map((c, i) => ({
    chamberIndex: i, chamberKey: c.hospitalId || 'c' + i, name: c.hospitalName, nameBn: c.hospitalNameBn, online: false, appointments: c.appointments,
    sitsToday: true, closedToday: closedToday.has(i), closedDates: [], requestsToday: i === 0 ? 2 : 0,
  }));
  const open = items.find((x) => !x.closedToday);
  const card = {
    ...ME_PROVIDER, ratingSummary: { avg: 4.6, count: 17 }, feeAmount: open?.chamberIndex === 1 ? 500 : 700, verified: true,
    nextSlot: open ? { date: today(), label: 'আজ', available: true, chamberIndex: open.chamberIndex } : { date: today() + DAY, label: 'কাল', available: true, chamberIndex: 0 },
  };
  return { doctorUid: 'u1', role: 'doctor', items, card };
}

const daily = Array.from({ length: 30 }, (_, i) => Math.max(0, Math.round(((Math.sin(i * 0.7 + 1) + 1) / 2) * 4 + (i > 24 ? 1 : 0))));
const sum = (n) => daily.slice(30 - n).reduce((a, b) => a + b, 0);

export default {
  providers,
  home_providers: homeProviders,
  home_called: () => ({ ok: true }),
  home_rate: () => ({ ok: true }),
  home_stats: () => ({
    enabled: true, blocked: false,
    calls: { 7: sum(7), 30: sum(30), 90: sum(30) + 22, total: sum(30) + 60 }, callers: Math.round(sum(30) * 0.7), daily,
    rating: 4.6, ratingCount: 17, reports: 0,
    comments: [
      { rating: 5, comment: 'সময়মতো এসেছেন, খুব যত্ন নিয়ে দেখেছেন', at: Date.now() - 2 * DAY },
      { rating: 4, comment: 'ভালো, তবে একটু দেরি হয়েছে', at: Date.now() - 6 * DAY },
      { rating: 5, comment: '', at: Date.now() - 11 * DAY },
    ],
  }),
  book: () => ({ id: 'a_new' }),
  my_appointments: appointments,
  appointment_update: () => ({ ok: true }),
  appointment_queue: appointmentQueue,
  appointment_late: () => ({ ok: true }),
  appointment_late_reply: () => ({ ok: true, chamber: queueToday().chambers[0] }),
  queue_today: queueToday,
  queue_control: () => ({ chamber: queueToday().chambers[0] }),
  provider_me: () => ({ provider: ME_PROVIDER, card: chamberDays().card }),
  chamber_days: chamberDays,
  chamber_day_set: (b) => {
    if (b.closed) closedToday.add(b.chamberIndex);
    else closedToday.delete(b.chamberIndex);
    return { ok: true, chamberIndex: b.chamberIndex, closed: !!b.closed, requests: b.chamberIndex === 0 ? 2 : 0 };
  },
  provider_save: () => ({ ok: true, cancelled: 0 }),
};
