// "যা দিলাম লিখে রাখুন"-এর পর: এই ওষুধগুলো বিক্রির হিসাবে তোলার প্রস্তাব (দাম পণ্যের তালিকা থেকে, বদলানো যায়)।
// না চাইলে "এখন না" — ওষুধ দেওয়া আটকায় না। DispenseForm-এ এক লাইন: <RxSaleOffer rx={rx} />
import { useEffect, useState } from 'react';
import { Button, ErrorText, Loading, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { PharmRx } from '../model';
import { money, numInput, numStr, qtyText, r2, readNum, sourceText, useBooksRefresh, type RxPreview } from './model';
import './books.css';

export function RxSaleOffer({ rx }: { rx: PharmRx }) {
  const last = rx.dispensed[rx.dispensed.length - 1]?.at ?? 0;
  // খোলার সময় যেটা শেষ ছিল — এর পরে নতুন ভাগ এলে তবেই প্রস্তাব
  const [base] = useState(last);
  const [closed, setClosed] = useState<number | null>(null);
  const at = last && last !== base && closed !== last ? last : null;
  const [pv, setPv] = useState<RxPreview | null>(null);
  const [price, setPrice] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const refresh = useBooksRefresh();

  useEffect(() => {
    if (!at) return;
    let live = true;
    setPv(null);
    setErr(null);
    call<RxPreview>('books_rx_preview', { code: rx.code, at })
      .then((r) => {
        if (!live) return;
        if (r.recorded) { setClosed(at); return; }
        setPv(r);
        setPrice(Object.fromEntries(r.lines.map((l) => [l.i, numStr(l.unitPrice != null ? r2(l.unitPrice - l.unitDisc) : null)])));
      })
      .catch(() => live && setClosed(at));
    return () => { live = false; };
  }, [at, rx.code]);

  if (!at) return null;
  if (!pv) return <Loading />;
  const bad = Object.values(price).some((s) => Number.isNaN(readNum(s)));
  const total = r2(pv.lines.reduce((a, l) => a + (readNum(price[l.i] ?? '') || 0) * l.qty, 0));
  async function record() {
    if (!pv || bad) return;
    setBusy(true);
    setErr(null);
    try {
      await call('books_sale_add', {
        source: 'rx', rx: { code: pv.code, at: pv.at },
        lines: pv.lines.map((l) => {
          const p = readNum(price[l.i] ?? '');
          // দাম না বদলালে পণ্যের MRP আর ছাড় আলাদা থাকে (হিসাবে "ছাড় দেওয়া" ঠিক আসে)
          const same = l.unitPrice != null && p === r2(l.unitPrice - l.unitDisc);
          return { i: l.i, ...(l.itemId ? { itemId: l.itemId } : {}), unitPrice: same ? l.unitPrice : p, unitDisc: same ? l.unitDisc : 0 };
        }),
      });
      toast(tr('Added to sales', 'বিক্রির হিসাবে উঠেছে'));
      setClosed(pv.at);
      refresh();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="bk-offer">
      <div className="row">
        <span className="t-title-s grow">{tr('Add to sales?', 'বিক্রির হিসাবে তুলবেন?')}</span>
        <span className="t-body-s muted">{sourceText(pv.source)}</span>
      </div>
      <div className="col gap-6">
        {pv.lines.map((l) => (
          <div key={l.i} className="row gap-8">
            <span className="grow ellipsis">{l.itemName || l.name} <span className="muted num">×{qtyText(l.qty)}</span></span>
            <input
              className="input num bk-small-input"
              inputMode="decimal"
              value={price[l.i] ?? ''}
              placeholder={tr('Price', 'দাম')}
              aria-label={`${l.name}: ${tr('price per unit', 'প্রতি এককের দাম')}`}
              onChange={(e) => setPrice({ ...price, [l.i]: numInput(e.target.value) })}
            />
          </div>
        ))}
      </div>
      <ErrorText>{err}</ErrorText>
      <div className="row wrap gap-8">
        <span className="grow semi num">{tr('Total ', 'মোট ')}{money(total)}</span>
        <Button variant="text" onClick={() => setClosed(pv.at)}>{tr('Not now', 'এখন না')}</Button>
        <Button variant="tonal" icon="payments" busy={busy} disabled={bad} onClick={record}>{tr('Add to sales', 'হিসাবে তুলুন')}</Button>
      </div>
    </div>
  );
}
