// "Dorkar দিয়ে কতবার রক্ত দিয়েছেন" — প্রোফাইলের রক্তদানের অংশ আর শেয়ার লিংকের ডোনার ব্যাজে।
// serverState/{uid}-এ রাখা (অ্যাপ লিখতে পারে না), দিনে একবারই গোনা — একই দিনে দুই জায়গা থেকে চিহ্ন পড়লেও।
const { db, FieldValue } = require('./firebase');
const R = require('./rules');

async function countDonation(uid, now = Date.now()) {
  if (!uid) return;
  const today = R.todayDhakaUtcMidnight(now);
  const ref = db.doc(`serverState/${uid}`);
  try {
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (s.exists && (s.get('donationCountedDay') || 0) >= today) return;
      t.set(ref, { donationCount: FieldValue.increment(1), donationCountedDay: today }, { merge: true });
    });
  } catch (e) {
    console.error('donation count failed', e.message);
  }
}

async function donationCountOf(uid) {
  const s = await db.doc(`serverState/${uid}`).get();
  return (s.exists && s.get('donationCount')) || 0;
}

module.exports = { countDonation, donationCountOf };
