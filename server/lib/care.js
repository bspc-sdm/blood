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
    const schedule = str(c.schedule, 120);
    if (!schedule) return { error: ['Enter days and time for each chamber', 'প্রতিটা চেম্বারে কোন দিন ও কখন বসেন লিখুন'] };
    out.push({
      hospitalId: h.id,
      hospitalName: h.nameEn,
      hospitalNameBn: h.nameBn || '',
      district: h.district,
      upazilaId: h.upazilaId || null,
      unionId: h.unionId || null,
      room: str(c.room, 60),
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
  if (!h || h.enabled !== true) {
    return { homeVisit: { enabled: false, radiusKm: 5, upazilaIds: [], services: [], schedule: '', fee: '' } };
  }
  const services = pickCodes(h.services, HOME_SERVICES, HOME_SERVICES.length);
  if (!services.length) return { error: ['Choose the services you provide at home', 'বাসায় কোন কোন সেবা দেন বেছে নিন'] };
  const radiusKm = HOME_RADII.includes(h.radiusKm) ? h.radiusKm : 5;
  const upazilaIds = Array.isArray(h.upazilaIds) ? [...new Set(h.upazilaIds.filter(Number.isInteger))].slice(0, 10) : [];
  return {
    homeVisit: {
      enabled: true,
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
  if (typeof nurse.lat !== 'number' || typeof nurse.lng !== 'number') return false;
  return R.distanceKm(visit.lat, visit.lng, nurse.lat, nurse.lng) <= (h.radiusKm || 5);
}

function visitExpired(v, now = Date.now()) {
  return v.status === 'open' && now - (v.createdAt || 0) > VISIT_OPEN_MS;
}

module.exports = {
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
  takesAppointments,
  matchesFilter,
  parseHomeVisit,
  nurseCovers,
  visitExpired,
};
