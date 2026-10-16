// ফেরত নেওয়া চাকরি/সিভি ডেটা মোছা: ছোট নকল Firestore দিয়ে
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

const store = new Map(); // path → data
const DEL = { del: true };
function match(data, f) {
  const v = f.field.split('.').reduce((o, k) => (o == null ? undefined : o[k]), data);
  if (f.op === '==') return v === f.val;
  if (f.op === 'in') return f.val.includes(v);
  if (f.op === '>') return typeof v === 'number' && v > f.val;
  throw new Error(f.op);
}
function query(coll, filters = [], lim = Infinity) {
  return {
    where: (field, op, val) => query(coll, [...filters, { field, op, val }], lim),
    limit: (n) => query(coll, filters, n),
    get: async () => {
      const docs = [...store.entries()]
        .filter(([p, d]) => p.split('/')[0] === coll && p.split('/').length === 2 && filters.every((f) => match(d, f)))
        .slice(0, lim)
        .map(([p, d]) => ({ ref: { path: p }, data: () => d }));
      return { empty: docs.length === 0, size: docs.length, docs };
    },
  };
}
const doc = (p) => ({
  path: p,
  get: async () => ({ exists: store.has(p), get: (k) => (store.get(p) || {})[k] }),
  set: async (v) => store.set(p, { ...(store.get(p) || {}), ...v }),
  delete: async () => store.delete(p),
});
const db = {
  collection: (c) => query(c),
  doc,
  batch: () => {
    const ops = [];
    return {
      delete: (ref) => ops.push(() => store.delete(ref.path)),
      update: (ref, ch) => ops.push(() => {
        const d = { ...store.get(ref.path) };
        Object.entries(ch).forEach(([k, v]) => (v === DEL ? delete d[k] : (d[k] = v)));
        store.set(ref.path, d);
      }),
      commit: async () => ops.forEach((f) => f()),
    };
  },
};
require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { db, FieldValue: { delete: () => DEL } },
};
const { cleanupRemovedFeatures } = require('../lib/cleanup');

test('চাকরি/সিভির ডেটা মোছে, বাকি সব থাকে, তারপর আর চলে না', async () => {
  store.set('jobs/j1', { title: 'x' });
  store.set('applications/j1_u1', { uid: 'u1' });
  store.set('employers/u2', {});
  store.set('profiles/u1', { headline: 'h' });
  store.set('jobSaves/u1_j1', {});
  store.set('jobAlerts/u1', {});
  store.set('verifyDocs/employer_u2_tradeLicense', { kind: 'employer' });
  store.set('verifyDocs/pharmacy_p1_license', { kind: 'pharmacy' });
  store.set('modReports/a', { kind: 'job' });
  store.set('modReports/b', { kind: 'question' });
  store.set('inbox/u1_1', { data: { type: 'job_alert' } });
  store.set('inbox/u1_2', { data: { type: 'response' } });
  store.set('serverState/u1', { donationCount: 2, donationCountedDay: 5, inboxSeenAt: 9 });
  store.set('config/jobRules', { words: [] });
  store.set('users/u1', { name: 'n' });

  const r = await cleanupRemovedFeatures();
  assert.strictEqual(r.done, true);
  assert.deepStrictEqual(
    [...store.keys()].sort(),
    ['config/cleanup', 'inbox/u1_2', 'modReports/b', 'serverState/u1', 'users/u1', 'verifyDocs/pharmacy_p1_license'],
  );
  assert.deepStrictEqual(store.get('serverState/u1'), { inboxSeenAt: 9 });
  store.set('jobs/j2', {});
  assert.strictEqual(await cleanupRemovedFeatures(), null);
  assert.ok(store.has('jobs/j2'));
});
