const admin = require('firebase-admin');
const { toEnglish } = require('./i18n');

// Vercel-এর Environment Variable থেকে Firebase-এর service account key পড়া হয়।
// FIREBASE_SERVICE_ACCOUNT-এ JSON ফাইলের পুরো লেখাটা থাকবে (চাইলে base64 করেও রাখা যায়)।
function loadServiceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error('FIREBASE_SERVICE_ACCOUNT সেট করা নেই');
  const text = raw.trim().startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8');
  return JSON.parse(text);
}

if (!admin.apps.length) {
  admin.initializeApp({ credential: admin.credential.cert(loadServiceAccount()) });
}

// ডাটাবেস: DATABASE_URL থাকলে PostgreSQL (lib/pgstore.js — Firestore-এর মতো একই API), না থাকলে Firestore।
// লগইন (auth) আর পুশ (messaging) সবসময় Firebase-এই থাকে।
const usePg = !!process.env.DATABASE_URL;
const store = usePg ? require('./pgstore') : null;
const db = usePg ? store.db : admin.firestore();
const FieldValue = usePg ? store.FieldValue : admin.firestore.FieldValue;

class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    // অ্যাপ যাতে নির্দিষ্ট অবস্থা চিনতে পারে (যেমন "phone_unverified")
    if (code) this.code = code;
  }
}

/** অ্যাপ থেকে আসা Firebase লগইন টোকেন যাচাই করে uid ফেরত দেয় */
const SUPPORT_EMAIL = 'ekapkbd@gmail.com';

/** বন্ধ করা অ্যাকাউন্ট: কারণ (থাকলে) আর আপিলের ইমেইলসহ বার্তা; অ্যাপ "account_disabled" দেখে লগআউট করে */
async function accountDisabledError(req, uid) {
  let reason = '';
  if (uid) reason = await db.doc(`serverState/${uid}`).get().then((s) => (s.exists && s.get('disabledReason')) || '').catch(() => '');
  const en = req && req.body && req.body.lang === 'en';
  const msg = en
    ? `Your account was suspended by the Lifolo team.${reason ? ` Reason: ${reason}.` : ''} To appeal, email ${SUPPORT_EMAIL}`
    : `আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে।${reason ? ` কারণ: ${reason}।` : ''} আপিল করতে ইমেইল করুন: ${SUPPORT_EMAIL}`;
  return new HttpError(403, msg, 'account_disabled');
}

/** মোছার অপেক্ষায় থাকা অ্যাকাউন্টের বার্তা (কবে মুছবে, আর আবার লগইন করলে বাতিল) */
function deletionPendingMessage(req, at) {
  const en = req && req.body && req.body.lang === 'en';
  const day = new Date(at).toLocaleDateString(en ? 'en-GB' : 'bn-BD', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'long', year: 'numeric' });
  return en
    ? `Your account will be deleted on ${day}. Log in again before then to keep it.`
    : `আপনার অ্যাকাউন্ট ${day} মুছে যাবে। এর আগে আবার লগইন করলে মোছা বাতিল হবে।`;
}

/** JWT-র ভেতর থেকে uid (যাচাই ছাড়া — শুধু বন্ধ অ্যাকাউন্টের বার্তার জন্য) */
function uidFromToken(token) {
  try {
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')).sub || null;
  } catch {
    return null;
  }
}

/**
 * লগইন টোকেন যাচাই — বন্ধ বা লগইন বাতিল করা অ্যাকাউন্টও সাথে সাথে ধরা পড়ে (১ ঘণ্টা অপেক্ষা নয়)
 */
async function decodeToken(req, token) {
  try {
    return await admin.auth().verifyIdToken(token, true);
  } catch (e) {
    if (['auth/user-disabled', 'auth/id-token-revoked'].includes(e.code)) {
      const uid = uidFromToken(token);
      const u = uid ? await admin.auth().getUser(uid).catch(() => null) : null;
      if (u && u.disabled) throw await accountDisabledError(req, uid);
      // নিজে মোছার অনুরোধ করেছেন (৩০ দিনের অপেক্ষা): অন্য ফোনেও লগআউট, কারণসহ
      if (u && e.code === 'auth/id-token-revoked') {
        const at = await db.doc(`serverState/${uid}`).get().then((s) => (s.exists && s.get('deleteAt')) || 0).catch(() => 0);
        if (at) throw new HttpError(403, deletionPendingMessage(req, at), 'account_disabled');
      }
    }
    const en = req && req.body && req.body.lang === 'en';
    throw new HttpError(401, en ? 'Session expired, please log in again' : 'লগইনের মেয়াদ শেষ, আবার লগইন করুন');
  }
}

async function verifyUser(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) throw new HttpError(401, 'লগইন দরকার');
  const decoded = await decodeToken(req, token);
  return decoded.uid;
}

/** সব API একই নিয়মে: শুধু POST, JSON উত্তর, ভুল হলে বাংলায় বার্তা */
function handler(fn, { methods = ['POST'] } = {}) {
  return async (req, res) => {
    if (!methods.includes(req.method)) {
      res.status(405).json({ error: 'Method not allowed' });
      return;
    }
    try {
      const result = await fn(req, res);
      res.status(200).json(result || { ok: true });
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) console.error(e);
      const en = req.body?.lang === 'en';
      res.status(status).json({
        error: status === 500
          ? (en ? 'Server error, please try again' : 'সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন')
          : en ? toEnglish(e.message) : e.message,
        ...(e && e.code && status !== 500 && typeof e.code === 'string' && /^[a-z_]+$/.test(e.code) ? { code: e.code } : {}),
      });
    }
  };
}

/**
 * পুশ নোটিফিকেশন পাঠানো। items: [{ uid, token, title, body, data }]
 * অকেজো টোকেন (অ্যাপ মুছে ফেলা ইত্যাদি) প্রোফাইল থেকে সরিয়ে দেওয়া হয়।
 * কতগুলো সফল হলো সেটা ফেরত দেয়।
 */
/**
 * অ্যাপের ভেতরের নোটিফিকেশন তালিকা (ঘণ্টা আইকন): inbox/{uid_উল্টো সময়_র‍্যান্ডম}।
 * id-তে উল্টো সময় থাকায় uid দিয়ে খুঁজলে নতুনগুলো আগে আসে (আলাদা ইনডেক্স লাগে না)। ৩০ দিন পর ক্রন মুছে দেয়।
 */
async function addInbox(items) {
  const now = Date.now();
  const rows = items.filter((it) => it.uid && it.title);
  for (let i = 0; i < rows.length; i += 400) {
    const batch = db.batch();
    rows.slice(i, i + 400).forEach((it, k) => {
      const rev = String(9999999999999 - now).padStart(13, '0');
      const id = `${it.uid}_${rev}_${(i + k).toString(36)}${Math.random().toString(36).slice(2, 6)}`;
      batch.set(db.doc(`inbox/${id}`), {
        uid: it.uid,
        title: String(it.title).slice(0, 200),
        body: String(it.body || '').slice(0, 300),
        data: it.data || {},
        createdAt: now,
      });
    });
    await batch.commit();
  }
}

/**
 * একজন ব্যবহারকারীর সব ফোনের পুশ টোকেন (নতুন অ্যাপ: fcmTokens তালিকা, পুরোনো অ্যাপ: fcmToken)।
 * x: users ডকের snapshot বা তার data (uid সহ)। সর্বোচ্চ ৫টা ফোন।
 */
function tokensOf(x) {
  if (!x) return [];
  const get = (k) => (typeof x.get === 'function' ? x.get(k) : x[k]);
  const list = Array.isArray(get('fcmTokens')) ? get('fcmTokens') : [];
  const one = get('fcmToken');
  return [...new Set([...list, ...(one ? [one] : [])].filter((t) => typeof t === 'string' && t))].slice(-5);
}

/**
 * পুশ পাঠানো। items: [{ uid, tokens? | token?, title, body, data }]
 * টোকেন না থাকলেও অ্যাপের ঘণ্টার তালিকায় (inbox) লেখা হয় — ফোনে নোটিফিকেশন বন্ধ থাকলেও পরে দেখা যায়।
 */
async function sendPush(items) {
  try {
    await addInbox(items);
  } catch (e) {
    console.error('inbox write failed', e);
  }
  // প্রতি ফোন একটা বার্তা
  const flat = items.flatMap((it) => {
    const toks = Array.isArray(it.tokens) ? it.tokens : it.token ? [it.token] : [];
    return [...new Set(toks)].map((token) => ({ ...it, token }));
  });
  // একই ফোনে একই বার্তা দুবার নয় (দুই অ্যাকাউন্টে একই টোকেন থাকলেও)
  const seen = new Set();
  const list = flat.filter((it) => {
    // একই ফোন দুই অ্যাকাউন্টে থাকলে দুটোই যায় — ফোন নিজে শুধু এখন ঢোকা অ্যাকাউন্টেরটা দেখায় (forUid)
    const k = `${it.token}|${it.uid || ''}|${it.title}|${it.body}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  // কতজন মানুষ পেলেন (কয়েকটা ফোন থাকলেও একজন একবার গোনা)
  const reached = new Set();
  let anon = 0;
  for (let i = 0; i < list.length; i += 500) {
    const chunk = list.slice(i, i + 500);
    // শুধু data বার্তা: অ্যাপ নিজে নোটিফিকেশন দেখায়, তাই লগআউট করা ফোনে বা অন্য অ্যাকাউন্টে ঢোকা ফোনে
    // আগের অ্যাকাউন্টের নোটিফিকেশন বাদ দিতে পারে ("forUid" = কার জন্য)
    const messages = chunk.map((it) => ({
      token: it.token,
      data: {
        ...Object.fromEntries(Object.entries(it.data || {}).filter(([, v]) => v != null).map(([k, v]) => [k, String(v)])),
        title: String(it.title || '').slice(0, 200),
        body: String(it.body || '').slice(0, 1000),
        ...(it.uid ? { forUid: String(it.uid) } : {}),
      },
      android: { priority: 'high' },
    }));
    const result = await admin.messaging().sendEach(messages);
    const dead = [];
    result.responses.forEach((r, idx) => {
      if (r.success) {
        if (chunk[idx].uid) reached.add(chunk[idx].uid);
        else anon++;
      } else if (
        ['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(
          r.error?.code,
        )
      ) {
        dead.push(chunk[idx]);
      }
    });
    // অচল টোকেন সরানো: তালিকা থেকে, আর পুরোনো ঘরে সেটাই থাকলে সেটাও
    await Promise.all(dead.filter((it) => it.uid).map(async (it) => {
      const ref = db.doc(`users/${it.uid}`);
      try {
        await db.runTransaction(async (t) => {
          const u = await t.get(ref);
          if (!u.exists) return;
          t.update(ref, {
            fcmTokens: FieldValue.arrayRemove(it.token),
            ...(u.get('fcmToken') === it.token ? { fcmToken: FieldValue.delete() } : {}),
          });
        });
      } catch (e) {
        console.error('token cleanup failed', e.message);
      }
    }));
  }
  return reached.size + anon;
}

module.exports = { addInbox, admin, db, FieldValue, HttpError, verifyUser, decodeToken, accountDisabledError, deletionPendingMessage, handler, sendPush, tokensOf };
