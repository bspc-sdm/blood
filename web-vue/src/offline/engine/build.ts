// অফলাইন কাজ → রসিদ (Invoice) আর স্থানীয় প্রভাব (effects)। কোনো IO নেই — সহজে পরীক্ষা করা যায়।
import { makeLine, totals } from '../money';
import { R } from '../text';
import type { BizKind, ChamberAppt, FeeData, Invoice, InvoiceFee, InvoicePayment, LabBillData, PaymentIn, SaleData } from '../types';

/** সিঙ্ক না হওয়া কাজের স্থানীয় প্রভাব — পড়ার সময় সার্ভারের অবস্থার ওপর বসে; সার্ভার নিলে সেখানে মিশে যায় */
export type Effect =
  | { t: 'stock'; itemId: string; delta: number }
  | { t: 'appt_add'; appt: ChamberAppt }
  | { t: 'appt_set'; id: string; patch: Partial<ChamberAppt> };

export interface IssueCtx {
  kind: BizKind;
  bizId: string;
  biz: { name: string; phone?: string; address?: string };
  deviceId: string;
  uid: string;
  localId: string;
  no: string;
  verifyCode: string;
  at: number;
  demo: boolean;
}

const METHODS = new Set(['cash', 'bkash', 'nagad', 'card', 'other']);

export function cleanPayments(list: PaymentIn[] | undefined, at: number): InvoicePayment[] {
  return (list ?? [])
    .filter((p) => p && Number(p.amount) > 0)
    .map((p) => {
      const out: InvoicePayment = { at, amount: Math.round(Number(p.amount) * 100) / 100, method: METHODS.has(p.method) ? p.method : 'other' };
      if (p.ref) out.ref = String(p.ref).slice(0, 80);
      return out;
    });
}

const cleanFees = (f: InvoiceFee[] | undefined): InvoiceFee[] =>
  (f ?? []).filter((x) => x && x.label && Number(x.amount) > 0).map((x) => ({ label: String(x.label).slice(0, 60), amount: Math.round(Number(x.amount) * 100) / 100 }));

function base(ctx: IssueCtx, kind: Invoice['kind']): Invoice {
  const inv: Invoice = {
    id: '',
    no: ctx.no,
    kind,
    bizId: ctx.bizId,
    bizName: ctx.biz.name,
    customer: {},
    lines: [],
    subtotal: 0,
    disc: 0,
    fees: [],
    total: 0,
    paid: 0,
    due: 0,
    payments: [],
    status: 'issued',
    issuedAt: ctx.at,
    issuedBy: ctx.uid,
    deviceId: ctx.deviceId,
    offline: true,
    verifyCode: ctx.verifyCode,
    verifyUrl: `https://lifolo.com/v/${ctx.verifyCode}`,
    localId: ctx.localId,
    sync: 'pending',
  };
  if (ctx.biz.phone) inv.bizPhone = ctx.biz.phone;
  if (ctx.biz.address) inv.bizAddress = ctx.biz.address;
  if (ctx.demo) inv.demo = true;
  return inv;
}

function customerOf(c: { name?: string; phone?: string } | undefined) {
  const out: Invoice['customer'] = {};
  const name = c?.name?.trim();
  const phone = c?.phone?.replace(/[^\d+]/g, '');
  if (name) out.name = name.slice(0, 80);
  if (phone) out.phone = phone.slice(0, 20);
  return out;
}

/** লাইন, ছাড়, ফি, টাকা — পেমেন্ট না দিলে পুরোটা ক্যাশে (books_sale_add-এর মতো) */
function finish(inv: Invoice, disc: number | undefined, fees: InvoiceFee[] | undefined, payments: PaymentIn[] | undefined, at: number) {
  inv.fees = cleanFees(fees);
  const t0 = totals(inv.lines, disc ?? 0, inv.fees, []);
  inv.payments = payments === undefined ? (t0.total > 0 ? [{ at, amount: t0.total, method: 'cash' }] : []) : cleanPayments(payments, at);
  Object.assign(inv, totals(inv.lines, disc ?? 0, inv.fees, inv.payments));
}

export function buildSale(d: SaleData, ctx: IssueCtx): { invoice: Invoice; effects: Effect[] } {
  const inv = base(ctx, 'pharmacy');
  inv.customer = customerOf(d.customer);
  inv.lines = d.lines.map((l) => makeLine({ name: l.name, qty: l.qty, price: l.price, disc: l.disc, unit: l.unit, code: l.code }));
  inv.refType = d.rxOrderId ? 'rx_order' : 'book_sale';
  if (d.rxOrderId) inv.refId = d.rxOrderId;
  finish(inv, d.disc, d.fees, d.payments, ctx.at);
  const effects: Effect[] = d.lines.filter((l) => l.itemId).map((l) => ({ t: 'stock', itemId: l.itemId!, delta: -Number(l.qty) }));
  return { invoice: inv, effects };
}

export function buildLabBill(d: LabBillData, ctx: IssueCtx): Invoice {
  const inv = base(ctx, 'lab');
  inv.customer = customerOf(d.customer);
  inv.lines = d.lines.map((l) => makeLine({ name: l.name, qty: l.qty ?? 1, price: l.price, disc: l.disc, code: l.code }));
  if (d.labOrderId) {
    inv.refType = 'lab_order';
    inv.refId = d.labOrderId;
  }
  finish(inv, d.disc, d.fees, d.payments, ctx.at);
  return inv;
}

export function buildFee(d: FeeData, ctx: IssueCtx, appt: ChamberAppt | null): { invoice: Invoice; effects: Effect[] } {
  const inv = base(ctx, 'chamber');
  inv.customer = customerOf(d.walkIn ? { name: d.walkIn.name, phone: d.walkIn.phone } : { name: appt?.name });
  inv.lines = [makeLine({ name: R.visit[d.visitType] ?? R.visit.new, qty: 1, price: d.amount })];
  if (d.appointmentId) {
    inv.refType = 'appointment';
    inv.refId = d.appointmentId;
  }
  finish(inv, 0, [], [{ amount: d.amount, method: d.method, ref: d.ref }], ctx.at);
  const effects: Effect[] = [];
  if (d.appointmentId) effects.push({ t: 'appt_set', id: d.appointmentId, patch: { feeNo: ctx.no, visitType: d.visitType } });
  return { invoice: inv, effects };
}

/** বাকি আদায়: নতুন পেমেন্ট যোগ, মোট আবার হিসাব */
export function applyPay(inv: Invoice, amount: number, method: InvoicePayment['method'], ref: string | undefined, at: number): Invoice {
  const out: Invoice = { ...inv, payments: [...inv.payments, ...cleanPayments([{ amount, method, ref }], at)] };
  Object.assign(out, totals(out.lines, out.disc, out.fees, out.payments));
  return out;
}

export function applyVoid(inv: Invoice, reason: string): Invoice {
  return { ...inv, status: 'void', voidReason: reason.slice(0, 200) };
}

/** দাম বদল আর ১০%-এর বেশি ছাড় (I7) — কাউন্টারের পিনে মানা */
export function discountShare(lines: { qty: number; price: number; disc?: number }[], billDisc = 0): number {
  const gross = lines.reduce((s, l) => s + Number(l.qty) * Number(l.price), 0);
  const disc = lines.reduce((s, l) => s + (Number(l.disc) || 0), 0) + (Number(billDisc) || 0);
  return gross > 0 ? disc / gross : 0;
}
