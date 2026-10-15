// ডাক্তার/নার্সের দিকে কিছু বদলালে (চেম্বার মোছা, বার বদল, তালিকা বন্ধ, ব্যাজ বাতিল, অ্যাকাউন্ট বন্ধ)
// সামনের যেসব অ্যাপয়েন্টমেন্ট আর টেকে না সেগুলো বাতিল করে রোগীকে জানানো — নইলে রোগী গিয়ে দেখবেন কেউ নেই।
const { db, sendPush } = require('./firebase');
const R = require('./rules');
const K = require('./care');

function dateLabel(millis) {
  const d = new Date(millis);
  return `${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`;
}

/** চেম্বারের তালিকায় এই অ্যাপয়েন্টমেন্টের চেম্বার এখনো আছে আর সেদিন খোলা কি না */
function stillValid(a, chambers) {
  const key = a.chamberKey || a.chamber || '';
  const ch = chambers.find((c) => (c.hospitalId || c.hospitalName) === key || c.hospitalName === a.chamber);
  return !!(ch && ch.appointments && K.chamberOpenOn(ch, a.date));
}

/**
 * keep(a) false হলে বাতিল। reason: [en, bn] — রোগী আর ডাক্তার দুজনেই কার্ডে দেখেন।
 * ফেরত: কয়টা বাতিল হলো
 */
async function cancelStale(providerUid, keep, reason) {
  const today = R.todayDhakaUtcMidnight();
  // চালু (অপেক্ষায়/নিশ্চিত) গুলোই — দুটো সমান-সমান খোঁজ, আলাদা ইনডেক্স লাগে না, পুরোনো বাতিলগুলো সীমা খায় না
  const snaps = await Promise.all(['pending', 'confirmed'].map((st) =>
    db.collection('appointments').where('providerUid', '==', providerUid).where('status', '==', st).limit(500).get()));
  const stale = snaps.flatMap((s) => s.docs).filter((d) => (d.get('date') || 0) >= today && !keep(d.data()));
  if (!stale.length) return 0;
  for (let i = 0; i < stale.length; i += 400) {
    const batch = db.batch();
    stale.slice(i, i + 400).forEach((d) => batch.update(d.ref, {
      status: 'cancelled', cancelledBy: 'system', providerNote: reason[1], updatedAt: Date.now(),
    }));
    await batch.commit();
  }
  try {
    const patients = [...new Set(stale.map((d) => d.get('patientUid')))];
    const users = await db.getAll(...patients.map((id) => db.doc(`users/${id}`)));
    const byUid = new Map(users.filter((u) => u.exists).map((u) => [u.id, u]));
    await sendPush(stale.filter((d) => byUid.has(d.get('patientUid'))).map((d) => {
      const u = byUid.get(d.get('patientUid'));
      const lang = u.get('lang');
      return {
        uid: u.id,
        token: u.get('fcmToken'),
        title: R.L(lang, 'Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'),
        body: `${d.get('providerName') || ''} · ${dateLabel(d.get('date'))} · ${R.L(lang, reason[0], reason[1])}`,
        data: { type: 'appointment' },
      };
    }));
  } catch (e) {
    console.error('stale appointment push failed', e);
  }
  return stale.length;
}

/**
 * ডাক্তার/নার্সের পুরো তালিকা বন্ধ (অ্যাকাউন্ট বন্ধ বা রিপোর্ট টিকলে): চেম্বার আর বাসায় সেবা দুটোই,
 * সামনের অ্যাপয়েন্টমেন্ট বাতিল করে রোগীদের জানানো। আবার চালু: liftProvider।
 */
async function suspendProvider(uid, reason) {
  const pRef = db.doc(`providers/${uid}`);
  const p = await pRef.get();
  if (!p.exists || p.get('adminSuspended') === true) return false;
  await pRef.update({
    adminSuspended: true,
    suspendReason: reason || 'admin',
    listedBeforeSuspend: p.get('listed') !== false,
    listed: false,
    active: false,
    'homeVisit.enabled': false,
  });
  await cancelStale(uid, () => false, ['The doctor/nurse is not available now', 'ডাক্তার/নার্স এখন পাওয়া যাচ্ছে না']);
  return true;
}

async function liftProvider(uid) {
  const { FieldValue } = require('./firebase');
  const pRef = db.doc(`providers/${uid}`);
  const p = await pRef.get();
  if (!p.exists || p.get('adminSuspended') !== true) return false;
  const listed = p.get('listedBeforeSuspend') !== false;
  await pRef.update({
    adminSuspended: FieldValue.delete(),
    suspendReason: FieldValue.delete(),
    listedBeforeSuspend: FieldValue.delete(),
    listed,
    active: listed && p.get('appointments') === true,
  });
  return true;
}

module.exports = { cancelStale, stillValid, suspendProvider, liftProvider };
