// দোকান আর অ্যাম্বুলেন্সের নিজের হিসাব (শুধু নিজেরটাই দেখা যায়)।
// প্রতিটা ঘটনায় একটা ছোট গণনা বাড়ে (দিন অনুযায়ী) — দেখার সময় একটা ডক পড়লেই হয়।
//
// pharmacyStats/{pharmacyId}: { d: { YYYYMMDD: { req, replied, avail, none, chosen, done, amount, noShow, cancelled } }, top: { key: n } }
// ambulanceStats/{ambulanceId}: { d: { YYYYMMDD: n }, total }
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const FIELDS = ['req', 'replied', 'avail', 'none', 'chosen', 'done', 'amount', 'noShow', 'cancelled'];
const DAY = 24 * 3600 * 1000;

/** দোকানের গণনা বাড়ানো (ব্যর্থ হলেও মূল কাজ আটকায় না) */
async function bumpShop(pharmacyId, counts, keys = [], at = Date.now()) {
  if (!pharmacyId) return;
  try {
    const inc = {};
    for (const [k, v] of Object.entries(counts)) if (FIELDS.includes(k) && v) inc[k] = FieldValue.increment(v);
    const top = {};
    keys.slice(0, 30).forEach((k) => { top[k] = FieldValue.increment(1); });
    await db.doc(`pharmacyStats/${pharmacyId}`).set({ d: { [R.dhakaDayKey(at)]: inc }, ...(keys.length ? { top } : {}), started: at }, { merge: true });
  } catch (e) {
    console.error('shop stats failed', e.message);
  }
}

async function bumpAmbulance(ambulanceId, at = Date.now()) {
  try {
    await db.doc(`ambulanceStats/${ambulanceId}`).set({ d: { [R.dhakaDayKey(at)]: FieldValue.increment(1) }, total: FieldValue.increment(1) }, { merge: true });
  } catch (e) {
    console.error('ambulance stats failed', e.message);
  }
}

/** প্রথমবার: আগের অর্ডার থেকে হিসাব বানানো (এরপর ঘটনা ধরে বাড়ে) */
async function backfillShop(pharmacyId) {
  const snap = await db.collection('medOrders').where('notified', 'array-contains', pharmacyId).limit(1000).get();
  const d = {};
  const top = {};
  const add = (at, k, v = 1) => {
    const day = R.dhakaDayKey(at || Date.now());
    d[day] = d[day] || {};
    d[day][k] = (d[day][k] || 0) + v;
  };
  for (const doc of snap.docs) {
    const x = doc.data();
    add(x.createdAt, 'req');
    const r = (x.replies || {})[pharmacyId];
    if (r) {
      add(r.at || x.createdAt, 'replied');
      add(r.at || x.createdAt, r.status === 'none' ? 'none' : 'avail');
    }
    if (x.chosen === pharmacyId) {
      add(x.confirmedAt || x.createdAt, 'chosen');
      if (x.status === 'done') {
        add(x.doneAt || x.createdAt, 'done');
        add(x.doneAt || x.createdAt, 'amount', (r ? (r.total || 0) + (r.fee || 0) : 0));
        (x.drugKeys || []).forEach((k) => { top[k] = (top[k] || 0) + 1; });
      }
      if (x.status === 'no_show') add(x.doneAt || x.createdAt, 'noShow');
      if (x.status === 'cancelled') add(x.cancelledAt || x.createdAt, 'cancelled');
    }
  }
  const data = { d, top, started: Date.now(), backfilled: snap.size };
  await db.doc(`pharmacyStats/${pharmacyId}`).set(data);
  return data;
}

function sumDays(d, days, now) {
  const out = Object.fromEntries(FIELDS.map((f) => [f, 0]));
  for (let i = 0; i < days; i++) {
    const day = d[R.dhakaDayKey(now - i * DAY)];
    if (day) FIELDS.forEach((f) => { out[f] += day[f] || 0; });
  }
  return out;
}

const actions = {
  /** দোকানের নিজের হিসাব: ৭/৩০/৯০ দিন, দৈনিক বিক্রি, বেশি বিক্রি হওয়া ওষুধ */
  async shop_stats(req, user) {
    const shop = await db.doc(`pharmacies/${user.uid}`).get();
    if (!shop.exists) throw new HttpError(404, C.tr(req, 'Open your pharmacy first', 'আগে নিজের ফার্মেসি খুলুন'));
    const ref = db.doc(`pharmacyStats/${user.uid}`);
    let s = await ref.get();
    const data = s.exists && s.get('backfilled') !== undefined ? s.data() : await backfillShop(user.uid);
    const d = data.d || {};
    const now = Date.now();
    const daily = Array.from({ length: 30 }, (_, i) => {
      const day = d[R.dhakaDayKey(now - (29 - i) * DAY)] || {};
      return { req: day.req || 0, done: day.done || 0, amount: day.amount || 0 };
    });
    const top = Object.entries(data.top || {}).sort((a, b) => b[1] - a[1]).slice(0, 15).map(([key, n]) => ({ key, n }));
    const n = shop.get('ratingCount') || 0;
    const st = await db.doc(`pharmacyStock/${user.uid}`).get();
    const items = (st.exists && st.get('items')) || {};
    return {
      periods: { 7: sumDays(d, 7, now), 30: sumDays(d, 30, now), 90: sumDays(d, 90, now) },
      daily,
      top,
      rating: n ? Math.round((shop.get('ratingSum') / n) * 10) / 10 : 0,
      ratingCount: n,
      stock: { total: Object.keys(items).length, inStock: Object.values(items).filter((v) => v.s === 'in').length },
    };
  },

  /** অ্যাম্বুলেন্স মালিকের হিসাব: অ্যাপ থেকে কতবার ফোন এসেছে, রেটিং, সাম্প্রতিক মন্তব্য, রিপোর্ট */
  async ambulance_stats(req, user) {
    const mine = await db.collection('ambulances').where('ownerUid', '==', user.uid).limit(10).get();
    const now = Date.now();
    const items = await Promise.all(mine.docs.map(async (a) => {
      const [st, calls, reports] = await Promise.all([
        db.doc(`ambulanceStats/${a.id}`).get(),
        db.collection('ambulanceCalls').where('ambulanceId', '==', a.id).limit(300).get(),
        db.collection('modReports').where('targetPath', '==', `ambulances/${a.id}`).limit(50).get(),
      ]);
      const d = (st.exists && st.get('d')) || {};
      const sum = (days) => Array.from({ length: days }, (_, i) => d[R.dhakaDayKey(now - i * DAY)] || 0).reduce((x, y) => x + y, 0);
      const n = a.get('ratingCount') || 0;
      const comments = calls.docs
        .filter((c) => c.get('rating'))
        .sort((x, y) => (y.get('ratedAt') || 0) - (x.get('ratedAt') || 0))
        .slice(0, 10)
        // কে দিয়েছেন তা দেখানো হয় না (রোগীর গোপনীয়তা)
        .map((c) => ({ rating: c.get('rating'), comment: c.get('comment') || '', at: c.get('ratedAt') || 0 }));
      return {
        id: a.id,
        name: a.get('name'),
        status: a.get('status'),
        calls: { 7: sum(7), 30: sum(30), 90: sum(90), total: (st.exists && st.get('total')) || 0 },
        // আগে থেকে যাঁরা ফোন করেছেন (হিসাব শুরুর আগের সহ) — আলাদা মানুষ
        callers: calls.size,
        daily: Array.from({ length: 30 }, (_, i) => d[R.dhakaDayKey(now - (29 - i) * DAY)] || 0),
        rating: n ? Math.round((a.get('ratingSum') / n) * 10) / 10 : 0,
        ratingCount: n,
        comments,
        reports: reports.size,
      };
    }));
    return { items };
  },
};

module.exports = { actions, bumpShop, bumpAmbulance };
