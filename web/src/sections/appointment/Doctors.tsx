// অ্যাপয়েন্টমেন্ট: ডাক্তার / পুষ্টিবিদ / ফিটনেস ট্রেইনারের তালিকা আর বিস্তারিত (অ্যাপের AppointmentTab, ProviderList,
// ProviderCard, ProviderDetailDialog, BookDialog)। বাছা মানুষ URL-এ: /appointment/doctor/:id?t=doctor
import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  ActiveChips, Button, ChoiceChips, Dialog, DirectoryCard, EmptyState, ErrorBox, FilterBar, IconButton, IconLine, InfoText, PhoneLine, PillTabs, SkeletonCards, Switch, TextArea, ErrorText, toast,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useFilterArea } from '@/ui/AreaPicker';
import { DetailPane, ListDetail } from '@/shell/templates';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { formatDate, dateInputValue, fromDateInput } from '@/lib/format';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel, upazila } from '@/lib/geo';
import {
  CHAMBER_TYPES, DAY_MS, badgeIcon, badgeLabel, canBook, chamberArea, chamberLabel, chamberOpenOn, chamberTimeText, coverageText, feeText, homeServiceLabel,
  parseProvider, ratingLine, specialtiesOf, specialtyLabel, timesText, today, useCatalog, type Chamber, type FamilyMember, type Hospital, type Provider,
} from './model';
import { AreaSheet, DetailBlock, DetailHead, EmergencyNotice, FilterChoice, FilterSheet, HospitalPickerDialog, ReasonReportDialog, useAreasReady, type AreaSel } from './shared';

const TAB_ICON: Record<string, string> = { doctor: 'stethoscope', nutritionist: 'restaurant', trainer: 'fitness_center' };
const tabLabel = (t: string) => (t === 'nutritionist' ? tr('Nutritionist', 'পুষ্টিবিদ') : t === 'trainer' ? tr('Fitness trainer', 'ফিটনেস ট্রেইনার') : tr('Doctor', 'ডাক্তার'));

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
  const [hospital, setHospital] = useState<Hospital | null>(null);
  const [specialty, setSpecialty] = useState<string | null>(null);
  const [homeOnly, setHomeOnly] = useState(false);
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
  const q = useCall<{ items: any[] }>('providers', body);
  const list = useMemo(() => (q.data?.items ?? []).map(parseProvider), [q.data]);
  const t = query.trim().toLowerCase();
  const shown = list
    .filter((p) => p.uid !== uid)
    .filter((p) => !t || p.name.toLowerCase().includes(t) || p.degrees.toLowerCase().includes(t) || p.chambers.some((c) => chamberLabel(c).toLowerCase().includes(t)));

  // সরাসরি খোলা লিংক: এখনকার তালিকায় না থাকলে পুরো দেশের তালিকা থেকে
  const inList = detailId ? list.find((p) => p.uid === detailId) ?? null : null;
  const fb = useCall<{ items: any[] }>('providers', { type, homeVisit: false }, { enabled: !!detailId && !inList && !q.isLoading });
  const selected = inList ?? (detailId ? (fb.data?.items ?? []).map(parseProvider).find((p) => p.uid === detailId) ?? null : null);

  const qs = sp.toString();
  const listPath = '/appointment' + (qs ? `?${qs}` : '');
  const open = (id: string) => nav(`/appointment/doctor/${id}${qs ? `?${qs}` : ''}`);
  const filtersOn = !!(cur.district || cur.upazilaId || hospital || specialty || homeOnly);
  const isEmptyFilter = !cur.district && !hospital && !specialty && !homeOnly;

  const listPart = (
    <div className="list-inner appt-pre">
      <div className="row appt-title">
        <h1 className="t-headline grow appt-h1">{tr('Appointment', 'অ্যাপয়েন্টমেন্ট')}</h1>
        {!guest ? (
          <Link className="btn text small has-icon" to="/my-appointments">
            <Icon name="event_outline" />
            {tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')}
          </Link>
        ) : null}
        <Link className="appt-home-btn" to="/appointment/home-care">
          <Icon name="home_health" />
          {tr('Home care', 'বাসায় সেবা')}
        </Link>
      </div>
      <div className="appt-bleed">
        <PillTabs
          tabs={CHAMBER_TYPES.map((k) => ({ key: k, label: tabLabel(k), icon: TAB_ICON[k] }))}
          value={type}
          onChange={(k) => {
            setHospital(null);
            setSpecialty(null);
            setHomeOnly(false);
            setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
            if (detailId) nav(k === 'doctor' ? '/appointment' : `/appointment?t=${k}`, { replace: true });
          }}
        />
      </div>
      <FilterBar
        areaLabel={hospital ? areaLabel(hospital.district, hospital.upazilaId) : cur.district ? areaLabel(cur.district, cur.upazilaId, cur.unionId) : tr('All districts', 'সব জেলা')}
        onArea={() => setShowArea(true)}
        filterCount={[hospital, specialty, homeOnly || null].filter(Boolean).length}
        onFilter={() => setShowFilter(true)}
        search={query}
        onSearch={setQuery}
      >
        <ActiveChips
          items={[
            ...(hospital ? [{ label: chamberLabel({ hospitalName: hospital.nameEn, hospitalNameBn: hospital.nameBn }), onRemove: () => setHospital(null) }] : []),
            ...(specialty ? [{ label: specialtyLabel(specialty), onRemove: () => setSpecialty(null) }] : []),
            ...(homeOnly ? [{ label: tr('Home visit', 'বাসায় যান'), onRemove: () => setHomeOnly(false) }] : []),
          ]}
        />
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
          {!isEmptyFilter ? (
            <Button
              variant="text"
              onClick={() => {
                setArea({ district: null, upazilaId: null, unionId: null });
                setHospital(null);
                setSpecialty(null);
                setHomeOnly(false);
              }}
            >
              {tr('Search everywhere', 'সব জায়গায় খুঁজুন')}
            </Button>
          ) : null}
        </EmptyState>
      ) : (
        shown.map((p) => <ProviderCard key={p.uid} p={p} area={cur} hospital={hospital} selected={p.uid === detailId} onClick={() => open(p.uid)} />)
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
            setHospital(null);
            setArea(v);
            if (v.district) setFa({ district: v.district, upazilaId: v.upazilaId, unionId: v.unionId });
            setShowArea(false);
          }}
          onClose={() => setShowArea(false)}
        />
      ) : null}
      {showFilter ? (
        <FilterSheet
          title={tr('Filter', 'ফিল্টার')}
          onClose={() => setShowFilter(false)}
          onClear={filtersOn ? () => { setHospital(null); setSpecialty(null); setHomeOnly(false); } : undefined}
        >
          <div className="col gap-6">
            <span className="t-title-s">{tr('Hospital', 'হাসপাতাল')}</span>
            <div className="row wrap">
              <Button variant="outline" icon="local_hospital" onClick={() => setPickHospital(true)}>
                {hospital ? chamberLabel({ hospitalName: hospital.nameEn, hospitalNameBn: hospital.nameBn }) : tr('Choose hospital', 'হাসপাতাল বেছে নিন')}
              </Button>
              {hospital ? <Button variant="text" onClick={() => setHospital(null)}>{tr('Remove', 'সরান')}</Button> : null}
            </div>
          </div>
          <FilterChoice title={tr('Specialty', 'বিশেষজ্ঞতা')} options={specialtiesOf(type)} value={specialty} label={specialtyLabel} onChange={setSpecialty} />
          {type === 'doctor' ? <Switch label={tr('Home visit', 'বাসায় যান')} checked={homeOnly} onChange={setHomeOnly} /> : null}
        </FilterSheet>
      ) : null}
      {pickHospital ? (
        <HospitalPickerDialog
          initialDistrict={cur.district}
          initialUpazilaId={cur.upazilaId}
          onPick={(h) => {
            setHospital(h);
            setArea({ district: h.district, upazilaId: null, unionId: null });
            setPickHospital(false);
          }}
          onClose={() => setPickHospital(false)}
        />
      ) : null}
    </>
  );
}

function ProviderCard({ p, area, hospital, selected, onClick }: { p: Provider; area: AreaSel; hospital: Hospital | null; selected: boolean; onClick: () => void }) {
  // ফিল্টারের সাথে মেলা চেম্বার আগে
  const ch = p.chambers.find((c) => (!hospital || c.hospitalId === hospital.id) && (!area.district || c.district === area.district) && (area.upazilaId == null || c.upazilaId === area.upazilaId)) ?? p.chambers[0];
  return (
    <DirectoryCard
      title={p.name}
      icon={badgeIcon(p.type)}
      photo={p.photo || null}
      subtitle={[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')}
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
  const ups = hv ? hv.upazilaIds.map((id) => upazila(id)).filter(Boolean).map((u) => (isBangla() ? u!.bn : u!.en)) : [];

  return (
    <>
      <div className="row top">
        <div className="grow">
          <DetailHead title={p.name} icon={badgeIcon(p.type)} photo={p.photo || null} subtitle={p.specialties.map(specialtyLabel).join(' · ')} meta={ratingLine(p.rating, p.ratingCount)} />
        </div>
        {two && onClose && !preview ? <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={onClose} /> : null}
      </div>
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
              ['call', c.serialPhone ? tr('Serial by phone: ', 'সিরিয়াল ফোনে: ') + num(c.serialPhone) : ''],
            ]}
          >
            {p.appointments && c.appointments ? (
              <Button
                square
                icon="calendar_month"
                onClick={() => !preview && requireLogin(tr('Log in to request an appointment', 'অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন'), () => setBooking(i))}
              >
                {tr('Request appointment', 'অ্যাপয়েন্টমেন্ট চাই')}
              </Button>
            ) : null}
          </DetailBlock>
          {c.serialPhone ? (
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
      {!preview && !guest && p.uid !== uid && !reported ? (
        <div className="row">
          <span className="grow" />
          <Button variant="text" icon="flag" onClick={() => setReporting(true)}>{tr('Report', 'রিপোর্ট')}</Button>
        </div>
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

/** সামনের যে দিনগুলোতে চেম্বার খোলা (আজ থেকে ৬০ দিন) */
function nextOpenDays(c: Chamber, count: number): number[] {
  const out: number[] = [];
  const t0 = today();
  for (let i = 0; i <= 60 && out.length < count; i++) {
    const d = t0 + i * DAY_MS;
    if (chamberOpenOn(c, d)) out.push(d);
  }
  return out;
}

function BookDialog({ provider, index, onClose, onBooked }: { provider: Provider; index: number; onClose: () => void; onBooked: () => void }) {
  const ch = provider.chambers[index];
  const [date, setDate] = useState<number | null>(null);
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
          <ChoiceChips options={quick.map((d) => ({ value: d, label: formatDate(d) }))} value={date} onChange={(v: number) => setDate(v)} />
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
