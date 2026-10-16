// লগইন: ইমেইলে ৬ সংখ্যার কোড পাঠানো (লগইন ছাড়াই ডাকা যায়, তাই কড়া সীমা)
const { admin, db, HttpError, handler } = require('../../lib/firebase');
const E = require('../../lib/email-code');
const R = require('../../lib/rules');

// দৈনিক মোট সীমা: আগে থেকে অ্যাকাউন্ট আছে এমন ইমেইল আর নতুন ইমেইল আলাদা,
// যাতে কেউ বানানো ঠিকানায় কোড চেয়ে সীমা শেষ করলেও পুরোনো ব্যবহারকারীরা লগইন করতে পারেন
// (আর অস্তিত্বহীন ঠিকানায় বেশি ইমেইল ফেরত এসে Gmail অ্যাকাউন্ট ঝুঁকিতে না পড়ে)
const DAILY_CAP_EXISTING = 300;
const DAILY_CAP_NEW = 120;
const DAILY_CAP_PER_IP = 50;

async function accountExists(email) {
  try {
    await admin.auth().getUserByEmail(email);
    return true;
  } catch (e) {
    if (e.code === 'auth/user-not-found') return false;
    throw e;
  }
}

module.exports = handler(async (req) => {
  const email = E.normalizeEmail(req.body?.email);
  if (!email) throw new HttpError(400, 'সঠিক ইমেইল দিন');

  const exists = await accountExists(email);
  const ref = db.doc(`emailCodes/${E.docIdFor(email)}`);
  // Vercel x-forwarded-for নিজেই বসায়, তাই এটা বিশ্বাসযোগ্য
  const ip = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() || 'unknown';
  const ipRef = db.doc(`serverState/ip_${E.docIdFor(E.ipKey(ip)).slice(0, 40)}`);
  const globalRef = db.doc(exists ? 'serverState/_emailGlobalExisting' : 'serverState/_emailGlobalNew');
  const globalCap = exists ? DAILY_CAP_EXISTING : DAILY_CAP_NEW;
  const now = Date.now();
  const day = R.dhakaDayKey(now);
  const code = E.newCode();
  const rid = require('crypto').randomBytes(16).toString('hex');

  await db.runTransaction(async (t) => {
    const [snap, ipSnap, globalSnap] = await Promise.all([t.get(ref), t.get(ipRef), t.get(globalRef)]);
    const d = snap.exists ? snap.data() : {};
    const ipToday = ipSnap.exists && ipSnap.get('day') === day ? ipSnap.get('count') || 0 : 0;
    const globalToday = globalSnap.exists && globalSnap.get('day') === day ? globalSnap.get('count') || 0 : 0;
    if (ipToday >= DAILY_CAP_PER_IP) throw new HttpError(429, 'আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।');
    if (globalToday >= globalCap) throw new HttpError(503, 'আজকের মতো কোড পাঠানোর সীমা শেষ। কাল আবার চেষ্টা করুন।');
    if (d.lastSentAt && now - d.lastSentAt < E.RESEND_GAP_MS) {
      throw new HttpError(429, 'একটু আগেই কোড পাঠানো হয়েছে। ১ মিনিট পর আবার চেষ্টা করুন।');
    }
    const sentToday = d.sendDay === day ? d.sendCount || 0 : 0;
    if (sentToday >= E.MAX_SENDS_PER_DAY) {
      throw new HttpError(429, 'আজ এই ইমেইলে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।');
    }
    // আগের চালু কোডগুলো রেখে দেওয়া হয় (সর্বোচ্চ ৩টা), নতুনটা যোগ হয়
    const live = (Array.isArray(d.codes) ? d.codes : []).filter((c) => c && c.exp > now);
    // rid: শুধু যে ফোন/ব্রাউজার কোড চেয়েছে সে জানে — যাচাইয়ে এটা দিলে অন্য কেউ ভুল কোড দিয়ে এই কোড আটকাতে পারে না
    const codes = [...live, { h: E.hashCode(email, code), exp: now + E.CODE_TTL_MS, rid, fails: 0 }].slice(-E.MAX_LIVE_CODES);
    t.set(ipRef, { day, count: ipToday + 1 });
    t.set(globalRef, { day, count: globalToday + 1 });
    t.set(
      ref,
      { codes, lastSentAt: now, sendDay: day, sendCount: sentToday + 1 },
      { merge: true },
    );
  });

  try {
    await E.sendCodeEmail(email, code);
  } catch (e) {
    console.error('email send failed', e);
    await ref.update({ lastSentAt: 0 }).catch(() => {});
    throw new HttpError(502, 'ইমেইল পাঠানো যায়নি। একটু পর আবার চেষ্টা করুন।');
  }
  return { ok: true, rid };
});
