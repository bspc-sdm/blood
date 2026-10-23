// অ্যাপে মেসেজ (নকল API + নকল SSE): তালিকা আর না পড়া ব্যাজ → খোলা → পড়া জানানো → লেখা পাঠানো (clientId);
// SSE-তে নতুন মেসেজ আর ✓✓; SSE না চললে নিজে থেকে দেখা; না গেলে বাংলা কারণ আর একই clientId-তে আবার;
// ছবি (এখানেই ছোট করা JPEG), লোকেশন, মোছা, রিপোর্ট, ব্লক, বন্ধ কথোপকথন; বড় পর্দায় দুই ভাগ।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, noSideScroll, watchErrors, type PageDef } from './helpers';

const MIN = 60_000;
interface Msg { id: string; threadId: string; from: string; text?: string; photoThumb?: string; location?: { lat: number; lng: number }; at: number; readAt?: number | null; unsent?: boolean }
interface Server {
  calls: { action: string; body: any }[];
  msgs: Record<string, Msg[]>;
  threads: any[];
  unread: number;
  fail: Record<string, { status: number; code: string; error: string }[]>;
}
const did = (st: Server, action: string) => st.calls.filter((c) => c.action === action).map((c) => c.body);

/** মেসেজের নকল সার্ভার (অবস্থা রাখে) — বাকি কাজ test/fixtures-এ */
async function fakeChat(page: Page): Promise<Server> {
  const now = Date.now();
  const st: Server = {
    calls: [],
    unread: 3,
    fail: {},
    msgs: {
      t1: [
        { id: 'm1', threadId: 't1', from: 'u1', text: 'আপনি কি আজ আসতে পারবেন?', at: now - 30 * MIN, readAt: now - 25 * MIN },
        { id: 'm2', threadId: 't1', from: 'd1', text: 'জি, ৪টার মধ্যে আসব।', at: now - 20 * MIN },
        { id: 'm3', threadId: 't1', from: 'd1', text: 'রক্তের গ্রুপ B+ তো?', at: now - 4 * MIN },
      ],
      t2: [{ id: 'p1', threadId: 't2', from: 'ph1', text: 'আপনার ওষুধ পাঠানো হয়েছে।', at: now - 3 * 1440 * MIN }],
      t3: [{ id: 'a1', threadId: 't3', from: 'dr1', text: 'আমি রাস্তায় আছি', at: now - 60 * MIN }],
    },
    threads: [
      { threadId: 't1', ctx: 'blood', refId: 'm1', peer: { uid: 'd1', name: 'সুমাইয়া আক্তার' }, unread: 2, canSend: true },
      { threadId: 't3', ctx: 'ambulance', refId: 'ar1', peer: { uid: 'dr1', name: 'জনসেবা অ্যাম্বুলেন্স' }, unread: 1, canSend: true },
      { threadId: 't2', ctx: 'rx_order', refId: 'o1', peer: { uid: 'ph1', name: 'লাজ ফার্মা' }, unread: 0, canSend: false },
    ],
  };
  const info = (id: string) => {
    const t = st.threads.find((x) => x.threadId === id);
    return { threadId: id, ctx: t.ctx, refId: t.refId, peer: t.peer, canSend: t.canSend };
  };
  const last = (id: string) => {
    const m = st.msgs[id].at(-1)!;
    return { text: m.text ?? (m.photoThumb ? '📷' : '📍'), at: m.at, mine: m.from === 'u1' };
  };
  await page.route('**/api/community', async (route: Route) => {
    const body = route.request().postDataJSON() || {};
    const a = String(body.action);
    if (!a.startsWith('chat_') && a !== 'call_block') return route.continue();
    st.calls.push({ action: a, body });
    const reply = (json: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, ...(json as object) }) });
    const f = st.fail[a]?.shift();
    if (f) return route.fulfill({ status: f.status, contentType: 'application/json', body: JSON.stringify({ error: f.error, code: f.code }) });
    switch (a) {
      case 'chat_unread': return reply({ total: st.unread });
      case 'chat_threads': return reply({ items: st.threads.map((t) => ({ ...t, last: last(t.threadId) })), next: null });
      case 'chat_messages': return reply({ ...info(body.threadId), messages: st.msgs[body.threadId] ?? [], before: null });
      case 'chat_open': return reply({ ...info(body.threadId || 't1'), messages: st.msgs[body.threadId || 't1'], before: null });
      case 'chat_send': {
        const list = st.msgs[body.threadId];
        let m = list.find((x) => (x as any).clientId === body.clientId);
        if (!m) {
          m = { id: `s${list.length + 1}`, threadId: body.threadId, from: 'u1', at: Date.now(), readAt: null, ...(body.text ? { text: body.text } : {}), ...(body.photo ? { photoThumb: body.photo } : {}), ...(body.location ? { location: body.location } : {}) };
          (m as any).clientId = body.clientId;
          list.push(m);
        }
        return reply({ message: m });
      }
      case 'chat_read': {
        const t = st.threads.find((x) => x.threadId === body.threadId);
        st.unread = Math.max(0, st.unread - (t?.unread ?? 0));
        if (t) t.unread = 0;
        return reply({});
      }
      case 'chat_unsend': {
        for (const l of Object.values(st.msgs)) for (const m of l) if (m.id === body.messageId) Object.assign(m, { unsent: true, text: undefined });
        return reply({});
      }
      default: return reply({});
    }
  });
  return st;
}

/** SSE: test ছাড়া পর্যন্ত স্ট্রিম ঝুলে থাকে; push() দিলে ঘটনাগুলো যায় (তারপর স্ট্রিম শেষ, পরের বার শুধু ping) */
async function fakeSse(page: Page) {
  const queue: { event: string; data: unknown }[][] = [];
  const waiters: ((e: { event: string; data: unknown }[]) => void)[] = [];
  await page.route('**/api/events', async (route) => {
    const evs = queue.shift() ?? (await new Promise<{ event: string; data: unknown }[]>((r) => waiters.push(r)));
    await route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: evs.map((e) => `event: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`).join('') }).catch(() => {});
  });
  return {
    push(evs: { event: string; data: unknown }[]) {
      const w = waiters.shift();
      if (w) w(evs);
      else queue.push(evs);
    },
  };
}
const sseDown = (page: Page) => page.route('**/api/events', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"no sse"}' }));

async function openLive(page: Page, p: PageDef, path: string, extra: Record<string, unknown> = {}) {
  await page.addInitScript((t: any) => {
    (window as any).__lfTest = t;
    try {
      localStorage.clear();
      localStorage.setItem('lf.lang', 'bn');
    } catch {}
  }, { ...(p.test || {}), ...extra });
  await page.goto(path, { waitUntil: 'domcontentloaded' });
}
const base = () => def('chat', 'messages');
const bubbles = (page: Page) => page.getByTestId('msg');
const compose = (page: Page) => page.getByTestId('compose-text');

test('তালিকা: না পড়া ব্যাজ → খোলা → পড়া জানানো → লেখা পাঠানো (✓, clientId)', async ({ page }) => {
  const errs = watchErrors(page);
  const st = await fakeChat(page);
  await sseDown(page);
  await openLive(page, base(), '/messages');
  await expect(page.getByTestId('top-chat')).toContainText('৩');
  const rows = page.getByTestId('thread-row');
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toContainText('সুমাইয়া আক্তার');
  await expect(rows.first()).toContainText('রক্তের গ্রুপ B+ তো?');
  await expect(rows.first().getByTestId('thread-unread')).toHaveText('২');
  await expect(rows.nth(2)).toContainText('বন্ধ');
  await noSideScroll(page);

  await rows.first().click();
  await expect(page).toHaveURL(/\/messages\/t1$/);
  await expect(page.getByTestId('thread-peer')).toHaveText('সুমাইয়া আক্তার');
  await expect(bubbles(page)).toHaveCount(3);
  // মোবাইলে পুরো পাতা: নিচের ট্যাব লুকানো
  await expect(page.locator('.bottom-nav')).toBeHidden();
  await expect.poll(() => did(st, 'chat_read')).toEqual([{ threadId: 't1', upTo: st.msgs.t1[2].at, lang: 'bn' }]);
  // নিজের পুরোনো মেসেজে ✓✓ (দেখেছেন)
  await expect(bubbles(page).first().getByTestId('msg-tick')).toHaveAttribute('aria-label', 'দেখেছেন');

  await compose(page).fill('হ্যাঁ, B+। হাসপাতালের গেটে থাকব।');
  await page.getByTestId('compose-send').click();
  await expect(compose(page)).toHaveValue('');
  const sent = bubbles(page).last();
  await expect(sent).toContainText('হ্যাঁ, B+। হাসপাতালের গেটে থাকব।');
  await expect(sent.getByTestId('msg-tick')).toHaveAttribute('aria-label', 'গেছে');
  const b = did(st, 'chat_send')[0];
  expect(b).toMatchObject({ threadId: 't1', text: 'হ্যাঁ, B+। হাসপাতালের গেটে থাকব।' });
  expect(b.clientId).toMatch(/^[\w-]{8,}$/);
  await noSideScroll(page);
  expect(errs).toEqual([]);
});

test('SSE: নতুন মেসেজ (chat_message) আর ✓✓ (chat_read) — পাতা না বদলেই', async ({ page }) => {
  const st = await fakeChat(page);
  const sse = await fakeSse(page);
  // পোল খুব দেরিতে, যাতে শুধু SSE-ই আনতে পারে
  await openLive(page, base(), '/messages/t1', { chatTiming: { pollMs: 600_000, threadPollMs: 600_000, livePollMs: 600_000 } });
  await expect(bubbles(page)).toHaveCount(3);
  await compose(page).fill('আসছি');
  await page.getByTestId('compose-send').click();
  await expect(bubbles(page).last().getByTestId('msg-tick')).toHaveAttribute('aria-label', 'গেছে');
  const n0 = did(st, 'chat_messages').length;

  st.msgs.t1.push({ id: 'n9', threadId: 't1', from: 'd1', text: 'ঠিক আছে, অপেক্ষা করছি', at: Date.now() });
  sse.push([
    { event: 'chat_message', data: { threadId: 't1', messageId: 'n9', from: 'সুমাইয়া', cat: 'messages' } },
  ]);
  await expect(bubbles(page).last()).toContainText('ঠিক আছে, অপেক্ষা করছি');
  expect(did(st, 'chat_messages').length).toBe(n0 + 1);
  const mine = bubbles(page).filter({ hasText: 'আসছি' });
  await expect(mine.getByTestId('msg-tick')).toHaveAttribute('aria-label', 'গেছে');
  sse.push([{ event: 'chat_read', data: { threadId: 't1', upTo: Date.now(), by: 'd1' } }]);
  await expect(mine.getByTestId('msg-tick')).toHaveAttribute('aria-label', 'দেখেছেন', { timeout: 8000 });
});

test('SSE না চললে: খোলা কথোপকথন নিজে থেকে নতুন মেসেজ আনে, ব্যাজও বদলায়', async ({ page }) => {
  const st = await fakeChat(page);
  await sseDown(page);
  await openLive(page, base(), '/messages/t3', { chatTiming: { pollMs: 700, threadPollMs: 300 } });
  await expect(bubbles(page)).toHaveCount(1);
  st.msgs.t3.push({ id: 'a2', threadId: 't3', from: 'dr1', text: '৫ মিনিটে পৌঁছাব', at: Date.now() });
  await expect(bubbles(page).last()).toContainText('৫ মিনিটে পৌঁছাব');
  st.unread = 9;
  await expect(page.getByTestId('top-chat')).toContainText('৯', { timeout: 5000 });
});

test('না গেলে: বাংলা কারণ, "আবার চেষ্টা" একই clientId; বন্ধ হয়ে গেলে শুধু পড়া যায়', async ({ page }) => {
  const st = await fakeChat(page);
  await sseDown(page);
  st.fail.chat_send = [{ status: 429, code: 'chat_rate', error: 'Too many messages' }];
  await openLive(page, base(), '/messages/t1');
  await compose(page).fill('এক');
  await page.getByTestId('compose-send').click();
  const failed = bubbles(page).last();
  await expect(failed).toHaveAttribute('data-status', 'failed');
  await expect(failed).toContainText('খুব দ্রুত পাঠাচ্ছেন — এক মিনিট পরে আবার চেষ্টা করুন।');
  await failed.getByRole('button', { name: 'আবার চেষ্টা' }).click();
  await expect(bubbles(page).last()).toHaveAttribute('data-status', 'sent');
  const sends = did(st, 'chat_send');
  expect(sends).toHaveLength(2);
  expect(sends[1].clientId).toBe(sends[0].clientId);
  expect(st.msgs.t1.filter((m) => m.text === 'এক')).toHaveLength(1);

  st.fail.chat_send = [{ status: 409, code: 'chat_closed', error: 'closed' }];
  await compose(page).fill('দুই');
  await page.getByTestId('compose-send').click();
  await expect(page.getByTestId('thread-readonly')).toContainText('এই কথোপকথন বন্ধ হয়ে গেছে');
  await expect(compose(page)).toHaveCount(0);
});

test('বন্ধ কথোপকথন (canSend=false): লেখার জায়গা নেই, কারণ দেখায়', async ({ page }) => {
  await fakeChat(page);
  await sseDown(page);
  await openLive(page, base(), '/messages/t2');
  await expect(page.getByTestId('thread-peer')).toHaveText('লাজ ফার্মা');
  await expect(page.getByTestId('thread-readonly')).toContainText('রিকোয়েস্ট বা অর্ডার শেষ, তাই এখন শুধু পড়া যায়। ৯০ দিন পরে ছবিসহ মুছে যাবে।');
  await expect(compose(page)).toHaveCount(0);
});

test('মোছা (১০ মিনিটের মধ্যে), রিপোর্ট আর ব্লক', async ({ page }) => {
  const st = await fakeChat(page);
  await sseDown(page);
  st.msgs.t1.push({ id: 'm9', threadId: 't1', from: 'u1', text: 'ভুল নম্বর দিয়েছি', at: Date.now() - 2 * MIN, readAt: null });
  await openLive(page, base(), '/messages/t1');
  const own = bubbles(page).filter({ hasText: 'ভুল নম্বর দিয়েছি' });
  await own.locator('.chat-bubble').click();
  await own.getByTestId('msg-unsend').click();
  await expect(page.getByRole('dialog')).toContainText('মেসেজটা মুছবেন?');
  await page.getByRole('dialog').getByRole('button', { name: 'দুজনের জন্যই মুছুন' }).click();
  await expect(bubbles(page).filter({ hasText: 'মেসেজ মুছে ফেলা হয়েছে' })).toHaveCount(1);
  expect(did(st, 'chat_unsend')[0]).toMatchObject({ messageId: 'm9' });
  // পুরোনো নিজের মেসেজে মোছা নেই
  await bubbles(page).first().locator('.chat-bubble').click();
  await expect(bubbles(page).first().getByTestId('msg-unsend')).toHaveCount(0);

  const theirs = bubbles(page).filter({ hasText: 'রক্তের গ্রুপ B+ তো?' });
  await theirs.locator('.chat-bubble').click();
  await theirs.getByTestId('msg-report').click();
  const dlg = page.getByTestId('report-dialog');
  await dlg.getByText('ভুয়া বা প্রতারণা').click();
  await dlg.getByRole('textbox').fill('টাকা চেয়েছেন');
  await page.getByTestId('report-send').click();
  await expect(page.getByText('রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।')).toBeVisible();
  expect(did(st, 'chat_report')[0]).toMatchObject({ messageId: 'm3', reason: 'fake', note: 'টাকা চেয়েছেন' });

  await page.getByTestId('thread-block').click();
  await expect(page.getByRole('dialog')).toContainText('সুমাইয়া আক্তার-কে ব্লক করবেন?');
  await page.getByRole('dialog').getByRole('button', { name: 'ব্লক' }).click();
  await expect(page.getByTestId('thread-readonly')).toContainText('আপনি এঁকে ব্লক করেছেন — এই কথোপকথন এখন শুধু পড়া যায়।');
  expect(did(st, 'call_block')[0]).toMatchObject({ peerUid: 'd1' });
});

test('ছবি (এখানেই ছোট করা JPEG, ১ MB-র নিচে) আর লোকেশন (জিজ্ঞেস করে)', async ({ page, context }) => {
  const st = await fakeChat(page);
  await sseDown(page);
  await context.grantPermissions(['geolocation']);
  await context.setGeolocation({ latitude: 24.7471, longitude: 90.4203 });
  await openLive(page, base(), '/messages/t1');
  await expect(bubbles(page)).toHaveCount(3);
  // বড় ছবি (৩০০০×২০০০, এলোমেলো রঙ — সহজে ছোট হয় না) পাতার ভেতরেই বানিয়ে দেওয়া
  const png = await page.evaluate(async () => {
    const c = document.createElement('canvas');
    c.width = 3000;
    c.height = 2000;
    const x = c.getContext('2d')!;
    const img = x.createImageData(3000, 2000);
    for (let i = 0; i < img.data.length; i++) img.data[i] = (i * 2654435761) % 251;
    x.putImageData(img, 0, 0);
    const b: Blob = await new Promise((r) => c.toBlob((v) => r(v!), 'image/png'));
    return Array.from(new Uint8Array(await b.arrayBuffer()));
  });
  await page.getByTestId('compose-file').setInputFiles({ name: 'big.png', mimeType: 'image/png', buffer: Buffer.from(png) });
  await expect.poll(() => did(st, 'chat_send').length, { timeout: 15_000 }).toBe(1);
  const photo = did(st, 'chat_send')[0].photo as string;
  expect(photo.startsWith('/9j/')).toBe(true);
  expect(photo.length).toBeLessThanOrEqual(900 * 1024);
  expect(Buffer.byteLength(photo, 'base64')).toBeLessThan(1024 * 1024);
  await expect(bubbles(page).last().locator('.chat-photo img')).toBeVisible();

  await page.getByTestId('compose-location').click();
  await expect(page.getByRole('dialog')).toContainText('আপনার এখনকার লোকেশন পাঠাবেন?');
  await page.getByRole('dialog').getByRole('button', { name: 'পাঠান' }).click();
  await expect.poll(() => did(st, 'chat_send').length).toBe(2);
  expect(did(st, 'chat_send')[1].location).toEqual({ lat: 24.7471, lng: 90.4203 });
  await expect(bubbles(page).last().getByTestId('msg-location')).toHaveAttribute('href', 'https://www.google.com/maps?q=24.7471,90.4203');
});

test('@desktop বড় পর্দা: বাঁয়ে তালিকা, ডানে কথোপকথন; অন্যটা চাপলে ডান পাশ বদলায়, পাশের মেনুতে ব্যাজ', async ({ page }) => {
  const errs = watchErrors(page);
  await page.setViewportSize({ width: 1440, height: 960 });
  await fakeChat(page);
  await sseDown(page);
  await openLive(page, base(), '/messages');
  await expect(page.locator('.chat-pick')).toContainText('একটা কথোপকথন বেছে নিন');
  await expect(page.getByTestId('nav-chat-badge')).toHaveText('৩');
  await page.getByTestId('thread-row').first().click();
  await expect(page.getByTestId('thread-list')).toBeVisible();
  await expect(page.getByTestId('thread-peer')).toHaveText('সুমাইয়া আক্তার');
  await expect(page.getByTestId('thread-row').first()).toHaveAttribute('aria-current', 'page');
  await page.getByTestId('thread-row').nth(1).click();
  await expect(page.getByTestId('thread-peer')).toHaveText('জনসেবা অ্যাম্বুলেন্স');
  await expect(bubbles(page)).toContainText(['আমি রাস্তায় আছি']);
  const box = (await page.getByTestId('thread-view').boundingBox())!;
  const list = (await page.getByTestId('thread-list').boundingBox())!;
  expect(box.x).toBeGreaterThan(list.x + list.width - 1);
  await noSideScroll(page);
  expect(errs).toEqual([]);
});
