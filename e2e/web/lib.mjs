// e2e সাহায্যকারী: ব্রাউজার, ডেমো রোলে লগইন/রোল বদল, ধাপের স্ক্রিনশট আর manifest, নিজে থেকে কিছু ভুল ধরা
// (পাশে স্ক্রল, কনসোল/সার্ভারের ভুল, বাংলায় ইংরেজি লেখা)।
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { BASE, E2E } from './stack.mjs';
import { demo, ensureConsent } from './api.mjs';

export const SHOTS = path.join(E2E, 'web/shots');
export const PHONE = { width: 412, height: 915 };
export const DESKTOP = { width: 1366, height: 900 };
const CHROME = process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

/** ডেমো রোলের নাম যেমন লগইন পাতার তালিকায় দেখায় (server/lib/demo/index.js ROLES[].bn) */
export const ROLE_LABEL = {
  patient: 'সাধারণ ব্যবহারকারী / রোগী',
  donor: 'রক্তদাতা',
  doctor: 'ডাক্তার',
  assistant: 'ডাক্তারের সহকারী',
  pharmacy: 'ফার্মেসি',
  lab: 'প্যাথলজি / ল্যাব',
  ambulance: 'অ্যাম্বুলেন্স',
  bloodbank: 'ব্লাড ব্যাংক',
  homecare: 'বাসায় সেবা (নার্স)',
  caregiver: 'কেয়ারগিভার',
};
export const PHONE_ROLES = new Set(['patient', 'donor', 'caregiver', 'ambulance', 'guest', 'homecare', 'bloodbank']);

/**
 * স্বাস্থ্য তথ্যের সম্মতির সুইচ (health_consent, backend/docs/CONSENT.md) — FEATURES_ALL_ON-এর বাইরে, তাই আলাদা:
 * E2E_CONSENT=on হলে ০০-এর পরে মালিক সুইচটা "সবার জন্য" চালু করেন (CI-র ডিফল্ট — সব সুইচ চালু)।
 */
export const CONSENT = (process.env.E2E_CONSENT || 'off') === 'on';
let consentReady = false;
/**
 * সম্মতি চালু থাকলে ডেমো রোলগুলোর সম্মতি প্রস্তুতিতেই (API, একবার) — আগের যাত্রাগুলো সম্মতির আগের মতোই চলে (রোগীর হয়ে
 * কেয়ারগিভারের কাজেও রোগীর সম্মতি লাগে)। পর্দা দিয়ে সম্মতি: ১০-এর নতুন ব্যবহারকারী (ADM-08)।
 */
export async function demoConsents() {
  if (!CONSENT || consentReady) return;
  for (const r of Object.keys(ROLE_LABEL)) {
    try {
      await ensureConsent(await demo(r));
    } catch (e) {
      // এই ডেমোতে না থাকা রোল (লগইন হয় না) — বাদ
      if (!/demo login/.test(e.message)) throw e;
    }
  }
  consentReady = true;
}

/** সম্মতির পর্দা (সার্ভারের লেখা: শিরোনাম ‘স্বাস্থ্য তথ্য রাখার আগে আপনার সম্মতি’, বোতাম ‘রাজি আছি’ / ‘এখন না’) */
export const consentDialog = (page) => page.getByRole('dialog').filter({ hasText: 'স্বাস্থ্য তথ্য রাখার আগে আপনার সম্মতি' });

export const manifest = [];
export const issues = []; // নিজে থেকে ধরা সন্দেহ — হাতে দেখে bug/ঠিক আছে ঠিক করা হয়

let browser;
/** স্ট্যাকের ঘড়ি (stack.mjs লিখে রাখে) — ব্রাউজারও একই সময়ে */
export function clockOff() {
  if (process.env.E2E_TIME_OFFSET_MS) return Number(process.env.E2E_TIME_OFFSET_MS);
  const f = path.join(E2E, '.build/clock.json');
  return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')).offset : 0;
}
export async function launch() {
  browser = await chromium.launch({ executablePath: CHROME, args: ['--lang=bn-BD'] });
  return browser;
}
export async function close() {
  await browser?.close();
}

const pad = (n) => String(n).padStart(2, '0');

/**
 * একটা যাত্রা (journey): নিজের ফোল্ডার, নিজের ক্রমিক ধাপ।
 * J.page(role) — সেই রোলের পাতা (ফোন বা ডেস্কটপ মাপ), দরকার হলে লগইন/রোল বদল ডেমো তালিকা দিয়ে
 */
export class Journey {
  constructor(no, slug, titleBn) {
    this.no = no;
    this.id = `${pad(no)}-${slug}`;
    this.titleBn = titleBn;
    this.step = 0;
    this.pages = {}; // viewport -> {ctx, page, role}
    this.errs = [];
    this.done = [];
    this.known = [];
    this.scId = '';
    fs.mkdirSync(path.join(SHOTS, this.id), { recursive: true });
  }

  async ctxFor(vp) {
    if (this.pages[vp]) return this.pages[vp];
    const isPhone = vp === 'phone';
    const ctx = await browser.newContext({
      viewport: isPhone ? PHONE : DESKTOP, deviceScaleFactor: 1, locale: 'bn-BD', timezoneId: 'Asia/Dhaka',
      isMobile: isPhone, hasTouch: isPhone,
      geolocation: { latitude: 23.999, longitude: 90.42 }, permissions: ['geolocation', 'clipboard-read', 'clipboard-write'],
    });
    const off = clockOff();
    if (off) await ctx.clock.install({ time: Date.now() + off });
    await ctx.addInitScript(() => {
      // tel: লিংক — আসল ফোন খোলার বদলে লিখে রাখা (অ্যাপের নিজের কাজ, যেমন ফোনের হিসাব, আগের মতোই চলে)
      document.addEventListener('click', (e) => {
        const a = e.target && e.target.closest && e.target.closest('a[href^="tel:"]');
        if (a) { e.preventDefault(); window.__lastTel = a.getAttribute('href'); }
      }, true);
      const open0 = window.open;
      window.open = (u, ...r) => (String(u || '').startsWith('tel:') ? ((window.__lastTel = String(u)), null) : open0.call(window, u, ...r));
      try {
        if (!localStorage.getItem('lf.lang')) localStorage.setItem('lf.lang', 'bn');
        // ফোনে "অ্যাপ নামান" জাতীয় বাধা বন্ধ নয় — আসল ব্যবহারকারীর মতোই
      } catch {}
    });
    const page = await ctx.newPage();
    page.setDefaultTimeout(8000);
    trackInflight(page);
    const errs = this.errs;
    page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
    page.on('console', (m) => {
      if (m.type() === 'error' && !/Failed to load resource|favicon/i.test(m.text())) errs.push(`console: ${m.text().slice(0, 200)}`);
    });
    page.on('response', async (r) => {
      if (r.url().includes('/api/') && r.status() >= 500) errs.push(`api ${r.status()} ${r.url()}`);
    });
    page.on('dialog', (d) => d.accept().catch(() => {}));
    this.pages[vp] = { ctx, page, role: null, vp };
    return this.pages[vp];
  }

  /** এই রোলের পাতা (ফোন/ডেস্কটপ রোল দেখে, বা vp দিয়ে জোর করে) — লগইন/রোল বদল ডেমো তালিকা দিয়ে */
  async as(role, vp = PHONE_ROLES.has(role) ? 'phone' : 'desktop') {
    if (role !== 'guest' && role !== 'owner') await demoConsents();
    const P = await this.ctxFor(vp);
    if (P.role !== role) {
      await switchRole(P.page, role, P.role);
      P.role = role;
    }
    this.cur = P;
    return P.page;
  }

  get page() {
    return this.cur.page;
  }

  /** ধাপের স্ক্রিনশট + manifest; status: ok|bug|fixed; opt.full = পুরো পাতা */
  async shot(titleBn, expectedBn, opt = {}) {
    const page = opt.page || this.cur.page;
    const P = Object.values(this.pages).find((x) => x.page === page) || this.cur;
    this.step++;
    await settle(page);
    // ডায়ালগ খোলা থাকলে পুরো পাতার ছবি ভুল দেখায় (ডায়ালগ পর্দার মাপে আটকে থাকে) — তখন শুধু পর্দা
    if (opt.full && (await page.locator('[role=dialog]:visible').count())) opt = { ...opt, full: false };
    if (opt.full) await page.evaluate(() => window.scrollTo(0, 0));
    const auto = await autoCheck(page);
    const errs = this.errs.splice(0);
    const file = `${this.id}/${pad(this.step)}-step.png`;
    if (opt.full) {
      // পুরো পাতা: জানালাটাই লম্বা করে ছবি (fullPage-এ নিচের/পাশের স্থির মেনু মাঝখানে বসে যায়)
      const vp = page.viewportSize();
      const h = await page.evaluate(() => Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
      if (h > vp.height + 4) {
        await page.setViewportSize({ width: vp.width, height: Math.min(h, 5000) });
        await page.waitForTimeout(250);
        await page.screenshot({ path: path.join(SHOTS, file) });
        await page.setViewportSize(vp);
      } else await page.screenshot({ path: path.join(SHOTS, file) });
    } else await page.screenshot({ path: path.join(SHOTS, file) });
    const entry = {
      journey: this.id,
      journeyTitleBn: this.titleBn,
      step: this.step,
      titleBn,
      role: P.role || 'guest',
      viewport: P.vp === 'phone' ? `ফোন ${PHONE.width}×${PHONE.height}` : `ডেস্কটপ ${DESKTOP.width}×${DESKTOP.height}`,
      file,
      expectedBn,
      status: opt.status || 'ok',
      noteBn: opt.noteBn || '',
      scenario: opt.sc || this.scId || '',
      url: page.url().replace(BASE, ''),
    };
    if (opt.check === false) {
      entry.status = 'bug';
    }
    manifest.push(entry);
    const found = [...auto, ...errs];
    if (found.length) issues.push({ file, titleBn, found });
    if (process.env.E2E_TRACE) console.log(`  ${file}  ${titleBn}${found.length ? `  !! ${found.join(' | ').slice(0, 300)}` : ''}`);
    return entry;
  }

  /** একটা দৃশ্য (গাইডের scenario): ভুল হলে স্ক্রিনশট রেখে পরেরটায় যাওয়া */
  async sc(id, titleBn, fn) {
    this.scId = id;
    this.scTitle = titleBn;
    const t0 = Date.now();
    try {
      await fn();
      this.done.push({ id, ok: true });
    } catch (e) {
      const msg = e.message.split('\n')[0].slice(0, 220);
      const at = (/journeys\/[^)]+:(\d+):\d+/.exec(e.stack || '') || [])[0] || '';
      console.error(`   !! ${id} ${titleBn}: ${msg} @${at}`);
      this.done.push({ id, ok: false, msg });
      try {
        if (this.cur) await this.shot(`${titleBn} — এখানে আটকে গেছে`, 'পরের ধাপে যাওয়া যায়নি', { status: 'bug', noteBn: `স্বয়ংক্রিয় ধাপ থেমেছে: ${msg}` });
      } catch {}
    }
    if (process.env.E2E_TRACE) console.log(`  [${id}] ${Math.round((Date.now() - t0) / 1000)}s`);
    this.scId = '';
  }

  /**
   * জানা ভুল (অ্যাপ/সার্ভারের — রিপোর্ট করা, এখনো ঠিক হয়নি; e2e নিজে ঠিক করে না): যাচাইটা চলে, ব্যর্থ হলে "known" হিসেবে
   * রিপোর্টে আলাদা তালিকায় (রান লাল হয় না); পাস করলে রিপোর্টে "ঠিক হয়েছে? — xfail সরান"।
   */
  async xfail(bug, titleBn, fn) {
    try {
      await fn();
      this.known.push({ bug, title: titleBn, passed: true });
      console.error(`   ?? ${bug} ${titleBn}: passes now — remove the xfail`);
    } catch (e) {
      const msg = e.message.split('\n')[0].slice(0, 220);
      this.known.push({ bug, title: titleBn, passed: false, msg });
      if (process.env.E2E_TRACE) console.log(`   (known bug ${bug}) ${titleBn}: ${msg}`);
    }
  }

  async end() {
    for (const P of Object.values(this.pages)) await P.ctx.close().catch(() => {});
    this.pages = {};
  }
}

/**
 * পাতার ভেতরের শর্ত সত্যি হওয়া পর্যন্ত (page.evaluate দিয়ে বারবার দেখা)। page.waitForFunction নয় — সেটা পাতায় স্ট্রিং
 * eval করে, আর পাতাগুলো প্রোডাকশনের CSP-তে চলে (script-src 'self', unsafe-eval নেই), তাই সেখানে আটকে যায়।
 */
export async function until(page, fn, arg = null, timeout = 8000) {
  const t0 = Date.now();
  for (;;) {
    let ok = false;
    try {
      ok = await page.evaluate(fn, arg);
    } catch (e) {
      if (!/Execution context was destroyed|navigat|Target closed|frame was detached/i.test(e.message)) throw e;
    }
    if (ok) return;
    if (Date.now() - t0 > timeout) throw new Error(`timeout ${timeout}ms waiting for: ${String(fn).replace(/\s+/g, ' ').slice(0, 120)}`);
    await page.waitForTimeout(100);
  }
}

// চলতি অনুরোধ (পাতা প্রতি) — লম্বা স্ট্রিম বাদ: ওয়েব লগইন থাকলে সবসময় GET /api/events (SSE, features_changed / কল) খোলা
// রাখে, তাই Playwright-এর 'networkidle' আর কখনো আসে না — প্রতিটা settle পুরো ৬ সেকেন্ড অপেক্ষা করত (যাত্রা ৫৫ মিনিট পেরোত)
const inflight = new WeakMap();
const longLived = (r) => /\/api\/events(\?|$)/.test(r.url()) || /text\/event-stream/.test(r.headers().accept || '');
// ফন্ট আলাদা (নিচে document.fonts.ready): Chromium-এর .woff fallback অনুরোধের অনেক সময় শেষ-খবর (requestfinished/failed)
// আসে না — ধরলে সেই পাতার প্রতিটা settle ৬ সেকেন্ড আটকাত (রক্তের যাত্রা ১৯ মিনিট)। আর ১০ সেকেন্ডের পুরোনো অনুরোধও গোনা হয় না।
const STALE_MS = 10_000;
export function trackInflight(page) {
  const set = new Map();
  inflight.set(page, set);
  page.on('request', (r) => { if (!longLived(r) && r.resourceType() !== 'font') set.set(r, Date.now()); });
  page.on('requestfinished', (r) => set.delete(r));
  page.on('requestfailed', (r) => set.delete(r));
  page.on('framenavigated', (f) => { if (f === page.mainFrame()) for (const r of set.keys()) if (r.frame() !== f) set.delete(r); });
}

/** নেটওয়ার্ক ৫০০ ms চুপ ('networkidle'-এর মতো, লম্বা স্ট্রিম বাদে) — বড়জোর timeout পর্যন্ত */
async function quiet(page, timeout = 6000) {
  const set = inflight.get(page);
  if (!set) return page.waitForLoadState('networkidle', { timeout }).catch(() => {});
  await page.waitForLoadState('load', { timeout }).catch(() => {});
  const t0 = Date.now();
  const busy = () => {
    const now = Date.now();
    for (const at of set.values()) if (now - at < STALE_MS) return true;
    return false;
  };
  let calmSince = busy() ? 0 : Date.now();
  while (Date.now() - t0 < timeout) {
    if (busy()) calmSince = 0;
    else if (!calmSince) calmSince = Date.now();
    else if (Date.now() - calmSince >= 500) return;
    await page.waitForTimeout(50);
  }
}

/** নেটওয়ার্ক থামা আর লোড চিহ্ন চলে যাওয়া পর্যন্ত */
export async function settle(page, ms = 250) {
  await quiet(page, 6000);
  await page.evaluate(() => Promise.race([document.fonts.ready, new Promise((r) => setTimeout(r, 3000))])).catch(() => {});
  await until(page, () => !document.querySelector('.loading, [aria-busy="true"], .skeleton'), null, 4000).catch(() => {});
  await page.waitForTimeout(ms);
}

/** পাশে স্ক্রল, আর বাংলা মোডে ইংরেজি বাক্য (৩+ শব্দ) */
async function autoCheck(page) {
  return page.evaluate(() => {
    const out = [];
    const over = document.documentElement.scrollWidth - window.innerWidth;
    if (over > 1) out.push(`horizontal overflow ${over}px`);
    // কাটা পড়া লেখা: ডান দিকে পর্দার বাইরে যাওয়া দৃশ্যমান উপাদান
    const W = window.innerWidth;
    const cut = [];
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.position === 'fixed') continue;
      if (r.right > W + 2 && r.left < W && el.children.length === 0 && (el.textContent || '').trim()) {
        // স্ক্রল করা যায় এমন পাত্রের ভেতরে হলে ঠিক আছে
        let p = el.parentElement;
        let scroller = false;
        while (p && p !== document.body) {
          const s = getComputedStyle(p);
          if (/(auto|scroll|hidden)/.test(s.overflowX)) { scroller = true; break; }
          p = p.parentElement;
        }
        if (!scroller) cut.push((el.textContent || '').trim().slice(0, 30));
      }
    }
    if (cut.length) out.push(`cut off at right: ${cut.slice(0, 4).join(' / ')}`);
    if ((localStorage.getItem('lf.lang') || 'bn') === 'bn') {
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      const eng = new Set();
      const OK = /^(USG.*|.*abdomen.*|Lifolo|OK|PDF|QR|SMS|BMI|ECG|CBC|HbA1c|Rx|ID|Spam|Gmail|Google|Maps|WhatsApp|Imo|bKash|Nagad|Android|App|Play Store|Email|Hb|mg|ml|Tab|Cap|Syp|Inj|kg|cm|mmHg|mg\/dL|mmol\/L|Napa|Ace|Seclo|Losectil|Metformin|Paracetamol|Omeprazole|Amlodipine|Lipid Profile|S\.? ?Creatinine|Blood Sugar|Urine R\/E|X-Ray|USG|FBS|RBS|TSH|SGPT|Chest|Whole Abdomen|ESR|Serum|Hemoglobin|Negative|Positive|A\+|B\+|O\+|AB\+|A-|B-|O-|AB-)$/i;
      while (walker.nextNode()) {
        const n = walker.currentNode;
        const t = (n.textContent || '').trim();
        if (!t || t.length < 8) continue;
        const el = n.parentElement;
        if (!el || el.closest('script,style,code,input,textarea,[translate="no"],.mono,.drug,.rx-med')) continue;
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) continue;
        // ৩+ ইংরেজি শব্দ পরপর, বাংলা নেই
        if (/[ঀ-৿]/.test(t)) continue;
        const words = t.split(/\s+/).filter((w) => /^[A-Za-z][A-Za-z'’,.:;!?()-]*$/.test(w));
        // কোড/সংক্ষেপ (সব বড় হাতের) বাদ — অন্তত দুটো ছোট হাতের সাধারণ শব্দ লাগবে
        const plain = words.filter((w) => /^[A-Za-z]?[a-z]{2,}/.test(w));
        // UI বাক্য চেনা যায় সাধারণ ইংরেজি ছোট শব্দে (ওষুধ/পরীক্ষার নাম ইংরেজিতেই থাকে, সেগুলো বাদ)
        const fn = words.filter((w) => /^(the|to|and|of|is|are|a|an|your|you|not|no|this|for|in|with|please|will|was|has|have|be|can|on|at|from|by|or|it|yet|here|there|now|more|all|add|tap|send|save|cancel|open|close|try|again|log|login|sign)$/i.test(w.replace(/[^A-Za-z]/g, '')));
        if (words.length >= 3 && plain.length >= 2 && fn.length >= 1 && !OK.test(t)) eng.add(t.slice(0, 60));
      }
      if (eng.size) out.push(`english text: ${[...eng].slice(0, 5).join(' / ')}`);
    }
    return out;
  });
}

/** লগইন পাতার ডেমো তালিকা দিয়ে ঢোকা, বা প্রোফাইল › রোল বদলান */
export async function switchRole(page, role, current) {
  if (role === 'guest') {
    if (current && current !== 'guest' && current !== 'owner') await logout(page);
    else await page.goto(`${BASE}/`);
    return;
  }
  // 'owner' = শুধু অ্যাডমিন প্যানেলে (কুকি) ঢোকা — ওয়েব অ্যাপে তখনো অতিথি
  if (!current || current === 'guest' || current === 'owner') {
    await page.goto(`${BASE}/login`);
    const dlg = page.getByRole('dialog');
    await dlg.getByText('ডেমো (পরীক্ষার জন্য)').waitFor();
    await dlg.getByText(ROLE_LABEL[role], { exact: false }).first().click();
  } else {
    await page.goto(`${BASE}/profile`);
    await page.getByText('রোল বদলান', { exact: true }).first().click();
    const dlg = page.getByRole('dialog');
    await dlg.getByText('ডেমো (পরীক্ষার জন্য)').waitFor();
    await dlg.getByText(ROLE_LABEL[role], { exact: false }).first().click();
  }
  await until(page, () => !document.querySelector('[role="dialog"]'), null, 15000);
  await settle(page);
}

export async function logout(page) {
  await page.goto(`${BASE}/profile`);
  await page.getByText('লগআউট', { exact: true }).first().click();
  const dlg = page.getByRole('dialog');
  if (await dlg.count()) await dlg.getByRole('button', { name: 'লগআউট' }).click().catch(() => {});
  await settle(page);
}

/** লেখা দেখে চাপা (প্রথম দৃশ্যমান) */
export async function tap(page, text, opt = {}) {
  const scope = opt.in ? page.locator(opt.in) : page;
  const loc = typeof text === 'string' || text instanceof RegExp
    ? (opt.role ? scope.getByRole(opt.role, { name: text, exact: opt.exact ?? false })
      // লেখা না থাকলে আইকন-বোতামের নাম (aria-label) দিয়ে
      : scope.getByText(text, { exact: opt.exact ?? false }).or(scope.getByRole('button', { name: text, exact: opt.exact ?? false })))
    : text;
  const el = vis(loc).first();
  await el.scrollIntoViewIfNeeded().catch(() => {});
  await el.click({ timeout: opt.timeout || 8000 });
  await settle(page, opt.wait ?? 200);
}

export async function has(page, text, timeout = 5000) {
  try {
    await vis(page.getByText(text, { exact: false })).first().waitFor({ timeout });
    return true;
  } catch {
    return false;
  }
}

export async function go(page, p, { soft = false } = {}) {
  if (soft) {
    // পাতা নতুন করে না খুলে অ্যাপের ভেতরেই যাওয়া (মেনু/লিংক চাপার মতো) — কেয়ারগিভার মোড মেমোরিতে থাকে, রিলোডে চলে যায়
    await page.evaluate((to) => { history.pushState({}, '', to); dispatchEvent(new PopStateEvent('popstate', { state: {} })); }, p);
  } else await page.goto(`${BASE}${p}`);
  await settle(page);
}

export function writeManifest(dir = SHOTS) {
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest, null, 1));
  fs.writeFileSync(path.join(dir, 'issues.json'), JSON.stringify(issues, null, 1));
}

/** পরীক্ষার ঘড়িতে ঢাকার আজকের (বা n দিন পরের) তারিখ YYYY-MM-DD */
export function dhakaISO(days = 0) {
  const t = Date.now() + clockOff() + 6 * 3600 * 1000 + days * 86400000;
  return new Date(t).toISOString().slice(0, 10);
}
/** ইংরেজি অঙ্ক → বাংলা */
export const bn = (x) => String(x).replace(/\d/g, (d) => '০১২৩৪৫৬৭৮৯'[d]);

/** দৃশ্যমান লেখা আসা পর্যন্ত অপেক্ষা (না এলে ভুল) */
export async function see(page, text, timeout = 8000) {
  await vis(page.getByText(text, { exact: false })).first().waitFor({ timeout });
}

/** শুধু দৃশ্যমানগুলো (Playwright ১.৪৯-এ filter({visible}) নেই) */
export const vis = (loc) => loc.and(loc.page().locator(':visible'));

/** অ্যাডমিন প্যানেলের কাজের বোতাম: সতর্কতার বোতামে প্রথম চাপে ‘নিশ্চিত? আবার চাপুন’ — তখন আবার চাপা */
export async function adminAct(page, loc) {
  await loc.click();
  await page.waitForTimeout(200);
  const armed = page.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' });
  if (await armed.count()) await armed.first().click();
  await settle(page, 700);
}

/** ডাক্তারের পাতায় চেম্বারের অ্যাপয়েন্টমেন্ট বোতাম (নামের নিচের চেম্বার-কার্ড, aria-label ‘অ্যাপয়েন্টমেন্ট: <চেম্বার>’; ২.৩৩.০-এর আগে ‘অ্যাপয়েন্টমেন্ট চাই’) */
export const bookBtn = (page) => page.getByRole('button', { name: /^অ্যাপয়েন্টমেন্ট: |^অ্যাপয়েন্টমেন্ট চাই$/ });

/**
 * ‘অনুরোধ পাঠান’-এর পরে: চেম্বারে অগ্রিম থাকলে (সুইচ pay_chamber_advance, PAYMENTS.md) আগে পেমেন্টের জানালা
 * ‘অনুরোধ গেছে। সিরিয়াল রাখতে অগ্রিম দিন।’ — রোগী পরে দেবেন (জানালা বন্ধ; বুকিং থাকে, ‘অগ্রিম বাকি’), তারপর
 * ‘অনুরোধ পাঠানো হয়েছে’। সুইচ বন্ধ বা অগ্রিম নেই (আজকের পার হওয়া সময়) — সরাসরি ‘অনুরোধ পাঠানো হয়েছে’।
 * J দিলে অগ্রিমের জানালার ছবি।
 */
export async function bookSent(page, J) {
  const sent = vis(page.getByText('অনুরোধ পাঠানো হয়েছে'));
  const adv = page.getByRole('dialog').filter({ hasText: 'সিরিয়াল রাখতে অগ্রিম দিন' });
  await sent.or(adv).first().waitFor({ timeout: 10000 });
  if (await adv.count()) {
    await settle(page, 300);
    if (J) await J.shot('অগ্রিমের জানালা (পরে দেবেন)', '‘অনুরোধ গেছে। সিরিয়াল রাখতে অগ্রিম দিন।’ — অগ্রিম ৳২০০, ডাক্তারের ডেমো নম্বর; বন্ধ করলে বুকিং থাকে (‘অগ্রিম বাকি’)।');
    await page.keyboard.press('Escape');
  }
  await see(page, 'অনুরোধ পাঠানো হয়েছে');
}

/**
 * আমার কাজ › ফার্মেসি (তিন ট্যাব: অর্ডার · ওষুধ খোঁজা · দোকান › তথ্য/বিক্রি/স্টক/এনালাইটিক্স)। পুরোনো ট্যাবের নামও চলে
 * (‘অনলাইন অর্ডার’ → অর্ডার, ‘পণ্য ও স্টক’ → দোকান › স্টক)।
 */
export async function pharmacyWork(page, tab) {
  const to = {
    'অনলাইন অর্ডার': 'orders', 'অর্ডার': 'orders', 'ওষুধ খোঁজা': 'finds', 'দোকান': 'shop', 'তথ্য': 'shop',
    'বিক্রি': 'shop/sales', 'পণ্য ও স্টক': 'shop/stock', 'স্টক': 'shop/stock', 'এনালাইটিক্স': 'shop/analytics',
  }[tab] || 'orders';
  await go(page, `/work/pharmacy/${to}`);
}

/**
 * দোকান › বিক্রি-র পণ্য খোঁজার ঘর। রসিদ চালু থাকলে (সুইচ inv_pharmacy, INVOICES.md) লেখা ‘খুঁজুন বা বারকোড স্ক্যান করুন’,
 * বন্ধ থাকলে ‘পণ্য খুঁজুন’ (দোকান › মাল কেনা-তেও ‘পণ্য খুঁজুন’)।
 */
export const posPicker = (page) => page.getByPlaceholder(/^পণ্য খুঁজুন$|^খুঁজুন বা বারকোড স্ক্যান করুন$/).first();

/** রসিদ চালু: ‘প্রতিটা বিক্রির পর রসিদ প্রিন্ট’ বন্ধ (যাত্রায় ছাপা নয়) — বন্ধ সুইচে ঘরটাই নেই */
export async function posNoPrint(page) {
  const c = page.getByRole('checkbox', { name: 'প্রতিটা বিক্রির পর রসিদ প্রিন্ট' });
  if ((await c.count()) && (await c.first().isChecked())) await c.first().uncheck();
}

/**
 * ‘বিক্রি লিখুন’ → ‘বিক্রি লেখা হয়েছে’ (রসিদ চালু: ‘… — রসিদ তৈরি’ আর রসিদের জানালা; ছবি নিয়ে বন্ধ, যাতে পরের ধাপ চলে)।
 * J দিলে রসিদের ছবি।
 */
export async function posSave(page, J) {
  await page.getByRole('button', { name: 'বিক্রি লিখুন' }).click();
  await see(page, 'বিক্রি লেখা হয়েছে');
  const r = page.getByRole('dialog').filter({ hasText: 'রসিদ' });
  if (await r.count()) {
    await settle(page, 300);
    if (J) await J.shot('বিক্রির রসিদ', 'রসিদ নম্বর, দোকানের নাম, লাইন, ছাড়, মোট, পরিশোধ (নগদ) আর যাচাইয়ের QR।');
    await page.keyboard.press('Escape');
    await r.first().waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
  }
}

/** আমার কাজ › প্যাথলজি (অনুরোধ · ল্যাব › ল্যাবের তথ্য/পরীক্ষা ও দাম/স্বাভাবিক মাত্রা); পুরোনো ‘পরীক্ষার অনুরোধ’ → অনুরোধ */
export async function labWorkTab(page, tab) {
  const to = { 'পরীক্ষা ও দাম': 'orders/prices', 'স্বাভাবিক মাত্রা': 'orders/ranges', 'ল্যাবের তথ্য': 'info', 'ল্যাব': 'info' }[tab] || 'orders';
  await go(page, `/work/lab/${to}`);
}

/**
 * ফার্মেসির/ল্যাবের অর্ডারের সারি — অবস্থা অনুযায়ী আলাদা ট্যাবে থাকে (নতুন · চলছে / তৈরি · শেষ; ল্যাবে নমুনা/চলছে ·
 * রিপোর্ট দেওয়া), তাই যে ট্যাবে আছে সেটা খুলে সারি ফেরত
 */
export async function orderRow(page, text, opt = {}) {
  const tabs = page.getByRole('tab', { name: /^(নতুন|চলছে|নমুনা|রিপোর্ট দেওয়া|শেষ)/ });
  const find = () => {
    let l = page.locator('tr, article');
    for (const t of [].concat(text)) l = l.filter({ hasText: t });
    if (opt.button) l = l.filter({ has: page.getByRole('button', { name: opt.button }) });
    return vis(l).first();
  };
  // তালিকা আসা পর্যন্ত (দোকানের তথ্য আগে আসে, তারপর অর্ডারের তালিকা — মাঝে নেটওয়ার্ক এক মুহূর্ত থামতে পারে)
  const t0 = Date.now();
  while (Date.now() - t0 < 10000) {
    if (await find().count()) return find();
    if (await vis(tabs).count()) break;
    await page.waitForTimeout(200);
  }
  if (await find().count()) return find();
  const n = await tabs.count();
  for (let i = 0; i < n; i++) {
    if (!(await tabs.nth(i).isVisible())) continue;
    await tabs.nth(i).click();
    await settle(page);
    for (let k = 0; k < 15; k++) {
      if (await find().count()) return find();
      await page.waitForTimeout(200);
    }
  }
  throw new Error(`সারি পাওয়া যায়নি: ${[].concat(text).join(' + ')}`);
}

/** সেবা › ফার্মেসি: আগে ‘কীভাবে কিনবেন?’ — ‘দোকানে গিয়ে কিনব’ (কাছের সব ফার্মেসি) বাছা, না চাইলে কিছু না */
export async function inStore(page) {
  await settle(page);
  const r = page.getByRole('radio', { name: /^দোকানে গিয়ে কিনব/ }).or(page.getByRole('button', { name: /^দোকানে গিয়ে কিনব/ }));
  if (await r.count()) { await r.first().click(); await settle(page); }
}

/** বিস্তারিতে ‘কপি’ বোতাম থাকলে নম্বর কপি (২.৩x-এ সরানো — তখন কিছু করে না, false) */
export async function copyIfThere(page) {
  const copy = page.getByRole('button', { name: 'কপি' }).first();
  if (!(await copy.count())) return false;
  await copy.click();
  await see(page, 'কপি হয়েছে');
  return true;
}
