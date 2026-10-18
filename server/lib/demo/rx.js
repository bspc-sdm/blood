// ডেমো ডেটা — ভাগ: rx (ডাক্তার, অ্যাপয়েন্টমেন্ট/সিরিয়াল, প্রেসক্রিপশন, সহকারী, ফার্মেসি/প্যাথলজির সাথে কাজ)। দেখুন index.js।
//
// কী বানায় (সব নাম "ডেমো" দিয়ে, সব তারিখ ctx.now ধরে):
//  - ডেমো ডাক্তার (ctx.u.doctor): অনুমোদিত ডাক্তার ব্যাজ (ভুয়া রেজি. DEMO-A-12345), চেম্বার ২টা (ময়মনসিংহ মেডিকেল কলেজ
//    হাসপাতাল শনি–বৃহস্পতি বিকাল, মুক্তাগাছা উপজেলা স্বাস্থ্য কমপ্লেক্স সোম/বুধ সকাল + শুক্র), সহকারী (ctx.u.assistant-এর ইমেইল),
//    অনলাইনে রিপোর্ট দেখার ফি
//  - আরও ৪ জন ডাক্তার (গাইনি, শিশু, হৃদরোগ, চর্ম) + ১ জন যাচাইয়ের অপেক্ষায় (হাড়) + ১ জনের ব্যাজ-সংশোধনের অনুরোধ
//  - আজকের সিরিয়াল (নিশ্চিত ১–৪, অপেক্ষায়, বাতিল), সামনের আর আগের অ্যাপয়েন্টমেন্ট; ডেমো রোগীর সব অবস্থার অ্যাপয়েন্টমেন্ট
//  - আজকের সিরিয়াল চলছে (এখন ২ নম্বর, গড় ~৮ মিনিট), ৪ নম্বর রোগী ২০ মিনিট সময় চেয়েছেন (উত্তরের অপেক্ষায়)
//  - প্রেসক্রিপশন (রোগীর, এন্ট্রি করা রোগীর, কাগজের — কোড দিয়ে যোগ করার জন্য, একটা বাতিল), ফার্মেসিতে পাঠানো/অনলাইন অর্ডার,
//    "চেয়েও পাননি" তালিকা, স্টক, প্যাথলজিতে পরীক্ষা (নতুন, নমুনা নেওয়া, রিপোর্ট দেওয়া, বাতিল, বাসা থেকে নমুনা — সময় নিশ্চিতের অপেক্ষায়),
//    পাঠানোর সময়ের আনুমানিক দাম (services ভাগের labPrices থেকে), রিপোর্ট দেখানোর অনুরোধ
// ফার্মেসি আর প্যাথলজির তালিকা (pharmacies/{uid}, labs/{uid}) services ভাগ বানায়; না পেলে (বা দরকারি ঘর না থাকলে) এখানে যোগ হয়।
const crypto = require('crypto');
const { db } = require('../firebase');
const DISTRICTS = require('../districts');
const DIR = require('../hospital-dir');
const K = require('../care');
const P = require('../prescriptions');
const RX = require('../rxnet');
const LT = require('../labtests');
const LP = require('../labprices');

const DAY = 24 * 60 * 60 * 1000;
const MIN = 60 * 1000;
const DIST = DISTRICTS.find((d) => d.en === 'Mymensingh').bn;
const SADAR = 466;

// গাইডে লেখা স্থির কোড (প্রেসক্রিপশনের কোডের নিয়ম: I, O, 0, 1 ছাড়া ১০ অক্ষর)
const CODES = { A: 'DEMXRXA222', B: 'DEMXRXB333', C: 'DEMXRXC444', K: 'DEMXRXK777', PAPER: 'DEMXPAPER6' };
const SHOP_CODE = 'DEMQKP'; // services ভাগের মতো
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const randCode = () => `DEMX${[...crypto.randomBytes(6)].map((b) => CODE_CHARS[b % CODE_CHARS.length]).join('')}`;

const md5key = (k) => crypto.createHash('md5').update(String(k)).digest('hex').slice(0, 10);
const nameKey = (n) => String(n || '').trim().toLowerCase().replace(/\s+/g, ' ');
const missKey = (n) => `k_${crypto.createHash('sha1').update(nameKey(n)).digest('hex').slice(0, 16)}`;

/** হাসপাতালের তালিকা (DGHS) থেকে চেম্বার — provider_save যেভাবে রাখে */
function chamber(hospitalId, o) {
  const h = DIR.find(hospitalId);
  if (!h) throw new Error(`demo rx: hospital ${hospitalId} missing`);
  const times = o.times || [{ days: o.days, start: o.start, end: o.end }];
  const days = [...new Set(times.flatMap((t) => t.days))].sort();
  return {
    hospitalId: h.id,
    hospitalName: h.nameEn,
    hospitalNameBn: h.nameBn || '',
    district: h.district,
    upazilaId: h.upazilaId || null,
    unionId: h.unionId || null,
    room: o.room || '',
    days,
    start: times[0].start,
    end: times[0].end,
    ...(times.length > 1 ? { times } : {}),
    schedule: o.schedule || '',
    noFee: false,
    fee: o.fee || '',
    appointments: o.appointments !== false,
    serialPhone: o.serialPhone || '',
  };
}

/** ঢাকার দিনের (UTC মধ্যরাত) এই সময়ে — মিলিসেকেন্ড */
const at = (dateMid, hhmm) => dateMid + (Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))) * MIN - 6 * 3600 * 1000;

function nextOpen(ch, from) {
  for (let d = from, i = 0; i < 21; i++, d += DAY) if (K.chamberOpenOn(ch, d)) return d;
  return from;
}
function prevOpen(ch, from) {
  for (let d = from, i = 0; i < 21; i++, d -= DAY) if (K.chamberOpenOn(ch, d)) return d;
  return from;
}
const startOn = (ch, d) => (K.chamberTimesOn(ch, d).slice().sort((x, y) => x.start.localeCompare(y.start))[0] || {}).start || ch.start;

/** ল্যাবের রিপোর্টের সারি — lab_report যেভাবে রাখে (ছক থেকে একক, মাত্রা, বেশি/কম) */
function reportRows(testName, gender, values) {
  const f = LT.formFor(testName, gender);
  if (!f.code) return [];
  return f.params.filter((p) => values[p.key] != null).map((p) => {
    const result = String(values[p.key]);
    const flag = p.low != null || p.high != null ? LT.flagOf(result, p.low, p.high) : '';
    return {
      test: p.name, result, unit: p.unit || '', range: p.range || p.text || '', flag,
      group: f.name, code: f.code, key: p.key,
      ...(p.low != null ? { low: p.low } : {}), ...(p.high != null ? { high: p.high } : {}),
    };
  });
}

const med = (name, form, strength, dose, days, timing = '', note = '') => ({ name, form, strength, dose, timing, days, note });
const findings = (o = {}) => ({ bp: '', pulse: '', temp: '', weight: '', spo2: '', other: '', ...o });

module.exports = {
  CODES,
  async seed(ctx) {
    const now = ctx.now;
    const today = P.dhakaToday(now);
    const set = (path, data, opts) => ctx.set(path, data, opts);

    const doc = ctx.u.doctor;
    const asst = ctx.u.assistant;
    const pat = ctx.u.patient;
    const shopU = ctx.u.pharmacy;
    const labU = ctx.u.lab;

    // ---------------------------------------------------------------
    // আগের ভাগগুলোর লেখা পড়া (ফার্মেসি/প্যাথলজির নাম, রোগীর ফোন) — ব্যাচ আগে পাঠিয়ে
    // ---------------------------------------------------------------
    await ctx.flush();
    const [shopS, labS, stockS, missedS, patS, docS, asstS, priceS] = await db.getAll(
      db.doc(`pharmacies/${shopU.uid}`), db.doc(`labs/${labU.uid}`), db.doc(`pharmacyStock/${shopU.uid}`), db.doc(`pharmacyMissed/${shopU.uid}`),
      db.doc(`users/${pat.uid}`), db.doc(`users/${doc.uid}`), db.doc(`users/${asst.uid}`), db.doc(`labPrices/${labU.uid}`),
    );

    // নিজেদের প্রোফাইল (blood ভাগ আগে লিখে থাকলে শুধু ফাঁকা ঘর)
    const profile = async (s, u, phone) => {
      const x = (s.exists && s.data()) || {};
      const add = {};
      if (!x.name) add.name = u.name;
      if (!x.phone) add.phone = phone;
      if (!x.placeName) add.placeName = DIST;
      if (!x.lang) add.lang = 'bn';
      if (Object.keys(add).length || !s.exists) await set(`users/${u.uid}`, add, { merge: true });
      return { ...x, ...add };
    };
    const patProfile = await profile(patS, pat, '01700000101');
    await profile(docS, doc, '01700000201');
    await profile(asstS, asst, '01700000203');
    const patPhone = K.localBd(K.normalizeBd(patProfile.phone) || '8801700000101');

    // ফার্মেসি: না থাকলে অনুমোদিত তালিকা; থাকলে শুধু প্রেসক্রিপশনের কাজে লাগা ঘর (দোকানের কোড, অনলাইনে বিক্রি)
    const shop = shopS.exists ? shopS.data() : null;
    if (!shop) {
      const loc = ctx.near(0.4, 0.3);
      await set(`pharmacies/${shopU.uid}`, {
        name: 'ডেমো লাইফ ফার্মেসি', licenseNo: 'DEMO-DL-1501', phone: '01700000150', district: DIST, upazilaId: SADAR,
        address: 'ডেমো ভবন, চরপাড়া মোড়, ময়মনসিংহ', lat: loc.lat, lng: loc.lng, open24: true, openAt: '', closeAt: '', closedDays: [],
        delivery: true, deliveryNote: 'শহরের ভেতরে ৩০ টাকা', discount: 5, ownerUid: shopU.uid, status: 'approved', docsHave: {},
        createdAt: now - 90 * DAY, updatedAt: now - 90 * DAY, reviewedAt: now - 89 * DAY,
      });
    }
    const shopName = (shop && shop.name) || 'ডেমো লাইফ ফার্মেসি';
    const shopAdd = {};
    if (!shop || !shop.shopCode) shopAdd.shopCode = SHOP_CODE;
    if (!shop || !shop.online) shopAdd.online = { enabled: true, radiusKm: 5, fee: '৩০ টাকা', note: 'সকাল ১০টা – রাত ১০টা', updatedAt: now - 30 * DAY };
    if (shop && shop.status !== 'approved') shopAdd.status = 'approved';
    if (Object.keys(shopAdd).length) await set(`pharmacies/${shopU.uid}`, shopAdd, { merge: true });
    const deliveryFee = ((shop && shop.online) || shopAdd.online).fee || '';

    // প্যাথলজি
    const lab = labS.exists ? labS.data() : null;
    if (!lab) {
      await set(`labs/${labU.uid}`, {
        name: 'ডেমো সিটি ডায়াগনস্টিক সেন্টার', facilityId: null, district: DIST, upazilaId: SADAR, address: 'ডেমো টাওয়ার, মেডিকেল গেট, ময়মনসিংহ',
        licenseNo: 'DEMO-HSM-2201', phone: '01700000160', hours: 'সকাল ৮টা – রাত ১০টা', homeCollection: true, ownerUid: labU.uid,
        status: 'approved', docsHave: {}, createdAt: now - 90 * DAY, updatedAt: now - 90 * DAY, reviewedAt: now - 89 * DAY,
      });
    } else if (lab.status !== 'approved') {
      await set(`labs/${labU.uid}`, { status: 'approved' }, { merge: true });
    }
    const labName = (lab && lab.name) || 'ডেমো সিটি ডায়াগনস্টিক সেন্টার';

    // ---------------------------------------------------------------
    // ডাক্তার
    // ---------------------------------------------------------------
    const DOC_NAME = doc.name; // "ডা. ডেমো হাসান" (index.js)
    const DOC_DEGREES = 'এমবিবিএস, এফসিপিএস (মেডিসিন) — ডেমো'; // services ভাগের প্রেসক্রিপশনের সাথে মিলিয়ে
    const DOC_REG = 'DEMO-A-10001';
    // ডেমোতে যেকোনো দিন সকাল-বিকাল সিরিয়াল নেওয়া যায় (মালিক যেদিনই পরীক্ষা করুন, "আজ" খোলা থাকে)।
    // বিকালের ভাগ মাঝরাত পেরিয়ে (রাত ১টা পর্যন্ত): আগে রাত ১০:৩০-এ শেষ হত, তাই রাত ১০:৩০–১২টায় (ঢাকা) "আজকের চেম্বারের
    // সময় শেষ" — ডেমো রোগী আজকের সিরিয়াল নিতে পারতেন না (রাতে পরীক্ষা করলে যাত্রা ভাঙত, queue.test-ও)। রাত পেরোনো ভাগ
    // কখনো "শেষ" ধরা হয় না; মাঝরাতের পরে নতুন দিনের সকালের ভাগ সামনে থাকে।
    const ALL_DAYS = [6, 0, 1, 2, 3, 4, 5];
    const ch1 = chamber('dghs_392', {
      room: 'ডেমো চেম্বার · বহির্বিভাগ ৩১২',
      times: [{ days: ALL_DAYS, start: '10:00', end: '13:00' }, { days: ALL_DAYS, start: '16:00', end: '01:00' }],
      fee: '৫০০ টাকা', serialPhone: '01700000202', schedule: 'ডেমো চেম্বার — প্রতিদিন খোলা',
    });
    const ch2 = chamber('dghs_382', {
      room: 'ডেমো চেম্বার · রুম ৫', days: [5], start: '14:00', end: '15:30',
      fee: '৪০০ টাকা', schedule: 'শুক্রবার শুধু ফলো-আপ (ডেমো)',
    });
    const docChambers = [ch1, ch2];
    const verification = (t) => ({ by: 'demo-seed', byEmail: '', at: t, checks: ['bmdc_found', 'bmdc_name', 'bmdc_active', 'workplace'] });

    await set(`badges/${doc.uid}`, {
      type: 'doctor', status: 'approved', regNo: DOC_REG, workplace: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল (ডেমো)', name: DOC_NAME,
      email: doc.email, docsHave: {}, createdAt: now - 120 * DAY, reviewedAt: now - 118 * DAY, reviewedBy: 'demo-seed', verification: verification(now - 118 * DAY),
    }, { merge: true });
    await set(`providers/${doc.uid}`, {
      type: 'doctor', name: DOC_NAME, listed: true, appointments: true, active: true,
      specialties: ['medicine', 'endocrinology'], degrees: DOC_DEGREES, gender: 'male', chambers: docChambers,
      assistantEmails: [asst.email.toLowerCase()],
      reportReview: { enabled: true, noFee: false, fee: '৩০০ টাকা', payNumber: '01700000202', payMethods: ['bkash', 'nagad'] },
      updatedAt: now - 30 * DAY,
    }, { merge: true });

    // অন্য ডাক্তাররা (ডিরেক্টরি ভরা থাকুক)
    const others = [
      { key: 'rx-doc-nasrin', name: 'ডা. ডেমো নাসরিন আক্তার', gender: 'female', sp: ['gynecology'], deg: 'MBBS, FCPS (Gynae & Obs)', phone: '01700000211',
        ch: [chamber('dghs_392', { room: 'ডেমো চেম্বার · গাইনি বহির্বিভাগ', days: [6, 1, 3], start: '15:00', end: '17:00', fee: '৮০০ টাকা', serialPhone: '01700000211' })] },
      { key: 'rx-doc-kamrul', name: 'ডা. ডেমো কামরুল ইসলাম', gender: 'male', sp: ['pediatrics'], deg: 'MBBS, DCH (Child Health)', phone: '01700000212',
        ch: [chamber('dghs_392', { room: 'ডেমো চেম্বার · শিশু বহির্বিভাগ', days: [0, 2, 4], start: '15:00', end: '18:00', fee: '৬০০ টাকা' })],
        change: { regNo: 'DEMO-A-55555', workplace: 'ডেমো শিশু চেম্বার, চরপাড়া (সংশোধন)' } },
      { key: 'rx-doc-mahbub', name: 'ডা. ডেমো মাহবুব আলম', gender: 'male', sp: ['cardiology', 'medicine'], deg: 'MBBS, MD (Cardiology)', phone: '01700000213',
        ch: [chamber('dghs_404', { room: 'ডেমো চেম্বার · হৃদরোগ কক্ষ', days: [6, 0, 1, 2, 3], start: '16:00', end: '20:00', fee: '১০০০ টাকা', serialPhone: '01700000213' })] },
      { key: 'rx-doc-farzana', name: 'ডা. ডেমো ফারজানা হক', gender: 'female', sp: ['dermatology'], deg: 'MBBS, DDV (Skin & VD)', phone: '01700000214', noAppt: true,
        ch: [chamber('dghs_392', { room: 'ডেমো চেম্বার · চর্ম বহির্বিভাগ', days: [6, 0, 1, 2, 3, 4], start: '10:00', end: '12:00', fee: '৭০০ টাকা', serialPhone: '01700000214', appointments: false })] },
      { key: 'rx-doc-tanvir', name: 'ডা. ডেমো তানভীর রহমান', gender: 'male', sp: ['orthopedics'], deg: 'MBBS, D-Ortho', phone: '01700000215', pending: true,
        ch: [chamber('dghs_392', { room: 'ডেমো চেম্বার · হাড় ও জোড়া', days: [0, 2], start: '18:00', end: '21:00', fee: '৭০০ টাকা' })] },
    ];
    const docs = {};
    for (const o of others) {
      const uid = await ctx.extraUser(o.key, { name: o.name, phone: o.phone, placeName: DIST, lang: 'bn', gender: o.gender });
      docs[o.key] = { uid, name: o.name, ch: o.ch };
      const reg = `DEMO-A-${String(20000 + others.indexOf(o) * 1111)}`;
      const email = `${o.key.replace('rx-doc-', '')}.doctor@lifolo-demo.test`;
      const created = o.pending ? now - 2 * DAY : now - (200 - others.indexOf(o) * 20) * DAY;
      await set(`badges/${uid}`, {
        type: 'doctor', regNo: reg, workplace: `${o.ch[0].hospitalNameBn} (ডেমো)`, name: o.name, email, docsHave: {}, createdAt: created,
        ...(o.pending ? { status: 'pending' } : { status: 'approved', reviewedAt: created + DAY, reviewedBy: 'demo-seed', verification: verification(created + DAY) }),
        ...(o.change ? { pendingChange: { type: 'doctor', regNo: o.change.regNo, workplace: o.change.workplace, name: o.name, at: now - 6 * 3600 * 1000 } } : {}),
      });
      await set(`providers/${uid}`, {
        type: 'doctor', name: o.name, listed: true, appointments: !o.noAppt, active: !o.noAppt, specialties: o.sp, degrees: o.deg,
        gender: o.gender, chambers: o.ch, updatedAt: created + DAY,
      });
    }

    // ---------------------------------------------------------------
    // রোগীরা (লগইন ছাড়া পেছনের)
    // ---------------------------------------------------------------
    const PATS = {
      salma: ['ডেমো রোগী সালমা খাতুন', 38, 'female', '01700000311'],
      karim: ['ডেমো রোগী আব্দুল করিম', 58, 'male', '01700000312'],
      nasima: ['ডেমো রোগী নাসিমা বেগম', 45, 'female', '01700000313'],
      habib: ['ডেমো রোগী হাবিবুর রহমান', 33, 'male', '01700000314'],
      shirin: ['ডেমো রোগী শিরিন আক্তার', 29, 'female', '01700000315'],
      fatema: ['ডেমো রোগী ফাতেমা বেগম', 61, 'female', '01700000316'],
      moriom: ['ডেমো রোগী মরিয়ম নেসা', 52, 'female', '01700000317'],
      rashed: ['ডেমো রোগী রাশেদুল ইসলাম', 49, 'male', '01700000319'],
      kamal: ['ডেমো রোগী কামাল হোসেন', 40, 'male', '01700000318'],
    };
    const bp = {};
    for (const [k, [name, age, gender, phone]] of Object.entries(PATS)) {
      const uid = await ctx.extraUser(`rx-pat-${k}`, { name, phone, placeName: DIST, lang: 'bn', gender });
      bp[k] = { uid, name, age, gender, phone };
    }
    const me = { uid: pat.uid, name: patProfile.name || pat.name, age: 45, gender: 'male', phone: patPhone };

    // ---------------------------------------------------------------
    // অ্যাপয়েন্টমেন্ট
    // ---------------------------------------------------------------
    const serialMax = new Map(); // serials/{...} → সর্বোচ্চ নম্বর
    let apptN = 0;
    const appt = async (p, prov, ch, date, o) => {
      const id = `demo-rx-appt-${String(++apptN).padStart(2, '0')}`;
      const chamberKey = ch.hospitalId || ch.hospitalName || '';
      if (o.serial) {
        const sk = `serials/${prov.uid}_${date}_${md5key(chamberKey)}`;
        serialMax.set(sk, Math.max(serialMax.get(sk) || 0, o.serial));
      }
      await set(`appointments/${id}`, {
        patientUid: p.uid, patientName: p.name, providerUid: prov.uid, providerName: prov.name, type: 'doctor',
        chamber: ch.hospitalName, chamberBn: ch.hospitalNameBn || '', chamberKey, start: startOn(ch, date), room: ch.room || '',
        schedule: ch.schedule || '', date, note: o.note, status: o.status, providerNote: o.providerNote || '',
        ...(o.serial ? { serial: o.serial } : {}),
        ...(o.cancelledBy ? { cancelledBy: o.cancelledBy } : {}),
        ...(o.extra || {}),
        createdAt: o.createdAt, ...(o.status !== 'pending' ? { updatedAt: o.updatedAt || o.createdAt + 2 * 3600 * 1000 } : {}),
      });
      return id;
    };
    const D = { uid: doc.uid, name: DOC_NAME };
    const todayCh = K.chamberOpenOn(ch1, today) ? ch1 : ch2;
    const todayCi = todayCh === ch1 ? 0 : 1;

    // আজকের সিরিয়াল (ডেমো ডাক্তার)
    const tSalma = await appt(bp.salma, D, todayCh, today, { status: 'confirmed', serial: 1, note: 'থাইরয়েডের রিপোর্ট দেখাতে চাই, ক্লান্ত লাগে', createdAt: now - 2 * DAY });
    const tKarim = await appt(bp.karim, D, todayCh, today, { status: 'confirmed', serial: 2, note: 'প্রেসার বেশি থাকছে, মাথা ভার লাগে', createdAt: now - 2 * DAY + 3 * 3600 * 1000 });
    // ডেমো রোগীর আজ এই ডাক্তারের কাছে কিছু নেই — মালিক নিজে সিরিয়াল নেবেন (রোগীর পুরো যাত্রা)
    await appt(bp.rashed, D, todayCh, today, { status: 'confirmed', serial: 3, note: 'ডায়াবেটিসের ফলো-আপ, রিপোর্ট দেখাতে চাই', providerNote: 'আগের রিপোর্ট সাথে আনবেন', createdAt: now - DAY });
    // ৪ নম্বর রোগী সময় চেয়েছেন — ডাক্তার/সহকারী "ঠিক আছে" বা "পরে আসুন" চাপবেন (queue.js)
    await appt(bp.moriom, D, todayCh, today, {
      status: 'confirmed', serial: 4, note: 'হাঁটুতে ব্যথা, ঘুম কম হয়', createdAt: now - DAY + 3600 * 1000,
      extra: { lateMin: 20, lateNote: 'জ্যামে আটকে আছি', lateAt: now - 5 * MIN, lateStatus: 'asked', lateReplyAt: 0, lateCount: 1 },
    });
    await appt(bp.habib, D, todayCh, today, { status: 'pending', note: 'তিন দিন ধরে জ্বর আর গা ব্যথা', createdAt: now - 2 * 3600 * 1000 });
    await appt(bp.shirin, D, todayCh, today, { status: 'cancelled', note: 'মাথাব্যথা', createdAt: now - DAY, updatedAt: now - 5 * 3600 * 1000 });
    // কাল/সামনে
    const nextCh1 = nextOpen(ch1, today + DAY);
    await appt(bp.fatema, D, ch1, nextCh1, { status: 'pending', note: 'ডায়াবেটিস, পায়ে জ্বালাপোড়া', createdAt: now - 5 * 3600 * 1000 });
    const later = nextOpen(ch1, nextCh1 + DAY);
    await appt(bp.nasima, D, ch1, later, { status: 'confirmed', serial: 1, note: 'রিপোর্ট দেখাতে (আলট্রাসনো)', providerNote: 'খালি পেটে আসবেন না', createdAt: now - 6 * 3600 * 1000 });
    // আগের (রোগী তালিকা আর প্রেসক্রিপশন)
    const d2 = prevOpen(ch1, today - 2 * DAY);
    const aNasima = await appt(bp.nasima, D, ch1, d2, { status: 'confirmed', serial: 2, note: 'পেটে ব্যথা আর গ্যাস, বমি ভাব', createdAt: d2 - 2 * DAY });
    const dYest = prevOpen(ch1, today - DAY);
    await appt(bp.kamal, D, ch1, dYest, { status: 'declined', note: 'চেকআপ', providerNote: 'সেদিন সিরিয়াল পূর্ণ, অন্য দিন নিন', createdAt: dYest - DAY });
    const d7 = prevOpen(ch1, today - 7 * DAY);
    const aSalmaOld = await appt(bp.salma, D, ch1, d7, { status: 'completed', serial: 5, note: 'গলা ফোলা, ওজন বাড়ছে', createdAt: d7 - 2 * DAY });
    const d20 = prevOpen(ch1, today - 20 * DAY);
    const aMe20 = await appt(me, D, ch1, d20, { status: 'completed', serial: 2, note: 'সুগার বেশি, প্রেসারও বাড়ছে', createdAt: d20 - 2 * DAY });
    const d75 = prevOpen(ch1, today - 75 * DAY);
    const aMe75 = await appt(me, D, ch1, d75, { status: 'completed', serial: 4, note: 'প্রস্রাব বেশি হয়, খুব পিপাসা লাগে', createdAt: d75 - 3 * DAY });
    const d10 = prevOpen(ch1, today - 10 * DAY);
    await appt(bp.karim, D, ch1, d10, { status: 'completed', serial: 3, note: 'প্রেসারের ওষুধ শেষ', createdAt: d10 - DAY });

    // ডেমো রোগীর অন্য ডাক্তারের কাছে: নিশ্চিত আর অপেক্ষায় (সামনে), উত্তর আসেনি, না করা, বাতিল
    const mah = docs['rx-doc-mahbub'];
    const mDate = nextOpen(mah.ch[0], today + 3 * DAY);
    await appt(me, mah, mah.ch[0], mDate, {
      status: 'confirmed', serial: 2, note: 'বুকে চাপ লাগে, সিঁড়ি ভাঙলে হাঁপিয়ে যাই', providerNote: 'ইসিজি রিপোর্ট সাথে আনবেন', createdAt: now - 20 * 3600 * 1000,
    });
    const mOld = prevOpen(mah.ch[0], today - 40 * DAY);
    await appt(me, mah, mah.ch[0], mOld, { status: 'expired', note: 'ইসিজি রিপোর্ট দেখাতে', createdAt: mOld - 3 * DAY, updatedAt: mOld + DAY });
    const kam = docs['rx-doc-kamrul'];
    await appt(me, kam, kam.ch[0], nextOpen(kam.ch[0], today + 2 * DAY), { status: 'pending', note: 'ছেলের জ্বর আর কাশি (ছেলের জন্য)', createdAt: now - 3 * 3600 * 1000 });
    const kOld = prevOpen(kam.ch[0], today - 12 * DAY);
    await appt(me, kam, kam.ch[0], kOld, { status: 'declined', note: 'ছেলের জ্বর আর কাশি (ছেলের জন্য)', providerNote: 'সেদিন সিরিয়াল পূর্ণ, অন্য দিন নিন', createdAt: kOld - 2 * DAY });
    const nas = docs['rx-doc-nasrin'];
    const nOld = prevOpen(nas.ch[0], today - 30 * DAY);
    await appt(me, nas, nas.ch[0], nOld, {
      status: 'cancelled', cancelledBy: 'system', note: 'স্ত্রীর নিয়মিত চেকআপ', providerNote: 'ডাক্তার চেম্বার বা বার বদলেছেন', createdAt: nOld - 4 * DAY,
    });
    // অন্য ডাক্তারদের কিছু বুকিং (তালিকা খালি না দেখায়)
    await appt(bp.shirin, nas, nas.ch[0], nextOpen(nas.ch[0], today + DAY), { status: 'confirmed', serial: 1, note: 'গর্ভকালীন চেকআপ', createdAt: now - 8 * 3600 * 1000 });
    await appt(bp.habib, mah, mah.ch[0], prevOpen(mah.ch[0], today - 9 * DAY), { status: 'completed', serial: 2, note: 'বুকে ব্যথা', createdAt: now - 12 * DAY });

    for (const [sk, n] of serialMax) await set(sk, { n, at: now - 3600 * 1000 });

    // আজকের সিরিয়াল চলছে (queue.js): ১ নম্বর দেখা শেষ, এখন ২ নম্বর; ডাক্তার গড়ে ~৮ মিনিট নেন।
    // মালিক নিজে আজ সিরিয়াল নিলে (৫ নম্বর) তাঁর কার্ডে "এখন চলছে", "আগে কতজন", আনুমানিক সময় আসে।
    const qKey = md5key(todayCh.hospitalId || todayCh.hospitalName || '');
    await set(`queues/${doc.uid}_${today}_${qKey}`, {
      providerUid: doc.uid, date: today, chamberKey: todayCh.hospitalId || todayCh.hospitalName || '', key: qKey,
      started: true, current: 2, pos: 2, done: [1], paused: false, pausedAt: 0, finished: false,
      startedAt: now - 16 * MIN, lastAt: now - 8 * MIN, advances: [now - 16 * MIN, now - 8 * MIN], updatedAt: now - 8 * MIN,
    });
    await set(`providers/${doc.uid}`, { queueStats: { [qKey]: { avg: 8, n: 40, at: now - 8 * MIN } } }, { merge: true });

    // ---------------------------------------------------------------
    // এন্ট্রি করা রোগী (সহকারী এন্ট্রি করেছেন — অ্যাপে অ্যাকাউন্ট নেই)
    // ---------------------------------------------------------------
    const rxPat = async (id, name, age, gender, phone, createdAt, lastRxAt) => {
      const ph = K.normalizeBd(phone);
      await set(`rxPatients/${id}`, {
        doctorUid: doc.uid, name, age, gender, phone: K.localBd(ph), phoneKey: P.phoneKey(phone), linkedUid: null,
        createdBy: asst.uid, createdAt, lastRxAt,
      });
      return id;
    };
    const P1 = await rxPat('demo-rx-pat-jamila', 'ডেমো রোগী জমিলা খাতুন', 62, 'female', '01700000321', now - 12 * DAY, now - 10 * DAY);
    await rxPat('demo-rx-pat-malek', 'ডেমো রোগী আব্দুল মালেক', 70, 'male', '01700000322', now - 3 * 3600 * 1000, 0);
    const P3 = await rxPat('demo-rx-pat-paper', me.name, me.age, me.gender, me.phone, now - 31 * DAY, now - 30 * DAY);

    // ---------------------------------------------------------------
    // প্রেসক্রিপশন
    // ---------------------------------------------------------------
    const doctorBlock = (ci) => {
      const ch = ci == null ? null : docChambers[ci];
      return {
        name: DOC_NAME, degrees: DOC_DEGREES, specialties: ['medicine', 'endocrinology'], regNo: DOC_REG,
        chamber: ch ? { name: ch.hospitalNameBn || ch.hospitalName || '', address: [ch.address, ch.district].filter(Boolean).join(', '), phone: ch.serialPhone || '' } : null,
      };
    };
    const rxs = {};
    const rx = async (key, o) => {
      const id = `demo-rx-${key}`;
      const ci = o.ci === undefined ? 0 : o.ci;
      const followUpDate = o.followUpDays ? P.dhakaToday(o.createdAt) + o.followUpDays * DAY : null;
      const ch = ci == null ? null : docChambers[ci];
      const data = {
        code: o.code || randCode(), status: o.status || 'active', createdAt: o.createdAt, doctorUid: doc.uid, doctor: doctorBlock(ci),
        patient: { name: o.p.name, age: o.p.age, gender: o.p.gender }, patientUid: o.patientUid === undefined ? o.p.uid || null : o.patientUid,
        rxPatientId: o.rxPatientId || null, appointmentId: o.appointmentId || null,
        complaints: o.complaints || '', findings: findings(o.findings), diagnosis: o.diagnosis || '', medicines: o.medicines || [], tests: o.tests || [],
        testPlace: o.tests && o.tests.length ? labName : '', testPlaceId: null, advice: o.advice || '',
        followUpDate, followUpBookDate: followUpDate && ch && ch.appointments ? nextOpen(ch, followUpDate) : null,
        followUpNote: o.followUpDays ? o.followUpNote || '' : '', chamberIndex: ci,
        ...(o.status === 'cancelled' ? { cancelledAt: o.createdAt + 15 * MIN } : {}),
      };
      if (o.dispensed) {
        data.dispensed = o.dispensed.map((x) => ({ by: shopU.uid, name: shopName, at: x.at, items: x.items }));
        data.refillAt = RX.refillDate(data, now);
      }
      rxs[key] = { id, ...data };
      await set(`prescriptions/${id}`, data);
      return rxs[key];
    };

    const rxA = await rx('a', {
      p: me, code: CODES.A, createdAt: at(d75, '17:45'), appointmentId: aMe75,
      complaints: 'বারবার প্রস্রাব, পিপাসা, ক্লান্তি — ২ মাস', findings: { bp: '140/90', pulse: '84', weight: '78 kg' },
      diagnosis: 'Type 2 Diabetes Mellitus (newly diagnosed)',
      medicines: [med('Comet', 'Tablet', '500 mg', '1+0+1', 30, 'after'), med('Secrin', 'Tablet', '1 mg', '1+0+0', 30, 'before', 'নাস্তার ১৫ মিনিট আগে')],
      tests: ['HbA1c', 'FBS', 'Lipid profile'],
      advice: 'চিনি ও মিষ্টি বাদ দিন। প্রতিদিন ৩০ মিনিট হাঁটুন। ভাত কম, সবজি বেশি খান।', followUpDays: 30, followUpNote: 'রিপোর্ট নিয়ে আসবেন',
      dispensed: [{ at: at(d75, '18:30'), items: [{ i: 0, qty: 60 }, { i: 1, qty: 30 }] }],
    });
    const rxC = await rx('c', {
      p: me, code: CODES.C, status: 'cancelled', createdAt: at(d20, '17:20'), appointmentId: aMe20,
      diagnosis: 'Type 2 DM', medicines: [med('Comet', 'Tablet', '500 mg', '1+1+1', 30, 'after')], advice: 'ভুল মাত্রা — বাতিল করে নতুন দেওয়া হয়েছে',
    });
    const rxB = await rx('b', {
      p: me, code: CODES.B, createdAt: at(d20, '17:35'), appointmentId: aMe20,
      complaints: 'সুগার নিয়ন্ত্রণে নেই, মাথা ভার লাগে', findings: { bp: '150/95', pulse: '88', weight: '77 kg', other: 'RBS 11.2 mmol/L' },
      diagnosis: 'Type 2 DM, Dyslipidaemia, Hypertension',
      medicines: [
        med('Comet', 'Tablet', '500 mg', '1+0+1', 30, 'after'),
        med('Rosuva', 'Tablet', '10 mg', '0+0+1', 30, 'after'),
        med('Bislol', 'Tablet', '2.5 mg', '1+0+0', 30, 'before'),
        med('Napa', 'Tablet', '500 mg', '1+1+1', 3, 'after', 'জ্বর বা ব্যথা থাকলে'),
      ],
      tests: ['Lipid profile', 'S. Creatinine', 'CBC', 'ECG'],
      advice: 'লবণ কম খান। নিয়মিত প্রেসার আর সুগার মাপুন। ৩০ দিন পর রিপোর্ট নিয়ে আসবেন।', followUpDays: 30, followUpNote: 'রিপোর্ট নিয়ে আসবেন',
      dispensed: [{ at: at(d20, '18:20'), items: [{ i: 0, qty: 30 }, { i: 1, qty: 30 }] }],
    });
    const rxS1 = await rx('salma-old', {
      p: bp.salma, createdAt: at(d7, '17:30'), appointmentId: aSalmaOld,
      complaints: 'গলা ফোলা, ওজন বাড়ছে, শীত বেশি লাগে', findings: { bp: '120/80', weight: '68 kg' }, diagnosis: 'Hypothyroidism (suspected)',
      medicines: [med('Thyrox', 'Tablet', '50 mcg', '1+0+0', 30, 'before', 'খালি পেটে')], tests: ['TSH', 'CBC'], advice: 'রিপোর্ট দেখিয়ে ওষুধের মাত্রা ঠিক হবে।',
    });
    const rxT1 = await rx('salma-today', {
      p: bp.salma, createdAt: now - 50 * MIN, appointmentId: tSalma, ci: todayCi,
      complaints: 'ক্লান্তি কমেছে', diagnosis: 'Hypothyroidism', medicines: [med('Thyrox', 'Tablet', '75 mcg', '1+0+0', 60, 'before', 'খালি পেটে')],
      tests: ['TSH'], advice: '৬ সপ্তাহ পর TSH করাবেন।', followUpDays: 45,
    });
    const rxK = await rx('karim-today', {
      p: bp.karim, code: CODES.K, createdAt: now - 30 * MIN, appointmentId: tKarim, ci: todayCi,
      complaints: 'মাথা ভার, ঘাড়ে ব্যথা', findings: { bp: '160/100', pulse: '90' }, diagnosis: 'Essential Hypertension',
      medicines: [med('Amdocal', 'Tablet', '5 mg', '0+0+1', 30, 'after'), med('Losart', 'Tablet', '50 mg', '1+0+0', 30, 'after'), med('Ecosprin', 'Tablet', '75 mg', '0+1+0', 30, 'after')],
      tests: ['Lipid profile', 'ECG'], advice: 'লবণ, গরুর মাংস, ভাজাপোড়া কম। প্রতিদিন প্রেসার মাপুন।', followUpDays: 15,
    });
    const rxD = await rx('nasima', {
      p: bp.nasima, createdAt: at(d2, '18:10'), appointmentId: aNasima,
      complaints: 'পেটে ব্যথা, গ্যাস, বমি ভাব — ১ সপ্তাহ', diagnosis: 'Acid peptic disease',
      medicines: [med('Seclo', 'Capsule', '20 mg', '1+0+1', 14, 'before'), med('Motigut', 'Tablet', '10 mg', '1+1+1', 7, 'before'), med('Antacid Plus', 'Suspension', '', '2 চামচ ৩ বার', 7, 'after')],
      tests: ['USG of whole abdomen'], advice: 'ঝাল-তেল কম খাবেন, রাতে দেরি করে খাবেন না।',
    });
    const rxKarimOld = await rx('karim-old', {
      p: bp.karim, createdAt: at(d10, '18:00'), appointmentId: null,
      diagnosis: 'Hypertension', medicines: [med('Amdocal', 'Tablet', '5 mg', '0+0+1', 0, 'after')], tests: ['CBC', 'RBS'], advice: 'প্রেসারের ওষুধ চলবে।',
    });
    await rx('jamila', {
      p: { name: 'ডেমো রোগী জমিলা খাতুন', age: 62, gender: 'female', uid: null }, rxPatientId: P1, patientUid: null, createdAt: now - 10 * DAY,
      complaints: 'হাঁটুতে ব্যথা, সকালে জমে থাকে', diagnosis: 'Osteoarthritis knee',
      medicines: [med('Calbo', 'Tablet', '500 mg', '0+1+0', 30, 'after'), med('Naprosyn', 'Tablet', '250 mg', '1+0+1', 7, 'after', 'ব্যথা থাকলে')],
      advice: 'সিঁড়ি কম ভাঙবেন, ওজন কমান।',
    });
    const rxPaper = await rx('paper', {
      p: me, code: CODES.PAPER, rxPatientId: P3, patientUid: null, createdAt: now - 30 * DAY, ci: 1,
      complaints: 'সর্দি-কাশি, হালকা জ্বর', diagnosis: 'Acute URTI',
      medicines: [med('Fexo', 'Tablet', '120 mg', '0+0+1', 7, 'after'), med('Napa', 'Tablet', '500 mg', '1+1+1', 3, 'after', 'জ্বর থাকলে')],
      tests: ['RBS'], advice: 'গরম পানি খাবেন, বিশ্রাম নিন।',
    });
    // বাসা থেকে নমুনার অনুরোধের প্রেসক্রিপশন (ফাতেমা — ডায়াবেটিস)
    const rxF = await rx('fatema', {
      p: bp.fatema, createdAt: now - 3 * 3600 * 1000, appointmentId: null, ci: null,
      complaints: 'পায়ে জ্বালাপোড়া, পিপাসা বেশি', diagnosis: 'Type 2 DM (follow-up)',
      medicines: [med('Comet', 'Tablet', '500 mg', '1+0+1', 30, 'after')], tests: ['HbA1c', 'FBS', 'Lipid profile'],
      advice: 'খালি পেটে নমুনা দেবেন।',
    });
    void rxC; void rxPaper;

    // ---------------------------------------------------------------
    // ফার্মেসি: পাঠানো প্রেসক্রিপশন, অনলাইন অর্ডার, "চেয়েও পাননি", স্টক
    // ---------------------------------------------------------------
    const order = (id, r, p, o) => set(`rxOrders/${id}`, {
      rxId: r.id, code: r.code, pharmacyId: shopU.uid, pharmacyName: shopName, patientUid: p.uid, patientName: r.patient.name,
      status: o.status, mode: o.mode || 'shop', note: o.note || '', createdAt: o.createdAt, ...o.extra,
    });
    await order('demo-rx-order-me-done', rxB, me, { status: 'done', createdAt: at(d20, '18:05'), extra: { doneAt: at(d20, '18:20'), missed: ['Bislol'] } });
    await order('demo-rx-order-nasima', rxD, bp.nasima, { status: 'sent', createdAt: now - 40 * MIN, note: 'বাকিটা পরে নেব' });
    await order('demo-rx-order-karim-online', rxK, bp.karim, {
      status: 'sent', mode: 'online', createdAt: now - 20 * MIN, note: 'সন্ধ্যার পরে দিলে ভালো',
      extra: { address: 'ডেমো বাসা ১২, রোড ৩, কাচিঝুলি, ময়মনসিংহ', phone: bp.karim.phone, deliveryFee },
    });
    await order('demo-rx-order-salma-back', rxS1, bp.salma, {
      status: 'withdrawn', createdAt: at(d7, '18:00'), extra: { withdrawReason: 'not_available', missed: ['Thyrox'], updatedAt: at(d7, '18:15') },
    });
    if (!missedS.exists) {
      const items = {};
      for (const [n, count, ago] of [['Thyrox', 2, 7], ['Bislol', 1, 20], ['Ecosprin', 1, 33]]) items[missKey(n)] = { name: n, count, lastAt: now - ago * DAY };
      await set(`pharmacyMissed/${shopU.uid}`, { items, updatedAt: now - 7 * DAY });
    }
    if (!stockS.exists) {
      const names = ['Comet', 'Rosuva', 'Napa', 'Napa Extra', 'Seclo', 'Motigut', 'Amdocal', 'Losart', 'Secrin', 'Fexo',
        'Calbo', 'Ace', 'Sergel', 'Maxpro', 'Alatrol', 'Montene', 'Azithrocin', 'Cef-3', 'Monas', 'Neurobion']
        .sort((a, b) => a.localeCompare(b));
      await set(`pharmacyStock/${shopU.uid}`, { names, keys: names.map(nameKey), updatedAt: now - 3 * DAY });
    }

    // ---------------------------------------------------------------
    // প্যাথলজি: পরীক্ষা আর রিপোর্ট, ডাক্তারের রিপোর্ট দেখা
    // ---------------------------------------------------------------
    // চালু অর্ডারে পাঠানোর সময়ের দাম (rx_send_lab-এর মতো) — services ভাগের দামের তালিকা থেকে
    await LT.load();
    const labDoc = lab || { homeCollection: true };
    const priceX = priceS.exists ? priceS.data() : null;
    const est = (tests) => LP.snapshot(LP.estimate(tests, priceX, labDoc));
    const labOrder = (id, r, p, o) => set(`labOrders/${id}`, {
      rxId: r.id, code: r.code, labId: labU.uid, labName, patientUid: p.uid || null, patientName: r.patient.name, patientAge: r.patient.age,
      patientGender: r.patient.gender, doctorUid: doc.uid, doctorName: DOC_NAME, tests: o.tests, status: o.status, createdAt: o.createdAt,
      ...(['sent', 'received'].includes(o.status) && est(o.tests) ? { prices: est(o.tests) } : {}),
      ...(o.home ? { home: o.home } : {}),
      ...(o.report ? { report: { items: o.report, note: o.note || '', photos: [], reportedAt: o.reportedAt }, reportedAt: o.reportedAt } : {}),
      ...(o.updatedAt ? { updatedAt: o.updatedAt } : {}),
    });
    const review = (labOrderId, r, p, o) => set(`rxReviews/${labOrderId}`, {
      rxId: r.id, labOrderId, doctorUid: doc.uid, doctorName: DOC_NAME, patientUid: p.uid, patientName: r.patient.name, labName,
      tests: o.tests, status: o.status, fee: '৩০০ টাকা', noFee: false, payNumber: '01700000202', payMethods: ['bkash', 'nagad'],
      createdAt: o.createdAt, updatedAt: o.updatedAt || o.createdAt, ...o.extra,
    });

    const l1At = at(d75, '17:00') + 2 * DAY;
    const l1Tests = ['HbA1c', 'FBS', 'Lipid profile'];
    await labOrder('demo-rx-lab-me-old', rxA, me, {
      tests: l1Tests, status: 'reported', createdAt: at(d75, '19:00'), reportedAt: l1At, note: 'খালি পেটে নমুনা নেওয়া হয়েছে',
      report: [
        ...reportRows('HbA1c', 'male', { hba1c: '8.4' }),
        ...reportRows('FBS', 'male', { glu_f: '9.1' }),
        ...reportRows('Lipid profile', 'male', { chol: '236', tg: '210', hdl: '36', ldl: '152' }),
      ],
    });
    await review('demo-rx-lab-me-old', rxA, me, {
      tests: l1Tests, status: 'done', createdAt: l1At, updatedAt: l1At + DAY,
      extra: { method: 'bkash', trxId: 'DEMO5H2K8M', paidAt: l1At + 6 * 3600 * 1000, doneAt: l1At + DAY, comment: 'সুগার আর কোলেস্টেরল বেশি। ওষুধ চালিয়ে যান, ৩০ দিন পর আবার দেখাবেন।', newRxId: null },
    });
    const l2At = at(d20, '17:00') + 4 * DAY;
    await labOrder('demo-rx-lab-me-lipid', rxB, me, {
      tests: ['Lipid profile', 'S. Creatinine'], status: 'reported', createdAt: at(d20, '19:00'), reportedAt: l2At,
      report: [
        ...reportRows('Lipid profile', 'male', { chol: '198', tg: '160', hdl: '41', ldl: '118' }),
        ...reportRows('S. Creatinine', 'male', { creat: '1.1' }),
      ],
    });
    await review('demo-rx-lab-me-lipid', rxB, me, {
      tests: ['Lipid profile', 'S. Creatinine'], status: 'claimed', createdAt: l2At, updatedAt: now - 3 * 3600 * 1000,
      extra: { method: 'bkash', trxId: 'DEMO7K3P9Q' },
    });
    // নমুনা নেওয়া হয়েছে — ল্যাব রিপোর্ট লিখবে
    await labOrder('demo-rx-lab-me-cbc', rxB, me, { tests: ['CBC'], status: 'received', createdAt: now - DAY, updatedAt: now - DAY + 3600 * 1000 });
    // নতুন অনুরোধ — ল্যাব "নমুনা নিয়েছি" চাপবে
    await labOrder('demo-rx-lab-salma-new', rxT1, bp.salma, { tests: ['TSH'], status: 'sent', createdAt: now - 25 * MIN });
    // আগের রোগীর রিপোর্ট — ডাক্তারকে দেখতে হবে (টাকা মিলেছে)
    const l5At = at(d7, '17:00') + 2 * DAY;
    await labOrder('demo-rx-lab-salma-tsh', rxS1, bp.salma, {
      tests: ['TSH', 'CBC'], status: 'reported', createdAt: at(d7, '19:00'), reportedAt: l5At,
      report: [
        ...reportRows('TSH', 'female', { tsh: '9.8' }),
        ...reportRows('CBC', 'female', { hb: '10.9', esr: '28', wbc: '7.2', neut: '62', lymph: '30', mono: '5', eos: '3', baso: '0', plt: '265' }),
      ],
    });
    await review('demo-rx-lab-salma-tsh', rxS1, bp.salma, {
      tests: ['TSH', 'CBC'], status: 'paid', createdAt: l5At, updatedAt: now - 5 * 3600 * 1000, extra: { method: 'nagad', trxId: 'DEMO2N6R4T', paidAt: now - 5 * 3600 * 1000 },
    });
    await labOrder('demo-rx-lab-karim', rxKarimOld, bp.karim, {
      tests: ['CBC', 'RBS'], status: 'reported', createdAt: at(d10, '19:00'), reportedAt: at(d10, '19:00') + DAY,
      report: [...reportRows('RBS', 'male', { glu: '6.8' }), ...reportRows('CBC', 'male', { hb: '14.1', esr: '12', wbc: '8.1', plt: '240' })],
    });
    await review('demo-rx-lab-karim', rxKarimOld, bp.karim, { tests: ['CBC', 'RBS'], status: 'unpaid', createdAt: at(d10, '19:00') + DAY });
    // বাসা থেকে নমুনা — কাল সকাল ৭–৯, প্যাথলজি "সময় নিশ্চিত করুন" চাপবে (বা "বাসায় যেতে পারব না")
    await labOrder('demo-rx-lab-fatema-home', rxF, bp.fatema, {
      tests: ['HbA1c', 'FBS', 'Lipid profile'], status: 'sent', createdAt: now - 15 * MIN,
      home: {
        address: 'ডেমো বাসা ৫, স্কুল রোড, কাচিঝুলি, ময়মনসিংহ', phone: bp.fatema.phone, date: today + DAY, slot: 'early',
        note: 'খালি পেটে থাকব', fee: priceX && Number.isInteger(priceX.homeFee) ? priceX.homeFee : null, status: 'requested', requestedAt: now - 15 * MIN,
      },
    });
    // ল্যাব বাতিল করেছে
    await labOrder('demo-rx-lab-nasima-usg', rxD, bp.nasima, { tests: ['USG of whole abdomen'], status: 'cancelled', createdAt: at(d2, '19:00'), updatedAt: at(d2, '19:00') + 3 * 3600 * 1000 });

    // এই সার্ভারের মেমোরির তালিকা নতুন করে (ডাক্তার, ফার্মেসি, প্যাথলজির তালিকা সাথে সাথে দেখা যায়)
    const M = require('../memcache');
    ['providers:', 'home:', 'pharm:', 'labs:'].forEach((k) => M.clear(k));
  },
};
