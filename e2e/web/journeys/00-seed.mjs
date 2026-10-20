// মালিক: অ্যাডমিন প্যানেলে (/admin) নিজের ইমেইলে লগইন করে "ডেমো (পরীক্ষা)" › "ডেমো বানান" — ধাপে ধাপে admin_demo_seed
import { BASE } from '../stack.mjs';
import { settle } from '../lib.mjs';

export const meta = { no: 0, slug: 'demo-seed', titleBn: 'মালিক: ডেমো বানানো (অ্যাডমিন প্যানেল)' };
export const OWNER_EMAIL = 'ekapkbd@gmail.com';

/**
 * লগইন কোড একই ইমেইলে মিনিটে একবারই যায় (সার্ভারের নিয়ম) — আগের কোড ৬০ সেকেন্ডের কম আগে গিয়ে থাকলে বাকিটা অপেক্ষা
 * (আগের যাত্রা এইমাত্র লগইন করে থাকলে)। অনিশ্চিত "আবার চেষ্টা" নয় — সার্ভারের লগের সময় দেখে ঠিক যতটুকু দরকার।
 */
export async function waitCodeGap(email) {
  const { at } = await (await fetch(`${BASE}/__e2e/mail?to=${encodeURIComponent(email)}`)).json().catch(() => ({}));
  if (!at) return;
  const wait = at + 61000 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
}

export async function adminLogin(page) {
  await page.goto(`${BASE}/admin`);
  await settle(page, 400);
  // কুকির সেশন আগে থেকেই থাকলে আবার লগইন লাগে না
  if (!(await page.locator('input[type=email]').count())) return;
  await page.locator('input[type=email]').fill(OWNER_EMAIL);
  await waitCodeGap(OWNER_EMAIL);
  await page.getByRole('button').filter({ hasText: /কোড/ }).first().click();
  await page.locator('input[inputmode=numeric], input[autocomplete=one-time-code]').first().waitFor();
  const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${OWNER_EMAIL}`)).json();
  await page.locator('input[inputmode=numeric], input[autocomplete=one-time-code]').first().fill(code);
  await page.getByRole('button').filter({ hasText: /লগইন|যাচাই|ঢুকুন/ }).first().click();
  await settle(page, 600);
}

export async function run(J) {
  const P = await J.ctxFor('desktop');
  P.role = 'owner';
  J.cur = P;
  const page = P.page;
  await page.goto(`${BASE}/admin`);
  await J.shot('অ্যাডমিন প্যানেলে ঢোকার পাতা', 'ইমেইল দিয়ে কোড চাওয়ার ঘর দেখা যায়।');
  await adminLogin(page);
  await J.shot('মালিক হিসেবে প্যানেলে ঢোকা', 'প্যানেলের বাম মেনু আর প্রথম পাতা দেখা যায়।');
  await page.getByText('ডেমো (পরীক্ষা)').first().click();
  await settle(page);
  await J.shot('ডেমো পাতা — ডেমো বন্ধ', '"ডেমো বন্ধ" লেখা আর "ডেমো বানান" বোতাম, নিচে রোলের তালিকা।');
  await page.getByRole('button', { name: /ডেমো বানান|নতুন করে বানান/ }).click();
  await page.getByText(/ডেমো চালু আছে/).waitFor({ timeout: 120000 });
  await settle(page);
  await J.shot('ধাপে ধাপে ডেমো বানানো শেষ', '"ডেমো চালু আছে (… ডামি তথ্য)" লেখা আসে।');
}
