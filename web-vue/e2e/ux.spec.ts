// সাম্প্রতিক UX যা React-এর মতোই থাকতে হবে (মালিকের তালিকা)
import { expect, test } from '@playwright/test';
import { def, open, watchErrors } from './helpers';

test('ডাক্তারের তালিকা: চেম্বার আগে, আজ/আগামীকাল/বিশেষজ্ঞতার ফিল্টার', async ({ page }) => {
  const errs = watchErrors(page);
  await open(page, def('appointment', 'list'));
  for (const chip of ['আজ বসছেন', 'আগামীকাল', 'এখন চেম্বারে']) await expect(page.getByRole('button', { name: chip }).first()).toBeVisible();
  // প্রথম কার্ডে চেম্বার (হাসপাতাল, বার ও সময়, ফি)
  const card = page.locator('main').getByText('ফি:').first();
  await expect(card).toBeVisible();
  const all = await page.locator('main').innerText();
  await page.getByRole('button', { name: 'আজ বসছেন' }).first().click();
  await page.waitForTimeout(400);
  const today = await page.locator('main').innerText();
  expect(today.length).toBeLessThan(all.length);
  expect(errs).toEqual([]);
});

test('আমার প্রেসক্রিপশন: তালিকা, কোড দিয়ে যোগ, জনের ভাগ', async ({ page }) => {
  await open(page, def('health', 'rx-list'));
  await expect(page.getByRole('heading', { name: 'আমার প্রেসক্রিপশন' }).first()).toBeVisible();
  await expect(page.getByText('কোড দিয়ে যোগ করুন').first()).toBeVisible();
  await expect(page.getByText('সবাই').first()).toBeVisible();
});

test('ফার্মেসির খুঁটিনাটি: নিচে আটকানো "প্রেসক্রিপশন পাঠান", পাশে ছোট ফোন বোতাম', async ({ page }) => {
  await open(page, def('services', 'pharm-user'));
  // আগে "কীভাবে কিনবেন?" — দোকানে গিয়ে
  await page.getByText('দোকানে গিয়ে কিনব').first().click();
  await page.getByText('লাইফ কেয়ার ফার্মেসি').first().click();
  const foot = page.locator('.services-foot').first();
  await expect(foot).toBeVisible();
  expect(await foot.evaluate((el) => getComputedStyle(el).position)).toBe('sticky');
  const send = foot.locator('button.detail-bar-main', { hasText: 'প্রেসক্রিপশন পাঠান' });
  await expect(send).toBeVisible();
  const call = foot.locator('.detail-bar-row a[href^="tel:"]', { hasText: 'ফোন' });
  await expect(call).toBeVisible();
  // একই সারিতে, ফোন আগে; মালিকের অনুরোধ: ফোনের মাপে "ফোন" ছোট (লেখার মাপে), "প্রেসক্রিপশন পাঠান" চওড়া
  const [sw, cw] = [(await send.boundingBox())!, (await call.boundingBox())!];
  expect(Math.abs(sw.y - cw.y)).toBeLessThan(2);
  expect(cw.x).toBeLessThan(sw.x);
  expect(cw.width).toBeLessThan(sw.width * 0.6);
  // "প্রেসক্রিপশন পাঠান" এক লাইনে, কাটা নয়
  expect(await send.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
});

test('ফোন + আরেকটা কাজ পাশাপাশি: প্যাথলজি আর ডাক্তারেও ফোন ছোট; অ্যাম্বুলেন্সে দুটো সমান (ফোনই মূল)', async ({ page }) => {
  const widths = async () => {
    const row = page.locator('.detail-bar-row').first();
    await expect(row).toBeVisible();
    const call = row.locator('a[href^="tel:"]').first();
    const other = row.locator('button').first();
    await expect(call).toBeVisible();
    await expect(other).toBeVisible();
    return [(await call.boundingBox())!.width, (await other.boundingBox())!.width];
  };
  await open(page, def('services', 'lab-detail'));
  const [lc, lo] = await widths();
  expect(lc).toBeLessThan(lo * 0.6);
  // ডাক্তার: ফোনের মাপে "সিরিয়ালের ফোন" ছোট হয়ে "ফোন"
  await open(page, def('appointment', 'detail'));
  const [dc, dO] = await widths();
  expect(dc).toBeLessThan(dO * 0.6);
  expect((await page.locator('.detail-bar-row a[href^="tel:"]').first().innerText()).trim()).toBe('ফোন');
  await open(page, def('services', 'amb-user-detail'));
  const [ac, ao] = await widths();
  expect(Math.abs(ac - ao)).toBeLessThan(2);
});

test('ফার্মেসির ট্যাব: অর্ডার / ওষুধ খোঁজা / দোকান (তথ্য · বিক্রি · স্টক · এনালাইটিক্স)', async ({ page }) => {
  await open(page, def('work', 'pharmacy'));
  for (const t of ['অর্ডার', 'ওষুধ খোঁজা', 'দোকান']) await expect(page.getByRole('tab', { name: new RegExp(t) }).first()).toBeVisible();
  await page.getByRole('tab', { name: /দোকান/ }).first().click();
  await page.waitForURL(/\/work\/pharmacy\/shop/);
  for (const t of ['তথ্য', 'বিক্রি', 'স্টক', 'এনালাইটিক্স']) await expect(page.getByRole('tab', { name: new RegExp(t) }).first()).toBeVisible();
  await page.getByRole('tab', { name: /এনালাইটিক্স/ }).first().click();
  await page.waitForURL(/\/shop\/analytics/);
  await page.getByRole('tab', { name: /ওষুধ খোঁজা/ }).first().click();
  await page.waitForURL(/\/work\/pharmacy\/finds/);
  await expect(page.getByText('ওষুধ খোঁজার অনুরোধ').first()).toBeVisible();
});

test('অ্যাম্বুলেন্স: "অ্যাম্বুলেন্স ডাকুন" — গাড়ির ধরন, কোথায় যাবেন, ভাড়ার সীমা', async ({ page }) => {
  await open(page, def('services', 'amb-user-detail'), '/services/ambulance');
  await page.getByRole('button', { name: 'অ্যাম্বুলেন্স ডাকুন' }).first().click();
  const dlg = page.getByRole('dialog');
  await expect(dlg).toBeVisible();
  await expect(dlg.getByText('গাড়ির ধরন')).toBeVisible();
  await expect(dlg.getByText('ভাড়া সর্বোচ্চ')).toBeVisible();
  // ভাড়ার আগে থেকে ঠিক করা ধাপ (৳…), "যেকোনো" আর "অন্য…"
  const budget = dlg.getByTestId('amb-budget');
  expect(await budget.getByRole('button').count()).toBeGreaterThan(3);
  await expect(budget.getByRole('button', { name: 'যেকোনো' })).toBeVisible();
  // গন্তব্য না দিলে পাঠানো যায় না
  await dlg.getByTestId('amb-send').click();
  await expect(dlg.getByText('"কোথায় যাবেন" বেছে নিন').first()).toBeVisible();
});

test('বাইরের history.pushState-এর পরেও কার্ড চাপলে খোলে (কেয়ারগিভার মোডের "নরম" যাওয়া)', async ({ page }) => {
  await open(page, def('appointment', 'my'));
  await page.evaluate(() => {
    history.pushState({}, '', '/my-appointments');
    dispatchEvent(new PopStateEvent('popstate', { state: {} }));
  });
  await page.waitForTimeout(300);
  await page.locator('article[role=button]').first().click();
  await expect(page).toHaveURL(/\/my-appointments\/[^/]+$/);
});
