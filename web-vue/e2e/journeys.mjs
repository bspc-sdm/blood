// পুরো যাত্রা (e2e/web/journeys, বদল ছাড়া) Vue ওয়েব আর Vue অ্যাডমিনের ওপর — আসল সার্ভারের কোড (e2e/server/local.js),
// মেমোরির ডাটাবেস, Firebase Auth emulator, পরীক্ষার ঘড়ি (ঢাকা ১১:০০)।
//
//   node e2e/journeys.mjs [যাত্রার নম্বর/নামের অংশ ...]
//   E2E_DIR=…/e2e      — React-এর e2e ফোল্ডার (আগে থেকে ../e2e); তার node_modules (firebase-tools, playwright-core) লাগে
//   WEB_BUILD=…/w      — কোন ওয়েব বিল্ড (আগে থেকে এখানে বানানো Vue বিল্ড: e2e/.build/w); React-এর সাথে মেলাতে React-এর বিল্ড দিন
//   ADMIN=vue|orig     — /admin কোনটা (আগে থেকে vue: dist-admin; orig = server/public/admin)
//   E2E_PORT=4600      — লোকাল সার্ভারের পোর্ট
// ফল: <E2E_DIR>/web/shots (manifest.json, summary.json, issues.json) — e2e/web/run.mjs-এর মতোই
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const WEBVUE = path.join(here, '..');
const E2E = path.resolve(process.env.E2E_DIR || path.join(WEBVUE, '../e2e'));
const ROOT = path.join(E2E, '..');
const ADMIN = process.env.ADMIN || 'vue';
const ADMIN_DIR = path.resolve(process.env.ADMIN_DIR || path.join(WEBVUE, 'dist-admin'));
const filters = process.argv.slice(2);

const { BASE, PORT, clockOffset } = await import(pathToFileURL(path.join(E2E, 'web/stack.mjs')).href);
const lib = await import(pathToFileURL(path.join(E2E, 'web/lib.mjs')).href);
const { Journey, launch, close, manifest, issues, writeManifest, SHOTS } = lib;

// ---- বিল্ড ----
function buildVue() {
  const out = path.join(here, '.build/w');
  if (!process.env.E2E_NOBUILD) {
    const r = spawnSync('npx', ['vite', 'build', '--outDir', out, '--emptyOutDir'], { cwd: WEBVUE, stdio: 'inherit', env: { ...process.env, VITE_AUTH_EMULATOR: 'origin' } });
    if (r.status !== 0) throw new Error('web-vue build failed');
    if (ADMIN === 'vue') {
      const a = spawnSync('npx', ['vite', 'build', '-c', 'vite.admin.config.ts'], { cwd: WEBVUE, stdio: 'inherit' });
      if (a.status !== 0) throw new Error('admin build failed');
    }
  }
  return out;
}
const WEB_BUILD = path.resolve(process.env.WEB_BUILD || buildVue());

// ---- স্ট্যাক: emulator + লোকাল সার্ভার (e2e/web/stack.mjs-এর মতো, শুধু ওয়েব বিল্ড আমাদের) ----
async function waitFor(url, ms = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    try {
      if ((await fetch(url)).status < 500) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`not ready: ${url}`);
}
const procs = [];
fs.mkdirSync(path.join(here, '.build'), { recursive: true });
const log = fs.openSync(path.join(here, '.build/stack.log'), 'a');
let running = false;
try {
  running = (await fetch(`${BASE}/__e2e/log`)).ok;
} catch {}
if (!running) {
  const off = clockOffset();
  fs.mkdirSync(path.join(E2E, '.build'), { recursive: true });
  fs.writeFileSync(path.join(E2E, '.build/clock.json'), JSON.stringify({ offset: off }));
  const clockEnv = { E2E_TIME_OFFSET_MS: String(off), NODE_OPTIONS: `--require ${path.join(E2E, 'server/clock.js')}` };
  let emuUp = false;
  try {
    emuUp = (await fetch('http://127.0.0.1:9099/')).ok;
  } catch {}
  if (!emuUp) {
    procs.push(spawn(path.join(E2E, 'node_modules/.bin/firebase'), ['emulators:start', '--only', 'auth', '--project', 'blood-3eb28'], { cwd: E2E, stdio: ['ignore', log, log], detached: true, env: { ...process.env, ...clockEnv } }));
    await waitFor('http://127.0.0.1:9099/');
  } else {
    await fetch('http://127.0.0.1:9099/emulator/v1/projects/blood-3eb28/accounts', { method: 'DELETE' });
  }
  procs.push(spawn(process.execPath, [path.join(E2E, 'server/local.js')], {
    stdio: ['ignore', log, log], detached: true,
    env: { ...process.env, ...clockEnv, PORT: String(PORT), BUILD_DIR: WEB_BUILD, FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099' },
  }));
  await waitFor(`${BASE}/__e2e/log`);
}
const stop = () => {
  for (const p of procs.reverse()) {
    try {
      process.kill(-p.pid, 'SIGTERM');
    } catch {}
  }
};

// ---- ব্রাউজার: /admin → Vue অ্যাডমিন (সার্ভারের মতো কড়া CSP সহ) ----
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
const browser = await launch();
if (ADMIN === 'vue') {
  const newContext = browser.newContext.bind(browser);
  browser.newContext = async (...a) => {
    const ctx = await newContext(...a);
    await ctx.route((u) => {
      const p = new URL(u).pathname;
      return p === '/admin' || p.startsWith('/admin/');
    }, (route) => {
      const rel = new URL(route.request().url()).pathname.replace(/^\/admin\/?/, '');
      let file = path.join(ADMIN_DIR, rel || 'index.html');
      if (!file.startsWith(ADMIN_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ADMIN_DIR, 'index.html');
      return route.fulfill({ status: 200, body: fs.readFileSync(file), headers: { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'content-security-policy': CSP, 'cache-control': 'no-cache' } });
    });
    return ctx;
  };
}

// ---- যাত্রা (e2e/web/run.mjs-এর মতো) ----
const files = fs.readdirSync(path.join(E2E, 'web/journeys')).filter((f) => /^\d+.*\.mjs$/.test(f)).sort();
const chosen = files.filter((f) => !filters.length || filters.some((x) => f.startsWith(x.padStart(2, '0')) || f.includes(x)));
fs.mkdirSync(SHOTS, { recursive: true });
const summary = [];
for (const f of chosen) {
  const mod = await import(pathToFileURL(path.join(E2E, 'web/journeys', f)).href);
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
writeManifest();
fs.writeFileSync(path.join(SHOTS, 'summary.json'), JSON.stringify(summary, null, 1));
fs.writeFileSync(path.join(SHOTS, 'issues.json'), JSON.stringify(issues, null, 1));
console.log(`issues: ${issues.length}`);
stop();
process.exit(summary.some((s) => s.failed) ? 1 : 0);
