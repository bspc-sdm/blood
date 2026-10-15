// ফার্মেসি: লাইসেন্সধারী দোকান যোগ হয় (অ্যাডমিন যাচাই), রোগী প্রেসক্রিপশন/ওষুধের তালিকা দিয়ে অর্ডার দেন,
// কাছের খোলা দোকানগুলো তিন ট্যাপে উত্তর দেয় (আছে/কিছু/নেই + দাম), রোগী একটা বেছে নেন।
// স্টক তালিকা রাখতে হয় না। টাকা অ্যাপে নেই — ডেলিভারি বা দোকানে নগদ/দোকানের নিজের বিকাশে।
const { db, FieldValue, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota, userName } = require('./community-db');

const PHOTO_KEEP_MS = 7 * 24 * 3600 * 1000;
const MAX_PHOTO_CHARS = 400 * 1024; // base64, ≈৩০০ KB ছবি
const NO_SHOW_LIMIT = 3;
const STATUS_ACTIVE = ['open', 'confirmed', 'ready', 'on_the_way'];

function pharmacyView(d, mine = false) {
  const x = d.data();
  const n = x.ratingCount || 0;
  return {
    id: d.id,
    name: x.name,
    phone: x.phone,
    address: x.address || '',
    district: x.district,
    upazilaId: x.upazilaId || null,
    openAt: x.openAt || '',
    closeAt: x.closeAt || '',
    open24: x.open24 === true,
    openNow: K.pharmacyOpenNow(x),
    delivery: x.delivery === true,
    deliveryNote: x.deliveryNote || '',
    discount: x.discount || 0,
    rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
    ratingCount: n,
    ...(mine ? { licenseNo: x.licenseNo, status: x.status, hasLocation: typeof x.lat === 'number' } : {}),
  };
}

async function pushUid(uid, en, bn, body, data) {
  if (!uid) return;
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists) return;
    await sendPush([{ uid, token: u.get('fcmToken'), title: R.L(u.get('lang'), en, bn), body: body || '', data }]);
  } catch (e) {
    console.error('pharmacy push failed', e);
  }
}

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/** অনুমোদিত দোকান (জেলার), নিজেরটা বাদ */
async function approvedIn(district) {
  const snap = await db.collection('pharmacies').where('district', '==', district).where('status', '==', 'approved').limit(500).get();
  return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
}

function orderForPatient(d, photo) {
  const x = d.data();
  const replies = Object.entries(x.replies || {}).map(([pid, r]) => ({ pharmacyId: pid, ...r }));
  return {
    id: d.id,
    role: 'patient',
    items: x.items || '',
    hasPhoto: !!photo || x.hasPhoto === true,
    needsPrescription: x.needsPrescription === true,
    mode: x.mode,
    address: x.address || '',
    status: K.medOrderExpired(x) ? 'expired' : x.status,
    chosen: x.chosen || null,
    chosenName: x.chosen && x.replies && x.replies[x.chosen] ? x.replies[x.chosen].name : '',
    replies,
    notifiedCount: (x.notified || []).length,
    remindDays: x.remindDays || 0,
    rating: x.rating || 0,
    createdAt: x.createdAt,
  };
}

const actions = {
  // ---------- দোকান ----------
  async pharmacy_me(req, user) {
    const s = await db.doc(`pharmacies/${user.uid}`).get();
    return { pharmacy: s.exists ? pharmacyView(s, true) : null };
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
    const lat = typeof b.lat === 'number' && b.lat > 20.3 && b.lat < 26.8 ? Math.round(b.lat * 1000) / 1000 : null;
    const lng = typeof b.lng === 'number' && b.lng > 87.9 && b.lng < 92.8 ? Math.round(b.lng * 1000) / 1000 : null;
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
      phone: `0${phone.slice(2)}`,
      district: b.district,
      upazilaId,
      address,
      lat,
      lng,
      open24,
      openAt: open24 ? '' : b.openAt,
      closeAt: open24 ? '' : b.closeAt,
      delivery: b.delivery === true,
      deliveryNote: K.str(b.deliveryNote, 120),
      discount,
      updatedAt: Date.now(),
    };
    const ref = db.doc(`pharmacies/${user.uid}`);
    const s = await ref.get();
    if (s.exists) {
      if (s.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'Your pharmacy was suspended after reports. Contact us.', 'রিপোর্টের পর আপনার ফার্মেসি বন্ধ আছে। আমাদের সাথে যোগাযোগ করুন।'));
      const changed = ['name', 'licenseNo'].some((k) => s.get(k) !== data[k]);
      await ref.update({ ...data, ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    await useQuota(req, user.uid, 'pharmacy');
    await ref.set({ ...data, ownerUid: user.uid, status: 'pending', createdAt: Date.now() });
    return { ok: true, pending: true };
  },

  /** এলাকার ফার্মেসির তালিকা (সরাসরি ফোনের জন্যও) */
  async pharmacies(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const snap = await db.collection('pharmacies').where('district', '==', b.district).where('status', '==', 'approved').limit(500).get();
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    let items = snap.docs.map((d) => {
      const v = pharmacyView(d);
      const x = d.data();
      v.km = typeof b.lat === 'number' && typeof x.lat === 'number' ? Math.round(R.distanceKm(b.lat, b.lng, x.lat, x.lng) * 10) / 10 : null;
      return v;
    });
    if (upazilaId) items = items.filter((p) => p.upazilaId === upazilaId || (p.km != null && p.km <= K.MED_RADIUS_KM));
    if (b.openNow === true) items = items.filter((p) => p.openNow);
    items.sort((a, c) => (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)) || (c.rating - a.rating));
    return { items: items.slice(0, 100) };
  },

  // ---------- অর্ডার ----------
  async med_order(req, user) {
    const b = req.body || {};
    const err = (en, bn, code = 400) => new HttpError(code, C.tr(req, en, bn));
    const items = K.str(b.items, 1000);
    const photo = typeof b.photo === 'string' && b.photo.length > 100 && b.photo.length <= MAX_PHOTO_CHARS && /^[A-Za-z0-9+/=\n]+$/.test(b.photo) ? b.photo : null;
    if (!items && !photo) throw err('Add the prescription photo or write the medicines', 'প্রেসক্রিপশনের ছবি দিন বা ওষুধের নাম লিখুন');
    if (b.needsPrescription === true && !photo) throw err('Prescription medicines need the prescription photo', 'প্রেসক্রিপশনের ওষুধে প্রেসক্রিপশনের ছবি লাগবে');
    const mode = b.mode === 'pickup' ? 'pickup' : 'delivery';
    const address = mode === 'delivery' ? C.cleanText(b.address, 200) : '';
    if (mode === 'delivery' && !address) throw err('Write the delivery address', 'ডেলিভারির ঠিকানা লিখুন');
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err('Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    const lat = typeof b.lat === 'number' ? b.lat : null;
    const lng = typeof b.lng === 'number' ? b.lng : null;
    if (lat == null && !upazilaId) throw err('Use your current location or choose the upazila', 'বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন');
    if (b.termsAccepted !== true) throw err('Please accept the terms', 'শর্তে সম্মতি দিন');

    const [me, state] = await Promise.all([db.doc(`users/${user.uid}`).get(), db.doc(`serverState/${user.uid}`).get()]);
    if (state.exists && (state.get('medNoShow') || 0) >= NO_SHOW_LIMIT) {
      throw err('Ordering is turned off for your account because orders were not received several times. Contact us.', 'কয়েকবার অর্ডার না নেওয়ায় আপনার অর্ডার দেওয়া বন্ধ আছে। আমাদের সাথে যোগাযোগ করুন।', 403);
    }
    if (!me.exists || !me.get('phone')) throw err('Add your phone number in your profile first', 'আগে প্রোফাইলে ফোন নম্বর দিন');
    await require('./phone').requireVerified(req, user.uid);
    await useQuota(req, user.uid, 'medTry');

    const order = { lat, lng, upazilaId };
    let targets;
    if (b.pharmacyId) {
      // আগের দোকানে আবার অর্ডার
      if (!R.isValidId(b.pharmacyId) || b.pharmacyId === user.uid) throw err('Pharmacy not found', 'ফার্মেসি পাওয়া যায়নি', 404);
      const p = await db.doc(`pharmacies/${b.pharmacyId}`).get();
      if (!p.exists || p.get('status') !== 'approved') throw err('This pharmacy is not taking orders now', 'এই ফার্মেসি এখন অর্ডার নিচ্ছে না', 404);
      targets = [{ id: p.id, ...p.data() }];
    } else {
      const all = (await approvedIn(b.district)).filter((p) => p.id !== user.uid);
      targets = K.pickPharmacies(order, all);
      if (!targets.length) return { ok: false, noPharmacy: true };
    }
    await useQuota(req, user.uid, 'medOrder');
    const now = Date.now();
    const ids = targets.map((p) => p.id);
    const ref = db.collection('medOrders').doc();
    const batch = db.batch();
    batch.set(ref, {
      patientUid: user.uid,
      patientName: (await userName(user.uid)).slice(0, 80),
      items,
      hasPhoto: !!photo,
      needsPrescription: b.needsPrescription === true,
      mode,
      address,
      lat,
      lng,
      district: b.district,
      upazilaId,
      notified: ids,
      openFor: ids,
      replies: {},
      chosen: null,
      status: 'open',
      remindDays: 0,
      createdAt: now,
      openedAt: now,
      termsAcceptedAt: now,
    });
    if (photo) batch.set(db.doc(`medOrderPhotos/${ref.id}`), { data: photo, expiresAt: now + PHOTO_KEEP_MS, patientUid: user.uid });
    await batch.commit();

    const users = await db.getAll(...ids.map((id) => db.doc(`users/${id}`)));
    await sendPush(users.filter((u) => u.exists).map((u) => ({
      uid: u.id,
      token: u.get('fcmToken'),
      title: R.L(u.get('lang'), 'New medicine order nearby', 'কাছে নতুন ওষুধের অর্ডার'),
      body: R.L(u.get('lang'), mode === 'pickup' ? 'Pickup from shop' : 'Home delivery', mode === 'pickup' ? 'দোকান থেকে নেবেন' : 'বাসায় ডেলিভারি'),
      data: { type: 'pharmacy_order', orderId: ref.id },
    })));
    return { ok: true, id: ref.id, notified: ids.length };
  },

  /** রোগী হিসেবে নিজের অর্ডার, আর দোকান হিসেবে নতুন অনুরোধ ও নিজের নেওয়া অর্ডার */
  async med_orders(req, user) {
    const now = Date.now();
    const [mine, offered, taken, shop] = await Promise.all([
      db.collection('medOrders').where('patientUid', '==', user.uid).limit(40).get(),
      db.collection('medOrders').where('openFor', 'array-contains', user.uid).limit(60).get(),
      db.collection('medOrders').where('chosen', '==', user.uid).limit(60).get(),
      db.doc(`pharmacies/${user.uid}`).get(),
    ]);
    // ফোন: বেছে নেওয়ার পর দুজনের
    const need = new Set();
    mine.docs.forEach((d) => d.get('chosen') && need.add(d.get('chosen')));
    taken.docs.forEach((d) => need.add(d.get('patientUid')));
    const ids = [...need];
    const usersSnap = ids.length ? await db.getAll(...ids.map((id) => db.doc(`users/${id}`))) : [];
    const phones = new Map(usersSnap.map((s) => [s.id, s.exists ? s.get('phone') || '' : '']));
    const shops = ids.length ? await db.getAll(...ids.map((id) => db.doc(`pharmacies/${id}`))) : [];
    const shopPhone = new Map(shops.filter((s) => s.exists).map((s) => [s.id, s.get('phone')]));

    const live = (s) => ['confirmed', 'ready', 'on_the_way'].includes(s);
    const asPatient = mine.docs
      .map((d) => {
        const o = orderForPatient(d);
        o.otherPhone = o.chosen && live(o.status) ? shopPhone.get(o.chosen) || phones.get(o.chosen) || '' : '';
        return o;
      })
      .sort((a, b) => b.createdAt - a.createdAt);

    const base = (d) => {
      const x = d.data();
      return {
        id: d.id,
        role: 'pharmacy',
        items: x.items || '',
        hasPhoto: x.hasPhoto === true,
        needsPrescription: x.needsPrescription === true,
        mode: x.mode,
        status: K.medOrderExpired(x, now) ? 'expired' : x.status,
        patientName: x.patientName || '',
        myReply: (x.replies || {})[user.uid] || null,
        km: typeof x.lat === 'number' && shop.exists && typeof shop.get('lat') === 'number'
          ? Math.round(R.distanceKm(x.lat, x.lng, shop.get('lat'), shop.get('lng')) * 10) / 10 : null,
        createdAt: x.createdAt,
      };
    };
    const takenIds = new Set(taken.docs.map((d) => d.id));
    const asPharmacy = [
      ...offered.docs
        .filter((d) => !takenIds.has(d.id) && d.get('status') === 'open' && !K.medOrderExpired(d.data(), now))
        .map((d) => ({ ...base(d), address: '', otherPhone: '' })),
      ...taken.docs.map((d) => ({
        ...base(d),
        address: live(d.get('status')) ? d.get('address') || '' : '',
        otherPhone: live(d.get('status')) ? phones.get(d.get('patientUid')) || '' : '',
        myReply: (d.get('replies') || {})[user.uid] || null,
      })),
    ].sort((a, b) => b.createdAt - a.createdAt);
    return { asPatient, asPharmacy, isPharmacy: shop.exists && shop.get('status') === 'approved' };
  },

  /** প্রেসক্রিপশনের ছবি: রোগী, যে দোকানে গেছে (খোলা থাকতে) আর বেছে নেওয়া দোকান */
  async med_order_photo(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const [o, p] = await Promise.all([db.doc(`medOrders/${id}`).get(), db.doc(`medOrderPhotos/${id}`).get()]);
    if (!o.exists || !p.exists) throw new HttpError(404, C.tr(req, 'The photo is no longer available', 'ছবিটা আর নেই'));
    const x = o.data();
    const ok = x.patientUid === user.uid || x.chosen === user.uid || (x.status === 'open' && (x.openFor || []).includes(user.uid));
    if (!ok) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    return { photo: p.get('data') };
  },

  /** দোকানের দ্রুত উত্তর: status all/some/none, মোট দাম, ডেলিভারি চার্জ */
  async med_order_reply(req, user) {
    const b = req.body || {};
    if (!R.isValidId(b.id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (!['all', 'some', 'none'].includes(b.status)) throw new HttpError(400, C.tr(req, 'Choose what you have', 'কী আছে বেছে নিন'));
    const total = Number.isInteger(b.total) && b.total >= 0 && b.total <= 1000000 ? b.total : 0;
    if (b.status !== 'none' && total <= 0) throw new HttpError(400, C.tr(req, 'Enter the total price', 'মোট দাম লিখুন'));
    const fee = Number.isInteger(b.fee) && b.fee >= 0 && b.fee <= 5000 ? b.fee : 0;
    const shop = await db.doc(`pharmacies/${user.uid}`).get();
    if (!shop.exists || shop.get('status') !== 'approved') throw new HttpError(403, C.tr(req, 'Your pharmacy is not approved yet', 'আপনার ফার্মেসি এখনো অনুমোদিত নয়'));
    const ref = db.doc(`medOrders/${b.id}`);
    const patient = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const x = s.data();
      if (x.status !== 'open' || K.medOrderExpired(x) || !(x.openFor || []).includes(user.uid)) {
        throw new HttpError(409, C.tr(req, 'This order is closed', 'এই অর্ডার বন্ধ হয়ে গেছে'));
      }
      t.update(ref, {
        [`replies.${user.uid}`]: {
          name: shop.get('name'),
          status: b.status,
          total: b.status === 'none' ? 0 : total,
          fee: x.mode === 'delivery' && b.status !== 'none' ? fee : 0,
          note: K.str(b.note, 200),
          altBrand: b.altBrand === true,
          discount: shop.get('discount') || 0,
          rating: shop.get('ratingCount') ? Math.round((shop.get('ratingSum') / shop.get('ratingCount')) * 10) / 10 : 0,
          at: Date.now(),
        },
        ...(b.status === 'none' ? { openFor: FieldValue.arrayRemove(user.uid) } : {}),
      });
      return x.patientUid;
    });
    if (b.status !== 'none') {
      await pushUid(patient, 'A pharmacy replied to your order', 'একটা ফার্মেসি আপনার অর্ডারে উত্তর দিয়েছে', shop.get('name'), { type: 'pharmacy_mine' });
    }
    return { ok: true };
  },

  /** রোগী একটা উত্তর বেছে নেন */
  async med_order_choose(req, user) {
    const { id, pharmacyId } = req.body || {};
    if (!R.isValidId(id) || !R.isValidId(pharmacyId)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`medOrders/${id}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists || s.get('patientUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const x = s.data();
      const r = (x.replies || {})[pharmacyId];
      if (x.status !== 'open' || !r || r.status === 'none') throw new HttpError(409, C.tr(req, 'This can\'t be chosen now', 'এখন এটা বেছে নেওয়া যায় না'));
      t.update(ref, { status: 'confirmed', chosen: pharmacyId, openFor: [], confirmedAt: Date.now() });
    });
    const others = Object.keys((await ref.get()).get('replies') || {}).filter((p) => p !== pharmacyId);
    await pushUid(pharmacyId, 'Your quote was accepted — prepare the order', 'আপনার উত্তর বেছে নেওয়া হয়েছে — অর্ডার তৈরি করুন', '', { type: 'pharmacy_order', orderId: id });
    await Promise.all(others.map((p) => pushUid(p, 'The customer chose another pharmacy', 'গ্রাহক অন্য ফার্মেসি বেছে নিয়েছেন', '', { type: 'pharmacy_order', orderId: id })));
    return { ok: true };
  },

  /** ready / on_the_way / done / no_show (দোকান), cancel / rate / remind (রোগী) */
  async med_order_update(req, user) {
    const { id, op } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`medOrders/${id}`);
    const nope = () => new HttpError(400, C.tr(req, 'This can\'t be changed now', 'এখন এটা বদলানো যায় না'));
    const rating = Number.isInteger(req.body?.rating) ? req.body.rating : 0;
    const days = Number.isInteger(req.body?.days) ? req.body.days : 0;
    const res = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const x = s.data();
      const isShop = x.chosen === user.uid;
      const isPatient = x.patientUid === user.uid;
      const now = Date.now();
      if (isShop && op === 'ready' && x.status === 'confirmed') {
        t.update(ref, { status: 'ready', readyAt: now });
        return { to: x.patientUid, msg: x.mode === 'pickup' ? ['Your medicines are ready for pickup', 'আপনার ওষুধ তৈরি, দোকান থেকে নিন'] : ['Your medicines are packed', 'আপনার ওষুধ প্যাক করা হয়েছে'] };
      }
      if (isShop && op === 'on_the_way' && ['confirmed', 'ready'].includes(x.status) && x.mode === 'delivery') {
        t.update(ref, { status: 'on_the_way', onTheWayAt: now });
        return { to: x.patientUid, msg: ['Your medicines are on the way', 'আপনার ওষুধ রওনা দিয়েছে'] };
      }
      if ((isShop || isPatient) && op === 'done' && ['confirmed', 'ready', 'on_the_way'].includes(x.status)) {
        t.update(ref, { status: 'done', doneAt: now, doneBy: isShop ? 'pharmacy' : 'patient' });
        return isShop ? { to: x.patientUid, msg: ['Order complete — please rate the pharmacy', 'অর্ডার সম্পন্ন — ফার্মেসিকে রেটিং দিন'] } : {};
      }
      if (isShop && op === 'no_show' && ['confirmed', 'ready', 'on_the_way'].includes(x.status)) {
        t.update(ref, { status: 'no_show', doneAt: now });
        t.set(db.doc(`serverState/${x.patientUid}`), { medNoShow: FieldValue.increment(1) }, { merge: true });
        return { to: x.patientUid, msg: ['The pharmacy marked your order as not received', 'ফার্মেসি জানিয়েছে আপনি অর্ডার নেননি'] };
      }
      if (isPatient && op === 'cancel' && STATUS_ACTIVE.includes(x.status)) {
        t.update(ref, { status: 'cancelled', cancelledAt: now, openFor: [] });
        return x.chosen ? { to: x.chosen, toShop: true, msg: ['The customer cancelled the order', 'গ্রাহক অর্ডার বাতিল করেছেন'] } : {};
      }
      if (isPatient && op === 'rate' && x.status === 'done' && !x.rating && x.chosen) {
        if (rating < 1 || rating > 5) throw new HttpError(400, C.tr(req, 'Choose 1 to 5 stars', '১ থেকে ৫ তারা দিন'));
        t.update(ref, { rating, comment: K.str(req.body?.comment, 300) });
        t.set(db.doc(`pharmacies/${x.chosen}`), { ratingSum: FieldValue.increment(rating), ratingCount: FieldValue.increment(1) }, { merge: true });
        return {};
      }
      if (isPatient && op === 'remind' && x.chosen && ['done', 'confirmed', 'ready', 'on_the_way'].includes(x.status)) {
        // মাসিক ওষুধ: এত দিন পর মনে করানো (০ = বন্ধ)
        if (![0, 7, 15, 30, 60, 90].includes(days)) throw nope();
        t.update(ref, { remindDays: days, remindAt: days ? (x.doneAt || now) + (days - 3) * 24 * 3600 * 1000 : null });
        return {};
      }
      throw nope();
    });
    // রোগীর কাছে গেলে "আমার অর্ডার", দোকানের কাছে গেলে দোকানের অর্ডার খোলে
    if (res.to) await pushUid(res.to, res.msg[0], res.msg[1], '', { type: res.toShop ? 'pharmacy_order' : 'pharmacy_mine', orderId: id });
    return { ok: true };
  },

  // ---------- অ্যাডমিন ----------
  async admin_pharmacies(req, user) {
    await requireAdmin(req, user);
    const snap = await db.collection('pharmacies').where('status', '==', 'pending').limit(100).get();
    return { items: snap.docs.map((d) => pharmacyView(d, true)) };
  },

  async admin_pharmacy_resolve(req, user) {
    await requireAdmin(req, user);
    const { id, approve } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`pharmacies/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await ref.update({ status: approve === true ? 'approved' : 'rejected', reviewedAt: Date.now() });
    await pushUid(id, approve === true ? 'Your pharmacy is approved' : 'Pharmacy not approved', approve === true ? 'আপনার ফার্মেসি অনুমোদিত হয়েছে' : 'ফার্মেসি অনুমোদন হয়নি', s.get('name'), { type: 'services' });
    return { ok: true };
  },
};

/** ক্রন: মাসিক ওষুধের রিমাইন্ডার, পুরোনো ছবি মোছা */
async function dailyPharmacy(now = Date.now()) {
  const due = await db.collection('medOrders').where('remindAt', '<=', now).limit(500).get();
  const list = due.docs.filter((d) => d.get('remindDays') > 0);
  const users = list.length ? await db.getAll(...[...new Set(list.map((d) => d.get('patientUid')))].map((u) => db.doc(`users/${u}`))) : [];
  const byUid = new Map(users.filter((u) => u.exists).map((u) => [u.id, u]));
  const msgs = [];
  const batch = db.batch();
  for (const d of list) {
    const u = byUid.get(d.get('patientUid'));
    batch.update(d.ref, { remindAt: now + d.get('remindDays') * 24 * 3600 * 1000 });
    if (!u) continue;
    msgs.push({
      uid: u.id,
      token: u.get('fcmToken'),
      title: R.L(u.get('lang'), 'Time to reorder your medicines', 'নিয়মিত ওষুধ আবার অর্ডারের সময়'),
      body: R.L(u.get('lang'), 'Tap to order again from the same pharmacy', 'একই ফার্মেসি থেকে আবার অর্ডার দিতে চাপুন'),
      data: { type: 'pharmacy_mine' },
    });
  }
  if (list.length) await batch.commit();
  if (msgs.length) await sendPush(msgs);
  const old = await db.collection('medOrderPhotos').where('expiresAt', '<', now).limit(500).get();
  for (let i = 0; i < old.docs.length; i += 400) {
    const b = db.batch();
    old.docs.slice(i, i + 400).forEach((d) => b.delete(d.ref));
    await b.commit();
  }
  return { reminders: msgs.length, photosDeleted: old.size };
}

module.exports = { actions, dailyPharmacy };
