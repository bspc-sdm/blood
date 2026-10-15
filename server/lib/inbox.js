// অ্যাপের ভেতরের নোটিফিকেশন তালিকা (ঘণ্টা আইকন)।
// "পড়া হয়েছে" প্রতিটা নোটিফিকেশনে আলাদা লেখা হয় না — serverState/{uid}.inboxSeenAt একবার লেখা হয়,
// আর তার পরের নোটিফিকেশনগুলোই না-পড়া (Firestore-এর লেখার সীমা বাঁচাতে)।
const { admin, db } = require('./firebase');

const REV_MAX = 9999999999999;
const rev = (t) => String(REV_MAX - t).padStart(13, '0');

async function seenAt(uid) {
  const s = await db.doc(`serverState/${uid}`).get();
  return (s.exists && s.get('inboxSeenAt')) || 0;
}

/** না-পড়া: id-র ক্রম অনুযায়ী (নতুন আগে) inboxSeenAt-এর পরেরগুলো — শুধু id দিয়ে খোঁজ, আলাদা ইনডেক্স লাগে না */
async function unreadCount(uid) {
  const seen = await seenAt(uid);
  const id = admin.firestore.FieldPath.documentId();
  const snap = await db.collection('inbox').where(id, '>=', `${uid}_`).where(id, '<', `${uid}_${rev(seen)}`).count().get();
  return snap.data().count;
}

const actions = {
  /** সর্বশেষ ৫০টা (নতুন আগে) */
  async inbox(req, user) {
    const [snap, seen] = await Promise.all([
      db.collection('inbox').where('uid', '==', user.uid).limit(50).get(),
      seenAt(user.uid),
    ]);
    return {
      items: snap.docs.map((d) => ({
        id: d.id,
        title: d.get('title'),
        body: d.get('body') || '',
        data: d.get('data') || {},
        read: (d.get('createdAt') || 0) <= seen,
        createdAt: d.get('createdAt'),
      })),
    };
  },

  /** সব পড়া হয়েছে: একটাই লেখা */
  async inbox_read(req, user) {
    await db.doc(`serverState/${user.uid}`).set({ inboxSeenAt: Date.now() }, { merge: true });
    return { ok: true };
  },
};

/** ক্রন: ২১ দিনের পুরোনো মুছে ফেলা */
async function cleanupInbox(now = Date.now()) {
  const snap = await db.collection('inbox').where('createdAt', '<', now - 21 * 24 * 3600 * 1000).limit(4000).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = db.batch();
    snap.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  return snap.size;
}

module.exports = { actions, unreadCount, cleanupInbox };
