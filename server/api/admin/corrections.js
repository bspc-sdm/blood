// অ্যাডমিনের জন্য: অপেক্ষায় থাকা "শেষ রক্তদানের তারিখ সংশোধন" অনুরোধের তালিকা
const { db, handler } = require('../../lib/firebase');
const { verifyAdmin } = require('../../lib/admin');

module.exports = handler(async (req) => {
  await verifyAdmin(req);
  const snap = await db.collection('correctionRequests').where('status', '==', 'pending').get();
  const items = snap.docs
    .map((d) => {
      const x = d.data();
      return {
        id: d.id,
        uid: x.uid,
        name: x.name || '',
        currentMillis: x.currentMillis ?? null,
        requestedMillis: x.requestedMillis ?? null,
        reason: x.reason || '',
        createdAtMillis: x.createdAtMillis || 0,
      };
    })
    .sort((a, b) => a.createdAtMillis - b.createdAtMillis)
    .slice(0, 100);
  return { ok: true, items };
});
