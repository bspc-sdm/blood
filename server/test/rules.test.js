const test = require('node:test');
const assert = require('node:assert');
const R = require('../lib/rules');
const DISTRICTS = require('../lib/districts');

const DAY = R.DAY_MS;

test('রক্তের গ্রুপ মেলানো', () => {
  assert.ok(R.canDonate('O-', 'AB+'));
  assert.ok(R.canDonate('O-', 'O-'));
  assert.ok(!R.canDonate('O+', 'O-'));
  assert.ok(!R.canDonate('A+', 'B+'));
  assert.ok(R.canDonate('B-', 'AB-'));
  assert.ok(!R.canDonate('AB+', 'A+'));
});

test('১২০ দিনের যোগ্যতা', () => {
  const now = Date.UTC(2026, 9, 15);
  assert.ok(R.isEligible(null, now));
  assert.ok(!R.isEligible(now - 119 * DAY, now));
  assert.ok(R.isEligible(now - 120 * DAY, now));
});

test('দূরত্ব: ঢাকা থেকে ময়মনসিংহ প্রায় ১১০ কিমি', () => {
  const dhaka = DISTRICTS.find((d) => d.en === 'Dhaka');
  const mym = DISTRICTS.find((d) => d.en === 'Mymensingh');
  const km = R.distanceKm(dhaka.lat, dhaka.lng, mym.lat, mym.lng);
  assert.ok(km > 100 && km < 125, String(km));
});

test('কাছের জেলা: ময়মনসিংহের কেন্দ্রে নিজের জেলা প্রথমে, ৩০টার বেশি না', () => {
  const near = R.nearDistricts(24.7466, 90.4072);
  assert.strictEqual(near[0], DISTRICTS.find((d) => d.en === 'Mymensingh').bn);
  assert.ok(near.length >= 1 && near.length <= 30);
});

test('বাংলাদেশের তারিখ: রাত ১টা (UTC আগের দিন ১৯টা) হলেও আজকের তারিখ', () => {
  const dhaka1am = Date.UTC(2026, 9, 14, 19, 0); // ১৫ অক্টোবর রাত ১টা ঢাকায়
  assert.strictEqual(R.todayDhakaUtcMidnight(dhaka1am), Date.UTC(2026, 9, 15));
  assert.strictEqual(R.dhakaDayKey(dhaka1am), '20261015');
});

test('ডোনার বাছাই: গ্রুপ, যোগ্যতা, নিজে বাদ, ধাপে ধাপে দূরত্ব', () => {
  const now = Date.UTC(2026, 9, 15);
  const request = { requesterUid: 'me', patientGroup: 'A+', lat: 24.75, lng: 90.4 };
  const base = { fcmToken: 't', notify: true, lastDonationMillis: null, birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60 };
  const users = [
    { ...base, uid: 'me', bloodGroup: 'A+', lat: 24.75, lng: 90.4 }, // নিজে
    { ...base, uid: 'near', bloodGroup: 'O-', lat: 24.76, lng: 90.4 }, // ~১ কিমি
    { ...base, uid: 'wrongGroup', bloodGroup: 'B+', lat: 24.75, lng: 90.41 },
    { ...base, uid: 'notEligible', bloodGroup: 'A+', lat: 24.75, lng: 90.41, lastDonationMillis: now - 10 * DAY },
    { ...base, uid: 'off', bloodGroup: 'A+', lat: 24.75, lng: 90.41, notify: false },
    { ...base, uid: 'noToken', bloodGroup: 'A+', lat: 24.75, lng: 90.41, fcmToken: null },
    { ...base, uid: 'far20', bloodGroup: 'A-', lat: 24.93, lng: 90.4, maxKm: 25 }, // ~২০ কিমি, ২৫ কিমি পর্যন্ত যেতে রাজি
    { ...base, uid: 'far60', bloodGroup: 'A-', lat: 25.29, lng: 90.4 }, // ~৬০ কিমি
  ];
  const picked = R.pickDonors(request, users, now).map((u) => u.uid);
  // ৫ কিমিতে ১ জন (<১০), ১০ কিমিতে ১ জন, ২৫ কিমিতে ২ জন
  assert.deepStrictEqual(picked, ['near', 'far20']);
});

test('রিকোয়েস্ট যাচাই: ঠিক তথ্য পাস, ভুল তথ্য আটকায়', () => {
  const now = Date.UTC(2026, 9, 15);
  const mym = DISTRICTS.find((d) => d.en === 'Mymensingh');
  const good = { patientGroup: 'A+', bags: 2, hospital: 'মমেক', placeName: mym.bn, lat: 24.75, lng: 90.4, neededByMillis: now };
  assert.strictEqual(R.validateRequest(good, now), null);
  assert.ok(R.validateRequest({ ...good, patientGroup: 'Z+' }, now));
  assert.ok(R.validateRequest({ ...good, bags: 50 }, now));
  assert.ok(R.validateRequest({ ...good, placeName: 'লন্ডন' }, now));
  assert.ok(R.validateRequest({ ...good, lat: 51.5 }, now));
  assert.ok(R.validateRequest({ ...good, neededByMillis: now + 90 * DAY }, now));
  assert.ok(R.validateRequest({ ...good, hospital: 'x'.repeat(200) }, now));
});

test('আইডি যাচাই', () => {
  assert.ok(R.isValidId('abcDEF_123-x'));
  assert.ok(!R.isValidId('a/b'));
  assert.ok(!R.isValidId(''));
  assert.ok(!R.isValidId(42));
});

test('বয়স আর ওজনসহ যোগ্যতা', () => {
  const now = Date.UTC(2026, 9, 15);
  const ok = { birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60, lastDonationMillis: null };
  assert.ok(R.isEligibleDonor(ok, now));
  assert.ok(!R.isEligibleDonor({ ...ok, weightKg: 45 }, now));
  assert.ok(!R.isEligibleDonor({ ...ok, birthDateMillis: Date.UTC(2010, 0, 1) }, now)); // ১৬ বছর
  assert.ok(!R.isEligibleDonor({ ...ok, birthDateMillis: Date.UTC(1960, 0, 1) }, now)); // ৬৬ বছর
  assert.ok(!R.isEligibleDonor({ ...ok, birthDateMillis: null }, now)); // জন্ম তারিখ নেই
  // ১৮তম জন্মদিনের আগের দিন অযোগ্য, জন্মদিনে যোগ্য
  assert.strictEqual(R.ageYears(Date.UTC(2008, 9, 16), now), 17);
  assert.strictEqual(R.ageYears(Date.UTC(2008, 9, 15), now), 18);
});

test('isActive: তারিখের পরের দিন পর্যন্ত খোলা', () => {
  const needed = Date.UTC(2026, 9, 15);
  assert.ok(R.isActive({ status: 'open', neededByMillis: needed }, needed + 23 * 3600e3));
  assert.ok(!R.isActive({ status: 'open', neededByMillis: needed }, needed + DAY + 1));
  assert.ok(!R.isActive({ status: 'closed', neededByMillis: needed }, needed));
});

test('সেটিংস: কোন গ্রুপের নোটিফিকেশন চান', () => {
  const now = Date.UTC(2026, 9, 15);
  const request = { requesterUid: 'me', patientGroup: 'A+', lat: 24.75, lng: 90.4 };
  const base = { fcmToken: 't', notify: true, birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60, bloodGroup: 'O-', lat: 24.75, lng: 90.41 };
  const users = [
    { ...base, uid: 'all' }, // কিছু বাছেননি → সবার
    { ...base, uid: 'onlyAplus', notifyGroups: ['A+', 'O-'] },
    { ...base, uid: 'onlyB', notifyGroups: ['B+', 'B-'] }, // A+ চান না
    { ...base, uid: 'stale', bloodGroup: 'A+', notifyGroups: ['AB-'] }, // গ্রুপ বদলেছে, বাছাই অচল → সবার
  ];
  const picked = R.pickDonors(request, users, now).map((u) => u.uid).sort();
  assert.deepStrictEqual(picked, ['all', 'onlyAplus', 'stale']);
});

test('সেটিংস: দিনে কয়টা নোটিফিকেশন', () => {
  assert.strictEqual(R.pushLimit({}), 3);
  assert.strictEqual(R.pushLimit({ maxPushPerDay: 1 }), 1);
  assert.strictEqual(R.pushLimit({ maxPushPerDay: 10 }), 10);
  assert.strictEqual(R.pushLimit({ maxPushPerDay: 500 }), 10);
  assert.strictEqual(R.pushLimit({ maxPushPerDay: 0 }), 1);
  assert.strictEqual(R.pushLimit({ maxPushPerDay: '5' }), 3);
});

test('রক্ত লাগার সময়: বাছা তারিখের ভেতরে (বাংলাদেশের সময়ে)', () => {
  const date = Date.UTC(2026, 9, 20); // ২০ অক্টোবর
  const at = (h, m) => date + (h * 60 + m) * 60 * 1000 - 6 * 60 * 60 * 1000;
  assert.ok(R.neededAtMatchesDate(at(0, 0), date));
  assert.ok(R.neededAtMatchesDate(at(16, 30), date));
  assert.ok(R.neededAtMatchesDate(at(23, 59), date));
  assert.ok(!R.neededAtMatchesDate(at(24, 0), date));
  assert.ok(!R.neededAtMatchesDate(at(-1, 0), date));
  assert.ok(!R.neededAtMatchesDate(1.5, date));
  assert.strictEqual(R.formatTimeBn(at(16, 5)), 'বিকাল ৪:০৫');
  assert.strictEqual(R.formatTimeBn(at(0, 30)), 'রাত ১২:৩০');
  assert.strictEqual(R.formatTimeBn(at(12, 0)), 'দুপুর ১২:০০');
  const now = Date.UTC(2026, 9, 15);
  const mym = DISTRICTS.find((d) => d.en === 'Mymensingh');
  const good = { patientGroup: 'A+', bags: 2, hospital: 'মমেক', placeName: mym.bn, lat: 24.75, lng: 90.4, neededByMillis: date };
  assert.strictEqual(R.validateRequest({ ...good, neededAtMillis: at(10, 0) }, now), null);
  assert.ok(R.validateRequest({ ...good, neededAtMillis: at(30, 0) }, now));
});

test('কমিউনিটি: লেখা পরিষ্কার আর ব্যাজ', () => {
  const C = require('../lib/community');
  assert.strictEqual(C.cleanText('  hi  ', 10), 'hi');
  assert.strictEqual(C.cleanText('a\n\n\n\nb', 10), 'a\n\nb');
  assert.strictEqual(C.cleanText('   ', 10), null);
  assert.strictEqual(C.cleanText('x'.repeat(11), 10), null);
  assert.strictEqual(C.cleanText(5, 10), null);
  assert.ok(C.isBadge('doctor') && C.isBadge('nurse') && C.isBadge('nutritionist'));
  assert.ok(!C.isBadge('admin'));
  assert.strictEqual(C.tr({ body: { lang: 'bn' } }, 'en', 'bn'), 'bn');
  assert.strictEqual(C.tr({ body: {} }, 'en', 'bn'), 'en');
});

test('সার্ভারের বার্তা ইংরেজিতে', () => {
  const { toEnglish } = require('../lib/i18n');
  assert.strictEqual(toEnglish('কোড মেলেনি। আবার দেখে লিখুন।'), 'Wrong code. Please check and try again.');
  assert.strictEqual(toEnglish('একসাথে সর্বোচ্চ 2টা খোলা রিকোয়েস্ট রাখা যায়'), 'You can keep up to 2 open requests at a time');
  assert.strictEqual(toEnglish('Already English'), 'Already English');
});

test('দূরত্বের সীমা আর যাতায়াত খরচ', () => {
  const now = Date.UTC(2026, 9, 15);
  const base = { fcmToken: 't', notify: true, birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60, bloodGroup: 'O-', lng: 90.4 };
  const request = { requesterUid: 'me', patientGroup: 'A+', lat: 24.75, lng: 90.4 };
  const users = [
    { ...base, uid: 'r5_at3', lat: 24.777, maxKm: 5 }, // ~৩ কিমি, সীমা ৫
    { ...base, uid: 'r5_at20', lat: 24.93, maxKm: 5 }, // ~২০ কিমি, সীমা ৫ → বাদ
    { ...base, uid: 'r50_at40', lat: 25.11, maxKm: 50 }, // ~৪০ কিমি, সীমা ৫০
    { ...base, uid: 'default_at20', lat: 24.93 }, // ~২০ কিমি, সীমা ১০ (ডিফল্ট) → বাদ
    { ...base, uid: 'fare5_at20', lat: 24.93, maxKm: 25, fareAfterKm: 5 }, // খরচ লাগবে, রিকোয়েস্টে খরচ নেই → বাদ
  ];
  const ids = (r) => R.pickDonors(r, users, now).map((u) => u.uid).sort();
  assert.deepStrictEqual(ids(request), ['r50_at40', 'r5_at3']);
  assert.deepStrictEqual(ids({ ...request, travelFare: true }), ['fare5_at20', 'r50_at40', 'r5_at3']);
  assert.strictEqual(R.donorRadius({ maxKm: 7 }), 10);
});

test('হাসপাতাল: নাম মেলানো আর ডুপ্লিকেট', () => {
  const H = require('../lib/hospital-match');
  assert.strictEqual(H.normalizeName('Mymensingh Medical College Hospital'), 'mymensingh medical college');
  assert.strictEqual(H.normalizeName('Popular Diagnostic Centre Ltd.'), 'popular diagnostic');
  const a = { nameEn: 'Popular Diagnostic Center Ltd', district: 'ময়মনসিংহ', upazilaId: 10 };
  assert.ok(H.isDuplicate(a, { nameEn: 'Popular Diagnostic Centre', district: 'ময়মনসিংহ', upazilaId: 10 }));
  assert.ok(H.isDuplicate(a, { nameEn: 'Populer Diagnostic', district: 'ময়মনসিংহ', upazilaId: null }));
  assert.ok(!H.isDuplicate(a, { nameEn: 'Popular Diagnostic', district: 'ঢাকা', upazilaId: 10 }));
  assert.ok(!H.isDuplicate(a, { nameEn: 'Popular Diagnostic', district: 'ময়মনসিংহ', upazilaId: 11 }));
  assert.ok(!H.isDuplicate(a, { nameEn: 'Labaid Hospital', district: 'ময়মনসিংহ', upazilaId: 10 }));
  assert.ok(H.UPAZILA.size > 480 && H.UNION.size > 4000);
});

test('DGHS তালিকা মেলানো', () => {
  const D = require('../lib/dghs');
  const ourDistricts = ['Bagerhat', 'Barishal', 'Bogura', "Cox's Bazar", 'Cumilla', 'Jashore', 'Jhalokathi', 'Khagrachari', 'Maulvibazar', 'Netrakona', 'Chattogram', 'Mymensingh'];
  ourDistricts.forEach((d) => assert.ok(D.mapDistrict(d), d));
  const h = D.toHospital(['5', 'Mymensingh Medical College Hospital', 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল, ময়মনসিংহ', 'Medical College Hospital', 'Mymensingh', 'Mymensingh Sadar', '', '', '', '0']);
  assert.strictEqual(h.id, 'dghs_5');
  assert.strictEqual(h.data.type, 'govt_mch');
  assert.strictEqual(h.data.nameBn, 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল');
  assert.ok(h.data.upazilaId, 'upazila matched');
  const u = D.toHospital(['9', 'Trishal Upazila Health Complex', '', 'Upazila Health Complex', 'Mymensingh', 'Trishal', '', '', '', '0']);
  assert.strictEqual(u.data.type, 'govt_uhc');
  assert.ok(u.data.upazilaId);
});

test('চেম্বার: যাচাই, পুরোনো ডক, ফিল্টার', () => {
  const K = require('../lib/care');
  const hospitals = new Map([['h1', { id: 'h1', nameEn: 'Trishal UHC', nameBn: 'ত্রিশাল', district: 'ময়মনসিংহ', upazilaId: 10, unionId: null }]]);
  assert.ok(K.parseChambers([], hospitals).error);
  assert.ok(K.parseChambers([{ hospitalId: 'x', days: [1], start: '09:00', end: '10:00' }], hospitals).error);
  const t = { days: [6, 0], start: '17:00', end: '21:00' };
  assert.ok(K.parseChambers([{ hospitalId: 'h1', days: [], start: '17:00', end: '21:00' }], hospitals).error);
  assert.ok(K.parseChambers([{ hospitalId: 'h1', days: [1], start: '17:00', end: '17:00' }], hospitals).error);
  assert.ok(!K.parseChambers([{ hospitalId: 'h1', days: [1], start: '20:00', end: '00:00' }], hospitals).error); // রাত ১২টা পর্যন্ত
  assert.strictEqual(K.scheduleText({ days: [0, 6], start: '17:00', end: '21:00', schedule: 'x' }), 'Sat, Sun · 17:00–21:00 · x');
  assert.ok(K.parseChambers(Array(4).fill({ hospitalId: 'h1', ...t }), hospitals).error);
  const ok = K.parseChambers([{ hospitalId: 'h1', ...t, schedule: 'Fridays closed', appointments: true, serialPhone: '017-1234 5678' }], hospitals);
  assert.deepStrictEqual(ok.chambers[0].days, [0, 6]);
  assert.ok(K.chamberOpenOn(ok.chambers[0], Date.UTC(2026, 9, 17))); // শনিবার
  assert.ok(!K.chamberOpenOn(ok.chambers[0], Date.UTC(2026, 9, 19))); // সোমবার
  assert.ok(K.chamberOpenOn({ schedule: 'old' }, Date.UTC(2026, 9, 19)));
  assert.strictEqual(ok.chambers[0].district, 'ময়মনসিংহ');
  assert.strictEqual(ok.chambers[0].serialPhone, '01712345678');

  const legacy = { type: 'doctor', chamber: 'My chamber', district: 'ঢাকা', schedule: 'Sun', active: true };
  assert.strictEqual(K.chambersOf(legacy)[0].hospitalName, 'My chamber');
  assert.ok(K.isListed(legacy) && K.takesAppointments(legacy));
  assert.ok(!K.isListed({ active: false }));

  const doc = { specialties: ['medicine'], chambers: ok.chambers };
  assert.ok(K.matchesFilter(doc, { district: 'ময়মনসিংহ', upazilaId: 10 }));
  assert.ok(K.matchesFilter(doc, { hospitalId: 'h1', specialty: 'medicine' }));
  assert.ok(!K.matchesFilter(doc, { specialty: 'eye' }));
  assert.ok(!K.matchesFilter(doc, { district: 'ঢাকা' }));
  assert.ok(!K.matchesFilter(doc, { upazilaId: 11 }));
  assert.ok(K.matchesFilter(doc, {}));
});

test('বাসায় সেবা: সেটিং, ফোন, এলাকা মেলানো', () => {
  const K = require('../lib/care');
  assert.ok(K.parseHomeVisit({ enabled: true, services: [], phone: '01712345678' }).error);
  // চালু করলে রোগীরা যে নম্বরে ফোন করবেন তা লাগবে
  assert.ok(K.parseHomeVisit({ enabled: true, services: ['dressing'] }).error);
  assert.ok(K.parseHomeVisit({ enabled: true, services: ['dressing'], phone: '12345' }).error);
  const h = K.parseHomeVisit({ enabled: true, services: ['injection', 'bogus', 'dressing'], radiusKm: 7, upazilaIds: [1, 1, 'x'], phone: '+8801712-345678' }).homeVisit;
  assert.deepStrictEqual(h.services, ['injection', 'dressing']);
  assert.strictEqual(h.radiusKm, 5);
  assert.deepStrictEqual(h.upazilaIds, [1]);
  assert.strictEqual(h.phone, '01712345678');
  // ডাক্তারের আলাদা সেবার তালিকা
  const d = K.parseHomeVisit({ enabled: true, services: ['checkup', 'injection', 'bedridden'], radiusKm: 20, phone: '01812345678' }, 'doctor').homeVisit;
  assert.deepStrictEqual(d.services, ['checkup', 'bedridden']);
  assert.strictEqual(d.radiusKm, 20);
  const off = K.parseHomeVisit({ enabled: false, services: ['dressing'], radiusKm: 10 }).homeVisit;
  assert.strictEqual(off.enabled, false);
  assert.deepStrictEqual(off.services, ['dressing']);

  const p = { homeVisit: h, lat: 23.81, lng: 90.41, locationSource: 'gps', placeName: 'ঢাকা' };
  // খুঁজছেন যিনি তাঁর GPS থেকে: ব্যাসার্ধের মধ্যে হলে দূরত্ব সহ
  const near = K.homeMatch(p, { lat: 23.83, lng: 90.42 });
  assert.ok(near && near.km > 2 && near.km < 3);
  assert.strictEqual(K.homeMatch(p, { lat: 24.5, lng: 90.4 }), null);
  assert.ok(K.homeMatch(p, { lat: 24.5, lng: 90.4, upazilaId: 1 })); // বেছে রাখা উপজেলা
  // শুধু জেলা বেছে নেওয়া লোকেশন দিয়ে দূরত্ব ধরা হয় না
  assert.strictEqual(K.homeMatch({ ...p, locationSource: 'district' }, { lat: 23.83, lng: 90.42 }), null);
  // এলাকা বেছে খুঁজলে: উপজেলা মিললে, অথবা একই জেলায় GPS
  assert.ok(K.homeMatch(p, { district: 'কুমিল্লা', upazilaId: 1 }));
  assert.ok(K.homeMatch(p, { district: 'কুমিল্লা' })); // উপজেলা ১ কুমিল্লায়
  assert.ok(K.homeMatch(p, { district: 'ঢাকা' }));
  assert.strictEqual(K.homeMatch({ ...p, homeVisit: { ...h, upazilaIds: [] } }, { district: 'কুমিল্লা' }), null);
  assert.strictEqual(K.homeMatch({ ...p, homeVisit: { ...h, enabled: false } }, {}), null);
  assert.strictEqual(K.homeMatch({ ...p, homeVisitBlocked: true }, {}), null);
});

test('হাসপাতালের স্থির তালিকা', () => {
  const DIR = require('../lib/hospital-dir');
  const list = DIR.inDistrict('ঢাকা');
  assert.ok(list.length > 1000);
  const h = DIR.find(list[0].id);
  assert.strictEqual(h.nameEn, list[0].nameEn);
  assert.strictEqual(DIR.find('dghs_0'), null);
  // সিটি কর্পোরেশনের থানা এলাকার তালিকায় আছে
  const { UPAZILA } = require('../lib/hospital-match');
  assert.ok([...UPAZILA.values()].some((u) => u.district === 'ঢাকা' && u.en === 'Dhanmondi'));
});

test('ফোন নম্বর স্বাভাবিক করা', () => {
  const P = require('../lib/care');
  assert.strictEqual(P.normalizeBd('01712-345678'), '8801712345678');
  assert.strictEqual(P.normalizeBd('+8801912345678'), '8801912345678');
  assert.strictEqual(P.normalizeBd('1712345678'), '8801712345678');
  assert.strictEqual(P.normalizeBd('01212345678'), null);
  assert.strictEqual(P.normalizeBd('123'), null);
});

test('ফার্মেসি: খোলা সময় আর কাছের দোকান বাছাই', () => {
  const K = require('../lib/care');
  const at = (h, m) => Date.UTC(2026, 9, 15, h - 6, m); // বাংলাদেশ সময় h:m
  assert.strictEqual(K.dhakaHHMM(at(9, 5)), '09:05');
  assert.ok(K.pharmacyOpenNow({ openAt: '09:00', closeAt: '23:00' }, at(12, 0)));
  assert.ok(!K.pharmacyOpenNow({ openAt: '09:00', closeAt: '23:00' }, at(23, 30)));
  assert.ok(K.pharmacyOpenNow({ openAt: '18:00', closeAt: '02:00' }, at(1, 0))); // রাত পেরিয়ে
  assert.ok(!K.pharmacyOpenNow({ openAt: '18:00', closeAt: '02:00' }, at(10, 0)));
  assert.ok(K.pharmacyOpenNow({ open24: true }, at(4, 0)));
  const order = { lat: 24.75, lng: 90.4, upazilaId: 7 };
  const ps = [
    { id: 'far', lat: 24.9, lng: 90.4, upazilaId: 8, openAt: '09:00', closeAt: '23:00' },
    { id: 'near', lat: 24.76, lng: 90.4, upazilaId: 8, openAt: '09:00', closeAt: '23:00' },
    { id: 'closed', lat: 24.751, lng: 90.4, openAt: '09:00', closeAt: '10:00' },
    { id: 'sameUpazila', upazilaId: 7, open24: true },
  ];
  assert.deepStrictEqual(K.pickPharmacies(order, ps, at(12, 0)).map((p) => p.id), ['near', 'sameUpazila']);
  assert.deepStrictEqual(K.pickPharmacies(order, ps, at(12, 0), ['near']).map((p) => p.id), ['sameUpazila']);
  assert.ok(K.medOrderExpired({ status: 'open', createdAt: 0 }, K.MED_OPEN_MS + 1));
});

test('রক্তদানের তারিখ বদল: সতর্কতা', () => {
  const R = require('../lib/rules');
  const D = R.DAY_MS;
  assert.strictEqual(R.donationRisk(null, 100 * D), null); // প্রথমবার দেওয়া
  assert.strictEqual(R.donationRisk(500 * D, 400 * D), 'backward');
  assert.strictEqual(R.donationRisk(500 * D, null), 'backward'); // "কখনো দিইনি"
  assert.strictEqual(R.donationRisk(500 * D, 560 * D), 'short_gap'); // ৬০ দিন পর
  assert.strictEqual(R.donationRisk(500 * D, 620 * D), null); // ১২০ দিন
  assert.strictEqual(R.donationRisk(500 * D, 500 * D), null);
});

test('নিজের লেখা চেম্বারের জায়গা (জিম/অফিস): নাম, জেলা, ঠিকানা লাগে', () => {
  const K = require('../lib/care');
  const t = { days: [1], start: '09:00', end: '11:00' };
  const ok = K.parseChambers([{ placeName: 'Power Gym', district: 'ঢাকা', address: 'House 1, Road 2, Dhanmondi', ...t }], new Map(), true);
  // ডাক্তার নিজে লিখতে পারেন না — হাসপাতালের তালিকা থেকে
  assert.ok(K.parseChambers([{ placeName: 'Power Gym', district: 'ঢাকা', address: 'House 1, Road 2, Dhanmondi', ...t }], new Map()).error);
  assert.ok(!ok.error);
  assert.strictEqual(ok.chambers[0].custom, true);
  assert.strictEqual(ok.chambers[0].hospitalId, null);
  assert.ok(K.parseChambers([{ placeName: 'Gym', district: 'নেই', address: 'House 1, Road 2', ...t }], new Map(), true).error);
  assert.ok(K.parseChambers([{ placeName: 'Power Gym', district: 'ঢাকা', address: '', ...t }], new Map(), true).error);
});
