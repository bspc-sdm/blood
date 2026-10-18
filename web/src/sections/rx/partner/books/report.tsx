// ফার্মেসি — "হিসাব": আজ / ৭ দিন / ৩০ দিন / এই মাস — বিক্রি, ছাড়, কেনা দাম, লাভ, মার্জিন, নষ্টে ক্ষতি, নিট লাভ;
// দিনের বিক্রি আর লাভের ছোট বার চার্ট; বেশি বিক্রি/লাভ, কেনা দামের নিচে বিক্রি; স্টকের দাম, কম স্টক, মেয়াদ, বিক্রি নেই।
import { useState } from 'react';
import { ErrorBox, Segmented, SkeletonCards, cx } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { Panel } from '../common';
import { dayShort, expiryText, money, periods, qtyText, sourceText, unitText, type Period, type RankRow, type Report, type ShopItem } from './model';
import './books.css';

export function ReportTab() {
  const [period, setPeriod] = useState<Period>('7d');
  const q = useCall<Report>('books_report', { period });
  const r = q.data;
  return (
    <div className="col gap-16">
      <div className="row wrap gap-8">
        <Segmented value={period} onChange={setPeriod} label={tr('Period', 'সময়')} options={periods()} />
      </div>
      {q.isLoading ? <SkeletonCards n={2} h={160} /> : q.error || !r ? <section className="card"><ErrorBox error={q.error ? errorText(q.error) : ''} onRetry={() => q.refetch()} /></section> : (
        <>
          <Totals r={r} />
          <Panel title={tr('Daily sales & profit', 'দিনের বিক্রি ও লাভ')} icon="history">
            <DailyChart r={r} />
          </Panel>
          <div className="rxp-two">
            <Panel title={tr('Top sellers', 'বেশি বিক্রি')} icon="payments"><Rank rows={r.topSales} v={(x) => money(x.net)} /></Panel>
            <Panel title={tr('Most profit', 'বেশি লাভ')} icon="star"><Rank rows={r.topProfit} v={(x) => money(x.profit)} /></Panel>
          </div>
          {r.belowCost.length ? (
            <Panel title={tr('Sold below cost', 'কেনা দামের নিচে বিক্রি')} icon="warning" className="bk-warn">
              <Rank rows={r.belowCost} v={(x) => '−' + money(x.loss)} sub={(x) => tr(`${x.lossQty} sold`, `${qtyText(x.lossQty)}টা বিক্রি`)} />
            </Panel>
          ) : null}
          <Stock r={r} />
          <div className="rxp-two">
            <Panel title={tr('Low stock', 'কম স্টক')} icon="warning" count={num(r.low.length)}>
              <Items list={r.low} v={(i) => `${qtyText(i.qty)}${i.unit ? ' ' + unitText(i.unit) : ''}`} sub={(i) => tr('reorder at ', 'আনবেন ') + qtyText(i.lowAt)} />
            </Panel>
            <Panel title={tr('Expiring in 60 days', '৬০ দিনের মধ্যে মেয়াদ শেষ')} icon="schedule" count={num(r.expiring.length)}>
              <Items list={r.expiring} v={(i) => expiryText(i.expiry)} sub={(i) => (i.expDays != null && i.expDays < 0 ? tr('expired', 'মেয়াদ শেষ') : tr(`${i.expDays} days`, `${num(i.expDays ?? 0)} দিন`))} bad />
            </Panel>
          </div>
          <Panel title={tr('No sale in 30 days', '৩০ দিনে বিক্রি নেই')} icon="info" count={num(r.noSale.length)}>
            <Items list={r.noSale} v={(i) => `${qtyText(i.qty)}${i.unit ? ' ' + unitText(i.unit) : ''}`} sub={(i) => (i.cost != null && i.qty != null ? money(Math.round(i.qty * i.cost)) : '')} />
          </Panel>
        </>
      )}
    </div>
  );
}

function Totals({ r }: { r: Report }) {
  const T = r.totals;
  const tile = (l: string, v: string, tone?: 'good' | 'bad', sub?: string) => (
    <div className={cx('stat', tone && `bk-${tone}`)}>
      <span className="l">{l}</span>
      <span className="v">{v}</span>
      {sub ? <span className="t-body-s muted">{sub}</span> : null}
    </div>
  );
  return (
    <div className="col gap-12">
      <div className="stats">
        {tile(tr('Sales', 'বিক্রি'), money(T.net), undefined, tr(`${T.sales} sales`, `${num(T.sales)}টা বিক্রি`))}
        {tile(tr('Gross profit', 'মোট লাভ'), money(T.profit), T.profit >= 0 ? 'good' : 'bad', T.margin != null ? tr(`margin ${T.margin}%`, `মার্জিন ${num(T.margin)}%`) : undefined)}
        {tile(tr('Net profit', 'নিট লাভ'), money(T.netProfit), T.netProfit >= 0 ? 'good' : 'bad', tr('after losses', 'ক্ষতি বাদে'))}
      </div>
      <div className="rxp-two">
      <dl className="kv num bk-kv">
        <dt>{tr('Discount given', 'ছাড় দেওয়া')}</dt><dd>{money(T.discount)}</dd>
        <dt>{tr('Cost of goods sold', 'বিক্রি হওয়া মালের কেনা দাম')}</dt><dd>{money(T.cost)}</dd>
        <dt>{tr('Damaged / expired (loss)', 'নষ্ট / মেয়াদ শেষে ক্ষতি')}</dt><dd>{money(T.writeOff)}</dd>
        <dt>{tr('Stock bought', 'মাল কেনা')}</dt><dd>{money(T.bought)}</dd>
      </dl>
      <dl className="kv num bk-kv">
        {(['counter', 'rx', 'online'] as const).map((s) => (
          <FragmentRow key={s} l={sourceText(s)} v={`${money(r.bySource[s].net)} · ${num(r.bySource[s].n)}`} />
        ))}
        {T.uncosted > 0 ? <FragmentRow l={tr('Sales without cost price', 'কেনা দাম নেই এমন বিক্রি')} v={money(T.uncosted)} /> : null}
      </dl>
      </div>
    </div>
  );
}
const FragmentRow = ({ l, v }: { l: string; v: string }) => (<><dt>{l}</dt><dd>{v}</dd></>);

/** দিনের বিক্রি আর লাভ — পাশাপাশি দুটো বার; ছোঁয়া/মাউসে দিনের সংখ্যা */
function DailyChart({ r }: { r: Report }) {
  const max = Math.max(1, ...r.daily.map((d) => Math.max(d.net, d.profit)));
  const [on, setOn] = useState<number | null>(null);
  const cur = on != null ? r.daily[on] : null;
  const labelAt = new Set([0, Math.floor((r.daily.length - 1) / 2), r.daily.length - 1]);
  return (
    <div className="col gap-8">
      <div className="row wrap gap-16 t-body-s">
        <span className="row gap-6"><span className="bk-sw bk-sales" />{tr('Sales', 'বিক্রি')}</span>
        <span className="row gap-6"><span className="bk-sw bk-profit" />{tr('Profit', 'লাভ')}</span>
        <span className="grow" />
        <span className="muted num">{cur ? `${dayShort(cur.day)} · ${tr('sales', 'বিক্রি')} ${money(cur.net)} · ${tr('profit', 'লাভ')} ${money(cur.profit)}` : tr('max ', 'সর্বোচ্চ ') + money(Math.round(max))}</span>
      </div>
      <div className="bk-chart" role="img" aria-label={tr('Daily sales and profit', 'দিনের বিক্রি ও লাভ')} onMouseLeave={() => setOn(null)}>
        {r.daily.map((d, i) => (
          <div
            key={d.day}
            className={cx('bk-col', on === i && 'on')}
            title={`${dayShort(d.day)}: ${money(d.net)} / ${money(d.profit)}`}
            onMouseEnter={() => setOn(i)}
            onClick={() => setOn(on === i ? null : i)}
          >
            <div className="bk-bars">
              <span className="bk-bar bk-sales" style={{ height: `${(Math.max(0, d.net) / max) * 100}%` }} />
              <span className="bk-bar bk-profit" style={{ height: `${(Math.max(0, d.profit) / max) * 100}%` }} />
            </div>
            <span className="bk-x num">{labelAt.has(i) ? dayShort(d.day) : ''}</span>
          </div>
        ))}
      </div>
      <details className="bk-table-view">
        <summary className="t-body-s">{tr('See as table', 'টেবিলে দেখুন')}</summary>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>{tr('Day', 'দিন')}</th><th className="bk-r">{tr('Sales', 'বিক্রি')}</th><th className="bk-r">{tr('Profit', 'লাভ')}</th></tr></thead>
            <tbody>{r.daily.map((d) => <tr key={d.day}><td className="num">{dayShort(d.day)}</td><td className="bk-r num">{money(d.net)}</td><td className="bk-r num">{money(d.profit)}</td></tr>)}</tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

function Rank({ rows, v, sub }: { rows: RankRow[]; v: (x: RankRow) => string; sub?: (x: RankRow) => string }) {
  if (!rows.length) return <p className="t-body-m muted">{tr('Nothing yet', 'এখনো কিছু নেই')}</p>;
  return (
    <ol className="bk-rank">
      {rows.map((x) => (
        <li key={x.id}>
          <span className="grow ellipsis">{x.name}</span>
          <span className="t-body-s muted num">{sub ? sub(x) : tr(`${x.qty}`, `${qtyText(x.qty)}টা`)}</span>
          <span className="num semi">{v(x)}</span>
        </li>
      ))}
    </ol>
  );
}

function Items({ list, v, sub, bad }: { list: ShopItem[]; v: (i: ShopItem) => string; sub?: (i: ShopItem) => string; bad?: boolean }) {
  if (!list.length) return <p className="t-body-m muted">{tr('None', 'নেই')}</p>;
  return (
    <ul className="bk-rank">
      {list.map((i) => (
        <li key={i.id}>
          <span className="grow ellipsis">{i.name}</span>
          {sub ? <span className="t-body-s muted num">{sub(i)}</span> : null}
          <span className={cx('num semi', bad && 'c-error')}>{v(i)}</span>
        </li>
      ))}
    </ul>
  );
}

function Stock({ r }: { r: Report }) {
  const s = r.stock;
  return (
    <Panel title={tr('Stock value', 'স্টকের দাম')} icon="list_alt">
      <div className="stats">
        <div className="stat"><span className="l">{tr('At cost', 'কেনা দামে')}</span><span className="v">{money(s.atCost)}</span></div>
        <div className="stat"><span className="l">{tr('At sale price', 'বিক্রি দামে')}</span><span className="v">{money(s.atPrice)}</span></div>
      </div>
      {s.noCost || s.noPrice || s.counted < s.items ? (
        <p className="t-body-s muted">
          {[s.counted < s.items ? tr(`${s.items - s.counted} without qty`, `${num(s.items - s.counted)}টার পরিমাণ নেই`) : '', s.noCost ? tr(`${s.noCost} without cost`, `${num(s.noCost)}টার কেনা দাম নেই`) : '', s.noPrice ? tr(`${s.noPrice} without price`, `${num(s.noPrice)}টার বিক্রি দাম নেই`) : ''].filter(Boolean).join(' · ')}
        </p>
      ) : null}
    </Panel>
  );
}
