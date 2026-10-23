// অ্যাডমিন › ফিচার সুইচ (backend/docs/FEATURES.md) — নকল API (এই পরীক্ষার নিজের, page.route): ১২টা কার্ড, বন্ধ → পাইলট,
// পাইলট খুঁজে যোগ/সরানো, ডেমো টিক, "সবার জন্য"-র আগে নিশ্চিত করা, বদলের ইতিহাস।
import { expect, test, type Page } from '@playwright/test';
import { watchErrors } from './helpers';

const KEYS = ['calls_audio', 'calls_video', 'appt_video', 'chat', 'chat_media', 'online_chamber', 'inv_pharmacy', 'inv_lab', 'inv_chamber', 'inv_ambulance', 'my_expenses', 'desktop'];
const NEEDS: Record<string, string[]> = { calls_video: ['calls_audio'], appt_video: ['online_chamber'], chat_media: ['chat'] };
const TWO = new Set(['calls_audio', 'calls_video', 'appt_video', 'chat', 'chat_media']);
const PILOT = { uid: 'pilotU1', email: 'pilot@example.com', name: 'পাইলট রহিম', phone: '01711000001' };

/** একটা পরীক্ষার নকল সার্ভার: সুইচের অবস্থা + পাঠানো বদল */
async function mockFeatures(page: Page) {
  const st: Record<string, { mode: string; pilotUids: string[]; demo: boolean }> = Object.fromEntries(KEYS.map((k) => [k, { mode: 'off', pilotUids: [], demo: false }]));
  const sets: any[] = [];
  const history: any[] = [];
  await page.route('**/api/community', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const json = (x: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(x) });
    if (body.action === 'admin_features_get') {
      return json({
        items: KEYS.map((key) => ({
          key, ...st[key], needs: NEEDS[key] || [], twoParty: TWO.has(key),
          pilots: st[key].pilotUids.map((uid) => (uid === PILOT.uid ? PILOT : { uid, email: '', name: '', phone: '' })),
          last: history.find((h) => h.key === key) || null,
        })),
        history,
        v: history.length,
      });
    }
    if (body.action === 'admin_feature_set') {
      sets.push(body);
      const cur = st[body.key];
      const prevMode = cur.mode;
      st[body.key] = { mode: body.mode, pilotUids: body.pilotUids ?? cur.pilotUids, demo: body.demo ?? cur.demo };
      history.unshift({ id: history.length + 1, at: Date.now(), key: body.key, mode: body.mode, prevMode, demo: st[body.key].demo, pilotCount: st[body.key].pilotUids.length, byEmail: 'owner@example.com' });
      return json({ ok: true, v: history.length });
    }
    if (body.action === 'admin_user_find') return json({ items: [{ ...PILOT, bloodGroup: 'A+', district: '', disabled: false }] });
    return route.fallback();
  });
  return { st, sets };
}

async function login(page: Page) {
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
}

const card = (page: Page, key: string) => page.locator(`section.feature[data-key="${key}"]`);

test('ফিচার সুইচ: ১২টা কার্ড, সব বন্ধ, নির্ভরতা আর দুজনের নিয়ম লেখা', async ({ page }) => {
  const errs = watchErrors(page);
  await mockFeatures(page);
  await login(page);
  await page.goto('/admin#/features');
  await expect(page.locator('.topbar h1')).toHaveText(/^ফিচার সুইচ/);
  await expect(page.locator('section.feature')).toHaveCount(12);
  await expect(card(page, 'chat')).toContainText('মেসেজ');
  await expect(card(page, 'online_chamber')).toContainText('অনলাইন চেম্বার');
  await expect(card(page, 'chat').locator('.chip.bad')).toHaveText('বন্ধ');
  await expect(card(page, 'chat').getByRole('button', { name: 'বন্ধ' })).toBeDisabled();
  await expect(card(page, 'calls_video')).toContainText('আগে চালু থাকতে হবে: অডিও কল');
  await expect(card(page, 'chat')).toContainText('দুজনেরই চালু থাকলে');
  await expect(card(page, 'inv_lab')).not.toContainText('দুজনেরই');
  // পাইলট না হলে পাইলটের অংশ নেই
  await expect(card(page, 'chat').locator('.feature-pilots')).toHaveCount(0);
  expect(errs).toEqual([]);
});

test('পাইলট: চালু, খুঁজে যোগ, ডেমো টিক, সরানো — আর ইতিহাস', async ({ page }) => {
  const { sets } = await mockFeatures(page);
  await login(page);
  await page.goto('/admin#/features');
  const chat = card(page, 'chat');
  await chat.getByRole('button', { name: 'পাইলট' }).click();
  await expect(chat.locator('.chip.pri')).toHaveText('পাইলট');
  expect(sets.at(-1)).toMatchObject({ key: 'chat', mode: 'pilot' });
  await expect(chat).toContainText('এখনো কেউ নেই');

  // খুঁজে যোগ (অ্যাডমিনের ব্যবহারকারী খোঁজা)
  await chat.getByRole('searchbox').fill('pilot@example.com');
  await chat.getByRole('button', { name: 'খুঁজুন' }).click();
  await expect(chat.getByText('pilot@example.com')).toBeVisible();
  await chat.getByRole('button', { name: 'যোগ করুন' }).click();
  await expect(chat.locator('.feature-pilots b').first()).toHaveText('পাইলট ব্যবহারকারী (১)');
  expect(sets.at(-1)).toMatchObject({ key: 'chat', mode: 'pilot', pilotUids: ['pilotU1'] });
  await expect(chat.getByText('পাইলট রহিম')).toBeVisible();

  // ডেমো অ্যাকাউন্টে চালু
  await chat.getByLabel('ডেমো অ্যাকাউন্টে চালু').check();
  await expect.poll(() => sets.at(-1)).toMatchObject({ key: 'chat', mode: 'pilot', demo: true });
  await expect(chat.getByLabel('ডেমো অ্যাকাউন্টে চালু')).toBeChecked();

  // সরানো
  await chat.getByRole('button', { name: 'সরান' }).click();
  await expect(chat).toContainText('এখনো কেউ নেই');
  expect(sets.at(-1)).toMatchObject({ key: 'chat', pilotUids: [] });

  // ইতিহাস: নতুনটা আগে
  const rows = page.locator('table.t').last().locator('tbody tr');
  await expect(rows).toHaveCount(4);
  await expect(rows.first()).toContainText('মেসেজ');
  await expect(rows.last()).toContainText('বন্ধ →');
});

test('"সবার জন্য" — প্রথম চাপে শুধু "নিশ্চিত?", দ্বিতীয় চাপে চালু; বন্ধ করা যায়', async ({ page }) => {
  const { sets } = await mockFeatures(page);
  await login(page);
  await page.goto('/admin#/features');
  const oc = card(page, 'online_chamber');
  await oc.getByRole('button', { name: 'সবার জন্য' }).click();
  await expect(oc.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' })).toBeVisible();
  expect(sets).toEqual([]);
  await oc.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' }).click();
  await expect(oc.locator('.chip.good')).toHaveText('সবার জন্য');
  expect(sets).toEqual([expect.objectContaining({ key: 'online_chamber', mode: 'on' })]);
  await expect(oc.getByRole('button', { name: 'সবার জন্য' })).toBeDisabled();
  // আবার বন্ধ (নিশ্চিত লাগে না)
  await oc.getByRole('button', { name: 'বন্ধ' }).click();
  await expect(oc.locator('.chip.bad')).toHaveText('বন্ধ');
  expect(sets.at(-1)).toMatchObject({ key: 'online_chamber', mode: 'off' });
});

test('সার্ভারের ভুল কার্ডেই দেখায়', async ({ page }) => {
  await mockFeatures(page);
  await page.route('**/api/community', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    if (body.action === 'admin_feature_set') {
      return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: 'এমন ব্যবহারকারী নেই: x' }) });
    }
    return route.fallback();
  });
  await login(page);
  await page.goto('/admin#/features');
  const chat = card(page, 'chat');
  await chat.getByRole('button', { name: 'পাইলট' }).click();
  await expect(chat.getByRole('alert')).toHaveText('এমন ব্যবহারকারী নেই: x');
  await expect(chat.locator('.chip.bad')).toHaveText('বন্ধ');
});

test('কলের রিপোর্ট: মেনু (calls_audio পাইলট), তালিকা, সমাধান', async ({ page }) => {
  const sets: any[] = [];
  await page.route('**/api/community', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const json = (x: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(x) });
    if (body.action === 'admin_features_get') return json({ items: [{ key: 'calls_audio', mode: 'pilot', pilotUids: [], pilots: [], demo: true, needs: [], twoParty: true }], history: [], v: 1 });
    if (body.action === 'admin_call_reports') {
      return json({ items: body.status === 'open' ? [{
        id: 'c1', callId: 'call1', reason: 'harassment', note: 'রাতে বারবার কল', status: 'open', createdAt: Date.now(),
        reporter: { uid: 'u1', name: 'রহিম', email: 'r@example.com' },
        reported: { uid: 'u2', name: 'করিম', email: 'k@example.com', openReports: 3, blockedBy: 2 },
        call: { ctx: 'blood', video: false, durationSec: 42 },
      }] : [] });
    }
    if (body.action === 'admin_call_report_set') {
      sets.push(body);
      return json({ ok: true });
    }
    return route.fallback();
  });
  await login(page);
  await expect(page.locator('nav.nav a', { hasText: 'অনলাইন, কল ও মেসেজের রিপোর্ট' })).toBeVisible();
  await page.goto('/admin#/callreports');
  await expect(page.locator('.topbar h1')).toContainText('কলের রিপোর্ট');
  const main = page.locator('main.page');
  await expect(main).toContainText('হয়রানি');
  await expect(main).toContainText('রাতে বারবার কল');
  await expect(main).toContainText('খোলা রিপোর্ট ৩টা');
  await expect(main).toContainText('৪২ সেকেন্ড');
  await expect(main.getByRole('tab', { name: 'কল' })).toHaveAttribute('aria-selected', 'true');
  await main.getByRole('button', { name: 'সমাধান হয়েছে' }).click();
  await expect.poll(() => sets[0]).toMatchObject({ id: 'c1', status: 'resolved' });
});

test('মেসেজের রিপোর্ট: লেখা আর ছবি শুধু অ্যাডমিনের পাতায়, সমাধান', async ({ page }) => {
  const errs = watchErrors(page);
  const sets: any[] = [];
  // ১×১ পিক্সেলের JPEG নয়, PNG-ও চলে — data: URL CSP-তে অনুমোদিত
  const PIX = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
  await page.route('**/api/community', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const json = (x: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(x) });
    if (body.action === 'admin_features_get') return json({ items: [{ key: 'chat', mode: 'on', pilotUids: [], pilots: [], demo: false, needs: [], twoParty: true }], history: [], v: 1 });
    if (body.action === 'admin_chat_reports') {
      return json({ ok: true, items: body.status === 'open' ? [{
        id: 'm1', messageId: 'msg1', threadId: 't1', reason: 'spam', note: '', status: 'open', createdAt: Date.now(),
        reporter: { uid: 'u1', name: 'রহিম' }, reported: { uid: 'u2', name: 'করিম' },
        message: { ctx: 'blood', kind: 'photo', text: 'টাকা পাঠান', at: Date.now() }, photo: `data:image/png;base64,${PIX}`,
      }] : [] });
    }
    if (body.action === 'admin_chat_report_resolve') {
      sets.push(body);
      return json({ ok: true });
    }
    return route.fallback();
  });
  await login(page);
  await page.goto('/admin#/msgreports');
  await expect(page.locator('.topbar h1')).toContainText('মেসেজের রিপোর্ট');
  const main = page.locator('main.page');
  await expect(main).toContainText('স্প্যাম');
  await expect(main).toContainText('টাকা পাঠান');
  await expect(main.getByRole('img', { name: 'রিপোর্ট করা ছবি' })).toBeVisible();
  await main.getByRole('button', { name: 'বাদ দিন' }).click();
  await expect.poll(() => sets[0]).toMatchObject({ id: 'm1', status: 'dismissed' });
  expect(errs).toEqual([]);
});
