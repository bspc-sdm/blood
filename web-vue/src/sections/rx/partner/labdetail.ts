// প্যাথলজির রিপোর্ট দেওয়ার ছকের সারি আর হিসাব (কম্পোনেন্ট: ReportEntry.vue …)
import { tr } from '@/lib/i18n';
import type { LabHome } from '@/lib/labprice';
import { autoFlag, parseNum, rangeText, type LabForm } from './model';

export const LAB_BASE = '/work/lab/orders';
export const DAY = 24 * 60 * 60 * 1000;

/** expired: রোগীর চাওয়া সময় পেরিয়ে গেছে, এখনো সময় জানানো হয়নি — এখনো নিশ্চিত করা যায়, রোগীও বদলাতে পারেন */
export const homeStatusLab = (h: LabHome) =>
  h.status === 'confirmed' ? tr('Time confirmed', 'সময় নিশ্চিত') : h.status === 'declined' ? tr('Not going', 'যাবেন না')
    : h.status === 'expired' ? tr('Time has passed', 'সময় পেরিয়ে গেছে') : tr('Waiting for your time', 'সময় জানানো বাকি');

export interface Row {
  id: number; test: string; result: string; unit: string; range: string; flag: string;
  group: string; code: string | null; key: string | null; fixed: boolean; numeric: boolean; options: string[]; long: boolean;
  lowText: string; highText: string; base: [number | null, number | null];
}
let seq = 0;
export const freeRow = (test = ''): Row => ({
  id: ++seq, test, result: '', unit: '', range: '', flag: '', group: '', code: null, key: null, fixed: false, numeric: false, options: [], long: false, lowText: '', highText: '', base: [null, null],
});
const fmt = (v: number | null) => (v == null ? '' : String(v));

export function rowsFrom(forms: LabForm[]): Row[] {
  return forms.flatMap((f) =>
    f.code && f.params.length
      ? f.params.map((p) => {
          const numeric = p.low != null || p.high != null;
          return {
            id: ++seq, test: p.name, result: '', unit: p.unit || '', range: numeric ? rangeText(p.low, p.high) : p.range || p.text || '', flag: '',
            group: f.name, code: f.code, key: p.key, fixed: true, numeric, options: p.options || [], long: !!p.long,
            lowText: fmt(p.low), highText: fmt(p.high), base: [p.low, p.high] as [number | null, number | null],
          };
        })
      : [freeRow(f.test)],
  );
}
export const lowOf = (r: Row) => parseNum(r.lowText);
export const highOf = (r: Row) => parseNum(r.highText);
export function flagOf(r: Row): string {
  if (!r.numeric) return r.flag;
  const lo = lowOf(r);
  const hi = highOf(r);
  const a = autoFlag(r.result, Number.isNaN(lo) ? null : lo, Number.isNaN(hi) ? null : hi);
  return a ?? r.flag;
}
