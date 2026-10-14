const test = require('node:test');
const assert = require('node:assert');
process.env.FIREBASE_SERVICE_ACCOUNT = JSON.stringify({ private_key: 'test-secret-key' });
const E = require('../lib/email-code');

test('ইমেইল যাচাই ও ছোট হাতের অক্ষরে রূপান্তর', () => {
  assert.strictEqual(E.normalizeEmail('  Parvez@Gmail.com '), 'parvez@gmail.com');
  assert.strictEqual(E.normalizeEmail('not-an-email'), null);
  assert.strictEqual(E.normalizeEmail('a@b'), null);
  assert.strictEqual(E.normalizeEmail(''), null);
  assert.strictEqual(E.normalizeEmail('x<a@evil.com>.co'), null);
  assert.strictEqual(E.normalizeEmail('রক্ত@gmail.com'), null);
  assert.strictEqual(E.normalizeEmail('a+tag@sub.example.co.uk'), 'a+tag@sub.example.co.uk');
});

test('কোড ৬ সংখ্যার, হ্যাশ মেলে শুধু সঠিক কোডে', () => {
  for (let i = 0; i < 50; i++) assert.match(E.newCode(), /^\d{6}$/);
  const h = E.hashCode('a@b.com', '123456');
  assert.ok(E.codesMatch(h, E.hashCode('a@b.com', '123456')));
  assert.ok(!E.codesMatch(h, E.hashCode('a@b.com', '123457')));
  assert.ok(!E.codesMatch(h, E.hashCode('x@b.com', '123456')));
});

test('IPv6 ঠিকানা প্রথম ৪ অংশে গোনা হয়', () => {
  assert.strictEqual(E.ipKey('2001:db8:85a3:1:aaaa:bbbb:cccc:dddd'), '2001:db8:85a3:1');
  assert.strictEqual(E.ipKey('103.4.5.6'), '103.4.5.6');
});
