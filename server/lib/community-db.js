// কমিউনিটি অংশের ডাটাবেস সহায়ক: ব্যাজ, দৈনিক সীমা, নাম
const { db, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');

/** অনুমোদিত ব্যাজ (না থাকলে null) */
async function approvedBadge(uid) {
  const s = await db.doc(`badges/${uid}`).get();
  return s.exists && s.get('status') === 'approved' ? s.get('type') : null;
}

/** দৈনিক সীমা (serverState/{uid}) */
async function useQuota(req, uid, kind) {
  const ref = db.doc(`serverState/${uid}`);
  const day = R.dhakaDayKey();
  await db.runTransaction(async (t) => {
    const s = await t.get(ref);
    const used = s.exists && s.get(`${kind}Day`) === day ? s.get(`${kind}Count`) || 0 : 0;
    if (used >= C.LIMITS[kind]) {
      throw new HttpError(429, C.tr(req, 'Daily limit reached. Try again tomorrow.', 'আজকের সীমা শেষ। কাল আবার চেষ্টা করুন।'));
    }
    t.set(ref, { [`${kind}Day`]: day, [`${kind}Count`]: used + 1 }, { merge: true });
  });
}

async function userName(uid) {
  const s = await db.doc(`users/${uid}`).get();
  return (s.exists && s.get('name')) || '';
}

module.exports = { approvedBadge, useQuota, userName };
