// ডাক্তার/নার্সের চেম্বার আর হোম নার্সের খাঁটি নিয়ম (ডাটাবেস ছাড়া, তাই টেস্ট করা যায়)।
const R = require('./rules');

const DOCTOR_SPECIALTIES = [
  'medicine', 'pediatrics', 'gynecology', 'cardiology', 'orthopedics', 'ent', 'eye', 'dermatology',
  'neurology', 'psychiatry', 'surgery', 'nephrology', 'urology', 'gastro', 'oncology', 'dental',
  'endocrinology', 'chest', 'physical_medicine', 'general',
];
const NURSE_SPECIALTIES = ['general_nursing', 'icu', 'midwife', 'home_care', 'pediatric_nursing', 'psychiatric_nursing'];

// হোম নার্সের সেবা; প্রথম তিনটার জন্য প্রেসক্রিপশন লাগবে
const HOME_SERVICES = [
  'injection', 'saline', 'cannula', 'dressing', 'catheter', 'nebulizer', 'bp_sugar', 'elderly_care', 'newborn_care', 'post_op',
];
const NEEDS_PRESCRIPTION = ['injection', 'saline', 'cannula'];
const HOME_RADII = [3, 5, 10];
const MAX_CHAMBERS = 3;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const VISIT_OPEN_MS = 24 * 60 * 60 * 1000; // খোলা অনুরোধ ২৪ ঘণ্টা পর আর দেখায় না

function str(v, max) {
  return typeof v === 'string' ? v.trim().slice(0, max) : '';
}

function phone(v) {
  return typeof v === 'string' ? v.replace(/[^0-9+]/g, '').slice(0, 15) : '';
}

function specialtiesFor(type) {
  return type === 'nurse' ? NURSE_SPECIALTIES : DOCTOR_SPECIALTIES;
}

/** তালিকা থেকে শুধু বৈধ, অনন্য কোড */
function pickCodes(v, allowed, max) {
  if (!Array.isArray(v)) return [];
  return [...new Set(v.filter((x) => allowed.includes(x)))].slice(0, max);
}

/**
 * অ্যাপের পাঠানো চেম্বার যাচাই। hospitals: id → হাসপাতাল (সার্ভার আগে থেকে পড়ে রাখে)।
 * ভুল থাকলে { error: [en, bn] }।
 */
function parseChambers(list, hospitals) {
  if (!Array.isArray(list) || list.length === 0) return { error: ['Add at least one chamber', 'অন্তত একটা চেম্বার যোগ করুন'] };
  if (list.length > MAX_CHAMBERS) return { error: ['Up to 3 chambers', 'সর্বোচ্চ ৩টা চেম্বার'] };
  const out = [];
  for (const c of list) {
    const h = c && hospitals.get(c.hospitalId);
    if (!h) return { error: ['Choose the hospital from the list', 'তালিকা থেকে হাসপাতাল বেছে নিন'] };
    // বার (০ = রবি … ৬ = শনি, JS-এর মতো) আর সময় ("17:00"); লেখা শুধু বাড়তি নোট
    const days = Array.isArray(c.days) ? [...new Set(c.days.filter((d) => Number.isInteger(d) && d >= 0 && d <= 6))].sort() : [];
    const start = TIME_RE.test(c.start) ? c.start : '';
    const end = TIME_RE.test(c.end) ? c.end : '';
    const schedule = str(c.schedule, 120);
    if (!days.length) return { error: ['Choose the days for each chamber', 'প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন'] };
    if (!start || !end || end === start) return { error: ['Set the start and end time for each chamber', 'প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন'] };
    out.push({
      hospitalId: h.id,
      hospitalName: h.nameEn,
      hospitalNameBn: h.nameBn || '',
      district: h.district,
      upazilaId: h.upazilaId || null,
      unionId: h.unionId || null,
      room: str(c.room, 60),
      days,
      start,
      end,
      schedule,
      fee: str(c.fee, 40),
      appointments: c.appointments === true,
      serialPhone: phone(c.serialPhone),
    });
  }
  return { chambers: out };
}

/** আগের ভার্সনের এক চেম্বারের ডক → নতুন ধরন */
function chambersOf(doc) {
  if (Array.isArray(doc.chambers)) return doc.chambers;
  if (!doc.chamber) return [];
  return [{
    hospitalId: null,
    hospitalName: doc.chamber,
    hospitalNameBn: '',
    district: doc.district || '',
    upazilaId: null,
    unionId: null,
    room: '',
    schedule: doc.schedule || '',
    fee: doc.fee || '',
    appointments: doc.active !== false,
    serialPhone: '',
  }];
}

const DAY_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DAY_ORDER = [6, 0, 1, 2, 3, 4, 5];
/** আগের ভার্সনের অ্যাপের জন্য এক লাইনে সময়: "Sat, Sun · 17:00–21:00 · নোট" */
function scheduleText(c) {
  if (!c) return '';
  const parts = [];
  if (Array.isArray(c.days) && c.days.length) parts.push(DAY_ORDER.filter((d) => c.days.includes(d)).map((d) => DAY_EN[d]).join(', '));
  if (c.start && c.end) parts.push(`${c.start}–${c.end}`);
  if (c.schedule) parts.push(c.schedule);
  return parts.join(' · ');
}

/** এই তারিখে (UTC মধ্যরাত) চেম্বার বসে কি না; পুরোনো চেম্বারে বার না থাকলে সব দিন */
function chamberOpenOn(chamber, dateUtcMidnight) {
  const days = chamber && Array.isArray(chamber.days) ? chamber.days : [];
  if (!days.length) return true;
  return days.includes(new Date(dateUtcMidnight).getUTCDay());
}

function isListed(doc) {
  return doc.listed === undefined ? doc.active !== false : doc.listed === true;
}

/** অ্যাপে অ্যাপয়েন্টমেন্ট নেন কি না (মূল সুইচ) */
function takesAppointments(doc) {
  return doc.appointments === undefined ? doc.active !== false : doc.appointments === true;
}

function chamberMatches(c, f) {
  if (f.hospitalId) return c.hospitalId === f.hospitalId;
  if (f.district && c.district !== f.district) return false;
  if (f.upazilaId && c.upazilaId !== f.upazilaId) return false;
  if (f.unionId && c.unionId !== f.unionId) return false;
  return true;
}

/** খোঁজার ফিল্টার: অন্তত একটা চেম্বার মিললেই; বিশেষজ্ঞতা মিলতে হবে */
function matchesFilter(doc, f) {
  if (f.specialty && !(doc.specialties || []).includes(f.specialty)) return false;
  const any = f.hospitalId || f.district || f.upazilaId || f.unionId;
  if (!any) return true;
  return chambersOf(doc).some((c) => chamberMatches(c, f));
}

/** হোম ভিজিটের সেটিং যাচাই */
function parseHomeVisit(h) {
  h = h || {};
  const enabled = h.enabled === true;
  const services = pickCodes(h.services, HOME_SERVICES, HOME_SERVICES.length);
  // বন্ধ করলেও আগের সেবা/এলাকা থেকে যায়, আবার চালু করলে নতুন করে লিখতে হয় না
  if (enabled && !services.length) return { error: ['Choose the services you provide at home', 'বাসায় কোন কোন সেবা দেন বেছে নিন'] };
  const radiusKm = HOME_RADII.includes(h.radiusKm) ? h.radiusKm : 5;
  const upazilaIds = Array.isArray(h.upazilaIds) ? [...new Set(h.upazilaIds.filter(Number.isInteger))].slice(0, 10) : [];
  return {
    homeVisit: {
      enabled,
      radiusKm,
      upazilaIds,
      services,
      schedule: str(h.schedule, 120),
      fee: str(h.fee, 60),
    },
  };
}

/**
 * এই নার্স কি এই অনুরোধে যেতে পারেন? nurse = providers ডক + users ডকের lat/lng/gender।
 * এলাকা: নিজের লোকেশন থেকে radiusKm-এর মধ্যে, অথবা বেছে রাখা উপজেলায়।
 */
function nurseCovers(nurse, visit) {
  const h = nurse.homeVisit;
  if (!h || h.enabled !== true) return false;
  if (!visit.services.every((s) => h.services.includes(s))) return false;
  if (visit.femalePreferred && nurse.gender !== 'female') return false;
  if (visit.upazilaId && (h.upazilaIds || []).includes(visit.upazilaId)) return true;
  // শুধু জেলা বেছে নেওয়া লোকেশন জেলার মাঝখান — দূরত্বের হিসাবে ভুল হয়, তাই শুধু GPS হলে
  if (nurse.locationSource !== 'gps') return false;
  if (typeof nurse.lat !== 'number' || typeof nurse.lng !== 'number') return false;
  return R.distanceKm(visit.lat, visit.lng, nurse.lat, nurse.lng) <= (h.radiusKm || 5);
}

/**
 * খোলা অনুরোধ কখন আর দেখাবে না: খোলার (বা আবার খোলার) ২৪ ঘণ্টা পর,
 * তবে পরের কোনো সময়ের জন্য হলে সেই সময়ের ১ ঘণ্টা পর পর্যন্ত খোলা থাকে।
 */
function visitExpired(v, now = Date.now()) {
  if (v.status !== 'open') return false;
  const opened = v.openedAt || v.createdAt || 0;
  const until = Math.max(opened + VISIT_OPEN_MS, (v.when || 0) + 60 * 60 * 1000);
  return now > until;
}

/** "01712-345678" / "+8801712345678" → "8801712345678" (বাংলাদেশি মোবাইল না হলে null) */
function normalizeBd(raw) {
  const d = String(raw || '').replace(/\D/g, '');
  const local = d.startsWith('880') ? d.slice(2) : d.startsWith('0') ? d : `0${d}`;
  return /^01[3-9]\d{8}$/.test(local) ? `88${local}` : null;
}

// ---------- ফার্মেসি ----------
const MED_NOTIFY = 8; // কতগুলো কাছের দোকানের মধ্যে থেকে বাছাই (অর্ডার যায় এক সময়ে একটাতেই)
const MED_FORWARD_MS = 10 * 60 * 1000; // উত্তর না এলে এতক্ষণ পর রোগী অন্য দোকানে পাঠাতে পারেন
const MED_RADIUS_KM = 5;
const MED_OPEN_MS = 3 * 3600 * 1000; // উত্তরের জন্য অর্ডার ৩ ঘণ্টা খোলা

/** বাংলাদেশের সময় "HH:MM" */
function dhakaHHMM(now = Date.now()) {
  const d = new Date(now + 6 * 3600 * 1000);
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
}

/** দোকান এখন খোলা কি না (রাত পেরিয়ে খোলা থাকলেও) */
function pharmacyOpenNow(p, now = Date.now()) {
  if (p.open24) return true;
  const o = p.openAt;
  const c = p.closeAt;
  if (!TIME_RE.test(o || '') || !TIME_RE.test(c || '')) return true;
  const t = dhakaHHMM(now);
  return o < c ? t >= o && t < c : t >= o || t < c;
}

/**
 * অর্ডার কোন দোকানগুলোতে যাবে: খোলা, অনুমোদিত, কাছের (GPS থাকলে ৫ কিমি, নইলে একই উপজেলা), কাছেরটা আগে।
 * pharmacies: [{ id, lat, lng, upazilaId, ... }]
 */
function pickPharmacies(order, pharmacies, now = Date.now(), exclude = []) {
  return pharmacies
    .filter((p) => !exclude.includes(p.id) && pharmacyOpenNow(p, now))
    .map((p) => {
      const km = typeof p.lat === 'number' && typeof order.lat === 'number' ? R.distanceKm(order.lat, order.lng, p.lat, p.lng) : null;
      return { ...p, km };
    })
    .filter((p) => (p.km != null && p.km <= MED_RADIUS_KM) || (order.upazilaId && p.upazilaId === order.upazilaId))
    .sort((a, b) => (a.km ?? 99) - (b.km ?? 99))
    .slice(0, MED_NOTIFY);
}

function medOrderExpired(o, now = Date.now()) {
  return o.status === 'open' && now - (o.openedAt || o.createdAt || 0) > MED_OPEN_MS;
}

module.exports = {
  MED_NOTIFY,
  MED_FORWARD_MS,
  MED_RADIUS_KM,
  MED_OPEN_MS,
  dhakaHHMM,
  pharmacyOpenNow,
  pickPharmacies,
  medOrderExpired,
  normalizeBd,
  DOCTOR_SPECIALTIES,
  NURSE_SPECIALTIES,
  HOME_SERVICES,
  NEEDS_PRESCRIPTION,
  HOME_RADII,
  MAX_CHAMBERS,
  VISIT_OPEN_MS,
  str,
  phone,
  specialtiesFor,
  pickCodes,
  parseChambers,
  chambersOf,
  isListed,
  chamberOpenOn,
  scheduleText,
  takesAppointments,
  matchesFilter,
  parseHomeVisit,
  nurseCovers,
  visitExpired,
};
