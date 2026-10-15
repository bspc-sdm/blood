// ঔষধ প্রশাসন অধিদপ্তরের (DGDA) নিবন্ধিত ওষুধের তালিকা — DGHS-এর সরকারি OCL টার্মিনোলজি সার্ভার থেকে।
// ফল drugs-raw.json (GitHub Actions-এ চলে, "data-drugs" শাখায় রাখা হয়)।
import { writeFileSync } from 'node:fs';
const BASE = 'https://api.tr.ocl.dghs.gov.bd/orgs/MoHFW/sources/DGDA-Drugs/concepts/';
const LIMIT = 500;
async function get(url, tries = 5) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url, { headers: { Accept: 'application/json' } });
      if (r.ok) return { json: await r.json(), headers: r.headers };
      console.error('HTTP', r.status, url);
    } catch (e) {
      console.error('fail', url, e.message);
    }
    await new Promise((res) => setTimeout(res, 3000 * (i + 1)));
  }
  throw new Error('failed ' + url);
}
const first = await get(`${BASE}?limit=2&verbose=true&includeMappings=false`);
console.log('SAMPLE', JSON.stringify(first.json, null, 1).slice(0, 4000));
console.log('num_found', first.headers.get('num_found'), 'pages', first.headers.get('pages'));
const all = [];
for (let page = 1; ; page++) {
  const { json, headers } = await get(`${BASE}?limit=${LIMIT}&page=${page}&verbose=true`);
  if (!Array.isArray(json) || !json.length) break;
  for (const c of json) {
    all.push({ id: c.id, cls: c.concept_class, dt: c.datatype, name: c.display_name, names: (c.names || []).map((n) => [n.name, n.locale, n.name_type]), extras: c.extras || {}, retired: c.retired });
  }
  console.log('page', page, 'total', all.length, 'of', headers.get('num_found'));
  if (json.length < LIMIT) break;
  await new Promise((res) => setTimeout(res, 400));
}
writeFileSync('drugs-raw.json', JSON.stringify(all));
console.log('done', all.length);
