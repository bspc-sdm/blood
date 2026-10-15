// অ্যাকাউন্ট মোছা (Google Play-র নিয়মে অ্যাপের ভেতর থেকেই করা যেতে হয়)।
// প্রোফাইল, ছবি, ফোন, ব্যাজ, চেম্বার, রক্তের রিকোয়েস্ট, সাড়া, প্রশ্ন, উত্তর, টিপস — সব মোছে।
// অন্যজনের সাথে চলমান অ্যাপয়েন্টমেন্ট মোছে না; বাতিল করে, নিজের নাম/তথ্য সরিয়ে দেয় আর অন্যজনকে জানায়।
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
    const token = u.get('fcmToken');
    if (token) await sendPush([{ uid, token, title: R.L(u.get('lang'), en, bn), body: '', data: { type } }]);
  } catch (e) {
    console.error('delete-account push failed', e);
  }
}

module.exports = {
  async delete_account(req, user) {
    const uid = user.uid;
    const userRef = db.doc(`users/${uid}`);
    const stateRef = db.doc(`serverState/${uid}`);
    const [u, state] = await Promise.all([userRef.get(), stateRef.get()]);

    const responded = Object.keys({ ...(u.get('respondedTo') || {}), ...(u.get('commitments') || {}) }).filter((x) => ID_RE.test(x));
    const answerPaths = ((state.exists && state.get('answerPaths')) || []).filter(
      (p) => typeof p === 'string' && /^questions\/[A-Za-z0-9_-]+\/answers\/[A-Za-z0-9_-]+$/.test(p),
    );

    const [myRequests, myQuestions, myTips, asPatient, asProvider, corrections, reports, visitsMine, visitsNursed, myAmbulances, medMine, medShop, myInbox, myTickets] = await Promise.all([
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
    ]);

    await Promise.all([
      // ১) নিজের রক্তের রিকোয়েস্ট (হাসপাতাল, নোট, এলাকা, ফোন সহ) পুরোপুরি
      ...myRequests.docs.map((d) => db.recursiveDelete(d.ref)),
      // ২) অন্যের রিকোয়েস্টে নিজের সাড়া (নাম ও ফোন থাকে)
      ...responded.map((rid) => db.doc(`requests/${rid}/responses/${uid}`).delete()),
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
      // ৬) অ্যাপয়েন্টমেন্ট: অন্যজনের রেকর্ড থাকে, কিন্তু চলমান হলে বাতিল আর নিজের তথ্য মুছে
      ...asPatient.docs.map(async (d) => {
        const active = ['pending', 'confirmed'].includes(d.get('status'));
        await d.ref.update({ patientName: '', note: '', ...(active ? { status: 'cancelled' } : {}) });
        if (active) await notify(d.get('providerUid'), 'Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে');
      }),
      ...asProvider.docs.map(async (d) => {
        const active = ['pending', 'confirmed'].includes(d.get('status'));
        await d.ref.update({ providerName: '', chamber: '', schedule: '', providerNote: '', ...(active ? { status: 'cancelled' } : {}) });
        if (active) await notify(d.get('patientUid'), 'Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে');
      }),
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
      deleteDocs([...myAmbulances.docs, ...myInbox.docs, ...myTickets.docs]),
      ...myAmbulances.docs.map((d) => db.doc(`ambulanceStats/${d.id}`).delete()),
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

    // ৮) প্রোফাইল (ছবি, ফোন সহ), ব্যাজ, চেম্বার, সার্ভারের হিসাব, লগইন কোড
    const email = E.normalizeEmail(user.email || '');
    await Promise.all([
      userRef.delete(),
      db.doc(`badges/${uid}`).delete(),
      db.doc(`providers/${uid}`).delete(),
      // স্টক তালিকা আর জেলার সূচি থেকেও (নইলে খোঁজায় দোকানের id থেকে যেত), আর দোকানের হিসাব
      (async () => {
        const shop = await db.doc(`pharmacies/${uid}`).get();
        await require('./stock').removeShop(uid, shop.exists ? shop.get('district') : null).catch((e) => console.error('stock remove failed', e.message));
        await Promise.all([db.doc(`pharmacies/${uid}`).delete(), db.doc(`pharmacyStats/${uid}`).delete()]);
        const reqs = await db.collection('drugRequests').where('pharmacyId', '==', uid).limit(500).get();
        await Promise.all(reqs.docs.filter((d) => d.get('status') !== 'approved').map((d) => d.ref.delete()));
      })(),
      stateRef.delete(),
      email ? db.doc(`emailCodes/${E.docIdFor(email)}`).delete() : Promise.resolve(),
    ]);

    // ৯) লগইন অ্যাকাউন্ট (আগের চেষ্টায় মুছে গিয়ে থাকলে সমস্যা নেই)
    try {
      await admin.auth().deleteUser(uid);
    } catch (e) {
      if (e.code !== 'auth/user-not-found') throw e;
    }
    return { ok: true };
  },
};
