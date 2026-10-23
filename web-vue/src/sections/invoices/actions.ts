// রসিদ তৈরি আর বদলের কাজ — ডেস্কটপে (isDesktop) অফলাইন স্টোরে লেখা (পরে মেলে), নইলে সরাসরি সার্ভারে।
// অনলাইনের ইনপুট (books_sale_add, lab_bill_create, chamber_fee_collect, invoice_pay, invoice_void) আর অফলাইনের op
// (@/offline-এর SaleData, LabBillData, FeeData, PayData, VoidData …) আলাদা করে দেওয়া — ডেস্কটপ op নিজের আকারে রাখে।
import { useQueryClient } from '@tanstack/vue-query';
import { call, errorText } from '@/lib/api';
import { T } from './text';
import type { OfflineOp } from '@/offline';
import { offlineStore } from './offline';
import { parseInvoice, pickInvoice, type Invoice, type PayMethod } from './model';

export interface Issued { invoice: Invoice | null; offline: boolean; raw?: any }

/** একটা কাজ: ডেস্কটপে record(op), নইলে call(action, body) */
export async function issue(action: string, body: Record<string, unknown>, op: () => OfflineOp): Promise<Issued> {
  const s = offlineStore();
  if (s) {
    const r = await s.record(op());
    return { invoice: r.invoice ? parseInvoice(r.invoice) : null, offline: true, raw: r };
  }
  const r = await call(action, body);
  return { invoice: pickInvoice(r), offline: false, raw: r };
}

const refOf = (inv: Invoice) => (inv.id ? { id: inv.id } : { localId: inv.localId });

export const payDue = (inv: Invoice, amount: number, method: PayMethod, ref?: string) => {
  const r = ref?.trim() ? { ref: ref.trim() } : {};
  return issue('invoice_pay', { id: inv.id, amount, method, ...r }, () => ({ type: 'pay', data: { invoice: refOf(inv), amount, method, ...r } }));
};
export const voidInvoice = (inv: Invoice, reason: string, extra: Record<string, unknown> = {}) =>
  issue('invoice_void', { id: inv.id, reason: reason.trim(), ...extra }, () => ({ type: 'void', data: { invoice: refOf(inv), reason: reason.trim() } }));

/** রসিদ বদলালে তালিকা/খরচ আবার আনা */
export function useInvoiceRefresh() {
  const qc = useQueryClient();
  return () =>
    qc.invalidateQueries({
      predicate: (q) => {
        const k = String(q.queryKey[0]);
        return k.startsWith('invoice_') || k === 'my_invoices' || k === 'my_expenses' || k.startsWith('books_') || k === 'stock_get' || k === 'queue_today';
      },
    });
}

/** ভুলের লেখা — ডেস্কটপের "owner_only" (কাউন্টারের পিনে মালিকের কাজ) বাংলায় */
export function errText(e: unknown): string {
  if (e && typeof e === 'object' && (e as { code?: string }).code === 'owner_only') return T.ownerOnly();
  return errorText(e);
}
