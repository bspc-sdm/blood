// উপজেলার কেন্দ্র (অক্ষাংশ, দ্রাঘিমাংশ) — ফোনের/ম্যাপের জায়গা থেকে উপজেলা আপনা থেকে বের করতে (২.৩১.০, অ্যাম্বুলেন্স ডাকা)।
// উৎস: OCHA/HDX COD-AB Bangladesh ADM3 (center_lat/center_lon), npm-এর bd-offline-geocoder 0.1.1-এর
// data/boundaries/bgd_admin3.geojson হয়ে (জেলার সীমানাও একই উৎস থেকে: app/src/main/assets/district_bounds.json)।
// চালানো: npm pack bd-offline-geocoder@0.1.1 && tar xzf … → node scripts/build-upazila-centers.js <bgd_admin3.geojson>
// ফল: server/lib/upazila-centers.json, app/src/main/assets/upazila_centers.json, web/public/data/upazila_centers.json (একই ফাইল)।
// নাম মেলানো server/lib/dghs.js-এর নিয়মে (জেলা › উপজেলা); না মিললে সেই উপজেলার কেন্দ্র নেই — তখন বেছে নেওয়া উপজেলাই থাকে।
const fs = require('fs');
const path = require('path');
const { mapDistrict, mapUpazila } = require('../server/lib/dghs');

const src = process.argv[2];
if (!src) {
  console.error('usage: node scripts/build-upazila-centers.js <bgd_admin3.geojson>');
  process.exit(1);
}
// ADM3-এর বানান যেগুলো dghs.js-এর নিয়মে মেলে না
const DISTRICT_FIX = { Chapainababganj: 'Chapainawabganj', Jhalokati: 'Jhalakathi' };
const UPAZILA_FIX = {
  'Chandpur/Matlab Dakkhin': 56, 'Cumilla/Adarsha Sadar': 11, 'Cumilla/Sadar Dakkhin': 14, 'Khulna/Paikgachha': 206,
  'Chapainababganj/Bholahat': 158, 'Chapainababganj/Chapainawabganj Sadar': 155, 'Chapainababganj/Gomastapur': 156,
  'Chapainababganj/Nachole': 157, 'Chapainababganj/Shibganj': 159, 'Sirajganj/Rayganj': 108, 'Barishal/Ujirpur': 252,
  'Jhalokati/Jhalokathi Sadar': 230, 'Jhalokati/Kanthalia': 231, 'Jhalokati/Nalchhity': 232, 'Jhalokati/Rajapur': 233,
  'Mymensingh/Fulpur': 468,
};
const geo = JSON.parse(fs.readFileSync(src, 'utf8'));
const c = {};
// সিটি কর্পোরেশন: ADM3-এ পুরো শহর এক ঘর, আমাদের তালিকায় আলাদা থানা — শহরের কেন্দ্র সবচেয়ে কাছে হলে উপজেলা আপনা থেকে নয়
const cc = {};
const miss = [];
for (const f of geo.features) {
  const p = f.properties;
  const d = mapDistrict(DISTRICT_FIX[p.adm2_name] || p.adm2_name);
  if (d && /City Corporation/i.test(p.adm3_name) && p.center_lat != null) {
    (cc[d] = cc[d] || []).push([Math.round(p.center_lat * 1e4) / 1e4, Math.round(p.center_lon * 1e4) / 1e4]);
    continue;
  }
  const id = UPAZILA_FIX[`${p.adm2_name}/${p.adm3_name}`] || (d ? mapUpazila(d, p.adm3_name) : null);
  if (!id || p.center_lat == null) {
    miss.push(`${p.adm2_name}/${p.adm3_name}`);
    continue;
  }
  if (c[id]) continue;
  c[id] = [Math.round(p.center_lat * 1e4) / 1e4, Math.round(p.center_lon * 1e4) / 1e4];
}
const out = JSON.stringify({ src: 'OCHA/HDX COD-AB Bangladesh ADM3 centres (via bd-offline-geocoder 0.1.1)', c, cc });
const root = path.join(__dirname, '..');
for (const f of ['server/lib/upazila-centers.json', 'app/src/main/assets/upazila_centers.json', 'web/public/data/upazila_centers.json']) {
  fs.writeFileSync(path.join(root, f), out + '\n');
}
console.log(`centres: ${Object.keys(c).length}, city: ${Object.keys(cc).length}, unmatched: ${miss.length}`);
if (miss.length) console.log(miss.join(', '));
