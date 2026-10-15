// লগইন: কোড যাচাই করে Firebase-এর লগইন টোকেন দেওয়া (অ্যাপ এটা দিয়ে লগইন করে)
const { admin, db, HttpError, handler } = require('../../lib/firebase');
const E = require('../../lib/email-code');
const R = require('../../lib/rules');

async function getOrCreateUser(email) {
  try {
    const user = await admin.auth().getUserByEmail(email);
    if (!user.emailVerified) await admin.auth().updateUser(user.uid, { emailVerified: true });
    return user;
  } catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
  }
  try {
    return await admin.auth().createUser({ email, emailVerified: true });
  } catch (e) {
    // একই মুহূর্তে অন্য অনুরোধে অ্যাকাউন্ট তৈরি হয়ে গেলে
    if (e.code === 'auth/email-already-exists') return admin.auth().getUserByEmail(email);
    throw e;
  }
}

module.exports = handler(async (req, res) => {
  const email = E.normalizeEmail(req.body?.email);
  const code = String(req.body?.code || '').trim();
  if (!email) throw new HttpError(400, 'সঠিক ইমেইল দিন');
  if (!/^\d{6}$/.test(code)) throw new HttpError(400, '৬ সংখ্যার কোড দিন');

  const ref = db.doc(`emailCodes/${E.docIdFor(email)}`);
  const now = Date.now();
  const day = R.dhakaDayKey(now);
  const hash = E.hashCode(email, code);

  const ok = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    const d = snap.exists ? snap.data() : {};
    const live = (Array.isArray(d.codes) ? d.codes : []).filter((c) => c && c.exp > now);
    if (!live.length) throw new HttpError(400, 'কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।');

    // দিনে মোট ১০ বার ভুল দিলে সেদিন আর চেষ্টা করা যাবে না
    const failsToday = d.failDay === day ? d.failCount || 0 : 0;
    if (failsToday >= E.MAX_FAILS_PER_DAY) {
      throw new HttpError(429, 'আজ অনেকবার ভুল কোড দেওয়া হয়েছে। কাল আবার চেষ্টা করুন।');
    }
    if (!live.some((c) => E.codesMatch(c.h, hash))) {
      t.set(ref, { failDay: day, failCount: failsToday + 1 }, { merge: true });
      return false;
    }
    // সফল হলে সব চালু কোড বাতিল (একবার ব্যবহারের পর আর চলবে না)
    t.set(ref, { codes: [] }, { merge: true });
    return true;
  });
  if (!ok) throw new HttpError(400, 'কোড মেলেনি। আবার দেখে লিখুন।');

  // ওয়েব অ্যাডমিন প্যানেল: টোকেনের বদলে httpOnly কুকি, আর শুধু অ্যাডমিনরা
  if (req.body?.web === true) {
    const { isAdmin } = require('../../lib/admin');
    if (!(await isAdmin({ email, email_verified: true }))) {
      throw new HttpError(403, 'এই ইমেইল অ্যাডমিন হিসেবে যোগ করা নেই');
    }
    const user = await getOrCreateUser(email);
    require('../../lib/websession').start(res, user.uid, email);
    return { ok: true };
  }

  const user = await getOrCreateUser(email);
  const token = await admin.auth().createCustomToken(user.uid);
  return { ok: true, token };
});
