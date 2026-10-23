// LIFOLO PAY — হাতে MFS পেমেন্ট (bKash/Nagad/Rocket/Upay): রোগী সরাসরি ব্যবসার নম্বরে পাঠান, TrxID আর শেষ ৪ অঙ্ক দিয়ে জানান,
// ব্যবসা মিলিয়ে নিশ্চিত করে (তখন রসিদ)। Lifolo টাকা রাখে না। চুক্তি: backend/docs/PAYMENTS.md (মালিক অনুমোদিত ২০২৬-১০-২৩)।
// এখানে: সার্ভারের আকার (pay request), যাচাইয়ের নিয়ম (সার্ভারও আবার দেখে), অবস্থার ধারা (timeline)।
import { toAsciiDigits } from '@/lib/i18n';

export type PayMethodKind = 'bkash' | 'nagad' | 'rocket' | 'upay';
export const PAY_METHOD_KINDS: PayMethodKind[] = ['bkash', 'nagad', 'rocket', 'upay'];
export type AcctType = 'personal' | 'merchant';
export type PayPurpose = 'chamber_fee' | 'booking_advance' | 'delivery' | 'lab' | 'ambulance_fare' | 'invoice_due';
/** নিরাপত্তা পর্যালোচনা (docs/test-plan/PAYMENTS-SECURITY-REVIEW.md): refund_claimed — ব্যবসা ফেরত পাঠিয়েছে বলছে, রোগী "পেয়েছি"
 *  বললে (বা ৭ দিন পর) refunded; refund_due — ব্যবসা বাতিল করেছে/দেয়নি, ফেরত পাওনা (P5, P11) */
export type PayStatus = 'open' | 'claimed' | 'confirmed' | 'rejected' | 'expired' | 'refund_due' | 'refund_claimed' | 'refunded' | 'cancelled';
export const PAY_STATUSES: PayStatus[] = ['open', 'claimed', 'confirmed', 'rejected', 'expired', 'refund_due', 'refund_claimed', 'refunded', 'cancelled'];
export type BizKind = 'chamber' | 'pharmacy' | 'lab' | 'ambulance';
export type RejectReason = 'not_received' | 'wrong_amount' | 'wrong_number' | 'other';
export const REJECT_REASONS: RejectReason[] = ['not_received', 'wrong_amount', 'wrong_number', 'other'];

/** অনুরোধের ভেতরের নম্বর (তৈরির সময়ের ছবি — পরে নম্বর বদলালেও এটাই) */
export interface PayMethod { method: PayMethodKind; number: string; type: AcctType; active: boolean }
/** ব্যবসার সেটিংয়ের নম্বর: বদলের পর ২৪ ঘণ্টা আগেরটাই চলে (pendingUntil পর্যন্ত activeNumber) */
export interface MethodSetting extends PayMethod { pendingUntil: number | null; activeNumber: string }
/** by: কে "পাঠিয়েছি" বলেছেন (কেয়ারগিভার নিজের ওয়ালেট থেকে দিলে রোগী নন); byName: সার্ভার নাম দিলে */
export interface PayClaim { method: PayMethodKind; trxId: string; senderLast4: string; at: number; by?: string; byName: string }
export interface PayRefund { trxId: string; amount: number; at: number; ackAt: number | null }
export interface PayRequest {
  id: string;
  kind: BizKind | string;
  bizId: string;
  bizName: string;
  payerUid: string;
  /** ব্যবসার তালিকায়: কে দিচ্ছেন (সার্ভার দিলে) */
  payerName: string;
  refType: string;
  refId: string;
  purpose: PayPurpose | string;
  amount: number;
  methods: PayMethod[];
  status: PayStatus;
  claim: PayClaim | null;
  confirmedAt: number | null;
  confirmedBy: string | null;
  rejectReason: string | null;
  rejectNote: string | null;
  rejectedAt: number | null;
  refund: PayRefund | null;
  /** নিশ্চিত হওয়া টাকা ফেরত পাওনা (ব্যবসা বাতিল করেছে / দেয়নি — P11) */
  refundDue: { at: number; why: string } | null;
  /** pay_request_get-এর ফেরতের নিয়ম আর সতর্কতা (সার্ভারের লেখা) */
  policy: string[];
  invoiceId: string | null;
  /** চেম্বারের অগ্রিম: এর আগে বাতিল করলে ফেরত */
  refundableUntil: number | null;
  expiresAt: number | null;
  createdAt: number;
  demo: boolean;
}
export interface PayPage { items: PayRequest[]; next: string | null }
export interface PaySummary { day: string; collected: number; confirmedCount: number; open: number; pending: number }

const s = (v: unknown) => (typeof v === 'string' ? v : v == null ? '' : String(v));
const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : Number(v) || 0);
const nOrNull = (v: unknown) => (v == null || v === '' ? null : Number.isFinite(Number(v)) ? Number(v) : null);

export const isMethod = (m: unknown): m is PayMethodKind => PAY_METHOD_KINDS.includes(m as PayMethodKind);
const status = (v: unknown): PayStatus => (PAY_STATUSES.includes(v as PayStatus) ? (v as PayStatus) : 'open');

export function parseMethod(o: any): PayMethod | null {
  if (!o || !isMethod(o.method)) return null;
  return { method: o.method, number: s(o.number), type: o.type === 'merchant' ? 'merchant' : 'personal', active: o.active !== false };
}
export function parseSetting(o: any): MethodSetting | null {
  const m = parseMethod(o);
  if (!m) return null;
  const pend = nOrNull(o.pendingUntil ?? o.effectiveAt ?? o.coolOffUntil);
  return { ...m, pendingUntil: pend && pend > 0 ? pend : null, activeNumber: s(o.activeNumber ?? o.currentNumber) || m.number };
}

export function parsePayRequest(o: any): PayRequest {
  const c = o?.claim;
  const r = o?.refund;
  return {
    id: s(o?.id),
    kind: s(o?.kind),
    bizId: s(o?.bizId),
    bizName: s(o?.bizName),
    payerUid: s(o?.payerUid),
    payerName: s(o?.payerName ?? o?.patientName),
    refType: s(o?.refType),
    refId: s(o?.refId),
    purpose: s(o?.purpose),
    amount: n(o?.amount),
    methods: (Array.isArray(o?.methods) ? o.methods : []).map(parseMethod).filter((m: PayMethod | null): m is PayMethod => !!m),
    status: status(o?.status),
    claim: c && typeof c === 'object' ? { method: isMethod(c.method) ? c.method : 'bkash', trxId: s(c.trxId), senderLast4: s(c.senderLast4), at: n(c.at), ...(c.by ? { by: s(c.by) } : {}), byName: s(c.byName ?? c.claimedByName) } : null,
    confirmedAt: nOrNull(o?.confirmedAt),
    confirmedBy: o?.confirmedBy ? s(o.confirmedBy) : null,
    rejectReason: o?.rejectReason ? s(o.rejectReason) : null,
    rejectNote: o?.rejectNote ? s(o.rejectNote) : null,
    rejectedAt: nOrNull(o?.rejectedAt),
    refund: r && typeof r === 'object' ? { trxId: s(r.trxId), amount: n(r.amount), at: n(r.at), ackAt: nOrNull(r.ackAt) } : null,
    refundDue: o?.refundDue && typeof o.refundDue === 'object' ? { at: n(o.refundDue.at), why: s(o.refundDue.why) } : null,
    policy: (Array.isArray(o?.policy) ? o.policy : o?.policy ? [o.policy] : []).map(s).filter(Boolean),
    invoiceId: o?.invoiceId ? s(o.invoiceId) : null,
    refundableUntil: nOrNull(o?.refundableUntil),
    expiresAt: nOrNull(o?.expiresAt),
    createdAt: n(o?.createdAt),
    demo: o?.demo === true,
  };
}
/** কাজের উত্তরে অনুরোধ: {request} / {payRequest} / {pay} বা সরাসরি; pay_request_get-এর policy অনুরোধের সাথে */
export function pickRequest(r: any): PayRequest | null {
  if (!r || typeof r !== 'object') return null;
  const o = r.request ?? r.payRequest ?? r.pay ?? (r.id && (r.purpose || r.status) ? r : null);
  return o && typeof o === 'object' && o.id ? parsePayRequest(r.policy && !o.policy ? { ...o, policy: r.policy } : o) : null;
}
export function parsePayPage(r: any): PayPage {
  const items = Array.isArray(r?.items) ? r.items : Array.isArray(r?.payments) ? r.payments : Array.isArray(r?.requests) ? r.requests : [];
  return { items: items.map(parsePayRequest), next: r?.next ?? r?.cursor ?? null };
}
export function parseSummary(r: any): PaySummary {
  const o = r?.summary ?? r ?? {};
  return {
    day: s(o.day),
    collected: n(o.collected ?? o.total ?? o.confirmedAmount),
    confirmedCount: n(o.confirmedCount ?? o.count),
    open: n(o.open ?? o.openCount),
    pending: n(o.pending ?? o.pendingClaims ?? o.claimed),
  };
}

/** অর্ডার/অ্যাপয়েন্টমেন্ট/অনুরোধের সাথে লাগানো পেমেন্ট: payRequest {…} বা payRequestId (+ payStatus, payAmount) */
export interface PayLink { id: string; status: PayStatus | null; amount: number | null; request: PayRequest | null }
export function payLinkOf(o: any): PayLink | null {
  if (!o || typeof o !== 'object') return null;
  const obj = o.payRequest && typeof o.payRequest === 'object' ? o.payRequest : null;
  if (obj?.id) {
    const req = parsePayRequest(obj);
    return { id: req.id, status: req.status, amount: req.amount, request: req };
  }
  const id = s(o.payRequestId ?? o.payId);
  if (!id) return null;
  return { id, status: o.payStatus ? status(o.payStatus) : null, amount: nOrNull(o.payAmount), request: null };
}

/** লাগানো পেমেন্টের অবস্থা (জানা না থাকলে খোলা ধরে) */
export const linkStatus = (l: PayLink): PayStatus => l.request?.status ?? l.status ?? 'open';
/** এখনো দেওয়া বাকি (খোলা, বা দোকান পায়নি বলেছে) */
export const linkDue = (l: PayLink | null | undefined) => !!l && (linkStatus(l) === 'open' || linkStatus(l) === 'rejected');

// ---------- কোন অবস্থায় কী করা যায় ----------
/** রোগী "পাঠিয়েছি" জানাতে পারেন: খোলা, বা ব্যবসা পায়নি বলেছে (আবার জানানো) — মেয়াদ না পেরোলে */
export const canClaim = (r: Pick<PayRequest, 'status' | 'expiresAt'>, now = Date.now()) =>
  (r.status === 'open' || r.status === 'rejected') && !(r.expiresAt && r.expiresAt < now);
/** ব্যবসা নিশ্চিত করতে পারে: রোগী জানিয়েছেন, বা ভুল করে "পাইনি" বলেছিল (৭ দিনের মধ্যে আবার নিশ্চিত — সার্ভার দেখে; P3) */
export const canConfirm = (r: Pick<PayRequest, 'status' | 'claim'>) => r.status === 'claimed' || (r.status === 'rejected' && !!r.claim);
/** "পাইনি": শুধু রোগী জানানো অবস্থায় */
export const canReject = (r: Pick<PayRequest, 'status'>) => r.status === 'claimed';
export const canDecide = (r: Pick<PayRequest, 'status' | 'claim'>) => canConfirm(r) || canReject(r);
/** টাকা ফেরত: নিশ্চিত হওয়ার পর, বা ফেরত পাওনা হলে (শুধু মালিক; সার্ভার আগের ফেরত বাদ দিয়ে সীমা দেখে) */
export const canRefund = (r: Pick<PayRequest, 'status' | 'refund'>) => r.status === 'confirmed' || r.status === 'refund_due';
/** ফেরত পাওনা — সার্ভারের refundDue (অবস্থা "নিশ্চিত" থাকে) বা পুরোনো refund_due অবস্থা */
export const owesRefund = (r: Pick<PayRequest, 'status' | 'refundDue'>) => r.status === 'refund_due' || (r.status === 'confirmed' && !!r.refundDue);
/** ব্যবসা ফেরত পাঠিয়েছে বলছে — রোগী "পেয়েছি" / "পাইনি" */
export const needsRefundAck = (r: Pick<PayRequest, 'status' | 'refund'>) => r.status === 'refund_claimed' || (r.status === 'refunded' && !!r.refund && !r.refund.ackAt);
/** শেষ অবস্থা (আর কিছু হবে না) */
export const isFinal = (st: PayStatus) => st === 'confirmed' || st === 'expired' || st === 'refunded' || st === 'cancelled';
/** সক্রিয় নম্বর (তৈরির সময়ের ছবি থেকে) */
export const activeMethods = (r: Pick<PayRequest, 'methods'>) => r.methods.filter((m) => m.active && m.number);

// ---------- লেখার নিয়ম ----------
/** TrxID: bKash ঠিক ১০টা, বাকিগুলো ৬–২০টা ইংরেজি অক্ষর/অঙ্ক */
export const trxMax = (m: PayMethodKind) => (m === 'bkash' ? 10 : 20);
export const trxMin = (m: PayMethodKind) => (m === 'bkash' ? 10 : 6);
export const trxClean = (v: string, m: PayMethodKind) => toAsciiDigits(v).toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, trxMax(m));
export const trxValid = (v: string, m: PayMethodKind) => new RegExp(`^[0-9A-Z]{${trxMin(m)},${trxMax(m)}}$`).test(v);
/** যে নম্বর থেকে পাঠিয়েছেন তার শেষ ৪ অঙ্ক */
export const last4Clean = (v: string) => toAsciiDigits(v).replace(/\D/g, '').slice(0, 4);
export const last4Valid = (v: string) => /^\d{4}$/.test(v);
/** বাংলাদেশের মোবাইল নম্বর → 01XXXXXXXXX (+88/88 বাদ), না মিললে null */
export function normMobile(v: string): string | null {
  const d = toAsciiDigits(v).replace(/[^0-9+]/g, '').replace(/^\+?88(?=01)/, '');
  return /^01[3-9]\d{8}$/.test(d) ? d : null;
}
export const mobileInput = (v: string) => toAsciiDigits(v).replace(/[^0-9+]/g, '').slice(0, 14);

// ---------- অবস্থার ধারা ----------
export type StepKind = 'created' | 'claimed' | 'confirmed' | 'rejected' | 'refund_due' | 'refunded' | 'refund_ack' | 'expired' | 'cancelled' | 'waiting';
export interface Step { k: StepKind; at: number | null; done: boolean }
/** অনুরোধ → ধাপগুলো (হয়ে যাওয়া + এখন যার অপেক্ষা) */
export function timelineOf(r: PayRequest): Step[] {
  const out: Step[] = [{ k: 'created', at: r.createdAt || null, done: true }];
  if (r.claim) out.push({ k: 'claimed', at: r.claim.at || null, done: true });
  switch (r.status) {
    case 'open':
      out.push({ k: 'claimed', at: null, done: false });
      break;
    case 'claimed':
      out.push({ k: 'confirmed', at: null, done: false });
      break;
    case 'rejected':
      out.push({ k: 'rejected', at: r.rejectedAt, done: true });
      break;
    case 'refund_due':
      if (r.confirmedAt) out.push({ k: 'confirmed', at: r.confirmedAt, done: true });
      out.push({ k: 'refund_due', at: null, done: true });
      out.push({ k: 'refunded', at: null, done: false });
      break;
    case 'confirmed':
      out.push({ k: 'confirmed', at: r.confirmedAt, done: true });
      break;
    case 'refund_claimed':
      out.push({ k: 'confirmed', at: r.confirmedAt, done: true });
      out.push({ k: 'refunded', at: r.refund?.at ?? null, done: true });
      out.push({ k: 'refund_ack', at: null, done: false });
      break;
    case 'refunded':
      out.push({ k: 'confirmed', at: r.confirmedAt, done: true });
      out.push({ k: 'refunded', at: r.refund?.at ?? null, done: true });
      out.push({ k: 'refund_ack', at: r.refund?.ackAt ?? null, done: !!r.refund?.ackAt });
      break;
    case 'expired':
      out.push({ k: 'expired', at: r.expiresAt, done: true });
      break;
    case 'cancelled':
      out.push({ k: 'cancelled', at: null, done: true });
      break;
  }
  return out;
}

/** "2026-10" এই মাস (ঢাকা) */
export const monthOf = (now = Date.now()) => new Date(now + 6 * 3600 * 1000).toISOString().slice(0, 7);
