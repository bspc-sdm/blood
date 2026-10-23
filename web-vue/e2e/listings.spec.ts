// সেবার পাঁচ তালিকার এক ছাঁচ (listings CONTRACT §১, §৩, §৩b): ৩৯০ আর ১৪৪০-এ, লাইট আর ডার্কে —
// পাশে স্ক্রল নেই, প্রথম চিপ কাটা নয়, ফোনে প্রথম পর্দায় ৩+ কার্ড, ডেস্কটপে তালিকা-বিস্তারিত; কঙ্কাল, খালি অবস্থা, সাজানো,
// বাংলা অঙ্ক; "আমার কাজ"-এর "রোগীরা আপনাকে যেভাবে দেখেন", সুইচ আর নতুন / চলছে / শেষ।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors, type PageDef } from './helpers';

const USER = def('services', 'amb-user-detail');
const withTest = (p: PageDef, extra: Record<string, unknown>): PageDef => ({ ...p, test: { ...(p.test ?? {}), ...extra } });
const LISTS = [
  { kind: 'ambulance', path: '/services/ambulance', first: 'জননী অ্যাম্বুলেন্স সার্ভিস' },
  { kind: 'doctor', path: '/services/doctor', first: 'ডা. মাহমুদা আক্তার' },
  { kind: 'pharmacy', path: '/services/pharmacy', first: 'লাইফ কেয়ার ফার্মেসি' },
  { kind: 'bloodbank', path: '/services/bloodbank', first: 'জেলা সদর হাসপাতাল ব্লাড ব্যাংক' },
  { kind: 'lab', path: '/services/lab', first: 'নিরাময় ডায়াগনস্টিক সেন্টার' },
];
const cards = (page: Page) => page.getByTestId('listing-card');

/** প্রথম চিপ (সেবার ট্যাবের প্রথমটা আর ছাঁকনির সারির প্রথমটা) পুরোটা দেখা যায় */
async function firstChipsWhole(page: Page) {
  const vw = page.viewportSize()!.width;
  for (const loc of [page.getByTestId('sort-chip'), page.getByTestId('filter-chips').getByRole('button').first(), page.getByTestId('service-tabs').getByRole('tab').first()]) {
    const b = (await loc.boundingBox())!;
    expect(b.x, 'প্রথম চিপ বাঁয়ে কাটা নয়').toBeGreaterThanOrEqual(0);
    expect(b.x + b.width, 'প্রথম চিপ ডানে কাটা নয়').toBeLessThanOrEqual(vw);
  }
  // ছাঁকনির সারির প্রথম চিপ সারির ভেতরে শুরু
  const row = (await page.getByTestId('filter-chips').boundingBox())!;
  const chip = (await page.getByTestId('filter-chips').getByRole('button').first().boundingBox())!;
  expect(chip.x).toBeGreaterThanOrEqual(row.x - 0.5);
}

for (const theme of ['light', 'dark']) {
  for (const L of LISTS) {
    test(`${L.kind} (${theme}): পাশে স্ক্রল নেই, প্রথম চিপ কাটা নয়, প্রথম পর্দায় ৩+ কার্ড`, async ({ page }) => {
      const errs = watchErrors(page);
      await open(page, withTest(USER, { theme }), L.path);
      await expect(cards(page).first()).toBeVisible();
      await noSideScroll(page);
      await firstChipsWhole(page);
      // নিচের মেনুর ওপরে অন্তত ৩টা কার্ড পুরো দেখা যায়
      const navTop = await page.locator('.bottom-nav').evaluate((el) => el.getBoundingClientRect().top).catch(() => page.viewportSize()!.height);
      const n = await cards(page).count();
      let whole = 0;
      for (let i = 0; i < n; i++) {
        const b = (await cards(page).nth(i).boundingBox())!;
        if (b.y + b.height <= navTop + 0.5) whole++;
      }
      expect(whole, 'প্রথম পর্দায় পুরো কার্ড').toBeGreaterThanOrEqual(3);
      // বাংলায় মূল লাইনে ইংরেজি অঙ্ক নয় ("ফি ৳৫০০", "১ কিমির কম")
      for (const t of await page.getByTestId('key-line').allInnerTexts()) expect(t).not.toMatch(/[0-9]/);
      // কার্ডে ফোন নম্বর লেখা নেই
      expect(await cards(page).first().innerText()).not.toMatch(/০১[৩-৯]\d{8}|01[3-9]\d{8}/);
      expect(errs).toEqual([]);
    });

    test(`@desktop ${L.kind} (${theme}): তালিকা-বিস্তারিত — কিছু না বাছলেও প্রথমটা, বাছলে URL বদলায় (পাতা নতুন করে নয়)`, async ({ page }) => {
      test.skip(page.viewportSize()!.width < 900, 'ডেস্কটপ');
      const errs = watchErrors(page);
      await open(page, withTest(USER, { theme }), L.path);
      const detail = page.getByTestId('listing-detail');
      await expect(detail).toBeVisible();
      await expect(detail).toContainText(L.first);
      await noSideScroll(page);
      await firstChipsWhole(page);
      await page.evaluate(() => ((window as any).__noReload = 1));
      const second = cards(page).nth(1);
      const name = (await second.locator('.lst-name').innerText()).trim();
      await second.click();
      await expect(page).toHaveURL(new RegExp(`${L.path}/[^/?]+`));
      await expect(detail).toContainText(name);
      await expect(second).toHaveAttribute('aria-pressed', 'true');
      expect(await page.evaluate(() => (window as any).__noReload)).toBe(1);
      expect(errs).toEqual([]);
    });
  }
}

test('ফোনে বিস্তারিত: পুরো পাতা, নিচে আটকানো বার (মূল বাটন + কল/মেসেজ আইকন), ঠিকানা ও ফোন কপি করা যায়', async ({ page }) => {
  test.skip(page.viewportSize()!.width > 600, 'ফোন');
  await open(page, USER, '/services/pharmacy/ph1');
  const d = page.getByTestId('listing-detail');
  await expect(d).toContainText('লাইফ কেয়ার ফার্মেসি');
  for (const h of ['মূল তথ্য', 'খোলার সময়', 'ঠিকানা ও ফোন', 'রেটিং']) await expect(page.getByRole('heading', { name: h })).toBeVisible();
  await expect(page.getByTestId('detail-phone')).toHaveText('০১৭১১০০০৫০১');
  const bar = page.getByTestId('detail-bar');
  expect(await bar.evaluate((el) => getComputedStyle(el).position)).toBe('sticky');
  await expect(bar.getByRole('button', { name: 'প্রেসক্রিপশন পাঠান' })).toBeVisible();
  await expect(bar.getByTestId('contact-call')).toBeVisible();
  const b = (await bar.boundingBox())!;
  expect(b.y + b.height).toBeLessThanOrEqual(page.viewportSize()!.height + 1);
});

test('সেবার ট্যাব: অ্যাম্বুলেন্স · ডাক্তার · ফার্মেসি · ব্লাড ব্যাংক · ল্যাব; ডাক্তার "সেবা"-তে, অ্যাপয়েন্টমেন্টেও', async ({ page }) => {
  await open(page, USER, '/services/doctor');
  await expect(page.getByTestId('service-tabs').getByRole('tab')).toHaveText(['অ্যাম্বুলেন্স', 'ডাক্তার', 'ফার্মেসি', 'ব্লাড ব্যাংক', 'ল্যাব']);
  await expect(page.getByTestId('service-tabs').getByRole('tab', { name: 'ডাক্তার' })).toHaveAttribute('aria-selected', 'true');
  await expect(cards(page).filter({ hasText: 'ডা. রফিকুল ইসলাম' })).toContainText('পরের সিরিয়াল: কাল');
  await expect(cards(page).first().getByRole('button', { name: 'সিরিয়াল নিন' })).toBeVisible();
  await page.getByTestId('service-tabs').getByRole('tab', { name: 'ল্যাব' }).click();
  await expect(page).toHaveURL(/\/services\/lab$/);
  await open(page, def('appointment', 'list'));
  await expect(cards(page).first()).toBeVisible();
});

test('কার্ডের মূল লাইন আর ব্যাজ: প্রতি ধরনের তিন তথ্য, মজুদ, পুরনো তথ্য, বন্ধ ফার্মেসিতে বাটন নেই', async ({ page }) => {
  await open(page, USER, '/services/pharmacy');
  const life = cards(page).filter({ hasText: 'লাইফ কেয়ার ফার্মেসি' });
  await expect(life.getByTestId('key-line')).toHaveText('খোলা · ১ কিমির কম · ডেলিভারি আছে');
  await expect(life).toContainText('২৪ ঘণ্টা');
  await expect(life).toContainText('১০% ছাড়');
  await expect(life.getByTestId('rating')).toHaveText('★৪.৭ (৩১)');
  const closed = cards(page).filter({ hasText: 'ত্রিশাল মেডিসিন কর্নার' });
  await expect(closed.getByTestId('key-line')).toContainText('বন্ধ');
  await expect(closed.getByRole('button', { name: 'প্রেসক্রিপশন পাঠান' })).toHaveCount(0);

  await page.goto('/services/bloodbank', { waitUntil: 'networkidle' });
  const bb = cards(page).filter({ hasText: 'জেলা সদর হাসপাতাল ব্লাড ব্যাংক' });
  await expect(bb.getByTestId('stock-chips')).toContainText('A+ আছে');
  await expect(bb.getByTestId('stock-chips')).toContainText('হালনাগাদ: ২ ঘণ্টা আগে');
  await expect(cards(page).filter({ hasText: 'সন্ধানী ডোনার ক্লাব' })).toContainText('পুরনো তথ্য');
  await expect(cards(page).filter({ hasText: 'প্রিমিয়ার ব্লাড সেন্টার' })).toContainText('স্টকের খবর এখনো দেওয়া হয়নি');
  await expect(bb.getByRole('link', { name: 'ফোন করুন' })).toHaveAttribute('href', 'tel:0912000001');

  await page.goto('/services/lab', { waitUntil: 'networkidle' });
  await expect(cards(page).first().getByTestId('key-line')).toHaveText('খোলা · প্রায় ১ কিমি · বাসা থেকে নমুনা');
  await expect(cards(page).first().getByRole('button', { name: 'পরীক্ষা পাঠান' })).toBeVisible();

  await page.goto('/services/ambulance', { waitUntil: 'networkidle' });
  const amb = cards(page).filter({ hasText: 'জননী অ্যাম্বুলেন্স সার্ভিস' });
  await expect(amb.getByTestId('key-line')).toContainText('এসি/আইসিইউ');
  await expect(amb).toContainText('খালি আছে');
  // ৯৯৯ একবারই
  await expect(page.locator('main').getByText(/৯৯৯/).filter({ visible: true })).toHaveCount(1);
});

test('সাজানো আর ছাঁকনি: মেনু থেকে "রেটিং" (sort যায়), চিপ বাছা দেখায়, "মুছুন"; খোঁজা নাম দিয়ে', async ({ page }) => {
  const bodies: any[] = [];
  page.on('request', (r) => {
    const b = r.url().includes('/api/') ? r.postDataJSON() : null;
    if (b?.action === 'pharmacies') bodies.push(b);
  });
  await open(page, USER, '/services/pharmacy');
  await expect(page.getByTestId('sort-chip')).toContainText('নিকটতম');
  await page.getByTestId('sort-chip').click();
  await page.getByTestId('sort-menu').getByRole('menuitemradio', { name: 'রেটিং' }).click();
  await expect.poll(() => bodies.at(-1)?.sort).toBe('rating');
  await expect(page.getByTestId('sort-chip')).toContainText('রেটিং');
  const chips = page.getByTestId('filter-chips');
  await chips.getByRole('button', { name: 'এখন খোলা' }).click();
  await expect(chips.getByRole('button', { name: 'এখন খোলা' })).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(() => bodies.at(-1)?.openNow).toBe(true);
  await expect(cards(page).filter({ hasText: 'ত্রিশাল মেডিসিন কর্নার' })).toHaveCount(0);
  await chips.getByTestId('filter-clear').click();
  await expect(cards(page)).toHaveCount(3);
  await page.getByTestId('listing-search').fill('নিউ মডেল');
  await expect(cards(page)).toHaveCount(1);
  await page.getByTestId('listing-search').fill('কিছুই নেই এমন');
  await expect(page.getByTestId('listing-empty')).toContainText('এই খোঁজে কিছু মেলেনি');
  await page.getByTestId('listing-empty').getByRole('button', { name: 'মুছুন' }).click();
  await expect(cards(page)).toHaveCount(3);
  // দোকানে গিয়ে / অনলাইন অর্ডার — চিপ জোড়া, তালিকা আটকায় না
  await chips.getByRole('button', { name: 'অনলাইন অর্ডার' }).click();
  await expect.poll(() => bodies.at(-1)?.mode).toBe('online');
});

test('কঙ্কাল কার্ডের মতো (তথ্য এলে জায়গা নড়ে না); খালি অবস্থা এক লাইন + "এলাকা বদলান"', async ({ page }) => {
  let slow = false;
  await page.route('**/api/community', async (route) => {
    if (slow && route.request().postDataJSON()?.action === 'labs') await new Promise((r) => setTimeout(r, 2500));
    await route.continue();
  });
  await open(page, USER, '/services/ambulance');
  slow = true;
  await page.getByTestId('service-tabs').getByRole('tab', { name: 'ল্যাব' }).click();
  const sk = page.getByTestId('listing-skeleton');
  await expect(sk).toBeVisible();
  const skTop = (await sk.locator('.lst-card').first().boundingBox())!.y;
  await expect(cards(page).first()).toBeVisible();
  const top = (await cards(page).first().boundingBox())!.y;
  expect(Math.abs(top - skTop)).toBeLessThan(2);

  const dhaka = def('services', 'amb-empty').test!.filterArea;
  await open(page, withTest(USER, { filterArea: dhaka }), '/services/pharmacy');
  const empty = page.getByTestId('listing-empty');
  await expect(empty).toContainText('এই এলাকায় এখনো কোনো ফার্মেসি নেই');
  await expect(empty.getByRole('button')).toHaveText(['এলাকা বদলান']);
});

// ---------- "আমার কাজ" (§৩b) ----------
test('ব্লাড ব্যাংকের মালিক: রোগীরা যেভাবে দেখেন (একই কার্ড), ৮ গ্রুপের আছে/কম/নেই → bloodbank_stock_set, "সব ঠিক আছে"', async ({ page }) => {
  const sent: any[] = [];
  page.on('request', (r) => {
    const b = r.url().includes('/api/') ? r.postDataJSON() : null;
    if (b?.action === 'bloodbank_stock_set') sent.push(b);
  });
  await open(page, def('work', 'bloodbank'));
  const pv = page.getByTestId('provider-preview');
  await expect(pv.getByTestId('listing-card')).toContainText('সন্ধানী ব্লাড ব্যাংক');
  await expect(pv.getByTestId('stock-chips')).toContainText('A+ আছে');
  await pv.getByTestId('stock-O--have').click();
  await expect.poll(() => sent.at(-1)).toMatchObject({ id: 'bb1', groups: { 'O-': 'have', 'A+': 'have' } });
  await expect(pv.getByTestId('stock-chips')).toContainText('O- আছে');
  await pv.getByTestId('stock-refresh').click();
  await expect.poll(() => sent.at(-1)).toMatchObject({ id: 'bb1', refresh: true });
  // আবার আগের মতো (নকল সার্ভারের অবস্থা অন্য পরীক্ষার জন্য)
  await pv.getByTestId('stock-O--have').click();
  await expect.poll(() => sent.at(-1)?.groups?.['O-']).toBeNull();
  // ভাঁজ: মনে থাকে
  await pv.getByTestId('preview-toggle').click();
  await expect(pv.getByTestId('listing-card')).toHaveCount(0);
  // ব্যবহারকারী ধরে এই ব্রাউজারে মনে থাকে (পরীক্ষার সাহায্যকারী প্রতিবার খোলার সময় localStorage মোছে, তাই চাবিটাই দেখা)
  const uid = def('work', 'bloodbank').test!.user.uid;
  expect(await page.evaluate((k) => localStorage.getItem(k), `lf.pv.closed.${uid}.bloodbank.bb1`)).toBe('1');
  await pv.getByTestId('preview-toggle').click();
  await expect(pv.getByTestId('listing-card')).toHaveCount(1);
});

test('ফার্মেসির মালিক: রোগীরা যেভাবে দেখেন এক লাইনে ("দেখুন" → পুরো কার্ড), খোলা/বন্ধ শুধু দেখায় ("বদলান" → /work), ডেলিভারির সুইচ থাকে; কোডের বাক্স ডানে', async ({ page }) => {
  const sent: any[] = [];
  page.on('request', (r) => {
    const b = r.url().includes('/api/') ? r.postDataJSON() : null;
    if (b?.action?.startsWith('pharmacy_')) sent.push(b);
  });
  await open(page, def('rxpartner', 'ph-dispense'));
  const pv = page.getByTestId('provider-preview');
  // বড় কার্ড নয় (সেটা "আমার কাজ"-এর প্রথম পাতায় এক লাইনে) — চাপলে জানালায়
  await expect(pv.getByTestId('listing-card')).toHaveCount(0);
  await expect(pv.getByTestId('sw-open')).toHaveCount(0);
  await expect(pv.getByTestId('queue-state')).toHaveText('খোলা');
  await expect(pv.getByTestId('state-change')).toHaveAttribute('href', /\/work$/);
  await pv.getByTestId('preview-see').click();
  await expect(page.getByRole('dialog').getByTestId('key-line')).toContainText('খোলা');
  await page.keyboard.press('Escape');
  await pv.getByTestId('sw-delivery').getByRole('button', { name: 'ডেলিভারি বন্ধ' }).click();
  await expect.poll(() => sent.find((x) => x.action === 'pharmacy_delivery_set')).toMatchObject({ delivery: false });
  await pv.getByTestId('sw-delivery').getByRole('button', { name: 'ডেলিভারি চালু' }).click();
  await expect.poll(() => sent.filter((x) => x.action === 'pharmacy_delivery_set').at(-1)).toMatchObject({ delivery: true });
  expect(sent.filter((x) => x.action === 'pharmacy_open_override')).toEqual([]);
  // "বদলান" → "আমার কাজ"-এর অবস্থার বোতাম
  await pv.getByTestId('state-change').click();
  await expect(page).toHaveURL(/\/work$/);
  await page.goBack();
  // আমার কাজ CONTRACT §২: চতুর্থ ভাগ "ওষুধ খোঁজা"
  await expect(page.getByTestId('queue-segs').getByRole('tab')).toHaveText([/^নতুন/, /^চলছে/, /^শেষ/, /^ওষুধ খোঁজা/]);
  if (page.viewportSize()!.width >= 900) {
    const left = (await page.getByTestId('provider-preview').boundingBox())!;
    const code = (await page.locator('.rxp-codeside').boundingBox())!;
    expect(code.x).toBeGreaterThan(left.x + left.width - 1);
  }
});

test('ডাক্তার: রোগীরা যেভাবে দেখেন এক লাইনে + চেম্বারপ্রতি অবস্থা (শুধু দেখায়, "বদলান" → /work); বুকিং নতুন / চলছে / শেষ', async ({ page }) => {
  const sent: any[] = [];
  page.on('request', (r) => {
    const b = r.url().includes('/api/') ? r.postDataJSON() : null;
    if (b?.action === 'chamber_day_set') sent.push(b);
  });
  await open(page, def('work', 'pro'));
  const pv = page.getByTestId('provider-preview');
  await expect(pv.getByTestId('listing-card')).toHaveCount(0);
  await expect(pv.locator('[data-testid^="sw-chamber-"]')).toHaveCount(0);
  await expect(pv.getByTestId('queue-state').first()).toHaveText('আজ সিরিয়াল চলছে');
  await expect(pv.getByTestId('state-change').first()).toHaveAttribute('href', /\/work$/);
  await pv.getByTestId('preview-see').click();
  await expect(page.getByRole('dialog').getByTestId('listing-card')).toContainText('আজ সিরিয়াল আছে');
  await page.keyboard.press('Escape');
  expect(sent).toEqual([]);
  // বুকিং এখন নিজের ট্যাবে (/work/pro/bookings); /work/pro = আজকের রোগী
  await page.goto('/work/pro/bookings', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('queue-segs').getByRole('tab')).toHaveText([/^নতুন/, /^চলছে/, /^শেষ/]);
});

test('@desktop "আমার কাজ" তালিকা-বিস্তারিত: বুকিং বাঁয়ে, বাছা সিরিয়াল ডানে', async ({ page }) => {
  test.skip(page.viewportSize()!.width < 960, 'ডেস্কটপ');
  await open(page, { ...def('work', 'pro'), path: '/work/pro/bookings' });
  const items = page.getByTestId('q-item');
  await expect(items.first()).toHaveAttribute('aria-current', 'true');
  const n = await items.count();
  if (n > 1) {
    const name = (await items.nth(1).locator('.t-title-s').innerText()).trim();
    await items.nth(1).click();
    await expect(page.locator('.q-detail')).toContainText(name);
  }
  await noSideScroll(page);
});

test('অ্যাম্বুলেন্সের মালিক: "খালি আছি / ট্রিপে আছি" শুধু দেখায় ("বদলান" → /work); ডাক না থাকলে এক লাইন (ভাগ নয়)', async ({ page }) => {
  await open(page, def('work', 'ambulance'));
  const pv = page.getByTestId('provider-preview').first();
  await expect(pv.getByTestId('listing-card')).toHaveCount(0);
  await expect(page.getByTestId('sw-available')).toHaveCount(0);
  await expect(pv.getByTestId('queue-state')).toHaveText('খালি আছি');
  await expect(pv.getByTestId('state-change')).toHaveAttribute('href', /\/work$/);
  await pv.getByTestId('preview-see').click();
  await expect(page.getByRole('dialog').getByTestId('listing-card')).toContainText('খালি আছে');
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('amb-calls-empty')).toBeVisible();
  await expect(page.getByTestId('queue-segs')).toHaveCount(0);
});
