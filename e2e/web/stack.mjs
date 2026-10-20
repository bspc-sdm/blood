// লোকাল স্ট্যাক চালু করা: Firebase Auth emulator + ওয়েব বিল্ড (emulator-মুখী, e2e/.build/w) + অ্যাডমিন বিল্ড (e2e/.build/admin)
// + সার্ভার (Spring-এর সামনে server/spring-front.js, বা পুরোনো Node: e2e/server/local.js)।
// সরাসরি চালালে (node e2e/web/stack.mjs) চালু থাকে, Ctrl+C-তে বন্ধ। run.mjs এটা import করে।
// ওয়েব: E2E_WEB=vue (আগে থেকে — web-vue/, মানুষ যেটা ব্যবহার করেন: /w/ = Vue ওয়েব, /admin = Vue অ্যাডমিন) বা
// E2E_WEB=react (অবসরে যাওয়া web/ আর server/public/admin — শুধু পুরোনো তুলনার জন্য)।
// পোর্ট: E2E_PORT (সামনের সার্ভার, 4600), E2E_EMU_PORT (Auth emulator, 9099) — একই মেশিনে অন্য স্ট্যাক চললে বদলান।
// E2E_BACKEND=http://127.0.0.1:8080 দিলে সার্ভার = আগে থেকে চালু Spring (backend/), local.js-এর বদলে server/spring-front.js
// (E2E_BACKEND_LOG = Spring-এর লগ ফাইল, লগইন কোড পড়ার জন্য)। Spring-এর ঘড়ি সরানো যায় না, তাই তখন E2E_CLOCK না দিলে আসল সময়;
// CI (.github/workflows/backend-e2e.yml) পুরো মেশিনের ঘড়িই ১১:০০-তে নেয়।
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const E2E = path.join(here, '..');
export const ROOT = path.join(E2E, '..');
export const PORT = Number(process.env.E2E_PORT || 4600);
export const BASE = `http://localhost:${PORT}`;
export const EMU_PORT = Number(process.env.E2E_EMU_PORT || 9099);
export const WEB = (process.env.E2E_WEB || 'vue').toLowerCase() === 'react' ? 'react' : 'vue';

/**
 * পরীক্ষার ঘড়ি: E2E_CLOCK (ঢাকার সময় HH:MM, আগে থেকে 11:00) — আজকের সেই সময়ে সব চলে (সার্ভার, emulator, ব্রাউজার)।
 * E2E_CLOCK=real দিলে আসল সময়। একবার হিসাব করে env-এ রাখা, যাতে সব প্রক্রিয়ায় একই।
 */
export function clockOffset() {
  if (process.env.E2E_TIME_OFFSET_MS) return Number(process.env.E2E_TIME_OFFSET_MS);
  const want = process.env.E2E_CLOCK || (process.env.E2E_BACKEND ? 'real' : '11:00');
  if (want === 'real') return 0;
  const [h, m] = want.split(':').map(Number);
  const now = Date.now();
  const DHAKA = 6 * 3600 * 1000;
  const dayStart = Math.floor((now + DHAKA) / 86400000) * 86400000 - DHAKA; // ঢাকার আজকের শুরু (UTC ms)
  const off = dayStart + (h * 60 + m) * 60000 - now;
  process.env.E2E_TIME_OFFSET_MS = String(off);
  return off;
}

async function waitFor(url, ms = 60000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    try {
      const r = await fetch(url);
      if (r.status < 500) return true;
    } catch {}
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`not ready: ${url}`);
}

const newest = (d) => (fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).reduce((m, e) => (e.name === 'node_modules' || e.name.startsWith('dist') ? m : Math.max(m, e.isDirectory() ? newest(path.join(d, e.name)) : fs.statSync(path.join(d, e.name)).mtimeMs)), 0) : 0);
const fresh = (out, srcs) => {
  const idx = path.join(out, 'index.html');
  return fs.existsSync(idx) && srcs.every((d) => fs.statSync(idx).mtimeMs > newest(d));
};
function vite(cwd, args, env = {}) {
  const r = spawnSync('npx', ['vite', 'build', ...args], { cwd, stdio: 'inherit', env: { ...process.env, ...env } });
  if (r.status !== 0) throw new Error(`build failed: ${cwd} ${args.join(' ')}`);
}

/** অ্যাডমিন প্যানেলের ফোল্ডার: Vue (বিল্ড করা) বা পুরোনো server/public/admin */
export const ADMIN_DIR = WEB === 'vue' ? path.join(E2E, '.build/admin') : path.join(ROOT, 'server/public/admin');

/** ওয়েব (emulator-মুখী, VITE_AUTH_EMULATOR=origin) আর অ্যাডমিন বিল্ড — উৎস বদলালে আবার; E2E_REBUILD=1 সবসময় */
export function buildWeb({ force = false } = {}) {
  const out = path.join(E2E, '.build/w');
  fs.mkdirSync(path.join(E2E, '.build'), { recursive: true });
  const mark = path.join(E2E, '.build/web-kind');
  const sameKind = fs.existsSync(mark) && fs.readFileSync(mark, 'utf8') === WEB;
  const again = force || !!process.env.E2E_REBUILD || !sameKind;
  if (WEB === 'react') {
    if (again || !fresh(out, [path.join(ROOT, 'web/src')])) vite(path.join(ROOT, 'web'), ['--outDir', out, '--emptyOutDir'], { VITE_AUTH_EMULATOR: 'origin' });
  } else {
    const wv = path.join(ROOT, 'web-vue');
    if (again || !fresh(out, [path.join(wv, 'src'), path.join(wv, 'public')])) vite(wv, ['--outDir', out, '--emptyOutDir'], { VITE_AUTH_EMULATOR: 'origin' });
    if (again || !fresh(ADMIN_DIR, [path.join(wv, 'admin')])) vite(wv, ['-c', 'vite.admin.config.ts', '--outDir', ADMIN_DIR, '--emptyOutDir']);
  }
  fs.writeFileSync(mark, WEB);
  return out;
}

export async function startStack({ log = path.join(E2E, '.build/stack.log') } = {}) {
  fs.mkdirSync(path.dirname(log), { recursive: true });
  // আগে থেকে চালু থাকলে সেটাই (ঘড়িও সেটার)
  try {
    if ((await fetch(`${BASE}/__e2e/log`)).ok) {
      const cf = path.join(E2E, '.build/clock.json');
      if (fs.existsSync(cf)) process.env.E2E_TIME_OFFSET_MS = String(JSON.parse(fs.readFileSync(cf, 'utf8')).offset);
      return { stop() {} };
    }
  } catch {}
  const out = fs.openSync(log, 'a');
  const procs = [];
  const clockFile = path.join(E2E, '.build/clock.json');
  let emuUp0 = false;
  try { emuUp0 = (await fetch(`http://127.0.0.1:${EMU_PORT}/`)).ok; } catch {}
  // emulator আগে থেকে চালু থাকলে তার ঘড়ির সাথে মেলানো
  if (emuUp0 && !process.env.E2E_TIME_OFFSET_MS && fs.existsSync(clockFile)) process.env.E2E_TIME_OFFSET_MS = String(JSON.parse(fs.readFileSync(clockFile, 'utf8')).offset);
  const off = clockOffset();
  fs.writeFileSync(clockFile, JSON.stringify({ offset: off }));
  const clockEnv = { E2E_TIME_OFFSET_MS: String(off), NODE_OPTIONS: `--require ${path.join(E2E, 'server/clock.js')}` };
  let emuUp = false;
  try { emuUp = (await fetch(`http://127.0.0.1:${EMU_PORT}/`)).ok; } catch {}
  if (!emuUp) {
    const args = ['emulators:start', '--only', 'auth', '--project', 'blood-3eb28'];
    if (EMU_PORT !== 9099) {
      const cfg = path.join(E2E, '.build/firebase.json');
      const base = JSON.parse(fs.readFileSync(path.join(E2E, 'firebase.json'), 'utf8'));
      base.emulators.auth.port = EMU_PORT;
      fs.writeFileSync(cfg, JSON.stringify(base));
      args.push('--config', cfg);
    }
    const emu = spawn(path.join(E2E, 'node_modules/.bin/firebase'), args, { cwd: E2E, stdio: ['ignore', out, out], detached: true, env: { ...process.env, ...clockEnv } });
    procs.push(emu);
    await waitFor(`http://127.0.0.1:${EMU_PORT}/`);
  } else {
    // আগের চালানোর ব্যবহারকারী মুছে নতুন করে শুরু (ঘড়ি মেলাতে আলাদা করে চালানো emulator-এও একই E2E_TIME_OFFSET_MS লাগবে)
    await fetch(`http://127.0.0.1:${EMU_PORT}/emulator/v1/projects/blood-3eb28/accounts`, { method: 'DELETE' });
  }
  const build = buildWeb();
  const srv = spawn(process.execPath, [path.join(E2E, process.env.E2E_BACKEND ? 'server/spring-front.js' : 'server/local.js')], {
    stdio: ['ignore', out, out], detached: true,
    env: { ...process.env, ...clockEnv, PORT: String(PORT), BUILD_DIR: build, ADMIN_DIR, FIREBASE_AUTH_EMULATOR_HOST: `127.0.0.1:${EMU_PORT}`, E2E_VERBOSE: '1' },
  });
  procs.push(srv);
  await waitFor(`${BASE}/__e2e/log`);
  return {
    stop() {
      for (const p of procs.reverse()) {
        try { process.kill(-p.pid, 'SIGTERM'); } catch {}
      }
    },
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const s = await startStack();
  console.log(`stack ready (${WEB} web): ${BASE}/  (admin: ${BASE}/admin)  E2E_TIME_OFFSET_MS=${process.env.E2E_TIME_OFFSET_MS}`);
  const bye = () => { s.stop(); process.exit(0); };
  process.on('SIGINT', bye);
  process.on('SIGTERM', bye);
}
