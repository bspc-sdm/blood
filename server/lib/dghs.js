// সরকারি স্বাস্থ্য অধিদপ্তরের (DGHS) Facility Registry-র সারি → আমাদের হাসপাতাল।
// উৎস: hris.mohfw.gov.bd/public/facility-registry (সরকারি প্রকাশিত তালিকা)। শুধু একবার শুরুতে তালিকা ভরার জন্য।
const DISTRICTS = require('./districts');
const { normalizeName, UPAZILA } = require('./hospital-match');

const letters = (s) => String(s || '').toLowerCase().replace(/[^a-z]/g, '');

// DGHS-এর জেলার বানান → আমাদের তালিকার ইংরেজি বানান
const DISTRICT_ALIAS = {
  barishal: 'barisal',
  coxsbazar: 'coxsbazar',
  cumilla: 'comilla',
  jhalokathi: 'jhalakathi',
  khagrachari: 'khagrachhari',
  maulvibazar: 'moulvibazar',
  netrakona: 'netrokona',
};
// উপজেলার নামের ভেতরে জেলার নতুন বানান থাকলে পুরোনোতে
const SPELLING = [
  [/cumilla/g, 'comilla'], [/chattogram|chittogram/g, 'chittagong'], [/barishal/g, 'barisal'],
  [/jashore/g, 'jessore'], [/bogura/g, 'bogra'], [/netrokona|netrakona/g, 'netrokona'],
];

function mapDistrict(name) {
  const k = letters(name);
  const want = DISTRICT_ALIAS[k] || k;
  const d = DISTRICTS.find((x) => letters(x.en) === want);
  return d ? d.bn : null;
}

function upazilaKey(s) {
  let k = String(s || '').toLowerCase();
  for (const [re, to] of SPELLING) k = k.replace(re, to);
  return k.replace(/[^a-z]/g, '').replace(/sadar$/, '').replace(/^sadar/, '');
}

function editDistance(a, b) {
  const m = a.length;
  const n = b.length;
  let prev = Array.from({ length: n + 1 }, (_, j) => j);
  for (let i = 1; i <= m; i++) {
    const cur = [i];
    for (let j = 1; j <= n; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[n];
}

// বানান বা নাম বদলের কারণে আপনা থেকে না মেলা উপজেলা (DGHS নাম → আমাদের তালিকার id)
const UPAZILA_ALIAS = {
  saidpur: 422, // Syedpur
  phultala: 207, // Fultola
  dacope: 213, // Dakop
  singair: 364, // Singiar
  roypura: 315, // Raipura
  matlabdakshin: 56, // Matlab South
  matlabuttar: 58, // Matlab North
  cumillaadarshasadar: 11, // Comilla Sadar
  cumillasadardakshin: 14, // Sadarsouth
  lakshmichari: 91, // Laxmichhari
  senbagh: 49, // Senbug
  fulchhari: 434, // Phulchari
  rajibpur: 456, // Charrajibpur
  raumari: 455, // Rowmari
  royganj: 108, // Raigonj
  indurkani: 245, // আগের Zianagar
  shantiganj: 301, // আগের South Sunamganj
  sulla: 309, // Shalla
};

/** জেলার ভেতরে সবচেয়ে কাছের নামের উপজেলা (না মিললে null) */
function mapUpazila(districtBn, name) {
  const alias = UPAZILA_ALIAS[letters(name)];
  if (alias && UPAZILA.get(alias)?.district === districtBn) return alias;
  const k = upazilaKey(name);
  if (!k) return null;
  let best = null;
  let bestScore = 0;
  for (const u of UPAZILA.values()) {
    if (u.district !== districtBn) continue;
    const c = upazilaKey(u.en);
    if (!c) continue;
    const score = 1 - editDistance(k, c) / Math.max(k.length, c.length);
    if (score > bestScore) {
      bestScore = score;
      best = u;
    }
  }
  return bestScore >= 0.75 ? best.id : null;
}

function mapType(t, isPrivate) {
  if (isPrivate) return 'private_hospital';
  if (/Medical College Hospital|Medical University Hospital/.test(t)) return 'govt_mch';
  if (/Postgraduate|Specialized|Special Purpose|Infectious|Chest|Trauma|Dental/.test(t)) return 'govt_specialized';
  if (/District\/General Hospital/.test(t)) return 'govt_district';
  if (/Upazila Health Complex/.test(t)) return 'govt_uhc';
  return 'govt_hospital';
}

/** বাংলা নাম থেকে ঠিকানার অংশ (প্রথম কমার পরে) বাদ */
function cleanBn(s) {
  return String(s || '').split(/[,،]/)[0].replace(/[।|]+$/g, '').replace(/\s+/g, ' ').trim().slice(0, 120);
}

/**
 * row: [ID, Name, NameBn, Type, District, Upazila, CityCorp, Paurasava, Union, Private]
 * ফেরত: { id, data } অথবা null (জেলা না মিললে)
 */
function toHospital(row) {
  const [id, name, nameBn, type, district, upazila, cityCorp, paurasava, union, priv] = row.map((x) => String(x ?? '').trim());
  const districtBn = mapDistrict(district);
  if (!districtBn || !/^\d+$/.test(id) || !name) return null;
  const upazilaId = upazila ? mapUpazila(districtBn, upazila) : null;
  return {
    id: `dghs_${id}`,
    data: {
      nameEn: name.replace(/\s+/g, ' ').slice(0, 120),
      nameBn: cleanBn(nameBn),
      type: mapType(type, priv === '1'),
      district: districtBn,
      upazilaId,
      unionId: null,
      address: [upazila, union, paurasava, cityCorp].filter(Boolean).join(', ').slice(0, 200),
      phone: '',
      normKey: normalizeName(name),
      source: 'dghs',
      dghsId: Number(id),
      status: 'approved',
      createdAt: Date.now(),
    },
    unmatchedUpazila: upazila && !upazilaId ? `${district}|${upazila}` : null,
  };
}

module.exports = { toHospital, mapDistrict, mapUpazila, mapType, cleanBn };
