// প্রোফাইল = সিভি (LinkedIn-এর মতো): শিরোনাম, পরিচয়, অভিজ্ঞতা, শিক্ষা, দক্ষতা, ভাষা, সনদ, বাইরের লিংক।
// এটা অ্যাপের মূল প্রোফাইলের অংশ (নিচের বারের "প্রোফাইল")। চাকরি (lib/jobs.js) এটা ব্যবহার করে আবেদনে।
// - শেয়ার লিংক /cv/{uid} (api/share.js) — "লিংক দিয়ে সবাই দেখবে" চালু থাকলে
// - ফোন/ইমেইল কে দেখবে: contactVisibility (কেউ না / যেখানে আবেদন / শেয়ার লিংকেও)
// - ডোনার ব্যাজ (রক্তের গ্রুপ) শেয়ার লিংকে দেখাবে কি না: showDonor (না বললে লুকানো)
// - চাকরির প্রস্তাব নিলে কর্মস্থল "যাচাই করা" (verified) হয় — ব্যবহারকারী নিজে এটা বসাতে পারেন না
const { admin, db, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');

const profileRef = (uid) => db.doc(`profiles/${uid}`);

// চাকরির ক্ষেত্র (সিভির "যে কাজ খুঁজছেন" আর চাকরির বিজ্ঞাপন — দুই জায়গাতেই)
const CATEGORIES = ['health', 'education', 'sales', 'accounts', 'office', 'it', 'driving', 'factory', 'hospitality', 'security', 'construction', 'domestic', 'other'];
const EDUCATION = ['none', 'ssc', 'hsc', 'diploma', 'bachelor', 'masters'];
const VISIBILITY = ['none', 'employers', 'all'];

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const str = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+\n/g, '\n').slice(0, max) : '');
const YM = /^(19[5-9]\d|20\d\d)-(0[1-9]|1[0-2])$/;
const ymOf = (ms) => { const d = new Date(ms); return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`; };
const URL_RE = /^https?:\/\/[^\s<>"']+\.[^\s<>"']+$/i;

function cleanList(v, max, fn) {
  return (Array.isArray(v) ? v : []).slice(0, max).map(fn).filter(Boolean);
}

/**
 * সিভি যাচাই। before: আগের সেভ করা সিভি — "যাচাই করা" কর্মস্থল শুধু আগে থেকে থাকলে টিকে থাকে।
 */
function parseProfile(req, b, before = null) {
  const year = new Date().getUTCFullYear();
  const prevVerified = new Map(((before && before.experience) || []).filter((e) => e.verified === true)
    .map((e) => [`${e.title}|${e.org}|${e.from}`, e]));
  const exp = cleanList(b.experience, 15, (e) => {
    if (!e || typeof e !== 'object') return null;
    const title = str(e.title, 80);
    const org = str(e.org, 100);
    if (!title || !org) return null;
    const from = YM.test(e.from || '') ? e.from : '';
    const current = e.current === true;
    const to = !current && YM.test(e.to || '') ? e.to : '';
    if (from && to && to < from) throw err(req, `"${title}": end date is before start date`, `"${title}": শেষের তারিখ শুরুর আগে`);
    const v = prevVerified.get(`${title}|${org}|${from}`);
    return { title, org, from, to, current, details: str(e.details, 500), ...(v ? { verified: true, ...(v.jobId ? { jobId: v.jobId } : {}) } : {}) };
  });
  const edu = cleanList(b.education, 10, (e) => {
    if (!e || typeof e !== 'object') return null;
    const degree = str(e.degree, 100);
    const institute = str(e.institute, 120);
    if (!degree || !institute) return null;
    const y = Number.isInteger(e.year) && e.year >= 1950 && e.year <= year + 6 ? e.year : null;
    // স্তর (নিয়োগকর্তার ছাঁকার জন্য): এসএসসি, এইচএসসি…
    const level = EDUCATION.includes(e.level) && e.level !== 'none' ? e.level : '';
    return { degree, institute, year: y, result: str(e.result, 40), level };
  });
  const certs = cleanList(b.certs, 20, (e) => {
    if (!e || typeof e !== 'object') return null;
    const name = str(e.name, 100);
    if (!name) return null;
    const y = Number.isInteger(e.year) && e.year >= 1950 && e.year <= year + 1 ? e.year : null;
    const link = typeof e.link === 'string' && URL_RE.test(e.link.trim()) ? e.link.trim().slice(0, 300) : '';
    return { name, issuer: str(e.issuer, 100), year: y, ...(link ? { link } : {}) };
  });
  // বাইরের লিংক: LinkedIn, Facebook, GitHub, ওয়েবসাইট (https://… দিয়ে)
  const links = [...new Set(cleanList(b.links, 5, (l) => {
    const u = typeof l === 'string' ? l.trim() : l && typeof l.url === 'string' ? l.url.trim() : '';
    if (!u) return null;
    const full = /^https?:\/\//i.test(u) ? u : `https://${u}`;
    if (!URL_RE.test(full)) throw err(req, `Check the link: ${u}`, `লিংকটা ঠিক করে লিখুন: ${u}`);
    return full.slice(0, 300);
  }))];
  const words = (v, n, len) => [...new Set(cleanList(v, n, (s) => str(s, len)))];
  return {
    headline: str(b.headline, 100),
    about: str(b.about, 1000),
    experience: exp,
    education: edu,
    skills: words(b.skills, 30, 40),
    languages: words(b.languages, 10, 30),
    certs,
    links,
    contactVisibility: VISIBILITY.includes(b.contactVisibility) ? b.contactVisibility : 'employers',
    public: b.public === true,
    openToWork: b.openToWork === true,
    showDonor: b.showDonor === true,
    wantCategories: K.pickCodes(b.wantCategories, CATEGORIES, 5),
    updatedAt: Date.now(),
  };
}

/** আবেদন করার মতো সিভি: শিরোনাম, আর অভিজ্ঞতা বা শিক্ষা অন্তত একটা */
const cvComplete = (p) => !!(p && p.headline && ((p.experience || []).length || (p.education || []).length));

/** কোন অংশ বাকি (পরামর্শের জন্য) */
function cvMissing(p, u) {
  const x = p || {};
  return [
    !(u && u.get('photo')) && 'photo',
    !x.headline && 'headline',
    !x.about && 'about',
    !(x.experience || []).length && 'experience',
    !(x.education || []).length && 'education',
    !(x.skills || []).length && 'skills',
    !(x.languages || []).length && 'languages',
  ].filter(Boolean);
}

/** সিভি কতটা পূরণ (%) — ছবি, শিরোনাম, পরিচয়, অভিজ্ঞতা, শিক্ষা, দক্ষতা, ভাষা */
function cvCompleteness(p, u) {
  return Math.round(((7 - cvMissing(p, u).length) / 7) * 100);
}

/** মোট অভিজ্ঞতা (পূর্ণ বছর) */
function experienceYears(list, now = Date.now()) {
  let months = 0;
  const cur = ymOf(now);
  for (const e of list || []) {
    if (!e.from) continue;
    const end = e.current ? cur : e.to;
    if (!end) continue;
    const [fy, fm] = e.from.split('-').map(Number);
    const [ty, tm] = end.split('-').map(Number);
    months += Math.max(0, (ty - fy) * 12 + (tm - fm) + 1);
  }
  return Math.floor(months / 12);
}

/** সর্বোচ্চ শিক্ষার স্তর */
function eduLevel(list) {
  let best = 0;
  for (const e of list || []) best = Math.max(best, EDUCATION.indexOf(e.level || 'none'));
  return EDUCATION[Math.max(0, best)];
}

/**
 * সিভি দেখানোর রূপ। contact: ফোন/ইমেইল দেখানো যাবে কি না।
 * roles: { badge, employerVerified } — পেশাদার ব্যাজ আর যাচাই করা নিয়োগকর্তা (থাকলে)
 */
function cvView(uid, p, u, { contact = false, email = '', roles = null, mine = false } = {}) {
  const x = p || {};
  const showDonor = x.showDonor === true;
  return {
    uid,
    name: (u && u.get('name')) || '',
    photo: (u && u.get('photo')) || '',
    district: (u && u.get('placeName')) || '',
    headline: x.headline || '',
    about: x.about || '',
    experience: x.experience || [],
    education: x.education || [],
    skills: x.skills || [],
    languages: x.languages || [],
    certs: x.certs || [],
    links: x.links || [],
    openToWork: x.openToWork === true,
    wantCategories: x.wantCategories || [],
    contactVisibility: x.contactVisibility || 'employers',
    public: x.public === true,
    showDonor,
    // ডোনার ব্যাজ: নিজে সবসময় দেখেন; অন্যরা শুধু চালু থাকলে
    ...((mine || showDonor) && u && u.get('bloodGroup') && u.get('bloodGroup') !== 'unknown'
      ? { bloodGroup: u.get('bloodGroup'), donationCount: u.get('donationCount') || 0 } : {}),
    ...(roles ? { badge: roles.badge || '', employerVerified: roles.employerVerified === true } : {}),
    complete: cvComplete(x),
    completeness: cvCompleteness(x, u),
    missing: mine ? cvMissing(x, u) : [],
    experienceYears: experienceYears(x.experience),
    eduLevel: eduLevel(x.education),
    updatedAt: x.updatedAt || 0,
    ...(contact ? { phone: K.fixPhone(u && u.get('phone')), email } : {}),
  };
}

async function emailOf(uid) {
  return admin.auth().getUser(uid).then((a) => (a.email || '').toLowerCase()).catch(() => '');
}

/** পেশাদার ব্যাজ আর যাচাই করা নিয়োগকর্তা */
async function rolesOf(uid) {
  const [b, e] = await Promise.all([
    db.doc(`badges/${uid}`).get(),
    db.doc(`employers/${uid}`).get(), // FEATURE:jobs — চাকরি মুছলে এই লাইন সরান (ডক না থাকলে এমনিতেই false)
  ]);
  return {
    badge: b.exists && b.get('status') === 'approved' ? b.get('type') || '' : '',
    employerVerified: e.exists && e.get('verified') === true,
  };
}

const actions = {
  async cv_get(req, user) {
    const [p, u, roles] = await Promise.all([profileRef(user.uid).get(), db.doc(`users/${user.uid}`).get(), rolesOf(user.uid)]);
    return { cv: cvView(user.uid, p.exists ? p.data() : null, u, { contact: true, email: (user.email || '').toLowerCase(), roles, mine: true }) };
  },

  async cv_save(req, user) {
    const ref = profileRef(user.uid);
    const before = await ref.get();
    const data = parseProfile(req, req.body || {}, before.exists ? before.data() : null);
    await ref.set(data, { merge: false });
    const [u, roles] = await Promise.all([db.doc(`users/${user.uid}`).get(), rolesOf(user.uid)]);
    return { ok: true, cv: cvView(user.uid, data, u, { contact: true, email: (user.email || '').toLowerCase(), roles, mine: true }) };
  },
};

/** শেয়ার লিংকের পাতার জন্য: সবার জন্য খোলা সিভি */
async function publicCv(uid) {
  if (!R.isValidId(uid)) return null;
  const [p, u] = await Promise.all([profileRef(uid).get(), db.doc(`users/${uid}`).get()]);
  if (!p.exists || p.get('public') !== true) return null;
  const all = p.get('contactVisibility') === 'all';
  return cvView(uid, p.data(), u, { contact: all, email: all ? await emailOf(uid) : '', roles: await rolesOf(uid) });
}

async function deleteForUser(uid) {
  await profileRef(uid).delete();
}

module.exports = {
  actions, CATEGORIES, EDUCATION, parseProfile, cvComplete, cvCompleteness, cvMissing, experienceYears, eduLevel, cvView, emailOf, publicCv, profileRef, deleteForUser,
};
