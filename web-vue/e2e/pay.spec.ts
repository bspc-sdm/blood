// পেমেন্ট (backend/docs/PAYMENTS.md, docs/test-plan/PAYMENTS-SECURITY-REVIEW.md) — নকল API-র সাথে:
// রোগীর "পেমেন্ট" ট্যাব (দাবি, SSE-তে নিজে নতুন, কেয়ারগিভার), প্রবেশপথগুলো (রসিদের বাকি, ট্রিপ শেষের ভাড়া, অনলাইন অর্ডার, পরীক্ষা,
// চেম্বারের অগ্রিম, অনলাইন চেম্বারের ফি নতুন পথে), ব্যবসার "পেমেন্ট" (আজকের হিসাব, তিন টিকে নিশ্চিত, পাইনি, সব, ফেরত, CSV, নম্বর —
// আবার লগইন, "আমি করিনি", অগ্রিম), অ্যাডমিনের পেমেন্ট ট্যাব আর সতর্কতা, bKash গেটওয়ের ফল, রক্তের অংশে কিছুই নয়, আর সুইচ বন্ধ থাকলে আগের মতো।
// নকল উত্তর এই ফাইলেই (page.route) — test/fixtures বদলানো হয়নি।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const H = 3600_000;
const ALL = {
  pay_manual: true, pay_chamber_advance: true, pay_delivery: true, pay_lab: true, pay_ambulance: true, pay_due: true, pay_bkash_pgw: true,
  inv_pharmacy: true, inv_lab: true, inv_chamber: true, inv_ambulance: true, my_expenses: true, online_chamber: true, appt_video: true, chat: true,
};
type Seen = { action: string; body: any }[];
type Reply = (body: any, route: Route) => unknown | Promise<unknown>;
/** features → keys; handlers-এ থাকলে সেই উত্তর ({__status, body} = ভুল), নইলে নকল সার্ভারে */
async function mock(page: Page, handlers: Record<string, Reply> = {}, keys: Record<string, boolean> = ALL): Promise<Seen> {
  const seen: Seen = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: String(body.action), body });
    if (body.action === 'features') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ keys, v: 1 }) });
    const f = handlers[String(body.action)];
    if (!f) return route.continue();
    const out: any = await f(body, route);
    if (out && out.__status) return route.fulfill({ status: out.__status, contentType: 'application/json', body: JSON.stringify(out.body) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(out ?? { ok: true }) });
  });
  return seen;
}
const bodies = (seen: Seen, action: string) => seen.filter((x) => x.action === action).map((x) => x.body);
const base = async (route: Route) => (await route.fetch()).json();
const patient = (path: string, extra: Record<string, unknown> = {}) => {
  const d = def('health', 'rx-list');
  return { ...d, path, test: { ...d.test, ...extra } };
};
const owner = (path: string, extra: Record<string, unknown> = {}) => {
  const d = def('shopbooks', 'bk-items');
  return { ...d, path, test: { ...d.test, ...extra } };
};
const notFound = () => ({ __status: 404, body: { error: 'Not found', code: 'not_found' } });
// পরীক্ষা শেষে ঝুলে থাকা route.fetch (SSE/রিফ্রেশের পর আবার আনা) ভুল হিসেবে না ধরা
test.afterEach(async ({ page }) => page.unrouteAll({ behavior: 'ignoreErrors' }));

const REQ = (o: Record<string, unknown> = {}) => ({
  id: 'prq_ph1', kind: 'pharmacy', bizId: 'u-work', bizName: 'নিউ লাইফ ফার্মেসি', payerUid: 'u1', refType: 'rx_order', refId: 'o9', purpose: 'delivery', amount: 450,
  methods: [{ method: 'bkash', number: '01711223344', type: 'personal' }, { method: 'nagad', number: '01811223344', type: 'merchant' }],
  status: 'open', expiresAt: Date.now() + 20 * H, createdAt: Date.now() - H, ...o,
});

test.describe('রোগী: "আমার রসিদ ও খরচ"-এর পেমেন্ট', () => {
  test('তালিকা → শিট: সতর্কতা, নম্বর কপি, ফেরতের নিয়ম, TrxID + শেষ ৪ → pay_claim; SSE pay_confirmed এলে নিজে "পরিশোধিত"', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const errs = watchErrors(page);
    let req: any = REQ();
    let live = false;
    // SSE: শুরুতে বন্ধ (ব্যর্থ → ছোট বিরতিতে আবার), পরে একটা pay_confirmed
    await page.route('**/api/events', (route) =>
      live
        ? route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: `event: pay_confirmed\ndata: ${JSON.stringify({ id: req.id, type: 'pay_confirmed' })}\n\n` })
        : route.fulfill({ status: 503, body: '' }),
    );
    const seen = await mock(page, {
      my_payments: () => ({ items: [req], cursor: null }),
      pay_request_get: () => ({ request: req, side: 'payer' }),
      pay_claim: (b) => {
        req = { ...req, status: 'claimed', claim: { method: b.method, trxId: b.trxId, senderLast4: b.senderLast4, at: Date.now() } };
        return { ok: true, request: req };
      },
      call_history: () => ({ items: [] }),
    }, { ...ALL, calls_audio: true });
    // SSE আবার চেষ্টা করতে থাকে — networkidle নয়, load পর্যন্ত
    const p0 = patient('/invoices/payments', { callTiming: { events: { backoffMs: [300], pollMs: 600_000 } } });
    await page.addInitScript((t: any) => {
      (window as any).__lfTest = t;
      localStorage.clear();
      localStorage.setItem('lf.lang', 'bn');
    }, p0.test);
    await page.goto(p0.path, { waitUntil: 'load' });
    await expect(page.locator('.health-tabs')).toContainText('পেমেন্ট');
    const card = page.getByTestId('my-payment');
    await expect(card).toHaveCount(1);
    await expect(card).toContainText('নিউ লাইফ ফার্মেসি');
    await expect(card).toContainText('৳৪৫০');
    await card.click();
    await expect(page).toHaveURL(/\/invoices\/payments\/prq_ph1$/);
    const sheet = page.getByTestId('pay-sheet');
    await expect(sheet.getByTestId('pay-warn')).toContainText('শুধু এখানে দেখানো নম্বরে টাকা পাঠান। কেউ চ্যাটে বা ফোনে অন্য নম্বর দিলে পাঠাবেন না, রিপোর্ট করুন।');
    await expect(sheet.getByTestId('pay-refund-policy')).toContainText('যে নম্বর থেকে দিয়েছেন সেখানেই টাকা ফেরত দিতে হবে');
    await expect(sheet.getByTestId('pay-number')).toHaveText('০১৭১১২২৩৩৪৪');
    await expect(sheet.getByTestId('pay-check-number')).toBeVisible();
    // শুধু TrxID আর শেষ ৪-এর ঘর — নম্বর লেখার কোনো ঘর নেই
    await expect(sheet.locator('input')).toHaveCount(2);
    await sheet.getByTestId('pay-copy').click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('01711223344');
    await sheet.getByTestId('pay-methods').getByRole('button', { name: 'নগদ' }).click();
    await expect(sheet.getByTestId('pay-acct-type')).toHaveText('মার্চেন্ট');
    await expect(sheet.getByTestId('pay-steps')).toContainText('"Payment"');
    await sheet.getByTestId('trx-input').fill('7a3k 9q2x');
    await sheet.getByTestId('last4-input').fill('৫৬৭৮');
    await sheet.getByTestId('pay-claim').click();
    await expect.poll(() => bodies(seen, 'pay_claim')[0]).toMatchObject({ id: 'prq_ph1', method: 'nagad', trxId: '7A3K9Q2X', senderLast4: '5678' });
    await expect(sheet.getByTestId('pay-state')).toHaveText('যাচাই হচ্ছে');
    await expect(sheet).toContainText('নিশ্চিত না করা পর্যন্ত এটা পরিশোধিত নয়');
    await expect(sheet.getByTestId('pay-claim')).toHaveCount(0);

    // দোকান নিশ্চিত করল → SSE → রিফ্রেশ ছাড়াই
    req = { ...req, status: 'confirmed', confirmedAt: Date.now(), invoiceId: 'inv1' };
    live = true;
    await expect(sheet.getByTestId('pay-state')).toHaveText('পরিশোধিত', { timeout: 8000 });
    await expect(card.locator('.tag')).toHaveText('পরিশোধিত');
    await expect(sheet.getByTestId('pay-timeline')).toContainText('নিউ লাইফ ফার্মেসি নিশ্চিত করেছেন');
    await expect(sheet.getByTestId('invoice-link')).toHaveAttribute('href', '/invoices/inv1');
    await noSideScroll(page);
    expect(errs.filter((e) => !/503|events/.test(e))).toEqual([]);
  });

  test('দোকান "পাইনি": আলাদা বার্তা, আপত্তি (pay_report); 409 — আবার পাঠাবেন না; কেয়ারগিভার মোডে asUid', async ({ page }) => {
    const req = REQ({ status: 'rejected', rejectReason: 'wrong_amount', rejectNote: '৩০০ এসেছে', rejectedAt: Date.now() - H, claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: Date.now() - 2 * H } });
    const seen = await mock(page, {
      my_payments: () => ({ items: [req], cursor: null }),
      pay_request_get: () => ({ request: req }),
      pay_report: () => ({ ok: true, id: 'prp1' }),
      pay_claim: () => ({ __status: 409, body: { error: 'used', code: 'pay_ref_used' } }),
    });
    await open(page, patient('/invoices/payments/prq_ph1'));
    const rej = page.getByTestId('pay-rejected');
    await expect(rej).toContainText('দোকান বলছে টাকা আসেনি');
    await expect(rej).toContainText('টাকার পরিমাণ মেলেনি — ৩০০ এসেছে');
    await expect(rej).toContainText('9GH7K2LM1P');
    await rej.getByTestId('pay-dispute').click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('report-reasons').locator('[aria-pressed=true]')).toContainText('টাকা দিয়েছি, কিন্তু নিশ্চিত করছেন না');
    await dlg.getByTestId('report-send').click();
    await expect.poll(() => bodies(seen, 'pay_report')[0]).toMatchObject({ id: 'prq_ph1', reason: 'not_confirmed' });
    const sheet = page.getByTestId('pay-sheet');
    await sheet.getByTestId('trx-input').fill('9GH7K2LM1P');
    await sheet.getByTestId('last4-input').fill('5678');
    await sheet.getByTestId('pay-claim').click();
    await expect(sheet).toContainText('আবার টাকা পাঠাবেন না');
    await expect(sheet.getByTestId('pay-ref-report')).toBeVisible();

    await page.goto('/invoices/payments', { waitUntil: 'networkidle' });
    await page.getByTestId('care-switch').getByRole('button', { name: 'আমেনা বেগম' }).click();
    await expect.poll(() => bodies(seen, 'my_payments').some((b) => b.asUid === 'p1')).toBe(true);
  });

  test('ফেরত পাঠিয়েছে বলছে → "পেয়েছি" (pay_refund_ack); ডেমোতে "আসল টাকা পাঠাবেন না"', async ({ page }) => {
    let req: any = REQ({ demo: true, status: 'refund_claimed', confirmedAt: Date.now() - 3 * H, claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: Date.now() - 4 * H }, refund: { trxId: 'RF12345678', amount: 450, at: Date.now() - H } });
    const seen = await mock(page, {
      my_payments: () => ({ items: [req] }),
      pay_request_get: () => ({ request: req }),
      pay_refund_ack: () => {
        req = { ...req, status: 'refunded', refund: { ...req.refund, ackAt: Date.now() } };
        return { ok: true, request: req };
      },
    });
    await open(page, patient('/invoices/payments/prq_ph1'));
    const sheet = page.getByTestId('pay-sheet');
    await expect(sheet.getByTestId('pay-demo')).toHaveText('ডেমো — আসল টাকা পাঠাবেন না');
    await expect(sheet.getByTestId('pay-state')).toHaveText('ফেরত পাঠিয়েছে বলছে');
    await expect(sheet.getByTestId('pay-refund')).toContainText('ফেরতের TrxID: RF12345678');
    await sheet.getByTestId('refund-ack').click();
    await expect.poll(() => bodies(seen, 'pay_refund_ack').length).toBe(1);
    await expect(sheet.getByTestId('refund-acked')).toBeVisible();
    await expect(sheet.getByTestId('pay-state')).toHaveText('ফেরত দেওয়া হয়েছে');
  });
});

test('ফেরত পাওনা (refundDue): রোগী "ফেরত পাওনা — …" দেখেন; সার্ভারের ফেরতের নিয়ম (policy) দেখায়', async ({ page }) => {
  const req = REQ({ status: 'confirmed', confirmedAt: Date.now() - 30 * H, refundDue: { at: Date.now() - H, why: 'order_cancelled' } });
  await mock(page, { my_payments: () => ({ items: [req] }), pay_request_get: () => ({ request: req, side: 'payer', policy: 'দোকান বাতিল করলে পুরো টাকা ফেরত পাবেন।' }) });
  await open(page, patient('/invoices/payments/prq_ph1'));
  await expect(page.getByTestId('pay-state')).toHaveText('ফেরত পাওনা');
  await expect(page.getByTestId('pay-refund-due')).toContainText('ফেরত পাওনা — নিউ লাইফ ফার্মেসি');
  await expect(page.getByTestId('my-payment').locator('.tag')).toHaveText('ফেরত পাওনা');
  const open2 = REQ({ id: 'prq_p2' });
  await page.route('**/api/community', async (route) => {
    const b = route.request().postDataJSON() || {};
    if (b.action === 'pay_request_get' && b.id === 'prq_p2') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ request: open2, policy: ['দোকান বাতিল করলে পুরো টাকা ফেরত পাবেন।'] }) });
    return route.fallback();
  });
  await page.goto('/invoices/payments/prq_p2', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('pay-refund-policy')).toContainText('দোকান বাতিল করলে পুরো টাকা ফেরত পাবেন।');
});

test.describe('প্রবেশপথ', () => {
  test('রসিদের বাকি: "বাকি পরিশোধ করুন" → pay_due_start → শিট', async ({ page }) => {
    const seen = await mock(page, {
      pay_request_get: (b) => (b.id ? { request: REQ({ id: 'prq_due', kind: 'lab', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', refType: 'invoice', refId: 'inv2', purpose: 'invoice_due', amount: 195 }) } : notFound()),
      pay_due_start: () => ({ ok: true, created: true, request: REQ({ id: 'prq_due', kind: 'lab', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', refType: 'invoice', refId: 'inv2', purpose: 'invoice_due', amount: 195 }) }),
    });
    await open(page, patient('/invoices/inv2'));
    await page.getByTestId('pay-open').click();
    await expect.poll(() => bodies(seen, 'pay_due_start')[0]).toMatchObject({ invoiceId: 'inv2' });
    const dlg = page.getByRole('dialog');
    await expect(dlg).toContainText('রসিদের বাকি');
    await expect(dlg.getByTestId('pay-amount')).toHaveText('৳১৯৫');
    await expect(dlg.getByTestId('pay-number')).toBeVisible();
  });

  test('ট্রিপ শেষে ভাড়া (ঐচ্ছিক, নগদেও চলে); শেষ না হলে কিছুই নয়', async ({ page }) => {
    let status = 'accepted';
    const amb = { name: 'জননী অ্যাম্বুলেন্স', phone: '01711223344', id: 'amb1', km: 2 };
    await mock(page, {
      ambreq_status: () => ({ request: { id: 'ar7', status, createdAt: Date.now() - H, widenAt: 0, expiresAt: Date.now() + H, wave: 1, notified: 3, pickup: 'চরপাড়া', note: '', type: null, district: 'ময়মনসিংহ', upazilaId: null, ambulance: amb, cancelledBy: null, cancelReason: null, fallback: [], called: [], canRate: false, agreedFare: 1500 } }),
      pay_request_get: (b) => (b.refType === 'ambreq' ? { request: REQ({ id: 'prq_amb', kind: 'ambulance', bizId: 'amb1', bizName: 'জননী অ্যাম্বুলেন্স', refType: 'ambreq', refId: 'ar7', purpose: 'ambulance_fare', amount: 1500 }) } : notFound()),
    });
    await open(page, patient('/services/ambulance/call/ar7'));
    // চালকের নম্বর আর কলের আইকন (ফোন কল · অ্যাপ কল · ভিডিও কল)
    await expect(page.getByTestId('contact-number')).toHaveText('০১৭১১২২৩৩৪৪');
    await expect(page.getByTestId('pay-entry')).toHaveCount(0);
    status = 'done';
    await page.reload({ waitUntil: 'networkidle' });
    const entry = page.getByTestId('pay-entry');
    await expect(entry).toContainText('মোবাইল ব্যাংকিংয়ে ভাড়া দিন');
    await expect(entry).toContainText('ঐচ্ছিক — চালককে নগদে দিলেও চলবে।');
    await entry.getByTestId('pay-open').click();
    await expect(page.getByRole('dialog').getByTestId('pay-refund-policy')).toContainText('যে ভাড়া ঠিক হয়েছে শুধু সেটাই দিন');
  });

  test('প্রেসক্রিপশনের অনলাইন অর্ডার আর পরীক্ষা: দোকান/প্যাথলজি চাইলে "এখনই দিন"', async ({ page }) => {
    await mock(page, {
      rx_activity: async (_b, route) => {
        const j = await base(route);
        return {
          ...j,
          orders: [{ id: 'o9', pharmacyName: 'নিউ লাইফ ফার্মেসি', status: 'accepted', reason: '', createdAt: Date.now() - H, mode: 'online', price: '৳৪৫০', eta: 'আজ সন্ধ্যা', deliveryFee: '', missed: [], payRequestId: 'prq_ph1', payStatus: 'open' }],
          labOrders: (j.labOrders ?? []).map((o: any, i: number) => (i === 0 ? { ...o, status: 'received' } : o)),
        };
      },
      pay_request_get: (b) => (b.id === 'prq_ph1' ? { request: REQ() } : b.refType === 'lab_order' ? { request: REQ({ id: 'prq_lab', kind: 'lab', bizName: 'পপুলার ডায়াগনস্টিক সেন্টার', refType: 'lab_order', purpose: 'lab', amount: 550 }) } : notFound()),
    });
    await open(page, patient('/services/pharmacy/prescriptions/rx1'));
    const entries = page.getByTestId('pay-entry');
    await expect(entries.filter({ hasText: 'এখনই দিন (বিকাশ/নগদ)' })).toHaveCount(1);
    await entries.filter({ hasText: 'এখনই দিন' }).getByTestId('pay-open').click();
    await expect(page.getByRole('dialog').getByTestId('pay-biz')).toContainText('নিউ লাইফ ফার্মেসি');
    await page.keyboard.press('Escape');
    const lab = entries.filter({ hasText: 'প্যাথলজির টাকা দিন' });
    if (await lab.count()) await expect(lab.first()).toContainText('চাইলে নগদেও দিতে পারেন।');
  });

  test('চেম্বারের অগ্রিম: বুকিংয়ের আগে নিয়ম, পরে সাথে সাথে শিট; তালিকায় "অগ্রিম বাকি"', async ({ page }) => {
    const seen = await mock(page, {
      providers: async (_b, route) => {
        const j = await base(route);
        return { ...j, items: j.items.map((p: any) => (p.uid === 'p_mahmuda' ? { ...p, chambers: p.chambers.map((c: any) => ({ ...c, advance: { amount: 200, refundableUntilHoursBefore: 6 } })) } : p)) };
      },
      provider_get: async (_b, route) => {
        const j = await base(route);
        const p = j.provider ?? j;
        const withAdv = { ...p, chambers: (p.chambers ?? []).map((c: any) => ({ ...c, advance: { amount: 200, refundableUntilHoursBefore: 6 } })) };
        return j.provider ? { ...j, provider: withAdv } : withAdv;
      },
      book: () => ({ ok: true, id: 'a77', payRequestId: 'prq_adv' }),
      pay_request_get: (b) => (b.refType === 'appointment' || b.id === 'prq_adv'
        ? { request: REQ({ id: 'prq_adv', kind: 'chamber', bizId: 'p_mahmuda', bizName: 'ডা. মাহমুদা আক্তার', refType: 'appointment', refId: 'a77', purpose: 'booking_advance', amount: 200, refundableUntil: Date.now() + 30 * H }) }
        : notFound()),
      my_appointments: async (_b, route) => {
        const j = await base(route);
        return { ...j, items: j.items.map((a: any, i: number) => (i === 0 && a.role === 'patient' && !a.online ? { ...a, payRequestId: 'prq_adv', payStatus: 'open' } : a)) };
      },
    });
    await open(page, def('appointment', 'book'));
    await page.getByRole('button', { name: 'এই চেম্বারে অ্যাপয়েন্টমেন্ট' }).first().click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('advance-line')).toContainText('৳২০০ অগ্রিম লাগে');
    await expect(dlg.getByTestId('advance-line')).toContainText('৬ ঘণ্টা আগে');
    await dlg.getByLabel('কী সমস্যা').fill('জ্বর');
    await dlg.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await expect.poll(() => bodies(seen, 'pay_request_get').find((b) => b.id === 'prq_adv')).toBeTruthy();
    const pay = page.getByRole('dialog');
    await expect(pay).toContainText('অনুরোধ গেছে। সিরিয়াল রাখতে অগ্রিম দিন।');
    await expect(pay.getByTestId('pay-refund-policy')).toContainText('ডাক্তার বাতিল করলে পুরো টাকা ফেরত');
    await page.goto('/my-appointments', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('advance-due').first()).toHaveText('অগ্রিম বাকি');
    // অ্যাপয়েন্টমেন্টের ভেতরে (তালিকায় পেমেন্ট না থাকলেও খুঁজে): "অগ্রিম বাকি" আর "অগ্রিম দিন"
    await page.goto('/my-appointments/a1', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('pay-entry-state')).toHaveText('অগ্রিম বাকি');
    await expect(page.getByTestId('pay-open')).toHaveText('অগ্রিম দিন');
  });

  test('অনলাইন চেম্বারের ফি নতুন পথে: অ্যাপয়েন্টমেন্টে পেমেন্ট থাকলে একই শিট (TrxID + শেষ ৪)', async ({ page }) => {
    const seen = await mock(page, {
      appointment_queue: () => ({ live: false }),
      my_appointments: async (_b, route) => {
        const j = await base(route);
        j.items.unshift({ id: 'on5', role: 'patient', type: 'doctor', otherName: 'ডা. রুবিনা ইয়াসমিন', chamber: '', start: '20:00', date: Date.now(), note: 'জ্বর', status: 'confirmed', createdAt: Date.now(), online: true, fee: '৫০০ টাকা', payState: 'unpaid', bkashNumber: '01712345678', payRequestId: 'prq_on5', payStatus: 'open' });
        return j;
      },
      pay_request_get: () => ({ request: REQ({ id: 'prq_on5', kind: 'chamber', bizName: 'ডা. রুবিনা ইয়াসমিন', refType: 'appointment', refId: 'on5', purpose: 'chamber_fee', amount: 500, methods: [{ method: 'bkash', number: '01712345678', type: 'personal' }] }) }),
      pay_claim: (b) => ({ ok: true, request: REQ({ id: 'prq_on5', status: 'claimed', claim: { ...b, at: Date.now() } }) }),
    });
    await open(page, def('appointment', 'my-detail'), '/my-appointments/on5');
    const box = page.getByTestId('online-pay');
    await expect(box.getByTestId('pay-state')).toHaveText('পাঠানো হয়নি');
    await expect(box.getByTestId('pay-number')).toHaveText('০১৭১২৩৪৫৬৭৮');
    await box.getByTestId('trx-input').fill('9GH7K2LM1P');
    await box.getByTestId('last4-input').fill('1234');
    await box.getByTestId('pay-claim').click();
    await expect.poll(() => bodies(seen, 'pay_claim')[0]).toMatchObject({ id: 'prq_on5', method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '1234' });
    expect(bodies(seen, 'appt_pay_claim')).toHaveLength(0);
  });
});

test.describe('ব্যবসা: "পেমেন্ট"', () => {
  const claimed = REQ({ id: 'prq_c1', payerName: 'রাকিব হাসান', status: 'claimed', claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: Date.now() - 26 * H } });
  const confirmed = REQ({ id: 'prq_ok', payerName: 'সুমি', status: 'confirmed', confirmedAt: Date.now() - H, invoiceId: 'b1', claim: { method: 'nagad', trxId: 'AB12CD34', senderLast4: '4321', at: Date.now() - 2 * H } });

  for (const size of ['mobile', 'desktop @desktop']) {
    test(`মেলানো বাকি: আজকের হিসাব, তিন টিক ছাড়া "পেয়েছি" বন্ধ → pay_confirm {checks} → রসিদ (${size})`, async ({ page }) => {
      const errs = watchErrors(page);
      let st = 'claimed';
      const seen = await mock(page, {
        pay_summary: () => ({ day: '2026-10-23', collected: 1200, confirmedCount: 3, openCount: 2, pendingClaims: 1 }),
        pay_list_biz: (b) => ({ items: b.status === 'claimed' ? (st === 'claimed' ? [claimed] : []) : [claimed, confirmed] }),
        pay_confirm: async (_b, route) => {
          st = 'confirmed';
          const inv = (await (await route.fetch({ postData: JSON.stringify({ action: 'invoice_list_biz', kind: 'pharmacy', bizId: 'u-work', period: 'today' }) })).json()).items?.[0];
          return { ok: true, request: { ...claimed, status: 'confirmed' }, invoice: inv };
        },
        pay_reject: () => ({ ok: true }),
      });
      await open(page, owner('/work/payments?biz=pharmacy%3Au-work'));
      await expect(page.getByTestId('sum-collected')).toHaveText('৳১,২০০');
      await expect(page.getByTestId('sum-pending')).toHaveText('১');
      const row = page.getByTestId('pay-inbox').getByTestId('biz-pay');
      await expect(row).toContainText('রাকিব হাসান');
      await expect(row.getByTestId('biz-pay-state')).toHaveText('রোগী বলছেন পাঠিয়েছেন — মিলিয়ে নিন');
      await expect(row.getByTestId('biz-pay-claim')).toContainText('9GH7K2LM1P');
      await expect(row.getByTestId('biz-pay-late')).toBeVisible();
      await row.getByTestId('pay-confirm').click();
      const dlg = page.getByRole('dialog');
      await expect(dlg.getByTestId('confirm-claim')).toContainText('৳৪৫০');
      await expect(dlg.getByTestId('confirm-go')).toBeDisabled();
      await dlg.getByTestId('check-trx').click();
      await dlg.getByTestId('check-amount').click();
      await expect(dlg.getByTestId('confirm-go')).toBeDisabled();
      await dlg.getByTestId('check-last4').click();
      await dlg.getByTestId('confirm-go').click();
      await expect.poll(() => bodies(seen, 'pay_confirm')[0]).toMatchObject({ id: 'prq_c1', checks: { trxId: true, amount: true, last4: true } });
      await expect(page.getByRole('dialog').getByTestId('receipt')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('pay-inbox')).toContainText('মেলানোর কিছু নেই');
      await noSideScroll(page);
      expect(errs).toEqual([]);
    });
  }

  test('সব পেমেন্ট: ছাঁকনি, ফেরত (যে নম্বর থেকে এসেছিল সেখানেই), CSV', async ({ page }) => {
    const seen = await mock(page, {
      pay_list_biz: () => ({ items: [claimed, confirmed] }),
      pay_refund: () => ({ ok: true }),
      pay_export_csv: () => ({ filename: 'lifolo-pay-pharmacy-2026-10.csv', csv: '﻿id,amount\r\nprq_ok,450\r\n' }),
    });
    await open(page, owner('/work/payments/all?biz=pharmacy%3Au-work'));
    await page.getByTestId('pay-status-filter').getByRole('button', { name: 'পরিশোধিত' }).click();
    await expect.poll(() => bodies(seen, 'pay_list_biz').at(-1)).toMatchObject({ status: 'confirmed', period: '7d' });
    const row = page.getByTestId('pay-list').getByTestId('biz-pay').filter({ hasText: 'সুমি' });
    await row.getByTestId('pay-refund-open').click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('refund-to-sender')).toContainText('শেষ ৪: ৪৩২১');
    await dlg.getByTestId('refund-trx').fill('rf-123456');
    await dlg.getByTestId('refund-save').click();
    await expect.poll(() => bodies(seen, 'pay_refund')[0]).toMatchObject({ id: 'prq_ok', trxId: 'RF123456' });
    const dl = page.waitForEvent('download');
    await page.getByTestId('pay-csv-export').click();
    expect((await dl).suggestedFilename()).toBe('lifolo-pay-pharmacy-2026-10.csv');
  });

  test('নম্বর: ২৪ ঘণ্টার নোটিশ, পুরোনো লগইনে "আবার লগইন করুন" (401 reauth_required), "আমি করিনি" → pay_methods_freeze; ডাক্তারের অগ্রিম', async ({ page }) => {
    let set = 0;
    await page.route('**/api/auth/send-code', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"rid":"r1"}' }));
    const seen = await mock(page, {
      pay_methods_get: (b) => ({
        kind: b.kind, bizId: b.bizId,
        methods: [{ method: 'bkash', number: '01711223344', type: 'personal', active: true, activeNumber: '01711223344', pendingUntil: null },
          { method: 'nagad', number: '01911000111', type: 'merchant', active: true, activeNumber: null, pendingUntil: Date.now() + 20 * H }],
        history: [{ method: 'nagad', change: 'added', oldLast4: null, newLast4: '0111', by: 'u-work', at: Date.now() - 4 * H }],
        ...(b.kind === 'chamber' ? { advance: [], chambers: [{ chamberKey: 'dghs_3', name: 'Popular Hospital', nameBn: 'পপুলার হাসপাতাল' }] } : {}),
      }),
      pay_methods_set: () => (++set === 1 ? { __status: 401, body: { error: 'login again', code: 'reauth_required' } } : { ok: true }),
      pay_methods_freeze: () => ({ ok: true }),
    });
    await open(page, owner('/work/payments/settings?biz=pharmacy%3Au-work'));
    const s = page.getByTestId('pay-settings');
    await expect(s.getByTestId('pay-cooloff')).toContainText('২৪ ঘণ্টা পর চালু হবে');
    await expect(s.getByTestId('pm-pending')).toContainText('নতুন নম্বর ০১৯১১০০০১১১ চালু হবে');
    await s.getByTestId('pm-on-rocket').locator('input').check();
    await s.getByTestId('pm-number-rocket').fill('0161');
    await s.getByTestId('pm-save').click();
    await expect(s).toContainText('রকেট-এর জন্য সঠিক মোবাইল নম্বর দিন');
    await s.getByTestId('pm-number-rocket').fill('01611223344');
    await s.getByTestId('pm-save').click();
    await expect.poll(() => bodies(seen, 'pay_methods_set')[0]?.methods).toContainEqual({ method: 'rocket', number: '01611223344', type: 'personal', active: true });
    const re = page.getByRole('dialog');
    await expect(re).toContainText('আবার লগইন করুন');
    await re.getByTestId('reauth-send').click();
    await expect(re.getByTestId('reauth-code')).toBeVisible();
    await page.keyboard.press('Escape');

    await s.getByTestId('pay-history').getByTestId('pay-not-me').click();
    await page.getByRole('dialog').getByRole('button', { name: 'আমি করিনি' }).click();
    await expect.poll(() => bodies(seen, 'pay_methods_freeze')[0]).toMatchObject({ kind: 'pharmacy', bizId: 'u-work' });

    // ডাক্তার: চেম্বারের অগ্রিম
    await page.goto('/work/payments/settings?biz=chamber%3Au-work', { waitUntil: 'networkidle' });
    const adv = page.getByTestId('pay-advance');
    await adv.locator('[data-chamber="dghs_3"] input[type=checkbox]').check();
    await adv.getByTestId('adv-amount').fill('৩০০');
    await adv.getByTestId('adv-save').click();
    await expect.poll(() => bodies(seen, 'pay_methods_set').find((b) => b.advance)?.advance).toEqual([{ chamberKey: 'dghs_3', amount: 300, refundableUntilHoursBefore: 24 }]);
  });

  test('ব্যবসার দিক থেকে পেমেন্ট চাওয়া: অনলাইন অর্ডারে "পেমেন্ট চান" → pay_request_create; "আমার কাজ"-এ পেমেন্টের কার্ড', async ({ page }) => {
    const seen = await mock(page, {
      rx_orders_pharmacy: async (_b, route) => {
        const j = await base(route);
        const items = j.items.map((o: any, i: number) => (i === 0 ? { ...o, mode: 'online', status: 'accepted', price: '৳৪৫০' } : o));
        return { ...j, items };
      },
      pay_request_get: () => notFound(),
      pay_request_create: (b) => ({ ok: true, created: true, request: REQ({ refId: b.refId }) }),
    });
    await open(page, owner('/work'));
    await expect(page.getByTestId('pay-biz-link')).toBeVisible();
    await page.goto('/work/pharmacy/orders?s=active', { waitUntil: 'networkidle' });
    const ask = page.getByTestId('ask-pay').first();
    await ask.getByTestId('ask-pay-open').click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('ask-pay-amount-note')).toContainText('অর্ডার বা বিল থেকে');
    await dlg.getByTestId('ask-pay-send').click();
    await expect.poll(() => bodies(seen, 'pay_request_create')[0]).toMatchObject({ refType: 'rx_order', purpose: 'delivery' });
    expect(bodies(seen, 'pay_request_create')[0].amount).toBeUndefined();
  });
});

test('অনলাইন চেম্বারের bKash নম্বর বদল (provider_save): পুরোনো লগইনে 401 reauth_required → "আবার লগইন করুন"', async ({ page }) => {
  await page.route('**/api/auth/send-code', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' }));
  const seen = await mock(page, { provider_save: () => ({ __status: 401, body: { error: 'login again', code: 'reauth_required' } }) });
  await open(page, def('appointment', 'pro-setup'));
  await page.getByRole('button', { name: 'সেভ করুন' }).click();
  await expect.poll(() => bodies(seen, 'provider_save').length).toBe(1);
  const dlg = page.getByRole('dialog');
  await expect(dlg).toContainText('আবার লগইন করুন');
  await dlg.getByTestId('reauth-send').click();
  await expect(dlg.getByTestId('reauth-code')).toBeVisible();
});

test('ফিচার সুইচ বন্ধ: পেমেন্টের ট্যাব/কার্ড/বোতাম কিছুই নেই, সরাসরি লিংকে "শিগগির আসছে"', async ({ page }) => {
  const errs = watchErrors(page);
  const keys = { inv_pharmacy: true, inv_lab: true, inv_chamber: true, inv_ambulance: true, my_expenses: true };
  const seen = await mock(page, {}, keys);
  await open(page, patient('/invoices'));
  await expect(page.locator('.health-tabs')).not.toContainText('পেমেন্ট');
  await open(page, patient('/invoices/inv2'));
  await expect(page.getByTestId('pay-entry')).toHaveCount(0);
  await open(page, patient('/invoices/payments'));
  await expect(page.getByTestId('feature-off')).toBeVisible();
  await open(page, patient('/services/pharmacy/prescriptions/rx1'));
  await expect(page.getByTestId('pay-entry')).toHaveCount(0);
  await open(page, owner('/work'));
  await expect(page.getByTestId('pay-biz-link')).toHaveCount(0);
  await open(page, owner('/work/payments'));
  await expect(page.getByTestId('feature-off')).toBeVisible();
  await open(page, owner('/work/pharmacy/orders?s=active'));
  await expect(page.getByTestId('ask-pay')).toHaveCount(0);
  expect(seen.some((c) => c.action.startsWith('pay_') || c.action === 'my_payments')).toBe(false);
  expect(errs).toEqual([]);
});

test('রক্তের অংশে কোনো পেমেন্ট নেই (সব সুইচ চালু থাকলেও)', async ({ page }) => {
  const seen = await mock(page);
  for (const [part, name] of [['blood', 'home-member'], ['blood', 'request-detail'], ['blood', 'my-request-detail'], ['blood', 'new-request']] as const) {
    await open(page, def(part, name));
    await expect(page.locator('[data-testid^="pay-"], [data-testid="ask-pay"]')).toHaveCount(0);
  }
  expect(seen.some((c) => c.action.startsWith('pay_') || c.action === 'my_payments')).toBe(false);
});

test('bKash গেটওয়ের ফল: অবস্থা সার্ভার থেকে (ঠিকানার status নয়); সুইচ বন্ধ হলে "শিগগির আসছে"', async ({ page }) => {
  const seen = await mock(page, { pgw_status: (b) => (b.id === 'ck1' ? { status: 'completed', amount: 299, trxId: 'BK12345678' } : { status: 'failed' }) });
  await open(page, patient('/pay/result/ck1?status=failure'));
  await expect(page.getByTestId('pgw-result')).toContainText('পেমেন্ট সফল হয়েছে');
  await expect(page.getByTestId('pgw-result')).toContainText('BK12345678');
  expect(bodies(seen, 'pgw_status')[0]).toMatchObject({ id: 'ck1' });
  await page.goto('/pay/result/ck2?status=success', { waitUntil: 'networkidle' });
  await expect(page.getByTestId('pgw-result')).toContainText('পেমেন্ট হয়নি');
  const off = await (async () => {
    const p2 = await page.context().newPage();
    await mock(p2, {}, {});
    await open(p2, patient('/pay/result/ck1'));
    return p2;
  })();
  await expect(off.getByTestId('feature-off')).toBeVisible();
});

test('অ্যাডমিন: রিপোর্টের পাতায় "পেমেন্ট" ট্যাব (TrxID ঢাকা) আর "সতর্কতা" (আটকানো নম্বর, ফেরত পাওনা)', async ({ page }) => {
  const errs = watchErrors(page);
  const seen = await mock(page, {
    admin_appt_reports: () => ({ items: [] }),
    admin_features_get: () => ({ items: [{ key: 'pay_manual', mode: 'pilot' }] }),
    admin_pay_reports: (b) => ({
      items: b.status === 'open'
        ? [{ id: 'prp1', reporterUid: 'u9', reason: 'asked_other_number', note: 'চ্যাটে অন্য নম্বর দিয়েছে', status: 'open', createdAt: Date.now() - H, request: REQ({ status: 'claimed', claim: { method: 'bkash', trxId: '9GH7K2LM1P', senderLast4: '5678', at: Date.now() - 2 * H } }), events: [{ action: 'claim', from: 'open', to: 'claimed', trx: '9GH7K2LM1P', at: Date.now() - 2 * H }] }]
        : [],
    }),
    admin_pay_report_set: () => ({ ok: true }),
    admin_pay_unfreeze: () => ({ ok: true }),
    admin_pay_flags: () => ({
      users: [{ uid: 'u5', name: 'করিম', rejected30d: 3, lastRejectAt: Date.now() - H, openClaims: 1 }],
      businesses: [{ kind: 'pharmacy', bizId: 'b7', bizName: 'সেবা ফার্মেসি', pendingClaims: 2, oldestClaimAt: Date.now() - 80 * H }],
      frozen: [{ kind: 'chamber', bizId: 'd3', bizName: 'ডা. কামাল', frozenAt: Date.now() - H, via: 'email' }],
      numbersChanged: [{ kind: 'chamber', bizId: 'd3', bizName: 'ডা. কামাল', method: 'bkash', change: 'number', oldLast4: '1111', newLast4: '9999', at: Date.now() - 2 * H }],
      refundsOverdue: [{ request: REQ({ status: 'confirmed', bizName: 'আল-মদিনা', refundDue: { at: Date.now() - 8 * 24 * H, why: 'order_cancelled' } }) }],
      reportsLate: [], disputedRejects: [],
    }),
  }, { pay_manual: true });
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
  await expect(page.locator('nav.nav a', { hasText: 'অনলাইন, কল ও মেসেজের রিপোর্ট' })).toBeVisible();
  await page.goto('/admin#/apptreports');
  await page.locator('main.page [role=tablist]').first().getByRole('tab', { name: 'পেমেন্ট' }).click();
  await expect(page).toHaveURL(/#\/payreports/);
  const main = page.locator('main.page');
  await expect(page.locator('.topbar h1')).toContainText('পেমেন্ট রিপোর্ট');
  await expect(main).toContainText('অন্য নম্বরে টাকা চেয়েছে');
  await expect(main).toContainText('9G••••1P');
  await expect(main).not.toContainText('9GH7K2LM1P');
  // "দোকানের দোষ" — সতর্ক বোতাম: "নিশ্চিত? আবার চাপুন"
  await main.getByRole('button', { name: 'দোকানের দোষ' }).click();
  await main.getByRole('button', { name: /আবার চাপুন/ }).click();
  await expect.poll(() => bodies(seen, 'admin_pay_report_set')[0]).toMatchObject({ id: 'prp1', status: 'resolved', outcome: 'business_fault' });
  await page.getByTestId('pay-report-tabs').getByRole('tab', { name: 'সতর্কতা' }).click();
  await expect(main).toContainText('"আমি করিনি" — আটকানো পেমেন্ট নম্বর (১)');
  await expect(main).toContainText('ডা. কামাল');
  await main.getByRole('button', { name: 'আবার চালু করুন' }).click();
  await expect.poll(() => bodies(seen, 'admin_pay_unfreeze')[0]).toMatchObject({ kind: 'chamber', bizId: 'd3' });
  await expect(main).toContainText('ফেরত পাওনা ৭ দিনের বেশি (১)');
  await expect(main).toContainText('আল-মদিনা');
  await expect(main).toContainText('সেবা ফার্মেসি');
  await expect(main).toContainText('করিম');
  expect(errs.filter((e) => !/401|403|Unauthorized/.test(e))).toEqual([]);
});
