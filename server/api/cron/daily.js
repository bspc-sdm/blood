// প্রতিদিন একবার (বাংলাদেশ সময় সকাল ৯টার কাছাকাছি) Vercel নিজে এটা চালায়:
// ১) মেয়াদ পেরোনো রিকোয়েস্ট বন্ধ করা  ২) যারা গত ২৪ ঘণ্টায় আবার রক্ত দেওয়ার যোগ্য হয়েছেন তাদের জানানো
const { db, HttpError, handler, sendPush } = require('../../lib/firebase');
const R = require('../../lib/rules');

module.exports = handler(
  async (req) => {
    const secret = process.env.CRON_SECRET;
    if (!secret || req.headers.authorization !== `Bearer ${secret}`) {
      throw new HttpError(401, 'অনুমতি নেই');
    }
    const now = Date.now();

    // ১) মেয়াদ পেরোনো রিকোয়েস্ট
    const open = await db.collection('requests').where('status', '==', 'open').get();
    const expired = open.docs.filter((d) => (d.get('neededByMillis') || 0) + R.DAY_MS <= now);
    for (let i = 0; i < expired.length; i += 400) {
      const batch = db.batch();
      expired.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'closed' }));
      await batch.commit();
    }

    // ২) আবার যোগ্য হওয়া ডোনার: শেষ রক্তদান ঠিক ১২০ দিন আগের ২৪ ঘণ্টার মধ্যে
    const windowEnd = now - R.ELIGIBILITY_DAYS * R.DAY_MS;
    const windowStart = windowEnd - R.DAY_MS;
    const eligibleSnap = await db
      .collection('users')
      .where('lastDonationMillis', '>', windowStart)
      .where('lastDonationMillis', '<=', windowEnd)
      .get();
    const reminders = eligibleSnap.docs
      .filter((d) => d.get('fcmToken') && d.get('notify') !== false)
      .map((d) => ({
        uid: d.id,
        token: d.get('fcmToken'),
        title: R.L(d.get('lang'), 'You can donate blood again', 'আপনি আবার রক্ত দিতে পারবেন'),
        body: R.L(d.get('lang'), 'Open the app to see if anyone nearby needs blood.', 'অ্যাপ খুলে দেখুন আপনার আশেপাশে কারো রক্ত লাগবে কি না।'),
        data: { type: 'eligible' },
      }));
    const sent = await sendPush(reminders);

    // ৩) আজকের অ্যাপয়েন্টমেন্ট, অপেক্ষায় থাকা অনুরোধ আর হোম ভিজিটের কথা মনে করানো
    let care = null;
    try {
      care = await require('../../lib/reminders').dailyReminders(now);
    } catch (e) {
      console.error('care reminders failed', e);
    }

    return { ok: true, closed: expired.length, eligibleReminders: sent, care };
  },
  { methods: ['GET'] },
);
