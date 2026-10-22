// সেটিংস › নোটিফিকেশন: "সব নোটিফিকেশন" আর ধরন অনুযায়ী সুইচ (notify_prefs_get/set), জরুরি ধরন বন্ধে পাতার ভেতরে "নিশ্চিত?",
// "আশেপাশে রক্ত লাগলে"-র নিচে আগের দিনে সর্বোচ্চ/গ্রুপ, "অ্যাকাউন্ট" সবসময় চালু, "আমার ব্যবসা" শুধু "আমার কাজ" থাকলে,
// সেভ না হলে আগের অবস্থা আর টোস্ট, নিচে ঘণ্টার নোট।
// সার্ভারের অবস্থা এই পরীক্ষা নিজেই রাখে (page.route) — নকল সার্ভার (test/fixtures) অবস্থা রাখে না।
import { expect, test, type Page } from '@playwright/test';
import { def, open, watchErrors } from './helpers';

const CATS = ['blood_nearby', 'blood_emergency', 'my_request', 'appointments', 'rx', 'ambulance', 'community', 'business'];

interface Server { all: boolean; cats: Record<string, boolean>; sets: any[] }

async function fakePrefs(page: Page, opts: { noBusiness?: boolean; failSet?: boolean } = {}): Promise<Server> {
  const st: Server = { all: true, cats: Object.fromEntries(CATS.map((c) => [c, true])), sets: [] };
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    const reply = (json: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(json) });
    if (body.action === 'notify_prefs_get') return reply({ ok: true, all: st.all, cats: st.cats });
    if (body.action === 'notify_prefs_set') {
      st.sets.push({ all: body.all, cats: body.cats });
      if (opts.failSet) return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"x"}' });
      if (typeof body.all === 'boolean') st.all = body.all;
      Object.assign(st.cats, body.cats || {});
      return reply({ ok: true, all: st.all, cats: st.cats });
    }
    if (opts.noBusiness) {
      const empty: Record<string, unknown> = {
        me: { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: false, unread: 0 },
        ambulance_mine: { items: [] }, pharmacy_me: { pharmacy: null }, bloodbank_mine: { items: [] }, lab_me: { lab: null }, rx_assisting: { items: [] },
      };
      if (body.action in empty) return reply(empty[body.action]);
    }
    return route.continue();
  });
  return st;
}

const sw = (page: Page, cat: string) => page.locator(`[data-cat="${cat}"] input[role="switch"]`);
const master = (page: Page) => sw(page, 'all');

test('নোটিফিকেশন: সব ধরনের সুইচ, অ্যাকাউন্ট সবসময় চালু, একটা বন্ধ করলে শুধু সেটাই পাঠানো হয়', async ({ page }) => {
  const errs = watchErrors(page);
  const st = await fakePrefs(page);
  await open(page, def('account', 'settings'));
  const box = page.locator('[data-testid="notify-settings"]');
  await expect(box.getByText('সব নোটিফিকেশন')).toBeVisible();
  for (const label of ['আশেপাশে রক্ত লাগলে', 'জরুরি রক্তের আহ্বান', 'আমার রক্তের অনুরোধ', 'অ্যাপয়েন্টমেন্ট আর সিরিয়াল', 'প্রেসক্রিপশন, ওষুধ আর ল্যাব', 'অ্যাম্বুলেন্স', 'প্রশ্নোত্তর আর টিপস', 'আমার ব্যবসা', 'অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)']) {
    await expect(box.getByText(label, { exact: true })).toBeVisible();
  }
  for (const c of CATS) await expect(sw(page, c)).toBeChecked();
  await expect(sw(page, 'account')).toBeChecked();
  await expect(sw(page, 'account')).toBeDisabled();
  // আগের "আশেপাশে রক্ত লাগলে"-র নিচের সেটিংস
  await expect(box.getByText('দিনে সর্বোচ্চ')).toBeVisible();
  await expect(box.getByTestId('bell-note')).toContainText('ঘণ্টার তালিকায় (ইনবক্স) সব খবর আগের মতোই থাকবে');

  await sw(page, 'rx').click();
  await expect(sw(page, 'rx')).not.toBeChecked();
  await expect.poll(() => st.sets).toEqual([{ cats: { rx: false } }]);
  expect(st.cats.rx).toBe(false);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await sw(page, 'rx').click();
  await expect.poll(() => st.cats.rx).toBe(true);

  // আশেপাশে রক্ত বন্ধ: নিচের দিনে সর্বোচ্চ/গ্রুপ লুকায়
  await sw(page, 'blood_nearby').click();
  await expect(box.getByText('দিনে সর্বোচ্চ')).toHaveCount(0);
  await expect.poll(() => st.sets.at(-1)).toEqual({ cats: { blood_nearby: false } });
  expect(errs).toEqual([]);
});

test('নোটিফিকেশন: জরুরি রক্ত আর অ্যাম্বুলেন্স বন্ধের আগে পাতার ভেতরে "নিশ্চিত?" — বাতিল করলে চালুই থাকে', async ({ page }) => {
  const st = await fakePrefs(page);
  let nativeDialog = false;
  page.on('dialog', async (d) => {
    nativeDialog = true;
    await d.dismiss();
  });
  await open(page, def('account', 'settings'));
  for (const cat of ['blood_emergency', 'ambulance']) {
    await sw(page, cat).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg).toBeVisible();
    await expect(dlg.getByText('জরুরি খবর পাবেন না — নিশ্চিত?')).toBeVisible();
    await dlg.getByRole('button', { name: 'বাতিল' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(sw(page, cat)).toBeChecked();
    expect(st.sets).toEqual([]);
  }
  await sw(page, 'blood_emergency').click();
  await page.getByRole('dialog').getByRole('button', { name: 'বন্ধ করুন' }).click();
  await expect(sw(page, 'blood_emergency')).not.toBeChecked();
  await expect.poll(() => st.sets).toEqual([{ cats: { blood_emergency: false } }]);
  // চালু করতে কিছু জিজ্ঞেস করে না
  await sw(page, 'blood_emergency').click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect.poll(() => st.cats.blood_emergency).toBe(true);
  expect(nativeDialog).toBe(false);
});

test('নোটিফিকেশন: "সব নোটিফিকেশন" বন্ধ — ধরনগুলো নিষ্ক্রিয়, অ্যাকাউন্ট চালুই', async ({ page }) => {
  const st = await fakePrefs(page);
  await open(page, def('account', 'settings'));
  await master(page).click();
  await expect(master(page)).not.toBeChecked();
  await expect(page.getByText('বন্ধ: শুধু অ্যাকাউন্ট আর নিরাপত্তার খবর আসবে')).toBeVisible();
  await expect(sw(page, 'account')).toBeChecked();
  await expect.poll(() => st.sets).toEqual([{ all: false }]);
  for (const c of CATS) await expect(sw(page, c)).toBeDisabled();
  await master(page).click();
  await expect.poll(() => st.all).toBe(true);
  await expect(sw(page, 'rx')).toBeEnabled();
});

test('নোটিফিকেশন: "আমার কাজ" না থাকলে "আমার ব্যবসা" দেখায় না; সেভ না হলে আগের অবস্থায় ফেরে', async ({ page }) => {
  await fakePrefs(page, { noBusiness: true, failSet: true });
  const d = def('account', 'settings');
  await open(page, { ...d, test: { ...d.test, acct: undefined } });
  await expect(sw(page, 'rx')).toBeVisible();
  await expect(sw(page, 'business')).toHaveCount(0);
  await sw(page, 'community').click();
  await expect(page.getByRole('status').filter({ hasText: 'সেভ হয়নি, ইন্টারনেট দেখুন।' })).toBeVisible();
  await expect(sw(page, 'community')).toBeChecked();
});
