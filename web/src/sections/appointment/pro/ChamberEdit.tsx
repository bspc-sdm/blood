// চেম্বার ও প্রোফাইল সেটআপ (অ্যাপের ChamberEditScreen / ChamberEditor / TimeSlotsEditor / HomeCareTermsCheck)।
// ডাক্তার: হাসপাতালের তালিকা থেকে চেম্বার; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে; নার্স: চেম্বার নেই, শুধু বাসায় সেবা।
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Check, ChoiceChips, Dialog, ErrorText, IconButton, InfoText, ListRow, SkeletonCards, Switch, TextInput, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { call, errorText, ServerError } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { isBangla, num, tr } from '@/lib/i18n';
import { DISTRICTS, areaLabel, currentPosition, distanceKm, districtAt, districtLabel, upazila, upazilasOf } from '@/lib/geo';
import {
  HOME_RADII, HOME_TYPES, MAX_CHAMBERS, MAX_CHAMBER_TIMES, WEEK_ORDER, chamberArea, chamberLabel, clash, dayShort, homeServiceLabel, homeServicesOf, parseProvider, slotsOf,
  specialtiesOf, specialtyLabel, withSlots, type Chamber, type ChamberTime, type HomeStats, type HomeVisit, type Provider,
} from '../model';
import { AreaSheet, HospitalPickerDialog, PhoneVerifyDialog } from '../shared';
import { ProviderDetail } from '../Doctors';

const TERMS_URL = 'https://github.com/bspc-sdm/blood/blob/main/TERMS.md';
const emptyHome = (): HomeVisit => ({ enabled: false, radiusKm: 5, upazilaIds: [], services: [], schedule: '', fee: '', phone: '', noFee: false, times: [] });
const newSlot = (): ChamberTime => ({ days: [], start: '', end: '' });
type Ed = Chamber & { key: number };
let keySeq = 1;

function chamberJson(c: Chamber) {
  return {
    hospitalId: c.hospitalId,
    room: c.room,
    schedule: c.schedule,
    ...(c.custom ? { placeName: c.hospitalName, district: c.district, address: c.address, ...(c.upazilaId != null ? { upazilaId: c.upazilaId } : {}) } : {}),
    fee: c.fee,
    noFee: c.noFee,
    appointments: c.appointments,
    serialPhone: c.serialPhone,
    days: c.days,
    start: c.start,
    end: c.end,
    ...(c.times.length > 1 ? { times: c.times } : {}),
  };
}
function homeJson(h: HomeVisit) {
  return {
    enabled: h.enabled, termsAccepted: h.enabled, radiusKm: h.radiusKm, upazilaIds: h.upazilaIds, services: h.services,
    schedule: h.schedule, fee: h.fee, noFee: h.noFee, phone: h.phone, times: h.times,
  };
}

export function ChamberEdit({ type, onSaved }: { type: string; onSaved: () => void }) {
  const { profile, setPlace, uid } = useSession();
  const pm = useCall<{ provider: any | null }>('provider_me');
  const nurse = type === 'nurse';
  const homeCapable = HOME_TYPES.includes(type);

  const [listed, setListed] = useState(true);
  const [takes, setTakes] = useState(true);
  const [specialties, setSpecialties] = useState<string[]>([]);
  const [degrees, setDegrees] = useState('');
  const [gender, setGender] = useState('');
  const [chambers, setChambers] = useState<Ed[]>([]);
  const [home, setHome] = useState<HomeVisit>(emptyHome);
  const [homeAgreed, setHomeAgreed] = useState(false);
  const [oldChamber, setOldChamber] = useState<string | null>(null);
  const [gpsPlace, setGpsPlace] = useState<string | null>(profile?.locationSource === 'gps' ? profile.placeName : null);
  const loaded = useRef(false);

  useEffect(() => {
    if (loaded.current || !pm.data) return;
    loaded.current = true;
    const p = pm.data.provider ? parseProvider(pm.data.provider) : null;
    if (!p) return;
    setListed(p.listed);
    setTakes(p.appointments);
    setSpecialties(p.specialties);
    setDegrees(p.degrees);
    setGender(p.gender);
    // আগের ভার্সনের লেখা চেম্বার (হাসপাতালের সাথে যুক্ত নয়, নিজের জায়গাও নয়) আবার বেছে নিতে হবে
    setChambers(p.chambers.filter((c) => c.hospitalId != null || c.custom).map((c) => ({ ...c, key: keySeq++ })));
    const old = p.chambers.find((c) => c.hospitalId == null && !c.custom);
    if (old) setOldChamber(old.hospitalName);
    if (p.homeVisit) {
      setHome(p.homeVisit);
      setHomeAgreed(p.homeVisit.enabled && !!p.homeVisit.phone);
    }
  }, [pm.data]);

  const [pickFor, setPickFor] = useState<number | null>(null); // -1 = নতুন চেম্বার
  const [districtFor, setDistrictFor] = useState<number | null>(null);
  const [pickUpazila, setPickUpazila] = useState(false);
  const [preview, setPreview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [gpsBusy, setGpsBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [verifyPhone, setVerifyPhone] = useState(false);

  const setCh = (i: number, c: Ed) => setChambers((l) => l.map((x, j) => (j === i ? c : x)));

  function validate(): string | null {
    const named = (i: number) => chamberLabel(chambers[i]);
    const homeTimes = home.times.length ? home.times : [newSlot()];
    if (!specialties.length) return tr('Choose at least one specialty', 'অন্তত একটা বিশেষজ্ঞতা বেছে নিন');
    if (nurse && !home.enabled) return tr('Turn on home care', 'বাসায় সেবা চালু করুন');
    if (!homeCapable && !chambers.length) return tr('Add a chamber', 'অন্তত একটা চেম্বার যোগ করুন');
    if (!nurse && !chambers.length && !home.enabled) return tr('Add a chamber, or turn on home care', 'অন্তত একটা চেম্বার যোগ করুন, অথবা বাসায় সেবা চালু করুন');
    if (!nurse) {
      if (chambers.some((c) => c.custom && (c.hospitalName.trim().length < 3 || !c.district || c.address.trim().length < 5))) return tr('Write the place name, district and address', 'জায়গার নাম, জেলা আর ঠিকানা লিখুন');
      if (chambers.some((c) => slotsOf(c).some((t) => !t.days.length))) return tr('Choose the days for each chamber', 'প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন');
      if (chambers.some((c) => slotsOf(c).some((t) => !t.start || !t.end || t.start === t.end))) return tr('Set the start and end time for each chamber', 'প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন');
      const cl = clash(chambers.map((c) => ({ slots: slotsOf(c) })));
      if (cl) {
        const [x, y] = cl;
        return x === y
          ? tr(`Two times in "${named(x)}" overlap on the same day`, `"${named(x)}" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে`)
          : tr(`"${named(x)}" and "${named(y)}" overlap on the same day and time`, `"${named(x)}" আর "${named(y)}" একই বারে একই সময়ে পড়েছে — একসাথে দুই জায়গায় বসা যায় না`);
      }
      if (chambers.some((c) => !c.fee.trim() && !c.noFee)) return tr('Enter the fee for each chamber, or tick "No fee"', 'প্রতিটা চেম্বারে ফি দিন, অথবা "ফি চাই না" টিক দিন');
    }
    if (home.enabled) {
      if (!home.services.length) return tr('Choose the services you provide at home', 'বাসায় কোন কোন সেবা দেন বেছে নিন');
      if (homeTimes.some((t) => !t.days.length)) return tr('Choose the days you visit homes', 'কোন কোন বার বাসায় যান বেছে নিন');
      if (homeTimes.some((t) => !t.start || !t.end || t.start === t.end)) return tr('Set the start and end time for home visits', 'বাসায় যাওয়ার শুরু আর শেষের সময় দিন');
      if (clash([{ slots: homeTimes }])) return tr('Two home-visit times overlap on the same day', 'বাসায় যাওয়ার দুটো সময় একই বারে মিলে যাচ্ছে');
      if (!home.fee.trim() && !home.noFee) return tr('Enter the home care fee, or tick "No fee"', 'বাসায় সেবার ফি দিন, অথবা "ফি চাই না" টিক দিন');
      if (!/^(\+?88)?01[3-9]\d{8}$/.test(home.phone)) return tr('Enter a valid mobile number for home care', 'বাসায় সেবার জন্য সঠিক মোবাইল নম্বর দিন');
      if (!homeAgreed) return tr('Please accept the home care terms', 'বাসায় সেবার শর্তে সম্মতি দিন');
      if (!gpsPlace && !home.upazilaIds.length) return tr('Set your current location or choose upazilas for home care', 'বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন');
    }
    return null;
  }

  async function save() {
    const e = validate();
    setErr(e);
    if (e) return;
    setBusy(true);
    try {
      const r = await call<{ ok: boolean; cancelled?: number }>('provider_save', {
        listed: listed && !nurse,
        appointments: takes && !nurse,
        specialties,
        degrees: degrees.trim(),
        gender,
        chambers: nurse ? [] : chambers.map(chamberJson),
        homeVisit: homeJson(homeCapable ? home : emptyHome()),
      });
      const n = r.cancelled ?? 0;
      toast(
        n > 0
          ? tr(`${n} upcoming appointment(s) no longer fit and were cancelled. Patients were told.`, `সামনের ${num(n)}টা অ্যাপয়েন্টমেন্ট আর মেলে না, তাই বাতিল হয়েছে। রোগীদের জানানো হয়েছে।`)
          : tr('Saved.', 'সেভ হয়েছে।'),
      );
      pm.refetch();
      onSaved();
    } catch (x) {
      if (x instanceof ServerError && x.code === 'phone_unverified') setVerifyPhone(true);
      else setErr(errorText(x));
    } finally {
      setBusy(false);
    }
  }

  async function useGps() {
    setGpsBusy(true);
    setErr(null);
    try {
      const { lat, lng } = await currentPosition();
      const d = await districtAt(lat, lng);
      await setPlace({ lat, lng, placeName: d.bn, source: 'gps' });
      setGpsPlace(d.bn);
    } catch (x) {
      setErr(x instanceof Error && x.message && !/denied|no-geo/i.test(x.message) ? x.message : tr("Couldn't get your location.", 'লোকেশন পাওয়া যায়নি।'));
    } finally {
      setGpsBusy(false);
    }
  }

  // রোগীরা তালিকায় চাপলে যেমন দেখবেন (সেভ না করেও, এখনকার বদলসহ)
  const previewProvider: Provider = useMemo(() => ({
    uid: uid ?? '', type, name: profile?.name ?? '', specialties, degrees: degrees.trim(), gender, listed, appointments: takes,
    chambers: nurse ? [] : chambers, homeVisit: homeCapable ? { ...home, phone: home.phone || ' ' } : null, rating: 0, ratingCount: 0, photo: '',
  }), [uid, type, profile, specialties, degrees, gender, listed, takes, nurse, chambers, homeCapable, home]);

  if (pm.isLoading) return <SkeletonCards n={3} h={140} />;
  if (pm.error) return <ErrorText>{errorText(pm.error)}</ErrorText>;

  const profileCard = (
    <section className="card lowest col gap-12 appt-sec-card">
      <h2 className="t-title-m">{tr('Profile', 'প্রোফাইল')}</h2>
      {!nurse ? (
        <div className="col gap-2">
          <Switch label={tr('Show me in the list', 'তালিকায় আমাকে দেখাও')} checked={listed} onChange={setListed} />
          <Switch label={tr('Take appointments through the app', 'অ্যাপে অ্যাপয়েন্টমেন্ট নেব')} checked={takes} onChange={setTakes} />
        </div>
      ) : null}
      <div className="col gap-6">
        <span className="t-title-s">{tr('Specialty', 'বিশেষজ্ঞতা')}</span>
        <ChoiceChips
          multi
          options={specialtiesOf(type).map((c) => ({ value: c, label: specialtyLabel(c) }))}
          value={specialties}
          onChange={(v: string[]) => setSpecialties(v.slice(0, 5))}
        />
      </div>
      <TextInput label={tr('Degrees (optional)', 'ডিগ্রি (ঐচ্ছিক)')} placeholder={tr('e.g. MBBS, FCPS (Medicine)', 'যেমন: MBBS, FCPS (Medicine)')} value={degrees} onChange={(e) => setDegrees(e.target.value.slice(0, 120))} />
      <div className="col gap-6">
        <span className="t-label-l">{tr('Gender (optional)', 'লিঙ্গ (ঐচ্ছিক)')}</span>
        <ChoiceChips options={[{ value: '', label: tr('Not set', 'দেখাব না') }, { value: 'female', label: tr('Female', 'মহিলা') }, { value: 'male', label: tr('Male', 'পুরুষ') }]} value={gender} onChange={setGender} />
      </div>
    </section>
  );

  const homeCard = homeCapable ? (
    <section className="card lowest col gap-12 appt-sec-card">
      <h2 className="t-title-m">{tr('Home care', 'বাসায় সেবা')}</h2>
      <HomeStatusNote />
      <Switch
        label={tr('I visit patients at home', 'আমি রোগীর বাসায় গিয়ে সেবা দিই')}
        checked={home.enabled}
        onChange={(on) => setHome((h) => ({ ...h, enabled: on, phone: h.phone || profile?.phone || '' }))}
      />
      {home.enabled ? (
        <>
          <TextInput
            label={tr('Phone patients will call', 'রোগীরা যে নম্বরে ফোন করবেন')}
            hint={tr('Shown to everyone in the list', 'তালিকায় সবাই দেখবেন')}
            inputMode="tel"
            value={home.phone}
            onChange={(e) => setHome((h) => ({ ...h, phone: e.target.value.replace(/[^0-9+]/g, '').slice(0, 14) }))}
          />
          <div className="col gap-6">
            <span className="t-label-l">{tr('Services', 'কী কী সেবা দেন')}</span>
            <ChoiceChips multi options={homeServicesOf(type).map((c) => ({ value: c, label: homeServiceLabel(c) }))} value={home.services} onChange={(v: string[]) => setHome((h) => ({ ...h, services: v }))} />
          </div>
          <div className="col gap-6">
            <span className="t-label-l">{tr('How far from your location', 'আপনার লোকেশন থেকে কত দূর')}</span>
            <ChoiceChips options={HOME_RADII.map((r) => ({ value: r, label: tr(`${r} km`, `${num(r)} কিমি`) }))} value={home.radiusKm} onChange={(v: number) => setHome((h) => ({ ...h, radiusKm: v }))} />
            {gpsPlace ? <span className="t-body-s muted">{tr('Location: ', 'লোকেশন: ') + districtLabel(gpsPlace)}</span> : null}
            <Button variant="outline" icon="location_on" busy={gpsBusy} onClick={useGps} style={{ alignSelf: 'flex-start' }}>
              {gpsPlace ? tr('Update location', 'লোকেশন আপডেট') : tr('Use my current location', 'বর্তমান লোকেশন দিন')}
            </Button>
          </div>
          <div className="col gap-6">
            <span className="t-label-l">{tr('Also these upazilas (optional)', 'এই উপজেলাগুলোতেও (ঐচ্ছিক)')}</span>
            <div className="chips">
              {home.upazilaIds.map((id) => {
                const u = upazila(id);
                return (
                  <button key={id} type="button" className="chip on" onClick={() => setHome((h) => ({ ...h, upazilaIds: h.upazilaIds.filter((x) => x !== id) }))} aria-label={tr('Remove', 'সরান') + ': ' + (u ? (isBangla() ? u.bn : u.en) : id)}>
                    {u ? (isBangla() ? u.bn : u.en) : num(id)}
                    <Icon name="close" />
                  </button>
                );
              })}
              {home.upazilaIds.length < 10 ? (
                <button type="button" className="chip lead" onClick={() => setPickUpazila(true)}>
                  <Icon name="add" />
                  {tr('Add', 'যোগ')}
                </button>
              ) : null}
            </div>
          </div>
          <div className="col gap-6">
            <span className="t-title-s">{tr('When you visit', 'কখন যেতে পারেন')}</span>
            <TimeSlotsEditor slots={home.times.length ? home.times : [newSlot()]} onChange={(times) => setHome((h) => ({ ...h, times }))} />
          </div>
          <TextInput label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} placeholder={tr('e.g. Call an hour before', 'যেমন: এক ঘণ্টা আগে ফোন দিন')} value={home.schedule} onChange={(e) => setHome((h) => ({ ...h, schedule: e.target.value.slice(0, 120) }))} />
          <TextInput label={tr('Fee', 'ফি')} placeholder={tr('e.g. 300 Tk + transport', 'যেমন: ৩০০ টাকা + যাতায়াত')} disabled={home.noFee} value={home.fee} onChange={(e) => setHome((h) => ({ ...h, fee: e.target.value.slice(0, 60) }))} />
          <Check checked={home.noFee} onChange={(v) => setHome((h) => ({ ...h, noFee: v, fee: v ? '' : h.fee }))}>{tr('No fee', 'ফি চাই না')}</Check>
          <div className="col gap-2">
            <Check checked={homeAgreed} onChange={setHomeAgreed}>{tr('I accept the home care terms, and agree to show this number to patients', 'বাসায় সেবার শর্ত মেনে নিচ্ছি, আর এই নম্বরটা রোগীদের দেখাতে রাজি')}</Check>
            <a href={TERMS_URL} target="_blank" rel="noreferrer" className="t-body-s c-primary appt-terms">{tr('Read the terms', 'শর্তগুলো পড়ুন')}</a>
          </div>
        </>
      ) : null}
    </section>
  ) : null;

  return (
    <div className="col gap-16 appt-setup">
      <div className="row wrap">
        <Button variant="outline" icon="info" onClick={() => setPreview(true)}>{tr('See how patients see it', 'রোগীরা যেমন দেখবেন')}</Button>
      </div>
      {profileCard}

      {!nurse ? (
        <section className="col gap-12">
          <h2 className="t-title-m">{tr('Chambers (up to 3)', 'চেম্বার (সর্বোচ্চ ৩টা)')}</h2>
          {oldChamber ? (
            <InfoText>
              {tr(`Your earlier chamber "${oldChamber}" now needs to be chosen from the hospital list. Tap Add chamber.`, `আগের চেম্বার "${oldChamber}" এখন হাসপাতালের তালিকা থেকে বেছে নিতে হবে। "চেম্বার যোগ করুন" চাপুন।`)}
            </InfoText>
          ) : null}
          <div className="appt-ch-grid">
            {chambers.map((c, i) => (
              <ChamberEditor
                key={c.key}
                c={c}
                onChange={(n) => setCh(i, { ...n, key: c.key })}
                onChangeHospital={() => setPickFor(i)}
                onDistrict={() => setDistrictFor(i)}
                onRemove={() => setChambers((l) => l.filter((_, j) => j !== i))}
              />
            ))}
            {chambers.length < MAX_CHAMBERS ? (
              <button
                type="button"
                className="appt-add-ch"
                onClick={() => {
                  // ডাক্তার: হাসপাতালের তালিকা থেকে; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে (হাসপাতালে বসেন না)
                  if (type === 'doctor') setPickFor(-1);
                  else setChambers((l) => [...l, { ...blankChamber(profile?.placeName ?? '', true), key: keySeq++ }]);
                }}
              >
                <Icon name="add" />
                {tr('Add chamber', 'চেম্বার যোগ করুন')}
              </button>
            ) : null}
          </div>
        </section>
      ) : null}

      {homeCard}

      <div className="appt-savebar">
        <ErrorText>{err}</ErrorText>
        <div className="row">
          <span className="grow" />
          <Button busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
        </div>
      </div>

      {pickFor != null ? (
        <HospitalPickerDialog
          initialDistrict={chambers[pickFor]?.district || profile?.placeName || null}
          initialUpazilaId={chambers[pickFor]?.upazilaId ?? null}
          onPick={(h) => {
            const fresh = { hospitalId: h.id, hospitalName: h.nameEn, hospitalNameBn: h.nameBn, district: h.district, upazilaId: h.upazilaId, unionId: h.unionId };
            if (pickFor >= 0 && chambers[pickFor]) setCh(pickFor, { ...chambers[pickFor], ...fresh });
            else setChambers((l) => [...l, { ...blankChamber(h.district, false), ...fresh, appointments: takes, key: keySeq++ }]);
            setPickFor(null);
          }}
          onClose={() => setPickFor(null)}
        />
      ) : null}
      {districtFor != null && chambers[districtFor] ? (
        <AreaSheet
          value={{ district: chambers[districtFor].district || null, upazilaId: chambers[districtFor].upazilaId, unionId: null }}
          onPick={(v) => {
            if (v.district) setCh(districtFor, { ...chambers[districtFor], district: v.district, upazilaId: v.upazilaId });
            setDistrictFor(null);
          }}
          onClose={() => setDistrictFor(null)}
        />
      ) : null}
      {pickUpazila ? (
        <UpazilaPicker
          own={profile?.placeName ?? ''}
          lat={profile?.lat ?? null}
          lng={profile?.lng ?? null}
          onPick={(id) => {
            setHome((h) => (h.upazilaIds.includes(id) ? h : { ...h, upazilaIds: [...h.upazilaIds, id] }));
            setPickUpazila(false);
          }}
          onClose={() => setPickUpazila(false)}
        />
      ) : null}
      {preview ? (
        <Dialog onClose={() => setPreview(false)} title={tr('See how patients see it', 'রোগীরা যেমন দেখবেন')} size="wide">
          <div className="col gap-12">
            <ProviderDetail p={previewProvider} preview />
          </div>
        </Dialog>
      ) : null}
      {verifyPhone ? (
        <PhoneVerifyDialog
          onClose={() => setVerifyPhone(false)}
          onVerified={() => {
            setVerifyPhone(false);
            setErr(tr('Phone verified. Tap Save again.', 'ফোন যাচাই হয়েছে। আবার সেভ চাপুন।'));
          }}
        />
      ) : null}
    </div>
  );
}

function blankChamber(district: string, custom: boolean): Chamber {
  return {
    hospitalId: null, hospitalName: '', hospitalNameBn: '', district, upazilaId: null, unionId: null, room: '', schedule: '', fee: '',
    appointments: true, serialPhone: '', days: [], start: '', end: '', noFee: false, custom, address: '', times: [],
  };
}

function ChamberEditor({ c, onChange, onChangeHospital, onDistrict, onRemove }: { c: Chamber; onChange: (c: Chamber) => void; onChangeHospital: () => void; onDistrict: () => void; onRemove: () => void }) {
  return (
    <div className="card col gap-12 appt-ch-ed">
      {c.custom ? (
        <>
          <div className="row top">
            <div className="grow">
              <TextInput label={tr('Place name', 'জায়গার নাম')} placeholder={tr('e.g. Fit Life Gym', 'যেমন: ফিট লাইফ জিম')} value={c.hospitalName} onChange={(e) => onChange({ ...c, hospitalName: e.target.value.slice(0, 100), hospitalNameBn: e.target.value.slice(0, 100) })} />
            </div>
            <IconButton icon="delete" label={tr('Remove', 'সরান')} onClick={onRemove} style={{ marginTop: 20 }} />
          </div>
          <button type="button" className="area-btn" onClick={onDistrict}>
            <Icon name="location_on" />
            <span>{c.district ? areaLabel(c.district, c.upazilaId) : tr('Choose district', 'জেলা বেছে নিন')}</span>
            <Icon name="expand_more_round" />
          </button>
          <TextInput label={tr('Address', 'ঠিকানা')} placeholder={tr('House, road, area', 'বাড়ি, রোড, এলাকা')} value={c.address} onChange={(e) => onChange({ ...c, address: e.target.value.slice(0, 150) })} />
        </>
      ) : (
        <div className="row top">
          <div className="col gap-2 grow">
            <span className="t-title-s">{chamberLabel(c)}</span>
            {chamberArea(c) ? <span className="t-body-s muted">{chamberArea(c)}</span> : null}
          </div>
          <IconButton icon="edit" label={tr('Change hospital', 'হাসপাতাল বদলান')} onClick={onChangeHospital} />
          <IconButton icon="delete" label={tr('Remove', 'সরান')} onClick={onRemove} />
        </div>
      )}
      <TimeSlotsEditor slots={slotsOf(c)} onChange={(l) => onChange(withSlots(c, l))} />
      <TextInput label={tr('Note (optional)', 'নোট (ঐচ্ছিক)')} placeholder={tr('e.g. Closed on public holidays', 'যেমন: সরকারি ছুটির দিন বন্ধ')} value={c.schedule} onChange={(e) => onChange({ ...c, schedule: e.target.value.slice(0, 120) })} />
      <div className="appt-two">
        <TextInput label={tr('Room (optional)', 'রুম (ঐচ্ছিক)')} value={c.room} onChange={(e) => onChange({ ...c, room: e.target.value.slice(0, 60) })} />
        <TextInput label={tr('Fee', 'ফি')} placeholder={tr('e.g. 500 Tk', 'যেমন: ৫০০ টাকা')} disabled={c.noFee} value={c.fee} onChange={(e) => onChange({ ...c, fee: e.target.value.slice(0, 40) })} />
      </div>
      <Check checked={c.noFee} onChange={(v) => onChange({ ...c, noFee: v, fee: v ? '' : c.fee })}>{tr('No fee', 'ফি চাই না')}</Check>
      <TextInput label={tr('Serial phone (optional)', 'সিরিয়ালের ফোন (ঐচ্ছিক)')} inputMode="tel" value={c.serialPhone} onChange={(e) => onChange({ ...c, serialPhone: e.target.value.replace(/[^0-9+]/g, '').slice(0, 15) })} />
      <Switch label={tr('Appointments in app at this chamber', 'এই চেম্বারে অ্যাপে অ্যাপয়েন্টমেন্ট')} checked={c.appointments} onChange={(v) => onChange({ ...c, appointments: v })} />
    </div>
  );
}

/** বার আর শুরু–শেষের ভাগ (চেম্বার আর বাসায় সেবা): প্রতিটা ভাগে বার, সময়, আর "অন্য বারে আলাদা সময়" */
function TimeSlotsEditor({ slots, onChange }: { slots: ChamberTime[]; onChange: (l: ChamberTime[]) => void }) {
  const set = (i: number, t: ChamberTime) => onChange(slots.map((x, j) => (j === i ? t : x)));
  return (
    <div className="col gap-12">
      {slots.map((s, i) => (
        <div key={i} className="col gap-6 appt-slot">
          <div className="row">
            <span className="t-label-l grow">{slots.length > 1 ? tr(`Time ${i + 1}: days`, `সময় ${num(i + 1)}: কোন কোন বার`) : tr('Days', 'কোন কোন বার')}</span>
            {slots.length > 1 ? <IconButton small icon="close" label={tr('Remove this time', 'এই সময় সরান')} onClick={() => onChange(slots.filter((_, j) => j !== i))} /> : null}
          </div>
          <ChoiceChips multi options={WEEK_ORDER.map((d) => ({ value: d, label: dayShort(d) }))} value={s.days} onChange={(v: number[]) => set(i, { ...s, days: v })} />
          <div className="row appt-times">
            <TextInput type="time" label={tr('Start time', 'শুরুর সময়')} value={s.start} onChange={(e) => set(i, { ...s, start: e.target.value })} />
            <span className="appt-dash">–</span>
            <TextInput type="time" label={tr('End time', 'শেষের সময়')} value={s.end} onChange={(e) => set(i, { ...s, end: e.target.value })} />
          </div>
        </div>
      ))}
      {slots.length < MAX_CHAMBER_TIMES ? (
        <Button variant="text" icon="add" onClick={() => onChange([...slots, newSlot()])} style={{ alignSelf: 'flex-start' }}>
          {tr('Different time on other days', 'অন্য বারে আলাদা সময়')}
        </Button>
      ) : null}
    </div>
  );
}

/** বাসায় সেবার উপজেলা: নিজের জেলা আগে, তারপর কাছের জেলাগুলো (সীমানার কাছে থাকলে পাশের জেলায়ও যান) */
function UpazilaPicker({ own, lat, lng, onPick, onClose }: { own: string; lat: number | null; lng: number | null; onPick: (id: number) => void; onClose: () => void }) {
  const [q, setQ] = useState('');
  const near = lat != null && lng != null ? [...DISTRICTS].sort((a, b) => distanceKm(lat, lng, a.lat, a.lng) - distanceKm(lat, lng, b.lat, b.lng)).slice(0, 6).map((d) => d.bn) : [];
  const districts = [...new Set([own, ...near].filter(Boolean))];
  const items = districts.flatMap((d) => upazilasOf(d));
  const t = q.trim().toLowerCase();
  const label = (u: { district: string; bn: string; en: string }) => (isBangla() ? u.bn : u.en) + (u.district === own ? '' : ` (${districtLabel(u.district)})`);
  return (
    <Dialog onClose={onClose} title={tr('Upazila', 'উপজেলা')}>
      <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder={tr('Search', 'খুঁজুন')} aria-label={tr('Search', 'খুঁজুন')} />
      <div className="col gap-2 appt-pick-list">
        {items.filter((u) => !t || u.bn.includes(q.trim()) || u.en.toLowerCase().includes(t)).map((u) => (
          <ListRow key={u.id} title={label(u)} onClick={() => onPick(u.id)} trailing={<span />} />
        ))}
        {!items.length ? <p className="t-body-m muted" style={{ padding: 16 }}>{tr('Nothing found', 'কিছু পাওয়া যায়নি')}</p> : null}
      </div>
    </Dialog>
  );
}

/** সেটআপের ভেতরে: রিপোর্টের পর বন্ধ হলে কারণ আর আপিল, চালু থাকলে ফোনের হিসাব */
function HomeStatusNote() {
  const q = useCall<HomeStats>('home_stats');
  const s = q.data;
  if (!s || (!s.blocked && !s.enabled)) return null;
  if (s.blocked) {
    return (
      <div className="card danger col gap-6">
        <span className="t-body-m">{tr('Turned off by the Lifolo team after a report. You can appeal.', 'রিপোর্টের পর Lifolo টিম বন্ধ করেছে। আপিল করতে পারেন।')}</span>
        <Link className="btn outline small has-icon" to="/help" style={{ alignSelf: 'flex-start' }}><Icon name="help" />{tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন')}</Link>
      </div>
    );
  }
  return (
    <div className="card tonal col gap-2">
      <span className="t-body-m">{tr('You are in the list — patients call you directly.', 'আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।')}</span>
      <span className="t-body-s">
        {tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + tr(`${s.calls?.[7] ?? 0} in 7 days · ${s.calls?.[30] ?? 0} in 30 days`, `৭ দিনে ${num(s.calls?.[7] ?? 0)} · ৩০ দিনে ${num(s.calls?.[30] ?? 0)}`)}
      </span>
    </div>
  );
}
