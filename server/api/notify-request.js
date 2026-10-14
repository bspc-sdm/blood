// নতুন রিকোয়েস্ট দেওয়ার পর অ্যাপ এটা ডাকে: আশেপাশের মানানসই, যোগ্য ডোনারদের পুশ নোটিফিকেশন পাঠায়।
const { db, FieldValue, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
const R = require('../lib/rules');

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = String(req.body?.requestId || '');
  if (!requestId) throw new HttpError(400, 'requestId দরকার');

  const ref = db.doc(`requests/${requestId}`);

  // একই রিকোয়েস্টে দুবার যেন না পাঠানো হয়
  const request = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    if (!snap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = { id: snap.id, ...snap.data() };
    if (r.requesterUid !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (!R.isActive(r)) throw new HttpError(400, 'রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ');
    if (r.notifiedAt) return null;
    t.update(ref, { notifiedAt: FieldValue.serverTimestamp(), notifiedCount: 0 });
    return r;
  });
  if (!request) {
    const again = await ref.get();
    return { ok: true, already: true, sent: again.get('notifiedCount') || 0 };
  }

  // কাছের জেলাগুলোর ব্যবহারকারী
  const usersSnap = await db
    .collection('users')
    .where('placeName', 'in', R.nearDistricts(request.lat, request.lng))
    .get();
  const users = usersSnap.docs.map((d) => ({ uid: d.id, ...d.data() }));
  let donors = R.pickDonors(request, users);

  // দিনে সর্বোচ্চ ৩টা নোটিফিকেশন
  const day = R.dhakaDayKey();
  const stateRefs = donors.map((u) => db.doc(`serverState/${u.uid}`));
  const states = stateRefs.length ? await db.getAll(...stateRefs) : [];
  const countToday = (s) => (s.exists && s.get('pushDay') === day ? s.get('pushCount') || 0 : 0);
  donors = donors.filter((u, i) => countToday(states[i]) < R.MAX_PUSH_PER_DAY);

  const title = `🩸 ${request.patientGroup} রক্ত দরকার${request.urgent ? ' (জরুরি)' : ''}`;
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
    const i = stateRefs.findIndex((r) => r.id === u.uid);
    batch.set(
      db.doc(`serverState/${u.uid}`),
      { pushDay: day, pushCount: countToday(states[i]) + 1 },
      { merge: true },
    );
  });
  batch.update(ref, { notifiedCount: sent });
  await batch.commit();

  return { ok: true, sent };
});
