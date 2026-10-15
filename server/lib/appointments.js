// অ্যাপয়েন্টমেন্ট: ব্যাজধারী ডাক্তার/নার্স চেম্বারের তথ্য দেন, রোগী অনুরোধ পাঠান, ডাক্তার/নার্স নিশ্চিত করেন।
// টাকা নেওয়া নেই। নিশ্চিত হলে দুজনেই একে অপরের ফোন নম্বর দেখেন।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const DISTRICTS = require('./districts');
const { approvedBadge, useQuota, userName } = require('./community-db');
const K = require('./care');

const PROVIDER_TYPES = ['doctor', 'nurse'];
const DAY_MS = 24 * 60 * 60 * 1000;

function provider(d) {
  const x = d.data();
  const chambers = K.chambersOf(x);
  const first = chambers[0] || {};
  const count = x.ratingCount || 0;
  return {
    uid: d.id,
    type: x.type,
    name: x.name,
    specialties: x.specialties || [],
    degrees: x.degrees || '',
    gender: x.gender || '',
    listed: K.isListed(x),
    appointments: K.takesAppointments(x),
    chambers,
    homeVisit: x.type === 'nurse' && x.homeVisit ? x.homeVisit : null,
    rating: count ? Math.round((x.ratingSum / count) * 10) / 10 : 0,
    ratingCount: count,
    // আগের ভার্সনের অ্যাপের জন্য
    specialty: x.specialty || (x.specialties || []).map((s) => s.replace(/_/g, ' ')).join(', '),
    chamber: first.hospitalName || '',
    district: first.district || '',
    schedule: first.schedule || '',
    fee: first.fee || '',
    active: K.isListed(x) && K.takesAppointments(x),
  };
}

async function push(uid, en, bn, body, data) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    const token = u.get('fcmToken');
    if (token) await sendPush([{ uid, token, title: R.L(u.get('lang'), en, bn), body, data }]);
  } catch (e) {
    console.error('appointment push failed', e);
  }
}

function dateLabel(millis) {
  const d = new Date(millis);
  return `${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`;
}

/** আগের ভার্সনের অ্যাপ (এক চেম্বার, লেখা দিয়ে) */
async function legacySave(req, user, badge) {
  const b = req.body || {};
  const chamber = C.cleanText(b.chamber, 120);
  const schedule = C.cleanText(b.schedule, 120);
  if (!chamber) throw new HttpError(400, C.tr(req, 'Enter your chamber / hospital', 'চেম্বার / হাসপাতালের নাম দিন'));
  if (!schedule) throw new HttpError(400, C.tr(req, 'Enter your days and time', 'কোন দিন ও কখন বসেন লিখুন'));
  if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
  const cur = await db.doc(`providers/${user.uid}`).get();
  if (cur.exists && Array.isArray(cur.get('chambers'))) {
    throw new HttpError(409, C.tr(req, 'Please update the app to edit your chambers', 'চেম্বার বদলাতে অ্যাপ আপডেট করুন'));
  }
  const active = b.active !== false;
  await db.doc(`providers/${user.uid}`).set({
    type: badge,
    name: (await userName(user.uid)).slice(0, 80),
    specialty: typeof b.specialty === 'string' ? b.specialty.trim().slice(0, 80) : '',
    chamber,
    district: b.district,
    schedule,
    fee: typeof b.fee === 'string' ? b.fee.trim().slice(0, 40) : '',
    active,
    listed: active,
    appointments: active,
    updatedAt: Date.now(),
  }, { merge: true });
  return { ok: true };
}

module.exports = {
  /**
   * ডাক্তার/নার্সের তালিকা। ফিল্টার: district, upazilaId, unionId, hospitalId, specialty;
   * homeVisit: true হলে শুধু বাসায় যাওয়া নার্স।
   */
  async providers(req) {
    const b = req.body || {};
    const type = b.type;
    if (!PROVIDER_TYPES.includes(type)) throw new HttpError(400, C.tr(req, 'Choose doctor or nurse', 'ডাক্তার বা নার্স বেছে নিন'));
    const f = {
      district: typeof b.district === 'string' && DISTRICTS.some((d) => d.bn === b.district) ? b.district : null,
      upazilaId: Number.isInteger(b.upazilaId) ? b.upazilaId : null,
      unionId: Number.isInteger(b.unionId) ? b.unionId : null,
      hospitalId: R.isValidId(b.hospitalId) ? b.hospitalId : null,
      specialty: K.specialtiesFor(type).includes(b.specialty) ? b.specialty : null,
    };
    // নতুন ধরনের ডক (listed) আর আগের ভার্সনের ডক (active) — দুটোই সমান-সমান খোঁজ
    const [listedSnap, legacySnap] = await Promise.all([
      db.collection('providers').where('type', '==', type).where('listed', '==', true).limit(1000).get(),
      db.collection('providers').where('type', '==', type).where('active', '==', true).limit(300).get(),
    ]);
    const all = new Map();
    [...listedSnap.docs, ...legacySnap.docs].forEach((d) => all.set(d.id, d));
    // আগের ভার্সনের অ্যাপ (homeVisit পাঠায় না) সবাইকে বুক করতে দেয়, তাই তাকে শুধু অ্যাপয়েন্টমেন্ট নেওয়া মানুষ দেখানো
    const oldClient = b.homeVisit === undefined;
    const docs = [...all.values()].filter((d) => {
      const x = d.data();
      if (!K.isListed(x) || !K.matchesFilter(x, f)) return false;
      if (oldClient && !(K.takesAppointments(x) && K.chambersOf(x).some((c) => c.appointments))) return false;
      if (b.homeVisit === true && !(x.homeVisit && x.homeVisit.enabled && x.homeVisitBlocked !== true)) return false;
      return true;
    });
    // ব্যাজ বাতিল হয়ে গেলে তালিকায় দেখাবে না
    const badges = docs.length ? await db.getAll(...docs.map((d) => db.doc(`badges/${d.id}`))) : [];
    const ok = new Set(badges.filter((x) => x.exists && x.get('status') === 'approved').map((x) => x.id));
    const items = docs.filter((d) => ok.has(d.id)).map(provider);
    items.sort((p, q) => q.ratingCount - p.ratingCount || String(p.name).localeCompare(String(q.name)));
    return { items: items.slice(0, 150) };
  },

  /** নিজের চেম্বারের তথ্য (না থাকলে null) */
  async provider_me(req, user) {
    const s = await db.doc(`providers/${user.uid}`).get();
    return { provider: s.exists ? provider(s) : null };
  },

  async provider_save(req, user) {
    const badge = await approvedBadge(user.uid);
    if (!PROVIDER_TYPES.includes(badge)) {
      throw new HttpError(403, C.tr(req, 'Only verified doctors and nurses can add chambers', 'শুধু যাচাই করা ডাক্তার ও নার্স চেম্বার যোগ করতে পারেন'));
    }
    const b = req.body || {};
    if (!Array.isArray(b.chambers)) return legacySave(req, user, badge);
    const err = (e) => new HttpError(400, C.tr(req, e[0], e[1]));

    const ids = [...new Set(b.chambers.map((c) => c && c.hospitalId).filter(R.isValidId))].slice(0, K.MAX_CHAMBERS);
    const { findHospital } = require('./hospitals');
    const found = await Promise.all(ids.map(findHospital));
    const hospitals = new Map(found.filter(Boolean).map((h) => [h.id, h]));
    const parsed = K.parseChambers(b.chambers, hospitals);
    if (parsed.error) throw err(parsed.error);
    const specialties = K.pickCodes(b.specialties, K.specialtiesFor(badge), 5);
    if (!specialties.length) throw err(['Choose at least one specialty', 'অন্তত একটা বিশেষজ্ঞতা বেছে নিন']);
    let homeVisit = { enabled: false };
    if (badge === 'nurse') {
      const h = K.parseHomeVisit(b.homeVisit);
      if (h.error) throw err(h.error);
      homeVisit = h.homeVisit;
    }
    const gender = ['male', 'female'].includes(b.gender) ? b.gender : '';
    if (homeVisit.enabled && b.homeVisit?.termsAccepted !== true) {
      throw err(['Please accept the Home Nurse terms', 'হোম নার্সের শর্তে সম্মতি দিন']);
    }
    if (homeVisit.enabled) {
      await require('./phone').requireVerified(req, user.uid);
      const u = await db.doc(`users/${user.uid}`).get();
      if (!(typeof u.get('lat') === 'number' && u.get('locationSource') === 'gps') && !homeVisit.upazilaIds.length) {
        throw err(['Set your current location or choose upazilas for home visits', 'হোম ভিজিটের জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন']);
      }
    }
    const listed = b.listed !== false;
    const takes = b.appointments === true;
    const name = (await userName(user.uid)).slice(0, 80);
    const ref = db.doc(`providers/${user.uid}`);
    // অ্যাডমিন একই সময়ে বন্ধ করলেও যেন আবার চালু হয়ে না যায়: পড়া আর লেখা একই লেনদেনে
    await db.runTransaction(async (t) => {
      const cur = await t.get(ref);
      if (homeVisit.enabled && cur.exists && cur.get('homeVisitBlocked') === true) {
        throw err(['Home visits are turned off for your account after a report. Contact us.', 'রিপোর্টের পর আপনার হোম ভিজিট বন্ধ আছে। আমাদের সাথে যোগাযোগ করুন।']);
      }
      t.set(ref, {
        type: badge,
        name,
        listed,
        appointments: takes,
        active: listed && takes, // আগের ভার্সনের অ্যাপ এটা দেখে
        specialties,
        degrees: K.str(b.degrees, 120),
        gender,
        chambers: parsed.chambers,
        homeVisit,
        ...(homeVisit.enabled ? { homeVisitTermsAt: Date.now() } : {}),
        updatedAt: Date.now(),
      }, { merge: true });
    });
    return { ok: true };
  },

  /** রোগীর অনুরোধ */
  async book(req, user) {
    const { providerUid, date } = req.body || {};
    const chamberIndex = Number.isInteger(req.body?.chamberIndex) ? req.body.chamberIndex : null;
    if (!R.isValidId(providerUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (providerUid === user.uid) throw new HttpError(400, C.tr(req, 'You can\'t book yourself', 'নিজের কাছে অ্যাপয়েন্টমেন্ট নেওয়া যায় না'));
    const today = R.todayDhakaUtcMidnight();
    if (!Number.isInteger(date) || date < today || date > today + 60 * DAY_MS) {
      throw new HttpError(400, C.tr(req, 'Pick a date within the next 60 days', 'আগামী ৬০ দিনের মধ্যে তারিখ দিন'));
    }
    const note = C.cleanText(req.body?.note, 300);
    if (!note) throw new HttpError(400, C.tr(req, 'Write the reason for the visit', 'কী সমস্যা, সংক্ষেপে লিখুন'));
    const ps = await db.doc(`providers/${providerUid}`).get();
    const notTaking = () => new HttpError(404, C.tr(req, 'Not taking appointments right now', 'এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না'));
    if (!ps.exists || !K.isListed(ps.data()) || !K.takesAppointments(ps.data()) || !PROVIDER_TYPES.includes(await approvedBadge(providerUid))) {
      throw notTaking();
    }
    const chambers = K.chambersOf(ps.data());
    const ch = chamberIndex == null ? chambers.find((c) => c.appointments) : chambers[chamberIndex];
    if (!ch || !ch.appointments) throw notTaking();
    if (!K.chamberOpenOn(ch, date)) {
      throw new HttpError(400, C.tr(req, 'The chamber is closed on that day. Pick one of its days.', 'সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।'));
    }
    await useQuota(req, user.uid, 'book');
    const name = (await userName(user.uid)).slice(0, 80);
    const ref = await db.collection('appointments').add({
      patientUid: user.uid,
      patientName: name,
      providerUid,
      providerName: ps.get('name'),
      type: ps.get('type'),
      chamber: ch.hospitalName,
      chamberBn: ch.hospitalNameBn || '',
      chamberKey: ch.hospitalId || ch.hospitalName || '',
      start: ch.start || '',
      room: ch.room || '',
      schedule: ch.schedule,
      date,
      note,
      status: 'pending',
      providerNote: '',
      createdAt: Date.now(),
    });
    await push(providerUid, 'New appointment request', 'নতুন অ্যাপয়েন্টমেন্টের অনুরোধ', `${name} · ${dateLabel(date)}`, { type: 'appointment' });
    return { id: ref.id };
  },

  /** নিজের অ্যাপয়েন্টমেন্ট: রোগী হিসেবে আর ডাক্তার/নার্স হিসেবে */
  async my_appointments(req, user) {
    const [mine, toMe] = await Promise.all([
      db.collection('appointments').where('patientUid', '==', user.uid).limit(100).get(),
      db.collection('appointments').where('providerUid', '==', user.uid).limit(200).get(),
    ]);
    // নিশ্চিত হলে অপরজনের ফোন
    const need = new Set();
    mine.docs.forEach((d) => d.get('status') === 'confirmed' && need.add(d.get('providerUid')));
    toMe.docs.forEach((d) => d.get('status') === 'confirmed' && need.add(d.get('patientUid')));
    const ids = [...need];
    const snaps = ids.length ? await db.getAll(...ids.map((id) => db.doc(`users/${id}`))) : [];
    const phones = new Map(snaps.map((s) => [s.id, s.exists ? s.get('phone') || '' : '']));

    const item = (d, role) => {
      const other = role === 'patient' ? d.get('providerUid') : d.get('patientUid');
      return {
        id: d.id,
        role,
        otherName: role === 'patient' ? d.get('providerName') : d.get('patientName'),
        type: d.get('type'),
        chamber: d.get('chamber'),
        chamberBn: d.get('chamberBn') || '',
        room: d.get('room') || '',
        start: d.get('start') || '',
        serial: d.get('serial') || 0,
        schedule: d.get('schedule'),
        date: d.get('date'),
        note: d.get('note'),
        status: d.get('status'),
        providerNote: d.get('providerNote') || '',
        otherPhone: d.get('status') === 'confirmed' ? phones.get(other) || '' : '',
        createdAt: d.get('createdAt'),
      };
    };
    return { items: [...mine.docs.map((d) => item(d, 'patient')), ...toMe.docs.map((d) => item(d, 'provider'))] };
  },

  /** নিশ্চিত / বাতিল / না করা */
  async appointment_update(req, user) {
    // body.action অনুরোধের নাম ("appointment_update"); কী করতে হবে তা body.op-এ
    const { id, op: action } = req.body || {};
    if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`appointments/${id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const a = s.data();
    const isProvider = a.providerUid === user.uid;
    if (isProvider && (action === 'confirm' || action === 'decline') && !PROVIDER_TYPES.includes(await approvedBadge(user.uid))) {
      throw new HttpError(403, C.tr(req, 'Your professional badge is not active', 'আপনার পেশাদার ব্যাজ এখন চালু নেই'));
    }
    const isPatient = a.patientUid === user.uid;
    const note = typeof req.body?.providerNote === 'string' ? req.body.providerNote.trim().slice(0, 200) : '';
    let next = null;
    if (isProvider && a.status === 'pending' && action === 'confirm') next = 'confirmed';
    else if (isProvider && a.status === 'pending' && action === 'decline') next = 'declined';
    else if ((isProvider || isPatient) && ['pending', 'confirmed'].includes(a.status) && action === 'cancel') next = 'cancelled';
    if (!next) throw new HttpError(400, C.tr(req, 'This can\'t be changed now', 'এখন এটা বদলানো যায় না'));
    // নিশ্চিত হলে ওই দিনের ওই চেম্বারের পরের সিরিয়াল নম্বর (একই লেনদেনে, যাতে দুজন একই নম্বর না পান)
    let serial = null;
    await db.runTransaction(async (t) => {
      const cur = await t.get(ref);
      if (cur.get('status') !== a.status) throw new HttpError(409, C.tr(req, 'This changed just now. Refresh.', 'এইমাত্র বদলেছে। রিফ্রেশ করুন।'));
      const update = { status: next, providerNote: isProvider && note ? note : a.providerNote || '', updatedAt: Date.now() };
      if (next === 'confirmed') {
        const key = require('node:crypto').createHash('md5').update(String(a.chamberKey || a.chamber || '')).digest('hex').slice(0, 10);
        const cRef = db.doc(`serials/${a.providerUid}_${a.date}_${key}`);
        const c = await t.get(cRef);
        serial = (c.exists ? c.get('n') || 0 : 0) + 1;
        t.set(cRef, { n: serial, at: Date.now() });
        update.serial = serial;
      }
      t.update(ref, update);
    });

    const to = isProvider ? a.patientUid : a.providerUid;
    const texts = {
      confirmed: ['Appointment confirmed', 'অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে'],
      declined: ['Appointment not accepted', 'অ্যাপয়েন্টমেন্ট নেওয়া হয়নি'],
      cancelled: ['Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'],
    }[next];
    await push(to, texts[0], texts[1], `${isProvider ? a.providerName : a.patientName} · ${dateLabel(a.date)}${serial ? ` · #${serial}` : ''}`, { type: 'appointment' });
    return { ok: true };
  },
};
