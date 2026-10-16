// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { db: { doc: () => ({}), collection: () => ({}) }, admin: {}, FieldValue: {}, HttpError: class extends Error { constructor(s, m) { super(m); this.status = s; } }, sendPush: async () => 0 },
};
const J = require('../lib/jobs');
const R = require('../lib/rules');

const req = { headers: {}, body: {} };
const job = (x = {}) => ({
  title: 'সেলস এক্সিকিউটিভ', category: 'sales', type: 'fulltime', district: require('../lib/districts').find((d) => d.en === 'Mymensingh').bn, salaryMin: 15000, salaryMax: 22000,
  description: 'দোকানে বিক্রয়, হিসাব রাখা আর গ্রাহকের সাথে কথা বলা। সকাল ৯টা থেকে সন্ধ্যা ৭টা।', deadline: R.todayDhakaUtcMidnight() + 10 * R.DAY_MS, ...x,
});

test('চাকরির বিজ্ঞাপন: বেতন, বর্ণনা, শেষ তারিখ লাগবে', () => {
  const ok = J.parseJob(req, job());
  assert.strictEqual(ok.salaryMin, 15000);
  assert.strictEqual(ok.vacancies, 1);
  assert.throws(() => J.parseJob(req, job({ salaryMin: 0 })));
  assert.throws(() => J.parseJob(req, job({ salaryMax: 100 })));
  assert.throws(() => J.parseJob(req, job({ description: 'ছোট' })));
  assert.throws(() => J.parseJob(req, job({ deadline: R.todayDhakaUtcMidnight() + 120 * R.DAY_MS })));
  assert.throws(() => J.parseJob(req, job({ category: 'x' })));
});

test('চাকরির বিজ্ঞাপনে ফি বা জামানত চাওয়া আটকায়', () => {
  assert.throws(() => J.parseJob(req, job({ description: `${job().description} যোগদানের আগে ৫০০০ টাকা জামানত দিতে হবে।` })));
  assert.throws(() => J.parseJob(req, job({ description: `${job().description} Registration fee 500 Tk.` })));
});

test('সিভি: অসম্পূর্ণ লাইন বাদ, তারিখ উল্টো হলে ভুল, আবেদনের মতো সম্পূর্ণ কি না', () => {
  const p = J.parseProfile(req, {
    headline: 'হিসাবরক্ষক',
    experience: [{ title: 'হিসাবরক্ষক', org: 'আল-আমিন স্টোর', from: '2021-03', current: true }, { title: '', org: 'x' }],
    education: [{ degree: 'বিকম', institute: 'আনন্দ মোহন কলেজ', year: 2020 }],
    skills: ['Excel', 'Excel', 'Tally'],
    contactVisibility: 'bad',
  });
  assert.strictEqual(p.experience.length, 1);
  assert.strictEqual(p.experience[0].to, '');
  assert.deepStrictEqual(p.skills, ['Excel', 'Tally']);
  assert.strictEqual(p.contactVisibility, 'employers');
  assert.strictEqual(p.public, false);
  assert.ok(J.cvComplete(p));
  assert.ok(!J.cvComplete({ headline: 'x', experience: [], education: [] }));
  assert.throws(() => J.parseProfile(req, { experience: [{ title: 'a', org: 'b', from: '2022-05', to: '2021-01' }] }));
});

test('বেতন: আলোচনাসাপেক্ষ চলে, বিনা বেতনের ইন্টার্নশিপে বেতন লাগে না, মেয়াদ ৬ মাস পর্যন্ত', () => {
  const n = J.parseJob(req, job({ salaryMin: 0, salaryMax: 0, salaryNegotiable: true }));
  assert.strictEqual(n.salaryNegotiable, true);
  const i = J.parseJob(req, job({ type: 'internship', paid: false, durationMonths: 3, salaryMin: 0, salaryMax: 0 }));
  assert.strictEqual(i.paid, false);
  assert.strictEqual(i.salaryMax, 0);
  assert.throws(() => J.parseJob(req, job({ type: 'internship', paid: false, durationMonths: 8 })));
  assert.throws(() => J.parseJob(req, job({ type: 'internship', paid: true, durationMonths: 2, salaryMin: 0, salaryMax: 0 })));
  assert.strictEqual(J.parseJob(req, job({ type: 'remote' })).type, 'remote');
});

test('আবেদনের প্রশ্ন: একাধিক বিকল্পে অন্তত ২টা উত্তর, ৫টা পর্যন্ত', () => {
  const j = J.parseJob(req, job({
    questions: [
      { text: 'মোটরসাইকেল চালাতে পারেন?', kind: 'yesno', required: true },
      { text: 'কবে যোগ দিতে পারবেন?', kind: 'choice', options: ['এখনই', '১ মাস পর'] },
      { text: '', kind: 'text' },
    ],
    coverLetter: 'required',
  }));
  assert.strictEqual(j.questions.length, 2);
  assert.strictEqual(j.questions[0].required, true);
  assert.strictEqual(j.coverLetter, 'required');
  assert.throws(() => J.parseJob(req, job({ questions: [{ text: 'x?', kind: 'choice', options: ['a'] }] })));
});

test('স্বয়ংক্রিয় যাচাই: সন্দেহজনক শব্দ, কম বেতন, শর্ত, অস্পষ্ট বর্ণনা ধরা পড়ে', () => {
  const ok = J.flagsFor(J.parseJob(req, job({ description: `${job().description} বছরে দুটো উৎসব বোনাস আর দুপুরের খাবার দেওয়া হবে।` })), J.DEFAULT_WORDS);
  assert.deepStrictEqual(ok, []);
  const codes = (x) => J.flagsFor(J.parseJob(req, job(x)), J.DEFAULT_WORDS).map((f) => f.code);
  assert.ok(codes({ description: `${job().description} ঘরে বসে আয় করুন, দৈনিক ইনকাম ১০০০ টাকা।` }).includes('words'));
  assert.ok(codes({ salaryMin: 2000, salaryMax: 3000 }).includes('low_salary'));
  assert.ok(codes({ description: `${job().description} শুধু পুরুষ প্রার্থী আবেদন করবেন, বয়স ৩০ এর মধ্যে।` }).includes('condition'));
  assert.ok(codes({ description: 'দোকানে কাজ, ভালো বেতন, দ্রুত যোগাযোগ করুন এখনই।' }).includes('vague'));
});

test('সিভি: মোট অভিজ্ঞতা আর পূরণের হার', () => {
  const now = Date.UTC(2026, 9, 16);
  assert.strictEqual(J.experienceYears([{ from: '2021-03', current: true }], now), 5);
  assert.strictEqual(J.experienceYears([{ from: '2020-01', to: '2020-12' }, { from: '2022-01', to: '2022-06' }], now), 1);
  assert.strictEqual(J.cvCompleteness({ headline: 'x', experience: [{}] }, null), 29);
});
