// ডেমো ডেটা — ভাগ: services (দেখুন index.js)
// ফার্মেসি (তালিকা, স্টক, ওষুধ খোঁজা, অনলাইন অর্ডার), প্যাথলজি (পরীক্ষা ও দামসহ), অ্যাম্বুলেন্স, বাসায় সেবা (নার্স/ডাক্তার),
// রেটিং, রিপোর্ট আর মালিকের অনুমোদনের জন্য অপেক্ষার তালিকা।
//
// রক্তের ভাগ (blood) আগে চলে, প্রেসক্রিপশনের ভাগ (rx) পরে। অনলাইন অর্ডার প্রেসক্রিপশন ছাড়া হয় না
// (rxOrders → prescriptions), তাই অর্ডারের জন্য এখানে ৪টা আলাদা প্রেসক্রিপশন (demo-svc-rx-*) বানানো হয়।
// সব নাম/নম্বর বানানো; ফোন 0170000015x–0170000019x।
const crypto = require('crypto');
const R = require('../rules');
const DISTRICTS = require('../districts');
const DRUGS = require('../drugs.json');

const DISTRICT = DISTRICTS.find((d) => d.en === 'Gazipur').bn;
const SADAR = 320; // গাজীপুর সদর
const KALIAKAIR = 318; // কালিয়াকৈর
const SREEPUR = 321; // শ্রীপুর
const HOUR = 3600 * 1000;
const MIN = 60 * 1000;

// কাগজের ছবির জায়গায় একটা ছোট "DEMO DOCUMENT" ছবি (প্যানেলে অনুমোদনের আগে দেখা যায়)
const DOC_IMG = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABcQERQRDhcUEhQaGBcbIjklIh8fIkYyNSk5UkhXVVFIUE5bZoNvW2F8Yk5QcptzfIeLkpSSWG2grJ+OqoOPko3/wAALCADcAWgBAREA/8QAGgABAAMBAQEAAAAAAAAAAAAAAAIDBAUBBv/EAEIQAAEDAgIHBAgEBAQHAQAAAAEAAgMEEQUSFSExNYKy0RNBU6IUIiVRYXGB8DKRobEGQnLBIzM0UhYkNkNic/FF/9oACAEBAAA/APpZ5o6eJ0srsrG7Ta6yaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8juiaZoPH8jui1wTR1ETZYnZmO2G1lkxvdU3DzBZcMw2kqMPillizPde5zEd5+K1aGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqaGoPA87uqyYphtJT4fJLFFle21jmJ7x8VrwTdUPFzFMb3VNw8wTBN1Q8XMVvRERERERERERERERERERERERERERYMb3VNw8wTBN1Q8XMUx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';

/** rxnet.js-এর মতো: ওষুধের নাম → খোঁজার চাবি */
const nameKey = (n) => String(n || '').trim().toLowerCase().replace(/\s+/g, ' ');
/** rxnet.js-এর "দিতে পারেননি" তালিকার চাবি */
const missKey = (n) => `k_${crypto.createHash('sha1').update(nameKey(n)).digest('hex').slice(0, 16)}`;
/** sellerstats.js-এর মতো: কে রেটিং দিয়েছেন তার গোপন সংকেত */
const raterKey = (collection, id, uid) => crypto.createHash('sha256').update(`${collection}:${id}:${uid}`).digest('hex').slice(0, 16);

function drug(name, generic, form) {
  const r = DRUGS.d.find((x) => x[0] === name && x[1].includes(generic) && DRUGS.f[x[2]] === form);
  return r ? { name: r[0], generic: r[1], form: DRUGS.f[r[2]] || '', company: DRUGS.c[r[3]] || '' } : { name, generic, form, company: '' };
}

/** গত ৩০ দিনের দৈনিক ফোনের গণনা (স্থির, দেখতে স্বাভাবিক) */
function dailyCalls(now, base, seed) {
  const d = {};
  let total = 0;
  for (let i = 0; i < 30; i++) {
    const n = Math.max(0, Math.round(base + 2 * Math.sin((i + seed) * 1.3) + ((i * 7 + seed) % 3) - 1));
    if (n) d[R.dhakaDayKey(now - i * 86400000)] = n;
    total += n;
  }
  return { d, total };
}

const at3 = (p) => ({ lat: Math.round(p.lat * 1000) / 1000, lng: Math.round(p.lng * 1000) / 1000 });

module.exports = {
  async seed(ctx) {
    const { u, now, day } = ctx;
    const P = u.patient.uid;
    const loc = (dx, dy) => ctx.near(dx, dy);

    // ---------- সাহায্যকারী ----------
    const docs = async (kind, id, ownerUid, types, at) => {
      const have = {};
      for (const t of types) {
        await ctx.set(`verifyDocs/${kind}_${id}_${t}`, { kind, listingId: id, type: t, ownerUid, data: DOC_IMG, size: DOC_IMG.length, at });
        have[t] = at;
      }
      return have;
    };
    const verification = (checks, at) => ({ by: 'demo', byEmail: 'demo@lifolo-demo.test', at, checks });
    const snapshot = (x) => require('../listingreview').snapshot(x);

    // ব্যবসার ডেমো অ্যাকাউন্টের প্রোফাইল: রক্তের ভাগ আগেই লেখে (নাম, ফোন, জেলা) — না থাকলে শুধু বাকি ঘরগুলো
    const { db } = require('../firebase');
    const profile = async (key, phone) => {
      const s = await db.doc(`users/${u[key].uid}`).get();
      const x = s.exists ? s.data() : {};
      const c = { lat: Math.round(ctx.CENTER.lat * 100) / 100, lng: Math.round(ctx.CENTER.lng * 100) / 100 };
      const add = {
        ...(x.name ? {} : { name: u[key].name }),
        ...(x.phone ? {} : { phone }),
        ...(x.lang ? {} : { lang: 'bn' }),
        ...(x.placeName ? {} : { placeName: DISTRICT, lat: c.lat, lng: c.lng, geohash: require('../geo').hash(c.lat, c.lng), locationSource: 'district' }),
      };
      if (Object.keys(add).length) await ctx.set(`users/${u[key].uid}`, add, { merge: true });
      return { ...x, ...add };
    };

    // =====================================================================
    // ফার্মেসি
    // =====================================================================
    const PH = u.pharmacy.uid;
    const phLoc = loc(0.3, 0.2);
    await profile('pharmacy', '01700000150');
    const STOCK = ['Napa', 'Ace', 'Seclo', 'Losectil', 'Maxpro', 'Sergel', 'Pantonix', 'Fexo', 'Alatrol', 'Tofen', 'Montene', 'Monas',
      'Ceevit', 'Calbo', 'Xinc', 'Comet', 'Rosuva', 'Ecosprin', 'Bislol', 'Amdocal', 'Azithrocin', 'Cef-3', 'Flagyl', 'Orsaline-N'];
    const pharmacy = async (id, x) => {
      const created = x.createdAt || now - 60 * day;
      const approved = x.status === 'approved';
      const have = await docs('pharmacy', id, id, x.docTypes || ['license', 'front'], created);
      const data = {
        name: x.name, licenseNo: x.licenseNo, phone: x.phone, district: DISTRICT, upazilaId: x.upazilaId || SADAR, address: x.address,
        ...at3(x.loc), open24: !!x.open24, openAt: x.open24 ? '' : x.openAt, closeAt: x.open24 ? '' : x.closeAt, closedDays: x.closedDays || [],
        delivery: !!x.delivery, deliveryNote: x.deliveryNote || '', discount: x.discount || 0, updatedAt: created,
      };
      await ctx.set(`pharmacies/${id}`, {
        ...data,
        ownerUid: id,
        status: x.status,
        docsHave: have,
        createdAt: created,
        ...(approved ? {
          reviewedAt: created + day, reviewedBy: 'demo', approvedName: x.name, lastApproved: snapshot(data),
          verification: verification(['license_no', 'license_name', 'license_addr', 'license_valid', 'license_seal', 'signboard', 'phone'], created + day),
          ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
          // দিকভিত্তিক (ব্যবহার / ঠিক ওষুধ ও ন্যায্য দাম / ডেলিভারির দ্রুততা) — বিস্তারিতে আলাদা লাইনে
          ...(x.aspects ? { aspects: x.aspects } : {}),
        } : {}),
        ...(x.closedUntil ? { closedUntil: x.closedUntil } : {}),
        ...(x.online ? { online: { ...x.online, updatedAt: created + 2 * day } } : {}),
        ...(x.shopCode ? { shopCode: x.shopCode } : {}),
      });
      if (x.stock) {
        const names = [...x.stock].sort((a, b) => a.localeCompare(b));
        await ctx.set(`pharmacyStock/${id}`, { names, keys: names.map(nameKey), updatedAt: now - 3 * day });
      }
      if (approved) {
        const c = dailyCalls(now, x.callsPerDay || 1, id.length);
        await ctx.set(`pharmacyStats/${id}`, { d: c.d, total: c.total + 20, callers: Math.round(c.total * 0.7) });
      }
    };

    // ডেমো ফার্মেসি (লগইন করা যায়) — অনুমোদিত, অনলাইনে বিক্রি চালু, স্টক আছে।
    // ২৪ ঘণ্টা খোলা (rx ভাগের মতো): আগে সকাল ৬টা–রাত ২টা ছিল, তাই রাত ২–৬টায় (ঢাকা) দোকান "বন্ধ" — ডেমো রোগীর
    // "ওষুধ খুঁজুন" ডেমো দোকানে পৌঁছাত না (রাতে পরীক্ষা করলে যাত্রা ভাঙত, demo-pharmacy.test-ও)।
    // তাই "২৪ ঘণ্টা খোলা" ছাঁকনিতে এটা আর "ডেমো ২৪ ঘণ্টা ফার্মেসি" — বাকি ডেমো দোকান ২৪ ঘণ্টা নয় (open24 দেবেন না)।
    await pharmacy(PH, {
      name: 'ডেমো লাইফ ফার্মেসি', licenseNo: 'DEMO-DL-1501', phone: '01700000150', loc: phLoc,
      address: 'শিববাড়ি মোড়, হাসপাতাল রোড, জয়দেবপুর, গাজীপুর', open24: true,
      delivery: true, deliveryNote: 'শিববাড়ি, জয়দেবপুর, চান্দনা চৌরাস্তা — ৩০ টাকা', discount: 10, status: 'approved',
      docTypes: ['license', 'front', 'pharmacist'], createdAt: now - 90 * day, ratingSum: 54, ratingCount: 12, callsPerDay: 3,
      aspects: { behaviour: { sum: 56, count: 12 }, price: { sum: 44, count: 10 }, delivery: { sum: 29, count: 7 } },
      online: { enabled: true, radiusKm: 10, fee: '৩০ টাকা', note: 'সকাল ৮টা – রাত ১১টা ডেলিভারি' }, shopCode: 'DEMQKP',
      stock: STOCK,
    });
    // "দিতে পারেননি" তালিকা (ক্রেতা চেয়েও পাননি)
    const missed = [['Mixtard 30', 4, 2], ['Thyrox 50', 2, 5], ['Nexum 20', 1, 9]];
    await ctx.set(`pharmacyMissed/${PH}`, {
      items: Object.fromEntries(missed.map(([n, c, d]) => [missKey(n), { name: n, count: c, lastAt: now - d * day }])),
      updatedAt: now - 2 * day,
    });

    // পেছনের ফার্মেসি (লগইন ছাড়া মালিক)
    const ph24 = await ctx.extraUser('svc-ph-24h', { name: 'ডেমো ২৪ ঘণ্টা ফার্মেসির মালিক', phone: '01700000151', placeName: DISTRICT });
    await pharmacy(ph24, {
      name: 'ডেমো ২৪ ঘণ্টা ফার্মেসি', licenseNo: 'DEMO-DL-1502', phone: '01700000151', loc: loc(0.8, -0.4),
      address: 'তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল গেট, জয়দেবপুর, গাজীপুর', open24: true, delivery: true, deliveryNote: 'রাতেও ডেলিভারি, ৫০ টাকা', discount: 5,
      status: 'approved', ratingSum: 38, ratingCount: 9, callsPerDay: 4,
      aspects: { behaviour: { sum: 37, count: 9 }, price: { sum: 26, count: 6 }, delivery: { sum: 17, count: 4 } },
      online: { enabled: true, radiusKm: 5, fee: '৫০ টাকা', note: '২৪ ঘণ্টা' },
      stock: ['Napa', 'Ace', 'Seclo', 'Maxpro', 'Fexo', 'Alatrol', 'Montene', 'Ceevit', 'Comet', 'Ecosprin', 'Orsaline-N', 'Flagyl'],
    });
    const phModel = await ctx.extraUser('svc-ph-model', { name: 'ডেমো নিউ মডেল ফার্মেসির মালিক', phone: '01700000152', placeName: DISTRICT });
    await pharmacy(phModel, {
      name: 'ডেমো নিউ মডেল ফার্মেসি', licenseNo: 'DEMO-DL-1503', phone: '01700000152', loc: loc(-0.6, 0.5),
      address: 'জয়দেবপুর বাজার, গাজীপুর', openAt: '09:00', closeAt: '22:00', closedDays: [5], discount: 12,
      status: 'approved', ratingSum: 21, ratingCount: 6, callsPerDay: 2,
      aspects: { behaviour: { sum: 22, count: 6 }, price: { sum: 13, count: 4 } },
      stock: ['Napa', 'Seclo', 'Sergel', 'Alatrol', 'Monas', 'Calbo', 'Bislol', 'Amdocal', 'Rosuva'],
    });
    const phSeba = await ctx.extraUser('svc-ph-seba', { name: 'ডেমো সেবা ড্রাগ হাউসের মালিক', phone: '01700000153', placeName: DISTRICT });
    await pharmacy(phSeba, {
      name: 'ডেমো সেবা ড্রাগ হাউস', licenseNo: 'DEMO-DL-1504', phone: '01700000153', loc: loc(1.2, 0.9),
      address: 'ছায়াবীথি মোড়, জয়দেবপুর, গাজীপুর', openAt: '08:00', closeAt: '23:00', delivery: true, deliveryNote: 'কাছাকাছি ফ্রি',
      // সাময়িক বন্ধ (সংস্কার) — সারাক্ষণ "বন্ধ" দেখায়
      closedUntil: now + 5 * day, status: 'approved', ratingSum: 12, ratingCount: 3,
    });
    const phKali = await ctx.extraUser('svc-ph-kaliakair', { name: 'ডেমো কালিয়াকৈর ফার্মেসির মালিক', phone: '01700000154', placeName: DISTRICT });
    await pharmacy(phKali, {
      name: 'ডেমো কালিয়াকৈর মেডিসিন কর্নার', licenseNo: 'DEMO-DL-1505', phone: '01700000154', loc: loc(-19.5, 8), upazilaId: KALIAKAIR,
      address: 'কালিয়াকৈর বাজার, গাজীপুর', openAt: '08:00', closeAt: '21:00', status: 'approved', ratingSum: 0, ratingCount: 0,
    });
    // অনুমোদনের অপেক্ষায় (মালিক ওয়েব প্যানেলে কাগজ দেখে অনুমোদন দেবেন)
    const phNew = await ctx.extraUser('svc-ph-new', { name: 'ডেমো আরোগ্য ফার্মেসির মালিক', phone: '01700000155', placeName: DISTRICT });
    await pharmacy(phNew, {
      name: 'ডেমো আরোগ্য ফার্মেসি', licenseNo: 'DEMO-DL-1506', phone: '01700000155', loc: loc(0.2, -0.9),
      address: 'জয়দেবপুর রেলগেট, গাজীপুর', openAt: '08:30', closeAt: '22:30', delivery: true, discount: 8,
      status: 'pending', docTypes: ['license', 'front'], createdAt: now - 1 * day,
    });

    // ---------- অনলাইন অর্ডার: প্রেসক্রিপশন (রোগী: ডেমো রোগী, ডাক্তার: ডেমো ডাক্তার) ----------
    const D = u.doctor;
    const rx = async (id, code, x) => {
      await ctx.set(`prescriptions/${id}`, {
        code,
        status: 'active',
        createdAt: x.createdAt,
        doctorUid: D.uid,
        doctor: { name: D.name, degrees: 'এমবিবিএস, এফসিপিএস (মেডিসিন) — ডেমো', specialties: ['medicine'], regNo: 'DEMO-A-10001', chamber: null },
        patient: { name: u.patient.name, age: 45, gender: 'male' },
        patientUid: P,
        rxPatientId: null,
        appointmentId: null,
        complaints: x.complaints,
        findings: { bp: x.bp || '১৩০/৮৫', pulse: '৮০/মিনিট', temp: '', weight: '৭০ কেজি', spo2: '', other: '' },
        diagnosis: x.diagnosis,
        medicines: x.medicines,
        tests: [],
        testPlace: '',
        testPlaceId: null,
        advice: x.advice || '',
        followUpDate: null,
        followUpBookDate: null,
        followUpNote: '',
        chamberIndex: null,
        ...(x.dispensed ? { dispensed: x.dispensed, refillAt: null } : {}),
      });
    };
    const med = (name, form, strength, dose, days, timing = 'after', note = '') => ({ name, form, strength, dose, timing, days, note });
    const RXA = 'demo-svc-rx-a';
    const RXB = 'demo-svc-rx-b';
    const RXC = 'demo-svc-rx-c';
    const RXD = 'demo-svc-rx-d';
    await rx(RXA, 'DEMSVCRXA2', {
      createdAt: now - 1 * day, complaints: 'জ্বর ৩ দিন, গলা ব্যথা, হাঁচি', diagnosis: 'ভাইরাল জ্বর, অ্যালার্জিক রাইনাইটিস',
      medicines: [med('Napa', 'Tablet', '500 mg', '1+1+1', 5), med('Seclo', 'Capsule', '20 mg', '1+0+1', 14, 'before'), med('Fexo', 'Tablet', '120 mg', '0+0+1', 7)],
      advice: 'প্রচুর পানি ও তরল খাবেন। জ্বর ১০২°-এর বেশি হলে যোগাযোগ করবেন।',
    });
    await rx(RXB, 'DEMSVCRXB3', {
      createdAt: now - 2 * day, complaints: 'রাতে শ্বাসকষ্ট, কাশি', diagnosis: 'অ্যাজমা (হালকা)',
      medicines: [med('Montene', 'Tablet', '10 mg', '0+0+1', 30), med('Alatrol', 'Tablet', '10 mg', '0+0+1', 10)],
      advice: 'ধুলাবালি এড়িয়ে চলবেন।',
    });
    await rx(RXC, 'DEMSVCRXC4', {
      createdAt: now - 2.5 * day, complaints: 'দুর্বলতা, মাড়ি থেকে রক্ত', diagnosis: 'ভিটামিন সি-র ঘাটতি', bp: '১২০/৮০',
      medicines: [med('Ceevit', 'Tablet', '250 mg', '1+0+0', 30)],
      dispensed: [{ by: PH, name: 'ডেমো লাইফ ফার্মেসি', at: now - 1 * day, items: [{ i: 0, qty: 30 }] }],
    });
    await rx(RXD, 'DEMSVCRXD5', {
      createdAt: now - 3 * HOUR, complaints: 'ডায়াবেটিস ও কোলেস্টেরলের নিয়মিত চেকআপ', diagnosis: 'টাইপ-২ ডায়াবেটিস, ডিসলিপিডেমিয়া', bp: '১৪০/৯০',
      medicines: [med('Comet', 'Tablet', '500 mg', '1+0+1', 30), med('Rosuva', 'Tablet', '10 mg', '0+0+1', 30), med('Ecosprin', 'Tablet', '75 mg', '0+1+0', 0, 'after', 'চলবে')],
      advice: 'মিষ্টি ও তেল-চর্বি কম। রোজ ৩০ মিনিট হাঁটবেন।',
    });
    const order = async (id, x) => {
      await ctx.set(`rxOrders/${id}`, {
        rxId: x.rxId, code: x.code, pharmacyId: x.pharmacyId, pharmacyName: x.pharmacyName, patientUid: P, patientName: u.patient.name,
        status: x.status, mode: 'online', address: 'বাসা ১২, রোড ৩, শিববাড়ি, জয়দেবপুর, গাজীপুর', phone: '01700000159', deliveryFee: x.deliveryFee || '৩০ টাকা',
        note: x.note || '', createdAt: x.createdAt, ...(x.extra || {}),
      });
    };
    // ১) নতুন — ডেমো ফার্মেসি "অর্ডার নিলাম" চাপবে
    await order('demo-svc-ord-a', { rxId: RXA, code: 'DEMSVCRXA2', pharmacyId: PH, pharmacyName: 'ডেমো লাইফ ফার্মেসি', status: 'sent', createdAt: now - 25 * MIN, note: 'গেটের দারোয়ানের কাছে দিলেও চলবে' });
    // ২) অন্য দোকান দিতে পারেনি → ডেমো ফার্মেসি নিয়ে পাঠিয়ে দিয়েছে (পথে) — পৌঁছালে "খুলে ওষুধ লিখুন"
    await order('demo-svc-ord-b0', {
      rxId: RXB, code: 'DEMSVCRXB3', pharmacyId: ph24, pharmacyName: 'ডেমো ২৪ ঘণ্টা ফার্মেসি', status: 'cancelled', deliveryFee: '৫০ টাকা',
      createdAt: now - 5 * HOUR, extra: { reason: 'ওষুধ নেই', missed: ['Montene'], updatedAt: now - 4.5 * HOUR },
    });
    await order('demo-svc-ord-b', {
      rxId: RXB, code: 'DEMSVCRXB3', pharmacyId: PH, pharmacyName: 'ডেমো লাইফ ফার্মেসি', status: 'out', createdAt: now - 4 * HOUR,
      extra: { price: '৪৮০ টাকা', eta: 'আজ সন্ধ্যা ৭টার মধ্যে', updatedAt: now - 1 * HOUR },
    });
    // ৩) আগের অর্ডার — গতকাল দেওয়া হয়ে গেছে (অনলাইন, ৭ দিনের মধ্যে → রোগী ব্যবহার, দাম আর ডেলিভারি — তিন দিকেই রেটিং দিতে পারেন)
    await order('demo-svc-ord-c', {
      rxId: RXC, code: 'DEMSVCRXC4', pharmacyId: PH, pharmacyName: 'ডেমো লাইফ ফার্মেসি', status: 'done', createdAt: now - 1.2 * day,
      extra: { price: '১৮০ টাকা', eta: 'এক ঘণ্টার মধ্যে', doneAt: now - 1 * day, updatedAt: now - 1 * day },
    });

    // ---------- ওষুধ খোঁজা ----------
    const buyer = await ctx.extraUser('svc-buyer', { name: 'ডেমো ক্রেতা সালমা', phone: '01700000158', placeName: DISTRICT });
    const c2 = (p) => ({ lat: Math.round(p.lat * 100) / 100, lng: Math.round(p.lng * 100) / 100 });
    // রোগীর চালু খোঁজ: একটা দোকান "আছে" বলেছে, একটা "নেই", ডেমো ফার্মেসি এখনো উত্তর দেয়নি
    await ctx.set('medFinds/demo-svc-find-1', {
      uid: P, drug: drug('Montene', '10 mg', 'Tablet'), district: DISTRICT, upazilaId: SADAR, ...c2(ctx.CENTER),
      notified: [PH, ph24, phModel], answers: { [ph24]: { have: true, at: now - 20 * MIN }, [phModel]: { have: false, at: now - 15 * MIN } },
      // আসলে ৬ ঘণ্টা খোলা থাকে; ডেমোতে কয়েক দিন, যাতে পরে পরীক্ষা করলেও দেখা যায়
      status: 'open', createdAt: now - 40 * MIN, expiresAt: now + 3 * day,
    });
    // আগের (বন্ধ করা) খোঁজ
    await ctx.set('medFinds/demo-svc-find-2', {
      uid: P, drug: drug('Rosuva', '10 mg', 'Tablet'), district: DISTRICT, upazilaId: SADAR, ...c2(ctx.CENTER),
      notified: [PH, phModel], answers: { [PH]: { have: true, at: now - 23 * HOUR } },
      status: 'closed', closedAt: now - 22 * HOUR, createdAt: now - 1 * day, expiresAt: now + 2 * day,
    });
    // অন্য একজনের খোঁজ — ডেমো ফার্মেসি "আছে"/"নেই" চাপবে
    await ctx.set('medFinds/demo-svc-find-3', {
      uid: buyer, drug: drug('Sergel', '20 mg', 'Capsule'), district: DISTRICT, upazilaId: SADAR, ...c2(loc(0.5, -0.5)),
      notified: [PH, ph24], answers: {}, status: 'open', createdAt: now - 10 * MIN, expiresAt: now + 3 * day,
    });

    // =====================================================================
    // প্যাথলজি
    // =====================================================================
    const lab = async (id, x) => {
      const created = x.createdAt || now - 60 * day;
      const have = await docs('lab', id, id, ['license', 'front'], created);
      await ctx.set(`labs/${id}`, {
        name: x.name, facilityId: null, district: DISTRICT, upazilaId: x.upazilaId || SADAR, address: x.address, licenseNo: x.licenseNo,
        phone: x.phone, hours: x.hours, homeCollection: !!x.homeCollection, updatedAt: created,
        ownerUid: id, status: x.status, docsHave: have, createdAt: created,
        ...(x.status === 'approved' ? {
          reviewedAt: created + day, reviewedBy: 'demo', approvedName: x.name,
          verification: verification(['license_no', 'license_name', 'license_valid', 'license_type', 'signboard', 'phone'], created + day),
        } : {}),
        ...(x.ranges ? { ranges: x.ranges } : {}),
      });
    };
    const LAB = u.lab.uid;
    await profile('lab', '01700000160');
    await lab(LAB, {
      name: 'ডেমো সিটি ডায়াগনস্টিক সেন্টার', address: 'হাসপাতাল রোড, শিববাড়ি, জয়দেবপুর, গাজীপুর', licenseNo: 'DEMO-HSM-2201', phone: '01700000160',
      hours: 'সকাল ৮টা – রাত ১০টা (শুক্রবার বিকাল ৩টা থেকে)', homeCollection: true, status: 'approved', createdAt: now - 80 * day,
      // নিজের মেশিনের স্বাভাবিক মাত্রা (রিপোর্টের ছকে আগে থেকে বসে)
      ranges: { 'cbc.hb': { low: 11.5, high: 16.5 }, 'rbs.glu': { low: 3.9, high: 7.8 } },
    });
    const labPop = await ctx.extraUser('svc-lab-popular', { name: 'ডেমো পপুলার প্যাথলজির মালিক', phone: '01700000161', placeName: DISTRICT });
    await lab(labPop, {
      name: 'ডেমো পপুলার প্যাথলজি', address: 'স্টেশন রোড, জয়দেবপুর, গাজীপুর', licenseNo: 'DEMO-HSM-2202', phone: '01700000161',
      hours: 'সকাল ৭টা – রাত ৯টা', status: 'approved',
    });
    const labTri = await ctx.extraUser('svc-lab-sreepur', { name: 'ডেমো শ্রীপুর ডায়াগনস্টিকের মালিক', phone: '01700000162', placeName: DISTRICT });
    await lab(labTri, {
      name: 'ডেমো শ্রীপুর ডায়াগনস্টিক', address: 'মাওনা চৌরাস্তা, শ্রীপুর, গাজীপুর', licenseNo: 'DEMO-HSM-2203', phone: '01700000162',
      hours: 'সকাল ৯টা – সন্ধ্যা ৭টা', homeCollection: true, upazilaId: SREEPUR, status: 'approved',
    });
    // দামের তালিকা নেই — রোগী দেখেন "দাম জানা নেই — ফোনে জেনে নিন"
    const labRoyal = await ctx.extraUser('svc-lab-royal', { name: 'ডেমো রয়েল ল্যাবের মালিক', phone: '01700000164', placeName: DISTRICT });
    await lab(labRoyal, {
      name: 'ডেমো রয়েল ল্যাব', address: 'রথখোলা, জয়দেবপুর, গাজীপুর', licenseNo: 'DEMO-HSM-2205', phone: '01700000164',
      hours: 'সকাল ৮টা – রাত ৯টা', status: 'approved', createdAt: now - 40 * day,
    });

    // পরীক্ষা ও দাম (labprices.js) — ডেমো প্যাথলজির পুরো তালিকা, ১০% ছাড়, বাসা থেকে নমুনা ১৫০ টাকা;
    // পেছনের প্যাথলজিগুলোর আধা তালিকা, আলাদা দাম (রয়েল ল্যাবের তালিকাই নেই)
    const P_ = (code, name, price, tat, on = true) => ({ code, name, price, tat, on });
    await ctx.set(`labPrices/${LAB}`, {
      items: [
        P_('cbc', 'CBC (Complete Blood Count)', 400, 'একই দিন'),
        P_('esr', 'ESR', 150, 'একই দিন'),
        P_('rbs', 'Random Blood Sugar (RBS)', 100, '১ ঘণ্টা'),
        P_('fbs', 'Fasting Blood Sugar (FBS)', 100, '১ ঘণ্টা'),
        P_('2habf', '2 Hours After Breakfast (2HABF)', 100, '১ ঘণ্টা'),
        P_('hba1c', 'HbA1c', 1000, 'একই দিন'),
        P_('lipid', 'Lipid Profile', 1200, 'একই দিন'),
        P_('creat', 'Serum Creatinine', 400, 'একই দিন'),
        P_('alt', 'SGPT (ALT)', 400, 'একই দিন'),
        P_('uric', 'Serum Uric Acid', 450, 'একই দিন'),
        P_('tsh', 'TSH', 900, '২৪ ঘণ্টা'),
        P_('thyroid', 'Thyroid Profile (TSH, FT4, FT3)', 2200, '২৪ ঘণ্টা'),
        P_('urine_re', 'Urine R/E', 200, 'একই দিন'),
        P_('blood_group', 'Blood Grouping & Rh Typing', 150, '৩০ মিনিট'),
        P_('hbsag', 'HBsAg', 500, 'একই দিন'),
        P_('ecg', 'ECG', 400, '৩০ মিনিট'),
        // ছকে নেই — নিজে লেখা
        P_(null, 'Serum Vitamin B12', 1800, '৪৮ ঘণ্টা'),
        // মেশিন নষ্ট — এখন হয় না
        P_('usg_abd', 'USG of Whole Abdomen', 1500, 'একই দিন', false),
      ],
      discountPct: 10, homeFee: 150, updatedAt: now - 5 * day,
    });
    await ctx.set(`labPrices/${labPop}`, {
      items: [
        P_('cbc', 'CBC (Complete Blood Count)', 350, 'একই দিন'),
        P_('rbs', 'Random Blood Sugar (RBS)', 80, '১ ঘণ্টা'),
        P_('fbs', 'Fasting Blood Sugar (FBS)', 80, '১ ঘণ্টা'),
        P_('hba1c', 'HbA1c', 950, '২৪ ঘণ্টা'),
        P_('lipid', 'Lipid Profile', 1100, '২৪ ঘণ্টা'),
        P_('creat', 'Serum Creatinine', 350, 'একই দিন'),
        P_('urine_re', 'Urine R/E', 150, 'একই দিন'),
        P_('ecg', 'ECG', 350, '৩০ মিনিট'),
        P_('usg_abd', 'USG of Whole Abdomen', 1400, 'একই দিন'),
      ],
      discountPct: null, homeFee: null, updatedAt: now - 12 * day,
    });
    await ctx.set(`labPrices/${labTri}`, {
      items: [
        P_('cbc', 'CBC (Complete Blood Count)', 300, '২৪ ঘণ্টা'),
        P_('rbs', 'Random Blood Sugar (RBS)', 70, '১ ঘণ্টা'),
        P_('hba1c', 'HbA1c', 900, '২ দিন'),
        P_('tsh', 'TSH', 850, '২ দিন'),
        P_('urine_re', 'Urine R/E', 120, 'একই দিন'),
      ],
      discountPct: 5, homeFee: 100, updatedAt: now - 20 * day,
    });

    const labNew = await ctx.extraUser('svc-lab-new', { name: 'ডেমো নিউ লাইফ ল্যাবের মালিক', phone: '01700000163', placeName: DISTRICT });
    await lab(labNew, {
      name: 'ডেমো নিউ লাইফ ল্যাব', address: 'ছায়াবীথি, জয়দেবপুর, গাজীপুর', licenseNo: 'DEMO-HSM-2204', phone: '01700000163',
      hours: 'সকাল ৮টা – রাত ৮টা', status: 'pending', createdAt: now - 2 * day,
    });

    // =====================================================================
    // অ্যাম্বুলেন্স
    // =====================================================================
    const AMB_CHECKS = ['reg_no', 'reg_type', 'plate', 'fitness', 'phone', 'details'];
    const ambulance = async (id, ownerUid, x) => {
      const created = x.createdAt || now - 60 * day;
      const have = await docs('ambulance', id, ownerUid, ['reg', 'fitness', 'front'], created);
      const data = {
        name: x.name, phone: x.phone, vehicleNo: x.vehicleNo, types: x.types, district: DISTRICT, upazilaId: x.upazilaId || SADAR,
        area: x.area || '', fare: x.fare || '', updatedAt: created,
      };
      await ctx.set(`ambulances/${id}`, {
        ...data,
        ownerUid,
        status: x.status,
        docsHave: have,
        createdAt: created,
        available: x.available === true,
        // গাড়ি যেখানে অপেক্ষা করে (চালক GPS দিয়ে দিলে) — আসলের মতো ২ দশমিকে কাটা; না থাকলে জেলার কেন্দ্র ধরে "প্রায়" দূরত্ব
        ...(x.at ? require('../ambulance').point(x.at.lat, x.at.lng) : {}),
        ...(x.availableAt ? { availableAt: x.availableAt } : {}),
        // "এখনো খালি আছেন?" — আসলের মতো খালি করার ১১ ঘণ্টা পর (মেয়াদ পেরোনো গাড়িতে আর নেই; সাড়ে ১১ ঘণ্টার মিনিতে আগেই পেরিয়েছে → তালিকা খুললেই পুশ)
        ...(x.status === 'approved' && x.available === true && x.availableAt && now - x.availableAt < require('../ambulance').AVAILABLE_MS
          ? { remindAt: x.availableAt + require('../ambulance').REMIND_AT_MS } : {}),
        ...(x.busyAt ? { busyAt: x.busyAt } : {}),
        ...(x.status === 'approved' ? {
          reviewedAt: created + day, reviewedBy: 'demo', approvedName: x.name, lastApproved: snapshot(data),
          verification: verification(AMB_CHECKS, created + day), ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
        } : {}),
      });
      if (x.status === 'approved') {
        const c = dailyCalls(now, x.callsPerDay || 1, id.length);
        await ctx.set(`ambulanceStats/${id}`, { d: c.d, total: c.total + 15, callers: Math.round(c.total * 0.8), recent: x.recent || [] });
      }
    };
    const AMB = u.ambulance.uid;
    await profile('ambulance', '01700000170');
    const AMB_AC = 'demo-svc-amb-ac';
    await ambulance(AMB_AC, AMB, {
      name: 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)', phone: '01700000170', vehicleNo: 'DEMO-GAZ-CHA-11-0001', types: ['ac', 'general'],
      area: 'তাজউদ্দীন মেডিকেল গেট, শিববাড়ি', fare: 'শহরের মধ্যে ৮০০ টাকা, ঢাকা ৩০০০ টাকা', status: 'approved', at: at3(loc(0.8, 1.2)),
      available: true, availableAt: now - 30 * MIN, ratingSum: 42, ratingCount: 9, callsPerDay: 2,
      recent: [
        { rating: 4, comment: 'ভাড়া একটু বেশি, তবে গাড়ি পরিষ্কার', at: now - 9 * day, who: 'demo0000000000a1' },
        { rating: 5, comment: 'রাত ২টায় ফোন করেছিলাম, ২০ মিনিটে এসেছে। অনেক ধন্যবাদ।', at: now - 3 * day, who: raterKey('ambulanceStats', AMB_AC, P) },
      ],
    });
    // রোগী আগে ফোন করে রেটিং দিয়েছেন
    await ctx.set(`ambulanceCalls/${P}_${AMB_AC}`, { uid: P, ambulanceId: AMB_AC, at: now - 4 * day, firstAt: now - 4 * day, rating: 5, comment: 'রাত ২টায় ফোন করেছিলাম, ২০ মিনিটে এসেছে। অনেক ধন্যবাদ।', ratedAt: now - 3 * day });
    await ambulance('demo-svc-amb-nonac', AMB, {
      name: 'ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)', phone: '01700000171', vehicleNo: 'DEMO-GAZ-CHA-11-0002', types: ['general'],
      area: 'শিববাড়ি, ছায়াবীথি', fare: 'শহরের মধ্যে ৫০০ টাকা', status: 'approved', at: at3(loc(1.5, 0.5)),
      // এখন ট্রিপে (চালক "ট্রিপে আছি" করেছেন)
      available: false, availableAt: now - 50 * MIN, busyAt: now - 50 * MIN, ratingSum: 8, ratingCount: 2,
    });
    // "এখনো খালি আছেন?": সাড়ে ১১ ঘণ্টা আগে "খালি" জানানো — চালকের কার্ডে "আর ৩০ মিনিট", তালিকা খুললেই চালকের কাছে পুশ
    // (ডেমো বানানোর আধা ঘণ্টা পর এটা "ফোনে জেনে নিন" হয়ে যায়, যদি না চালক "আবার চালু রাখুন" চাপেন)
    await ambulance('demo-svc-amb-mini', AMB, {
      name: 'ডেমো জীবন মিনি অ্যাম্বুলেন্স', phone: '01700000176', vehicleNo: 'DEMO-GAZ-CHA-11-0007', types: ['general'],
      area: 'চান্দনা চৌরাস্তা, ভোগড়া', fare: 'শহরের মধ্যে ৪০০ টাকা', status: 'approved', at: at3(loc(-2.5, -1.5)),
      available: true, availableAt: now - 11.5 * HOUR, ratingSum: 9, ratingCount: 2,
    });
    // ডেমো চালকের নতুন আইসিইউ গাড়ি — অনুমোদনের অপেক্ষায়
    await ambulance('demo-svc-amb-icu-new', AMB, {
      name: 'ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স', phone: '01700000172', vehicleNo: 'DEMO-GAZ-CHA-11-0003', types: ['icu', 'ac'],
      area: 'গাজীপুর থেকে ঢাকা', fare: 'ঢাকা ৮০০০ টাকা (অক্সিজেন সহ)', status: 'pending', createdAt: now - 6 * HOUR,
    });
    const ambMomota = await ctx.extraUser('svc-amb-momota', { name: 'ডেমো মমতা অ্যাম্বুলেন্সের মালিক', phone: '01700000173', placeName: DISTRICT });
    const AMB_MOMOTA = 'demo-svc-amb-momota';
    await ambulance(AMB_MOMOTA, ambMomota, {
      name: 'ডেমো মমতা অ্যাম্বুলেন্স', phone: '01700000173', vehicleNo: 'DEMO-GAZ-GA-12-0004', types: ['general'], upazilaId: SREEPUR,
      area: 'মাওনা চৌরাস্তা, শ্রীপুর', fare: 'আলোচনা সাপেক্ষে', status: 'approved', at: at3(loc(5.5, 22)), available: true, availableAt: now - 3 * HOUR, ratingSum: 16, ratingCount: 5,
    });
    const ambIcu = await ctx.extraUser('svc-amb-icu', { name: 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিসের মালিক', phone: '01700000174', placeName: DISTRICT });
    const AMB_ICU = 'demo-svc-amb-icu';
    await ambulance(AMB_ICU, ambIcu, {
      name: 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস', phone: '01700000174', vehicleNo: 'DEMO-GAZ-CHA-13-0005', types: ['icu', 'ac'],
      area: 'তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল', fare: 'ঢাকা ১০০০০ টাকা', status: 'approved', at: at3(loc(0.5, 0.9)),
      // ১২ ঘণ্টার বেশি আগে "খালি" জানিয়েছে → "ফোনে জেনে নিন"
      available: true, availableAt: now - 20 * HOUR, ratingSum: 23, ratingCount: 5,
    });
    const ambFreezer = await ctx.extraUser('svc-amb-freezer', { name: 'ডেমো ফ্রিজার ভ্যানের মালিক', phone: '01700000175', placeName: DISTRICT });
    await ambulance('demo-svc-amb-freezer', ambFreezer, {
      name: 'ডেমো শেষ যাত্রা ফ্রিজার ভ্যান', phone: '01700000175', vehicleNo: 'DEMO-GAZ-CHA-14-0006', types: ['freezer'],
      // গাড়ির জায়গা দেওয়া নেই → জেলার কেন্দ্র ধরে "প্রায়" দূরত্ব
      area: 'পুরো গাজীপুর জেলা ও ঢাকা', fare: 'দূরত্ব অনুযায়ী', status: 'approved', available: true, availableAt: now - 2 * HOUR,
    });
    // রোগী ৩ ঘণ্টা আগে আইসিইউ সার্ভিসে ফোন করেছিলেন → এখন রেটিং দিতে পারবেন
    await ctx.set(`ambulanceCalls/${P}_${AMB_ICU}`, { uid: P, ambulanceId: AMB_ICU, at: now - 3 * HOUR, firstAt: now - 3 * HOUR });

    // =====================================================================
    // বাসায় সেবা (নার্স / ডাক্তার)
    // =====================================================================
    const NURSE = u.homecare.uid;
    const nProfile = await profile('homecare', '01700000180');
    await ctx.set(`users/${NURSE}`, { gender: 'female' }, { merge: true });
    // ফোন যাচাই চালু থাকলে নার্স সেটিং বদলাতে পারেন (প্রোফাইলের নম্বর যাচাই করা ধরা)
    const nPhone = require('../care').normalizeBd(nProfile.phone);
    if (nPhone) await ctx.set(`serverState/${NURSE}`, { verifiedPhone: nPhone, phoneVerifiedAt: now - 30 * day }, { merge: true });
    const homePro = async (uid, x) => {
      await ctx.set(`badges/${uid}`, {
        type: x.type, regNo: x.regNo, workplace: x.workplace, name: x.name, email: x.email || '', status: x.badgeStatus || 'approved',
        docsHave: await docs('badge', uid, uid, x.type === 'nurse' ? ['cert', 'idcard'] : ['cert'], now - 70 * day),
        createdAt: now - 70 * day, ...(x.badgeStatus === 'pending' ? {} : { reviewedAt: now - 69 * day, reviewedBy: 'demo' }),
      });
      if (!x.homeVisit) return;
      await ctx.set(`providers/${uid}`, {
        type: x.type, name: x.name, listed: false, appointments: false, active: false, specialties: x.specialties, degrees: x.degrees,
        gender: x.gender, chambers: [], homeVisit: x.homeVisit, homeVisitTermsAt: now - 60 * day, updatedAt: now - 10 * day,
        ratingSum: x.ratingSum || 0, ratingCount: x.ratingCount || 0,
      });
      const c = dailyCalls(now, x.callsPerDay || 1, uid.length);
      await ctx.set(`homeStats/${uid}`, { d: c.d, total: c.total + 10, callers: Math.round(c.total * 0.6), recent: x.recent || [] });
    };
    await homePro(NURSE, {
      type: 'nurse', name: u.homecare.name, regNo: 'DEMO-BNMC-55001', workplace: 'শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল (ডেমো)', email: u.homecare.email,
      specialties: ['general_nursing', 'home_care'], degrees: 'ডিপ্লোমা ইন নার্সিং অ্যান্ড মিডওয়াইফারি', gender: 'female',
      homeVisit: {
        enabled: true, radiusKm: 10, upazilaIds: [SADAR, KALIAKAIR], services: ['injection', 'saline', 'cannula', 'dressing', 'bp_sugar', 'elderly_care', 'newborn_care'],
        phone: '01700000180', schedule: 'জরুরি হলে রাতেও আসি', times: [{ days: [0, 1, 2, 3, 4, 6], start: '09:00', end: '18:00' }], noFee: false, fee: '৩০০–৫০০ টাকা (সেবা অনুযায়ী)',
      },
      ratingSum: 27, ratingCount: 6, callsPerDay: 2,
      recent: [
        { rating: 5, comment: 'আম্মার স্যালাইন খুব যত্ন করে দিয়েছেন।', at: now - 12 * day, who: 'demo0000000000b1' },
        { rating: 4, comment: 'সময়মতো এসেছেন, ব্যবহার ভালো।', at: now - 5 * day, who: 'demo0000000000b2' },
      ],
    });
    // রোগী ৫ ঘণ্টা আগে নার্সকে ফোন করেছিলেন → রেটিং দিতে পারবেন
    await ctx.set(`homeCalls/${P}_${NURSE}`, { uid: P, providerUid: NURSE, at: now - 5 * HOUR, firstAt: now - 5 * HOUR });

    const nShahin = await ctx.extraUser('svc-nurse-shahin', { name: 'ডেমো নার্স শাহীন', phone: '01700000181', placeName: DISTRICT, ...at3(loc(-1, 1)), locationSource: 'gps', gender: 'male' });
    await homePro(nShahin, {
      type: 'nurse', name: 'ডেমো নার্স শাহীন', regNo: 'DEMO-BNMC-55002', workplace: 'ডেমো কমিউনিটি হাসপাতাল',
      specialties: ['general_nursing', 'icu'], degrees: 'বিএসসি ইন নার্সিং', gender: 'male',
      homeVisit: { enabled: true, radiusKm: 5, upazilaIds: [], services: ['injection', 'saline', 'catheter', 'post_op', 'dressing'], phone: '01700000181', schedule: '', times: [{ days: [5, 6], start: '08:00', end: '20:00' }, { days: [0, 1, 2, 3, 4], start: '17:00', end: '22:00' }], noFee: false, fee: '৪০০ টাকা' },
      ratingSum: 7, ratingCount: 2,
    });
    const nMousumi = await ctx.extraUser('svc-nurse-mousumi', { name: 'ডেমো নার্স মৌসুমী', phone: '01700000182', placeName: DISTRICT, locationSource: 'district', gender: 'female' });
    await homePro(nMousumi, {
      type: 'nurse', name: 'ডেমো নার্স মৌসুমী', regNo: 'DEMO-BNMC-55003', workplace: 'ডেমো শ্রীপুর উপজেলা স্বাস্থ্য কমপ্লেক্স',
      specialties: ['midwife', 'pediatric_nursing'], degrees: 'ডিপ্লোমা ইন মিডওয়াইফারি', gender: 'female',
      homeVisit: { enabled: true, radiusKm: 5, upazilaIds: [SREEPUR, SADAR], services: ['newborn_care', 'bp_sugar', 'injection'], phone: '01700000182', schedule: 'আগে ফোন করে সময় ঠিক করে নিন', times: [], noFee: false, fee: '৩০০ টাকা' },
    });
    const dRafiq = await ctx.extraUser('svc-doc-rafiq', { name: 'ডা. ডেমো রফিক', phone: '01700000183', placeName: DISTRICT, ...at3(loc(1, -1)), locationSource: 'gps', gender: 'male' });
    await homePro(dRafiq, {
      type: 'doctor', name: 'ডা. ডেমো রফিক', regNo: 'DEMO-A-10002', workplace: 'ডেমো জেনারেল হাসপাতাল',
      specialties: ['medicine', 'general'], degrees: 'এমবিবিএস (ডেমো)', gender: 'male',
      homeVisit: { enabled: true, radiusKm: 10, upazilaIds: [], services: ['checkup', 'elderly_care', 'bedridden', 'follow_up'], phone: '01700000183', schedule: 'সন্ধ্যার পর', times: [{ days: [0, 1, 2, 3, 4, 6], start: '18:00', end: '22:00' }], noFee: false, fee: '১০০০ টাকা' },
      ratingSum: 9, ratingCount: 2,
    });
    // অনুমোদনের অপেক্ষায় নার্সের ব্যাজ (অনুমোদন হলে তিনি নিজে বাসায় সেবা চালু করবেন)
    const nTania = await ctx.extraUser('svc-nurse-tania', { name: 'ডেমো নার্স তানিয়া', phone: '01700000184', placeName: DISTRICT, gender: 'female' });
    await homePro(nTania, {
      type: 'nurse', name: 'ডেমো নার্স তানিয়া', regNo: 'DEMO-BNMC-55004', workplace: 'ডেমো সূর্যের হাসি ক্লিনিক', badgeStatus: 'pending', email: 'nurse-tania@lifolo-demo.test',
    });

    // =====================================================================
    // রিপোর্ট (মালিক অ্যাডমিন হিসেবে দেখবেন) — রিপোর্ট শুধু অ্যাপ থেকে ফোন করা মানুষ দিতে পারেন
    // =====================================================================
    const report = async (kind, path, reporter, reason, ago) => {
      await ctx.set(`modReports/${path.replace(/\//g, '_')}__${reporter}`, { kind, targetPath: path, reporterUid: reporter, reason, status: 'open', createdAt: now - ago });
    };
    await ctx.set(`pharmacyCalls/${P}_${phModel}`, { uid: P, pharmacyId: phModel, at: now - 2 * day });
    await report('pharmacy', `pharmacies/${phModel}`, P, 'ফোনে বলেছিল ওষুধ আছে, গিয়ে দেখি নেই। দামও MRP-র বেশি চাইল।', 1 * day);
    await ctx.set(`ambulanceCalls/${buyer}_${AMB_MOMOTA}`, { uid: buyer, ambulanceId: AMB_MOMOTA, at: now - 3 * day, firstAt: now - 3 * day });
    await report('ambulance', `ambulances/${AMB_MOMOTA}`, buyer, 'বলেছিল ২০ মিনিটে আসবে, দেড় ঘণ্টা পর এসেছে। ভাড়াও দ্বিগুণ চেয়েছে।', 2 * day);
    await ctx.set(`homeCalls/${buyer}_${nShahin}`, { uid: buyer, providerUid: nShahin, at: now - 4 * day, firstAt: now - 4 * day });
    await report('homecare', `providers/${nShahin}`, buyer, 'সময় দিয়ে আসেননি, ফোনও ধরেননি।', 3 * day);

    // মেমোরিতে রাখা তালিকা (ফার্মেসি, প্যাথলজি, বাসায় সেবা, অতিথির উত্তর) নতুন করে — লেখা শেষ হওয়ার পর
    await ctx.flush();
    const M = require('../memcache');
    ['pharm:', 'labs:', 'labprices:', 'home:', 'providers:', 'guest:'].forEach((k) => M.clear(k));
  },
};
