// অ্যাডমিন প্যানেল: পুরোনো (server/public/admin) বনাম Vue (dist-admin) — একই লোকাল সার্ভার, একই তথ্য, একই কড়া CSP।
// লোকাল স্ট্যাক আগে চালু থাকতে হবে (e2e/server/local.js + Auth emulator), মালিকের ইমেইলে লগইন (/__e2e/mail থেকে কোড)।
// চালান: BASE=http://localhost:4610 node test/admin-shots.mjs <outDir> [mobile,desktop]
//   outDir/orig/*.png আর outDir/vue/*.png; outDir/problems.json-এ পাতার ভুল আর CSP-তে আটকানো জিনিস
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE || 'http://localhost:4610';
const OWNER = process.env.OWNER_EMAIL || 'ekapkbd@gmail.com';
const VUE_DIR = path.resolve(process.env.VUE_DIR || path.join(here, '../dist-admin'));
const [outDir = path.join(here, 'admin-out'), sizesArg = 'mobile,desktop'] = process.argv.slice(2);
const SIZES = sizesArg.split(',');
const VP = { mobile: { width: 390, height: 844 }, desktop: { width: 1440, height: 960 } };
// vercel.json-এর /admin(.*) হেডার
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.txt': 'text/plain' };
const CHROME = process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

async function routes(ctx, variant) {
  await ctx.route((u) => new URL(u).pathname === '/admin' || new URL(u).pathname.startsWith('/admin/'), async (route) => {
    const p = new URL(route.request().url()).pathname;
    if (variant === 'vue') {
      const rel = p.replace(/^\/admin\/?/, '');
      let file = path.join(VUE_DIR, rel || 'index.html');
      if (!file.startsWith(VUE_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(VUE_DIR, 'index.html');
      return route.fulfill({ status: 200, body: fs.readFileSync(file), headers: { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'content-security-policy': CSP, 'cache-control': 'no-cache' } });
    }
    const resp = await route.fetch();
    return route.fulfill({ response: resp, headers: { ...resp.headers(), 'content-security-policy': CSP } });
  });
}

const browser = await chromium.launch({ executablePath: CHROME });
const problems = [];
const T0 = Date.now();

async function api(page, action, body = {}) {
  return page.evaluate(async ([a, b]) => {
    const r = await fetch('/api/community', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-Lifolo-Admin': '1' }, body: JSON.stringify({ lang: 'bn', action: a, panel: true, ...b }) });
    return r.json();
  }, [action, body]);
}

// ---- লগইন (একবার), কুকি পরে সব জায়গায় ----
const stateFile = path.join(outDir, 'state.json');
fs.mkdirSync(outDir, { recursive: true });
{
  const ctx = await browser.newContext({ viewport: VP.desktop, locale: 'bn-BD', ...(fs.existsSync(stateFile) ? { storageState: stateFile } : {}) });
  await routes(ctx, 'orig');
  const page = await ctx.newPage();
  await page.goto(`${BASE}/admin`);
  await page.waitForTimeout(1200);
  if (await page.locator('input[type=email]').count()) {
    await page.locator('input[type=email]').fill(OWNER);
    await page.getByRole('button', { name: 'কোড পাঠান' }).click();
    await page.locator('input[autocomplete=one-time-code]').waitFor();
    const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${OWNER}`)).json();
    await page.locator('input[autocomplete=one-time-code]').fill(code);
    await page.getByRole('button', { name: 'লগইন করুন' }).click();
    await page.locator('.shell').waitFor({ timeout: 20000 });
  }
  // ডেমো না থাকলে বানানো (পুরোনো প্যানেল দিয়ে, ধাপে ধাপে)
  const st = await api(page, 'admin_demo_status');
  if (!st.active || st.partial) {
    for (let step = 0, i = 0; step !== null && i < 40; i++) {
      const r = await api(page, 'admin_demo_seed', { step });
      if (r.error) throw new Error(r.error);
      step = r.next ?? null;
    }
  }
  await ctx.storageState({ path: stateFile });
  // পাতার তালিকার জন্য আইডি
  var ov = await api(page, 'admin_overview');
  var found = await api(page, 'admin_user_find', { q: 'demo' });
  var tests = await api(page, 'labtests_get');
  await ctx.close();
}

const first = (k) => (ov[k] && ov[k].items && ov[k].items[0]) || null;
const ph = first('pharmacies');
const bd = first('badges');
const tk = first('tickets');
const user = (found.items || [])[0];
const PAGES = [
  ['dashboard', ''],
  ['analytics', 'analytics'],
  ['emergency', 'emergency'],
  ['badges', 'badges'],
  ['badges-approved', 'badges?s=approved'],
  ['pharmacies', 'pharmacies'],
  ['pharmacies-approved', 'pharmacies?s=approved'],
  ['ambulances', 'ambulances'],
  ['ambulances-approved', 'ambulances?s=approved'],
  ['bloodbanks', 'bloodBanks'],
  ['bloodbanks-approved', 'bloodBanks?s=approved'],
  ['labs', 'labs'],
  ['hospitals', 'hospitals'],
  ['tickets', 'tickets'],
  ['tickets-closed', 'tickets?s=closed'],
  ['reports', 'reports'],
  ['corrections', 'corrections'],
  ['users', 'users'],
  ['users-find', 'users?q=demo'],
  ...(user ? [['user', `user/${encodeURIComponent(user.uid)}`], ['user-community', `user/${encodeURIComponent(user.uid)}`, ['a[data-sec=community]']], ['user-timeline', `user/${encodeURIComponent(user.uid)}`, ['a[data-sec=timeline]']]] : []),
  ['catalog', 'catalog'],
  ['catalog-amb', 'catalog?l=ambulanceType.all'],
  ['labtests', 'labtests'],
  ...(tests.tests && tests.tests[0] ? [['labtest-edit', `labtests?t=${encodeURIComponent(tests.tests[0].code)}`]] : []),
  ['labtest-new', 'labtests?t='],
  ['log', 'log'],
  ['admins', 'admins'],
  ['demo', 'demo'],
  ...(ph ? [['verify-pharmacy', `verify/pharmacies:${encodeURIComponent(ph.id)}`]] : []),
  ...(bd ? [['verify-badge', `verify/badges:${encodeURIComponent(bd.uid + (bd.change ? ':change' : ''))}`]] : []),
  ...(tk ? [['ticket', `ticket/${encodeURIComponent(tk.id)}`]] : []),
  ['menu-open', '', ['.menu-btn'], ['mobile']],
  ['warn-armed', 'pharmacies', ['.t button.warn']],
  ['bank-edit', 'bloodBanks?s=approved', ['.t button.quiet:has-text("সম্পাদনা")']],
  ['notfound-verify', 'verify/pharmacies:nope'],
];

for (const variant of ['orig', 'vue']) {
  for (const size of SIZES) {
    // লগইনের পাতা
    {
      const ctx = await browser.newContext({ viewport: VP[size], locale: 'bn-BD' });
      await routes(ctx, variant);
      const page = await ctx.newPage();
      await page.clock.setFixedTime(new Date(T0));
      await page.goto(`${BASE}/admin`);
      await page.waitForTimeout(1200);
      fs.mkdirSync(path.join(outDir, variant), { recursive: true });
      await page.screenshot({ path: path.join(outDir, variant, `login-${size}.png`), fullPage: true });
      await ctx.close();
    }
    const ctx = await browser.newContext({ viewport: VP[size], locale: 'bn-BD', storageState: stateFile });
    await routes(ctx, variant);
    for (const [name, hash, clicks = [], only] of PAGES) {
      if (only && !only.includes(size)) continue;
      const page = await ctx.newPage();
      const errs = [];
      page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
      page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
      await page.clock.setFixedTime(new Date(T0));
      await page.goto(`${BASE}/admin#/${hash}`);
      await page.locator('.shell').waitFor({ timeout: 15000 }).catch((e) => errs.push('shell: ' + e.message.split('\n')[0]));
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(900);
      for (const sel of clicks) {
        try {
          await page.locator(sel).first().click({ timeout: 4000 });
          await page.waitForTimeout(700);
          await page.waitForLoadState('networkidle').catch(() => {});
        } catch (e) { errs.push('click ' + sel + ': ' + e.message.split('\n')[0]); }
      }
      await page.mouse.move(0, 0);
      await page.screenshot({ path: path.join(outDir, variant, `${name}-${size}.png`), fullPage: true });
      if (errs.length) problems.push({ variant, size, name, errs });
      await page.close();
    }
    await ctx.close();
  }
}
fs.writeFileSync(path.join(outDir, 'problems.json'), JSON.stringify(problems, null, 2));
console.log(JSON.stringify(problems, null, 1));
await browser.close();
