// ডেমো ডেটা — ভাগ: blood (দেখুন index.js)
// রক্তদান: প্রোফাইল (ডোনারের তথ্য, এলাকা, নোটিফিকেশন সেটিংস), পেছনের ডোনার, রক্তের রিকোয়েস্ট (খোলা/বন্ধ, সাড়া সহ),
// জরুরি আহ্বান, ব্লাড ব্যাংক (অনুমোদিত + অপেক্ষায়), হাসপাতাল (যোগ হওয়া + অনুরোধ), তারিখ সংশোধনের অনুরোধ, ঘণ্টার নোটিফিকেশন।
// সব ঘরের নাম আর রূপ অ্যাপ/সার্ভারের আসল লেখার মতো (requestcreate.js, api/respond.js, profile.ts, bloodbank.js, emergency.js …)।
const { FieldValue } = require('../firebase');
const R = require('../rules');
const Geo = require('../geo');
const DISTRICTS = require('../districts');
const { normalizeName } = require('../hospital-match');
const { snapshot } = require('../listingreview');
const sanctions = require('../sanctions');

const DISTRICT = DISTRICTS.find((d) => d.en === 'Mymensingh').bn; // ডাটাবেসের বানান (য় একটা অক্ষর) হুবহু
const SADAR = 466; // ময়মনসিংহ সদর উপজেলা (geo.json)
const HOUR = 3600 * 1000;

// ব্লাড ব্যাংকের সাইনবোর্ডের ডেমো ছবি (৩৬০×২০০ JPEG, "DEMO … TEST PHOTO - NOT REAL") — অ্যাডমিনের যাচাই পাতায় দেখায়
const DEMO_JPEG = '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';

/** বাংলাদেশের আজকের তারিখ (UTC মধ্যরাত) থেকে n দিন পর/আগে — অ্যাপের DatePicker-এর মতো */
const dayOf = (ctx, n) => R.todayDhakaUtcMidnight(ctx.now) + n * R.DAY_MS;
/** বাছা তারিখের (UTC মধ্যরাত) বাংলাদেশের সময় ঘণ্টা:মিনিট → আসল মুহূর্ত (অ্যাপের Rules.dhakaMillis) */
const dhakaAt = (dateUtcMidnight, h, m = 0) => dateUtcMidnight - 6 * HOUR + (h * 60 + m) * 60 * 1000;
/** অ্যাপের মতো ≈১ কিমি-তে গোল করা স্থানাঙ্ক আর তার geohash */
function spot(ctx, dx, dy) {
  const p = ctx.near(dx, dy);
  const lat = Math.round(p.lat * 100) / 100;
  const lng = Math.round(p.lng * 100) / 100;
  return { lat, lng, geohash: Geo.hash(lat, lng) };
}

/** ProfileRepository.saveLocation-এর ঘরগুলো (+ donorGeo, refreshDonorGeo-র মতো) */
function locationFields(ctx, loc, bloodGroup, source = 'gps') {
  const key = Geo.donorKey(bloodGroup, loc.geohash);
  return {
    lat: loc.lat, lng: loc.lng, geohash: loc.geohash, placeName: DISTRICT, locationSource: source,
    locationUpdatedAtMillis: ctx.now - 3 * R.DAY_MS,
    ...(key ? { donorGeo: key } : {}),
  };
}

const birth = (y, m, d) => Date.UTC(y, m - 1, d);

async function seed(ctx) {
  const U = ctx.u;
  const now = ctx.now;
  const today = dayOf(ctx, 0);
  const daysAgo = (n) => today - n * R.DAY_MS;

  // ======================= ১) ডেমো লগইন ব্যবহারকারীদের প্রোফাইল =======================
  // সবাই জেলার মাঝখানে, "district" উৎসে (অ্যাপ খুললে GPS দিয়ে জায়গা বদলে যায় না — আশেপাশের তালিকা ঠিক থাকে)
  const home = (() => {
    const d = DISTRICTS.find((x) => x.bn === DISTRICT);
    const lat = Math.round(d.lat * 100) / 100;
    const lng = Math.round(d.lng * 100) / 100;
    return { lat, lng, geohash: Geo.hash(lat, lng) };
  })();
  const PHONE = {
    patient: '01700001101', donor: '01700001102', doctor: '01700001103', assistant: '01700001104', pharmacy: '01700001105',
    lab: '01700001106', ambulance: '01700001107', bloodbank: '01700001108', homecare: '01700001109', caregiver: '01700001110',
  };
  const settings = { notify: true, maxPushPerDay: R.MAX_PUSH_PER_DAY, notifyGroups: null, viewGroups: null, maxKm: 10 };
  for (const key of Object.keys(PHONE)) {
    if (!U[key]) continue;
    await ctx.set(`users/${U[key].uid}`, {
      name: U[key].name, phone: PHONE[key], lang: 'bn', notify: true, updatedAt: FieldValue.serverTimestamp(),
      ...locationFields(ctx, home, null, 'district'),
    }, { merge: true });
  }
  // রক্তদাতা: O+, যোগ্য (শেষ রক্তদান ১৫০ দিন আগে)
  await ctx.set(`users/${U.donor.uid}`, {
    bloodGroup: 'O+', birthDateMillis: birth(1994, 3, 15), weightKg: 68, lastDonationMillis: daysAgo(150),
    donorGeo: Geo.donorKey('O+', home.geohash), ...settings,
  }, { merge: true });
  // কেয়ারগিভার: B+, ৩৫ দিন আগে রক্ত দিয়েছেন — এখন যোগ্য নন (পরের তারিখ দেখায়)
  await ctx.set(`users/${U.caregiver.uid}`, {
    bloodGroup: 'B+', birthDateMillis: birth(1990, 8, 2), weightKg: 55, lastDonationMillis: daysAgo(35),
    donorGeo: Geo.donorKey('B+', home.geohash), ...settings,
  }, { merge: true });
  // রোগী: রক্তদাতার তথ্য নেই (হোমে "রক্ত দিতে চাই" দেখায়) — শুধু নাম, ফোন, এলাকা

  // ======================= ২) পেছনের ব্যবহারকারী (লগইন ছাড়া) =======================
  let phoneNo = 1;
  const nextPhone = () => `017000012${String(phoneNo++).padStart(2, '0')}`;
  const X = {}; // key -> { uid, name, phone, group, lat, lng }
  async function person(key, name, opts = {}) {
    const phone = nextPhone();
    const loc = spot(ctx, opts.dx || 0, opts.dy || 0);
    const donor = opts.group ? {
      bloodGroup: opts.group, birthDateMillis: opts.birth, weightKg: opts.weight,
      ...(opts.last != null ? { lastDonationMillis: opts.last } : {}),
      notify: true, maxPushPerDay: R.MAX_PUSH_PER_DAY, maxKm: opts.maxKm || 10,
      ...(opts.fareAfterKm != null ? { fareAfterKm: opts.fareAfterKm } : {}),
    } : { notify: true };
    const uid = await ctx.extraUser(key, {
      name, phone, lang: 'bn', updatedAt: FieldValue.serverTimestamp(),
      ...locationFields(ctx, loc, opts.group || null), ...donor,
    });
    X[key] = { uid, name, phone, group: opts.group || null, fareAfterKm: opts.fareAfterKm ?? null, ...loc };
    return X[key];
  }

  // ১২ জন ডোনার: বিভিন্ন গ্রুপ; কেউ যোগ্য, কেউ সম্প্রতি দিয়েছেন / বয়স / ওজনের কারণে নয়
  await person('sakib', 'ডেমো ডোনার সাকিব', { group: 'O+', birth: birth(1996, 1, 20), weight: 72, last: daysAgo(200), dx: 1.2, dy: 0.8 });
  await person('nadia', 'ডেমো ডোনার নাদিয়া', { group: 'A+', birth: birth(1999, 6, 5), weight: 54, dx: -0.8, dy: 1.6 });
  await person('tanvir', 'ডেমো ডোনার তানভীর', { group: 'B+', birth: birth(1992, 11, 11), weight: 75, last: daysAgo(130), dx: 2.5, dy: -1.1, fareAfterKm: 5 });
  await person('rumana', 'ডেমো ডোনার রুমানা', { group: 'O-', birth: birth(1993, 2, 28), weight: 58, last: daysAgo(300), dx: -2.2, dy: -0.6 });
  await person('imran', 'ডেমো ডোনার ইমরান', { group: 'AB+', birth: birth(1988, 9, 9), weight: 80, last: daysAgo(400), dx: 3.4, dy: 2.1 });
  await person('farhana', 'ডেমো ডোনার ফারহানা', { group: 'A-', birth: birth(1997, 4, 14), weight: 52, last: daysAgo(40), dx: -1.5, dy: 2.9 });
  await person('jahid', 'ডেমো ডোনার জাহিদ', { group: 'O+', birth: birth(1991, 12, 1), weight: 70, last: daysAgo(40), dx: 0.4, dy: -2.4 });
  await person('mitu', 'ডেমো ডোনার মিতু', { group: 'B-', birth: birth(1998, 7, 21), weight: 51, last: daysAgo(180), dx: 4.6, dy: -0.3 });
  await person('rafiq', 'ডেমো ডোনার রফিক', { group: 'O+', birth: birth(1986, 5, 30), weight: 77, dx: -3.1, dy: 1.0, maxKm: 25 });
  await person('shapla', 'ডেমো ডোনার শাপলা', { group: 'AB-', birth: birth(1995, 10, 3), weight: 56, last: daysAgo(60), dx: 1.9, dy: 3.8 });
  await person('habib', 'ডেমো ডোনার হাবিব (৬২ বছর)', { group: 'A+', birth: birth(1964, 3, 3), weight: 70, last: daysAgo(500), dx: -4.0, dy: -2.8 });
  await person('sumi', 'ডেমো ডোনার সুমি (৪৫ কেজি)', { group: 'B+', birth: birth(2000, 1, 9), weight: 45, dx: 0.9, dy: 4.4 });
  // রক্তপ্রার্থী (ডোনার নন) আর ব্লাড ব্যাংকের মালিক
  await person('selina', 'ডেমো রক্তপ্রার্থী সেলিনা', { dx: 0.7, dy: 1.3 });
  await person('mahmud', 'ডেমো রক্তপ্রার্থী মাহমুদ', { dx: 1.8, dy: -0.9 });
  await person('kamal', 'ডেমো রক্তপ্রার্থী কামাল', { dx: -1.1, dy: -1.7 });
  await person('ayesha', 'ডেমো রক্তপ্রার্থী আয়েশা', { dx: -0.5, dy: 2.2 });
  await person('nurul', 'ডেমো রক্তপ্রার্থী নুরুল', { dx: 2.9, dy: 1.4 });
  await person('polash', 'ডেমো রক্তপ্রার্থী পলাশ', { dx: -4, dy: -16 });
  await person('rehana', 'ডেমো রক্তপ্রার্থী রেহানা', { dx: 1.4, dy: 0.2 });
  await person('bbowner', 'ডেমো ব্লাড ব্যাংক ব্যবস্থাপক', { dx: 0.2, dy: -0.5 });

  // ======================= ৩) রক্তের রিকোয়েস্ট =======================
  /** request_create-এর মতো ডক (+ চাইলে বাড়তি অবস্থা), আর আলাদা private/contact (শুধু রক্তপ্রার্থী আর রাজি ডোনার পড়েন) */
  async function request(id, by, f) {
    const loc = spot(ctx, f.dx, f.dy);
    const open = f.status !== 'closed';
    const doc = {
      requesterUid: by.uid,
      requesterName: by.name,
      patientGroup: f.group,
      bags: f.bags,
      hospital: f.hospital,
      placeName: DISTRICT,
      lat: loc.lat,
      lng: loc.lng,
      geohash: loc.geohash,
      ...(open ? { geoOpen: loc.geohash } : {}),
      neededByMillis: f.neededBy,
      neededAtMillis: f.neededAt ?? null,
      urgent: f.urgent === true,
      travelFare: f.travelFare === true,
      note: f.note || '',
      status: open ? 'open' : 'closed',
      createdAtMillis: f.createdAt,
      // createdAt (Timestamp) ইচ্ছা করে নেই: অ্যাপ তখন createdAtMillis ধরে (সংখ্যা রাখলে অ্যাপের getTimestamp ভেঙে যেত)
      acceptedCount: f.acceptedCount || 0,
      confirmedCount: f.confirmedCount || 0,
      fulfilled: f.fulfilled === true,
      ...(f.extra || {}),
    };
    const bad = R.validateRequest(doc, f.createdAt);
    if (bad) throw new Error(`demo request ${id}: ${bad}`);
    await ctx.set(`requests/${id}`, doc);
    await ctx.set(`requests/${id}/private/contact`, { phone: by.phone, name: by.name });
    return { id, ...doc };
  }
  /** api/respond.js-এর মতো সাড়া; ডোনারের প্রোফাইলে commitments/respondedTo আর serverState-এ respondedTo */
  async function respond(req, d, f = {}) {
    const at = f.at || now - 2 * HOUR;
    await ctx.set(`requests/${req.id}/responses/${d.uid}`, {
      donorUid: d.uid,
      donorName: d.name,
      donorPhone: d.phone,
      donorGroup: d.group,
      distanceKm: Math.round(R.distanceKm(req.lat, req.lng, d.lat, d.lng)),
      fareAfterKm: typeof d.fareAfterKm === 'number' ? d.fareAfterKm : null,
      status: f.status || 'accepted',
      createdAtMillis: at,
      ...(f.confirmed ? { confirmed: true, confirmedAt: at + HOUR } : {}),
    });
    const active = (f.status || 'accepted') === 'accepted' && req.status === 'open';
    await ctx.set(`users/${d.uid}`, { respondedTo: { [req.id]: at }, ...(active ? { commitments: { [req.id]: at } } : {}) }, { merge: true });
    await ctx.set(`serverState/${d.uid}`, { respondedTo: { [req.id]: at } }, { merge: true });
  }

  const P = { uid: U.patient.uid, name: U.patient.name, phone: PHONE.patient };
  const D = { uid: U.donor.uid, name: U.donor.name, phone: PHONE.donor, group: 'O+', lat: home.lat, lng: home.lng };

  // রোগীর খোলা রিকোয়েস্ট: A+, ২ ব্যাগ — একজন নিশ্চিত, একজন রাজি (নিশ্চিত করার অপেক্ষায়); ডেমো ডোনার (O+) এটা দেখেন
  const pOpen = await request('demo-rq-patient-open', P, {
    group: 'A+', bags: 2, hospital: 'ডেমো চরপাড়া জেনারেল হাসপাতাল', dx: 0.6, dy: -0.4,
    neededBy: dayOf(ctx, 3), neededAt: dhakaAt(dayOf(ctx, 3), 11, 0), createdAt: now - 5 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — আমার বাবার পিত্তথলির অপারেশন, সকালে ভর্তি',
    acceptedCount: 2, confirmedCount: 1,
    // ডোনারদের আগেই জানানো হয়েছে (৩ বার — আর "আবার জানান" নেই, যাতে ডেমো থেকে আসল ডোনারদের কাছে নোটিফিকেশন না যায়)
    extra: { notifiedAt: FieldValue.serverTimestamp(), notifiedCount: 7, notifyRounds: 3, lastNotifiedAt: now - 4 * HOUR },
  });
  await respond(pOpen, X.sakib, { confirmed: true, at: now - 4 * HOUR });
  await respond(pOpen, X.nadia, { at: now - 3 * HOUR });

  // রোগীর ইতিহাস: ৪০ দিন আগে পূরণ হয়ে বন্ধ (দুজন রক্ত দিয়েছেন — তাই তাঁরা এখন যোগ্য নন)
  const pDone = await request('demo-rq-patient-done', P, {
    status: 'closed', group: 'A+', bags: 2, hospital: 'ডেমো মেডিকেল গেট জেনারেল হাসপাতাল', dx: -0.3, dy: 0.5,
    neededBy: daysAgo(40), createdAt: daysAgo(41) + 10 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — মায়ের রক্তস্বল্পতা',
    acceptedCount: 2, confirmedCount: 2, fulfilled: true,
    extra: { closedAt: daysAgo(40) + 14 * HOUR, closedBy: 'requester', bagsReceived: 2, bagsReceivedBefore: 0, notifiedCount: 9, notifyRounds: 1, lastNotifiedAt: daysAgo(41) + 10 * HOUR },
  });
  await respond(pDone, X.farhana, { status: 'donated', confirmed: true, at: daysAgo(41) + 11 * HOUR });
  await respond(pDone, X.jahid, { status: 'donated', confirmed: true, at: daysAgo(41) + 12 * HOUR });
  // রোগীর আরেকটা: "আর লাগবে না" বলে বন্ধ (১২ দিন আগে)
  await request('demo-rq-patient-notneeded', P, {
    status: 'closed', group: 'B+', bags: 1, hospital: 'ডেমো কাচিঝুলি মা ও শিশু ক্লিনিক', dx: 1.0, dy: 1.0,
    neededBy: daysAgo(12), createdAt: daysAgo(13) + 9 * HOUR, note: 'ডেমো রিকোয়েস্ট (আসল নয়)',
    extra: { closedAt: daysAgo(12) + 8 * HOUR, closedBy: 'not_needed', bagsReceivedBefore: 0, notifiedCount: 4, notifyRounds: 1, lastNotifiedAt: daysAgo(13) + 9 * HOUR },
  });

  // অন্যদের খোলা রিকোয়েস্ট (আশেপাশে, বিভিন্ন গ্রুপ)
  await request('demo-rq-urgent', X.selina, {
    group: 'A+', bags: 1, urgent: true, hospital: 'ডেমো মেডিকেল গেট জেনারেল হাসপাতাল', dx: 0.9, dy: 1.4,
    neededBy: dayOf(ctx, 2), createdAt: now - 1 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — সিজারিয়ান অপারেশন, জরুরি',
  });
  await request('demo-rq-fare', X.mahmud, {
    group: 'B+', bags: 2, travelFare: true, hospital: 'ডেমো গাঙ্গিনারপাড় ক্লিনিক', dx: 1.9, dy: -1.0,
    neededBy: dayOf(ctx, 4), neededAt: dhakaAt(dayOf(ctx, 4), 10, 30), createdAt: now - 7 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — যাতায়াত ভাড়া আর নাস্তা দেওয়া হবে',
  });
  const rqO = await request('demo-rq-oplus', X.kamal, {
    group: 'O+', bags: 1, hospital: 'ডেমো কাচিঝুলি মা ও শিশু ক্লিনিক', dx: -1.2, dy: -1.6,
    neededBy: dayOf(ctx, 6), createdAt: now - 26 * HOUR, note: 'ডেমো রিকোয়েস্ট (আসল নয়) — ডেঙ্গু, প্লাটিলেট কম',
    acceptedCount: 1,
  });
  await respond(rqO, X.rafiq, { at: now - 20 * HOUR });
  await request('demo-rq-thal', X.ayesha, {
    group: 'AB+', bags: 3, hospital: 'ডেমো থ্যালাসেমিয়া সেন্টার, চরপাড়া', dx: -0.6, dy: 2.3,
    neededBy: dayOf(ctx, 3), createdAt: now - 30 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — থ্যালাসেমিয়া, প্রতি মাসে রক্ত লাগে',
    extra: { bagsReceived: 1 },
  });
  await request('demo-rq-oneg', X.nurul, {
    group: 'O-', bags: 1, hospital: 'ডেমো চরপাড়া জেনারেল হাসপাতাল', dx: 2.8, dy: 1.3,
    neededBy: dayOf(ctx, 2), createdAt: now - 3 * HOUR, note: 'ডেমো রিকোয়েস্ট (আসল নয়) — O- গ্রুপ দরকার',
  });
  await request('demo-rq-far', X.polash, {
    group: 'B+', bags: 1, hospital: 'ডেমো ত্রিশাল উপজেলা স্বাস্থ্য কমপ্লেক্স', dx: -4, dy: -16,
    neededBy: dayOf(ctx, 5), createdAt: now - 9 * HOUR, note: 'ডেমো রিকোয়েস্ট (আসল নয়) — ১০ কিমির বেশি দূরে',
  });
  // ডেমো ডোনার আগেই রাজি হয়েছেন আর রক্তপ্রার্থী তাঁকে নিশ্চিত করেছেন (হোমে "প্রসেসিং", নম্বর দেখা যায়)
  const rqD = await request('demo-rq-donor-confirmed', X.rehana, {
    group: 'A+', bags: 1, hospital: 'ডেমো নেত্রকোণা মোড় ক্লিনিক', dx: 1.4, dy: 0.2,
    neededBy: dayOf(ctx, 2), neededAt: dhakaAt(dayOf(ctx, 2), 16, 0), createdAt: now - 10 * HOUR,
    note: 'ডেমো রিকোয়েস্ট (আসল নয়) — হার্টের অপারেশন',
    acceptedCount: 1, confirmedCount: 1, fulfilled: true,
  });
  await respond(rqD, D, { confirmed: true, at: now - 8 * HOUR });

  // ======================= ৪) জরুরি আহ্বান (Lifolo টিম) =======================
  const eLoc = spot(ctx, 0.3, 0.2);
  const radiusKm = 25;
  const districts = [...new Set([DISTRICT, ...DISTRICTS.filter((d) => R.distanceKm(eLoc.lat, eLoc.lng, d.lat, d.lng) <= radiusKm + 25).map((d) => d.bn)])].slice(0, 30);
  const E1 = 'demo-em-live';
  await ctx.set(`emergencies/${E1}`, {
    hospitalId: null, hospitalName: 'ডেমো মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ)', district: DISTRICT, districts,
    lat: eLoc.lat, lng: eLoc.lng, exactLocation: true, radiusKm,
    groups: {
      'O+': { need: 4, coming: 1, notified: 6, closed: false },
      'A+': { need: 3, coming: 0, notified: 4, closed: false },
      'B-': { need: 1, coming: 1, notified: 3, closed: true },
    },
    contactPhone: '01700001310', note: 'ডেমো আহ্বান (আসল নয়) — বাস দুর্ঘটনা, জরুরি বিভাগের ব্লাড ব্যাংকে আসুন',
    status: 'active', createdAt: now - 2 * HOUR, endsAt: now + 46 * HOUR, createdBy: 'demo@lifolo-demo.test', waves: 1, lastWaveAt: now - 2 * HOUR,
  });
  const emDonor = async (eid, d, status, at) => {
    await ctx.set(`emergencies/${eid}/donors/${d.uid}`, { uid: d.uid, group: d.group, status, km: 2.1, notifiedAt: at, ...(status === 'coming' ? { comingAt: at + 600000 } : {}) });
    if (status === 'coming') await ctx.set(`users/${d.uid}`, { emergencyComing: { [eid]: at + 600000 } }, { merge: true });
  };
  await emDonor(E1, X.rafiq, 'coming', now - 2 * HOUR);
  await emDonor(E1, X.mitu, 'coming', now - 2 * HOUR);
  await emDonor(E1, X.sakib, 'notified', now - 2 * HOUR);
  await emDonor(E1, X.nadia, 'notified', now - 2 * HOUR);
  // শেষ হওয়া আহ্বান: ডেমো ডোনার "আসছি" বলেছিলেন — অ্যাপ জিজ্ঞেস করে "রক্ত দিয়েছেন?"
  const E2 = 'demo-em-ended';
  await ctx.set(`emergencies/${E2}`, {
    hospitalId: null, hospitalName: 'ডেমো সদর হাসপাতাল, ময়মনসিংহ', district: DISTRICT, districts,
    lat: home.lat, lng: home.lng, exactLocation: false, radiusKm: 10,
    groups: { 'O+': { need: 2, coming: 2, notified: 6, closed: true } },
    contactPhone: '01700001311', note: 'ডেমো আহ্বান (আসল নয়) — শেষ হয়েছে',
    status: 'closed', closedAt: now - 2 * R.DAY_MS, createdAt: now - 2 * R.DAY_MS - 12 * HOUR, endsAt: now - 2 * R.DAY_MS,
    createdBy: 'demo@lifolo-demo.test', waves: 2, lastWaveAt: now - 2 * R.DAY_MS - 10 * HOUR,
  });
  await emDonor(E2, D, 'coming', now - 2 * R.DAY_MS - 11 * HOUR);
  await emDonor(E2, X.sakib, 'coming', now - 2 * R.DAY_MS - 11 * HOUR);

  // ======================= ৫) ব্লাড ব্যাংক =======================
  const allServices = ['whole_blood', 'components', 'platelets', 'screening', 'donate_here'];
  async function bank(id, ownerUid, f, opts = {}) {
    const data = {
      name: f.name, kind: f.kind, phone: f.phone, district: DISTRICT, upazilaId: SADAR, address: f.address, licenseNo: f.licenseNo || '',
      open24: f.open24 === true, openAt: f.open24 ? '' : f.openAt, closeAt: f.open24 ? '' : f.closeAt, services: f.services,
      closedDays: f.closedDays || [], updatedAt: now - 20 * R.DAY_MS,
    };
    const approved = opts.status === 'approved';
    await ctx.set(`bloodBanks/${id}`, {
      ...data, ownerUid, status: opts.status, createdAt: now - (opts.ageDays || 30) * R.DAY_MS,
      docsHave: opts.docs || {},
      ...(ownerUid ? {} : { addedByAdmin: true }),
      ...(approved ? { reviewedAt: now - 25 * R.DAY_MS, reviewedBy: 'demo', approvedName: data.name, lastApproved: { ...snapshot(data), demo: true } } : {}),
    });
    for (const t of Object.keys(opts.docs || {})) {
      await ctx.set(`verifyDocs/bloodbank_${id}_${t}`, { kind: 'bloodbank', listingId: id, type: t, ownerUid: ownerUid || null, data: DEMO_JPEG, size: DEMO_JPEG.length, at: opts.docs[t] });
    }
    // মালিকের হিসাব: গত কয়েক দিনের ফোন
    if (opts.calls) {
      const d = {};
      opts.calls.forEach((n, i) => { if (n) d[R.dhakaDayKey(now - i * R.DAY_MS)] = n; });
      const total = opts.calls.reduce((a, b) => a + b, 0);
      await ctx.set(`bloodBankStats/${id}`, { d, total, callers: Math.max(1, total - 3) });
    }
    // বন্ধ (suspend) করলে যে নিষেধাজ্ঞার নথি হয় সেগুলোর পথ আগে থেকেই তালিকায় — ডেমো মুছলে সেগুলোও মোছে
    // (নিষেধাজ্ঞা শুধু listing: true থাকলে, তাই খালি নথি কিছু আটকায় না)
    for (const k of sanctions.listingKeys('bloodbank', data)) await ctx.set(`sanctions/${k}`, {}, { merge: true });
  }
  const docTime = now - 3 * HOUR;
  await bank('demo-bb-mine', U.bloodbank.uid, {
    name: 'ডেমো জীবনধারা ব্লাড ব্যাংক', kind: 'private', phone: '01700001301', licenseNo: 'DEMO-BB-0001',
    address: 'ডেমো ভবন, চরপাড়া মোড়', open24: true, services: allServices,
  }, { status: 'approved', docs: { front: docTime, license: docTime }, calls: [3, 5, 2, 0, 4, 6, 1, 2, 0, 3, 2, 5, 1, 0, 2] });
  await bank('demo-bb-pending', U.bloodbank.uid, {
    name: 'ডেমো রক্তবন্ধু স্বেচ্ছাসেবী সংগঠন', kind: 'org', phone: '01700001302',
    address: 'ডেমো অফিস, গাঙ্গিনারপাড়', openAt: '10:00', closeAt: '18:00', services: ['whole_blood', 'donate_here'],
  }, { status: 'pending', ageDays: 1, docs: { front: docTime } });
  await bank('demo-bb-govt', X.bbowner.uid, {
    name: 'ডেমো সদর হাসপাতাল ব্লাড ব্যাংক', kind: 'govt', phone: '01700001303',
    address: 'ডেমো সদর হাসপাতাল, ২য় তলা, হাসপাতাল রোড', openAt: '08:00', closeAt: '20:00', closedDays: [5], services: ['whole_blood', 'screening', 'donate_here'],
  }, { status: 'approved', calls: [1, 0, 2, 1] });
  await bank('demo-bb-club', null, {
    name: 'ডেমো আলোর পথ ব্লাড ডোনার ক্লাব', kind: 'org', phone: '01700001304',
    address: 'ডেমো ক্লাবঘর, কাচিঝুলি', openAt: '09:00', closeAt: '17:00', services: ['whole_blood', 'platelets', 'donate_here'],
  }, { status: 'approved', ageDays: 60, calls: [0, 1, 0, 0, 2] });
  // অ্যাডমিনের রিপোর্টের তালিকায়: সরকারি ব্লাড ব্যাংকে ফোন করে খারাপ অভিজ্ঞতা (রিপোর্ট শুধু ফোন করা মানুষই দিতে পারেন)
  await ctx.set(`bloodBankCalls/${X.mahmud.uid}_demo-bb-govt`, { uid: X.mahmud.uid, bankId: 'demo-bb-govt', at: now - 30 * HOUR });
  await ctx.set(`modReports/bloodBanks_demo-bb-govt__${X.mahmud.uid}`, {
    kind: 'bloodbank', targetPath: 'bloodBanks/demo-bb-govt', reporterUid: X.mahmud.uid,
    reason: 'ডেমো রিপোর্ট (আসল নয়) — সন্ধ্যা ৭টায় গিয়ে বন্ধ পেয়েছি, ফোনও ধরেনি', status: 'open', createdAt: now - 28 * HOUR,
  });

  // ======================= ৬) হাসপাতাল: অ্যাপ থেকে যোগ হওয়া (অনুমোদিত) + যোগের অনুরোধ (অপেক্ষায়) =======================
  const H1 = { nameEn: 'Demo Charpara General Hospital', nameBn: 'ডেমো চরপাড়া জেনারেল হাসপাতাল' };
  await ctx.set('hospitals/demo-hosp-charpara', {
    ...H1, type: 'private_hospital', district: DISTRICT, upazilaId: SADAR, unionId: null,
    address: 'ডেমো রোড, চরপাড়া', phone: '01700001320', normKey: normalizeName(H1.nameEn),
    source: 'user', status: 'approved', createdAt: now - 50 * R.DAY_MS,
  });
  const H2 = { nameEn: 'Demo Kachijhuli Mother and Child Clinic', nameBn: 'ডেমো কাচিঝুলি মা ও শিশু ক্লিনিক' };
  await ctx.set('hospitalRequests/demo-hreq-kachijhuli', {
    ...H2, type: 'clinic', district: DISTRICT, upazilaId: SADAR, unionId: null,
    address: 'ডেমো গলি, কাচিঝুলি', phone: '01700001321', normKey: normalizeName(H2.nameEn),
    requesterUids: [P.uid, X.kamal.uid], count: 2, status: 'pending', createdAt: now - 20 * HOUR,
  });

  // ======================= ৭) শেষ রক্তদানের তারিখ সংশোধনের অনুরোধ (আগের ব্যবস্থা — অ্যাডমিন দেখেন) =======================
  await ctx.set('correctionRequests/demo-corr-farhana', {
    uid: X.farhana.uid, name: X.farhana.name, currentMillis: daysAgo(40), requestedMillis: daysAgo(130),
    reason: 'ডেমো অনুরোধ (আসল নয়) — রক্তপ্রার্থী ভুল করে আমার নাম দিয়েছিলেন, আসলে ৪ মাস আগে দিয়েছি',
    status: 'pending', createdAtMillis: now - 6 * HOUR,
  });

  // ======================= ৮) ঘণ্টার নোটিফিকেশন (অ্যাপের "নোটিফিকেশন" তালিকা) =======================
  async function inbox(uid, at, title, body, data) {
    const rev = String(9999999999999 - at).padStart(13, '0');
    await ctx.set(`inbox/${uid}_${rev}_demo${String(at % 1000).padStart(3, '0')}`, { uid, title, body, data, createdAt: at });
  }
  await inbox(P.uid, now - 3 * HOUR, 'একজন ডোনার রাজি হয়েছেন', `${X.nadia.name} (A+) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।`, { requestId: pOpen.id, type: 'response' });
  await inbox(P.uid, now - 4 * HOUR - 1, 'একজন ডোনার রাজি হয়েছেন', `${X.sakib.name} (O+) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।`, { requestId: pOpen.id, type: 'response' });
  await inbox(D.uid, now - 7 * HOUR, 'আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন', `${rqD.hospital} · ${R.formatTimeBn(rqD.neededAtMillis)}`, { requestId: rqD.id, type: 'request' });
  await inbox(D.uid, now - 2 * HOUR - 7, 'জরুরি: O+ রক্ত দরকার', 'ডেমো মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ) · প্রায় ২ কিমি দূরে · Lifolo টিমের আহ্বান — খুলে "আসছি" চাপুন', { type: 'emergency', emergencyId: E1 });
}

module.exports = { seed };
