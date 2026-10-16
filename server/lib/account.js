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
    // টোকেন না থাকলেও অ্যাপের ঘণ্টার তালিকায় যায়
    if (u.exists) await sendPush([{ uid, tokens: require('./firebase').tokensOf(u), title: R.L(u.get('lang'), en, bn), body: '', data: { type } }]);
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

    // নিষেধাজ্ঞা অ্যাকাউন্ট মুছলেও থাকে (একই ইমেইলে নতুন অ্যাকাউন্ট খুললে আবার লাগে)।
    // বন্ধ করা অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংকের নিষেধাজ্ঞা বন্ধের সময়ই রাখা হয়েছে; এখানে বাসায় সেবার ব্লক।
    try {
      const prov = await db.doc(`providers/${uid}`).get();
      await require('./sanctions').keepForEmail(user.email, { homeCare: prov.exists && prov.get('homeVisitBlocked') === true });
    } catch (e) {
      console.error('keep sanctions failed', e);
    }

    // নিজের দেওয়া বাসায় সেবার রেটিং হিসাব থেকে বাদ (মুছে নতুন অ্যাকাউন্টে আবার রেটিং দিয়ে গড় বদলানো যায় না)
    await Promise.all(callsMade.docs.filter((d) => Number.isInteger(d.get('rating')) && d.get('rating') > 0).map((d) =>
      db.doc(`providers/${d.get('providerUid')}`).update({
        ratingSum: FieldValue.increment(-d.get('rating')),
        ratingCount: FieldValue.increment(-1),
      }).catch(() => {})));

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

    // জরুরি আহ্বানে নিজের সাড়া (সাম্প্রতিকগুলোতে)
    const recentEmergencies = await db.collection('emergencies').orderBy('createdAt', 'desc').limit(200).get();
    await Promise.all(recentEmergencies.docs.map((d) => d.ref.collection('donors').doc(uid).delete().catch(() => {})));

    // ৮) প্রোফাইল (ছবি, ফোন সহ), ব্যাজ, চেম্বার, সার্ভারের হিসাব, লগইন কোড
    const email = E.normalizeEmail(user.email || '');
    await Promise.all([
      userRef.delete(),
      db.doc(`badges/${uid}`).delete(),
      // কাগজের ছবি (লাইসেন্স, সনদ) — অ্যাকাউন্টের সাথে মুছে যায়
      require('./verify').deleteForOwner(uid).catch(() => {}),
      // সিভি, নিয়োগকর্তার তথ্য, আবেদন; চালু চাকরির বিজ্ঞাপন বন্ধ
      require('./jobs').deleteForUser(uid).catch((e) => console.error('job data delete failed', e.message)), // FEATURE:jobs
      require('./cv').deleteForUser(uid).catch((e) => console.error('cv delete failed', e.message)),
      db.doc(`providers/${uid}`).delete(),
      // দোকান আর তার ফোনের হিসাব
      db.doc(`pharmacies/${uid}`).delete(),
      db.doc(`pharmacyStats/${uid}`).delete(),
      // বাসায় সেবার ফোনের হিসাব
      db.doc(`homeStats/${uid}`).delete(),
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
