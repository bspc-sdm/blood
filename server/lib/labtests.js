// পরীক্ষার রিপোর্টের ছক (১৭ অক্টোবর ২০২৬): প্রতিটা পরীক্ষার ঘর (প্যারামিটার), একক, স্বাভাবিক মাত্রা
// (নারী/পুরুষ আলাদা হতে পারে), লেখা-ফলের বাছাই (Negative/Positive)। ল্যাব রিপোর্ট দিতে গেলে ঘরগুলো ভরা পায়,
// শুধু ফল লেখে; বেশি/কম চিহ্ন নিজে থেকে বসে। ল্যাব নিজের মেশিনের মাত্রা রাখতে পারে (labs/{uid}.ranges)।
// সব ল্যাবে একই ছক — তাই রোগী/ডাক্তার একই পরীক্ষার আগের-পরের ফল পাশাপাশি দেখেন।
// config/labTests: { tests: [...], updatedAt } — অ্যাডমিন প্যানেল থেকে যোগ/বদল/লুকানো; না থাকলে ডিফল্ট।
const fb = () => require('./firebase');
const C = require('./community');
const DEFAULTS = require('./labtests-defaults.json');
const { clip } = require('./text');

const TTL = 5 * 60 * 1000;
const MAX_TESTS = 200;
const MAX_PARAMS = 40;

let current = null;
let loadedAt = 0;

const clone = (x) => JSON.parse(JSON.stringify(x));

/** খোঁজার জন্য নাম: ছোট হাতের, শুধু অক্ষর-সংখ্যা, এক ফাঁকা */
function norm(s) {
  return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function merge(saved) {
  const out = clone(DEFAULTS);
  if (!Array.isArray(saved)) return out;
  const byCode = new Map(saved.map((t) => [t.code, t]));
  const merged = out.map((d) => byCode.get(d.code) || d);
  const known = new Set(out.map((d) => d.code));
  return [...merged, ...saved.filter((t) => !known.has(t.code))];
}

async function load(now = Date.now()) {
  if (current && now - loadedAt < TTL) return current;
  try {
    const s = await fb().db.doc('config/labTests').get();
    current = merge(s.exists ? s.get('tests') : null);
    loadedAt = now;
  } catch (e) {
    console.error('labtests load failed', e.message);
    if (!current) current = merge(null);
  }
  return current;
}

function tests() {
  return current || merge(null);
}

/** ডাক্তারের লেখা পরীক্ষার নাম → ছক ("S. Creatinine", "CBC with ESR", "Urine R/E") */
function match(name, list = tests()) {
  const n = norm(name);
  if (!n) return null;
  const tries = [n, n.replace(/^(s|serum|blood|plasma)\s+/, ''), n.replace(/\s+(test|level|levels)$/, '')];
  for (const t of list) {
    if (t.hidden) continue;
    const keys = [t.code, t.name, ...(t.aliases || [])].map(norm);
    if (tries.some((x) => keys.includes(x))) return t;
  }
  return null;
}

/** স্বাভাবিক মাত্রা: [নিচ, ওপর] (null = সীমা নেই), লেখায় */
function rangeFor(p, gender) {
  const r = (gender === 'female' && p.f) || (gender === 'male' && p.m) || p.r || null;
  if (!r) {
    // নারী/পুরুষ না জানলে দুটোই লেখা
    if (p.m && p.f) return { low: null, high: null, range: `M: ${rangeText(p.m)}, F: ${rangeText(p.f)}` };
    return { low: null, high: null, range: p.text || '' };
  }
  return { low: r[0] ?? null, high: r[1] ?? null, range: rangeText(r) };
}

function rangeText(r) {
  const [lo, hi] = r;
  if (lo != null && hi != null) return `${lo} – ${hi}`;
  if (hi != null) return `< ${hi}`;
  if (lo != null) return `> ${lo}`;
  return '';
}

/** ফল সংখ্যা হলে মাত্রার সাথে মিলিয়ে বেশি/কম */
function flagOf(result, low, high) {
  const v = Number(String(result || '').replace(/,/g, '').trim());
  if (!String(result || '').trim() || !Number.isFinite(v)) return '';
  if (high != null && v > high) return 'high';
  if (low != null && v < low) return 'low';
  return '';
}

/** ল্যাবের রিপোর্ট ফর্ম: প্রতিটা পরীক্ষার ঘর, ল্যাবের নিজের মাত্রা থাকলে সেটা */
function formFor(testName, gender, labRanges = {}) {
  const t = match(testName);
  if (!t) return { test: testName, code: null, name: testName, params: [] };
  return {
    test: testName,
    code: t.code,
    name: t.name,
    params: t.params.map((p) => {
      const own = labRanges[`${t.code}.${p.key}`];
      const base = rangeFor(p, gender);
      const rg = own ? { low: own.low ?? null, high: own.high ?? null, range: own.range || rangeText([own.low ?? null, own.high ?? null]) } : base;
      return {
        key: p.key, name: p.name, unit: p.unit || '', ...rg,
        text: p.text || '', options: p.options || [], long: p.long === true,
      };
    }),
  };
}

// ---------------------------------------------------------------------------
// অ্যাডমিনের সম্পাদনা
// ---------------------------------------------------------------------------

const num = (v) => (v === null || v === '' || v === undefined ? null : Number.isFinite(Number(v)) ? Number(v) : NaN);

function pair(v) {
  if (!Array.isArray(v) || v.length !== 2) return null;
  const a = num(v[0]);
  const b = num(v[1]);
  if (Number.isNaN(a) || Number.isNaN(b)) return NaN;
  if (a == null && b == null) return null;
  if (a != null && b != null && a > b) return NaN;
  return [a, b];
}

function cleanTest(req, t, taken) {
  const E = (en, bn) => new (fb().HttpError)(400, C.tr(req, en, bn));
  const name = C.cleanText(String(t?.name || ''), 80);
  if (!name) throw E('Write the test name', 'পরীক্ষার নাম লিখুন');
  const bn = C.cleanText(String(t?.bn || ''), 80) || '';
  let code = typeof t.code === 'string' && /^[a-z0-9_]{1,40}$/.test(t.code) ? t.code : null;
  if (!code) {
    const base = clip(norm(name).replace(/\s+/g, '_'), 30) || 'test';
    code = base;
    for (let i = 2; taken.has(code); i++) code = `${base}_${i}`;
  }
  const aliases = [...new Set((Array.isArray(t.aliases) ? t.aliases : String(t.aliases || '').split(','))
    .map((a) => clip(String(a).trim().toLowerCase(), 60)).filter(Boolean))].slice(0, 20);
  const raw = Array.isArray(t.params) ? t.params : [];
  if (!raw.length || raw.length > MAX_PARAMS) throw E(`1–${MAX_PARAMS} rows`, `১–${MAX_PARAMS}টা ঘর`);
  const keys = new Set();
  const params = raw.map((p, i) => {
    const pn = C.cleanText(String(p?.name || ''), 60);
    if (!pn) throw E(`Row ${i + 1}: write the name`, `ঘর ${i + 1}: নাম লিখুন`);
    let key = typeof p.key === 'string' && /^[a-z0-9_]{1,30}$/.test(p.key) ? p.key : clip(norm(pn).replace(/\s+/g, '_'), 24) || `p${i + 1}`;
    while (keys.has(key)) key = `${key}_${i + 1}`;
    keys.add(key);
    const out = { key, name: pn };
    const unit = clip(String(p.unit || '').trim(), 20);
    if (unit) out.unit = unit;
    for (const k of ['r', 'm', 'f']) {
      const v = pair(p[k]);
      if (Number.isNaN(v)) throw E(`${pn}: check the normal range`, `${pn}: স্বাভাবিক মাত্রা দেখে নিন`);
      if (v) out[k] = v;
    }
    const text = clip(String(p.text || '').trim(), 40);
    if (text) out.text = text;
    const options = (Array.isArray(p.options) ? p.options : String(p.options || '').split(','))
      .map((o) => clip(String(o).trim(), 30)).filter(Boolean).slice(0, 10);
    if (options.length) out.options = options;
    if (p.long === true) out.long = true;
    return out;
  });
  return { code, name, ...(bn ? { bn } : {}), aliases, params, ...(t.hidden === true ? { hidden: true } : {}) };
}

const actions = {
  /** সবাই: ছকের তালিকা (অ্যাপ/ওয়েব রেখে দেয়; ডাক্তার পরীক্ষার নাম বাছতে, ল্যাব রিপোর্ট দিতে) */
  async labtests_get() {
    await load();
    return { tests: tests() };
  },

  /** অ্যাডমিন: একটা পরীক্ষার ছক যোগ বা বদল (মোছা নয় — লুকানো) */
  async admin_labtests_set(req, user) {
    const { HttpError, db } = fb();
    if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    await load(Date.now() + TTL + 1);
    const list = tests();
    const t = req.body?.test || {};
    const exists = typeof t.code === 'string' && list.some((x) => x.code === t.code);
    if (!exists && list.length >= MAX_TESTS) throw new HttpError(400, C.tr(req, `Up to ${MAX_TESTS} tests`, `সর্বোচ্চ ${MAX_TESTS}টা পরীক্ষা`));
    const clean = cleanTest(req, exists ? t : { ...t, code: null }, new Set(list.map((x) => x.code)));
    // অন্য পরীক্ষার নামের সাথে মিলে গেলে ভুল ছক বসবে
    const clash = list.find((x) => x.code !== clean.code && !x.hidden && !clean.hidden
      && [x.name, ...(x.aliases || [])].map(norm).some((k) => [clean.name, ...clean.aliases].map(norm).includes(k)));
    if (clash) throw new HttpError(409, C.tr(req, `This name is already used by "${clash.name}"`, `এই নাম আগেই "${clash.name}"-এ আছে`));
    const next = exists ? list.map((x) => (x.code === clean.code ? clean : x)) : [...list, clean];
    // ডিফল্টের সাথে হুবহু এক হলে আলাদা রাখার দরকার নেই, কিন্তু সরল রাখতে পুরো তালিকাই রাখা
    await db.doc('config/labTests').set({ tests: next, updatedAt: Date.now() });
    current = next;
    loadedAt = Date.now();
    return { test: clean, tests: next };
  },
};

module.exports = { actions, load, tests, match, rangeFor, rangeText, flagOf, formFor, norm, merge, cleanTest };
