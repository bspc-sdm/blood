// কাছের জিনিস খোঁজা (geohash): জেলার তালিকা ধরে সব পড়ার বদলে শুধু আশেপাশের ঘরগুলো পড়া হয়।
// রক্ত (ডোনার, রিকোয়েস্ট, জরুরি আহ্বান) এখন এটা ব্যবহার করে; পরে বাড়ি ভাড়া আর পার্কিংও একই নিয়মে।
//
// নিয়ম: যে কালেকশনে খুঁজতে হবে, তার প্রতিটা ডকে `lat`, `lng` আর একটা geohash ঘর (যেমন `geohash`) থাকে।
// কোয়েরি শুধু ওই একটা ঘরে range (orderBy + startAt/endAt) — তাই আলাদা composite index লাগে না।
// অবস্থা (খোলা/বন্ধ) দিয়ে ছাঁকতে হলে আলাদা ঘর রাখা হয় যা শুধু খোলা থাকলে থাকে (যেমন রিকোয়েস্টের `geoOpen`)।
const G = require('geofire-common');

/** অ্যাপের মতোই ১০ অক্ষরের geohash (GeoFireUtils-এর সাথে মেলে) */
function hash(lat, lng) {
  if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return G.geohashForLocation([lat, lng], 10);
}

const distanceKm = (aLat, aLng, bLat, bLng) => G.distanceBetween([aLat, aLng], [bLat, bLng]);

/**
 * কাছের ডক: radiusKm-এর ভেতরে থাকা সব ডক (দূরত্বসহ, কাছেরটা আগে)।
 * query: কালেকশন (বা আগে থেকে where দেওয়া কোয়েরি — তবে where দিলে index লাগতে পারে, তাই সাধারণত খালি কালেকশন)
 * field: geohash ঘরের নাম
 */
async function near(query, field, lat, lng, radiusKm, { limitPerBound = 2000 } = {}) {
  const bounds = G.geohashQueryBounds([lat, lng], radiusKm * 1000);
  const snaps = await Promise.all(bounds.map(([start, end]) => query.orderBy(field).startAt(start).endAt(end).limit(limitPerBound).get()));
  return collect(snaps, lat, lng, radiusKm);
}

const GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];

/**
 * ডোনার খোঁজার ঘর: "গ্রুপ|geohash" (যেমন "O+|wh2kc1e70w")। গ্রুপ বা লোকেশন না থাকলে null।
 * এক ঘরেই গ্রুপ আর জায়গা — তাই শুধু মানানসই গ্রুপের মানুষ পড়া হয়, আলাদা composite index লাগে না।
 */
function donorKey(bloodGroup, geohash) {
  if (!GROUPS.includes(bloodGroup) || typeof geohash !== 'string' || !geohash) return null;
  return `${bloodGroup}|${geohash}`;
}

/** ব্যবহারকারীর ডক থেকে donorGeo-র ঠিক মান */
const donorKeyOf = (x) => donorKey(x.bloodGroup, x.geohash || hash(x.lat, x.lng));

/**
 * কাছের ডোনার: শুধু groups-এর মানুষ, radiusKm-এর ভেতরে (দূরত্বসহ, কাছেরটা আগে)।
 * যোগ্যতা, নোটিফিকেশন চালু কি না — এসব আগের মতোই কোডে ছাঁকা হয়।
 */
async function nearDonors(db, groups, lat, lng, radiusKm, { limitPerBound = 2000 } = {}) {
  const bounds = G.geohashQueryBounds([lat, lng], radiusKm * 1000);
  const col = db.collection('users');
  const list = [...new Set(groups)].filter((g) => GROUPS.includes(g));
  const snaps = await Promise.all(list.flatMap((g) => bounds.map(([start, end]) =>
    col.orderBy('donorGeo').startAt(`${g}|${start}`).endAt(`${g}|${end}`).limit(limitPerBound).get())));
  return collect(snaps, lat, lng, radiusKm);
}

// সব পুরোনো প্রোফাইলে donorGeo বসানো শেষ হয়েছে কি না (একবার হ্যাঁ হলে আর পড়া হয় না)
let donorGeoDone = false;
async function donorGeoReady(db) {
  if (donorGeoDone) return true;
  const f = await db.doc('config/migrations').get().catch(() => null);
  donorGeoDone = !!(f && f.exists && f.get('usersDonorGeo') === true);
  return donorGeoDone;
}

/** ব্যবহারকারীদের donorGeo ঠিক করা (যাঁদের মান নেই বা পুরোনো) — কতজন বদলালো */
async function fixDonorGeo(db, FieldValue, docs) {
  const fix = docs
    .map((d) => ({ d, key: donorKeyOf(d.data()) }))
    .filter(({ d, key }) => (d.get('donorGeo') || null) !== key);
  for (let i = 0; i < fix.length; i += 400) {
    const b = db.batch();
    fix.slice(i, i + 400).forEach(({ d, key }) => b.update(d.ref, { donorGeo: key || FieldValue.delete() }));
    await b.commit();
  }
  return fix.length;
}

function collect(snaps, lat, lng, radiusKm) {
  const seen = new Set();
  const out = [];
  for (const snap of snaps) {
    for (const d of snap.docs) {
      if (seen.has(d.id)) continue;
      seen.add(d.id);
      const x = d.data();
      if (typeof x.lat !== 'number' || typeof x.lng !== 'number') continue;
      const km = distanceKm(lat, lng, x.lat, x.lng);
      if (km <= radiusKm) out.push({ doc: d, km });
    }
  }
  return out.sort((a, b) => a.km - b.km);
}

/**
 * দৈনিক রক্ষণাবেক্ষণ:
 * - যাঁদের লোকেশন আছে কিন্তু geohash নেই (পুরোনো প্রোফাইল), তাঁদের geohash বসানো
 * - খোলা রিকোয়েস্টে geoOpen বসানো (আগের রিকোয়েস্ট), আর বন্ধ রিকোয়েস্ট থেকে geoOpen সরানো
 */
async function maintain(db, FieldValue, isActive, now = Date.now()) {
  const out = { usersFixed: 0, requestsOpened: 0, requestsCleared: 0 };
  // ব্যবহারকারী: একবারই পুরো তালিকা দেখা (চিহ্ন রেখে), পরে শুধু নতুনরা অ্যাপ থেকেই geohash নিয়ে আসেন
  const flag = db.doc('config/migrations');
  const f = await flag.get();
  if (!(f.exists && f.get('usersGeohash') === true)) {
    const users = await db.collection('users').get();
    const fix = users.docs.filter((d) => !d.get('geohash') && hash(d.get('lat'), d.get('lng')));
    for (let i = 0; i < fix.length; i += 400) {
      const b = db.batch();
      fix.slice(i, i + 400).forEach((d) => b.update(d.ref, { geohash: hash(d.get('lat'), d.get('lng')) }));
      await b.commit();
    }
    out.usersFixed = fix.length;
    await flag.set({ usersGeohash: true, usersGeohashAt: now }, { merge: true });
  }
  // donorGeo (গ্রুপ|geohash): একবার সবার জন্য, তারপর প্রতিদিন শুধু গত দিনে প্রোফাইল বা লোকেশন বদলানোদের জন্য
  // (নতুন অ্যাপ নিজেই বসায়; এটা পুরোনো অ্যাপ থেকে বদলানো প্রোফাইলের জন্য)
  const f2 = await flag.get();
  if (!(f2.exists && f2.get('usersDonorGeo') === true)) {
    const users = await db.collection('users').get();
    out.donorGeoFixed = await fixDonorGeo(db, FieldValue, users.docs);
    await flag.set({ usersDonorGeo: true, usersDonorGeoAt: now }, { merge: true });
    donorGeoDone = true;
  } else {
    const since = now - 26 * 60 * 60 * 1000;
    const [a, b] = await Promise.all([
      db.collection('users').where('updatedAt', '>=', new Date(since)).get(),
      db.collection('users').where('locationUpdatedAtMillis', '>=', since).get(),
    ]);
    const byId = new Map([...a.docs, ...b.docs].map((d) => [d.id, d]));
    out.donorGeoFixed = await fixDonorGeo(db, FieldValue, [...byId.values()]);
  }
  // খোলা রিকোয়েস্ট (geoOpen না থাকলে বসানো)
  const open = await db.collection('requests').where('status', '==', 'open').get();
  const opened = open.docs.filter((d) => !d.get('geoOpen') && isActive(d.data(), now) && hash(d.get('lat'), d.get('lng')));
  for (let i = 0; i < opened.length; i += 400) {
    const b = db.batch();
    opened.slice(i, i + 400).forEach((d) => b.update(d.ref, { geoOpen: hash(d.get('lat'), d.get('lng')) }));
    await b.commit();
  }
  out.requestsOpened = opened.length;
  // geoOpen আছে কিন্তু আর খোলা নেই (পুরোনো অ্যাপ নিজে বন্ধ করলে) — সরানো
  const tagged = await db.collection('requests').orderBy('geoOpen').limit(2000).get();
  const stale = tagged.docs.filter((d) => d.get('status') !== 'open' || !isActive(d.data(), now));
  for (let i = 0; i < stale.length; i += 400) {
    const b = db.batch();
    stale.slice(i, i + 400).forEach((d) => b.update(d.ref, { geoOpen: FieldValue.delete() }));
    await b.commit();
  }
  out.requestsCleared = stale.length;
  return out;
}

module.exports = { hash, near, nearDonors, donorKey, donorKeyOf, donorGeoReady, fixDonorGeo, distanceKm, maintain };
