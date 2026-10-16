// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের (মুছতে FEATURES.md দেখুন)
// চাকরি আর সিভি-প্রোফাইল।
// - সিভি (profiles/{uid}): শিরোনাম, নিজের সম্পর্কে, অভিজ্ঞতা, শিক্ষা, দক্ষতা, ভাষা, সনদ; শেয়ার লিংক (/cv/{uid}) আর অ্যাপে PDF।
// - নিয়োগকর্তা (employers/{uid}): প্রতিষ্ঠানের তথ্য; ট্রেড লাইসেন্সের ছবি দিলে অ্যাডমিন যাচাই করে "যাচাই করা নিয়োগকর্তা"।
// - চাকরি (jobs/{id}): চালু (open) / বিরতি (paused, নতুন আবেদন বন্ধ) / বন্ধ (closed, লোক পাওয়া গেছে)।
//   যাচাই করা নিয়োগকর্তার বিজ্ঞাপন সাথে সাথে চালু — তবে স্বয়ংক্রিয় যাচাইয়ে কিছু ধরা পড়লে অ্যাডমিনের সারিতে।
//   বেতনের সীমা বা "আলোচনাসাপেক্ষ" দিতেই হয়; কোনো ফি চাওয়া নিষেধ। শেষ তারিখ পার হলে বিরতি, নিয়োগকর্তা জানেন।
// - আবেদন (applications/{jobId}_{uid}): আবেদন → দেখা হয়েছে → শর্টলিস্ট → ইন্টারভিউ → প্রস্তাব → নিয়োগ / বাছাই হয়নি।
//   ইন্টারভিউর আমন্ত্রণ আর চাকরির প্রস্তাবে প্রার্থী সাড়া দেন; প্রস্তাব নিলে সিভিতে কর্মস্থল যোগ হয়।
// - সেভ করা চাকরি (jobSaves/{uid}_{jobId}), চাকরির অ্যালার্ট (jobAlerts/{uid}), নিষিদ্ধ শব্দ (config/jobRules)।
//
// ডেটাবেসের সব কাজ নিচের `store`-এ — পরে নিজের সার্ভারে সরালে শুধু এই অংশ বদলাতে হবে।
const { admin, db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');
// সিভি এখন মূল প্রোফাইলের অংশ (lib/cv.js) — চাকরি সেটা ব্যবহার করে
const { CATEGORIES, EDUCATION, cvView, cvComplete, emailOf, profileRef } = require('./cv');

// ---------- ডেটাবেস ----------
const store = {
  profile: (uid) => db.doc(`profiles/${uid}`),
  user: (uid) => db.doc(`users/${uid}`),
  employer: (uid) => db.doc(`employers/${uid}`),
  job: (id) => db.doc(`jobs/${id}`),
  newJob: () => db.collection('jobs').doc(),
  application: (jobId, uid) => db.doc(`applications/${jobId}_${uid}`),
  save: (uid, jobId) => db.doc(`jobSaves/${uid}_${jobId}`),
  alert: (uid) => db.doc(`jobAlerts/${uid}`),
  rules: () => db.doc('config/jobRules'),
  openJobsIn: (district) => db.collection('jobs').where('status', '==', 'open').where('district', '==', district).limit(400).get(),
  jobsOf: (employerUid) => db.collection('jobs').where('employerUid', '==', employerUid).limit(100).get(),
  jobsByStatus: (status) => db.collection('jobs').where('status', '==', status).limit(300).get(),
  /** দৈনিক কাজের জন্য সব চালু বিজ্ঞাপন (পাতা ধরে) */
  allOpen: async () => {
    const out = [];
    let last = null;
    for (;;) {
      let q = db.collection('jobs').where('status', '==', 'open').orderBy('__name__').limit(500);
      if (last) q = q.startAfter(last);
      const s = await q.get();
      out.push(...s.docs);
      if (s.size < 500) return out;
      last = s.docs[s.size - 1];
    }
  },
  applicationsOf: (uid) => db.collection('applications').where('uid', '==', uid).limit(200).get(),
  applicationsFor: (jobId) => db.collection('applications').where('jobId', '==', jobId).limit(300).get(),
  applicationsByStatus: (status) => db.collection('applications').where('status', '==', status).limit(500).get(),
  interviewsOfEmployer: (uid) => db.collection('applications').where('employerUid', '==', uid).where('status', '==', 'interview').limit(200).get(),
  savesOf: (uid) => db.collection('jobSaves').where('uid', '==', uid).limit(200).get(),
  savesFor: (jobId) => db.collection('jobSaves').where('jobId', '==', jobId).limit(500).get(),
  savesDue: (deadline) => db.collection('jobSaves').where('deadline', '==', deadline).limit(500).get(),
  alertsIn: (district) => db.collection('jobAlerts').where('district', '==', district).where('on', '==', true).limit(500).get(),
  employersByStatus: (status) => db.collection('employers').where('verifyStatus', '==', status).limit(300).get(),
  getAll: (refs) => (refs.length ? db.getAll(...refs) : Promise.resolve([])),
};

// ---------- তালিকা ----------
const TYPES = ['fulltime', 'parttime', 'contract', 'internship', 'remote'];
const EMPLOYER_KINDS = ['company', 'shop', 'hospital', 'school', 'ngo', 'household', 'other'];
const COVER = ['optional', 'required', 'off'];
const Q_KINDS = ['text', 'choice', 'yesno'];
// নিয়োগকর্তা যা দিতে পারেন ("নিয়োগ" আসে প্রার্থী প্রস্তাব নিলে)
const APP_STATUSES = ['shortlisted', 'interview', 'offered', 'rejected'];
const REJECT_REASONS = ['experience', 'skills', 'education', 'location', 'filled', 'other'];
const WITHDRAW_REASONS = ['got_job', 'salary', 'location', 'other'];
const MAX_DEADLINE_DAYS = 90;
const MAX_ACTIVE_JOBS = 30;
const MAX_INTERN_MONTHS = 6;
const LIVE = ['open', 'paused', 'pending', 'changes'];

const err = (req, en, bn, status = 400, code) => new HttpError(status, C.tr(req, en, bn), code);
const str = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+\n/g, '\n').slice(0, max) : '');
const YM = /^(19[5-9]\d|20\d\d)-(0[1-9]|1[0-2])$/;
const ymOf = (ms) => { const d = new Date(ms); return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`; };

function cleanList(v, max, fn) {
  return (Array.isArray(v) ? v : []).slice(0, max).map(fn).filter(Boolean);
}

// ---------- চাকরি ----------
function parseQuestions(req, v) {
  const seen = new Set();
  return cleanList(v, 5, (q, i) => {
    if (!q || typeof q !== 'object') return null;
    const text = C.cleanText(q.text, 200);
    if (!text) return null;
    const kind = Q_KINDS.includes(q.kind) ? q.kind : 'text';
    let id = typeof q.id === 'string' && /^q[0-9a-z]{1,8}$/.test(q.id) ? q.id : `q${Math.random().toString(36).slice(2, 8)}`;
    while (seen.has(id)) id = `q${Math.random().toString(36).slice(2, 8)}`;
    seen.add(id);
    const options = kind === 'choice' ? [...new Set(cleanList(q.options, 6, (o) => str(o, 60)))] : [];
    if (kind === 'choice' && options.length < 2) throw err(req, `"${text}": give at least 2 choices`, `"${text}": অন্তত ২টা উত্তর বিকল্প দিন`);
    return { id, text, kind, options, required: q.required === true };
  });
}

const FEE_RE = /(জামানত|রেজিস্ট্রেশন\s*ফি|আবেদন\s*ফি|ট্রেনিং\s*ফি|registration\s*fee|application\s*fee|training\s*fee|security\s*deposit)/i;

function parseJob(req, b) {
  const title = C.cleanText(b.title, 100);
  if (!title) throw err(req, 'Write the job title', 'পদের নাম লিখুন');
  if (!CATEGORIES.includes(b.category)) throw err(req, 'Choose the job category', 'চাকরির ধরন বেছে নিন');
  if (!TYPES.includes(b.type)) throw err(req, 'Choose full-time, part-time etc.', 'ফুলটাইম, পার্টটাইম ইত্যাদি বেছে নিন');
  if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
  const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
  const intern = b.type === 'internship';
  const paid = intern ? b.paid === true : true;
  const durationMonths = intern ? b.durationMonths : null;
  if (intern && !(Number.isInteger(durationMonths) && durationMonths >= 1 && durationMonths <= MAX_INTERN_MONTHS)) {
    throw err(req, `Internship length: 1 to ${MAX_INTERN_MONTHS} months`, `ইন্টার্নশিপের মেয়াদ ১ থেকে ${MAX_INTERN_MONTHS} মাস`);
  }
  const negotiable = paid && b.salaryNegotiable === true;
  let min = paid && Number.isInteger(b.salaryMin) ? b.salaryMin : 0;
  let max = paid && Number.isInteger(b.salaryMax) ? b.salaryMax : 0;
  // বেতন: সীমা, অথবা "আলোচনাসাপেক্ষ" — পুরো লুকানো যায় না (বিনা বেতনের ইন্টার্নশিপ বাদে)
  if (paid && !negotiable && (min < 500 || max < min || max > 5000000)) {
    throw err(req, 'Enter the monthly salary range (minimum and maximum), or mark it negotiable', 'মাসিক বেতনের সীমা দিন (সর্বনিম্ন আর সর্বোচ্চ), অথবা "আলোচনাসাপেক্ষ" বেছে নিন');
  }
  if (negotiable && (min || max) && (min < 500 || max < min || max > 5000000)) {
    throw err(req, 'Check the salary range', 'বেতনের সীমা ঠিক করে লিখুন');
  }
  if (!paid) { min = 0; max = 0; }
  const description = C.cleanText(b.description, 3000);
  if (!description || description.length < 30) throw err(req, 'Describe the work (at least 30 characters)', 'কাজের বর্ণনা লিখুন (অন্তত ৩০ অক্ষর)');
  const today = R.todayDhakaUtcMidnight();
  const deadline = Number.isInteger(b.deadline) ? b.deadline : 0;
  if (deadline < today || deadline > today + MAX_DEADLINE_DAYS * R.DAY_MS) {
    throw err(req, `Set an application deadline within ${MAX_DEADLINE_DAYS} days`, `আবেদনের শেষ তারিখ দিন (আজ থেকে ${MAX_DEADLINE_DAYS} দিনের মধ্যে)`);
  }
  // ফি চাওয়া নিষেধ — স্পষ্ট টাকার দাবি থাকলে আটকানো
  if (FEE_RE.test(`${title} ${description}`)) {
    throw err(req, 'Asking job seekers for any fee or deposit is not allowed', 'প্রার্থীর কাছে কোনো ফি বা জামানত চাওয়া যাবে না');
  }
  return {
    title,
    category: b.category,
    type: b.type,
    district: b.district,
    upazilaId,
    salaryMin: min,
    salaryMax: max,
    salaryNegotiable: negotiable,
    paid,
    durationMonths: intern ? durationMonths : null,
    vacancies: Number.isInteger(b.vacancies) && b.vacancies >= 1 && b.vacancies <= 500 ? b.vacancies : 1,
    experienceYears: Number.isInteger(b.experienceYears) && b.experienceYears >= 0 && b.experienceYears <= 40 ? b.experienceYears : 0,
    education: EDUCATION.includes(b.education) ? b.education : 'none',
    skills: [...new Set(cleanList(b.skills, 15, (s) => str(s, 40)))],
    description,
    deadline,
    questions: parseQuestions(req, b.questions),
    coverLetter: COVER.includes(b.coverLetter) ? b.coverLetter : 'optional',
    updatedAt: Date.now(),
  };
}

// ---------- স্বয়ংক্রিয় যাচাই (ধরা পড়লে অ্যাডমিনের সারিতে) ----------
const DEFAULT_WORDS = [
  'MLM', 'এমএলএম', 'পিরামিড', 'pyramid', 'নেটওয়ার্ক মার্কেটিং', 'network marketing', 'ঘরে বসে আয়', 'ঘরে বসে ইনকাম',
  'দৈনিক ইনকাম', 'earn daily', 'ডলার ইনকাম', 'বিনিয়োগ করে আয়', 'investment required', 'অনলাইনে আয়', 'easy money',
];
// কারণ ছাড়া লিঙ্গ/ধর্ম/বয়সের শর্ত — বাতিল নয়, অ্যাডমিন দেখেন (বৈধ কারণ থাকতে পারে)
const CONDITION_RE = /((শুধু|শুধুমাত্র|কেবল|কেবলমাত্র)\s*(পুরুষ|মহিলা|নারী|ছেলে|মেয়ে|মুসলিম|মুসলমান|হিন্দু|খ্রিস্টান|বৌদ্ধ))|(only\s+(male|female|men|women|boys|girls|muslim|hindu|christian))|((মুসলিম|মুসলমান|হিন্দু|খ্রিস্টান|বৌদ্ধ)\s*(হতে\s*হবে|প্রার্থী))|(বয়স\s*.{0,12}(এর\s*মধ্যে|এর\s*নিচে|সর্বোচ্চ|অনূর্ধ্ব))|((age|aged)\s*(below|under|max|limit))/i;

let wordsCache = { at: 0, words: [] };
async function bannedWords() {
  if (Date.now() - wordsCache.at < 60000) return wordsCache.words;
  const s = await store.rules().get().catch(() => null);
  const extra = s && s.exists ? s.get('words') || [] : [];
  wordsCache = { at: Date.now(), words: [...new Set([...DEFAULT_WORDS, ...extra])] };
  return wordsCache.words;
}

/** বিজ্ঞাপনে সন্দেহের কারণ (খালি = ঠিক আছে) */
function flagsFor(job, words) {
  const flags = [];
  const text = `${job.title}\n${job.description}\n${(job.skills || []).join(' ')}`;
  const low = text.toLowerCase();
  const hit = words.filter((w) => w && low.includes(String(w).toLowerCase()));
  if (hit.length) flags.push({ code: 'words', text: `সন্দেহজনক শব্দ: ${hit.slice(0, 5).join(', ')}` });
  if (job.paid && !job.salaryNegotiable && ['fulltime', 'contract', 'remote'].includes(job.type) && job.salaryMax < 5000) {
    flags.push({ code: 'low_salary', text: 'ফুলটাইম কাজে মাসে ৫,০০০ টাকার কম বেতন' });
  }
  if ((job.description || '').length < 80) flags.push({ code: 'vague', text: 'বর্ণনা খুব ছোট বা অস্পষ্ট' });
  if (CONDITION_RE.test(text)) flags.push({ code: 'condition', text: 'লিঙ্গ, ধর্ম বা বয়সের শর্ত আছে — বৈধ কারণ আছে কি না দেখুন' });
  if ((job.type === 'internship' && job.experienceYears > 0) || job.experienceYears >= 15) {
    flags.push({ code: 'unrealistic', text: 'অভিজ্ঞতার শর্ত পদের সাথে মেলে না' });
  }
  return flags;
}

function jobView(d, extra = {}) {
  const x = d.data();
  return {
    id: d.id,
    title: x.title,
    orgName: x.orgName || '',
    employerVerified: x.employerVerified === true,
    category: x.category,
    type: x.type,
    district: x.district,
    upazilaId: x.upazilaId || null,
    salaryMin: x.salaryMin || 0,
    salaryMax: x.salaryMax || 0,
    salaryNegotiable: x.salaryNegotiable === true,
    paid: x.paid !== false,
    durationMonths: x.durationMonths || null,
    vacancies: x.vacancies || 1,
    experienceYears: x.experienceYears || 0,
    education: x.education || 'none',
    skills: x.skills || [],
    deadline: x.deadline || 0,
    createdAt: x.createdAt || 0,
    applicants: x.applicants || 0,
    status: x.status,
    pausedBy: x.status === 'paused' ? x.pausedBy || '' : '',
    coverLetter: x.coverLetter || 'optional',
    questions: x.questions || [],
    ...extra,
  };
}

function employerView(s, mine = false) {
  if (!s || !s.exists) return null;
  const x = s.data();
  return {
    uid: s.id,
    name: x.name,
    kind: x.kind,
    district: x.district,
    upazilaId: x.upazilaId || null,
    address: x.address || '',
    website: x.website || '',
    about: x.about || '',
    verified: x.verified === true,
    ...(mine ? {
      phone: K.fixPhone(x.phone), verifyStatus: x.verifyStatus || 'none', statusReason: x.statusReason || '',
      docsRequested: !!x.docsRequestedAt, docsRequestNote: x.docsRequestNote || '',
      postingBlocked: x.postingBlocked === true, blockReason: x.blockReason || '',
    } : {}),
  };
}

/** আবেদনের সাথে থাকা ইন্টারভিউ/প্রস্তাব/কারণ (প্রার্থী আর নিয়োগকর্তা দুজনেই দেখেন) */
function appExtras(a) {
  return {
    reason: a.get('reason') || '',
    interview: a.get('interview') || null,
    offer: a.get('offer') || null,
    withdrawReason: a.get('withdrawReason') || '',
    withdrawNote: a.get('withdrawNote') || '',
  };
}

async function pushTo(uid, make) {
  await require('./requestend').pushUsers([uid], make);
}

const APP_TEXT = {
  seen: ['Your application was seen', 'আপনার আবেদন দেখা হয়েছে'],
  shortlisted: ['You were shortlisted', 'আপনি শর্টলিস্টে আছেন'],
  interview: ['Interview invitation', 'ইন্টারভিউয়ের আমন্ত্রণ'],
  offered: ['You got a job offer', 'আপনি চাকরির প্রস্তাব পেয়েছেন'],
  rejected: ['Not selected this time', 'এবার বাছাই হননি'],
  closed: ['Hiring has closed', 'নিয়োগ বন্ধ হয়েছে'],
};

// বাছাই না হলে প্রার্থী যা দেখেন (ভদ্র ভাষায়)
const REJECT_TEXT = {
  experience: ['More experience was needed for this role', 'এই পদে আরও অভিজ্ঞতা দরকার ছিল'],
  skills: ['The required skills did not match', 'দরকারি দক্ষতার সাথে মেলেনি'],
  education: ['The education requirement did not match', 'শিক্ষাগত যোগ্যতা মেলেনি'],
  location: ['The work location did not suit', 'কাজের জায়গার সাথে মেলেনি'],
  filled: ['The position has been filled', 'পদটা পূরণ হয়ে গেছে'],
  other: ['Another candidate was chosen', 'অন্য প্রার্থী বাছাই হয়েছেন'],
};

/** চালু হলে অ্যালার্ট চালু রাখা মানুষদের জানানো (একবারই) */
async function sendAlerts(id, job) {
  try {
    if (job.alertedAt) return 0;
    await store.job(id).update({ alertedAt: Date.now() });
    const snap = await store.alertsIn(job.district);
    const uids = snap.docs.filter((d) => {
      const a = d.data();
      if (d.id === job.employerUid) return false;
      if ((a.categories || []).length && !a.categories.includes(job.category)) return false;
      if (a.type && a.type !== job.type) return false;
      if (a.minSalary > 0 && !job.salaryNegotiable && (job.salaryMax || 0) < a.minSalary) return false;
      if (Number.isInteger(a.upazilaId) && job.upazilaId && a.upazilaId !== job.upazilaId) return false;
      return true;
    }).map((d) => d.id);
    if (!uids.length) return 0;
    return await require('./requestend').pushUsers(uids, (u, lang) => ({
      title: R.L(lang, 'New job for you', 'আপনার জন্য নতুন চাকরি'),
      body: [job.title, job.orgName].filter(Boolean).join(' · '),
      data: { type: 'job_alert', jobId: id },
    }));
  } catch (e) {
    console.error('job alerts failed', e.message);
    return 0;
  }
}

/** শেষ তারিখ বদলালে সেভ করা চাকরির মনে করানোর তারিখও */
async function syncSaves(jobId, deadline) {
  try {
    const snap = await store.savesFor(jobId);
    if (!snap.size) return;
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { deadline }));
    await batch.commit();
  } catch (e) {
    console.error('save sync failed', e.message);
  }
}

async function ownJob(req, user, id) {
  if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
  const s = await store.job(id).get();
  if (!s.exists || s.get('employerUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
  return s;
}

/** প্রার্থীর উত্তর যাচাই */
function parseAnswers(req, job, raw) {
  const given = raw && typeof raw === 'object' ? raw : {};
  const out = {};
  for (const q of job.questions || []) {
    let v = given[q.id];
    if (q.kind === 'yesno') v = v === 'yes' || v === 'no' ? v : '';
    else if (q.kind === 'choice') v = (q.options || []).includes(v) ? v : '';
    else v = str(v, 500);
    if (!v && q.required) throw err(req, `Answer: "${q.text}"`, `উত্তর দিন: "${q.text}"`, 400, 'answers');
    if (v) out[q.id] = v;
  }
  return out;
}

const actions = {
  // ================= নিয়োগকর্তা =================
  async employer_get(req, user) {
    const s = await store.employer(user.uid).get();
    return { employer: employerView(s, true) };
  },

  async employer_save(req, user) {
    const b = req.body || {};
    const name = C.cleanText(b.name, 100);
    if (!name) throw err(req, 'Write the organisation or your name', 'প্রতিষ্ঠান বা আপনার নাম লিখুন');
    if (!EMPLOYER_KINDS.includes(b.kind)) throw err(req, 'Choose the type', 'ধরন বেছে নিন');
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const phone = K.contactPhone(b.phone);
    if (!phone) throw err(req, 'Enter a valid phone number', 'সঠিক ফোন নম্বর দিন');
    const website = str(b.website, 100);
    if (website && !/^(https?:\/\/)?[a-z0-9.-]+\.[a-z]{2,}(\/\S*)?$/i.test(website)) throw err(req, 'Enter a valid website', 'সঠিক ওয়েবসাইট দিন');
    const data = {
      name, kind: b.kind, district: b.district,
      upazilaId: Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null,
      address: str(b.address, 200), phone, website, about: str(b.about, 600), updatedAt: Date.now(),
    };
    const ref = store.employer(user.uid);
    const s = await ref.get();
    const V = require('./verify');
    const x = s.exists ? s.data() : null;
    // যাচাই করা নিয়োগকর্তার নাম/ফোন বড় করে বদলালে আবার যাচাই
    const reverify = x && x.verified === true && ((!K.smallNameEdit(x.name, name) && x.name !== name) || x.phone !== phone);
    const hasDocs = b.docs && typeof b.docs === 'object' && Object.keys(b.docs).length > 0;
    if (!x) {
      const docsHave = hasDocs ? await V.saveDocs(req, 'employer', user.uid, user.uid, data, b.docs, true) : null;
      await ref.set({
        ...data, ownerUid: user.uid, verified: false, verifyStatus: docsHave ? 'pending' : 'none', docsHave: docsHave || {}, createdAt: Date.now(),
      });
    } else {
      const docsHave = hasDocs ? await V.saveDocs(req, 'employer', user.uid, user.uid, { ...x, ...data }, b.docs, false) : null;
      // ট্রেড লাইসেন্স থাকলে তবেই যাচাইয়ের অপেক্ষায় (শুধু অফিসের ছবিতে নয়); যাচাই করা নিয়োগকর্তা ছবি দিলেও যাচাই থাকে
      const pending = reverify || (docsHave && x.verified !== true && !!(docsHave.tradeLicense || (x.docsHave || {}).tradeLicense));
      await ref.update({
        ...data,
        ...(pending && x.verifyStatus !== 'pending' ? { verifyStatus: 'pending', verified: false, docsRequestedAt: FieldValue.delete() } : {}),
      });
      if (x.name !== name || reverify) {
        const jobs = await store.jobsOf(user.uid);
        const batch = db.batch();
        jobs.docs.forEach((d) => batch.update(d.ref, { orgName: name, ...(reverify ? { employerVerified: false } : {}) }));
        if (jobs.size) await batch.commit();
      }
    }
    return { ok: true, employer: employerView(await ref.get(), true) };
  },

  // ================= চাকরি: নিয়োগকর্তা =================
  async job_save(req, user) {
    const b = req.body || {};
    const emp = await store.employer(user.uid).get();
    if (!emp.exists) throw err(req, 'Add your organisation details first', 'আগে প্রতিষ্ঠানের তথ্য দিন');
    if (emp.get('postingBlocked') === true) {
      throw err(req, 'The Dorkar team has stopped job posting for your account', 'Dorkar টিম আপনার অ্যাকাউন্টে চাকরির বিজ্ঞাপন দেওয়া বন্ধ রেখেছে', 403);
    }
    const data = parseJob(req, b);
    const flags = flagsFor(data, await bannedWords());
    const verified = emp.get('verified') === true;
    const ok = verified && !flags.length;
    if (b.id) {
      const s = await ownJob(req, user, b.id);
      const was = s.get('status');
      if (['suspended', 'closed'].includes(was)) throw err(req, 'This job is closed', 'এই বিজ্ঞাপন বন্ধ');
      // ফেরত পাঠানো/বাতিল → আবার দেখা; যাচাই না করা বা সন্দেহ থাকলে → অপেক্ষায়; বাকিটা আগের মতো
      const status = ['rejected', 'changes', 'pending'].includes(was) || !ok ? 'pending' : was;
      await s.ref.update({
        ...data, flags, orgName: emp.get('name'), employerVerified: verified, status,
        ...(status !== was ? { statusReason: FieldValue.delete() } : {}),
      });
      if (data.deadline !== s.get('deadline')) await syncSaves(b.id, data.deadline);
      if (status === 'open') await sendAlerts(b.id, { ...s.data(), ...data, orgName: emp.get('name'), employerUid: user.uid });
      return { ok: true, id: b.id, status, flags: flags.length > 0 };
    }
    const mine = await store.jobsOf(user.uid);
    if (mine.docs.filter((d) => LIVE.includes(d.get('status'))).length >= MAX_ACTIVE_JOBS) {
      throw err(req, `Up to ${MAX_ACTIVE_JOBS} active jobs at a time`, `একসাথে সর্বোচ্চ ${MAX_ACTIVE_JOBS}টা চালু বিজ্ঞাপন`);
    }
    await useQuota(req, user.uid, 'job');
    const ref = store.newJob();
    const status = ok ? 'open' : 'pending';
    const doc = {
      ...data, flags, employerUid: user.uid, orgName: emp.get('name'), employerVerified: verified, status, applicants: 0, createdAt: Date.now(),
    };
    await ref.set(doc);
    if (status === 'open') await sendAlerts(ref.id, doc);
    return { ok: true, id: ref.id, status, flags: flags.length > 0 };
  },

  /** বিরতি (নতুন আবেদন বন্ধ) বা আবার চালু */
  async job_pause(req, user) {
    const s = await ownJob(req, user, req.body?.id);
    const pause = req.body?.pause === true;
    const st = s.get('status');
    if (pause) {
      if (st !== 'open') throw err(req, 'Only a live job can be paused', 'শুধু চালু বিজ্ঞাপনে বিরতি দেওয়া যায়');
      await s.ref.update({ status: 'paused', pausedBy: 'employer', pausedAt: Date.now() });
      return { ok: true, status: 'paused' };
    }
    if (st !== 'paused') throw err(req, 'This job is not paused', 'এই বিজ্ঞাপনে বিরতি নেই');
    if ((s.get('deadline') || 0) < R.todayDhakaUtcMidnight()) {
      throw err(req, 'The deadline has passed. Edit the job and set a new deadline first.', 'শেষ তারিখ পার হয়ে গেছে। আগে বিজ্ঞাপন বদলে নতুন শেষ তারিখ দিন।', 400, 'deadline');
    }
    await s.ref.update({ status: 'open', pausedBy: FieldValue.delete(), pausedAt: FieldValue.delete() });
    return { ok: true, status: 'open' };
  },

  /** নিয়োগ বন্ধ (লোক পাওয়া গেছে): যাঁদের এখনো সিদ্ধান্ত হয়নি তাঁদের জানানো */
  async job_close(req, user) {
    const s = await ownJob(req, user, req.body?.id);
    if (s.get('status') === 'closed') return { ok: true };
    if (s.get('status') === 'suspended') throw err(req, 'This job is closed', 'এই বিজ্ঞাপন বন্ধ');
    await s.ref.update({ status: 'closed', closedAt: Date.now(), closedBy: 'employer' });
    await notifyClosed(s.id, s.data());
    return { ok: true };
  },

  async my_jobs(req, user) {
    const [snap, emp] = await Promise.all([store.jobsOf(user.uid), store.employer(user.uid).get()]);
    const items = snap.docs.map((d) => jobView(d, { statusReason: d.get('statusReason') || '' })).sort((a, b) => b.createdAt - a.createdAt);
    return { items, employer: employerView(emp, true) };
  },

  /** আবেদনকারীরা — খুললেই "আবেদন করা"গুলো "দেখা হয়েছে" হয় */
  async job_applicants(req, user) {
    const s = await ownJob(req, user, req.body?.id);
    const id = s.id;
    // বন্ধ করা (রিপোর্ট/অ্যাডমিন) বা বাতিল বিজ্ঞাপনে প্রার্থীর যোগাযোগ আর দেখা যায় না
    if (['suspended', 'rejected'].includes(s.get('status'))) throw err(req, 'This job was taken down', 'এই বিজ্ঞাপন বন্ধ করা হয়েছে', 403);
    const apps = (await store.applicationsFor(id)).docs;
    const uids = apps.map((d) => d.get('uid'));
    const [profiles, users, emails] = await Promise.all([
      store.getAll(uids.map((u) => store.profile(u))),
      store.getAll(uids.map((u) => store.user(u))),
      Promise.all(uids.map((u) => emailOf(u))),
    ]);
    const now = Date.now();
    const unseen = apps.filter((d) => d.get('status') === 'applied');
    if (unseen.length) {
      const batch = db.batch();
      unseen.forEach((d) => batch.update(d.ref, { status: 'seen', seenAt: now, updatedAt: now }));
      await batch.commit();
      await require('./requestend').pushUsers(unseen.map((d) => d.get('uid')), (u, lang) => ({
        title: R.L(lang, APP_TEXT.seen[0], APP_TEXT.seen[1]),
        body: [s.get('title'), s.get('orgName')].filter(Boolean).join(' · '),
        data: { type: 'job', jobId: id },
      }));
    }
    const items = apps.map((d, i) => {
      const p = profiles[i] && profiles[i].exists ? profiles[i].data() : null;
      const vis = (p && p.contactVisibility) || 'employers';
      const show = vis !== 'none' && d.get('status') !== 'withdrawn';
      return {
        uid: d.get('uid'),
        status: d.get('status') === 'applied' ? 'seen' : d.get('status'),
        note: d.get('note') || '',
        coverNote: d.get('coverNote') || '',
        answers: d.get('answers') || {},
        appliedAt: d.get('createdAt') || 0,
        ...appExtras(d),
        cv: cvView(d.get('uid'), p, users[i], { contact: show, email: show ? emails[i] : '' }),
      };
    }).sort((a, b) => a.appliedAt - b.appliedAt);
    return { job: jobView(s), items };
  },

  /**
   * নিয়োগকর্তা: শর্টলিস্ট / ইন্টারভিউর আমন্ত্রণ / প্রস্তাব / বাছাই হয়নি — প্রার্থী নোটিফিকেশন পান
   * interview: { at, mode: inperson|online, place, link }
   * offer: { designation, salary, joiningDate, expiresAt }
   */
  async application_update(req, user) {
    const b = req.body || {};
    const { jobId, uid, status } = b;
    if (!R.isValidId(uid) || !APP_STATUSES.includes(status)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const job = await ownJob(req, user, jobId);
    if (['suspended', 'rejected'].includes(job.get('status'))) throw err(req, 'This job was taken down', 'এই বিজ্ঞাপন বন্ধ করা হয়েছে', 403);
    const emp = await store.employer(user.uid).get();
    if (emp.exists && emp.get('postingBlocked') === true) throw err(req, 'The Dorkar team has stopped hiring on your account', 'Dorkar টিম আপনার অ্যাকাউন্টে নিয়োগ বন্ধ রেখেছে', 403);
    const ref = store.application(jobId, uid);
    const a = await ref.get();
    if (!a.exists || a.get('status') === 'withdrawn') throw err(req, 'This application was withdrawn', 'আবেদনটা তুলে নেওয়া হয়েছে');
    if (a.get('status') === 'hired') throw err(req, 'This person is already hired', 'এই প্রার্থী নিয়োগ পেয়ে গেছেন');
    const note = str(b.note, 300);
    const now = Date.now();
    const today = R.todayDhakaUtcMidnight(now);
    const upd = { status, note, updatedAt: now };
    if (status === 'interview') {
      const iv = b.interview || {};
      const at = Number.isInteger(iv.at) ? iv.at : 0;
      if (at < now + 30 * 60000 || at > now + 90 * R.DAY_MS) throw err(req, 'Choose an interview date and time in the future', 'সামনের কোনো তারিখ আর সময় দিন');
      const mode = iv.mode === 'online' ? 'online' : 'inperson';
      const place = str(iv.place, 200);
      const link = str(iv.link, 300);
      if (mode === 'inperson' && !place) throw err(req, 'Write where the interview is', 'ইন্টারভিউর জায়গা লিখুন');
      if (mode === 'online' && !/^https?:\/\/\S+$/i.test(link)) throw err(req, 'Give the meeting link (starting with https://)', 'মিটিংয়ের লিংক দিন (https:// দিয়ে শুরু)');
      upd.interview = { at, mode, place: mode === 'inperson' ? place : '', link: mode === 'online' ? link : '', response: 'pending', sentAt: now };
    } else if (status === 'offered') {
      const o = b.offer || {};
      const designation = C.cleanText(o.designation, 80) || String(job.get('title') || '').slice(0, 80);
      const salary = Number.isInteger(o.salary) && o.salary >= 0 && o.salary <= 5000000 ? o.salary : -1;
      if (salary < 0) throw err(req, 'Write the monthly salary', 'মাসিক বেতন লিখুন');
      const joiningDate = Number.isInteger(o.joiningDate) ? o.joiningDate : 0;
      if (joiningDate < today || joiningDate > today + 180 * R.DAY_MS) throw err(req, 'Choose the joining date', 'যোগদানের তারিখ দিন');
      const expiresAt = Number.isInteger(o.expiresAt) ? o.expiresAt : 0;
      if (expiresAt < today || expiresAt > today + 30 * R.DAY_MS) throw err(req, 'The offer can stay open up to 30 days', 'প্রস্তাবের মেয়াদ আজ থেকে ৩০ দিনের মধ্যে দিন');
      upd.offer = { designation, salary, joiningDate, expiresAt, response: 'pending', sentAt: now };
    } else if (status === 'rejected') {
      if (!REJECT_REASONS.includes(b.reason)) throw err(req, 'Choose a reason', 'একটা কারণ বেছে নিন');
      upd.reason = b.reason;
    }
    await ref.update(upd);
    const [en, bn] = APP_TEXT[status];
    await pushTo(uid, (u, lang) => ({
      title: R.L(lang, en, bn),
      body: [job.get('title'), job.get('orgName'),
        status === 'rejected' ? R.L(lang, REJECT_TEXT[b.reason][0], REJECT_TEXT[b.reason][1]) : '', note].filter(Boolean).join(' · '),
      data: { type: 'job', jobId },
    }));
    return { ok: true };
  },

  // ================= চাকরি: প্রার্থী =================
  /** জেলার চালু চাকরি, ফিল্টারসহ; নতুন আগে */
  async jobs(req, user) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const [snap, mine, saves] = await Promise.all([store.openJobsIn(b.district), store.applicationsOf(user.uid), store.savesOf(user.uid)]);
    const applied = new Map(mine.docs.map((d) => [d.get('jobId'), d.get('status')]));
    const saved = new Set(saves.docs.map((d) => d.get('jobId')));
    const today = R.todayDhakaUtcMidnight();
    const q = str(b.q, 60).toLowerCase();
    let items = snap.docs
      .filter((d) => (d.get('deadline') || 0) >= today)
      .map((d) => jobView(d, { myStatus: applied.get(d.id) || '', saved: saved.has(d.id) }));
    if (Number.isInteger(b.upazilaId)) items = items.filter((j) => j.upazilaId === b.upazilaId || !j.upazilaId);
    if (CATEGORIES.includes(b.category)) items = items.filter((j) => j.category === b.category);
    if (TYPES.includes(b.type)) items = items.filter((j) => j.type === b.type);
    if (Number.isInteger(b.minSalary) && b.minSalary > 0) items = items.filter((j) => j.salaryNegotiable || j.salaryMax >= b.minSalary);
    if (q) items = items.filter((j) => `${j.title} ${j.orgName} ${(j.skills || []).join(' ')}`.toLowerCase().includes(q));
    items.sort((a, c) => c.createdAt - a.createdAt);
    return { items: items.slice(0, 150) };
  },

  async job(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const s = await store.job(id).get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const mineJob = s.get('employerUid') === user.uid;
    const app = await store.application(id, user.uid).get();
    // নিজের আবেদন থাকলে বন্ধ করা বিজ্ঞাপনও দেখা যায় (কী হলো জানতে)
    if (!['open', 'paused', 'closed'].includes(s.get('status')) && !mineJob && !app.exists) throw err(req, 'This job is not available', 'এই চাকরির বিজ্ঞাপন এখন নেই', 404);
    const [emp, sv] = await Promise.all([store.employer(s.get('employerUid')).get(), store.save(user.uid, id).get()]);
    return {
      job: jobView(s, {
        description: s.get('description') || '', mine: mineJob, saved: sv.exists,
        ...(mineJob ? { statusReason: s.get('statusReason') || '', flags: (s.get('flags') || []).map((f) => f.text) } : {}),
      }),
      employer: employerView(emp),
      myApplication: app.exists ? {
        status: app.get('status'), note: app.get('note') || '', at: app.get('createdAt') || 0, ...appExtras(app),
      } : null,
    };
  },

  async job_apply(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const [s, p, u] = await Promise.all([store.job(id).get(), store.profile(user.uid).get(), store.user(user.uid).get()]);
    if (!s.exists || s.get('status') !== 'open' || (s.get('deadline') || 0) < R.todayDhakaUtcMidnight()) {
      throw err(req, 'This job is not taking applications now', 'এই চাকরিতে এখন আবেদন নেওয়া হচ্ছে না');
    }
    if (s.get('employerUid') === user.uid) throw err(req, "You can't apply to your own job", 'নিজের বিজ্ঞাপনে আবেদন করা যায় না');
    if (!cvComplete(p.exists ? p.data() : null)) {
      throw err(req, 'Complete your CV first: a headline, and your experience or education', 'আগে সিভি পূরণ করুন: শিরোনাম, আর অভিজ্ঞতা বা শিক্ষা', 400, 'cv_incomplete');
    }
    if (!(u.exists && u.get('phone'))) throw err(req, 'Add your mobile number in your profile first', 'আগে প্রোফাইলে মোবাইল নম্বর দিন');
    const ref = store.application(id, user.uid);
    const before = await ref.get();
    if (before.exists && before.get('status') !== 'withdrawn') return { ok: true, already: true };
    const mode = s.get('coverLetter') || 'optional';
    const coverNote = mode === 'off' ? '' : str(req.body?.coverNote, 1500);
    if (mode === 'required' && coverNote.length < 20) throw err(req, 'Write a cover letter (at least 20 characters)', 'কভার লেটার লিখুন (অন্তত ২০ অক্ষর)', 400, 'cover');
    const answers = parseAnswers(req, s.data(), req.body?.answers);
    await useQuota(req, user.uid, 'apply');
    const fresh = await db.runTransaction(async (t) => {
      const a = await t.get(ref);
      if (a.exists && a.get('status') !== 'withdrawn') return false;
      t.set(ref, {
        jobId: id, uid: user.uid, employerUid: s.get('employerUid'), status: 'applied', coverNote, answers,
        createdAt: Date.now(), updatedAt: Date.now(),
      });
      if (!a.exists) t.update(store.job(id), { applicants: FieldValue.increment(1) });
      return true;
    });
    if (!fresh) return { ok: true, already: true };
    await pushTo(s.get('employerUid'), (x, lang) => ({
      title: R.L(lang, 'New application', 'নতুন আবেদন এসেছে'),
      body: [s.get('title'), (u.get('name') || '')].filter(Boolean).join(' · '),
      data: { type: 'job_applicants', jobId: id },
    }));
    return { ok: true };
  },

  /** আবেদন তুলে নেওয়া (কারণ ঐচ্ছিক) */
  async application_withdraw(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.application(id, user.uid);
    const a = await ref.get();
    if (!a.exists || a.get('status') === 'withdrawn') return { ok: true };
    if (['hired', 'rejected'].includes(a.get('status'))) throw err(req, 'A decision has already been made', 'সিদ্ধান্ত হয়ে গেছে, এখন তুলে নেওয়া যায় না');
    await ref.update({
      status: 'withdrawn', updatedAt: Date.now(),
      withdrawReason: WITHDRAW_REASONS.includes(req.body?.reason) ? req.body.reason : '',
      withdrawNote: str(req.body?.note, 300),
    });
    return { ok: true };
  },

  /** ইন্টারভিউর আমন্ত্রণে সাড়া: আসব / আসতে পারব না / অন্য সময় চাই */
  async interview_respond(req, user) {
    const { jobId } = req.body || {};
    const response = ['accepted', 'declined', 'reschedule'].includes(req.body?.response) ? req.body.response : null;
    if (!R.isValidId(jobId) || !response) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.application(jobId, user.uid);
    const a = await ref.get();
    const iv = a.exists ? a.get('interview') : null;
    if (!a.exists || a.get('status') !== 'interview' || !iv) throw err(req, 'No interview invitation', 'ইন্টারভিউর কোনো আমন্ত্রণ নেই');
    const note = str(req.body?.note, 300);
    if (response === 'reschedule' && !note) throw err(req, 'Write which time suits you', 'কোন সময় সুবিধা লিখুন');
    await ref.update({ 'interview.response': response, 'interview.responseNote': note, 'interview.respondedAt': Date.now(), updatedAt: Date.now() });
    const [job, u] = await Promise.all([store.job(jobId).get(), store.user(user.uid).get()]);
    const T = { accepted: ['Will attend the interview', 'ইন্টারভিউতে আসবেন'], declined: ["Can't attend the interview", 'ইন্টারভিউতে আসতে পারবেন না'], reschedule: ['Asked for another time', 'অন্য সময় চেয়েছেন'] }[response];
    await pushTo(a.get('employerUid'), (x, lang) => ({
      title: R.L(lang, T[0], T[1]),
      body: [u.get('name') || '', job.exists ? job.get('title') : '', note].filter(Boolean).join(' · '),
      data: { type: 'job_applicants', jobId },
    }));
    return { ok: true };
  },

  /** চাকরির প্রস্তাবে সাড়া; নিলে "নিয়োগ" আর সিভিতে কর্মস্থল */
  async offer_respond(req, user) {
    const { jobId } = req.body || {};
    if (!R.isValidId(jobId)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const accept = req.body?.accept === true;
    const ref = store.application(jobId, user.uid);
    const a = await ref.get();
    const o = a.exists ? a.get('offer') : null;
    if (!a.exists || a.get('status') !== 'offered' || !o || o.response !== 'pending') throw err(req, 'No open offer', 'খোলা কোনো প্রস্তাব নেই');
    // মেয়াদের দিনের শেষ পর্যন্ত (বাংলাদেশ সময়)
    if (Date.now() >= o.expiresAt + R.DAY_MS - 6 * 3600000) throw err(req, 'This offer has expired', 'প্রস্তাবের মেয়াদ শেষ হয়ে গেছে');
    const now = Date.now();
    const job = await store.job(jobId).get();
    await ref.update({
      ...(accept ? { status: 'hired', hiredAt: now } : {}),
      'offer.response': accept ? 'accepted' : 'declined', 'offer.respondedAt': now, 'offer.responseNote': str(req.body?.note, 300), updatedAt: now,
    });
    if (accept) {
      // সিভিতে নতুন কর্মস্থল (সবার উপরে)
      const pref = store.profile(user.uid);
      await db.runTransaction(async (t) => {
        const p = await t.get(pref);
        if (!p.exists) return;
        const org = job.exists ? job.get('orgName') : '';
        const list = (p.get('experience') || []).filter((e) => !(e.title === o.designation && e.org === org && e.current));
        t.update(pref, {
          experience: [{ title: o.designation, org, from: ymOf(o.joiningDate), to: '', current: true, details: '', verified: true, jobId }, ...list].slice(0, 15),
          updatedAt: now,
        });
      });
    }
    const u = await store.user(user.uid).get();
    await pushTo(a.get('employerUid'), (x, lang) => ({
      title: accept ? R.L(lang, 'Offer accepted', 'প্রস্তাব গ্রহণ করেছেন') : R.L(lang, 'Offer declined', 'প্রস্তাব ফিরিয়ে দিয়েছেন'),
      body: [u.get('name') || '', o.designation].filter(Boolean).join(' · '),
      data: { type: 'job_applicants', jobId },
    }));
    return { ok: true, hired: accept };
  },

  async my_applications(req, user) {
    const apps = (await store.applicationsOf(user.uid)).docs;
    const jobs = await store.getAll(apps.map((a) => store.job(a.get('jobId'))));
    const items = apps.map((a, i) => ({
      jobId: a.get('jobId'),
      status: a.get('status'),
      note: a.get('note') || '',
      appliedAt: a.get('createdAt') || 0,
      updatedAt: a.get('updatedAt') || 0,
      ...appExtras(a),
      job: jobs[i] && jobs[i].exists ? jobView(jobs[i]) : null,
    })).sort((a, b) => (b.updatedAt || b.appliedAt) - (a.updatedAt || a.appliedAt));
    return { items };
  },

  /** সামনের ইন্টারভিউ — ফোন নিজে ২৪ ঘণ্টা আর ১ ঘণ্টা আগে মনে করায় (প্রার্থী আর নিয়োগকর্তা দুজনেরই) */
  async interviews_upcoming(req, user) {
    const now = Date.now();
    const [mine, asEmployer] = await Promise.all([store.applicationsOf(user.uid), store.interviewsOfEmployer(user.uid)]);
    const pick = (d) => d.get('status') === 'interview' && d.get('interview') && d.get('interview').at > now && d.get('interview').response !== 'declined';
    // প্রার্থী: "আসব" বললে; নিয়োগকর্তা: যাঁরা "আসতে পারব না" বলেননি
    const cand = mine.docs.filter((d) => pick(d) && d.get('interview').response === 'accepted');
    const emp = asEmployer.docs.filter(pick);
    const jobIds = [...new Set([...cand, ...emp].map((d) => d.get('jobId')))];
    const [jobs, users] = await Promise.all([
      store.getAll(jobIds.map((id) => store.job(id))),
      store.getAll(emp.map((d) => store.user(d.get('uid')))),
    ]);
    const jt = new Map(jobs.filter((j) => j.exists).map((j) => [j.id, j]));
    const item = (d, role, who) => {
      const iv = d.get('interview');
      const j = jt.get(d.get('jobId'));
      return { id: `${d.get('jobId')}_${d.get('uid')}_${role}`, jobId: d.get('jobId'), role, at: iv.at, mode: iv.mode, place: iv.place || '', link: iv.link || '', title: j ? j.get('title') : '', who };
    };
    return {
      items: [
        ...cand.map((d) => item(d, 'candidate', jt.get(d.get('jobId'))?.get('orgName') || '')),
        ...emp.map((d, i) => item(d, 'employer', (users[i] && users[i].exists && users[i].get('name')) || '')),
      ].sort((a, b) => a.at - b.at).slice(0, 50),
    };
  },

  // ---------- সেভ করা চাকরি ----------
  async job_bookmark(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.save(user.uid, id);
    if (req.body?.saved === false) {
      await ref.delete();
      return { ok: true, saved: false };
    }
    const s = await store.job(id).get();
    if (!s.exists || !['open', 'paused'].includes(s.get('status'))) throw err(req, 'This job is not available', 'এই চাকরির বিজ্ঞাপন এখন নেই');
    const mine = await store.savesOf(user.uid);
    if (mine.size >= 100) throw err(req, 'Up to 100 saved jobs', 'সর্বোচ্চ ১০০টা চাকরি সেভ করা যায়');
    await ref.set({ uid: user.uid, jobId: id, deadline: s.get('deadline') || 0, createdAt: Date.now() });
    return { ok: true, saved: true };
  },

  async saved_jobs(req, user) {
    const [saves, mine] = await Promise.all([store.savesOf(user.uid), store.applicationsOf(user.uid)]);
    const applied = new Map(mine.docs.map((d) => [d.get('jobId'), d.get('status')]));
    const jobs = await store.getAll(saves.docs.map((d) => store.job(d.get('jobId'))));
    const items = jobs.filter((j) => j.exists).map((j) => jobView(j, { myStatus: applied.get(j.id) || '', saved: true }))
      .sort((a, b) => a.deadline - b.deadline);
    // মুছে যাওয়া বিজ্ঞাপনের সেভ সরানো
    const gone = saves.docs.filter((d, i) => !jobs[i].exists);
    if (gone.length) await Promise.all(gone.map((d) => d.ref.delete().catch(() => {})));
    return { items };
  },

  // ---------- চাকরির অ্যালার্ট ----------
  async job_alert_get(req, user) {
    const s = await store.alert(user.uid).get();
    return {
      alert: s.exists && s.get('district') ? {
        district: s.get('district'), upazilaId: s.get('upazilaId') || null, categories: s.get('categories') || [],
        type: s.get('type') || '', minSalary: s.get('minSalary') || 0, on: s.get('on') === true,
      } : null,
    };
  },

  async job_alert_save(req, user) {
    const b = req.body || {};
    if (b.on === false) {
      await store.alert(user.uid).set({ uid: user.uid, on: false, updatedAt: Date.now() }, { merge: true });
      return { ok: true };
    }
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    await store.alert(user.uid).set({
      uid: user.uid,
      district: b.district,
      upazilaId: Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null,
      categories: K.pickCodes(b.categories, CATEGORIES, 5),
      type: TYPES.includes(b.type) ? b.type : '',
      minSalary: Number.isInteger(b.minSalary) && b.minSalary > 0 && b.minSalary <= 5000000 ? b.minSalary : 0,
      on: true,
      updatedAt: Date.now(),
    });
    return { ok: true };
  },

  // ================= অ্যাডমিন =================
  async admin_jobs(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'changes', 'open', 'paused', 'closed', 'rejected', 'suspended'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await store.jobsByStatus(status);
    const V = require('./verify');
    const emps = await store.getAll([...new Set(snap.docs.map((d) => d.get('employerUid')))].map((u) => store.employer(u)));
    const em = new Map(emps.filter((e) => e.exists).map((e) => [e.id, e]));
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...jobView(d),
        description: d.get('description') || '',
        employerUid: d.get('employerUid'),
        ownerUid: d.get('employerUid'),
        statusReason: d.get('statusReason') || '',
        flags: d.get('flags') || [],
        verification: d.get('verification') || null,
        employer: employerHistory(em.get(d.get('employerUid'))),
        ...(['pending', 'rejected', 'changes'].includes(status) ? { verify: await V.panelInfo('job', d.id, d.data()).catch(() => null) } : {}),
      }))),
    };
  },

  /** অনুমোদন (চালু) — প্যানেলে সব বিষয় টিক দিয়ে; বাতিল, "ঠিক করতে বলুন" বা বন্ধ কারণসহ */
  async admin_job_resolve(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const want = req.body?.status;
    if (!['approved', 'rejected', 'changes', 'suspended'].includes(want)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.job(id);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const reason = str(req.body?.reason, 300);
    if (want !== 'approved' && reason.length < 3) throw err(req, 'Write the reason', 'কারণ লিখুন');
    // অনুমোদনের সময় শেষ তারিখ পার হয়ে থাকলে বিরতিতে (নিয়োগকর্তা তারিখ বাড়িয়ে চালু করবেন)
    const expired = (s.get('deadline') || 0) < R.todayDhakaUtcMidnight();
    const status = want === 'approved' ? (expired ? 'paused' : 'open') : want;
    if (s.get('status') === status) return { ok: true, unchanged: true };
    const verification = want === 'approved' && ['pending', 'rejected', 'changes'].includes(s.get('status'))
      ? require('./verify').assertChecks(req, user, 'job', s.data(), req.body?.checks) : null;
    await ref.update({
      status, reviewedAt: Date.now(), reviewedBy: user.uid,
      ...(verification ? { verification } : {}),
      ...(want === 'approved' ? { statusReason: FieldValue.delete(), flags: [], ...(expired ? { pausedBy: 'deadline' } : {}) } : { statusReason: reason }),
    });
    if (want === 'rejected') await store.employer(s.get('employerUid')).update({ rejectedCount: FieldValue.increment(1) }).catch(() => {});
    if (want === 'suspended') await notifyClosed(id, s.data());
    if (status === 'open') await sendAlerts(id, s.data());
    const title = {
      approved: ['Your job is now live', 'আপনার চাকরির বিজ্ঞাপন চালু হয়েছে'],
      rejected: ['Job not approved', 'চাকরির বিজ্ঞাপন অনুমোদন হয়নি'],
      changes: ['Please fix your job post', 'চাকরির বিজ্ঞাপনটা ঠিক করে আবার দিন'],
      suspended: ['Your job was taken down', 'আপনার চাকরির বিজ্ঞাপন বন্ধ করা হয়েছে'],
    }[want];
    await pushTo(s.get('employerUid'), (u, lang) => ({
      title: R.L(lang, title[0], title[1]),
      body: [s.get('title'), reason ? (want === 'changes' ? reason : R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`)) : ''].filter(Boolean).join(' · '),
      data: { type: 'job_mine', jobId: id },
    }));
    return { ok: true };
  },

  async admin_employers(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'verified', 'rejected', 'none'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await store.employersByStatus(status);
    const V = require('./verify');
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...employerView(d, true),
        history: employerHistory(d),
        id: d.id,
        ownerUid: d.id,
        createdAt: d.get('createdAt') || 0,
        verification: d.get('verification') || null,
        ...(['pending', 'rejected', 'none'].includes(status) ? { verify: await V.panelInfo('employer', d.id, d.data()).catch(() => null) } : {}),
      }))),
    };
  },

  async admin_employer_resolve(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.employer(id);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const approve = req.body?.status === 'approved';
    const reason = str(req.body?.reason, 200);
    const verification = approve ? require('./verify').assertChecks(req, user, 'employer', s.data(), req.body?.checks) : null;
    await ref.update({
      verified: approve, verifyStatus: approve ? 'verified' : 'rejected', reviewedAt: Date.now(), reviewedBy: user.uid,
      ...(approve ? { verification, statusReason: FieldValue.delete(), docsRequestedAt: FieldValue.delete() } : { statusReason: reason }),
    });
    const jobs = await store.jobsOf(id);
    if (jobs.size) {
      const batch = db.batch();
      jobs.docs.forEach((d) => batch.update(d.ref, { employerVerified: approve }));
      await batch.commit();
    }
    await pushTo(id, (u, lang) => ({
      title: approve ? R.L(lang, 'Your organisation is verified', 'আপনার প্রতিষ্ঠান যাচাই হয়েছে') : R.L(lang, 'Organisation not verified', 'প্রতিষ্ঠান যাচাই হয়নি'),
      body: [s.get('name'), !approve && reason ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : ''].filter(Boolean).join(' · '),
      data: { type: 'job_mine' },
    }));
    return { ok: true };
  },

  /** নিয়োগকর্তাকে সতর্ক করা (কারণসহ নোটিফিকেশন) */
  async admin_employer_warn(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const reason = str(req.body?.reason, 300);
    if (reason.length < 3) throw err(req, 'Write the reason', 'কারণ লিখুন');
    const ref = store.employer(id);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    await ref.update({ warnCount: FieldValue.increment(1), lastWarnAt: Date.now(), lastWarnReason: reason });
    await pushTo(id, (u, lang) => ({
      title: R.L(lang, 'Warning from the Dorkar team', 'Dorkar টিমের সতর্কবার্তা'),
      body: reason,
      data: { type: 'job_mine' },
    }));
    return { ok: true };
  },

  /** বিজ্ঞাপন দেওয়া বন্ধ / আবার চালু। বন্ধ করলে চালু, বিরতির আর অপেক্ষার সব বিজ্ঞাপনও বন্ধ */
  async admin_employer_block(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const block = req.body?.block === true;
    const reason = str(req.body?.reason, 300);
    if (block && reason.length < 3) throw err(req, 'Write the reason', 'কারণ লিখুন');
    const ref = store.employer(id);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    await ref.update(block ? { postingBlocked: true, blockReason: reason, blockedAt: Date.now() } : { postingBlocked: false, blockReason: FieldValue.delete() });
    let down = 0;
    if (block) {
      const jobs = (await store.jobsOf(id)).docs.filter((d) => LIVE.includes(d.get('status')));
      for (const d of jobs) {
        await d.ref.update({ status: 'suspended', statusReason: reason, suspendedAt: Date.now() });
        await notifyClosed(d.id, d.data());
      }
      down = jobs.length;
    }
    await pushTo(id, (u, lang) => ({
      title: block ? R.L(lang, 'Job posting stopped', 'চাকরির বিজ্ঞাপন দেওয়া বন্ধ করা হয়েছে') : R.L(lang, 'You can post jobs again', 'আবার চাকরির বিজ্ঞাপন দিতে পারবেন'),
      body: block ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : s.get('name') || '',
      data: { type: 'job_mine' },
    }));
    return { ok: true, jobsDown: down };
  },

  /** নিষিদ্ধ/সন্দেহজনক শব্দ — এগুলো থাকলে বিজ্ঞাপন অ্যাডমিনের সারিতে যায় */
  async admin_job_words(req, user) {
    await requireAdmin(req, user);
    const s = await store.rules().get();
    return { words: s.exists ? s.get('words') || [] : [], defaults: DEFAULT_WORDS };
  },

  async admin_job_words_save(req, user) {
    await requireAdmin(req, user);
    const words = [...new Set(cleanList(req.body?.words, 200, (w) => str(w, 40)))];
    await store.rules().set({ words, updatedAt: Date.now(), updatedBy: user.uid });
    wordsCache = { at: 0, words: [] };
    return { ok: true, words };
  },
};

/** প্যানেলে নিয়োগকর্তার ইতিহাস */
function employerHistory(e) {
  if (!e || !e.exists) return null;
  return {
    name: e.get('name') || '',
    verified: e.get('verified') === true,
    rejectedCount: e.get('rejectedCount') || 0,
    reportCount: e.get('reportCount') || 0,
    warnCount: e.get('warnCount') || 0,
    lastWarnReason: e.get('lastWarnReason') || '',
    postingBlocked: e.get('postingBlocked') === true,
    blockReason: e.get('blockReason') || '',
  };
}

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/** বিজ্ঞাপন বন্ধ হলে যাঁদের সিদ্ধান্ত হয়নি তাঁদের জানানো */
async function notifyClosed(jobId, job) {
  try {
    const apps = (await store.applicationsFor(jobId)).docs.filter((d) => ['applied', 'seen', 'shortlisted', 'interview', 'offered'].includes(d.get('status')));
    if (!apps.length) return;
    await require('./requestend').pushUsers(apps.map((d) => d.get('uid')), (u, lang) => ({
      title: R.L(lang, APP_TEXT.closed[0], APP_TEXT.closed[1]),
      body: [job.title, job.orgName].filter(Boolean).join(' · '),
      data: { type: 'job', jobId },
    }));
  } catch (e) {
    console.error('job close notify failed', e.message);
  }
}

/** রিপোর্ট টিকলে: বিজ্ঞাপন বন্ধ, নিয়োগকর্তাকে কারণসহ, আবেদনকারীদের "নিয়োগ বন্ধ" */
async function suspendByReport(id, reason) {
  const ref = store.job(id);
  const s = await ref.get();
  if (!s.exists || s.get('status') === 'suspended') return;
  await ref.update({ status: 'suspended', statusReason: reason || '', suspendedAt: Date.now() });
  await notifyClosed(id, s.data());
  await pushTo(s.get('employerUid'), (u, lang) => ({
    title: R.L(lang, 'Your job was taken down', 'আপনার চাকরির বিজ্ঞাপন বন্ধ করা হয়েছে'),
    body: [s.get('title'), reason ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : ''].filter(Boolean).join(' · '),
    data: { type: 'job_mine', jobId: id },
  }));
}

/** হোমের "চলমান": প্রার্থীর চলমান আবেদন, নিয়োগকর্তার নতুন আবেদন */
async function ongoing(req, uid, now = Date.now()) {
  const [mine, incoming] = await Promise.all([
    store.applicationsOf(uid),
    db.collection('applications').where('employerUid', '==', uid).where('status', '==', 'applied').limit(300).get(),
  ]);
  const active = mine.docs.filter((d) => ['applied', 'seen', 'shortlisted', 'interview', 'offered'].includes(d.get('status')));
  const jobIds = [...new Set([...active.map((d) => d.get('jobId')), ...incoming.docs.map((d) => d.get('jobId'))])];
  const jobs = await store.getAll(jobIds.map((id) => store.job(id)));
  const jm = new Map(jobs.filter((j) => j.exists).map((j) => [j.id, j]));
  const items = [];
  for (const d of active) {
    const j = jm.get(d.get('jobId'));
    if (!j || ['suspended', 'rejected'].includes(j.get('status'))) continue;
    const st = d.get('status');
    // বিজ্ঞাপন বন্ধ/থামানো হলে শুধু ইন্টারভিউ আর প্রস্তাব চলমান থাকে
    if (j.get('status') !== 'open' && !['interview', 'offered'].includes(st)) continue;
    const iv = d.get('interview');
    const o = d.get('offer');
    // প্রস্তাব ফিরিয়ে দেওয়া/মেয়াদ শেষ হলে আর চলমান নয়
    if (st === 'offered' && o && o.response !== 'pending') continue;
    if (st === 'interview' && iv && iv.at < now - 6 * 3600000) continue;
    const line = {
      applied: C.tr(req, 'Applied', 'আবেদন করা হয়েছে'),
      seen: C.tr(req, 'Seen by the employer', 'নিয়োগকর্তা দেখেছেন'),
      shortlisted: C.tr(req, 'Shortlisted', 'শর্টলিস্টে আছেন'),
      interview: iv && iv.response === 'pending' ? C.tr(req, 'Interview invitation — reply', 'ইন্টারভিউর আমন্ত্রণ — উত্তর দিন') : C.tr(req, 'Interview', 'ইন্টারভিউ'),
      offered: C.tr(req, 'Job offer — reply', 'চাকরির প্রস্তাব — উত্তর দিন'),
    }[st];
    items.push({
      key: `job_${d.id}`, service: 'job', title: `${j.get('title')} · ${j.get('orgName') || ''}`, line, note: '',
      ...(st === 'interview' && iv ? { date: iv.at, dateTime: true } : {}),
      ...(st === 'offered' && o ? { date: o.expiresAt } : {}),
      action: (st === 'interview' && iv && iv.response === 'pending') || st === 'offered',
      at: d.get('updatedAt') || d.get('createdAt') || 0,
      open: { type: 'job', jobId: d.get('jobId') },
    });
  }
  const byJob = new Map();
  incoming.docs.forEach((d) => byJob.set(d.get('jobId'), [...(byJob.get(d.get('jobId')) || []), d]));
  for (const [jobId, list] of byJob) {
    const j = jm.get(jobId);
    if (!j || j.get('status') !== 'open') continue;
    items.push({
      key: `jobnew_${jobId}`, service: 'job', title: j.get('title'),
      line: C.tr(req, `${list.length} new application(s)`, `${list.length}টা নতুন আবেদন`), note: '', action: true,
      at: Math.max(...list.map((d) => d.get('createdAt') || 0)), open: { type: 'job_applicants', jobId },
    });
  }
  return items;
}

/** কেউ বিজ্ঞাপন রিপোর্ট করলে নিয়োগকর্তার হিসাবে (প্যানেলে ইতিহাস) */
async function countReport(jobDoc) {
  const uid = jobDoc && jobDoc.get('employerUid');
  if (uid) await store.employer(uid).update({ reportCount: FieldValue.increment(1) }).catch(() => {});
}

/**
 * দৈনিক:
 * - শেষ তারিখ পার হওয়া চালু বিজ্ঞাপন বিরতিতে, নিয়োগকর্তা জানেন
 * - প্রস্তাবের মেয়াদ শেষের দিনে প্রার্থীকে মনে করানো; পার হলে "মেয়াদ শেষ"
 * - সেভ করা চাকরির শেষ তারিখের ২ দিন আগে মনে করানো
 */
async function daily(now = Date.now()) {
  const today = R.todayDhakaUtcMidnight(now);
  const out = { paused: 0, offerReminders: 0, offersExpired: 0, savedReminders: 0 };

  const old = (await store.allOpen()).filter((d) => (d.get('deadline') || 0) < today);
  for (let i = 0; i < old.length; i += 400) {
    const batch = db.batch();
    old.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'paused', pausedBy: 'deadline', pausedAt: now }));
    await batch.commit();
  }
  out.paused = old.length;
  for (const d of old) {
    await pushTo(d.get('employerUid'), (u, lang) => ({
      title: R.L(lang, 'Application deadline is over', 'আবেদনের শেষ তারিখ পার হয়েছে'),
      body: R.L(lang, `${d.get('title')}: no new applications now. Extend the deadline to take more.`, `${d.get('title')}: এখন নতুন আবেদন নেওয়া হচ্ছে না। আরও নিতে চাইলে শেষ তারিখ বাড়ান।`),
      data: { type: 'job_mine', jobId: d.id },
    }));
  }

  const offered = (await store.applicationsByStatus('offered')).docs.filter((d) => (d.get('offer') || {}).response === 'pending');
  for (const d of offered) {
    const o = d.get('offer');
    if (o.expiresAt < today) {
      await d.ref.update({ 'offer.response': 'expired', updatedAt: now });
      out.offersExpired++;
    } else if (o.expiresAt === today && !o.reminded) {
      await d.ref.update({ 'offer.reminded': true });
      await pushTo(d.get('uid'), (u, lang) => ({
        title: R.L(lang, 'Your job offer expires today', 'আজ চাকরির প্রস্তাবের মেয়াদ শেষ'),
        body: R.L(lang, `${o.designation}: accept or decline in the app.`, `${o.designation}: অ্যাপে গিয়ে গ্রহণ বা ফিরিয়ে দিন।`),
        data: { type: 'job', jobId: d.get('jobId') },
      }));
      out.offerReminders++;
    }
  }

  const due = (await store.savesDue(today + 2 * R.DAY_MS)).docs;
  if (due.length) {
    const jobs = await store.getAll([...new Set(due.map((d) => d.get('jobId')))].map((id) => store.job(id)));
    const jm = new Map(jobs.filter((j) => j.exists && j.get('status') === 'open').map((j) => [j.id, j]));
    for (const d of due) {
      const j = jm.get(d.get('jobId'));
      if (!j) continue;
      const a = await store.application(j.id, d.get('uid')).get();
      if (a.exists && a.get('status') !== 'withdrawn') continue;
      await pushTo(d.get('uid'), (u, lang) => ({
        title: R.L(lang, 'Saved job closes in 2 days', 'সেভ করা চাকরির আবেদন ২ দিন পর শেষ'),
        body: [j.get('title'), j.get('orgName')].filter(Boolean).join(' · '),
        data: { type: 'job_alert', jobId: j.id },
      }));
      out.savedReminders++;
    }
  }
  return out;
}

/** অ্যাকাউন্ট মোছার সময়: সিভি, নিয়োগকর্তার তথ্য, আবেদন, সেভ, অ্যালার্ট; চালু বিজ্ঞাপন বন্ধ */
async function deleteForUser(uid) {
  // আগে ব্যক্তিগত তথ্য (সিভি, নিয়োগকর্তা, অ্যালার্ট) — বাকি অংশ আটকালেও এগুলো মুছে যায়
  const first = db.batch();
  first.delete(store.employer(uid));
  first.delete(store.alert(uid));
  await first.commit();
  const [jobs, apps, saves] = await Promise.all([store.jobsOf(uid), store.applicationsOf(uid), store.savesOf(uid)]);
  const ops = [
    ...jobs.docs.filter((d) => LIVE.includes(d.get('status'))).map((d) => (b) => b.update(d.ref, { status: 'closed', closedAt: Date.now(), closedBy: 'deleted' })),
    ...apps.docs.map((d) => (b) => b.delete(d.ref)),
    ...saves.docs.map((d) => (b) => b.delete(d.ref)),
  ];
  for (let i = 0; i < ops.length; i += 400) {
    const b = db.batch();
    ops.slice(i, i + 400).forEach((op) => op(b));
    await b.commit();
  }
}

module.exports = {
  actions, CATEGORIES, TYPES, EDUCATION, EMPLOYER_KINDS, parseJob, flagsFor, daily, deleteForUser, ongoing, suspendByReport, countReport, DEFAULT_WORDS,
};
