// ওয়েব অ্যাডমিন প্যানেলের কাজ: অ্যানালিটিক্স, ব্যবহারকারী খোঁজা ও ব্যবস্থা, কাজের ইতিহাস।
const { admin, db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const { isAdmin, isOwner, adminEmails } = require('./admin');

const DAY = 24 * 3600 * 1000;
const DHAKA = 6 * 3600 * 1000;
const STATS_TTL = 30 * 60 * 1000;

async function requireAdmin(req, user) {
  if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

/** ঢাকার সময়ে আজকের মধ্যরাত (UTC মিলিসেকেন্ডে) */
function dhakaMidnight(now) {
  return Math.floor((now + DHAKA) / DAY) * DAY - DHAKA;
}

async function count(q) {
  const s = await q.count().get();
  return s.data().count;
}

/** একসাথে অনেক count() — ২০টা করে */
async function counts(queries) {
  const out = [];
  for (let i = 0; i < queries.length; i += 20) {
    out.push(...(await Promise.all(queries.slice(i, i + 20).map((q) => count(q).catch((e) => {
      console.error('count failed', e.message);
      return null;
    })))));
  }
  return out;
}

/** Firebase Auth থেকে ব্যবহারকারীর হিসাব (Firestore-এর পড়া খরচ হয় না) */
async function authStats(now, start30) {
  const newPerDay = new Array(30).fill(0);
  let total = 0;
  let disabled = 0;
  let new7 = 0;
  let new30 = 0;
  let active7 = 0;
  let active30 = 0;
  let pageToken;
  for (let page = 0; page < 50; page++) {
    const res = await admin.auth().listUsers(1000, pageToken);
    for (const u of res.users) {
      total++;
      if (u.disabled) disabled++;
      const created = Date.parse(u.metadata.creationTime || '') || 0;
      const seen = Math.max(Date.parse(u.metadata.lastRefreshTime || '') || 0, Date.parse(u.metadata.lastSignInTime || '') || 0);
      if (now - created < 7 * DAY) new7++;
      if (now - created < 30 * DAY) new30++;
      if (now - seen < 7 * DAY) active7++;
      if (now - seen < 30 * DAY) active30++;
      const idx = Math.floor((created - start30) / DAY);
      if (idx >= 0 && idx < 30) newPerDay[idx]++;
    }
    pageToken = res.pageToken;
    if (!pageToken) break;
  }
  return { total, disabled, new7, new30, active7, active30, newPerDay };
}

async function buildStats(now) {
  const today = dhakaMidnight(now);
  const start30 = today - 29 * DAY;
  const days = Array.from({ length: 30 }, (_, i) => start30 + i * DAY);

  // দৈনিক সংখ্যা: একটা ফিল্ডে পরিসর (আলাদা ইনডেক্স লাগে না)
  const SERIES = [
    ['requests', 'requests', 'createdAtMillis'],
    // বাসায় সেবা: নতুন রোগী-ডাক্তার/নার্স যোগাযোগ (প্রথম ফোনের দিন)
    ['homeCalls', 'homeCalls', 'firstAt'],
    ['appointments', 'appointments', 'createdAt'],
  ];
  const dailyQueries = SERIES.flatMap(([, col, field]) =>
    days.map((d) => db.collection(col).where(field, '>=', d).where(field, '<', d + DAY)));

  const STATUS = {
    appointments: ['pending', 'confirmed', 'declined', 'cancelled', 'expired', 'completed'],
  };
  const statusQueries = Object.entries(STATUS).flatMap(([col, list]) => list.map((s) => db.collection(col).where('status', '==', s)));

  const totalsSpec = [
    ['usersWithProfile', db.collection('users')],
    ['openRequests', db.collection('requests').where('status', '==', 'open')],
    ['allRequests', db.collection('requests')],
    ['doctors', db.collection('badges').where('type', '==', 'doctor').where('status', '==', 'approved')],
    ['nurses', db.collection('badges').where('type', '==', 'nurse').where('status', '==', 'approved')],
    ['nutritionists', db.collection('badges').where('type', '==', 'nutritionist').where('status', '==', 'approved')],
    ['trainers', db.collection('badges').where('type', '==', 'trainer').where('status', '==', 'approved')],
    ['homeDoctors', db.collection('providers').where('type', '==', 'doctor').where('homeVisit.enabled', '==', true)],
    ['homeNurses', db.collection('providers').where('type', '==', 'nurse').where('homeVisit.enabled', '==', true)],
    ['pharmacies', db.collection('pharmacies').where('status', '==', 'approved')],
    ['ambulances', db.collection('ambulances').where('status', '==', 'approved')],
    ['bloodBanks', db.collection('bloodBanks').where('status', '==', 'approved')],
    ['questions', db.collection('questions')],
    ['tips', db.collection('tips')],
    ['openTickets', db.collection('tickets').where('status', '==', 'open')],
  ];
  const groupQueries = R.BLOOD_GROUPS.map((g) => db.collection('users').where('bloodGroup', '==', g));

  const [daily, statuses, totals, groups, users] = await Promise.all([
    counts(dailyQueries),
    counts(statusQueries),
    counts(totalsSpec.map(([, q]) => q)),
    counts(groupQueries),
    authStats(now, start30).catch((e) => {
      console.error('auth stats failed', e.message);
      return null;
    }),
  ]);

  const series = {};
  SERIES.forEach(([key], i) => { series[key] = daily.slice(i * 30, i * 30 + 30); });
  if (users) series.newUsers = users.newPerDay;
  const byStatus = {};
  let k = 0;
  for (const [col, list] of Object.entries(STATUS)) {
    byStatus[col] = {};
    list.forEach((s) => { byStatus[col][s] = statuses[k++]; });
  }
  const t = {};
  totalsSpec.forEach(([key], i) => { t[key] = totals[i]; });
  const bloodGroups = {};
  R.BLOOD_GROUPS.forEach((g, i) => { bloodGroups[g] = groups[i]; });
  if (users) delete users.newPerDay;
  return { at: now, days, series, byStatus, totals: t, bloodGroups, users };
}

// ---------------- ব্যবহারকারী ----------------

function phoneVariants(raw) {
  const p = K.normalizeBd(raw);
  if (!p) return [];
  const local = K.localBd(p);
  return [local, `+${p}`, p];
}

async function authUser(uid) {
  try {
    return await admin.auth().getUser(uid);
  } catch (e) {
    if (e.code === 'auth/user-not-found') return null;
    throw e;
  }
}

function brief(u, doc) {
  return {
    uid: u ? u.uid : doc.id,
    email: u ? u.email || '' : '',
    name: (doc && doc.exists && doc.get('name')) || '',
    phone: (doc && doc.exists && doc.get('phone')) || '',
    bloodGroup: (doc && doc.exists && doc.get('bloodGroup')) || '',
    district: (doc && doc.exists && doc.get('placeName')) || '',
    disabled: !!(u && u.disabled),
  };
}

const ms = (ts) => (typeof ts === 'number' ? ts : ts && typeof ts.toMillis === 'function' ? ts.toMillis() : null);
const latest = (snap, field, n = 10) => snap.docs
  .map((d) => ({ d, at: ms(d.get(field)) || 0 }))
  .sort((a, b) => b.at - a.at)
  .slice(0, n);

async function userView(uid) {
  const [u, doc, badge, prov, shop, state, ambs, reqs, ordersMine, visitsMine, visitsNursed, apptMine, apptToMe, tickets, reportsAgainst, homeStats, banks] = await Promise.all([
    authUser(uid),
    db.doc(`users/${uid}`).get(),
    db.doc(`badges/${uid}`).get(),
    db.doc(`providers/${uid}`).get(),
    db.doc(`pharmacies/${uid}`).get(),
    db.doc(`serverState/${uid}`).get(),
    db.collection('ambulances').where('ownerUid', '==', uid).limit(10).get(),
    db.collection('requests').where('requesterUid', '==', uid).limit(50).get(),
    db.collection('medOrders').where('patientUid', '==', uid).limit(50).get(),
    db.collection('homeVisits').where('patientUid', '==', uid).limit(50).get(),
    db.collection('homeVisits').where('nurseUid', '==', uid).limit(50).get(),
    db.collection('appointments').where('patientUid', '==', uid).limit(50).get(),
    db.collection('appointments').where('providerUid', '==', uid).limit(50).get(),
    db.collection('tickets').where('uid', '==', uid).limit(20).get(),
    db.collection('modReports').where('reportedUid', '==', uid).limit(50).get(),
    db.doc(`homeStats/${uid}`).get(),
    db.collection('bloodBanks').where('ownerUid', '==', uid).limit(5).get(),
  ]);
  if (!u && !doc.exists) return null;
  const st = (k) => (state.exists ? state.get(k) : undefined);
  const noShowActive = (st('medNoShow') || 0) >= 3 && Date.now() - (st('medNoShowAt') || 0) < 90 * DAY;
  const created = u ? Date.parse(u.metadata.creationTime || '') || null : null;
  const seen = u ? Math.max(Date.parse(u.metadata.lastRefreshTime || '') || 0, Date.parse(u.metadata.lastSignInTime || '') || 0) || null : null;
  const email = u ? (u.email || '').toLowerCase() : '';
  return {
    ...brief(u, doc),
    createdAt: created,
    lastSeenAt: seen,
    lastDonationMillis: doc.exists ? doc.get('lastDonationMillis') ?? null : null,
    notify: doc.exists ? doc.get('notify') !== false : null,
    phoneVerified: !!st('verifiedPhone'),
    // শেষ রক্তদানের তারিখ বদলের ইতিহাস (সবশেষটা আগে)
    donationDateLog: (st('donationDateLog') || []).slice().reverse(),
    isAdmin: email ? await isAdmin({ email, email_verified: true }) : false,
    isOwner: email ? adminEmails().includes(email) : false,
    badge: badge.exists ? { type: badge.get('type'), status: badge.get('status'), regNo: badge.get('regNo') || '', workplace: badge.get('workplace') || '' } : null,
    provider: prov.exists ? {
      listed: K.isListed(prov.data()),
      homeVisit: !!(prov.get('homeVisit') && prov.get('homeVisit').enabled),
      homeVisitBlocked: prov.get('homeVisitBlocked') === true,
      homePhone: K.fixPhone(prov.get('homeVisit.phone')),
      homeCalls: (homeStats.exists && homeStats.get('total')) || 0,
      chambers: K.chambersOf(prov.data()).length,
      suspended: prov.get('adminSuspended') === true,
      suspendReason: prov.get('suspendReason') || '',
    } : null,
    pharmacy: shop.exists ? { id: shop.id, name: shop.get('name'), status: shop.get('status'), phone: K.fixPhone(shop.get('phone')), district: shop.get('district') || '' } : null,
    bloodBanks: banks.docs.map((d) => ({ id: d.id, name: d.get('name'), status: d.get('status'), phone: K.fixPhone(d.get('phone')), district: d.get('district') || '' })),
    ambulances: ambs.docs.map((d) => ({ id: d.id, name: d.get('name'), status: d.get('status'), vehicleNo: d.get('vehicleNo') || '', phone: K.fixPhone(d.get('phone')) })),
    blocks: {
      homeVisitBanned: st('homeVisitBanned') === true,
      homeVisitBlocked: prov.exists && prov.get('homeVisitBlocked') === true,
      medNoShow: st('medNoShow') || 0,
      medBlocked: noShowActive,
    },
    activity: {
      requests: latest(reqs, 'createdAtMillis').map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), group: d.get('patientGroup'), hospital: d.get('hospital') || '', place: d.get('placeName') || '' })),
      orders: latest(ordersMine, 'createdAt').map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), shop: d.get('currentName') || '', items: (d.get('items') || []).length })),
      visits: latest({ docs: [...visitsMine.docs, ...visitsNursed.docs] }, 'createdAt').map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), role: d.get('nurseUid') === uid ? 'nurse' : 'patient', services: d.get('services') || [] })),
      appointments: latest({ docs: [...apptMine.docs, ...apptToMe.docs] }, 'createdAt').map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), role: d.get('providerUid') === uid ? 'provider' : 'patient', date: d.get('date') || '' })),
      tickets: latest(tickets, 'updatedAt', 20).map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), category: d.get('category') })),
      reportsAgainst: reportsAgainst.size,
    },
  };
}

const actions = {
  /** অ্যানালিটিক্স: ৩০ মিনিট মনে রাখা হয় (সব অ্যাডমিন একই হিসাব দেখেন), fresh দিলে নতুন করে */
  async admin_stats(req, user) {
    await requireAdmin(req, user);
    const ref = db.doc('serverState/_adminStats');
    const now = Date.now();
    if (req.body?.fresh !== true) {
      const s = await ref.get();
      if (s.exists && now - (s.get('at') || 0) < STATS_TTL && s.get('data')) return JSON.parse(s.get('data'));
    }
    const data = await buildStats(now);
    await ref.set({ at: now, data: JSON.stringify(data) }).catch((e) => console.error('stats cache write failed', e.message));
    return data;
  },

  /** ইমেইল, ফোন বা uid দিয়ে ব্যবহারকারী খোঁজা */
  async admin_user_find(req, user) {
    await requireAdmin(req, user);
    const q = String(req.body?.q || '').trim();
    if (q.length < 3 || q.length > 200) throw new HttpError(400, C.tr(req, 'Enter an email, phone number or user ID', 'ইমেইল, ফোন নম্বর বা ইউজার আইডি দিন'));
    const found = new Map();
    if (q.includes('@')) {
      try {
        const u = await admin.auth().getUserByEmail(q.toLowerCase());
        found.set(u.uid, u);
      } catch (e) {
        if (e.code !== 'auth/user-not-found' && e.code !== 'auth/invalid-email') throw e;
      }
    } else if (phoneVariants(q).length) {
      const snap = await db.collection('users').where('phone', 'in', phoneVariants(q)).limit(10).get();
      snap.docs.forEach((d) => found.set(d.id, null));
      // ফার্মেসি আর অ্যাম্বুলেন্সের নম্বর দিয়েও
      const [ph, am] = await Promise.all([
        // পুরোনো ভুল রূপ ("001…") সহ
        db.collection('pharmacies').where('phone', 'in', [K.localBd(K.normalizeBd(q)), `0${K.localBd(K.normalizeBd(q))}`]).limit(10).get(),
        db.collection('ambulances').where('phone', 'in', [K.localBd(K.normalizeBd(q)), `0${K.localBd(K.normalizeBd(q))}`]).limit(10).get(),
      ]);
      ph.docs.forEach((d) => found.set(d.id, null));
      am.docs.forEach((d) => found.set(d.get('ownerUid'), null));
    } else if (K.contactPhone(q)) {
      // ল্যান্ডলাইন/হটলাইন: অ্যাম্বুলেন্স আর ব্লাড ব্যাংকের নম্বর
      const [am, bb] = await Promise.all(['ambulances', 'bloodBanks'].map((c) => db.collection(c).where('phone', '==', K.contactPhone(q)).limit(10).get()));
      [...am.docs, ...bb.docs].forEach((d) => d.get('ownerUid') && found.set(d.get('ownerUid'), null));
    } else if (R.isValidId(q)) {
      found.set(q, null);
    }
    const items = [];
    for (const [uid, u0] of [...found.entries()].slice(0, 10)) {
      if (!R.isValidId(uid)) continue;
      const [u, doc] = await Promise.all([u0 ? Promise.resolve(u0) : authUser(uid), db.doc(`users/${uid}`).get()]);
      if (u || doc.exists) items.push(brief(u, doc));
    }
    return { items };
  },

  async admin_user(req, user) {
    await requireAdmin(req, user);
    const uid = req.body?.uid;
    if (!R.isValidId(uid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const v = await userView(uid);
    if (!v) throw new HttpError(404, C.tr(req, 'User not found', 'ব্যবহারকারী পাওয়া যায়নি'));
    return { user: v };
  },

  /** আপিলের সমাধান: নিষেধাজ্ঞা তোলা/দেওয়া, অ্যাকাউন্ট বন্ধ/খোলা */
  async admin_user_action(req, user) {
    await requireAdmin(req, user);
    const { uid, op } = req.body || {};
    if (!R.isValidId(uid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    // অ্যাডমিন বা মালিকের ওপর ব্যবস্থা: শুধু মালিক, আর মালিকের ওপর কেউ না
    const target = await authUser(uid);
    const targetEmail = target ? (target.email || '').toLowerCase() : '';
    if (targetEmail && adminEmails().includes(targetEmail) && uid !== user.uid) {
      throw new HttpError(403, C.tr(req, "Actions on the owner's account aren't allowed", 'মালিকের অ্যাকাউন্টে এই কাজ করা যায় না'));
    }
    if (targetEmail && !isOwner(user) && (await isAdmin({ email: targetEmail, email_verified: true }))) {
      throw new HttpError(403, C.tr(req, 'Only the owner can act on an admin', 'অ্যাডমিনের ওপর শুধু মালিক ব্যবস্থা নিতে পারেন'));
    }
    const stateRef = db.doc(`serverState/${uid}`);
    switch (op) {
      case 'homevisit_unban':
        await Promise.all([
          stateRef.set({ homeVisitBanned: FieldValue.delete() }, { merge: true }),
          db.doc(`providers/${uid}`).get().then((p) => p.exists && p.ref.update({ homeVisitBlocked: FieldValue.delete() })),
          // আগের অ্যাকাউন্ট থেকে থেকে যাওয়া (ইমেইলের) বাসায় সেবার নিষেধাজ্ঞাও তুলে নেওয়া
          (async () => {
            const k = require('./sanctions').emailKey(targetEmail);
            if (k) await db.doc(`sanctions/${k}`).set({ homeCare: false }, { merge: true });
          })(),
        ]);
        break;
      // রিপোর্টের পর বন্ধ করা ডাক্তার/নার্সের তালিকা আবার চালু (আপিল মঞ্জুর)
      case 'provider_unsuspend':
        await require('./apptcancel').liftProvider(uid);
        break;
      // সাময়িক বন্ধ: চেম্বার আর বাসায় সেবা তালিকা থেকে সরে, সামনের অ্যাপয়েন্টমেন্ট বাতিল; "আবার চালু" দিয়ে ফেরে
      case 'provider_suspend':
        await require('./apptcancel').suspendProvider(uid, 'admin');
        break;
      // চেম্বার মুছে ফেলা (ভুল/ভুয়া জায়গা): সব চেম্বার সরে, সামনের অ্যাপয়েন্টমেন্ট বাতিল; বাসায় সেবা থাকে
      case 'provider_chambers_remove': {
        const pRef = db.doc(`providers/${uid}`);
        const p = await pRef.get();
        if (p.exists) {
          await pRef.update({ chambers: [], chamber: FieldValue.delete(), listed: false, appointments: false, active: false });
          await require('./apptcancel').cancelStale(uid, () => false, ['The chamber was removed', 'চেম্বারটা সরানো হয়েছে']);
          await require('./requestend').pushUsers([uid], (u, lang) => ({
            title: R.L(lang, 'Your chambers were removed by the Lifolo team', 'Lifolo টিম আপনার চেম্বার সরিয়ে দিয়েছে'),
            body: String(req.body?.reason || '').trim().slice(0, 200),
            data: { type: 'appointment', role: 'provider' },
          }));
        }
        break;
      }
      case 'homecare_ban':
      case 'homevisit_ban_nurse': // আগের নাম
        await require('./homecare').blockHomeCare(uid);
        break;
      case 'med_unblock':
        await stateRef.set({ medNoShow: 0, medNoShowAt: FieldValue.delete() }, { merge: true });
        break;
      case 'disable':
      case 'enable': {
        if (!target) throw new HttpError(404, C.tr(req, 'User not found', 'ব্যবহারকারী পাওয়া যায়নি'));
        if (target.uid === user.uid) throw new HttpError(400, C.tr(req, "You can't change your own account", 'নিজের অ্যাকাউন্টে এটা করা যায় না'));
        if (op === 'disable') {
          await admin.auth().updateUser(uid, { disabled: true });
          // কারণ — লগইনের চেষ্টায় আর অ্যাপে ব্যবহারকারী দেখেন
          await stateRef.set({ disabledReason: String(req.body?.reason || '').trim().slice(0, 200), disabledAt: Date.now() }, { merge: true });
          // চালু লগইন বাতিল: নতুন টোকেন আর পাবে না, পুরোনোটা সর্বোচ্চ ১ ঘণ্টা
          await admin.auth().revokeRefreshTokens(uid);
          await db.doc(`users/${uid}`).set({ fcmToken: FieldValue.delete(), fcmTokens: FieldValue.delete() }, { merge: true }).catch(() => {});
          // ডাক্তার/নার্স হলে: তালিকা আর বাসায় সেবা বন্ধ, সামনের অ্যাপয়েন্টমেন্ট বাতিল করে রোগীদের জানানো
          await require('./apptcancel').suspendProvider(uid, 'account');
        } else {
          await admin.auth().updateUser(uid, { disabled: false });
          await stateRef.set({ disabledReason: FieldValue.delete(), disabledAt: FieldValue.delete() }, { merge: true });
          // চেম্বারের তালিকা আগের মতো (বাসায় সেবা নিজে আবার চালু করবেন); রিপোর্টের কারণে বন্ধ থাকলে সেটা আলাদা
          const p = await db.doc(`providers/${uid}`).get();
          if (p.exists && p.get('suspendReason') === 'account') await require('./apptcancel').liftProvider(uid);
        }
        break;
      }
      default:
        throw new HttpError(400, C.tr(req, 'Unknown action', 'অজানা কাজ'));
    }
    return { ok: true };
  },

  /** কাজের ইতিহাস: কোন অ্যাডমিন কবে কী করেছেন */
  async admin_log(req, user) {
    await requireAdmin(req, user);
    const before = Number.isInteger(req.body?.before) ? req.body.before : null;
    let q = db.collection('adminLog').orderBy('at', 'desc').limit(100);
    if (before) q = q.where('at', '<', before);
    const snap = await q.get();
    return { items: snap.docs.map((d) => ({ id: d.id, ...d.data() })) };
  },
};

// কোন কাজগুলো ইতিহাসে লেখা হবে, আর অনুরোধ থেকে কী লিখে রাখা হবে
const LOGGED = {
  admin_badge_resolve: (b) => ({ target: `badges/${b.uid}`, detail: b.status || (b.approve === true ? 'approved' : 'rejected') }),
  admin_pharmacy_resolve: (b) => ({ target: `pharmacies/${b.id}`, detail: [b.status || (b.approve === true ? 'approved' : 'rejected'), b.reason].filter(Boolean).join(' · ') }),
  admin_bloodbank_resolve: (b) => ({ target: `bloodBanks/${b.id}`, detail: [b.status || (b.approve === true ? 'approved' : 'rejected'), b.reason].filter(Boolean).join(' · ') }),
  admin_emergency_create: (b) => ({ target: 'emergencies', detail: `${b.hospitalName || b.hospitalId || ''}, ${b.district || ''}` }),
  admin_emergency_more: (b) => ({ target: `emergencies/${b.id}`, detail: 'more' }),
  admin_emergency_group: (b) => ({ target: `emergencies/${b.id}`, detail: `${b.group} ${b.closed === true ? 'closed' : 'opened'}` }),
  admin_emergency_close: (b) => ({ target: `emergencies/${b.id}`, detail: 'closed' }),
  admin_bloodbank_add: (b) => ({ target: 'bloodBanks', detail: `add ${b.name || ''}` }),
  admin_bloodbank_edit: (b) => ({ target: `bloodBanks/${b.id}`, detail: `edit ${b.name || ''}` }),
  admin_bloodbank_delete: (b) => ({ target: `bloodBanks/${b.id}`, detail: 'deleted' }),
  admin_ambulance_resolve: (b) => ({ target: `ambulances/${b.id}`, detail: [b.status || (b.approve === true ? 'approved' : 'rejected'), b.reason].filter(Boolean).join(' · ') }),
  admin_hospital_resolve: (b) => ({ target: `hospitalRequests/${b.id}`, detail: b.op + (b.targetId ? ` → ${b.targetId}` : '') }),
  // অ্যাপ থেকে অ্যাডমিন অন্যের প্রশ্ন/উত্তর/টিপস মুছলে (কী মুছলেন তার শুরুটুকু)
  admin_delete_post: (b) => ({ target: b.path, detail: `deleted: ${String(b.text || '').slice(0, 120)}` }),
  admin_report_resolve: (b) => ({ target: b.targetPath, detail: b.remove === true ? 'removed' : 'kept' }),
  admin_catalog_set: (b) => ({ target: `config/catalog/${b.kind}/${b.type}`, detail: `${(b.items || []).length} items` }),
  admin_ticket_reply: (b) => ({ target: `tickets/${b.id}`, detail: b.close === true ? (b.text ? 'replied+closed' : 'closed') : 'replied' }),
  // কোন অ্যাডমিন কার কোন ভাগ দেখলেন
  admin_user_section: (b) => ({ target: `users/${b.uid}`, detail: `viewed ${b.section}` }),
  admin_verify_docs: (b) => ({ target: `${b.kind}/${b.id}`, detail: 'viewed documents' }),
  admin_request_docs: (b) => ({ target: `${b.kind}/${b.id}`, detail: `asked for documents${b.note ? `: ${b.note}` : ''}` }),
  admin_user_action: (b) => ({ target: `users/${b.uid}`, detail: [b.op, b.reason].filter(Boolean).join(' · ') }),
  admin_add: (b) => ({ target: 'config/admins', detail: `add ${b.email}` }),
  admin_remove: (b) => ({ target: 'config/admins', detail: `remove ${b.email}` }),
  correction_resolve: (b) => ({ target: `correctionRequests/${b.id}`, detail: b.approve === true ? 'approved' : 'rejected' }),
};

/** সফল অ্যাডমিন কাজ লিখে রাখা (ব্যর্থ হলেও মূল কাজ আটকায় না) */
async function logAdmin(action, user, body) {
  const fn = LOGGED[action];
  if (!fn) return;
  try {
    const { target, detail } = fn(body || {});
    await db.collection('adminLog').add({
      at: Date.now(),
      action,
      by: (user.email || '').toLowerCase(),
      byUid: user.uid,
      via: user.webSession ? 'web' : 'app',
      target: String(target || '').slice(0, 300),
      detail: String(detail || '').slice(0, 300),
    });
  } catch (e) {
    console.error('admin log failed', e.message);
  }
}

module.exports = { actions, logAdmin, buildStats, dhakaMidnight, phoneVariants };
