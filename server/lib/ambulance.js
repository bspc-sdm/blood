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

function view(d, mine = false) {
  const x = d.data();
  const n = x.ratingCount || 0;
  return {
    id: d.id,
    name: x.name,
    phone: x.phone,
    types: x.types || [],
    district: x.district,
    upazilaId: x.upazilaId || null,
    area: x.area || '',
    fare: x.fare || '',
    available: x.available !== false,
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    ...(mine ? { vehicleNo: x.vehicleNo, status: x.status } : {}),
  };
}

function requireAdmin(req, user) {
  return require('./admin').isAdmin(user).then((ok) => {
    if (!ok) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
  });
}

const actions = {
  /** জেলার অনুমোদিত অ্যাম্বুলেন্স: খালি আগে, নিজের উপজেলার আগে, রেটিং বেশি আগে */
  async ambulances(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const snap = await db.collection('ambulances').where('district', '==', b.district).where('status', '==', 'approved').limit(300).get();
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    let items = snap.docs.map((d) => view(d));
    if (TYPES.includes(b.type)) items = items.filter((a) => a.types.includes(b.type));
    items.sort((a, c) =>
      (c.available - a.available) ||
      ((upazilaId && c.upazilaId === upazilaId) - (upazilaId && a.upazilaId === upazilaId)) ||
      (c.rating - a.rating) || (c.ratingCount - a.ratingCount));
    return { items };
  },

  async ambulance_mine(req, user) {
    const snap = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    return { items: snap.docs.map((d) => view(d, true)) };
  },

  /** নতুন যোগ বা বদল। নাম, ফোন বা গাড়ির নম্বর বদলালে আবার যাচাই */
  async ambulance_save(req, user) {
    const b = req.body || {};
    const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
    const name = C.cleanText(b.name, 80);
    if (!name) throw err('Enter the service or driver name', 'সার্ভিস বা চালকের নাম লিখুন');
    const phone = K.normalizeBd(b.phone);
    if (!phone) throw err('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন');
    const vehicleNo = C.cleanText(b.vehicleNo, 40);
    if (!vehicleNo) throw err('Enter the vehicle registration number', 'গাড়ির রেজিস্ট্রেশন নম্বর দিন');
    const types = K.pickCodes(b.types, TYPES, TYPES.length);
    if (!types.length) throw err('Choose the ambulance type', 'অ্যাম্বুলেন্সের ধরন বেছে নিন');
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err('Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    if (b.termsAccepted !== true) throw err('Please accept the terms', 'শর্তে সম্মতি দিন');
    const data = {
      name,
      phone: `0${phone.slice(2)}`,
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
      if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'This ambulance was suspended after reports. Contact us.', 'রিপোর্টের পর এই অ্যাম্বুলেন্স বন্ধ আছে। আমাদের সাথে যোগাযোগ করুন।'));
      const changed = ['name', 'phone', 'vehicleNo'].some((k) => s.get(k) !== data[k]) || s.get('status') === 'rejected';
      await ref.update({ ...data, ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    // রিপোর্টের পর বন্ধ হওয়া গাড়ি মুছে আবার যোগ করা যায় না
    const sameVehicle = await db.collection('ambulances').where('vehicleNo', '==', vehicleNo).where('status', '==', 'suspended').limit(1).get();
    if (!sameVehicle.empty) throw new HttpError(403, C.tr(req, 'This vehicle was suspended after reports. Contact us.', 'রিপোর্টের পর এই গাড়ি বন্ধ আছে। আমাদের সাথে যোগাযোগ করুন।'));
    const mine = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    if (mine.size >= MAX_PER_OWNER) throw err('Up to 10 ambulances per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ১০টা অ্যাম্বুলেন্স');
    await useQuota(req, user.uid, 'ambulance');
    await db.collection('ambulances').add({ ...data, ownerUid: user.uid, status: 'pending', available: true, createdAt: Date.now() });
    return { ok: true, pending: true };
  },

  async ambulance_available(req, user) {
    const { id, available } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await ref.update({ available: available === true, updatedAt: Date.now() });
    return { ok: true };
  },

  async ambulance_delete(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) return { ok: true };
    if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'A suspended ambulance can\'t be removed. Contact us.', 'বন্ধ করা অ্যাম্বুলেন্স সরানো যায় না। আমাদের সাথে যোগাযোগ করুন।'));
    await ref.delete();
    return { ok: true };
  },

  /** কেউ ফোন করলে লিখে রাখা — রেটিং/রিপোর্ট শুধু যারা ফোন করেছেন তারাই দিতে পারেন */
  async ambulance_called(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) return { ok: true };
    await db.doc(`ambulanceCalls/${user.uid}_${id}`).set({ uid: user.uid, ambulanceId: id, at: Date.now() }, { merge: true });
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
      const old = c.get('rating') || 0;
      t.update(ref, { ratingSum: FieldValue.increment(rating - old), ratingCount: FieldValue.increment(old ? 0 : 1) });
      t.update(callRef, { rating, comment: K.str(req.body?.comment, 300), ratedAt: Date.now() });
    });
    return { ok: true };
  },

  async admin_ambulances(req, user) {
    await requireAdmin(req, user);
    const snap = await db.collection('ambulances').where('status', '==', 'pending').limit(100).get();
    return { items: snap.docs.map((d) => view(d, true)) };
  },

  async admin_ambulance_resolve(req, user) {
    await requireAdmin(req, user);
    const { id, approve } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`ambulances/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await ref.update({ status: approve === true ? 'approved' : 'rejected', reviewedAt: Date.now() });
    try {
      const u = await db.doc(`users/${s.get('ownerUid')}`).get();
      const lang = u.get('lang');
      await require('./firebase').sendPush([{
        uid: s.get('ownerUid'),
        token: u.get('fcmToken'),
        title: approve === true ? R.L(lang, 'Your ambulance is now listed', 'আপনার অ্যাম্বুলেন্স তালিকায় এসেছে') : R.L(lang, 'Ambulance not approved', 'অ্যাম্বুলেন্স অনুমোদন হয়নি'),
        body: s.get('name'),
        data: { type: 'services' },
      }]);
    } catch (e) {
      console.error('ambulance push failed', e);
    }
    return { ok: true };
  },
};

module.exports = { actions, TYPES };
