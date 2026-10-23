// ওষুধ খুঁজুন — কতটা লাগবে (backend/docs/MEDFIND.md)। রোগী: ওষুধ বেছে − সংখ্যা + আর ধরন অনুযায়ী একক (ট্যাবলেট: পাতা/পিস,
// সিরাপ: বোতল), med_find-এ qty+unit যায়; "আমার খোঁজ"-এ পুরোটা থাকা দোকান আগে, কম থাকলে "আপনার দরকার …"।
// ফার্মেসি: "৩ পাতা লাগবে", কতটা আছে লিখে পাঠান (নেই বোতাম নেই), চুপচাপ "বাদ দিন", উত্তর বদলানো।
// ৩৭৫px আর ডেস্কটপ; MF_SHOTS দিলে ছবি সেখানে।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const shot = async (page: Page, name: string) => {
  if (process.env.MF_SHOTS) await page.screenshot({ path: `${process.env.MF_SHOTS}/${name}.png`, fullPage: true });
};
const withLang = (part: string, name: string, lang = 'bn') => {
  const d = def(part, name);
  return { ...d, test: { ...d.test, lang } };
};

/** med_find / med_find_answer-এ যা গেল তা ধরা (বাকিটা নকল সার্ভার দেয়) */
async function capture(page: Page, action: string, reply: (b: any) => unknown = () => ({ ok: true, id: 'mfx', notified: 4 })) {
  const sent: any[] = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action !== action) return route.continue();
    sent.push(body);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(reply(body)) });
  });
  return sent;
}

async function pickDrug(page: Page, q: string, title: RegExp) {
  await page.getByLabel('ওষুধের নাম').fill(q);
  await page.locator('.services-drug').filter({ hasText: title }).first().click();
  await expect(page.getByTestId('medfind-qty')).toBeVisible();
}

const FINDS = (now: number) => [
  { id: 'r1', drug: { name: 'Napa', generic: 'Paracetamol 500 mg', form: 'Tablet', company: 'Beximco' }, area: 'চরপাড়া', km: 0.8, answer: null, createdAt: now - 10 * 60_000, qty: 3, unit: 'strip', haveQty: null, haveUnit: null },
  { id: 'r2', drug: { name: 'Sergel', generic: 'Esomeprazole 20 mg', form: 'Capsule', company: 'Healthcare' }, area: 'গাঙ্গিনাপাড়', km: 1.6, answer: 'yes', createdAt: now - 40 * 60_000, qty: 14, unit: 'pcs', haveQty: 10, haveUnit: 'pcs' },
];

/** ফার্মেসির "ওষুধ খোঁজা" — নকল তালিকা আর উত্তর (পাঠানো উত্তর তালিকায় বসে) */
async function pharmacyMock(page: Page) {
  let items = FINDS(Date.now());
  const sent: any[] = [];
  await page.route('**/api/community', async (route) => {
    const b = route.request().postDataJSON() || {};
    if (b.action === 'med_find_requests') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items, findOff: false }) });
    if (b.action === 'med_find_answer') {
      sent.push(b);
      if (b.dismiss) items = items.filter((x) => x.id !== b.id);
      else items = items.map((x) => (x.id === b.id ? { ...x, answer: 'yes', haveQty: b.haveQty, haveUnit: x.unit } : x));
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
    }
    return route.continue();
  });
  return sent;
}

test.describe('ওষুধ খুঁজুন — কতটা লাগবে (৩৭৫px)', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('ট্যাবলেট: শুরুতে ১ পাতা; − + আর লিখে বদলানো, পিস বাছা; ১–৯৯৯; পাঠালে qty আর unit যায়', async ({ page }) => {
    const errs = watchErrors(page);
    const sent = await capture(page, 'med_find');
    await open(page, withLang('services', 'med-user'));
    await pickDrug(page, 'Napa', /^Napa · Tablet/);
    const box = page.getByTestId('qty-input');
    await expect(page.getByText('কতটা লাগবে', { exact: true })).toBeVisible();
    await expect(box).toHaveValue('১');
    await expect(page.getByTestId('qty-unit-strip')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('qty-unit-pcs')).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByTestId('qty-minus')).toBeDisabled();
    await page.getByTestId('qty-plus').click();
    await page.getByTestId('qty-plus').click();
    await expect(box).toHaveValue('৩');
    await shot(page, 'medfind-qty-picker-375');
    await noSideScroll(page);
    // পিস, তারপর লিখে ১৫, এক কম
    await page.getByTestId('qty-unit-pcs').click();
    await expect(page.getByTestId('qty-unit-pcs')).toHaveAttribute('aria-pressed', 'true');
    await box.fill('15');
    await page.getByTestId('qty-minus').click();
    await expect(box).toHaveValue('১৪');
    // ১০০০ নেয় না (৯৯৯-এর বেশি) — আগের সংখ্যা থাকে; বাংলা অঙ্কে লেখা চলে
    await box.fill('0');
    await box.blur();
    await expect(box).toHaveValue('১৪');
    await box.fill('২০');
    await box.blur();
    await expect(box).toHaveValue('২০');
    await page.getByRole('button', { name: 'কাছের দোকানগুলোকে জিজ্ঞেস করুন' }).click();
    await expect(page.getByText(/৪টা দোকানকে জিজ্ঞেস করা হয়েছে/)).toBeVisible();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ qty: 20, unit: 'pcs' });
    expect(errs).toEqual([]);
  });

  test('সিরাপ: একক শুধু বোতল (বাছার কিছু নেই); ইংরেজিতে "How Much Do You Need"', async ({ page }) => {
    const sent = await capture(page, 'med_find');
    await open(page, withLang('services', 'med-user'));
    await pickDrug(page, 'Napa', /^Napa · Syrup/);
    await expect(page.getByTestId('qty-unit-bottle')).toHaveText('বোতল');
    await expect(page.locator('.medfind-units')).toHaveCount(0);
    await page.getByTestId('qty-plus').click();
    await page.getByRole('button', { name: 'কাছের দোকানগুলোকে জিজ্ঞেস করুন' }).click();
    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0]).toMatchObject({ qty: 2, unit: 'bottle' });
  });

  test('ইংরেজি: একই বাছাই, Strip/Pieces', async ({ page }) => {
    await open(page, withLang('services', 'med-user', 'en'));
    await page.getByLabel('Medicine name').fill('Napa');
    await page.locator('.services-drug').filter({ hasText: /^Napa · Tablet/ }).first().click();
    await expect(page.getByText('How Much Do You Need', { exact: true })).toBeVisible();
    await expect(page.getByTestId('qty-unit-strip')).toHaveText('Strip');
    await expect(page.getByTestId('qty-unit-pcs')).toHaveText('Pieces');
    await expect(page.getByTestId('qty-input')).toHaveValue('1');
    await shot(page, 'medfind-qty-picker-375-en');
    await noSideScroll(page);
  });

  test('আমার খোঁজ: কতটা লাগবে; পুরোটা থাকা দোকান আগে, কম থাকলে "আপনার দরকার …"; "নেই" কোথাও নেই', async ({ page }) => {
    const errs = watchErrors(page);
    await open(page, withLang('services', 'med-user'));
    const card = page.locator('.card').filter({ hasText: 'Napa Extend' }).first();
    await expect(card.getByTestId('find-need')).toHaveText('১০ পিস লাগবে');
    await expect(card.getByTestId('find-reply')).toHaveText(['আছে — ১২ পিস · পুরোটা আছে', 'আছে — ৪ পিস (আপনার দরকার ১০)']);
    await expect(card.getByText('লাইফ কেয়ার ফার্মেসি')).toBeVisible();
    await expect(card.getByText(/নেই/)).toHaveCount(0);
    await shot(page, 'medfind-replies-375');
    await noSideScroll(page);
    expect(errs).toEqual([]);
  });

  test('ফার্মেসি: "৩ পাতা লাগবে", কতটা আছে লিখে পাঠান; ভুল সংখ্যা আটকায়; "নেই" বোতাম নেই', async ({ page }) => {
    const errs = watchErrors(page);
    const sent = await pharmacyMock(page);
    await open(page, withLang('work', 'pharmacy'), '/work/pharmacy/finds');
    const row = page.locator('.work-find').filter({ hasText: 'Napa' });
    await expect(row.getByTestId('find-need')).toHaveText('৩ পাতা লাগবে');
    await expect(row.getByTestId('find-have-unit')).toHaveText('পাতা');
    await expect(row.getByRole('button', { name: 'নেই', exact: true })).toHaveCount(0);
    await expect(row.getByTestId('find-dismiss')).toHaveText('বাদ দিন');
    await shot(page, 'medfind-pharmacy-answer-375');
    await noSideScroll(page);
    await row.getByTestId('find-have-qty').fill('0');
    await row.getByTestId('find-have-send').click();
    await expect(row.getByText('১ থেকে ৯৯৯৯-এর মধ্যে একটা সংখ্যা লিখুন')).toBeVisible();
    expect(sent).toHaveLength(0);
    await row.getByTestId('find-have-qty').fill('৫');
    await row.getByTestId('find-have-send').click();
    await expect(row.getByTestId('find-said')).toHaveText('আপনার উত্তর: আছে — ৫ পাতা');
    expect(sent).toMatchObject([{ action: 'med_find_answer', id: 'r1', haveQty: 5 }]);
    // আগে উত্তর দেওয়া: বদলান → আগের সংখ্যা ঘরে, নতুন সংখ্যা যায়
    const r2 = page.locator('.work-find').filter({ hasText: 'Sergel' });
    await expect(r2.getByTestId('find-said')).toHaveText('আপনার উত্তর: আছে — ১০ পিস');
    await r2.getByRole('button', { name: 'বদলান' }).click();
    await expect(r2.getByTestId('find-have-qty')).toHaveValue('10');
    await expect(r2.getByTestId('find-dismiss')).toHaveCount(0);
    await r2.getByTestId('find-have-qty').fill('1200');
    await r2.getByTestId('find-have-send').click();
    await expect(r2.getByTestId('find-said')).toHaveText('আপনার উত্তর: আছে — ১২০০ পিস');
    expect(sent[1]).toMatchObject({ id: 'r2', haveQty: 1200 });
    expect(errs).toEqual([]);
  });

  test('ফার্মেসি: "বাদ দিন" শুধু নিজের তালিকা থেকে সরায় (রোগীকে কিছু যায় না)', async ({ page }) => {
    const sent = await pharmacyMock(page);
    await open(page, withLang('work', 'pharmacy'), '/work/pharmacy/finds');
    await page.locator('.work-find').filter({ hasText: 'Napa' }).getByTestId('find-dismiss').click();
    await expect(page.locator('.work-find').filter({ hasText: 'Napa' })).toHaveCount(0);
    await expect(page.locator('.work-find').filter({ hasText: 'Sergel' })).toBeVisible();
    expect(sent).toMatchObject([{ action: 'med_find_answer', id: 'r1', dismiss: true }]);
  });

  test('ফার্মেসি ইংরেজিতে', async ({ page }) => {
    await pharmacyMock(page);
    await open(page, withLang('work', 'pharmacy', 'en'), '/work/pharmacy/finds');
    const row = page.locator('.work-find').filter({ hasText: 'Napa' });
    await expect(row.getByTestId('find-need')).toHaveText('Needs 3 strips');
    await expect(row.getByTestId('find-have-unit')).toHaveText('strips');
    await expect(page.locator('.work-find').filter({ hasText: 'Sergel' }).getByTestId('find-said')).toHaveText('You Said: Have 10 Pcs');
    await shot(page, 'medfind-pharmacy-answer-375-en');
    await noSideScroll(page);
  });
});

test.describe('ওষুধ খুঁজুন — কতটা লাগবে (ডেস্কটপ) @desktop', () => {
  test.beforeEach(({}, info) => test.skip(info.project.name !== 'desktop', 'শুধু ১৪৪০ প্রস্থে'));

  test('রোগী: বাছাই ডেস্কটপেও @desktop', async ({ page }) => {
    const errs = watchErrors(page);
    const sent = await capture(page, 'med_find');
    await open(page, withLang('services', 'med-user'));
    await pickDrug(page, 'Napa', /^Napa · Tablet/);
    await page.getByTestId('qty-plus').click();
    await shot(page, 'medfind-qty-picker-desktop');
    await noSideScroll(page);
    await page.getByRole('button', { name: 'কাছের দোকানগুলোকে জিজ্ঞেস করুন' }).click();
    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0]).toMatchObject({ qty: 2, unit: 'strip' });
    expect(errs).toEqual([]);
  });

  test('ফার্মেসির টেবিল @desktop', async ({ page }) => {
    const sent = await pharmacyMock(page);
    await open(page, withLang('work', 'pharmacy'), '/work/pharmacy/finds');
    const row = page.locator('tr').filter({ hasText: 'Napa' });
    await expect(row.getByTestId('find-need')).toHaveText('৩ পাতা লাগবে');
    await shot(page, 'medfind-pharmacy-answer-desktop');
    await noSideScroll(page);
    await row.getByTestId('find-have-qty').fill('3');
    await row.getByTestId('find-have-send').click();
    await expect(row.getByTestId('find-said')).toHaveText('আপনার উত্তর: আছে — ৩ পাতা');
    expect(sent).toMatchObject([{ action: 'med_find_answer', id: 'r1', haveQty: 3 }]);
  });
});
