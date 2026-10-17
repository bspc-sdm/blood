// /work — "আমার কাজ": প্রতিটা ব্যবসার অবস্থা, হিসাব আর দ্রুত কাজ (অ্যাপের WorkTab)
import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { Workspace } from '@/shell/templates';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import {
  StatusTag, Stat, ambulanceDocs, badgeIcon, badgeLabel, bloodBankDocs, businessIcon, businessName, callsLine, labDocs, pharmacyDocs, statusCounts,
  useAfterChange, useWorkGate, DocsNeededNotice, MAX_AMBULANCES, MAX_BLOODBANKS,
  type Ambulance, type BloodBank, type CallSummary, type Kind, type Lab, type ListingStats, type Pharmacy,
  WorkEmpty,
} from './shared';

export function WorkOverview() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const w = s.work;
  const amb = w.ambulances as Ambulance[];
  const ph = w.pharmacy as Pharmacy | null;
  const bbs = w.bloodBanks as BloodBank[];
  const lab = w.lab as Lab | null;
  const ready = !gate;
  const ambStats = useCall<{ items: ListingStats[] }>('ambulance_stats', {}, { enabled: ready && amb.length > 0 });
  const phStats = useCall<CallSummary>('pharmacy_stats', {}, { enabled: ready && !!ph });
  const bbStats = useCall<{ items: ListingStats[] }>('bloodbank_stats', {}, { enabled: ready && bbs.length > 0 });
  const finds = useCall<{ items: { answer: string | null }[]; findOff: boolean }>('med_find_requests', {}, { enabled: ready && ph?.status === 'approved' });
  const after = useAfterChange();
  const title = tr('My work', 'আমার কাজ');

  if (gate) return <Workspace title={title}>{gate}</Workspace>;

  const hasBusiness = amb.length > 0 || !!ph || bbs.length > 0 || !!lab;
  if (!hasBusiness && !w.professional) {
    return (
      <Workspace title={title}>
        <WorkEmpty
          icon="storefront"
          title={tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?')}
          text={tr('Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।')}
        >
          <Button icon="add" onClick={() => nav('/work/add')}>{tr('Add business', 'ব্যবসা যোগ করুন')}</Button>
        </WorkEmpty>
      </Workspace>
    );
  }

  // সব মিলিয়ে হিসাব
  const sumCalls = (k: 7 | 30) =>
    (ambStats.data?.items ?? []).reduce((a, x) => a + x.calls[k], 0) + (phStats.data?.calls[k] ?? 0) + (bbStats.data?.items ?? []).reduce((a, x) => a + x.calls[k], 0);
  const statuses = [...amb.map((a) => a.status), ph?.status, ...bbs.map((b) => b.status), lab?.status].filter(Boolean) as string[];
  const live = statuses.filter((x) => x === 'approved').length;
  const review = statuses.filter((x) => x === 'pending').length;
  const openFinds = finds.data && !finds.data.findOff ? finds.data.items.filter((r) => !r.answer).length : null;
  const statsLoaded = !!(ambStats.data || phStats.data || bbStats.data);

  const ambSt = new Map((ambStats.data?.items ?? []).map((x) => [x.id, x]));
  const bbSt = new Map((bbStats.data?.items ?? []).map((x) => [x.id, x]));
  const sumOf = (ids: string[], m: Map<string, ListingStats>): CallSummary | null => {
    const xs = ids.map((id) => m.get(id)).filter(Boolean) as ListingStats[];
    if (!xs.length) return null;
    const add = (k: 7 | 30 | 90 | 'total') => xs.reduce((a, x) => a + x.calls[k], 0);
    return { calls: { 7: add(7), 30: add(30), 90: add(90), total: add('total') }, callers: 0, daily: [] };
  };

  // কাগজের ছবি বাকি
  const notices: ReactNode[] = [];
  const needs = (x: { docsMissing?: boolean; docsRequested?: boolean }) => !!(x.docsMissing || x.docsRequested);
  amb.filter(needs).forEach((a) => notices.push(<DocsNeededNotice key={'a' + a.id} kind="ambulance" id={a.id} specs={ambulanceDocs()} item={a} onDone={after} title={`${businessName('ambulance')} · ${a.name} · ${a.vehicleNo}`} />));
  if (ph && needs(ph)) notices.push(<DocsNeededNotice key="ph" kind="pharmacy" id={ph.id} specs={pharmacyDocs()} item={ph} onDone={after} title={`${businessName('pharmacy')} · ${ph.name}`} />);
  bbs.filter(needs).forEach((b) => notices.push(<DocsNeededNotice key={'b' + b.id} kind="bloodbank" id={b.id} specs={bloodBankDocs(b.kind)} item={b} onDone={after} title={`${businessName('bloodbank')} · ${b.name}`} />));
  if (lab && needs(lab)) notices.push(<DocsNeededNotice key="lab" kind="lab" id={lab.id} specs={labDocs()} item={lab} onDone={after} title={`${businessName('lab')} · ${lab.name}`} />);

  const canAdd = amb.length < MAX_AMBULANCES || !ph || bbs.length < MAX_BLOODBANKS || !lab;

  return (
    <Workspace
      title={title}
      intro={<h2 className="t-headline work-mobile-title">{title}</h2>}
      actions={canAdd ? <Button icon="add" onClick={() => nav('/work/add')}>{hasBusiness ? tr('Add another business', 'আরেকটা ব্যবসা যোগ করুন') : tr('Add business', 'ব্যবসা যোগ করুন')}</Button> : null}
    >
      {hasBusiness ? (
        <div className="stats work-stats">
          <Stat icon="call" value={statsLoaded ? num(sumCalls(7)) : '—'} label={tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')} />
          <Stat icon="call" value={statsLoaded ? num(sumCalls(30)) : '—'} label={tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')} />
          <Stat icon="check_circle" value={num(live)} label={tr('Listed', 'তালিকায় আছে')} />
          <Stat icon="schedule" value={num(review)} label={tr('Under review', 'যাচাই চলছে')} />
          {openFinds != null ? <Stat icon="medication" value={num(openFinds)} label={tr('Medicine requests', 'ওষুধ খোঁজার অনুরোধ')} /> : null}
        </div>
      ) : null}

      {notices.length ? <div className="col gap-8">{notices}</div> : null}

      <div className="work-grid">
        {w.professional ? (
          <BizCard
            icon={badgeIcon(w.professional)}
            title={w.professional === 'nurse' ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার')}
            sub={w.professional === 'nurse' ? badgeLabel(w.professional) : badgeLabel(w.professional) + tr(' · bookings, patients, setup', ' · বুকিং, রোগী, সেটআপ')}
            to="/work/pro"
            actions={<Link to="/work/pro" className="btn small has-icon"><Icon name="calendar_month" />{tr('Open', 'খুলুন')}</Link>}
          />
        ) : null}

        {amb.length ? (
          <BizCard
            kind="ambulance"
            sub={[
              tr(`${amb.length} in total`, `${num(amb.length)}টা`),
              ...(amb.some((a) => a.status === 'approved') ? [tr(`${amb.filter((a) => a.status === 'approved' && a.available).length} free`, `${num(amb.filter((a) => a.status === 'approved' && a.available).length)}টা খালি`)] : []),
              ...statusCounts(amb.map((a) => a.status)),
            ].join(' · ')}
            calls={sumOf(amb.map((a) => a.id), ambSt)}
            to="/work/ambulance"
            actions={amb.length < MAX_AMBULANCES ? <Link to="/work/ambulance/new" className="btn outline small has-icon"><Icon name="add" />{tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন')}</Link> : null}
          >
            <ul className="work-mini-list">
              {amb.slice(0, 4).map((a) => (
                <li key={a.id}>
                  <span className="ellipsis grow">{a.vehicleNo || a.name}</span>
                  {a.status === 'approved' ? <span className={a.available ? 'work-dot on' : 'work-dot'}>{a.available ? tr('Available', 'খালি আছে') : tr('Busy', 'ব্যস্ত')}</span> : <StatusTag status={a.status} />}
                </li>
              ))}
              {amb.length > 4 ? <li className="muted">+{num(amb.length - 4)}</li> : null}
            </ul>
          </BizCard>
        ) : null}

        {ph ? (
          <BizCard
            kind="pharmacy"
            sub={ph.name}
            status={<StatusTag status={ph.status} closed={ph.closedUntil > 0} />}
            calls={phStats.data ?? null}
            to="/work/pharmacy"
            note={ph.closedUntil > 0 ? tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ph.closedUntil) + tr('', ' পর্যন্ত') : null}
            actions={
              <>
                {ph.status === 'approved' ? <a href="/doctor" className="btn outline small has-icon"><Icon name="medication" />{tr('Prescriptions & orders', 'প্রেসক্রিপশন ও অর্ডার')}</a> : null}
                <Link to="/work/pharmacy/edit" className="btn text small has-icon"><Icon name="edit" />{tr('Edit details', 'তথ্য বদলান')}</Link>
              </>
            }
          />
        ) : null}

        {bbs.length ? (
          <BizCard
            kind="bloodbank"
            sub={[bbs.length === 1 ? bbs[0].name : tr(`${bbs.length} blood banks`, `${num(bbs.length)}টা`), ...statusCounts(bbs.map((b) => b.status))].join(' · ')}
            status={bbs.length === 1 ? <StatusTag status={bbs[0].status} /> : null}
            calls={sumOf(bbs.map((b) => b.id), bbSt)}
            to="/work/bloodbank"
            actions={bbs.length < MAX_BLOODBANKS ? <Link to="/work/bloodbank/new" className="btn outline small has-icon"><Icon name="add" />{tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন')}</Link> : null}
          />
        ) : null}

        {lab ? (
          <BizCard
            kind="lab"
            sub={lab.name}
            status={<StatusTag status={lab.status} />}
            to="/work/lab"
            actions={
              <>
                {lab.status === 'approved' ? <a href="/doctor" className="btn outline small has-icon"><Icon name="list_alt" />{tr('Tests & reports', 'পরীক্ষা ও রিপোর্ট')}</a> : null}
                <Link to="/work/lab/edit" className="btn text small has-icon"><Icon name="edit" />{tr('Edit details', 'তথ্য বদলান')}</Link>
              </>
            }
          />
        ) : null}
      </div>
    </Workspace>
  );
}

function BizCard({
  kind, icon, title, sub, status, calls, to, actions, note, children,
}: { kind?: Kind; icon?: string; title?: string; sub: string; status?: ReactNode; calls?: CallSummary | null; to: string; actions?: ReactNode; note?: string | null; children?: ReactNode }) {
  return (
    <article className="card work-biz">
      <Link to={to} className="work-biz-head">
        <span className="work-ic"><Icon name={icon ?? businessIcon(kind!)} /></span>
        <span className="col gap-2 grow">
          <span className="t-title-m">{title ?? businessName(kind!)}</span>
          <span className="t-body-s muted clamp2">{sub}</span>
        </span>
        <Icon name="chevron_right" className="work-chev" />
      </Link>
      {status || calls !== undefined || note ? (
        <div className="col gap-6">
          {status}
          {note ? <span className="t-body-s">{note}</span> : null}
          {calls !== undefined ? (
            <span className="icon-line"><Icon name="call" /><span>{tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(calls)}</span></span>
          ) : null}
        </div>
      ) : null}
      {children}
      {actions ? <div className="row wrap gap-8 work-biz-actions">{actions}</div> : null}
    </article>
  );
}
