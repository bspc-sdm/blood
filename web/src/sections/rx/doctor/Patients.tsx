// রোগী: আজ-কালকের অ্যাপয়েন্টমেন্ট, এন্ট্রি করা রোগী, অ্যাপে আগে আসা রোগী — খোঁজা, নতুন যোগ, লেখা, আগের প্রেসক্রিপশন।
// অ্যাপের RxPatientsPane / PatientRow / RxPatientAddDialog / PatientHistoryDialog; প্যানেলের patientsView/historyView।
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Action, Avatar, Button, ChoiceChips, Dialog, DirectoryCard, EmptyState, ErrorBox, ErrorText, IconButton, SkeletonCards, Tag, TextInput, cx, toast,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useMedia } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import {
  ageText, dayText, deletedName, findTarget, forDoctor, genderLabel, targetBody, targetKey, targetSearch,
  type Actor, type DoctorRx, type RxPatient, type RxPatientsData, type RxTarget,
} from './model';

type Link = (path: string, extra?: Record<string, string>) => string;

export function usePatients(actor: Actor) {
  return useCall<RxPatientsData>('rx_patients', forDoctor(actor));
}

// ---------------- পাতা ----------------
export function PatientsPage({ actor, link }: { actor: Actor; link: Link }) {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const wide = useMedia('(min-width: 1100px)');
  const q = usePatients(actor);
  const doctor = actor.role === 'doctor';
  const target = doctor ? findTarget(sp, q.data) : null;
  const select = (t: RxTarget | null) => nav(link('', t ? targetSearch(t) : {}), { replace: !!t && !!target });
  const write = (t: RxTarget) => nav(link('/write', targetSearch(t)));

  const list = (
    <PatientsList
      actor={actor}
      query={q}
      selectedKey={target ? targetKey(target) : null}
      onOpen={doctor ? (t) => select(t) : undefined}
      onWrite={doctor ? write : undefined}
      onAdded={(p) => {
        if (doctor) write({ rxPatientId: p.id, name: p.name, age: p.age, gender: p.gender });
      }}
    />
  );

  if (!target) return list;
  const history = <HistoryPane key={targetKey(target)} target={target} link={link} wide={wide} onClose={() => select(null)} onWrite={() => write(target)} />;
  if (!wide) return history;
  return (
    <div className="rxd-split">
      <div className="rxd-split-main">{list}</div>
      <aside className="rxd-split-side" aria-label={tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন')}>{history}</aside>
    </div>
  );
}

// ---------------- তালিকা ----------------
interface Row { key: string; t: RxTarget; icon: string; title: string; sub: string; tag: string | null }
interface Group { key: string; title: string; rows: Row[]; empty?: string }

/** রোগীর তালিকা: onOpen = নাম চাপলে (আগের প্রেসক্রিপশন), onWrite = "লিখুন" (শুধু ডাক্তার) */
export function PatientsList({
  actor, query, selectedKey, onOpen, onWrite, onAdded, pick,
}: {
  actor: Actor;
  query: ReturnType<typeof usePatients>;
  selectedKey?: string | null;
  onOpen?: (t: RxTarget) => void;
  onWrite?: (t: RxTarget) => void;
  onAdded?: (p: RxPatient) => void;
  /** লেখার পাতায় রোগী বাছাই: নাম চাপলেই লেখা */
  pick?: boolean;
}) {
  const wide = useMedia('(min-width: 1100px)');
  const [q, setQ] = useState('');
  const [adding, setAdding] = useState(false);
  const search = useRef<HTMLInputElement>(null);
  const d = query.data;
  const doctor = actor.role === 'doctor';

  // "/" চাপলে খোঁজার ঘরে
  useEffect(() => {
    const f = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
      if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))) return;
      if (document.querySelector('.overlay')) return;
      e.preventDefault();
      search.current?.focus();
    };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, []);

  const groups = useMemo<Group[]>(() => {
    if (!d) return [];
    const t = toAsciiDigits(q.trim().toLowerCase());
    const hit = (...xs: (string | null | undefined)[]) => !t || xs.some((x) => String(x ?? '').toLowerCase().includes(t));
    const out: Group[] = [];
    const appts = d.appointments.filter((a) => hit(a.name));
    if (appts.length) {
      out.push({
        key: 'a',
        title: tr('Appointments (today & tomorrow)', 'অ্যাপের অ্যাপয়েন্টমেন্ট (আজ–কাল)'),
        rows: appts.map((a) => ({
          key: 'a' + a.id, t: { appointmentId: a.id, name: a.name }, icon: 'event_outline', title: a.name || '-',
          sub: [dayText(a.date), a.chamber].filter(Boolean).join(' · '), tag: a.serial ? tr(`Serial ${a.serial}`, `সিরিয়াল ${num(a.serial)}`) : null,
        })),
      });
    }
    out.push({
      key: 'p',
      title: tr('Entered patients', 'এন্ট্রি করা রোগী'),
      empty: t ? tr('No one found', 'কাউকে পাওয়া যায়নি') : tr('No patients entered yet', 'এখনো কোনো রোগী এন্ট্রি করা হয়নি'),
      rows: d.patients.filter((p) => hit(p.name, p.phone)).map((p) => ({
        key: 'p' + p.id, t: { rxPatientId: p.id, name: p.name, age: p.age, gender: p.gender }, icon: 'person_outline', title: p.name,
        sub: [ageText(p.age), genderLabel(p.gender), num(p.phone), p.lastRxAt ? tr('Last: ', 'শেষ: ') + formatDate(p.lastRxAt) : ''].filter(Boolean).join(' · '),
        tag: p.linked ? tr('In app', 'অ্যাপে আছেন') : null,
      })),
    });
    const past = d.appPatients.filter((a) => hit(a.name));
    if (past.length) {
      out.push({
        key: 'h',
        title: tr('Came through the app before', 'অ্যাপে আগে এসেছেন'),
        rows: past.map((a) => ({
          key: 'h' + a.appointmentId, t: { appointmentId: a.appointmentId, name: a.name }, icon: 'history', title: a.name || deletedName(),
          sub: tr(`${a.count} visit(s) · last `, `${num(a.count)} বার · শেষ `) + formatDate(a.last), tag: null,
        })),
      });
    }
    return out;
  }, [d, q]);

  const click = pick ? onWrite : onOpen;
  const total = d ? d.appointments.length + d.patients.length + d.appPatients.length : 0;

  return (
    <div className="col gap-12">
      <div className="row wrap rxd-toolbar">
        <label className="rxd-search">
          <Icon name="search" />
          <input
            ref={search}
            value={q}
            onChange={(e) => setQ(e.target.value.slice(0, 40))}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setQ('');
            }}
            placeholder={tr('Search name or phone', 'নাম বা ফোন দিয়ে খুঁজুন')}
            aria-label={tr('Search name or phone', 'নাম বা ফোন দিয়ে খুঁজুন')}
          />
          {wide ? <kbd className="rxd-kbd" aria-hidden="true">/</kbd> : null}
        </label>
        <span className="grow" />
        <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => query.refetch()} />
        <Button icon="add" onClick={() => setAdding(true)}>
          {doctor ? tr('Add patient', 'রোগী যোগ') : tr('New patient', 'নতুন রোগী')}
        </Button>
      </div>

      {query.isPending ? (
        <SkeletonCards n={4} h={64} />
      ) : query.isError ? (
        <ErrorBox error={errorText(query.error)} onRetry={() => query.refetch()} />
      ) : !total ? (
        <EmptyState
          icon="person_outline"
          title={tr('No patients yet', 'এখনো কোনো রোগী নেই')}
          text={tr('Appointments from the app and patients you enter appear here.', 'অ্যাপের অ্যাপয়েন্টমেন্ট আর এন্ট্রি করা রোগী এখানে আসবে।')}
        />
      ) : wide ? (
        <div className="table-wrap">
          <table className="table rxd-table">
            <thead>
              <tr>
                <th>{tr('Patient', 'রোগী')}</th>
                <th>{tr('Details', 'বিস্তারিত')}</th>
                <th aria-label={tr('Actions', 'কাজ')} />
              </tr>
            </thead>
            {groups.map((g) => (
              <tbody key={g.key}>
                <tr className="rxd-group">
                  <th colSpan={3} scope="colgroup">{g.title} <span className="rxd-count num">{num(g.rows.length)}</span></th>
                </tr>
                {!g.rows.length ? (
                  <tr><td colSpan={3} className="muted">{g.empty}</td></tr>
                ) : (
                  g.rows.map((r) => (
                    <tr
                      key={r.key}
                      className={cx(click && 'rxd-click', selectedKey === targetKey(r.t) && 'rxd-sel')}
                      tabIndex={click ? 0 : undefined}
                      aria-selected={click ? selectedKey === targetKey(r.t) : undefined}
                      onClick={click ? () => click(r.t) : undefined}
                      onKeyDown={click ? (e) => {
                        if (e.key === 'Enter' && e.target === e.currentTarget) click(r.t);
                      } : undefined}
                    >
                      <td className="rxd-td-name">
                        <span className="row gap-12">
                          <span className="rxd-row-ic"><Icon name={r.icon} /></span>
                          <span className="t-title-s">{r.title}</span>
                        </span>
                      </td>
                      <td>
                        <span className="row wrap gap-6">
                          <span className="t-body-m muted">{r.sub}</span>
                          {r.tag ? <Tag tone="info">{r.tag}</Tag> : null}
                        </span>
                      </td>
                      <td className="rxd-td-acts">
                        <RowActions r={r} onWrite={onWrite} onOpen={pick ? undefined : onOpen} />
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            ))}
          </table>
        </div>
      ) : (
        groups.map((g) => (
          <section key={g.key} className="col gap-6">
            <h2 className="rxd-group-title">{g.title}</h2>
            {!g.rows.length ? <p className="t-body-m muted rxd-pad">{g.empty}</p> : null}
            {g.rows.map((r) => (
              <PatientRow key={r.key} r={r} selected={selectedKey === targetKey(r.t)} onClick={click ? () => click(r.t) : undefined} onWrite={onWrite && !pick ? () => onWrite(r.t) : undefined} />
            ))}
          </section>
        ))
      )}

      {adding ? (
        <PatientAddDialog
          actor={actor}
          onClose={() => setAdding(false)}
          onAdded={(p, existed) => {
            setAdding(false);
            toast(existed ? tr('This patient was already entered', 'এই রোগী আগে থেকেই ছিলেন') : tr('Patient added', 'রোগী যোগ হয়েছে'));
            query.refetch();
            onAdded?.(p);
          }}
        />
      ) : null}
    </div>
  );
}

function RowActions({ r, onWrite, onOpen }: { r: Row; onWrite?: (t: RxTarget) => void; onOpen?: (t: RxTarget) => void }) {
  if (!onWrite && !onOpen) return null;
  return (
    <span className="row rxd-acts">
      {onWrite ? (
        <Action icon="edit_note" primary onClick={(e) => { e.stopPropagation(); onWrite(r.t); }}>{tr('Write', 'লিখুন')}</Action>
      ) : null}
      {onOpen ? (
        <IconButton small icon="history" label={tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন')} onClick={(e) => { e.stopPropagation(); onOpen(r.t); }} />
      ) : null}
    </span>
  );
}

/** ছোট পর্দায় এক সারি (অ্যাপের PatientRow) */
function PatientRow({ r, selected, onClick, onWrite }: { r: Row; selected: boolean; onClick?: () => void; onWrite?: () => void }) {
  const inner: ReactNode = (
    <span className="row gap-12">
      <Avatar icon={r.icon === 'person_outline' ? 'person_outline' : r.icon} size={40} />
      <span className="col gap-2 grow">
        <span className="t-title-m">{r.title}</span>
        {r.sub ? <span className="t-body-s muted">{r.sub}</span> : null}
        {r.tag ? <span><Tag tone="info">{r.tag}</Tag></span> : null}
      </span>
      {onWrite ? (
        <Action icon="edit_note" primary onClick={(e) => { e.stopPropagation(); onWrite(); }}>{tr('Write', 'লিখুন')}</Action>
      ) : onClick ? <Icon name="chevron_right" className="rxd-chev" /> : null}
    </span>
  );
  if (!onClick) return <div className="card rxd-prow">{inner}</div>;
  return (
    <div
      className={cx('card click rxd-prow', selected && 'sel')}
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
          e.preventDefault();
          onClick();
        }
      }}
    >
      {inner}
    </div>
  );
}

// ---------------- নতুন রোগী ----------------
export function PatientAddDialog({ actor, onClose, onAdded }: { actor: Actor; onClose: () => void; onAdded: (p: RxPatient, existed: boolean) => void }) {
  const [name, setName] = useState('');
  const [age, setAge] = useState('');
  const [gender, setGender] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const ok = !!name.trim() && !!phone.trim();
  async function save() {
    if (!ok || busy) return;
    setBusy(true);
    setErr(null);
    try {
      const a = toAsciiDigits(age.trim());
      const r = await call<{ patient: RxPatient; existed: boolean }>('rx_patient_add', {
        ...forDoctor(actor), name: name.trim(), age: a ? Number(a) : null, gender, phone: toAsciiDigits(phone.trim()),
      });
      onAdded(r.patient, r.existed);
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('New patient', 'নতুন রোগী')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} disabled={!ok} onClick={save}>{tr('Save', 'সেভ')}</Button>
        </>
      }
    >
      <form className="col gap-12" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <TextInput label={tr('Name', 'নাম')} value={name} onChange={(e) => setName(e.target.value.slice(0, 80))} autoComplete="off" />
        <div className="row top gap-12 rxd-age-row">
          <TextInput label={tr('Age (years)', 'বয়স (বছর)')} inputMode="numeric" value={age} onChange={(e) => setAge(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3))} />
          <div className="field">
            <span className="label">{tr('Gender', 'লিঙ্গ')}</span>
            <ChoiceChips options={[{ value: 'male', label: genderLabel('male') }, { value: 'female', label: genderLabel('female') }]} value={gender} onChange={(v: string) => setGender(gender === v ? '' : v)} />
          </div>
        </div>
        <TextInput label={tr('Mobile number', 'মোবাইল নম্বর')} type="tel" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value.slice(0, 20))} autoComplete="off" />
        <p className="t-body-s muted">{tr('The patient adds the prescription to their own app with this number and the code.', 'রোগী এই নম্বর আর কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।')}</p>
        <ErrorText>{err}</ErrorText>
        <button type="submit" hidden />
      </form>
    </Dialog>
  );
}

// ---------------- একজন রোগীর আগের প্রেসক্রিপশন ----------------
function HistoryPane({ target, link, wide, onClose, onWrite }: { target: RxTarget; link: Link; wide: boolean; onClose: () => void; onWrite: () => void }) {
  const nav = useNavigate();
  const q = useCall<{ items: DoctorRx[] }>('rx_patient_history', targetBody(target));
  const name = target.name || tr('Patient', 'রোগী');
  return (
    <div className="col gap-12">
      <div className="row">
        {!wide ? <IconButton icon="arrow_back" label={tr('Back', 'পেছনে')} onClick={onClose} /> : null}
        <div className="col gap-2 grow">
          <h2 className="t-title-l">{name}</h2>
          {target.age != null || target.gender ? <span className="t-body-s muted">{[ageText(target.age), genderLabel(target.gender)].filter(Boolean).join(' · ')}</span> : null}
        </div>
        {wide ? <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={onClose} /> : null}
      </div>
      <Button square icon="edit_note" onClick={onWrite}>{tr('Write a prescription', 'প্রেসক্রিপশন লিখুন')}</Button>
      {q.isPending ? (
        <SkeletonCards n={2} h={110} />
      ) : q.isError ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !q.data.items.length ? (
        <EmptyState icon="medication" title={tr('No prescription for this patient yet', 'এই রোগীকে এখনো প্রেসক্রিপশন দেননি')} />
      ) : (
        <>
          <h3 className="rxd-group-title">{tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন')}</h3>
          {q.data.items.map((rx) => <RxCard key={rx.id} rx={rx} onClick={() => nav(link('/view/' + rx.id))} title={formatDate(rx.createdAt)} />)}
        </>
      )}
    </div>
  );
}

/** অ্যাপের RxListCard (ডাক্তারের দিক) */
export function RxCard({ rx, onClick, title }: { rx: DoctorRx; onClick: () => void; title?: string }) {
  const meds = rx.medicines ?? [];
  return (
    <DirectoryCard
      title={title ?? (rx.patient?.name || '-')}
      icon="medication"
      subtitle={title ? rx.diagnosis || null : [ageText(rx.patient?.age), genderLabel(rx.patient?.gender)].filter(Boolean).join(' · ') || null}
      status={rx.status === 'cancelled' ? { text: tr('Cancelled', 'বাতিল'), good: false } : null}
      meta={title ? null : formatDate(rx.createdAt)}
      details={[
        ['medication', meds.slice(0, 3).map((m) => m.name).join(', ') + (meds.length > 3 ? ' +' + num(meds.length - 3) : '')],
        ['list_alt', rx.tests?.length ? tr(`${rx.tests.length} test(s)`, `${num(rx.tests.length)}টা পরীক্ষা`) : ''],
        ['calendar_month', rx.followUpDate ? tr('Come again: ', 'আবার দেখাবেন: ') + formatDate(rx.followUpDate) : ''],
      ]}
      onClick={onClick}
    />
  );
}
