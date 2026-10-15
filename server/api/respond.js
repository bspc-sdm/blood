// ডোনার "রক্ত দেব" চাপলে: যাচাই করে সাড়া তৈরি করে, আর রক্তপ্রার্থীকে জানায়।
// সাড়া শুধু সার্ভার তৈরি করতে পারে, তাই দিনে কয়টা সাড়া দেওয়া যাবে তার সীমা কেউ এড়াতে পারে না।
const { db, HttpError, verifyUser, handler, sendPush } = require('../lib/firebase');
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

    t.set(responseRef, {
      donorUid: uid,
      donorName: u.name || '',
      donorPhone: u.phone,
      donorGroup: u.bloodGroup,
      status: 'accepted',
      createdAtMillis: Date.now(),
    });
    t.set(stateRef, { respondDay: day, respondCount: used + 1 }, { merge: true });
    // ডোনারের "প্রসেসিং" তালিকার জন্য: কোন কোন রিকোয়েস্টে রাজি হয়েছেন (অ্যাপ নিজের প্রোফাইল থেকে পড়ে)
    t.set(userRef, { commitments: { [requestId]: Date.now() } }, { merge: true });
    return { ...r, donorName: u.name || '', donorGroup: u.bloodGroup };
  });

  if (!request) return { ok: true, already: true };

  // রক্তপ্রার্থীকে জানানো (না গেলেও সাড়া ঠিকই সেভ হয়েছে)
  try {
    const requester = await db.doc(`users/${request.requesterUid}`).get();
    const token = requester.get('fcmToken');
    if (token) {
      await sendPush([
        {
          uid: request.requesterUid,
          token,
          title: 'একজন ডোনার রাজি হয়েছেন',
          body: `${request.donorName} (${request.donorGroup}) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।`,
          data: { requestId, type: 'response' },
        },
      ]);
    }
  } catch (e) {
    console.error('requester push failed', e);
  }

  return { ok: true };
});
