// সরকারি স্বাস্থ্য অধিদপ্তরের (DGHS) Facility Registry-র সারি → আমাদের হাসপাতাল।
// উৎস: hris.mohfw.gov.bd/public/facility-registry (সরকারি প্রকাশিত তালিকা)। শুধু একবার শুরুতে তালিকা ভরার জন্য।
const DISTRICTS = require('./districts');
const { normalizeName, UPAZILA } = require('./hospital-match');
const { clip } = require('./text');

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
  // "Kotwali (Dhaka)" → "kotwali": বন্ধনীর অংশ শুধু আলাদা করে চেনার জন্য
  let k = String(s || '').toLowerCase().replace(/\([^)]*\)/g, '');
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
  monoharganj: 13, // Monohargonj
};

// সিটি কর্পোরেশনের থানা: DGHS-এর নাম → geo.json-এর ইংরেজি নাম
const THANA_ALIAS = {
  chittogramport: 'Bandar (Chattogram Port)',
  chattogramport: 'Bandar (Chattogram Port)',
  uttarapaschim: 'Uttara West',
  uttarapurba: 'Uttara East',
  mugdapara: 'Mugda',
  tejgaonindarea: 'Tejgaon Industrial Area',
  shahjahanpur: 'Shajahanpur',
};

/** জেলার ভেতরে সবচেয়ে কাছের নামের উপজেলা (না মিললে null) */
function mapUpazila(districtBn, name) {
  const alias = UPAZILA_ALIAS[letters(name)];
  if (alias && UPAZILA.get(alias)?.district === districtBn) return alias;
  const thana = THANA_ALIAS[letters(name)];
  if (thana) {
    for (const u of UPAZILA.values()) if (u.district === districtBn && u.en === thana) return u.id;
  }
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

function mapType(t, isPrivate, name = '') {
  if (/Diagnostic/i.test(t)) return 'diagnostic';
  if (isPrivate || /NGO/.test(t)) return /clinic/i.test(name) ? 'clinic' : 'private_hospital';
  if (/Medical College Hospital|Medical University Hospital/.test(t)) return 'govt_mch';
  if (/Postgraduate|Specialized|Special Purpose|Infectious|Chest|Trauma|Dental/.test(t)) return 'govt_specialized';
  if (/District\/General Hospital/.test(t)) return 'govt_district';
  if (/Upazila Health Complex/.test(t)) return 'govt_uhc';
  return 'govt_hospital';
}

/** সব বড় হাতের নাম ("POPULAR DIAGNOSTIC CENTRE LTD") → "Popular Diagnostic Centre Ltd" */
function titleIfCaps(s) {
  if (/[a-z]/.test(s) || !/[A-Z]/.test(s)) return s;
  return s.toLowerCase().replace(/\b([a-z])/g, (c) => c.toUpperCase());
}

/** বাংলা নাম থেকে ঠিকানার অংশ (প্রথম কমার পরে) বাদ */
function cleanBn(s) {
  return clip(String(s || '').split(/[,،]/)[0].replace(/[।|]+$/g, '').replace(/\s+/g, ' ').trim(), 120);
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
      nameEn: clip(titleIfCaps(name.replace(/\s+/g, ' ')), 120),
      nameBn: cleanBn(nameBn),
      type: mapType(type, priv === '1', name),
      district: districtBn,
      upazilaId,
      unionId: null,
      address: clip([upazila, union, paurasava, cityCorp].filter(Boolean).join(', '), 200),
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
