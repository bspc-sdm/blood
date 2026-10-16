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
