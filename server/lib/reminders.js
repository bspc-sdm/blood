// প্রতিদিন সকালের (বাংলাদেশ সময় ৯টা) মনে করিয়ে দেওয়া: আজকের অ্যাপয়েন্টমেন্ট, উত্তর না দেওয়া অনুরোধ, আজকের হোম ভিজিট।
// Vercel-এর ফ্রি প্ল্যানে ক্রন দিনে একবারই চলে, তাই "কয়েক ঘণ্টা আগে" নয়, সকালেই সারাদিনের জন্য।
const { db, sendPush } = require('./firebase');
const R = require('./rules');

async function users(uids) {
  const ids = [...new Set(uids.filter(Boolean))];
  if (!ids.length) return new Map();
  const snaps = await db.getAll(...ids.map((id) => db.doc(`users/${id}`)));
  return new Map(snaps.filter((s) => s.exists).map((s) => [s.id, s]));
}

function msg(u, en, bn, bodyEn, bodyBn, data) {
  if (!u || !u.get('fcmToken')) return null;
  const lang = u.get('lang');
  return { uid: u.id, token: u.get('fcmToken'), title: R.L(lang, en, bn), body: R.L(lang, bodyEn, bodyBn), data };
}

async function dailyReminders(now = Date.now()) {
  const today = R.todayDhakaUtcMidnight(now);
  const [confirmed, pending, visits] = await Promise.all([
    db.collection('appointments').where('date', '==', today).where('status', '==', 'confirmed').limit(1000).get(),
    db.collection('appointments').where('status', '==', 'pending').limit(1000).get(),
    Promise.all(['accepted', 'on_the_way'].map((s) => db.collection('homeVisits').where('status', '==', s).limit(500).get())),
  ]);
  // তারিখ পেরিয়ে যাওয়া অপেক্ষার অনুরোধ বন্ধ, যাতে প্রতিদিন পড়তে না হয়
  const stale = pending.docs.filter((d) => (d.get('date') || 0) < today);
  for (let i = 0; i < stale.length; i += 400) {
    const batch = db.batch();
    stale.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'expired', updatedAt: now }));
    await batch.commit();
  }
  const waiting = pending.docs.filter((d) => (d.get('date') || 0) >= today && now - (d.get('createdAt') || now) > 12 * 60 * 60 * 1000);
  // আজকের হোম ভিজিট (সময় দেওয়া থাকলে আজকের দিনে)
  const todayVisits = visits.flatMap((s) => s.docs).filter((d) => {
    const w = d.get('when') || 0;
    return w >= today - 6 * 3600000 && w < today + 18 * 3600000;
  });

  const u = await users([
    ...confirmed.docs.flatMap((d) => [d.get('patientUid'), d.get('providerUid')]),
    ...waiting.map((d) => d.get('providerUid')),
    ...todayVisits.flatMap((d) => [d.get('patientUid'), d.get('nurseUid')]),
  ]);
  const out = [];

  // রোগী: আজকের অ্যাপয়েন্টমেন্ট
  for (const d of confirmed.docs) {
    const serial = d.get('serial') ? ` · #${d.get('serial')}` : '';
    out.push(msg(u.get(d.get('patientUid')), 'Appointment today', 'আজ অ্যাপয়েন্টমেন্ট আছে',
      `${d.get('providerName') || ''} · ${d.get('chamber') || ''}${serial}`,
      `${d.get('providerName') || ''} · ${d.get('chamberBn') || d.get('chamber') || ''}${serial}`, { type: 'appointment' }));
  }
  // ডাক্তার/নার্স: আজ কতজন, আর কতগুলো অনুরোধ অপেক্ষায়
  const countBy = (docs) => docs.reduce((m, d) => m.set(d.get('providerUid'), (m.get(d.get('providerUid')) || 0) + 1), new Map());
  for (const [uid, n] of countBy(confirmed.docs)) {
    out.push(msg(u.get(uid), 'Your appointments today', 'আজকের অ্যাপয়েন্টমেন্ট', `${n} confirmed`, `${R.bnDigits(String(n))}টি নিশ্চিত`, { type: 'appointment' }));
  }
  for (const [uid, n] of countBy(waiting)) {
    out.push(msg(u.get(uid), 'Appointment requests waiting', 'অ্যাপয়েন্টমেন্টের অনুরোধ অপেক্ষায়',
      `${n} patient(s) are waiting for your reply`, `${R.bnDigits(String(n))} জন রোগী উত্তরের অপেক্ষায়`, { type: 'appointment' }));
  }
  // হোম ভিজিট: আজ
  for (const d of todayVisits) {
    const t = d.get('when') ? R.formatTimeEn(d.get('when')) : '';
    const tb = d.get('when') ? R.formatTimeBn(d.get('when')) : '';
    out.push(msg(u.get(d.get('patientUid')), 'Home nurse visit today', 'আজ হোম নার্স আসবেন', `${d.get('nurseName') || ''} ${t}`.trim(), `${d.get('nurseName') || ''} ${tb}`.trim(), { type: 'homevisit', visitId: d.id }));
    out.push(msg(u.get(d.get('nurseUid')), 'Home visit today', 'আজ হোম ভিজিট আছে', `${d.get('patientName') || ''} ${t}`.trim(), `${d.get('patientName') || ''} ${tb}`.trim(), { type: 'homevisit', visitId: d.id }));
  }
  const list = out.filter(Boolean);
  const sent = list.length ? await sendPush(list) : 0;
  return { appointmentsToday: confirmed.size, waiting: waiting.length, visitsToday: todayVisits.length, sent };
}

module.exports = { dailyReminders };
