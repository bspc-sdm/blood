// ===== ডেমো (শুধু পরীক্ষার জন্য) =====
// পারভেজ (১৮ অক্টোবর ২০২৬): "সব রুলের জন্য ডেমো একাউন্ট ... পরবর্তীতে যখন বলবো ডিলেট করে দিবা"
// পুরো ফোল্ডার `server/lib/demo/` + community.js-এর একটা লাইন + অ্যাডমিন প্যানেলের "ডেমো" অংশ মুছলেই ডেমো শেষ।
//
// কী করে:
//   admin_demo_seed  — আগের ডেমো মুছে নতুন করে সব ডেমো অ্যাকাউন্ট আর ডামি ডেটা বানায় (শুধু মালিক)
//   admin_demo_wipe  — সব ডেমো অ্যাকাউন্ট আর তাদের সব ডেটা মুছে ফেলে
//   admin_demo_status — কোন অ্যাকাউন্ট, কোন ইমেইল, কত ডক
//
// লগইন: ইমেইল/কোড নেই — অ্যাপ/ওয়েবের লগইন পাতায় "ডেমো" অংশে রোলে চাপলেই সেই অ্যাকাউন্টে ঢোকা
// (verify-code-এ {demoRoles:true} → তালিকা, {demo:"<key>"} → লগইন টোকেন)। ডেমো চালু থাকলেই শুধু চলে।
// ডেমোতে অ্যাডমিন নেই: এক ক্লিকে যে কেউ অ্যাডমিন হলে আসল জিনিস বদলাতে পারত। অ্যাডমিন পরীক্ষা মালিকের নিজের অ্যাকাউন্টে।
// প্রতিটা ডামি ডকে `demo: true` থাকে, আর সব পথ `serverState/_demo`-র তালিকায় — মোছার সময় সেটা ধরে।
const { admin, db, HttpError } = require('../firebase');
const { isOwner } = require('../admin');
const geo = require('../geo');

/** লগইন করা যায় এমন ডেমো অ্যাকাউন্ট — key ইমেইলের অংশ, uid স্থির */
const ROLES = [
  { key: 'patient', name: 'ডেমো রোগী রহিম', bn: 'সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)' },
  { key: 'donor', name: 'ডেমো ডোনার করিম', bn: 'রক্তদাতা' },
  { key: 'doctor', name: 'ডা. ডেমো হাসান', bn: 'ডাক্তার' },
  { key: 'assistant', name: 'ডেমো সহকারী সুমন', bn: 'ডাক্তারের সহকারী' },
  { key: 'pharmacy', name: 'ডেমো ফার্মেসির মালিক', bn: 'ফার্মেসি' },
  { key: 'lab', name: 'ডেমো প্যাথলজির মালিক', bn: 'প্যাথলজি / ল্যাব' },
  { key: 'ambulance', name: 'ডেমো অ্যাম্বুলেন্স চালক', bn: 'অ্যাম্বুলেন্স' },
  { key: 'bloodbank', name: 'ডেমো ব্লাড ব্যাংক', bn: 'ব্লাড ব্যাংক' },
  { key: 'homecare', name: 'ডেমো নার্স নাসরিন', bn: 'বাসায় সেবা (নার্স)' },
  { key: 'caregiver', name: 'ডেমো কেয়ারগিভার রুমা', bn: 'কেয়ারগিভার (রোগীর হয়ে কাজ করেন)' },
];

/** ময়মনসিংহ সদরের কাছে — সব ডামি জিনিস এখানে, যাতে "আশেপাশে" দেখা যায় */
const CENTER = { lat: 24.7539, lng: 90.4073, place: 'ময়মনসিংহ সদর' };

const uidOf = (key) => `demo-${key}`;
const isDemoUid = (uid) => typeof uid === 'string' && uid.startsWith('demo-');
/** ডেমো আর আসল আলাদা: ডেমোর কাজে আসলদের নোটিফিকেশন যায় না, ডেমো ডোনার আসল রিকোয়েস্টে সাড়া দিতে পারে না (আর উল্টোটাও) */
const sameWorld = (a, b) => isDemoUid(a) === isDemoUid(b);

const DOMAIN = 'lifolo-demo.test';
const demoEmail = (key) => `${key}@${DOMAIN}`;
const isDemoEmail = (email) => typeof email === 'string' && email.endsWith(`@${DOMAIN}`);

/** কাছাকাছি একটা জায়গা: dx, dy কিলোমিটারে (পূর্ব, উত্তর) */
function near(dxKm = 0, dyKm = 0) {
  const lat = +(CENTER.lat + dyKm / 111).toFixed(5);
  const lng = +(CENTER.lng + dxKm / (111 * Math.cos((CENTER.lat * Math.PI) / 180))).toFixed(5);
  return { lat, lng, geohash: geo.hash(lat, lng) };
}

const MANIFEST = 'serverState/_demo';

/**
 * ডামি বানানোর সাহায্যকারী — প্রতিটা ভাগের seed(ctx) এটা পায়।
 *   ctx.u.<key>        — { uid, email, name } (লগইন করা যায়)
 *   ctx.set(path, data) — ডক লেখা (demo:true যোগ হয়, পথ তালিকায় ওঠে)
 *   ctx.extraUser(key, profile) — লগইন ছাড়া পেছনের ব্যবহারকারী (যেমন আশেপাশের ডোনার); uid ফেরত
 *   ctx.near(dx, dy), ctx.now, ctx.day (মিলিসেকেন্ড), ctx.CENTER
 */
function makeCtx(users) {
  const paths = new Set();
  const extra = [];
  let batch = db.batch();
  let n = 0;
  const flush = async () => {
    if (n) await batch.commit();
    batch = db.batch();
    n = 0;
  };
  const ctx = {
    u: users,
    paths,
    extraUids: extra,
    now: Date.now(),
    day: 86400000,
    CENTER,
    near,
    async set(path, data, opts) {
      batch.set(db.doc(path), { ...data, demo: true }, opts || {});
      paths.add(path);
      if (++n >= 400) await flush();
    },
    async extraUser(key, profile = {}) {
      const uid = uidOf(`x-${key}`);
      await admin.auth().createUser({ uid, displayName: profile.name || key }).catch((e) => {
        if (e.code !== 'auth/uid-already-exists') throw e;
      });
      extra.push(uid);
      await ctx.set(`users/${uid}`, profile);
      return uid;
    },
    flush,
  };
  return ctx;
}

/** ডেমো অ্যাকাউন্ট (Auth) — থাকলে সেটাই, না থাকলে স্থির uid দিয়ে নতুন */
async function ensureUser(key, email, name) {
  try {
    const u = await admin.auth().getUserByEmail(email);
    // অন্য কেউ নিজের অ্যাকাউন্টে এই ঠিকানা বসিয়ে থাকলে ডেমো সেখানে কখনো নয়
    if (u.uid !== uidOf(key)) throw new HttpError(409, `ডেমো ঠিকানা ${email} অন্য অ্যাকাউন্টে আছে — ডেমো বানানো বন্ধ`);
    return u.uid;
  } catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
  }
  const uid = uidOf(key);
  try {
    await admin.auth().createUser({ uid, email, emailVerified: true, displayName: name });
  } catch (e) {
    if (e.code !== 'auth/uid-already-exists') throw e;
    await admin.auth().updateUser(uid, { email, emailVerified: true, disabled: false });
  }
  return uid;
}

// ভাগগুলো — প্রতিটা { seed(ctx) } দেয়
const PARTS = ['./blood', './services', './rx', './health'];

async function wipe() {
  const m = await db.doc(MANIFEST).get();
  const paths = (m.exists && m.get('paths')) || [];
  const uids = ((m.exists && m.get('uids')) || []).filter(isDemoUid);
  let errors = 0;
  const emails = (m.exists && m.get('emails')) || {};
  // ১) ডেমো অ্যাকাউন্টের সব ডেটা — আসল "অ্যাকাউন্ট মোছা" দিয়ে (পরীক্ষার সময় যা বানানো হয়েছে সেটাও যায়)
  const { deletion } = require('../account');
  for (let i = 0; i < uids.length; i += 10) {
    await Promise.all(uids.slice(i, i + 10).map(async (uid) => {
      await deletion.deleteNow(uid, emails[uid] || null).catch((e) => { errors++; console.error('demo wipe user', uid, e.message); });
      await admin.auth().deleteUser(uid).catch(() => {});
    }));
  }
  // ১ক) ডেমো ডাক্তারের লেখা প্রেসক্রিপশন আর এন্ট্রি করা রোগী (অ্যাকাউন্ট মোছা শুধু রোগীর দিক ধরে) — সাথে সেগুলোর
  //     ফার্মেসি/প্যাথলজির অনুরোধ আর রিপোর্ট দেখার অনুরোধ
  for (const uid of uids) {
    const [rxs, pts] = await Promise.all([
      db.collection('prescriptions').where('doctorUid', '==', uid).limit(500).get(),
      db.collection('rxPatients').where('doctorUid', '==', uid).limit(1000).get(),
    ]);
    for (const rx of rxs.docs) {
      const [orders, labs] = await Promise.all([
        db.collection('rxOrders').where('rxId', '==', rx.id).limit(50).get(),
        db.collection('labOrders').where('rxId', '==', rx.id).limit(50).get(),
      ]);
      const b = db.batch();
      orders.docs.forEach((d) => b.delete(d.ref));
      labs.docs.forEach((d) => { b.delete(d.ref); b.delete(db.doc(`rxReviews/${d.id}`)); });
      b.delete(rx.ref);
      await b.commit();
    }
    for (let i = 0; i < pts.docs.length; i += 400) {
      const b = db.batch();
      pts.docs.slice(i, i + 400).forEach((d) => b.delete(d.ref));
      await b.commit();
    }
  }
  // ২) বানানো ডকগুলো (যেগুলো কারো নামে নয়)
  for (let i = 0; i < paths.length; i += 400) {
    const b = db.batch();
    paths.slice(i, i + 400).forEach((p) => b.delete(db.doc(p)));
    await b.commit();
  }
  // কিছু মোছা না গেলে তালিকা থাকে — আবার "সব ডেমো মুছুন" চাপলে বাকিটা যায়
  if (errors) throw new HttpError(500, `${errors}টা ডেমো অ্যাকাউন্ট পুরো মোছা যায়নি — আবার "সব ডেমো মুছুন" চাপুন`);
  await db.doc(MANIFEST).delete();
  return { users: uids.length, docs: paths.length };
}

async function seed() {
  await wipe();
  const users = {};
  const emails = {};
  for (const r of ROLES) {
    const email = demoEmail(r.key);
    const uid = await ensureUser(r.key, email, r.name);
    users[r.key] = { uid, email, name: r.name };
    emails[uid] = email;
  }
  // আগে থেকেই তালিকায় — মাঝপথে থেমে গেলেও "সব ডেমো মুছুন" সব পায়
  await db.doc(MANIFEST).set({ paths: [], uids: Object.values(users).map((u) => u.uid), emails, at: Date.now(), partial: true });
  const ctx = makeCtx(users);
  for (const p of PARTS) {
    await require(p).seed(ctx);
    await ctx.flush();
    await db.doc(MANIFEST).set({ paths: [...ctx.paths], uids: [...Object.values(users).map((u) => u.uid), ...ctx.extraUids], emails, at: Date.now(), partial: true });
  }
  const uids = [...Object.values(users).map((u) => u.uid), ...ctx.extraUids];
  await db.doc(MANIFEST).set({ paths: [...ctx.paths], uids, emails, at: Date.now() });
  return { users: ROLES.map((r) => ({ key: r.key, role: r.bn, email: users[r.key].email })), docs: ctx.paths.size };
}

/** লগইন পাতার "ডেমো" অংশ: ডেমো চালু থাকলে রোলের তালিকা, নইলে খালি */
async function roles() {
  const m = await db.doc(MANIFEST).get();
  if (!m.exists) return { active: false, roles: [] };
  return { active: true, roles: ROLES.map((r) => ({ key: r.key, name: r.name, role: r.bn })) };
}

/** রোলে চাপলে: সেই ডেমো অ্যাকাউন্টের লগইন টোকেন (ডেমো চালু থাকলেই শুধু) */
async function login(key) {
  const r = ROLES.find((x) => x.key === key);
  const m = await db.doc(MANIFEST).get();
  if (!r || !m.exists) throw new HttpError(404, 'ডেমো চালু নেই');
  const email = demoEmail(r.key);
  const uid = Object.keys(m.get('emails') || {}).find((u) => m.get('emails')[u] === email);
  if (!uid || !isDemoUid(uid)) throw new HttpError(404, 'ডেমো চালু নেই');
  // সাধারণ লগইনের মতো: বন্ধ করা অ্যাকাউন্টে কারণসহ বার্তা
  const au = await admin.auth().getUser(uid).catch(() => null);
  if (au && au.disabled) throw await require('../firebase').accountDisabledError({ body: {} }, uid);
  // সাধারণ লগইনের মতো: মোছার অপেক্ষায় থাকলে আবার লগইনে মোছা বাতিল
  const restored = await require('../account').deletion.cancel(uid).catch(() => false);
  return { ok: true, token: await admin.auth().createCustomToken(uid), restored, demo: true };
}

const actions = {
  async admin_demo_seed(req, user) {
    if (!isOwner(user)) throw new HttpError(403, 'শুধু মালিক');
    return seed();
  },
  async admin_demo_wipe(req, user) {
    if (!isOwner(user)) throw new HttpError(403, 'শুধু মালিক');
    return wipe();
  },
  async admin_demo_status(req, user) {
    if (!isOwner(user)) throw new HttpError(403, 'শুধু মালিক');
    const m = await db.doc(MANIFEST).get();
    const roles = ROLES.map((r) => ({ key: r.key, role: r.bn, email: demoEmail(r.key) }));
    if (!m.exists) return { active: false, roles };
    return { active: true, at: m.get('at'), docs: (m.get('paths') || []).length, roles };
  },
};

module.exports = { actions, seed, wipe, roles, login, ROLES, CENTER, uidOf, isDemoUid, sameWorld, isDemoEmail, demoEmail, near };
