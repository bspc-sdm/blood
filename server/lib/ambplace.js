// অ্যাম্বুলেন্স ডাকার জায়গা (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬): "কোথা থেকে নেবে" আর "কোথায় যাবেন"।
//
// কোথা থেকে নেবে — তিনভাবে, কোনো টাকা লাগে না, অ্যাপের ভেতরে ম্যাপ নেই:
//   ১) এখনকার জায়গা: ফোন/ব্রাউজারের এককালীন GPS (আগের মতো)। নেওয়ার পর "Google ম্যাপে দেখুন" — শুধু লিংক/intent,
//      ফোনের Google Maps অ্যাপ (বা ব্রাউজার) সেই বিন্দুতে খোলে; কোনো API key নয়, কিছু ফেরত আসে না।
//   ২) Google ম্যাপ থেকে জায়গা: Google Maps-এ জায়গা চেপে ধরে "শেয়ার" → Lifolo (অ্যাপের share target), অথবা লিংক
//      লেখার ঘরে পেস্ট। লম্বা লিংক থেকে অক্ষাংশ/দ্রাঘিমাংশ এখানেই পড়া যায় (parseMapsText); ছোট লিংক
//      (maps.app.goo.gl/…, goo.gl/maps/…) সার্ভার redirect ধরে খোলে (maps_link_resolve) — শুধু Google-এর ঠিকানায়,
//      সময়সীমা আর ব্যবহারকারী-প্রতি সীমাসহ। API key লাগে না।
//   ৩) লোকেশন লিখুন: এলাকা / রাস্তা / চেনা জায়গা (আগের মতো)।
//   জায়গা (১ বা ২) থাকলে উপজেলা আপনা থেকে: জেলার ভেতরে সবচেয়ে কাছের উপজেলা-কেন্দ্র (upazila-centers.json, OCHA ADM3)।
//   কেন্দ্র না থাকলে (সিটি কর্পোরেশনের থানা) বা অনেক দূরে হলে বেছে নেওয়া উপজেলাই থাকে।
//   ভবিষ্যতে অ্যাপের ভেতরে Google Maps SDK-র ম্যাপ বসানো যায় (Android-এ ম্যাপ দেখানো বিনামূল্যে), কিন্তু তার জন্য
//   Google Cloud-এ বিলিং অ্যাকাউন্ট (কার্ড) লাগে — এখন নেই, তাই লিংক/শেয়ারের পথ।
//   গোপনীয়তা আগের মতো: ঠিক জায়গা শুধু রাজি হওয়া চালক পান, অনুরোধ বন্ধের ২৪ ঘণ্টা পর মোছে (ambreq.js)।
//
// কোথায় যাবেন — হাসপাতাল/ক্লিনিকের তালিকা (DGHS Facility Registry, hospital-dir.json; ডায়াগনস্টিক বাদ) +
//   তালিকায় না থাকা কয়েকটা বড় হাসপাতাল (EXTRA) + "অন্য জায়গা লিখুন"। রোগীর জেলারটা আগে (GPS থাকলে কাছেরটা আগে),
//   সাথে সারা দেশের বড় হাসপাতাল (TOP)। খোঁজা বাংলা/ইংরেজিতে, "য়" একরকম করে।
const { HttpError } = require('./firebase');
const C = require('./community');
const geo = require('./geo');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const CENTERS = require('./upazila-centers.json');
const DIR = require('./hospital-dir.json');

// ------------------------------------------------------------
// উপজেলা আপনা থেকে
// ------------------------------------------------------------

/** এর বেশি দূরের কেন্দ্র হলে উপজেলা আপনা থেকে নয় (ভুল জেলা/ভুল বিন্দু) */
const MAX_CENTER_KM = 30;

/** জেলায় এমন উপজেলা/থানা আছে যার কেন্দ্র নেই (সিটি কর্পোরেশনের থানা) — তখন শহরের কেন্দ্র কাছে হলে আপনা থেকে নয় */
const NO_CENTER = new Set([...UPAZILA.values()].filter((u) => !CENTERS.c[u.id]).map((u) => u.district));

/**
 * বিন্দু থেকে জেলার ভেতরের উপজেলা: সবচেয়ে কাছের উপজেলা-কেন্দ্র। না পারলে null (তখন বেছে নেওয়া উপজেলা থাকে)।
 */
function nearestUpazila(district, lat, lng) {
  if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  let best = null;
  let bestKm = Infinity;
  for (const u of UPAZILA.values()) {
    if (u.district !== district) continue;
    const c = CENTERS.c[u.id];
    if (!c) continue;
    const km = geo.distanceKm(lat, lng, c[0], c[1]);
    if (km < bestKm) { bestKm = km; best = u.id; }
  }
  if (best == null || bestKm > MAX_CENTER_KM) return null;
  if (NO_CENTER.has(district)) {
    for (const c of CENTERS.cc[district] || []) if (geo.distanceKm(lat, lng, c[0], c[1]) < bestKm) return null;
  }
  return best;
}

// ------------------------------------------------------------
// Google Maps-এর লিংক/লেখা থেকে বিন্দু
// ------------------------------------------------------------

/** বাংলাদেশের ভেতরে কি না (ambreq.js-এর exactPoint-এর সীমা) */
const inBd = (lat, lng) => lat >= 20.3 && lat <= 26.8 && lng >= 87.9 && lng <= 92.8;
const NUM = '(-?\\d{1,3}(?:\\.\\d+)?)';
// আগে আসল জায়গা (!3d…!4d…), তারপর q/query/ll/center/destination=lat,lng, তারপর /search|place/lat,lng, শেষে @lat,lng (ম্যাপের মাঝখান)
const PATTERNS = [
  new RegExp(`!3d${NUM}!4d${NUM}`),
  new RegExp(`[?&](?:q|query|ll|center|destination|daddr|sll)=(?:loc:)?${NUM}\\s*,\\s*\\+?${NUM}`),
  new RegExp(`/(?:search|place|dir)/${NUM}\\s*,\\s*\\+?${NUM}`),
  new RegExp(`@${NUM},${NUM}`),
  new RegExp(`\\bgeo:${NUM},${NUM}`),
];
// লিংক ছাড়া শুধু সংখ্যা ("23.8103, 90.4125" — Google Maps-এ জায়গা চেপে ধরলে এভাবে দেখায়)
const PLAIN = new RegExp(`(?:^|[^\\d.])${NUM}\\s*,\\s*${NUM}(?![\\d.])`);
const SHORT_RE = /https?:\/\/(?:maps\.app\.goo\.gl|goo\.gl\/maps)\/[A-Za-z0-9_\-/?=&.%]+/i;

function decode(s) {
  let out = String(s || '');
  for (let i = 0; i < 2; i++) {
    try { out = decodeURIComponent(out.replace(/\+/g, ' ')); } catch { break; }
  }
  return out;
}

function pointFrom(text, withPlain) {
  const t = decode(text);
  for (const re of [...PATTERNS, ...(withPlain ? [PLAIN] : [])]) {
    const m = t.match(re);
    if (!m) continue;
    const lat = Number(m[1]);
    const lng = Number(m[2]);
    if (Number.isFinite(lat) && Number.isFinite(lng) && inBd(lat, lng)) {
      return { lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5 };
    }
  }
  return null;
}

/**
 * শেয়ার করা বা পেস্ট করা লেখা: { lat, lng } (লম্বা লিংক বা শুধু সংখ্যা), { short: url } (ছোট লিংক — সার্ভারে খুলতে হবে),
 * নইলে null। বাংলাদেশের বাইরের বিন্দু নয়।
 */
function parseMapsText(text) {
  const s = String(text || '').slice(0, 2000);
  if (!s.trim()) return null;
  const p = pointFrom(s, true);
  if (p) return p;
  const short = s.match(SHORT_RE);
  return short ? { short: short[0] } : null;
}

// ছোট লিংক খোলা: শুধু এই ঠিকানাগুলোয় যাওয়া (redirect-এর প্রতিটা ধাপে যাচাই)
const SHORT_HOSTS = new Set(['maps.app.goo.gl', 'goo.gl']);
const isFollowHost = (h) => SHORT_HOSTS.has(h) || /^(?:www\.|maps\.|consent\.)?google\.com(?:\.bd)?$/.test(h);
const MAX_HOPS = 5;
const TIMEOUT_MS = 6000;
const MAX_BODY = 400 * 1024;

function isShortUrl(u) {
  return u.protocol === 'https:' && (u.hostname === 'maps.app.goo.gl' || (u.hostname === 'goo.gl' && u.pathname.startsWith('/maps/')));
}

/**
 * ছোট লিংক → { lat, lng } বা null। fetchImpl: টেস্টে নকল fetch। redirect নিজে ধরা (redirect: 'manual'),
 * প্রতিটা ধাপের ঠিকানা Google-এর হতে হবে; শেষ পাতায় বিন্দু না থাকলে পাতার লেখায় খোঁজা (সর্বোচ্চ ৪০০ KB)।
 */
async function resolveShort(url, fetchImpl = fetch, timeoutMs = TIMEOUT_MS) {
  let u;
  try { u = new URL(String(url)); } catch { return null; }
  if (!isShortUrl(u)) return null;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    for (let hop = 0; hop < MAX_HOPS; hop++) {
      const res = await fetchImpl(u.href, {
        method: 'GET', redirect: 'manual', signal: ctl.signal,
        headers: { 'user-agent': 'Mozilla/5.0 (Linux; Android 14) Lifolo/2.31', 'accept-language': 'en' },
      });
      const loc = res.headers && typeof res.headers.get === 'function' ? res.headers.get('location') : null;
      if (res.status >= 300 && res.status < 400 && loc) {
        let next;
        try { next = new URL(loc, u); } catch { return null; }
        if (next.protocol !== 'https:' || !isFollowHost(next.hostname)) return null;
        const p = pointFrom(next.href, false);
        if (p) return p;
        u = next;
        continue;
      }
      if (res.status !== 200 || !isFollowHost(u.hostname) || SHORT_HOSTS.has(u.hostname)) return null;
      const body = String(await res.text()).slice(0, MAX_BODY);
      const arr = body.match(/\[null,null,(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)\]/);
      if (arr && inBd(Number(arr[1]), Number(arr[2]))) return { lat: Math.round(arr[1] * 1e5) / 1e5, lng: Math.round(arr[2] * 1e5) / 1e5 };
      return pointFrom(body, false);
    }
    return null;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// ব্যবহারকারী-প্রতি সীমা (একই সার্ভার চালু থাকা পর্যন্ত): মিনিটে ৬টা, ঘণ্টায় ৩০টা
const LIMITS = [[60 * 1000, 6], [3600 * 1000, 30]];
const used = new Map();
function checkLimit(uid, now = Date.now()) {
  const list = (used.get(uid) || []).filter((t) => now - t < 3600 * 1000);
  for (const [win, max] of LIMITS) if (list.filter((t) => now - t < win).length >= max) return false;
  list.push(now);
  used.set(uid, list);
  if (used.size > 5000) [...used.keys()].slice(0, 1000).forEach((k) => used.delete(k));
  return true;
}

// ------------------------------------------------------------
// হাসপাতাল/ক্লিনিক (গন্তব্য)
// ------------------------------------------------------------

/** অ্যাম্বুলেন্সের গন্তব্যে যে ধরনগুলো (ডায়াগনস্টিক নয়), আগে বড়গুলো */
const TYPE_RANK = { govt_mch: 0, govt_specialized: 1, govt_district: 2, govt_uhc: 3, govt_hospital: 4, private_hospital: 5, clinic: 6 };

// DGHS-এর তালিকায় নেই এমন বড় হাসপাতাল (মন্ত্রণালয়/বিশ্ববিদ্যালয়ের অধীনে)
const EXTRA = [
  {
    id: 'x_bsmmu', nameEn: 'Bangladesh Medical University (BSMMU, PG Hospital), Shahbag', nameBn: 'বাংলাদেশ মেডিকেল বিশ্ববিদ্যালয় (বিএসএমএমইউ, পিজি হাসপাতাল), শাহবাগ',
    type: 'govt_mch', district: 'ঢাকা', upazilaId: 1039,
  },
  {
    id: 'x_kurmitola', nameEn: 'Kurmitola General Hospital', nameBn: 'কুর্মিটোলা জেনারেল হাসপাতাল',
    type: 'govt_hospital', district: 'ঢাকা', upazilaId: 1008,
  },
];

// সারা দেশের বড় হাসপাতাল — রোগীর জেলার তালিকার পরে সবসময়
const TOP = [
  'dghs_31', // ঢাকা মেডিকেল কলেজ হাসপাতাল
  'dghs_7', // জাতীয় হৃদরোগ ইনস্টিটিউট (NICVD)
  'x_bsmmu',
  'dghs_22839', // শহীদ তাজউদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল, গাজীপুর
  'dghs_52', // স্যার সলিমুল্লাহ মেডিকেল কলেজ মিটফোর্ড হাসপাতাল
  'dghs_47', // শহীদ সোহরাওয়ার্দী মেডিকেল কলেজ হাসপাতাল
  'dghs_13562', // মুগদা মেডিকেল কলেজ হাসপাতাল
  'x_kurmitola',
  'dghs_13', // নিটোর (পঙ্গু)
  'dghs_16775', // নিউরো সায়েন্সেস
  'dghs_6', // ক্যান্সার
  'dghs_9', // কিডনি
  'dghs_24589', // বার্ন
  'dghs_24118', // বাংলাদেশ শিশু হাসপাতাল (ঢাকা শিশু)
  'dghs_392', // ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল
  'dghs_751', // চট্টগ্রাম মেডিকেল কলেজ হাসপাতাল
  'dghs_1554', // রাজশাহী
  'dghs_2186', // সিলেট এমএজি ওসমানী
  'dghs_1800', // খুলনা
  'dghs_1612', // রংপুর
  'dghs_1969', // শের-ই-বাংলা, বরিশাল
  'dghs_1105', // শহীদ জিয়াউর রহমান, বগুড়া
  'dghs_862', // কুমিল্লা
  'dghs_104', // ফরিদপুর
];

let byId = null;
let all = null;
function load() {
  if (all) return;
  byId = new Map();
  all = [];
  for (const [district, rows] of Object.entries(DIR.districts)) {
    for (const r of rows) {
      if (TYPE_RANK[r[3]] == null) continue;
      const f = { id: `dghs_${r[0]}`, nameEn: r[1], nameBn: r[2] || '', type: r[3], district, upazilaId: r[4] || null };
      byId.set(f.id, f);
      all.push(f);
    }
  }
  for (const f of EXTRA) { byId.set(f.id, f); all.push(f); }
}

/** id ("dghs_123" / "x_bsmmu") দিয়ে গন্তব্যের হাসপাতাল (ডায়াগনস্টিক নয়), না পেলে null */
function facility(id) {
  load();
  return typeof id === 'string' ? byId.get(id) || null : null;
}

/** বাংলা "য়/ড়/ঢ়"-এর দুই রূপ একরকম, ছোট হাতের — অ্যাপের Areas.bnNorm আর ওয়েবের bnNorm-এর মতো */
function bnNorm(s) {
  return String(s || '').normalize('NFC').replace(/য়/g, 'য়').replace(/ড়/g, 'ড়').replace(/ঢ়/g, 'ঢ়').toLowerCase();
}

/** ছোট রূপ (ফোনে কম ডেটা): [id, ইংরেজি, বাংলা, ধরন, জেলা, উপজেলা] */
const row = (f) => [f.id, f.nameEn, f.nameBn, f.type, f.district, f.upazilaId];

/** জায়গা থেকে হাসপাতালের দূরত্ব (উপজেলার কেন্দ্র, না থাকলে জেলার কেন্দ্র) — আনুমানিক, শুধু সাজাতে */
function approxKm(f, lat, lng) {
  const c = (f.upazilaId && CENTERS.c[f.upazilaId]) || (() => { const d = DISTRICTS.find((x) => x.bn === f.district); return d ? [d.lat, d.lng] : null; })();
  return c ? geo.distanceKm(lat, lng, c[0], c[1]) : 9999;
}

/**
 * গন্তব্যের তালিকা: q খালি হলে জেলার সব (বড়গুলো আগে; জায়গা দিলে কাছেরটা আগে), q দিলে সারা দেশে খোঁজা
 * (জেলারটা আগে, সর্বোচ্চ ৬০টা)। top: সারা দেশের বড় হাসপাতাল।
 */
function facilities({ district, q, lat, lng }) {
  load();
  const point = typeof lat === 'number' && typeof lng === 'number' && inBd(lat, lng) ? { lat, lng } : null;
  const rank = (f) => TYPE_RANK[f.type] ?? 9;
  const query = String(q || '').trim().slice(0, 60);
  let items;
  if (!query) {
    items = all.filter((f) => f.district === district);
    if (point) {
      const km = new Map(items.map((f) => [f.id, approxKm(f, point.lat, point.lng)]));
      // একই উপজেলার ভেতরে বড়গুলো আগে (দূরত্ব উপজেলার কেন্দ্র ধরে — তাই ৩ কিমির ধাপে)
      items.sort((a, b) => Math.floor(km.get(a.id) / 3) - Math.floor(km.get(b.id) / 3) || rank(a) - rank(b) || a.nameEn.localeCompare(b.nameEn));
    } else {
      items.sort((a, b) => rank(a) - rank(b) || a.nameEn.localeCompare(b.nameEn));
    }
  } else {
    const nq = bnNorm(query);
    const words = nq.split(/\s+/).filter(Boolean);
    const hit = (f) => {
      const hay = `${bnNorm(f.nameBn)} ${f.nameEn.toLowerCase()}`;
      return words.every((w) => hay.includes(w));
    };
    items = all.filter(hit)
      .sort((a, b) => (b.district === district) - (a.district === district) || rank(a) - rank(b) || a.nameEn.localeCompare(b.nameEn))
      .slice(0, 60);
  }
  return { items: items.map(row), top: TOP.map((id) => byId.get(id)).filter(Boolean).map(row) };
}

const actions = {
  /**
   * "কোথায় যাবেন"-এর তালিকা (রোগী) আর মালিকের ভাড়ার তালিকার হাসপাতালের সারি।
   * body: district, q (খোঁজা — সারা দেশে), lat/lng (রোগীর জায়গা — কাছেরটা আগে)। জেলার তালিকা ১০ মিনিট মেমোরিতে।
   */
  async amb_facilities(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const hasPoint = typeof b.lat === 'number' && typeof b.lng === 'number';
    if (!b.q && !hasPoint) {
      return require('./memcache').cached(`ambfac:${b.district}`, 10 * 60 * 1000, async () => facilities({ district: b.district }));
    }
    return facilities({ district: b.district, q: typeof b.q === 'string' ? b.q : '', lat: b.lat, lng: b.lng });
  },

  /**
   * Google Maps-এর ছোট লিংক (বা পুরো লিংক/লেখা) → { lat, lng }। body: url (বা শেয়ার করা লেখা)।
   * লম্বা লিংক এখানেই পড়া; ছোট লিংক শুধু maps.app.goo.gl / goo.gl/maps — redirect ধরে Google-এর ঠিকানায়।
   */
  async maps_link_resolve(req, user) {
    const text = typeof req.body?.url === 'string' ? req.body.url : '';
    const bad = () => new HttpError(400, C.tr(req,
      "Couldn't find a place in this link — in Google Maps, press and hold the place, then Share.",
      'এই লিংকে জায়গা পাওয়া যায়নি — Google ম্যাপে জায়গাটা চেপে ধরে "শেয়ার" চাপুন।'));
    const p = parseMapsText(text);
    if (!p) throw bad();
    if (p.lat != null) return { lat: p.lat, lng: p.lng };
    if (!checkLimit(user.uid)) throw new HttpError(429, C.tr(req, 'Too many links. Wait a minute and try again.', 'অনেকগুলো লিংক। এক মিনিট পর আবার চেষ্টা করুন।'));
    const q = await module.exports.resolveShort(p.short);
    if (!q) throw bad();
    return q;
  },
};

module.exports = { actions, nearestUpazila, parseMapsText, resolveShort, facility, facilities, bnNorm, checkLimit, TOP, EXTRA, MAX_CENTER_KM };
