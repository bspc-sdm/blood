// অ্যাম্বুলেন্স: মালিক/চালক নিজের অ্যাম্বুলেন্স যোগ করেন, অ্যাডমিন যাচাই করে অনুমোদন দেন,
// রোগীর লোকজন এলাকা দিয়ে খুঁজে সরাসরি ফোন করেন। টাকা অ্যাপে নেই।
const { db, FieldValue, HttpError } = require('./firebase');
const geo = require('./geo');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');

const TYPES = ['general', 'ac', 'icu', 'freezer'];
const MAX_PER_OWNER = 10;
const RATE_WINDOW_MS = 7 * 24 * 3600 * 1000;
// ফোন চেপে সাথে সাথে কেটে রেটিং দেওয়া যায় না: প্রথম ফোনের অন্তত ১ ঘণ্টা পর
const RATE_AFTER_MS = 60 * 60 * 1000;
/**
 * রেটিং দেওয়া যায় কি না (ফোনের রেকর্ড c): ৭ দিনের মধ্যে, আর প্রথম ফোনের ১ ঘণ্টা পর —
 * অথবা অ্যাপে ডাকা অ্যাম্বুলেন্সের ট্রিপ চালক "শেষ" করেছেন (tripAt; lib/ambreq.js) — তখন অপেক্ষা নেই।
 */
function rateable(c, now = Date.now()) {
  if (now - (c.at || 0) > RATE_WINDOW_MS) return false;
  if (c.tripAt && now - c.tripAt <= RATE_WINDOW_MS) return true;
  return now - (c.firstAt || c.at || 0) >= RATE_AFTER_MS;
}
// "এখন খালি আছি" কতক্ষণ থাকে: চালক ভুলে গেলে বা কাজ ছেড়ে দিলে যেন তাঁর নম্বর সবার উপরে না থাকে
const AVAILABLE_MS = 12 * 3600 * 1000;
// "এখনো খালি আছেন?" — খালি থাকার মেয়াদ শেষ হওয়ার এই সময় আগে চালককে একবার মনে করানো
const REMIND_BEFORE_MS = 60 * 60 * 1000;
// খালি করার এতক্ষণ পর মনে করানোর সময় (remindAt = availableAt + ১১ ঘণ্টা)
const REMIND_AT_MS = AVAILABLE_MS - REMIND_BEFORE_MS;
// মনে করানোর খোঁজ: তালিকা পড়ার সাথে (Hobby প্ল্যানে ঘন ঘন ক্রন নেই) — এক সার্ভারে ১০ মিনিটে একবারের বেশি নয়
const REMIND_EVERY_MS = 10 * 60 * 1000;
// এক পাতায় কয়টা, সর্বোচ্চ কয় পাতা, একসাথে কয়টা transaction
const REMIND_PAGE = 100;
const REMIND_MAX_PAGES = 20;
const REMIND_PARALLEL = 5;
// তালিকা পড়ার সাথে চলা খোঁজ আটকে গেলে এর পর ছেড়ে দেওয়া (নইলে এই সার্ভারে আর কখনো খোঁজা হতো না)
const REMIND_GUARD_MS = 20 * 1000;
// গাড়ির জায়গা বেছে নেওয়া জেলার কেন্দ্র থেকে এর বেশি দূরে হলে ভুল ধরা হয় (জেলার সবচেয়ে দূরের আসল জায়গা কেন্দ্র থেকে ~১২৯ কিমি)
const BASE_MAX_KM = 130;
// জায়গা না দেওয়া গাড়ি জেলার যেকোনো জায়গায় থাকতে পারে — সাজানোর সময় এতটা দূরে ধরা, যাতে নিশ্চিত কাছের গাড়ি আগে আসে
const APPROX_EXTRA_KM = 5;

/**
 * বাংলাদেশের ভেতরের স্থানাঙ্ক, ২ দশমিকে (≈১ কিমির ঘর), নইলে null।
 * গোপনীয়তা: চালকের আসল GPS কখনো রাখা হয় না, আর রোগীর দিকের স্থানাঙ্কও একই ঘরে কাটা —
 * নানা জায়গা থেকে তালিকা চেয়ে গাড়ির (হয়তো চালকের বাসার) জায়গা ১০০ মিটারে বের করা যায় না, বড়জোর ~১ কিমির ঘর।
 */
function point(lat, lng) {
  if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < 20.3 || lat > 26.8 || lng < 87.9 || lng > 92.8) return null;
  return { lat: Math.round(lat * 100) / 100, lng: Math.round(lng * 100) / 100 };
}

/** দূরত্ব পুরো কিমিতে; ১ কিমির কম হলে ০ (অ্যাপে "১ কিমির কম") */
const wholeKm = (km) => (km < 1 ? 0 : Math.round(km));

/** অল্প অল্প করে একসাথে (একসাথে সর্বোচ্চ n) — ফল একই ক্রমে */
async function mapLimit(list, n, fn) {
  const out = new Array(list.length);
  let next = 0;
  const worker = async () => {
    while (next < list.length) {
      const i = next++;
      out[i] = await fn(list[i], i);
    }
  };
  await Promise.all(Array.from({ length: Math.min(n, list.length) }, worker));
  return out;
}

/**
 * গাড়ি কোথা থেকে চলে: চালক GPS দিয়ে জায়গা দিলে সেটা (পুরোনো ৩ দশমিকের জায়গাও এখানে ২ দশমিকে কাটা),
 * নইলে জেলার কেন্দ্র (উপজেলার স্থানাঙ্ক আমাদের কাছে নেই)। exact = false হলে দূরত্ব আনুমানিক ("প্রায়")।
 */
function baseOf(x) {
  const p = typeof x.lat === 'number' && typeof x.lng === 'number' ? point(x.lat, x.lng) : null;
  if (p) return { ...p, exact: true };
  const d = DISTRICTS.find((y) => y.bn === x.district);
  return d ? { lat: d.lat, lng: d.lng, exact: false } : null;
}

/** খালি কি না — শেষবার জানানোর ১২ ঘণ্টা পর নিজে থেকে "জানা নেই" */
function availableNow(x, now = Date.now()) {
  if (x.available === false) return false;
  const at = x.availableAt || x.updatedAt || x.createdAt || 0;
  return now - at <= AVAILABLE_MS;
}

/**
 * ট্রিপে আছে (চালক নিজে "খালি নেই" করেছেন) — তালিকায় দেখায় না; ট্রিপ শেষে "খালি আছি" চাপলে আবার আসে।
 * চালক ভুলে গেলে ১২ ঘণ্টা পর আবার তালিকায় ("ফোনে জেনে নিন"), যাতে গাড়িটা চিরদিন হারিয়ে না যায়।
 * নতুন যোগ হওয়া গাড়ি (কখনো বন্ধ করা হয়নি) ব্যস্ত ধরা হয় না।
 */
function busyNow(x, now = Date.now()) {
  if (x.available !== false) return false;
  const at = x.busyAt || x.availableAt || 0;
  return at > 0 && now - at < AVAILABLE_MS;
}

function view(d, mine = false) {
  const x = d.data();
  const n = x.ratingCount || 0;
  return {
    id: d.id,
    name: x.name,
    phone: K.fixPhone(x.phone),
    types: x.types || [],
    district: x.district,
    upazilaId: x.upazilaId || null,
    area: x.area || '',
    fare: x.fare || '',
    available: availableNow(x),
    // ০ = এখন ট্রিপে; অন্যথায় শেষ জানানোর সময় (খালি না হলে "ফোনে জেনে নিন")
    availableAt: busyNow(x) ? 0 : x.availableAt || x.updatedAt || x.createdAt || 1,
    // খালি থাকলে কখন পর্যন্ত (চালকের কার্ডে "আর ৪৫ মিনিট")
    availableUntil: availableNow(x) ? (x.availableAt || x.updatedAt || x.createdAt || 0) + AVAILABLE_MS : 0,
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    ...(mine ? { vehicleNo: x.vehicleNo, status: x.status, statusReason: x.statusReason || '', hasBase: typeof x.lat === 'number' } : {}),
  };
}

/** প্যানেলের তালিকায়: অনুমোদিতের পর কী বদলেছে, কারণ, আর অপেক্ষার তালিকায় সম্ভাব্য একই তালিকা */
async function adminExtras(collection, d, status) {
  const LR = require('./listingreview');
  return {
    statusReason: d.get('statusReason') || '',
    editRejectedReason: d.get('editRejectedReason') || '',
    changes: LR.changedFields(d.data()),
    ...(status === 'pending' ? { duplicates: await LR.possibleDuplicates(collection, d.id, d.data()).catch(() => []) } : {}),
    // কাগজ আর টিক দেওয়ার তালিকা (অপেক্ষা/বাতিলে — অনুমোদনের আগে), আর অনুমোদনের সময়ের যাচাই
    ...(['pending', 'rejected'].includes(status) ? { verify: await require('./verify').panelInfo('ambulance', d.id, d.data()).catch(() => null) } : {}),
    verification: d.get('verification') || null,
  };
}

/** মালিকের নিজের কার্ডে: কোন কাগজের ছবি বাকি (অপেক্ষায়/বাতিল বা অ্যাডমিন চাইলে) */
async function withDocs(kind, d, v) {
  const x = d.data();
  if (!['pending', 'rejected'].includes(x.status) && !x.docsRequestedAt) return v;
  return { ...v, ...(await require('./verify').ownerInfo(kind, d.id, x).catch(() => ({}))), docsRequestNote: x.docsRequestNote || '' };
}

/**
 * পুরোনো ডক (remindAt আসার আগে খালি করা): খালি থাকার মেয়াদের ভেতরে থাকা অনুমোদিত খালি গাড়িতে remindAt বসানো।
 * শুধু দৈনিক ক্রন থেকে — availableAt-এর range (এক ঘরের index); পরে চালক আবার চালু করলে remindAt নিজেই বসে।
 * দৌড়ে চালক এর মাঝে বন্ধ করলেও ক্ষতি নেই: মনে করানোর সময় আবার দেখে, খালি না থাকলে শুধু remindAt মোছে।
 */
async function backfillRemindAt(now = Date.now()) {
  const snap = await db.collection('ambulances').where('availableAt', '>=', now - AVAILABLE_MS).limit(1000).get();
  const todo = snap.docs.filter((d) => {
    const x = d.data();
    return x.status === 'approved' && x.available === true && typeof x.remindAt !== 'number' && (x.reminderSentAt || 0) < x.availableAt;
  });
  await mapLimit(todo, REMIND_PARALLEL, (d) => d.ref.update({ remindAt: d.get('availableAt') + REMIND_AT_MS }).catch(() => {}));
  return todo.length;
}

/**
 * "এখনো খালি আছেন?": "খালি আছি"-র মেয়াদ (১২ ঘণ্টা) শেষ হওয়ার শেষ এক ঘণ্টায় থাকা গাড়ির মালিককে একবার পুশ।
 * খালি করার সময় remindAt (= availableAt + ১১ ঘণ্টা) বসে; বন্ধ করলে, ট্রিপে গেলে বা আবার যাচাইয়ে গেলে মোছে।
 * কোয়েরি শুধু remindAt <= এখন (এক ঘরের index) — পাতায় পাতায়; যেটাই পড়া হয় তার remindAt মোছা হয়
 * (পাঠানো হোক বা না হোক — অনুমোদন নেই, মেয়াদ শেষ ইত্যাদি), তাই একই ডক বারবার পড়া হয় না।
 * কয়েকটা সার্ভার একসাথে পেলেও transaction দিয়ে একজনই পাঠায়। ফেরত: কয়টা গাড়ির জন্য পাঠানো হলো।
 * backfill: দৈনিক ক্রনে আগে পুরোনো ডকে remindAt বসানো।
 */
async function remindAvailable(now = Date.now(), { backfill = false } = {}) {
  if (backfill) await backfillRemindAt(now);
  const due = (x) => x.status === 'approved' && x.available === true && availableNow(x, now);
  const claimed = [];
  let last = null;
  for (let page = 0; page < REMIND_MAX_PAGES; page++) {
    let q = db.collection('ambulances').where('remindAt', '<=', now).orderBy('remindAt').limit(REMIND_PAGE);
    if (last) q = q.startAfter(last);
    const snap = await q.get();
    if (snap.empty) break;
    last = snap.docs[snap.docs.length - 1];
    const sent = await mapLimit(snap.docs, REMIND_PARALLEL, (d) => db.runTransaction(async (t) => {
      const s = await t.get(d.ref);
      if (!s.exists || typeof s.get('remindAt') !== 'number' || s.get('remindAt') > now) return false;
      const send = due(s.data());
      t.update(d.ref, { remindAt: FieldValue.delete(), ...(send ? { reminderSentAt: now } : {}) });
      return send;
    }).catch(() => false));
    snap.docs.forEach((d, i) => sent[i] && claimed.push(d));
    if (snap.size < REMIND_PAGE) break;
  }
  if (!claimed.length) return 0;
  const { sendPush, tokensOf } = require('./firebase');
  const owners = [...new Set(claimed.map((d) => d.get('ownerUid')).filter(Boolean))];
  if (!owners.length) return claimed.length;
  const users = new Map((await db.getAll(...owners.map((u) => db.doc(`users/${u}`)))).filter((u) => u.exists).map((u) => [u.id, u]));
  const items = claimed.filter((d) => users.has(d.get('ownerUid'))).map((d) => {
    const u = users.get(d.get('ownerUid'));
    const lang = u.get('lang');
    return {
      uid: u.id,
      tokens: tokensOf(u),
      title: R.L(lang, `${d.get('name')} — still available?`, `${d.get('name')} — এখনো খালি আছেন?`),
      body: R.L(lang, 'If yes, open and tap "Keep available".', 'হ্যাঁ হলে খুলে "আবার চালু রাখুন" চাপুন।'),
      // অ্যাপ/ওয়েব: "আমার কাজ" › আমার অ্যাম্বুলেন্স
      data: { type: 'services', kind: 'ambulance', ambulanceId: d.id },
    };
  });
  if (items.length) await sendPush(items);
  return claimed.length;
}

// শেষ পেছনের খোঁজ (পরীক্ষায় শেষ হওয়া পর্যন্ত অপেক্ষার জন্য)
let lastRemind = Promise.resolve();

/**
 * তালিকা পড়ার সাথে পেছনে খোঁজা — উত্তর এর জন্য অপেক্ষা করে না (জরুরি অবস্থায় প্রতিটা সেকেন্ড দামি)।
 * এই সার্ভারে শেষবার কখন খোঁজা হয়েছে memcache-এ (পরীক্ষায় clear() দিলে আবার খোঁজে); আটকে গেলে ২০ সেকেন্ডে ছেড়ে দেয়।
 * Vercel-এ উত্তর পাঠানোর পরও কাজ চলতে দিতে request-context-এর waitUntil (যা @vercel/functions ভেতরে ব্যবহার করে) —
 * না থাকলে (লোকাল/পরীক্ষা) এমনিই চলে; বাদ পড়লে দৈনিক ক্রন আছে।
 */
function remindInBackground() {
  const guarded = () => {
    let timer;
    const stop = new Promise((resolve) => {
      timer = setTimeout(() => resolve(0), REMIND_GUARD_MS);
      if (timer.unref) timer.unref();
    });
    return Promise.race([remindAvailable(), stop]).finally(() => clearTimeout(timer));
  };
  const p = require('./memcache').cached('ambulance:remind', REMIND_EVERY_MS, guarded)
    .catch((e) => console.error('ambulance remind failed', e && e.message));
  lastRemind = p;
  try {
    const ctx = globalThis[Symbol.for('@vercel/request-context')]?.get?.();
    if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(p);
  } catch {
    // Vercel-এর বাইরে — কিছু করার নেই
  }
  return p;
}

function requireAdmin(req, user) {
  return require('./admin').isAdmin(user).then((ok) => {
    if (!ok) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
  });
}

const actions = {
  /**
   * জেলার অনুমোদিত অ্যাম্বুলেন্স: খালি আগে, তারপর কাছেরটা আগে (lat/lng দিলে), নিজের উপজেলার আগে, রেটিং বেশি আগে।
   * lat/lng: রোগীর লোকজনের এখনকার জায়গা; gps: true = ফোন/ব্রাউজারের আসল GPS। ২ দশমিকে কাটা হয় (গোপনীয়তা, point দেখুন)।
   * gps ছাড়া স্থানাঙ্ক আনুমানিক ধরা: উপজেলা দিলে নিজের উপজেলা আগে, দূরত্ব শুধু তার পরে; km দেখানো হয় না।
   * অ্যাপ GPS না পেলে ("এলাকা ধরে") lat/lng পাঠায়ই না; পুরোনো অ্যাপও না → আগের সাজানো।
   * km: পুরো কিমি (০ = ১ কিমির কম), শুধু gps হলে।
   * nearestId: "সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন" বাটনের গাড়ি (ডেমো আর আসল আলাদা — আসল মানুষকে ডেমো গাড়ি নয়)।
   */
  async ambulances(req, user) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    // চালকদের "এখনো খালি আছেন?" — পেছনে চলে, তালিকার উত্তর এর জন্য অপেক্ষা করে না
    remindInBackground();
    const snap = await db.collection('ambulances').where('district', '==', b.district).where('status', '==', 'approved').limit(300).get();
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    // কোনগুলোতে রেটিং দিতে পারি (অ্যাপ বন্ধ করে খুললেও "রেটিং" বাটন থাকে)
    const calls = user && user.uid ? await db.collection('ambulanceCalls').where('uid', '==', user.uid).limit(200).get() : { docs: [] };
    const now = Date.now();
    const canRate = new Set(calls.docs.filter((c) => rateable(c.data(), now)).map((c) => c.get('ambulanceId')));
    let docs = snap.docs;
    if (require('./catalog').codes('ambulanceType').includes(b.type)) docs = docs.filter((d) => (d.get('types') || []).includes(b.type));
    // ট্রিপে থাকা গাড়ি তালিকায় নয় — শুধু কয়টা ট্রিপে আছে তা জানানো
    // ব্যস্ত গাড়ি আলাদা তালিকায় (অ্যাপে ভাঁজ করা অংশে) — দরকারে ফোন করে জানা যায় কবে ফিরবে
    const busy = docs.filter((d) => busyNow(d.data(), now))
      .map((d) => ({ ...view(d), canRate: canRate.has(d.id), busy: true, busySince: d.get('busyAt') || d.get('availableAt') || 0 }))
      .sort((a, c) => ((upazilaId && c.upazilaId === upazilaId) - (upazilaId && a.upazilaId === upazilaId)) || (a.busySince - c.busySince));
    const from = point(b.lat, b.lng);
    // আসল GPS না হলে (এলাকার কেন্দ্র ইত্যাদি) আনুমানিক — নিজের উপজেলা আগে, km নয়
    const exactFrom = !!from && b.gps === true;
    const rows = docs.filter((d) => !busyNow(d.data(), now)).map((d) => {
      const v = { ...view(d), canRate: canRate.has(d.id) };
      const base = from ? baseOf(d.data()) : null;
      let dist = 9999;
      if (base) {
        const km = geo.distanceKm(from.lat, from.lng, base.lat, base.lng);
        // গাড়ির জায়গা দেওয়া নেই — জেলার কেন্দ্র ধরে, তাই "প্রায়" আর একটু পিছিয়ে
        dist = km + (base.exact ? 0 : APPROX_EXTRA_KM);
        if (exactFrom) {
          v.km = wholeKm(km);
          if (!base.exact) v.kmApprox = true;
        }
      }
      return { v, dist, owner: d.get('ownerUid') };
    });
    const sameUpazila = (x) => (upazilaId && x.upazilaId === upazilaId ? 1 : 0);
    rows.sort((a, c) =>
      (c.v.available - a.v.available) ||
      (from && !exactFrom ? sameUpazila(c.v) - sameUpazila(a.v) : 0) ||
      (from ? a.dist - c.dist : 0) ||
      (sameUpazila(c.v) - sameUpazila(a.v)) ||
      (c.v.rating - a.v.rating) || (c.v.ratingCount - a.v.ratingCount));
    const items = rows.map((r) => r.v);
    const uid = user && user.uid;
    // শুধু ফ্রিজার ভ্যান (মৃতদেহ নেওয়ার গাড়ি) রোগীর জরুরি ফোনে নয় — "ফ্রিজার" দিয়ে ছাঁকলে তবেই
    const forPatient = (v) => b.type === 'freezer' || v.types.some((t) => t !== 'freezer');
    const nearest = rows.find((r) => r.v.available && forPatient(r.v) && r.owner !== uid && require('./demo').sameWorld(uid, r.owner));
    return { items, busyCount: busy.length, busy: busy.slice(0, 50), nearestId: nearest ? nearest.v.id : null };
  },

  async ambulance_mine(req, user) {
    const snap = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    return { items: await Promise.all(snap.docs.map((d) => withDocs('ambulance', d, view(d, true)))) };
  },

  /** নতুন যোগ বা বদল। নাম, ফোন বা গাড়ির নম্বর বদলালে আবার যাচাই */
  async ambulance_save(req, user) {
    const b = req.body || {};
    const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
    const name = C.cleanText(b.name, 80);
    if (!name) throw err('Enter the service or driver name', 'সার্ভিস বা চালকের নাম লিখুন');
    // মোবাইল, ল্যান্ডলাইন বা হটলাইন (সার্ভিসের অফিসের নম্বর)
    const phone = K.contactPhone(b.phone);
    if (!phone) throw err('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন');
    const vehicleNo = C.cleanText(b.vehicleNo, 40);
    if (!vehicleNo) throw err('Enter the vehicle registration number', 'গাড়ির রেজিস্ট্রেশন নম্বর দিন');
    const allTypes = require('./catalog').codes('ambulanceType');
    const types = K.pickCodes(b.types, allTypes, allTypes.length);
    if (!types.length) throw err('Choose the ambulance type', 'অ্যাম্বুলেন্সের ধরন বেছে নিন');
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err('Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    if (b.termsAccepted !== true) throw err('Please accept the terms', 'শর্তে সম্মতি দিন');
    // গাড়ি যেখানে অপেক্ষা করে (ঐচ্ছিক, ফোনের GPS) — "সবচেয়ে কাছের" খুঁজতে; না দিলে জেলার কেন্দ্র ধরা হয়।
    // ২ দশমিকে কেটে রাখা (≈১ কিমির ঘর) — আসল GPS কখনো রাখা হয় না।
    // জেলার বাইরে হলে জায়গাটা বাদ, বাকি সেভ হয় (ভুল জায়গার জন্য পুরো ফর্ম আটকায় না) — উত্তরে baseIgnored
    let base = b.clearBase === true ? null : point(b.lat, b.lng);
    const dc = DISTRICTS.find((d) => d.bn === b.district);
    const baseIgnored = !!base && geo.distanceKm(base.lat, base.lng, dc.lat, dc.lng) > BASE_MAX_KM;
    if (baseIgnored) base = null;
    const data = {
      name,
      phone,
      vehicleNo,
      types,
      district: b.district,
      upazilaId,
      area: K.str(b.area, 120),
      fare: K.str(b.fare, 120),
      ...(base ? { lat: base.lat, lng: base.lng } : {}),
      updatedAt: Date.now(),
    };
    if (b.id) {
      if (!R.isValidId(b.id)) throw err('Not found', 'পাওয়া যায়নি');
      const ref = db.doc(`ambulances/${b.id}`);
      const s = await ref.get();
      if (!s.exists || s.get('ownerUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'This ambulance is suspended. You can appeal from Profile → Help & appeals.', 'এই অ্যাম্বুলেন্স বন্ধ করা আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
      await require('./sanctions').checkListing(req, 'ambulance', data, user.email, false);
      // নামের ছোট বানান-ঠিক হলে আবার যাচাই নয় (তালিকা থেকে সরে না); ফোন/গাড়ির নম্বর বা বড় বদলে যাচাই
      // জেলা বদলালেও আবার যাচাই (এক জায়গায় যাচাই করা গাড়ি অন্য জেলায় সরানো যায় না)
      const { changed, approvedName } = K.reviewNeeded(s, data, ['phone', 'vehicleNo', 'district']);
      // নতুন কাগজের ছবি দিলে (না দিলে আগেরগুলো থাকে)
      await require('./verify').saveDocs(req, 'ambulance', b.id, user.uid, { ...s.data(), ...data }, b.docs, false);
      // নতুন জায়গা না দিলে আগেরটা থাকে; চালক "জায়গা মুছুন" চাপলে, বা জেলা বদলালে (পুরোনো জায়গা আর খাটে না) মুছে জেলার কেন্দ্র
      const dropBase = !base && typeof s.get('lat') === 'number' && (b.clearBase === true || s.get('district') !== data.district);
      await ref.update({
        ...data, ...(approvedName ? { approvedName } : {}), ...K.keepApproved(s, data, changed),
        // আবার যাচাইয়ে গেলে তালিকায় নেই — মনে করানোও নয়
        ...(changed ? { status: 'pending', remindAt: FieldValue.delete() } : {}),
        ...(dropBase ? { lat: FieldValue.delete(), lng: FieldValue.delete() } : {}),
      });
      return { ok: true, pending: changed || s.get('status') === 'pending', ...(baseIgnored ? { baseIgnored: true } : {}) };
    }
    // রিপোর্টের পর বন্ধ হওয়া গাড়ি মুছে আবার যোগ করা যায় না
    const sameVehicle = await db.collection('ambulances').where('vehicleNo', '==', vehicleNo).where('status', '==', 'suspended').limit(1).get();
    if (!sameVehicle.empty) throw new HttpError(403, C.tr(req, 'This vehicle is suspended. You can appeal from Profile → Help & appeals.', 'এই গাড়ি বন্ধ করা আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
    await require('./sanctions').checkListing(req, 'ambulance', data, user.email, true);
    const mine = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    if (mine.size >= MAX_PER_OWNER) throw err('Up to 10 ambulances per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ১০টা অ্যাম্বুলেন্স');
    // কাগজের ছবি (রেজিস্ট্রেশন, ফিটনেস, গাড়ি) আগে — না থাকলে তালিকা তৈরিই হয় না, দৈনিক সীমাও খরচ হয় না
    require('./verify').validateDocs(req, 'ambulance', data, b.docs, true);
    await useQuota(req, user.uid, 'ambulance');
    const ref = db.collection('ambulances').doc();
    const docsHave = await require('./verify').saveDocs(req, 'ambulance', ref.id, user.uid, data, b.docs, true);
    await ref.set({ ...data, ownerUid: user.uid, status: 'pending', available: false, docsHave: docsHave || {}, createdAt: Date.now() });
    return { ok: true, pending: true, id: ref.id, ...(baseIgnored ? { baseIgnored: true } : {}) };
  },

  async ambulance_available(req, user) {
    const { id, available } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const on = available === true;
    const now = Date.now();
    await ref.update({
      available: on, availableAt: now, busyAt: on ? FieldValue.delete() : now, updatedAt: now,
      // "এখনো খালি আছেন?" — শুধু অনুমোদিত খালি গাড়িতে, ১১ ঘণ্টা পর; বন্ধ/ট্রিপে গেলে মোছে
      remindAt: on && s.get('status') === 'approved' ? now + REMIND_AT_MS : FieldValue.delete(),
    });
    return { ok: true };
  },

  async ambulance_delete(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) return { ok: true };
    if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'A suspended ambulance can\'t be removed. You can appeal from Profile → Help & appeals.', 'বন্ধ করা অ্যাম্বুলেন্স সরানো যায় না। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
    await require('./moderation').assertNoOpenReports(req, `ambulances/${id}`);
    await ref.delete();
    await require('./verify').deleteDocs('ambulance', id, s.data()).catch(() => {});
    return { ok: true };
  },

  /**
   * কেউ ফোন করলে লিখে রাখা — রেটিং/রিপোর্ট শুধু যারা ফোন করেছেন তারাই দিতে পারেন।
   * reqId: চালু "অ্যাম্বুলেন্স ডাকুন" অনুরোধের অপেক্ষার সময় ফোন — অনুরোধেও তোলা (lib/ambreq.js attachCall)
   */
  async ambulance_called(req, user) {
    const { id } = req.body || {};
    if (R.isValidId(id) && req.body?.reqId != null) {
      await require('./ambreq').attachCall(user.uid, req.body.reqId, id).catch((e) => console.error('amb req call attach', e.message));
    }
    if (!R.isValidId(id)) return { ok: true };
    const a = await db.doc(`ambulances/${id}`).get();
    if (!a.exists || a.get('ownerUid') === user.uid) return { ok: true };
    if (!require('./demo').sameWorld(user.uid, a.get('ownerUid'))) return { ok: true }; // ডেমো: আলাদা — অন্য জগতের ফোন গোনা হয় না
    const callRef = db.doc(`ambulanceCalls/${user.uid}_${id}`);
    const prev = await callRef.get();
    const now = Date.now();
    await callRef.set({ uid: user.uid, ambulanceId: id, at: now, ...(prev.exists ? {} : { firstAt: now }) }, { merge: true });
    // মালিকের হিসাব: একই মানুষ ১০ মিনিটের মধ্যে আবার চাপলে নতুন ফোন ধরা হয় না
    if (!prev.exists || now - (prev.get('at') || 0) > 10 * 60 * 1000) {
      await require('./sellerstats').bumpAmbulance(id, now, !prev.exists);
    }
    return { ok: true };
  },

  async ambulance_rate(req, user) {
    const { id } = req.body || {};
    const rating = Number.isInteger(req.body?.rating) ? req.body.rating : 0;
    if (!R.isValidId(id) || rating < 1 || rating > 5) throw new HttpError(400, C.tr(req, 'Choose 1 to 5 stars', '১ থেকে ৫ তারা দিন'));
    const callRef = db.doc(`ambulanceCalls/${user.uid}_${id}`);
    const ref = db.doc(`ambulances/${id}`);
    await db.runTransaction(async (t) => {
      const [c, a] = await Promise.all([t.get(callRef), t.get(ref)]);
      if (!a.exists || !require('./demo').sameWorld(user.uid, a.get('ownerUid'))) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি')); // ডেমো: আলাদা
      if (!c.exists || Date.now() - (c.get('at') || 0) > RATE_WINDOW_MS) {
        throw new HttpError(403, C.tr(req, 'You can rate after calling this ambulance from the app', 'অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন'));
      }
      if (!rateable(c.data())) {
        throw new HttpError(403, C.tr(req, 'You can rate 1 hour after your first call', 'প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন'));
      }
      const old = c.get('rating') || 0;
      t.update(ref, { ratingSum: FieldValue.increment(rating - old), ratingCount: FieldValue.increment(old ? 0 : 1) });
      t.update(callRef, { rating, comment: K.str(req.body?.comment, 300), ratedAt: Date.now() });
    });
    // মালিকের হিসাবে সাম্প্রতিক ১০টা রেটিং (কে দিয়েছেন তা ছাড়া)
    await require('./sellerstats').addRecentRating('ambulanceStats', id, rating, K.str(req.body?.comment, 300), user.uid);
    return { ok: true };
  },

  /** অবস্থা অনুযায়ী তালিকা (ডিফল্ট: অপেক্ষায়) */
  async admin_ambulances(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'approved', 'suspended', 'rejected'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('ambulances').where('status', '==', status).limit(300).get();
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...view(d, true),
        ownerUid: d.get('ownerUid') || null,
        createdAt: d.get('createdAt') || null,
        reviewedAt: (status === 'suspended' && d.get('suspendedAt')) || d.get('reviewedAt') || null,
        ...(await adminExtras('ambulances', d, status)),
      }))),
    };
  },

  async admin_ambulance_resolve(req, user) {
    await requireAdmin(req, user);
    // অ্যাপ: approve; ওয়েব প্যানেল: status দিয়ে বন্ধ বা আবার চালুও, সাথে কারণ
    return require('./listingreview').resolveListing(req, user, 'ambulance', 'ambulances', req.body?.id);
  },
};

module.exports = {
  actions, TYPES, AVAILABLE_MS, REMIND_AT_MS, BASE_MAX_KM, remindAvailable, point, baseOf, availableNow, busyNow, rateable,
  // পরীক্ষায়: তালিকা পড়ার সাথে চলা পেছনের খোঁজ শেষ হওয়া পর্যন্ত অপেক্ষা
  remindIdle: () => lastRemind,
};
