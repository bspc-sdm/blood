// পেশাদারের কাজের জায়গা (/work/pro/*): বুকিং (সিরিয়ালের সারি), রোগী, চেম্বার ও প্রোফাইল সেটআপ, বাসায় সেবার হিসাব —
// অ্যাপের ChamberHubScreen / AppointmentsList(role = provider) / PatientsList / ChamberEditScreen / MyHomeCareCard।
// কম্পিউটারে চেম্বারের সহকারী বা ডাক্তার ব্যবহার করবেন — তাই চওড়া টেবিল। প্রেসক্রিপশন, টিম, সহকারী: /work/rx।
import { useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Button, ConfirmDialog, Dialog, EmptyState, ErrorBox, ErrorText, IconButton, IconLine, PhoneLine, PillTabs, Select, SkeletonCards, Tag, TextInput, cx, toast,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { Workspace } from '@/shell/templates';
import { useMedia } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { formatDate } from '@/lib/format';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import {
  CHAMBER_TYPES, HOME_TYPES, PROVIDER_TYPES, apptChamber, badgeLabel, hhmmText, isActive, parseAppointment, parseProvider, statusLabel, statusTone, today, useCatalog, type Appointment, type HomeStats,
} from '../model';
import { MiniBars, StatTile, ratingValue, useAreasReady } from '../shared';
import { ChamberEdit } from './ChamberEdit';
import '../appointment.css';

export function ProfessionalWorkspace({ base = '/work/pro' }: { base?: string }) {
  useCatalog();
  useAreasReady();
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { work, me, guest, ready, login } = useSession();
  const pm = useCall<{ provider: any | null }>('provider_me', {}, { enabled: ready && !guest });
  const provider = pm.data?.provider ? parseProvider(pm.data.provider) : null;
  const badge = me?.badge && me.badge.status === 'approved' && PROVIDER_TYPES.includes(me.badge.type) ? me.badge.type : null;
  const type = work.professional ?? badge ?? (provider && PROVIDER_TYPES.includes(provider.type) ? provider.type : null);
  const nurse = type === 'nurse';
  const chamberType = !!type && CHAMBER_TYPES.includes(type);
  const appts = useCall<{ items: any[] }>('my_appointments', {}, { enabled: ready && !guest && chamberType });
  const mine = useMemo(() => (appts.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'provider'), [appts.data]);
  const pending = mine.filter((a) => a.status === 'pending').length;

  const sub = pathname.startsWith(base) ? pathname.slice(base.length).replace(/\/$/, '') : '';
  const title = nurse ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার');

  if (!ready || (!guest && !type && pm.isLoading)) {
    return (
      <Workspace title={title}>
        <SkeletonCards n={3} h={120} />
      </Workspace>
    );
  }
  if (guest) {
    return (
      <Workspace title={title}>
        <EmptyState icon="stethoscope" title={title}>
          <Button onClick={login}>{tr('Log in', 'লগইন')}</Button>
        </EmptyState>
      </Workspace>
    );
  }
  if (!type) {
    return (
      <Workspace title={title}>
        <EmptyState icon="workspace_premium" title={tr('Only verified professionals can add chambers', 'শুধু যাচাই করা পেশাজীবীরা চেম্বার যোগ করতে পারেন')}>
          <Link className="btn tonal" to="/profile">{tr('Profile', 'প্রোফাইল')}</Link>
        </EmptyState>
      </Workspace>
    );
  }

  // ট্যাব: ডাক্তার/পুষ্টিবিদ/ট্রেইনার — বুকিং, রোগী, সেটআপ (+ ডাক্তারের বাসায় সেবা); নার্স — বাসায় সেবা, সেটআপ
  const tabs = nurse
    ? [
      { key: '', label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health' },
      { key: '/chamber', label: tr('Setup', 'সেটআপ'), icon: 'settings_outline' },
    ]
    : [
      { key: '', label: tr('Bookings', 'বুকিং'), icon: 'event_outline', selectedIcon: 'event_filled', count: pending },
      { key: '/patients', label: tr('Patients', 'রোগী'), icon: 'person_outline' },
      { key: '/chamber', label: tr('Setup', 'সেটআপ'), icon: 'settings_outline' },
      ...(type && HOME_TYPES.includes(type) ? [{ key: '/home', label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health' }] : []),
    ];
  const cur = tabs.some((t) => t.key === sub) ? sub : '';
  const go = (k: string) => nav(base + k);

  return (
    <Workspace
      title={title}
      intro={
        <div className="col gap-2">
          <h1 className="t-headline appt-h1">{title}</h1>
          <span className="t-body-m muted">{[provider?.name, badgeLabel(type)].filter(Boolean).join(' · ')}</span>
        </div>
      }
      actions={
        type === 'doctor' ? (
          <Link className="btn tonal has-icon" to="/work/rx">
            <Icon name="edit_note" />
            {tr('Write a prescription', 'প্রেসক্রিপশন লিখুন')}
          </Link>
        ) : undefined
      }
    >
      <div className="appt-ws-tabs">
        <PillTabs tabs={tabs} value={cur} onChange={go} label={title} />
      </div>
      {nurse ? (
        cur === '/chamber' ? <ChamberEdit type={type} onSaved={() => go('')} /> : <HomeCareTab onSetup={() => go('/chamber')} />
      ) : cur === '/patients' ? (
        <PatientsTab list={mine} loading={appts.isLoading} error={appts.error ? errorText(appts.error) : null} />
      ) : cur === '/chamber' ? (
        <ChamberEdit type={type} onSaved={() => { appts.refetch(); go(''); }} />
      ) : cur === '/home' ? (
        <HomeCareTab onSetup={() => go('/chamber')} />
      ) : (
        <BookingsTab list={mine} loading={appts.isLoading} error={appts.error ? errorText(appts.error) : null} reload={() => appts.refetch()} hasChamber={!!provider?.chambers.length} onSetup={() => go('/chamber')} />
      )}
      {type === 'doctor' ? <DoctorPanelCard /> : null}
    </Workspace>
  );
}
export default ProfessionalWorkspace;

/** প্রেসক্রিপশন, টিম আর সহকারী (আমার কাজ › প্রেসক্রিপশন) */
function DoctorPanelCard() {
  return (
    <Link className="card click appt-panel-card" to="/work/rx">
      <span className="row wrap gap-16">
        <span className="appt-panel-ico"><Icon name="medication" /></span>
        <span className="col gap-2 grow">
          <span className="t-title-m">{tr('Prescriptions', 'প্রেসক্রিপশন')}</span>
          <span className="t-body-s muted">{tr('Prescriptions, patients, team and assistants', 'প্রেসক্রিপশন, রোগী, টিম ও সহকারী')}</span>
        </span>
        <span className="btn text has-icon"><Icon name="chevron_right" />{tr('Open', 'খুলুন')}</span>
      </span>
    </Link>
  );
}

// ---------------- বুকিং: সিরিয়ালের সারি ----------------
type View = 'today' | 'upcoming' | 'waiting' | 'past';

function BookingsTab({ list, loading, error, reload, hasChamber, onSetup }: { list: Appointment[]; loading: boolean; error: string | null; reload: () => void; hasChamber: boolean; onSetup: () => void }) {
  const wide = useMedia('(min-width: 1100px)');
  const [sp, setSp] = useSearchParams();
  const [chamber, setChamber] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<Appointment | null>(null);
  const [cancelling, setCancelling] = useState<Appointment | null>(null);
  const t0 = today();

  const chambers = [...new Set(list.map((a) => a.chamber).filter(Boolean))];
  const inCh = list.filter((a) => !chamber || a.chamber === chamber);
  const bySerial = (a: Appointment, b: Appointment) => (a.serial || 999) - (b.serial || 999) || a.createdAt - b.createdAt;
  const groups: Record<View, Appointment[]> = {
    today: inCh.filter((a) => a.date === t0 && a.status !== 'expired').sort((a, b) => Number(!isActive(a.status) && a.status !== 'completed') - Number(!isActive(b.status) && b.status !== 'completed') || bySerial(a, b)),
    upcoming: inCh.filter((a) => a.date > t0 && isActive(a.status)).sort((a, b) => a.date - b.date || bySerial(a, b)),
    waiting: inCh.filter((a) => a.status === 'pending').sort((a, b) => a.date - b.date || a.createdAt - b.createdAt),
    past: inCh.filter((a) => a.date < t0 || (a.date > t0 && !isActive(a.status))).sort((a, b) => b.date - a.date || bySerial(a, b)),
  };
  const asked = sp.get('v') as View | null;
  const view: View = asked && asked in groups ? asked : groups.today.length ? 'today' : groups.waiting.length ? 'waiting' : 'upcoming';
  const rows = groups[view];
  const patients = new Set(list.filter((a) => a.status === 'confirmed' || a.status === 'completed').map((a) => a.otherKey || a.otherName)).size;

  async function act(a: Appointment, op: string, extra: Record<string, unknown> = {}) {
    setBusyId(a.id);
    setErr(null);
    try {
      await call('appointment_update', { id: a.id, op, providerNote: '', ...extra });
      reload();
      return true;
    } catch (e) {
      setErr(errorText(e));
      return false;
    } finally {
      setBusyId(null);
    }
  }

  if (loading) return <SkeletonCards n={4} h={72} />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;

  const actions = (a: Appointment) => (
    <div className="row wrap appt-acts">
      {a.status === 'pending' ? (
        <>
          <Button small busy={busyId === a.id} disabled={!!busyId} onClick={() => setConfirming(a)}>{tr('Confirm', 'নিশ্চিত করুন')}</Button>
          <Button small variant="text" disabled={!!busyId} onClick={() => act(a, 'decline')}>{tr('Decline', 'না করুন')}</Button>
        </>
      ) : a.status === 'confirmed' ? (
        <Button small variant="danger-text" disabled={!!busyId} onClick={() => setCancelling(a)}>{tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')}</Button>
      ) : null}
    </div>
  );
  const name = (a: Appointment) => a.otherName || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট');
  const serialCell = (a: Appointment) => (a.serial > 0 ? <span className="appt-serial-chip num">{num(a.serial)}</span> : <span className="muted">—</span>);

  return (
    <div className="col gap-16">
      <div className="stats">
        <StatTile value={num(groups.today.filter((a) => isActive(a.status)).length)} label={tr('Today', 'আজ')} />
        <StatTile value={num(groups.waiting.length)} label={tr('Waiting', 'অপেক্ষায়')} className={groups.waiting.length ? 'appt-stat-hot' : undefined} />
        <StatTile value={num(groups.upcoming.length)} label={tr('Upcoming', 'সামনের')} />
        <StatTile value={num(patients)} label={tr('Patients', 'রোগী')} />
      </div>
      <div className="row wrap appt-toolbar">
        <PillTabs
          secondary
          tabs={[
            { key: 'today', label: tr('Today', 'আজ'), count: groups.today.length },
            { key: 'waiting', label: tr('Waiting', 'অপেক্ষায়'), count: groups.waiting.length },
            { key: 'upcoming', label: tr('Upcoming', 'সামনের'), count: groups.upcoming.length },
            { key: 'past', label: tr('Past', 'আগের') },
          ]}
          value={view}
          onChange={(k) => setSp(k === 'today' ? {} : { v: k }, { replace: true })}
        />
        <span className="grow" />
        {chambers.length > 1 ? (
          <div className="appt-ch-select">
            <Select aria-label={tr('Chamber', 'চেম্বার')} value={chamber} onChange={(e) => setChamber(e.target.value)} options={[{ value: '', label: tr('All chambers', 'সব চেম্বার') }, ...chambers.map((c) => ({ value: c, label: apptChamber(list.find((a) => a.chamber === c)!) }))]} />
          </div>
        ) : null}
        <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={reload} />
      </div>
      <ErrorText>{err}</ErrorText>
      {!rows.length ? (
        <EmptyState icon="calendar_month" title={tr('No appointments', 'কোনো অ্যাপয়েন্টমেন্ট নেই')}>
          {!hasChamber ? <Button variant="tonal" icon="add" onClick={onSetup}>{tr('Add chamber', 'চেম্বার যোগ করুন')}</Button> : null}
        </EmptyState>
      ) : wide ? (
        <div className="table-wrap">
          <table className="table appt-table">
            <thead>
              <tr>
                <th>{tr('Serial', 'সিরিয়াল')}</th>
                <th>{tr('Patient', 'রোগী')}</th>
                <th>{tr('Date', 'তারিখ')}</th>
                <th>{tr('Time', 'সময়')}</th>
                {chambers.length > 1 && !chamber ? <th>{tr('Chamber', 'চেম্বার')}</th> : null}
                <th>{tr('Status', 'অবস্থা')}</th>
                <th>{tr('Phone', 'ফোন')}</th>
                <th aria-label={tr('Actions', 'কাজ')} />
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id} className={cx(!isActive(a.status) && a.status !== 'completed' && 'appt-dim')}>
                  <td>{serialCell(a)}</td>
                  <td className="appt-td-name">
                    <div className="t-title-s">{name(a)}</div>
                    {a.note ? <div className="t-body-s muted clamp2">{a.note}</div> : null}
                    {a.providerNote ? <div className="t-body-s appt-note">{tr('Note: ', 'নোট: ') + a.providerNote}</div> : null}
                  </td>
                  <td className="appt-nowrap">{a.date === t0 ? tr('Today', 'আজ') : formatDate(a.date)}</td>
                  <td className="appt-nowrap">{a.start ? hhmmText(a.start) : '—'}</td>
                  {chambers.length > 1 && !chamber ? <td>{apptChamber(a)}</td> : null}
                  <td><Tag tone={statusTone(a.status)}>{statusLabel(a.status)}</Tag></td>
                  <td className="appt-phone-cell">{a.otherPhone ? <PhoneLine phone={a.otherPhone} /> : <span className="muted">—</span>}</td>
                  <td>{actions(a)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="grid-cards appt-grid">
          {rows.map((a) => (
            <article key={a.id} className={cx('card col gap-6', !isActive(a.status) && a.status !== 'completed' && 'appt-dim')}>
              <div className="row">
                {a.serial > 0 ? serialCell(a) : null}
                <span className="t-title-m grow clamp2">{name(a)}</span>
                <Tag tone={statusTone(a.status)}>{statusLabel(a.status)}</Tag>
              </div>
              <IconLine icon="calendar_month">{(a.date === t0 ? tr('Today', 'আজ') : formatDate(a.date)) + (a.start ? ', ' + tr('from ', '') + hhmmText(a.start) + tr('', ' থেকে') : '')}</IconLine>
              {a.chamber ? <IconLine icon="local_hospital">{apptChamber(a) + (a.room ? ' · ' + a.room : '')}</IconLine> : null}
              {a.note ? <p className="t-body-m">{a.note}</p> : null}
              {a.providerNote ? <p className="t-body-m appt-note">{tr('Note: ', 'নোট: ') + a.providerNote}</p> : null}
              {a.otherPhone ? <PhoneLine phone={a.otherPhone} /> : null}
              {actions(a)}
            </article>
          ))}
        </div>
      )}

      {confirming ? (
        <ConfirmApptDialog
          a={confirming}
          next={1 + Math.max(0, ...list.filter((x) => x.date === confirming.date && x.chamber === confirming.chamber && x.serial > 0).map((x) => x.serial))}
          onClose={() => setConfirming(null)}
          onConfirm={async (serial, note) => {
            const ok = await act(confirming, 'confirm', { providerNote: note, ...(serial ? { serial } : {}) });
            if (ok) setConfirming(null);
            return ok;
          }}
          busy={busyId === confirming.id}
          error={err}
        />
      ) : null}
      {cancelling ? (
        <ConfirmDialog
          title={tr('Cancel appointment?', 'অ্যাপয়েন্টমেন্ট বাতিল করবেন?')}
          text={`${name(cancelling)} · ${formatDate(cancelling.date)}${cancelling.serial ? ' · ' + tr('Serial #', 'সিরিয়াল নং ') + num(cancelling.serial) : ''}`}
          confirm={tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')}
          danger
          busy={busyId === cancelling.id}
          error={err}
          onConfirm={async () => {
            if (await act(cancelling, 'cancel')) {
              setCancelling(null);
              toast(tr('Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'));
            }
          }}
          onClose={() => setCancelling(null)}
        />
      ) : null}
    </div>
  );
}

function ConfirmApptDialog({ a, next, onClose, onConfirm, busy, error }: { a: Appointment; next: number; onClose: () => void; onConfirm: (serial: number | null, note: string) => Promise<boolean>; busy: boolean; error: string | null }) {
  // নিজের খাতার সিরিয়াল (ফোনে/সরাসরি নেওয়া রোগীসহ); খালি রাখলে অ্যাপ নিজে পরের নম্বর দেয়
  const [serial, setSerial] = useState('');
  const [note, setNote] = useState('');
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="small"
      title={tr('Confirm appointment', 'অ্যাপয়েন্টমেন্ট নিশ্চিত')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} onClick={() => onConfirm(parseInt(toAsciiDigits(serial), 10) || null, note.trim())}>{tr('Confirm', 'নিশ্চিত করুন')}</Button>
        </>
      }
    >
      <div className="col gap-2">
        <span className="t-title-m">{a.otherName}</span>
        <span className="t-body-s muted">{formatDate(a.date)}{a.chamber ? ' · ' + apptChamber(a) : ''}</span>
      </div>
      <TextInput
        label={tr('Serial no. (optional)', 'সিরিয়াল নম্বর (ঐচ্ছিক)')}
        hint={tr('Leave empty and the app gives the next number', 'খালি রাখলে অ্যাপ পরের নম্বর দেবে')}
        inputMode="numeric"
        placeholder={num(next)}
        value={serial}
        onChange={(e) => setSerial(e.target.value.replace(/[^0-9০-৯]/g, '').slice(0, 3))}
      />
      <TextInput
        label={tr('Note for patient (optional)', 'রোগীর জন্য নোট (ঐচ্ছিক)')}
        placeholder={tr('e.g. Come at 6 PM', 'যেমন: সন্ধ্যা ৬টায় আসবেন')}
        value={note}
        onChange={(e) => setNote(e.target.value.slice(0, 200))}
      />
      <ErrorText>{error}</ErrorText>
    </Dialog>
  );
}

// ---------------- রোগী: একজন একবার — কতবার এসেছেন, শেষ কবে, ফোন ----------------
function PatientsTab({ list, loading, error }: { list: Appointment[]; loading: boolean; error: string | null }) {
  const wide = useMedia('(min-width: 1100px)');
  const [q, setQ] = useState('');
  const pats = useMemo(() => {
    const m = new Map<string, Appointment[]>();
    list.filter((a) => a.status === 'confirmed' || a.status === 'completed').forEach((a) => {
      const k = a.otherKey || a.otherName;
      m.set(k, [...(m.get(k) ?? []), a]);
    });
    return [...m.values()]
      .map((a) => ({ name: a[0].otherName, count: a.length, last: Math.max(...a.map((x) => x.date)), phone: a.find((x) => x.otherPhone)?.otherPhone ?? '' }))
      .sort((x, y) => y.last - x.last);
  }, [list]);
  const t = q.trim().toLowerCase();
  const shown = pats.filter((p) => !t || p.name.toLowerCase().includes(t) || p.phone.includes(toAsciiDigits(t)));
  if (loading) return <SkeletonCards n={4} h={64} />;
  if (error) return <ErrorBox error={error} />;
  if (!pats.length) return <EmptyState icon="person_outline" title={tr('No patients yet', 'এখনো কোনো রোগী নেই')} />;
  const visits = (p: { count: number; last: number }) => tr(`${p.count} visit(s) · last `, `${num(p.count)} বার · শেষ `) + formatDate(p.last);
  return (
    <div className="col gap-12">
      <input className="input appt-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={tr('Search by name', 'নাম দিয়ে খুঁজুন')} aria-label={tr('Search by name', 'নাম দিয়ে খুঁজুন')} />
      {wide ? (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>{tr('Patient', 'রোগী')}</th>
                <th>{tr('Visits', 'কতবার')}</th>
                <th>{tr('Last', 'শেষ')}</th>
                <th>{tr('Phone', 'ফোন')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((p, i) => (
                <tr key={i}>
                  <td className="t-title-s">{p.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট')}</td>
                  <td className="num">{num(p.count)}</td>
                  <td className="appt-nowrap">{formatDate(p.last)}</td>
                  <td className="appt-phone-cell">{p.phone ? <PhoneLine phone={p.phone} /> : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="grid-cards appt-grid">
          {shown.map((p, i) => (
            <article key={i} className="card col gap-6">
              <span className="t-title-m">{p.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট')}</span>
              <span className="t-body-s muted">{visits(p)}</span>
              {p.phone ? <PhoneLine phone={p.phone} /> : null}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------- বাসায় সেবা: চালু কি না, রিপোর্টের পর বন্ধ, অ্যাপ থেকে ফোনের হিসাব ----------------
function HomeCareTab({ onSetup }: { onSetup: () => void }) {
  const q = useCall<HomeStats>('home_stats');
  const s = q.data;
  if (q.isLoading) return <SkeletonCards n={2} h={120} />;
  if (q.error || !s) return <ErrorBox error={q.error ? errorText(q.error) : ''} onRetry={() => q.refetch()} />;
  if (s.blocked) {
    return (
      <div className="card danger col">
        <span className="t-title-m">{tr('Your home care', 'আপনার বাসায় সেবা')}</span>
        <p className="t-body-m">{tr('Turned off by the Lifolo team after a report. You can appeal.', 'রিপোর্টের পর Lifolo টিম বন্ধ করেছে। আপিল করতে পারেন।')}</p>
        <Link className="btn outline has-icon" to="/help/new?category=appointment" style={{ alignSelf: 'flex-start' }}><Icon name="help" />{tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন')}</Link>
      </div>
    );
  }
  if (!s.enabled) {
    return (
      <div className="card tonal col">
        <span className="t-title-m">{tr('Your home care', 'আপনার বাসায় সেবা')}</span>
        <p className="t-body-m">{tr('Do you visit patients at home? Turn it on so patients nearby can call you.', 'রোগীর বাসায় যান? চালু করুন, কাছের রোগীরা সরাসরি ফোন করবেন।')}</p>
        <Button variant="filled" icon="home_health" onClick={onSetup} style={{ alignSelf: 'flex-start' }}>{tr('Turn on home care', 'বাসায় সেবা চালু করুন')}</Button>
      </div>
    );
  }
  return (
    <div className="col gap-16">
      <div className="row wrap">
        <span className="t-body-m grow">{tr('You are in the list — patients call you directly.', 'আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।')}</span>
        <Button variant="outline" icon="edit" onClick={onSetup}>{tr('Change settings', 'সেটিং বদলান')}</Button>
      </div>
      <div className="stats">
        <StatTile value={num(s.calls?.[7] ?? 0)} label={tr('Calls in 7 days', '৭ দিনে ফোন')} />
        <StatTile value={num(s.calls?.[30] ?? 0)} label={tr('Calls in 30 days', '৩০ দিনে ফোন')} />
        <StatTile value={num(s.callers ?? 0)} label={tr('Different callers', 'আলাদা মানুষ')} />
        <StatTile value={ratingValue(s)} label={tr('Rating', 'রেটিং')} />
        {s.reports > 0 ? <StatTile value={num(s.reports)} label={tr('Reports', 'রিপোর্ট')} className="appt-stat-hot" /> : null}
      </div>
      <div className="ws-grid side">
        <section className="card col">
          <h2 className="t-title-s">{tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')}</h2>
          <MiniBars values={s.daily ?? []} />
        </section>
        <section className="card col">
          <h2 className="t-title-s">{tr('Recent ratings', 'সাম্প্রতিক রেটিং')}</h2>
          {s.comments?.length ? (
            s.comments.map((c, i) => (
              <div key={i} className="col gap-2 appt-comment">
                <span className="appt-stars-txt" aria-label={num(c.rating)}>{'★'.repeat(Math.max(0, Math.min(5, c.rating)))}</span>
                {c.comment ? <span className="t-body-m">{c.comment}</span> : null}
              </div>
            ))
          ) : (
            <span className="t-body-m muted">—</span>
          )}
        </section>
      </div>
    </div>
  );
}
