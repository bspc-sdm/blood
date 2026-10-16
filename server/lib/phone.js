// ফোন নম্বর যাচাই (SMS-এ ৬ সংখ্যার কোড)।
// SMS পাঠাতে খরচ হয়, তাই Vercel-এ SMS_URL_TEMPLATE বসানো না থাকলে পুরো ব্যবস্থা বন্ধ থাকে
// (তখন কারো কাছে যাচাই চাওয়া হয় না)। চালু করতে যেকোনো বাংলাদেশি SMS গেটওয়ের GET লিংক দিন, যেমন:
//   https://gateway.example/api/send?api_key=KEY&senderid=ID&number={to}&message={msg}
// {to} = 8801XXXXXXXXX, {msg} = বার্তা (URL-encoded)।
const crypto = require('crypto');
const { db, HttpError } = require('./firebase');
const C = require('./community');
const { normalizeBd } = require('./care');
const { useQuota } = require('./community-db');

const CODE_MS = 10 * 60 * 1000;

function enabled() {
  return !!process.env.SMS_URL_TEMPLATE;
}

function hash(uid, phone, code) {
  const secret = process.env.CRON_SECRET || process.env.FIREBASE_SERVICE_ACCOUNT || 'phone';
  return crypto.createHmac('sha256', `phone-v1:${secret}`).update(`${uid}:${phone}:${code}`).digest('hex');
}

async function send(to, text) {
  const url = process.env.SMS_URL_TEMPLATE.replace('{to}', encodeURIComponent(to)).replace('{msg}', encodeURIComponent(text));
  const r = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error(`SMS gateway ${r.status}`);
}

/** এই ব্যবহারকারীর প্রোফাইলের নম্বর যাচাই করা কি না (ব্যবস্থা বন্ধ থাকলে সবাই যাচাই করা ধরা হয়) */
async function isVerified(uid) {
  if (!enabled()) return true;
  const [u, s] = await Promise.all([db.doc(`users/${uid}`).get(), db.doc(`serverState/${uid}`).get()]);
  const phone = normalizeBd(u.get('phone'));
  return !!phone && s.exists && s.get('verifiedPhone') === phone;
}

async function requireVerified(req, uid) {
  if (!(await isVerified(uid))) {
    throw new HttpError(403, C.tr(req, 'Verify your phone number first', 'আগে আপনার ফোন নম্বর যাচাই করুন'), 'phone_unverified');
  }
}

const actions = {
  async phone_send_code(req, user) {
    if (!enabled()) throw new HttpError(400, C.tr(req, 'Phone verification is not available yet', 'ফোন যাচাই এখনো চালু হয়নি'));
    const u = await db.doc(`users/${user.uid}`).get();
    const phone = normalizeBd(u.get('phone'));
    if (!phone) throw new HttpError(400, C.tr(req, 'Add a valid Bangladeshi mobile number in your profile', 'প্রোফাইলে সঠিক বাংলাদেশি মোবাইল নম্বর দিন'));
    await useQuota(req, user.uid, 'phoneCode');
    // একই নম্বরে: দিনে সর্বোচ্চ ৩টা, দুটোর মাঝে ১ মিনিট — যাতে অনেক অ্যাকাউন্ট দিয়ে কাউকে SMS-এ বিরক্ত করা না যায়
    const R = require('./rules');
    // নম্বর সরাসরি ডকের নামে নয়, গোপন চাবি দিয়ে হ্যাশ করে
    const limRef = db.doc(`phoneLimits/h_${crypto.createHmac('sha256', String(process.env.CRON_SECRET || process.env.FIREBASE_SERVICE_ACCOUNT || 'phone')).update(`lim:${phone}`).digest('hex').slice(0, 40)}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(limRef);
      const day = R.dhakaDayKey();
      const n = s.exists && s.get('day') === day ? s.get('n') || 0 : 0;
      if (n >= 3 || (s.exists && Date.now() - (s.get('at') || 0) < 60 * 1000)) {
        throw new HttpError(429, C.tr(req, 'Too many codes for this number. Try again later.', 'এই নম্বরে অনেকবার কোড গেছে। পরে চেষ্টা করুন।'));
      }
      t.set(limRef, { day, n: n + 1, at: Date.now() });
    });
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    await db.doc(`serverState/${user.uid}`).set(
      { phoneCode: { hash: hash(user.uid, phone, code), phone, exp: Date.now() + CODE_MS, tries: 0 } },
      { merge: true },
    );
    try {
      await send(phone, `Lifolo: your code is ${code}. It expires in 10 minutes.`);
    } catch (e) {
      console.error('sms failed', e);
      throw new HttpError(502, C.tr(req, 'Couldn\'t send the SMS. Try again later.', 'SMS পাঠানো যায়নি। পরে আবার চেষ্টা করুন।'));
    }
    return { ok: true, to: `0${phone.slice(3, 6)}*****${phone.slice(-3)}` };
  },

  async phone_verify_code(req, user) {
    const code = String(req.body?.code || '').replace(/\D/g, '');
    const ref = db.doc(`serverState/${user.uid}`);
    const ok = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const p = s.exists ? s.get('phoneCode') : null;
      if (!p || Date.now() > p.exp || p.tries >= 5) return 'expired';
      if (code.length !== 6 || hash(user.uid, p.phone, code) !== p.hash) {
        t.set(ref, { phoneCode: { ...p, tries: p.tries + 1 } }, { merge: true });
        return 'wrong';
      }
      t.set(ref, { verifiedPhone: p.phone, phoneVerifiedAt: Date.now(), phoneCode: null }, { merge: true });
      return 'ok';
    });
    if (ok === 'expired') throw new HttpError(400, C.tr(req, 'The code expired. Send a new one.', 'কোডের মেয়াদ শেষ। নতুন কোড নিন।'));
    if (ok === 'wrong') throw new HttpError(400, C.tr(req, 'Wrong code', 'কোড মেলেনি'));
    return { ok: true };
  },
};

module.exports = { actions, enabled, isVerified, requireVerified, normalizeBd };
