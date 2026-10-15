// হাসপাতালের নাম মেলানো (ডুপ্লিকেট ধরা) আর এলাকার তালিকা — ডাটাবেস ছাড়া, তাই টেস্ট করা যায়।
// এলাকার তথ্য (geo.json): github.com/nuhil/bangladesh-geocode (MIT License, Copyright (c) 2014 Nuhil Mehdy)
const GEO = require('./geo.json');

const UPAZILA = new Map(GEO.u.map(([id, district, bn, en]) => [id, { id, district, bn, en }]));
const UNION = new Map(GEO.n.map(([id, upazilaId, bn, en]) => [id, { id, upazilaId, bn, en }]));

// তুলনার সময় বাদ দেওয়া সাধারণ শব্দ
const STOP = new Set([
  'hospital', 'hospitals', 'clinic', 'clinics', 'ltd', 'limited', 'pvt', 'private', 'the', 'and', 'of',
  'center', 'centre', 'bd', 'bangladesh', 'general', 'and', 'co',
]);

/** নাম তুলনার রূপ: ছোট হাতের অক্ষর, চিহ্ন আর সাধারণ শব্দ বাদ */
function normalizeName(name) {
  return String(name || '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .split(' ')
    .filter((w) => w && !STOP.has(w))
    .join(' ');
}

function levenshtein(a, b) {
  if (a === b) return 0;
  const m = a.length;
  const n = b.length;
  if (!m) return n;
  if (!n) return m;
  let prev = Array.from({ length: n + 1 }, (_, j) => j);
  for (let i = 1; i <= m; i++) {
    const cur = [i];
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[n];
}

/** দুটো নাম প্রায় একই কি না (০–১) */
function similarity(a, b) {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (!x || !y) return 0;
  if (x === y) return 1;
  const lev = 1 - levenshtein(x, y) / Math.max(x.length, y.length);
  const tx = new Set(x.split(' '));
  const ty = new Set(y.split(' '));
  const inter = [...tx].filter((t) => ty.has(t)).length;
  const jac = inter / new Set([...tx, ...ty]).size;
  return Math.max(lev, jac);
}

const DUP_THRESHOLD = 0.85;

/** একই জায়গায় প্রায় একই নাম (উপজেলা না জানা থাকলে জেলা ধরে) */
function isDuplicate(a, b) {
  if (a.district !== b.district) return false;
  if (a.upazilaId && b.upazilaId && a.upazilaId !== b.upazilaId) return false;
  return similarity(a.nameEn, b.nameEn) >= DUP_THRESHOLD;
}

module.exports = { normalizeName, similarity, isDuplicate, UPAZILA, UNION, DUP_THRESHOLD };
