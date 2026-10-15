// রক্তপ্রার্থী রাজি হওয়া ডোনারদের মধ্যে থেকে যাঁদের নেবেন তাঁদের "নিশ্চিত" করেন।
// নিশ্চিত ডোনার নোটিফিকেশন পান; যত ব্যাগ দরকার তত জন নিশ্চিত হলে বাকি রাজি ডোনাররা জানেন "আপাতত লাগবে না"।
// সাড়ার status "accepted"-ই থাকে, শুধু confirmed: true যোগ হয় — তাই Firestore-এর নিয়ম বদলাতে হয়নি।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');

module.exports = {
  async confirm_donor(req, user) {
    const { requestId, donorUid } = req.body || {};
    if (!R.isValidId(requestId) || !R.isValidId(donorUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const reqRef = db.doc(`requests/${requestId}`);
    const respRef = reqRef.collection('responses').doc(donorUid);

    const result = await db.runTransaction(async (t) => {
      const [rs, ds] = await Promise.all([t.get(reqRef), t.get(respRef)]);
      if (!rs.exists) throw new HttpError(404, C.tr(req, 'Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি'));
      const r = rs.data();
      if (r.requesterUid !== user.uid) throw new HttpError(403, C.tr(req, 'This is not your request', 'এটা আপনার রিকোয়েস্ট না'));
      if (!R.isActive(r)) throw new HttpError(400, C.tr(req, 'This request is closed', 'রিকোয়েস্টটা বন্ধ হয়ে গেছে'));
      if (!ds.exists || ds.get('status') !== 'accepted') {
        throw new HttpError(400, C.tr(req, 'This donor is no longer available', 'এই ডোনার এখন আর রাজি অবস্থায় নেই'));
      }
      if (ds.get('confirmed') === true) return null;

      // নিশ্চিত/রক্ত দেওয়া ডোনার গোনা (এইজন সহ)
      const all = await t.get(reqRef.collection('responses').where('status', 'in', ['accepted', 'donated']));
      const counted = all.docs.filter((d) => d.id === donorUid || d.get('confirmed') === true || d.get('status') === 'donated');
      const confirmedCount = counted.length;
      const fulfilled = confirmedCount >= (r.bags || 1);
      t.update(respRef, { confirmed: true, confirmedAt: Date.now() });
      t.update(reqRef, { confirmedCount, fulfilled });
      // এইমাত্র পূরণ হলে, বাকি রাজি (নিশ্চিত না হওয়া) ডোনারদের জানাতে হবে
      const others = fulfilled && !r.fulfilled
        ? all.docs.filter((d) => d.id !== donorUid && d.get('status') === 'accepted' && d.get('confirmed') !== true).map((d) => d.id)
        : [];
      return { r, others };
    });
    if (!result) return { ok: true, already: true };

    const { r, others } = result;
    try {
      const ids = [donorUid, ...others];
      const users = await db.getAll(...ids.map((id) => db.doc(`users/${id}`)));
      const when = r.neededAtMillis ? r.neededAtMillis : null;
      await sendPush(
        users
          .filter((u) => u.exists && u.get('fcmToken'))
          .map((u) => {
            const lang = u.get('lang');
            const time = when ? ` · ${lang === 'en' ? R.formatTimeEn(when) : R.formatTimeBn(when)}` : '';
            return u.id === donorUid
              ? {
                uid: u.id,
                token: u.get('fcmToken'),
                title: R.L(lang, 'You are confirmed — please come', 'আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন'),
                body: `${r.hospital}${time}`,
                data: { requestId, type: 'request' },
              }
              : {
                uid: u.id,
                token: u.get('fcmToken'),
                title: R.L(lang, 'Enough donors for now — thank you!', 'আপাতত যথেষ্ট ডোনার পাওয়া গেছে, ধন্যবাদ!'),
                body: R.L(lang, 'Please stay available in case it changes.', 'দরকার হলে আবার জানানো হবে।'),
                data: { requestId, type: 'request' },
              };
          }),
      );
    } catch (e) {
      console.error('confirm push failed', e);
    }
    return { ok: true };
  },
};
