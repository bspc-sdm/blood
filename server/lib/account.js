// অ্যাকাউন্ট মোছা (Google Play-র নিয়মে অ্যাপের ভেতর থেকেই করা যেতে হয়)।
// প্রোফাইল, ছবি, ফোন, ব্যাজ, চেম্বার, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, সাড়া — সব মোছে; খোলা রিকোয়েস্ট বন্ধ করে।
const { admin, db } = require('./firebase');
const E = require('./email-code');

async function deleteQuery(q) {
  const snap = await q.get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = db.batch();
    snap.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  return snap.docs;
}

module.exports = {
  async delete_account(req, user) {
    const uid = user.uid;
    const userRef = db.doc(`users/${uid}`);
    const stateRef = db.doc(`serverState/${uid}`);
    const [u, state] = await Promise.all([userRef.get(), stateRef.get()]);

    // ১) নিজের রক্তের রিকোয়েস্ট: বন্ধ, ফোনের গোপন ডকুমেন্ট মোছা
    const mine = await db.collection('requests').where('requesterUid', '==', uid).get();
    for (const d of mine.docs) {
      await d.ref.update({ status: 'closed', requesterName: '' });
      await db.recursiveDelete(d.ref.collection('private'));
    }

    // ২) অন্যের রিকোয়েস্টে নিজের সাড়া (নাম ও ফোন থাকে)
    const responded = Object.keys({ ...(u.get('respondedTo') || {}), ...(u.get('commitments') || {}) });
    for (const rid of responded) {
      if (/^[A-Za-z0-9_-]{1,64}$/.test(rid)) await db.doc(`requests/${rid}/responses/${uid}`).delete();
    }

    // ৩) নিজের উত্তর
    const answerPaths = (state.exists && state.get('answerPaths')) || [];
    for (const p of answerPaths) {
      const ref = db.doc(p);
      const s = await ref.get();
      if (s.exists && s.get('uid') === uid) {
        await ref.delete();
        await ref.parent.parent.update({ answerCount: admin.firestore.FieldValue.increment(-1) }).catch(() => {});
      }
    }

    // ৪) নিজের প্রশ্ন (উত্তর সহ), টিপস, অ্যাপয়েন্টমেন্ট, সংশোধনের অনুরোধ, রিপোর্ট
    const qs = await db.collection('questions').where('uid', '==', uid).get();
    for (const d of qs.docs) await db.recursiveDelete(d.ref);
    await deleteQuery(db.collection('tips').where('uid', '==', uid));
    await deleteQuery(db.collection('appointments').where('patientUid', '==', uid));
    await deleteQuery(db.collection('appointments').where('providerUid', '==', uid));
    await deleteQuery(db.collection('correctionRequests').where('uid', '==', uid));
    await deleteQuery(db.collection('reports').where('reporterUid', '==', uid));

    // ৫) প্রোফাইল, ব্যাজ, চেম্বার, সার্ভারের হিসাব, লগইন কোড
    const email = E.normalizeEmail(user.email || '');
    await Promise.all([
      userRef.delete(),
      db.doc(`badges/${uid}`).delete(),
      db.doc(`providers/${uid}`).delete(),
      stateRef.delete(),
      email ? db.doc(`emailCodes/${E.docIdFor(email)}`).delete() : Promise.resolve(),
    ]);

    // ৬) লগইন অ্যাকাউন্ট
    await admin.auth().deleteUser(uid);
    return { ok: true };
  },
};
