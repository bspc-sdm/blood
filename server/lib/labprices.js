// প্যাথলজির পরীক্ষা ও দাম, আর বাসা থেকে নমুনা (১৮ অক্টোবর ২০২৬, পারভেজ: "রোগী পাঠানোর আগেই জানবে কোন ল্যাবে কোন পরীক্ষা হয়, কত টাকা")
//
// labPrices/{labUid}: { items: [{ code|null, name, price, tat, on }], discountPct|null, homeFee|null, updatedAt } — শুধু সার্ভার পড়ে-লেখে।
//   code: পরীক্ষার ছকের (labtests.js) কোড; নিজে লেখা পরীক্ষা ছকের নামের সাথে মিললে কোড নিজে থেকে বসে।
//   price: টাকা (পূর্ণ সংখ্যা); tat: রিপোর্ট কতক্ষণে ("একই দিন", "২৪ ঘণ্টা"); on: এখন হয় কি না।
//   discountPct: সব পরীক্ষায় ছাড় (%); homeFee: বাসা থেকে নমুনা নেওয়ার চার্জ (শুধু homeCollection চালু থাকলে)।
// দাম বসানো শুধু ওয়েবে (বসে করার কাজ — মালিকের নিয়ম); অ্যাপে প্যাথলজির মালিক "দাম বসাতে ওয়েবে খুলুন" পান।
// প্রেসক্রিপশনের পরীক্ষার নাম (ডাক্তারের লেখা) ছকের সাথে মিলিয়ে (labtests.match) তালিকার সাথে মেলানো হয়; ছকে না থাকলে নামে নামে।
//
// বাসা থেকে নমুনা — labOrders/{id}.home:
//   { address, phone, date (চাওয়া দিন, UTC মধ্যরাত), slot (early|morning|afternoon), note, fee, status: requested|confirmed|declined,
//     requestedAt, visitDate, time ("08:00"), at (ঠিক সময়, মিলিসেকেন্ড), confirmNote, confirmedAt, declineNote, declinedAt }
//   ঠিকানা-ফোন শুধু সেই প্যাথলজি (অর্ডার চালু থাকা পর্যন্ত) আর রোগী/কেয়ারগিভার দেখেন — কখনো তালিকায় বা ডাক্তারের কাছে নয়।
const { db, HttpError } = require('./firebase');
const C = require('./community');
const K = require('./care');
const R = require('./rules');
const LT = require('./labtests');
const P = require('./prescriptions');

const DAY = 24 * 60 * 60 * 1000;
const MIN = 60 * 1000;
const MAX_ITEMS = 200;
const MAX_PRICE = 200000;
const MAX_HOME_FEE = 5000;
/** বাসায় আসার সময়ের ভাগ: [শুরু, শেষ] ঘণ্টা (ঢাকা) */
const SLOTS = { early: [7, 9], morning: [9, 12], afternoon: [15, 18] };

const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const str = (v, n) => (typeof v === 'string' ? v.replace(/\r\n/g, '\n').trim().slice(0, n) : '');
const bn = (n) => R.bnDigits(n);

/** সংখ্যা বা লেখা সংখ্যা (বাংলা অঙ্ক, কমা, ৳ চলে) → পূর্ণ সংখ্যা; খালি হলে null; ভুল হলে NaN */
function toInt(v) {
  if (v === null || v === undefined || v === '') return null;
  if (typeof v === 'number') return Number.isInteger(v) ? v : NaN;
  if (typeof v !== 'string') return NaN;
  const t = v.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d))).replace(/[,\s৳]/g, '');
  if (!t) return null;
  return /^\d{1,7}$/.test(t) ? Number(t) : NaN;
}

async function myLab(req, user) {
  const s = await db.doc(`labs/${user.uid}`).get();
  if (!s.exists || s.get('status') !== 'approved') throw err(req, 'Only approved labs can do this', 'শুধু অনুমোদিত প্যাথলজি এটা করতে পারে', 403);
  return s;
}

/** মালিকের পাঠানো তালিকা যাচাই: ছকের কোড বা নিজে লেখা নাম, দাম, রিপোর্টের সময়, চালু/বন্ধ; একই পরীক্ষা দুবার নয় */
function cleanItems(req, raw) {
  const list = LT.tests();
  const out = [];
  const seen = new Set();
  raw.forEach((r, i) => {
    let code = null;
    let name;
    if (typeof r?.code === 'string' && r.code) {
      const t = list.find((x) => x.code === r.code && !x.hidden);
      if (!t) throw err(req, `Row ${i + 1}: this test is not in the list`, `সারি ${bn(i + 1)}: পরীক্ষাটা তালিকায় নেই`);
      code = t.code;
      name = t.name;
    } else {
      name = str(r?.name, 80);
      if (!name) throw err(req, `Row ${i + 1}: write the test name`, `সারি ${bn(i + 1)}: পরীক্ষার নাম লিখুন`);
      const t = LT.match(name, list);
      if (t) {
        code = t.code;
        name = t.name;
      }
    }
    const price = toInt(r?.price);
    if (price === null) throw err(req, `${name}: write the price`, `${name}: দাম লিখুন`);
    if (Number.isNaN(price) || price < 1 || price > MAX_PRICE) throw err(req, `${name}: check the price`, `${name}: দাম দেখে নিন`);
    const k = code ? `c:${code}` : `n:${LT.norm(name)}`;
    if (seen.has(k)) throw err(req, `${name} is in the list twice`, `${name} তালিকায় দুবার আছে`);
    seen.add(k);
    out.push({ code, name, price, tat: str(r?.tat, 30), on: r?.on !== false });
  });
  return out;
}

/** মালিকের দেখা (সব সারি, বন্ধগুলোও) */
function ownerView(x, lab) {
  return {
    items: (x && x.items) || [],
    discountPct: (x && x.discountPct) || null,
    homeFee: x && Number.isInteger(x.homeFee) ? x.homeFee : null,
    updatedAt: (x && x.updatedAt) || 0,
    homeCollection: !!lab && lab.homeCollection === true,
  };
}

/** সবার দেখা: শুধু চালু পরীক্ষা; বাসার চার্জ শুধু বাসা থেকে নমুনা নিলে; তালিকা না থাকলে null */
function publicView(x, lab) {
  const on = ((x && x.items) || []).filter((i) => i.on !== false);
  if (!on.length) return null;
  return {
    items: on.map((i) => ({ code: i.code || null, name: i.name, price: i.price, tat: i.tat || '' })),
    discountPct: x.discountPct || null,
    homeFee: lab && lab.homeCollection === true && Number.isInteger(x.homeFee) ? x.homeFee : null,
    updatedAt: x.updatedAt || 0,
  };
}

/** এলাকার প্যাথলজিগুলোর (ids: এলাকার পুরো তালিকা — areaLabs) দামের তালিকা (id → ডেটা) — এক মিনিট মেমোরিতে, সেভ করলে মোছে */
async function pricesFor(district, ids) {
  const M = require('./memcache');
  const list = [...new Set(ids)].filter((id) => R.isValidId(id));
  if (!list.length) return new Map();
  const rows = await M.cached(`labprices:${district}`, 60 * 1000, async () => {
    const snaps = await db.getAll(...list.map((id) => db.doc(`labPrices/${id}`)));
    return snaps.filter((s) => s.exists).map((s) => [s.id, s.data()]);
  });
  return new Map(rows);
}

/**
 * প্রেসক্রিপশনের পরীক্ষা (ডাক্তারের লেখা নাম) এই ল্যাবের তালিকায় কোনগুলো, কত টাকা।
 * তালিকা না থাকলে (বা সব বন্ধ) null — "দাম জানা নেই"।
 * আগে LT.load() ডাকতে হয় (ছকের নাম মেলাতে)।
 */
function estimate(tests, x, lab) {
  const on = ((x && x.items) || []).filter((i) => i.on !== false);
  if (!on.length) return null;
  const items = [];
  const missing = [];
  for (const t of tests || []) {
    const m = LT.match(t);
    const n = LT.norm(t);
    const it = on.find((i) => (m && i.code === m.code) || (!i.code && LT.norm(i.name) === n));
    if (it) items.push({ test: t, name: it.name, price: it.price, tat: it.tat || '' });
    else missing.push(t);
  }
  const subtotal = items.reduce((a, i) => a + i.price, 0);
  const d = x.discountPct || 0;
  return {
    items,
    missing,
    have: items.length,
    subtotal,
    discountPct: d || null,
    estimate: Math.round((subtotal * (100 - d)) / 100),
    homeFee: lab && lab.homeCollection === true && Number.isInteger(x.homeFee) ? x.homeFee : null,
    tat: [...new Set(items.map((i) => i.tat).filter(Boolean))].join(' / '),
  };
}

/** অর্ডারে রাখা দাম (পাঠানোর সময়ের) — প্যাথলজি আর রোগী একই হিসাব দেখেন */
function snapshot(e) {
  if (!e) return null;
  return { items: e.items, missing: e.missing, subtotal: e.subtotal, discountPct: e.discountPct, estimate: e.estimate, at: Date.now() };
}

/** ঢাকার এখনকার ঘণ্টা-মিনিট (মিনিটে) */
const dhakaMinutes = (now) => Math.floor(((now + 6 * 3600 * 1000) % DAY) / MIN);

/** রোগীর বাসা থেকে নমুনার অনুরোধ যাচাই — না চাইলে null */
function cleanHome(req, h, lab, now = Date.now()) {
  if (!h || typeof h !== 'object') return null;
  if (!lab || lab.homeCollection !== true) throw err(req, "This lab doesn't collect samples from home", 'এই প্যাথলজি বাসা থেকে নমুনা নেয় না', 409);
  const address = str(h.address, 200);
  if (!address) throw err(req, 'Write the home address', 'বাসার ঠিকানা লিখুন');
  const ph = K.normalizeBd(h.phone);
  if (!ph) throw err(req, 'Enter a mobile number', 'মোবাইল নম্বর দিন');
  if (![0, 1, 2].includes(h.day)) throw err(req, 'Choose the day', 'কবে আসবে বেছে নিন');
  const slot = SLOTS[h.slot] ? h.slot : null;
  if (!slot) throw err(req, 'Choose a time', 'কোন সময়ে আসবে বেছে নিন');
  // আজকের পেরিয়ে যাওয়া সময় নয়
  if (h.day === 0 && dhakaMinutes(now) >= SLOTS[slot][1] * 60) throw err(req, 'This time has passed today — choose another', 'আজকের এই সময় পেরিয়ে গেছে — অন্য সময় বেছে নিন');
  return { address, phone: K.localBd(ph), date: P.dhakaToday(now) + h.day * DAY, slot, note: str(h.note, 120), status: 'requested', requestedAt: now };
}

/** প্যাথলজির দেওয়া সময়: দিন (আজ থেকে ৩ দিনের মধ্যে, UTC মধ্যরাত) আর "HH:MM" (সকাল ৫টা – রাত ১০টা) */
function cleanVisit(req, date, time, now = Date.now()) {
  const today = P.dhakaToday(now);
  const k = Number.isInteger(date) ? (date - today) / DAY : NaN;
  if (!Number.isInteger(k) || k < 0 || k > 3) throw err(req, 'Choose the day', 'দিন বেছে নিন');
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(String(time || ''));
  if (!m || Number(m[1]) < 5 || Number(m[1]) > 21) throw err(req, 'Choose the time', 'সময় বেছে নিন');
  const mins = Number(m[1]) * 60 + Number(m[2]);
  const at = date + mins * MIN - 6 * 3600 * 1000;
  if (at < now - 30 * MIN) throw err(req, 'This time has passed', 'এই সময় পেরিয়ে গেছে');
  return { visitDate: date, time: `${m[1]}:${m[2]}`, at };
}

/** "কাল সকাল ৮টায়" / "tomorrow at 8:00 AM" (নোটিফিকেশনের শিরোনাম) */
function visitWords(date, time, now = Date.now()) {
  const k = Math.round((date - P.dhakaToday(now)) / DAY);
  const [h, mm] = time.split(':').map(Number);
  const part = h < 5 ? 'রাত' : h < 12 ? 'সকাল' : h < 15 ? 'দুপুর' : h < 18 ? 'বিকাল' : h < 20 ? 'সন্ধ্যা' : 'রাত';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  const d = new Date(date);
  const dayBn = k === 0 ? 'আজ' : k === 1 ? 'কাল' : k === 2 ? 'পরশু' : `${bn(d.getUTCDate())} তারিখ`;
  const dayEn = k === 0 ? 'today' : k === 1 ? 'tomorrow' : `on ${d.getUTCDate()}/${d.getUTCMonth() + 1}`;
  return {
    bn: `${dayBn} ${part} ${bn(h12)}টা${mm ? ` ${bn(mm)} মিনিটে` : 'য়'}`,
    en: `${dayEn} at ${h12}:${String(mm).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`,
  };
}

/**
 * অর্ডারের "বাসা থেকে" অংশ। contact: ঠিকানা-ফোন দেখানো যাবে কি না
 * (রোগী/কেয়ারগিভার সবসময়; প্যাথলজি শুধু অর্ডার চালু থাকলে আর বাসায় যাওয়া বাতিল না হলে)।
 */
function homeView(h, contact) {
  if (!h) return null;
  return {
    status: h.status || 'requested',
    date: h.date || 0,
    slot: h.slot || '',
    note: h.note || '',
    fee: Number.isInteger(h.fee) ? h.fee : null,
    visitDate: h.visitDate || 0,
    time: h.time || '',
    at: h.at || 0,
    confirmNote: h.confirmNote || '',
    declineNote: h.declineNote || '',
    ...(contact ? { address: h.address || '', phone: h.phone || '' } : {}),
  };
}

const actions = {
  /** প্যাথলজি (ওয়েব): নিজের পরীক্ষা ও দামের তালিকা */
  async lab_prices_get(req, user) {
    const lab = await myLab(req, user);
    const s = await db.doc(`labPrices/${user.uid}`).get();
    return ownerView(s.exists ? s.data() : null, lab.data());
  },

  /**
   * প্যাথলজি (ওয়েব): তালিকা সেভ — items: [{ code?, name?, price, tat?, on? }], discountPct?, homeFee?
   * পুরো তালিকা একবারে (যা পাঠানো হয় সেটাই থাকে); সব খালি হলে তালিকা মুছে যায়।
   */
  async lab_prices_set(req, user) {
    const lab = await myLab(req, user);
    const b = req.body || {};
    if (!Array.isArray(b.items)) throw err(req, 'Nothing to save', 'সেভ করার কিছু নেই');
    if (b.items.length > MAX_ITEMS) throw err(req, `Up to ${MAX_ITEMS} tests`, `সর্বোচ্চ ${bn(MAX_ITEMS)}টা পরীক্ষা`);
    await LT.load();
    const items = cleanItems(req, b.items);
    const d = toInt(b.discountPct);
    if (Number.isNaN(d) || (d != null && (d < 0 || d > 90))) throw err(req, 'Discount must be 0–90%', 'ছাড় ০–৯০% এর মধ্যে দিন');
    const fee = toInt(b.homeFee);
    if (Number.isNaN(fee) || (fee != null && (fee < 0 || fee > MAX_HOME_FEE))) throw err(req, 'Check the home collection fee', 'বাসা থেকে নমুনার চার্জ দেখে নিন');
    const doc = { items, discountPct: d || null, homeFee: lab.get('homeCollection') === true && fee != null ? fee : null, updatedAt: Date.now() };
    const ref = db.doc(`labPrices/${user.uid}`);
    if (!items.length && !doc.discountPct && doc.homeFee == null) await ref.delete();
    else await ref.set(doc);
    require('./memcache').clear('labprices:');
    require('./memcache').clear('guest:');
    return ownerView(items.length || doc.discountPct || doc.homeFee != null ? doc : null, lab.data());
  },
};

module.exports = { actions, publicView, ownerView, pricesFor, estimate, snapshot, cleanHome, cleanVisit, visitWords, homeView, toInt, SLOTS };
