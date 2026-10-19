// ফার্মেসির হিসাব-খাতা — সার্ভারের আকার (server/lib/shopbooks.js) আর লেখা (অ্যাপের PharmacyBooks.kt-এর একই)
import { useQueryClient } from '@tanstack/vue-query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';

export type Disc = { type: 'pct' | 'amt'; v: number } | null;
/** books_items */
export interface ShopItem {
  id: string; name: string; generic: string; form: string; unit: string;
  qty: number | null; cost: number | null; price: number | null; disc: Disc; salePrice: number | null;
  lowAt: number | null; expiry: string | null; inStock: boolean; low: boolean; expDays: number | null; lastSaleAt: number | null; updatedAt: number;
}
export interface SaleLine { itemId: string; name: string; qty: number; unitPrice: number | null; unitDisc: number; discount: number; net: number; unitCost: number | null; cost: number | null; profit: number | null; i?: number }
export interface SaleTotals { gross: number; discount: number; billDiscount: number; net: number; cost: number; profit: number; netCosted: number; unpriced: number }
export interface Sale { id: string; at: number; day: string; source: 'counter' | 'rx' | 'online'; lines: SaleLine[]; totals: SaleTotals; rx?: { code: string } /* রোগীর নাম রাখা হয় না — শুধু প্রেসক্রিপশনের কোড */; customer?: string }
export interface Purchase { id: string; at: number; supplier: string; note: string; lines: { itemId: string; name: string; qty: number; unitCost: number | null; expiry: string | null; total: number | null }[]; total: number }
export interface Adjust { id: string; kind: 'writeoff' | 'count'; name: string; before: number | null; after: number | null; delta: number; loss: number; reason: string; at: number }
export interface RankRow { id: string; name: string; qty: number; net: number; profit: number; loss: number; lossQty: number }
/** books_report */
export interface Report {
  period: Period; from: string; to: string;
  totals: { sales: number; gross: number; discount: number; net: number; cost: number; profit: number; margin: number | null; uncosted: number; writeOff: number; netProfit: number; bought: number; purchases: number };
  bySource: Record<'counter' | 'rx' | 'online', { n: number; net: number }>;
  daily: { day: string; net: number; profit: number; sales: number }[];
  topSales: RankRow[]; topProfit: RankRow[]; belowCost: RankRow[];
  stock: { items: number; counted: number; atCost: number; atPrice: number; noCost: number; noPrice: number };
  low: ShopItem[]; expiring: ShopItem[]; noSale: ShopItem[];
}
/** books_rx_preview */
export interface RxPreview {
  code: string; at: number; source: 'rx' | 'online'; recorded: boolean;
  lines: { i: number; name: string; qty: number; itemId: string | null; itemName: string; unitPrice: number | null; unitDisc: number; unitCost: number | null; stockQty: number | null }[];
}
export type Period = 'today' | '7d' | '30d' | 'month';

export const UNITS = ['piece', 'strip', 'box', 'bottle', 'tube', 'vial', 'sachet', 'other'] as const;
export const unitText = (u: string) =>
  u === 'piece' ? tr('pcs', 'পিস') : u === 'strip' ? tr('strip', 'পাতা') : u === 'box' ? tr('box', 'বক্স') : u === 'bottle' ? tr('bottle', 'বোতল')
    : u === 'tube' ? tr('tube', 'টিউব') : u === 'vial' ? tr('vial', 'ভায়াল') : u === 'sachet' ? tr('sachet', 'স্যাশে') : u === 'other' ? tr('other', 'অন্য') : '';

export const periods = (): { value: Period; label: string }[] => [
  { value: 'today', label: tr('Today', 'আজ') },
  { value: '7d', label: tr('7 days', '৭ দিন') },
  { value: '30d', label: tr('30 days', '৩০ দিন') },
  { value: 'month', label: tr('This month', 'এই মাস') },
];
export const sourceText = (s: string) => (s === 'rx' ? tr('Prescription', 'প্রেসক্রিপশন') : s === 'online' ? tr('Online', 'অনলাইন') : tr('Counter', 'কাউন্টার'));

/** ৳১,২৩৪.৫ — বাংলায় বাংলা অঙ্ক, লাখের হিসাবে কমা */
export function money(v: number | null | undefined): string {
  if (v == null) return '—';
  const s = Math.abs(v).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  return (v < 0 ? '−' : '') + '৳' + num(s);
}
export const qtyText = (q: number | null | undefined) => (q == null ? '—' : num(q.toLocaleString('en-IN', { maximumFractionDigits: 2 })));
export const discText = (d: Disc) => (!d ? '' : d.type === 'pct' ? `${num(d.v)}%` : money(d.v));
/** "2027-03" → "মার্চ ২০২৭" */
export function expiryText(e: string | null): string {
  if (!e) return '';
  const [y, m] = e.split('-').map(Number);
  const bn = ['জানু', 'ফেব্রু', 'মার্চ', 'এপ্রিল', 'মে', 'জুন', 'জুলাই', 'আগস্ট', 'সেপ্টে', 'অক্টো', 'নভে', 'ডিসে'];
  const en = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${tr(en[m - 1], bn[m - 1])} ${num(y)}`;
}
/** "20261018" → "১৮/১০" */
export const dayShort = (d: string) => `${num(Number(d.slice(6, 8)))}/${num(Number(d.slice(4, 6)))}`;

/** লেখা সংখ্যা (বাংলা অঙ্কও): খালি = null, ভুল = NaN */
export function readNum(s: string): number | null {
  const t = toAsciiDigits(s).replace(/,/g, '').trim();
  if (!t) return null;
  const v = Number(t);
  return Number.isFinite(v) && v >= 0 ? v : NaN;
}
/** ইনপুটে শুধু সংখ্যা আর দশমিক */
export const numInput = (s: string) => toAsciiDigits(s).replace(/[^0-9.]/g, '').replace(/(\..*)\./g, '$1').slice(0, 10);
export const numStr = (v: number | null | undefined) => (v == null ? '' : String(v));

/** প্রতি এককে ছাড় (টাকায়) — সার্ভারের unitDiscOf */
export function unitDisc(price: number | null, d: Disc): number {
  if (price == null || !d) return 0;
  return Math.round(Math.min(price, d.type === 'pct' ? (price * d.v) / 100 : d.v) * 100) / 100;
}
export const r2 = (x: number) => Math.round((x + Number.EPSILON) * 100) / 100;

/** হিসাবের সব কাজের ক্যাশ নতুন করে আনা (একটা বদলালে অন্যগুলোও বদলায়) */
export function useBooksRefresh() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).startsWith('books_') || q.queryKey[0] === 'stock_get' });
}

/** কাছাকাছি নামে খোঁজা (তালিকার ভেতরে) */
export const findItems = (items: ShopItem[], q: string, n = 8) => {
  const t = q.trim().toLowerCase();
  if (!t) return [];
  const starts = items.filter((i) => i.name.toLowerCase().startsWith(t));
  const has = items.filter((i) => !i.name.toLowerCase().startsWith(t) && (i.name.toLowerCase().includes(t) || i.generic.toLowerCase().includes(t)));
  return [...starts, ...has].slice(0, n);
};
