// লগইন: কোড যাচাই করে Firebase-এর লগইন টোকেন দেওয়া (অ্যাপ এটা দিয়ে লগইন করে)
const { admin, db, HttpError, handler } = require('../../lib/firebase');
const E = require('../../lib/email-code');
const R = require('../../lib/rules');

async function getOrCreateUser(email) {
  try {
    const user = await admin.auth().getUserByEmail(email);
    if (!user.emailVerified) {
      // ইমেইল যাচাই ছাড়া কেউ আগে এই ঠিকানায় অ্যাকাউন্ট খুলে থাকলে তার সব সেশন বাতিল — আসল মালিক একা থাকেন
      await admin.auth().revokeRefreshTokens(user.uid);
      await admin.auth().updateUser(user.uid, { emailVerified: true });
    }
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

  // নতুন অ্যাপ/প্যানেল: কোড চাওয়ার সময় পাওয়া rid পাঠায় — ভুলের গোনা শুধু সেই কোডে, তাই অন্য কেউ ভুল কোড
  // দিয়ে আসল মানুষকে আটকাতে পারে না। rid ছাড়া (পুরোনো অ্যাপ) আগের মতো দিনে ১০ বার ভুলের সীমা।
  const rids = (Array.isArray(req.body?.rids) ? req.body.rids : []).filter((x) => typeof x === 'string' && /^[0-9a-f]{32}$/.test(x)).slice(0, 5);
  const ok = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    const d = snap.exists ? snap.data() : {};
    const all = Array.isArray(d.codes) ? d.codes : [];
    const live = all.filter((c) => c && c.exp > now);
    if (!live.length) throw new HttpError(400, 'কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।');

    if (rids.length) {
      const mine = live.filter((c) => c.rid && rids.includes(c.rid) && (c.fails || 0) < E.MAX_ATTEMPTS);
      if (!mine.length) throw new HttpError(400, 'কোডের মেয়াদ শেষ বা অনেকবার ভুল হয়েছে। নতুন কোড চান।');
      if (mine.some((c) => E.codesMatch(c.h, hash))) {
        t.set(ref, { codes: [] }, { merge: true });
        return true;
      }
      // ভুল: শুধু এই মানুষের কোডগুলোতে গোনা (৫ বার ভুলে সেই কোড বাতিল)
      t.set(ref, { codes: live.map((c) => (mine.includes(c) ? { ...c, fails: (c.fails || 0) + 1 } : c)) }, { merge: true });
      return false;
    }

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

  /** মোছার অপেক্ষায় (৩০ দিন) থাকা অ্যাকাউন্টে আবার লগইন = মোছা বাতিল, লুকানো সব আগের মতো */
  const keepAccount = (uid) => require('../../lib/account').deletion.cancel(uid).catch((e) => {
    console.error('cancel deletion failed', e);
    return false;
  });

  // ওয়েব অ্যাডমিন প্যানেল: টোকেনের বদলে httpOnly কুকি, আর শুধু অ্যাডমিনরা
  if (req.body?.web === true) {
    const { isAdmin } = require('../../lib/admin');
    // পার্টনার ওয়েব: যাচাই করা ডাক্তার, কোনো ডাক্তারের সহকারী, বা অনুমোদিত ফার্মেসি/প্যাথলজির মালিক
    if (req.body?.panel === 'doctor') {
      const user = await getOrCreateUser(email);
      if (user.disabled) throw new HttpError(403, 'এই অ্যাকাউন্ট বন্ধ করা আছে');
      const { approvedBadge } = require('../../lib/community-db');
      const { db } = require('../../lib/firebase');
      const doctor = (await approvedBadge(user.uid)) === 'doctor';
      const assistant = doctor ? true : !(await db.collection('providers').where('assistantEmails', 'array-contains', email).limit(1).get()).empty;
      // অনুমোদিত ফার্মেসি আর প্যাথলজির মালিকও (ওষুধ দেওয়া, রিপোর্ট দেওয়া)
      const [shop, lab] = doctor || assistant ? [null, null] : await Promise.all([db.doc(`pharmacies/${user.uid}`).get(), db.doc(`labs/${user.uid}`).get()]);
      const owner = !!((shop && shop.exists && shop.get('status') === 'approved') || (lab && lab.exists && lab.get('status') === 'approved'));
      if (!doctor && !assistant && !owner) throw new HttpError(403, 'এই ইমেইল কোনো যাচাই করা ডাক্তার, সহকারী, অনুমোদিত ফার্মেসি বা প্যাথলজির নয়');
      await keepAccount(user.uid);
      require('../../lib/websession').start(res, user.uid, email);
      return { ok: true };
    }
    if (!(await isAdmin({ email, email_verified: true }))) {
      throw new HttpError(403, 'এই ইমেইল অ্যাডমিন হিসেবে যোগ করা নেই');
    }
    const user = await getOrCreateUser(email);
    if (user.disabled) throw new HttpError(403, 'এই অ্যাকাউন্ট বন্ধ করা আছে');
    await keepAccount(user.uid);
    require('../../lib/websession').start(res, user.uid, email);
    return { ok: true };
  }

  const user = await getOrCreateUser(email);
  // বন্ধ করা অ্যাকাউন্ট: কাঁচা ইংরেজি ত্রুটির বদলে কারণ আর আপিলের ঠিকানা
  if (user.disabled) throw await require('../../lib/firebase').accountDisabledError(req, user.uid);
  const restored = await keepAccount(user.uid);
  const token = await admin.auth().createCustomToken(user.uid);
  return { ok: true, token, restored };
});
