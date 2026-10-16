// ডিজিটাল প্রেসক্রিপশন: যাচাই, কোড, তারিখ
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { db: {}, FieldValue: {}, HttpError, sendPush: async () => 0, tokensOf: () => [] },
};
const P = require('../lib/prescriptions');
const req = { body: { lang: 'en' } };

test('ফোন নম্বরের চাবি: একই নম্বরের সব রূপে এক, নম্বর নিজে নেই', () => {
  const a = P.phoneKey('01712345678');
  assert.ok(a && a.startsWith('p_'));
  assert.strictEqual(P.phoneKey('+8801712345678'), a);
  assert.ok(!a.includes('1712345678'));
  assert.strictEqual(P.phoneKey('123'), null);
});

test('ঢাকার আজকের তারিখ (UTC মধ্যরাত)', () => {
  // ঢাকায় ১৭ অক্টোবর রাত ১টা = UTC ১৬ অক্টোবর সন্ধ্যা ৭টা
  assert.strictEqual(P.dhakaToday(Date.UTC(2026, 9, 16, 19, 0)), Date.UTC(2026, 9, 17));
  assert.strictEqual(P.dhakaToday(Date.UTC(2026, 9, 16, 17, 59)), Date.UTC(2026, 9, 16));
});

test('ওষুধ যাচাই', () => {
  const m = P._parse.parseMedicines(req, [{ name: ' Napa ', strength: '500 mg', form: 'Tablet', dose: '1 + 0 + 1', timing: 'after', days: 5 }]);
  assert.deepStrictEqual(m[0], { name: 'Napa', form: 'Tablet', strength: '500 mg', dose: '1+0+1', timing: 'after', days: 5, note: '' });
  assert.strictEqual(P._parse.parseMedicines(req, [{ name: 'X', days: 0 }])[0].days, 0);
  assert.throws(() => P._parse.parseMedicines(req, [{ name: '' }]));
  assert.throws(() => P._parse.parseMedicines(req, [{ name: 'X', days: 400 }]));
  assert.throws(() => P._parse.parseMedicines(req, [{ name: 'X', days: 2.5 }]));
  assert.strictEqual(P._parse.parseMedicines(req, [{ name: 'X', timing: 'weird' }])[0].timing, '');
  assert.ok(P.DOSE_RE.test('1+0+1') && P.DOSE_RE.test('0+0+1') && P.DOSE_RE.test('½+0+½') && P.DOSE_RE.test('1+1+1+1'));
});

test('রোগী আর পরীক্ষা', () => {
  assert.deepStrictEqual(P._parse.parsePatient(req, { name: 'Karim', age: 40, gender: 'male' }), { name: 'Karim', age: 40, gender: 'male' });
  assert.strictEqual(P._parse.parsePatient(req, { name: 'K', age: '' }).age, null);
  assert.throws(() => P._parse.parsePatient(req, { name: '' }));
  assert.throws(() => P._parse.parsePatient(req, { name: 'K', age: 200 }));
  assert.deepStrictEqual(P._parse.parseTests(req, ['CBC', ' CBC ', '', 'RBS']), ['CBC', 'RBS']);
});

test('যাচাইয়ের কোড', () => {
  assert.ok(P.CODE_RE.test('ABCDEFGH23'));
  assert.ok(!P.CODE_RE.test('ABCDEFGH01')); // ০ আর ১ বাদ (O/I-এর সাথে গুলিয়ে যায়)
  assert.ok(!P.CODE_RE.test('abc'));
});

test('তালিকা: ডিফল্ট, নতুন কোড, মোছা যায় না', () => {
  const Cat = require('../lib/catalog');
  const m = Cat.merge(null);
  assert.ok(m.specialty.doctor.some((i) => i.code === 'medicine' && i.bn === 'মেডিসিন'));
  assert.ok(Cat.codes('ambulanceType').includes('icu'));
  // সংরক্ষিত তালিকায় ক্রম বদল আর নতুন নাম; বাদ পড়া ডিফল্ট শেষে থাকে
  const m2 = Cat.merge({ specialty: { doctor: [{ code: 'eye', en: 'Eye', bn: 'চোখ' }, { code: 'c_physio', en: 'Physio', bn: 'ফিজিও' }] } });
  assert.strictEqual(m2.specialty.doctor[0].code, 'eye');
  assert.strictEqual(m2.specialty.doctor[0].bn, 'চোখ');
  assert.ok(m2.specialty.doctor.some((i) => i.code === 'medicine'));
  assert.strictEqual(Cat.makeCode('Physio therapy', new Set(['c_physio_therapy'])), 'c_physio_therapy_2');
});
