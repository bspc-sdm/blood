// জরুরি রক্তের আহ্বান (দুর্ঘটনা ইত্যাদি): শুধু অ্যাডমিন ওয়েব প্যানেল থেকে দেন।
// প্রতিটা গ্রুপে যত ব্যাগ দরকার তার ৩ গুণ কাছের যোগ্য ডোনারকে আগে জানানো হয় (ধাপে ধাপে, ভিড় এড়াতে);
// ডোনার "আসছি" চাপলে গোনা হয়, যথেষ্ট হলে সেই গ্রুপ নিজে থেকে বন্ধ। শেষ হলে যারা আসছিলেন তাদের জানানো হয়।
//
// emergencies/{id}: { hospitalId, hospitalName, district, districts[], lat, lng, radiusKm, groups: { g: { need, coming, notified, closed, adminClosed } },
//                     contactPhone, note, status: active|closed, createdAt, endsAt, createdBy, waves }
// emergencies/{id}/donors/{uid}: { group, status: notified|coming|cancelled|donated|not_donated, km, notifiedAt, comingAt }
// users/{uid}.emergencyComing: { emergencyId: comingAt } — "আসছি" বলা আহ্বান; শেষে "রক্ত দিয়েছেন?" জিজ্ঞেস করতে
const { db, FieldValue, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const E = require('./emergency-rules');
const DISTRICTS = require('./districts');

const HOUR = 3600 * 1000;

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

function center(e) {
  return { lat: e.lat, lng: e.lng };
}

/** একটা ধাপ পাঠানো: খোলা গ্রুপগুলোতে আগে না জানানো কাছের যোগ্য ডোনার */
async function sendWave(id) {
  const ref = db.doc(`emergencies/${id}`);
  const s = await ref.get();
  if (!s.exists) return { sent: 0 };
  const e = s.data();
  if (!E.isLive(e)) return { sent: 0 };
  const Geo = require('./geo');
  // শুধু খোলা গ্রুপগুলোর মানুষ (donorGeo); পুরোনো প্রোফাইলে সেটা বসানো শেষ না হলে আগের মতো সবাই
  const openGroups = Object.entries(e.groups || {}).filter(([, x]) => !x.closed && x.coming < x.need).map(([g]) => g);
  if (!openGroups.length) return { sent: 0 };
  const [found, already] = await Promise.all([
    (await Geo.donorGeoReady(db))
      ? Geo.nearDonors(db, openGroups, e.lat, e.lng, e.radiusKm)
      : Geo.near(db.collection('users'), 'geohash', e.lat, e.lng, e.radiusKm),
    ref.collection('donors').select().get(),
  ]);
  const usersSnap = { docs: found.map((x) => x.doc) };
  const users = usersSnap.docs.map((d) => ({ uid: d.id, ...d.data() }));
  const wave = E.pickWave(users, center(e), e.groups, e.radiusKm, new Set(already.docs.map((d) => d.id)));
  if (!wave.length) return { sent: 0 };
  const now = Date.now();
  // আগে লিখে রাখা, যাতে দুবার "আরও জানান" চাপলেও একজন দুবার না পান
  for (let i = 0; i < wave.length; i += 400) {
    const b = db.batch();
    wave.slice(i, i + 400).forEach((u) => b.set(ref.collection('donors').doc(u.uid), { uid: u.uid, group: u.group, status: 'notified', km: Math.round(u.km * 10) / 10, notifiedAt: now }));
    await b.commit();
  }
  const perGroup = {};
  wave.forEach((u) => { perGroup[u.group] = (perGroup[u.group] || 0) + 1; });
  await db.runTransaction(async (t) => {
    const cur = await t.get(ref);
    const groups = cur.get('groups');
    Object.entries(perGroup).forEach(([g, n]) => { if (groups[g]) groups[g].notified = (groups[g].notified || 0) + n; });
    t.update(ref, { groups, waves: (cur.get('waves') || 0) + 1, lastWaveAt: now });
  });
  const sent = await sendPush(wave.map((u) => ({
    uid: u.uid,
    tokens: u.tokens,
    title: R.L(u.lang, `Emergency: ${u.group} blood needed`, `জরুরি: ${u.group} রক্ত দরকার`),
    body: R.L(u.lang, `${e.hospitalName} · ${R.formatKm(u.km, 'en')} · Lifolo team appeal — open to say you're coming`,
      `${e.hospitalName} · ${R.formatKm(u.km, 'bn')} · Lifolo টিমের আহ্বান — খুলে "আসছি" চাপুন`),
    data: { type: 'emergency', emergencyId: id },
  })));
  return { sent, notified: wave.length };
}

/** অ্যাপে দেখানোর রূপ (নিজের অবস্থা সহ) */
function view(d, me, mine) {
  const e = d.data();
  const groups = {};
  Object.entries(e.groups || {}).forEach(([g, x]) => {
    groups[g] = { need: x.need, coming: x.coming || 0, full: !!x.closed || (x.coming || 0) >= x.need };
  });
  const myGroup = me && me.bloodGroup;
  const g = myGroup && groups[myGroup];
  return {
    id: d.id,
    hospitalName: e.hospitalName,
    district: e.district,
    contactPhone: K.fixPhone(e.contactPhone),
    note: e.note || '',
    groups,
    createdAt: e.createdAt,
    endsAt: e.endsAt,
    myGroup: myGroup || '',
    myStatus: mine && mine.exists ? mine.get('status') : '',
    // আমার গ্রুপ দরকার, এখনো ভরেনি, আর আমি এখন রক্ত দিতে পারি
    canCome: !!(g && !g.full && me && R.isEligibleDonor(me)),
    eligible: !!(me && R.isEligibleDonor(me)),
  };
}

async function notifyComing(id, e, en, bn, bodyEn = '', bodyBn = '') {
  const coming = await db.collection(`emergencies/${id}/donors`).where('status', '==', 'coming').get();
  if (coming.empty) return;
  const users = await db.getAll(...coming.docs.map((d) => db.doc(`users/${d.id}`)));
  await sendPush(users.filter((u) => u.exists).map((u) => ({
    uid: u.id,
    tokens: require('./firebase').tokensOf(u),
    title: R.L(u.get('lang'), en, bn),
    body: [e.hospitalName, R.L(u.get('lang'), bodyEn, bodyBn)].filter(Boolean).join(' · '),
    data: { type: 'emergency', emergencyId: id },
  })));
}

const ENDED_TEXT = [
  'Emergency appeal ended', 'জরুরি আহ্বান শেষ',
  'If you haven\'t left, no need to come. If you donated, tell us in the app. Thank you!',
  'এখনো রওনা না দিলে আর আসার দরকার নেই। রক্ত দিয়ে থাকলে অ্যাপে জানান। ধন্যবাদ!',
];
const ASK_DAYS = 7;

/** শেষ হওয়া আহ্বান, যেখানে "আসছি" বলেছিলেন কিন্তু রক্ত দিয়েছেন কি না জানাননি (৭ দিন পর্যন্ত) */
async function askDonated(uid, me) {
  const coming = (me && me.exists && me.get('emergencyComing')) || {};
  const ids = Object.keys(coming).filter((k) => R.isValidId(k)).slice(0, 10);
  if (!ids.length) return [];
  const now = Date.now();
  const docs = await db.getAll(...ids.map((k) => db.doc(`emergencies/${k}`)));
  const stale = [];
  const ask = [];
  docs.forEach((d, i) => {
    if (!d.exists) { stale.push(ids[i]); return; }
    const e = d.data();
    const endedAt = e.status === 'closed' ? e.closedAt || e.endsAt : e.endsAt;
    if (E.isLive(e, now)) return;
    if (now - (endedAt || 0) > ASK_DAYS * 24 * HOUR) { stale.push(ids[i]); return; }
    ask.push({ id: d.id, hospitalName: e.hospitalName, endedAt: endedAt || 0 });
  });
  if (stale.length) {
    await db.doc(`users/${uid}`).update(Object.fromEntries(stale.map((k) => [`emergencyComing.${k}`, FieldValue.delete()]))).catch(() => {});
  }
  return ask;
}

/** সময় পেরোনো আহ্বান বন্ধ করে যারা আসছিলেন তাদের জানানো (দৈনিক ক্রন) */
async function closeExpired(now = Date.now()) {
  const snap = await db.collection('emergencies').where('status', '==', 'active').limit(50).get();
  let n = 0;
  for (const d of snap.docs.filter((x) => (x.get('endsAt') || 0) <= now)) {
    // দুটো অনুরোধ একসাথে এলে একজনই বন্ধ করে জানায়
    const won = await db.runTransaction(async (t) => {
      const cur = await t.get(d.ref);
      if (!cur.exists || cur.get('status') !== 'active') return false;
      t.update(d.ref, { status: 'closed', closedAt: d.get('endsAt') || now, closedBy: 'expired' });
      return true;
    });
    if (!won) continue;
    await notifyComing(d.id, d.data(), ...ENDED_TEXT).catch((e) => console.error('emergency end push failed', e));
    n++;
  }
  return n;
}

const actions = {
  /** নিজের জেলার (বা যাকে জানানো হয়েছে তার) চালু আহ্বান */
  async emergencies_active(req, user) {
    const [snap, me] = await Promise.all([
      db.collection('emergencies').where('status', '==', 'active').limit(20).get(),
      db.doc(`users/${user.uid}`).get(),
    ]);
    const live = snap.docs.filter((d) => E.isLive(d.data()));
    // সময় পেরোনো আহ্বান কেউ দেখতে এলেই বন্ধ (ক্রনের অপেক্ষা নয়) — "আসছি" বলা ডোনাররা সাথে সাথে জানেন
    if (snap.docs.some((d) => !E.isLive(d.data()))) await closeExpired().catch((e) => console.error('emergency close failed', e));
    const ask = await askDonated(user.uid, me);
    if (!live.length) return { items: [], ask };
    const mine = await db.getAll(...live.map((d) => db.doc(`emergencies/${d.id}/donors/${user.uid}`)));
    const meData = me.exists ? me.data() : null;
    const items = live
      .map((d, i) => ({ d, m: mine[i] }))
      .filter(({ d, m }) => m.exists || (meData && (d.get('districts') || []).includes(meData.placeName)))
      .map(({ d, m }) => view(d, meData, m));
    items.sort((a, b) => b.createdAt - a.createdAt);
    return { items, ask };
  },

  /** "আসছি" বলা ডোনার: রক্ত দিয়েছেন কি না — দিলে শেষ রক্তদানের তারিখ আজ (শুধু সামনে) */
  async emergency_donated(req, user) {
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const donated = req.body?.donated === true;
    const eref = db.doc(`emergencies/${id}`);
    const dref = db.doc(`emergencies/${id}/donors/${user.uid}`);
    const uref = db.doc(`users/${user.uid}`);
    await db.runTransaction(async (t) => {
      const [e, d, u] = await Promise.all([t.get(eref), t.get(dref), t.get(uref)]);
      // চলমান আহ্বানে "দিইনি" নয় — তখন "আসতে পারছি না" (গোনা কমে, অন্য কেউ আসতে পারেন)
      if (!donated && e.exists && E.isLive(e.data())) {
        throw new HttpError(400, C.tr(req, 'Tap "Can\'t come" instead', '"আসতে পারছি না" চাপুন'));
      }
      if (!d.exists || !['coming', 'donated', 'not_donated'].includes(d.get('status'))) {
        throw new HttpError(400, C.tr(req, 'You did not say you were coming to this appeal', 'এই আহ্বানে আপনি "আসছি" বলেননি'));
      }
      // একবারই উত্তর (বারবার "দিয়েছি" চেপে তারিখ সামনে ঠেলা যায় না)
      if (d.get('status') !== 'coming') {
        if (u.exists) t.update(uref, { [`emergencyComing.${id}`]: FieldValue.delete() });
        return;
      }
      t.update(dref, { status: donated ? 'donated' : 'not_donated', answeredAt: Date.now() });
      if (u.exists) {
        // রক্তদানের দিন: আহ্বানের দিন (পরে জানালেও), আজকের পরে নয়
        const today = R.todayDhakaUtcMidnight(Math.min(Date.now(), Math.max(d.get('comingAt') || 0, (e.exists && e.get('createdAt')) || 0) || Date.now()));
        const last = u.get('lastDonationMillis');
        t.update(uref, {
          [`emergencyComing.${id}`]: FieldValue.delete(),
          ...(donated && (last == null || last < today) ? { lastDonationMillis: today } : {}),
        });
      }
    });
    return { ok: true };
  },

  /** ডোনার: আসছি / আসতে পারছি না */
  async emergency_respond(req, user) {
    const { id, op } = req.body || {};
    if (!R.isValidId(id) || !['coming', 'cancel'].includes(op)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`emergencies/${id}`);
    const dref = ref.collection('donors').doc(user.uid);
    const me = await db.doc(`users/${user.uid}`).get();
    const g = me.exists ? me.get('bloodGroup') : null;
    await db.runTransaction(async (t) => {
      const [s, d] = await Promise.all([t.get(ref), t.get(dref)]);
      if (!s.exists || !E.isLive(s.data())) throw new HttpError(400, C.tr(req, 'This appeal has ended', 'এই আহ্বান শেষ হয়ে গেছে'));
      const groups = s.get('groups') || {};
      const cur = d.exists ? d.get('status') : null;
      if (op === 'coming') {
        if (cur === 'coming' || cur === 'donated') return;
        const x = g && groups[g];
        if (!x) throw new HttpError(400, C.tr(req, 'Your blood group is not needed here', 'এখানে আপনার গ্রুপের রক্ত দরকার নেই'));
        if (!R.isEligibleDonor(me.data())) {
          throw new HttpError(400, C.tr(req, 'You can\'t donate right now (age, weight or last donation)', 'এখন আপনি রক্ত দিতে পারবেন না (বয়স, ওজন বা শেষ রক্তদানের সময়)'));
        }
        if (x.closed || (x.coming || 0) >= x.need) throw new HttpError(409, C.tr(req, 'Enough donors are already coming for your group — thank you!', 'আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!'));
        x.coming = (x.coming || 0) + 1;
        // যথেষ্ট হলে নিজে থেকে বন্ধ (কেউ বাতিল করলে আবার খোলে)
        if (x.coming >= x.need) x.closed = true;
        t.set(dref, { uid: user.uid, group: g, status: 'coming', comingAt: Date.now(), ...(d.exists ? {} : { notifiedAt: null }) }, { merge: true });
        t.update(db.doc(`users/${user.uid}`), { [`emergencyComing.${id}`]: Date.now() });
      } else {
        if (cur !== 'coming') return;
        const dg = d.get('group');
        const x = groups[dg];
        if (x) {
          x.coming = Math.max(0, (x.coming || 0) - 1);
          if (!x.adminClosed && x.coming < x.need) x.closed = false;
        }
        t.update(dref, { status: 'cancelled', cancelledAt: Date.now() });
        t.update(db.doc(`users/${user.uid}`), { [`emergencyComing.${id}`]: FieldValue.delete() });
      }
      t.update(ref, { groups });
    });
    return { ok: true };
  },

  // ---------- অ্যাডমিন ----------
  /** প্যানেলের ফর্মে হাসপাতাল বাছাইয়ের জন্য */
  async admin_hospitals(req, user) {
    await requireAdmin(req, user);
    const { district } = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const list = (await require('./hospitals').actions.hospitals({ ...req, body: { district } })).items;
    return { items: list.map((h) => ({ id: h.id, name: h.nameBn || h.nameEn, nameEn: h.nameEn })) };
  },

  async admin_emergency_create(req, user) {
    await requireAdmin(req, user);
    const b = req.body || {};
    const err = (en, bn) => new HttpError(400, C.tr(req, en, bn));
    if (b.confirmed !== true) throw err('Confirm that you have spoken to the hospital blood bank', 'হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছেন — টিক দিন');
    const dist = DISTRICTS.find((d) => d.bn === b.district);
    if (!dist) throw err('Choose a district', 'জেলা বেছে নিন');
    let hospitalName = C.cleanText(b.hospitalName, 120);
    let hospitalId = null;
    if (typeof b.hospitalId === 'string' && b.hospitalId) {
      const h = await require('./hospitals').findHospital(b.hospitalId);
      if (h) {
        hospitalId = h.id;
        hospitalName = h.nameBn || h.nameEn;
      }
    }
    if (!hospitalName) throw err('Choose or write the hospital', 'হাসপাতাল বেছে নিন বা লিখুন');
    const groups = E.parseGroups(b.groups);
    if (!Object.keys(groups).length) throw err('Enter bags needed for at least one group', 'অন্তত একটা গ্রুপে কত ব্যাগ লাগবে দিন');
    const radiusKm = E.RADII.includes(b.radiusKm) ? b.radiusKm : 25;
    const hours = E.HOURS.includes(b.hours) ? b.hours : 12;
    const phone = K.normalizeBd(b.contactPhone);
    if (!phone) throw err('Enter the hospital contact mobile number', 'হাসপাতালের যোগাযোগের মোবাইল নম্বর দিন');
    // হাসপাতালের লোকেশন দিলে দূরত্ব সেখান থেকে, নইলে জেলার মাঝখান থেকে
    const hasLoc = typeof b.lat === 'number' && typeof b.lng === 'number' && b.lat > 20.3 && b.lat < 26.8 && b.lng > 87.9 && b.lng < 92.8;
    const lat = hasLoc ? b.lat : dist.lat;
    const lng = hasLoc ? b.lng : dist.lng;
    const districts = [...new Set([dist.bn, ...DISTRICTS.filter((d) => R.distanceKm(lat, lng, d.lat, d.lng) <= radiusKm + 25).map((d) => d.bn)])].slice(0, 30);
    const now = Date.now();
    const ref = await db.collection('emergencies').add({
      hospitalId, hospitalName, district: dist.bn, districts, lat, lng, exactLocation: hasLoc, radiusKm, groups,
      contactPhone: K.localBd(phone), note: C.cleanText(b.note, 300), status: 'active',
      createdAt: now, endsAt: now + hours * HOUR, createdBy: (user.email || '').toLowerCase(), waves: 0,
    });
    const r = await sendWave(ref.id);
    return { ok: true, id: ref.id, ...r };
  },

  /** আরও ডোনার জানানো (পরের ধাপ) */
  async admin_emergency_more(req, user) {
    await requireAdmin(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const r = await sendWave(id);
    return { ok: true, ...r };
  },

  /** একটা গ্রুপ বন্ধ / আবার খোলা */
  async admin_emergency_group(req, user) {
    await requireAdmin(req, user);
    const { id, group } = req.body || {};
    if (!R.isValidId(id) || !R.BLOOD_GROUPS.includes(group)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const close = req.body?.closed === true;
    const ref = db.doc(`emergencies/${id}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      const groups = s.get('groups') || {};
      if (!groups[group]) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
      groups[group].closed = close || (groups[group].coming || 0) >= groups[group].need;
      groups[group].adminClosed = close;
      t.update(ref, { groups });
    });
    return { ok: true };
  },

  /** পুরো আহ্বান শেষ: যারা আসছিলেন তাদের জানানো */
  async admin_emergency_close(req, user) {
    await requireAdmin(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`emergencies/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (s.get('status') === 'closed') return { ok: true, unchanged: true };
    await ref.update({ status: 'closed', closedAt: Date.now() });
    await notifyComing(id, s.data(), ...ENDED_TEXT);
    return { ok: true };
  },

  /** প্যানেলের তালিকা: সাম্প্রতিক ৩০টা */
  async admin_emergencies(req, user) {
    await requireAdmin(req, user);
    const snap = await db.collection('emergencies').orderBy('createdAt', 'desc').limit(30).get();
    const now = Date.now();
    return {
      items: snap.docs.map((d) => {
        const e = d.data();
        return {
          id: d.id, hospitalName: e.hospitalName, district: e.district, radiusKm: e.radiusKm, groups: e.groups,
          contactPhone: K.fixPhone(e.contactPhone), note: e.note || '', live: E.isLive(e, now), status: e.status,
          createdAt: e.createdAt, endsAt: e.endsAt, createdBy: e.createdBy || '', waves: e.waves || 0, exactLocation: !!e.exactLocation,
        };
      }),
    };
  },
};

module.exports = { actions, sendWave, closeExpired };
