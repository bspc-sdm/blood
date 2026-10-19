// অ্যাম্বুলেন্স এক চাপে ডাকুন (২.২৮.০, পারভেজ "বানা", ১৯ অক্টোবর ২০২৬) — Uber-এর মতো, সাথে ফোনের বিকল্প।
//
// রোগী "অ্যাম্বুলেন্স ডাকুন" চাপলে ambulanceRequests/{id} তৈরি হয়, আর কাছের খালি (মানানসই ধরনের) ৫টা গাড়ির
// মালিক/চালকের কাছে একসাথে পুশ যায়। যিনি আগে "আসছি" চাপেন তিনিই পান (transaction: open → accepted);
// বাকিদের তালিকা থেকে সাথে সাথে সরে যায়।
//
// সময় (দীর্ঘ চলা সার্ভার নেই, Vercel Hobby-তে মিনিটে মিনিটে ক্রনও নেই):
//   - রোগীর অ্যাপ অপেক্ষার সময় কয়েক সেকেন্ড পরপর অবস্থা জানতে চায় (একটা ডক পড়া — সস্তা)।
//   - ৬০ সেকেন্ডে কেউ না নিলে একবার পরিধি বাড়ানো (পরের ৫টা) আর রোগীকে কাছের ৩টা গাড়ির ফোনের বাটন —
//     এটা ঘটে রোগীর অবস্থা জানতে চাওয়ায় বা কোনো চালকের তালিকা পড়ায়; transaction-এ wave দেখে, তাই একবারই।
//   - ৩ মিনিটে কেউ না নিলে "সময় শেষ" (ফোনের বাটন থাকে)।
//   - দৈনিক ক্রন: পড়ে থাকা চালু অনুরোধ বন্ধ, আর বন্ধের ২৪ ঘণ্টা পর ঠিক জায়গা, ফোন, ঠিকানা, নোট, নাম মোছা।
//
// গোপনীয়তা: "আসছি" চাপার আগে চালক দেখেন শুধু দূরত্ব (পুরো কিমি) আর রোগীর লেখা ঠিকানা/নোট — ঠিক GPS, ফোন, নাম নয়।
// রাজি হওয়া চালক শুধু সেই অনুরোধে রোগীর নাম, ফোন আর ঠিক জায়গা (ম্যাপে খুলতে) পান। রোগী দেখেন গাড়ির নাম আর
// চালকের ফোন (তালিকায় এমনিতেই প্রকাশ্য) — গাড়ির নম্বর নয় (সেটা শুধু মালিক ও অ্যাডমিন)।
// ডেমো আর আসল আলাদা (sameWorld)। অতিথি ডাকতে পারেন না (লগইন দরকার), ফোন আগের মতো সবাই।
//
// ফোন আর অনুরোধ একসাথে (পারভেজ: "৬০ সেকেন্ডের পর রোগী ৩টায় ফোন করলে, আবার পরিধিও বাড়লে — কীভাবে সামলাবে?"):
//   ১) চালু অনুরোধের সময় রোগী কোনো গাড়িতে ফোন করলে (ambulance_called + reqId) সেটা অনুরোধে calledIds-এ ওঠে;
//      পরিধি বাড়ানোর সময় ফোন করা গাড়ি বাদ (তাদের কাছে আগেই আছে)।
//   ২) ফোন করা গাড়ির চালক (আগে পাঠানো না হলেও) তালিকায় অনুরোধটা পান, "রোগী আপনাকে ফোন করেছেন" চিহ্নসহ;
//      অ্যাপে "আসছি" চাপা সাধারণ রাজি হওয়ার মতোই (আগে যিনি) — এটাই পছন্দের পথ।
//   ৩) রোগী ফোনে গাড়ি পেলে এক চাপে জানান (ambreq_byphone): অনুরোধ byPhone হয়ে বন্ধ, সেই গাড়ির সাথে যুক্ত
//      (রেটিংয়ের যোগ্যতায় ফোনের মতো গোনা); বাকি সব চালকের তালিকা থেকে সরে, পুশ "রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই"।
//   ৪) byPhone-এর পর অ্যাপে "আসছি" চাপলে পরিষ্কার বার্তা — নেওয়া যায় না।
//   ৫) একজন রাজি হলেন, কিন্তু রোগী ফোনে অন্য গাড়ি পেয়েছেন: "ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল" (ambreq_replace) —
//      সেই চালক বাতিল (patient_found_other, ভদ্র পুশ), তারপর রোগী একটা গাড়ি বাছলে byPhone, নইলে অনুরোধ আবার খোলা।
//   ৬) কঠিন নিয়ম: এক অনুরোধে এক সময়ে সর্বোচ্চ একটা গাড়ি রাজি — সব বদল transaction-এ, status দেখে।
const { db, FieldValue, HttpError } = require('./firebase');
const geo = require('./geo');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA, UNION } = require('./hospital-match');

const SEC = 1000;
const MIN = 60 * SEC;
const HOUR = 60 * MIN;
/** এক ধাপে কয়জন মালিককে পুশ */
const WAVE = 5;
/** কেউ না নিলে এতক্ষণ পর পরিধি বাড়ানো আর ফোনের বাটন */
const WIDEN_MS = 60 * SEC;
/** কেউ না নিলে এতক্ষণ পর "সময় শেষ" */
const EXPIRE_MS = 3 * MIN;
/** রাজি হওয়ার পর চালক "শেষ" না চাপলে এতক্ষণ পর নিজে থেকে বন্ধ (দৈনিক ক্রন বা পরের পড়ায়) */
const ACCEPTED_MAX_MS = 12 * HOUR;
/** অপেক্ষার পাতায় কয়টা গাড়ির ফোনের বাটন */
const FALLBACK = 3;
/** ঘণ্টায় সর্বোচ্চ কয়টা অনুরোধ */
const PER_HOUR = 5;
/** বন্ধের এতক্ষণ পর ঠিক জায়গা, ফোন, ঠিকানা, নোট, নাম মোছা */
const PURGE_AFTER_MS = 24 * HOUR;
/** গাড়ি রোগীর এর বেশি দূরে হলে নয় (জেলার নিয়মের মতো — lib/ambulance.js BASE_MAX_KM) */
const MAX_KM = 130;
/** জায়গা না দেওয়া গাড়ি জেলার যেকোনো জায়গায় থাকতে পারে — সাজানোর সময় একটু পিছিয়ে (lib/ambulance.js-এর মতো) */
const APPROX_EXTRA_KM = 5;

const ACTIVE = ['open', 'accepted', 'arrived'];
const PATIENT_CANCEL = ['wrong', 'found_other', 'not_needed', 'too_slow', 'other'];
/** চালু অনুরোধের সময় (আবার খুললে নতুন করে গোনা) */
const openedAt = (x) => x.openedAt || x.createdAt;
const DRIVER_CANCEL = ['vehicle_problem', 'cant_reach', 'patient_unreachable', 'other'];

const A = () => require('./ambulance');
const sameWorld = (a, b) => require('./demo').sameWorld(a, b);
const err = (req, status, en, bn, code) => new HttpError(status, C.tr(req, en, bn), code);
const wholeKm = (km) => (km < 1 ? 0 : Math.round(km));

/** রোগীর ঠিক জায়গা (বাংলাদেশের ভেতরে), ৫ দশমিকে (~১ মিটার) — শুধু রাজি হওয়া চালকের জন্য */
function exactPoint(lat, lng) {
  if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < 20.3 || lat > 26.8 || lng < 87.9 || lng > 92.8) return null;
  return { lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5 };
}

/**
 * গাড়ি এই অনুরোধে চলে কি না। ধরন (২.২৯.০, রোগী নিশ্চিত করার ঘরে বাছেন): null = "যেকোনো", ac = এসি,
 * general = নন-এসি/সাধারণ, icu = আইসিইউ, freezer = ফ্রিজার (মরদেহ)।
 * ফ্রিজার শুধু রোগী নিজে "ফ্রিজার" বাছলে: ফ্রিজার ভ্যান (অন্য ধরনও লেখা থাকলেও) রোগীর কোনো ডাকে যায় না —
 * মৃতদেহের গাড়িতে জীবিত রোগী নয়।
 */
function typeOk(types, type) {
  const t = types || [];
  if (type === 'freezer') return t.includes('freezer');
  if (t.includes('freezer')) return false;
  return type ? t.includes(type) : t.length > 0;
}

/**
 * কোন গাড়িগুলোতে পাঠানো যায়, কাছেরটা আগে: অনুমোদিত, ট্রিপে নয়, ধরন মেলে, নিজের নয়, একই জগৎ (ডেমো/আসল),
 * ১৩০ কিমির ভেতরে। খালি জানানোগুলো আগে, তারপর "জানা নেই" (১২ ঘণ্টা আগে খালি জানানো) — খালি শেষ হলে তবেই এরা।
 * GPS না থাকলে দূরত্ব নয়: নিজের উপজেলা আগে, তারপর রেটিং। একই মালিকের কয়েকটা গাড়ি থাকলে শুধু কাছেরটা।
 * docs: ambulances-এর snapshot; r: { uid, lat?, lng?, gps, upazilaId, type }; skipOwners: আগে পাঠানো মালিক
 */
function candidates(docs, r, skipOwners = [], now = Date.now(), skipIds = []) {
  const from = r.gps && typeof r.lat === 'number' ? { lat: r.lat, lng: r.lng } : null;
  const skip = new Set(skipOwners);
  const skipId = new Set(skipIds);
  const rows = [];
  for (const d of docs) {
    const x = d.data();
    const owner = x.ownerUid;
    if (x.status !== 'approved' || !owner || owner === r.uid || skip.has(owner) || skipId.has(d.id)) continue;
    if (!sameWorld(r.uid, owner)) continue;
    if (A().busyNow(x, now) || x.available === false) continue;
    if (!typeOk(x.types, r.type)) continue;
    const base = A().baseOf(x);
    let dist = null;
    let km = null;
    let approx = false;
    if (from && base) {
      const real = geo.distanceKm(from.lat, from.lng, base.lat, base.lng);
      if (real > MAX_KM) continue;
      dist = real + (base.exact ? 0 : APPROX_EXTRA_KM);
      km = wholeKm(real);
      approx = !base.exact;
    }
    const n = x.ratingCount || 0;
    rows.push({
      id: d.id, owner, name: x.name, phone: K.fixPhone(x.phone), km, approx, dist,
      avail: A().availableNow(x, now), same: r.upazilaId && x.upazilaId === r.upazilaId ? 1 : 0,
      rating: n ? x.ratingSum / n : 0,
    });
  }
  rows.sort((a, b) => (b.avail - a.avail) || (a.dist != null && b.dist != null ? a.dist - b.dist : 0) || (b.same - a.same) || (b.rating - a.rating));
  // একই মালিক একবারই (কাছের গাড়িটা)
  const seen = new Set();
  return rows.filter((x) => (seen.has(x.owner) ? false : seen.add(x.owner)));
}

/** জেলার অনুমোদিত গাড়ি (তালিকার মতো একই কোয়েরি) */
async function districtAmbulances(district) {
  return (await db.collection('ambulances').where('district', '==', district).where('status', '==', 'approved').limit(300).get()).docs;
}

/** ফোনের বাটনের জন্য: কাছের ৩টা খালি গাড়ি (নাম, ফোন, দূরত্ব) — একবার হিসাব করে অনুরোধে রাখা, তাই প্রতি পড়ায় খরচ নেই */
function fallbackOf(list) {
  return list.filter((x) => x.avail).slice(0, FALLBACK).map((x) => ({ id: x.id, name: x.name, phone: x.phone, km: x.km, ...(x.approx ? { kmApprox: true } : {}) }));
}

/** একটা ধাপের পাঠানো: অনুরোধে যোগ করার ঘর */
function waveFields(list) {
  const sentTo = {};
  const km = {};
  list.forEach((x) => {
    sentTo[x.owner] = x.id;
    if (x.km != null) km[x.owner] = x.approx ? -1 - x.km : x.km; // ঋণাত্মক = "প্রায়" (জায়গা দেওয়া নেই)
  });
  return { sentTo, km };
}

const kmOf = (v) => (typeof v !== 'number' ? { km: null } : v < 0 ? { km: -1 - v, kmApprox: true } : { km: v });

/** চালকদের পুশ: ঠিকানা/নোট নয় (নোটিফিকেশনের তালিকায় ২১ দিন থাকে) — শুধু দূরত্ব */
async function pushDrivers(id, x, owners) {
  if (!owners.length) return;
  const { sendPush, tokensOf } = require('./firebase');
  const users = (await db.getAll(...owners.map((u) => db.doc(`users/${u}`)))).filter((u) => u.exists);
  await sendPush(users.map((u) => {
    const lang = u.get('lang');
    const k = kmOf(x.km?.[u.id]);
    const where = k.km == null ? R.L(lang, 'nearby', 'কাছেই') : R.formatKm(k.km, lang);
    return {
      uid: u.id,
      tokens: tokensOf(u),
      title: R.L(lang, `Ambulance needed — ${where}`, R.bnNumbers(`অ্যাম্বুলেন্স দরকার — ${where}`)),
      body: R.L(lang, 'Open and tap "Coming" — the first to accept gets it.', 'খুলে "আসছি" চাপুন — আগে যিনি রাজি হবেন তিনিই পাবেন।'),
      // পুরোনো অ্যাপও "আমার অ্যাম্বুলেন্স" পাতা খোলে (type: services, kind: ambulance)
      data: { type: 'services', kind: 'ambulance', sub: 'amb_req', reqId: id },
    };
  }));
}

/** রোগীকে পুশ (অপেক্ষার পাতা খোলে) */
async function pushPatient(id, uid, en, bn, enBody = '', bnBody = '') {
  const { sendPush, tokensOf } = require('./firebase');
  const u = await db.doc(`users/${uid}`).get();
  if (!u.exists) return;
  const lang = u.get('lang');
  await sendPush([{ uid, tokens: tokensOf(u), title: R.L(lang, en, bn), body: R.L(lang, enBody, bnBody), data: { type: 'amb_req_mine', reqId: id } }]);
}

/** চালককে পুশ (রোগী বাতিল করলে) */
async function pushDriver(id, uid, en, bn) {
  const { sendPush, tokensOf } = require('./firebase');
  const u = await db.doc(`users/${uid}`).get();
  if (!u.exists) return;
  const lang = u.get('lang');
  await sendPush([{ uid, tokens: tokensOf(u), title: R.L(lang, en, bn), body: '', data: { type: 'services', kind: 'ambulance', sub: 'amb_req', reqId: id } }]);
}

/** বন্ধের সময়ের ঘর: চালু তালিকা থেকে সরানো, ২৪ ঘণ্টা পর মোছার দিন */
const closeFields = (now, status, extra = {}) => ({ status, active: false, activeOwners: [], closedAt: now, purgeAt: now + PURGE_AFTER_MS, ...extra });

/** গাড়ি আবার খালি (ট্রিপ শেষ/বাতিল) — "খালি আছি" চাপার মতোই (ambulance_available) */
function freeVehicle(t, ambRef, amb, now) {
  if (!amb || !amb.exists || amb.get('reqBusy') == null) return;
  t.update(ambRef, {
    available: true, availableAt: now, busyAt: FieldValue.delete(), updatedAt: now, reqBusy: FieldValue.delete(),
    remindAt: amb.get('status') === 'approved' ? now + A().REMIND_AT_MS : FieldValue.delete(),
  });
}

/**
 * সময় ধরে এগোনো (রোগীর অবস্থা পড়া, চালকের তালিকা পড়া, দৈনিক ক্রন — যেকোনো জায়গা থেকে, বারবার ডাকলেও একবারই):
 *  - খোলা, ৩ মিনিট পেরিয়েছে → expired (রোগীকে পুশ)
 *  - খোলা, ৬০ সেকেন্ড পেরিয়েছে বা সবাই "পারব না" বলেছেন, এখনো প্রথম ধাপ → পরের ৫টা + ফোনের বাটন
 *  - রাজি/পৌঁছেছে, ১২ ঘণ্টা পেরিয়েছে → নিজে থেকে বন্ধ
 * ফেরত: নতুন ডক (snapshot-এর মতো data())
 */
async function advance(ref, now = Date.now()) {
  const s = await ref.get();
  if (!s.exists) return null;
  const x = s.data();
  if (x.status === 'open' && now - openedAt(x) >= EXPIRE_MS) {
    const done = await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      if (c.get('status') !== 'open') return false;
      t.update(ref, closeFields(now, 'expired'));
      return true;
    });
    // দৈনিক ক্রনে অনেক পরে বন্ধ হলে পুশ নয় (রোগী অনেক আগেই অন্যভাবে ব্যবস্থা করেছেন)
    if (done && now - openedAt(x) < EXPIRE_MS + 10 * MIN) {
      await pushPatient(ref.id, x.uid, 'No ambulance accepted', 'কোনো অ্যাম্বুলেন্স রাজি হয়নি',
        'Please call one directly from the list.', 'তালিকা থেকে সরাসরি ফোন করুন।').catch(() => {});
    }
    return (await ref.get()).data();
  }
  if (x.status === 'open' && (x.wave || 1) === 1) {
    const owners = Object.keys(x.sentTo || {});
    const allDeclined = owners.length > 0 && owners.every((o) => (x.declined || []).includes(o));
    if (now - openedAt(x) >= WIDEN_MS || allDeclined) {
      // কোয়েরি transaction-এর বাইরে; লেখা শুধু wave এখনো ১ থাকলে — তাই বারবার ডাকলেও একবারই
      // ফোন করা গাড়ি বাদ — তাদের কাছে অনুরোধ আগেই আছে (attachCall)
      const list = candidates(await districtAmbulances(x.district), x, [], now);
      const called = x.calledIds || [];
      const next = list.filter((c) => !owners.includes(c.owner) && !called.includes(c.id)).slice(0, WAVE);
      const w = waveFields(next);
      const won = await db.runTransaction(async (t) => {
        const c = await t.get(ref);
        if (c.get('status') !== 'open' || (c.get('wave') || 1) !== 1) return false;
        const declined = c.get('declined') || [];
        // ফোন হওয়ার পর অন্য সার্ভার এর মাঝে যোগ করে থাকলে সেগুলোও বাদ
        const nowSent = c.get('sentTo') || {};
        const fresh = next.filter((n) => !nowSent[n.owner] && !(c.get('calledIds') || []).includes(n.id));
        Object.keys(w.sentTo).forEach((o) => { if (!fresh.some((n) => n.owner === o)) { delete w.sentTo[o]; delete w.km[o]; } });
        next.splice(0, next.length, ...fresh);
        t.update(ref, {
          wave: 2, widenedAt: now,
          sentTo: { ...(c.get('sentTo') || {}), ...w.sentTo },
          km: { ...(c.get('km') || {}), ...w.km },
          activeOwners: [...new Set([...(c.get('activeOwners') || []), ...next.map((n) => n.owner)])].filter((o) => !declined.includes(o)),
          fallback: fallbackOf(list),
        });
        return true;
      });
      if (won) await pushDrivers(ref.id, { km: w.km }, next.map((n) => n.owner)).catch((e) => console.error('amb req widen push', e.message));
      return (await ref.get()).data();
    }
  }
  if (['accepted', 'arrived'].includes(x.status) && now - (x.acceptedAt || x.createdAt) >= ACCEPTED_MAX_MS) {
    await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      if (!['accepted', 'arrived'].includes(c.get('status'))) return;
      const ambRef = db.doc(`ambulances/${c.get('ambulanceId')}`);
      const amb = c.get('ambulanceId') ? await t.get(ambRef) : null;
      t.update(ref, closeFields(now, 'done', { closedBy: 'auto' }));
      freeVehicle(t, ambRef, amb, now);
    });
    return (await ref.get()).data();
  }
  return x;
}

/** রোগীর দিকের দেখা */
function patientView(id, x, now = Date.now()) {
  const accepted = ['accepted', 'arrived', 'done', 'byPhone'].includes(x.status) || (x.status === 'cancelled' && x.ambulanceId);
  const showFallback = (x.wave || 1) > 1 || ['expired', 'cancelled'].includes(x.status);
  return {
    id,
    status: x.status,
    createdAt: x.createdAt,
    widenAt: openedAt(x) + WIDEN_MS,
    expiresAt: openedAt(x) + EXPIRE_MS,
    wave: x.wave || 1,
    notified: Object.keys(x.sentTo || {}).length,
    type: x.type || null,
    pickup: x.pickup || '',
    note: x.note || '',
    district: x.district,
    upazilaId: x.upazilaId || null,
    unionId: x.unionId || null,
    gps: x.gps === true,
    ...(accepted ? {
      ambulance: {
        id: x.ambulanceId, name: x.ambulanceName || '', phone: x.driverPhone || '', ...kmOf(x.km?.[x.acceptedBy || x.bookedOwner]),
      },
    } : {}),
    acceptedAt: x.acceptedAt || 0,
    arrivedAt: x.arrivedAt || 0,
    closedAt: x.closedAt || 0,
    cancelledBy: x.cancelledBy || null,
    cancelReason: x.cancelReason || null,
    cancelText: x.cancelText || '',
    fallback: showFallback ? x.fallback || [] : [],
    // অপেক্ষার সময় যেসব গাড়িতে ফোন করেছেন ("ফোনে কোনো গাড়ি পেয়েছেন?" — এখান থেকে বাছা)
    called: (x.calledIds || []).map((cid) => ({ id: cid, ...(x.calledInfo?.[cid] || { name: '', phone: '' }) })),
    // শেষ হলে রেটিং (চালু রেটিংয়ের নিয়মেই, ৭ দিনের মধ্যে)
    canRate: x.status === 'done' && x.closedBy !== 'auto' && now - (x.closedAt || 0) <= 7 * 24 * HOUR,
  };
}

/** চালকের দিকের দেখা: রাজি হওয়ার আগে ঠিক জায়গা, ফোন, নাম নয় */
function driverView(id, x, uid) {
  const mine = x.acceptedBy === uid;
  return {
    id,
    status: mine ? x.status : 'open',
    createdAt: x.createdAt,
    expiresAt: openedAt(x) + EXPIRE_MS,
    // রোগী এই চালকের গাড়িতে ফোন করেছেন — "রোগী আপনাকে ফোন করেছেন"
    called: (x.calledIds || []).includes(mine ? x.ambulanceId : x.sentTo?.[uid]),
    ambulanceId: mine ? x.ambulanceId : x.sentTo?.[uid] || null,
    ...kmOf(x.km?.[uid]),
    type: x.type || null,
    district: x.district,
    upazilaId: x.upazilaId || null,
    unionId: x.unionId || null,
    pickup: x.pickup || '',
    note: x.note || '',
    ...(mine ? {
      patientName: x.patientName || '',
      phone: x.phone || '',
      ...(typeof x.lat === 'number' ? { lat: x.lat, lng: x.lng } : {}),
      acceptedAt: x.acceptedAt || 0,
      arrivedAt: x.arrivedAt || 0,
    } : {}),
  };
}

/** রেটিংয়ের যোগ্যতা: শেষ হওয়া অনুরোধ = ফোনের মতোই ambulanceCalls-এ, trip সহ (১ ঘণ্টা অপেক্ষা লাগে না) */
async function recordTrip(uid, ambulanceId, now) {
  const callRef = db.doc(`ambulanceCalls/${uid}_${ambulanceId}`);
  const prev = await callRef.get();
  await callRef.set({ uid, ambulanceId, at: now, tripAt: now, ...(prev.exists ? {} : { firstAt: now }) }, { merge: true });
  if (!prev.exists || now - (prev.get('at') || 0) > 10 * MIN) await require('./sellerstats').bumpAmbulance(ambulanceId, now, !prev.exists).catch(() => {});
}

async function loadMine(req, id, field, uid) {
  if (!R.isValidId(id)) throw err(req, 404, 'Not found', 'পাওয়া যায়নি');
  const ref = db.doc(`ambulanceRequests/${id}`);
  const s = await ref.get();
  if (!s.exists || (field === 'uid' ? s.get('uid') !== uid : !Object.prototype.hasOwnProperty.call(s.get('sentTo') || {}, uid))) {
    throw err(req, 404, 'Not found', 'পাওয়া যায়নি');
  }
  return { ref, s };
}

const actions = {
  /**
   * "অ্যাম্বুলেন্স ডাকুন"। body: district, upazilaId, lat/lng + gps:true (ফোনের আসল GPS, না থাকলে এলাকা ধরে),
   * type (null = ফ্রিজার ছাড়া যেকোনো), pickup (ঠিকানা/চেনা জায়গা), note, phone, ambulanceId (শিট থেকে — সেটা আগে)
   */
  async ambreq_create(req, user) {
    const b = req.body || {};
    const now = Date.now();
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 400, 'Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    // ইউনিয়ন/এলাকা (২.২৯.০): শুধু চালকের জানার জন্য (ঠিকানার সাথে) — বাছাই উপজেলা ধরে
    const unionId = upazilaId && Number.isInteger(b.unionId) && UNION.get(b.unionId)?.upazilaId === upazilaId ? b.unionId : null;
    const type = require('./catalog').codes('ambulanceType').includes(b.type) ? b.type : null;
    const phone = K.normalizeBd(b.phone);
    if (!phone) throw err(req, 400, 'Enter a mobile number the driver can call', 'চালক ফোন করতে পারেন এমন মোবাইল নম্বর দিন');
    const pickup = K.str(b.pickup, 160);
    if (!pickup) throw err(req, 400, 'Write where to pick up (address or landmark)', 'কোথা থেকে নেবে লিখুন (ঠিকানা বা চেনা জায়গা)');
    const note = K.str(b.note, 200);
    let p = b.gps === true ? exactPoint(b.lat, b.lng) : null;
    // বেছে নেওয়া জেলা থেকে অনেক দূরের GPS (ভুল জেলা) — এলাকা ধরে
    const dc = DISTRICTS.find((d) => d.bn === b.district);
    if (p && geo.distanceKm(p.lat, p.lng, dc.lat, dc.lng) > MAX_KM) p = null;
    const r = { uid: user.uid, gps: !!p, ...(p || {}), upazilaId, type, district: b.district };

    const list = candidates(await districtAmbulances(b.district), r, [], now);
    // শিট থেকে একটা নির্দিষ্ট গাড়ি: সেটা আগে (খালি না জানালেও — রোগী নিজে বেছেছেন; ট্রিপে থাকলে নয়)
    let first = R.isValidId(b.ambulanceId) ? list.find((c) => c.id === b.ambulanceId) : null;
    if (!first && R.isValidId(b.ambulanceId)) {
      const a = await db.doc(`ambulances/${b.ambulanceId}`).get();
      if (a.exists && a.get('district') === b.district) {
        const one = candidates([a], r, [], now);
        // একই মালিকের অন্য গাড়ি তালিকায় থাকলে সেটার জায়গায় এটা
        if (one.length) first = one[0];
      }
    }
    const wave1 = [...(first ? [first] : []), ...list.filter((c) => !first || c.owner !== first.owner)].slice(0, WAVE);
    if (!wave1.length) {
      throw err(req, 409, 'No free ambulance nearby right now — please call one from the list', 'এখন কাছে কোনো খালি অ্যাম্বুলেন্স নেই — তালিকা থেকে ফোন করুন', 'no_ambulance');
    }
    const w = waveFields(wave1);
    const prof = await db.doc(`users/${user.uid}`).get();
    const ref = db.collection('ambulanceRequests').doc();
    const limRef = db.doc(`ambReqLimits/${user.uid}`);
    // একসাথে একটাই চালু অনুরোধ, ঘণ্টায় ৫টা — transaction-এ, যাতে দুই চাপে দুটো না হয়
    await db.runTransaction(async (t) => {
      const lim = await t.get(limRef);
      const times = (lim.exists ? lim.get('times') || [] : []).filter((x) => now - x < HOUR);
      const activeId = lim.exists ? lim.get('activeId') : null;
      if (activeId) {
        const aref = db.doc(`ambulanceRequests/${activeId}`);
        const a = await t.get(aref);
        const st = a.exists ? a.get('status') : null;
        const stale = st === 'open' && now - openedAt(a.data()) >= EXPIRE_MS;
        if (ACTIVE.includes(st) && !stale) {
          throw err(req, 409, 'You already have an ambulance request running', 'আপনার একটা অ্যাম্বুলেন্সের অনুরোধ এখনো চালু আছে', 'amb_req_active');
        }
      }
      if (times.length >= PER_HOUR) {
        throw err(req, 429, 'Up to 5 ambulance requests an hour. Please call from the list.', 'ঘণ্টায় সর্বোচ্চ ৫ বার ডাকা যায়। তালিকা থেকে ফোন করুন।');
      }
      t.set(ref, {
        uid: user.uid,
        ...(require('./demo').isDemoUid(user.uid) ? { demo: true } : {}),
        status: 'open', active: true, wave: 1,
        district: b.district, upazilaId, unionId, type,
        gps: !!p, ...(p ? { lat: p.lat, lng: p.lng } : {}),
        pickup, note, phone: K.localBd(phone),
        patientName: String(prof.exists ? prof.get('name') || '' : '').slice(0, 80),
        sentTo: w.sentTo, km: w.km, activeOwners: wave1.map((c) => c.owner), declined: [],
        ...(first ? { chosenId: first.id } : {}),
        createdAt: now,
      });
      t.set(limRef, { times: [...times, now], activeId: ref.id, at: now });
    });
    await pushDrivers(ref.id, { km: w.km }, wave1.map((c) => c.owner)).catch((e) => console.error('amb req push', e.message));
    return { id: ref.id, notified: wave1.length, request: patientView(ref.id, (await ref.get()).data(), now) };
  },

  /** রোগীর অপেক্ষার পাতা: id দিলে সেটা, না দিলে নিজের চালু অনুরোধ (অ্যাপ বন্ধ করে খুললেও ফিরে আসে) */
  async ambreq_status(req, user) {
    const now = Date.now();
    const id = req.body?.id;
    let ref;
    if (id != null) {
      ref = (await loadMine(req, id, 'uid', user.uid)).ref;
    } else {
      const lim = await db.doc(`ambReqLimits/${user.uid}`).get();
      const aid = lim.exists ? lim.get('activeId') : null;
      if (!aid) return { request: null };
      ref = db.doc(`ambulanceRequests/${aid}`);
    }
    const x = await advance(ref, now);
    if (!x || x.uid !== user.uid) return { request: null };
    // id ছাড়া: শুধু চালু (বা এইমাত্র বন্ধ — ১০ মিনিটের মধ্যে, যাতে "সময় শেষ"/"শেষ" দেখা যায়)
    if (id == null && !ACTIVE.includes(x.status) && now - (x.closedAt || 0) > 10 * MIN) return { request: null };
    return { request: patientView(ref.id, x, now) };
  },

  /** রোগী বাতিল (খোলা বা রাজি হওয়ার পরে) — কারণ বেছে */
  async ambreq_cancel(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'uid', user.uid);
    const reason = PATIENT_CANCEL.includes(req.body?.reason) ? req.body.reason : 'other';
    const now = Date.now();
    const out = await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      if (!ACTIVE.includes(c.get('status'))) return null;
      const ambRef = c.get('ambulanceId') ? db.doc(`ambulances/${c.get('ambulanceId')}`) : null;
      const amb = ambRef ? await t.get(ambRef) : null;
      t.update(ref, closeFields(now, 'cancelled', { cancelledBy: 'patient', cancelReason: reason }));
      if (ambRef) freeVehicle(t, ambRef, amb, now);
      return { driver: c.get('acceptedBy') || null };
    });
    if (out && out.driver) {
      await pushDriver(ref.id, out.driver, 'The patient cancelled the ambulance request', 'রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন').catch(() => {});
    }
    return { ok: true };
  },

  /** চালকের "ডাক": তাঁর কাছে পাঠানো খোলা অনুরোধ আর নিজের নেওয়াটা (সময় পেরোলে এখান থেকেও এগোয়) */
  async ambreq_driver(req, user) {
    const now = Date.now();
    const snap = await db.collection('ambulanceRequests').where('activeOwners', 'array-contains', user.uid).limit(30).get();
    const rows = await Promise.all(snap.docs.map((d) => advance(d.ref, now).then((x) => [d.id, x])));
    const items = rows
      .filter(([, x]) => x && ACTIVE.includes(x.status) && (x.activeOwners || []).includes(user.uid) && !(x.declined || []).includes(user.uid)
        && (x.status === 'open' || x.acceptedBy === user.uid) && sameWorld(user.uid, x.uid))
      .map(([id, x]) => driverView(id, x, user.uid))
      .sort((a, b) => (a.status === 'open') - (b.status === 'open') || b.createdAt - a.createdAt);
    return { items };
  },

  /** "আসছি" — আগে যিনি চাপেন তিনিই পান */
  async ambreq_accept(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'owner', user.uid);
    const now = Date.now();
    // সময় পেরোনো হলে আগে বন্ধ
    await advance(ref, now);
    const res = await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      const x = c.data();
      if (x.status !== 'open') {
        if (x.acceptedBy === user.uid) return { already: true };
        const [en, bn] = {
          expired: ['This request has expired', 'এই অনুরোধের সময় শেষ'],
          cancelled: ['The patient cancelled this request', 'রোগী অনুরোধটা বাতিল করেছেন'],
          byPhone: ['The patient already got an ambulance by phone — no longer needed', 'রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন — আর দরকার নেই'],
        }[x.status] || ['Another driver already took this request', 'অন্য একজন চালক আগেই নিয়ে নিয়েছেন'];
        throw err(req, 409, en, bn, 'taken');
      }
      if ((x.declined || []).includes(user.uid) || !(x.activeOwners || []).includes(user.uid)) throw err(req, 404, 'Not found', 'পাওয়া যায়নি');
      // advance-এর পরে আর এই transaction-এর আগে সময় পেরোলে (রিভিউ): রাজি নয়
      if (now - openedAt(x) >= EXPIRE_MS) throw err(req, 409, 'This request has expired', 'এই অনুরোধের সময় শেষ', 'taken');
      const ambulanceId = x.sentTo[user.uid];
      const ambRef = db.doc(`ambulances/${ambulanceId}`);
      const amb = await t.get(ambRef);
      if (!amb.exists || amb.get('ownerUid') !== user.uid || amb.get('status') !== 'approved') {
        throw err(req, 409, 'This ambulance is not listed now', 'এই অ্যাম্বুলেন্স এখন তালিকায় নেই');
      }
      t.update(ref, {
        status: 'accepted', acceptedBy: user.uid, ambulanceId, ambulanceName: amb.get('name') || '', driverPhone: K.fixPhone(amb.get('phone')),
        acceptedAt: now, activeOwners: [user.uid],
      });
      // ট্রিপে (তালিকায় "ব্যস্ত"); শেষ/বাতিলে আবার খালি — reqBusy দেখে বোঝা যায় এটা অনুরোধের জন্য
      t.update(ambRef, { available: false, availableAt: now, busyAt: now, updatedAt: now, reqBusy: ref.id, remindAt: FieldValue.delete() });
      return { patient: x.uid, name: amb.get('name') || '' };
    });
    if (res.patient) {
      await pushPatient(ref.id, res.patient, `${res.name} is coming`, `${res.name} আসছে`,
        'Open to see the driver\'s phone.', 'চালকের ফোন নম্বর দেখতে খুলুন।').catch(() => {});
    }
    return { ok: true, request: driverView(ref.id, (await ref.get()).data(), user.uid) };
  },

  /** "পারব না" — তাঁর তালিকা থেকে সরে; সবাই বললে আগেই পরিধি বাড়ে */
  async ambreq_decline(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'owner', user.uid);
    await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      if (c.get('status') !== 'open') return;
      t.update(ref, { declined: FieldValue.arrayUnion(user.uid), activeOwners: FieldValue.arrayRemove(user.uid) });
    });
    await advance(ref, Date.now()).catch(() => {});
    return { ok: true };
  },

  /** চালক: "পৌঁছেছি" / "শেষ" / "বাতিল" (কারণসহ) — body.step */
  async ambreq_step(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'owner', user.uid);
    const step = req.body?.step;
    if (!['arrived', 'done', 'cancel'].includes(step)) throw err(req, 400, 'Not found', 'পাওয়া যায়নি');
    const reason = DRIVER_CANCEL.includes(req.body?.reason) ? req.body.reason : 'other';
    const now = Date.now();
    const out = await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      const x = c.data();
      if (x.acceptedBy !== user.uid || !['accepted', 'arrived'].includes(x.status)) {
        throw err(req, 409, 'This request is already closed', 'এই অনুরোধ আগেই বন্ধ হয়েছে');
      }
      if (step === 'arrived') {
        if (x.status === 'arrived') return null;
        t.update(ref, { status: 'arrived', arrivedAt: now });
        return { x, step };
      }
      const ambRef = db.doc(`ambulances/${x.ambulanceId}`);
      const amb = await t.get(ambRef);
      t.update(ref, step === 'done'
        ? closeFields(now, 'done', { doneAt: now })
        : closeFields(now, 'cancelled', { cancelledBy: 'driver', cancelReason: reason }));
      freeVehicle(t, ambRef, amb, now);
      return { x, step };
    });
    if (!out) return { ok: true };
    const { x } = out;
    if (step === 'done') await recordTrip(x.uid, x.ambulanceId, now);
    const name = x.ambulanceName || '';
    if (step === 'arrived') await pushPatient(ref.id, x.uid, `${name} has arrived`, `${name} পৌঁছেছে`).catch(() => {});
    else if (step === 'done') await pushPatient(ref.id, x.uid, 'Trip finished — rate the ambulance', 'ট্রিপ শেষ — অ্যাম্বুলেন্সকে রেটিং দিন').catch(() => {});
    else {
      await pushPatient(ref.id, x.uid, `${name} cancelled`, `${name} বাতিল করেছে`,
        'Please call another ambulance from the list.', 'তালিকা থেকে অন্য অ্যাম্বুলেন্সে ফোন করুন।').catch(() => {});
    }
    return { ok: true };
  },
  /**
   * "ফোনে কোনো গাড়ি পেয়েছেন?" → এই গাড়ি (রোগী এই অনুরোধের সময় যেটায় ফোন করেছেন)। অনুরোধ byPhone হয়ে বন্ধ,
   * বাকি চালকদের তালিকা থেকে সরে, পুশ "আর দরকার নেই"। খোলা অবস্থায়; রাজি হওয়া চালক থাকলে ambreq_replace দিয়ে।
   */
  async ambreq_byphone(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'uid', user.uid);
    return byPhone(req, ref, user.uid, req.body?.ambulanceId, false);
  },

  /**
   * একজন রাজি হয়েছেন, কিন্তু রোগী ফোনে অন্য গাড়ি পেয়েছেন: সেই চালককে বাতিল (patient_found_other, ভদ্র পুশ)।
   * ambulanceId দিলে (ফোন করা গাড়ি) byPhone হয়ে বন্ধ; না দিলে অনুরোধ আবার খোলা (সময় নতুন করে, ওই চালক বাদ)।
   */
  async ambreq_replace(req, user) {
    const { ref } = await loadMine(req, req.body?.id, 'uid', user.uid);
    const target = req.body?.ambulanceId;
    if (target != null) return byPhone(req, ref, user.uid, target, true);
    const now = Date.now();
    const out = await db.runTransaction(async (t) => {
      const c = await t.get(ref);
      const x = c.data();
      if (!['accepted', 'arrived'].includes(x.status)) throw err(req, 409, 'Nothing to change now', 'এখন বদলানোর কিছু নেই');
      const ambRef = db.doc(`ambulances/${x.ambulanceId}`);
      const amb = await t.get(ambRef);
      const declined = [...new Set([...(x.declined || []), x.acceptedBy])];
      const owners = Object.keys(x.sentTo || {}).filter((o) => !declined.includes(o));
      t.update(ref, {
        status: 'open', openedAt: now, active: true, activeOwners: owners, declined,
        acceptedBy: FieldValue.delete(), ambulanceId: FieldValue.delete(), ambulanceName: FieldValue.delete(), driverPhone: FieldValue.delete(),
        acceptedAt: FieldValue.delete(), arrivedAt: FieldValue.delete(),
        replaced: FieldValue.arrayUnion({ uid: x.acceptedBy, ambulanceId: x.ambulanceId, at: now, reason: 'patient_found_other' }),
      });
      freeVehicle(t, ambRef, amb, now);
      return { driver: x.acceptedBy, owners, km: x.km || {} };
    });
    await politeCancel(ref.id, out.driver);
    await pushDrivers(ref.id, { km: out.km }, out.owners).catch(() => {});
    return { ok: true, request: patientView(ref.id, (await ref.get()).data(), now) };
  },
};

/** চালককে ভদ্রভাবে জানানো: রোগী ফোনে অন্য গাড়ি পেয়েছেন */
function politeCancel(id, driver) {
  return pushDriver(id, driver, 'Sorry — the patient found another ambulance by phone. Thank you for accepting!',
    'দুঃখিত — রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন। রাজি হওয়ার জন্য ধন্যবাদ!').catch(() => {});
}

/**
 * byPhone বন্ধ: ambulanceId অবশ্যই এই অনুরোধে ফোন করা গাড়ি। replacing = রাজি হওয়া চালককে সরিয়ে (নিয়ম ৫), নইলে শুধু খোলা থেকে।
 * সব transaction-এ: status দেখে — এক অনুরোধে এক সময়ে একটাই রাজি গাড়ি (নিয়ম ৬)।
 */
async function byPhone(req, ref, uid, ambulanceId, replacing) {
  const now = Date.now();
  if (!R.isValidId(ambulanceId)) throw err(req, 400, 'Choose the ambulance you got', 'যে গাড়ি পেয়েছেন সেটা বেছে নিন');
  const out = await db.runTransaction(async (t) => {
    const c = await t.get(ref);
    const x = c.data();
    if (!(x.calledIds || []).includes(ambulanceId)) throw err(req, 400, 'Choose an ambulance you called', 'যে গাড়িতে ফোন করেছেন সেটা বেছে নিন');
    const ok = replacing ? ['accepted', 'arrived'].includes(x.status) : x.status === 'open';
    if (!ok) {
      // একই গাড়ি অ্যাপেই রাজি হয়ে থাকলে আলাদা কিছু করার নেই
      if (['accepted', 'arrived'].includes(x.status) && x.ambulanceId === ambulanceId) return { same: true };
      throw err(req, 409, x.status === 'open' ? 'Nobody has accepted yet' : 'This request is already closed',
        x.status === 'open' ? 'এখনো কেউ রাজি হননি' : 'এই অনুরোধ আগেই বন্ধ হয়েছে', 'state');
    }
    if (replacing && x.ambulanceId === ambulanceId) return { same: true };
    const a = await t.get(db.doc(`ambulances/${ambulanceId}`));
    let accRef = null;
    let acc = null;
    if (replacing) {
      accRef = db.doc(`ambulances/${x.ambulanceId}`);
      acc = await t.get(accRef);
    }
    const info = x.calledInfo?.[ambulanceId] || {};
    t.update(ref, closeFields(now, 'byPhone', {
      byPhoneId: ambulanceId,
      ...(replacing ? {
        replaced: FieldValue.arrayUnion({ uid: x.acceptedBy, ambulanceId: x.ambulanceId, at: now, reason: 'patient_found_other' }),
        acceptedBy: FieldValue.delete(),
      } : {}),
      ambulanceId, ambulanceName: (a.exists && a.get('name')) || info.name || '', driverPhone: (a.exists && K.fixPhone(a.get('phone'))) || info.phone || '',
      bookedOwner: a.exists ? a.get('ownerUid') || null : null,
    }));
    if (replacing) freeVehicle(t, accRef, acc, now);
    const others = (replacing ? [] : x.activeOwners || []).filter((o) => !a.exists || o !== a.get('ownerUid'));
    return { others, driver: replacing ? x.acceptedBy : null };
  });
  if (out.same) return { ok: true, request: patientView(ref.id, (await ref.get()).data(), now) };
  // রেটিংয়ের যোগ্যতা: ফোনের রেকর্ড (ফোনের মতোই ১ ঘণ্টা পর), এই অনুরোধের সাথে যুক্ত
  await db.doc(`ambulanceCalls/${uid}_${ambulanceId}`).set({ uid, ambulanceId, at: now, byPhoneReq: ref.id }, { merge: true }).catch(() => {});
  if (out.driver) await politeCancel(ref.id, out.driver);
  await pushNoLonger(ref.id, out.others);
  return { ok: true, request: patientView(ref.id, (await ref.get()).data(), now) };
}

/** বাকি চালকদের: "রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই" */
async function pushNoLonger(id, owners) {
  if (!owners.length) return;
  const { sendPush, tokensOf } = require('./firebase');
  const users = (await db.getAll(...owners.map((u) => db.doc(`users/${u}`)))).filter((u) => u.exists);
  await sendPush(users.map((u) => ({
    uid: u.id, tokens: tokensOf(u),
    title: R.L(u.get('lang'), 'The patient got an ambulance — no longer needed', 'রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই'), body: '',
    data: { type: 'services', kind: 'ambulance', sub: 'amb_req', reqId: id },
  }))).catch(() => {});
}

/**
 * রোগী চালু অনুরোধের সময় কোনো গাড়িতে ফোন করলেন (ambulance_called + reqId): calledIds-এ তোলা।
 * অনুরোধ খোলা থাকলে আর সেই মালিকের কাছে আগে না গিয়ে থাকলে (বা "পারব না" বলে থাকলে) তাঁর তালিকায় আনা —
 * "রোগী আপনাকে ফোন করেছেন" চিহ্নসহ, যাতে অ্যাপে "আসছি" চাপতে পারেন। প্রথম ফোনে তাঁকে একটা পুশ।
 */
async function attachCall(uid, reqId, ambulanceId) {
  if (!R.isValidId(reqId) || !R.isValidId(ambulanceId)) return false;
  const ref = db.doc(`ambulanceRequests/${reqId}`);
  const aref = db.doc(`ambulances/${ambulanceId}`);
  const now = Date.now();
  const out = await db.runTransaction(async (t) => {
    const [c, a] = await Promise.all([t.get(ref), t.get(aref)]);
    if (!c.exists || c.get('uid') !== uid || !ACTIVE.includes(c.get('status')) || !a.exists) return null;
    const x = c.data();
    const owner = a.get('ownerUid');
    if (!owner || owner === uid || !sameWorld(uid, owner)) return null;
    const calledIds = x.calledIds || [];
    const first = !calledIds.includes(ambulanceId);
    const upd = {
      calledIds: first ? [...calledIds, ambulanceId] : calledIds,
      calledInfo: { ...(x.calledInfo || {}), [ambulanceId]: { name: a.get('name') || '', phone: K.fixPhone(a.get('phone')) } },
    };
    let added = false;
    if (x.status === 'open' && a.get('status') === 'approved') {
      const sentTo = { ...(x.sentTo || {}) };
      // একই মালিকের অন্য গাড়িতে আগে পাঠানো থাকলে: ফোন করা গাড়িটাই তাঁর গাড়ি ধরা
      if (sentTo[owner] !== ambulanceId) {
        sentTo[owner] = ambulanceId;
        const base = A().baseOf(a.data());
        const km = { ...(x.km || {}) };
        if (x.gps && typeof x.lat === 'number' && base) {
          const real = geo.distanceKm(x.lat, x.lng, base.lat, base.lng);
          km[owner] = base.exact ? wholeKm(real) : -1 - wholeKm(real);
        } else delete km[owner];
        Object.assign(upd, { sentTo, km });
      }
      if (!(x.activeOwners || []).includes(owner) || (x.declined || []).includes(owner)) {
        upd.activeOwners = [...new Set([...(x.activeOwners || []), owner])];
        upd.declined = (x.declined || []).filter((o) => o !== owner);
        added = true;
      }
    }
    t.update(ref, upd);
    return { owner, push: x.status === 'open' && (first || added) };
  });
  if (out && out.push) {
    await pushDriver(reqId, out.owner, 'The patient called you — tap "Coming" in the app', 'রোগী আপনাকে ফোন করেছেন — অ্যাপে "আসছি" চাপুন').catch(() => {});
  }
  return !!out;
}

/**
 * দৈনিক ক্রন: (১) পড়ে থাকা চালু অনুরোধ এগোনো/বন্ধ, (২) বন্ধের ২৪ ঘণ্টা পর ঠিক জায়গা, ফোন, ঠিকানা, নোট, নাম মোছা।
 * purgeAt মোছার সাথে সরে যায়, তাই প্রতিবার প্রথম পাতাই নতুন।
 */
async function dailyCleanup(now = Date.now()) {
  const live = await db.collection('ambulanceRequests').where('active', '==', true).limit(300).get();
  for (const d of live.docs) await advance(d.ref, now).catch(() => {});
  let purged = 0;
  for (let page = 0; page < 20; page++) {
    const s = await db.collection('ambulanceRequests').where('purgeAt', '<=', now).limit(300).get();
    if (s.empty) break;
    const b = db.batch();
    s.docs.forEach((d) => b.update(d.ref, {
      lat: FieldValue.delete(), lng: FieldValue.delete(), phone: FieldValue.delete(), pickup: FieldValue.delete(),
      note: FieldValue.delete(), patientName: FieldValue.delete(), purgeAt: FieldValue.delete(), purgedAt: now,
    }));
    await b.commit();
    purged += s.size;
    if (s.size < 300) break;
  }
  return { advanced: live.size, purged };
}

/**
 * অ্যাকাউন্ট মোছা (বা মোছার অপেক্ষা শুরু): রোগী হিসেবে চালু অনুরোধ বাতিল করে চালককে জানানো; full হলে নিজের সব অনুরোধ মোছা।
 * চালক হিসেবে: নেওয়া অনুরোধ বাতিল করে রোগীকে জানানো, খোলাগুলোর তালিকা থেকে সরানো।
 */
async function closeForUser(uid, { full = false } = {}) {
  const now = Date.now();
  const [mine, driving] = await Promise.all([
    db.collection('ambulanceRequests').where('uid', '==', uid).limit(500).get(),
    db.collection('ambulanceRequests').where('activeOwners', 'array-contains', uid).limit(50).get(),
  ]);
  for (const d of mine.docs) {
    if (ACTIVE.includes(d.get('status'))) {
      await actions.ambreq_cancel({ body: { id: d.id, reason: 'other' } }, { uid }).catch(() => {});
    }
    if (full) await d.ref.delete().catch(() => {});
  }
  for (const d of driving.docs) {
    if (d.get('acceptedBy') === uid) {
      await actions.ambreq_step({ body: { id: d.id, step: 'cancel', reason: 'other' } }, { uid }).catch(() => {});
    } else if (d.get('status') === 'open') {
      await d.ref.update({ activeOwners: FieldValue.arrayRemove(uid) }).catch(() => {});
    }
  }
  if (full) await db.doc(`ambReqLimits/${uid}`).delete().catch(() => {});
  return mine.size;
}

module.exports = {
  actions, candidates, advance, dailyCleanup, closeForUser, patientView, driverView, attachCall,
  WAVE, WIDEN_MS, EXPIRE_MS, PER_HOUR, PURGE_AFTER_MS, FALLBACK, PATIENT_CANCEL, DRIVER_CANCEL,
};
