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
  if (body.urgent !== undefined) {
    if (typeof body.urgent !== 'boolean') throw new HttpError(400, 'জরুরি ঠিক নেই');
    c.urgent = body.urgent;
  }
  if (body.note !== undefined) {
    if (typeof body.note !== 'string' || body.note.length > 300) throw new HttpError(400, 'অতিরিক্ত তথ্য ৩০০ অক্ষরের মধ্যে লিখুন');
    c.note = body.note.trim();
  }
  return c;
}

/** ডোনারদের জন্য কী বদলেছে তার ছোট বিবরণ */
function describe(before, changes) {
  const parts = [];
  if (changes.hospital !== undefined && changes.hospital !== before.hospital) parts.push(`হাসপাতাল: ${changes.hospital}`);
  if (changes.neededByMillis !== undefined && changes.neededByMillis !== before.neededByMillis) {
    const d = new Date(changes.neededByMillis);
    parts.push(`তারিখ: ${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`);
  }
  if (changes.neededAtMillis !== undefined && changes.neededAtMillis !== before.neededAtMillis) {
    parts.push(`সময়: ${R.formatTimeBn(changes.neededAtMillis)}`);
  }
  if (changes.bags !== undefined && changes.bags !== before.bags) parts.push(`${changes.bags} ব্যাগ`);
  if (changes.urgent === true && !before.urgent) parts.push('এখন জরুরি');
  if (changes.note !== undefined && changes.note !== (before.note || '')) parts.push('অতিরিক্ত তথ্য বদলেছে');
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

  const before = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    if (!snap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = snap.data();
    if (r.requesterUid !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (!R.isActive(r, now)) throw new HttpError(400, 'বন্ধ বা মেয়াদ শেষ হওয়া রিকোয়েস্ট এডিট করা যায় না');
    if ((r.editCount || 0) >= MAX_EDITS) {
      throw new HttpError(429, `একটা রিকোয়েস্ট সর্বোচ্চ ${MAX_EDITS} বার এডিট করা যায়। দরকার হলে বন্ধ করে নতুন রিকোয়েস্ট দিন।`);
    }
    const invalid = R.validateRequest({ ...r, ...changes }, now);
    if (invalid) throw new HttpError(400, invalid);

    t.update(ref, { ...changes, editCount: (r.editCount || 0) + 1, editedAt: FieldValue.serverTimestamp() });
    return r;
  });

  // যারা রাজি আছেন তাদের জানানো (না গেলেও এডিট ঠিকই সেভ হয়েছে)
  let notified = 0;
  const parts = describe(before, changes);
  if (parts.length) {
    try {
      const accepted = await ref.collection('responses').where('status', '==', 'accepted').get();
      const userRefs = accepted.docs.map((d) => db.doc(`users/${d.id}`));
      const users = userRefs.length ? await db.getAll(...userRefs) : [];
      notified = await sendPush(
        users
          .filter((u) => u.exists && u.get('fcmToken'))
          .map((u) => ({
            uid: u.id,
            token: u.get('fcmToken'),
            title: `${before.patientGroup} রক্তের রিকোয়েস্টে পরিবর্তন`,
            body: parts.join(' · '),
            data: { requestId, type: 'request' },
          })),
      );
    } catch (e) {
      console.error('edit push failed', e);
    }
  }

  return { ok: true, notified };
});
