// রক্তপ্রার্থী নিজের রিকোয়েস্ট পুরোপুরি মুছে ফেলেন (চালু বা বন্ধ যেকোনোটা)।
// Firestore নিয়মে অ্যাপ নিজে মুছতে পারে না (সাড়া দেওয়া ডোনারদের নাম-ফোন ভেতরে থাকে), তাই সার্ভার দিয়ে।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const actions = {
  async request_delete(req, user) {
    const id = req.body?.requestId;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`requests/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('requesterUid') !== user.uid) throw new HttpError(403, C.tr(req, 'This is not your request', 'এটা আপনার রিকোয়েস্ট না'));

    // চালু রিকোয়েস্টে যাঁরা রাজি ছিলেন, তাঁদের জানানো — যাতে কেউ শুধু শুধু হাসপাতালে না যান
    const wasOpen = s.get('status') === 'open';
    const willing = wasOpen ? await ref.collection('responses').where('status', '==', 'accepted').limit(50).get() : { docs: [] };
    await db.recursiveDelete(ref);

    if (willing.docs.length) {
      const users = await db.getAll(...willing.docs.map((d) => db.doc(`users/${d.id}`)));
      try {
        await sendPush(users.filter((u) => u.exists).map((u) => ({
          uid: u.id,
          token: u.get('fcmToken'),
          title: R.L(u.get('lang'), 'Blood request withdrawn', 'রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে'),
          body: R.L(u.get('lang'), `The ${s.get('patientGroup')} request was removed by the requester. You don't need to go. Thank you!`,
            `${s.get('patientGroup')} রক্তের রিকোয়েস্টটা রক্তপ্রার্থী তুলে নিয়েছেন। আপনার যাওয়ার দরকার নেই। ধন্যবাদ!`),
          data: { type: 'request_removed' },
        })));
      } catch (e) {
        console.error('request delete push failed', e);
      }
    }
    return { ok: true, notified: willing.docs.length };
  },
};

module.exports = { actions };
