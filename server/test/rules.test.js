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
