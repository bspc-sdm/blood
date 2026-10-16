// চাকরি আর সিভি-প্রোফাইল।
// - সিভি (profiles/{uid}): শিরোনাম, নিজের সম্পর্কে, অভিজ্ঞতা, শিক্ষা, দক্ষতা, ভাষা, সনদ; শেয়ার লিংক (/cv/{uid}) আর অ্যাপে PDF।
// - নিয়োগকর্তা (employers/{uid}): প্রতিষ্ঠানের তথ্য; ট্রেড লাইসেন্সের ছবি দিলে অ্যাডমিন যাচাই করে "যাচাই করা নিয়োগকর্তা"।
// - চাকরি (jobs/{id}): যাচাই করা নিয়োগকর্তার বিজ্ঞাপন সাথে সাথে চালু; বাকিদেরটা অ্যাডমিন দেখে চালু করেন।
//   বেতনের সীমা দিতেই হয়; কোনো ফি চাওয়া নিষেধ। শেষ তারিখ পার হলে নিজে বন্ধ।
// - আবেদন (applications/{jobId}_{uid}): অ্যাপের ভেতরেই, প্রোফাইল দিয়ে। প্রার্থী অবস্থা দেখেন
//   (আবেদন করা → দেখা হয়েছে → শর্টলিস্ট → ইন্টারভিউ → নিয়োগ / বাছাই হয়নি)।
//
// ডেটাবেসের সব কাজ নিচের `store`-এ — পরে নিজের সার্ভারে সরালে শুধু এই অংশ বদলাতে হবে।
const { admin, db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota } = require('./community-db');

// ---------- ডেটাবেস ----------
const store = {
  profile: (uid) => db.doc(`profiles/${uid}`),
  user: (uid) => db.doc(`users/${uid}`),
  employer: (uid) => db.doc(`employers/${uid}`),
  job: (id) => db.doc(`jobs/${id}`),
  newJob: () => db.collection('jobs').doc(),
  application: (jobId, uid) => db.doc(`applications/${jobId}_${uid}`),
  openJobsIn: (district) => db.collection('jobs').where('status', '==', 'open').where('district', '==', district).limit(400).get(),
  jobsOf: (employerUid) => db.collection('jobs').where('employerUid', '==', employerUid).limit(100).get(),
  jobsByStatus: (status) => db.collection('jobs').where('status', '==', status).limit(300).get(),
  applicationsOf: (uid) => db.collection('applications').where('uid', '==', uid).limit(200).get(),
  applicationsFor: (jobId) => db.collection('applications').where('jobId', '==', jobId).limit(300).get(),
  employersByStatus: (status) => db.collection('employers').where('verifyStatus', '==', status).limit(300).get(),
  getAll: (refs) => (refs.length ? db.getAll(...refs) : Promise.resolve([])),
};

// ---------- তালিকা ----------
const CATEGORIES = ['health', 'education', 'sales', 'accounts', 'office', 'it', 'driving', 'factory', 'hospitality', 'security', 'construction', 'domestic', 'other'];
const TYPES = ['fulltime', 'parttime', 'contract', 'internship'];
const EDUCATION = ['none', 'ssc', 'hsc', 'diploma', 'bachelor', 'masters'];
const EMPLOYER_KINDS = ['company', 'shop', 'hospital', 'school', 'ngo', 'household', 'other'];
const VISIBILITY = ['none', 'employers', 'all'];
// নিয়োগকর্তা প্রার্থীর আবেদনে যা দিতে পারেন
const APP_STATUSES = ['shortlisted', 'interview', 'rejected', 'hired'];
const MAX_DEADLINE_DAYS = 90;
const MAX_ACTIVE_JOBS = 30;

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const str = (v, max) => (typeof v === 'string' ? v.trim().replace(/\s+\n/g, '\n').slice(0, max) : '');
const YM = /^(19[5-9]\d|20\d\d)-(0[1-9]|1[0-2])$/;

// ---------- সিভি ----------
function cleanList(v, max, fn) {
  return (Array.isArray(v) ? v : []).slice(0, max).map(fn).filter(Boolean);
}

function parseProfile(req, b) {
  const year = new Date().getUTCFullYear();
  const exp = cleanList(b.experience, 15, (e) => {
    if (!e || typeof e !== 'object') return null;
    const title = str(e.title, 80);
    const org = str(e.org, 100);
    if (!title || !org) return null;
    const from = YM.test(e.from || '') ? e.from : '';
    const current = e.current === true;
    const to = !current && YM.test(e.to || '') ? e.to : '';
    if (from && to && to < from) throw err(req, `"${title}": end date is before start date`, `"${title}": শেষের তারিখ শুরুর আগে`);
    return { title, org, from, to, current, details: str(e.details, 500) };
  });
  const edu = cleanList(b.education, 10, (e) => {
    if (!e || typeof e !== 'object') return null;
    const degree = str(e.degree, 100);
    const institute = str(e.institute, 120);
    if (!degree || !institute) return null;
    const y = Number.isInteger(e.year) && e.year >= 1950 && e.year <= year + 6 ? e.year : null;
    return { degree, institute, year: y, result: str(e.result, 40) };
  });
  const certs = cleanList(b.certs, 20, (e) => {
    if (!e || typeof e !== 'object') return null;
    const name = str(e.name, 100);
    if (!name) return null;
    const y = Number.isInteger(e.year) && e.year >= 1950 && e.year <= year + 1 ? e.year : null;
    return { name, issuer: str(e.issuer, 100), year: y };
  });
  const words = (v, n, len) => [...new Set(cleanList(v, n, (s) => str(s, len)))];
  return {
    headline: str(b.headline, 100),
    about: str(b.about, 1000),
    experience: exp,
    education: edu,
    skills: words(b.skills, 30, 40),
    languages: words(b.languages, 10, 30),
    certs,
    contactVisibility: VISIBILITY.includes(b.contactVisibility) ? b.contactVisibility : 'employers',
    public: b.public === true,
    openToWork: b.openToWork === true,
    wantCategories: K.pickCodes(b.wantCategories, CATEGORIES, 5),
    updatedAt: Date.now(),
  };
}

/** আবেদন করার মতো সিভি: শিরোনাম, আর অভিজ্ঞতা বা শিক্ষা অন্তত একটা */
const cvComplete = (p) => !!(p && p.headline && ((p.experience || []).length || (p.education || []).length));

/** সিভি দেখানোর রূপ। contact: ফোন/ইমেইল দেখানো যাবে কি না */
function cvView(uid, p, u, { contact = false, email = '' } = {}) {
  const x = p || {};
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
    openToWork: x.openToWork === true,
    wantCategories: x.wantCategories || [],
    contactVisibility: x.contactVisibility || 'employers',
    public: x.public === true,
    complete: cvComplete(x),
    updatedAt: x.updatedAt || 0,
    ...(contact ? { phone: K.fixPhone(u && u.get('phone')), email } : {}),
  };
}

async function emailOf(uid) {
  return admin.auth().getUser(uid).then((a) => (a.email || '').toLowerCase()).catch(() => '');
}

// ---------- চাকরি ----------
function parseJob(req, b) {
  const title = C.cleanText(b.title, 100);
  if (!title) throw err(req, 'Write the job title', 'পদের নাম লিখুন');
  if (!CATEGORIES.includes(b.category)) throw err(req, 'Choose the job category', 'চাকরির ধরন বেছে নিন');
  if (!TYPES.includes(b.type)) throw err(req, 'Choose full-time, part-time etc.', 'ফুলটাইম, পার্টটাইম ইত্যাদি বেছে নিন');
  if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
  const upazilaId = Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === b.district ? b.upazilaId : null;
  const min = Number.isInteger(b.salaryMin) ? b.salaryMin : 0;
  const max = Number.isInteger(b.salaryMax) ? b.salaryMax : 0;
  // বেতনের সীমা দিতেই হবে (মাসিক টাকা)
  if (min < 500 || max < min || max > 5000000) throw err(req, 'Enter the monthly salary range (minimum and maximum)', 'মাসিক বেতনের সীমা দিন (সর্বনিম্ন আর সর্বোচ্চ)');
  const description = C.cleanText(b.description, 3000);
  if (!description || description.length < 30) throw err(req, 'Describe the work (at least 30 characters)', 'কাজের বর্ণনা লিখুন (অন্তত ৩০ অক্ষর)');
  const today = R.todayDhakaUtcMidnight();
  const deadline = Number.isInteger(b.deadline) ? b.deadline : 0;
  if (deadline < today || deadline > today + MAX_DEADLINE_DAYS * R.DAY_MS) {
    throw err(req, `Set an application deadline within ${MAX_DEADLINE_DAYS} days`, `আবেদনের শেষ তারিখ দিন (আজ থেকে ${MAX_DEADLINE_DAYS} দিনের মধ্যে)`);
  }
  // ফি চাওয়া নিষেধ — বর্ণনায় স্পষ্ট টাকার দাবি থাকলে আটকানো (অ্যাডমিনও দেখেন)
  if (/(জামানত|রেজিস্ট্রেশন\s*ফি|আবেদন\s*ফি|registration\s*fee|application\s*fee|security\s*deposit)/i.test(description)) {
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
    vacancies: Number.isInteger(b.vacancies) && b.vacancies >= 1 && b.vacancies <= 500 ? b.vacancies : 1,
    experienceYears: Number.isInteger(b.experienceYears) && b.experienceYears >= 0 && b.experienceYears <= 40 ? b.experienceYears : 0,
    education: EDUCATION.includes(b.education) ? b.education : 'none',
    skills: [...new Set(cleanList(b.skills, 15, (s) => str(s, 40)))],
    description,
    deadline,
    updatedAt: Date.now(),
  };
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
    vacancies: x.vacancies || 1,
    experienceYears: x.experienceYears || 0,
    education: x.education || 'none',
    skills: x.skills || [],
    deadline: x.deadline || 0,
    createdAt: x.createdAt || 0,
    applicants: x.applicants || 0,
    status: x.status,
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
    } : {}),
  };
}

async function pushTo(uid, make) {
  await require('./requestend').pushUsers([uid], make);
}

const APP_TEXT = {
  seen: ['Your application was seen', 'আপনার আবেদন দেখা হয়েছে'],
  shortlisted: ['You were shortlisted', 'আপনি শর্টলিস্টে আছেন'],
  interview: ['Interview call', 'ইন্টারভিউয়ের ডাক'],
  rejected: ['Not selected this time', 'এবার বাছাই হননি'],
  hired: ['Congratulations — you got the job', 'অভিনন্দন — চাকরিটা আপনার'],
  closed: ['Hiring has closed', 'নিয়োগ বন্ধ হয়েছে'],
};

const actions = {
  // ================= সিভি =================
  async cv_get(req, user) {
    const [p, u] = await Promise.all([store.profile(user.uid).get(), store.user(user.uid).get()]);
    return { cv: cvView(user.uid, p.exists ? p.data() : null, u, { contact: true, email: (user.email || '').toLowerCase() }) };
  },

  async cv_save(req, user) {
    const data = parseProfile(req, req.body || {});
    await store.profile(user.uid).set(data, { merge: false });
    const u = await store.user(user.uid).get();
    return { ok: true, cv: cvView(user.uid, data, u, { contact: true, email: (user.email || '').toLowerCase() }) };
  },

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
    const reverify = x && x.verified === true && (!K.smallNameEdit(x.name, name) && x.name !== name || x.phone !== phone);
    const hasDocs = b.docs && typeof b.docs === 'object' && Object.keys(b.docs).length > 0;
    if (!x) {
      // নতুন নিয়োগকর্তা: নথি এখনো নেই, তাই ছবি আগে রেখে হিসাবটা এখানেই বসানো
      const docsHave = hasDocs ? await V.saveDocs(req, 'employer', user.uid, user.uid, data, b.docs, true) : null;
      await ref.set({
        ...data, ownerUid: user.uid, verified: false, verifyStatus: docsHave ? 'pending' : 'none', docsHave: docsHave || {}, createdAt: Date.now(),
      });
    } else {
      const docsHave = hasDocs ? await V.saveDocs(req, 'employer', user.uid, user.uid, { ...x, ...data }, b.docs, false) : null;
      const pending = docsHave || reverify;
      await ref.update({
        ...data,
        ...(pending && x.verifyStatus !== 'pending' ? { verifyStatus: 'pending', verified: false, docsRequestedAt: FieldValue.delete() } : {}),
      });
      // নাম বদলালে চালু বিজ্ঞাপনেও নতুন নাম
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
    const data = parseJob(req, b);
    const verified = emp.get('verified') === true;
    if (b.id) {
      if (!R.isValidId(b.id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
      const ref = store.job(b.id);
      const s = await ref.get();
      if (!s.exists || s.get('employerUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
      if (['suspended', 'closed'].includes(s.get('status'))) throw err(req, 'This job is closed', 'এই বিজ্ঞাপন বন্ধ');
      // যাচাই না করা নিয়োগকর্তা বদলালে আবার অ্যাডমিন দেখেন
      const status = verified ? s.get('status') === 'rejected' ? 'pending' : s.get('status') : 'pending';
      await ref.update({ ...data, orgName: emp.get('name'), employerVerified: verified, status });
      return { ok: true, id: b.id, status };
    }
    const mine = await store.jobsOf(user.uid);
    if (mine.docs.filter((d) => ['open', 'pending'].includes(d.get('status'))).length >= MAX_ACTIVE_JOBS) {
      throw err(req, `Up to ${MAX_ACTIVE_JOBS} active jobs at a time`, `একসাথে সর্বোচ্চ ${MAX_ACTIVE_JOBS}টা চালু বিজ্ঞাপন`);
    }
    await useQuota(req, user.uid, 'job');
    const ref = store.newJob();
    const status = verified ? 'open' : 'pending';
    await ref.set({
      ...data, employerUid: user.uid, orgName: emp.get('name'), employerVerified: verified, status, applicants: 0, createdAt: Date.now(),
    });
    return { ok: true, id: ref.id, status };
  },

  /** নিয়োগ বন্ধ: যাঁদের এখনো সিদ্ধান্ত হয়নি তাঁদের জানানো */
  async job_close(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.job(id);
    const s = await ref.get();
    if (!s.exists || s.get('employerUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    if (s.get('status') === 'closed') return { ok: true };
    await ref.update({ status: 'closed', closedAt: Date.now(), closedBy: 'employer' });
    await notifyClosed(id, s.data());
    return { ok: true };
  },

  async my_jobs(req, user) {
    const [snap, emp] = await Promise.all([store.jobsOf(user.uid), store.employer(user.uid).get()]);
    const items = snap.docs.map((d) => jobView(d, { statusReason: d.get('statusReason') || '' })).sort((a, b) => b.createdAt - a.createdAt);
    return { items, employer: employerView(emp, true) };
  },

  /** আবেদনকারীরা — খুললেই "আবেদন করা"গুলো "দেখা হয়েছে" হয় */
  async job_applicants(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const s = await store.job(id).get();
    if (!s.exists || s.get('employerUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const apps = (await store.applicationsFor(id)).docs.filter((d) => d.get('status') !== 'withdrawn');
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
    }
    const items = apps.map((d, i) => {
      const p = profiles[i] && profiles[i].exists ? profiles[i].data() : null;
      const vis = (p && p.contactVisibility) || 'employers';
      return {
        uid: d.get('uid'),
        status: d.get('status') === 'applied' ? 'seen' : d.get('status'),
        note: d.get('note') || '',
        coverNote: d.get('coverNote') || '',
        appliedAt: d.get('createdAt') || 0,
        cv: cvView(d.get('uid'), p, users[i], { contact: vis !== 'none', email: vis !== 'none' ? emails[i] : '' }),
      };
    }).sort((a, b) => a.appliedAt - b.appliedAt);
    return { job: jobView(s), items };
  },

  /** নিয়োগকর্তা: শর্টলিস্ট / ইন্টারভিউ / বাছাই হয়নি / নিয়োগ — প্রার্থী নোটিফিকেশন পান */
  async application_update(req, user) {
    const { jobId, uid } = req.body || {};
    const status = req.body?.status;
    if (!R.isValidId(jobId) || !R.isValidId(uid) || !APP_STATUSES.includes(status)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const job = await store.job(jobId).get();
    if (!job.exists || job.get('employerUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const ref = store.application(jobId, uid);
    const a = await ref.get();
    if (!a.exists || a.get('status') === 'withdrawn') throw err(req, 'This application was withdrawn', 'আবেদনটা তুলে নেওয়া হয়েছে');
    const note = str(req.body?.note, 300);
    await ref.update({ status, note, updatedAt: Date.now() });
    const [en, bn] = APP_TEXT[status];
    await pushTo(uid, (u, lang) => ({
      title: R.L(lang, en, bn),
      body: [job.get('title'), job.get('orgName'), note].filter(Boolean).join(' · '),
      data: { type: 'job', jobId },
    }));
    return { ok: true };
  },

  // ================= চাকরি: প্রার্থী =================
  /** জেলার চালু চাকরি, ফিল্টারসহ; নতুন আগে */
  async jobs(req, user) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const [snap, mine] = await Promise.all([store.openJobsIn(b.district), store.applicationsOf(user.uid)]);
    const applied = new Map(mine.docs.map((d) => [d.get('jobId'), d.get('status')]));
    const today = R.todayDhakaUtcMidnight();
    const q = str(b.q, 60).toLowerCase();
    let items = snap.docs
      .filter((d) => (d.get('deadline') || 0) >= today)
      .map((d) => jobView(d, { myStatus: applied.get(d.id) || '' }));
    if (Number.isInteger(b.upazilaId)) items = items.filter((j) => j.upazilaId === b.upazilaId || !j.upazilaId);
    if (CATEGORIES.includes(b.category)) items = items.filter((j) => j.category === b.category);
    if (TYPES.includes(b.type)) items = items.filter((j) => j.type === b.type);
    if (Number.isInteger(b.minSalary) && b.minSalary > 0) items = items.filter((j) => j.salaryMax >= b.minSalary);
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
    if (!['open', 'closed'].includes(s.get('status')) && !mineJob) throw err(req, 'This job is not available', 'এই চাকরির বিজ্ঞাপন এখন নেই', 404);
    const [emp, app] = await Promise.all([store.employer(s.get('employerUid')).get(), store.application(id, user.uid).get()]);
    return {
      job: jobView(s, { description: s.get('description') || '', mine: mineJob, statusReason: mineJob ? s.get('statusReason') || '' : '' }),
      employer: employerView(emp),
      myApplication: app.exists ? { status: app.get('status'), note: app.get('note') || '', at: app.get('createdAt') || 0 } : null,
    };
  },

  async job_apply(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const [s, p, u] = await Promise.all([store.job(id).get(), store.profile(user.uid).get(), store.user(user.uid).get()]);
    if (!s.exists || s.get('status') !== 'open' || (s.get('deadline') || 0) < R.todayDhakaUtcMidnight()) {
      throw err(req, 'This job is no longer taking applications', 'এই চাকরিতে আর আবেদন নেওয়া হচ্ছে না');
    }
    if (s.get('employerUid') === user.uid) throw err(req, "You can't apply to your own job", 'নিজের বিজ্ঞাপনে আবেদন করা যায় না');
    if (!cvComplete(p.exists ? p.data() : null)) {
      throw new HttpError(400, C.tr(req, 'Complete your CV first: a headline, and your experience or education', 'আগে সিভি পূরণ করুন: শিরোনাম, আর অভিজ্ঞতা বা শিক্ষা'), 'cv_incomplete');
    }
    if (!(u.exists && u.get('phone'))) throw err(req, 'Add your mobile number in your profile first', 'আগে প্রোফাইলে মোবাইল নম্বর দিন');
    const ref = store.application(id, user.uid);
    const before = await ref.get();
    if (before.exists && before.get('status') !== 'withdrawn') return { ok: true, already: true };
    await useQuota(req, user.uid, 'apply');
    const fresh = await db.runTransaction(async (t) => {
      const a = await t.get(ref);
      if (a.exists && a.get('status') !== 'withdrawn') return false;
      t.set(ref, {
        jobId: id, uid: user.uid, employerUid: s.get('employerUid'), status: 'applied', coverNote: str(req.body?.coverNote, 500),
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

  async application_withdraw(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.application(id, user.uid);
    const a = await ref.get();
    if (!a.exists) return { ok: true };
    if (['hired', 'rejected'].includes(a.get('status'))) throw err(req, 'A decision has already been made', 'সিদ্ধান্ত হয়ে গেছে, এখন তুলে নেওয়া যায় না');
    await ref.update({ status: 'withdrawn', updatedAt: Date.now() });
    return { ok: true };
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
      job: jobs[i] && jobs[i].exists ? jobView(jobs[i]) : null,
    })).sort((a, b) => (b.updatedAt || b.appliedAt) - (a.updatedAt || a.appliedAt));
    return { items };
  },

  // ================= অ্যাডমিন =================
  async admin_jobs(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'open', 'closed', 'rejected', 'suspended'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await store.jobsByStatus(status);
    const V = require('./verify');
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...jobView(d),
        description: d.get('description') || '',
        employerUid: d.get('employerUid'),
        ownerUid: d.get('employerUid'),
        statusReason: d.get('statusReason') || '',
        verification: d.get('verification') || null,
        ...(['pending', 'rejected'].includes(status) ? { verify: await V.panelInfo('job', d.id, d.data()).catch(() => null) } : {}),
      }))),
    };
  },

  /** অনুমোদন (open) — প্যানেলে সব বিষয় টিক দিয়ে; বাতিল বা বন্ধ কারণসহ */
  async admin_job_resolve(req, user) {
    await requireAdmin(req, user);
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const status = { approved: 'open', rejected: 'rejected', suspended: 'suspended' }[req.body?.status];
    if (!status) throw err(req, 'Not found', 'পাওয়া যায়নি');
    const ref = store.job(id);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    if (s.get('status') === status) return { ok: true, unchanged: true };
    const reason = str(req.body?.reason, 200);
    const verification = status === 'open' && ['pending', 'rejected'].includes(s.get('status'))
      ? require('./verify').assertChecks(req, user, 'job', s.data(), req.body?.checks) : null;
    await ref.update({
      status, reviewedAt: Date.now(), reviewedBy: user.uid,
      ...(verification ? { verification } : {}),
      ...(status === 'open' ? { statusReason: FieldValue.delete() } : { statusReason: reason }),
    });
    if (status === 'suspended') await notifyClosed(id, s.data());
    const title = status === 'open' ? ['Your job is now live', 'আপনার চাকরির বিজ্ঞাপন চালু হয়েছে']
      : status === 'rejected' ? ['Job not approved', 'চাকরির বিজ্ঞাপন অনুমোদন হয়নি'] : ['Your job was taken down', 'আপনার চাকরির বিজ্ঞাপন বন্ধ করা হয়েছে'];
    await pushTo(s.get('employerUid'), (u, lang) => ({
      title: R.L(lang, title[0], title[1]),
      body: [s.get('title'), reason ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : ''].filter(Boolean).join(' · '),
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
    // চালু বিজ্ঞাপনে "যাচাই করা" চিহ্ন
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
};

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/** বিজ্ঞাপন বন্ধ হলে যাঁদের সিদ্ধান্ত হয়নি তাঁদের জানানো */
async function notifyClosed(jobId, job) {
  try {
    const apps = (await store.applicationsFor(jobId)).docs.filter((d) => ['applied', 'seen', 'shortlisted', 'interview'].includes(d.get('status')));
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

/** দৈনিক: শেষ তারিখ পার হওয়া বিজ্ঞাপন বন্ধ */
async function closeExpiredJobs(now = Date.now()) {
  const today = R.todayDhakaUtcMidnight(now);
  const snap = await store.jobsByStatus('open');
  const old = snap.docs.filter((d) => (d.get('deadline') || 0) < today);
  for (let i = 0; i < old.length; i += 400) {
    const batch = db.batch();
    old.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'closed', closedAt: now, closedBy: 'deadline' }));
    await batch.commit();
  }
  return old.length;
}

/** অ্যাকাউন্ট মোছার সময়: সিভি, নিয়োগকর্তার তথ্য, আবেদন; চালু বিজ্ঞাপন বন্ধ */
async function deleteForUser(uid) {
  const [jobs, apps] = await Promise.all([store.jobsOf(uid), store.applicationsOf(uid)]);
  const batch = db.batch();
  jobs.docs.filter((d) => ['open', 'pending'].includes(d.get('status'))).forEach((d) => batch.update(d.ref, { status: 'closed', closedAt: Date.now(), closedBy: 'deleted' }));
  apps.docs.forEach((d) => batch.delete(d.ref));
  batch.delete(store.profile(uid));
  batch.delete(store.employer(uid));
  await batch.commit();
}

/** শেয়ার লিংকের পাতার জন্য: সবার জন্য খোলা সিভি */
async function publicCv(uid) {
  if (!R.isValidId(uid)) return null;
  const [p, u] = await Promise.all([store.profile(uid).get(), store.user(uid).get()]);
  if (!p.exists || p.get('public') !== true) return null;
  const all = p.get('contactVisibility') === 'all';
  return cvView(uid, p.data(), u, { contact: all, email: all ? await emailOf(uid) : '' });
}

module.exports = { actions, CATEGORIES, TYPES, EDUCATION, EMPLOYER_KINDS, parseProfile, parseJob, cvComplete, closeExpiredJobs, deleteForUser, publicCv, suspendByReport };
