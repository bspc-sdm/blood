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
//   চাওয়া দিন-সময় পেরিয়ে গেলেও 'requested' থাকলে দেখানোর সময় 'expired' ("সময় পেরিয়ে গেছে") — দুই দিকেই; আলাদা ক্রন লাগে না।
//   রোগী 'requested' থাকতে সময় বদলাতে (lab_home_change) বা বাসা থেকে নেওয়া বাতিল করতে (lab_home_cancel → সাধারণ পরীক্ষা) পারেন।
// serverState/{uid}.labHome: { address, phone, at } — রোগীর শেষ বাসার ঠিকানা-ফোন (পরের বার ফর্মে বসাতে; শুধু সার্ভার পড়ে)।
const { db, FieldValue, HttpError } = require('./firebase');
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

/**
 * নিজে লেখা পরীক্ষার নাম মেলানোর চাবি — বাংলা অক্ষর-অঙ্কও থাকে (LT.norm শুধু a–z0–9 রাখে, তাই বাংলা নাম "" হয়ে
 * যেকোনো বাংলা নামের সাথে মিলে যেত)। ছোট হাতের, বাংলা অঙ্ক = ইংরেজি অঙ্ক, যতিচিহ্ন-ফাঁকা মিলে এক ফাঁকা।
 * খালি চাবি কখনো কিছুর সাথে মেলানো হয় না। ছকের (কোডের) মেলানো আগের মতো LT.match দিয়ে।
 */
function nameKey(v) {
  return String(v || '').normalize('NFC').toLowerCase()
    .replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d)))
    .replace(/[\u200c\u200d]/g, '')
    .replace(/[^\p{L}\p{N}\p{M}]+/gu, ' ')
    .trim();
}

/** ছকে এখন লুকানো পরীক্ষার কোড (অ্যাডমিন লুকালে) — সবার দেখা তালিকা আর হিসাবে আসে না */
const hiddenCodes = () => new Set(LT.tests().filter((t) => t.hidden).map((t) => t.code));

/** চালু আর দেখানো যায় এমন সারি (লুকানো কোডের সারি বাদ — মালিক আবার সেভ করলে নিজে লেখা হিসেবে থাকে) */
function visibleItems(x) {
  const hid = hiddenCodes();
  return ((x && x.items) || []).filter((i) => i.on !== false && !(i.code && hid.has(i.code)));
}

async function myLab(req, user) {
  const s = await db.doc(`labs/${user.uid}`).get();
  if (!s.exists || s.get('status') !== 'approved') throw err(req, 'Only approved labs can do this', 'শুধু অনুমোদিত প্যাথলজি এটা করতে পারে', 403);
  return s;
}

/**
 * মালিকের পাঠানো তালিকা যাচাই: ছকের কোড বা নিজে লেখা নাম, দাম, রিপোর্টের সময়, চালু/বন্ধ; একই পরীক্ষা দুবার নয়।
 * ছকে এখন লুকানো (বা আর নেই) কোড এলে সেভ আটকায় না — নাম দিয়ে নিজে লেখা পরীক্ষা হিসেবে থাকে (kept-এ নাম, মালিককে জানাতে)।
 */
function cleanItems(req, raw) {
  const list = LT.tests();
  const out = [];
  const kept = [];
  const seen = new Set();
  raw.forEach((r, i) => {
    let code = null;
    let name;
    const asked = typeof r?.code === 'string' && r.code ? r.code : null;
    const byCode = asked ? list.find((x) => x.code === asked) : null;
    if (byCode && !byCode.hidden) {
      code = byCode.code;
      name = byCode.name;
    } else {
      name = str(r?.name, 80) || (byCode ? byCode.name : '');
      if (asked && !name) throw err(req, `Row ${i + 1}: this test is not in the list`, `সারি ${bn(i + 1)}: পরীক্ষাটা তালিকায় নেই`);
      if (!name || !nameKey(name)) throw err(req, `Row ${i + 1}: write the test name`, `সারি ${bn(i + 1)}: পরীক্ষার নাম লিখুন`);
      const t = LT.match(name, list);
      if (t) {
        code = t.code;
        name = t.name;
      } else if (asked) kept.push(name);
    }
    const price = toInt(r?.price);
    if (price === null) throw err(req, `${name}: write the price`, `${name}: দাম লিখুন`);
    if (Number.isNaN(price) || price < 1 || price > MAX_PRICE) throw err(req, `${name}: check the price`, `${name}: দাম দেখে নিন`);
    const k = code ? `c:${code}` : `n:${nameKey(name)}`;
    if (seen.has(k)) throw err(req, `${name} is in the list twice`, `${name} তালিকায় দুবার আছে`);
    seen.add(k);
    out.push({ code, name, price, tat: str(r?.tat, 30), on: r?.on !== false });
  });
  return { items: out, kept };
}

/** মালিকের দেখা (সব সারি, বন্ধগুলোও; ছকে লুকানো কোডের সারিতে hidden — আবার সেভ করলে নিজে লেখা হিসেবে থাকবে) */
function ownerView(x, lab) {
  const hid = hiddenCodes();
  return {
    items: ((x && x.items) || []).map((i) => (i.code && hid.has(i.code) ? { ...i, hidden: true } : i)),
    discountPct: (x && x.discountPct) || null,
    homeFee: x && Number.isInteger(x.homeFee) ? x.homeFee : null,
    updatedAt: (x && x.updatedAt) || 0,
    homeCollection: !!lab && lab.homeCollection === true,
  };
}

/** সবার দেখা: শুধু চালু পরীক্ষা (ছকে লুকানো কোড বাদ); বাসার চার্জ শুধু বাসা থেকে নমুনা নিলে; তালিকা না থাকলে null */
function publicView(x, lab) {
  const on = visibleItems(x);
  if (!on.length) return null;
  return {
    items: on.map((i) => ({ code: i.code || null, name: i.name, price: i.price, tat: i.tat || '' })),
    discountPct: x.discountPct || null,
    homeFee: lab && lab.homeCollection === true && Number.isInteger(x.homeFee) ? x.homeFee : null,
    updatedAt: x.updatedAt || 0,
  };
}

/**
 * এলাকার তালিকার জন্য ছোট সারাংশ (পুরো দামের তালিকা নয় — সেটা বিস্তারিত খুললে lab_prices_public):
 * কয়টা পরীক্ষার দাম, সবচেয়ে কম দাম, ছাড়, বাসার চার্জ। তালিকা না থাকলে null।
 */
function publicSummary(x, lab) {
  const v = publicView(x, lab);
  if (!v) return null;
  return { count: v.items.length, from: Math.min(...v.items.map((i) => i.price)), discountPct: v.discountPct, homeFee: v.homeFee, updatedAt: v.updatedAt };
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

/** পরীক্ষার চাবি: ছকে থাকলে কোড, নইলে নাম (বাংলাসহ) — একই পরীক্ষা দুবার গোনা হয় না */
function testKey(t) {
  const m = LT.match(t);
  return { m, n: nameKey(t), k: m ? `c:${m.code}` : `n:${nameKey(t)}` };
}

/** আলাদা পরীক্ষা কয়টা (একই ছকের কোডে মেলা দুটো নাম একটা) */
function countTests(tests) {
  return new Set((tests || []).map((t) => testKey(t).k)).size;
}

/**
 * প্রেসক্রিপশনের পরীক্ষা (ডাক্তারের লেখা নাম) এই ল্যাবের তালিকায় কোনগুলো, কত টাকা।
 * তালিকা না থাকলে (বা সব বন্ধ) null — "দাম জানা নেই"। একটাও না হলে estimate/subtotal null (৳০ নয়), have ০।
 * একই ছকের কোডে মেলা দুটো নাম একবারই গোনা (total = আলাদা পরীক্ষা)।
 * আগে LT.load() ডাকতে হয় (ছকের নাম মেলাতে)।
 */
function estimate(tests, x, lab) {
  const on = visibleItems(x);
  if (!on.length) return null;
  const items = [];
  const missing = [];
  const seen = new Set();
  for (const t of tests || []) {
    const { m, n, k } = testKey(t);
    if (seen.has(k)) continue;
    seen.add(k);
    // ছকের কোডে, নইলে নিজে লেখা নামে (খালি নাম কখনো মেলে না)
    const it = on.find((i) => (m && i.code === m.code) || (!i.code && !!n && nameKey(i.name) === n));
    if (it) items.push({ test: t, name: it.name, price: it.price, tat: it.tat || '' });
    else missing.push(t);
  }
  const homeFee = lab && lab.homeCollection === true && Number.isInteger(x.homeFee) ? x.homeFee : null;
  const d = x.discountPct || 0;
  if (!items.length) {
    return { items, missing, have: 0, total: seen.size, subtotal: null, discountPct: d || null, estimate: null, homeFee, tat: '' };
  }
  const subtotal = items.reduce((a, i) => a + i.price, 0);
  return {
    items,
    missing,
    have: items.length,
    total: seen.size,
    subtotal,
    discountPct: d || null,
    estimate: Math.round((subtotal * (100 - d)) / 100),
    homeFee,
    tat: [...new Set(items.map((i) => i.tat).filter(Boolean))].join(' / '),
  };
}

/** অর্ডারে রাখা দাম (পাঠানোর সময়ের) — প্যাথলজি আর রোগী একই হিসাব দেখেন; একটা পরীক্ষাও না মিললে কিছু রাখা হয় না */
function snapshot(e) {
  if (!e || !e.items.length || e.estimate == null) return null;
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

/** চাওয়া দিন-সময় পেরিয়ে গেছে, অথচ প্যাথলজি এখনো সময় জানায়নি (দেখানোর সময় হিসাব — ক্রন লাগে না) */
function homeExpired(h, now = Date.now()) {
  if (!h || (h.status || 'requested') !== 'requested' || !SLOTS[h.slot] || !Number.isInteger(h.date)) return false;
  return now >= h.date + SLOTS[h.slot][1] * 60 * MIN - 6 * 3600 * 1000;
}

/**
 * অর্ডারের "বাসা থেকে" অংশ। contact: ঠিকানা-ফোন দেখানো যাবে কি না
 * (রোগী/কেয়ারগিভার সবসময়; প্যাথলজি শুধু অর্ডার চালু থাকলে আর বাসায় যাওয়া বাতিল না হলে)।
 * status: requested | confirmed | declined | expired (চাওয়া সময় পেরিয়ে গেছে, এখনো requested)।
 */
function homeView(h, contact, now = Date.now()) {
  if (!h) return null;
  return {
    status: homeExpired(h, now) ? 'expired' : h.status || 'requested',
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

const SLOT_WORDS = {
  early: ['7–9 AM', 'সকাল ৭–৯'],
  morning: ['9 AM–12 PM', 'সকাল ৯–১২'],
  afternoon: ['3–6 PM', 'বিকেল ৩–৬'],
};

/** ঠিকানার ছোট রূপ (নোটিফিকেশনে, লক স্ক্রিনে): বাসা/রোডের নম্বর বাদে এলাকার নাম, তারপর "…" */
function shortAddress(a) {
  const parts = String(a || '').split(/[,،।\n]+/).map((p) => p.trim()).filter(Boolean);
  if (!parts.length) return '';
  const area = parts.find((p) => !/[0-9০-৯]/.test(p)) || parts[0];
  let out = '';
  for (const w of area.split(/\s+/)) {
    if (out && (out + ' ' + w).length > 18) break;
    out = out ? `${out} ${w}` : w;
  }
  return `${out.slice(0, 24)}…`;
}

/**
 * প্যাথলজির নোটিফিকেশনের লাইন (পুরোনো অ্যাপে বাসার ঘরগুলো দেখায় না, তাই দরকারি কথা এখানেই):
 * "বাসা থেকে নমুনা · কাল সকাল ৭–৯ · চরপাড়া…" — ঠিকানা ছোট করে, ফোন নয়।
 */
function homePushLine(h, now = Date.now()) {
  if (!h) return null;
  const k = Math.round((h.date - P.dhakaToday(now)) / DAY);
  const d = new Date(h.date);
  const dayBn = k === 0 ? 'আজ' : k === 1 ? 'কাল' : k === 2 ? 'পরশু' : `${bn(d.getUTCDate())} তারিখ`;
  const dayEn = k === 0 ? 'today' : k === 1 ? 'tomorrow' : k === 2 ? 'day after tomorrow' : `${d.getUTCDate()}/${d.getUTCMonth() + 1}`;
  const [slotEn, slotBn] = SLOT_WORDS[h.slot] || ['', ''];
  const addr = shortAddress(h.address);
  return {
    en: ['Sample from home', `${dayEn} ${slotEn}`.trim(), addr].filter(Boolean).join(' · '),
    bn: ['বাসা থেকে নমুনা', `${dayBn} ${slotBn}`.trim(), addr].filter(Boolean).join(' · '),
  };
}

/** রোগীর শেষ বাসার ঠিকানা-ফোন রেখে দেওয়া (পরের বার ফর্মে আগে থেকে বসে) — serverState, শুধু সার্ভার পড়ে */
async function rememberHome(uid, home) {
  if (!uid || !home) return;
  await db.doc(`serverState/${uid}`).set({ labHome: { address: home.address, phone: home.phone, at: Date.now() } }, { merge: true }).catch(() => {});
}

/** রোগীর শেষ বাসার ঠিকানা-ফোন (না থাকলে null) */
async function lastHome(uid) {
  const s = await db.doc(`serverState/${uid}`).get();
  const h = s.exists ? s.get('labHome') : null;
  return h && h.address ? { address: h.address, phone: h.phone || '' } : null;
}

/**
 * প্যাথলজির অর্ডার থেকে রোগীর বাসার ঠিকানা-ফোন মোছা (প্যাথলজি সরালে / অ্যাকাউন্ট মুছলে)।
 * আগে চালু অর্ডারগুলো, তারপর (openOnly না হলে) বাকি সব — পাতায় পাতায়, শেষ না হওয়া পর্যন্ত।
 */
async function stripHomes(labId, { openOnly = false } = {}) {
  const PAGE = 300;
  const strip = { 'home.address': FieldValue.delete(), 'home.phone': FieldValue.delete() };
  let n = 0;
  const run = async (q) => {
    let last = null;
    for (let page = 0; page < 500; page++) {
      const s = await (last ? q.startAfter(last) : q).limit(PAGE).get();
      const hit = s.docs.filter((d) => d.get('home.address') || d.get('home.phone'));
      if (hit.length) {
        const b = db.batch();
        hit.forEach((d) => b.update(d.ref, strip));
        await b.commit();
        n += hit.length;
      }
      if (s.size < PAGE) break;
      last = s.docs[s.docs.length - 1];
    }
  };
  const base = db.collection('labOrders').where('labId', '==', labId);
  await run(base.where('status', 'in', ['sent', 'received']));
  if (!openOnly) await run(base);
  return n;
}

const actions = {
  /** প্যাথলজি (ওয়েব): নিজের পরীক্ষা ও দামের তালিকা */
  async lab_prices_get(req, user) {
    const lab = await myLab(req, user);
    await LT.load();
    const s = await db.doc(`labPrices/${user.uid}`).get();
    return ownerView(s.exists ? s.data() : null, lab.data());
  },

  /**
   * প্যাথলজি (ওয়েব): তালিকা সেভ — items: [{ code?, name?, price, tat?, on? }], discountPct?, homeFee?
   * পুরো তালিকা একবারে (যা পাঠানো হয় সেটাই থাকে); সব খালি হলে তালিকা মুছে যায়।
   * ছকে লুকানো কোডের সারি নাম দিয়ে নিজে লেখা হিসেবে থাকে — kept: সেই নামগুলো (মালিককে জানাতে)।
   */
  async lab_prices_set(req, user) {
    const lab = await myLab(req, user);
    const b = req.body || {};
    if (!Array.isArray(b.items)) throw err(req, 'Nothing to save', 'সেভ করার কিছু নেই');
    if (b.items.length > MAX_ITEMS) throw err(req, `Up to ${MAX_ITEMS} tests`, `সর্বোচ্চ ${bn(MAX_ITEMS)}টা পরীক্ষা`);
    await LT.load();
    const { items, kept } = cleanItems(req, b.items);
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
    return { ...ownerView(items.length || doc.discountPct || doc.homeFee != null ? doc : null, lab.data()), kept };
  },

  /**
   * সবাই (অতিথিও): একটা প্যাথলজির পুরো পরীক্ষা ও দামের তালিকা — বিস্তারিত খুললে (এলাকার তালিকায় শুধু সারাংশ)।
   * এক মিনিট মেমোরিতে (সেভ করলে মোছে); অতিথির উত্তর api-তেও এক মিনিট থাকে।
   */
  async lab_prices_public(req) {
    const { labId } = req.body || {};
    const nf = () => err(req, 'Not found', 'পাওয়া যায়নি', 404);
    if (!R.isValidId(labId)) throw nf();
    await LT.load();
    const row = await require('./memcache').cached(`labprices:one:${labId}`, 60 * 1000, async () => {
      const [l, p] = await db.getAll(db.doc(`labs/${labId}`), db.doc(`labPrices/${labId}`));
      if (!l.exists || l.get('status') !== 'approved') return null;
      return { lab: { homeCollection: l.get('homeCollection') === true }, x: p.exists ? p.data() : null };
    });
    if (!row) throw nf();
    return { prices: publicView(row.x, row.lab) };
  },
};

module.exports = {
  actions, publicView, publicSummary, ownerView, pricesFor, estimate, snapshot, countTests, nameKey, cleanHome, cleanVisit, visitWords,
  homeView, homeExpired, homePushLine, shortAddress, rememberHome, lastHome, stripHomes, toInt, SLOTS,
};
