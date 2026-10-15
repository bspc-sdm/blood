// ব্লাড ব্যাংক: সরকারি হাসপাতালের ব্লাড ব্যাংক, বেসরকারি (লাইসেন্স সহ) আর স্বেচ্ছাসেবী সংগঠন।
// মালিক/দায়িত্বপ্রাপ্ত কেউ যোগ করেন, অথবা অ্যাডমিন নিজে যোগ করেন; অ্যাডমিন অনুমোদন দিলে তালিকায় আসে।
// মানুষ এলাকা দিয়ে খুঁজে সরাসরি ফোন করেন। অ্যাপে কোনো অর্ডার/টাকা নেই, স্টকের হিসাবও নেই।
//
// bloodBanks/{id}: { name, kind, phone, district, upazilaId, address, open24, openAt, closeAt, services, licenseNo,
//                    ownerUid|null, status: pending|approved|rejected|suspended, addedByAdmin?, createdAt }
// bloodBankCalls/{callerUid}_{id}, bloodBankStats/{id}
const { db, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');
const S = require('./sellerstats');

const KINDS = ['govt', 'private', 'org'];
const SERVICES = ['whole_blood', 'components', 'platelets', 'screening', 'donate_here'];
const MAX_PER_OWNER = 3;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

function view(d, mine = false) {
  const x = d.data();
  return {
    id: d.id,
    name: x.name,
    kind: x.kind,
    phone: K.fixPhone(x.phone),
    district: x.district,
    upazilaId: x.upazilaId || null,
    address: x.address || '',
    open24: x.open24 === true,
    openAt: x.openAt || '',
    closeAt: x.closeAt || '',
    openNow: K.pharmacyOpenNow(x),
    services: x.services || [],
    ...(mine ? { licenseNo: x.licenseNo || '', status: x.status } : {}),
  };
}

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/** অ্যাপ বা প্যানেল থেকে পাঠানো তথ্য যাচাই */
function parse(req, b) {
  const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
  const name = C.cleanText(b.name, 100);
  if (!name) throw err('Enter the blood bank name', 'ব্লাড ব্যাংকের নাম লিখুন');
  const kind = KINDS.includes(b.kind) ? b.kind : null;
  if (!kind) throw err('Choose the type', 'ধরন বেছে নিন');
  const phone = K.normalizeBd(b.phone);
  if (!phone) throw err('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন');
  if (!DISTRICTS.some((d) => d.bn === b.district)) throw err('Choose a district', 'জেলা বেছে নিন');
  const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
  const address = C.cleanText(b.address, 200);
  if (!address) throw err('Write the address', 'ঠিকানা লিখুন');
  const licenseNo = C.cleanText(b.licenseNo, 60);
  // বেসরকারি ব্লাড ব্যাংকের সরকারি লাইসেন্স লাগে
  if (kind === 'private' && !licenseNo) throw err('Enter the blood bank licence number', 'ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন');
  const open24 = b.open24 === true;
  const openAt = TIME_RE.test(b.openAt) ? b.openAt : '';
  const closeAt = TIME_RE.test(b.closeAt) ? b.closeAt : '';
  if (!open24 && (!openAt || !closeAt || openAt === closeAt)) throw err('Set the opening hours or choose 24 hours', 'খোলার সময় দিন অথবা ২৪ ঘণ্টা বেছে নিন');
  const services = K.pickCodes(b.services, SERVICES, SERVICES.length);
  if (!services.length) throw err('Choose the services', 'কী কী সেবা দেন বেছে নিন');
  return {
    name, kind, phone: K.localBd(phone), district: b.district, upazilaId, address, licenseNo,
    open24, openAt: open24 ? '' : openAt, closeAt: open24 ? '' : closeAt, services, updatedAt: Date.now(),
  };
}

async function pushOwner(uid, en, bn, body) {
  if (!uid) return;
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists || !u.get('fcmToken')) return;
    await require('./firebase').sendPush([{ uid, token: u.get('fcmToken'), title: R.L(u.get('lang'), en, bn), body, data: { type: 'services' } }]);
  } catch (e) {
    console.error('blood bank push failed', e.message);
  }
}

const actions = {
  /** জেলার অনুমোদিত ব্লাড ব্যাংক: খোলা আগে, নিজের উপজেলা আগে, সরকারি আগে */
  async bloodbanks(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const snap = await db.collection('bloodBanks').where('district', '==', b.district).where('status', '==', 'approved').limit(300).get();
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    let items = snap.docs.map((d) => view(d));
    if (b.open24 === true) items = items.filter((x) => x.open24);
    const rank = (x) => (x.openNow ? 4 : 0) + (upazilaId && x.upazilaId === upazilaId ? 2 : 0) + (x.kind === 'govt' ? 1 : 0);
    items.sort((x, y) => rank(y) - rank(x) || String(x.name).localeCompare(String(y.name)));
    return { items };
  },

  async bloodbank_mine(req, user) {
    const snap = await db.collection('bloodBanks').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    return { items: snap.docs.map((d) => view(d, true)) };
  },

  /** নতুন যোগ বা বদল। নাম, ফোন বা লাইসেন্স বদলালে আবার যাচাই */
  async bloodbank_save(req, user) {
    const b = req.body || {};
    if (b.termsAccepted !== true) throw new HttpError(400, C.tr(req, 'Please accept the terms', 'শর্তে সম্মতি দিন'));
    const data = parse(req, b);
    if (b.id) {
      if (!R.isValidId(b.id)) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const ref = db.doc(`bloodBanks/${b.id}`);
      const s = await ref.get();
      if (!s.exists || s.get('ownerUid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      if (s.get('status') === 'suspended') {
        throw new HttpError(403, C.tr(req, 'This blood bank is suspended. You can appeal from Profile → Help & appeals.', 'এই ব্লাড ব্যাংক বন্ধ করা আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
      }
      await require('./sanctions').checkListing(req, 'bloodbank', data, user.email, false);
      const { changed, approvedName } = K.reviewNeeded(s, data, ['phone', 'licenseNo', 'kind']);
      await ref.update({ ...data, ...(approvedName ? { approvedName } : {}), ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    const mine = await db.collection('bloodBanks').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    if (mine.size >= MAX_PER_OWNER) throw new HttpError(400, C.tr(req, 'Up to 3 blood banks per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক'));
    await require('./sanctions').checkListing(req, 'bloodbank', data, user.email, true);
    await useQuota(req, user.uid, 'bloodbank');
    await db.collection('bloodBanks').add({ ...data, ownerUid: user.uid, status: 'pending', createdAt: Date.now() });
    return { ok: true, pending: true };
  },

  async bloodbank_delete(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) return { ok: true };
    const ref = db.doc(`bloodBanks/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('ownerUid') !== user.uid) return { ok: true };
    if (s.get('status') === 'suspended') {
      throw new HttpError(403, C.tr(req, 'A suspended blood bank can\'t be removed. You can appeal from Profile → Help & appeals.', 'বন্ধ করা ব্লাড ব্যাংক সরানো যায় না। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
    }
    await Promise.all([ref.delete(), db.doc(`bloodBankStats/${id}`).delete()]);
    return { ok: true };
  },

  /** ফোন বাটন চাপলে (মালিকের হিসাবের জন্য; একই মানুষ ১০ মিনিটে একবার) */
  async bloodbank_called(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) return { ok: true };
    const s = await db.doc(`bloodBanks/${id}`).get();
    if (!s.exists || s.get('ownerUid') === user.uid) return { ok: true };
    const ref = db.doc(`bloodBankCalls/${user.uid}_${id}`);
    const prev = await ref.get();
    const now = Date.now();
    await ref.set({ uid: user.uid, bankId: id, at: now }, { merge: true });
    if (!prev.exists || now - (prev.get('at') || 0) > 10 * 60 * 1000) await S.bumpCalls('bloodBankStats', id, now, !prev.exists);
    return { ok: true };
  },

  /** মালিকের নিজের হিসাব */
  async bloodbank_stats(req, user) {
    const mine = await db.collection('bloodBanks').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    const items = await Promise.all(mine.docs.map(async (d) => {
      const [st, reports] = await Promise.all([
        db.doc(`bloodBankStats/${d.id}`).get(),
        db.collection('modReports').where('targetPath', '==', `bloodBanks/${d.id}`).count().get(),
      ]);
      return { id: d.id, name: d.get('name'), status: d.get('status'), ...S.callSummary(st), reports: reports.data().count };
    }));
    return { items };
  },

  // ---------- অ্যাডমিন ----------
  async admin_bloodbanks(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'approved', 'suspended', 'rejected'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('bloodBanks').where('status', '==', status).limit(300).get();
    return {
      items: snap.docs.map((d) => ({
        ...view(d, true),
        ownerUid: d.get('ownerUid') || null,
        addedByAdmin: d.get('addedByAdmin') === true,
        createdAt: d.get('createdAt') || null,
        reviewedAt: (status === 'suspended' && d.get('suspendedAt')) || d.get('reviewedAt') || null,
      })),
    };
  },

  async admin_bloodbank_resolve(req, user) {
    await requireAdmin(req, user);
    const { id } = req.body || {};
    const status = ['approved', 'rejected', 'suspended'].includes(req.body?.status) ? req.body.status : req.body?.approve === true ? 'approved' : 'rejected';
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`bloodBanks/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (s.get('status') === status) return { ok: true, unchanged: true };
    await ref.update({ status, reviewedAt: Date.now(), reviewedBy: user.uid, ...(status === 'suspended' ? { suspendedAt: Date.now() } : {}), ...(status === 'approved' ? { approvedName: s.get('name') } : {}) });
    await require('./sanctions').onStatusChange('bloodbank', s.data(), s.get('status'), status);
    const en = status === 'approved' ? 'Your blood bank is now listed' : status === 'suspended' ? 'Your blood bank was suspended. You can appeal from Profile → Help & appeals.' : 'Blood bank not approved';
    const bn = status === 'approved' ? 'আপনার ব্লাড ব্যাংক তালিকায় এসেছে' : status === 'suspended' ? 'আপনার ব্লাড ব্যাংক বন্ধ করা হয়েছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।' : 'ব্লাড ব্যাংক অনুমোদন হয়নি';
    await pushOwner(s.get('ownerUid'), en, bn, s.get('name'));
    return { ok: true };
  },

  /** প্যানেলের ফর্মের জন্য জেলার নাম */
  async admin_districts(req, user) {
    await requireAdmin(req, user);
    return { items: DISTRICTS.map((d) => d.bn).sort((a, b) => a.localeCompare(b, 'bn')) };
  },

  /** অ্যাডমিন নিজে চেনা ব্লাড ব্যাংক যোগ করেন (সাথে সাথে তালিকায়, কোনো মালিক নেই) */
  async admin_bloodbank_add(req, user) {
    await requireAdmin(req, user);
    const data = parse(req, req.body || {});
    const ref = await db.collection('bloodBanks').add({ ...data, ownerUid: null, addedByAdmin: true, status: 'approved', createdAt: Date.now(), reviewedAt: Date.now(), reviewedBy: user.uid });
    return { ok: true, id: ref.id };
  },
};

module.exports = { actions, KINDS, SERVICES };
