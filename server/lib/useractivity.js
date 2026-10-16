// অ্যাডমিন প্যানেল: একজন ব্যবহারকারী অ্যাপে যা যা করেছেন — অ্যাপের ভাগ ধরে (রক্ত, আলোচনা, সেবা, ডাক্তার, নোটিফিকেশন, আপিল)।
// শুধু দেখা যায়, কিছু বদলানো যায় না। প্রতিটা ভাগ চাইলে তবেই পড়া হয় (বিনামূল্যের প্ল্যানের দৈনিক পড়ার সীমা বাঁচাতে)।
// কোন অ্যাডমিন কার পাতা দেখলেন তা অ্যাডমিন লগে থাকে (admin_user_section)।
const { db, HttpError } = require('./firebase');
const C = require('./community');
const R = require('./rules');
const K = require('./care');

const SECTIONS = ['blood', 'community', 'services', 'care', 'inbox', 'support', 'admin'];

const ms = (v) => (typeof v === 'number' ? v : v && typeof v.toMillis === 'function' ? v.toMillis() : 0);
const newest = (docs, field, n = 30) => docs
  .map((d) => ({ d, at: ms(d.get(field)) }))
  .sort((a, b) => b.at - a.at)
  .slice(0, n);

/** collection group খোঁজার জন্য Firestore-এ আলাদা সূচি লাগে; না থাকলে সেই অংশ খালি, কারণসহ */
async function tryGroup(fn) {
  try {
    return { docs: (await fn()).docs, note: '' };
  } catch (e) {
    const needIndex = /index/i.test(String(e.message || '')) || e.code === 9;
    return { docs: [], note: needIndex ? 'index' : String(e.message || '').slice(0, 120) };
  }
}

async function blood(uid) {
  const [u, reqs, resp, emer, corr] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.collection('requests').where('requesterUid', '==', uid).limit(50).get(),
    tryGroup(() => db.collectionGroup('responses').where('donorUid', '==', uid).limit(50).get()),
    tryGroup(() => db.collectionGroup('donors').where('uid', '==', uid).limit(30).get()),
    db.collection('correctionRequests').where('uid', '==', uid).limit(20).get(),
  ]);
  // সাড়া দেওয়া রিকোয়েস্টগুলোর হাসপাতাল/গ্রুপ
  const parents = resp.docs.map((d) => d.ref.parent.parent).filter(Boolean);
  const reqDocs = parents.length ? await db.getAll(...parents) : [];
  const byId = new Map(reqDocs.filter((s) => s.exists).map((s) => [s.id, s]));
  const emParents = emer.docs.map((d) => d.ref.parent.parent).filter(Boolean);
  const emDocs = emParents.length ? await db.getAll(...emParents) : [];
  const emById = new Map(emDocs.filter((s) => s.exists).map((s) => [s.id, s]));
  return {
    profile: u.exists ? {
      bloodGroup: u.get('bloodGroup') || '', lastDonationMillis: u.get('lastDonationMillis') ?? null, notify: u.get('notify') !== false,
      placeName: u.get('placeName') || '', available: u.get('available') !== false, devices: (u.get('fcmTokens') || []).length || (u.get('fcmToken') ? 1 : 0),
      openCommitments: Object.keys(u.get('commitments') || {}).length,
    } : null,
    requests: newest(reqs.docs, 'createdAtMillis').map(({ d, at }) => ({
      id: d.id, at, status: d.get('status'), group: d.get('patientGroup'), bags: d.get('bags') || 1, hospital: d.get('hospital') || '',
      place: d.get('placeName') || '', accepted: d.get('acceptedCount') || 0, confirmed: d.get('confirmedCount') || 0, closedBy: d.get('closedBy') || '',
    })),
    responses: newest(resp.docs, 'createdAtMillis').map(({ d, at }) => {
      const r = byId.get(d.ref.parent.parent.id);
      return {
        requestId: d.ref.parent.parent.id, at: at || ms(d.get('updatedAt')), status: d.get('status'), confirmed: d.get('confirmed') === true,
        hospital: r ? r.get('hospital') : '', group: r ? r.get('patientGroup') : '', requesterUid: r ? r.get('requesterUid') : '',
      };
    }),
    responsesNote: resp.note,
    emergencies: emer.docs.map((d) => {
      const e = emById.get(d.ref.parent.parent.id);
      return { id: d.ref.parent.parent.id, status: d.get('status'), at: d.get('comingAt') || d.get('notifiedAt') || 0, hospital: e ? e.get('hospitalName') : '' };
    }).sort((a, b) => b.at - a.at),
    emergenciesNote: emer.note,
    corrections: newest(corr.docs, 'createdAt', 20).map(({ d, at }) => ({ at, status: d.get('status'), reason: d.get('reason') || '', requestedMillis: d.get('requestedMillis') || null })),
  };
}

async function community(uid) {
  const [qs, ans, tips, reportsBy] = await Promise.all([
    db.collection('questions').where('uid', '==', uid).limit(50).get(),
    tryGroup(() => db.collectionGroup('answers').where('uid', '==', uid).limit(50).get()),
    db.collection('tips').where('uid', '==', uid).limit(50).get(),
    db.collection('modReports').where('reporterUid', '==', uid).limit(50).get(),
  ]);
  return {
    questions: newest(qs.docs, 'createdAt').map(({ d, at }) => ({ id: d.id, at, text: String(d.get('text') || '').slice(0, 300), anonymous: d.get('anonymous') === true, answers: d.get('answerCount') || 0, edited: !!d.get('editedAt') })),
    answers: newest(ans.docs, 'createdAt').map(({ d, at }) => ({ questionId: d.ref.parent.parent.id, at, text: String(d.get('text') || '').slice(0, 300), edited: !!d.get('editedAt') })),
    answersNote: ans.note,
    tips: newest(tips.docs, 'createdAt').map(({ d, at }) => ({ id: d.id, at, title: d.get('title') || '', body: String(d.get('body') || '').slice(0, 200) })),
    reportsMade: newest(reportsBy.docs, 'createdAt').map(({ d, at }) => ({ at, kind: d.get('kind'), target: d.get('targetPath'), reason: d.get('reason') || '', status: d.get('status') })),
  };
}

async function services(uid) {
  const [ambs, shop, banks, amCalls, phCalls, bbCalls, homeCalls, finds] = await Promise.all([
    db.collection('ambulances').where('ownerUid', '==', uid).limit(10).get(),
    db.doc(`pharmacies/${uid}`).get(),
    db.collection('bloodBanks').where('ownerUid', '==', uid).limit(5).get(),
    db.collection('ambulanceCalls').where('uid', '==', uid).limit(50).get(),
    db.collection('pharmacyCalls').where('uid', '==', uid).limit(50).get(),
    db.collection('bloodBankCalls').where('uid', '==', uid).limit(50).get(),
    db.collection('homeCalls').where('uid', '==', uid).limit(50).get(),
    db.collection('medFinds').where('uid', '==', uid).limit(30).get(),
  ]);
  // যাঁদের ফোন করেছেন তাঁদের নাম
  const names = async (coll, ids) => {
    const uniq = [...new Set(ids.filter((id) => R.isValidId(id)))].slice(0, 60);
    if (!uniq.length) return new Map();
    const snaps = await db.getAll(...uniq.map((id) => db.doc(`${coll}/${id}`)));
    return new Map(snaps.filter((s) => s.exists).map((s) => [s.id, s.get('name') || '']));
  };
  const [amN, phN, bbN, hcN] = await Promise.all([
    names('ambulances', amCalls.docs.map((d) => d.get('ambulanceId'))),
    names('pharmacies', phCalls.docs.map((d) => d.get('pharmacyId'))),
    names('bloodBanks', bbCalls.docs.map((d) => d.get('bankId'))),
    names('providers', homeCalls.docs.map((d) => d.get('providerUid'))),
  ]);
  const listing = (d) => ({
    id: d.id, name: d.get('name'), status: d.get('status'), phone: K.fixPhone(d.get('phone')), district: d.get('district') || '',
    statusReason: d.get('statusReason') || '', verified: !!d.get('verification'), rating: d.get('ratingCount') ? Math.round((d.get('ratingSum') / d.get('ratingCount')) * 10) / 10 : 0,
  });
  const call = (kind, map, idField) => (d) => ({ kind, at: d.get('at') || 0, firstAt: d.get('firstAt') || 0, name: map.get(d.get(idField)) || '(মুছে গেছে)', rating: d.get('rating') || 0, comment: d.get('comment') || '' });
  return {
    ambulances: ambs.docs.map((d) => ({ ...listing(d), vehicleNo: d.get('vehicleNo') || '' })),
    pharmacy: shop.exists ? { ...listing(shop), licenseNo: shop.get('licenseNo') || '' } : null,
    bloodBanks: banks.docs.map((d) => ({ ...listing(d), kind: d.get('kind') || '' })),
    calls: [
      ...amCalls.docs.map(call('ambulance', amN, 'ambulanceId')),
      ...phCalls.docs.map(call('pharmacy', phN, 'pharmacyId')),
      ...bbCalls.docs.map(call('bloodbank', bbN, 'bankId')),
      ...homeCalls.docs.map(call('homecare', hcN, 'providerUid')),
    ].sort((a, b) => b.at - a.at).slice(0, 80),
    medFinds: newest(finds.docs, 'createdAt').map(({ d, at }) => ({
      at, drug: (d.get('drug') || {}).name || '', district: d.get('district') || '', status: d.get('status'),
      asked: (d.get('notified') || []).length, have: Object.values(d.get('answers') || {}).filter((a) => a && a.have).length,
    })),
  };
}

async function care(uid) {
  const [prov, mine, toMe, homeToMe] = await Promise.all([
    db.doc(`providers/${uid}`).get(),
    db.collection('appointments').where('patientUid', '==', uid).limit(50).get(),
    db.collection('appointments').where('providerUid', '==', uid).limit(50).get(),
    db.collection('homeCalls').where('providerUid', '==', uid).limit(50).get(),
  ]);
  const appt = (role) => ({ d, at }) => ({
    at, role, status: d.get('status'), date: d.get('date') || 0, chamber: d.get('chamber') || '', serial: d.get('serial') || null,
    other: role === 'patient' ? d.get('providerName') || '' : d.get('patientName') || '',
  });
  return {
    provider: prov.exists ? {
      name: prov.get('name') || '', type: prov.get('type') || '', listed: K.isListed(prov.data()), suspended: prov.get('adminSuspended') === true,
      chambers: K.chambersOf(prov.data()).map((c) => ({ hospital: c.custom ? `${c.hospitalName} (নিজে লেখা) · ${c.address || ''}` : c.hospitalName, days: c.days || [], start: c.start || '', end: c.end || '', times: K.chamberTimes(c), appointments: !!c.appointments })),
      homeVisit: !!(prov.get('homeVisit') && prov.get('homeVisit').enabled), homePhone: K.fixPhone(prov.get('homeVisit.phone')),
    } : null,
    appointments: [...newest(mine.docs, 'createdAt', 50).map(appt('patient')), ...newest(toMe.docs, 'createdAt', 50).map(appt('provider'))].sort((a, b) => b.at - a.at),
    homeCallsReceived: homeToMe.size,
  };
}

async function inbox(uid) {
  const snap = await db.collection('inbox').where('uid', '==', uid).limit(60).get();
  return {
    items: snap.docs.map((d) => ({ at: d.get('createdAt') || 0, title: d.get('title') || '', body: d.get('body') || '', type: (d.get('data') || {}).type || '' }))
      .sort((a, b) => b.at - a.at),
  };
}

async function support(uid) {
  const snap = await db.collection('tickets').where('uid', '==', uid).limit(30).get();
  return {
    items: newest(snap.docs, 'updatedAt').map(({ d, at }) => ({ id: d.id, at, status: d.get('status'), category: d.get('category'), subject: String(((d.get('messages') || [])[0] || {}).text || '').slice(0, 200), messages: (d.get('messages') || []).length })),
  };
}

/**
 * এই মানুষের ওপর অ্যাডমিনের সব কাজ: তাঁর অ্যাকাউন্ট, ব্যাজ, আর তাঁর ফার্মেসি/অ্যাম্বুলেন্স/ব্লাড ব্যাংক/প্যাথলজির সিদ্ধান্ত।
 * (আগে শুধু users/{uid} দেখা হতো, তাই ব্যাজ বা তালিকার অনুমোদন-বাতিল এখানে আসত না)
 */
async function adminLog(uid) {
  const owned = await Promise.all(['pharmacies', 'ambulances', 'bloodBanks', 'labs'].map((c) =>
    db.collection(c).where('ownerUid', '==', uid).limit(20).get().then((s) => s.docs.map((d) => `${c}/${d.id}`)).catch(() => [])));
  const targets = [...new Set([`users/${uid}`, `badges/${uid}`, ...owned.flat()])];
  const chunks = [];
  for (let i = 0; i < targets.length; i += 30) chunks.push(targets.slice(i, i + 30));
  const snaps = await Promise.all(chunks.map((t) => db.collection('adminLog').where('target', 'in', t).limit(100).get()));
  return {
    items: snaps.flatMap((s) => s.docs)
      .map((d) => ({ at: d.get('at') || 0, action: d.get('action'), detail: d.get('detail') || '', by: d.get('by') || '', via: d.get('via') || '', target: d.get('target') || '' }))
      .sort((a, b) => b.at - a.at)
      .slice(0, 100),
  };
}

const LOADERS = { blood, community, services, care, inbox, support, admin: adminLog };

const actions = {
  /** অ্যাডমিন: একজন ব্যবহারকারীর একটা ভাগের সব কাজ (শুধু দেখা) */
  async admin_user_section(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const { uid, section } = req.body || {};
    if (!R.isValidId(uid) || !SECTIONS.includes(section)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    return { section, ...(await LOADERS[section](uid)) };
  },
};

module.exports = { actions, SECTIONS };
