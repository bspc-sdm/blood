// প্যাথলজি — পরীক্ষা ও দাম (/work/lab/orders/prices, শুধু ওয়েবে — বসে করার কাজ): ছক থেকে পরীক্ষা খুঁজে যোগ বা নিজে লিখে,
// প্রতিটার দাম (৳), রিপোর্ট কতক্ষণে, এখন হয় কি না; সব পরীক্ষায় ছাড় (%), বাসা থেকে নমুনার চার্জ।
// রোগীরা সেবার তালিকায় আর প্রেসক্রিপশন পাঠানোর সময় দেখেন কোথায় কোন পরীক্ষা কত টাকা (server/lib/labprices.js)।
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, ErrorBox, ErrorText, IconButton, SkeletonCards, TextInput, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { isBangla, num, tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { taka } from '@/lib/labprice';
import { Panel, useInvalidate, useWide } from './common';
import { parseNum, type LabPriceItem, type LabPriceList, type TestDef } from './model';

interface Row { id: number; code: string | null; name: string; price: string; tat: string; on: boolean; hidden?: boolean }
let seq = 0;
const rowOf = (i: LabPriceItem): Row => ({ id: ++seq, code: i.code, name: i.name, price: String(i.price), tat: i.tat || '', on: i.on !== false, hidden: i.hidden === true });
const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9ঀ-৿]+/g, ' ').trim();
const TAT = () => [tr('30 minutes', '৩০ মিনিট'), tr('1 hour', '১ ঘণ্টা'), tr('Same day', 'একই দিন'), tr('24 hours', '২৪ ঘণ্টা'), tr('2 days', '২ দিন'), tr('3 days', '৩ দিন')];
/** দাম: পূর্ণ সংখ্যা ১–২,০০,০০০ (বাংলা অঙ্ক চলে); খালি null, ভুল NaN */
function priceOf(s: string): number | null {
  const v = parseNum(s.replace(/[৳\s]/g, ''));
  if (v == null) return null;
  return Number.isInteger(v) && v >= 1 && v <= 200000 ? v : NaN;
}

export function LabPrices() {
  const q = useCall<LabPriceList>('lab_prices_get', {}, { staleTime: 0 });
  const cat = useCall<{ tests: TestDef[] }>('labtests_get', {}, { staleTime: 10 * 60_000 });
  if (q.isLoading) return <SkeletonCards n={2} h={220} />;
  if (q.error || !q.data) return <ErrorBox error={q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')} onRetry={() => q.refetch()} />;
  return <Editor key={q.data.updatedAt} data={q.data} tests={(cat.data?.tests ?? []).filter((t) => !t.hidden)} />;
}

function Editor({ data, tests }: { data: LabPriceList; tests: TestDef[] }) {
  const wide = useWide();
  const invalidate = useInvalidate();
  const [rows, setRows] = useState<Row[]>(() => data.items.map(rowOf));
  const [disc, setDisc] = useState(data.discountPct ? String(data.discountPct) : '');
  const [fee, setFee] = useState(data.homeFee != null ? String(data.homeFee) : '');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [tried, setTried] = useState(false);
  const bn = useMemo(() => new Map(tests.map((t) => [t.code, t.bn ?? ''])), [tests]);

  useEffect(() => {
    if (!dirty) return;
    const f = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', f);
    return () => window.removeEventListener('beforeunload', f);
  }, [dirty]);

  const change = (next: Row[]) => {
    setRows(next);
    setDirty(true);
  };
  const set = (id: number, patch: Partial<Row>) => change(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  const has = (code: string | null, name: string) => rows.some((r) => (code ? r.code === code : !r.code && norm(r.name) === norm(name)));
  function add(code: string | null, name: string) {
    if (has(code, name)) return;
    change([{ id: ++seq, code, name, price: '', tat: '', on: true }, ...rows]);
    setTimeout(() => document.querySelector<HTMLInputElement>('[data-lp-price]')?.focus(), 30);
  }

  async function save() {
    setTried(true);
    const bad = rows.find((r) => priceOf(r.price) == null || Number.isNaN(priceOf(r.price)));
    if (bad) {
      setErr(priceOf(bad.price) == null ? tr(`${bad.name}: write the price`, `${bad.name}: দাম লিখুন`) : tr(`${bad.name}: check the price`, `${bad.name}: দাম দেখে নিন`));
      return;
    }
    const d = parseNum(disc);
    const h = parseNum(fee);
    if (Number.isNaN(d) || (d != null && (d < 0 || d > 90 || !Number.isInteger(d)))) return setErr(tr('Discount must be 0–90%', 'ছাড় ০–৯০% এর মধ্যে দিন'));
    if (Number.isNaN(h) || (h != null && (h < 0 || h > 5000 || !Number.isInteger(h)))) return setErr(tr('Check the home collection fee', 'বাসা থেকে নমুনার চার্জ দেখে নিন'));
    setBusy(true);
    setErr(null);
    try {
      // ছকে লুকানো পরীক্ষা: কোডের সাথে নামও — সার্ভার নাম দিয়ে নিজে লেখা হিসেবে রাখে (সেভ আটকায় না)
      const out = await call<LabPriceList>('lab_prices_set', {
        items: rows.map((r) => ({ ...(r.code ? { code: r.code, ...(r.hidden ? { name: r.name } : {}) } : { name: r.name }), price: priceOf(r.price), tat: r.tat.trim(), on: r.on })),
        discountPct: d, homeFee: data.homeCollection ? h : null,
      });
      setDirty(false);
      toast(out.kept?.length
        ? tr(`Saved. ${out.kept.join(', ')} is no longer in the test list — kept as your own test`, `সেভ হয়েছে। ${out.kept.join(', ')} এখন পরীক্ষার ছকে নেই — নিজে লেখা পরীক্ষা হিসেবে রাখা হলো`)
        : tr('Saved — patients see these prices now', 'সেভ হয়েছে — রোগীরা এখন এই দাম দেখবেন'));
      await invalidate('lab_prices_get');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const on = rows.filter((r) => r.on).length;
  const badPrice = (r: Row) => tried && (priceOf(r.price) == null || Number.isNaN(priceOf(r.price)));
  const nameCell = (r: Row) => (
    <div className="col gap-2">
      <span className={cx('semi', !r.on && 'muted')}>{r.name}</span>
      {r.hidden ? (
        <span className="t-body-s c-error">{tr('Removed from the test list — patients don\'t see it; save to keep it as your own test', 'পরীক্ষার ছক থেকে সরানো — রোগীরা দেখেন না; সেভ করলে নিজে লেখা হিসেবে থাকবে')}</span>
      ) : isBangla() && r.code && bn.get(r.code) ? <span className="t-body-s muted">{bn.get(r.code)}</span> : !r.code ? <span className="t-body-s muted">{tr('Own test', 'নিজে লেখা')}</span> : null}
    </div>
  );
  const priceInput = (r: Row, i: number) => (
    <input
      className={cx('input rxp-cell num lp-price', badPrice(r) && 'bad')}
      inputMode="numeric"
      maxLength={8}
      value={r.price}
      placeholder="৳"
      data-lp-price={i === 0 ? '' : undefined}
      aria-label={`${r.name}: ${tr('Price (Tk)', 'দাম (৳)')}`}
      onChange={(e) => set(r.id, { price: e.target.value })}
    />
  );
  const tatInput = (r: Row) => (
    <input className="input rxp-cell lp-tat" list="lp-tat" maxLength={30} value={r.tat} placeholder={tr('e.g. Same day', 'যেমন: একই দিন')} aria-label={`${r.name}: ${tr('Report in', 'রিপোর্ট কতক্ষণে')}`} onChange={(e) => set(r.id, { tat: e.target.value })} />
  );
  const onSwitch = (r: Row) => (
    <label className="lp-on">
      <input type="checkbox" checked={r.on} onChange={(e) => set(r.id, { on: e.target.checked })} />
      <span>{r.on ? tr('Done here', 'হয়') : tr('Not now', 'এখন হয় না')}</span>
    </label>
  );
  const remove = (r: Row) => <IconButton small icon="close" label={tr('Remove', 'সরান')} onClick={() => change(rows.filter((x) => x.id !== r.id))} />;

  const list = (
    <Panel title={tr('Tests & prices', 'পরীক্ষা ও দাম')} icon="payments" count={rows.length ? tr(`${on} tests`, `${num(on)}টা পরীক্ষা`) : undefined}>
      <datalist id="lp-tat">{TAT().map((t) => <option key={t} value={t} />)}</datalist>
      {!rows.length ? (
        <div className="rxp-empty-card">
          <Icon name="payments" />
          <span>{tr('No tests yet — add from the list', 'এখনো কোনো পরীক্ষা নেই — তালিকা থেকে যোগ করুন')}</span>
        </div>
      ) : wide ? (
        <div className="table-wrap rxp-entry">
          <table className="table rxp-table lp-table">
            <thead>
              <tr>
                <th>{tr('Test', 'পরীক্ষা')}</th>
                <th>{tr('Price (Tk)', 'দাম (৳)')}</th>
                <th>{tr('Report in', 'রিপোর্ট কতক্ষণে')}</th>
                <th>{tr('Now', 'এখন')}</th>
                <th><span className="sr-only">{tr('Remove', 'সরান')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id} className={cx(!r.on && 'rxp-done')}>
                  <td>{nameCell(r)}</td>
                  <td>{priceInput(r, i)}</td>
                  <td>{tatInput(r)}</td>
                  <td>{onSwitch(r)}</td>
                  <td className="rxp-td-actions">{remove(r)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="col gap-8">
          {rows.map((r, i) => (
            <div key={r.id} className="rxp-entry-card">
              <div className="row top">
                <div className="grow">{nameCell(r)}</div>
                {remove(r)}
              </div>
              <div className="row gap-8">
                <div className="lp-price-wrap">{priceInput(r, i)}</div>
                <div className="grow">{tatInput(r)}</div>
              </div>
              {onSwitch(r)}
            </div>
          ))}
        </div>
      )}
      <ErrorText>{err}</ErrorText>
      <div className="row wrap gap-8">
        <span className="t-body-s muted grow">{data.updatedAt ? tr('Last saved ', 'শেষ সেভ ') + timeAgo(data.updatedAt) : ''}</span>
        <Button icon="check_circle" busy={busy} disabled={!dirty} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
    </Panel>
  );

  const addPanel = <AddTests tests={tests} has={has} onAdd={add} suggest={wide} />;
  const settings = (
      <Panel title={tr('Discount & home fee', 'ছাড় ও বাসার চার্জ')} icon="tune">
        <TextInput label={tr('Discount on all tests (%)', 'সব পরীক্ষায় ছাড় (%)')} inputMode="numeric" maxLength={2} value={disc} placeholder="০" onChange={(e) => { setDisc(e.target.value); setDirty(true); }} />
        {data.homeCollection ? (
          <TextInput label={tr('Sample from home — fee (Tk)', 'বাসা থেকে নমুনা — চার্জ (৳)')} inputMode="numeric" maxLength={5} value={fee} placeholder="৳" onChange={(e) => { setFee(e.target.value); setDirty(true); }} />
        ) : (
          <div className="row wrap gap-8">
            <span className="t-body-s muted grow">{tr('Sample from home is off', 'বাসা থেকে নমুনা বন্ধ')}</span>
            <Link to="/work/lab/edit" className="btn text small">{tr('Turn on', 'চালু করুন')}</Link>
          </div>
        )}
        {data.homeCollection && parseNum(fee) === 0 ? <span className="t-body-s muted">{tr('Patients see: free', 'রোগীরা দেখবেন: ফ্রি')}</span> : null}
        {parseNum(disc) ? <span className="t-body-s muted">{tr(`e.g. ${taka(1000)} → ${taka(1000 - 10 * (parseNum(disc) ?? 0))}`, `যেমন ${taka(1000)} → ${taka(1000 - 10 * (parseNum(disc) ?? 0))}`)}</span> : null}
      </Panel>
  );

  // চওড়া পর্দা: বাঁয়ে তালিকা, ডানে যোগ আর ছাড়; মোবাইলে খোঁজা → তালিকা → ছাড়
  return (
    <div className="rxp-grid">
      {wide ? (
        <><div className="rxp-main">{list}</div><div className="rxp-side col gap-16">{addPanel}{settings}</div></>
      ) : <>{addPanel}{list}{settings}</>}
    </div>
  );
}

/** ছক থেকে পরীক্ষা খোঁজা (নাম, বাংলা নাম, ডাক্তারের লেখা অন্য নাম) — না পেলে নিজে লিখে যোগ */
function AddTests({ tests, has, onAdd, suggest }: { tests: TestDef[]; has: (code: string | null, name: string) => boolean; onAdd: (code: string | null, name: string) => void; suggest: boolean }) {
  const [q, setQ] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const t = norm(q);
  const found = useMemo(
    // মোবাইলে লিখলে তবেই মিল দেখায় (তালিকা নিচে চাপা না পড়ে)
    () => (t ? tests.filter((x) => [x.name, x.bn ?? '', x.code, ...(x.aliases ?? [])].some((s) => norm(s).includes(t))) : suggest ? tests : []).filter((x) => !has(x.code, x.name)).slice(0, t ? 8 : 6),
    [t, tests, has, suggest],
  );
  const exact = tests.some((x) => [x.name, ...(x.aliases ?? [])].some((s) => norm(s) === t));
  const pick = (code: string | null, name: string) => {
    onAdd(code, name);
    setQ('');
    input.current?.focus();
  };
  return (
    <Panel title={tr('Add a test', 'পরীক্ষা যোগ করুন')} icon="add">
      <div className="rxp-search">
        <Icon name="search" />
        <input
          ref={input}
          className="input"
          value={q}
          maxLength={80}
          aria-label={tr('Find a test', 'পরীক্ষা খুঁজুন')}
          placeholder={tr('Find a test', 'পরীক্ষা খুঁজুন')}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            e.preventDefault();
            if (found[0]) pick(found[0].code, found[0].name);
            else if (q.trim().length >= 2) pick(null, q.trim());
          }}
        />
      </div>
      <ul className="rxp-found">
        {found.map((x) => (
          <li key={x.code}>
            <span className="col gap-2 grow">
              <span className="semi">{x.name}</span>
              {isBangla() && x.bn ? <span className="t-body-s muted">{x.bn}</span> : null}
            </span>
            <Button small variant="tonal" icon="add" onClick={() => pick(x.code, x.name)}>{tr('Add', 'যোগ')}</Button>
          </li>
        ))}
        {q.trim().length >= 2 && !exact && !has(null, q.trim()) ? (
          <li className="rxp-found-free">
            <span className="t-body-s muted grow">{tr('Not in the list?', 'তালিকায় নেই?')}</span>
            <Button small variant="outline" icon="add" onClick={() => pick(null, q.trim())}>{tr(`Add "${q.trim()}"`, `"${q.trim()}" যোগ করুন`)}</Button>
          </li>
        ) : null}
      </ul>
    </Panel>
  );
}
