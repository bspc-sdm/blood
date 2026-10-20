// অ্যাকাউন্ট মোছার পাবলিক পাতা /delete-account (Google Play-র Delete account URL, app/PLAY-STORE.md §৮):
// লগইন ছাড়াই খোলে, কী মোছে/কবে বলে, ইমেইলের পথ; লগইন থাকলে প্রোফাইলের একই নিশ্চিতকরণ দিয়ে মোছা; সেটিংস থেকে লিংক
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const guest = { name: 'delete-guest', path: '/delete-account', test: { lang: 'bn', theme: 'light' } };
const member = () => ({ ...def('account', 'settings'), path: '/delete-account' });

function trackActions(page: Page) {
  const actions: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/community') {
      try {
        actions.push(String(JSON.parse(r.postData() || '{}').action));
      } catch {}
    }
  });
  return actions;
}

test('অতিথি: পাতা খোলে, কী মোছে আর তিন উপায় দেখায়, "লগইন করে মুছুন" লগইন খোলে', async ({ page }) => {
  const errs = watchErrors(page);
  await open(page, guest);
  await expect(page.getByRole('heading', { name: 'Lifolo অ্যাকাউন্ট মুছুন' })).toBeVisible();
  await expect(page.getByText('৩০ দিন পর চিরতরে মুছে যায়', { exact: false })).toBeVisible();
  await expect(page.getByText('প্রোফাইল → অ্যাকাউন্ট মুছুন', { exact: false })).toBeVisible();
  const mail = page.locator('a[href^="mailto:ekapkbd@gmail.com"]').first();
  await expect(mail).toBeVisible();
  expect(await mail.getAttribute('href')).toContain('subject=');
  await expect(page.locator('a[href^="/privacy"]').first()).toBeVisible();
  await noSideScroll(page);
  await page.getByRole('button', { name: 'লগইন করে মুছুন' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').locator('input[type="email"]').first()).toBeVisible();
  expect(errs).toEqual([]);
});

test('ইংরেজিতে: শিরোনাম আর বোতাম', async ({ page }) => {
  await open(page, { ...guest, test: { lang: 'en', theme: 'light' } });
  await expect(page.getByRole('heading', { name: 'Delete your Lifolo account' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log in and delete' })).toBeVisible();
});

test('লগইন করা: ইমেইল দেখায়, নিশ্চিত করলে delete_account আর তারিখসহ "অনুরোধ নেওয়া হয়েছে"', async ({ page }) => {
  const errs = watchErrors(page);
  const actions = trackActions(page);
  await open(page, member());
  await expect(page.getByText('arif.demo@example.com')).toBeVisible();
  await page.getByRole('button', { name: 'আমার অ্যাকাউন্ট মুছুন' }).click();
  const dlg = page.getByRole('dialog');
  await expect(dlg.getByText('অ্যাকাউন্ট মুছবেন?')).toBeVisible();
  // বাতিল করলে কিছু হয় না
  await dlg.getByRole('button', { name: 'বাতিল' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(actions).not.toContain('delete_account');
  // আবার → মুছুন
  await page.getByRole('button', { name: 'আমার অ্যাকাউন্ট মুছুন' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' }).click();
  const done = page.getByTestId('delete-done');
  await expect(done).toBeVisible();
  await expect(done).toContainText('মোছার অনুরোধ নেওয়া হয়েছে');
  await expect(done).toContainText('তারিখে মুছে যাবে');
  expect(actions).toContain('delete_account');
  await noSideScroll(page);
  expect(errs).toEqual([]);
});

test('লগইন করা, ?confirm=1: নিশ্চিতকরণ নিজে খোলে', async ({ page }) => {
  await open(page, member(), '/delete-account?confirm=1');
  await expect(page.getByRole('dialog').getByText('অ্যাকাউন্ট মুছবেন?')).toBeVisible();
});

test('সেটিংস → "অ্যাকাউন্ট মুছুন" পাবলিক পাতায় নিয়ে যায় (অতিথিতেও)', async ({ page }) => {
  await open(page, def('account', 'guest-settings'));
  await page.getByText('অ্যাকাউন্ট মুছুন').first().click();
  await page.waitForURL(/\/delete-account$/);
  await expect(page.getByRole('heading', { name: 'Lifolo অ্যাকাউন্ট মুছুন' })).toBeVisible();
});

test('প্রোফাইলের মোছা আগের মতোই: একই ডায়ালগ, মুছলে হোমে', async ({ page }) => {
  const actions = trackActions(page);
  await open(page, { ...def('account', 'settings'), path: '/profile' });
  await page.getByText('অ্যাকাউন্ট মুছুন').first().click();
  const dlg = page.getByRole('dialog');
  await expect(dlg.getByText('অ্যাকাউন্ট মুছবেন?')).toBeVisible();
  await dlg.getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' }).click();
  await page.waitForURL((u) => u.pathname === '/');
  expect(actions).toContain('delete_account');
});
