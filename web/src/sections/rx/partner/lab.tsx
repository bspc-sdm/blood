// /work/lab/orders/* — প্যাথলজির ডেস্ক (আগে /doctor প্যানেলে ছিল): পরীক্ষার অনুরোধ, কোড দিয়ে খোলা, নমুনা নেওয়া,
// রিপোর্ট দেওয়া (ছকের ঘর, একক, স্বাভাবিক মাত্রা), ছবি, আর নিজের মেশিনের স্বাভাবিক মাত্রা
// (অ্যাপের MyLabPanel, LabReportEntryDialog, LabReportView)
import { useMemo, useState } from 'react';
import { matchPath, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Button, ErrorBox, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { CodeBox, PartnerPage, useInvalidate, usePartnerGate, useWide } from './common';
import { LabDetail } from './labdetail';
import { LabRanges } from './labranges';
import { ageText, genderText, labStatusText, labTone, type LabOrder } from './model';

const BASE = '/work/lab/orders';

export function LabOrdersWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const nav = useNavigate();
  const gate = usePartnerGate('lab');
  const title = tr('Tests & reports', 'পরীক্ষা ও রিপোর্ট');
  const orders = useCall<{ items: LabOrder[] }>('lab_orders', {}, { enabled: !gate, refetchInterval: 60_000 });
  if (gate) return <PartnerPage title={title} backTo="/work/lab">{gate}</PartnerPage>;

  const m = (p: string) => matchPath({ path: BASE + p, end: true }, pathname);
  const ranges = !!m('/ranges');
  const detail = ranges ? null : m('/:id');
  const fresh = (orders.data?.items ?? []).filter((o) => o.status === 'sent').length;

  return (
    <PartnerPage title={title} backTo="/work/lab">
      <div className="rxp-tabs">
        <PillTabs
          label={title}
          value={ranges ? 'ranges' : 'orders'}
          onChange={(k) => nav(k === 'ranges' ? `${BASE}/ranges` : BASE)}
          tabs={[
            { key: 'orders', label: tr('Test requests', 'পরীক্ষার অনুরোধ'), icon: 'list_alt', count: fresh },
            { key: 'ranges', label: tr('Normal ranges', 'স্বাভাবিক মাত্রা'), icon: 'tune' },
          ]}
        />
      </div>
      {ranges ? <LabRanges /> : detail?.params.id ? <LabDetail key={detail.params.id} id={detail.params.id} orders={orders.data?.items ?? null} ordersLoading={orders.isLoading} /> : <LabList orders={orders} />}
    </PartnerPage>
  );
}
export default LabOrdersWorkspace;

type View = 'open' | 'reported' | 'cancelled' | 'all';

function LabList({ orders }: { orders: UseQueryResult<{ items: LabOrder[] }, Error> }) {
  const nav = useNavigate();
  const wide = useWide();
  const invalidate = useInvalidate();
  const [sp, setSp] = useSearchParams();
  const [q, setQ] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [openErr, setOpenErr] = useState<string | null>(null);
  const items = useMemo(() => orders.data?.items ?? [], [orders.data]);
  const groups: Record<View, LabOrder[]> = {
    open: items.filter((o) => o.status === 'sent' || o.status === 'received'),
    reported: items.filter((o) => o.status === 'reported'),
    cancelled: items.filter((o) => o.status === 'cancelled'),
    all: items,
  };
  const asked = sp.get('v') as View | null;
  const view: View = asked && asked in groups ? asked : 'open';
  const t = q.trim().toLowerCase();
  const rows = t ? groups[view].filter((o) => o.patientName.toLowerCase().includes(t) || o.code.toLowerCase().includes(t) || o.tests.some((x) => x.toLowerCase().includes(t))) : groups[view];

  async function openCode(code: string) {
    setOpening(true);
    setOpenErr(null);
    try {
      const r = await call<{ item: LabOrder }>('lab_open_code', { code });
      await invalidate('lab_orders');
      nav(`${BASE}/${r.item.id}`);
    } catch (e) {
      setOpenErr(errorText(e));
    } finally {
      setOpening(false);
    }
  }
  async function sampleTaken(o: LabOrder) {
    setBusyId(o.id);
    setErr(null);
    try {
      await call('lab_order_status', { id: o.id, status: 'received' });
      await invalidate('lab_orders');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusyId(null);
    }
  }

  const go = (o: LabOrder) => nav(`${BASE}/${o.id}`);
  const actions = (o: LabOrder) => (
    <div className="row wrap gap-6 rxp-row-actions" onClick={(e) => e.stopPropagation()}>
      {o.status === 'sent' ? <Button small variant="outline" busy={busyId === o.id} disabled={!!busyId} onClick={() => sampleTaken(o)}>{tr('Sample taken', 'নমুনা নিয়েছি')}</Button> : null}
      {o.status === 'sent' || o.status === 'received' ? <Button small onClick={() => go(o)}>{tr('Give report', 'রিপোর্ট দিন')}</Button> : null}
      {o.status === 'reported' ? <Button small variant="tonal" onClick={() => go(o)}>{tr('View', 'দেখুন')}</Button> : null}
    </div>
  );
  const who = (o: LabOrder) => [ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ');

  return (
    <div className="col gap-16">
      <section className="card rxp-panel rxp-lab-code">
        <CodeBox label={tr('Patient came with a paper prescription?', 'কাগজের প্রেসক্রিপশন নিয়ে এলে')} busy={opening} error={openErr} onOpen={openCode} />
        <p className="t-body-s muted">{tr('Enter the code under the QR — the tests are added here.', 'QR-এর নিচের কোড দিন — পরীক্ষাগুলো এখানে যোগ হবে।')}</p>
      </section>

      <div className="row wrap gap-8">
        <div className="rxp-sub-pills">
          <PillTabs
            secondary
            label={tr('Test requests', 'পরীক্ষার অনুরোধ')}
            value={view}
            onChange={(k) => setSp(k === 'open' ? {} : { v: k }, { replace: true })}
            tabs={[
              { key: 'open', label: tr('Active', 'চালু'), count: groups.open.length },
              { key: 'reported', label: tr('Report given', 'রিপোর্ট দেওয়া হয়েছে') },
              { key: 'cancelled', label: tr('Cancelled', 'বাতিল') },
              { key: 'all', label: tr('All', 'সব') },
            ]}
          />
        </div>
        <span className="grow" />
        <div className="rxp-search rxp-search-sm">
          <Icon name="search" />
          <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder={tr('Name, code or test', 'নাম, কোড বা পরীক্ষা')} aria-label={tr('Search', 'খুঁজুন')} />
        </div>
      </div>
      {err ? <ErrorBox error={err} /> : null}

      {orders.isLoading ? <SkeletonCards n={4} h={72} /> : orders.error ? <ErrorBox error={errorText(orders.error)} onRetry={() => orders.refetch()} /> : !rows.length ? (
        <div className="card rxp-empty-card">
          <Icon name="list_alt" />
          <span>{items.length ? tr('Nothing here', 'এখানে কিছু নেই') : tr('No test requests yet', 'এখনো কোনো পরীক্ষার অনুরোধ নেই')}</span>
        </div>
      ) : wide ? (
        <div className="table-wrap">
          <table className="table rxp-table">
            <thead>
              <tr>
                <th>{tr('Patient', 'রোগী')}</th>
                <th>{tr('Tests', 'পরীক্ষা')}</th>
                <th>{tr('Doctor', 'ডাক্তার')}</th>
                <th>{tr('Status', 'অবস্থা')}</th>
                <th>{tr('Came', 'এসেছে')}</th>
                <th><span className="sr-only">{tr('Actions', 'কাজ')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((o) => (
                <tr
                  key={o.id}
                  className="rxp-click"
                  tabIndex={0}
                  onClick={() => go(o)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && e.target === e.currentTarget) go(o);
                  }}
                >
                  <td>
                    <div className="col gap-2">
                      <span className="semi">{o.patientName || '—'}</span>
                      <span className="t-body-s muted">{[who(o), o.code].filter(Boolean).join(' · ')}</span>
                    </div>
                  </td>
                  <td className="rxp-td-tests">{o.tests.join(', ')}</td>
                  <td className="t-body-s">{o.doctorName || '—'}</td>
                  <td><Tag tone={labTone(o.status)}>{labStatusText(o.status)}</Tag></td>
                  <td className="t-body-s muted rxp-nowrap" title={formatDateTime(o.createdAt)}>{timeAgo(o.createdAt)}</td>
                  <td className="rxp-td-actions">{actions(o)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="rxp-cards">
          {rows.map((o) => (
            <article key={o.id} className={cx('card click col gap-6')} role="button" tabIndex={0} onClick={() => go(o)} onKeyDown={(e) => e.key === 'Enter' && e.target === e.currentTarget && go(o)}>
              <div className="row">
                <span className="t-title-s grow">{o.patientName || '—'}</span>
                <Tag tone={labTone(o.status)}>{labStatusText(o.status)}</Tag>
              </div>
              <span className="t-body-s muted">{[who(o), timeAgo(o.createdAt)].filter(Boolean).join(' · ')}</span>
              <span className="t-body-m">{o.tests.join(', ')}</span>
              {o.doctorName ? <span className="t-body-s muted">{o.doctorName}</span> : null}
              {actions(o)}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
