#!/usr/bin/env node
// web-vue → desktop/dist (Tauri-র frontendDist): ওয়েবের ডেস্কটপ বিল্ড dist/w-এ, আর dist/index.html (পাতা /w/assets/… খোঁজে,
// তাই ফাইলগুলো dist/w-এর নিচেই থাকে; Tauri না পাওয়া ঠিকানায় index.html দেয় — /services, /work … চলে)।
//
//   node desktop/scripts/prepare-web.mjs          (web-vue/node_modules থাকতে হবে: cd web-vue && npm ci)
// পরিবেশ: VITE_SENTRY_DSN, VITE_SENTRY_RELEASE (থাকলে) — ওয়েবের মতোই।
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = join(here, '..');
const webvue = join(desktop, '..', 'web-vue');
const dist = join(desktop, 'dist');

const vite = join(webvue, 'node_modules', 'vite', 'bin', 'vite.js');
if (!existsSync(vite)) {
  console.error('web-vue/node_modules নেই — আগে: cd web-vue && npm ci');
  process.exit(1);
}
mkdirSync(dist, { recursive: true });
execFileSync(process.execPath, [vite, 'build', '-c', 'vite.desktop.config.ts'], { cwd: webvue, stdio: 'inherit' });

const index = join(dist, 'w', 'index.html');
const html = readFileSync(index, 'utf8');
if (!html.includes('/w/assets/') || html.includes('/src/main.ts')) {
  console.error('desktop build: index.html looks wrong');
  process.exit(1);
}
copyFileSync(index, join(dist, 'index.html'));

// মাপ (ইনস্টলার ~১০ MB লক্ষ্য — ওয়েবের অংশ কত বড়)
let total = 0;
const walk = (d) => {
  for (const name of readdirSync(d)) {
    const p = join(d, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p);
    else total += st.size;
  }
};
walk(dist);
writeFileSync(join(dist, '.size'), String(total));
console.log(`desktop/dist ready: ${(total / 1048576).toFixed(1)} MB`);
