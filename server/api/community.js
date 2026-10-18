// Health Discussion, Health Tips আর পেশাদার ব্যাজ — সব একটা endpoint-এ (body.action দিয়ে আলাদা)।
// ডাটাবেসের এই অংশ অ্যাপ সরাসরি পড়তে/লিখতে পারে না; সব এখান দিয়ে যায়, তাই ব্যাজ কেউ নিজে বসাতে পারে না।
const { admin, db, FieldValue, HttpError, handler, sendPush } = require('../lib/firebase');
const { isAdmin, isOwner, adminEmails, clearCache } = require('../lib/admin');
const R = require('../lib/rules');
const C = require('../lib/community');
const websession = require('../lib/websession');

async function decodeUser(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) throw new HttpError(401, C.tr(req, 'Please log in', 'লগইন দরকার'));
  return require('../lib/firebase').decodeToken(req, token);
}

const { approvedBadge, useQuota, userName } = require('../lib/community-db');
const appointments = require('../lib/appointments');
const moderation = require('../lib/moderation');
const account = require('../lib/account');
const donors = require('../lib/donors');
const hospitals = require('../lib/hospitals');
const homecare = require('../lib/homecare');
const bloodbank = require('../lib/bloodbank');
const emergency = require('../lib/emergency');
const donationdate = require('../lib/donationdate');
const phone = require('../lib/phone');
const inbox = require('../lib/inbox');
const ambulance = require('../lib/ambulance');
const pharmacy = require('../lib/pharmacy');
const support = require('../lib/support');
const adminweb = require('../lib/adminweb');
const myrequests = require('../lib/myrequests');
const sellerstats = require('../lib/sellerstats');
const medfind = require('../lib/medfind');
const guest = require('../lib/guest');
const { clip } = require('../lib/text');

function cursor(req) {
  const b = req.body?.before;
  return Number.isInteger(b) && b > 0 ? b : null;
}

/**
 * লেখকদের এখনকার ব্যাজ: ব্যাজ বাতিল হলে পুরোনো উত্তর/টিপসে আর "ডাক্তার" দেখায় না,
 * আর যাচাই করা নাম (ব্যাজের নাম) দেখায় — পরে প্রোফাইলের নাম বদলে অন্য কেউ সাজা যায় না।
 */
async function liveBadges(uids) {
  const ids = [...new Set(uids.filter((u) => R.isValidId(u)))];
  const snaps = ids.length ? await db.getAll(...ids.map((u) => db.doc(`badges/${u}`))) : [];
  return new Map(snaps.filter((b) => b.exists && b.get('status') === 'approved').map((b) => [b.id, { type: b.get('type'), name: b.get('name') || '' }]));
}

const actions = {
  /** নিজের ব্যাজ আর অ্যাডমিন কি না */
  async me(req, user) {
    const s = await db.doc(`badges/${user.uid}`).get();
    return {
      badge: s.exists ? {
        type: s.get('type'),
        status: s.get('status'),
        regNo: s.get('regNo') || '',
        workplace: s.get('workplace') || '',
        // অনুমোদিত ব্যাজের তথ্য সংশোধনের অনুরোধ যাচাই চলছে কি না
        changePending: !!s.get('pendingChange'),
        statusReason: s.get('statusReason') || '',
        // অপেক্ষা/বাতিলে বা অ্যাডমিন চাইলে: কোন সনদের ছবি বাকি
        ...(['pending', 'rejected'].includes(s.get('status')) || s.get('docsRequestedAt')
          ? { ...(await require('../lib/verify').ownerInfo('badge', user.uid, s.data()).catch(() => ({}))), docsRequestNote: s.get('docsRequestNote') || '' } : {}),
      } : null,
      isAdmin: await isAdmin(user),
      isOwner: isOwner(user),
      // ফোন যাচাই চালু আছে কি না, আর নিজের নম্বর যাচাই করা কি না
      phoneCheck: phone.enabled(),
      phoneVerified: await phone.isVerified(user.uid),
      unread: await inbox.unreadCount(user.uid).catch(() => 0),
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
          // লগইন ছাড়া (অতিথি) প্রশ্নকারীর নাম দেখায় না — স্বাস্থ্যের প্রশ্নের পাশে আসল নাম কেউ জড়ো করতে না পারে
          authorName: x.anonymous || !user.uid ? null : x.authorName,
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
    const live = await liveBadges(as.docs.map((d) => d.get('uid')));
    return {
      question: {
        id: qs.id,
        text: x.text,
        authorName: x.anonymous || !user.uid ? null : x.authorName,
        mine: x.uid === user.uid,
        // নিজের প্রশ্নে নাম লুকানো আছে কি না (পরে বদলানো যায়)
        ...(x.uid === user.uid ? { anonymous: x.anonymous === true } : {}),
        createdAt: x.createdAt,
        editedAt: x.editedAt || 0,
        answerCount: x.answerCount || 0,
      },
      answers: as.docs.map((d) => ({
        id: d.id,
        text: d.get('text'),
        authorName: live.get(d.get('uid'))?.name || d.get('authorName'),
        badge: live.get(d.get('uid'))?.type || null,
        mine: d.get('uid') === user.uid,
        createdAt: d.get('createdAt'),
        editedAt: d.get('editedAt') || 0,
      })),
    };
  },

  async ask(req, user) {
    const text = C.cleanText(req.body?.text, 1000);
    if (!text) throw new HttpError(400, C.tr(req, 'Write your question (up to 1000 characters)', 'প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)'));
    await useQuota(req, user.uid, 'ask');
    const ref = await db.collection('questions').add({
      uid: user.uid,
      authorName: clip(await userName(user.uid), 80),
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
    const name = clip(await userName(user.uid), 80);
    const batch = db.batch();
    const aRef = qRef.collection('answers').doc();
    batch.set(aRef, { uid: user.uid, authorName: name, badge, text, createdAt: Date.now() });
    batch.update(qRef, { answerCount: FieldValue.increment(1), lastAnswerAt: Date.now() });
    // অ্যাকাউন্ট মুছলে নিজের উত্তরগুলো খুঁজে মোছার জন্য
    batch.set(db.doc(`serverState/${user.uid}`), { answerPaths: FieldValue.arrayUnion(aRef.path) }, { merge: true });
    await batch.commit();

    // প্রশ্নকারীকে জানানো
    const asker = qs.get('uid');
    if (asker && asker !== user.uid) {
      try {
        const u = await db.doc(`users/${asker}`).get();
        if (u.exists) {
          await sendPush([{
            uid: asker,
            tokens: require('../lib/firebase').tokensOf(u),
            title: R.L(u.get('lang'), 'Your question has a new answer', 'আপনার প্রশ্নের উত্তর এসেছে'),
            body: clip(text, 120),
            data: { type: 'answer', questionId: id },
          }]);
        }
      } catch (e) {
        console.error('answer push failed', e);
      }
    }
    return { ok: true };
  },

  /** নিজের প্রশ্ন বদলানো (উত্তর থাকলেও; "সম্পাদিত" দেখায়) */
  async edit_question(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const text = C.cleanText(req.body?.text, 1000);
    if (!text) throw new HttpError(400, C.tr(req, 'Write your question (up to 1000 characters)', 'প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)'));
    const ref = db.doc(`questions/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Question not found', 'প্রশ্নটা পাওয়া যায়নি'));
    if (s.get('uid') !== user.uid) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    // "আমার নাম দেখাবে না" পরে চালু/বন্ধ করা যায় (না পাঠালে আগের মতো)
    const anon = typeof req.body?.anonymous === 'boolean' && req.body.anonymous !== (s.get('anonymous') === true) ? { anonymous: req.body.anonymous } : {};
    if (s.get('text') === text) {
      if (Object.keys(anon).length) await ref.update(anon);
      return { ok: true };
    }
    await ref.update({ text, editedAt: Date.now(), ...anon });
    await moderation.reopenOnEdit(`questions/${id}`);
    return { ok: true };
  },

  async edit_answer(req, user) {
    const { id, answerId } = req.body || {};
    if (!R.isValidId(id) || !R.isValidId(answerId)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const text = C.cleanText(req.body?.text, 2000);
    if (!text) throw new HttpError(400, C.tr(req, 'Write your answer (up to 2000 characters)', 'উত্তর লিখুন (২০০০ অক্ষরের মধ্যে)'));
    const ref = db.doc(`questions/${id}/answers/${answerId}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (s.get('uid') !== user.uid) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    // ব্যাজ চলে গেলে পুরোনো উত্তর বদলে নতুন কথা লেখা যায় না (মুছতে পারবেন)
    if (!(await approvedBadge(user.uid))) throw new HttpError(403, C.tr(req, 'Your professional badge is not active', 'আপনার পেশাদার ব্যাজ এখন চালু নেই'));
    if (s.get('text') === text) return { ok: true };
    await ref.update({ text, editedAt: Date.now() });
    await moderation.reopenOnEdit(`questions/${id}/answers/${answerId}`);
    return { ok: true };
  },

  async delete_question(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`questions/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    await db.recursiveDelete(ref);
    if (s.get('uid') !== user.uid) await adminweb.logAdmin('admin_delete_post', user, { path: `questions/${id}`, text: s.get('text') });
    return { ok: true };
  },

  async delete_answer(req, user) {
    const { id, answerId } = req.body || {};
    if (!R.isValidId(id) || !R.isValidId(answerId)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`questions/${id}/answers/${answerId}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    const batch = db.batch();
    batch.delete(ref);
    batch.update(db.doc(`questions/${id}`), { answerCount: FieldValue.increment(-1) });
    await batch.commit();
    if (s.get('uid') !== user.uid) await adminweb.logAdmin('admin_delete_post', user, { path: `questions/${id}/answers/${answerId}`, text: s.get('text') });
    return { ok: true };
  },

  // ---------- Health Tips ----------
  async tips(req, user) {
    let q = db.collection('tips').orderBy('createdAt', 'desc').limit(C.PAGE);
    const before = cursor(req);
    if (before) q = q.where('createdAt', '<', before);
    const snap = await q.get();
    const live = await liveBadges(snap.docs.map((d) => d.get('uid')));
    return {
      items: snap.docs.map((d) => ({
        id: d.id,
        title: d.get('title'),
        body: d.get('body'),
        authorName: live.get(d.get('uid'))?.name || d.get('authorName'),
        badge: live.get(d.get('uid'))?.type || null,
        mine: d.get('uid') === user.uid,
        createdAt: d.get('createdAt'),
        editedAt: d.get('editedAt') || 0,
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
      authorName: clip(await userName(user.uid), 80),
      badge,
      title,
      body,
      createdAt: Date.now(),
    });
    return { id: ref.id };
  },

  async edit_tip(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const title = C.cleanText(req.body?.title, 120);
    const body = C.cleanText(req.body?.body, 3000);
    if (!title) throw new HttpError(400, C.tr(req, 'Add a title (up to 120 characters)', 'শিরোনাম দিন (১২০ অক্ষরের মধ্যে)'));
    if (!body) throw new HttpError(400, C.tr(req, 'Write the tip (up to 3000 characters)', 'টিপস লিখুন (৩০০০ অক্ষরের মধ্যে)'));
    const ref = db.doc(`tips/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (s.get('uid') !== user.uid) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    if (!(await approvedBadge(user.uid))) throw new HttpError(403, C.tr(req, 'Your professional badge is not active', 'আপনার পেশাদার ব্যাজ এখন চালু নেই'));
    if (s.get('title') === title && s.get('body') === body) return { ok: true };
    await ref.update({ title, body, editedAt: Date.now() });
    await moderation.reopenOnEdit(`tips/${id}`);
    return { ok: true };
  },

  async delete_tip(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`tips/${id}`);
    const s = await ref.get();
    if (!s.exists) return { ok: true };
    if (s.get('uid') !== user.uid && !(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
    await ref.delete();
    if (s.get('uid') !== user.uid) await adminweb.logAdmin('admin_delete_post', user, { path: `tips/${id}`, text: s.get('title') });
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
    // অনুমোদিত ব্যাজ: তথ্য সংশোধনের অনুরোধ — অ্যাডমিন দেখা পর্যন্ত আগের মতোই অনুমোদিত, তালিকা থেকে সরে না
    if (cur.exists && cur.get('status') === 'approved') {
      await useQuota(req, user.uid, 'badge');
      await ref.update({
        // পেশা বদলানো যায় না (তালিকা আর সেবা পেশা ধরে); শুধু নম্বর, কর্মস্থল আর নাম
        pendingChange: { type: cur.get('type'), regNo, workplace, name: clip(await userName(user.uid), 80), at: Date.now() },
        ...(await require('../lib/verify').saveDocs(req, 'badge', user.uid, user.uid, { type: cur.get('type') }, req.body?.docs, false) ? { docsSentAt: Date.now() } : {}),
        docsRequestedAt: FieldValue.delete(), docsRequestNote: FieldValue.delete(),
      });
      return { ok: true, correction: true };
    }
    // সনদের ছবি (নার্স ও অন্য পেশায় দরকারি; ডাক্তারের নম্বর BMDC সাইটে মেলানো হয়) — সীমা খরচের আগে পরীক্ষা
    require('../lib/verify').validateDocs(req, 'badge', { type }, req.body?.docs, true);
    await useQuota(req, user.uid, 'badge');
    const docsHave = (await require('../lib/verify').saveDocs(req, 'badge', user.uid, user.uid, { type }, req.body?.docs, true)) || {};
    await ref.set({
      docsHave,
      type,
      regNo,
      workplace,
      name: clip(await userName(user.uid), 80),
      email: user.email || '',
      status: 'pending',
      createdAt: Date.now(),
    });
    return { ok: true };
  },

  async admin_badges(req, user) {
    if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    // অ্যাপ: অপেক্ষায়; ওয়েব প্যানেল: অনুমোদিত বা বাতিলও
    const status = ['pending', 'approved', 'rejected'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('badges').where('status', '==', status).limit(300).get();
    // অপেক্ষার তালিকায় অনুমোদিত ব্যাজের সংশোধনের অনুরোধও
    // (শুধু ওয়েব প্যানেলে — আগের অ্যাপে "বাতিল" চাপলে ব্যাজই চলে যেত)
    const changes = status === 'pending' && req.body?.panel === true ? await db.collection('badges').where('pendingChange.at', '>', 0).limit(100).get() : { docs: [] };
    return {
      items: await Promise.all([...snap.docs, ...changes.docs].map(async (d) => ({
        // কাগজ আর টিক দেওয়ার তালিকা (ওয়েব প্যানেল)
        ...(req.body?.panel === true && (d.get('status') !== 'approved' || d.get('pendingChange'))
          ? { verify: await require('../lib/verify').panelInfo('badge', d.id, { ...d.data(), type: (d.get('pendingChange') || {}).type || d.get('type') }).catch(() => null) } : {}),
        verification: d.get('verification') || null,
        change: d.get('pendingChange') || null,
        status: d.get('status'),
        reviewedAt: d.get('reviewedAt') || null,
        uid: d.id,
        type: d.get('type'),
        regNo: d.get('regNo'),
        workplace: d.get('workplace'),
        name: d.get('name'),
        email: d.get('email'),
        createdAt: d.get('createdAt'),
      }))),
    };
  },

  async admin_badge_resolve(req, user) {
    if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const uid = req.body?.uid;
    if (!R.isValidId(uid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    // নিজের ব্যাজ নিজে অনুমোদন/বাতিল নয় — অন্য অ্যাডমিন দেখবেন
    if (uid === user.uid) throw new HttpError(403, C.tr(req, 'Another admin must review your own badge', 'নিজের ব্যাজ অন্য অ্যাডমিন দেখবেন'));
    const ref = db.doc(`badges/${uid}`);
    const bs = await ref.get();
    if (!bs.exists) throw new HttpError(404, C.tr(req, 'Application not found', 'আবেদনটা পাওয়া যায়নি'));
    // সংশোধনের অনুরোধ: মঞ্জুর হলে নতুন তথ্য বসে (তালিকার নামও), নইলে শুধু অনুরোধ সরে — ব্যাজ থাকে
    if (req.body?.correction === true) {
      const ch = bs.get('pendingChange');
      if (!ch) return { ok: true, unchanged: true };
      if (req.body?.approve === true) {
        const verification = require('../lib/verify').assertChecks(req, user, 'badge', { type: ch.type, docsHave: bs.get('docsHave') }, req.body?.checks);
        await ref.update({
          type: ch.type, regNo: ch.regNo, workplace: ch.workplace, name: ch.name, pendingChange: FieldValue.delete(), reviewedAt: Date.now(), reviewedBy: user.uid, verification,
          docsRequestedAt: FieldValue.delete(), docsRequestNote: FieldValue.delete(),
        });
        // পেশা বদলালে (যেমন ডাক্তার → নার্স) আগের পেশার তালিকা থেকে সরে; নতুন পেশায় আবার তথ্য দিয়ে চালু করবেন
        const p = await db.doc(`providers/${uid}`).get().catch(() => null);
        if (p && p.exists) {
          const typeChanged = p.get('type') && p.get('type') !== ch.type;
          await p.ref.update({ name: ch.name, ...(typeChanged ? { listed: false, active: false, appointments: false } : {}) }).catch(() => {});
          if (typeChanged) await require('../lib/apptcancel').cancelStale(uid, () => false, ['The doctor/nurse is no longer listed', 'ডাক্তার/নার্স এখন তালিকায় নেই']);
        }
      } else {
        await ref.update({ pendingChange: FieldValue.delete(), docsRequestedAt: FieldValue.delete(), docsRequestNote: FieldValue.delete() });
      }
      await require('../lib/requestend').pushUsers([uid], (u, lang) => ({
        title: req.body?.approve === true ? R.L(lang, 'Your badge details were updated', 'আপনার ব্যাজের তথ্য সংশোধন হয়েছে') : R.L(lang, 'Badge correction not accepted', 'ব্যাজের তথ্য সংশোধনের অনুরোধ গ্রহণ হয়নি'),
        body: '',
        data: { type: 'badge' },
      }));
      return { ok: true };
    }
    const pRef = db.doc(`providers/${uid}`);
    require('../lib/memcache').clear('providers:');
    require('../lib/memcache').clear('home:');
    // অপেক্ষা বা বাতিল থেকে অনুমোদন: প্যানেলে সব বিষয় টিক দেওয়া লাগবে
    const verification = req.body?.approve === true && bs.get('status') !== 'approved'
      ? require('../lib/verify').assertChecks(req, user, 'badge', bs.data(), req.body?.checks) : null;
    if (req.body?.approve !== true) {
      // ব্যাজ বাতিল হলে ডাক্তার/নার্সের তালিকা থেকেও সরে যায় (নতুন আর পুরোনো দুই ধরনের অ্যাপেই),
      // সামনের অ্যাপয়েন্টমেন্ট বাতিল হয়ে রোগীরা জানেন
      const p = await pRef.get();
      if (p.exists) {
        // আগে তালিকায় ছিলেন কি না মনে রাখা (আবার অনুমোদনে শুধু তখনই ফেরে)
        const before = p.get('badgeRejected') === true ? {} : { listedBefore: p.get('listed') === true, activeBefore: p.get('active') === true };
        await pRef.set({ active: false, listed: false, badgeRejected: true, ...before }, { merge: true });
        await require('../lib/apptcancel').cancelStale(uid, () => false, ['The doctor/nurse is no longer listed', 'ডাক্তার/নার্স এখন তালিকায় নেই']);
      }
    } else {
      // আগে বাতিল হয়ে তালিকা থেকে সরে থাকলে আবার অনুমোদনে ফিরে আসেন — শুধু একই পেশায়, আগে তালিকায় থাকলে,
      // আর অ্যাডমিন বন্ধ না রাখলে। পেশা বদলালে (ডাক্তার বাতিল → নার্স অনুমোদন) আগের পেশার তালিকায় ফেরেন না।
      const p = await pRef.get();
      if (p.exists && p.get('badgeRejected') === true) {
        const same = p.get('type') === bs.get('type') && p.get('adminSuspended') !== true;
        const listed = same && p.get('listedBefore') !== false;
        await pRef.update({
          listed,
          active: listed && p.get('activeBefore') !== false && p.get('appointments') === true,
          ...(same ? {} : { appointments: false }),
          badgeRejected: FieldValue.delete(), listedBefore: FieldValue.delete(), activeBefore: FieldValue.delete(),
        });
      }
    }
    await ref.update({
      // ব্যাজ বাতিল হলে ঝুলে থাকা সংশোধনের অনুরোধও সরে (নইলে আবার আবেদন করা যেত না)
      ...(req.body?.approve === true ? {} : { pendingChange: FieldValue.delete() }),
      status: req.body?.approve === true ? 'approved' : 'rejected',
      reviewedAt: Date.now(),
      reviewedBy: user.uid,
      ...(verification ? { verification, docsRequestedAt: FieldValue.delete(), docsRequestNote: FieldValue.delete() } : {}),
      // বাতিলের কারণ (ব্যবহারকারী দেখেন)
      ...(req.body?.approve !== true && C.cleanText(req.body?.reason, 200) ? { statusReason: C.cleanText(req.body.reason, 200) } : {}),
      ...(req.body?.approve === true ? { statusReason: FieldValue.delete() } : {}),
    });
    return { ok: true };
  },

  // ---------- অ্যাডমিন যোগ/বাদ (শুধু মালিক) ----------
  async admin_list(req, user) {
    if (!isOwner(user)) throw new HttpError(403, C.tr(req, 'Owner only', 'শুধু মালিক'));
    const s = await db.doc('config/admins').get();
    return { owners: adminEmails(), items: ((s.exists && s.get('emails')) || []).map((email) => ({ email })) };
  },

  async admin_add(req, user) {
    if (!isOwner(user)) throw new HttpError(403, C.tr(req, 'Owner only', 'শুধু মালিক'));
    const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : '';
    if (!/^[^\s@/]+@[^\s@/]+\.[a-z]{2,}$/.test(email) || email.length > 200) {
      throw new HttpError(400, C.tr(req, 'Enter a valid email', 'সঠিক ইমেইল দিন'));
    }
    const cur = await db.doc('config/admins').get();
    if (((cur.exists && cur.get('emails')) || []).length >= 20) throw new HttpError(400, C.tr(req, 'Up to 20 admins', 'সর্বোচ্চ ২০ জন অ্যাডমিন'));
    await db.doc('config/admins').set({ emails: FieldValue.arrayUnion(email), updatedAt: Date.now() }, { merge: true });
    clearCache();
    return { ok: true };
  },

  async admin_remove(req, user) {
    if (!isOwner(user)) throw new HttpError(403, C.tr(req, 'Owner only', 'শুধু মালিক'));
    const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : '';
    if (!email) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await db.doc('config/admins').set({ emails: FieldValue.arrayRemove(email), updatedAt: Date.now() }, { merge: true });
    clearCache();
    return { ok: true };
  },
};

Object.assign(actions, appointments, moderation, account, donors, hospitals.actions, phone.actions, inbox.actions, ambulance.actions, pharmacy.actions, support.actions, adminweb.actions, myrequests.actions, sellerstats.actions, medfind.actions, homecare.actions, bloodbank.actions, emergency.actions, donationdate.actions, require('../lib/requestcreate').actions, require('../lib/devices').actions, require('../lib/verify').actions, require('../lib/useractivity').actions, require('../lib/prescriptions').actions, require('../lib/catalog').actions, require('../lib/rxnet').actions, require('../lib/labprices').actions, require('../lib/labtests').actions, require('../lib/careaccess').actions, require('../lib/queue').actions, guest.actions);
// ফার্মেসির হিসাব-খাতা (পণ্য, কেনা, বিক্রি, লাভ-ক্ষতি)
Object.assign(actions, require('../lib/shopbooks').actions);
// ডেমো (পরীক্ষা) — ডেমো মুছলে এই লাইনও মুছবেন
Object.assign(actions, require('../lib/demo').actions);

/**
 * ওয়েব প্যানেল: ঢুকলেই সব অপেক্ষমাণ কাজ একসাথে, এলাকার নাম সহ।
 * একটা অংশ ব্যর্থ হলেও বাকিগুলো দেখায় (সেই অংশে error থাকে)।
 */
actions.admin_overview = async (req, user) => {
  if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
  const { UPAZILA, UNION } = require('../lib/hospital-match');
  const area = (district, upazilaId, unionId) =>
    [UNION.get(unionId)?.bn, UPAZILA.get(upazilaId)?.bn, district].filter(Boolean).join(', ');
  const part = async (fn) => {
    try {
      return { items: await fn() };
    } catch (e) {
      console.error('overview part failed', e);
      return { items: [], error: e instanceof HttpError ? e.message : C.tr(req, 'Could not load', 'আনা যায়নি') };
    }
  };
  const withArea = (list) => list.map((x) => ({ ...x, areaLabel: area(x.district, x.upazilaId, x.unionId) }));
  const owner = isOwner(user);
  const [badges, pharmacies, ambulances, bloodBanks, labs, hospitalRequests, reports, corrections, adminList] = await Promise.all([
    part(async () => (await actions.admin_badges(req, user)).items),
    part(async () => withArea((await actions.admin_pharmacies(req, user)).items)),
    part(async () => withArea((await actions.admin_ambulances(req, user)).items)),
    part(async () => withArea((await actions.admin_bloodbanks(req, user)).items)),
    part(async () => (await actions.admin_labs(req, user)).items),
    part(async () => (await actions.admin_hospital_requests(req, user)).items.map((h) => ({
      ...h,
      areaLabel: area(h.district, h.upazilaId, h.unionId),
      possibleDuplicates: (h.possibleDuplicates || []).map((d) => ({ ...d, areaLabel: area(null, d.upazilaId, d.unionId) })),
    }))),
    part(async () => (await actions.admin_reports(req, user)).items),
    part(() => require('../lib/corrections').pendingCorrections()),
    owner ? actions.admin_list(req, user).catch(() => null) : null,
  ]);
  const tickets = await part(async () => (await actions.admin_tickets({ ...req, body: { ...req.body, status: 'open' } }, user)).items);
  return {
    me: { email: user.email, isOwner: owner },
    badges,
    pharmacies,
    ambulances,
    bloodBanks,
    labs,
    hospitalRequests,
    reports,
    corrections,
    tickets,
    admins: adminList,
    at: Date.now(),
  };
};

// অ্যাকাউন্ট বন্ধ কি না (Firebase Auth) — প্রতি অনুরোধে না পড়ে ১ মিনিট মনে রাখা
const disabledCache = new Map();
async function accountDisabled(uid) {
  const c = disabledCache.get(uid);
  if (c && Date.now() - c.at < 60 * 1000) return c.v;
  const u = await admin.auth().getUser(uid).catch((e) => (e.code === 'auth/user-not-found' ? { disabled: true } : null));
  const v = !!(u && u.disabled);
  disabledCache.set(uid, { at: Date.now(), v });
  return v;
}

// ওয়েব অ্যাডমিন প্যানেল (কুকি দিয়ে লগইন): শুধু অ্যাডমিন কাজ চলে, অ্যাপের সাধারণ কাজ নয়
async function webUser(req, res) {
  const web = websession.user(req, res);
  if (!web) return null;
  if (web.error) throw new HttpError(401, C.tr(req, 'Session expired, please log in again', 'লগইনের মেয়াদ শেষ, আবার লগইন করুন'), 'session_expired');
  const action = String(req.body?.action || '');
  if (action === 'web_logout') return web;
  // অ্যাডমিন প্যানেল বাছাইয়ের তালিকাও পড়ে (catalog_get, labtests_get); বাকি সব শুধু admin_ কাজ
  if (!action.startsWith('admin_') && !['catalog_get', 'labtests_get'].includes(action)) throw new HttpError(403, C.tr(req, 'Not available on the web panel', 'ওয়েব প্যানেলে এটা চলে না'));
  // অ্যাডমিন থেকে বাদ পড়লে বা অ্যাকাউন্ট বন্ধ হলে প্যানেলও বন্ধ
  if (!(await isAdmin(web)) || (await accountDisabled(web.uid))) {
    websession.end(res);
    throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'), 'session_expired');
  }
  return web;
}

actions.web_logout = async (req, user, res) => {
  websession.end(res);
  return { ok: true };
};

module.exports = handler(async (req, res) => {
  const hasBearer = String(req.headers.authorization || '').startsWith('Bearer ');
  let user = hasBearer ? await decodeUser(req) : await webUser(req, res);
  if (!user) {
    // লগইন ছাড়া (অতিথি): শুধু পড়ার কিছু কাজ, IP-প্রতি সীমাসহ; বাকি সব কাজে আগের মতো "লগইন দরকার"
    if (!guest.isPublic(req.body?.action)) user = await decodeUser(req);
    else {
      guest.checkRate(req);
      user = guest.guestUser();
    }
  }
  const fn = Object.prototype.hasOwnProperty.call(actions, req.body?.action) ? actions[req.body.action] : null;
  // বাছাইয়ের তালিকা (প্যানেলে বদলানো যায়) — ৫ মিনিটে একবার পড়া
  await require('../lib/catalog').load();
  if (!fn) throw new HttpError(400, C.tr(req, 'Unknown request. Please update the app.', 'অজানা অনুরোধ। অ্যাপ আপডেট করুন।'));
  // কেয়ারগিভার রোগীর হয়ে (body.asUid) — অনুমোদিত কাজ আর চালু সংযোগ হলে রোগীর uid দিয়ে
  const actor = user.guest ? user : await require('../lib/careaccess').actAs(req, user);
  // অতিথির অনুরোধ (সবই শুধু পড়া, কারো নিজের তথ্য নেই): একই অনুরোধের উত্তর এক মিনিট মেমোরিতে — বারবার ডাটাবেস পড়া নয়
  const result = user.guest
    ? await require('../lib/memcache').cached(`guest:${guest.cacheKey(req.body)}`, 60 * 1000, () => fn(req, actor, res))
    : await fn(req, actor, res);
  // অ্যাডমিনের কাজ ইতিহাসে (কে, কখন, কী) — কিছু না বদলালে নয়
  if (String(req.body.action).startsWith('admin_') && !(result && result.unchanged)) await adminweb.logAdmin(req.body.action, user, req.body);
  return result;
});
