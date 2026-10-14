// লগইন: কোড যাচাই করে Firebase-এর লগইন টোকেন দেওয়া (অ্যাপ এটা দিয়ে লগইন করে)
const { admin, db, HttpError, handler } = require('../../lib/firebase');
const E = require('../../lib/email-code');

module.exports = handler(async (req) => {
  const email = E.normalizeEmail(req.body?.email);
  const code = String(req.body?.code || '').trim();
  if (!email) throw new HttpError(400, 'সঠিক ইমেইল দিন');
  if (!/^\d{6}$/.test(code)) throw new HttpError(400, '৬ সংখ্যার কোড দিন');

  const ref = db.doc(`emailCodes/${E.docIdFor(email)}`);
  const now = Date.now();

  const ok = await db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    if (!snap.exists || !snap.get('codeHash')) throw new HttpError(400, 'আগে কোড চান');
    const d = snap.data();
    if (d.expiresAt < now) throw new HttpError(400, 'কোডের মেয়াদ শেষ। নতুন কোড চান।');
    if ((d.attempts || 0) >= E.MAX_ATTEMPTS) throw new HttpError(429, 'অনেকবার ভুল কোড দেওয়া হয়েছে। নতুন কোড চান।');
    if (!E.codesMatch(d.codeHash, E.hashCode(email, code))) {
      t.update(ref, { attempts: (d.attempts || 0) + 1 });
      return false;
    }
    // একবার ব্যবহার করা কোড আর চলবে না
    t.update(ref, { codeHash: null, expiresAt: 0, attempts: 0 });
    return true;
  });
  if (!ok) throw new HttpError(400, 'কোড মেলেনি। আবার দেখে লিখুন।');

  // এই ইমেইলের অ্যাকাউন্ট (না থাকলে নতুন)
  let user;
  try {
    user = await admin.auth().getUserByEmail(email);
    if (!user.emailVerified) await admin.auth().updateUser(user.uid, { emailVerified: true });
  } catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
    user = await admin.auth().createUser({ email, emailVerified: true });
  }
  const token = await admin.auth().createCustomToken(user.uid);
  return { ok: true, token };
});
