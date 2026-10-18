// সব যাত্রা চালানো: node e2e/web/run.mjs [যাত্রার নম্বর/নামের অংশ ...]
//   E2E_NOSTACK=1  — আগে থেকে চালু স্ট্যাক ব্যবহার (node e2e/web/stack.mjs)
//   E2E_RESEED=1   — শুরুতে API দিয়ে ডেমো নতুন করে বানানো (প্যানেলের মতোই ধাপে ধাপে admin_demo_seed)
// ফল: e2e/web/shots/<যাত্রা>/<ধাপ>.png, shots/manifest.json, shots/issues.json
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { startStack, buildWeb, BASE } from './stack.mjs';
import { Journey, launch, close, manifest, issues, writeManifest, SHOTS } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const filters = process.argv.slice(2);
const OWNER = 'ekapkbd@gmail.com';

export async function ownerToken() {
  const v = await (await fetch(`${BASE}/__e2e/custom-token?email=${OWNER}`)).json();
  const r = await fetch(`${BASE}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=e2e`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: v.token, returnSecureToken: true }) }).then((x) => x.json());
  return r.idToken;
}

export async function reseed() {
  const tok = await ownerToken();
  let step = 0;
  while (step !== null) {
    const r = await fetch(`${BASE}/api/community`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${tok}` }, body: JSON.stringify({ action: 'admin_demo_seed', step, lang: 'bn' }) }).then((x) => x.json());
    if (r.error) throw new Error(r.error);
    step = r.next;
  }
}

const stack = process.env.E2E_NOSTACK ? null : await startStack();
// চালু স্ট্যাকেও web/src বদলালে নতুন বিল্ড (সার্ভার প্রতিবার ডিস্ক থেকে পড়ে)
buildWeb();
const files = fs.readdirSync(path.join(here, 'journeys')).filter((f) => /^\d+.*\.mjs$/.test(f)).sort();
const chosen = files.filter((f) => !filters.length || filters.some((x) => f.startsWith(x.padStart(2, '0')) || f.includes(x)));
if (process.env.E2E_RESEED) await reseed();
fs.mkdirSync(SHOTS, { recursive: true });
// আগের manifest রেখে দেওয়া (একটা যাত্রা আবার চালালে বাকিগুলোর তথ্য থাকে)
const mfile = path.join(SHOTS, 'manifest.json');
const old = fs.existsSync(mfile) ? JSON.parse(fs.readFileSync(mfile, 'utf8')) : [];
await launch();
const summary = [];
for (const f of chosen) {
  const mod = await import(pathToFileURL(path.join(here, 'journeys', f)).href);
  const { no, slug, titleBn } = mod.meta;
  const J = new Journey(no, slug, titleBn);
  fs.rmSync(path.join(SHOTS, J.id), { recursive: true, force: true });
  fs.mkdirSync(path.join(SHOTS, J.id), { recursive: true });
  const t0 = Date.now();
  let failed = null;
  try {
    await mod.run(J);
  } catch (e) {
    failed = e;
    console.error(`!! ${J.id} stopped at step ${J.step + 1}:`, e.message.split('\n').slice(0, 3).join(' '));
    try {
      if (J.cur) await J.shot('যাত্রা এখানে থেমেছে (স্ক্রিপ্ট/অ্যাপের ভুল)', 'পরের ধাপে যাওয়া যায়নি', { status: 'bug', noteBn: `থেমেছে: ${e.message.split('\n')[0].slice(0, 200)}` });
    } catch {}
  }
  await J.end();
  const mine = manifest.filter((m) => m.journey === J.id);
  summary.push({ journey: J.id, steps: mine.length, ok: mine.filter((m) => m.status === 'ok').length, failed: failed ? failed.message.split('\n')[0] : null, s: Math.round((Date.now() - t0) / 1000) });
  console.log(`${J.id}: ${mine.length} ধাপ${failed ? ' (থেমেছে)' : ''} ${Math.round((Date.now() - t0) / 1000)}s`);
}
await close();
const ran = new Set(manifest.map((m) => m.journey));
const merged = [...old.filter((m) => !ran.has(m.journey) && fs.existsSync(path.join(SHOTS, m.file))), ...manifest].sort((a, b) => (a.journey < b.journey ? -1 : a.journey > b.journey ? 1 : a.step - b.step));
manifest.splice(0, manifest.length, ...merged);
writeManifest();
fs.writeFileSync(path.join(SHOTS, 'summary.json'), JSON.stringify(summary, null, 1));
console.log(`issues: ${issues.length}`);
stack?.stop();
