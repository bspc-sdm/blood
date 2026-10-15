// নতুন রিকোয়েস্ট দেওয়ার পর অ্যাপ এটা ডাকে: আশেপাশের মানানসই, যোগ্য ডোনারদের পুশ নোটিফিকেশন পাঠায়।
const { db, FieldValue, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
const R = require('../lib/rules');

const LOCK_MS = 60 * 1000;

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = req.body?.requestId;
  if (!R.isValidId(requestId)) throw new HttpError(400, 'requestId ঠিক নেই');

  const ref = db.doc(`requests/${requestId}`);
  const stateRef = db.doc(`serverState/${uid}`);
  const now = Date.now();
  const day = R.dhakaDayKey(now);

  // যাচাই + সাময়িক তালা, যাতে একই রিকোয়েস্টে একসাথে দুবার পাঠানো না হয়
  const request = await db.runTransaction(async (t) => {
    const [snap, stateSnap] = await Promise.all([t.get(ref), t.get(stateRef)]);
    if (!snap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = { id: snap.id, ...snap.data() };
    if (r.requesterUid !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (!R.isActive(r, now)) throw new HttpError(400, 'রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ');
    if (r.notifiedAt) return null;
    if ((r.notifyingUntil || 0) > now) throw new HttpError(409, 'নোটিফিকেশন পাঠানো চলছে, একটু পরে দেখুন');

    const invalid = R.validateRequest(r, now);
    if (invalid) throw new HttpError(400, invalid);

    // একজন দিনে সর্বোচ্চ ৩টা রিকোয়েস্টের নোটিফিকেশন পাঠাতে পারবে
    const used = stateSnap.exists && stateSnap.get('requestDay') === day ? stateSnap.get('requestCount') || 0 : 0;
    if (used >= R.MAX_REQUESTS_PER_DAY) {
      throw new HttpError(429, `দিনে সর্বোচ্চ ${R.MAX_REQUESTS_PER_DAY}টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।`);
    }

    t.update(ref, { notifyingUntil: now + LOCK_MS });
    t.set(stateRef, { requestDay: day, requestCount: used + 1 }, { merge: true });
    return r;
  });
  if (!request) {
    const again = await ref.get();
    return { ok: true, already: true, sent: again.get('notifiedCount') || 0 };
  }

  try {
    // একসাথে খোলা রিকোয়েস্টের সীমা (অ্যাপেও আটকানো হয়, এখানে আবার যাচাই)
    const mine = await db.collection('requests').where('requesterUid', '==', uid).get();
    const openCount = mine.docs.filter((d) => R.isActive(d.data(), now)).length;
    if (openCount > R.MAX_OPEN_REQUESTS) {
      throw new HttpError(429, `একসাথে সর্বোচ্চ ${R.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়`);
    }

    // কাছের জেলাগুলোর ব্যবহারকারী
    const usersSnap = await db
      .collection('users')
      .where('placeName', 'in', R.nearDistricts(request.lat, request.lng))
      .get();
    const users = usersSnap.docs.map((d) => ({ uid: d.id, ...d.data() }));
    let donors = R.pickDonors(request, users, now);

    // প্রত্যেক ডোনার দিনে সর্বোচ্চ যতটা নোটিফিকেশন চেয়েছেন (সেটিংস, না বদলালে ৩টা)
    const stateRefs = donors.map((u) => db.doc(`serverState/${u.uid}`));
    const states = stateRefs.length ? await db.getAll(...stateRefs) : [];
    const pushedToday = (s) => (s.exists && s.get('pushDay') === day ? s.get('pushCount') || 0 : 0);
    const counts = new Map(donors.map((u, i) => [u.uid, pushedToday(states[i])]));
    donors = donors.filter((u) => counts.get(u.uid) < R.pushLimit(u));

    const title = `${request.patientGroup} রক্ত দরকার${request.urgent ? ' (জরুরি)' : ''}`;
    const sent = await sendPush(
      donors.map((u) => ({
        uid: u.uid,
        token: u.fcmToken,
        title,
        body: `${request.bags} ব্যাগ · ${request.hospital}, ${request.placeName} · ${R.formatKm(u.km)}`,
        data: { requestId, type: 'request' },
      })),
    );

    const batch = db.batch();
    donors.forEach((u) => {
      batch.set(
        db.doc(`serverState/${u.uid}`),
        counts.get(u.uid) > 0
          ? { pushDay: day, pushCount: FieldValue.increment(1) }
          : { pushDay: day, pushCount: 1 },
        { merge: true },
      );
    });
    batch.update(ref, {
      notifiedAt: FieldValue.serverTimestamp(),
      notifiedCount: sent,
      notifyingUntil: FieldValue.delete(),
    });
    await batch.commit();

    return { ok: true, sent };
  } catch (e) {
    // কিছু ভুল হলে তালা খুলে দেওয়া, যাতে অ্যাপ থেকে আবার চেষ্টা করা যায়
    await ref.update({ notifyingUntil: FieldValue.delete() }).catch(() => {});
    throw e;
  }
});
