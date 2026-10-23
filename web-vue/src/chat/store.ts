// মেসেজের অবস্থা (পুরো ওয়েবে একটাই): কথোপকথনের তালিকা, প্রতিটা কথোপকথনের মেসেজ, না পড়া সংখ্যা (ব্যাজ)।
// নতুন খবর: SSE (calls/events.ts-এর একই স্ট্রিম) `chat_message` {threadId, messageId, from, cat} আর
// `chat_read` {threadId, upTo, by}। SSE না চললে: ১৫ সেকেন্ড পরপর chat_unread, আর খোলা কথোপকথন ৫ সেকেন্ড পরপর।
// পাঠানো: আগে এখানেই দেখায় ("যাচ্ছে…"), clientId একই রেখে আবার পাঠালেও সার্ভারে একবারই থাকে।
import { reactive } from 'vue';
import { errorText } from '@/lib/api';
import { TEST_MODE } from '@/lib/testmode';
import { sseLive, tapEvents } from '@/calls/events';
import {
  chatBlock, chatMessages, chatOpen, chatRead, chatReport, chatSend, chatThreads, chatUnread, chatUnsend, parseMessage, refusalOf,
  type ChatCtx, type ChatLocation, type ChatMessage, type OpenResult, type ReportReason, type ThreadInfo, type ThreadItem,
} from './api';
import { refusalText } from './text';

export const UNSEND_MS = 10 * 60_000;
export const TEXT_MAX = 1000;
const base = { pollMs: 15_000, threadPollMs: 5_000, livePollMs: 120_000, debounceMs: 250 };
export const timing = { ...base };
function applyTestTiming() {
  const t = TEST_MODE ? (window as any).__lfTest?.chatTiming : null;
  Object.assign(timing, base, t && typeof t === 'object' ? t : {});
}

/** closed = কেন পাঠানো যায় না: আমি ব্লক করেছি / সার্ভার মানা করেছে (ওপাশের ব্লকও এতে) / সম্পর্ক শেষ */
export type Closed = 'blocked' | 'not_allowed' | 'closed';
interface ThreadState extends ThreadInfo { closed?: Closed }
export const chat = reactive({
  me: null as string | null,
  unread: 0,
  threads: [] as ThreadItem[],
  threadsNext: null as string | null,
  threadsLoaded: false,
  threadsLoading: false,
  threadsError: null as string | null,
  info: {} as Record<string, ThreadState>,
  msgs: {} as Record<string, ChatMessage[]>,
  /** আরও পুরোনো পাতার cursor: undefined = এখনো আনা হয়নি, null = আর নেই */
  older: {} as Record<string, string | null | undefined>,
  loading: {} as Record<string, boolean>,
  error: {} as Record<string, string | null>,
  /** এখন যেটা খোলা (পড়া হয়েছে জানাতে, SSE না চললে বারবার দেখতে) */
  openThread: null as string | null,
});

const byAt = (a: ChatMessage, b: ChatMessage) => a.at - b.at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

/** সার্ভারের মেসেজ মেলানো (id ধরে) — এখানকার "যাচ্ছে…" মেসেজ রেখে */
export function mergeMessages(threadId: string, list: ChatMessage[]) {
  const cur = chat.msgs[threadId] ?? [];
  const map = new Map(cur.map((m) => [m.id, m]));
  for (const m of list) {
    const old = map.get(m.id);
    // পড়ার খবর আগেই এলে (SSE) পুরোনো উত্তরে হারায় না
    map.set(m.id, old?.readAt && !m.readAt ? { ...m, readAt: old.readAt } : m);
  }
  chat.msgs[threadId] = [...map.values()].sort(byAt);
}

export function setInfo(i: Partial<ThreadState> & { threadId: string }) {
  const old = chat.info[i.threadId];
  const defaults: ThreadState = { threadId: i.threadId, ctx: null, refId: '', peer: { uid: '', name: '' }, canSend: true };
  chat.info[i.threadId] = Object.assign(defaults, old, i);
  const t = chat.threads.find((x) => x.threadId === i.threadId);
  if (t) {
    if (i.canSend != null) t.canSend = i.canSend;
    if (i.peer?.name) t.peer = i.peer;
  }
}

/** chat_open-এর ফল বসানো (মেসেজ বোতাম থেকে) */
export function primeOpen(r: OpenResult) {
  setInfo({ threadId: r.threadId, ctx: r.ctx, refId: r.refId, peer: r.peer, canSend: r.canSend });
  mergeMessages(r.threadId, r.messages);
  if (chat.older[r.threadId] === undefined) chat.older[r.threadId] = r.before;
}
export async function openThreadFor(ctx: ChatCtx, refId: string, peerUid?: string): Promise<string> {
  const r = await chatOpen({ ctx, refId, peerUid });
  primeOpen(r);
  return r.threadId;
}

// ---------- আনা ----------
const inflight = new Map<string, Promise<void>>();
function once(key: string, f: () => Promise<void>): Promise<void> {
  const p = inflight.get(key);
  if (p) return p;
  const q = f().finally(() => inflight.delete(key));
  inflight.set(key, q);
  return q;
}

/** সবচেয়ে নতুন পাতা — সাথে peer/canSend/ctx/refId (সরাসরি লিংক /messages/:threadId এভাবেই খোলে) */
export function loadLatest(threadId: string): Promise<void> {
  return once(`l:${threadId}`, async () => {
    if (!chat.msgs[threadId]) chat.loading[threadId] = true;
    try {
      const r = await chatMessages(threadId);
      if (Object.keys(r.info).length) setInfo({ threadId, ...r.info });
      mergeMessages(threadId, r.messages);
      if (chat.older[threadId] === undefined) chat.older[threadId] = r.before;
      chat.error[threadId] = null;
    } catch (e) {
      if (!chat.msgs[threadId]?.length) chat.error[threadId] = errorText(e);
    } finally {
      chat.loading[threadId] = false;
    }
  });
}
export function loadOlder(threadId: string): Promise<void> {
  const cursor = chat.older[threadId];
  if (!cursor) return Promise.resolve();
  return once(`o:${threadId}`, async () => {
    const r = await chatMessages(threadId, cursor);
    mergeMessages(threadId, r.messages);
    chat.older[threadId] = r.before;
  });
}

export function loadThreads(more = false): Promise<void> {
  return once(`t:${more}`, async () => {
    chat.threadsLoading = true;
    try {
      const r = await chatThreads(more ? chat.threadsNext : null);
      if (more) {
        const have = new Set(chat.threads.map((t) => t.threadId));
        chat.threads.push(...r.items.filter((t) => !have.has(t.threadId)));
      } else {
        chat.threads = r.items;
      }
      for (const t of r.items) if (!chat.info[t.threadId]) chat.info[t.threadId] = { threadId: t.threadId, ctx: t.ctx, refId: t.refId, peer: t.peer, canSend: t.canSend };
      if (!more || r.items.length) chat.threadsNext = r.next;
      chat.threadsLoaded = true;
      chat.threadsError = null;
    } catch (e) {
      if (!chat.threadsLoaded) chat.threadsError = errorText(e);
      throw e;
    } finally {
      chat.threadsLoading = false;
    }
  });
}

export async function refreshUnread(): Promise<void> {
  try {
    chat.unread = await chatUnread();
  } catch {
    /* পরে আবার */
  }
}

// ---------- পাঠানো ----------
export function newClientId(): string {
  const c = (globalThis as any).crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `c${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
}
interface Payload { text?: string; photo?: string; location?: ChatLocation }
const payloads = new Map<string, Payload>();

/** এখানে আগে দেখিয়ে সার্ভারে পাঠানো; ফেরত: সফল কি না */
export async function send(threadId: string, p: Payload, clientId = newClientId()): Promise<boolean> {
  const id = `local:${clientId}`;
  payloads.set(clientId, p);
  const local: ChatMessage = {
    id, threadId, from: chat.me ?? '', at: Date.now(), clientId, status: 'sending', readAt: null,
    ...(p.text ? { text: p.text } : {}), ...(p.photo ? { photoThumb: p.photo } : {}), ...(p.location ? { location: p.location } : {}),
  };
  const prev = chat.msgs[threadId]?.find((m) => m.id === id);
  if (prev) local.at = prev.at;
  chat.msgs[threadId] = [...(chat.msgs[threadId] ?? []).filter((m) => m.id !== id), local].sort(byAt);
  try {
    const m = await chatSend({ threadId, clientId, ...p });
    chat.msgs[threadId] = (chat.msgs[threadId] ?? []).filter((x) => x.id !== id);
    mergeMessages(threadId, [m.id ? m : { ...local, id: `sent:${clientId}`, status: undefined }]);
    payloads.delete(clientId);
    bumpThread(threadId, m.text ? m.text : m.photoThumb || m.photoUrl ? '📷' : m.location ? '📍' : '', m.at || Date.now(), true);
    return true;
  } catch (e) {
    const r = refusalOf(e);
    if (r === 'closed' || r === 'blocked' || r === 'not_allowed') setInfo({ threadId, canSend: false, closed: r });
    const msg = refusalText(r, e instanceof Error ? e.message : undefined);
    chat.msgs[threadId] = (chat.msgs[threadId] ?? []).map((x) => (x.id === id ? { ...x, status: 'failed' as const, error: msg } : x));
    return false;
  }
}
/** "যায়নি" মেসেজ আবার (একই clientId) */
export function retry(threadId: string, m: ChatMessage): Promise<boolean> {
  const p = m.clientId ? payloads.get(m.clientId) : null;
  if (!m.clientId || !p) return Promise.resolve(false);
  return send(threadId, p, m.clientId);
}
export function discard(threadId: string, m: ChatMessage) {
  chat.msgs[threadId] = (chat.msgs[threadId] ?? []).filter((x) => x.id !== m.id);
  if (m.clientId) payloads.delete(m.clientId);
}

function bumpThread(threadId: string, text: string, at: number, mine: boolean) {
  const i = chat.threads.findIndex((t) => t.threadId === threadId);
  if (i < 0) return;
  const t = { ...chat.threads[i], last: { text, at, mine } };
  chat.threads.splice(i, 1);
  chat.threads.unshift(t);
}

export const canUnsend = (m: ChatMessage, now = Date.now()) => !!chat.me && m.from === chat.me && !m.unsent && !m.status && now - m.at < UNSEND_MS;

export async function unsend(threadId: string, m: ChatMessage): Promise<void> {
  await chatUnsend(m.id);
  chat.msgs[threadId] = (chat.msgs[threadId] ?? []).map((x) => (x.id === m.id ? { id: x.id, threadId, from: x.from, at: x.at, readAt: x.readAt, unsent: true } : x));
}
export const report = (m: ChatMessage, reason: ReportReason, note: string) => chatReport(m.id, reason, note);
export async function block(threadId: string): Promise<void> {
  const peer = chat.info[threadId]?.peer.uid;
  if (!peer) return;
  await chatBlock(peer);
  setInfo({ threadId, canSend: false, closed: 'blocked' });
}

// ---------- পড়া ----------
const readSent: Record<string, number> = {};
/** ওপাশের সবচেয়ে নতুন মেসেজ পর্যন্ত পড়া হয়েছে জানানো (খোলা আর পাতা সামনে থাকলে) */
export async function markRead(threadId: string): Promise<void> {
  const list = chat.msgs[threadId] ?? [];
  let upTo = 0;
  for (const m of list) if (m.from !== chat.me && !m.status && m.at > upTo) upTo = m.at;
  const t = chat.threads.find((x) => x.threadId === threadId);
  if (!upTo || (readSent[threadId] ?? 0) >= upTo) {
    if (t && t.unread && upTo && (readSent[threadId] ?? 0) >= upTo) t.unread = 0;
    return;
  }
  readSent[threadId] = upTo;
  const had = t?.unread ?? 0;
  if (t) t.unread = 0;
  if (had) chat.unread = Math.max(0, chat.unread - had);
  try {
    await chatRead(threadId, upTo);
    void refreshUnread();
  } catch {
    readSent[threadId] = 0;
  }
}

// ---------- ঘটনা ----------
const timers: Record<string, ReturnType<typeof setTimeout>> = {};
function later(key: string, f: () => void) {
  clearTimeout(timers[key]);
  timers[key] = setTimeout(f, timing.debounceMs);
}

/** SSE-র ঘটনা (নাম, data) */
export function handleChatEvent(name: string, o: any) {
  if (!o || typeof o !== 'object') return;
  const threadId = typeof o.threadId === 'string' ? o.threadId : '';
  if (name === 'chat_message' && threadId) {
    if (o.message && typeof o.message === 'object') mergeMessages(threadId, [parseMessage(o.message, threadId)]);
    else if (chat.msgs[threadId] || chat.openThread === threadId) later(`l:${threadId}`, () => void loadLatest(threadId));
    later('unread', () => void refreshUnread());
    if (chat.threadsLoaded) later('threads', () => void loadThreads().catch(() => {}));
  } else if (name === 'chat_read' && threadId) {
    const upTo = Number(o.upTo);
    if (!Number.isFinite(upTo) || upTo <= 0) return;
    if (o.by && o.by === chat.me) {
      // আমার আরেক ডিভাইসে পড়া
      const t = chat.threads.find((x) => x.threadId === threadId);
      if (t) t.unread = 0;
      later('unread', () => void refreshUnread());
      return;
    }
    const list = chat.msgs[threadId];
    if (list) chat.msgs[threadId] = list.map((m) => (m.from === chat.me && !m.status && !m.readAt && m.at <= upTo ? { ...m, readAt: upTo } : m));
  }
}

// ---------- চালু/বন্ধ ----------
let stopFn: (() => void) | null = null;
/** লগইন হলে একবার (AppShell-এর ব্যাজ ডাকে); আবার ডাকলে আগেরটা বন্ধ করে নতুন */
export function startChat(uid: string) {
  if (stopFn && chat.me === uid) return;
  stopChat();
  applyTestTiming();
  chat.me = uid;
  const untap = tapEvents(handleChatEvent);
  let last = 0;
  const tick = async () => {
    if (document.visibilityState === 'hidden') return;
    const now = Date.now();
    const live = sseLive.value;
    // খোলা কথোপকথন: SSE না চললে ঘন ঘন (নতুন মেসেজ আর ✓✓ দুটোই এভাবে আসে)
    if (!live && chat.openThread) void loadLatest(chat.openThread);
    if (now - last < (live ? timing.livePollMs : timing.pollMs)) return;
    last = now;
    const before = chat.unread;
    await refreshUnread();
    if (chat.threadsLoaded && (chat.unread !== before || !live)) void loadThreads().catch(() => {});
  };
  const iv = setInterval(tick, Math.min(timing.threadPollMs, timing.pollMs));
  const onVisible = () => {
    if (document.visibilityState !== 'visible') return;
    void refreshUnread();
    if (chat.openThread) void loadLatest(chat.openThread);
  };
  document.addEventListener('visibilitychange', onVisible);
  void refreshUnread();
  last = Date.now();
  stopFn = () => {
    untap();
    clearInterval(iv);
    document.removeEventListener('visibilitychange', onVisible);
  };
}
export function stopChat() {
  stopFn?.();
  stopFn = null;
}
/** লগআউট: অন্যজনের মেসেজ যেন না থাকে */
export function resetChat() {
  stopChat();
  for (const k of Object.keys(timers)) clearTimeout(timers[k]);
  Object.assign(chat, { me: null, unread: 0, threads: [], threadsNext: null, threadsLoaded: false, threadsLoading: false, threadsError: null, info: {}, msgs: {}, older: {}, loading: {}, error: {}, openThread: null });
  payloads.clear();
  for (const k of Object.keys(readSent)) delete readSent[k];
}
