// ফার্মেসি — "মাল কেনা": কোন পণ্য কয়টা, প্রতি একক কেনা দাম, মেয়াদ → পরিমাণ বাড়ে, কেনা দাম গড় হয়। পাশে আগের কেনা।
import { useState } from 'react';
import { Button, ConfirmDialog, ErrorText, IconButton, Loading, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { dateInputValue, formatDayOf } from '@/lib/format';
import { Panel } from '../common';
import { ItemPicker } from './picker';
import { money, numInput, numStr, qtyText, r2, readNum, unitText, useBooksRefresh, type Purchase, type ShopItem } from './model';
import './books.css';

interface Line { key: string; itemId: string | null; name: string; unit: string; qty: string; cost: string; expiry: string }

const todayInput = () => dateInputValue(Date.now() + 6 * 3600 * 1000);

export function BuyTab() {
  const items = useCall<{ items: ShopItem[] }>('books_items', {});
  const list = useCall<{ items: Purchase[] }>('books_purchases', {});
  const refresh = useBooksRefresh();
  const [lines, setLines] = useState<Line[]>([]);
  const [supplier, setSupplier] = useState('');
  const [date, setDate] = useState(todayInput());
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<Purchase | null>(null);

  const add = (i: ShopItem | null, name: string) => {
    if (i && lines.some((l) => l.itemId === i.id)) return;
    setLines([...lines, { key: i?.id ?? 'n:' + name, itemId: i?.id ?? null, name: i?.name ?? name, unit: i?.unit ?? '', qty: '', cost: numStr(i?.cost), expiry: '' }]);
    setTimeout(() => document.querySelectorAll<HTMLInputElement>('[data-bk-qty]')[lines.length]?.focus(), 30);
  };
  const set = (k: string, p: Partial<Line>) => setLines(lines.map((l) => (l.key === k ? { ...l, ...p } : l)));
  const parsed = lines.map((l) => ({ ...l, q: readNum(l.qty), c: readNum(l.cost) }));
  const bad = parsed.some((l) => Number.isNaN(l.q) || Number.isNaN(l.c));
  const ready = parsed.filter((l) => l.q && l.q > 0);
  const total = r2(ready.reduce((a, l) => a + (l.c != null && !Number.isNaN(l.c) ? l.q! * l.c : 0), 0));

  async function save() {
    if (!ready.length || bad) return;
    setBusy(true);
    setErr(null);
    try {
      await call('books_purchase_add', {
        at: date === todayInput() ? undefined : date, supplier,
        lines: ready.map((l) => ({ ...(l.itemId ? { itemId: l.itemId } : { name: l.name }), qty: l.q, unitCost: l.c, expiry: l.expiry || null })),
      });
      toast(tr('Saved — stock updated', 'সেভ হয়েছে — স্টক বেড়েছে'));
      setLines([]);
      setSupplier('');
      refresh();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rxp-grid">
      <div className="col gap-16 rxp-main">
        <Panel title={tr('Stock in', 'মাল কেনা')} icon="storefront">
          <div className="bk-form-grid">
            <TextInput label={tr('Date', 'তারিখ')} type="date" value={date} max={todayInput()} onChange={(e) => setDate(e.target.value || todayInput())} />
            <TextInput label={tr('Supplier (optional)', 'কার কাছ থেকে (ঐচ্ছিক)')} value={supplier} maxLength={80} onChange={(e) => setSupplier(e.target.value)} />
          </div>
          {!items.data ? <Loading /> : <ItemPicker items={items.data.items} onPick={(i) => add(i, i.name)} onNew={(n) => add(null, n)} />}
          {lines.length ? (
            <div className="col gap-8">
              {lines.map((l) => (
                <div key={l.key} className="bk-line">
                  <div className="col gap-2 bk-line-name">
                    <span className="semi">{l.name}</span>
                    {!l.itemId ? <span className="t-body-s c-primary">{tr('New product', 'নতুন পণ্য')}</span> : null}
                  </div>
                  <label className="bk-cell"><span className="t-label-s muted">{tr('Qty', 'পরিমাণ')}{l.unit ? ` (${unitText(l.unit)})` : ''}</span>
                    <input data-bk-qty="" className="input num" inputMode="decimal" value={l.qty} onChange={(e) => set(l.key, { qty: numInput(e.target.value) })} />
                  </label>
                  <label className="bk-cell"><span className="t-label-s muted">{tr('Cost / unit', 'কেনা দাম / একক')}</span>
                    <input className="input num" inputMode="decimal" value={l.cost} onChange={(e) => set(l.key, { cost: numInput(e.target.value) })} />
                  </label>
                  <label className="bk-cell"><span className="t-label-s muted">{tr('Expiry', 'মেয়াদ')}</span>
                    <input className="input" type="month" value={l.expiry} onChange={(e) => set(l.key, { expiry: e.target.value })} />
                  </label>
                  <IconButton small icon="close" label={tr('Remove', 'সরান') + ': ' + l.name} onClick={() => setLines(lines.filter((x) => x.key !== l.key))} />
                </div>
              ))}
            </div>
          ) : null}
          <div className="row wrap gap-8">
            <span className="grow t-title-s num">{ready.length ? tr('Total ', 'মোট ') + money(total) : ''}</span>
            <Button icon="check_circle" busy={busy} disabled={!ready.length || bad} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
          </div>
          <ErrorText>{err}</ErrorText>
        </Panel>
      </div>
      <aside className="col gap-16 rxp-side">
        <Panel title={tr('Bought before', 'আগের কেনা')} icon="history" count={list.data ? num(list.data.items.length) : undefined}>
          {!list.data ? (list.error ? <ErrorText>{errorText(list.error)}</ErrorText> : <Loading />) : !list.data.items.length ? (
            <p className="t-body-m muted">{tr('Nothing yet', 'এখনো কিছু নেই')}</p>
          ) : (
            <ul className="bk-list">
              {list.data.items.slice(0, 60).map((p) => (
                <li key={p.id}>
                  <div className="row">
                    <span className="semi grow">{p.supplier || tr('Stock in', 'মাল কেনা')}</span>
                    <span className="num semi">{money(p.total)}</span>
                    <IconButton small icon="delete" label={tr('Delete', 'মুছুন')} onClick={() => setDeleting(p)} />
                  </div>
                  <span className="t-body-s muted">{formatDayOf(p.at)} · {p.lines.map((l) => `${l.name} ×${qtyText(l.qty)}`).join(', ')}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </aside>
      {deleting ? (
        <ConfirmDialog
          title={tr('Delete this entry?', 'এই কেনা মুছবেন?')}
          text={tr('The quantities will be taken off the stock.', 'পরিমাণগুলো স্টক থেকে বাদ যাবে।')}
          confirm={tr('Delete', 'মুছুন')}
          danger
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            try {
              await call('books_purchase_delete', { id: deleting.id });
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
