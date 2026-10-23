// আমার প্রেসক্রিপশন: প্রতিটা কার্ডে এখন কোন অবস্থায় (রঙিন চিপ + পরীক্ষা/ফলো-আপের ছোট লাইন), উপরে তিন ট্যাব
// চলমান (শুরুতে) / পূর্বের / সব — গুনতিসহ, ভেতরে টাইমলাইন। সার্ভারের state (backend/docs/RX-STATE.md) নকল করে।
// ৩৭৫px আর ডেস্কটপ; RX_SHOTS দিলে ছবি সেখানে।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const D = 24 * 3600 * 1000;
const now = Date.now();
const today = (() => {
  const d = new Date(now + 6 * 3600 * 1000);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
})();
const shot = async (page: Page, name: string) => {
  if (process.env.RX_SHOTS) await page.screenshot({ path: `${process.env.RX_SHOTS}/${name}.png`, fullPage: true });
};

const base = async (route: Route) => (await route.fetch()).json();
type State = Record<string, unknown>;
const st = (o: State): State => ({ phase: 'active', tone: 'neutral', cancelled: false, cancelledAt: null, medicine: null, lab: null, followUpDate: null, lastActivityAt: now, ...o });
const med = (state: string, bought = 0, total = 3, extra: State = {}) => ({ state, bought, total, pharmacyName: null, mode: null, ...extra });

/** নকল তালিকা: নকল সার্ভারের rx1 থেকে কয়েকটা, প্রতিটার আলাদা অবস্থা (নতুনটা আগে নয় — পাতা নিজে সাজায়) */
function items(rx1: any): any[] {
  const mk = (id: string, ago: number, name: string, state: State, extra: State = {}) => ({ ...rx1, id, code: `${id.toUpperCase()}23456789`.slice(0, 10), createdAt: now - ago * D, doctor: { ...rx1.doctor, name }, state, ...extra });
  return [
    mk('rxa', 30, 'ডা. সাবরিনা হক', st({ phase: 'past', primary: 'all_bought', tone: 'good', medicine: med('all_bought', 3, 3) })),
    mk('rxb', 1, 'ডা. ফারহানা ইয়াসমিন', st({ primary: 'sent', tone: 'primary', medicine: med('sent', 0, 3, { pharmacyName: 'লাইফ কেয়ার ফার্মেসি', mode: 'shop' }), lab: { state: 'pending', orders: 0, reported: 0, labName: null, reviewed: false }, followUpDate: today + 14 * D })),
    mk('rxc', 6, 'ডা. তানভীর রহমান', st({ primary: 'partial', tone: 'info', medicine: med('partial', 3, 5), lab: { state: 'received', orders: 1, reported: 0, labName: 'পপুলার', reviewed: false } })),
    mk('rxd', 12, 'ডা. মাহমুদুল হাসান', st({ primary: 'running_out', tone: 'error', medicine: med('running_out', 2, 3) })),
    mk('rxe', 3, 'ডা. নুসরাত জাহান', st({ primary: 'out', tone: 'primary', medicine: med('out', 0, 3, { mode: 'online' }), lab: { state: 'reported', orders: 1, reported: 1, labName: 'সিটি', reviewed: true } })),
    mk('rxf', 60, 'ডা. কামরুল ইসলাম', st({ phase: 'past', primary: 'cancelled', tone: 'muted', cancelled: true, cancelledAt: now - 59 * D, medicine: med('not_bought') }), { status: 'cancelled' }),
    mk('rxg', 40, 'ডা. রেহানা পারভীন', st({ phase: 'past', primary: 'lab_reported', tone: 'good', lab: { state: 'reported', orders: 1, reported: 1, labName: 'সিটি', reviewed: false } }), { medicines: [] }),
  ];
}
const TIMELINE = [
  { kind: 'written', at: now - D },
  { kind: 'pharmacy_sent', at: now - D + 3600_000, name: 'লাইফ কেয়ার ফার্মেসি', mode: 'shop' },
  { kind: 'lab_sent', at: now - 5 * 3600_000, name: 'সিটি ডায়াগনস্টিক সেন্টার' },
  { kind: 'follow_up', at: today + 14 * D, ahead: true },
];

async function mock(page: Page, make: (rx1: any) => any[] = items) {
  let list: any[] = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'rx_mine') {
      const j = await base(route);
      list = make(j.items[0]);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: list }) });
    }
    if (body.action === 'rx_get' && list.some((r) => r.id === body.id)) {
      const r = list.find((x) => x.id === body.id);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ item: { ...r, state: { ...r.state, timeline: TIMELINE } } }) });
    }
    return route.continue();
  });
}
const listPage = (lang = 'bn') => {
  const d = def('health', 'rx-list');
  return { ...d, test: { ...d.test, lang } };
};
const cards = (page: Page) => page.locator('article.card');

test.describe('আমার প্রেসক্রিপশন: অবস্থা আর ট্যাব (৩৭৫px)', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('চলমান শুরুতে খোলা, গুনতি বাংলা অঙ্কে; প্রতিটা কার্ডে রঙিন চিপ আর ছোট লাইন; নতুনটা আগে', async ({ page }) => {
    const errs = watchErrors(page);
    await mock(page);
    await open(page, listPage());
    await expect(page.getByRole('tab', { name: 'চলমান (৪)' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tab', { name: 'পূর্বের (৩)' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'সব (৭)' })).toBeVisible();
    const chips = page.getByTestId('rx-status-chip');
    await expect(chips).toHaveText(['ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা', 'ডেলিভারির পথে', 'আংশিক কেনা (৩/৫)', 'ওষুধ শেষ হয়ে আসছে']);
    // রং থিমের: আংশিক = secondary, শেষ হয়ে আসছে = error
    await expect(chips.nth(2)).toHaveClass(/info/);
    await expect(chips.nth(3)).toHaveClass(/error/);
    // পরীক্ষা আর ফলো-আপের ছোট লাইন
    const first = cards(page).first();
    await expect(first.getByTestId('rx-status-sub')).toHaveText(['পরীক্ষা বাকি', /^ফলো-আপ: /]);
    await expect(cards(page).nth(1).getByTestId('rx-status-sub')).toHaveText(['রিপোর্ট এসেছে · ডাক্তার দেখেছেন']);
    await expect(cards(page).nth(2).getByTestId('rx-status-sub')).toHaveText(['নমুনা নেওয়া হয়েছে']);
    // বাতিলটা চলমানে নেই
    await expect(page.getByText('বাতিল করেছেন ডাক্তার')).toHaveCount(0);
    await noSideScroll(page);
    await shot(page, 'web-rx-active-375');
    expect(errs).toEqual([]);
  });

  test('পূর্বের: শেষ আর বাতিল (ধূসর, "বাতিল"); সব: সবগুলো, নতুনটা আগে; ঠিকানায় ট্যাব থাকে', async ({ page }) => {
    await mock(page);
    await open(page, listPage());
    await page.getByRole('tab', { name: 'পূর্বের (৩)' }).click();
    await expect(page).toHaveURL(/tab=past/);
    await expect(page.getByTestId('rx-status-chip')).toHaveText(['সব ওষুধ কেনা হয়েছে', 'রিপোর্ট এসেছে', 'বাতিল করেছেন ডাক্তার']);
    const cancelled = cards(page).filter({ hasText: 'বাতিল করেছেন ডাক্তার' });
    await expect(cancelled).toHaveClass(/rx-cancelled/);
    await expect(cancelled.getByText('বাতিল', { exact: true })).toBeVisible();
    await noSideScroll(page);
    await shot(page, 'web-rx-past-375');
    await page.getByRole('tab', { name: 'সব (৭)' }).click();
    await expect(cards(page)).toHaveCount(7);
    const names = await cards(page).locator('.t-title-m').allInnerTexts();
    expect(names[0]).toBe('ডা. ফারহানা ইয়াসমিন');
    expect(names.at(-1)).toBe('ডা. কামরুল ইসলাম');
    // ফিরে এলেও একই ট্যাব (ঠিকানায়)
    await page.reload({ waitUntil: 'networkidle' });
    await expect(page.getByRole('tab', { name: 'সব (৭)' })).toHaveAttribute('aria-selected', 'true');
  });

  test('চলমান খালি: তবু চলমান খোলে, ছোট বার্তা আর "পূর্বেরগুলো দেখুন"', async ({ page }) => {
    await mock(page, (rx1) => items(rx1).filter((r) => r.state.phase === 'past'));
    await open(page, listPage());
    await expect(page.getByRole('tab', { name: 'চলমান (০)' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('rx-tab-empty')).toContainText('এখন চলমান কোনো প্রেসক্রিপশন নেই।');
    await page.getByRole('button', { name: /পূর্বেরগুলো দেখুন/ }).click();
    await expect(page.getByRole('tab', { name: 'পূর্বের (৩)' })).toHaveAttribute('aria-selected', 'true');
    await expect(cards(page)).toHaveCount(3);
  });

  test('ভেতরে: এখন কোন অবস্থায় + টাইমলাইন তারিখসহ', async ({ page }) => {
    const errs = watchErrors(page);
    await mock(page);
    await open(page, listPage(), '/services/pharmacy/prescriptions/rxb');
    const tl = page.getByTestId('rx-timeline');
    await expect(tl.getByTestId('rx-status-chip')).toHaveText('ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা');
    const steps = tl.locator('li');
    await expect(steps).toHaveCount(4);
    await expect(steps.nth(0)).toContainText('প্রেসক্রিপশন লেখা হয়েছে');
    await expect(steps.nth(1)).toContainText('ফার্মেসিতে পাঠানো — লাইফ কেয়ার ফার্মেসি');
    await expect(steps.nth(2)).toContainText('ল্যাবে পরীক্ষা পাঠানো — সিটি ডায়াগনস্টিক সেন্টার');
    await expect(steps.nth(3)).toContainText('ফলো-আপে দেখানোর দিন');
    await expect(steps.nth(3)).toHaveClass(/ahead/);
    // প্রতিটা ধাপে তারিখ (বাংলা মাস)
    for (let i = 0; i < 4; i++) await expect(steps.nth(i).locator('.when')).toHaveText(/[০-৯]+ \S+ [০-৯]{4}/);
    await noSideScroll(page);
    await shot(page, 'web-rx-detail-375');
    expect(errs).toEqual([]);
  });

  test('ইংরেজি: Ongoing / Previous / All', async ({ page }) => {
    await mock(page);
    await open(page, listPage('en'));
    await expect(page.getByRole('tab', { name: 'Ongoing (4)' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tab', { name: 'Previous (3)' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'All (7)' })).toBeVisible();
    await expect(page.getByTestId('rx-status-chip').first()).toHaveText('Sent to Pharmacy — Waiting for Reply');
    await noSideScroll(page);
  });

  test('পুরোনো সার্ভার (state নেই): ট্যাব তবু চলে — বাতিল পূর্বেরতে, চলমানে চিপ নেই', async ({ page }) => {
    await mock(page, (rx1) => items(rx1).map(({ state: _s, ...r }) => r));
    await open(page, listPage());
    await expect(page.getByRole('tab', { name: 'চলমান (৬)' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('rx-status-chip')).toHaveCount(0);
    await page.getByRole('tab', { name: 'পূর্বের (১)' }).click();
    await expect(page.getByTestId('rx-status-chip')).toHaveText(['বাতিল করেছেন ডাক্তার']);
  });

  test('অন্ধকার থিমে পড়া যায় (চিপের লেখা আর পটভূমি আলাদা)', async ({ page }) => {
    await mock(page);
    const d = listPage();
    await open(page, { ...d, test: { ...d.test, theme: 'dark' } });
    const chip = page.getByTestId('rx-status-chip').first();
    await expect(chip).toBeVisible();
    const [fg, bg] = await chip.evaluate((el) => [getComputedStyle(el).color, getComputedStyle(el).backgroundColor]);
    expect(fg).not.toBe(bg);
    await shot(page, 'web-rx-active-375-dark');
  });
});

test('ডেস্কটপ: তালিকা আর পাশে টাইমলাইন @desktop', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'শুধু ১৪৪০ প্রস্থে');
  const errs = watchErrors(page);
  await mock(page);
  await open(page, listPage(), '/services/pharmacy/prescriptions/rxb');
  await expect(page.getByRole('tab', { name: 'চলমান (৪)' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByTestId('rx-status-chip').first()).toBeVisible();
  await expect(page.getByTestId('rx-timeline').locator('li')).toHaveCount(4);
  await noSideScroll(page);
  await shot(page, 'web-rx-desktop');
  expect(errs).toEqual([]);
});
