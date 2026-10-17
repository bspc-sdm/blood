// ফার্মেসি — স্টকের তালিকা: সরকারি ওষুধের তালিকা থেকে খুঁজে যোগ (স্টকে আছে), সরানো (স্টকে নেই), একসাথে অনেকগুলো
// (অ্যাপের StockDialog, প্যানেলের stockView)। রোগীরা প্রেসক্রিপশন থেকে দেখেন কোন দোকানে কয়টা ওষুধ আছে।
import { useEffect, useMemo, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, ErrorText, IconButton, Loading, Tag, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, trPlain } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { Panel } from './common';
import type { Drug } from './model';

const k = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');

export function StockTab() {
  const qc = useQueryClient();
  const stock = useCall<{ items: string[]; updatedAt: number }>('stock_get', {});
  const names = stock.data?.items;
  const have = useMemo(() => new Set((names ?? []).map(k)), [names]);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function change(add: string[], remove: string[], label: string) {
    setBusy(label);
    setErr(null);
    try {
      const r = await call<{ items: string[] }>('stock_set', { add, remove });
      qc.setQueriesData<{ items: string[]; updatedAt: number }>({ predicate: (q) => q.queryKey[0] === 'stock_get' }, () => ({ items: r.items, updatedAt: Date.now() }));
      return true;
    } catch (e) {
      setErr(errorText(e));
      return false;
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="rxp-grid even">
      <div className="col gap-16 rxp-main">
        <AddPanel have={have} busy={busy} onChange={change} />
        <BulkPanel busy={busy} onChange={change} />
        <ErrorText>{err}</ErrorText>
      </div>
      <div className="col gap-16">
        <Panel
          title={tr('Stock list', 'স্টকের তালিকা')}
          icon="list_alt"
          count={names ? tr(`${names.length} medicines`, `${num(names.length)}টা ওষুধ`) : undefined}
        >
          <p className="t-body-s muted">
            {tr('Keep a stock list so patients can see which medicines you have.', 'স্টকের তালিকা রাখলে রোগীরা দেখতে পাবেন আপনার কাছে কোন ওষুধ আছে।')}
            {stock.data?.updatedAt ? ' ' + tr('Last changed ', 'শেষ বদল ') + timeAgo(stock.data.updatedAt) : ''}
          </p>
          {!names ? (stock.error ? <ErrorText>{errorText(stock.error)}</ErrorText> : <Loading />) : (
            <StockList names={names} busy={busy} onRemove={(n) => change([], [n], 'rm:' + n).then((ok) => ok && toast(trPlain(`${n} — marked out of stock`, `${n} — স্টকে নেই করা হয়েছে`)))} />
          )}
        </Panel>
      </div>
    </div>
  );
}

/** সরকারি তালিকা থেকে খোঁজা: ↑↓ দিয়ে বাছা, Enter-এ যোগ */
function AddPanel({ have, busy, onChange }: { have: Set<string>; busy: string | null; onChange: (add: string[], remove: string[], label: string) => Promise<boolean> }) {
  const [q, setQ] = useState('');
  const [found, setFound] = useState<Drug[]>([]);
  const [loading, setLoading] = useState(false);
  const [sel, setSel] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const term = q.trim();

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
        if (live) {
          setFound((r.items ?? []).slice(0, 10));
          setSel(0);
        }
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

  const exact = found.some((d) => k(d.name) === k(term));
  async function add(name: string) {
    if (have.has(k(name))) return;
    if (await onChange([name], [], 'add:' + name)) {
      toast(trPlain(`${name} — added to stock`, `${name} — স্টকে যোগ হয়েছে`));
      input.current?.focus();
    }
  }

  return (
    <Panel title={tr('Add a medicine', 'ওষুধ যোগ করুন')} icon="add">
      <div className="rxp-search">
        <Icon name="search" />
        <input
          ref={input}
          className="input"
          autoFocus
          value={q}
          maxLength={60}
          role="combobox"
          aria-expanded={found.length > 0}
          aria-controls="rxp-drug-list"
          aria-label={tr('Add a medicine', 'ওষুধ যোগ করুন')}
          placeholder={tr('Type the medicine name', 'ওষুধের নাম লিখুন')}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              setSel((s) => Math.min(found.length - 1, s + 1));
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              setSel((s) => Math.max(0, s - 1));
            } else if (e.key === 'Enter') {
              e.preventDefault();
              const d = found[sel];
              if (d) add(d.name);
              else if (term.length >= 2) add(term);
            } else if (e.key === 'Escape') setQ('');
          }}
        />
      </div>
      {term.length < 2 ? (
        <p className="t-body-s muted">{tr('From the government medicine list. ↑ ↓ to choose, Enter to add.', 'সরকারি ওষুধের তালিকা থেকে। ↑ ↓ দিয়ে বাছুন, Enter চাপলে যোগ হবে।')}</p>
      ) : loading && !found.length ? <Loading /> : (
        <ul className="rxp-found" id="rxp-drug-list" role="listbox">
          {found.map((d, i) => {
            const in_ = have.has(k(d.name));
            return (
              <li key={d.i} role="option" aria-selected={i === sel} className={cx(i === sel && 'on')} onMouseEnter={() => setSel(i)}>
                <div className="col gap-2 grow">
                  <span className="semi">{d.name}</span>
                  <span className="t-body-s muted">{[d.generic, d.form, d.company].filter(Boolean).join(' · ')}</span>
                </div>
                {in_ ? (
                  <Tag tone="good">{tr('Added', 'আছে')}</Tag>
                ) : (
                  <Button small icon="add" busy={busy === 'add:' + d.name} disabled={!!busy} onClick={() => add(d.name)}>{tr('Add', 'যোগ')}</Button>
                )}
              </li>
            );
          })}
          {!exact && !loading ? (
            <li className="rxp-found-free">
              <span className="grow t-body-s">{found.length ? tr('Not in the list?', 'তালিকায় নেই?') : tr('No match in the list', 'তালিকায় মেলেনি')}</span>
              <Button small variant="outline" icon="add" disabled={!!busy || have.has(k(term))} onClick={() => add(term)}>
                {tr(`Add "${term}"`, `"${term}" যোগ করুন`)}
              </Button>
            </li>
          ) : null}
        </ul>
      )}
    </Panel>
  );
}

/** একসাথে অনেকগুলো: প্রতি লাইনে একটা নাম */
function BulkPanel({ busy, onChange }: { busy: string | null; onChange: (add: string[], remove: string[], label: string) => Promise<boolean> }) {
  const [text, setText] = useState('');
  const lines = [...new Set(text.split('\n').map((x) => x.trim()).filter(Boolean))];
  return (
    <details className="card rxp-panel rxp-bulk">
      <summary className="t-title-s">{tr('Add many at once', 'অনেকগুলো একসাথে যোগ করুন')}</summary>
      <textarea
        className="textarea"
        rows={6}
        value={text}
        onChange={(e) => setText(e.target.value)}
        aria-label={tr('One name per line', 'প্রতি লাইনে একটা নাম')}
        placeholder={tr('One name per line', 'প্রতি লাইনে একটা নাম')}
      />
      <div className="row">
        <span className="t-body-s muted grow">{lines.length ? tr(`${lines.length} names`, `${num(lines.length)}টা নাম`) : ''}</span>
        <Button
          variant="tonal"
          busy={busy === 'bulk'}
          disabled={!lines.length || !!busy}
          onClick={async () => {
            if (await onChange(lines, [], 'bulk')) {
              setText('');
              toast(tr('Added to stock', 'স্টকে যোগ হয়েছে'));
            }
          }}
        >
          {tr('Add all', 'সব যোগ করুন')}
        </Button>
      </div>
    </details>
  );
}

function StockList({ names, busy, onRemove }: { names: string[]; busy: string | null; onRemove: (n: string) => void }) {
  const [f, setF] = useState('');
  const t = k(f);
  const shown = t ? names.filter((n) => k(n).includes(t)) : names;
  return (
    <div className="col gap-8">
      {names.length > 8 ? (
        <div className="rxp-search">
          <Icon name="search" />
          <input className="input" value={f} onChange={(e) => setF(e.target.value)} placeholder={tr('Find in my list', 'তালিকায় খুঁজুন')} aria-label={tr('Find in my list', 'তালিকায় খুঁজুন')} />
        </div>
      ) : null}
      {!names.length ? (
        <p className="t-body-m muted">{tr('No medicines in the list yet', 'তালিকায় এখনো কোনো ওষুধ নেই')}</p>
      ) : !shown.length ? (
        <p className="t-body-m muted">{tr('No match', 'মেলেনি')}</p>
      ) : (
        <ul className="rxp-stock">
          {shown.slice(0, 500).map((n) => (
            <li key={n}>
              <span className="grow ellipsis" title={n}>{n}</span>
              {busy === 'rm:' + n ? <Loading inline /> : (
                <IconButton small icon="close" label={tr('Remove', 'সরান') + ': ' + n} disabled={!!busy} onClick={() => onRemove(n)} />
              )}
            </li>
          ))}
        </ul>
      )}
      {shown.length > 500 ? <p className="t-body-s muted">{tr('Search to see the rest', 'বাকিগুলো দেখতে খুঁজুন')}</p> : null}
    </div>
  );
}
