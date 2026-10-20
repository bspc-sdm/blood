// অ্যাডমিন (Vue) — লেখার কাজগুলো সত্যি সার্ভারে: বাতিল (কারণসহ), যাচাই করে অনুমোদন, আপিলের উত্তর, তালিকা সেভ, জরুরি আহ্বান।
// admin-shots.mjs-এর পরে চালান (একই লগইন state.json): BASE=… node test/admin-smoke.mjs <outDir>
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE || 'http://localhost:4610';
const VUE_DIR = path.resolve(process.env.VUE_DIR || path.join(here, '../dist-admin'));
const VARIANT = process.env.VARIANT || 'vue';
const [outDir = path.join(here, 'admin-out')] = process.argv.slice(2);
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
const browser = await chromium.launch({ executablePath: process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'bn-BD', storageState: path.join(outDir, 'state.json') });
await ctx.route((u) => new URL(u).pathname === '/admin' || new URL(u).pathname.startsWith('/admin/'), async (route) => {
  if (VARIANT !== 'vue') { const r = await route.fetch(); return route.fulfill({ response: r, headers: { ...r.headers(), 'content-security-policy': CSP } }); }
  const rel = new URL(route.request().url()).pathname.replace(/^\/admin\/?/, '');
  let file = path.join(VUE_DIR, rel || 'index.html');
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(VUE_DIR, 'index.html');
  return route.fulfill({ status: 200, body: fs.readFileSync(file), headers: { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'content-security-policy': CSP } });
});
const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error' && !/inline style/.test(m.text())) errs.push('console: ' + m.text()); });
const results = [];
const ok = (name, cond) => { results.push([name, !!cond]); console.log(cond ? 'PASS' : 'FAIL', name); };
const toast = () => page.locator('#toast').textContent();
const wait = (ms = 700) => page.waitForTimeout(ms);
async function go(hash) { await page.goto(`${BASE}/admin#/${hash}`); await page.locator('.shell').waitFor(); await page.waitForLoadState('networkidle'); await wait(500); }

// ১) ফার্মেসি বাতিল (কারণসহ): প্রথম চাপে "নিশ্চিত?", আবার চাপলে বাতিল, তালিকা থেকে সরে
await go('pharmacies');
const before = await page.locator('.t tbody tr').count();
const row = page.locator('.t tbody tr').first();
await row.locator('input.reason').fill('তথ্য অসম্পূর্ণ বা ভুল');
await row.getByRole('button', { name: 'বাতিল' }).click();
ok('warn arms', await row.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' }).count());
await row.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' }).click();
await wait(1500);
ok('pharmacy rejected toast', /বাতিল করা হয়েছে/.test(await toast()));
ok('pharmacy list shrank', (await page.locator('.t tbody tr').count()) === before - 1 || before === 1);
await go('pharmacies?s=rejected');
ok('reason shown on rejected', await page.getByText('কারণ: তথ্য অসম্পূর্ণ বা ভুল').count());

// ২) যাচাই করে অনুমোদন: সব টিক না দিলে বোতাম বন্ধ
await go('badges');
const vlink = page.locator('a.btn.primary', { hasText: 'যাচাই করে' }).first();
if (await vlink.count()) {
  await vlink.click();
  await page.locator('.checklist').waitFor();
  await wait(400);
  const btn = page.locator('.verify .row button.btn.primary');
  ok('approve disabled before ticks', await btn.isDisabled());
  const boxes = page.locator('.checklist input[type=checkbox]');
  for (let i = 0; i < await boxes.count(); i++) await boxes.nth(i).check();
  const missing = /দরকারি ছবি নেই/.test(await page.locator('.verify .row .small').textContent());
  ok('approve enabled after ticks (or missing photo)', missing || !(await btn.isDisabled()));
  if (!missing) {
    await btn.click();
    await page.waitForURL(/#\/badges$/);
    await wait(1200);
    ok('verify approve toast', /অনুমোদন দেওয়া হয়েছে/.test(await toast()));
  }
}

// ৩) আপিলের উত্তর
await go('tickets');
if (await page.locator('.t tbody tr.click').count()) {
  await page.locator('.t tbody tr.click').first().click();
  await page.locator('textarea.reply').waitFor();
  await page.locator('textarea.reply').fill('দেখছি, একটু অপেক্ষা করুন।');
  await page.getByRole('button', { name: 'উত্তর পাঠান' }).click();
  await wait(1500);
  ok('ticket reply shown', await page.locator('.msg.admin', { hasText: 'দেখছি, একটু অপেক্ষা করুন।' }).count());
}

// ৪) বাছাইয়ের তালিকা: নতুন নাম যোগ করে সেভ
await go('catalog?l=ambulanceType.all');
const n0 = await page.locator('.t tbody tr').count();
await page.getByRole('button', { name: 'নতুন নাম যোগ' }).click();
await page.locator('.t tbody tr').last().locator('input.cell-input').first().fill('পরীক্ষার ধরন');
await page.locator('.t tbody tr').last().locator('input.cell-input').nth(1).fill('Test kind');
await page.getByRole('button', { name: 'সেভ করুন' }).click();
await wait(1500);
ok('catalog saved', /সেভ হয়েছে/.test(await toast()) && (await page.locator('.t tbody tr').count()) === n0 + 1);
await go('catalog?l=ambulanceType.all');
ok('catalog persisted', await page.locator('.t tbody tr').last().locator('input.cell-input').first().inputValue() === 'পরীক্ষার ধরন');

// ৫) জরুরি আহ্বান: ফর্ম পূরণ করে পাঠানো
await go('emergency');
await page.locator('select[aria-label="জেলা"]').selectOption({ index: 1 });
await wait(1500);
const hs = page.locator('select[aria-label="হাসপাতাল"]');
if (await hs.isEnabled() && (await hs.locator('option').count()) > 1) await hs.selectOption({ index: 1 });
else await page.locator('input[placeholder="তালিকায় না থাকলে নাম লিখুন"]').fill('পরীক্ষার হাসপাতাল');
await page.locator('input[aria-label="O+ কত ব্যাগ"]').fill('2');
await page.locator('input[placeholder="01XXXXXXXXX"]').fill('01711000000');
await page.locator('.bank-form input[type=checkbox]').check();
await page.getByRole('button', { name: 'আহ্বান পাঠান' }).click();
await page.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' }).click();
await wait(2500);
const err = await page.locator('.row-error').allTextContents();
ok('emergency created: ' + err.join('|'), (await page.getByText('চালু আহ্বান').count()) > 0);

// ৬) মেনুতে এখনকার পাতায় আবার চাপলে নতুন করে আনা; রিফ্রেশ
await page.locator('.topbar button[aria-label="রিফ্রেশ"]').click();
await wait(1000);
ok('refresh toast', /হালনাগাদ হয়েছে/.test(await toast()));

ok('no page errors: ' + errs.join(' | '), errs.length === 0);
fs.writeFileSync(path.join(outDir, `smoke-${VARIANT}.json`), JSON.stringify({ results, errs }, null, 2));
await browser.close();
process.exit(results.every(([, v]) => v) ? 0 : 1);
