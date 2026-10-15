// ফার্মেসির স্টক তালিকা: কোন ওষুধ "সাধারণত থাকে" / "এখন নেই" (পরিমাণ নয় — কাউন্টারের বিক্রি অ্যাপে
// ধরা পড়ে না, তাই সংখ্যা দ্রুত ভুল হয়ে যেত)। দাম আর ছাড় ঐচ্ছিক।
//
// pharmacyStock/{pharmacyId}: { j: JSON { [key]: { s: 'in'|'out', m: হাতে দেওয়া?, at, p?, u?, d? } }, autoOldest, updatedAt }
// stockIdx/{জেলা}__{ভাগ}: { j: JSON { [key]: { [pharmacyId]: [দাম|0, একক, ছাড়] } } } — শুধু "থাকে"
//
// কেন JSON লেখা (map নয়): Firestore map-এর প্রতিটা ঘরের জন্য আলাদা ইনডেক্স রাখে, আর এক ডকে ৪০,০০০-এর বেশি
// ইনডেক্স হলে লেখা ব্যর্থ হয়; একটা লেখা (string) একটাই ইনডেক্স। ২৫৬ ভাগে ভাগ করায় বড় জেলাতেও প্রতিটা ভাগ ছোট।
// তালিকা আর সূচি একই transaction-এ লেখা হয়, তাই কখনো আলাদা হয়ে যায় না।
const { db, HttpError } = require('./firebase');
const C = require('./community');
const R = require('./rules');
const K = require('./care');
const DISTRICTS = require('./districts');

const SHARDS = 256;
const MAX_ITEMS = 6000;
const AUTO_KEEP_MS = 30 * 24 * 3600 * 1000;
const KEY_RE = /^([a-z0-9]{1,14}|c_[A-Za-z0-9]{10,30})$/;
// দামের একক: পিস, পাতা, বক্স, বোতল, টিউব, ভায়াল/অ্যাম্পুল
const UNITS = ['piece', 'strip', 'box', 'bottle', 'tube', 'vial'];
const MAX_PRICE = 99999;

// Object.prototype-এর নাম (যেমন constructor) বাদ — JSON-এর সাধারণ object-এ বসালে গোলমাল করত
const validKey = (k) => typeof k === 'string' && KEY_RE.test(k) && !(k in Object.prototype);

/** key-এর পুরোটার হ্যাশ থেকে ভাগ (শেষ অক্ষর ধরলে ভাগ অসমান হতো) */
function shardOf(key) {
  let h = 2166136261;
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) % SHARDS;
}
const indexRef = (district, shard) => db.doc(`stockIdx/${district}__${shard}`);
const parse = (s) => {
  if (typeof s !== 'string' || !s) return {};
  try { return JSON.parse(s) || {}; } catch { return {}; }
};

/** অনুরোধ থেকে দাম/একক/ছাড় (দেওয়া না থাকলে undefined = আগেরটাই; null = মুছে দেওয়া) */
function priceFields(c) {
  const out = {};
  if (c.p === null) out.p = null;
  else if (Number.isInteger(c.p) && c.p > 0 && c.p <= MAX_PRICE) out.p = c.p;
  if (UNITS.includes(c.u)) out.u = c.u;
  if (c.d === null) out.d = null;
  else if (Number.isInteger(c.d) && c.d >= 0 && c.d <= 50) out.d = c.d;
  return out;
}

const tuple = (it) => [it.p || 0, Math.max(0, UNITS.indexOf(it.u || 'piece')), it.d || 0];

/** দোকানের তালিকা পড়া (আগের map রূপ থাকলে সেটাও) */
function itemsOf(snap) {
  if (!snap.exists) return {};
  if (typeof snap.get('j') === 'string') return parse(snap.get('j'));
  return snap.get('items') || {};
}

function autoOldest(items) {
  let min = null;
  for (const v of Object.values(items)) if (!v.m && (min == null || v.at < min)) min = v.at;
  return min;
}

/**
 * দোকানের তালিকা বদল, সূচি সহ একই transaction-এ।
 * changes: [{ key, s: 'in'|'out'|'remove', p?, u?, d? }], manual: হাতে করা কি না।
 * অর্ডারের উত্তর থেকে নিজে শেখার সময় manual=false (৩০ দিন পর নিজে থেকে বাদ যায়)।
 * opts.onlyAuto: নিজে শেখা "নেই" হাতে দেওয়া "থাকে"-কে বদলায় না। opts.expireBefore: পুরোনো নিজে শেখা বাদ।
 * ফেরত: { changed, skipped } (skipped = তালিকা ভরে যাওয়ায় যোগ হয়নি)
 */
async function applyChanges(pharmacyId, district, changes, manual, opts = {}) {
  const ref = db.doc(`pharmacyStock/${pharmacyId}`);
  return db.runTransaction(async (t) => {
    const s = await t.get(ref);
    const items = itemsOf(s);
    const now = Date.now();
    let list = changes;
    if (opts.expireBefore) {
      // ক্রন: transaction-এর ভেতরে আবার দেখে (এর মধ্যে দোকান হাতে বদলালে সেটা থাকে)
      list = Object.entries(items).filter(([, v]) => !v.m && (v.at || 0) < opts.expireBefore).map(([key]) => ({ key, s: 'remove' }));
    }
    const touched = new Map(); // key → নতুন অবস্থা (null = সূচি থেকে সরানো)
    let skipped = 0;
    let changed = 0;
    let count = Object.keys(items).length;
    for (const c of list) {
      const cur = items[c.key];
      if (c.s === 'remove') {
        if (!cur) continue;
        delete items[c.key];
        count--;
        changed++;
        touched.set(c.key, null);
        continue;
      }
      if (opts.onlyAuto && cur && cur.m && c.s === 'out') continue;
      if (!cur && count >= MAX_ITEMS) { skipped++; continue; }
      if (!cur) count++;
      // নিজে শেখা তথ্য হাতে দেওয়া চিহ্ন আর দাম রাখে
      const it = { ...(cur || {}), s: c.s, m: manual || !!(cur && cur.m), at: now };
      for (const [f, v] of Object.entries(priceFields(c))) {
        if (v === null) delete it[f];
        else it[f] = v;
      }
      items[c.key] = it;
      changed++;
      touched.set(c.key, it.s === 'in' ? it : null);
    }
    if (!changed) return { changed: 0, skipped };

    // সূচির যে ভাগগুলো বদলাবে, সেগুলো পড়া (transaction-এ সব পড়া লেখার আগে)
    const byShard = new Map();
    if (district) {
      for (const key of touched.keys()) {
        const sh = shardOf(key);
        if (!byShard.has(sh)) byShard.set(sh, []);
        byShard.get(sh).push(key);
      }
    }
    const shardIds = [...byShard.keys()];
    const shardRefs = shardIds.map((sh) => indexRef(district, sh));
    const shardSnaps = shardRefs.length ? await t.getAll(...shardRefs) : [];

    t.set(ref, { j: JSON.stringify(items), count: Object.keys(items).length, autoOldest: autoOldest(items), updatedAt: now });
    shardSnaps.forEach((snap, i) => {
      const idx = parse(snap.exists ? snap.get('j') : '');
      for (const key of byShard.get(shardIds[i])) {
        const it = touched.get(key);
        if (it) {
          idx[key] = idx[key] || {};
          idx[key][pharmacyId] = tuple(it);
        } else if (idx[key]) {
          delete idx[key][pharmacyId];
          if (!Object.keys(idx[key]).length) delete idx[key];
        }
      }
      t.set(shardRefs[i], { j: JSON.stringify(idx), updatedAt: now });
    });
    return { changed, skipped };
  });
}

/** জেলায় কোন key কোন দোকানে "থাকে" — key → { pharmacyId: [দাম, একক, ছাড়] } */
async function whoHas(district, keys) {
  const shards = [...new Set(keys.map(shardOf))];
  const docs = await db.getAll(...shards.map((sh) => indexRef(district, sh)));
  const out = new Map();
  docs.forEach((d) => {
    if (!d.exists) return;
    const idx = parse(d.get('j'));
    keys.forEach((key) => { if (idx[key] && Object.keys(idx[key]).length) out.set(key, idx[key]); });
  });
  return out;
}

/** অর্ডারের দোকান বাছাই: কাছের খোলা দোকানগুলোর মধ্যে যেখানে বেশি ওষুধ "থাকে", সেটা আগে */
async function preferStocked(district, keys, candidates) {
  if (!keys || !keys.length || candidates.length < 2) return candidates;
  try {
    const has = await whoHas(district, keys);
    const cover = (p) => keys.filter((k) => has.get(k) && has.get(k)[p.id]).length;
    return candidates
      .map((p, i) => ({ p, i, c: cover(p) }))
      .sort((a, b) => b.c - a.c || a.i - b.i)
      .map((x) => x.p);
  } catch (e) {
    console.error('stock prefer failed', e.message);
    return candidates;
  }
}

/**
 * অর্ডারের উত্তর থেকে শেখা: "সব আছে" → থাকে, "নেই" → এখন নেই (শুধু নিজে শেখা তথ্যে —
 * দোকান হাতে "থাকে" দিয়ে থাকলে একটা "নেই" উত্তরে সেটা বদলায় না; অন্য কারণেও "নেই" বলা হতে পারে)
 */
async function learnFromReply(pharmacyId, district, keys, status) {
  const list = [...new Set((keys || []).filter(validKey))].slice(0, 15);
  if (!list.length || !['all', 'none'].includes(status)) return;
  try {
    await applyChanges(pharmacyId, district, list.map((key) => ({ key, s: status === 'all' ? 'in' : 'out' })), false, { onlyAuto: true });
  } catch (e) {
    console.error('stock learn failed', e.message);
  }
}

/** দোকানের জেলা বদলালে সূচিও সরানো: পুরোনো জেলা থেকে মুছে নতুন জেলায় */
async function moveDistrict(pharmacyId, from, to) {
  if (!from || !to || from === to) return;
  const s = await db.doc(`pharmacyStock/${pharmacyId}`).get();
  const items = itemsOf(s);
  const ins = Object.keys(items).filter((k) => items[k].s === 'in');
  if (!ins.length) return;
  await reindex(from, pharmacyId, ins, null);
  await reindex(to, pharmacyId, ins, items);
}

/** একটা দোকানের অনেকগুলো key সূচিতে বসানো/সরানো (items null = সরানো) — ভাগ ধরে, একেকটা transaction */
async function reindex(district, pharmacyId, keys, items) {
  const byShard = new Map();
  keys.forEach((k) => {
    const sh = shardOf(k);
    if (!byShard.has(sh)) byShard.set(sh, []);
    byShard.get(sh).push(k);
  });
  for (const [sh, list] of byShard) {
    const ref = indexRef(district, sh);
    await db.runTransaction(async (t) => {
      const snap = await t.get(ref);
      const idx = parse(snap.exists ? snap.get('j') : '');
      list.forEach((key) => {
        if (items && items[key]) {
          idx[key] = idx[key] || {};
          idx[key][pharmacyId] = tuple(items[key]);
        } else if (idx[key]) {
          delete idx[key][pharmacyId];
          if (!Object.keys(idx[key]).length) delete idx[key];
        }
      });
      t.set(ref, { j: JSON.stringify(idx), updatedAt: Date.now() });
    });
  }
}

/** দোকান মুছে গেলে: তালিকা আর সূচি থেকে */
async function removeShop(pharmacyId, district) {
  const ref = db.doc(`pharmacyStock/${pharmacyId}`);
  const s = await ref.get();
  if (!s.exists) return;
  const items = itemsOf(s);
  if (district) await reindex(district, pharmacyId, Object.keys(items).filter((k) => items[k].s === 'in'), null);
  await ref.delete();
}

/**
 * ক্রন: ৩০ দিনের পুরোনো নিজে শেখা তথ্য বাদ (autoOldest দিয়ে শুধু যেগুলো দরকার),
 * আর আগের map রূপে রাখা তালিকা নতুন রূপে আনা।
 */
async function dailyStock(now = Date.now()) {
  // একবারের কাজ: প্রথম দিনের (map রূপের) তালিকা নতুন রূপে আর নতুন সূচিতে
  const flag = db.doc('serverState/_stockFormat2');
  if (!(await flag.get()).exists) {
    const old = await db.collection('pharmacyStock').limit(2000).get();
    for (const d of old.docs) {
      if (typeof d.get('j') === 'string') continue;
      const items = d.get('items') || {};
      const shop = await db.doc(`pharmacies/${d.id}`).get();
      if (shop.exists) await reindex(shop.get('district'), d.id, Object.keys(items).filter((k) => items[k].s === 'in'), items);
      await d.ref.set({ j: JSON.stringify(items), count: Object.keys(items).length, autoOldest: autoOldest(items), updatedAt: now });
    }
    await flag.set({ at: now, migrated: old.size });
  }
  const cutoff = now - AUTO_KEEP_MS;
  const snap = await db.collection('pharmacyStock').where('autoOldest', '<', cutoff).limit(200).get();
  let removed = 0;
  for (const d of snap.docs) {
    const shop = await db.doc(`pharmacies/${d.id}`).get();
    const r = await applyChanges(d.id, shop.exists ? shop.get('district') : null, [], false, { expireBefore: cutoff });
    removed += r.changed;
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
    return { items: itemsOf(s), max: MAX_ITEMS };
  },

  /** যোগ / "এখন নেই" / আবার "থাকে" / সরানো, দাম সহ — একবারে ৫০০টা পর্যন্ত */
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
    const r = await applyChanges(user.uid, shop.get('district'), changes, true);
    return { ok: true, skipped: r.skipped, max: MAX_ITEMS };
  },

  /**
   * ক্রেতা: এই ওষুধগুলো জেলার কোন দোকানে "সাধারণত থাকে"।
   * ফেরত: দোকানের তালিকা (কয়টা আছে, দাম, দূরত্ব, খোলা কি না) — বেশি মিল আগে।
   */
  async stock_find(req) {
    const b = req.body || {};
    if (!DISTRICTS.some((d) => d.bn === b.district)) throw new HttpError(400, C.tr(req, 'Choose a district', 'জেলা বেছে নিন'));
    const keys = [...new Set((Array.isArray(b.keys) ? b.keys : []).filter(validKey))].slice(0, 20);
    if (!keys.length) return { items: [] };
    const has = await whoHas(b.district, keys);
    const cover = new Map();
    has.forEach((shops) => Object.keys(shops).forEach((id) => cover.set(id, (cover.get(id) || 0) + 1)));
    if (!cover.size) return { items: [] };
    // বেশি মিল আগে নিয়ে সর্বোচ্চ ১০০টা দোকান পড়া
    const ids = [...cover.entries()].sort((a, c) => c[1] - a[1]).slice(0, 100).map(([id]) => id);
    const shops = await db.getAll(...ids.map((id) => db.doc(`pharmacies/${id}`)));
    const lat = typeof b.lat === 'number' ? b.lat : null;
    const lng = typeof b.lng === 'number' ? b.lng : null;
    const items = shops
      .filter((s) => s.exists && s.get('status') === 'approved' && s.get('district') === b.district)
      .map((s) => {
        const x = s.data();
        const n = x.ratingCount || 0;
        const mine = keys.filter((k) => has.get(k) && has.get(k)[s.id]);
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
          has: mine,
          // দোকান দাম বসিয়ে থাকলে: key → { price, unit, discount }
          prices: Object.fromEntries(mine
            .map((k) => [k, has.get(k)[s.id]])
            .filter(([, v]) => Array.isArray(v) && v[0] > 0)
            .map(([k, v]) => [k, { price: v[0], unit: UNITS[v[1]] || 'piece', discount: v[2] || 0 }])),
        };
      })
      .sort((a, c) => (c.has.length - a.has.length) || (c.openNow - a.openNow) || ((a.km ?? 99) - (c.km ?? 99)))
      .slice(0, 20);
    return { items };
  },
};

module.exports = { actions, UNITS, MAX_PRICE, priceFields, applyChanges, learnFromReply, preferStocked, moveDistrict, removeShop, dailyStock, validKey, shardOf, whoHas, KEY_RE };
