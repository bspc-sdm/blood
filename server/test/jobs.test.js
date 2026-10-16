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
