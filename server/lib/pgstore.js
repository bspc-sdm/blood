// Firestore-এর মতো ডাটাবেস, PostgreSQL-এ (ফেজ ১: Firestore → Contabo VPS-এর PostgreSQL)।
// সার্ভারের কোড আগের মতোই db.collection(...).doc(...).get()/set()/where()... লেখে — এই ফাইল সেগুলো SQL-এ বদলায়।
// সার্ভার যতটুকু ব্যবহার করে ঠিক ততটুকু (test/support/fakefb.js-এর তালিকা), আর Firestore-এর নিয়মে:
//  - এক টেবিল docs(path, coll, grp, id, data jsonb) — scripts/pg-schema.sql
//  - where: ধরন মিলিয়ে তুলনা (সংখ্যা সংখ্যার সাথে, লেখা লেখার সাথে, বাইট ধরে); ঘর না থাকলে ডক বাদ
//  - orderBy না দিলে id-র ক্রম; orderBy-র ঘর না থাকলে ডক বাদ; শেষে id দিয়ে টাই ভাঙা
//  - transaction: আসল PostgreSQL transaction, পড়া ডকে লক (advisory + SELECT ... FOR UPDATE), deadlock হলে আবার চেষ্টা;
//    কোয়েরিতে শুধু "নোঙর" ঘর-মানে লক (পুরো কালেকশনে নয় — _runQuery দেখুন); লেখার পর পড়া চলে না
//  - লেখা: U+0000 ফেরতযোগ্য escape, অর্ধেক ইমোজি U+FFFD (encStr) — jsonb-র কারণে লেখা কখনো ভুল দেয় না
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

// লেখা (মান আর চাবি) jsonb-তে রাখার আগে:
//  - U+0000: jsonb রাখতে পারে না (22P05)। ফেরতযোগ্য escape: U+0000 → U+0001 U+0001, U+0001 → U+0001 U+0002।
//    বাইটের ক্রম একই থাকে (U+0000 < U+0001 < বাকি সব), তাই ==, <, orderBy, cursor আগের মতোই মেলে; পড়লে আসলটা ফেরে।
//  - একা surrogate (অর্ধেক ইমোজি): UTF-8-এ হয় না, PostgreSQL ফেরত দেয় (22P02) — U+FFFD বসে (Firestore-ও তাই করে)।
// কতবার হলো — migration লগে দেখায়
const textStats = { nul: 0, surrogate: 0 };
const NEEDS_ESC = /[\u0000\u0001]/;
function encStr(s) {
  if (!s.isWellFormed()) { s = s.toWellFormed(); textStats.surrogate++; }
  if (NEEDS_ESC.test(s)) {
    if (s.includes('\u0000')) textStats.nul++;
    s = s.replace(/[\u0000\u0001]/g, (c) => (c === '\u0000' ? '\u0001\u0001' : '\u0001\u0002'));
  }
  return s;
}
function decStr(s) {
  return s.includes('\u0001') ? s.replace(/\u0001([\u0001\u0002])/g, (_, c) => (c === '\u0001' ? '\u0000' : '\u0001')) : s;
}
const escKey = (k) => { const s = encStr(k); return s.startsWith('$') ? `$${s}` : s; };
const unescKey = (k) => decStr(k.startsWith('$$') ? k.slice(1) : k);

/** লেখার মান → jsonb-তে রাখার রূপ (FieldValue এখানে আসার আগেই আলাদা করা হয়) */
function encode(v, where = 'value') {
  if (v === null) return null;
  if (typeof v === 'string') return encStr(v);
  if (typeof v === 'boolean') return v;
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
  if (typeof v === 'string') return decStr(v);
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
    // ±Infinity: PostgreSQL-এর numeric-এও আছে (fs_num একই মান দেয়); NaN কোনো range-এ পড়ে না
    if (e && (e.$num === 'Infinity' || e.$num === '-Infinity')) return e.$num;
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
  const segs = rawSegsOf(field);
  return segs && segs.map(escKey);
}

/** segsOf-এর মতো, কিন্তু কোডের নিজের নাম (escape ছাড়া) — লেখার data-র ভেতরে খোঁজার জন্য */
function rawSegsOf(field) {
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
  return segs;
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

/**
 * Firestore-এর precondition: { exists } বা { lastUpdateTime } (update()/delete()-এ)।
 * ফেরত { exists?, updateUs? (BigInt, মাইক্রোসেকেন্ড — PostgreSQL-এর updated_at-এর মাপে) }। অন্য কিছু দিলে ভুল।
 */
function preconditionOf(pre, what) {
  if (pre === undefined || pre === null) return {};
  if (!isPlainObject(pre)) throw new Error(`Invalid precondition for ${what}()`);
  const out = {};
  for (const [k, v] of Object.entries(pre)) {
    if (v === undefined) continue;
    if (k === 'exists') {
      if (typeof v !== 'boolean') throw new Error('Precondition "exists" must be a boolean');
      out.exists = v;
    } else if (k === 'lastUpdateTime') {
      if (!isTimestampLike(v)) throw new Error('Precondition "lastUpdateTime" must be a Timestamp');
      out.updateUs = BigInt(v.seconds) * 1000000n + BigInt(Math.floor(v.nanoseconds / 1000));
    } else {
      throw new Error(`Precondition "${k}" is not supported by pgstore (only exists, lastUpdateTime)`);
    }
  }
  if (out.exists !== undefined && out.updateUs !== undefined) throw new Error('A precondition can have only one of exists / lastUpdateTime');
  if (what === 'update' && out.exists === false) throw new Error('update() cannot use the precondition exists: false');
  return out;
}

/** precondition যাচাই: cur = এখনকার data (না থাকলে undefined), curUs = এখনকার updated_at (মাইক্রোসেকেন্ড) */
function checkPrecondition(cur, curUs, op) {
  const pre = op.pre;
  if (!pre) return;
  if (pre.exists === true && cur === undefined) throw fsError(5, 'NOT_FOUND', `No document to ${op.type}: ${op.path}`);
  if (pre.exists === false && cur !== undefined) throw fsError(6, 'ALREADY_EXISTS', `Document already exists: ${op.path}`);
  if (pre.updateUs !== undefined && (cur === undefined || curUs !== pre.updateUs)) {
    throw fsError(9, 'FAILED_PRECONDITION', `The document's update time does not match the precondition: ${op.path}`);
  }
}

/** একটা লেখা প্রয়োগ: cur = এখনকার encoded data (না থাকলে undefined) → নতুন data (মুছলে undefined) */
function applyOp(cur, op, nowTs, curUs = null) {
  if (op.type === 'delete') {
    checkPrecondition(cur, curUs, op);
    return undefined;
  }
  if (op.type === 'create' && cur !== undefined) throw fsError(6, 'ALREADY_EXISTS', `Document already exists: ${op.path}`);
  if (op.type === 'update' && cur === undefined) throw fsError(5, 'NOT_FOUND', `No document to update: ${op.path}`);
  checkPrecondition(cur, curUs, op);

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
        const raw = getAt(op.data, rawSegsOf(f));
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

/** update()-এর ops: update({ 'a.b': 1 }, pre?) বা update('a.b', 1, FieldPath, 2, ..., pre?) */
function updateOp(ref, args) {
  let fields;
  let pre;
  if (args.length === 1 || (args.length === 2 && isPlainObject(args[0]))) {
    const obj = args[0];
    if (!isPlainObject(obj)) throw new Error('update() needs an object');
    pre = args[1];
    fields = Object.entries(obj).filter(([, v]) => v !== undefined).map(([k, v]) => [segsOf(k), v]);
    if (fields.some(([s]) => s === null)) throw new Error('Cannot update document id');
  } else {
    const list = args.slice();
    if (list.length % 2) pre = list.pop();
    fields = [];
    for (let i = 0; i + 1 < list.length; i += 2) fields.push([segsOf(list[i]), list[i + 1]]);
  }
  if (!fields.length) throw new Error('update() needs at least one field');
  return { type: 'update', path: ref.path, fields, pre: preconditionOf(pre, 'update') };
}

function deleteOp(ref, pre) {
  return { type: 'delete', path: ref.path, pre: preconditionOf(pre, 'delete') };
}

// ---------------------------------------------------------------- Snapshot

/** created_at / updated_at → মাইক্রোসেকেন্ড (bigint, লেখা হিসেবে আসে) */
const TIME_COLS = '(extract(epoch FROM created_at) * 1000000)::bigint AS c_us, (extract(epoch FROM updated_at) * 1000000)::bigint AS u_us';

function tsFromUs(us) {
  const n = BigInt(us);
  let s = n / 1000000n;
  let r = n % 1000000n;
  if (r < 0n) { r += 1000000n; s -= 1n; }
  return new Timestamp(Number(s), Number(r) * 1000);
}

class DocumentSnapshot {
  constructor(store, ref, raw, row) {
    this._store = store;
    this.ref = ref;
    this._raw = raw; // encoded data বা undefined
    this.id = ref.id;
    this.exists = raw !== undefined;
    this.readTime = Timestamp.now();
    // মাইক্রোসেকেন্ড পর্যন্ত (PostgreSQL-এর মাপ) — তাই updateTime হুবহু lastUpdateTime precondition-এ দেওয়া যায়
    this.createTime = row && row.c_us != null ? tsFromUs(row.c_us) : undefined;
    this.updateTime = row && row.u_us != null ? tsFromUs(row.u_us) : undefined;
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
  /**
   * সব সাজানোর ঘর: নিজের orderBy, তারপর বাকি অসমতা-ঘর (ঘরের নামের ক্রমে), শেষে id।
   * বাড়তি ঘরগুলো আর id শেষ orderBy-র দিকে (না থাকলে asc) — firebase-admin-এর createImplicitOrderBy-র মতো।
   */
  _orderKeys() {
    const o = this._o;
    const keys = o.orders.map((x) => ({ segs: x.segs, dir: x.dir }));
    const same = (a, b) => (a === null || b === null ? a === b : a.length === b.length && a.every((s, i) => s === b[i]));
    const has = (segs) => keys.some((k) => same(k.segs, segs));
    const dir = keys.length ? keys[keys.length - 1].dir : 'asc';
    const extra = [];
    for (const f of o.filters) {
      if (!(RANGE_OPS.has(f.op) || f.op === '!=' || f.op === 'not-in') || f.segs === null) continue;
      if (!has(f.segs) && !extra.some((e) => same(e, f.segs))) extra.push(f.segs);
    }
    // ঘরের নামের ক্রম: অংশ ধরে, প্রতিটা অংশ বাইট ধরে (Firestore-এর FieldPath তুলনা)
    const cmpSeg = (a, b) => (a === b ? 0 : Buffer.compare(Buffer.from(a), Buffer.from(b)));
    extra.sort((a, b) => {
      for (let i = 0; i < Math.min(a.length, b.length); i++) { const c = cmpSeg(unescKey(a[i]), unescKey(b[i])); if (c) return c; }
      return a.length - b.length;
    }).forEach((segs) => keys.push({ segs, dir }));
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

    const cols = mode === 'count' ? '1' : o.select && !o.select.length ? `path, ${TIME_COLS}` : `path, data, ${TIME_COLS}`;
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
        // তালিকায় null থাকলে Firestore কিছুই ফেরত দেয় না
        if (f.value.some((v) => v === null)) return 'FALSE';
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
  async update(...args) { return (await this._store._commit([updateOp(this, args)]))[0]; }
  async create(data) { return (await this._store._commit([{ type: 'create', path: this.path, data: checkData(data) }]))[0]; }
  async delete(pre) { return (await this._store._commit([deleteOp(this, pre)]))[0]; }
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
  update(ref, ...args) { return this._add(updateOp(ref, args)); }
  create(ref, data) { return this._add({ type: 'create', path: ref.path, data: checkData(data) }); }
  delete(ref, pre) { return this._add(deleteOp(ref, pre)); }
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
  // firebase-admin-এর মতো: লেখা জমা হওয়ার পর পড়া চলে না (নইলে নিজের লেখা না দেখা পুরোনো তথ্য ফেরত আসত)
  _checkRead() {
    if (this._ops.length) throw new Error('Firestore transactions require all reads to be executed before all writes.');
  }
  async get(refOrQuery) {
    this._checkRead();
    if (refOrQuery instanceof Query) return this._store._runQuery(refOrQuery, this._client);
    if (!isRefLike(refOrQuery)) throw new Error('Transaction.get() needs a DocumentReference or Query');
    return (await this._store._getDocs([refOrQuery], this._client))[0];
  }
  async getAll(...refs) {
    this._checkRead();
    const list = refs.filter((r) => isRefLike(r));
    return this._store._getDocs(list, this._client);
  }
  set(ref, data, opts) { this._ops.push(setOp(ref, data, opts)); return this; }
  update(ref, ...args) { this._ops.push(updateOp(ref, args)); return this; }
  create(ref, data) { this._ops.push({ type: 'create', path: ref.path, data: checkData(data) }); return this; }
  delete(ref, pre) { this._ops.push(deleteOp(ref, pre)); return this; }
}

// ---------------------------------------------------------------- ডাটাবেস

const SCHEMA_FILE = path.join(__dirname, '..', 'scripts', 'pg-schema.sql');
// advisory lock-এর শ্রেণি ('LF'):
//  LOCK_CLASS     ডকের ঠিকানা (hashtext(path)) — transaction-এ পড়া আর সব লেখায় exclusive
//  LOCK_CLASS + 1 কালেকশন 'c:<coll>' / 'g:<grp>' — পড়া/লেখায় shared, ছাঁকনিহীন কোয়েরিতে exclusive;
//                 'b:<coll>' — অনেক ঘরের বড় লেখায় exclusive, ছাঁকনিওয়ালা কোয়েরিতে shared
//  LOCK_CLASS + 2 শর্ত (কালেকশন, উপরের-স্তরের ঘর, মান) — == / in কোয়েরিতে exclusive (একটা ঘর), আর যে ডক লেখার পর
//                 ওই ঘরে ওই মান রাখে তার লেখায় shared
const LOCK_CLASS = 0x4c46;
// এক লেখায় এক কালেকশনে এর বেশি শর্ত-লক লাগলে (বড় batch) তার বদলে একটা 'b:<coll>' লক (lock table ভরে না যায়)
const PRED_PER_COLL = 64;
const PRED_TOTAL = 256;

const CONNECT_TIMEOUT = /timeout exceeded when trying to connect|Connection terminated due to connection timeout/i;
const CONNECT_TRIES = 4;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** স্থির JSON (map-এর চাবি সাজিয়ে) — শর্ত-লকের চাবিতে একই মান সবসময় একই লেখা */
function canon(e) {
  if (e === null || typeof e !== 'object') return JSON.stringify(e);
  if (Array.isArray(e)) return `[${e.map(canon).join(',')}]`;
  return `{${Object.keys(e).sort().map((k) => `${JSON.stringify(k)}:${canon(e[k])}`).join(',')}}`;
}

/** শর্ত-লকের চাবি → advisory lock-এর int4 (sha1-এর প্রথম ৪ বাইট; লম্বা মানও ছোট সংখ্যা হয়ে যায়) */
const predKey = (coll, field, valCanon) => crypto.createHash('sha1').update(JSON.stringify([coll, field, valCanon])).digest().readInt32BE(0);

/** লেখার শর্ত-লক: ডকের উপরের-স্তরের প্রতিটা ঘর (map ছাড়া — map-এ == কোয়েরি শর্ত-লক পায় না) */
function docPredKeys(coll, data) {
  const out = [];
  for (const [k, v] of Object.entries(data)) if (!isMap(v)) out.push(predKey(coll, k, canon(v)));
  return out;
}

class PgFirestore {
  /**
   * opts: { connectionString, schema, poolMax, autoSchema, pool, connectionTimeoutMillis }
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
      // খোলা transaction-এ কিছু না করে বসে থাকলে (কোডের ভুল, আটকে যাওয়া await) PostgreSQL সংযোগ কেটে লক ছেড়ে দেয়
      options.push(`-c idle_in_transaction_session_timeout=${Number(process.env.PG_IDLE_TX_TIMEOUT_MS) || 30000}`);
      // একটা কোয়েরি এর বেশি চললে বাতিল (schema বানানোয় নিজে বন্ধ করা হয়)
      options.push(`-c statement_timeout=${Number(process.env.PG_STATEMENT_TIMEOUT_MS) || 60000}`);
      this._pool = new Pool({
        connectionString: cs,
        max: Number(this._opts.poolMax || process.env.PG_POOL_MAX) || 10,
        idleTimeoutMillis: 30000,
        connectionTimeoutMillis: this._opts.connectionTimeoutMillis || 15000,
        allowExitOnIdle: true,
        options: options.join(' '),
      });
      this._pool.on('error', (e) => console.error('pg pool error', e.message));
    }
    return this._pool;
  }

  /** pool থেকে সংযোগ; সংযোগের সময় পেরোলে (সব সংযোগ ব্যস্ত বা সার্ভার ধীর) কয়েকবার আবার চেষ্টা, ক্রমে বেশি অপেক্ষা করে */
  async _connect() {
    for (let attempt = 1; ; attempt++) {
      try {
        const client = await this.pool.connect();
        // হাতে থাকা (pool-এ নয়) সংযোগ সার্ভার কেটে দিলে (idle_in_transaction_session_timeout, রিস্টার্ট) client 'error'
        // ছোড়ে — শোনার কেউ না থাকলে পুরো প্রসেস পড়ে যেত। মনে রাখা হয়; ফেরত দেওয়ার সময় সংযোগটা ফেলে দেওয়া হয়।
        if (!client._fsErrorHook) {
          client._fsErrorHook = true;
          client.on('error', (e) => { client._fsBroken = e; });
        }
        return client;
      } catch (e) {
        if (!CONNECT_TIMEOUT.test(e && e.message) || attempt >= CONNECT_TRIES) throw e;
        await sleep(100 * 2 ** attempt * (0.5 + Math.random()));
      }
    }
  }

  async _schemaVersion(c) {
    const reg = await c.query("SELECT to_regclass('fs_meta') IS NOT NULL AS ok");
    if (!reg.rows[0].ok) return null;
    const r = await c.query("SELECT value FROM fs_meta WHERE key = 'schema_version'");
    return r.rows.length ? r.rows[0].value : null;
  }

  /**
   * scripts/pg-schema.sql চালানো — শুধু যখন ডাটাবেসের schema_version ফাইলের সংস্করণের সাথে মেলে না।
   * মিললে কোনো DDL নয় (CREATE INDEX IF NOT EXISTS-ও টেবিলে লক নেয় — প্রতি চালুতে লেখা আটকাত)।
   * কয়েকটা প্রসেস একসাথে চালু হলেও advisory lock দিয়ে একটাই চালায়।
   */
  ensureSchema() {
    if (!this._schemaReady) {
      this._schemaReady = (async () => {
        const sql = fs.readFileSync(SCHEMA_FILE, 'utf8');
        const m = /'schema_version',\s*'([^']+)'/.exec(sql);
        if (!m) throw new Error('pg-schema.sql has no schema_version');
        const want = m[1];
        const client = await this._connect();
        try {
          if ((await this._schemaVersion(client)) === want) return;
          await client.query('BEGIN');
          await client.query('SET LOCAL statement_timeout = 0');
          await client.query('SET LOCAL lock_timeout = 0');
          await client.query('SELECT pg_advisory_xact_lock($1, 0)', [LOCK_CLASS]);
          if ((await this._schemaVersion(client)) !== want) await client.query(sql);
          await client.query('COMMIT');
        } catch (e) {
          await client.query('ROLLBACK').catch(() => {});
          throw e;
        } finally {
          client.release(!!client._fsBroken);
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
    if (client) return client.query(text, values);
    for (let attempt = 1; ; attempt++) {
      try {
        return await this.pool.query(text, values);
      } catch (e) {
        // সংযোগই পাওয়া যায়নি — কোয়েরি যায়নি, তাই আবার পাঠানো নিরাপদ
        if (!CONNECT_TIMEOUT.test(e && e.message) || attempt >= CONNECT_TRIES) throw e;
        await sleep(100 * 2 ** attempt * (0.5 + Math.random()));
      }
    }
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
      const client = await this._connect();
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
          await sleep(Math.min(1000, 20 * 2 ** attempt) * (0.5 + Math.random()));
          continue;
        }
        throw e;
      } finally {
        client.release(broken || !!client._fsBroken);
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

  /** ডকগুলোর কালেকশনে shared লক (ছাঁকনিহীন কোয়েরির exclusive লকের সাথে মেলে — _runQuery দেখুন) */
  async _lockColls(client, paths) {
    const keys = [...new Set(paths.flatMap((p) => { const d = docParts(p); return [`c:${d.coll}`, `g:${d.grp}`]; }))].sort();
    if (!keys.length) return;
    await client.query('SELECT pg_advisory_xact_lock_shared($1, hashtext(k)) FROM unnest($2::text[]) WITH ORDINALITY AS u(k, i) ORDER BY i', [LOCK_CLASS + 1, keys]);
  }

  /** transaction-এ: পড়ার আগে লক (না থাকা ডকেও — advisory lock), তারপর নতুন snapshot-এ SELECT ... FOR UPDATE */
  async _lockPaths(client, paths) {
    const list = [...new Set(paths)].sort();
    if (!list.length) return;
    await client.query('SELECT pg_advisory_xact_lock($1, hashtext(p)) FROM unnest($2::text[]) WITH ORDINALITY AS u(p, i) ORDER BY i', [LOCK_CLASS, list]);
  }

  /** শর্ত-লক (সংখ্যার ক্রমে, একই চাবি একবার) */
  async _lockPreds(client, keys, shared) {
    if (!keys.length) return;
    await client.query(
      `SELECT ${shared ? 'pg_advisory_xact_lock_shared' : 'pg_advisory_xact_lock'}($1, h)
       FROM (SELECT DISTINCT h FROM unnest($2::int4[]) AS u(h) ORDER BY 1) s`,
      [LOCK_CLASS + 2, [...new Set(keys)]],
    );
  }

  async _getDocs(refs, client) {
    if (!refs.length) return [];
    const paths = refs.map((r) => r.path);
    paths.forEach((p) => docParts(p));
    let rows;
    if (client) {
      // কালেকশনে shared লক পড়ার সময়েই (লেখার সময় নয়) — তাই ছাঁকনিহীন কোয়েরির লক আর এই ডকের লক সবসময় একই ক্রমে
      await this._lockColls(client, paths);
      await this._lockPaths(client, paths);
      rows = (await client.query(`SELECT path, data, ${TIME_COLS} FROM docs WHERE path = ANY($1::text[]) ORDER BY path FOR UPDATE`, [[...new Set(paths)]])).rows;
    } else {
      rows = (await this._query(`SELECT path, data, ${TIME_COLS} FROM docs WHERE path = ANY($1::text[])`, [[...new Set(paths)]])).rows;
    }
    const byPath = new Map(rows.map((r) => [r.path, r]));
    return refs.map((r) => {
      const row = byPath.get(r.path);
      return new DocumentSnapshot(this, r instanceof DocumentReference ? r : this.doc(r.path), row ? row.data : undefined, row);
    });
  }

  /**
   * transaction-এর কোয়েরির শর্ত-লক — "নোঙর": প্রথম উপরের-স্তরের ঘরের == ছাঁকনি (না থাকলে প্রথম in — তার সব মান)।
   * কোডে সবচেয়ে বাছাইকারী ছাঁকনিটা আগে লিখুন (requesterUid == uid, তারপর status == 'open') — নোঙর যত কম ডকে মেলে,
   * তত কম লেখা অপেক্ষা করে। নোঙর না পেলে null।
   * নোঙর একটাই যথেষ্ট: কোয়েরিতে নতুন ঢোকা যেকোনো ডকের লেখার পরের রূপে নোঙর-ঘরে নোঙর-মান থাকেই, আর সেই লেখা
   * ওই চাবিতে shared লক নেয়।
   */
  _predKeys(q) {
    const o = q._o;
    if (o.group) return null;
    const usable = (f) => f.segs !== null && f.segs.length === 1;
    const f = o.filters.find((x) => usable(x) && x.op === '==') || o.filters.find((x) => usable(x) && x.op === 'in');
    if (!f) return null;
    const vals = (f.op === 'in' ? f.value : [f.value]).map((v) => encode(v, 'where()'));
    if (vals.some(isMap)) return null;
    return vals.map((e) => predKey(o.coll, f.segs[0], canon(e)));
  }

  async _runQuery(q, client) {
    if (client) {
      // transaction-এ কোয়েরি — phantom নেই (Firestore-এর serializable-এর মতো), কিন্তু পুরো কালেকশন আটকে নয়:
      //  - উপরের-স্তরের ঘরে == / in ছাঁকনি থাকলে (requestcreate: requesterUid == uid): সেই (কালেকশন, ঘর, মান)-এ
      //    exclusive শর্ত-লক। যে লেখার পর কোনো ডকের ওই ঘরে ওই মান থাকে (নতুন ডক, বা বদলে কোয়েরিতে "ঢোকা")
      //    সে ওই চাবিতে shared লক নেয় — তাই commit পর্যন্ত অপেক্ষা করে। আগে থেকেই মেলা ডকগুলো নিচের FOR UPDATE-এ
      //    লক (বদলানো/মোছা অপেক্ষা করে)। অন্য মানের লেখা (অন্য ব্যবহারকারীর রিকোয়েস্ট) আটকায় না।
      //  - নইলে (শুধু range/array-contains, ছাঁকনি নেই, collectionGroup): আগের মতো পুরো কালেকশনে exclusive লক।
      const keys = this._predKeys(q);
      if (keys) {
        await client.query('SELECT pg_advisory_xact_lock_shared($1, hashtext($2))', [LOCK_CLASS + 1, `b:${q._o.coll}`]);
        await this._lockPreds(client, keys, false);
      } else {
        const key = q._o.group ? `g:${q._o.group}` : `c:${q._o.coll}`;
        await client.query('SELECT pg_advisory_xact_lock($1, hashtext($2))', [LOCK_CLASS + 1, key]);
      }
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
      const client = await this._connect();
      let broken = false;
      try {
        await client.query('BEGIN');
        const res = await this._applyOps(client, ops);
        await client.query('COMMIT');
        return res;
      } catch (e) {
        await client.query('ROLLBACK').catch(() => { broken = true; });
        if (RETRYABLE.has(e && e.code) && attempt < 5) {
          await sleep(10 * attempt * (0.5 + Math.random()));
          continue;
        }
        throw e;
      } finally {
        client.release(broken || !!client._fsBroken);
      }
    }
  }

  /** লেখার শর্ত-লক: লেখার পর থাকা প্রতিটা ডকের উপরের-স্তরের সব ঘর-মান (মোছায় নয় — আগে মেলা ডক FOR UPDATE-এ লক থাকে) */
  _writePreds(paths, after) {
    const byColl = new Map();
    for (const p of paths) {
      const next = after.get(p);
      if (next === undefined) continue;
      const coll = docParts(p).coll;
      if (!byColl.has(coll)) byColl.set(coll, new Set());
      const set = byColl.get(coll);
      for (const k of docPredKeys(coll, next)) set.add(k);
    }
    const shared = [];
    const bulk = [];
    for (const [coll, set] of [...byColl].sort((a, b) => a[1].size - b[1].size)) {
      if (set.size > PRED_PER_COLL || shared.length + set.size > PRED_TOTAL) bulk.push(`b:${coll}`);
      else shared.push(...set);
    }
    return { shared, bulk: bulk.sort() };
  }

  /** client-এর চলমান transaction-এ ops প্রয়োগ: লক, এখনকার তথ্য পড়া, হিসাব, একবারে লেখা */
  async _applyOps(client, ops) {
    const paths = [...new Set(ops.map((o) => o.path))];
    await this._lockColls(client, paths);
    await this._lockPaths(client, paths);
    // এখনকার তথ্য পড়া শুধু যেখানে লাগে (পুরো set বা precondition-ছাড়া delete-এ নয়)
    const needRead = [...new Set(ops.filter((o) => !(o.type === 'set' && !o.merge && !o.mergeFields)
      && !(o.type === 'delete' && o.pre.exists === undefined && o.pre.updateUs === undefined)).map((o) => o.path))];
    const state = new Map();
    const beforeUs = new Map();
    if (needRead.length) {
      const cur = await client.query(
        'SELECT path, data, (extract(epoch FROM updated_at) * 1000000)::bigint AS u_us FROM docs WHERE path = ANY($1::text[]) ORDER BY path FOR UPDATE',
        [needRead],
      );
      cur.rows.forEach((row) => { state.set(row.path, row.data); beforeUs.set(row.path, BigInt(row.u_us)); });
    }
    const touched = new Set();
    const nowTs = Timestamp.now();
    for (const op of ops) {
      const c = state.has(op.path) ? state.get(op.path) : undefined;
      // একই batch-এ একই ডকে দ্বিতীয় লেখার precondition: আগের লেখার পর সময় আর মেলে না
      const us = touched.has(op.path) ? null : beforeUs.get(op.path) ?? null;
      state.set(op.path, applyOp(c, op, nowTs, us));
      touched.add(op.path);
    }
    const preds = this._writePreds(paths, state);
    if (preds.bulk.length) {
      await client.query('SELECT pg_advisory_xact_lock($1, hashtext(k)) FROM unnest($2::text[]) WITH ORDINALITY AS u(k, i) ORDER BY i', [LOCK_CLASS + 1, preds.bulk]);
    }
    await this._lockPreds(client, preds.shared, true);
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
    const written = new Map();
    if (up.path.length) {
      const r = await client.query(
        `INSERT INTO docs (path, coll, grp, id, data)
         SELECT * FROM unnest($1::text[], $2::text[], $3::text[], $4::text[], $5::text[]::jsonb[])
         ON CONFLICT (path) DO UPDATE SET data = EXCLUDED.data, updated_at = clock_timestamp()
         RETURNING path, (extract(epoch FROM updated_at) * 1000000)::bigint AS u_us`,
        [up.path, up.coll, up.grp, up.id, up.data],
      );
      r.rows.forEach((row) => written.set(row.path, tsFromUs(row.u_us)));
    }
    // writeTime = ডকের নতুন updateTime (lastUpdateTime precondition-এ দেওয়া যায়); মোছায় এখনকার সময়
    return ops.map((op) => ({ writeTime: written.get(op.path) || nowTs }));
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
  _internal: { encode, decode, docParts, encodeTs, applyOp, textStats },
  /** DATABASE_URL থেকে বানানো ডাটাবেস (প্রথম ব্যবহারে pool খোলে) */
  get db() { return getDefault(); },
};
