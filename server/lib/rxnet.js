// প্রেসক্রিপশনের পরের ধাপগুলো (১৬ অক্টোবর ২০২৬):
//  ধাপ ২ ফার্মেসি — কোড দিয়ে প্রেসক্রিপশন খুলে কত ওষুধ দিল লেখা; বাকিটা অন্য ফার্মেসি দিতে পারে;
//         রোগী দেখেন কেনা/বাকি; কেনা ওষুধ শেষ হওয়ার ২ দিন আগে মনে করানো (ক্রন)
//  ধাপ ৪ স্টক — ফার্মেসি ওষুধের নামের তালিকা রাখে; রোগী দেখেন কোন দোকানে কয়টা আছে, প্রেসক্রিপশন পাঠান
//  ধাপ ৩ প্যাথলজি — অনুমোদিত ল্যাবে পরীক্ষা পাঠানো, ল্যাব ফল লেখে, রোগী পান; ডাক্তার দেখেন শুধু টাকা মিললে
//         (ডাক্তারের নিজের bKash/Nagad নম্বরে পাঠিয়ে TrxID — Lifolo টাকা ধরে না; ডাক্তার/সহকারী মিলিয়ে নিশ্চিত করেন)
// সব সংগ্রহ (rxOrders, pharmacyStock, labs, labOrders, labPrices, rxReviews) শুধু সার্ভার পড়ে-লেখে।
const { db, FieldValue, HttpError, sendPush, tokensOf } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const DISTRICTS = require('./districts');
const { UPAZILA } = require('./hospital-match');
const { useQuota, approvedBadge } = require('./community-db');
const P = require('./prescriptions');

const DAY_MS = 24 * 60 * 60 * 1000;
const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const notFound = (req) => err(req, 'Not found', 'পাওয়া যায়নি', 404);
const str = (v, n) => (typeof v === 'string' ? v.replace(/\r\n/g, '\n').trim().slice(0, n) : '');
const key = (name) => String(name || '').trim().toLowerCase().replace(/\s+/g, ' ');

/** রোগী আর তাঁর কেয়ারগিভারদের */
const pushPatient = (...a) => require('./careaccess').pushPatient(...a);
const LT = require('./labtests');
// পরীক্ষার দাম আর বাসা থেকে নমুনা (labprices.js)
const LP = require('./labprices');

/** body: লেখা, বা { en, bn } (যাঁকে পাঠানো হচ্ছে তাঁর ভাষায়) */
async function push(uid, en, bn, body, data) {
  if (!uid) return;
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists) return;
    const text = body && typeof body === 'object' ? R.L(u.get('lang'), body.en, body.bn) : body || '';
    await sendPush([{ uid, tokens: tokensOf(u), title: R.L(u.get('lang'), en, bn), body: text, data }]);
  } catch (e) {
    console.error('rxnet push failed', e.message);
  }
}

// ---------------------------------------------------------------------------
// ওষুধের হিসাব
// ---------------------------------------------------------------------------

/** দিনে কয়টা: "1+0+1" → 2, "½+0+½" → 1; বুঝতে না পারলে 0 */
function perDay(dose) {
  const parts = String(dose || '').replace(/\s/g, '').replace(/½/g, '0.5').split('+');
  if (parts.length < 2 || parts.length > 4) return 0;
  let sum = 0;
  for (const p of parts) {
    const n = Number(p);
    if (!Number.isFinite(n) || n < 0) return 0;
    sum += n;
  }
  return sum;
}

/** গোনা যায় এমন (ট্যাবলেট/ক্যাপসুল, ধরন না লেখা হলেও) */
function countable(m) {
  const f = String(m.form || '').toLowerCase();
  return !f || f.includes('tablet') || f.includes('capsule') || f === 'tab.' || f === 'cap.';
}

/** কতটা লাগবে (পিস) — দিন আর নিয়ম জানা, গোনা যায় এমন ওষুধে; না জানলে null */
function needQty(m) {
  const d = perDay(m.dose);
  return countable(m) && d > 0 && m.days > 0 ? Math.ceil(d * m.days) : null;
}

/** প্রতিটা ওষুধে কেনা আর বাকি */
function medStatus(rx) {
  const got = new Map();
  for (const d of rx.dispensed || []) for (const it of d.items || []) got.set(it.i, (got.get(it.i) || 0) + it.qty);
  return (rx.medicines || []).map((m, i) => {
    const need = needQty(m);
    const g = got.get(i) || 0;
    return { i, name: m.name, form: m.form || '', strength: m.strength || '', dose: m.dose || '', days: m.days || 0, need, got: g,
      left: need == null ? null : Math.max(0, need - g), done: need == null ? g > 0 && m.days > 0 : g >= need };
  });
}

/**
 * দোকানে আনুমানিক খরচ: স্টকে আছে আর দাম জানা ওষুধে দাম × বাকি পরিমাণ (দাম = দোকানের ছাড় বাদে বিক্রি দাম, প্রতি পিস/বোতল…)।
 * পরিমাণ না জানলে: সিরাপ/মলম/ড্রপ ইত্যাদি (গোনা যায় না) ১টা ধরা; ট্যাবলেট/ক্যাপসুলে কয়টা জানা নেই — দাম ধরা হয় না।
 * { total (পুরো টাকা), priced (কয়টার দাম জানা), of (কেনা বাকি কয়টা) } — একটারও দাম না জানলে null।
 */
function pharmacyEstimate(wanted, keys, prices) {
  if (!prices) return null;
  let sum = 0;
  let priced = 0;
  for (const w of wanted) {
    if (!keys.has(w.k)) continue;
    const p = Object.prototype.hasOwnProperty.call(prices, w.k) ? prices[w.k] : null;
    if (typeof p !== 'number' || !(p > 0)) continue;
    const q = w.left != null ? w.left : w.countable ? null : 1;
    if (!(q > 0)) continue;
    sum += p * q;
    priced++;
  }
  return priced ? { total: Math.round(sum), priced, of: wanted.length } : null;
}

/** placeId-এর একটা দোকান/প্যাথলজি: অনুমোদিত আর এই জেলার হলে [ডক], নইলে [] */
async function onePlace(col, id, district) {
  const d = await db.doc(`${col}/${id}`).get();
  return d.exists && d.get('status') === 'approved' && d.get('district') === district ? [d] : [];
}

/**
 * কবে মনে করাতে হবে: কেনা ওষুধ (গোনা যায়, নিয়ম জানা) যেদিন শেষ হবে তার ২ দিন আগে —
 * পুরো কোর্স কেনা হয়ে গেলে নয়; "চলবে" ওষুধে সবসময়। ঢাকার তারিখ (UTC মধ্যরাত), না থাকলে null।
 */
function refillDate(rx, now = Date.now()) {
  const first = new Map();
  for (const d of rx.dispensed || []) for (const it of d.items || []) if (!first.has(it.i)) first.set(it.i, d.at);
  let best = null;
  for (const s of medStatus(rx)) {
    const m = rx.medicines[s.i];
    const pd = perDay(m.dose);
    if (!countable(m) || pd <= 0 || s.got <= 0) continue;
    if (s.need != null && s.got >= s.need) continue;
    const runOut = P.dhakaToday(first.get(s.i)) + Math.floor(s.got / pd) * DAY_MS;
    // অল্প কিনলে (২ দিনের কম) কাল মনে করানো — আজকের তারিখ পেরিয়ে গেলে ক্রন আর ধরে না
    const at = Math.max(runOut - 2 * DAY_MS, P.dhakaToday(now) + DAY_MS);
    if (best == null || at < best) best = at;
  }
  return best;
}

async function myPharmacy(req, user) {
  const s = await db.doc(`pharmacies/${user.uid}`).get();
  if (!s.exists || s.get('status') !== 'approved') {
    throw err(req, 'Only approved pharmacies can do this', 'শুধু অনুমোদিত ফার্মেসি এটা করতে পারে', 403);
  }
  return s;
}

async function rxByCode(req, code, uid) {
  const c = String(code || '').trim().toUpperCase().replace(/[\s-]/g, '');
  if (!P.CODE_RE.test(c)) throw err(req, 'Check the code on the prescription', 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
  const snap = await db.collection('prescriptions').where('code', '==', c).limit(1).get();
  // ডেমো: ডেমো দোকান/প্যাথলজি আসল প্রেসক্রিপশন খুলতে পারে না, আসলরাও ডেমোর নয় (ডেমো মুছলে uid অংশ মুছবেন)
  if (snap.empty || (uid && !require('./demo').sameWorld(uid, snap.docs[0].get('doctorUid')))) throw err(req, 'No prescription with this code', 'এই কোডের কোনো প্রেসক্রিপশন নেই', 404);
  return snap.docs[0];
}

/** ফার্মেসির দেখা: ডাক্তার, রোগীর নাম-বয়স, তারিখ, ওষুধ (কেনা/বাকি), আগের বিক্রি — রোগনির্ণয়/ফোন নয় */
function pharmacyRxView(d) {
  const x = d.data();
  return {
    id: d.id,
    code: x.code,
    status: x.status,
    createdAt: x.createdAt,
    doctor: { name: x.doctor?.name || '', degrees: x.doctor?.degrees || '', regNo: x.doctor?.regNo || '' },
    patient: { name: x.patient?.name || '', age: x.patient?.age ?? null },
    medicines: medStatus(x).map((s) => ({ ...s, timing: x.medicines[s.i].timing || '', note: x.medicines[s.i].note || '' })),
    dispensed: (x.dispensed || []).map((p) => ({ name: p.name, at: p.at, items: p.items })),
  };
}

// ---------------------------------------------------------------------------
// প্যাথলজি (ল্যাব) তালিকা
// ---------------------------------------------------------------------------

function labView(d, mine = false) {
  const x = d.data();
  return {
    id: d.id,
    name: x.name,
    phone: K.fixPhone(x.phone),
    address: x.address || '',
    district: x.district,
    upazilaId: x.upazilaId || null,
    facilityId: x.facilityId || null,
    homeCollection: x.homeCollection === true,
    hours: x.hours || '',
    ...(mine ? { licenseNo: x.licenseNo, status: x.status, statusReason: x.statusReason || '' } : {}),
  };
}

async function areaLabs(district) {
  const M = require('./memcache');
  const rows = await M.cached(`labs:${district}`, 60 * 1000, async () =>
    (await db.collection('labs').where('district', '==', district).limit(500).get()).docs
      .filter((d) => d.get('status') === 'approved').map((d) => ({ id: d.id, x: d.data() })));
  return rows.map(({ id, x }) => M.docLike(id, x));
}

async function myLab(req, user) {
  const s = await db.doc(`labs/${user.uid}`).get();
  if (!s.exists || s.get('status') !== 'approved') throw err(req, 'Only approved labs can do this', 'শুধু অনুমোদিত প্যাথলজি এটা করতে পারে', 403);
  return s;
}

// ---------------------------------------------------------------------------
// রিপোর্ট দেখানো (ডাক্তার)
// ---------------------------------------------------------------------------

function reviewSettings(prov) {
  const r = (prov && prov.get && prov.get('reportReview')) || {};
  return { enabled: r.enabled === true, noFee: r.noFee === true, fee: r.fee || '', payNumber: r.payNumber || '', payMethods: Array.isArray(r.payMethods) ? r.payMethods : [] };
}

function reviewView(d, withSecret = false) {
  const x = d.data();
  return {
    id: d.id,
    rxId: x.rxId,
    labOrderId: x.labOrderId,
    doctorUid: x.doctorUid,
    doctorName: x.doctorName || '',
    patientName: x.patientName || '',
    labName: x.labName || '',
    tests: x.tests || [],
    status: x.status,
    fee: x.fee || '',
    noFee: x.noFee === true,
    payNumber: x.payNumber || '',
    payMethods: x.payMethods || [],
    method: x.method || '',
    ...(withSecret ? { trxId: x.trxId || '' } : {}),
    rejectNote: x.rejectNote || '',
    comment: x.comment || '',
    newRxId: x.newRxId || null,
    createdAt: x.createdAt || 0,
    updatedAt: x.updatedAt || 0,
  };
}

/** ডাক্তার নিজে বা তাঁর সহকারী */
async function doctorOrAssistant(req, user, doctorUid) {
  const d = typeof doctorUid === 'string' && doctorUid ? doctorUid : user.uid;
  if (d === user.uid) {
    if ((await approvedBadge(user.uid)) !== 'doctor') throw err(req, 'Doctors only', 'শুধু ডাক্তার', 403);
    return { doctorUid: d, role: 'doctor' };
  }
  if (!R.isValidId(d)) throw notFound(req);
  const p = await db.doc(`providers/${d}`).get();
  const email = user.email_verified ? String(user.email || '').toLowerCase() : '';
  if (!email || !p.exists || !(p.get('assistantEmails') || []).includes(email)) throw err(req, "You are not this doctor's assistant", 'আপনি এই ডাক্তারের সহকারী নন', 403);
  return { doctorUid: d, role: 'assistant' };
}

/**
 * ল্যাব অর্ডার। who: কে দেখছেন — 'patient' (রোগী/কেয়ারগিভার), 'lab' (সেই প্যাথলজি), নইলে (ডাক্তার) কেউ নন।
 * বাসার ঠিকানা-ফোন শুধু রোগী, আর প্যাথলজি অর্ডার চালু থাকা পর্যন্ত (বাসায় যাওয়া বাতিল না হলে)।
 */
function labOrderView(d, withPhotos = false, who = null) {
  const x = d.data();
  const open = ['sent', 'received'].includes(x.status);
  const contact = who === 'patient' || (who === 'lab' && open && x.home && x.home.status !== 'declined');
  return {
    id: d.id,
    rxId: x.rxId,
    code: x.code,
    labId: x.labId,
    labName: x.labName || '',
    patientName: x.patientName || '',
    patientAge: x.patientAge ?? null,
    patientGender: x.patientGender || '',
    doctorName: x.doctorName || '',
    tests: x.tests || [],
    status: x.status,
    createdAt: x.createdAt || 0,
    // পাঠানোর সময়ের আনুমানিক দাম (প্যাথলজি আর রোগী একই হিসাব দেখেন)
    prices: x.prices || null,
    home: LP.homeView(x.home, contact),
    report: x.report ? {
      items: x.report.items || [],
      note: x.report.note || '',
      reportedAt: x.report.reportedAt || 0,
      photoCount: (x.report.photos || []).length,
      ...(withPhotos ? { photos: x.report.photos || [] } : {}),
    } : null,
  };
}

const MAX_PHOTO_B64 = 300 * 1024;

/**
 * একই রোগীর আগের রিপোর্টের ফল — ছকের ঘর ধরে (`code.key`), নতুন আগে, প্রতিটায় সর্বোচ্চ ৫টা।
 * রোগী আর ডাক্তার একই পরীক্ষার আগের-পরের ফল পাশাপাশি দেখেন (সব ল্যাবের)।
 * onlyLab: প্যাথলজি নিজে দেখলে শুধু তার নিজের আগের অর্ডারগুলো — অন্য ল্যাবের ফল কখনো নয় (১৮ অক্টোবর ২০২৬, গোপনীয়তা)।
 */
async function reportHistory(patientUid, exceptId, before, onlyLab = null) {
  if (!patientUid) return {};
  const snap = await db.collection('labOrders').where('patientUid', '==', patientUid).limit(200).get();
  const out = {};
  for (const d of snap.docs.filter((o) => o.id !== exceptId && o.get('status') === 'reported' && (o.get('reportedAt') || 0) < before
    && (!onlyLab || o.get('labId') === onlyLab))
    .sort((a, c) => (c.get('reportedAt') || 0) - (a.get('reportedAt') || 0))) {
    for (const it of d.get('report.items') || []) {
      if (!it.code || !it.key || it.long) continue;
      const k = `${it.code}.${it.key}`;
      const list = out[k] || (out[k] = []);
      if (list.length < 5) list.push({ result: it.result, unit: it.unit || '', flag: it.flag || '', at: d.get('reportedAt') || 0, lab: d.get('labName') || '' });
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// ডাক্তারের রোগীর ইতিহাসে পরীক্ষার রিপোর্ট (১৮ অক্টোবর ২০২৬, "ডাক্তারকে রিপোর্ট দেখানো")
// ---------------------------------------------------------------------------

/**
 * ডাক্তার ফল দেখেন শুধু lab_order_get-এর একই শর্তে: রিপোর্ট দেখার অনুরোধ (rxReviews) 'paid' বা 'done' —
 * অর্থাৎ রোগী টাকা দিয়েছেন আর ডাক্তার/সহকারী মিলিয়ে নিয়েছেন, অথবা ডাক্তার "ফি চাই না" দিয়েছেন (রিপোর্ট আসার সাথে সাথে 'paid')।
 * 'off' (অনলাইনে রিপোর্ট দেখা বন্ধ ছিল), 'unpaid', 'claimed' — ফল নয়, শুধু অবস্থা।
 */
const DOCTOR_SEES = ['paid', 'done'];
/** ইতিহাসে সর্বোচ্চ কয়টা প্রেসক্রিপশনের (নতুনগুলো) ল্যাব অর্ডার, আর প্রতিটায় কয়টা — উত্তর ছোট রাখতে */
const LAB_HISTORY_RX = 20;
const LAB_PER_RX = 10;

/**
 * ডাক্তারের দিক থেকে একটা ল্যাব অর্ডার: অবস্থা সবসময় (পাঠানো/নমুনা নেওয়া/বাতিল/রিপোর্ট এসেছে);
 * ফল (report: ঘর, নোট — ছবি নয়) শুধু open হলে, নইলে null। review: রিপোর্ট দেখার অনুরোধের অবস্থা — টাকা/TrxID নয়।
 */
function doctorLabView(d, r) {
  const x = d.data();
  const review = r && r.exists ? r.get('status') || null : null;
  const open = x.status === 'reported' && DOCTOR_SEES.includes(review);
  const v = labOrderView(d);
  return {
    id: d.id,
    rxId: x.rxId,
    labName: x.labName || '',
    tests: x.tests || [],
    status: x.status,
    createdAt: x.createdAt || 0,
    reportedAt: x.reportedAt || 0,
    review,
    open,
    report: open ? v.report : null,
    // ডাক্তার নিজে রোগীকে যা জানিয়েছিলেন
    comment: open && review === 'done' ? r.get('comment') || '' : '',
  };
}

/**
 * এই ডাক্তারের প্রেসক্রিপশনগুলোর (rxIds — ডাক্তারের নিজের বলে আগেই মেলানো) ল্যাব অর্ডার, নতুন আগে।
 * প্রেসক্রিপশন ধরে খোঁজা, তারপর অর্ডার আর অনুরোধ দুটোরই doctorUid মিলিয়ে — অন্য ডাক্তারের কিছু কখনো আসে না।
 */
async function labsForDoctor(doctorUid, rxIds) {
  const ids = [...new Set((rxIds || []).filter((id) => R.isValidId(id)))].slice(0, LAB_HISTORY_RX);
  if (!doctorUid || !ids.length) return [];
  const snaps = await Promise.all(ids.map((id) => db.collection('labOrders').where('rxId', '==', id).limit(30).get()));
  const docs = snaps.flatMap((s) => s.docs
    .filter((d) => d.get('doctorUid') === doctorUid)
    .sort((a, c) => (c.get('createdAt') || 0) - (a.get('createdAt') || 0))
    .slice(0, LAB_PER_RX));
  if (!docs.length) return [];
  const reviews = await db.getAll(...docs.map((d) => db.doc(`rxReviews/${d.id}`)));
  return docs
    .map((d, i) => doctorLabView(d, reviews[i] && reviews[i].exists && reviews[i].get('doctorUid') === doctorUid ? reviews[i] : null))
    .sort((a, c) => c.createdAt - a.createdAt);
}

// ---------------------------------------------------------------------------
// ফার্মেসির অর্ডার (দোকানে গিয়ে / অনলাইনে) আর "দিতে পারেননি" তালিকা
// ---------------------------------------------------------------------------

/** চালু অর্ডার: পাঠানো, তৈরি (দোকানে), নেওয়া হয়েছে / পথে (অনলাইনে) */
const OPEN_ORDER = ['sent', 'ready', 'accepted', 'out'];
const SHOP_CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const SHOP_CODE_RE = /^[A-HJ-NP-Z2-9]{6}$/;

function onlineOf(x) {
  const o = (x && x.online) || {};
  return { enabled: o.enabled === true, radiusKm: Number.isInteger(o.radiusKm) ? o.radiusKm : 3, fee: o.fee || '', note: o.note || '' };
}

// ---------------------------------------------------------------------------
// অনলাইন অর্ডারের ডেলিভারির ঠিকানা-ফোন কতদিন থাকে (১৮ অক্টোবর ২০২৬, গোপনীয়তা)
//  - অর্ডার বন্ধ (দেওয়া হয়েছে / বাতিল / ফিরিয়ে নেওয়া) হলে closedAt, আর ঠিকানা-ফোন থাকলে purgeContactAt = ৭ দিন পর;
//    দৈনিক ক্রন (purgeClosedContacts) purgeContactAt পেরোনোগুলো থেকে ঠিকানা-ফোন মোছে, চিহ্নটাও সরায় — তাই প্রশ্ন সস্তা
//  - ফার্মেসি সরালে / মালিকের অ্যাকাউন্ট মুছলে সেই দোকানের সব অর্ডার থেকে এখনই (stripOrderContacts)
//  রোগীর নিজের অর্ডারের ইতিহাস (rx_activity) ঠিকানা দেখায় না, তাই কিছু ভাঙে না; দোকানও শুধু চালু অর্ডারে দেখে।
// ---------------------------------------------------------------------------
const CONTACT_KEEP_MS = 7 * DAY_MS;
const CONTACT_STRIP = () => ({ address: FieldValue.delete(), phone: FieldValue.delete(), purgeContactAt: FieldValue.delete() });

/** বন্ধ হওয়ার সময় অর্ডারে যোগ হয়: closedAt, আর ঠিকানা-ফোন থাকলে কবে মুছবে */
function closeMarks(x, now = Date.now()) {
  return { closedAt: now, ...(x && (x.address || x.phone) ? { purgeContactAt: now + CONTACT_KEEP_MS } : {}) };
}

/** পাতায় পাতায় (৩০০) পড়ে যেগুলোতে ঠিকানা-ফোন আছে সেগুলো থেকে মোছা; cursor: true হলে পরের পাতা startAfter দিয়ে */
async function stripPages(q, { cursor = true, maxPages = 500 } = {}) {
  const PAGE = 300;
  let n = 0;
  let last = null;
  for (let page = 0; page < maxPages; page++) {
    const s = await (cursor && last ? q.startAfter(last) : q).limit(PAGE).get();
    const hit = s.docs.filter((d) => d.get('address') || d.get('phone') || d.get('purgeContactAt') != null);
    if (hit.length) {
      const upd = () => ({ ...CONTACT_STRIP(), contactRemovedAt: Date.now() });
      const b = db.batch();
      hit.forEach((d) => b.update(d.ref, upd()));
      // মাঝে কোনো অর্ডার মুছে গেলে (যেমন একই অ্যাকাউন্ট মোছায় রোগীর নিজের অর্ডার) পুরো batch আটকায় — তখন একটা একটা করে
      await b.commit().catch(() => Promise.all(hit.map((d) => d.ref.update(upd()).catch(() => {}))));
      n += hit.length;
    }
    if (s.size < PAGE) break;
    last = s.docs[s.docs.length - 1];
  }
  return n;
}

/** ফার্মেসি সরালে / মালিকের অ্যাকাউন্ট মুছলে: সেই দোকানের সব অর্ডার থেকে ঠিকানা-ফোন, শেষ পর্যন্ত */
async function stripOrderContacts(pharmacyId) {
  if (!R.isValidId(pharmacyId)) return 0;
  return stripPages(db.collection('rxOrders').where('pharmacyId', '==', pharmacyId));
}

/**
 * দৈনিক ক্রন: বন্ধ হওয়ার ৭ দিন পেরোনো অর্ডারের ঠিকানা-ফোন মোছা। মোছার সাথে purgeContactAt সরে যায়,
 * তাই প্রতিবার একই প্রশ্নের প্রথম পাতাই নতুন (cursor লাগে না); একবারে সর্বোচ্চ ২০ পাতা, বাকি পরের দিন।
 */
async function purgeClosedContacts(now = Date.now()) {
  return stripPages(db.collection('rxOrders').where('purgeContactAt', '<=', now), { cursor: false, maxPages: 20 });
}

/**
 * এই নিয়মের আগে বন্ধ হওয়া অনলাইন অর্ডার (purgeContactAt নেই): একবারই সব অনলাইন অর্ডার পাতায় পাতায় দেখা —
 * বন্ধ আর ৭ দিন পেরোনো হলে এখনই মোছা, ৭ দিন না হলে purgeContactAt বসানো। অগ্রগতি maintenance/rxOrderContacts-এ;
 * শেষ হলে done, তারপর প্রতিদিন শুধু একটা ডক পড়া।
 */
async function backfillClosedContacts(now = Date.now(), maxPages = 20) {
  const ref = db.doc('maintenance/rxOrderContacts');
  const st = await ref.get();
  if (st.exists && st.get('done')) return { done: true, stripped: 0, marked: 0 };
  const PAGE = 300;
  const q = db.collection('rxOrders').where('mode', '==', 'online');
  let last = null;
  const lastPath = st.exists ? st.get('last') : null;
  if (lastPath) {
    const ls = await db.doc(lastPath).get();
    // কার্সরের অর্ডার মুছে গেলে শুরু থেকে আবার (কাজটা একই অর্ডারে দুবার হলেও ক্ষতি নেই)
    if (ls.exists) last = ls;
  }
  let stripped = 0;
  let marked = 0;
  let done = false;
  for (let page = 0; page < maxPages; page++) {
    const s = await (last ? q.startAfter(last) : q).limit(PAGE).get();
    const b = db.batch();
    let ops = 0;
    for (const d of s.docs) {
      const x = d.data();
      if (OPEN_ORDER.includes(x.status) || !(x.address || x.phone) || x.purgeContactAt != null) continue;
      const closed = x.closedAt || x.doneAt || x.updatedAt || x.createdAt || 0;
      if (closed + CONTACT_KEEP_MS <= now) { b.update(d.ref, { ...CONTACT_STRIP(), contactRemovedAt: now }); stripped++; }
      else { b.update(d.ref, { closedAt: closed, purgeContactAt: closed + CONTACT_KEEP_MS }); marked++; }
      ops++;
    }
    if (ops) await b.commit();
    if (s.size < PAGE) { done = true; break; }
    last = s.docs[s.docs.length - 1];
  }
  await ref.set(done ? { done: true, doneAt: now } : { last: last ? last.ref.path : null, updatedAt: now }, { merge: true });
  return { done, stripped, marked };
}

async function uniqueShopCode() {
  const crypto = require('crypto');
  for (let i = 0; i < 6; i++) {
    const c = [...crypto.randomBytes(6)].map((b) => SHOP_CODE_CHARS[b % SHOP_CODE_CHARS.length]).join('');
    const s = await db.collection('pharmacies').where('shopCode', '==', c).limit(1).get();
    if (s.empty) return c;
  }
  throw new Error('shop code collision');
}

/** প্রেসক্রিপশনের ক্রম (i) → ওষুধের নাম */
async function missingNames(rxId, missing) {
  const idx = (Array.isArray(missing) ? missing : []).filter((i) => Number.isInteger(i) && i >= 0 && i < 60);
  if (!idx.length || !R.isValidId(rxId)) return [];
  const s = await db.doc(`prescriptions/${rxId}`).get();
  const meds = s.exists ? s.get('medicines') || [] : [];
  return [...new Set(idx.map((i) => meds[i]?.name).filter(Boolean))];
}

const missKey = (name) => `k_${require('crypto').createHash('sha1').update(key(name)).digest('hex').slice(0, 16)}`;

/** দোকান যে ওষুধ চেয়েও দিতে পারেনি — কতবার, শেষ কবে (দোকান বুঝবে কী রাখা দরকার) */
async function addMissed(pharmacyId, names) {
  const list = [...new Set((names || []).filter(Boolean))];
  if (!list.length || !R.isValidId(pharmacyId)) return;
  const ref = db.doc(`pharmacyMissed/${pharmacyId}`);
  try {
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const items = (s.exists && s.get('items')) || {};
      const now = Date.now();
      for (const n of list) {
        const k = missKey(n);
        const cur = items[k] || { name: n, count: 0 };
        items[k] = { name: cur.name || n, count: (cur.count || 0) + 1, lastAt: now };
      }
      const keys = Object.keys(items);
      if (keys.length > 500) keys.sort((a, c) => items[a].lastAt - items[c].lastAt).slice(0, keys.length - 500).forEach((k) => delete items[k]);
      t.set(ref, { items, updatedAt: now });
    });
  } catch (e) {
    console.error('missed log failed', e.message);
  }
}

/** রোগীর নিজের, এখনো 'requested' (প্যাথলজি সময় জানায়নি) বাসা থেকে নমুনার অনুরোধ */
async function myHomeRequest(req, user) {
  const { id } = req.body || {};
  if (!R.isValidId(id)) throw notFound(req);
  const ref = db.doc(`labOrders/${id}`);
  const s = await ref.get();
  if (!s.exists || s.get('patientUid') !== user.uid || !s.get('home')) throw notFound(req);
  if (s.get('status') !== 'sent' || (s.get('home.status') || 'requested') !== 'requested') {
    throw err(req, 'The lab has already answered — call the lab to change', 'প্যাথলজি এর মধ্যে জানিয়েছে — বদলাতে প্যাথলজিতে ফোন করুন', 409);
  }
  return { ref, s };
}

const actions = {
  // ======================= প্যানেলে প্যাথলজি অনুমোদন =======================
  async admin_labs(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw err(req, 'Admins only', 'শুধু অ্যাডমিন', 403);
    const status = ['pending', 'approved', 'suspended', 'rejected'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('labs').where('status', '==', status).limit(300).get();
    const LR = require('./listingreview');
    const { UPAZILA: U } = require('./hospital-match');
    return {
      items: await Promise.all(snap.docs.map(async (d) => ({
        ...labView(d, true),
        areaLabel: [U.get(d.get('upazilaId'))?.bn, d.get('district')].filter(Boolean).join(', '),
        ownerUid: d.id,
        createdAt: d.get('createdAt') || null,
        reviewedAt: d.get('reviewedAt') || null,
        changes: LR.changedFields(d.data()),
        ...(['pending', 'rejected'].includes(status) ? { verify: await require('./verify').panelInfo('lab', d.id, d.data()).catch(() => null) } : {}),
        verification: d.get('verification') || null,
      }))),
    };
  },

  async admin_lab_resolve(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw err(req, 'Admins only', 'শুধু অ্যাডমিন', 403);
    return require('./listingreview').resolveListing(req, user, 'lab', 'labs', req.body?.id);
  },

  // ======================= ধাপ ২: ফার্মেসি =======================
  /** ফার্মেসি: কোড দিয়ে প্রেসক্রিপশন খোলা */
  async rx_pharmacy_open(req, user) {
    await myPharmacy(req, user);
    await useQuota(req, user.uid, 'rxPharm');
    const d = await rxByCode(req, req.body?.code, user.uid);
    return { item: pharmacyRxView(d) };
  },

  /** ফার্মেসি: কত ওষুধ দিলেন (items: [{ i, qty }]) */
  async rx_dispense(req, user) {
    const shop = await myPharmacy(req, user);
    const d0 = await rxByCode(req, req.body?.code, user.uid);
    const raw = Array.isArray(req.body?.items) ? req.body.items : [];
    const now = Date.now();
    const out = await db.runTransaction(async (t) => {
      const s = await t.get(d0.ref);
      const x = s.data();
      if (x.status !== 'active') throw err(req, 'This prescription was cancelled. Do not dispense.', 'এই প্রেসক্রিপশন বাতিল। ওষুধ দেবেন না।', 409);
      const st = medStatus(x);
      const items = [];
      for (const it of raw) {
        if (!Number.isInteger(it?.i) || !st[it.i]) continue;
        const qty = Number.isInteger(it.qty) ? it.qty : 0;
        if (qty <= 0) continue;
        if (qty > 1000) throw err(req, 'Check the quantity', 'পরিমাণ দেখে নিন');
        const m = st[it.i];
        if (m.left != null && qty > m.left) {
          throw err(req, `${m.name}: only ${m.left} left on this prescription`, `${m.name}: এই প্রেসক্রিপশনে আর ${m.left}টা বাকি`, 409);
        }
        if (m.left == null && m.done) throw err(req, `${m.name} was already given`, `${m.name} আগেই দেওয়া হয়েছে`, 409);
        items.push({ i: it.i, qty });
      }
      const marked = Array.isArray(req.body?.missing) && req.body.missing.some((i) => Number.isInteger(i) && st[i]);
      if (!items.length && !marked) throw err(req, 'Enter how many you gave', 'কয়টা দিলেন লিখুন');
      if (!items.length) return { next: x, x, none: true };
      const dispensed = [...(x.dispensed || []), { by: user.uid, name: shop.get('name') || '', at: now, items }].slice(-30);
      const next = { ...x, dispensed };
      t.update(d0.ref, { dispensed, refillAt: refillDate(next), refillNotifiedAt: FieldValue.delete() });
      return { next, x };
    });
    // "নেই" চিহ্ন দেওয়া ওষুধ দোকানের "দিতে পারেননি" তালিকায়
    const missed = await missingNames(d0.id, req.body?.missing);
    // এই দোকানে পাঠানো খোলা অনুরোধ থাকলে সম্পন্ন
    const orders = await db.collection('rxOrders').where('rxId', '==', d0.id).limit(50).get();
    let logged = false;
    for (const o of orders.docs) {
      if (o.get('pharmacyId') !== user.uid || !OPEN_ORDER.includes(o.get('status'))) continue;
      const added = missed.filter((n) => !(o.get('missed') || []).includes(n));
      const upd = { ...(out.none ? {} : { status: 'done', doneAt: now, ...closeMarks(o.data(), now) }), ...(added.length ? { missed: [...(o.get('missed') || []), ...added].slice(0, 30) } : {}) };
      if (Object.keys(upd).length) await o.ref.update(upd);
      if (!logged) await addMissed(user.uid, added);
      logged = true;
    }
    if (!logged) await addMissed(user.uid, missed);
    if (out.x.patientUid && out.none && missed.length) {
      await pushPatient(out.x.patientUid, `${shop.get('name') || ''} doesn't have some medicines`, `${shop.get('name') || ''}-এ কিছু ওষুধ নেই`, missed.join(', '), { type: 'rx', id: d0.id });
    } else if (out.x.patientUid && !out.none) {
      await pushPatient(out.x.patientUid, 'Medicines given from your prescription', 'প্রেসক্রিপশনের ওষুধ দেওয়া হয়েছে', shop.get('name') || '', { type: 'rx', id: d0.id });
    }
    return { item: pharmacyRxView({ id: d0.id, data: () => out.next }) };
  },

  /**
   * রোগী: কাছের ফার্মেসি — mode 'shop' (দোকানে দাঁড়িয়ে: সবচেয়ে কাছেরটা ওপরে),
   * 'online' (শুধু যাঁরা অনলাইনে বিক্রি করেন, ডেলিভারির দূরত্বের মধ্যে; যেখানে বেশি ওষুধ আছে আগে)।
   * mode না দিলে (পুরোনো অ্যাপ): স্টক অনুযায়ী।
   */
  async rx_pharmacy_options(req, user) {
    const b = req.body || {};
    const s = await db.doc(`prescriptions/${b.rxId}`).get().catch(() => null);
    if (!R.isValidId(b.rxId) || !s || !s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const mode = ['shop', 'online'].includes(b.mode) ? b.mode : null;
    // দোকানের নাম দিয়ে খোঁজা (দোকানে দাঁড়িয়ে) — দূরত্বের সীমা ছাড়া পুরো জেলায়
    const q = typeof b.q === 'string' ? b.q.trim().toLowerCase().slice(0, 60) : '';
    // কেনা বাকি ওষুধ: কতটা বাকি (left), গোনা যায় কি না — আনুমানিক খরচের জন্য
    const wanted = medStatus(s.data()).filter((m) => !m.done).map((m) => ({ name: m.name, k: key(m.name), left: m.left, countable: countable(m) }));
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const hasLoc = typeof b.lat === 'number' && typeof b.lng === 'number';
    // placeId: দোকানের নিজের পাতা থেকে পাঠানো — শুধু সেই দোকান (দূরে বা অন্য উপজেলায় হলেও; একই জেলা আর একই ডেমো/আসল জগৎ)
    const placeId = R.isValidId(b.placeId) ? b.placeId : null;
    // ডেমো: ডেমো রোগী শুধু ডেমো দোকান, আসল রোগী শুধু আসল দোকান (ডেমো মুছলে filter অংশ মুছবেন)
    const docs = (placeId ? await onePlace('pharmacies', placeId, b.district) : await require('./pharmacy').areaPharmacies(b.district, upazilaId))
      .filter((d) => require('./demo').sameWorld(user.uid, d.id));
    const near = docs.map((d) => {
      const x = d.data();
      const km = hasLoc && typeof x.lat === 'number' ? Math.round(R.distanceKm(b.lat, b.lng, x.lat, x.lng) * 10) / 10 : null;
      return { d, x, km, on: onlineOf(x) };
    }).filter((p) => {
      if (placeId) return true;
      if (q) return String(p.x.name || '').toLowerCase().includes(q) && (mode !== 'online' || p.on.enabled);
      if (mode === 'online') {
        if (!p.on.enabled) return false;
        return p.km != null ? p.km <= p.on.radiusKm : !upazilaId || p.x.upazilaId === upazilaId;
      }
      return !upazilaId || p.x.upazilaId === upazilaId || (p.km != null && p.km <= K.MED_RADIUS_KM);
    }).sort((a, c) => (a.km ?? 99) - (c.km ?? 99)).slice(0, 40);
    const stocks = near.length ? await db.getAll(...near.map((p) => db.doc(`pharmacyStock/${p.d.id}`))) : [];
    const items = near.map((p, i) => {
      const keys = new Set(stocks[i].exists ? stocks[i].get('keys') || [] : []);
      const has = wanted.filter((w) => keys.has(w.k));
      const prices = stocks[i].exists ? stocks[i].get('prices') || null : null;
      return {
        id: p.d.id,
        name: p.x.name,
        phone: K.fixPhone(p.x.phone),
        address: p.x.address || '',
        km: p.km,
        openNow: !((p.x.closedUntil || 0) > Date.now()) && K.pharmacyOpenNow(p.x),
        keepsStock: stocks[i].exists && keys.size > 0,
        have: has.length,
        total: wanted.length,
        missing: wanted.filter((w) => !keys.has(w.k)).map((w) => w.name),
        online: p.on.enabled,
        deliveryFee: p.on.fee,
        onlineNote: p.on.note,
        estimate: pharmacyEstimate(wanted, keys, prices),
      };
    });
    if (mode === 'shop') items.sort((a, c) => (a.km ?? 99) - (c.km ?? 99));
    else items.sort((a, c) => (c.have - a.have) || (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)));
    return { items: items.slice(0, 30) };
  },

  /** রোগী: দোকানের কোড দিয়ে দোকান খোঁজা (দোকানে দাঁড়িয়ে, কাউন্টারে লেখা কোড) */
  async rx_shop_find(req, user) {
    const c = String(req.body?.code || '').trim().toUpperCase().replace(/[\s-]/g, '');
    if (!SHOP_CODE_RE.test(c)) throw err(req, 'Check the shop code', 'দোকানের কোডটা দেখে লিখুন');
    await useQuota(req, user.uid, 'medTry');
    const snap = await db.collection('pharmacies').where('shopCode', '==', c).limit(1).get();
    const d = snap.docs[0];
    if (!d || d.get('status') !== 'approved' || !require('./demo').sameWorld(user.uid, d.id)) throw err(req, 'No pharmacy with this code', 'এই কোডের কোনো ফার্মেসি নেই', 404); // ডেমো: আলাদা
    const on = onlineOf(d.data());
    return { item: { id: d.id, name: d.get('name'), address: d.get('address') || '', phone: K.fixPhone(d.get('phone')), online: on.enabled, deliveryFee: on.fee } };
  },

  /**
   * রোগী: প্রেসক্রিপশন একটা ফার্মেসিতে পাঠানো।
   * mode 'shop' — দোকানে গিয়ে কিনছেন: দোকান সাথে সাথে দেখে, যা দিল লেখে।
   * mode 'online' — ডেলিভারি অর্ডার (শুধু অনলাইনে বিক্রি করা দোকান; দাম দোকানে বা ক্যাশ অন ডেলিভারিতে — Lifolo টাকা ধরে না)।
   */
  async rx_send_pharmacy(req, user) {
    const { rxId, pharmacyId } = req.body || {};
    if (!R.isValidId(rxId) || !R.isValidId(pharmacyId)) throw notFound(req);
    const mode = req.body?.mode === 'online' ? 'online' : 'shop';
    const [s, p] = await Promise.all([db.doc(`prescriptions/${rxId}`).get(), db.doc(`pharmacies/${pharmacyId}`).get()]);
    if (!s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    if (s.get('status') !== 'active') throw err(req, 'This prescription was cancelled', 'এই প্রেসক্রিপশন বাতিল', 409);
    if (!p.exists || p.get('status') !== 'approved' || !require('./demo').sameWorld(user.uid, pharmacyId)) throw err(req, 'This pharmacy is not available', 'এই ফার্মেসি এখন নেই', 404); // ডেমো: আলাদা
    if (!medStatus(s.data()).some((m) => !m.done)) throw err(req, 'All medicines on this prescription were already bought', 'এই প্রেসক্রিপশনের সব ওষুধ কেনা হয়ে গেছে', 409);
    let delivery = {};
    if (mode === 'online') {
      const on = onlineOf(p.data());
      if (!on.enabled) throw err(req, "This pharmacy doesn't take online orders", 'এই ফার্মেসি অনলাইনে অর্ডার নেয় না', 409);
      const address = C.cleanText(req.body?.address, 200);
      if (!address) throw err(req, 'Write the delivery address', 'কোথায় পৌঁছাবে, ঠিকানা লিখুন');
      const ph = K.normalizeBd(req.body?.phone);
      if (!ph) throw err(req, 'Enter a mobile number for the delivery', 'ডেলিভারির জন্য মোবাইল নম্বর দিন');
      delivery = { address, phone: K.localBd(ph), deliveryFee: on.fee };
    }
    const open = await db.collection('rxOrders').where('rxId', '==', rxId).limit(50).get();
    if (open.docs.some((o) => o.get('pharmacyId') === pharmacyId && OPEN_ORDER.includes(o.get('status')))) {
      throw err(req, 'Already sent to this pharmacy', 'এই ফার্মেসিতে আগেই পাঠানো আছে', 409);
    }
    await useQuota(req, user.uid, 'rxSend');
    const x = s.data();
    const ref = await db.collection('rxOrders').add({
      rxId, code: x.code, pharmacyId, pharmacyName: p.get('name') || '', patientUid: user.uid,
      patientName: x.patient?.name || '', status: 'sent', mode, ...delivery, note: str(req.body?.note, 200), createdAt: Date.now(),
      ...(user.actingBy ? { byCaretaker: user.actingBy } : {}),
    });
    await push(pharmacyId,
      mode === 'online' ? 'New online order' : 'A customer sent a prescription to your shop',
      mode === 'online' ? 'নতুন অনলাইন অর্ডার' : 'দোকানে একজন প্রেসক্রিপশন পাঠিয়েছেন',
      x.patient?.name || '', { type: 'rx_pharmacy', id: ref.id });
    return { id: ref.id };
  },

  /**
   * রোগী: ফিরিয়ে নেওয়া (দোকান সব ওষুধ দিতে পারেনি, বা অন্য কারণে) — বাকি ওষুধ অন্য দোকানে নেওয়া যায়।
   * reason 'not_available' হলে যে ওষুধগুলো তখনো কেনা বাকি, সেগুলো ওই দোকানের "দিতে পারেননি" তালিকায় জমা হয়।
   */
  async rx_order_withdraw(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`rxOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    if (!['sent', 'ready', 'accepted'].includes(s.get('status'))) throw err(req, "This can't be taken back now", 'এখন আর ফিরিয়ে নেওয়া যায় না', 409);
    const notAvailable = req.body?.reason === 'not_available';
    let added = [];
    if (notAvailable) {
      const rx = await db.doc(`prescriptions/${s.get('rxId')}`).get();
      const left = rx.exists ? medStatus(rx.data()).filter((m) => !m.done).map((m) => m.name) : [];
      const already = new Set(s.get('missed') || []);
      added = left.filter((n) => !already.has(n));
    }
    await ref.update({
      status: 'withdrawn', withdrawReason: notAvailable ? 'not_available' : 'other',
      ...(added.length ? { missed: [...(s.get('missed') || []), ...added].slice(0, 30) } : {}), updatedAt: Date.now(), ...closeMarks(s.data()),
    });
    await addMissed(s.get('pharmacyId'), added);
    await push(s.get('pharmacyId'), 'The customer took back the prescription', 'ক্রেতা প্রেসক্রিপশন ফিরিয়ে নিয়েছেন', s.get('patientName') || '', { type: 'rx_pharmacy', id });
    return { ok: true };
  },

  /** ফার্মেসি: আসা প্রেসক্রিপশন (দোকানে এসেছেন আর অনলাইন অর্ডার) */
  async rx_orders_pharmacy(req, user) {
    await myPharmacy(req, user);
    const snap = await db.collection('rxOrders').where('pharmacyId', '==', user.uid).limit(300).get();
    const items = snap.docs.map((d) => ({ id: d.id, ...d.data() })).sort((a, c) => c.createdAt - a.createdAt).slice(0, 100)
      .map((o) => ({
        id: o.id, rxId: o.rxId, code: o.code, patientName: o.patientName, note: o.note || '', status: o.status, createdAt: o.createdAt,
        mode: o.mode || 'shop', price: o.price || '', eta: o.eta || '', deliveryFee: o.deliveryFee || '', missed: o.missed || [],
        // ঠিকানা-ফোন শুধু চালু অনলাইন অর্ডারে
        ...(o.mode === 'online' && OPEN_ORDER.includes(o.status) ? { address: o.address || '', phone: o.phone || '' } : {}),
      }));
    return { items };
  },

  /**
   * ফার্মেসি: দোকানে — 'ready' (তৈরি) / 'cancelled'; অনলাইনে — 'accepted' (দাম, কখন পৌঁছাবে) / 'out' (পথে) / 'cancelled'।
   * দেওয়া হলে "ওষুধ দিলাম" (rx_dispense) দিয়ে অর্ডার সম্পন্ন। missing: যে ওষুধ নেই (প্রেসক্রিপশনের ক্রম)।
   */
  async rx_order_update(req, user) {
    await myPharmacy(req, user);
    const { id, status } = req.body || {};
    if (!R.isValidId(id) || !['ready', 'accepted', 'out', 'cancelled'].includes(status)) throw notFound(req);
    const ref = db.doc(`rxOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('pharmacyId') !== user.uid) throw notFound(req);
    const mode = s.get('mode') || 'shop';
    const cur = s.get('status');
    const allowed = {
      ready: mode === 'shop' && cur === 'sent',
      accepted: mode === 'online' && cur === 'sent',
      out: mode === 'online' && ['sent', 'accepted'].includes(cur),
      cancelled: OPEN_ORDER.includes(cur),
    };
    if (!allowed[status]) throw err(req, "This can't be changed now", 'এখন এটা বদলানো যায় না', 409);
    const reason = str(req.body?.reason, 200);
    const missedNames = await missingNames(s.get('rxId'), req.body?.missing);
    const update = { status, updatedAt: Date.now() };
    if (status === 'cancelled') Object.assign(update, { reason }, closeMarks(s.data(), update.updatedAt));
    if (status === 'accepted') {
      update.price = str(req.body?.price, 40);
      update.eta = str(req.body?.eta, 60);
    }
    const added = missedNames.filter((n) => !(s.get('missed') || []).includes(n));
    if (added.length) update.missed = [...(s.get('missed') || []), ...added].slice(0, 30);
    await ref.update(update);
    await addMissed(user.uid, added);
    const shop = s.get('pharmacyName');
    const T = {
      ready: [`${shop}: your medicines are ready`, `${shop}: আপনার ওষুধ তৈরি`],
      accepted: [`${shop} accepted your order`, `${shop} অর্ডার নিয়েছে`],
      out: [`${shop}: your medicines are on the way`, `${shop}: ওষুধ পথে আছে`],
      cancelled: [`${shop} can't provide your medicines`, `${shop} ওষুধ দিতে পারবে না`],
    }[status];
    const body = status === 'accepted' ? [update.price, update.eta].filter(Boolean).join(' · ')
      : status === 'cancelled' ? [reason, added.length ? `নেই: ${added.join(', ')}` : ''].filter(Boolean).join(' · ') : '';
    await pushPatient(s.get('patientUid'), T[0], T[1], body, { type: 'rx', id: s.get('rxId') });
    return { ok: true };
  },

  /** ফার্মেসি: দোকানের কোড, অনলাইন বিক্রির সেটিং আর "দিতে পারেননি" তালিকা */
  async rx_pharmacy_home(req, user) {
    const shop = await myPharmacy(req, user);
    let code = shop.get('shopCode');
    if (!code) {
      code = await uniqueShopCode();
      await shop.ref.update({ shopCode: code });
    }
    const m = await db.doc(`pharmacyMissed/${user.uid}`).get();
    const items = (m.exists && m.get('items')) || {};
    return {
      shopCode: code,
      online: onlineOf(shop.data()),
      hasLocation: typeof shop.get('lat') === 'number',
      missed: Object.entries(items).map(([k, v]) => ({ k, name: v.name, count: v.count || 0, lastAt: v.lastAt || 0 }))
        .sort((a, c) => (c.count - a.count) || (c.lastAt - a.lastAt)).slice(0, 200),
    };
  },

  /** ফার্মেসি: অনলাইনে বিক্রি চালু/বন্ধ — ডেলিভারির দূরত্ব, চার্জ, নোট */
  async rx_pharmacy_online_set(req, user) {
    const shop = await myPharmacy(req, user);
    const b = req.body || {};
    const enabled = b.enabled === true;
    if (enabled && typeof shop.get('lat') !== 'number') throw err(req, 'Set the shop location first (edit your pharmacy)', 'আগে দোকানের লোকেশন দিন (ফার্মেসির তথ্য বদলে)');
    const radiusKm = Number.isInteger(b.radiusKm) && b.radiusKm >= 1 && b.radiusKm <= 30 ? b.radiusKm : 3;
    const online = { enabled, radiusKm, fee: str(b.fee, 40), note: str(b.note, 120), updatedAt: Date.now() };
    await shop.ref.update({ online });
    require('./memcache').clear('pharm:');
    return { online: onlineOf({ online }) };
  },

  /** ফার্মেসি: "দিতে পারেননি" তালিকা থেকে সরানো (স্টকে আনলে) — k না দিলে সব */
  async rx_missed_clear(req, user) {
    await myPharmacy(req, user);
    const ref = db.doc(`pharmacyMissed/${user.uid}`);
    const k = typeof req.body?.k === 'string' ? req.body.k : null;
    if (!k) {
      await ref.delete();
      return { ok: true };
    }
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) return;
      const items = s.get('items') || {};
      delete items[k];
      t.set(ref, { items, updatedAt: Date.now() });
    });
    return { ok: true };
  },

  // ======================= ধাপ ৪: স্টক =======================
  async stock_get(req, user) {
    await myPharmacy(req, user);
    const s = await db.doc(`pharmacyStock/${user.uid}`).get();
    return { items: (s.exists && s.get('names')) || [], updatedAt: (s.exists && s.get('updatedAt')) || 0 };
  },

  /** add / remove: নামের তালিকা (সর্বোচ্চ ৩০০০) */
  async stock_set(req, user) {
    await myPharmacy(req, user);
    const add = (Array.isArray(req.body?.add) ? req.body.add : []).map((n) => str(n, 100)).filter(Boolean).slice(0, 3000);
    const remove = new Set((Array.isArray(req.body?.remove) ? req.body.remove : []).map(key));
    const ref = db.doc(`pharmacyStock/${user.uid}`);
    const names = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const cur = (s.exists && s.get('names')) || [];
      const map = new Map(cur.map((n) => [key(n), n]));
      for (const n of add) if (!map.has(key(n))) map.set(key(n), n);
      for (const k of remove) map.delete(k);
      if (map.size > 3000) throw err(req, 'Up to 3000 medicines', 'সর্বোচ্চ ৩০০০টা ওষুধ');
      const list = [...map.values()].sort((a, b) => a.localeCompare(b));
      // হিসাব-খাতার পণ্যের দাম (shopbooks.js, রোগীর আনুমানিক খরচ) যেমন আছে তেমন থাকে
      const keep = s.exists ? { ...(s.get('prices') ? { prices: s.get('prices') } : {}), ...(s.get('pricesAll') ? { pricesAll: true } : {}) } : {};
      t.set(ref, { names: list, keys: list.map(key), ...keep, updatedAt: Date.now() });
      return list;
    });
    return { items: names };
  },

  // ======================= ধাপ ৩: প্যাথলজি =======================
  async lab_me(req, user) {
    const s = await db.doc(`labs/${user.uid}`).get();
    if (!s.exists) return { lab: null };
    const v = labView(s, true);
    const x = s.data();
    const docs = ['pending', 'rejected'].includes(x.status) || x.docsRequestedAt
      ? await require('./verify').ownerInfo('lab', s.id, x).catch(() => ({})) : {};
    return { lab: { ...v, ...docs, docsRequestNote: x.docsRequestNote || '' } };
  },

  /** নিবন্ধন/বদল: স্বাস্থ্য অধিদপ্তরের তালিকা থেকে সেন্টার (facilityId) বা নিজে লেখা; লাইসেন্স; কাগজের ছবি */
  async lab_save(req, user) {
    const b = req.body || {};
    const ref = db.doc(`labs/${user.uid}`);
    const s = await ref.get();
    let fac = null;
    if (typeof b.facilityId === 'string' && b.facilityId) {
      fac = await require('./hospitals').findHospital(b.facilityId);
      if (!fac) throw err(req, 'Choose the centre from the list again', 'তালিকা থেকে সেন্টারটা আবার বেছে নিন');
    }
    const name = C.cleanText(fac ? (fac.nameBn || fac.nameEn) : b.name, 120);
    if (!name) throw err(req, 'Write the centre name', 'সেন্টারের নাম লিখুন');
    const district = fac ? fac.district : b.district;
    if (!DISTRICTS.some((d) => d.bn === district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const upazilaId = fac ? fac.upazilaId || null : Number.isInteger(b.upazilaId) && UPAZILA.get(b.upazilaId)?.district === district ? b.upazilaId : null;
    const address = C.cleanText(b.address || (fac && fac.address) || '', 200);
    if (!address) throw err(req, 'Write the address', 'ঠিকানা লিখুন');
    const licenseNo = C.cleanText(b.licenseNo, 60);
    if (!licenseNo) throw err(req, 'Enter the DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর দিন');
    const phone = K.normalizeBd(b.phone);
    if (!phone) throw err(req, 'Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন');
    if (b.termsAccepted !== true) throw err(req, 'Please accept the terms', 'শর্তে সম্মতি দিন');
    const data = {
      name, facilityId: fac ? fac.id : null, district, upazilaId, address, licenseNo, phone: K.localBd(phone),
      hours: str(b.hours, 80), homeCollection: b.homeCollection === true, updatedAt: Date.now(),
    };
    const V = require('./verify');
    if (s.exists) {
      if (s.get('status') === 'suspended') throw err(req, 'Your lab is suspended. You can appeal from Profile → Help & appeals.', 'আপনার প্যাথলজি বন্ধ করা আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করুন।', 403);
      const { changed, approvedName } = K.reviewNeeded(s, data, ['licenseNo', 'phone', 'district', 'facilityId']);
      await V.saveDocs(req, 'lab', user.uid, user.uid, { ...s.data(), ...data }, b.docs, false);
      await ref.update({ ...data, ...(approvedName ? { approvedName } : {}), ...K.keepApproved(s, data, changed), ...(changed ? { status: 'pending' } : {}) });
      return { ok: true, pending: changed || s.get('status') === 'pending' };
    }
    V.validateDocs(req, 'lab', data, b.docs, true);
    await useQuota(req, user.uid, 'lab');
    const docsHave = await V.saveDocs(req, 'lab', user.uid, user.uid, data, b.docs, true);
    await ref.set({ ...data, ownerUid: user.uid, status: 'pending', docsHave: docsHave || {}, createdAt: Date.now() });
    return { ok: true, pending: true };
  },

  async lab_delete(req, user) {
    const ref = db.doc(`labs/${user.uid}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('status') === 'suspended') throw err(req, "A suspended lab can't be removed", 'বন্ধ করা প্যাথলজি সরানো যায় না', 403);
    await ref.delete();
    await require('./verify').deleteDocs('lab', user.uid, s.data()).catch(() => {});
    // দামের তালিকা, আর চালু অর্ডারে রোগীর বাসার ঠিকানা-ফোন (প্যাথলজি আর নেই — অর্ডার রোগীর কাছে থাকে)
    await Promise.all([db.doc(`labPrices/${user.uid}`).delete(), LP.stripHomes(user.uid, { openOnly: true })]);
    ['labs:', 'labprices:', 'guest:'].forEach((k) => require('./memcache').clear(k));
    return { ok: true };
  },

  /** এলাকার অনুমোদিত প্যাথলজি (সেবা ট্যাবের তালিকা) */
  async labs(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const docs = await areaLabs(b.district);
    // পরীক্ষা ও দামের সারাংশ (কয়টা পরীক্ষা, সবচেয়ে কম দাম, ছাড়, বাসার চার্জ) — তালিকা না থাকলে null;
    // পুরো তালিকা বিস্তারিত খুললে lab_prices_public (তালিকা হালকা থাকে)
    await LT.load();
    const pm = await LP.pricesFor(b.district, docs.map((d) => d.id));
    let items = docs.map((d) => ({ ...labView(d), prices: LP.publicSummary(pm.get(d.id), d.data()) }));
    if (upazilaId) items = [...items.filter((l) => l.upazilaId === upazilaId), ...items.filter((l) => l.upazilaId !== upazilaId)];
    if (b.facilityId) items.sort((a, c) => (c.facilityId === b.facilityId) - (a.facilityId === b.facilityId));
    return { items: items.slice(0, 100) };
  },

  /**
   * রোগী (বা কেয়ারগিভার): প্রেসক্রিপশনের পরীক্ষা কোন প্যাথলজিতে কয়টা হয়, আনুমানিক কত — tests: বাছা পরীক্ষা (না দিলে সব)।
   * সাজানো: ডাক্তারের বলা জায়গা (প্রেসক্রিপশনের testPlaceId) → যেখানে বেশি পরীক্ষা হয় → কম দাম → কাছে (একই উপজেলা / দূরত্ব)।
   * দামের তালিকা নেই এমন প্যাথলজিও আসে (priced: false)। দিনের সীমা rxPharm (চিপ বদলালে ক্লায়েন্ট একটু থেমে ডাকে)।
   * lastHome: শেষ বার বাসা থেকে নমুনার ঠিকানা-ফোন (serverState-এ রাখা — অর্ডার খুঁজে নয়), ফর্মে আগে থেকে বসাতে।
   */
  async rx_lab_options(req, user) {
    const b = req.body || {};
    if (!R.isValidId(b.rxId)) throw notFound(req);
    const s = await db.doc(`prescriptions/${b.rxId}`).get();
    if (!s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw err(req, 'Choose a district', 'জেলা বেছে নিন');
    await useQuota(req, user.uid, 'rxPharm');
    const all = s.get('tests') || [];
    const picked = (Array.isArray(b.tests) ? b.tests : all).filter((t) => all.includes(t));
    const wanted = picked.length ? picked : all;
    const upazilaId = Number.isInteger(b.upazilaId) ? b.upazilaId : null;
    const hasLoc = typeof b.lat === 'number' && typeof b.lng === 'number';
    const place = s.get('testPlaceId') || null;
    await LT.load();
    const total = LP.countTests(wanted);
    // placeId: প্যাথলজির নিজের পাতা থেকে পাঠানো — শুধু সেটা (জেলার তালিকার সীমার বাইরে হলেও), দাম সরাসরি পড়া
    // (pricesFor-এর জেলার ক্যাশে একটা প্যাথলজির দাম রাখলে অন্যদের তালিকা ভুল হতো)
    const placeId = R.isValidId(b.placeId) ? b.placeId : null;
    const area = placeId ? await onePlace('labs', placeId, b.district) : await areaLabs(b.district);
    const pm = placeId
      ? new Map(area.length ? [[placeId, await db.doc(`labPrices/${placeId}`).get().then((p) => (p.exists ? p.data() : null))]].filter(([, v]) => v) : [])
      : await LP.pricesFor(b.district, area.map((d) => d.id));
    // ডেমো: ডেমো রোগী শুধু ডেমো প্যাথলজি, আসল রোগী শুধু আসল (ডেমো মুছলে filter অংশ মুছবেন)
    const items = area.filter((d) => require('./demo').sameWorld(user.uid, d.id)).map((d) => {
      const x = d.data();
      const px = pm.get(d.id) || null;
      const e = LP.estimate(wanted, px, x);
      const km = hasLoc && typeof x.lat === 'number' && typeof x.lng === 'number' ? Math.round(R.distanceKm(b.lat, b.lng, x.lat, x.lng) * 10) / 10 : null;
      return {
        ...labView(d),
        suggested: !!place && x.facilityId === place,
        near: !!upazilaId && x.upazilaId === upazilaId,
        km,
        priced: !!e,
        have: e ? e.have : null,
        total: e ? e.total : total,
        missing: e ? e.missing : [],
        items: e ? e.items : [],
        subtotal: e ? e.subtotal : null,
        discountPct: e ? e.discountPct : null,
        estimate: e ? e.estimate : null,
        homeFee: x.homeCollection === true && px && Number.isInteger(px.homeFee) ? px.homeFee : null,
        tat: e ? e.tat : '',
      };
    });
    // ডাক্তারের বলা জায়গা সবার আগে (এই ব্রাঞ্চের আগে labs-এ যেমন ছিল);
    // দামের তালিকা না থাকলে কয়টা হয় জানা নেই — একটাও হয় না এমন প্যাথলজির আগে, অন্তত একটা হয় এমনটার পরে
    const rank = (o) => (o.priced ? o.have * 2 : 1);
    items.sort((a, c) => (Number(c.suggested) - Number(a.suggested))
      || (rank(c) - rank(a))
      || ((a.estimate ?? Infinity) - (c.estimate ?? Infinity))
      || (Number(c.near) - Number(a.near))
      || ((a.km ?? 999) - (c.km ?? 999)));
    return { items: items.slice(0, 60), lastHome: await LP.lastHome(user.uid) };
  },

  /** রোগী: প্রেসক্রিপশনের পরীক্ষা একটা প্যাথলজিতে পাঠানো (home: বাসা থেকে নমুনা — ঠিকানা, ফোন, দিন, সময়, নোট) */
  async rx_send_lab(req, user) {
    const { rxId, labId } = req.body || {};
    if (!R.isValidId(rxId) || !R.isValidId(labId)) throw notFound(req);
    const [s, l] = await Promise.all([db.doc(`prescriptions/${rxId}`).get(), db.doc(`labs/${labId}`).get()]);
    if (!s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    const x = s.data();
    if (x.status !== 'active') throw err(req, 'This prescription was cancelled', 'এই প্রেসক্রিপশন বাতিল', 409);
    if (!l.exists || l.get('status') !== 'approved' || !require('./demo').sameWorld(user.uid, labId)) throw err(req, 'This lab is not available', 'এই প্যাথলজি এখন নেই', 404); // ডেমো: আলাদা
    const tests = (Array.isArray(req.body?.tests) ? req.body.tests : x.tests || []).filter((t) => (x.tests || []).includes(t));
    if (!tests.length) throw err(req, 'Choose the tests', 'পরীক্ষা বেছে নিন');
    // বাসা থেকে নমুনা (চাইলে) — প্যাথলজি বাসা থেকে না নিলে নয়
    const home = LP.cleanHome(req, req.body?.home, l.data());
    const prev = await db.collection('labOrders').where('rxId', '==', rxId).limit(50).get();
    if (prev.docs.some((o) => o.get('labId') === labId && ['sent', 'received'].includes(o.get('status')))) {
      throw err(req, 'Already sent to this lab', 'এই প্যাথলজিতে আগেই পাঠানো আছে', 409);
    }
    await useQuota(req, user.uid, 'rxSend');
    // এখনকার দামের তালিকা থেকে হিসাব রেখে দেওয়া (পরে দাম বদলালেও এই অর্ডারে একই থাকে)
    await LT.load();
    const pd = await db.doc(`labPrices/${labId}`).get();
    const px = pd.exists ? pd.data() : null;
    const est = LP.estimate(tests, px, l.data());
    const ref = await db.collection('labOrders').add({
      rxId, code: x.code, labId, labName: l.get('name') || '', patientUid: user.uid, patientName: x.patient?.name || '',
      patientAge: x.patient?.age ?? null, patientGender: x.patient?.gender || '', doctorUid: x.doctorUid, doctorName: x.doctor?.name || '',
      tests, status: 'sent', createdAt: Date.now(),
      ...(LP.snapshot(est) ? { prices: LP.snapshot(est) } : {}),
      ...(home ? { home: { ...home, fee: px && Number.isInteger(px.homeFee) ? px.homeFee : null } } : {}),
      ...(user.actingBy ? { byCaretaker: user.actingBy } : {}),
    });
    if (home) await LP.rememberHome(user.uid, home);
    // পুরোনো অ্যাপের প্যাথলজি বাসার ঘর দেখে না — দরকারি কথা (দিন, সময়, এলাকা; ফোন নয়) নোটিফিকেশনেই
    const head = `${x.patient?.name || ''} · ${tests.join(', ')}`;
    const line = LP.homePushLine(home);
    await push(labId,
      home ? 'New test request — sample from home' : 'New test request',
      home ? 'নতুন পরীক্ষা — বাসা থেকে নমুনা' : 'নতুন পরীক্ষার অনুরোধ',
      line ? { en: `${line.en} · ${head}`, bn: `${line.bn} · ${head}` } : head, { type: 'rx_lab', id: ref.id });
    return { id: ref.id };
  },

  /** প্যাথলজি: আসা পরীক্ষা (ছবি ছাড়া) */
  async lab_orders(req, user) {
    await myLab(req, user);
    const snap = await db.collection('labOrders').where('labId', '==', user.uid).limit(400).get();
    return { items: snap.docs.map((d) => labOrderView(d, false, 'lab')).sort((a, c) => c.createdAt - a.createdAt).slice(0, 150) };
  },

  /** প্যাথলজি: কোড দিয়ে খোলা (রোগী অ্যাপে না পাঠিয়ে কাগজ নিয়ে এলে) — নতুন অনুরোধ হিসেবে যোগ */
  async lab_open_code(req, user) {
    const lab = await myLab(req, user);
    await useQuota(req, user.uid, 'rxPharm');
    const d = await rxByCode(req, req.body?.code, user.uid);
    const x = d.data();
    if (x.status !== 'active') throw err(req, 'This prescription was cancelled', 'এই প্রেসক্রিপশন বাতিল', 409);
    if (!(x.tests || []).length) throw err(req, 'No tests on this prescription', 'এই প্রেসক্রিপশনে কোনো পরীক্ষা নেই');
    const prev = await db.collection('labOrders').where('rxId', '==', d.id).limit(50).get();
    const mine = prev.docs.find((o) => o.get('labId') === user.uid && o.get('status') !== 'cancelled');
    if (mine) return { item: labOrderView(mine, false, 'lab') };
    // নিজের দামের তালিকা থেকে হিসাব (কাউন্টারে বলে দিতে)
    await LT.load();
    const pd = await db.doc(`labPrices/${user.uid}`).get();
    const est = LP.estimate(x.tests, pd.exists ? pd.data() : null, lab.data());
    const ref = await db.collection('labOrders').add({
      rxId: d.id, code: x.code, labId: user.uid, labName: lab.get('name') || '', patientUid: x.patientUid || null, patientName: x.patient?.name || '',
      patientAge: x.patient?.age ?? null, patientGender: x.patient?.gender || '', doctorUid: x.doctorUid, doctorName: x.doctor?.name || '',
      tests: x.tests, status: 'received', createdAt: Date.now(), walkIn: true,
      ...(LP.snapshot(est) ? { prices: LP.snapshot(est) } : {}),
    });
    return { item: labOrderView(await ref.get(), false, 'lab') };
  },

  async lab_order_status(req, user) {
    await myLab(req, user);
    const { id, status } = req.body || {};
    if (!R.isValidId(id) || !['received', 'cancelled'].includes(status)) throw notFound(req);
    const ref = db.doc(`labOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('labId') !== user.uid || s.get('status') === 'reported') throw notFound(req);
    // শুধু পাঠানো → নমুনা নেওয়া, আর পাঠানো/নমুনা নেওয়া → বাতিল (বাতিল আবার খোলা যায় না)
    const cur = s.get('status');
    const allowed = (status === 'received' && cur === 'sent') || (status === 'cancelled' && ['sent', 'received'].includes(cur));
    if (!allowed) throw err(req, "This request can't be changed now", 'এই অনুরোধ এখন আর বদলানো যায় না', 409);
    await ref.update({ status, updatedAt: Date.now() });
    if (status === 'cancelled') {
      await pushPatient(s.get('patientUid'), `${s.get('labName')} can't do your tests`, `${s.get('labName')} পরীক্ষা করতে পারবে না`, '', { type: 'rx', id: s.get('rxId') });
    }
    return { ok: true };
  },

  /**
   * প্যাথলজি: বাসা থেকে নমুনার সময় নিশ্চিত — date (দিন, UTC মধ্যরাত), time ("08:00"), note (ঐচ্ছিক)।
   * রোগী (আর কেয়ারগিভার) জানেন: "সিটি ডায়াগনস্টিক কাল সকাল ৮টায় আসবে"। নমুনা নেওয়ার আগে আবার বদলানো যায়।
   */
  async lab_home_confirm(req, user) {
    await myLab(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`labOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('labId') !== user.uid || !s.get('home')) throw notFound(req);
    if (s.get('status') !== 'sent' || s.get('home.status') === 'declined') throw err(req, "The home visit can't be changed now", 'এখন আর বাসায় যাওয়ার সময় বদলানো যায় না', 409);
    const v = LP.cleanVisit(req, req.body?.date, req.body?.time);
    const note = str(req.body?.note, 120);
    const now = Date.now();
    await ref.update({
      'home.status': 'confirmed', 'home.visitDate': v.visitDate, 'home.time': v.time, 'home.at': v.at,
      'home.confirmNote': note, 'home.confirmedAt': now, updatedAt: now,
    });
    const w = LP.visitWords(v.visitDate, v.time, now);
    const lab = s.get('labName') || '';
    await pushPatient(s.get('patientUid'), `${lab} will come ${w.en}`, `${lab} ${w.bn} আসবে`, note, { type: 'rx', id: s.get('rxId') });
    return { ok: true, at: v.at };
  },

  /** প্যাথলজি: বাসায় যেতে পারবে না — অর্ডার থাকে (রোগী প্যাথলজিতে এসে নমুনা দেবেন), রোগী জানেন */
  async lab_home_decline(req, user) {
    await myLab(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`labOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('labId') !== user.uid || !s.get('home')) throw notFound(req);
    if (s.get('status') !== 'sent' || s.get('home.status') === 'declined') throw err(req, "The home visit can't be changed now", 'এখন আর বাসায় যাওয়ার সময় বদলানো যায় না', 409);
    const note = str(req.body?.note, 120);
    const now = Date.now();
    // বাসায় যাওয়া হবে না, তাই রোগীর ঠিকানা-ফোন প্যাথলজির কাছে রাখার দরকার নেই — এখনই মোছা (দিন/সময়/অবস্থা ইতিহাসে থাকে)
    await ref.update({
      'home.status': 'declined', 'home.declineNote': note, 'home.declinedAt': now,
      'home.address': FieldValue.delete(), 'home.phone': FieldValue.delete(), updatedAt: now,
    });
    const lab = s.get('labName') || '';
    await pushPatient(s.get('patientUid'), `${lab} can't come to your home — give the sample at the lab`, `${lab} বাসায় আসতে পারবে না — প্যাথলজিতে গিয়ে নমুনা দিন`, note, { type: 'rx', id: s.get('rxId') });
    return { ok: true };
  },

  /**
   * রোগী (বা কেয়ারগিভার): বাসা থেকে নমুনার অনুরোধ বাতিল — প্যাথলজি সময় জানানোর আগে (চাওয়া সময় পেরিয়ে গেলেও)।
   * অর্ডার থাকে, সাধারণ পরীক্ষা হিসেবে (প্যাথলজিতে গিয়ে নমুনা); ঠিকানা-ফোন অর্ডার থেকে মোছে। প্যাথলজি জানে।
   */
  async lab_home_cancel(req, user) {
    const { ref, s } = await myHomeRequest(req, user);
    const now = Date.now();
    await ref.update({ home: FieldValue.delete(), homeCancelledAt: now, updatedAt: now });
    await push(s.get('labId'), 'No home visit — the patient will come to the lab', 'বাসায় যেতে হবে না — রোগী প্যাথলজিতে এসে নমুনা দেবেন',
      `${s.get('patientName') || ''} · ${(s.get('tests') || []).join(', ')}`, { type: 'rx_lab', id: s.id });
    return { ok: true };
  },

  /**
   * রোগী (বা কেয়ারগিভার): বাসা থেকে নমুনার দিন/সময়/ঠিকানা বদল — প্যাথলজি সময় জানানোর আগে।
   * home: rx_send_lab-এর মতো { address, phone, day, slot, note }। প্যাথলজি জানে (নতুন সময়, এলাকা)।
   */
  async lab_home_change(req, user) {
    const { ref, s } = await myHomeRequest(req, user);
    const l = await db.doc(`labs/${s.get('labId')}`).get();
    if (!l.exists || l.get('status') !== 'approved') throw err(req, 'This lab is not available', 'এই প্যাথলজি এখন নেই', 404);
    const home = LP.cleanHome(req, req.body?.home, l.data());
    if (!home) throw err(req, 'Choose a time', 'কোন সময়ে আসবে বেছে নিন');
    const fee = s.get('home.fee');
    const now = Date.now();
    await ref.update({ home: { ...home, fee: Number.isInteger(fee) ? fee : null }, updatedAt: now });
    await LP.rememberHome(user.uid, home);
    const line = LP.homePushLine(home, now);
    const head = `${s.get('patientName') || ''} · `;
    await push(s.get('labId'), 'The patient changed the home visit', 'রোগী বাসা থেকে নমুনার সময় বদলেছেন',
      { en: head + line.en, bn: head + line.bn }, { type: 'rx_lab', id: s.id });
    return { ok: true };
  },

  /**
   * প্যাথলজি: ফল দেওয়া — items: [{ test, result, unit, range, flag }], note, photos (ছোট JPEG base64, সর্বোচ্চ ২ — ডকের ১MB সীমা)।
   * রোগী পান; ডাক্তারের "রিপোর্ট দেখা"-র তালিকায় যায় (টাকা না মিললে বিষয়বস্তু দেখেন না)।
   */
  async lab_report(req, user) {
    await myLab(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`labOrders/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('labId') !== user.uid || s.get('status') === 'cancelled') throw notFound(req);
    const raw = Array.isArray(req.body?.items) ? req.body.items.slice(0, 120) : [];
    const n = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
    const items = raw.map((r) => {
      const low = n(r?.low);
      const high = n(r?.high);
      const result = str(r?.result, r?.long ? 2000 : 60);
      // ছকের সংখ্যার ঘরে বেশি/কম নিজে থেকে (মাত্রার সাথে মিলিয়ে); লেখার ঘরে ল্যাব যা দেয়
      const auto = low != null || high != null ? LT.flagOf(result, low, high) : null;
      const flag = auto != null && Number.isFinite(Number(String(result).replace(/,/g, ''))) ? auto : ['high', 'low', 'abnormal'].includes(r?.flag) ? r.flag : '';
      return {
        test: str(r?.test, 80), result, unit: str(r?.unit, 30), range: str(r?.range, 60), flag,
        ...(typeof r?.group === 'string' && r.group ? { group: str(r.group, 80) } : {}),
        ...(typeof r?.code === 'string' && /^[a-z0-9_]{1,40}$/.test(r.code) ? { code: r.code } : {}),
        ...(typeof r?.key === 'string' && /^[a-z0-9_]{1,30}$/.test(r.key) ? { key: r.key } : {}),
        ...(low != null ? { low } : {}), ...(high != null ? { high } : {}),
        ...(r?.long === true ? { long: true } : {}),
      };
    }).filter((r) => r.test && r.result);
    const photos = (Array.isArray(req.body?.photos) ? req.body.photos : []).filter((p) => typeof p === 'string' && p.length > 100).slice(0, 2);
    if (photos.some((p) => p.length > MAX_PHOTO_B64 || !/^[A-Za-z0-9+/=\s]+$/.test(p))) throw err(req, 'Photos are too large', 'ছবি অনেক বড়');
    if (!items.length && !photos.length) throw err(req, 'Enter at least one result or a photo of the report', 'অন্তত একটা ফল বা রিপোর্টের ছবি দিন');
    const now = Date.now();
    await ref.update({ status: 'reported', report: { items, note: str(req.body?.note, 500), photos, reportedAt: now }, reportedAt: now });
    const x = s.data();
    if (x.patientUid) await pushPatient(x.patientUid, 'Your test report is ready', 'আপনার পরীক্ষার রিপোর্ট এসেছে', x.labName || '', { type: 'rx', id: x.rxId });
    // ডাক্তারের দেখার তালিকা
    const prov = await db.doc(`providers/${x.doctorUid}`).get();
    const st = reviewSettings(prov);
    const status = !st.enabled ? 'off' : st.noFee ? 'paid' : 'unpaid';
    await db.doc(`rxReviews/${id}`).set({
      rxId: x.rxId, labOrderId: id, doctorUid: x.doctorUid, doctorName: x.doctorName || '', patientUid: x.patientUid || null,
      patientName: x.patientName || '', labName: x.labName || '', tests: x.tests || [], status, fee: st.fee, noFee: st.noFee,
      payNumber: st.payNumber, payMethods: st.payMethods, createdAt: now, updatedAt: now,
    }, { merge: true });
    if (status === 'paid') await push(x.doctorUid, 'A report is waiting for you', 'একটা রিপোর্ট দেখার অপেক্ষায়', x.patientName || '', { type: 'rx_review' });
    return { ok: true };
  },

  /** রিপোর্ট পড়া: রোগী (নিজের), ল্যাব (নিজের), ডাক্তার (টাকা মিললে বা ফি না থাকলে) */
  async lab_order_get(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const s = await db.doc(`labOrders/${id}`).get();
    if (!s.exists) throw notFound(req);
    const x = s.data();
    let ok = x.patientUid === user.uid || x.labId === user.uid;
    if (!ok && x.doctorUid === user.uid) {
      const r = await db.doc(`rxReviews/${id}`).get();
      ok = r.exists && DOCTOR_SEES.includes(r.get('status')); // ইতিহাসের doctorLabView-এর একই নিয়ম
      if (!ok) throw err(req, "The patient hasn't paid for the report review yet", 'রোগী এখনো রিপোর্ট দেখার টাকা দেননি', 402);
    }
    if (!ok) throw notFound(req);
    const who = x.patientUid === user.uid ? 'patient' : x.labId === user.uid ? 'lab' : null;
    // প্যাথলজি পায় শুধু নিজের আগের ফল; রোগী আর (অনুমতি পাওয়া) ডাক্তার সব ল্যাবের
    const history = await reportHistory(x.patientUid, id, x.reportedAt || Date.now(), who === 'lab' ? x.labId : null);
    return { item: labOrderView(s, true, who), history };
  },

  /** প্যাথলজি: রিপোর্টের ফর্ম — প্রতিটা পরীক্ষার ছক (ঘর, একক, স্বাভাবিক মাত্রা; ল্যাবের নিজের মাত্রা থাকলে সেটা) */
  async lab_form(req, user) {
    const lab = await myLab(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const s = await db.doc(`labOrders/${id}`).get();
    if (!s.exists || s.get('labId') !== user.uid) throw notFound(req);
    await LT.load();
    const ranges = lab.get('ranges') || {};
    return { forms: (s.get('tests') || []).map((t) => LT.formFor(t, s.get('patientGender') || '', ranges)) };
  },

  /** প্যাথলজি: নিজের মেশিনের স্বাভাবিক মাত্রা রেখে দেওয়া (ranges: [{ code, key, low, high }]; দুটোই null = ছকেরটা) */
  async lab_ranges_set(req, user) {
    const lab = await myLab(req, user);
    const raw = Array.isArray(req.body?.ranges) ? req.body.ranges.slice(0, 100) : [];
    const cur = { ...(lab.get('ranges') || {}) };
    const n = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
    for (const r of raw) {
      if (typeof r?.code !== 'string' || !/^[a-z0-9_]{1,40}$/.test(r.code) || typeof r?.key !== 'string' || !/^[a-z0-9_]{1,30}$/.test(r.key)) continue;
      const low = n(r.low);
      const high = n(r.high);
      const k = `${r.code}.${r.key}`;
      if (low == null && high == null) delete cur[k];
      else if (low != null && high != null && low > high) throw err(req, 'The low value is bigger than the high value', 'নিচের মান ওপরের মানের চেয়ে বড়');
      else cur[k] = { low, high };
    }
    if (Object.keys(cur).length > 500) throw err(req, 'Too many ranges', 'অনেক বেশি মাত্রা');
    await lab.ref.update({ ranges: cur });
    return { ok: true, count: Object.keys(cur).length };
  },

  /** রোগী: এই প্রেসক্রিপশনের ফার্মেসি/প্যাথলজি অনুরোধ, রিপোর্ট আর ডাক্তারের দেখা */
  async rx_activity(req, user) {
    const { rxId } = req.body || {};
    if (!R.isValidId(rxId)) throw notFound(req);
    const s = await db.doc(`prescriptions/${rxId}`).get();
    if (!s.exists || (s.get('patientUid') !== user.uid && s.get('doctorUid') !== user.uid)) throw notFound(req);
    const isPatient = s.get('patientUid') === user.uid;
    const [orders, labs] = await Promise.all([
      db.collection('rxOrders').where('rxId', '==', rxId).limit(50).get(),
      db.collection('labOrders').where('rxId', '==', rxId).limit(50).get(),
    ]);
    const reviews = labs.size ? await db.getAll(...labs.docs.map((d) => db.doc(`rxReviews/${d.id}`))) : [];
    // পরের খোলা চেম্বারের দিন (রিপোর্ট দেখানোর সিরিয়াল)
    let nextOpen = null;
    const ci = s.get('chamberIndex');
    if (Number.isInteger(ci)) {
      const prov = await db.doc(`providers/${s.get('doctorUid')}`).get();
      const ch = prov.exists ? K.chambersOf(prov.data())[ci] : null;
      if (ch && ch.appointments && K.takesAppointments(prov.data())) {
        const today = P.dhakaToday();
        for (let i = 0, d = today; i < 14; i++, d += DAY_MS) {
          if (K.chamberOpenOn(ch, d)) { nextOpen = d; break; }
        }
      }
    }
    return {
      medicines: isPatient ? medStatus(s.data()) : [],
      dispensed: isPatient ? (s.get('dispensed') || []).map((p) => ({ name: p.name, at: p.at, items: p.items })) : [],
      orders: isPatient ? orders.docs.map((d) => ({
        id: d.id, pharmacyId: d.get('pharmacyId'), pharmacyName: d.get('pharmacyName'), status: d.get('status'), reason: d.get('reason') || '', createdAt: d.get('createdAt'),
        mode: d.get('mode') || 'shop', price: d.get('price') || '', eta: d.get('eta') || '', deliveryFee: d.get('deliveryFee') || '', missed: d.get('missed') || [],
      })).sort((a, c) => c.createdAt - a.createdAt) : [],
      // বাসার ঠিকানা-ফোন অর্ডার ধরে: যিনি পাঠিয়েছেন (অর্ডারের রোগী) তিনিই — প্রেসক্রিপশনের মালিক দেখে নয়
      labOrders: labs.docs.filter((d) => isPatient || DOCTOR_SEES.includes(reviews.find((r) => r.id === d.id)?.get('status')))
        .map((d) => labOrderView(d, false, d.get('patientUid') === user.uid ? 'patient' : null)),
      reviews: reviews.filter((r) => r.exists).map((r) => reviewView(r)),
      nextOpen,
    };
  },

  // ======================= রিপোর্ট দেখানো (অনলাইনে, টাকা দিয়ে) =======================
  /** ডাক্তার: সেটিং — চালু, ফি বা ফি নেই, যে নম্বরে টাকা পাঠাবেন, bKash/Nagad */
  async review_settings(req, user) {
    if ((await approvedBadge(user.uid)) !== 'doctor') throw err(req, 'Doctors only', 'শুধু ডাক্তার', 403);
    const ref = db.doc(`providers/${user.uid}`);
    if (req.body?.set === true) {
      const b = req.body;
      const enabled = b.enabled === true;
      const noFee = b.noFee === true;
      const fee = noFee ? '' : str(b.fee, 40);
      const payMethods = (Array.isArray(b.payMethods) ? b.payMethods : []).filter((m) => ['bkash', 'nagad', 'rocket'].includes(m));
      const pn = K.normalizeBd(b.payNumber);
      if (enabled && !noFee) {
        if (!fee) throw err(req, 'Enter the fee, or tick "No fee"', 'ফি দিন, অথবা "ফি চাই না" টিক দিন');
        if (!pn) throw err(req, 'Enter the mobile number for payment', 'টাকা পাঠানোর মোবাইল নম্বর দিন');
        if (!payMethods.length) throw err(req, 'Choose bKash, Nagad or Rocket', 'bKash, Nagad বা Rocket বেছে নিন');
      }
      await ref.set({ reportReview: { enabled, noFee, fee, payNumber: pn ? K.localBd(pn) : '', payMethods } }, { merge: true });
    }
    const p = await ref.get();
    return reviewSettings(p);
  },

  /** রোগী: টাকা পাঠিয়ে TrxID */
  async review_pay(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`rxReviews/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('patientUid') !== user.uid) throw notFound(req);
    if (!['unpaid'].includes(s.get('status'))) throw err(req, 'Already sent', 'আগেই পাঠানো হয়েছে', 409);
    const trxId = str(req.body?.trxId, 40).replace(/\s/g, '');
    if (!/^[A-Za-z0-9]{6,30}$/.test(trxId)) throw err(req, 'Enter the transaction ID from the payment SMS', 'টাকা পাঠানোর SMS-এর TrxID দিন');
    const method = (s.get('payMethods') || []).includes(req.body?.method) ? req.body.method : (s.get('payMethods') || [])[0] || '';
    await ref.update({ status: 'claimed', trxId, method, rejectNote: FieldValue.delete(), updatedAt: Date.now() });
    await push(s.get('doctorUid'), 'Check a report-review payment', 'রিপোর্ট দেখার টাকা মিলিয়ে নিন', `${s.get('patientName')} · ${method} · ${trxId}`, { type: 'rx_review' });
    return { ok: true };
  },

  /** ডাক্তার/সহকারী: সব রিপোর্ট দেখার অনুরোধ (TrxID সহ — মেলানোর জন্য; রিপোর্ট নয়) */
  async reviews_doctor(req, user) {
    const { doctorUid } = await doctorOrAssistant(req, user, req.body?.doctorUid);
    const snap = await db.collection('rxReviews').where('doctorUid', '==', doctorUid).limit(400).get();
    return { items: snap.docs.filter((d) => d.get('status') !== 'off').map((d) => reviewView(d, true)).sort((a, c) => c.updatedAt - a.updatedAt).slice(0, 150) };
  },

  /** ডাক্তার/সহকারী: টাকা মিলেছে (ok) বা মেলেনি */
  async review_confirm(req, user) {
    const { doctorUid } = await doctorOrAssistant(req, user, req.body?.doctorUid);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`rxReviews/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('doctorUid') !== doctorUid || s.get('status') !== 'claimed') throw notFound(req);
    const ok = req.body?.ok === true;
    const note = str(req.body?.note, 200);
    await ref.update(ok ? { status: 'paid', paidAt: Date.now(), updatedAt: Date.now() } : { status: 'unpaid', rejectNote: note || 'TrxID মেলেনি', updatedAt: Date.now() });
    await pushPatient(s.get('patientUid'),
      ok ? 'The doctor received your payment and will check the report' : "The doctor couldn't find your payment",
      ok ? 'ডাক্তার টাকা পেয়েছেন, রিপোর্ট দেখবেন' : 'ডাক্তার আপনার টাকা খুঁজে পাননি',
      ok ? '' : note, { type: 'rx', id: s.get('rxId') });
    return { ok: true };
  },

  /** ডাক্তার: দেখে মন্তব্য (দরকার হলে নতুন প্রেসক্রিপশনের id) */
  async review_done(req, user) {
    if ((await approvedBadge(user.uid)) !== 'doctor') throw err(req, 'Doctors only', 'শুধু ডাক্তার', 403);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`rxReviews/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('doctorUid') !== user.uid || s.get('status') !== 'paid') throw notFound(req);
    const comment = str(req.body?.comment, 1000);
    if (!comment) throw err(req, 'Write what the patient should know', 'রোগীকে কী জানাবেন লিখুন');
    const newRxId = R.isValidId(req.body?.newRxId) ? req.body.newRxId : null;
    await ref.update({ status: 'done', comment, newRxId, doneAt: Date.now(), updatedAt: Date.now() });
    await pushPatient(s.get('patientUid'), 'The doctor checked your report', 'ডাক্তার আপনার রিপোর্ট দেখেছেন', comment.slice(0, 120), { type: 'rx', id: s.get('rxId') });
    return { ok: true };
  },
};

/** প্রতিদিন: কেনা ওষুধ শেষ হওয়ার ২ দিন আগে মনে করানো */
async function refillReminders(now = Date.now()) {
  const today = P.dhakaToday(now);
  const snap = await db.collection('prescriptions').where('refillAt', '==', today).limit(500).get();
  let sent = 0;
  for (const d of snap.docs) {
    const x = d.data();
    if (x.status !== 'active' || !x.patientUid || x.refillNotifiedAt === today) continue;
    const low = medStatus(x).filter((s) => s.got > 0 && !(s.need != null && s.got >= s.need)).map((s) => s.name);
    await pushPatient(x.patientUid, 'Your medicines will run out in 2 days', '২ দিন পর ওষুধ শেষ হবে — কিনে নিন', low.join(', '), { type: 'rx', id: d.id });
    await d.ref.update({ refillNotifiedAt: today });
    sent++;
  }
  return { sent };
}

module.exports = { actions, pharmacyEstimate, refillReminders, stripOrderContacts, purgeClosedContacts, backfillClosedContacts, CONTACT_KEEP_MS, perDay, needQty, medStatus, refillDate, countable, labView, labsForDoctor, DOCTOR_SEES };
