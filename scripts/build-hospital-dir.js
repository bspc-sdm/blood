// dghs-raw.json (scripts/fetch-dghs.mjs-এর ফল) → server/lib/hospital-dir.json
// চালানো: node scripts/build-hospital-dir.js path/to/dghs-raw.json
const fs = require('node:fs');
const path = require('node:path');
const D = require('../server/lib/dghs');

const raw = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const out = {};
let n = 0;
for (const r of raw) {
  // [Id, Name, NameBn, Code, Email, Agency, Type, Division, District, CityCorp, Upazila, Paurasava, Union, Private]
  const h = D.toHospital([r[0], r[1], r[2], r[6], r[8], r[10], r[9], r[11], r[12], r[13]]);
  if (!h) continue;
  const d = h.data;
  (out[d.district] = out[d.district] || []).push([d.dghsId, d.nameEn, d.nameBn, d.type, d.upazilaId, null]);
  n++;
}
for (const k of Object.keys(out)) out[k].sort((a, b) => a[1].localeCompare(b[1]));
const file = path.join(__dirname, '../server/lib/hospital-dir.json');
fs.writeFileSync(file, JSON.stringify({ source: 'DGHS Facility Registry (hris.mohfw.gov.bd)', fetched: new Date().toISOString().slice(0, 10), districts: out }));
console.log('hospitals', n, 'districts', Object.keys(out).length, 'bytes', fs.statSync(file).size);
console.log(Object.entries(out).map(([k, v]) => [k, v.length]).sort((a, b) => b[1] - a[1]).slice(0, 6));
