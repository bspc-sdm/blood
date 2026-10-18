// ফার্মেসি — "পণ্য ও স্টক": সরকারি ওষুধের তালিকা থেকে বা নিজে লিখে পণ্য; পরিমাণ, কেনা দাম, বিক্রি দাম, ছাড়, মেয়াদ — সব ঐচ্ছিক।
// শুধু নাম রাখলেও আগের "স্টকের তালিকা"-র মতো চলে (রোগীরা দেখেন কোন দোকানে আছে)। অ্যাপের PharmacyBooks.kt-এর ItemsPane।
import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, ChoiceChips, Dialog, ConfirmDialog, ErrorText, Loading, Segmented, Select, Tag, TextInput, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, trPlain } from '@/lib/i18n';
import { Panel, useWide } from '../common';
import type { Drug } from '../model';
import { UNITS, discText, expiryText, money, numInput, numStr, qtyText, readNum, unitText, useBooksRefresh, type Disc, type ShopItem } from './model';
import './books.css';

type Filter = 'all' | 'low' | 'exp' | 'out';
type Draft = Partial<ShopItem> & { name: string };

export function ItemsTab() {
  const q = useCall<{ items: ShopItem[] }>('books_items', {});
  const items = q.data?.items;
  const [filter, setFilter] = useState<Filter>('all');
  const [find, setFind] = useState('');
  const [editing, setEditing] = useState<Draft | null>(null);
  const [adjusting, setAdjusting] = useState<ShopItem | null>(null);
  const refresh = useBooksRefresh();
  const have = useMemo(() => new Set((items ?? []).map((i) => i.name.trim().toLowerCase())), [items]);

  const t = find.trim().toLowerCase();
  const shown = (items ?? []).filter((i) =>
    (filter === 'all' || (filter === 'low' && i.low) || (filter === 'exp' && i.expDays != null && i.expDays <= 60) || (filter === 'out' && !i.inStock))
    && (!t || i.name.toLowerCase().includes(t) || i.generic.toLowerCase().includes(t)));
  const count = (f: Filter) => (items ?? []).filter((i) => (f === 'low' ? i.low : f === 'exp' ? i.expDays != null && i.expDays <= 60 : !i.inStock)).length;

  return (
    <div className="col gap-16">
      <div className="rxp-two bk-add-row">
        <AddPanel have={have} onPick={(d) => setEditing(d)} onAdded={refresh} />
        <BulkPanel onAdded={refresh} />
      </div>
      <Panel
          title={tr('Products & stock', 'পণ্য ও স্টক')}
          icon="list_alt"
          count={items ? tr(`${items.length} products`, `${num(items.length)}টা পণ্য`) : undefined}
        >
          <div className="row wrap gap-8">
            <div className="rxp-search rxp-search-sm">
              <Icon name="search" />
              <input className="input" value={find} onChange={(e) => setFind(e.target.value)} placeholder={tr('Find in my list', 'তালিকায় খুঁজুন')} aria-label={tr('Find in my list', 'তালিকায় খুঁজুন')} />
            </div>
            <ChoiceChips
              value={filter}
              onChange={(v: Filter) => setFilter(v === filter ? 'all' : v)}
              options={[
                { value: 'low', label: tr('Low stock', 'কম স্টক') + (items ? ` (${num(count('low'))})` : '') },
                { value: 'exp', label: tr('Expiring', 'মেয়াদ শেষের পথে') + (items ? ` (${num(count('exp'))})` : '') },
                { value: 'out', label: tr('Out of stock', 'স্টকে নেই') + (items ? ` (${num(count('out'))})` : '') },
              ]}
            />
          </div>
          {!items ? (q.error ? <ErrorText>{errorText(q.error)}</ErrorText> : <Loading />) : (
            <ItemList items={shown} total={items.length} onEdit={setEditing} onAdjust={setAdjusting} onChanged={refresh} />
          )}
      </Panel>
      {editing ? <ItemDialog key={editing.id ?? editing.name} start={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} onAdjust={(i) => { setEditing(null); setAdjusting(i); }} /> : null}
      {adjusting ? <AdjustDialog item={adjusting} onClose={() => setAdjusting(null)} onDone={() => { setAdjusting(null); refresh(); }} /> : null}
    </div>
  );
}

function stockTag(i: ShopItem) {
  if (!i.inStock) return <Tag tone="error">{tr('Out of stock', 'স্টকে নেই')}</Tag>;
  if (i.low) return <Tag tone="info">{tr('Low', 'কম')}</Tag>;
  return null;
}
function expTag(i: ShopItem) {
  if (!i.expiry) return null;
  const soon = i.expDays != null && i.expDays <= 60;
  return <span className={cx('t-body-s bk-nowrap', soon ? 'c-error semi' : 'muted')}>{expiryText(i.expiry)}{i.expDays != null && i.expDays < 0 ? tr(' (expired)', ' (মেয়াদ শেষ)') : ''}</span>;
}

function StockSwitch({ i, onChanged }: { i: ShopItem; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <input
      type="checkbox"
      role="switch"
      className="switch"
      checked={i.inStock}
      disabled={busy}
      aria-label={`${i.name}: ${tr('In stock', 'স্টকে আছে')}`}
      onChange={async (e) => {
        setBusy(true);
        try {
          await call('books_item_stock', { id: i.id, on: e.target.checked });
          onChanged();
        } catch (er) {
          toast(errorText(er));
        } finally {
          setBusy(false);
        }
      }}
    />
  );
}

function ItemList({ items, total, onEdit, onAdjust, onChanged }: { items: ShopItem[]; total: number; onEdit: (i: ShopItem) => void; onAdjust: (i: ShopItem) => void; onChanged: () => void }) {
  const wide = useWide();
  if (!total) return <p className="t-body-m muted">{tr('No products yet', 'এখনো কোনো পণ্য নেই')}</p>;
  if (!items.length) return <p className="t-body-m muted">{tr('No match', 'মেলেনি')}</p>;
  const list = items.slice(0, 400);
  const priceCell = (i: ShopItem) => (
    <div className="col gap-2 bk-nowrap">
      <span className="num">{money(i.price)}</span>
      {i.disc ? <span className="t-body-s c-primary num">−{discText(i.disc)} = {money(i.salePrice)}</span> : null}
    </div>
  );
  return (
    <>
      {wide ? (
        <div className="table-wrap rxp-table-wrap">
          <table className="table rxp-table bk-table">
            <thead>
              <tr>
                <th>{tr('Product', 'পণ্য')}</th>
                <th className="bk-r">{tr('Qty', 'পরিমাণ')}</th>
                <th className="bk-r">{tr('Cost', 'কেনা দাম')}</th>
                <th className="bk-r">{tr('Sale price', 'বিক্রি দাম')}</th>
                <th>{tr('Expiry', 'মেয়াদ')}</th>
                <th>{tr('In stock', 'স্টকে আছে')}</th>
                <th><span className="sr-only">{tr('Edit', 'বদলান')}</span></th>
              </tr>
            </thead>
            <tbody>
              {list.map((i) => (
                <tr key={i.id} className={cx(!i.inStock && 'bk-out')}>
                  <td>
                    <div className="col gap-2">
                      <span className="row gap-6"><span className="semi">{i.name}</span>{stockTag(i)}</span>
                      {i.generic || i.form ? <span className="t-body-s muted">{[i.generic, i.form].filter(Boolean).join(' · ')}</span> : null}
                    </div>
                  </td>
                  <td className="bk-r num rxp-nowrap">{qtyText(i.qty)}{i.qty != null && i.unit ? <span className="muted"> {unitText(i.unit)}</span> : null}</td>
                  <td className="bk-r num">{money(i.cost)}</td>
                  <td className="bk-r">{priceCell(i)}</td>
                  <td>{expTag(i)}</td>
                  <td><StockSwitch i={i} onChanged={onChanged} /></td>
                  <td className="rxp-td-actions">
                    <div className="row gap-4 rxp-row-actions">
                      <Button small variant="text" icon="edit" onClick={() => onEdit(i)}>{tr('Edit', 'বদলান')}</Button>
                      <Button small variant="text" onClick={() => onAdjust(i)}>{tr('Adjust', 'নষ্ট/গণনা')}</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="col gap-8">
          {list.map((i) => (
            <div key={i.id} className={cx('bk-card', !i.inStock && 'bk-out')}>
              <div className="row top">
                <div className="col gap-2 grow">
                  <span className="row wrap gap-6"><span className="semi">{i.name}</span>{stockTag(i)}</span>
                  <span className="t-body-s muted num">
                    {[i.qty != null ? `${qtyText(i.qty)} ${unitText(i.unit)}`.trim() : '', i.cost != null ? tr('cost ', 'কেনা ') + money(i.cost) : '', i.price != null ? tr('price ', 'বিক্রি ') + money(i.price) : ''].filter(Boolean).join(' · ')}
                  </span>
                  {i.disc ? <span className="t-body-s c-primary">{tr('Disc ', 'ছাড় ')}{discText(i.disc)} → {money(i.salePrice)}</span> : null}
                  {expTag(i)}
                </div>
                <StockSwitch i={i} onChanged={onChanged} />
              </div>
              <div className="row gap-4">
                <Button small variant="text" icon="edit" onClick={() => onEdit(i)}>{tr('Edit', 'বদলান')}</Button>
                <Button small variant="text" onClick={() => onAdjust(i)}>{tr('Adjust', 'নষ্ট/গণনা')}</Button>
              </div>
            </div>
          ))}
        </div>
      )}
      {items.length > 400 ? <p className="t-body-s muted">{tr('Search to see the rest', 'বাকিগুলো দেখতে খুঁজুন')}</p> : null}
    </>
  );
}

/** সরকারি তালিকা থেকে খোঁজা: "যোগ" = শুধু নাম (আগের মতো), "দাম সহ" = পুরো ফর্ম */
function AddPanel({ have, onPick, onAdded }: { have: Set<string>; onPick: (d: Draft) => void; onAdded: () => void }) {
  const [q, setQ] = useState('');
  const [found, setFound] = useState<Drug[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const term = q.trim();
  const k = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');

  useEffect(() => {
    if (term.length < 2) {
      setFound([]);
      setLoading(false);
      return;
    }
    let live = true;
    setLoading(true);
    const t = setTimeout(async () => {
      try {
        const r = await call<{ items: Drug[] }>('drug_search', { q: term });
        if (live) setFound((r.items ?? []).slice(0, 8));
      } catch {
        if (live) setFound([]);
      } finally {
        if (live) setLoading(false);
      }
    }, 300);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [term]);

  async function quick(d: { name: string; generic?: string; form?: string }) {
    setBusy(d.name);
    try {
      await call('books_item_save', { name: d.name, generic: d.generic ?? '', form: d.form ?? '' });
      toast(trPlain(`${d.name} — added`, `${d.name} — যোগ হয়েছে`));
      onAdded();
      input.current?.focus();
    } catch (e) {
      toast(errorText(e));
    } finally {
      setBusy(null);
    }
  }
  const exact = found.some((d) => k(d.name) === k(term));
  return (
    <Panel title={tr('Add a product', 'পণ্য যোগ করুন')} icon="add">
      <div className="rxp-search">
        <Icon name="search" />
        <input ref={input} className="input" value={q} maxLength={60} role="combobox" aria-expanded={found.length > 0} onChange={(e) => setQ(e.target.value)} placeholder={tr('Type the medicine name', 'ওষুধের নাম লিখুন')} aria-label={tr('Add a product', 'পণ্য যোগ করুন')} />
      </div>
      {term.length < 2 ? null : loading && !found.length ? <Loading /> : (
        <ul className="rxp-found">
          {found.map((d) => {
            const in_ = have.has(k(d.name));
            return (
              <li key={d.i}>
                <div className="col gap-2 grow">
                  <span className="semi">{d.name}</span>
                  <span className="t-body-s muted">{[d.generic, d.form, d.company].filter(Boolean).join(' · ')}</span>
                </div>
                {in_ ? <Tag tone="good">{tr('Added', 'আছে')}</Tag> : (
                  <div className="row gap-4 none">
                    <Button small variant="text" onClick={() => onPick({ name: d.name, generic: d.generic, form: d.form })}>{tr('With price', 'দাম সহ')}</Button>
                    <Button small icon="add" busy={busy === d.name} disabled={!!busy} onClick={() => quick(d)}>{tr('Add', 'যোগ')}</Button>
                  </div>
                )}
              </li>
            );
          })}
          {!exact && !loading && !have.has(k(term)) ? (
            <li className="rxp-found-free">
              <span className="grow t-body-s">{found.length ? tr('Not in the list?', 'তালিকায় নেই?') : tr('No match in the list', 'তালিকায় মেলেনি')}</span>
              <Button small variant="outline" icon="add" onClick={() => onPick({ name: term })}>{tr(`Add "${term}"`, `"${term}" যোগ করুন`)}</Button>
            </li>
          ) : null}
        </ul>
      )}
    </Panel>
  );
}

/** একসাথে অনেকগুলো নাম (প্রতি লাইনে একটা) — আগের স্টকের তালিকার মতো */
function BulkPanel({ onAdded }: { onAdded: () => void }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const lines = [...new Set(text.split('\n').map((x) => x.trim()).filter(Boolean))];
  return (
    <details className="card rxp-panel rxp-bulk">
      <summary className="t-title-s">{tr('Add many at once', 'অনেকগুলো একসাথে যোগ করুন')}</summary>
      <textarea className="textarea" rows={6} value={text} onChange={(e) => setText(e.target.value)} aria-label={tr('One name per line', 'প্রতি লাইনে একটা নাম')} placeholder={tr('One name per line', 'প্রতি লাইনে একটা নাম')} />
      <div className="row">
        <span className="t-body-s muted grow">{lines.length ? tr(`${lines.length} names`, `${num(lines.length)}টা নাম`) : ''}</span>
        <Button
          variant="tonal"
          busy={busy}
          disabled={!lines.length}
          onClick={async () => {
            setBusy(true);
            try {
              await call('stock_set', { add: lines });
              setText('');
              toast(tr('Added', 'যোগ হয়েছে'));
              onAdded();
            } catch (e) {
              toast(errorText(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          {tr('Add all', 'সব যোগ করুন')}
        </Button>
      </div>
    </details>
  );
}

/** পণ্যের ফর্ম: নাম ছাড়া সব ঐচ্ছিক */
function ItemDialog({ start, onClose, onSaved, onAdjust }: { start: Draft; onClose: () => void; onSaved: () => void; onAdjust: (i: ShopItem) => void }) {
  const edit = start.id ? (start as ShopItem) : null;
  const [name, setName] = useState(start.name);
  const [unit, setUnit] = useState(start.unit ?? '');
  const [qty, setQty] = useState(numStr(start.qty));
  const [cost, setCost] = useState(numStr(start.cost));
  const [price, setPrice] = useState(numStr(start.price));
  const [dType, setDType] = useState<'pct' | 'amt'>(start.disc?.type ?? 'pct');
  const [dVal, setDVal] = useState(numStr(start.disc?.v));
  const [lowAt, setLowAt] = useState(numStr(start.lowAt));
  const [expiry, setExpiry] = useState(start.expiry ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [confirmDel, setConfirmDel] = useState(false);

  const nums = { qty: readNum(qty), cost: readNum(cost), price: readNum(price), d: readNum(dVal), lowAt: readNum(lowAt) };
  const bad = Object.values(nums).some((v) => v != null && Number.isNaN(v));
  const disc: Disc = nums.d ? { type: dType, v: nums.d } : null;
  const after = nums.price != null && disc ? Math.max(0, nums.price - (dType === 'pct' ? (nums.price * disc.v) / 100 : disc.v)) : null;

  async function save() {
    if (!name.trim() || bad) return;
    setBusy(true);
    setErr(null);
    try {
      await call('books_item_save', {
        ...(edit ? { id: edit.id } : {}), name, generic: start.generic ?? '', form: start.form ?? '', unit,
        qty: nums.qty, cost: nums.cost, price: nums.price, disc, lowAt: nums.lowAt, expiry: expiry || null,
      });
      toast(tr('Saved', 'সেভ হয়েছে'));
      onSaved();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const field = (label: string, v: string, set: (s: string) => void, hint?: string) => (
    <TextInput label={label} value={v} inputMode="decimal" className="input num" onChange={(e) => set(numInput(e.target.value))} hint={hint} />
  );
  return (
    <Dialog
      onClose={onClose}
      title={edit ? edit.name : tr('New product', 'নতুন পণ্য')}
      actions={
        <>
          {edit ? <Button variant="danger-text" onClick={() => setConfirmDel(true)}>{tr('Delete', 'মুছুন')}</Button> : null}
          {edit ? <Button variant="text" onClick={() => onAdjust(edit)}>{tr('Adjust', 'নষ্ট/গণনা')}</Button> : null}
          <span className="grow" />
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} disabled={!name.trim() || bad} onClick={save}>{tr('Save', 'সেভ')}</Button>
        </>
      }
    >
      <form className="col gap-12" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <TextInput label={tr('Product name', 'পণ্যের নাম')} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} hint={start.generic ? [start.generic, start.form].filter(Boolean).join(' · ') : undefined} />
        <p className="t-body-s muted">{tr('Everything below is optional.', 'নিচের সব ঐচ্ছিক।')}</p>
        <div className="bk-form-grid">
          {field(tr('Quantity', 'পরিমাণ'), qty, setQty, edit && edit.qty != null ? tr('Change = count correction', 'বদলালে "গণনা ঠিক করা" হিসেবে থাকবে') : undefined)}
          <Select label={tr('Unit', 'একক')} value={unit} onChange={(e) => setUnit(e.target.value)} placeholder="—" options={UNITS.map((u) => ({ value: u, label: unitText(u) }))} />
          {field(tr('Cost price (per unit)', 'কেনা দাম (প্রতি একক)'), cost, setCost)}
          {field(tr('Sale price / MRP (per unit)', 'বিক্রি দাম / MRP (প্রতি একক)'), price, setPrice)}
        </div>
        <div className="col gap-6">
          <span className="t-label-l">{tr('Discount', 'ছাড়')}</span>
          <div className="row wrap gap-8">
            <Segmented value={dType} onChange={setDType} label={tr('Discount', 'ছাড়')} options={[{ value: 'pct', label: '%' }, { value: 'amt', label: tr('Tk', 'টাকা') }]} />
            <input className="input num bk-small-input" inputMode="decimal" value={dVal} onChange={(e) => setDVal(numInput(e.target.value))} aria-label={tr('Discount', 'ছাড়')} placeholder="০" />
            {after != null ? <span className="t-body-s c-primary">{tr('Sells at ', 'বিক্রি হবে ')}{money(Math.round(after * 100) / 100)}</span> : null}
          </div>
        </div>
        <div className="bk-form-grid">
          {field(tr('Reorder at (qty)', 'কত থাকলে আবার আনবেন'), lowAt, setLowAt)}
          <TextInput label={tr('Expiry (month/year)', 'মেয়াদ (মাস/বছর)')} type="month" value={expiry} onChange={(e) => setExpiry(e.target.value)} />
        </div>
        {nums.cost != null && after != null && after < nums.cost ? <p className="t-body-s c-error">{tr('Sells below cost', 'কেনা দামের চেয়ে কমে বিক্রি হবে')}</p> : null}
        {nums.cost != null && after == null && nums.price != null && nums.price < nums.cost ? <p className="t-body-s c-error">{tr('Sells below cost', 'কেনা দামের চেয়ে কমে বিক্রি হবে')}</p> : null}
        <ErrorText>{err}</ErrorText>
        <button type="submit" hidden />
      </form>
      {confirmDel && edit ? (
        <ConfirmDialog
          title={tr('Delete this product?', 'পণ্যটা মুছবেন?')}
          text={edit.name}
          confirm={tr('Delete', 'মুছুন')}
          danger
          onClose={() => setConfirmDel(false)}
          onConfirm={async () => {
            try {
              await call('books_item_delete', { id: edit.id });
              onSaved();
            } catch (e) {
              setErr(errorText(e));
              setConfirmDel(false);
            }
          }}
        />
      ) : null}
    </Dialog>
  );
}

/** নষ্ট/মেয়াদ শেষ (কেনা দামে ক্ষতি) বা গণনা ঠিক করা */
export function AdjustDialog({ item, onClose, onDone }: { item: ShopItem; onClose: () => void; onDone: () => void }) {
  const [kind, setKind] = useState<'writeoff' | 'count'>('writeoff');
  const [qty, setQty] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const n = readNum(qty);
  const ok = n != null && !Number.isNaN(n) && (kind === 'count' || n > 0);
  return (
    <Dialog
      onClose={onClose}
      size="small"
      title={item.name}
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={!ok}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await call('books_adjust', { itemId: item.id, kind, qty: n, reason });
                toast(tr('Saved', 'সেভ হয়েছে'));
                onDone();
              } catch (e) {
                setErr(errorText(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            {tr('Save', 'সেভ')}
          </Button>
        </>
      }
    >
      <div className="col gap-12">
        <Segmented value={kind} onChange={setKind} options={[{ value: 'writeoff', label: tr('Damaged / expired', 'নষ্ট / মেয়াদ শেষ') }, { value: 'count', label: tr('Correct the count', 'গণনা ঠিক করা') }]} />
        <span className="t-body-s muted">{tr('Now: ', 'এখন: ')}{qtyText(item.qty)} {item.qty != null ? unitText(item.unit) : ''}</span>
        <TextInput
          label={kind === 'writeoff' ? tr('How many', 'কয়টা') : tr('Counted', 'গুনে পেলেন')}
          value={qty}
          inputMode="decimal"
          className="input num"
          autoFocus
          onChange={(e) => setQty(numInput(e.target.value))}
          hint={kind === 'writeoff' && item.cost != null && n ? tr('Loss ', 'ক্ষতি ') + money(Math.round(n * item.cost * 100) / 100) : undefined}
        />
        {kind === 'writeoff' ? <TextInput label={tr('Reason (optional)', 'কারণ (ঐচ্ছিক)')} value={reason} maxLength={120} onChange={(e) => setReason(e.target.value)} /> : null}
        <ErrorText>{err}</ErrorText>
      </div>
    </Dialog>
  );
}
