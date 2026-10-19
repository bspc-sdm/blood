// অ্যাম্বুলেন্স ডাকার জায়গা আর গন্তব্য (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬):
// Google Maps-এর লিংক/লেখা থেকে বিন্দু; ছোট লিংক সার্ভারে খোলা (নকল fetch — শুধু Google-এর ঠিকানা, সময়সীমা, সীমা);
// জায়গা থেকে উপজেলা আপনা থেকে (কাছের উপজেলা-কেন্দ্র; শহরের থানায় নয়); জায়গা থাকলে লেখা ঐচ্ছিক;
// গন্তব্য হাসপাতাল/ক্লিনিকের তালিকা থেকে — ভাড়ার তালিকায় id দিয়ে মেলা, নইলে হাসপাতালের উপজেলা/জেলা ধরে; তালিকা ও খোঁজা।
const test = require('node:test');
const path = require('path');
const assert = require('assert');
const SERVER = path.join(__dirname, '..');
const clock = require('./support/clock');
const F = require('./support/fakefb');
require.cache[path.join(SERVER, 'lib/firebase.js')] = { id: 'fb', filename: 'fb', loaded: true, exports: F.firebase };
const api = require(path.join(SERVER, 'api/community.js'));
const PLACE = require(path.join(SERVER, 'lib/ambplace.js'));
const FARE = require(path.join(SERVER, 'lib/ambfare.js'));
const M = require(path.join(SERVER, 'lib/memcache'));
const { db } = F.firebase;

const DISTRICTS = require(path.join(SERVER, 'lib/districts'));
const MYM = DISTRICTS.find((d) => d.en === 'Mymensingh');
const DISTRICT = MYM.bn;
const DHAKA = DISTRICTS.find((d) => d.en === 'Dhaka').bn;
const GAZIPUR = DISTRICTS.find((d) => d.en === 'Gazipur').bn;
const CENTERS = require(path.join(SERVER, 'lib/upazila-centers.json')).c;
const SADAR = 466;
const TRISHAL = 463;
const MMCH = 'dghs_392'; // ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল (সদর)
const TRISHAL_HOSP = 'dghs_19935'; // ব্র্যাক (বিএইচপি) - ত্রিশাল
const DMCH = 'dghs_31'; // ঢাকা মেডিকেল কলেজ হাসপাতাল
const HOUR = 3600 * 1000;

let ip = 0;
async function call(uid, action, body = {}) {
  let out;
  const res = { statusCode: 200, status(c) { this.statusCode = c; return this; }, json(j) { out = j; }, setHeader() {}, getHeader() {} };
  const headers = uid ? { authorization: `Bearer ${uid}` } : { 'x-forwarded-for': `10.8.0.${++ip % 250}` };
  await api({ method: 'POST', headers, body: { lang: 'bn', action, ...body } }, res);
  return { status: res.statusCode, body: out };
}
async function ok(uid, action, body) {
  const r = await call(uid, action, body);
  assert.strictEqual(r.status, 200, `${action}: ${JSON.stringify(r.body)}`);
  return r.body;
}
const user = async (uid) => {
  F.users.set(uid, { uid, email: `${uid}@example.com` });
  await db.doc(`users/${uid}`).set({ name: `নাম ${uid}`, lang: 'bn', phone: '01811000000' });
};
const chart = (local, rows = []) => ({ local, rows, oxygen: null, icu: null, night: null });
const veh = { district: DISTRICT, upazilaId: SADAR };

/** নকল fetch: url → { status, location?, body? }; ডাকা ঠিকানাগুলো calls-এ */
function fakeFetch(routes, calls = []) {
  return async (url, opts) => {
    calls.push(url);
    assert.strictEqual(opts.redirect, 'manual');
    const r = typeof routes === 'function' ? await routes(url, opts) : routes[url];
    if (!r) return { status: 404, headers: { get: () => null }, text: async () => '' };
    return { status: r.status, headers: { get: (h) => (h === 'location' ? r.location || null : null) }, text: async () => r.body || '' };
  };
}

test('Google Maps-এর লিংক/লেখা: !3d!4d (আসল জায়গা) আগে, q/query/ll, /search/, @, geo:, শুধু সংখ্যা; ছোট লিংক আলাদা; বাংলাদেশের বাইরে নয়', () => {
  const p = PLACE.parseMapsText;
  // জায়গার পাতা: @ ম্যাপের মাঝখান, !3d!4d আসল জায়গা — আসলটা
  assert.deepStrictEqual(
    p('https://www.google.com/maps/place/Dhaka+Medical+College+Hospital/@23.7261,90.3955,17z/data=!3m1!4b1!4m6!3m5!1s0x3755b8c1!8m2!3d23.725636!4d90.397697!16s%2Fm%2F0abc'),
    { lat: 23.72564, lng: 90.3977 },
  );
  assert.deepStrictEqual(p('https://maps.google.com/?q=23.9999,90.4203'), { lat: 23.9999, lng: 90.4203 });
  assert.deepStrictEqual(p('https://www.google.com/maps/search/?api=1&query=24.7471%2C90.4203'), { lat: 24.7471, lng: 90.4203 });
  assert.deepStrictEqual(p('https://www.google.com/maps/search/24.7471,+90.4203?entry=tts'), { lat: 24.7471, lng: 90.4203 });
  assert.deepStrictEqual(p('https://www.google.com/maps/@24.7471,90.4203,15z'), { lat: 24.7471, lng: 90.4203 });
  assert.deepStrictEqual(p('geo:24.7471,90.4203?q=24.7471,90.4203(Pin)'), { lat: 24.7471, lng: 90.4203 });
  // Google Maps-এ জায়গা চেপে ধরলে যে সংখ্যা দেখায়
  assert.deepStrictEqual(p('23.810331, 90.412521'), { lat: 23.81033, lng: 90.41252 });
  // শেয়ারের লেখা: নাম + ছোট লিংক
  assert.deepStrictEqual(p('Dropped pin\nNear Trishal\nhttps://maps.app.goo.gl/AbC12xYz?g_st=ic'), { short: 'https://maps.app.goo.gl/AbC12xYz?g_st=ic' });
  assert.deepStrictEqual(p('See https://goo.gl/maps/Xy12AB please'), { short: 'https://goo.gl/maps/Xy12AB' });
  // অন্য দেশ, অন্য সাইট, ফাঁকা
  assert.strictEqual(p('https://maps.google.com/?q=51.5074,-0.1278'), null);
  assert.strictEqual(p('https://example.com/xyz'), null);
  assert.strictEqual(p('https://goo.gl/abc'), null);
  assert.strictEqual(p('   '), null);
});

test('ছোট লিংক খোলা (নকল fetch): redirect ধরে বিন্দু; শুধু Google-এর ঠিকানা; পাতার লেখায় খোঁজা; সময়সীমা; অন্য লিংকে fetch নয়', async () => {
  const short = 'https://maps.app.goo.gl/AbC12xYz';
  const place = 'https://www.google.com/maps/place/Trishal/@24.58,90.39,14z/data=!4m6!3m5!8m2!3d24.5637!4d90.4104';
  let calls = [];
  assert.deepStrictEqual(await PLACE.resolveShort(short, fakeFetch({ [short]: { status: 302, location: place } }, calls)), { lat: 24.5637, lng: 90.4104 });
  assert.deepStrictEqual(calls, [short]);

  // দুই ধাপ (consent পাতা হয়ে — continue-এর ভেতরে লিংক)
  const consent = `https://consent.google.com/m?continue=${encodeURIComponent('https://www.google.com/maps/search/24.7471,+90.4203')}`;
  calls = [];
  assert.deepStrictEqual(await PLACE.resolveShort(short, fakeFetch({ [short]: { status: 301, location: consent } }, calls)), { lat: 24.7471, lng: 90.4203 });

  // শেষ পাতায় লিংকে বিন্দু নেই — পাতার লেখায়
  const noCoords = 'https://www.google.com/maps/place/Some+Clinic';
  assert.deepStrictEqual(await PLACE.resolveShort(short, fakeFetch({
    [short]: { status: 302, location: noCoords },
    [noCoords]: { status: 200, body: '<html>…window.APP_INITIALIZATION_STATE=[[[5000,90.3977,23.7256],[0,0,0]],[null,null,23.725636,90.397697]]…' },
  })), { lat: 23.72564, lng: 90.3977 });

  // Google ছাড়া অন্য ঠিকানায় redirect — যাওয়া হয় না
  calls = [];
  assert.strictEqual(await PLACE.resolveShort(short, fakeFetch({ [short]: { status: 302, location: 'https://evil.example/@23.7,90.4' } }, calls)), null);
  assert.strictEqual(calls.length, 1);
  // http (https নয়) — না
  assert.strictEqual(await PLACE.resolveShort(short, fakeFetch({ [short]: { status: 302, location: 'http://www.google.com/maps/@23.7,90.4,15z' } })), null);
  // অনেক ধাপ — থামে
  calls = [];
  assert.strictEqual(await PLACE.resolveShort(short, fakeFetch(() => ({ status: 302, location: 'https://www.google.com/maps/loop' }), calls)), null);
  assert.ok(calls.length <= 5);
  // সাড়া না দিলে সময়সীমায় থামে
  const hang = (url, opts) => new Promise((_, rej) => opts.signal.addEventListener('abort', () => rej(new Error('aborted'))));
  const t0 = Date.now();
  assert.strictEqual(await PLACE.resolveShort(short, hang, 50), null);
  assert.ok(Date.now() - t0 < 2000);
  // ছোট লিংক নয় — fetch হয় না
  calls = [];
  for (const u of ['https://example.com/x', 'http://maps.app.goo.gl/AbC', 'https://goo.gl/abc', 'not a url']) {
    assert.strictEqual(await PLACE.resolveShort(u, fakeFetch({}, calls)), null);
  }
  assert.strictEqual(calls.length, 0);
});

test('maps_link_resolve: লম্বা লিংক সরাসরি, ছোট লিংক নকল fetch-এ; ভুল লিংকে বাংলা বার্তা; লগইন লাগে; মিনিটে ৬টার বেশি নয়', async () => {
  const realFetch = globalThis.fetch;
  const calls = [];
  const short = 'https://maps.app.goo.gl/Zz99';
  globalThis.fetch = fakeFetch({ [short]: { status: 302, location: 'https://www.google.com/maps/@24.5637,90.4104,15z' } }, calls);
  try {
    await user('link-user');
    assert.deepStrictEqual(await ok('link-user', 'maps_link_resolve', { url: 'https://maps.google.com/?q=24.7471,90.4203' }), { lat: 24.7471, lng: 90.4203 });
    assert.strictEqual(calls.length, 0);
    assert.deepStrictEqual(await ok('link-user', 'maps_link_resolve', { url: `জায়গা: ${short}` }), { lat: 24.5637, lng: 90.4104 });
    assert.deepStrictEqual(calls, [short]);
    const bad = await call('link-user', 'maps_link_resolve', { url: 'https://maps.app.goo.gl/none' });
    assert.strictEqual(bad.status, 400);
    assert.match(bad.body.error, /চেপে ধরে/);
    assert.strictEqual((await call('link-user', 'maps_link_resolve', { url: 'হাসপাতালের পাশে' })).status, 400);
    assert.strictEqual((await call(null, 'maps_link_resolve', { url: short })).status, 401);
    // সীমা: ছোট লিংক মিনিটে ৬টা (আগে ২টা হয়েছে)
    await user('link-busy');
    for (let i = 0; i < 6; i++) assert.strictEqual((await call('link-busy', 'maps_link_resolve', { url: short })).status, 200);
    assert.strictEqual((await call('link-busy', 'maps_link_resolve', { url: short })).status, 429);
    // লম্বা লিংক সীমায় গোনে না
    assert.strictEqual((await call('link-busy', 'maps_link_resolve', { url: 'https://maps.google.com/?q=24.7471,90.4203' })).status, 200);
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('জায়গা থেকে উপজেলা: জেলার ভেতরে কাছের কেন্দ্র; শহরের থানায় (কেন্দ্র নেই) বা অনেক দূরে হলে নয়', () => {
  const [tLat, tLng] = CENTERS[TRISHAL];
  assert.strictEqual(PLACE.nearestUpazila(DISTRICT, tLat + 0.01, tLng - 0.01), TRISHAL);
  const [sLat, sLng] = CENTERS[320];
  assert.strictEqual(PLACE.nearestUpazila(GAZIPUR, sLat, sLng), 320);
  // ঢাকা শহর (শাহবাগ): থানাগুলোর কেন্দ্র নেই, শহরের কেন্দ্র কাছে — বেছে নেওয়া থানাই থাকে
  assert.strictEqual(PLACE.nearestUpazila(DHAKA, 23.7383, 90.3955), null);
  // সাভারের ভেতরে — সাভার
  const [vLat, vLng] = CENTERS[365];
  assert.strictEqual(PLACE.nearestUpazila(DHAKA, vLat, vLng), 365);
  // জেলা থেকে অনেক দূরে, ভুল সংখ্যা
  assert.strictEqual(PLACE.nearestUpazila(DISTRICT, 22.35, 91.8), null);
  assert.strictEqual(PLACE.nearestUpazila(DISTRICT, NaN, 90), null);
});

test('ডাকা: জায়গা দিলে উপজেলা না বাছলেও আপনা থেকে (গোনাতেও), লেখা ঐচ্ছিক; জায়গা না দিলে লেখা লাগে; ঠিক জায়গা রাজির আগে চালক পান না', async () => {
  for (const k of [...F.store.keys()]) if (/^(ambulances|ambulanceRequests|ambReqLimits)\//.test(k)) F.store.delete(k);
  M.clear('');
  clock.setDhaka('2026-10-19 12:00');
  const [tLat, tLng] = CENTERS[TRISHAL];
  await user('place-p');
  await user('place-d');
  await db.doc('ambulances/pa1').set({
    name: 'গাড়ি pa1', phone: '01711000000', vehicleNo: 'V-pa1', types: ['general'], district: DISTRICT, upazilaId: TRISHAL, area: '',
    ownerUid: 'place-d', status: 'approved', available: true, availableAt: Date.now() - HOUR, lat: tLat, lng: tLng,
    fareChart: chart(800, [{ kind: 'district', district: DHAKA, amount: 5000 }]), createdAt: Date.now() - 30 * 24 * HOUR,
  });
  const where = { district: DISTRICT, lat: tLat + 0.005, lng: tLng, gps: true };
  const dest = { kind: 'hospital', facilityId: TRISHAL_HOSP, name: 'ব্র্যাক ত্রিশাল' };
  const c = await ok('place-p', 'ambreq_count', { ...where, dest, maxFare: 1000 });
  assert.strictEqual(c.upazilaId, TRISHAL);
  assert.strictEqual(c.count, 1);
  // উপজেলা বাছা থাকলে সেটাই (আপনা থেকে নয়)
  assert.strictEqual((await ok('place-p', 'ambreq_count', { ...where, upazilaId: SADAR, dest })).upazilaId, undefined);
  // জায়গা নেই, লেখা নেই — না
  assert.strictEqual((await call('place-p', 'ambreq_create', { district: DISTRICT, upazilaId: TRISHAL, pickup: '', phone: '01811000000', dest })).status, 400);
  const r = await ok('place-p', 'ambreq_create', { ...where, pickup: '', phone: '01811000000', dest, maxFare: 1000 });
  const x = (await db.doc(`ambulanceRequests/${r.id}`).get()).data();
  assert.strictEqual(x.upazilaId, TRISHAL);
  assert.strictEqual(x.upazilaAuto, true);
  assert.strictEqual(x.pickup, '');
  assert.deepStrictEqual(x.dest, { kind: 'hospital', facilityId: TRISHAL_HOSP, name: 'ব্র্যাক ত্রিশাল', upazilaId: TRISHAL, district: DISTRICT });
  // চালক রাজির আগে: গন্তব্যের নাম হ্যাঁ, ঠিক জায়গা নয়
  const d = (await ok('place-d', 'ambreq_driver')).items.find((i) => i.id === r.id);
  assert.strictEqual(d.destLabel, 'ব্র্যাক ত্রিশাল');
  assert.strictEqual(d.suggestedFare, 800);
  assert.strictEqual(d.lat, undefined);
  clock.reset();
});

test('গন্তব্যের হাসপাতাল ও ভাড়া: তালিকার id দিয়ে মেলা (নাম ভিন্ন হলেও), নইলে নাম, নইলে হাসপাতালের উপজেলা/জেলা; উপজেলা/জেলা তালিকা থেকে', () => {
  // রোগীর পাঠানো উপজেলা/জেলা নয় — তালিকারটা
  const mmch = FARE.destOf({ kind: 'hospital', facilityId: MMCH, name: 'মমেক', upazilaId: TRISHAL, district: DHAKA });
  assert.deepStrictEqual(mmch, { kind: 'hospital', facilityId: MMCH, name: 'মমেক', upazilaId: SADAR, district: DISTRICT });
  // নাম না দিলে তালিকার নাম
  assert.strictEqual(FARE.destOf({ kind: 'hospital', facilityId: DMCH }).name, 'ঢাকা মেডিকেল কলেজ হাসপাতাল');
  // অন্য জায়গা (লেখা) আগের মতো
  assert.deepStrictEqual(FARE.destOf({ kind: 'hospital', name: 'চরপাড়া ক্লিনিক' }), { kind: 'hospital', name: 'চরপাড়া ক্লিনিক' });
  // ভুল id → শুধু লেখা; লেখাও না থাকলে null
  assert.deepStrictEqual(FARE.destOf({ kind: 'hospital', facilityId: '../x', name: 'চরপাড়া ক্লিনিক' }), { kind: 'hospital', name: 'চরপাড়া ক্লিনিক' });
  assert.strictEqual(FARE.destOf({ kind: 'hospital', facilityId: 'dghs_0' }), null);

  const byId = chart(800, [{ kind: 'hospital', facilityId: MMCH, name: 'মেডিকেল', amount: 650 }, { kind: 'district', district: DHAKA, amount: 5000 }]);
  // id মেলে — নাম আলাদা হলেও
  assert.strictEqual(FARE.baseFare(byId, veh, mmch), 650);
  // id নেই, নাম মেলে (পুরোনো লেখা সারি)
  const byName = chart(800, [{ kind: 'hospital', name: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল', amount: 700 }]);
  assert.strictEqual(FARE.baseFare(byName, veh, FARE.destOf({ kind: 'hospital', facilityId: TRISHAL_HOSP, name: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল' })), 700);
  // সারি নেই: হাসপাতাল নিজের উপজেলায় → local; অন্য উপজেলায় → সেই উপজেলার ঘর, নইলে জেলার ঘর
  const areas = chart(800, [{ kind: 'upazila', upazilaId: TRISHAL, amount: 1200 }, { kind: 'district', district: DHAKA, amount: 5000 }]);
  assert.strictEqual(FARE.baseFare(areas, veh, FARE.destOf({ kind: 'hospital', facilityId: MMCH, name: 'মমেক' })), 800);
  assert.strictEqual(FARE.baseFare(areas, veh, FARE.destOf({ kind: 'hospital', facilityId: TRISHAL_HOSP, name: 'ব্র্যাক' })), 1200);
  assert.strictEqual(FARE.baseFare(areas, veh, FARE.destOf({ kind: 'hospital', facilityId: DMCH, name: 'ঢামেক' })), 5000);
  assert.strictEqual(FARE.estimate(areas, veh, FARE.destOf({ kind: 'hospital', facilityId: DMCH, name: 'ঢামেক' }), {}), 5000);
  // লেখা গন্তব্য, মেলে না — জানা নেই
  assert.strictEqual(FARE.baseFare(areas, veh, FARE.destOf({ kind: 'hospital', name: 'অন্য কোথাও' })), null);

  // মালিকের তালিকা: হাসপাতালের সারি তালিকা থেকে (id) বা লেখা; একই হাসপাতাল দুবার নয়, তবে id-ওয়ালা আর লেখা আলাদা
  const n = FARE.normalizeChart({ local: 800, rows: [
    { kind: 'hospital', facilityId: DMCH, name: 'ঢামেক', amount: 5500 },
    { kind: 'hospital', name: 'ঢাকা মেডিকেল', amount: 5600 },
  ] }, veh);
  assert.deepStrictEqual(n.chart.rows[0], { kind: 'hospital', facilityId: DMCH, name: 'ঢামেক', upazilaId: 1039, district: DHAKA, amount: 5500 });
  const dup = FARE.normalizeChart({ local: 800, rows: [
    { kind: 'hospital', facilityId: DMCH, name: 'ঢামেক', amount: 5500 },
    { kind: 'hospital', facilityId: DMCH, name: 'ঢাকা মেডিকেল', amount: 5600 },
  ] }, veh);
  assert.match(dup.error[1], /দুবার/);
});

test('হাসপাতাল/ক্লিনিকের তালিকা: জেলারটা (ডায়াগনস্টিক নয়, বড়গুলো আগে; জায়গা দিলে কাছেরটা আগে), সারা দেশের বড়গুলো, বাংলা/ইংরেজি খোঁজা ("য়" একরকম)', async () => {
  await user('fac-user');
  M.clear('');
  const f = await ok('fac-user', 'amb_facilities', { district: DISTRICT });
  assert.ok(f.items.length > 100);
  assert.ok(f.items.every((r) => r[4] === DISTRICT && r[3] !== 'diagnostic'));
  assert.strictEqual(f.items[0][0], MMCH); // মেডিকেল কলেজ আগে
  const topIds = f.top.map((r) => r[0]);
  for (const id of [DMCH, 'dghs_7', 'x_bsmmu', 'dghs_22839']) assert.ok(topIds.includes(id), id);
  // ত্রিশালে দাঁড়িয়ে: ত্রিশালের হাসপাতাল আগে
  const [tLat, tLng] = CENTERS[TRISHAL];
  const near = await ok('fac-user', 'amb_facilities', { district: DISTRICT, lat: tLat, lng: tLng });
  assert.strictEqual(near.items[0][5], TRISHAL);
  // খোঁজা: বাংলা (য় দুই রূপে), ইংরেজি, সারা দেশে (জেলারটা আগে)
  const yaA = 'ময়মনসিংহ মেডিকেল'; // য + ়
  const yaB = 'ময়মনসিংহ মেডিকেল'; // য়
  for (const q of [yaA, yaB, 'mymensingh medical']) {
    const s = await ok('fac-user', 'amb_facilities', { district: DISTRICT, q });
    assert.strictEqual(s.items[0][0], MMCH, q);
  }
  const dhaka = await ok('fac-user', 'amb_facilities', { district: DISTRICT, q: 'dhaka medical college' });
  assert.ok(dhaka.items.some((r) => r[0] === DMCH));
  const bsmmu = await ok('fac-user', 'amb_facilities', { district: DISTRICT, q: 'পিজি' });
  assert.strictEqual(bsmmu.items[0][0], 'x_bsmmu');
  assert.strictEqual((await call('fac-user', 'amb_facilities', { district: 'x' })).status, 400);
});
