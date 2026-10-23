// অ্যাপে কল (backend/docs/CALLS.md): সার্ভারের কাজগুলো আর উত্তরের ধরন।
// কল শুধু যাদের মধ্যে আগে থেকে সম্পর্ক আছে (রক্তের রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্স, অর্ডার) — সার্ভার যাচাই করে।
import { call, ServerError } from '@/lib/api';

/** order = পুরোনো (ভাগ হওয়ার আগের) কলের তালিকায় শুধু */
export type CallCtx = 'blood' | 'appointment' | 'ambulance' | 'rx_order' | 'lab_order' | 'order';
export type CallState = 'ringing' | 'accepted' | 'declined' | 'missed' | 'ended' | 'cancelled';
export interface CallPeer { uid: string; name: string; photoThumb?: string }
/** LiveKit-এ ঢোকার চাবি */
export interface RoomGrant { callId: string; room: string; url: string; token: string }
export interface StartResult extends RoomGrant { peer: CallPeer; video: boolean }
export interface HistoryItem {
  callId: string;
  ctx: CallCtx;
  refId: string;
  peer: CallPeer;
  video: boolean;
  state: CallState;
  startedAt: number;
  durationSec: number;
  /** in = আমাকে কেউ করেছেন, out = আমি করেছি (না থাকলে অজানা) */
  dir: 'in' | 'out' | null;
}
/** SSE / পুশের `call_incoming` */
export interface Incoming { callId: string; video: boolean; ctx: CallCtx | null; refId: string; caller: CallPeer }

const str = (v: unknown) => (typeof v === 'string' ? v : '');
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const CTXS: CallCtx[] = ['blood', 'appointment', 'ambulance', 'rx_order', 'lab_order', 'order'];
const STATES: CallState[] = ['ringing', 'accepted', 'declined', 'missed', 'ended', 'cancelled'];

export function parsePeer(o: any, fallbackName = ''): CallPeer {
  if (typeof o === 'string') return { uid: '', name: o };
  return { uid: str(o?.uid), name: str(o?.name) || fallbackName, ...(str(o?.photoThumb) ? { photoThumb: str(o.photoThumb) } : {}) };
}
export function parseState(v: unknown): CallState | null {
  return STATES.includes(v as CallState) ? (v as CallState) : null;
}
function parseGrant(o: any): RoomGrant {
  return { callId: str(o?.callId), room: str(o?.room), url: str(o?.url), token: str(o?.token) };
}
export function parseHistory(o: any): HistoryItem {
  const dir = o?.dir === 'in' || o?.dir === 'out' ? o.dir : o?.incoming === true ? 'in' : o?.incoming === false ? 'out' : null;
  return {
    callId: str(o?.callId),
    ctx: CTXS.includes(o?.ctx) ? o.ctx : 'blood',
    refId: str(o?.refId),
    peer: parsePeer(o?.peer),
    video: o?.video === true,
    state: parseState(o?.state) ?? 'ended',
    startedAt: num(o?.startedAt),
    durationSec: Math.max(0, Math.round(num(o?.durationSec))),
    dir,
  };
}
export function parseIncoming(o: any): Incoming | null {
  const callId = str(o?.callId);
  if (!callId) return null;
  const caller = o?.caller && typeof o.caller === 'object' ? parsePeer(o.caller) : parsePeer(null, str(o?.caller) || str(o?.callerName));
  return { callId, video: o?.video === true || o?.video === 'true', ctx: CTXS.includes(o?.ctx) ? o.ctx : null, refId: str(o?.refId), caller };
}

export async function callStart(body: { ctx: CallCtx; refId: string; peerUid?: string; video: boolean }): Promise<StartResult> {
  const r = await call('call_start', { ...body });
  return { ...parseGrant(r), peer: parsePeer(r?.peer), video: r?.video === true || (r?.video == null && body.video) };
}
/** video:false = ভিডিও কল অডিওতে ধরা (না দিলে যেমন বেজেছে) */
export async function callAccept(callId: string, video?: boolean): Promise<RoomGrant & { peer: CallPeer | null; video: boolean | null }> {
  const r = await call('call_accept', { callId, ...(video != null ? { video } : {}) });
  return { ...parseGrant(r), callId, peer: r?.peer ? parsePeer(r.peer) : null, video: typeof r?.video === 'boolean' ? r.video : null };
}
export const callDecline = (callId: string) => call('call_decline', { callId });
export const callCancel = (callId: string) => call('call_cancel', { callId });
export const callEnd = (callId: string) => call('call_end', { callId });
export interface StatusResult {
  state: CallState | null;
  startedAt: number;
  endedAt: number;
  video: boolean | null;
  peer: CallPeer | null;
  /** কথা চলার সময় নতুন টোকেন (১০ মিনিটের) — আবার যুক্ত হতে / ভিডিওতে যেতে */
  grant: RoomGrant | null;
  /** ভিডিওর অনুরোধ: কে করেছেন (uid) — উত্তরের অপেক্ষায় থাকলে */
  upgradeBy: string | null;
}
export async function callStatus(callId: string): Promise<StatusResult> {
  const r = await call('call_status', { callId });
  const g = parseGrant({ ...r, callId });
  return {
    state: parseState(r?.state),
    startedAt: num(r?.startedAt),
    endedAt: num(r?.endedAt),
    video: typeof r?.video === 'boolean' ? r.video : null,
    peer: r?.peer ? parsePeer(r.peer) : null,
    grant: g.token && g.url ? g : null,
    upgradeBy: str(r?.upgrade?.by) || str(r?.upgradeBy) || null,
  };
}
/** next = পরের পাতার cursor (শেষটার startedAt); না থাকলে শেষ */
export async function callHistory(before?: number): Promise<{ items: HistoryItem[]; next: number | null }> {
  const r = await call('call_history', before ? { before } : {});
  const raw: any[] = Array.isArray(r?.items) ? r.items : [];
  const items = raw.map(parseHistory).filter((x) => x.callId);
  const next = typeof r?.next === 'number' ? r.next : r?.more === true && items.length ? items[items.length - 1].startedAt : null;
  return { items, next };
}
/** কথার মাঝে ভিডিও চাওয়া (রক্ত/অ্যাম্বুলেন্স/অর্ডারের কল অডিওতে শুরু হয়) */
export const callUpgrade = (callId: string) => call('call_upgrade', { callId });
export async function callUpgradeAccept(callId: string): Promise<RoomGrant> {
  return { ...parseGrant(await call('call_upgrade_accept', { callId })), callId };
}
export const callUpgradeDecline = (callId: string) => call('call_upgrade_decline', { callId });
// ব্লক আর রিপোর্ট
export interface Blocked { uid: string; name: string; photoThumb?: string; blockedAt: number }
export const callBlock = (peerUid: string, callId?: string | null) => call('call_block', { peerUid, ...(callId ? { callId } : {}) });
export const callUnblock = (peerUid: string) => call('call_unblock', { peerUid });
export async function callBlocks(): Promise<Blocked[]> {
  const r = await call('call_blocks');
  const raw: any[] = Array.isArray(r?.items) ? r.items : [];
  return raw.map((o) => ({ ...parsePeer(o), blockedAt: num(o?.blockedAt) })).filter((x) => x.uid);
}
export type ReportReason = 'harassment' | 'spam' | 'fake' | 'other';
export const callReport = (callId: string, reason: ReportReason, note: string) => call('call_report', { callId, reason, ...(note ? { note } : {}) });
/** অনলাইন অ্যাপয়েন্টমেন্টের ভিডিও কলে ঢোকা (রোগী বা ডাক্তার/সহকারী) */
export async function apptJoin(appointmentId: string): Promise<RoomGrant> {
  return parseGrant(await call('appt_join', { appointmentId }));
}

/** সার্ভারের "না" — কোনটা কী (বোতামের নিচে কী দেখাব, ফোনের নম্বর দেখাব কি না) */
export type StartRefusal = 'off' | 'busy' | 'not_allowed' | 'blocked' | 'rate' | 'unavailable' | 'other';
export function refusalOf(e: unknown): StartRefusal {
  if (!(e instanceof ServerError)) return 'other';
  if (e.code === 'call_off') return 'off';
  if (e.code === 'call_busy') return 'busy';
  if (e.code === 'call_blocked') return 'blocked';
  if (e.code === 'calls_unavailable' || e.status === 503) return 'unavailable';
  if (e.code === 'call_not_allowed' || e.status === 403) return 'not_allowed';
  if (e.status === 429 || e.code === 'call_rate') return 'rate';
  return 'other';
}
