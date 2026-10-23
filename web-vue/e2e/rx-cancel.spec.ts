// প্রেসক্রিপশন বাতিলের কারণ (backend/docs/RX-STATE.md "Cancellation reason"):
//  - ডাক্তার: "এই প্রেসক্রিপশন বাতিল করুন" → কারণ (রেডিও) + নোট ("অন্য কারণ"-এ লাগবে) → নিশ্চিত → rx_cancel {id, reason, note}
//  - রোগী: ধূসর কার্ডে "বাতিল করেছেন ডাক্তার — <কারণ>", ভেতরে টাইমলাইনে কারণ আর নোট
// ৩৭৫px আর ডেস্কটপ; RX_SHOTS দিলে ছবি সেখানে।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const D = 24 * 3600 * 1000;
const now = Date.now();
const NOTE = 'রিপোর্টে কিডনির সমস্যা — Napa-র মাত্রা কমিয়ে নতুন প্রেসক্রিপশন দেওয়া হলো';
const shot = async (page: Page, name: string) => {
  if (process.env.RX_SHOTS) await page.screenshot({ path: `${process.env.RX_SHOTS}/${name}.png`, fullPage: true });
};
const json = (route: Route, body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

// ---------------- ডাক্তার ----------------

async function doctorMock(page: Page) {
  const sent: any[] = [];
  let cancelled = false;
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'rx_cancel') {
      sent.push(body);
      cancelled = true;
      return json(route, { ok: true });
    }
    if (body.action === 'rx_get' && cancelled) {
      const j = await (await route.fetch()).json();
      return json(route, { ...j, item: { ...j.item, status: 'cancelled' } });
    }
    return route.continue();
  });
  return sent;
}

async function doctorFlow(page: Page, shotName: string) {
  const errs = watchErrors(page);
  const sent = await doctorMock(page);
  await open(page, def('rxdoctor', 'view'));
  await page.getByRole('button', { name: 'এই প্রেসক্রিপশন বাতিল করুন' }).click();
  const d = page.getByRole('dialog');
  await expect(d.getByText('কেন বাতিল করছেন?')).toBeVisible();
  const radios = d.getByRole('radio');
  await expect(radios).toHaveCount(5);
  for (const label of ['ভুল ওষুধ/মাত্রা', 'ভুল রোগী', 'নতুন প্রেসক্রিপশন দিয়েছি', 'রোগী জানিয়েছেন সমস্যা', 'অন্য কারণ']) {
    await expect(d.getByRole('radio', { name: label })).toBeVisible();
  }
  const next = d.getByTestId('rx-cancel-next');
  await expect(next).toBeDisabled();
  // "অন্য কারণ": নোট ছাড়া বা ৩ অক্ষরের কম হলে এগোনো যায় না
  await d.getByRole('radio', { name: 'অন্য কারণ' }).check();
  await expect(d.getByLabel('কারণটা লিখুন (৩–২০০ অক্ষর)')).toBeVisible();
  await expect(next).toBeDisabled();
  await d.getByLabel('কারণটা লিখুন (৩–২০০ অক্ষর)').fill('ab');
  await expect(d.getByText('অন্তত ৩ অক্ষর')).toBeVisible();
  await expect(next).toBeDisabled();
  // অন্য কারণ বাছলে নোট ঐচ্ছিক — যা লেখা ছিল থাকে
  await d.getByRole('radio', { name: 'রোগী জানিয়েছেন সমস্যা' }).check();
  await expect(d.getByLabel('নোট (ঐচ্ছিক)')).toBeVisible();
  await d.getByLabel('নোট (ঐচ্ছিক)').fill(NOTE);
  await expect(next).toBeEnabled();
  await noSideScroll(page);
  await shot(page, `${shotName}-pick`);
  await next.click();
  // নিশ্চিতের ধাপ: কারণ আর নোট, এখনো কিছু যায়নি
  const sum = d.getByTestId('rx-cancel-summary');
  await expect(sum).toContainText('রোগী জানিয়েছেন সমস্যা');
  await expect(sum).toContainText(NOTE);
  expect(sent).toEqual([]);
  await shot(page, `${shotName}-confirm`);
  // ফিরে গিয়ে বদলানো যায়
  await d.getByRole('button', { name: 'ফিরে যান' }).click();
  await expect(d.getByRole('radio', { name: 'রোগী জানিয়েছেন সমস্যা' })).toBeChecked();
  await d.getByTestId('rx-cancel-next').click();
  await d.getByRole('button', { name: 'বাতিল করুন', exact: true }).click();
  await expect(page.getByText('বাতিল করা হয়েছে')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(sent).toHaveLength(1);
  expect(sent[0]).toMatchObject({ action: 'rx_cancel', id: 'rxd1', reason: 'patient_reported', note: NOTE });
  // বাতিলের পর বাটন আর নেই
  await expect(page.getByRole('button', { name: 'এই প্রেসক্রিপশন বাতিল করুন' })).toHaveCount(0);
  expect(errs).toEqual([]);
}

test.describe('ডাক্তার: কারণসহ বাতিল (৩৭৫px)', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('কারণ (রেডিও) → নোট → নিশ্চিত → rx_cancel {id, reason, note}', async ({ page }) => {
    await doctorFlow(page, 'web-rx-cancel-375');
  });

  test('"রেখে দিন": কিছু পাঠানো হয় না', async ({ page }) => {
    const sent = await doctorMock(page);
    await open(page, def('rxdoctor', 'view'));
    await page.getByRole('button', { name: 'এই প্রেসক্রিপশন বাতিল করুন' }).click();
    await page.getByRole('dialog').getByRole('radio', { name: 'ভুল রোগী' }).check();
    await page.getByRole('dialog').getByRole('button', { name: 'রেখে দিন' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(sent).toEqual([]);
    await expect(page.getByRole('button', { name: 'এই প্রেসক্রিপশন বাতিল করুন' })).toBeVisible();
  });
});

test('ডাক্তার: কারণসহ বাতিল @desktop', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'শুধু ১৪৪০ প্রস্থে');
  await doctorFlow(page, 'web-rx-cancel-desktop');
});

// ---------------- রোগী ----------------

async function patientMock(page: Page) {
  let list: any[] = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'rx_mine') {
      const j = await (await route.fetch()).json();
      const rx1 = j.items[0];
      const cancelledState = (reason: string | null, note: string | null) => ({
        phase: 'past', primary: 'cancelled', tone: 'muted', cancelled: true, cancelledAt: now - 2 * D, cancelReason: reason, cancelNote: note,
        medicine: null, lab: null, followUpDate: null, lastActivityAt: now - 2 * D,
      });
      list = [
        { ...rx1, id: 'rxc1', status: 'cancelled', createdAt: now - 3 * D, doctor: { ...rx1.doctor, name: 'ডা. কামরুল ইসলাম' }, state: cancelledState('patient_reported', NOTE) },
        { ...rx1, id: 'rxc2', status: 'cancelled', createdAt: now - 9 * D, doctor: { ...rx1.doctor, name: 'ডা. রেহানা পারভীন' }, state: cancelledState(null, null) },
      ];
      return json(route, { items: list });
    }
    if (body.action === 'rx_get' && list.some((r) => r.id === body.id)) {
      const r = list.find((x) => x.id === body.id);
      const timeline = [
        { kind: 'written', at: r.createdAt },
        { kind: 'cancelled', at: now - 2 * D, ...(r.state.cancelReason ? { reason: r.state.cancelReason } : {}), ...(r.state.cancelNote ? { note: r.state.cancelNote } : {}) },
      ];
      return json(route, { item: { ...r, state: { ...r.state, timeline } } });
    }
    return route.continue();
  });
}
const listPage = () => def('health', 'rx-list');

async function patientFlow(page: Page, shotName: string) {
  const errs = watchErrors(page);
  await patientMock(page);
  await open(page, listPage(), '/services/pharmacy/prescriptions?tab=past');
  await expect(page.getByRole('tab', { name: 'পূর্বের (২)' })).toHaveAttribute('aria-selected', 'true');
  const cards = page.locator('article.card');
  await expect(cards.getByTestId('rx-status-chip')).toHaveText(['বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা', 'বাতিল করেছেন ডাক্তার']);
  await expect(cards.first()).toHaveClass(/rx-cancelled/);
  // কার্ডে নোট নেই (শুধু ভেতরে)
  await expect(cards.filter({ hasText: NOTE })).toHaveCount(0);
  await noSideScroll(page);
  await shot(page, `${shotName}-list`);
  await cards.first().click();
  const tl = page.getByTestId('rx-timeline');
  await expect(tl.getByTestId('rx-status-chip')).toHaveText('বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা');
  const steps = tl.locator('li');
  await expect(steps).toHaveCount(2);
  await expect(steps.nth(1)).toContainText('বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা');
  await expect(tl.getByTestId('rx-cancel-note')).toHaveText(NOTE);
  await noSideScroll(page);
  await shot(page, shotName);
  expect(errs).toEqual([]);
}

test.describe('রোগী: বাতিলের কারণ (৩৭৫px)', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('ধূসর কার্ডে "বাতিল করেছেন ডাক্তার — <কারণ>", ভেতরে কারণ আর নোট', async ({ page }) => {
    await patientFlow(page, 'web-rx-cancelled-375');
  });

  test('কারণ ছাড়া বাতিল (আগের release): শুধু "বাতিল করেছেন ডাক্তার", নোট নেই', async ({ page }) => {
    await patientMock(page);
    await open(page, listPage(), '/services/pharmacy/prescriptions/rxc2?tab=past');
    const tl = page.getByTestId('rx-timeline');
    await expect(tl.getByTestId('rx-status-chip')).toHaveText('বাতিল করেছেন ডাক্তার');
    await expect(tl.getByTestId('rx-cancel-note')).toHaveCount(0);
  });
});

test('রোগী: বাতিলের কারণ @desktop', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'শুধু ১৪৪০ প্রস্থে');
  await patientFlow(page, 'web-rx-cancelled-desktop');
});
