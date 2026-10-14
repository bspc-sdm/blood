const admin = require('firebase-admin');

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
  constructor(status, message) {
    super(message);
    this.status = status;
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
      res.status(405).json({ error: 'এই পদ্ধতি চলবে না' });
      return;
    }
    try {
      const result = await fn(req, res);
      res.status(200).json(result || { ok: true });
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) console.error(e);
      res.status(status).json({ error: status === 500 ? 'সার্ভারে সমস্যা হয়েছে' : e.message });
    }
  };
}

/**
 * পুশ নোটিফিকেশন পাঠানো। items: [{ uid, token, title, body, data }]
 * অকেজো টোকেন (অ্যাপ মুছে ফেলা ইত্যাদি) প্রোফাইল থেকে সরিয়ে দেওয়া হয়।
 * কতগুলো সফল হলো সেটা ফেরত দেয়।
 */
async function sendPush(items) {
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
        cleanup.update(db.doc(`users/${chunk[idx].uid}`), { fcmToken: FieldValue.delete() });
        dirty = true;
      }
    });
    if (dirty) await cleanup.commit();
  }
  return sent;
}

module.exports = { admin, db, FieldValue, HttpError, verifyUser, handler, sendPush };
