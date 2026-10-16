// অ্যাম্বুলেন্স, ফার্মেসি আর বাসায় সেবা দেওয়া ডাক্তার/নার্সের নিজের হিসাব (শুধু নিজেরটাই দেখা যায়):
// অ্যাপ থেকে কতবার ফোন এসেছে। প্রতিটা ফোনে দিনের গণনা একটা বাড়ে — দেখার সময় একটা ডক পড়লেই হয়।
//
// ambulanceStats/{id}, pharmacyStats/{id}, homeStats/{uid}: { d: { YYYYMMDD: n }, total, callers, recent?: [{ rating, comment, at }] }
const { db, FieldValue } = require('./firebase');
const R = require('./rules');

const DAY = 24 * 3600 * 1000;

/** একটা ফোন গোনা (newCaller: এই মানুষ প্রথমবার) — ব্যর্থ হলেও মূল কাজ আটকায় না */
async function bumpCalls(collection, id, at = Date.now(), newCaller = false) {
  try {
    await db.doc(`${collection}/${id}`).set({
      d: { [R.dhakaDayKey(at)]: FieldValue.increment(1) },
      total: FieldValue.increment(1),
      ...(newCaller ? { callers: FieldValue.increment(1) } : {}),
    }, { merge: true });
  } catch (e) {
    console.error('call stats failed', e.message);
  }
}

const bumpAmbulance = (id, at, newCaller) => bumpCalls('ambulanceStats', id, at, newCaller);

/** সাম্প্রতিক ১০টা রেটিং/মন্তব্য (কে দিয়েছেন তা ছাড়া) */
async function addRecentRating(collection, id, rating, comment, raterUid) {
  try {
    const ref = db.doc(`${collection}/${id}`);
    // একই মানুষ রেটিং বদলালে আগেরটা সরে নতুনটা বসে (দুবার দেখায় না); কে দিয়েছেন তা গোপন সংকেতে
    const who = raterUid ? require('node:crypto').createHash('sha256').update(`${collection}:${id}:${raterUid}`).digest('hex').slice(0, 16) : null;
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      const recent = ((s.exists && s.get('recent')) || []).filter((r) => !who || r.who !== who).slice(-9);
      t.set(ref, { recent: [...recent, { rating, comment, at: Date.now(), ...(who ? { who } : {}) }] }, { merge: true });
    });
  } catch (e) {
    console.error('rating stats failed', e.message);
  }
}

const addAmbulanceRating = (id, rating, comment) => addRecentRating('ambulanceStats', id, rating, comment);

/** গণনার ডক থেকে ৭/৩০/৯০ দিনের হিসাব আর ৩০ দিনের দৈনিক সংখ্যা */
function callSummary(snap, now = Date.now()) {
  const d = (snap.exists && snap.get('d')) || {};
  const sum = (days) => Array.from({ length: days }, (_, i) => d[R.dhakaDayKey(now - i * DAY)] || 0).reduce((x, y) => x + y, 0);
  return {
    calls: { 7: sum(7), 30: sum(30), 90: sum(90), total: (snap.exists && snap.get('total')) || 0 },
    callers: (snap.exists && snap.get('callers')) || 0,
    daily: Array.from({ length: 30 }, (_, i) => d[R.dhakaDayKey(now - (29 - i) * DAY)] || 0),
  };
}

const actions = {
  /** অ্যাম্বুলেন্স মালিকের হিসাব: ফোন, রেটিং, সাম্প্রতিক মন্তব্য, রিপোর্ট */
  async ambulance_stats(req, user) {
    const mine = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(10).get();
    const items = await Promise.all(mine.docs.map(async (a) => {
      const [st, reports] = await Promise.all([
        db.doc(`ambulanceStats/${a.id}`).get(),
        require('./moderation').reportCount(`ambulances/${a.id}`).then((n) => ({ data: () => ({ count: n }) })),
      ]);
      const n = a.get('ratingCount') || 0;
      return {
        id: a.id,
        name: a.get('name'),
        status: a.get('status'),
        ...callSummary(st),
        rating: n ? Math.round((a.get('ratingSum') / n) * 10) / 10 : 0,
        ratingCount: n,
        // কে দিয়েছেন তা দেখানো হয় না (রোগীর গোপনীয়তা)
        comments: ((st.exists && st.get('recent')) || []).slice().reverse().map(({ who, ...c }) => c),
        reports: reports.data().count,
      };
    }));
    return { items };
  },
};

module.exports = { actions, bumpCalls, bumpAmbulance, addAmbulanceRating, addRecentRating, callSummary };
