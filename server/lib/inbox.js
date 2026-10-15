// অ্যাপের ভেতরের নোটিফিকেশন তালিকা (ঘণ্টা আইকন)
const { db } = require('./firebase');

async function unreadCount(uid) {
  const snap = await db.collection('inbox').where('uid', '==', uid).where('read', '==', false).count().get();
  return snap.data().count;
}

const actions = {
  /** সর্বশেষ ৫০টা (নতুন আগে) */
  async inbox(req, user) {
    const snap = await db.collection('inbox').where('uid', '==', user.uid).limit(50).get();
    return {
      items: snap.docs.map((d) => ({
        id: d.id,
        title: d.get('title'),
        body: d.get('body') || '',
        data: d.get('data') || {},
        read: d.get('read') === true,
        createdAt: d.get('createdAt'),
      })),
    };
  },

  /** সব পড়া হয়েছে */
  async inbox_read(req, user) {
    const snap = await db.collection('inbox').where('uid', '==', user.uid).where('read', '==', false).limit(400).get();
    if (snap.empty) return { ok: true };
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { read: true }));
    await batch.commit();
    return { ok: true };
  },
};

/** ক্রন: ৩০ দিনের পুরোনো মুছে ফেলা */
async function cleanupInbox(now = Date.now()) {
  const snap = await db.collection('inbox').where('createdAt', '<', now - 30 * 24 * 3600 * 1000).limit(2000).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = db.batch();
    snap.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  return snap.size;
}

module.exports = { actions, unreadCount, cleanupInbox };
