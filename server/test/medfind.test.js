// ওষুধ খোঁজা: তালিকা থেকে নাম মেলানো
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

require.cache[path.join(__dirname, '../lib/firebase.js')] = {
  id: 'firebase', filename: 'firebase', loaded: true,
  exports: { db: {}, FieldValue: {}, HttpError: Error, sendPush: async () => 0 },
};
const M = require('../lib/medfind');

test('ব্র্যান্ডের নামের শুরু মিললে আগে', () => {
  const r = M.searchDrugs('napa');
  assert.ok(r.length > 0 && r.length <= 15);
  assert.ok(r[0].name.toLowerCase().startsWith('napa'));
});

test('জেনেরিক নাম দিয়েও পাওয়া যায়', () => {
  assert.ok(M.searchDrugs('esomeprazole').some((d) => /esomeprazole/i.test(d.generic)));
});

test('এক অক্ষরে খোঁজা হয় না, একই ওষুধ দুবার আসে না', () => {
  assert.deepStrictEqual(M.searchDrugs('n'), []);
  const r = M.searchDrugs('para');
  const keys = r.map((d) => `${d.name}|${d.generic}|${d.form}|${d.company}`);
  assert.strictEqual(new Set(keys).size, keys.length);
});
