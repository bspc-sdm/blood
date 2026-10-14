// অ্যাডমিন যাচাই: শুধু এই ইমেইলের (Google-যাচাইকৃত) অ্যাকাউন্ট অ্যাডমিনের কাজ করতে পারবে।
// অন্য কাউকে অ্যাডমিন করতে চাইলে Vercel-এ ADMIN_EMAILS (কমা দিয়ে আলাদা) সেট করুন।
const { admin, HttpError } = require('./firebase');

const DEFAULT_ADMINS = ['ekapkbd@gmail.com'];

function adminEmails() {
  const env = (process.env.ADMIN_EMAILS || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
  return env.length ? env : DEFAULT_ADMINS;
}

async function verifyAdmin(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) throw new HttpError(401, 'লগইন দরকার');
  let decoded;
  try {
    decoded = await admin.auth().verifyIdToken(token);
  } catch {
    throw new HttpError(401, 'লগইনের মেয়াদ শেষ, আবার লগইন করুন');
  }
  const email = (decoded.email || '').toLowerCase();
  if (!decoded.email_verified || !adminEmails().includes(email)) {
    throw new HttpError(403, 'শুধু অ্যাডমিন এটা করতে পারবেন');
  }
  return decoded.uid;
}

module.exports = { verifyAdmin };
