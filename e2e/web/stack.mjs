// লোকাল স্ট্যাক চালু করা: Firebase Auth emulator + ওয়েব বিল্ড (emulator-মুখী, e2e/.build/w) + লোকাল সার্ভার (e2e/server/local.js)।
// সরাসরি চালালে (node e2e/web/stack.mjs) চালু থাকে, Ctrl+C-তে বন্ধ। run.mjs এটা import করে।
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const E2E = path.join(here, '..');
export const ROOT = path.join(E2E, '..');
export const PORT = Number(process.env.E2E_PORT || 4600);
export const BASE = `http://localhost:${PORT}`;
const EMU_PORT = 9099;

/**
 * পরীক্ষার ঘড়ি: E2E_CLOCK (ঢাকার সময় HH:MM, আগে থেকে 11:00) — আজকের সেই সময়ে সব চলে (সার্ভার, emulator, ব্রাউজার)।
 * E2E_CLOCK=real দিলে আসল সময়। একবার হিসাব করে env-এ রাখা, যাতে সব প্রক্রিয়ায় একই।
 */
export function clockOffset() {
  if (process.env.E2E_TIME_OFFSET_MS) return Number(process.env.E2E_TIME_OFFSET_MS);
  const want = process.env.E2E_CLOCK || '11:00';
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

export function buildWeb({ force = false } = {}) {
  const out = path.join(E2E, '.build/w');
  // web/src বিল্ডের পরে বদলালে আবার বানানো
  const newest = (d) => fs.readdirSync(d, { withFileTypes: true }).reduce((m, e) => Math.max(m, e.isDirectory() ? newest(path.join(d, e.name)) : fs.statSync(path.join(d, e.name)).mtimeMs), 0);
  const idx = path.join(out, 'index.html');
  const fresh = fs.existsSync(idx) && fs.statSync(idx).mtimeMs > newest(path.join(ROOT, 'web/src'));
  if (!force && fresh && !process.env.E2E_REBUILD) return out;
  const r = spawnSync('npx', ['vite', 'build', '--outDir', out, '--emptyOutDir'], {
    cwd: path.join(ROOT, 'web'), stdio: 'inherit', env: { ...process.env, VITE_AUTH_EMULATOR: 'origin' },
  });
  if (r.status !== 0) throw new Error('web build failed');
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
    const emu = spawn(path.join(E2E, 'node_modules/.bin/firebase'), ['emulators:start', '--only', 'auth', '--project', 'blood-3eb28'], { cwd: E2E, stdio: ['ignore', out, out], detached: true, env: { ...process.env, ...clockEnv } });
    procs.push(emu);
    await waitFor(`http://127.0.0.1:${EMU_PORT}/`);
  } else {
    // আগের চালানোর ব্যবহারকারী মুছে নতুন করে শুরু (ঘড়ি মেলাতে আলাদা করে চালানো emulator-এও একই E2E_TIME_OFFSET_MS লাগবে)
    await fetch(`http://127.0.0.1:${EMU_PORT}/emulator/v1/projects/blood-3eb28/accounts`, { method: 'DELETE' });
  }
  const build = buildWeb();
  const srv = spawn(process.execPath, [path.join(E2E, 'server/local.js')], {
    stdio: ['ignore', out, out], detached: true,
    env: { ...process.env, ...clockEnv, PORT: String(PORT), BUILD_DIR: build, FIREBASE_AUTH_EMULATOR_HOST: `127.0.0.1:${EMU_PORT}`, E2E_VERBOSE: '1' },
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
  console.log(`stack ready: ${BASE}/  (admin: ${BASE}/admin)  E2E_TIME_OFFSET_MS=${process.env.E2E_TIME_OFFSET_MS}`);
  const bye = () => { s.stop(); process.exit(0); };
  process.on('SIGINT', bye);
  process.on('SIGTERM', bye);
}
