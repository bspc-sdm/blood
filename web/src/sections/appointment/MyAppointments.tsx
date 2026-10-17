// আমার অ্যাপয়েন্টমেন্ট (রোগী হিসেবে): সামনের আর আগের, সিরিয়াল, অবস্থা, বাতিল (অ্যাপের MyAppointmentsScreen)।
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, ConfirmDialog, EmptyState, ErrorBox, IconButton, IconLine, PhoneLine, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { DetailPane, ListDetail, SubPageBar } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { formatDate } from '@/lib/format';
import { tr } from '@/lib/i18n';
import { apptChamber, apptWhen, isActive, parseAppointment, serialText, statusLabel, statusTone, today, type Appointment } from './model';

export function MyAppointmentsPage({ detailId }: { detailId: string | null }) {
  const title = tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট');
  usePageTitle(title);
  const nav = useNavigate();
  const two = useTwoPane();
  const { guest, ready, login } = useSession();
  const q = useCall<{ items: any[] }>('my_appointments', {}, { enabled: ready && !guest });
  const mine = useMemo(() => (q.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'patient'), [q.data]);
  const t0 = today();
  const upcoming = mine.filter((a) => isActive(a.status) && a.date >= t0).sort((a, b) => a.date - b.date || a.serial - b.serial);
  const past = mine.filter((a) => !upcoming.includes(a)).sort((a, b) => b.date - a.date || b.createdAt - a.createdAt);
  const selected = detailId ? mine.find((a) => a.id === detailId) ?? null : null;
  const [cancelling, setCancelling] = useState<Appointment | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function cancel(a: Appointment) {
    setBusy(true);
    setErr(null);
    try {
      await call('appointment_update', { id: a.id, op: 'cancel', providerNote: '' });
      setCancelling(null);
      toast(tr('Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'));
      await q.refetch();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const refresh = <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => q.refetch()} />;
  const card = (a: Appointment) => <ApptCard key={a.id} a={a} selected={a.id === detailId} onClick={() => nav(`/my-appointments/${a.id}`)} />;

  const listPart = guest ? (
    <div className="list-inner">
      <EmptyState icon="calendar_month" title={title}>
        <Button onClick={login}>{tr('Log in', 'লগইন')}</Button>
      </EmptyState>
    </div>
  ) : (
    <div className="list-inner">
      {q.isLoading || !ready ? (
        <SkeletonCards n={3} h={140} />
      ) : q.error ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !mine.length ? (
        <EmptyState icon="calendar_month" title={tr('No appointments', 'কোনো অ্যাপয়েন্টমেন্ট নেই')}>
          <Button variant="tonal" icon="stethoscope" onClick={() => nav('/appointment')}>{tr('Appointment', 'অ্যাপয়েন্টমেন্ট')}</Button>
        </EmptyState>
      ) : (
        <>
          {upcoming.length ? <h2 className="section-label appt-sec">{tr('Upcoming', 'সামনের')}</h2> : null}
          {upcoming.map(card)}
          {past.length ? <h2 className="section-label appt-sec">{tr('Past', 'আগের')}</h2> : null}
          {past.map(card)}
        </>
      )}
    </div>
  );

  const detail = detailId ? (
    <DetailPane title={selected?.otherName || title} backTo="/my-appointments" actions={two ? undefined : refresh}>
      {selected ? (
        <ApptDetail a={selected} onCancel={() => { setErr(null); setCancelling(selected); }} />
      ) : q.isLoading ? (
        <SkeletonCards n={1} h={200} />
      ) : (
        <EmptyState icon="calendar_month" title={tr('Not found', 'পাওয়া যায়নি')} />
      )}
    </DetailPane>
  ) : null;

  return (
    <>
      {!detailId || two ? <SubPageBar title={title} backTo="/profile" actions={guest ? undefined : refresh} /> : null}
      <ListDetail list={listPart} detail={detail} placeholder={<EmptyState icon="calendar_month" title={title} />} />
      {cancelling ? (
        <ConfirmDialog
          title={tr('Cancel appointment?', 'অ্যাপয়েন্টমেন্ট বাতিল করবেন?')}
          text={`${cancelling.otherName} · ${formatDate(cancelling.date)}`}
          confirm={tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')}
          danger
          busy={busy}
          error={err}
          onConfirm={() => cancel(cancelling)}
          onClose={() => !busy && setCancelling(null)}
        />
      ) : null}
    </>
  );
}

function ApptCard({ a, selected, onClick }: { a: Appointment; selected: boolean; onClick: () => void }) {
  return (
    <article
      className={cx('card click col gap-6', selected && 'sel', !isActive(a.status) && 'appt-dim')}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
    >
      <div className="row">
        <span className="t-title-m grow clamp2">{a.otherName || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট')}</span>
        <Tag tone={statusTone(a.status)}>{statusLabel(a.status)}</Tag>
      </div>
      <IconLine icon="calendar_month">{apptWhen(a, formatDate)}</IconLine>
      {a.serial > 0 ? <span className="t-title-s bold c-primary">{serialText(a.serial)}</span> : null}
      {a.chamber ? <IconLine icon="local_hospital">{apptChamber(a) + (a.room ? ' · ' + a.room : '')}</IconLine> : null}
      {a.note ? <p className="t-body-m clamp2">{a.note}</p> : null}
      {a.providerNote ? <p className="t-body-m appt-note">{tr('Note: ', 'নোট: ') + a.providerNote}</p> : null}
    </article>
  );
}

function ApptDetail({ a, onCancel }: { a: Appointment; onCancel: () => void }) {
  return (
    <>
      <div className="row">
        <h2 className="t-title-l grow">{a.otherName || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট')}</h2>
        <Tag tone={statusTone(a.status)}>{statusLabel(a.status)}</Tag>
      </div>
      {a.serial > 0 ? (
        <div className="card tonal appt-serial">
          <span className="t-label-m">{tr('Serial', 'সিরিয়াল')}</span>
          <span className="appt-serial-n num">{serialText(a.serial)}</span>
        </div>
      ) : null}
      <div className="col">
        <IconLine icon="calendar_month">{apptWhen(a, formatDate)}</IconLine>
        {a.chamber ? <IconLine icon="local_hospital">{apptChamber(a) + (a.room ? ' · ' + a.room : '')}</IconLine> : null}
        {a.schedule ? <IconLine icon="schedule">{a.schedule}</IconLine> : null}
      </div>
      {a.note ? (
        <div className="col gap-4">
          <span className="t-label-m muted">{tr('Reason for visit', 'কী সমস্যা')}</span>
          <p className="t-body-l pre">{a.note}</p>
        </div>
      ) : null}
      {a.providerNote ? <p className="t-body-m appt-note">{tr('Note: ', 'নোট: ') + a.providerNote}</p> : null}
      {a.otherPhone ? <PhoneLine phone={a.otherPhone} big /> : null}
      {isActive(a.status) ? (
        <div className="row">
          <Button variant="danger-text" icon="close" onClick={onCancel}>{tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')}</Button>
        </div>
      ) : null}
    </>
  );
}
