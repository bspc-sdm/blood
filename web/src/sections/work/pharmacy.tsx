// /work/pharmacy — নিজের ফার্মেসি: অবস্থা, কয়েক দিন বন্ধ, হিসাব, ওষুধ খোঁজার অনুরোধ, বদল (অ্যাপের MyPharmacyPanel, ShopEditScreen, PharmacyFindRequests)
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, ConfirmDialog, Dialog, ErrorText, InfoText, Loading, Switch, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { areaLabel, currentPosition } from '@/lib/geo';
import { formatDate, formatKm, normalizePhone, timeAgo, todayUtcMidnight } from '@/lib/format';
import { DAY_MS, DHAKA_OFFSET_MS } from '@/lib/rules';
import type { AreaValue } from '@/ui/AreaPicker';
import {
  AppealButton, BizPage, AreaField, DocPhotosField, DocsNeededNotice, Flash, MiniBars, PAPERS_CHANGED, Stat, StatusReason, StatusTag, TermsCheck, TimeRange, WeekdayChips,
  closedDaysText, hoursText, missingDocs, myBusinessTitle, pharmacyDocs, sentForApproval, statusText, useAfterChange, useAreas, useFlash, useWorkGate,
  type CallSummary, type Pharmacy,
  WorkEmpty,
} from './shared';

interface FindReq { id: string; drug: { name: string; generic?: string; form?: string; company?: string }; area: string; km: number | null; answer: 'yes' | 'no' | null; createdAt: number }

export function MyPharmacy() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const ph = s.work.pharmacy as Pharmacy | null;
  const stats = useCall<CallSummary>('pharmacy_stats', {}, { enabled: !gate && !!ph });
  const after = useAfterChange();
  const [flash, setFlash] = useFlash();
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [del, setDel] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);
  useAreas();
  const title = myBusinessTitle('pharmacy');
  if (gate) return <BizPage title={title}>{gate}</BizPage>;

  if (!ph) {
    return (
      <BizPage title={title}>
        <Flash msg={flash} />
        <WorkEmpty
          icon="storefront"
          title={tr('Have a licensed pharmacy?', 'লাইসেন্সধারী ফার্মেসি আছে?')}
          text={tr('Add it so people nearby can find it — especially at night — and call you directly. Free: no commission, the app sells nothing.', 'যোগ করুন, যাতে কাছের মানুষ (বিশেষ করে রাতে) খুঁজে পেয়ে সরাসরি ফোন করতে পারেন। বিনামূল্যে: কোনো কমিশন নেই, অ্যাপ কিছু বিক্রি করে না।')}
        >
          <Button icon="add" onClick={() => nav('/work/pharmacy/new')}>{tr('Add my pharmacy', 'আমার ফার্মেসি যোগ করুন')}</Button>
        </WorkEmpty>
      </BizPage>
    );
  }

  async function act(done: string, f: () => Promise<unknown>) {
    setBusy(true);
    setErr(null);
    setFlash(null);
    try {
      await f();
      await after();
      setFlash(done);
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  function pause(days: number) {
    setPausing(false);
    // বাংলাদেশ সময়ে শেষ দিনের রাত পর্যন্ত
    const until = todayUtcMidnight() + (days + 1) * DAY_MS - DHAKA_OFFSET_MS;
    act(tr('People will see it as closed until then', 'ততদিন তালিকায় "বন্ধ" দেখাবে'), () => call('pharmacy_pause', { until }));
  }
  async function remove() {
    setBusy(true);
    setDelErr(null);
    try {
      await call('pharmacy_delete');
      await after();
      setDel(false);
      setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
    } catch (e) {
      setDelErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const approved = ph.status === 'approved';
  const st = stats.data;
  const place = [ph.address, areaLabel(ph.district, ph.upazilaId)].filter(Boolean).join(', ');
  const hours = [hoursText(ph), closedDaysText(ph.closedDays)].filter(Boolean).join(' · ');

  return (
    <BizPage
      title={title}
     
      actions={<Button variant="tonal" icon="edit" onClick={() => nav('/work/pharmacy/edit')}>{tr('Edit details', 'তথ্য বদলান')}</Button>}
    >
      <Flash msg={flash} error={err} />
      <DocsNeededNotice kind="pharmacy" id={ph.id} specs={pharmacyDocs()} item={ph} onDone={after} />
      <div className="stats work-stats">
        <Stat icon="call" value={st ? num(st.calls[7]) : '—'} label={tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')} />
        <Stat icon="call" value={st ? num(st.calls[30]) : '—'} label={tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')} />
        <Stat icon="call" value={st ? num(st.calls[90]) : '—'} label={tr('Calls from the app · 90 days', 'অ্যাপ থেকে ফোন · ৯০ দিনে')} />
        <Stat icon="group_round" value={st ? num(st.callers) : '—'} label={tr('Different callers', 'আলাদা মানুষ')} />
      </div>

      <div className="ws-grid side">
        <div className="col gap-16">
          {approved ? <FindRequests /> : null}
          {approved ? (
            <PartnerCard
              icon="medication"
              title={tr('Prescriptions & orders', 'প্রেসক্রিপশন ও অর্ডার')}
              sub={tr('Give medicines from a prescription, online orders and stock', 'প্রেসক্রিপশন থেকে ওষুধ দেওয়া, অনলাইন অর্ডার ও স্টক')}
            />
          ) : null}
          <section className="card col gap-8">
            <h3 className="t-title-m">{tr('Calls from the app', 'অ্যাপ থেকে ফোন')}</h3>
            {st ? (
              <>
                <p className="t-body-m">{tr(`${st.calls[7]} in 7 days · ${st.calls[30]} in 30 days`, `৭ দিনে ${num(st.calls[7])} · ৩০ দিনে ${num(st.calls[30])}`)}</p>
                <p className="t-title-s">{tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')}</p>
                <MiniBars values={st.daily} />
              </>
            ) : stats.error ? <ErrorText>{stats.error.message}</ErrorText> : <Loading />}
          </section>
        </div>

        <aside className="card col gap-8 work-side">
          <div className="row top">
            <span className="work-ic"><Icon name="storefront" /></span>
            <div className="col gap-4 grow">
              <h3 className="t-title-m">{ph.name}</h3>
              {ph.status !== 'approved' ? <span className="t-label-l c-primary">{statusText(ph.status)}</span> : null}
            </div>
            <StatusTag status={ph.status} closed={ph.closedUntil > 0} />
          </div>
          <StatusReason status={ph.status} reason={ph.statusReason} />
          {ph.status === 'pending' ? <p className="t-body-s muted">{tr("You'll appear in the list after approval.", 'অনুমোদনের পর তালিকায় দেখাবে।')}</p> : null}
          {hours ? <span className="icon-line"><Icon name="schedule" /><span>{hours}</span></span> : null}
          <span className="icon-line"><Icon name="call" /><span className="num">{num(ph.phone)}</span></span>
          {place ? <span className="icon-line"><Icon name="location_on" /><span>{place}</span></span> : null}
          <span className="icon-line"><Icon name="badge" /><span>{tr('Drug licence number', 'ড্রাগ লাইসেন্স নম্বর')}: {ph.licenseNo}</span></span>
          {ph.discount > 0 ? <span className="icon-line"><Icon name="payments" /><span>{tr(`${ph.discount}% off MRP`, `MRP থেকে ${num(ph.discount)}% ছাড়`)}</span></span> : null}
          <span className="icon-line"><Icon name="local_shipping" /><span>{ph.delivery ? tr('Home delivery', 'বাসায় ডেলিভারি') + (ph.deliveryNote ? ' · ' + ph.deliveryNote : '') : tr('No home delivery', 'বাসায় ডেলিভারি নেই')}</span></span>
          <hr className="divider" />
          {ph.status !== 'suspended' ? (
            ph.closedUntil > 0 ? (
              <div className="col gap-8">
                <span className="icon-line"><Icon name="info" /><span>{tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ph.closedUntil) + tr('', ' পর্যন্ত')}</span></span>
                <Button variant="tonal" icon="storefront" busy={busy} onClick={() => act(tr('Opened again', 'আবার খোলা হয়েছে'), () => call('pharmacy_pause', { until: null }))}>{tr('Open again', 'আবার খুলুন')}</Button>
              </div>
            ) : (
              <Button variant="tonal" icon="schedule" disabled={busy} onClick={() => setPausing(true)}>{tr('Closed for some days', 'কয়েক দিন বন্ধ থাকবে')}</Button>
            )
          ) : null}
          {ph.status === 'suspended' || ph.status === 'rejected' ? <AppealButton category="pharmacy" /> : null}
          {ph.status !== 'suspended' ? (
            <Button variant="danger-text" disabled={busy} onClick={() => { setDelErr(null); setDel(true); }}>{tr('Remove my pharmacy', 'ফার্মেসি মুছে ফেলুন')}</Button>
          ) : null}
        </aside>
      </div>

      {pausing ? (
        <Dialog onClose={() => setPausing(false)} title={tr('Closed for how long?', 'কত দিন বন্ধ থাকবে?')} size="small" actions={<Button variant="text" onClick={() => setPausing(false)}>{tr('Cancel', 'বাতিল')}</Button>}>
          <div className="work-days">
            {[1, 3, 7, 15, 30].map((d) => (
              <Button key={d} variant="outline" onClick={() => pause(d)}>{tr(`${d} day(s)`, `${num(d)} দিন`)}</Button>
            ))}
          </div>
        </Dialog>
      ) : null}
      {del ? (
        <ConfirmDialog
          title={tr('Remove your pharmacy?', 'ফার্মেসি মুছে ফেলবেন?')}
          text={tr('It will disappear from the list. You can add it again later (it will need approval again).', 'তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।')}
          confirm={tr('Remove', 'মুছে ফেলুন')}
          danger
          busy={busy}
          error={delErr}
          onConfirm={remove}
          onClose={() => setDel(false)}
        />
      ) : null}
    </BizPage>
  );
}

/** পার্টনার প্যানেলের (/doctor) লিংক: প্রেসক্রিপশন, অর্ডার, স্টক, রিপোর্ট সেখানে */
export function PartnerCard({ icon, title, sub }: { icon: string; title: string; sub: string }) {
  return (
    <section className="card work-partner">
      <span className="work-ic"><Icon name={icon} /></span>
      <div className="col gap-2 grow">
        <h3 className="t-title-m">{title}</h3>
        <span className="t-body-s muted">{sub}</span>
      </div>
      <a href="/doctor" className="btn work-open-btn">
        {tr('Open', 'খুলুন')}
        <Icon name="chevron_right" />
      </a>
    </section>
  );
}

/** দোকান: কাছের মানুষ কী খুঁজছেন — এক চাপে "আছে"/"নেই" */
function FindRequests() {
  const q = useCall<{ items: FindReq[]; findOff: boolean }>('med_find_requests', {}, { refetchInterval: 60_000 });
  const wide = useMedia('(min-width: 1000px)');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);
  const off = q.data?.findOff ?? false;
  const list = q.data?.items;

  async function answer(r: FindReq, have: boolean) {
    setBusyId(r.id);
    setErr(null);
    try {
      await call('med_find_answer', { id: r.id, have });
      await q.refetch();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusyId(null);
    }
  }
  async function setOn(on: boolean) {
    setSwitching(true);
    setErr(null);
    try {
      await call('pharmacy_find_setting', { off: !on });
      await q.refetch();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setSwitching(false);
    }
  }
  const drugLine = (r: FindReq) => r.drug.name + (r.drug.form ? ' · ' + r.drug.form : '');
  const meta = (r: FindReq) => [r.area || null, r.km != null ? formatKm(r.km) : null, timeAgo(r.createdAt)].filter(Boolean).join(' · ');
  const buttons = (r: FindReq) =>
    r.answer === 'yes' ? (
      <div className="row wrap gap-8">
        <span className="t-label-l c-primary">{tr('You said: have it', 'আপনার উত্তর: আছে')}</span>
        <Button variant="text" small disabled={busyId != null} onClick={() => answer(r, false)}>{tr('Change to no', '"নেই" করুন')}</Button>
      </div>
    ) : (
      <div className="row wrap gap-8">
        <Button small busy={busyId === r.id} disabled={busyId != null} onClick={() => answer(r, true)}>{tr('Have it', 'আছে')}</Button>
        <Button variant="outline" small disabled={busyId != null || r.answer === 'no'} onClick={() => answer(r, false)}>
          {r.answer === 'no' ? tr('Said no', '"নেই" বলেছেন') : tr("Don't have", 'নেই')}
        </Button>
      </div>
    );

  return (
    <section className="card col gap-8">
      <Switch
        checked={!off}
        disabled={switching || !q.data}
        onChange={setOn}
        label={<span className="t-title-m">{tr('Medicine requests', 'ওষুধ খোঁজার অনুরোধ')}</span>}
        sub={off ? tr("Turned off — you won't get requests.", 'বন্ধ — অনুরোধ আসবে না।') : undefined}
      />
      <ErrorText>{err ?? q.error?.message}</ErrorText>
      {off ? null : !list ? (q.error ? null : <Loading />) : !list.length ? (
        <p className="t-body-m muted">{tr('No open requests now.', 'এখন কোনো খোলা অনুরোধ নেই।')}</p>
      ) : wide ? (
        <div className="table-wrap work-inner-table">
          <table className="table">
            <thead>
              <tr>
                <th>{tr('Medicine', 'ওষুধ')}</th>
                <th>{tr('Area', 'এলাকা')}</th>
                <th>{tr('Your answer', 'আপনার উত্তর')}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.id}>
                  <td>
                    <div className="col gap-2">
                      <span className="semi">{drugLine(r)}</span>
                      {r.drug.generic ? <span className="t-body-s muted">{r.drug.generic}</span> : null}
                    </div>
                  </td>
                  <td className="t-body-s muted">{meta(r)}</td>
                  <td>{buttons(r)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="col gap-8">
          {list.map((r) => (
            <div key={r.id} className="work-find">
              <span className="semi">{drugLine(r)}</span>
              {r.drug.generic ? <span className="t-body-s">{r.drug.generic}</span> : null}
              <span className="t-body-s muted">{meta(r)}</span>
              {buttons(r)}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export function PharmacyForm() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const existing = s.work.pharmacy as Pharmacy | null;
  const title = existing ? tr('Edit pharmacy', 'ফার্মেসি বদলান') : tr('Open a pharmacy', 'ফার্মেসি খুলুন');
  return (
    <FormPage title={title} backTo="/work/pharmacy">
      {gate ?? <PharmacyEditor key={existing?.id ?? 'new'} existing={existing} onDone={(flash) => nav('/work/pharmacy', { state: { flash } })} />}
    </FormPage>
  );
}

function PharmacyEditor({ existing, onDone }: { existing: Pharmacy | null; onDone: (flash: string) => void }) {
  const s = useSession();
  const nav = useNavigate();
  const after = useAfterChange();
  const p = s.profile;
  const [name, setName] = useState(existing?.name ?? '');
  const [licenseNo, setLicenseNo] = useState(existing?.licenseNo ?? '');
  const [phone, setPhone] = useState(existing?.phone ?? p?.phone ?? '');
  const [place, setPlace] = useState<AreaValue | null>(existing ? { district: existing.district, upazilaId: existing.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
  const [address, setAddress] = useState(existing?.address ?? '');
  const [loc, setLoc] = useState<{ lat: number; lng: number } | null>(null);
  const [gps, setGps] = useState(false);
  const [gpsErr, setGpsErr] = useState<string | null>(null);
  const [open24, setOpen24] = useState(existing?.open24 ?? false);
  const [openAt, setOpenAt] = useState(existing?.openAt || '09:00');
  const [closeAt, setCloseAt] = useState(existing?.closeAt || '23:00');
  const [closedDays, setClosedDays] = useState<number[]>(existing?.closedDays ?? []);
  const [delivery, setDelivery] = useState(existing?.delivery ?? true);
  const [deliveryNote, setDeliveryNote] = useState(existing?.deliveryNote ?? '');
  const [discount, setDiscount] = useState(String(existing?.discount ?? 0));
  const [docs, setDocs] = useState<Record<string, string>>({});
  const [agreed, setAgreed] = useState(!!existing);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const hadLocation = !!existing?.hasLocation;

  async function useGps() {
    setGps(true);
    setGpsErr(null);
    try {
      setLoc(await currentPosition());
    } catch {
      setGpsErr(tr("Couldn't get your location. Allow location in the browser or pick a district.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন বা জেলা বেছে নিন।'));
    } finally {
      setGps(false);
    }
  }

  async function save() {
    const specs = pharmacyDocs();
    const mobile = normalizePhone(phone);
    const disc = parseInt(toAsciiDigits(discount) || '0', 10) || 0;
    const e = !name.trim() ? tr('Enter the pharmacy name', 'ফার্মেসির নাম লিখুন')
      : !licenseNo.trim() ? tr('Enter the drug licence number', 'ড্রাগ লাইসেন্স নম্বর দিন')
        : !mobile ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
          : !place ? tr('Choose a district', 'জেলা বেছে নিন')
            : !address.trim() ? tr('Write the shop address', 'দোকানের ঠিকানা লিখুন')
              : !loc && !hadLocation ? tr('Set the shop location', 'দোকানের লোকেশন দিন')
                : !open24 && (!openAt || !closeAt || openAt === closeAt) ? tr('Set opening and closing time', 'খোলা আর বন্ধের সময় দিন')
                  : disc > 30 ? tr('Discount can be up to 30%', 'ছাড় সর্বোচ্চ ৩০%')
                    : !existing && missingDocs(specs, docs) ? missingDocs(specs, docs)
                      : !agreed ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
    setErr(e);
    if (e || !place) return;
    setBusy(true);
    try {
      const r = await call<{ pending?: boolean }>('pharmacy_save', {
        name: name.trim(), licenseNo: licenseNo.trim(), phone: mobile, district: place.district, upazilaId: place.upazilaId ?? null, address: address.trim(),
        lat: loc?.lat ?? null, lng: loc?.lng ?? null, open24, openAt, closeAt, delivery, deliveryNote: deliveryNote.trim(), discount: disc,
        closedDays: [...closedDays].sort((a, b) => a - b), termsAccepted: true, docs,
      });
      after();
      onDone(r.pending ? sentForApproval('pharmacy') : tr('Saved', 'সেভ হয়েছে'));
    } catch (x) {
      setErr(errorText(x));
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
      <TextInput label={tr('Pharmacy name', 'ফার্মেসির নাম')} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoCapitalize="words" />
      <div className="work-two">
        <TextInput label={tr('Drug licence number', 'ড্রাগ লাইসেন্স নম্বর')} value={licenseNo} maxLength={40} onChange={(e) => setLicenseNo(e.target.value)} />
        <TextInput label={tr('Shop phone', 'দোকানের ফোন')} inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value.replace(/[^\d+০-৯]/g, '').slice(0, 15))} />
      </div>
      <AreaField label={tr('Area', 'এলাকা')} value={place} onChange={setPlace} />
      <TextInput label={tr('Shop address', 'দোকানের ঠিকানা')} value={address} maxLength={200} onChange={(e) => setAddress(e.target.value)} />
      <div className="col gap-6">
        {loc ? (
          <InfoText>{tr('Shop location set', 'দোকানের লোকেশন নেওয়া হয়েছে')}</InfoText>
        ) : (
          <>
            <span className="t-body-s muted">{hadLocation ? tr('Update only if the shop moved.', 'দোকান সরালে তবেই আবার দিন।') : tr('Stand inside the shop and tap the button.', 'দোকানের ভেতরে দাঁড়িয়ে বাটন চাপুন।')}</span>
            <Button variant="outline" icon="location_on" busy={gps} onClick={useGps} className="work-self-start">{tr('Use current location as shop location', 'বর্তমান লোকেশনকে দোকানের লোকেশন ধরুন')}</Button>
          </>
        )}
        <ErrorText>{gpsErr}</ErrorText>
      </div>
      <Switch checked={open24} onChange={setOpen24} label={tr('Open 24 hours', '২৪ ঘণ্টা খোলা')} />
      {!open24 ? <TimeRange openAt={openAt} closeAt={closeAt} onChange={(o, c) => { setOpenAt(o); setCloseAt(c); }} /> : null}
      <WeekdayChips value={closedDays} onChange={setClosedDays} />
      <Switch checked={delivery} onChange={setDelivery} label={tr('Home delivery', 'বাসায় ডেলিভারি দিই')} />
      {delivery ? <TextInput label={tr('Delivery area / charge (optional)', 'ডেলিভারির এলাকা / চার্জ (ঐচ্ছিক)')} value={deliveryNote} maxLength={120} onChange={(e) => setDeliveryNote(e.target.value)} /> : null}
      <TextInput label={tr('Usual discount on MRP (%)', 'সাধারণত MRP থেকে কত % ছাড়')} inputMode="numeric" value={discount} onChange={(e) => setDiscount(toAsciiDigits(e.target.value).replace(/\D/g, '').slice(0, 2))} />
      <DocPhotosField specs={pharmacyDocs()} photos={docs} onChange={setDocs} note={existing ? PAPERS_CHANGED() : null} />
      <TermsCheck checked={agreed} onChange={setAgreed} />
      <ErrorText>{err}</ErrorText>
      <div className="form-actions">
        <Button variant="text" disabled={busy} onClick={() => nav('/work/pharmacy')}>{tr('Cancel', 'বাতিল')}</Button>
        <Button busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
    </>
  );
}
