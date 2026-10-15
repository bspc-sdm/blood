// ওয়েব অ্যাডমিন প্যানেলের লগইন সেশন।
// ব্রাউজারে Firebase টোকেন রাখা হয় না; সার্ভার নিজে সই করা একটা httpOnly কুকি দেয়,
// যেটা পাতার কোনো স্ক্রিপ্ট পড়তে পারে না। শুধু অ্যাডমিনদের জন্য, শুধু অ্যাডমিন কাজে চলে।
const crypto = require('crypto');

const COOKIE = '__Host-lf_admin';
const IDLE_MS = 30 * 60 * 1000; // ৩০ মিনিট কিছু না করলে লগআউট
const MAX_MS = 12 * 3600 * 1000; // যত ব্যবহারই হোক, ১২ ঘণ্টা পর আবার লগইন
const REFRESH_MS = 60 * 1000; // মিনিটে একবারের বেশি কুকি নবায়ন নয়
// কুকি দিয়ে আসা অনুরোধে এই হেডার লাগবে — অন্য সাইট থেকে জোর করে পাঠানো অনুরোধ (CSRF) আটকাতে
const CSRF_HEADER = 'x-lifolo-admin';

let cachedKey = null;
function key() {
  if (cachedKey) return cachedKey;
  // আলাদা সিক্রেট না থাকলে service account থেকে বানানো (সেটা শুধু সার্ভারেই আছে)
  const base = process.env.WEB_SESSION_SECRET || process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!base) throw new Error('সেশনের সিক্রেট নেই');
  cachedKey = crypto.createHash('sha256').update('lifolo-admin-web-v1\n' + base).digest();
  return cachedKey;
}

const b64 = (buf) => Buffer.from(buf).toString('base64url');
const sign = (payload) => b64(crypto.createHmac('sha256', key()).update(payload).digest());

function encode(s) {
  const payload = b64(JSON.stringify(s));
  return `${payload}.${sign(payload)}`;
}

/** কুকির লেখা যাচাই করে সেশন ফেরত দেয়; ভুয়া বা মেয়াদ শেষ হলে null */
function decode(token, now = Date.now()) {
  if (typeof token !== 'string' || token.length > 2000) return null;
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [payload, sig] = parts;
  if (!payload || !sig) return null;
  const want = Buffer.from(sign(payload));
  const got = Buffer.from(sig);
  if (want.length !== got.length || !crypto.timingSafeEqual(want, got)) return null;
  let s;
  try {
    s = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (!s || typeof s.u !== 'string' || typeof s.e !== 'string' || !Number.isFinite(s.i) || !Number.isFinite(s.s)) return null;
  if (now - s.s > IDLE_MS || now - s.i > MAX_MS || s.s > now + 60000) return null;
  return s;
}

function cookieValue(req) {
  const raw = req.headers?.cookie || '';
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0 && part.slice(0, i).trim() === COOKIE) return part.slice(i + 1).trim();
  }
  return null;
}

function setCookie(res, value, maxAgeSec) {
  if (!res || typeof res.setHeader !== 'function') return;
  res.setHeader('Set-Cookie', `${COOKIE}=${value}; Path=/; Max-Age=${maxAgeSec}; HttpOnly; Secure; SameSite=Strict`);
}

/** লগইন সফল হলে কুকি বসানো */
function start(res, uid, email, now = Date.now()) {
  setCookie(res, encode({ u: uid, e: email, i: now, s: now }), Math.floor(MAX_MS / 1000));
}

function end(res) {
  setCookie(res, '', 0);
}

/** অনুরোধে ওয়েব সেশনের কুকি আছে কি না (থাকলেই ওয়েবের নিয়ম চলবে) */
function present(req) {
  return !!cookieValue(req);
}

/**
 * কুকি থেকে ব্যবহারকারী। কুকি না থাকলে null।
 * কুকি থাকলে কিন্তু ভুয়া/মেয়াদোত্তীর্ণ/হেডার ছাড়া হলে { error } ফেরত দেয় (ডাকনেওয়ালা 401 দেবে)।
 */
function user(req, res, now = Date.now()) {
  const token = cookieValue(req);
  if (!token) return null;
  if (req.headers?.[CSRF_HEADER] !== '1') return { error: 'csrf' };
  const s = decode(token, now);
  if (!s) {
    end(res);
    return { error: 'expired' };
  }
  if (now - s.s > REFRESH_MS) setCookie(res, encode({ ...s, s: now }), Math.floor(MAX_MS / 1000));
  // অ্যাপের Firebase টোকেনের মতো আকার, যাতে isAdmin/isOwner একইভাবে চলে
  return { uid: s.u, email: s.e, email_verified: true, webSession: true };
}

module.exports = { COOKIE, IDLE_MS, MAX_MS, CSRF_HEADER, encode, decode, start, end, present, user };
