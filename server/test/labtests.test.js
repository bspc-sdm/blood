// রিপোর্টের ছক, কেয়ারগিভারের কাজের সীমা
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    if (code) this.code = code;
  }
}
require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { admin: {}, db: {}, FieldValue: {}, HttpError, sendPush: async () => 0, tokensOf: () => [] },
};
const LT = require('../lib/labtests');
const CA = require('../lib/careaccess');
const DEF = require('../lib/labtests-defaults.json');
const req = { body: { lang: 'en' } };

test('ডাক্তারের লেখা নাম থেকে ছক', () => {
  assert.strictEqual(LT.match('CBC')?.code, 'cbc');
  assert.strictEqual(LT.match('CBC with ESR')?.code, 'cbc');
  assert.strictEqual(LT.match('S. Creatinine')?.code, 'creat');
  assert.strictEqual(LT.match('Serum Creatinine')?.code, 'creat');
  assert.strictEqual(LT.match('Urine R/E')?.code, 'urine_re');
  assert.strictEqual(LT.match('urine r/e')?.code, 'urine_re');
  assert.strictEqual(LT.match('HbA1c')?.code, 'hba1c');
  assert.strictEqual(LT.match('X-ray chest P/A view')?.code, 'cxr');
  assert.strictEqual(LT.match('Something unknown'), null);
  // অ্যাপের সাধারণ পরীক্ষার বোতাম সবগুলোর ছক আছে
  for (const t of ['CBC', 'RBS', 'FBS', 'HbA1c', 'Lipid profile', 'S. Creatinine', 'SGPT', 'Urine R/E', 'X-ray chest P/A', 'ECG', 'USG of whole abdomen', 'TSH']) assert.ok(LT.match(t), t);
});

test('ছকের নাম কোনো দুই পরীক্ষায় এক নয়', () => {
  const seen = new Map();
  for (const t of DEF) {
    for (const k of [t.code, t.name, ...(t.aliases || [])].map(LT.norm)) {
      assert.ok(!seen.has(k) || seen.get(k) === t.code, `${k}: ${seen.get(k)} / ${t.code}`);
      seen.set(k, t.code);
    }
    const keys = t.params.map((p) => p.key);
    assert.strictEqual(new Set(keys).size, keys.length, t.code);
    for (const p of t.params) for (const r of [p.r, p.m, p.f].filter(Boolean)) assert.ok(r[0] == null || r[1] == null || r[0] <= r[1], `${t.code}.${p.key}`);
  }
});

test('নারী/পুরুষ ভেদে মাত্রা আর বেশি/কম', () => {
  const hb = DEF.find((t) => t.code === 'cbc').params.find((p) => p.key === 'hb');
  assert.deepStrictEqual(LT.rangeFor(hb, 'female'), { low: 12, high: 15, range: '12 – 15' });
  assert.deepStrictEqual(LT.rangeFor(hb, 'male'), { low: 13, high: 17, range: '13 – 17' });
  assert.strictEqual(LT.rangeFor(hb, '').range, 'M: 13 – 17, F: 12 – 15');
  assert.strictEqual(LT.flagOf('11.2', 12, 15), 'low');
  assert.strictEqual(LT.flagOf('16', 12, 15), 'high');
  assert.strictEqual(LT.flagOf('13', 12, 15), '');
  assert.strictEqual(LT.flagOf('210', null, 200), 'high');
  assert.strictEqual(LT.flagOf('Negative', null, 200), '');
  assert.strictEqual(LT.flagOf('1,250', 150, 410), 'high');
});

test('ল্যাবের ফর্ম: নিজের মাত্রা থাকলে সেটা', () => {
  const f = LT.formFor('FBS', 'male', { 'fbs.glu_f': { low: 3.5, high: 6 } });
  assert.strictEqual(f.code, 'fbs');
  assert.strictEqual(f.params[0].low, 3.5);
  assert.strictEqual(f.params[0].range, '3.5 – 6');
  const none = LT.formFor('Bone scan', 'male');
  assert.strictEqual(none.code, null);
  assert.deepStrictEqual(none.params, []);
});

test('অ্যাডমিনের ছক যাচাই', () => {
  const t = LT.cleanTest(req, { name: 'Serum Magnesium', aliases: 's magnesium, magnesium', params: [{ name: 'Magnesium', unit: 'mg/dL', r: ['1.7', '2.2'] }] }, new Set());
  assert.strictEqual(t.code, 'serum_magnesium');
  assert.deepStrictEqual(t.params[0].r, [1.7, 2.2]);
  assert.deepStrictEqual(t.aliases, ['s magnesium', 'magnesium']);
  assert.throws(() => LT.cleanTest(req, { name: 'X', params: [{ name: 'A', r: [5, 2] }] }, new Set()), /normal range/);
  assert.throws(() => LT.cleanTest(req, { name: 'X', params: [] }, new Set()), /rows/);
});

test('কেয়ারগিভার: প্রোফাইল/অ্যাকাউন্টের কাজ পারেন না', async () => {
  for (const a of ['rx_mine', 'rx_send_pharmacy', 'review_pay', 'book', 'rx_order_withdraw']) assert.ok(CA.CARE_ACTIONS.has(a), a);
  for (const a of ['delete_account', 'profile_save', 'care_invite', 'care_remove', 'family_set', 'request_create']) assert.ok(!CA.CARE_ACTIONS.has(a), a);
  const user = { uid: 'caretakerUid1' };
  assert.strictEqual(await CA.actAs({ body: { action: 'rx_mine' } }, user), user);
  await assert.rejects(CA.actAs({ body: { action: 'delete_account', asUid: 'patientUid12' } }, user), (e) => e.status === 403);
});

test('পরিবারের সদস্য যাচাই', () => {
  const m = CA._clean.cleanMember(req, { name: 'Amma', age: 70, gender: 'female', phone: '01712345678' }, 0);
  assert.match(m.id, /^m_[a-f0-9]{10}$/);
  assert.strictEqual(m.phone, '01712345678');
  assert.throws(() => CA._clean.cleanMember(req, { name: '' }, 0));
  assert.throws(() => CA._clean.cleanMember(req, { name: 'A', phone: '123' }, 0));
});
