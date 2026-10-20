// Sentry test workflow: ওয়েবে (https://lifolo.com/#sentry-test) Sentry চালু হয় কি না আর পরীক্ষার ঘটনা যায় কি না।
// Chromium (Playwright) — নাম সরাসরি VPS-এ (Cloudflare মাঝে নয়; আসল নাম আর সার্টিফিকেট)।
//   node deploy/ci/sentry-web-test.mjs <VPS-IP>      (web-vue/node_modules-এর playwright)
// ফল stdout-এ: console=<…> · post=<HTTP কোড,…> — শেষ লাইন "ok …" বা "failed: …"
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(path.resolve('web-vue/package.json'));
const { chromium } = require('@playwright/test');

const ip = process.argv[2] || '';
const args = /^[0-9.]+$/.test(ip) ? [`--host-resolver-rules=MAP lifolo.com ${ip}`] : [];
const browser = await chromium.launch({ args });
const page = await browser.newPage();
const logs = [];
const posts = [];
page.on('console', (m) => { if (m.text().includes('[sentry]')) logs.push(m.text()); });
page.on('response', (r) => { if (/\.sentry\.io\//.test(r.url()) && r.request().method() === 'POST') posts.push(r.status()); });
page.on('requestfailed', (r) => { if (/\.sentry\.io\//.test(r.url())) posts.push(`failed:${r.failure()?.errorText}`); });
await page.goto('https://lifolo.com/#sentry-test', { waitUntil: 'load', timeout: 60000 });
for (let i = 0; i < 30 && !(logs.some((l) => l.includes('test event sent')) && posts.length); i++) await page.waitForTimeout(500);
await browser.close();
console.log(`console=${logs.join(' | ') || '-'}`);
console.log(`post=${posts.join(',') || '-'}`);
const ok = logs.some((l) => l.includes('initialised')) && posts.includes(200);
console.log(ok ? `ok (initialised, POST ${posts.join(',')})` : `failed: ${logs.length ? 'no 200 POST' : 'SDK not initialised'}`);
process.exit(ok ? 0 : 1);
