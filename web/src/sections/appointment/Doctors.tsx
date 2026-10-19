// অ্যাপয়েন্টমেন্ট: ডাক্তার / পুষ্টিবিদ / ফিটনেস ট্রেইনারের তালিকা আর বিস্তারিত (অ্যাপের AppointmentTab, ProviderList,
// ProviderCard, ProviderDetailDialog, BookDialog)। বাছা মানুষ URL-এ: /appointment/doctor/:id?t=doctor
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Button, cx, ChoiceChips, Dialog, DirectoryCard, EmptyState, ErrorBox, FilterBar, IconButton, IconLine, InfoText, PhoneLine, PillTabs, SkeletonCards, Switch, TextArea, ErrorText, toast,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useFilterArea } from '@/ui/AreaPicker';
import { DetailPane, ListDetail } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { formatDate, dateInputValue, dhakaHourMinute, fromDateInput } from '@/lib/format';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel, upazila } from '@/lib/geo';
import {
  CHAMBER_TYPES, DAY_MS, FEE_STEPS, NO_DOCTOR_FILTERS, badgeIcon, dayShort, slotsOf, badgeLabel, canBook, chamberArea, chamberLabel, chamberOpenOn, chamberPlace, chamberShort, chamberState,
  chamberTimeText, coverageText, feeShort, feeText, homeServiceLabel, nextSlotText, parseProvider, shortTime, ratingLine, specialtiesOf, specialtyLabel, timesText, today, useCatalog,
  type Chamber, type DoctorFilters, type FamilyMember, type Hospital, type Provider, type SpecialtyFacet,
} from './model';
import { AreaSheet, DetailBlock, DetailHead, EmergencyNotice, FilterSheet, HospitalPickerDialog, ReasonReportDialog, useAreasReady, type AreaSel } from './shared';

const TAB_ICON: Record<string, string> = { doctor: 'stethoscope', nutritionist: 'restaurant', trainer: 'fitness_center' };
const tabLabel = (t: string) => (t === 'nutritionist' ? tr('Nutritionist', 'পুষ্টিবিদ') : t === 'trainer' ? tr('Fitness trainer', 'ফিটনেস ট্রেইনার') : tr('Doctor', 'ডাক্তার'));

/** শেষবারের ছাঁকনি (ধরনপ্রতি, এই ব্রাউজারে) — এলাকা useFilterArea-তে আলাদা, আগের মতো */
interface SavedFilters extends DoctorFilters { hospital: Hospital | null; homeOnly: boolean }
const NO_FILTERS: SavedFilters = { ...NO_DOCTOR_FILTERS, hospital: null, homeOnly: false };
const filterKey = (t: string) => `lf.doctorFilters.${t}`;
function loadFilters(t: string): SavedFilters {
  const v = store.getJson<Partial<SavedFilters>>(filterKey(t));
  if (!v || typeof v !== 'object') return NO_FILTERS;
  return {
    day: v.day === 'today' || v.day === 'tomorrow' ? v.day : null,
    openNow: v.openNow === true,
    specialty: typeof v.specialty === 'string' && v.specialty ? v.specialty : null,
    maxFee: typeof v.maxFee === 'number' && FEE_STEPS.includes(v.maxFee) ? v.maxFee : null,
    hospitalQ: typeof v.hospitalQ === 'string' ? v.hospitalQ.slice(0, 60) : '',
    gender: v.gender === 'male' || v.gender === 'female' ? v.gender : null,
    hospital: v.hospital && typeof v.hospital === 'object' && typeof v.hospital.id === 'string' ? v.hospital : null,
    homeOnly: v.homeOnly === true && t === 'doctor',
  };
}
const feeLabel = (n: number) => tr(`Up to ৳${n.toLocaleString('en-IN')}`, `৳${num(n.toLocaleString('en-IN'))} পর্যন্ত`);
const genderLabel = (g: string) => (g === 'female' ? tr('Female doctor', 'নারী ডাক্তার') : tr('Male doctor', 'পুরুষ ডাক্তার'));
const dayLabel = (d: string) => (d === 'tomorrow' ? tr('Tomorrow', 'আগামীকাল') : tr('Sitting today', 'আজ বসছেন'));

/** লেখা থামার পরে (হাসপাতালের নাম লিখতে লিখতে প্রতিটা অক্ষরে সার্ভারে নয়) */
function useDebounced<T>(v: T, ms: number): T {
  const [d, setD] = useState(v);
  useEffect(() => {
    const t = setTimeout(() => setD(v), ms);
    return () => clearTimeout(t);
  }, [v, ms]);
  return d;
}

export function DoctorsPage({ detailId }: { detailId: string | null }) {
  usePageTitle(tr('Appointment', 'অ্যাপয়েন্টমেন্ট'));
  useCatalog();
  useAreasReady();
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const { uid, guest } = useSession();
  const type = CHAMBER_TYPES.includes(sp.get('t') ?? '') ? (sp.get('t') as string) : 'doctor';
  const [fa, setFa] = useFilterArea();
  const [area, setArea] = useState<AreaSel | null>(null);
  const cur: AreaSel = area ?? { district: fa?.district ?? null, upazilaId: fa?.upazilaId ?? null, unionId: fa?.unionId ?? null };
  // ২.৩৩.০: আজ/আগামীকাল, এখন চেম্বারে, বিশেষজ্ঞতা, ফি, হাসপাতাল, লিঙ্গ — শেষবারেরটা মনে থাকে (অ্যাপেও)
  const [saved, setSaved] = useState<Record<string, SavedFilters>>({});
  const f = saved[type] ?? loadFilters(type);
  const setF = (patch: Partial<SavedFilters>) => {
    const n = { ...f, ...patch };
    store.setJson(filterKey(type), n);
    setSaved((m) => ({ ...m, [type]: n }));
  };
  const clearF = () => setF(NO_FILTERS);
  const { hospital, specialty, homeOnly } = f;
  const hq = useDebounced(f.hospitalQ.trim(), 400);
  const [query, setQuery] = useState('');
  const [showArea, setShowArea] = useState(false);
  const [showFilter, setShowFilter] = useState(false);
  const [pickHospital, setPickHospital] = useState(false);
  const [info, setInfo] = useState<string | null>(null);

  const body: Record<string, unknown> = { type, homeVisit: homeOnly };
  if (hospital) body.hospitalId = hospital.id;
  else {
    if (cur.district) body.district = cur.district;
    if (cur.upazilaId != null) body.upazilaId = cur.upazilaId;
    if (cur.unionId != null) body.unionId = cur.unionId;
  }
  if (specialty) body.specialty = specialty;
  if (f.day) body.day = f.day;
  if (f.openNow) body.openNow = true;
  if (f.maxFee) body.maxFee = f.maxFee;
  if (hq.length >= 2) body.hospitalQ = hq;
  if (f.gender) body.gender = f.gender;
  const q = useCall<{ items: any[]; facets?: { specialties: SpecialtyFacet[]; gender: boolean } }>('providers', body);
  const list = useMemo(() => (q.data?.items ?? []).map((o) => ({ p: parseProvider(o), matched: Array.isArray(o?.matched) ? (o.matched as number[]) : null })), [q.data]);
  // বিশেষজ্ঞতার চিপ: সার্ভার যা বলে (এই এলাকায় যেগুলো আছে); নতুন তালিকা আসার সময় আগেরটা থাকে
  const lastFacets = useRef<Record<string, { specialties: SpecialtyFacet[]; gender: boolean }>>({});
  if (q.data?.facets) lastFacets.current[type] = q.data.facets;
  const facets = q.data?.facets ?? lastFacets.current[type] ?? null;
  const t = query.trim().toLowerCase();
  const shown = list
    .filter(({ p }) => p.uid !== uid)
    .filter(({ p }) => !t || p.name.toLowerCase().includes(t) || p.degrees.toLowerCase().includes(t) || p.chambers.some((c) => chamberLabel(c).toLowerCase().includes(t)));

  // সরাসরি খোলা লিংক: এখনকার তালিকায় না থাকলে পুরো দেশের তালিকা থেকে
  const inList = detailId ? list.find(({ p }) => p.uid === detailId)?.p ?? null : null;
  const fb = useCall<{ items: any[] }>('providers', { type, homeVisit: false }, { enabled: !!detailId && !inList && !q.isLoading });
  const selected = inList ?? (detailId ? (fb.data?.items ?? []).map(parseProvider).find((p) => p.uid === detailId) ?? null : null);

  const qs = sp.toString();
  const listPath = '/appointment' + (qs ? `?${qs}` : '');
  const open = (id: string) => nav(`/appointment/doctor/${id}${qs ? `?${qs}` : ''}`);
  const active = [
    ...(f.day ? [{ label: dayLabel(f.day), onRemove: () => setF({ day: null }) }] : []),
    ...(f.openNow ? [{ label: tr('In chamber now', 'এখন চেম্বারে'), onRemove: () => setF({ openNow: false }) }] : []),
    ...(specialty ? [{ label: specialtyLabel(specialty), onRemove: () => setF({ specialty: null }) }] : []),
    ...(f.maxFee ? [{ label: feeLabel(f.maxFee), onRemove: () => setF({ maxFee: null }) }] : []),
    ...(hospital ? [{ label: chamberLabel({ hospitalName: hospital.nameEn, hospitalNameBn: hospital.nameBn }), onRemove: () => setF({ hospital: null }) }] : []),
    ...(f.hospitalQ.trim() ? [{ label: tr('Hospital: ', 'হাসপাতাল: ') + f.hospitalQ.trim(), onRemove: () => setF({ hospitalQ: '' }) }] : []),
    ...(f.gender ? [{ label: genderLabel(f.gender), onRemove: () => setF({ gender: null }) }] : []),
    ...(homeOnly ? [{ label: tr('Home visit', 'বাসায় যান'), onRemove: () => setF({ homeOnly: false }) }] : []),
  ];
  // এক চাপের ছাঁকনি (চালু না থাকলে): আজ · আগামীকাল · এখন চেম্বারে · ৳৫০০ পর্যন্ত · বেশি থাকা ৩টা বিশেষজ্ঞতা
  const quick = [
    ...(!f.day ? [{ key: 'today', label: dayLabel('today'), on: () => setF({ day: 'today' }) }, { key: 'tomorrow', label: dayLabel('tomorrow'), on: () => setF({ day: 'tomorrow' }) }] : []),
    ...(!f.openNow ? [{ key: 'now', label: tr('In chamber now', 'এখন চেম্বারে'), on: () => setF({ openNow: true }) }] : []),
    ...(!f.maxFee ? [{ key: 'fee', label: feeLabel(500), on: () => setF({ maxFee: 500 }) }] : []),
    ...(!specialty && facets ? facets.specialties.slice(0, 3).map((x) => ({ key: 'sp-' + x.code, label: specialtyLabel(x.code), on: () => setF({ specialty: x.code }) })) : []),
  ];
  const filterCount = active.length;
  const isEmptyFilter = !cur.district && !active.length;

  const listPart = (
    <div className="list-inner appt-pre">
      <div className="row appt-title">
        <h1 className="t-headline grow appt-h1">{tr('Appointment', 'অ্যাপয়েন্টমেন্ট')}</h1>
        <div className="row appt-title-end">
        {!guest ? (
          <Link className="btn text small has-icon appt-my-link" to="/my-appointments" aria-label={tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')} title={tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')}>
            <Icon name="event_outline" />
            <span className="appt-wide-only">{tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')}</span>
          </Link>
        ) : null}
        <Link className="appt-home-btn" to="/appointment/home-care">
          <Icon name="home_health" />
          {tr('Home care', 'বাসায় সেবা')}
        </Link>
        </div>
      </div>
      <div className="appt-bleed">
        <PillTabs
          tabs={CHAMBER_TYPES.map((k) => ({ key: k, label: tabLabel(k), icon: TAB_ICON[k] }))}
          value={type}
          onChange={(k) => {
            // ধরনপ্রতি নিজের শেষ ছাঁকনি (store-এ) — বদলালে সেটা ফিরে আসে
            setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
            if (detailId) nav(k === 'doctor' ? '/appointment' : `/appointment?t=${k}`, { replace: true });
          }}
        />
      </div>
      <FilterBar
        areaLabel={hospital ? areaLabel(hospital.district, hospital.upazilaId) : cur.district ? areaLabel(cur.district, cur.upazilaId, cur.unionId) : tr('All districts', 'সব জেলা')}
        onArea={() => setShowArea(true)}
        filterCount={filterCount}
        onFilter={() => setShowFilter(true)}
        search={query}
        onSearch={setQuery}
      >
        {active.length || quick.length ? (
          <div className="chips scroll appt-qchips" role="group" aria-label={tr('Filters', 'ছাঁকনি')}>
            {active.map((a) => (
              <button key={'a-' + a.label} type="button" className="chip on" onClick={a.onRemove} aria-label={tr('Remove', 'সরান') + ': ' + a.label}>
                {a.label}
                <Icon name="close" />
              </button>
            ))}
            {quick.map((x) => (
              <button key={x.key} type="button" className="chip" onClick={x.on}>
                {x.label}
              </button>
            ))}
          </div>
        ) : null}
      </FilterBar>
      <InfoText>{info}</InfoText>
      {q.isLoading ? (
        <SkeletonCards n={4} h={150} />
      ) : q.error ? (
        <ErrorBox error={errorText(q.error)} onRetry={() => q.refetch()} />
      ) : !shown.length ? (
        <EmptyState
          icon={badgeIcon(type)}
          title={type === 'nutritionist' ? tr('No nutritionists found', 'কোনো পুষ্টিবিদ পাওয়া যায়নি') : type === 'trainer' ? tr('No fitness trainers found', 'কোনো ফিটনেস ট্রেইনার পাওয়া যায়নি') : tr('No doctors found', 'কোনো ডাক্তার পাওয়া যায়নি')}
          text={tr('Try a wider area or remove a filter.', 'বড় এলাকা দিয়ে বা কোনো ফিল্টার সরিয়ে দেখুন।')}
        >
          {active.length ? <Button variant="text" onClick={clearF}>{tr('Remove all filters', 'সব ছাঁকনি সরান')}</Button> : null}
          {!isEmptyFilter && !active.length ? (
            <Button
              variant="text"
              onClick={() => {
                setArea({ district: null, upazilaId: null, unionId: null });
                clearF();
              }}
            >
              {tr('Search everywhere', 'সব জায়গায় খুঁজুন')}
            </Button>
          ) : null}
        </EmptyState>
      ) : (
        shown.map(({ p, matched }) => <ProviderCard key={p.uid} p={p} matched={matched} area={cur} hospital={hospital} selected={p.uid === detailId} onClick={() => open(p.uid)} />)
      )}
    </div>
  );

  const detail = detailId ? (
    selected ? (
      <DetailPane title={selected.name} backTo={listPath} hideTitleWide>
        <ProviderDetail p={selected} onClose={() => nav(listPath)} onBooked={(m) => setInfo(m)} />
      </DetailPane>
    ) : (
      <DetailPane title={tr('Doctor', 'ডাক্তার')} backTo={listPath}>
        {fb.isLoading || q.isLoading ? <SkeletonCards n={2} h={160} /> : <EmptyState icon="stethoscope" title={tr('Not found', 'পাওয়া যায়নি')} />}
      </DetailPane>
    )
  ) : null;

  const spOptions = facets ? facets.specialties.map((x) => x.code) : specialtiesOf(type);
  const spCount = (code: string) => facets?.specialties.find((x) => x.code === code)?.n;

  return (
    <>
      <ListDetail list={listPart} detail={detail} placeholder={<EmptyState icon={badgeIcon(type)} title={tabLabel(type)} />} />
      {showArea ? (
        <AreaSheet
          value={hospital ? { district: hospital.district, upazilaId: null, unionId: null } : cur}
          allowAll
          withUnions
          onPick={(v) => {
            // এলাকা বদলালে আগের হাসপাতাল আর মেলে না
            setF({ hospital: null });
            setArea(v);
            if (v.district) setFa({ district: v.district, upazilaId: v.upazilaId, unionId: v.unionId });
            setShowArea(false);
          }}
          onClose={() => setShowArea(false)}
        />
      ) : null}
      {showFilter ? (
        <FilterSheet title={tr('Filter', 'ফিল্টার')} onClose={() => setShowFilter(false)} onClear={active.length ? clearF : undefined}>
          <div className="col gap-6">
            <span className="t-title-s">{tr('Day', 'দিন')}</span>
            <ChoiceChips
              options={[{ value: '', label: tr('Any day', 'যেকোনো দিন') }, { value: 'today', label: dayLabel('today') }, { value: 'tomorrow', label: dayLabel('tomorrow') }]}
              value={f.day ?? ''}
              onChange={(v: string) => setF({ day: v === 'today' || v === 'tomorrow' ? v : null })}
            />
          </div>
          <Switch label={tr('In chamber now', 'এখন চেম্বারে')} sub={tr('Sitting at the chamber right now', 'এই মুহূর্তে চেম্বারে বসে আছেন')} checked={f.openNow} onChange={(v) => setF({ openNow: v })} />
          <SpecialtyPicker options={spOptions} count={spCount} value={specialty} onChange={(v) => setF({ specialty: v })} />
          <div className="col gap-6">
            <span className="t-title-s">{tr('Hospital / chamber', 'হাসপাতাল / চেম্বার')}</span>
            <input
              className="input"
              value={f.hospitalQ}
              onChange={(e) => setF({ hospitalQ: e.target.value.slice(0, 60) })}
              placeholder={tr('Type a hospital name', 'হাসপাতালের নাম লিখুন')}
              aria-label={tr('Hospital name', 'হাসপাতালের নাম')}
            />
            <div className="row wrap">
              <Button variant="outline" icon="local_hospital" onClick={() => setPickHospital(true)}>
                {hospital ? chamberLabel({ hospitalName: hospital.nameEn, hospitalNameBn: hospital.nameBn }) : tr('Choose from list', 'তালিকা থেকে বেছে নিন')}
              </Button>
              {hospital ? <Button variant="text" onClick={() => setF({ hospital: null })}>{tr('Remove', 'সরান')}</Button> : null}
            </div>
          </div>
          <div className="col gap-6">
            <span className="t-title-s">{tr('Fee', 'ফি')}</span>
            <ChoiceChips
              options={[{ value: 0, label: tr('Any', 'যেকোনো') }, ...FEE_STEPS.map((n) => ({ value: n, label: feeLabel(n) }))]}
              value={f.maxFee ?? 0}
              onChange={(v: number) => setF({ maxFee: v || null })}
            />
          </div>
          {facets?.gender || f.gender ? (
            <div className="col gap-6">
              <span className="t-title-s">{tr('Doctor', 'ডাক্তার')}</span>
              <ChoiceChips
                options={[{ value: '', label: tr('Any', 'যেকোনো') }, { value: 'female', label: genderLabel('female') }, { value: 'male', label: genderLabel('male') }]}
                value={f.gender ?? ''}
                onChange={(v: string) => setF({ gender: v === 'male' || v === 'female' ? v : null })}
              />
            </div>
          ) : null}
          {type === 'doctor' ? <Switch label={tr('Home visit', 'বাসায় যান')} checked={homeOnly} onChange={(v) => setF({ homeOnly: v })} /> : null}
        </FilterSheet>
      ) : null}
      {pickHospital ? (
        <HospitalPickerDialog
          initialDistrict={cur.district}
          initialUpazilaId={cur.upazilaId}
          onPick={(h) => {
            setF({ hospital: h });
            setArea({ district: h.district, upazilaId: null, unionId: null });
            setPickHospital(false);
          }}
          onClose={() => setPickHospital(false)}
        />
      ) : null}
    </>
  );
}

/** বিশেষজ্ঞতা: তালিকায় যেগুলো আছে (সংখ্যাসহ), বেশি হলে খোঁজার ঘর */
function SpecialtyPicker({ options, count, value, onChange }: { options: string[]; count: (c: string) => number | undefined; value: string | null; onChange: (v: string | null) => void }) {
  const [q, setQ] = useState('');
  const t = q.trim().toLowerCase();
  const list = (value && !options.includes(value) ? [value, ...options] : options).filter((c) => !t || specialtyLabel(c).toLowerCase().includes(t) || c.includes(t));
  return (
    <div className="col gap-6">
      <span className="t-title-s">{tr('Specialty', 'বিশেষজ্ঞতা')}</span>
      {options.length > 8 ? (
        <input className="input" value={q} onChange={(e) => setQ(e.target.value.slice(0, 40))} placeholder={tr('Search specialty', 'বিশেষজ্ঞতা খুঁজুন')} aria-label={tr('Search specialty', 'বিশেষজ্ঞতা খুঁজুন')} />
      ) : null}
      {list.length ? (
        <ChoiceChips
          options={list.map((c) => ({ value: c, label: specialtyLabel(c) + (count(c) ? ` (${num(count(c)!)})` : '') }))}
          value={value}
          onChange={(v: string) => onChange(v === value ? null : v)}
        />
      ) : (
        <span className="t-body-s muted">{tr('No match', 'মেলেনি')}</span>
      )}
    </div>
  );
}

/** তালিকার কার্ডের অবস্থা: এখন চেম্বারে / আজ ৪টা (সবুজ) — অন্য দিনে কিছু নয় */
function cardStatus(c: Chamber | undefined): { text: string; good: boolean } | null {
  if (!c) return null;
  const s = chamberState(c);
  if (s.kind === 'now') return { text: tr('In chamber now', 'এখন চেম্বারে'), good: true };
  if (s.kind === 'today') return { text: s.start ? tr('Today ', 'আজ ') + shortTime(s.start) : tr('Today', 'আজ'), good: true };
  return null;
}

function ProviderCard({ p, matched, area, hospital, selected, onClick }: { p: Provider; matched: number[] | null; area: AreaSel; hospital: Hospital | null; selected: boolean; onClick: () => void }) {
  // ছাঁকনিতে মেলা চেম্বার (সার্ভারের matched) আগে, নইলে এলাকার সাথে মেলা চেম্বার
  const ch = (matched?.length ? p.chambers[matched[0]] : undefined)
    ?? p.chambers.find((c) => (!hospital || c.hospitalId === hospital.id) && (!area.district || c.district === area.district) && (area.upazilaId == null || c.upazilaId === area.upazilaId)) ?? p.chambers[0];
  return (
    <DirectoryCard
      title={p.name}
      icon={badgeIcon(p.type)}
      photo={p.photo || null}
      subtitle={[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')}
      status={cardStatus(ch)}
      meta={ratingLine(p.rating, p.ratingCount)}
      details={ch ? [['local_hospital', chamberLabel(ch)], ['schedule', chamberTimeText(ch)], ['payments', feeText(ch.noFee, ch.fee)]] : []}
      tags={[canBook(p) ? tr('Appointment in app', 'অ্যাপে অ্যাপয়েন্টমেন্ট') : null, p.homeVisit?.enabled ? tr('Home visit', 'বাসায় যান') : null].filter(Boolean) as string[]}
      note={p.chambers.length > 1 ? tr(`+${p.chambers.length - 1} more chamber(s)`, `আরও ${num(p.chambers.length - 1)}টা চেম্বার`) : null}
      onClick={onClick}
      selected={selected}
    />
  );
}

/** ডাক্তার/নার্সের বিস্তারিত: সব চেম্বার, সিরিয়ালের ফোন, অ্যাপয়েন্টমেন্ট, হোম ভিজিট (preview: নিজে দেখছেন, বাটন কাজ করে না) */
export function ProviderDetail({ p, onClose, onBooked, preview }: { p: Provider; onClose?: () => void; onBooked?: (msg: string) => void; preview?: boolean }) {
  const two = useTwoPane();
  const { uid, guest, requireLogin } = useSession();
  const [booking, setBooking] = useState<number | null>(null);
  const [reporting, setReporting] = useState(false);
  const [reported, setReported] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const hv = p.homeVisit && p.homeVisit.enabled && p.homeVisit.phone.trim() ? p.homeVisit : null;
  // ২.২৭.০ নিচের আটকানো বার (অ্যাপের DetailSheet-এর মতো): অ্যাপয়েন্টমেন্ট নেওয়া যায় এমন চেম্বার একটা হলে সরাসরি, কয়েকটা হলে
  // কোন চেম্বার জিজ্ঞেস করে; সিরিয়ালের ফোন সব চেম্বারে একই হলে পাশে "ফোন" — তখন চেম্বারের ভেতরে আর আলাদা নেই
  const bookable = p.appointments ? p.chambers.map((c, i) => (c.appointments ? i : -1)).filter((i) => i >= 0) : [];
  const serialPhones = [...new Set(p.chambers.map((c) => c.serialPhone).filter(Boolean))];
  const pinnedCall = serialPhones.length === 1 ? serialPhones[0] : null;
  const [choosing, setChoosing] = useState(false);
  const book = (i: number) => !preview && requireLogin(tr('Log in to request an appointment', 'অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন'), () => setBooking(i));
  const ups = hv ? hv.upazilaIds.map((id) => upazila(id)).filter(Boolean).map((u) => (isBangla() ? u!.bn : u!.en)) : [];

  return (
    <>
      <div className="row top">
        <div className="grow">
          <DetailHead title={p.name} icon={badgeIcon(p.type)} photo={p.photo || null} subtitle={p.specialties.map(specialtyLabel).join(' · ')} meta={ratingLine(p.rating, p.ratingCount)} />
        </div>
        {two && onClose && !preview ? <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={onClose} /> : null}
      </div>
      {/* ২.৩৩.০: নামের নিচেই চেম্বার বাছাই — চাপলে সেই চেম্বারেই অ্যাপয়েন্টমেন্ট ("কোন চেম্বারে?" ছাড়া) */}
      <ChamberPicker p={p} bookable={bookable} onBook={book} preview={preview} />
      <InfoText>{done}</InfoText>
      {done ? <Link className="btn tonal small has-icon" to="/my-appointments" style={{ alignSelf: 'flex-start' }}><Icon name="event_outline" />{tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')}</Link> : null}
      {p.chambers.map((c, i) => (
        <div key={i} className="col">
          <hr className="divider" />
          <DetailBlock
            title={chamberLabel(c)}
            lines={[
              ['location_on', chamberArea(c)],
              ['info', c.room ? tr('Room: ', 'রুম: ') + c.room : ''],
              ['schedule', chamberTimeText(c)],
              ['payments', feeText(c.noFee, c.fee)],
            ]}
          >
            {bookable.length > 1 && bookable.includes(i) ? (
              <Button square icon="calendar_month" onClick={() => book(i)}>
                {tr('Appointment at this chamber', 'এই চেম্বারে অ্যাপয়েন্টমেন্ট')}
              </Button>
            ) : null}
          </DetailBlock>
          {c.serialPhone && !pinnedCall ? (
            <div className="appt-phone">
              <span className="t-label-m muted">{tr('Call for serial', 'সিরিয়ালের জন্য ফোন')}</span>
              {preview ? <span className="t-title-m num">{num(c.serialPhone)}</span> : <PhoneLine phone={c.serialPhone} />}
            </div>
          ) : null}
        </div>
      ))}
      {hv ? (
        <div className="col">
          <hr className="divider" />
          <DetailBlock
            title={tr('Home visit', 'বাসায় গিয়ে সেবা')}
            lines={[
              ['medical_services', hv.services.map(homeServiceLabel).join(' · ')],
              ['location_on', coverageText(hv.radiusKm, ups)],
              ['schedule', timesText(hv.times, hv.schedule)],
              ['payments', hv.noFee ? tr('No fee', 'ফি নেই') : hv.fee ? tr('Fee: ', 'ফি: ') + hv.fee : ''],
            ]}
          />
          <div className="appt-phone">
            <span className="t-label-m muted">{tr('Call to visit home', 'বাসায় আসতে ফোন')}</span>
            {preview ? (
              <span className="t-title-m num">{num(hv.phone)}</span>
            ) : (
              <PhoneLine phone={hv.phone} onCall={() => { if (!guest) call('home_called', { uid: p.uid }).catch(() => {}); }} />
            )}
          </div>
        </div>
      ) : null}
      <hr className="divider" />
      <DetailBlock title={tr('About', 'পরিচিতি')} lines={[['school', p.degrees], ['badge', badgeLabel(p.type)]]} />
      {preview ? <p className="t-body-s muted">{tr('This is how patients see you. Save to update it.', 'রোগীরা আপনাকে এভাবেই দেখবেন। বদল দেখাতে সেভ করুন।')}</p> : null}
      {reported ? <InfoText>{tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।')}</InfoText> : null}
      <EmergencyNotice k="doctor" />
      {!preview && !guest && p.uid !== uid && !reported && !(bookable.length || pinnedCall) ? (
        <div className="row">
          <span className="grow" />
          <Button variant="text" icon="flag" onClick={() => setReporting(true)}>{tr('Report', 'রিপোর্ট')}</Button>
        </div>
      ) : null}
      {bookable.length || pinnedCall ? (
        <div className="detail-bar">
          <div className="detail-bar-row">
            {pinnedCall ? (
              preview ? (
                <span className={cx('btn outline square has-icon', !bookable.length && 'solo')}><Icon name="call" />{num(pinnedCall)}</span>
              ) : (
                <a className={cx('btn outline square has-icon', !bookable.length && 'solo')} href={`tel:${pinnedCall}`}>
                  <Icon name="call" />
                  {tr('Call for serial', 'সিরিয়ালের ফোন')}
                </a>
              )
            ) : null}
            {bookable.length ? (
              <Button square icon="calendar_month" className={cx(!pinnedCall && 'solo')} onClick={() => (bookable.length === 1 ? book(bookable[0]) : !preview && setChoosing(true))}>
                {tr('Appointment', 'অ্যাপয়েন্টমেন্ট')}
              </Button>
            ) : null}
          </div>
          {pinnedCall || (!preview && !guest && p.uid !== uid && !reported) ? (
            <div className="row services-acts">
              {pinnedCall ? <span className="t-body-m muted num">{num(pinnedCall)}</span> : null}
              <span className="grow" />
              {!preview && !guest && p.uid !== uid && !reported ? <Button variant="text" icon="flag" onClick={() => setReporting(true)}>{tr('Report', 'রিপোর্ট')}</Button> : null}
            </div>
          ) : null}
        </div>
      ) : null}
      {choosing ? (
        <Dialog onClose={() => setChoosing(false)} size="small" title={tr('Which chamber?', 'কোন চেম্বারে?')} actions={<Button variant="text" onClick={() => setChoosing(false)}>{tr('Cancel', 'বাতিল')}</Button>}>
          <ChamberChoices p={p} bookable={bookable} onPick={(i) => { setChoosing(false); book(i); }} />
        </Dialog>
      ) : null}

      {reporting ? (
        <ReasonReportDialog
          onClose={() => setReporting(false)}
          onSubmit={async (reason) => {
            await call('report', { kind: 'provider', id: p.uid, reason });
            setReporting(false);
            setReported(true);
          }}
        />
      ) : null}
      {booking != null && p.chambers[booking] ? (
        <BookDialog
          provider={p}
          index={booking}
          onClose={() => setBooking(null)}
          onBooked={() => {
            setBooking(null);
            const m = tr("Request sent. You'll be notified when it's confirmed.", 'অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।');
            setDone(m);
            toast(m);
            onBooked?.(m);
          }}
        />
      ) : null}
    </>
  );
}

/**
 * নামের নিচে চেম্বার বাছাই (২.৩৩.০, পারভেজ): প্রতিটা চেম্বার ছোট চাপযোগ্য কার্ড — ছোট নাম · উপজেলা, আজকের অবস্থা
 * ("আজ বসবেন ৪টা–৯টা" / "আজ বন্ধ · আগামীকাল ১০টা"), ফি। অ্যাপয়েন্টমেন্ট নেন এমন চেম্বারে চাপলে সরাসরি বুকিং;
 * না নিলে সিরিয়ালের ফোন (থাকলে)। নিচের বিস্তারিত ভাগগুলো তথ্যের জন্য থাকে।
 */
function ChamberPicker({ p, bookable, onBook, preview }: { p: Provider; bookable: number[]; onBook: (i: number) => void; preview?: boolean }) {
  if (!p.chambers.length) return null;
  return (
    <div className="col gap-6 appt-chpick">
      <span className="t-label-l muted">{bookable.length ? tr('Choose a chamber to book', 'চেম্বার বেছে অ্যাপয়েন্টমেন্ট নিন') : tr('Chambers', 'চেম্বার')}</span>
      {p.chambers.map((c, i) => {
        const st = chamberState(c);
        const can = bookable.includes(i) && st.kind !== 'none';
        const tel = !bookable.includes(i) && c.serialPhone ? c.serialPhone : null;
        const extra = bookable.includes(i) ? null : tel ? tr('Serial by phone', 'সিরিয়াল ফোনে') : tr('No appointment in app', 'অ্যাপে অ্যাপয়েন্টমেন্ট নেই');
        const inner = (
          <>
            <span className="appt-chpick-ic"><Icon name="local_hospital" /></span>
            <span className="col grow appt-chpick-txt">
              <span className="t-title-s semi ellipsis">{chamberShort(c)}</span>
              {st.text ? <span className={cx('t-body-s appt-st', st.kind)}>{st.text}</span> : null}
              <span className="t-body-s muted">{[chamberPlace(c), feeShort(c), extra].filter(Boolean).join(' · ')}</span>
            </span>
            {can ? <span className="appt-chpick-go"><Icon name="calendar_month" /></span> : tel ? <span className="appt-chpick-go"><Icon name="call" /></span> : null}
          </>
        );
        if (can) {
          return (
            <button key={i} type="button" className="appt-chpick-card" onClick={() => onBook(i)} aria-label={tr('Appointment at ', 'অ্যাপয়েন্টমেন্ট: ') + chamberLabel(c)}>
              {inner}
            </button>
          );
        }
        if (tel && !preview) return <a key={i} className="appt-chpick-card" href={`tel:${tel}`}>{inner}</a>;
        return <div key={i} className="appt-chpick-card static">{inner}</div>;
      })}
    </div>
  );
}

/**
 * "কোন চেম্বারে?" (২.৩৩.০): প্রতিটা চেম্বার স্পষ্ট চাপযোগ্য কার্ড — নাম · উপজেলা, পরের বসার দিন-সময়, ফি, ডানে "বেছে নিন ›";
 * সামনে বসার দিন না থাকলে ধূসর আর চাপা যায় না।
 */
function ChamberChoices({ p, bookable, onPick }: { p: Provider; bookable: number[]; onPick: (i: number) => void }) {
  return (
    <div className="col gap-8">
      {bookable.map((i) => {
        const c = p.chambers[i];
        const st = chamberState(c);
        const off = st.kind === 'none' || !nextOpenDays(c, 1).length;
        return (
          <button key={i} type="button" className="appt-pick-card" disabled={off} onClick={() => onPick(i)}>
            <span className="col grow appt-chpick-txt">
              <span className="t-title-s semi">{chamberLabel(c)}</span>
              <span className="t-body-s muted">{chamberPlace(c)}</span>
              <span className={cx('t-body-m appt-st', off ? 'none' : st.kind)}>
                {off ? tr('No upcoming days', 'সামনে বসার দিন নেই') : (st.kind === 'now' ? '' : tr('Next: ', 'পরের সময়: ')) + nextSlotText(st)}
              </span>
              {feeShort(c) ? <span className="t-body-s muted">{feeText(c.noFee, c.fee)}</span> : null}
            </span>
            {off ? null : (
              <span className="appt-pick-go">
                {tr('Choose', 'বেছে নিন')}
                <Icon name="chevron_right" />
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/** সামনের যে দিনগুলোতে চেম্বার খোলা (আজ থেকে ৬০ দিন) */
function nextOpenDays(c: Chamber, count: number): number[] {
  const out: number[] = [];
  const t0 = today();
  // আজকের চেম্বারের সময় শেষ হয়ে গেলে আজ নয় (রাত পেরোনো চেম্বার বাদে) — সার্ভারের নিয়মের মতো
  const [h, m] = dhakaHourMinute(Date.now());
  const now = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  const todayOver = () => {
    const sl = slotsOf(c).filter((t) => t.days.includes(new Date(t0).getUTCDay()));
    return sl.length > 0 && sl.every((t) => t.start && t.end && t.start < t.end && now >= t.end);
  };
  for (let i = 0; i <= 60 && out.length < count; i++) {
    const d = t0 + i * DAY_MS;
    if (chamberOpenOn(c, d) && !(i === 0 && todayOver())) out.push(d);
  }
  return out;
}
/** "শনি, ১৭ অক্টোবর" */
const dayChip = (d: number) => dayShort(new Date(d).getUTCDay()) + ', ' + formatDate(d).replace(/\s+\S+$/, '');

function BookDialog({ provider, index, onClose, onBooked }: { provider: Provider; index: number; onClose: () => void; onBooked: () => void }) {
  const ch = provider.chambers[index];
  // চেম্বার বেছে এলে পরের বসার দিন আগে থেকে বাছা (বদলানো যায়) — এক চাপ কম
  const [date, setDate] = useState<number | null>(() => nextOpenDays(ch, 1)[0] ?? null);
  const [note, setNote] = useState('');
  const [memberId, setMemberId] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // কার জন্য: নিজে, অথবা পরিবারের সদস্য (যাঁর নিজের অ্যাকাউন্ট নেই)
  const fam = useCall<{ family: FamilyMember[] }>('family_get');
  const family = fam.data?.family ?? [];
  const quick = useMemo(() => nextOpenDays(ch, 6), [ch]);
  const t0 = today();

  async function send() {
    const e =
      date == null ? tr('Pick a date', 'তারিখ বেছে নিন')
      : date < t0 ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
      : !chamberOpenOn(ch, date) ? tr('The chamber is closed that day. Pick one of its days.', 'সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।')
      : !note.trim() ? tr('Write the reason for the visit', 'কী সমস্যা, সংক্ষেপে লিখুন')
      : null;
    setErr(e);
    if (e || date == null) return;
    setBusy(true);
    try {
      await call('book', {
        providerUid: provider.uid,
        chamberIndex: index,
        date,
        note: note.trim(),
        chamberKey: ch.hospitalId || ch.hospitalName,
        ...(memberId ? { memberId } : {}),
      });
      onBooked();
    } catch (x) {
      setErr(errorText(x));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={provider.name}
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} onClick={send}>{tr('Send request', 'অনুরোধ পাঠান')}</Button>
        </>
      }
    >
      <div className="col appt-pre">
        <IconLine icon="local_hospital">{chamberLabel(ch)}</IconLine>
        <IconLine icon="schedule">{chamberTimeText(ch)}</IconLine>
      </div>
      {family.length ? (
        <div className="col gap-6">
          <span className="t-label-l">{tr('For whom', 'কার জন্য')}</span>
          <ChoiceChips options={[{ value: '', label: tr('Me', 'আমি') }, ...family.map((m) => ({ value: m.id, label: m.name }))]} value={memberId} onChange={setMemberId} />
        </div>
      ) : null}
      <div className="col gap-6">
        <span className="t-label-l">{tr('Pick a date', 'তারিখ বেছে নিন')}</span>
        {quick.length ? (
          <ChoiceChips options={quick.map((d) => ({ value: d, label: dayChip(d) }))} value={date} onChange={(v: number) => setDate(v)} />
        ) : null}
        <input
          type="date"
          className="input"
          aria-label={tr('Pick a date', 'তারিখ বেছে নিন')}
          min={dateInputValue(t0)}
          max={dateInputValue(t0 + 60 * DAY_MS)}
          value={date ? dateInputValue(date) : ''}
          onChange={(e) => setDate(fromDateInput(e.target.value))}
        />
      </div>
      <TextArea label={tr('Reason for visit', 'কী সমস্যা')} rows={3} value={note} onChange={(e) => setNote(e.target.value.slice(0, 300))} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}
