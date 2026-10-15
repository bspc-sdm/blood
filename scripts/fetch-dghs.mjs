// DGHS Facility Registry (hris.mohfw.gov.bd) থেকে হাসপাতাল/ক্লিনিক/ডায়াগনস্টিকের সারি নামানো।
// GitHub Actions-এ চলে (workflow: fetch-dghs), ফল dghs-raw.json হিসেবে artifact-এ যায়।
import { writeFileSync } from 'node:fs';

const BASE = 'https://hris.mohfw.gov.bd/public/facility-registry/reports/organization-list';
// সরকারি (আগে যেগুলো নেওয়া হয়েছিল) + বেসরকারি হাসপাতাল/ক্লিনিক, ডায়াগনস্টিক, এনজিও, অন্যান্য হাসপাতাল, সিএমএইচ
const TYPES = [27, 26, 73, 74, 75, 25, 24, 34, 41, 7, 72, 28, 94, 33, 5, 3, 2, 20, 19, 36, 29, 21, 61, 97, 43, 45, 44];

const decode = (s) => s
  .replace(/<[^>]*>/g, '')
  .replace(/&amp;/g, '&').replace(/&#0?39;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ')
  .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
  .replace(/\s+/g, ' ').trim();

async function get(url, tries = 4) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'GetBlood-directory/1.0 (non-commercial blood donation app)' } });
      if (r.ok) return await r.text();
      console.error('HTTP', r.status, url);
    } catch (e) {
      console.error('fetch failed', url, e.message);
    }
    await new Promise((res) => setTimeout(res, 2000 * (i + 1)));
  }
  throw new Error('failed ' + url);
}

function parse(html) {
  const table = html.match(/<table[\s\S]*?<\/table>/i);
  if (!table) return [];
  const rows = [];
  for (const tr of table[0].match(/<tr[\s\S]*?<\/tr>/gi) || []) {
    const cells = (tr.match(/<td[\s\S]*?<\/td>/gi) || []).map(decode);
    if (cells.length >= 14 && /^\d+$/.test(cells[0])) rows.push(cells.slice(0, 14));
  }
  return rows;
}

const all = new Map();
for (const t of TYPES) {
  const first = await get(`${BASE}?facility_type_id[]=${t}`);
  const total = Number((first.match(/Total (\d+) items/) || [])[1] || 0);
  const pages = Math.ceil(total / 50);
  parse(first).forEach((r) => all.set(r[0], r));
  for (let p = 2; p <= pages; p += 4) {
    const batch = [];
    for (let q = p; q < p + 4 && q <= pages; q++) batch.push(get(`${BASE}?facility_type_id[]=${t}&page=${q}`).then(parse));
    (await Promise.all(batch)).flat().forEach((r) => all.set(r[0], r));
    await new Promise((res) => setTimeout(res, 300));
  }
  console.log(`type ${t}: total ${total}, collected so far ${all.size}`);
}
// [Id, Name, NameBn, Code, Email, Agency, Type, Division, District, CityCorp, Upazila, Paurasava, Union, Private]
writeFileSync('dghs-raw.json', JSON.stringify([...all.values()]));
console.log('rows', all.size);
