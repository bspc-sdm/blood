// অ্যাকাউন্ট মোছা (Google Play-র নিয়মে অ্যাপের ভেতর থেকেই করা যেতে হয়)।
// মোছার অনুরোধের সাথে সাথে নয়: ৩০ দিন অপেক্ষা (পারভেজ, ১৭ অক্টোবর ২০২৬)। এর মধ্যে আবার লগইন করলে মোছা বাতিল,
// নইলে দৈনিক ক্রন মোছে। অপেক্ষার সময় অ্যাকাউন্ট লুকানো: তালিকা বন্ধ, নোটিফিকেশন নেই, সব ফোন থেকে লগআউট।
// প্রোফাইল, ছবি, ফোন, ব্যাজ, চেম্বার, রক্তের রিকোয়েস্ট, সাড়া, প্রশ্ন, উত্তর, টিপস — সব মোছে।
// চলমান অ্যাপয়েন্টমেন্ট আগে বাতিল করে অন্যজনকে জানায়, তারপর সব অ্যাপয়েন্টমেন্টের রেকর্ড মোছে।
// লগইন অ্যাকাউন্ট সবশেষে মোছা হয়, তাই মাঝপথে থেমে গেলে আবার চেষ্টা করলে বাকিটা হয়ে যায়।
const { admin, db, FieldValue, sendPush } = require('./firebase');
const R = require('./rules');
const E = require('./email-code');

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

async function deleteDocs(docs) {
  for (let i = 0; i < docs.length; i += 400) {
    const batch = db.batch();
    docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
}

async function notify(uid, en, bn, type = 'appointment') {
  try {
    const u = await db.doc(`users/${uid}`).get();
    // টোকেন না থাকলেও অ্যাপের ঘণ্টার তালিকায় যায়
    if (u.exists) await sendPush([{ uid, tokens: require('./firebase').tokensOf(u), title: R.L(u.get('lang'), en, bn), body: '', data: { type } }]);
  } catch (e) {
    console.error('delete-account push failed', e);
  }
}

/** এখনই সব মোছা (৩০ দিন পর দৈনিক ক্রন থেকে) */
/** ডাক্তারের রোগীর তালিকা (rxPatients) পাতায় পাতায় শেষ পর্যন্ত মোছা */
async function deleteDoctorPatients(uid) {
  for (;;) {
    const snap = await db.collection('rxPatients').where('doctorUid', '==', uid).limit(400).get();
    if (snap.empty) return;
    const b = db.batch();
    snap.docs.forEach((d) => b.delete(d.ref));
    await b.commit();
    if (snap.size < 400) return;
  }
}

async function deleteNow(uid, email) {
  const userRef = db.doc(`users/${uid}`);
  const stateRef = db.doc(`serverState/${uid}`);
  const [u, state] = await Promise.all([userRef.get(), stateRef.get()]);

  // কোন কোন রিকোয়েস্টে সাড়া দিয়েছেন: প্রোফাইল (অ্যাপ বদলাতে পারে) আর সার্ভারের নিজের তালিকা — দুটো মিলিয়ে
  const responded = Object.keys({
    ...(u.get('respondedTo') || {}), ...(u.get('commitments') || {}), ...((state.exists && state.get('respondedTo')) || {}),
  }).filter((x) => ID_RE.test(x));
  const answerPaths = ((state.exists && state.get('answerPaths')) || []).filter(
    (p) => typeof p === 'string' && /^questions\/[A-Za-z0-9_-]+\/answers\/[A-Za-z0-9_-]+$/.test(p),
  );

  const [myRequests, myQuestions, myTips, asPatient, asProvider, corrections, reports, visitsMine, visitsNursed, myAmbulances, medMine, medShop, myInbox, myTickets, myFinds, callsMade, callsGot, myBanks] = await Promise.all([
    db.collection('requests').where('requesterUid', '==', uid).get(),
    db.collection('questions').where('uid', '==', uid).get(),
    db.collection('tips').where('uid', '==', uid).get(),
    db.collection('appointments').where('patientUid', '==', uid).get(),
    db.collection('appointments').where('providerUid', '==', uid).get(),
    db.collection('correctionRequests').where('uid', '==', uid).get(),
    db.collection('modReports').where('reporterUid', '==', uid).get(),
    db.collection('homeVisits').where('patientUid', '==', uid).get(),
    db.collection('homeVisits').where('nurseUid', '==', uid).get(),
    db.collection('ambulances').where('ownerUid', '==', uid).get(),
    db.collection('medOrders').where('patientUid', '==', uid).get(),
    db.collection('medOrders').where('chosen', '==', uid).get(),
    db.collection('inbox').where('uid', '==', uid).limit(1000).get(),
    db.collection('tickets').where('uid', '==', uid).limit(500).get(),
    db.collection('medFinds').where('uid', '==', uid).limit(500).get(),
    db.collection('homeCalls').where('uid', '==', uid).limit(1000).get(),
    db.collection('homeCalls').where('providerUid', '==', uid).limit(1000).get(),
    db.collection('bloodBanks').where('ownerUid', '==', uid).limit(10).get(),
  ]);
  // অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংকে ফোনের রেকর্ড (কাকে, কবে, রেটিং, মন্তব্য), পুরোনো রিপোর্ট, হাসপাতাল যোগের অনুরোধ
  const [ambCalls, pharmCalls, bankCalls, oldReports, hospReqs] = await Promise.all([
    db.collection('ambulanceCalls').where('uid', '==', uid).limit(1000).get(),
    db.collection('pharmacyCalls').where('uid', '==', uid).limit(1000).get(),
    db.collection('bloodBankCalls').where('uid', '==', uid).limit(1000).get(),
    db.collection('reports').where('reporterUid', '==', uid).limit(500).get(),
    db.collection('hospitalRequests').where('requesterUids', 'array-contains', uid).limit(200).get(),
  ]);

  // নিষেধাজ্ঞা অ্যাকাউন্ট মুছলেও থাকে (একই ইমেইলে নতুন অ্যাকাউন্ট খুললে আবার লাগে)।
  // বন্ধ করা অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংকের নিষেধাজ্ঞা বন্ধের সময়ই রাখা হয়েছে; এখানে বাসায় সেবার ব্লক।
  try {
    const prov = await db.doc(`providers/${uid}`).get();
    await require('./sanctions').keepForEmail(email, { homeCare: prov.exists && prov.get('homeVisitBlocked') === true });
  } catch (e) {
    console.error('keep sanctions failed', e);
  }

  // নিজের দেওয়া রেটিং হিসাব থেকে বাদ (মুছে নতুন অ্যাকাউন্টে আবার রেটিং দিয়ে গড় বদলানো যায় না),
  // মালিকের "সাম্প্রতিক মন্তব্য" থেকেও
  const rated = (d) => Number.isInteger(d.get('rating')) && d.get('rating') > 0;
  const S = require('./sellerstats');
  await Promise.all([
    ...callsMade.docs.filter(rated).map((d) => Promise.all([
      db.doc(`providers/${d.get('providerUid')}`).update({ ratingSum: FieldValue.increment(-d.get('rating')), ratingCount: FieldValue.increment(-1) }).catch(() => {}),
      S.removeRecentRating('homeStats', d.get('providerUid'), uid),
    ])),
    ...ambCalls.docs.filter(rated).map((d) => Promise.all([
      db.doc(`ambulances/${d.get('ambulanceId')}`).update({ ratingSum: FieldValue.increment(-d.get('rating')), ratingCount: FieldValue.increment(-1) }).catch(() => {}),
      S.removeRecentRating('ambulanceStats', d.get('ambulanceId'), uid),
    ])),
    // ফার্মেসির রেটিং আলাদা সংগ্রহে (ফোন ছাড়া কেনার পরেও দেওয়া যায়) — গড় ঠিক করে রেকর্ডসহ মোছা
    require('./pharmacy').removeRatingsBy(uid).catch((e) => console.error('pharmacy ratings cleanup failed', e.message)),
  ]);

  // চালু রিকোয়েস্টে যাঁরা রাজি ছিলেন — মোছার আগে তালিকা রেখে পরে জানানো (যাতে কেউ শুধু শুধু হাসপাতালে না যান)
  const RE = require('./requestend');
  const endedNotices = await Promise.all(
    myRequests.docs
      .filter((d) => d.get('status') === 'open')
      .map(async (d) => {
        const w = await d.ref.collection('responses').where('status', '==', 'accepted').limit(50).get();
        return { id: d.id, r: d.data(), ids: w.docs.map((x) => x.id) };
      }),
  );

  await Promise.all([
    // ১) নিজের রক্তের রিকোয়েস্ট (হাসপাতাল, নোট, এলাকা, ফোন সহ) পুরোপুরি
    ...myRequests.docs.map((d) => db.recursiveDelete(d.ref)),
    // ২) অন্যের রিকোয়েস্টে নিজের সাড়া (নাম ও ফোন থাকে) — রাজি থাকলে হিসাব কমে, রক্তপ্রার্থী জানেন
    ...responded.map((rid) => RE.donorLeft(rid, uid).catch((e) => console.error('donorLeft failed', e))),
    // ৩) নিজের প্রশ্ন (উত্তর সহ)
    ...myQuestions.docs.map((d) => db.recursiveDelete(d.ref)),
    // ৪) টিপস, সংশোধনের অনুরোধ, রিপোর্ট
    deleteDocs([...myTips.docs, ...corrections.docs, ...reports.docs]),
    // ৫) অন্যের প্রশ্নে নিজের উত্তর
    ...answerPaths.map(async (p) => {
      const ref = db.doc(p);
      const s = await ref.get();
      if (!s.exists || s.get('uid') !== uid) return;
      await ref.delete();
      await ref.parent.parent.update({ answerCount: FieldValue.increment(-1) }).catch(() => {});
    }),
    // ৬) অ্যাপয়েন্টমেন্ট: চলমান হলে অন্যজনকে জানিয়ে, তারপর রেকর্ড পুরোপুরি মোছা (সিরিয়ালের গোনা আলাদা থাকে)
    ...asPatient.docs.map(async (d) => {
      const active = ['pending', 'confirmed'].includes(d.get('status'));
      if (active) await notify(d.get('providerUid'), 'Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে');
      await d.ref.delete();
    }),
    ...asProvider.docs.map(async (d) => {
      const active = ['pending', 'confirmed'].includes(d.get('status'));
      if (active) await notify(d.get('patientUid'), 'Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে');
      await d.ref.delete();
    }),
    // ফোনের রেকর্ড আর পুরোনো রিপোর্ট
    deleteDocs([...ambCalls.docs, ...pharmCalls.docs, ...bankCalls.docs, ...oldReports.docs]),
    // হাসপাতাল যোগের অনুরোধে নিজের নাম (অনুরোধটা অন্যদেরও হতে পারে, তাই শুধু নিজেকে সরানো)
    ...hospReqs.docs.map((d) => d.ref.update({ requesterUids: FieldValue.arrayRemove(uid) }).catch(() => {})),
    // ৭) হোম ভিজিট: নিজের অনুরোধের ঠিকানা/নাম মুছে, চলমান হলে বাতিল
    ...visitsMine.docs.map(async (d) => {
      const active = ['open', 'accepted', 'on_the_way'].includes(d.get('status'));
      await d.ref.update({
        patientName: '', address: '', note: '', placeName: '', comment: '', lat: null, lng: null, openFor: [],
        ...(active ? { status: 'cancelled', cancelledBy: 'patient' } : {}),
      });
      if (active && d.get('nurseUid')) await notify(d.get('nurseUid'), 'Home visit cancelled', 'হোম ভিজিট বাতিল হয়েছে', 'homevisit');
    }),
    ...visitsNursed.docs.map(async (d) => {
      const active = ['accepted', 'on_the_way'].includes(d.get('status'));
      await d.ref.update({ nurseName: '', ...(active ? { status: 'cancelled', cancelledBy: 'nurse' } : {}) });
      if (active) await notify(d.get('patientUid'), 'Home visit cancelled', 'হোম ভিজিট বাতিল হয়েছে', 'homevisit');
    }),
    // অ্যাম্বুলেন্স, নোটিফিকেশনের তালিকা
    deleteDocs([...myAmbulances.docs, ...myInbox.docs, ...myTickets.docs, ...myFinds.docs, ...callsMade.docs, ...callsGot.docs]),
    ...myAmbulances.docs.map((d) => db.doc(`ambulanceStats/${d.id}`).delete()),
    // নিজের যোগ করা ব্লাড ব্যাংক (বন্ধ করা হলেও) আর তার হিসাব
    deleteDocs(myBanks.docs),
    ...myBanks.docs.map((d) => db.doc(`bloodBankStats/${d.id}`).delete()),
    // ওষুধের অর্ডার: নিজের ঠিকানা, নাম, ছবি মুছে, চলমান হলে বাতিল
    ...medMine.docs.map(async (d) => {
      const active = ['open', 'confirmed', 'ready', 'on_the_way'].includes(d.get('status'));
      await d.ref.update({ patientName: '', address: '', items: '', lat: null, lng: null, comment: '', remindAt: null, remindDays: 0, openFor: [], ...(active ? { status: 'cancelled' } : {}) });
      await db.doc(`medOrderPhotos/${d.id}`).delete().catch(() => {});
      if (active && d.get('chosen')) await notify(d.get('chosen'), 'Order cancelled', 'অর্ডার বাতিল হয়েছে', 'pharmacy_order');
    }),
    ...medShop.docs.map(async (d) => {
      const active = ['confirmed', 'ready', 'on_the_way'].includes(d.get('status'));
      if (active) {
        await d.ref.update({ status: 'cancelled' });
        await notify(d.get('patientUid'), 'Order cancelled', 'অর্ডার বাতিল হয়েছে', 'pharmacy_mine');
      }
    }),
  ]);

  for (const n of endedNotices) if (n.ids.length) await RE.notifyEnded(n.id, n.r, 'removed', n.ids);

  // অ্যাম্বুলেন্স ডাকুন: চালু অনুরোধ বাতিল (অন্যজনকে জানিয়ে), রোগী হিসেবে নিজের সব অনুরোধ মোছা
  await require('./ambreq').closeForUser(uid, { full: true }).catch((e) => console.error('amb req cleanup failed', e));

  // জরুরি আহ্বানে নিজের সাড়া (সাম্প্রতিকগুলোতে)
  const recentEmergencies = await db.collection('emergencies').orderBy('createdAt', 'desc').limit(200).get();
  await Promise.all(recentEmergencies.docs.map((d) => d.ref.collection('donors').doc(uid).delete().catch(() => {})));

  // ৭খ) প্রেসক্রিপশন: রোগীর অ্যাকাউন্ট থেকে খুলে দেওয়া (ডাক্তারের লেখা রেকর্ড থাকে, কারো অ্যাকাউন্টে নয়);
  // ফার্মেসি/প্যাথলজির অনুরোধ (বাসা থেকে নমুনার ঠিকানা-ফোনসহ), রিপোর্ট দেখার অনুরোধ মোছা; নিজের প্যাথলজি, দামের তালিকা আর স্টক মোছা
  try {
    const [rxs, rxPats, rxOrd, labOrd, revs] = await Promise.all([
      db.collection('prescriptions').where('patientUid', '==', uid).limit(500).get(),
      db.collection('rxPatients').where('linkedUid', '==', uid).limit(200).get(),
      db.collection('rxOrders').where('patientUid', '==', uid).limit(500).get(),
      db.collection('labOrders').where('patientUid', '==', uid).limit(500).get(),
      db.collection('rxReviews').where('patientUid', '==', uid).limit(500).get(),
    ]);
    await Promise.all([
      ...rxs.docs.map((d) => d.ref.update({ patientUid: null })),
      ...rxPats.docs.map((d) => d.ref.update({ linkedUid: null })),
      ...rxOrd.docs.map((d) => d.ref.delete()),
      ...labOrd.docs.map((d) => d.ref.delete()),
      ...revs.docs.map((d) => d.ref.delete()),
      db.doc(`labs/${uid}`).delete(),
      // প্যাথলজির পরীক্ষা ও দামের তালিকা
      db.doc(`labPrices/${uid}`).delete(),
      // নিজের প্যাথলজিতে আসা সব অর্ডারে রোগীর বাসার ঠিকানা-ফোন (অর্ডার রোগীর কাছে থাকে, প্যাথলজি আর নেই) —
      // আগে চালুগুলো, তারপর বাকি সব, পাতায় পাতায় শেষ পর্যন্ত (labprices.js stripHomes)
      require('./labprices').stripHomes(uid).catch((e) => console.error('lab home strip failed', e.message)),
      // নিজের দোকানে আসা অনলাইন অর্ডারে রোগীর ডেলিভারির ঠিকানা-ফোন (দোকান আর নেই) — পাতায় পাতায় শেষ পর্যন্ত
      require('./rxnet').stripOrderContacts(uid).catch((e) => console.error('rx order contact strip failed', e.message)),
      db.doc(`pharmacyStock/${uid}`).delete(),
      db.doc(`pharmacyMissed/${uid}`).delete(),
      // দোকানের পণ্য আর হিসাব-খাতা (কেনা, বিক্রি, দিনের যোগফল)
      require('./shopbooks').deleteFor(uid),
      require('./careaccess').deleteFor(uid),
      // ডাক্তারের আজকের/আগের সিরিয়ালের সারি (queues)
      require('./queue').deleteFor(uid),
      // ডাক্তারের নিজে লেখা রোগীর তালিকা (নাম, বয়স, ফোন) — পারভেজ "ওকে", ১৮ অক্টোবর; লেখা প্রেসক্রিপশন রোগীর কাগজ, তাই থাকে
      deleteDoctorPatients(uid).catch((e) => console.error('rx patients delete failed', e.message)),
    ]);
  } catch (e) {
    console.error('rx cleanup failed', e);
  }

  // ৮) প্রোফাইল (ছবি, ফোন সহ), ব্যাজ, চেম্বার, সার্ভারের হিসাব, লগইন কোড
  const normEmail = E.normalizeEmail(email || '');
  await Promise.all([
    userRef.delete(),
    db.doc(`badges/${uid}`).delete(),
    // কাগজের ছবি (লাইসেন্স, সনদ) — অ্যাকাউন্টের সাথে মুছে যায়
    require('./verify').deleteForOwner(uid).catch(() => {}),
    db.doc(`providers/${uid}`).delete(),
    // দোকান আর তার ফোনের হিসাব
    db.doc(`pharmacies/${uid}`).delete(),
    db.doc(`pharmacyStats/${uid}`).delete(),
    // নিজের দোকানে অন্যদের দেওয়া রেটিং (দোকানই নেই)
    require('./pharmacy').deleteRatingsOf(uid).catch(() => {}),
    // বাসায় সেবার ফোনের হিসাব
    db.doc(`homeStats/${uid}`).delete(),
    stateRef.delete(),
    normEmail ? db.doc(`emailCodes/${E.docIdFor(normEmail)}`).delete() : Promise.resolve(),
  ]);

  // ৯) লগইন অ্যাকাউন্ট (আগের চেষ্টায় মুছে গিয়ে থাকলে সমস্যা নেই)
  try {
    await admin.auth().deleteUser(uid);
  } catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
  }
  return { ok: true };
}

const DAY = 24 * 60 * 60 * 1000;
const GRACE_MS = 30 * DAY;

async function ownListings(uid) {
  const [amb, banks, shop, lab] = await Promise.all([
    db.collection('ambulances').where('ownerUid', '==', uid).get(),
    db.collection('bloodBanks').where('ownerUid', '==', uid).get(),
    db.doc(`pharmacies/${uid}`).get(),
    db.doc(`labs/${uid}`).get(),
  ]);
  return [...amb.docs, ...banks.docs, ...[shop, lab].filter((d) => d.exists)];
}

/** মোছার অনুরোধ: ৩০ দিন পরের তারিখ, অ্যাকাউন্ট লুকানো, সব ফোন থেকে লগআউট */
async function schedule(uid) {
  const now = Date.now();
  const stateRef = db.doc(`serverState/${uid}`);
  const state = await stateRef.get();
  const deleteAt = (state.exists && state.get('deleteAt')) || now + GRACE_MS;
  await stateRef.set({ deleteAt, deleteRequestedAt: now }, { merge: true });
  for (const d of await ownListings(uid)) {
    const st = d.get('status');
    if (st === 'suspended' || d.get('hiddenForDeletion')) continue;
    await d.ref.update({ hiddenForDeletion: st || 'pending', status: 'suspended' });
  }
  // অ্যাম্বুলেন্স ডাকুন: চালু অনুরোধ বাতিল (রোগী বা চালক — অন্যজনকে জানিয়ে)
  await require('./ambreq').closeForUser(uid).catch((e) => console.error('amb req close for deletion failed', e));
  // ডাক্তার/নার্স: তালিকা আর বাসায় সেবা বন্ধ, সামনের অ্যাপয়েন্টমেন্ট বাতিল করে রোগীদের জানানো
  await require('./apptcancel').suspendProvider(uid, 'deletion').catch((e) => console.error('suspend for deletion failed', e));
  await db.doc(`users/${uid}`).set({ fcmToken: FieldValue.delete(), fcmTokens: FieldValue.delete() }, { merge: true }).catch(() => {});
  await admin.auth().revokeRefreshTokens(uid).catch(() => {});
  return { ok: true, deleteAt };
}

/** ৩০ দিনের মধ্যে আবার লগইন: মোছা বাতিল, লুকানো সব আগের মতো। বাতিল হলে true */
async function cancel(uid) {
  const stateRef = db.doc(`serverState/${uid}`);
  const state = await stateRef.get();
  if (!state.exists || !state.get('deleteAt')) return false;
  await stateRef.update({ deleteAt: FieldValue.delete(), deleteRequestedAt: FieldValue.delete() });
  for (const d of await ownListings(uid)) {
    const prev = d.get('hiddenForDeletion');
    if (!prev) continue;
    // মাঝে অ্যাডমিন অবস্থা বদলে থাকলে সেটাই থাকে, শুধু চিহ্ন সরে
    if (d.get('status') === 'suspended') await d.ref.update({ status: prev, hiddenForDeletion: FieldValue.delete() });
    else await d.ref.update({ hiddenForDeletion: FieldValue.delete() });
  }
  const p = await db.doc(`providers/${uid}`).get();
  if (p.exists && p.get('suspendReason') === 'deletion') await require('./apptcancel').liftProvider(uid);
  return true;
}

/** দৈনিক ক্রন: যাঁদের ৩০ দিন পেরিয়েছে তাঁদের অ্যাকাউন্ট মোছা (একবারে কয়েকটা, বাকি পরের দিন) */
async function runDue(now = Date.now(), max = 5) {
  const due = await db.collection('serverState').where('deleteAt', '<=', now).limit(max).get();
  let n = 0;
  for (const d of due.docs) {
    try {
      const u = await admin.auth().getUser(d.id).catch((e) => (e.code === 'auth/user-not-found' ? null : Promise.reject(e)));
      await deleteNow(d.id, (u && u.email) || '');
      n += 1;
    } catch (e) {
      console.error('scheduled delete failed', d.id, e);
    }
  }
  return n;
}

// কাজ (অ্যাপ থেকে): শুধু এটাই — বাকিগুলো সার্ভারের ভেতরের, অ্যাপ ডাকতে পারে না
module.exports = {
  async delete_account(req, user) {
    return schedule(user.uid);
  },
};
// actions-এ যেন না যায় (Object.assign শুধু enumerable নেয়)
Object.defineProperty(module.exports, 'deletion', { value: { schedule, cancel, runDue, deleteNow, GRACE_MS }, enumerable: false });
