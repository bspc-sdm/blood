// অ্যাডমিন সংশোধনের অনুরোধ অনুমোদন বা বাতিল করেন। অনুমোদন দিলে ব্যবহারকারীর তারিখ বদলায়
// (অ্যাপ থেকে তারিখ পেছানো যায় না, তাই এটাই একমাত্র পথ), আর ব্যবহারকারীকে জানানো হয়।
const { db, HttpError, handler, sendPush } = require('../../lib/firebase');
const { verifyAdmin } = require('../../lib/admin');
const R = require('../../lib/rules');

module.exports = handler(async (req) => {
  const adminUid = await verifyAdmin(req);
  const id = req.body?.id;
  const approve = req.body?.approve === true;
  if (!R.isValidId(id)) throw new HttpError(400, 'id ঠিক নেই');

  const ref = db.doc(`correctionRequests/${id}`);
  const result = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    if (!snap.exists) throw new HttpError(404, 'অনুরোধ পাওয়া যায়নি');
    const c = snap.data();
    if (c.status !== 'pending') return { already: true, uid: c.uid };
    if (!R.isValidId(c.uid)) throw new HttpError(400, 'অনুরোধে সমস্যা আছে');

    if (approve) {
      const requested = c.requestedMillis;
      if (requested != null && (typeof requested !== 'number' || requested > R.todayDhakaUtcMidnight())) {
        throw new HttpError(400, 'চাওয়া তারিখটা ঠিক নেই');
      }
      t.set(db.doc(`users/${c.uid}`), { lastDonationMillis: requested ?? null }, { merge: true });
    }
    t.update(ref, {
      status: approve ? 'approved' : 'rejected',
      resolvedAtMillis: Date.now(),
      resolvedBy: adminUid,
    });
    return { uid: c.uid };
  });

  if (!result.already) {
    try {
      const user = await db.doc(`users/${result.uid}`).get();
      const token = user.get('fcmToken');
      if (token) {
        await sendPush([
          {
            uid: result.uid,
            token,
            title: approve
              ? R.L(user.get('lang'), 'Date corrected', 'তারিখ সংশোধন হয়েছে')
              : R.L(user.get('lang'), 'Date correction not approved', 'তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে'),
            body: approve
              ? R.L(user.get('lang'), 'Your last donation date has been fixed.', 'আপনার শেষ রক্তদানের তারিখ ঠিক করে দেওয়া হয়েছে।')
              : R.L(user.get('lang'), 'See your profile in the app for details.', 'অ্যাপের প্রোফাইল পাতায় বিস্তারিত দেখুন।'),
            data: { type: 'correction' },
          },
        ]);
      }
    } catch (e) {
      console.error('correction push failed', e);
    }
  }

  return { ok: true, ...result };
});
