// নিষেধাজ্ঞা যা অ্যাকাউন্ট বা তালিকা মুছলেও থাকে — যাতে বন্ধ করা অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংক মুছে বা
// নতুন অ্যাকাউন্ট খুলে (একই ইমেইলে) আবার ফিরে আসা না যায়।
// sanctions/{key}: key = sha256(ধরন:স্বাভাবিক রূপ) — আসল নম্বর/ইমেইল এখানে রাখা হয় না।
const crypto = require('node:crypto');
const { db, HttpError } = require('./firebase');
const C = require('./community');
const K = require('./care');

const { normId } = K;

function key(kind, value) {
  return crypto.createHash('sha256').update(`${kind}:${value}`).digest('hex').slice(0, 40);
}

/**
 * একটা তালিকার যেসব পরিচয় দিয়ে নিষেধাজ্ঞা দেওয়া/খোঁজা হয়।
 * অ্যাম্বুলেন্স: গাড়ির নম্বর (একজন মালিকের অনেক গাড়ি একই ফোনে থাকে, তাই ফোন নয়);
 * ফার্মেসি: লাইসেন্স + ফোন; ব্লাড ব্যাংক: লাইসেন্স (থাকলে) + ফোন।
 */
function listingKeys(kind, x) {
  const keys = [];
  if (kind === 'ambulance') {
    const veh = normId(x.vehicleNo);
    if (veh.length >= 4) keys.push(key('veh', veh));
    return keys;
  }
  const lic = normId(x.licenseNo);
  if (lic.length >= 3) keys.push(key('lic', lic));
  // মোবাইল হলে আন্তর্জাতিক রূপে (আগের নিষেধাজ্ঞার সাথে মেলে), ল্যান্ডলাইন/হটলাইন হলে যেমন আছে
  const phone = K.normalizeBd(x.phone) || K.contactPhone(x.phone);
  if (phone) keys.push(key(`phone_${kind}`, phone));
  return keys;
}

function emailKey(email) {
  const e = String(email || '').trim().toLowerCase();
  return e ? key('email', e) : null;
}

/** বন্ধ করার সময়: তালিকার পরিচয় + মালিকের ইমেইল */
async function ban(kind, x, ownerEmail, info) {
  const keys = [...listingKeys(kind, x), ...(ownerEmail ? [emailKey(ownerEmail)] : [])].filter(Boolean);
  const batch = db.batch();
  keys.forEach((k) => batch.set(db.doc(`sanctions/${k}`), { listing: true, ...info, at: Date.now() }, { merge: true }));
  if (keys.length) await batch.commit();
}

/** আবার চালু করলে (আপিল মঞ্জুর) */
async function unban(kind, x, ownerEmail) {
  const keys = [...listingKeys(kind, x), ...(ownerEmail ? [emailKey(ownerEmail)] : [])].filter(Boolean);
  await Promise.all(keys.map((k) => db.doc(`sanctions/${k}`).delete().catch(() => {})));
}

/**
 * নতুন যোগ বা বদলের আগে: এই গাড়ি/লাইসেন্স/ফোন আগে বন্ধ হয়েছিল কি না;
 * নতুন যোগের সময় মালিকের ইমেইলও (আগের অ্যাকাউন্ট মুছে নতুন খুললেও ধরা পড়ে)
 */
async function checkListing(req, kind, x, email, isNew) {
  const keys = [...listingKeys(kind, x), ...(isNew && email ? [emailKey(email)] : [])].filter(Boolean);
  if (!keys.length) return;
  const snaps = await db.getAll(...keys.map((k) => db.doc(`sanctions/${k}`)));
  if (snaps.some((s) => s.exists && s.get('listing') === true)) {
    throw new HttpError(403, C.tr(req, 'This was suspended by the Lifolo team earlier. You can appeal from Profile → Help & appeals.',
      'এটা আগে Lifolo টিম বন্ধ করেছিল। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
  }
}

/** অ্যাকাউন্ট মোছার সময়: যে নিষেধাজ্ঞা ছিল সেগুলো ইমেইলের সংকেতে থেকে যায় */
async function keepForEmail(email, flags) {
  const k = emailKey(email);
  if (!k || !Object.values(flags).some(Boolean)) return;
  await db.doc(`sanctions/${k}`).set({ ...flags, at: Date.now() }, { merge: true });
}

/** নতুন অ্যাকাউন্টে (একই ইমেইল) আগের নিষেধাজ্ঞা */
async function forEmail(email) {
  const k = emailKey(email);
  if (!k) return {};
  const s = await db.doc(`sanctions/${k}`).get();
  return s.exists ? s.data() : {};
}

/** অ্যাডমিন অবস্থা বদলালে: বন্ধ হলে নিষেধাজ্ঞা, বন্ধ থেকে আবার চালু হলে তুলে নেওয়া */
async function onStatusChange(kind, x, prev, next) {
  try {
    if (next !== 'suspended' && prev !== 'suspended') return;
    let email = null;
    if (x.ownerUid) {
      const { admin } = require('./firebase');
      email = await admin.auth().getUser(x.ownerUid).then((u) => u.email).catch(() => null);
    }
    if (next === 'suspended') {
      await ban(kind, x, email, { kind });
    } else if (prev === 'suspended') {
      // তালিকার পরিচয় তুলে নেওয়া; ইমেইলের নিষেধাজ্ঞা শুধু তখনই, যখন মালিকের আর কোনো বন্ধ তালিকা নেই
      await unban(kind, x, null);
      if (email && x.ownerUid) {
        const others = await Promise.all(['ambulances', 'pharmacies', 'bloodBanks'].map((c) =>
          db.collection(c).where('ownerUid', '==', x.ownerUid).where('status', '==', 'suspended').limit(1).get()));
        if (others.every((s) => s.empty)) {
          const k = emailKey(email);
          if (k) await db.doc(`sanctions/${k}`).set({ listing: false }, { merge: true });
        }
      }
    }
  } catch (e) {
    console.error('sanction update failed', e);
  }
}

module.exports = { normId, listingKeys, emailKey, ban, unban, checkListing, keepForEmail, forEmail, onStatusChange };
