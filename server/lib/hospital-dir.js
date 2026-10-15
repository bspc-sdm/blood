// স্বাস্থ্য অধিদপ্তরের (DGHS) Facility Registry থেকে নেওয়া হাসপাতাল/ক্লিনিক/ডায়াগনস্টিকের স্থির তালিকা।
// ১৭ হাজারের বেশি — Firestore-এ না রেখে ফাইলে, যাতে প্রতিবার পড়তে ডাটাবেসের দৈনিক সীমা খরচ না হয়।
// নতুন করে তৈরি: scripts/fetch-dghs.mjs (GitHub Actions) → scripts/build-hospital-dir.js
const DIR = require('./hospital-dir.json');

let byId = null;

function toObj(district, r) {
  return {
    id: `dghs_${r[0]}`,
    nameEn: r[1],
    nameBn: r[2] || '',
    type: r[3],
    district,
    upazilaId: r[4] || null,
    unionId: r[5] || null,
    address: '',
    phone: '',
  };
}

/** জেলার সব (DGHS) */
function inDistrict(district) {
  return (DIR.districts[district] || []).map((r) => toObj(district, r));
}

/** id ("dghs_123") দিয়ে খোঁজা */
function find(id) {
  if (!byId) {
    byId = new Map();
    for (const [d, rows] of Object.entries(DIR.districts)) rows.forEach((r) => byId.set(`dghs_${r[0]}`, [d, r]));
  }
  const x = byId.get(id);
  return x ? toObj(x[0], x[1]) : null;
}

module.exports = { inDistrict, find, fetched: DIR.fetched };
