// লগইন ছাড়া ব্যবহার (অতিথি) — নতুন নকশা N1/N2 (১৭ অক্টোবর ২০২৬)।
// অ্যাকাউন্ট না থাকলেও তালিকা দেখা আর ফোন করা যায়; শুধু পড়ার কিছু কাজ টোকেন ছাড়া চলে।
// লেখার সব কাজ (সাড়া, বুকিং, প্রশ্ন, রিপোর্ট, রেটিং, যোগ করা…) আগের মতোই লগইন চায়।
const { db, HttpError } = require('./firebase');
const C = require('./community');
const R = require('./rules');
const geo = require('./geo');
const { ipKey } = require('./email-code');

/** অতিথি যেসব কাজ করতে পারেন — সবই শুধু পড়া, কারো ব্যক্তিগত তথ্য নয় */
const PUBLIC_ACTIONS = new Set([
  'guest_requests',
  'emergencies_active',
  'questions', 'question', 'tips',
  'providers', 'home_providers', 'hospitals',
  'ambulances', 'pharmacies', 'bloodbanks', 'labs',
  // একটা প্যাথলজির পুরো পরীক্ষা ও দামের তালিকা (বিস্তারিত খুললে; labprices.js)
  'lab_prices_public',
  'drug_search', 'catalog_get',
]);

const isPublic = (action) => PUBLIC_ACTIONS.has(String(action || ''));

/** অতিথির "ব্যবহারকারী": uid নেই — কাজগুলো uid না পেলে ব্যক্তিগত অংশ (রেটিং, "আমার") বাদ দেয় */
function guestUser() {
  return { uid: null, guest: true, email: '' };
}

// IP-প্রতি সীমা (একই সার্ভার চালু থাকা পর্যন্ত): মিনিটে ২৪০টা অনুরোধ — যাতে কেউ টোকেন ছাড়া ডাটাবেস খালি না করে।
// মোবাইল অপারেটরের এক IP-র পেছনে অনেক মানুষ থাকেন, তাই খুব কম রাখা হয়নি; একই অনুরোধ ১ মিনিট মেমোরিতে থাকে।
const PER_MINUTE = 240;
const hits = new Map();
function checkRate(req, now = Date.now()) {
  // Vercel x-forwarded-for নিজেই বসায়, তাই এটা বিশ্বাসযোগ্য
  const ip = ipKey(String(req.headers?.['x-forwarded-for'] || '').split(',')[0].trim() || 'unknown');
  const h = hits.get(ip);
  if (!h || now - h.at > 60 * 1000) {
    hits.set(ip, { at: now, n: 1 });
    if (hits.size > 5000) [...hits.keys()].slice(0, 1000).forEach((k) => hits.delete(k));
    return;
  }
  h.n += 1;
  if (h.n > PER_MINUTE) throw new HttpError(429, C.tr(req, 'Too many requests. Wait a minute and try again.', 'অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।'));
}

/** অতিথির জন্য রিকোয়েস্টের তথ্য: নাম, ফোন, নোট, uid নেই (শেয়ার করা পাতার মতো); ঠিক লোকেশনের বদলে দূরত্ব */
function requestView(d, km) {
  const x = d.data();
  return {
    id: d.id,
    patientGroup: x.patientGroup || '',
    bags: x.bags || 1,
    hospital: x.hospital || '',
    placeName: x.placeName || '',
    neededByMillis: x.neededByMillis || 0,
    neededAtMillis: typeof x.neededAtMillis === 'number' ? x.neededAtMillis : null,
    urgent: x.urgent === true,
    travelFare: x.travelFare === true,
    status: x.status || 'open',
    acceptedCount: Math.max(x.acceptedCount || 0, 0),
    confirmedCount: Math.max(x.confirmedCount || 0, 0),
    bagsReceived: Math.max(x.bagsReceived || 0, 0),
    km: Math.round(km * 10) / 10,
  };
}

const actions = {
  /**
   * আশেপাশের খোলা রক্তের রিকোয়েস্ট (অতিথির হোম)। লগইন করা অ্যাপ আগের মতো সরাসরি ডাটাবেস পড়ে।
   * body: lat, lng (বাংলাদেশের ভেতরে), maxKm (৫–৫০, ডিফল্ট ২৫)
   */
  async guest_requests(req) {
    const b = req.body || {};
    if (typeof b.lat !== 'number' || typeof b.lng !== 'number' || b.lat < 20 || b.lat > 27 || b.lng < 88 || b.lng > 93) {
      throw new HttpError(400, C.tr(req, 'Choose your area', 'এলাকা বেছে নিন'));
    }
    const maxKm = Number.isInteger(b.maxKm) ? Math.min(Math.max(b.maxKm, 5), 25) : 10;
    // ডাটাবেস খোঁজা ~৫ কিমির ঘর ধরে (ঘরের মাঝখান থেকে একটু বড় পরিধি) আর এক মিনিট মেমোরিতে —
    // লোকেশন একটু একটু বদলে বারবার নতুন করে পড়ানো যায় না; দূরত্ব মাপা হয় আসল জায়গা থেকে
    const cLat = Math.round(b.lat * 20) / 20;
    const cLng = Math.round(b.lng * 20) / 20;
    const found = await require('./memcache').cached(`guestreq:${cLat},${cLng}:${maxKm}`, 60 * 1000, async () => {
      const list = await geo.near(db.collection('requests'), 'geoOpen', cLat, cLng, maxKm + 4, { limitPerBound: 200 });
      return list.map(({ doc }) => ({ id: doc.id, x: doc.data() }));
    });
    const now = Date.now();
    const { docLike } = require('./memcache');
    const items = found
      .filter(({ x }) => R.isActive(x, now) && typeof x.lat === 'number' && typeof x.lng === 'number')
      .map(({ id, x }) => ({ d: docLike(id, x), km: geo.distanceKm(b.lat, b.lng, x.lat, x.lng) }))
      .filter(({ km }) => km <= maxKm)
      .map(({ d, km }) => requestView(d, km))
      .sort((p, q) => (q.urgent - p.urgent) || (p.km - q.km))
      .slice(0, 100);
    return { items };
  },
};

/** অতিথির উত্তর মেমোরিতে রাখার চাবি: কাজ আর অনুরোধের তথ্য (ক্রম ঠিক রেখে) */
function cacheKey(body) {
  const b = body || {};
  return JSON.stringify(Object.keys(b).sort().map((k) => [k, b[k]])).slice(0, 500);
}

module.exports = { PUBLIC_ACTIONS, isPublic, guestUser, checkRate, actions, requestView, cacheKey };
