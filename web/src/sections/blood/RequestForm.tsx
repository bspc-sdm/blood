// নতুন রিকোয়েস্ট আর রিকোয়েস্ট এডিট — অ্যাপের NewRequestScreen / EditRequestScreen (RequestScreens.kt)।
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  BloodGroupBadge, Button, ChoiceChips, Dialog, ErrorText, Field, InfoText, Loading, Switch, TextArea, TextInput,
} from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { PlacePicker } from '@/ui/AreaPicker';
import { FormPage } from '@/shell/templates';
import { call, errorText } from '@/lib/api';
import { isBangla, num, toAsciiDigits, tr } from '@/lib/i18n';
import { areaLabel, districtLabel, geohash, roundCoord } from '@/lib/geo';
import { useSession, type Place } from '@/lib/session';
import { BLOOD_GROUPS, DAY_MS, MAX_OPEN_REQUESTS, dhakaMillis } from '@/lib/rules';
import { dateInputValue, dhakaHourMinute, formatDate, formatTimeHM, fromDateInput, normalizePhone, todayUtcMidnight } from '@/lib/format';
import { api, myRequests, requesterContact, useInvalidate, useMyRequest } from './data';
import { isActive, nameCase, type BloodRequest } from './model';
import { LoginNeeded } from './LoginNeeded';

// ---------- ফর্মের অংশ (নতুন ও এডিট দুটোতেই) ----------

const FieldLabel = ({ children }: { children: string }) => <span className="t-title-s">{children}</span>;

function BagsPicker({ bags, onChange }: { bags: number; onChange: (n: number) => void }) {
  const opts = [1, 2, 3, 4, 5, ...(bags > 5 ? [bags] : [])].map((n) => ({ value: n, label: num(n) }));
  return <ChoiceChips options={opts} value={bags} onChange={onChange} />;
}

/** তারিখ আর সময়: অ্যাপের মতো দুটো বাটন, চাপলে ব্রাউজারের তারিখ/সময় বাছাই */
function DateTimeFields({
  date, hour, minute, onDate, onTime,
}: { date: number; hour: number | null; minute: number | null; onDate: (d: number) => void; onTime: (h: number, m: number) => void }) {
  const dRef = useRef<HTMLInputElement>(null);
  const tRef = useRef<HTMLInputElement>(null);
  const open = (el: HTMLInputElement | null) => {
    if (!el) return;
    try {
      el.showPicker();
    } catch {
      el.focus();
      el.click();
    }
  };
  const today = todayUtcMidnight();
  return (
    <div className="blood-dt">
      <div className="blood-pick">
        <button type="button" className="btn outline has-icon blood-pickbtn" onClick={() => open(dRef.current)}>
          <Icon name="calendar_month" />
          <span className="ellipsis">{formatDate(date)}</span>
        </button>
        <input
          ref={dRef}
          type="date"
          tabIndex={-1}
          aria-label={tr('Date', 'তারিখ')}
          min={dateInputValue(today)}
          max={dateInputValue(today + 30 * DAY_MS)}
          value={dateInputValue(date)}
          onChange={(e) => {
            const v = fromDateInput(e.target.value);
            if (v != null) onDate(v);
          }}
        />
      </div>
      <div className="blood-pick">
        <button type="button" className="btn outline has-icon blood-pickbtn" onClick={() => open(tRef.current)}>
          <Icon name="schedule" />
          <span className="ellipsis">{hour != null && minute != null ? formatTimeHM(hour, minute) : tr('Time', 'সময়')}</span>
        </button>
        <input
          ref={tRef}
          type="time"
          tabIndex={-1}
          aria-label={tr('Time', 'সময়')}
          value={hour != null && minute != null ? `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` : ''}
          onChange={(e) => {
            const m = /^(\d{2}):(\d{2})/.exec(e.target.value);
            if (m) onTime(parseInt(m[1], 10), parseInt(m[2], 10));
          }}
        />
      </div>
    </div>
  );
}

function TravelFareSwitch({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return (
    <Switch
      checked={on}
      onChange={onChange}
      label={tr("I'll pay donors' travel cost", 'ডোনারের যাতায়াত খরচ দেব')}
      sub={tr('Travel cost only — buying or selling blood is not allowed. Donors farther away may need this.', 'শুধু যাতায়াত খরচ — রক্ত কেনাবেচা নিষিদ্ধ। দূরের ডোনারদের এটা লাগতে পারে।')}
    />
  );
}

interface Hospital { id: string; nameEn: string; nameBn: string; type: string; district: string; upazilaId: number | null; unionId: number | null }
const hospitalLabel = (h: Hospital) => (isBangla() && h.nameBn.trim() ? h.nameBn : h.nameEn);

/** হাসপাতালের ঘর: লিখতে থাকলে নিচে ওই জেলার হাসপাতালের নাম আসে, চাপলে বসে যায় */
function HospitalField({ value, onChange, district }: { value: string; onChange: (v: string) => void; district: string | null }) {
  const [focused, setFocused] = useState(false);
  const [picked, setPicked] = useState(false);
  const [adding, setAdding] = useState(false);
  const [added, setAdded] = useState<string | null>(null);
  const s = useSession();
  const hq = useQuery({
    queryKey: ['hospitals', district],
    queryFn: () => call<{ items: Hospital[] }>('hospitals', { district }).then((o) => o.items ?? []),
    enabled: !!district,
    staleTime: 10 * 60_000,
  });
  const q = value.trim();
  const matches = useMemo(() => {
    if (q.length < 2 || picked) return [];
    const ql = q.toLowerCase();
    const starts = (h: Hospital) => h.nameEn.toLowerCase().startsWith(ql) || h.nameBn.startsWith(q);
    return (hq.data ?? [])
      .filter((h) => h.nameEn.toLowerCase().includes(ql) || h.nameBn.includes(q))
      .sort((a, b) => Number(starts(b)) - Number(starts(a)))
      .slice(0, 6);
  }, [q, hq.data, picked]);
  return (
    <div className="col gap-4 blood-hosp">
      <div className="blood-hosp-input">
        <Icon name="local_hospital" className="blood-hosp-ic" />
        <TextInput
          label={tr('Hospital', 'হাসপাতাল')}
          value={value}
          maxLength={120}
          autoComplete="off"
          onChange={(e) => {
            onChange(e.target.value.slice(0, 120));
            setPicked(false);
          }}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
        />
      </div>
      {focused && matches.length ? (
        <div className="blood-suggest" role="listbox">
          {matches.map((h) => (
            <button
              key={h.id}
              type="button"
              role="option"
              aria-selected={false}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                onChange(hospitalLabel(h).slice(0, 120));
                setPicked(true);
                setFocused(false);
              }}
            >
              <span className="t-body-m semi">{hospitalLabel(h)}</span>
              {areaLabel(h.district, h.upazilaId, h.unionId) ? <span className="t-body-s muted">{areaLabel(h.district, h.upazilaId, h.unionId)}</span> : null}
            </button>
          ))}
        </div>
      ) : null}
      <div className="row gap-4">
        <span className="t-body-s muted">{tr('Not in the list?', 'তালিকায় নেই?')}</span>
        <Button variant="text" small onClick={() => s.requireLogin(tr('Log in to request a hospital', 'হাসপাতাল যোগের অনুরোধ করতে লগইন করুন'), () => setAdding(true))}>
          {tr('Request to add', 'যোগ করার অনুরোধ')}
        </Button>
      </div>
      <InfoText>{added}</InfoText>
      {adding ? (
        <AddHospitalDialog
          initialName={q}
          district={district}
          onExisting={(h) => {
            setAdding(false);
            onChange(hospitalLabel(h).slice(0, 120));
            setPicked(true);
          }}
          onSent={(m) => {
            setAdding(false);
            setAdded(m);
          }}
          onDismiss={() => setAdding(false)}
        />
      ) : null}
    </div>
  );
}

const HOSPITAL_TYPES = ['govt_mch', 'govt_specialized', 'govt_district', 'govt_uhc', 'govt_hospital', 'private_hospital', 'clinic', 'diagnostic'];
function typeLabel(t: string) {
  switch (t) {
    case 'govt_mch': return tr('Govt. medical college hospital', 'সরকারি মেডিকেল কলেজ হাসপাতাল');
    case 'govt_specialized': return tr('Govt. specialized hospital', 'সরকারি বিশেষায়িত হাসপাতাল');
    case 'govt_district': return tr('District / Sadar hospital', 'জেলা / সদর হাসপাতাল');
    case 'govt_uhc': return tr('Upazila Health Complex', 'উপজেলা স্বাস্থ্য কমপ্লেক্স');
    case 'govt_hospital': return tr('Other govt. hospital', 'অন্যান্য সরকারি হাসপাতাল');
    case 'private_hospital': return tr('Private hospital', 'বেসরকারি হাসপাতাল');
    case 'clinic': return tr('Clinic', 'ক্লিনিক');
    default: return tr('Diagnostic centre', 'ডায়াগনস্টিক সেন্টার');
  }
}

/** হাসপাতাল যোগের অনুরোধ (অ্যাপের AddHospitalDialog; জেলা = রিকোয়েস্টের এলাকা) */
function AddHospitalDialog({
  initialName, district, onExisting, onSent, onDismiss,
}: { initialName: string; district: string | null; onExisting: (h: Hospital) => void; onSent: (m: string) => void; onDismiss: () => void }) {
  const latin = /[A-Za-z]/.test(initialName);
  const [nameEn, setNameEn] = useState(latin ? initialName : '');
  const [nameBn, setNameBn] = useState(latin ? '' : initialName);
  const [type, setType] = useState('private_hospital');
  const [address, setAddress] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function send() {
    if (!district) return setErr(tr('Choose a district', 'জেলা বেছে নিন'));
    if (!/[A-Za-z]/.test(nameEn)) return setErr(tr('Enter the name in English', 'ইংরেজিতে নাম লিখুন'));
    setBusy(true);
    setErr(null);
    try {
      const r = await call<{ ok: boolean; merged?: boolean; duplicateOf?: Hospital }>('hospital_request', {
        nameEn: nameCase(nameEn), nameBn: nameBn.trim(), type, district, upazilaId: null, unionId: null, address: address.trim(), phone,
      });
      if (r.duplicateOf) onExisting(r.duplicateOf);
      else if (r.merged) onSent(tr('Someone already asked to add this hospital — your request was added to theirs.', 'এই হাসপাতালের অনুরোধ আগেই এসেছে — আপনারটাও তার সাথে যোগ হলো।'));
      else onSent(tr('Request sent. You can choose it after an admin approves.', 'অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে বেছে নিতে পারবেন।'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onDismiss()}
      title={tr('Add a hospital', 'হাসপাতাল যোগের অনুরোধ')}
      actions={
        <>
          <Button variant="text" onClick={onDismiss} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button busy={busy} onClick={send}>{tr('Send', 'পাঠান')}</Button>
        </>
      }
    >
      <p className="t-body-s muted">{tr('An admin checks it before it appears in the list.', 'অ্যাডমিন যাচাই করার পর তালিকায় আসবে।')}</p>
      {district ? (
        <div className="row gap-6 t-body-m"><Icon name="location_on" size={18} className="c-primary" />{districtLabel(district)}</div>
      ) : null}
      <TextInput label={tr('Name in English', 'ইংরেজিতে নাম')} value={nameEn} maxLength={120} onChange={(e) => setNameEn(e.target.value)} />
      <TextInput label={tr('Name in Bangla (optional)', 'বাংলায় নাম (ঐচ্ছিক)')} value={nameBn} maxLength={120} onChange={(e) => setNameBn(e.target.value)} />
      <Field label={tr('Type', 'ধরন')}>
        <ChoiceChips options={HOSPITAL_TYPES.map((t) => ({ value: t, label: typeLabel(t) }))} value={type} onChange={setType} />
      </Field>
      <TextInput label={tr('Address (optional)', 'ঠিকানা (ঐচ্ছিক)')} value={address} maxLength={200} onChange={(e) => setAddress(e.target.value)} />
      <TextInput
        label={tr('Phone (optional)', 'ফোন (ঐচ্ছিক)')}
        inputMode="tel"
        value={phone}
        onChange={(e) => setPhone(toAsciiDigits(e.target.value).replace(/[^0-9+]/g, '').slice(0, 15))}
      />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- নতুন রিকোয়েস্ট ----------

export function NewRequestPage() {
  const s = useSession();
  const title = tr('Create Request', 'রিকোয়েস্ট দিন');
  if (!s.ready || (!s.guest && !s.profileLoaded)) return <FormPage title={title} backTo="/"><Loading /></FormPage>;
  if (s.guest || !s.profile?.name.trim() || !s.profile?.phone) {
    return (
      <FormPage title={title} backTo="/">
        <LoginNeeded icon="bloodtype" reason={tr('Log in to request blood', 'রক্তের রিকোয়েস্ট দিতে লগইন করুন')} />
      </FormPage>
    );
  }
  return <NewRequestForm title={title} />;
}

function NewRequestForm({ title }: { title: string }) {
  const s = useSession();
  const nav = useNavigate();
  const invalidate = useInvalidate();
  const profile = s.profile!;
  const [group, setGroup] = useState('');
  const [bags, setBags] = useState(1);
  const [hospital, setHospital] = useState('');
  const [note, setNote] = useState('');
  const [neededBy, setNeededBy] = useState(todayUtcMidnight());
  const [hour, setHour] = useState<number | null>(null);
  const [minute, setMinute] = useState<number | null>(null);
  const [urgent, setUrgent] = useState(false);
  const [travelFare, setTravelFare] = useState(false);
  const [phone, setPhone] = useState(profile.phone);
  // প্রাইভেসি: রিকোয়েস্ট সবাই দেখে, তাই বাসার এলাকা আপনা থেকে বসানো হয় না
  const [place, setPlace] = useState<Place | null>(null);
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    const uid = s.uid;
    const contact = normalizePhone(phone);
    const err = !uid
      ? tr('Please log in again', 'আবার লগইন করুন')
      : !group
        ? tr("Choose the patient's blood group", 'রোগীর রক্তের গ্রুপ বেছে নিন')
        : !hospital.trim()
          ? tr('Enter the hospital', 'হাসপাতালের নাম লিখুন')
          : !place
            ? tr('Choose the area', 'এলাকা বেছে নিন')
            : neededBy < todayUtcMidnight()
              ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
              : hour == null || minute == null
                ? tr('Pick the time', 'সময় বেছে নিন')
                : dhakaMillis(neededBy, hour, minute) <= Date.now()
                  ? tr('That time has passed', 'এই সময় পার হয়ে গেছে')
                  : !contact
                    ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
                    : null;
    setError(err);
    if (err || !uid || !place || hour == null || minute == null || !contact) return;
    setBusy(true);
    try {
      const open = (await myRequests(uid)).filter((r) => isActive(r)).length;
      if (open >= MAX_OPEN_REQUESTS) {
        throw new Error(tr(`You can keep up to ${num(MAX_OPEN_REQUESTS)} open requests`, `একসাথে সর্বোচ্চ ${num(MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়`));
      }
      const lat = roundCoord(place.lat);
      const lng = roundCoord(place.lng);
      const id = await api.create({
        requesterName: profile.name.slice(0, 80),
        requesterPhone: contact,
        patientGroup: group,
        bags,
        hospital: nameCase(hospital).slice(0, 120),
        placeName: place.placeName,
        lat,
        lng,
        geohash: geohash(lat, lng),
        neededByMillis: neededBy,
        neededAtMillis: dhakaMillis(neededBy, hour, minute),
        urgent,
        travelFare,
        note: note.trim().slice(0, 300),
      });
      // আশেপাশের ডোনারদের জানানো; না গেলে রিকোয়েস্টের পাতায় আবার চেষ্টার বাটন থাকে
      await api.notify(id).catch(() => {});
      await invalidate();
      nav(`/my-requests/${id}`, { replace: true });
    } catch (e) {
      setError(errorText(e) || tr("Couldn't create the request", 'রিকোয়েস্ট দেওয়া যায়নি'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormPage title={title} backTo="/">
      <FieldLabel>{tr("Patient's blood group", 'রোগীর রক্তের গ্রুপ')}</FieldLabel>
      <div className="blood-groups">
        <ChoiceChips options={BLOOD_GROUPS.map((g) => ({ value: g, label: g }))} value={group || null} onChange={setGroup} />
      </div>

      <FieldLabel>{tr('Bags needed', 'কত ব্যাগ')}</FieldLabel>
      <BagsPicker bags={bags} onChange={setBags} />

      <HospitalField value={hospital} onChange={setHospital} district={place?.placeName ?? profile.placeName} />

      <button type="button" className="btn outline has-icon block" onClick={() => setPicking(true)}>
        <Icon name="location_on" />
        <span className="ellipsis">{place ? districtLabel(place.placeName) : tr('Choose area', 'এলাকা বেছে নিন')}</span>
      </button>

      <FieldLabel>{tr('Needed by', 'কবে লাগবে')}</FieldLabel>
      <DateTimeFields date={neededBy} hour={hour} minute={minute} onDate={setNeededBy} onTime={(h, m) => { setHour(h); setMinute(m); }} />

      <Switch checked={urgent} onChange={setUrgent} label={tr('Urgent', 'জরুরি')} />
      <TravelFareSwitch on={travelFare} onChange={setTravelFare} />

      <TextArea label={tr('Note (optional)', 'অতিরিক্ত তথ্য (ঐচ্ছিক)')} value={note} maxLength={300} rows={3} onChange={(e) => setNote(e.target.value.slice(0, 300))} />

      <TextInput
        label={tr('Contact number', 'যোগাযোগ নম্বর')}
        hint={tr("Donors who agreed call this number — it can be a family member's.", 'রাজি ডোনাররা এই নম্বরে ফোন করবেন — রোগীর লোকের নম্বরও দিতে পারেন।')}
        inputMode="tel"
        autoComplete="tel"
        value={phone}
        maxLength={20}
        onChange={(e) => setPhone(e.target.value)}
      />

      <ErrorText>{error}</ErrorText>
      <Button block icon="send" busy={busy} onClick={submit}>{title}</Button>

      {picking ? (
        <PlacePicker
          title={tr('Where is the patient?', 'রোগী কোথায় আছেন?')}
          onPicked={(p) => {
            setPlace(p);
            setPicking(false);
          }}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </FormPage>
  );
}

// ---------- রিকোয়েস্ট এডিট ----------

/** ব্যাগ, হাসপাতাল, তারিখ-সময়, জরুরি, যাতায়াত খরচ, অতিরিক্ত তথ্য আর যোগাযোগ নম্বর বদলানো যায়; গ্রুপ আর এলাকা নয় */
export function EditRequestPage({ id }: { id: string }) {
  const s = useSession();
  const title = tr('Edit Request', 'রিকোয়েস্ট এডিট');
  const q = useMyRequest(id);
  const back = `/my-requests/${id}`;
  if (!s.ready) return <FormPage title={title} backTo={back}><Loading /></FormPage>;
  if (s.guest) {
    return (
      <FormPage title={title} backTo={back}>
        <LoginNeeded reason={tr('Log in to see your requests', 'আপনার রিকোয়েস্ট দেখতে লগইন করুন')} />
      </FormPage>
    );
  }
  const r = q.data?.r;
  if (q.isLoading) return <FormPage title={title} backTo={back}><Loading /></FormPage>;
  if (!r || r.requesterUid !== s.uid || !isActive(r)) {
    return (
      <FormPage title={title} backTo={back}>
        <ErrorText>{!r ? tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি') : tr('This request is closed', 'রিকোয়েস্টটা বন্ধ হয়ে গেছে')}</ErrorText>
      </FormPage>
    );
  }
  return <EditRequestForm key={r.id} title={title} back={back} r={r} />;
}

function EditRequestForm({ title, back, r }: { title: string; back: string; r: BloodRequest }) {
  const nav = useNavigate();
  const invalidate = useInvalidate();
  const initial = r.neededAtMillis != null ? dhakaHourMinute(r.neededAtMillis) : null;
  const [bags, setBags] = useState(r.bags);
  const [hospital, setHospital] = useState(r.hospital);
  const [note, setNote] = useState(r.note);
  const [neededBy, setNeededBy] = useState(r.neededByMillis);
  const [hour, setHour] = useState<number | null>(initial?.[0] ?? null);
  const [minute, setMinute] = useState<number | null>(initial?.[1] ?? null);
  const [urgent, setUrgent] = useState(r.urgent);
  const [travelFare, setTravelFare] = useState(r.travelFare);
  const [savedPhone, setSavedPhone] = useState<string | null>(null);
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // যোগাযোগ নম্বর (রাজি ডোনাররা এতে ফোন করেন)
  useEffect(() => {
    requesterContact(r.id).then((c) => {
      if (c) {
        setSavedPhone(c.phone);
        setPhone(c.phone);
      }
    }, () => {});
  }, [r.id]);

  async function save() {
    const at = hour != null && minute != null ? dhakaMillis(neededBy, hour, minute) : null;
    const newPhone = phone.trim() && phone.trim() !== savedPhone ? phone.trim() : null;
    const err = !hospital.trim()
      ? tr('Enter the hospital', 'হাসপাতালের নাম লিখুন')
      : neededBy < todayUtcMidnight()
        ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
        : at == null
          ? tr('Pick the time', 'সময় বেছে নিন')
          : at !== r.neededAtMillis && at <= Date.now()
            ? tr('That time has passed', 'এই সময় পার হয়ে গেছে')
            : newPhone && !normalizePhone(newPhone)
              ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
              : null;
    setError(err);
    if (err || at == null) return;
    setBusy(true);
    try {
      const n = await api.edit({
        requestId: r.id,
        bags,
        hospital: nameCase(hospital),
        neededByMillis: neededBy,
        neededAtMillis: at,
        urgent,
        travelFare,
        note: note.trim(),
        ...(newPhone ? { contactPhone: normalizePhone(newPhone) } : {}),
      });
      await invalidate();
      const info = n > 0 ? tr(`Saved. ${num(n)} donor(s) were notified.`, `সেভ হয়েছে। রাজি থাকা ${num(n)} জন ডোনারকে জানানো হয়েছে।`) : tr('Saved.', 'সেভ হয়েছে।');
      nav(back, { replace: true, state: { info } });
    } catch (e) {
      setError(errorText(e) || tr("Couldn't save", 'সেভ হয়নি'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FormPage title={title} backTo={back}>
      <div className="row gap-12">
        <BloodGroupBadge group={r.patientGroup} size={44} />
        <div className="col gap-2">
          <span className="semi">{districtLabel(r.placeName)}</span>
          <span className="t-body-m muted">{tr("Group and area can't be changed", 'গ্রুপ আর এলাকা বদলানো যায় না')}</span>
        </div>
      </div>

      <FieldLabel>{tr('Bags needed', 'কত ব্যাগ')}</FieldLabel>
      <BagsPicker bags={bags} onChange={setBags} />

      <HospitalField value={hospital} onChange={setHospital} district={r.placeName || null} />

      <FieldLabel>{tr('Needed by', 'কবে লাগবে')}</FieldLabel>
      <DateTimeFields date={neededBy} hour={hour} minute={minute} onDate={setNeededBy} onTime={(h, m) => { setHour(h); setMinute(m); }} />

      <Switch checked={urgent} onChange={setUrgent} label={tr('Urgent', 'জরুরি')} />
      <TravelFareSwitch on={travelFare} onChange={setTravelFare} />

      <TextArea label={tr('Note (optional)', 'অতিরিক্ত তথ্য (ঐচ্ছিক)')} value={note} maxLength={300} rows={3} onChange={(e) => setNote(e.target.value.slice(0, 300))} />

      {savedPhone != null ? (
        <TextInput
          label={tr('Contact number', 'যোগাযোগ নম্বর')}
          hint={tr("Donors who agreed call this number — it can be a family member's.", 'রাজি ডোনাররা এই নম্বরে ফোন করবেন — রোগীর লোকের নম্বরও দিতে পারেন।')}
          inputMode="tel"
          value={phone}
          maxLength={20}
          onChange={(e) => setPhone(e.target.value)}
        />
      ) : null}

      <ErrorText>{error}</ErrorText>
      <Button block busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
    </FormPage>
  );
}
