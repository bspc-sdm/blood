// অ্যাপের Rules.kt-এর নিয়মগুলো, সার্ভারের জন্য। দুই জায়গায় একই নিয়ম থাকতে হবে।
const DISTRICTS = require('./districts');

const DAY_MS = 24 * 60 * 60 * 1000;
const ELIGIBILITY_DAYS = 120;
const MAX_PUSH_PER_DAY = 3; // একজন ডোনার দিনে কয়টা রিকোয়েস্ট নোটিফিকেশন পাবে
const MAX_RESPONSES_PER_DAY = 5; // একজন দিনে কয়টা রিকোয়েস্টে "রক্ত দেব" চাপতে পারবে
const RADII_KM = [5, 10, 25];
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

function isEligible(lastDonationMillis, now = Date.now()) {
  if (lastDonationMillis == null) return true;
  return now >= lastDonationMillis + ELIGIBILITY_DAYS * DAY_MS;
}

function isActive(request, now = Date.now()) {
  return request.status === 'open' && (request.neededByMillis || 0) + DAY_MS > now;
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

/**
 * রিকোয়েস্টের জন্য কোন ডোনারদের জানানো হবে: মানানসই গ্রুপ, যোগ্য, নোটিফিকেশন চালু,
 * ধাপে ধাপে দূরত্ব (৫ → ১০ → ২৫ কিমি) যতক্ষণ না যথেষ্ট ডোনার পাওয়া যায়।
 * users: [{ uid, bloodGroup, lastDonationMillis, lat, lng, notify, fcmToken }]
 */
function pickDonors(request, users, now = Date.now()) {
  const candidates = users
    .filter(
      (u) =>
        u.uid !== request.requesterUid &&
        u.fcmToken &&
        u.notify !== false &&
        typeof u.lat === 'number' &&
        typeof u.lng === 'number' &&
        canDonate(u.bloodGroup, request.patientGroup) &&
        isEligible(u.lastDonationMillis, now),
    )
    .map((u) => ({ ...u, km: distanceKm(request.lat, request.lng, u.lat, u.lng) }));

  let chosen = [];
  for (const radius of RADII_KM) {
    chosen = candidates.filter((u) => u.km <= radius);
    if (chosen.length >= ENOUGH_DONORS) break;
  }
  return chosen.sort((a, b) => a.km - b.km);
}

function formatKm(km) {
  return km < 1 ? '১ কিমির কম দূরে' : `প্রায় ${Math.round(km).toLocaleString('bn-BD')} কিমি দূরে`;
}

module.exports = {
  DAY_MS,
  ELIGIBILITY_DAYS,
  MAX_PUSH_PER_DAY,
  MAX_RESPONSES_PER_DAY,
  canDonate,
  isEligible,
  isActive,
  distanceKm,
  nearDistricts,
  todayDhakaUtcMidnight,
  dhakaDayKey,
  pickDonors,
  formatKm,
};
