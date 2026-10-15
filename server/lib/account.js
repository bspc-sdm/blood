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

async function notify(uid, en, bn) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    const token = u.get('fcmToken');
    if (token) await sendPush([{ uid, token, title: R.L(u.get('lang'), en, bn), body: '', data: { type: 'appointment' } }]);
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

    const [myRequests, myQuestions, myTips, asPatient, asProvider, corrections, reports, visitsMine, visitsNursed] = await Promise.all([
      db.collection('requests').where('requesterUid', '==', uid).get(),
      db.collection('questions').where('uid', '==', uid).get(),
      db.collection('tips').where('uid', '==', uid).get(),
      db.collection('appointments').where('patientUid', '==', uid).get(),
      db.collection('appointments').where('providerUid', '==', uid).get(),
      db.collection('correctionRequests').where('uid', '==', uid).get(),
      db.collection('modReports').where('reporterUid', '==', uid).get(),
      db.collection('homeVisits').where('patientUid', '==', uid).get(),
      db.collection('homeVisits').where('nurseUid', '==', uid).get(),
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
        await d.ref.update({ patientName: '', address: '', note: '', placeName: '', ...(active ? { status: 'cancelled', cancelledBy: 'patient' } : {}) });
        if (active && d.get('nurseUid')) await notify(d.get('nurseUid'), 'Home visit cancelled', 'হোম ভিজিট বাতিল হয়েছে');
      }),
      ...visitsNursed.docs.map(async (d) => {
        const active = ['accepted', 'on_the_way'].includes(d.get('status'));
        await d.ref.update({ nurseName: '', ...(active ? { status: 'cancelled', cancelledBy: 'nurse' } : {}) });
        if (active) await notify(d.get('patientUid'), 'Home visit cancelled', 'হোম ভিজিট বাতিল হয়েছে');
      }),
    ]);

    // ৮) প্রোফাইল (ছবি, ফোন সহ), ব্যাজ, চেম্বার, সার্ভারের হিসাব, লগইন কোড
    const email = E.normalizeEmail(user.email || '');
    await Promise.all([
      userRef.delete(),
      db.doc(`badges/${uid}`).delete(),
      db.doc(`providers/${uid}`).delete(),
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
