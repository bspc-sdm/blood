// আগের "হোম নার্স" অনুরোধ-ব্যবস্থা বন্ধ (২.২.০)। এখন "বাসায় সেবা" — ডাক্তার/নার্সের তালিকা থেকে সরাসরি ফোন (homecare.js)।
// পুরোনো অ্যাপের জন্য: নতুন অনুরোধ নেওয়া হয় না, তালিকা খালি; চলমান অনুরোধ একবারে বন্ধ করে দুই পক্ষকে জানানো হয়।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const ACTIVE = ['open', 'accepted', 'on_the_way'];

function closed(req) {
  return new HttpError(
    410,
    C.tr(req, 'Home visits are now direct: update the app and call a doctor or nurse from "Home care".', 'বাসায় সেবা এখন সরাসরি ফোনে: অ্যাপ আপডেট করে "বাসায় সেবা" থেকে ডাক্তার বা নার্সকে ফোন করুন।'),
    'homevisit_closed',
  );
}

const actions = {
  async home_visit_request(req) {
    throw closed(req);
  },
  async home_visits() {
    return { asPatient: [], asNurse: [] };
  },
  async home_visit_update(req) {
    throw closed(req);
  },
};

async function pushUid(uid, en, bn, body) {
  if (!uid) return;
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (!u.exists || !u.get('fcmToken')) return;
    await sendPush([{ uid, token: u.get('fcmToken'), title: R.L(u.get('lang'), en, bn), body, data: { type: 'homecare' } }]);
  } catch (e) {
    console.error('homevisit close push failed', e.message);
  }
}

/** দৈনিক কাজে একবারই চলে: চলমান অনুরোধ বাতিল আর রোগী ও নার্সকে জানানো */
async function dailyHomeVisits(now = Date.now()) {
  const flag = db.doc('serverState/_homeVisitsClosed');
  if ((await flag.get()).exists) return { done: true };
  let closedCount = 0;
  const notify = [];
  for (const st of ACTIVE) {
    const snap = await db.collection('homeVisits').where('status', '==', st).limit(500).get();
    for (const d of snap.docs) {
      await d.ref.update({ status: 'cancelled', cancelledBy: 'system', cancelledAt: now, openFor: [] });
      closedCount++;
      notify.push(d.get('patientUid'));
      if (d.get('nurseUid')) notify.push(d.get('nurseUid'));
    }
  }
  for (const uid of [...new Set(notify.filter(Boolean))]) {
    await pushUid(uid, 'Home visit requests have closed', 'হোম ভিজিটের অনুরোধ বন্ধ হয়েছে', 'এখন "বাসায় সেবা" থেকে ডাক্তার বা নার্সকে সরাসরি ফোন করুন');
  }
  // আগে যারা বাসায় যাওয়া চালু করেছিলেন: তালিকায় দেখাতে ফোন নম্বর দিতে হবে (আগে নম্বর শুধু গ্রহণের পর দেখা যেত)
  const on = await db.collection('providers').where('homeVisit.enabled', '==', true).limit(1000).get();
  const noPhone = on.docs.filter((d) => !d.get('homeVisit.phone')).map((d) => d.id);
  for (const uid of noPhone) {
    await pushUid(uid, 'Add a phone number to appear in Home care', '"বাসায় সেবা" তালিকায় দেখাতে ফোন নম্বর দিন', 'অ্যাপ আপডেট করে "আমার চেম্বার ও প্রোফাইল" থেকে রোগীরা যে নম্বরে ফোন করবেন তা দিন');
  }
  await flag.set({ at: now, closed: closedCount, noPhone: noPhone.length });
  return { closed: closedCount, noPhone: noPhone.length };
}

module.exports = { actions, dailyHomeVisits };
