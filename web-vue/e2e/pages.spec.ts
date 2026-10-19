// প্রতিটা পাতা (test/pages/*.json — স্ক্রিনশটের একই তালিকা) মোবাইল মাপে: খোলে, JS ভুল নেই, পাশে স্ক্রল নেই, কিছু দেখায়
import { expect, test } from '@playwright/test';
import { PARTS, open, pagesOf, watchErrors, noSideScroll } from './helpers';

for (const part of PARTS) {
  test.describe(part, () => {
    for (const p of pagesOf(part)) {
      if (p.sizes && !p.sizes.includes('mobile')) continue;
      test(p.name, async ({ page }) => {
        const errs = watchErrors(page);
        await open(page, p);
        // ধাপগুলো (চাপা/লেখা) — React-এর নকল তথ্যে কিছু ধাপ দুই অ্যাপেই চলে না, তাই ধাপের ব্যর্থতা এখানে ধরা হয় না
        for (const sel of p.click || []) {
          await page.locator(sel).first().click({ timeout: 3000 }).catch(() => {});
          await page.waitForTimeout(250);
        }
        for (const [sel, val] of p.fill || []) await page.locator(sel).first().fill(val, { timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(p.wait ?? 300);
        await expect(page.locator('#root')).not.toBeEmpty();
        expect((await page.locator('body').innerText()).trim().length).toBeGreaterThan(5);
        await noSideScroll(page);
        expect(errs).toEqual([]);
      });
    }
  });
}
