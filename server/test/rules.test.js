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
  const base = { fcmToken: 't', notify: true, lastDonationMillis: null };
  const users = [
    { ...base, uid: 'me', bloodGroup: 'A+', lat: 24.75, lng: 90.4 }, // নিজে
    { ...base, uid: 'near', bloodGroup: 'O-', lat: 24.76, lng: 90.4 }, // ~১ কিমি
    { ...base, uid: 'wrongGroup', bloodGroup: 'B+', lat: 24.75, lng: 90.41 },
    { ...base, uid: 'notEligible', bloodGroup: 'A+', lat: 24.75, lng: 90.41, lastDonationMillis: now - 10 * DAY },
    { ...base, uid: 'off', bloodGroup: 'A+', lat: 24.75, lng: 90.41, notify: false },
    { ...base, uid: 'noToken', bloodGroup: 'A+', lat: 24.75, lng: 90.41, fcmToken: null },
    { ...base, uid: 'far20', bloodGroup: 'A-', lat: 24.93, lng: 90.4 }, // ~২০ কিমি
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

test('isActive: তারিখের পরের দিন পর্যন্ত খোলা', () => {
  const needed = Date.UTC(2026, 9, 15);
  assert.ok(R.isActive({ status: 'open', neededByMillis: needed }, needed + 23 * 3600e3));
  assert.ok(!R.isActive({ status: 'open', neededByMillis: needed }, needed + DAY + 1));
  assert.ok(!R.isActive({ status: 'closed', neededByMillis: needed }, needed));
});
