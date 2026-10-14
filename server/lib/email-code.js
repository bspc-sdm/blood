// ইমেইলে লগইন কোড: তৈরি, সংরক্ষণ (শুধু হ্যাশ), যাচাই, আর Gmail দিয়ে পাঠানো।
const crypto = require('crypto');
const nodemailer = require('nodemailer');

const CODE_TTL_MS = 10 * 60 * 1000; // কোড ১০ মিনিট চলবে
const MAX_ATTEMPTS = 5; // একটা কোডে সর্বোচ্চ ৫ বার ভুল চেষ্টা
const RESEND_GAP_MS = 60 * 1000; // আবার কোড চাইতে কমপক্ষে ১ মিনিট
const MAX_SENDS_PER_DAY = 8; // একটা ইমেইলে দিনে সর্বোচ্চ ৮টা কোড

const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[A-Za-z]{2,24}$/;

function normalizeEmail(raw) {
  const email = String(raw || '').trim().toLowerCase();
  return EMAIL_RE.test(email) && email.length <= 254 ? email : null;
}

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

/** ডাটাবেসে ইমেইল সরাসরি না রেখে তার হ্যাশ দিয়ে ডকুমেন্টের নাম */
function docIdFor(email) {
  return sha256(`email:${email}`);
}

function newCode() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, '0');
}

/** কোডের হ্যাশ (সাথে গোপন লবণ, যাতে ডাটাবেস দেখে কোড বের করা না যায়) */
function hashCode(email, code) {
  const pepper = process.env.CRON_SECRET || '';
  return sha256(`${pepper}:${email}:${code}`);
}

function codesMatch(a, b) {
  const x = Buffer.from(a, 'hex');
  const y = Buffer.from(b, 'hex');
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

let transporter;
function mailer() {
  if (!transporter) {
    const user = process.env.GMAIL_USER;
    const pass = (process.env.GMAIL_APP_PASSWORD || '').replace(/\s+/g, '');
    if (!user || !pass) throw new Error('GMAIL_USER / GMAIL_APP_PASSWORD সেট করা নেই');
    transporter = nodemailer.createTransport({ service: 'gmail', auth: { user, pass } });
  }
  return transporter;
}

async function sendCodeEmail(email, code) {
  await mailer().sendMail({
    from: `"রক্তদান অ্যাপ" <${process.env.GMAIL_USER}>`,
    to: email,
    subject: `আপনার লগইন কোড: ${code}`,
    text:
      `রক্তদান অ্যাপে লগইনের কোড: ${code}\n\n` +
      'কোডটা ১০ মিনিট চলবে। আপনি কোড না চেয়ে থাকলে এই ইমেইল উপেক্ষা করুন, কাউকে কোডটা দেবেন না।',
    html:
      `<p>রক্তদান অ্যাপে লগইনের কোড:</p><p style="font-size:28px;font-weight:bold;letter-spacing:6px">${code}</p>` +
      '<p>কোডটা ১০ মিনিট চলবে। আপনি কোড না চেয়ে থাকলে এই ইমেইল উপেক্ষা করুন, কাউকে কোডটা দেবেন না।</p>',
  });
}

module.exports = {
  CODE_TTL_MS,
  MAX_ATTEMPTS,
  RESEND_GAP_MS,
  MAX_SENDS_PER_DAY,
  normalizeEmail,
  docIdFor,
  newCode,
  hashCode,
  codesMatch,
  sendCodeEmail,
};
