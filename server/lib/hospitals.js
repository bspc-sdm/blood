// হাসপাতালের তালিকা: অনুমোদিত হাসপাতাল (hospitals), যে কেউ যোগ করার অনুরোধ (hospitalRequests), অ্যাডমিন অনুমোদন।
// ডুপ্লিকেট ঠেকানো: ইংরেজি নাম থেকে সাধারণ শব্দ বাদ দিয়ে তুলনা, একই জেলা/উপজেলায় প্রায় একই নাম হলে আটকানো।
// এলাকার তথ্য (geo.json): github.com/nuhil/bangladesh-geocode (MIT License, Copyright (c) 2014 Nuhil Mehdy)
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const DISTRICTS = require('./districts');
const DIR = require('./hospital-dir');

const TYPES = [
  'govt_mch', // সরকারি মেডিকেল কলেজ/বিশ্ববিদ্যালয় হাসপাতাল
  'govt_specialized', // সরকারি বিশেষায়িত/ইনস্টিটিউট হাসপাতাল
  'govt_district', // জেলা/সদর হাসপাতাল
  'govt_uhc', // উপজেলা স্বাস্থ্য কমপ্লেক্স
  'govt_hospital', // অন্যান্য সরকারি হাসপাতাল
  'private_hospital',
  'clinic',
  'diagnostic',
];
const USER_TYPES = ['govt_hospital', 'private_hospital', 'clinic', 'diagnostic', 'govt_mch', 'govt_specialized', 'govt_district', 'govt_uhc'];

const { normalizeName, similarity, isDuplicate, UPAZILA, UNION, DUP_THRESHOLD } = require('./hospital-match');
const { clip } = require('./text');

function hospital(d) {
  const x = typeof d.data === 'function' ? d.data() : d;
  return {
    id: d.id,
    nameEn: x.nameEn,
    nameBn: x.nameBn || '',
    type: x.type,
    district: x.district,
    upazilaId: x.upazilaId || null,
    unionId: x.unionId || null,
    address: x.address || '',
    phone: x.phone || '',
  };
}

// অ্যাপ থেকে যোগ হওয়া (অ্যাডমিন অনুমোদিত) হাসপাতাল অল্প, তাই কয়েক মিনিট মনে রাখা হয়
const userCache = new Map();
const USER_CACHE_MS = 5 * 60 * 1000;

async function userAdded(district, fresh = false) {
  const c = userCache.get(district);
  if (!fresh && c && Date.now() - c.at < USER_CACHE_MS) return c.list;
  const snap = await db.collection('hospitals').where('district', '==', district).where('source', '==', 'user').get();
  const list = snap.docs.filter((d) => d.get('status') !== 'removed').map(hospital);
  userCache.set(district, { at: Date.now(), list });
  return list;
}

/** জেলার সব হাসপাতাল: DGHS-এর স্থির তালিকা + অ্যাপ থেকে যোগ হওয়া */
async function approvedIn(district, fresh = false) {
  return [...DIR.inDistrict(district), ...(await userAdded(district, fresh))];
}

/** id দিয়ে একটা হাসপাতাল (না পেলে null) */
async function findHospital(id) {
  if (typeof id !== 'string') return null;
  if (id.startsWith('dghs_')) return DIR.find(id);
  if (!R.isValidId(id)) return null;
  const s = await db.doc(`hospitals/${id}`).get();
  return s.exists && s.get('status') !== 'removed' ? hospital(s) : null;
}

function validLocation(req, district, upazilaId, unionId) {
  if (!DISTRICTS.some((d) => d.bn === district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
  if (upazilaId != null) {
    const u = UPAZILA.get(upazilaId);
    if (!u || u.district !== district) throw new HttpError(400, C.tr(req, 'Choose a valid upazila', 'সঠিক উপজেলা বেছে নিন'));
  }
  if (unionId != null) {
    const n = UNION.get(unionId);
    if (!n || n.upazilaId !== upazilaId) throw new HttpError(400, C.tr(req, 'Choose a valid union', 'সঠিক ইউনিয়ন বেছে নিন'));
  }
}

const actions = {
  /** জেলার (চাইলে উপজেলার) অনুমোদিত হাসপাতাল */
  async hospitals(req) {
    const { district } = req.body || {};
    const upazilaId = Number.isInteger(req.body?.upazilaId) ? req.body.upazilaId : null;
    if (!DISTRICTS.some((d) => d.bn === district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    let list = await approvedIn(district);
    if (upazilaId) list = list.filter((h) => h.upazilaId === upazilaId || h.upazilaId == null);
    list.sort((a, b) => a.nameEn.localeCompare(b.nameEn));
    return { items: list };
  },

  /** যে কেউ হাসপাতাল যোগ করার অনুরোধ */
  async hospital_request(req, user) {
    const b = req.body || {};
    const nameEn = C.cleanText(b.nameEn, 120);
    if (!nameEn || !/[A-Za-z]/.test(nameEn)) {
      throw new HttpError(400, C.tr(req, 'Enter the hospital name in English', 'হাসপাতালের নাম ইংরেজিতে লিখুন'));
    }
    const nameBn = typeof b.nameBn === 'string' ? clip(b.nameBn.trim(), 120) : '';
    if (!USER_TYPES.includes(b.type)) throw new HttpError(400, C.tr(req, 'Choose the type', 'ধরন বেছে নিন'));
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const unionId = Number.isInteger(b.unionId) ? b.unionId : null;
    validLocation(req, b.district, upazilaId, unionId);
    const address = typeof b.address === 'string' ? clip(b.address.trim(), 200) : '';
    const phone = typeof b.phone === 'string' ? b.phone.replace(/[^0-9+]/g, '').slice(0, 15) : '';
    const item = { nameEn, district: b.district, upazilaId };

    // ১) আগে থেকেই অনুমোদিত?
    const existing = (await approvedIn(b.district, true)).find((h) => isDuplicate(item, h));
    if (existing) return { ok: false, duplicateOf: existing };

    // ২) একই হাসপাতালের অনুরোধ অপেক্ষায়? তাহলে সেটার সাথে যোগ
    const pending = await db.collection('hospitalRequests').where('district', '==', b.district).get();
    const same = pending.docs.find((d) => d.get('status') === 'pending' && isDuplicate(item, d.data()));
    if (same) {
      if (!(same.get('requesterUids') || []).includes(user.uid)) {
        await same.ref.update({ requesterUids: FieldValue.arrayUnion(user.uid), count: FieldValue.increment(1) });
      }
      return { ok: true, merged: true };
    }

    await useQuotaLocal(req, user.uid);
    await db.collection('hospitalRequests').add({
      nameEn,
      nameBn,
      type: b.type,
      district: b.district,
      upazilaId,
      unionId,
      address,
      phone,
      normKey: normalizeName(nameEn),
      requesterUids: [user.uid],
      count: 1,
      status: 'pending',
      createdAt: Date.now(),
    });
    return { ok: true };
  },

  /** অ্যাডমিন: অপেক্ষায় থাকা অনুরোধ, সম্ভাব্য ডুপ্লিকেট সহ */
  async admin_hospital_requests(req, user) {
    await requireAdmin(req, user);
    const snap = await db.collection('hospitalRequests').where('status', '==', 'pending').limit(100).get();
    const byDistrict = new Map();
    const items = [];
    for (const d of snap.docs) {
      const r = d.data();
      if (!byDistrict.has(r.district)) byDistrict.set(r.district, await approvedIn(r.district));
      const dups = byDistrict.get(r.district)
        .filter((h) => r.district === h.district && similarity(r.nameEn, h.nameEn) >= 0.7)
        .slice(0, 5);
      items.push({ id: d.id, ...hospital({ id: d.id, ...r }), count: r.count || 1, possibleDuplicates: dups });
    }
    return { items };
  },

  /** অ্যাডমিন: অনুমোদন / বাতিল / আগের হাসপাতালের সাথে মেলানো */
  async admin_hospital_resolve(req, user) {
    await requireAdmin(req, user);
    const { id, op, targetId } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`hospitalRequests/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('status') !== 'pending') throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const r = s.data();
    // যাঁরা চেয়েছিলেন তাঁদের জানানো (চেম্বার সেভ করতে এই হাসপাতালের অপেক্ষায় থাকতে পারেন)
    const tell = (en, bn) => require('./requestend').pushUsers(r.requesterUids || [], (u, lang) => ({
      title: R.L(lang, en, bn),
      body: r.nameBn && lang !== 'en' ? r.nameBn : r.nameEn || r.nameBn || '',
      data: { type: 'hospital' },
    }));
    if (op === 'approve') {
      const h = await db.collection('hospitals').add({
        nameEn: r.nameEn,
        nameBn: r.nameBn || '',
        type: r.type,
        district: r.district,
        upazilaId: r.upazilaId || null,
        unionId: r.unionId || null,
        address: r.address || '',
        phone: r.phone || '',
        normKey: r.normKey,
        source: 'user',
        status: 'approved',
        createdAt: Date.now(),
      });
      await ref.update({ status: 'approved', hospitalId: h.id, reviewedAt: Date.now() });
      userCache.delete(r.district);
      await tell('The hospital you asked for is now in the list', 'আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে');
      return { ok: true, hospitalId: h.id };
    }
    if (op === 'merge') {
      if (!(await findHospital(targetId))) {
        throw new HttpError(400, C.tr(req, 'Choose the existing hospital', 'আগের হাসপাতালটা বেছে নিন'));
      }
      await ref.update({ status: 'merged', hospitalId: targetId, reviewedAt: Date.now() });
      await tell('The hospital you asked for was already in the list — search it again', 'আপনার চাওয়া হাসপাতাল আগেই তালিকায় ছিল — আবার খুঁজে দেখুন');
      return { ok: true };
    }
    if (op === 'reject') {
      await ref.update({ status: 'rejected', reviewedAt: Date.now() });
      await tell('Hospital request not approved', 'হাসপাতাল যোগের অনুরোধ অনুমোদন হয়নি');
      return { ok: true };
    }
    throw new HttpError(400, C.tr(req, 'Unknown action', 'অজানা কাজ'));
  },
};

async function requireAdmin(req, user) {
  // আবর্তনশীল require এড়াতে এখানে
  const { isAdmin } = require('./admin');
  if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

async function useQuotaLocal(req, uid) {
  const { useQuota } = require('./community-db');
  await useQuota(req, uid, 'hospital');
}

module.exports = { actions, findHospital, normalizeName, similarity, isDuplicate, TYPES, UPAZILA, UNION, DUP_THRESHOLD };
