// অ্যাকাউন্ট মোছা ৩০ দিন পরে (পারভেজ, ১৭ অক্টোবর ২০২৬): অনুরোধে লুকানো, আবার লগইনে ফেরত, ক্রনে মোছা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const account = require(path.join(SERVER, 'lib/account.js'));
const { db } = F.firebase;

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { authorization: `Bearer ${uid}` }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}

test('মোছার অনুরোধ: সাথে সাথে মোছে না, ৩০ দিনের তারিখ, ব্যবসা লুকানো', async () => {
  const U = 'owner000001';
  F.users.set(U, { uid: U, email: 'o@example.com' });
  await db.doc(`users/${U}`).set({ name: 'ডেমো মালিক', fcmToken: 'x' });
  await db.doc(`pharmacies/${U}`).set({ name: 'ডেমো ফার্মেসি', status: 'approved' });
  await db.doc('ambulances/a1').set({ ownerUid: U, name: 'ডেমো অ্যাম্বুলেন্স', status: 'pending' });
  await db.doc('ambulances/a2').set({ ownerUid: U, name: 'ডেমো ২', status: 'approved' });

  const before = Date.now();
  const r = await call(U, 'delete_account');
  assert.strictEqual(r.status, 200);
  assert.ok(r.body.deleteAt >= before + 29 * 86400000);
  assert.ok((await db.doc(`users/${U}`).get()).exists, 'প্রোফাইল এখনো আছে');
  assert.strictEqual((await db.doc(`pharmacies/${U}`).get()).get('status'), 'suspended');
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('status'), 'suspended');
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('hiddenForDeletion'), 'pending');
  assert.strictEqual((await db.doc(`users/${U}`).get()).get('fcmToken'), undefined, 'নোটিফিকেশন বন্ধ');

  // মাঝে অ্যাডমিন একটা অবস্থা বদলালে সেটাই থাকে
  await db.doc('ambulances/a2').update({ status: 'rejected' });

  // আবার লগইন: মোছা বাতিল, আগের অবস্থা
  assert.strictEqual(await account.deletion.cancel(U), true);
  assert.strictEqual((await db.doc(`serverState/${U}`).get()).get('deleteAt'), undefined);
  assert.strictEqual((await db.doc(`pharmacies/${U}`).get()).get('status'), 'approved');
  assert.strictEqual((await db.doc('ambulances/a1').get()).get('status'), 'pending');
  assert.strictEqual((await db.doc('ambulances/a2').get()).get('status'), 'rejected');
  assert.strictEqual(await account.deletion.cancel(U), false, 'অনুরোধ না থাকলে কিছু হয় না');
});

test('৩০ দিন পেরোলে দৈনিক ক্রন মোছে; সময় না হলে মোছে না', async () => {
  const A = 'gone0000001';
  const B = 'wait0000001';
  for (const u of [A, B]) {
    F.users.set(u, { uid: u, email: `${u}@example.com` });
    await db.doc(`users/${u}`).set({ name: 'ডেমো' });
    await db.doc(`pharmacies/${u}`).set({ name: 'ডেমো', status: 'approved' });
  }
  await call(A, 'delete_account');
  await call(B, 'delete_account');
  // A-র সময় পেরিয়ে গেছে
  await db.doc(`serverState/${A}`).set({ deleteAt: Date.now() - 1000 }, { merge: true });
  const n = await account.deletion.runDue(Date.now());
  assert.strictEqual(n, 1);
  assert.ok(!(await db.doc(`users/${A}`).get()).exists);
  assert.ok(!(await db.doc(`pharmacies/${A}`).get()).exists);
  assert.ok(!F.users.has(A), 'লগইন অ্যাকাউন্টও মুছেছে');
  assert.ok((await db.doc(`users/${B}`).get()).exists, 'সময় না হওয়াটা থাকে');
});

test('অ্যাপ "deletion" সাহায্যকারী কাজগুলো ডাকতে পারে না', async () => {
  const U = 'nosy0000001';
  F.users.set(U, { uid: U, email: 'n@example.com' });
  for (const a of ['deletion', 'schedule', 'cancel', 'runDue', 'deleteNow']) {
    const r = await call(U, a);
    assert.strictEqual(r.status, 400, a);
  }
});
