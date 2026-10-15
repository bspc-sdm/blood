// রক্তপ্রার্থী নিজের খোলা রিকোয়েস্ট এডিট করলে: ব্যাগ, হাসপাতাল, তারিখ, জরুরি, অতিরিক্ত তথ্য।
// রক্তের গ্রুপ আর এলাকা বদলানো যায় না (ডোনার বাছাই এগুলো দিয়েই হয়েছে) — সেজন্য বন্ধ করে নতুন রিকোয়েস্ট দিতে হবে।
// যারা আগেই "রক্ত দেব" চেপেছেন তাদের পরিবর্তনের নোটিফিকেশন যায়।
const { db, FieldValue, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
const R = require('../lib/rules');

const MAX_EDITS = 10; // একটা রিকোয়েস্ট সর্বোচ্চ ১০ বার এডিট (রাজি ডোনারদের বারবার নোটিফিকেশন দিয়ে বিরক্ত করা আটকাতে)

function readChanges(body) {
  const c = {};
  if (body.bags !== undefined) {
    if (!Number.isInteger(body.bags) || body.bags < 1 || body.bags > 10) throw new HttpError(400, 'ব্যাগের সংখ্যা ঠিক নেই');
    c.bags = body.bags;
  }
  if (body.hospital !== undefined) {
    const h = typeof body.hospital === 'string' ? body.hospital.trim() : '';
    if (!h || h.length > 120) throw new HttpError(400, 'হাসপাতালের নাম ঠিক নেই');
    c.hospital = h;
  }
  if (body.neededByMillis !== undefined) {
    if (!Number.isInteger(body.neededByMillis)) throw new HttpError(400, 'তারিখ ঠিক নেই');
    if (body.neededByMillis < R.todayDhakaUtcMidnight()) throw new HttpError(400, 'আজ বা পরের কোনো তারিখ দিন');
    c.neededByMillis = body.neededByMillis;
  }
  if (body.neededAtMillis !== undefined) {
    if (!Number.isInteger(body.neededAtMillis)) throw new HttpError(400, 'সময় ঠিক নেই');
    c.neededAtMillis = body.neededAtMillis;
  }
  if (body.travelFare !== undefined) {
    if (typeof body.travelFare !== 'boolean') throw new HttpError(400, 'তথ্য ঠিক নেই');
    c.travelFare = body.travelFare;
  }
  if (body.urgent !== undefined) {
    if (typeof body.urgent !== 'boolean') throw new HttpError(400, 'জরুরি ঠিক নেই');
    c.urgent = body.urgent;
  }
  // যোগাযোগ নম্বর (রোগীর লোকের নম্বরও হতে পারে) — রাজি ডোনাররা এই নম্বর দেখেন
  if (body.contactPhone !== undefined) {
    const p = require('../lib/care').normalizeBd(body.contactPhone);
    if (!p) throw new HttpError(400, 'সঠিক মোবাইল নম্বর দিন');
    c.contactPhone = require('../lib/care').localBd(p);
  }
  if (body.note !== undefined) {
    if (typeof body.note !== 'string' || body.note.length > 300) throw new HttpError(400, 'অতিরিক্ত তথ্য ৩০০ অক্ষরের মধ্যে লিখুন');
    c.note = body.note.trim();
  }
  return c;
}

/** ডোনারদের জন্য কী বদলেছে তার ছোট বিবরণ */
function describe(before, changes, lang) {
  const L = (en, bn) => R.L(lang, en, bn);
  const parts = [];
  if (changes.hospital !== undefined && changes.hospital !== before.hospital) parts.push(`${L('Hospital', 'হাসপাতাল')}: ${changes.hospital}`);
  if (changes.neededByMillis !== undefined && changes.neededByMillis !== before.neededByMillis) {
    const d = new Date(changes.neededByMillis);
    parts.push(`${L('Date', 'তারিখ')}: ${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`);
  }
  if (changes.neededAtMillis !== undefined && changes.neededAtMillis !== before.neededAtMillis) {
    parts.push(`${L('Time', 'সময়')}: ${lang === 'en' ? R.formatTimeEn(changes.neededAtMillis) : R.formatTimeBn(changes.neededAtMillis)}`);
  }
  if (changes.bags !== undefined && changes.bags !== before.bags) parts.push(L(`${changes.bags} bag(s)`, `${changes.bags} ব্যাগ`));
  if (changes.urgent === true && !before.urgent) parts.push(L('Now urgent', 'এখন জরুরি'));
  if (changes.travelFare === true && before.travelFare !== true) parts.push(L('Travel cost will be paid', 'যাতায়াত খরচ দেওয়া হবে'));
  if (changes.note !== undefined && changes.note !== (before.note || '')) parts.push(L('Note updated', 'অতিরিক্ত তথ্য বদলেছে'));
  if (changes.contactPhone !== undefined && changes.contactPhone !== before.contactPhone) parts.push(`${L('New contact number', 'নতুন যোগাযোগ নম্বর')}: ${changes.contactPhone}`);
  return parts;
}

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = req.body?.requestId;
  if (!R.isValidId(requestId)) throw new HttpError(400, 'requestId ঠিক নেই');
  const changes = readChanges(req.body || {});
  if (!Object.keys(changes).length) throw new HttpError(400, 'কিছু বদলানো হয়নি');

  const ref = db.doc(`requests/${requestId}`);
  const now = Date.now();

  const contactRef = ref.collection('private').doc('contact');
  const before = await db.runTransaction(async (t) => {
    const [snap, contact] = await Promise.all([t.get(ref), t.get(contactRef)]);
    if (!snap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = snap.data();
    if (r.requesterUid !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (!R.isActive(r, now)) throw new HttpError(400, 'বন্ধ বা মেয়াদ শেষ হওয়া রিকোয়েস্ট এডিট করা যায় না');
    if ((r.editCount || 0) >= MAX_EDITS) {
      throw new HttpError(429, `একটা রিকোয়েস্ট সর্বোচ্চ ${MAX_EDITS} বার এডিট করা যায়। দরকার হলে বন্ধ করে নতুন রিকোয়েস্ট দিন।`);
    }
    const { contactPhone, ...fields } = changes;
    const invalid = R.validateRequest({ ...r, ...fields }, now);
    if (invalid) throw new HttpError(400, invalid);

    // ব্যাগ বদলালে "ডোনার পাওয়া গেছে" আবার হিসাব (৩ ব্যাগ করলে আর "পাওয়া গেছে" নয়)
    let recount = {};
    let standby = [];
    if (fields.bags !== undefined && fields.bags !== r.bags) {
      const all = await t.get(ref.collection('responses').where('status', 'in', ['accepted', 'donated']));
      const count = all.docs.filter((d) => d.get('status') === 'donated' || d.get('confirmed') === true).length;
      recount = { confirmedCount: count, fulfilled: count >= fields.bags };
      if (r.fulfilled === true && !recount.fulfilled) {
        standby = all.docs.filter((d) => d.get('status') === 'accepted' && d.get('confirmed') !== true).map((d) => d.id);
      }
    }
    const oldPhone = contact.exists ? contact.get('phone') : '';
    t.update(ref, { ...fields, ...recount, editCount: (r.editCount || 0) + 1, editedAt: FieldValue.serverTimestamp() });
    if (contactPhone !== undefined && contactPhone !== oldPhone) {
      t.set(contactRef, { phone: contactPhone }, { merge: true });
    }
    return { ...r, contactPhone: oldPhone, standby };
  });

  // আবার ডোনার লাগবে — "আপাতত যথেষ্ট" শোনা ডোনারদের জানানো
  if (before.standby.length) {
    await require('../lib/requestend').pushUsers(before.standby, (u, lang) => ({
      title: R.L(lang, 'A donor may be needed again', 'আবার ডোনার লাগতে পারে'),
      body: R.L(lang, 'The requester needs more bags now. They will confirm soon.', 'রক্তপ্রার্থীর এখন আরও ব্যাগ লাগবে। শিগগিরই নিশ্চিত করবেন।'),
      data: { requestId, type: 'request' },
    }));
  }

  // যারা রাজি আছেন তাদের জানানো (না গেলেও এডিট ঠিকই সেভ হয়েছে)
  let notified = 0;
  if (describe(before, changes, 'bn').length) {
    try {
      const accepted = await ref.collection('responses').where('status', '==', 'accepted').get();
      const userRefs = accepted.docs.map((d) => db.doc(`users/${d.id}`));
      const users = userRefs.length ? await db.getAll(...userRefs) : [];
      notified = await sendPush(
        users
          .filter((u) => u.exists)
          .map((u) => ({
            uid: u.id,
            token: u.get('fcmToken'),
            title: R.L(u.get('lang'), `${before.patientGroup} blood request updated`, `${before.patientGroup} রক্তের রিকোয়েস্টে পরিবর্তন`),
            body: describe(before, changes, u.get('lang')).join(' · '),
            data: { requestId, type: 'request' },
          })),
      );
    } catch (e) {
      console.error('edit push failed', e);
    }
  }

  return { ok: true, notified };
});
