import { tr } from '@/lib/i18n';
import { parseNum, type LabPriceItem } from './model';

export interface PriceRow { id: number; code: string | null; name: string; price: string; tat: string; on: boolean; hidden?: boolean }
let seq = 0;
export const nextId = () => ++seq;
export const rowOf = (i: LabPriceItem): PriceRow => ({ id: ++seq, code: i.code, name: i.name, price: String(i.price), tat: i.tat || '', on: i.on !== false, hidden: i.hidden === true });
export const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9ঀ-৿]+/g, ' ').trim();
export const TAT = () => [tr('30 minutes', '৩০ মিনিট'), tr('1 hour', '১ ঘণ্টা'), tr('Same day', 'একই দিন'), tr('24 hours', '২৪ ঘণ্টা'), tr('2 days', '২ দিন'), tr('3 days', '৩ দিন')];
/** দাম: পূর্ণ সংখ্যা ১–২,০০,০০০ (বাংলা অঙ্ক চলে); খালি null, ভুল NaN */
export function priceOf(s: string): number | null {
  const v = parseNum(s.replace(/[৳\s]/g, ''));
  if (v == null) return null;
  return Number.isInteger(v) && v >= 1 && v <= 200000 ? v : NaN;
}
