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
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
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
    await require('./verify').deleteDocs('pharmacy', user.uid, s.data()).catch(() => {});
    return { ok: true };
  },

  /** এলাকার ফার্মেসির তালিকা (সরাসরি ফোনের জন্যও) */
  async pharmacies(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const docs = await areaPharmacies(b.district, upazilaId);
    let items = docs.map((d) => {
      const v = pharmacyView(d);
      const x = d.data();
      v.km = typeof b.lat === 'number' && typeof x.lat === 'number' ? Math.round(R.distanceKm(b.lat, b.lng, x.lat, x.lng) * 10) / 10 : null;
      return v;
    });
    if (upazilaId) items = items.filter((p) => p.upazilaId === upazilaId || (p.km != null && p.km <= K.MED_RADIUS_KM));
    if (b.openNow === true) items = items.filter((p) => p.openNow);
    // খোলা আগে, তারপর কাছেরটা। রেটিং ক্রমে নয় — নতুন দোকান পিছিয়ে পড়ে আর রেটিং সাজানো যায়; রেটিং কার্ডে দেখায়
    items.sort((a, c) => (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)) || a.name.localeCompare(c.name, 'bn'));
    return { items: items.slice(0, 100) };
  },

  /** কেউ অ্যাপ থেকে ফোন করলে — মালিকের হিসাবের জন্য (একই মানুষ ১০ মিনিটে একবার) */
  async pharmacy_called(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id) || id === user.uid) return { ok: true };
    const p = await db.doc(`pharmacies/${id}`).get();
    if (!p.exists) return { ok: true };
    const callRef = db.doc(`pharmacyCalls/${user.uid}_${id}`);
    const prev = await callRef.get();
    const now = Date.now();
    await callRef.set({ uid: user.uid, pharmacyId: id, at: now }, { merge: true });
    if (!prev.exists || now - (prev.get('at') || 0) > 10 * 60 * 1000) {
      await require('./sellerstats').bumpCalls('pharmacyStats', id, now, !prev.exists);
    }
    return { ok: true };
  },

  /** দোকানের মালিক: অ্যাপ থেকে কতবার ফোন এসেছে */
  async pharmacy_stats(req, user) {
    const s = await db.doc(`pharmacyStats/${user.uid}`).get();
    return require('./sellerstats').callSummary(s);
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

module.exports = { areaPharmacies, actions };
