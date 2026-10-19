// সেবার তালিকা: অ্যাম্বুলেন্স / ফার্মেসি / ব্লাড ব্যাংক / প্যাথলজি — এলাকা, ফিল্টার, কার্ড, আর পাশে (বা পুরো পাতায়) বিস্তারিত
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ActiveChips, Avatar, Button, ChoiceChips, EmptyState, ErrorBox, FilterBar, Loading, SkeletonCards, Switch } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { AreaPicker, useFilterArea } from '@/ui/AreaPicker';
import { ListDetail, DetailPane } from '@/shell/templates';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { areaLabel, currentPosition, districtAt, ensureAreas } from '@/lib/geo';
import { useSession } from '@/lib/session';
import {
  ambulanceEntry, ambulanceKm, ambulanceTypeLabel, ambulanceTypes, bloodBankEntry, kindIcon, kindLabel, labEntry, pharmacyEntry, recall, remember, useCatalog,
  type Ambulance, type BloodBank, type Entry, type Kind, type Lab, type ListResult, type Pharmacy, type RateAspect,
} from './data';
import { Emergency999, EntryDetail, FilterSheet, NearestCall, RatingDialog, ReportDialog, ServiceCard, ServiceTabs } from './ui';
import type { PublicPrices } from '@/lib/labprice';
import { RxSendFromPlace } from '@/sections/health/RxActivity';

const ACTION: Record<Kind, string> = { ambulance: 'ambulances', pharmacy: 'pharmacies', bloodbank: 'bloodbanks', lab: 'labs' };

/**
 * প্যাথলজির বিস্তারিত: এলাকার তালিকায় শুধু দামের সারাংশ, পুরো পরীক্ষা ও দামের তালিকা এখানে আলাদা করে
 * (lab_prices_public — অতিথিও পারেন; সার্ভারে এক মিনিট মেমোরিতে, এখানে এক মিনিট রাখা)।
 */
function LabEntryDetail({ lab, backTo }: { lab: Lab; backTo: string }) {
  const q = useCall<{ prices: PublicPrices | null }>('lab_prices_public', { labId: lab.id }, { enabled: !!lab.prices, staleTime: 60 * 1000 });
  const full = !lab.prices ? null : q.data ? q.data.prices : q.isError ? null : undefined;
  return (
    <EntryDetail e={labEntry(lab, full)} backTo={backTo}>
      <RxSendFromPlace lab={lab} />
    </EntryDetail>
  );
}

/** এলাকার নাম (উপজেলার তালিকা এলে আবার আঁকা) */
function useAreasLoaded() {
  const [, set] = useState(0);
  useEffect(() => {
    let live = true;
    ensureAreas().then(() => live && set((n) => n + 1), () => {});
    return () => {
      live = false;
    };
  }, []);
}

/** byArea: GPS নেই — সার্ভারে স্থানাঙ্ক পাঠানো হয় না, আগের মতো নিজের উপজেলা আগে; lat/lng তখন null */
type Origin = { lat: number; lng: number; byArea: false } | { lat: null; lng: null; byArea: true };
// ২ দশমিক (≈১ কিমি) — সার্ভারও এভাবেই কাটে; এর বেশি সূক্ষ্ম পাঠানোর দরকার নেই
const r2 = (v: number) => Math.round(v * 100) / 100;

/**
 * অ্যাম্বুলেন্সের দূরত্ব কোথা থেকে: ব্রাউজারে লোকেশনের অনুমতি আগেই দেওয়া থাকলে এখনকার জায়গা (বেছে নেওয়া জেলার ভেতরে হলে),
 * নইলে প্রোফাইলের GPS জায়গা (একই জেলা), নইলে "এলাকা ধরে" (স্থানাঙ্ক নয় — জেলার কেন্দ্র থেকে দূরত্ব অর্থহীন, উপজেলার ক্রম নষ্ট করে)।
 * অনুমতি চেয়ে আটকানো হয় না — প্রতিটা সেকেন্ড দামি।
 * GPS-এর জন্য সর্বোচ্চ ২.৫ সেকেন্ড অপেক্ষা; তারপর একবার ঠিক হলে আর বদলায় না (বাটনের গাড়ি হঠাৎ বদলে যেন ভুল জায়গায় চাপ না পড়ে)।
 */
function useAmbulanceOrigin(district: string | undefined, on: boolean): { origin: Origin | null; ready: boolean } {
  const s = useSession();
  const [gps, setGps] = useState<{ lat: number; lng: number; district: string } | null | undefined>(on ? undefined : null);
  const settled = useRef(false);
  useEffect(() => {
    if (!on) return;
    let live = true;
    const done = (v: { lat: number; lng: number; district: string } | null) => {
      if (!live || settled.current) return;
      settled.current = true;
      setGps(v);
    };
    const timer = setTimeout(() => done(null), 2500);
    const q = navigator.permissions?.query?.({ name: 'geolocation' as PermissionName });
    if (!q) done(null);
    else {
      q.then(async (p) => {
        if (p.state !== 'granted') return null;
        const pos = await currentPosition();
        return { lat: pos.lat, lng: pos.lng, district: (await districtAt(pos.lat, pos.lng)).bn };
      })
        .then((v) => done(v), () => done(null));
    }
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [on]);
  const origin = useMemo<Origin | null>(() => {
    if (!on || !district) return null;
    if (gps && gps.district === district) return { lat: r2(gps.lat), lng: r2(gps.lng), byArea: false };
    const p = s.place;
    if (p?.source === 'gps' && p.placeName === district) return { lat: r2(p.lat), lng: r2(p.lng), byArea: false };
    return { lat: null, lng: null, byArea: true };
  }, [on, district, gps, s.place]);
  return { origin, ready: !on || gps !== undefined };
}

export function Directory({ kind, id }: { kind: Kind; id: string | null }) {
  useAreasLoaded();
  const nav = useNavigate();
  const { search } = useLocation();
  const [params, setParams] = useSearchParams();
  const s = useSession();
  const lists = useCatalog();
  const [area, setArea] = useFilterArea();
  const [showArea, setShowArea] = useState(false);
  const [showFilter, setShowFilter] = useState(false);
  const [busyOpen, setBusyOpen] = useState(false);
  const [rating, setRating] = useState<{ id: string; name: string; aspects?: RateAspect[] } | null>(null);
  const [reporting, setReporting] = useState<string | null>(null);

  // ফিল্টার URL-এ (বিস্তারিত খুলে ফিরে এলেও থাকে)
  const type = kind === 'ambulance' ? params.get('type') : null;
  const openNow = kind === 'pharmacy' && params.get('open') === '1';
  // ২৪ ঘণ্টা খোলা: ব্লাড ব্যাংক আর ফার্মেসি (রাতে ওষুধ দরকার হলে)
  const open24 = (kind === 'bloodbank' || kind === 'pharmacy') && params.get('h24') === '1';
  const setParam = (k: string, v: string | null) => {
    const p = new URLSearchParams(params);
    if (v == null) p.delete(k);
    else p.set(k, v);
    setParams(p, { replace: true });
  };
  const clearFilters = () => setParams(new URLSearchParams(), { replace: true });

  const gps = s.place?.source === 'gps' ? s.place : null;
  const { origin, ready } = useAmbulanceOrigin(area?.district, kind === 'ambulance');
  const body = useMemo(() => {
    const b: Record<string, unknown> = { district: area?.district, upazilaId: area?.upazilaId ?? null };
    if (kind === 'ambulance' && type) b.type = type;
    // কাছের খালি গাড়ি আগে (সার্ভার দূরত্ব হিসাব করে) — শুধু আসল GPS হলে; "এলাকা ধরে" হলে কিছুই নয় (উপজেলা আগে)
    if (kind === 'ambulance' && origin && !origin.byArea) Object.assign(b, { lat: origin.lat, lng: origin.lng, gps: true });
    if (kind === 'pharmacy') Object.assign(b, { lat: gps?.lat ?? null, lng: gps?.lng ?? null, openNow, open24 });
    if (kind === 'bloodbank') b.open24 = open24;
    return b;
  }, [kind, area?.district, area?.upazilaId, type, openNow, open24, gps?.lat, gps?.lng, origin]);
  const q = useCall<ListResult<any>>(ACTION[kind], body, { enabled: !!area?.district && ready });
  const items = q.data?.items ?? [];
  const busy = (q.data?.busy ?? []) as Ambulance[];
  useEffect(() => {
    remember(kind, q.data?.items);
    remember(kind, q.data?.busy);
  }, [kind, q.data]);

  const toEntry = (x: any): Entry =>
    kind === 'ambulance' ? ambulanceEntry(x as Ambulance, lists) : kind === 'pharmacy' ? pharmacyEntry(x as Pharmacy) : kind === 'bloodbank' ? bloodBankEntry(x as BloodBank, lists) : labEntry(x as Lab);
  const entries = items.map(toEntry);
  const busyEntries = busy.map(toEntry);

  const listPath = `/services/${kind}${search}`;
  const open = (eid: string) => nav(`/services/${kind}/${eid}${search}`);
  const rateGate = (e: Entry) => () => s.requireLogin(tr('Log in to do this', 'এর জন্য লগইন করুন'), () => setRating({ id: e.id, name: e.title, aspects: e.rateAspects }));
  const reportGate = (e: Entry) => () => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => setReporting(e.id));

  const card = (e: Entry) => (
    <ServiceCard
      key={e.id}
      e={e}
      selected={e.id === id}
      onOpen={() => open(e.id)}
      onRate={e.canRate ? rateGate(e) : undefined}
      onReport={e.reportable ? reportGate(e) : undefined}
    />
  );

  // ---------- ফিল্টার ----------
  const filterCount = (type ? 1 : 0) + (openNow ? 1 : 0) + (open24 ? 1 : 0);
  const chips = [
    ...(type ? [{ label: ambulanceTypeLabel(lists, type), onRemove: () => setParam('type', null) }] : []),
    ...(openNow ? [{ label: tr('Open now', 'এখন খোলা'), onRemove: () => setParam('open', null) }] : []),
    ...(open24 ? [{ label: tr('24 hours', '২৪ ঘণ্টা'), onRemove: () => setParam('h24', null) }] : []),
  ];

  // ---------- খালি অবস্থা ----------
  const empty = () => {
    const owner = (
      <Link to="/work/add" className="btn text small">{tr('Add yours from My work', '"আমার কাজ" থেকে যোগ করুন')}</Link>
    );
    if (kind === 'ambulance') {
      return (
        <EmptyState
          icon="ambulance"
          title={(q.data?.busyCount ?? busy.length) > 0 ? tr('All ambulances here are on a trip now', 'এই এলাকার সব অ্যাম্বুলেন্স এখন ট্রিপে আছে') : tr('No ambulance listed in this area yet', 'এই এলাকায় এখনো কোনো অ্যাম্বুলেন্স যোগ হয়নি')}
          text={tr('Know an ambulance service? They can join from "My work".', 'কোনো অ্যাম্বুলেন্স সার্ভিস চিনলে "আমার কাজ" থেকে যোগ হতে বলুন।')}
        >
          {owner}
        </EmptyState>
      );
    }
    if (kind === 'pharmacy') {
      return (
        <EmptyState icon="storefront" title={tr('No pharmacy in the app here yet', 'এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই')} text={tr('Know a pharmacy? They can join from "My work".', 'কোনো ফার্মেসি চিনলে "আমার কাজ" থেকে যোগ হতে বলুন।')}>
          {owner}
        </EmptyState>
      );
    }
    if (kind === 'bloodbank') {
      return (
        <EmptyState icon="bloodtype" title={tr('No blood bank in the app here yet', 'এখানে এখনো অ্যাপে কোনো ব্লাড ব্যাংক নেই')} text={tr('Know one? They can join from "My work".', 'কোনো ব্লাড ব্যাংক চিনলে "আমার কাজ" থেকে যোগ হতে বলুন।')}>
          {owner}
        </EmptyState>
      );
    }
    return (
      <EmptyState icon="local_hospital" title={tr('No lab in the app here yet', 'এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই')} text={tr('Know a diagnostic centre? They can join from "My work".', 'কোনো ডায়াগনস্টিক সেন্টার চিনলে "আমার কাজ" থেকে যোগ হতে বলুন।')}>
        {owner}
      </EmptyState>
    );
  };

  const list = (
    <div className="list-inner">
      <ServiceTabs kind={kind} sub={kind === 'pharmacy' ? 'list' : undefined} />
      <FilterBar
        areaLabel={area ? areaLabel(area.district, area.upazilaId) : ''}
        onArea={() => setShowArea(true)}
        filterCount={filterCount}
        onFilter={kind === 'lab' ? undefined : () => setShowFilter(true)}
      >
        <ActiveChips items={chips} />
      </FilterBar>
      {kind === 'ambulance' ? <Nearest list={items as Ambulance[]} id={q.data?.nearestId} byArea={!!origin?.byArea} lists={lists} /> : null}
      {kind === 'ambulance' ? <Emergency999 /> : null}
      {kind === 'bloodbank' ? (
        <p className="t-body-s muted">{tr('Buying or selling blood is illegal; only processing costs apply.', 'রক্ত কেনা-বেচা অবৈধ; শুধু পরীক্ষা ও প্রসেসিং খরচ লাগে।')}</p>
      ) : null}
      {!area ? (
        <EmptyState icon="location_on" title={tr('Choose area', 'এলাকা বেছে নিন')}>
          <Button onClick={() => setShowArea(true)}>{tr('Choose area', 'এলাকা বেছে নিন')}</Button>
        </EmptyState>
      ) : q.isLoading ? (
        <SkeletonCards n={4} h={150} />
      ) : q.isError ? (
        <ErrorBox error={q.error.message} onRetry={() => q.refetch()} />
      ) : (
        <>
          {entries.length ? <div className="grid-cards">{entries.map(card)}</div> : empty()}
          {kind === 'ambulance' && busy.length ? (
            <BusyBlock open={busyOpen || !entries.length} toggle={() => setBusyOpen(!(busyOpen || !entries.length))} count={busy.length}>
              <div className="grid-cards">{busyEntries.map(card)}</div>
            </BusyBlock>
          ) : null}
        </>
      )}
    </div>
  );

  // ---------- বিস্তারিত ----------
  let detail = null;
  if (id) {
    const raw = items.find((x) => x.id === id) ?? busy.find((x) => x.id === id) ?? recall<any>(kind, id);
    if (raw) {
      const e = toEntry(raw);
      detail = kind === 'lab'
        ? <LabEntryDetail key={raw.id} lab={raw as Lab} backTo={listPath} />
        : (
          <EntryDetail key={raw.id} e={e} backTo={listPath} onRate={e.canRate ? rateGate(e) : undefined} onReport={e.reportable ? reportGate(e) : undefined}>
            {/* ফার্মেসির পাতা থেকেই প্রেসক্রিপশন পাঠানো */}
            {kind === 'pharmacy' ? <RxSendFromPlace pharmacy={raw as Pharmacy} /> : null}
          </EntryDetail>
        );
    } else if (area && (q.isLoading || q.isFetching)) {
      detail = (
        <DetailPane title={kindLabel(kind)} backTo={listPath}>
          <Loading />
        </DetailPane>
      );
    } else {
      detail = (
        <DetailPane title={kindLabel(kind)} backTo={listPath}>
          <EmptyState icon={kindIcon(kind)} title={tr('Not found', 'পাওয়া যায়নি')} />
        </DetailPane>
      );
    }
  }

  const placeholder = (
    <Summary kind={kind} area={area ? areaLabel(area.district, area.upazilaId) : ''} items={items} busyCount={busy.length} loading={!!area && q.isLoading} />
  );

  return (
    <>
      <ListDetail list={list} detail={detail} placeholder={placeholder} />
      {showArea ? (
        <AreaPicker
          value={area ? { district: area.district, upazilaId: null } : null}
          onClose={() => setShowArea(false)}
          onPicked={(v) => {
            setArea({ district: v.district, upazilaId: v.upazilaId ?? null, unionId: null });
            setShowArea(false);
          }}
        />
      ) : null}
      {showFilter ? (
        <FilterSheet onClose={() => setShowFilter(false)} onClear={clearFilters}>
          {kind === 'ambulance' ? (
            <div className="col gap-6">
              <span className="t-title-s">{tr('Ambulance type', 'অ্যাম্বুলেন্সের ধরন')}</span>
              <ChoiceChips
                options={ambulanceTypes(lists).map((t) => ({ value: t, label: ambulanceTypeLabel(lists, t) }))}
                value={type}
                onChange={(v: string) => setParam('type', v === type ? null : v)}
              />
            </div>
          ) : null}
          {kind === 'pharmacy' ? <Switch label={tr('Open now only', 'শুধু এখন খোলা')} checked={openNow} onChange={(v) => setParam('open', v ? '1' : null)} /> : null}
          {kind === 'bloodbank' || kind === 'pharmacy' ? <Switch label={tr('Open 24 hours only', 'শুধু ২৪ ঘণ্টা খোলা')} checked={open24} onChange={(v) => setParam('h24', v ? '1' : null)} /> : null}
        </FilterSheet>
      ) : null}
      {rating ? <RatingDialog kind={kind} id={rating.id} name={rating.name} aspects={rating.aspects} onClose={() => setRating(null)} /> : null}
      {reporting ? <ReportDialog kind={kind} id={reporting} onClose={() => setReporting(null)} /> : null}
    </>
  );
}

/** তালিকার উপরে বড় বাটন: সবচেয়ে কাছের খালি গাড়িতে এক চাপে ফোন — নিচের লাইনে নাম, দূরত্ব, ধরন */
function Nearest({ list, id, byArea, lists }: { list: Ambulance[]; id?: string | null; byArea: boolean; lists: ReturnType<typeof useCatalog> }) {
  const a = id ? list.find((x) => x.id === id) : undefined;
  if (!a) return null;
  // এলাকা ধরে হলে দূরত্ব নয় (জানা নেই), শুধু "এলাকা ধরে"
  const sub = [a.name, byArea ? tr('By area', 'এলাকা ধরে') : ambulanceKm(a), a.types.map((t) => ambulanceTypeLabel(lists, t)).join(', ')]
    .filter(Boolean)
    .join(' · ');
  return <NearestCall id={a.id} phone={a.phone} sub={sub} />;
}

/** ট্রিপে থাকা গাড়ি: আলাদা ভাঁজ করা অংশ; খালি গাড়ি না থাকলে নিজে থেকে খোলা */
function BusyBlock({ open, toggle, count, children }: { open: boolean; toggle: () => void; count: number; children: React.ReactNode }) {
  return (
    <div className="col gap-12">
      <button type="button" className="services-fold" aria-expanded={open} onClick={toggle}>
        <span className="t-title-s grow">{tr(`On a trip now (${count})`, `এখন ট্রিপে আছে (${num(count)})`)}</span>
        <Icon name="expand_more_round" className={open ? 'flip' : undefined} />
      </button>
      {open ? (
        <>
          <p className="t-body-s muted">{tr("They're busy — call only if you can wait, to ask when they'll be free.", 'এরা এখন ব্যস্ত — অপেক্ষা করতে পারলে ফোন করে জেনে নিন কখন খালি হবে।')}</p>
          {children}
        </>
      ) : null}
    </div>
  );
}

/** বড় পর্দায় কিছু বাছা না থাকলে: ছোট সারাংশ আর দরকারি এক-দুটো কাজ */
function Summary({ kind, area, items, busyCount, loading }: { kind: Kind; area: string; items: any[]; busyCount: number; loading: boolean }) {
  const stats: [string, number][] =
    kind === 'ambulance'
      ? [[tr('Available', 'খালি আছে'), items.filter((a) => a.available).length], [tr('Call to check', 'ফোনে জেনে নিন'), items.filter((a) => !a.available).length], [tr('On a trip', 'ট্রিপে'), busyCount]]
      : kind === 'lab'
        ? [[tr('Labs', 'প্যাথলজি'), items.length], [tr('With prices', 'দাম দেওয়া'), items.filter((l) => l.prices).length], [tr('Sample from home', 'বাসা থেকে নমুনা নেয়'), items.filter((l) => l.homeCollection).length]]
        : [[tr('Open', 'খোলা'), items.filter((x) => x.openNow).length], [tr('Total', 'মোট'), items.length]];
  return (
    <div className="services-ph">
      <div className="row gap-12">
        <Avatar icon={kindIcon(kind)} tint={kind === 'bloodbank' ? 'error' : undefined} size={48} />
        <div className="col gap-2 grow">
          <h2 className="t-title-l">{kindLabel(kind)}</h2>
          {area ? <span className="t-body-m muted ellipsis">{area}</span> : null}
        </div>
      </div>
      {area && !loading ? (
        <div className="services-stats">
          {stats.map(([l, v]) => (
            <div key={l} className="stat">
              <span className="v">{num(v)}</span>
              <span className="l">{l}</span>
            </div>
          ))}
        </div>
      ) : null}
      <p className="t-body-m muted">{tr('Pick one from the list to see details.', 'বিস্তারিত দেখতে তালিকা থেকে একটা বেছে নিন।')}</p>
      <div className="row wrap">
        {kind === 'ambulance' ? (
          <a className="btn danger square has-icon" href="tel:999">
            <Icon name="call" />
            {tr('Call 999', '৯৯৯-এ ফোন করুন')}
          </a>
        ) : null}
        {kind === 'pharmacy' ? (
          <Link className="btn tonal square has-icon" to="/services/pharmacy/medicine">
            <Icon name="search" />
            {tr('Find a medicine', 'ওষুধ খুঁজুন')}
          </Link>
        ) : null}
        {kind === 'lab' ? (
          <Link className="btn tonal square has-icon" to="/prescriptions">
            <Icon name="list_alt" />
            {tr('My prescriptions', 'আমার প্রেসক্রিপশন')}
          </Link>
        ) : null}
      </div>
    </div>
  );
}
