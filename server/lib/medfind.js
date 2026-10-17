// "ওষুধ খুঁজুন": কোনো ওষুধ কোথাও পাওয়া না গেলে, তালিকা থেকে বেছে কাছের দোকানগুলোকে জিজ্ঞেস করা।
// দোকান এক চাপে "আছে"/"নেই" জানায়; ক্রেতা "আছে" বলা দোকানে সরাসরি ফোন করেন।
// অ্যাপ কিছু বিক্রি করে না: দাম, অর্ডার, ঠিকানা, প্রেসক্রিপশন কিছুই নেই। দোকান ক্রেতার নাম-ফোন দেখে না।
//
// medFinds/{id}: { uid, drug: {name, generic, form, company}, district, upazilaId, lat, lng, notified: [pharmacyId],
//                  answers: { pharmacyId: { have, at } }, status: 'open'|'closed', createdAt, expiresAt }
const { db, FieldValue, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');

const NOTIFY_MAX = 15;
const OPEN_MS = 6 * 3600 * 1000;
const PER_SHOP_DAY = 30;

// ---------- সরকারি (DGDA) ওষুধের তালিকা: সার্ভারের ফাইলে, প্রথম খোঁজায় পড়া ----------
let LIST = null;
function list() {
  if (LIST) return LIST;
  // require দিলে Vercel ফাইলটা নিশ্চিতভাবে ফাংশনের সাথে নেয়
  const o = require('./drugs.json');
  const rows = o.d.map((r, i) => ({ i, name: r[0], generic: r[1], form: o.f[r[2]] || '', company: o.c[r[3]] || '' }));
  LIST = { rows, lowerNames: rows.map((r) => r.name.toLowerCase()), lowerGenerics: rows.map((r) => r.generic.toLowerCase()) };
  return LIST;
}

/** নাম দিয়ে খোঁজা: ব্র্যান্ডের শুরু মিললে আগে, তারপর ব্র্যান্ডের ভেতরে, তারপর জেনেরিক */
function searchDrugs(query, limit = 15) {
  const q = String(query || '').trim().toLowerCase();
  if (q.length < 2) return [];
  const { rows, lowerNames, lowerGenerics } = list();
  const starts = [];
  const contains = [];
  const generic = [];
  for (let i = 0; i < rows.length; i++) {
    const n = lowerNames[i];
    if (n.startsWith(q)) starts.push(rows[i]);
    else if (contains.length < limit && n.includes(q)) contains.push(rows[i]);
    else if (generic.length < limit && lowerGenerics[i].includes(q)) generic.push(rows[i]);
    if (starts.length >= limit * 3) break;
  }
  starts.sort((a, b) => a.name.length - b.name.length);
  const seen = new Set();
  return [...starts, ...contains, ...generic].filter((r) => {
    const k = `${r.name}|${r.generic}|${r.form}|${r.company}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, limit);
}

async function pushUid(uid, en, bn, body, data) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists) return;
    await sendPush([{ uid, tokens: require('./firebase').tokensOf(u), title: R.L(u.get('lang'), en, bn), body: body || '', data }]);
  } catch (e) {
    console.error('med find push failed', e.message);
  }
}

const expired = (x, now = Date.now()) => x.status !== 'open' || now > (x.expiresAt || 0);

function areaLabel(x) {
  const u = UPAZILA.get(x.upazilaId);
  return [u && u.bn, x.district].filter(Boolean).join(', ');
}

const actions = {
  /** তালিকা থেকে ওষুধ খোঁজা (লেখার সাথে সাথে) */
  async drug_search(req) {
    return { items: searchDrugs(req.body?.q).map((r) => ({ i: r.i, name: r.name, generic: r.generic, form: r.form, company: r.company })) };
  },

  /** ক্রেতা: "কোন দোকানে আছে?" — কাছের সর্বোচ্চ ১৫টা দোকানে যায় */
  async med_find(req, user) {
    const b = req.body || {};
    const { rows } = list();
    const d = Number.isInteger(b.i) && b.i >= 0 && b.i < rows.length ? rows[b.i] : null;
    if (!d) throw new HttpError(400, C.tr(req, 'Choose the medicine from the list', 'তালিকা থেকে ওষুধ বেছে নিন'));
    if (!DISTRICTS.some((x) => x.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
    const lat = typeof b.lat === 'number' ? Math.round(b.lat * 100) / 100 : null;
    const lng = typeof b.lng === 'number' ? Math.round(b.lng * 100) / 100 : null;

    // উপজেলার দোকান আগে খোঁজা — বড় জেলায় ৫০০-র সীমায় কাছের দোকান যেন বাদ না পড়ে
    const docs = await require('./pharmacy').areaPharmacies(b.district, upazilaId);
    const day = R.dhakaDayKey();
    const now = Date.now();
    if (!docs.some((s) => s.id !== user.uid)) return { ok: false, noPharmacy: true };
    let shops = docs
      .filter((s) => s.id !== user.uid && s.get('findOff') !== true)
      // একটা দোকানে দিনে বেশি অনুরোধ নয়
      .filter((s) => !(s.get('findDay') === day && (s.get('findCount') || 0) >= PER_SHOP_DAY))
      .map((s) => {
        const x = s.data();
        return {
          id: s.id,
          upazilaId: x.upazilaId || null,
          open: K.pharmacyOpenNow(x, now),
          km: lat != null && typeof x.lat === 'number' ? R.distanceKm(lat, lng, x.lat, x.lng) : null,
        };
      });
    // শুধু এখন খোলা দোকান (জিজ্ঞাসা বন্ধ রাখা বা আজকের সীমা পার হওয়া দোকান বাদ) — বন্ধ দোকানে রাত ২টায় নোটিফিকেশন যায় না, আর উত্তরও আসে না
    shops = shops.filter((p) => p.open);
    if (!shops.length) return { ok: false, noneOpen: true };
    // উপজেলায় (বা ৫ কিমির মধ্যে) খোলা দোকান থাকলে শুধু সেগুলো, নইলে জেলার খোলা দোকান
    if (upazilaId) {
      const near = shops.filter((p) => p.upazilaId === upazilaId || (p.km != null && p.km <= 5));
      if (near.length) shops = near;
    }
    shops.sort((a, c) => (a.km ?? 99) - (c.km ?? 99));
    // ডেমো: ডেমোর খোঁজ শুধু ডেমো দোকানে, আসল খোঁজ শুধু আসল দোকানে (ডেমো মুছলে filter অংশ মুছবেন)
    const targets = shops.filter((x) => require('./demo').sameWorld(user.uid, x.id)).slice(0, NOTIFY_MAX);

    await useQuota(req, user.uid, 'medFind');
    const ref = db.collection('medFinds').doc();
    const drug = { name: d.name, generic: d.generic, form: d.form, company: d.company };
    await ref.set({
      uid: user.uid,
      drug,
      district: b.district,
      upazilaId,
      lat,
      lng,
      notified: targets.map((t) => t.id),
      answers: {},
      status: 'open',
      createdAt: now,
      expiresAt: now + OPEN_MS,
    });
    // দোকানের দৈনিক গণনা (নতুন দিনে আবার ১ থেকে)
    const sameDay = new Set(docs.filter((s) => s.get('findDay') === day).map((s) => s.id));
    await Promise.all(targets.map((t) => db.doc(`pharmacies/${t.id}`)
      .update(sameDay.has(t.id) ? { findCount: FieldValue.increment(1) } : { findDay: day, findCount: 1 })
      .catch(() => {})));
    await Promise.all(targets.map((t) => pushUid(t.id, 'Someone nearby is looking for a medicine', 'কাছে একজন ওষুধ খুঁজছেন', `${d.name} — আপনার কাছে আছে?`, { type: 'med_find', findId: ref.id })));
    return { ok: true, id: ref.id, notified: targets.length };
  },

  /** ক্রেতা: নিজের খোঁজ আর কোন দোকান "আছে" বলেছে */
  async med_finds_mine(req, user) {
    // পুরোনো খোঁজ রোজ মুছে যায় (dailyCleanup), তাই এখানে সব চালু/সাম্প্রতিক খোঁজই আসে
    const snap = await db.collection('medFinds').where('uid', '==', user.uid).limit(60).get();
    const docs = snap.docs.sort((a, c) => (c.get('createdAt') || 0) - (a.get('createdAt') || 0)).slice(0, 10);
    const ids = new Set();
    docs.forEach((d) => Object.entries(d.get('answers') || {}).forEach(([pid, a]) => a.have && ids.add(pid)));
    const shops = ids.size ? await db.getAll(...[...ids].map((id) => db.doc(`pharmacies/${id}`))) : [];
    const byId = new Map(shops.filter((s) => s.exists && s.get('status') === 'approved').map((s) => [s.id, s]));
    const now = Date.now();
    return {
      items: docs.map((d) => {
        const x = d.data();
        const yes = Object.entries(x.answers || {})
          .filter(([pid, a]) => a.have && byId.has(pid))
          .sort((a, c) => a[1].at - c[1].at)
          .map(([pid, a]) => {
            const s = byId.get(pid).data();
            return {
              id: pid,
              name: s.name,
              phone: K.fixPhone(s.phone),
              address: s.address || '',
              openNow: K.pharmacyOpenNow(s, now),
              open24: s.open24 === true,
              km: x.lat != null && typeof s.lat === 'number' ? Math.round(R.distanceKm(x.lat, x.lng, s.lat, s.lng) * 10) / 10 : null,
              at: a.at,
            };
          });
        return {
          id: d.id,
          drug: x.drug,
          area: areaLabel(x),
          open: !expired(x, now),
          notified: (x.notified || []).length,
          answered: Object.keys(x.answers || {}).length,
          have: yes,
          createdAt: x.createdAt,
        };
      }),
    };
  },

  async med_find_close(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`medFinds/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('uid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await ref.update({ status: 'closed', closedAt: Date.now() });
    return { ok: true };
  },

  /** দোকান: কাছের মানুষ কী খুঁজছেন (খোলা অনুরোধ) */
  async med_find_requests(req, user) {
    const shop = await db.doc(`pharmacies/${user.uid}`).get();
    if (!shop.exists || shop.get('status') !== 'approved') return { items: [], findOff: shop.exists && shop.get('findOff') === true };
    // পুরোনো খোঁজ রোজ মুছে যায় (dailyCleanup); একটা দোকানে দিনে সর্বোচ্চ ৩০টা আসে, তাই ১০০-তে আজকেরগুলো বাদ পড়ে না
    const snap = await db.collection('medFinds').where('notified', 'array-contains', user.uid).limit(100).get();
    const now = Date.now();
    const items = snap.docs
      .filter((d) => !expired(d.data(), now))
      .sort((a, c) => (c.get('createdAt') || 0) - (a.get('createdAt') || 0))
      .slice(0, 30)
      .map((d) => {
        const x = d.data();
        const mine = (x.answers || {})[user.uid];
        return {
          id: d.id,
          drug: x.drug,
          area: areaLabel(x),
          km: x.lat != null && typeof shop.get('lat') === 'number' ? Math.round(R.distanceKm(x.lat, x.lng, shop.get('lat'), shop.get('lng')) * 10) / 10 : null,
          answer: mine ? (mine.have ? 'yes' : 'no') : null,
          createdAt: x.createdAt,
        };
      });
    return { items, findOff: shop.get('findOff') === true };
  },

  /** দোকান: এক চাপে "আছে" / "নেই" */
  async med_find_answer(req, user) {
    const { id } = req.body || {};
    const have = req.body?.have === true;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const shop = await db.doc(`pharmacies/${user.uid}`).get();
    if (!shop.exists || shop.get('status') !== 'approved') throw new HttpError(403, C.tr(req, 'Your pharmacy is not approved', 'আপনার ফার্মেসি অনুমোদিত নয়'));
    const ref = db.doc(`medFinds/${id}`);
    const res = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists || !(s.get('notified') || []).includes(user.uid)) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      if (expired(s.data())) throw new HttpError(409, C.tr(req, 'This request has closed', 'এই অনুরোধ বন্ধ হয়ে গেছে'));
      const prev = (s.get('answers') || {})[user.uid];
      t.update(ref, { [`answers.${user.uid}`]: { have, at: Date.now() } });
      return { uid: s.get('uid'), drug: s.get('drug'), firstYes: have && !(prev && prev.have) };
    });
    if (res.firstYes) {
      await pushUid(res.uid, `Available at ${shop.get('name')}`, `${shop.get('name')}-এ আছে`, `${res.drug.name} — ফোন করে নিশ্চিত হয়ে নিন`, { type: 'med_find_mine', findId: id });
    }
    return { ok: true };
  },

  /** দোকান: ওষুধ খোঁজার অনুরোধ নেবে কি না */
  async pharmacy_find_setting(req, user) {
    const ref = db.doc(`pharmacies/${user.uid}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Open your pharmacy first', 'আগে নিজের ফার্মেসি যোগ করুন'));
    await ref.update({ findOff: req.body?.off === true });
    return { ok: true };
  },
};

/** খোঁজ বন্ধ হওয়ার ২ দিন পর মুছে ফেলা — নইলে দোকান আর ক্রেতার তালিকায় পুরোনো খোঁজ জমে নতুনগুলো বাদ পড়ে */
const KEEP_AFTER_EXPIRY_MS = 2 * 24 * 3600 * 1000;
async function dailyCleanup(now = Date.now()) {
  let deleted = 0;
  for (let round = 0; round < 5; round++) {
    const snap = await db.collection('medFinds').where('expiresAt', '<', now - KEEP_AFTER_EXPIRY_MS).limit(400).get();
    if (snap.empty) break;
    const b = db.batch();
    snap.docs.forEach((d) => b.delete(d.ref));
    await b.commit();
    deleted += snap.size;
    if (snap.size < 400) break;
  }
  return deleted;
}

module.exports = { actions, searchDrugs, dailyCleanup };
