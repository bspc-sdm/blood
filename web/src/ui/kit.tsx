// ছোট উপাদানগুলো — অ্যাপের Common.kt / Cards.kt / PillTabs.kt / FilterBar.kt-এর ওয়েব রূপ।
// সব অংশ (হোম, সেবা, অ্যাপয়েন্টমেন্ট…) এগুলোই ব্যবহার করে, যাতে দেখতে এক রকম থাকে।
import { useEffect, useId, useRef, useState, type ReactNode, type ButtonHTMLAttributes, type InputHTMLAttributes, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './Icon';
import { num, tr } from '@/lib/i18n';

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');
export { cx };

// ---------- বাটন ----------
type BtnVariant = 'filled' | 'tonal' | 'outline' | 'text' | 'danger' | 'danger-text';
export function Button({
  variant = 'filled', icon, busy, block, small, square, children, className, ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: BtnVariant; icon?: string; busy?: boolean; block?: boolean; small?: boolean; square?: boolean }) {
  return (
    <button
      type="button"
      {...rest}
      disabled={rest.disabled || busy}
      className={cx('btn', variant !== 'filled' && variant, icon && 'has-icon', block && 'block', small && 'small', square && 'square', className)}
    >
      {busy ? <span className="spinner small" /> : icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}

export function IconButton({ icon, label, small, badge, className, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { icon: string; label: string; small?: boolean; badge?: number | string | null }) {
  return (
    <button type="button" aria-label={label} title={label} {...rest} className={cx('icon-btn', small && 'small', className)}>
      <Icon name={icon} />
      {badge ? <span className="badge-dot">{typeof badge === 'number' ? num(badge > 99 ? '99+' : badge) : badge}</span> : null}
    </button>
  );
}

/** অ্যাপের SmallAction: ছোট গোল কাজের বাটন */
export function Action({ icon, primary, busy, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { icon?: string; primary?: boolean; busy?: boolean }) {
  return (
    <button type="button" {...rest} disabled={rest.disabled || busy} className={cx('act', primary && 'primary', rest.className)}>
      {busy ? <span className="spinner small" /> : icon ? <Icon name={icon} /> : null}
      <span className="ellipsis">{children}</span>
    </button>
  );
}

/** নিচের কোণে ভাসমান ছোট বাটন (অ্যাপের SmallFab) */
export function Fab({ icon, children, onClick }: { icon: string; children: ReactNode; onClick: () => void }) {
  return (
    <button type="button" className="fab" onClick={onClick}>
      <Icon name={icon} />
      {children}
    </button>
  );
}

// ---------- লেবেল, লাইন ----------
export function Tag({ children, tone }: { children: ReactNode; tone?: 'error' | 'good' | 'info' | 'primary' }) {
  return <span className={cx('tag', tone)}>{children}</span>;
}
export const UrgentTag = () => <Tag tone="error">{tr('Urgent', 'জরুরি')}</Tag>;

export function IconLine({ icon, children, color }: { icon: string; children: ReactNode; color?: string }) {
  return (
    <div className="icon-line" style={color ? { color } : undefined}>
      <Icon name={icon} />
      <span>{children}</span>
    </div>
  );
}

export function BloodGroupBadge({ group, size = 52 }: { group: string; size?: number }) {
  return (
    <span className="badge-group" style={{ width: size, height: size, fontSize: size * 0.34 }}>
      {group === 'unknown' ? '?' : group}
    </span>
  );
}

/** রক্তের ব্যাগের ছবিতে সংখ্যা (অ্যাপের BloodBag) */
export function BloodBag({ n, filled = true, label }: { n: number; filled?: boolean; label?: string }) {
  return (
    <svg width="30" height="40" viewBox="0 0 30 40" role="img" aria-label={label ?? tr(`${n} bag(s) needed`, `${num(n)} ব্যাগ দরকার`)} style={{ flex: 'none' }}>
      <line x1="15" y1="0" x2="15" y2="6.4" stroke="var(--error)" strokeWidth="1.6" />
      <rect x="10.8" y="4.8" width="8.4" height="4" rx="2" fill="var(--error)" />
      <rect x=".8" y="8" width="28.4" height="31.2" rx="7" fill={filled ? 'var(--error)' : 'var(--error-a12)'} stroke="var(--error)" strokeWidth="1.6" />
      <text x="15" y="29" textAnchor="middle" fill={filled ? 'var(--on-error)' : 'var(--error)'} fontSize="14" fontWeight="700" fontFamily="Hind Siliguri, Roboto, sans-serif">
        {num(n)}
      </text>
    </svg>
  );
}

/** গোল ছবি বা আইকন (অ্যাপের CardAvatar) */
export function Avatar({ photo, icon = 'person_filled', size = 52, tint }: { photo?: string | null; icon?: string; size?: number; tint?: 'primary' | 'error' }) {
  const src = photo ? (photo.startsWith('data:') ? photo : `data:image/jpeg;base64,${photo}`) : null;
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, ...(tint === 'error' ? { background: 'var(--error-a12)', color: 'var(--error)' } : {}) }}
    >
      {src ? <img src={src} alt="" /> : <Icon name={icon} size={size / 2} />}
    </span>
  );
}

// ---------- অবস্থা ----------
export function Loading({ inline }: { inline?: boolean }) {
  return inline ? <span className="spinner small" role="status" aria-label={tr('Loading', 'লোড হচ্ছে')} /> : (
    <div className="loading" role="status" aria-label={tr('Loading', 'লোড হচ্ছে')}>
      <span className="spinner" />
    </div>
  );
}
export function SkeletonCards({ n = 3, h = 112 }: { n?: number; h?: number }) {
  return (
    <>
      {Array.from({ length: n }, (_, i) => (
        <div key={i} className="skeleton" style={{ height: h }} />
      ))}
    </>
  );
}
export function EmptyState({ icon, title, text, children }: { icon: string; title: string; text?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <Icon name={icon} />
      <div className="t">{title}</div>
      {text ? <div className="s">{text}</div> : null}
      {children}
    </div>
  );
}
export function ErrorText({ children }: { children?: ReactNode }) {
  if (!children) return null;
  return <p className="err-text" role="alert">{children}</p>;
}
export function InfoText({ children }: { children?: ReactNode }) {
  if (!children) return null;
  return <p className="info-text" role="status">{children}</p>;
}
export function ErrorBox({ error, onRetry }: { error: string; onRetry?: () => void }) {
  return (
    <div className="col" style={{ alignItems: 'center', padding: 24 }}>
      <ErrorText>{error}</ErrorText>
      {onRetry ? <Button variant="text" onClick={onRetry}>{tr('Try again', 'আবার চেষ্টা করুন')}</Button> : null}
    </div>
  );
}

// ---------- ট্যাব ----------
export interface PillTab { key: string; label: string; icon?: string; selectedIcon?: string; count?: number }
export function PillTabs({ tabs, value, onChange, secondary, label }: { tabs: PillTab[]; value: string; onChange: (k: string) => void; secondary?: boolean; label?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  // বাছা ট্যাব সবসময় পুরোটা দেখা যায় (অ্যাপের মতো মাঝখানে আসে)
  useEffect(() => {
    const el = ref.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    const box = ref.current;
    if (el && box && box.scrollWidth > box.clientWidth) {
      box.scrollTo({ left: el.offsetLeft - box.clientWidth / 2 + el.offsetWidth / 2, behavior: 'smooth' });
    }
  }, [value]);
  return (
    <div ref={ref} className={cx('pills', secondary && 'secondary')} role="tablist" aria-label={label}>
      {tabs.map((t) => {
        const sel = t.key === value;
        const ic = sel ? t.selectedIcon ?? t.icon : t.icon;
        return (
          <button key={t.key} type="button" role="tab" aria-selected={sel} className="pill" onClick={() => onChange(t.key)}>
            {ic ? <Icon name={ic} /> : null}
            {t.label}
            {t.count ? <span className="n">{num(t.count)}</span> : null}
          </button>
        );
      })}
    </div>
  );
}

// ---------- এলাকা বার (অ্যাপের FilterBar) ----------
export function FilterBar({
  areaLabel, onArea, filterCount = 0, onFilter, search, onSearch, searchHint, children,
}: {
  areaLabel: string; onArea: () => void; filterCount?: number; onFilter?: () => void;
  search?: string; onSearch?: (s: string) => void; searchHint?: string; children?: ReactNode;
}) {
  const [open, setOpen] = useState(!!search);
  return (
    <div className="col gap-6">
      <div className="fbar">
        <button type="button" className="area-btn" onClick={onArea} aria-label={tr('Change area', 'এলাকা বদলান') + ': ' + areaLabel}>
          <Icon name="location_on" />
          <span>{areaLabel || tr('Choose area', 'এলাকা বেছে নিন')}</span>
          <Icon name="expand_more_round" />
        </button>
        {onFilter ? (
          <IconButton icon="tune" label={tr('Filter', 'ফিল্টার')} onClick={onFilter} badge={filterCount || null} style={filterCount ? { color: 'var(--primary)' } : undefined} />
        ) : null}
        {onSearch ? (
          <IconButton
            icon={open ? 'close' : 'search'}
            label={open ? tr('Close search', 'খোঁজা বন্ধ') : searchHint ?? tr('Search by name', 'নাম দিয়ে খুঁজুন')}
            onClick={() => {
              if (open) onSearch('');
              setOpen(!open);
            }}
          />
        ) : null}
      </div>
      {onSearch && open ? (
        <input className="input" autoFocus value={search ?? ''} onChange={(e) => onSearch(e.target.value)} placeholder={searchHint ?? tr('Search by name', 'নাম দিয়ে খুঁজুন')} />
      ) : null}
      {children}
    </div>
  );
}

/** চালু ফিল্টার (মুছে ফেলা যায়) — অ্যাপের InputChip */
export function ActiveChips({ items }: { items: { label: string; onRemove: () => void }[] }) {
  if (!items.length) return null;
  return (
    <div className="chips scroll">
      {items.map((f) => (
        <button key={f.label} type="button" className="chip on" onClick={f.onRemove} aria-label={tr('Remove', 'সরান') + ': ' + f.label}>
          {f.label}
          <Icon name="close" />
        </button>
      ))}
    </div>
  );
}

// ---------- ডিরেক্টরি কার্ড (অ্যাপের DirectoryCard) ----------
export interface CardActionDef { label: string; icon?: string; onClick: () => void; busy?: boolean }
export function DirectoryCard({
  title, icon, photo, tint, subtitle, status, meta, details = [], tags = [], note, primary, secondary, onClick, selected, menu,
}: {
  title: string; icon: string; photo?: string | null; tint?: 'primary' | 'error'; subtitle?: string | null;
  status?: { text: string; good: boolean } | null; meta?: string | null; details?: [string, string | null | undefined][];
  tags?: string[]; note?: string | null; primary?: CardActionDef | null; secondary?: CardActionDef | null;
  onClick?: () => void; selected?: boolean; menu?: ReactNode;
}) {
  const body = (
    <>
      <div className="row top gap-12">
        <Avatar photo={photo} icon={icon} tint={tint} />
        <div className="col gap-2 grow">
          <div className="row" style={{ alignItems: 'center' }}>
            <span className="t-title-m clamp2" style={{ minWidth: 0 }}>{title}</span>
            {status ? <Tag tone={status.good ? 'good' : undefined}>{status.text}</Tag> : null}
          </div>
          {subtitle ? <span className="t-body-m c-primary clamp2">{subtitle}</span> : null}
          {meta ? <span className="t-body-s muted">{meta}</span> : null}
        </div>
        {menu}
      </div>
      {details.some((d) => d[1]) || tags.length || note ? (
        <div className="col gap-6" style={{ marginTop: 10 }}>
          {details.filter((d) => d[1]).map(([ic, text]) => (
            <IconLine key={ic + text} icon={ic}>{text}</IconLine>
          ))}
          {tags.length ? <div className="row wrap gap-6">{tags.map((t) => <Tag key={t} tone="primary">{t}</Tag>)}</div> : null}
          {note ? <p className="t-body-s muted">{note}</p> : null}
        </div>
      ) : null}
      {primary || secondary ? (
        <div className="row" style={{ marginTop: 12 }}>
          {primary ? (
            <Button square icon={primary.icon} busy={primary.busy} onClick={(e) => { e.stopPropagation(); primary.onClick(); }}>
              {primary.label}
            </Button>
          ) : null}
          <span className="grow" />
          {secondary ? (
            <Button variant="text" icon={secondary.icon} onClick={(e) => { e.stopPropagation(); secondary.onClick(); }}>
              {secondary.label}
            </Button>
          ) : null}
        </div>
      ) : null}
    </>
  );
  if (!onClick) return <article className={cx('card', selected && 'sel')}>{body}</article>;
  return (
    <article
      className={cx('card click', selected && 'sel')}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onClick}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
          e.preventDefault();
          onClick();
        }
      }}
    >
      {body}
    </article>
  );
}

// ---------- ফোন নম্বর ----------
/** ফোন নম্বর: লেখা হিসেবে দেখা যায় (কপি করা যায়), পাশে "ফোন করুন" (মোবাইলে tel: খোলে) */
export function PhoneLine({ phone, onCall, big }: { phone: string; onCall?: () => void; big?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="row wrap gap-8">
      <a className={cx('btn square has-icon', !big && 'small')} href={`tel:${phone}`} onClick={() => onCall?.()}>
        <Icon name="call" />
        {tr('Call', 'ফোন করুন')}
      </a>
      <span className="t-title-m num" style={{ userSelect: 'all', fontWeight: 600 }}>{num(phone)}</span>
      <button
        type="button"
        className="btn text small"
        onClick={() => {
          navigator.clipboard?.writeText(phone).then(() => setCopied(true), () => setCopied(false));
          setTimeout(() => setCopied(false), 2000);
        }}
      >
        {copied ? tr('Copied', 'কপি হয়েছে') : tr('Copy', 'কপি')}
      </button>
    </div>
  );
}

// ---------- ফর্ম ----------
export function Field({ label, hint, error, children, htmlFor }: { label?: ReactNode; hint?: ReactNode; error?: ReactNode; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="field">
      {label ? <label htmlFor={htmlFor}>{label}</label> : null}
      {children}
      {error ? <span className="err">{error}</span> : hint ? <span className="hint">{hint}</span> : null}
    </div>
  );
}
export function TextInput({ label, hint, error, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label?: ReactNode; hint?: ReactNode; error?: ReactNode }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} htmlFor={rest.id ?? id}>
      <input id={rest.id ?? id} className="input" {...rest} />
    </Field>
  );
}
export function TextArea({ label, hint, error, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: ReactNode; hint?: ReactNode; error?: ReactNode }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} htmlFor={rest.id ?? id}>
      <textarea id={rest.id ?? id} className="textarea" {...rest} />
    </Field>
  );
}
export function Select({ label, hint, error, options, placeholder, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & { label?: ReactNode; hint?: ReactNode; error?: ReactNode; options: { value: string; label: string }[]; placeholder?: string }) {
  const id = useId();
  return (
    <Field label={label} hint={hint} error={error} htmlFor={rest.id ?? id}>
      <select id={rest.id ?? id} className="select" {...rest}>
        {placeholder != null ? <option value="">{placeholder}</option> : null}
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    </Field>
  );
}
export function Check({ checked, onChange, children, disabled }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; disabled?: boolean }) {
  return (
    <label className="check">
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span>{children}</span>
    </label>
  );
}
export function Switch({ checked, onChange, label, sub, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; sub?: ReactNode; disabled?: boolean }) {
  const id = useId();
  return (
    <div className="row gap-16" style={{ minHeight: 56 }}>
      <label htmlFor={id} className="col gap-2 grow" style={{ cursor: 'pointer' }}>
        <span className="t-body-l">{label}</span>
        {sub ? <span className="t-body-s muted">{sub}</span> : null}
      </label>
      <input id={id} type="checkbox" role="switch" className="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
    </div>
  );
}
export function Segmented<T extends string | number>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label?: string }) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={String(o.value)} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
/** চিপ দিয়ে এক বা একাধিক বাছাই (অ্যাপের FilterChip) */
export function ChoiceChips<T extends string | number>({ options, value, onChange, multi }: { options: { value: T; label: string }[]; value: T | T[] | null; onChange: (v: any) => void; multi?: boolean }) {
  const isOn = (v: T) => (multi ? (value as T[] | null)?.includes(v) ?? false : value === v);
  return (
    <div className="chips">
      {options.map((o) => (
        <button
          key={String(o.value)}
          type="button"
          className="chip"
          aria-pressed={isOn(o.value)}
          onClick={() => {
            if (!multi) return onChange(o.value);
            const cur = (value as T[] | null) ?? [];
            onChange(cur.includes(o.value) ? cur.filter((x) => x !== o.value) : [...cur, o.value]);
          }}
        >
          {isOn(o.value) ? <Icon name="check_circle" /> : null}
          {o.label}
        </button>
      ))}
    </div>
  );
}

// ---------- ডায়ালগ ----------
let openDialogs = 0;
export function Dialog({
  open = true, onClose, title, children, actions, size, sheet = true, labelledBy,
}: { open?: boolean; onClose: () => void; title?: ReactNode; children?: ReactNode; actions?: ReactNode; size?: 'small' | 'wide'; sheet?: boolean; labelledBy?: string }) {
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  // onClose প্রতিবার নতুন হতে পারে — তাই ref-এ; নইলে প্রতিটা অক্ষর লেখার পর ফোকাস অন্য ঘরে চলে যেত
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    openDialogs++;
    document.body.style.overflow = 'hidden';
    const prev = document.activeElement as HTMLElement | null;
    // খোলার সময় একবারই: আগে লেখার ঘর, না থাকলে প্রথম বাটন
    const t = setTimeout(() => {
      const el = box.current;
      if (!el || el.contains(document.activeElement)) return;
      const f = el.querySelector<HTMLElement>('input:not([type=hidden]):not([disabled]), select, textarea') ?? el.querySelector<HTMLElement>('button:not([data-close])');
      (f ?? el).focus();
    }, 30);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeRef.current();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      clearTimeout(t);
      window.removeEventListener('keydown', onKey);
      openDialogs--;
      if (!openDialogs) document.body.style.overflow = '';
      prev?.focus?.();
    };
  }, [open]);
  if (!open) return null;
  return createPortal(
    <div className={cx('overlay', sheet && 'sheet')} onMouseDown={(e) => e.target === e.currentTarget && closeRef.current()}>
      <div ref={box} className={cx('dialog', size)} role="dialog" aria-modal="true" aria-labelledby={title ? id : labelledBy} tabIndex={-1}>
        {title ? (
          <div className="row">
            <h2 id={id} className="t-title-l grow">{title}</h2>
            <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={onClose} data-close="" />
          </div>
        ) : null}
        {children}
        {actions ? <div className="dialog-actions">{actions}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

/** "নিশ্চিত?" — ব্রাউজারের confirm() এর বদলে */
export function ConfirmDialog({
  title, text, confirm, danger, onConfirm, onClose, busy, error,
}: { title: string; text?: ReactNode; confirm: string; danger?: boolean; onConfirm: () => void; onClose: () => void; busy?: boolean; error?: string | null }) {
  return (
    <Dialog
      onClose={onClose}
      size="small"
      title={title}
      actions={
        <>
          <Button variant="text" onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button variant={danger ? 'danger' : 'filled'} busy={busy} onClick={onConfirm}>{confirm}</Button>
        </>
      }
    >
      {text ? <div className="t-body-m muted">{text}</div> : null}
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}

// ---------- টোস্ট ----------
let toastSetter: ((m: string | null) => void) | null = null;
let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(message: string) {
  if (!toastSetter) return;
  toastSetter(message);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastSetter?.(null), 3500);
}
export function ToastHost() {
  const [m, setM] = useState<string | null>(null);
  useEffect(() => {
    toastSetter = setM;
    return () => {
      toastSetter = null;
    };
  }, []);
  return m ? <div className="toast" role="status" aria-live="polite">{m}</div> : null;
}

// ---------- তালিকার সারি ----------
export function ListRow({ icon, title, sub, onClick, href, trailing, danger }: { icon?: string; title: ReactNode; sub?: ReactNode; onClick?: () => void; href?: string; trailing?: ReactNode; danger?: boolean }) {
  const inner = (
    <>
      {icon ? <Icon name={icon} style={danger ? { color: 'var(--error)' } : undefined} /> : null}
      <span className="col gap-2 grow">
        <span className="t-body-l" style={danger ? { color: 'var(--error)' } : undefined}>{title}</span>
        {sub ? <span className="sub">{sub}</span> : null}
      </span>
      {trailing ?? (onClick || href ? <Icon name="chevron_right" className="chev" /> : null)}
    </>
  );
  if (href) return <a className="list-row" href={href}>{inner}</a>;
  return (
    <button type="button" className="list-row" onClick={onClick} disabled={!onClick}>
      {inner}
    </button>
  );
}

/** সময় গুনতে: প্রতি সেকেন্ডে এখনকার সময় */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}
