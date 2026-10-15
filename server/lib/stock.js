// ফার্মেসির স্টক তালিকা: কোন ওষুধ "সাধারণত থাকে" / "এখন নেই" (পরিমাণ নয় — কাউন্টারের বিক্রি অ্যাপে
// ধরা পড়ে না, তাই সংখ্যা দ্রুত ভুল হয়ে যেত)।
//
// pharmacyStock/{pharmacyId}: { items: { [key]: { s: 'in'|'out', m: হাতে দেওয়া?, at, p?: দাম, u?: একক, d?: ছাড়% } } }
// stockIndex/{জেলা}__{ভাগ}: { k: { [key]: [pharmacyId, …] }, p: { [key]: { [pharmacyId]: [দাম, একক, ছাড়] } } }
//   — শুধু "থাকে"; খোঁজার সময় এক-দুটো ডক পড়লেই দোকান আর দাম দুটোই পাওয়া যায়।
// দাম আর ছাড় ঐচ্ছিক: দোকান চাইলে বসায়; না বসালে উত্তরে দাম জানায় (আগের মতো)।
// key: অ্যাপের ওষুধের তালিকা থেকে বানানো (Drugs.kt — নাম|জেনেরিক|ধরনের ৬৪-বিট হ্যাশ), বা যোগ করা ওষুধে "c_<id>"।
const { db, FieldValue, HttpError } = require('./firebase');
const C = require('./community');
const R = require('./rules');
const K = require('./care');
const DISTRICTS = require('./districts');

const SHARDS = 16;
const MAX_ITEMS = 6000;
const AUTO_KEEP_MS = 30 * 24 * 3600 * 1000;
const KEY_RE = /^([a-z0-9]{6,14}|c_[A-Za-z0-9]{10,30})$/;
// দামের একক: পিস, পাতা, বক্স, বোতল, টিউব, ভায়াল/অ্যাম্পুল
const UNITS = ['piece', 'strip', 'box', 'bottle', 'tube', 'vial'];

/** অনুরোধ থেকে দাম/একক/ছাড় (দেওয়া না থাকলে undefined = আগেরটাই থাকবে; null = মুছে দেওয়া) */
function priceFields(c) {
  const out = {};
  if (c.p === null) out.p = null;
  else if (Number.isInteger(c.p) && c.p > 0 && c.p <= 100000) out.p = c.p;
  if (UNITS.includes(c.u)) out.u = c.u;
  if (c.d === null) out.d = null;
  else if (Number.isInteger(c.d) && c.d >= 0 && c.d <= 50) out.d = c.d;
  return out;
}

const validKey = (k) => typeof k === 'string' && KEY_RE.test(k);
const shardOf = (key) => key.charCodeAt(key.length - 1) % SHARDS;
const indexRef = (district, shard) => db.doc(`stockIndex/${district}__${shard}`);

/**
 * সূচি হালনাগাদ: add/remove = key-এর তালিকা। ভাগ অনুযায়ী একটা করে লেখা।
 * arrayUnion/arrayRemove — একসাথে কয়েকটা দোকান বদলালেও কারো লেখা হারায় না।
 */
async function updateIndex(district, pharmacyId, add, remove, prices = new Map()) {
  if (!district || (!add.length && !remove.length && !prices.size)) return;
  const byShard = new Map();
  const doc = (key) => {
    const sh = shardOf(key);
    if (!byShard.has(sh)) byShard.set(sh, { k: {}, p: {} });
    return byShard.get(sh);
  };
  add.forEach((k) => { doc(k).k[k] = FieldValue.arrayUnion(pharmacyId); });
  remove.forEach((k) => {
    doc(k).k[k] = FieldValue.arrayRemove(pharmacyId);
    doc(k).p[k] = { [pharmacyId]: FieldValue.delete() };
  });
  // prices: key → [দাম, একক, ছাড়] বা null (মুছে দেওয়া)
  prices.forEach((v, k) => { if (!remove.includes(k)) doc(k).p[k] = { [pharmacyId]: v ? v : FieldValue.delete() }; });
  await Promise.all([...byShard.entries()].map(([sh, data]) => {
    const body = {};
    if (Object.keys(data.k).length) body.k = data.k;
    if (Object.keys(data.p).length) body.p = data.p;
    return indexRef(district, sh).set(body, { merge: true });
  }));
}

const priceTuple = (it) => (it && it.s === 'in' && it.p ? [it.p, UNITS.indexOf(it.u || 'piece'), it.d || 0] : null);

/**
 * দোকানের তালিকা বদল। changes: [{ key, s: 'in'|'out'|'remove' }], manual: হাতে করা কি না।
 * অর্ডারের উত্তর থেকে নিজে শেখার সময় manual=false (৩০ দিন পর নিজে থেকে বাদ যায়)।
 */
async function applyChanges(pharmacyId, district, changes, manual) {
  const ref = db.doc(`pharmacyStock/${pharmacyId}`);
  const now = Date.now();
  const res = await db.runTransaction(async (t) => {
    const s = await t.get(ref);
    const items = (s.exists && s.get('items')) || {};
    const next = {};
    const deleted = [];
    const add = [];
    const remove = [];
    const prices = new Map();
    let count = Object.keys(items).length;
    for (const c of changes) {
      const cur = items[c.key];
      if (c.s === 'remove') {
        if (!cur) continue;
        deleted.push(c.key);
        count--;
        if (cur.s === 'in') remove.push(c.key);
        continue;
      }
      if (!cur && count >= MAX_ITEMS) continue;
      if (!cur) count++;
      // নিজে শেখা তথ্য হাতে দেওয়া "নেই"-কে "আছে" করতে পারে (দোকানই বলেছে আছে), কিন্তু হাতে দেওয়া চিহ্ন আর দাম রাখে
      const it = { ...(cur || {}), s: c.s, m: manual || !!(cur && cur.m), at: now };
      const pf = priceFields(c);
      for (const [f, v] of Object.entries(pf)) {
        if (v === null) delete it[f];
        else it[f] = v;
      }
      next[c.key] = it;
      if (c.s === 'in' && (!cur || cur.s !== 'in')) add.push(c.key);
      if (c.s === 'out' && cur && cur.s === 'in') remove.push(c.key);
      const before = JSON.stringify(priceTuple(cur));
      const after = priceTuple(it);
      if (c.s === 'in' && JSON.stringify(after) !== before) prices.set(c.key, after);
    }
    if (!Object.keys(next).length && !deleted.length) return { add, remove, prices };
    if (s.exists) {
      const patch = { updatedAt: now };
      for (const [k, v] of Object.entries(next)) patch[`items.${k}`] = v;
      deleted.forEach((k) => { patch[`items.${k}`] = FieldValue.delete(); });
      t.update(ref, patch);
    } else {
      t.set(ref, { items: next, updatedAt: now });
    }
    return { add, remove, prices };
  });
  await updateIndex(district, pharmacyId, res.add, res.remove, res.prices);
  return res;
}

/** জেলায় কোন key কোন দোকানে "থাকে" — key → Set(pharmacyId); prices: key → { pharmacyId: [দাম, একক, ছাড়] } */
async function whoHas(district, keys, withPrices = false) {
  const shards = [...new Set(keys.map(shardOf))];
  const docs = await db.getAll(...shards.map((sh) => indexRef(district, sh)));
  const out = new Map();
  const prices = new Map();
  docs.forEach((d) => {
    if (!d.exists) return;
    const k = d.get('k') || {};
    const p = d.get('p') || {};
    keys.forEach((key) => {
      if (Array.isArray(k[key]) && k[key].length) out.set(key, new Set(k[key]));
      if (withPrices && p[key]) prices.set(key, p[key]);
    });
  });
  return withPrices ? { has: out, prices } : out;
}

/** অর্ডারের দোকান বাছাই: কাছের খোলা দোকানগুলোর মধ্যে যেখানে বেশি ওষুধ "থাকে", সেটা আগে */
async function preferStocked(district, keys, candidates) {
  if (!keys || !keys.length || candidates.length < 2) return candidates;
  try {
    const has = await whoHas(district, keys);
    const cover = (p) => keys.filter((k) => has.get(k) && has.get(k).has(p.id)).length;
    return candidates
      .map((p, i) => ({ p, i, c: cover(p) }))
      .sort((a, b) => b.c - a.c || a.i - b.i)
      .map((x) => x.p);
  } catch (e) {
    console.error('stock prefer failed', e.message);
    return candidates;
  }
}

/** অর্ডারের উত্তর থেকে শেখা: "সব আছে" → থাকে, "নেই" → এখন নেই */
async function learnFromReply(pharmacyId, district, keys, status) {
  const list = (keys || []).filter(validKey).slice(0, 30);
  if (!list.length || !['all', 'none'].includes(status)) return;
  try {
    await applyChanges(pharmacyId, district, list.map((key) => ({ key, s: status === 'all' ? 'in' : 'out' })), false);
  } catch (e) {
    console.error('stock learn failed', e.message);
  }
}

/** দোকানের জেলা বদলালে সূচিও সরানো */
async function moveDistrict(pharmacyId, from, to) {
  if (!from || !to || from === to) return;
  const s = await db.doc(`pharmacyStock/${pharmacyId}`).get();
  const items = (s.exists && s.get('items')) || {};
  const ins = Object.keys(items).filter((k) => items[k].s === 'in');
  await updateIndex(from, pharmacyId, [], ins);
  const prices = new Map(ins.filter((k) => priceTuple(items[k])).map((k) => [k, priceTuple(items[k])]));
  await updateIndex(to, pharmacyId, ins, [], prices);
}

/** ক্রন: ৩০ দিনের পুরোনো নিজে শেখা তথ্য বাদ */
async function dailyStock(now = Date.now()) {
  const snap = await db.collection('pharmacyStock').limit(2000).get();
  let removed = 0;
  for (const d of snap.docs) {
    const items = d.get('items') || {};
    const old = Object.entries(items).filter(([, v]) => !v.m && now - (v.at || 0) > AUTO_KEEP_MS).map(([k]) => k);
    if (!old.length) continue;
    const shop = await db.doc(`pharmacies/${d.id}`).get();
    await applyChanges(d.id, shop.exists ? shop.get('district') : null, old.map((key) => ({ key, s: 'remove' })), false);
    removed += old.length;
  }
  return removed;
}

async function myShop(req, user) {
  const shop = await db.doc(`pharmacies/${user.uid}`).get();
  if (!shop.exists) throw new HttpError(404, C.tr(req, 'Open your pharmacy first', 'আগে নিজের ফার্মেসি খুলুন'));
  if (shop.get('status') === 'suspended') throw new HttpError(403, C.tr(req, 'Your pharmacy is suspended', 'আপনার ফার্মেসি বন্ধ করা আছে'));
  return shop;
}

const actions = {
  /** নিজের দোকানের তালিকা */
  async stock_mine(req, user) {
    await myShop(req, user);
    const s = await db.doc(`pharmacyStock/${user.uid}`).get();
    return { items: (s.exists && s.get('items')) || {}, max: MAX_ITEMS };
  },

  /** যোগ / "এখন নেই" / আবার "থাকে" / সরানো — একবারে ৫০০টা পর্যন্ত */
  async stock_set(req, user) {
    const shop = await myShop(req, user);
    const raw = Array.isArray(req.body?.changes) ? req.body.changes : [];
    const changes = [];
    const seen = new Set();
    for (const c of raw.slice(0, 500)) {
      if (!c || !validKey(c.key) || !['in', 'out', 'remove'].includes(c.s) || seen.has(c.key)) continue;
      seen.add(c.key);
      changes.push({ key: c.key, s: c.s, p: c.p, u: c.u, d: c.d });
    }
    if (!changes.length) throw new HttpError(400, C.tr(req, 'Nothing to change', 'বদলানোর কিছু নেই'));
    await applyChanges(user.uid, shop.get('district'), changes, true);
    return { ok: true };
  },

  /**
   * ক্রেতা: এই ওষুধগুলো জেলার কোন খোলা দোকানে "সাধারণত থাকে"।
   * ফেরত: দোকানের তালিকা (কয়টা আছে, দূরত্ব, খোলা কি না) — বেশি মিল আগে।
   */
  async stock_find(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const keys = [...new Set((Array.isArray(b.keys) ? b.keys : []).filter(validKey))].slice(0, 20);
    if (!keys.length) return { items: [] };
    const { has, prices } = await whoHas(b.district, keys, true);
    const ids = new Set();
    has.forEach((set) => set.forEach((id) => ids.add(id)));
    if (!ids.size) return { items: [] };
    const shops = await db.getAll(...[...ids].slice(0, 100).map((id) => db.doc(`pharmacies/${id}`)));
    const lat = typeof b.lat === 'number' ? b.lat : null;
    const lng = typeof b.lng === 'number' ? b.lng : null;
    const items = shops
      .filter((s) => s.exists && s.get('status') === 'approved' && s.get('district') === b.district)
      .map((s) => {
        const x = s.data();
        const n = x.ratingCount || 0;
        return {
          id: s.id,
          name: x.name,
          phone: K.fixPhone(x.phone),
          address: x.address || '',
          upazilaId: x.upazilaId || null,
          openNow: K.pharmacyOpenNow(x),
          delivery: x.delivery === true,
          discount: x.discount || 0,
          rating: n ? Math.round((x.ratingSum / n) * 10) / 10 : 0,
          ratingCount: n,
          km: lat != null && typeof x.lat === 'number' ? Math.round(R.distanceKm(lat, lng, x.lat, x.lng) * 10) / 10 : null,
          has: keys.filter((k) => has.get(k) && has.get(k).has(s.id)),
          // দোকান দাম বসিয়ে থাকলে: key → { price, unit, discount }
          prices: Object.fromEntries(keys
            .map((k) => [k, prices.get(k) && prices.get(k)[s.id]])
            .filter(([, v]) => Array.isArray(v))
            .map(([k, v]) => [k, { price: v[0], unit: UNITS[v[1]] || 'piece', discount: v[2] || 0 }])),
        };
      })
      .sort((a, c) => (c.has.length - a.has.length) || (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)))
      .slice(0, 20);
    return { items };
  },
};

module.exports = { actions, UNITS, priceFields, applyChanges, learnFromReply, preferStocked, moveDistrict, dailyStock, validKey, shardOf, KEY_RE };
