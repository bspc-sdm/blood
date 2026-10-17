// ডেমো — ভাগ: health (আলোচনা, টিপস, ব্যাজ, রিপোর্ট, কেয়ারগিভার, নোটিফিকেশন, আপিল, তারিখ সংশোধন, অ্যাকাউন্ট মোছা)
// ডেমো মুছলে এই ফাইলও মুছবেন।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
// অন্য ভাগগুলোর (blood, services, rx) নিজেদের টেস্ট আছে — এখানে শুধু health ভাগ, তাই সেগুলো খালি রাখা।
// সব ভাগ একসাথে চালাতে: DEMO_ALL=1 node --test test/demo-health.test.js
if (!process.env.DEMO_ALL) {
  for (const p of ['blood', 'services', 'rx']) {
    const file = require.resolve(path.join(SERVER, `lib/demo/${p}.js`));
    require.cache[file] = { id: file, filename: file, loaded: true, exports: { async seed() {} } };
  }
}
const api = require(path.join(SERVER, 'api/community.js'));
const verify = require(path.join(SERVER, 'api/auth/verify-code.js'));
const demo = require(path.join(SERVER, 'lib/demo'));
const { db } = F.firebase;

// নকল ডাটাবেসে নেই এমন একটা জিনিস (আসল Firestore-এ আছে): প্রশ্ন মুছলে তার উত্তরসহ মোছা
if (!db.recursiveDelete) {
  db.recursiveDelete = async (ref) => {
    for (const k of [...F.store.keys()]) if (k === ref.path || k.startsWith(`${ref.path}/`)) F.store.delete(k);
  };
}

// …আর ref.parent.parent (উত্তর মুছলে প্রশ্নের উত্তর-সংখ্যা কমানো, account.js deleteNow)
{
  const rawDoc = db.doc;
  const withParent = (ref) => {
    const parts = ref.path.split('/');
    Object.defineProperty(ref, 'parent', {
      configurable: true,
      get: () => ({ id: parts[parts.length - 2], path: parts.slice(0, -1).join('/'), parent: parts.length > 2 ? withParent(rawDoc(parts.slice(0, -2).join('/'))) : null }),
    });
    return ref;
  };
  db.doc = (p) => withParent(rawDoc(p));
}

const OWNER = 'owner-demo-test';
F.users.set(OWNER, { uid: OWNER, email: 'ekapkbd@gmail.com' });
const U = (k) => demo.uidOf(k);
const X = (k) => demo.uidOf(`x-${k}`);

async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': '10.0.0.9' };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
async function post(handler, body) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await handler({ method: 'POST', headers: {}, body }, res);
  return { status: res.statusCode, body: out };
}
const has = (items, id) => items.some((x) => x.id === id);

test('health ডেমো: দেখা, পরীক্ষকের কাজ, আর মোছার পর কিছু থাকে না', async (t) => {
  await db.doc('users/realuser0002').set({ name: 'আসল ব্যবহারকারী' });
  await db.doc('questions/realq1').set({ uid: 'realuser0002', authorName: 'আসল', text: 'আসল প্রশ্ন', createdAt: Date.now(), answerCount: 0 });
  await ok(OWNER, 'admin_demo_seed');

  // ---- অতিথি (লগইন ছাড়া): প্রশ্ন আর টিপস দেখা যায়, প্রশ্নকারীর নাম নয় ----
  const gq = await ok(null, 'questions');
  assert.ok(has(gq.items, 'demo-q-diabetes') && has(gq.items, 'demo-q-ad'));
  assert.ok(gq.items.every((x) => x.authorName === null), 'অতিথি নাম দেখেন না');
  const gt = await ok(null, 'tips');
  const dengue = gt.items.find((x) => x.id === 'demo-tip-dengue');
  assert.strictEqual(dengue.badge, 'doctor');
  assert.strictEqual(dengue.authorName, 'ডা. ডেমো হাসান');
  assert.ok(has(gt.items, 'demo-tip-insulin') && gt.items.find((x) => x.id === 'demo-tip-insulin').badge === 'nurse');

  // ---- রোগী: নিজের প্রশ্ন, উত্তর আর ব্যাজ ----
  const pq = await ok(U('patient'), 'questions');
  const sleep = pq.items.find((x) => x.id === 'demo-q-sleep');
  assert.ok(sleep.mine && sleep.authorName === null && sleep.answerCount === 1, 'নাম লুকানো নিজের প্রশ্ন');
  assert.strictEqual(pq.items.find((x) => x.id === 'demo-q-diabetes').authorName, 'ডেমো রোগী রহিম');
  const qd = await ok(U('patient'), 'question', { id: 'demo-q-diabetes' });
  assert.deepStrictEqual(qd.answers.map((a) => a.badge), ['doctor', 'expert']);
  assert.strictEqual(qd.question.answerCount, 2);
  assert.strictEqual((await ok(U('patient'), 'question', { id: 'demo-q-knee' })).answers.length, 0);

  const meDoc = await ok(U('doctor'), 'me');
  assert.strictEqual(meDoc.badge.status, 'approved');
  assert.strictEqual(meDoc.badge.type, 'doctor');
  assert.strictEqual((await ok(U('homecare'), 'me')).badge.type, 'nurse');
  assert.strictEqual((await ok(U('patient'), 'me')).badge, null);

  // ---- পরীক্ষকের কাজ: রোগী প্রশ্ন করেন, উত্তর দিতে পারেন না; ডাক্তার/নার্স উত্তর ও টিপস দেন ----
  const asked = await ok(U('patient'), 'ask', { text: 'ডেমো পরীক্ষা: গ্যাস্ট্রিকের ওষুধ কতদিন খাওয়া যায়?', anonymous: false });
  assert.strictEqual((await call(U('patient'), 'answer', { id: 'demo-q-knee', text: 'উত্তর' })).status, 403);
  await ok(U('doctor'), 'answer', { id: 'demo-q-knee', text: 'ডেমো উত্তর: প্রতিদিন হালকা হাঁটা আর চেয়ারে বসে পা সোজা করার ব্যায়াম করুন।' });
  await ok(U('doctor'), 'answer', { id: asked.id, text: 'ডেমো উত্তর: ডাক্তারের পরামর্শ ছাড়া ২ সপ্তাহের বেশি নয়।' });
  await ok(U('homecare'), 'answer', { id: 'demo-q-bedsore', text: 'ডেমো উত্তর: প্রতি ২ ঘণ্টায় পাশ ফেরান, বিছানা শুকনো রাখুন।' });
  assert.strictEqual((await ok(U('patient'), 'question', { id: 'demo-q-knee' })).question.answerCount, 1);
  const tip = await ok(U('doctor'), 'post_tip', { title: 'ডেমো টিপস: হাত ধোয়া', body: 'খাবারের আগে আর টয়লেটের পরে সাবান দিয়ে ২০ সেকেন্ড হাত ধুয়ে নিন।' });
  assert.strictEqual((await call(U('patient'), 'post_tip', { title: 'x', body: 'y' })).status, 403);
  // বেনামি প্রশ্নে অ্যাডমিনও নাম দেখেন না — রিপোর্ট করলে "বেনামি"
  await ok(U('caregiver'), 'report', { kind: 'answer', id: 'demo-q-fever', answerId: 'demo-a-fever-exp' });
  await ok(U('caregiver'), 'report', { kind: 'question', id: 'demo-q-sleep' });

  // ---- মালিক: রিপোর্ট ----
  const reps = (await ok(OWNER, 'admin_reports')).items;
  const ad = reps.find((x) => x.targetPath === 'questions/demo-q-ad');
  assert.strictEqual(ad.count, 2);
  assert.strictEqual(ad.authorName, 'ডেমো হারবাল সেন্টার');
  assert.strictEqual(reps.find((x) => x.targetPath === 'questions/demo-q-fever/answers/demo-a-fever-exp').count, 2);
  assert.strictEqual(reps.find((x) => x.targetPath === 'questions/demo-q-sleep').authorName, 'বেনামি');
  await ok(OWNER, 'admin_report_resolve', { targetPath: 'questions/demo-q-ad', kind: 'question', remove: true });
  assert.ok(!(await db.doc('questions/demo-q-ad').get()).exists, 'বিজ্ঞাপন মুছে গেছে');
  await ok(OWNER, 'admin_report_resolve', { targetPath: 'questions/demo-q-fever/answers/demo-a-fever-exp', kind: 'answer', remove: false });
  assert.ok((await db.doc('questions/demo-q-fever/answers/demo-a-fever-exp').get()).exists, 'রেখে দেওয়া উত্তর থাকে');

  // ---- মালিক: ব্যাজ (পুষ্টিবিদ অপেক্ষায়, ট্রেইনার বাতিল) ----
  const pend = (await ok(OWNER, 'admin_badges', { status: 'pending', panel: true })).items;
  const nb = pend.find((x) => x.uid === X('h-nutri'));
  assert.strictEqual(nb.type, 'nutritionist');
  assert.ok(nb.verify.docs.find((d) => d.type === 'cert').has, 'সনদের ছবি আছে');
  const vd = await ok(OWNER, 'admin_verify_docs', { kind: 'badge', id: X('h-nutri') });
  assert.ok(vd.photos.find((p) => p.type === 'cert').data.startsWith('/9j/'));
  assert.strictEqual((await call(OWNER, 'admin_badge_resolve', { uid: X('h-nutri'), approve: true })).status, 400, 'টিক ছাড়া অনুমোদন নয়');
  await ok(OWNER, 'admin_badge_resolve', { uid: X('h-nutri'), approve: true, checks: ['cert_name', 'cert_relevant', 'workplace'] });
  assert.strictEqual((await db.doc(`badges/${X('h-nutri')}`).get()).get('status'), 'approved');
  assert.ok((await ok(OWNER, 'admin_badges', { status: 'rejected', panel: true })).items.some((x) => x.uid === X('h-trainer')));
  // ডোনার নিজে ব্যাজের আবেদন করেন (ডাক্তার — ছবি ঐচ্ছিক), মালিক কারণসহ বাতিল করেন
  await ok(U('donor'), 'badge_apply', { type: 'doctor', regNo: 'ডেমো-A-777', workplace: 'ডেমো হাসপাতাল' });
  await ok(OWNER, 'admin_badge_resolve', { uid: U('donor'), approve: false, reason: 'BMDC-তে নম্বর পাওয়া যায়নি' });
  const dme = await ok(U('donor'), 'me');
  assert.strictEqual(dme.badge.status, 'rejected');
  assert.strictEqual(dme.badge.statusReason, 'BMDC-তে নম্বর পাওয়া যায়নি');

  // ---- সাহায্য ও আপিল ----
  const mine = (await ok(U('patient'), 'tickets_mine')).items;
  for (const id of ['demo-t-open', 'demo-t-answered', 'demo-t-closed']) assert.ok(has(mine, id), id);
  const openT = (await ok(OWNER, 'admin_tickets', { status: 'open' })).items;
  assert.ok(has(openT, 'demo-t-open') && has(openT, 'demo-t-appeal'));
  await ok(OWNER, 'admin_ticket_reply', { id: 'demo-t-open', text: 'ডেমো উত্তর: ডাক্তার ৪৮ ঘণ্টায় সাড়া না দিলে অনুরোধ নিজে থেকে শেষ হয়।' });
  assert.strictEqual((await db.doc('tickets/demo-t-open').get()).get('status'), 'answered');
  await ok(U('patient'), 'ticket_create', { category: 'other', text: 'ডেমো পরীক্ষা: অ্যাপে বাংলা লেখা ছোট দেখায়।' });
  assert.strictEqual((await call(U('patient'), 'ticket_create', { category: 'other', text: 'আরেকটা ডেমো সমস্যা লিখলাম' })).status, 429, 'খোলা সর্বোচ্চ ৩টা');

  // ---- নোটিফিকেশন ----
  const inb = (await ok(U('patient'), 'inbox')).items;
  assert.ok(inb.length >= 5);
  assert.strictEqual(inb.find((x) => x.data.questionId === 'demo-q-sleep').read, false, 'নতুন উত্তর না-পড়া');
  assert.strictEqual(inb.find((x) => x.data.ticketId === 'demo-t-answered').read, false);
  assert.strictEqual(inb.find((x) => x.data.type === 'care').read, true, 'পুরোনোগুলো পড়া');
  assert.ok(inb.findIndex((x) => x.data.questionId === 'demo-q-sleep') < inb.findIndex((x) => x.data.type === 'care'), 'নতুনগুলো আগে');
  assert.ok((await ok(U('caregiver'), 'inbox')).items.filter((x) => x.data.type === 'care').length >= 2);
  assert.ok((await ok(U('donor'), 'inbox')).items.some((x) => x.data.questionId === 'demo-q-donate'));
  await ok(U('patient'), 'inbox_read');
  assert.ok((await ok(U('patient'), 'inbox')).items.every((x) => x.read));

  // ---- কেয়ারগিভার ----
  const cl = await ok(U('caregiver'), 'care_list');
  assert.strictEqual(cl.caringFor.find((x) => x.patientUid === U('patient')).status, 'active');
  assert.strictEqual(cl.caringFor.find((x) => x.patientUid === X('h-mother')).status, 'invited');
  assert.strictEqual((await ok(U('patient'), 'care_list')).family.length, 2);
  await ok(U('caregiver'), 'rx_mine', { asUid: U('patient') });
  await ok(U('caregiver'), 'my_appointments', { asUid: U('patient') });
  await ok(U('caregiver'), 'family_get', { asUid: U('patient') });
  for (const a of ['ask', 'delete_account', 'family_set', 'care_invite', 'ticket_create']) {
    assert.strictEqual((await call(U('caregiver'), a, { asUid: U('patient'), text: 'x' })).status, 403, `${a} রোগীর হয়ে নয়`);
  }
  await ok(U('caregiver'), 'care_respond', { patientUid: X('h-mother'), accept: true });
  assert.strictEqual((await call(U('patient'), 'care_invite', { email: 'nobody@lifolo-demo.test' })).status, 404);
  await ok(U('patient'), 'care_invite', { email: demo.demoEmail('donor') });
  assert.strictEqual((await ok(U('donor'), 'care_list')).caringFor[0].status, 'invited');
  assert.strictEqual((await call(U('patient'), 'care_invite', { email: demo.demoEmail('lab') })).status, 409, 'সর্বোচ্চ ২ জন');
  await ok(U('patient'), 'care_remove', { caretakerUid: U('caregiver') });
  const revoked = await call(U('caregiver'), 'rx_mine', { asUid: U('patient') });
  assert.strictEqual(revoked.status, 403);
  assert.strictEqual(revoked.body.code, 'care_revoked');

  // ---- অ্যাডমিনের সারাংশ আর একজনের কাজের ইতিহাস ----
  const ov = await ok(OWNER, 'admin_overview');
  assert.ok(ov.corrections.items.some((c) => c.id === 'demo-corr-masud'));
  assert.ok(ov.tickets.items.some((x) => x.id === 'demo-t-appeal'));
  const act = await ok(OWNER, 'admin_user_section', { uid: U('patient'), section: 'community' });
  assert.ok(act.questions.some((x) => x.id === 'demo-q-diabetes'));
  assert.ok(act.reportsMade.length >= 2);
  assert.ok((await ok(OWNER, 'admin_user_section', { uid: U('patient'), section: 'support' })).items.length >= 4);

  // ---- অ্যাকাউন্ট মোছা (কেয়ারগিভার): ৩০ দিনের অপেক্ষা, তারপর এক ক্লিকের ডেমো লগইন ----
  const del = await ok(U('caregiver'), 'delete_account');
  assert.ok(del.deleteAt > Date.now() + 29 * 86400000);
  const relog = await post(verify, { demo: 'caregiver' });
  assert.strictEqual(relog.status, 200);
  const pending = (await db.doc(`serverState/${U('caregiver')}`).get()).get('deleteAt');
  assert.strictEqual(relog.body.restored, true, 'ডেমো লগইনেও মোছা বাতিল (সাধারণ লগইনের মতো)');
  assert.strictEqual(pending, undefined);

  // ---- মোছা: সব ডেমো, আর পরীক্ষার সময় বানানো সবকিছু ----
  await ok(OWNER, 'admin_demo_wipe');
  const left = [...F.store.entries()].filter(([, v]) => v && v.demo === true).map(([k]) => k);
  assert.deepStrictEqual(left, []);
  // ডেমো uid-এর নামে কিছু থাকা চলবে না (অ্যাডমিনের কাজের ইতিহাস বাদে — সেটা ইচ্ছে করে রাখা হয়)
  const mentions = [...F.store.entries()]
    .filter(([k]) => !k.startsWith('adminLog/'))
    .filter(([k, v]) => /demo-/.test(k) || /"demo-(?!x-h-|q-|a-|tip-|t-|corr-)/.test(JSON.stringify(v)) || /"demo-x-/.test(JSON.stringify(v)))
    .map(([k]) => k);
  assert.deepStrictEqual(mentions, []);
  for (const c of ['questions', 'tips', 'modReports', 'tickets', 'careLinks', 'inbox', 'badges', 'verifyDocs', 'correctionRequests']) {
    const rest = [...F.store.keys()].filter((k) => k.startsWith(`${c}/`) && !k.startsWith('questions/realq1'));
    assert.deepStrictEqual(rest, [], c);
  }
  assert.ok((await db.doc('questions/realq1').get()).exists, 'আসল প্রশ্ন থাকে');
  assert.ok((await db.doc('users/realuser0002').get()).exists);
  const logs = [...F.store.keys()].filter((k) => k.startsWith('adminLog/'));
  if (logs.length) t.diagnostic(`মোছার পরও অ্যাডমিনের কাজের ইতিহাসে ${logs.length}টা লেখা থাকে (ডেমো জিনিসের পথসহ)`);
});
