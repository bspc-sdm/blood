// Firestore-এর মতো ডাটাবেস, PostgreSQL-এ (ফেজ ১: Firestore → Contabo VPS-এর PostgreSQL)।
// সার্ভারের কোড আগের মতোই db.collection(...).doc(...).get()/set()/where()... লেখে — এই ফাইল সেগুলো SQL-এ বদলায়।
// সার্ভার যতটুকু ব্যবহার করে ঠিক ততটুকু (test/support/fakefb.js-এর তালিকা), আর Firestore-এর নিয়মে:
//  - এক টেবিল docs(path, coll, grp, id, data jsonb) — scripts/pg-schema.sql
//  - where: ধরন মিলিয়ে তুলনা (সংখ্যা সংখ্যার সাথে, লেখা লেখার সাথে, বাইট ধরে); ঘর না থাকলে ডক বাদ
//  - orderBy না দিলে id-র ক্রম; orderBy-র ঘর না থাকলে ডক বাদ; শেষে id দিয়ে টাই ভাঙা
//  - transaction: আসল PostgreSQL transaction, পড়া ডকে লক (advisory + SELECT ... FOR UPDATE), deadlock হলে আবার চেষ্টা
//  - সব মান parameter হিসেবে যায়; ঘরের নাম শুধু [A-Za-z_][A-Za-z0-9_]* হলে SQL-এ সরাসরি বসে (index-এর জন্য), নইলে parameter
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------- মানের ধরন

class Timestamp {
  constructor(seconds, nanoseconds) {
    if (!Number.isInteger(seconds) || !Number.isInteger(nanoseconds) || nanoseconds < 0 || nanoseconds > 999999999) {
      throw new Error(`Invalid Timestamp(${seconds}, ${nanoseconds})`);
    }
    // firebase-admin-এর Timestamp-এর মতো একই নাম — JSON-এ একই রূপ ({"_seconds":..,"_nanoseconds":..})
    this._seconds = seconds;
    this._nanoseconds = nanoseconds;
  }
  static now() { return Timestamp.fromMillis(Date.now()); }
  static fromDate(d) { return Timestamp.fromMillis(d.getTime()); }
  static fromMillis(ms) {
    const s = Math.floor(ms / 1000);
    return new Timestamp(s, Math.floor((ms - s * 1000) * 1e6));
  }
  get seconds() { return this._seconds; }
  get nanoseconds() { return this._nanoseconds; }
  toMillis() { return this._seconds * 1000 + Math.floor(this._nanoseconds / 1e6); }
  toDate() { return new Date(this._seconds * 1000 + Math.round(this._nanoseconds / 1e6)); }
  isEqual(o) { return !!o && o._seconds === this._seconds && o._nanoseconds === this._nanoseconds; }
  valueOf() {
    // firebase-admin-এর মতো: < > দিয়ে তুলনা করা যায় এমন লেখা
    const s = this._seconds - -62135596800;
    return `${String(s).padStart(12, '0')}.${String(this._nanoseconds).padStart(9, '0')}`;
  }
  toString() { return `Timestamp(seconds=${this._seconds}, nanoseconds=${this._nanoseconds})`; }
}

class GeoPoint {
  constructor(latitude, longitude) {
    this._latitude = latitude;
    this._longitude = longitude;
  }
  get latitude() { return this._latitude; }
  get longitude() { return this._longitude; }
  isEqual(o) { return !!o && o.latitude === this._latitude && o.longitude === this._longitude; }
}

class FieldPath {
  constructor(...segments) {
    if (!segments.length || segments.some((s) => typeof s !== 'string' || !s)) throw new Error('Invalid FieldPath');
    this.segments = segments;
  }
  static documentId() { return DOC_ID; }
  isEqual(o) { return !!o && Array.isArray(o.segments) && o.segments.join('\0') === this.segments.join('\0'); }
  toString() { return this.segments.join('.'); }
}
const DOC_ID = new FieldPath('__name__');

class FieldValue {
  constructor(op, arg) {
    this._op = op;
    this._arg = arg;
  }
  static delete() { return new FieldValue('delete'); }
  static serverTimestamp() { return new FieldValue('ts'); }
  static increment(n) {
    if (typeof n !== 'number') throw new Error('increment() needs a number');
    return new FieldValue('inc', n);
  }
  static arrayUnion(...v) { return new FieldValue('union', v); }
  static arrayRemove(...v) { return new FieldValue('remove', v); }
  isEqual(o) { return o instanceof FieldValue && o._op === this._op && JSON.stringify(o._arg) === JSON.stringify(this._arg); }
}

// firebase-admin-এর নিজের FieldValue/Timestamp/FieldPath এলেও চেনা (কেউ admin.firestore.* ব্যবহার করলে)
function sentinelOf(v) {
  if (v instanceof FieldValue) return v;
  if (!v || typeof v !== 'object') return null;
  switch (v.constructor && v.constructor.name) {
    case 'DeleteTransform': return FieldValue.delete();
    case 'ServerTimestampTransform': return FieldValue.serverTimestamp();
    case 'NumericIncrementTransform': return FieldValue.increment(v.operand);
    case 'ArrayUnionTransform': return FieldValue.arrayUnion(...v.elements);
    case 'ArrayRemoveTransform': return FieldValue.arrayRemove(...v.elements);
    default: return null;
  }
}
const isTimestampLike = (v) => v instanceof Timestamp
  || (v && typeof v === 'object' && v.constructor && v.constructor.name === 'Timestamp'
    && Number.isInteger(v.seconds) && Number.isInteger(v.nanoseconds) && typeof v.toMillis === 'function');
const isGeoLike = (v) => v instanceof GeoPoint
  || (v && typeof v === 'object' && v.constructor && v.constructor.name === 'GeoPoint' && typeof v.latitude === 'number');
const isRefLike = (v) => v instanceof DocumentReference
  || (v && typeof v === 'object' && v.constructor && v.constructor.name === 'DocumentReference' && typeof v.path === 'string');
const isPlainObject = (v) => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  const proto = Object.getPrototypeOf(v);
  return proto === Object.prototype || proto === null;
};

// ---------------------------------------------------------------- jsonb-তে রূপ (encode / decode)

const MARKERS = new Set(['$ts', '$geo', '$ref', '$bytes', '$num']);
const escKey = (k) => (k.startsWith('$') ? `$${k}` : k);
const unescKey = (k) => (k.startsWith('$$') ? k.slice(1) : k);

/** লেখার মান → jsonb-তে রাখার রূপ (FieldValue এখানে আসার আগেই আলাদা করা হয়) */
function encode(v, where = 'value') {
  if (v === null) return null;
  if (typeof v === 'string' || typeof v === 'boolean') return v;
  if (typeof v === 'number') return Number.isFinite(v) ? v : { $num: String(v) };
  if (typeof v === 'bigint') return Number(v);
  if (v instanceof Date) return encodeTs(Timestamp.fromDate(v));
  if (isTimestampLike(v)) return encodeTs(v);
  if (isGeoLike(v)) return { $geo: { lat: v.latitude, lng: v.longitude } };
  if (isRefLike(v)) return { $ref: v.path };
  if (Buffer.isBuffer(v) || v instanceof Uint8Array) return { $bytes: Buffer.from(v).toString('base64') };
  if (sentinelOf(v)) throw new Error(`FieldValue is not allowed in ${where}`);
  if (Array.isArray(v)) {
    return v.map((x) => {
      if (Array.isArray(x)) throw new Error('Nested arrays are not supported');
      return x === undefined ? null : encode(x, 'an array');
    });
  }
  if (typeof v === 'object') {
    const out = {};
    for (const [k, x] of Object.entries(v)) if (x !== undefined) out[escKey(k)] = encode(x, where);
    return out;
  }
  // undefined, function, symbol: Firestore এগুলো রাখতে দেয় না
  throw new Error(`Cannot store ${typeof v} in ${where}`);
}

function encodeTs(t) { return { $ts: { s: t.seconds, n: t.nanoseconds } }; }

/** jsonb-র রূপ → কোডের মান (Timestamp, GeoPoint, DocumentReference, Buffer) */
function decode(v, store) {
  if (v === null || typeof v !== 'object') return v;
  if (Array.isArray(v)) return v.map((x) => decode(x, store));
  const keys = Object.keys(v);
  if (keys.length === 1 && MARKERS.has(keys[0])) {
    const x = v[keys[0]];
    switch (keys[0]) {
      case '$ts': return new Timestamp(x.s, x.n);
      case '$geo': return new GeoPoint(x.lat, x.lng);
      case '$ref': return store ? store.doc(x) : x;
      case '$bytes': return Buffer.from(x, 'base64');
      case '$num': return Number(x);
      default: break;
    }
  }
  const out = {};
  for (const k of keys) out[unescKey(k)] = decode(v[k], store);
  return out;
}

/** তুলনার জন্য ধরনের ক্রম — SQL-এর fs_rank()-এর সাথে মেলে */
function rankOf(e) {
  if (e === null) return 0;
  if (typeof e === 'boolean') return 1;
  if (typeof e === 'number') return 2;
  if (typeof e === 'string') return 4;
  if (Array.isArray(e)) return 9;
  const k = Object.keys(e);
  if (k.length === 1 && MARKERS.has(k[0])) return { $ts: 3, $num: 2, $bytes: 5, $ref: 6, $geo: 7 }[k[0]];
  return 10;
}

/** ধরন অনুযায়ী তুলনার চাবি: 'num' → fs_num, 'str' → fs_str, 'raw' → jsonb নিজে, 'none' → (null) সব সমান */
function keyKind(rank) {
  if (rank === 0) return 'none';
  if (rank === 1 || rank === 2 || rank === 3) return 'num';
  if (rank === 4 || rank === 6) return 'str';
  return 'raw';
}

/** encoded মান → SQL-এ তুলনার parameter (সংখ্যা হুবহু লেখা হিসেবে, যাতে ন্যানোসেকেন্ড না হারায়) */
function keyParam(e, kind) {
  if (kind === 'num') {
    if (typeof e === 'boolean') return e ? '1' : '0';
    if (typeof e === 'number') return String(e);
    if (e && e.$ts) {
      const total = BigInt(e.$ts.s) * 1000000000n + BigInt(e.$ts.n);
      const neg = total < 0n;
      const abs = neg ? -total : total;
      return `${neg ? '-' : ''}${abs / 1000000000n}.${String(abs % 1000000000n).padStart(9, '0')}`;
    }
    return null; // NaN
  }
  if (kind === 'str') return typeof e === 'string' ? e : e.$ref;
  return JSON.stringify(e);
}

function deepEq(a, b) {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) return a.length === b.length && a.every((x, i) => deepEq(x, b[i]));
  const ka = Object.keys(a);
  return ka.length === Object.keys(b).length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEq(a[k], b[k]));
}

const cloneJson = (x) => (x === undefined ? undefined : JSON.parse(JSON.stringify(x)));

// ---------------------------------------------------------------- ঘরের ঠিকানা (field path)

/** 'a.b' বা FieldPath → escaped segments; documentId হলে null */
function segsOf(field) {
  let segs;
  if (typeof field === 'string') {
    if (field === '__name__') return null;
    segs = field.split('.');
  } else if (field && Array.isArray(field.segments)) {
    segs = field.segments;
    if (segs.length === 1 && segs[0] === '__name__') return null;
  } else {
    throw new Error(`Invalid field path: ${field}`);
  }
  if (!segs.length || segs.some((s) => typeof s !== 'string' || !s)) throw new Error(`Invalid field path: ${field}`);
  return segs.map(escKey);
}

function getAt(obj, segs) {
  let cur = obj;
  for (const s of segs) {
    if (!cur || typeof cur !== 'object' || Array.isArray(cur) || !Object.prototype.hasOwnProperty.call(cur, s)) return undefined;
    cur = cur[s];
  }
  return cur;
}

const isMap = (x) => !!x && typeof x === 'object' && !Array.isArray(x) && rankOf(x) === 10;

function setAt(obj, segs, val) {
  let cur = obj;
  for (let i = 0; i < segs.length - 1; i++) {
    if (!isMap(cur[segs[i]])) cur[segs[i]] = {};
    cur = cur[segs[i]];
  }
  cur[segs[segs.length - 1]] = val;
}

function deleteAt(obj, segs) {
  let cur = obj;
  for (let i = 0; i < segs.length - 1; i++) {
    if (!isMap(cur[segs[i]])) return;
    cur = cur[segs[i]];
  }
  delete cur[segs[segs.length - 1]];
}

// ---------------------------------------------------------------- ডকের ঠিকানা

function splitPath(p) {
  if (typeof p !== 'string' || !p) throw new Error(`Invalid path: ${p}`);
  const parts = p.split('/');
  if (parts.some((s) => !s || s === '.' || s === '..' || /^__.*__$/.test(s))) throw new Error(`Invalid path: ${p}`);
  return parts;
}

function docParts(p) {
  const parts = splitPath(p);
  if (parts.length % 2) throw new Error(`Not a document path: ${p}`);
  return { path: p, coll: parts.slice(0, -1).join('/'), grp: parts[parts.length - 2], id: parts[parts.length - 1] };
}

const autoId = () => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = crypto.randomBytes(40);
  let out = '';
  for (let i = 0; out.length < 20; i++) if (bytes[i] < 248) out += chars[bytes[i] % 62];
  return out;
};

// ---------------------------------------------------------------- SQL সাহায্য

class Params {
  constructor() { this.values = []; }
  add(v) { this.values.push(v); return `$${this.values.length}`; }
}

const SAFE_SEG = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** jsonb-র ভেতরের ঘর: নিরাপদ নাম হলে সরাসরি (expression index মেলে), নইলে parameter */
function fieldSql(segs, p) {
  if (segs.every((s) => SAFE_SEG.test(s))) return `(data${segs.map((s) => `->'${s}'`).join('')})`;
  return `(data #> ${p.add(segs)}::text[])`;
}

function keySql(x, kind) {
  if (kind === 'num') return `fs_num(${x})`;
  if (kind === 'str') return `(fs_str(${x}) COLLATE "C")`;
  return x;
}

function keyParamSql(e, kind, p) {
  if (kind === 'num') return `${p.add(keyParam(e, kind))}::numeric`;
  if (kind === 'str') return `(${p.add(keyParam(e, kind))}::text COLLATE "C")`;
  return `${p.add(keyParam(e, kind))}::jsonb`;
}

/** x (op) e — Firestore-এর ক্রমে, ধরন ভিন্ন হলে ধরনের ক্রমে। op: '<' '>' '=' */
function orderCmpSql(x, e, op, p) {
  const r = rankOf(e);
  const kind = keyKind(r);
  const sameRank = `fs_rank(${x}) = ${r}`;
  if (op === '=') {
    if (kind === 'none') return sameRank;
    if (kind === 'num' && keyParam(e, kind) === null) return `(${sameRank} AND fs_num(${x}) IS NULL)`;
    return `(${sameRank} AND ${keySql(x, kind)} = ${keyParamSql(e, kind, p)})`;
  }
  const rankCmp = `fs_rank(${x}) ${op} ${r}`;
  if (kind === 'none' || (kind === 'num' && keyParam(e, kind) === null)) return `(${rankCmp})`;
  return `(${rankCmp} OR (${sameRank} AND ${keySql(x, kind)} ${op} ${keyParamSql(e, kind, p)}))`;
}

const RETRYABLE = new Set(['40001', '40P01', '55P03']);

function fsError(code, name, message) {
  return Object.assign(new Error(`${code} ${name}: ${message}`), { code, details: message });
}

// ---------------------------------------------------------------- লেখা (set / update / delete) প্রয়োগ

/**
 * data-র ভেতরের FieldValue আলাদা করা: transforms-এ [segs, FieldValue], ফেরত encoded মান (FieldValue ছাড়া)।
 * merge: true হলে প্রতিটা পাতা আলাদা ঘর (fieldMask-এ যোগ হয়)।
 */
function splitValue(v, segs, transforms, mask) {
  const s = sentinelOf(v);
  if (s) {
    if (s._op === 'delete') {
      if (!mask) throw new Error('FieldValue.delete() is only allowed in update() or set() with merge');
      mask.push({ segs, del: true });
    } else {
      transforms.push([segs, s]);
    }
    return undefined;
  }
  if (isPlainObject(v)) {
    const keys = Object.keys(v).filter((k) => v[k] !== undefined);
    if (mask && keys.length) {
      // merge: ভেতরের map-এর প্রতিটা ঘর আলাদা মেশে
      for (const k of keys) {
        const out = splitValue(v[k], [...segs, escKey(k)], transforms, mask);
        if (out !== undefined) mask.push({ segs: [...segs, escKey(k)], val: out });
      }
      return undefined;
    }
    const out = {};
    for (const k of keys) {
      const x = splitValue(v[k], [...segs, escKey(k)], transforms, null);
      if (x !== undefined) out[escKey(k)] = x;
    }
    return out;
  }
  return encode(v);
}

function applyTransform(cur, fv, nowTs) {
  switch (fv._op) {
    case 'ts': return encodeTs(nowTs);
    case 'inc': return (typeof cur === 'number' ? cur : 0) + fv._arg;
    case 'union': {
      const arr = Array.isArray(cur) ? cur.slice() : [];
      for (const e of fv._arg.map((x) => encode(x, 'arrayUnion'))) if (!arr.some((a) => deepEq(a, e))) arr.push(e);
      return arr;
    }
    case 'remove': {
      const rm = fv._arg.map((x) => encode(x, 'arrayRemove'));
      return Array.isArray(cur) ? cur.filter((a) => !rm.some((e) => deepEq(a, e))) : [];
    }
    default: throw new Error(`Unknown FieldValue ${fv._op}`);
  }
}

/** একটা লেখা প্রয়োগ: cur = এখনকার encoded data (না থাকলে undefined) → নতুন data (মুছলে undefined) */
function applyOp(cur, op, nowTs) {
  if (op.type === 'delete') {
    if (op.mustExist && cur === undefined) throw fsError(5, 'NOT_FOUND', `No document to delete: ${op.path}`);
    return undefined;
  }
  if (op.type === 'create' && cur !== undefined) throw fsError(6, 'ALREADY_EXISTS', `Document already exists: ${op.path}`);
  if (op.type === 'update' && cur === undefined) throw fsError(5, 'NOT_FOUND', `No document to update: ${op.path}`);

  const transforms = [];
  let next;
  if (op.type === 'update') {
    next = cloneJson(cur);
    for (const [segs, v] of op.fields) {
      const s = sentinelOf(v);
      if (s && s._op === 'delete') deleteAt(next, segs);
      else {
        const out = splitValue(v, segs, transforms, null);
        if (out !== undefined) setAt(next, segs, out);
      }
    }
  } else if (op.type === 'set' && (op.merge || op.mergeFields)) {
    next = cur === undefined ? {} : cloneJson(cur);
    const mask = [];
    if (op.mergeFields) {
      for (const f of op.mergeFields) {
        const segs = segsOf(f);
        const raw = getAt(op.data, segs.map(unescKey));
        if (raw === undefined) continue;
        const s = sentinelOf(raw);
        if (s && s._op === 'delete') { mask.push({ segs, del: true }); continue; }
        const out = splitValue(raw, segs, transforms, null);
        if (out !== undefined) mask.push({ segs, val: out });
      }
    } else {
      for (const [k, v] of Object.entries(op.data)) {
        if (v === undefined) continue;
        const segs = [escKey(k)];
        const out = splitValue(v, segs, transforms, mask);
        // খালি map একটা পাতা — সেই ঘর {} হয়ে যায় (Firestore-এর মতো)
        if (out !== undefined) mask.push({ segs, val: out });
      }
    }
    for (const m of mask) {
      if (m.del) deleteAt(next, m.segs);
      else setAt(next, m.segs, m.val);
    }
  } else {
    // set (পুরো বদল) বা create
    next = splitValue(op.data, [], transforms, null) || {};
  }
  for (const [segs, fv] of transforms) setAt(next, segs, applyTransform(getAt(next, segs), fv, nowTs));
  return next;
}

function normalizeUpdateArgs(args) {
  // update({ 'a.b': 1 }) বা update('a.b', 1, FieldPath, 2, ...)
  if (args.length === 1 || (args.length === 2 && isPlainObject(args[0]) && args[1] && typeof args[1] === 'object' && 'exists' in args[1])) {
    const obj = args[0];
    if (!isPlainObject(obj)) throw new Error('update() needs an object');
    const fields = Object.entries(obj).filter(([, v]) => v !== undefined).map(([k, v]) => [segsOf(k), v]);
    if (!fields.length) throw new Error('update() needs at least one field');
    if (fields.some(([s]) => s === null)) throw new Error('Cannot update document id');
    return fields;
  }
  const fields = [];
  for (let i = 0; i + 1 < args.length; i += 2) fields.push([segsOf(args[i]), args[i + 1]]);
  if (!fields.length) throw new Error('update() needs at least one field');
  return fields;
}

// ---------------------------------------------------------------- Snapshot

class DocumentSnapshot {
  constructor(store, ref, raw, row) {
    this._store = store;
    this.ref = ref;
    this._raw = raw; // encoded data বা undefined
    this.id = ref.id;
    this.exists = raw !== undefined;
    this.readTime = Timestamp.now();
    this.createTime = row && row.created_at ? Timestamp.fromDate(row.created_at) : undefined;
    this.updateTime = row && row.updated_at ? Timestamp.fromDate(row.updated_at) : undefined;
  }
  data() { return this._raw === undefined ? undefined : decode(this._raw, this._store); }
  get(field) {
    if (this._raw === undefined) return undefined;
    const segs = segsOf(field);
    if (segs === null) return this.id;
    return decode(getAt(this._raw, segs), this._store);
  }
  isEqual(o) { return o instanceof DocumentSnapshot && o.ref.path === this.ref.path && deepEq(o._raw, this._raw); }
}
class QueryDocumentSnapshot extends DocumentSnapshot {}

class QuerySnapshot {
  constructor(query, docs) {
    this.query = query;
    this.docs = docs;
    this.size = docs.length;
    this.empty = docs.length === 0;
    this.readTime = Timestamp.now();
  }
  forEach(fn, thisArg) { this.docs.forEach(fn, thisArg); }
  docChanges() { return this.docs.map((doc, i) => ({ type: 'added', doc, oldIndex: -1, newIndex: i })); }
}

// ---------------------------------------------------------------- Query

// db.collection, query.where ইত্যাদি আলাদা করে ধরে ডাকলেও (const { doc } = db; doc('a/b')) কাজ করে —
// firebase-admin-এ যেমন চলে না, কিন্তু টেস্টের নকল ডাটাবেসে চলত আর কিছু টেস্ট সেভাবে মোড়ায়
const QUERY_METHODS = ['where', 'orderBy', 'limit', 'limitToLast', 'offset', 'select', 'startAt', 'startAfter', 'endAt', 'endBefore', 'get', 'count', 'doc', 'add'];
const DB_METHODS = ['collection', 'doc', 'collectionGroup', 'batch', 'getAll', 'runTransaction', 'recursiveDelete'];
function bindMethods(obj, names) {
  for (const m of names) if (typeof obj[m] === 'function') obj[m] = obj[m].bind(obj);
}

const RANGE_OPS = new Set(['<', '<=', '>', '>=']);
const ALL_OPS = new Set(['==', '!=', '<', '<=', '>', '>=', 'in', 'not-in', 'array-contains', 'array-contains-any']);

class Query {
  constructor(store, o) {
    this._store = store;
    this.firestore = store;
    this._o = {
      coll: null, group: null, filters: [], orders: [], limit: null, limitToLast: false, offset: 0,
      start: null, end: null, select: null, ...o,
    };
    bindMethods(this, QUERY_METHODS);
  }
  _with(patch) { return new Query(this._store, { ...this._o, ...patch }); }

  where(field, op, value) {
    if (field && typeof field === 'object' && !Array.isArray(field.segments) && field.constructor && /Filter/.test(field.constructor.name)) {
      throw new Error('Filter objects are not supported by pgstore');
    }
    if (!ALL_OPS.has(op)) throw new Error(`Invalid where() operator: ${op}`);
    if (value === undefined) throw new Error('where() value cannot be undefined');
    if ((op === 'in' || op === 'not-in' || op === 'array-contains-any') && (!Array.isArray(value) || !value.length)) {
      throw new Error(`'${op}' needs a non-empty array`);
    }
    return this._with({ filters: [...this._o.filters, { segs: segsOf(field), op, value }] });
  }
  orderBy(field, dir = 'asc') {
    if (dir !== 'asc' && dir !== 'desc') throw new Error(`Invalid orderBy direction: ${dir}`);
    if (this._o.start || this._o.end) throw new Error('orderBy() must come before startAt()/endAt()');
    return this._with({ orders: [...this._o.orders, { segs: segsOf(field), dir }] });
  }
  limit(n) { return this._with({ limit: Number(n), limitToLast: false }); }
  limitToLast(n) { return this._with({ limit: Number(n), limitToLast: true }); }
  offset(n) { return this._with({ offset: Number(n) }); }
  select(...fields) { return this._with({ select: fields.map((f) => segsOf(f)).filter(Boolean) }); }
  startAt(...v) { return this._with({ start: { values: v, inclusive: true } }); }
  startAfter(...v) { return this._with({ start: { values: v, inclusive: false } }); }
  endAt(...v) { return this._with({ end: { values: v, inclusive: true } }); }
  endBefore(...v) { return this._with({ end: { values: v, inclusive: false } }); }
  withConverter() { throw new Error('withConverter is not supported by pgstore'); }
  onSnapshot() { throw new Error('onSnapshot is not supported by pgstore'); }

  async get() { return this._store._runQuery(this, null); }
  count() {
    return { get: async () => { const n = await this._store._count(this); return { data: () => ({ count: n }) }; } };
  }

  // -------- SQL তৈরি
  /** সব সাজানোর ঘর: নিজের orderBy, তারপর বাকি অসমতা-ঘর (Firestore-এর নিয়মে), শেষে id */
  _orderKeys() {
    const o = this._o;
    const keys = o.orders.map((x) => ({ segs: x.segs, dir: x.dir }));
    const has = (segs) => keys.some((k) => (k.segs === null ? segs === null : segs !== null && k.segs.join('\0') === segs.join('\0')));
    const ineq = o.filters.filter((f) => RANGE_OPS.has(f.op) || f.op === '!=' || f.op === 'not-in');
    const extra = [];
    for (const f of ineq) if (!has(f.segs) && !extra.some((e) => (e === null ? f.segs === null : f.segs && e.join('\0') === f.segs.join('\0')))) extra.push(f.segs);
    extra.filter(Boolean).sort((a, b) => (a.join('.') < b.join('.') ? -1 : 1)).forEach((segs) => keys.push({ segs, dir: 'asc' }));
    if (!has(null)) keys.push({ segs: null, dir: keys.length ? keys[keys.length - 1].dir : 'asc' });
    return keys;
  }

  /** {text, values} — mode: 'rows' | 'count'; lock: transaction-এ FOR UPDATE */
  _sql(mode, lock) {
    const o = this._o;
    const p = new Params();
    const where = [];
    const nameCol = o.group ? 'path' : 'id';
    if (o.group) where.push(`grp = ${p.add(o.group)}`);
    else where.push(`coll = ${p.add(o.coll)}`);

    const knownRank = new Map(); // ঘর → ধরন জানা থাকলে (== বা range থেকে) সাজানো সহজ (index কাজে লাগে)
    for (const f of o.filters) where.push(this._filterSql(f, p, knownRank, nameCol));

    const keys = this._orderKeys();
    // orderBy-র ঘর না থাকলে ডক বাদ (Firestore-এর মতো)
    for (const k of keys) if (k.segs) where.push(`${fieldSql(k.segs, p)} IS NOT NULL`);

    // cursor (startAt/After, endAt/Before)
    if (o.start) where.push(this._cursorSql(o.start, keys, true, p, nameCol, knownRank));
    if (o.end) where.push(this._cursorSql(o.end, keys, false, p, nameCol, knownRank));

    const cols = mode === 'count' ? '1' : o.select && !o.select.length ? 'path, created_at, updated_at' : 'path, data, created_at, updated_at';
    let text = `SELECT ${cols} FROM docs WHERE ${where.join(' AND ')}`;
    const needOrder = mode !== 'count' || o.limit != null || o.offset;
    if (needOrder && mode !== 'count') {
      const flip = (d) => (o.limitToLast ? (d === 'asc' ? 'desc' : 'asc') : d);
      const parts = [];
      for (const k of keys) {
        const dir = flip(k.dir).toUpperCase();
        if (!k.segs) { parts.push(`${nameCol} ${dir}`); continue; }
        const x = fieldSql(k.segs, p);
        const kr = knownRank.get(k.segs.join('\0'));
        if (kr !== undefined) {
          const kind = keyKind(kr);
          if (kind !== 'none') parts.push(`${keySql(x, kind)} ${dir}`);
        } else {
          parts.push(`fs_rank(${x}) ${dir}`, `fs_num(${x}) ${dir}`, `(fs_str(${x}) COLLATE "C") ${dir}`, `${x} ${dir}`);
        }
      }
      text += ` ORDER BY ${parts.join(', ')}`;
    }
    if (o.limit != null) text += ` LIMIT ${p.add(o.limit)}`;
    if (o.offset) text += ` OFFSET ${p.add(o.offset)}`;
    if (lock && mode !== 'count') text += ' FOR UPDATE';
    if (mode === 'count') text = `SELECT count(*)::int AS n FROM (${text}) q`;
    return { text, values: p.values };
  }

  _nameValue(v) {
    // documentId-র তুলনার মান: কালেকশনে id, collectionGroup-এ পুরো ঠিকানা
    const o = this._o;
    let s = v;
    if (isRefLike(v)) s = v.path;
    if (typeof s !== 'string' || !s) throw new Error('documentId() value must be a non-empty string or DocumentReference');
    if (o.group) return s;
    if (s.includes('/')) {
      const d = docParts(s);
      if (d.coll !== o.coll) return { mismatch: true };
      return d.id;
    }
    return s;
  }

  _filterSql(f, p, knownRank, nameCol) {
    if (f.segs === null) {
      // documentId() দিয়ে খোঁজ
      const conv = (v) => this._nameValue(v);
      if (f.op === 'in' || f.op === 'not-in') {
        const ids = f.value.map(conv).filter((x) => typeof x === 'string');
        return f.op === 'in' ? `${nameCol} = ANY(${p.add(ids)}::text[])` : `NOT (${nameCol} = ANY(${p.add(ids)}::text[]))`;
      }
      const v = conv(f.value);
      if (typeof v !== 'string') return f.op === '!=' ? 'TRUE' : 'FALSE';
      const op = { '==': '=', '!=': '<>' }[f.op] || f.op;
      if (!['=', '<>', '<', '<=', '>', '>='].includes(op)) throw new Error(`Invalid documentId() operator: ${f.op}`);
      return `${nameCol} ${op} ${p.add(v)}`;
    }
    const x = fieldSql(f.segs, p);
    const fkey = f.segs.join('\0');
    switch (f.op) {
      case '==': {
        const e = encode(f.value, 'where()');
        knownRank.set(fkey, rankOf(e));
        const conds = [`${x} = ${p.add(JSON.stringify(e))}::jsonb`];
        // GIN index (jsonb_path_ops) কাজে লাগাতে একই শর্ত containment দিয়েও — শুধু সাধারণ (scalar) মানে, যেখানে দুটো হুবহু এক
        if (e === null || typeof e !== 'object') conds.push(`data @> ${p.add(JSON.stringify(nest(f.segs, e)))}::jsonb`);
        return `(${conds.join(' AND ')})`;
      }
      case '!=': {
        const e = encode(f.value, 'where()');
        return `(${x} IS NOT NULL AND ${x} <> 'null'::jsonb AND ${x} <> ${p.add(JSON.stringify(e))}::jsonb)`;
      }
      case 'in': {
        const list = f.value.map((v) => JSON.stringify(encode(v, 'where()')));
        return `${x} = ANY(${p.add(list)}::jsonb[])`;
      }
      case 'not-in': {
        const list = f.value.map((v) => JSON.stringify(encode(v, 'where()')));
        return `(${x} IS NOT NULL AND ${x} <> 'null'::jsonb AND NOT (${x} = ANY(${p.add(list)}::jsonb[])))`;
      }
      case 'array-contains': {
        const e = encode(f.value, 'where()');
        const conds = [`jsonb_typeof(${x}) = 'array'`, `${x} @> ${p.add(JSON.stringify([e]))}::jsonb`];
        // ভেতরের map/array হলে containment আংশিক মেলায় — তাই হুবহু মিলও দেখা
        if (e !== null && typeof e === 'object') conds.push(`EXISTS (SELECT 1 FROM jsonb_array_elements(${x}) ae WHERE ae = ${p.add(JSON.stringify(e))}::jsonb)`);
        else conds.push(`data @> ${p.add(JSON.stringify(nest(f.segs, [e])))}::jsonb`);
        return `(${conds.join(' AND ')})`;
      }
      case 'array-contains-any': {
        const list = f.value.map((v) => JSON.stringify(encode(v, 'where()')));
        return `(jsonb_typeof(${x}) = 'array' AND EXISTS (SELECT 1 FROM jsonb_array_elements(${x}) ae WHERE ae = ANY(${p.add(list)}::jsonb[])))`;
      }
      default: {
        // < <= > >= : শুধু একই ধরনের মানের সাথে (Firestore-এর মতো)
        const e = encode(f.value, 'where()');
        const r = rankOf(e);
        const kind = keyKind(r);
        knownRank.set(fkey, r);
        const base = `${x} IS NOT NULL AND fs_rank(${x}) = ${r}`;
        if (kind === 'none') return f.op === '<=' || f.op === '>=' ? `(${base})` : 'FALSE';
        if (kind === 'num' && keyParam(e, kind) === null) return 'FALSE';
        return `(${base} AND ${keySql(x, kind)} ${f.op} ${keyParamSql(e, kind, p)})`;
      }
    }
  }

  /** cursor-এর মানগুলো (encoded) — DocumentSnapshot দিলে তার orderBy-ঘরের মান আর ঠিকানা */
  _cursorValues(c, keys) {
    if (c.values.length === 1 && c.values[0] instanceof DocumentSnapshot) {
      const snap = c.values[0];
      if (!snap.exists) throw new Error('Cannot use a non-existent document as a cursor');
      return keys.map((k) => {
        if (!k.segs) return { name: this._o.group ? snap.ref.path : snap.id };
        const v = getAt(snap._raw, k.segs);
        if (v === undefined) throw new Error(`Cursor document is missing the orderBy field ${k.segs.join('.')}`);
        return { e: v };
      });
    }
    if (c.values.length > keys.length) throw new Error('Too many cursor values');
    return c.values.map((v, i) => {
      if (!keys[i].segs) {
        const nv = this._nameValue(v);
        if (typeof nv !== 'string') throw new Error('Cursor document id is from another collection');
        return { name: nv };
      }
      return { e: encode(v, 'cursor') };
    });
  }

  _cursorSql(c, keys, isStart, p, nameCol, knownRank) {
    const vals = this._cursorValues(c, keys);
    // isStart: সাজানোর দিকে ≥ (inclusive) বা > ; end: ≤ বা <
    const build = (i) => {
      if (i === vals.length) return c.inclusive ? 'TRUE' : 'FALSE';
      const k = keys[i];
      const forward = (k.dir === 'asc') === isStart; // asc-এ start মানে বড়
      const op = forward ? '>' : '<';
      // parameter শুধু যেটা SQL-এ যায় সেটার জন্য (না গেলে PostgreSQL তার ধরন বুঝতে পারে না)
      const x = k.segs ? fieldSql(k.segs, p) : null;
      const cmp = (o) => (x ? orderCmpSql(x, vals[i].e, o, p) : `${nameCol} ${o} ${p.add(vals[i].name)}`);
      const beyond = cmp(op);
      const rest = build(i + 1);
      if (rest === 'FALSE') return `(${beyond})`;
      if (rest === 'TRUE') return `(${beyond} OR ${cmp('=')})`;
      return `(${beyond} OR (${cmp('=')} AND ${rest}))`;
    };
    const parts = [build(0)];
    // প্রথম ঘরের দুই দিকের সীমা একই ধরনের হলে সেই ধরন আর সীমা আলাদা করেও লেখা (ফল একই; index ব্যবহার হয়) — geohash-এর খোঁজ
    const o = this._o;
    const k0 = keys[0];
    if (isStart && o.start && o.end && k0.segs) {
      const a = this._cursorValues(o.start, keys)[0];
      const b = this._cursorValues(o.end, keys)[0];
      if (a && b && a.e !== undefined && b.e !== undefined && rankOf(a.e) === rankOf(b.e) && ['num', 'str'].includes(keyKind(rankOf(a.e)))) {
        const r = rankOf(a.e);
        const kind = keyKind(r);
        if (keyParam(a.e, kind) !== null && keyParam(b.e, kind) !== null) {
          const [lo, hi] = k0.dir === 'asc' ? [a.e, b.e] : [b.e, a.e];
          const x = fieldSql(k0.segs, p);
          parts.push(`fs_rank(${x}) = ${r}`, `${keySql(x, kind)} >= ${keyParamSql(lo, kind, p)}`, `${keySql(x, kind)} <= ${keyParamSql(hi, kind, p)}`);
          knownRank.set(k0.segs.join('\0'), r);
        }
      }
    }
    return parts.join(' AND ');
  }
}

function nest(segs, v) {
  let out = v;
  for (let i = segs.length - 1; i >= 0; i--) out = { [segs[i]]: out };
  return out;
}

class CollectionReference extends Query {
  constructor(store, cpath) {
    const parts = splitPath(cpath);
    if (parts.length % 2 === 0) throw new Error(`Not a collection path: ${cpath}`);
    super(store, { coll: cpath });
    this.id = parts[parts.length - 1];
    this.path = cpath;
  }
  get parent() {
    const parts = this.path.split('/');
    return parts.length > 1 ? new DocumentReference(this._store, parts.slice(0, -1).join('/')) : null;
  }
  doc(id) {
    if (id === undefined) id = autoId();
    if (typeof id !== 'string' || !id) throw new Error('doc() id must be a non-empty string');
    return new DocumentReference(this._store, `${this.path}/${id}`);
  }
  async add(data) {
    const ref = this.doc();
    await ref.create(data);
    return ref;
  }
  async listDocuments() {
    const r = await this._store._query('SELECT path FROM docs WHERE coll = $1 ORDER BY id', [this.path]);
    return r.rows.map((x) => new DocumentReference(this._store, x.path));
  }
  isEqual(o) { return o instanceof CollectionReference && o.path === this.path; }
}

class DocumentReference {
  constructor(store, p) {
    const d = docParts(p);
    this._store = store;
    this.firestore = store;
    this.path = p;
    this.id = d.id;
  }
  get parent() { return new CollectionReference(this._store, this.path.split('/').slice(0, -1).join('/')); }
  collection(name) { return new CollectionReference(this._store, `${this.path}/${name}`); }
  async get() { return (await this._store._getDocs([this], null))[0]; }
  async set(data, opts) { return (await this._store._commit([setOp(this, data, opts)]))[0]; }
  async update(...args) { return (await this._store._commit([{ type: 'update', path: this.path, fields: normalizeUpdateArgs(args) }]))[0]; }
  async create(data) { return (await this._store._commit([{ type: 'create', path: this.path, data: checkData(data) }]))[0]; }
  async delete(pre) { return (await this._store._commit([{ type: 'delete', path: this.path, mustExist: !!(pre && pre.exists) }]))[0]; }
  async listCollections() { return this._store._listCollections(this.path); }
  isEqual(o) { return isRefLike(o) && o.path === this.path; }
}

function checkData(data) {
  if (!isPlainObject(data)) throw new Error('Document data must be an object');
  return data;
}

function setOp(ref, data, opts) {
  checkData(data);
  const o = opts || {};
  return { type: 'set', path: ref.path, data, merge: !!o.merge, mergeFields: Array.isArray(o.mergeFields) ? o.mergeFields : null };
}

// ---------------------------------------------------------------- batch / transaction

class WriteBatch {
  constructor(store) { this._store = store; this._ops = []; this._done = false; }
  _add(op) { if (this._done) throw new Error('Batch already committed'); this._ops.push(op); return this; }
  set(ref, data, opts) { return this._add(setOp(ref, data, opts)); }
  update(ref, ...args) { return this._add({ type: 'update', path: ref.path, fields: normalizeUpdateArgs(args) }); }
  create(ref, data) { return this._add({ type: 'create', path: ref.path, data: checkData(data) }); }
  delete(ref, pre) { return this._add({ type: 'delete', path: ref.path, mustExist: !!(pre && pre.exists) }); }
  async commit() {
    this._done = true;
    return this._ops.length ? this._store._commit(this._ops) : [];
  }
}

class Transaction {
  constructor(store, client) {
    this._store = store;
    this._client = client;
    this._ops = [];
    bindMethods(this, ['get', 'getAll', 'set', 'update', 'create', 'delete']);
  }
  async get(refOrQuery) {
    if (refOrQuery instanceof Query) return this._store._runQuery(refOrQuery, this._client);
    if (!isRefLike(refOrQuery)) throw new Error('Transaction.get() needs a DocumentReference or Query');
    return (await this._store._getDocs([refOrQuery], this._client))[0];
  }
  async getAll(...refs) {
    const list = refs.filter((r) => isRefLike(r));
    return this._store._getDocs(list, this._client);
  }
  set(ref, data, opts) { this._ops.push(setOp(ref, data, opts)); return this; }
  update(ref, ...args) { this._ops.push({ type: 'update', path: ref.path, fields: normalizeUpdateArgs(args) }); return this; }
  create(ref, data) { this._ops.push({ type: 'create', path: ref.path, data: checkData(data) }); return this; }
  delete(ref, pre) { this._ops.push({ type: 'delete', path: ref.path, mustExist: !!(pre && pre.exists) }); return this; }
}

// ---------------------------------------------------------------- ডাটাবেস

const SCHEMA_FILE = path.join(__dirname, '..', 'scripts', 'pg-schema.sql');
const LOCK_CLASS = 0x4c46; // advisory lock-এর শ্রেণি ('LF'); objid = hashtext(path)

class PgFirestore {
  /**
   * opts: { connectionString, schema, poolMax, autoSchema, pool }
   * schema: আলাদা PostgreSQL schema (টেস্টে প্রতি ফাইলে নতুন); না দিলে search_path যেমন আছে।
   */
  constructor(opts = {}) {
    this._opts = opts;
    this._pool = opts.pool || null;
    this._schemaReady = null;
    bindMethods(this, DB_METHODS);
  }

  get pool() {
    if (!this._pool) {
      const { Pool } = require('pg');
      const cs = this._opts.connectionString || process.env.DATABASE_URL;
      if (!cs) throw new Error('DATABASE_URL সেট করা নেই');
      const options = [];
      if (this._opts.schema) {
        if (!/^[a-z_][a-z0-9_]*$/.test(this._opts.schema)) throw new Error('Invalid schema name');
        options.push(`-c search_path=${this._opts.schema}`);
      }
      // অনেকক্ষণ লক না পেলে ভুল ফেরত (transaction তখন আবার চেষ্টা করে) — চিরকাল আটকে থাকে না
      options.push(`-c lock_timeout=${Number(process.env.PG_LOCK_TIMEOUT_MS) || 10000}`);
      this._pool = new Pool({
        connectionString: cs,
        max: Number(this._opts.poolMax || process.env.PG_POOL_MAX) || 10,
        idleTimeoutMillis: 30000,
        connectionTimeoutMillis: 15000,
        allowExitOnIdle: true,
        options: options.join(' '),
      });
      this._pool.on('error', (e) => console.error('pg pool error', e.message));
    }
    return this._pool;
  }

  /** scripts/pg-schema.sql চালানো (একবার; একসাথে কয়েকটা প্রসেস চালু হলেও advisory lock দিয়ে একটার পর একটা) */
  ensureSchema() {
    if (!this._schemaReady) {
      this._schemaReady = (async () => {
        const sql = fs.readFileSync(SCHEMA_FILE, 'utf8');
        const client = await this.pool.connect();
        try {
          await client.query('BEGIN');
          await client.query('SELECT pg_advisory_xact_lock($1, 0)', [LOCK_CLASS]);
          await client.query(sql);
          await client.query('COMMIT');
        } catch (e) {
          await client.query('ROLLBACK').catch(() => {});
          throw e;
        } finally {
          client.release();
        }
      })().catch((e) => { this._schemaReady = null; throw e; });
    }
    return this._schemaReady;
  }

  async _ready() {
    const auto = this._opts.autoSchema !== undefined ? this._opts.autoSchema : process.env.PG_AUTO_SCHEMA !== '0';
    if (auto) await this.ensureSchema();
  }

  async _query(text, values, client) {
    await this._ready();
    return (client || this.pool).query(text, values);
  }

  // -------- Firestore-এর মতো API
  collection(p) { return new CollectionReference(this, p); }
  doc(p) { return new DocumentReference(this, p); }
  collectionGroup(id) {
    if (typeof id !== 'string' || !id || id.includes('/')) throw new Error('Invalid collection group id');
    return new Query(this, { group: id });
  }
  batch() { return new WriteBatch(this); }
  async getAll(...args) { return this._getDocs(args.filter((r) => isRefLike(r)), null); }
  async listCollections() { return this._listCollections(null); }

  async recursiveDelete(ref) {
    const p = ref.path;
    // p নিজে, আর p/ দিয়ে শুরু সব ঠিকানা (বাইট ধরে: '/' এর পরের অক্ষর '0')
    if (ref instanceof DocumentReference || isRefLike(ref)) {
      await this._query('DELETE FROM docs WHERE path = $1 OR (path >= $2 AND path < $3)', [p, `${p}/`, `${p}0`]);
    } else {
      await this._query('DELETE FROM docs WHERE path >= $1 AND path < $2', [`${p}/`, `${p}0`]);
    }
  }

  async runTransaction(fn, opts = {}) {
    const max = opts.maxAttempts || 5;
    for (let attempt = 1; ; attempt++) {
      await this._ready();
      const client = await this.pool.connect();
      let broken = false;
      try {
        await client.query('BEGIN');
        const t = new Transaction(this, client);
        const result = await fn(t);
        if (t._ops.length) await this._applyOps(client, t._ops);
        await client.query('COMMIT');
        return result;
      } catch (e) {
        await client.query('ROLLBACK').catch(() => { broken = true; });
        if (RETRYABLE.has(e && e.code) && attempt < max) {
          await new Promise((r) => setTimeout(r, Math.min(1000, 20 * 2 ** attempt) * (0.5 + Math.random())));
          continue;
        }
        throw e;
      } finally {
        client.release(broken);
      }
    }
  }

  async close() { if (this._pool) await this._pool.end(); this._pool = null; this._schemaReady = null; }

  // -------- ভেতরের কাজ
  async _listCollections(docPath) {
    const ids = new Set();
    if (docPath === null) {
      const r = await this._query("SELECT DISTINCT split_part(coll, '/', 1) AS c FROM docs", []);
      r.rows.forEach((x) => ids.add(x.c));
    } else {
      const r = await this._query('SELECT DISTINCT coll FROM docs WHERE path >= $1 AND path < $2', [`${docPath}/`, `${docPath}0`]);
      r.rows.forEach((x) => ids.add(x.coll.slice(docPath.length + 1).split('/')[0]));
    }
    return [...ids].sort().map((id) => new CollectionReference(this, docPath ? `${docPath}/${id}` : id));
  }

  /** transaction-এ: পড়ার আগে লক (না থাকা ডকেও — advisory lock), তারপর নতুন snapshot-এ SELECT ... FOR UPDATE */
  async _lockPaths(client, paths) {
    const list = [...new Set(paths)].sort();
    if (!list.length) return;
    await client.query('SELECT pg_advisory_xact_lock($1, hashtext(p)) FROM unnest($2::text[]) WITH ORDINALITY AS u(p, i) ORDER BY i', [LOCK_CLASS, list]);
  }

  async _getDocs(refs, client) {
    if (!refs.length) return [];
    const paths = refs.map((r) => r.path);
    paths.forEach((p) => docParts(p));
    let rows;
    if (client) {
      await this._lockPaths(client, paths);
      rows = (await client.query('SELECT path, data, created_at, updated_at FROM docs WHERE path = ANY($1::text[]) ORDER BY path FOR UPDATE', [[...new Set(paths)]])).rows;
    } else {
      rows = (await this._query('SELECT path, data, created_at, updated_at FROM docs WHERE path = ANY($1::text[])', [[...new Set(paths)]])).rows;
    }
    const byPath = new Map(rows.map((r) => [r.path, r]));
    return refs.map((r) => {
      const row = byPath.get(r.path);
      return new DocumentSnapshot(this, r instanceof DocumentReference ? r : this.doc(r.path), row ? row.data : undefined, row);
    });
  }

  async _runQuery(q, client) {
    if (client) {
      // transaction-এ কোয়েরি: পুরো কালেকশনে exclusive লক — লেখার সময় সবাই shared লক নেয়, তাই পড়া থেকে commit
      // পর্যন্ত এই কালেকশনে নতুন ডক ঢোকা/বদলানো আটকে থাকে (phantom নেই, Firestore-এর serializable-এর মতো)
      const key = q._o.group ? `g:${q._o.group}` : `c:${q._o.coll}`;
      await client.query('SELECT pg_advisory_xact_lock($1, hashtext($2))', [LOCK_CLASS + 1, key]);
    }
    const { text, values } = q._sql('rows', !!client);
    const r = client ? await client.query(text, values) : await this._query(text, values);
    let rows = r.rows;
    if (q._o.limitToLast) rows = rows.slice().reverse();
    const sel = q._o.select;
    const docs = rows.map((row) => {
      let data = row.data === undefined ? {} : row.data;
      if (sel && sel.length) {
        const out = {};
        for (const segs of sel) {
          const v = getAt(data, segs);
          if (v !== undefined) setAt(out, segs, v);
        }
        data = out;
      }
      return new QueryDocumentSnapshot(this, this.doc(row.path), data, row);
    });
    return new QuerySnapshot(q, docs);
  }

  async _count(q) {
    const { text, values } = q._sql('count', false);
    const r = await this._query(text, values);
    return Number(r.rows[0].n);
  }

  /** batch বা একক লেখা: নিজের ছোট transaction-এ (লেখার সময় সব ডকে লক) */
  async _commit(ops) {
    ops.forEach((op) => docParts(op.path));
    await this._ready();
    for (let attempt = 1; ; attempt++) {
      const client = await this.pool.connect();
      let broken = false;
      try {
        await client.query('BEGIN');
        const res = await this._applyOps(client, ops);
        await client.query('COMMIT');
        return res;
      } catch (e) {
        await client.query('ROLLBACK').catch(() => { broken = true; });
        if (RETRYABLE.has(e && e.code) && attempt < 5) {
          await new Promise((r) => setTimeout(r, 10 * attempt * (0.5 + Math.random())));
          continue;
        }
        throw e;
      } finally {
        client.release(broken);
      }
    }
  }

  /** client-এর চলমান transaction-এ ops প্রয়োগ: লক, এখনকার তথ্য পড়া, হিসাব, একবারে লেখা */
  async _applyOps(client, ops) {
    const paths = [...new Set(ops.map((o) => o.path))];
    // কালেকশনে shared লক (transaction-এর কোয়েরির exclusive লকের সাথে মেলে — _runQuery দেখুন)
    const groups = [...new Set(paths.flatMap((p) => { const d = docParts(p); return [`c:${d.coll}`, `g:${d.grp}`]; }))].sort();
    await client.query('SELECT pg_advisory_xact_lock_shared($1, hashtext(k)) FROM unnest($2::text[]) WITH ORDINALITY AS u(k, i) ORDER BY i', [LOCK_CLASS + 1, groups]);
    await this._lockPaths(client, paths);
    const needRead = new Set(ops.filter((o) => !(o.type === 'set' && !o.merge && !o.mergeFields) && !(o.type === 'delete' && !o.mustExist)).map((o) => o.path));
    const state = new Map();
    if (needRead.size) {
      const r = await client.query('SELECT path, data FROM docs WHERE path = ANY($1::text[]) ORDER BY path FOR UPDATE', [[...needRead]]);
      r.rows.forEach((row) => state.set(row.path, row.data));
    }
    const nowTs = Timestamp.now();
    for (const op of ops) {
      const cur = state.has(op.path) ? state.get(op.path) : undefined;
      state.set(op.path, applyOp(cur, op, nowTs));
    }
    const up = { path: [], coll: [], grp: [], id: [], data: [] };
    const del = [];
    for (const p of paths) {
      const v = state.get(p);
      if (v === undefined) del.push(p);
      else {
        const d = docParts(p);
        up.path.push(p); up.coll.push(d.coll); up.grp.push(d.grp); up.id.push(d.id); up.data.push(JSON.stringify(v));
      }
    }
    if (del.length) await client.query('DELETE FROM docs WHERE path = ANY($1::text[])', [del]);
    if (up.path.length) {
      await client.query(
        `INSERT INTO docs (path, coll, grp, id, data)
         SELECT * FROM unnest($1::text[], $2::text[], $3::text[], $4::text[], $5::text[]::jsonb[])
         ON CONFLICT (path) DO UPDATE SET data = EXCLUDED.data, updated_at = clock_timestamp()`,
        [up.path, up.coll, up.grp, up.id, up.data],
      );
    }
    return ops.map(() => ({ writeTime: nowTs }));
  }
}

// ---------------------------------------------------------------- module

let defaultStore = null;
function getDefault() {
  if (!defaultStore) defaultStore = new PgFirestore({});
  return defaultStore;
}

module.exports = {
  PgFirestore,
  FieldValue,
  FieldPath,
  Timestamp,
  GeoPoint,
  DocumentReference,
  CollectionReference,
  Query,
  DocumentSnapshot,
  // migration আর টেস্টের জন্য
  _internal: { encode, decode, docParts, encodeTs, applyOp },
  /** DATABASE_URL থেকে বানানো ডাটাবেস (প্রথম ব্যবহারে pool খোলে) */
  get db() { return getDefault(); },
};
