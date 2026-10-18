// ফার্মেসি ও প্যাথলজির কাজের পাতার সাধারণ অংশ: খোলস, অনুমোদনের দরজা, কোড লেখার ঘর
import { useRef, useState, type ReactNode, type RefObject } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, EmptyState, ErrorText, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { SubPageBar, Workspace } from '@/shell/templates';
import { useMedia, useMobile } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { statusText, useWorkGate } from '../../work/shared';
import { cleanCode, validCode } from './model';
import './rxpartner.css';

/** চওড়া পর্দা: টেবিল; নইলে কার্ড */
export const useWide = () => useMedia('(min-width: 1000px)');
/** পাশে সেটিং-এর ঘরসহ পাতার ছক (অনলাইন অর্ডার): মাঝের জায়গা ছোট, তাই ছক শুধু বড় পর্দায় — ১৩৬৬ পর্দায় কাজের বোতাম কেটে যেত */
export const useWideWithSide = () => useMedia('(min-width: 1600px)');

/** পাতা: উপরে "পেছনে" বার (নিজের ব্যবসার পাতায় ফেরা), নিচে চওড়া কাজের জায়গা */
export function PartnerPage({ title, backTo, actions, children }: { title: string; backTo: string; actions?: ReactNode; children: ReactNode }) {
  const mobile = useMobile();
  return (
    <>
      <SubPageBar title={title} backTo={backTo} actions={!mobile && actions ? <div className="row gap-8 rxp-bar-actions">{actions}</div> : undefined} />
      <Workspace title={title} actions={mobile ? actions : undefined}>{children}</Workspace>
    </>
  );
}

/** লগইন/কাজ আনা বাকি, বা ব্যবসা নেই/অনুমোদন হয়নি — বন্ধুসুলভ অবস্থা আর নিজের ব্যবসার পাতার লিংক */
export function usePartnerGate(kind: 'pharmacy' | 'lab'): ReactNode | null {
  const s = useSession();
  const gate = useWorkGate();
  if (gate) return gate;
  const biz = (kind === 'pharmacy' ? s.work.pharmacy : s.work.lab) as { status: string; name: string } | null;
  if (biz && biz.status === 'approved') return null;
  const to = kind === 'pharmacy' ? '/work/pharmacy' : '/work/lab';
  const icon = kind === 'pharmacy' ? 'storefront' : 'local_hospital';
  const title = !biz
    ? kind === 'pharmacy' ? tr('Add your pharmacy first', 'আগে আপনার ফার্মেসি যোগ করুন') : tr('Add your lab first', 'আগে আপনার প্যাথলজি যোগ করুন')
    : kind === 'pharmacy' ? tr('Only approved pharmacies can do this', 'শুধু অনুমোদিত ফার্মেসি এটা করতে পারে') : tr('Only approved labs can do this', 'শুধু অনুমোদিত প্যাথলজি এটা করতে পারে');
  const text = !biz
    ? kind === 'pharmacy'
      ? tr('Once your pharmacy is approved, give medicines from prescriptions, take online orders and keep your stock here.', 'ফার্মেসি অনুমোদন হলে এখানে প্রেসক্রিপশনের ওষুধ দেওয়া, অনলাইন অর্ডার আর স্টক রাখা যাবে।')
      : tr('Once your lab is approved, take tests from prescriptions and give reports here.', 'প্যাথলজি অনুমোদন হলে এখানে প্রেসক্রিপশনের পরীক্ষা নেওয়া আর রিপোর্ট দেওয়া যাবে।')
    : `${biz.name} · ${statusText(biz.status)}`;
  return (
    <div className="rxp-gate">
      <EmptyState icon={icon} title={title} text={text}>
        <Link className="btn tonal has-icon" to={to}>
          <Icon name={icon} />
          {kind === 'pharmacy' ? tr('My pharmacy', 'আমার ফার্মেসি') : tr('My lab', 'আমার প্যাথলজি')}
        </Link>
      </EmptyState>
    </div>
  );
}

/** সার্ভারের কাজের পর ক্যাশ নতুন করে আনা (কাজের নাম ধরে) */
export function useInvalidate() {
  const qc = useQueryClient();
  return (...actions: string[]) => qc.invalidateQueries({ predicate: (q) => actions.includes(String(q.queryKey[0])) });
}

/** প্রেসক্রিপশনের কোড লেখা/পেস্ট: Enter-এ খোলে; লিংক পেস্ট করলেও কোডটা বের করে নেয় */
export function CodeBox({
  label, placeholder, busy, onOpen, autoFocus, inputRef, error,
}: { label: string; placeholder?: string; busy?: boolean; onOpen: (code: string) => void; autoFocus?: boolean; inputRef?: RefObject<HTMLInputElement>; error?: string | null }) {
  const [code, setCode] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const own = useRef<HTMLInputElement>(null);
  const ref = inputRef ?? own;
  function submit(raw = code) {
    const c = cleanCode(raw);
    if (!validCode(c)) {
      setErr(tr('Check the code on the prescription', 'প্রেসক্রিপশনের কোডটা দেখে লিখুন'));
      ref.current?.focus();
      return;
    }
    setErr(null);
    onOpen(c);
  }
  return (
    <form
      className="col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <label className="t-label-l" htmlFor="rxp-code">{label}</label>
      <div className="rxp-code-row">
        <input
          id="rxp-code"
          ref={ref}
          className={cx('input rxp-code-input', (err || error) && 'bad')}
          value={code}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          inputMode="text"
          maxLength={80}
          placeholder={placeholder ?? tr('Code under the QR', 'QR-এর নিচের কোড')}
          onChange={(e) => {
            setErr(null);
            setCode(cleanCode(e.target.value));
          }}
          onPaste={(e) => {
            const t = e.clipboardData.getData('text');
            const c = cleanCode(t);
            if (validCode(c)) {
              e.preventDefault();
              setCode(c);
              submit(c);
            }
          }}
        />
        <Button type="submit" busy={busy} icon="search">{tr('Open', 'খুলুন')}</Button>
      </div>
      <ErrorText>{err ?? error}</ErrorText>
    </form>
  );
}

/** ছোট শিরোনামসহ কার্ড */
export function Panel({ title, icon, count, actions, children, className }: { title: ReactNode; icon?: string; count?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx('card rxp-panel', className)}>
      <div className="rxp-panel-head">
        {icon ? <span className="rxp-panel-ic"><Icon name={icon} /></span> : null}
        <h2 className="t-title-m grow">{title}</h2>
        {count != null ? <span className="rxp-count num">{count}</span> : null}
        {actions}
      </div>
      {children}
    </section>
  );
}

/** Enter চাপলে পরের ঘরে (একই data-rxp-nav দলের) — শেষ ঘরে onLast */
export function focusNext(el: HTMLElement, group: string, onLast?: () => void) {
  const all = Array.from(document.querySelectorAll<HTMLElement>(`[data-rxp-nav="${group}"]:not([disabled])`));
  const i = all.indexOf(el);
  const next = i >= 0 ? all[i + 1] : undefined;
  if (next) {
    next.focus();
    if (next instanceof HTMLInputElement) next.select();
  } else onLast?.();
}

export function SmallButtonLink({ to, icon, children }: { to: string; icon?: string; children: ReactNode }) {
  return (
    <Link to={to} className="btn text small has-icon">
      {icon ? <Icon name={icon} /> : null}
      {children}
    </Link>
  );
}
