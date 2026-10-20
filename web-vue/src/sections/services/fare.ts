// অ্যাম্বুলেন্সের ভাড়া (২.৩০.০): মালিকের ভাড়ার তালিকা (ঘর), রোগীর "কোথায় যাবেন", টাকার লেখা।
// হিসাব সার্ভারে (server/lib/ambfare.js) — এখানে শুধু দেখানো আর বাছাই।
// ২.৩১.০: রোগীর "কোথায় যাবেন" হাসপাতাল/ক্লিনিকের তালিকা থেকে (DestField → FacilityDialog) বা "অন্য জায়গা লিখুন";
// মালিকের হাসপাতালের সারিও তালিকা থেকে বাছা যায় (facilityId — ভাড়া মেলায় id দিয়ে), লেখাও চলে।
import type { Facility } from './facility';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { districtLabel, upazila } from '@/lib/geo';

export type Dest =
  | { kind: 'upazila'; upazilaId: number }
  | { kind: 'district'; district: string }
  | { kind: 'hospital'; name: string; facilityId?: string; upazilaId?: number | null; district?: string | null };
export type FareRow = Dest & { amount: number };
export interface FareChart { local: number | null; rows: FareRow[]; oxygen: number | null; icu: number | null; night: number | null }

export const MAX_FARE_ROWS = 15;
/** রোগীর বাজেটের চিপ (null = যেকোনো) */
export const FARE_LEVELS = [1000, 2000, 5000, 10000];

/** "৳১,২০০" */
export function taka(n: number | null | undefined): string {
  if (n == null) return '';
  return `৳${num(n.toLocaleString('en-IN'))}`;
}

/** ঘরের লেখা → পুরো টাকা; খালি = null; ভুল = NaN */
export function parseTaka(s: string): number | null {
  const t = toAsciiDigits(s).replace(/[,\s৳]/g, '');
  if (!t) return null;
  return /^\d+$/.test(t) ? Number(t) : NaN;
}

export function destLabel(d: Dest | null | undefined): string {
  if (!d) return '';
  if (d.kind === 'upazila') {
    const u = upazila(d.upazilaId);
    return u ? `${tr(u.en, u.bn)}, ${districtLabel(u.district)}` : '';
  }
  if (d.kind === 'district') return districtLabel(d.district);
  return d.name;
}

export const keyOf = (d: Dest | null): string => (!d ? '' : d.kind === 'upazila' ? `u:${d.upazilaId}` : d.kind === 'district' ? `d:${d.district}` : d.facilityId ? `f:${d.facilityId}` : 'h');
export const fromFacility = (f: Facility): Dest => ({ kind: 'hospital', name: f.name, facilityId: f.facilityId, upazilaId: f.upazilaId, district: f.district });

/** মালিকের ঘরের এক সারি (লেখার অবস্থায়) */
export interface RowDraft { dest: Dest | null; amount: string }
export interface ChartDraft { local: string; rows: RowDraft[]; oxygen: string; icu: string; night: string }

const s = (n: number | null | undefined) => (n == null ? '' : String(n));
export function chartDraft(c: FareChart | null | undefined): ChartDraft {
  return {
    local: s(c?.local), rows: (c?.rows || []).map((r) => ({ dest: r as Dest, amount: s(r.amount) })),
    oxygen: s(c?.oxygen), icu: s(c?.icu), night: s(c?.night),
  };
}

/**
 * লেখা থেকে সার্ভারে পাঠানোর তালিকা; ভুল হলে { error }। খালি তালিকা → chart: null।
 * (সার্ভারও একই নিয়মে যাচাই করে — এখানে শুধু আগে জানানো)
 */
export function chartFromDraft(d: ChartDraft): { chart: FareChart | null } | { error: string } {
  const amt = (v: string, lo: number, hi: number): number | null | 'bad' | 'range' => {
    const n = parseTaka(v);
    if (n == null) return null;
    if (Number.isNaN(n)) return 'bad';
    return n < lo || n > hi ? 'range' : n;
  };
  const local = amt(d.local, 100, 100000);
  if (local === 'bad') return { error: tr('Write fares in whole taka', 'ভাড়া পুরো টাকায় লিখুন') };
  if (local === 'range') return { error: tr('Fares must be between ৳100 and ৳1,00,000', 'ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন') };
  const rows: FareRow[] = [];
  for (const r of d.rows) {
    if (!r.dest && !r.amount.trim()) continue;
    if (!r.dest) return { error: tr('Choose each destination', 'প্রতিটা গন্তব্য বেছে নিন') };
    const a = amt(r.amount, 100, 100000);
    if (a == null) return { error: tr('Write the fare for each destination', 'প্রতিটা গন্তব্যের ভাড়া লিখুন') };
    if (a === 'bad') return { error: tr('Write fares in whole taka', 'ভাড়া পুরো টাকায় লিখুন') };
    if (a === 'range') return { error: tr('Fares must be between ৳100 and ৳1,00,000', 'ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন') };
    rows.push({ ...r.dest, amount: a } as FareRow);
  }
  const ex: Record<'oxygen' | 'icu' | 'night', number | null> = { oxygen: null, icu: null, night: null };
  for (const k of ['oxygen', 'icu', 'night'] as const) {
    const a = amt(d[k], 50, 50000);
    if (a === 'bad') return { error: tr('Write extras in whole taka', 'বাড়তি ভাড়া পুরো টাকায় লিখুন') };
    if (a === 'range') return { error: tr('Extras must be between ৳50 and ৳50,000', 'বাড়তি ভাড়া ৳৫০ থেকে ৳৫০,০০০-এর মধ্যে লিখুন') };
    ex[k] = a;
  }
  if (local == null && !rows.length) {
    if (ex.oxygen != null || ex.icu != null || ex.night != null) return { error: tr('Write at least one fare (local or a destination)', 'অন্তত একটা ভাড়া লিখুন (নিজের উপজেলা বা কোনো গন্তব্য)') };
    return { chart: null };
  }
  return { chart: { local, rows, ...ex } };
}

/** কার্ডে ছোট সারাংশ: "নিজের উপজেলা ৳৮০০ · ঢাকা ৳৫,০০০ · +২" */
export function chartSummary(c: FareChart | null | undefined): string {
  if (!c) return '';
  const parts = [
    ...(c.local != null ? [`${tr('Local', 'নিজের উপজেলা')} ${taka(c.local)}`] : []),
    ...c.rows.slice(0, 2).map((r) => `${destLabel(r)} ${taka(r.amount)}`),
  ];
  const more = c.rows.length - 2;
  return parts.join(' · ') + (more > 0 ? ` · +${num(more)}` : '');
}
