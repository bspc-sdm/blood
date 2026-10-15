// ওয়েব অ্যাডমিন প্যানেলের কুকি সেশন
process.env.WEB_SESSION_SECRET = 'test-secret';
const test = require('node:test');
const assert = require('node:assert');
const S = require('../lib/websession');

function fakeRes() {
  const headers = {};
  return { headers, setHeader: (k, v) => { headers[k.toLowerCase()] = v; } };
}
function reqWith(cookie, csrf = true) {
  return { headers: { cookie, ...(csrf ? { [S.CSRF_HEADER]: '1' } : {}) } };
}
function issued(now = Date.now()) {
  const res = fakeRes();
  S.start(res, 'uid1', 'a@b.com', now);
  return res.headers['set-cookie'].split(';')[0];
}

test('লগইনের কুকি httpOnly, Secure, SameSite=Strict', () => {
  const res = fakeRes();
  S.start(res, 'uid1', 'a@b.com');
  const c = res.headers['set-cookie'];
  assert.match(c, /^__Host-lf_admin=/);
  assert.match(c, /HttpOnly/);
  assert.match(c, /Secure/);
  assert.match(c, /SameSite=Strict/);
  assert.match(c, /Path=\//);
});

test('ঠিক কুকি আর হেডার থাকলে ব্যবহারকারী পাওয়া যায়', () => {
  const u = S.user(reqWith(issued()), fakeRes());
  assert.deepStrictEqual(u, { uid: 'uid1', email: 'a@b.com', email_verified: true, webSession: true });
});

test('কুকি না থাকলে null (অ্যাপের নিয়মে যায়)', () => {
  assert.strictEqual(S.user({ headers: {} }, fakeRes()), null);
});

test('CSRF হেডার ছাড়া চলে না', () => {
  assert.deepStrictEqual(S.user(reqWith(issued(), false), fakeRes()), { error: 'csrf' });
});

test('বদলানো কুকি চলে না', () => {
  const c = issued();
  const [name, value] = c.split('=');
  const [payload, sig] = value.split('.');
  const forged = Buffer.from(JSON.stringify({ u: 'other', e: 'x@y.com', i: Date.now(), s: Date.now() })).toString('base64url');
  assert.deepStrictEqual(S.user(reqWith(`${name}=${forged}.${sig}`), fakeRes()), { error: 'expired' });
  assert.deepStrictEqual(S.user(reqWith(`${name}=${payload}.${sig.slice(0, -2)}xx`), fakeRes()), { error: 'expired' });
});

test('২ ঘণ্টা কিছু না করলে মেয়াদ শেষ', () => {
  const t0 = Date.now();
  const c = issued(t0);
  assert.ok(S.user(reqWith(c), fakeRes(), t0 + 119 * 60000).uid);
  const res = fakeRes();
  assert.deepStrictEqual(S.user(reqWith(c), res, t0 + 121 * 60000), { error: 'expired' });
  assert.match(res.headers['set-cookie'], /Max-Age=0/);
});

test('ব্যবহার চললে কুকি নবায়ন হয়, কিন্তু ১২ ঘণ্টা পর আর না', () => {
  const t0 = Date.now() - 13 * 3600 * 1000;
  let cookie = issued(t0);
  let now = t0;
  // প্রতি ২০ মিনিটে একবার ব্যবহার
  while (now - t0 < 11.5 * 3600 * 1000) {
    now += 20 * 60000;
    const res = fakeRes();
    assert.ok(S.user(reqWith(cookie), res, now).uid, 'চালু থাকার কথা');
    if (res.headers['set-cookie']) cookie = res.headers['set-cookie'].split(';')[0];
  }
  now = t0 + 12 * 3600 * 1000 + 60000;
  assert.deepStrictEqual(S.user(reqWith(cookie), fakeRes(), now), { error: 'expired' });
});

test('কুকির মধ্যে অন্য নামের কুকি থাকলেও ঠিক কুকি পড়ে', () => {
  const c = issued();
  assert.ok(S.user(reqWith(`foo=bar; ${c}; x=y`), fakeRes()).uid);
});
