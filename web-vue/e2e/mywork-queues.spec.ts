// "আমার কাজ"-এর কাজের সারি (mywork CONTRACT §২) — ৩৯০ আর ১৪৪০-এ, ফিচার সুইচ চালু আর বন্ধ:
// স্থির ট্যাব (সর্বোচ্চ ৪, সংখ্যাসহ, লেখা কাটা নয়, পাশে স্ক্রল নেই), সারিতে একটাই মূল বোতাম + "আরও", ফার্মেসি অর্ডার · বিক্রি · দোকান
// (তৃতীয় স্তর নেই), ডাক্তারের এক "আজকের রোগী" (সহকারী লেখেন না), প্যাথলজির আটকানো "রিপোর্ট পাঠান" আর মেয়াদ পেরোনো সময় সবুজ নয়,
// অ্যাম্বুলেন্সের খালি/ট্রিপে এক জায়গায় আর খালি "ডাক" এক লাইনে, লেখা ছাড়া নিয়ন্ত্রণ নেই, ডেস্কটপে তালিকা-বিস্তারিত।
import { expect, test, type Locator, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors, type PageDef } from './helpers';

const withTest = (p: PageDef, path: string, extra: Record<string, unknown> = {}): PageDef => ({ ...p, path, test: { ...(p.test ?? {}), ...extra } });
const pharm = (path: string, extra: Record<string, unknown> = {}) => withTest(def('rxpartner', 'ph-dispense'), path, extra);
const lab = (path: string) => withTest(def('rxpartner', 'lab-list'), path);
const doctor = (path: string, extra: Record<string, unknown> = {}) => withTest(def('rxdoctor', 'patients'), path, extra);
const amb = () => def('work', 'ambulance');
const APPROVED_LAB = { lab: { id: 'u-work', name: 'পপুলার ডায়াগনস্টিক সেন্টার', status: 'approved', phone: '', address: '', district: '', upazilaId: null, facilityId: null, homeCollection: true, hours: '', licenseNo: '', statusReason: '' } };
const OFF = { features: { inv_pharmacy: false, inv_lab: false, inv_chamber: false, inv_ambulance: false, pay_delivery: false, pay_lab: false } };

async function approveLab(page: Page) {
  await page.route('**/api/community', async (route) => {
    const b = route.request().postDataJSON() || {};
    if (b.action === 'lab_me') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(APPROVED_LAB) });
    return route.continue();
  });
}
/** প্রতিটা ট্যাব পর্দার ভেতরে, লেখা কাটা নয় (ellipsis/লুকানো নয়) */
async function tabsWhole(page: Page, scope: Locator) {
  const vw = page.viewportSize()!.width;
  const tabs = scope.getByRole('tab');
  const n = await tabs.count();
  expect(n).toBeGreaterThan(0);
  expect(n).toBeLessThanOrEqual(4);
  for (let i = 0; i < n; i++) {
    const t = tabs.nth(i);
    const b = (await t.boundingBox())!;
    expect(b.x, 'ট্যাব বাঁয়ে কাটা নয়').toBeGreaterThanOrEqual(0);
    expect(b.x + b.width, 'ট্যাব ডানে কাটা নয়').toBeLessThanOrEqual(vw + 0.5);
    expect(await t.evaluate((e) => e.scrollWidth <= e.clientWidth + 1), 'লেখা ট্যাবের ভেতরে').toBe(true);
    expect(b.height, 'চাপার জায়গা').toBeGreaterThanOrEqual(43.5);
  }
}
/** কার্ডে একটার বেশি মূল (ভরা) বোতাম নয় */
async function onePrimary(cards: Locator) {
  const n = await cards.count();
  expect(n).toBeGreaterThan(0);
  for (let i = 0; i < n; i++) expect(await cards.nth(i).locator('.q-acts > .btn:not(.text):not(.outline):not(.tonal):not(.danger-text)').count()).toBeLessThanOrEqual(1);
}

for (const sw of ['on', 'off'] as const) {
  const extra = sw === 'off' ? OFF : {};
  test(`ফার্মেসি (সুইচ ${sw}): অর্ডার · বিক্রি · দোকান; নতুন · চলছে · শেষ · ওষুধ খোঁজা; একটাই মূল বোতাম; "নতুন" চিহ্ন নয় @desktop`, async ({ page }) => {
    const errs = watchErrors(page);
    await open(page, pharm('/work/pharmacy/orders', extra));
    const top = page.getByTestId('pharmacy-tabs');
    await expect(top.getByRole('tab')).toHaveText([/^অর্ডার/, /^বিক্রি$/, /^দোকান$/]);
    await tabsWhole(page, top);
    const segs = page.getByTestId('queue-segs');
    // সংখ্যা বাংলা অঙ্কে (নকল সার্ভারের অবস্থা অন্য পরীক্ষায় বদলায়, তাই ঠিক সংখ্যা নয়)
    await expect(segs.getByRole('tab')).toHaveText([/^নতুন\s*[০-৯]*$/, /^চলছে\s*[০-৯]*$/, /^শেষ$/, /^ওষুধ খোঁজা\s*[০-৯]*$/]);
    await tabsWhole(page, segs);
    if (page.viewportSize()!.width < 960) {
      const cards = page.getByTestId('order-card');
      await onePrimary(cards);
      // "নতুন" ভাগে কার্ডে আবার "নতুন" চিহ্ন নয়
      await expect(cards.locator('.tag', { hasText: /^নতুন$/ })).toHaveCount(0);
      await expect(cards.first().getByTestId('row-more')).toContainText('আরও');
      await cards.first().getByTestId('row-more').click();
      await expect(page.getByRole('menuitem', { name: 'পারব না' })).toBeVisible();
      await page.keyboard.press('Escape');
    }
    await noSideScroll(page);
    // বিক্রি এক চাপে (কাউন্টার)
    await top.getByRole('tab', { name: 'বিক্রি' }).click();
    await page.waitForURL(/\/work\/pharmacy\/shop\/sales/);
    await expect(page.getByRole('combobox').first()).toBeVisible();
    await expect(page.getByRole('tab', { name: 'রসিদ' })).toHaveCount(sw === 'on' ? 1 : 0);
    // দোকান: স্টক · মাল কেনা · হিসাব · তথ্য — তৃতীয় স্তর নেই
    await top.getByRole('tab', { name: 'দোকান' }).click();
    await page.waitForURL(/\/work\/pharmacy\/shop\/stock/);
    const shop = page.getByTestId('shop-tabs');
    await expect(shop.getByRole('tab')).toHaveText(['স্টক', 'মাল কেনা', 'হিসাব', 'তথ্য']);
    await tabsWhole(page, shop);
    await expect(page.getByRole('tablist')).toHaveCount(2);
    // স্টকের সুইচের পাশে দেখা যায় এমন লেখা
    await expect(page.locator('.books-stock-sw').first()).toContainText(/স্টকে (আছে|নেই)/);
    await shop.getByRole('tab', { name: 'মাল কেনা' }).click();
    await page.waitForURL(/v=buy/);
    await expect(shop.getByRole('tab', { name: 'মাল কেনা' })).toHaveAttribute('aria-selected', 'true');
    await page.getByTestId('pharmacy-tabs').getByRole('tab', { name: /অর্ডার/ }).click();
    await page.getByTestId('queue-segs').getByRole('tab', { name: /ওষুধ খোঁজা/ }).click();
    await page.waitForURL(/\/work\/pharmacy\/finds/);
    await expect(page.getByTestId('queue-segs').getByRole('tab', { name: /ওষুধ খোঁজা/ })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText('ওষুধ খোঁজার অনুরোধ').first()).toBeVisible();
    expect(errs).toEqual([]);
  });
}

test('@desktop ফার্মেসি: বাঁয়ে সারি, ডানে কোডের বাক্স আর বাছা অর্ডার; কোড খুললে প্রেসক্রিপশন ডানে', async ({ page }) => {
  test.skip(page.viewportSize()!.width < 960, 'ডেস্কটপ');
  await open(page, pharm('/work/pharmacy/orders?seg=active'));
  await expect(page.getByTestId('queue-segs').getByRole('tab', { name: /^চলছে/ })).toHaveAttribute('aria-selected', 'true');
  await page.goto('/work/pharmacy/orders', { waitUntil: 'networkidle' });
  const items = page.getByTestId('q-item');
  await expect(items.first()).toHaveAttribute('aria-current', 'true');
  const list = (await page.locator('.qd-list').boundingBox())!;
  const side = (await page.locator('.rxp-codeside').boundingBox())!;
  expect(side.x).toBeGreaterThan(list.x + list.width - 1);
  await expect(page.locator('.rxp-codeside').getByTestId('order-card')).toHaveCount(1);
  if ((await items.count()) > 1) {
    const name = (await items.nth(1).locator('.t-title-s').innerText()).trim();
    await items.nth(1).click();
    await expect(page.locator('.rxp-codeside').getByTestId('order-card')).toContainText(name);
  }
  await onePrimary(page.locator('.rxp-codeside').getByTestId('order-card'));
  await noSideScroll(page);
});

test('প্যাথলজি: স্থির ভাগ, একটাই মূল বোতাম, মেয়াদ পেরোনো সময় সবুজ নয় @desktop', async ({ page }) => {
  await approveLab(page);
  await open(page, lab('/work/lab/orders'));
  await tabsWhole(page, page.getByTestId('lab-tabs'));
  await expect(page.getByTestId('queue-segs').getByRole('tab')).toHaveText([/^নতুন/, /^চলছে/, /^শেষ$/]);
  await tabsWhole(page, page.getByTestId('queue-segs'));
  const expired = page.getByTestId('home-slot-expired').first();
  await expect(expired).toContainText('সময় পেরিয়ে গেছে');
  const ok = page.locator('.lp-home-tag:not(.expired)').first();
  const bg = (l: Locator) => l.evaluate((e) => getComputedStyle(e).backgroundColor);
  expect(await bg(expired)).not.toBe(await bg(ok));
  if (page.viewportSize()!.width < 960) await onePrimary(page.getByTestId('lab-card'));
  await noSideScroll(page);
});

test('প্যাথলজি: "রিপোর্ট পাঠান" সবসময় দেখা যায় (ফোনে নিচে আটকানো, ডেস্কটপে ডানে-উপরে) @desktop', async ({ page }) => {
  await approveLab(page);
  await open(page, lab('/work/lab/orders/lo2'));
  const send = page.getByRole('button', { name: 'রিপোর্ট পাঠান' });
  await expect(send).toHaveCount(1, { timeout: 15_000 });
  await expect(send).toBeInViewport();
  await page.mouse.wheel(0, 900);
  await page.waitForTimeout(200);
  await expect(send).toBeInViewport();
  if (page.viewportSize()!.width >= 960) {
    // তালিকা পাশে থাকে
    await expect(page.getByTestId('q-item').first()).toBeVisible();
    await expect(page.getByTestId('q-item').filter({ hasText: 'রহিমা খাতুন' })).toHaveAttribute('aria-current', 'true');
  }
  await noSideScroll(page);
});

test('অ্যাম্বুলেন্স: খালি/ট্রিপে শুধু এক জায়গায়; ডাক না থাকলে এক লাইন; লেখাসহ "বদলান"/"আরও" @desktop', async ({ page }) => {
  await open(page, amb());
  await expect(page.getByTestId('amb-calls-empty')).toContainText('এখন কোনো ডাক নেই');
  await expect(page.getByTestId('queue-segs')).toHaveCount(0);
  // খালি/ট্রিপে বদলানো শুধু "আমার কাজ"-এর অবস্থার বোতামে: এখানে চালু গাড়ির প্রতিটায় শুধু অবস্থা আর "বদলান" → /work;
  // রোগীরা যেভাবে দেখেন — বড় কার্ড নয়, এক লাইন "দেখুন"; গাড়ির তালিকায় সুইচ নেই
  await expect(page.getByTestId('sw-available')).toHaveCount(0);
  await expect(page.locator('[data-testid=provider-preview] .pv-switch')).toHaveCount(0);
  await expect(page.getByTestId('provider-preview').getByTestId('queue-state')).toHaveText(['খালি আছি', 'ট্রিপে আছি']);
  await expect(page.getByTestId('provider-preview').getByTestId('listing-card')).toHaveCount(0);
  await expect(page.getByTestId('provider-preview').getByRole('button', { name: 'দেখুন' })).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'আবার চালু রাখুন' })).toHaveCount(0);
  await expect(page.locator('.work-cards [role=switch], .work-table [role=switch]')).toHaveCount(0);
  await page.getByTestId('provider-preview').first().getByTestId('state-change').click();
  await expect(page).toHaveURL(/\/work$/);
  await expect(page.locator('[data-testid^="status-ambulance-"]').first()).toBeVisible();
  await page.goBack();
  await expect(page.getByTestId('amb-state').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'বদলান' }).first()).toBeVisible();
  await page.getByTestId('row-more').first().click();
  await expect(page.getByRole('menuitem', { name: 'এই অ্যাম্বুলেন্স সরিয়ে দিন' })).toBeVisible();
  await noSideScroll(page);
});

for (const sw of ['on', 'off'] as const) {
  const extra = sw === 'off' ? OFF : {};
  test(`ডাক্তার (সুইচ ${sw}): এক "আজকের রোগী" — অবস্থা, একটাই মূল বোতাম (ডাকুন → প্রেসক্রিপশন লিখুন), লিংক সারিতে একবার @desktop`, async ({ page }) => {
    const errs = watchErrors(page);
    const sent: any[] = [];
    page.on('request', (r) => {
      const b = r.url().includes('/api/') ? r.postDataJSON() : null;
      if (b?.action === 'queue_control') sent.push(b);
    });
    await open(page, doctor('/work/pro', extra));
    const tabs = page.getByTestId('doctor-tabs');
    await expect(tabs.getByRole('tab')).toHaveText([/^আজকের রোগী/, /^বুকিং/, /^রোগী$/, /^প্রেসক্রিপশন$/]);
    await tabsWhole(page, tabs);
    const rows = page.getByTestId('desk-row');
    await expect(rows).toHaveCount(5);
    await expect(page.getByTestId('tq-state')).toHaveText(['শেষ', 'দেখা হচ্ছে', 'অপেক্ষায়', 'অপেক্ষায়', 'অপেক্ষায়']);
    // ডেস্কের অংশ শুধু সুইচ চালু থাকলে
    await expect(page.getByTestId('desk-walkin')).toHaveCount(sw === 'on' ? 1 : 0);
    await expect(page.getByTestId('biz-invoices')).toHaveCount(sw === 'on' ? 1 : 0);
    // আগের "আজকের সিরিয়াল"-এর সংখ্যার ঘর বা চার ঘর নেই
    await expect(page.locator('.stats')).toHaveCount(0);
    if (page.viewportSize()!.width < 960) {
      for (let i = 0; i < 5; i++) {
        const row = rows.nth(i);
        expect(await row.locator('.tq-acts > .btn').count()).toBeLessThanOrEqual(1);
        // প্রেসক্রিপশনের লিংক সারিতে একবারই (বোতাম বা "আরও"-তে)
        const inline = await row.getByTestId('tq-write').count();
        let inMenu = 0;
        if (await row.getByTestId('row-more').count()) {
          await row.getByTestId('row-more').click();
          inMenu = await page.getByRole('menu').getByTestId('tq-write').count();
          await page.keyboard.press('Escape');
        }
        expect(inline + inMenu).toBe(1);
      }
      await expect(rows.nth(1).getByTestId('tq-write')).toHaveText('প্রেসক্রিপশন লিখুন');
      await rows.nth(4).getByTestId('tq-call').click();
    } else {
      await expect(page.getByTestId('tq-detail')).toContainText('সালমা খাতুন');
      await expect(page.getByTestId('tq-detail').getByTestId('tq-write')).toHaveCount(1);
      await page.getByTestId('q-item').nth(4).click();
      await page.getByTestId('tq-detail').getByTestId('tq-call').click();
    }
    await expect.poll(() => sent.at(-1)).toMatchObject({ op: 'set', appointmentId: expect.any(String) });
    await noSideScroll(page);
    expect(errs).toEqual([]);
  });
}

test('ডাক্তার: "আরও"-তে সেটআপ, সহকারী; পুরোনো ডেস্ক আর /work/pro/patients এক জায়গায় @desktop', async ({ page }) => {
  await open(page, doctor('/work/rx/desk'));
  await page.waitForURL(/\/work\/pro$/);
  await expect(page.getByTestId('today-queue')).toBeVisible();
  await page.getByTestId('doctor-more').click();
  for (const t of ['নতুন প্রেসক্রিপশন', 'সহকারী', 'সেটআপ']) await expect(page.getByRole('menuitem', { name: t })).toBeVisible();
  await page.getByRole('menuitem', { name: 'সেটআপ' }).click();
  await page.waitForURL(/\/work\/pro\/chamber/);
  await expect(page.getByTestId('doctor-subpage')).toHaveText('সেটআপ');
  await page.getByTestId('doctor-tabs').getByRole('tab', { name: 'রোগী', exact: true }).click();
  await page.waitForURL(/\/work\/rx$/);
  await expect(page.getByTestId('doctor-tabs').getByRole('tab', { name: 'রোগী', exact: true })).toHaveAttribute('aria-selected', 'true');
  // কম্পিউটারের ছকে আগের প্রেসক্রিপশন: আইকনের সাথে লেখা (ফোনে পুরো সারি চাপলেই খোলে)
  if (page.viewportSize()!.width >= 1100) await expect(page.getByTestId('rx-history').first()).toHaveText('আগের প্রেসক্রিপশন');
  await page.goto('/work/pro/patients', { waitUntil: 'networkidle' });
  await page.waitForURL(/\/work\/rx$/);
  // পুরোনো বুকিংয়ের লিংক
  await page.goto('/work/pro?v=waiting', { waitUntil: 'networkidle' });
  await page.waitForURL(/\/work\/pro\/bookings\?v=waiting/);
  await expect(page.getByTestId('queue-segs').getByRole('tab', { name: /নতুন/ })).toHaveAttribute('aria-selected', 'true');
  await onePrimary(page.locator('article.card'));
  // হোমের কাজের লিংক (work_today, backend MYWORK.md)
  await page.goto('/work/pro?tab=bookings', { waitUntil: 'networkidle' });
  await page.waitForURL(/\/work\/pro\/bookings$/);
  await page.goto('/work/pharmacy/orders?seg=active', { waitUntil: 'networkidle' });
});

test('সহকারী: একই "আজকের রোগী", প্রেসক্রিপশন লেখা নেই; ট্যাব আজকের রোগী · রোগী · টাকা মেলানো @desktop', async ({ page }) => {
  await open(page, doctor('/work/rx?doctor=doc1'));
  await expect(page.getByRole('tablist').first().getByRole('tab')).toHaveText([/আজকের রোগী/, /রোগী/, /টাকা মেলানো/]);
  await tabsWhole(page, page.getByRole('tablist').first().locator('..'));
  await expect(page.getByTestId('desk-row')).toHaveCount(5);
  await expect(page.getByTestId('tq-write')).toHaveCount(0);
  for (const b of await page.getByTestId('row-more').all()) {
    await b.click();
    await expect(page.getByRole('menu').getByTestId('tq-write')).toHaveCount(0);
    await page.keyboard.press('Escape');
  }
  await noSideScroll(page);
});

/** কল/মেসেজের বোতামে আইকনের সাথে লেখা, চাপার জায়গা ≥ ৪৮px */
async function wordedContacts(scope: Locator) {
  const call = scope.getByTestId('contact-call').first();
  await expect(call).toBeVisible();
  await expect(call).toHaveText('কল');
  const b = (await call.boundingBox())!;
  expect(b.height, 'কলের বোতামের উচ্চতা').toBeGreaterThanOrEqual(47.5);
  expect(b.width, 'কলের বোতামের চওড়া').toBeGreaterThanOrEqual(47.5);
  const msg = scope.getByTestId('contact-message');
  if (await msg.count()) {
    await expect(msg.first()).toHaveText('মেসেজ');
    expect((await msg.first().boundingBox())!.height).toBeGreaterThanOrEqual(47.5);
  }
}

test('যোগাযোগের বোতামে লেখা: "কল" · "মেসেজ" — অর্ডারের কার্ড, বুকিং, সেবার তালিকায় একই', async ({ page }) => {
  const errs = watchErrors(page);
  await open(page, pharm('/work/pharmacy/orders'));
  const card = page.getByTestId('order-card').filter({ has: page.getByTestId('contact-message') }).first();
  await wordedContacts(card);
  await noSideScroll(page);
  await open(page, def('appointment', 'pro-bookings'), '/work/pro/bookings?v=upcoming');
  await wordedContacts(page.locator('body'));
  await noSideScroll(page);
  await open(page, def('services', 'amb-user-detail'), '/services/pharmacy');
  await wordedContacts(page.getByTestId('listing-card').first());
  await noSideScroll(page);
  expect(errs).toEqual([]);
});
