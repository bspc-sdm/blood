// /work/bloodbank — নিজের ব্লাড ব্যাংক (সর্বোচ্চ ৩টা): তালিকা, হিসাব, যোগ/বদল/সরানো (অ্যাপের MyBloodBanksSection, BloodBankEditDialog)
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, ChoiceChips, ConfirmDialog, ErrorText, Field, IconButton, Switch, Tag, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import type { AreaValue } from '@/ui/AreaPicker';
import {
  AppealButton, BizPage, AreaField, DocPhotosField, DocsNeededNotice, Flash, MAX_BLOODBANKS, PAPERS_CHANGED, Stat, StatsDialog, StatusReason, StatusTag, TermsCheck, TimeRange, WeekdayChips,
  bloodBankDocs, callsLine, closedDaysText, hoursText, isContactPhone, missingDocs, myBusinessTitle, phoneInput, sentForApproval, useAfterChange, useAreas, useCatalog, useFlash, useWorkGate,
  type BloodBank, type ListingStats,
  WorkEmpty,
} from './shared';

const BB_KINDS = ['govt', 'private', 'org'];
const kindLabel = (k: string) => (k === 'govt' ? tr('Government hospital', 'সরকারি হাসপাতাল') : k === 'private' ? tr('Private', 'বেসরকারি') : tr('Volunteer organisation', 'স্বেচ্ছাসেবী সংগঠন'));

export function MyBloodBanks() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const wide = useMedia('(min-width: 1000px)');
  const list = s.work.bloodBanks as BloodBank[];
  const stats = useCall<{ items: ListingStats[] }>('bloodbank_stats', {}, { enabled: !gate && list.length > 0 });
  const cat = useCatalog('bloodBankService');
  const after = useAfterChange();
  const [flash, setFlash] = useFlash();
  const [del, setDel] = useState<BloodBank | null>(null);
  const [delBusy, setDelBusy] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);
  const [details, setDetails] = useState<BloodBank | null>(null);
  useAreas();
  const title = myBusinessTitle('bloodbank');
  if (gate) return <BizPage title={title}>{gate}</BizPage>;

  const st = new Map((stats.data?.items ?? []).map((x) => [x.id, x]));
  const canAdd = list.length < MAX_BLOODBANKS;
  async function remove() {
    if (!del) return;
    setDelBusy(true);
    setDelErr(null);
    try {
      await call('bloodbank_delete', { id: del.id });
      await after();
      setDel(null);
      setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
    } catch (e) {
      setDelErr(errorText(e));
    } finally {
      setDelBusy(false);
    }
  }
  const place = (b: BloodBank) => [b.address, areaLabel(b.district, b.upazilaId)].filter(Boolean).join(', ');
  const hours = (b: BloodBank) => [hoursText(b), closedDaysText(b.closedDays)].filter(Boolean).join(' · ');
  const actions = (b: BloodBank) => (
    <div className="row gap-4 work-row-actions">
      <IconButton icon="edit" label={tr('Edit', 'বদলান')} onClick={() => nav(`/work/bloodbank/${b.id}`)} />
      {b.status !== 'suspended' ? <IconButton icon="delete" label={tr('Remove this blood bank', 'এই ব্লাড ব্যাংক সরিয়ে দিন')} onClick={() => { setDelErr(null); setDel(b); }} /> : null}
    </div>
  );

  return (
    <BizPage
      title={title}
      actions={canAdd ? <Button icon="add" onClick={() => nav('/work/bloodbank/new')}>{tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন')}</Button> : null}
    >
      <Flash msg={flash} />
      {list.length ? (
        <div className="stats work-stats">
          <Stat icon="bloodtype" value={num(list.length)} label={tr('Blood banks', 'ব্লাড ব্যাংক')} />
          <Stat icon="check_circle" value={num(list.filter((b) => b.status === 'approved').length)} label={tr('Listed', 'তালিকায় আছে')} />
          <Stat icon="call" value={stats.data ? num(stats.data.items.reduce((n, x) => n + x.calls[7], 0)) : '—'} label={tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')} />
          <Stat icon="call" value={stats.data ? num(stats.data.items.reduce((n, x) => n + x.calls[30], 0)) : '—'} label={tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')} />
        </div>
      ) : null}
      {list.map((b) => (
        <DocsNeededNotice key={b.id} kind="bloodbank" id={b.id} specs={bloodBankDocs(b.kind)} item={b} onDone={after} title={b.name} />
      ))}
      {!list.length ? (
        <WorkEmpty icon="bloodtype" title={tr('No blood bank added yet', 'এখনো কোনো ব্লাড ব্যাংক যোগ করা হয়নি')}>
          <Button icon="add" onClick={() => nav('/work/bloodbank/new')}>{tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন')}</Button>
        </WorkEmpty>
      ) : wide ? (
        <div className="table-wrap">
          <table className="table work-table">
            <thead>
              <tr>
                <th>{tr('Blood bank name', 'ব্লাড ব্যাংকের নাম')}</th>
                <th>{tr('Address', 'ঠিকানা')}</th>
                <th>{tr('Open hours', 'খোলার সময়')}</th>
                <th>{tr('Status', 'অবস্থা')}</th>
                <th>{tr('Calls from the app', 'অ্যাপ থেকে ফোন')}</th>
                <th aria-label={tr('Actions', 'কাজ')} />
              </tr>
            </thead>
            <tbody>
              {list.map((b) => (
                <tr key={b.id}>
                  <td>
                    <div className="col gap-2">
                      <span className="semi">{b.name}</span>
                      <span className="t-body-s muted">{kindLabel(b.kind)}</span>
                    </div>
                  </td>
                  <td className="work-td-area">{place(b)}</td>
                  <td>{hours(b)}</td>
                  <td>
                    <div className="col gap-4">
                      <StatusTag status={b.status} />
                      <StatusReason status={b.status} reason={b.statusReason} />
                      {b.status === 'rejected' || b.status === 'suspended' ? <AppealButton /> : null}
                    </div>
                  </td>
                  <td>
                    <div className="col gap-2">
                      <span className="num">{callsLine(st.get(b.id))}</span>
                      {st.get(b.id) ? <button type="button" className="work-link" onClick={() => setDetails(b)}>{tr('Details', 'বিস্তারিত')}</button> : null}
                    </div>
                  </td>
                  <td>{actions(b)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="work-cards">
          {list.map((b) => (
            <article key={b.id} className="card col gap-8">
              <div className="row top">
                <div className="col gap-2 grow">
                  <span className="t-title-m">{b.name}</span>
                  <span className="t-body-s muted">{kindLabel(b.kind)}</span>
                </div>
                <StatusTag status={b.status} />
              </div>
              <StatusReason status={b.status} reason={b.statusReason} />
              <div className="row wrap gap-6">{b.services.map((x) => <Tag key={x} tone="primary">{cat.label(x)}</Tag>)}</div>
              {place(b) ? <span className="icon-line"><Icon name="location_on" /><span>{place(b)}</span></span> : null}
              {hours(b) ? <span className="icon-line"><Icon name="schedule" /><span>{hours(b)}</span></span> : null}
              <div className="row">
                <span className="icon-line grow"><Icon name="call" /><span>{tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(st.get(b.id))}</span></span>
                {st.get(b.id) ? <Button variant="text" small onClick={() => setDetails(b)}>{tr('Details', 'বিস্তারিত')}</Button> : null}
              </div>
              <div className="row wrap gap-8">
                {b.status === 'rejected' || b.status === 'suspended' ? <AppealButton /> : null}
                <span className="grow" />
                {actions(b)}
              </div>
            </article>
          ))}
        </div>
      )}
      {del ? (
        <ConfirmDialog
          title={tr('Remove this blood bank?', 'এই ব্লাড ব্যাংক সরিয়ে দেবেন?')}
          text={del.name}
          confirm={tr('Remove', 'মুছে ফেলুন')}
          danger
          busy={delBusy}
          error={delErr}
          onConfirm={remove}
          onClose={() => setDel(null)}
        />
      ) : null}
      {details && st.get(details.id) ? <StatsDialog title={details.name} st={st.get(details.id)!} onClose={() => setDetails(null)} /> : null}
    </BizPage>
  );
}

export function BloodBankForm({ id }: { id: string | null }) {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const existing = id ? (s.work.bloodBanks as BloodBank[]).find((b) => b.id === id) ?? null : null;
  const title = existing ? tr('Edit blood bank', 'ব্লাড ব্যাংক বদলান') : tr('Add blood bank', 'ব্লাড ব্যাংক যোগ');
  let body = gate;
  if (!body && id && !existing) {
    body = (
      <WorkEmpty icon="bloodtype" title={tr('Not found', 'পাওয়া যায়নি')}>
        <Link to="/work/bloodbank" className="btn">{myBusinessTitle('bloodbank')}</Link>
      </WorkEmpty>
    );
  }
  if (!body && !id && s.work.bloodBanks.length >= MAX_BLOODBANKS) body = <WorkEmpty icon="bloodtype" title={tr('Up to 3 blood banks per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক')} />;
  return (
    <FormPage title={title} backTo="/work/bloodbank">
      {body ?? <BloodBankEditor existing={existing} onDone={(flash) => nav('/work/bloodbank', { state: { flash } })} />}
    </FormPage>
  );
}

function BloodBankEditor({ existing, onDone }: { existing: BloodBank | null; onDone: (flash: string) => void }) {
  const s = useSession();
  const nav = useNavigate();
  const cat = useCatalog('bloodBankService');
  const after = useAfterChange();
  const p = s.profile;
  const [name, setName] = useState(existing?.name ?? '');
  const [kind, setKind] = useState(existing?.kind ?? 'govt');
  const [phone, setPhone] = useState(existing?.phone ?? p?.phone ?? '');
  const [licenseNo, setLicenseNo] = useState(existing?.licenseNo ?? '');
  const [place, setPlace] = useState<AreaValue | null>(existing ? { district: existing.district, upazilaId: existing.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
  const [address, setAddress] = useState(existing?.address ?? '');
  const [open24, setOpen24] = useState(existing?.open24 ?? true);
  const [openAt, setOpenAt] = useState(existing?.openAt || '08:00');
  const [closeAt, setCloseAt] = useState(existing?.closeAt || '20:00');
  const [closedDays, setClosedDays] = useState<number[]>(existing?.closedDays ?? []);
  const [services, setServices] = useState<string[]>(existing?.services ?? ['whole_blood', 'screening']);
  const [docs, setDocs] = useState<Record<string, string>>({});
  const [agreed, setAgreed] = useState(!!existing);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [del, setDel] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);

  async function save() {
    const specs = bloodBankDocs(kind);
    const e = !name.trim() ? tr('Enter the blood bank name', 'ব্লাড ব্যাংকের নাম লিখুন')
      : !isContactPhone(phone) ? tr('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন')
        : kind === 'private' && !licenseNo.trim() ? tr('Enter the blood bank licence number', 'ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন')
          : !place ? tr('Choose a district', 'জেলা বেছে নিন')
            : !address.trim() ? tr('Write the address', 'ঠিকানা লিখুন')
              : !open24 && (!openAt || !closeAt || openAt === closeAt) ? tr('Set the opening hours', 'খোলার সময় দিন')
                : !services.length ? tr('Choose the services', 'কী কী সেবা দেন বেছে নিন')
                  : !existing && missingDocs(specs, docs) ? missingDocs(specs, docs)
                    : !agreed ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
    setErr(e);
    if (e || !place) return;
    setBusy(true);
    try {
      const r = await call<{ pending?: boolean }>('bloodbank_save', {
        id: existing?.id ?? null, name: name.trim(), kind, phone, district: place.district, upazilaId: place.upazilaId ?? null, address: address.trim(),
        licenseNo: licenseNo.trim(), open24, openAt: open24 ? '' : openAt, closeAt: open24 ? '' : closeAt, services,
        closedDays: [...closedDays].sort((a, b) => a - b), termsAccepted: true, docs,
      });
      after();
      onDone(r.pending ? sentForApproval('bloodbank') : tr('Saved', 'সেভ হয়েছে'));
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
      await call('bloodbank_delete', { id: existing.id });
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
      <TextInput label={tr('Blood bank name', 'ব্লাড ব্যাংকের নাম')} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoCapitalize="words" />
      <Field label={tr('Type', 'ধরন')}>
        <ChoiceChips options={BB_KINDS.map((k) => ({ value: k, label: kindLabel(k) }))} value={kind} onChange={setKind} />
      </Field>
      <div className="work-two">
        <TextInput label={tr('Phone for calls', 'যে নম্বরে ফোন আসবে')} hint={tr('Mobile, landline or hotline', 'মোবাইল, ল্যান্ডলাইন বা হটলাইন')} inputMode="tel" value={phone} onChange={(e) => setPhone(phoneInput(e.target.value))} />
        {kind === 'private' ? <TextInput label={tr('Licence number', 'লাইসেন্স নম্বর')} value={licenseNo} maxLength={60} onChange={(e) => setLicenseNo(e.target.value)} /> : null}
      </div>
      <AreaField label={tr('Area', 'এলাকা')} value={place} onChange={setPlace} />
      <TextInput label={tr('Address', 'ঠিকানা')} value={address} maxLength={200} onChange={(e) => setAddress(e.target.value)} />
      <Switch checked={open24} onChange={setOpen24} label={tr('Open 24 hours', '২৪ ঘণ্টা খোলা')} />
      {!open24 ? <TimeRange openAt={openAt} closeAt={closeAt} onChange={(o, c) => { setOpenAt(o); setCloseAt(c); }} /> : null}
      <WeekdayChips value={closedDays} onChange={setClosedDays} />
      <Field label={tr('Services', 'কী কী সেবা')}>
        <ChoiceChips multi options={cat.options} value={services} onChange={setServices} />
      </Field>
      <DocPhotosField specs={bloodBankDocs(kind)} photos={docs} onChange={setDocs} note={existing ? PAPERS_CHANGED() : null} />
      <TermsCheck checked={agreed} onChange={setAgreed} />
      <ErrorText>{err}</ErrorText>
      <div className="form-actions">
        {existing && existing.status !== 'suspended' ? (
          <Button variant="danger-text" className="work-del" disabled={busy} onClick={() => setDel(true)}>{tr('Remove this blood bank', 'এই ব্লাড ব্যাংক সরিয়ে দিন')}</Button>
        ) : null}
        <Button variant="text" disabled={busy} onClick={() => nav('/work/bloodbank')}>{tr('Cancel', 'বাতিল')}</Button>
        <Button busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
      {del && existing ? (
        <ConfirmDialog
          title={tr('Remove this blood bank?', 'এই ব্লাড ব্যাংক সরিয়ে দেবেন?')}
          text={existing.name}
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
