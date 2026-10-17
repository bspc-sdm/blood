// /work/lab — নিজের প্যাথলজি: অবস্থা, কাগজ, বদল; পরীক্ষা আর রিপোর্ট পার্টনার প্যানেলে (অ্যাপের MyLabPanel, LabEditScreen)
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, ConfirmDialog, Dialog, EmptyState, ErrorText, ListRow, Loading, Switch, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage, Workspace } from '@/shell/templates';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { isBangla, num, tr } from '@/lib/i18n';
import { areaLabel, bnNorm } from '@/lib/geo';
import { normalizePhone } from '@/lib/format';
import type { AreaValue } from '@/ui/AreaPicker';
import {
  AppealButton, AreaField, DocPhotosField, DocsNeededNotice, Flash, PAPERS_CHANGED, StatusReason, StatusTag, TermsCheck,
  labDocs, missingDocs, myBusinessTitle, sentForApproval, statusText, useAfterChange, useAreas, useFlash, useWorkGate,
  type Lab,
} from './shared';
import { PartnerCard } from './pharmacy';

export function MyLab() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const lab = s.work.lab as Lab | null;
  const after = useAfterChange();
  const [flash, setFlash] = useFlash();
  const [del, setDel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [delErr, setDelErr] = useState<string | null>(null);
  useAreas();
  const title = myBusinessTitle('lab');
  const crumb = <Link to="/work" className="work-crumb"><Icon name="arrow_back" />{tr('My work', 'আমার কাজ')}</Link>;
  if (gate) return <Workspace title={title}>{gate}</Workspace>;
  if (!lab) {
    return (
      <Workspace title={title} intro={crumb}>
        <Flash msg={flash} />
        <EmptyState
          icon="local_hospital"
          title={tr('Run a diagnostic centre?', 'ডায়াগনস্টিক সেন্টার চালান?')}
          text={tr('Join so patients can send you tests from their prescriptions, and give reports in the app. Free.', 'যোগ দিন — রোগীরা প্রেসক্রিপশন থেকে আপনার কাছে পরীক্ষা পাঠাবেন, রিপোর্ট অ্যাপেই দেবেন। বিনামূল্যে।')}
        >
          <Button icon="add" onClick={() => nav('/work/lab/new')}>{tr('Add my lab', 'আমার প্যাথলজি যোগ করুন')}</Button>
        </EmptyState>
      </Workspace>
    );
  }
  async function remove() {
    setBusy(true);
    setDelErr(null);
    try {
      await call('lab_delete');
      await after();
      setDel(false);
      setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
    } catch (e) {
      setDelErr(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const place = [lab.address, areaLabel(lab.district, lab.upazilaId)].filter(Boolean).join(', ');
  return (
    <Workspace title={title} intro={crumb} actions={<Button variant="tonal" icon="edit" onClick={() => nav('/work/lab/edit')}>{tr('Edit details', 'তথ্য বদলান')}</Button>}>
      <Flash msg={flash} />
      <DocsNeededNotice kind="lab" id={lab.id} specs={labDocs()} item={lab} onDone={after} />
      <div className="ws-grid side">
        <div className="col gap-16">
          {lab.status === 'approved' ? (
            <PartnerCard
              icon="list_alt"
              title={tr('Tests & reports', 'পরীক্ষা ও রিপোর্ট')}
              sub={tr('Test requests from prescriptions, sample taken, give report', 'প্রেসক্রিপশন থেকে আসা পরীক্ষা, নমুনা নেওয়া, রিপোর্ট দেওয়া')}
            />
          ) : (
            <section className="card work-partner">
              <span className="work-ic"><Icon name="schedule" /></span>
              <div className="col gap-2 grow">
                <h3 className="t-title-m">{statusText(lab.status)}</h3>
                {lab.status === 'pending' ? <span className="t-body-s muted">{tr("You'll appear in the list after approval.", 'অনুমোদনের পর তালিকায় দেখাবে।')}</span> : null}
                <StatusReason status={lab.status} reason={lab.statusReason} />
              </div>
              {lab.status === 'rejected' || lab.status === 'suspended' ? <AppealButton /> : null}
            </section>
          )}
        </div>
        <aside className="card col gap-8 work-side">
          <div className="row top">
            <span className="work-ic"><Icon name="local_hospital" /></span>
            <div className="col gap-4 grow">
              <h3 className="t-title-m">{lab.name}</h3>
              <span className="t-label-l c-primary">{statusText(lab.status)}</span>
            </div>
            <StatusTag status={lab.status} />
          </div>
          <span className="icon-line"><Icon name="call" /><span className="num">{num(lab.phone)}</span></span>
          {place ? <span className="icon-line"><Icon name="location_on" /><span>{place}</span></span> : null}
          {lab.hours ? <span className="icon-line"><Icon name="schedule" /><span>{lab.hours}</span></span> : null}
          {lab.homeCollection ? <span className="icon-line"><Icon name="home_health" /><span>{tr('Collects samples from home', 'বাসা থেকে নমুনা নেয়')}</span></span> : null}
          <span className="icon-line"><Icon name="badge" /><span>{tr('DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর')}: {lab.licenseNo}</span></span>
          {lab.status !== 'suspended' ? (
            <>
              <hr className="divider" />
              <Button variant="danger-text" disabled={busy} onClick={() => { setDelErr(null); setDel(true); }}>{tr('Remove my lab', 'প্যাথলজি মুছে ফেলুন')}</Button>
            </>
          ) : null}
        </aside>
      </div>
      {del ? (
        <ConfirmDialog
          title={tr('Remove your lab?', 'প্যাথলজি মুছে ফেলবেন?')}
          text={tr('It will disappear from the list. You can add it again later (it will need approval again).', 'তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।')}
          confirm={tr('Remove', 'মুছে ফেলুন')}
          danger
          busy={busy}
          error={delErr}
          onConfirm={remove}
          onClose={() => setDel(false)}
        />
      ) : null}
    </Workspace>
  );
}

export function LabForm() {
  const s = useSession();
  const nav = useNavigate();
  const gate = useWorkGate();
  const existing = s.work.lab as Lab | null;
  const title = existing ? tr('My lab', 'আমার প্যাথলজি') : tr('Add my lab', 'আমার প্যাথলজি যোগ করুন');
  return (
    <FormPage title={title} backTo="/work/lab">
      {gate ?? <LabEditor key={existing?.id ?? 'new'} existing={existing} onDone={(flash) => nav('/work/lab', { state: { flash } })} />}
    </FormPage>
  );
}

interface Hospital { id: string; nameEn: string; nameBn: string; district: string; upazilaId: number | null; address: string }

function LabEditor({ existing, onDone }: { existing: Lab | null; onDone: (flash: string) => void }) {
  const s = useSession();
  const nav = useNavigate();
  const after = useAfterChange();
  const p = s.profile;
  const [facilityId, setFacilityId] = useState<string | null>(existing?.facilityId ?? null);
  const [name, setName] = useState(existing?.name ?? '');
  const [place, setPlace] = useState<AreaValue | null>(existing ? { district: existing.district, upazilaId: existing.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
  const [address, setAddress] = useState(existing?.address ?? '');
  const [license, setLicense] = useState(existing?.licenseNo ?? '');
  const [phone, setPhone] = useState(existing?.phone ?? p?.phone ?? '');
  const [hours, setHours] = useState(existing?.hours ?? '');
  const [homeCollection, setHomeCollection] = useState(existing?.homeCollection ?? false);
  const [docs, setDocs] = useState<Record<string, string>>({});
  const [agreed, setAgreed] = useState(!!existing);
  const [pick, setPick] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function save() {
    const specs = labDocs();
    const mobile = normalizePhone(phone);
    const e = !name.trim() ? tr('Write the centre name', 'সেন্টারের নাম লিখুন')
      : !place ? tr('Choose a district', 'জেলা বেছে নিন')
        : !address.trim() ? tr('Write the address', 'ঠিকানা লিখুন')
          : !license.trim() ? tr('Enter the DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর দিন')
            : !mobile ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
              : !existing && missingDocs(specs, docs) ? missingDocs(specs, docs)
                : !agreed ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
    setErr(e);
    if (e || !place) return;
    setBusy(true);
    try {
      const r = await call<{ pending?: boolean }>('lab_save', {
        facilityId, name: name.trim(), district: place.district, upazilaId: place.upazilaId ?? null, address: address.trim(), licenseNo: license.trim(),
        phone: mobile, hours: hours.trim(), homeCollection, termsAccepted: true, docs,
      });
      after();
      onDone(r.pending ? sentForApproval('lab') : tr('Saved', 'সেভ হয়েছে'));
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
      <div className="col gap-6">
        <Button variant="outline" icon="local_hospital" className="work-self-start work-wrap-btn" onClick={() => setPick(true)}>
          {facilityId ? name : tr('Choose from the DGHS list', 'স্বাস্থ্য অধিদপ্তরের তালিকা থেকে বেছে নিন')}
        </Button>
        <span className="t-body-s muted">{tr('Not in the list? Write the details below.', 'তালিকায় না পেলে নিচে নিজে লিখুন।')}</span>
      </div>
      <TextInput label={tr('Centre name', 'সেন্টারের নাম')} value={name} maxLength={120} onChange={(e) => { setName(e.target.value); setFacilityId(null); }} />
      <AreaField label={tr('Area', 'এলাকা')} value={place} onChange={(v) => { setPlace(v); setFacilityId(null); }} />
      <TextInput label={tr('Address', 'ঠিকানা')} value={address} maxLength={200} onChange={(e) => setAddress(e.target.value)} />
      <div className="work-two">
        <TextInput label={tr('DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর')} value={license} maxLength={60} onChange={(e) => setLicense(e.target.value)} />
        <TextInput label={tr('Mobile number', 'মোবাইল নম্বর')} inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value.replace(/[^\d+০-৯]/g, '').slice(0, 20))} />
      </div>
      <TextInput label={tr('Hours (e.g. 8 AM – 10 PM)', 'সময় (যেমন: সকাল ৮টা – রাত ১০টা)')} value={hours} maxLength={80} onChange={(e) => setHours(e.target.value)} />
      <Switch checked={homeCollection} onChange={setHomeCollection} label={tr('Collect samples from home', 'বাসা থেকে নমুনা নিই')} />
      <DocPhotosField specs={labDocs()} photos={docs} onChange={setDocs} note={existing ? PAPERS_CHANGED() : null} />
      <TermsCheck checked={agreed} onChange={setAgreed} />
      <ErrorText>{err}</ErrorText>
      <div className="form-actions">
        <Button variant="text" disabled={busy} onClick={() => nav('/work/lab')}>{tr('Cancel', 'বাতিল')}</Button>
        <Button busy={busy} onClick={save}>{tr('Save', 'সেভ করুন')}</Button>
      </div>
      {pick ? (
        <HospitalPicker
          district={place?.district ?? null}
          onClose={() => setPick(false)}
          onPick={(h) => {
            setFacilityId(h.id);
            setName(isBangla() && h.nameBn ? h.nameBn : h.nameEn);
            setPlace({ district: h.district, upazilaId: h.upazilaId });
            if (!address.trim()) setAddress(h.address);
            setPick(false);
          }}
        />
      ) : null}
    </>
  );
}

/** স্বাস্থ্য অধিদপ্তরের তালিকা থেকে সেন্টার (জেলা ধরে, নাম দিয়ে খোঁজা) */
function HospitalPicker({ district, onPick, onClose }: { district: string | null; onPick: (h: Hospital) => void; onClose: () => void }) {
  const [dist, setDist] = useState<AreaValue | null>(district ? { district, upazilaId: null } : null);
  const [q, setQ] = useState('');
  const res = useCall<{ items: Hospital[] }>('hospitals', { district: dist?.district }, { enabled: !!dist, staleTime: 10 * 60_000 });
  const list = useMemo(() => {
    const t = bnNorm(q.trim());
    const all = res.data?.items ?? [];
    return (t ? all.filter((h) => bnNorm(h.nameBn).includes(t) || h.nameEn.toLowerCase().includes(t)) : all).slice(0, 200);
  }, [res.data, q]);
  return (
    <Dialog onClose={onClose} title={tr('Choose from the DGHS list', 'স্বাস্থ্য অধিদপ্তরের তালিকা থেকে বেছে নিন')}>
      <AreaField label={tr('District', 'জেলা')} value={dist} onChange={(v) => setDist({ district: v.district, upazilaId: null })} />
      <input className="input" placeholder={tr('Search by name', 'নাম দিয়ে খুঁজুন')} aria-label={tr('Search by name', 'নাম দিয়ে খুঁজুন')} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="col gap-2 work-pick-list">
        {!dist ? null : res.isLoading ? <Loading /> : res.error ? <ErrorText>{res.error.message}</ErrorText> : !list.length ? (
          <p className="t-body-m muted" style={{ padding: 16 }}>{tr('No match', 'মেলেনি')}</p>
        ) : list.map((h) => (
          <ListRow
            key={h.id}
            icon="local_hospital"
            title={isBangla() && h.nameBn ? h.nameBn : h.nameEn}
            sub={[isBangla() ? h.nameEn : h.nameBn, h.address].filter(Boolean).join(' · ')}
            onClick={() => onPick(h)}
            trailing={<span />}
          />
        ))}
      </div>
    </Dialog>
  );
}
