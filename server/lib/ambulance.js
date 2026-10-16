// অ্যাম্বুলেন্স: মালিক/চালক নিজের অ্যাম্বুলেন্স যোগ করেন, অ্যাডমিন যাচাই করে অনুমোদন দেন,
// রোগীর লোকজন এলাকা দিয়ে খুঁজে সরাসরি ফোন করেন। টাকা অ্যাপে নেই।
const { db, FieldValue, HttpError } = require('./firebase');
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
// "এখন খালি আছি" কতক্ষণ থাকে: চালক ভুলে গেলে বা কাজ ছেড়ে দিলে যেন তাঁর নম্বর সবার উপরে না থাকে
const AVAILABLE_MS = 12 * 3600 * 1000;

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
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    ...(mine ? { vehicleNo: x.vehicleNo, status: x.status, statusReason: x.statusReason || '' } : {}),
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

function requireAdmin(req, user) {
  return require('./admin').isAdmin(user).then((ok) => {
    if (!ok) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
  });
}

const actions = {
  /** জেলার অনুমোদিত অ্যাম্বুলেন্স: খালি আগে, নিজের উপজেলার আগে, রেটিং বেশি আগে */
  async ambulances(req, user) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const snap = await db.collection('ambulances').where('district', '==', b.district).where('status', '==', 'approved').limit(300).get();
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    // কোনগুলোতে রেটিং দিতে পারি (অ্যাপ বন্ধ করে খুললেও "রেটিং" বাটন থাকে)
    const calls = user ? await db.collection('ambulanceCalls').where('uid', '==', user.uid).limit(200).get() : { docs: [] };
    const now = Date.now();
    const rateable = new Set(calls.docs.filter((c) => now - (c.get('at') || 0) <= RATE_WINDOW_MS
      && now - (c.get('firstAt') || c.get('at') || 0) >= RATE_AFTER_MS).map((c) => c.get('ambulanceId')));
    let docs = snap.docs;
    if (require('./catalog').codes('ambulanceType').includes(b.type)) docs = docs.filter((d) => (d.get('types') || []).includes(b.type));
    // ট্রিপে থাকা গাড়ি তালিকায় নয় — শুধু কয়টা ট্রিপে আছে তা জানানো
    // ব্যস্ত গাড়ি আলাদা তালিকায় (অ্যাপে ভাঁজ করা অংশে) — দরকারে ফোন করে জানা যায় কবে ফিরবে
    const busy = docs.filter((d) => busyNow(d.data(), now))
      .map((d) => ({ ...view(d), canRate: rateable.has(d.id), busy: true, busySince: d.get('busyAt') || d.get('availableAt') || 0 }))
      .sort((a, c) => ((upazilaId && c.upazilaId === upazilaId) - (upazilaId && a.upazilaId === upazilaId)) || (a.busySince - c.busySince));
    let items = docs.filter((d) => !busyNow(d.data(), now)).map((d) => ({ ...view(d), canRate: rateable.has(d.id) }));
    items.sort((a, c) =>
      (c.available - a.available) ||
      ((upazilaId && c.upazilaId === upazilaId) - (upazilaId && a.upazilaId === upazilaId)) ||
      (c.rating - a.rating) || (c.ratingCount - a.ratingCount));
    return { items, busyCount: busy.length, busy: busy.slice(0, 50) };
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
    const data = {
      name,
      phone,
      vehicleNo,
      types,
      district: b.district,
      upazilaId,
      area: K.str(b.area, 120),
      fare: K.str(b.fare, 120),
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
      await ref.update({ ...data, ...(approvedName ? { approvedName } : {}), ...K.keepApproved(s, data, changed), ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
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
    return { ok: true, pending: true, id: ref.id };
  },

  async ambulance_available(req, user) {
    const { id, available } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const on = available === true;
    await ref.update({ available: on, availableAt: Date.now(), busyAt: on ? FieldValue.delete() : Date.now(), updatedAt: Date.now() });
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

  /** কেউ ফোন করলে লিখে রাখা — রেটিং/রিপোর্ট শুধু যারা ফোন করেছেন তারাই দিতে পারেন */
  async ambulance_called(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) return { ok: true };
    const a = await db.doc(`ambulances/${id}`).get();
    if (!a.exists || a.get('ownerUid') === user.uid) return { ok: true };
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
      if (!a.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      if (!c.exists || Date.now() - (c.get('at') || 0) > RATE_WINDOW_MS) {
        throw new HttpError(403, C.tr(req, 'You can rate after calling this ambulance from the app', 'অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন'));
      }
      if (Date.now() - (c.get('firstAt') || c.get('at') || 0) < RATE_AFTER_MS) {
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

module.exports = { actions, TYPES };
