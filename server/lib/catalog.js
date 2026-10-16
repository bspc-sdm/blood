// বাছাইয়ের তালিকা (বিশেষজ্ঞতা, বাসায় সেবা, অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা) — অ্যাডমিন প্যানেল থেকে
// নতুন যোগ, নাম বদল, লুকানো আর ক্রম সাজানো যায়; অ্যাপ নতুন সংস্করণ ছাড়াই নেয় (catalog_get)।
// config/catalog: { lists: { [kind]: { [type]: [{ code, en, bn, hidden }] } }, updatedAt }
// কোনো কোড মোছা যায় না (আগে কেউ বেছে রাখলে তাঁর প্রোফাইলে ভুল দেখাত) — শুধু লুকানো।
// firebase দেরিতে (care.js-এর বিশুদ্ধ যাচাইয়ের পরীক্ষায় সার্ভিস অ্যাকাউন্ট লাগে না)
const fb = () => require('./firebase');
const C = require('./community');
const DEFAULTS = require('./catalog-defaults.json');

const KINDS = {
  specialty: ['doctor', 'nurse', 'nutritionist', 'trainer'],
  homeService: ['doctor', 'nurse'],
  ambulanceType: ['all'],
  bloodBankService: ['all'],
};
const MAX_ITEMS = 80;
const TTL = 5 * 60 * 1000;

let current = null;
let loadedAt = 0;

function clone(x) {
  return JSON.parse(JSON.stringify(x));
}

/** ডিফল্ট + সংরক্ষিত তালিকা: সংরক্ষিত তালিকা থাকলে সেটাই (ক্রমসহ), তাতে না থাকা ডিফল্ট কোড শেষে যোগ হয় */
function merge(saved) {
  const out = clone(DEFAULTS);
  for (const [kind, types] of Object.entries(KINDS)) {
    for (const type of types) {
      const list = saved && saved[kind] && Array.isArray(saved[kind][type]) ? saved[kind][type] : null;
      if (!list) continue;
      const seen = new Set(list.map((i) => i.code));
      out[kind][type] = [...list.map((i) => ({ code: i.code, en: i.en, bn: i.bn, ...(i.hidden ? { hidden: true } : {}) })),
        ...out[kind][type].filter((d) => !seen.has(d.code))];
    }
  }
  return out;
}

/** প্রতিটা অনুরোধের শুরুতে (৫ মিনিটে একবার পড়া) */
async function load(now = Date.now()) {
  if (current && now - loadedAt < TTL) return current;
  try {
    const s = await fb().db.doc('config/catalog').get();
    current = merge(s.exists ? s.get('lists') : null);
    loadedAt = now;
  } catch (e) {
    console.error('catalog load failed', e.message);
    if (!current) current = merge(null);
  }
  return current;
}

function lists() {
  return current || merge(null);
}

/** যাচাইয়ের জন্য সব কোড (লুকানোও — আগে বেছে রাখা থাকলে সেভ আটকাবে না) */
function codes(kind, type = 'all') {
  const t = (lists()[kind] || {})[type] || [];
  return t.map((i) => i.code);
}

/** নতুন কোড: ইংরেজি নাম থেকে, ছোট হাতের, _ দিয়ে; আগে থাকলে শেষে সংখ্যা */
function makeCode(en, taken) {
  const base = `c_${String(en || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, 30) || 'item'}`;
  let code = base;
  for (let i = 2; taken.has(code); i++) code = `${base}_${i}`;
  return code;
}

const actions = {
  /** অ্যাপ: সব তালিকা (লুকানোগুলো hidden দিয়ে — লেবেল দেখাতে লাগে, বাছাইয়ে আসে না) */
  async catalog_get() {
    const s = await fb().db.doc('config/catalog').get().catch(() => null);
    return { lists: lists(), updatedAt: (s && s.exists && s.get('updatedAt')) || 0 };
  },

  /** অ্যাডমিন: একটা তালিকা পুরো সেভ (ক্রম, নাম, লুকানো, নতুন) */
  async admin_catalog_set(req, user) {
    if (!(await require('./admin').isAdmin(user))) throw new (fb().HttpError)(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const { kind, type } = req.body || {};
    if (!KINDS[kind] || !KINDS[kind].includes(type)) throw new (fb().HttpError)(400, C.tr(req, 'Unknown list', 'অজানা তালিকা'));
    const items = Array.isArray(req.body?.items) ? req.body.items : [];
    if (!items.length || items.length > MAX_ITEMS) throw new (fb().HttpError)(400, C.tr(req, `1–${MAX_ITEMS} items`, `১–${MAX_ITEMS}টা নাম`));
    const now = lists()[kind][type];
    const known = new Set(now.map((i) => i.code));
    const taken = new Set(known);
    const out = [];
    for (const it of items) {
      const en = C.cleanText(String(it?.en || ''), 60);
      const bn = C.cleanText(String(it?.bn || ''), 60);
      if (!en || !bn) throw new (fb().HttpError)(400, C.tr(req, 'Write both Bangla and English names', 'বাংলা আর ইংরেজি দুটো নামই লিখুন'));
      let code = typeof it.code === 'string' && known.has(it.code) ? it.code : null;
      if (!code) {
        code = makeCode(en, taken);
        taken.add(code);
      }
      if (out.some((o) => o.code === code)) continue;
      out.push({ code, en, bn, ...(it.hidden === true ? { hidden: true } : {}) });
    }
    // মোছা যায় না: আগের কোনো কোড বাদ পড়লে লুকানো হিসেবে শেষে থাকে
    for (const old of now) if (!out.some((o) => o.code === old.code)) out.push({ ...old, hidden: true });
    const ref = fb().db.doc('config/catalog');
    const s = await ref.get();
    const saved = (s.exists && s.get('lists')) || {};
    saved[kind] = { ...(saved[kind] || {}), [type]: out };
    await ref.set({ lists: saved, updatedAt: Date.now() });
    current = merge(saved);
    loadedAt = Date.now();
    return { items: current[kind][type] };
  },
};

module.exports = { actions, load, lists, codes, merge, makeCode, KINDS };
