// লগইন: ইমেইলে ৬ সংখ্যার কোড পাঠানো (লগইন ছাড়াই ডাকা যায়, তাই কড়া সীমা)
const { db, HttpError, handler } = require('../../lib/firebase');
const E = require('../../lib/email-code');
const R = require('../../lib/rules');

module.exports = handler(async (req) => {
  const email = E.normalizeEmail(req.body?.email);
  if (!email) throw new HttpError(400, 'সঠিক ইমেইল দিন');

  const ref = db.doc(`emailCodes/${E.docIdFor(email)}`);
  // একই ইন্টারনেট ঠিকানা থেকে অনেক ইমেইলে কোড চাওয়া আটকানো, আর মোট দৈনিক সীমা (Gmail দিনে ~৫০০)
  const ip = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() || 'unknown';
  const ipRef = db.doc(`serverState/ip_${E.docIdFor(ip).slice(0, 40)}`);
  const globalRef = db.doc('serverState/_emailGlobal');
  const now = Date.now();
  const day = R.dhakaDayKey(now);
  const code = E.newCode();

  await db.runTransaction(async (t) => {
    const [snap, ipSnap, globalSnap] = await Promise.all([t.get(ref), t.get(ipRef), t.get(globalRef)]);
    const d = snap.exists ? snap.data() : {};
    const ipToday = ipSnap.exists && ipSnap.get('day') === day ? ipSnap.get('count') || 0 : 0;
    const globalToday = globalSnap.exists && globalSnap.get('day') === day ? globalSnap.get('count') || 0 : 0;
    if (ipToday >= 20) throw new HttpError(429, 'আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।');
    if (globalToday >= 450) throw new HttpError(503, 'আজকের মতো কোড পাঠানোর সীমা শেষ। কাল আবার চেষ্টা করুন।');
    if (d.lastSentAt && now - d.lastSentAt < E.RESEND_GAP_MS) {
      throw new HttpError(429, 'একটু আগেই কোড পাঠানো হয়েছে। ১ মিনিট পর আবার চেষ্টা করুন।');
    }
    const sentToday = d.sendDay === day ? d.sendCount || 0 : 0;
    if (sentToday >= E.MAX_SENDS_PER_DAY) {
      throw new HttpError(429, 'আজ এই ইমেইলে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।');
    }
    t.set(ipRef, { day, count: ipToday + 1 });
    t.set(globalRef, { day, count: globalToday + 1 });
    t.set(ref, {
      codeHash: E.hashCode(email, code),
      expiresAt: now + E.CODE_TTL_MS,
      attempts: 0,
      lastSentAt: now,
      sendDay: day,
      sendCount: sentToday + 1,
    });
  });

  try {
    await E.sendCodeEmail(email, code);
  } catch (e) {
    console.error('email send failed', e);
    await ref.update({ lastSentAt: 0 }).catch(() => {});
    throw new HttpError(502, 'ইমেইল পাঠানো যায়নি। একটু পর আবার চেষ্টা করুন।');
  }
  return { ok: true };
});
