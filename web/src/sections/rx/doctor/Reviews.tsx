// রিপোর্ট দেখার অনুরোধ — অ্যাপের RxReviewsPane / ReviewReadDialog / ReviewSettingsDialog, প্যানেলের reviewsView/readReport।
// ডাক্তার: সেটিং (ফি, টাকার নম্বর), টাকা মেলানো, রিপোর্ট পড়ে রোগীকে জানানো। সহকারী: শুধু টাকা মেলানো (doctorUid দিয়ে)।
import { useMemo, useState } from 'react';
import { Action, Button, Check, ChoiceChips, Dialog, EmptyState, ErrorBox, ErrorText, IconButton, Loading, PillTabs, SkeletonCards, Switch, Tag, TextArea, TextInput, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDate, timeAgo } from '@/lib/format';
import { imgSrc } from '../../health/common';
import {
  PAY_METHODS, SELF, ageText, flagLabel, forDoctor, payLabel, reviewStatus,
  type Actor, type LabHistory, type LabOrder, type LabResult, type ReviewSettings, type RxReview,
} from './model';

type Link = (path: string, extra?: Record<string, string>) => string;
type View = 'all' | 'claimed' | 'paid' | 'done';
const ORDER: Record<string, number> = { claimed: 0, paid: 1, unpaid: 2, done: 3 };

export function ReviewsPage({ actor }: { actor: Actor; link: Link }) {
  const doctor = actor.role === 'doctor';
  const q = useCall<{ items: RxReview[] }>('reviews_doctor', forDoctor(actor));
  const [view, setView] = useState<View>('all');
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [reading, setReading] = useState<RxReview | null>(null);
  const items = useMemo(
    () => [...(q.data?.items ?? [])].sort((a, b) => (ORDER[a.status] ?? 9) - (ORDER[b.status] ?? 9) || b.updatedAt - a.updatedAt),
    [q.data],
  );
  const count = (s: string) => items.filter((r) => r.status === s).length;
  const shown = items.filter((r) => view === 'all' || r.status === view);

  async function confirm(r: RxReview, ok: boolean) {
    setBusy(r.id + ok);
    setErr(null);
    try {
      await call('review_confirm', { ...forDoctor(actor), id: r.id, ok, ...(ok ? {} : { note: tr('TrxID not found', 'TrxID মেলেনি') }) });
      toast(ok ? tr('Confirmed', 'নিশ্চিত হয়েছে') : tr('The patient has been told', 'রোগীকে জানানো হয়েছে'));
      await q.refetch();
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(null);
  }

  return (
    <div className="col gap-16">
      {doctor ? <SettingsCard /> : null}
      <div className="row wrap rxd-toolbar">
        <PillTabs
          secondary
          label={tr('Reports', 'রিপোর্ট')}
          value={view}
          onChange={(k) => setView(k as View)}
          tabs={[
            { key: 'all', label: tr('All', 'সব') },
            { key: 'claimed', label: tr('Check payment', 'টাকা মেলাতে হবে'), count: count('claimed') },
            ...(doctor ? [{ key: 'paid', label: tr('To read', 'দেখতে হবে'), count: count('paid') }] : []),
            { key: 'done', label: tr('Done', 'দেখা হয়েছে') },
          ]}
        />
        <span className="grow" />
        <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => q.refetch()} />
      </div>
      <ErrorText>{err}</ErrorText>
      {q.isPending ? (
        <SkeletonCards n={3} h={120} />
      ) : q.isError ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !shown.length ? (
        <EmptyState
          icon="list_alt"
          title={tr('No reports yet', 'এখনো কোনো রিপোর্ট নেই')}
          text={view === 'all' ? tr('Reports from labs for your prescriptions appear here.', 'আপনার প্রেসক্রিপশনের পরীক্ষার রিপোর্ট এখানে আসবে।') : undefined}
        />
      ) : (
        <div className="rxd-cards">
          {shown.map((r) => (
            <article key={r.id} className="card col gap-6">
              <div className="row top">
                <div className="col gap-2 grow">
                  <span className="t-title-m">{r.patientName || '-'}</span>
                  <span className="t-body-s muted">{[r.labName, r.tests.join(', ')].filter(Boolean).join(' · ')}</span>
                </div>
                <Tag tone={r.status === 'claimed' || r.status === 'paid' ? 'good' : undefined}>{reviewStatus(r.status)}</Tag>
              </div>
              <span className="t-body-s muted">{timeAgo(r.updatedAt || r.createdAt)}</span>
              {r.status === 'claimed' ? (
                <>
                  <span className="t-body-l semi rxd-break">{payLabel(r.method) + ' · TrxID: ' + (r.trxId ?? '') + (r.fee ? ' · ' + r.fee : '')}</span>
                  <span className="t-body-s muted">{tr('Check your payment app for this TrxID.', 'আপনার bKash/Nagad-এ এই TrxID মিলিয়ে দেখুন।')}</span>
                  <div className="row wrap">
                    <Action primary icon="check_circle" busy={busy === r.id + true} disabled={!!busy} onClick={() => confirm(r, true)}>{tr('Received', 'টাকা পেয়েছি')}</Action>
                    <Action busy={busy === r.id + false} disabled={!!busy} onClick={() => confirm(r, false)}>{tr('Not found', 'মেলেনি')}</Action>
                  </div>
                </>
              ) : null}
              {r.status === 'paid' && doctor ? (
                <div>
                  <Action primary icon="list_alt" onClick={() => setReading(r)}>{tr('Read report & reply', 'রিপোর্ট দেখে জানান')}</Action>
                </div>
              ) : null}
              {r.status === 'done' && r.comment ? <p className="t-body-m pre rxd-comment">{r.comment}</p> : null}
            </article>
          ))}
        </div>
      )}
      {reading ? (
        <ReviewReadDialog
          r={reading}
          onClose={() => setReading(null)}
          onDone={() => {
            setReading(null);
            toast(tr('Sent', 'পাঠানো হয়েছে'));
            q.refetch();
          }}
        />
      ) : null}
    </div>
  );
}

// ---------------- সেটিং ----------------
function settingsLine(s: ReviewSettings) {
  if (!s.enabled) return tr('Off', 'বন্ধ');
  if (s.noFee) return tr('On · No fee', 'চালু · ফি চাই না');
  return [tr('On', 'চালু'), s.fee, s.payMethods.map(payLabel).join(', '), num(s.payNumber)].filter(Boolean).join(' · ');
}

function SettingsCard() {
  const q = useCall<ReviewSettings>('review_settings');
  const [open, setOpen] = useState(false);
  return (
    <section className="card row wrap gap-12 rxd-settings">
      <span className="rxd-ic"><Icon name="settings_outline" /></span>
      <div className="col gap-2 grow">
        <span className="t-title-m">{tr('Online report review', 'অনলাইনে রিপোর্ট দেখা')}</span>
        <span className="t-body-s muted">{q.data ? settingsLine(q.data) : q.isError ? errorText(q.error) : '…'}</span>
      </div>
      <Button variant="outline" icon="edit" disabled={!q.data} onClick={() => setOpen(true)}>{tr('Change settings', 'সেটিং বদলান')}</Button>
      {open && q.data ? (
        <ReviewSettingsDialog
          initial={q.data}
          onClose={() => setOpen(false)}
          onSaved={() => {
            setOpen(false);
            toast(tr('Saved', 'সেভ হয়েছে'));
            q.refetch();
          }}
        />
      ) : null}
    </section>
  );
}

function ReviewSettingsDialog({ initial, onClose, onSaved }: { initial: ReviewSettings; onClose: () => void; onSaved: () => void }) {
  const [v, setV] = useState<ReviewSettings>(initial);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function save() {
    setBusy(true);
    setErr(null);
    try {
      await call('review_settings', { set: true, enabled: v.enabled, noFee: v.noFee, fee: v.fee.trim(), payNumber: v.payNumber.trim(), payMethods: v.payMethods });
      onSaved();
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Online report review', 'অনলাইনে রিপোর্ট দেখা')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} onClick={save}>{tr('Save', 'সেভ')}</Button>
        </>
      }
    >
      <Switch label={tr('Check reports online', 'অনলাইনে রিপোর্ট দেখব')} checked={v.enabled} onChange={(x) => setV({ ...v, enabled: x })} />
      {v.enabled ? (
        <>
          <TextInput label={tr('Fee', 'ফি')} value={v.fee} disabled={v.noFee} placeholder={tr('e.g. 300 taka', 'যেমন: ৩০০ টাকা')} onChange={(e) => setV({ ...v, fee: e.target.value.slice(0, 40) })} />
          <Check checked={v.noFee} onChange={(x) => setV({ ...v, noFee: x, fee: x ? '' : v.fee })}>{tr('No fee', 'ফি চাই না')}</Check>
          {!v.noFee ? (
            <>
              <TextInput label={tr('Number to receive money', 'যে নম্বরে টাকা নেবেন')} type="tel" inputMode="tel" value={v.payNumber} onChange={(e) => setV({ ...v, payNumber: e.target.value.slice(0, 20) })} />
              <ChoiceChips multi options={PAY_METHODS.map((m) => ({ value: m, label: payLabel(m) }))} value={v.payMethods} onChange={(x: string[]) => setV({ ...v, payMethods: x })} />
              <p className="t-body-s muted">{tr('Patients send money to you directly and enter the TrxID. You or your assistant confirm it; then the report opens for you.', 'রোগী সরাসরি আপনাকে টাকা পাঠিয়ে TrxID দেবেন। আপনি বা সহকারী মিলিয়ে নিশ্চিত করলে তবেই রিপোর্ট খুলবে।')}</p>
            </>
          ) : null}
        </>
      ) : null}
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------------- রিপোর্ট পড়ে জানানো ----------------
function ReviewReadDialog({ r, onClose, onDone }: { r: RxReview; onClose: () => void; onDone: () => void }) {
  const q = useCall<{ item: LabOrder; history?: LabHistory }>('lab_order_get', { id: r.labOrderId, ...SELF });
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function send() {
    if (!comment.trim()) return;
    setBusy(true);
    setErr(null);
    try {
      await call('review_done', { id: r.id, comment: comment.trim() });
      onDone();
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      size="wide"
      title={r.patientName || tr('Test report', 'পরীক্ষার রিপোর্ট')}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button icon="send" busy={busy} disabled={!comment.trim()} onClick={send}>{tr('Send to patient', 'রোগীকে পাঠান')}</Button>
        </>
      }
    >
      {q.isPending ? <Loading /> : q.isError ? <ErrorText>{errorText(q.error)}</ErrorText> : <LabReportView o={q.data.item} history={q.data.history ?? {}} />}
      <TextArea
        label={tr('What should the patient know?', 'রোগীকে কী জানাবেন?')}
        rows={3}
        value={comment}
        onChange={(e) => setComment(e.target.value.slice(0, 1000))}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            send();
          }
        }}
      />
      <p className="t-body-s muted">{tr('Need to change medicines? Write a new prescription from the Prescriptions tab.', 'ওষুধ বদলাতে হলে "প্রেসক্রিপশন" থেকে নতুন লিখুন।')}</p>
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

/** রিপোর্ট: পরীক্ষা ধরে ভাগ, বেশি/কম চিহ্ন, একই রোগীর আগের ফল (অ্যাপের LabReportView) — রোগীর ইতিহাসেও (Labs.tsx) */
export function LabReportView({ o, history }: { o: LabOrder; history: LabHistory }) {
  const rep = o.report;
  const groups: { name: string; rows: LabResult[] }[] = [];
  for (const it of rep?.items ?? []) {
    const g = it.group ?? '';
    if (groups.length && groups[groups.length - 1].name === g) groups[groups.length - 1].rows.push(it);
    else groups.push({ name: g, rows: [it] });
  }
  const prevOf = (it: LabResult) => (it.code ? history[`${it.code}.${it.key}`] ?? [] : []);
  const hasPrev = (rep?.items ?? []).some((it) => prevOf(it).length);
  return (
    <div className="col gap-12">
      <div className="col gap-2">
        <span className="t-title-m">{o.labName}</span>
        <span className="t-body-s muted">{[o.patientName, ageText(o.patientAge), rep?.reportedAt ? formatDate(rep.reportedAt) : null].filter(Boolean).join(' · ')}</span>
      </div>
      {groups.map((g, gi) => (
        <div key={gi} className="table-wrap">
          {g.name ? <div className="rxd-rep-group">{g.name}</div> : null}
          <table className="table rxd-rep">
            <thead>
              <tr>
                <th>{tr('Test', 'পরীক্ষা')}</th>
                <th>{tr('Result', 'ফল')}</th>
                <th>{tr('Normal', 'স্বাভাবিক')}</th>
                {hasPrev ? <th>{tr('Before', 'আগে')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {g.rows.map((it, i) => {
                const prev = prevOf(it);
                return (
                  <tr key={i}>
                    <td className="t-title-s">{it.test}</td>
                    <td className={it.long ? 'pre' : 'rxd-nowrap'}>
                      <span className={it.flag ? 'semi c-error' : 'semi'}>{it.result + (it.unit ? ' ' + it.unit : '')}</span>
                      {it.flag ? <> <Tag tone="error">{flagLabel(it.flag)}</Tag></> : null}
                    </td>
                    <td className="muted">{it.range || '—'}</td>
                    {hasPrev ? (
                      <td className="t-body-s muted">
                        {prev.length ? prev.slice(0, 3).map((h) => num(h.result) + (h.flag === 'high' ? '↑' : h.flag === 'low' ? '↓' : '') + ' (' + formatDate(h.at) + ')').join(', ') : '—'}
                      </td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ))}
      {rep?.note ? <p className="t-body-m pre">{rep.note}</p> : null}
      {(rep?.photos ?? []).map((p, i) => (
        <img key={i} src={imgSrc(p)} alt={tr('Report photo', 'রিপোর্টের ছবি')} className="rxd-photo" />
      ))}
    </div>
  );
}
