// ফার্মেসির তালিকা (২.৩২.০, পারভেজ): আগে "দোকানে গিয়ে কিনব" / "অনলাইনে অর্ডার" বাছাই (মনে থাকে), উপরে প্রেসক্রিপশন বাছাই,
// আর প্রতিটা কার্ডে প্রেসক্রিপশনের হিসাব — কত % ওষুধ আছে, মোট ছাড়, আনুমানিক দাম, ছাড়ের শর্ত (অ্যাপের Pharmacy.kt-এর মতো)
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, IconLine, ListRow, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call } from '@/lib/api';
import { tr, useLang } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import type { Rx } from '@/sections/health/Prescriptions';
import { deliveryText, discountTermsText, rxDiscountText, rxHaveText, rxPayableText, type BuyMode, type Pharmacy } from './data';

// ---------- কীভাবে কিনবেন (শেষ বাছাই মনে থাকে) ----------
const MODE_KEY = 'lf.pharmacy.buyMode';
export function useBuyMode(): [BuyMode | null, (m: BuyMode) => void] {
  const [mode, set] = useState<BuyMode | null>(() => {
    const v = store.get(MODE_KEY);
    return v === 'shop' || v === 'online' ? v : null;
  });
  return [mode, (m) => {
    store.set(MODE_KEY, m);
    set(m);
  }];
}

/** দুটো বড় ঘর পাশাপাশি — বাছা না থাকলে নিচে তালিকা আসে না */
export function BuyModePicker({ value, onChange }: { value: BuyMode | null; onChange: (m: BuyMode) => void }) {
  const opts: { m: BuyMode; icon: string; label: string; sub: string }[] = [
    { m: 'shop', icon: 'storefront', label: tr("I'll buy at the shop", 'দোকানে গিয়ে কিনব'), sub: tr('All pharmacies nearby', 'কাছের সব ফার্মেসি') },
    { m: 'online', icon: 'local_shipping', label: tr('Order online', 'অনলাইনে অর্ডার'), sub: tr('Home delivery', 'বাসায় ডেলিভারি') },
  ];
  return (
    <div className="col gap-6">
      {!value ? <span className="t-title-s">{tr('How will you buy?', 'কীভাবে কিনবেন?')}</span> : null}
      <div className={cx('ph-mode', !value && 'ask')} role="radiogroup" aria-label={tr('How will you buy?', 'কীভাবে কিনবেন?')}>
        {opts.map((o) => (
          <button key={o.m} type="button" role="radio" aria-checked={value === o.m} className="ph-mode-opt" onClick={() => onChange(o.m)} data-testid={`ph-mode-${o.m}`}>
            <Icon name={o.icon} />
            <span className="col gap-2" style={{ minWidth: 0 }}>
              <span className="t-label-l">{o.label}</span>
              <span className="t-body-s sub">{o.sub}</span>
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ---------- প্রেসক্রিপশন বাছাই ----------
/** নিজের (কেয়ারগিভার মোডে রোগীর) চালু, ওষুধ লেখা প্রেসক্রিপশন — নতুনটা আগে; অতিথি হলে খালি। RxPickDialog-এর একই চাবি। */
export function useMyMedRx(): { list: Rx[]; ready: boolean } {
  const lang = useLang();
  const care = useCareMode();
  const { uid, guest } = useSession();
  const q = useQuery({
    queryKey: ['rx_mine', care?.uid ?? null, lang, uid],
    queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
    enabled: !guest,
  });
  if (guest) return { list: [], ready: true };
  const list = (q.data ?? []).filter((r) => r.status === 'active' && (r.medicines ?? []).length > 0).sort((a, b) => b.createdAt - a.createdAt);
  return { list, ready: !q.isPending };
}

const rxLabel = (r: Rx) => [r.doctor?.name || tr('Doctor', 'ডাক্তার'), formatDate(r.createdAt)].join(' · ');

/** "প্রেসক্রিপশন: ডা. … · তারিখ ▾" — চাপলে তালিকা; "প্রেসক্রিপশন ছাড়া" বাছলে সাধারণ তালিকা */
export function RxSelector({ list, value, onChange }: { list: Rx[]; value: Rx | null; onChange: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="ph-rxsel" onClick={() => setOpen(true)} data-testid="ph-rx-select">
        <Icon name="medication" />
        <span className="grow ellipsis">
          {value ? tr('Prescription: ', 'প্রেসক্রিপশন: ') + rxLabel(value) : tr('Without a prescription', 'প্রেসক্রিপশন ছাড়া')}
        </span>
        <Icon name="expand_more_round" />
      </button>
      {open ? (
        <Dialog onClose={() => setOpen(false)} size="small" title={tr('Which prescription?', 'কোন প্রেসক্রিপশন?')}>
          <p className="t-body-s muted">{tr('Each pharmacy shows how many of its medicines it has and the likely price.', 'প্রতিটা ফার্মেসিতে এর কত ওষুধ আছে আর আনুমানিক দাম দেখাবে।')}</p>
          <div className="col gap-4">
            {list.map((r) => (
              <ListRow
                key={r.id}
                icon="medication"
                title={rxLabel(r)}
                sub={(r.medicines ?? []).map((m) => m.name).join(', ')}
                trailing={value?.id === r.id ? <Icon name="check_circle" className="c-primary" /> : undefined}
                onClick={() => {
                  onChange(r.id);
                  setOpen(false);
                }}
              />
            ))}
            <ListRow
              icon="storefront"
              title={tr('Without a prescription', 'প্রেসক্রিপশন ছাড়া')}
              trailing={!value ? <Icon name="check_circle" className="c-primary" /> : undefined}
              onClick={() => {
                onChange('none');
                setOpen(false);
              }}
            />
          </div>
        </Dialog>
      ) : null}
    </>
  );
}

// ---------- কার্ড আর বিস্তারিতের প্রেসক্রিপশনের হিসাব ----------
/**
 * প্রেসক্রিপশন বাছা থাকলে: কত % ওষুধ আছে, মোট ছাড় (দাম না জানলে দোকানের সাধারণ %), আনুমানিক দাম; সবসময় ছাড়ের শর্ত
 * আর (অনলাইনে) ডেলিভারির চার্জ ও কত টাকার বেশিতে ফ্রি। প্রেসক্রিপশন না থাকলে কিছুই নয় (কার্ডের সাধারণ লাইনগুলোই)।
 */
export function PharmacyRxInfo({ p, mode, big }: { p: Pharmacy; mode: BuyMode | null; big?: boolean }) {
  const r = p.rx;
  if (!r) return null;
  const terms = discountTermsText(p);
  const delivery = deliveryText(p, mode === 'online');
  const color = 'var(--on-surface)';
  return (
    <div className={cx('ph-rxinfo', big && 'big')} data-testid="ph-rxinfo">
      <IconLine icon={r.keepsStock && r.total ? (r.have === r.total ? 'check_circle' : 'medication') : 'info'} color={color}>
        <span className={cx(r.keepsStock && r.total > 0 && 'semi')}>{rxHaveText(r)}</span>
      </IconLine>
      {r.total ? <IconLine icon="payments" color={color}><span className="semi">{rxPayableText(r)}</span></IconLine> : null}
      {rxDiscountText(p, r) ? <IconLine icon="verified" color="var(--primary)">{rxDiscountText(p, r)}</IconLine> : null}
      {terms && !r.belowMin ? <IconLine icon="info">{terms}</IconLine> : null}
      {delivery ? <IconLine icon="local_shipping">{delivery}</IconLine> : null}
    </div>
  );
}
