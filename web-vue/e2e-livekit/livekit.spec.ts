// আসল LiveKit (livekit-server --dev, চাবি devkey/secret) — দুই ব্রাউজার-প্রসঙ্গ (দুইজন মানুষ), নকল ক্যামেরা/মাইক:
// ১. অনলাইন অ্যাপয়েন্টমেন্ট: রোগী অপেক্ষার ঘরে → ডাক্তার যুক্ত → দুজনেই সময় দেখেন, অন্যজনের ভিডিও আসে → ডাক্তার বেরোলে রোগী আবার অপেক্ষায়।
// ২. রক্তের অডিও কল: কল যায় → ওপাশে ব্যানার (SSE) → ধরেন → দুজনেই যুক্ত, অন্যজনের শব্দ আসে, ভিডিও নয় → রেখে দিলে ওপাশেও শেষ।
// টোকেন এখানেই বানানো (সার্ভারের মতো HS256, ১০ মিনিট)।
import crypto from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { def, type PageDef } from '../e2e/helpers';

test.skip(!process.env.LIVEKIT_BIN, 'LIVEKIT_BIN নেই — লোকাল livekit-server ছাড়া এই পরীক্ষা চলে না');

const LK_URL = 'ws://127.0.0.1:7880';
const b64 = (o: unknown) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
function lkToken(identity: string, room: string, video: boolean) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64({ alg: 'HS256', typ: 'JWT' });
  const body = b64({
    iss: 'devkey', sub: identity, name: identity, nbf: now - 10, exp: now + 600,
    video: { room, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: false, canPublishSources: video ? ['camera', 'microphone'] : ['microphone'] },
  });
  const sig = crypto.createHmac('sha256', 'secret').update(`${head}.${body}`).digest('base64url');
  return `${head}.${body}.${sig}`;
}

type H = (body: any) => unknown;
async function api(page: Page, handlers: Record<string, H>) {
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    const h = handlers[body.action];
    if (!h) return route.continue();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify((await h(body)) ?? { ok: true }) });
  });
}
async function person(browser: Browser, base: PageDef, uid: string, name: string): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log(`[${uid}] pageerror`, e.message));
  await page.addInitScript((t: any) => {
    (window as any).__lfTest = t;
    try {
      localStorage.setItem('lf.lang', 'bn');
    } catch {}
  }, { ...(base.test || {}), user: { uid, email: `${uid}@example.com` }, profile: { ...((base.test as any)?.profile || {}), name }, callTiming: { activePollMs: 2000 } });
  return { ctx, page };
}
const status = (p: Page) => p.getByTestId('call-status');
const remoteVideoPlaying = (p: Page) =>
  p.evaluate(() => {
    const v = document.querySelector<HTMLVideoElement>('[data-testid="call-remote-video"]');
    return !!v && v.videoWidth > 0 && !v.paused;
  });

test('অনলাইন অ্যাপয়েন্টমেন্ট: রোগী আর ডাক্তার একই ঘরে, দুজনের ভিডিও', async ({ browser }) => {
  const base = def('appointment', 'my');
  const room = `c_lk${Date.now()}`;
  const pat = await person(browser, base, 'pat1', 'রোগী');
  const doc = await person(browser, base, 'doc1', 'ডাক্তার');
  for (const [p, uid] of [[pat.page, 'pat1'], [doc.page, 'doc1']] as const) {
    await api(p, {
      appt_join: () => ({ ok: true, callId: room.slice(2), room, url: LK_URL, token: lkToken(uid, room, true), video: true }),
      call_status: () => ({ ok: true, state: 'accepted', video: true, url: LK_URL, room, token: lkToken(uid, room, true) }),
      call_end: () => ({ ok: true, state: 'ended' }),
    });
  }
  await pat.page.goto('/appointments/a1/call?name=ডা. মাহমুদা', { waitUntil: 'domcontentloaded' });
  await pat.page.getByTestId('appt-join').click();
  await expect(status(pat.page)).toHaveText('ডাক্তারের অপেক্ষায়…');

  await doc.page.goto('/appointments/a1/call?role=provider&name=রোগী', { waitUntil: 'domcontentloaded' });
  await doc.page.getByTestId('appt-join').click();
  await expect(status(doc.page)).toHaveText(/^০০:/);
  await expect(status(pat.page)).toHaveText(/^০০:/);
  await expect.poll(() => remoteVideoPlaying(pat.page), { timeout: 20_000 }).toBe(true);
  await expect.poll(() => remoteVideoPlaying(doc.page), { timeout: 20_000 }).toBe(true);
  await expect(pat.page.getByTestId('call-local-video')).toHaveCount(1);

  // ডাক্তার বেরোলেন: রোগী আবার অপেক্ষার ঘরে
  await doc.page.getByTestId('call-hangup').click();
  await expect(status(pat.page)).toHaveText('ডাক্তারের অপেক্ষায়…', { timeout: 30_000 });
  await pat.ctx.close();
  await doc.ctx.close();
});

test('রক্তের অডিও কল: কল যায়, ওপাশে ব্যানার, ধরলে দুজনেই যুক্ত (শব্দ আসে, ভিডিও নয়), রেখে দিলে দুপাশেই শেষ', async ({ browser }) => {
  const callId = `lk${Date.now()}`;
  const room = `c_${callId}`;
  let state = 'ringing';
  const caller = await person(browser, def('blood', 'my-request-detail'), 'req1', 'রক্তপ্রার্থী');
  const callee = await person(browser, def('account', 'profile-nobiz'), 'don1', 'ডোনার');
  await api(caller.page, {
    contact_can: (b) => ({ ok: true, items: b.items.map((i: any) => ({ ...i, peerUid: i.peerUid || 'don1', canCall: true, canVideo: true, canMessage: false })) }),
    call_start: () => ({ ok: true, callId, room, url: LK_URL, token: lkToken('req1', room, false), video: false, peer: { uid: 'don1', name: 'ডোনার' } }),
    call_status: () => ({ ok: true, state, video: false, ...(state === 'accepted' ? { url: LK_URL, room, token: lkToken('req1', room, false) } : {}) }),
    call_end: () => ((state = 'ended'), { ok: true, state }),
  });
  await api(callee.page, {
    call_accept: () => ((state = 'accepted'), { ok: true, callId, room, url: LK_URL, token: lkToken('don1', room, false), video: false, peer: { uid: 'req1', name: 'রক্তপ্রার্থী' } }),
    call_status: () => ({ ok: true, state, video: false, ...(state === 'accepted' ? { url: LK_URL, room, token: lkToken('don1', room, false) } : {}) }),
    call_end: () => ((state = 'ended'), { ok: true, state }),
  });
  // ওপাশের SSE: কল করার পরে তবেই ঘটনা
  let did = false;
  let rang = false;
  await callee.page.route('**/api/events', async (route) => {
    if (!rang && did && state === 'ringing') {
      rang = true;
      return route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: `event: call_incoming\ndata: ${JSON.stringify({ callId, video: false, ctx: 'blood', refId: 'm1', caller: { uid: 'req1', name: 'রক্তপ্রার্থী' } })}\n\n` });
    }
    return route.fulfill({ status: 503, body: '' });
  });
  await caller.page.goto('/my-requests/m1', { waitUntil: 'domcontentloaded' });
  await callee.page.goto('/profile', { waitUntil: 'domcontentloaded' });
  await caller.page.getByTestId('call-audio').first().click();
  await expect(status(caller.page)).toHaveText('রিং হচ্ছে');
  did = true;
  // ওপাশ SSE আবার যুক্ত হলে ঘটনা পায় (প্রথমবার 503 ছিল — পিছিয়ে আবার চেষ্টা)
  await expect(callee.page.getByTestId('call-incoming')).toContainText('রক্তপ্রার্থী', { timeout: 20_000 });
  await callee.page.getByTestId('call-accept').click();
  await expect(status(callee.page)).toHaveText(/^০০:/);
  await expect(status(caller.page)).toHaveText(/^০০:/);
  for (const p of [caller.page, callee.page]) {
    await expect.poll(() => p.evaluate(() => document.querySelectorAll('audio[data-call-audio]').length), { timeout: 20_000 }).toBeGreaterThan(0);
    expect(await remoteVideoPlaying(p)).toBe(false);
  }
  await caller.page.getByTestId('call-hangup').click();
  await expect(status(callee.page)).toContainText(/কল শেষ|উনি কল থেকে বেরিয়ে গেছেন/, { timeout: 30_000 });
  await caller.ctx.close();
  await callee.ctx.close();
});
