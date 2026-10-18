// ফার্মেসি — "বিক্রি": কাউন্টারে তাড়াতাড়ি বিক্রি লেখা (পণ্য খুঁজে কয়টা; দাম আর ছাড় নিজে থেকে, বদলানো যায়; পুরো বিলে % বা টাকায় ছাড়),
// পাশে আজকের/এই সপ্তাহের বিক্রি (কাউন্টার, প্রেসক্রিপশন, অনলাইন)।
import { useState } from 'react';
import { Button, ConfirmDialog, ErrorText, IconButton, Loading, Segmented, Tag, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import { Panel } from '../common';
import { ItemPicker } from './picker';
import { money, numInput, numStr, qtyText, r2, readNum, sourceText, unitDisc, unitText, useBooksRefresh, type Sale, type ShopItem } from './model';
import './books.css';

interface Line { item: ShopItem; qty: string; price: string; disc: string }

/** বিলের হিসাব (সার্ভারের priceSale-এর মতো — দেখানোর জন্য; আসল হিসাব সার্ভারে) */
function billOf(lines: Line[], billType: 'pct' | 'amt', billV: number | null) {
  let gross = 0;
  let after = 0;
  let lineDisc = 0;
  for (const l of lines) {
    const q = readNum(l.qty) || 0;
    const p = readNum(l.price);
    if (p == null || Number.isNaN(p)) continue;
    const g = q * p;
    const d = Math.min(g, q * (readNum(l.disc) || 0));
    gross += g;
    lineDisc += d;
    after += g - d;
  }
  const bd = billV && after > 0 ? Math.min(after, billType === 'pct' ? (after * billV) / 100 : billV) : 0;
  return { gross: r2(gross), discount: r2(lineDisc + bd), net: r2(after - bd) };
}

export function SalesTab() {
  const items = useCall<{ items: ShopItem[] }>('books_items', {});
  const [period, setPeriod] = useState<'today' | '7d'>('today');
  const sales = useCall<{ items: Sale[] }>('books_sales', { period });
  const refresh = useBooksRefresh();
  const [lines, setLines] = useState<Line[]>([]);
  const [billType, setBillType] = useState<'pct' | 'amt'>('pct');
  const [billV, setBillV] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<Sale | null>(null);

  const add = (i: ShopItem) => {
    const ex = lines.find((l) => l.item.id === i.id);
    if (ex) {
      setLines(lines.map((l) => (l === ex ? { ...l, qty: String((readNum(l.qty) || 0) + 1) } : l)));
      return;
    }
    setLines([...lines, { item: i, qty: '1', price: numStr(i.price), disc: i.price != null && i.disc ? String(unitDisc(i.price, i.disc)) : '' }]);
  };
  const set = (id: string, p: Partial<Line>) => setLines(lines.map((l) => (l.item.id === id ? { ...l, ...p } : l)));
  const bv = readNum(billV);
  const bad = Number.isNaN(bv) || lines.some((l) => [l.qty, l.price, l.disc].some((s) => Number.isNaN(readNum(s))));
  const ready = lines.filter((l) => (readNum(l.qty) || 0) > 0);
  const bill = billOf(ready, billType, bv);

  async function save() {
    if (!ready.length || bad) return;
    setBusy(true);
    setErr(null);
    try {
      const r = await call<{ sale: Sale }>('books_sale_add', {
        lines: ready.map((l) => ({ itemId: l.item.id, qty: readNum(l.qty), unitPrice: readNum(l.price), unitDisc: readNum(l.disc) || 0 })),
        billDisc: bv ? { type: billType, v: bv } : null,
      });
      toast(tr('Sale saved', 'বিক্রি লেখা হয়েছে') + ' · ' + money(r.sale.totals.net));
      setLines([]);
      setBillV('');
      refresh();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const list = sales.data?.items ?? [];
  const sum = r2(list.reduce((a, s) => a + s.totals.net, 0));
  return (
    <div className="rxp-grid">
      <div className="col gap-16 rxp-main">
        <Panel title={tr('New sale', 'নতুন বিক্রি')} icon="payments">
          {!items.data ? <Loading /> : <ItemPicker items={items.data.items} onPick={add} />}
          {lines.length ? (
            <div className="col gap-8">
              {lines.map((l) => {
                const q = readNum(l.qty) || 0;
                const p = readNum(l.price);
                const d = readNum(l.disc) || 0;
                const low = l.item.cost != null && p != null && !Number.isNaN(p) && p - d < l.item.cost;
                return (
                  <div key={l.item.id} className="bk-line">
                    <div className="col gap-2 bk-line-name">
                      <span className="semi">{l.item.name}</span>
                      <span className="t-body-s muted num">
                        {l.item.qty != null ? tr('Stock ', 'স্টক ') + qtyText(l.item.qty) + (l.item.unit ? ` ${unitText(l.item.unit)}` : '') : ''}
                        {low ? <span className="c-error semi"> · {tr('below cost', 'কেনা দামের নিচে')}</span> : null}
                      </span>
                    </div>
                    <label className="bk-cell"><span className="t-label-s muted">{tr('Qty', 'কয়টা')}</span>
                      <input className="input num" inputMode="decimal" value={l.qty} onChange={(e) => set(l.item.id, { qty: numInput(e.target.value) })} />
                    </label>
                    <label className="bk-cell"><span className="t-label-s muted">{tr('Price', 'দাম')}</span>
                      <input className="input num" inputMode="decimal" value={l.price} placeholder="—" onChange={(e) => set(l.item.id, { price: numInput(e.target.value) })} />
                    </label>
                    <label className="bk-cell"><span className="t-label-s muted">{tr('Disc / unit', 'ছাড় / একক')}</span>
                      <input className="input num" inputMode="decimal" value={l.disc} placeholder="০" onChange={(e) => set(l.item.id, { disc: numInput(e.target.value) })} />
                    </label>
                    <span className="bk-line-total num semi">{p != null && !Number.isNaN(p) ? money(r2(q * (p - Math.min(p, d)))) : '—'}</span>
                    <IconButton small icon="close" label={tr('Remove', 'সরান') + ': ' + l.item.name} onClick={() => setLines(lines.filter((x) => x !== l))} />
                  </div>
                );
              })}
            </div>
          ) : null}
          {lines.length ? (
            <div className="row wrap gap-8">
              <span className="t-label-l">{tr('Bill discount', 'বিলে ছাড়')}</span>
              <Segmented value={billType} onChange={setBillType} label={tr('Bill discount', 'বিলে ছাড়')} options={[{ value: 'pct', label: '%' }, { value: 'amt', label: tr('Tk', 'টাকা') }]} />
              <input className="input num bk-small-input" inputMode="decimal" value={billV} placeholder="০" aria-label={tr('Bill discount', 'বিলে ছাড়')} onChange={(e) => setBillV(numInput(e.target.value))} />
            </div>
          ) : null}
          <div className="bk-bill">
            <dl className="kv num">
              <dt>{tr('Total', 'মোট')}</dt><dd>{money(bill.gross)}</dd>
              <dt>{tr('Discount', 'ছাড়')}</dt><dd>{money(bill.discount)}</dd>
            </dl>
            <div className="col gap-2 bk-pay">
              <span className="t-label-m muted">{tr('To pay', 'দিতে হবে')}</span>
              <span className="bk-big num">{money(bill.net)}</span>
            </div>
          </div>
          <div className="row wrap gap-8">
            <Button icon="check_circle" busy={busy} disabled={!ready.length || bad} onClick={save}>{tr('Save sale', 'বিক্রি লিখুন')}</Button>
            {lines.length ? <Button variant="text" onClick={() => { setLines([]); setBillV(''); }}>{tr('Clear', 'মুছে নতুন')}</Button> : null}
          </div>
          <ErrorText>{err}</ErrorText>
        </Panel>
      </div>
      <aside className="col gap-16 rxp-side">
        <Panel title={tr('Sales', 'বিক্রি')} icon="history" count={sales.data ? money(sum) : undefined}>
          <div className="row"><Segmented value={period} onChange={setPeriod} options={[{ value: 'today', label: tr('Today', 'আজ') }, { value: '7d', label: tr('7 days', '৭ দিন') }]} /></div>
          {!sales.data ? (sales.error ? <ErrorText>{errorText(sales.error)}</ErrorText> : <Loading />) : !list.length ? (
            <p className="t-body-m muted">{tr('No sales yet', 'এখনো বিক্রি নেই')}</p>
          ) : (
            <ul className="bk-list">
              {list.slice(0, 80).map((s) => (
                <li key={s.id}>
                  <div className="row">
                    <Tag tone={s.source === 'counter' ? undefined : s.source === 'rx' ? 'primary' : 'info'}>{sourceText(s.source)}</Tag>
                    <span className="t-body-s muted grow">{period === 'today' ? formatTime(s.at) : formatDayOf(s.at)}{s.rx?.patient ? ` · ${s.rx.patient}` : s.customer ? ` · ${s.customer}` : ''}</span>
                    <span className="num semi">{money(s.totals.net)}</span>
                    <IconButton small icon="delete" label={tr('Delete', 'মুছুন')} onClick={() => setDeleting(s)} />
                  </div>
                  <span className="t-body-s muted">{s.lines.map((l) => `${l.name} ×${qtyText(l.qty)}`).join(', ')}</span>
                  {s.totals.profit < 0 ? <span className="t-body-s c-error">{tr('Loss ', 'ক্ষতি ')}{money(-s.totals.profit)}</span> : null}
                </li>
              ))}
            </ul>
          )}
          {sales.data && list.length ? <span className="t-body-s muted">{tr(`${list.length} sales`, `${num(list.length)}টা বিক্রি`)}</span> : null}
        </Panel>
      </aside>
      {deleting ? (
        <ConfirmDialog
          title={tr('Delete this sale?', 'এই বিক্রি মুছবেন?')}
          text={tr('The quantities go back to stock.', 'পরিমাণগুলো স্টকে ফিরে যাবে।')}
          confirm={tr('Delete', 'মুছুন')}
          danger
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            try {
              await call('books_sale_delete', { id: deleting.id });
              refresh();
            } catch (e) {
              toast(errorText(e));
            }
            setDeleting(null);
          }}
        />
      ) : null}
    </div>
  );
}
