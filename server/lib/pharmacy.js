// ফার্মেসির তালিকা: লাইসেন্সধারী দোকান যোগ হয় (অ্যাডমিন যাচাই), মানুষ এলাকার খোলা দোকান খুঁজে সরাসরি ফোন করেন।
// প্রেসক্রিপশন থেকে ওষুধ দেওয়া, দোকানে/অনলাইনে অর্ডার, স্টক — rxnet.js।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');

const ADMIN_STATUSES = ['pending', 'approved', 'suspended', 'rejected'];

// ---------- রেটিং (অ্যাম্বুলেন্স/বাসায় সেবার মতো; পারভেজ, ১৮ অক্টোবর ২০২৬) ----------
// রেটিং দোকানের সেবার — ওষুধ কেমন কাজ করল তার নয় (ওষুধ দোকানদার বানান না)। তাই কয়েকটা দিক, প্রতিটা ১–৫ তারা:
//   behaviour — ব্যবহার (সবাই);
//   price     — ঠিক ওষুধ ও ন্যায্য দাম (প্রেসক্রিপশনে যা লেখা তা-ই দিয়েছে কি না) — শুধু যিনি এই দোকান থেকে কিনেছেন;
//   delivery  — ডেলিভারির দ্রুততা — শুধু যাঁর এই দোকানে অনলাইন অর্ডার "দেওয়া হয়েছে" (done)।
// কে দিতে পারেন: (১) অ্যাপ থেকে এই দোকানে ফোন করেছেন — প্রথম ফোনের ১ ঘণ্টা পর, শেষ ফোনের ৭ দিনের মধ্যে (অ্যাম্বুলেন্সের মতো);
// (২) এই দোকানে পাঠানো প্রেসক্রিপশনের অর্ডার "দেওয়া হয়েছে" (done) হয়েছে, বা (৩) এই দোকান তাঁর প্রেসক্রিপশন থেকে ওষুধ দিয়েছে —
// কেনার ৭ দিনের মধ্যে (সেবা মনে থাকতে থাকতে; ফোনের মতোই)। দোকানের মালিক নিজের দোকানে নয়; ডেমো আর আসল আলাদা।
// একজন একটা দোকানে একটাই রেটিং (পরে বদলানো যায়) —
//   pharmacyRatings/{uid}_{pharmacyId}: { uid, pharmacyId, rating (দিকগুলোর গড়, পূর্ণসংখ্যা), aspects: { behaviour: 4, ... }, comment, ratedAt }
// গড়: pharmacies/{id}.ratingSum / ratingCount (কার্ডের "★ ৪.৭ (৩১)" এখান থেকেই — সব দিকের মিলিত রেটিং);
// দিকভিত্তিক: pharmacies/{id}.aspects.{behaviour|price|delivery}.sum / count; সাম্প্রতিক ১০টা মন্তব্য pharmacyStats/{id}.recent
const RATE_WINDOW_MS = 7 * 24 * 3600 * 1000;
// ফোন চেপে সাথে সাথে কেটে রেটিং দেওয়া যায় না: প্রথম ফোনের অন্তত ১ ঘণ্টা পর
const RATE_AFTER_MS = 60 * 60 * 1000;
const BOUGHT_WINDOW_MS = 7 * 24 * 3600 * 1000;
const ASPECTS = ['behaviour', 'price', 'delivery'];

/** ফোনের রেকর্ড দিয়ে: 'ok' / 'wait' (১ ঘণ্টা হয়নি) / null */
function callBasis(c, now) {
  if (!c || !c.exists || now - (c.get('at') || 0) > RATE_WINDOW_MS) return null;
  return now - (c.get('firstAt') || c.get('at') || 0) >= RATE_AFTER_MS ? 'ok' : 'wait';
}

/**
 * গত ৭ দিনে কোন দোকান থেকে ওষুধ পেয়েছেন (অর্ডার সম্পন্ন বা প্রেসক্রিপশনে দেওয়া) — দোকান → 'shop' / 'online'
 * ('online' = অনলাইন অর্ডার সম্পন্ন — তখন ডেলিভারির রেটিংও)
 */
async function boughtFrom(uid, now = Date.now()) {
  const [orders, rxs] = await Promise.all([
    db.collection('rxOrders').where('patientUid', '==', uid).limit(200).get(),
    db.collection('prescriptions').where('patientUid', '==', uid).limit(200).get(),
  ]);
  const out = new Map();
  orders.docs.forEach((o) => {
    if (o.get('status') !== 'done' || now - (o.get('doneAt') || o.get('updatedAt') || o.get('createdAt') || 0) > BOUGHT_WINDOW_MS) return;
    const id = o.get('pharmacyId');
    if (o.get('mode') === 'online') out.set(id, 'online');
    else if (!out.has(id)) out.set(id, 'shop');
  });
  rxs.docs.forEach((r) => (r.get('dispensed') || []).forEach((d) => {
    if (d && d.by && now - (d.at || 0) <= BOUGHT_WINDOW_MS && !out.has(d.by)) out.set(d.by, 'shop');
  }));
  return out;
}

/** কেনার ধরন থেকে কোন দিকগুলোতে রেটিং দেওয়া যায় */
function aspectsFor(bought) {
  if (bought === 'online') return ASPECTS.slice();
  if (bought === 'shop') return ['behaviour', 'price'];
  return ['behaviour'];
}

/** দোকানের দিকভিত্তিক গড়: { behaviour: { rating: 4.6, count: 10 }, ... } (যেগুলোতে অন্তত একটা রেটিং) */
function aspectView(x) {
  const a = (x && x.aspects) || {};
  const out = {};
  ASPECTS.forEach((k) => {
    const n = (a[k] && a[k].count) || 0;
    if (n > 0) out[k] = { rating: Math.round((a[k].sum / n) * 10) / 10, count: n };
  });
  return out;
}

/** আগের আর নতুন দিকের রেটিংয়ের পার্থক্য — pharmacies/{id}-এর update (aspects.behaviour.sum ইত্যাদি) */
function aspectDelta(oldA, newA) {
  const up = {};
  ASPECTS.forEach((k) => {
    const o = Number.isInteger(oldA && oldA[k]) ? oldA[k] : 0;
    const n = Number.isInteger(newA && newA[k]) ? newA[k] : 0;
    if (n - o) up[`aspects.${k}.sum`] = FieldValue.increment(n - o);
    const dc = (n ? 1 : 0) - (o ? 1 : 0);
    if (dc) up[`aspects.${k}.count`] = FieldValue.increment(dc);
  });
  return up;
}

/** দোকানের মালিক নিজে, বা ডেমো ↔ আসল — রেটিং নয় */
function cannotRate(uid, id, p) {
  return !uid || id === uid || (p && p.get && p.get('ownerUid') === uid) || !require('./demo').sameWorld(uid, id);
}

/** দোকান মুছলে: এই দোকানে দেওয়া সব রেটিং */
async function deleteRatingsOf(pharmacyId) {
  const snap = await db.collection('pharmacyRatings').where('pharmacyId', '==', pharmacyId).limit(2000).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const b = db.batch();
    snap.docs.slice(i, i + 400).forEach((d) => b.delete(d.ref));
    await b.commit();
  }
}

/** অ্যাকাউন্ট মুছলে: নিজের দেওয়া রেটিং গড় থেকে বাদ আর মালিকের "সাম্প্রতিক মন্তব্য" থেকেও (অ্যাম্বুলেন্সের মতো) */
async function removeRatingsBy(uid) {
  const snap = await db.collection('pharmacyRatings').where('uid', '==', uid).limit(1000).get();
  const S = require('./sellerstats');
  await Promise.all(snap.docs.map(async (d) => {
    const pid = d.get('pharmacyId');
    const r = d.get('rating');
    if (R.isValidId(pid) && Number.isInteger(r) && r > 0) {
      // মিলিত গড়ের সাথে দিকভিত্তিক যোগফল/সংখ্যা থেকেও বাদ
      await db.doc(`pharmacies/${pid}`).update({
        ratingSum: FieldValue.increment(-r), ratingCount: FieldValue.increment(-1), ...aspectDelta(d.get('aspects'), null),
      }).catch(() => {});
      await S.removeRecentRating('pharmacyStats', pid, uid);
    }
    await d.ref.delete();
  }));
  if (snap.size) require('./memcache').clear('pharm:');
}

/** তালিকার জন্য: কোন দোকানে কোন কোন দিকে রেটিং দেওয়া যায় — দোকান → ['behaviour', ...] (অতিথি হলে খালি) */
async function rateableFor(uid, ids, now = Date.now()) {
  const out = new Map();
  if (!uid || !ids.length) return out;
  const [calls, bought] = await Promise.all([
    db.collection('pharmacyCalls').where('uid', '==', uid).limit(200).get(),
    boughtFrom(uid, now),
  ]);
  const called = new Set();
  calls.docs.forEach((c) => { if (callBasis(c, now) === 'ok') called.add(c.get('pharmacyId')); });
  ids.forEach((id) => {
    if ((bought.has(id) || called.has(id)) && !cannotRate(uid, id)) out.set(id, aspectsFor(bought.get(id)));
  });
  return out;
}

// প্রেসক্রিপশন বাছা থাকলে তালিকায় কয়টা দোকান (প্রতিটার স্টকের ডক পড়া হয়)
const RX_LIST_MAX = 40;

/**
 * কার্ডের প্রেসক্রিপশনের হিসাব: কয়টা ওষুধ আছে (স্টকের তালিকা না রাখলে keepsStock false), আনুমানিক দাম, মোট ছাড়।
 * ছাড় দোকানের দাম থেকে: MRP − বিক্রি দাম (দোকানের হিসাব-খাতা), বাকি পরিমাণ ধরে। দোকান "৳৫০০-এর বেশি কিনলে" ছাড় দিলে
 * আর এই প্রেসক্রিপশনের MRP-র মোট তার কম হলে ছাড় ধরা হয় না (দাম = MRP-তে) — belowMin।
 * দাম না জানলে payable/discount null — কার্ডে দোকানের সাধারণ "MRP থেকে ৫% ছাড়"।
 */
function rxCard(p, m) {
  const e = m.estimate;
  const out = { keepsStock: m.keepsStock, have: m.have, total: m.total, missing: m.missing, priced: e ? e.priced : 0, payable: null, mrp: null, discount: null, belowMin: false };
  if (!e) return out;
  out.payable = e.total;
  if (e.mrp != null) {
    out.mrp = e.mrp;
    out.discount = e.discount;
    if (p.discountMin > 0 && e.mrp < p.discountMin && e.discount > 0) {
      out.belowMin = true;
      out.payable = e.mrp;
      out.discount = 0;
    }
  }
  return out;
}

function pharmacyView(d, mine = false) {
  const x = d.data();
  const n = x.ratingCount || 0;
  // মালিক সাময়িক বন্ধ রাখলে (ঈদ, সংস্কার) সেই তারিখ পর্যন্ত "বন্ধ"
  const closedUntil = (x.closedUntil || 0) > Date.now() ? x.closedUntil : 0;
  return {
    id: d.id,
    name: x.name,
    phone: K.fixPhone(x.phone),
    address: x.address || '',
    district: x.district,
    upazilaId: x.upazilaId || null,
    openAt: x.openAt || '',
    closeAt: x.closeAt || '',
    open24: x.open24 === true,
    openNow: !closedUntil && K.pharmacyOpenNow(x),
    closedUntil,
    closedDays: Array.isArray(x.closedDays) ? x.closedDays : [],
    delivery: x.delivery === true,
    deliveryNote: x.deliveryNote || '',
    discount: x.discount || 0,
    // ছাড়ের শর্ত (১৯ অক্টোবর ২০২৬, ঐচ্ছিক): কত টাকার বেশি কিনলে ছাড়, কত টাকার বেশিতে ডেলিভারি ফ্রি, ছোট নোট ("শুধু নগদে")
    discountMin: Number.isInteger(x.discountMin) ? x.discountMin : 0,
    freeDeliveryOver: Number.isInteger(x.freeDeliveryOver) ? x.freeDeliveryOver : 0,
    discountNote: x.discountNote || '',
    // অনলাইনে অর্ডার নেয় কি না আর ডেলিভারি চার্জ (rxnet.js-এর online সেটিং) — তালিকায় "অনলাইনে অর্ডার" বাছলে এগুলোই
    online: !!(x.online && x.online.enabled === true),
    deliveryFee: (x.online && x.online.fee) || '',
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    // দিকভিত্তিক গড় (বিস্তারিতে "ব্যবহার ★৪.৬ · ডেলিভারি ★৪.২")
    aspects: aspectView(x),
    ...(mine ? { licenseNo: x.licenseNo, status: x.status, statusReason: x.statusReason || '', hasLocation: typeof x.lat === 'number' } : {}),
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
    ...(['pending', 'rejected'].includes(status) ? { verify: await require('./verify').panelInfo('pharmacy', d.id, d.data()).catch(() => null) } : {}),
    verification: d.get('verification') || null,
  };
}

/** মালিকের নিজের কার্ডে: কোন কাগজের ছবি বাকি (অপেক্ষায়/বাতিল বা অ্যাডমিন চাইলে) */
async function withDocs(kind, d, v) {
  const x = d.data();
  if (!['pending', 'rejected'].includes(x.status) && !x.docsRequestedAt) return v;
  return { ...v, ...(await require('./verify').ownerInfo(kind, d.id, x).catch(() => ({}))), docsRequestNote: x.docsRequestNote || '' };
}

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/**
 * জেলার অনুমোদিত ফার্মেসি — উপজেলা দেওয়া থাকলে আগে সেই উপজেলার (বড় জেলায় ৫০০-র সীমায় যেন কাছের দোকান বাদ না পড়ে),
 * সাথে জেলার বাকিগুলো (GPS-এ কাছে কিন্তু পাশের উপজেলার দোকানের জন্য)
 */
async function areaPharmacies(district, upazilaId, limit = 500) {
  // একই জেলার তালিকা ১ মিনিট মেমোরিতে (তালিকা আর ওষুধ খোঁজা — প্রতিবার ৫০০ পড়া নয়)
  const M = require('./memcache');
  const rows = await M.cached(`pharm:${district}:${upazilaId || ''}:${limit}`, 60 * 1000, async () =>
    (await areaPharmaciesFresh(district, upazilaId, limit)).map((d) => ({ id: d.id, x: d.data() })));
  return rows.map(({ id, x }) => M.docLike(id, x));
}

async function areaPharmaciesFresh(district, upazilaId, limit) {
  const base = db.collection('pharmacies').where('district', '==', district).where('status', '==', 'approved');
  const all = await base.limit(limit).get();
  // জেলার সব দোকান সীমার মধ্যে এলে আর খোঁজার দরকার নেই (পড়া বাঁচে)
  if (!upazilaId || all.size < limit) return all.docs;
  const local = await base.where('upazilaId', '==', upazilaId).limit(limit).get();
  const seen = new Set();
  return [...local.docs, ...all.docs].filter((d) => (seen.has(d.id) ? false : seen.add(d.id)));
}

const actions = {
  // ---------- দোকান ----------
  async pharmacy_me(req, user) {
    const s = await db.doc(`pharmacies/${user.uid}`).get();
    return { pharmacy: s.exists ? await withDocs('pharmacy', s, pharmacyView(s, true)) : null };
  },

  async pharmacy_save(req, user) {
    const b = req.body || {};
    const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
    const name = C.cleanText(b.name, 100);
    if (!name) throw err('Enter the pharmacy name', 'ফার্মেসির নাম লিখুন');
    const licenseNo = C.cleanText(b.licenseNo, 40);
    if (!licenseNo) throw err('Enter the drug licence number', 'ড্রাগ লাইসেন্স নম্বর দিন');
    const phone = K.normalizeBd(b.phone);
    if (!phone) throw err('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন');
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err('Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    const address = C.cleanText(b.address, 200);
    if (!address) throw err('Write the shop address', 'দোকানের ঠিকানা লিখুন');
    const ref = db.doc(`pharmacies/${user.uid}`);
    const s = await ref.get();
    // নতুন লোকেশন না দিলে আগেরটা থাকে
    let lat = typeof b.lat === 'number' && b.lat > 20.3 && b.lat < 26.8 ? Math.round(b.lat * 1000) / 1000 : null;
    let lng = typeof b.lng === 'number' && b.lng > 87.9 && b.lng < 92.8 ? Math.round(b.lng * 1000) / 1000 : null;
    if ((lat == null || lng == null) && s.exists && typeof s.get('lat') === 'number') {
      lat = s.get('lat');
      lng = s.get('lng');
    }
    if (lat == null || lng == null) throw err('Set the shop location (stand in the shop and tap "Use current location")', 'দোকানের লোকেশন দিন (দোকানে দাঁড়িয়ে "বর্তমান লোকেশন" চাপুন)');
    const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;
    const open24 = b.open24 === true;
    if (!open24 && (!TIME.test(b.openAt || '') || !TIME.test(b.closeAt || '') || b.openAt === b.closeAt)) {
      throw err('Set opening and closing time', 'খোলা আর বন্ধের সময় দিন');
    }
    const discount = Number.isInteger(b.discount) && b.discount >= 0 && b.discount <= 30 ? b.discount : 0;
    // ছাড়ের শর্ত — পুরোনো অ্যাপ এই ঘরগুলো পাঠায় না, তখন আগের মান থাকে (closedDays-এর মতো)
    const taka = (v) => (Number.isInteger(v) && v >= 0 && v <= 100000 ? v : 0);
    const terms = {
      ...('discountMin' in b ? { discountMin: taka(b.discountMin) } : {}),
      ...('freeDeliveryOver' in b ? { freeDeliveryOver: taka(b.freeDeliveryOver) } : {}),
      ...('discountNote' in b ? { discountNote: K.str(typeof b.discountNote === 'string' ? b.discountNote.replace(/\s+/g, ' ') : '', 80) } : {}),
    };
    if (b.termsAccepted !== true) throw err('Please accept the terms', 'শর্তে সম্মতি দিন');
    const data = {
      name,
      licenseNo,
      phone: K.localBd(phone),
      district: b.district,
      upazilaId,
      address,
      lat,
      lng,
      open24,
      openAt: open24 ? '' : b.openAt,
      closeAt: open24 ? '' : b.closeAt,
      // পুরোনো অ্যাপ এই ঘর পাঠায় না — তখন আগের মান থাকে
      ...(Array.isArray(b.closedDays) ? { closedDays: K.pickClosedDays(b.closedDays) } : {}),
      delivery: b.delivery === true,
      deliveryNote: K.str(b.deliveryNote, 120),
      discount,
      ...terms,
      updatedAt: Date.now(),
    };
    if (s.exists) {
      if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'Your pharmacy is suspended. You can appeal from Profile → Help & appeals.', 'আপনার ফার্মেসি বন্ধ করা আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
      await require('./sanctions').checkListing(req, 'pharmacy', data, user.email, false);
      const { changed, approvedName } = K.reviewNeeded(s, data, ['licenseNo', 'phone', 'district']);
      await require('./verify').saveDocs(req, 'pharmacy', user.uid, user.uid, { ...s.data(), ...data }, b.docs, false);
      await ref.update({ ...data, ...(approvedName ? { approvedName } : {}), ...K.keepApproved(s, data, changed), ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    await require('./sanctions').checkListing(req, 'pharmacy', data, user.email, true);
    // কাগজের ছবি (ড্রাগ লাইসেন্স, দোকান) আগে — দৈনিক সীমা খরচের আগেই পরীক্ষা
    require('./verify').validateDocs(req, 'pharmacy', data, b.docs, true);
    await useQuota(req, user.uid, 'pharmacy');
    const docsHave = await require('./verify').saveDocs(req, 'pharmacy', user.uid, user.uid, data, b.docs, true);
    await ref.set({ ...data, ownerUid: user.uid, status: 'pending', docsHave: docsHave || {}, createdAt: Date.now() });
    return { ok: true, pending: true };
  },

  /** সাময়িক বন্ধ (until = মিলিসেকেন্ড, সর্বোচ্চ ৬০ দিন) বা আবার খোলা (until = null) */
  async pharmacy_pause(req, user) {
    const ref = db.doc(`pharmacies/${user.uid}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const until = req.body?.until;
    if (until == null) {
      await ref.update({ closedUntil: FieldValue.delete(), updatedAt: Date.now() });
      return { ok: true };
    }
    const now = Date.now();
    if (!Number.isInteger(until) || until <= now || until > now + 60 * R.DAY_MS) {
      throw new HttpError(400, C.tr(req, 'Pick a date within 60 days', '৬০ দিনের মধ্যে তারিখ দিন'));
    }
    await ref.update({ closedUntil: until, updatedAt: now });
    return { ok: true };
  },

  /** দোকান তালিকা থেকে মুছে ফেলা (বন্ধ করা দোকান নয় — সেটা আপিলের মাধ্যমে) */
  async pharmacy_delete(req, user) {
    const ref = db.doc(`pharmacies/${user.uid}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('status') === 'suspended') {
      throw new HttpError(403, C.tr(req, 'A suspended pharmacy can\'t be removed. You can appeal from Profile → Help & appeals.', 'বন্ধ করা ফার্মেসি সরানো যায় না। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
    }
    await require('./moderation').assertNoOpenReports(req, `pharmacies/${user.uid}`);
    await ref.delete();
    await db.doc(`pharmacyStats/${user.uid}`).delete().catch(() => {});
    // দোকানের নাম uid দিয়ে — আবার যোগ করলে গড় শূন্য থেকে, তাই আগের রেটিংগুলোও মোছা (নইলে পরে বদলালে গড় ভুল হতো)
    await deleteRatingsOf(user.uid).catch(() => {});
    await require('./verify').deleteDocs('pharmacy', user.uid, s.data()).catch(() => {});
    // দোকান নেই — অনলাইন অর্ডারের ডেলিভারির ঠিকানা-ফোন সব অর্ডার থেকে মোছা (পাতায় পাতায়, শেষ পর্যন্ত)
    await require('./rxnet').stripOrderContacts(user.uid).catch((e) => console.error('rx order contact strip failed', e.message));
    return { ok: true };
  },

  /**
   * এলাকার ফার্মেসির তালিকা (সরাসরি ফোনের জন্যও)।
   * ১৯ অক্টোবর ২০২৬ (পারভেজ): আগে "দোকানে গিয়ে কিনব" / "অনলাইনে অর্ডার" বাছাই — mode:
   *   'online' — শুধু অনলাইনে অর্ডার নেওয়া দোকান, যাদের ডেলিভারির দূরত্বে আছেন (rx_pharmacy_options-এর একই নিয়ম:
   *              জায়গা জানা থাকলে দোকানের radiusKm-এর মধ্যে, না জানলে একই উপজেলা — উপজেলা না দিলে জেলার সব);
   *   'shop'   — আগের মতো সব দোকান (এলাকা, এখন খোলা, ২৪ ঘণ্টা)।
   * rxId (লগইন করা রোগীর নিজের চালু প্রেসক্রিপশন; কেয়ারগিভার মোডে রোগীর): প্রতিটা কার্ডে rx — কয়টা ওষুধ আছে, আনুমানিক দাম,
   * মোট ছাড় — আর সাজানো: বেশি ওষুধ আছে আগে, তারপর কম দাম, তারপর খোলা/কাছে। এক ডাকেই (স্টকের ডক একসাথে পড়া)।
   * প্রেসক্রিপশনের অংশ কখনো মেমোরিতে রাখা হয় না — মেমোরিতে শুধু জেলার দোকানের তালিকা (areaPharmacies), সবার একই।
   * mode বা rxId দিলে (নতুন অ্যাপ) লগইন করা মানুষ নিজের জগতের দোকানই পান (ডেমো ↔ আসল) — পাঠানো তো সেখানেই যায়।
   */
  async pharmacies(req, user) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const mode = ['shop', 'online'].includes(b.mode) ? b.mode : null;
    const uid = (user && user.uid) || null;
    const RX = require('./rxnet');
    // প্রেসক্রিপশন আগে যাচাই (অন্যের বা অতিথি হলে ৪০৩ — তালিকার কিছুই যায় না)
    const rx = b.rxId != null && b.rxId !== '' ? await RX.ownRx(req, user, b.rxId) : null;
    const docs = await areaPharmacies(b.district, upazilaId);
    const world = uid && (mode || rx) ? docs.filter((d) => require('./demo').sameWorld(uid, d.id)) : docs;
    let items = world.map((d) => {
      const v = pharmacyView(d);
      const x = d.data();
      v.km = typeof b.lat === 'number' && typeof x.lat === 'number' ? Math.round(R.distanceKm(b.lat, b.lng, x.lat, x.lng) * 10) / 10 : null;
      if (mode === 'online') v.radiusKm = RX.onlineOf(x).radiusKm;
      return v;
    });
    if (mode === 'online') {
      items = items.filter((p) => p.online && (p.km != null ? p.km <= p.radiusKm : !upazilaId || p.upazilaId === upazilaId));
      items.forEach((p) => delete p.radiusKm);
    } else if (upazilaId) items = items.filter((p) => p.upazilaId === upazilaId || (p.km != null && p.km <= K.MED_RADIUS_KM));
    if (b.openNow === true) items = items.filter((p) => p.openNow);
    // "২৪ ঘণ্টা খোলা" (রাতে ওষুধ দরকার হলে): দোকানের নিজের ঘর open24 থেকে। মেমোরির তালিকা (areaPharmacies) ছাঁকার আগের
    // পুরো জেলার — ছাঁকা এখানে হয়, তাই ক্যাশে এক ফিল্টারের ফল অন্য ফিল্টারে যায় না। পুরোনো দোকানে ঘর না থাকলে = ২৪ ঘণ্টা নয়
    if (b.open24 === true) items = items.filter((p) => p.open24);
    // খোলা আগে, তারপর কাছেরটা। রেটিং ক্রমে নয় — নতুন দোকান পিছিয়ে পড়ে আর রেটিং সাজানো যায়; রেটিং কার্ডে দেখায়
    items.sort((a, c) => (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)) || a.name.localeCompare(c.name, 'bn'));
    // প্রেসক্রিপশন বাছা থাকলে প্রতিটা দোকানের স্টকের ডক পড়তে হয় — তাই rx_pharmacy_options-এর মতো কাছের ৪০টা
    items = items.slice(0, rx ? RX_LIST_MAX : 100);
    let lastHome;
    if (rx) {
      const wanted = RX.wantedOf(rx.data());
      const stocks = items.length ? await db.getAll(...items.map((p) => db.doc(`pharmacyStock/${p.id}`))) : [];
      items = items.map((p, i) => ({ ...p, rx: rxCard(p, RX.stockMatch(stocks[i], wanted)) }));
      // বেশি ওষুধ আছে আগে (স্টকের তালিকা না রাখলে জানা নেই — শেষে), তারপর কম আনুমানিক দাম, তারপর খোলা, তারপর কাছে
      const avail = (p) => (p.rx.keepsStock && p.rx.total ? p.rx.have / p.rx.total : -1);
      items.sort((a, c) => (avail(c) - avail(a)) || ((a.rx.payable ?? Infinity) - (c.rx.payable ?? Infinity))
        || (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)) || a.name.localeCompare(c.name, 'bn'));
      // অনলাইন অর্ডারের ফর্মে আগের ঠিকানা-ফোন (শুধু নিজের — রোগীর)
      lastHome = await require('./labprices').lastHome(uid).catch(() => null);
    }
    // কোনগুলোতে রেটিং দিতে পারি (অ্যাপ বন্ধ করে খুললেও "রেটিং দিন" বাটন থাকে) আর কোন দিকে — অতিথির জন্য নয়
    const rateable = await rateableFor(uid, items.map((p) => p.id));
    return {
      items: items.map((p) => ({ ...p, canRate: rateable.has(p.id), rateAspects: rateable.get(p.id) || [] })),
      ...(rx ? { rxId: rx.id, lastHome: lastHome || null } : {}),
    };
  },

  /** কেউ অ্যাপ থেকে ফোন করলে — মালিকের হিসাবের জন্য (একই মানুষ ১০ মিনিটে একবার); রেটিং/রিপোর্ট এর পরেই */
  async pharmacy_called(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id) || id === user.uid) return { ok: true };
    const p = await db.doc(`pharmacies/${id}`).get();
    if (!p.exists || !require('./demo').sameWorld(user.uid, id)) return { ok: true }; // ডেমো: আলাদা — অন্য জগতের ফোন গোনা হয় না
    const callRef = db.doc(`pharmacyCalls/${user.uid}_${id}`);
    const prev = await callRef.get();
    const now = Date.now();
    // প্রথম ফোনের সময় (রেটিং তার ১ ঘণ্টা পর); আগের রেকর্ডে না থাকলে at ধরা হয়
    await callRef.set({ uid: user.uid, pharmacyId: id, at: now, ...(prev.exists ? {} : { firstAt: now }) }, { merge: true });
    if (!prev.exists || now - (prev.get('at') || 0) > 10 * 60 * 1000) {
      await require('./sellerstats').bumpCalls('pharmacyStats', id, now, !prev.exists);
    }
    return { ok: true };
  },

  /**
   * ফোন বা কেনার পর রেটিং (একজন একটা দোকানে একবার; পরে বদলানো যায়) — দোকানের সেবার, দিকভিত্তিক:
   * { id, aspects: { behaviour: 1-5, price?: 1-5, delivery?: 1-5 }, comment }। ব্যবহার লাগবেই; কোন দিক দেওয়া যায় তা সার্ভার ঠিক করে।
   * পুরোনো অ্যাপ শুধু rating পাঠায় — তখন সেটা ব্যবহারের রেটিং; আগে দেওয়া দাম/ডেলিভারির রেটিং থাকে (শুধু ব্যবহার বদলায়)।
   */
  async pharmacy_rate(req, user) {
    const b = req.body || {};
    const { id } = b;
    const stars = (v) => Number.isInteger(v) && v >= 1 && v <= 5;
    const legacy = !(b.aspects && typeof b.aspects === 'object' && !Array.isArray(b.aspects));
    const given = !legacy ? b.aspects : (b.rating !== undefined ? { behaviour: b.rating } : {});
    const aspects = {};
    for (const [k, v] of Object.entries(given)) {
      if (v == null || v === 0) continue; // না দেওয়া দিক
      if (!ASPECTS.includes(k) || !stars(v)) throw new HttpError(400, C.tr(req, 'Choose 1 to 5 stars', '১ থেকে ৫ তারা দিন'));
      aspects[k] = v;
    }
    if (!R.isValidId(id) || !aspects.behaviour) throw new HttpError(400, C.tr(req, 'Rate the behaviour with 1 to 5 stars', 'ব্যবহারে ১ থেকে ৫ তারা দিন'));
    const comment = K.str(b.comment, 300);
    const ref = db.doc(`pharmacies/${id}`);
    const rateRef = db.doc(`pharmacyRatings/${user.uid}_${id}`);
    const now = Date.now();
    const [p, c] = await Promise.all([ref.get(), db.doc(`pharmacyCalls/${user.uid}_${id}`).get()]);
    // ডেমো ↔ আসল: অন্য জগতের দোকান "নেই"
    if (!p.exists || !require('./demo').sameWorld(user.uid, id)) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (cannotRate(user.uid, id, p)) throw new HttpError(403, C.tr(req, "You can't rate your own pharmacy", 'নিজের ফার্মেসিতে রেটিং দেওয়া যায় না'));
    const byCall = callBasis(c, now);
    const bought = (await boughtFrom(user.uid, now)).get(id);
    if (byCall !== 'ok' && !bought) {
      if (byCall === 'wait') throw new HttpError(403, C.tr(req, 'You can rate 1 hour after your first call', 'প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন'));
      throw new HttpError(403, C.tr(req, 'You can rate after calling this pharmacy from the app or getting medicines from it', 'অ্যাপ থেকে ফোন করার বা এই দোকান থেকে ওষুধ নেওয়ার পর রেটিং দিতে পারবেন'));
    }
    // শুধু ফোন করেছেন — দাম/ডেলিভারি জানেন না; অনলাইন অর্ডার ছাড়া ডেলিভারি নয়
    const allowed = aspectsFor(bought);
    if (Object.keys(aspects).some((k) => !allowed.includes(k))) {
      throw new HttpError(403, aspects.delivery && allowed.includes('price')
        ? C.tr(req, 'You can rate delivery after an online order from this shop is delivered', 'এই দোকানের অনলাইন অর্ডার পৌঁছানোর পর ডেলিভারির রেটিং দেওয়া যায়')
        : C.tr(req, 'You can rate price and delivery after getting medicines from this shop', 'এই দোকান থেকে ওষুধ নেওয়ার পর দাম ও ডেলিভারির রেটিং দেওয়া যায়'));
    }
    // মিলিত রেটিং = দেওয়া দিকগুলোর গড় (পূর্ণসংখ্যায়) — কার্ডের গড় এ দিয়েই
    const avgOf = (o) => { const vals = Object.values(o); return Math.round(vals.reduce((a, v) => a + v, 0) / vals.length); };
    const rating = await db.runTransaction(async (t) => {
      const [r, shop] = await Promise.all([t.get(rateRef), t.get(ref)]);
      if (!shop.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const old = (r.exists && r.get('rating')) || 0;
      // আগের রেটিংয়ের দিক (আগের রেটিংয়ে দিক না থাকলে কিছু নয় — তখন দিকের যোগফলে সেটা ছিলই না)
      const oldAspects = (r.exists && r.get('aspects')) || null;
      // পুরোনো অ্যাপ শুধু ব্যবহারের তারা পাঠায় — আগে নতুন অ্যাপে দেওয়া দাম/ডেলিভারি মুছে না দিয়ে রাখা
      const next = legacy && oldAspects ? { ...Object.fromEntries(Object.entries(oldAspects).filter(([k, v]) => ASPECTS.includes(k) && stars(v))), ...aspects } : aspects;
      const value = avgOf(next);
      t.update(ref, { ratingSum: FieldValue.increment(value - old), ratingCount: FieldValue.increment(old ? 0 : 1), ...aspectDelta(oldAspects, next) });
      // merge নয় — নইলে আগের দেওয়া দিক (যেমন ডেলিভারি) নতুন রেটিংয়ে না থাকলেও থেকে যেত (পুরোনো অ্যাপে উপরে নিজে মেলানো)
      const firstRatedAt = (r.exists && (r.get('firstRatedAt') || r.get('ratedAt'))) || now;
      t.set(rateRef, { uid: user.uid, pharmacyId: id, rating: value, aspects: next, comment, ratedAt: now, firstRatedAt });
      return value;
    });
    // মালিকের হিসাবে সাম্প্রতিক ১০টা রেটিং (কে দিয়েছেন তা ছাড়া)
    await require('./sellerstats').addRecentRating('pharmacyStats', id, rating, comment, user.uid);
    // মেমোরির তালিকা নতুন করে, যাতে কার্ডে নতুন গড় সাথে সাথে দেখায়
    require('./memcache').clear('pharm:');
    return { ok: true, rating };
  },

  /** দোকানের মালিক: অ্যাপ থেকে কতবার ফোন এসেছে, রেটিং, সাম্প্রতিক মন্তব্য, রিপোর্ট (অ্যাম্বুলেন্সের হিসাবের মতো) */
  async pharmacy_stats(req, user) {
    const [s, p, reports] = await Promise.all([
      db.doc(`pharmacyStats/${user.uid}`).get(),
      db.doc(`pharmacies/${user.uid}`).get(),
      require('./moderation').reportCount(`pharmacies/${user.uid}`).catch(() => 0),
    ]);
    const n = (p.exists && p.get('ratingCount')) || 0;
    return {
      ...require('./sellerstats').callSummary(s),
      rating: n ? Math.round((p.get('ratingSum') / n) * 10) / 10 : 0,
      ratingCount: n,
      aspects: aspectView(p.exists ? p.data() : null),
      // কে দিয়েছেন তা দেখানো হয় না (রোগীর গোপনীয়তা)
      comments: ((s.exists && s.get('recent')) || []).slice().reverse().map(({ who, ...c }) => c),
      reports,
    };
  },

  // ---------- অ্যাডমিন ----------
  /** অবস্থা অনুযায়ী তালিকা (ডিফল্ট: অপেক্ষায়) — ওয়েব প্যানেলে অনুমোদিত/বন্ধ/বাতিলও দেখা যায় */
  async admin_pharmacies(req, user) {
    await requireAdmin(req, user);
    const status = ADMIN_STATUSES.includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('pharmacies').where('status', '==', status).limit(300).get();
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...pharmacyView(d, true),
        createdAt: d.get('createdAt') || null,
        reviewedAt: (status === 'suspended' && d.get('suspendedAt')) || d.get('reviewedAt') || null,
        ...(await adminExtras('pharmacies', d, status)),
      }))),
    };
  },

  /** অনুমোদন/বাতিল (অ্যাপ: approve), ওয়েব প্যানেল থেকে বন্ধ বা আবার চালুও (status) */
  async admin_pharmacy_resolve(req, user) {
    await requireAdmin(req, user);
    return require('./listingreview').resolveListing(req, user, 'pharmacy', 'pharmacies', req.body?.id);
  },
};

module.exports = { areaPharmacies, actions, removeRatingsBy, deleteRatingsOf };
