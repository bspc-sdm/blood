// শেষ রক্তদানের তারিখ বদলানো — অ্যাডমিনের অনুমোদন ছাড়াই, কিন্তু:
// পেছানো (বা "কখনো দিইনি") আর দুই রক্তদানের মাঝে ১২০ দিনের কম হলে ব্যবহারকারীকে জেনে-বুঝে নিশ্চিত করতে হয়;
// প্রতিটা বদল সার্ভারে লেখা থাকে (অ্যাডমিন দেখতে পান), দিনে সর্বোচ্চ ৩ বার।
const { db, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const { useQuota } = require('./community-db');

const MIN_MILLIS = Date.UTC(1990, 0, 1);
const LOG_MAX = 20;

const actions = {
  async donation_date_set(req, user) {
    const b = req.body || {};
    const after = b.millis === null ? null : Number.isInteger(b.millis) ? b.millis : undefined;
    if (after === undefined || (after != null && (after < MIN_MILLIS || after > R.todayDhakaUtcMidnight() + R.DAY_MS))) {
      throw new HttpError(400, C.tr(req, 'Choose a valid date (not in the future)', 'সঠিক তারিখ দিন (ভবিষ্যতের নয়)'));
    }
    const ref = db.doc(`users/${user.uid}`);
    const snap = await ref.get();
    if (!snap.exists) throw new HttpError(404, C.tr(req, 'Profile not found', 'প্রোফাইল পাওয়া যায়নি'));
    const before = snap.get('lastDonationMillis') ?? null;
    if (before === after) return { ok: true, unchanged: true };
    const risk = R.donationRisk(before, after);
    if (risk && b.confirmed !== true) {
      throw new HttpError(409, C.tr(req, 'Please confirm that you understand the risk', 'ঝুঁকি জেনে নিশ্চিত করুন'), 'confirm_needed');
    }
    await useQuota(req, user.uid, 'dateChange');
    const stateRef = db.doc(`serverState/${user.uid}`);
    await db.runTransaction(async (t) => {
      const st = await t.get(stateRef);
      const log = ((st.exists && st.get('donationDateLog')) || []).slice(-(LOG_MAX - 1));
      t.update(ref, { lastDonationMillis: after });
      t.set(stateRef, { donationDateLog: [...log, { from: before, to: after, at: Date.now(), risk: risk || '' }] }, { merge: true });
    });
    return { ok: true, risk };
  },
};

module.exports = { actions };
