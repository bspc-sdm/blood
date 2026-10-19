// ফার্মেসি — খোলা প্রেসক্রিপশনে কয়টা দিলেন (পুরো বা কিছু), "নেই" চিহ্ন ("অর্ডার" ট্যাব — orders.tsx);
// "চেয়েও পাননি" তালিকা আর দোকানের QR কোড ("দোকান" ট্যাব — shoppanels.tsx)
import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Check, ConfirmDialog, ErrorBox, ErrorText, InfoText, Loading, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDayOf } from '@/lib/format';
import { Panel, focusNext, useInvalidate, useWide } from './common';
import { RxSaleOffer } from './books/rxsale';
import { ageText, boughtText, doseText, timingText, medTitle, type PharmMed, type PharmRx, type PharmacyHome } from './model';

/** খোলা প্রেসক্রিপশন */
export function RxPanel({ code, onNext }: { code: string; onNext: () => void }) {
  const q = useCall<{ item: PharmRx }>('rx_pharmacy_open', { code }, { staleTime: 60_000 });
  if (q.isLoading) return <SkeletonCards n={1} h={260} />;
  if (q.error || !q.data) return <section className="card"><ErrorBox error={q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')} onRetry={() => q.refetch()} /></section>;
  return <DispenseForm start={q.data.item} onNext={onNext} />;
}

function DispenseForm({ start, onNext }: { start: PharmRx; onNext: () => void }) {
  const wide = useWide();
  const qc = useQueryClient();
  const invalidate = useInvalidate();
  const [rx, setRx] = useState(start);
  const [qty, setQty] = useState<Record<number, string>>({});
  const [none, setNone] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const cancelled = rx.status === 'cancelled';
  const open = rx.medicines.filter((m) => !m.done);
  const qn = (i: number) => parseInt(toAsciiDigits(qty[i] ?? ''), 10) || 0;
  const can = !cancelled && (open.some((m) => qn(m.i) > 0) || none.length > 0);

  // প্রথম "কয়টা" ঘরে ফোকাস — কাউন্টারে সরাসরি লেখা শুরু
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const t = setTimeout(() => box.current?.querySelector<HTMLInputElement>('[data-rxp-nav="qty"]')?.focus(), 60);
    return () => clearTimeout(t);
  }, [rx.id]);

  async function save() {
    if (!can || busy) return;
    const items = open.filter((m) => qn(m.i) > 0 && !none.includes(m.i)).map((m) => ({ i: m.i, qty: qn(m.i) }));
    const over = open.find((m) => m.left != null && qn(m.i) > m.left);
    if (over) {
      setErr(tr(`${over.name}: only ${over.left} left on this prescription`, `${over.name}: এই প্রেসক্রিপশনে আর ${num(over.left ?? 0)}টা বাকি`));
      return;
    }
    setBusy(true);
    setErr(null);
    setInfo(null);
    try {
      const r = await call<{ item: PharmRx }>('rx_dispense', { code: rx.code, items, missing: none });
      setRx(r.item);
      qc.setQueriesData({ predicate: (q) => q.queryKey[0] === 'rx_pharmacy_open' && (q.queryKey[1] as { code?: string })?.code === rx.code }, { item: r.item });
      setQty({});
      setNone([]);
      const msg = items.length ? tr('Saved', 'লেখা হয়েছে') : tr('The patient was told', 'রোগীকে জানানো হয়েছে');
      setInfo(msg);
      toast(msg);
      invalidate('rx_orders_pharmacy', 'rx_pharmacy_home');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const qtyCell = (m: PharmMed) => {
    if (m.done) return <Tag tone="good">{tr('All given', 'দেওয়া শেষ')}</Tag>;
    if (cancelled) return <span className="muted">—</span>;
    const off = none.includes(m.i);
    return (
      <div className="rxp-qty">
        <input
          className="input rxp-qty-input num"
          inputMode="numeric"
          data-rxp-nav="qty"
          disabled={off}
          aria-label={`${m.name}: ${tr('how many given', 'কয়টা দিলেন')}`}
          placeholder={m.left != null ? tr(`${m.left} left`, `বাকি ${num(m.left)}`) : tr('Qty', 'কয়টা')}
          value={qty[m.i] ?? ''}
          onChange={(e) => setQty({ ...qty, [m.i]: toAsciiDigits(e.target.value).replace(/\D/g, '').slice(0, 4) })}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              if (e.ctrlKey || e.metaKey) save();
              else focusNext(e.currentTarget, 'qty', save);
            }
          }}
        />
        {m.left != null && m.left > 0 && !off ? (
          <button type="button" className="rxp-full" onClick={() => setQty({ ...qty, [m.i]: String(m.left) })} title={tr('Give all that is left', 'বাকি সবটা দিন')}>
            {tr('All', 'পুরো')}
          </button>
        ) : null}
      </div>
    );
  };
  const noneCell = (m: PharmMed) =>
    m.done || cancelled ? null : (
      <Check
        checked={none.includes(m.i)}
        onChange={(v) => {
          setNone(v ? [...none, m.i] : none.filter((x) => x !== m.i));
          if (v) {
            const rest = { ...qty };
            delete rest[m.i];
            setQty(rest);
          }
        }}
      >
        {tr("Don't have it", 'নেই')}
      </Check>
    );
  const medName = (m: PharmMed) => (
    <div className="col gap-2">
      <span className="semi">{medTitle(m)}{m.strength ? <span className="muted"> · {m.strength}</span> : null}</span>
      {m.timing || m.note ? <span className="t-body-s muted">{[timingText(m.timing), m.note].filter(Boolean).join(' · ')}</span> : null}
    </div>
  );
  const bought = (m: PharmMed) => (
    <span className={cx('t-body-s', m.done ? 'c-primary' : 'muted')}>
      {boughtText(m)}
      {m.left != null && !m.done ? tr(` · ${m.left} left`, ` · বাকি ${num(m.left)}`) : ''}
    </span>
  );

  return (
    <section className="card rxp-panel rxp-rx" ref={box}>
      {cancelled ? (
        <div className="rxp-alert"><Icon name="warning" /><span>{tr('Cancelled prescription — do not dispense', 'বাতিল করা প্রেসক্রিপশন — ওষুধ দেবেন না')}</span></div>
      ) : null}
      <div className="rxp-rx-head">
        <div className="col gap-4 grow">
          <span className="t-title-m">{rx.patient.name || '—'}{ageText(rx.patient.age) ? <span className="muted t-body-m"> · {ageText(rx.patient.age)}</span> : null}</span>
          <span className="t-body-m">
            {rx.doctor.name}
            {rx.doctor.degrees ? <span className="muted"> · {rx.doctor.degrees}</span> : null}
            {rx.doctor.regNo ? <span className="muted"> · BMDC {rx.doctor.regNo}</span> : null}
          </span>
          <span className="t-body-s muted">{formatDayOf(rx.createdAt)}</span>
        </div>
        <span className="rxp-code-chip num" aria-label={tr('Prescription code', 'প্রেসক্রিপশনের কোড')}>{rx.code}</span>
      </div>

      {wide ? (
        <div className="table-wrap rxp-table-wrap">
          <table className="table rxp-table">
            <thead>
              <tr>
                <th>{tr('Medicine', 'ওষুধ')}</th>
                <th>{tr('Dose', 'নিয়ম')}</th>
                <th>{tr('Bought', 'কেনা')}</th>
                <th>{tr('Given now', 'এখন দিলেন')}</th>
                <th><span className="sr-only">{tr("Don't have it", 'নেই')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rx.medicines.map((m) => (
                <tr key={m.i} className={cx(m.done && 'rxp-done', none.includes(m.i) && 'rxp-none')}>
                  <td>{medName(m)}</td>
                  <td className="num rxp-nowrap">{doseText(m)}</td>
                  <td>{bought(m)}</td>
                  <td>{qtyCell(m)}</td>
                  <td>{noneCell(m)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="col gap-8">
          {rx.medicines.map((m) => (
            <div key={m.i} className={cx('rxp-med', m.done && 'rxp-done', none.includes(m.i) && 'rxp-none')}>
              <div className="row top">
                <div className="col gap-2 grow">
                  {medName(m)}
                  <span className="t-body-s num">{doseText(m)}</span>
                  {bought(m)}
                </div>
                {qtyCell(m)}
              </div>
              {noneCell(m)}
            </div>
          ))}
        </div>
      )}

      {none.length ? (
        <p className="t-body-s muted">{tr('The patient will be told, and it goes on your "asked for but not available" list.', 'রোগী জানবেন, আর ওষুধটা আপনার "চেয়েও পাননি" তালিকায় জমা হবে।')}</p>
      ) : null}
      {rx.dispensed.length ? (
        <div className="col gap-4">
          <span className="t-label-l">{tr('Given before', 'আগে দেওয়া')}</span>
          {rx.dispensed.map((d, i) => (
            <span key={i} className="t-body-s muted">
              {d.name} · {formatDayOf(d.at)} · {d.items.map((it) => `${rx.medicines[it.i]?.name ?? ''} ×${num(it.qty)}`).join(', ')}
            </span>
          ))}
        </div>
      ) : null}
      <ErrorText>{err}</ErrorText>
      <InfoText>{info}</InfoText>
      <RxSaleOffer rx={rx} />
      <div className="row wrap gap-8 rxp-actions">
        {!cancelled && open.length ? (
          <Button icon="check_circle" busy={busy} disabled={!can} onClick={save} title="Ctrl + Enter">
            {tr('Save what I gave', 'যা দিলাম লিখে রাখুন')}
          </Button>
        ) : null}
        <Button variant="text" icon="add" onClick={onNext}>{tr('Next patient', 'পরের রোগী')}</Button>
        {wide && !cancelled && open.length ? <span className="t-body-s muted rxp-kbd">{tr('Enter: next box · Ctrl+Enter: save', 'Enter: পরের ঘর · Ctrl+Enter: সেভ')}</span> : null}
      </div>
    </section>
  );
}

/** ক্রেতা চেয়েও পাননি এমন ওষুধ — স্টকে আনলে "এনেছি" */
export function MissedPanel() {
  const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
  const qc = useQueryClient();
  const invalidate = useInvalidate();
  const [busyK, setBusyK] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  const [clearing, setClearing] = useState(false);
  const list = home.data?.missed ?? [];
  const shown = all ? list : list.slice(0, 6);
  const drop = (k: string | null) =>
    qc.setQueriesData<PharmacyHome>({ predicate: (q) => q.queryKey[0] === 'rx_pharmacy_home' }, (d) => (d ? { ...d, missed: k ? d.missed.filter((m) => m.k !== k) : [] } : d));

  async function got(k: string, name: string) {
    setBusyK(k);
    setErr(null);
    try {
      await call('rx_missed_clear', { k });
      await call('stock_set', { add: [name], remove: [] });
      drop(k);
      invalidate('stock_get');
      toast(tr('Added to stock', 'স্টকে যোগ হয়েছে'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusyK(null);
    }
  }
  async function clearAll() {
    setBusyK('*');
    setErr(null);
    try {
      await call('rx_missed_clear', {});
      drop(null);
      setClearing(false);
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusyK(null);
    }
  }

  return (
    <Panel
      title={tr('Asked for but not available', 'চেয়েও পাননি এমন ওষুধ')}
      icon="list_alt"
      count={list.length ? num(list.length) : undefined}
    >
      <p className="t-body-s muted">{tr("Medicines customers came for but you couldn't give. Keep them in stock to sell more.", 'ক্রেতা প্রেসক্রিপশন নিয়ে এসে যে ওষুধ পাননি। স্টকে রাখলে বিক্রি বাড়বে।')}</p>
      {home.isLoading ? <Loading /> : home.error ? <ErrorText>{errorText(home.error)}</ErrorText> : !list.length ? (
        <p className="t-body-m muted">{tr('Nothing yet', 'এখনো কিছু নেই')}</p>
      ) : (
        <ul className="rxp-missed">
          {shown.map((m) => (
            <li key={m.k}>
              <div className="col gap-2 grow">
                <span className="semi">{m.name}</span>
                <span className="t-body-s muted">{tr(`${m.count} times · last `, `${num(m.count)} বার · শেষ `)}{formatDayOf(m.lastAt)}</span>
              </div>
              <Button small variant="tonal" icon="check_circle" busy={busyK === m.k} disabled={!!busyK} onClick={() => got(m.k, m.name)}>
                {tr('Got it in stock', 'এনেছি')}
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="row wrap gap-8">
        {list.length > 6 ? (
          <Button variant="text" small onClick={() => setAll(!all)}>{all ? tr('Show less', 'কম দেখান') : tr(`See all (${list.length})`, `সব দেখুন (${num(list.length)})`)}</Button>
        ) : null}
        <span className="grow" />
        {list.length ? <Button variant="danger-text" small disabled={!!busyK} onClick={() => setClearing(true)}>{tr('Clear list', 'তালিকা মুছুন')}</Button> : null}
      </div>
      <ErrorText>{err}</ErrorText>
      {clearing ? (
        <ConfirmDialog
          title={tr('Clear the whole list?', 'পুরো তালিকা মুছবেন?')}
          text={tr("It starts counting again from the next prescription. Your stock list doesn't change.", 'পরের প্রেসক্রিপশন থেকে আবার গোনা শুরু হবে। স্টকের তালিকা বদলাবে না।')}
          confirm={tr('Clear', 'মুছুন')}
          danger
          busy={busyK === '*'}
          error={err}
          onConfirm={clearAll}
          onClose={() => setClearing(false)}
        />
      ) : null}
    </Panel>
  );
}

/** কাউন্টারের QR আর দোকানের কোড — পোস্টার ছাপা (/shop/{কোড}) */
export function ShopCodePanel() {
  const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
  const [qrOk, setQrOk] = useState(true);
  const h = home.data;
  if (!h) return null;
  return (
    <Panel title={tr('Shop QR & code', 'দোকানের QR ও কোড')} icon="share">
      <div className="rxp-shop">
        {/* QR-এর নিচেই কোড — স্ক্যান না হলে ক্রেতা লিখে পাঠাতে পারেন */}
        <div className="col rxp-qr-box">
          {qrOk ? <img className="rxp-qr" src={`/api/share?shopqr=${encodeURIComponent(h.shopCode)}`} alt={tr('Shop QR', 'দোকানের QR')} onError={() => setQrOk(false)} /> : null}
          <span className="t-label-s muted">{tr('Code', 'কোড')}</span>
          <span className="rxp-shop-code num">{h.shopCode}</span>
        </div>
        <div className="col gap-6">
          <Button variant="tonal" small icon="share" onClick={() => window.open(`/shop/${encodeURIComponent(h.shopCode)}`, '_blank', 'noopener')}>
            {tr('Print counter poster', 'কাউন্টারের পোস্টার প্রিন্ট')}
          </Button>
        </div>
      </div>
      <p className="t-body-s muted">
        {tr('Put the poster at the counter. When a customer scans the QR in the app or types this code or your shop name, the prescription comes straight to "New" under Orders.', 'পোস্টারটা কাউন্টারে লাগিয়ে রাখুন। ক্রেতা অ্যাপে QR স্ক্যান করলে বা কোড/দোকানের নাম লিখলে প্রেসক্রিপশন সাথে সাথে "অর্ডার" ট্যাবের "নতুন"-এ আসবে।')}
      </p>
    </Panel>
  );
}
