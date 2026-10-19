// /work/lab/orders/* — প্যাথলজির ডেস্ক (আগে /doctor প্যানেলে ছিল): পরীক্ষার অনুরোধ, কোড দিয়ে খোলা, নমুনা নেওয়া,
// বাসা থেকে নমুনার সময় নিশ্চিত করা, রিপোর্ট দেওয়া (ছকের ঘর, একক, স্বাভাবিক মাত্রা), ছবি, পরীক্ষা ও দাম (শুধু ওয়েবে),
// আর নিজের মেশিনের স্বাভাবিক মাত্রা
// (অ্যাপের MyLabPanel, LabReportEntryDialog, LabReportView)
import { useMemo, useState } from 'react';
import { matchPath, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Button, ErrorBox, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import { LabTabs, myBusinessTitle } from '../../work/shared';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, useLang } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { CodeBox, ModeTag, PartnerPage, useInvalidate, usePartnerGate, useWide } from './common';
import { LabDetail } from './labdetail';
import { LabRanges } from './labranges';
import { LabPrices } from './labprices';
import { LAB_SEGS, ageText, genderText, labSegLabel, labSegOf, labStatusText, labTone, segCounts, type LabOrder, type LabOrders, type LabSeg } from './model';
import { homeLabTag } from './labdetail';

const BASE = '/work/lab/orders';

export function LabOrdersWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const gate = usePartnerGate('lab');
  const title = myBusinessTitle('lab');
  const orders = useCall<LabOrders>('lab_orders', {}, { enabled: !gate, refetchInterval: 60_000 });
  if (gate) return <PartnerPage title={title} backTo="/work">{gate}</PartnerPage>;

  const m = (p: string) => matchPath({ path: BASE + p, end: true }, pathname);
  const ranges = !!m('/ranges');
  const prices = !!m('/prices');
  const detail = ranges || prices ? null : m('/:id');

  // ২.২৭.০: দুই ট্যাব — "অনুরোধ" (তালিকা, একটা অনুরোধ) আর "ল্যাব" (/work/lab/info, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা)
  return (
    <PartnerPage title={title} backTo="/work">
      <LabTabs active={ranges ? 'ranges' : prices ? 'prices' : 'orders'} />
      {ranges ? <LabRanges /> : prices ? <LabPrices /> : detail?.params.id ? <LabDetail key={detail.params.id} id={detail.params.id} orders={orders.data?.items ?? null} ordersLoading={orders.isLoading} /> : <LabList orders={orders} />}
    </PartnerPage>
  );
}
export default LabOrdersWorkspace;

/** পুরোনো লিংকের ?v= (চালু/রিপোর্ট/বাতিল/সব) → নতুন ভাগ */
const LEGACY_VIEW: Record<string, LabSeg> = { open: 'new', reported: 'reported', cancelled: 'closed', all: 'new' };

function LabList({ orders }: { orders: UseQueryResult<LabOrders, Error> }) {
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
  const counts = segCounts(LAB_SEGS, orders.data?.counts, labSegOf as (x: never) => LabSeg, items);
  const asked = (sp.get('s') ?? LEGACY_VIEW[sp.get('v') ?? '']) as LabSeg | null;
  const view: LabSeg = asked && LAB_SEGS.includes(asked) ? asked : 'new';
  const inSeg = items.filter((o) => labSegOf(o) === view).sort((a, c) => c.createdAt - a.createdAt);
  const t = q.trim().toLowerCase();
  const rows = t ? inSeg.filter((o) => o.patientName.toLowerCase().includes(t) || o.code.toLowerCase().includes(t) || o.tests.some((x) => x.toLowerCase().includes(t))) : inSeg;

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
  // ল্যাবে আসবেন / বাসা থেকে নমুনা — বাসার ঠিকানা-ফোন-নোট (সার্ভার শুধু চালু অনুরোধে পাঠায়)
  const fromHome = (o: LabOrder) => !!o.home && o.home.status !== 'declined';
  const modeTag = (o: LabOrder) => (
    <ModeTag remote={fromHome(o)} icon={fromHome(o) ? 'home_health' : 'local_hospital'}>{fromHome(o) ? tr('Sample from home', 'বাসা থেকে নমুনা') : tr('Will come to the lab', 'ল্যাবে আসবেন')}</ModeTag>
  );
  const homeContact = (o: LabOrder) => {
    const h = o.home;
    if (!h || !fromHome(o) || !['sent', 'received'].includes(o.status) || !(h.address || h.phone || h.note)) return null;
    return (
      <div className="col gap-2" onClick={(e) => e.stopPropagation()}>
        {h.address ? <span className="icon-line"><Icon name="location_on" /><span>{h.address}</span></span> : null}
        {h.phone ? <span className="icon-line"><Icon name="call" /><a className="rxp-phone num" href={`tel:${h.phone}`}>{num(h.phone)}</a></span> : null}
        {h.note ? <span className="icon-line"><Icon name="edit_note" /><span>{h.note}</span></span> : null}
      </div>
    );
  };
  const who = (o: LabOrder) => [ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ');

  return (
    <div className="col gap-16">
      <section className="card rxp-panel rxp-lab-code rxp-open-code">
        <div className="row gap-8">
          <span className="rxp-panel-ic"><Icon name="search" /></span>
          <h2 className="t-title-m grow">{tr('Open a prescription by code', 'কোড দিয়ে প্রেসক্রিপশন খুলুন')}</h2>
        </div>
        <CodeBox label={tr('Patient came with a paper prescription? Code under the QR (or paste the link)', 'রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে: QR-এর নিচের কোড (বা লিংক পেস্ট করুন)')} busy={opening} error={openErr} onOpen={openCode} />
        <p className="t-body-s muted">{tr('The tests are added here and the report form opens.', 'পরীক্ষাগুলো এখানে যোগ হবে, রিপোর্টের পাতা খুলবে।')}</p>
      </section>

      <div className="row wrap gap-8">
        <div className="rxp-sub-pills">
          <PillTabs
            secondary
            label={tr('Test requests', 'পরীক্ষার অনুরোধ')}
            value={view}
            onChange={(k) => setSp(k === 'new' ? {} : { s: k }, { replace: true })}
            tabs={LAB_SEGS.map((s) => ({ key: s, label: labSegLabel(s), count: counts[s] }))}
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
          <span className="t-title-s">
            {t ? tr('Nothing matches', 'মেলে এমন কিছু নেই')
              : view === 'new' ? tr('No new requests now', 'এখন নতুন কোনো অনুরোধ নেই')
                : view === 'active' ? tr('No samples waiting', 'এখন কোনো নমুনা বা চলমান পরীক্ষা নেই')
                  : view === 'reported' ? tr('No reports given yet', 'এখনো কোনো রিপোর্ট দেওয়া হয়নি') : tr('Nothing cancelled', 'বাতিল হওয়া কোনো অনুরোধ নেই')}
          </span>
          {!t && view === 'new' ? <span className="t-body-s">{tr('Tests patients send from their prescriptions come here.', 'রোগীরা প্রেসক্রিপশন থেকে পরীক্ষা পাঠালে এখানে আসবে।')}</span> : null}
          {!t && view === 'active' ? <span className="t-body-s">{tr('After you take a sample (or confirm a home visit), it waits here for the report.', 'নমুনা নিলে (বা বাসায় যাওয়ার সময় নিশ্চিত করলে) রিপোর্ট দেওয়া পর্যন্ত এখানে থাকবে।')}</span> : null}
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
                  <td className="rxp-td-tests">
                    <div className="col gap-4">
                      <span>{o.tests.join(', ')}</span>
                      {modeTag(o)}
                      {homeLabTag(o)}
                      {homeContact(o)}
                    </div>
                  </td>
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
              <div className="row gap-8">
                <span className="t-title-s grow">{o.patientName || '—'}</span>
                {modeTag(o)}
              </div>
              <div className="row wrap gap-6">
                <Tag tone={labTone(o.status)}>{labStatusText(o.status)}</Tag>
                <span className="t-body-s muted">{[who(o), timeAgo(o.createdAt)].filter(Boolean).join(' · ')}</span>
              </div>
              <span className="t-body-m">{o.tests.join(', ')}</span>
              {homeLabTag(o)}
              {homeContact(o)}
              {o.doctorName ? <span className="t-body-s muted">{o.doctorName}</span> : null}
              {actions(o)}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
