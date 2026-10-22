// অ্যাপে কল (backend/docs/CALLS.md): সার্ভারের কাজগুলো আর উত্তরের ধরন।
// কল শুধু যাদের মধ্যে আগে থেকে সম্পর্ক আছে (রক্তের রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্স, অর্ডার) — সার্ভার যাচাই করে।
import { call, ServerError } from '@/lib/api';

export type CallCtx = 'blood' | 'appointment' | 'ambulance' | 'order';
export type CallState = 'ringing' | 'accepted' | 'declined' | 'missed' | 'ended' | 'cancelled';
export interface CallPeer { uid: string; name: string; photoThumb?: string }
/** LiveKit-এ ঢোকার চাবি */
export interface RoomGrant { callId: string; room: string; url: string; token: string }
export interface StartResult extends RoomGrant { peer: CallPeer }
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
const CTXS: CallCtx[] = ['blood', 'appointment', 'ambulance', 'order'];
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
  return { ...parseGrant(r), peer: parsePeer(r?.peer) };
}
export async function callAccept(callId: string): Promise<RoomGrant> {
  const r = await call('call_accept', { callId });
  return { ...parseGrant(r), callId };
}
export const callDecline = (callId: string) => call('call_decline', { callId });
export const callCancel = (callId: string) => call('call_cancel', { callId });
export const callEnd = (callId: string) => call('call_end', { callId });
export async function callStatus(callId: string): Promise<{ state: CallState | null; startedAt: number; endedAt: number }> {
  const r = await call('call_status', { callId });
  return { state: parseState(r?.state), startedAt: num(r?.startedAt), endedAt: num(r?.endedAt) };
}
export async function callHistory(before?: number): Promise<{ items: HistoryItem[]; more: boolean }> {
  const r = await call('call_history', before ? { before } : {});
  const raw: any[] = Array.isArray(r?.items) ? r.items : Array.isArray(r?.calls) ? r.calls : [];
  return { items: raw.map(parseHistory).filter((x) => x.callId), more: r?.more === true || r?.hasMore === true };
}
/** অনলাইন অ্যাপয়েন্টমেন্টের ভিডিও কলে ঢোকা (রোগী বা ডাক্তার/সহকারী) */
export async function apptJoin(appointmentId: string): Promise<RoomGrant> {
  return parseGrant(await call('appt_join', { appointmentId }));
}

/** সার্ভারের "না" — কোনটা কী (বোতামের নিচে কী দেখাব, ফোনের নম্বর দেখাব কি না) */
export type StartRefusal = 'off' | 'busy' | 'not_allowed' | 'rate' | 'other';
export function refusalOf(e: unknown): StartRefusal {
  if (!(e instanceof ServerError)) return 'other';
  if (e.code === 'call_off') return 'off';
  if (e.code === 'call_busy') return 'busy';
  if (e.code === 'call_not_allowed' || e.status === 403) return 'not_allowed';
  if (e.status === 429 || e.code === 'rate_limited') return 'rate';
  return 'other';
}
