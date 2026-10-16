// বাসায় সেবা: যে ডাক্তার আর নার্স রোগীর বাসায় যান, তাদের তালিকা (ডাক্তার আর নার্স আলাদা)।
// অ্যাপে কোনো অনুরোধ/গ্রহণ নেই — রোগী তালিকা দেখে সরাসরি ফোন করেন। টাকা অ্যাপে নেই।
// ডাক্তার/নার্স শুধু নিজের হিসাব দেখেন: অ্যাপ থেকে কতবার ফোন এসেছে, রেটিং, মন্তব্য।
//
// providers/{uid}.homeVisit: { enabled, services, radiusKm, upazilaIds, phone, schedule, fee }
// homeCalls/{callerUid}_{providerUid}: { uid, providerUid, at, firstAt, rating?, comment? }
// homeStats/{providerUid}: sellerstats-এর মতো দৈনিক গণনা
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const S = require('./sellerstats');

const TYPES = ['doctor', 'nurse'];
const RATE_WINDOW_MS = 30 * 24 * 3600 * 1000; // ফোন করার ৩০ দিনের মধ্যে রেটিং
// ফোন চেপে সাথে সাথে কেটে রেটিং দেওয়া যায় না: প্রথম ফোনের অন্তত ১ ঘণ্টা পর
const RATE_AFTER_MS = 60 * 60 * 1000;
const CALL_DEDUPE_MS = 10 * 60 * 1000;

function inBangladesh(lat, lng) {
  return typeof lat === 'number' && typeof lng === 'number' && lat > 20.3 && lat < 26.8 && lng > 87.9 && lng < 92.8;
}

/** তালিকায় যা দেখায় (ডাক্তার/নার্সের নিজের লোকেশন নয়, শুধু দূরত্ব) */
function view(d, km, mine) {
  const x = d.data();
  const h = x.homeVisit || {};
  const n = x.ratingCount || 0;
  return {
    uid: d.id,
    type: x.type,
    name: x.name || '',
    specialties: x.specialties || [],
    degrees: x.degrees || '',
    gender: x.gender || '',
    services: h.services || [],
    radiusKm: h.radiusKm || 5,
    upazilaIds: h.upazilaIds || [],
    schedule: h.schedule || '',
    fee: h.fee || '',
    phone: K.fixPhone(h.phone),
    km: km == null ? null : Math.round(km * 10) / 10,
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    // এই মানুষ আগে অ্যাপ থেকে ফোন করেছেন কি না, আর তাঁর দেওয়া রেটিং
    called: !!(mine && mine.exists),
    myRating: (mine && mine.exists && mine.get('rating')) || 0,
    // রেটিং দেওয়া যায় কি না: প্রথম ফোনের ১ ঘণ্টা পর থেকে, শেষ ফোনের ৩০ দিনের মধ্যে
    canRate: !!(mine && mine.exists)
      && Date.now() - (mine.get('firstAt') || mine.get('at') || 0) >= RATE_AFTER_MS
      && Date.now() - (mine.get('at') || 0) <= RATE_WINDOW_MS,
  };
}

const actions = {
  /**
   * তালিকা। type: doctor / nurse; জায়গা: lat+lng (নিজের GPS) অথবা district (+ upazilaId);
   * ফিল্টার: service, female।
   */
  async home_providers(req, user) {
    const b = req.body || {};
    if (!TYPES.includes(b.type)) throw new HttpError(400, C.tr(req, 'Choose doctor or nurse', 'ডাক্তার বা নার্স বেছে নিন'));
    const place = {};
    if (inBangladesh(b.lat, b.lng)) {
      place.lat = b.lat;
      place.lng = b.lng;
    }
    if (typeof b.district === 'string' && DISTRICTS.some((d) => d.bn === b.district)) place.district = b.district;
    if (Number.isInteger(b.upazilaId) && UPAZILA.has(b.upazilaId)) place.upazilaId = b.upazilaId;
    const service = K.homeServicesFor(b.type).includes(b.service) ? b.service : null;

    const snap = await db.collection('providers').where('type', '==', b.type).where('homeVisit.enabled', '==', true).limit(1000).get();
    // আগের ভার্সনে চালু করা মানুষের আলাদা ফোন নম্বর নেই — দেওয়ার আগে তালিকায় নেই
    const docs = snap.docs.filter((d) => d.id !== user.uid && d.get('homeVisitBlocked') !== true && !!d.get('homeVisit.phone')
      && (!service || (d.get('homeVisit.services') || []).includes(service))
      && (b.female !== true || d.get('gender') === 'female'));
    if (!docs.length) return { items: [] };
    const [users, badges] = await Promise.all([
      db.getAll(...docs.map((d) => db.doc(`users/${d.id}`))),
      db.getAll(...docs.map((d) => db.doc(`badges/${d.id}`))),
    ]);
    const found = [];
    docs.forEach((d, i) => {
      // ব্যাজ বাতিল হলে বা ধরন বদলালে তালিকায় নেই
      if (!badges[i].exists || badges[i].get('status') !== 'approved' || badges[i].get('type') !== b.type) return;
      const u = users[i];
      const m = K.homeMatch({
        ...d.data(),
        lat: u.exists ? u.get('lat') : null,
        lng: u.exists ? u.get('lng') : null,
        locationSource: u.exists ? u.get('locationSource') : null,
        placeName: u.exists ? u.get('placeName') : null,
      }, place);
      if (m) found.push({ d, km: m.km });
    });
    // কাছেরটা আগে; দূরত্ব না জানলে বেশি রেটিং পাওয়া আগে
    found.sort((p, q) => (p.km ?? 999) - (q.km ?? 999) || (q.d.get('ratingCount') || 0) - (p.d.get('ratingCount') || 0));
    const top = found.slice(0, 100);
    const mine = top.length ? await db.getAll(...top.map((f) => db.doc(`homeCalls/${user.uid}_${f.d.id}`))) : [];
    return { items: top.map((f, i) => view(f.d, f.km, mine[i])) };
  },

  /** রোগী অ্যাপ থেকে ফোন বাটন চাপলে (ডাক্তার/নার্সের হিসাবের জন্য; ব্যর্থ হলেও ফোন আটকায় না) */
  async home_called(req, user) {
    const { uid } = req.body || {};
    if (!R.isValidId(uid) || uid === user.uid) return { ok: true };
    const p = await db.doc(`providers/${uid}`).get();
    if (!p.exists || !(p.get('homeVisit') && p.get('homeVisit').enabled)) return { ok: true };
    const ref = db.doc(`homeCalls/${user.uid}_${uid}`);
    const prev = await ref.get();
    const now = Date.now();
    await ref.set({ uid: user.uid, providerUid: uid, at: now, ...(prev.exists ? {} : { firstAt: now }) }, { merge: true });
    // একই মানুষ ১০ মিনিটের মধ্যে আবার চাপলে নতুন ফোন ধরা হয় না
    if (!prev.exists || now - (prev.get('at') || 0) > CALL_DEDUPE_MS) await S.bumpCalls('homeStats', uid, now, !prev.exists);
    return { ok: true };
  },

  /** অ্যাপ থেকে ফোন করার পর রেটিং (একজন একবার; পরে বদলানো যায়) */
  async home_rate(req, user) {
    const { uid } = req.body || {};
    const rating = Number.isInteger(req.body?.rating) ? req.body.rating : 0;
    if (!R.isValidId(uid) || rating < 1 || rating > 5) throw new HttpError(400, C.tr(req, 'Choose 1 to 5 stars', '১ থেকে ৫ তারা দিন'));
    const comment = K.str(req.body?.comment, 300);
    const callRef = db.doc(`homeCalls/${user.uid}_${uid}`);
    const ref = db.doc(`providers/${uid}`);
    await db.runTransaction(async (t) => {
      const [c, p] = await Promise.all([t.get(callRef), t.get(ref)]);
      if (!p.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      if (!c.exists || Date.now() - (c.get('at') || 0) > RATE_WINDOW_MS) {
        throw new HttpError(403, C.tr(req, 'You can rate after calling from the app', 'অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন'));
      }
      if (Date.now() - (c.get('firstAt') || c.get('at') || 0) < RATE_AFTER_MS) {
        throw new HttpError(403, C.tr(req, 'You can rate 1 hour after your first call', 'প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন'));
      }
      const old = c.get('rating') || 0;
      t.update(ref, { ratingSum: FieldValue.increment(rating - old), ratingCount: FieldValue.increment(old ? 0 : 1) });
      t.update(callRef, { rating, comment, ratedAt: Date.now() });
    });
    await S.addRecentRating('homeStats', uid, rating, comment, user.uid);
    return { ok: true };
  },

  /** ডাক্তার/নার্সের নিজের হিসাব */
  async home_stats(req, user) {
    const [p, st, reports] = await Promise.all([
      db.doc(`providers/${user.uid}`).get(),
      db.doc(`homeStats/${user.uid}`).get(),
      require('./moderation').reportCount(`providers/${user.uid}`).then((n) => ({ data: () => ({ count: n }) })),
    ]);
    const n = (p.exists && p.get('ratingCount')) || 0;
    return {
      enabled: !!(p.exists && p.get('homeVisit') && p.get('homeVisit').enabled),
      blocked: !!(p.exists && p.get('homeVisitBlocked') === true),
      ...S.callSummary(st),
      rating: n ? Math.round((p.get('ratingSum') / n) * 10) / 10 : 0,
      ratingCount: n,
      // কে দিয়েছেন তা দেখানো হয় না (রোগীর গোপনীয়তা)
      comments: ((st.exists && st.get('recent')) || []).slice().reverse().map(({ who, ...c }) => c),
      reports: reports.data().count,
    };
  },
};

/** রিপোর্টের পর অ্যাডমিন বাদ দিলে: বাসায় সেবা বন্ধ, নিজে আবার চালু করতে পারেন না (আপিল করা যায়) */
async function blockHomeCare(uid) {
  if (!R.isValidId(uid)) return;
  await db.doc(`providers/${uid}`).set({ homeVisit: { enabled: false }, homeVisitBlocked: true }, { merge: true });
}

module.exports = { actions, blockHomeCare, RATE_WINDOW_MS };
