// কেয়ারগিভার আর পরিবারের সদস্য (১৭ অক্টোবর ২০২৬)
// - প্রত্যেকে ১–২ জন কেয়ারগিভার (জরুরি যোগাযোগ) যোগ করতে পারেন — তাঁদের Lifolo অ্যাকাউন্টের ইমেইল দিয়ে।
//   কেয়ারগিভার রাজি হলে নিজের ফোন থেকেই রোগীর হয়ে প্রেসক্রিপশন, ফার্মেসি/প্যাথলজিতে পাঠানো, রিপোর্টের টাকা,
//   সিরিয়াল — এসব করতে পারেন (body.asUid); রোগীর নোটিফিকেশনও পান। রোগী যেকোনো সময় সরাতে পারেন।
//   লগইন ভাগ করা হয়নি: তাতে কে কী করল বোঝা যায় না, আর একবার দিলে ফেরত নেওয়া যায় না।
//   প্রোফাইল বদল, অ্যাকাউন্ট মোছা, রক্তের অনুরোধ — এসব কেয়ারগিভার পারেন না (CARE_ACTIONS-এর বাইরে)।
// - পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই — বয়স্ক বাবা-মা, শিশু): users/{uid}.family, সর্বোচ্চ ৬ জন।
//   তাঁদের নামে সিরিয়াল নেওয়া যায়, তাঁদের ফোন নম্বর দিয়ে প্রেসক্রিপশন যোগ করা যায়।
// careLinks/{patientUid}_{caretakerUid}: { patientUid, caretakerUid, patientName, caretakerName, caretakerEmail, status, createdAt, acceptedAt }
const { admin, db, HttpError, sendPush, tokensOf } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const { useQuota, userName } = require('./community-db');

const MAX_CARETAKERS = 2;
const MAX_FAMILY = 6;
const EMAIL_RE = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;

/** কেয়ারগিভার রোগীর হয়ে যা যা করতে পারেন */
const CARE_ACTIONS = new Set([
  'rx_mine', 'rx_get', 'rx_claim', 'rx_activity', 'rx_pharmacy_options', 'rx_send_pharmacy', 'rx_order_withdraw', 'rx_shop_find',
  'rx_send_lab', 'labs', 'lab_order_get', 'review_pay', 'labtests_get',
  'my_appointments', 'book', 'appointment_update', 'providers', 'family_get',
]);

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const linkId = (patientUid, caretakerUid) => `${patientUid}_${caretakerUid}`;

async function activeCaretakers(patientUid) {
  const snap = await db.collection('careLinks').where('patientUid', '==', patientUid).limit(10).get();
  return snap.docs.filter((d) => d.get('status') === 'active').map((d) => d.get('caretakerUid'));
}

/**
 * body.asUid থাকলে: কেয়ারগিভার রোগীর হয়ে কাজ করছেন — চালু সংযোগ আর অনুমোদিত কাজ হলে রোগীর uid দিয়ে চলে।
 * ফেরত: যার হয়ে কাজ চলবে সেই user (না থাকলে আগেরটাই)।
 */
async function actAs(req, user) {
  const asUid = req.body?.asUid;
  if (asUid == null || asUid === '' || asUid === user.uid) return user;
  const action = String(req.body?.action || '');
  if (!R.isValidId(asUid) || !CARE_ACTIONS.has(action)) {
    throw err(req, "A caregiver can't do this for the patient", 'কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না', 403);
  }
  const s = await db.doc(`careLinks/${linkId(asUid, user.uid)}`).get();
  if (!s.exists || s.get('status') !== 'active') {
    throw new HttpError(403, C.tr(req, "You are no longer this person's caregiver", 'আপনি আর এই মানুষের কেয়ারগিভার নন'), 'care_revoked');
  }
  return { uid: asUid, actingBy: user.uid, email: '', email_verified: false, firebase: user.firebase };
}

/** রোগী আর তাঁর কেয়ারগিভারদের নোটিফিকেশন (কেয়ারগিভারের শিরোনামে রোগীর নাম, খুললে রোগীর হয়ে) */
async function pushPatient(uid, en, bn, body, data) {
  if (!uid) return;
  try {
    const [u, carers] = await Promise.all([db.doc(`users/${uid}`).get(), activeCaretakers(uid)]);
    const list = [];
    if (u.exists) list.push({ uid, tokens: tokensOf(u), title: R.L(u.get('lang'), en, bn), body: body || '', data });
    if (carers.length) {
      const name = (u.exists && u.get('name')) || '';
      const cs = await db.getAll(...carers.map((c) => db.doc(`users/${c}`)));
      for (const c of cs) {
        if (!c.exists) continue;
        list.push({ uid: c.id, tokens: tokensOf(c), title: `${name}: ${R.L(c.get('lang'), en, bn)}`, body: body || '', data: { type: 'care', asUid: uid } });
      }
    }
    if (list.length) await sendPush(list);
  } catch (e) {
    console.error('care push failed', e.message);
  }
}

function linkView(d) {
  const x = d.data();
  return {
    patientUid: x.patientUid, caretakerUid: x.caretakerUid, patientName: x.patientName || '', caretakerName: x.caretakerName || '',
    caretakerEmail: x.caretakerEmail || '', status: x.status, createdAt: x.createdAt || 0, acceptedAt: x.acceptedAt || 0,
  };
}

function cleanMember(req, m, i) {
  const name = C.cleanText(String(m?.name || ''), 60);
  if (!name) throw err(req, `Member ${i + 1}: write the name`, `সদস্য ${i + 1}: নাম লিখুন`);
  const age = Number.isInteger(m.age) && m.age >= 0 && m.age <= 120 ? m.age : null;
  const gender = ['male', 'female'].includes(m.gender) ? m.gender : '';
  const phone = m.phone ? K.normalizeBd(m.phone) : null;
  if (m.phone && !phone) throw err(req, `${name}: check the mobile number`, `${name}: মোবাইল নম্বর দেখে নিন`);
  const id = typeof m.id === 'string' && /^m_[a-z0-9]{6,12}$/.test(m.id) ? m.id : `m_${require('crypto').randomBytes(5).toString('hex')}`;
  return { id, name, relation: String(m.relation || '').trim().slice(0, 30), age, gender, phone: phone ? K.localBd(phone) : '' };
}

const actions = {
  /** আমার কেয়ারগিভার, যাঁদের দেখাশোনা করি, আর পরিবারের সদস্য */
  async care_list(req, user) {
    const [mine, forOthers, u] = await Promise.all([
      db.collection('careLinks').where('patientUid', '==', user.uid).limit(10).get(),
      db.collection('careLinks').where('caretakerUid', '==', user.uid).limit(30).get(),
      db.doc(`users/${user.uid}`).get(),
    ]);
    return {
      caretakers: mine.docs.map(linkView),
      caringFor: forOthers.docs.map(linkView),
      family: (u.exists && u.get('family')) || [],
    };
  },

  /** রোগী: কেয়ারগিভার যোগ (তাঁর Lifolo অ্যাকাউন্টের ইমেইল) — তিনি রাজি হলে চালু */
  async care_invite(req, user) {
    const email = String(req.body?.email || '').trim().toLowerCase();
    if (!EMAIL_RE.test(email)) throw err(req, 'Enter their email', 'তাঁর ইমেইল দিন');
    if (email === String(user.email || '').toLowerCase()) throw err(req, "You can't add yourself", 'নিজেকে যোগ করা যায় না');
    const mine = await db.collection('careLinks').where('patientUid', '==', user.uid).limit(10).get();
    if (mine.size >= MAX_CARETAKERS) throw err(req, `Up to ${MAX_CARETAKERS} caregivers. Remove one first.`, `সর্বোচ্চ ${MAX_CARETAKERS} জন কেয়ারগিভার। আগে একজনকে সরান।`, 409);
    const target = await admin.auth().getUserByEmail(email).catch(() => null);
    if (!target) throw err(req, 'No Lifolo account with this email. Ask them to install Lifolo and log in first.', 'এই ইমেইলে কোনো Lifolo অ্যাকাউন্ট নেই। আগে তাঁকে Lifolo খুলে লগইন করতে বলুন।', 404);
    if (target.uid === user.uid) throw err(req, "You can't add yourself", 'নিজেকে যোগ করা যায় না');
    const ref = db.doc(`careLinks/${linkId(user.uid, target.uid)}`);
    if ((await ref.get()).exists) throw err(req, 'Already added', 'আগেই যোগ করা আছে', 409);
    await useQuota(req, user.uid, 'care');
    const [me, them] = await Promise.all([userName(user.uid), userName(target.uid)]);
    await ref.set({
      patientUid: user.uid, caretakerUid: target.uid, patientName: me.slice(0, 80), caretakerName: (them || target.displayName || '').slice(0, 80),
      caretakerEmail: email, status: 'invited', createdAt: Date.now(),
    });
    const u = await db.doc(`users/${target.uid}`).get();
    if (u.exists) {
      await sendPush([{ uid: target.uid, tokens: tokensOf(u), title: R.L(u.get('lang'), `${me} wants you as their caregiver`, `${me} আপনাকে কেয়ারগিভার করতে চান`),
        body: R.L(u.get('lang'), 'Open Lifolo → Profile → Caregivers to accept', 'Lifolo → প্রোফাইল → কেয়ারগিভার থেকে রাজি হন'), data: { type: 'care' } }]).catch(() => {});
    }
    return { ok: true };
  },

  /** কেয়ারগিভার: রাজি / না */
  async care_respond(req, user) {
    const { patientUid } = req.body || {};
    if (!R.isValidId(patientUid)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const ref = db.doc(`careLinks/${linkId(patientUid, user.uid)}`);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    if (req.body?.accept === true) {
      await ref.update({ status: 'active', acceptedAt: Date.now(), caretakerName: (await userName(user.uid)).slice(0, 80) });
      const p = await db.doc(`users/${patientUid}`).get();
      if (p.exists) {
        await sendPush([{ uid: patientUid, tokens: tokensOf(p), title: R.L(p.get('lang'), `${s.get('caretakerName')} is now your caregiver`, `${s.get('caretakerName')} এখন আপনার কেয়ারগিভার`), body: '', data: { type: 'care' } }]).catch(() => {});
      }
    } else {
      await ref.delete();
    }
    return { ok: true };
  },

  /** রোগী কেয়ারগিভারকে সরান, বা কেয়ারগিভার নিজে সরে যান */
  async care_remove(req, user) {
    const { patientUid, caretakerUid } = req.body || {};
    const id = R.isValidId(caretakerUid) ? linkId(user.uid, caretakerUid) : R.isValidId(patientUid) ? linkId(patientUid, user.uid) : null;
    if (!id) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    await db.doc(`careLinks/${id}`).delete();
    return { ok: true };
  },

  /** পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই) */
  async family_get(req, user) {
    const u = await db.doc(`users/${user.uid}`).get();
    return { family: (u.exists && u.get('family')) || [] };
  },

  async family_set(req, user) {
    const raw = Array.isArray(req.body?.family) ? req.body.family : null;
    if (!raw) throw err(req, 'Nothing to save', 'সেভ করার কিছু নেই');
    if (raw.length > MAX_FAMILY) throw err(req, `Up to ${MAX_FAMILY} members`, `সর্বোচ্চ ${MAX_FAMILY} জন সদস্য`);
    const family = raw.map((m, i) => cleanMember(req, m, i));
    await db.doc(`users/${user.uid}`).set({ family }, { merge: true });
    return { family };
  },
};

/** অ্যাকাউন্ট মুছলে: দুই দিকের সংযোগ */
async function deleteFor(uid) {
  const [a, b] = await Promise.all([
    db.collection('careLinks').where('patientUid', '==', uid).limit(20).get(),
    db.collection('careLinks').where('caretakerUid', '==', uid).limit(50).get(),
  ]);
  await Promise.all([...a.docs, ...b.docs].map((d) => d.ref.delete()));
}

module.exports = { actions, actAs, pushPatient, activeCaretakers, deleteFor, CARE_ACTIONS, MAX_CARETAKERS, MAX_FAMILY, _clean: { cleanMember } };
