// /work/ambulance — নিজের অ্যাম্বুলেন্স: তালিকা, খালি/ব্যস্ত, হিসাব, যোগ/বদল/সরানো (অ্যাপের MyAmbulancesSection)
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, ChoiceChips, ConfirmDialog, ErrorText, Field, IconButton, InfoText, Tag, TextInput, useNow } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel, InaccuratePosition, precisePosition } from '@/lib/geo';
import { timeAgo } from '@/lib/format';
import type { AreaValue } from '@/ui/AreaPicker';
import {
  AppealButton, BizPage, AreaField, DocPhotosField, DocsNeededNotice, Flash, MAX_AMBULANCES, PAPERS_CHANGED, Stat, StatsDialog, StatusReason, StatusTag, TermsCheck,
  ambulanceDocs, callsLine, isContactPhone, missingDocs, myBusinessTitle, phoneInput, sentForApproval, useAfterChange, useAreas, useCatalog, useFlash, useWorkGate,
  type Ambulance, type ListingStats,
  WorkEmpty,
} from './shared';

/** "খালি আছি"-র মেয়াদ শেষ হতে আর কত মিনিট (এক ঘণ্টার কম হলে), নইলে null — মেয়াদ পেরোলে null */
function minutesLeft(a: Ambulance, now: number): number | null {
  if (a.status !== 'approved' || !a.available) return null;
  const until = a.availableUntil || (a.availableAt > 1 ? a.availableAt + 12 * 3600 * 1000 : 0);
  const left = until - now;
  return left > 0 && left < 3600 * 1000 ? Math.max(1, Math.ceil(left / 60000)) : null;
}

/**
 * মেয়াদের শেষ ঘণ্টায়: "খালি আছে — আর ৪৫ মিনিট" আর এক চাপে আবার ১২ ঘণ্টা ("এখনো খালি আছেন?" পুশ এখানেই আনে)।
 * পাতা খোলা থাকলেও প্রতি ৩০ সেকেন্ডে মিনিট কমে; মেয়াদ পেরোলে লুকায়।
 */
function StillAvailable({ a, busy, onRenew }: { a: Ambulance; busy: boolean; onRenew: () => void }) {
  const now = useNow(30 * 1000);
  const left = minutesLeft(a, now);
  if (left == null) return null;
  return (
    <div className="work-still">
      <span className="t-body-m semi grow">{tr(`Available — ${left} min left`, `খালি আছে — আর ${num(left)} মিনিট`)}</span>
      <Button small busy={busy} onClick={onRenew}>{tr('Keep available', 'আবার চালু রাখুন')}</Button>
    </div>
  );
}

export function MyAmbulances() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const wide = useMedia('(min-width: 1000px)');
  const list = s.work.ambulances as Ambulance[];
  const stats = useCall<{ items: ListingStats[] }>('ambulance_stats', {}, { enabled: !gate && list.length > 0 });
  const cat = useCatalog('ambulanceType');
  const after = useAfterChange();
  const [flash, setFlash] = useFlash();
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [del, setDel] = useState<Ambulance | null>(null);
  const [delBusy, setDelBusy] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);
  const [details, setDetails] = useState<Ambulance | null>(null);
  useAreas();
  const title = myBusinessTitle('ambulance');
  if (gate) return <BizPage title={title}>{gate}</BizPage>;

  const st = new Map((stats.data?.items ?? []).map((x) => [x.id, x]));
  const canAdd = list.length < MAX_AMBULANCES;

  async function toggle(a: Ambulance, on: boolean) {
    setBusy(a.id);
    setErr(null);
    setFlash(null);
    try {
      await call('ambulance_available', { id: a.id, available: on });
      await after();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(null);
    }
  }
  async function remove() {
    if (!del) return;
    setDelBusy(true);
    setDelErr(null);
    try {
      await call('ambulance_delete', { id: del.id });
      await after();
      setDel(null);
      setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
    } catch (e) {
      setDelErr(errorText(e));
    } finally {
      setDelBusy(false);
    }
  }

  const types = (a: Ambulance) => a.types.map(cat.label).join(' · ');
  const area = (a: Ambulance) => [areaLabel(a.district, a.upazilaId), a.area].filter(Boolean).join(' · ');
  const avail = (a: Ambulance) =>
    a.status === 'approved' ? (
      <label className="work-avail">
        <input type="checkbox" role="switch" className="switch" checked={a.available} disabled={busy === a.id} onChange={(e) => toggle(a, e.target.checked)} />
        <span className="t-body-m">{a.available ? tr('Available now', 'এখন খালি আছি') : tr('On a trip / busy', 'ট্রিপে আছি / ব্যস্ত')}</span>
      </label>
    ) : <span className="muted">—</span>;
  const actions = (a: Ambulance) => (
    <div className="row gap-4 work-row-actions">
      <IconButton icon="edit" label={tr('Edit', 'বদলান')} onClick={() => nav(`/work/ambulance/${a.id}`)} />
      {a.status !== 'suspended' ? <IconButton icon="delete" label={tr('Remove this ambulance', 'এই অ্যাম্বুলেন্স সরিয়ে দিন')} onClick={() => { setDelErr(null); setDel(a); }} /> : null}
    </div>
  );
  const callsCell = (a: Ambulance) => {
    const x = st.get(a.id);
    return (
      <div className="col gap-2">
        <span className="num">{x ? callsLine(x) : '—'}</span>
        {x ? <button type="button" className="work-link" onClick={() => setDetails(a)}>{tr('Details', 'বিস্তারিত')}</button> : null}
      </div>
    );
  };

  const live = list.filter((a) => a.status === 'approved');
  return (
    <BizPage
      title={title}
      actions={canAdd ? <Button icon="add" onClick={() => nav('/work/ambulance/new')}>{tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন')}</Button> : null}
    >
      <Flash msg={flash} error={err} />
      {list.length ? (
        <div className="stats work-stats">
          <Stat icon="ambulance" value={num(list.length)} label={tr('Ambulances', 'অ্যাম্বুলেন্স')} />
          <Stat icon="check_circle" value={num(live.filter((a) => a.available).length)} label={tr('Available now', 'এখন খালি')} />
          <Stat icon="schedule" value={num(list.filter((a) => a.status === 'pending').length)} label={tr('Under review', 'যাচাই চলছে')} />
          <Stat icon="call" value={stats.data ? num((stats.data.items ?? []).reduce((n, x) => n + x.calls[30], 0)) : '—'} label={tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')} />
        </div>
      ) : null}
      {list.map((a) => (
        <DocsNeededNotice key={a.id} kind="ambulance" id={a.id} specs={ambulanceDocs()} item={a} onDone={after} title={`${a.name} · ${a.vehicleNo}`} />
      ))}

      {!list.length ? (
        <WorkEmpty icon="ambulance" title={tr('No ambulance added yet', 'এখনো কোনো অ্যাম্বুলেন্স যোগ করা হয়নি')}>
          <Button icon="add" onClick={() => nav('/work/ambulance/new')}>{tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন')}</Button>
        </WorkEmpty>
      ) : wide ? (
        <div className="table-wrap">
          <table className="table work-table">
            <thead>
              <tr>
                <th>{tr('Vehicle', 'গাড়ি')}</th>
                <th>{tr('Type', 'ধরন')}</th>
                <th>{tr('Area', 'এলাকা')}</th>
                <th>{tr('Status', 'অবস্থা')}</th>
                <th>{tr('Right now', 'এখনকার অবস্থা')}</th>
                <th>{tr('Calls from the app', 'অ্যাপ থেকে ফোন')}</th>
                <th aria-label={tr('Actions', 'কাজ')} />
              </tr>
            </thead>
            <tbody>
              {list.map((a) => (
                <tr key={a.id}>
                  <td>
                    <div className="col gap-2">
                      <span className="semi">{a.name}</span>
                      <span className="t-body-s muted">{a.vehicleNo}</span>
                    </div>
                  </td>
                  <td>{types(a)}</td>
                  <td className="work-td-area">{area(a)}</td>
                  <td>
                    <div className="col gap-4">
                      <StatusTag status={a.status} />
                      <StatusReason status={a.status} reason={a.statusReason} />
                      {a.status === 'rejected' || a.status === 'suspended' ? <AppealButton category="ambulance" /> : null}
                    </div>
                  </td>
                  <td>
                    <div className="col gap-2">
                      {avail(a)}
                      {a.status === 'approved' && a.available && a.availableAt > 1 ? <span className="t-body-s muted">{tr('Marked available ', 'খালি জানিয়েছেন ') + timeAgo(a.availableAt)}</span> : null}
                      <StillAvailable a={a} busy={busy === a.id} onRenew={() => toggle(a, true)} />
                    </div>
                  </td>
                  <td>{callsCell(a)}</td>
                  <td>{actions(a)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="work-cards">
          {list.map((a) => (
            <article key={a.id} className="card col gap-8">
              <div className="row top">
                <div className="col gap-2 grow">
                  <span className="t-title-m">{a.name}</span>
                  <span className="t-body-s muted">{a.vehicleNo}</span>
                </div>
                <StatusTag status={a.status} />
              </div>
              <StatusReason status={a.status} reason={a.statusReason} />
              <div className="row wrap gap-6">{a.types.map((t) => <Tag key={t} tone="primary">{cat.label(t)}</Tag>)}</div>
              {area(a) ? <span className="icon-line"><Icon name="location_on" /><span>{area(a)}</span></span> : null}
              {a.fare ? <span className="icon-line"><Icon name="payments" /><span>{tr('Fare: ', 'ভাড়া: ') + a.fare}</span></span> : null}
              {a.status === 'approved' ? (
                <div className="col gap-2">
                  {avail(a)}
                  <StillAvailable a={a} busy={busy === a.id} onRenew={() => toggle(a, true)} />
                  <span className="t-body-s muted">{tr('Turn off when you go on a trip.', 'ট্রিপে গেলে বন্ধ করুন।')}</span>
                </div>
              ) : null}
              <div className="row">
                <span className="icon-line grow"><Icon name="call" /><span>{tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(st.get(a.id))}</span></span>
                {st.get(a.id) ? <Button variant="text" small onClick={() => setDetails(a)}>{tr('Details', 'বিস্তারিত')}</Button> : null}
              </div>
              <div className="row wrap gap-8">
                {a.status === 'rejected' || a.status === 'suspended' ? <AppealButton category="ambulance" /> : null}
                <span className="grow" />
                {actions(a)}
              </div>
            </article>
          ))}
        </div>
      )}
      {wide && live.length ? <p className="t-body-s muted">{tr('Turn off when you go on a trip.', 'ট্রিপে গেলে বন্ধ করুন।')}</p> : null}

      {del ? (
        <ConfirmDialog
          title={tr('Remove this ambulance?', 'এই অ্যাম্বুলেন্স সরিয়ে দেবেন?')}
          text={`${del.name} · ${del.vehicleNo}`}
          confirm={tr('Remove', 'মুছে ফেলুন')}
          danger
          busy={delBusy}
          error={delErr}
          onConfirm={remove}
          onClose={() => setDel(null)}
        />
      ) : null}
      {details && st.get(details.id) ? <StatsDialog title={`${details.name} · ${details.vehicleNo}`} st={st.get(details.id)!} onClose={() => setDetails(null)} /> : null}
    </BizPage>
  );
}

export function AmbulanceForm({ id }: { id: string | null }) {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const existing = id ? (s.work.ambulances as Ambulance[]).find((a) => a.id === id) ?? null : null;
  const title = existing ? tr('Edit ambulance', 'অ্যাম্বুলেন্স বদলান') : tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ');
  if (gate) return <FormPage title={title} backTo="/work/ambulance">{gate}</FormPage>;
  if (id && !existing) {
    return (
      <FormPage title={title} backTo="/work/ambulance">
        <WorkEmpty icon="ambulance" title={tr('Not found', 'পাওয়া যায়নি')}>
          <Link to="/work/ambulance" className="btn">{myBusinessTitle('ambulance')}</Link>
        </WorkEmpty>
      </FormPage>
    );
  }
  if (!id && s.work.ambulances.length >= MAX_AMBULANCES) {
    return (
      <FormPage title={title} backTo="/work/ambulance">
        <WorkEmpty icon="ambulance" title={tr('Up to 10 ambulances per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ১০টা অ্যাম্বুলেন্স')} />
      </FormPage>
    );
  }
  return (
    <FormPage title={title} backTo="/work/ambulance">
      <AmbulanceEditor existing={existing} onDone={(flash) => nav('/work/ambulance', { state: { flash } })} />
    </FormPage>
  );
}

function AmbulanceEditor({ existing, onDone }: { existing: Ambulance | null; onDone: (flash: string) => void }) {
  const s = useSession();
  const nav = useNavigate();
  const cat = useCatalog('ambulanceType');
  const after = useAfterChange();
  const p = s.profile;
  const [name, setName] = useState(existing?.name ?? '');
  const [phone, setPhone] = useState(existing?.phone ?? p?.phone ?? '');
  const [vehicleNo, setVehicleNo] = useState(existing?.vehicleNo ?? '');
  const [types, setTypes] = useState<string[]>(existing?.types ?? ['general']);
  const [place, setPlace] = useState<AreaValue | null>(existing ? { district: existing.district, upazilaId: existing.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
  const [area, setArea] = useState(existing?.area ?? '');
  const [fare, setFare] = useState(existing?.fare ?? '');
  // গাড়ি যেখানে অপেক্ষা করে (ঐচ্ছিক): "সবচেয়ে কাছের" খুঁজতে; না দিলে জেলার কেন্দ্র ধরা হয়।
  // শুধু ঠিকঠাক GPS (২০০ মিটারের ভেতরে) নেওয়া হয়; ভুল হলে এই সারিতেই লেখা, ফর্ম সেভ আটকায় না। clearBase: "জায়গা মুছুন"
  const [base, setBase] = useState<{ lat: number; lng: number } | null>(null);
  const [clearBase, setClearBase] = useState(false);
  const [gps, setGps] = useState(false);
  const [gpsErr, setGpsErr] = useState<string | null>(null);
  const [docs, setDocs] = useState<Record<string, string>>({});
  const [agreed, setAgreed] = useState(!!existing);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [del, setDel] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);

  async function useGps() {
    setGps(true);
    setGpsErr(null);
    try {
      const p = await precisePosition(200);
      setBase({ lat: p.lat, lng: p.lng });
      setClearBase(false);
    } catch (x) {
      setGpsErr(x instanceof InaccuratePosition
        ? tr("Couldn't get an accurate location — go outside and try again.", 'লোকেশন ঠিকমতো পাওয়া যায়নি — বাইরে গিয়ে আবার চেষ্টা করুন')
        : tr("Couldn't get your location. Allow location in the browser.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন।'));
    } finally {
      setGps(false);
    }
  }
  function forgetBase() {
    setBase(null);
    setGpsErr(null);
    // আগে দেওয়া জায়গা থাকলে সেভের সময় সার্ভারেও মোছে
    setClearBase(!!existing?.hasBase);
  }

  async function save() {
    const specs = ambulanceDocs();
    const e = !name.trim() ? tr('Enter the service or driver name', 'সার্ভিস বা চালকের নাম লিখুন')
      : !isContactPhone(phone) ? tr('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন')
        : !vehicleNo.trim() ? tr('Enter the vehicle registration number', 'গাড়ির রেজিস্ট্রেশন নম্বর দিন')
          : !types.length ? tr('Choose the ambulance type', 'অ্যাম্বুলেন্সের ধরন বেছে নিন')
            : !place ? tr('Choose a district', 'জেলা বেছে নিন')
              : !existing && missingDocs(specs, docs) ? missingDocs(specs, docs)
                : !agreed ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
    setErr(e);
    if (e || !place) return;
    setBusy(true);
    try {
      const r = await call<{ pending?: boolean; baseIgnored?: boolean }>('ambulance_save', {
        id: existing?.id ?? null, name: name.trim(), phone, vehicleNo: vehicleNo.trim(), types, district: place.district, upazilaId: place.upazilaId ?? null,
        area: area.trim(), fare: fare.trim(), termsAccepted: true, docs, ...(base ? { lat: base.lat, lng: base.lng } : clearBase ? { clearBase: true } : {}),
      });
      after();
      // জায়গাটা জেলার বাইরে হলে সার্ভার শুধু জায়গাটা বাদ দেয়, বাকিটা সেভ হয়
      const ignored = r.baseIgnored ? tr(' — vehicle location was outside the district, not saved', ' — গাড়ির জায়গা জেলার বাইরে, তাই নেওয়া হয়নি') : '';
      onDone((r.pending ? sentForApproval('ambulance') : tr('Saved', 'সেভ হয়েছে')) + ignored);
    } catch (x) {
      setErr(errorText(x));
      setBusy(false);
    }
  }
  async function remove() {
    if (!existing) return;
    setBusy(true);
    setDelErr(null);
    try {
      await call('ambulance_delete', { id: existing.id });
      after();
      onDone(tr('Removed', 'মুছে ফেলা হয়েছে'));
    } catch (x) {
      setDelErr(errorText(x));
      setBusy(false);
    }
  }

  return (
    <>
      {existing ? (
        <div className="row wrap gap-8">
          <StatusTag status={existing.status} />
          <StatusReason status={existing.status} reason={existing.statusReason} />
        </div>
      ) : null}
      <TextInput label={tr('Service / driver name', 'সার্ভিস বা চালকের নাম')} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} autoCapitalize="words" />
      <div className="work-two">
        <TextInput label={tr('Phone for calls', 'যে নম্বরে ফোন আসবে')} hint={tr('Mobile, landline or hotline', 'মোবাইল, ল্যান্ডলাইন বা হটলাইন')} inputMode="tel" value={phone} onChange={(e) => setPhone(phoneInput(e.target.value))} />
        <TextInput label={tr('Vehicle registration no.', 'গাড়ির রেজিস্ট্রেশন নম্বর')} value={vehicleNo} maxLength={40} onChange={(e) => setVehicleNo(e.target.value)} />
      </div>
      <Field label={tr('Type', 'ধরন')}>
        <ChoiceChips multi options={cat.options} value={types} onChange={setTypes} />
      </Field>
      <AreaField label={tr('Base area', 'কোথা থেকে চলে')} value={place} onChange={setPlace} />
      <Field label={tr('Where the vehicle waits (stand / hospital gate) — not your home', 'গাড়ি যেখানে অপেক্ষা করে (স্ট্যান্ড/হাসপাতাল গেট) — বাসা নয়')}>
        <div className="col gap-6">
          {base ? (
            <InfoText>{tr('Vehicle location set', 'গাড়ির জায়গা নেওয়া হয়েছে')}</InfoText>
          ) : (
            <span className="t-body-s muted">
              {clearBase ? tr('Location will be removed when you save.', 'সেভ করলে জায়গা মুছে যাবে।')
                : existing?.hasBase ? tr('Location given. Update only if the waiting place changed.', 'জায়গা দেওয়া আছে। অপেক্ষার জায়গা বদলালে তবেই আবার দিন।')
                  : tr('Optional — stand there and tap. Nearby patients find you first; only the area (about 1 km) is kept.', 'ঐচ্ছিক — সেখানে দাঁড়িয়ে চাপুন। কাছের রোগী আগে খুঁজে পাবেন; শুধু এলাকা (প্রায় ১ কিমি) রাখা হয়।')}
            </span>
          )}
          <div className="row wrap gap-8">
            {!base ? (
              <Button variant="outline" icon="location_on" busy={gps} onClick={useGps}>{tr('Use current location', 'বর্তমান লোকেশন ধরুন')}</Button>
            ) : null}
            {base || (existing?.hasBase && !clearBase) ? (
              <Button variant="text" icon="close" disabled={gps} onClick={forgetBase}>{tr('Remove location', 'জায়গা মুছুন')}</Button>
            ) : null}
          </div>
          <ErrorText>{gpsErr}</ErrorText>
        </div>
      </Field>
      <TextInput label={tr('Where you go (optional)', 'কোন কোন এলাকায় যান (ঐচ্ছিক)')} value={area} maxLength={120} onChange={(e) => setArea(e.target.value)} />
      <TextInput label={tr('Fare (optional)', 'ভাড়া (ঐচ্ছিক)')} placeholder={tr('e.g. Within city 800 Tk, per km 40 Tk', 'যেমন: শহরের মধ্যে ৮০০ টাকা, প্রতি কিমি ৪০ টাকা')} value={fare} maxLength={120} onChange={(e) => setFare(e.target.value)} />
      <DocPhotosField specs={ambulanceDocs()} photos={docs} onChange={setDocs} note={existing ? PAPERS_CHANGED() : null} />
      <TermsCheck checked={agreed} onChange={setAgreed} />
      <ErrorText>{err}</ErrorText>
      <div className="form-actions">
        {existing && existing.status !== 'suspended' ? (
          <Button variant="danger-text" className="work-del" disabled={busy} onClick={() => setDel(true)}>{tr('Remove this ambulance', 'এই অ্যাম্বুলেন্স সরিয়ে দিন')}</Button>
        ) : null}
        <Button variant="text" disabled={busy} onClick={() => nav('/work/ambulance')}>{tr('Cancel', 'বাতিল')}</Button>
        <Button busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
      {del && existing ? (
        <ConfirmDialog
          title={tr('Remove this ambulance?', 'এই অ্যাম্বুলেন্স সরিয়ে দেবেন?')}
          text={`${existing.name} · ${existing.vehicleNo}`}
          confirm={tr('Remove', 'মুছে ফেলুন')}
          danger
          busy={busy}
          error={delErr}
          onConfirm={remove}
          onClose={() => setDel(false)}
        />
      ) : null}
    </>
  );
}
