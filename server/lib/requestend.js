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
    return await sendPush(users.filter((u) => u.exists).map((u) => ({ uid: u.id, tokens: require('./firebase').tokensOf(u), ...make(u, u.get('lang')) })));
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
  notNeeded: (L, r) => ({
    title: L('Blood no longer needed — no need to come', 'আর রক্ত লাগবে না, আসতে হবে না'),
    body: L(`${r.hospital}: the requester closed the request. Thank you for offering!`,
      `${r.hospital}: রক্তপ্রার্থী রিকোয়েস্ট বন্ধ করেছেন। রাজি হওয়ার জন্য ধন্যবাদ!`),
  }),
  reopened: (L, r) => ({
    title: L('Request opened again', 'রিকোয়েস্ট আবার খোলা হয়েছে'),
    body: L(`${r.hospital}: it was closed by mistake. You may still be needed — please stay available.`,
      `${r.hospital}: ভুল করে বন্ধ হয়েছিল। আপনাকে এখনো দরকার হতে পারে, প্রস্তুত থাকুন।`),
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
 * রাজি ডোনার চলে গেলে: রাজির সংখ্যা কমে, নিশ্চিত হলে আবার গোনা, রক্তপ্রার্থীকে জানানো।
 * mode "deleted": অ্যাকাউন্ট মুছেছেন — সাড়ার নথি মোছা হয়।
 * mode "elsewhere": অন্য রিকোয়েস্টে রক্ত দিয়েছেন — সাড়া "বাতিল" হয়।
 */
async function withdraw(requestId, uid, mode) {
  const reqRef = db.doc(`requests/${requestId}`);
  const respRef = reqRef.collection('responses').doc(uid);
  const out = await db.runTransaction(async (t) => {
    const [rs, ds] = await Promise.all([t.get(reqRef), t.get(respRef)]);
    if (!ds.exists) return null;
    const wasAccepted = ds.get('status') === 'accepted';
    if (mode === 'elsewhere' && !wasAccepted) return null;
    const wasConfirmed = wasAccepted && ds.get('confirmed') === true;
    let all = null;
    if (rs.exists && wasConfirmed) all = await t.get(reqRef.collection('responses').where('status', 'in', ['accepted', 'donated']));
    if (mode === 'deleted') t.delete(respRef);
    else t.update(respRef, { status: 'cancelled', cancelledReason: 'donated_elsewhere', updatedAt: Date.now() });
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
    const elsewhere = mode === 'elsewhere';
    await pushUsers([out.r.requesterUid], (u, lang) => ({
      title: R.L(lang, 'A donor is no longer available', 'একজন ডোনার আর থাকছেন না'),
      body: elsewhere
        ? R.L(lang, out.confirmed ? 'A confirmed donor gave blood for another patient. Please confirm another from My Request.' : 'A donor who agreed gave blood for another patient.',
          out.confirmed ? 'একজন নিশ্চিত ডোনার অন্য রোগীকে রক্ত দিয়েছেন। "আমার রিকোয়েস্ট" থেকে আরেকজনকে নিশ্চিত করুন।' : 'রাজি হওয়া একজন ডোনার অন্য রোগীকে রক্ত দিয়েছেন।')
        : R.L(lang, out.confirmed ? 'A confirmed donor left the app. Please confirm another from My Request.' : 'A donor who agreed left the app.',
          out.confirmed ? 'একজন নিশ্চিত ডোনার অ্যাপ ছেড়ে গেছেন। "আমার রিকোয়েস্ট" থেকে আরেকজনকে নিশ্চিত করুন।' : 'রাজি হওয়া একজন ডোনার অ্যাপ ছেড়ে গেছেন।'),
      data: { requestId, type: 'response' },
    }));
  }
}

const donorLeft = (requestId, uid) => withdraw(requestId, uid, 'deleted');

/** ডোনার এক রিকোয়েস্টে রক্ত দিলেন: বাকি রিকোয়েস্টগুলোর "রাজি" মুক্ত করা */
async function releaseOthers(uid, exceptRequestId) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    const ids = Object.keys((u.exists && u.get('commitments')) || {}).filter((id) => id !== exceptRequestId && R.isValidId(id));
    for (const id of ids.slice(0, 20)) await withdraw(id, uid, 'elsewhere').catch((e) => console.error('release failed', e));
    if (ids.length) {
      await db.doc(`users/${uid}`).update(Object.fromEntries([exceptRequestId, ...ids].map((id) => [`commitments.${id}`, FieldValue.delete()])));
    }
  } catch (e) {
    console.error('releaseOthers failed', e);
  }
}

module.exports = { pushUsers, notifyEnded, donorLeft, releaseOthers };
