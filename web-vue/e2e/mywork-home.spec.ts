// "আমার কাজ"-এর প্রথম পাতা "আজকের কাজ" আর কাজের পাতার খোলস (mywork CONTRACT §১, §৩) — ৩৯০ আর ১৪৪০-এ, লাইট আর ডার্কে:
// ভূমিকার চিপ (মনে থাকে), খোলা / বিরতি / বন্ধ (বিরতিতে "আবার খুলবে …"), আজকের সংখ্যা (চাপলে বাছা সারি), এখনই দেখুন,
// শুরু করতে যা বাকি, "রোগীরা আপনাকে যেভাবে দেখেন" এক লাইন, "সেবা দিন" (ভূমিকা নেই), ট্যাব পরে এসে সরে না,
// সার্ভারে work_today না থাকলে আগের কাজ থেকে, সুইচ বন্ধে সংখ্যা/সারি নেই; ব্যবসার পাতাতেও একই নিচের মেনু আর উপরে পাতার নাম।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors, type PageDef } from './helpers';

const HOME = def('work', 'overview');
const withTest = (p: PageDef, extra: Record<string, unknown>): PageDef => ({ ...p, test: { ...(p.test ?? {}), ...extra } });
const ALL_OFF = Object.fromEntries(
  ['calls_audio', 'calls_video', 'appt_video', 'chat', 'chat_media', 'online_chamber', 'inv_pharmacy', 'inv_lab', 'inv_chamber', 'inv_ambulance', 'my_expenses', 'desktop',
    'pay_manual', 'pay_chamber_advance', 'pay_delivery', 'pay_lab', 'pay_ambulance', 'pay_due', 'pay_bkash_pgw', 'practice_systems', 'pro_physio', 'pro_psychologist', 'title_guard'].map((k) => [k, false]),
);
const block = (page: Page, role: string) => page.getByTestId(`role-${role}`);
const NO_WORK: Record<string, unknown> = {
  ambulance_mine: { items: [] }, bloodbank_mine: { items: [] }, pharmacy_me: { pharmacy: null }, lab_me: { lab: null }, rx_assisting: { items: [] },
  me: { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 },
};
async function noWork(page: Page) {
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action in NO_WORK) return r.fulfill({ json: NO_WORK[b.action] });
    return r.fallback();
  });
}

for (const theme of ['light', 'dark']) {
  test(`@desktop আজকের কাজ (${theme}): চিপ, অবস্থা, সংখ্যা, এখনই দেখুন, শুরু করতে যা বাকি — পাশে স্ক্রল নেই, বাংলা অঙ্ক`, async ({ page }) => {
    const errs = watchErrors(page);
    await open(page, withTest(HOME, { theme }));
    await expect(page.getByTestId('today-home')).toBeVisible();
    // চিপ: সব + পাঁচ ভূমিকা (চেম্বার · ফার্মেসি · ল্যাব · ব্লাড ব্যাংক · অ্যাম্বুলেন্স)
    const chips = page.getByRole('tablist', { name: 'কোন কাজ দেখবেন' }).getByRole('tab');
    await expect(chips).toHaveCount(6);
    await expect(chips.first()).toHaveText('সব');
    const first = (await chips.first().boundingBox())!;
    expect(first.x, 'প্রথম চিপ কাটা নয়').toBeGreaterThanOrEqual(0);
    // অবস্থা: ফার্মেসি খোলা/বিরতি/বন্ধ; অ্যাম্বুলেন্সে বিরতি নেই; ব্লাড ব্যাংকে স্টক কবে
    const ph = block(page, 'pharmacy');
    await expect(ph.getByRole('group', { name: /অবস্থা/ }).getByRole('button')).toHaveText([/খোলা/, /বিরতি/, /বন্ধ/]);
    await expect(block(page, 'ambulance').getByRole('group', { name: /অবস্থা/ }).first().getByRole('button')).toHaveText([/খালি আছি/, /ট্রিপে আছি/]);
    await expect(block(page, 'bloodbank').getByTestId('stock-line')).toContainText('স্টক হালনাগাদ');
    // আজকের সংখ্যা — বাংলা অঙ্ক, টাকা ৳; চাপলে বাছা সারি
    const tiles = ph.locator('.td-tile');
    await expect(tiles).toHaveCount(4);
    for (const t of await tiles.allInnerTexts()) expect(t).not.toMatch(/[0-9]/);
    await expect(ph.getByTestId('tile-salesTodayTk')).toContainText('৳১,৬৬৪');
    await expect(ph.getByTestId('tile-active')).toHaveAttribute('href', /\/work\/pharmacy\/orders\?s=active$/);
    // প্যাথলজি যাচাইয়ে: সংখ্যার বদলে "শুরু করতে যা বাকি" (✓ সহ)
    await expect(block(page, 'lab').getByTestId('setup-list')).toContainText('শুরু করতে যা বাকি');
    await expect(block(page, 'lab').locator('.td-tile')).toHaveCount(0);
    // এখনই দেখুন: ৫টা, প্রতিটায় একটাই বোতাম; বাকি "সব দেখুন"
    const rows = page.getByTestId('today-tasks').locator('.td-task');
    await expect(rows).toHaveCount(5);
    for (let i = 0; i < 5; i++) await expect(rows.nth(i).getByRole('button')).toHaveCount(1);
    await page.getByTestId('tasks-all').click();
    await expect(rows).toHaveCount(6);
    // "রোগীরা আপনাকে যেভাবে দেখেন": এক লাইন করে, "দেখুন" চাপলে পুরো কার্ড
    await page.getByTestId('preview-lines').getByTestId('preview-see').nth(1).click();
    await expect(page.getByRole('dialog').getByTestId('listing-card')).toContainText('নিউ লাইফ ফার্মেসি');
    await page.keyboard.press('Escape');
    await noSideScroll(page);
    expect(errs).toEqual([]);
  });
}

test('@desktop চিপ: একটা ভূমিকা বাছলে শুধু সেটা, আবার খুললেও মনে থাকে', async ({ page }) => {
  await open(page, HOME);
  await page.getByRole('tab', { name: /ফার্মেসি/ }).click();
  await expect(block(page, 'pharmacy')).toBeVisible();
  await expect(block(page, 'chamber')).toHaveCount(0);
  // এখনই দেখুন — শুধু ফার্মেসির
  await expect(page.getByTestId('today-tasks').locator('.td-task[data-kind="chamber"]')).toHaveCount(0);
  // অন্য পাতায় গিয়ে ফিরে এলে (পাতা নতুন করে বসে, ব্রাউজারে রাখা বাছাই পড়ে) — রিলোডে পরীক্ষার init script সব মুছে দেয়
  const soft = (to: string) => page.evaluate((t) => { history.pushState({}, '', t); dispatchEvent(new PopStateEvent('popstate', { state: {} })); }, to);
  await soft('/health');
  await expect(page.getByTestId('today-home')).toHaveCount(0);
  expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('lf.work.role.')).map((k) => localStorage.getItem(k)))).toEqual(['pharmacy']);
  await soft('/work');
  await expect(page.getByRole('tab', { name: /ফার্মেসি/ })).toHaveAttribute('aria-selected', 'true');
  await expect(block(page, 'chamber')).toHaveCount(0);
});

test('@desktop বিরতি: ১ ঘণ্টা → কমলা "আবার খুলবে …" (সার্ভারে until), আবার খোলা', async ({ page }) => {
  // এই পরীক্ষার নিজের অবস্থা (নকল সার্ভারের ফার্মেসি অন্য পরীক্ষার সাথে ভাগ করা নয়)
  let st: { status: string; pausedUntil: number | null } = { status: 'open', pausedUntil: null };
  const sent: any[] = [];
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action === 'work_today') {
      const j = await (await r.fetch()).json();
      j.items = j.items.map((x: any) => (x.kind === 'pharmacy' ? { ...x, ...st } : x));
      return r.fulfill({ json: j });
    }
    if (b.action !== 'pharmacy_open_override') return r.fallback();
    sent.push(b);
    st = b.open ? { status: 'open', pausedUntil: null } : b.until ? { status: 'paused', pausedUntil: b.until } : { status: 'closed', pausedUntil: null };
    return r.fulfill({ json: { ok: true, openNow: !!b.open, openOverride: null } });
  });
  await open(page, HOME);
  const ctl = block(page, 'pharmacy').getByRole('group', { name: /অবস্থা/ });
  await expect(ctl.getByRole('button', { name: /খোলা/ })).toHaveAttribute('aria-pressed', 'true');
  await ctl.getByRole('button', { name: /বিরতি/ }).click();
  const opts = page.getByTestId('pause-options');
  await expect(opts.getByRole('button')).toHaveText(['৩০ মিনিট', '১ ঘণ্টা', 'আজকের মতো', 'বাতিল']);
  const t0 = Date.now();
  await opts.getByRole('button', { name: '১ ঘণ্টা' }).click();
  await expect(ctl.getByRole('button', { name: /বিরতি/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(block(page, 'pharmacy').getByTestId('status-note')).toContainText('আবার খুলবে');
  expect(sent[0].open).toBe(false);
  expect(sent[0].until).toBeGreaterThan(t0 + 55 * 60_000);
  expect(sent[0].until).toBeLessThan(t0 + 65 * 60_000);
  await ctl.getByRole('button', { name: /বন্ধ/ }).click();
  await expect(block(page, 'pharmacy').getByTestId('status-note')).toContainText('বন্ধ');
  await ctl.getByRole('button', { name: /খোলা/ }).click();
  await expect(block(page, 'pharmacy').getByTestId('status-note')).toHaveCount(0);
  expect(sent.map((b) => b.open)).toEqual([false, false, true]);
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

test('অ্যাম্বুলেন্স: খালি/ট্রিপে আর "আবার চালু রাখুন" (খালির শেষ ঘণ্টায়) শুধু এখানে — এক চাপে ambulance_available', async ({ page }) => {
  const sent: any[] = [];
  // নকল সার্ভারের অবস্থা না বদলাতে উত্তর এখানেই
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action === 'ambulance_available') {
      sent.push(b);
      return r.fulfill({ json: { ok: true } });
    }
    return r.fallback();
  });
  await open(page, HOME);
  const amb = block(page, 'ambulance');
  await expect(amb.getByText(/খালি আছে — আর [০-৯]+ মিনিট/)).toBeVisible();
  await amb.getByRole('button', { name: 'আবার চালু রাখুন' }).click();
  await expect.poll(() => sent.at(-1)).toMatchObject({ id: 'amb1', available: true });
  await noSideScroll(page);
});

test('সার্ভারে work_today নেই (পুরোনো সার্ভার): আগের কাজ থেকে একই পাতা (সংখ্যা, এখনই দেখুন)', async ({ page }) => {
  const errs = watchErrors(page);
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action === 'work_today') return r.fulfill({ status: 400, json: { error: 'অজানা অনুরোধ। অ্যাপ আপডেট করুন।' } });
    return r.fallback();
  });
  await open(page, HOME);
  // নকল সার্ভারের অর্ডার অন্য পরীক্ষাও বদলায় — সংখ্যা নয়, ঘরটা আর বাংলা অঙ্ক
  await expect(block(page, 'pharmacy').getByTestId('tile-new')).toContainText(/[০-৯]/);
  await expect(block(page, 'chamber').getByTestId('tile-today')).toBeVisible();
  await expect(page.getByTestId('today-tasks').locator('.td-task[data-kind="pharmacy"]').first()).toContainText('প্রেসক্রিপশন');
  expect(errs.filter((e) => !/400/.test(e))).toEqual([]);
});

test('সুইচ বন্ধ: সার্ভার যে সংখ্যা/কাজ দেয় না তা নেই, পেমেন্ট আর Windows অ্যাপের সারিও নেই — পাতা ভাঙে না', async ({ page }) => {
  const errs = watchErrors(page);
  // সার্ভার বন্ধ সুইচের চাবি পাঠায় না (এখানে দোকানের বিক্রি আর ওষুধ খোঁজা)
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action !== 'work_today') return r.fallback();
    const j = await (await r.fetch()).json();
    j.items = j.items.map((x: any) => (x.kind === 'pharmacy' ? { ...x, counts: { new: 2, active: 1 }, tasks: x.tasks.filter((t: any) => t.type !== 'find') } : x));
    return r.fulfill({ json: j });
  });
  await open(page, withTest(HOME, { features: ALL_OFF }));
  await expect(block(page, 'pharmacy').locator('.td-tile')).toHaveCount(2);
  await expect(block(page, 'pharmacy').getByTestId('tile-salesTodayTk')).toHaveCount(0);
  await expect(page.getByTestId('today-tasks')).not.toContainText('কেউ খুঁজছেন');
  await expect(page.getByTestId('work-pages')).toBeVisible();
  await expect(page.getByTestId('pay-biz-link')).toHaveCount(0);
  await expect(page.getByTestId('desktop-app-link')).toHaveCount(0);
  await noSideScroll(page);
  expect(errs).toEqual([]);
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

test('সুইচ চালু: পেমেন্ট আর Windows অ্যাপের সারি থাকে', async ({ page }) => {
  await open(page, HOME);
  await expect(page.getByTestId('pay-biz-link')).toBeVisible();
  await expect(page.getByTestId('desktop-app-link')).toBeVisible();
});

test('ভূমিকা নেই: নিচের মেনুতে "সেবা দিন" — কারা যোগ দিতে পারেন, প্রতিটার পথ', async ({ page }) => {
  await noWork(page);
  await open(page, HOME);
  await expect(page.locator('.bottom-nav a')).toHaveCount(5);
  await expect(page.locator('.bottom-nav a').last()).toHaveText('সেবা দিন');
  const intro = page.getByTestId('serve-intro');
  await expect(intro).toContainText('Lifolo-তে সেবা দিন');
  for (const k of ['doctor', 'pharmacy', 'lab', 'bloodbank', 'ambulance']) await expect(intro.getByTestId(`serve-${k}`)).toBeVisible();
  await intro.getByTestId('serve-pharmacy').click();
  await expect(page).toHaveURL(/\/work\/pharmacy\/new$/);
});

test('অতিথিও "সেবা দিন" দেখেন; চাপলে লগইন চায়', async ({ page }) => {
  await open(page, { name: 'guest', path: '/work', test: { lang: 'bn' } });
  await expect(page.locator('.bottom-nav a').last()).toHaveText('সেবা দিন');
  await page.getByTestId('serve-lab').click();
  await expect(page.getByText('সেবা দিতে লগইন করুন')).toBeVisible();
});

test('ট্যাব পরে এসে সরে না: কাজ আসার আগেই ৫টা ট্যাব, পাতায় কঙ্কাল (ঘুরন্ত চাকা নয়)', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  await page.route('**/api/community', async (r) => {
    const b = JSON.parse(r.request().postData() || '{}');
    if (b.action === 'ambulance_mine') await gate;
    return r.fallback();
  });
  await page.addInitScript((t: any) => {
    (window as any).__lfTest = t;
    try {
      localStorage.clear();
      localStorage.setItem('lf.lang', 'bn');
    } catch {}
  }, HOME.test);
  await page.goto('/work');
  await expect(page.getByTestId('work-skeleton')).toBeVisible();
  await expect(page.locator('.bottom-nav a')).toHaveCount(5);
  const before = await page.locator('.bottom-nav a').nth(4).boundingBox();
  release();
  await expect(page.getByTestId('today-home')).toBeVisible();
  await expect(page.locator('.bottom-nav a')).toHaveCount(5);
  expect(await page.locator('.bottom-nav a').nth(4).boundingBox()).toEqual(before);
});

test('ব্যবসার পাতায় একই খোলস: উপরে পাতার নাম আর পেছনে, নিচে একই মেনু', async ({ page }) => {
  await open(page, HOME, '/work/pharmacy/orders');
  await expect(page.locator('.topbar .page-title')).toHaveText('আমার ফার্মেসি');
  await expect(page.locator('.topbar .page-title')).toBeVisible();
  await expect(page.locator('.bottom-nav')).toBeVisible();
  await expect(page.locator('.bottom-nav a[aria-current="page"]')).toHaveText('আমার কাজ');
  await page.getByTestId('top-back').click();
  await expect(page).toHaveURL(/\/work$/);
  await expect(page.locator('.topbar .page-title')).toHaveText('আজকের কাজ');
  await expect(page.getByTestId('top-back')).toHaveCount(0);
  // ডাক্তারের পাতাতেও একই
  await page.goto('/work/pro');
  await expect(page.locator('.topbar .page-title')).toHaveText('আমার চেম্বার');
  await expect(page.locator('.bottom-nav')).toBeVisible();
});

test('@desktop বড় পর্দা: বাঁ মেনুতে ভূমিকার ভাগ; বিক্রি এক চাপে; ব্যবসার পাতায় শিরোনাম উপরের বারে', async ({ page }) => {
  test.skip(page.viewportSize()!.width < 960, 'ডেস্কটপ');
  await open(page, HOME);
  const nav = page.getByTestId('work-nav');
  await expect(nav.getByRole('link')).toHaveText(['আজকের কাজ', 'চেম্বার', 'প্রেসক্রিপশন', 'ফার্মেসি', 'বিক্রি করুন', 'ল্যাব', 'ব্লাড ব্যাংক', 'অ্যাম্বুলেন্স', 'পেমেন্ট']);
  await expect(nav.getByRole('link', { name: 'আজকের কাজ' })).toHaveAttribute('aria-current', 'page');
  // "এখনই দেখুন" প্রথম পর্দাতেই (ডানে)
  const tasks = (await page.getByTestId('today-tasks').boundingBox())!;
  expect(tasks.y).toBeLessThan(page.viewportSize()!.height);
  await nav.getByRole('link', { name: 'বিক্রি করুন' }).click();
  await expect(page).toHaveURL(/\/work\/pharmacy\/shop\/sales$/);
  await expect(page.locator('.topbar .page-title')).toHaveText('আমার ফার্মেসি');
  await expect(page.getByTestId('top-back')).toBeHidden();
  // বিক্রির ঘর পুরো উচ্চতা (নিচে বড় ফাঁকা নয়)
  const pos = (await page.locator('.rxp-panel.bk-pos').boundingBox())!;
  expect(pos.height).toBeGreaterThan(page.viewportSize()!.height * 0.55);
  await page.goto('/work/bloodbank');
  await expect(page.locator('.topbar .page-title')).toHaveText('আমার ব্লাড ব্যাংক');
  // ব্লাড ব্যাংক: দুই ভাগ — ডানে তথ্যের কার্ড, বোতামে লেখা
  await expect(page.getByRole('button', { name: 'বদলান' })).toBeVisible();
  // মোছা "আরও" মেনুতে (আমার কাজ CONTRACT §২ — মূল বোতামের পাশে নয়), আইকনের সাথে লেখা
  await page.getByTestId('row-more').first().click();
  await expect(page.getByRole('menuitem', { name: 'এই ব্লাড ব্যাংক সরিয়ে দিন' })).toBeVisible();
});

test('ফোন: চিপের সারি পাশে স্ক্রল করে, পাতা নয়; প্রথম পর্দায় অবস্থা আর সংখ্যা', async ({ page }) => {
  test.skip(page.viewportSize()!.width > 500, 'ফোন');
  await open(page, HOME);
  await expect(block(page, 'chamber')).toBeVisible();
  await noSideScroll(page);
  const tile = (await block(page, 'chamber').locator('.td-tile').first().boundingBox())!;
  expect(tile.y + tile.height).toBeLessThan(844 - 60);
  // বোতামগুলো ৪৮px-এর কম নয়
  const btn = (await block(page, 'pharmacy').getByRole('button', { name: /বিরতি/ }).boundingBox())!;
  expect(btn.height).toBeGreaterThanOrEqual(47.5);
});
