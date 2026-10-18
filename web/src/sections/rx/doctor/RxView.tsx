// ডাক্তারের লেখা প্রেসক্রিপশন: তালিকা (rx_doctor_list) আর একটা খুলে কাগজ, ছাপা, বাতিল (rx_get, rx_cancel)।
// অ্যাপের RxDoctorTab / RxDetailDialog(asDoctor) / RxPaper; প্যানেলের listView / paperView। ছাপায় শুধু কাগজটা।
import { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { Button, Dialog, EmptyState, ErrorBox, ErrorText, IconButton, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useMedia } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { RxPaper, patientLine } from '../../health/Prescriptions';
import { SELF, statusTag, useRxInvalidate, type DoctorLab, type DoctorRx } from './model';
import { RxCard } from './Patients';
import { DoctorLabList } from './Labs';

type Link = (path: string, extra?: Record<string, string>) => string;

// ---------------- তালিকা ----------------
export function RxListPage({ link }: { link: Link }) {
  const nav = useNavigate();
  const wide = useMedia('(min-width: 1100px)');
  const q = useCall<{ items: DoctorRx[] }>('rx_doctor_list');
  const [s, setS] = useState('');
  const shown = useMemo(() => {
    const t = toAsciiDigits(s.trim().toLowerCase());
    return (q.data?.items ?? []).filter((r) => !t || [r.patient?.name, r.diagnosis, r.code].some((x) => String(x ?? '').toLowerCase().includes(t)));
  }, [q.data, s]);
  const open = (id: string) => nav(link('/view/' + id));

  return (
    <div className="col gap-12">
      <div className="row wrap rxd-toolbar">
        <label className="rxd-search">
          <Icon name="search" />
          <input value={s} onChange={(e) => setS(e.target.value.slice(0, 40))} placeholder={tr('Search by name', 'নাম দিয়ে খুঁজুন')} aria-label={tr('Search by name', 'নাম দিয়ে খুঁজুন')} />
        </label>
        <span className="grow" />
        <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => q.refetch()} />
        <Button icon="edit_note" onClick={() => nav(link('/write'))}>{tr('New prescription', 'নতুন প্রেসক্রিপশন')}</Button>
      </div>
      {q.isPending ? (
        <SkeletonCards n={4} h={72} />
      ) : q.isError ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !shown.length ? (
        <EmptyState icon="medication" title={s ? tr('No one found', 'কাউকে পাওয়া যায়নি') : tr('No prescriptions yet', 'এখনো কোনো প্রেসক্রিপশন নেই')} />
      ) : wide ? (
        <div className="table-wrap">
          <table className="table rxd-table">
            <thead>
              <tr>
                <th>{tr('Date', 'তারিখ')}</th>
                <th>{tr('Patient', 'রোগী')}</th>
                <th>{tr('Diagnosis', 'রোগনির্ণয়')}</th>
                <th>{tr('Medicines', 'ওষুধ')}</th>
                <th>{tr('Status', 'অবস্থা')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((rx) => (
                <tr
                  key={rx.id}
                  className={cx('rxd-click', rx.status === 'cancelled' && 'rxd-dim')}
                  tabIndex={0}
                  onClick={() => open(rx.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') open(rx.id);
                  }}
                >
                  <td className="rxd-nowrap">{formatDate(rx.createdAt)}</td>
                  <td className="rxd-td-name"><span className="t-title-s">{patientLine(rx) || '-'}</span></td>
                  <td className="rxd-td-wrap">{rx.diagnosis || <span className="muted">—</span>}</td>
                  <td className="rxd-td-wrap muted">{(rx.medicines ?? []).slice(0, 4).map((m) => m.name).join(', ') + ((rx.medicines?.length ?? 0) > 4 ? ' +' + num(rx.medicines.length - 4) : '')}</td>
                  <td>
                    <span className="row wrap gap-6">
                      <Tag tone={rx.status === 'cancelled' ? 'error' : 'good'}>{statusTag(rx.status)}</Tag>
                      {rx.linked ? <Tag tone="primary">{tr('In app', 'অ্যাপে')}</Tag> : null}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="grid-cards">
          {shown.map((rx) => <RxCard key={rx.id} rx={rx} onClick={() => open(rx.id)} />)}
        </div>
      )}
    </div>
  );
}

// ---------------- একটা প্রেসক্রিপশন ----------------
const PRINT_PATH = 'M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z';
function PrintIcon() {
  return <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d={PRINT_PATH} /></svg>;
}

export function RxViewPage({ id, link }: { id: string; link: Link }) {
  const nav = useNavigate();
  const invalidate = useRxInvalidate();
  const q = useCall<{ item: DoctorRx; labOrders?: DoctorLab[] }>('rx_get', { id, ...SELF });
  const [cancelling, setCancelling] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const rx = q.data?.item ?? null;
  const labs = q.data?.labOrders ?? [];
  const back = () => (window.history.length > 1 ? nav(-1) : nav(link('/list')));

  async function doCancel() {
    if (!rx) return;
    setBusy(true);
    setErr(null);
    try {
      await call('rx_cancel', { id: rx.id });
      invalidate();
      await q.refetch();
      setCancelling(false);
      toast(tr('Cancelled', 'বাতিল করা হয়েছে'));
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }

  if (q.isPending) return <SkeletonCards n={1} h={420} />;
  if (q.isError || !rx) return <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />;
  const active = rx.status !== 'cancelled';

  return (
    <div className="rxd-view">
      <div className="row wrap rxd-toolbar rxd-view-bar">
        <Button variant="text" icon="arrow_back" onClick={back}>{tr('Back', 'ফিরে যান')}</Button>
        <span className="grow" />
        <Button variant="tonal" className="rxd-print-btn" onClick={() => window.print()}>
          <PrintIcon />
          {tr('Print / PDF', 'ছাপুন / PDF')}
        </Button>
      </div>
      <div className="rxd-paper">
        <RxPaper rx={rx} />
      </div>
      <aside className="col gap-12 rxd-view-side">
        <section className="card col gap-6">
          <span className="t-label-m muted">{tr('Code', 'কোড')}</span>
          <span className="t-title-l health-code">{rx.code}</span>
          <span className="row wrap gap-6">
            <Tag tone={active ? 'good' : 'error'}>{statusTag(rx.status)}</Tag>
            {rx.linked ? <Tag tone="primary">{tr('In app', 'অ্যাপে আছেন')}</Tag> : null}
          </span>
          {!rx.linked && active ? (
            <p className="t-body-s muted">{tr('The patient adds the prescription to their own app with this number and the code.', 'রোগী এই নম্বর আর কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।')}</p>
          ) : null}
        </section>
        {rx.tests?.length || labs.length ? (
          // পার্টনার ল্যাবের পরীক্ষা — অবস্থা সবসময়, ফল শুধু রিপোর্ট দেখার টাকা মিললে / ফি না থাকলে
          <section className="card col gap-6" aria-label={tr('Test reports', 'পরীক্ষার রিপোর্ট')}>
            <span className="t-label-m muted">{tr('Test reports', 'পরীক্ষার রিপোর্ট')}</span>
            {labs.length ? (
              <DoctorLabList labs={labs} />
            ) : (
              <span className="t-body-s muted">{tr('The patient has not sent these tests to a partner lab yet', 'রোগী এখনো কোনো পার্টনার ল্যাবে পরীক্ষা পাঠাননি')}</span>
            )}
          </section>
        ) : null}
        <Button icon="edit_note" variant="outline" onClick={() => nav(link('/write'))}>{tr('New prescription', 'নতুন প্রেসক্রিপশন')}</Button>
        {active ? (
          <Button variant="danger-text" icon="close" onClick={() => { setErr(null); setCancelling(true); }}>
            {tr('Cancel this prescription', 'এই প্রেসক্রিপশন বাতিল করুন')}
          </Button>
        ) : null}
      </aside>

      {createPortal(
        <div className="health-print rxd-print" aria-hidden="true">
          <RxPaper rx={rx} print />
        </div>,
        document.body,
      )}

      {cancelling ? (
        // ConfirmDialog-এর মতো, তবে অ্যাপের "রেখে দিন" — পাশাপাশি "বাতিল" আর "বাতিল করুন" গুলিয়ে যায়
        <Dialog
          onClose={() => !busy && setCancelling(false)}
          size="small"
          title={tr('Cancel this prescription?', 'প্রেসক্রিপশনটা বাতিল করবেন?')}
          actions={
            <>
              <Button variant="text" disabled={busy} onClick={() => setCancelling(false)}>{tr('Keep', 'রেখে দিন')}</Button>
              <Button variant="danger" busy={busy} onClick={doCancel}>{tr('Cancel it', 'বাতিল করুন')}</Button>
            </>
          }
        >
          <div className="t-body-m muted">{tr('Pharmacies will see it as cancelled. You can write a new one.', 'ফার্মেসি যাচাই করলে বাতিল দেখাবে। চাইলে নতুন করে লিখতে পারেন।')}</div>
          <ErrorText>{err}</ErrorText>
        </Dialog>
      ) : null}
    </div>
  );
}
