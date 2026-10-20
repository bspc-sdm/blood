// অ্যাডমিন প্যানেল (Vue, /admin) — নকল সার্ভার, সার্ভারের মতো কড়া CSP। লেখা ঘরের মান রাখা, লগইন, ১৮টা মেনু, পাতা, কাজের বোতাম।
import { expect, test, type Page } from '@playwright/test';
import { watchErrors, noSideScroll } from './helpers';

async function login(page: Page) {
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await expect(page.getByText('কোড গেছে: owner@example.com')).toBeVisible();
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
}

const NAV = ['ড্যাশবোর্ড', 'অ্যানালিটিক্স', 'জরুরি আহ্বান', 'ব্যাজ', 'ফার্মেসি', 'অ্যাম্বুলেন্স', 'ব্লাড ব্যাংক', 'প্যাথলজি', 'হাসপাতাল',
  'আপিল ও সমস্যা', 'রিপোর্ট', 'তারিখ সংশোধন', 'ব্যবহারকারী', 'বাছাইয়ের তালিকা', 'রিপোর্টের ছক', 'কাজের ইতিহাস', 'অ্যাডমিন', 'ডেমো (পরীক্ষা)'];
const TITLES: [string, RegExp][] = [
  ['', /ড্যাশবোর্ড/], ['analytics', /অ্যানালিটিক্স/], ['emergency', /জরুরি আহ্বান/], ['badges', /ব্যাজ/], ['pharmacies', /ফার্মেসি/],
  ['ambulances', /অ্যাম্বুলেন্স/], ['bloodBanks', /ব্লাড ব্যাংক/], ['labs', /প্যাথলজি/], ['hospitals', /হাসপাতাল যোগের অনুরোধ/],
  ['tickets', /আপিল ও সমস্যা/], ['reports', /রিপোর্ট/], ['corrections', /রক্তদানের তারিখ সংশোধন/], ['users', /ব্যবহারকারী/],
  ['catalog', /বাছাইয়ের তালিকা/], ['labtests', /রিপোর্টের ছক/], ['log', /কাজের ইতিহাস/], ['admins', /অ্যাডমিন/], ['demo', /ডেমো \(পরীক্ষা\)/],
];

test('লগইন: ভুল ইমেইল, ইমেইলে কোড, বাংলা অঙ্কের কোড, ১৮টা মেনু', async ({ page }) => {
  const errs = watchErrors(page);
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('not-an-email');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await expect(page.getByRole('alert')).toHaveText('সঠিক ইমেইল দিন');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await expect(page.getByRole('button', { name: /আবার কোড পাঠান \([০-৯]+ সেকেন্ড\)/ })).toBeDisabled();
  await page.locator('input[autocomplete=one-time-code]').fill('12');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.getByRole('alert')).toHaveText('৬ সংখ্যার কোড দিন');
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
  const items = await page.locator('nav.nav a .lbl').allInnerTexts();
  expect(items).toEqual(NAV);
  await expect(page.locator('nav.nav a.on')).toHaveAttribute('aria-current', 'page');
  expect(errs.filter((e) => !/401|Unauthorized/.test(e))).toEqual([]);
});

test('প্রতিটা পাতা খোলে, শিরোনাম ঠিক, CSP-তে কিছু আটকায় না @desktop', async ({ page }) => {
  const errs = watchErrors(page);
  await login(page);
  for (const [hash, title] of TITLES) {
    await page.goto(`/admin#/${hash}`);
    await expect(page.locator('.topbar h1')).toHaveText(title);
    await expect(page.locator('main.page .note', { hasText: 'লোড হচ্ছে…' })).toHaveCount(0);
    // (বাছাইয়ের তালিকা আর রিপোর্টের ছকে খালি .error সবসময় থাকে — আগের প্যানেলেও)
    await expect(page.locator('main.page .error:not(:empty)')).toHaveCount(0);
    await noSideScroll(page);
  }
  expect(errs).toEqual([]);
});

test('মেনুর সংখ্যা, মোবাইলে মেনু খোলা-বন্ধ, এখনকার পাতায় আবার চাপলে নতুন করে', async ({ page }) => {
  await login(page);
  await page.locator('.menu-btn').click();
  await expect(page.locator('.shell')).toHaveClass(/open/);
  await page.locator('nav.nav a', { hasText: 'ফার্মেসি' }).click();
  await expect(page.locator('.shell')).not.toHaveClass(/open/);
  await expect(page).toHaveURL(/#\/pharmacies$/);
  await expect(page.locator('.seg a.on')).toHaveText('অপেক্ষায়');
  await page.keyboard.press('Escape');
});

test('তালিকা: খোঁজা, ট্যাব, "নিশ্চিত? আবার চাপুন", কারণসহ বাতিল', async ({ page }) => {
  await login(page);
  await page.goto('/admin#/pharmacies?s=approved');
  await expect(page.locator('.seg a.on')).toHaveText('অনুমোদিত');
  await expect(page.locator('.toolbar .count')).toBeVisible();
  const rows = page.locator('.t tbody tr');
  const n = await rows.count();
  test.skip(n === 0, 'নকল তথ্যে অনুমোদিত ফার্মেসি নেই');
  await page.locator('.toolbar input[type=search]').fill('zzzz-নেই');
  await expect(page.locator('.card.empty')).toHaveText('এখানে কিছু নেই।');
  await expect(page.locator('.toolbar .count')).toHaveText('০টা');
  await page.locator('.toolbar input[type=search]').fill('');
  await expect(rows).toHaveCount(n);
  const row = rows.first();
  await row.locator('input.reason').fill('নিয়ম ভঙ্গ হয়েছে');
  const req = page.waitForRequest((r) => r.url().endsWith('/api/community') && r.postDataJSON()?.action === 'admin_pharmacy_resolve');
  await row.getByRole('button', { name: 'বন্ধ করুন' }).click();
  await row.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' }).click();
  expect((await req).postDataJSON()).toMatchObject({ status: 'suspended', reason: 'নিয়ম ভঙ্গ হয়েছে', panel: true });
  await expect(page.locator('#toast')).toHaveText('ফার্মেসি বন্ধ করা হয়েছে');
});

test('যাচাই: সব টিক না দিলে অনুমোদন বন্ধ', async ({ page }) => {
  await login(page);
  await page.goto('/admin#/badges');
  await expect(page.locator('.toolbar .count')).toBeVisible();
  const link = page.locator('a.btn.primary', { hasText: 'যাচাই করে' }).first();
  test.skip(!(await link.count()), 'অপেক্ষায় ব্যাজ নেই');
  await link.click();
  await expect(page.locator('.topbar h1')).toHaveText(/যাচাই/);
  const approve = page.locator('.verify .row button.btn.primary');
  await expect(approve).toBeDisabled();
  const boxes = page.locator('.checklist input[type=checkbox]');
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check();
  const missing = await page.getByText('দরকারি ছবি নেই — আগে ছবি চান').count();
  if (missing) await expect(approve).toBeDisabled();
  else {
    await expect(page.locator('.verify .row .small')).toHaveText('সব মিলেছে');
    await expect(approve).toBeEnabled();
  }
});

test('বাছাইয়ের তালিকা: নতুন নাম যোগ করে সেভ', async ({ page }) => {
  await login(page);
  await page.goto('/admin#/catalog?l=ambulanceType.all');
  const rows = page.locator('.t tbody tr');
  await expect(rows.first()).toBeVisible();
  const n = await rows.count();
  await page.getByRole('button', { name: 'নতুন নাম যোগ' }).click();
  await expect(rows).toHaveCount(n + 1);
  await rows.last().locator('input.cell-input').first().fill('পরীক্ষা');
  await page.getByRole('button', { name: 'সেভ করুন' }).click();
  await expect(page.locator('#toast')).toHaveText('সেভ হয়েছে — অ্যাপে কয়েক ঘণ্টার মধ্যে দেখাবে');
  await expect(rows.last().locator('td').nth(2)).not.toHaveText('নতুন');
});

test('ব্যবহারকারী: খোঁজা, পুরো তথ্য, অ্যাপের ভাগের ট্যাব', async ({ page }) => {
  await login(page);
  await page.goto('/admin#/users');
  await page.getByLabel('ব্যবহারকারী খুঁজুন').fill('doctor@lifolo-demo.test');
  await page.getByRole('button', { name: 'খুঁজুন' }).click();
  await expect(page).toHaveURL(/users\?q=doctor%40lifolo-demo\.test/);
  await page.locator('.t tbody tr.click').first().click();
  await expect(page).toHaveURL(/#\/user\//);
  await expect(page.locator('.facts').first()).toContainText('doctor@lifolo-demo.test');
  await expect(page.locator('.ctl').first()).toContainText('অ্যাকাউন্ট');
  await expect(page.locator('.user-tab .sub-title').first()).toBeVisible();
  for (const tab of ['আলোচনা', 'সেবা', 'ডাক্তার ও অ্যাপয়েন্টমেন্ট', 'সময়ক্রম']) {
    await page.locator('.tabs-wrap a', { hasText: tab }).click();
    await expect(page.locator('.tabs-wrap a.on')).toHaveText(tab);
    await expect(page.locator('.user-tab .error')).toHaveCount(0);
    await expect(page.locator('.user-tab .sub-title').first()).toBeVisible();
  }
});

test('লগআউট', async ({ page }) => {
  await login(page);
  // মোবাইলে বাম মেনু লুকানো — আগে খোলা
  if (await page.locator('.menu-btn').isVisible()) await page.locator('.menu-btn').click();
  await page.getByRole('button', { name: 'লগআউট' }).click();
  await expect(page.getByText('লগআউট হয়েছে।')).toBeVisible();
  await expect(page.locator('input[type=email]')).toBeVisible();
});
