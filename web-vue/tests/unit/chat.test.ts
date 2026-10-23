// অ্যাপে মেসেজ (src/chat): সার্ভারের উত্তর পড়া, "না"-র কারণ বাংলায়, পাঠানো (clientId একই রেখে আবার), বন্ধ কথোপকথন,
// SSE-র chat_message / chat_read, পড়া জানানো (upTo = দেখা সবচেয়ে নতুন মেসেজের at), ১০ মিনিটে মোছা, ছবি ছোট করার ধাপ, মেসেজের চেহারা।
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const { call, ServerError } = vi.hoisted(() => {
  class ServerError extends Error {
    constructor(message: string, public status: number, public code?: string) {
      super(message);
    }
  }
  return { call: vi.fn(), ServerError };
});
vi.mock('@/lib/api', () => ({
  call: (...a: unknown[]) => call(...a),
  errorText: (e: unknown) => (e instanceof Error ? e.message : 'x'),
  ServerError,
}));
vi.mock('@/lib/firebase', () => ({ auth: { currentUser: null } }));

import { parseMessage, parseThread, refusalOf, chatMessages, chatOpen } from '@/chat/api';
import { handleEvent } from '@/calls/events';
import {
  UNSEND_MS, canUnsend, chat, handleChatEvent, loadOlder, markRead, mergeMessages, primeOpen, resetChat, retry, send, startChat, stopChat, timing, unsend,
} from '@/chat/store';
import { nextShrink } from '@/chat/photo';
import { lastText, refusalText } from '@/chat/text';
import { chatCtxOf } from '@/chat/open';
import MessageBubble from '@/chat/MessageBubble.vue';

const actions = (name: string) => call.mock.calls.filter((c) => c[0] === name).map((c) => c[1]);
const msg = (o: Record<string, unknown>) => parseMessage({ threadId: 't1', ...o });

beforeEach(() => {
  localStorage.setItem('lf.lang', 'bn');
  call.mockReset();
  resetChat();
  chat.me = 'me';
});
afterEach(() => {
  vi.useRealTimers();
  resetChat();
  delete (window as any).__lfTest;
});

describe('সার্ভারের উত্তর', () => {
  it('মেসেজ: লেখা, ছবি, লোকেশন, পড়া; মুছে ফেলা হলে শুধু unsent', () => {
    expect(parseMessage({ id: 'a', threadId: 't', from: 'u', text: 'হাই', at: 5, readAt: 9 })).toEqual({ id: 'a', threadId: 't', from: 'u', text: 'হাই', at: 5, readAt: 9 });
    expect(parseMessage({ id: 'b', from: 'u', photoThumb: 'AAA', photoUrl: 'https://x/y.jpg', at: 1 }, 't9')).toMatchObject({ threadId: 't9', photoThumb: 'AAA', photoUrl: 'https://x/y.jpg', readAt: null });
    expect(parseMessage({ id: 'c', from: 'u', location: { lat: 24.7, lng: '90.4' }, at: 1 }).location).toEqual({ lat: 24.7, lng: 90.4 });
    expect(parseMessage({ id: 'c', from: 'u', location: { lat: 0, lng: 0 }, at: 1 }).location).toBeUndefined();
    const u = parseMessage({ id: 'd', from: 'u', text: 'গোপন', photoUrl: 'x', unsent: true, at: 1 });
    expect(u.unsent).toBe(true);
    expect(u.text).toBeUndefined();
    expect(u.photoUrl).toBeUndefined();
  });
  it('থ্রেড: প্রসঙ্গ, শেষ মেসেজ, না পড়া; অজানা প্রসঙ্গ null', () => {
    const t = parseThread({ threadId: 't1', ctx: 'lab_order', refId: 'l1', peer: { uid: 'p', name: 'ল্যাব' }, last: { text: '📷', at: 3, mine: true }, unread: 4, canSend: false });
    expect(t).toMatchObject({ ctx: 'lab_order', canSend: false, unread: 4, last: { text: '📷', at: 3, mine: true } });
    expect(parseThread({ threadId: 'x', ctx: 'weird' }).ctx).toBeNull();
    expect(lastText('📷')).toBe('📷 ছবি');
    expect(lastText('📍')).toBe('📍 লোকেশন');
  });
  it('chat_messages: পুরোনো → নতুন, before = পরের cursor (null = শেষ); সাথে peer/canSend', async () => {
    call.mockResolvedValueOnce({ messages: [{ id: 'a', from: 'u', at: 1 }], before: 'cur1', canSend: false, peer: { uid: 'p', name: 'রিনা' }, ctx: 'blood', refId: 'r' });
    const r = await chatMessages('t1');
    expect(call).toHaveBeenCalledWith('chat_messages', { threadId: 't1' });
    expect(r.before).toBe('cur1');
    expect(r.messages[0].threadId).toBe('t1');
    expect(r.info).toEqual({ peer: { uid: 'p', name: 'রিনা' }, canSend: false, ctx: 'blood', refId: 'r' });
    call.mockResolvedValueOnce({ messages: [], before: null });
    expect((await chatMessages('t1', 'cur1')).before).toBeNull();
    expect(call).toHaveBeenLastCalledWith('chat_messages', { threadId: 't1', before: 'cur1' });
  });
  it('chat_open: peerUid না থাকলে পাঠায় না', async () => {
    call.mockResolvedValueOnce({ threadId: 't5', peer: { uid: 'p', name: 'ক' }, canSend: true, messages: [], before: null });
    const r = await chatOpen({ ctx: 'ambulance', refId: 'a1' });
    expect(call).toHaveBeenCalledWith('chat_open', { ctx: 'ambulance', refId: 'a1' });
    expect(r).toMatchObject({ threadId: 't5', ctx: 'ambulance', refId: 'a1', canSend: true });
  });
  it('"না"-র কারণ আর বাংলা লেখা', () => {
    expect(refusalOf(new ServerError('x', 429, 'chat_rate'))).toBe('rate');
    expect(refusalOf(new ServerError('x', 403, 'chat_not_allowed'))).toBe('not_allowed');
    expect(refusalOf(new ServerError('x', 403, 'call_blocked'))).toBe('blocked');
    expect(refusalOf(new ServerError('x', 409, 'chat_closed'))).toBe('closed');
    expect(refusalOf(new ServerError('x', 0))).toBe('net');
    expect(refusalOf(new Error('x'))).toBe('other');
    expect(refusalText('rate')).toBe('খুব দ্রুত পাঠাচ্ছেন — এক মিনিট পরে আবার চেষ্টা করুন।');
    expect(refusalText('closed')).toBe('এই কথোপকথন বন্ধ — এখন শুধু পড়া যায়।');
    expect(refusalText('blocked')).toBe('আপনি এঁকে ব্লক করেছেন — মেসেজ যাবে না।');
    expect(refusalText('not_allowed')).toBe('এখন অ্যাপে এঁকে মেসেজ করা যায় না।');
    // সার্ভারের ইংরেজি ভুল দেখানো হয় না
    expect(refusalText('other', 'Internal error')).toBe('পাঠানো যায়নি — আবার চেষ্টা করুন।');
  });
  it('কলের প্রসঙ্গ → মেসেজের প্রসঙ্গ', () => {
    expect(chatCtxOf('blood')).toBe('blood');
    expect(chatCtxOf('order')).toBe('rx_order');
    expect(chatCtxOf('order', 'lab')).toBe('lab_order');
    expect(chatCtxOf('nope')).toBeNull();
  });
});

describe('পাঠানো', () => {
  it('আগে "যাচ্ছে…" দেখায়, তারপর সার্ভারের মেসেজ; থ্রেডের শেষ মেসেজ বদলায়', async () => {
    chat.threads = [parseThread({ threadId: 't0', last: null }), parseThread({ threadId: 't1', last: null })];
    let done!: (v: unknown) => void;
    call.mockReturnValueOnce(new Promise((r) => (done = r)));
    const p = send('t1', { text: 'কেমন আছেন' }, 'cid-1');
    expect(chat.msgs.t1).toHaveLength(1);
    expect(chat.msgs.t1[0]).toMatchObject({ id: 'local:cid-1', status: 'sending', from: 'me', text: 'কেমন আছেন' });
    expect(actions('chat_send')[0]).toEqual({ threadId: 't1', clientId: 'cid-1', text: 'কেমন আছেন' });
    done({ message: { id: 'srv1', threadId: 't1', from: 'me', text: 'কেমন আছেন', at: 100 } });
    expect(await p).toBe(true);
    expect(chat.msgs.t1.map((m) => m.id)).toEqual(['srv1']);
    expect(chat.threads[0]).toMatchObject({ threadId: 't1', last: { text: 'কেমন আছেন', mine: true } });
  });
  it('না গেলে "যায়নি" + বাংলা কারণ; আবার চেষ্টায় একই clientId (সার্ভারে একবারই)', async () => {
    call.mockRejectedValueOnce(new ServerError('Too many', 429, 'chat_rate'));
    expect(await send('t1', { text: 'আবার' }, 'cid-2')).toBe(false);
    const failed = chat.msgs.t1[0];
    expect(failed).toMatchObject({ status: 'failed', error: 'খুব দ্রুত পাঠাচ্ছেন — এক মিনিট পরে আবার চেষ্টা করুন।' });
    call.mockResolvedValueOnce({ message: { id: 'srv2', from: 'me', text: 'আবার', at: 200 } });
    expect(await retry('t1', failed)).toBe(true);
    expect(actions('chat_send').map((b) => b.clientId)).toEqual(['cid-2', 'cid-2']);
    expect(chat.msgs.t1.map((m) => m.id)).toEqual(['srv2']);
  });
  it('৪০৯ chat_closed → কথোপকথন শুধু পড়া যায়; ৪০৩ call_blocked → ব্লক', async () => {
    primeOpen({ threadId: 't1', ctx: 'blood', refId: 'r', peer: { uid: 'p', name: 'ক' }, canSend: true, messages: [], before: null });
    call.mockRejectedValueOnce(new ServerError('closed', 409, 'chat_closed'));
    await send('t1', { text: 'x' });
    expect(chat.info.t1).toMatchObject({ canSend: false, closed: 'closed' });
    call.mockRejectedValueOnce(new ServerError('b', 403, 'call_blocked'));
    await send('t1', { text: 'y' });
    expect(chat.info.t1.closed).toBe('blocked');
  });
  it('পোলে সার্ভারের মেসেজ আগে এলেও দুবার দেখায় না', async () => {
    let done!: (v: unknown) => void;
    call.mockReturnValueOnce(new Promise((r) => (done = r)));
    const p = send('t1', { text: 'এক' }, 'cid-3');
    mergeMessages('t1', [msg({ id: 'srv3', from: 'me', text: 'এক', at: 50 })]);
    done({ message: { id: 'srv3', from: 'me', text: 'এক', at: 50 } });
    await p;
    expect(chat.msgs.t1.map((m) => m.id)).toEqual(['srv3']);
  });
});

describe('মোছা, পড়া, SSE', () => {
  it('নিজের মেসেজ ১০ মিনিটের মধ্যে মোছা যায়; মুছলে লেখা চলে যায়', async () => {
    const now = 1_000_000_000;
    const mine = msg({ id: 'a', from: 'me', text: 'ভুল', at: now - UNSEND_MS + 1000 });
    expect(canUnsend(mine, now)).toBe(true);
    expect(canUnsend({ ...mine, at: now - UNSEND_MS - 1 }, now)).toBe(false);
    expect(canUnsend({ ...mine, from: 'p' }, now)).toBe(false);
    mergeMessages('t1', [mine]);
    call.mockResolvedValueOnce({ ok: true });
    await unsend('t1', mine);
    expect(actions('chat_unsend')).toEqual([{ messageId: 'a' }]);
    expect(chat.msgs.t1[0]).toMatchObject({ id: 'a', unsent: true });
    expect(chat.msgs.t1[0].text).toBeUndefined();
  });
  it('পড়া জানানো: upTo = ওপাশের সবচেয়ে নতুন মেসেজের at, একবারই; থ্রেড আর মোট না পড়া কমে', async () => {
    chat.threads = [parseThread({ threadId: 't1', unread: 2 })];
    chat.unread = 5;
    mergeMessages('t1', [msg({ id: 'a', from: 'p', at: 10 }), msg({ id: 'b', from: 'p', at: 20 }), msg({ id: 'c', from: 'me', at: 30 })]);
    call.mockResolvedValue({ ok: true, total: 3 });
    await markRead('t1');
    expect(actions('chat_read')).toEqual([{ threadId: 't1', upTo: 20 }]);
    expect(chat.threads[0].unread).toBe(0);
    await markRead('t1');
    expect(actions('chat_read')).toHaveLength(1);
  });
  it('SSE chat_read (ওপাশ পড়েছেন) → আমার মেসেজে ✓✓; আমার আরেক ডিভাইসে পড়া → থ্রেডের না পড়া ০', () => {
    mergeMessages('t1', [msg({ id: 'a', from: 'me', at: 10 }), msg({ id: 'b', from: 'me', at: 30 }), msg({ id: 'c', from: 'p', at: 20 })]);
    handleChatEvent('chat_read', { threadId: 't1', upTo: 25, by: 'p' });
    expect(chat.msgs.t1.find((m) => m.id === 'a')!.readAt).toBe(25);
    expect(chat.msgs.t1.find((m) => m.id === 'b')!.readAt).toBeNull();
    chat.threads = [parseThread({ threadId: 't1', unread: 3 })];
    call.mockResolvedValue({ total: 0 });
    handleChatEvent('chat_read', { threadId: 't1', upTo: 99, by: 'me' });
    expect(chat.threads[0].unread).toBe(0);
    // পরে পুরোনো উত্তর এলেও ✓✓ থাকে
    mergeMessages('t1', [msg({ id: 'a', from: 'me', at: 10, readAt: null })]);
    expect(chat.msgs.t1.find((m) => m.id === 'a')!.readAt).toBe(25);
  });
  it('SSE chat_message (একই স্ট্রিম, calls/events) → খোলা কথোপকথন নতুন করে আনে, ব্যাজ নতুন', async () => {
    vi.useFakeTimers();
    (window as any).__lfTest = { chatTiming: { debounceMs: 10 } };
    chat.openThread = 't1';
    call.mockImplementation(async (a: string) => (a === 'chat_unread' ? { total: 7 } : a === 'chat_messages' ? { messages: [{ id: 'n1', from: 'p', text: 'নতুন', at: 5 }], before: null } : {}));
    handleEvent('chat_message', JSON.stringify({ threadId: 't1', messageId: 'n1', from: 'রিনা', cat: 'messages' }));
    // এখনো চালু হয়নি (startChat ছাড়া কেউ শোনে না)
    await vi.advanceTimersByTimeAsync(50);
    expect(actions('chat_messages')).toHaveLength(0);
    startChat('me');
    await vi.advanceTimersByTimeAsync(0);
    handleEvent('chat_message', JSON.stringify({ threadId: 't1', messageId: 'n1', from: 'রিনা', cat: 'messages' }));
    await vi.advanceTimersByTimeAsync(50);
    expect(actions('chat_messages')).toEqual([{ threadId: 't1' }]);
    expect(chat.msgs.t1.map((m) => m.text)).toEqual(['নতুন']);
    expect(chat.unread).toBe(7);
    stopChat();
  });
  it('SSE না চললে: খোলা কথোপকথন আর না পড়া সংখ্যা নিজে থেকে দেখে', async () => {
    vi.useFakeTimers();
    call.mockImplementation(async (a: string) => (a === 'chat_unread' ? { total: 1 } : { messages: [], before: null }));
    (window as any).__lfTest = { chatTiming: { pollMs: 1000, threadPollMs: 200 } };
    startChat('me');
    expect(timing.threadPollMs).toBe(200);
    chat.openThread = 't1';
    call.mockClear();
    await vi.advanceTimersByTimeAsync(5000);
    expect(actions('chat_messages').length).toBeGreaterThan(3);
    expect(actions('chat_unread').length).toBeGreaterThan(0);
    stopChat();
  });
  it('আগের মেসেজ: cursor ফেরত দেয়, শেষে আর চায় না', async () => {
    primeOpen({ threadId: 't1', ctx: 'blood', refId: 'r', peer: { uid: 'p', name: 'ক' }, canSend: true, messages: [msg({ id: 'z', from: 'p', at: 100 })], before: 'c1' });
    call.mockResolvedValueOnce({ messages: [{ id: 'y', from: 'p', at: 50 }], before: null });
    await loadOlder('t1');
    expect(actions('chat_messages')).toEqual([{ threadId: 't1', before: 'c1' }]);
    expect(chat.msgs.t1.map((m) => m.id)).toEqual(['y', 'z']);
    await loadOlder('t1');
    expect(actions('chat_messages')).toHaveLength(1);
  });
});

describe('ছবি আর চেহারা', () => {
  it('ছবি ছোট করার ধাপ: আগে মান, তারপর মাপ, শেষে থামে', () => {
    let s: ReturnType<typeof nextShrink> = { maxSide: 1600, quality: 0.8 };
    const steps = [];
    while (s) {
      steps.push(s);
      s = nextShrink(s);
    }
    expect(steps[1]).toEqual({ maxSide: 1600, quality: 0.7 });
    expect(steps.at(-1)!.maxSide).toBeLessThanOrEqual(1000);
    expect(steps.length).toBeLessThan(20);
  });
  it('মেসেজ: ✓ গেছে / ✓✓ দেখেছেন / যাচ্ছে… / যায়নি / মুছে ফেলা', () => {
    const at = Date.now();
    const w = (m: Record<string, unknown>, mine = true) => mount(MessageBubble, { props: { m: msg({ id: 'a', from: mine ? 'me' : 'p', at, ...m }), mine, selected: false, now: at, canReport: true } });
    expect(w({ text: 'হাই' }).find('[data-testid="msg-tick"]').attributes('aria-label')).toBe('গেছে');
    expect(w({ text: 'হাই', readAt: at }).find('[data-testid="msg-tick"]').attributes('aria-label')).toBe('দেখেছেন');
    expect(w({ text: 'হাই' }, false).find('[data-testid="msg-tick"]').exists()).toBe(false);
    const sending = mount(MessageBubble, { props: { m: { ...msg({ id: 'l', from: 'me', at, text: 'x' }), status: 'sending' as const }, mine: true, selected: false, now: at, canReport: false } });
    expect(sending.text()).toContain('যাচ্ছে…');
    const failed = mount(MessageBubble, { props: { m: { ...msg({ id: 'l', from: 'me', at, text: 'x' }), status: 'failed' as const, error: 'কারণ' }, mine: true, selected: false, now: at, canReport: false } });
    expect(failed.text()).toContain('যায়নি');
    expect(failed.text()).toContain('আবার চেষ্টা');
    expect(w({ unsent: true }).text()).toContain('মেসেজ মুছে ফেলা হয়েছে');
  });
  it('বাছলে কাজ: নিজের নতুন মেসেজে "দুজনের জন্যই মুছুন", ওপাশের মেসেজে "রিপোর্ট"', async () => {
    const at = Date.now();
    const own = mount(MessageBubble, { props: { m: msg({ id: 'a', from: 'me', at, text: 'x' }), mine: true, selected: true, now: at, canReport: true } });
    expect(own.find('[data-testid="msg-unsend"]').exists()).toBe(true);
    expect(own.find('[data-testid="msg-report"]').exists()).toBe(false);
    const old = mount(MessageBubble, { props: { m: msg({ id: 'a', from: 'me', at: at - UNSEND_MS - 1, text: 'x' }), mine: true, selected: true, now: at, canReport: true } });
    expect(old.find('[data-testid="msg-unsend"]').exists()).toBe(false);
    expect(old.text()).toContain('শুধু ১০ মিনিটের মধ্যে মোছা যায়');
    const theirs = mount(MessageBubble, { props: { m: msg({ id: 'b', from: 'p', at, text: 'x' }), mine: false, selected: true, now: at, canReport: true } });
    await theirs.find('[data-testid="msg-report"]').trigger('click');
    expect(theirs.emitted('report')).toHaveLength(1);
  });
});
