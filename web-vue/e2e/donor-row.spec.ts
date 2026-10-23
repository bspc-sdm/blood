// রক্তপ্রার্থীর দিকে রাজি হওয়া ডোনারের সারি (DonorRow) ৩৬০px ফোনে — লম্বা বাংলা নাম, বাংলা আর ইংরেজি, কল/মেসেজ চালু:
// নাম, ফোন, দূরত্ব, অবস্থা পুরো পড়া যায় (চেপে ছোট হয় না, অক্ষর-অক্ষর ভাঙে না); ডানে শুধু দুই আইকন — কল · মেসেজ;
// কলের মেনুতে ঠিক "ফোন কল · অ্যাপ কল · ভিডিও কল" (ইংরেজিতে Phone Call · App Call · Video Call), পর্দার ভেতরে।
// (অ্যান্ড্রয়েডে RC3-এ চারটা আইকন নামের সারিতে ছিল — নামের কলাম ০ চওড়া হয়ে বড় খালি ফাঁক; এখানে সেটা যেন না হয়)
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, openCallMenu, watchErrors, type PageDef } from './helpers';

const LONG = 'মোহাম্মদ আব্দুল্লাহ আল মামুন চৌধুরী';

function longName(p: PageDef, lang: 'bn' | 'en', path?: string): PageDef {
  const t = p.test as Record<string, any>;
  const queries = { ...t.queries };
  queries['requests/m1/responses'] = queries['requests/m1/responses'].map((r: any, i: number) => (i === 0 ? { ...r, donorName: LONG } : r));
  return { ...p, path: path ?? p.path, test: { ...t, queries, lang } };
}

/** প্রতিটা ডোনারের লেখার অংশ চওড়া (≥ ১২০px), নাম ২ লাইনের বেশি নয়, ফোন-দূরত্বের লাইন অক্ষর-অক্ষর ভাঙেনি; আইকন পর্দার ভেতরে */
async function readable(page: Page) {
  const rows = page.locator('.blood-donor');
  await expect(rows.first()).toBeVisible();
  await expect(rows.first().getByTestId('contact-message')).toBeVisible();
  const n = await rows.count();
  expect(n).toBeGreaterThan(1);
  for (let i = 0; i < n; i++) {
    const row = rows.nth(i);
    const info = await row.getByTestId('donor-info').boundingBox();
    expect(info!.width, `row ${i} text width`).toBeGreaterThanOrEqual(120);
    const name = await row.locator('.blood-donor-name').boundingBox();
    expect(name!.height, `row ${i} name ≤ 2 lines`).toBeLessThanOrEqual(56);
    const meta = await row.getByTestId('donor-info').locator('.t-body-s').first().boundingBox();
    expect(meta!.height, `row ${i} phone · distance not one letter per line`).toBeLessThanOrEqual(60);
    for (const id of ['contact-call', 'contact-message']) {
      const b = await row.getByTestId(id).boundingBox();
      expect(b && b.x >= 0 && b.x + b.width <= 360, `${id} inside the screen`).toBeTruthy();
    }
  }
  await expect(page.getByText(LONG, { exact: true }).first()).toBeVisible();
  await noSideScroll(page);
}

test.describe('৩৬০px', () => {
  test.use({ viewport: { width: 360, height: 800 } });

  for (const lang of ['bn', 'en'] as const) {
    test(`ডোনারের সারি: লম্বা নাম, দুই আইকন, কলের মেনু (${lang})`, async ({ page }, info) => {
      const errs = watchErrors(page);
      await open(page, longName(def('blood', 'my-request-detail'), lang));
      await readable(page);
      await page.screenshot({ path: info.outputPath(`donor-row-360-${lang}.png`), fullPage: true });
      const menu = await openCallMenu(page, page.locator('.blood-donor').first());
      // ইংরেজিতে অ্যাপের সব বোতামের মতো Title Case (tr)
      const want = lang === 'bn' ? ['ফোন কল', 'অ্যাপ কল', 'ভিডিও কল'] : ['Phone Call', 'App Call', 'Video Call'];
      await expect(menu.getByRole('menuitem')).toHaveText([new RegExp('^' + want[0]), want[1], want[2]]);
      const box = await menu.boundingBox();
      expect(box!.x >= 0 && box!.x + box!.width <= 360, 'menu inside the screen').toBeTruthy();
      await noSideScroll(page);
      await page.screenshot({ path: info.outputPath(`donor-call-menu-360-${lang}.png`) });
      // বাইরে চাপলে বন্ধ
      await page.mouse.click(4, 400);
      await expect(menu).toHaveCount(0);
      expect(errs).toEqual([]);
    });
  }

  test('হোমের "আমার রিকোয়েস্ট" কার্ডেও একই সারি', async ({ page }, info) => {
    await open(page, longName(def('blood', 'home-member'), 'bn'));
    await readable(page);
    await page.screenshot({ path: info.outputPath('donor-card-360-bn.png'), fullPage: true });
  });
});
