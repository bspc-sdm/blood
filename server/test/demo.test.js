// ডেমো: বানানো, লগইনের কোড, আর মোছা (ডেমো মুছলে এই ফাইলও মুছবেন)
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const { db } = F.firebase;
const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
module.exports = { call, F, db, demo };

const verify = require(path.join(SERVER, 'api/auth/verify-code.js'));
async function post(handler, body) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await handler({ method: 'POST', headers: {}, body }, res);
  return { status: res.statusCode, body: out };
}

test('ডেমো বানানো: সব অ্যাকাউন্ট, এক ক্লিকে লগইন, মালিক ছাড়া কেউ বানাতে পারে না', async () => {
  const off = await post(verify, { demoRoles: true });
  assert.deepStrictEqual(off.body, { active: false, roles: [] });
  assert.strictEqual((await post(verify, { demo: 'doctor' })).status, 404);

  const r = await call(OWNER, 'admin_demo_seed');
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  assert.strictEqual(r.body.users.length, demo.ROLES.length);
  assert.ok(!demo.ROLES.some((x) => x.key === 'admin'), 'ডেমোতে অ্যাডমিন নেই');
  for (const u of r.body.users) assert.ok(F.users.has(demo.uidOf(u.key)), u.key);

  const list = await post(verify, { demoRoles: true });
  assert.strictEqual(list.body.active, true);
  assert.strictEqual(list.body.roles.length, demo.ROLES.length);
  const login = await post(verify, { demo: 'doctor' });
  assert.strictEqual(login.status, 200);
  assert.strictEqual(login.body.token, 'ctok_demo-doctor');
  assert.strictEqual((await post(verify, { demo: 'admin' })).status, 404);

  const no = await call(demo.uidOf('patient'), 'admin_demo_seed');
  assert.strictEqual(no.status, 403);
});

test('ডেমো মোছা: সব ডেমো ডক আর অ্যাকাউন্ট চলে যায়, অন্যদের কিছু হয় না', async () => {
  await db.doc('users/realuser0001').set({ name: 'আসল' });
  await call(OWNER, 'admin_demo_seed');
  const r = await call(OWNER, 'admin_demo_wipe');
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  assert.ok(![...F.users.keys()].some((u) => demo.isDemoUid(u)));
  assert.ok((await db.doc('users/realuser0001').get()).exists);
  assert.strictEqual((await post(verify, { demo: 'doctor' })).status, 404, 'মোছার পর লগইন বন্ধ');
});

test('প্যানেলের মতো ধাপে ধাপে বানানো আর মোছা', async () => {
  let step = 0;
  const seen = [];
  while (step !== null) {
    const r = await call(OWNER, 'admin_demo_seed', { step });
    assert.strictEqual(r.status, 200, JSON.stringify(r.body));
    seen.push(step);
    // মাঝপথে লগইনের তালিকা বন্ধ থাকে
    if (r.body.next !== null) assert.strictEqual((await post(verify, { demoRoles: true })).body.active, false);
    step = r.body.next;
  }
  assert.deepStrictEqual(seen, [0, 1, 2, 3, 4]);
  assert.strictEqual((await post(verify, { demoRoles: true })).body.active, true);
  const st = await call(OWNER, 'admin_demo_status');
  assert.ok(st.body.docs > 100 && st.body.partial === false);
  let w;
  for (let i = 0; i < 5; i++) { w = await call(OWNER, 'admin_demo_wipe'); if (!w.body.more) break; }
  assert.strictEqual(w.status, 200, JSON.stringify(w.body));
  assert.deepStrictEqual([...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k), []);
});
