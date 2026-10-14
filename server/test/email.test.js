const test = require('node:test');
const assert = require('node:assert');
const E = require('../lib/email-code');

test('ইমেইল যাচাই ও ছোট হাতের অক্ষরে রূপান্তর', () => {
  assert.strictEqual(E.normalizeEmail('  Parvez@Gmail.com '), 'parvez@gmail.com');
  assert.strictEqual(E.normalizeEmail('not-an-email'), null);
  assert.strictEqual(E.normalizeEmail('a@b'), null);
  assert.strictEqual(E.normalizeEmail(''), null);
});

test('কোড ৬ সংখ্যার, হ্যাশ মেলে শুধু সঠিক কোডে', () => {
  for (let i = 0; i < 50; i++) assert.match(E.newCode(), /^\d{6}$/);
  const h = E.hashCode('a@b.com', '123456');
  assert.ok(E.codesMatch(h, E.hashCode('a@b.com', '123456')));
  assert.ok(!E.codesMatch(h, E.hashCode('a@b.com', '123457')));
  assert.ok(!E.codesMatch(h, E.hashCode('x@b.com', '123456')));
});
