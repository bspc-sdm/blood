// সাম্প্রতিক UX যা React-এর মতোই থাকতে হবে (মালিকের তালিকা)
import { expect, test } from '@playwright/test';
import { def, open, watchErrors } from './helpers';

test('ডাক্তারের তালিকা: এক ছাঁচের কার্ড (বিশেষজ্ঞতা · ফি), আজ/আগামীকাল/এখন চেম্বারে চিপ, "মুছুন"', async ({ page }) => {
  const errs = watchErrors(page);
  const bodies: any[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/') && r.postDataJSON()?.action === 'providers') bodies.push(r.postDataJSON());
  });
  await open(page, def('appointment', 'list'));
  const chips = page.getByTestId('filter-chips');
  for (const chip of ['আজ বসছেন', 'আগামীকাল', 'এখন চেম্বারে']) await expect(chips.getByRole('button', { name: chip })).toBeVisible();
  // প্রথম কার্ডের মূল লাইনে ফি "ফি ৳৭০০", ব্যাজ "আজ সিরিয়াল আছে", রেটিং
  const card = page.getByTestId('listing-card').first();
  await expect(card.getByTestId('key-line')).toContainText('ফি ৳');
  await expect(card).toContainText('আজ সিরিয়াল আছে');
  await expect(card.getByTestId('rating')).toContainText('★');
  await chips.getByRole('button', { name: 'আজ বসছেন' }).click();
  await expect(chips.getByRole('button', { name: 'আজ বসছেন' })).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(() => bodies.at(-1)?.day).toBe('today');
  await chips.getByTestId('filter-clear').click();
  await expect(chips.getByRole('button', { name: 'আজ বসছেন' })).toHaveAttribute('aria-pressed', 'false');
  expect(errs).toEqual([]);
});

test('আমার প্রেসক্রিপশন: তালিকা, কোড দিয়ে যোগ, জনের ভাগ', async ({ page }) => {
  await open(page, def('health', 'rx-list'));
  await expect(page.getByRole('heading', { name: 'আমার প্রেসক্রিপশন' }).first()).toBeVisible();
  await expect(page.getByText('কোড দিয়ে যোগ করুন').first()).toBeVisible();
  await expect(page.getByText('সবাই').first()).toBeVisible();
});

test('আমার প্রেসক্রিপশন থাকে সেবা → ফার্মেসিতে (স্বাস্থ্যে নয়); পুরোনো /prescriptions সেখানে যায়', async ({ page }) => {
  const errs = watchErrors(page);
  const rx = def('health', 'rx-list');
  await open(page, rx, '/prescriptions/rx1?who=x');
  await expect(page).toHaveURL(/\/services\/pharmacy\/prescriptions\/rx1\?who=x$/);
  await open(page, rx);
  await expect(page.getByRole('tab', { name: 'আমার প্রেসক্রিপশন' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('tab', { name: 'ওষুধ খুঁজুন' })).toBeVisible();
  await expect(page.locator('a[aria-current="page"][href="/services"]:visible').first()).toBeVisible();
  await expect(page.locator('a[aria-current="page"][href="/health"]:visible')).toHaveCount(0);
  await page.getByRole('tab', { name: 'ওষুধ খুঁজুন' }).click();
  await expect(page).toHaveURL(/\/services\/pharmacy\/medicine$/);
  await page.getByRole('tab', { name: 'আমার প্রেসক্রিপশন' }).click();
  await expect(page).toHaveURL(/\/services\/pharmacy\/prescriptions$/);
  await open(page, rx, '/health/discussion');
  await expect(page.getByRole('tab', { name: 'আলোচনা' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'আমার প্রেসক্রিপশন' })).toHaveCount(0);
  expect(errs).toEqual([]);
});

test('বিস্তারিতের নিচে আটকানো বার: মূল বাটন + কল আইকন (ফার্মেসি, প্যাথলজি, ডাক্তার, অ্যাম্বুলেন্স)', async ({ page }) => {
  const bar = async (primary: string) => {
    const b = page.getByTestId('detail-bar').first();
    await expect(b).toBeVisible();
    expect(await b.evaluate((el) => getComputedStyle(el).position)).toBe('sticky');
    const main = b.getByRole('button', { name: primary });
    const call = b.getByTestId('contact-call');
    await expect(main).toBeVisible();
    await expect(call).toBeVisible();
    const [m, c] = [(await main.boundingBox())!, (await call.boundingBox())!];
    // একই সারিতে, মূল বাটন চওড়া আর আগে, কল আইকন ডানে
    expect(Math.abs(m.y + m.height / 2 - (c.y + c.height / 2))).toBeLessThan(4);
    expect(c.x).toBeGreaterThan(m.x);
    expect(c.width).toBeLessThan(m.width * 0.5);
    expect(await main.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    // ফোন নম্বর "ঠিকানা ও ফোন"-এ, কপি করা যায়
    await expect(page.getByTestId('detail-phone').first()).toBeVisible();
  };
  await open(page, def('services', 'pharm-user'));
  await page.getByTestId('listing-card').filter({ hasText: 'লাইফ কেয়ার ফার্মেসি' }).click();
  await bar('প্রেসক্রিপশন পাঠান');
  await open(page, def('services', 'lab-detail'));
  await bar('পরীক্ষা পাঠান');
  await open(page, def('appointment', 'detail'));
  await bar('সিরিয়াল নিন');
  await open(page, def('services', 'amb-user-detail'));
  await bar('অ্যাম্বুলেন্স ডাকুন');
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
