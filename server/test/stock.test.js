// স্টক তালিকা আর জেলার সূচি — Firestore-এর ছোট নকল দিয়ে
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

// ---------- নকল Firestore (যতটুকু stock.js লাগে) ----------
const store = new Map();
class Sentinel { constructor(op, v) { this.op = op; this.v = v; } }
const FieldValue = {
  delete: () => new Sentinel('delete'),
  arrayUnion: (...v) => new Sentinel('union', v),
  arrayRemove: (...v) => new Sentinel('remove', v),
};
const clone = (x) => JSON.parse(JSON.stringify(x));
function applyValue(cur, v) {
  if (v instanceof Sentinel) {
    if (v.op === 'union') return [...new Set([...(Array.isArray(cur) ? cur : []), ...v.v])];
    if (v.op === 'remove') return (Array.isArray(cur) ? cur : []).filter((x) => !v.v.includes(x));
  }
  return v;
}
function mergeInto(target, src) {
  for (const [k, v] of Object.entries(src)) {
    if (v instanceof Sentinel && v.op === 'delete') delete target[k];
    else if (v && typeof v === 'object' && !Array.isArray(v) && !(v instanceof Sentinel)) {
      if (!target[k] || typeof target[k] !== 'object' || Array.isArray(target[k])) target[k] = {};
      mergeInto(target[k], v);
    } else target[k] = applyValue(target[k], v);
  }
}
function snap(p) {
  const d = store.get(p);
  return { id: p.split('/').pop(), exists: !!d, data: () => (d ? clone(d) : undefined), get: (f) => (d ? clone(d)[f] : undefined), ref: ref(p) };
}
function ref(p) {
  return {
    path: p,
    id: p.split('/').pop(),
    async get() { return snap(p); },
    async set(data, opt) {
      if (opt && opt.merge) { const cur = store.get(p) || {}; mergeInto(cur, data); store.set(p, cur); } else {
        const fresh = {}; mergeInto(fresh, data); store.set(p, fresh);
      }
    },
    async update(patch) {
      const cur = store.get(p);
      if (!cur) throw new Error('no doc ' + p);
      for (const [fp, v] of Object.entries(patch)) {
        const parts = fp.split('.');
        let o = cur;
        for (const part of parts.slice(0, -1)) { if (!o[part] || typeof o[part] !== 'object') o[part] = {}; o = o[part]; }
        const last = parts[parts.length - 1];
        if (v instanceof Sentinel && v.op === 'delete') delete o[last];
        else o[last] = applyValue(o[last], v);
      }
    },
    async delete() { store.delete(p); },
  };
}
const db = {
  doc: ref,
  async getAll(...refs) { return refs.map((r) => snap(r.path)); },
  async runTransaction(fn) {
    return fn({ get: (r) => r.get(), getAll: (...rs) => Promise.all(rs.map((r) => r.get())), set: (r, d, o) => r.set(d, o), update: (r, d) => r.update(d) });
  },
  collection: () => ({ limit: () => ({ get: async () => ({ docs: [] }) }), where: () => ({ limit: () => ({ get: async () => ({ docs: [] }) }) }) }),
};
class HttpError extends Error { constructor(s, m) { super(m); this.status = s; } }
require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { db, FieldValue, HttpError, admin: {}, sendPush: async () => 0, addInbox: async () => {}, handler: (f) => f, verifyUser: async () => 'x' },
};

const S = require('../lib/stock');
const D = 'ময়মনসিংহ';
const idx = (k, d = D) => JSON.parse((store.get(`stockIdx/${d}__${S.shardOf(k)}`) || { j: '{}' }).j)[k] || {};
const mine = (pid) => JSON.parse((store.get(`pharmacyStock/${pid}`) || { j: '{}' }).j);

test('যোগ করলে দোকানের তালিকা আর জেলার সূচিতে আসে, দাম সহ', async () => {
  store.clear();
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'in', p: 12, u: 'strip', d: 5 }, { key: 'sergel20cap', s: 'in' }], true);
  const it = mine('shopA').napa500tab;
  assert.deepStrictEqual({ s: it.s, p: it.p, u: it.u, d: it.d, m: it.m }, { s: 'in', p: 12, u: 'strip', d: 5, m: true });
  assert.deepStrictEqual(idx('napa500tab'), { shopA: [12, 1, 5] });
  assert.deepStrictEqual(idx('sergel20cap'), { shopA: [0, 0, 0] });
});

test('দুই দোকান একই ওষুধ; একটা "এখন নেই" করলে শুধু সেটা সূচি থেকে সরে', async () => {
  await S.applyChanges('shopB', D, [{ key: 'napa500tab', s: 'in' }], true);
  assert.deepStrictEqual(Object.keys(idx('napa500tab')).sort(), ['shopA', 'shopB']);
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'out' }], true);
  assert.deepStrictEqual(Object.keys(idx('napa500tab')), ['shopB']);
  assert.strictEqual(mine('shopA').napa500tab.p, 12, 'দোকানের নিজের তালিকায় দাম থেকে যায়');
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'in' }], true);
  assert.deepStrictEqual(idx('napa500tab').shopA, [12, 1, 5], 'আবার চালু করলে আগের দাম ফেরে');
});

test('দাম বদল আর মুছে দেওয়া', async () => {
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'in', p: 15 }], true);
  assert.deepStrictEqual(idx('napa500tab').shopA, [15, 1, 5]);
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'in', p: null, d: null }], true);
  assert.deepStrictEqual(idx('napa500tab').shopA, [0, 1, 0]);
  await S.applyChanges('shopA', D, [{ key: 'napa500tab', s: 'in', p: 100000 }], true);
  assert.deepStrictEqual(idx('napa500tab').shopA, [0, 1, 0], 'সীমার বেশি দাম নেওয়া হয় না');
});

test('সরালে তালিকা আর সূচি দুটো থেকেই যায়', async () => {
  await S.applyChanges('shopB', D, [{ key: 'napa500tab', s: 'remove' }], true);
  assert.ok(!mine('shopB').napa500tab);
  assert.deepStrictEqual(Object.keys(idx('napa500tab')), ['shopA']);
});

test('অর্ডারের উত্তর থেকে শেখা: হাতে দেওয়া "থাকে" একটা "নেই" উত্তরে বদলায় না', async () => {
  await S.learnFromReply('shopC', D, ['ace100tab'], 'all');
  assert.strictEqual(mine('shopC').ace100tab.m, false);
  assert.deepStrictEqual(Object.keys(idx('ace100tab')), ['shopC']);
  await S.learnFromReply('shopC', D, ['ace100tab'], 'none');
  assert.strictEqual(mine('shopC').ace100tab.s, 'out', 'নিজে শেখাটা বদলায়');
  await S.learnFromReply('shopA', D, ['napa500tab'], 'none');
  assert.strictEqual(mine('shopA').napa500tab.s, 'in', 'হাতে দেওয়াটা বদলায় না');
  await S.learnFromReply('shopA', D, ['bad key!'], 'all');
});

test('কাছের দোকানের ক্রমে স্টক থাকা দোকান আগে', async () => {
  await S.applyChanges('shopB', D, [{ key: 'xyz123abc', s: 'in' }, { key: 'abc987xyz', s: 'in' }], true);
  const c = [{ id: 'shopA' }, { id: 'shopC' }, { id: 'shopB' }];
  const out = await S.preferStocked(D, ['xyz123abc', 'abc987xyz'], c);
  assert.deepStrictEqual(out.map((x) => x.id), ['shopB', 'shopA', 'shopC']);
  const same = await S.preferStocked(D, [], c);
  assert.deepStrictEqual(same.map((x) => x.id), ['shopA', 'shopC', 'shopB']);
});

test('জেলা বদলালে সূচি সরে, দাম সহ', async () => {
  await S.applyChanges('shopM', D, [{ key: 'move111aaa', s: 'in', p: 40 }], true);
  await S.moveDistrict('shopM', D, 'ঢাকা');
  assert.ok(!idx('move111aaa').shopM);
  assert.deepStrictEqual(idx('move111aaa', 'ঢাকা').shopM, [40, 0, 0]);
});

test('ক্রন: ৩০ দিনের পুরোনো নিজে শেখা তথ্য বাদ, হাতে দেওয়া থাকে', async () => {
  const old = Date.now() - 40 * 24 * 3600 * 1000;
  await S.applyChanges('shopE', D, [{ key: 'manual1aa', s: 'in' }], true);
  await S.learnFromReply('shopE', D, ['auto1aaaa'], 'all');
  const doc = store.get('pharmacyStock/shopE');
  const items = JSON.parse(doc.j);
  items.auto1aaaa.at = old;
  items.manual1aa.at = old;
  doc.j = JSON.stringify(items);
  const r = await S.applyChanges('shopE', D, [], false, { expireBefore: Date.now() - 30 * 24 * 3600 * 1000 });
  assert.strictEqual(r.changed, 1);
  assert.ok(!mine('shopE').auto1aaaa);
  assert.ok(mine('shopE').manual1aa);
  assert.ok(!idx('auto1aaaa').shopE);
});

test('ভাগ সমানভাবে ছড়ায়', () => {
  const counts = new Array(256).fill(0);
  for (let i = 0; i < 25600; i++) counts[S.shardOf(Math.random().toString(36).slice(2, 15))]++;
  assert.ok(Math.max(...counts) < 200 && Math.min(...counts) > 40, `অসমান: ${Math.min(...counts)}–${Math.max(...counts)}`);
});

test('key যাচাই', () => {
  assert.ok(S.validKey('a1b2c3d4e5'));
  assert.ok(S.validKey('z9'));
  assert.ok(S.validKey('c_AbCdEf1234567890xyz1'));
  assert.ok(!S.validKey('../x'));
  assert.ok(!S.validKey('items.x'));
  assert.ok(!S.validKey('ABC123DEF'));
  assert.ok(!S.validKey('__proto__'));
  assert.ok(!S.validKey('constructor'));
});
