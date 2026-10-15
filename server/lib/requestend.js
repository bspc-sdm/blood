// রক্তের রিকোয়েস্ট শেষ হলে (রক্ত পেয়েছি, সময় শেষ, মুছে ফেলা, রক্তপ্রার্থী অ্যাকাউন্ট মুছলে) যাঁরা "রাজি" ছিলেন
// তাঁদের জানানো — যাতে কেউ শুধু শুধু হাসপাতালে না যান। আর রাজি ডোনার চলে গেলে (অ্যাকাউন্ট মুছলে) হিসাব ঠিক রাখা।
const { db, FieldValue, sendPush } = require('./firebase');
const R = require('./rules');

/** ব্যবহারকারীদের পুশ + অ্যাপের ঘণ্টার তালিকা (টোকেন না থাকলেও তালিকায় যায়) */
async function pushUsers(uids, make) {
  const ids = [...new Set(uids.filter(Boolean))];
  if (!ids.length) return 0;
  try {
    const users = await db.getAll(...ids.map((id) => db.doc(`users/${id}`)));
    return await sendPush(users.filter((u) => u.exists).map((u) => ({ uid: u.id, token: u.get('fcmToken'), ...make(u, u.get('lang')) })));
  } catch (e) {
    console.error('request end push failed', e);
    return 0;
  }
}

const TEXT = {
  closed: (L, r) => ({
    title: L('Request closed — no need to come', 'রিকোয়েস্ট বন্ধ হয়েছে, আর আসতে হবে না'),
    body: L(`${r.hospital}: the requester got the blood. If you already donated, tell us in the app. Thank you!`,
      `${r.hospital}: রক্তপ্রার্থী রক্ত পেয়ে গেছেন। আগেই রক্ত দিয়ে থাকলে অ্যাপে জানান। ধন্যবাদ!`),
  }),
  expired: (L, r) => ({
    title: L('Request time is over', 'রিকোয়েস্টের সময় শেষ'),
    body: L(`${r.hospital}: the request has closed, you don't need to go. If you donated, tell us in the app.`,
      `${r.hospital}: রিকোয়েস্টটা বন্ধ হয়েছে, আর যেতে হবে না। রক্ত দিয়ে থাকলে অ্যাপে জানান।`),
  }),
  removed: (L, r) => ({
    title: L('Blood request withdrawn', 'রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে'),
    body: L(`The ${r.patientGroup} request at ${r.hospital} was removed. You don't need to go. Thank you!`,
      `${r.hospital}-এর ${r.patientGroup} রিকোয়েস্টটা তুলে নেওয়া হয়েছে। আপনার যাওয়ার দরকার নেই। ধন্যবাদ!`),
  }),
};

/**
 * রিকোয়েস্ট শেষ হলে এখনো "রাজি" থাকা ডোনারদের জানানো।
 * reason: closed | expired | removed ; donorIds দিলে আর খোঁজা হয় না (মোছার আগে পড়ে রাখা তালিকা)
 */
async function notifyEnded(requestId, r, reason, donorIds = null) {
  let ids = donorIds;
  if (!ids) {
    const snap = await db.collection(`requests/${requestId}/responses`).where('status', '==', 'accepted').limit(50).get();
    ids = snap.docs.map((d) => d.id);
  }
  if (!ids.length) return 0;
  const make = TEXT[reason] || TEXT.closed;
  return pushUsers(ids, (u, lang) => ({
    ...make((en, bn) => R.L(lang, en, bn), r),
    data: reason === 'removed' ? { type: 'request_removed' } : { requestId, type: 'request' },
  }));
}

/**
 * রাজি ডোনার চলে গেলে (অ্যাকাউন্ট মুছলে): রাজির সংখ্যা কমে, নিশ্চিত হলে আবার গোনা, রক্তপ্রার্থীকে জানানো।
 * সাড়ার নথি এখানেই মোছা হয়।
 */
async function donorLeft(requestId, uid) {
  const reqRef = db.doc(`requests/${requestId}`);
  const respRef = reqRef.collection('responses').doc(uid);
  const out = await db.runTransaction(async (t) => {
    const [rs, ds] = await Promise.all([t.get(reqRef), t.get(respRef)]);
    if (!ds.exists) return null;
    const wasAccepted = ds.get('status') === 'accepted';
    const wasConfirmed = wasAccepted && ds.get('confirmed') === true;
    let all = null;
    if (rs.exists && wasConfirmed) all = await t.get(reqRef.collection('responses').where('status', 'in', ['accepted', 'donated']));
    t.delete(respRef);
    if (!rs.exists || !wasAccepted) return null;
    const r = rs.data();
    const upd = { acceptedCount: FieldValue.increment(-1) };
    if (all) {
      const count = all.docs.filter((d) => d.id !== uid && (d.get('status') === 'donated' || d.get('confirmed') === true)).length;
      upd.confirmedCount = count;
      upd.fulfilled = count >= (r.bags || 1);
    }
    t.update(reqRef, upd);
    return { r, confirmed: wasConfirmed };
  });
  if (out && R.isActive(out.r)) {
    await pushUsers([out.r.requesterUid], (u, lang) => ({
      title: R.L(lang, 'A donor is no longer available', 'একজন ডোনার আর থাকছেন না'),
      body: R.L(lang, out.confirmed ? 'A confirmed donor left the app. Please confirm another from My Request.' : 'A donor who agreed left the app.',
        out.confirmed ? 'একজন নিশ্চিত ডোনার অ্যাপ ছেড়ে গেছেন। "আমার রিকোয়েস্ট" থেকে আরেকজনকে নিশ্চিত করুন।' : 'রাজি হওয়া একজন ডোনার অ্যাপ ছেড়ে গেছেন।'),
      data: { requestId, type: 'response' },
    }));
  }
}

module.exports = { pushUsers, notifyEnded, donorLeft };
