// সাহায্য ও আপিল: ব্যবহারকারী সমস্যা বা আপিল লেখেন, অ্যাডমিন ওয়েব প্যানেল থেকে উত্তর দেন।
// tickets/{id} — অ্যাপ সরাসরি পড়তে/লিখতে পারে না (Firestore নিয়মে নেই), সব এখান দিয়ে।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const { useQuota, userName } = require('./community-db');

const CATEGORIES = ['account', 'badge', 'pharmacy', 'ambulance', 'bloodbank', 'homevisit', 'homecare', 'medorder', 'appointment', 'blood', 'other'];
const STATUSES = ['open', 'answered', 'closed'];
const MAX_OPEN = 3;
const MAX_MESSAGES = 40;
const TEXT_MAX = 1500;

function view(d) {
  const x = d.data();
  return {
    id: d.id,
    category: x.category,
    status: x.status,
    messages: (x.messages || []).map((m) => ({ from: m.from, text: m.text, at: m.at })),
    createdAt: x.createdAt,
    updatedAt: x.updatedAt,
  };
}

async function notifyUser(uid, ticketId) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    const lang = u.get('lang');
    await sendPush([{
      uid,
      tokens: require('./firebase').tokensOf(u),
      title: R.L(lang, 'Reply from the Dorkar team', 'Dorkar টিমের উত্তর এসেছে'),
      body: R.L(lang, 'Open Help & appeals to read it', '"সাহায্য ও আপিল" খুলে দেখুন'),
      data: { type: 'support', ticketId },
    }]);
  } catch (e) {
    console.error('ticket push failed', e);
  }
}

const actions = {
  /** নতুন আপিল/সমস্যা */
  async ticket_create(req, user) {
    const b = req.body || {};
    const category = CATEGORIES.includes(b.category) ? b.category : null;
    if (!category) throw new HttpError(400, C.tr(req, 'Choose what it is about', 'কী নিয়ে, বেছে নিন'));
    const text = C.cleanText(b.text, TEXT_MAX);
    if (!text || text.length < 10) throw new HttpError(400, C.tr(req, 'Please describe the problem (at least 10 letters)', 'সমস্যাটা একটু খুলে লিখুন (অন্তত ১০ অক্ষর)'));
    // একটা ফিল্ডে খোঁজা (আলাদা ইনডেক্স লাগে না), অবস্থা এখানে গোনা
    const mine = await db.collection('tickets').where('uid', '==', user.uid).limit(100).get();
    if (mine.docs.filter((d) => d.get('status') !== 'closed').length >= MAX_OPEN) {
      throw new HttpError(429, C.tr(req, 'You already have 3 open requests. Please wait for a reply or add to an existing one.', 'আপনার ৩টা অনুরোধ খোলা আছে। উত্তরের অপেক্ষা করুন বা আগেরটাতেই লিখুন।'));
    }
    await useQuota(req, user.uid, 'ticket');
    const now = Date.now();
    const ref = await db.collection('tickets').add({
      uid: user.uid,
      email: (user.email || '').toLowerCase(),
      name: await userName(user.uid),
      category,
      status: 'open',
      messages: [{ from: 'user', text, at: now }],
      lastFrom: 'user',
      createdAt: now,
      updatedAt: now,
    });
    return { ok: true, id: ref.id };
  },

  async tickets_mine(req, user) {
    const snap = await db.collection('tickets').where('uid', '==', user.uid).limit(50).get();
    const items = snap.docs.map(view).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 20);
    return { items };
  },

  /** আগের অনুরোধে আরও লেখা (বন্ধ থাকলে আবার খোলে) */
  async ticket_reply(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const text = C.cleanText(req.body?.text, TEXT_MAX);
    if (!text) throw new HttpError(400, C.tr(req, 'Write something', 'কিছু লিখুন'));
    const ref = db.doc(`tickets/${id}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists || s.get('uid') !== user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const messages = s.get('messages') || [];
      if (messages.length >= MAX_MESSAGES) throw new HttpError(400, C.tr(req, 'This conversation is full. Please start a new one.', 'এই কথোপকথন ভরে গেছে। নতুন করে লিখুন।'));
      const now = Date.now();
      t.update(ref, { messages: [...messages, { from: 'user', text, at: now }], status: 'open', lastFrom: 'user', updatedAt: now });
    });
    return { ok: true };
  },

  // ---------- অ্যাডমিন ----------
  async admin_tickets(req, user) {
    await requireAdmin(req, user);
    const status = STATUSES.includes(req.body?.status) ? req.body.status : 'open';
    const snap = await db.collection('tickets').where('status', '==', status).limit(200).get();
    const items = snap.docs
      .map((d) => ({ ...view(d), uid: d.get('uid'), email: d.get('email') || '', name: d.get('name') || '', lastFrom: d.get('lastFrom') }))
      // খোলা: পুরোনোটা আগে (যিনি বেশিক্ষণ অপেক্ষা করছেন); বাকি: নতুনটা আগে
      .sort((a, b) => (status === 'open' ? a.updatedAt - b.updatedAt : b.updatedAt - a.updatedAt));
    return { items };
  },

  async admin_ticket(req, user) {
    await requireAdmin(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const d = await db.doc(`tickets/${id}`).get();
    if (!d.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    return { item: { ...view(d), uid: d.get('uid'), email: d.get('email') || '', name: d.get('name') || '', lastFrom: d.get('lastFrom') } };
  },

  /** উত্তর দেওয়া, চাইলে সাথে বন্ধ করা (শুধু বন্ধ করলে লেখা লাগে না) */
  async admin_ticket_reply(req, user) {
    await requireAdmin(req, user);
    const { id } = req.body || {};
    const close = req.body?.close === true;
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const text = req.body?.text ? C.cleanText(req.body.text, TEXT_MAX) : null;
    if (!text && !close) throw new HttpError(400, C.tr(req, 'Write a reply', 'উত্তর লিখুন'));
    const ref = db.doc(`tickets/${id}`);
    const uid = await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const messages = s.get('messages') || [];
      if (text && messages.length >= MAX_MESSAGES) {
        throw new HttpError(400, C.tr(req, 'This conversation is full. Close it; the user can start a new one.', 'এই কথোপকথন ভরে গেছে। বন্ধ করুন, ব্যবহারকারী নতুন করে লিখতে পারবেন।'));
      }
      const now = Date.now();
      t.update(ref, {
        ...(text ? { messages: [...messages, { from: 'admin', text, at: now, by: user.email || '' }], lastFrom: 'admin' } : {}),
        status: close ? 'closed' : 'answered',
        updatedAt: now,
      });
      return s.get('uid');
    });
    if (text) await notifyUser(uid, id);
    return { ok: true };
  },
};

function requireAdmin(req, user) {
  return require('./admin').isAdmin(user).then((ok) => {
    if (!ok) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
  });
}

module.exports = { actions, CATEGORIES };
