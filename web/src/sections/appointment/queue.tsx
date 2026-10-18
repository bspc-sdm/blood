// সিরিয়াল চলছে (সার্ভারের server/lib/queue.js; অ্যাপের Queue.kt):
//  - PatientQueue: রোগী (বা কেয়ারগিভার) — আজকের নিশ্চিত সিরিয়ালে এখন কত চলছে, আগে কতজন, কখন আসবেন, "দেরি হবে"
//  - QueuePanel: ডাক্তার/সহকারী — "শুরু করুন", "পরের রোগী", সিরিয়াল চেপে "এখন দেখছি", "বিরতি", দেরির উত্তর
// খোলা থাকলে ৩০ সেকেন্ডে একবার নতুন করে আনে (রিয়েলটাইম নয়)।
import { useState } from 'react';
import { Action, Button, ChoiceChips, Dialog, ErrorText, Tag, TextInput, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { formatTime } from '@/lib/format';
import { isBangla, num, tr } from '@/lib/i18n';
import { today, type Appointment } from './model';

const REFRESH = 30_000;
export const LATE_MINUTES = [10, 20, 30, 60];

export interface LateInfo { min: number; note: string; at: number; status: 'asked' | 'ok' | 'later' }
export interface PatientQueueInfo {
  live: boolean;
  serial?: number;
  started?: boolean;
  paused?: boolean;
  current?: number;
  state?: 'waiting' | 'now' | 'done' | 'passed' | 'finished';
  ahead?: number;
  later?: boolean;
  avgMin?: number;
  eta?: number | null;
  arriveBy?: number | null;
  late?: LateInfo | null;
  canLate?: boolean;
}
export interface QueueRow { id: string; serial: number; name: string; status: string; state: string; ahead: number; late: LateInfo | null }
export interface QueueChamber {
  chamberKey: string; key: string; name: string; nameBn: string; start: string; avgMin: number; avgFrom: string; lateAsked: number;
  queue: { started: boolean; current: number; paused: boolean; finished: boolean; next: number | null; left: number; max: number };
  rows: QueueRow[];
}

/** আজকের নিশ্চিত সিরিয়াল কি না (সারির তথ্য তখনই) */
export const queueLive = (a: Appointment) => a.role === 'patient' && a.status === 'confirmed' && a.serial > 0 && a.date === today();
const lateText = (min: number) => tr(`${min} min late`, `${num(min)} মিনিট দেরি হবে`);

export function usePatientQueue(a: Appointment) {
  return useCall<PatientQueueInfo>('appointment_queue', { id: a.id }, { enabled: queueLive(a), refetchInterval: REFRESH, staleTime: 15_000 });
}

/** "১১:০০-এর মধ্যে উপস্থিত থাকুন" / "এখনই চলে আসুন" */
function arriveText(q: PatientQueueInfo) {
  if (!q.arriveBy) return null;
  return q.arriveBy <= Date.now() ? tr('Come now', 'এখনই চলে আসুন') : tr(`Be there by ${formatTime(q.arriveBy)}`, `${formatTime(q.arriveBy)}-এর মধ্যে উপস্থিত থাকুন`);
}
function stateText(q: PatientQueueInfo): string | null {
  switch (q.state) {
    case 'now': return tr("It's your turn now", 'এখন আপনার পালা');
    case 'done': return tr('Seen', 'দেখা হয়ে গেছে');
    case 'passed': return tr('Your serial has passed — tell the chamber', 'আপনার সিরিয়াল পার হয়ে গেছে — চেম্বারে জানান');
    case 'finished': return tr("Today's serials are over", 'আজকের সিরিয়াল শেষ');
    default: return null;
  }
}

/** তালিকার কার্ডে এক লাইন */
export function PatientQueueLine({ a }: { a: Appointment }) {
  const q = usePatientQueue(a).data;
  if (!queueLive(a) || !q?.live) return null;
  const s = stateText(q);
  return (
    <span className="t-body-s appt-q-line">
      {s ?? [
        q.started ? tr(`Now: #${q.current}`, `এখন চলছে: ${num(q.current ?? 0)}`) : tr('Not started', 'এখনো শুরু হয়নি'),
        tr(`${q.ahead} before you`, `আপনার আগে ${num(q.ahead ?? 0)} জন`),
        arriveText(q),
      ].filter(Boolean).join(' · ')}
    </span>
  );
}

/** বিস্তারিতে পুরো কার্ড + "দেরি হবে" */
export function PatientQueue({ a }: { a: Appointment }) {
  const query = usePatientQueue(a);
  const q = query.data;
  const [asking, setAsking] = useState(false);
  if (!queueLive(a) || !q?.live) return null;
  const s = stateText(q);
  return (
    <section className="card col gap-8 appt-q" aria-live="polite">
      <div className="row wrap gap-6">
        <span className="t-label-m grow">{tr('Serial today', 'আজকের সিরিয়াল')}</span>
        {q.paused ? <Tag tone="primary">{tr('Doctor is on a short break', 'ডাক্তার একটু বিরতিতে')}</Tag> : null}
      </div>
      <div className="appt-q-nums">
        <div className="col gap-2">
          <span className="t-body-s muted">{tr('Now', 'এখন চলছে')}</span>
          <span className="appt-q-big num">{q.started && q.current ? num(q.current) : '—'}</span>
        </div>
        <div className="col gap-2">
          <span className="t-body-s muted">{tr('Your serial', 'আপনার সিরিয়াল')}</span>
          <span className="appt-q-big mine num">{num(q.serial ?? a.serial)}</span>
        </div>
      </div>
      {s ? (
        <span className={cx('t-title-s', q.state === 'now' && 'c-primary')}>{s}</span>
      ) : (
        <>
          {!q.started ? <span className="t-body-m muted">{tr('Not started yet', 'এখনো শুরু হয়নি')}</span> : null}
          <span className="t-title-s">{tr(`${q.ahead} before you`, `আপনার আগে ${num(q.ahead ?? 0)} জন`)}</span>
          {q.later ? <span className="t-body-m appt-note">{tr('You will be seen at the end', 'আপনাকে সবার শেষে দেখা হবে')}</span> : null}
          <span className="t-body-m muted">{tr(`Doctor takes ~${q.avgMin} min each`, `ডাক্তার গড়ে ${num(q.avgMin ?? 10)} মিনিট নেন`)}</span>
          {q.eta ? (
            <div className="appt-q-eta">
              <Icon name="schedule" />
              <span>
                {tr(`Estimated ${formatTime(q.eta)}`, `আনুমানিক সময় ${formatTime(q.eta)}`)}
                {arriveText(q) ? ' — ' + arriveText(q) : ''}
              </span>
            </div>
          ) : null}
        </>
      )}
      {q.late ? <LateLine late={q.late} /> : null}
      {q.canLate ? (
        <div className="row">
          <Button variant="outline" small icon="directions_run" onClick={() => setAsking(true)}>{tr('I will be late', 'দেরি হবে')}</Button>
        </div>
      ) : null}
      {asking ? <LateDialog a={a} current={q.late} onClose={() => setAsking(false)} onSent={() => { setAsking(false); query.refetch(); }} /> : null}
    </section>
  );
}

function LateLine({ late }: { late: LateInfo }) {
  const reply = late.status === 'ok' ? tr('Doctor: OK', 'ডাক্তার: ঠিক আছে') : late.status === 'later' ? tr('Doctor: come later', 'ডাক্তার: পরে আসুন') : tr('Waiting for reply', 'উত্তরের অপেক্ষায়');
  return (
    <span className={cx('t-body-m', late.status === 'asked' ? 'muted' : 'appt-note')}>
      {tr(`Asked for ${late.min} min`, `সময় চেয়েছেন: ${num(late.min)} মিনিট`)} · {reply}
    </span>
  );
}

function LateDialog({ a, current, onClose, onSent }: { a: Appointment; current?: LateInfo | null; onClose: () => void; onSent: () => void }) {
  const [min, setMin] = useState<number | null>(current?.min ?? null);
  const [note, setNote] = useState(current?.note ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function send() {
    if (!min) return setErr(tr('Choose how late', 'কত দেরি হবে বেছে নিন'));
    setBusy(true);
    setErr(null);
    try {
      await call('appointment_late', { id: a.id, minutes: min, note: note.trim() });
      toast(tr('Sent to the doctor', 'ডাক্তারকে পাঠানো হয়েছে'));
      onSent();
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
      title={tr('I will be late', 'দেরি হবে')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} onClick={send}>{tr('Send', 'পাঠান')}</Button>
        </>
      }
    >
      <ChoiceChips options={LATE_MINUTES.map((m) => ({ value: m, label: tr(`${m} min`, `${num(m)} মিনিট`) }))} value={min} onChange={setMin} />
      <TextInput label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} placeholder={tr('e.g. Stuck in traffic', 'যেমন: জ্যামে আছি')} value={note} onChange={(e) => setNote(e.target.value.slice(0, 100))} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------------- ডাক্তার/সহকারী ----------------

/** আজকের সিরিয়ালের সারি চালানো; doctorUid = সহকারী কার হয়ে (ডাক্তার নিজে হলে null) */
export function QueuePanel({ doctorUid }: { doctorUid?: string | null }) {
  const body = doctorUid ? { doctorUid } : {};
  const q = useCall<{ role: string; chambers: QueueChamber[] }>('queue_today', body, { refetchInterval: REFRESH, staleTime: 10_000 });
  if (!q.data?.chambers.length) return q.error ? <ErrorText>{errorText(q.error)}</ErrorText> : null;
  const many = q.data.chambers.length > 1;
  return (
    <div className="col gap-12">
      {q.data.chambers.map((c) => <QueueChamberCard key={c.key} c={c} many={many} doctorUid={doctorUid ?? null} reload={() => q.refetch()} />)}
    </div>
  );
}

function QueueChamberCard({ c, many, doctorUid, reload }: { c: QueueChamber; many: boolean; doctorUid: string | null; reload: () => void }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const qs = c.queue;
  const extra = doctorUid ? { doctorUid } : {};
  async function run(key: string, action: string, b: Record<string, unknown>, done?: string) {
    setBusy(key);
    setErr(null);
    try {
      await call(action, { ...extra, ...b });
      if (done) toast(done);
      reload();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(null);
    }
  }
  const control = (op: string, more: Record<string, unknown> = {}, done?: string) => run(op + (more.appointmentId ?? ''), 'queue_control', { chamberKey: c.chamberKey, op, ...more }, done);
  const status = qs.paused ? tr('Break', 'বিরতি') : qs.finished ? tr('Done', 'শেষ') : qs.started ? tr('Running', 'চলছে') : tr('Not started', 'শুরু হয়নি');
  const rows = c.rows.filter((r) => r.state !== 'cancelled');
  return (
    <section className="card col gap-12 appt-qp" aria-label={tr("Today's serial", 'আজকের সিরিয়াল')}>
      <div className="row wrap gap-6">
        <span className="col gap-2 grow">
          <span className="t-title-m">{tr("Today's serial", 'আজকের সিরিয়াল')}</span>
          {many ? <span className="t-body-s muted">{isBangla() && c.nameBn ? c.nameBn : c.name}</span> : null}
        </span>
        <Tag tone={qs.paused ? 'primary' : qs.started && !qs.finished ? 'good' : undefined}>{status}</Tag>
      </div>
      <div className="appt-q-nums">
        <div className="col gap-2">
          <span className="t-body-s muted">{tr('Seeing now', 'এখন দেখছি')}</span>
          <span className="appt-q-big num">{qs.started && qs.current ? num(qs.current) : '—'}</span>
        </div>
        <div className="col gap-2">
          <span className="t-body-s muted">{tr('Next', 'পরের')}</span>
          <span className="appt-q-big soft num">{qs.next ? num(qs.next) : '—'}</span>
        </div>
        <div className="col gap-2">
          <span className="t-body-s muted">{tr('Avg.', 'গড়')}</span>
          <span className="appt-q-big soft num">{tr(`${c.avgMin}m`, `${num(c.avgMin)} মি.`)}</span>
        </div>
      </div>
      <div className="row wrap gap-6">
        {!qs.started ? (
          <Button icon="chevron_right" busy={busy === 'start'} disabled={!!busy} onClick={() => control('start')}>{tr('Start', 'শুরু করুন')}</Button>
        ) : (
          <>
            <Button icon="chevron_right" busy={busy === 'next'} disabled={!!busy || (!qs.next && qs.finished)} onClick={() => control('next')}>{tr('Next patient', 'পরের রোগী')}</Button>
            {qs.paused ? (
              <Button variant="tonal" icon="sync" busy={busy === 'resume'} disabled={!!busy} onClick={() => control('resume')}>{tr('Resume', 'আবার শুরু')}</Button>
            ) : (
              <Button variant="outline" icon="schedule" busy={busy === 'pause'} disabled={!!busy || qs.finished} onClick={() => control('pause')}>{tr('Break', 'বিরতি')}</Button>
            )}
          </>
        )}
      </div>
      <ErrorText>{err}</ErrorText>
      <div className="appt-qp-rows">
        {rows.map((r) => {
          const now = qs.started && qs.current === r.serial;
          const asked = r.late?.status === 'asked' && (r.state === 'waiting' || r.state === 'later');
          return (
            <div key={r.id} className={cx('appt-qp-row', now && 'now', (r.state === 'done' || r.state === 'passed') && 'appt-dim')}>
              <button
                type="button"
                className="appt-qp-pick"
                disabled={!!busy || now}
                title={tr('Seeing now', 'এখন দেখছি')}
                onClick={() => control('set', { appointmentId: r.id }, tr(`Seeing now: #${r.serial}`, `এখন দেখছি: ${num(r.serial)}`))}
              >
                <span className="appt-serial-chip num">{num(r.serial)}</span>
                <span className="col gap-2 grow appt-qp-name">
                  <span className="t-title-s ellipsis">{r.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট')}</span>
                  <span className="row wrap gap-6">
                    {now ? <Tag tone="good">{tr('Seeing now', 'এখন দেখছি')}</Tag> : null}
                    {r.state === 'done' ? <Tag>{tr('Seen', 'দেখা হয়েছে')}</Tag> : null}
                    {r.state === 'later' ? <Tag>{tr('At the end', 'শেষে')}</Tag> : null}
                    {r.late && (asked || r.state === 'later' || r.late.status === 'ok') && r.state !== 'done' ? <Tag tone={asked ? 'error' : 'info'}>{lateText(r.late.min)}</Tag> : null}
                  </span>
                  {asked && r.late?.note ? <span className="t-body-s muted">{r.late.note}</span> : null}
                </span>
              </button>
              {asked ? (
                <span className="row gap-6 appt-qp-reply">
                  <Action primary busy={busy === 'ok' + r.id} disabled={!!busy} onClick={() => run('ok' + r.id, 'appointment_late_reply', { id: r.id, op: 'ok' })}>{tr('OK', 'ঠিক আছে')}</Action>
                  <Action busy={busy === 'later' + r.id} disabled={!!busy} onClick={() => run('later' + r.id, 'appointment_late_reply', { id: r.id, op: 'later' })}>{tr('Come later', 'পরে আসুন')}</Action>
                </span>
              ) : null}
            </div>
          );
        })}
      </div>
    </section>
  );
}
