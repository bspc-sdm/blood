// ডাক্তার অ্যাকাউন্ট মুছলে নিজে লেখা রোগীর তালিকা মোছে, লেখা প্রেসক্রিপশন থাকে
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const account = require(path.join(SERVER, 'lib/account.js'));
const { db } = F.firebase;

test('ডাক্তার মুছলে: rxPatients মোছে (৪০০-র বেশিও), প্রেসক্রিপশন থাকে, অন্য ডাক্তারের রোগী থাকে', async () => {
  const D = 'docdel00001';
  F.users.set(D, { uid: D, email: 'd@example.com' });
  await db.doc(`users/${D}`).set({ name: 'ডা. পরীক্ষা' });
  for (let i = 0; i < 450; i++) await db.doc(`rxPatients/p${i}`).set({ doctorUid: D, name: `রোগী ${i}`, phone: '01711000000' });
  await db.doc('rxPatients/other1').set({ doctorUid: 'otherdoc1', name: 'অন্য' });
  await db.doc('prescriptions/rx1').set({ doctorUid: D, patientUid: 'pat000001', code: 'AB23CD45EF', status: 'active' });
  await account.deletion.deleteNow(D, 'd@example.com');
  await new Promise((r) => setTimeout(r, 20));
  const left = [...F.store.keys()].filter((k) => k.startsWith('rxPatients/'));
  assert.deepStrictEqual(left, ['rxPatients/other1']);
  assert.ok((await db.doc('prescriptions/rx1').get()).exists, 'প্রেসক্রিপশন থাকে');
});
