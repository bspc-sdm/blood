// মেমোরিতে Firestore/Auth-এর নকল — সার্ভারের কোড যেভাবে ব্যবহার করে ঠিক সেটুকু
const SENT = Symbol('fv');
const store = new Map(); // path -> data
const pushes = [];
let autoId = 0;
let txQueue = Promise.resolve();

const clone = (x) => (x === undefined ? undefined : JSON.parse(JSON.stringify(x)));
const getPath = (o, p) => String(p).split('.').reduce((a, k) => (a == null ? undefined : a[k]), o);

function applyValue(cur, v) {
  if (v && v[SENT]) {
    const s = v[SENT];
    if (s.op === 'delete') return undefined;
    if (s.op === 'inc') return (cur || 0) + s.n;
    if (s.op === 'union') return [...new Set([...(cur || []), ...s.v])];
    if (s.op === 'remove') return (cur || []).filter((x) => !s.v.includes(x));
    if (s.op === 'ts') return Date.now();
  }
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    const out = {};
    for (const [k, x] of Object.entries(v)) {
      const r = applyValue(undefined, x);
      if (r !== undefined) out[k] = r;
    }
    return out;
  }
  return clone(v);
}

function setPath(o, path, v) {
  const keys = path.split('.');
  let cur = o;
  for (let i = 0; i < keys.length - 1; i++) {
    if (cur[keys[i]] == null || typeof cur[keys[i]] !== 'object') cur[keys[i]] = {};
    cur = cur[keys[i]];
  }
  const last = keys[keys.length - 1];
  const r = applyValue(cur[last], v);
  if (r === undefined) delete cur[last];
  else cur[last] = r;
}

function deepMerge(base, add) {
  for (const [k, v] of Object.entries(add)) {
    if (v && typeof v === 'object' && !Array.isArray(v) && !v[SENT]) {
      if (!base[k] || typeof base[k] !== 'object' || Array.isArray(base[k])) base[k] = {};
      deepMerge(base[k], v);
    } else {
      const r = applyValue(base[k], v);
      if (r === undefined) delete base[k];
      else base[k] = r;
    }
  }
  return base;
}

function snap(path) {
  const data = store.get(path);
  const id = path.split('/').pop();
  return {
    id, exists: data !== undefined, ref: docRef(path),
    data: () => clone(data), get: (p) => clone(getPath(data, p)),
  };
}

function docRef(path) {
  return {
    id: path.split('/').pop(), path,
    get: async () => snap(path),
    set: async (data, opt) => { write.set(path, data, opt); },
    update: async (data) => { write.update(path, data); },
    delete: async () => { store.delete(path); },
    collection: (name) => collection(`${path}/${name}`),
    // আসলের মতো: ডকের parent = কালেকশন, কালেকশনের parent = তার উপরের ডক (না থাকলে null)
    get parent() {
      const parts = path.split('/');
      const cpath = parts.slice(0, -1).join('/');
      const up = parts.length > 2 ? parts.slice(0, -2).join('/') : null;
      return { id: parts[parts.length - 2], path: cpath, parent: up ? docRef(up) : null };
    },
  };
}

const write = {
  set(path, data, opt) {
    if (opt && opt.merge && store.has(path)) store.set(path, deepMerge(clone(store.get(path)), data));
    else store.set(path, applyValue(undefined, data));
  },
  update(path, data) {
    if (!store.has(path)) throw Object.assign(new Error(`NOT_FOUND ${path}`), { code: 5 });
    if (!Object.keys(data).length) throw new Error('empty update');
    const cur = clone(store.get(path));
    for (const [k, v] of Object.entries(data)) setPath(cur, k, v);
    store.set(path, cur);
  },
};

function collection(cpath, filters = [], lim = Infinity, ord = null, dir = 'asc', after = null) {
  const q = {
    where: (f, op, v) => collection(cpath, [...filters, [f, op, v]], lim, ord, dir, after),
    // orderBy + startAt/endAt (geohash-এর ঘর ধরে খোঁজা) — ঘর না থাকা ডক বাদ পড়ে, আসলের মতো;
    // সাজানো হয় limit-এর আগে (orderBy('at', 'desc').limit(n) = নতুন n-টা)
    orderBy: (f, d = 'asc') => collection(cpath, filters, lim, f, d, after),
    startAt: (v) => collection(cpath, [...filters, [ord, '>=', v]], lim, ord, dir, after),
    endAt: (v) => collection(cpath, [...filters, [ord, '<=', v]], lim, ord, dir, after),
    limit: (n) => collection(cpath, filters, n, ord, dir, after),
    // ডক দিয়ে কার্সর (পাতায় পাতায় পড়া): একই সাজানোয় সেই ডকের পরেরগুলো; অন্য কিছু দিলে আগের মতো উপেক্ষা
    startAfter: (v) => (v && typeof v === 'object' && v.ref && v.ref.path ? collection(cpath, filters, lim, ord, dir, v.ref.path) : q),
    select: () => q,
    get: async () => {
      const depth = cpath.split('/').length + 1;
      const docs = [...store.keys()].filter((p) => p.startsWith(`${cpath}/`) && p.split('/').length === depth)
        .filter((p) => filters.every(([f, op, v]) => {
          const x = getPath(store.get(p), f);
          if (op === '==') return JSON.stringify(x) === JSON.stringify(v);
          if (op === 'array-contains') return Array.isArray(x) && x.includes(v);
          if (op === 'in') return v.includes(x);
          if (op === '>=') return x >= v;
          if (op === '<=') return x <= v;
          if (op === '<') return x < v;
          if (op === '>') return x > v;
          throw new Error(`op ${op}`);
        }))
        .sort((a, b) => {
          if (!ord) return 0;
          const x = getPath(store.get(a), ord);
          const y = getPath(store.get(b), ord);
          const c = x === y ? 0 : x == null ? -1 : y == null ? 1 : x < y ? -1 : 1;
          return dir === 'desc' ? -c : c;
        })
        .filter((p, i, all) => !after || i > all.indexOf(after))
        .slice(0, lim).map(snap);
      return { docs, empty: !docs.length, size: docs.length, forEach: (fn) => docs.forEach(fn) };
    },
    count: () => ({ get: async () => { const r = await q.get(); return { data: () => ({ count: r.size }) }; } }),
    add: async (data) => {
      const id = `a${(++autoId).toString().padStart(6, '0')}`;
      write.set(`${cpath}/${id}`, data);
      return docRef(`${cpath}/${id}`);
    },
    doc: (id) => docRef(`${cpath}/${id || `a${(++autoId).toString().padStart(6, '0')}`}`),
  };
  return q;
}

const db = {
  doc: (p) => docRef(p),
  collection: (c) => collection(c),
  getAll: async (...refs) => refs.map((r) => snap(r.path)),
  // ডক আর তার নিচের সব সাব-কালেকশন
  recursiveDelete: async (ref) => {
    const p = ref.path;
    [...store.keys()].filter((k) => k === p || k.startsWith(`${p}/`)).forEach((k) => store.delete(k));
  },
  // আসলের মতো একটার পর একটা (একই ডক দুটো transaction-এ একসাথে পড়ে-লিখলে আসলে একটা আবার চলে — এখানে পরেরটা অপেক্ষা করে)
  runTransaction: (fn) => {
    const run = txQueue.then(() => fn({
      get: async (r) => (r.path ? snap(r.path) : r.get()),
      getAll: async (...refs) => refs.map((r) => snap(r.path)),
      set: (r, d, o) => write.set(r.path, d, o),
      update: (r, d) => write.update(r.path, d),
      delete: (r) => store.delete(r.path),
    }));
    txQueue = run.catch(() => {});
    return run;
  },
  batch: () => {
    const ops = [];
    const b = {
      set: (r, d, o) => { ops.push(() => write.set(r.path, d, o)); return b; },
      update: (r, d) => { ops.push(() => write.update(r.path, d)); return b; },
      delete: (r) => { ops.push(() => store.delete(r.path)); return b; },
      commit: async () => ops.forEach((f) => f()),
    };
    return b;
  },
};

const FieldValue = {
  delete: () => ({ [SENT]: { op: 'delete' } }),
  increment: (n) => ({ [SENT]: { op: 'inc', n } }),
  arrayUnion: (...v) => ({ [SENT]: { op: 'union', v } }),
  arrayRemove: (...v) => ({ [SENT]: { op: 'remove', v } }),
  serverTimestamp: () => ({ [SENT]: { op: 'ts' } }),
};

class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    if (code) this.code = code;
  }
}

const users = new Map(); // uid -> { uid, email, displayName }
const admin = {
  auth: () => ({
    verifyIdToken: async (t) => { const u = users.get(t); if (!u) throw new Error('bad token'); return { uid: u.uid, email: u.email, email_verified: true }; },
    getUser: async (uid) => { const u = users.get(uid); if (!u) throw Object.assign(new Error('nf'), { code: 'auth/user-not-found' }); return { ...u, disabled: false }; },
    revokeRefreshTokens: async () => {},
    createCustomToken: async (uid) => `ctok_${uid}`,
    deleteUser: async (uid) => { users.delete(uid); },
    createUser: async (d) => {
      const uid = d.uid || `u${(++autoId).toString().padStart(8, '0')}`;
      if (users.has(uid)) throw Object.assign(new Error('exists'), { code: 'auth/uid-already-exists' });
      if (d.email && [...users.values()].some((x) => x.email === d.email)) throw Object.assign(new Error('exists'), { code: 'auth/email-already-exists' });
      users.set(uid, { uid, email: d.email, displayName: d.displayName });
      return users.get(uid);
    },
    updateUser: async (uid, d) => { const u = users.get(uid); if (!u) throw Object.assign(new Error('nf'), { code: 'auth/user-not-found' }); Object.assign(u, d); return u; },
    getUserByEmail: async (e) => { const u = [...users.values()].find((x) => x.email === e); if (!u) throw Object.assign(new Error('nf'), { code: 'auth/user-not-found' }); return u; },
  }),
  firestore: { FieldValue },
};

function handler(fn) {
  return async (req, res) => {
    try {
      res.status(200).json((await fn(req, res)) || { ok: true });
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) console.error(e);
      res.status(status).json({ error: e.message, ...(e.code && typeof e.code === 'string' ? { code: e.code } : {}) });
    }
  };
}

module.exports = {
  store, pushes, users,
  firebase: {
    addInbox: async () => {}, admin, db, FieldValue, HttpError, handler,
    verifyUser: async () => { throw new HttpError(401, 'x'); },
    decodeToken: async (req, t) => admin.auth().verifyIdToken(t),
    accountDisabledError: async () => new HttpError(403, 'disabled'),
    sendPush: async (items) => { items.forEach((i) => pushes.push(i)); return items.length; },
    tokensOf: (u) => [`tok_${u.id}`],
  },
};
