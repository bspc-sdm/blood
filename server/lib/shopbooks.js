// ফার্মেসির হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬):
// "দোকানে প্রোডাক্ট এন্ট্রির পাশাপাশি যেনো প্রোডাক্টের কস্টিং প্রাইজ, সেল প্রাইজ, আর ছাড়ের পরিমাণ % এবং এমাউন্টে বসাতে পারে।
//  যদি এগুলো না করে তাহলে সমস্যা নাই, করলে ইনভেন্টরী দেখতে পারবে। প্রফিট লস এনালাইটিক্স দেখতে পারবে।"
//
// পণ্য   shopItems/{shopUid}/items/{id}      — নাম ছাড়া সব ঐচ্ছিক (পরিমাণ, কেনা দাম, বিক্রি দাম, ছাড়, কখন আবার আনতে হবে, মেয়াদ)
// কেনা   shopBooks/{uid}/purchases/{id}      — মাল এল: পরিমাণ বাড়ে, কেনা দাম গড় হয় (weighted average)
// বিক্রি  shopBooks/{uid}/sales/{id}          — কাউন্টারে / প্রেসক্রিপশনে / অনলাইনে: পরিমাণ কমে, লাভ-ক্ষতি
// বদল   shopBooks/{uid}/adjust/{id}         — নষ্ট/মেয়াদ শেষ (কেনা দামে ক্ষতি), গণনা ঠিক করা
// দিন    shopBooks/{uid}/days/{yyyymmdd}     — দিনের যোগফল, একই transaction-এ; হিসাবের পাতা শুধু এগুলো (≤৩১টা) আর পণ্য পড়ে
//
// স্টকের নামের তালিকা (pharmacyStock/{uid}, রোগীর প্রেসক্রিপশন মেলানো) আগের মতোই থাকে: পরিমাণ ০ হলে নাম সরে, ০-র বেশি হলে আসে;
// পরিমাণ না লেখা পণ্যে তালিকাই ঠিক করে "স্টকে আছে" কি না। তালিকার পুরোনো নাম প্রথমবার খুললে পণ্য হয়ে যায়।
// শুধু দোকানের মালিক (pharmacies/{uid} থাকতে হবে); সংগ্রহগুলো শুধু সার্ভার পড়ে-লেখে (firestore.rules-এ নেই = বন্ধ)।
const crypto = require('crypto');
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_ITEMS = 3000; // স্টকের তালিকার সীমার সমান
const MAX_LINES = 100;
const err = (req, en, bn, status = 400) => new HttpError(status, C.tr(req, en, bn));
const notFound = (req) => err(req, 'Not found', 'পাওয়া যায়নি', 404);
const str = (v, n) => (typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, n) : '');
/** rxnet.js-এর stock_set-এর একই চাবি (প্রেসক্রিপশনের নাম মেলানো) */
const key = (name) => String(name || '').trim().toLowerCase().replace(/\s+/g, ' ');
const idOf = (name) => `k${crypto.createHash('sha1').update(key(name)).digest('hex').slice(0, 16)}`;
const r2 = (x) => Math.round((Number(x) + Number.EPSILON) * 100) / 100;
const dayKey = (ms) => R.dhakaDayKey(ms);
/** ঢাকার দিনের শুরু (মিলিসেকেন্ড) */
const dayStart = (ms) => Date.parse(`${dayKey(ms).replace(/(\d{4})(\d\d)(\d\d)/, '$1-$2-$3')}T00:00:00+06:00`);

const UNITS = ['piece', 'strip', 'box', 'bottle', 'tube', 'vial', 'sachet', 'other'];
const SOURCES = ['counter', 'rx', 'online'];

const itemsCol = (uid) => db.collection(`shopItems/${uid}/items`);
const itemRef = (uid, id) => db.doc(`shopItems/${uid}/items/${id}`);
const bookCol = (uid, name) => db.collection(`shopBooks/${uid}/${name}`);
const dayRef = (uid, day) => db.doc(`shopBooks/${uid}/days/${day}`);
const stockRef = (uid) => db.doc(`pharmacyStock/${uid}`);

async function myShop(req, user) {
  if (!user || user.guest || !R.isValidId(user.uid)) throw err(req, 'Please log in', 'লগইন করুন', 401);
  const s = await db.doc(`pharmacies/${user.uid}`).get();
  if (!s.exists) throw err(req, 'Add your pharmacy first', 'আগে আপনার ফার্মেসি যোগ করুন', 403);
  return s;
}

// ---------------------------------------------------------------------------
// লেখা যাচাই
// ---------------------------------------------------------------------------

/** টাকা/পরিমাণ: খালি = null; ঋণাত্মক বা অনেক বড় হলে ভুল */
function numOf(req, v, en, bn, max = 1e7) {
  if (v == null || v === '') return null;
  const n = typeof v === 'number' ? v : Number(String(v).replace(/,/g, ''));
  if (!Number.isFinite(n) || n < 0 || n > max) throw err(req, `Check the ${en}`, `${bn} দেখে নিন`);
  return r2(n);
}
const qtyOf = (req, v) => numOf(req, v, 'quantity', 'পরিমাণ', 100000);
const moneyOf = (req, v, en = 'price', bn = 'দাম') => numOf(req, v, en, bn);

/** ছাড়: { type: 'pct' | 'amt', v } — না দিলে null */
function discOf(req, d) {
  if (d == null || d === '' || (typeof d === 'object' && (d.v == null || d.v === '' || Number(d.v) === 0))) return null;
  if (typeof d !== 'object' || !['pct', 'amt'].includes(d.type)) throw err(req, 'Check the discount', 'ছাড় দেখে নিন');
  const v = numOf(req, d.v, 'discount', 'ছাড়', d.type === 'pct' ? 100 : 1e7);
  return v ? { type: d.type, v } : null;
}

/** মেয়াদ: "2027-03" (মাস/বছর) */
function expiryOf(req, v) {
  if (v == null || v === '') return null;
  if (typeof v !== 'string' || !/^20\d\d-(0[1-9]|1[0-2])$/.test(v)) throw err(req, 'Check the expiry (month/year)', 'মেয়াদ (মাস/বছর) দেখে নিন');
  return v;
}
/** মেয়াদের মাসের শেষ দিন (ঢাকা) */
function expiryEnd(e) {
  if (!e) return null;
  const [y, m] = e.split('-').map(Number);
  return Date.UTC(y, m, 1) - 6 * 3600 * 1000 - 1;
}

/** দিন: না দিলে এখন; ৬০ দিনের বেশি আগে বা ভবিষ্যতে নয় (তারিখ দিলে সেদিন দুপুর) */
function atOf(req, v, now) {
  if (v == null || v === '') return now;
  let t = typeof v === 'number' ? v : NaN;
  if (typeof v === 'string' && /^\d{4}-\d\d-\d\d$/.test(v)) t = Date.parse(`${v}T12:00:00+06:00`);
  if (!Number.isFinite(t)) throw err(req, 'Check the date', 'তারিখ দেখে নিন');
  if (dayKey(t) === dayKey(now)) return Math.min(t, now);
  if (t > now || t < now - 60 * DAY_MS) throw err(req, 'Date must be within the last 60 days', 'তারিখ গত ৬০ দিনের মধ্যে হতে হবে');
  return t;
}

/** প্রতি এককে ছাড় (টাকায়) — দাম না জানলে ০ */
function unitDiscOf(price, disc) {
  if (price == null || !disc) return 0;
  return r2(Math.min(price, disc.type === 'pct' ? (price * disc.v) / 100 : disc.v));
}

// ---------------------------------------------------------------------------
// পণ্যের দেখা আর স্টকের তালিকা
// ---------------------------------------------------------------------------

function itemView(id, x, stockKeys, now = Date.now()) {
  const exp = expiryEnd(x.expiry);
  const qty = typeof x.qty === 'number' ? x.qty : null;
  const lowAt = typeof x.lowAt === 'number' ? x.lowAt : null;
  const price = typeof x.price === 'number' ? x.price : null;
  return {
    id,
    name: x.name,
    generic: x.generic || '',
    form: x.form || '',
    unit: x.unit || '',
    qty,
    cost: typeof x.cost === 'number' ? x.cost : null,
    price,
    disc: x.disc || null,
    // ছাড় বাদে প্রতি এককের দাম (কাউন্টারে নিজে থেকে বসে)
    salePrice: price == null ? null : r2(price - unitDiscOf(price, x.disc)),
    lowAt,
    expiry: x.expiry || null,
    inStock: stockKeys.has(x.key || key(x.name)),
    low: qty != null && lowAt != null && qty <= lowAt,
    expDays: exp == null ? null : Math.floor((exp - now) / DAY_MS),
    lastSaleAt: x.lastSaleAt || null,
    updatedAt: x.updatedAt || 0,
  };
}

const stockKeysOf = (s) => new Set((s && s.exists && s.get('keys')) || []);

/**
 * স্টকের নামের তালিকা বদলানো (rxnet.js-এর stock_set-এর একই আকার: names সাজানো, keys, updatedAt) —
 * পণ্যের হিসাবের একই transaction-এ, যাতে দুটো কখনো আলাদা না হয়। পড়া আগে (t.get), এটা লেখার সময়।
 */
function stockWrite(t, uid, snap, add, remove, req) {
  if (!add.length && !remove.length) return;
  const cur = (snap.exists && snap.get('names')) || [];
  const map = new Map(cur.map((n) => [key(n), n]));
  let changed = false;
  for (const n of add) if (!map.has(key(n))) { map.set(key(n), n); changed = true; }
  for (const n of remove) if (map.delete(key(n))) changed = true;
  if (!changed) return;
  const list = [...map.values()].sort((a, b) => a.localeCompare(b));
  // সীমা পেরোলে চুপচাপ কোনো নাম বাদ নয় (stock_set-এর মতো জানানো) — শুধু নাম বাড়লে; সরানোয় কখনো আটকায় না
  if (list.length > MAX_ITEMS && list.length > cur.length) throw err(req, `Up to ${MAX_ITEMS} medicines`, `সর্বোচ্চ ${MAX_ITEMS}টা ওষুধ`);
  t.set(stockRef(uid), { names: list, keys: list.map(key), updatedAt: Date.now() });
}

/** পরিমাণ বদলালে স্টকের তালিকা: ০ হলে সরে, ০ থেকে বাড়লে আসে (পরিমাণ না জানলে বদলায় না) */
function stockChange(name, before, after, add, remove) {
  if (after == null) return;
  if (after <= 0 && (before == null || before > 0)) remove.push(name);
  else if (after > 0 && (before == null || before <= 0)) add.push(name);
}

/** নতুন পণ্যের ডক */
function newItem(name, extra = {}, now = Date.now()) {
  return {
    name, key: key(name), generic: '', form: '', unit: '', qty: null, cost: null, price: null, disc: null, lowAt: null, expiry: null,
    lastSaleAt: null, createdAt: now, updatedAt: now, ...extra,
  };
}

/** দিনের যোগফলে যোগ (সব increment — আগে পড়তে হয় না) */
function dayAdd(t, uid, day, add) {
  const inc = (o) => {
    const out = {};
    for (const [k, v] of Object.entries(o)) {
      if (v && typeof v === 'object' && !Array.isArray(v)) out[k] = inc(v);
      else if (typeof v === 'number') out[k] = FieldValue.increment(r2(v));
      else out[k] = v;
    }
    return out;
  };
  t.set(dayRef(uid, day), { day, ...inc(add), updatedAt: Date.now() }, { merge: true });
}

// ---------------------------------------------------------------------------
// বিক্রির হিসাব
// ---------------------------------------------------------------------------

/**
 * লাইন → মোট (ছাড়, নিট, কেনা দাম, লাভ)। দাম না জানলে টাকার হিসাবে নয় (শুধু পরিমাণ);
 * কেনা দাম না জানলে লাভে ধরা হয় না (netCosted শুধু কেনা দাম জানা লাইনের নিট — মার্জিন সেটা দিয়ে)।
 * billDisc: পুরো বিলে ছাড় (% বা টাকা) — লাইনের ছাড়ের পরের দামের অনুপাতে ভাগ।
 */
function priceSale(rawLines, billDisc) {
  const lines = rawLines.map((l) => {
    const priced = l.unitPrice != null;
    const gross = priced ? r2(l.qty * l.unitPrice) : 0;
    const ld = priced ? r2(Math.min(gross, l.qty * (l.unitDisc || 0))) : 0;
    return { ...l, unitDisc: priced ? l.unitDisc || 0 : 0, priced, gross, lineDisc: ld, after: r2(gross - ld) };
  });
  const sumAfter = r2(lines.reduce((a, l) => a + l.after, 0));
  let bd = 0;
  if (billDisc && sumAfter > 0) bd = r2(Math.min(sumAfter, billDisc.type === 'pct' ? (sumAfter * billDisc.v) / 100 : billDisc.v));
  // অনুপাতে ভাগ (কোনো লাইনের ভাগ তার ছাড়ের পরের দামের বেশি নয়); পয়সার গরমিল শেষ থেকে যে লাইনে জায়গা আছে সেখানে —
  // কোনো লাইনের নিট কখনো ঋণাত্মক নয়, আর ভাগের যোগফল ঠিক বিলের ছাড়
  const shares = lines.map((l) => (bd > 0 && l.after > 0 ? Math.min(l.after, r2((bd * l.after) / sumAfter)) : 0));
  let left = r2(bd - shares.reduce((a, v) => a + v, 0));
  for (let i = lines.length - 1; i >= 0 && left !== 0; i--) {
    const move = left > 0 ? Math.min(r2(lines[i].after - shares[i]), left) : -Math.min(shares[i], -left);
    shares[i] = r2(shares[i] + move);
    left = r2(left - move);
  }
  const out = lines.map((l, i) => {
    const share = shares[i];
    const net = r2(l.after - share);
    const cost = l.unitCost != null ? r2(l.qty * l.unitCost) : null;
    const profit = l.priced && cost != null ? r2(net - cost) : null;
    return {
      itemId: l.itemId, name: l.name, qty: l.qty, unitPrice: l.priced ? l.unitPrice : null, unitDisc: l.unitDisc,
      discount: r2(l.lineDisc + share), net, unitCost: l.unitCost ?? null, cost, profit, ...(l.i != null ? { i: l.i } : {}),
    };
  });
  const sum = (f) => r2(out.reduce((a, l) => a + (f(l) || 0), 0));
  const gross = r2(lines.reduce((a, l) => a + l.gross, 0));
  return {
    lines: out,
    totals: {
      gross,
      discount: r2(lines.reduce((a, l) => a + l.lineDisc, 0) + bd),
      billDiscount: bd,
      net: sum((l) => l.net),
      cost: sum((l) => (l.profit != null ? l.cost : 0)),
      profit: sum((l) => l.profit),
      netCosted: sum((l) => (l.profit != null ? l.net : 0)),
      unpriced: out.filter((l) => l.unitPrice == null).length,
    },
  };
}

/** বিক্রির দিনের যোগফলে কী যোগ হবে (sign = -1 হলে বাদ — বিক্রি মুছলে); ডেমোও এটা দিয়েই দিন বানায় */
function saleDayDelta(sale, sign = 1) {
  const s = (v) => sign * (v || 0);
  const items = {};
  for (const l of sale.lines) {
    const cur = items[l.itemId] || { n: l.name, q: 0, net: 0, profit: 0, neg: 0, nq: 0 };
    cur.q += s(l.qty);
    cur.net += s(l.net);
    if (l.profit != null) cur.profit += s(l.profit);
    if (l.profit != null && l.profit < 0) { cur.neg += s(l.profit); cur.nq += s(l.qty); }
    items[l.itemId] = cur;
  }
  const T = sale.totals;
  return {
    sales: s(1), gross: s(T.gross), discount: s(T.discount), net: s(T.net), cost: s(T.cost), profit: s(T.profit), nc: s(T.netCosted),
    src: { [sale.source]: { n: s(1), net: s(T.net) } },
    items,
  };
}
const saleDayAdd = (t, uid, sale, sign = 1) => dayAdd(t, uid, sale.day, saleDayDelta(sale, sign));

/** প্রেসক্রিপশনের ওষুধের নাম → দোকানের পণ্য: হুবহু নাম, নইলে একটাই মেলে এমন "নাম + শক্তি" */
function matchItem(name, list) {
  const k = key(name);
  const exact = list.find((x) => x.key === k);
  if (exact) return exact;
  const near = list.filter((x) => x.key.startsWith(`${k} `) || k.startsWith(`${x.key} `));
  return near.length === 1 ? near[0] : null;
}

async function rxByCode(req, code) {
  const c = String(code || '').trim().toUpperCase().replace(/[\s-]/g, '');
  if (!require('./prescriptions').CODE_RE.test(c)) throw err(req, 'Check the code on the prescription', 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
  const snap = await db.collection('prescriptions').where('code', '==', c).limit(1).get();
  if (snap.empty) throw err(req, 'No prescription with this code', 'এই কোডের কোনো প্রেসক্রিপশন নেই', 404);
  return snap.docs[0];
}

/** দোকানের দেওয়া একটা ভাগ (rx_dispense-এর লেখা) — at না দিলে এই দোকানের শেষটা */
function dispensingOf(rx, uid, at) {
  const list = (rx.get('dispensed') || []).filter((d) => d.by === uid);
  if (at == null) return list[list.length - 1] || null;
  return list.find((d) => d.at === at) || null;
}

/** অনলাইন অর্ডারের মাধ্যমে দেওয়া কি না (অর্ডার "দেওয়া হয়েছে" হয়েছিল এই ভাগের সময়ে) */
async function sourceOf(rxId, uid, at) {
  const orders = await db.collection('rxOrders').where('rxId', '==', rxId).limit(50).get();
  return orders.docs.some((o) => o.get('pharmacyId') === uid && o.get('mode') === 'online' && o.get('status') === 'done'
    && Math.abs((o.get('doneAt') || 0) - at) < 10 * 60 * 1000) ? 'online' : 'rx';
}

const rxSaleId = (rxId, at) => `rx_${rxId}_${at}`;

// ---------------------------------------------------------------------------
// কাজগুলো
// ---------------------------------------------------------------------------

/** সব পণ্য (স্টকের তালিকার পুরোনো নামগুলো প্রথমবার পণ্য হয়ে যায়) */
async function loadItems(uid) {
  const [snap, stock] = await Promise.all([itemsCol(uid).limit(MAX_ITEMS).get(), stockRef(uid).get()]);
  const rows = snap.docs.map((d) => ({ id: d.id, x: d.data() }));
  const have = new Set(rows.map((r) => r.x.key || key(r.x.name)));
  const names = (stock.exists && stock.get('names')) || [];
  // আগের কোনো পণ্যের ঘরে কখনো লেখা নয়: নাম বদলানো পণ্যের id পুরোনো নামের (idOf) — সেই নাম আবার তালিকায় এলে
  // ওই id-তে ফাঁকা পণ্য বসালে আসল পণ্যটা ("Napa 500") মুছে যেত। তাই চাবি আর id দুটোই দেখে, আর
  // transaction-এ পড়ে না থাকলে তবেই তৈরি (একই সময়ে কেনা/বিক্রিতে তৈরি হওয়া পণ্যের উপর লেখা হয় না)।
  const ids = new Set(rows.map((r) => r.id));
  const seen = new Set();
  const missing = names.filter((n) => {
    const k = key(n);
    if (have.has(k) || seen.has(k) || ids.has(idOf(n))) return false;
    seen.add(k);
    return true;
  }).slice(0, Math.max(0, MAX_ITEMS - rows.length));
  if (missing.length) {
    const now = Date.now();
    for (let i = 0; i < missing.length; i += 400) {
      const part = missing.slice(i, i + 400);
      const made = await db.runTransaction(async (t) => {
        const snaps = await t.getAll(...part.map((n) => itemRef(uid, idOf(n))));
        return part.map((n, k) => {
          const id = idOf(n);
          if (snaps[k].exists) return { id, x: snaps[k].data() };
          const x = newItem(n, {}, now);
          t.set(itemRef(uid, id), x);
          return { id, x };
        });
      });
      rows.push(...made);
    }
  }
  return { rows, stockKeys: stockKeysOf(stock) };
}

const sortItems = (list) => list.sort((a, b) => a.name.localeCompare(b.name));

const actions = {
  /** পণ্য ও স্টক */
  async books_items(req, user) {
    await myShop(req, user);
    const { rows, stockKeys } = await loadItems(user.uid);
    const now = Date.now();
    return { items: sortItems(rows.map((r) => itemView(r.id, r.x, stockKeys, now))), units: UNITS };
  },

  /**
   * পণ্য যোগ/বদল: name (তালিকা থেকে বা নিজে লেখা) ছাড়া সব ঐচ্ছিক।
   * আগের পণ্যে পরিমাণ বদলালে "গণনা ঠিক করা" হিসেবে লেখা থাকে।
   */
  async books_item_save(req, user) {
    await myShop(req, user);
    const b = req.body || {};
    const name = str(b.name, 100);
    if (!name) throw err(req, 'Write the product name', 'পণ্যের নাম লিখুন');
    const editId = b.id == null || b.id === '' ? null : b.id;
    if (editId != null && !R.isValidId(editId)) throw notFound(req);
    const price = moneyOf(req, b.price, 'sale price', 'বিক্রি দাম');
    const disc = discOf(req, b.disc);
    if (disc && disc.type === 'amt' && price != null && disc.v > price) throw err(req, 'Discount is more than the price', 'ছাড় দামের চেয়ে বেশি');
    const data = {
      name, key: key(name), generic: str(b.generic, 100), form: str(b.form, 40),
      unit: UNITS.includes(b.unit) ? b.unit : '',
      cost: moneyOf(req, b.cost, 'cost price', 'কেনা দাম'), price, disc,
      lowAt: qtyOf(req, b.lowAt), expiry: expiryOf(req, b.expiry),
    };
    const qtyGiven = Object.prototype.hasOwnProperty.call(b, 'qty');
    const qty = qtyGiven ? qtyOf(req, b.qty) : undefined;
    const id = editId || idOf(name);
    // নাম বদলালে একই নামের অন্য পণ্য নয়
    const same = await itemsCol(user.uid).where('key', '==', data.key).limit(2).get();
    if (same.docs.some((d) => d.id !== id)) throw err(req, 'A product with this name is already in your list', 'এই নামে আগেই একটা পণ্য আছে', 409);
    if (!editId) {
      const count = (await itemsCol(user.uid).count().get()).data().count;
      if (count >= MAX_ITEMS) throw err(req, `Up to ${MAX_ITEMS} products`, `সর্বোচ্চ ${MAX_ITEMS}টা পণ্য`);
    }
    const now = Date.now();
    const ref = itemRef(user.uid, id);
    const out = await db.runTransaction(async (t) => {
      const [s, st] = [await t.get(ref), await t.get(stockRef(user.uid))];
      if (editId && !s.exists) throw notFound(req);
      if (!editId && s.exists) throw err(req, 'A product with this name is already in your list', 'এই নামে আগেই একটা পণ্য আছে', 409);
      const add = [];
      const remove = [];
      let next;
      if (!s.exists) {
        next = newItem(name, { ...data, qty: qty ?? null }, now);
        // নতুন পণ্য: পরিমাণ না দিলে "স্টকে আছে" (আগের মতো শুধু নাম)
        if (next.qty == null || next.qty > 0) add.push(name);
        t.set(ref, next);
      } else {
        const cur = s.data();
        next = { ...cur, ...data, ...(qtyGiven ? { qty } : {}), updatedAt: now };
        if (cur.key !== data.key && stockKeysOf(st).has(cur.key)) { remove.push(cur.name); add.push(name); }
        if (qtyGiven && qty !== (cur.qty ?? null)) {
          stockChange(name, cur.qty ?? null, qty, add, remove);
          if (qty != null) {
            const a = bookCol(user.uid, 'adjust').doc();
            t.set(a, {
              kind: 'count', itemId: id, name, before: cur.qty ?? null, after: qty, delta: r2(qty - (cur.qty || 0)), unitCost: cur.cost ?? null,
              loss: 0, reason: '', at: now, day: dayKey(now), createdAt: now,
            });
            dayAdd(t, user.uid, dayKey(now), { adjusts: 1 });
          }
        }
        t.set(ref, next);
      }
      stockWrite(t, user.uid, st, add, remove.filter((n) => !add.includes(n)), req);
      const keys = stockKeysOf(st);
      add.forEach((n) => keys.add(key(n)));
      remove.filter((n) => !add.includes(n)).forEach((n) => keys.delete(key(n)));
      return itemView(id, next, keys, now);
    });
    return { item: out };
  },

  /** পণ্য মুছে ফেলা (স্টকের তালিকা থেকেও); আগের কেনা-বেচার হিসাব থাকে */
  async books_item_delete(req, user) {
    await myShop(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    await db.runTransaction(async (t) => {
      const [s, st] = [await t.get(itemRef(user.uid, id)), await t.get(stockRef(user.uid))];
      if (!s.exists) return;
      stockWrite(t, user.uid, st, [], [s.get('name')], req);
      t.delete(itemRef(user.uid, id));
    });
    return { ok: true };
  },

  /** "স্টকে আছে/নেই" হাতে (পরিমাণ না রাখা দোকানের জন্য — আগের তালিকার মতো) */
  async books_item_stock(req, user) {
    await myShop(req, user);
    const { id } = req.body || {};
    const on = req.body?.on === true;
    if (!R.isValidId(id)) throw notFound(req);
    return db.runTransaction(async (t) => {
      const [s, st] = [await t.get(itemRef(user.uid, id)), await t.get(stockRef(user.uid))];
      if (!s.exists) throw notFound(req);
      const name = s.get('name');
      stockWrite(t, user.uid, st, on ? [name] : [], on ? [] : [name], req);
      const keys = stockKeysOf(st);
      if (on) keys.add(key(name)); else keys.delete(key(name));
      return { item: itemView(id, s.data(), keys) };
    });
  },

  // ======================= মাল কেনা =======================
  /**
   * lines: [{ itemId | name, qty, unitCost?, expiry?, price? }] — পরিমাণ বাড়ে, কেনা দাম গড় হয়,
   * মেয়াদ সবচেয়ে কাছেরটা থাকে, price দিলে নতুন বিক্রি দাম (MRP)। নতুন নাম হলে পণ্য তৈরি।
   */
  async books_purchase_add(req, user) {
    await myShop(req, user);
    const b = req.body || {};
    const now = Date.now();
    const at = atOf(req, b.at, now);
    const raw = (Array.isArray(b.lines) ? b.lines : []).slice(0, MAX_LINES + 1);
    if (raw.length > MAX_LINES) throw err(req, `Up to ${MAX_LINES} lines`, `সর্বোচ্চ ${MAX_LINES}টা লাইন`);
    const lines = [];
    for (const l of raw) {
      const qty = qtyOf(req, l?.qty);
      if (!qty) continue;
      const id = R.isValidId(l?.itemId) ? l.itemId : str(l?.name, 100) ? idOf(str(l.name, 100)) : null;
      if (!id) continue;
      lines.push({ id, name: str(l?.name, 100), qty, unitCost: moneyOf(req, l?.unitCost, 'cost price', 'কেনা দাম'), expiry: expiryOf(req, l?.expiry), price: moneyOf(req, l?.price, 'sale price', 'বিক্রি দাম') });
    }
    if (!lines.length) throw err(req, 'Add at least one product with quantity', 'অন্তত একটা পণ্য আর পরিমাণ দিন');
    const ids = [...new Set(lines.map((l) => l.id))];
    const ref = bookCol(user.uid, 'purchases').doc();
    const out = await db.runTransaction(async (t) => {
      const snaps = await t.getAll(...ids.map((id) => itemRef(user.uid, id)));
      const st = await t.get(stockRef(user.uid));
      const cur = new Map(snaps.map((s, i) => [ids[i], s.exists ? s.data() : null]));
      const keys = stockKeysOf(st);
      const add = [];
      const remove = [];
      const saved = [];
      // মুছলে হুবহু ফেরানোর জন্য: কেনার আগে প্রতিটা পণ্যের পরিমাণ (null = লেখা ছিল না), কেনা দাম, বিক্রি দাম, মেয়াদ, স্টকের তালিকায় ছিল কি না
      const undo = {};
      for (const l of lines) {
        let x = cur.get(l.id);
        if (!undo[l.id]) {
          undo[l.id] = x
            ? { qty: x.qty ?? null, cost: x.cost ?? null, price: x.price ?? null, expiry: x.expiry ?? null, inStock: keys.has(x.key || key(x.name)) }
            // এই কেনাতেই নতুন পণ্য: গণনা ০ থেকে শুরু
            : { qty: 0, cost: null, price: null, expiry: null, inStock: false };
        }
        if (!x) {
          if (!l.name) throw notFound(req);
          x = newItem(l.name, {}, now);
        }
        const before = x.qty ?? null;
        const have = before || 0;
        const q = r2(have + l.qty);
        const cost = l.unitCost == null ? x.cost ?? null
          : x.cost != null && have > 0 ? r2((have * x.cost + l.qty * l.unitCost) / q) : l.unitCost;
        const expiry = l.expiry ? (x.expiry && have > 0 && x.expiry < l.expiry ? x.expiry : l.expiry) : x.expiry ?? null;
        stockChange(x.name, before, q, add, remove);
        x = { ...x, qty: q, cost, expiry, ...(l.price != null ? { price: l.price } : {}), updatedAt: now };
        cur.set(l.id, x);
        saved.push({ itemId: l.id, name: x.name, qty: l.qty, unitCost: l.unitCost, expiry: l.expiry, total: l.unitCost != null ? r2(l.qty * l.unitCost) : null });
      }
      for (const id of ids) {
        const x = cur.get(id);
        t.set(itemRef(user.uid, id), x);
        // কেনার পরের দাম/মেয়াদ — মোছার সময় এর পরে কেউ হাতে বদলে থাকলে সেটা রাখা হয়
        undo[id].after = { cost: x.cost ?? null, price: x.price ?? null, expiry: x.expiry ?? null };
      }
      stockWrite(t, user.uid, st, add, remove, req);
      const doc = {
        at, day: dayKey(at), supplier: str(b.supplier, 80), note: str(b.note, 200), lines: saved, undo,
        total: r2(saved.reduce((a, l) => a + (l.total || 0), 0)), createdAt: now,
      };
      t.set(ref, doc);
      dayAdd(t, user.uid, doc.day, { purchases: 1, bought: doc.total });
      return doc;
    });
    const { undo, ...shown } = out;
    return { id: ref.id, purchase: { id: ref.id, ...shown } };
  },

  /** কেনার তালিকা (গত ৯০ দিন) */
  async books_purchases(req, user) {
    await myShop(req, user);
    // নতুনগুলো আগে, সীমার মধ্যে (সীমা আগে কেটে পরে সাজালে নতুনগুলোই বাদ পড়তে পারত)
    const snap = await bookCol(user.uid, 'purchases').where('at', '>=', Date.now() - 90 * DAY_MS).orderBy('at', 'desc').limit(200).get();
    return { items: snap.docs.map((d) => ({ id: d.id, ...d.data() })).map(({ demo, undo, ...x }) => x) };
  },

  /** ভুল করে লেখা কেনা মোছা: পরিমাণ ফিরে যায় (গড় কেনা দাম যেমন আছে তেমন) */
  async books_purchase_delete(req, user) {
    await myShop(req, user);
    const { id } = req.body || {};
    if (!R.isValidId(id)) throw notFound(req);
    const ref = db.doc(`shopBooks/${user.uid}/purchases/${id}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw notFound(req);
      const p = s.data();
      const ids = [...new Set((p.lines || []).map((l) => l.itemId))];
      const snaps = ids.length ? await t.getAll(...ids.map((i) => itemRef(user.uid, i))) : [];
      const st = await t.get(stockRef(user.uid));
      const add = [];
      const remove = [];
      const same = (a, c) => (a ?? null) === (c ?? null);
      snaps.forEach((it, k) => {
        if (!it.exists) return;
        const x = it.data();
        const q = (p.lines || []).filter((l) => l.itemId === ids[k]).reduce((a, l) => a + l.qty, 0);
        const u = p.undo && p.undo[ids[k]];
        if (!u) {
          // আগের (undo ছাড়া) কেনা: শুধু পরিমাণ কমে
          if (x.qty == null) return;
          const nq = r2(Math.max(0, x.qty - q));
          stockChange(x.name, x.qty, nq, add, remove);
          t.update(it.ref, { qty: nq, updatedAt: Date.now() });
          return;
        }
        const up = { updatedAt: Date.now() };
        if (u.qty == null) {
          // কেনার আগে পরিমাণ লেখা ছিল না — আবার "লেখা নেই", স্টকের তালিকায় আগের মতো
          up.qty = null;
          if (u.inStock) add.push(x.name); else remove.push(x.name);
        } else if (x.qty != null) {
          up.qty = r2(Math.max(0, x.qty - q));
          stockChange(x.name, x.qty, up.qty, add, remove);
        }
        // এই কেনায় বদলানো কেনা দাম/বিক্রি দাম/মেয়াদ আগেরটায় — পরে হাতে বদলানো হলে সেটাই থাকে
        for (const f of ['cost', 'price', 'expiry']) if (u.after && same(x[f], u.after[f]) && !same(x[f], u[f])) up[f] = u[f] ?? null;
        t.update(it.ref, up);
      });
      stockWrite(t, user.uid, st, add, remove, req);
      t.delete(ref);
      dayAdd(t, user.uid, p.day, { purchases: -1, bought: -(p.total || 0) });
    });
    return { ok: true };
  },

  // ======================= বিক্রি =======================
  /**
   * কাউন্টারে বিক্রি: lines [{ itemId | name, qty, unitPrice?, unitDisc? }] (দাম না দিলে পণ্যের বিক্রি দাম, ছাড় না দিলে পণ্যের ছাড়);
   * billDisc { type: 'pct'|'amt', v } — পুরো বিলে। প্রেসক্রিপশনে: source 'rx' + rx { code, at } + lines [{ i, itemId?, unitPrice?, unitDisc? }] —
   * পরিমাণ দোকানের লেখা "যা দিলাম" থেকে (বদলানো যায় না), একই ভাগ দুবার নয়।
   */
  async books_sale_add(req, user) {
    await myShop(req, user);
    const b = req.body || {};
    const now = Date.now();
    const isRx = b.source === 'rx' || b.source === 'online' || (b.rx && typeof b.rx === 'object');
    const billDisc = discOf(req, b.billDisc);
    let at = atOf(req, b.at, now);
    let source = 'counter';
    let ref = bookCol(user.uid, 'sales').doc();
    let rxInfo = null;
    let want = [];
    if (isRx) {
      const d = await rxByCode(req, b.rx?.code);
      const dAt = Number.isInteger(b.rx?.at) ? b.rx.at : null;
      const disp = dispensingOf(d, user.uid, dAt);
      if (!disp) throw err(req, "You haven't given medicines from this prescription", 'এই প্রেসক্রিপশন থেকে আপনি ওষুধ দেননি', 404);
      at = disp.at;
      source = await sourceOf(d.id, user.uid, disp.at);
      ref = db.doc(`shopBooks/${user.uid}/sales/${rxSaleId(d.id, disp.at)}`);
      rxInfo = { rxId: d.id, code: d.get('code'), at: disp.at, patient: d.get('patient.name') || '' };
      const meds = d.get('medicines') || [];
      const given = new Map();
      for (const l of Array.isArray(b.lines) ? b.lines.slice(0, MAX_LINES) : []) if (Number.isInteger(l?.i)) given.set(l.i, l);
      want = (disp.items || []).filter((it) => meds[it.i]).map((it) => {
        const g = given.get(it.i) || {};
        return { i: it.i, name: meds[it.i].name, qty: it.qty, itemId: R.isValidId(g.itemId) ? g.itemId : null, unitPrice: g.unitPrice, unitDisc: g.unitDisc, given: given.has(it.i) };
      });
    } else {
      const raw = (Array.isArray(b.lines) ? b.lines : []).slice(0, MAX_LINES + 1);
      if (raw.length > MAX_LINES) throw err(req, `Up to ${MAX_LINES} lines`, `সর্বোচ্চ ${MAX_LINES}টা লাইন`);
      for (const l of raw) {
        const qty = qtyOf(req, l?.qty);
        if (!qty) continue;
        const nm = str(l?.name, 100);
        const itemId = R.isValidId(l?.itemId) ? l.itemId : nm ? idOf(nm) : null;
        if (!itemId) continue;
        want.push({ name: nm, qty, itemId, unitPrice: l?.unitPrice, unitDisc: l?.unitDisc, given: true });
      }
    }
    if (!want.length) throw err(req, 'Add at least one product with quantity', 'অন্তত একটা পণ্য আর পরিমাণ দিন');
    // প্রেসক্রিপশনের নাম → পণ্য (দোকান বেছে না দিলে)
    if (isRx && want.some((w) => !w.itemId)) {
      const { rows } = await loadItems(user.uid);
      const list = rows.map((r) => ({ id: r.id, key: r.x.key || key(r.x.name) }));
      for (const w of want) {
        if (w.itemId) continue;
        const m = matchItem(w.name, list);
        // দোকানের তালিকায় মেলে না: শুধু নাম দিয়ে বিক্রির লাইন — নতুন (ফাঁকা) পণ্য তৈরি হয় না, কোনো পণ্যের পরিমাণেও হাত পড়ে না
        if (m) w.itemId = m.id;
        else { w.itemId = `x${idOf(w.name).slice(1)}`; w.noItem = true; }
      }
    }
    const ids = [...new Set(want.filter((w) => !w.noItem).map((w) => w.itemId))];
    const sale = await db.runTransaction(async (t) => {
      if (isRx && (await t.get(ref)).exists) throw err(req, 'This sale is already in your books', 'এই বিক্রি আগেই হিসাবে তোলা হয়েছে', 409);
      const snaps = ids.length ? await t.getAll(...ids.map((id) => itemRef(user.uid, id))) : [];
      const st = await t.get(stockRef(user.uid));
      const cur = new Map(snaps.map((s, i) => [ids[i], s.exists ? s.data() : null]));
      const lines = want.map((w) => {
        let x = w.noItem ? { name: w.name, price: null, cost: null, disc: null } : cur.get(w.itemId);
        if (!x) {
          if (!w.name) throw notFound(req);
          x = newItem(w.name, {}, now);
          cur.set(w.itemId, x);
        }
        const unitPrice = w.given && w.unitPrice !== undefined ? moneyOf(req, w.unitPrice) : x.price ?? null;
        const unitDisc = unitPrice == null ? 0
          : w.given && w.unitDisc !== undefined ? Math.min(unitPrice, moneyOf(req, w.unitDisc, 'discount', 'ছাড়') || 0)
            : unitDiscOf(unitPrice, x.disc);
        return { i: w.i, itemId: w.itemId, name: x.name, qty: w.qty, unitPrice, unitDisc, unitCost: x.cost ?? null };
      });
      const priced = priceSale(lines, billDisc);
      want.forEach((w, k) => { if (w.noItem) priced.lines[k].noItem = true; });
      const add = [];
      const remove = [];
      const sold = new Map();
      for (const l of priced.lines) if (!l.noItem) sold.set(l.itemId, (sold.get(l.itemId) || 0) + l.qty);
      // আসলে কতটা কমল (পণ্যপ্রতি) — মুছলে ঠিক এটুকুই ফেরে
      const decOf = new Map();
      for (const [id, q] of sold) {
        const x = cur.get(id);
        const before = x.qty ?? null;
        // হিসাবের চেয়ে বেশি বিক্রি হলে ০ (দোকান পরে গণনা ঠিক করবে) — বিক্রি আটকায় না
        const nq = before == null ? null : r2(Math.max(0, before - q));
        decOf.set(id, before == null ? 0 : r2(before - nq));
        stockChange(x.name, before, nq, add, remove);
        t.set(itemRef(user.uid, id), { ...x, qty: nq, lastSaleAt: Math.max(x.lastSaleAt || 0, at), updatedAt: now });
      }
      // লাইনে ভাগ করে রাখা (একই পণ্য কয়েক লাইনে থাকলে ক্রমে)
      for (const l of priced.lines) {
        const left = decOf.get(l.itemId) || 0;
        l.dec = r2(Math.min(l.qty, left));
        if (decOf.has(l.itemId)) decOf.set(l.itemId, r2(left - l.dec));
      }
      stockWrite(t, user.uid, st, add, remove, req);
      const doc = {
        at, day: dayKey(at), source, lines: priced.lines, totals: priced.totals, billDisc: billDisc || null,
        ...(rxInfo ? { rx: rxInfo } : {}), customer: str(b.customer, 60), createdAt: now,
      };
      t.set(ref, doc);
      saleDayAdd(t, user.uid, doc);
      return doc;
    });
    return { id: ref.id, sale: { id: ref.id, ...sale } };
  },

  /**
   * "যা দিলাম লিখে রাখুন"-এর পর: এই ভাগটা বিক্রির হিসাবে তোলার আগে দেখা — প্রতিটা ওষুধে মেলানো পণ্য, দাম, ছাড়, কেনা দাম।
   * at না দিলে এই দোকানের শেষ ভাগ।
   */
  async books_rx_preview(req, user) {
    await myShop(req, user);
    const d = await rxByCode(req, req.body?.code);
    const disp = dispensingOf(d, user.uid, Number.isInteger(req.body?.at) ? req.body.at : null);
    if (!disp) throw err(req, "You haven't given medicines from this prescription", 'এই প্রেসক্রিপশন থেকে আপনি ওষুধ দেননি', 404);
    const meds = d.get('medicines') || [];
    const [{ rows, stockKeys }, done, source] = await Promise.all([
      loadItems(user.uid), db.doc(`shopBooks/${user.uid}/sales/${rxSaleId(d.id, disp.at)}`).get(), sourceOf(d.id, user.uid, disp.at),
    ]);
    const list = rows.map((r) => ({ id: r.id, key: r.x.key || key(r.x.name), x: r.x }));
    const lines = (disp.items || []).filter((it) => meds[it.i]).map((it) => {
      const m = matchItem(meds[it.i].name, list);
      const v = m ? itemView(m.id, m.x, stockKeys) : null;
      return {
        i: it.i, name: meds[it.i].name, qty: it.qty, itemId: m ? m.id : null, itemName: v ? v.name : '',
        unitPrice: v ? v.price : null, unitDisc: v && v.price != null ? r2(v.price - v.salePrice) : 0, unitCost: v ? v.cost : null, stockQty: v ? v.qty : null,
      };
    });
    return { code: d.get('code'), at: disp.at, source, recorded: done.exists, patient: d.get('patient.name') || '', lines };
  },

  /** বিক্রির তালিকা — period ('today' | '7d' | '30d' | 'month'), না দিলে ৭ দিন */
  async books_sales(req, user) {
    await myShop(req, user);
    const { start } = periodOf(req.body?.period || '7d');
    const snap = await bookCol(user.uid, 'sales').where('at', '>=', start).orderBy('at', 'desc').limit(300).get();
    return { items: snap.docs.map((d) => ({ id: d.id, ...d.data() })).map(({ demo, ...x }) => x) };
  },

  /** ভুল বিক্রি মোছা: পরিমাণ আর দিনের হিসাব ফিরে যায় (প্রেসক্রিপশনেরটা আবার তোলা যায়) */
  async books_sale_delete(req, user) {
    await myShop(req, user);
    const { id } = req.body || {};
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(id)) throw notFound(req);
    const ref = db.doc(`shopBooks/${user.uid}/sales/${id}`);
    await db.runTransaction(async (t) => {
      const s = await t.get(ref);
      if (!s.exists) throw notFound(req);
      const sale = s.data();
      const ids = [...new Set((sale.lines || []).filter((l) => !l.noItem).map((l) => l.itemId))];
      const snaps = ids.length ? await t.getAll(...ids.map((i) => itemRef(user.uid, i))) : [];
      const st = await t.get(stockRef(user.uid));
      const add = [];
      const remove = [];
      snaps.forEach((it, k) => {
        if (!it.exists || it.get('qty') == null) return;
        // বিক্রিতে আসলে যতটা কমেছিল (dec; ০-তে থামলে কম) — আগের বিক্রিতে dec নেই, তখন পুরো পরিমাণ
        const q = (sale.lines || []).filter((l) => l.itemId === ids[k] && !l.noItem).reduce((a, l) => a + (typeof l.dec === 'number' ? l.dec : l.qty), 0);
        if (!q) return;
        const nq = r2(it.get('qty') + q);
        stockChange(it.get('name'), it.get('qty'), nq, add, remove);
        t.update(it.ref, { qty: nq, updatedAt: Date.now() });
      });
      stockWrite(t, user.uid, st, add, remove, req);
      t.delete(ref);
      saleDayAdd(t, user.uid, sale, -1);
    });
    return { ok: true };
  },

  // ======================= নষ্ট / গণনা =======================
  /**
   * kind 'writeoff' — নষ্ট/মেয়াদ শেষ: qty কমে, কেনা দামে ক্ষতি; kind 'count' — গণনা ঠিক করা: qty = গুনে পাওয়া সংখ্যা (লাভ-ক্ষতিতে নয়)।
   */
  async books_adjust(req, user) {
    await myShop(req, user);
    const b = req.body || {};
    const kind = b.kind === 'count' ? 'count' : b.kind === 'writeoff' ? 'writeoff' : null;
    if (!kind || !R.isValidId(b.itemId)) throw notFound(req);
    const q = qtyOf(req, b.qty);
    if (q == null || (kind === 'writeoff' && q <= 0)) throw err(req, 'Enter the quantity', 'পরিমাণ লিখুন');
    const now = Date.now();
    const ref = bookCol(user.uid, 'adjust').doc();
    const out = await db.runTransaction(async (t) => {
      const it = await t.get(itemRef(user.uid, b.itemId));
      const st = await t.get(stockRef(user.uid));
      if (!it.exists) throw notFound(req);
      const x = it.data();
      const before = x.qty ?? null;
      const after = kind === 'count' ? q : before == null ? null : r2(Math.max(0, before - q));
      // নষ্ট: পরিমাণ জানা থাকলে যতটা আসলে ছিল তার বেশি ক্ষতি নয়
      const gone = kind === 'writeoff' ? (before == null ? q : r2(Math.min(q, before))) : 0;
      const delta = kind === 'count' ? r2(q - (before || 0)) : -gone;
      const loss = kind === 'writeoff' && x.cost != null ? r2(gone * x.cost) : 0;
      const add = [];
      const remove = [];
      stockChange(x.name, before, after, add, remove);
      t.update(it.ref, { qty: after, updatedAt: now });
      stockWrite(t, user.uid, st, add, remove, req);
      const doc = { kind, itemId: b.itemId, name: x.name, before, after, delta, unitCost: x.cost ?? null, loss, reason: str(b.reason, 120), at: now, day: dayKey(now), createdAt: now };
      t.set(ref, doc);
      dayAdd(t, user.uid, doc.day, kind === 'writeoff' ? { adjusts: 1, writeOff: loss, woq: gone } : { adjusts: 1 });
      return doc;
    });
    return { id: ref.id, adjust: { id: ref.id, ...out } };
  },

  /** নষ্ট/গণনার তালিকা (গত ৯০ দিন) */
  async books_adjusts(req, user) {
    await myShop(req, user);
    const snap = await bookCol(user.uid, 'adjust').where('at', '>=', Date.now() - 90 * DAY_MS).orderBy('at', 'desc').limit(200).get();
    return { items: snap.docs.map((d) => ({ id: d.id, ...d.data() })).map(({ demo, ...x }) => x) };
  },

  /**
   * অ্যাপের ছোট "আজকের হিসাব" কার্ড (পুরো হিসাব শুধু ওয়েবে): আজকের বিক্রি, লাভ, কম স্টক আর ৬০ দিনে মেয়াদ শেষের সংখ্যা।
   * priced = দাম বসানো কোনো পণ্য আছে কি না (না থাকলে অ্যাপ শুধু "ওয়েবে খুলুন" দেখায়)।
   */
  async books_today(req, user) {
    await myShop(req, user);
    const now = Date.now();
    const [d, snap] = await Promise.all([dayRef(user.uid, dayKey(now)).get(), itemsCol(user.uid).limit(MAX_ITEMS).get()]);
    const x = (d.exists && d.data()) || {};
    let low = 0;
    let expiring = 0;
    let priced = false;
    for (const s of snap.docs) {
      const v = itemView(s.id, s.data(), new Set(), now);
      if (v.low) low++;
      if (v.expDays != null && v.expDays <= 60 && (v.qty == null || v.qty > 0)) expiring++;
      if (v.price != null) priced = true;
    }
    return { sales: Math.round(x.sales || 0), net: r2(x.net || 0), profit: r2(x.profit || 0), writeOff: r2(x.writeOff || 0), low, expiring, priced };
  },

  // ======================= হিসাব (লাভ-ক্ষতি) =======================
  async books_report(req, user) {
    await myShop(req, user);
    const period = ['today', '7d', '30d', 'month'].includes(req.body?.period) ? req.body.period : '30d';
    const now = Date.now();
    const { days } = periodOf(period, now);
    const [daySnaps, { rows, stockKeys }] = await Promise.all([
      db.getAll(...days.map((d) => dayRef(user.uid, d))),
      loadItems(user.uid),
    ]);
    return report(period, days, daySnaps.map((s) => (s.exists ? s.data() : null)), rows, stockKeys, now);
  },
};

/** সময়ের ভাগ → দিনের চাবিগুলো (পুরোনো আগে) আর শুরুর সময় */
function periodOf(period, now = Date.now()) {
  const today = dayStart(now);
  let n = period === 'today' ? 1 : period === '30d' ? 30 : period === 'month' ? Number(dayKey(now).slice(6, 8)) : 7;
  n = Math.max(1, Math.min(31, n));
  const days = [];
  // দিনের মাঝখান ধরে (ঘড়ির হিসাবে কোনো দিন বাদ না পড়ে)
  for (let i = n - 1; i >= 0; i--) days.push(dayKey(today - i * DAY_MS + 12 * 3600 * 1000));
  return { days, start: today - (n - 1) * DAY_MS };
}

/** দিনের যোগফল আর পণ্য থেকে পুরো হিসাব (পরীক্ষায় সরাসরি ডাকা যায়) */
function report(period, days, dayDocs, rows, stockKeys, now = Date.now()) {
  const T = { sales: 0, gross: 0, discount: 0, net: 0, cost: 0, profit: 0, nc: 0, writeOff: 0, bought: 0, purchases: 0 };
  const src = { counter: { n: 0, net: 0 }, rx: { n: 0, net: 0 }, online: { n: 0, net: 0 } };
  const per = new Map();
  const daily = days.map((day, i) => {
    const x = dayDocs[i] || {};
    for (const k of Object.keys(T)) T[k] += x[k] || 0;
    for (const s of SOURCES) { src[s].n += x.src?.[s]?.n || 0; src[s].net += x.src?.[s]?.net || 0; }
    for (const [id, v] of Object.entries(x.items || {})) {
      const c = per.get(id) || { id, name: v.n || '', q: 0, net: 0, profit: 0, neg: 0, nq: 0 };
      c.name = v.n || c.name;
      c.q += v.q || 0; c.net += v.net || 0; c.profit += v.profit || 0; c.neg += v.neg || 0; c.nq += v.nq || 0;
      per.set(id, c);
    }
    return { day, net: r2(x.net || 0), profit: r2(x.profit || 0), sales: Math.round(x.sales || 0) };
  });
  for (const k of Object.keys(T)) T[k] = r2(T[k]);
  for (const s of SOURCES) src[s] = { n: Math.round(src[s].n), net: r2(src[s].net) };
  const list = [...per.values()].map((c) => ({ id: c.id, name: c.name, qty: r2(c.q), net: r2(c.net), profit: r2(c.profit), loss: r2(-c.neg), lossQty: r2(c.nq) }))
    .filter((c) => c.qty > 0 || c.net > 0);
  const items = rows.map((r) => itemView(r.id, r.x, stockKeys, now));
  const live = items.filter((i) => i.qty != null && i.qty > 0);
  const value = (f) => r2(live.reduce((a, i) => a + (f(i) != null ? i.qty * f(i) : 0), 0));
  const quiet = now - 30 * DAY_MS;
  const createdOf = new Map(rows.map((r) => [r.id, r.x.createdAt || 0]));
  return {
    period,
    from: days[0],
    to: days[days.length - 1],
    totals: {
      sales: Math.round(T.sales), gross: T.gross, discount: T.discount, net: T.net,
      cost: T.cost, profit: T.profit, margin: T.nc > 0 ? Math.round((T.profit / T.nc) * 1000) / 10 : null,
      // কেনা দাম জানা নেই এমন বিক্রি (লাভে ধরা হয়নি)
      uncosted: r2(T.net - T.nc),
      writeOff: T.writeOff, netProfit: r2(T.profit - T.writeOff), bought: T.bought, purchases: Math.round(T.purchases),
    },
    bySource: src,
    daily,
    topSales: [...list].sort((a, c) => c.net - a.net).slice(0, 10),
    topProfit: list.filter((c) => c.profit > 0).sort((a, c) => c.profit - a.profit).slice(0, 10),
    belowCost: list.filter((c) => c.loss > 0).sort((a, c) => c.loss - a.loss).slice(0, 20),
    stock: {
      items: items.length,
      counted: items.filter((i) => i.qty != null).length,
      atCost: value((i) => i.cost),
      atPrice: value((i) => i.price),
      noCost: live.filter((i) => i.cost == null).length,
      noPrice: live.filter((i) => i.price == null).length,
    },
    low: items.filter((i) => i.low).sort((a, c) => a.qty - c.qty).slice(0, 50),
    expiring: items.filter((i) => i.expDays != null && i.expDays <= 60 && (i.qty == null || i.qty > 0)).sort((a, c) => a.expDays - c.expDays).slice(0, 50),
    noSale: live.filter((i) => (i.lastSaleAt || createdOf.get(i.id) || 0) < quiet).sort((a, c) => (a.lastSaleAt || 0) - (c.lastSaleAt || 0)).slice(0, 50),
  };
}

/** অ্যাকাউন্ট মোছা: পণ্য আর পুরো হিসাব-খাতা */
async function deleteFor(uid) {
  if (!R.isValidId(uid)) return;
  await Promise.all([db.recursiveDelete(db.doc(`shopItems/${uid}`)), db.recursiveDelete(db.doc(`shopBooks/${uid}`))]);
}

module.exports = { actions, deleteFor, priceSale, saleDayDelta, report, periodOf, idOf, key, dayKey, dayStart, itemView, unitDiscOf, UNITS };
