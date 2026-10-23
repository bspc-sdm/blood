// সার্ভারের কাজ (backend/docs/PAYMENTS.md) — সব এক জায়গায়, যাতে চুক্তি বদলালে শুধু এখানে বদলায়।
// রোগীর কাজগুলো কেয়ারগিভার মোডে রোগীর uid (asUid) নিয়ে যায় (lib/care.ts-এর তালিকা)।
import { useQueryClient } from '@tanstack/vue-query';
import { call } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { parsePayPage, parsePayRequest, parseSetting, parseSummary, pickRequest, type BizKind, type MethodSetting, type PayMethodKind, type PayPage, type PayPurpose, type PayStatus, type RejectReason } from './model';

// ---------- রোগী ----------
export const getRequest = (id: string) => call('pay_request_get', { id }).then((r) => pickRequest(r) ?? parsePayRequest(r));
/** জিনিস ধরে (অ্যাপয়েন্টমেন্ট, অর্ডার, পরীক্ষা, অ্যাম্বুলেন্স, রসিদ) — সবচেয়ে নতুন পেমেন্ট; না থাকলে 404 */
export const getRequestByRef = (refType: string, refId: string, purpose?: PayPurpose) =>
  call('pay_request_get', { refType, refId, ...(purpose ? { purpose } : {}) }).then((r) => pickRequest(r));
export const claim = (id: string, method: PayMethodKind, trxId: string, senderLast4: string) => call('pay_claim', { id, method, trxId, senderLast4 }).then(pickRequest);
export const refundAck = (id: string) => call('pay_refund_ack', { id }).then(pickRequest);
export const report = (id: string, reason: string, note?: string) => call('pay_report', { id, reason, ...(note?.trim() ? { note: note.trim() } : {}) });
export const myPayments = (cursor?: string | null) => call('my_payments', cursor ? { cursor } : {}).then(parsePayPage);
/** রসিদের বাকি: পেমেন্ট শুরু (একই রসিদে খোলা থাকলে সেটাই ফেরে) */
export const startDue = (invoiceId: string) => call('pay_due_start', { invoiceId }).then(pickRequest);
/** সিস্টেম/ব্যবসা তৈরি করে (সার্ভার সম্পর্ক আর অবস্থা যাচাই করে): যেমন ট্রিপ শেষে অ্যাম্বুলেন্সের ভাড়া, অর্ডারের অগ্রিম */
/** টাকা সবসময় জিনিস থেকে (অর্ডার, বিল, ভাড়া, রসিদের বাকি) — এখান থেকে টাকা যায় না (P6) */
export const createRequest = (refType: string, refId: string, purpose: PayPurpose) => call('pay_request_create', { refType, refId, purpose }).then(pickRequest);

// ---------- ব্যবসা ----------
export interface Advance { chamberKey: string; amount: number; refundableUntilHoursBefore: number }
export interface ChamberRef { chamberKey: string; name: string; nameBn: string }
export interface MethodChangeRow { method: string; change: string; oldLast4: string; newLast4: string; by: string; at: number }
export interface MethodsInfo { methods: MethodSetting[]; advance: Advance[]; chambers: ChamberRef[]; history: MethodChangeRow[]; frozen: boolean; owner: boolean }
/** pay_methods_get → নম্বর (activeNumber, pendingUntil), ডাক্তারের অগ্রিম আর চেম্বার, মালিককে বদলের ইতিহাস */
export const getMethods = (kind: BizKind, bizId: string) =>
  call('pay_methods_get', { kind, bizId }).then((r: any): MethodsInfo => ({
    methods: (Array.isArray(r?.methods) ? r.methods : []).map(parseSetting).filter((m: MethodSetting | null): m is MethodSetting => !!m),
    advance: (Array.isArray(r?.advance) ? r.advance : []).map((a: any) => ({ chamberKey: String(a.chamberKey ?? ''), amount: Number(a.amount) || 0, refundableUntilHoursBefore: Number(a.refundableUntilHoursBefore) || 0 })),
    chambers: (Array.isArray(r?.chambers) ? r.chambers : []).map((c: any) => ({ chamberKey: String(c.chamberKey ?? ''), name: String(c.name ?? ''), nameBn: String(c.nameBn ?? '') })),
    history: (Array.isArray(r?.history) ? r.history : []).map((h: any) => ({ method: String(h.method ?? ''), change: String(h.change ?? ''), oldLast4: String(h.oldLast4 ?? ''), newLast4: String(h.newLast4 ?? ''), by: String(h.by ?? ''), at: Number(h.at) || 0 })),
    frozen: r?.frozen === true,
    owner: Array.isArray(r?.history),
  }));
/** নম্বর আর/অথবা অগ্রিম (শুধু মালিক; ১০ মিনিটের মধ্যে লগইন — নইলে 401 reauth_required) */
export const setMethods = (kind: BizKind, bizId: string, body: { methods?: { method: PayMethodKind; number: string; type: 'personal' | 'merchant'; active: boolean }[]; advance?: Advance[] }) =>
  call('pay_methods_set', { kind, bizId, ...body });
/** "আমি করিনি": সব নম্বর এখনই বন্ধ, অন্য ডিভাইস লগআউট, অ্যাডমিনের কাছে (নিরাপত্তা পর্যালোচনা P4) */
export const freezeMethods = (kind: BizKind, bizId: string) => call('pay_methods_freeze', { kind, bizId });
export const confirmPay = (id: string, checks: { trxId: boolean; amount: boolean; last4: boolean } | null, extra: Record<string, unknown> = {}) =>
  call('pay_confirm', { id, ...(checks ? { checks } : {}), ...extra });
export const rejectPay = (id: string, reason: RejectReason, note?: string, extra: Record<string, unknown> = {}) =>
  call('pay_reject', { id, reason, ...(note?.trim() ? { note: note.trim() } : {}), ...extra });
export const refundPay = (id: string, trxId: string, amount?: number | null, extra: Record<string, unknown> = {}) =>
  call('pay_refund', { id, trxId, ...(amount != null ? { amount } : {}), ...extra });
export const listBiz = (kind: BizKind, bizId: string, opts: { status?: PayStatus | ''; period?: string; cursor?: string | null } = {}): Promise<PayPage> =>
  call('pay_list_biz', { kind, bizId, ...(opts.status ? { status: opts.status } : {}), ...(opts.period ? { period: opts.period } : {}), ...(opts.cursor ? { cursor: opts.cursor } : {}) }).then(parsePayPage);
export const summary = (kind: BizKind, bizId: string, day?: string) => call('pay_summary', { kind, bizId, ...(day ? { day } : {}) }).then(parseSummary);
export const exportCsv = (kind: BizKind, bizId: string, month: string) => call<{ csv?: string; filename?: string; url?: string }>('pay_export_csv', { kind, bizId, month });

// ---------- Lifolo-র নিজের আয় (bKash গেটওয়ে) ----------
export const pgwCheckout = (purpose: string, amount: number) => call<{ bkashURL?: string; url?: string }>('pgw_checkout', { purpose, amount });

// ---------- ক্যাশ ----------
/** পেমেন্ট বদলালে যা আবার আনতে হয়: পেমেন্ট, তালিকা, সারাংশ, আর যেসব জিনিসের সাথে পেমেন্ট লাগানো (অ্যাপয়েন্টমেন্ট, অর্ডার, রসিদ …) */
const PAY_KEYS = ['pay_request_get', 'my_payments', 'pay_list_biz', 'pay_summary', 'pay_methods_get'];
const LINKED = ['my_appointments', 'rx_patients', 'rx_activity', 'rx_orders_pharmacy', 'lab_orders', 'my_invoices', 'my_expenses', 'invoice_get', 'invoice_list_biz', 'ambreq_status', 'queue_today', 'review_requests'];
export function invalidatePay(qc = queryClient, linked = true) {
  return qc.invalidateQueries({
    predicate: (q) => {
      const k = String(q.queryKey[0]);
      return PAY_KEYS.includes(k) || (linked && LINKED.includes(k));
    },
  });
}
export function usePayRefresh() {
  const qc = useQueryClient();
  return (linked = true) => invalidatePay(qc, linked);
}
