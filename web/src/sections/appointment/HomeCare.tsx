// বাসায় সেবা: যে ডাক্তার আর নার্স রোগীর বাসায় যান (অ্যাপের HomeCare.kt)। অ্যাপে অনুরোধ/গ্রহণ নেই —
// রোগী সরাসরি ফোন করেন (home_called দিয়ে হিসাব), ফোনের পর রেটিং (home_rate)। বাছা মানুষ URL-এ।
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ActiveChips, Button, DirectoryCard, EmptyState, ErrorBox, FilterBar, IconButton, InfoText, PhoneLine, PillTabs, SkeletonCards, Switch, toast } from '@/ui/kit';
import { useFilterArea } from '@/ui/AreaPicker';
import { DetailPane, ListDetail } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel, district as findDistrict, upazila } from '@/lib/geo';
import { badgeIcon, coverageText, feeText, homeDistance, homeServiceLabel, homeServicesOf, parseHomePro, ratingLine, specialtyLabel, timesText, useCatalog, type HomePro } from './model';
import { AreaSheet, DetailBlock, DetailHead, FilterChoice, FilterSheet, ReasonReportDialog, StarRatingDialog, useAreasReady, type AreaSel } from './shared';

const upLabels = (ids: number[]) => ids.map((id) => upazila(id)).filter(Boolean).map((u) => (isBangla() ? u!.bn : u!.en));

export function HomeCarePage({ detailId }: { detailId: string | null }) {
  usePageTitle(tr('Home care', 'বাসায় সেবা'));
  useCatalog();
  useAreasReady();
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const { place, guest, uid } = useSession();
  const type = sp.get('t') === 'nurse' ? 'nurse' : 'doctor';
  const hasGps = place?.source === 'gps';
  const [fa, setFa] = useFilterArea();
  // GPS থাকলে শুরুতে নিজের কাছে, নইলে নিজের জেলায়
  const [nearMe, setNearMe] = useState<boolean | null>(null);
  const near = nearMe ?? hasGps;
  const [area, setArea] = useState<AreaSel | null>(null);
  const cur: AreaSel = area ?? { district: fa?.district ?? null, upazilaId: fa?.upazilaId ?? null, unionId: null };
  const [service, setService] = useState<string | null>(null);
  const [female, setFemale] = useState(false);
  const [showArea, setShowArea] = useState(false);
  const [showFilter, setShowFilter] = useState(false);
  const [info, setInfo] = useState<string | null>(null);

  const body: Record<string, unknown> = { type, female };
  if (near && place) {
    body.lat = place.lat;
    body.lng = place.lng;
    // নিজের জেলাও: যাঁরা লোকেশন না দিয়ে উপজেলা বেছেছেন তাঁরাও যেন আসেন
    if (findDistrict(place.placeName)) body.district = place.placeName;
  } else {
    if (cur.district) body.district = cur.district;
    if (cur.upazilaId != null) body.upazilaId = cur.upazilaId;
  }
  if (service) body.service = service;
  const q = useCall<{ items: any[] }>('home_providers', body);
  const list = useMemo(() => (q.data?.items ?? []).map(parseHomePro), [q.data]);
  const inList = detailId ? list.find((p) => p.uid === detailId) ?? null : null;
  // সরাসরি খোলা লিংক: এখনকার এলাকায় না থাকলে সব জায়গার তালিকা থেকে
  const fb = useCall<{ items: any[] }>('home_providers', { type, female: false }, { enabled: !!detailId && !inList && !q.isLoading });
  const selected = inList ?? (detailId ? (fb.data?.items ?? []).map(parseHomePro).find((p) => p.uid === detailId) ?? null : null);

  const qs = sp.toString();
  const listPath = '/appointment/home-care' + (qs ? `?${qs}` : '');
  const [rating, setRating] = useState<HomePro | null>(null);
  const [reporting, setReporting] = useState<HomePro | null>(null);

  const onCall = (p: HomePro) => {
    if (!guest) call('home_called', { uid: p.uid }).catch(() => {});
  };

  const listPart = (
    <div className="list-inner appt-pre">
      <div className="row appt-title">
        <IconButton icon="arrow_back" label={tr('Back', 'ফিরে যান')} onClick={() => nav('/appointment')} />
        <h1 className="t-title-l grow appt-h1">{tr('Home care', 'বাসায় সেবা')}</h1>
      </div>
      <div className="appt-bleed">
        <PillTabs
          secondary
          tabs={[
            { key: 'doctor', label: tr('Doctor at home', 'বাসায় ডাক্তার'), icon: 'stethoscope' },
            { key: 'nurse', label: tr('Nurse at home', 'বাসায় নার্স'), icon: 'medical_services' },
          ]}
          value={type}
          onChange={(k) => {
            setService(null);
            setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
            if (detailId) nav(k === 'doctor' ? '/appointment/home-care' : `/appointment/home-care?t=${k}`, { replace: true });
          }}
        />
      </div>
      <FilterBar
        areaLabel={near ? tr('Near me', 'আমার কাছে') : cur.district ? areaLabel(cur.district, cur.upazilaId) : tr('All districts', 'সব জেলা')}
        onArea={() => setShowArea(true)}
        filterCount={[service, female || null].filter(Boolean).length}
        onFilter={() => setShowFilter(true)}
      >
        <ActiveChips
          items={[
            ...(service ? [{ label: homeServiceLabel(service), onRemove: () => setService(null) }] : []),
            ...(female ? [{ label: tr('Female only', 'শুধু মহিলা'), onRemove: () => setFemale(false) }] : []),
          ]}
        />
        {type === 'nurse' ? <p className="t-body-s muted">{tr('Injection or saline needs a doctor\'s prescription.', 'ইনজেকশন বা স্যালাইনে ডাক্তারের প্রেসক্রিপশন লাগবে।')}</p> : null}
      </FilterBar>
      <InfoText>{info}</InfoText>
      {q.isLoading ? (
        <SkeletonCards n={4} h={170} />
      ) : q.error ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !list.length ? (
        <EmptyState
          icon={type === 'doctor' ? 'stethoscope' : 'medical_services'}
          title={type === 'doctor' ? tr('No doctor who visits homes here yet', 'এখানে বাসায় যান এমন ডাক্তার এখনো নেই') : tr('No nurse who visits homes here yet', 'এখানে বাসায় যান এমন নার্স এখনো নেই')}
          text={tr('Try another area or remove a filter.', 'অন্য এলাকা দিয়ে বা ফিল্টার সরিয়ে দেখুন।')}
        >
          {service || female ? <Button variant="text" onClick={() => { setService(null); setFemale(false); }}>{tr('Remove filters', 'ফিল্টার সরান')}</Button> : null}
        </EmptyState>
      ) : (
        list.map((p) => (
          <HomeProCard
            key={p.uid}
            p={p}
            selected={p.uid === detailId}
            onOpen={() => nav(`/appointment/home-care/${p.uid}${qs ? `?${qs}` : ''}`)}
            onCall={() => {
              onCall(p);
              window.location.href = `tel:${p.phone}`;
            }}
            onRate={p.canRate ? () => setRating(p) : null}
          />
        ))
      )}
    </div>
  );

  const detail = detailId ? (
    selected ? (
      <DetailPane title={selected.name} backTo={listPath} hideTitleWide>
        <HomeProDetail
          p={selected}
          onClose={() => nav(listPath)}
          onCall={() => onCall(selected)}
          onRate={selected.canRate ? () => setRating(selected) : null}
          onReport={!guest && selected.uid !== uid ? () => setReporting(selected) : null}
        />
      </DetailPane>
    ) : (
      <DetailPane title={tr('Home care', 'বাসায় সেবা')} backTo={listPath}>
        {fb.isLoading || q.isLoading ? <SkeletonCards n={2} h={160} /> : <EmptyState icon="home_health" title={tr('Not found', 'পাওয়া যায়নি')} />}
      </DetailPane>
    )
  ) : null;

  return (
    <>
      <ListDetail list={listPart} detail={detail} placeholder={<EmptyState icon="home_health" title={tr('Home care', 'বাসায় সেবা')} />} />
      {showArea ? (
        <AreaSheet
          value={cur}
          allowAll
          nearMe={hasGps ? near : null}
          onNearMe={() => {
            setNearMe(true);
            setShowArea(false);
          }}
          onPick={(v) => {
            setNearMe(false);
            setArea(v);
            if (v.district) setFa({ district: v.district, upazilaId: v.upazilaId, unionId: null });
            setShowArea(false);
          }}
          onClose={() => setShowArea(false)}
        />
      ) : null}
      {showFilter ? (
        <FilterSheet title={tr('Filter', 'ফিল্টার')} onClose={() => setShowFilter(false)} onClear={service || female ? () => { setService(null); setFemale(false); } : undefined}>
          <FilterChoice title={tr('Service', 'সেবা')} options={homeServicesOf(type)} value={service} label={homeServiceLabel} onChange={setService} />
          <Switch label={tr('Female only', 'শুধু মহিলা')} checked={female} onChange={setFemale} />
        </FilterSheet>
      ) : null}
      {rating ? (
        <StarRatingDialog
          title={tr('Rate ', 'রেটিং: ') + rating.name}
          onClose={() => setRating(null)}
          onSubmit={async (stars, comment) => {
            await call('home_rate', { uid: rating.uid, rating: stars, comment });
            setRating(null);
            const m = tr('Thanks for the rating!', 'রেটিং দেওয়ার জন্য ধন্যবাদ!');
            setInfo(m);
            toast(m);
            q.refetch();
          }}
        />
      ) : null}
      {reporting ? (
        <ReasonReportDialog
          onClose={() => setReporting(null)}
          onSubmit={async (reason) => {
            await call('report', { kind: 'homecare', id: reporting.uid, reason });
            setReporting(null);
            const m = tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।');
            setInfo(m);
            toast(m);
          }}
        />
      ) : null}
    </>
  );
}

function HomeProCard({ p, selected, onOpen, onCall, onRate }: { p: HomePro; selected: boolean; onOpen: () => void; onCall: () => void; onRate: (() => void) | null }) {
  const ups = upLabels(p.upazilaIds);
  return (
    <DirectoryCard
      title={p.name}
      icon={badgeIcon(p.type)}
      photo={p.photo || null}
      subtitle={[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')}
      meta={ratingLine(p.rating, p.ratingCount, homeDistance(p.km))}
      details={[
        ['medical_services', p.services.map(homeServiceLabel).join(' · ')],
        ['location_on', ups.length ? tr('Also visits: ', 'যান: ') + ups.join(', ') : ''],
        ['schedule', timesText(p.times, p.schedule)],
        ['payments', feeText(p.noFee, p.fee)],
      ]}
      primary={{ label: tr('Call', 'ফোন করুন'), icon: 'call', onClick: onCall }}
      secondary={onRate ? { label: p.myRating > 0 ? tr(`Your rating ★${p.myRating}`, `আপনার রেটিং ★${num(p.myRating)}`) : tr('Rate', 'রেটিং দিন'), onClick: onRate } : null}
      onClick={onOpen}
      selected={selected}
    />
  );
}

function HomeProDetail({ p, onClose, onCall, onRate, onReport }: { p: HomePro; onClose: () => void; onCall: () => void; onRate: (() => void) | null; onReport: (() => void) | null }) {
  const two = useTwoPane();
  return (
    <>
      <div className="row top">
        <div className="grow">
          <DetailHead
            title={p.name}
            icon={badgeIcon(p.type)}
            photo={p.photo || null}
            subtitle={[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')}
            meta={ratingLine(p.rating, p.ratingCount, homeDistance(p.km))}
          />
        </div>
        {two ? <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={onClose} /> : null}
      </div>
      <div className="appt-phone">
        <PhoneLine phone={p.phone} big onCall={onCall} />
      </div>
      <hr className="divider" />
      <DetailBlock title={tr('Services at home', 'বাসায় যে সেবা দেন')} lines={p.services.map((s) => ['medical_services', homeServiceLabel(s)] as [string, string])} />
      <hr className="divider" />
      <DetailBlock title={tr('Where they go', 'কোথায় যান')} lines={[['location_on', coverageText(p.radiusKm, upLabels(p.upazilaIds))]]} />
      <hr className="divider" />
      <DetailBlock title={tr('Time and fee', 'সময় ও ফি')} lines={[['schedule', timesText(p.times, p.schedule)], ['payments', feeText(p.noFee, p.fee)]]} />
      {onRate || onReport ? (
        <div className="row">
          {onRate ? <Button variant="text" icon="star" onClick={onRate}>{p.myRating > 0 ? tr(`Your rating ★${p.myRating}`, `আপনার রেটিং ★${num(p.myRating)}`) : tr('Rate', 'রেটিং দিন')}</Button> : null}
          <span className="grow" />
          {onReport ? <Button variant="text" icon="flag" onClick={onReport}>{tr('Report', 'রিপোর্ট')}</Button> : null}
        </div>
      ) : null}
    </>
  );
}
