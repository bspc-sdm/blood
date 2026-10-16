// ডিজিটাল প্রেসক্রিপশন (ধাপ ১, ১৬ অক্টোবর ২০২৬)
// - শুধু যাচাই করা (BMDC) ডাক্তার লেখেন; ডাক্তারের যোগ করা সহকারী রোগী এন্ট্রি করেন (লিখতে পারেন না)
// - অ্যাপে অ্যাপয়েন্টমেন্ট নেওয়া রোগী: সরাসরি তাঁর অ্যাকাউন্টে
// - ফোনে সিরিয়াল নেওয়া রোগী: সহকারী নাম-বয়স-ফোন দিয়ে এন্ট্রি করেন (rxPatients)। রোগী অ্যাপে প্রেসক্রিপশনের কোড আর
//   নিজের ফোন নম্বর দিয়ে একবার যোগ করলে ওই ডাক্তারের আগের-পরের সব প্রেসক্রিপশন তাঁর অ্যাকাউন্টে আসে।
//   (ফোন নম্বরকে পাসওয়ার্ড করা হয়নি — নম্বর পরিবার, সহকারী, ফার্মেসি সবাই জানে)
// - একবার দিলে বদলায় না; শুধু বাতিল করে নতুন দেওয়া যায়
// - QR/কোড দিয়ে যে কেউ (ফার্মেসি) আসল কি না মিলিয়ে নিতে পারে: /rx/{code}
// prescriptions আর rxPatients অ্যাপ সরাসরি পড়তে/লিখতে পারে না (Firestore নিয়মে নেই) — সব এখান দিয়ে।
const crypto = require('crypto');
const { db, HttpError, sendPush, tokensOf } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const { approvedBadge, useQuota } = require('./community-db');

const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_ASSISTANTS = 5;
const MAX_MEDS = 25;
const MAX_TESTS = 25;
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_RE = /^[A-HJ-NP-Z2-9]{10}$/;
const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;
const DOSE_RE = /^(\d{1,2}(\.5)?|½)(\+(\d{1,2}(\.5)?|½)){1,3}$/;
const TIMINGS = ['before', 'after', ''];

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const str = (v, n) => (typeof v === 'string' ? v.replace(/\r\n/g, '\n').trim().slice(0, n) : '');

function secret() {
  return String(process.env.CRON_SECRET || process.env.FIREBASE_SERVICE_ACCOUNT || 'rx');
}

/** ফোন নম্বর সরাসরি না রেখে গোপন চাবিতে হ্যাশ (খোঁজার জন্য) */
function phoneKey(phone) {
  const n = K.normalizeBd(phone);
  return n ? `p_${crypto.createHmac('sha256', secret()).update(`rx-phone:${n}`).digest('hex').slice(0, 40)}` : null;
}

function newCode() {
  return [...crypto.randomBytes(10)].map((b) => CODE_CHARS[b % CODE_CHARS.length]).join('');
}

/** আজকের তারিখ (ঢাকা) UTC মধ্যরাত হিসেবে — অ্যাপয়েন্টমেন্টের তারিখের মতো */
function dhakaToday(now = Date.now()) {
  const d = new Date(now + 6 * 3600 * 1000);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

async function requireDoctor(req, uid) {
  if ((await approvedBadge(uid)) !== 'doctor') {
    throw err(req, 'Only verified doctors can write prescriptions', 'শুধু যাচাই করা ডাক্তার প্রেসক্রিপশন লিখতে পারেন', 403);
  }
}

const myEmail = (user) => (user.email_verified ? String(user.email || '').toLowerCase() : '');

/** কার হয়ে কাজ: নিজে ডাক্তার, অথবা ডাক্তারের যোগ করা সহকারী (ইমেইল মিলিয়ে) */
async function actingFor(req, user, doctorUid) {
  const d = typeof doctorUid === 'string' && doctorUid && doctorUid !== user.uid ? doctorUid : user.uid;
  if (d === user.uid) {
    await requireDoctor(req, user.uid);
    return { doctorUid: d, role: 'doctor' };
  }
  if (!R.isValidId(d)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
  const p = await db.doc(`providers/${d}`).get();
  const email = myEmail(user);
  if (!email || !p.exists || !(p.get('assistantEmails') || []).includes(email)) {
    throw err(req, "You are not this doctor's assistant", 'আপনি এই ডাক্তারের সহকারী হিসেবে যোগ করা নেই', 403);
  }
  if ((await approvedBadge(d)) !== 'doctor') throw err(req, 'This doctor is not verified now', 'এই ডাক্তার এখন যাচাই করা নেই', 403);
  return { doctorUid: d, role: 'assistant' };
}

function patientView(d) {
  const x = d.data();
  return {
    id: d.id,
    name: x.name || '',
    age: x.age ?? null,
    gender: x.gender || '',
    phone: x.phone || '',
    linked: !!x.linkedUid,
    createdAt: x.createdAt || 0,
    lastRxAt: x.lastRxAt || 0,
  };
}

function parsePatient(req, b) {
  const name = str(b?.name, 80);
  if (!name) throw err(req, "Write the patient's name", 'রোগীর নাম লিখুন');
  let age = b?.age;
  if (age === '' || age === undefined || age === null) age = null;
  else if (!Number.isInteger(age) || age < 0 || age > 130) throw err(req, 'Enter a valid age', 'সঠিক বয়স দিন');
  const gender = ['male', 'female'].includes(b?.gender) ? b.gender : '';
  return { name, age, gender };
}

function parseMedicines(req, list) {
  if (list !== undefined && !Array.isArray(list)) throw err(req, 'Check the medicines', 'ওষুধগুলো দেখে নিন');
  const out = [];
  for (const m of (list || []).slice(0, MAX_MEDS + 1)) {
    if (out.length >= MAX_MEDS) throw err(req, `Up to ${MAX_MEDS} medicines`, `সর্বোচ্চ ${MAX_MEDS}টা ওষুধ`);
    const name = str(m?.name, 100);
    if (!name) throw err(req, 'Write the name of each medicine', 'প্রতিটা ওষুধের নাম লিখুন');
    const dose = str(m?.dose, 30).replace(/\s+/g, '');
    // "১+০+১" ধরনের, অথবা ছোট লেখা ("প্রয়োজনে", "দিনে ১ বার")
    const doseOk = !dose || DOSE_RE.test(dose) || dose.length <= 30;
    if (!doseOk) throw err(req, 'Check the dose', 'খাওয়ার নিয়ম দেখে নিন');
    const days = m?.days === undefined || m?.days === null || m?.days === '' ? 0 : m.days;
    if (!Number.isInteger(days) || days < 0 || days > 365) throw err(req, 'Days must be 0–365 (0 = continue)', 'দিন ০–৩৬৫ (০ = চলবে)');
    out.push({
      name,
      form: str(m?.form, 40),
      strength: str(m?.strength, 40),
      dose,
      timing: TIMINGS.includes(m?.timing) ? m.timing : '',
      days,
      note: str(m?.note, 120),
    });
  }
  return out;
}

function parseTests(req, list) {
  if (list !== undefined && !Array.isArray(list)) throw err(req, 'Check the tests', 'পরীক্ষাগুলো দেখে নিন');
  const out = [];
  for (const t of list || []) {
    const s = str(t, 80);
    if (!s) continue;
    if (out.length >= MAX_TESTS) throw err(req, `Up to ${MAX_TESTS} tests`, `সর্বোচ্চ ${MAX_TESTS}টা পরীক্ষা`);
    if (!out.includes(s)) out.push(s);
  }
  return out;
}

function parseFindings(f) {
  const o = f && typeof f === 'object' ? f : {};
  return { bp: str(o.bp, 20), pulse: str(o.pulse, 20), temp: str(o.temp, 20), weight: str(o.weight, 20), spo2: str(o.spo2, 20), other: str(o.other, 300) };
}

/** পূর্ণ প্রেসক্রিপশন (রোগী, লেখক ডাক্তার) */
function rxView(d) {
  const x = d.data();
  return {
    id: d.id,
    code: x.code,
    status: x.status,
    createdAt: x.createdAt || 0,
    doctorUid: x.doctorUid,
    doctor: x.doctor || {},
    patient: x.patient || {},
    complaints: x.complaints || '',
    findings: x.findings || {},
    diagnosis: x.diagnosis || '',
    medicines: x.medicines || [],
    tests: x.tests || [],
    testPlace: x.testPlace || '',
    testPlaceId: x.testPlaceId || null,
    advice: x.advice || '',
    followUpDate: x.followUpDate || null,
    followUpBookDate: x.followUpBookDate || null,
    followUpNote: x.followUpNote || '',
    chamberIndex: Number.isInteger(x.chamberIndex) ? x.chamberIndex : null,
    linked: !!x.patientUid,
  };
}

async function push(uid, en, bn, body, data) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists) return;
    await sendPush([{ uid, tokens: tokensOf(u), title: R.L(u.get('lang'), en, bn), body: body || '', data }]);
  } catch (e) {
    console.error('rx push failed', e.message);
  }
}

async function uniqueCode() {
  for (let i = 0; i < 5; i++) {
    const c = newCode();
    const s = await db.collection('prescriptions').where('code', '==', c).limit(1).get();
    if (s.empty) return c;
  }
  throw new Error('code collision');
}

const actions = {
  /** ডাক্তার নিজে: নাম আর চেম্বারগুলোর নাম (ওয়েবে লেখার সময় চেম্বার বাছতে) */
  async rx_me(req, user) {
    await requireDoctor(req, user.uid);
    const p = await db.doc(`providers/${user.uid}`).get();
    const chambers = p.exists ? K.chambersOf(p.data()).map((c) => c.hospitalNameBn || c.hospitalName || '') : [];
    return { name: (p.exists && p.get('name')) || '', degrees: (p.exists && p.get('degrees')) || '', chambers };
  },

  // ---------- সহকারী ----------
  /** ডাক্তার: সহকারীদের ইমেইল */
  async rx_team(req, user) {
    await requireDoctor(req, user.uid);
    const p = await db.doc(`providers/${user.uid}`).get();
    return { emails: (p.exists && p.get('assistantEmails')) || [] };
  },

  async rx_team_set(req, user) {
    await requireDoctor(req, user.uid);
    const raw = Array.isArray(req.body?.emails) ? req.body.emails : [];
    const own = myEmail(user);
    const emails = [...new Set(raw.map((e) => String(e || '').trim().toLowerCase()).filter(Boolean))];
    if (emails.length > MAX_ASSISTANTS) throw err(req, `Up to ${MAX_ASSISTANTS} assistants`, `সর্বোচ্চ ${MAX_ASSISTANTS} জন সহকারী`);
    if (emails.some((e) => !EMAIL_RE.test(e))) throw err(req, 'Check the email addresses', 'ইমেইলগুলো দেখে নিন');
    if (own && emails.includes(own)) throw err(req, "You can't add yourself", 'নিজেকে সহকারী হিসেবে যোগ করা যায় না');
    await db.doc(`providers/${user.uid}`).set({ assistantEmails: emails }, { merge: true });
    return { emails };
  },

  /** সহকারী: কোন কোন ডাক্তারের হয়ে কাজ করি */
  async rx_assisting(req, user) {
    const email = myEmail(user);
    if (!email) return { items: [] };
    const snap = await db.collection('providers').where('assistantEmails', 'array-contains', email).limit(10).get();
    const items = [];
    for (const d of snap.docs) {
      if ((await approvedBadge(d.id)) !== 'doctor') continue;
      items.push({ doctorUid: d.id, name: d.get('name') || '', degrees: d.get('degrees') || '' });
    }
    return { items };
  },

  // ---------- রোগী এন্ট্রি ----------
  /** ডাক্তার/সহকারী: এন্ট্রি করা রোগী + অ্যাপে আজকের আশেপাশের অ্যাপয়েন্টমেন্ট */
  async rx_patients(req, user) {
    const { doctorUid, role } = await actingFor(req, user, req.body?.doctorUid);
    const today = dhakaToday();
    const [ps, appts] = await Promise.all([
      db.collection('rxPatients').where('doctorUid', '==', doctorUid).limit(1000).get(),
      db.collection('appointments').where('providerUid', '==', doctorUid).limit(500).get(),
    ]);
    const patients = ps.docs.map(patientView).sort((a, b) => (b.lastRxAt || b.createdAt) - (a.lastRxAt || a.createdAt)).slice(0, 300);
    const appointments = appts.docs
      .filter((d) => ['pending', 'confirmed', 'completed'].includes(d.get('status')) && d.get('date') >= today - 2 * DAY_MS && d.get('date') <= today + DAY_MS)
      .map((d) => ({ id: d.id, name: d.get('patientName') || '', date: d.get('date'), serial: d.get('serial') || null, status: d.get('status'), chamber: d.get('chamberBn') || d.get('chamber') || '' }))
      .sort((a, b) => a.date - b.date || (a.serial || 999) - (b.serial || 999));
    return { role, patients, appointments };
  },

  async rx_patient_add(req, user) {
    const { doctorUid } = await actingFor(req, user, req.body?.doctorUid);
    const p = parsePatient(req, req.body);
    const phone = K.normalizeBd(req.body?.phone);
    if (!phone) throw err(req, "Enter the patient's mobile number", 'রোগীর মোবাইল নম্বর দিন');
    const key = phoneKey(phone);
    // একই নম্বর আর একই নাম আগে এন্ট্রি থাকলে সেটাই (বয়স হালনাগাদ)
    const snap = await db.collection('rxPatients').where('doctorUid', '==', doctorUid).limit(1000).get();
    const same = snap.docs.find((d) => d.get('phoneKey') === key && String(d.get('name') || '').toLowerCase() === p.name.toLowerCase());
    if (same) {
      if (p.age !== null || p.gender) await same.ref.update({ ...(p.age !== null ? { age: p.age } : {}), ...(p.gender ? { gender: p.gender } : {}) });
      return { patient: patientView(await same.ref.get()), existed: true };
    }
    await useQuota(req, user.uid, 'rxPatient');
    const ref = await db.collection('rxPatients').add({
      doctorUid, ...p, phone: K.localBd(phone), phoneKey: key, linkedUid: null, createdBy: user.uid, createdAt: Date.now(), lastRxAt: 0,
    });
    return { patient: patientView(await ref.get()), existed: false };
  },

  // ---------- প্রেসক্রিপশন ----------
  /** শুধু ডাক্তার নিজে */
  async rx_create(req, user) {
    await requireDoctor(req, user.uid);
    const b = req.body || {};
    let patientUid = null;
    let rxPatientId = null;
    let appointmentId = null;
    let base = {};
    if (R.isValidId(b.appointmentId)) {
      const a = await db.doc(`appointments/${b.appointmentId}`).get();
      if (!a.exists || a.get('providerUid') !== user.uid) throw err(req, 'Appointment not found', 'অ্যাপয়েন্টমেন্ট পাওয়া যায়নি', 404);
      appointmentId = a.id;
      patientUid = a.get('patientUid');
      base = { name: a.get('patientName') || '' };
    } else if (R.isValidId(b.rxPatientId)) {
      const p = await db.doc(`rxPatients/${b.rxPatientId}`).get();
      if (!p.exists || p.get('doctorUid') !== user.uid) throw err(req, 'Patient not found', 'রোগী পাওয়া যায়নি', 404);
      rxPatientId = p.id;
      patientUid = p.get('linkedUid') || null;
      base = { name: p.get('name'), age: p.get('age') ?? null, gender: p.get('gender') || '' };
    } else {
      throw err(req, 'Choose the patient', 'রোগী বেছে নিন');
    }
    const patient = parsePatient(req, { ...base, ...(b.patient || {}) });
    const medicines = parseMedicines(req, b.medicines);
    const tests = parseTests(req, b.tests);
    const advice = str(b.advice, 1000);
    if (!medicines.length && !tests.length && !advice) throw err(req, 'Add a medicine, a test or advice', 'অন্তত একটা ওষুধ, পরীক্ষা বা পরামর্শ দিন');
    const followUpDays = Number.isInteger(b.followUpDays) && b.followUpDays > 0 && b.followUpDays <= 365 ? b.followUpDays : 0;

    const [prov, badge] = await Promise.all([db.doc(`providers/${user.uid}`).get(), db.doc(`badges/${user.uid}`).get()]);
    const chambers = prov.exists ? K.chambersOf(prov.data()) : [];
    const ci = Number.isInteger(b.chamberIndex) && chambers[b.chamberIndex] ? b.chamberIndex : null;
    const ch = ci === null ? null : chambers[ci];
    const doctor = {
      name: (prov.exists && prov.get('name')) || '',
      degrees: (prov.exists && prov.get('degrees')) || '',
      specialties: (prov.exists && prov.get('specialties')) || [],
      regNo: (badge.exists && badge.get('regNo')) || '',
      chamber: ch ? { name: ch.hospitalNameBn || ch.hospitalName || '', address: [ch.address, ch.district].filter(Boolean).join(', '), phone: ch.serialPhone || '' } : null,
    };
    await useQuota(req, user.uid, 'rx');
    const now = Date.now();
    // আবার দেখানোর দিন চেম্বার বন্ধ থাকলে পরের খোলা দিনে সিরিয়ালের প্রস্তাব (অ্যাপের "সিরিয়াল নিন")
    const followUpDate = followUpDays ? dhakaToday(now) + followUpDays * DAY_MS : null;
    let followUpBookDate = null;
    if (followUpDate && ch && ch.appointments && K.takesAppointments(prov.data())) {
      for (let i = 0, d = followUpDate; i < 14; i++, d += DAY_MS) {
        if (K.chamberOpenOn(ch, d)) {
          followUpBookDate = d;
          break;
        }
      }
    }
    const code = await uniqueCode();
    const ref = await db.collection('prescriptions').add({
      code,
      status: 'active',
      createdAt: now,
      doctorUid: user.uid,
      doctor,
      patient,
      patientUid,
      rxPatientId,
      appointmentId,
      complaints: str(b.complaints, 1000),
      findings: parseFindings(b.findings),
      diagnosis: str(b.diagnosis, 500),
      medicines,
      tests,
      testPlace: str(b.testPlace, 200),
      testPlaceId: typeof b.testPlaceId === 'string' && /^[A-Za-z0-9_-]{1,60}$/.test(b.testPlaceId) ? b.testPlaceId : null,
      advice,
      followUpDate,
      followUpBookDate,
      followUpNote: followUpDays ? str(b.followUpNote, 80) : '',
      chamberIndex: ci,
    });
    if (rxPatientId) await db.doc(`rxPatients/${rxPatientId}`).update({ lastRxAt: now });
    if (patientUid) {
      await push(patientUid, `New prescription from ${doctor.name}`, `${doctor.name} নতুন প্রেসক্রিপশন দিয়েছেন`, '', { type: 'rx', id: ref.id });
    }
    return { id: ref.id, code };
  },

  async rx_cancel(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const ref = db.doc(`prescriptions/${id}`);
    const s = await ref.get();
    if (!s.exists || s.get('doctorUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    if (s.get('status') === 'cancelled') return { ok: true };
    await ref.update({ status: 'cancelled', cancelledAt: Date.now() });
    return { ok: true };
  },

  /** ডাক্তার: নিজের লেখা সাম্প্রতিক প্রেসক্রিপশন */
  async rx_doctor_list(req, user) {
    await requireDoctor(req, user.uid);
    const snap = await db.collection('prescriptions').where('doctorUid', '==', user.uid).limit(500).get();
    return { items: snap.docs.map(rxView).sort((a, b) => b.createdAt - a.createdAt).slice(0, 150) };
  },

  /** রোগী: আমার প্রেসক্রিপশন */
  async rx_mine(req, user) {
    const snap = await db.collection('prescriptions').where('patientUid', '==', user.uid).limit(300).get();
    return { items: snap.docs.map(rxView).sort((a, b) => b.createdAt - a.createdAt) };
  },

  /**
   * রোগী: কাগজের/শেয়ার করা প্রেসক্রিপশন নিজের অ্যাকাউন্টে যোগ — কোড + সহকারীকে দেওয়া ফোন নম্বর
   * (আর প্রোফাইলের নম্বরও একই হতে হবে)। মিললে ওই ডাক্তারের কাছে এই রোগীর আগের-পরের সব প্রেসক্রিপশন আসে।
   */
  async rx_claim(req, user) {
    const code = String(req.body?.code || '').trim().toUpperCase().replace(/[\s-]/g, '');
    if (!CODE_RE.test(code)) throw err(req, 'Check the code on the prescription', 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
    await useQuota(req, user.uid, 'rxClaim');
    const phone = K.normalizeBd(req.body?.phone);
    const u = await db.doc(`users/${user.uid}`).get();
    const mine = K.normalizeBd(u.get('phone'));
    const notMatch = () => err(req, "The code and phone number don't match", 'কোড আর ফোন নম্বর মেলেনি');
    if (!phone || phone !== mine) {
      throw err(req, 'Use the same mobile number as in your profile', 'প্রোফাইলের মোবাইল নম্বরটাই দিন');
    }
    const snap = await db.collection('prescriptions').where('code', '==', code).limit(1).get();
    if (snap.empty) throw notMatch();
    const rx = snap.docs[0];
    if (rx.get('patientUid') === user.uid) return { ok: true, added: 0 };
    const pid = rx.get('rxPatientId');
    if (!pid) throw notMatch();
    const pRef = db.doc(`rxPatients/${pid}`);
    const p = await pRef.get();
    if (!p.exists || p.get('phoneKey') !== phoneKey(phone)) throw notMatch();
    if (p.get('linkedUid') && p.get('linkedUid') !== user.uid) {
      throw err(req, 'This prescription is already added to another account', 'এই প্রেসক্রিপশন আরেকটা অ্যাকাউন্টে যোগ করা আছে', 409);
    }
    await pRef.update({ linkedUid: user.uid });
    const all = await db.collection('prescriptions').where('rxPatientId', '==', pid).limit(500).get();
    const batch = db.batch();
    all.docs.forEach((d) => batch.update(d.ref, { patientUid: user.uid }));
    await batch.commit();
    return { ok: true, added: all.size };
  },

  async rx_get(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const s = await db.doc(`prescriptions/${id}`).get();
    if (!s.exists || (s.get('patientUid') !== user.uid && s.get('doctorUid') !== user.uid)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    return { item: rxView(s) };
  },
};

/** /rx/{code}: আসল কি না মিলিয়ে দেখা (ফার্মেসি) — ডাক্তার, তারিখ, রোগীর নাম, ওষুধ; ফোন/রোগনির্ণয় নয় */
async function verify(code) {
  const c = String(code || '').trim().toUpperCase();
  if (!CODE_RE.test(c)) return null;
  const snap = await db.collection('prescriptions').where('code', '==', c).limit(1).get();
  if (snap.empty) return null;
  const x = snap.docs[0].data();
  return {
    status: x.status,
    createdAt: x.createdAt,
    doctor: { name: x.doctor?.name || '', degrees: x.doctor?.degrees || '', regNo: x.doctor?.regNo || '', chamber: x.doctor?.chamber?.name || '' },
    patient: { name: x.patient?.name || '', age: x.patient?.age ?? null },
    medicines: (x.medicines || []).map((m) => ({ name: m.name, strength: m.strength, form: m.form, dose: m.dose, days: m.days })),
    tests: x.tests || [],
  };
}

/** প্রতিদিন: আবার দেখানোর ২ দিন আগে আর আগের দিন রোগীকে মনে করানো */
async function followUpReminders(now = Date.now()) {
  const today = dhakaToday(now);
  let sent = 0;
  for (const days of [2, 1]) {
    const snap = await db.collection('prescriptions').where('followUpDate', '==', today + days * DAY_MS).limit(500).get();
    for (const d of snap.docs) {
      const x = d.data();
      if (x.status !== 'active' || !x.patientUid) continue;
      const name = x.doctor?.name || '';
      await push(
        x.patientUid,
        days === 1 ? `Tomorrow: see ${name} again` : `In 2 days: see ${name} again`,
        days === 1 ? `কাল ${name}-এর কাছে আবার দেখানোর দিন` : `২ দিন পর ${name}-এর কাছে আবার দেখানোর দিন`,
        x.followUpNote || '',
        { type: 'rx', id: d.id },
      );
      sent++;
    }
  }
  return { sent };
}

module.exports = { actions, verify, followUpReminders, phoneKey, dhakaToday, DOSE_RE, CODE_RE, _parse: { parseMedicines, parsePatient, parseTests, parseFindings } };
