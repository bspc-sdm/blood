// অ্যাম্বুলেন্সের ভাড়া (২.৩০.০, পারভেজ, ১৯ অক্টোবর ২০২৬): মালিকের ভাড়ার তালিকা, রোগীর গন্তব্য থেকে আনুমানিক ভাড়া,
// আর বাজেটের ছাঁকনি। ডাটাবেস ছাড়া — তাই সরাসরি পরীক্ষা করা যায়।
//
// গাড়ির ভাড়ার তালিকা (ambulances/{id}.fareChart):
//   { local: ৮০০,                           — "নিজের উপজেলার ভেতরে"
//     rows: [{ kind: 'upazila', upazilaId, amount },  — একই জেলার অন্য উপজেলা
//            { kind: 'district', district, amount },  — জেলা (যেমন ঢাকা; নিজের জেলা দিলে "জেলার ভেতরে যেকোনো জায়গা")
//            { kind: 'hospital', name, amount }],     — হাসপাতালের নাম (লেখা)
//     oxygen, icu, night }                   — বাড়তি (এককালীন টাকা), না থাকলে null
// পুরো টাকা, যুক্তিসংগত সীমায়, সর্বোচ্চ ১৫টা গন্তব্য। আগের লেখা "ভাড়া" (fare) নোট হিসেবে থাকে।
//
// আনুমানিক ভাড়া = গন্তব্যের ভাড়া + (আইসিইউ ধরন বাছলে আইসিইউ যন্ত্র) + (অক্সিজেন চাইলে অক্সিজেন) + (রাত ১০টা–সকাল ৬টা হলে রাতের বাড়তি)।
// গন্তব্যের ভাড়া: উপজেলা = নিজের উপজেলা হলে local, নইলে সেই উপজেলার ঘর, নইলে সেই উপজেলার জেলার ঘর;
// জেলা = জেলার ঘর; হাসপাতাল = নাম মেলে এমন হাসপাতালের ঘর। না মিললে "জানা নেই" (null)।
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');

const MIN_FARE = 100;
const MAX_FARE = 100000;
const MIN_EXTRA = 50;
const MAX_EXTRA = 50000;
const MAX_ROWS = 15;
const EXTRAS = ['oxygen', 'icu', 'night'];
/** রোগীর বাজেটের চিপ (যেকোনো = null) — "৳X পর্যন্ত বাড়ান"-এর ধাপও এগুলো */
const LEVELS = [1000, 2000, 5000, 10000];
const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;

/** রাত (রাত ১০টা–সকাল ৬টা, ঢাকার সময়) */
function isNight(now = Date.now()) {
  const h = Math.floor(((now + 6 * HOUR) % DAY) / HOUR);
  return h >= 22 || h < 6;
}

/** বাংলা অঙ্কও পড়া: "১২০০" → 1200; পুরো টাকা না হলে NaN */
function taka(v) {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return Number.isInteger(v) ? v : NaN;
  if (typeof v !== 'string') return NaN;
  const s = v.replace(/[০-৯]/g, (c) => String('০১২৩৪৫৬৭৮৯'.indexOf(c))).replace(/[,\s৳]/g, '');
  if (!s) return null;
  return /^\d+$/.test(s) ? Number(s) : NaN;
}

// হাসপাতালের নাম মেলানো: ছোট হাতের, চিহ্ন আর সাধারণ শব্দ বাদ, ফাঁক ছাড়া
const STOP = new Set(['hospital', 'clinic', 'ltd', 'limited', 'the', 'হাসপাতাল', 'হসপিটাল', 'ক্লিনিক', 'লিমিটেড', 'লি']);
function hospKey(name) {
  return String(name || '').toLowerCase()
    .replace(/[^a-z0-9ঀ-৿]+/g, ' ')
    .split(' ').filter((w) => w && !STOP.has(w)).join('');
}
function hospMatch(a, b) {
  const x = hospKey(a);
  const y = hospKey(b);
  if (!x || !y) return false;
  if (x === y) return true;
  const [s, l] = x.length <= y.length ? [x, y] : [y, x];
  return s.length >= 4 && l.includes(s);
}

/** গন্তব্যের চাবি (একই গন্তব্য দুবার নয়) */
function destKey(d) {
  if (d.kind === 'upazila') return `u:${d.upazilaId}`;
  if (d.kind === 'district') return `d:${d.district}`;
  return `h:${hospKey(d.name)}`;
}

/**
 * রোগীর বা মালিকের দেওয়া গন্তব্য যাচাই: { kind: 'upazila', upazilaId } | { kind: 'district', district } | { kind: 'hospital', name }।
 * ভুল হলে null।
 */
function destOf(v) {
  if (!v || typeof v !== 'object') return null;
  if (v.kind === 'upazila') return Number.isInteger(v.upazilaId) && UPAZILA.has(v.upazilaId) ? { kind: 'upazila', upazilaId: v.upazilaId } : null;
  if (v.kind === 'district') return DISTRICTS.some((d) => d.bn === v.district) ? { kind: 'district', district: v.district } : null;
  if (v.kind === 'hospital') {
    const name = typeof v.name === 'string' ? v.name.replace(/\s+/g, ' ').trim().slice(0, 80) : '';
    return name.length >= 3 && hospKey(name) ? { kind: 'hospital', name } : null;
  }
  return null;
}

/** গন্তব্যের নাম (চালকের কার্ড, পুশ নয়) */
function destLabel(d, lang = 'bn') {
  if (!d) return '';
  const en = lang === 'en';
  if (d.kind === 'upazila') {
    const u = UPAZILA.get(d.upazilaId);
    if (!u) return '';
    const dist = en ? (DISTRICTS.find((x) => x.bn === u.district) || {}).en || u.district : u.district;
    return en ? `${u.en}, ${dist}` : `${u.bn}, ${dist}`;
  }
  if (d.kind === 'district') return en ? (DISTRICTS.find((x) => x.bn === d.district) || {}).en || d.district : d.district;
  return d.name || '';
}

/**
 * মালিকের ভাড়ার তালিকা যাচাই। veh: { district, upazilaId } (গাড়ির)।
 * ফেরত { chart } (খালি হলে chart = null) অথবা { error: [en, bn] }।
 */
function normalizeChart(input, veh) {
  if (input == null) return { chart: null };
  if (typeof input !== 'object' || Array.isArray(input)) return { error: ['Fare list is not valid', 'ভাড়ার তালিকা ঠিক নেই'] };
  const bad = (en, bn) => ({ error: [en, bn] });
  const fareOk = (n) => n >= MIN_FARE && n <= MAX_FARE;
  const local = taka(input.local);
  if (Number.isNaN(local)) return bad('Write fares in whole taka', 'ভাড়া পুরো টাকায় লিখুন');
  if (local != null && !fareOk(local)) return bad('Fares must be between ৳100 and ৳1,00,000', 'ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন');
  const rowsIn = Array.isArray(input.rows) ? input.rows : [];
  if (rowsIn.length > MAX_ROWS) return bad('Up to 15 destinations', 'সর্বোচ্চ ১৫টা গন্তব্য');
  const rows = [];
  const seen = new Set();
  for (const r of rowsIn) {
    const d = destOf(r);
    if (!d) return bad('Choose each destination', 'প্রতিটা গন্তব্য বেছে নিন');
    if (d.kind === 'upazila') {
      const u = UPAZILA.get(d.upazilaId);
      if (u.district !== veh.district) return bad('Choose an upazila of your district', 'নিজের জেলার উপজেলা বেছে নিন');
      if (d.upazilaId === veh.upazilaId) return bad('Write your own upazila\'s fare in "Within own upazila"', 'নিজের উপজেলার ভাড়া "নিজের উপজেলার ভেতরে" ঘরে লিখুন');
    }
    const amount = taka(r.amount);
    if (amount == null) return bad('Write the fare for each destination', 'প্রতিটা গন্তব্যের ভাড়া লিখুন');
    if (Number.isNaN(amount)) return bad('Write fares in whole taka', 'ভাড়া পুরো টাকায় লিখুন');
    if (!fareOk(amount)) return bad('Fares must be between ৳100 and ৳1,00,000', 'ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন');
    const k = destKey(d);
    if (seen.has(k)) return bad('A destination is listed twice', 'একই গন্তব্য দুবার আছে');
    seen.add(k);
    rows.push({ ...d, amount });
  }
  const chart = { local, rows };
  for (const e of EXTRAS) {
    const n = taka(input[e]);
    if (Number.isNaN(n)) return bad('Write extras in whole taka', 'বাড়তি ভাড়া পুরো টাকায় লিখুন');
    if (n != null && (n < MIN_EXTRA || n > MAX_EXTRA)) return bad('Extras must be between ৳50 and ৳50,000', 'বাড়তি ভাড়া ৳৫০ থেকে ৳৫০,০০০-এর মধ্যে লিখুন');
    chart[e] = n;
  }
  if (local == null && !rows.length) {
    // শুধু বাড়তি দিয়ে কোনো গন্তব্যের ভাড়া হয় না
    if (EXTRAS.some((e) => chart[e] != null)) return bad('Write at least one fare (local or a destination)', 'অন্তত একটা ভাড়া লিখুন (নিজের উপজেলা বা কোনো গন্তব্য)');
    return { chart: null };
  }
  return { chart };
}

/** গন্তব্যের ভাড়া (বাড়তি ছাড়া), না জানা থাকলে null */
function baseFare(chart, veh, dest) {
  if (!chart || !dest) return null;
  const rows = chart.rows || [];
  const districtRow = (district) => rows.find((r) => r.kind === 'district' && r.district === district);
  if (dest.kind === 'upazila') {
    if (veh.upazilaId && dest.upazilaId === veh.upazilaId && chart.local != null) return chart.local;
    const r = rows.find((x) => x.kind === 'upazila' && x.upazilaId === dest.upazilaId);
    if (r) return r.amount;
    const u = UPAZILA.get(dest.upazilaId);
    const d = u ? districtRow(u.district) : null;
    return d ? d.amount : null;
  }
  if (dest.kind === 'district') {
    const d = districtRow(dest.district);
    return d ? d.amount : null;
  }
  const h = rows.find((x) => x.kind === 'hospital' && hospMatch(x.name, dest.name));
  return h ? h.amount : null;
}

/**
 * আনুমানিক ভাড়া: গন্তব্য + বাড়তি (আইসিইউ ধরন, অক্সিজেন, রাত)। opts: { type, oxygen, night }। না জানা = null।
 * গাড়ির তালিকায় বাড়তিটা না থাকলে ধরে নেওয়া হয় ভাড়ার মধ্যেই আছে (০)।
 */
function estimate(chart, veh, dest, opts = {}) {
  const base = baseFare(chart, veh, dest);
  if (base == null) return null;
  return base
    + (opts.type === 'icu' ? chart.icu || 0 : 0)
    + (opts.oxygen ? chart.oxygen || 0 : 0)
    + (opts.night ? chart.night || 0 : 0);
}

/** বাজেটে চলে কি না: maxFare null = যেকোনো; ভাড়া না জানা গাড়ি শুধু includeUnknown হলে */
function fits(est, maxFare, includeUnknown) {
  if (est == null) return !!includeUnknown;
  return maxFare == null || est <= maxFare;
}

/** রোগীর বাজেট যাচাই: null (যেকোনো) বা MIN_FARE–MAX_FARE-এর পুরো টাকা; ভুল হলে NaN */
function budgetOf(v) {
  if (v == null || v === 0 || v === '') return null;
  const n = taka(v);
  if (n == null) return null;
  if (Number.isNaN(n) || n < MIN_FARE || n > MAX_FARE) return NaN;
  return n;
}

/**
 * ০টা মিললে "৳X পর্যন্ত বাড়ান": বাজেটের পরের যে চিপে গাড়ি আছে; চিপে না হলে সবচেয়ে কম ভাড়া (হাজারে উপরে)।
 * ests: আনুমানিক ভাড়া (জানা গুলো)। কিছুই না থাকলে null।
 */
function raiseTo(ests, maxFare) {
  const known = ests.filter((e) => e != null && (maxFare == null || e > maxFare));
  if (!known.length || maxFare == null) return null;
  const lo = Math.min(...known);
  const level = LEVELS.find((l) => l > maxFare && l >= lo);
  return level || Math.ceil(lo / 1000) * 1000;
}

module.exports = {
  MIN_FARE, MAX_FARE, MAX_ROWS, LEVELS, EXTRAS,
  isNight, taka, hospMatch, destOf, destLabel, normalizeChart, baseFare, estimate, fits, budgetOf, raiseTo,
};
