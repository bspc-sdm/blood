// সিরিয়াল চলছে (১৮ অক্টোবর ২০২৬, পারভেজ): "ডাক্তারকে সিরিয়াল দিলে রোগীরা দেখতে পারবে কত নাম্বার সিরিয়াল চলছে,
// একটা রোগী দেখতে ডাক্তার গড়ে কত সময় নেয়, তার উপর ভিত্তি করে রোগী কখন উপস্থিত থাকবে।
// কোন কারনে রোগীর লেট হলে সময় চেয়ে যেনো রিকোয়েস্ট করতে পারে।"
//
// - ডাক্তার বা তাঁর সহকারী আজকের সিরিয়ালে "শুরু করুন", "পরের রোগী", কোনো সিরিয়াল চেপে "এখন দেখছি", "বিরতি"/"আবার শুরু"।
// - সিরিয়ালের নম্বর ডাক্তারের খাতার নম্বর (ফোনে নেওয়া রোগীর নম্বরও এর মধ্যে) — তাই "পরের রোগী" এক এক করে বাড়ে,
//   শুধু বাতিল হওয়া, দেখা হয়ে যাওয়া আর "পরে আসুন" করা নম্বর বাদ দেয়। শেষ নম্বরের পর "পরে আসুন" করা রোগীরা।
// - রোগীপ্রতি গড় সময়: প্রতিবার পরের রোগীতে যাওয়ার ফাঁক (১–৬০ মিনিট; বিরতি বাদ) দিয়ে চলমান গড় —
//   providers/{uid}.queueStats.{key} = { avg, n, at } (বড় খোঁজ নেই)। আগের হিসাব কম থাকলে চেম্বারের সেটিং (patientMin), নইলে ১০।
// - রোগী (আর তাঁর কেয়ারগিভার) দেখেন: এখন কত নম্বর, আগে কতজন, আনুমানিক সময়, কখনের মধ্যে আসবেন। আগে ২ জন থাকলে একবার নোটিফিকেশন।
// - দেরি: রোগী "দেরি হবে" (১০/২০/৩০/৬০ মিনিট + ছোট নোট) → ডাক্তার/সহকারী "ঠিক আছে" (সিরিয়াল থাকে) বা "পরে আসুন" (সবার শেষে)।
//
// queues/{providerUid}_{date}_{key}  (key = serials-এর মতো chamberKey-এর md5 ১০ অক্ষর)
//   { providerUid, date, chamberKey, key, started, current, pos, done:[নম্বর], paused, pausedAt, startedAt, lastAt,
//     advances:[ms, শেষ ১২টা], finished, updatedAt }
//   current = এখন যাঁকে দেখছেন (০ = কেউ না); pos = ক্রমে কত পর্যন্ত এসেছে (পেছনের কাউকে চাপলে সরে না)
// appointments/{id}-এ: lateMin, lateNote, lateAt, lateStatus ('asked' | 'ok' | 'later'), lateReplyAt, lateCount, nearPushAt
// queues অ্যাপ সরাসরি পড়তে/লিখতে পারে না (Firestore নিয়মে নেই) — সব এখান দিয়ে।
const crypto = require('crypto');
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const K = require('./care');
const { approvedBadge } = require('./community-db');

const MIN = 60 * 1000;
const DEFAULT_MIN = 10;
const MAX_GAP_MIN = 60; // এর বেশি ফাঁক গড়ে ধরা হয় না (চা-নামাজ-অন্য কাজ)
const MIN_GAP_MIN = 1; // ভুলে পরপর চাপলে গড় নামে না
const STATS_N = 200; // চলমান গড়ের ওজন — মোটামুটি শেষ ~৩০ দিনের রোগী
const STATS_STALE = 30 * 24 * 3600 * 1000; // ৩০ দিন কিছু না হলে নতুন করে
const STATS_MIN_N = 3; // এর কম হিসাব থাকলে চেম্বারের সেটিং
const NEAR = 2; // আগে এতজন থাকলে নোটিফিকেশন
const LATE_MINUTES = [10, 20, 30, 60];
const MAX_LATE_ASKS = 3;
const CHAMBER_TYPES = ['doctor', 'nutritionist', 'trainer'];
const ACTIVE = ['confirmed', 'completed'];

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const keyOf = (chamberKey) => crypto.createHash('md5').update(String(chamberKey)).digest('hex').slice(0, 10);
const apptKey = (a) => a.chamberKey || a.chamber || '';
const queueId = (providerUid, date, key) => `${providerUid}_${date}_${key}`;

/** ঢাকার এই দিনের (UTC মধ্যরাত) "HH:MM" — মিলিসেকেন্ডে */
function atTime(date, hhmm) {
  if (!/^\d{2}:\d{2}$/.test(hhmm || '')) return null;
  return date + (Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))) * MIN - 6 * 3600 * 1000;
}

// ---------------------------------------------------------------
// ক্রম (শুধু হিসাব — পরীক্ষায় সরাসরি)
// ---------------------------------------------------------------

/**
 * আজকের অ্যাপয়েন্টমেন্ট থেকে নম্বরের অবস্থা:
 * cancelled = যে নম্বরের সব অ্যাপয়েন্টমেন্ট বাতিল (ফোনে নেওয়া নম্বর — অ্যাপে নেই — বাদ যায় না);
 * later = "পরে আসুন" করা নম্বর; max = শেষ নম্বর
 */
function numbers(appts, counter = 0) {
  const live = new Set();
  const any = new Set();
  const later = new Set();
  let max = counter || 0;
  for (const a of appts) {
    const s = a.serial;
    if (!Number.isInteger(s) || s < 1) continue;
    any.add(s);
    max = Math.max(max, s);
    if (ACTIVE.includes(a.status)) {
      live.add(s);
      if (a.lateStatus === 'later') later.add(s);
    }
  }
  const cancelled = new Set([...any].filter((s) => !live.has(s)));
  return { cancelled, later, max };
}

/** পরের নম্বর: pos-এর পরের প্রথম চালু নম্বর; না থাকলে "পরে আসুন" করাদের সবচেয়ে ছোটটা; না থাকলে null */
function nextAfter(pos, done, cur, nums) {
  for (let n = pos + 1; n <= nums.max; n++) {
    if (!done.has(n) && !nums.later.has(n) && !nums.cancelled.has(n)) return { n, forward: true };
  }
  const l = [...nums.later].filter((n) => !done.has(n) && n !== cur).sort((a, b) => a - b)[0];
  return l ? { n: l, forward: false } : null;
}

/** এখনকার পরে যাঁরা আসবেন, ক্রমে (এখন যিনি দেখছেন তিনি বাদ) */
function order(q, nums) {
  const done = new Set(q.done || []);
  let cur = q.started ? q.current || 0 : 0;
  let pos = q.started ? q.pos || 0 : 0;
  if (cur) done.add(cur);
  const out = [];
  for (let i = 0; i < 1000; i++) {
    const nx = nextAfter(pos, done, cur, nums);
    if (!nx) break;
    out.push(nx.n);
    done.add(nx.n);
    cur = nx.n;
    if (nx.forward) pos = nx.n;
  }
  return out;
}

/**
 * একজন রোগীর জায়গা: state = 'waiting' | 'now' | 'done' | 'passed' | 'finished'; ahead = আগে কতজন (এখন যিনি দেখছেন তিনিসহ)
 */
function placeOf(q, nums, serial) {
  if (q.started && q.current === serial) return { state: 'now', ahead: 0 };
  if ((q.done || []).includes(serial)) return { state: 'done', ahead: 0 };
  const list = order(q, nums);
  const i = list.indexOf(serial);
  if (i < 0) return { state: q.finished ? 'finished' : 'passed', ahead: 0 };
  return { state: 'waiting', ahead: i + (q.started && q.current ? 1 : 0) };
}

/** রোগীপ্রতি গড় মিনিট: আগের হিসাব (যথেষ্ট থাকলে), নইলে চেম্বারের সেটিং, নইলে ১০ */
function avgFor(provider, key, chamber, now = Date.now()) {
  const st = provider && provider.queueStats && provider.queueStats[key];
  if (st && st.n >= STATS_MIN_N && now - (st.at || 0) < STATS_STALE && st.avg > 0) return { avgMin: Math.max(1, Math.round(st.avg)), avgFrom: 'history' };
  const set = chamber && Number.isInteger(chamber.patientMin) && chamber.patientMin > 0 ? chamber.patientMin : null;
  return { avgMin: set || DEFAULT_MIN, avgFrom: set ? 'setting' : 'default' };
}

/** নতুন ফাঁক (মিনিট) দিয়ে চলমান গড় */
function addGap(st, gapMin, now = Date.now()) {
  const fresh = !st || !(st.n > 0) || now - (st.at || 0) >= STATS_STALE;
  const n = fresh ? 1 : Math.min((st.n || 0) + 1, STATS_N);
  const avg = fresh ? gapMin : st.avg + (gapMin - st.avg) / n;
  return { avg: Math.round(avg * 100) / 100, n, at: now };
}

const round5 = (ms, up) => {
  const step = 5 * MIN;
  return (up ? Math.ceil(ms / step) : Math.floor(ms / step)) * step;
};

/**
 * রোগীর আনুমানিক সময় আর কখনের মধ্যে আসবেন।
 * শুরুর আগে: চেম্বার শুরুর সময় (পেরিয়ে গেলে এখন) + আগে কতজন × গড়;
 * শুরুর পরে: এখন যিনি দেখছেন তাঁর শেষ (শুরু + গড়, কমপক্ষে এখন) + বাকিরা × গড়।
 * আসার সময় = আনুমানিক − (২০ মিনিট বা ২ জনের সময়, যেটা বেশি)
 */
function etaFor(q, place, avgMin, startMs, now = Date.now()) {
  if (place.state !== 'waiting') return { eta: place.state === 'now' ? now : null, arriveBy: null };
  const avg = avgMin * MIN;
  let eta;
  if (!q.started) {
    eta = Math.max(startMs || now, now) + place.ahead * avg;
  } else {
    const curEnd = q.paused ? now + avg : Math.max(now, (q.lastAt || now) + avg);
    eta = q.current ? curEnd + (place.ahead - 1) * avg : now + place.ahead * avg;
  }
  eta = round5(eta, true);
  const arriveBy = round5(eta - Math.max(20, 2 * avgMin) * MIN, false);
  return { eta, arriveBy };
}

// ---------------------------------------------------------------
// পড়া
// ---------------------------------------------------------------

/** ডাক্তার/পুষ্টিবিদ/ট্রেইনার নিজে, অথবা ডাক্তারের যোগ করা সহকারী (ইমেইল মিলিয়ে) */
async function controller(req, user, doctorUid) {
  const d = typeof doctorUid === 'string' && doctorUid ? doctorUid : user.uid;
  if (d === user.uid) {
    if (!CHAMBER_TYPES.includes(await approvedBadge(user.uid))) throw err(req, 'Only verified professionals', 'শুধু যাচাই করা পেশাজীবী', 403);
    return { doctorUid: d, role: 'doctor' };
  }
  if (!R.isValidId(d)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
  const p = await db.doc(`providers/${d}`).get();
  const email = user.email_verified ? String(user.email || '').toLowerCase() : '';
  if (!email || !p.exists || !(p.get('assistantEmails') || []).includes(email)) {
    throw err(req, "You are not this doctor's assistant", 'আপনি এই ডাক্তারের সহকারী নন', 403);
  }
  if ((await approvedBadge(d)) !== 'doctor') throw err(req, 'This doctor is not verified now', 'এই ডাক্তার এখন যাচাই করা নেই', 403);
  return { doctorUid: d, role: 'assistant' };
}

async function todayAppts(providerUid, date) {
  const snap = await db.collection('appointments').where('providerUid', '==', providerUid).where('date', '==', date).limit(500).get();
  return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
}

const chamberFor = (chambers, a) => chambers.find((c) => (c.hospitalId || c.hospitalName) === apptKey(a) || c.hospitalName === a.chamber) || null;

/** এক চেম্বারের আজকের সব: সারি, ক্রম, গড় */
async function load(providerUid, date, chamberKey, cache = {}) {
  const key = keyOf(chamberKey);
  const [qs, cs, ps, all] = await Promise.all([
    db.doc(`queues/${queueId(providerUid, date, key)}`).get(),
    db.doc(`serials/${queueId(providerUid, date, key)}`).get(),
    cache.provider ? Promise.resolve(cache.provider) : db.doc(`providers/${providerUid}`).get(),
    cache.appts ? Promise.resolve(cache.appts) : todayAppts(providerUid, date),
  ]);
  const appts = all.filter((a) => apptKey(a) === chamberKey);
  const q = qs.exists ? qs.data() : { started: false, current: 0, pos: 0, done: [] };
  const nums = numbers(appts, cs.exists ? cs.get('n') || 0 : 0);
  const provider = ps.exists ? ps.data() : {};
  const chamber = chamberFor(K.chambersOf(provider), appts[0] || { chamberKey });
  return { key, q, nums, appts, provider, chamber };
}

function queueView(q, nums) {
  const list = order(q, nums);
  return {
    started: !!q.started,
    current: q.started ? q.current || 0 : 0,
    paused: !!q.paused,
    finished: !!q.finished && !(q.started && q.current),
    startedAt: q.startedAt || 0,
    lastAt: q.lastAt || 0,
    next: list[0] || null,
    left: list.length,
    max: nums.max,
  };
}

const lateView = (a) => (a.lateStatus ? { min: a.lateMin || 0, note: a.lateNote || '', at: a.lateAt || 0, status: a.lateStatus } : null);

/** ডাক্তার/সহকারীর এক চেম্বারের দেখা */
function chamberView(L, now = Date.now()) {
  const { q, nums, appts, chamber, provider, key } = L;
  const rows = appts.filter((a) => Number.isInteger(a.serial) && a.serial > 0)
    .sort((x, y) => x.serial - y.serial || (x.createdAt || 0) - (y.createdAt || 0))
    .map((a) => {
      const live = ACTIVE.includes(a.status);
      const p = live ? placeOf(q, nums, a.serial) : { state: 'cancelled', ahead: 0 };
      return {
        id: a.id, serial: a.serial, name: a.patientName || '', status: a.status, state: live && a.lateStatus === 'later' && p.state === 'waiting' ? 'later' : p.state,
        ahead: p.ahead, late: lateView(a),
      };
    });
  const first = appts[0] || {};
  return {
    chamberKey: apptKey(first),
    key,
    name: (chamber && chamber.hospitalName) || first.chamber || '',
    nameBn: (chamber && chamber.hospitalNameBn) || first.chamberBn || '',
    start: first.start || (chamber && chamber.start) || '',
    ...avgFor(provider, key, chamber, now),
    queue: queueView(q, nums),
    rows,
    lateAsked: rows.filter((r) => r.late && r.late.status === 'asked' && ['waiting', 'later'].includes(r.state)).length,
  };
}

/** রোগীর দেখা (একটা অ্যাপয়েন্টমেন্ট) */
function patientView(a, L, now = Date.now()) {
  const { q, nums, chamber, provider, key } = L;
  const place = placeOf(q, nums, a.serial);
  const { avgMin, avgFrom } = avgFor(provider, key, chamber, now);
  const startMs = atTime(a.date, a.start || (chamber && chamber.start) || '');
  const { eta, arriveBy } = etaFor(q, place, avgMin, startMs, now);
  const late = lateView(a);
  return {
    live: true,
    serial: a.serial,
    started: !!q.started,
    paused: !!q.paused,
    current: q.started ? q.current || 0 : 0,
    state: place.state,
    ahead: place.ahead,
    later: a.lateStatus === 'later',
    avgMin,
    avgFrom,
    eta,
    arriveBy,
    late,
    canLate: place.state === 'waiting' && a.lateStatus !== 'later' && (a.lateCount || 0) < MAX_LATE_ASKS,
  };
}

// ---------------------------------------------------------------
// নোটিফিকেশন: আগে ২ জন থাকলে একবার
// ---------------------------------------------------------------
async function notifyNear(L) {
  const { pushPatient } = require('./careaccess');
  const { q, nums, appts } = L;
  if (!q.started) return 0;
  const list = order(q, nums);
  const due = [];
  for (const a of appts) {
    if (a.status !== 'confirmed' || !Number.isInteger(a.serial) || a.nearPushAt) continue;
    const i = list.indexOf(a.serial);
    if (i < 0) continue;
    const ahead = i + (q.current ? 1 : 0);
    if (ahead >= 1 && ahead <= NEAR) due.push({ a, ahead });
  }
  for (const { a, ahead } of due) {
    await db.doc(`appointments/${a.id}`).update({ nearPushAt: Date.now() }).catch(() => {});
    a.nearPushAt = Date.now();
    await pushPatient(
      a.patientUid,
      `${ahead} before you — please come to the chamber`,
      `আপনার আগে ${R.bnDigits(ahead)} জন — চেম্বারে চলে আসুন`,
      `${a.providerName || ''} · ${a.chamberBn || a.chamber || ''} · #${a.serial}`,
      { type: 'appointment', role: 'patient', id: a.id },
    );
  }
  return due.length;
}

async function pushProvider(uid, en, bn, body, data) {
  try {
    const { sendPush, tokensOf } = require('./firebase');
    const u = await db.doc(`users/${uid}`).get();
    if (u.exists) await sendPush([{ uid, tokens: tokensOf(u), title: R.L(u.get('lang'), en, bn), body: body || '', data }]);
  } catch (e) {
    console.error('queue push failed', e.message);
  }
}

// ---------------------------------------------------------------
// কাজ
// ---------------------------------------------------------------
const actions = {
  /** ডাক্তার/সহকারী: আজকের চেম্বারগুলোর সিরিয়াল আর কে কোথায় (body.doctorUid = সহকারী কার হয়ে) */
  async queue_today(req, user) {
    const { doctorUid, role } = await controller(req, user, req.body?.doctorUid);
    const date = R.todayDhakaUtcMidnight();
    const [appts, ps] = await Promise.all([todayAppts(doctorUid, date), db.doc(`providers/${doctorUid}`).get()]);
    const keys = [...new Set(appts.filter((a) => Number.isInteger(a.serial) && a.serial > 0).map(apptKey))];
    const chambers = [];
    for (const k of keys) chambers.push(chamberView(await load(doctorUid, date, k, { appts, provider: ps })));
    return { role, date, chambers };
  },

  /**
   * ডাক্তার/সহকারী: op = start | next | set (appointmentId বা serial) | pause | resume
   * body: { chamberKey, op, appointmentId?, serial?, doctorUid? }
   */
  async queue_control(req, user) {
    const b = req.body || {};
    const { doctorUid } = await controller(req, user, b.doctorUid);
    const op = String(b.op || '');
    if (!['start', 'next', 'set', 'pause', 'resume'].includes(op)) throw err(req, 'Unknown action', 'অজানা কাজ');
    const chamberKey = typeof b.chamberKey === 'string' ? b.chamberKey : '';
    const date = R.todayDhakaUtcMidnight();
    const L = await load(doctorUid, date, chamberKey);
    if (!L.appts.some((a) => ACTIVE.includes(a.status) && a.serial > 0)) throw err(req, 'No confirmed serial today', 'আজ কোনো নিশ্চিত সিরিয়াল নেই', 404);
    let target = null;
    if (op === 'set') {
      if (R.isValidId(b.appointmentId)) {
        const a = L.appts.find((x) => x.id === b.appointmentId);
        if (!a || !ACTIVE.includes(a.status) || !(a.serial > 0)) throw err(req, 'This serial is not active', 'এই সিরিয়াল চালু নেই', 409);
        target = a.serial;
      } else if (Number.isInteger(b.serial) && b.serial >= 1 && b.serial <= 999) {
        target = b.serial;
      } else {
        throw err(req, 'Choose the serial', 'সিরিয়াল বেছে নিন');
      }
    }
    const ref = db.doc(`queues/${queueId(doctorUid, date, L.key)}`);
    const pRef = db.doc(`providers/${doctorUid}`);
    await db.runTransaction(async (t) => {
      const [qs, ps] = await Promise.all([t.get(ref), t.get(pRef)]);
      const now = Date.now();
      const q = qs.exists ? qs.data() : { started: false, current: 0, pos: 0, done: [] };
      const done = new Set(q.done || []);
      const up = { providerUid: doctorUid, date, chamberKey, key: L.key, updatedAt: now };
      // বিরতি থেকে ফেরা: বিরতির সময়টা গড়ে না ধরতে শেষ বদলের সময় সামনে সরানো
      const resumed = () => (q.paused ? { lastAt: (q.lastAt || now) + Math.max(0, now - (q.pausedAt || now)), paused: false, pausedAt: 0 } : {});
      if (op === 'pause' || op === 'resume') {
        if (!q.started) throw err(req, 'Start the serial first', 'আগে সিরিয়াল শুরু করুন', 409);
        if (op === 'pause' && !q.paused) Object.assign(up, { paused: true, pausedAt: now });
        if (op === 'resume') Object.assign(up, resumed());
        t.set(ref, up, { merge: true });
        return;
      }
      if (op === 'start' && q.started) return; // দুবার চাপলে কিছু নয়
      const fresh = op === 'start' || !q.started;
      const cur = fresh ? 0 : q.current || 0;
      const pos = fresh ? 0 : q.pos || 0;
      if (cur) done.add(cur);
      let n;
      let newPos = pos;
      if (op === 'set') {
        n = target;
        done.delete(n);
        if (n > pos) newPos = n;
      } else {
        const nx = nextAfter(pos, done, cur, L.nums);
        if (!nx) {
          // সবাই দেখা শেষ
          Object.assign(up, resumed(), { started: true, current: 0, done: [...done].sort((x, y) => x - y), finished: true, lastAt: now });
          t.set(ref, up, { merge: true });
          return;
        }
        n = nx.n;
        if (nx.forward) newPos = n;
      }
      // গড়: আগের রোগী থেকে এই রোগী পর্যন্ত (বিরতি বাদ, ১–৬০ মিনিট হলে)
      const lastAt = q.paused ? (q.lastAt || now) + Math.max(0, now - (q.pausedAt || now)) : q.lastAt || 0;
      if (!fresh && cur && lastAt) {
        const gap = (now - lastAt) / MIN;
        if (gap >= MIN_GAP_MIN && gap <= MAX_GAP_MIN) {
          const stats = (ps.exists && ps.get('queueStats')) || {};
          t.update(pRef, { [`queueStats.${L.key}`]: addGap(stats[L.key], gap, now) });
        }
      }
      Object.assign(up, {
        started: true, current: n, pos: newPos, done: [...done].sort((x, y) => x - y), finished: false, paused: false, pausedAt: 0,
        lastAt: now, advances: [...(fresh ? [] : q.advances || []), now].slice(-12),
        ...(fresh ? { startedAt: now } : {}),
      });
      t.set(ref, up, { merge: true });
    });
    const after = await load(doctorUid, date, chamberKey);
    if (op !== 'pause') await notifyNear(after);
    return { chamber: chamberView(after) };
  },

  /** রোগী (বা কেয়ারগিভার): আজকের নিশ্চিত সিরিয়ালে এখন কত চলছে, আগে কতজন, কখন আসবেন */
  async appointment_queue(req, user) {
    const id = req.body?.id;
    if (!R.isValidId(id)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const s = await db.doc(`appointments/${id}`).get();
    if (!s.exists || s.get('patientUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const a = { id: s.id, ...s.data() };
    const date = R.todayDhakaUtcMidnight();
    if (a.status !== 'confirmed' || a.date !== date || !(a.serial > 0)) return { live: false };
    return patientView(a, await load(a.providerUid, date, apptKey(a)));
  },

  /** রোগী (বা কেয়ারগিভার): "দেরি হবে" — ১০/২০/৩০/৬০ মিনিট + ছোট নোট; সিরিয়াল পার হওয়ার আগে, দিনে একটা (বদলানো যায়) */
  async appointment_late(req, user) {
    const b = req.body || {};
    if (!R.isValidId(b.id)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const ref = db.doc(`appointments/${b.id}`);
    const s = await ref.get();
    if (!s.exists || s.get('patientUid') !== user.uid) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const a = { id: s.id, ...s.data() };
    const date = R.todayDhakaUtcMidnight();
    if (a.status !== 'confirmed' || a.date !== date || !(a.serial > 0)) throw err(req, "Only for today's confirmed serial", 'শুধু আজকের নিশ্চিত সিরিয়ালে', 409);
    const minutes = Number(b.minutes);
    if (!LATE_MINUTES.includes(minutes)) throw err(req, 'Choose how late', 'কত দেরি হবে বেছে নিন');
    if (a.lateStatus === 'later') throw err(req, 'The doctor already moved you to the end', 'ডাক্তার আপনাকে আগেই শেষে রেখেছেন', 409);
    if ((a.lateCount || 0) >= MAX_LATE_ASKS) throw err(req, "You can't change it again today", 'আজ আর বদলানো যাবে না', 409);
    const L = await load(a.providerUid, date, apptKey(a));
    const place = placeOf(L.q, L.nums, a.serial);
    if (place.state !== 'waiting') throw err(req, 'Your serial has already passed', 'আপনার সিরিয়াল পার হয়ে গেছে', 409);
    const note = C.cleanText(b.note, 100);
    await ref.update({ lateMin: minutes, lateNote: note, lateAt: Date.now(), lateStatus: 'asked', lateReplyAt: 0, lateCount: (a.lateCount || 0) + 1 });
    await pushProvider(
      a.providerUid,
      `Patient will be ${minutes} min late`,
      `রোগী ${R.bnDigits(minutes)} মিনিট দেরিতে আসবেন`,
      `${a.patientName || ''} · #${a.serial}${note ? ` · ${note}` : ''}`,
      { type: 'appointment', role: 'provider' },
    );
    return { ok: true };
  },

  /** ডাক্তার/সহকারী: দেরির উত্তর — op 'ok' (সিরিয়াল থাকে) বা 'later' (সবার শেষে; অন্যদের নম্বর বদলায় না) */
  async appointment_late_reply(req, user) {
    const b = req.body || {};
    if (!R.isValidId(b.id)) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const ref = db.doc(`appointments/${b.id}`);
    const s = await ref.get();
    if (!s.exists) throw err(req, 'Not found', 'পাওয়া যায়নি', 404);
    const a = { id: s.id, ...s.data() };
    await controller(req, user, a.providerUid === user.uid ? null : a.providerUid);
    const op = b.op === 'later' ? 'later' : b.op === 'ok' ? 'ok' : null;
    if (!op) throw err(req, 'Choose a reply', 'উত্তর বেছে নিন');
    if (!a.lateStatus) throw err(req, 'No request for more time', 'সময় চাওয়ার অনুরোধ নেই', 409);
    const date = R.todayDhakaUtcMidnight();
    if (a.status !== 'confirmed' || a.date !== date) throw err(req, 'This changed just now. Refresh.', 'এইমাত্র বদলেছে। রিফ্রেশ করুন।', 409);
    const L = await load(a.providerUid, date, apptKey(a));
    const place = placeOf(L.q, L.nums, a.serial);
    if (!['waiting'].includes(place.state)) throw err(req, 'This serial has already passed', 'এই সিরিয়াল পার হয়ে গেছে', 409);
    await ref.update({ lateStatus: op, lateReplyAt: Date.now(), ...(op === 'later' ? { nearPushAt: FieldValue.delete() } : {}) });
    const { pushPatient } = require('./careaccess');
    await pushPatient(
      a.patientUid,
      op === 'ok' ? 'Doctor: OK, your serial stays' : 'Doctor: come later — you will be seen at the end',
      op === 'ok' ? 'ডাক্তার: ঠিক আছে, আপনার সিরিয়াল থাকছে' : 'ডাক্তার: পরে আসুন — আপনাকে সবার শেষে দেখা হবে',
      `${a.providerName || ''} · #${a.serial}`,
      { type: 'appointment', role: 'patient', id: a.id },
    );
    // শেষে সরালে অন্যদের ক্রম এগোয় — কারো আগে ২ জন হলে জানানো
    const after = await load(a.providerUid, date, apptKey(a));
    await notifyNear(after);
    return { ok: true, chamber: chamberView(after) };
  },
};

/** অ্যাকাউন্ট মুছলে: ডাক্তারের সিরিয়ালের সারি (রোগীর নাম নেই, তবু ডাক্তারের uid আছে) */
async function deleteFor(uid) {
  const snap = await db.collection('queues').where('providerUid', '==', uid).limit(1000).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = db.batch();
    snap.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  return snap.size;
}

module.exports = {
  actions, deleteFor, keyOf, queueId,
  _q: { numbers, nextAfter, order, placeOf, avgFor, addGap, etaFor, atTime },
  DEFAULT_MIN, LATE_MINUTES,
};
