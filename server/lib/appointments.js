// অ্যাপয়েন্টমেন্ট: ব্যাজধারী ডাক্তার/নার্স চেম্বারের তথ্য দেন, রোগী অনুরোধ পাঠান, ডাক্তার/নার্স নিশ্চিত করেন।
// টাকা নেওয়া নেই। নিশ্চিত হলে দুজনেই একে অপরের ফোন নম্বর দেখেন।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const DISTRICTS = require('./districts');
const { approvedBadge, useQuota, userName } = require('./community-db');

const PROVIDER_TYPES = ['doctor', 'nurse'];
const DAY_MS = 24 * 60 * 60 * 1000;

function provider(d) {
  return {
    uid: d.id,
    type: d.get('type'),
    name: d.get('name'),
    specialty: d.get('specialty') || '',
    chamber: d.get('chamber'),
    district: d.get('district'),
    schedule: d.get('schedule'),
    fee: d.get('fee') || '',
    active: d.get('active') !== false,
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

module.exports = {
  /** কোন ধরনের (doctor/nurse) চালু থাকা সেবাদানকারীর তালিকা */
  async providers(req) {
    const type = req.body?.type;
    if (!PROVIDER_TYPES.includes(type)) throw new HttpError(400, C.tr(req, 'Choose doctor or nurse', 'ডাক্তার বা নার্স বেছে নিন'));
    const snap = await db.collection('providers').where('type', '==', type).where('active', '==', true).limit(300).get();
    // ব্যাজ বাতিল হয়ে গেলে তালিকায় দেখাবে না
    const badges = snap.docs.length ? await db.getAll(...snap.docs.map((d) => db.doc(`badges/${d.id}`))) : [];
    const ok = new Set(badges.filter((b) => b.exists && b.get('status') === 'approved').map((b) => b.id));
    return { items: snap.docs.filter((d) => ok.has(d.id)).map(provider) };
  },

  /** নিজের চেম্বারের তথ্য (না থাকলে null) */
  async provider_me(req, user) {
    const s = await db.doc(`providers/${user.uid}`).get();
    return { provider: s.exists ? provider(s) : null };
  },

  async provider_save(req, user) {
    const badge = await approvedBadge(user.uid);
    if (!PROVIDER_TYPES.includes(badge)) {
      throw new HttpError(403, C.tr(req, 'Only verified doctors and nurses can take appointments', 'শুধু যাচাই করা ডাক্তার ও নার্স অ্যাপয়েন্টমেন্ট নিতে পারেন'));
    }
    const b = req.body || {};
    const chamber = C.cleanText(b.chamber, 120);
    const schedule = C.cleanText(b.schedule, 120);
    const specialty = typeof b.specialty === 'string' ? b.specialty.trim().slice(0, 80) : '';
    const fee = typeof b.fee === 'string' ? b.fee.trim().slice(0, 40) : '';
    if (!chamber) throw new HttpError(400, C.tr(req, 'Enter your chamber / hospital', 'চেম্বার / হাসপাতালের নাম দিন'));
    if (!schedule) throw new HttpError(400, C.tr(req, 'Enter your days and time', 'কোন দিন ও কখন বসেন লিখুন'));
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    await db.doc(`providers/${user.uid}`).set({
      type: badge,
      name: (await userName(user.uid)).slice(0, 80),
      specialty,
      chamber,
      district: b.district,
      schedule,
      fee,
      active: b.active !== false,
      updatedAt: Date.now(),
    });
    return { ok: true };
  },

  /** রোগীর অনুরোধ */
  async book(req, user) {
    const { providerUid, date } = req.body || {};
    if (!R.isValidId(providerUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (providerUid === user.uid) throw new HttpError(400, C.tr(req, 'You can\'t book yourself', 'নিজের কাছে অ্যাপয়েন্টমেন্ট নেওয়া যায় না'));
    const today = R.todayDhakaUtcMidnight();
    if (!Number.isInteger(date) || date < today || date > today + 60 * DAY_MS) {
      throw new HttpError(400, C.tr(req, 'Pick a date within the next 60 days', 'আগামী ৬০ দিনের মধ্যে তারিখ দিন'));
    }
    const note = C.cleanText(req.body?.note, 300);
    if (!note) throw new HttpError(400, C.tr(req, 'Write the reason for the visit', 'কী সমস্যা, সংক্ষেপে লিখুন'));
    const ps = await db.doc(`providers/${providerUid}`).get();
    if (!ps.exists || ps.get('active') === false || !PROVIDER_TYPES.includes(await approvedBadge(providerUid))) {
      throw new HttpError(404, C.tr(req, 'Not taking appointments right now', 'এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না'));
    }
    await useQuota(req, user.uid, 'book');
    const name = (await userName(user.uid)).slice(0, 80);
    const ref = await db.collection('appointments').add({
      patientUid: user.uid,
      patientName: name,
      providerUid,
      providerName: ps.get('name'),
      type: ps.get('type'),
      chamber: ps.get('chamber'),
      schedule: ps.get('schedule'),
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
    await ref.update({ status: next, providerNote: isProvider && note ? note : a.providerNote || '', updatedAt: Date.now() });

    const to = isProvider ? a.patientUid : a.providerUid;
    const texts = {
      confirmed: ['Appointment confirmed', 'অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে'],
      declined: ['Appointment not accepted', 'অ্যাপয়েন্টমেন্ট নেওয়া হয়নি'],
      cancelled: ['Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'],
    }[next];
    await push(to, texts[0], texts[1], `${isProvider ? a.providerName : a.patientName} · ${dateLabel(a.date)}`, { type: 'appointment' });
    return { ok: true };
  },
};
