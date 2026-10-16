// প্রতিদিন একবার (বাংলাদেশ সময় সকাল ৯টার কাছাকাছি) Vercel নিজে এটা চালায়:
// ১) মেয়াদ পেরোনো রিকোয়েস্ট বন্ধ করা  ২) যারা গত ২৪ ঘণ্টায় আবার রক্ত দেওয়ার যোগ্য হয়েছেন তাদের জানানো
const { db, FieldValue, HttpError, handler, sendPush } = require('../../lib/firebase');
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
    const expired = open.docs.filter((d) => !R.isActive(d.data(), now));
    for (let i = 0; i < expired.length; i += 400) {
      const batch = db.batch();
      expired.slice(i, i + 400).forEach((d) => batch.update(d.ref, { status: 'closed', closedAt: now, closedBy: 'expired', geoOpen: FieldValue.delete() }));
      await batch.commit();
    }
    // সময় শেষ হওয়া রিকোয়েস্টে যাঁরা রাজি ছিলেন তাঁদের জানানো (যাতে কেউ শুধু শুধু না যান, আর রক্ত দিলে জানান)
    try {
      const { notifyEnded } = require('../../lib/requestend');
      for (const d of expired.slice(0, 200)) await notifyEnded(d.id, d.data(), 'expired');
    } catch (e) {
      console.error('expiry notify failed', e);
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
      .filter((d) => d.get('notify') !== false)
      .map((d) => ({
        uid: d.id,
        tokens: require('../../lib/firebase').tokensOf(d),
        title: R.L(d.get('lang'), 'You can donate blood again', 'আপনি আবার রক্ত দিতে পারবেন'),
        body: R.L(d.get('lang'), 'Open the app to see if anyone nearby needs blood.', 'অ্যাপ খুলে দেখুন আপনার আশেপাশে কারো রক্ত লাগবে কি না।'),
        data: { type: 'eligible' },
      }));
    const sent = await sendPush(reminders);

    // ৩) আজকের অ্যাপয়েন্টমেন্ট আর অপেক্ষায় থাকা অনুরোধের কথা মনে করানো
    let care = null;
    try {
      care = await require('../../lib/reminders').dailyReminders(now);
    } catch (e) {
      console.error('care reminders failed', e);
    }

    let med = null;
    try {
      med = await require('../../lib/pharmacy').dailyPharmacy(now);
    } catch (e) {
      console.error('pharmacy daily failed', e);
    }
    // ফেরত নেওয়া চাকরি/সিভি ফিচারের রেখে যাওয়া ডেটা মোছা (একবারের কাজ)
    let cleanup = null;
    try {
      cleanup = await require('../../lib/cleanup').cleanupRemovedFeatures();
    } catch (e) {
      console.error('cleanup failed', e);
    }
    // আগের হোম ভিজিটের অনুরোধ-ব্যবস্থা বন্ধ (একবারের কাজ)
    let homeVisits = null;
    try {
      homeVisits = await require('../../lib/homevisit').dailyHomeVisits(now);
    } catch (e) {
      console.error('home visit close failed', e);
    }
    // সময় পেরোনো জরুরি আহ্বান বন্ধ, "আসছি" বলা ডোনারদের জানানো (রক্ত দিলে অ্যাপে জানাতে বলা)
    let emergenciesClosed = 0;
    try {
      emergenciesClosed = await require('../../lib/emergency').closeExpired(now);
    } catch (e) {
      console.error('emergency close failed', e);
    }
    // নার্সের চেম্বার বন্ধ (একবারের কাজ)
    let nurseChambers = null;
    try {
      nurseChambers = await require('../../lib/cleanup').closeNurseChambers();
    } catch (e) {
      console.error('nurse chambers close failed', e);
    }
    // ওষুধ খোঁজা: বন্ধ হওয়ার ২ দিন পরের খোঁজ মোছা
    let medFindsDeleted = 0;
    try {
      medFindsDeleted = await require('../../lib/medfind').dailyCleanup(now);
    } catch (e) {
      console.error('med find cleanup failed', e);
    }
    // ফোন-কোডের দৈনিক সীমার পুরোনো দিনের নথি (আগে ফোন নম্বরই নথির নাম ছিল) মোছা
    let phoneLimitsDeleted = 0;
    try {
      const old = await db.collection('phoneLimits').where('day', '<', R.dhakaDayKey(now)).limit(400).get();
      if (!old.empty) {
        const b = db.batch();
        old.docs.forEach((d) => b.delete(d.ref));
        await b.commit();
        phoneLimitsDeleted = old.size;
      }
    } catch (e) {
      console.error('phone limits cleanup failed', e);
    }
    // কাছের খোঁজার (geohash) রক্ষণাবেক্ষণ
    let geo = null;
    try {
      geo = await require('../../lib/geo').maintain(db, FieldValue, R.isActive, now);
    } catch (e) {
      console.error('geo maintain failed', e);
    }
    // প্রেসক্রিপশন: আবার দেখানোর ২ দিন আগে আর আগের দিন মনে করানো
    let rxReminders = null;
    try {
      rxReminders = await require('../../lib/prescriptions').followUpReminders(now);
    } catch (e) {
      console.error('rx reminders failed', e);
    }
    // কেনা ওষুধ শেষ হওয়ার ২ দিন আগে
    let refill = null;
    try {
      refill = await require('../../lib/rxnet').refillReminders(now);
    } catch (e) {
      console.error('refill reminders failed', e);
    }
    let inboxDeleted = 0;
    try {
      inboxDeleted = await require('../../lib/inbox').cleanupInbox(now);
    } catch (e) {
      console.error('inbox cleanup failed', e);
    }

    // পুরোনো ভুল রূপে ("001…") রাখা ফার্মেসি/অ্যাম্বুলেন্সের নম্বর ঠিক করা (একবারের কাজ, পরে কিছু পাবে না)
    let phonesFixed = 0;
    try {
      const K = require('../../lib/care');
      for (const col of ['pharmacies', 'ambulances']) {
        const snap = await db.collection(col).where('phone', '>=', '001').where('phone', '<', '002').limit(400).get();
        if (snap.empty) continue;
        const batch = db.batch();
        snap.docs.forEach((d) => batch.update(d.ref, { phone: K.fixPhone(d.get('phone')) }));
        await batch.commit();
        phonesFixed += snap.size;
      }
    } catch (e) {
      console.error('phone fix failed', e);
    }

    return { ok: true, closed: expired.length, eligibleReminders: sent, care, med, homeVisits, inboxDeleted, phonesFixed, emergenciesClosed, cleanup, geo, phoneLimitsDeleted, medFindsDeleted, nurseChambers, rxReminders, refill };
  },
  { methods: ['GET'] },
);
