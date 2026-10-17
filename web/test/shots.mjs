// স্ক্রিনশট আর পরীক্ষা: তিন মাপে (মোবাইল ৩৯০, ট্যাবলেট ৮২০, ডেস্কটপ ১৪৪০) প্রতিটা পাতা।
// পাশাপাশি ধরে: পাতা পাশে স্ক্রল হয় কি না, কনসোলের ভুল, আর ভাঙা পাতা।
// চালান: node test/shots.mjs <pages.json> <outDir>
// pages.json: [{ "name": "home", "path": "/", "login": false, "test": { ...window.__lfTest }, "click": ["selector", ...], "wait": 600 }]
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const [pagesFile, outDir = path.join(here, 'out')] = process.argv.slice(2);
const pages = JSON.parse(fs.readFileSync(pagesFile, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });
const PORT = 4300 + Math.floor(Math.random() * 500);
const srv = spawn(process.execPath, [path.join(here, 'serve.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'], env: process.env });
await new Promise((r) => srv.stdout.once('data', r));

const SIZES = (process.env.SIZES || 'mobile,tablet,desktop').split(',');
const VP = { mobile: { width: 390, height: 844 }, tablet: { width: 820, height: 1180 }, desktop: { width: 1440, height: 960 } };
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const problems = [];
for (const size of SIZES) {
  const ctx = await browser.newContext({ viewport: VP[size], deviceScaleFactor: 1, locale: 'bn-BD', isMobile: size === 'mobile', hasTouch: size === 'mobile' });
  for (const p of pages) {
    if (p.sizes && !p.sizes.includes(size)) continue;
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
    page.on('console', (m) => { if (m.type() === 'error' && !/googleapis|Failed to load resource|firestore|identitytoolkit/i.test(m.text())) errs.push('console: ' + m.text()); });
    await page.addInitScript((t) => { window.__lfTest = t; try { localStorage.clear(); localStorage.setItem('lf.lang', (t && t.lang) || 'bn'); if (t && t.theme) localStorage.setItem('lf.theme', t.theme); if (t && t.guestArea) localStorage.setItem('lf.guestArea', JSON.stringify(t.guestArea)); if (t && t.filterArea) localStorage.setItem('lf.filterArea', JSON.stringify(t.filterArea)); } catch {} }, p.test || {});
    await page.goto(`http://localhost:${PORT}${p.path}`, { waitUntil: 'networkidle' }).catch((e) => errs.push('goto: ' + e.message));
    for (const sel of p.click || []) {
      try { await page.locator(sel).first().click({ timeout: 4000 }); await page.waitForTimeout(350); } catch (e) { errs.push('click ' + sel + ': ' + e.message.split('\n')[0]); }
    }
    for (const [sel, val] of p.fill || []) {
      try { await page.locator(sel).first().fill(val, { timeout: 4000 }); } catch (e) { errs.push('fill ' + sel + ': ' + e.message.split('\n')[0]); }
    }
    await page.waitForTimeout(p.wait ?? 500);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) errs.push(`horizontal overflow ${overflow}px`);
    const file = path.join(outDir, `${p.name}-${size}.png`);
    await page.screenshot({ path: file, fullPage: !!p.full });
    if (errs.length) problems.push({ page: p.name, size, errs });
    await page.close();
  }
  await ctx.close();
}
await browser.close();
srv.kill();
fs.writeFileSync(path.join(outDir, 'problems.json'), JSON.stringify(problems, null, 2));
console.log(problems.length ? `PROBLEMS:\n${JSON.stringify(problems, null, 2)}` : 'no problems');
