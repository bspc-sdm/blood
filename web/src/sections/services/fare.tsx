// অ্যাম্বুলেন্সের ভাড়া (২.৩০.০): মালিকের ভাড়ার তালিকা (ঘর), রোগীর "কোথায় যাবেন", টাকার লেখা।
// হিসাব সার্ভারে (server/lib/ambfare.js) — এখানে শুধু দেখানো আর বাছাই।
import { useEffect, useState } from 'react';
import { Button, Field, IconButton, Select, TextInput } from '@/ui/kit';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { districtLabel, districtsSorted, ensureAreas, areasReady, upazila, upazilasOf } from '@/lib/geo';

export type Dest =
  | { kind: 'upazila'; upazilaId: number }
  | { kind: 'district'; district: string }
  | { kind: 'hospital'; name: string };
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

function useAreasReady() {
  const [ok, set] = useState(areasReady());
  useEffect(() => {
    if (!ok) ensureAreas().then(() => set(true), () => {});
  }, [ok]);
  return ok;
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

const keyOf = (d: Dest | null): string => (!d ? '' : d.kind === 'upazila' ? `u:${d.upazilaId}` : d.kind === 'district' ? `d:${d.district}` : 'h');

/**
 * গন্তব্য বাছাই: জেলার উপজেলা / অন্য জেলা / হাসপাতাল (লিখুন)।
 * district: যে জেলার উপজেলা আগে দেখাবে; ownUpazila দিলে সেটার নাম "নিজের উপজেলা" নয়, সাধারণভাবেই।
 * hospitals: নাম মেলাতে পরামর্শ (কাছের গাড়ির তালিকা থেকে)।
 */
export function DestPicker({
  label, district, value, onChange, hospitals = [], exclude = [], error,
}: {
  label: string; district: string; value: Dest | null; onChange: (d: Dest | null) => void;
  hospitals?: string[]; exclude?: string[]; error?: string | null;
}) {
  useAreasReady();
  const [hospText, setHospText] = useState(value?.kind === 'hospital' ? value.name : '');
  const [mode, setMode] = useState(keyOf(value));
  useEffect(() => {
    setMode(keyOf(value));
    if (value?.kind === 'hospital') setHospText(value.name);
  }, [value?.kind === 'hospital' ? value.name : keyOf(value)]);
  const ups = upazilasOf(district).filter((u) => !exclude.includes(`u:${u.id}`));
  const dists = districtsSorted().filter((d) => !exclude.includes(`d:${d.bn}`));
  const options = [
    ...ups.map((u) => ({ value: `u:${u.id}`, label: `${tr(u.en, u.bn)} (${tr('upazila', 'উপজেলা')})` })),
    { value: 'h', label: tr('A hospital (type the name)', 'কোনো হাসপাতাল (নাম লিখুন)') },
    ...dists.map((d) => ({ value: `d:${d.bn}`, label: `${districtLabel(d.bn)} (${tr('district', 'জেলা')})` })),
  ];
  const listId = `hosp-${district}`;
  function pick(v: string) {
    setMode(v);
    if (!v) onChange(null);
    else if (v === 'h') onChange(hospText.trim().length >= 3 ? { kind: 'hospital', name: hospText.trim() } : null);
    else if (v.startsWith('u:')) onChange({ kind: 'upazila', upazilaId: Number(v.slice(2)) });
    else onChange({ kind: 'district', district: v.slice(2) });
  }
  return (
    <div className="col gap-6">
      <Select label={label} value={mode} placeholder={tr('Choose…', 'বেছে নিন…')} options={options} error={mode === 'h' ? undefined : error ?? undefined} onChange={(e) => pick(e.target.value)} />
      {mode === 'h' ? (
        <>
          <TextInput
            label={tr('Hospital name', 'হাসপাতালের নাম')}
            value={hospText}
            maxLength={80}
            list={hospitals.length ? listId : undefined}
            error={error ?? undefined}
            onChange={(e) => {
              setHospText(e.target.value);
              const t = e.target.value.trim();
              onChange(t.length >= 3 ? { kind: 'hospital', name: t } : null);
            }}
          />
          {hospitals.length ? <datalist id={listId}>{hospitals.map((h) => <option key={h} value={h} />)}</datalist> : null}
        </>
      ) : null}
    </div>
  );
}

/** মালিকের ঘরের এক সারি (লেখার অবস্থায়) */
interface RowDraft { dest: Dest | null; amount: string }
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

function AmountInput({ label, value, onChange, hint }: { label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  return <TextInput label={label} hint={hint} inputMode="numeric" placeholder="৳" value={value} maxLength={7} onChange={(e) => onChange(e.target.value)} />;
}

/** মালিকের ফর্মে "ভাড়ার তালিকা": নিজের উপজেলা, গন্তব্যের সারি (সর্বোচ্চ ১৫টা), বাড়তি */
export function FareChartEditor({ district, upazilaId, value, onChange }: { district: string | null; upazilaId: number | null; value: ChartDraft; onChange: (d: ChartDraft) => void }) {
  useAreasReady();
  const set = (p: Partial<ChartDraft>) => onChange({ ...value, ...p });
  const setRow = (i: number, p: Partial<RowDraft>) => set({ rows: value.rows.map((r, j) => (j === i ? { ...r, ...p } : r)) });
  const used = (i: number) => value.rows.filter((_, j) => j !== i).map((r) => keyOf(r.dest)).filter((k) => k && k !== 'h');
  return (
    <Field label={tr('Fare list (helps patients choose by budget)', 'ভাড়ার তালিকা (রোগী বাজেট দিয়ে খুঁজতে পারেন)')}>
      <div className="col gap-8 fare-chart">
        <AmountInput label={tr('Within own upazila', 'নিজের উপজেলার ভেতরে')} value={value.local} onChange={(v) => set({ local: v })} />
        {district ? value.rows.map((r, i) => (
          <div key={i} className="fare-row">
            <DestPicker
              label={tr(`Destination ${i + 1}`, `গন্তব্য ${num(i + 1)}`)}
              district={district}
              value={r.dest}
              exclude={[...used(i), ...(upazilaId ? [`u:${upazilaId}`] : [])]}
              onChange={(d) => setRow(i, { dest: d })}
            />
            <div className="row gap-8 fare-row-amt">
              <AmountInput label={tr('Fare', 'ভাড়া')} value={r.amount} onChange={(v) => setRow(i, { amount: v })} />
              <IconButton icon="delete" label={tr('Remove', 'সরান')} onClick={() => set({ rows: value.rows.filter((_, j) => j !== i) })} />
            </div>
          </div>
        )) : <span className="t-body-s muted">{tr('Choose the base area first.', 'আগে "কোথা থেকে চলে" বেছে নিন।')}</span>}
        {district && value.rows.length < MAX_FARE_ROWS ? (
          <div>
            <Button variant="outline" small icon="add" onClick={() => set({ rows: [...value.rows, { dest: null, amount: '' }] })}>
              {tr('Add a destination', 'গন্তব্য যোগ করুন')}
            </Button>
          </div>
        ) : null}
        <span className="t-label-l muted">{tr('Extras (optional, added on top)', 'বাড়তি (ঐচ্ছিক, ভাড়ার সাথে যোগ হয়)')}</span>
        <div className="work-three">
          <AmountInput label={tr('Oxygen', 'অক্সিজেন')} value={value.oxygen} onChange={(v) => set({ oxygen: v })} />
          <AmountInput label={tr('ICU equipment', 'আইসিইউ যন্ত্র')} value={value.icu} onChange={(v) => set({ icu: v })} />
          <AmountInput label={tr('Night (10 pm–6 am)', 'রাতে (রাত ১০টা–সকাল ৬টা)')} value={value.night} onChange={(v) => set({ night: v })} />
        </div>
      </div>
    </Field>
  );
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
