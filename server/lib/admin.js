// অ্যাডমিন যাচাই।
// মালিক (owner): DEFAULT_ADMINS বা Vercel-এর ADMIN_EMAILS — শুধু তিনি অ্যাপ থেকে অন্য অ্যাডমিন যোগ/বাদ দিতে পারেন।
// অন্য অ্যাডমিন: Firestore admins/{ইমেইল} (অ্যাপ সরাসরি পড়তে/লিখতে পারে না)।
const { admin, db, HttpError } = require('./firebase');

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
  if (!(await isAdmin(decoded))) {
    throw new HttpError(403, 'শুধু অ্যাডমিন এটা করতে পারবেন');
  }
  return decoded.uid;
}

/** মালিক কি না (টোকেনের যাচাই করা ইমেইল) */
function isOwner(decoded) {
  const email = (decoded.email || '').toLowerCase();
  return !!decoded.email_verified && adminEmails().includes(email);
}

// অ্যাডমিন তালিকা একটা ডকে (config/admins.emails), ১০ মিনিট মনে রাখা হয় — যাতে প্রতিটা অনুরোধে ডাটাবেস পড়তে না হয়
let cache = { at: 0, set: new Set() };
async function extraAdmins() {
  if (Date.now() - cache.at < 10 * 60 * 1000) return cache.set;
  const s = await db.doc('config/admins').get();
  cache = { at: Date.now(), set: new Set((s.exists && s.get('emails')) || []) };
  return cache.set;
}
function clearCache() {
  cache = { at: 0, set: new Set() };
}

/** যাচাই করা টোকেন অ্যাডমিনের কি না */
async function isAdmin(decoded) {
  if (isOwner(decoded)) return true;
  const email = (decoded.email || '').toLowerCase();
  return !!decoded.email_verified && !!email && (await extraAdmins()).has(email);
}

module.exports = { verifyAdmin, isAdmin, isOwner, adminEmails, clearCache };
