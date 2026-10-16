// লগইন ছাড়া ব্যবহার (অতিথি, নতুন নকশা N1/N2): শুধু পড়ার কাজ টোকেন ছাড়া চলে, লেখার কাজে লগইন লাগে।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const geo = require(path.join(SERVER, 'lib/geo'));
const DIST = require(path.join(SERVER, 'lib/districts')).find((d) => d.en === 'Mymensingh').bn;

async function guestCall(action, body = {}, ip = '1.2.3.4') {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  await api({ method: 'POST', headers: { 'x-forwarded-for': ip }, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}

test('অতিথি: তালিকা দেখা যায়, লেখার কাজে লগইন লাগে', async () => {
  const s = F.store;
  const now = Date.now();
  const lat = 24.75, lng = 90.40;
  s.set('requests/r1', {
    requesterUid: 'u1', requesterName: 'গোপন নাম', patientGroup: 'O+', bags: 2, hospital: 'মেডিকেল কলেজ', placeName: DIST,
    lat, lng, geoOpen: geo.hash(lat, lng), neededByMillis: now + 86400000, status: 'open', urgent: true, note: 'গোপন নোট',
  });
  s.set('requests/r2', {
    requesterUid: 'u2', requesterName: 'x', patientGroup: 'A+', bags: 1, hospital: 'বন্ধ', placeName: DIST,
    lat, lng, neededByMillis: now + 86400000, status: 'closed',
  });
  s.set('ambulances/a1', { name: 'জরুরি সেবা', status: 'approved', district: DIST, phone: '01711000000', types: [] });

  const r = await guestCall('guest_requests', { lat, lng });
  assert.strictEqual(r.status, 200, JSON.stringify(r.body));
  assert.strictEqual(r.body.items.length, 1);
  const item = r.body.items[0];
  assert.strictEqual(item.id, 'r1');
  assert.strictEqual(item.patientGroup, 'O+');
  // নাম, uid, নোট, ঠিক লোকেশন যায় না
  for (const k of ['requesterName', 'requesterUid', 'note', 'lat', 'lng']) assert.ok(!(k in item), k);

  const a = await guestCall('ambulances', { district: DIST });
  assert.strictEqual(a.status, 200, JSON.stringify(a.body));
  assert.strictEqual(a.body.items.length, 1);
  assert.strictEqual(a.body.items[0].canRate, false);

  const e = await guestCall('emergencies_active', { district: DIST });
  assert.strictEqual(e.status, 200, JSON.stringify(e.body));
  assert.deepStrictEqual(e.body.ask, []);

  const q = await guestCall('questions');
  assert.strictEqual(q.status, 200, JSON.stringify(q.body));

  // লেখার কাজ, নিজের তথ্য: লগইন দরকার
  for (const action of ['ask', 'me', 'report', 'book', 'request_create', 'ambulance_called', 'med_find', 'admin_overview']) {
    const x = await guestCall(action, { text: 'হ্যালো' });
    assert.strictEqual(x.status, 401, `${action} → ${x.status}`);
  }
  // কেয়ারগিভার সাজা যায় না
  const asUid = await guestCall('labs', { district: DIST, asUid: 'u1' });
  assert.strictEqual(asUid.status, 200);
});

test('অতিথি: IP-প্রতি মিনিটে ৬০টার বেশি নয়', async () => {
  let last;
  for (let i = 0; i < 61; i++) last = await guestCall('catalog_get', {}, '9.9.9.9');
  assert.strictEqual(last.status, 429);
  const other = await guestCall('catalog_get', {}, '8.8.8.8');
  assert.strictEqual(other.status, 200);
});
