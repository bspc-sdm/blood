// প্যাথলজি — একটা পরীক্ষার অনুরোধ: অবস্থা বদল, রিপোর্ট দেওয়া (ছকের ঘর ভরা — শুধু ফল লিখুন; বেশি/কম নিজে থেকে),
// ছবি, নোট; দেওয়া হলে রিপোর্ট দেখা ও ছাপা (অ্যাপের LabReportEntryDialog, LabReportView; প্যানেলের reportForm)
import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, ConfirmDialog, ErrorBox, ErrorText, IconButton, SkeletonCards, Switch, Tag, TextArea, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, trPlain } from '@/lib/i18n';
import { formatDateTime, formatDayOf } from '@/lib/format';
import { compressDocument } from '../../work/shared';
import { Panel, focusNext, useInvalidate, useWide } from './common';
import {
  ageText, autoFlag, flagArrow, flagText, genderText, labStatusText, labTone, parseNum, rangeText,
  type HistoryPoint, type LabForm, type LabOrder, type LabResult,
} from './model';

const BASE = '/work/lab/orders';

export function LabDetail({ id, orders, ordersLoading }: { id: string; orders: LabOrder[] | null; ordersLoading: boolean }) {
  const fromList = orders?.find((o) => o.id === id) ?? null;
  // তালিকায় না থাকলে (পুরোনো) সরাসরি আনা
  const direct = useCall<{ item: LabOrder; history: Record<string, HistoryPoint[]> }>('lab_order_get', { id }, { enabled: !ordersLoading && !fromList });
  const o = fromList ?? direct.data?.item ?? null;
  if (!o) {
    if (ordersLoading || direct.isLoading) return <SkeletonCards n={2} h={180} />;
    return <ErrorBox error={direct.error ? errorText(direct.error) : tr('Not found', 'পাওয়া যায়নি')} />;
  }
  return <OrderView o={o} />;
}

function OrderView({ o }: { o: LabOrder }) {
  const invalidate = useInvalidate();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const active = o.status === 'sent' || o.status === 'received';

  async function setStatus(status: 'received' | 'cancelled') {
    setBusy(true);
    setErr(null);
    try {
      await call('lab_order_status', { id: o.id, status });
      await invalidate('lab_orders');
      setCancelling(false);
      toast(status === 'cancelled' ? tr('The patient was told', 'রোগীকে জানানো হয়েছে') : tr('Saved', 'সেভ হয়েছে'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="col gap-16">
      <div className="row">
        <Link to={BASE} className="btn text small has-icon rxp-back"><Icon name="arrow_back" />{tr('All requests', 'সব অনুরোধ')}</Link>
      </div>
      <section className="card rxp-panel">
        <div className="rxp-rx-head">
          <div className="col gap-4 grow">
            <div className="row wrap gap-8">
              <span className="t-title-l">{o.patientName || '—'}</span>
              <Tag tone={labTone(o.status)}>{labStatusText(o.status)}</Tag>
            </div>
            <span className="t-body-m muted">{[ageText(o.patientAge), genderText(o.patientGender), o.doctorName].filter(Boolean).join(' · ')}</span>
            <span className="t-body-s muted">{tr('Came ', 'এসেছে ')}{formatDateTime(o.createdAt)}</span>
          </div>
          {o.code ? <span className="rxp-code-chip num">{o.code}</span> : null}
        </div>
        <div className="row wrap gap-6">
          {o.tests.map((t) => <Tag key={t} tone="primary">{t}</Tag>)}
        </div>
        {active ? (
          <div className="row wrap gap-8">
            {o.status === 'sent' ? <Button variant="tonal" icon="check_circle" busy={busy} onClick={() => setStatus('received')}>{tr('Sample taken', 'নমুনা নিয়েছি')}</Button> : null}
            <Button variant="danger-text" disabled={busy} onClick={() => setCancelling(true)}>{tr("Can't do it", 'করতে পারব না')}</Button>
          </div>
        ) : null}
        <ErrorText>{err}</ErrorText>
      </section>

      {active ? <ReportEntry key={o.id} o={o} /> : o.status === 'reported' ? <ReportPane o={o} /> : null}

      {cancelling ? (
        <ConfirmDialog
          title={tr("Can't do these tests?", 'পরীক্ষাগুলো করতে পারবেন না?')}
          text={tr('The patient will be told and can send them to another lab.', 'রোগী জানবেন, অন্য প্যাথলজিতে পাঠাতে পারবেন।')}
          confirm={tr("Can't do it", 'করতে পারব না')}
          danger
          busy={busy}
          error={err}
          onConfirm={() => setStatus('cancelled')}
          onClose={() => setCancelling(false)}
        />
      ) : null}
    </div>
  );
}

// ---------------- রিপোর্ট দেওয়া ----------------
interface Row {
  id: number; test: string; result: string; unit: string; range: string; flag: string;
  group: string; code: string | null; key: string | null; fixed: boolean; numeric: boolean; options: string[]; long: boolean;
  lowText: string; highText: string; base: [number | null, number | null];
}
let seq = 0;
const freeRow = (test = ''): Row => ({
  id: ++seq, test, result: '', unit: '', range: '', flag: '', group: '', code: null, key: null, fixed: false, numeric: false, options: [], long: false, lowText: '', highText: '', base: [null, null],
});
const fmt = (v: number | null) => (v == null ? '' : String(v));

function rowsFrom(forms: LabForm[]): Row[] {
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
const lowOf = (r: Row) => parseNum(r.lowText);
const highOf = (r: Row) => parseNum(r.highText);
function flagOf(r: Row): string {
  if (!r.numeric) return r.flag;
  const lo = lowOf(r);
  const hi = highOf(r);
  const a = autoFlag(r.result, Number.isNaN(lo) ? null : lo, Number.isNaN(hi) ? null : hi);
  return a ?? r.flag;
}

function ReportEntry({ o }: { o: LabOrder }) {
  const wide = useWide();
  const invalidate = useInvalidate();
  const form = useCall<{ forms: LabForm[] }>('lab_form', { id: o.id }, { staleTime: Infinity, retry: false });
  const [rows, setRows] = useState<Row[] | null>(null);
  const [keep, setKeep] = useState(false);
  const [note, setNote] = useState('');
  const [photos, setPhotos] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [sent, setSent] = useState(false);

  // ছক এলে (না এলে প্রতিটা পরীক্ষার একটা খালি সারি) — একবারই
  useEffect(() => {
    if (rows) return;
    if (form.data) setRows(rowsFrom(form.data.forms ?? []));
    else if (form.error) setRows(o.tests.map((t) => freeRow(t)));
  }, [form.data, form.error, rows, o.tests]);

  const filled = (rows ?? []).filter((r) => r.test.trim() && r.result.trim());
  const dirty = filled.length > 0 || photos.length > 0 || !!note.trim();
  useEffect(() => {
    if (!dirty) return;
    const f = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', f);
    return () => window.removeEventListener('beforeunload', f);
  }, [dirty]);

  if (!rows || sent) return <SkeletonCards n={1} h={320} />;
  const set = (id: number, patch: Partial<Row>) => setRows((rs) => (rs ?? []).map((r) => (r.id === id ? { ...r, ...patch } : r)));

  function check(): string | null {
    const bad = (rows ?? []).find((r) => r.numeric && (Number.isNaN(lowOf(r)) || Number.isNaN(highOf(r)) || ((lowOf(r) ?? -Infinity) > (highOf(r) ?? Infinity))));
    if (bad) return tr(`${bad.test}: check the normal range`, `${bad.test}: মাত্রা দেখে নিন`);
    if (!filled.length && !photos.length) return tr('Enter at least one result or a photo of the report', 'অন্তত একটা ফল বা রিপোর্টের ছবি দিন');
    return null;
  }
  async function send() {
    const all = rows ?? [];
    setBusy(true);
    setErr(null);
    try {
      if (keep) {
        const changed = all.filter((r) => r.fixed && r.numeric && r.code && r.key && (lowOf(r) !== r.base[0] || highOf(r) !== r.base[1]));
        if (changed.length) await call('lab_ranges_set', { ranges: changed.map((r) => ({ code: r.code, key: r.key, low: lowOf(r), high: highOf(r) })) });
      }
      const items: LabResult[] = filled.map((r) => {
        const lo = r.numeric ? lowOf(r) : null;
        const hi = r.numeric ? highOf(r) : null;
        return {
          test: r.test.trim(), result: r.result.trim(), unit: r.unit.trim(), range: r.numeric ? rangeText(lo, hi) : r.range.trim(), flag: flagOf(r),
          ...(r.group && r.code && r.key ? { group: r.group, code: r.code, key: r.key } : {}),
          ...(lo != null ? { low: lo } : {}), ...(hi != null ? { high: hi } : {}),
          ...(r.long ? { long: true } : {}),
        };
      });
      await call('lab_report', { id: o.id, items, note: note.trim(), photos });
      setConfirming(false);
      setSent(true);
      toast(tr('Report sent to the patient', 'রোগীর কাছে রিপোর্ট গেছে'));
      await invalidate('lab_orders', 'lab_order_get');
    } catch (e) {
      setErr(errorText(e));
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }
  function trySend() {
    const e = check();
    setErr(e);
    if (!e) setConfirming(true);
  }

  // পরীক্ষা ধরে ভাগ (CBC, লিপিড…); নিজে লেখা সারি শেষে
  const groups: { name: string; rows: Row[] }[] = [];
  for (const r of rows) {
    const g = r.fixed ? r.group : '';
    const last = groups[groups.length - 1];
    if (last && last.name === g) last.rows.push(r);
    else groups.push({ name: g, rows: [r] });
  }
  const hasFixed = rows.some((r) => r.fixed);
  const hasNumeric = rows.some((r) => r.numeric);

  const resultInput = (r: Row) =>
    r.long ? (
      <textarea
        className="textarea rxp-long"
        value={r.result}
        maxLength={2000}
        rows={3}
        aria-label={`${r.test}: ${tr('Findings', 'ফলাফল')}`}
        placeholder={tr('Findings', 'ফলাফল')}
        onChange={(e) => set(r.id, { result: e.target.value })}
      />
    ) : (
      <>
        <input
          className={cx('input rxp-cell', r.numeric && 'num')}
          value={r.result}
          maxLength={60}
          inputMode={r.numeric ? 'decimal' : undefined}
          list={r.options.length ? `rxp-opt-${r.id}` : undefined}
          data-rxp-nav="result"
          aria-label={`${r.test || tr('Test', 'পরীক্ষা')}: ${tr('Result', 'ফল')}`}
          placeholder={r.options.length ? r.range || tr('Choose', 'বাছুন') : tr('Result', 'ফল')}
          onChange={(e) => set(r.id, { result: e.target.value })}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              focusNext(e.currentTarget, 'result');
            }
          }}
        />
        {r.options.length ? (
          <datalist id={`rxp-opt-${r.id}`}>
            {r.options.map((x) => <option key={x} value={x} />)}
          </datalist>
        ) : null}
      </>
    );
  const unitCell = (r: Row) =>
    r.fixed ? <span className="t-body-s muted">{r.unit}</span> : (
      <input className="input rxp-cell rxp-unit" value={r.unit} maxLength={30} aria-label={tr('Unit', 'একক')} placeholder={tr('Unit', 'একক')} onChange={(e) => set(r.id, { unit: e.target.value })} />
    );
  const rangeCell = (r: Row) =>
    r.numeric ? (
      <div className="rxp-range">
        <input className={cx('input rxp-cell num', Number.isNaN(lowOf(r)) && 'bad')} inputMode="decimal" value={r.lowText} maxLength={10} aria-label={`${r.test}: ${tr('Low', 'নিচে')}`} placeholder={tr('Low', 'নিচে')} onChange={(e) => set(r.id, { lowText: e.target.value })} />
        <span className="muted">–</span>
        <input className={cx('input rxp-cell num', Number.isNaN(highOf(r)) && 'bad')} inputMode="decimal" value={r.highText} maxLength={10} aria-label={`${r.test}: ${tr('High', 'ওপরে')}`} placeholder={tr('High', 'ওপরে')} onChange={(e) => set(r.id, { highText: e.target.value })} />
      </div>
    ) : r.long ? null : (
      <input className="input rxp-cell" value={r.range} maxLength={60} aria-label={`${r.test}: ${tr('Normal range', 'স্বাভাবিক মাত্রা')}`} placeholder={tr('Normal range', 'স্বাভাবিক মাত্রা')} onChange={(e) => set(r.id, { range: e.target.value })} />
    );
  const flagCell = (r: Row) => {
    if (r.long) return null;
    if (r.numeric) {
      const f = flagOf(r);
      return f ? <Tag tone="error">{flagText(f)}</Tag> : <span className="muted">—</span>;
    }
    return (
      <select className="select rxp-cell rxp-flag" value={r.flag} aria-label={`${r.test}: ${tr('High/low', 'বেশি/কম')}`} onChange={(e) => set(r.id, { flag: e.target.value })}>
        <option value="">—</option>
        <option value="high">{flagText('high')}</option>
        <option value="low">{flagText('low')}</option>
        <option value="abnormal">{flagText('abnormal')}</option>
      </select>
    );
  };
  const nameCell = (r: Row) =>
    r.fixed ? <span className="semi">{r.test}</span> : (
      <div className="row gap-4">
        <input className="input rxp-cell" value={r.test} maxLength={80} aria-label={tr('Test', 'পরীক্ষা')} placeholder={tr('Test', 'পরীক্ষা')} onChange={(e) => set(r.id, { test: e.target.value })} />
        <IconButton small icon="close" label={tr('Remove row', 'সারি সরান')} onClick={() => setRows((rs) => (rs ?? []).filter((x) => x.id !== r.id))} />
      </div>
    );

  return (
    <Panel title={tr('Report', 'রিপোর্ট')} icon="edit_note">
      {hasFixed ? (
        <p className="t-body-s muted">
          {tr('Rows, units and normal ranges are filled in — just write the results. High/low is marked by itself. Change a range if your machine is different.', 'ঘর, একক আর স্বাভাবিক মাত্রা ভরা আছে — শুধু ফল লিখুন। বেশি/কম নিজে থেকে বসবে। আপনার মেশিনের মাত্রা আলাদা হলে বদলে নিন।')}
        </p>
      ) : null}
      {form.error ? <p className="t-body-s muted">{tr("Couldn't load the form — write the results yourself.", 'ছক আনা যায়নি — ফল নিজে লিখুন।')}</p> : null}

      {wide ? (
        <div className="col gap-12">
          {groups.map((g, gi) => (
            <div key={gi} className="table-wrap rxp-entry">
              {g.name ? <div className="rxp-group">{g.name}</div> : null}
              <table className="table rxp-table">
                <thead>
                  <tr>
                    <th className="rxp-col-test">{tr('Test', 'পরীক্ষা')}</th>
                    <th className="rxp-col-result">{tr('Result', 'ফল')}</th>
                    <th className="rxp-col-unit">{tr('Unit', 'একক')}</th>
                    <th className="rxp-col-range">{tr('Normal range', 'স্বাভাবিক মাত্রা')}</th>
                    <th className="rxp-col-flag">{tr('High/low', 'বেশি/কম')}</th>
                  </tr>
                </thead>
                <tbody>
                  {g.rows.map((r) => (
                    <tr key={r.id}>
                      <td>{nameCell(r)}</td>
                      {r.long ? <td colSpan={4}>{resultInput(r)}</td> : (
                        <>
                          <td>{resultInput(r)}</td>
                          <td>{unitCell(r)}</td>
                          <td>{rangeCell(r)}</td>
                          <td>{flagCell(r)}</td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      ) : (
        <div className="col gap-8">
          {groups.map((g, gi) => (
            <Fragment key={gi}>
              {g.name ? <div className="rxp-group flat">{g.name}</div> : null}
              {g.rows.map((r) => (
                <div key={r.id} className="rxp-entry-card">
                  <div className="row">
                    <div className="grow">{nameCell(r)}</div>
                    {r.numeric ? flagCell(r) : null}
                  </div>
                  <div className="row gap-8">
                    <div className="grow">{resultInput(r)}</div>
                    {!r.long ? unitCell(r) : null}
                  </div>
                  {!r.long ? (
                    <div className="row wrap gap-8">
                      <span className="t-body-s muted">{tr('Normal', 'স্বাভাবিক')}</span>
                      <div className="grow">{rangeCell(r)}</div>
                      {!r.numeric ? flagCell(r) : null}
                    </div>
                  ) : null}
                </div>
              ))}
            </Fragment>
          ))}
        </div>
      )}
      <div className="row">
        <Button variant="text" small icon="add" onClick={() => setRows([...rows, freeRow()])}>{tr('Add a row', 'আরেকটা সারি')}</Button>
      </div>
      {hasNumeric ? (
        <Switch checked={keep} onChange={setKeep} label={tr('Keep changed ranges for my lab', 'বদলানো মাত্রা আমার ল্যাবের জন্য রাখুন')} sub={tr('The next report form will have these', 'পরের রিপোর্টে এটাই আসবে')} />
      ) : null}
      <div className="rxp-two">
        <TextArea label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} value={note} maxLength={500} rows={3} onChange={(e) => setNote(e.target.value)} />
        <ReportPhotos photos={photos} onChange={setPhotos} />
      </div>
      <ErrorText>{err}</ErrorText>
      <div className="row wrap gap-8">
        <span className="t-body-s muted grow">{filled.length ? tr(`${filled.length} results written`, `${num(filled.length)}টা ফল লেখা হয়েছে`) : ''}</span>
        <Button icon="send" busy={busy} onClick={trySend}>{tr('Send report', 'রিপোর্ট পাঠান')}</Button>
      </div>
      {confirming ? (
        <ConfirmDialog
          title={tr('Send the report?', 'রিপোর্ট পাঠাবেন?')}
          text={trPlain(
            `It goes to ${o.patientName || 'the patient'} right away${filled.some((r) => flagOf(r)) ? ', with high/low marked' : ''}. It can't be changed after sending.`,
            `${o.patientName || 'রোগী'}-এর কাছে সাথে সাথে যাবে${filled.some((r) => flagOf(r)) ? ' (বেশি/কম চিহ্নসহ)' : ''}। পাঠানোর পর আর বদলানো যায় না।`,
          )}
          confirm={tr('Send', 'পাঠান')}
          busy={busy}
          onConfirm={send}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </Panel>
  );
}

/** রিপোর্টের ছবি (ঐচ্ছিক, সর্বোচ্চ ২): ক্যানভাসে ছোট করে JPEG */
function ReportPhotos({ photos, onChange }: { photos: string[]; onChange: (p: string[]) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function picked(f: File | undefined) {
    if (!f) return;
    setBusy(true);
    setErr(null);
    try {
      onChange([...photos, await compressDocument(f)].slice(0, 2));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="col gap-8">
      <span className="t-label-l">{tr('Report photos', 'রিপোর্টের ছবি')}</span>
      <span className="t-body-s muted">{tr('Photos of the printed report, if you like (up to 2).', 'চাইলে ছাপানো রিপোর্টের ছবি দিন (সর্বোচ্চ ২টা)।')}</span>
      <input ref={input} type="file" accept="image/*" hidden onChange={(e) => { picked(e.target.files?.[0]); e.target.value = ''; }} />
      <div className="row wrap gap-8">
        {photos.map((p, i) => (
          <div key={i} className="rxp-thumb">
            <img src={`data:image/jpeg;base64,${p}`} alt={tr(`Report photo ${i + 1}`, `রিপোর্টের ছবি ${num(i + 1)}`)} />
            <IconButton small icon="close" label={tr('Remove', 'সরান')} onClick={() => onChange(photos.filter((_, j) => j !== i))} />
          </div>
        ))}
        {photos.length < 2 ? (
          <Button variant="outline" icon="photo_camera" busy={busy} onClick={() => input.current?.click()}>{tr('Add photo', 'ছবি দিন')}</Button>
        ) : null}
      </div>
      <ErrorText>{err}</ErrorText>
    </div>
  );
}

// ---------------- দেওয়া রিপোর্ট ----------------
function ReportPane({ o }: { o: LabOrder }) {
  const q = useCall<{ item: LabOrder; history: Record<string, HistoryPoint[]> }>('lab_order_get', { id: o.id });
  if (q.isLoading) return <SkeletonCards n={1} h={260} />;
  if (q.error || !q.data) return <ErrorBox error={q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')} onRetry={() => q.refetch()} />;
  // মাথার তথ্য তালিকা থেকেই; ফল, ছবি আর আগের ফল পুরো রিপোর্ট থেকে
  return <ReportView o={{ ...o, report: q.data.item.report ?? o.report }} history={q.data.history ?? {}} />;
}

export function ReportView({ o, history }: { o: LabOrder; history: Record<string, HistoryPoint[]> }) {
  const r = o.report;
  const groups = useMemo(() => {
    const out: { name: string; rows: LabResult[] }[] = [];
    for (const i of r?.items ?? []) {
      const g = i.group || '';
      const last = out[out.length - 1];
      if (last && last.name === g) last.rows.push(i);
      else out.push({ name: g, rows: [i] });
    }
    return out;
  }, [r]);
  if (!r) return null;
  const prev = (i: LabResult) => {
    const list = i.code && i.key ? history[`${i.code}.${i.key}`] ?? [] : [];
    return list.slice(0, 3).map((h) => `${num(h.result)}${flagArrow(h.flag)} (${formatDayOf(h.at)})`).join(', ');
  };
  const hasHist = (r.items ?? []).some((i) => prev(i));
  return (
    <section className="card rxp-panel rxp-print">
      <div className="rxp-rx-head">
        <div className="col gap-4 grow">
          <span className="t-title-l">{o.labName}</span>
          <span className="t-body-m">{[o.patientName, ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ')}</span>
          <span className="t-body-s muted">{[o.doctorName, r.reportedAt ? tr('Report: ', 'রিপোর্ট: ') + formatDateTime(r.reportedAt) : ''].filter(Boolean).join(' · ')}</span>
        </div>
        <Button variant="tonal" icon="share" className="rxp-noprint" onClick={() => window.print()}>{tr('Print', 'ছাপুন')}</Button>
      </div>
      {groups.map((g, gi) => (
        <div key={gi} className="table-wrap rxp-entry">
          {g.name ? <div className="rxp-group">{g.name}</div> : null}
          <table className="table rxp-table rxp-report">
            <thead>
              <tr>
                <th>{tr('Test', 'পরীক্ষা')}</th>
                <th>{tr('Result', 'ফল')}</th>
                <th>{tr('Normal', 'স্বাভাবিক')}</th>
                <th><span className="sr-only">{tr('High/low', 'বেশি/কম')}</span></th>
                {hasHist ? <th>{tr('Before', 'আগে')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {g.rows.map((i, k) =>
                i.long ? (
                  <tr key={k}>
                    <td className="semi">{i.test}</td>
                    <td colSpan={hasHist ? 4 : 3} className="pre">{i.result}</td>
                  </tr>
                ) : (
                  <tr key={k}>
                    <td className="semi">{i.test}</td>
                    <td className={cx('num rxp-nowrap', i.flag && 'c-error semi')}>{i.result}{i.unit ? <span className="muted"> {i.unit}</span> : null}</td>
                    <td className="t-body-s muted">{i.range || '—'}</td>
                    <td>{i.flag ? <Tag tone="error">{flagText(i.flag)}</Tag> : null}</td>
                    {hasHist ? <td className="t-body-s muted">{prev(i) || '—'}</td> : null}
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      ))}
      {r.note ? <p className="t-body-m pre">{r.note}</p> : null}
      {(r.photos ?? []).length ? (
        <div className="rxp-photos">
          {(r.photos ?? []).map((p, i) => <img key={i} src={`data:image/jpeg;base64,${p}`} alt={tr('Report photo', 'রিপোর্টের ছবি')} />)}
        </div>
      ) : null}
    </section>
  );
}
