// রসিদ (invoice) — সার্ভারের আকার (backend/docs/INVOICES.md, মালিক অনুমোদিত ২০২৬-১০-২৩) আর সব জায়গার সাধারণ হিসাব:
// টাকা লেখা (বাংলা/ইংরেজি অঙ্ক), ধরন, পরিশোধের মাধ্যম, বিলের আগাম হিসাব (আসল হিসাব সার্ভারে), সার্ভারের উত্তর থেকে রসিদ বের করা।
import { num, tr } from '@/lib/i18n';
import { T } from './text';

import type { Invoice as OfflineInvoice, InvoiceFee, InvoiceLine, InvoicePayment, InvoiceKind, PayMethod as OPayMethod, Paper as OPaper } from '@/offline';

export type InvKind = InvoiceKind;
export const INV_KINDS: InvKind[] = ['pharmacy', 'lab', 'chamber', 'ambulance'];
export type PayMethod = OPayMethod;
export const PAY_METHODS: PayMethod[] = ['cash', 'bkash', 'nagad', 'card', 'other'];
export type RefType = NonNullable<OfflineInvoice['refType']>;
export type Paper = OPaper;
export const PAPERS: Paper[] = ['58', '80', 'A4'];
export type Period = 'today' | '7d' | '30d' | 'month';

export type InvLine = InvoiceLine;
export type InvFee = InvoiceFee;
export type InvPayment = InvoicePayment;
/** সার্ভারের রসিদ (ডেস্কটপের একই ধরন — @/offline) + সার্ভারের বাড়তি: ডেমো, অ্যাম্বুলেন্সের ভাড়ার তালিকার চেয়ে বেশি */
export type Invoice = OfflineInvoice & { demo?: boolean; overFare?: boolean; chartFare?: number };
/** invoice_verify — শুধু এটুকু (রোগী/ক্রেতার কিছু নয়); lines = লাইনের সংখ্যা (নাম নয়) */
export interface VerifyInfo { bizName: string; kind: InvKind; no: string; issuedAt: number; total: number; paid: number; due: number; status: 'issued' | 'void'; lines: number | null; demo: boolean; suspended: boolean; voidedAt: number | null }
export interface InvPage { items: Invoice[]; next: string | null; sum: { count: number; total: number; paid: number; due: number } | null }
export interface Expenses { total: number; byKind: Partial<Record<InvKind, number>>; count: number }

/** ডেস্কটপে বানানো, এখনো সার্ভারে যায়নি */
export const isPending = (inv: Invoice) => inv.sync === 'pending' || (inv.offline && !inv.id);
/** ডেমো রসিদ: সার্ভার demo বলে, বা কোড DEMO দিয়ে শুরু (নিরাপত্তা পর্যালোচনা I9) */
export const isDemo = (inv: { demo?: boolean; verifyCode?: string }) => inv.demo === true || /^DEMO/i.test(inv.verifyCode ?? '');

// ---------- লেখা ----------
export const kindText = (k: string) =>
  k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি') : k === 'lab' ? tr('Lab', 'প্যাথলজি') : k === 'chamber' ? T.chamber() : k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : k;
export const kindIcon = (k: string) => (k === 'pharmacy' ? 'storefront' : k === 'lab' ? 'local_hospital' : k === 'chamber' ? 'stethoscope' : 'ambulance');
export const methodText = (m: string) =>
  m === 'cash' ? T.cash() : m === 'bkash' ? T.bkash() : m === 'nagad' ? T.nagad() : m === 'card' ? T.card() : tr('Other', 'অন্য');
export const periods = (): { value: Period; label: string }[] => [
  { value: 'today', label: tr('Today', 'আজ') },
  { value: '7d', label: tr('7 days', '৭ দিন') },
  { value: '30d', label: tr('30 days', '৩০ দিন') },
  { value: 'month', label: tr('This month', 'এই মাস') },
];

const BN_DIGITS = '০১২৩৪৫৬৭৮৯';
/** অঙ্ক বাছাই করা ভাষায় (রসিদে: বাংলা মূল, ইংরেজি বাছা যায়) */
export const digitsOf = (s: string | number, d: 'bn' | 'en') => (d === 'bn' ? String(s).replace(/[0-9]/g, (x) => BN_DIGITS[+x]) : String(s));
/** ৳১,২৩৪.৫০ — রসিদে সবসময় দুই দশমিক */
export function moneyIn(v: number | null | undefined, d: 'bn' | 'en'): string {
  if (v == null || !Number.isFinite(v)) return '—';
  const s = Math.abs(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (v < 0 ? '−' : '') + '৳' + digitsOf(s, d);
}
/** পর্দার লেখায় (অ্যাপের ভাষা অনুযায়ী অঙ্ক) — দশমিক শুধু থাকলে */
export function money(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return (v < 0 ? '−' : '') + '৳' + num(Math.abs(v).toLocaleString('en-IN', { maximumFractionDigits: 2 }));
}
export const r2 = (x: number) => Math.round((x + Number.EPSILON) * 100) / 100;

/** "01711223344" → "017••••3344" (ব্যবসার তালিকায় ৩০ দিন পর সার্ভার নিজেই ঢাকে; এখানে শুধু দেখানো) */
export const maskPhone = (p?: string) => (p && p.length >= 8 && !p.includes('•') ? p.slice(0, 3) + '••••' + p.slice(-4) : p ?? '');

/** "lifolo.com/v/AB12CD34EF" — লিংকের https:// বাদ */
export const verifyShort = (inv: Pick<Invoice, 'verifyUrl' | 'verifyCode'>) =>
  (inv.verifyUrl || `https://lifolo.com/v/${inv.verifyCode}`).replace(/^https?:\/\//, '').replace(/\/$/, '');

// ---------- বিলের আগাম হিসাব (দেখানোর জন্য; সার্ভার আবার হিসাব করে) ----------
export interface DraftLine { name: string; qty: number; price: number; disc: number }
export type BillDisc = { type: 'pct' | 'amt'; v: number } | null;
export function billOf(lines: DraftLine[], bill: BillDisc, fees: InvFee[] = []) {
  let subtotal = 0;
  let lineDisc = 0;
  for (const l of lines) {
    const g = r2(Math.max(0, l.qty) * Math.max(0, l.price));
    subtotal += g;
    lineDisc += Math.min(g, Math.max(0, l.disc));
  }
  const after = subtotal - lineDisc;
  const bd = bill && bill.v > 0 && after > 0 ? Math.min(after, bill.type === 'pct' ? (after * Math.min(100, bill.v)) / 100 : bill.v) : 0;
  const feeSum = fees.reduce((a, f) => a + Math.max(0, f.amount || 0), 0);
  return { subtotal: r2(subtotal), disc: r2(lineDisc + bd), fees: r2(feeSum), total: r2(after - bd + feeSum) };
}
/** দিলেন কত, ফেরত কত (নগদে বেশি দিলে); কম দিলে বাকি */
export function changeOf(total: number, given: number | null) {
  if (given == null || !Number.isFinite(given)) return { paid: total, change: 0, due: 0 };
  const paid = r2(Math.min(total, given));
  return { paid, change: r2(Math.max(0, given - total)), due: r2(Math.max(0, total - given)) };
}

/** সার্ভারে পাঠানোর payments: খালি "কত পেলেন" = পুরো টাকা; ০ = কিছু পাননি (সব বাকি) */
export function paymentsOf(total: number, method: PayMethod, given: number | null, ref?: string) {
  const { paid } = changeOf(total, given);
  return paid > 0 ? [{ amount: paid, method, ...(ref?.trim() ? { ref: ref.trim() } : {}) }] : [];
}

// ---------- সার্ভারের উত্তর ----------
const n2 = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : Number(v) || 0);
/** সার্ভারের রসিদ (টাকা সংখ্যা, তালিকা খালি হলেও অ্যারে) */
export function parseInvoice(o: any): Invoice {
  return {
    ...o,
    id: String(o?.id ?? ''),
    no: String(o?.no ?? ''),
    kind: o?.kind,
    bizId: String(o?.bizId ?? ''),
    bizName: String(o?.bizName ?? ''),
    customer: o?.customer ?? {},
    lines: Array.isArray(o?.lines) ? o.lines.map((l: any) => ({ ...l, qty: n2(l.qty), price: n2(l.price), disc: n2(l.disc), amount: n2(l.amount) })) : [],
    fees: Array.isArray(o?.fees) ? o.fees.map((f: any) => ({ label: String(f.label ?? ''), amount: n2(f.amount) })) : [],
    payments: Array.isArray(o?.payments) ? o.payments.map((p: any) => ({ ...p, at: n2(p.at), amount: n2(p.amount) })) : [],
    subtotal: n2(o?.subtotal), disc: n2(o?.disc), total: n2(o?.total), paid: n2(o?.paid), due: n2(o?.due),
    status: o?.status === 'void' ? 'void' : 'issued',
    issuedAt: n2(o?.issuedAt),
    offline: o?.offline === true,
    verifyCode: String(o?.verifyCode ?? ''),
    verifyUrl: String(o?.verifyUrl ?? (o?.verifyCode ? `https://lifolo.com/v/${o.verifyCode}` : '')),
  };
}
/** উত্তরে রসিদ: { invoice } বা সরাসরি */
export function pickInvoice(r: any): Invoice | null {
  const o = r && typeof r === 'object' ? (r.invoice ?? (r.id && r.no ? r : null)) : null;
  return o ? parseInvoice(o) : null;
}
export function parsePage(r: any): InvPage {
  const items = Array.isArray(r?.items) ? r.items : Array.isArray(r?.invoices) ? r.invoices : [];
  const sum = r?.sum ? { count: n2(r.sum.count), total: n2(r.sum.total), paid: n2(r.sum.paid), due: n2(r.sum.due) } : null;
  return { items: items.map(parseInvoice), next: r?.next ?? r?.cursor ?? null, sum };
}
/** invoice_verify-এর উত্তর ({invoice:{…, date, status: valid|void}}) */
export function parseVerify(r: any): VerifyInfo | null {
  const o = r?.invoice ?? r;
  if (!o || !o.no) return null;
  const lines = typeof o.lines === 'number' ? o.lines : typeof o.lineCount === 'number' ? o.lineCount : Array.isArray(o.lines) ? o.lines.length : null;
  return {
    bizName: String(o.bizName ?? ''), kind: o.kind, no: String(o.no), issuedAt: n2(o.issuedAt ?? o.date),
    total: n2(o.total), paid: n2(o.paid), due: n2(o.due), status: o.status === 'void' ? 'void' : 'issued',
    lines, demo: isDemo(o), suspended: o.sanctioned === true || o.suspended === true, voidedAt: o.voidedAt ? n2(o.voidedAt) : null,
  };
}
/** শেষ পরিশোধের মাধ্যম (রসিদে "নগদ" / "বিকাশ" …) */
export const methodsOf = (inv: Invoice) => [...new Set(inv.payments.map((p) => p.method))];

/** আজ (ঢাকা) "2026-10-23" */
export const todayStr = (now = Date.now()) => new Date(now + 6 * 3600 * 1000).toISOString().slice(0, 10);
/** "2026-10" এই মাস (ঢাকা) */
export function thisMonth(now = Date.now()): string {
  return new Date(now + 6 * 3600 * 1000).toISOString().slice(0, 7);
}
export function shiftMonth(m: string, by: number): string {
  const [y, mo] = m.split('-').map(Number);
  const d = new Date(Date.UTC(y, mo - 1 + by, 1));
  return d.toISOString().slice(0, 7);
}
const BN_MONTHS = ['জানুয়ারি', 'ফেব্রুয়ারি', 'মার্চ', 'এপ্রিল', 'মে', 'জুন', 'জুলাই', 'আগস্ট', 'সেপ্টেম্বর', 'অক্টোবর', 'নভেম্বর', 'ডিসেম্বর'];
const EN_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
export function monthText(m: string): string {
  const [y, mo] = m.split('-').map(Number);
  return tr(`${EN_MONTHS[mo - 1]} ${y}`, `${BN_MONTHS[mo - 1]} ${num(y)}`);
}
