// দোকানের পণ্য খোঁজা (কেনা/বিক্রির ফর্মে): ↑↓ দিয়ে বাছা, Enter-এ যোগ; তালিকায় না থাকলে নতুন নাম (কেনায়)
import { useRef, useState } from 'react';
import { cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { tr } from '@/lib/i18n';
import { findItems, money, qtyText, unitText, type ShopItem } from './model';

export function ItemPicker({ items, onPick, onNew, placeholder }: { items: ShopItem[]; onPick: (i: ShopItem) => void; onNew?: (name: string) => void; placeholder?: string }) {
  const [q, setQ] = useState('');
  const [sel, setSel] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const found = findItems(items, q);
  const term = q.trim();
  const exact = items.some((i) => i.name.toLowerCase() === term.toLowerCase());
  const pick = (i: ShopItem) => {
    onPick(i);
    setQ('');
    setSel(0);
    input.current?.focus();
  };
  const addNew = () => {
    if (!onNew || term.length < 2 || exact) return;
    onNew(term);
    setQ('');
  };
  return (
    <div className="col gap-6 bk-picker">
      <div className="rxp-search">
        <Icon name="search" />
        <input
          ref={input}
          className="input"
          value={q}
          maxLength={60}
          role="combobox"
          aria-expanded={found.length > 0}
          aria-label={placeholder ?? tr('Find a product', 'পণ্য খুঁজুন')}
          placeholder={placeholder ?? tr('Find a product', 'পণ্য খুঁজুন')}
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setSel((s) => Math.min(found.length - 1, s + 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(0, s - 1)); }
            else if (e.key === 'Enter') {
              e.preventDefault();
              if (found[sel]) pick(found[sel]);
              else addNew();
            } else if (e.key === 'Escape') setQ('');
          }}
        />
      </div>
      {term ? (
        <ul className="rxp-found" role="listbox">
          {found.map((i, k) => (
            <li key={i.id} role="option" aria-selected={k === sel} className={cx('bk-pick', k === sel && 'on')} onMouseEnter={() => setSel(k)} onClick={() => pick(i)}>
              <div className="col gap-2 grow">
                <span className="semi">{i.name}</span>
                <span className="t-body-s muted num">
                  {[i.qty != null ? tr('Stock ', 'স্টক ') + qtyText(i.qty) + (i.unit ? ` ${unitText(i.unit)}` : '') : '', i.salePrice != null ? money(i.salePrice) : ''].filter(Boolean).join(' · ')}
                </span>
              </div>
              <Icon name="add" />
            </li>
          ))}
          {!found.length && !onNew ? <li className="t-body-s muted">{tr('No match', 'মেলেনি')}</li> : null}
          {onNew && !exact && term.length >= 2 ? (
            <li className="rxp-found-free bk-pick" onClick={addNew}>
              <span className="grow t-body-s">{tr(`New: "${term}"`, `নতুন: "${term}"`)}</span>
              <Icon name="add" />
            </li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}
