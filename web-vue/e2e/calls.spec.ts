// অ্যাপে কল (নকল API + নকল LiveKit: window.__lfTest.fakeMedia): কল করা → রিং → কথা → রেখে দেওয়া; বন্ধ রাখা/ব্যস্ত;
// কল আসা (SSE) → ধরা/কাটা/ওপাশ রেখে দেওয়া; SSE না চললে তালিকা দেখে কল আসা; অনুমতি না দিলে বাংলা লেখা; ক্যামেরা না পেলে শুধু অডিও;
// কলের তালিকা; অনলাইন অ্যাপয়েন্টমেন্টের অপেক্ষার ঘর; কল বন্ধ থাকলে বোতাম নেই।
import { expect, test, type Page } from '@playwright/test';
import { def, noSideScroll, open, watchErrors, type PageDef } from './helpers';

type H = (body: any) => unknown;
interface Api { calls: { action: string; body: any }[] }

/** /api/community-র কিছু কাজ এই পরীক্ষার হাতে; বাকি নকল সার্ভারে */
async function api(page: Page, handlers: Record<string, H> = {}): Promise<Api> {
  const st: Api = { calls: [] };
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    st.calls.push({ action: body.action, body });
    const h = handlers[body.action];
    if (!h) return route.continue();
    const out: any = await h(body);
    if (out && out.__status) return route.fulfill({ status: out.__status, contentType: 'application/json', body: JSON.stringify(out.body || {}) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(out ?? { ok: true }) });
  });
  return st;
}
/** open()-এর মতো, কিন্তু নেট থামার অপেক্ষা নয় (কল আসার সময় call_status বারবার চলে) */
async function openLive(page: Page, p: PageDef) {
  await page.addInitScript((t: any) => {
    (window as any).__lfTest = t;
    try {
      localStorage.clear();
      localStorage.setItem('lf.lang', 'bn');
    } catch {}
  }, p.test || {});
  await page.goto(p.path, { waitUntil: 'domcontentloaded' });
}
const did = (st: Api, action: string) => st.calls.filter((c) => c.action === action);
const withTest = (p: PageDef, extra: Record<string, unknown>, path?: string): PageDef => ({ ...p, path: path ?? p.path, test: { ...(p.test || {}), ...extra } });
const FAST = { pollMs: 300, activePollMs: 400, endShowMs: 800, endFlagShowMs: 1500 };
const screen = (page: Page) => page.getByTestId('call-screen');
const status = (page: Page) => page.getByTestId('call-status');

/** বাংলাদেশের আজকের দিন (UTC মধ্যরাত) আর এখনকার "HH:MM" — অ্যাপয়েন্টমেন্টের কলের সময় */
function dhakaNow(offsetMin = 0) {
  const d = new Date(Date.now() + 6 * 3600_000 + offsetMin * 60_000);
  const hhmm = `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
  return { date: Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()), hhmm };
}
/** রোগীর নিশ্চিত অ্যাপয়েন্টমেন্ট (offsetMin মিনিট পরের সময়ে) */
function apptNow(id: string, offsetMin = 10) {
  const t = dhakaNow(offsetMin);
  return { id, role: 'patient', type: 'doctor', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', room: '', serial: 2, schedule: '', note: '', providerNote: '', otherPhone: '01711000222', otherKey: '', createdAt: Date.now() - 86400000, status: 'confirmed', otherName: 'ডা. মাহমুদা আক্তার', date: t.date, start: t.hhmm };
}

/** SSE-তে একটা ঘটনা (একবারই; পরের বার খালি স্ট্রিম) */
async function sse(page: Page, events: { event: string; data: unknown }[]) {
  let sent = false;
  await page.route('**/api/events', async (route) => {
    const body = sent ? ': ping\n\n' : events.map((e) => `event: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`).join('');
    sent = true;
    await route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body });
  });
}

test('রক্তের রিকোয়েস্ট: ডোনারকে অ্যাপে কল → রিং → ধরলেন → সময় চলে → কল কাটুন (call_end)', async ({ page }) => {
  const errs = watchErrors(page);
  let state = 'ringing';
  const st = await api(page, { call_status: () => ({ state }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: -1 }, callTiming: FAST }));
  const btn = page.getByTestId('call-audio').first();
  await expect(btn).toHaveText('অডিও কল');
  await expect(page.getByTestId('call-video').first()).toHaveText('ভিডিও কল');
  // সব বোতামের জন্য একটাই contact_can
  await expect.poll(() => did(st, 'contact_can').length).toBe(1);
  expect(did(st, 'contact_can')[0].body.items.length).toBeGreaterThan(1);
  expect(did(st, 'contact_can')[0].body.items[0]).toMatchObject({ ctx: 'blood', refId: 'm1' });
  // মেসেজ বোতাম চ্যাট অংশ জুড়লে তবেই
  await expect(page.getByTestId('contact-message')).toHaveCount(0);
  await btn.click();
  await expect(screen(page)).toBeVisible();
  await expect(status(page)).toHaveText('রিং হচ্ছে');
  const start = did(st, 'call_start')[0].body;
  expect(start).toMatchObject({ ctx: 'blood', refId: 'm1', video: false });
  expect(start.peerUid).toBeTruthy();
  await expect(page.getByTestId('call-peer')).toHaveText('সুমাইয়া আক্তার');
  // ফোনের বোতাম এখনো আগে (অ্যাপে কল তার পরে)
  await noSideScroll(page);

  state = 'accepted';
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
  await page.evaluate(() => (window as any).__lfCallFake.peerJoin());
  await expect(status(page)).toHaveText(/^০০:০[০-৯]$/);
  // মাইক বন্ধ/চালু
  await page.getByTestId('call-mic').click();
  await expect(page.getByTestId('call-mic')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('call-mic')).toHaveAttribute('aria-label', 'মাইক চালু');
  await page.getByTestId('call-hangup').click();
  await expect.poll(() => did(st, 'call_end').length).toBe(1);
  await expect(status(page)).toContainText('কল শেষ');
  await expect(screen(page)).toHaveCount(0, { timeout: 3000 });
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log[0]).toBe('connect wss://lifolo.com/rtc-test tok-caller');
  expect(log).toContain('mic false');
  expect(log.at(-1)).toBe('disconnect');
  expect(errs).toEqual([]);
});

test('কল করা: ওপাশ কেটে দিলেন / কেউ ধরলেন না — কারণ দেখায়, ঘর ছাড়ে', async ({ page }) => {
  let state = 'ringing';
  await api(page, { call_status: () => ({ state }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: -1 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText('রিং হচ্ছে');
  state = 'declined';
  await expect(status(page)).toHaveText('উনি কল কেটে দিয়েছেন');
  await expect(screen(page)).toHaveCount(0, { timeout: 3000 });
  state = 'ringing';
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText('রিং হচ্ছে');
  state = 'missed';
  await expect(status(page)).toHaveText('কেউ ধরেননি');
});

test('কল করা: রিং হওয়ার সময় রেখে দিলে call_cancel', async ({ page }) => {
  const st = await api(page);
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: -1 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText('রিং হচ্ছে');
  expect(did(st, 'call_start')[0].body.video).toBe(false);
  await expect(page.getByTestId('call-cam')).toHaveCount(0);
  await page.getByTestId('call-hangup').click();
  await expect.poll(() => did(st, 'call_cancel').length).toBe(1);
  expect(did(st, 'call_end')).toHaveLength(0);
});

test('উনি অ্যাপে কল বন্ধ রেখেছেন (409 call_off): সার্ভারের লেখা আর ফোনে কল করার বোতাম', async ({ page }) => {
  await api(page, { call_start: () => ({ __status: 409, body: { error: 'উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন', code: 'call_off' } }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: {}, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-error')).toContainText('উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন');
  const tel = page.getByTestId('call-error').locator('a[href^="tel:"]');
  await expect(tel).toContainText('ফোনে কল করুন');
  await page.getByTestId('call-close').click();
  await expect(screen(page)).toHaveCount(0);
});

test('ব্যস্ত (409 call_busy): বাংলা লেখা', async ({ page }) => {
  await api(page, { call_start: () => ({ __status: 409, body: { error: 'busy', code: 'call_busy' } }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: {}, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-error')).toContainText('উনি এখন অন্য কলে আছেন। একটু পরে আবার চেষ্টা করুন।');
});

test('মাইকের অনুমতি না দিলে: কল শুরুই হয় না, বাংলায় কীভাবে অনুমতি দেবেন', async ({ page }) => {
  const st = await api(page);
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { denyMic: true }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-error')).toContainText('মাইক্রোফোনের অনুমতি দেওয়া হয়নি। ঠিকানার পাশের তালা চিহ্নে চেপে');
  expect(did(st, 'call_start')).toHaveLength(0);
});

test('ভিডিও কলে ক্যামেরা না পেলে শুধু অডিওতে চলে; নেট দুর্বল হলে "শুধু অডিও"; ডিভাইস বদলানো', async ({ page }) => {
  await api(page, { call_status: () => ({ state: 'accepted' }), my_appointments: () => ({ items: [apptNow('a1')] }) });
  await open(page, withTest(def('appointment', 'my-detail'), { fakeMedia: { denyCam: true, peerAfterMs: 50 }, callTiming: FAST }));
  await page.getByTestId('call-video').first().click();
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
  await expect(page.getByTestId('call-note')).toHaveText('ক্যামেরা চালু হয়নি — শুধু অডিওতে চলছে');
  await expect(page.getByTestId('call-cam')).toHaveAttribute('aria-label', 'ক্যামেরা চালু');
  await page.evaluate(() => (window as any).__lfCallFake.poor(true));
  await page.getByTestId('call-audio-only').click();
  await expect(page.getByTestId('call-video-back')).toBeVisible();
  await page.getByTestId('call-devices').click();
  const dlg = page.getByTestId('call-device-dialog');
  await expect(dlg.getByLabel('মাইক্রোফোন')).toBeVisible();
  await dlg.getByLabel('মাইক্রোফোন').selectOption('m2');
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log).toContain('audioOnly true');
  await expect.poll(() => page.evaluate(() => (window as any).__lfCallFake.log.includes('device audioinput m2'))).toBe(true);
});

test('আবার সংযোগ: "আবার সংযোগ হচ্ছে…" দেখায়, ফিরে এলে সময়', async ({ page }) => {
  await api(page, { call_status: () => ({ state: 'accepted' }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText(/^০০:/);
  await page.evaluate(() => (window as any).__lfCallFake.reconnecting());
  await expect(status(page)).toHaveText('আবার সংযোগ হচ্ছে…');
  await page.evaluate(() => (window as any).__lfCallFake.reconnected());
  await expect(status(page)).toHaveText(/^০০:/);
  // ওপাশ বেরিয়ে গেলে কল শেষ
  await page.evaluate(() => (window as any).__lfCallFake.peerLeave());
  await expect(status(page)).toContainText('উনি কল থেকে বেরিয়ে গেছেন');
});

test('কল আসছে (SSE call_incoming): ব্যানার → ধরুন → কথা → কল কাটুন', async ({ page }) => {
  const errs = watchErrors(page);
  const st = await api(page, { call_status: () => ({ state: 'ringing' }) });
  await sse(page, [{ event: 'call_incoming', data: { callId: 'in1', video: true, ctx: 'appointment', refId: 'a1', caller: { uid: 'p1', name: 'ডা. মাহমুদা খাতুন' } } }]);
  await openLive(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  const banner = page.getByTestId('call-incoming');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('ডা. মাহমুদা খাতুন');
  await expect(banner).toContainText('ভিডিও কল আসছে · অ্যাপয়েন্টমেন্ট');
  await expect(page).toHaveTitle(/ভিডিও কল আসছে/);
  await noSideScroll(page);
  await banner.getByTestId('call-accept').click();
  await expect.poll(() => did(st, 'call_accept').map((c) => c.body.callId)).toEqual(['in1']);
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
  await expect(status(page)).toHaveText(/^০০:/);
  await expect(page).not.toHaveTitle(/কল আসছে/);
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log[0]).toBe('connect wss://lifolo.com/rtc-test tok-callee');
  await page.getByTestId('call-hangup').click();
  await expect.poll(() => did(st, 'call_end').length).toBe(1);
  expect(errs).toEqual([]);
});

test('কল আসছে: কেটে দিন → call_decline; ওপাশ রেখে দিলে ব্যানার চলে যায়', async ({ page }) => {
  let state = 'ringing';
  const st = await api(page, { call_status: () => ({ state }) });
  await sse(page, [{ event: 'call_incoming', data: { callId: 'in2', video: false, ctx: 'blood', refId: 'r9', caller: { uid: 'x1', name: 'সালমা আক্তার' } } }]);
  await openLive(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: {}, callTiming: FAST }));
  await expect(page.getByTestId('call-incoming')).toContainText('অ্যাপে কল আসছে · রক্তের রিকোয়েস্ট');
  await page.getByTestId('call-decline').click();
  await expect(page.getByTestId('call-incoming')).toHaveCount(0);
  await expect.poll(() => did(st, 'call_decline').map((c) => c.body.callId)).toEqual(['in2']);

  // আরেকটা কল আসে, ওপাশ রেখে দেন (call_state)
  await page.unroute('**/api/events');
  await sse(page, [
    { event: 'call_incoming', data: { callId: 'in3', video: false, ctx: 'blood', refId: 'r9', caller: { uid: 'x1', name: 'সালমা আক্তার' } } },
  ]);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('call-incoming')).toBeVisible();
  state = 'cancelled';
  await expect(page.getByTestId('call-incoming')).toHaveCount(0);
  await expect(screen(page)).toHaveCount(0);
});

test('SSE না চললে: কলের তালিকা দেখে কল আসা ধরা পড়ে', async ({ page }) => {
  await api(page, {
    call_history: () => ({ items: [{ callId: 'poll1', ctx: 'ambulance', refId: 'ar1', peer: { uid: 'dr1', name: 'জনসেবা অ্যাম্বুলেন্স' }, video: false, state: 'ringing', startedAt: Date.now(), durationSec: 0, dir: 'in' }] }),
  });
  await openLive(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: {}, callTiming: { ...FAST, events: { backoffMs: [100, 100], pollMs: 300 } } }));
  await expect(page.getByTestId('call-incoming')).toContainText('জনসেবা অ্যাম্বুলেন্স');
});

test('কলের তালিকা: দুই দিকের কল, মিস হওয়া, আরও দেখুন; প্রোফাইল থেকে খোলে', async ({ page }) => {
  const errs = watchErrors(page);
  const st = await api(page);
  await open(page, withTest(def('account', 'profile-nobiz'), {}));
  await page.getByRole('button', { name: /কলের তালিকা/ }).click();
  await expect(page).toHaveURL(/\/calls$/);
  const rows = page.getByTestId('call-row');
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0)).toContainText('রাকিব হাসান');
  await expect(rows.nth(0)).toContainText('করেছি · অডিও কল · রক্তের রিকোয়েস্ট · ০৩:০৫');
  await expect(rows.nth(1)).toContainText('এসেছে · ভিডিও কল · অ্যাপয়েন্টমেন্ট · মিস হয়েছে');
  await expect(rows.nth(2)).toContainText('কেটে দিয়েছেন');
  await page.getByRole('button', { name: 'আরও দেখুন' }).click();
  await expect(page.getByRole('button', { name: 'আরও দেখুন' })).toHaveCount(0);
  expect(did(st, 'call_history').some((c) => typeof c.body.before === 'number')).toBe(true);
  await noSideScroll(page);
  await rows.nth(0).getByRole('button').first().click();
  await expect(page).toHaveURL(/\/my-requests\/m1$/);
  expect(errs).toEqual([]);
});

test('অনলাইন অ্যাপয়েন্টমেন্ট: ভিডিও কলে যুক্ত হোন → অপেক্ষার ঘর → ডাক্তার এলে কথা', async ({ page }) => {
  const errs = watchErrors(page);
  const st = await api(page);
  await open(page, withTest(def('appointment', 'my'), { fakeMedia: { peerAfterMs: -1 }, callTiming: FAST }), '/appointments/a1/call?name=ডা. মাহমুদা খাতুন');
  await expect(page.getByTestId('appt-call-page')).toContainText('সময়ের ১৫ মিনিট আগে থেকে যুক্ত হতে পারবেন');
  await page.getByTestId('appt-join').click();
  await expect.poll(() => did(st, 'appt_join').map((c) => c.body.appointmentId)).toEqual(['a1']);
  await expect(status(page)).toHaveText('ডাক্তারের অপেক্ষায়…');
  await expect(screen(page)).toContainText('অপেক্ষার ঘর');
  await expect(screen(page)).toContainText('এই পাতায় থাকুন — উনি যুক্ত হলেই কল শুরু হবে।');
  await expect(page.getByTestId('call-peer')).toHaveText('ডা. মাহমুদা খাতুন');
  await noSideScroll(page);
  await page.evaluate(() => (window as any).__lfCallFake.peerJoin());
  await expect(status(page)).toHaveText(/^০০:/);
  // ডাক্তার সাময়িক বেরোলে আবার অপেক্ষার ঘর (কল শেষ নয়)
  await page.evaluate(() => (window as any).__lfCallFake.peerLeave());
  await expect(status(page)).toHaveText('ডাক্তারের অপেক্ষায়…');
  await page.getByTestId('call-hangup').click();
  await expect(screen(page)).toHaveCount(0, { timeout: 3000 });
  expect(did(st, 'call_end')).toHaveLength(0);
  expect(errs).toEqual([]);
});

test('অনলাইন অ্যাপয়েন্টমেন্ট: সময়ের আগে সার্ভার "না" বললে তার লেখা', async ({ page }) => {
  await api(page, { appt_join: () => ({ __status: 403, body: { error: 'সময়ের ১৫ মিনিট আগে থেকে যুক্ত হওয়া যাবে', code: 'too_early' } }) });
  await open(page, withTest(def('appointment', 'my'), { fakeMedia: {}, callTiming: FAST }), '/appointments/a1/call');
  await page.getByTestId('appt-join').click();
  await expect(page.getByTestId('call-error')).toContainText('সময়ের ১৫ মিনিট আগে থেকে যুক্ত হওয়া যাবে');
});

test('ডাক্তার কলে এলেন (SSE appt_call_ready): ব্যানার থেকে যুক্ত হওয়া', async ({ page }) => {
  const st = await api(page);
  await sse(page, [{ event: 'appt_call_ready', data: { appointmentId: 'a1' } }]);
  await open(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  const b = page.getByTestId('appt-call-ready');
  await expect(b).toContainText('ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন');
  await b.getByTestId('appt-ready-join').click();
  await expect.poll(() => did(st, 'appt_join').length).toBe(1);
  await expect(status(page)).toHaveText(/^০০:/);
});

test('সার্ভারে contact_can না থাকলে আর call_config enabled=false: বোতাম নেই', async ({ page }) => {
  await api(page, { contact_can: () => ({ __status: 400, body: { error: 'unknown action: contact_can' } }), call_config: () => ({ enabled: false }) });
  await open(page, withTest(def('blood', 'my-request-detail'), {}));
  await expect(page.locator('.blood-donor').first()).toBeVisible();
  await expect(page.getByTestId('call-buttons')).toHaveCount(0);
});

test('ব্যানার মোবাইল ব্রাউজারের মাপে: পাশে স্ক্রল নেই, শুধু নাম (লেখা) দিলেও চলে', async ({ page }) => {
  await api(page);
  await sse(page, [{ event: 'call_incoming', data: { callId: 'in9', video: false, ctx: 'order', refId: 'o1', caller: 'নতুন ফার্মেসি' } }]);
  await openLive(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: {}, callTiming: FAST }));
  await expect(page.getByTestId('call-incoming')).toContainText('নতুন ফার্মেসি');
  await expect(page.getByTestId('call-incoming')).toContainText('অর্ডার');
  // পাতায় চাপ পড়েছে কি না Playwright-এ নির্দিষ্ট নয় — "শব্দ শুনতে চাপুন" থাকলে চাপলে চলে যায় (না-চাপা অবস্থা: tests/unit/calls.test.ts)
  const sound = page.getByTestId('call-sound');
  if (await sound.count()) {
    await expect(sound).toHaveText('শব্দ শুনতে চাপুন');
    await sound.click();
    await expect(sound).toHaveCount(0);
  }
  await noSideScroll(page);
});

test('অ্যাপয়েন্টমেন্ট (রোগী, contact_can ছাড়া): নিশ্চিত আর সময়ের মধ্যে হলে কলের বোতাম, সময় অনেক দূরে হলে নেই', async ({ page }) => {
  const nowSlot = dhakaNow(10);
  const far = dhakaNow(-5 * 60);
  const appt = (o: Record<string, unknown>) => ({ role: 'patient', type: 'doctor', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', room: '', serial: 2, schedule: '', note: '', providerNote: '', otherPhone: '01711000222', otherKey: '', createdAt: Date.now() - 86400000, status: 'confirmed', otherName: 'ডা. মাহমুদা আক্তার', ...o });
  const st = await api(page, {
    contact_can: () => ({ __status: 400, body: { error: 'unknown action: contact_can' } }), 
    my_appointments: () => ({ items: [appt({ id: 'a1', date: nowSlot.date, start: nowSlot.hhmm }), appt({ id: 'a9', date: far.date, start: far.hhmm })] }),
    call_status: () => ({ state: 'ringing' }),
  });
  await open(page, withTest(def('appointment', 'my-detail'), { fakeMedia: {}, callTiming: FAST }));
  await expect(page.getByTestId('call-buttons')).toHaveCount(1);
  await page.getByTestId('call-video').click();
  await expect(status(page)).toHaveText('রিং হচ্ছে');
  expect(did(st, 'call_start')[0].body).toMatchObject({ ctx: 'appointment', refId: 'a1', video: true });
  await page.getByTestId('call-hangup').click();
  await open(page, withTest(def('appointment', 'my-detail'), {}), '/my-appointments/a9');
  await expect(page.getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট বাতিল' })).toBeVisible();
  await expect(page.getByTestId('call-buttons')).toHaveCount(0);
});

test('অ্যাপয়েন্টমেন্ট (ডাক্তার): আজকের বুকিংয়ে রোগীকে অ্যাপে কল', async ({ page }) => {
  const nowSlot = dhakaNow(0);
  const st = await api(page, {
    my_appointments: () => ({
      items: [{ id: 'q1', role: 'provider', type: 'doctor', chamber: 'Labib Hospital', chamberBn: 'লাবিব হাসপাতাল', room: '২০৪', start: nowSlot.hhmm, serial: 1, schedule: '', providerNote: '', otherPhone: '01711100001', otherName: 'আব্দুল করিম', otherKey: 'k1', date: nowSlot.date, note: '', status: 'confirmed', createdAt: Date.now() - 86400000 }],
    }),
  });
  await open(page, withTest(def('appointment', 'pro-bookings'), { fakeMedia: {}, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-peer')).toBeVisible();
  expect(did(st, 'call_start')[0].body).toMatchObject({ ctx: 'appointment', refId: 'q1', video: false });
});

test('অডিও কল থেকে ভিডিও: "ভিডিও চালু করতে অনুরোধ" → ওপাশ রাজি → নতুন টোকেনে ভিডিওসহ আবার যুক্ত', async ({ page }) => {
  let video = false;
  const st = await api(page, {
    call_status: () => (video ? { state: 'accepted', video: true, url: 'wss://lifolo.com/rtc-test', token: 'tok-video2' } : { state: 'accepted', video: false }),
  });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText(/^০০:/);
  await page.getByTestId('call-ask-video').click();
  await expect.poll(() => did(st, 'call_upgrade').map((c) => c.body.callId)).toEqual(['c_new']);
  await expect(page.getByTestId('call-note')).toHaveText('ভিডিওর অনুরোধ পাঠানো হয়েছে — ওনার উত্তরের অপেক্ষা');
  await expect(page.getByTestId('call-ask-video')).toHaveCount(0);
  video = true;
  await expect(page.getByTestId('call-cam')).toHaveAttribute('aria-label', 'ক্যামেরা বন্ধ');
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log).toContain('connect wss://lifolo.com/rtc-test tok-video2');
  expect(log).toContain('cam true');
});

test('ওপাশ ভিডিও চাইছেন (call_status upgradeBy): রাজি → call_upgrade_accept-এর টোকেনে ভিডিও; এখন না → call_upgrade_decline', async ({ page }) => {
  let asked = true;
  const st = await api(page, { call_status: () => ({ state: 'accepted', video: false, ...(asked ? { upgradeBy: 'peer1' } : {}) }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  const offer = page.getByTestId('call-video-offer');
  await expect(offer).toContainText('উনি ভিডিও চালু করতে চান');
  await offer.getByTestId('call-video-no').click();
  await expect.poll(() => did(st, 'call_upgrade_decline').length).toBe(1);
  asked = false;
  await page.waitForTimeout(600);
  asked = true;
  await expect(offer).toBeVisible();
  await offer.getByTestId('call-video-yes').click();
  await expect.poll(() => did(st, 'call_upgrade_accept').length).toBe(1);
  await expect(page.getByTestId('call-cam')).toHaveAttribute('aria-label', 'ক্যামেরা বন্ধ');
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log).toContain('connect wss://lifolo.com/rtc-test tok-video');
});

test('সংযোগ পুরো কেটে গেলে: call_status-এর নতুন টোকেনে আবার যুক্ত', async ({ page }) => {
  await api(page, { call_status: () => ({ state: 'accepted', video: false, url: 'wss://lifolo.com/rtc-test', token: 'tok-fresh' }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: 50 }, callTiming: { ...FAST, activePollMs: 60_000 } }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText(/^০০:/);
  await page.evaluate(() => (window as any).__lfCallFake.drop());
  await expect.poll(() => page.evaluate(() => (window as any).__lfCallFake.log.includes('connect wss://lifolo.com/rtc-test tok-fresh'))).toBe(true);
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
});

test('কল শেষে ব্লক আর রিপোর্ট', async ({ page }) => {
  const st = await api(page, { call_status: () => ({ state: 'accepted' }) });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(status(page)).toHaveText(/^০০:/);
  const peerUid = did(st, 'call_start')[0].body.peerUid;
  await page.getByTestId('call-hangup').click();
  await expect(status(page)).toContainText('কল শেষ');
  await page.getByTestId('call-report').click();
  const dlg = page.getByTestId('call-report-dialog');
  await dlg.getByText('হয়রানি বা গালাগাল').click();
  await dlg.getByLabel('কী হয়েছিল? (ঐচ্ছিক)').fill('বারবার গালি দিচ্ছিলেন');
  await dlg.getByTestId('call-report-send').click();
  await expect.poll(() => did(st, 'call_report').map((c) => c.body)).toEqual([expect.objectContaining({ callId: 'c_new', reason: 'harassment', note: 'বারবার গালি দিচ্ছিলেন' })]);
  // খোলা রেখে দেওয়া (নিজে বন্ধ হয় না) — ব্লক
  await page.waitForTimeout(1800);
  await page.getByTestId('call-block').click();
  await expect(page.getByRole('alertdialog').or(page.getByRole('dialog').last())).toContainText('উনি আর আপনাকে অ্যাপে কল বা মেসেজ করতে পারবেন না');
  await page.getByRole('button', { name: 'যোগাযোগ বন্ধ করুন', exact: true }).last().click();
  await expect.poll(() => did(st, 'call_block').map((c) => c.body)).toEqual([expect.objectContaining({ peerUid, callId: 'c_new' })]);
  await page.getByTestId('call-close').click();
  await expect(screen(page)).toHaveCount(0);
});

test('403 call_blocked আর 429 call_rate: বাংলা লেখা', async ({ page }) => {
  let n = 0;
  await api(page, {
    call_start: () => (++n === 1 ? { __status: 403, body: { error: 'blocked', code: 'call_blocked' } } : { __status: 429, body: { error: 'Too many', code: 'call_rate' } }),
  });
  await open(page, withTest(def('blood', 'my-request-detail'), { fakeMedia: {}, callTiming: FAST }));
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-error')).toContainText('আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন');
  await page.getByTestId('call-close').click();
  await page.getByTestId('call-audio').first().click();
  await expect(page.getByTestId('call-error')).toContainText('অনেকবার কল করেছেন। কয়েক মিনিট পরে আবার চেষ্টা করুন।');
});

test('সেটিংস: অ্যাপে কল সুইচ আর ব্লক করা তালিকা (আনব্লক)', async ({ page }) => {
  const st = await api(page);
  await open(page, withTest(def('account', 'settings'), {}));
  await expect(page.locator('[data-cat="calls"]')).toContainText('অ্যাপে কল');
  await page.getByTestId('call-blocks').getByRole('button', { name: /যাদের সাথে যোগাযোগ বন্ধ/ }).click();
  const dlg = page.getByTestId('call-blocks-dialog');
  await expect(dlg).toContainText('অচেনা নম্বর');
  await dlg.getByRole('button', { name: 'আনব্লক' }).click();
  await expect.poll(() => did(st, 'call_unblock').map((c) => c.body.peerUid)).toEqual(['b1']);
});

test('ডোনার → রক্তপ্রার্থী: নিশ্চিত হলে বোতাম; না হলে শুধু রক্তপ্রার্থী আগে কল করে থাকলে (সার্ভারের canCall না থাকলে)', async ({ page }) => {
  const base = def('blood', 'request-accepted');
  await api(page, { contact_can: () => ({ __status: 400, body: { error: 'unknown action: contact_can' } }), call_history: () => ({ items: [] }) });
  await open(page, base);
  await expect(page.locator('.blood-contact').getByTestId('call-audio')).toHaveText('অডিও কল');

  const unconfirmed = withTest(base, { docs: { ...base.test!.docs, 'requests/r9/responses/u1': { donorUid: 'u1', status: 'accepted', confirmed: false } } });
  await open(page, unconfirmed);
  await expect(page.locator('.blood-contact')).toBeVisible();
  await page.waitForTimeout(500);
  await expect(page.locator('.blood-contact').getByTestId('call-buttons')).toHaveCount(0);

  await page.unroute('**/api/community');
  await api(page, { contact_can: () => ({ __status: 400, body: { error: 'unknown action: contact_can' } }), call_history: () => ({ items: [{ callId: 'z1', ctx: 'blood', refId: 'r9', dir: 'in', peer: { uid: 'x1', name: 'সালমা' }, video: false, state: 'ended', startedAt: Date.now() - 60000, durationSec: 20 }] }) });
  await open(page, unconfirmed);
  await expect(page.locator('.blood-contact').getByTestId('call-audio')).toBeVisible();
});

test('contact_can: canCall/canVideo না হলে সেই বোতাম নেই; নিজে ব্লক করে থাকলে "আনব্লক"', async ({ page }) => {
  const st = await api(page, {
    contact_can: (b) => ({
      items: b.items.map((i: any, n: number) => ({ ...i, peerUid: i.peerUid || 'p', canCall: n !== 1, canVideo: n === 0, canMessage: false, blocked: n === 2 })),
    }),
  });
  await open(page, withTest(def('blood', 'my-request-detail'), {}));
  const rows = page.getByTestId('call-buttons');
  await expect(rows.nth(0).getByTestId('call-audio')).toBeVisible();
  await expect(rows.nth(0).getByTestId('call-video')).toBeVisible();
  // দ্বিতীয়টা: কিছুই চলে না → পুরো সারিই নেই; তৃতীয়টা: ব্লক করা
  await expect(page.getByTestId('contact-unblock')).toHaveCount(1);
  await expect(page.getByText('যোগাযোগ বন্ধ রেখেছেন')).toBeVisible();
  await page.getByTestId('contact-unblock').click();
  await expect.poll(() => did(st, 'call_unblock').length).toBe(1);
  // আনব্লকের পর আবার জানা
  await expect.poll(() => did(st, 'contact_can').length).toBeGreaterThan(1);
});

test('রক্তের ভিডিও কল এলে: "অডিওতে ধরুন" (call_accept video:false → অডিও কল, পরে ভিডিওর অনুরোধ) আর "ভিডিওতে ধরুন"', async ({ page }) => {
  const st = await api(page, { call_status: () => ({ state: 'ringing' }) });
  await sse(page, [{ event: 'call_incoming', data: { callId: 'iv1', video: true, ctx: 'blood', refId: 'r9', caller: { uid: 'x1', name: 'সালমা আক্তার' } } }]);
  await openLive(page, withTest(def('account', 'profile-nobiz'), { fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  const banner = page.getByTestId('call-incoming');
  await expect(banner.getByTestId('call-accept')).toHaveText('ভিডিওতে ধরুন');
  await expect(banner.getByTestId('call-accept-audio')).toHaveText('অডিওতে ধরুন');
  await noSideScroll(page);
  await banner.getByTestId('call-accept-audio').click();
  await expect.poll(() => did(st, 'call_accept').map((c) => c.body)).toEqual([{ lang: 'bn', action: 'call_accept', callId: 'iv1', video: false }]);
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
  await expect(page.getByTestId('call-cam')).toHaveCount(0);
  await expect(page.getByTestId('call-ask-video')).toHaveText('ভিডিও চালু করতে অনুরোধ');
  const log = await page.evaluate(() => (window as any).__lfCallFake.log as string[]);
  expect(log).not.toContain('cam true');
});

test('ফিচার সুইচ: calls_audio বন্ধ — কলের বোতাম, কলের তালিকা, নোটিফিকেশনের সুইচ নেই; appt_video বন্ধ — "শিগগির আসছে"', async ({ page }) => {
  await api(page);
  const off = { features: { calls_audio: false, calls_video: false, appt_video: false, chat: false } };
  await open(page, withTest(def('blood', 'my-request-detail'), off));
  await expect(page.locator('.blood-donor').first()).toBeVisible();
  await expect(page.getByTestId('call-buttons')).toHaveCount(0);
  await open(page, withTest(def('account', 'settings'), off));
  await expect(page.locator('[data-cat="rx"]')).toBeVisible();
  await expect(page.locator('[data-cat="calls"]')).toHaveCount(0);
  await expect(page.getByTestId('call-blocks')).toHaveCount(0);
  await open(page, withTest(def('account', 'profile-nobiz'), off), '/calls');
  await expect(page.getByTestId('feature-soon')).toContainText('এই সুবিধা শিগগির আসছে');
  await open(page, withTest(def('appointment', 'my'), off), '/appointments/a1/call');
  await expect(page.getByTestId('feature-soon')).toContainText('এই সুবিধা শিগগির আসছে');
  await expect(page.getByTestId('appt-join')).toHaveCount(0);
});

test('ফিচার সুইচ: calls_video বন্ধ — শুধু অডিও কল; ভিডিও কল এলেও একটাই "ধরুন" (অডিওতে), ভিডিওর অনুরোধ নেই', async ({ page }) => {
  const st = await api(page, { call_status: () => ({ state: 'ringing' }) });
  await sse(page, [{ event: 'call_incoming', data: { callId: 'nv1', video: true, ctx: 'blood', refId: 'r9', caller: { uid: 'x1', name: 'সালমা আক্তার' } } }]);
  await openLive(page, withTest(def('account', 'profile-nobiz'), { features: { calls_video: false }, fakeMedia: { peerAfterMs: 50 }, callTiming: FAST }));
  const banner = page.getByTestId('call-incoming');
  await expect(banner.getByTestId('call-accept')).toHaveText('ধরুন');
  await expect(banner.getByTestId('call-accept-audio')).toHaveCount(0);
  await banner.getByTestId('call-accept').click();
  await expect.poll(() => did(st, 'call_accept').map((c) => c.body.video)).toEqual([false]);
  await expect(screen(page)).toHaveAttribute('data-phase', 'active');
  await expect(status(page)).toHaveText(/^০০:/);
  await expect(page.getByTestId('call-ask-video')).toHaveCount(0);
  await page.getByTestId('call-hangup').click();
  await open(page, withTest(def('blood', 'my-request-detail'), { features: { calls_video: false } }));
  await expect(page.getByTestId('call-audio').first()).toBeVisible();
  await expect(page.getByTestId('call-video')).toHaveCount(0);
});
