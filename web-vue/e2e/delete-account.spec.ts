// অ্যাকাউন্ট মোছার পাতা /delete-account (Google Play-র Delete account URL, app/PLAY-STORE.md §৮; প্রোফাইল আর সেটিংসের
// "অ্যাকাউন্ট মুছুন"-ও এখানে): লগইন ছাড়াই খোলে; ওপরে "মুছে না ফেলে সাময়িক বন্ধ রাখুন"; কী মুছবে/কী থাকে; ৩০ দিন আর আসল তারিখ;
// লগইন থাকলে "আমি বুঝেছি" টিক না দিলে লাল বোতাম বন্ধ, মুছলে delete_account {ack:true}, এরপর কী হবে + ইমেইল, লগআউট।
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const guest = { name: 'delete-guest', path: '/delete-account', test: { lang: 'bn', theme: 'light' } };
const member = () => ({ ...def('account', 'settings'), path: '/delete-account' });

/** /api/community-তে যাওয়া কাজ (action আর পুরো body) */
function trackActions(page: Page) {
  const actions: { action: string; body: Record<string, unknown> }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/community') {
      try {
        const body = JSON.parse(r.postData() || '{}');
        actions.push({ action: String(body.action), body });
      } catch {}
    }
  });
  return actions;
}

// আজ + ৩০ দিন, ঢাকার তারিখ, বাংলায় — মাসের নাম src/lib/format.ts থেকেই (হাতে টাইপ নয়)
const here = path.dirname(fileURLToPath(import.meta.url));
const BN_MONTHS = JSON.parse(
  (fs.readFileSync(path.join(here, '../src/lib/format.ts'), 'utf8').match(/const BN_MONTHS = (\[[^\]]+\])/) || [])[1]!.replace(/'/g, '"'),
) as string[];
const bnNum = (n: number) => String(n).replace(/\d/g, (d) => '০১২৩৪৫৬৭৮৯'[Number(d)]);
function bnDatePlus30(now = Date.now()) {
  const d = new Date(now + 30 * 86400_000 + 6 * 3600_000);
  return `${bnNum(d.getUTCDate())} ${BN_MONTHS[d.getUTCMonth()]} ${bnNum(d.getUTCFullYear())}`;
}

test('অতিথি: সাময়িক বন্ধের বিকল্প ওপরে, কী মুছবে/কী থাকে, ৩০ দিন আর আসল তারিখ, "লগইন করে মুছুন" লগইন খোলে', async ({ page }) => {
  const errs = watchErrors(page);
  await open(page, guest);
  await expect(page.getByRole('heading', { name: 'Lifolo অ্যাকাউন্ট মুছুন' })).toBeVisible();
  // বিকল্পটা সবার আগে (মোছার তালিকার আগে)
  const alt = page.getByTestId('pause-instead');
  await expect(alt.getByRole('button', { name: 'মুছে না ফেলে সাময়িক বন্ধ রাখুন' })).toBeVisible();
  const altBox = await alt.boundingBox();
  const whatBox = await page.getByTestId('delete-what').boundingBox();
  expect(altBox!.y).toBeLessThan(whatBox!.y);

  const what = page.getByTestId('delete-what');
  for (const item of ['প্রোফাইল ও ছবি', 'ফোন নম্বর', 'রক্তের অনুরোধ', 'প্রেসক্রিপশন ও ল্যাব রিপোর্ট', 'অ্যাপয়েন্টমেন্ট', 'প্রশ্নোত্তর ও টিপস', 'আপনার দোকান/ল্যাব/অ্যাম্বুলেন্স/চেম্বারের তালিকা আর দোকানের হিসাব']) {
    await expect(what.getByText(item, { exact: true })).toBeVisible();
  }
  await expect(page.getByTestId('delete-kept')).toContainText('ডাক্তারের লেখা প্রেসক্রিপশনের রেকর্ড ডাক্তারের কাছে থাকে');
  const rec = page.getByTestId('delete-recovery');
  await expect(rec).toContainText('৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে। ৩০ দিন পর চিরতরে মুছে যাবে — আর ফেরানো যাবে না।');
  await expect(page.getByTestId('delete-date')).toHaveText(`আজ মুছলে ${bnDatePlus30()} পর্যন্ত ফেরানো যাবে, তারপর চিরতরে মুছে যাবে।`);

  await expect(page.getByText('প্রোফাইল → অ্যাকাউন্ট মুছুন', { exact: false })).toBeVisible();
  const mail = page.locator('a[href^="mailto:ekapkbd@gmail.com"]').first();
  await expect(mail).toBeVisible();
  expect(await mail.getAttribute('href')).toContain('subject=');
  await expect(page.locator('a[href^="/privacy"]').first()).toBeVisible();
  // অতিথির কাছে টিক/লাল বোতাম নেই — আগে লগইন
  await expect(page.getByTestId('delete-confirm')).toHaveCount(0);
  await noSideScroll(page);
  await page.getByRole('button', { name: 'লগইন করে মুছুন' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').locator('input[type="email"]').first()).toBeVisible();
  expect(errs).toEqual([]);
});

test('ইংরেজিতে: শিরোনাম, বিকল্প আর বোতাম', async ({ page }) => {
  await open(page, { ...guest, test: { lang: 'en', theme: 'light' } });
  await expect(page.getByRole('heading', { name: 'Delete your Lifolo account' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Pause instead of deleting' })).toBeVisible();
  await expect(page.getByText('Prescriptions and lab reports', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log in and delete' })).toBeVisible();
});

test('"মুছে না ফেলে সাময়িক বন্ধ রাখুন" সাময়িক বন্ধের পাতায় নিয়ে যায়', async ({ page }) => {
  await open(page, member());
  await page.getByRole('button', { name: 'মুছে না ফেলে সাময়িক বন্ধ রাখুন' }).click();
  await page.waitForURL(/\/deactivate-account$/);
  await expect(page.getByRole('heading', { name: 'অ্যাকাউন্ট সাময়িক বন্ধ রাখুন' })).toBeVisible();
});

test('লগইন করা: টিক না দিলে লাল বোতাম বন্ধ; টিক দিয়ে মুছলে ack:true, এরপর কী হবে, ইমেইল গেছে, লগআউট @desktop', async ({ page }) => {
  const errs = watchErrors(page);
  const actions = trackActions(page);
  await open(page, member());
  await expect(page.getByText('arif.demo@example.com')).toBeVisible();
  const btn = page.getByTestId('delete-confirm').getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' });
  await expect(btn).toBeDisabled();
  const ack = page.getByTestId('delete-ack').locator('input[type="checkbox"]');
  await expect(page.getByTestId('delete-ack')).toHaveText('আমি বুঝেছি, ৩০ দিন পর সব তথ্য চিরতরে মুছে যাবে।');
  // বন্ধ বোতামে জোর করে চাপলেও কিছু যায় না
  await btn.click({ force: true }).catch(() => {});
  expect(actions.map((a) => a.action)).not.toContain('delete_account');
  // টিক দিয়ে আবার তুলে দিলে আবার বন্ধ
  await ack.check();
  await expect(btn).toBeEnabled();
  await ack.uncheck();
  await expect(btn).toBeDisabled();
  await ack.check();
  await btn.click();

  const done = page.getByTestId('delete-done');
  await expect(done).toBeVisible();
  await expect(done).toContainText('মোছার অনুরোধ নেওয়া হয়েছে');
  await expect(done).toContainText(`${bnDatePlus30()} তারিখে মুছে যাবে`);
  await expect(done).toContainText('এরপর কী হবে');
  await expect(done).toContainText('আবার লগইন করলেই (অ্যাপে বা ওয়েবে) মোছা বাতিল');
  await expect(page.getByTestId('delete-email')).toHaveText('নিশ্চিত করে arif.demo@example.com ঠিকানায় একটা ইমেইল পাঠানো হয়েছে।');
  const del = actions.filter((a) => a.action === 'delete_account');
  expect(del).toHaveLength(1);
  expect(del[0].body.ack).toBe(true);
  // লগআউট: ওপরে আবার "লগইন"
  await expect(page.locator('.main-bar').getByRole('button', { name: 'লগইন' })).toBeVisible();
  await noSideScroll(page);
  expect(errs).toEqual([]);
});

test('লগইন করা: মোছা না হলে ভুল দেখায়, লগইন থাকে', async ({ page }) => {
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'delete_account') return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: 'সার্ভারে সমস্যা' }) });
    return route.continue();
  });
  await open(page, member());
  await page.getByTestId('delete-ack').locator('input').check();
  await page.getByTestId('delete-confirm').getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' }).click();
  await expect(page.getByTestId('delete-confirm').getByRole('alert')).toHaveText('সার্ভারে সমস্যা');
  await expect(page.getByTestId('delete-done')).toHaveCount(0);
  await expect(page.getByText('arif.demo@example.com')).toBeVisible();
});

test('লগইন করা, ?confirm=1: নিশ্চিত করার টিক-ঘরে নিয়ে যায়', async ({ page }) => {
  await open(page, member(), '/delete-account?confirm=1');
  await expect(page.getByTestId('delete-ack').locator('input')).toBeFocused();
});

test('সেটিংস → "অ্যাকাউন্ট মুছুন" মোছার পাতায় নিয়ে যায় (অতিথিতেও)', async ({ page }) => {
  await open(page, def('account', 'guest-settings'));
  await expect(page.getByText('অ্যাকাউন্ট সাময়িক বন্ধ', { exact: true })).toHaveCount(0);
  await page.getByText('অ্যাকাউন্ট মুছুন').first().click();
  await page.waitForURL(/\/delete-account$/);
  await expect(page.getByRole('heading', { name: 'Lifolo অ্যাকাউন্ট মুছুন' })).toBeVisible();
});

test('প্রোফাইল → "অ্যাকাউন্ট মুছুন": ছোট ডায়ালগ নয়, পুরো পাতা', async ({ page }) => {
  const actions = trackActions(page);
  await open(page, { ...def('account', 'settings'), path: '/profile' });
  await page.locator('button.list-row:has-text("অ্যাকাউন্ট মুছুন")').click();
  await page.waitForURL(/\/delete-account$/);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByTestId('delete-confirm')).toBeVisible();
  expect(actions.map((a) => a.action)).not.toContain('delete_account');
});
