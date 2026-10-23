// অনলাইন (ভিডিও) চেম্বার: ডাক্তারের সেটআপ ("অনলাইন (ভিডিও)", ঠিকানা নেই, bKash নম্বর যাচাই), তালিকায় "অনলাইন" ব্যাজ আর
// ছাঁকনি (online:true, এলাকা ছাড়া), অনলাইন বুকিং, রোগীর ফি (নম্বর কপি, TrxID, "টাকা পাঠিয়েছি" → যাচাই হচ্ছে),
// ডাক্তার/সহকারীর "ফি পেয়েছি"/"পাইনি" (নিশ্চিত ধাপ, TrxID), আর "ভিডিও কলে যুক্ত হোন" লিংক (সময়ের জানালায়)।
// নকল সার্ভারের উত্তরের ওপর অনলাইন অংশ এই পরীক্ষা নিজেই বসায় (page.route) — test/fixtures বদলানো হয়নি।
// ফির অংশ এখন LIFOLO PAY-এর PaySheet-এ (নম্বর pay-number, কপি pay-copy, ধাপ pay-steps) — আচরণ আগের মতোই (আগের appt_pay_* পথ)।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, open, watchErrors } from './helpers';

const H = 3600_000;
const DAY = 24 * H;
const dhakaNow = () => new Date(Date.now() + 6 * H);
const today = () => {
  const d = dhakaNow();
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};
/** এখনকার ঢাকার সময় "HH:MM" (কলের জানালার ভেতরে) */
const nowHM = () => {
  const d = dhakaNow();
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
};

type Calls = { action: string; body: any }[];
/** ফিচার সুইচ: সার্ভারের পরীক্ষার প্রোফাইলের মতো সব চালু (off: বন্ধ) */
const ALL_ON = { online_chamber: true, appt_video: true };
async function mock(page: Page, extra: (action: string, body: any, route: Route) => Promise<unknown | undefined>, keys: Record<string, boolean> = ALL_ON): Promise<Calls> {
  const seen: Calls = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: body.action, body });
    const out = body.action === 'features' ? { keys, v: 1 } : await extra(body.action, body, route);
    if (out === undefined) return route.continue();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(out) });
  });
  return seen;
}
const base = async (route: Route) => (await route.fetch()).json();

const ONLINE_DOC = {
  uid: 'p_online', type: 'doctor', name: 'ডা. রুবিনা ইয়াসমিন', specialties: ['medicine'], degrees: 'MBBS, FCPS', gender: 'female',
  listed: true, appointments: true, rating: 4.8, ratingCount: 12, photo: '',
  chambers: [{
    mode: 'online', hospitalId: null, hospitalName: 'Online video consultation', hospitalNameBn: 'অনলাইন ভিডিও পরামর্শ', district: null, upazilaId: null, unionId: null, room: '', schedule: '',
    fee: '৫০০ টাকা', noFee: false, appointments: true, serialPhone: '', days: [0, 1, 2, 3, 4, 5, 6], start: '00:00', end: '23:59', note: 'নেট চালু রাখবেন',
  }],
  homeVisit: null,
};

test('তালিকা: "অনলাইন" ব্যাজ, ছাঁকনিতে online:true (এলাকা ছাড়া), অনলাইন বুকিং', async ({ page }) => {
  const errs = watchErrors(page);
  const seen = await mock(page, async (action, body, route) => {
    if (action !== 'providers') return undefined;
    if (body.online) return { items: [ONLINE_DOC] };
    const j = await base(route);
    return { ...j, items: [...j.items, ONLINE_DOC] };
  });
  await open(page, def('appointment', 'list'));
  const card = page.locator('.card', { hasText: 'ডা. রুবিনা ইয়াসমিন' }).first();
  await expect(card).toBeVisible();
  await expect(card.locator('.tag', { hasText: /^অনলাইন$/ })).toBeVisible();
  await expect(card).toContainText('অনলাইন (ভিডিও)');

  await page.locator('.appt-qchips').getByRole('button', { name: 'অনলাইন', exact: true }).click();
  await expect.poll(() => seen.filter((c) => c.action === 'providers').at(-1)?.body).toMatchObject({ type: 'doctor', online: true });
  const last = seen.filter((c) => c.action === 'providers').at(-1)!.body;
  expect(last.district).toBeUndefined();
  expect(last.hospitalId).toBeUndefined();
  await expect(page.locator('.appt-qchips .chip.on', { hasText: 'অনলাইন' })).toBeVisible();
  await expect(page.getByText('ডা. মাহমুদা আক্তার')).toHaveCount(0);

  // বুকিং: এক চেম্বার → সরাসরি অনুরোধ; ঠিকানা নেই, ফির নিয়ম লেখা
  await card.click();
  await page.locator('.detail-bar').getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট' }).click();
  const dlg = page.getByRole('dialog');
  await expect(dlg.getByTestId('online-book-hint')).toContainText('bKash');
  await dlg.getByLabel('কী সমস্যা').fill('জ্বর আর কাশি');
  await dlg.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
  await expect.poll(() => seen.find((c) => c.action === 'book')?.body).toMatchObject({ providerUid: 'p_online', chamberIndex: 0, chamberKey: 'online' });
  expect(errs).toEqual([]);
});

test('রোগী: ফি bKash-এ — নম্বর কপি, TrxID দিয়ে "টাকা পাঠিয়েছি" → যাচাই হচ্ছে; সময় হলে কলে যুক্ত হওয়ার লিংক', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const errs = watchErrors(page);
  let pay: any = { payState: 'unpaid' };
  const seen = await mock(page, async (action, body, route) => {
    if (action === 'appt_pay_claim') {
      pay = { payState: 'claimed', payRef: body.payRef };
      return { ok: true };
    }
    if (action === 'appointment_queue' && body.id === 'on1') return { live: false };
    if (action !== 'my_appointments') return undefined;
    const j = await base(route);
    j.items.unshift({
      id: 'on1', role: 'patient', type: 'doctor', otherName: 'ডা. রুবিনা ইয়াসমিন', chamber: '', chamberBn: '', room: '', start: nowHM(), serial: 2, schedule: '',
      date: today(), note: 'জ্বর', status: 'confirmed', providerNote: '', otherPhone: '', otherKey: '', createdAt: Date.now() - DAY,
      online: true, bkashNumber: '01712345678', fee: '৫০০ টাকা', noFee: false, onlineNote: 'নেট চালু রাখবেন', ...pay,
    });
    return j;
  });
  await open(page, def('appointment', 'my-detail'), '/my-appointments/on1');
  const box = page.getByTestId('online-pay');
  await expect(box.getByTestId('pay-state')).toHaveText('পাঠানো হয়নি');
  await expect(box.getByTestId('pay-number')).toHaveText('০১৭১২৩৪৫৬৭৮');
  await expect(box.locator('[data-testid=pay-steps] li')).toHaveCount(4);
  await expect(box).toContainText('নেট চালু রাখবেন');
  await box.getByTestId('pay-copy').click();
  await expect(box.getByTestId('pay-copy')).toHaveText('কপি হয়েছে');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('01712345678');

  const join = page.getByTestId('join-call');
  await expect(join).toBeVisible();
  await expect(join).toHaveAttribute('href', /\/appointments\/on1\/call\?name=/);

  await box.getByTestId('trx-input').fill('9gh7k2lm1p');
  await box.getByTestId('pay-claim').click();
  await expect.poll(() => seen.find((c) => c.action === 'appt_pay_claim')?.body).toMatchObject({ action: 'appt_pay_claim', appointmentId: 'on1', payRef: '9GH7K2LM1P' });
  await expect(box.getByTestId('pay-state')).toHaveText('যাচাই হচ্ছে');
  await expect(box).toContainText('9GH7K2LM1P');
  await expect(box.getByTestId('pay-claim')).toHaveCount(0);
  expect(errs).toEqual([]);
});

test('রোগী: ডাক্তার পাননি — কারণ আর আবার জানানো; ফি পরিশোধিত', async ({ page }) => {
  let pay: any = { payState: 'unpaid', payNote: 'এই TrxID-তে টাকা আসেনি', payRef: 'OLD1' };
  await mock(page, async (action, body, route) => {
    if (action === 'appointment_queue' && body.id === 'on2') return { live: false };
    if (action !== 'my_appointments') return undefined;
    const j = await base(route);
    j.items.unshift({
      id: 'on2', role: 'patient', type: 'doctor', otherName: 'ডা. রুবিনা ইয়াসমিন', chamber: '', start: '20:00', date: today() + 2 * DAY,
      note: 'জ্বর', status: 'confirmed', createdAt: Date.now(), online: true, bkashNumber: '01712345678', fee: '৫০০ টাকা', ...pay,
    });
    return j;
  });
  await open(page, def('appointment', 'my-detail'), '/my-appointments/on2');
  const box = page.getByTestId('online-pay');
  await expect(box.getByTestId('pay-state')).toHaveText('ডাক্তার পাননি');
  await expect(box).toContainText('কারণ: এই TrxID-তে টাকা আসেনি');
  await expect(box.getByTestId('pay-claim')).toBeVisible();
  // সামনের দিন: এখনো কলের সময় নয়
  await expect(page.getByTestId('join-call')).toHaveCount(0);
  await expect(page.getByTestId('join-later')).toBeVisible();

  pay = { payState: 'paid', payRef: 'NEW2' };
  await page.getByRole('button', { name: 'রিফ্রেশ' }).first().click();
  await expect(box.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
  await expect(box.getByTestId('pay-number')).toHaveCount(0);
});

test('ডাক্তার: সেটআপে "অনলাইন (ভিডিও)" — ঠিকানা/রুম নেই, bKash নম্বর যাচাই, সেভে mode/bkashNumber/note', async ({ page }) => {
  const errs = watchErrors(page);
  const seen = await mock(page, async () => undefined);
  await open(page, def('appointment', 'pro-setup'));
  await page.getByTestId('add-online').click();
  const card = page.locator('.appt-ch-ed[data-mode="online"]');
  await expect(card).toBeVisible();
  await expect(card.getByRole('button', { name: 'অনলাইন (ভিডিও)' })).toHaveAttribute('aria-pressed', 'true');
  await expect(card.getByText('রুম (ঐচ্ছিক)')).toHaveCount(0);
  await expect(card.getByText('ঠিকানা', { exact: true })).toHaveCount(0);
  // প্রোফাইলের ফোন আগে থেকে
  await expect(card.getByTestId('bkash-number')).toHaveValue('01711000999');
  // অনলাইন চেম্বার একটাই
  await expect(page.getByTestId('add-online')).toHaveCount(0);

  // "চেম্বারে"-তে ফেরালে হাসপাতাল বেছে নিতে বলে; বন্ধ করে আবার অনলাইন
  const third = page.locator('.appt-ch-ed').nth(2);
  await third.getByRole('button', { name: 'চেম্বারে', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(third).toContainText('হাসপাতাল বেছে নিন');
  await third.getByRole('button', { name: 'অনলাইন (ভিডিও)' }).click();
  await expect(card).toBeVisible();

  await card.getByRole('button', { name: 'শুক্র' }).click();
  const times = card.locator('input[type="time"]');
  await times.nth(0).fill('20:00');
  await times.nth(1).fill('22:00');
  await card.getByLabel('ফি', { exact: true }).fill('৫০০ টাকা');
  await card.getByTestId('bkash-number').fill('01212345678');
  await expect(card).toContainText('প্রতিটা অনলাইন চেম্বারে সঠিক bKash নম্বর দিন');
  await page.getByRole('button', { name: 'সেভ করুন' }).click();
  await expect(page.locator('.appt-savebar')).toContainText('প্রতিটা অনলাইন চেম্বারে সঠিক bKash নম্বর দিন');
  expect(seen.some((c) => c.action === 'provider_save')).toBe(false);

  await card.getByTestId('bkash-number').fill('+8801812345678');
  await card.getByTestId('online-note').fill('সময়মতো নেট চালু রাখবেন');
  await page.getByRole('button', { name: 'সেভ করুন' }).click();
  await expect.poll(() => seen.find((c) => c.action === 'provider_save')?.body.chambers.length).toBe(3);
  const ch = seen.find((c) => c.action === 'provider_save')!.body.chambers;
  expect(ch[0]).toMatchObject({ mode: 'in_person', hospitalId: 'dghs_3' });
  expect(ch[2]).toMatchObject({ mode: 'online', bkashNumber: '01812345678', note: 'সময়মতো নেট চালু রাখবেন', fee: '৫০০ টাকা', days: [5], start: '20:00', end: '22:00', appointments: true });
  expect(ch[2].hospitalId).toBeUndefined();
  expect(ch[2].address).toBeUndefined();

  expect(errs).toEqual([]);
});

async function proBookings(page: Page, payState: string) {
  let pay: any = { payState, payRef: 'TRX42AB' };
  const seen = await mock(page, async (action, body, route) => {
    if (action === 'appt_pay_confirm') {
      pay = { payState: 'paid', payRef: 'TRX42AB' };
      return { ok: true };
    }
    if (action === 'appt_pay_reject') {
      pay = { payState: 'unpaid', payNote: body.reason ?? '', payRef: 'TRX42AB' };
      return { ok: true };
    }
    if (action !== 'my_appointments') return undefined;
    const j = await base(route);
    j.items.push({
      id: 'onp1', role: 'provider', type: 'doctor', otherName: 'মো. জসিম উদ্দিন', chamber: '', chamberBn: '', start: nowHM(), serial: 4, date: today(),
      note: 'মাথাব্যথা', status: 'confirmed', providerNote: '', otherPhone: '', otherKey: 'kx', createdAt: Date.now(), online: true, fee: '৫০০ টাকা', noFee: false, ...pay,
    });
    return j;
  });
  await open(page, def('appointment', 'pro-bookings'));
  return seen;
}

for (const size of ['mobile', 'desktop @desktop']) {
  test(`ডাক্তার: বুকিংয়ে ফির অবস্থা, TrxID, "ফি পেয়েছি" নিশ্চিত ধাপসহ, কলের লিংক (${size})`, async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await proBookings(page, 'claimed');
    const pro = page.getByTestId('online-pay-pro');
    await expect(pro.getByTestId('pay-state')).toHaveText('রোগী ফি পাঠিয়েছেন');
    await expect(pro.getByTestId('pay-ref')).toContainText('TRX42AB');
    await expect(pro.getByTestId('join-call')).toHaveAttribute('href', /\/appointments\/onp1\/call\?role=provider&name=/);

    await pro.getByTestId('pay-got').click();
    const dlg = page.getByRole('dialog');
    await expect(dlg).toContainText('ফি পেয়েছেন?');
    await expect(dlg).toContainText('TRX42AB');
    expect(seen.some((c) => c.action === 'appt_pay_confirm')).toBe(false);
    await dlg.getByRole('button', { name: 'ফি পেয়েছি' }).click();
    await expect.poll(() => seen.find((c) => c.action === 'appt_pay_confirm')?.body).toMatchObject({ action: 'appt_pay_confirm', appointmentId: 'onp1' });
    await expect(pro.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
    await expect(pro.getByTestId('pay-got')).toHaveCount(0);
    expect(errs).toEqual([]);
  });
}

test('ডাক্তার: "পাইনি" — কারণসহ appt_pay_reject', async ({ page }) => {
  const seen = await proBookings(page, 'claimed');
  const pro = page.getByTestId('online-pay-pro');
  await pro.getByTestId('pay-not').click();
  const dlg = page.getByRole('dialog');
  await expect(dlg).toContainText('ফি পাননি?');
  await dlg.getByTestId('reject-reason').fill('টাকা আসেনি');
  await dlg.getByTestId('reject-confirm').click();
  await expect.poll(() => seen.find((c) => c.action === 'appt_pay_reject')?.body).toMatchObject({ action: 'appt_pay_reject', appointmentId: 'onp1', reason: 'টাকা আসেনি' });
  await expect(pro.getByTestId('pay-state')).toHaveText('ফি পাননি');
});

test('সহকারী: রোগীর তালিকায় অনলাইন অ্যাপয়েন্টমেন্টের ফি — "ফি পেয়েছি" ডাক্তারের uid সাথে', async ({ page }) => {
  const errs = watchErrors(page);
  let pay: any = { payState: 'claimed', payRef: 'ASST77' };
  const seen = await mock(page, async (action, body, route) => {
    if (action === 'appt_pay_confirm') {
      pay = { payState: 'paid', payRef: 'ASST77' };
      return { ok: true };
    }
    if (action !== 'rx_patients') return undefined;
    const j = await base(route);
    j.appointments = [{ id: 'ona1', name: 'রেহানা পারভীন', date: today(), serial: 3, status: 'confirmed', chamber: '', online: true, start: nowHM(), ...pay }, ...j.appointments];
    return j;
  });
  await open(page, def('rxdoctor', 'assistant'));
  const pro = page.getByTestId('online-pay-pro');
  await expect(pro.getByTestId('pay-state')).toHaveText('রোগী ফি পাঠিয়েছেন');
  await expect(pro.getByTestId('pay-ref')).toContainText('ASST77');
  await expect(pro.getByTestId('join-call')).toHaveAttribute('href', /\/appointments\/ona1\/call\?role=provider/);
  await pro.getByTestId('pay-got').click();
  await page.getByRole('dialog').getByRole('button', { name: 'ফি পেয়েছি' }).click();
  await expect.poll(() => seen.find((c) => c.action === 'appt_pay_confirm')?.body).toMatchObject({ action: 'appt_pay_confirm', appointmentId: 'ona1', doctorUid: 'doc1' });
  await expect(pro.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
  expect(errs).toEqual([]);
});

test('রোগী: একই TrxID আবার (409) বাংলায়; ফি দিয়েছেন কিন্তু ডাক্তার আসেননি → "রিপোর্ট করুন" (appt_report)', async ({ page }) => {
  const errs = watchErrors(page);
  let pay: any = { payState: 'unpaid' };
  const twoHoursAgo = new Date(Date.now() + 6 * H - 2 * H);
  const start = `${String(twoHoursAgo.getUTCHours()).padStart(2, '0')}:${String(twoHoursAgo.getUTCMinutes()).padStart(2, '0')}`;
  const seen = await mock(page, async (action, body, route) => {
    if (action === 'appt_report') return { ok: true };
    if (action === 'appointment_queue' && body.id === 'on3') return { live: false };
    if (action !== 'my_appointments') return undefined;
    const j = await base(route);
    j.items.unshift({
      id: 'on3', role: 'patient', type: 'doctor', otherName: 'ডা. রুবিনা ইয়াসমিন', chamber: '', start, date: Date.parse(twoHoursAgo.toISOString().slice(0, 10)),
      note: 'জ্বর', status: 'confirmed', createdAt: Date.now(), online: true, bkashNumber: '01712345678', fee: '৫০০ টাকা', ...pay,
    });
    return j;
  });
  await page.route('**/api/community', async (route) => {
    const b = route.request().postDataJSON() || {};
    if (b.action !== 'appt_pay_claim') return route.fallback();
    seen.push({ action: b.action, body: b });
    return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'TrxID already used', code: 'pay_ref_used' }) });
  });
  await open(page, def('appointment', 'my-detail'), '/my-appointments/on3');
  const box = page.getByTestId('online-pay');
  await box.getByTestId('trx-input').fill('USED990011');
  await box.getByTestId('pay-claim').click();
  await expect(box).toContainText('এই TrxID আগেই ব্যবহার হয়েছে। মিলিয়ে সঠিক TrxID লিখুন।');
  await expect(box.getByTestId('pay-state')).toHaveText('পাঠানো হয়নি');
  await expect(box.getByTestId('appt-report')).toHaveCount(0);

  pay = { payState: 'paid', payRef: 'OK77' };
  await page.getByRole('button', { name: 'রিফ্রেশ' }).first().click();
  await expect(box.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
  await box.getByTestId('appt-report').click();
  const dlg = page.getByRole('dialog');
  await dlg.getByLabel('কী হয়েছে').fill('ডাক্তার কলে আসেননি');
  await dlg.getByRole('button', { name: 'রিপোর্ট', exact: true }).click();
  await expect.poll(() => seen.find((c) => c.action === 'appt_report')?.body).toMatchObject({ appointmentId: 'on3', reason: 'ডাক্তার কলে আসেননি' });
  await expect(box).toContainText('রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।');
  expect(errs.filter((e) => !/409/.test(e))).toEqual([]);
});

test('ফিচার সুইচ বন্ধ: তালিকা, সেটআপ, অ্যাপয়েন্টমেন্ট — আগের মতোই (অনলাইনের কিছু নেই)', async ({ page }) => {
  const errs = watchErrors(page);
  const seen = await mock(page, async (action, body, route) => {
    if (action === 'providers') {
      const j = await base(route);
      return { ...j, items: [...j.items, ONLINE_DOC] };
    }
    if (action === 'appointment_queue' && body.id === 'on9') return { live: false };
    if (action !== 'my_appointments') return undefined;
    const j = await base(route);
    j.items.unshift({ id: 'on9', role: 'patient', type: 'doctor', otherName: 'ডা. রুবিনা ইয়াসমিন', chamber: 'Online video consultation', start: nowHM(), date: today(), note: 'জ্বর', status: 'confirmed', createdAt: Date.now(), online: true, payState: 'unpaid', bkashNumber: '01712345678', fee: '৫০০ টাকা' });
    return j;
  }, {});
  await open(page, def('appointment', 'list'));
  await expect(page.locator('.card', { hasText: 'ডা. মাহমুদা আক্তার' }).first()).toBeVisible();
  await expect(page.locator('.appt-qchips').getByRole('button', { name: 'অনলাইন', exact: true })).toHaveCount(0);
  await expect(page.locator('.card .tag', { hasText: /^অনলাইন$/ })).toHaveCount(0);
  expect(seen.filter((c) => c.action === 'providers').every((c) => c.body.online === undefined)).toBe(true);

  await open(page, def('appointment', 'my-detail'), '/my-appointments/on9');
  await expect(page.getByText('কী সমস্যা')).toBeVisible();
  await expect(page.getByTestId('online-pay')).toHaveCount(0);
  await expect(page.getByTestId('join-call')).toHaveCount(0);

  await open(page, def('appointment', 'pro-setup'));
  await expect(page.getByRole('button', { name: 'চেম্বার যোগ করুন' })).toBeVisible();
  await expect(page.getByTestId('add-online')).toHaveCount(0);
  await expect(page.getByText('চেম্বারের ধরন')).toHaveCount(0);
  expect(errs).toEqual([]);
});

test('ডাক্তার: ফি দেওয়া অনলাইন অ্যাপয়েন্টমেন্ট বাতিলে কারণ চাই (reason_required), providerNote যায়', async ({ page }) => {
  const seen = await proBookings(page, 'paid');
  const pro = page.getByTestId('online-pay-pro');
  await expect(pro.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
  const row = page.locator('article, tr').filter({ has: pro });
  await row.getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট বাতিল' }).click();
  const dlg = page.getByRole('dialog');
  await expect(dlg.getByTestId('reason-needed')).toContainText('টাকা ফেরতের জন্য');
  await dlg.getByTestId('cancel-reason-send').click();
  await expect(dlg).toContainText('কারণ লিখুন');
  expect(seen.some((c) => c.action === 'appointment_update')).toBe(false);
  await dlg.getByTestId('cancel-reason').fill('জরুরি অপারেশনে আছি');
  await dlg.getByTestId('cancel-reason-send').click();
  await expect.poll(() => seen.find((c) => c.action === 'appointment_update')?.body).toMatchObject({ id: 'onp1', op: 'cancel', providerNote: 'জরুরি অপারেশনে আছি' });
});

test('অ্যাডমিন: "অনলাইন অ্যাপয়েন্টমেন্ট রিপোর্ট" — মেনু (সুইচ চালু হলে), তালিকা, সমাধান', async ({ page }) => {
  const errs = watchErrors(page);
  const seen = await mock(page, async (action, body) => {
    if (action === 'admin_appt_reports') {
      return {
        items: body.status === 'open'
          ? [{
            id: 'r1', reporterUid: 'u9', reporterRole: 'patient', reason: 'ফি দিয়েছি, ডাক্তার আসেননি', status: 'open', createdAt: Date.now() - H,
            appointment: { id: 'on1', patientName: 'রহিম', providerName: 'ডা. রুবিনা', date: today(), start: '19:30', status: 'confirmed', fee: '৫০০ টাকা', payState: 'paid', payRef: 'ABCDE12345', bkashNumber: '01712345678' },
            payLog: [{ by: 'patient', from: 'unpaid', to: 'claimed', payRef: 'ABCDE12345', at: Date.now() - 3 * H }, { by: 'doctor', from: 'claimed', to: 'paid', at: Date.now() - 2 * H }],
            bkashChanges: [{ oldLast3: '678', newLast3: '999', by: 'u7', at: Date.now() - DAY }],
          }]
          : [],
      };
    }
    if (action === 'admin_appt_report_set') return { ok: true };
    // মেনু: ফিচার সুইচের পাতার অবস্থা (প্যানেলে `features` চলে না)
    if (action === 'admin_features_get') return { items: [{ key: 'online_chamber', mode: 'on', pilotUids: [], pilots: [], demo: false, needs: [], twoParty: false }], history: [], v: 1 };
    return undefined;
  });
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
  const link = page.locator('nav.nav a', { hasText: 'অনলাইন, কল ও মেসেজের রিপোর্ট' });
  await expect(link).toBeVisible();
  await page.goto('/admin#/apptreports');
  await expect(page.locator('.topbar h1')).toContainText('অনলাইন অ্যাপয়েন্টমেন্ট রিপোর্ট');
  const main = page.locator('main.page');
  await expect(main).toContainText('ফি দিয়েছি, ডাক্তার আসেননি');
  await expect(main).toContainText('ABCDE12345');
  await expect(main).toContainText('রোগী পাঠিয়েছেন');
  await expect(main).toContainText('…৬৭৮ → …৯৯৯');
  await main.getByRole('button', { name: 'সমাধান হয়েছে' }).click();
  await expect.poll(() => seen.find((c) => c.action === 'admin_appt_report_set')?.body).toMatchObject({ id: 'r1', status: 'resolved' });
  expect(errs.filter((e) => !/401|Unauthorized/.test(e))).toEqual([]);
});
