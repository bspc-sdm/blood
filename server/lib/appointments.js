// অ্যাপয়েন্টমেন্ট: ব্যাজধারী ডাক্তার/নার্স চেম্বারের তথ্য দেন, রোগী অনুরোধ পাঠান, ডাক্তার/নার্স নিশ্চিত করেন।
// টাকা নেওয়া নেই। নিশ্চিত হলে দুজনেই একে অপরের ফোন নম্বর দেখেন।
const { db, HttpError, sendPush } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const DISTRICTS = require('./districts');
const { approvedBadge, useQuota, userName } = require('./community-db');
const K = require('./care');

// "আমার চেম্বার/বাসায় সেবা" যাঁরা পান
const PROVIDER_TYPES = ['doctor', 'nurse', 'nutritionist', 'trainer'];
// চেম্বার আর অ্যাপয়েন্টমেন্ট: ডাক্তার, পুষ্টিবিদ, ফিটনেস ট্রেইনার; নার্স শুধু বাসায় সেবা (১৬ অক্টোবর ২০২৬)
const CHAMBER_TYPES = ['doctor', 'nutritionist', 'trainer'];
// বাসায় সেবা: ডাক্তার আর নার্স
const HOME_TYPES = ['doctor', 'nurse'];
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
    // বন্ধ থাকলেও নিজের সেটিং ফিরে পেতে লাগে; ফোন নম্বর শুধু চালু থাকলে (তখন তালিকায় এমনিতেই দেখায়)
    homeVisit: x.homeVisit ? { ...x.homeVisit, phone: x.homeVisit.enabled && x.homeVisitBlocked !== true ? K.fixPhone(x.homeVisit.phone) : '' } : null,
    rating: count ? Math.round((x.ratingSum / count) * 10) / 10 : 0,
    ratingCount: count,
    // আগের ভার্সনের অ্যাপের জন্য
    specialty: x.specialty || (x.specialties || []).map((s) => s.replace(/_/g, ' ')).join(', '),
    chamber: first.hospitalName || '',
    district: first.district || '',
    schedule: K.scheduleText(first),
    fee: first.fee || '',
    active: K.isListed(x) && K.takesAppointments(x),
  };
}

async function push(uid, en, bn, body, data) {
  try {
    const u = await db.doc(`users/${uid}`).get();
    if (u.exists) await sendPush([{ uid, tokens: require('./firebase').tokensOf(u), title: R.L(u.get('lang'), en, bn), body, data }]);
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
  if (!CHAMBER_TYPES.includes(badge)) {
    throw new HttpError(409, C.tr(req, 'Nurses don\'t have chambers now. Update the app and turn on home care.', 'নার্সের চেম্বার আর নেই। অ্যাপ আপডেট করে বাসায় সেবা চালু করুন।'), 'update_required');
  }
  const chamber = C.cleanText(b.chamber, 120);
  const schedule = C.cleanText(b.schedule, 120);
  if (!chamber) throw new HttpError(400, C.tr(req, 'Enter your chamber / hospital', 'চেম্বার / হাসপাতালের নাম দিন'));
  if (!schedule) throw new HttpError(400, C.tr(req, 'Enter your days and time', 'কোন দিন ও কখন বসেন লিখুন'));
  if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
  const cur = await db.doc(`providers/${user.uid}`).get();
  // অ্যাডমিন বন্ধ করলে বা ব্যাজ বাতিল হলে পুরোনো অ্যাপ দিয়ে আবার চালু করা যায় না
  if (cur.exists && (cur.get('adminSuspended') === true || cur.get('badgeRejected') === true)) {
    throw new HttpError(403, C.tr(req, 'Your listing is turned off by the Lifolo team', 'আপনার তালিকা Lifolo টিম বন্ধ রেখেছে'));
  }
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
   * homeVisit: true হলে শুধু বাসায় যান এমন মানুষ (আগের অ্যাপ; নতুন অ্যাপ home_providers ব্যবহার করে)।
   */
  async providers(req) {
    const b = req.body || {};
    const type = b.type;
    if (!PROVIDER_TYPES.includes(type)) throw new HttpError(400, C.tr(req, 'Choose doctor or nurse', 'ডাক্তার বা নার্স বেছে নিন'));
    // নার্সের চেম্বার নেই — পুরোনো অ্যাপের "নার্স" তালিকা ফাঁকা (নার্স "বাসায় সেবা"-তে)
    if (!CHAMBER_TYPES.includes(type) && b.homeVisit !== true) return { items: [] };
    const f = {
      district: typeof b.district === 'string' && DISTRICTS.some((d) => d.bn === b.district) ? b.district : null,
      upazilaId: Number.isInteger(b.upazilaId) ? b.upazilaId : null,
      unionId: Number.isInteger(b.unionId) ? b.unionId : null,
      hospitalId: R.isValidId(b.hospitalId) ? b.hospitalId : null,
      specialty: K.specialtiesFor(type).includes(b.specialty) ? b.specialty : null,
    };
    // নতুন ধরনের ডক (listed) আর আগের ভার্সনের ডক (active) — দুটোই সমান-সমান খোঁজ।
    // পুরো তালিকা আর ব্যাজ ২ মিনিট মেমোরিতে (প্রতিবার খোলায় হাজারখানেক পড়া নয়)
    const M = require('./memcache');
    const cachedList = await M.cached(`providers:${type}`, 2 * 60 * 1000, async () => {
      const [listedSnap, legacySnap] = await Promise.all([
        db.collection('providers').where('type', '==', type).where('listed', '==', true).limit(1000).get(),
        db.collection('providers').where('type', '==', type).where('active', '==', true).limit(300).get(),
      ]);
      const m = new Map();
      [...listedSnap.docs, ...legacySnap.docs].forEach((d) => m.set(d.id, d));
      const ds = [...m.values()];
      const bs = ds.length ? await db.getAll(...ds.map((d) => db.doc(`badges/${d.id}`))) : [];
      // ব্যাজ অনুমোদিত আর একই পেশার (ডাক্তারের তালিকায় শুধু ডাক্তার)
      const okIds = new Set(bs.filter((x) => x.exists && x.get('status') === 'approved' && x.get('type') === type).map((x) => x.id));
      return ds.filter((d) => okIds.has(d.id)).map((d) => ({ id: d.id, x: d.data() }));
    });
    const all = new Map(cachedList.map(({ id, x }) => [id, M.docLike(id, x)]));
    // আগের ভার্সনের অ্যাপ (homeVisit পাঠায় না) সবাইকে বুক করতে দেয়, তাই তাকে শুধু অ্যাপয়েন্টমেন্ট নেওয়া মানুষ দেখানো
    const oldClient = b.homeVisit === undefined;
    const docs = [...all.values()].filter((d) => {
      const x = d.data();
      if (x.adminSuspended === true || !K.isListed(x) || !K.matchesFilter(x, f)) return false;
      if (oldClient && !(K.takesAppointments(x) && K.chambersOf(x).some((c) => c.appointments))) return false;
      if (b.homeVisit === true && !(x.homeVisit && x.homeVisit.enabled && x.homeVisitBlocked !== true)) return false;
      // শুধু বাসায় সেবা দেন (চেম্বার নেই) — চেম্বারের তালিকায় নয়, "বাসায় সেবা"-তে দেখান
      if (b.homeVisit !== true && !K.chambersOf(x).length) return false;
      return true;
    });
    // ব্যাজ বাতিল হলে তালিকায় নেই (উপরের মেমোরির তালিকাতেই বাদ)
    const items = docs.map(provider);
    items.sort((p, q) => q.ratingCount - p.ratingCount || String(p.name).localeCompare(String(q.name)));
    return { items: items.slice(0, 150) };
  },

  /** নিজের চেম্বারের তথ্য (না থাকলে null) */
  async provider_me(req, user) {
    const s = await db.doc(`providers/${user.uid}`).get();
    return { provider: s.exists ? provider(s) : null };
  },

  async provider_save(req, user) {
    // (নার্স: চেম্বার নেই, শুধু বাসায় সেবা — নিচে CHAMBER_TYPES)
    // এই সার্ভারের মেমোরির তালিকা নতুন করে (নিজের বদল সাথে সাথে দেখা যায়)
    require('./memcache').clear('providers:');
    require('./memcache').clear('home:');
    const badge = await approvedBadge(user.uid);
    if (!PROVIDER_TYPES.includes(badge)) {
      throw new HttpError(403, C.tr(req, 'Only verified professionals can add chambers', 'শুধু যাচাই করা পেশাজীবীরা চেম্বার যোগ করতে পারেন'));
    }
    const b = req.body || {};
    if (!Array.isArray(b.chambers)) return legacySave(req, user, badge);
    // ১.৪-এর অ্যাপ বার/সময় পাঠায় না — আপডেট করতে বলা
    if (b.chambers.some((c) => c && !Array.isArray(c.days))) {
      throw new HttpError(409, C.tr(req, 'Please update the app to save your chambers', 'চেম্বার সেভ করতে অ্যাপ আপডেট করুন'), 'update_required');
    }
    const err = (e) => new HttpError(400, C.tr(req, e[0], e[1]));

    const ids = [...new Set(b.chambers.map((c) => c && c.hospitalId).filter(R.isValidId))].slice(0, K.MAX_CHAMBERS);
    const { findHospital } = require('./hospitals');
    const found = await Promise.all(ids.map(findHospital));
    const hospitals = new Map(found.filter(Boolean).map((h) => [h.id, h]));
    const specialties = K.pickCodes(b.specialties, K.specialtiesFor(badge), 5);
    if (!specialties.length) throw err(['Choose at least one specialty', 'অন্তত একটা বিশেষজ্ঞতা বেছে নিন']);
    // আগের অ্যাপ ডাক্তারের জন্য homeVisit পাঠায় না — তখন আগেরটা রেখে দেওয়া
    // ২.১ পর্যন্ত অ্যাপে আলাদা ফোন নম্বরের ঘর নেই — আপডেট করতে বলা
    if (b.homeVisit?.enabled === true && b.homeVisit.phone === undefined) {
      throw new HttpError(409, C.tr(req, 'Please update the app to turn on home care', 'বাসায় সেবা চালু করতে অ্যাপ আপডেট করুন'), 'update_required');
    }
    // পুষ্টিবিদ/ট্রেইনারের বাসায় সেবা নেই
    if (!HOME_TYPES.includes(badge) && b.homeVisit?.enabled === true) throw err(['Home care is for doctors and nurses', 'বাসায় সেবা শুধু ডাক্তার আর নার্সের']);
    const hv = K.parseHomeVisit(HOME_TYPES.includes(badge) ? b.homeVisit : { enabled: false }, badge);
    if (hv.error) throw err(hv.error);
    const homeVisit = hv.homeVisit;
    const keepHome = b.homeVisit === undefined;
    // শুধু বাসায় গিয়ে সেবা দিলে চেম্বার না থাকলেও চলে; নার্সের চেম্বার নেই (পাঠালেও বাদ)
    const nurse = !CHAMBER_TYPES.includes(badge);
    if (nurse && !homeVisit.enabled && !keepHome) throw err(['Turn on home care', 'বাসায় সেবা চালু করুন']);
    const parsed = nurse || (b.chambers.length === 0 && homeVisit.enabled) ? { chambers: [] } : K.parseChambers(b.chambers, hospitals, badge !== 'doctor');
    if (parsed.error) throw err(parsed.error);
    const gender = ['male', 'female'].includes(b.gender) ? b.gender : '';
    if (homeVisit.enabled && b.homeVisit?.termsAccepted !== true) {
      throw err(['Please accept the home care terms', 'বাসায় সেবার শর্তে সম্মতি দিন']);
    }
    if (homeVisit.enabled) {
      // আগের অ্যাকাউন্টে (একই ইমেইল) রিপোর্টের পর বাসায় সেবা বন্ধ হয়ে থাকলে
      if ((await require('./sanctions').forEmail(user.email)).homeCare === true) {
        throw err(['Home care was turned off for this email after a report. You can appeal from Profile → Help & appeals.', 'রিপোর্টের পর এই ইমেইলে বাসায় সেবা বন্ধ আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।']);
      }
      await require('./phone').requireVerified(req, user.uid);
      const u = await db.doc(`users/${user.uid}`).get();
      if (!(typeof u.get('lat') === 'number' && u.get('locationSource') === 'gps') && !homeVisit.upazilaIds.length) {
        throw err(['Set your current location or choose upazilas for home visits', 'বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন']);
      }
    }
    const listed = nurse ? false : b.listed !== false;
    const takes = nurse ? false : b.appointments === true;
    // যাচাই করা নাম (ব্যাজের) — প্রোফাইলের নাম পরে বদলালেও তালিকায় যাচাই করা নামই থাকে
    const bs = await db.doc(`badges/${user.uid}`).get();
    const name = ((bs.exists && bs.get('name')) || (await userName(user.uid))).slice(0, 80);
    const ref = db.doc(`providers/${user.uid}`);
    // অ্যাডমিন একই সময়ে বন্ধ করলেও যেন আবার চালু হয়ে না যায়: পড়া আর লেখা একই লেনদেনে
    await db.runTransaction(async (t) => {
      const cur = await t.get(ref);
      if (cur.exists && cur.get('adminSuspended') === true) {
        throw new HttpError(403, C.tr(req, 'Your listing is turned off by the Lifolo team. You can appeal from Profile → Help & appeals.', 'Lifolo টিম আপনার তালিকা বন্ধ রেখেছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।'));
      }
      if (homeVisit.enabled && cur.exists && cur.get('homeVisitBlocked') === true) {
        throw err(['Home care is turned off for your account after a report. You can appeal from Profile → Help & appeals.', 'রিপোর্টের পর আপনার বাসায় সেবা বন্ধ আছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।']);
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
        ...(keepHome ? {} : { homeVisit }),
        ...(homeVisit.enabled ? { homeVisitTermsAt: Date.now() } : {}),
        updatedAt: Date.now(),
      }, { merge: true });
    });
    // চেম্বার মোছা/বার বদল/তালিকা বা অ্যাপয়েন্টমেন্ট বন্ধ হলে: সামনের যেগুলো আর টেকে না সেগুলো বাতিল করে রোগীদের জানানো
    const { cancelStale, stillValid } = require('./apptcancel');
    const cancelled = await cancelStale(
      user.uid,
      (a) => listed && takes && stillValid(a, parsed.chambers),
      ['The doctor changed the chamber or days', 'ডাক্তার চেম্বার বা বার বদলেছেন'],
    );
    return { ok: true, cancelled };
  },

  /** রোগীর অনুরোধ */
  async book(req, user) {
    const { providerUid, date } = req.body || {};
    const chamberIndex = Number.isInteger(req.body?.chamberIndex) ? req.body.chamberIndex : null;
    if (!R.isValidId(providerUid)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (providerUid === user.uid) throw new HttpError(400, C.tr(req, 'You can\'t book yourself', 'নিজের কাছে অ্যাপয়েন্টমেন্ট নেওয়া যায় না'));
    const today = R.todayDhakaUtcMidnight();
    // তারিখ = দিনের শুরু (অন্য সংখ্যা দিয়ে একই দিনে আলাদা সিরিয়াল বা দুবার বুকিং নয়)
    if (!Number.isInteger(date) || date % DAY_MS !== 0 || date < today || date > today + 60 * DAY_MS) {
      throw new HttpError(400, C.tr(req, 'Pick a date within the next 60 days', 'আগামী ৬০ দিনের মধ্যে তারিখ দিন'));
    }
    const note = C.cleanText(req.body?.note, 300);
    if (!note) throw new HttpError(400, C.tr(req, 'Write the reason for the visit', 'কী সমস্যা, সংক্ষেপে লিখুন'));
    const ps = await db.doc(`providers/${providerUid}`).get();
    const notTaking = () => new HttpError(404, C.tr(req, 'Not taking appointments right now', 'এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না'));
    // অ্যাডমিন বন্ধ করলে, বা ব্যাজ অন্য পেশার হলে বুকিং নয়
    if (!ps.exists || ps.get('adminSuspended') === true || !K.isListed(ps.data()) || !K.takesAppointments(ps.data())
      || (await approvedBadge(providerUid)) !== ps.get('type') || !CHAMBER_TYPES.includes(ps.get('type'))) {
      throw notTaking();
    }
    const chambers = K.chambersOf(ps.data());
    const ch = chamberIndex == null ? chambers.find((c) => c.appointments) : chambers[chamberIndex];
    if (!ch || !ch.appointments) throw notTaking();
    // রোগীর পাতা খোলা থাকতে ডাক্তার চেম্বার বদলালে ক্রম সরে যায় — হাসপাতাল মিলিয়ে নেওয়া, নইলে অন্য হাসপাতালে ভুল বুকিং
    const sentKey = typeof req.body?.chamberKey === 'string' ? req.body.chamberKey : null;
    if (sentKey && sentKey !== (ch.hospitalId || ch.hospitalName || '')) {
      throw new HttpError(409, C.tr(req, 'The doctor just changed their chambers. Please open the page again.', 'ডাক্তার এইমাত্র চেম্বারের তথ্য বদলেছেন। পাতাটা আবার খুলুন।'));
    }
    if (!K.chamberOpenOn(ch, date)) {
      throw new HttpError(400, C.tr(req, 'The chamber is closed on that day. Pick one of its days.', 'সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।'));
    }
    // আজকের চেম্বারের সময় শেষ হয়ে গেলে আজকের সিরিয়াল নয় (রাত পেরোনো চেম্বার বাদে)
    if (date === today && ch.start && ch.end && ch.start < ch.end && K.dhakaHHMM() >= ch.end) {
      throw new HttpError(400, C.tr(req, "Today's chamber time is over. Pick another day.", 'আজকের চেম্বারের সময় শেষ। অন্য দিন বেছে নিন।'));
    }
    // একই দিনে একই ডাক্তারের কাছে একটার বেশি চালু অনুরোধ নয়
    const dup = await db.collection('appointments').where('patientUid', '==', user.uid).where('providerUid', '==', providerUid).where('date', '==', date).limit(10).get();
    if (dup.docs.some((d) => ['pending', 'confirmed'].includes(d.get('status')))) {
      throw new HttpError(409, C.tr(req, 'You already have a request with this doctor for that day', 'সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে'));
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
    await push(providerUid, 'New appointment request', 'নতুন অ্যাপয়েন্টমেন্টের অনুরোধ', `${name} · ${dateLabel(date)}`, { type: 'appointment', role: 'provider' });
    return { id: ref.id };
  },

  /** নিজের অ্যাপয়েন্টমেন্ট: রোগী হিসেবে আর ডাক্তার/নার্স হিসেবে */
  async my_appointments(req, user) {
    // চালু (অপেক্ষায়/নিশ্চিত) সবগুলো আসে; পুরোনোগুলো অবস্থা ধরে অল্প করে —
    // যাতে পুরোনো রেকর্ড জমে নতুন অনুরোধ কখনো বাদ না পড়ে (দুই সমান-সমান খোঁজ, আলাদা ইনডেক্স লাগে না)
    const byStatus = async (field) => {
      const lists = await Promise.all([
        ...['pending', 'confirmed'].map((s) => db.collection('appointments').where(field, '==', user.uid).where('status', '==', s).limit(300).get()),
        ...['declined', 'cancelled', 'expired', 'completed'].map((s) => db.collection('appointments').where(field, '==', user.uid).where('status', '==', s).limit(30).get()),
      ]);
      return { docs: lists.flatMap((l) => l.docs) };
    };
    const [mine, toMe] = await Promise.all([byStatus('patientUid'), byStatus('providerUid')]);
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
        // ডাক্তার নিজের রোগীদের এক জায়গায় দেখতে (আসল uid নয়, ছোট সংকেত)
        otherKey: role === 'provider' ? require('crypto').createHash('sha256').update(`pat:${user.uid}:${other}`).digest('hex').slice(0, 12) : '',
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
    // আগের অ্যাপয়েন্টমেন্টে chamberKey নেই — চেম্বারের নাম মিলিয়ে হাসপাতালের id, যাতে একই চেম্বারের সিরিয়াল এক হিসাবে থাকে
    let chamberKey = a.chamberKey;
    if (!chamberKey && next === 'confirmed') {
      const ps = await db.doc(`providers/${a.providerUid}`).get();
      const c = ps.exists ? K.chambersOf(ps.data()).find((x) => x.hospitalName === a.chamber) : null;
      chamberKey = (c && c.hospitalId) || a.chamber || '';
    }
    await db.runTransaction(async (t) => {
      const cur = await t.get(ref);
      if (cur.get('status') !== a.status) throw new HttpError(409, C.tr(req, 'This changed just now. Refresh.', 'এইমাত্র বদলেছে। রিফ্রেশ করুন।'));
      const update = { status: next, providerNote: isProvider && note ? note : a.providerNote || '', updatedAt: Date.now() };
      if (next === 'confirmed') {
        const key = require('node:crypto').createHash('md5').update(String(chamberKey)).digest('hex').slice(0, 10);
        const cRef = db.doc(`serials/${a.providerUid}_${a.date}_${key}`);
        const c = await t.get(cRef);
        const last = c.exists ? c.get('n') || 0 : 0;
        // ডাক্তার নিজের খাতার নম্বর দিলে সেটাই (ফোনে/সরাসরি নেওয়া রোগীসহ); না দিলে পরের নম্বর
        const own = Number.isInteger(req.body?.serial) && req.body.serial >= 1 && req.body.serial <= 999 ? req.body.serial : null;
        serial = own || last + 1;
        t.set(cRef, { n: Math.max(last, serial), at: Date.now() });
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
    await push(to, texts[0], texts[1], `${isProvider ? a.providerName : a.patientName} · ${dateLabel(a.date)}${serial ? ` · #${serial}` : ''}`, { type: 'appointment', role: isProvider ? 'patient' : 'provider' });
    return { ok: true };
  },
};
