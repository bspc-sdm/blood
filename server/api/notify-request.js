// নতুন রিকোয়েস্ট দেওয়ার পর অ্যাপ এটা ডাকে: আশেপাশের মানানসই, যোগ্য ডোনারদের পুশ নোটিফিকেশন পাঠায়।
const { db, FieldValue, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
const R = require('../lib/rules');

const LOCK_MS = 60 * 1000;
// "আবার জানান": আরও ডোনার লাগলে — একটা রিকোয়েস্টে মোট ৩ বার, দুইবারের মাঝে ৬ ঘণ্টা; আগে জানানো মানুষ বাদ
const MAX_ROUNDS = 3;
const AGAIN_GAP_MS = 6 * 60 * 60 * 1000;

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = req.body?.requestId;
  if (!R.isValidId(requestId)) throw new HttpError(400, 'requestId ঠিক নেই');

  const ref = db.doc(`requests/${requestId}`);
  const stateRef = db.doc(`serverState/${uid}`);
  const now = Date.now();
  const day = R.dhakaDayKey(now);
  const again = req.body?.again === true;
  const metaRef = ref.collection('meta').doc('notified');

  // যাচাই + সাময়িক তালা, যাতে একই রিকোয়েস্টে একসাথে দুবার পাঠানো না হয়
  const request = await db.runTransaction(async (t) => {
    const [snap, stateSnap] = await Promise.all([t.get(ref), t.get(stateRef)]);
    if (!snap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = { id: snap.id, ...snap.data() };
    if (r.requesterUid !== uid) throw new HttpError(403, 'এটা আপনার রিকোয়েস্ট না');
    if (!R.isActive(r, now)) throw new HttpError(400, 'রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ');
    if (r.notifiedAt && !again) return null;
    if ((r.notifyingUntil || 0) > now) throw new HttpError(409, 'নোটিফিকেশন পাঠানো চলছে, একটু পরে দেখুন');

    const invalid = R.validateRequest(r, now);
    if (invalid) throw new HttpError(400, invalid);

    if (again) {
      if (!r.notifiedAt) throw new HttpError(400, 'আগে একবার ডোনারদের জানান');
      const rounds = r.notifyRounds || 1;
      if (rounds >= MAX_ROUNDS) throw new HttpError(429, `একটা রিকোয়েস্টে সর্বোচ্চ ${MAX_ROUNDS} বার জানানো যায়। পরিচিতদের সরাসরি জানান।`);
      const last = r.lastNotifiedAt || (r.notifiedAt && r.notifiedAt.toMillis ? r.notifiedAt.toMillis() : 0);
      if (now - last < AGAIN_GAP_MS) {
        const h = Math.ceil((AGAIN_GAP_MS - (now - last)) / 3600000);
        throw new HttpError(429, `${h} ঘণ্টা পর আবার জানাতে পারবেন`);
      }
      if (R.bagsLeft(r) <= 0) throw new HttpError(400, 'সব ব্যাগের ডোনার পাওয়া গেছে');
      t.update(ref, { notifyingUntil: now + LOCK_MS });
      return { ...r, rounds };
    }

    // একজন দিনে সর্বোচ্চ ৩টা রিকোয়েস্টের নোটিফিকেশন পাঠাতে পারবে
    const used = stateSnap.exists && stateSnap.get('requestDay') === day ? stateSnap.get('requestCount') || 0 : 0;
    if (used >= R.MAX_REQUESTS_PER_DAY) {
      throw new HttpError(429, `দিনে সর্বোচ্চ ${R.MAX_REQUESTS_PER_DAY}টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।`);
    }

    t.update(ref, { notifyingUntil: now + LOCK_MS });
    t.set(stateRef, { requestDay: day, requestCount: used + 1 }, { merge: true });
    return r;
  });
  if (!request) {
    const again = await ref.get();
    return { ok: true, already: true, sent: again.get('notifiedCount') || 0 };
  }

  try {
    // একসাথে খোলা রিকোয়েস্টের সীমা (অ্যাপেও আটকানো হয়, এখানে আবার যাচাই)
    const mine = await db.collection('requests').where('requesterUid', '==', uid).get();
    const openCount = mine.docs.filter((d) => R.isActive(d.data(), now)).length;
    if (openCount > R.MAX_OPEN_REQUESTS) {
      throw new HttpError(429, `একসাথে সর্বোচ্চ ${R.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়`);
    }

    // কাছের ব্যবহারকারী (geohash): ডোনার সর্বোচ্চ যতদূর যেতে রাজি হতে পারেন, ততদূরের ভেতরের সবাই
    const usersSnap = { docs: (await require('../lib/geo').near(db.collection('users'), 'geohash', request.lat, request.lng, Math.max(...R.RADIUS_CHOICES))).map((x) => x.doc) };
    // আগে যাঁদের জানানো হয়েছে তাঁরা বাদ (আবার জানানোর সময়) — তাহলে দূরের নতুন ডোনাররা আসেন
    const meta = await metaRef.get();
    const already = new Set((meta.exists && meta.get('uids')) || []);
    const allUsers = usersSnap.docs.map((d) => ({ uid: d.id, ...d.data() }));
    if (again) {
      // যাঁরা আগেই সাড়া দিয়েছেন (রাজি, নিশ্চিত, বাদ পড়া) তাঁদের আবার নয়
      const resp = await ref.collection('responses').get();
      resp.docs.forEach((d) => already.add(d.id));
      // ২.৯-এর আগে জানানো রিকোয়েস্টে তালিকা নেই — প্রথমবার যাঁরা পেয়েছিলেন আন্দাজে বাদ
      if (!meta.exists) R.pickDonors(request, allUsers, now).forEach((u) => already.add(u.uid));
    }
    const users = allUsers.filter((u) => !already.has(u.uid));
    let donors = R.pickDonors(request, users, now);

    // প্রত্যেক ডোনার দিনে সর্বোচ্চ যতটা নোটিফিকেশন চেয়েছেন (সেটিংস, না বদলালে ৩টা)
    const stateRefs = donors.map((u) => db.doc(`serverState/${u.uid}`));
    const states = stateRefs.length ? await db.getAll(...stateRefs) : [];
    const pushedToday = (s) => (s.exists && s.get('pushDay') === day ? s.get('pushCount') || 0 : 0);
    const counts = new Map(donors.map((u, i) => [u.uid, pushedToday(states[i])]));
    donors = donors.filter((u) => counts.get(u.uid) < R.pushLimit(u));

    const sent = await sendPush(
      donors.map((u) => ({
        uid: u.uid,
        tokens: require('../lib/firebase').tokensOf(u),
        title: R.L(
          u.lang,
          `${request.patientGroup} blood needed${request.urgent ? ' (urgent)' : ''}`,
          `${request.patientGroup} রক্ত দরকার${request.urgent ? ' (জরুরি)' : ''}`,
        ),
        body: `${R.L(u.lang, `${request.bags} bag(s)`, `${request.bags} ব্যাগ`)} · ${request.hospital}, ${R.districtLabel(request.placeName, u.lang)} · ${R.formatKm(u.km, u.lang)}`,
        data: { requestId, type: 'request' },
      })),
    );

    const batch = db.batch();
    donors.forEach((u) => {
      batch.set(
        db.doc(`serverState/${u.uid}`),
        counts.get(u.uid) > 0
          ? { pushDay: day, pushCount: FieldValue.increment(1) }
          : { pushDay: day, pushCount: 1 },
        { merge: true },
      );
    });
    batch.update(ref, again
      ? { notifiedCount: FieldValue.increment(sent), notifyRounds: request.rounds + 1, lastNotifiedAt: now, notifyingUntil: FieldValue.delete() }
      : { notifiedAt: FieldValue.serverTimestamp(), notifiedCount: sent, notifyRounds: 1, lastNotifiedAt: now, notifyingUntil: FieldValue.delete() });
    if (donors.length) {
      batch.set(metaRef, { uids: [...already, ...donors.map((u) => u.uid)].slice(-2000) });
    }
    await batch.commit();

    return { ok: true, sent };
  } catch (e) {
    // কিছু ভুল হলে তালা খুলে দেওয়া, যাতে অ্যাপ থেকে আবার চেষ্টা করা যায়
    await ref.update({ notifyingUntil: FieldValue.delete() }).catch(() => {});
    throw e;
  }
});
