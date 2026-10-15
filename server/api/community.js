// Health Discussion, Health Tips আর পেশাদার ব্যাজ — সব একটা endpoint-এ (body.action দিয়ে আলাদা)।
// ডাটাবেসের এই অংশ অ্যাপ সরাসরি পড়তে/লিখতে পারে না; সব এখান দিয়ে যায়, তাই ব্যাজ কেউ নিজে বসাতে পারে না।
const { admin, db, FieldValue, HttpError, handler, sendPush } = require('../lib/firebase');
const { isAdminToken } = require('../lib/admin');
const R = require('../lib/rules');
const C = require('../lib/community');

async function decodeUser(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) throw new HttpError(401, C.tr(req, 'Please log in', 'লগইন দরকার'));
  try {
    return await admin.auth().verifyIdToken(token);
  } catch {
    throw new HttpError(401, C.tr(req, 'Session expired, please log in again', 'লগইনের মেয়াদ শেষ, আবার লগইন করুন'));
  }
}

/** অনুমোদিত ব্যাজ (না থাকলে null) */
async function approvedBadge(uid) {
  const s = await db.doc(`badges/${uid}`).get();
  return s.exists && s.get('status') === 'approved' ? s.get('type') : null;
}

/** দৈনিক সীমা (serverState/{uid}) */
async function useQuota(req, uid, kind) {
  const ref = db.doc(`serverState/${uid}`);
  const day = R.dhakaDayKey();
  await db.runTransaction(async (t) => {
    const s = await t.get(ref);
    const used = s.exists && s.get(`${kind}Day`) === day ? s.get(`${kind}Count`) || 0 : 0;
    if (used >= C.LIMITS[kind]) {
      throw new HttpError(429, C.tr(req, 'Daily limit reached. Try again tomorrow.', 'আজকের সীমা শেষ। কাল আবার চেষ্টা করুন।'));
    }
    t.set(ref, { [`${kind}Day`]: day, [`${kind}Count`]: used + 1 }, { merge: true });
  });
}

async function userName(uid) {
  const s = await db.doc(`users/${uid}`).get();
  return (s.exists && s.get('name')) || '';
}

function cursor(req) {
  const b = req.body?.before;
  return Number.isInteger(b) && b > 0 ? b : null;
}

const actions = {
  /** নিজের ব্যাজ আর অ্যাডমিন কি না */
  async me(req, user) {
    const s = await db.doc(`badges/${user.uid}`).get();
    return {
      badge: s.exists ? { type: s.get('type'), status: s.get('status') } : null,
      isAdmin: isAdminToken(user),
    };
  },

  // ---------- Health Discussion ----------
  async questions(req, user) {
    let q = db.collection('questions').orderBy('createdAt', 'desc').limit(C.PAGE);
    const before = cursor(req);
    if (before) q = q.where('createdAt', '<', before);
    const snap = await q.get();
    return {
      items: snap.docs.map((d) => {
        const x = d.data();
        return {
          id: d.id,
          text: x.text,
          authorName: x.anonymous ? null : x.authorName,
          mine: x.uid === user.uid,
          createdAt: x.createdAt,
          answerCount: x.answerCount || 0,
        };
      }),
    };
  },

  async question(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`questions/${id}`);
    const [qs, as] = await Promise.all([ref.get(), ref.collection('answers').orderBy('createdAt').limit(200).get()]);
    if (!qs.exists) throw new HttpError(404, C.tr(req, 'Question not found', 'প্রশ্নটা পাওয়া যায়নি'));
    const x = qs.data();
    return {
      question: {
        id: qs.id,
        text: x.text,
        authorName: x.anonymous ? null : x.authorName,
        mine: x.uid === user.uid,
        createdAt: x.createdAt,
        answerCount: x.answerCount || 0,
      },
      answers: as.docs.map((d) => ({
        id: d.id,
        text: d.get('text'),
        authorName: d.get('authorName'),
        badge: d.get('badge'),
        mine: d.get('uid') === user.uid,
        createdAt: d.get('createdAt'),
      })),
    };
  },

  async ask(req, user) {
    const text = C.cleanText(req.body?.text, 1000);
    if (!text) throw new HttpError(400, C.tr(req, 'Write your question (up to 1000 characters)', 'প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)'));
    await useQuota(req, user.uid, 'ask');
    const ref = await db.collection('questions').add({
      uid: user.uid,
      authorName: (await userName(user.uid)).slice(0, 80),
      anonymous: req.body?.anonymous === true,
      text,
      createdAt: Date.now(),
      answerCount: 0,
    });
    return { id: ref.id };
  },

  async answer(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const badge = await approvedBadge(user.uid);
    if (!badge) {
      throw new HttpError(403, C.tr(req, 'Only verified health professionals can answer', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা উত্তর দিতে পারেন'));
    }
    const text = C.cleanText(req.body?.text, 2000);
    if (!text) throw new HttpError(400, C.tr(req, 'Write your answer (up to 2000 characters)', 'উত্তর লিখুন (২০০০ অক্ষরের মধ্যে)'));
    const qRef = db.doc(`questions/${id}`);
    const qs = await qRef.get();
    if (!qs.exists) throw new HttpError(404, C.tr(req, 'Question not found', 'প্রশ্নটা পাওয়া যায়নি'));
    await useQuota(req, user.uid, 'answer');
    const name = (await userName(user.uid)).slice(0, 80);
    const batch = db.batch();
    batch.set(qRef.collection('answers').doc(), { uid: user.uid, authorName: name, badge, text, createdAt: Date.now() });
    batch.update(qRef, { answerCount: FieldValue.increment(1), lastAnswerAt: Date.now() });
    await batch.commit();

    // প্রশ্নকারীকে জানানো
    const asker = qs.get('uid');
    if (asker && asker !== user.uid) {
      try {
        const u = await db.doc(`users/${asker}`).get();
        const token = u.get('fcmToken');
        if (token) {
          await sendPush([{
            uid: asker,
            token,
            title: R.L(u.get('lang'), 'Your question has a new answer', 'আপনার প্রশ্নের উত্তর এসেছে'),
            body: text.slice(0, 120),
            data: { type: 'answer', questionId: id },
          }]);
        }
      } catch (e) {
        console.error('answer push failed', e);
      }
    }
    return { ok: true };
  },

  async delete_question(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`questions/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    await db.recursiveDelete(ref);
    return { ok: true };
  },

  async delete_answer(req, user) {
    const { id, answerId } = req.body || {};
    if (!R.isValidId(id) || !R.isValidId(answerId)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`questions/${id}/answers/${answerId}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    const batch = db.batch();
    batch.delete(ref);
    batch.update(db.doc(`questions/${id}`), { answerCount: FieldValue.increment(-1) });
    await batch.commit();
    return { ok: true };
  },

  // ---------- Health Tips ----------
  async tips(req, user) {
    let q = db.collection('tips').orderBy('createdAt', 'desc').limit(C.PAGE);
    const before = cursor(req);
    if (before) q = q.where('createdAt', '<', before);
    const snap = await q.get();
    return {
      items: snap.docs.map((d) => ({
        id: d.id,
        title: d.get('title'),
        body: d.get('body'),
        authorName: d.get('authorName'),
        badge: d.get('badge'),
        mine: d.get('uid') === user.uid,
        createdAt: d.get('createdAt'),
      })),
    };
  },

  async post_tip(req, user) {
    const badge = await approvedBadge(user.uid);
    if (!badge) {
      throw new HttpError(403, C.tr(req, 'Only verified health professionals can post tips', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন'));
    }
    const title = C.cleanText(req.body?.title, 120);
    const body = C.cleanText(req.body?.body, 3000);
    if (!title) throw new HttpError(400, C.tr(req, 'Add a title (up to 120 characters)', 'শিরোনাম দিন (১২০ অক্ষরের মধ্যে)'));
    if (!body) throw new HttpError(400, C.tr(req, 'Write the tip (up to 3000 characters)', 'টিপস লিখুন (৩০০০ অক্ষরের মধ্যে)'));
    await useQuota(req, user.uid, 'tip');
    const ref = await db.collection('tips').add({
      uid: user.uid,
      authorName: (await userName(user.uid)).slice(0, 80),
      badge,
      title,
      body,
      createdAt: Date.now(),
    });
    return { id: ref.id };
  },

  async delete_tip(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`tips/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    await ref.delete();
    return { ok: true };
  },

  // ---------- পেশাদার ব্যাজ ----------
  async badge_apply(req, user) {
    const type = req.body?.type;
    if (!C.isBadge(type)) throw new HttpError(400, C.tr(req, 'Choose a profession', 'পেশা বেছে নিন'));
    const regNo = C.cleanText(req.body?.regNo, 60);
    const workplace = C.cleanText(req.body?.workplace, 120);
    if (!regNo) throw new HttpError(400, C.tr(req, 'Enter your registration / licence number', 'রেজিস্ট্রেশন / লাইসেন্স নম্বর দিন'));
    if (!workplace) throw new HttpError(400, C.tr(req, 'Enter your workplace', 'কর্মস্থল লিখুন'));
    const ref = db.doc(`badges/${user.uid}`);
    const cur = await ref.get();
    if (cur.exists && cur.get('status') === 'pending') {
      throw new HttpError(409, C.tr(req, 'Your application is already under review', 'আপনার আবেদন যাচাই চলছে'));
    }
    await useQuota(req, user.uid, 'badge');
    await ref.set({
      type,
      regNo,
      workplace,
      name: (await userName(user.uid)).slice(0, 80),
      email: user.email || '',
      status: 'pending',
      createdAt: Date.now(),
    });
    return { ok: true };
  },

  async admin_badges(req, user) {
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const snap = await db.collection('badges').where('status', '==', 'pending').limit(100).get();
    return {
      items: snap.docs.map((d) => ({
        uid: d.id,
        type: d.get('type'),
        regNo: d.get('regNo'),
        workplace: d.get('workplace'),
        name: d.get('name'),
        email: d.get('email'),
        createdAt: d.get('createdAt'),
      })),
    };
  },

  async admin_badge_resolve(req, user) {
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const uid = req.body?.uid;
    if (!R.isValidId(uid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`badges/${uid}`);
    if (!(await ref.get()).exists) throw new HttpError(404, C.tr(req, 'Application not found', 'আবেদনটা পাওয়া যায়নি'));
    await ref.update({
      status: req.body?.approve === true ? 'approved' : 'rejected',
      reviewedAt: Date.now(),
      reviewedBy: user.uid,
    });
    return { ok: true };
  },
};

module.exports = handler(async (req) => {
  const user = await decodeUser(req);
  const fn = actions[req.body?.action];
  if (!fn) throw new HttpError(400, C.tr(req, 'Unknown request. Please update the app.', 'অজানা অনুরোধ। অ্যাপ আপডেট করুন।'));
  return fn(req, user);
});
