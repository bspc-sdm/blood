// ডোনার "রক্ত দেব" চাপলে: যাচাই করে সাড়া তৈরি করে, আর রক্তপ্রার্থীকে জানায়।
// সাড়া শুধু সার্ভার তৈরি করতে পারে, তাই দিনে কয়টা সাড়া দেওয়া যাবে তার সীমা কেউ এড়াতে পারে না।
const { db, FieldValue, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
const R = require('../lib/rules');

module.exports = handler(async (req) => {
  const uid = await verifyUser(req);
  const requestId = req.body?.requestId;
  if (!R.isValidId(requestId)) throw new HttpError(400, 'requestId ঠিক নেই');

  const requestRef = db.doc(`requests/${requestId}`);
  const responseRef = db.doc(`requests/${requestId}/responses/${uid}`);
  const stateRef = db.doc(`serverState/${uid}`);
  const userRef = db.doc(`users/${uid}`);

  const request = await db.runTransaction(async (t) => {
    const [reqSnap, userSnap, respSnap, stateSnap] = await Promise.all([
      t.get(requestRef),
      t.get(userRef),
      t.get(responseRef),
      t.get(stateRef),
    ]);
    if (!reqSnap.exists) throw new HttpError(404, 'রিকোয়েস্ট পাওয়া যায়নি');
    const r = reqSnap.data();
    const u = userSnap.data() || {};
    if (!R.isActive(r)) throw new HttpError(400, 'রিকোয়েস্টটা বন্ধ হয়ে গেছে');
    if (r.requesterUid === uid) throw new HttpError(400, 'নিজের রিকোয়েস্টে সাড়া দেওয়া যায় না');
    if (!u.phone || !u.bloodGroup) throw new HttpError(400, 'আগে প্রোফাইল পূরণ করুন');
    if (u.bloodGroup === 'unknown') throw new HttpError(400, 'রক্ত দিতে আগে প্রোফাইলে আপনার রক্তের গ্রুপ দিন');
    if (!R.canDonate(u.bloodGroup, r.patientGroup)) {
      throw new HttpError(400, `আপনার গ্রুপ (${u.bloodGroup}) এই রোগীকে দেওয়া যায় না`);
    }
    if (!R.isEligibleDonor(u)) {
      throw new HttpError(400, 'আপনি এখন রক্ত দেওয়ার যোগ্য নন (বয়স ১৮–৬০, ওজন কমপক্ষে ৫০ কেজি, আর শেষ রক্তদানের পর ১২০ দিন লাগে)');
    }

    const existing = respSnap.exists ? respSnap.data().status : null;
    if (existing === 'declined') throw new HttpError(403, 'রক্তপ্রার্থী অন্য ডোনার বেছে নিয়েছেন');
    if (existing === 'accepted' || existing === 'donated') return null; // আগেই রাজি

    // দৈনিক সীমা
    const day = R.dhakaDayKey();
    const used = stateSnap.exists && stateSnap.get('respondDay') === day ? stateSnap.get('respondCount') || 0 : 0;
    if (used >= R.MAX_RESPONSES_PER_DAY) {
      throw new HttpError(429, `দিনে সর্বোচ্চ ${R.MAX_RESPONSES_PER_DAY}টা রিকোয়েস্টে সাড়া দেওয়া যায়। কাল আবার চেষ্টা করুন।`);
    }

    // কতজন রাজি আছেন — শুধু সংখ্যা, সবাই দেখতে পান (নাম/ফোন নয়)
    t.update(requestRef, { acceptedCount: FieldValue.increment(1) });
    t.set(responseRef, {
      donorUid: uid,
      donorName: u.name || '',
      donorPhone: u.phone,
      donorGroup: u.bloodGroup,
      // রক্তপ্রার্থী দেখবেন ডোনার কত দূরে আর যাতায়াত খরচ লাগবে কি না
      distanceKm: typeof u.lat === 'number' && typeof u.lng === 'number'
        ? Math.round(R.distanceKm(r.lat, r.lng, u.lat, u.lng))
        : null,
      fareAfterKm: typeof u.fareAfterKm === 'number' ? u.fareAfterKm : null,
      status: 'accepted',
      createdAtMillis: Date.now(),
    });
    // respondedTo সার্ভারের নিজের তালিকাতেও (অ্যাপ প্রোফাইল বদলালেও অ্যাকাউন্ট মোছার সময় সব সাড়া খুঁজে পাওয়া যায়)
    t.set(stateRef, { respondDay: day, respondCount: used + 1, respondedTo: { [requestId]: Date.now() } }, { merge: true });
    // ডোনারের "প্রসেসিং" তালিকার জন্য: কোন কোন রিকোয়েস্টে রাজি হয়েছেন (অ্যাপ নিজের প্রোফাইল থেকে পড়ে)
    // respondedTo স্থায়ী তালিকা: অ্যাকাউন্ট মুছলে নিজের সাড়া (নাম ও ফোন সহ) খুঁজে মোছার জন্য
    t.set(userRef, { commitments: { [requestId]: Date.now() }, respondedTo: { [requestId]: Date.now() } }, { merge: true });
    return { ...r, donorName: u.name || '', donorGroup: u.bloodGroup };
  });

  if (!request) return { ok: true, already: true };

  // রক্তপ্রার্থীকে জানানো (না গেলেও সাড়া ঠিকই সেভ হয়েছে)
  try {
    const requester = await db.doc(`users/${request.requesterUid}`).get();
    if (requester.exists) {
      await sendPush([
        {
          uid: request.requesterUid,
          tokens: require('../lib/firebase').tokensOf(requester),
          title: R.L(requester.get('lang'), 'A donor is ready to give blood', 'একজন ডোনার রাজি হয়েছেন'),
          body: R.L(
            requester.get('lang'),
            `${request.donorName} (${request.donorGroup}) agreed to donate. Open the app for their number.`,
            `${request.donorName} (${request.donorGroup}) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।`,
          ),
          data: { requestId, type: 'response' },
        },
      ]);
    }
  } catch (e) {
    console.error('requester push failed', e);
  }

  return { ok: true };
});
