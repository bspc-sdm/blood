// কাছের খোঁজা (geohash): অ্যাপের GeoFireUtils-এর সাথে একই geohash, দূরত্বের ভেতরে ছাঁকা
const test = require('node:test');
const assert = require('node:assert');
const Geo = require('../lib/geo');

test('geohash ১০ অক্ষরের, ভুল স্থানাঙ্কে null', () => {
  assert.strictEqual(Geo.hash(24.75, 90.4), 'wh2kc1e70w');
  assert.strictEqual(Geo.hash(null, 90), null);
  assert.strictEqual(Geo.hash(NaN, 90), null);
});

test('near: ঘর ধরে কোয়েরি, একই ডক একবার, দূরত্বের বাইরে বাদ, কাছেরটা আগে', async () => {
  const docs = [
    { id: 'a', lat: 24.75, lng: 90.40 },
    { id: 'b', lat: 24.80, lng: 90.40 },
    { id: 'c', lat: 23.81, lng: 90.41 }, // ঢাকা, ~১০০ কিমি
    { id: 'd' },
  ].map((x) => ({ id: x.id, data: () => x, h: Geo.hash(x.lat, x.lng) || '' }));
  const query = {
    orderBy: () => ({
      startAt: (s) => ({
        endAt: (e) => ({ limit: () => ({ get: async () => ({ docs: docs.filter((d) => d.h >= s && d.h <= e + '') }) }) }),
      }),
    }),
  };
  const out = await Geo.near(query, 'geohash', 24.75, 90.4, 25);
  assert.deepStrictEqual(out.map((x) => x.doc.id), ['a', 'b']);
  assert.ok(out[1].km > 5 && out[1].km < 6);
});

test('donorKey: গ্রুপ|geohash, ভুল গ্রুপ বা লোকেশন ছাড়া null', () => {
  assert.strictEqual(Geo.donorKey('O+', 'wh2kc1e70w'), 'O+|wh2kc1e70w');
  assert.strictEqual(Geo.donorKey('X+', 'wh2kc1e70w'), null);
  assert.strictEqual(Geo.donorKey('A+', ''), null);
  assert.strictEqual(Geo.donorKeyOf({ bloodGroup: 'AB-', lat: 24.75, lng: 90.4 }), 'AB-|wh2kc1e70w');
  assert.strictEqual(Geo.donorKeyOf({ bloodGroup: 'AB-' }), null);
});

test('nearDonors: শুধু চাওয়া গ্রুপের মানুষ (A+ আর AB+ আলাদা), দূরত্বের ভেতরে, কাছেরটা আগে', async () => {
  const people = [
    { id: 'o1', bloodGroup: 'O-', lat: 24.75, lng: 90.40 },
    { id: 'a1', bloodGroup: 'A+', lat: 24.751, lng: 90.401 },
    { id: 'ab', bloodGroup: 'AB+', lat: 24.75, lng: 90.40 },
    { id: 'o2', bloodGroup: 'O-', lat: 24.78, lng: 90.40 },
    { id: 'far', bloodGroup: 'O-', lat: 23.81, lng: 90.41 },
    { id: 'none', bloodGroup: 'O-' },
  ].map((x) => ({ id: x.id, data: () => x, k: Geo.donorKeyOf(x) }));
  const asked = [];
  const db = {
    collection: () => ({
      orderBy: (f) => ({
        startAt: (s) => ({
          endAt: (e) => ({ limit: () => ({ get: async () => { asked.push([f, s]); return { docs: people.filter((d) => d.k && d.k >= s && d.k <= e) }; } }) }),
        }),
      }),
    }),
  };
  const out = await Geo.nearDonors(db, ['O-', 'A+', 'O-', 'bad'], 24.75, 90.4, 10);
  assert.deepStrictEqual(out.map((x) => x.doc.id), ['o1', 'a1', 'o2']);
  assert.ok(asked.every(([f, s]) => f === 'donorGeo' && /^(O-|A\+)\|/.test(s)));
});

test('fixDonorGeo: শুধু ভুল বা নেই এমন মান বদলায়, লোকেশন না থাকলে মুছে দেয়', async () => {
  const updates = [];
  const mk = (id, x) => ({ id, ref: id, data: () => x, get: (k) => x[k] });
  const docs = [
    mk('ok', { bloodGroup: 'B+', geohash: 'abc', donorGeo: 'B+|abc' }),
    mk('old', { bloodGroup: 'B-', geohash: 'abc', donorGeo: 'B+|abc' }),
    mk('new', { bloodGroup: 'A-', geohash: 'xyz' }),
    mk('gone', { bloodGroup: 'A-', donorGeo: 'A-|xyz' }),
  ];
  const DEL = Symbol('del');
  const db = { batch: () => ({ update: (r, v) => updates.push([r, v.donorGeo]), commit: async () => {} }) };
  const n = await Geo.fixDonorGeo(db, { delete: () => DEL }, docs);
  assert.strictEqual(n, 3);
  assert.deepStrictEqual(updates, [['old', 'B-|abc'], ['new', 'A-|xyz'], ['gone', DEL]]);
});
