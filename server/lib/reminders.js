// প্রতিদিন সকালের (বাংলাদেশ সময় ৯টা) মনে করিয়ে দেওয়া: আজকের অ্যাপয়েন্টমেন্ট আর উত্তর না দেওয়া অনুরোধ।
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
  if (!u) return null; // টোকেন না থাকলেও অ্যাপের ঘণ্টার তালিকায় যায়
  const lang = u.get('lang');
  return { uid: u.id, tokens: require('./firebase').tokensOf(u), title: R.L(lang, en, bn), body: R.L(lang, bodyEn, bodyBn), data };
}

async function dailyReminders(now = Date.now()) {
  const today = R.todayDhakaUtcMidnight(now);
  const [confirmed, pending, allConfirmed] = await Promise.all([
    db.collection('appointments').where('date', '==', today).where('status', '==', 'confirmed').limit(1000).get(),
    db.collection('appointments').where('status', '==', 'pending').limit(1000).get(),
    db.collection('appointments').where('status', '==', 'confirmed').limit(2000).get(),
  ]);
  // উত্তর না পাওয়া অনুরোধ: তারিখ পার হলে, বা ৪৮ ঘণ্টায় ডাক্তার সাড়া না দিলে শেষ — রোগী জেনে অন্য ডাক্তার দেখতে পারেন
  const stale = pending.docs.filter((d) => (d.get('date') || 0) < today || now - (d.get('createdAt') || now) > 48 * 60 * 60 * 1000);
  for (let i = 0; i < stale.length; i += 400) {
    const batch = db.batch();
    stale.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'expired', updatedAt: now }));
    await batch.commit();
  }
  // তারিখের ৩ দিন পর নিশ্চিত অ্যাপয়েন্টমেন্ট "সম্পন্ন" — তখন দুই পক্ষের ফোন নম্বর আর দেখায় না
  const done = allConfirmed.docs.filter((d) => (d.get('date') || 0) < today - 3 * R.DAY_MS);
  for (let i = 0; i < done.length; i += 400) {
    const batch = db.batch();
    done.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'completed', updatedAt: now }));
    await batch.commit();
  }
  const staleIds = new Set(stale.map((d) => d.id));
  const waiting = pending.docs.filter((d) => !staleIds.has(d.id) && now - (d.get('createdAt') || now) > 12 * 60 * 60 * 1000);
  const u = await users([
    ...confirmed.docs.flatMap((d) => [d.get('patientUid'), d.get('providerUid')]),
    ...waiting.map((d) => d.get('providerUid')),
    ...stale.map((d) => d.get('patientUid')),
  ]);
  const out = [];

  // রোগী: ডাক্তার সাড়া দেননি
  for (const d of stale) {
    out.push(msg(u.get(d.get('patientUid')), 'No reply to your appointment request', 'অ্যাপয়েন্টমেন্টের অনুরোধে সাড়া আসেনি',
      `${d.get('providerName') || ''} didn't reply. You can request another doctor.`,
      `${d.get('providerName') || ''} সাড়া দেননি। অন্য ডাক্তারের কাছে অনুরোধ করতে পারেন।`, { type: 'appointment' }));
  }

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
  const list = out.filter(Boolean);
  const sent = list.length ? await sendPush(list) : 0;
  return { appointmentsToday: confirmed.size, waiting: waiting.length, sent };
}

module.exports = { dailyReminders };
