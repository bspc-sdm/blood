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

test('ওষুধের হিসাব: দিনে কয়টা, কতটা লাগবে, বাকি, কবে মনে করানো', () => {
  const N = require('../lib/rxnet');
  assert.strictEqual(N.perDay('1+0+1'), 2);
  assert.strictEqual(N.perDay('½+0+½'), 1);
  assert.strictEqual(N.perDay('প্রয়োজনে'), 0);
  assert.strictEqual(N.needQty({ dose: '1+1+1', days: 5, form: 'Tablet' }), 15);
  assert.strictEqual(N.needQty({ dose: '1+0+1', days: 0, form: 'Tablet' }), null); // চলবে
  assert.strictEqual(N.needQty({ dose: '1+0+1', days: 5, form: 'Syrup' }), null); // গোনা যায় না
  const day = Date.UTC(2026, 9, 10, 6); // ঢাকায় ১০ অক্টোবর দুপুর
  const rx = {
    medicines: [{ name: 'Napa', form: 'Tablet', dose: '1+1+1', days: 5 }, { name: 'Losectil', form: 'Capsule', dose: '1+0+0', days: 0 }],
    dispensed: [{ at: day, items: [{ i: 0, qty: 9 }, { i: 1, qty: 30 }] }],
  };
  const st = N.medStatus(rx);
  assert.deepStrictEqual([st[0].need, st[0].got, st[0].left, st[0].done], [15, 9, 6, false]);
  assert.deepStrictEqual([st[1].need, st[1].got, st[1].left], [null, 30, null]);
  // Napa ৯টা = ৩ দিন → ১৩ অক্টোবর শেষ, ১১ তারিখে মনে করানো; Losectil ৩০ দিন পরে
  assert.strictEqual(N.refillDate(rx, day), Date.UTC(2026, 9, 11));
  // পুরো কোর্স কেনা হলে Napa-র জন্য নয়
  rx.dispensed.push({ at: day, items: [{ i: 0, qty: 6 }] });
  assert.strictEqual(N.refillDate(rx, day), Date.UTC(2026, 10, 7));
});
