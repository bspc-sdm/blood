// টাকা (BDT, ২ দশমিক) — পয়সায় হিসাব, যাতে 0.1 + 0.2 ধরনের ভুল না হয়। সার্ভারও আবার হিসাব করে (CONTRACT: "the server recomputes all totals")।
import type { InvoiceFee, InvoiceLine, InvoicePayment } from './types';

export const paisa = (tk: number) => Math.round((Number.isFinite(tk) ? tk : 0) * 100);
export const taka = (p: number) => Math.round(p) / 100;
export const round2 = (tk: number) => taka(paisa(tk));

export interface LineIn {
  name: string;
  qty: number;
  price: number;
  disc?: number;
  unit?: string;
  code?: string;
}

/** লাইন: amount = qty × price − disc (০-এর নিচে নয়) */
export function makeLine(l: LineIn): InvoiceLine {
  const qty = Number(l.qty) || 0;
  const price = round2(Number(l.price) || 0);
  const disc = round2(Math.max(0, Number(l.disc) || 0));
  const amount = taka(Math.max(0, Math.round(qty * paisa(price)) - paisa(disc)));
  const out: InvoiceLine = { name: String(l.name ?? '').trim(), qty, price, disc, amount };
  if (l.unit) out.unit = l.unit;
  if (l.code) out.code = l.code;
  return out;
}

export interface Totals {
  subtotal: number;
  disc: number;
  total: number;
  paid: number;
  due: number;
}

/** subtotal = লাইনের যোগফল; total = subtotal − বিলের ছাড় + ফি; due = total − paid (০-এর নিচে নয়) */
export function totals(lines: InvoiceLine[], disc: number, fees: InvoiceFee[], payments: InvoicePayment[]): Totals {
  const sub = lines.reduce((s, l) => s + paisa(l.amount), 0);
  const d = Math.min(sub, paisa(Math.max(0, disc || 0)));
  const f = fees.reduce((s, x) => s + paisa(x.amount), 0);
  const total = Math.max(0, sub - d + f);
  const paid = payments.reduce((s, p) => s + paisa(p.amount), 0);
  return { subtotal: taka(sub), disc: taka(d), total: taka(total), paid: taka(paid), due: taka(Math.max(0, total - paid)) };
}

/** ১২৩৪৫.৫ → "12,345.50" (অঙ্ক বদলানো আলাদা) */
export function formatTaka(tk: number): string {
  const neg = tk < 0;
  const [i, f] = Math.abs(round2(tk)).toFixed(2).split('.');
  const g = i.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${neg ? '-' : ''}${g}.${f}`;
}

const BN = '০১২৩৪৫৬৭৮৯';
export const bnDigits = (s: string) => s.replace(/[0-9]/g, (d) => BN[+d]);
