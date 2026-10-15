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

const db = admin.firestore();
const { FieldValue } = admin.firestore;

class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    // অ্যাপ যাতে নির্দিষ্ট অবস্থা চিনতে পারে (যেমন "phone_unverified")
    if (code) this.code = code;
  }
}

/** অ্যাপ থেকে আসা Firebase লগইন টোকেন যাচাই করে uid ফেরত দেয় */
async function verifyUser(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) throw new HttpError(401, 'লগইন দরকার');
  try {
    const decoded = await admin.auth().verifyIdToken(token);
    return decoded.uid;
  } catch {
    throw new HttpError(401, 'লগইনের মেয়াদ শেষ, আবার লগইন করুন');
  }
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
        read: false,
        createdAt: now,
      });
    });
    await batch.commit();
  }
}

async function sendPush(items) {
  try {
    await addInbox(items);
  } catch (e) {
    console.error('inbox write failed', e);
  }
  items = items.filter((it) => it.token);
  let sent = 0;
  for (let i = 0; i < items.length; i += 500) {
    const chunk = items.slice(i, i + 500);
    const messages = chunk.map((it) => ({
      token: it.token,
      notification: { title: it.title, body: it.body },
      data: it.data || {},
      android: {
        priority: 'high',
        notification: { channelId: 'blood_requests', tag: it.data?.requestId || undefined },
      },
    }));
    const result = await admin.messaging().sendEach(messages);
    const cleanup = db.batch();
    let dirty = false;
    result.responses.forEach((r, idx) => {
      if (r.success) {
        sent++;
      } else if (
        ['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(
          r.error?.code,
        )
      ) {
        cleanup.set(db.doc(`users/${chunk[idx].uid}`), { fcmToken: FieldValue.delete() }, { merge: true });
        dirty = true;
      }
    });
    if (dirty) await cleanup.commit();
  }
  return sent;
}

module.exports = { addInbox, admin, db, FieldValue, HttpError, verifyUser, handler, sendPush };
