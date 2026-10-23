// অ্যাপে মেসেজ (backend/docs/CHAT.md): সার্ভারের কাজ আর উত্তরের ধরন।
// কল-এর একই সম্পর্ক, ব্লক আর রিপোর্ট — সার্ভার যাচাই করে। কথোপকথন (thread) = দুজন + প্রসঙ্গ (ctx) + refId।
import { call, ServerError } from '@/lib/api';

export type ChatCtx = 'blood' | 'appointment' | 'ambulance' | 'rx_order' | 'lab_order';
export interface ChatPeer { uid: string; name: string; photoThumb?: string }
export interface ChatLocation { lat: number; lng: number }
export interface ChatMessage {
  id: string;
  threadId: string;
  /** পাঠানো মানুষের uid */
  from: string;
  text?: string;
  /** কিছুক্ষণের সই করা লিংক (~১ ঘণ্টা) — মেয়াদ গেলে chat_messages আবার */
  photoUrl?: string;
  /** ছোট ছবি, base64 JPEG */
  photoThumb?: string;
  location?: ChatLocation;
  at: number;
  readAt?: number | null;
  unsent?: boolean;
  // শুধু এই ব্রাউজারে: পাঠানো চলছে / হয়নি (একই clientId-তে আবার পাঠালে সার্ভার একবারই রাখে)
  clientId?: string;
  status?: 'sending' | 'failed';
  error?: string;
}
/** closed = কেন পাঠানো যায় না: আমি ব্লক করেছি / সার্ভার মানা করেছে (ওপাশের ব্লকও এতে, জানানো হয় না) / সম্পর্ক শেষ */
export type Closed = 'blocked' | 'not_allowed' | 'closed';
export interface ThreadInfo { threadId: string; ctx: ChatCtx | null; refId: string; peer: ChatPeer; canSend: boolean; closed?: Closed }
export interface ThreadLast { text: string; at: number; mine: boolean }
export interface ThreadItem extends ThreadInfo { last: ThreadLast | null; unread: number }
/** একটা পাতা: পুরোনো → নতুন; before = আরও পুরোনোর cursor (null = আর নেই) */
export interface MessagePage { messages: ChatMessage[]; before: string | null }
export interface OpenResult extends ThreadInfo, MessagePage {}

const str = (v: unknown) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() && Number.isFinite(Number(v)) ? Number(v) : 0);
export const CHAT_CTXS: ChatCtx[] = ['blood', 'appointment', 'ambulance', 'rx_order', 'lab_order'];
const ctxOf = (v: unknown): ChatCtx | null => (CHAT_CTXS.includes(v as ChatCtx) ? (v as ChatCtx) : null);

export function parsePeer(o: any): ChatPeer {
  return { uid: str(o?.uid), name: str(o?.name), ...(str(o?.photoThumb) ? { photoThumb: str(o.photoThumb) } : {}) };
}
function parseLocation(o: any): ChatLocation | undefined {
  const lat = Number(o?.lat);
  const lng = Number(o?.lng);
  return o && Number.isFinite(lat) && Number.isFinite(lng) && (lat !== 0 || lng !== 0) ? { lat, lng } : undefined;
}
export function parseMessage(o: any, threadId = ''): ChatMessage {
  const m: ChatMessage = { id: str(o?.id), threadId: str(o?.threadId) || threadId, from: str(o?.from), at: num(o?.at) };
  if (o?.unsent === true) return { ...m, unsent: true, readAt: o?.readAt ? num(o.readAt) : null };
  if (str(o?.text)) m.text = str(o.text);
  if (str(o?.photoUrl)) m.photoUrl = str(o.photoUrl);
  if (str(o?.photoThumb)) m.photoThumb = str(o.photoThumb);
  const loc = parseLocation(o?.location);
  if (loc) m.location = loc;
  m.readAt = o?.readAt ? num(o.readAt) : null;
  return m;
}
function parsePage(r: any, threadId: string): MessagePage {
  const raw: any[] = Array.isArray(r?.messages) ? r.messages : [];
  const messages = raw.map((o) => parseMessage(o, threadId)).filter((m) => m.id);
  const b = r?.before;
  return { messages, before: b == null || b === '' || b === false ? null : str(b) };
}
export function parseThread(o: any): ThreadItem {
  const l = o?.last;
  return {
    threadId: str(o?.threadId),
    ctx: ctxOf(o?.ctx),
    refId: str(o?.refId),
    peer: parsePeer(o?.peer),
    canSend: o?.canSend !== false,
    last: l && typeof l === 'object' ? { text: str(l.text), at: num(l.at), mine: l.mine === true } : null,
    unread: Math.max(0, Math.round(num(o?.unread))),
  };
}

/** chat_open / chat_messages-এর মাথা: canSend আর কেন বন্ধ (blocked / closed) */
function closedOf(r: any): Closed | undefined {
  if (r?.canSend !== false) return undefined;
  return r?.blocked === true ? 'blocked' : r?.closed === true ? 'closed' : 'not_allowed';
}
export async function chatOpen(body: { ctx: ChatCtx; refId: string; peerUid?: string }): Promise<OpenResult> {
  const r = await call('chat_open', { ctx: body.ctx, refId: body.refId, ...(body.peerUid ? { peerUid: body.peerUid } : {}) });
  const threadId = str(r?.threadId);
  return { threadId, ctx: ctxOf(r?.ctx) ?? body.ctx, refId: str(r?.refId) || body.refId, peer: parsePeer(r?.peer), canSend: r?.canSend !== false, closed: closedOf(r), ...parsePage(r, threadId) };
}
export interface SendBody { threadId: string; clientId: string; text?: string; photo?: string; location?: ChatLocation }
export async function chatSend(body: SendBody): Promise<ChatMessage> {
  const r = await call('chat_send', { ...body });
  return parseMessage(r?.message ?? r, body.threadId);
}
/** পাতা ধরে: before না দিলে সবচেয়ে নতুন ৫০টা। সার্ভার peer/canSend দিলে সেটাও */
export async function chatMessages(threadId: string, before?: string | null): Promise<MessagePage & { info: Partial<ThreadInfo> }> {
  const r = await call('chat_messages', { threadId, ...(before ? { before } : {}) });
  const info: Partial<ThreadInfo> = {};
  if (r?.peer) info.peer = parsePeer(r.peer);
  if (typeof r?.canSend === 'boolean') {
    info.canSend = r.canSend;
    info.closed = closedOf(r);
  }
  if (ctxOf(r?.ctx)) info.ctx = ctxOf(r.ctx);
  if (str(r?.refId)) info.refId = str(r.refId);
  return { ...parsePage(r, threadId), info };
}
export async function chatThreads(cursor?: string | null): Promise<{ items: ThreadItem[]; next: string | null }> {
  const r = await call('chat_threads', cursor ? { cursor } : {});
  const raw: any[] = Array.isArray(r?.items) ? r.items : [];
  const n = r?.next ?? r?.cursor;
  return { items: raw.map(parseThread).filter((t) => t.threadId), next: n == null || n === '' ? null : str(n) };
}
/** upTo = দেখা সবচেয়ে নতুন মেসেজের at (ms) */
export const chatRead = (threadId: string, upTo: number) => call('chat_read', { threadId, upTo });
export const chatUnsend = (messageId: string) => call('chat_unsend', { messageId });
export type ReportReason = 'harassment' | 'spam' | 'fake' | 'other';
export const REPORT_REASONS: ReportReason[] = ['harassment', 'spam', 'fake', 'other'];
export const chatReport = (messageId: string, reason: ReportReason, note: string) => call('chat_report', { messageId, reason, ...(note ? { note } : {}) });
export async function chatUnread(): Promise<number> {
  const r = await call('chat_unread');
  return Math.max(0, Math.round(num(r?.total)));
}
/** ব্লক — কলের একই তালিকা (দুজনের কেউ আর অ্যাপে কল বা মেসেজ করতে পারেন না) */
export const chatBlock = (peerUid: string) => call('call_block', { peerUid });

/** সার্ভারের "না" — কোনটা কী */
export type ChatRefusal = 'off' | 'rate' | 'not_allowed' | 'blocked' | 'closed' | 'unsend_window' | 'bad_photo' | 'gone' | 'net' | 'other';
export function refusalOf(e: unknown): ChatRefusal {
  if (!(e instanceof ServerError)) return 'other';
  if (e.status === 0) return 'net';
  if (e.code === 'feature_off') return 'off';
  if (e.code === 'chat_rate' || e.status === 429) return 'rate';
  if (e.code === 'call_blocked') return 'blocked';
  if (e.code === 'chat_unsend_window') return 'unsend_window';
  if (e.code === 'bad_photo') return 'bad_photo';
  if (e.code === 'chat_closed' || e.status === 409) return 'closed';
  if (e.code === 'chat_not_allowed' || e.status === 403) return 'not_allowed';
  if (e.status === 404) return 'gone';
  return 'other';
}
