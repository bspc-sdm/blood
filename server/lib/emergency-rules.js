// জরুরি রক্তের আহ্বানের খাঁটি নিয়ম (ডাটাবেস ছাড়া, তাই টেস্ট করা যায়)।
const R = require('./rules');

const RADII = [10, 25, 50];
const HOURS = [6, 12, 24, 48];
const MAX_NEED = 200;

/** প্রতিটা গ্রুপে এক ধাপে কতজনকে জানানো হবে: দরকারের ৩ গুণ, অন্তত ৫ */
function waveSize(need) {
  return Math.max(need * 3, 5);
}

/** অ্যাডমিনের দেওয়া গ্রুপ আর ব্যাগ: { "A+": 10, ... } → { "A+": { need, coming: 0, notified: 0, closed: false } } */
function parseGroups(raw) {
  const out = {};
  if (!raw || typeof raw !== 'object') return out;
  for (const g of R.BLOOD_GROUPS) {
    const n = raw[g];
    if (Number.isInteger(n) && n > 0) out[g] = { need: Math.min(n, MAX_NEED), coming: 0, notified: 0, closed: false };
  }
  return out;
}

/**
 * এই ধাপে কাকে কাকে জানানো হবে। users: users ডক (uid সহ), center: { lat, lng },
 * groups: emergency.groups, already: আগে জানানো uid-এর Set।
 * ফল: [{ uid, group, km, token, lang }] — প্রতিটা খোলা গ্রুপে কাছের মানুষ আগে।
 */
function pickWave(users, center, groups, radiusKm, already, now = Date.now()) {
  const out = [];
  for (const [g, x] of Object.entries(groups)) {
    if (x.closed || x.coming >= x.need) continue;
    const list = users
      .filter((u) => u.bloodGroup === g && u.fcmToken && u.notify !== false && !already.has(u.uid)
        && typeof u.lat === 'number' && typeof u.lng === 'number' && R.isEligibleDonor(u, now))
      .map((u) => ({ uid: u.uid, group: g, km: R.distanceKm(center.lat, center.lng, u.lat, u.lng), token: u.fcmToken, lang: u.lang }))
      .filter((u) => u.km <= radiusKm)
      .sort((a, b) => a.km - b.km)
      .slice(0, waveSize(x.need - x.coming));
    out.push(...list);
  }
  return out;
}

/** চালু আছে কি না (বন্ধ করা হয়নি আর সময় পার হয়নি) */
function isLive(e, now = Date.now()) {
  return e.status === 'active' && (e.endsAt || 0) > now;
}

module.exports = { RADII, HOURS, MAX_NEED, waveSize, parseGroups, pickWave, isLive };
