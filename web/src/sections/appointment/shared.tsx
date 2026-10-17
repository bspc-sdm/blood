// অ্যাপয়েন্টমেন্ট অংশের ভেতরের ছোট উপাদান — অ্যাপের FilterSheet, AreaSheet, StarRatingDialog, ReasonReportDialog,
// HospitalPickerDialog, AddHospitalDialog, PhoneVerifyDialog, EmergencyNotice, CallStatsBlock-এর ওয়েব রূপ।
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Avatar, Button, ChoiceChips, Dialog, ErrorText, IconButton, InfoText, ListRow, Loading, TextArea, TextInput, cx } from '@/ui/kit';
import { AreaPicker } from '@/ui/AreaPicker';
import { Icon } from '@/ui/Icon';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel, areasReady, districtLabel, districtsSorted, ensureAreas, searchAreas, unionsOf, upazilasOf, type AreaHit } from '@/lib/geo';
import { HOSPITAL_TYPES, hospitalLabel, hospitalTypeLabel, similarHospitals, type HomeStats, type Hospital } from './model';

/** এলাকার তালিকা (উপজেলা/ইউনিয়নের নাম) আনা হলে আবার আঁকা */
export function useAreasReady() {
  const [ready, setReady] = useState(areasReady());
  useEffect(() => {
    if (!ready) ensureAreas().then(() => setReady(true), () => {});
  }, [ready]);
  return ready;
}

// ---------- ফিল্টার প্যানেল ----------
export function FilterSheet({ title, onClose, onClear, children }: { title: string; onClose: () => void; onClear?: () => void; children: ReactNode }) {
  return (
    <Dialog
      onClose={onClose}
      title={title}
      actions={
        <>
          {onClear ? <Button variant="outline" onClick={onClear}>{tr('Clear all', 'সব মুছুন')}</Button> : null}
          <Button onClick={onClose}>{tr('Show', 'দেখান')}</Button>
        </>
      }
    >
      {children}
    </Dialog>
  );
}
export function FilterChoice({ title, options, value, label, onChange }: { title: string; options: string[]; value: string | null; label: (c: string) => string; onChange: (v: string | null) => void }) {
  return (
    <div className="col gap-6">
      <span className="t-title-s">{title}</span>
      <ChoiceChips options={options.map((o) => ({ value: o, label: label(o) }))} value={value} onChange={(v: string) => onChange(v === value ? null : v)} />
    </div>
  );
}

// ---------- এলাকা (অ্যাপের AreaSheet: আমার কাছে · সব জেলা · জেলা › উপজেলা › ইউনিয়ন) ----------
export interface AreaSel { district: string | null; upazilaId: number | null; unionId: number | null }
export function AreaSheet({
  value, onPick, onClose, allowAll, withUnions, nearMe, onNearMe,
}: {
  value: AreaSel; onPick: (v: AreaSel) => void; onClose: () => void; allowAll?: boolean; withUnions?: boolean;
  /** null/undefined = "আমার কাছে" দেখানো হয় না */
  nearMe?: boolean | null; onNearMe?: () => void;
}) {
  const ready = useAreasReady();
  const [q, setQ] = useState('');
  const [dist, setDist] = useState<string | null>(null);
  const [upz, setUpz] = useState<number | null>(null);
  const hits: AreaHit[] = useMemo(() => (ready && q.trim() ? searchAreas(q, !!withUnions) : []), [q, ready, withUnions]);
  const sel = (d: string | null, u: number | null, n: number | null) => nearMe !== true && value.district === d && value.upazilaId === u && value.unionId === n;
  const check = (on: boolean) => (on ? <Icon name="check_circle" className="c-primary" style={{ width: 20, height: 20 }} /> : <span />);
  const pick = (d: string | null, u: number | null, n: number | null) => onPick({ district: d, upazilaId: u, unionId: n });

  return (
    <Dialog onClose={onClose} title={tr('Choose area', 'এলাকা বেছে নিন')}>
      <input
        className="input"
        placeholder={withUnions ? tr('District, upazila or union', 'জেলা, উপজেলা বা ইউনিয়ন লিখুন') : tr('District or upazila', 'জেলা বা উপজেলা লিখুন')}
        value={q}
        onChange={(e) => setQ(e.target.value)}
        aria-label={tr('Search area', 'এলাকা খুঁজুন')}
      />
      <div className="col gap-2 appt-pick-list">
        {q.trim() ? (
          <>
            {!ready ? <Loading /> : null}
            {hits.map((h) => (
              <ListRow
                key={`${h.district}-${h.upazilaId}-${h.unionId}`}
                icon="location_on"
                title={areaLabel(h.district, h.upazilaId, h.unionId)}
                sub={h.unionId != null ? tr('Union', 'ইউনিয়ন') : h.upazilaId != null ? tr('Upazila', 'উপজেলা') : tr('District', 'জেলা')}
                onClick={() => pick(h.district, h.upazilaId, h.unionId)}
                trailing={check(sel(h.district, h.upazilaId, h.unionId))}
              />
            ))}
            {ready && !hits.length ? <p className="t-body-m muted" style={{ padding: 16 }}>{tr('No area found', 'কোনো এলাকা পাওয়া যায়নি')}</p> : null}
          </>
        ) : !dist ? (
          <>
            {nearMe != null && onNearMe ? (
              <ListRow icon="location_on" title={tr('My current location', 'আমার বর্তমান লোকেশন')} sub={tr('Nearest first', 'কাছেরগুলো আগে')} onClick={onNearMe} trailing={check(nearMe === true)} />
            ) : null}
            {allowAll ? <ListRow icon="map" title={tr('All districts', 'সব জেলা')} onClick={() => pick(null, null, null)} trailing={check(sel(null, null, null))} /> : null}
            {districtsSorted().map((d) => (
              <ListRow key={d.en} title={isBangla() ? d.bn : d.en} onClick={() => { setDist(d.bn); setUpz(null); }} trailing={value.district === d.bn && nearMe !== true ? <Icon name="check_circle" className="c-primary" style={{ width: 20, height: 20 }} /> : undefined} />
            ))}
          </>
        ) : upz == null ? (
          <>
            <ListRow icon="arrow_back" title={tr('District', 'জেলা')} onClick={() => setDist(null)} trailing={<span />} />
            <ListRow icon="location_on" title={tr('Whole district', 'পুরো জেলা') + ' · ' + districtLabel(dist)} onClick={() => pick(dist, null, null)} trailing={check(sel(dist, null, null))} />
            {!ready ? <Loading /> : upazilasOf(dist).map((u) => (
              <ListRow key={u.id} title={isBangla() ? u.bn : u.en} onClick={() => (withUnions ? setUpz(u.id) : pick(dist, u.id, null))} trailing={withUnions ? undefined : check(sel(dist, u.id, null))} />
            ))}
          </>
        ) : (
          <>
            <ListRow icon="arrow_back" title={areaLabel(dist)} onClick={() => setUpz(null)} trailing={<span />} />
            <ListRow icon="location_on" title={tr('All unions', 'সব ইউনিয়ন') + ' · ' + areaLabel(dist, upz)} onClick={() => pick(dist, upz, null)} trailing={check(sel(dist, upz, null))} />
            {unionsOf(upz).map((n) => (
              <ListRow key={n.id} title={isBangla() ? n.bn : n.en} onClick={() => pick(dist, upz, n.id)} trailing={check(sel(dist, upz, n.id))} />
            ))}
          </>
        )}
      </div>
    </Dialog>
  );
}

// ---------- রেটিং আর রিপোর্ট ----------
export function StarRatingDialog({ title, onClose, onSubmit }: { title: string; onClose: () => void; onSubmit: (stars: number, comment: string) => Promise<void> }) {
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={title}
      size="small"
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Later', 'পরে')}</Button>
          <Button
            busy={busy}
            disabled={stars === 0}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await onSubmit(stars, comment.trim());
              } catch (e) {
                setErr(errorText(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            {tr('Submit', 'জমা দিন')}
          </Button>
        </>
      }
    >
      <div className="row appt-stars" role="radiogroup" aria-label={tr('Rating', 'রেটিং')}>
        {[1, 2, 3, 4, 5].map((i) => (
          <IconButton key={i} icon={i <= stars ? 'star_fill' : 'star'} label={num(i)} onClick={() => setStars(i)} aria-pressed={i <= stars} />
        ))}
      </div>
      <TextArea label={tr('Comment (optional)', 'মন্তব্য (ঐচ্ছিক)')} rows={2} value={comment} onChange={(e) => setComment(e.target.value.slice(0, 300))} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

export function ReasonReportDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Report a problem', 'সমস্যা জানান')}
      size="small"
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          <Button
            busy={busy}
            disabled={!reason.trim()}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await onSubmit(reason.trim());
              } catch (e) {
                setErr(errorText(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            {tr('Report', 'রিপোর্ট')}
          </Button>
        </>
      }
    >
      <p className="t-body-m">{tr("Wrong number, overcharging, didn't come…", 'ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…')}</p>
      <TextArea label={tr('What happened', 'কী হয়েছে')} rows={3} value={reason} onChange={(e) => setReason(e.target.value.slice(0, 300))} />
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- জরুরি সতর্কতা ----------
export function EmergencyNotice({ k = 'health' }: { k?: string }) {
  const key = `lf.dismissed.999_${k}`;
  const [hidden, setHidden] = useState(() => store.get(key) === '1');
  if (hidden) return null;
  return (
    <div className="card tonal row top appt-notice">
      <Icon name="info" />
      <p className="t-body-s grow">{tr('Emergency? Call 999 now. Answers here are general guidance, not a diagnosis.', 'জরুরি অবস্থায় এখনই ৯৯৯-এ ফোন করুন। এখানের উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।')}</p>
      <IconButton
        icon="close"
        small
        label={tr('Hide', 'সরান')}
        onClick={() => {
          store.set(key, '1');
          setHidden(true);
        }}
      />
    </div>
  );
}

// ---------- বিস্তারিত: মাথা আর ভাগ (অ্যাপের DetailSheet) ----------
export function DetailHead({ title, icon, photo, subtitle, meta, children }: { title: string; icon: string; photo?: string | null; subtitle?: string | null; meta?: string | null; children?: ReactNode }) {
  return (
    <div className="row top gap-16 appt-head">
      <Avatar photo={photo} icon={icon} size={72} />
      <div className="col gap-2 grow">
        <h2 className="t-title-l bold">{title}</h2>
        {subtitle ? <span className="t-body-m c-primary">{subtitle}</span> : null}
        {meta ? <span className="t-body-m muted">{meta}</span> : null}
        {children}
      </div>
    </div>
  );
}
export function DetailBlock({ title, lines, children }: { title: string; lines: [string, string | null | undefined][]; children?: ReactNode }) {
  const shown = lines.filter((l) => l[1]);
  if (!shown.length && !children) return null;
  return (
    <section className="col appt-block">
      <h3 className="t-title-s semi">{title}</h3>
      {shown.map(([ic, text], i) => (
        <div key={i} className="row top appt-line">
          <Icon name={ic} />
          <span className="t-body-l pre">{text}</span>
        </div>
      ))}
      {children ? <div className="row wrap">{children}</div> : null}
    </section>
  );
}

// ---------- ফোন যাচাই ----------
export function PhoneVerifyDialog({ onClose, onVerified }: { onClose: () => void; onVerified: () => void }) {
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function send() {
    setBusy(true);
    setErr(null);
    try {
      const r = await call<{ to?: string }>('phone_send_code');
      setSentTo(r.to || '');
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function verify() {
    setBusy(true);
    setErr(null);
    try {
      await call('phone_verify_code', { code });
      onVerified();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      onClose={() => !busy && onClose()}
      title={tr('Verify your phone', 'ফোন নম্বর যাচাই')}
      size="small"
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
          {sentTo == null ? (
            <Button busy={busy} onClick={send}>{tr('Send code', 'কোড পাঠান')}</Button>
          ) : (
            <Button busy={busy} disabled={code.length !== 6} onClick={verify}>{tr('Verify', 'যাচাই করুন')}</Button>
          )}
        </>
      }
    >
      <p className="t-body-m">{tr('A nurse will come to a home, so we check that the phone number in your profile is really yours.', 'নার্স বাসায় যাবেন, তাই প্রোফাইলের নম্বরটা সত্যিই আপনার কি না যাচাই করা হয়।')}</p>
      {sentTo != null ? (
        <>
          <p className="t-body-s">{tr('Code sent to ', 'কোড পাঠানো হয়েছে: ') + num(sentTo)}</p>
          <TextInput label={tr('6-digit code', '৬ সংখ্যার কোড')} inputMode="numeric" value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))} />
          <Button variant="text" onClick={send} disabled={busy}>{tr('Send again', 'আবার পাঠান')}</Button>
        </>
      ) : null}
      <ErrorText>{err}</ErrorText>
    </Dialog>
  );
}

// ---------- হাসপাতাল বাছাই ----------
export function HospitalPickerDialog({ initialDistrict, initialUpazilaId, onPick, onClose }: { initialDistrict: string | null; initialUpazilaId?: number | null; onPick: (h: Hospital) => void; onClose: () => void }) {
  const { requireLogin } = useSession();
  useAreasReady();
  const [district, setDistrict] = useState<string | null>(initialDistrict);
  const [upazilaId, setUpazilaId] = useState<number | null>(initialUpazilaId ?? null);
  const [q, setQ] = useState('');
  const [area, setArea] = useState(false);
  const [adding, setAdding] = useState(false);
  const [info, setInfo] = useState<string | null>(null);
  const hq = useCall<{ items: Hospital[] }>('hospitals', { district }, { enabled: !!district });
  const list = hq.data?.items ?? [];
  const shown = useMemo(() => {
    const inArea = list.filter((h) => upazilaId == null || h.upazilaId === upazilaId || h.upazilaId == null);
    const t = q.trim();
    if (!t) return inArea;
    const direct = inArea.filter((h) => h.nameEn.toLowerCase().includes(t.toLowerCase()) || h.nameBn.includes(t));
    return [...new Set([...direct, ...similarHospitals(t, inArea, 10)])];
  }, [list, upazilaId, q]);

  return (
    <>
      <Dialog onClose={onClose} title={tr('Choose hospital', 'হাসপাতাল বেছে নিন')}>
        <button type="button" className="area-btn" onClick={() => setArea(true)}>
          <Icon name="location_on" />
          <span>{district ? areaLabel(district, upazilaId) : tr('District', 'জেলা')}</span>
          <Icon name="expand_more_round" />
        </button>
        <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder={tr('Hospital / clinic name', 'হাসপাতাল / ক্লিনিকের নাম')} aria-label={tr('Hospital / clinic name', 'হাসপাতাল / ক্লিনিকের নাম')} />
        {district ? (
          <div className="row">
            <span className="t-body-s muted">{tr('Not in the list?', 'তালিকায় নেই?')}</span>
            <Button variant="text" small onClick={() => requireLogin(tr('Log in to request a hospital', 'হাসপাতাল যোগের অনুরোধ করতে লগইন করুন'), () => setAdding(true))}>
              {tr('Request to add', 'যোগ করার অনুরোধ')}
            </Button>
          </div>
        ) : null}
        <InfoText>{info}</InfoText>
        <ErrorText>{hq.error ? errorText(hq.error) : null}</ErrorText>
        <div className="col gap-2 appt-pick-list">
          {!district ? (
            <p className="t-body-m" style={{ padding: 12 }}>{tr('Choose the district first', 'আগে জেলা বেছে নিন')}</p>
          ) : hq.isLoading ? (
            <Loading />
          ) : (
            shown.slice(0, 300).map((h) => (
              <ListRow
                key={h.id}
                icon="local_hospital"
                title={hospitalLabel(h)}
                sub={[hospitalTypeLabel(h.type), areaLabel(null, h.upazilaId)].filter(Boolean).join(' · ')}
                onClick={() => onPick(h)}
                trailing={<span />}
              />
            ))
          )}
        </div>
      </Dialog>
      {area ? (
        <AreaPicker
          value={district ? { district, upazilaId } : null}
          onPicked={(v) => {
            setDistrict(v.district);
            setUpazilaId(v.upazilaId);
            setArea(false);
          }}
          onClose={() => setArea(false)}
        />
      ) : null}
      {adding ? (
        <AddHospitalDialog
          initialName={q}
          initialDistrict={district}
          initialUpazilaId={upazilaId}
          onExisting={(h) => {
            setAdding(false);
            onPick(h);
          }}
          onSent={(m) => {
            setAdding(false);
            setInfo(m);
          }}
          onClose={() => setAdding(false)}
        />
      ) : null}
    </>
  );
}

/** হাসপাতাল যোগের অনুরোধ: লেখার সময়ই মিলে যাওয়া হাসপাতাল দেখায়, যাতে একই হাসপাতাল দুবার না আসে */
export function AddHospitalDialog({
  initialName, initialDistrict, initialUpazilaId, onExisting, onSent, onClose,
}: { initialName: string; initialDistrict: string | null; initialUpazilaId: number | null; onExisting: (h: Hospital) => void; onSent: (msg: string) => void; onClose: () => void }) {
  const latin = /[A-Za-z]/.test(initialName);
  const [nameEn, setNameEn] = useState(latin ? initialName : '');
  const [nameBn, setNameBn] = useState(latin ? '' : initialName);
  const [type, setType] = useState('private_hospital');
  const [area, setArea] = useState<{ district: string | null; upazilaId: number | null; unionId: number | null }>({ district: initialDistrict, upazilaId: initialUpazilaId, unionId: null });
  const [address, setAddress] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [existing, setExisting] = useState<Hospital | null>(null);
  const [picking, setPicking] = useState(false);
  const hq = useCall<{ items: Hospital[] }>('hospitals', { district: area.district }, { enabled: !!area.district });
  const inArea = (hq.data?.items ?? []).filter((h) => area.upazilaId == null || h.upazilaId === area.upazilaId || h.upazilaId == null);
  const suggestions = [...new Set([...similarHospitals(nameEn, inArea, 4), ...(nameBn.trim().length >= 3 ? inArea.filter((h) => h.nameBn.includes(nameBn.trim())).slice(0, 3) : [])])];

  async function send() {
    if (!area.district) return setErr(tr('Choose a district', 'জেলা বেছে নিন'));
    if (!/[A-Za-z]/.test(nameEn)) return setErr(tr('Enter the name in English', 'ইংরেজিতে নাম লিখুন'));
    setBusy(true);
    setErr(null);
    try {
      const title = nameEn.trim().replace(/\s+/g, ' ').replace(/\b([a-z])/g, (m) => m.toUpperCase());
      const r = await call<{ ok?: boolean; merged?: boolean; duplicateOf?: Hospital }>('hospital_request', {
        nameEn: title, nameBn: nameBn.trim(), type, district: area.district, upazilaId: area.upazilaId, unionId: area.unionId, address: address.trim(), phone,
      });
      if (r.duplicateOf) setExisting(r.duplicateOf);
      else if (r.merged) onSent(tr('Someone already asked to add this hospital — your request was added to theirs.', 'এই হাসপাতালের অনুরোধ আগেই এসেছে — আপনারটাও তার সাথে যোগ হলো।'));
      else onSent(tr('Request sent. You can choose it after an admin approves.', 'অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে বেছে নিতে পারবেন।'));
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Dialog
        onClose={() => !busy && onClose()}
        title={tr('Add a hospital', 'হাসপাতাল যোগের অনুরোধ')}
        actions={
          <>
            <Button variant="text" onClick={onClose} disabled={busy}>{tr('Cancel', 'বাতিল')}</Button>
            <Button busy={busy} onClick={send}>{tr('Send', 'পাঠান')}</Button>
          </>
        }
      >
        <p className="t-body-s muted">{tr('An admin checks it before it appears in the list.', 'অ্যাডমিন যাচাই করার পর তালিকায় আসবে।')}</p>
        <button type="button" className="area-btn" onClick={() => setPicking(true)}>
          <Icon name="location_on" />
          <span>{area.district ? areaLabel(area.district, area.upazilaId, area.unionId) : tr('Choose a district', 'জেলা বেছে নিন')}</span>
          <Icon name="expand_more_round" />
        </button>
        <TextInput label={tr('Name in English', 'ইংরেজিতে নাম')} value={nameEn} onChange={(e) => setNameEn(e.target.value.slice(0, 120))} />
        <TextInput label={tr('Name in Bangla (optional)', 'বাংলায় নাম (ঐচ্ছিক)')} value={nameBn} onChange={(e) => setNameBn(e.target.value.slice(0, 120))} />
        {suggestions.length ? (
          <div className="card tonal col gap-4">
            <span className="t-label-l">{tr('Is it one of these? Tap to choose.', 'এর কোনোটা কি? থাকলে ট্যাপ করুন।')}</span>
            {suggestions.map((h) => (
              <button key={h.id} type="button" className="appt-link-row" onClick={() => onExisting(h)}>• {hospitalLabel(h)}</button>
            ))}
          </div>
        ) : null}
        <div className="col gap-6">
          <span className="t-label-l">{tr('Type', 'ধরন')}</span>
          <ChoiceChips options={HOSPITAL_TYPES.map((t) => ({ value: t, label: hospitalTypeLabel(t) }))} value={type} onChange={setType} />
        </div>
        <TextInput label={tr('Address (optional)', 'ঠিকানা (ঐচ্ছিক)')} value={address} onChange={(e) => setAddress(e.target.value.slice(0, 200))} />
        <TextInput label={tr('Phone (optional)', 'ফোন (ঐচ্ছিক)')} inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value.replace(/[^0-9+]/g, '').slice(0, 15))} />
        <ErrorText>{err}</ErrorText>
      </Dialog>
      {picking ? (
        <AreaPicker
          withUnions
          value={area.district ? { district: area.district, upazilaId: area.upazilaId, unionId: area.unionId } : null}
          onPicked={(v) => {
            setArea({ district: v.district, upazilaId: v.upazilaId, unionId: v.unionId ?? null });
            setPicking(false);
          }}
          onClose={() => setPicking(false)}
        />
      ) : null}
      {existing ? (
        <Dialog
          onClose={() => setExisting(null)}
          size="small"
          title={tr('Already in the list', 'তালিকায় আগে থেকেই আছে')}
          actions={
            <>
              <Button variant="text" onClick={() => setExisting(null)}>{tr('Back', 'ফিরে যান')}</Button>
              <Button onClick={() => onExisting(existing)}>{tr('Choose this', 'এটাই বেছে নিন')}</Button>
            </>
          }
        >
          <p className="t-body-m">{hospitalLabel(existing)}</p>
          <p className="t-body-s muted">{areaLabel(existing.district, existing.upazilaId)}</p>
        </Dialog>
      ) : null}
    </>
  );
}

// ---------- নিজের হিসাব: অ্যাপ থেকে ফোন, রেটিং, মন্তব্য ----------
export function MiniBars({ values }: { values: number[] }) {
  const max = Math.max(1, ...values);
  return (
    <div className="col gap-4">
      <div className="appt-bars" role="img" aria-label={tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')}>
        {values.map((v, i) => (
          <span key={i} title={num(v)} style={{ height: v > 0 ? `${Math.max(4, (v / max) * 100)}%` : 0 }} />
        ))}
      </div>
      <div className="row t-label-s muted">
        <span className="grow">{tr('30 days ago', '৩০ দিন আগে')}</span>
        <span>{tr('Today', 'আজ')}</span>
      </div>
      <span className="t-body-s muted">{tr(`Highest in a day: ${Math.max(0, ...values)}`, `এক দিনে সর্বোচ্চ: ${num(Math.max(0, ...values))}`)}</span>
    </div>
  );
}

export function StatTile({ value, label, className }: { value: ReactNode; label: string; className?: string }) {
  return (
    <div className={cx('stat', className)}>
      <span className="v">{value}</span>
      <span className="l">{label}</span>
    </div>
  );
}

/** রেটিং লেখা: "৪.৫ (১২)" বা "—" */
export function ratingValue(st: Pick<HomeStats, 'rating' | 'ratingCount'>) {
  return st.ratingCount > 0 ? `${num(st.rating.toFixed(1))} (${num(st.ratingCount)})` : '—';
}
