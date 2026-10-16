// কাগজের ছবি দিয়ে যাচাই: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক আর পেশাদার ব্যাজ।
// মালিক যোগ করার সময় কাগজের ছবি দেন; অ্যাডমিন প্যানেলে ছবি দেখে প্রতিটা বিষয় টিক দেন —
// সব টিক না হলে অনুমোদন হয় না (সার্ভারও যাচাই করে)। প্রতিটা বিষয়ের পাশে "সঠিক রূপ" লেখা থাকে।
//
// verifyDocs/{kind}_{id}_{type}: { kind, listingId, type, ownerUid, data (JPEG base64), size, at }
// অ্যাপ সরাসরি পড়তে/লিখতে পারে না (Firestore নিয়মে এই সংগ্রহ নেই) — শুধু সার্ভার।
// Firebase Storage বিনামূল্যের প্ল্যানে নেই, তাই ছোট করা ছবি (≤ ~৪০০ KB) সরাসরি ডেটাবেসে।
const { db, FieldValue, HttpError } = require('./firebase');
const C = require('./community');
const R = require('./rules');

const MAX_B64 = 700 * 1024;

// ---------- কোন কাগজ লাগে ----------
const DOCS = {
  ambulance: () => [
    { type: 'reg', label: 'রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড', hint: 'BRTA-র কাগজ — গাড়ির নম্বর আর ধরন পরিষ্কার পড়া যায়', required: true },
    { type: 'fitness', label: 'ফিটনেস সনদ', hint: 'মেয়াদের তারিখ পড়া যায় এমন', required: true },
    { type: 'front', label: 'গাড়ির ছবি (নম্বর প্লেটসহ)', hint: 'সামনে বা পেছন থেকে, প্লেট পড়া যায়', required: true },
  ],
  pharmacy: () => [
    { type: 'license', label: 'ড্রাগ লাইসেন্স', hint: 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়', required: true },
    { type: 'front', label: 'দোকানের সামনের ছবি (সাইনবোর্ডসহ)', hint: 'দোকানের নাম পড়া যায়', required: true },
    { type: 'pharmacist', label: 'ফার্মাসিস্টের নিবন্ধন সনদ', hint: 'থাকলে দিন', required: false },
  ],
  bloodbank: (x) => [
    { type: 'front', label: 'ব্লাড ব্যাংকের ছবি (সাইনবোর্ড বা প্রবেশপথ)', hint: 'নাম পড়া যায়', required: true },
    { type: 'license', label: 'স্বাস্থ্য অধিদপ্তরের ব্লাড ব্যাংক লাইসেন্স', hint: 'বেসরকারি হলে লাগবে', required: x && x.kind === 'private' },
    { type: 'authority', label: 'হাসপাতাল বা সংগঠনের প্যাডে প্রত্যয়ন', hint: 'সরকারি হাসপাতাল বা সংগঠন হলে দিন', required: false },
  ],
  lab: () => [
    { type: 'license', label: 'স্বাস্থ্য অধিদপ্তরের ক্লিনিক/ডায়াগনস্টিক লাইসেন্স', hint: 'পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়', required: true },
    { type: 'front', label: 'সেন্টারের সামনের ছবি (সাইনবোর্ডসহ)', hint: 'নাম পড়া যায়', required: true },
  ],
  badge: (x) => {
    const t = x && x.type;
    if (t === 'doctor') {
      return [
        { type: 'cert', label: 'BMDC নিবন্ধন সনদ', hint: 'থাকলে দিন — নম্বর অনলাইনে মিলিয়ে দেখা হয়', required: false },
        { type: 'idcard', label: 'কর্মস্থলের পরিচয়পত্র', hint: 'থাকলে দিন', required: false },
      ];
    }
    if (t === 'nurse') {
      return [
        { type: 'cert', label: 'নার্সিং ও মিডওয়াইফারি কাউন্সিলের (BNMC) নিবন্ধন সনদ', hint: 'নম্বর আর নাম পড়া যায়', required: true },
        { type: 'idcard', label: 'কর্মস্থলের পরিচয়পত্র', hint: 'থাকলে দিন', required: false },
      ];
    }
    return [
      { type: 'cert', label: 'পেশার সনদ বা ডিগ্রির কাগজ', hint: 'নাম আর প্রতিষ্ঠান পড়া যায়', required: true },
      { type: 'idcard', label: 'কর্মস্থলের পরিচয়পত্র', hint: 'থাকলে দিন', required: false },
    ];
  },
};

// ---------- অ্যাডমিন যা যা মিলিয়ে টিক দেবেন (format = সঠিক রূপ) ----------
const PHONE_CHECK = { id: 'phone', label: 'অ্যাপে দেওয়া নম্বরে ফোন করে কথা হয়েছে', format: 'মোবাইল ১১ অঙ্ক (০১…); ল্যান্ডলাইন এলাকার কোড দিয়ে (০২…, ০৯১…); হটলাইন ৫ অঙ্ক (১৬…)' };

const CHECKS = {
  ambulance: () => [
    { id: 'reg_no', label: 'রেজিস্ট্রেশন সনদের নম্বর আর অ্যাপে লেখা গাড়ির নম্বর হুবহু এক',
      format: 'জেলা বা মেট্রো – শ্রেণির অক্ষর – দুই অঙ্ক – চার অঙ্ক। যেমন: ঢাকা মেট্রো-ছ ১১-২২৩৩ বা ময়মনসিংহ-ছ ১১-০০৪৫' },
    { id: 'reg_type', label: 'রেজিস্ট্রেশনে গাড়ির ধরন "অ্যাম্বুলেন্স" লেখা',
      format: 'মাইক্রোবাস বা প্রাইভেটকার লেখা থাকলে বাতিল করুন — অনেক মাইক্রোবাস অ্যাম্বুলেন্স সেজে চলে' },
    { id: 'plate', label: 'গাড়ির ছবির নম্বর প্লেটে একই নম্বর', format: 'প্লেটের প্রতিটা অক্ষর আর অঙ্ক রেজিস্ট্রেশনের সাথে মিলিয়ে দেখুন' },
    { id: 'fitness', label: 'ফিটনেস সনদ এই গাড়িরই, আর মেয়াদ শেষ হয়নি', format: 'সনদের "মেয়াদ" বা "Valid up to" তারিখ আজকের পরে' },
    PHONE_CHECK,
    { id: 'details', label: 'নাম, এলাকা আর ভাড়ার তথ্য স্বাভাবিক (বিজ্ঞাপন বা ভুয়া নয়)', format: 'নামে শুধু সার্ভিস বা চালকের নাম; ফোন নম্বর বা লিংক নয়' },
  ],
  pharmacy: () => [
    { id: 'license_no', label: 'লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক', format: 'ছবিতে যেভাবে লেখা ঠিক সেভাবে — প্রতিটা অক্ষর, অঙ্ক, হাইফেন মিলবে' },
    { id: 'license_name', label: 'লাইসেন্সে দোকানের নাম আর অ্যাপের নাম এক', format: 'বানানের ছোট পার্থক্য চলবে, অন্য নাম নয়' },
    { id: 'license_addr', label: 'লাইসেন্সের ঠিকানা আর অ্যাপের জেলা ও ঠিকানা এক' },
    { id: 'license_valid', label: 'লাইসেন্সের মেয়াদ শেষ হয়নি', format: 'লাইসেন্সে লেখা মেয়াদ বা নবায়নের শেষ তারিখ আজকের পরে' },
    { id: 'license_seal', label: 'ঔষধ প্রশাসন অধিদপ্তরের সিল আর কর্মকর্তার স্বাক্ষর আছে' },
    { id: 'signboard', label: 'দোকানের ছবির সাইনবোর্ডে একই নাম' },
    PHONE_CHECK,
  ],
  bloodbank: (x) => [
    { id: 'name', label: 'নাম ছবির সাইনবোর্ড বা কাগজের সাথে মেলে' },
    { id: 'kind', label: 'ধরন ঠিক (সরকারি হাসপাতাল / বেসরকারি / স্বেচ্ছাসেবী সংগঠন)' },
    { id: 'address', label: 'ঠিকানা আর জেলা মেলে' },
    ...(x && x.kind === 'private' ? [
      { id: 'license_no', label: 'লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক', format: 'ছবিতে যেভাবে লেখা ঠিক সেভাবে' },
      { id: 'license_name', label: 'লাইসেন্সে একই প্রতিষ্ঠানের নাম' },
      { id: 'license_valid', label: 'লাইসেন্সের মেয়াদ শেষ হয়নি', format: 'মেয়াদের শেষ তারিখ আজকের পরে' },
    ] : []),
    { ...PHONE_CHECK, label: 'হাসপাতাল বা ব্লাড ব্যাংকের নম্বরে ফোন করে নিশ্চিত হয়েছেন' },
  ],
  lab: () => [
    { id: 'license_no', label: 'লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক', format: 'ছবিতে যেভাবে লেখা ঠিক সেভাবে' },
    { id: 'license_name', label: 'লাইসেন্সে সেন্টারের নাম আর অ্যাপের নাম এক' },
    { id: 'license_valid', label: 'লাইসেন্সের মেয়াদ শেষ হয়নি', format: 'মেয়াদের শেষ তারিখ আজকের পরে' },
    { id: 'license_type', label: 'লাইসেন্স ডায়াগনস্টিক/প্যাথলজির জন্য (শুধু ক্লিনিক নয়)' },
    { id: 'signboard', label: 'ছবির সাইনবোর্ডে একই নাম' },
    PHONE_CHECK,
  ],
  badge: (x) => {
    const t = x && x.type;
    if (t === 'doctor') {
      return [
        { id: 'bmdc_found', label: 'BMDC সাইটে নম্বর দিয়ে ডাক্তার পাওয়া গেছে', link: 'https://www.bmdc.org.bd/view-doctor',
          format: 'MBBS হলে A- এর পরে অঙ্ক (যেমন A-১২৩৪৫), BDS হলে D- এর পরে অঙ্ক' },
        { id: 'bmdc_name', label: 'BMDC-তে নাম অ্যাপের নামের সাথে মেলে', format: 'বানানের ছোট পার্থক্য চলবে; ভিন্ন মানুষ হলে বাতিল' },
        { id: 'bmdc_active', label: 'নিবন্ধন বৈধ (বাতিল বা মেয়াদোত্তীর্ণ নয়)' },
        { id: 'workplace', label: 'কর্মস্থল বাস্তব', format: 'হাসপাতাল বা ক্লিনিকে ফোন করে বা তাদের ওয়েবসাইটে মিলিয়েছেন' },
      ];
    }
    if (t === 'nurse') {
      return [
        { id: 'cert_no', label: 'সনদের নিবন্ধন নম্বর আর অ্যাপে লেখা নম্বর এক', format: 'সনদে যেভাবে লেখা ঠিক সেভাবে' },
        { id: 'cert_name', label: 'সনদে নাম অ্যাপের নামের সাথে মেলে' },
        { id: 'cert_valid', label: 'নিবন্ধনের মেয়াদ শেষ হয়নি', format: 'সনদে নবায়নের তারিখ থাকলে তা আজকের পরে' },
        { id: 'workplace', label: 'কর্মস্থল বাস্তব', format: 'হাসপাতাল বা ক্লিনিকে ফোন করে মিলিয়েছেন' },
      ];
    }
    return [
      { id: 'cert_name', label: 'সনদে নাম অ্যাপের নামের সাথে মেলে' },
      { id: 'cert_relevant', label: 'সনদটা এই পেশার (পুষ্টিবিদ, প্রশিক্ষক ইত্যাদি) সাথে সম্পর্কিত' },
      { id: 'workplace', label: 'কর্মস্থল বা কাজের জায়গা বাস্তব' },
    ];
  },
};

const spec = (kind, x) => ({ docs: DOCS[kind](x), checks: CHECKS[kind](x) });
const docId = (kind, id, type) => `verifyDocs/${kind}_${id}_${type}`;

function cleanB64(v) {
  if (typeof v !== 'string') return null;
  const s = v.replace(/^data:image\/[a-z]+;base64,/, '').replace(/\s+/g, '');
  if (!s || s.length > MAX_B64 || !/^[A-Za-z0-9+/]+=*$/.test(s)) return null;
  // JPEG বা PNG কি না (প্রথম কয়েক বাইট)
  const head = Buffer.from(s.slice(0, 16), 'base64');
  const jpeg = head[0] === 0xff && head[1] === 0xd8;
  const png = head[0] === 0x89 && head[1] === 0x50;
  return jpeg || png ? s : null;
}

/** কোন কোন কাগজ জমা আছে — তালিকার নথির docsHave থেকে (ছবি না নামিয়েই) */
async function present(kind, id, x) {
  return new Set(Object.keys((x && x.docsHave) || {}));
}

/**
 * ছবি পরীক্ষা (লেখার আগে) — দৈনিক সীমা খরচের আগেই ভুল ধরা পড়ে।
 * ফেরত: { type: base64 } শুধু ঠিকঠাক ছবি
 */
function validateDocs(req, kind, x, docs, isNew) {
  const list = DOCS[kind](x);
  const given = docs && typeof docs === 'object' ? docs : {};
  const clean = {};
  for (const d of list) {
    if (given[d.type] == null) continue;
    const b = cleanB64(given[d.type]);
    if (!b) throw new HttpError(400, C.tr(req, `The photo "${d.label}" could not be read or is too large`, `"${d.label}"-এর ছবিটা পড়া যায়নি বা অনেক বড়`));
    clean[d.type] = b;
  }
  if (isNew) {
    const missing = list.filter((d) => d.required && !clean[d.type]);
    if (missing.length) {
      throw new HttpError(400, C.tr(req,
        `Add photos of: ${missing.map((d) => d.label).join(', ')}. If you don't see this option, update the app.`,
        `এই কাগজের ছবি দিন: ${missing.map((d) => d.label).join(', ')}। ছবি দেওয়ার জায়গা না দেখলে অ্যাপ হালনাগাদ করুন।`), 'docs_required');
    }
  }
  return clean;
}

/**
 * তালিকা বা ব্যাজ সেভের সাথে আসা ছবি রাখা। isNew হলে দরকারি সব ছবি লাগবে।
 * docs: { type: base64 } — না পাঠানো ছবি আগের মতো থাকে।
 */
async function saveDocs(req, kind, id, ownerUid, x, docs, isNew) {
  const clean = validateDocs(req, kind, x, docs, isNew);
  const types = Object.keys(clean);
  if (!types.length) return null;
  const now = Date.now();
  const batch = db.batch();
  types.forEach((t) => batch.set(db.doc(docId(kind, id, t)), { kind, listingId: id, type: t, ownerUid: ownerUid || null, data: clean[t], size: clean[t].length, at: now }));
  // আগে থেকে থাকা তালিকা/ব্যাজ: কোন ছবি আছে তার হিসাব একই সাথে (নতুন তালিকায় ডাকা জায়গা নিজে বসায়)
  if (!isNew) batch.update(db.doc(`${COLL[kind]}/${id}`), Object.fromEntries(types.map((t) => [`docsHave.${t}`, now])));
  await batch.commit();
  return Object.fromEntries(types.map((t) => [t, now]));
}

async function deleteDocs(kind, id, x) {
  await Promise.all(DOCS[kind](x || { kind: 'private', type: 'nurse' }).map((d) => db.doc(docId(kind, id, d.type)).delete().catch(() => {})));
  // ধরন বদলের কারণে অন্য কাগজও থাকতে পারে
  const snap = await db.collection('verifyDocs').where('listingId', '==', id).limit(10).get().catch(() => ({ docs: [] }));
  await Promise.all(snap.docs.filter((d) => d.get('kind') === kind).map((d) => d.ref.delete().catch(() => {})));
}

/** অ্যাকাউন্ট মোছার সময় সব কাগজ */
async function deleteForOwner(uid) {
  const snap = await db.collection('verifyDocs').where('ownerUid', '==', uid).limit(100).get();
  await Promise.all(snap.docs.map((d) => d.ref.delete().catch(() => {})));
}

/**
 * অনুমোদনের আগে: প্যানেল থেকে আসা টিকগুলোতে সব বিষয় আছে কি না। সব না থাকলে অনুমোদন হয় না।
 * ফেরত: সংরক্ষণের জন্য যাচাইয়ের রেকর্ড
 */
function assertChecks(req, user, kind, x, checks) {
  const have = new Set(Object.keys((x && x.docsHave) || {}));
  const noPhoto = DOCS[kind](x).filter((d) => d.required && !have.has(d.type));
  if (noPhoto.length) {
    throw new HttpError(400, C.tr(req, 'Required document photos are missing — ask the owner for them first.', 'দরকারি কাগজের ছবি নেই — আগে মালিকের কাছে ছবি চান।'), 'docs_required');
  }
  const need = CHECKS[kind](x).map((c) => c.id);
  const got = new Set(Array.isArray(checks) ? checks.filter((c) => typeof c === 'string') : []);
  const missing = need.filter((id) => !got.has(id));
  if (missing.length) {
    throw new HttpError(400, C.tr(req,
      'Tick every check in the web admin panel after looking at the photos, then approve.',
      'ওয়েব অ্যাডমিন প্যানেলে ছবি দেখে সব বিষয় টিক দিয়ে তারপর অনুমোদন দিন।'), 'checks_required');
  }
  return { by: user.uid, byEmail: (user.email || '').toLowerCase(), at: Date.now(), checks: need };
}

/** প্যানেলের তালিকায়: কোন কাগজ আছে, কী টিক দিতে হবে */
async function panelInfo(kind, id, x) {
  const s = spec(kind, x);
  const has = await present(kind, id, x).catch(() => new Set());
  return {
    docs: s.docs.map((d) => ({ ...d, has: has.has(d.type) })),
    checks: s.checks,
    docsRequestedAt: x.docsRequestedAt || 0,
    verification: x.verification || null,
  };
}

/** মালিক নিজের কাগজ দেখার জন্য: কোনগুলো বাকি */
async function ownerInfo(kind, id, x) {
  const has = await present(kind, id, x).catch(() => new Set());
  const list = DOCS[kind](x);
  return {
    docs: list.map((d) => ({ type: d.type, label: d.label, hint: d.hint, required: d.required, has: has.has(d.type) })),
    docsMissing: list.some((d) => d.required && !has.has(d.type)),
    docsRequested: !!x.docsRequestedAt,
  };
}

const COLL = { ambulance: 'ambulances', pharmacy: 'pharmacies', bloodbank: 'bloodBanks', badge: 'badges', lab: 'labs' };
const isKind = (k) => Object.prototype.hasOwnProperty.call(COLL, k);

async function loadTarget(req, kind, id) {
  if (!isKind(kind) || !R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
  const s = await db.doc(`${COLL[kind]}/${id}`).get();
  if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
  const owner = kind === 'badge' || kind === 'pharmacy' || kind === 'lab' ? id : s.get('ownerUid');
  return { s, x: s.data(), owner };
}

const actions = {
  /** মালিক: পরে কাগজের ছবি দেওয়া (অ্যাডমিন চাইলে, বা আগের ভার্সনে যোগ করা তালিকা) */
  async verify_docs_upload(req, user) {
    const { kind, id } = req.body || {};
    const { s, x, owner } = await loadTarget(req, kind, id);
    if (owner !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    validateDocs(req, kind, x, req.body?.docs, false);
    await require('./community-db').useQuota(req, user.uid, 'docs');
    const have = await saveDocs(req, kind, id, user.uid, x, req.body?.docs, false);
    if (have) await s.ref.update({ docsRequestedAt: FieldValue.delete(), docsRequestNote: FieldValue.delete(), docsSentAt: Date.now() });
    return { ok: true, saved: have ? Object.keys(have).length : 0, ...(await ownerInfo(kind, id, { ...x, docsHave: { ...(x.docsHave || {}), ...(have || {}) }, docsRequestedAt: 0 })) };
  },

  /** অ্যাডমিন: যাচাইয়ের জন্য কাগজের ছবি (প্যানেলে চাইলে তবেই আসে) */
  async admin_verify_docs(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const { kind, id } = req.body || {};
    const { x } = await loadTarget(req, kind, id);
    const list = DOCS[kind](x);
    const snaps = await db.getAll(...list.map((d) => db.doc(docId(kind, id, d.type))));
    // হিসাব আসল ছবি থেকে (তালিকার docsHave পুরোনো হলেও ঠিক দেখায়)
    x.docsHave = Object.fromEntries(list.filter((d, i) => snaps[i].exists).map((d) => [d.type, (x.docsHave || {})[d.type] || Date.now()]));
    return {
      ...(await panelInfo(kind, id, x)),
      photos: list.map((d, i) => ({ type: d.type, label: d.label, data: snaps[i].exists ? snaps[i].get('data') : null, at: snaps[i].exists ? snaps[i].get('at') : 0 })),
    };
  },

  /** অ্যাডমিন: কাগজের ছবি চাওয়া — মালিক নোটিফিকেশন পান, অ্যাপে "ছবি দিন" দেখায় */
  async admin_request_docs(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const { kind, id } = req.body || {};
    const { s, x, owner } = await loadTarget(req, kind, id);
    if (!owner) throw new HttpError(400, C.tr(req, 'This was added by an admin and has no owner', 'এটা অ্যাডমিন যোগ করেছেন, কোনো মালিক নেই'));
    const note = C.cleanText(req.body?.note, 200) || '';
    await s.ref.update({ docsRequestedAt: Date.now(), docsRequestNote: note });
    const label = { ambulance: ['ambulance', 'অ্যাম্বুলেন্স'], pharmacy: ['pharmacy', 'ফার্মেসি'], bloodbank: ['blood bank', 'ব্লাড ব্যাংক'], badge: ['badge', 'ব্যাজ'], lab: ['diagnostic lab', 'প্যাথলজি'] }[kind];
    await require('./requestend').pushUsers([owner], (u, lang) => ({
      title: R.L(lang, `Add document photos for your ${label[0]}`, `আপনার ${label[1]}-এর কাগজের ছবি দিন`),
      body: [x.name || '', note].filter(Boolean).join(' · ') || R.L(lang, 'Open the app to add them. It will be checked after that.', 'অ্যাপ খুলে ছবি দিন। এরপর যাচাই হবে।'),
      data: { type: kind === 'badge' ? 'badge' : 'services', ...(kind === 'badge' ? {} : { kind }) },
    }));
    return { ok: true };
  },
};

module.exports = { actions, spec, validateDocs, saveDocs, deleteDocs, deleteForOwner, assertChecks, panelInfo, ownerInfo, DOCS, CHECKS };
