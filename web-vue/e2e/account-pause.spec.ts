// অ্যাকাউন্ট সাময়িক বন্ধ: /deactivate-account (কী হয়, টিক ছাড়া এক চাপে account_deactivate, তারপর লগআউট),
// বন্ধ অ্যাকাউন্টে লগইন থাকলে পুরো ওয়েব আটকে "আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে" + "আবার চালু করুন" (account_reactivate) + "লগআউট",
// আর যেকোনো কাজে 423 `account_deactivated` এলে একই পর্দা। সার্ভারের অবস্থা এই পরীক্ষা নিজেই রাখে (page.route)।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const member = (extra: Record<string, unknown> = {}, p = '/deactivate-account') => {
  const d = def('account', 'settings');
  return { ...d, path: p, test: { ...d.test, ...extra } };
};

interface Server { deactivated: boolean; calls: string[]; failReactivate?: boolean }

/** account_deactivate/reactivate আর বন্ধ থাকলে বাকি কাজে 423 — নকল সার্ভারের ওপরে */
async function fakeAccount(page: Page, st: Server) {
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    const action = String(body.action);
    st.calls.push(action);
    const reply = (status: number, json: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });
    if (action === 'account_deactivate') {
      st.deactivated = true;
      return reply(200, { ok: true });
    }
    if (action === 'account_reactivate') {
      if (st.failReactivate) return reply(500, { error: 'আবার চেষ্টা করুন' });
      st.deactivated = false;
      return reply(200, { ok: true });
    }
    if (st.deactivated) return reply(423, { error: 'Account is paused', code: 'account_deactivated' });
    return route.continue();
  });
}

const paused = (page: Page) => page.getByTestId('account-paused');

test('সাময়িক বন্ধের পাতা: কী হয় বলে, টিক নেই, এক চাপে account_deactivate, তারপর লগআউট', async ({ page }) => {
  const errs = watchErrors(page);
  const st: Server = { deactivated: false, calls: [] };
  await fakeAccount(page, st);
  await open(page, member());
  await expect(page.getByRole('heading', { name: 'অ্যাকাউন্ট সাময়িক বন্ধ রাখুন' })).toBeVisible();
  const what = page.getByTestId('pause-what');
  for (const t of ['রক্তদাতার তালিকাতেও থাকবেন না', 'বাসায় সেবার তালিকা লুকানো থাকবে', 'আগে নেওয়া অ্যাপয়েন্টমেন্ট থাকবে', '"নিষ্ক্রিয় ব্যবহারকারী"', 'অ্যাকাউন্টের খবর ছাড়া কোনো নোটিফিকেশন আসবে না।', 'কিছুই মুছবে না', 'সময়ের কোনো সীমা নেই', 'লগইন করে "আবার চালু করুন" চাপুন']) {
    await expect(what).toContainText(t);
  }
  await expect(page.locator('input[type="checkbox"]')).toHaveCount(0);
  await noSideScroll(page);
  await page.getByTestId('pause-confirm').click();
  await expect(page.getByTestId('pause-done')).toContainText('অ্যাকাউন্ট সাময়িক বন্ধ হয়েছে');
  await expect(page.getByTestId('pause-done')).toContainText('কিছুই মোছা হয়নি');
  expect(st.calls.filter((c) => c === 'account_deactivate')).toHaveLength(1);
  // লগআউট হয়েছে — আটকানো পর্দা নয়, অতিথির ওয়েব
  await expect(paused(page)).toHaveCount(0);
  await expect(page.locator('.main-bar').getByRole('button', { name: 'লগইন' })).toBeVisible();
  expect(errs).toEqual([]);
});

test('সাময়িক বন্ধের পাতা: অতিথি "লগইন করে বন্ধ করুন" চাপলে লগইন খোলে', async ({ page }) => {
  await open(page, { name: 'pause-guest', path: '/deactivate-account', test: { lang: 'bn', theme: 'light' } });
  await page.getByRole('button', { name: 'লগইন করে বন্ধ করুন' }).click();
  await expect(page.getByRole('dialog').locator('input[type="email"]').first()).toBeVisible();
});

test('প্রোফাইল আর সেটিংস → "অ্যাকাউন্ট সাময়িক বন্ধ" পাতায় নিয়ে যায়', async ({ page }) => {
  await open(page, member({}, '/profile'));
  await page.locator('button.list-row:has-text("অ্যাকাউন্ট সাময়িক বন্ধ")').click();
  await page.waitForURL(/\/deactivate-account$/);
  await open(page, member({}, '/settings'));
  await page.locator('button.list-row:has-text("অ্যাকাউন্ট সাময়িক বন্ধ")').click();
  await page.waitForURL(/\/deactivate-account$/);
});

test('বন্ধ অ্যাকাউন্টে লগইন: পুরো ওয়েব আটকানো; "আবার চালু করুন" → account_reactivate, ওয়েব ফেরে @desktop', async ({ page }) => {
  const errs = watchErrors(page);
  const st: Server = { deactivated: true, calls: [] };
  await fakeAccount(page, st);
  await open(page, member({ account: { state: 'deactivated', deactivatedAt: Date.UTC(2026, 9, 1, 6) } }, '/'));
  await expect(paused(page).getByRole('heading', { name: 'আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে' })).toBeVisible();
  await expect(paused(page)).toContainText('১ অক্টোবর ২০২৬ থেকে বন্ধ।');
  // মেনু, নিচের ট্যাব — কিছুই নেই
  await expect(page.locator('.bottom-nav, .side-nav, .main-bar')).toHaveCount(0);
  await expect(paused(page).getByRole('button', { name: 'লগআউট' })).toBeVisible();
  await noSideScroll(page);
  await paused(page).getByRole('button', { name: 'আবার চালু করুন' }).click();
  await expect(paused(page)).toHaveCount(0);
  await expect(page.getByText('অ্যাকাউন্ট আবার চালু হয়েছে।')).toBeVisible();
  await expect(page.locator('.main-bar')).toBeVisible();
  expect(st.calls).toContain('account_reactivate');
  expect(errs).toEqual([]);
});

test('বন্ধ অ্যাকাউন্ট: "অ্যাকাউন্ট মুছুন" মোছার পাতা খোলে (সাময়িক বন্ধের বিকল্প নেই), মুছলে লগআউট', async ({ page }) => {
  const st: Server = { deactivated: true, calls: [] };
  await fakeAccount(page, st);
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    // সার্ভারের নিয়ম: বন্ধ অবস্থাতেও delete_account চলে (আগে বন্ধ থাকা শেষ হয়)
    if (body.action === 'delete_account') {
      st.calls.push('delete_account');
      st.deactivated = false;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, deleteAt: Date.now() + 30 * 86400_000 }) });
    }
    return route.fallback();
  });
  await open(page, member({ account: { state: 'deactivated' } }, '/'));
  await paused(page).getByRole('link', { name: 'অ্যাকাউন্ট মুছুন' }).click();
  await page.waitForURL(/\/delete-account$/);
  await expect(paused(page)).toHaveCount(0);
  await expect(page.getByTestId('pause-instead')).toHaveCount(0);
  await page.getByTestId('delete-ack').locator('input').check();
  await page.getByTestId('delete-confirm').getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' }).click();
  await expect(page.getByTestId('delete-done')).toBeVisible();
  expect(st.calls).toContain('delete_account');
  await expect(page.locator('.main-bar').getByRole('button', { name: 'লগইন' })).toBeVisible();
});

test('বন্ধ অ্যাকাউন্ট: আবার চালু না হলে ভুল দেখায়, পর্দা থাকে; "লগআউট" অতিথির ওয়েবে নেয়', async ({ page }) => {
  const st: Server = { deactivated: true, calls: [], failReactivate: true };
  await fakeAccount(page, st);
  await open(page, member({ account: { state: 'deactivated' } }, '/'));
  await paused(page).getByRole('button', { name: 'আবার চালু করুন' }).click();
  await expect(paused(page).getByRole('alert')).toHaveText('আবার চেষ্টা করুন');
  await expect(paused(page)).toBeVisible();
  await paused(page).getByRole('button', { name: 'লগআউট' }).click();
  await expect(paused(page)).toHaveCount(0);
  await expect(page.locator('.main-bar').getByRole('button', { name: 'লগইন' })).toBeVisible();
});

test('যেকোনো কাজে 423 account_deactivated: চালু ধরে খোলা ওয়েবও আটকে যায়, আবার চালু করলে ফেরে', async ({ page }) => {
  const st: Server = { deactivated: true, calls: [] };
  await fakeAccount(page, st);
  // প্রোফাইলে অবস্থা নেই (চালু ধরা) — কিন্তু সার্ভার 423 দেয় (অন্য ফোন থেকে বন্ধ করা হয়েছে)
  await open(page, member({}, '/profile'));
  await expect(paused(page).getByRole('heading', { name: 'আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে' })).toBeVisible();
  await paused(page).getByRole('button', { name: 'আবার চালু করুন' }).click();
  await expect(paused(page)).toHaveCount(0);
  await expect(page).toHaveURL(/\/profile$/);
  await expect(page.locator('.main-bar')).toBeVisible();
});

test('423 নয় এমন ভুল (যেমন 500) ওয়েব আটকায় না', async ({ page }) => {
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'me') return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"x"}' });
    return route.continue();
  });
  await open(page, member({}, '/profile'));
  await expect(page.locator('.main-bar')).toBeVisible();
  await expect(paused(page)).toHaveCount(0);
});
