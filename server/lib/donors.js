// রক্তপ্রার্থী রাজি হওয়া ডোনারদের মধ্যে থেকে যাঁদের নেবেন তাঁদের "নিশ্চিত" করেন।
// নিশ্চিত ডোনার নোটিফিকেশন পান; যত ব্যাগ দরকার তত জন নিশ্চিত হলে বাকি রাজি ডোনাররা জানেন "আপাতত লাগবে না"।
// সাড়ার status "accepted"-ই থাকে, শুধু confirmed: true যোগ হয় — তাই Firestore-এর নিয়ম বদলাতে হয়নি।
const { db, FieldValue, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const RESTORE_MS = 30 * 60 * 1000;

async function pushTo(uids, make) {
  if (!uids.length) return;
  try {
    const users = await db.getAll(...uids.map((id) => db.doc(`users/${id}`)));
    await sendPush(users.filter((u) => u.exists).map((u) => make(u)));
  } catch (e) {
    console.error('donor push failed', e);
  }
}

module.exports = {
  /**
   * ডোনার: "বাতিল" (cancel) বা "রক্ত দিয়েছি" (donated); রক্তপ্রার্থী: "বাদ দিন" (remove)।
   * নিশ্চিত ডোনার চলে গেলে আবার হিসাব হয়; ব্যাগ আর পূরণ না থাকলে রক্তপ্রার্থী আর অপেক্ষায় থাকা ডোনারদের জানানো হয়।
   */
  async response_update(req, user) {
    const { requestId, op } = req.body || {};
    const donorUid = req.body?.donorUid || user.uid;
    if (!R.isValidId(requestId) || !R.isValidId(donorUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const reqRef = db.doc(`requests/${requestId}`);
    const respRef = reqRef.collection('responses').doc(donorUid);

    const result = await db.runTransaction(async (t) => {
      const [rs, ds] = await Promise.all([t.get(reqRef), t.get(respRef)]);
      if (!rs.exists || !ds.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const r = rs.data();
      const isDonor = donorUid === user.uid;
      const isRequester = r.requesterUid === user.uid;
      let next = null;
      if (ds.get('status') === 'accepted') {
        if (isDonor && op === 'cancel') next = 'cancelled';
        else if (isDonor && op === 'donated') next = 'donated';
        else if (isRequester && !isDonor && op === 'remove') next = 'declined';
      }
      // ভুল করে বাদ দিলে ৩০ মিনিটের মধ্যে রক্তপ্রার্থী ফিরিয়ে আনতে পারেন (চালু রিকোয়েস্টে)
      if (op === 'restore') {
        const ok = isRequester && !isDonor && ds.get('status') === 'declined' && ds.get('removedBy') === 'requester'
          && Date.now() - (ds.get('removedAt') || 0) <= RESTORE_MS && R.isActive(r);
        if (!ok) throw new HttpError(400, C.tr(req, 'This donor can no longer be brought back', 'এই ডোনারকে আর ফিরিয়ে আনা যায় না'));
        t.update(respRef, { status: 'accepted', confirmed: false, removedBy: FieldValue.delete(), removedAt: FieldValue.delete(), updatedAt: Date.now() });
        t.update(reqRef, { acceptedCount: FieldValue.increment(1) });
        return { r, restored: true };
      }
      if (!next) throw new HttpError(400, C.tr(req, 'This can\'t be changed now', 'এখন এটা বদলানো যায় না'));

      const wasConfirmed = ds.get('confirmed') === true;
      const all = await t.get(reqRef.collection('responses').where('status', 'in', ['accepted', 'donated']));
      // "রক্ত দিয়েছি": ডোনারের শেষ রক্তদানের তারিখ সার্ভারই বসায় (শুধু সামনে)
      const donorRef = db.doc(`users/${donorUid}`);
      const donorSnap = next === 'donated' ? await t.get(donorRef) : null;
      t.update(respRef, { status: next, updatedAt: Date.now(), ...(next === 'declined' ? { removedBy: 'requester', removedAt: Date.now() } : {}) });
      // বাতিল/বাদ হলে রাজির সংখ্যা কমে ("রক্ত দিয়েছি" হলে রাজির মধ্যেই গোনা থাকে)
      if (next !== 'donated') t.update(reqRef, { acceptedCount: FieldValue.increment(-1) });
      if (donorSnap && donorSnap.exists) {
        const today = R.todayDhakaUtcMidnight();
        const last = donorSnap.get('lastDonationMillis');
        if (last == null || last < today) t.update(donorRef, { lastDonationMillis: today });
      }

      // নিশ্চিত/রক্ত দেওয়া ডোনার আবার গোনা — সব অবস্থায়, যাতে "ডোনার পাওয়া গেছে" কখনো পুরোনো না থাকে
      const count = all.docs.filter(
        (d) => d.id !== donorUid && (d.get('status') === 'donated' || d.get('confirmed') === true),
      ).length + (next === 'donated' ? 1 : 0);
      const fulfilled = count >= (r.bags || 1);
      t.update(reqRef, { confirmedCount: count, fulfilled });
      const standby = all.docs
        .filter((d) => d.id !== donorUid && d.get('status') === 'accepted' && d.get('confirmed') !== true)
        .map((d) => d.id);
      const lost = wasConfirmed && next !== 'donated';
      return { r, lost, donated: next === 'donated', need: Math.max((r.bags || 1) - count, 0), reopened: r.fulfilled === true && !fulfilled, standby };
    });

    const { r } = result;
    // এক জায়গায় রক্ত দিলে অন্য রিকোয়েস্টের "রাজি" মুক্ত (সেই রক্তপ্রার্থীরা জানেন)
    if (result.donated) await require('./requestend').releaseOthers(donorUid, requestId);
    if (result.restored) {
      await pushTo([donorUid], (u) => ({
        uid: u.id,
        tokens: require('./firebase').tokensOf(u),
        title: R.L(u.get('lang'), 'You are back on the request', 'আপনাকে আবার রিকোয়েস্টে রাখা হয়েছে'),
        body: R.L(u.get('lang'), `${r.hospital}: the requester removed you by mistake. Please wait for confirmation.`, `${r.hospital}: রক্তপ্রার্থী ভুল করে বাদ দিয়েছিলেন। নিশ্চিত করার অপেক্ষায় থাকুন।`),
        data: { requestId, type: 'request' },
      }));
      return { ok: true, restored: true };
    }
    // রক্তপ্রার্থী বাদ দিলে ডোনারকে জানানো — নইলে তিনি হাসপাতালে চলে যেতে পারেন
    if (op === 'remove') {
      await pushTo([donorUid], (u) => ({
        uid: u.id,
        tokens: require('./firebase').tokensOf(u),
        title: R.L(u.get('lang'), 'You are no longer needed for this request', 'এই রিকোয়েস্টে আপনাকে আর লাগবে না'),
        body: R.L(u.get('lang'), `${r.hospital}: you don't need to go. Thank you for offering!`, `${r.hospital}: আপনাকে যেতে হবে না। রাজি হওয়ার জন্য ধন্যবাদ!`),
        data: { requestId, type: 'request' },
      }));
    }
    if (result.lost && R.isActive(r)) {
      // রক্তপ্রার্থীকে জানানো (ডোনার নিজে বাতিল করলে)
      if (op === 'cancel') {
        await pushTo([r.requesterUid], (u) => ({
          uid: u.id,
          tokens: require('./firebase').tokensOf(u),
          title: R.L(u.get('lang'), 'A confirmed donor cancelled', 'একজন নিশ্চিত ডোনার বাতিল করেছেন'),
          body: R.L(
            u.get('lang'),
            `${result.need} more donor(s) needed. Confirm another from My Request.`,
            `আরও ${result.need} জন লাগবে। "আমার রিকোয়েস্ট" থেকে আরেকজনকে নিশ্চিত করুন।`,
          ),
          data: { requestId, type: 'response' },
        }));
      }
      // "যথেষ্ট পাওয়া গেছে" শুনে অপেক্ষায় থাকা ডোনারদের জানানো যে আবার লাগতে পারে
      if (result.reopened) {
        await pushTo(result.standby, (u) => ({
          uid: u.id,
          tokens: require('./firebase').tokensOf(u),
          title: R.L(u.get('lang'), 'A donor may be needed again', 'আবার ডোনার লাগতে পারে'),
          body: R.L(u.get('lang'), 'The requester will confirm soon. Please stay available.', 'রক্তপ্রার্থী শিগগিরই নিশ্চিত করবেন। অনুগ্রহ করে প্রস্তুত থাকুন।'),
          data: { requestId, type: 'request' },
        }));
      }
    }
    return { ok: true };
  },

  async confirm_donor(req, user) {
    const { requestId, donorUid } = req.body || {};
    if (!R.isValidId(requestId) || !R.isValidId(donorUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const reqRef = db.doc(`requests/${requestId}`);
    const respRef = reqRef.collection('responses').doc(donorUid);

    const result = await db.runTransaction(async (t) => {
      const [rs, ds] = await Promise.all([t.get(reqRef), t.get(respRef)]);
      if (!rs.exists) throw new HttpError(404, C.tr(req, 'Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি'));
      const r = rs.data();
      if (r.requesterUid !== user.uid) throw new HttpError(403, C.tr(req, 'This is not your request', 'এটা আপনার রিকোয়েস্ট না'));
      if (!R.isActive(r)) throw new HttpError(400, C.tr(req, 'This request is closed', 'রিকোয়েস্টটা বন্ধ হয়ে গেছে'));
      if (!ds.exists || ds.get('status') !== 'accepted') {
        throw new HttpError(400, C.tr(req, 'This donor is no longer available', 'এই ডোনার এখন আর রাজি অবস্থায় নেই'));
      }
      if (ds.get('confirmed') === true) return null;

      // নিশ্চিত/রক্ত দেওয়া ডোনার গোনা (এইজন সহ)
      const all = await t.get(reqRef.collection('responses').where('status', 'in', ['accepted', 'donated']));
      const counted = all.docs.filter((d) => d.id === donorUid || d.get('confirmed') === true || d.get('status') === 'donated');
      const confirmedCount = counted.length;
      // যত ব্যাগ দরকার তার বেশি জনকে "আসুন" বলা যায় না (আরও লাগলে আগে ব্যাগ বাড়াতে হবে)
      if (confirmedCount > (r.bags || 1)) {
        throw new HttpError(400, C.tr(req, 'All bags are already covered. To confirm another donor, increase the bags first.', 'সব ব্যাগের ডোনার নিশ্চিত হয়ে গেছে। আরেকজন নিশ্চিত করতে আগে ব্যাগের সংখ্যা বাড়ান।'));
      }
      const fulfilled = confirmedCount >= (r.bags || 1);
      t.update(respRef, { confirmed: true, confirmedAt: Date.now() });
      t.update(reqRef, { confirmedCount, fulfilled });
      // এইমাত্র পূরণ হলে, বাকি রাজি (নিশ্চিত না হওয়া) ডোনারদের জানাতে হবে
      const others = fulfilled && !r.fulfilled
        ? all.docs.filter((d) => d.id !== donorUid && d.get('status') === 'accepted' && d.get('confirmed') !== true).map((d) => d.id)
        : [];
      return { r, others };
    });
    if (!result) return { ok: true, already: true };

    const { r, others } = result;
    try {
      const ids = [donorUid, ...others];
      const users = await db.getAll(...ids.map((id) => db.doc(`users/${id}`)));
      const when = r.neededAtMillis ? r.neededAtMillis : null;
      await sendPush(
        users
          .filter((u) => u.exists)
          .map((u) => {
            const lang = u.get('lang');
            const time = when ? ` · ${lang === 'en' ? R.formatTimeEn(when) : R.formatTimeBn(when)}` : '';
            return u.id === donorUid
              ? {
                uid: u.id,
                tokens: require('./firebase').tokensOf(u),
                title: R.L(lang, 'You are confirmed — please come', 'আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন'),
                body: `${r.hospital}${time}`,
                data: { requestId, type: 'request' },
              }
              : {
                uid: u.id,
                tokens: require('./firebase').tokensOf(u),
                title: R.L(lang, 'Enough donors for now — thank you!', 'আপাতত যথেষ্ট ডোনার পাওয়া গেছে, ধন্যবাদ!'),
                body: R.L(lang, 'Please stay available in case it changes.', 'দরকার হলে আবার জানানো হবে।'),
                data: { requestId, type: 'request' },
              };
          }),
      );
    } catch (e) {
      console.error('confirm push failed', e);
    }
    return { ok: true };
  },
};
