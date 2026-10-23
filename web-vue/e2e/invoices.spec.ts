// রসিদ (INVOICES.md): যাচাই পাতা (লগইন ছাড়া), রোগীর রসিদ/খরচ/কেয়ারগিভার, ফার্মেসির বিক্রি (POS) → রসিদ আর ছাপা,
// রসিদের তালিকা (বাতিল কারণসহ, বাকি আদায়, দিনের শেষ, CSV), অনলাইন অর্ডার পৌঁছে দেওয়া, প্যাথলজির বিল, চেম্বারের ডেস্ক,
// অ্যাম্বুলেন্সের "রসিদের চেয়ে বেশি নিয়েছে", ডেস্কটপের অফলাইন পথ (নকল OfflineStore) আর ফিচার সুইচ বন্ধ থাকলে আগের মতো।
// নকল সার্ভার: test/fixtures/invoices.mjs; বদলের কাজগুলো এখানে page.route দিয়ে ধরা।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const patient = (path: string, extra: Record<string, unknown> = {}) => {
  const d = def('health', 'rx-list');
  return { ...d, path, test: { ...d.test, ...extra } };
};
const owner = (path: string, extra: Record<string, unknown> = {}) => {
  const d = def('shopbooks', 'bk-items');
  return { ...d, path, test: { ...d.test, ...extra } };
};
const doctor = (path: string, extra: Record<string, unknown> = {}) => {
  const d = def('rxdoctor', 'patients');
  return { ...d, path, test: { ...d.test, ...extra } };
};
const guest = (path: string) => ({ name: 'guest', path, test: { lang: 'bn', theme: 'light' } });

type Seen = { action: string; body: any }[];
/** কাজগুলো দেখা; reply দিলে সেই উত্তর, নইলে নকল সার্ভারে যায় */
async function spy(page: Page, reply: Record<string, (b: any) => unknown> = {}): Promise<Seen> {
  const seen: Seen = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: String(body.action), body });
    const f = reply[String(body.action)];
    if (f) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(f(body)) });
    return route.continue();
  });
  return seen;
}
/** কার্ডের কাজ: মূল বোতাম হলে সেটা, নইলে "আরও" মেনু থেকে (আমার কাজ CONTRACT §২ — সারিতে একটাই মূল বোতাম) */
async function cardAct(page: Page, cards: ReturnType<Page['getByTestId']>, testid: string) {
  const n = await cards.count();
  for (let i = 0; i < n; i++) {
    const c = cards.nth(i);
    if (await c.getByTestId(testid).count()) return c.getByTestId(testid).click();
    if (!(await c.getByTestId('row-more').count())) continue;
    await c.getByTestId('row-more').click();
    const m = page.getByRole('menu').getByTestId(testid);
    if (await m.count()) return m.click();
    await page.keyboard.press('Escape');
  }
  throw new Error(`no ${testid}`);
}
const bodyOf = (seen: Seen, action: string) => seen.filter((x) => x.action === action).map((x) => x.body);
/** window.print গোনা (পরীক্ষায় আসল প্রিন্টের জানালা নয়) */
async function countPrints(page: Page) {
  await page.addInitScript(() => {
    (window as any).__prints = 0;
    window.print = () => {
      (window as any).__prints++;
    };
  });
}
const prints = (page: Page) => page.evaluate(() => (window as any).__prints as number);
const shown = (page: Page) => page.locator('.inv-paper-wrap [data-testid=receipt]');

const SALE_INV = (b: any) => ({
  sale: { id: 's9', at: Date.now(), day: '', source: 'counter', lines: [], totals: { gross: 12, discount: 0, billDiscount: 0, net: 12, cost: 9, profit: 3, netCosted: 12, unpriced: 0 } },
  invoice: {
    id: 'n1', no: 'W-000202', kind: 'pharmacy', bizId: 'u-work', bizName: 'নিউ লাইফ ফার্মেসি', customer: b.customer ?? {},
    lines: [{ name: 'Napa', qty: 10, price: 1.2, disc: 0, amount: 12 }], subtotal: 12, disc: 0, fees: [], total: 12, paid: 12, due: 0,
    payments: [{ at: Date.now(), amount: 12, method: 'cash' }], status: 'issued', issuedAt: Date.now(), issuedBy: 'u-work', offline: false,
    verifyCode: 'NEW0000001', verifyUrl: 'https://lifolo.com/v/NEW0000001',
  },
});

test.describe('যাচাই পাতা (লগইন ছাড়া)', () => {
  test('বৈধ: নম্বর, তারিখ, মোট বড় করে, লাইনের সংখ্যা, "মিলিয়ে নিন"; ক্রেতার কিছু নয়', async ({ page }) => {
    const errs = watchErrors(page);
    await open(page, guest('/v/PH4K2M9QXA'));
    const r = page.getByTestId('verify-result');
    await expect(r).toContainText('এই রসিদটি আসল');
    await expect(page.getByTestId('verify-status')).toHaveText('বৈধ');
    await expect(page.getByTestId('verify-no')).toHaveText('W-000041');
    await expect(page.getByTestId('verify-total')).toHaveText('৳১৫০');
    await expect(page.getByTestId('verify-lines')).toHaveText('রসিদে ২টা লাইন');
    await expect(r).toContainText('মেডিপ্লাস ফার্মেসি');
    await expect(r).toContainText('কাগজের সাথে নম্বর, তারিখ আর মোট মিলিয়ে নিন');
    await expect(r).not.toContainText('পারভেজ');
    await expect(r).not.toContainText('Napa');
    await noSideScroll(page);
    expect(errs).toEqual([]);
  });
  test('বাতিল, ডেমো ("ডেমো — আসল রসিদ নয়", কখনো "বৈধ" নয়), না পাওয়া, কোড ছাড়া', async ({ page }) => {
    await open(page, guest('/v/PH0V0ID9QX'));
    await expect(page.getByTestId('verify-result')).toContainText('এই রসিদটি বাতিল করা হয়েছে');
    await expect(page.getByTestId('verify-status')).toHaveText('বাতিল');
    await page.goto('/v/DEMO0A1B2C', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('verify-demo')).toHaveText('ডেমো — আসল রসিদ নয়');
    await expect(page.getByTestId('verify-status')).toHaveCount(0);
    await expect(page.getByTestId('verify-result')).not.toContainText('বৈধ');
    await page.goto('/v/ZZZZZZZZZZ', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('verify-none')).toContainText('২৪ ঘণ্টা পরেও না পেলে রসিদটা সন্দেহজনক');
    await page.goto('/v', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('verify-domain')).toHaveText('শুধু lifolo.com ঠিকানাই আসল');
    await page.getByRole('textbox', { name: 'কোড' }).fill('LB7H3N2WQP');
    await page.getByRole('button', { name: 'রসিদ যাচাই' }).click();
    await expect(page).toHaveURL(/\/v\/LB7H3N2WQP$/);
    await expect(page.getByTestId('verify-result')).toContainText('পপুলার ডায়াগনস্টিক সেন্টার');
  });
});

test.describe('রোগী', () => {
  test('আমার রসিদ → একটা রসিদ: কাগজ, অঙ্ক বদল, প্রিন্ট / PDF; কেয়ারগিভার বদলালে asUid', async ({ page }) => {
    const errs = watchErrors(page);
    await countPrints(page);
    const seen = await spy(page);
    await open(page, patient('/invoices'));
    const cards = page.getByTestId('my-invoice');
    await expect(cards).toHaveCount(5);
    await expect(cards.nth(1)).toContainText('বাকি ৳১৯৫');
    await expect(cards.nth(4)).toContainText('বাতিল');
    await cards.nth(1).click();
    await expect(page).toHaveURL(/\/invoices\/inv2$/);
    await expect(shown(page)).toContainText('পপুলার ডায়াগনস্টিক সেন্টার');
    await expect(shown(page)).toContainText('৳৫৯৫.০০');
    await expect(shown(page)).toContainText('বাসা থেকে নমুনা');
    await expect(shown(page)).toContainText('lifolo.com/v/LB7H3N2WQP — রসিদ যাচাই করুন');
    await expect(shown(page)).toContainText('টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়');
    await page.getByRole('button', { name: '123' }).click();
    await expect(shown(page)).toContainText('৳595.00');
    await page.getByRole('button', { name: '৫৮ মিমি' }).click();
    await expect(shown(page)).toHaveClass(/p58/);
    await page.getByTestId('receipt-print').click();
    expect(await prints(page)).toBe(1);
    // ছাপার কপি পাতায় আছে (একই কাগজ)
    await expect(page.locator('.inv-print [data-testid=receipt]')).toHaveClass(/p58/);
    await page.goto('/invoices', { waitUntil: 'networkidle' });
    await page.getByTestId('care-switch').getByRole('button', { name: 'আমেনা বেগম' }).click();
    await expect.poll(() => bodyOf(seen, 'my_invoices').some((b) => b.asUid === 'p1')).toBe(true);
    await expect(page.getByText('আমেনা বেগম-এর হয়ে কাজ করছেন')).toBeVisible();
    expect(errs).toEqual([]);
  });
  test('আমার খরচ: মাসের মোট আর ধরন অনুযায়ী; আগের মাসে খালি', async ({ page }) => {
    await open(page, patient('/invoices/expenses'));
    await expect(page.getByTestId('exp-total')).toHaveText('৳৪,৪৪৫');
    await expect(page.locator('[data-kind=ambulance]')).toContainText('৳৩,০০০');
    await page.getByRole('button', { name: 'আগের মাস' }).click();
    await expect(page.getByTestId('my-expenses')).toContainText('এই মাসে কোনো খরচ নেই');
  });
  test('অ্যাপয়েন্টমেন্ট থেকে রসিদ, অ্যাম্বুলেন্সের রসিদে "রসিদের চেয়ে বেশি নিয়েছে" (invoice_report)', async ({ page }) => {
    const seen = await spy(page);
    await open(page, patient('/my-appointments/a1'));
    await page.getByTestId('invoice-link').click();
    await expect(page).toHaveURL(/\/invoices\/inv3$/);
    await expect(shown(page)).toContainText('ভিজিট ফি — নতুন');
    await page.goto('/invoices/inv4', { waitUntil: 'networkidle' });
    await expect(page.getByText('ভাড়ার তালিকার চেয়ে বেশি')).toBeVisible();
    await page.getByTestId('over-report').click();
    await page.getByTestId('over-paid').fill('3500');
    await page.getByRole('dialog').getByRole('textbox', { name: 'কী হয়েছে' }).fill('রসিদে কম লিখেছে');
    await page.getByTestId('over-send').click();
    await expect.poll(() => bodyOf(seen, 'invoice_report')).toEqual([expect.objectContaining({ id: 'inv4', paid: 3500, note: 'রসিদে কম লিখেছে' })]);
  });
});

test.describe('ফার্মেসি', () => {
  test('বিক্রি (POS): Enter-এ যোগ, ক্রেতা, কত পেলেন → ফেরত, বিক্রি লিখলে রসিদ আর সাথে সাথে প্রিন্ট', async ({ page }) => {
    const errs = watchErrors(page);
    await countPrints(page);
    const seen = await spy(page, { books_sale_add: SALE_INV });
    await open(page, owner('/work/pharmacy/shop/sales'));
    const search = page.getByRole('combobox', { name: 'খুঁজুন বা বারকোড স্ক্যান করুন' });
    await expect(search).toBeFocused();
    await search.fill('Napa');
    await search.press('Enter');
    await page.locator('.bk-line input').first().fill('10');
    await page.getByTestId('pos-cust-name').fill('রাকিব');
    await page.getByTestId('pay-given').fill('20');
    await expect(page.getByTestId('pay-change')).toContainText('ফেরত ৳৮');
    await page.keyboard.press('Control+Enter');
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('receipt')).toContainText('W-000202');
    await expect.poll(() => prints(page)).toBe(1);
    const [b] = bodyOf(seen, 'books_sale_add');
    expect(b.customer).toEqual({ name: 'রাকিব' });
    expect(b.payments).toEqual([{ amount: 12, method: 'cash' }]);
    expect(b.lines[0]).toMatchObject({ qty: 10, unitPrice: 1.2 });
    expect(errs).toEqual([]);
  });
  test('রসিদের তালিকা: আদায়/বাকির যোগফল, বাকি আদায়, বাতিল (কারণ + লেখা), দিনের শেষ, CSV', async ({ page }) => {
    const seen = await spy(page);
    await open(page, owner('/work/pharmacy/shop/invoices'));
    const box = page.getByTestId('biz-invoices');
    await expect(page.getByTestId('biz-invoice')).toHaveCount(3);
    await expect(page.getByTestId('sum-paid')).toHaveText('৳১৫০');
    await expect(page.getByTestId('sum-due')).toHaveText('৳২০');
    // ক্রেতার ফোন তালিকায় ঢাকা
    await expect(box).toContainText('০১৮••••৫৬৭৮');
    await page.getByTestId('row-collect').first().click();
    await page.getByTestId('due-save').click();
    await expect.poll(() => bodyOf(seen, 'invoice_pay')).toEqual([{ action: 'invoice_pay', id: 'b1', amount: 20, method: 'cash', lang: 'bn' }]);
    await page.getByTestId('row-void').first().click();
    const dlg = page.getByRole('dialog');
    await expect(dlg).toContainText('জিনিসগুলো স্টকে ফিরে যাবে।');
    await expect(page.getByTestId('void-confirm')).toBeDisabled();
    await dlg.locator('[data-reason=wrong_price]').click();
    await expect(page.getByTestId('void-confirm')).toBeDisabled();
    await dlg.getByRole('textbox').fill('দাম ভুল লেখা হয়েছিল');
    await page.getByTestId('void-confirm').click();
    await expect.poll(() => bodyOf(seen, 'invoice_void')).toEqual([expect.objectContaining({ id: 'b1', reason: 'wrong_price', note: 'দাম ভুল লেখা হয়েছিল' })]);
    await box.getByRole('tab', { name: 'দিনের শেষ' }).click();
    await expect(page.getByTestId('day-voids')).toContainText('১');
    await expect(page.getByTestId('day-summary')).toContainText('ক্রেতা ফেরত দিয়েছেন — পাতা খোলেনি');
    await expect(page.getByTestId('day-summary')).toContainText('১০%-এর বেশি ছাড়');
    await expect(page.getByTestId('day-summary')).toContainText('জিনিসের দামের নিচে বিক্রি');
    await expect(page.getByTestId('day-summary')).toContainText('Orsaline-N');
    await expect(page.getByTestId('day-by-issuer')).toContainText('কাউন্টার — রনি');
    expect(bodyOf(seen, 'invoice_day_summary')[0]).toMatchObject({ kind: 'pharmacy', bizId: 'u-work', day: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) });
    await box.getByRole('tab', { name: 'রসিদ' }).click();
    const dl = page.waitForEvent('download');
    await page.getByTestId('csv-export').click();
    expect((await dl).suggestedFilename()).toMatch(/^lifolo-pharmacy-\d{4}-\d{2}\.csv$/);
    await noSideScroll(page);
  });
  test('অনলাইন অর্ডার "পৌঁছে দিয়েছি": লাইন, ডেলিভারি চার্জ → rx_order_update delivered, রসিদ', async ({ page }) => {
    const seen = await spy(page, {
      rx_order_update: (b) => ({ ok: true, invoice: { ...SALE_INV(b).invoice, no: 'W-000203', lines: b.lines.map((l: any) => ({ ...l, amount: l.qty * l.price - l.disc })), fees: b.fees, total: 82, paid: 82 } }),
    });
    await open(page, owner('/work/pharmacy/orders?s=active'));
    await cardAct(page, page.getByTestId('order-card'), 'order-delivered');
    const dlg = page.getByRole('dialog');
    await dlg.getByRole('combobox').fill('Napa');
    await dlg.getByRole('combobox').press('Enter');
    await dlg.getByTestId('line-qty').first().fill('10');
    await expect(page.getByTestId('deliver-total')).toHaveText('৳৪২');
    await page.getByTestId('deliver-save').click();
    await expect(dlg.getByTestId('receipt')).toContainText('W-000203');
    const [b] = bodyOf(seen, 'rx_order_update');
    expect(b).toMatchObject({ status: 'delivered', lines: [{ name: 'Napa', qty: 10, price: 1.2, disc: 0, itemId: expect.any(String) }], fees: [{ label: 'ডেলিভারি চার্জ', amount: 30 }] });
  });
});

test.describe('প্যাথলজি', () => {
  const approvedLab = { lab_me: () => ({ lab: { id: 'u-work', name: 'পপুলার ডায়াগনস্টিক সেন্টার', status: 'approved', phone: '', address: '', district: '', upazilaId: null, facilityId: null, homeCollection: true, hours: '', licenseNo: '', statusReason: '' } }) };
  test('অনুরোধ থেকে বিল: পরীক্ষা আর দাম আগে বসানো, ছাড় ১০%, বাসা থেকে নমুনা; আংশিক পরিশোধে বাকি', async ({ page }) => {
    await countPrints(page);
    const seen = await spy(page, approvedLab);
    await open(page, owner('/work/lab/orders/lh1'));
    // নমুনা নেওয়া বাকি: মূল বোতাম "নমুনা নিয়েছি", বিল "আরও"-তে
    await page.getByTestId('row-more').first().click();
    await page.getByTestId('lab-make-bill').click();
    await expect(page).toHaveURL(/\/work\/lab\/orders\/bill\?order=lh1$/);
    await expect(page.locator('.inv-line')).toHaveCount(2);
    await expect(page.getByTestId('lab-total')).toHaveText('৳১,৫৯০');
    await page.getByTestId('pay-given').fill('1000');
    await expect(page.getByTestId('pay-due')).toContainText('বাকি ৳৫৯০');
    await page.getByTestId('lab-save').click();
    await expect(page.getByRole('dialog').getByTestId('receipt')).toContainText('W-000089');
    const [b] = bodyOf(seen, 'lab_bill_create');
    expect(b).toMatchObject({
      labOrderId: 'lh1', disc: 160, fees: [{ label: 'বাসা থেকে নমুনা', amount: 150 }], payments: [{ amount: 1000, method: 'cash' }],
      lines: [{ name: 'Lipid Profile', qty: 1, price: 1200, disc: 0, code: 'Lipid Profile' }, { name: 'ECG', qty: 1, price: 400, disc: 0, code: 'ECG' }],
      customer: { name: 'আব্দুল করিম' },
    });
  });
  test('সরাসরি আসা রোগী: দামের তালিকা থেকে পরীক্ষা খুঁজে বিল', async ({ page }) => {
    const seen = await spy(page, approvedLab);
    await open(page, owner('/work/lab/orders/bill'));
    await page.getByTestId('lab-test-search').fill('CBC');
    await page.getByTestId('lab-test-search').press('Enter');
    await expect(page.locator('.inv-line')).toHaveCount(1);
    await page.getByTestId('lab-save').click();
    await expect.poll(() => bodyOf(seen, 'lab_bill_create').length).toBe(1);
    expect(bodyOf(seen, 'lab_bill_create')[0].labOrderId).toBeUndefined();
  });
});

test.describe('চেম্বারের ডেস্ক', () => {
  test('ডাক্তার: চেক-ইন, সিরিয়ালের ফি (চেম্বারের ফি আগে বসানো), সরাসরি আসা রোগী, আজকের আদায়', async ({ page }) => {
    const errs = watchErrors(page);
    await countPrints(page);
    const seen = await spy(page);
    // আমার কাজ CONTRACT §২: ডেস্ক আর আজকের সিরিয়াল এক "আজকের রোগী" (/work/pro); পুরোনো /work/rx/desk সেখানে যায়
    await open(page, doctor('/work/rx/desk'));
    await page.waitForURL(/\/work\/pro$/);
    const rows = page.getByTestId('desk-row');
    await expect(rows).toHaveCount(5);
    await expect(rows.first()).toContainText('ফি নেওয়া হয়েছে');
    // এখন দেখা রোগী: মূল বোতাম "প্রেসক্রিপশন লিখুন"; এসেছেন আর ফি "আরও"-তে
    await rows.nth(1).getByTestId('row-more').click();
    await page.getByRole('menu').getByTestId('desk-checkin').click();
    await expect(rows.nth(1)).toContainText('উপস্থিত');
    expect(bodyOf(seen, 'chamber_checkin')[0]).toMatchObject({ appointmentId: 'q2' });
    await rows.nth(1).getByTestId('row-more').click();
    await page.getByRole('menu').getByTestId('desk-fee').click();
    await expect(page.getByTestId('fee-amount')).toHaveValue('700');
    await page.getByTestId('fee-save').click();
    await expect(page.getByRole('dialog').getByTestId('receipt')).toContainText('W-000302');
    expect(bodyOf(seen, 'chamber_fee_collect')[0]).toMatchObject({ appointmentId: 'q2', visitType: 'new', amount: 700, method: 'cash' });
    await page.getByRole('dialog').getByRole('button', { name: 'বন্ধ' }).click();
    await page.getByTestId('desk-walkin').click();
    await page.getByRole('dialog').getByRole('textbox', { name: 'নাম' }).fill('রহিম উদ্দিন');
    await page.getByRole('dialog').getByRole('button', { name: 'ফলো-আপ' }).click();
    await page.getByTestId('fee-amount').fill('400');
    await page.getByRole('dialog').getByRole('button', { name: 'বিকাশ' }).click();
    await page.getByTestId('fee-save').click();
    await expect.poll(() => bodyOf(seen, 'chamber_fee_collect').length).toBe(2);
    expect(bodyOf(seen, 'chamber_fee_collect')[1]).toMatchObject({ walkIn: { name: 'রহিম উদ্দিন', chamberKey: 'dghs_3', date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) }, visitType: 'followup', amount: 400, method: 'bkash' });
    expect(errs).toEqual([]);
  });
  test('সহকারী: শুধু নিজের ২ ঘণ্টার মধ্যের রসিদ বাতিল করা যায়; দিনের শেষ আর CSV নেই; doctorUid যায়', async ({ page }) => {
    const seen = await spy(page);
    await open(page, doctor('/work/rx/desk?doctor=doc1'));
    await page.waitForURL(/\/work\/rx\?doctor=doc1$/);
    const list = page.getByTestId('biz-invoices');
    await expect(page.getByTestId('biz-invoice')).toHaveCount(2);
    await expect(page.getByTestId('biz-invoice').nth(0).getByTestId('row-void')).toHaveCount(1);
    await expect(page.getByTestId('biz-invoice').nth(1).getByTestId('row-void')).toHaveCount(0);
    await expect(list.getByRole('tab', { name: 'দিনের শেষ' })).toHaveCount(0);
    await expect(page.getByTestId('csv-export')).toHaveCount(0);
    expect(bodyOf(seen, 'queue_today')[0]).toMatchObject({ doctorUid: 'doc1' });
    expect(bodyOf(seen, 'invoice_list_biz')[0]).toMatchObject({ kind: 'chamber', bizId: 'doc1' });
  });
});

test('অ্যাম্বুলেন্সের মালিক: ট্রিপের রসিদ (শুধু দেখা)', async ({ page }) => {
  await open(page, owner('/work/ambulance'));
  await expect(page.getByTestId('amb-receipts')).toContainText('ট্রিপের রসিদ');
  await expect(page.getByTestId('amb-receipts').getByTestId('biz-invoice')).toHaveCount(1);
});

test('ডেস্কটপ (নকল OfflineStore): বিক্রি সার্ভারে নয় — record(op), রসিদ অফলাইন, ছাপা অ্যাপের প্রিন্টারে', async ({ page }) => {
  await page.addInitScript(() => {
    const w = window as any;
    w.__ops = [];
    w.__printed = [];
    const items = [{ id: 'it1', name: 'Napa', code: '8941100500017', price: 1.2, stock: 500, unit: 'piece' }];
    w.__lfOffline = {
      isDesktop: () => true,
      ready: async () => ({ deviceId: 'dev1', series: 'D1', asOf: Date.now(), pending: 0 }),
      items: async () => items,
      labPrices: async () => ({ items: [], fees: [] }),
      chamberDay: async (date: string) => ({ date, chambers: [], appointments: [], fees: {} }),
      invoices: async () => w.__ops.length ? [w.__lastInv] : [],
      role: () => 'owner',
      requireOwner: async () => true,
      record: async (op: any) => {
        w.__ops.push(op);
        const lines = op.data.lines.map((l: any) => ({ name: l.name, qty: l.qty, price: l.price, disc: l.disc || 0, amount: l.qty * l.price - (l.disc || 0) }));
        const total = lines.reduce((a: number, l: any) => a + l.amount, 0);
        return {
          localId: 'L1', no: 'D1-000001',
          invoice: {
            id: '', localId: 'L1', no: 'D1-000001', kind: 'pharmacy', bizId: 'u-work', bizName: 'নিউ লাইফ ফার্মেসি', customer: op.data.customer || {}, lines, subtotal: total, disc: 0, fees: [],
            total, paid: total, due: 0, payments: [{ at: Date.now(), amount: total, method: 'cash' }], status: 'issued', issuedAt: Date.now(), issuedBy: 'u-work', deviceId: 'dev1',
            offline: true, sync: 'pending', verifyCode: 'OFF0000001', verifyUrl: 'https://lifolo.com/v/OFF0000001',
          },
        };
      },
      pending: () => w.__ops.length,
      syncNow: async () => {},
      onChange: () => () => {},
      print: async (inv: any, opts: any) => {
        w.__printed.push({ no: inv.no, ...opts });
      },
    };
  });
  const seen = await spy(page);
  await open(page, owner('/work/pharmacy/shop/sales'));
  await expect(page.getByTestId('offline-bar')).toContainText('এই কম্পিউটার: D1');
  const search = page.getByRole('combobox', { name: 'খুঁজুন বা বারকোড স্ক্যান করুন' });
  // বারকোড স্ক্যানার: কোড + Enter
  await search.fill('8941100500017');
  await search.press('Enter');
  await page.locator('.bk-line input').first().fill('5');
  // USB স্ক্যানার (useBarcode): দ্রুত অক্ষর + Enter — একই জিনিস আরেকটা, খোঁজার ঘর খালি
  await search.focus();
  await page.keyboard.type('8941100500017');
  await page.keyboard.press('Enter');
  await expect(page.locator('.bk-line input').first()).toHaveValue('6');
  await expect(search).toHaveValue('');
  await page.getByTestId('pos-save').click();
  const dlg = page.getByRole('dialog');
  await expect(dlg.getByTestId('receipt')).toContainText('D1-000001');
  await expect(dlg.getByTestId('receipt-offline')).toContainText('ইন্টারনেট এলে যাচাই হবে');
  await expect(page.getByTestId('offline-bar')).toContainText('১টা মেলানোর অপেক্ষায়');
  const ops = await page.evaluate(() => (window as any).__ops);
  expect(ops).toEqual([{ type: 'sale', data: { lines: [{ itemId: 'it1', name: 'Napa', qty: 6, price: 1.2, disc: 0, code: '8941100500017' }], disc: 0, payments: [{ amount: 7.2, method: 'cash' }] } }]);
  await expect.poll(() => page.evaluate(() => (window as any).__printed)).toEqual([{ no: 'D1-000001', paper: 'A4', englishDigits: false }]);
  expect(bodyOf(seen, 'books_sale_add')).toEqual([]);
  expect(bodyOf(seen, 'books_items')).toEqual([]);
});

test('ফিচার সুইচ বন্ধ: আগের মতো — রসিদ নেই, ডেস্ক নেই, "আমার রসিদ" নেই, /invoices "শিগগির আসছে"; যাচাই পাতা খোলা', async ({ page }) => {
  const off = { features: { inv_pharmacy: false, inv_lab: false, inv_chamber: false, inv_ambulance: false, my_expenses: false } };
  const seen = await spy(page);
  await open(page, owner('/work/pharmacy/shop/sales', off));
  await expect(page.getByRole('tab', { name: 'রসিদ' })).toHaveCount(0);
  await page.getByRole('combobox', { name: 'পণ্য খুঁজুন' }).fill('Napa');
  await page.getByRole('combobox', { name: 'পণ্য খুঁজুন' }).press('Enter');
  await expect(page.getByTestId('pay-given')).toHaveCount(0);
  await expect(page.getByTestId('pos-cust-name')).toHaveCount(0);
  await page.getByTestId('pos-save').click();
  await expect.poll(() => bodyOf(seen, 'books_sale_add').length).toBe(1);
  expect(bodyOf(seen, 'books_sale_add')[0].payments).toBeUndefined();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.goto('/work/rx', { waitUntil: 'networkidle' });
  await expect(page.getByRole('tab', { name: 'ডেস্ক' })).toHaveCount(0);
  await page.goto('/work/ambulance', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('amb-receipts')).toHaveCount(0);
  await page.goto('/invoices', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('feature-off')).toContainText('এই সুবিধা শিগগির আসছে');
  await page.goto('/profile', { waitUntil: 'networkidle' });
  await expect(page.getByRole('button', { name: 'আমার প্রেসক্রিপশন' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'আমার রসিদ' })).toHaveCount(0);
  await page.goto('/v/PH4K2M9QXA', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('verify-status')).toHaveText('বৈধ');
});

test('বড় পর্দায় (১৪৪০) সারির আইকন ছোট — রসিদ, খরচ, যাচাই, ডেস্ক @desktop', async ({ page }, info) => {
  test.skip(info.project.name !== 'desktop', 'শুধু ১৪৪০ প্রস্থে');
  const small = async (sel: string, min: number) => {
    const boxes = await page.locator(sel).evaluateAll((els) => els.map((e) => e.getBoundingClientRect()).map((r) => [r.width, r.height]));
    expect(boxes.length).toBeGreaterThanOrEqual(min);
    for (const [w, h] of boxes) {
      expect(w).toBeLessThanOrEqual(32);
      expect(h).toBeLessThanOrEqual(32);
    }
  };
  await open(page, patient('/invoices/inv2'));
  await small('[data-testid=my-invoice] [data-testid=row-icon]', 5);
  // সারি ছোট থাকে (কার্ড ফুলে ওঠে না)
  for (const h of await page.getByTestId('my-invoice').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height))) expect(h).toBeLessThan(110);
  await page.goto('/invoices/expenses', { waitUntil: 'networkidle' });
  await small('[data-testid=my-expenses] [data-testid=row-icon]', 4);
  await expect(page.locator('.ld-detail')).toBeHidden();
  await page.goto('/v/PH4K2M9QXA', { waitUntil: 'networkidle' });
  await small('[data-testid=verify-result] svg:not(.health-qr)', 2);
  await open(page, doctor('/work/pro'));
  await small('[data-testid=today-queue] .btn svg', 1);
  await open(page, owner('/work/ambulance'));
  await small('[data-testid=amb-receipts] .rxp-panel-ic svg', 1);
});

test.describe('অনলাইন চেম্বারের ফি → রসিদ (appt_pay_confirm)', () => {
  const nowHM = () => {
    const d = new Date(Date.now() + 6 * 3600 * 1000);
    return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
  };
  const todayMs = () => {
    const d = new Date(Date.now() + 6 * 3600 * 1000);
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  };
  const onlineAppt = (x: Record<string, unknown>) => ({
    id: 'onp1', type: 'doctor', otherName: 'মো. জসিম উদ্দিন', chamber: '', chamberBn: '', start: nowHM(), serial: 4, date: todayMs(), note: '', status: 'confirmed',
    providerNote: '', otherPhone: '', otherKey: 'kx', createdAt: Date.now(), online: true, fee: '৫০০ টাকা', noFee: false, ...x,
  });
  /** my_appointments-এ একটা অনলাইন অ্যাপয়েন্টমেন্ট যোগ (নকল সার্ভারের উত্তরের ওপর) */
  async function withOnline(page: Page, appt: () => Record<string, unknown>, reply: Record<string, (b: any) => unknown> = {}) {
    const seen: Seen = [];
    await page.route('**/api/community', async (route) => {
      const body = route.request().postDataJSON() || {};
      seen.push({ action: String(body.action), body });
      const f = reply[String(body.action)];
      if (f) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(f(body)) });
      if (body.action !== 'my_appointments') return route.continue();
      const res = await route.fetch();
      const j = await res.json();
      j.items.push(appt());
      return route.fulfill({ response: res, json: j });
    });
    return seen;
  }
  test('ডাক্তার: "ফি পেয়েছি" → সার্ভারের রসিদ সাথে সাথে; পরিশোধিত হলে "রসিদ দেখুন"', async ({ page }) => {
    let pay: Record<string, unknown> = { payState: 'claimed', payRef: 'TRX42AB' };
    await withOnline(page, () => onlineAppt({ role: 'provider', ...pay }), {
      appt_pay_confirm: () => {
        pay = { payState: 'paid', payRef: 'TRX42AB', invoiceId: 'b5' };
        return { ok: true, invoice: { ...SALE_INV({}).invoice, id: 'b5', no: 'W-000301', kind: 'chamber', bizName: 'ডা. আরিফুল হক', lines: [{ name: 'অনলাইন ভিজিট ফি', qty: 1, price: 500, disc: 0, amount: 500 }], subtotal: 500, total: 500, paid: 500 } };
      },
    });
    await open(page, def('appointment', 'pro-bookings'));
    const pro = page.getByTestId('online-pay-pro');
    await pro.getByTestId('pay-got').click();
    for (const k of ['trx', 'amount', 'last4']) await page.getByRole('dialog').getByTestId(`check-${k}`).click();
    await page.getByRole('dialog').getByRole('button', { name: 'ফি পেয়েছি' }).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('receipt')).toContainText('অনলাইন ভিজিট ফি');
    await expect(dlg.getByTestId('receipt')).toContainText('W-000301');
    await dlg.getByRole('button', { name: 'বন্ধ' }).click();
    await expect(pro.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
    await pro.getByTestId('pay-receipt').click();
    await expect(page.getByRole('dialog').getByTestId('receipt')).toContainText('W-000301');
  });
  test('রোগী: ফি পরিশোধিত হলে "রসিদ দেখুন" → রসিদের পাতা', async ({ page }) => {
    await withOnline(page, () => onlineAppt({ id: 'onp2', role: 'patient', payState: 'paid', payRef: 'TRX42AB', invoiceId: 'inv3', bkashNumber: '01711000222' }));
    await open(page, patient('/my-appointments/onp2'));
    const box = page.getByTestId('online-pay');
    await expect(box.getByTestId('pay-state')).toHaveText('ফি পরিশোধিত');
    await box.getByTestId('invoice-link').click();
    await expect(page).toHaveURL(/\/invoices\/inv3$/);
  });
});
