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
    closedDays: Array.isArray(x.closedDays) ? x.closedDays : [],
    ...(mine ? { licenseNo: x.licenseNo || '', status: x.status, statusReason: x.statusReason || '' } : {}),
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
    ...(['pending', 'rejected'].includes(status) ? { verify: await require('./verify').panelInfo('bloodbank', d.id, d.data()).catch(() => null) } : {}),
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

/** অ্যাপ বা প্যানেল থেকে পাঠানো তথ্য যাচাই */
function parse(req, b) {
  const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
  const name = C.cleanText(b.name, 100);
  if (!name) throw err('Enter the blood bank name', 'ব্লাড ব্যাংকের নাম লিখুন');
  const kind = KINDS.includes(b.kind) ? b.kind : null;
  if (!kind) throw err('Choose the type', 'ধরন বেছে নিন');
  // হাসপাতালের ব্লাড ব্যাংকের অনেক সময় শুধু ল্যান্ডলাইন বা হটলাইন থাকে
  const phone = K.contactPhone(b.phone);
  if (!phone) throw err('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন');
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
    name, kind, phone, district: b.district, upazilaId, address, licenseNo,
    open24, openAt: open24 ? '' : openAt, closeAt: open24 ? '' : closeAt, services, updatedAt: Date.now(),
    // পুরোনো অ্যাপ এই ঘর পাঠায় না — তখন আগের মান থাকে
    ...(Array.isArray(b.closedDays) ? { closedDays: K.pickClosedDays(b.closedDays) } : {}),
  };
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
    return { items: await Promise.all(snap.docs.map((d) => withDocs('bloodbank', d, view(d, true)))) };
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
      const { changed, approvedName } = K.reviewNeeded(s, data, ['phone', 'licenseNo', 'kind', 'district', 'address']);
      await require('./verify').saveDocs(req, 'bloodbank', b.id, user.uid, { ...s.data(), ...data }, b.docs, false);
      await ref.update({ ...data, ...(approvedName ? { approvedName } : {}), ...K.keepApproved(s, data, changed), ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    const mine = await db.collection('bloodBanks').where('ownerUid', '==', user.uid).limit(MAX_PER_OWNER).get();
    if (mine.size >= MAX_PER_OWNER) throw new HttpError(400, C.tr(req, 'Up to 3 blood banks per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক'));
    await require('./sanctions').checkListing(req, 'bloodbank', data, user.email, true);
    require('./verify').validateDocs(req, 'bloodbank', data, b.docs, true);
    await useQuota(req, user.uid, 'bloodbank');
    const ref = db.collection('bloodBanks').doc();
    const docsHave = await require('./verify').saveDocs(req, 'bloodbank', ref.id, user.uid, data, b.docs, true);
    await ref.set({ ...data, ownerUid: user.uid, status: 'pending', docsHave: docsHave || {}, createdAt: Date.now() });
    return { ok: true, pending: true, id: ref.id };
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
    await require('./moderation').assertNoOpenReports(req, `bloodBanks/${id}`);
    await Promise.all([ref.delete(), db.doc(`bloodBankStats/${id}`).delete()]);
    await require('./verify').deleteDocs('bloodbank', id, s.data()).catch(() => {});
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
        require('./moderation').reportCount(`bloodBanks/${d.id}`).then((n) => ({ data: () => ({ count: n }) })),
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
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...view(d, true),
        ownerUid: d.get('ownerUid') || null,
        addedByAdmin: d.get('addedByAdmin') === true,
        createdAt: d.get('createdAt') || null,
        reviewedAt: (status === 'suspended' && d.get('suspendedAt')) || d.get('reviewedAt') || null,
        openAt: d.get('openAt') || '',
        closeAt: d.get('closeAt') || '',
        ...(await adminExtras('bloodBanks', d, status)),
      }))),
    };
  },

  async admin_bloodbank_resolve(req, user) {
    await requireAdmin(req, user);
    return require('./listingreview').resolveListing(req, user, 'bloodbank', 'bloodBanks', req.body?.id);
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

  /** অ্যাডমিন যেকোনো ব্লাড ব্যাংকের তথ্য ঠিক করেন (ভুল নম্বর, সময়); মালিক থাকলে তাঁকে জানানো হয় */
  async admin_bloodbank_edit(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`bloodBanks/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const data = parse(req, req.body || {});
    // প্যানেলের ফর্মে উপজেলা নেই — না পাঠালে আগেরটা থাকে
    if (req.body?.upazilaId === undefined) data.upazilaId = s.get('upazilaId') || null;
    const approved = s.get('status') === 'approved';
    await ref.update({
      ...data,
      editedByAdmin: Date.now(),
      // অ্যাডমিনের ঠিক করা তথ্যই এখন "অনুমোদিত"
      ...(approved ? { approvedName: data.name, lastApproved: require('./listingreview').snapshot({ ...s.data(), ...data }) } : {}),
    });
    if (s.get('ownerUid')) {
      await require('./requestend').pushUsers([s.get('ownerUid')], (u, lang) => ({
        title: R.L(lang, 'The Lifolo team updated your blood bank details', 'Lifolo টিম আপনার ব্লাড ব্যাংকের তথ্য ঠিক করেছে'),
        body: data.name,
        data: { type: 'services', kind: 'bloodbank' },
      }));
    }
    return { ok: true };
  },

  /** অ্যাডমিন মুছে ফেলেন (বন্ধ হয়ে যাওয়া, দুবার যোগ হওয়া) — বন্ধ (suspended) নয়, তাই নিষেধাজ্ঞা থাকে না */
  async admin_bloodbank_delete(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`bloodBanks/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    await Promise.all([ref.delete(), db.doc(`bloodBankStats/${id}`).delete().catch(() => {})]);
    await require('./verify').deleteDocs('bloodbank', id, s.data()).catch(() => {});
    // এর খোলা রিপোর্টগুলো আর দরকার নেই
    const reps = await db.collection('modReports').where('targetPath', '==', `bloodBanks/${id}`).where('status', '==', 'open').limit(200).get();
    if (!reps.empty) {
      const batch = db.batch();
      reps.docs.forEach((d) => batch.update(d.ref, { status: 'dismissed', resolvedAt: Date.now() }));
      await batch.commit();
    }
    if (s.get('ownerUid')) {
      await require('./requestend').pushUsers([s.get('ownerUid')], (u, lang) => ({
        title: R.L(lang, 'Your blood bank was removed from the list', 'আপনার ব্লাড ব্যাংক তালিকা থেকে সরানো হয়েছে'),
        body: [s.get('name'), C.cleanText(req.body?.reason, 200) ? R.L(lang, `Reason: ${req.body.reason.trim()}`, `কারণ: ${req.body.reason.trim()}`) : ''].filter(Boolean).join(' · '),
        data: { type: 'services', kind: 'bloodbank' },
      }));
    }
    return { ok: true };
  },
};

module.exports = { actions, KINDS, SERVICES };
