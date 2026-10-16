// ডাক্তার/নার্সের চেম্বার আর বাসায় সেবার খাঁটি নিয়ম (ডাটাবেস ছাড়া, তাই টেস্ট করা যায়)।
const R = require('./rules');

const DOCTOR_SPECIALTIES = [
  'medicine', 'pediatrics', 'gynecology', 'cardiology', 'orthopedics', 'ent', 'eye', 'dermatology',
  'neurology', 'psychiatry', 'surgery', 'nephrology', 'urology', 'gastro', 'oncology', 'dental',
  'endocrinology', 'chest', 'physical_medicine', 'general',
];
const NURSE_SPECIALTIES = ['general_nursing', 'icu', 'midwife', 'home_care', 'pediatric_nursing', 'psychiatric_nursing'];
const NUTRITIONIST_SPECIALTIES = ['clinical_nutrition', 'weight_diet', 'diabetes_diet', 'child_nutrition', 'pregnancy_nutrition', 'kidney_diet', 'sports_nutrition'];
const TRAINER_SPECIALTIES = ['weight_loss', 'strength', 'yoga', 'rehab_exercise', 'elderly_fitness', 'women_fitness', 'sports_training'];

// বাসায় গিয়ে নার্স যে সেবা দেন; প্রথম তিনটার জন্য রোগীর কাছে প্রেসক্রিপশন থাকা লাগে
const HOME_SERVICES = [
  'injection', 'saline', 'cannula', 'dressing', 'catheter', 'nebulizer', 'bp_sugar', 'elderly_care', 'newborn_care', 'post_op',
];
// বাসায় গিয়ে ডাক্তার যে সেবা দেন
const DOCTOR_HOME_SERVICES = ['checkup', 'follow_up', 'elderly_care', 'bedridden', 'child', 'palliative', 'post_op'];
const NEEDS_PRESCRIPTION = ['injection', 'saline', 'cannula'];
const HOME_RADII = [3, 5, 10, 20];
const MAX_CHAMBERS = 3;
// এক চেম্বারে বার অনুযায়ী আলাদা সময় (যেমন শনি-সোম বিকাল, শুক্র সকাল)
const MAX_CHAMBER_TIMES = 4;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const VISIT_OPEN_MS = 24 * 60 * 60 * 1000; // আগের অনুরোধ-ব্যবস্থা (বন্ধ) — পুরোনো ডক পড়ার জন্য

function str(v, max) {
  return typeof v === 'string' ? v.trim().slice(0, max) : '';
}

function phone(v) {
  return typeof v === 'string' ? v.replace(/[^0-9+]/g, '').slice(0, 15) : '';
}

function specialtiesFor(type) {
  // প্যানেলের তালিকা থেকে (নতুন যোগ করা সহ); না পেলে নিচের নির্দিষ্টগুলো
  const fromCatalog = require('./catalog').codes('specialty', ['nurse', 'nutritionist', 'trainer'].includes(type) ? type : 'doctor');
  if (fromCatalog.length) return fromCatalog;
  if (type === 'nurse') return NURSE_SPECIALTIES;
  if (type === 'nutritionist') return NUTRITIONIST_SPECIALTIES;
  if (type === 'trainer') return TRAINER_SPECIALTIES;
  return DOCTOR_SPECIALTIES;
}

/** তালিকা থেকে শুধু বৈধ, অনন্য কোড */
function pickCodes(v, allowed, max) {
  if (!Array.isArray(v)) return [];
  return [...new Set(v.filter((x) => allowed.includes(x)))].slice(0, max);
}

/** নিজের লেখা জায়গা: নাম, জেলা (আর থাকলে উপজেলা), ঠিকানা। অনুমোদন লাগে না — সমস্যা হলে অ্যাডমিন পরে সরান/বন্ধ করেন */
function customPlace(c) {
  const DISTRICTS = require('./districts');
  const { UPAZILA } = require('./hospital-match');
  const name = str(c.placeName, 100);
  const address = str(c.address, 150);
  if (name.length < 3) return { error: ['Write the name of the place', 'জায়গার নাম লিখুন'] };
  if (!DISTRICTS.some((d) => d.bn === c.district)) return { error: ['Choose the district', 'জেলা বেছে নিন'] };
  if (address.length < 5) return { error: ['Write the address', 'ঠিকানা লিখুন'] };
  const u = Number.isInteger(c.upazilaId) ? UPAZILA.get(c.upazilaId) : null;
  return { id: null, nameEn: name, nameBn: name, district: c.district, upazilaId: u && u.district === c.district ? u.id : null, unionId: null, custom: true, address };
}

/**
 * অ্যাপের পাঠানো চেম্বার যাচাই। hospitals: id → হাসপাতাল (সার্ভার আগে থেকে পড়ে রাখে)।
 * ভুল থাকলে { error: [en, bn] }।
 */
function parseChambers(list, hospitals, allowCustom = false) {
  if (!Array.isArray(list) || list.length === 0) return { error: ['Add at least one chamber', 'অন্তত একটা চেম্বার যোগ করুন'] };
  if (list.length > MAX_CHAMBERS) return { error: ['Up to 3 chambers', 'সর্বোচ্চ ৩টা চেম্বার'] };
  const out = [];
  for (const c of list) {
    // হাসপাতালের তালিকা থেকে বাছা, অথবা নিজের জায়গা (জিম, অফিস, দোকান) নাম-জেলা-ঠিকানা লিখে
    let h = c && hospitals.get(c.hospitalId);
    // নিজের লেখা জায়গা শুধু পুষ্টিবিদ আর ফিটনেস ট্রেইনারের (তাঁরা হাসপাতালে বসেন না); ডাক্তার তালিকা থেকে বাছেন
    if (!h && c && !c.hospitalId && allowCustom) {
      const place = customPlace(c);
      if (place.error) return place;
      h = place;
    }
    if (!h) return { error: ['Choose the hospital from the list', 'তালিকা থেকে হাসপাতাল বেছে নিন'] };
    // বার (০ = রবি … ৬ = শনি, JS-এর মতো) আর সময় ("17:00"); লেখা শুধু বাড়তি নোট।
    // times: বার অনুযায়ী আলাদা সময়ের ভাগ; না থাকলে (আগের অ্যাপ) উপরের days/start/end একটাই ভাগ
    const rawTimes = Array.isArray(c.times) && c.times.length ? c.times : [{ days: c.days, start: c.start, end: c.end }];
    const parsedTimes = parseTimes(rawTimes, ['Choose the days for each chamber', 'প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন'], ['Set the start and end time for each chamber', 'প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন']);
    if (parsedTimes.error) return parsedTimes;
    const { times } = parsedTimes;
    // উপরের days = সব ভাগের বার একসাথে (বুকিং-এর বার মেলানো, আগের অ্যাপ); start/end = প্রথম ভাগ
    const days = [...new Set(times.flatMap((t) => t.days))].sort();
    const { start, end } = times[0];
    const schedule = str(c.schedule, 120);
    out.push({
      hospitalId: h.id,
      hospitalName: h.nameEn,
      hospitalNameBn: h.nameBn || '',
      district: h.district,
      upazilaId: h.upazilaId || null,
      unionId: h.unionId || null,
      ...(h.custom ? { custom: true, address: h.address } : {}),
      room: str(c.room, 60),
      days,
      start,
      end,
      ...(times.length > 1 ? { times } : {}),
      schedule,
      // "ফি চাই না" টিক দিলে ফি ফাঁকা থাকে
      noFee: c.noFee === true,
      fee: c.noFee === true ? '' : str(c.fee, 40),
      appointments: c.appointments === true,
      serialPhone: phone(c.serialPhone),
    });
  }
  // একজন একই সময়ে দুই জায়গায় বসতে পারেন না: সব চেম্বারের সব ভাগ মিলিয়ে একই বারে সময় মিলে গেলে আটকানো
  const clash = timeClash(out);
  if (clash) {
    const [a, b] = clash;
    const same = a === b;
    return {
      error: same
        ? ['Two times in the same chamber overlap on the same day', `"${a.hospitalNameBn || a.hospitalName}" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে`]
        : [`${a.hospitalName} and ${b.hospitalName} overlap on the same day and time`, `"${a.hospitalNameBn || a.hospitalName}" আর "${b.hospitalNameBn || b.hospitalName}" একই বারে একই সময়ে পড়েছে — একসাথে দুই জায়গায় বসা যায় না`],
    };
  }
  return { chambers: out };
}

/** বার আর শুরু–শেষের ভাগগুলো যাচাই (চেম্বার আর বাসায় সেবা দুটোতেই) */
function parseTimes(raw, daysErr, timeErr) {
  if (!Array.isArray(raw)) return { times: [] };
  if (raw.length > MAX_CHAMBER_TIMES) return { error: ['Up to 4 time slots', 'সর্বোচ্চ ৪টা সময়'] };
  const times = [];
  for (const t of raw) {
    const td = Array.isArray(t?.days) ? [...new Set(t.days.filter((d) => Number.isInteger(d) && d >= 0 && d <= 6))].sort() : [];
    const ts = TIME_RE.test(t?.start) ? t.start : '';
    const te = TIME_RE.test(t?.end) ? t.end : '';
    if (!td.length) return { error: daysErr };
    if (!ts || !te || te === ts) return { error: timeErr };
    times.push({ days: td, start: ts, end: te });
  }
  return { times };
}

const toMin = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
const WEEK_MIN = 7 * 1440;

/** সপ্তাহের মিনিটে সময়ের টুকরো; রাত পেরোলে (শেষ < শুরু, যেমন ২০:০০–০০:০০) পরের দিনে গড়ায় */
function weekIntervals(t) {
  const out = [];
  const s = toMin(t.start);
  let e = toMin(t.end);
  if (e <= s) e += 1440;
  for (const d of t.days) {
    const a = d * 1440 + s;
    const b = d * 1440 + e;
    if (b <= WEEK_MIN) out.push([a, b]);
    else out.push([a, WEEK_MIN], [0, b - WEEK_MIN]);
  }
  return out;
}

/** দুই ভাগের সময় মিলে গেলে সেই দুই চেম্বার, নইলে null */
function timeClash(chambers) {
  const all = [];
  for (const c of chambers) for (const t of chamberTimes(c)) for (const iv of weekIntervals(t)) all.push({ c, iv });
  all.sort((x, y) => x.iv[0] - y.iv[0]);
  for (let i = 1; i < all.length; i++) {
    // আগের যেকোনো টুকরোর শেষের আগে শুরু হলে মিলে গেছে (শুধু ছোঁয়া — ১২:০০ শেষ, ১২:০০ শুরু — চলে)
    for (let j = i - 1; j >= 0; j--) {
      if (all[j].iv[1] > all[i].iv[0]) return [all[j].c, all[i].c];
    }
  }
  return null;
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
  const slot = (t) => {
    const parts = [];
    if (Array.isArray(t.days) && t.days.length) parts.push(DAY_ORDER.filter((d) => t.days.includes(d)).map((d) => DAY_EN[d]).join(', '));
    if (t.start && t.end) parts.push(`${t.start}–${t.end}`);
    return parts.join(' · ');
  };
  const parts = [chamberTimes(c).map(slot).filter(Boolean).join('; ')].filter(Boolean);
  if (c.schedule) parts.push(c.schedule);
  return parts.join(' · ');
}

/** চেম্বারের সময়ের ভাগগুলো (আগের ডকে একটাই: days/start/end) */
function chamberTimes(c) {
  if (!c) return [];
  if (Array.isArray(c.times) && c.times.length) return c.times;
  return [{ days: Array.isArray(c.days) ? c.days : [], start: c.start || '', end: c.end || '' }];
}

/** এই তারিখের বারে যে ভাগগুলো বসে (বার না দেওয়া পুরোনো চেম্বারে সব) */
function chamberTimesOn(c, dateUtcMidnight) {
  const day = new Date(dateUtcMidnight).getUTCDay();
  return chamberTimes(c).filter((t) => !t.days.length || t.days.includes(day));
}

/** এই তারিখে (UTC মধ্যরাত) চেম্বার বসে কি না; পুরোনো চেম্বারে বার না থাকলে সব দিন */
function chamberOpenOn(chamber, dateUtcMidnight) {
  if (!chamber) return false;
  return chamberTimesOn(chamber, dateUtcMidnight).length > 0;
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

function homeServicesFor(type) {
  const fromCatalog = require('./catalog').codes('homeService', type === 'doctor' ? 'doctor' : 'nurse');
  if (fromCatalog.length) return fromCatalog;
  return type === 'doctor' ? DOCTOR_HOME_SERVICES : HOME_SERVICES;
}

/**
 * বাসায় সেবার সেটিং যাচাই। ফোন নম্বরটা তালিকায় সবাই দেখবেন (রোগী সরাসরি ফোন করেন),
 * তাই চালু করলে আলাদা করে দিতে হয়।
 */
function parseHomeVisit(h, type = 'nurse') {
  h = h || {};
  const enabled = h.enabled === true;
  const services = pickCodes(h.services, homeServicesFor(type), 10);
  // বন্ধ করলেও আগের সেবা/এলাকা থেকে যায়, আবার চালু করলে নতুন করে লিখতে হয় না
  if (enabled && !services.length) return { error: ['Choose the services you provide at home', 'বাসায় কোন কোন সেবা দেন বেছে নিন'] };
  const norm = normalizeBd(h.phone);
  if (enabled && !norm) return { error: ['Enter a mobile number patients can call', 'রোগীরা যে নম্বরে ফোন করবেন, সেই মোবাইল নম্বর দিন'] };
  const radiusKm = HOME_RADII.includes(h.radiusKm) ? h.radiusKm : 5;
  const upazilaIds = Array.isArray(h.upazilaIds) ? [...new Set(h.upazilaIds.filter(Number.isInteger))].slice(0, 10) : [];
  // কোন কোন বার কখন যান (নতুন অ্যাপ পাঠায়; আগের অ্যাপে শুধু লেখা নোট)
  const pt = parseTimes(h.times, ['Choose the days you visit homes', 'কোন কোন বার বাসায় যান বেছে নিন'], ['Set the start and end time for home visits', 'বাসায় যাওয়ার শুরু আর শেষের সময় দিন']);
  if (pt.error) return pt;
  if (enabled && Array.isArray(h.times) && !pt.times.length) return { error: ['Set the days and time you visit homes', 'কোন বার কখন বাসায় যান দিন'] };
  if (timeClash([{ times: pt.times }])) return { error: ['Two home-visit times overlap on the same day', 'বাসায় যাওয়ার দুটো সময় একই বারে মিলে যাচ্ছে'] };
  return {
    homeVisit: {
      enabled,
      radiusKm,
      upazilaIds,
      services,
      phone: norm ? localBd(norm) : '',
      schedule: str(h.schedule, 120),
      times: pt.times,
      noFee: h.noFee === true,
      fee: h.noFee === true ? '' : str(h.fee, 60),
    },
  };
}

/**
 * এই ডাক্তার/নার্স কি খোঁজা জায়গায় যান? p = providers ডক + users ডকের lat/lng/locationSource/placeName।
 * place: { lat, lng } (খুঁজছেন যিনি, GPS) আর/অথবা { district, upazilaId } (বেছে নেওয়া এলাকা)।
 * মিললে { km } (দূরত্ব জানা না গেলে km = null), না মিললে null।
 */
function homeMatch(p, place) {
  const h = p.homeVisit;
  if (!h || h.enabled !== true || p.homeVisitBlocked === true) return null;
  const gps = p.locationSource === 'gps' && typeof p.lat === 'number' && typeof p.lng === 'number';
  const ups = h.upazilaIds || [];
  if (typeof place.lat === 'number' && typeof place.lng === 'number') {
    const km = gps ? R.distanceKm(place.lat, place.lng, p.lat, p.lng) : null;
    if (km != null && km <= (h.radiusKm || 5)) return { km };
    if (place.upazilaId && ups.includes(place.upazilaId)) return { km };
    // লোকেশন ছাড়া শুধু উপজেলা বেছে রাখা মানুষ: রোগীর জেলার কোনো উপজেলা থাকলে দেখানো (দূরত্ব অজানা, পরে সাজানো)
    if (!gps && place.district && ups.length) {
      const { UPAZILA } = require('./hospital-match');
      if (ups.some((id) => UPAZILA.get(id)?.district === place.district)) return { km: null };
    }
    return null;
  }
  if (place.upazilaId) {
    if (ups.includes(place.upazilaId)) return { km: null };
    // শুধু লোকেশন দিয়ে রাখা মানুষ: একই জেলায় থাকলে দেখানো (ফোনে জেনে নেবেন আসতে পারবেন কি না)
    return gps && place.district && p.placeName === place.district ? { km: null } : null;
  }
  if (place.district) {
    const { UPAZILA } = require('./hospital-match');
    if (ups.some((id) => UPAZILA.get(id)?.district === place.district)) return { km: null };
    return gps && p.placeName === place.district ? { km: null } : null;
  }
  return { km: null };
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

/** "8801712345678" → "01712345678" (দেখানো ও ডায়াল করার রূপ) */
function localBd(normalized) {
  return normalized ? normalized.slice(2) : '';
}

/** আগে ভুল করে "001…" রূপে রাখা নম্বর ঠিক করে দেখানো (পুরোনো ডক) */
function fixPhone(p) {
  return typeof p === 'string' && /^001[3-9]\d{8}$/.test(p) ? p.slice(1) : p || '';
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
  // মালিক সাময়িক বন্ধ রাখলে (ঈদ, সংস্কার) — তালিকা, "ওষুধ খুঁজুন" সব জায়গায় বন্ধ
  if ((p.closedUntil || 0) > now) return false;
  const off = Array.isArray(p.closedDays) ? p.closedDays : [];
  // সাপ্তাহিক বন্ধের দিন (০ = রবিবার, বাংলাদেশের সময়ে)
  const weekday = (ms) => new Date(ms + 6 * 3600 * 1000).getUTCDay();
  if (p.open24) return !off.includes(weekday(now));
  const o = p.openAt;
  const c = p.closeAt;
  if (!TIME_RE.test(o || '') || !TIME_RE.test(c || '')) return !off.includes(weekday(now));
  const t = dhakaHHMM(now);
  if (o < c) return t >= o && t < c && !off.includes(weekday(now));
  // রাত পেরিয়ে খোলা: মাঝরাতের পরের অংশটা আগের দিনের পালা
  if (t >= o) return !off.includes(weekday(now));
  if (t < c) return !off.includes(weekday(now - 24 * 3600 * 1000));
  return false;
}

/** সাপ্তাহিক বন্ধের দিন: ০–৬ (রবি–শনি), সর্বোচ্চ ৬টা (সপ্তাহে অন্তত একদিন খোলা) */
function pickClosedDays(v) {
  if (!Array.isArray(v)) return [];
  const days = [...new Set(v.filter((d) => Number.isInteger(d) && d >= 0 && d <= 6))].sort();
  return days.length >= 7 ? days.slice(0, 6) : days;
}

/**
 * যোগাযোগের নম্বর: মোবাইল (01XXXXXXXXX), ল্যান্ডলাইন (02…, 031… — ৯-১১ অঙ্ক) বা হটলাইন (১৬XXX ধরনের ৫ অঙ্ক)।
 * হাসপাতালের ব্লাড ব্যাংক আর অ্যাম্বুলেন্স সার্ভিসের অনেকের মোবাইল নেই। ফেরত: ডায়াল করার রূপ, নইলে null
 */
function contactPhone(raw) {
  const mobile = normalizeBd(raw);
  if (mobile) return localBd(mobile);
  const d = String(raw || '').replace(/\D/g, '');
  if (/^1\d{4}$/.test(d)) return d;
  const local = d.startsWith('880') ? `0${d.slice(3)}` : d;
  return /^0[2-9]\d{7,9}$/.test(local) ? local : null;
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

/**
 * নামের ছোট বানান-ঠিক কি না: শেষ অনুমোদিত নামের সাথে সর্বোচ্চ ২টা অক্ষরের তফাত (বাংলায় ৪টা চিহ্ন) (বড়-ছোট হাত, ফাঁকা জায়গা বাদে)।
 * তাহলে অনুমোদিত তালিকা আবার যাচাইয়ে যায় না। base না থাকলে (কখনো অনুমোদন হয়নি) false।
 */
/** গাড়ি/লাইসেন্স নম্বর: বাংলা অঙ্ক → ইংরেজি, বড় হাতের, শুধু অক্ষর আর অঙ্ক (লেখার ধরন বদলে নিষেধাজ্ঞা এড়ানো যায় না) */
function normId(v) {
  return String(v || '')
    .replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c)))
    .toUpperCase()
    .replace(/[^0-9A-Z\u0980-\u09FF]/g, '');
}

function smallNameEdit(base, next) {
  if (!base || !next) return false;
  const norm = (v) => String(v).toLowerCase().replace(/\s+/g, ' ').trim();
  const a = norm(base);
  const b = norm(next);
  if (a === b) return true;
  // বাংলায় একটা বানান-ঠিকে কয়েকটা চিহ্ন বদলায় (অ্যা → এ = ৪টা), তাই সেখানে ৪ পর্যন্ত
  const max = /[\u0980-\u09FF]/.test(a + b) ? 4 : 2;
  if (Math.abs(a.length - b.length) > max) return false;
  // লেভেনশটাইন দূরত্ব (ছোট লেখা, তাই সরল হিসাব)
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length] <= max;
}

/** অনুমোদিত তালিকার বদল: আবার যাচাই লাগবে কি না, আর কোন নাম "শেষ অনুমোদিত" হিসেবে রাখা হবে */
function reviewNeeded(s, data, keys) {
  const status = s.get('status');
  const base = s.get('approvedName') || (status === 'approved' ? s.get('name') : null);
  const nameChanged = (s.get('name') || '') !== data.name && !smallNameEdit(base, data.name);
  const other = keys.some((k) => (k === 'phone' ? fixPhone(s.get(k)) : s.get(k) || '') !== data[k]);
  return { changed: nameChanged || other || status === 'rejected', approvedName: base };
}

/**
 * অনুমোদিত তালিকার "শেষ অনুমোদিত তথ্য" (lastApproved) ঠিক রাখা:
 * যাচাই ছাড়াই চলে যাওয়া বদলে (বানান-ঠিক, সময়) নতুন তথ্যই অনুমোদিত; যাচাইয়ে গেলে আগেরটা থেকে যায়
 * (বাতিল হলে সেটাই ফেরত আসে)। আগের ভার্সনে অনুমোদিত তালিকায় না থাকলে এখন রাখা হয়।
 */
function keepApproved(s, data, changed) {
  if (s.get('status') !== 'approved') return {};
  const { snapshot } = require('./listingreview');
  if (!changed) return { lastApproved: snapshot({ ...s.data(), ...data }) };
  return s.get('lastApproved') ? {} : { lastApproved: snapshot(s.data()) };
}

module.exports = {
  timeClash,
  chamberTimes,
  chamberTimesOn,
  MAX_CHAMBER_TIMES,
  keepApproved,
  pickClosedDays,
  contactPhone,
  normId,
  smallNameEdit,
  reviewNeeded,
  MED_NOTIFY,
  MED_FORWARD_MS,
  MED_RADIUS_KM,
  MED_OPEN_MS,
  dhakaHHMM,
  pharmacyOpenNow,
  pickPharmacies,
  medOrderExpired,
  normalizeBd,
  localBd,
  fixPhone,
  DOCTOR_SPECIALTIES,
  NURSE_SPECIALTIES,
  HOME_SERVICES,
  DOCTOR_HOME_SERVICES,
  homeServicesFor,
  homeMatch,
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
  visitExpired,
};
