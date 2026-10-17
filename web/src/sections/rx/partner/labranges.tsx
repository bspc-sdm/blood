// প্যাথলজি — নিজের মেশিনের স্বাভাবিক মাত্রা (lab_ranges_set): পরীক্ষা বেছে ঘরগুলোর নিচ/ওপর লিখে রাখা,
// বা ছকের মাত্রায় ফেরানো। পরের রিপোর্টের ফর্মে এটাই ভরা থাকে (অ্যাপ/প্যানেলে রিপোর্ট দেওয়ার সময়ের "আমার ল্যাবের জন্য রাখুন"-এর মতো)।
import { useMemo, useState } from 'react';
import { Button, ConfirmDialog, ErrorBox, ErrorText, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { isBangla, tr, trPlain } from '@/lib/i18n';
import { Panel, useWide } from './common';
import { defRangeText, isNumericParam, parseNum, type TestDef } from './model';

export function LabRanges() {
  const wide = useWide();
  const q = useCall<{ tests: TestDef[] }>('labtests_get', {}, { staleTime: 10 * 60_000 });
  const [find, setFind] = useState('');
  const [sel, setSel] = useState<string | null>(null);
  const [saved, setSaved] = useState<Record<string, 'own' | 'reset'>>({});
  const tests = useMemo(() => (q.data?.tests ?? []).filter((t) => !t.hidden && t.params.some(isNumericParam)), [q.data]);
  const t = find.trim().toLowerCase();
  const shown = t ? tests.filter((x) => [x.name, x.bn ?? '', x.code, ...(x.aliases ?? [])].some((s) => s.toLowerCase().includes(t))) : tests;
  const cur = tests.find((x) => x.code === sel) ?? (wide ? shown[0] : undefined);

  if (q.isLoading) return <SkeletonCards n={2} h={200} />;
  if (q.error) return <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />;

  const list = (
    <Panel title={tr('Tests', 'পরীক্ষা')} icon="list_alt">
      <div className="rxp-search">
        <Icon name="search" />
        <input className="input" value={find} onChange={(e) => setFind(e.target.value)} placeholder={tr('Find a test', 'পরীক্ষা খুঁজুন')} aria-label={tr('Find a test', 'পরীক্ষা খুঁজুন')} />
      </div>
      <ul className="rxp-tests" role="listbox" aria-label={tr('Tests', 'পরীক্ষা')}>
        {shown.map((x) => (
          <li key={x.code}>
            <button type="button" role="option" aria-selected={cur?.code === x.code} className={cx(cur?.code === x.code && 'on')} onClick={() => { setSel(x.code); if (!wide) window.scrollTo({ top: 0 }); }}>
              <span className="col gap-2 grow">
                <span className="semi">{x.name}</span>
                {isBangla() && x.bn ? <span className="t-body-s muted">{x.bn}</span> : null}
              </span>
              {saved[x.code] === 'own' ? <Tag tone="good">{tr('Saved', 'রাখা আছে')}</Tag> : null}
              <Icon name="chevron_right" />
            </button>
          </li>
        ))}
        {!shown.length ? <li className="t-body-m muted rxp-pad">{tr('No match', 'মেলেনি')}</li> : null}
      </ul>
    </Panel>
  );

  const intro = (
    <p className="t-body-s muted">
      {tr(
        "If your machine's normal range is different, write it here — the next report form will be filled with it (for both men and women). You can also change it while giving a report.",
        'আপনার মেশিনের স্বাভাবিক মাত্রা আলাদা হলে এখানে লিখে রাখুন — পরের রিপোর্টের ফর্মে এটাই ভরা থাকবে (নারী-পুরুষ দুজনের জন্যই)। রিপোর্ট দেওয়ার সময়ও বদলে রাখা যায়।',
      )}
    </p>
  );

  if (!wide) {
    return (
      <div className="col gap-12">
        {intro}
        {sel && cur ? (
          <>
            <div className="row">
              <Button variant="text" small icon="arrow_back" onClick={() => setSel(null)}>{tr('All tests', 'সব পরীক্ষা')}</Button>
            </div>
            <RangeEditor key={cur.code} test={cur} onSaved={(kind) => setSaved({ ...saved, [cur.code]: kind })} />
          </>
        ) : list}
      </div>
    );
  }
  return (
    <div className="col gap-12">
      {intro}
      <div className="rxp-grid ranges">
        {list}
        {cur ? <RangeEditor key={cur.code} test={cur} onSaved={(kind) => setSaved({ ...saved, [cur.code]: kind })} /> : <span />}
      </div>
    </div>
  );
}

function RangeEditor({ test, onSaved }: { test: TestDef; onSaved: (kind: 'own' | 'reset') => void }) {
  const params = test.params.filter(isNumericParam);
  const [vals, setVals] = useState<Record<string, { low: string; high: string }>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);
  const edited = params.filter((p) => vals[p.key] && (vals[p.key].low.trim() || vals[p.key].high.trim()));
  const ph = (p: TestDef['params'][number], i: 0 | 1) => {
    const r = p.r ?? p.m ?? p.f;
    const v = r ? r[i] : null;
    return v == null ? '' : String(v);
  };

  async function save() {
    const ranges: { code: string; key: string; low: number | null; high: number | null }[] = [];
    for (const p of edited) {
      const lo = parseNum(vals[p.key].low);
      const hi = parseNum(vals[p.key].high);
      if (Number.isNaN(lo) || Number.isNaN(hi) || (lo == null && hi == null) || (lo != null && hi != null && lo > hi)) {
        setErr(tr(`${p.name}: check the normal range`, `${p.name}: মাত্রা দেখে নিন`));
        return;
      }
      ranges.push({ code: test.code, key: p.key, low: lo, high: hi });
    }
    if (!ranges.length) return;
    setBusy(true);
    setErr(null);
    try {
      await call('lab_ranges_set', { ranges });
      setVals({});
      onSaved('own');
      toast(tr('Saved — the next report will have these', 'রাখা হয়েছে — পরের রিপোর্টে এটাই আসবে'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function reset() {
    setBusy(true);
    setErr(null);
    try {
      await call('lab_ranges_set', { ranges: params.map((p) => ({ code: test.code, key: p.key, low: null, high: null })) });
      setVals({});
      setResetting(false);
      onSaved('reset');
      toast(tr('Back to the standard ranges', 'ছকের মাত্রায় ফেরানো হয়েছে'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title={test.name} icon="tune">
      {isBangla() && test.bn ? <span className="t-body-s muted">{test.bn}</span> : null}
      <div className="table-wrap rxp-entry">
        <table className="table rxp-table">
          <thead>
            <tr>
              <th>{tr('Row', 'ঘর')}</th>
              <th>{tr('Standard range', 'ছকের মাত্রা')}</th>
              <th>{tr('My lab', 'আমার ল্যাব')}</th>
            </tr>
          </thead>
          <tbody>
            {params.map((p) => {
              const v = vals[p.key] ?? { low: '', high: '' };
              const set = (patch: Partial<{ low: string; high: string }>) => setVals({ ...vals, [p.key]: { ...v, ...patch } });
              return (
                <tr key={p.key}>
                  <td>
                    <div className="col gap-2">
                      <span className="semi">{p.name}</span>
                      {p.unit ? <span className="t-body-s muted">{p.unit}</span> : null}
                    </div>
                  </td>
                  <td className="t-body-s num">{defRangeText(p) || '—'}</td>
                  <td>
                    <div className="rxp-range">
                      <input className={cx('input rxp-cell num', Number.isNaN(parseNum(v.low)) && 'bad')} inputMode="decimal" maxLength={10} value={v.low} placeholder={ph(p, 0) || tr('Low', 'নিচে')} aria-label={`${p.name}: ${tr('Low', 'নিচে')}`} onChange={(e) => set({ low: e.target.value })} />
                      <span className="muted">–</span>
                      <input className={cx('input rxp-cell num', Number.isNaN(parseNum(v.high)) && 'bad')} inputMode="decimal" maxLength={10} value={v.high} placeholder={ph(p, 1) || tr('High', 'ওপরে')} aria-label={`${p.name}: ${tr('High', 'ওপরে')}`} onChange={(e) => set({ high: e.target.value })} />
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="t-body-s muted">{tr('Leave a row empty to keep it as it is.', 'যে ঘর খালি রাখবেন, সেটা আগের মতোই থাকবে।')}</p>
      <ErrorText>{err}</ErrorText>
      <div className="row wrap gap-8">
        <Button variant="text" small disabled={busy} onClick={() => setResetting(true)}>{tr('Back to standard ranges', 'ছকের মাত্রায় ফেরান')}</Button>
        <span className="grow" />
        <Button busy={busy} disabled={!edited.length} onClick={save}>{tr('Keep for my lab', 'আমার ল্যাবের জন্য রাখুন')}</Button>
      </div>
      {resetting ? (
        <ConfirmDialog
          title={tr('Use the standard ranges?', 'ছকের মাত্রায় ফেরাবেন?')}
          text={trPlain(`Your own ranges for ${test.name} will be removed.`, `${test.name}-এর জন্য রাখা আপনার নিজের মাত্রা মুছে যাবে।`)}
          confirm={tr('Back to standard', 'ছকের মাত্রায় ফেরান')}
          busy={busy}
          error={err}
          onConfirm={reset}
          onClose={() => setResetting(false)}
        />
      ) : null}
    </Panel>
  );
}
