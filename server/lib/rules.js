// অ্যাপের Rules.kt-এর নিয়মগুলো, সার্ভারের জন্য। দুই জায়গায় একই নিয়ম থাকতে হবে।
const DISTRICTS = require('./districts');

const DAY_MS = 24 * 60 * 60 * 1000;
const ELIGIBILITY_DAYS = 120;
const MAX_PUSH_PER_DAY = 3; // ডোনার নিজে না বদলালে দিনে কয়টা রিকোয়েস্ট নোটিফিকেশন পাবে
const MAX_PUSH_PER_DAY_LIMIT = 10; // সেটিংসে সর্বোচ্চ যত বেছে নেওয়া যায়
const MAX_RESPONSES_PER_DAY = 5; // একজন দিনে কয়টা রিকোয়েস্টে "রক্ত দেব" চাপতে পারবে
const MAX_REQUESTS_PER_DAY = 3; // একজন দিনে কয়টা রিকোয়েস্টের নোটিফিকেশন পাঠাতে পারবে
const MAX_OPEN_REQUESTS = 2;
const RADII_KM = [5, 10, 25, 50];
const RADIUS_CHOICES = [5, 10, 25, 50]; // ডোনার কত দূর পর্যন্ত যেতে রাজি (সেটিংস)
const DEFAULT_RADIUS_KM = 10;
const ENOUGH_DONORS = 10; // এতজন পেলে আর দূরে খোঁজা হবে না
const BLOOD_GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];

// রোগীর গ্রুপ → কারা দিতে পারবে (লোহিত কণিকা / whole blood)
const DONORS_FOR = {
  'O-': ['O-'],
  'O+': ['O+', 'O-'],
  'A-': ['A-', 'O-'],
  'A+': ['A+', 'A-', 'O+', 'O-'],
  'B-': ['B-', 'O-'],
  'B+': ['B+', 'B-', 'O+', 'O-'],
  'AB-': ['AB-', 'A-', 'B-', 'O-'],
  'AB+': BLOOD_GROUPS,
};

function canDonate(donorGroup, patientGroup) {
  return (DONORS_FOR[patientGroup] || []).includes(donorGroup);
}

/** ডোনার নিজে দিনে কয়টা নোটিফিকেশন চেয়েছেন (সেটিংস); ঠিক না থাকলে ৩টা */
function pushLimit(user) {
  const n = user.maxPushPerDay;
  if (!Number.isInteger(n)) return MAX_PUSH_PER_DAY;
  return Math.min(Math.max(n, 1), MAX_PUSH_PER_DAY_LIMIT);
}

/**
 * ডোনার এই রোগীর গ্রুপের নোটিফিকেশন চান কি না (সেটিংস)। কিছু বাছা না থাকলে, বা বাছা গ্রুপগুলোর
 * কোনোটাকেই আর দিতে না পারলে (রক্তের গ্রুপ বদলানোর পর), যাদের দিতে পারেন সবার নোটিফিকেশন যায় — অ্যাপের মতোই।
 */
function wantsGroup(user, patientGroup) {
  const list = Array.isArray(user.notifyGroups) ? user.notifyGroups : null;
  if (!list) return true;
  const valid = list.filter((g) => canDonate(user.bloodGroup, g));
  return valid.length === 0 || valid.includes(patientGroup);
}

function isEligible(lastDonationMillis, now = Date.now()) {
  if (lastDonationMillis == null) return true;
  return now >= lastDonationMillis + ELIGIBILITY_DAYS * DAY_MS;
}

/**
 * শেষ রক্তদানের তারিখ বদলালে কোন সতর্কতা: 'backward' (পেছানো বা "কখনো দিইনি"),
 * 'short_gap' (সামনে নিলেও দুই তারিখের ফাঁক ১২০ দিনের কম), নইলে null।
 */
function donationRisk(before, after) {
  if (before == null) return null;
  if (after == null || after < before) return 'backward';
  if (after > before && after - before < ELIGIBILITY_DAYS * DAY_MS) return 'short_gap';
  return null;
}

const MIN_AGE = 18;
const MAX_AGE = 60;
const MIN_WEIGHT_KG = 50;

/** জন্ম তারিখ থেকে পূর্ণ বয়স (বছর) */
function ageYears(birthDateMillis, now = Date.now()) {
  if (typeof birthDateMillis !== 'number') return null;
  const b = new Date(birthDateMillis);
  const n = new Date(now + 6 * 60 * 60 * 1000); // বাংলাদেশের তারিখ
  let age = n.getUTCFullYear() - b.getUTCFullYear();
  const beforeBirthday =
    n.getUTCMonth() < b.getUTCMonth() || (n.getUTCMonth() === b.getUTCMonth() && n.getUTCDate() < b.getUTCDate());
  if (beforeBirthday) age--;
  return age;
}

/** বয়স, ওজন আর শেষ রক্তদানের সময় মিলিয়ে এখন রক্ত দিতে পারবেন কি না */
function isEligibleDonor(user, now = Date.now()) {
  const age = ageYears(user.birthDateMillis, now);
  return (
    age != null &&
    age >= MIN_AGE &&
    age <= MAX_AGE &&
    typeof user.weightKg === 'number' &&
    user.weightKg >= MIN_WEIGHT_KG &&
    isEligible(user.lastDonationMillis, now)
  );
}

/** নির্দিষ্ট সময় দেওয়া থাকলে তার ৬ ঘণ্টা পর পর্যন্ত, নইলে দরকারের দিনের শেষ পর্যন্ত */
const AFTER_NEEDED_MS = 6 * 60 * 60 * 1000;
function activeUntil(request) {
  return Number.isInteger(request.neededAtMillis)
    ? request.neededAtMillis + AFTER_NEEDED_MS
    : (request.neededByMillis || 0) + DAY_MS;
}

function isActive(request, now = Date.now()) {
  return request.status === 'open' && activeUntil(request) > now;
}

/** আর কয় ব্যাগ লাগবে: নিশ্চিত/রক্ত দেওয়া ডোনার বা পাওয়া ব্যাগ — যেটা বেশি — বাদ দিয়ে */
function bagsLeft(request) {
  const done = Math.max(request.confirmedCount || 0, request.bagsReceived || 0);
  return Math.max((request.bags || 1) - done, 0);
}

function distanceKm(aLat, aLng, bLat, bLng) {
  const R = 6371;
  const rad = (d) => (d * Math.PI) / 180;
  const dLat = rad(bLat - aLat);
  const dLng = rad(bLng - aLng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** কাছের জেলাগুলোর বাংলা নাম (কেন্দ্র ৭০ কিমির মধ্যে, সর্বোচ্চ ৩০টা) — অ্যাপের Districts.near-এর মতো */
function nearDistricts(lat, lng) {
  const list = DISTRICTS.map((d) => ({ d, km: distanceKm(lat, lng, d.lat, d.lng) }))
    .sort((a, b) => a.km - b.km);
  const near = list.filter((x) => x.km <= 70).slice(0, 30).map((x) => x.d.bn);
  return near.length ? near : [list[0].d.bn];
}

/** বাংলাদেশের আজকের তারিখ, UTC মধ্যরাত হিসেবে (অ্যাপের DatePicker-এর সাথে মেলানোর জন্য) */
function todayDhakaUtcMidnight(now = Date.now()) {
  const d = new Date(now + 6 * 60 * 60 * 1000);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

/** দৈনিক সীমার জন্য বাংলাদেশের তারিখ, যেমন "20261015" */
function dhakaDayKey(now = Date.now()) {
  return new Date(now + 6 * 60 * 60 * 1000).toISOString().slice(0, 10).replace(/-/g, '');
}

/** ডোনার কত কিমির মধ্যে রক্ত দিতে রাজি (সেটিংস); ঠিক না থাকলে ১০ */
function donorRadius(user) {
  return RADIUS_CHOICES.includes(user.maxKm) ? user.maxKm : DEFAULT_RADIUS_KM;
}

/**
 * যাতায়াত খরচ মেলে কি না: ডোনার "এত কিমির বেশি হলে খরচ লাগবে" বলে রাখলে, আর রিকোয়েস্টে খরচ দেওয়া না হলে,
 * সেই দূরত্বের বেশি হলে তাঁকে জানানো হয় না।
 */
function fareOk(user, request, km) {
  if (request.travelFare === true) return true;
  const after = user.fareAfterKm;
  if (typeof after !== 'number') return true;
  return km <= after;
}

/**
 * রিকোয়েস্টের জন্য কোন ডোনারদের জানানো হবে: মানানসই গ্রুপ, যোগ্য, নোটিফিকেশন চালু,
 * ধাপে ধাপে দূরত্ব (৫ → ১০ → ২৫ কিমি) যতক্ষণ না যথেষ্ট ডোনার পাওয়া যায়।
 * users: [{ uid, bloodGroup, lastDonationMillis, lat, lng, notify, fcmToken, fcmTokens }]
 */
function pickDonors(request, users, now = Date.now()) {
  const candidates = users
    .filter(
      (u) =>
        u.uid !== request.requesterUid &&
        (u.fcmToken || (Array.isArray(u.fcmTokens) && u.fcmTokens.length)) &&
        u.notify !== false &&
        typeof u.lat === 'number' &&
        typeof u.lng === 'number' &&
        canDonate(u.bloodGroup, request.patientGroup) &&
        wantsGroup(u, request.patientGroup) &&
        isEligibleDonor(u, now),
    )
    .map((u) => ({ ...u, km: distanceKm(request.lat, request.lng, u.lat, u.lng) }))
    // ডোনারের নিজের দূরত্বের সীমা আর যাতায়াত খরচের পছন্দ
    .filter((u) => u.km <= donorRadius(u) && fareOk(u, request, u.km));

  let chosen = [];
  for (const radius of RADII_KM) {
    chosen = candidates.filter((u) => u.km <= radius);
    if (chosen.length >= ENOUGH_DONORS) break;
  }
  return chosen.sort((a, b) => a.km - b.km);
}

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
function isValidId(id) {
  return typeof id === 'string' && ID_RE.test(id);
}

/** রিকোয়েস্টের তথ্য ঠিক আছে কি না (ভুয়া বা বিকৃত তথ্য দিয়ে পুশ পাঠানো আটকাতে)। সমস্যা থাকলে বার্তা, নইলে null */
function validateRequest(r, now = Date.now()) {
  const districtNames = DISTRICTS.map((d) => d.bn);
  if (!BLOOD_GROUPS.includes(r.patientGroup)) return 'রক্তের গ্রুপ ঠিক নেই';
  if (!Number.isInteger(r.bags) || r.bags < 1 || r.bags > 10) return 'ব্যাগের সংখ্যা ঠিক নেই';
  if (typeof r.hospital !== 'string' || !r.hospital.trim() || r.hospital.length > 120) return 'হাসপাতালের নাম ঠিক নেই';
  if (!districtNames.includes(r.placeName)) return 'এলাকা ঠিক নেই';
  if (typeof r.lat !== 'number' || r.lat < 20.5 || r.lat > 26.7) return 'লোকেশন ঠিক নেই';
  if (typeof r.lng !== 'number' || r.lng < 88 || r.lng > 92.7) return 'লোকেশন ঠিক নেই';
  if (typeof r.neededByMillis !== 'number' || r.neededByMillis > now + 31 * DAY_MS) return 'তারিখ ঠিক নেই';
  if (r.neededAtMillis != null && !neededAtMatchesDate(r.neededAtMillis, r.neededByMillis)) return 'সময় ঠিক নেই';
  return null;
}

const DHAKA_OFFSET_MS = 6 * 60 * 60 * 1000;

/**
 * রক্ত লাগার নির্দিষ্ট মুহূর্ত (neededAtMillis) বাছা তারিখের (neededByMillis, UTC মধ্যরাত) ভেতরে কি না —
 * বাংলাদেশের সময়ে ওই দিনের রাত ১২টা থেকে পরের রাত ১২টার আগ পর্যন্ত।
 */
function neededAtMatchesDate(neededAtMillis, neededByMillis) {
  if (!Number.isInteger(neededAtMillis)) return false;
  const start = neededByMillis - DHAKA_OFFSET_MS;
  return neededAtMillis >= start && neededAtMillis < start + DAY_MS;
}

/** বাংলাদেশের সময়ে "বিকাল ৪:৩০" ধরনের লেখা */
function formatTimeBn(millis) {
  const d = new Date(millis + DHAKA_OFFSET_MS);
  const h = d.getUTCHours();
  const m = d.getUTCMinutes();
  const part = h < 5 ? 'রাত' : h < 12 ? 'সকাল' : h < 15 ? 'দুপুর' : h < 18 ? 'বিকাল' : h < 20 ? 'সন্ধ্যা' : 'রাত';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  const bn = (t) => String(t).replace(/\d/g, (c) => '০১২৩৪৫৬৭৮৯'[c]);
  return `${part} ${bn(h12)}:${bn(String(m).padStart(2, '0'))}`;
}

/** ব্যবহারকারীর ভাষায় লেখা (প্রোফাইলের lang: "en" / "bn"; না থাকলে বাংলা) */
function L(lang, en, bn) {
  return lang === 'en' ? en : bn;
}

function bnDigits(t) {
  return String(t).replace(/\d/g, (c) => '০১২৩৪৫৬৭৮৯'[c]);
}

/** নোটিফিকেশনের লেখায় সংখ্যা বাংলা অঙ্কে — শুধু আলাদা সংখ্যা (তারিখ, সিরিয়াল, দাম); "HbA1c", "B12"-এর মতো নামের ভেতরের অঙ্ক নয় */
function bnNumbers(t) {
  return String(t).replace(/(^|[^A-Za-z0-9])(\d+)(?![A-Za-z0-9])/g, (m, pre, d) => pre + bnDigits(d));
}

function formatKm(km, lang) {
  if (lang === 'en') return km < 1 ? '< 1 km away' : `about ${Math.round(km)} km away`;
  return km < 1 ? '১ কিমির কম দূরে' : `প্রায় ${bnDigits(Math.round(km))} কিমি দূরে`;
}

/** জেলার নাম (ডাটাবেসে বাংলায় থাকে) ভাষা অনুযায়ী */
function districtLabel(bn, lang) {
  if (lang !== 'en') return bn;
  const d = DISTRICTS.find((x) => x.bn === bn);
  return d ? d.en : bn;
}

/** "4:05 PM" */
function formatTimeEn(millis) {
  const d = new Date(millis + DHAKA_OFFSET_MS);
  const h = d.getUTCHours();
  const m = String(d.getUTCMinutes()).padStart(2, '0');
  return `${h % 12 === 0 ? 12 : h % 12}:${m} ${h < 12 ? 'AM' : 'PM'}`;
}

module.exports = {
  RADIUS_CHOICES,
  DEFAULT_RADIUS_KM,
  RADII_KM,
  ENOUGH_DONORS,
  DONORS_FOR,
  BLOOD_GROUPS,
  DAY_MS,
  ELIGIBILITY_DAYS,
  MAX_REQUESTS_PER_DAY,
  MAX_OPEN_REQUESTS,
  isValidId,
  validateRequest,
  MAX_PUSH_PER_DAY,
  MAX_PUSH_PER_DAY_LIMIT,
  pushLimit,
  wantsGroup,
  MAX_RESPONSES_PER_DAY,
  canDonate,
  isEligible,
  isEligibleDonor,
  ageYears,
  isActive,
  activeUntil,
  bagsLeft,
  distanceKm,
  nearDistricts,
  todayDhakaUtcMidnight,
  donationRisk,
  dhakaDayKey,
  pickDonors,
  donorRadius,
  fareOk,
  formatKm,
  neededAtMatchesDate,
  formatTimeBn,
  formatTimeEn,
  districtLabel,
  L,
  bnDigits,
  bnNumbers,
};
