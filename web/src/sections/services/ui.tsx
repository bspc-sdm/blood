// সেবা অংশের নিজের উপাদান: কার্ড (অ্যাপের DirectoryCard), বিস্তারিত (DetailSheet), রেটিং, রিপোর্ট, ৯৯৯ লাইন, ফিল্টার শিট
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Avatar, Button, Dialog, ErrorText, IconButton, IconLine, PhoneLine, PillTabs, Tag, TextArea, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { DetailPane } from '@/shell/templates';
import { useTwoPane } from '@/shell/chrome';
import { kindLabel, recordCall, reportKind, type Entry, type Kind } from './data';

// ---------- উপরের ট্যাব ----------
export function ServiceTabs({ kind, sub }: { kind: Kind; sub?: 'list' | 'medicine' }) {
  const nav = useNavigate();
  return (
    <div className="services-tabs">
      <PillTabs
        label={tr('Services', 'সেবা')}
        value={kind}
        onChange={(k) => nav(`/services/${k}`)}
        tabs={[
          { key: 'ambulance', label: tr('Ambulance', 'অ্যাম্বুলেন্স'), icon: 'ambulance' },
          { key: 'pharmacy', label: tr('Pharmacy', 'ফার্মেসি'), icon: 'medication' },
          { key: 'bloodbank', label: tr('Blood bank', 'ব্লাড ব্যাংক'), icon: 'bloodtype' },
          { key: 'lab', label: tr('Lab', 'প্যাথলজি'), icon: 'local_hospital' },
        ]}
      />
      {sub ? (
        <PillTabs
          secondary
          label={tr('Pharmacy', 'ফার্মেসি')}
          value={sub}
          onChange={(k) => nav(k === 'medicine' ? '/services/pharmacy/medicine' : '/services/pharmacy')}
          tabs={[
            { key: 'list', label: tr('Pharmacies', 'ফার্মেসি'), icon: 'storefront' },
            { key: 'medicine', label: tr('Find a medicine', 'ওষুধ খুঁজুন'), icon: 'search' },
          ]}
        />
      ) : null}
    </div>
  );
}

// ---------- ৯৯৯ (অ্যাপের EmergencyCallCard: ছোট এক লাইন, ✕ চাপলে আর আসে না) ----------
const DISMISS_999 = 'lf.dismissed.999_ambulance';
export function Emergency999() {
  const [hidden, setHidden] = useState(() => store.get(DISMISS_999) === '1');
  if (hidden) return null;
  return (
    <div className="services-999">
      <span className="t-body-s muted grow">{tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা')}</span>
      <a className="act" href="tel:999">
        <Icon name="call" />
        <span>{num('999')}</span>
      </a>
      <IconButton
        small
        icon="close"
        label={tr('Hide', 'সরান')}
        onClick={() => {
          store.set(DISMISS_999, '1');
          setHidden(true);
        }}
      />
    </div>
  );
}

// ---------- ⋮ মেনু (রিপোর্ট) ----------
function MoreMenu({ onReport }: { onReport: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const f = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const k = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', f);
    document.addEventListener('keydown', k);
    return () => {
      document.removeEventListener('mousedown', f);
      document.removeEventListener('keydown', k);
    };
  }, [open]);
  return (
    <div ref={ref} className="services-more" onClick={(e) => e.stopPropagation()}>
      <IconButton small icon="more_vert" label={tr('More', 'আরও')} aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen(!open)} />
      {open ? (
        <div className="services-menu" role="menu">
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onReport();
            }}
          >
            <Icon name="flag" />
            {tr('Report', 'রিপোর্ট')}
          </button>
        </div>
      ) : null}
    </div>
  );
}

// ---------- কার্ড ----------
export function ServiceCard({
  e, selected, onOpen, onRate, onReport,
}: { e: Entry; selected: boolean; onOpen: () => void; onRate?: () => void; onReport?: () => void }) {
  const { guest } = useSession();
  const details = e.details.filter((d) => d[1]);
  return (
    <article
      className={cx('card click services-card', selected && 'sel')}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onOpen}
      onKeyDown={(ev) => {
        if ((ev.key === 'Enter' || ev.key === ' ') && ev.target === ev.currentTarget) {
          ev.preventDefault();
          onOpen();
        }
      }}
    >
      <div className="row top gap-12">
        <Avatar icon={e.icon} tint={e.tint} />
        <div className="col gap-2 grow">
          <div className="row">
            <span className="t-title-m clamp2" style={{ minWidth: 0 }}>{e.title}</span>
            {e.status ? <Tag tone={e.status.good ? 'good' : undefined}>{e.status.text}</Tag> : null}
          </div>
          {e.subtitle ? <span className="t-body-m c-primary clamp2">{e.subtitle}</span> : null}
          {e.meta ? <span className="t-body-s muted">{e.meta}</span> : null}
        </div>
        {onReport && !guest ? <MoreMenu onReport={onReport} /> : null}
      </div>
      {details.length || e.note ? (
        <div className="col gap-6" style={{ marginTop: 10 }}>
          {details.map(([ic, text]) => (
            <IconLine key={ic + text} icon={ic}>{text}</IconLine>
          ))}
          {e.note ? <p className="t-body-s muted">{e.note}</p> : null}
        </div>
      ) : null}
      <div className="row" style={{ marginTop: 12 }}>
        <a
          className="btn square has-icon"
          href={`tel:${e.phone}`}
          onClick={(ev) => {
            ev.stopPropagation();
            recordCall(e.kind, e.id, guest);
          }}
        >
          <Icon name="call" />
          {tr('Call', 'ফোন করুন')}
        </a>
        <span className="services-card-phone t-body-m muted num">{num(e.phone)}</span>
        <span className="grow" />
        {onRate ? (
          <Button
            variant="text"
            onClick={(ev) => {
              ev.stopPropagation();
              onRate();
            }}
          >
            {tr('Rate', 'রেটিং দিন')}
          </Button>
        ) : null}
      </div>
    </article>
  );
}

// ---------- বিস্তারিত ----------
export function EntryDetail({ e, backTo, onRate, onReport }: { e: Entry; backTo: string; onRate?: () => void; onReport?: () => void }) {
  const nav = useNavigate();
  const two = useTwoPane();
  const { guest } = useSession();
  return (
    <DetailPane title={kindLabel(e.kind)} backTo={backTo} hideTitleWide>
      <div className="services-head">
        <Avatar icon={e.icon} tint={e.tint} size={72} />
        <div className="col gap-4 grow">
          <h2 className="t-title-l">{e.title}</h2>
          {e.subtitle ? <span className="t-body-m c-primary">{e.subtitle}</span> : null}
          {e.meta ? <span className="t-body-m muted">{e.meta}</span> : null}
          {e.status ? <Tag tone={e.status.good ? 'good' : undefined}>{e.status.text}</Tag> : null}
        </div>
        {two ? <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={() => nav(backTo)} /> : null}
      </div>
      {e.sections.map((s) => {
        const lines = s.lines.filter((l) => l[1]);
        if (!lines.length && !s.link) return null;
        return (
          <section key={s.title} className="services-sec">
            <hr className="divider" />
            <h3 className="t-title-s">{s.title}</h3>
            {lines.map(([ic, text]) => (
              <div key={ic + text} className="services-line">
                <Icon name={ic} />
                <span>{text}</span>
              </div>
            ))}
            {s.link ? (
              <div>
                <Link className="btn outline square small has-icon" to={s.link.to}>
                  {s.link.icon ? <Icon name={s.link.icon} /> : null}
                  {s.link.label}
                </Link>
              </div>
            ) : null}
          </section>
        );
      })}
      <div className="services-foot">
        <PhoneLine big phone={e.phone} onCall={() => recordCall(e.kind, e.id, guest)} />
        {onRate || (e.reportable && onReport) ? (
          <div className="row services-acts">
            {onRate ? <Button variant="text" icon="star" onClick={onRate}>{tr('Rate', 'রেটিং দিন')}</Button> : null}
            <span className="grow" />
            {e.reportable && onReport ? (
              <Button variant="text" icon="flag" className="services-report" onClick={onReport}>{tr('Report', 'রিপোর্ট')}</Button>
            ) : null}
          </div>
        ) : null}
      </div>
    </DetailPane>
  );
}

// ---------- রেটিং (অ্যাপের StarRatingDialog) — অ্যাম্বুলেন্স আর ফার্মেসি ----------
const RATE: Partial<Record<Kind, [action: string, list: string]>> = { ambulance: ['ambulance_rate', 'ambulances'], pharmacy: ['pharmacy_rate', 'pharmacies'] };
export function RatingDialog({ kind = 'ambulance', id, name, onClose }: { kind?: Kind; id: string; name: string; onClose: () => void }) {
  const qc = useQueryClient();
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      const [action, list] = RATE[kind] ?? RATE.ambulance!;
      await call(action, { id, rating: stars, comment: comment.trim() });
      toast(tr('Thanks for the rating!', 'রেটিং দেওয়ার জন্য ধন্যবাদ!'));
      // তালিকা নতুন করে: কার্ডে নতুন গড় (★ ৪.৭ (৩২))
      qc.invalidateQueries({ queryKey: [list] });
      onClose();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('Rate ', 'রেটিং: ') + name}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Later', 'পরে')}</Button>
          <Button variant="text" busy={busy} disabled={stars < 1} onClick={submit}>{tr('Submit', 'জমা দিন')}</Button>
        </>
      }
    >
      <div className="services-stars" role="radiogroup" aria-label={tr('Rating', 'রেটিং')}>
        {[1, 2, 3, 4, 5].map((i) => (
          <button key={i} type="button" role="radio" aria-checked={stars === i} aria-label={num(i)} onClick={() => setStars(i)}>
            <Icon name={i <= stars ? 'star_fill' : 'star'} />
          </button>
        ))}
      </div>
      <TextArea label={tr('Comment (optional)', 'মন্তব্য (ঐচ্ছিক)')} rows={2} maxLength={300} value={comment} onChange={(e) => setComment(e.target.value)} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- রিপোর্ট (অ্যাপের ReasonReportDialog) ----------
export function ReportDialog({ kind, id, onClose }: { kind: Kind; id: string; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      await call('report', { kind: reportKind(kind), id, reason: reason.trim() });
      toast(tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।'));
      onClose();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('Report a problem', 'সমস্যা জানান')}
      actions={
        <>
          <Button variant="text" disabled={busy} onClick={onClose}>{tr('Cancel', 'বাতিল')}</Button>
          <Button variant="text" busy={busy} disabled={!reason.trim()} onClick={submit}>{tr('Report', 'রিপোর্ট')}</Button>
        </>
      }
    >
      <p className="t-body-m">{tr("Wrong number, overcharging, didn't come…", 'ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…')}</p>
      <TextArea label={tr('What happened', 'কী হয়েছে')} rows={3} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- ফিল্টার শিট (অ্যাপের FilterSheet) ----------
export function FilterSheet({ onClose, onClear, children }: { onClose: () => void; onClear: () => void; children: ReactNode }) {
  return (
    <Dialog
      onClose={onClose}
      title={tr('Filter', 'ফিল্টার')}
      actions={
        <>
          <Button variant="outline" onClick={onClear}>{tr('Clear all', 'সব মুছুন')}</Button>
          <Button onClick={onClose}>{tr('Show', 'দেখান')}</Button>
        </>
      }
    >
      {children}
    </Dialog>
  );
}
