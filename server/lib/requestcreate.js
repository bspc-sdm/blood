// রক্তের রিকোয়েস্ট তৈরি — সার্ভার দিয়ে, যাতে সব সীমা আর তথ্যের যাচাই এক জায়গায় হয়
// (একসাথে খোলা রিকোয়েস্ট, দিনে কয়টা, তারিখ ৩১ দিনের মধ্যে, ভুয়া "ডোনার পাওয়া গেছে" ইত্যাদি বসানো যায় না)।
// রক্তপ্রার্থীর ফোন আলাদা private/contact নথিতে — শুধু রাজি ডোনাররা দেখতে পান (Firestore নিয়ম)।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');

const GEOHASH_RE = /^[0-9bcdefghjkmnpqrstuvwxyz]{1,12}$/;

const actions = {
  async request_create(req, user) {
    const b = req.body || {};
    const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
    const now = Date.now();
    const lat = typeof b.lat === 'number' ? Math.round(b.lat * 100) / 100 : null;
    const lng = typeof b.lng === 'number' ? Math.round(b.lng * 100) / 100 : null;
    const r = {
      patientGroup: b.patientGroup,
      bags: b.bags,
      hospital: typeof b.hospital === 'string' ? b.hospital.trim().slice(0, 120) : '',
      placeName: b.placeName,
      lat,
      lng,
      neededByMillis: Number.isInteger(b.neededByMillis) ? b.neededByMillis : null,
      neededAtMillis: Number.isInteger(b.neededAtMillis) ? b.neededAtMillis : null,
    };
    const invalid = R.validateRequest(r, now);
    if (invalid) throw new HttpError(400, C.tr(req, require('./i18n').toEnglish(invalid), invalid));
    // অতীতের তারিখে নতুন রিকোয়েস্ট নয় (আজ বা সামনে)
    if (r.neededByMillis + R.DAY_MS <= now) throw err('The date has already passed', 'তারিখটা পার হয়ে গেছে');
    const phone = K.normalizeBd(b.requesterPhone);
    if (!phone) throw err('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন');
    const name = C.cleanText(b.requesterName, 80);
    if (!name) throw err('Enter your name', 'নাম লিখুন');
    const note = typeof b.note === 'string' ? b.note.trim().slice(0, 300) : '';
    const geohash = typeof b.geohash === 'string' && GEOHASH_RE.test(b.geohash) ? b.geohash : null;

    const stateRef = db.doc(`serverState/${user.uid}`);
    const day = R.dhakaDayKey(now);
    const ref = db.collection('requests').doc();
    await db.runTransaction(async (t) => {
      const [state, mine] = await Promise.all([
        t.get(stateRef),
        t.get(db.collection('requests').where('requesterUid', '==', user.uid).where('status', '==', 'open')),
      ]);
      const open = mine.docs.filter((d) => R.isActive(d.data(), now)).length;
      if (open >= R.MAX_OPEN_REQUESTS) {
        throw new HttpError(429, C.tr(req, `You can keep up to ${R.MAX_OPEN_REQUESTS} open requests at a time`, `একসাথে সর্বোচ্চ ${R.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়`));
      }
      const used = state.exists && state.get('createDay') === day ? state.get('createCount') || 0 : 0;
      if (used >= R.MAX_REQUESTS_PER_DAY) {
        throw new HttpError(429, C.tr(req, `Up to ${R.MAX_REQUESTS_PER_DAY} requests a day. If it's urgent, tell people you know directly.`,
          `দিনে সর্বোচ্চ ${R.MAX_REQUESTS_PER_DAY}টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।`));
      }
      t.set(stateRef, { createDay: day, createCount: used + 1 }, { merge: true });
      t.set(ref, {
        requesterUid: user.uid,
        requesterName: name,
        patientGroup: r.patientGroup,
        bags: r.bags,
        hospital: r.hospital,
        placeName: r.placeName,
        lat,
        lng,
        ...(geohash ? { geohash } : {}),
        neededByMillis: r.neededByMillis,
        neededAtMillis: r.neededAtMillis,
        urgent: b.urgent === true,
        travelFare: b.travelFare === true,
        note,
        status: 'open',
        createdAtMillis: now,
        createdAt: FieldValue.serverTimestamp(),
      });
      t.set(ref.collection('private').doc('contact'), { phone: K.localBd(phone), name });
    });
    return { ok: true, id: ref.id };
  },
};

const REOPEN_MS = 60 * 60 * 1000;

/** ভুল করে বন্ধ করলে ১ ঘণ্টার মধ্যে আবার খোলা (রক্তপ্রার্থী নিজে, সময় এখনো বাকি থাকলে) */
actions.request_reopen = async function requestReopen(req, user) {
  const id = req.body?.requestId;
  if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
  const ref = db.doc(`requests/${id}`);
  const now = Date.now();
  const r = await db.runTransaction(async (t) => {
    const s = await t.get(ref);
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const x = s.data();
    if (x.requesterUid !== user.uid) throw new HttpError(403, C.tr(req, 'This is not your request', 'এটা আপনার রিকোয়েস্ট না'));
    if (x.status === 'open') return null;
    const byRequester = ['requester', 'not_needed'].includes(x.closedBy) || x.closedBy === undefined;
    if (!byRequester || !x.closedAt || now - x.closedAt > REOPEN_MS || R.activeUntil(x) <= now) {
      throw new HttpError(400, C.tr(req, 'This request can no longer be opened again. Create a new one if needed.', 'এই রিকোয়েস্ট আর খোলা যায় না। দরকার হলে নতুন রিকোয়েস্ট দিন।'));
    }
    t.update(ref, { status: 'open', closedAt: FieldValue.delete(), closedBy: FieldValue.delete(), reopenedAt: now });
    return x;
  });
  if (r) await require('./requestend').notifyEnded(id, r, 'reopened');
  return { ok: true };
};

module.exports = { actions };
