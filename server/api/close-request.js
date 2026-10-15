// রক্তপ্রার্থী "রক্ত পেয়েছি" চাপলে: রিকোয়েস্ট বন্ধ, কত ব্যাগ পেয়েছেন লেখা, আর যারা রক্ত দিয়েছেন তাদের শেষ রক্তদানের তারিখ আপডেট।
// শুধু একবারই কাজ করে, আর শুধু "রাজি" থাকা ডোনারদের — যাতে কেউ বারবার ডেকে কাউকে আটকে রাখতে না পারে।
const { db, HttpError, verifyUser, handler } = require('../lib/firebase');
const R = require('../lib/rules');

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = req.body?.requestId;
  if (!R.isValidId(requestId)) throw new HttpError(400, 'requestId ঠিক নেই');
  const raw = Array.isArray(req.body?.donatedUids) ? req.body.donatedUids : [];
  const donated = [...new Set(raw.filter(R.isValidId))].slice(0, 20);

  const requestRef = db.doc(`requests/${requestId}`);
  const today = R.todayDhakaUtcMidnight();

  const result = await db.runTransaction(async (t) => {
    const reqSnap = await t.get(requestRef);
    if (!reqSnap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    if (reqSnap.get('requesterUid') !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (reqSnap.get('status') === 'closed') return { already: true };

    const responseRefs = donated.map((d) => db.doc(`requests/${requestId}/responses/${d}`));
    const userRefs = donated.map((d) => db.doc(`users/${d}`));
    const responses = responseRefs.length ? await t.getAll(...responseRefs) : [];
    const users = userRefs.length ? await t.getAll(...userRefs) : [];

    // কত ব্যাগ পেয়েছেন। সবগুলো না পেলে রিকোয়েস্ট খোলা থাকে (বাকি ব্যাগের জন্য), শুধু হিসাব আর ডোনার লেখা হয়।
    // আগের অ্যাপ সংখ্যা পাঠায় না — তখন আগের মতো বন্ধ।
    const bags = reqSnap.get('bags') || 1;
    const got = Number.isInteger(req.body?.bagsReceived) ? Math.max(0, Math.min(bags, req.body.bagsReceived)) : null;
    const partial = got != null && got < bags;
    if (partial) {
      t.update(requestRef, { bagsReceived: Math.max(got, reqSnap.get('bagsReceived') || 0), updatedAt: Date.now() });
    } else {
      t.update(requestRef, { status: 'closed', closedAt: Date.now(), ...(got != null ? { bagsReceived: got } : {}) });
    }
    let marked = 0;
    responses.forEach((snap, i) => {
      // শুধু যারা এখন "রাজি" অবস্থায় আছেন
      if (!snap.exists || snap.get('status') !== 'accepted') return;
      t.update(snap.ref, { status: 'donated' });
      marked++;
      const last = users[i].exists ? users[i].get('lastDonationMillis') : null;
      if (users[i].exists && (last == null || last < today)) {
        t.update(userRefs[i], { lastDonationMillis: today });
      }
    });
    return { marked, closed: !partial };
  });

  return { ok: true, ...result };
});
