// অ্যাপ আর ওয়েব আগে যা সরাসরি Firestore থেকে পড়ত/লিখত, তা এখন এখান দিয়ে (Contabo-তে সরানোর ধাপ ৩, ১৯ অক্টোবর ২০২৬)।
// firestore.rules-এর প্রতিটা নিয়ম এখানে সার্ভারে একই রকম (কে কী দেখতে/বদলাতে পারে):
//   users/{uid}                 — শুধু নিজে পড়া ও বদলানো; শেষ রক্তদানের তারিখ শুধু সামনে এগোয়; মোছা নয়।
//                                 আগে নিয়মে কোনো ঘরের তালিকা ছিল না — এখন শুধু অ্যাপ যেসব ঘর লেখে সেগুলো (ব্যাজ, পরিবার, টোকেন… নয়)
//   requests/{id}               — লগইন করা সবাই পড়তে পারে (ডেমো/আসল আলাদা); তৈরি-বন্ধ আগের মতোই অন্য পথে
//   requests/{id}/private       — রক্তপ্রার্থী নিজে, আর খোলা রিকোয়েস্টে রাজি থাকা বা রক্ত দেওয়া ডোনার
//   requests/{id}/responses/{d} — ডোনার নিজেরটা; রক্তপ্রার্থী শুধু রাজি/রক্ত দেওয়াদের
//   reports/{requestId}_{uid}   — নিজের নামে একবারই, শুধু তিনটা ঘর; কেউ পড়তে পারে না
// অতিথি (লগইন ছাড়া) এগুলোর কোনোটাই পারেন না — তাঁদের তালিকা guest.js-এর guest_requests (নাম-ফোন-নোট ছাড়া)।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const geo = require('./geo');
// ডেমো ফোল্ডার মুছলেও চলে (অন্য জায়গার মতো দরকারের সময় পড়া)
const sameWorld = (a, b) => require('./demo').sameWorld(a, b);

const err = (req, status, en, bn) => new HttpError(status, C.tr(req, en, bn));
const notFound = (req) => err(req, 404, 'Not found', 'পাওয়া যায়নি');
const needLogin = (req, user) => {
  if (!user || !user.uid) throw err(req, 401, 'Please log in', 'লগইন দরকার');
};

/** সংখ্যা, Firestore Timestamp বা Date → মিলিসেকেন্ড; না পারলে null */
function toMs(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (v && typeof v.toMillis === 'function') return v.toMillis();
  if (v instanceof Date) return v.getTime();
  if (v && typeof v._seconds === 'number') return v._seconds * 1000 + Math.round((v._nanoseconds || 0) / 1e6);
  return null;
}
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const str = (v) => (typeof v === 'string' ? v : '');

/** requests/{id} → অ্যাপের BloodRequest-এর ঘরগুলো (Timestamp মিলিসেকেন্ডে) */
function requestOut(id, x) {
  return {
    id,
    requesterUid: str(x.requesterUid),
    requesterName: str(x.requesterName),
    patientGroup: str(x.patientGroup),
    bags: num(x.bags) ?? 1,
    hospital: str(x.hospital),
    placeName: str(x.placeName),
    lat: num(x.lat) ?? 0,
    lng: num(x.lng) ?? 0,
    neededByMillis: num(x.neededByMillis) ?? 0,
    neededAtMillis: num(x.neededAtMillis),
    urgent: x.urgent === true,
    travelFare: x.travelFare === true,
    note: str(x.note),
    status: str(x.status) || 'open',
    createdAtMillis: num(x.createdAtMillis) ?? 0,
    createdAt: toMs(x.createdAt),
    // থাকলেই "জানানো হয়েছে" (অ্যাপ শুধু থাকা-না-থাকা দেখে)
    notifiedAt: x.notifiedAt == null ? null : toMs(x.notifiedAt) ?? 0,
    notifiedCount: num(x.notifiedCount) ?? 0,
    fulfilled: x.fulfilled === true,
    acceptedCount: num(x.acceptedCount) ?? 0,
    bagsReceived: num(x.bagsReceived) ?? 0,
    confirmedCount: num(x.confirmedCount) ?? 0,
    closedAt: toMs(x.closedAt) ?? 0,
    closedBy: str(x.closedBy),
    notifyRounds: num(x.notifyRounds),
    lastNotifiedAt: toMs(x.lastNotifiedAt),
  };
}

function responseOut(id, x) {
  return {
    donorUid: str(x.donorUid) || id,
    donorName: str(x.donorName),
    donorPhone: str(x.donorPhone),
    donorGroup: str(x.donorGroup),
    status: str(x.status) || 'accepted',
    confirmed: x.confirmed === true,
    distanceKm: num(x.distanceKm),
    fareAfterKm: num(x.fareAfterKm),
    createdAtMillis: num(x.createdAtMillis) ?? 0,
  };
}

/** নিজের প্রোফাইলের যেসব ঘর অ্যাপ/ওয়েব দেখায় (পুরো ডক নয় — পরিবার, টোকেন, সার্ভারের হিসাব যায় না) */
function profileOut(x) {
  return {
    name: str(x.name),
    phone: str(x.phone),
    bloodGroup: str(x.bloodGroup),
    lastDonationMillis: num(x.lastDonationMillis),
    birthDateMillis: num(x.birthDateMillis),
    weightKg: num(x.weightKg),
    lat: num(x.lat),
    lng: num(x.lng),
    placeName: typeof x.placeName === 'string' ? x.placeName : null,
    locationSource: typeof x.locationSource === 'string' ? x.locationSource : null,
    notify: x.notify !== false,
    maxPushPerDay: num(x.maxPushPerDay),
    notifyGroups: Array.isArray(x.notifyGroups) ? x.notifyGroups : null,
    viewGroups: Array.isArray(x.viewGroups) ? x.viewGroups : null,
    commitments: x.commitments && typeof x.commitments === 'object' && !Array.isArray(x.commitments) ? x.commitments : {},
    photo: typeof x.photo === 'string' && x.photo ? x.photo : null,
    // ছোট ছবি নেই (পুরোনো প্রোফাইল) — অ্যাপ একবার বানিয়ে পাঠায়
    hasPhotoThumb: typeof x.photoThumb === 'string' && !!x.photoThumb,
    maxKm: num(x.maxKm),
    fareAfterKm: num(x.fareAfterKm),
    lang: typeof x.lang === 'string' ? x.lang : null,
  };
}

// ---------- প্রোফাইলের কোন ঘর নিজে বদলানো যায় ----------
const BAD = Symbol('bad');
const DEL = Symbol('del');
const B64 = /^[A-Za-z0-9+/=]+$/;
const groupsOrNull = (v) => (v === null ? null : Array.isArray(v) && v.length <= 8 && v.every((g) => R.BLOOD_GROUPS.includes(g)) ? [...new Set(v)] : BAD);
const intIn = (lo, hi) => (v) => (Number.isInteger(v) && v >= lo && v <= hi ? v : BAD);
const nullOr = (f) => (v) => (v === null ? null : f(v));
const deleteOr = (f) => (v) => (v === null ? DEL : f(v));
const strMax = (max) => (v) => (typeof v === 'string' && v.length <= max ? v : BAD);
const MAX_MILLIS = 4102444800000; // ২১০০ সাল

/** অ্যাপ নিজে যেসব ঘর লেখে (আগে সরাসরি Firestore-এ) — এর বাইরে কিছু নয় */
const SELF_FIELDS = {
  name: strMax(100),
  phone: strMax(30),
  bloodGroup: (v) => (v === '' || R.BLOOD_GROUPS.includes(v) ? v : BAD),
  birthDateMillis: nullOr(intIn(-MAX_MILLIS, MAX_MILLIS)),
  weightKg: nullOr((v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 500 ? v : BAD)),
  // শুধু সামনে এগোনো যায় (নিচে আলাদা যাচাই)
  lastDonationMillis: nullOr(intIn(0, MAX_MILLIS)),
  notify: (v) => (typeof v === 'boolean' ? v : BAD),
  maxPushPerDay: (v) => (Number.isInteger(v) ? Math.min(Math.max(v, 1), R.MAX_PUSH_PER_DAY_LIMIT) : BAD),
  notifyGroups: groupsOrNull,
  viewGroups: groupsOrNull,
  maxKm: (v) => (R.RADIUS_CHOICES.includes(v) ? v : BAD),
  fareAfterKm: deleteOr(intIn(0, 1000)),
  lang: (v) => (v === 'en' || v === 'bn' ? v : BAD),
  photo: deleteOr((v) => (typeof v === 'string' && v.length <= 600 * 1024 && B64.test(v) ? v : BAD)),
  photoThumb: deleteOr((v) => (typeof v === 'string' && v.length <= 100 * 1024 && B64.test(v) ? v : BAD)),
  // লোকেশন: lat আর lng একসাথে; geohash, donorGeo, সময় সার্ভার বসায়
  lat: (v) => (typeof v === 'number' && Number.isFinite(v) && v >= -90 && v <= 90 ? Math.round(v * 100) / 100 : BAD),
  lng: (v) => (typeof v === 'number' && Number.isFinite(v) && v >= -180 && v <= 180 ? Math.round(v * 100) / 100 : BAD),
  placeName: strMax(120),
  locationSource: strMax(20),
};
// এগুলোর কোনোটা বদলালে "updatedAt" (সার্ভারের রোজের geohash ঠিক করার কাজ এটা দেখে) — অ্যাপের আগের মতো
const TOUCH_UPDATED = ['name', 'phone', 'bloodGroup', 'birthDateMillis', 'weightKg'];

const actions = {
  /** নিজের প্রোফাইল (না থাকলে profile: null) */
  async profile_get(req, user) {
    needLogin(req, user);
    const s = await db.doc(`users/${user.uid}`).get();
    return { profile: s.exists ? profileOut(s.data()) : null };
  },

  /**
   * নিজের প্রোফাইল বদল (merge)। body.set: { ঘর: মান } (null = খালি; fareAfterKm/photo/photoThumb-এ null = মোছা),
   * body.commitAdd: requestId (প্রসেসিং তালিকায়), body.commitRemove: [requestId…]
   */
  async profile_update(req, user) {
    needLogin(req, user);
    const b = req.body || {};
    const set = b.set == null ? {} : b.set;
    if (typeof set !== 'object' || Array.isArray(set)) throw err(req, 400, 'Invalid profile data', 'প্রোফাইলের তথ্য ঠিক নেই');
    const out = {};
    for (const [k, v] of Object.entries(set)) {
      if (!Object.prototype.hasOwnProperty.call(SELF_FIELDS, k)) {
        throw err(req, 403, "This can't be changed from the app", 'এটা অ্যাপ থেকে বদলানো যায় না');
      }
      const val = SELF_FIELDS[k](v);
      if (val === BAD) throw err(req, 400, 'Invalid profile data', 'প্রোফাইলের তথ্য ঠিক নেই');
      out[k] = val;
    }
    const hasLat = 'lat' in out;
    if (hasLat !== 'lng' in out) throw err(req, 400, 'Invalid profile data', 'প্রোফাইলের তথ্য ঠিক নেই');
    const add = b.commitAdd == null ? null : b.commitAdd;
    const remove = b.commitRemove == null ? [] : b.commitRemove;
    if ((add !== null && !R.isValidId(add)) || !Array.isArray(remove) || remove.length > 100 || !remove.every(R.isValidId)) {
      throw err(req, 400, 'Not found', 'পাওয়া যায়নি');
    }
    if (!Object.keys(out).length && add === null && !remove.length) return { ok: true, unchanged: true };

    const ref = db.doc(`users/${user.uid}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const cur = s.exists ? s.data() : {};
      // শেষ রক্তদানের তারিখ একবার দিলে শুধু সামনে এগোনো যায় — পেছানো বা মোছা নয় (বদল donation_date_set দিয়ে)
      if ('lastDonationMillis' in out) {
        const before = cur.lastDonationMillis ?? null;
        const after = out.lastDonationMillis;
        if (before != null && (after == null || after < before)) {
          throw err(req, 403, 'Use "Change date" to move your last donation date back', 'শেষ রক্তদানের তারিখ পেছাতে "তারিখ বদলান" ব্যবহার করুন');
        }
      }
      const data = {};
      for (const [k, v] of Object.entries(out)) data[k] = v === DEL ? FieldValue.delete() : v;
      if (hasLat) {
        data.geohash = geo.hash(out.lat, out.lng);
        data.locationUpdatedAtMillis = Date.now();
      }
      if (TOUCH_UPDATED.some((k) => k in out)) data.updatedAt = FieldValue.serverTimestamp();
      // ডোনার খোঁজার ঘর "গ্রুপ|geohash" — গ্রুপ বা লোকেশন বদলালে এখানেই ঠিক
      if ('bloodGroup' in out || hasLat) {
        const key = geo.donorKeyOf({ ...cur, ...out, ...(hasLat ? { geohash: data.geohash } : {}) });
        if ((cur.donorGeo || null) !== key) data.donorGeo = key || FieldValue.delete();
      }
      const commits = {};
      if (add !== null) commits[add] = Date.now();
      remove.filter((id) => id !== add).forEach((id) => { commits[id] = FieldValue.delete(); });
      if (Object.keys(commits).length) data.commitments = commits;
      t.set(ref, data, { merge: true });
    });
    return { ok: true };
  },

  // ---------- রক্তের রিকোয়েস্ট (লগইন করা সবাই পড়তে পারে; ডেমো আর আসল আলাদা) ----------
  async blood_request_get(req, user) {
    needLogin(req, user);
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw notFound(req);
    const s = await db.doc(`requests/${id}`).get();
    if (!s.exists || !sameWorld(user.uid, s.get('requesterUid'))) return { request: null };
    return { request: requestOut(s.id, s.data()) };
  },

  /** নিজের সব রিকোয়েস্ট, নতুনটা আগে */
  async blood_requests_mine(req, user) {
    needLogin(req, user);
    const snap = await db.collection('requests').where('requesterUid', '==', user.uid).limit(1000).get();
    return { items: snap.docs.map((d) => requestOut(d.id, d.data())).sort((a, b) => b.createdAtMillis - a.createdAtMillis) };
  },

  /**
   * আশেপাশের খোলা রিকোয়েস্ট (লগইন করা): geoOpen-এর ঘর ধরে (প্রতি ঘরে সর্বোচ্চ ৫০০), না চললে কাছের জেলাগুলো;
   * চালু, নিজেরগুলো বাদ, দেখার গ্রুপ মিলিয়ে, maxKm-এর মধ্যে; জরুরি আগে, তারপর কাছেরটা।
   * body: lat, lng, maxKm (১–৫০), groups (null = সব)
   */
  async blood_requests_near(req, user) {
    needLogin(req, user);
    const b = req.body || {};
    if (typeof b.lat !== 'number' || typeof b.lng !== 'number' || !Number.isFinite(b.lat) || !Number.isFinite(b.lng)
      || Math.abs(b.lat) > 90 || Math.abs(b.lng) > 180) {
      throw err(req, 400, 'Choose your area', 'এলাকা বেছে নিন');
    }
    const maxKm = Number.isInteger(b.maxKm) ? Math.min(Math.max(b.maxKm, 1), 50) : R.DEFAULT_RADIUS_KM;
    const groups = Array.isArray(b.groups) ? b.groups.filter((g) => R.BLOOD_GROUPS.includes(g)) : null;
    let found;
    try {
      const list = await geo.near(db.collection('requests'), 'geoOpen', b.lat, b.lng, maxKm, { limitPerBound: 500 });
      found = list.map(({ doc }) => doc).filter((d) => d.get('status') === 'open');
    } catch (e) {
      const snap = await db.collection('requests').where('status', '==', 'open').where('placeName', 'in', R.nearDistricts(b.lat, b.lng)).get();
      found = snap.docs;
    }
    const now = Date.now();
    const items = found
      .map((d) => requestOut(d.id, d.data()))
      .filter((r) => R.isActive(r, now) && r.requesterUid !== user.uid && sameWorld(user.uid, r.requesterUid)
        && (!groups || groups.includes(r.patientGroup)))
      .map((r) => ({ ...r, km: geo.distanceKm(b.lat, b.lng, r.lat, r.lng) }))
      .filter((r) => r.km <= maxKm)
      .sort((p, q) => Number(q.urgent) - Number(p.urgent) || p.km - q.km);
    return { items };
  },

  /** নিজের সাড়া (ডোনার নিজেরটাই — অন্যের নয়) */
  async blood_response_get(req, user) {
    needLogin(req, user);
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw notFound(req);
    const s = await db.doc(`requests/${id}/responses/${user.uid}`).get();
    return { response: s.exists ? responseOut(s.id, s.data()) : null };
  },

  /** রাজি থাকা বা রক্ত দেওয়া ডোনার (নাম-ফোনসহ) — শুধু রক্তপ্রার্থী নিজে */
  async blood_responses_list(req, user) {
    needLogin(req, user);
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`requests/${id}`);
    const r = await ref.get();
    if (!r.exists || r.get('requesterUid') !== user.uid) throw err(req, 403, 'This is not your request', 'এটা আপনার রিকোয়েস্ট না');
    const snap = await ref.collection('responses').where('status', 'in', ['accepted', 'donated']).limit(500).get();
    return {
      items: snap.docs.map((d) => responseOut(d.id, d.data()))
        .filter((x) => x.status === 'accepted' || x.status === 'donated')
        .sort((a, b) => a.createdAtMillis - b.createdAtMillis),
    };
  },

  /** রক্তপ্রার্থীর নাম ও ফোন: রক্তপ্রার্থী নিজে, বা খোলা রিকোয়েস্টে এখন রাজি / রক্ত দিয়েছেন এমন ডোনার */
  async blood_contact_get(req, user) {
    needLogin(req, user);
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`requests/${id}`);
    const r = await ref.get();
    const denied = () => err(req, 403, "You can't see this number", 'এই নম্বর দেখার অনুমতি নেই');
    if (!r.exists) throw denied();
    if (r.get('requesterUid') !== user.uid) {
      const mine = await ref.collection('responses').doc(user.uid).get();
      if (r.get('status') !== 'open' || !mine.exists || !['accepted', 'donated'].includes(mine.get('status'))) throw denied();
    }
    const c = await ref.collection('private').doc('contact').get();
    return { contact: c.exists ? { name: str(c.get('name')), phone: str(c.get('phone')) } : null };
  },

  /** রিকোয়েস্ট রিপোর্ট: একজন একটা রিকোয়েস্ট একবারই (reports/{requestId}_{uid}) */
  async report_request(req, user) {
    needLogin(req, user);
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`reports/${id}_${user.uid}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (s.exists) throw err(req, 409, 'Already reported', 'আগেই রিপোর্ট করা হয়েছে');
      t.set(ref, { requestId: id, reporterUid: user.uid, createdAtMillis: Date.now() });
    });
    return { ok: true };
  },
};

module.exports = { actions, requestOut, responseOut, profileOut, SELF_FIELDS };
