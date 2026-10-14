// রক্তপ্রার্থী "রক্ত পেয়েছি" চাপলে: রিকোয়েস্ট বন্ধ, আর যারা রক্ত দিয়েছেন তাদের শেষ রক্তদানের তারিখ আপডেট।
const { db, HttpError, verifyUser, handler } = require('../lib/firebase');
const R = require('../lib/rules');

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = String(req.body?.requestId || '');
  const donated = Array.isArray(req.body?.donatedUids) ? req.body.donatedUids.map(String).slice(0, 20) : [];
  if (!requestId) throw new HttpError(400, 'requestId দরকার');

  const requestRef = db.doc(`requests/${requestId}`);
  const today = R.todayDhakaUtcMidnight();

  await db.runTransaction(async (t) => {
    const reqSnap = await t.get(requestRef);
    if (!reqSnap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    if (reqSnap.get('requesterUid') !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');

    const responseRefs = donated.map((d) => db.doc(`requests/${requestId}/responses/${d}`));
    const userRefs = donated.map((d) => db.doc(`users/${d}`));
    const responses = responseRefs.length ? await t.getAll(...responseRefs) : [];
    const users = userRefs.length ? await t.getAll(...userRefs) : [];

    t.update(requestRef, { status: 'closed' });
    responses.forEach((snap, i) => {
      // শুধু যারা আসলে রাজি হয়েছিলেন
      if (!snap.exists || !['accepted', 'donated'].includes(snap.get('status'))) return;
      t.update(snap.ref, { status: 'donated' });
      const last = users[i].exists ? users[i].get('lastDonationMillis') : null;
      if (users[i].exists && (last == null || last < today)) {
        t.update(userRefs[i], { lastDonationMillis: today });
      }
    });
  });

  return { ok: true };
});
