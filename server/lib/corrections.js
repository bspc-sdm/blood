// "শেষ রক্তদানের তারিখ সংশোধন" — অপেক্ষায় থাকা অনুরোধ (অ্যাপ আর ওয়েব প্যানেল দুটোই ব্যবহার করে)
const { db } = require('./firebase');

async function pendingCorrections() {
  const snap = await db.collection('correctionRequests').where('status', '==', 'pending').get();
  return snap.docs
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
}

module.exports = { pendingCorrections };
